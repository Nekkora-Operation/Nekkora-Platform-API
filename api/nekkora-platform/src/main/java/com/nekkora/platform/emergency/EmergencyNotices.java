package com.nekkora.platform.emergency;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.mail.MailSender;
import com.nekkora.platform.session.ValidSession;

/**
 * Lado da Platform da recuperacao emergencial de MFA (016; contrato 6.1 e 9; revisao 4, itens 6 e 10). A Platform e a
 * UNICA identidade de servico que emite avisos, registra resultados de entrega e consome tokens de aviso, e so pelas
 * funcoes estreitas da 016 ({@code ops_notice_*}, {@code ops_account_proof_issue}); operadores nao tem acesso a elas.
 *
 * <p><b>Protocolo de entrega (item 10):</b> cada aviso e uma TENTATIVA. (1) emissao: tokens gerados aqui, so em memoria,
 * hash no banco ({@code EMITIDA}); (2) reivindicacao: {@code ENTREGANDO} com um {@code claim_id} desta execucao, com
 * commit, ANTES de entregar; (3) entrega a porta de e-mail, uma vez; (4) registro do desfecho. Se a confirmacao do
 * commit da reivindicacao se perder, esta MESMA execucao - que sabe ainda nao ter chamado o transporte - reconcilia
 * lendo a tentativa: so entrega se encontrar a propria reivindicacao em {@code ENTREGANDO}; em qualquer outro caso, ou
 * sem conseguir ler, nao entrega. Nenhuma tentativa e reentregue: um novo envio e outra tentativa (reenvio do
 * operador). O {@code attempt_id} correlaciona, mas NAO garante deduplicacao pelo SMTP.
 *
 * <p><b>Confirmacao e contestacao:</b> POST explicito, sem sessao, sem aprovar, sem reset. Qualquer recusa devolve a
 * MESMA resposta publica. O log leva so finalidade, categoria, organizacao DA ROTA (nao validada), estado do limitador
 * R7 e correlacao - nunca token, hash, destinatario ou link.
 */
public final class EmergencyNotices {

    private static final Logger LOG = LoggerFactory.getLogger(EmergencyNotices.class);
    private static final int BATCH = 20;

    /** Estados da tentativa na 016 (ops_mfa_notices.state). */
    static final short EMITIDA = 1;
    static final short ENTREGANDO = 2;
    static final short ENFILEIRADA = 3;
    static final short ACEITA = 4;
    static final short FALHOU = 5;
    static final short DESCONHECIDO = 6;

    private final OrgTransactions tx;
    private final MailSender mail;

    public EmergencyNotices(OrgTransactions tx, MailSender mail) {
        this.tx = tx;
        this.mail = mail;
    }

    // ------------------------------------------------------------------ confirmacao e contestacao (publicas)

    public void confirm(UUID org, String token, String limiter) {
        use("ops_notice_confirm", "confirmacao", org, token, limiter);
    }

    public void contest(UUID org, String token, String limiter) {
        use("ops_notice_contest", "contestacao", org, token, limiter);
    }

    private void use(String function, String what, UUID org, String token, String limiter) {
        String category;
        if (!Tokens.isValidFormat(token)) {
            category = "FORMATO_INVALIDO";
        } else {
            category = tx.inOrganization(org, Mode.READ_WRITE, j -> j.sql("SELECT public." + function + "(?, ?)")
                    .param(org).param(Tokens.sha256Hex(token)).query(String.class).single());
        }
        LOG.info("evento=aviso_mfa_emergencia.{} categoria={} org_rota={} org_validada=nao r7={} correlacao={}", what, category, org,
                limiter == null ? "DESCONHECIDO" : limiter, RequestContext.correlation());
        if (!"ACEITO".equals(category)) {
            throw ApiException.invalid("token");
        }
    }

    // ------------------------------------------------------------------ emissao e entrega (worker)

    private record Pending(UUID notice, UUID org) {
    }

    private record Issued(boolean issued, boolean confirm, boolean contest, short kind, UUID org, UUID recipient) {
    }

    enum Claim { MINHA, NAO_AUTORIZADA, INCERTA }

