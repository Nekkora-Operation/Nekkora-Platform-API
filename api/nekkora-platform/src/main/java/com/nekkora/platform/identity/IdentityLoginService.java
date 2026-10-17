package com.nekkora.platform.identity;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.core.db.IdentityTransactions.EmailCadastro;
import com.nekkora.core.db.IdentityTransactions.Mode;
import com.nekkora.core.db.IdentityTransactions.TokenDesafio;
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
import com.nekkora.platform.limits.Limits;
import com.nekkora.platform.mail.MailSender;

/**
 * Entrada geral da identidade confirmada (Missao 1, desenho §6.3, DC-17.3 e DC-17.13): senha, desafio, cadastro do
 * primeiro fator, verificacao por TOTP ou codigo de recuperacao, e logout. So este fluxo; cadastro, organizacoes, troca
 * de sessao e substituicao de fator ficam em servicos proprios.
 *
 * <p>Mesmas regras do {@code AuthService} por organizacao:
 * <ul>
 * <li>busca em transacao SOMENTE LEITURA; Argon2 FORA de transacao; escrita so depois, em transacao nova;</li>
 * <li>codigo errado e RESULTADO (a tentativa e confirmada) e so depois se responde 401;</li>
 * <li>resposta generica (401 {@code unauthenticated}); o log nunca registra e-mail, senha, codigo, token, hash nem
 * "identidade inexistente".</li>
 * </ul>
 * O banco faz o resto: o consumo do desafio, a conferencia da credencial vigente (senha obsoleta nao gera sessao), o
 * anti-replay do passo TOTP, o uso do codigo de recuperacao com a sessao RESTRITA (T0 da DC-17.13) e o nascimento da
 * sessao acontecem num so comando das funcoes da 017. A aplicacao confere o TOTP e o formato do codigo; o banco nao
 * verifica Argon2 nem TOTP (limite declarado no desenho, §13.1).
 */
