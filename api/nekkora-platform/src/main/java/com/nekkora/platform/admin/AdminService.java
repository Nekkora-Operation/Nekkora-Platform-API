package com.nekkora.platform.admin;

import java.util.UUID;

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
import com.nekkora.platform.mail.MailIntents;
import com.nekkora.platform.mail.MailSender;
import com.nekkora.platform.mfa.MfaRepository;
import com.nekkora.platform.session.SessionRepository;
import com.nekkora.platform.session.ValidSession;

/**
 * Rotas internas do primeiro corte (contrato 8.7, §5.3). Autorizacao pela matriz da 003 (permissao, nao nome de
 * papel), revalidada DENTRO da transacao da acao, junto com a sessao do ator (sem confiar no principal do filtro).
 */
public final class AdminService {

    private static final Logger LOG = LoggerFactory.getLogger(AdminService.class);
    private static final short ACTIVE = 2;
    private static final short SUSPENDED = 3;

    private final OrgTransactions tx;
    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final MfaRepository mfa;
    private final MailIntents mail;
    private final java.time.Duration stepUpWindow;

    public AdminService(OrgTransactions tx, AccountRepository accounts, SessionRepository sessions, MfaRepository mfa, MailIntents mail,
                        java.time.Duration stepUpWindow) {
        this.tx = tx;
        this.accounts = accounts;
        this.sessions = sessions;
        this.mfa = mfa;
        this.mail = mail;
        this.stepUpWindow = stepUpWindow;
    }

    private enum Failure { SESSION, PERMISSION, OWN_ACCOUNT, TARGET_NOT_FOUND, STEP_UP, LINKED_ACCOUNT }

    /** Suspende a conta (permissao org.manage): organizacao -> conta; revoga sessoes e invalida desafios (§11.4). */
    public void suspend(UUID org, ValidSession actor, String actorToken, UUID target) {
        runAdminAction(org, j -> {
            accounts.lockOrganization(j, org);
            Failure f = authorize(j, org, actor, actorToken, "org.manage", target);
            if (f != null) {
                return f;
            }
            if (!accounts.lock(j, org, target)) {
                return Failure.TARGET_NOT_FOUND;
            }
            accounts.updateStatus(j, org, target, SUSPENDED);
            mfa.invalidateForAccount(j, target, null);
            sessions.revokeForAccount(j, target, null);
            return null;
        });
    }

    /** Reativa a conta. NAO restaura sessoes revogadas nem concessoes revogadas. */
    public void reactivate(UUID org, ValidSession actor, String actorToken, UUID target) {
        runAdminAction(org, j -> {
            accounts.lockOrganization(j, org);
            Failure f = authorize(j, org, actor, actorToken, "org.manage", target);
            if (f != null) {
                return f;
            }
            if (!accounts.lock(j, org, target)) {
                return Failure.TARGET_NOT_FOUND;
            }
            accounts.updateStatus(j, org, target, ACTIVE);
            return null;
        });
    }

    /** Revoga a concessao externa (permissao external_access.manage). Idempotente se ja revogada. */
    public void revokeGrant(UUID org, ValidSession actor, String actorToken, UUID grant) {
        runAdminAction(org, j -> {
            Failure f = authorize(j, org, actor, actorToken, "external_access.manage", null);
            if (f != null) {
                return f;
            }
            Integer status = j.sql("SELECT status FROM public.client_workspace_grants WHERE organization_id = ? AND grant_id = ? FOR UPDATE")
                    .param(org).param(grant).query(Integer.class).optional().orElse(null);
            if (status == null) {
                return Failure.TARGET_NOT_FOUND;
            }
            if (status != 4) {
                // revoked_at ajustado ao created_at se o relogio do banco recuou (CHECK da 003), como nas sessoes
                j.sql("""
                        UPDATE public.client_workspace_grants SET status = 4, revoked_at = greatest(clock_timestamp(), created_at), revoked_by_membership_id = ?
                         WHERE organization_id = ? AND grant_id = ?
                        """).param(actor.membershipId()).param(org).param(grant).update();
            }
            return null;
        });
    }

