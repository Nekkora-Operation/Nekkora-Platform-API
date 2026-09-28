package com.nekkora.platform.mfa;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.session.SessionRepository;

/**
 * SQL de MFA (contrato, §3). As primitivas de consumo sao UPDATE ... WHERE <ainda disponivel> RETURNING, sob o
 * lock da conta e na ordem conta -> desafio -> fator -> codigo -> sessoes (§3.2).
 */
public final class MfaRepository {

    public static final short PURPOSE_VERIFY = 1;
    public static final short PURPOSE_ENROLL = 2;

    public static final short EV_ENROLLED = 1;
    public static final short EV_RECOVERY_CODE = 2;
    public static final short EV_RESET_ADMIN = 3;
    public static final short EV_CHALLENGE_LOCKED = 5;
    public static final short EV_STEP_UP = 6;

    public record Challenge(UUID id, UUID userId, short purpose, String credentialHash, String candidateHash, String csrfToken,
                          int attempts, boolean active) {
    }

    public record Factor(UUID id, SecretCipher.Encrypted secret, boolean confirmed) {
    }

    public UUID createChallenge(JdbcClient j, UUID org, UUID user, String tokenHash, short purpose, String credentialHash,
                             String candidateHash, String csrfToken, Duration validity) {
        return j.sql("""
                INSERT INTO public.auth_challenges (organization_id, user_id, token_hash, purpose, credential_hash, pending_password_hash,
                                                    csrf_token, created_at, expires_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, t.agora, t.agora + ?::interval FROM (SELECT clock_timestamp() AS agora) t
                RETURNING challenge_id
                """).param(org).param(user).param(tokenHash).param(purpose).param(credentialHash).param(candidateHash)
                .param(csrfToken).param(SessionRepository.interval(validity)).query(UUID.class).single();
    }

    /** Leitura sem lock (filtro de CSRF do contexto de desafio). */
    public Optional<Challenge> byToken(JdbcClient j, UUID org, String tokenHash) {
        return j.sql(SELECT_CHALLENGE + " WHERE organization_id = ? AND token_hash = ?").param(org).param(tokenHash).query(this::challenge).optional();
    }

    /** Trava o desafio (depois do lock da conta). */
    public Optional<Challenge> lock(JdbcClient j, UUID challenge) {
        return j.sql(SELECT_CHALLENGE + " WHERE challenge_id = ? FOR UPDATE").param(challenge).query(this::challenge).optional();
    }

    public Optional<UUID> challengeUser(JdbcClient j, UUID org, String tokenHash) {
        return j.sql("SELECT user_id FROM public.auth_challenges WHERE organization_id = ? AND token_hash = ?").param(org).param(tokenHash)
                .query(UUID.class).optional();
    }

    private static final String SELECT_CHALLENGE = """
            SELECT challenge_id, user_id, purpose, credential_hash, pending_password_hash, csrf_token, attempts,
                   (consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > clock_timestamp() AND attempts < 5) AS ativo
              FROM public.auth_challenges
            """;