public final class IdentityLoginService {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityLoginService.class);

    public record LoginResult(String status, String challengeToken, String csrfToken) {
    }

    public record NewSession(String token, String csrfToken, OffsetDateTime expiresAt, List<String> recoveryCodes, boolean restricted) {
    }

    private final IdentityTransactions tx;
    private final IdentityAuthRepository auth;
    private final PasswordHasher hasher;
    private final SecretCipher cipher;
    private final IdentityHmac hmac;
    private final Limits limits;
    private final MailSender mail;
    private final Duration challengeValidity;
    private final Duration sessionAbsolute;

    public IdentityLoginService(IdentityTransactions tx, IdentityAuthRepository auth, PasswordHasher hasher, SecretCipher cipher, IdentityHmac hmac,
                                Limits limits, MailSender mail, Duration challengeValidity, Duration sessionAbsolute) {
        this.tx = tx;
        this.auth = auth;
        this.hasher = hasher;
        this.cipher = cipher;
        this.hmac = hmac;
        this.limits = limits;
        this.mail = mail;
        this.challengeValidity = challengeValidity;
        this.sessionAbsolute = sessionAbsolute;
    }

    // ------------------------------------------------------------------ passo 1: senha

    /** POST /api/v1/auth/login. Qualquer falha de credencial ou de elegibilidade: 401 generico. */
    public LoginResult login(String email, char[] password, String ip) {
        limits.identityRoutes(ip);
        String identity = hmac.global(email);
        limits.identityBeforeAuthentication(identity, ip, false);
        if (email == null || password == null || bytesUtf8(password) > PasswordHasher.MAX_BYTES_LOGIN) {
            limits.identityPasswordFailure(identity, ip);
            throw denied("entrada", identity, ip);
        }
        String normalized = IdentityHmac.normalize(email);
        Optional<IdentityAuthRepository.Login> found = tx.preAuthentication(Mode.READ_ONLY, j -> auth.resolveLogin(j, normalized));
        boolean passwordOk;
        if (found.isEmpty() || found.get().passwordHash() == null) {
            hasher.verifyDummy(password);
            passwordOk = false;
        } else {
            passwordOk = hasher.verify(password, found.get().passwordHash());
        }
        if (!passwordOk || !found.get().eligible()) {
            limits.identityPasswordFailure(identity, ip);
            throw denied("credencial", identity, ip);
        }
        IdentityAuthRepository.Login l = found.get();
        String candidate = hasher.needsRehash(l.passwordHash()) ? hasher.generate(password) : null;
        String token = Tokens.newToken();
        String csrf = Tokens.newToken();
        Optional<Short> purpose = tx.inIdentity(new EmailCadastro(normalized), Mode.READ_WRITE, (id, j) -> {
            if (!id.identityId().equals(l.identityId()) || !auth.lockIdentity(j, l.identityId())) {
                return null;
            }
            // reconferencia contratada, DEPOIS do lock e antes do INSERT: a mesma identidade, ainda ACTIVE e verificada, e a
            // credencial vigente ainda e o hash que o Argon2 conferiu (suspensao ou troca de senha no intervalo: nada emitido)
            Optional<IdentityAuthRepository.Login> now = auth.resolveLogin(j, normalized);
            if (now.isEmpty() || !now.get().identityId().equals(l.identityId()) || !now.get().eligible()
                    || !Objects.equals(now.get().passwordHash(), l.passwordHash())) {
                return null;
            }
            short p = now.get().hasConfirmedFactor() ? IdentityAuthRepository.PURPOSE_VERIFY : IdentityAuthRepository.PURPOSE_ENROLL;
            auth.createChallenge(j, l.identityId(), Tokens.sha256Hex(token), p, l.passwordHash(), candidate, csrf, challengeValidity);
            return p;
        });
        if (purpose.isEmpty()) {
            throw denied("reconferencia", identity, ip);
        }
        LOG.info("evento=identidade.desafio_emitido identidade={} ip={}", identity, ClientIpResolver.truncated(ip));
        return new LoginResult(purpose.get() == IdentityAuthRepository.PURPOSE_VERIFY ? "mfa_required" : "mfa_enrollment_required", token, csrf);
    }

    // ------------------------------------------------------------------ MFA: cadastro do primeiro fator

    /** POST /api/v1/auth/mfa/enroll/start: fator pendente novo pela funcao da 017; devolve a URI otpauth. */
    public String startEnrollment(String challengeToken, String ip) {
        limits.identityRoutes(ip);
        ChallengeOwner o = challengeOwner(challengeToken, IdentityAuthRepository.PURPOSE_ENROLL);
        limits.identityBeforeAuthentication(o.limiterKey(), ip, true);
        byte[] secret = Totp.newSecret();
        try {
            SecretCipher.Encrypted enc = cipher.encryptForIdentity(secret, o.identityId());
            Optional<UUID> factor = tx.preAuthentication(Mode.READ_WRITE, j -> auth.enrollPending(j, challengeToken, enc));
            if (factor.isEmpty()) {
                throw denied("cadastro_recusado", o.limiterKey(), ip);
            }
            return Totp.uri(secret, o.email());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    /** POST /api/v1/auth/mfa/enroll/confirm: TOTP do fator pendente; o banco confirma, grava os codigos e cria a sessao. */
    public NewSession confirmEnrollment(String challengeToken, String code, String ip) {
        limits.identityRoutes(ip);
        ChallengeOwner o = challengeOwner(challengeToken, IdentityAuthRepository.PURPOSE_ENROLL);
        limits.identityBeforeAuthentication(o.limiterKey(), ip, true);
        List<String> codes = RecoveryCodes.generate();
        List<String> hashes = codes.stream().map(RecoveryCodes::normalize).map(RecoveryCodes::hash).toList();
        String token = Tokens.newToken();
        String csrf = Tokens.newToken();
        // uma so transacao: lock da identidade -> fator pendente lido sob o lock -> TOTP conferido depois da espera pela
        // identidade -> conclusao pela funcao com o id EXATO desse fator (a funcao ainda espera desafio, fator e credencial)
        // -> passo reconferido no fim, depois das esperas da funcao; fora da janela, rollback integral
        Completed r = completeUnderLock(challengeToken, o, (j, identity) -> {
            Optional<IdentityAuthRepository.Factor> pending = auth.factor(j, identity, false);
            if (pending.isEmpty()) {
                return Completed.denied("sem_fator_pendente");
            }
            OptionalLong step = verifyTotp(pending.get(), identity, code);
            if (step.isEmpty()) {
                return Completed.wrongCode();
            }
            OffsetDateTime e = auth.sessionExpiry(j, sessionAbsolute);
            IdentityAuthRepository.Completion c = auth.confirmFirst(j, challengeToken, pending.get().id(), hashes, step.getAsLong(), Tokens.sha256Hex(token), csrf, e);
            // a funcao ainda espera desafio, fator e credencial DEPOIS desta validacao: o passo e reconferido ao fim, antes do commit
            if (c.created() && !stillAccepted(step.getAsLong())) {
                throw new CompletionRollback("passo_vencido");
            }
            return Completed.of(c, e);
        });
        finish(r, o, challengeToken, ip, "cadastro_fator");
        return new NewSession(token, csrf, r.expires(), codes, false);
    }

    // ------------------------------------------------------------------ MFA: verificacao

    /**
     * POST /api/v1/auth/mfa/verify: TOTP (passo conferido aqui; anti-replay no banco) ou codigo de recuperacao (o banco
     * consome, cria a sessao RESTRITA, revoga as demais sessoes e invalida os demais desafios: T0 da DC-17.13).
     */
    public NewSession verify(String challengeToken, String code, String recoveryCode, String ip) {
        limits.identityRoutes(ip);
        if ((code == null) == (recoveryCode == null)) {
            throw ApiException.invalid("codigo");
        }
        ChallengeOwner o = challengeOwner(challengeToken, IdentityAuthRepository.PURPOSE_VERIFY);
        limits.identityBeforeAuthentication(o.limiterKey(), ip, true);
        String codeHash = null;
        if (recoveryCode != null) {
            String normalized = RecoveryCodes.normalize(recoveryCode);
            if (normalized == null) {
                throw wrongCode(o, challengeToken, ip);
            }
            codeHash = RecoveryCodes.hash(normalized);
        }
        String token = Tokens.newToken();
        String csrf = Tokens.newToken();
        String codeHashFinal = codeHash;
        // uma so transacao: lock da identidade (a troca de fator, identity_mfa_replace, trava a identidade antes: fica de
        // fora deste intervalo) -> fator confirmado lido sob o lock -> TOTP conferido depois da espera pela identidade ->
        // conclusao pela funcao (que ainda espera desafio, credencial e fator) -> no fim, depois dessas esperas, o passo
        // tem de estar na janela e gravado no MESMO fator conferido; senao, rollback integral
        Completed r = completeUnderLock(challengeToken, o, (j, identity) -> {
            Long step = null;
            UUID factorId = null;
            if (codeHashFinal == null) {
                Optional<IdentityAuthRepository.Factor> f = auth.factor(j, identity, true);
                if (f.isEmpty()) {
                    return Completed.denied("sem_fator_confirmado");
                }
                OptionalLong s = verifyTotp(f.get(), identity, code);
                if (s.isEmpty()) {
                    return Completed.wrongCode();
                }
                step = s.getAsLong();
                factorId = f.get().id();
            }
            OffsetDateTime e = auth.sessionExpiry(j, sessionAbsolute);
            IdentityAuthRepository.Completion c = auth.loginComplete(j, challengeToken, step, codeHashFinal, Tokens.sha256Hex(token), csrf, e);
            if (c.created() && step != null) {
                // a funcao ainda espera desafio, credencial e fator DEPOIS desta validacao: o passo e reconferido ao fim
                if (!stillAccepted(step)) {
                    throw new CompletionRollback("passo_vencido");
                }
                if (!auth.stepRecordedOn(j, factorId, step)) {
                    throw new CompletionRollback("fator_divergente");
                }
            }
            return Completed.of(c, e);
        });
        finish(r, o, challengeToken, ip, codeHash != null ? "codigo_recuperacao" : "totp");
        OffsetDateTime expires = r.expires();
        boolean recovery = codeHash != null;
        if (recovery) {
            try {
                mail.send(MailSender.Kind.NOTICE_RECOVERY_CODE_USED, null, o.email(), null);
            } catch (RuntimeException e) {
                LOG.warn("evento=identidade.aviso_falhou tipo=NOTICE_RECOVERY_CODE_USED correlacao={}", RequestContext.correlation());
            }
        }
        return new NewSession(token, csrf, expires, null, recovery);
    }

    // ------------------------------------------------------------------ logout

    /** POST /api/v1/auth/logout: revoga a sessao de identidade do cookie; sem sessao valida, nada (idempotente). */
    public void logout(String sessionToken) {
        if (!Tokens.isValidFormat(sessionToken)) {
            return;
        }
        tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
            // revisao 2 do bloco 3: identidade travada ANTES da sessao (ordem global da 017). Sem isso, a chave estrangeira
            // do evento pediria a identidade DEPOIS de a revogacao segurar a sessao - ciclo com quem trava identidade ->
            // sessao (troca, listagem, funcoes da 017)
            if (id.referencia().isPresent() && auth.lockIdentity(j, id.identityId()) && auth.revokeSession(j, id.referencia().get()) == 1) {
                auth.event(j, id.identityId(), IdentityAuthRepository.EV_LOGOUT, correlation());
            }
            return Boolean.TRUE;
        });
    }

    // ------------------------------------------------------------------ apoio

    /** Dono de um desafio vivo do proposito esperado: identidade, e-mail e a chave do limitador. Senao, 401. */
    private record ChallengeOwner(UUID identityId, UUID challengeId, String email, String limiterKey) {
    }

    private ChallengeOwner challengeOwner(String challengeToken, short purpose) {
        if (!Tokens.isValidFormat(challengeToken)) {
            throw ApiException.unauthenticated("desafio");
        }
        Optional<ChallengeOwner> o = tx.inIdentity(new TokenDesafio(challengeToken), Mode.READ_ONLY, (id, j) -> {
            Optional<IdentityAuthRepository.Challenge> ch = auth.challenge(j, id.referencia().orElse(null));
            Optional<String> email = auth.email(j, id.identityId());
            if (ch.isEmpty() || ch.get().purpose() != purpose || email.isEmpty()) {
                return null;
            }
            return new ChallengeOwner(id.identityId(), ch.get().id(), email.get(), hmac.global(email.get()));
        });
        if (o.isEmpty()) {
            throw ApiException.unauthenticated("desafio");
        }
        return o.get();
    }

    /** Desfecho da transacao de conclusao: sessao criada, codigo errado ou recusa (regra). */
    private record Completed(IdentityAuthRepository.Completion completion, OffsetDateTime expires, boolean wrong, String rule) {
        static Completed of(IdentityAuthRepository.Completion c, OffsetDateTime e) {
            return new Completed(c, e, false, null);
        }

        static Completed wrongCode() {
            return new Completed(null, null, true, null);
        }

        static Completed denied(String rule) {
            return new Completed(null, null, false, rule);
        }
    }

    /**
     * Verificacao final reprovada DEPOIS da funcao da 017 (passo fora da janela depois das esperas dela, ou passo gravado
     * num fator diferente do conferido): desfaz a transacao inteira - sessao, consumo do desafio, avanco ou confirmacao do
     * fator, codigos. Convertida em recusa fora da transacao.
     */
    private static final class CompletionRollback extends RuntimeException {
        final String rule;

        CompletionRollback(String rule) {
            super(rule, null, false, false);
            this.rule = rule;
        }
    }

    /**
     * Janela do TOTP no instante medido AGORA (depois de todas as esperas ja ocorridas na transacao): o passo aceito tem
     * de estar entre agora-1 e agora+1, a mesma tolerancia de {@code Totp.verify}.
     */
    private static boolean stillAccepted(long step) {
        long now = Totp.step(Instant.now());
        return step >= now - 1 && step <= now + 1;
    }

    /**
     * Transacao de conclusao com a identidade TRAVADA antes de qualquer leitura do fator: o trabalho (ler o fator, conferir
     * o TOTP, chamar a funcao da 017) roda inteiro sob esse lock. Identidade que nao trava: recusa.
     */
    private Completed completeUnderLock(String challengeToken, ChallengeOwner o,
                                        java.util.function.BiFunction<org.springframework.jdbc.core.simple.JdbcClient, UUID, Completed> work) {
        try {
            return tx.inIdentity(new TokenDesafio(challengeToken), Mode.READ_WRITE, (id, j) -> {
                if (!id.identityId().equals(o.identityId()) || !auth.lockIdentity(j, o.identityId())) {
                    return Completed.denied("reconferencia");
                }
                return work.apply(j, o.identityId());
            }).orElse(Completed.denied("desafio"));
        } catch (CompletionRollback e) {
            return Completed.denied(e.rule);
        }
    }

    /** Converte o desfecho: codigo errado (inclusive replay ou codigo de recuperacao inexistente) conta tentativa; sucesso zera R8. */
    private void finish(Completed r, ChallengeOwner o, String challengeToken, String ip, String method) {
        if (r.wrong() || (r.completion() != null && "FATOR_RECUSADO".equals(r.completion().status()))) {
            throw wrongCode(o, challengeToken, ip);
        }
        if (r.completion() == null) {
            throw denied(r.rule(), o.limiterKey(), ip);
        }
        if (!r.completion().created()) {
            throw denied("conclusao_" + r.completion().status(), o.limiterKey(), ip);
        }
        limits.identityFullLogin(o.limiterKey(), ip);
        LOG.info("evento=identidade.autenticada metodo={} identidade={} ip={}", method, o.limiterKey(), ClientIpResolver.truncated(ip));
    }

    /** Codigo errado: tentativa registrada (e o desafio invalidado no teto) em transacao confirmada; depois, R8m e 401. */
    private ApiException wrongCode(ChallengeOwner o, String challengeToken, String ip) {
        tx.inIdentity(new TokenDesafio(challengeToken), Mode.READ_WRITE, (id, j) -> auth.recordWrongAttempt(j, o.challengeId()));
        limits.identityMfaFailure(o.limiterKey(), ip);
        return denied("codigo", o.limiterKey(), ip);
    }

    private OptionalLong verifyTotp(IdentityAuthRepository.Factor f, UUID identity, String code) {
        byte[] secret = cipher.decryptForIdentity(f.secret(), identity);
        try {
            return Totp.verify(secret, code, Instant.now());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static ApiException denied(String rule, String identity, String ip) {
        LOG.info("evento=identidade.negada identidade={} ip={} regra={}", identity, ClientIpResolver.truncated(ip), rule);
        return ApiException.unauthenticated(rule);
    }

    private static int bytesUtf8(char[] c) {
        return StandardCharsets.UTF_8.encode(CharBuffer.wrap(c)).remaining();
    }

    private static UUID correlation() {
        try {
            return UUID.fromString(RequestContext.correlation());
        } catch (IllegalArgumentException | NullPointerException e) {
            return UUID.randomUUID();
        }
    }
}
