package com.nekkora.platform.identity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.platform.auth.crypto.SecretCipher;

/**
 * Entrada geral (Missao 1, desenho §6.3), SEM ESTADO: desafio, fator, sessao e funcoes da 017. As leituras e as escritas
 * diretas rodam com o contexto {@code nekkora.identity_id} ja definido pelo {@code IdentityTransactions} (RLS por
 * identidade). Toda transicao de estado do fator, o consumo do desafio e o nascimento da sessao sao das funcoes
 * SECURITY DEFINER da 017 ({@code identity_mfa_enroll_pending}, {@code identity_mfa_confirm_first},
 * {@code identity_login_complete}); a Platform so as chama.
 */
public final class IdentityAuthRepository {

    /** identity_auth_challenges.purpose (017). */
    public static final short PURPOSE_VERIFY = 1;
    public static final short PURPOSE_ENROLL = 2;
    /** identity_events.event_type (017). */
    public static final short EV_LOGOUT = 10;
    public static final short EV_STEP_UP = 13;
    /** Step-up por organizacao em sessao vinculada (P4), com a organizacao alvo. */
    public static final short EV_ORG_STEP_UP = 14;
    /** CHECK da 017: tentativas entre 0 e 5. */
    public static final int MAX_ATTEMPTS = 5;

    public record Login(UUID identityId, short status, boolean emailVerified, String passwordHash, boolean hasConfirmedFactor) {
        public boolean eligible() {
            return status == IdentityRepository.ACTIVE && emailVerified;
        }
    }

    public record Challenge(UUID id, short purpose, String csrfToken, int attempts) {
    }

    public record Factor(UUID id, SecretCipher.Encrypted secret) {
    }

    public record SessionRow(UUID id, UUID identityId, String csrfToken, boolean restricted) {
    }

    /** Sessao de identidade lida SOB O LOCK: ativa (nao revogada, no prazo, com MFA) e restrita (DC-17.13). */
    public record LockedSession(boolean active, boolean restricted) {
    }

    /** {@code identity_resolve_login}: chave exata pelo e-mail normalizado, sem contexto (funcao do dono). */
    public Optional<Login> resolveLogin(JdbcClient j, String normalizedEmail) {
        return j.sql("SELECT identity_id, status, email_verified_at IS NOT NULL, password_hash, tem_fator_confirmado FROM public.identity_resolve_login(?)")
                .param(normalizedEmail)
                .query((rs, n) -> new Login(rs.getObject(1, UUID.class), rs.getShort(2), rs.getBoolean(3), rs.getString(4), rs.getBoolean(5)))
                .optional();
    }

    /** Trava a identidade do contexto (ordem 017: identidade antes de desafio, fator e sessao). */
    public boolean lockIdentity(JdbcClient j, UUID identity) {
        return j.sql("SELECT 1 FROM public.identities WHERE identity_id = ? FOR UPDATE").param(identity).query(Integer.class).optional().isPresent();
    }

    /** Identidade ACTIVE com e-mail verificado (lida depois do lock da identidade). */
    public boolean identityActive(JdbcClient j, UUID identity) {
        return j.sql("SELECT EXISTS (SELECT 1 FROM public.identities WHERE identity_id = ? AND status = ? AND email_verified_at IS NOT NULL)")
                .param(identity).param(IdentityRepository.ACTIVE).query(Boolean.class).single();
    }

    /**
     * Trava a sessao de identidade (ordem 017: depois da identidade) e a devolve com o estado medido DEPOIS do lock, ou
     * vazio se a linha nao existe no contexto. Aquisicao e avaliacao sao dois comandos: num {@code SELECT ... FOR UPDATE}
     * a lista de saida e calculada antes da espera pelo lock (so e recalculada se a linha mudou), entao um
     * {@code clock_timestamp()} ali nao mede o prazo depois da espera.
     */
    public Optional<LockedSession> lockSession(JdbcClient j, UUID sessionId) {
        if (j.sql("SELECT 1 FROM public.identity_sessions WHERE identity_session_id = ? FOR UPDATE").param(sessionId).query(Integer.class).optional().isEmpty()) {
            return Optional.empty();
        }
        return j.sql("""
                SELECT revoked_at IS NULL AND expires_at > clock_timestamp() AND mfa_verified_at IS NOT NULL,
                       (recovery_used_at IS NOT NULL AND recovery_cleared_at IS NULL)
                  FROM public.identity_sessions WHERE identity_session_id = ?
                """).param(sessionId)
                .query((rs, n) -> new LockedSession(rs.getBoolean(1), rs.getBoolean(2))).optional();
    }

    /** Sessao ja travada nesta transacao ainda ativa e no prazo, pelo relogio do banco AGORA (reconferencia antes do commit). */
    public boolean sessionStillValid(JdbcClient j, UUID sessionId) {
        return j.sql("SELECT EXISTS (SELECT 1 FROM public.identity_sessions WHERE identity_session_id = ? AND revoked_at IS NULL AND expires_at > clock_timestamp())")
                .param(sessionId).query(Boolean.class).single();
    }

