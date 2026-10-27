package com.nekkora.platform.admin;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.DbErrors;
import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.account.AccountRepository;
import com.nekkora.platform.session.SessionRepository;
import com.nekkora.platform.session.ValidSession;

/**
 * Execucao comum dos atos de administracao de acesso (contrato de 2026-10-27, §3): transacao da organizacao da rota,
 * sessao do ator e permissao relidas DENTRO da transacao (nunca o principal do filtro), step-up lido do banco, recusa
 * como excecao (desfaz tudo, inclusive as intencoes de e-mail) e mapeamento unico para HTTP. As garantias do banco (023 e
 * 003) recusam com 42501 (autoridade) e P0001 (ultimo ORG_ADMIN): viram 403 sem detalhe, so com a regra no log.
 */
public final class AccessActions {

    private static final Logger LOG = LoggerFactory.getLogger(AccessActions.class);

    enum Failure { SESSION, PERMISSION, OWN_ACCOUNT, TARGET_NOT_FOUND, STEP_UP, CONFLICT, INVALID }

    /** Recusa de um ato: desfaz a transacao e responde pela falha. {@code rule}: so para o log. */
    static final class Refusal extends RuntimeException {
        final transient Failure failure;
        final String rule;

        Refusal(Failure failure, String rule) {
            super(null, null, false, false);
            this.failure = failure;
            this.rule = rule;
        }
    }

    static Refusal refuse(Failure f, String rule) {
        return new Refusal(f, rule);
    }

    private final OrgTransactions tx;
    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final Duration stepUpWindow;

    public AccessActions(OrgTransactions tx, AccountRepository accounts, SessionRepository sessions, Duration stepUpWindow) {
        this.tx = tx;
        this.accounts = accounts;
        this.sessions = sessions;
        this.stepUpWindow = stepUpWindow;
    }

    AccountRepository accounts() {
        return accounts;
    }

    SessionRepository sessions() {
        return sessions;
    }

    <T> T write(UUID org, Function<JdbcClient, T> action) {
        return run(org, Mode.READ_WRITE, action);
    }

    <T> T read(UUID org, Function<JdbcClient, T> action) {
        return run(org, Mode.READ_ONLY, action);
    }

    private <T> T run(UUID org, Mode mode, Function<JdbcClient, T> action) {
        try {
            return tx.inOrganization(org, mode, action);
        } catch (Refusal r) {
            LOG.info("evento=acesso.negado regra={} motivo={} correlacao={}", r.failure, r.rule, RequestContext.correlation());
            throw switch (r.failure) {
                case SESSION -> ApiException.unauthenticated("sessao");
                case TARGET_NOT_FOUND -> new ApiException(ApiError.NOT_FOUND, "alvo");
                case CONFLICT -> new ApiException(ApiError.CONFLICT, r.rule);
                case INVALID -> ApiException.invalid(r.rule);
                default -> ApiException.forbidden(r.failure.name());
            };
        } catch (RuntimeException e) {
            String state = DbErrors.sqlState(e);
            if ("P0001".equals(state) || "42501".equals(state)) {
                // P0001: a 003 recusou deixar a organizacao sem ORG_ADMIN efetivo; 42501: a 023 recusou a autoridade
                LOG.info("evento=acesso.negado regra={} correlacao={}", "P0001".equals(state) ? "invariante_003" : "autoridade_023",
                        RequestContext.correlation());
                throw ApiException.forbidden("P0001".equals(state) ? "invariante" : "autoridade");
            }
            throw e;
        }
    }

    /** Ator: sessao ainda valida (reavaliada aqui, sem travar a sessao) e conta interna. */
    void requireActor(JdbcClient j, UUID org, ValidSession actor, String actorToken) {
        if (sessions.validate(j, org, Tokens.sha256Hex(actorToken), false).isEmpty()) {
            throw refuse(Failure.SESSION, "sessao");
        }
        if (!actor.isInternal()) {
            throw refuse(Failure.PERMISSION, "conta_externa");
        }
    }

    void requirePermission(JdbcClient j, UUID org, ValidSession actor, String permission) {
        if (!accounts.hasPermission(j, org, actor.userId(), permission)) {
            throw refuse(Failure.PERMISSION, permission);
        }
    }

    /** Autoridade sobre um papel (a mesma regra do gatilho da 023, lida pela funcao do banco). */
    boolean canManageRole(JdbcClient j, UUID org, UUID membership, UUID role, UUID workspace) {
        return Boolean.TRUE.equals(j.sql("SELECT public.access_can_manage_role(?, ?, ?, ?)")
                .param(org).param(membership).param(role).param(workspace).query(Boolean.class).single());
    }

    void requireRoleAuthority(JdbcClient j, UUID org, ValidSession actor, UUID role, UUID workspace) {
        if (!canManageRole(j, org, actor.membershipId(), role, workspace)) {
            throw refuse(Failure.PERMISSION, "autoridade_papel");
        }
    }

    /** Step-up de TOTP nos ultimos minutos NA MESMA sessao, lido do banco com a hora atual. */
    void requireStepUp(JdbcClient j, ValidSession actor) {
        Boolean recent = j.sql("SELECT mfa_step_up_at IS NOT NULL AND mfa_step_up_at > clock_timestamp() - ?::interval FROM public.user_sessions WHERE session_id = ?")
                .param(SessionRepository.interval(stepUpWindow)).param(actor.sessionId()).query(Boolean.class).optional().orElse(false);
        if (!recent) {
            throw refuse(Failure.STEP_UP, "step_up");
        }
    }

    /**
     * Organizacao e depois as contas, em ordem crescente de user_id: o alvo FOR UPDATE e o ator FOR KEY SHARE (inventario de
     * locks, I-5). {@code false}: o alvo nao existe nesta organizacao.
     */
    boolean lockOrganizationAndAccounts(JdbcClient j, UUID org, UUID actor, UUID target) {
        accounts.lockOrganization(j, org);
        boolean found = true;
        for (UUID account : java.util.stream.Stream.of(actor, target).distinct().sorted().toList()) {
            if (account.equals(target)) {
                found = accounts.lock(j, org, target);
            } else {
                accounts.lockKeyShare(j, org, account);
            }
        }
        return found;
    }

    /** Organizacao FOR UPDATE e a conta do ator FOR KEY SHARE (atos sem conta alvo). */
    void lockOrganizationAndActor(JdbcClient j, UUID org, UUID actor) {
        accounts.lockOrganization(j, org);
        accounts.lockKeyShare(j, org, actor);
    }

    static UUID correlation() {
        try {
            return UUID.fromString(RequestContext.correlation());
        } catch (IllegalArgumentException | NullPointerException e) {
            return UUID.randomUUID();
        }
    }
}