    /** Emite e entrega os avisos pendentes: um lote por chamada. Devolve quantas entregas foram feitas a porta. */
    public int dispatchPending() {
        List<Pending> pending = tx.withoutContext(j -> j.sql("SELECT r_aviso, r_organizacao FROM public.ops_notice_pending(?)").param(BATCH)
                .query((rs, n) -> new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list());
        int sent = 0;
        for (Pending p : pending) {
            if (dispatch(p.notice())) {
                sent++;
            }
        }
        return sent;
    }

    /** Uma tentativa: emissao -> reivindicacao -> entrega unica -> desfecho. Devolve se entregou a porta. */
    boolean dispatch(UUID notice) {
        String confirmToken = Tokens.newToken();
        String contestToken = Tokens.newToken();
        Issued i = tx.withoutContext(j -> j.sql("""
                SELECT r_emitido, r_confirmar, r_contestar, r_tipo, r_organizacao, r_destinatario
                  FROM public.ops_notice_issue(?, ?, ?)
                """).param(notice).param(Tokens.sha256Hex(confirmToken)).param(Tokens.sha256Hex(contestToken))
                .query((rs, n) -> new Issued(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getShort(4),
                        rs.getObject(5, UUID.class), rs.getObject(6, UUID.class))).single());
        if (!i.issued()) {
            LOG.info("evento=aviso_mfa_emergencia.descartado org={}", i.org());
            return false;
        }
        MailSender.Kind kind = switch (i.kind()) {
            case 1 -> MailSender.Kind.NOTICE_MFA_EMERGENCY_HOLDER;
            case 2 -> MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN;
            default -> MailSender.Kind.NOTICE_MFA_EMERGENCY_EXECUTED;
        };
        Optional<String> email = tx.inOrganization(i.org(), Mode.READ_ONLY,
                j -> j.sql("SELECT email FROM public.users WHERE user_id = ?").param(i.recipient()).query(String.class).optional());
        if (email.isEmpty()) {
            // antes de qualquer entrega: EMITIDA -> FALHOU (os tokens da tentativa sao invalidados no banco)
            recordResult(notice, FALHOU, "SEM_DESTINATARIO");
            LOG.warn("evento=aviso_mfa_emergencia.sem_destinatario tipo={} org={}", kind, i.org());
            return false;
        }
        UUID claimId = UUID.randomUUID();
        Claim claim = claim(notice, claimId);
        if (claim != Claim.MINHA) {
            // sem autorizacao confirmada nao ha entrega; a varredura de recuperacao resolve o estado depois
            LOG.warn("evento=aviso_mfa_emergencia.entrega_nao_autorizada reivindicacao={} tipo={} org={}", claim, kind, i.org());
            return false;
        }
        MailSender.Outcome outcome;
        try {
            outcome = mail.sendEmergencyNotice(kind, i.org(), email.get(), i.confirm() ? confirmToken : null,
                    i.contest() ? contestToken : null, notice);
        } catch (RuntimeException e) {
            outcome = MailSender.Outcome.INCERTO;
        }
        recordTransportResult(notice, outcome);
        LOG.info("evento=aviso_mfa_emergencia.entregue_a_porta tipo={} org={} desfecho={}", kind, i.org(), outcome);
        return true;
    }

    /**
     * Reivindicacao EMITIDA -> ENTREGANDO. Chamada ANTES de qualquer chamada ao transporte para esta tentativa: por isso,
     * se a confirmacao do commit se perder, esta execucao pode reconciliar lendo a tentativa - e so ela. Depois de
     * reinicio, perda dessa certeza ou transporte incerto, a regra e nao reentregar (a tentativa fica DESCONHECIDO).
     */
    Claim claim(UUID notice, UUID claimId) {
        try {
            return tx.withoutContext(j -> j.sql("SELECT r_estado, r_minha FROM public.ops_notice_claim(?, ?)").param(notice).param(claimId)
                    .query((rs, n) -> rs.getBoolean(2) ? Claim.MINHA : Claim.NAO_AUTORIZADA).single());
        } catch (RuntimeException lost) {
            return reconcile(notice, claimId, true);
        }
    }

    private Claim reconcile(UUID notice, UUID claimId, boolean mayRetry) {
        record State(Short state, UUID claim) {
        }
        State s;
        try {
            s = tx.withoutContext(j -> j.sql("SELECT r_estado, r_claim FROM public.ops_notice_attempt_state(?)").param(notice)
                    .query((rs, n) -> new State((Short) rs.getObject(1, Short.class), rs.getObject(2, UUID.class))).single());
        } catch (RuntimeException e) {
            LOG.warn("evento=aviso_mfa_emergencia.reivindicacao_incerta alerta=sim");
            return Claim.INCERTA;
        }
        if (s.state() != null && s.state() == ENTREGANDO && claimId.equals(s.claim())) {
            return Claim.MINHA;   // o commit ocorreu; esta execucao ainda nao chamou o transporte
        }
        if (mayRetry && s.state() != null && s.state() == EMITIDA) {
            try {
                return tx.withoutContext(j -> j.sql("SELECT r_estado, r_minha FROM public.ops_notice_claim(?, ?)").param(notice).param(claimId)
                        .query((rs, n) -> rs.getBoolean(2) ? Claim.MINHA : Claim.NAO_AUTORIZADA).single());
            } catch (RuntimeException e) {
                return reconcile(notice, claimId, false);
            }
        }
        return Claim.NAO_AUTORIZADA;
    }

    /** Resultado vindo do transporte (sincrono ou assincrono), preso a tentativa exata. Idempotente no banco. */
    public void recordTransportResult(UUID notice, MailSender.Outcome outcome) {
        switch (outcome) {
            case ENFILEIRADO -> recordResult(notice, ENFILEIRADA, null);
            case ACEITO_PELO_TRANSPORTE -> recordResult(notice, ACEITA, null);
            case RECUSADO_DEFINITIVO -> recordResult(notice, FALHOU, "RECUSADO_PELO_TRANSPORTE");
            case INCERTO -> recordResult(notice, DESCONHECIDO, "RESULTADO_INCERTO");
        }
    }

    private void recordResult(UUID notice, short state, String category) {
        try {
            String r = tx.withoutContext(j -> j.sql("SELECT r_resultado FROM public.ops_notice_delivery_result(?, ?, ?)")
                    .param(notice).param(state).param(category).query(String.class).single());
            if (!"APLICADO".equals(r) && !"REPETIDO".equals(r)) {
                LOG.warn("evento=aviso_mfa_emergencia.resultado resultado={} alerta={}", r, "CONFLITANTE".equals(r) ? "sim" : "nao");
            }
        } catch (RuntimeException e) {
            // o registro nao aconteceu: a varredura de recuperacao leva a tentativa parada a DESCONHECIDO (nunca a ACEITA)
            LOG.warn("evento=aviso_mfa_emergencia.resultado_nao_registrado alerta=sim");
        }
    }

    /** Varredura de tentativas paradas (queda do processo): uma transacao por tentativa. Nunca aceite presumido. */
    public int recoverStalled() {
        List<UUID> stalled = tx.withoutContext(j -> j.sql("SELECT r_aviso FROM public.ops_notice_stalled(?)").param(BATCH)
                .query(UUID.class).list());
        int n = 0;
        for (UUID notice : stalled) {
            String r = tx.withoutContext(j -> j.sql("SELECT r_resultado FROM public.ops_notice_recover(?)").param(notice)
                    .query(String.class).single());
            if ("RECUPERADA".equals(r)) {
                n++;
            }
        }
        if (n > 0) {
            LOG.warn("evento=aviso_mfa_emergencia.recuperacao tentativas={} alerta=sim", n);
        }
        return n;
    }

    // ------------------------------------------------------------------ prova de conta para vinculo operacional

    public record LinkCode(String code, OffsetDateTime expiresAt) {
    }

    /**
     * Codigo de uso unico que o proprio titular da conta (autenticado) entrega a CLI operacional para registrar o
     * vinculo impeditivo (contrato 4). So o hash vai ao banco; valido por 10 min.
     */
    public LinkCode issueAccountLinkCode(UUID org, ValidSession session) {
        String code = Tokens.newToken();
        OffsetDateTime expires = tx.inOrganization(org, Mode.READ_WRITE, j -> j.sql("SELECT public.ops_account_proof_issue(?, ?, ?)")
                .param(org).param(session.userId()).param(Tokens.sha256Hex(code)).query(OffsetDateTime.class).single());
        LOG.info("evento=vinculo_operacional.prova_emitida org={} correlacao={}", org, RequestContext.correlation());
        return new LinkCode(code, expires);
    }
}