    /** Motivo codificado e obrigatorio do reset administrativo (016, D5). Codigos permanentes de mfa_events.reason_code. */
    public enum ResetReason {
        DISPOSITIVO_PERDIDO(1), DISPOSITIVO_TROCADO(2), SUSPEITA_COMPROMETIMENTO(3), OUTRO(4);

        private final short code;

        ResetReason(int code) {
            this.code = (short) code;
        }

        public short code() {
            return code;
        }

        public static java.util.Optional<ResetReason> parse(String s) {
            for (ResetReason r : values()) {
                if (r.name().equals(s)) {
                    return java.util.Optional.of(r);
                }
            }
            return java.util.Optional.empty();
        }
    }

    /**
     * Reset administrativo de MFA (contrato, §3.4): permissao identity.mfa.reset; step-up com TOTP nos ultimos 5
     * min NA MESMA sessao (lido do banco, nao do principal do filtro); alvo diferente do ator; na mesma transacao,
     * conta do alvo -> desafios -> fator/codigos -> sessoes; auditoria COM MOTIVO codificado (016, D5); aviso ao
     * titular e, se o alvo for ORG_ADMIN, aos demais ORG_ADMIN, depois do commit.
     *
     * <p>Conta VINCULADA a uma identidade (contrato P3, parecer de 2026-10-19): 403 generico, conferido SOB o lock da conta
     * e antes de qualquer efeito - sem revogar sessoes, sem evento e sem aviso. O fator dessa pessoa e o da identidade, que
     * nenhuma autoridade de organizacao redefine (DC-17.9). A recusa e garantida AQUI, pelo servico: as triggers da 017
     * permitem de proposito as transicoes terminais (desabilitar, invalidar, revogar), necessarias ao fechamento da
     * DC-17.12 e a operacao da 016, e por isso nao recusam o reset. A vinculacao concorrente serializa com o reset na
     * organizacao ({@code identity_link_account} tambem a trava {@code FOR UPDATE} antes da conta).
     */
    public void resetMfa(UUID org, ValidSession actor, String actorToken, UUID target, ResetReason reason) {
        java.util.Objects.requireNonNull(reason);
        String[] email = new String[1];
        java.util.List<String> otherAdmins = new java.util.ArrayList<>();
        runAdminAction(org, j -> {
            // Achado I-5 do inventario de locks. (0) verificacao preliminar SEM lock (validate(..., false) nao trava a
            // sessao); (1) organizacao; (2) ator e alvo em ordem crescente de user_id, cada um ja no modo final (alvo
            // FOR UPDATE, ator FOR KEY SHARE - o modo que o evento exige), sem promocao; (3) reconferencia de sessao,
            // step-up, permissao, elegibilidade e alvo != ator DEPOIS dos locks; (4) so entao os efeitos e o evento.
            Failure f = authorize(j, org, actor, actorToken, "identity.mfa.reset", target);
            if (f != null) {
                return f;
            }
            accounts.lockOrganization(j, org);
            boolean targetFound = true;
            for (UUID account : java.util.stream.Stream.of(actor.userId(), target).distinct().sorted().toList()) {
                if (account.equals(target)) {
                    targetFound = accounts.lock(j, org, target);
                } else {
                    accounts.lockKeyShare(j, org, account);
                }
            }
            if (!targetFound) {
                return Failure.TARGET_NOT_FOUND;
            }
            // conta vinculada: lida com o lock da conta ja tomado (o comando ve a vinculacao confirmada antes do lock)
            if (accounts.isLinked(j, org, target)) {
                return Failure.LINKED_ACCOUNT;
            }
            // intencoes NA transacao do reset (chave = evento do reset, + destinatario), ANTES das reconferencias de sessao,
            // permissao e step-up: a gravacao pode esperar locks, e essas reconferencias tem de ser as ultimas decisoes antes
            // dos efeitos e do commit. Recusa depois daqui desfaz tambem as intencoes (FailureRollback)
            UUID ev = correlation();
            email[0] = accounts.byId(j, org, target).map(AccountRepository.Account::email).orElse(null);
            if (accounts.isEffectiveOrgAdmin(j, org, target)) {
                otherAdmins.addAll(accounts.effectiveOrgAdminEmails(j, org, target));
            }
            if (email[0] != null) {
                mail.record(j, new MailIntents.Intent(MailSender.Kind.NOTICE_MFA_RESET, org, email[0], null, "reset:" + target + ":" + ev));
            }
            for (String other : otherAdmins) {
                mail.record(j, new MailIntents.Intent(MailSender.Kind.NOTICE_MFA_RESET_ADMIN_TARGET, org, other, null,
                        "reset:" + target + ":" + ev + ":" + com.nekkora.core.token.Tokens.sha256Hex(other)));
            }
            f = authorize(j, org, actor, actorToken, "identity.mfa.reset", target);
            if (f != null) {
                throw new FailureRollback(f);
            }
            Boolean recent = j.sql("SELECT mfa_step_up_at IS NOT NULL AND mfa_step_up_at > clock_timestamp() - ?::interval FROM public.user_sessions WHERE session_id = ?")
                    .param(SessionRepository.interval(stepUpWindow)).param(actor.sessionId()).query(Boolean.class).single();
            if (!recent) {
                throw new FailureRollback(Failure.STEP_UP);
            }
            mfa.invalidateForAccount(j, target, null);
            mfa.disableFactor(j, target);
            sessions.revokeForAccount(j, target, null);
            mfa.recordEvent(j, org, target, actor.userId(), MfaRepository.EV_RESET_ADMIN, ev, reason.code());
            return null;
        });
    }

