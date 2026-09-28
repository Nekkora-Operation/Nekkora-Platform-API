package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.ConcorrenciaIT.Aberta;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Inventario de locks, achados da 016 (I-2, I-3 e I-4). Contraprovas declaradas: os ensaios "ordem anterior" reproduzem
 * em SQL direto, com duas conexoes de superusuario e passos intercalados, a sequencia de locks descrita no inventario
 * (§4.2 e §4.3); eles provam que aquela ordem produz 40P01, nao executam a versao anterior da funcao. Os testes "depois"
 * usam as funcoes da 016 como estao e mostram espera simples (ou nenhuma espera), sem impasse.
 */
class InventarioLocksOpsIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private static void executar(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getSQLState(), e);
        }
    }

    private static String sqlstate(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
            return "00000";
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static void esperarAguardando(int n) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            Long k = DB.valor(Long.class, "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()");
            if (k != null && k >= n) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("nao houve " + n + " backends aguardando lock");
    }

    private static long aguardando() {
        return DB.valor(Long.class, "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()");
    }

    /** Duas conexoes; A faz a1, B faz b1, A pede a2 (bloqueia), B pede b2: devolve os SQLSTATE de a2 e b2. */
    private static List<String> ensaio(String a1, String b1, String a2, String b2) throws Exception {
        try (Connection a = DB.superusuario(); Connection b = DB.superusuario()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            executar(a, a1);
            executar(b, b1);
            CompletableFuture<String> x = CompletableFuture.supplyAsync(() -> sqlstate(a, a2));
            esperarAguardando(1);
            String y = sqlstate(b, b2);
            List<String> r = List.of(x.get(30, TimeUnit.SECONDS), y);
            a.rollback();
            b.rollback();
            return r;
        }
    }

    // ------------------------------------------------------------------ I-2

    /** Ordem anterior: registro de falha segurando a solicitacao (KEY SHARE) e pedindo a organizacao (FK do evento) x ato. */
    @Test
    void i2OrdemAnteriorDoRegistroDeFalhaProduzImpasse() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.verificada(eq, al);
        String org = al.org().id().toString();
        List<String> r = ensaio(
                "SELECT 1 FROM ops.ops_mfa_recovery_requests WHERE request_id = '" + s + "' FOR KEY SHARE",
                "SELECT 1 FROM organizations WHERE organization_id = '" + org + "' FOR UPDATE",
                "SELECT 1 FROM organizations WHERE organization_id = '" + org + "' FOR KEY SHARE",
                "SELECT 1 FROM ops.ops_mfa_recovery_requests WHERE request_id = '" + s + "' FOR UPDATE");
        assertThat(r).contains("40P01");
    }

    /** Depois: o registro de falha espera o ato (organizacao -> conta -> solicitacao) e conclui; nas duas ordens de chegada. */
    @Test
    void i2RegistroDeFalhaEsperaOAtoSemImpasseNasDuasOrdens() throws Exception {
        for (boolean atoPrimeiro : List.of(true, false)) {
            Cenarios.Equipe eq = C.equipe();
            Cenarios.Alvo al = C.alvo();
            UUID s = C.verificada(eq, al);
            String falha = "SELECT r_resultado FROM public.ops_mfa_record_failure('" + s + "', 'PRE_APROVAR', 'XX000', 'CHAMADA')";
            try (Aberta ato = new Aberta(eq.aprovador()); Aberta registro = new Aberta(eq.verificador())) {
                CompletableFuture<String> f;
                if (atoPrimeiro) {
                    assertThat(ato.chamar("ops_mfa_preapprove", s)[0]).isEqualTo("OK");
                    f = CompletableFuture.supplyAsync(() -> valor(registro.con, falha));
                    esperarAguardando(1);
                    ato.commit();
                    assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
                    registro.commit();
                } else {
                    assertThat(valor(registro.con, falha)).isEqualTo("OK");
                    CompletableFuture<String[]> a = CompletableFuture.supplyAsync(() -> {
                        try {
                            return ato.chamar("ops_mfa_preapprove", s);
                        } catch (SQLException e) {
                            return new String[] {"ERRO", e.getSQLState()};
                        }
                    });
                    esperarAguardando(1);
                    registro.commit();
                    assertThat(a.get(30, TimeUnit.SECONDS)[0]).isEqualTo("OK");
                    ato.commit();
                }
            }
            assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND act = 'FALHA'", s)).isEqualTo(1);
            assertThat(C.estado(s)).isEqualTo("PRE_APROVADA");
        }
    }

    /** Reconferencia: solicitacao inexistente e recusada, sem evento. */
    @Test
    void i2SolicitacaoInexistenteERecusada() {
        Cenarios.Equipe eq = C.equipe();
        UUID nada = UUID.randomUUID();
        assertThat(C.comoOperador(eq.verificador(), "SELECT r_motivo FROM public.ops_mfa_record_failure(?, 'EXECUTAR', 'XX000', 'CHAMADA')", nada))
                .isEqualTo("SOLICITACAO_INEXISTENTE");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ?", nada)).isZero();
    }

    private static String valor(Connection c, String sql) {
        try (Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            return "ERRO:" + e.getSQLState();
        }
    }

    // ------------------------------------------------------------------ I-3 e I-4

    /** Ordem anterior (com a FK de organizacao em ops_registry_events): vinculo segurando o operador e pedindo a organizacao x ato. */
    @Test
    void i3OrdemAnteriorDoVinculoComAFkDeOrganizacaoProduzImpasse() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        String org = al.org().id().toString();
        String op = eq.verificador().id().toString();
        List<String> r = ensaio(
                "SELECT 1 FROM ops.ops_operators WHERE operator_id = '" + op + "' FOR UPDATE",
                "SELECT 1 FROM organizations WHERE organization_id = '" + org + "' FOR UPDATE",
                "SELECT 1 FROM organizations WHERE organization_id = '" + org + "' FOR KEY SHARE",
                "SELECT 1 FROM ops.ops_operators WHERE operator_id = '" + op + "' FOR SHARE");
        assertThat(r).contains("40P01");
    }

    /**
     * Depois (I-3): com um ato segurando a organizacao, o vinculo de conta do operador conclui sem esperar por ela; o
     * evento continua gravando a organizacao (historico preservado).
     */
    @Test
    void i3VinculoNaoPedeAOrganizacaoEOEventoGuardaAOrganizacao() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        String prova = C.provaDeConta(al.org().id(), DB.membro(al.org()));
        try (Connection ato = DB.superusuario()) {
            ato.setAutoCommit(false);
            executar(ato, "SELECT 1 FROM organizations WHERE organization_id = '" + al.org().id() + "' FOR UPDATE");
            CompletableFuture<String> v = CompletableFuture.supplyAsync(() -> C.vincularConta(eq.executor(), prova));
            assertThat(v.get(30, TimeUnit.SECONDS)).as("sem esperar a organizacao").isEqualTo("OK");
            assertThat(aguardando()).isZero();
            ato.commit();
        }
        assertThat(DB.valor(UUID.class, "SELECT organization_id FROM ops.ops_registry_events WHERE kind = 'VINCULO_CONTA' AND actor_operator_id = ? "
                + "AND result = 'OK'", eq.executor().id())).isEqualTo(al.org().id());
    }

    /** Depois (I-4): com um ato segurando a organizacao, a prova de conta emitida pela Platform conclui sem esperar por ela. */
    @Test
    void i4ProvaDeContaNaoPedeAOrganizacao() throws Exception {
        Cenarios.Alvo al = C.alvo();
        UUID membro = DB.membro(al.org());
        try (Connection ato = DB.superusuario()) {
            ato.setAutoCommit(false);
            executar(ato, "SELECT 1 FROM organizations WHERE organization_id = '" + al.org().id() + "' FOR UPDATE");
            CompletableFuture<String> p = CompletableFuture.supplyAsync(() -> C.provaDeConta(al.org().id(), membro));
            assertThat(p.get(30, TimeUnit.SECONDS)).isNotBlank();
            assertThat(aguardando()).isZero();
            ato.commit();
        }
    }

    /** A FK retirada e so aquela: o catalogo nao tem FK de ops_registry_events para organizations, e as demais FKs seguem. */
    @Test
    void i3SoAFkDeOrganizacaoDoRegistroFoiRetirada() {
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND conrelid = 'ops.ops_registry_events'::regclass "
                + "AND confrelid = 'public.organizations'::regclass")).isZero();
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND conrelid = 'ops.ops_mfa_recovery_events'::regclass "
                + "AND confrelid = 'public.organizations'::regclass")).as("eventos das solicitacoes mantem a FK").isPositive();
    }
}
