package com.nekkora.platform.session;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.platform.config.PlatformProperties;

/**
 * SQL de sessao da Platform (8.3/8.5, §11.4; contrato revisao 4). Todo metodo roda DENTRO de uma transacao
 * aberta por OrgTransactions (contexto RLS ja definido por bind). Nenhum metodo decide HTTP.
 */
public final class SessionRepository {

    private final PlatformProperties.Session cfg;

    public SessionRepository(PlatformProperties.Session cfg) {
        this.cfg = cfg;
    }

    private record Evaluation(ValidSession session, OffsetDateTime currentTime) {
    }

    /**
     * Valida a sessao do token (hash) na organizacao da rota. Com {@code travar}: primeiro trava a linha
     * ({@code FOR UPDATE}); DEPOIS captura UM instante ({@code clock_timestamp()}) e avalia tudo com ele (§11.4):
     * revogacao, prazo absoluto, inatividade (30 min interna / 15 min externa), conta ACTIVE com e-mail
     * verificado, organizacao ativa, vinculo ativo (interno: organization_memberships; externo: client_memberships
     * e cliente ativo) e marcador de MFA; e, na sessao nascida da troca por sessao de organizacao, a sessao de
     * identidade de origem (DC-17.4). Valida -> toca last_seen_at com o MESMO instante, so se a ultima escrita
     * tem mais de 1 min. {@code travar=false}: so avalia (ator dentro de uma transacao de negocio).
     */
    public Optional<ValidSession> validate(JdbcClient j, UUID org, String tokenHash, boolean lock) {
        Optional<UUID> id;
        if (lock) {
            id = j.sql("SELECT session_id FROM public.user_sessions WHERE organization_id = ? AND session_token_hash = ? FOR UPDATE")
                    .param(org).param(tokenHash).query(UUID.class).optional();
        } else {
            id = j.sql("SELECT session_id FROM public.user_sessions WHERE organization_id = ? AND session_token_hash = ?")
                    .param(org).param(tokenHash).query(UUID.class).optional();
        }
        if (id.isEmpty()) {
            return Optional.empty();
        }
        Optional<Evaluation> a = j.sql("""
                SELECT s.session_id, s.user_id, s.csrf_token, s.mfa_step_up_at, s.expires_at, u.account_type, t.agora,
                       (s.revoked_at IS NULL AND s.mfa_verified_at IS NOT NULL AND s.csrf_token IS NOT NULL
                        AND s.expires_at > t.agora
                        AND s.last_seen_at > t.agora - CASE WHEN u.account_type = 1 THEN ?::interval ELSE ?::interval END
                        AND u.status = 2 AND u.email_verified_at IS NOT NULL AND o.status = 1
                        -- DC-17.4: sessao nascida da troca (identity_session_id preenchido) so vale enquanto a sessao de
                        -- identidade de origem estiver ativa, nao restrita, e a conta ainda ligada a ela (auxiliar da 017,
                        -- com a conferencia do contexto). Sessao do login por organizacao: inalterada
                        AND CASE WHEN s.identity_session_id IS NULL THEN true
                                 ELSE public.identity_org_session_allowed(s.organization_id, s.user_id, s.identity_session_id) END) AS valida,
                       CASE WHEN u.account_type = 1 THEN
                           (SELECT om.membership_id FROM public.organization_memberships om
                             WHERE om.user_id = u.user_id AND om.organization_id = u.organization_id AND om.status = 2)
                       ELSE
                           (SELECT cm.client_membership_id FROM public.client_memberships cm
                              JOIN public.clients c ON c.client_id = cm.client_id AND c.organization_id = cm.organization_id
                             WHERE cm.user_id = u.user_id AND cm.organization_id = u.organization_id AND cm.status = 2 AND c.status = 1)
                       END AS vinculo_id
                  FROM (SELECT clock_timestamp() AS agora) t
                  CROSS JOIN public.user_sessions s
                  JOIN public.users u ON u.user_id = s.user_id AND u.organization_id = s.organization_id
                  JOIN public.organizations o ON o.organization_id = s.organization_id
                 WHERE s.session_id = ?
                """)
                .param(interval(cfg.internalInactivity())).param(interval(cfg.externalInactivity())).param(id.get())
                .query((rs, n) -> new Evaluation(rs.getBoolean("valida") && rs.getObject("vinculo_id") != null
                        ? new ValidSession(rs.getObject("session_id", UUID.class), org, rs.getObject("user_id", UUID.class),
                                rs.getShort("account_type"), rs.getObject("vinculo_id", UUID.class), rs.getString("csrf_token"),
                                rs.getObject("mfa_step_up_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class))
                        : null, rs.getObject("agora", OffsetDateTime.class)))
                .optional();
        if (a.isEmpty() || a.get().session() == null) {
            return Optional.empty();
        }
        if (lock) {
            j.sql("UPDATE public.user_sessions SET last_seen_at = ? WHERE session_id = ? AND last_seen_at <= ? - interval '1 minute'")
                    .param(a.get().currentTime()).param(id.get()).param(a.get().currentTime()).update();
        }
        return Optional.of(a.get().session());
    }

    /**
     * Cria a sessao (depois do MFA, sob o lock da conta), aplicando o limite de sessoes: revoga as ativas mais
     * antigas para que, com a nova, sejam no maximo {@code maximoPorConta}. Um unico instante para todas as
     * colunas. A trigger da 014 exige marcador e fator ativo.
     */
    public UUID create(JdbcClient j, UUID org, UUID user, short accountType, String tokenHash, String csrfToken) {
        Duration inactivity = accountType == ValidSession.INTERNAL ? cfg.internalInactivity() : cfg.externalInactivity();
        OffsetDateTime currentTime = j.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        revokeExcess(j, org, user, currentTime, inactivity);
        return j.sql("""
                INSERT INTO public.user_sessions (organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at,
                                                  mfa_verified_at, csrf_token)
                VALUES (?, ?, ?, ?, ? + ?::interval, ?, ?, ?)
                RETURNING session_id
                """).param(org).param(user).param(tokenHash).param(currentTime).param(currentTime).param(interval(cfg.absolute()))
                .param(currentTime).param(currentTime).param(csrfToken)
                .query(UUID.class).single();
    }

    /**
     * Sessao de conta VINCULADA, nascida da troca (desenho §6.5 e porta do levantamento de modularidade, §4.3): mesma
     * politica de {@link #create} (limite por conta, um instante so), mais {@code identity_session_id} e o marcador de MFA
     * HERDADO da sessao de identidade. O prazo absoluto nao passa do da sessao de identidade ({@code naoDepoisDe}). Quem
     * chama ja travou a organizacao {@code FOR SHARE}; a trigger da 017 trava a conta e reconfere vinculo, sessao de
     * identidade e elegibilidade (ramo 2). Devolve o prazo gravado.
     */
    public OffsetDateTime createLinked(JdbcClient j, UUID org, UUID user, UUID identitySession, OffsetDateTime mfaVerifiedAt,
                                       OffsetDateTime notAfter, String tokenHash, String csrfToken) {
        OffsetDateTime currentTime = j.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
        revokeExcess(j, org, user, currentTime, cfg.internalInactivity());
        return j.sql("""
                INSERT INTO public.user_sessions (organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at,
                                                  mfa_verified_at, csrf_token, identity_session_id)
                VALUES (?, ?, ?, ?, least(? + ?::interval, ?), ?, ?, ?, ?)
                RETURNING expires_at
                """).param(org).param(user).param(tokenHash).param(currentTime).param(currentTime).param(interval(cfg.absolute())).param(notAfter)
                .param(currentTime).param(mfaVerifiedAt).param(csrfToken).param(identitySession)
                .query(OffsetDateTime.class).single();
    }

    public int revokeByToken(JdbcClient j, UUID org, String tokenHash) {
        return j.sql("UPDATE public.user_sessions SET revoked_at = clock_timestamp() WHERE organization_id = ? AND session_token_hash = ? AND revoked_at IS NULL")
                .param(org).param(tokenHash).update();
    }

    /** Revoga todas as sessoes ativas da conta, exceto {@code manter} (pode ser null). */
    public int revokeForAccount(JdbcClient j, UUID user, UUID keep) {
        return j.sql("UPDATE public.user_sessions SET revoked_at = clock_timestamp() WHERE user_id = ? AND revoked_at IS NULL AND session_id IS DISTINCT FROM ?")
                .param(user).param(keep).update();
    }

    public void recordStepUp(JdbcClient j, UUID session) {
        j.sql("UPDATE public.user_sessions SET mfa_step_up_at = clock_timestamp() WHERE session_id = ? AND revoked_at IS NULL")
                .param(session).update();
    }

    /** Limite por conta: revoga as ativas mais antigas para que, com a nova, sejam no maximo {@code maximoPorConta}. */
    private void revokeExcess(JdbcClient j, UUID org, UUID user, OffsetDateTime currentTime, Duration inactivity) {
        j.sql("""
                UPDATE public.user_sessions SET revoked_at = ?
                 WHERE session_id IN (
                     SELECT session_id FROM public.user_sessions
                      WHERE user_id = ? AND organization_id = ? AND revoked_at IS NULL AND expires_at > ?
                        AND last_seen_at > ? - ?::interval
                      ORDER BY created_at DESC, session_id DESC
                      OFFSET ?)
                """).param(currentTime).param(user).param(org).param(currentTime).param(currentTime).param(interval(inactivity))
                .param(cfg.maxPerAccount() - 1).update();
    }

    public static String interval(Duration d) {
        return d.toSeconds() + " seconds";
    }
}