    /** Ator: conta interna, sessao ainda valida (reavaliada aqui), permissao da matriz, e alvo != ator quando houver alvo. */
    private Failure authorize(JdbcClient j, UUID org, ValidSession actor, String actorToken, String permission, UUID target) {
        if (sessions.validate(j, org, Tokens.sha256Hex(actorToken), false).isEmpty()) {
            return Failure.SESSION;
        }
        if (!actor.isInternal() || !accounts.hasPermission(j, org, actor.userId(), permission)) {
            return Failure.PERMISSION;
        }
        if (target != null && target.equals(actor.userId())) {
            return Failure.OWN_ACCOUNT;
        }
        return null;
    }

    private void runAdminAction(UUID org, java.util.function.Function<JdbcClient, Failure> action) {
        Failure f;
        try {
            f = tx.inOrganization(org, Mode.READ_WRITE, action);
        } catch (FailureRollback e) {
            f = e.failure;
        } catch (RuntimeException e) {
            // ex.: trigger da 003 que impede deixar a organizacao sem ORG_ADMIN efetivo (P0001) - so SQLSTATE no log
            if ("P0001".equals(DbErrors.sqlState(e))) {
                LOG.info("evento=admin.negado regra=invariante_003 correlacao={}", RequestContext.correlation());
                throw ApiException.forbidden("invariante");
            }
            throw e;
        }
        if (f != null) {
            LOG.info("evento=admin.negado regra={} correlacao={}", f, RequestContext.correlation());
            throw switch (f) {
                case SESSION -> ApiException.unauthenticated("sessao");
                case TARGET_NOT_FOUND -> new ApiException(ApiError.NOT_FOUND, "alvo");
                default -> ApiException.forbidden(f.name());
            };
        }
    }

    /** Recusa depois de escritas (as intencoes de e-mail): desfaz a transacao e responde como a recusa. */
    private static final class FailureRollback extends RuntimeException {
        final transient Failure failure;

        FailureRollback(Failure failure) {
            super(null, null, false, false);
            this.failure = failure;
        }
    }

    private static UUID correlation() {
        try {
            return UUID.fromString(RequestContext.correlation());
        } catch (IllegalArgumentException e) {
            return UUID.randomUUID();
        }
    }
}
