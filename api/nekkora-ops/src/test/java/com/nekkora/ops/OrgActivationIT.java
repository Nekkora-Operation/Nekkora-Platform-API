package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.Credencial;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.cli.ExecutorAtos.Limites;
import com.nekkora.ops.cli.ExecutorAtos.ResultadoAtivacao;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Ativacao da organizacao e revisao da capacidade depois de ativada (022, {@code org_activation_activate} e
 * {@code org_activation_revise}) com operadores REAIS: certificado de cliente emitido pela CA operacional e registrado, papel
 * op_* membro do grupo nekkora_org_activation, conta responsavel provada pelo operador (ops_operator_platform_accounts,
 * prova emitida pela Platform). Cada recusa e conferida na auditoria e sem efeito.
 */
class OrgActivationIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private static final long GIB = 1L << 30;
    private static final long MIB_256 = 256L << 20;

    /** Operador do grupo de ativacao, com a conta (de outra organizacao) que ele provou controlar. */
    private record Ativador(OpsBanco.Operador op, UUID responsavel) {
    }

    private Ativador ativador(String apelido) {
        OpsBanco.Operador op = DB.operador(apelido);
        DB.sql("GRANT nekkora_org_activation TO " + op.papel());
        OpsBanco.Org interna = DB.organizacao();
        // vinculo pela CLI (vincular-conta): o titular emite o codigo na propria sessao; reusar o codigo e recusado
        String codigo = C.provaDeConta(interna.id(), interna.admin());
        assertThat(DB.cli(op).vincularConta(codigo).classe()).isEqualTo(Classe.OK);
        ExecutorAtos.VinculoConta reuso = DB.cli(op).vincularConta(codigo);
        assertThat(reuso.classe()).isEqualTo(Classe.RECUSA);
        assertThat(reuso.motivo()).isEqualTo("PROVA_INVALIDA");
        return new Ativador(op, interna.admin());
    }

    private short estado(UUID org) {
        return DB.valor(Short.class, "SELECT state FROM ops_activation.organization_states WHERE organization_id = ?", org);
    }

    private String limites(UUID org) {
        return DB.valor(String.class, "SELECT string_agg(c || ':' || coalesce((SELECT limit_mode || '/' || coalesce(limit_value::text, '-') "
                + "FROM get_effective_resource_limit(?, c)), 'ausente'), ' ' ORDER BY c) FROM generate_series(1, 5) c", org);
    }

    private long eventos(UUID org, String resultado, String motivo) {
        return DB.valor(Long.class, "SELECT count(*) FROM ops_activation.activation_events WHERE organization_id = ? AND result = ? "
                + "AND reason_code IS NOT DISTINCT FROM ?", org, resultado, motivo);
    }

    private long eventosDoAto(UUID org, int ato, String resultado, String motivo) {
        return DB.valor(Long.class, "SELECT count(*) FROM ops_activation.activation_events WHERE organization_id = ? AND act = ? "
                + "AND result = ? AND reason_code IS NOT DISTINCT FROM ?", org, (short) ato, resultado, motivo);
    }

    /** provision_resource_limit pelo papel real de provisionamento; devolve o SQLSTATE da recusa ou null se aceitou. */
    private String provisionarDireto(UUID org, int recurso, long valor, UUID autor) {
        try (Connection c = DB.comoServico("nekkora_provisioning");
             PreparedStatement ps = c.prepareStatement("SELECT provision_resource_limit(?, ?, 1::smallint, ?, ?)")) {
            ps.setObject(1, org);
            ps.setInt(2, recurso);
            ps.setLong(3, valor);
            ps.setObject(4, autor);
            ps.execute();
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private long revisoes(UUID org) {
        return DB.valor(Long.class, "SELECT count(*) FROM organization_resource_limit_revisions WHERE organization_id = ?", org);
    }

    @Test
    void organizacaoNovaNasceAguardandoAtivacaoComCapacidadeZero() {
        OpsBanco.Org o = DB.organizacao();
        assertThat(estado(o.id())).isEqualTo((short) 1);
        assertThat(DB.valor(Short.class, "SELECT origin FROM ops_activation.organization_states WHERE organization_id = ?", o.id())).isEqualTo((short) 1);
        // o caminho de provisionamento (papel real) nao abre capacidade antes da ativacao
        try (Connection c = DB.comoServico("nekkora_provisioning");
             PreparedStatement ps = c.prepareStatement("SELECT provision_resource_limit(?, 2, 1::smallint, 3, ?)")) {
            ps.setObject(1, o.id());
            ps.setObject(2, o.admin());
            assertThatThrownBy(ps::execute).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(revisoes(o.id())).isZero();
    }

    @Test
    void ativacaoAutorizadaAplicaEstadoLimitesEAuditoriaAtomicamente() {
        Ativador a = ativador("atv");
        OpsBanco.Org o = DB.organizacao();
        ResultadoAtivacao r = DB.cli(a.op()).ativarOrganizacao(o.id(), a.responsavel(), "contrato piloto aprovado (ticket CS-1)", 2, 1, 10,
                GIB, MIB_256);
        assertThat(r.classe()).isEqualTo(Classe.OK);
        assertThat(r.evento()).isNotNull();
        assertThat(r.aplicados()).isEqualTo(new Limites(2, 1, 10, GIB, MIB_256));
        assertThat(estado(o.id())).isEqualTo((short) 2);
        assertThat(limites(o.id())).isEqualTo("1:1/2 2:1/1 3:1/10 4:1/" + GIB + " 5:1/" + MIB_256);
        // auditoria: operador, papel da sessao, certificado lido pelo servidor, responsavel, motivo, valores e as 5 revisoes
        List<List<Object>> ev = DB.linhas("SELECT e.operator_id, e.session_role, e.cert_serial IS NOT NULL, e.cert_issuer_dn IS NOT NULL, "
                + "e.responsible_user_id, e.motive, e.workers_registered, e.concurrent_executions, e.pending_executions, "
                + "e.log_bytes_daily_alert, e.event_bytes_daily_alert, cardinality(e.limit_revision_ids), s.activation_event_id = e.event_id, "
                + "s.activated_at = e.created_at FROM ops_activation.activation_events e JOIN ops_activation.organization_states s "
                + "USING (organization_id) WHERE e.event_id = ?", r.evento());
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0)).containsExactly(a.op().id(), a.op().papel(), true, true, a.responsavel(), "contrato piloto aprovado (ticket CS-1)",
                2L, 1L, 10L, GIB, MIB_256, 5, true, true);
        // as revisoes apontam a conta responsavel como autoria do provisionamento e sao exatamente as do evento
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM organization_resource_limit_revisions r JOIN ops_activation.activation_events e "
                + "ON r.revision_id = ANY (e.limit_revision_ids) WHERE e.event_id = ? AND r.provisioned_by_user_id = ? AND r.organization_id = ?",
                r.evento(), a.responsavel(), o.id())).isEqualTo(5);
        assertThat(DB.valor(Short.class, "SELECT act FROM ops_activation.activation_events WHERE event_id = ?", r.evento())).isEqualTo((short) 1);
        // depois de ativada, o provisionamento direto continua barrado (D-ATV-2): so a revisao autorizada e auditada muda limite
        for (int recurso = 1; recurso <= 5; recurso++) {
            assertThat(provisionarDireto(o.id(), recurso, 3, a.responsavel())).isEqualTo("42501");
        }
        assertThat(limites(o.id())).isEqualTo("1:1/2 2:1/1 3:1/10 4:1/" + GIB + " 5:1/" + MIB_256);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops_activation.open_acts")).isZero();
    }

    @Test
    void revisaoAutorizadaMudaOsLimitesComAuditoria() {
        Ativador a = ativador("rev");
        OpsBanco.Org o = DB.organizacao();
        ExecutorAtos cli = DB.cli(a.op());
        assertThat(cli.ativarOrganizacao(o.id(), a.responsavel(), "ativacao", 1, 1, 5, null, null).classe()).isEqualTo(Classe.OK);
        long revAntes = revisoes(o.id());
        ResultadoAtivacao r = cli.revisarCapacidade(o.id(), a.responsavel(), "aditivo de contrato (ticket CS-2)", 4, 2, 40, GIB, null);
        assertThat(r.classe()).isEqualTo(Classe.OK);
        assertThat(r.aplicados()).isEqualTo(new Limites(4, 2, 40, GIB, null));
        assertThat(limites(o.id())).isEqualTo("1:1/4 2:1/2 3:1/40 4:1/" + GIB + " 5:2/-");
        assertThat(revisoes(o.id())).isEqualTo(revAntes + 5);
        List<List<Object>> ev = DB.linhas("SELECT act, operator_id, session_role, cert_serial IS NOT NULL, responsible_user_id, motive, "
                + "workers_registered, concurrent_executions, pending_executions, log_bytes_daily_alert, event_bytes_daily_alert, "
                + "cardinality(limit_revision_ids) FROM ops_activation.activation_events WHERE event_id = ?", r.evento());
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0)).containsExactly(2, a.op().id(), a.op().papel(), true, a.responsavel(), "aditivo de contrato (ticket CS-2)",
                4L, 2L, 40L, GIB, null, 5);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM organization_resource_limit_revisions r JOIN ops_activation.activation_events e "
                + "ON r.revision_id = ANY (e.limit_revision_ids) WHERE e.event_id = ? AND r.provisioned_by_user_id = ? AND r.organization_id = ?",
                r.evento(), a.responsavel(), o.id())).isEqualTo(5);
        // o estado e a ativacao original nao mudam
        assertThat(estado(o.id())).isEqualTo((short) 2);
        assertThat(eventosDoAto(o.id(), 1, "OK", null)).isEqualTo(1);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops_activation.open_acts")).isZero();
    }

    @Test
    void revisaoRecusadaAuditadaESemEfeito() {
        Ativador a = ativador("rvr");
        ExecutorAtos cli = DB.cli(a.op());
        // organizacao aguardando ativacao: a revisao nao substitui a ativacao
        OpsBanco.Org aguardando = DB.organizacao();
        assertThat(cli.revisarCapacidade(aguardando.id(), a.responsavel(), "antes da ativacao", 1, 1, 1, null, null).motivo()).isEqualTo("NAO_ATIVADA");
        assertThat(estado(aguardando.id())).isEqualTo((short) 1);
        assertThat(revisoes(aguardando.id())).isZero();
        // ativada: limites iguais aos vigentes, parametros invalidos, responsavel nao provado
        OpsBanco.Org o = DB.organizacao();
        assertThat(cli.ativarOrganizacao(o.id(), a.responsavel(), "ativacao", 2, 1, 5, null, null).classe()).isEqualTo(Classe.OK);
        String antes = limites(o.id());
        long revAntes = revisoes(o.id());
        ResultadoAtivacao igual = cli.revisarCapacidade(o.id(), a.responsavel(), "igual", 2, 1, 5, null, null);
        assertThat(igual.motivo()).isEqualTo("SEM_MUDANCA");
        assertThat(igual.evento()).isNotNull();
        assertThat(igual.aplicados()).isNull();
        assertThat(cli.revisarCapacidade(o.id(), a.responsavel(), "zero", 0, 1, 5, null, null).motivo()).isEqualTo("PARAMETRO_INVALIDO");
        assertThat(cli.revisarCapacidade(o.id(), a.responsavel(), "", 3, 1, 5, null, null).motivo()).isEqualTo("PARAMETRO_INVALIDO");
        assertThat(cli.revisarCapacidade(o.id(), o.admin(), "responsavel errado", 3, 1, 5, null, null).motivo()).isEqualTo("RESPONSAVEL_NAO_VINCULADO");
        assertThat(limites(o.id())).isEqualTo(antes);
        assertThat(revisoes(o.id())).isEqualTo(revAntes);
        assertThat(eventosDoAto(o.id(), 2, "OK", null)).isZero();
        assertThat(eventosDoAto(o.id(), 2, "RECUSA", "SEM_MUDANCA")).isEqualTo(1);
        assertThat(eventosDoAto(o.id(), 2, "RECUSA", "PARAMETRO_INVALIDO")).isEqualTo(2);
        assertThat(eventosDoAto(aguardando.id(), 2, "RECUSA", "NAO_ATIVADA")).isEqualTo(1);
    }

    @Test
    void revisaoConcorrenteIgualEsperaAPrimeiraERecebeSemMudanca() throws Exception {
        Ativador a = ativador("rvc");
        OpsBanco.Org o = DB.organizacao();
        ExecutorAtos cliB = DB.cli(a.op());
        assertThat(cliB.ativarOrganizacao(o.id(), a.responsavel(), "ativacao", 1, 1, 2, null, null).classe()).isEqualTo(Classe.OK);
        try (Credencial cred = DB.credencial(a.op()); Connection ca = DB.conectar(cred)) {
            ca.setAutoCommit(false);
            String ra;
            try (PreparedStatement ps = ca.prepareStatement("SELECT r_resultado FROM org_activation_revise(?, ?, 'concorrente A', 3, 2, 20, NULL, NULL)")) {
                ps.setObject(1, o.id());
                ps.setObject(2, a.responsavel());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    ra = rs.getString(1);
                }
            }
            assertThat(ra).isEqualTo("OK");
            CompletableFuture<ResultadoAtivacao> b = CompletableFuture.supplyAsync(
                    () -> cliB.revisarCapacidade(o.id(), a.responsavel(), "concorrente B", 3, 2, 20, null, null));
            esperarBloqueio("org_activation_revise");
            assertThat(b).isNotDone();
            ca.commit();
            ResultadoAtivacao rb = b.get(60, TimeUnit.SECONDS);
            assertThat(rb.classe()).isEqualTo(Classe.RECUSA);
            assertThat(rb.motivo()).isEqualTo("SEM_MUDANCA");
        }
        assertThat(limites(o.id())).isEqualTo("1:1/3 2:1/2 3:1/20 4:2/- 5:2/-");
        assertThat(eventosDoAto(o.id(), 2, "OK", null)).isEqualTo(1);
    }

    /**
     * Duas revisoes simultaneas com valores DIFERENTES: a organizacao serializa os atos (FOR UPDATE); cada revisao substitui os
     * cinco limites pelos que pediu. Vale a ultima revisao APLICADA COM SUCESSO na ordem de serializacao (nao necessariamente a
     * ultima requisicao recebida): a segunda espera, aplica os proprios valores depois do commit da primeira e vale como
     * vigente; o resultado devolvido a ela traz o identificador do ato e os limites aplicados. As duas ficam auditadas com autorizacao, valores e revisoes proprias, na ordem do lock, e o ultimo ato OK
     * auditado e exatamente o limite vigente.
     */
    @Test
    void revisaoConcorrenteDiferenteSerializaNaOrdemDoLockEAuditaAsDuas() throws Exception {
        Ativador a = ativador("rvd");
        Ativador b = ativador("rve");
        OpsBanco.Org o = DB.organizacao();
        assertThat(DB.cli(a.op()).ativarOrganizacao(o.id(), a.responsavel(), "ativacao", 1, 1, 2, null, null).classe()).isEqualTo(Classe.OK);
        long revAntes = revisoes(o.id());
        ExecutorAtos cliB = DB.cli(b.op());
        String eventoA;
        ResultadoAtivacao rb;
        try (Credencial cred = DB.credencial(a.op()); Connection ca = DB.conectar(cred)) {
            ca.setAutoCommit(false);
            try (PreparedStatement ps = ca.prepareStatement("SELECT r_resultado, r_evento FROM org_activation_revise(?, ?, 'concorrente A', 3, 2, 20, NULL, NULL)")) {
                ps.setObject(1, o.id());
                ps.setObject(2, a.responsavel());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("OK");
                    eventoA = rs.getString(2);
                }
            }
            CompletableFuture<ResultadoAtivacao> fb = CompletableFuture.supplyAsync(
                    () -> cliB.revisarCapacidade(o.id(), b.responsavel(), "concorrente B", 5, 4, 50, GIB, null));
            esperarBloqueio("org_activation_revise");
            assertThat(fb).isNotDone();
            // enquanto A nao confirma, nada de A nem de B e visivel
            assertThat(limites(o.id())).isEqualTo("1:1/1 2:1/1 3:1/2 4:2/- 5:2/-");
            ca.commit();
            rb = fb.get(60, TimeUnit.SECONDS);
            assertThat(rb.classe()).isEqualTo(Classe.OK);
            assertThat(rb.evento()).isNotEqualTo(UUID.fromString(eventoA));
            assertThat(rb.aplicados()).isEqualTo(new Limites(5, 4, 50, GIB, null));
        }
        // vigente: os valores de B, aplicados depois de A
        assertThat(limites(o.id())).isEqualTo("1:1/5 2:1/4 3:1/50 4:1/" + GIB + " 5:2/-");
        assertThat(revisoes(o.id())).isEqualTo(revAntes + 10);
        // auditoria: dois atos OK, cada um com o proprio operador, responsavel, valores e 5 revisoes, na ordem do lock
        List<List<Object>> ev = DB.linhas("SELECT operator_id, responsible_user_id, motive, workers_registered, concurrent_executions, "
                + "pending_executions, log_bytes_daily_alert, cardinality(limit_revision_ids) FROM ops_activation.activation_events "
                + "WHERE organization_id = ? AND act = 2 AND result = 'OK' ORDER BY created_at, event_id", o.id());
        assertThat(ev).hasSize(2);
        assertThat(ev.get(0)).containsExactly(a.op().id(), a.responsavel(), "concorrente A", 3L, 2L, 20L, null, 5);
        assertThat(ev.get(1)).containsExactly(b.op().id(), b.responsavel(), "concorrente B", 5L, 4L, 50L, GIB, 5);
        assertThat(eventosDoAto(o.id(), 2, "RECUSA", null)).isZero();
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops_activation.activation_events WHERE organization_id = ? AND result = 'RECUSA'",
                o.id())).isZero();
        // as revisoes de limite vigentes (mesma ordem de get_effective_resource_limit) sao exatamente as do ultimo ato auditado (B)
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM (SELECT DISTINCT ON (resource_code) revision_id FROM organization_resource_limit_revisions "
                + "WHERE organization_id = ? AND resource_code BETWEEN 1 AND 5 ORDER BY resource_code, effective_from DESC, revision_number DESC) v "
                + "JOIN ops_activation.activation_events e ON e.event_id = ? AND v.revision_id = ANY (e.limit_revision_ids)",
                o.id(), rb.evento())).isEqualTo(5);
        // repetir os valores de A agora nao e SEM_MUDANCA (a referencia e o ultimo ato, de B): aplica e volta a A
        assertThat(DB.cli(a.op()).revisarCapacidade(o.id(), a.responsavel(), "volta a A", 3, 2, 20, null, null).classe()).isEqualTo(Classe.OK);
        assertThat(limites(o.id())).isEqualTo("1:1/3 2:1/2 3:1/20 4:2/- 5:2/-");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops_activation.open_acts")).isZero();
    }

    @Test
    void repeticaoERecusadaComJaAtivadaSemNovoEfeito() {
        Ativador a = ativador("rep");
        OpsBanco.Org o = DB.organizacao();
        ExecutorAtos cli = DB.cli(a.op());
        assertThat(cli.ativarOrganizacao(o.id(), a.responsavel(), "primeira", 1, 1, 5, null, null).classe()).isEqualTo(Classe.OK);
        String antes = limites(o.id());
        long revAntes = revisoes(o.id());
        ResultadoAtivacao r = cli.ativarOrganizacao(o.id(), a.responsavel(), "segunda", 9, 9, 99, null, null);
        assertThat(r.classe()).isEqualTo(Classe.RECUSA);
        assertThat(r.motivo()).isEqualTo("JA_ATIVADA");
        assertThat(limites(o.id())).isEqualTo(antes).isEqualTo("1:1/1 2:1/1 3:1/5 4:2/- 5:2/-");
        assertThat(revisoes(o.id())).isEqualTo(revAntes);
        assertThat(eventos(o.id(), "OK", null)).isEqualTo(1);
        assertThat(eventos(o.id(), "RECUSA", "JA_ATIVADA")).isEqualTo(1);
    }

    @Test
    void ativacaoConcorrenteEsperaAPrimeiraERecebeJaAtivada() throws Exception {
        Ativador a = ativador("conc");
        OpsBanco.Org o = DB.organizacao();
        // credencial de B emitida ANTES: o registro do certificado trava o operador, que A segura FOR SHARE durante o ato
        ExecutorAtos cliB = DB.cli(a.op());
        // A: chamada numa transacao do proprio operador, ainda sem commit (segura o lock da organizacao)
        try (Credencial cred = DB.credencial(a.op()); Connection ca = DB.conectar(cred)) {
            ca.setAutoCommit(false);
            String ra;
            try (PreparedStatement ps = ca.prepareStatement("SELECT r_resultado FROM org_activation_activate(?, ?, 'concorrente A', 1, 1, 2, NULL, NULL)")) {
                ps.setObject(1, o.id());
                ps.setObject(2, a.responsavel());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    ra = rs.getString(1);
                }
            }
            assertThat(ra).isEqualTo("OK");
            // B: mesma organizacao pela CLI, em paralelo; tem de ESPERAR o lock da organizacao
            CompletableFuture<ResultadoAtivacao> b = CompletableFuture.supplyAsync(
                    () -> cliB.ativarOrganizacao(o.id(), a.responsavel(), "concorrente B", 3, 3, 30, null, null));
            esperarBloqueio("org_activation_activate");
            assertThat(b).isNotDone();
            ca.commit();
            ResultadoAtivacao rb = b.get(60, TimeUnit.SECONDS);
            assertThat(rb.classe()).isEqualTo(Classe.RECUSA);
            assertThat(rb.motivo()).isEqualTo("JA_ATIVADA");
        }
        assertThat(limites(o.id())).isEqualTo("1:1/1 2:1/1 3:1/2 4:2/- 5:2/-");
        assertThat(eventos(o.id(), "OK", null)).isEqualTo(1);
    }

    /** Espera ate haver um backend esperando lock com a consulta indicada (o intercalamento controlado). */
    private void esperarBloqueio(String trecho) throws InterruptedException {
        long fim = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < fim) {
            Long n = DB.valor(Long.class, "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE ?", "%" + trecho + "%");
            if (n != null && n > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("a segunda ativacao nao chegou a esperar o lock; sessoes: " + DB.linhas(
                "SELECT usename, state, wait_event_type, wait_event, left(query, 120) FROM pg_stat_activity WHERE usename LIKE 'op\\_%'"));
    }

    @Test
    void recusasAuditadasESemEfeito() {
        Ativador a = ativador("rec");
        ExecutorAtos cli = DB.cli(a.op());
        // responsavel nao provado por este operador
        OpsBanco.Org o1 = DB.organizacao();
        ResultadoAtivacao r1 = cli.ativarOrganizacao(o1.id(), o1.admin(), "responsavel errado", 1, 1, 1, null, null);
        assertThat(r1.motivo()).isEqualTo("RESPONSAVEL_NAO_VINCULADO");
        // parametros: recurso 1-3 abaixo de 1, motivo vazio, limiar 0
        assertThat(cli.ativarOrganizacao(o1.id(), a.responsavel(), "zero", 0, 1, 1, null, null).motivo()).isEqualTo("PARAMETRO_INVALIDO");
        assertThat(cli.ativarOrganizacao(o1.id(), a.responsavel(), "  ", 1, 1, 1, null, null).motivo()).isEqualTo("PARAMETRO_INVALIDO");
        assertThat(cli.ativarOrganizacao(o1.id(), a.responsavel(), "limiar", 1, 1, 1, 0L, null).motivo()).isEqualTo("PARAMETRO_INVALIDO");
        // organizacao suspensa: nao ativa e continua distinguivel (status 2, estado 1)
        OpsBanco.Org o2 = DB.organizacao();
        DB.sqlNa(o2.id(), "UPDATE organizations SET status = 2 WHERE organization_id = '" + o2.id() + "'");
        assertThat(cli.ativarOrganizacao(o2.id(), a.responsavel(), "suspensa", 1, 1, 1, null, null).motivo()).isEqualTo("ORGANIZACAO_NAO_ATIVA");
        assertThat(estado(o2.id())).isEqualTo((short) 1);
        // inexistente
        UUID nenhuma = UUID.randomUUID();
        assertThat(cli.ativarOrganizacao(nenhuma, a.responsavel(), "inexistente", 1, 1, 1, null, null).motivo()).isEqualTo("ORGANIZACAO_INEXISTENTE");
        // vinculo impeditivo: o operador tem conta provada NA organizacao alvo
        OpsBanco.Org o3 = DB.organizacao();
        assertThat(C.vincularConta(a.op(), C.provaDeConta(o3.id(), o3.admin()))).isEqualTo("OK");
        assertThat(cli.ativarOrganizacao(o3.id(), a.responsavel(), "proprio vinculo", 1, 1, 1, null, null).motivo()).isEqualTo("VINCULO_IMPEDITIVO");
        for (UUID o : List.of(o1.id(), o2.id(), o3.id())) {
            assertThat(estado(o)).isEqualTo((short) 1);
            assertThat(revisoes(o)).isZero();
            assertThat(eventos(o, "OK", null)).isZero();
        }
        assertThat(eventos(o1.id(), "RECUSA", "PARAMETRO_INVALIDO")).isEqualTo(3);
        assertThat(eventos(nenhuma, "RECUSA", "ORGANIZACAO_INEXISTENTE")).isEqualTo(1);
    }

    @Test
    void foraDoGrupoServicosEAdministradorDaOrganizacaoNaoAtivam() throws SQLException {
        OpsBanco.Org o = DB.organizacao();
        // operador registrado e com certificado, mas fora do grupo nekkora_org_activation
        OpsBanco.Operador fora = DB.operador("fora", "VERIFICAR", "APROVAR", "EXECUTAR", "CANCELAR", "CONSULTAR", "CADASTRAR");
        ResultadoAtivacao r = DB.cli(fora).ativarOrganizacao(o.id(), o.admin(), "fora do grupo", 1, 1, 1, null, null);
        assertThat(r.classe()).isEqualTo(Classe.FALHA_CONFIRMADA);
        assertThat(r.sqlstate()).isEqualTo("42501");
        ResultadoAtivacao rr = DB.cli(fora).revisarCapacidade(o.id(), o.admin(), "fora do grupo", 1, 1, 1, null, null);
        assertThat(rr.classe()).isEqualTo(Classe.FALHA_CONFIRMADA);
        assertThat(rr.sqlstate()).isEqualTo("42501");
        // servicos: Platform (onde atua o ORG_ADMIN do cliente), Portal e provisionamento
        for (String papel : List.of("nekkora_platform", "nekkora_portal", "nekkora_provisioning")) {
            for (String funcao : List.of("org_activation_activate", "org_activation_revise")) {
                try (Connection c = DB.comoServico(papel);
                     PreparedStatement ps = c.prepareStatement("SELECT * FROM " + funcao + "(?, ?, 'x', 1, 1, 1, NULL, NULL)")) {
                    ps.setObject(1, o.id());
                    ps.setObject(2, o.admin());
                    assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class)
                            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
                }
            }
        }
        // a Platform le so o estado, nunca as tabelas da ativacao
        try (Connection c = DB.comoServico("nekkora_platform")) {
            try (PreparedStatement ps = c.prepareStatement("SELECT activation_state FROM org_activation_states(ARRAY[?]::uuid[])")) {
                ps.setObject(1, o.id());
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getShort(1)).isEqualTo((short) 1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM ops_activation.activation_events")) {
                assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
            }
        }
        assertThat(estado(o.id())).isEqualTo((short) 1);
    }
}
