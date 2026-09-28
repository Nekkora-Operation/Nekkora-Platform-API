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
 * Lado da Platform da recuperacao emergencial de MFA (016; contrato 6.1 e 9). A Platform e a UNICA identidade de
 * servico que emite avisos e consome tokens de aviso, e so pelas funcoes estreitas da 016 ({@code ops_notice_*},
 * {@code ops_account_proof_issue}); operadores nao tem acesso a elas e nao registram confirmacao.
 *
 * <ul>
 * <li>Emissao: tokens gerados aqui, so em memoria; o banco recebe o SHA-256. O e-mail sai DEPOIS do commit.</li>
 * <li>Confirmacao e contestacao: POST explicito (a pagina nunca age ao abrir o link), sem sessao, sem aprovar, sem
 * reset. Qualquer recusa devolve a MESMA resposta publica.</li>
 * </ul>
 * Log: so categorias (tipo, organizacao, resultado) - nunca token, destinatario ou link.
 */
public final class EmergencyNotices {

    private static final Logger LOG = LoggerFactory.getLogger(EmergencyNotices.class);
    private static final int BATCH = 20;

    private final OrgTransactions tx;
    private final MailSender mail;

    public EmergencyNotices(OrgTransactions tx, MailSender mail) {
        this.tx = tx;
        this.mail = mail;
    }

    // ------------------------------------------------------------------ confirmacao e contestacao (publicas)

    public void confirm(UUID org, String token) {
        use("ops_notice_confirm", "confirmacao", org, token);
    }

    public void contest(UUID org, String token) {
        use("ops_notice_contest", "contestacao", org, token);
    }

    private void use(String function, String what, UUID org, String token) {
        boolean ok = false;
        if (Tokens.isValidFormat(token)) {
            ok = tx.inOrganization(org, Mode.READ_WRITE, j -> j.sql("SELECT public." + function + "(?, ?)")
                    .param(org).param(Tokens.sha256Hex(token)).query(Boolean.class).single());
        }
        LOG.info("evento=aviso_mfa_emergencia.{} resultado={} org={} correlacao={}", what, ok ? "aceito" : "recusado", org,
                RequestContext.correlation());
        if (!ok) {
            throw ApiException.invalid("token");
        }
    }

    // ------------------------------------------------------------------ emissao (worker)

    private record Pending(UUID notice, UUID org) {
    }

    private record Issued(boolean issued, boolean confirm, boolean contest, short kind, UUID org, UUID recipient) {
    }

    /** Emite os avisos pendentes: um lote por chamada. Devolve quantos e-mails foram entregues a porta de e-mail. */
    public int dispatchPending() {
        List<Pending> pending = tx.withoutContext(j -> j.sql("SELECT r_aviso, r_organizacao FROM public.ops_notice_pending(?)").param(BATCH)
                .query((rs, n) -> new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list());
        int sent = 0;
        for (Pending p : pending) {
            String confirmToken = Tokens.newToken();
            String contestToken = Tokens.newToken();
            Issued i = tx.withoutContext(j -> j.sql("""
                    SELECT r_emitido, r_confirmar, r_contestar, r_tipo, r_organizacao, r_destinatario
                      FROM public.ops_notice_issue(?, ?, ?)
                    """).param(p.notice()).param(Tokens.sha256Hex(confirmToken)).param(Tokens.sha256Hex(contestToken))
                    .query((rs, n) -> new Issued(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getShort(4),
                            rs.getObject(5, UUID.class), rs.getObject(6, UUID.class))).single());
            if (!i.issued()) {
                LOG.info("evento=aviso_mfa_emergencia.descartado org={}", p.org());
                continue;
            }
            Optional<String> email = tx.inOrganization(i.org(), Mode.READ_ONLY,
                    j -> j.sql("SELECT email FROM public.users WHERE user_id = ?").param(i.recipient()).query(String.class).optional());
            MailSender.Kind kind = switch (i.kind()) {
                case 1 -> MailSender.Kind.NOTICE_MFA_EMERGENCY_HOLDER;
                case 2 -> MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN;
                default -> MailSender.Kind.NOTICE_MFA_EMERGENCY_EXECUTED;
            };
            if (email.isEmpty()) {
                LOG.warn("evento=aviso_mfa_emergencia.sem_destinatario tipo={} org={}", kind, i.org());
                continue;
            }
            try {
                mail.sendEmergencyNotice(kind, i.org(), email.get(), i.confirm() ? confirmToken : null, i.contest() ? contestToken : null);
                sent++;
                LOG.info("evento=aviso_mfa_emergencia.entregue_a_porta tipo={} org={}", kind, i.org());
            } catch (RuntimeException e) {
                // aviso emitido e nao entregue: o operador reenvia (novo token); aceite SMTP nunca conta como confirmacao
                LOG.warn("evento=aviso_mfa_emergencia.falha_envio tipo={} org={}", kind, i.org());
            }
        }
        return sent;
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
