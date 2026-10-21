package com.nekkora.platform.auth;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.DbErrors;
import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.ClientIpResolver;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.auth.crypto.RecoveryCodes;
import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.auth.crypto.Totp;
import com.nekkora.platform.config.PlatformProperties;
import com.nekkora.platform.account.AccountRepository;
import com.nekkora.platform.account.AccountRepository.Account;
import com.nekkora.platform.limits.Limits;
import com.nekkora.platform.mail.MailSender;
import com.nekkora.platform.mfa.MfaRepository;
import com.nekkora.platform.mfa.MfaRepository.Challenge;
import com.nekkora.platform.mfa.MfaRepository.Factor;
import com.nekkora.platform.session.SessionRepository;
import com.nekkora.platform.session.ValidSession;

/**
 * Fluxos de autenticacao da Platform (contrato 8.6/8.7/8.10, revisao 4; §3 do relatorio da API).
 *
 * <p>Regras que atravessam todos os metodos:
 * <ul>
 *   <li>Busca do login em transacao SOMENTE LEITURA; Argon2 FORA de transacao e sem conexao; escrita so depois,
 *       em nova transacao de escrita (§3.1; contrato §2.1).</li>
 *   <li>Falhas de negocio dentro da transacao sao RESULTADOS, nao excecoes: a transacao e confirmada (ex.: tentativa
 *       errada registrada, desafio invalidado) e so depois se responde 401 (§3.2, passo 3).</li>
 *   <li>Lock da conta primeiro; depois desafio, fator, codigos, sessoes (§3.2).</li>
 *   <li>Respostas genericas; o log nunca registra e-mail, senha, codigo, token, hash nem "conta inexistente".</li>
 * </ul>
 */
