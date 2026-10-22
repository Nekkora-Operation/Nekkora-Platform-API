package com.nekkora.platform.identity;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.auth.crypto.RecoveryCodes;
import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.auth.crypto.Totp;
import com.nekkora.platform.identity.IdentitySessionResolver.IdentityPrincipal;
import com.nekkora.platform.limits.Limits;
import com.nekkora.platform.mail.MailSender;

/**
 * Credenciais da identidade pela SESSAO de identidade (missao PLATFORM-API-IDENTIDADE-002): substituicao do fator
 * (DC-17.13), nos dois caminhos que a 017 autoriza com a prova {@code troca}:
 * <ul>
 * <li>A, troca de aparelho: sessao comum com step-up de ate 5 min ({@code POST /api/v1/auth/step-up});</li>
 * <li>B, fator perdido: sessao RESTRITA nascida de senha + codigo de recuperacao (T0), dentro de 15 min da marca.</li>
 * </ul>
 * Rotas: {@code /api/v1/auth/mfa/enroll/start} e {@code /confirm} no modo sessao (o modo desafio continua no
 * {@link IdentityLoginService}). Nenhum papel de organizacao entra aqui: so a propria sessao de identidade.
 *
 * <p>Inicio: {@code identity_mfa_enroll_pending} no modo sessao cria o fator pendente novo (descartando o pendente
 * anterior) e devolve a URI otpauth. Conclusao, numa so transacao READ COMMITTED com o contexto da sessao, na ordem global
 * da 017: identidade {@code FOR UPDATE} -> fator pendente lido sob o lock -> TOTP conferido no segredo DESTE fator ->
 * {@code identity_mfa_replace} com o id exato dele (trava sessao, fator antigo, fator novo, codigos e demais sessoes, mede
 * a hora depois dos locks e reconfere a prova) -> passo gravado no fator novo (anti-replay: o mesmo codigo nao serve
 * depois para step-up nem login) -> no fim, depois de todas as esperas, sessao e janela do passo reconferidas; fora delas,
 * rollback integral (fatores, codigos, restricao, revogacoes e evento).
 *
 * <p>Respostas: sem sessao valida, inclusive a que caiu durante as esperas: 401, sem efeito. Sem prova ({@code troca}),
 * sem fator pendente, codigo errado, replay ou passo vencido nas esperas: 403 generico, sem efeito; codigo errado conta em
 * R8m (identidade global), como o step-up. O aviso ao titular sai DEPOIS do commit; a falha dele nao desfaz a troca.
 * Log sem e-mail, codigo, token, segredo nem hash.
 *
 * <p>Troca de senha ({@code POST /api/v1/auth/password}) e regeneracao independente dos codigos
 * ({@code POST /api/v1/auth/mfa/recovery-codes}): so sessao COMUM com step-up recente (o step-up da identidade so aceita
 * TOTP). As funcoes {@code identity_password_change} (017) e {@code identity_recovery_codes_regenerate} (019) reconferem a
 * prova {@code step_up} depois dos locks e fazem tudo num so comando: a troca de senha so vale se o hash vigente ainda for
 * o que o Argon2 conferiu, invalida os desafios pendentes, revoga as DEMAIS sessoes e grava o evento 12; a regeneracao
 * invalida o conjunto vivo e grava o novo (evento 15). Sessao restrita: 403 antes de qualquer Argon2. Senha atual errada:
 * 403, conta em R8 (identidade global). Avisos ao titular depois do commit. O re-hash do login nao muda.
 */
