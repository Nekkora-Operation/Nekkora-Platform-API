package com.nekkora.platform.identity;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.core.db.IdentityTransactions.Mode;
import com.nekkora.core.db.IdentityTransactions.TokenSessao;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.ClientIpResolver;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.auth.crypto.RecoveryCodes;
import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.auth.crypto.Totp;
import com.nekkora.platform.identity.IdentitySessionResolver.IdentityPrincipal;
import com.nekkora.platform.limits.Limits;

/**
 * Segundo fator OPCIONAL (029, missao PLATFORM-LOCAL-002, politica aprovada pela titular em 2026-11-02): para quem NUNCA
 * confirmou fator, a reconfirmacao da senha (5 min, na mesma sessao) substitui o step-up por TOTP nas acoes sensiveis;
 * e a ativacao voluntaria do MFA pelas configuracoes da conta. Quem tem ou ja teve fator nao passa por aqui: a funcao da
 * 029 recusa a reconfirmacao e a ativacao (403), e o TOTP continua exigido.
 *
 * <p>Mesmas regras dos servicos de credencial: R12 e R8 antes do Argon2; o hash vigente sai de
 * {@code identity_resolve_login} (a Platform nao le {@code identity_credentials}); Argon2 FORA de transacao; a funcao da
 * 029 exige que a credencial vigente ainda seja esse hash, sob o lock da identidade. Senha errada: R8 (falha de senha) e
 * 403. O log nunca registra e-mail, senha, codigo, token nem hash.
 */