public final class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    public record LoginResult(String status, String challengeToken, String csrfToken) {
    }

    public record NewSession(String token, String csrfToken, OffsetDateTime expiresAt, List<String> recoveryCodes) {
    }

    /** Resultado de uma transacao de MFA. */
    private sealed interface Outcome permits Ok, Denied {
    }

    private record Ok(NewSession session, String email, boolean usedRecoveryCode, String uri) implements Outcome {
    }

    /** {@code codigoErrado}: conta como falha de MFA no limitador. */
    private record Denied(String rule, boolean wrongCode) implements Outcome {
    }

    /**
     * Falha de consumo DEPOIS de um segundo fator correto (§3.2, passo 4: o caminho do codigo correto e atomico). Lancada
     * dentro da transacao para que o TransactionTemplate faca ROLLBACK de tudo o que ja foi consumido nela (passo TOTP,
     * codigo de recuperacao, desafio) e convertida em 401 fora dela. O caminho do codigo ERRADO (passo 3) nao usa esta
     * excecao: ele retorna normalmente e a tentativa e confirmada (COMMIT).
     */
    private static final class ConsumptionFailed extends RuntimeException {
        ConsumptionFailed() {
            super("consumo", null, false, false);
        }
    }

    /** Executa a conclusao do MFA; ConsumptionFailed -> transacao desfeita e Denied("consumo"). */
    private static Outcome atomicCompletion(java.util.function.Supplier<Outcome> completion) {
        try {
            return completion.get();
        } catch (ConsumptionFailed e) {
            return new Denied("consumo", false);
        }
    }

    private final OrgTransactions tx;
    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final MfaRepository mfa;
    private final PasswordHasher hasher;
    private final SecretCipher secretCipher;
    private final IdentityHmac hmac;
    private final Limits limits;
    private final MailSender mail;
    private final PlatformProperties.Session cfg;
    private final PlatformProperties.Limits limitsConfig;

    public AuthService(OrgTransactions tx, AccountRepository accounts, SessionRepository sessions, MfaRepository mfa, PasswordHasher hasher,
                       SecretCipher secretCipher, IdentityHmac hmac, Limits limits, MailSender mail, PlatformProperties.Session cfg,
                       PlatformProperties.Limits limitsConfig) {
        this.tx = tx;
        this.accounts = accounts;
        this.sessions = sessions;
        this.mfa = mfa;
        this.hasher = hasher;
        this.secretCipher = secretCipher;
        this.hmac = hmac;
        this.limits = limits;
        this.mail = mail;
        this.cfg = cfg;
        this.limitsConfig = limitsConfig;
    }

    // ------------------------------------------------------------------ login (passo 1: senha)

    public LoginResult login(UUID org, String email, char[] password, String ip) {
        String identity = hmac.fromTime(org, email);
        limits.beforeAuthentication(org, identity, ip, false);
        if (email == null || password == null || bytesUtf8(password) > PasswordHasher.MAX_BYTES_LOGIN) {
            limits.passwordFailure(org, identity, ip);
            throw denied("R_entrada", org, identity, ip);
        }
        Optional<Account> account = tx.inOrganization(org, Mode.READ_ONLY, j -> accounts.byEmail(j, org, IdentityHmac.normalize(email)));
        boolean passwordOk;
        if (account.isEmpty() || account.get().passwordHash() == null) {
            hasher.verifyDummy(password);
            passwordOk = false;
        } else {
            passwordOk = hasher.verify(password, account.get().passwordHash());
        }
        if (!passwordOk || !account.get().eligible()) {
            limits.passwordFailure(org, identity, ip);
            throw denied("senha_ou_conta", org, identity, ip);
        }
        Account c = account.get();
        String candidate = hasher.needsRehash(c.passwordHash()) ? hasher.generate(password) : null;
        String token = Tokens.newToken();
        String csrf = Tokens.newToken();
        short purpose = c.hasConfirmedFactor() ? MfaRepository.PURPOSE_VERIFY : MfaRepository.PURPOSE_ENROLL;
        try {
            tx.inOrganization(org, Mode.READ_WRITE, j -> {
                accounts.lock(j, org, c.userId());
                return mfa.createChallenge(j, org, c.userId(), Tokens.sha256Hex(token), purpose, c.passwordHash(), candidate, csrf, cfg.challenge());
            });
        } catch (DataAccessException e) {
            // conta vinculada entre a leitura e o lock (desenho §4.5, camada 2): a trigger da 017 recusa o desafio (23514).
            // Mesma resposta de credencial invalida, sem contar como senha errada: a senha conferiu
            if (!linkedAccountRefusal(e)) {
                throw e;
            }
            throw denied("senha_ou_conta", org, identity, ip);
        }
        LOG.info("evento=auth.challenge_issued org={} identidade={} ip={}", org, identity, ClientIpResolver.truncated(ip));
        return new LoginResult(purpose == MfaRepository.PURPOSE_VERIFY ? "mfa_required" : "mfa_enrollment_required", token, csrf);
    }

    // ------------------------------------------------------------------ MFA: cadastro

    public String startEnrollment(UUID org, String challengeToken, String ip) {
        String identity = challengeIdentity(org, challengeToken);
        limits.beforeAuthentication(org, identity, ip, true);
        Outcome r = tx.inOrganization(org, Mode.READ_WRITE, j -> {
            Optional<Challenge> d = lockedChallenge(j, org, challengeToken, MfaRepository.PURPOSE_ENROLL);
            if (d.isEmpty()) {
                return new Denied("desafio", false);
            }
            Optional<Account> c = recheck(j, org, d.get());
            if (c.isEmpty()) {
                return new Denied("reconferencia", false);
            }
            Optional<Factor> f = mfa.activeFactor(j, d.get().userId());
            if (f.isPresent() && f.get().confirmed()) {
                mfa.invalidate(j, d.get().id());
                return new Denied("fator_ja_confirmado", false);
            }
            byte[] secret = Totp.newSecret();
            try {
                mfa.storePendingFactor(j, org, d.get().userId(), secretCipher.encrypt(secret, org, d.get().userId()));
                return new Ok(null, c.get().email(), false, Totp.uri(secret, c.get().email()));
            } finally {
                java.util.Arrays.fill(secret, (byte) 0);
            }
        });
        if (r instanceof Denied n) {
            throw denied(n.rule(), org, identity, ip);
        }
        return ((Ok) r).uri();
    }

    public NewSession confirmEnrollment(UUID org, String challengeToken, String code, String ip) {
        String identity = challengeIdentity(org, challengeToken);
        limits.beforeAuthentication(org, identity, ip, true);
        Outcome r = atomicCompletion(() -> tx.inOrganization(org, Mode.READ_WRITE, j -> {
            Optional<Challenge> d = lockedChallenge(j, org, challengeToken, MfaRepository.PURPOSE_ENROLL);
            if (d.isEmpty()) {
                return new Denied("desafio", false);
            }
            Optional<Account> c = recheck(j, org, d.get());
            if (c.isEmpty()) {
                return new Denied("reconferencia", false);
            }
            Optional<Factor> f = mfa.activeFactor(j, d.get().userId());
            if (f.isEmpty() || f.get().confirmed()) {
                mfa.invalidate(j, d.get().id());
                return new Denied("sem_fator_pendente", false);
            }
            OptionalLong step = verifyTotp(f.get(), org, d.get().userId(), code);
            if (step.isEmpty()) {
                return wrongAttempt(j, org, d.get());
            }
            if (mfa.consumeChallenge(j, d.get().id()) != 1 || mfa.confirm(j, f.get().id(), step.getAsLong()) != 1) {
                throw new ConsumptionFailed();
            }
            applyRehash(j, d.get());
            List<String> codes = RecoveryCodes.generate();
            mfa.storeRecoveryCodes(j, org, d.get().userId(), f.get().id(),
                    codes.stream().map(RecoveryCodes::normalize).map(RecoveryCodes::hash).toList());
            mfa.recordEvent(j, org, d.get().userId(), null, MfaRepository.EV_ENROLLED, correlation());
            NewSession s = createSession(j, org, c.get(), codes);
            return new Ok(s, c.get().email(), false, null);
        }));
        return complete(r, org, identity, ip);
    }

    // ------------------------------------------------------------------ MFA: verificacao (TOTP ou codigo de recuperacao)

    public NewSession verify(UUID org, String challengeToken, String code, String recoveryCode, String ip) {
        String identity = challengeIdentity(org, challengeToken);
        limits.beforeAuthentication(org, identity, ip, true);
        Outcome r = atomicCompletion(() -> tx.inOrganization(org, Mode.READ_WRITE, j -> {
            Optional<Challenge> d = lockedChallenge(j, org, challengeToken, MfaRepository.PURPOSE_VERIFY);
            if (d.isEmpty()) {
                return new Denied("desafio", false);
            }
            Optional<Account> c = recheck(j, org, d.get());
            if (c.isEmpty()) {
                return new Denied("reconferencia", false);
            }
            Optional<Factor> f = mfa.activeFactor(j, d.get().userId());
            if (f.isEmpty() || !f.get().confirmed()) {
                mfa.invalidate(j, d.get().id());
                return new Denied("sem_fator_confirmado", false);
            }
            boolean recovery = recoveryCode != null;
            if (recovery) {
                String normalizedCode = RecoveryCodes.normalize(recoveryCode);
                if (normalizedCode == null || mfa.consumeRecoveryCode(j, d.get().userId(), f.get().id(), RecoveryCodes.hash(normalizedCode)) != 1) {
                    return wrongAttempt(j, org, d.get());
                }
            } else {
                OptionalLong step = verifyTotp(f.get(), org, d.get().userId(), code);
                if (step.isEmpty() || mfa.consumeStep(j, f.get().id(), step.getAsLong()) != 1) {
                    return wrongAttempt(j, org, d.get());
                }
            }
            // o segundo fator ja foi consumido acima: se o desafio nao puder mais ser consumido (expirou entre o lock e
            // aqui, por exemplo), desfaz a transacao inteira - o codigo/passo volta a valer e nenhuma sessao nasce
            if (mfa.consumeChallenge(j, d.get().id()) != 1) {
                throw new ConsumptionFailed();
            }
            applyRehash(j, d.get());
            if (recovery) {
                sessions.revokeForAccount(j, d.get().userId(), null);
                mfa.invalidateForAccount(j, d.get().userId(), null);
                mfa.recordEvent(j, org, d.get().userId(), null, MfaRepository.EV_RECOVERY_CODE, correlation());
            }
            return new Ok(createSession(j, org, c.get(), null), c.get().email(), recovery, null);
        }));
        NewSession s = complete(r, org, identity, ip);
        if (((Ok) r).usedRecoveryCode()) {
            mail.send(MailSender.Kind.NOTICE_RECOVERY_CODE_USED, org, ((Ok) r).email(), null);
        }
        return s;
    }

    // ------------------------------------------------------------------ sessao

    /** Logout da sessao atual (idempotente; sem limitador). */
    public void logout(UUID org, String sessionToken) {
        if (!Tokens.isValidFormat(sessionToken)) {
            return;
        }
        tx.inOrganization(org, Mode.READ_WRITE, j -> sessions.revokeByToken(j, org, Tokens.sha256Hex(sessionToken)));
    }

    /** Encerra todas as sessoes da conta, inclusive a atual, e invalida desafios pendentes. */
    public void revokeAll(UUID org, ValidSession s, String sessionToken) {
        boolean ok = tx.inOrganization(org, Mode.READ_WRITE, j -> {
            accounts.lock(j, org, s.userId());
            if (sessions.validate(j, org, Tokens.sha256Hex(sessionToken), false).isEmpty()) {
                return false;
            }
            mfa.invalidateForAccount(j, s.userId(), null);
            sessions.revokeForAccount(j, s.userId(), null);
            return true;
        });
        if (!ok) {
            throw ApiException.unauthenticated("sessao");
        }
    }

    public void stepUp(UUID org, ValidSession s, String sessionToken, String code, String ip) {
        String identity = userIdentity(org, s.userId());
        limits.beforeAuthentication(org, identity, ip, true);
        Outcome r = tx.inOrganization(org, Mode.READ_WRITE, j -> {
            accounts.lock(j, org, s.userId());
            if (sessions.validate(j, org, Tokens.sha256Hex(sessionToken), false).isEmpty()) {
                return new Denied("sessao", false);
            }
            Optional<Factor> f = mfa.activeFactor(j, s.userId());
            if (f.isEmpty() || !f.get().confirmed()) {
                return new Denied("sem_fator", false);
            }
            OptionalLong step = verifyTotp(f.get(), org, s.userId(), code);
            if (step.isEmpty() || mfa.consumeStep(j, f.get().id(), step.getAsLong()) != 1) {
                return new Denied("codigo", true);
            }
            sessions.recordStepUp(j, s.sessionId());
            mfa.recordEvent(j, org, s.userId(), null, MfaRepository.EV_STEP_UP, correlation());
            return new Ok(null, null, false, null);
        });
        if (r instanceof Denied n) {
            if (n.wrongCode()) {
                limits.mfaFailure(org, identity, ip);
            }
            LOG.info("evento=auth.step_up_denied org={} identidade={} regra={}", org, identity, n.rule());
            throw "sessao".equals(n.rule()) ? ApiException.unauthenticated("sessao") : ApiException.forbidden("step_up");
        }
    }

    // ------------------------------------------------------------------ senha

    public void changePassword(UUID org, ValidSession s, String sessionToken, char[] current, char[] newPassword, String ip) {
        // conta vinculada (sessao nascida da troca): a senha local nao autentica nem muda (DC-17.12, desenho §4.5). 403
        // generico, sem Argon2 e sem contar falha; a senha da pessoa e a da identidade
        if (s.isLinked()) {
            LOG.info("evento=auth.troca_senha_negada org={} regra=conta_vinculada", org);
            throw ApiException.forbidden("conta_vinculada");
        }
        PasswordPolicy.enforce(newPassword);
        String identity = userIdentity(org, s.userId());
        limits.beforeAuthentication(org, identity, ip, false);
        String hash = tx.inOrganization(org, Mode.READ_ONLY, j -> accounts.currentHash(j, s.userId()).orElse(null));
        if (current == null || bytesUtf8(current) > PasswordHasher.MAX_BYTES_LOGIN || !hasher.verify(current, hash)) {
            limits.passwordFailure(org, identity, ip);
            throw denied("senha_atual", org, identity, ip);
        }
        String newHash = hasher.generate(newPassword);
        String email;
        try {
            email = tx.inOrganization(org, Mode.READ_WRITE, j -> {
                accounts.lock(j, org, s.userId());
                if (sessions.validate(j, org, Tokens.sha256Hex(sessionToken), false).isEmpty()
                        || !accounts.replaceHashIfUnchanged(j, s.userId(), hash, newHash, true)) {
                    return null;
                }
                mfa.invalidateForAccount(j, s.userId(), null);
                sessions.revokeForAccount(j, s.userId(), s.sessionId());
                return accounts.byId(j, org, s.userId()).map(Account::email).orElse(null);
            });
        } catch (DataAccessException e) {
            // vinculada no intervalo: a trigger da 017 recusa a troca do hash (23514); nada foi alterado
            if (!linkedAccountRefusal(e)) {
                throw e;
            }
            throw ApiException.forbidden("conta_vinculada");
        }
        if (email == null) {
            throw ApiException.unauthenticated("sessao_ou_credencial_alterada");
        }
        mail.send(MailSender.Kind.NOTICE_PASSWORD_CHANGED, org, email, null);
    }

    /** Sempre "aceito" (202 identico). Piso de tempo de resposta fora de transacao. */
    public void requestRecovery(UUID org, String email, String ip) {
        long requestStartNanos = System.nanoTime();
        try {
            String identity = hmac.fromTime(org, email);
            if (email == null || !limits.recoveryAllowed(org, identity, ip)) {
                return;
            }
            Optional<Account> account = tx.inOrganization(org, Mode.READ_ONLY, j -> accounts.byEmail(j, org, IdentityHmac.normalize(email)));
            if (account.isEmpty() || !account.get().eligible()) {
                return;
            }
            String token = Tokens.newToken();
            try {
                tx.inOrganization(org, Mode.READ_WRITE, j -> {
                    accounts.lock(j, org, account.get().userId());
                    j.sql("UPDATE public.password_reset_tokens SET revoked_at = clock_timestamp() WHERE user_id = ? AND consumed_at IS NULL AND revoked_at IS NULL")
                            .param(account.get().userId()).update();
                    return j.sql("""
                            INSERT INTO public.password_reset_tokens (user_id, token_hash, created_at, expires_at)
                            SELECT ?, ?, t.agora, t.agora + ?::interval FROM (SELECT clock_timestamp() AS agora) t
                            """).param(account.get().userId()).param(Tokens.sha256Hex(token))
                            .param(SessionRepository.interval(cfg.passwordRecovery())).update();
                });
            } catch (DataAccessException e) {
                // vinculada entre a leitura e o lock: a trigger da 017 recusa o token (23514). "Nao enviar": a resposta
                // continua 202 identica, com o mesmo piso (DC-17.12)
                if (!linkedAccountRefusal(e)) {
                    throw e;
                }
                return;
            }
            mail.send(MailSender.Kind.PASSWORD_RECOVERY, org, account.get().email(), token);
        } finally {
            floor(requestStartNanos);
        }
    }

    public void confirmRecovery(UUID org, String token, char[] newPassword) {
        if (!Tokens.isValidFormat(token)) {
            throw ApiException.invalid("token");
        }
        PasswordPolicy.enforce(newPassword);
        String newHash = hasher.generate(newPassword);
        boolean ok = tx.inOrganization(org, Mode.READ_WRITE, j -> {
            Optional<UUID> user = j.sql("SELECT user_id FROM public.password_reset_tokens WHERE token_hash = ?")
                    .param(Tokens.sha256Hex(token)).query(UUID.class).optional();
            if (user.isEmpty() || !accounts.lock(j, org, user.get())) {
                return false;
            }
            int consumed = j.sql("""
                    UPDATE public.password_reset_tokens SET consumed_at = clock_timestamp()
                     WHERE token_hash = ? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp()
                    """).param(Tokens.sha256Hex(token)).update();
            if (consumed != 1) {
                return false;
            }
            Optional<String> current = accounts.currentHash(j, user.get());
            if (current.isPresent()) {
                accounts.replaceHashIfUnchanged(j, user.get(), current.get(), newHash, true);
            } else {
                j.sql("INSERT INTO public.user_credentials (user_id, password_hash) VALUES (?, ?)").param(user.get()).param(newHash).update();
            }
            mfa.invalidateForAccount(j, user.get(), null);
            sessions.revokeForAccount(j, user.get(), null);
            return true;
        });
        if (!ok) {
            throw ApiException.invalid("token");
        }
    }

    public void confirmEmail(UUID org, String token) {
        if (!Tokens.isValidFormat(token)) {
            throw ApiException.invalid("token");
        }
        // Achado I-1 do inventario de locks: organizacao -> conta -> token. O UPDATE em users dispara o trigger de presenca
        // de ORG_ADMIN (003), que trava a organizacao; por isso a organizacao vem ANTES da conta e do consumo do token
        // (antes, a ordem era token -> conta -> organizacao, invertida em relacao a suspensao e aos atos da 016).
        boolean ok = tx.inOrganization(org, Mode.READ_WRITE, j -> {
            String hash = Tokens.sha256Hex(token);
            Optional<UUID> user = j.sql("SELECT user_id FROM public.email_verification_tokens WHERE token_hash = ?")
                    .param(hash).query(UUID.class).optional();       // leitura sem lock
            if (user.isEmpty()) {
                return false;
            }
            accounts.lockOrganization(j, org);
            if (!accounts.lock(j, org, user.get())) {
                return false;
            }
            // reconferencia e consumo atomicos, depois dos locks
            Optional<String> email = j.sql("""
                    UPDATE public.email_verification_tokens SET consumed_at = clock_timestamp()
                     WHERE token_hash = ? AND user_id = ? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp()
                    RETURNING target_email
                    """).param(hash).param(user.get()).query(String.class).optional();
            if (email.isEmpty()) {
                return false;
            }
            return j.sql("""
                    UPDATE public.users SET email_verified_at = coalesce(email_verified_at, clock_timestamp())
                     WHERE organization_id = ? AND user_id = ? AND email = ?
                    """).param(org).param(user.get()).param(email.get()).update() == 1;
        });
        if (!ok) {
            throw ApiException.invalid("token");
        }
    }

    // ------------------------------------------------------------------ auxiliares

    /** Lock da conta do desafio e depois do desafio; exige desafio ativo com a finalidade. Sem efeito se nao existir. */
    private Optional<Challenge> lockedChallenge(JdbcClient j, UUID org, String token, short purpose) {
        if (!Tokens.isValidFormat(token)) {
            return Optional.empty();
        }
        Optional<UUID> user = mfa.challengeUser(j, org, Tokens.sha256Hex(token));
        if (user.isEmpty() || !accounts.lock(j, org, user.get())) {
            return Optional.empty();
        }
        Optional<Challenge> d = mfa.byToken(j, org, Tokens.sha256Hex(token)).flatMap(x -> mfa.lock(j, x.id()));
        if (d.isEmpty() || !d.get().active() || d.get().purpose() != purpose) {
            d.ifPresent(x -> mfa.invalidate(j, x.id()));
            return Optional.empty();
        }
        return d;
    }

    /**
     * Reconferencia sob o lock da conta (§3.2, passo 2): credencial IDENTICA a validada no passo 1 e conta,
     * e-mail, organizacao e vinculo ativos. Divergencia invalida o desafio (novo login obrigatorio).
     */
    private Optional<Account> recheck(JdbcClient j, UUID org, Challenge d) {
        Optional<Account> c = accounts.byId(j, org, d.userId());
        if (c.isEmpty() || !c.get().eligible() || !Objects.equals(c.get().passwordHash(), d.credentialHash())) {
            mfa.invalidate(j, d.id());
            return Optional.empty();
        }
        return c;
    }

    /** Codigo errado: registra a tentativa; na 5a invalida e audita. Resultado normal (a transacao e confirmada). */
    private Outcome wrongAttempt(JdbcClient j, UUID org, Challenge d) {
        int n = mfa.recordAttempt(j, d.id());
        if (n >= 5) {
            mfa.invalidate(j, d.id());
            mfa.recordEvent(j, org, d.userId(), null, MfaRepository.EV_CHALLENGE_LOCKED, correlation());
        }
        return new Denied("codigo", true);
    }

    private OptionalLong verifyTotp(Factor f, UUID org, UUID user, String code) {
        byte[] secret = secretCipher.decrypt(f.secret(), org, user);
        try {
            return Totp.verify(secret, code, Instant.now());
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    /** Re-hash so se a credencial continua a mesma (§4.3); a reconferencia ja garantiu isso sob o lock. */
    private void applyRehash(JdbcClient j, Challenge d) {
        if (d.candidateHash() != null) {
            accounts.replaceHashIfUnchanged(j, d.userId(), d.credentialHash(), d.candidateHash(), false);
        }
    }

    private NewSession createSession(JdbcClient j, UUID org, Account c, List<String> codes) {
        String token = Tokens.newToken();
        String csrf = Tokens.newToken();
        UUID id = sessions.create(j, org, c.userId(), c.accountType(), Tokens.sha256Hex(token), csrf);
        OffsetDateTime expires = j.sql("SELECT expires_at FROM public.user_sessions WHERE session_id = ?").param(id).query(OffsetDateTime.class).single();
        return new NewSession(token, csrf, expires, codes);
    }

    private NewSession complete(Outcome r, UUID org, String identity, String ip) {
        if (r instanceof Denied n) {
            if (n.wrongCode()) {
                limits.mfaFailure(org, identity, ip);
            }
            throw denied(n.rule(), org, identity, ip);
        }
        Ok ok = (Ok) r;
        limits.fullLogin(org, identity, ip);
        LOG.info("evento=auth.authenticated org={} identidade={} ip={}", org, identity, ClientIpResolver.truncated(ip));
        return ok.session();
    }

    /** Identidade (HMAC do e-mail) do dono do desafio, para as chaves do limitador; desafio inexistente -> 401. */
    private String challengeIdentity(UUID org, String token) {
        if (!Tokens.isValidFormat(token)) {
            throw ApiException.unauthenticated("desafio");
        }
        String email = tx.inOrganization(org, Mode.READ_ONLY, j -> mfa.challengeUser(j, org, Tokens.sha256Hex(token))
                .flatMap(u -> accounts.byId(j, org, u)).map(Account::email).orElse(null));
        if (email == null) {
            throw ApiException.unauthenticated("desafio");
        }
        return hmac.fromTime(org, email);
    }

    private String userIdentity(UUID org, UUID user) {
        String email = tx.inOrganization(org, Mode.READ_ONLY, j -> accounts.byId(j, org, user).map(Account::email).orElse(null));
        if (email == null) {
            throw ApiException.unauthenticated("sessao");
        }
        return hmac.fromTime(org, email);
    }

    private static ApiException denied(String rule, UUID org, String identity, String ip) {
        // sem motivo que distinga existencia de conta: so a regra generica
        LOG.info("evento=auth.denied org={} identidade={} ip={} regra={}", org, identity, ClientIpResolver.truncated(ip),
                rule.equals("senha_ou_conta") ? "credencial" : rule);
        return ApiException.unauthenticated(rule);
    }

    private void floor(long startNanos) {
        long remaining = limitsConfig.recoveryResponseFloor().toNanos() - (System.nanoTime() - startNanos);
        if (remaining > 0) {
            try {
                Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Recusa das triggers de fechamento da entrada local de conta vinculada (017, §4.5, camada 2): SQLSTATE 23514. */
    private static boolean linkedAccountRefusal(DataAccessException e) {
        return "23514".equals(DbErrors.sqlState(e));
    }

    private static int bytesUtf8(char[] c) {
        return StandardCharsets.UTF_8.encode(CharBuffer.wrap(c)).remaining();
    }

    private static UUID correlation() {
        String c = com.nekkora.core.web.RequestContext.correlation();
        try {
            return UUID.fromString(c);
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }
}