public final class IdentityCredentialService {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityCredentialService.class);

    /** Fim da substituicao: codigos novos, mostrados uma unica vez, e a sessao ja sem restricao. */
    public record Replaced(List<String> recoveryCodes, boolean restricted) {
    }

    private final IdentityTransactions tx;
    private final IdentityAuthRepository auth;
    private final SecretCipher cipher;
    private final IdentityHmac hmac;
    private final Limits limits;
    private final MailSender mail;
    private final PasswordHasher hasher;
    private final PasswordRule passwordRule;

    public IdentityCredentialService(IdentityTransactions tx, IdentityAuthRepository auth, SecretCipher cipher, IdentityHmac hmac, Limits limits,
                                     MailSender mail, PasswordHasher hasher, PasswordRule passwordRule) {
        this.tx = tx;
        this.auth = auth;
        this.cipher = cipher;
        this.hmac = hmac;
        this.limits = limits;
        this.mail = mail;
        this.hasher = hasher;
        this.passwordRule = passwordRule;
    }

    /** Desfecho da transacao: concluido, ou recusa com a regra (e se conta como codigo errado). */
    private record Outcome(String rule, boolean wrongCode) {
        static final Outcome DONE = new Outcome(null, false);
    }

    /** Reconferencia final reprovada depois das escritas: desfaz a transacao inteira. */
    private static final class ReplaceRollback extends RuntimeException {
        final transient Outcome outcome;

        ReplaceRollback(Outcome outcome) {
            super(outcome.rule(), null, false, false);
            this.outcome = outcome;
        }
    }

    /** Dono da sessao: identidade, e-mail (rotulo da URI otpauth e destinatario do aviso) e a chave dos limitadores. */
    private record Owner(UUID identityId, String email, String limiterKey) {
    }

    // ------------------------------------------------------------------ substituicao: inicio

    /** POST /api/v1/auth/mfa/enroll/start, modo sessao: fator pendente novo; devolve a URI otpauth. */
    public String startReplacement(IdentityPrincipal who, String sessionToken, String ip) {
        limits.identityRoutes(ip);
        Owner o = owner(who, sessionToken);
        limits.identityBeforeAuthentication(o.limiterKey(), ip, true);
        byte[] secret = Totp.newSecret();
        try {
            SecretCipher.Encrypted enc = cipher.encryptForIdentity(secret, o.identityId());
            Optional<Outcome> r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
                if (!id.identityId().equals(o.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()) {
                    return new Outcome("sessao", false);
                }
                if (auth.enrollPendingForSession(j, sessionToken, enc).isPresent()) {
                    return Outcome.DONE;
                }
                // recusa da funcao (zero efeito): a sessao caiu (401) ou falta a prova de troca (403)
                return new Outcome(auth.sessionStillValid(j, who.sessionId()) ? "sem_prova" : "sessao", false);
            });
            Outcome out = r.orElse(new Outcome("sessao", false));
            if (out.rule() != null) {
                throw refused(out, o, ip, "troca_fator_inicio");
            }
            LOG.info("evento=identidade.troca_fator_iniciada identidade={} restrita={} ip={}", o.limiterKey(), who.restricted(),
                    ClientIpResolver.truncated(ip));
            return Totp.uri(secret, o.email());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    // ------------------------------------------------------------------ substituicao: conclusao (T1)

    /** POST /api/v1/auth/mfa/enroll/confirm, modo sessao: TOTP do fator pendente novo; o banco conclui a troca. */
    public Replaced confirmReplacement(IdentityPrincipal who, String sessionToken, String code, String ip) {
        limits.identityRoutes(ip);
        Owner o = owner(who, sessionToken);
        limits.identityBeforeAuthentication(o.limiterKey(), ip, true);
        List<String> codes = RecoveryCodes.generate();
        List<String> hashes = codes.stream().map(RecoveryCodes::normalize).map(RecoveryCodes::hash).toList();
        UUID correlation = correlation();
        Outcome r;
        try {
            r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
                if (!id.identityId().equals(o.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()
                        || !auth.lockIdentity(j, o.identityId())) {
                    return new Outcome("sessao", false);
                }
                // fator pendente lido SOB o lock da identidade: inicio, conclusao, verificacao e troca concorrentes travam a
                // identidade antes; o TOTP e conferido no segredo deste fator, e e este id que vai para a funcao
                Optional<IdentityAuthRepository.Factor> pending = auth.factor(j, o.identityId(), false);
                if (pending.isEmpty()) {
                    return new Outcome(auth.sessionStillValid(j, who.sessionId()) ? "sem_fator_pendente" : "sessao", false);
                }
                OptionalLong step = verifyTotp(pending.get(), o.identityId(), code);
                if (step.isEmpty()) {
                    return new Outcome("codigo", true);
                }
                if (!auth.replaceFactor(j, sessionToken, pending.get().id(), hashes)) {
                    return new Outcome(auth.sessionStillValid(j, who.sessionId()) ? "sem_prova" : "sessao", false);
                }
                // o fator novo acabou de ser confirmado, sem passo gravado: o passo do codigo de confirmacao passa a ser o
                // ultimo aceito nele (o mesmo codigo nao serve para step-up nem login). Outra quantidade de linhas e
                // impossivel com a identidade travada: desfaz
                if (auth.consumeStep(j, pending.get().id(), step.getAsLong()) != 1) {
                    throw new IllegalStateException("fator novo confirmado sem o passo da confirmacao");
                }
                // a funcao espera locks da sessao, dos fatores, dos codigos e das demais sessoes DEPOIS do TOTP: sessao e
                // passo sao reconferidos agora, antes do commit
                if (!auth.sessionStillValid(j, who.sessionId())) {
                    throw new ReplaceRollback(new Outcome("sessao_vencida", false));
                }
                if (!stillAccepted(step.getAsLong())) {
                    throw new ReplaceRollback(new Outcome("passo_vencido", false));
                }
                return Outcome.DONE;
            }).orElse(new Outcome("sessao", false));
        } catch (ReplaceRollback e) {
            r = e.outcome;
        }
        if (r.rule() != null) {
            throw refused(r, o, ip, "troca_fator");
        }
        LOG.info("evento=identidade.fator_substituido identidade={} restrita_antes={} ip={} correlacao={}", o.limiterKey(), who.restricted(),
                ClientIpResolver.truncated(ip), correlation);
        notice(MailSender.Kind.NOTICE_MFA_REPLACED, o.email());
        return new Replaced(codes, false);
    }

    // ------------------------------------------------------------------ troca de senha

    /**
     * POST /api/v1/auth/password. Ordem: R12 -> sessao (401) e restricao (403) -> politica da senha nova (400) -> R8 ->
     * hash vigente por {@code identity_resolve_login} (a Platform nao le {@code identity_credentials}) -> Argon2 da senha
     * atual FORA de transacao (errada: R8 e 403) -> hash novo -> uma transacao com {@code identity_password_change}.
     */
    public void changePassword(IdentityPrincipal who, String sessionToken, char[] current, char[] newPassword, String ip) {
        limits.identityRoutes(ip);
        Owner o = owner(who, sessionToken);
        if (who.restricted()) {
            throw refused(new Outcome("sessao_restrita", false), o, ip, "troca_senha");
        }
        passwordRule.enforce(newPassword);
        limits.identityBeforeAuthentication(o.limiterKey(), ip, false);
        Optional<IdentityAuthRepository.Login> l = tx.preAuthentication(Mode.READ_ONLY, j -> auth.resolveLogin(j, IdentityHmac.normalize(o.email())));
        String hash = l.filter(x -> x.identityId().equals(o.identityId())).map(IdentityAuthRepository.Login::passwordHash).orElse(null);
        boolean ok;
        if (hash == null) {
            hasher.verifyDummy(current == null ? new char[0] : current);
            ok = false;
        } else {
            ok = current != null && bytesUtf8(current) <= PasswordHasher.MAX_BYTES_LOGIN && hasher.verify(current, hash);
        }
        if (!ok) {
            limits.identityPasswordFailure(o.limiterKey(), ip);
            throw refused(new Outcome("senha_atual", false), o, ip, "troca_senha");
        }
        String newHash = hasher.generate(newPassword);
        Outcome r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
            if (!id.identityId().equals(o.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()) {
                return new Outcome("sessao", false);
            }
            if (auth.changePassword(j, sessionToken, hash, newHash)) {
                return Outcome.DONE;
            }
            // recusa sem efeito: sessao caiu (401); sem step-up, restrita no intervalo ou senha trocada no intervalo (403)
            return new Outcome(auth.sessionStillValid(j, who.sessionId()) ? "sem_prova_ou_senha_alterada" : "sessao", false);
        }).orElse(new Outcome("sessao", false));
        if (r.rule() != null) {
            throw refused(r, o, ip, "troca_senha");
        }
        LOG.info("evento=identidade.senha_trocada identidade={} ip={}", o.limiterKey(), ClientIpResolver.truncated(ip));
        notice(MailSender.Kind.NOTICE_PASSWORD_CHANGED, o.email());
    }

    // ------------------------------------------------------------------ regeneracao dos codigos

    /** POST /api/v1/auth/mfa/recovery-codes: conjunto novo (mostrado uma unica vez); o anterior e invalidado no mesmo comando. */
    public List<String> regenerateRecoveryCodes(IdentityPrincipal who, String sessionToken, String ip) {
        limits.identityRoutes(ip);
        Owner o = owner(who, sessionToken);
        if (who.restricted()) {
            throw refused(new Outcome("sessao_restrita", false), o, ip, "regeneracao_codigos");
        }
        List<String> codes = RecoveryCodes.generate();
        List<String> hashes = codes.stream().map(RecoveryCodes::normalize).map(RecoveryCodes::hash).toList();
        Outcome r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
            if (!id.identityId().equals(o.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()) {
                return new Outcome("sessao", false);
            }
            if (auth.regenerateRecoveryCodes(j, sessionToken, hashes)) {
                return Outcome.DONE;
            }
            return new Outcome(auth.sessionStillValid(j, who.sessionId()) ? "sem_prova" : "sessao", false);
        }).orElse(new Outcome("sessao", false));
        if (r.rule() != null) {
            throw refused(r, o, ip, "regeneracao_codigos");
        }
        LOG.info("evento=identidade.codigos_regenerados identidade={} ip={}", o.limiterKey(), ClientIpResolver.truncated(ip));
        notice(MailSender.Kind.NOTICE_RECOVERY_CODES_REGENERATED, o.email());
        return codes;
    }

    // ------------------------------------------------------------------ apoio

    /** Aviso ao titular DEPOIS do commit: a falha do transporte nao desfaz a alteracao de seguranca. */
    private void notice(MailSender.Kind kind, String email) {
        try {
            mail.send(kind, null, email, null);
        } catch (RuntimeException e) {
            LOG.warn("evento=identidade.aviso_falhou tipo={} correlacao={}", kind, RequestContext.correlation());
        }
    }

    private static int bytesUtf8(char[] c) {
        return StandardCharsets.UTF_8.encode(CharBuffer.wrap(c)).remaining();
    }

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
        return "sessao".equals(r.rule()) || "sessao_vencida".equals(r.rule()) ? ApiException.unauthenticated("sessao_identidade")
                : ApiException.forbidden(event);
    }

    /** Mesma tolerancia de {@code Totp.verify}, no instante medido AGORA. */
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

    private static UUID correlation() {
        try {
            return UUID.fromString(RequestContext.correlation());
        } catch (IllegalArgumentException | NullPointerException e) {
            return UUID.randomUUID();
        }
    }
}