public final class IdentityOptionalMfaService {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityOptionalMfaService.class);

    /** Sessao de organizacao vinculada ja validada pelo filtro (mesma forma do step-up vinculado). */
    public record OrgSession(UUID organizationId, UUID sessionId, UUID identitySessionId) {
    }

    /** Fim da ativacao: sessao nova (com MFA) e os codigos de recuperacao, mostrados uma unica vez. */
    public record Activated(String token, String csrfToken, OffsetDateTime expiresAt, List<String> recoveryCodes) {
    }

    /** Estado do segundo fator da identidade: ativo, e como a sessao atual prova a identidade nas acoes sensiveis. */
    public record FactorState(boolean mfaEnabled, String stepUpMethod) {
    }

    private record Owner(UUID identityId, String email, String limiterKey) {
    }

    private record Outcome(String rule, boolean wrongCode) {
        static final Outcome DONE = new Outcome(null, false);
    }

    private static final class ActivationRollback extends RuntimeException {
        final transient Outcome outcome;

        ActivationRollback(Outcome outcome) {
            super(outcome.rule(), null, false, false);
            this.outcome = outcome;
        }
    }

    private final IdentityTransactions tx;
    private final IdentityAuthRepository auth;
    private final PasswordHasher hasher;
    private final SecretCipher cipher;
    private final IdentityHmac hmac;
    private final Limits limits;
    private final Duration sessionAbsolute;

    public IdentityOptionalMfaService(IdentityTransactions tx, IdentityAuthRepository auth, PasswordHasher hasher, SecretCipher cipher,
                                      IdentityHmac hmac, Limits limits, Duration sessionAbsolute) {
        this.tx = tx;
        this.auth = auth;
        this.hasher = hasher;
        this.cipher = cipher;
        this.hmac = hmac;
        this.limits = limits;
        this.sessionAbsolute = sessionAbsolute;
    }

    // ------------------------------------------------------------------ estado

    /** {@code GET /api/v1/me/session}: fator ativo e o metodo de step-up da sessao (totp | password). */
    public FactorState state(IdentityPrincipal who, String sessionToken) {
        if (who == null || !Tokens.isValidFormat(sessionToken)) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        Optional<FactorState> s = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_ONLY, (id, j) -> {
            if (!id.referencia().filter(who.sessionId()::equals).isPresent()) {
                return null;
            }
            boolean withoutMfa = auth.sessionWithoutMfa(j, who.sessionId()).orElse(false);
            return new FactorState(auth.hasConfirmedFactor(j, id.identityId()), withoutMfa ? "password" : "totp");
        });
        return s.orElseThrow(() -> ApiException.unauthenticated("sessao_identidade"));
    }

    // ------------------------------------------------------------------ reconfirmacao da senha

    /** {@code POST /api/v1/auth/password-confirm}: reconfirmacao na sessao de identidade. 204; recusa 403; sessao caiu 401. */
    public void confirmPassword(IdentityPrincipal who, String sessionToken, char[] password, String ip) {
        reconfirm(who, sessionToken, password, ip, null, null);
    }

    /**
     * {@code POST /api/v1/o/{org}/auth/password-confirm}: reconfirmacao na sessao de organizacao VINCULADA, com o cookie da
     * sessao de identidade de origem EXATA (como o step-up vinculado). Sessao de organizacao do login por organizacao: 403.
     */
    public void confirmPasswordForOrganization(OrgSession s, String identityToken, char[] password, String ip) {
        if (s.identitySessionId() == null || !Tokens.isValidFormat(identityToken)) {
            LOG.info("evento=identidade.reconfirmacao_senha_negada org={} regra=sem_sessao_de_identidade", s.organizationId());
            throw ApiException.forbidden("step_up");
        }
        Optional<IdentityPrincipal> who = tx.inIdentity(new TokenSessao(identityToken), Mode.READ_ONLY, (id, j) -> id.referencia()
                .filter(s.identitySessionId()::equals)
                .map(ref -> new IdentityPrincipal(ref, id.identityId(), null, false))
                .orElse(null));
        if (who.isEmpty()) {
            LOG.info("evento=identidade.reconfirmacao_senha_negada org={} regra=sessao_de_origem_divergente", s.organizationId());
            throw ApiException.forbidden("step_up");
        }
        reconfirm(who.get(), identityToken, password, ip, s.organizationId(), s.sessionId());
    }

    private void reconfirm(IdentityPrincipal who, String sessionToken, char[] password, String ip, UUID org, UUID orgSession) {
        limits.identityRoutes(ip);
        Owner o = owner(who, sessionToken);
        if (who.restricted()) {
            throw refused(new Outcome("sessao_restrita", false), o, ip, "reconfirmacao_senha");
        }
        limits.identityBeforeAuthentication(o.limiterKey(), ip, false);
        Optional<IdentityAuthRepository.Login> l = tx.preAuthentication(Mode.READ_ONLY, j -> auth.resolveLogin(j, IdentityHmac.normalize(o.email())));
        String hash = l.filter(x -> x.identityId().equals(o.identityId())).map(IdentityAuthRepository.Login::passwordHash).orElse(null);
        boolean ok;
        if (hash == null) {
            hasher.verifyDummy(password == null ? new char[0] : password);
            ok = false;
        } else {
            ok = password != null && bytesUtf8(password) <= PasswordHasher.MAX_BYTES_LOGIN && hasher.verify(password, hash);
        }
        if (!ok) {
            limits.identityPasswordFailure(o.limiterKey(), ip);
            throw refused(new Outcome("senha", false), o, ip, "reconfirmacao_senha");
        }
        Outcome r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
            if (!id.identityId().equals(o.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()) {
                return new Outcome("sessao", false);
            }
            if (auth.reconfirmPassword(j, sessionToken, hash, org, orgSession)) {
                return Outcome.DONE;
            }
            // recusa sem efeito: sessao caiu (401); identidade com fator, sessao com MFA, senha trocada no intervalo ou
            // sessao de organizacao fora da regra (403)
            return new Outcome(auth.sessionStillValid(j, who.sessionId()) && auth.identityActive(j, o.identityId()) ? "sem_elegibilidade" : "sessao", false);
        }).orElse(new Outcome("sessao", false));
        if (r.rule() != null) {
            throw refused(r, o, ip, "reconfirmacao_senha");
        }
        LOG.info("evento=identidade.senha_reconfirmada identidade={} org={} ip={}", o.limiterKey(), org == null ? "-" : org,
                ClientIpResolver.truncated(ip));
    }

    // ------------------------------------------------------------------ ativacao voluntaria do MFA

    /**
     * {@code POST /api/v1/auth/mfa/activate/start}: fator pendente novo pela funcao da 017 (029: prova 'ativacao', senha
     * reconfirmada ha ate 5 min); devolve a URI otpauth. Sem a prova, com fator ou sessao com MFA: 403.
     */
    public String startActivation(IdentityPrincipal who, String sessionToken, String ip) {
        limits.identityRoutes(ip);
        Owner o = owner(who, sessionToken);
        limits.identityBeforeAuthentication(o.limiterKey(), ip, true);
        byte[] secret = Totp.newSecret();
        try {
            SecretCipher.Encrypted enc = cipher.encryptForIdentity(secret, o.identityId());
            Outcome r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
                if (!id.identityId().equals(o.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()) {
                    return new Outcome("sessao", false);
                }
                if (!auth.sessionWithoutMfa(j, who.sessionId()).orElse(false)) {
                    return new Outcome("sessao_com_mfa", false);
                }
                if (auth.enrollPendingForSession(j, sessionToken, enc).isPresent()) {
                    return Outcome.DONE;
                }
                return new Outcome(auth.sessionStillValid(j, who.sessionId()) && auth.identityActive(j, o.identityId()) ? "sem_prova" : "sessao", false);
            }).orElse(new Outcome("sessao", false));
            if (r.rule() != null) {
                throw refused(r, o, ip, "ativacao_mfa");
            }
            LOG.info("evento=identidade.ativacao_mfa_iniciada identidade={} ip={}", o.limiterKey(), ClientIpResolver.truncated(ip));
            return Totp.uri(secret, o.email());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /**
     * {@code POST /api/v1/auth/mfa/activate/confirm}: TOTP do pendente; a funcao da 029 confirma o fator, grava os codigos,
     * revoga TODAS as sessoes da identidade e cria a sessao nova com MFA (o cookie e trocado pelo controller).
     */
    public Activated confirmActivation(IdentityPrincipal who, String sessionToken, String code, String ip) {
        limits.identityRoutes(ip);
        Owner o = owner(who, sessionToken);
        limits.identityBeforeAuthentication(o.limiterKey(), ip, true);
        List<String> codes = RecoveryCodes.generate();
        List<String> hashes = codes.stream().map(RecoveryCodes::normalize).map(RecoveryCodes::hash).toList();
        String token = Tokens.newToken();
        String csrf = Tokens.newToken();
        OffsetDateTime[] expires = new OffsetDateTime[1];
        Outcome r;
        try {
            r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
                if (!id.identityId().equals(o.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()
                        || !auth.lockIdentity(j, o.identityId())) {
                    return new Outcome("sessao", false);
                }
                // pendente lido SOB o lock da identidade; o TOTP e conferido no segredo deste fator, e e este id que vai a funcao
                Optional<IdentityAuthRepository.Factor> pending = auth.factor(j, o.identityId(), false);
                if (pending.isEmpty()) {
                    return new Outcome(auth.sessionStillValid(j, who.sessionId()) && auth.identityActive(j, o.identityId()) ? "sem_fator_pendente" : "sessao", false);
                }
                OptionalLong step = verifyTotp(pending.get(), o.identityId(), code);
                if (step.isEmpty()) {
                    return new Outcome("codigo", true);
                }
                OffsetDateTime e = auth.sessionExpiry(j, sessionAbsolute);
                IdentityAuthRepository.Completion c = auth.activateMfa(j, sessionToken, pending.get().id(), hashes, step.getAsLong(),
                        Tokens.sha256Hex(token), csrf, e);
                if (!c.created()) {
                    return new Outcome("SESSAO_INVALIDA".equals(c.status()) ? "sessao" : "ativacao_" + c.status(), false);
                }
                // sem aviso por e-mail nesta versao (nao ha modelo de "MFA ativado"; a auditoria e o evento 20). A
                // reconferencia do passo, depois das esperas da funcao, e a ultima coisa antes do commit
                if (!stillAccepted(step.getAsLong())) {
                    throw new ActivationRollback(new Outcome("passo_vencido", false));
                }
                expires[0] = e;
                return Outcome.DONE;
            }).orElse(new Outcome("sessao", false));
        } catch (ActivationRollback e) {
            r = e.outcome;
        }
        if (r.rule() != null) {
            throw refused(r, o, ip, "ativacao_mfa");
        }
        LOG.info("evento=identidade.mfa_ativado identidade={} ip={}", o.limiterKey(), ClientIpResolver.truncated(ip));
        return new Activated(token, csrf, expires[0], codes);
    }

    // ------------------------------------------------------------------ apoio

    private Owner owner(IdentityPrincipal who, String sessionToken) {
        if (who == null || !Tokens.isValidFormat(sessionToken)) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        Optional<Owner> o = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_ONLY, (id, j) -> id.referencia().filter(who.sessionId()::equals)
                .flatMap(s -> auth.email(j, id.identityId()))
                .map(e -> new Owner(id.identityId(), e, hmac.global(e)))
                .orElse(null));
        if (o.isEmpty()) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        return o.get();
    }

    private ApiException refused(Outcome r, Owner o, String ip, String event) {
        if (r.wrongCode()) {
            limits.identityMfaFailure(o.limiterKey(), ip);
        }
        LOG.info("evento=identidade.{}_negada identidade={} ip={} regra={}", event, o.limiterKey(), ClientIpResolver.truncated(ip), r.rule());
        return "sessao".equals(r.rule()) ? ApiException.unauthenticated("sessao_identidade") : ApiException.forbidden("step_up");
    }

    private static boolean stillAccepted(long step) {
        long now = Totp.step(Instant.now());
        return step >= now - 1 && step <= now + 1;
    }

    private OptionalLong verifyTotp(IdentityAuthRepository.Factor f, UUID identity, String code) {
        byte[] secret = cipher.decryptForIdentity(f.secret(), identity);
        try {
            return Totp.verify(secret, code, Instant.now());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static int bytesUtf8(char[] c) {
        return StandardCharsets.UTF_8.encode(CharBuffer.wrap(c)).remaining();
    }
}
