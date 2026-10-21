package com.nekkora.platform.identity;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Acesso as tabelas de identidade da 017, SEM ESTADO (padrao de account, session e mfa): recebe o {@link JdbcClient} da
 * transacao aberta pelo servico com {@code IdentityTransactions}, que ja definiu o contexto {@code nekkora.identity_id}.
 * Toda leitura e escrita passa pela RLS por identidade e pelos privilegios por coluna da Platform (017, secoes 11 e 12a).
 */
public final class IdentityRepository {

    /** identities.status (017): 1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=CLOSED. */
    public static final short PENDING = 1;
    public static final short ACTIVE = 2;

    /** identity_events.event_type (017). */
    public static final short SIGNUP_REQUESTED = 1;
    public static final short SIGNUP_CONFIRMED = 2;

    /** identity_email_tokens.purpose (017): 1=CONFIRMACAO_CADASTRO. */
    private static final short PURPOSE_SIGNUP = 1;

    public record State(short status, String email) {
    }

    /** Estado e e-mail gravado da identidade do contexto, sem lock. */
    public Optional<State> state(JdbcClient j, UUID identity) {
        return j.sql("SELECT status, email FROM public.identities WHERE identity_id = ?").param(identity)
                .query((rs, n) -> new State(rs.getShort(1), rs.getString(2))).optional();
    }

    /** Trava a identidade (ordem 017: identidade antes de qualquer linha dela) e devolve estado e e-mail. */
    public Optional<State> lock(JdbcClient j, UUID identity) {
        return j.sql("SELECT status, email FROM public.identities WHERE identity_id = ? FOR UPDATE").param(identity)
                .query((rs, n) -> new State(rs.getShort(1), rs.getString(2))).optional();
    }

    /**
     * Identidade nova, PENDING, SEM credencial. O nome provisorio e o proprio e-mail (a coluna e NOT NULL): nada escolhido
     * por quem fez o pedido fica na identidade antes da confirmacao. O identity_id ja e o contexto da transacao (WITH
     * CHECK da RLS). Duplicidade: 23505.
     */
    public void insertPending(JdbcClient j, UUID identity, String email) {
        j.sql("INSERT INTO public.identities (identity_id, email, display_name, status) VALUES (?, ?, ?, ?)")
                .param(identity).param(email).param(email).param(PENDING).update();
    }

    /** Credencial da identidade: so na confirmacao, na transacao que consome o token. Ja existente: 23505. */
    public void insertCredential(JdbcClient j, UUID identity, String passwordHash) {
        j.sql("INSERT INTO public.identity_credentials (identity_id, password_hash) VALUES (?, ?)").param(identity).param(passwordHash).update();
    }

    /**
     * Revoga os tokens de confirmacao ainda vivos da identidade (no maximo um valido por identidade, desenho §6.2). revoked_at
     * ajustado ao created_at se o relogio do banco recuou (CHECK da 017), como nas sessoes.
     */
    public int revokeLiveTokens(JdbcClient j, UUID identity) {
        return j.sql("""
                UPDATE public.identity_email_tokens SET revoked_at = greatest(clock_timestamp(), created_at)
                 WHERE identity_id = ? AND purpose = ? AND consumed_at IS NULL AND revoked_at IS NULL
                """).param(identity).param(PURPOSE_SIGNUP).update();
    }

    /**
     * Token novo: so o hash. created_at e expires_at saem do MESMO instante da transacao ({@code now()}), porque a 017
     * limita a validade a 24 h contadas de created_at.
     */
    public void insertToken(JdbcClient j, UUID identity, String targetEmail, String tokenHash, Duration validity) {
        j.sql("""
                INSERT INTO public.identity_email_tokens (purpose, identity_id, target_email, token_hash, expires_at)
                VALUES (?, ?, ?, ?, now() + ?::interval)
                """).param(PURPOSE_SIGNUP).param(identity).param(targetEmail).param(tokenHash).param(validity.toSeconds() + " seconds").update();
    }

    /**
     * Trava a linha do token (ordem 017: identidade -> token), SEM conferir validade: a conferencia vem depois, num
     * comando novo ({@link #consumeToken}), com o relogio lido DEPOIS de qualquer espera por este lock. Um UPDATE direto
     * que esperasse o lock de quem so TRAVOU a linha seguiria com a condicao avaliada antes da espera.
     */
    public boolean lockToken(JdbcClient j, UUID identity, String tokenHash) {
        return j.sql("SELECT 1 FROM public.identity_email_tokens WHERE token_hash = ? AND identity_id = ? AND purpose = ? FOR UPDATE")
                .param(tokenHash).param(identity).param(PURPOSE_SIGNUP).query(Integer.class).optional().isPresent();
    }

    /**
     * Consumo unico (desenho §6.2), depois de {@link #lockToken}: o UPDATE condicional confere validade e estado no
     * instante do consumo. Devolve o e-mail de destino do token consumido, ou vazio se ele nao estava mais vivo.
     */
    public Optional<String> consumeToken(JdbcClient j, UUID identity, String tokenHash) {
        return j.sql("""
                UPDATE public.identity_email_tokens SET consumed_at = clock_timestamp()
                 WHERE token_hash = ? AND identity_id = ? AND purpose = ?
                   AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > clock_timestamp()
                RETURNING target_email
                """).param(tokenHash).param(identity).param(PURPOSE_SIGNUP).query(String.class).optional();
    }

    /**
     * PENDING -> ACTIVE com o e-mail verificado e o nome escolhido NA CONFIRMACAO, so se o e-mail do token e o da
     * identidade. Devolve as linhas alteradas.
     */
    public int activate(JdbcClient j, UUID identity, String targetEmail, String displayName) {
        return j.sql("""
                UPDATE public.identities SET status = ?, email_verified_at = clock_timestamp(), display_name = ?
                 WHERE identity_id = ? AND status = ? AND email = ? AND email_verified_at IS NULL
                """).param(ACTIVE).param(displayName).param(identity).param(PENDING).param(targetEmail).update();
    }

    /** identity_events, append-only: nunca e-mail, token ou hash (desenho §8). */
    public void event(JdbcClient j, UUID identity, short type, UUID correlation) {
        j.sql("INSERT INTO public.identity_events (identity_id, event_type, correlation_id) VALUES (?, ?, ?)")
                .param(identity).param(type).param(correlation).update();
    }
}