    /**
     * Anti-replay do step-up: o passo so avanca. Devolve as linhas alteradas; 0 = passo ja usado (ou anterior) ou fator
     * que deixou de estar confirmado e habilitado. A trigger da 017 recusa recuo de qualquer forma.
     */
    public int consumeStep(JdbcClient j, UUID factorId, long step) {
        return j.sql("""
                UPDATE public.identity_mfa_factors SET last_used_step = ?
                 WHERE factor_id = ? AND confirmed_at IS NOT NULL AND disabled_at IS NULL AND (last_used_step IS NULL OR last_used_step < ?)
                """).param(step).param(factorId).param(step).update();
    }

    /** Step-up na sessao de identidade, pelo relogio do banco; a trigger da 017 recusa recuo, futuro e sessao revogada. */
    public int recordStepUp(JdbcClient j, UUID sessionId) {
        return j.sql("UPDATE public.identity_sessions SET mfa_step_up_at = clock_timestamp() WHERE identity_session_id = ? AND revoked_at IS NULL")
                .param(sessionId).update();
    }

    public boolean hasConfirmedFactor(JdbcClient j, UUID identity) {
        return j.sql("SELECT EXISTS (SELECT 1 FROM public.identity_mfa_factors WHERE identity_id = ? AND confirmed_at IS NOT NULL AND disabled_at IS NULL)")
                .param(identity).query(Boolean.class).single();
    }

    public Optional<String> email(JdbcClient j, UUID identity) {
        return j.sql("SELECT email FROM public.identities WHERE identity_id = ?").param(identity).query(String.class).optional();
    }

    /**
     * Desafio novo (so o hash do token). {@code credentialHash} = hash vigente que o Argon2 conferiu; {@code candidateHash}
     * = re-hash pendente, aplicado pela funcao de conclusao. created_at e expires_at saem do mesmo instante da transacao
     * (a 017 limita o prazo a 5 min contados de created_at).
     */
    public void createChallenge(JdbcClient j, UUID identity, String tokenHash, short purpose, String credentialHash, String candidateHash,
                                String csrfToken, Duration validity) {
        j.sql("""
                INSERT INTO public.identity_auth_challenges (identity_id, token_hash, purpose, credential_hash, pending_password_hash, csrf_token, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, now() + ?::interval)
                """).param(identity).param(tokenHash).param(purpose).param(credentialHash).param(candidateHash).param(csrfToken)
                .param(validity.toSeconds() + " seconds").update();
    }

    /** Desafio vivo pelo id (o lookup ja o achou vivo; aqui, com o contexto, o proposito, o CSRF e as tentativas). */
    public Optional<Challenge> challenge(JdbcClient j, UUID challengeId) {
        return j.sql("""
                SELECT challenge_id, purpose, csrf_token, attempts FROM public.identity_auth_challenges
                 WHERE challenge_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > clock_timestamp()
                """).param(challengeId)
                .query((rs, n) -> new Challenge(rs.getObject(1, UUID.class), rs.getShort(2), rs.getString(3), rs.getInt(4))).optional();
    }

    /**
     * Codigo errado: incrementa as tentativas (so de desafio vivo e abaixo do teto) e devolve o total; no teto, o desafio e
     * invalidado (com a limpeza dos hashes que o CHECK da 017 exige). Desafio que nao esta mais vivo: devolve o teto.
     */
    public int recordWrongAttempt(JdbcClient j, UUID challengeId) {
        int n = j.sql("""
                UPDATE public.identity_auth_challenges SET attempts = attempts + 1
                 WHERE challenge_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL AND attempts < ?
                RETURNING attempts
                """).param(challengeId).param(MAX_ATTEMPTS).query(Integer.class).optional().orElse(MAX_ATTEMPTS);
        if (n >= MAX_ATTEMPTS) {
            j.sql("""
                    UPDATE public.identity_auth_challenges SET invalidated_at = clock_timestamp(), credential_hash = NULL, pending_password_hash = NULL
                     WHERE challenge_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL
                    """).param(challengeId).update();
        }
        return n;
    }

    /** Fator pendente habilitado (cadastro) ou confirmado habilitado (verificacao), com o segredo cifrado. */
    public Optional<Factor> factor(JdbcClient j, UUID identity, boolean confirmed) {
        return j.sql("SELECT factor_id, secret_ciphertext, secret_nonce, key_id FROM public.identity_mfa_factors WHERE identity_id = ? AND disabled_at IS NULL AND "
                        + (confirmed ? "confirmed_at IS NOT NULL" : "confirmed_at IS NULL"))
                .param(identity)
                .query((rs, n) -> new Factor(rs.getObject(1, UUID.class), new SecretCipher.Encrypted(rs.getBytes(2), rs.getBytes(3), rs.getString(4))))
                .optional();
    }