    private Challenge challenge(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Challenge(rs.getObject("challenge_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getShort("purpose"),
                rs.getString("credential_hash"), rs.getString("pending_password_hash"), rs.getString("csrf_token"),
                rs.getInt("attempts"), rs.getBoolean("ativo"));
    }

    /** Consome o desafio (uso unico): 1 = consumido agora; 0 = ja consumido/invalidado/expirado/esgotado. */
    public int consumeChallenge(JdbcClient j, UUID challenge) {
        return j.sql("""
                UPDATE public.auth_challenges SET consumed_at = clock_timestamp(), credential_hash = NULL, pending_password_hash = NULL
                 WHERE challenge_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL AND expires_at > clock_timestamp() AND attempts < 5
                """).param(challenge).update();
    }

    /** Codigo errado: incrementa tentativas; devolve o novo total. */
    public int recordAttempt(JdbcClient j, UUID challenge) {
        return j.sql("UPDATE public.auth_challenges SET attempts = attempts + 1 WHERE challenge_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL RETURNING attempts")
                .param(challenge).query(Integer.class).optional().orElse(5);
    }

    public int invalidate(JdbcClient j, UUID challenge) {
        return j.sql("""
                UPDATE public.auth_challenges SET invalidated_at = clock_timestamp(), credential_hash = NULL, pending_password_hash = NULL
                 WHERE challenge_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL
                """).param(challenge).update();
    }

    /** Invalida todos os desafios pendentes da conta (exceto {@code manter}). */
    public int invalidateForAccount(JdbcClient j, UUID user, UUID keep) {
        return j.sql("""
                UPDATE public.auth_challenges SET invalidated_at = clock_timestamp(), credential_hash = NULL, pending_password_hash = NULL
                 WHERE user_id = ? AND consumed_at IS NULL AND invalidated_at IS NULL AND challenge_id IS DISTINCT FROM ?
                """).param(user).param(keep).update();
    }

    /** Fator nao desabilitado (confirmado ou pendente), travado. */
    public Optional<Factor> activeFactor(JdbcClient j, UUID user) {
        return j.sql("""
                SELECT factor_id, secret_ciphertext, secret_nonce, key_id, confirmed_at IS NOT NULL AS confirmado
                  FROM public.user_mfa_factors WHERE user_id = ? AND disabled_at IS NULL FOR UPDATE
                """).param(user).query((rs, n) -> new Factor(rs.getObject("factor_id", UUID.class),
                new SecretCipher.Encrypted(rs.getBytes("secret_ciphertext"), rs.getBytes("secret_nonce"), rs.getString("key_id")),
                rs.getBoolean("confirmado"))).optional();
    }

    /** Grava (ou substitui) o fator PENDENTE da conta. */
    public void storePendingFactor(JdbcClient j, UUID org, UUID user, SecretCipher.Encrypted c) {
        int n = j.sql("""
                UPDATE public.user_mfa_factors SET secret_ciphertext = ?, secret_nonce = ?, key_id = ?
                 WHERE user_id = ? AND disabled_at IS NULL AND confirmed_at IS NULL
                """).param(c.ciphertext()).param(c.nonce()).param(c.keyId()).param(user).update();
        if (n == 0) {
            j.sql("""
                    INSERT INTO public.user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id)
                    VALUES (?, ?, 1, ?, ?, ?)
                    """).param(org).param(user).param(c.ciphertext()).param(c.nonce()).param(c.keyId()).update();
        }
    }

    public int confirm(JdbcClient j, UUID factor, long step) {
        return j.sql("UPDATE public.user_mfa_factors SET confirmed_at = clock_timestamp(), last_used_step = ? WHERE factor_id = ? AND confirmed_at IS NULL AND disabled_at IS NULL")
                .param(step).param(factor).update();
    }

    /** Anti-replay (RFC 6238): aceita o passo so se maior que o ultimo aceito. 1 = aceito; 0 = reutilizado/antigo. */
    public int consumeStep(JdbcClient j, UUID factor, long step) {
        return j.sql("""
                UPDATE public.user_mfa_factors SET last_used_step = ?
                 WHERE factor_id = ? AND confirmed_at IS NOT NULL AND disabled_at IS NULL AND (last_used_step IS NULL OR last_used_step < ?)
                """).param(step).param(factor).param(step).update();
    }

    public void storeRecoveryCodes(JdbcClient j, UUID org, UUID user, UUID factor, List<String> hashes) {
        for (String h : hashes) {
            j.sql("INSERT INTO public.user_mfa_recovery_codes (organization_id, user_id, factor_id, code_hash) VALUES (?, ?, ?, ?)")
                    .param(org).param(user).param(factor).param(h).update();
        }
    }

    /** Uso unico do codigo de recuperacao: 1 = consumido agora. */
    public int consumeRecoveryCode(JdbcClient j, UUID user, UUID factor, String hash) {
        return j.sql("""
                UPDATE public.user_mfa_recovery_codes SET used_at = clock_timestamp()
                 WHERE user_id = ? AND factor_id = ? AND code_hash = ? AND used_at IS NULL AND invalidated_at IS NULL
                """).param(user).param(factor).param(hash).update();
    }

    /** Reset: desabilita o fator e invalida os codigos nao usados. */
    public int disableFactor(JdbcClient j, UUID user) {
        j.sql("UPDATE public.user_mfa_recovery_codes SET invalidated_at = clock_timestamp() WHERE user_id = ? AND used_at IS NULL AND invalidated_at IS NULL")
                .param(user).update();
        return j.sql("UPDATE public.user_mfa_factors SET disabled_at = clock_timestamp() WHERE user_id = ? AND disabled_at IS NULL")
                .param(user).update();
    }

    public void recordEvent(JdbcClient j, UUID org, UUID user, UUID actor, short kind, UUID correlation) {
        recordEvent(j, org, user, actor, kind, correlation, null);
    }

    /** {@code reason}: motivo codificado, obrigatorio no RESET_BY_ADMIN (016, D5); nulo nos demais tipos. */
    public void recordEvent(JdbcClient j, UUID org, UUID user, UUID actor, short kind, UUID correlation, Short reason) {
        j.sql("INSERT INTO public.mfa_events (organization_id, user_id, actor_user_id, event_type, correlation_id, reason_code) VALUES (?, ?, ?, ?, ?, ?)")
                .param(org).param(user).param(actor).param(kind).param(correlation).param(reason).update();
    }
}
