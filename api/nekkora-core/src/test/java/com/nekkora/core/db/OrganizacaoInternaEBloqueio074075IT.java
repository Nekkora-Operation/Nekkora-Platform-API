package com.nekkora.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nekkora.core.testsupport.RealDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Migrations 074 e 075 (NEKKORA-LICENCIAMENTO-002, decisoes de 22 e 23/11), num PostgreSQL 16 real com 001-075 aplicadas pelo
 * migrador suportado: (1) a 074 aplicou com SUPERUSER so na janela dela e a 075 sem elevacao; o migrador terminou 0|0|0;
 * (2) a organizacao DESIGNADA fica fora do limite de vazias - pela designacao, nunca pelo nome - e volta a contar na retirada,
 * travando novas criacoes sem mexer em organizacao nenhuma; o dono da identidade so le organizacao e retirada da designacao;
 * (3) a leitura minima do bloqueio: so platform e ingestion, so organization_id e lifted_at, so a organizacao do contexto;
 * sem escrita; portal e provisionamento sem nada; (4) a organizacao interna designada nao e bloqueada por aqui.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrganizacaoInternaEBloqueio074075IT {

    private static RealDatabase db;
    private static final UUID A = UUID.fromString("74000000-0000-4000-8000-00000000000a");
    private static final UUID B = UUID.fromString("74000000-0000-4000-8000-00000000000b");
    /** Mesmo NOME de uma organizacao interna, sem designacao: conta normalmente. */
    private static final UUID HOMONIMA = UUID.fromString("74000000-0000-4000-8000-00000000000c");
    private static final UUID BLOQUEADA = UUID.fromString("75000000-0000-4000-8000-00000000000d");
    private static final UUID OUTRA = UUID.fromString("75000000-0000-4000-8000-00000000000e");
    private static final UUID PESSOA = UUID.fromString("74000000-0000-4000-8000-0000000000ff");

    @BeforeAll
    static void setUp() throws Exception {
        db = RealDatabase.shared();
        db.asSuperuser("INSERT INTO organizations (organization_id, name, status) VALUES ('" + A + "', 'Interna 074', 1), ('" + B + "', 'Vazia 074', 1), ('"
                        + HOMONIMA + "', 'Nekkora Operacoes', 1), ('" + BLOQUEADA + "', 'Bloqueada 075', 1), ('" + OUTRA + "', 'Outra 075', 1) ON CONFLICT DO NOTHING",
                "INSERT INTO identities (identity_id, email, display_name, email_verified_at, status) VALUES ('" + PESSOA
                        + "', 'responsavel074@exemplo.test', 'Responsavel sintetica', now(), 2) ON CONFLICT DO NOTHING",
                "INSERT INTO organization_responsibility (organization_id, responsible_identity_id, source) VALUES ('" + A + "', '" + PESSOA + "', 1), ('"
                        + B + "', '" + PESSOA + "', 1), ('" + HOMONIMA + "', '" + PESSOA + "', 1) ON CONFLICT DO NOTHING");
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = db.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet r = st.executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    /** Como o migrador comum (SET ROLE: a RLS vale), numa transacao, com a organizacao em conferencia (073: nekkora.operador_org). */
    private static void migrador(UUID org, String... sqls) throws SQLException {
        try (Connection c = db.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL ROLE migrador_nekkora");
                st.execute("SELECT set_config('nekkora.operador_org', '" + org + "', true)");
                for (String sql : sqls) {
                    st.execute(sql);
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Como o dono da identidade, com a identidade em contexto (como as funcoes SECURITY DEFINER da 067 fazem). */
    private static String dono(String sql) throws SQLException {
        try (Connection c = db.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL ROLE nekkora_identity_owner");
                st.execute("SELECT set_config('nekkora.fn_identity_id', '" + PESSOA + "', true)");
                try (ResultSet r = st.executeQuery(sql)) {
                    return r.next() ? r.getString(1) : null;
                }
            } finally {
                c.rollback();
            }
        }
    }

    /** Como um papel da aplicacao, com a organizacao em contexto; devolve a primeira coluna ou o SQLSTATE da recusa. */
    private static String papel(String role, UUID org, String sql) {
        try (Connection c = db.connectAs(role)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                if (org != null) {
                    st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
                }
                if (st.execute(sql)) {
                    try (ResultSet r = st.getResultSet()) {
                        return r.next() ? r.getString(1) : null;
                    }
                }
                return "OK";
            } catch (SQLException e) {
                return e.getSQLState();
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String VAZIAS = "SELECT pg_catalog.cardinality(public.identity_empty_organizations('" + PESSOA + "'))";
    private static final String LIMITE = "SELECT coalesce(public.identity_org_limit_reason('" + PESSOA + "'), 'PODE')";

    @Test
    @Order(1)
    void a074AplicouSoNaJanelaDelaEA075SemElevacao() throws Exception {
        String log = db.serverLog();
        assertThat(log).contains("aplicada versao=074 elevada=SUPERUSER").contains("fim    versao=074 SUPERUSER revogado, estado 0|0|0")
                .contains("aplicada versao=075 elevada=nao");
        assertThat(one("SELECT rolsuper || ' ' || rolcreaterole || ' ' || rolbypassrls FROM pg_roles WHERE rolname = 'migrador_nekkora'"))
                .isEqualTo("false false false");
    }

    @Test
    @Order(2)
    void aInternaDesignadaFicaForaDoLimiteDeVaziasEVoltaAContarNaRetirada() throws Exception {
        // tres vazias da mesma responsavel, uma delas com o NOME de interna: sem designacao, todas contam e o limite (3) trava
        assertThat(dono(VAZIAS)).isEqualTo("3");
        assertThat(dono(LIMITE)).as("pelo nome nada muda").isEqualTo("EMPTY_LIMIT");
        migrador(A, "INSERT INTO nekkora_internal_organization_designations (organization_id, designated_by, designation_ticket, designation_reason) VALUES ('"
                + A + "', 'titular (teste 074)', 'LAB-074-1', 'organizacao interna designada no teste da 074')");
        assertThat(dono(VAZIAS)).as("so a designada sai da contagem").isEqualTo("2");
        assertThat(dono(LIMITE)).isEqualTo("PODE");
        assertThat(dono("SELECT public.identity_empty_organizations('" + PESSOA + "')::text")).doesNotContain(A.toString()).contains(HOMONIMA.toString());
        // o dono da identidade le so organizacao e retirada: nem quem designou, nem chamado, nem motivo; nada escreve
        for (String proibido : List.of("SELECT designated_by FROM nekkora_internal_organization_designations",
                "SELECT designation_reason FROM nekkora_internal_organization_designations",
                "SELECT designation_ticket FROM nekkora_internal_organization_designations",
                "UPDATE nekkora_internal_organization_designations SET withdrawn_at = now()")) {
            assertThatThrownBy(() -> dono(proibido)).as(proibido).hasMessageContaining("permission denied");
        }
        // retirada: volta a contar no ato; o limite trava NOVAS criacoes e nenhuma organizacao muda
        migrador(A, "UPDATE nekkora_internal_organization_designations SET withdrawn_at = now(), withdrawn_by = 'titular (teste 074)', "
                + "withdrawal_ticket = 'LAB-074-2', withdrawal_reason = 'retirada da designacao no teste da 074' WHERE organization_id = '" + A + "' AND withdrawn_at IS NULL");
        assertThat(dono(VAZIAS)).isEqualTo("3");
        assertThat(dono(LIMITE)).isEqualTo("EMPTY_LIMIT");
        assertThat(one("SELECT string_agg(status::text, ',' ORDER BY organization_id) FROM organizations WHERE organization_id IN ('" + A + "', '" + B + "', '"
                + HOMONIMA + "')")).as("nada apagado, encerrado nem desativado").isEqualTo("1,1,1");
        assertThat(one("SELECT count(*) || '|' || count(*) FILTER (WHERE withdrawn_at IS NOT NULL) FROM nekkora_internal_organization_designations WHERE "
                + "organization_id = '" + A + "'")).as("auditoria: designacao e retirada ficam").isEqualTo("1|1");
    }

    @Test
    @Order(3)
    void aLeituraDoBloqueioEMinimaPorColunaPorPapelEPorOrganizacao() throws Exception {
        migrador(BLOQUEADA, "INSERT INTO organization_nekkora_holds (organization_id, reason, registered_by) VALUES ('" + BLOQUEADA
                + "', 'irregularidade apurada no teste da 075', 'suporte (teste 075)')");
        for (String role : List.of("nekkora_platform", "nekkora_ingestion")) {
            assertThat(papel(role, BLOQUEADA, "SELECT public.organization_held('" + BLOQUEADA + "')")).as(role).isEqualTo("t");
            assertThat(papel(role, BLOQUEADA, "SELECT count(*) FROM organization_nekkora_holds WHERE lifted_at IS NULL")).as(role).isEqualTo("1");
            assertThat(papel(role, BLOQUEADA, "SELECT organization_id::text FROM organization_nekkora_holds")).as(role).isEqualTo(BLOQUEADA.toString());
            for (String proibido : List.of("SELECT reason FROM organization_nekkora_holds", "SELECT registered_by FROM organization_nekkora_holds",
                    "SELECT lift_reason FROM organization_nekkora_holds", "SELECT lifted_by FROM organization_nekkora_holds",
                    "SELECT hold_id FROM organization_nekkora_holds", "SELECT * FROM organization_nekkora_holds",
                    "INSERT INTO organization_nekkora_holds (organization_id, reason, registered_by) VALUES ('" + OUTRA + "', 'ataque pelo servico', 'ataque')",
                    "UPDATE organization_nekkora_holds SET lifted_at = now(), lifted_by = 'servico', lift_reason = 'levantado pelo servico'",
                    "DELETE FROM organization_nekkora_holds",
                    "SELECT count(*) FROM nekkora_internal_organization_designations", "SELECT count(*) FROM license_operators")) {
                assertThat(papel(role, BLOQUEADA, proibido)).as(role + ": " + proibido).isEqualTo("42501");
            }
            // isolamento: com outra organizacao em contexto, nao enxerga o bloqueio desta
            assertThat(papel(role, OUTRA, "SELECT public.organization_held('" + BLOQUEADA + "')")).as(role + " em outra organizacao").isEqualTo("f");
            assertThat(papel(role, OUTRA, "SELECT count(*) FROM organization_nekkora_holds")).isEqualTo("0");
            assertThat(papel(role, null, "SELECT count(*) FROM organization_nekkora_holds")).as(role + " sem contexto").isEqualTo("0");
        }
        for (String role : List.of("nekkora_portal", "nekkora_provisioning")) {
            assertThat(papel(role, BLOQUEADA, "SELECT organization_id FROM organization_nekkora_holds")).as(role).isEqualTo("42501");
            assertThat(papel(role, BLOQUEADA, "SELECT public.organization_held('" + BLOQUEADA + "')")).as(role).isEqualTo("42501");
        }
        // historico: com o bloqueio levantado e outro aberto, vale o aberto; levantado tudo, nao esta bloqueada
        migrador(BLOQUEADA, "UPDATE organization_nekkora_holds SET lifted_at = now(), lifted_by = 'suporte (teste 075)', lift_reason = 'resolvido no teste da 075' "
                + "WHERE organization_id = '" + BLOQUEADA + "' AND lifted_at IS NULL");
        assertThat(papel("nekkora_platform", BLOQUEADA, "SELECT public.organization_held('" + BLOQUEADA + "')")).isEqualTo("f");
        migrador(BLOQUEADA, "INSERT INTO organization_nekkora_holds (organization_id, reason, registered_by) VALUES ('" + BLOQUEADA
                + "', 'nova irregularidade no teste da 075', 'suporte (teste 075)')");
        assertThat(papel("nekkora_platform", BLOQUEADA, "SELECT public.organization_held('" + BLOQUEADA + "')")).isEqualTo("t");
        assertThat(one("SELECT status FROM organizations WHERE organization_id = '" + BLOQUEADA + "'")).as("separado da desativacao").isEqualTo("1");
    }

    @Test
    @Order(4)
    void aInternaDesignadaNaoEBloqueadaPorAquiEOBloqueioNasceAberto() throws Exception {
        migrador(OUTRA, "INSERT INTO nekkora_internal_organization_designations (organization_id, designated_by, designation_ticket, designation_reason) VALUES ('"
                + OUTRA + "', 'titular (teste 075)', 'LAB-075-1', 'organizacao interna designada no teste da 075')");
        assertThatThrownBy(() -> migrador(OUTRA, "INSERT INTO organization_nekkora_holds (organization_id, reason, registered_by) VALUES ('" + OUTRA
                + "', 'bloquear a interna no teste da 075', 'suporte (teste 075)')")).hasMessageContaining("interna designada");
        migrador(OUTRA, "UPDATE nekkora_internal_organization_designations SET withdrawn_at = now(), withdrawn_by = 'titular (teste 075)', "
                + "withdrawal_ticket = 'LAB-075-2', withdrawal_reason = 'retirada da designacao no teste da 075' WHERE organization_id = '" + OUTRA
                + "' AND withdrawn_at IS NULL");
        assertThatThrownBy(() -> migrador(OUTRA, "INSERT INTO organization_nekkora_holds (organization_id, reason, registered_by, lifted_at, lifted_by, lift_reason) "
                + "VALUES ('" + OUTRA + "', 'bloqueio ja levantado no teste', 'suporte (teste 075)', now(), 'suporte', 'levantado no nascimento')"))
                .hasMessageContaining("nasce aberto");
        assertThat(one("SELECT rolsuper || ' ' || rolcreaterole || ' ' || rolbypassrls FROM pg_roles WHERE rolname = 'migrador_nekkora'"))
                .isEqualTo("false false false");
    }
}