    /**
     * Vinculo do TOTP ao fator: depois de {@code identity_login_complete}, o passo aceito tem de estar gravado no MESMO
     * fator que a aplicacao conferiu, ainda confirmado e habilitado. A funcao da 017 nao recebe o id do fator; esta
     * leitura, na mesma transacao e sob o lock da identidade, fecha essa lacuna.
     */
    public boolean stepRecordedOn(JdbcClient j, UUID factorId, long step) {
        return j.sql("SELECT EXISTS (SELECT 1 FROM public.identity_mfa_factors WHERE factor_id = ? AND last_used_step = ? AND confirmed_at IS NOT NULL AND disabled_at IS NULL)")
                .param(factorId).param(step).query(Boolean.class).single();
    }

    /** {@code identity_mfa_enroll_pending} pelo desafio de cadastro: o id do fator pendente novo, ou vazio se recusado. */
    public Optional<UUID> enrollPending(JdbcClient j, String challengeToken, SecretCipher.Encrypted secret) {
        return Optional.ofNullable(j.sql("SELECT public.identity_mfa_enroll_pending(?, NULL, ?, ?, ?)")
                .param(challengeToken).param(secret.ciphertext()).param(secret.nonce()).param(secret.keyId()).query(UUID.class).single());
    }

    /** Prazo de uma sessao nova pelo relogio do BANCO e da transacao ({@code now()}), para caber no CHECK de 12 h da 017. */
    public OffsetDateTime sessionExpiry(JdbcClient j, Duration absolute) {
        return j.sql("SELECT now() + ?::interval").param(absolute.toSeconds() + " seconds").query(OffsetDateTime.class).single();
    }

    public record Completion(String status, UUID sessionId) {
        public boolean created() {
            return "SESSAO_CRIADA".equals(status) && sessionId != null;
        }
    }

    /** {@code identity_mfa_confirm_first}: consumo do desafio, fator, codigos, re-hash e sessao num so comando. */
    public Completion confirmFirst(JdbcClient j, String challengeToken, UUID factorId, List<String> codeHashes, long step,
                                   String sessionHash, String csrf, OffsetDateTime expires) {
        return j.sql("SELECT o_status, o_identity_session_id FROM public.identity_mfa_confirm_first(?, ?, ?::text[], ?, ?, ?, ?)")
                .param(challengeToken).param(factorId).param(pgArray(codeHashes)).param(step).param(sessionHash).param(csrf).param(expires)
                .query((rs, n) -> new Completion(rs.getString(1), rs.getObject(2, UUID.class))).single();
    }

    /** {@code identity_login_complete}: 'totp' com o passo ou 'recuperacao' com o hash do codigo. */
    public Completion loginComplete(JdbcClient j, String challengeToken, Long step, String recoveryCodeHash,
                                    String sessionHash, String csrf, OffsetDateTime expires) {
        return j.sql("SELECT o_status, o_identity_session_id FROM public.identity_login_complete(?, ?, ?, ?, ?, ?, ?)")
                .param(challengeToken).param(recoveryCodeHash == null ? "totp" : "recuperacao").param(step).param(recoveryCodeHash)
                .param(sessionHash).param(csrf).param(expires)
                .query((rs, n) -> new Completion(rs.getString(1), rs.getObject(2, UUID.class))).single();
    }

    /** Sessao de identidade ativa do contexto (o lookup ja a achou ativa e no prazo): CSRF e restricao (DC-17.13). */
    public Optional<SessionRow> session(JdbcClient j, UUID sessionId) {
        return j.sql("""
                SELECT identity_session_id, identity_id, csrf_token, (recovery_used_at IS NOT NULL AND recovery_cleared_at IS NULL)
                  FROM public.identity_sessions
                 WHERE identity_session_id = ? AND revoked_at IS NULL AND expires_at > clock_timestamp()
                """).param(sessionId)
                .query((rs, n) -> new SessionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getBoolean(4))).optional();
    }

    /** Logout: revoga a sessao (idempotente) e devolve as linhas alteradas. */
    public int revokeSession(JdbcClient j, UUID sessionId) {
        return j.sql("UPDATE public.identity_sessions SET revoked_at = greatest(clock_timestamp(), created_at) WHERE identity_session_id = ? AND revoked_at IS NULL")
                .param(sessionId).update();
    }

    public void event(JdbcClient j, UUID identity, short type, UUID correlation) {
        event(j, identity, type, null, correlation);
    }

    /** Evento com organizacao alvo (nula nos eventos sem organizacao). A FK da organizacao pede FOR KEY SHARE nela. */
    public void event(JdbcClient j, UUID identity, short type, UUID targetOrganization, UUID correlation) {
        j.sql("INSERT INTO public.identity_events (identity_id, event_type, target_organization_id, correlation_id) VALUES (?, ?, ?, ?)")
                .param(identity).param(type).param(targetOrganization).param(correlation).update();
    }

    private static String pgArray(List<String> hexes) {
        return "{" + String.join(",", hexes) + "}";
    }
}
