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
 * Migration 073 (NEKKORA-LICENCIAMENTO-002, decisoes de 22/11): o procedimento do operador pelo MIGRADOR, sem elevacao.
 * Prova, num PostgreSQL 16 real com 001-073 aplicadas pelo migrador suportado: (1) a 073 aplicou sem SUPERUSER e o migrador
 * terminou 0|0|0; (2) os papeis da aplicacao nao alcancam as tabelas do procedimento; (3) o migrador so consegue o previsto:
 * designar e retirar (uma vigente; retirada uma vez; nada apagado), cadastrar (so na organizacao designada, com chamado e
 * justificativa), revogar (uma vez, com quem, chamado e motivo), RECADASTRAR como cadastro novo (a revogacao fica), a D7 e o
 * bloqueio (levantar uma vez); a leitura das conferencias so alcanca a organizacao declarada; nada fora disso.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperacaoPeloMigrador073IT {

    private static RealDatabase db;
    private static final UUID INTERNA = UUID.fromString("73000000-0000-4000-8000-000000000001");
    private static final UUID OUTRA = UUID.fromString("73000000-0000-4000-8000-000000000002");
    private static UUID operadora;
    private static UUID estranha;

    @BeforeAll
    static void setUp() throws Exception {
        db = RealDatabase.shared();
        db.asSuperuser("INSERT INTO organizations (organization_id, name, status) VALUES ('" + INTERNA + "', 'Nekkora Operacoes 073', 1), ('"
                + OUTRA + "', 'Outra 073', 1) ON CONFLICT DO NOTHING");
        operadora = UUID.fromString(user(INTERNA, "operadora073@exemplo.test"));
        estranha = UUID.fromString(user(OUTRA, "estranha073@exemplo.test"));
    }

    private static String user(UUID org, String email) throws SQLException {
        db.asSuperuserIn(org, "INSERT INTO users (organization_id, account_type, display_name, email, email_verified_at, status) VALUES ('" + org
                + "', 1, 'Pessoa sintetica', '" + email + "', now(), 2)");
        return one("SELECT user_id FROM users WHERE email = '" + email + "'");
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = db.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet r = st.executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        }
    }

    /** Como o migrador comum (SET ROLE a partir do superusuario: a RLS passa a valer), numa transacao; org = nekkora.operador_org. */
    private static String migrador(UUID org, String... sqls) throws SQLException {
        try (Connection c = db.connectAsSuperuser()) {
            c.setAutoCommit(false);
            String last = null;
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL ROLE migrador_nekkora");
                if (org != null) {
                    st.execute("SELECT set_config('nekkora.operador_org', '" + org + "', true)");
                }
                for (String sql : sqls) {
                    if (st.execute(sql)) {
                        try (ResultSet r = st.getResultSet()) {
                            last = r.next() ? r.getString(1) : null;
                        }
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
            return last;
        }
    }

    private static String designar(UUID org) {
        return "INSERT INTO nekkora_internal_organization_designations (organization_id, designated_by, designation_ticket, designation_reason) VALUES ('"
                + org + "', 'titular (teste 073)', 'LAB-073-1', 'organizacao interna do teste da 073')";
    }

    private static String cadastrar(UUID org, UUID user) {
        return "INSERT INTO license_operators (operator_user_id, organization_id, registered_by, registration_ticket, registration_justification) VALUES ('"
                + user + "', '" + org + "', 'titular (teste 073)', 'LAB-073-2', 'operadora de licencas do teste da 073')";
    }

    private static String revogar(UUID user) {
        return "UPDATE license_operators SET revoked_at = now(), revoked_by = 'titular (teste 073)', revocation_ticket = 'LAB-073-3', "
                + "revocation_reason = 'revogacao do teste da 073' WHERE operator_user_id = '" + user + "' AND revoked_at IS NULL";
    }

    @Test
    @Order(1)
    void aplicouSemElevacaoEOMigradorTerminouSemAtributos() throws Exception {
        assertThat(one("SELECT rolsuper || ' ' || rolcreaterole || ' ' || rolbypassrls FROM pg_roles WHERE rolname = 'migrador_nekkora'"))
                .isEqualTo("false false false");
        assertThat(db.serverLog()).contains("versao=073").doesNotContain("073 roda SEM elevacao");
        assertThat(one("SELECT count(*) FROM pg_policies WHERE policyname IN ('migrator_procedure', 'migrator_procedure_insert', "
                + "'migrator_procedure_update', 'operator_check') AND roles <> ARRAY['migrador_nekkora'::name]")).isEqualTo("0");
    }

    @Test
    @Order(2)
    void osPapeisDaAplicacaoNaoAlteramAsTabelasDoProcedimento() throws Exception {
        List<String> ataques = List.of(
                designar(OUTRA),
                "UPDATE nekkora_internal_organization_designations SET withdrawn_at = now()",
                "SELECT count(*) FROM nekkora_internal_organization_designations",
                cadastrar(INTERNA, operadora),
                "UPDATE license_operators SET revoked_at = now()",
                "DELETE FROM license_operators",
                "UPDATE license_rollout SET new_organizations_licensed = true, updated_by = 'ataque'",
                "INSERT INTO organization_nekkora_holds (organization_id, reason, registered_by) VALUES ('" + OUTRA + "', 'bloqueio por ataque', 'ataque')",
                "UPDATE organization_nekkora_holds SET lifted_at = now(), lifted_by = 'ataque'");
        for (String papel : List.of("nekkora_platform", "nekkora_portal", "nekkora_provisioning", "nekkora_ingestion")) {
            for (String sql : ataques) {
                try (Connection c = db.connectAs(papel); Statement st = c.createStatement()) {
                    assertThatThrownBy(() -> st.execute(sql)).as(papel + ": " + sql).isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                }
            }
        }
    }

    @Test
    @Order(3)
    void aLeituraDasConferenciasSoAlcancaAOrganizacaoDeclarada() throws Exception {
        assertThat(migrador(null, "SELECT count(*) FROM users")).as("sem organizacao declarada: nada").isEqualTo("0");
        assertThat(migrador(INTERNA, "SELECT count(*) FROM users WHERE email LIKE '%073@exemplo.test'")).isEqualTo("1");
        assertThat(migrador(INTERNA, "SELECT count(*) FROM users WHERE user_id = '" + estranha + "'")).isEqualTo("0");
        assertThat(migrador(INTERNA, "SELECT count(*) FROM organizations")).isEqualTo("1");
        // fora das conferencias, o migrador continua sem alcance (RLS forcada, sem politica): ex. licencas
        assertThat(migrador(INTERNA, "SELECT count(*) FROM organization_licenses")).isEqualTo("0");
        assertThatThrownBy(() -> migrador(INTERNA, "INSERT INTO organization_licenses (organization_id, license_number) VALUES ('" + INTERNA + "', 1)"))
                .hasMessageContaining("row-level security");
    }

    @Test
    @Order(4)
    void designacaoFormalUmaVigenteSemApagar() throws Exception {
        assertThatThrownBy(() -> migrador(INTERNA, "INSERT INTO nekkora_internal_organization_designations (organization_id, designated_by, "
                + "designation_ticket, designation_reason) VALUES ('" + INTERNA + "', 'titular', 'LAB', 'curto')")).isInstanceOf(SQLException.class);
        migrador(INTERNA, designar(INTERNA));
        assertThatThrownBy(() -> migrador(OUTRA, designar(OUTRA))).as("uma vigente por vez").hasMessageContaining("ux_nekkora_internal_organization_current");
        assertThatThrownBy(() -> migrador(INTERNA, "UPDATE nekkora_internal_organization_designations SET organization_id = '" + OUTRA + "'"))
                .hasMessageContaining("so a retirada");
        assertThatThrownBy(() -> migrador(INTERNA, "DELETE FROM nekkora_internal_organization_designations")).isInstanceOf(SQLException.class);
    }

    @Test
    @Order(5)
    void cadastroRevogacaoERecadastro() throws Exception {
        assertThatThrownBy(() -> migrador(INTERNA, "INSERT INTO license_operators (operator_user_id, organization_id, registered_by) VALUES ('"
                + operadora + "', '" + INTERNA + "', 'titular (teste 073)')")).hasMessageContaining("chamado e justificativa");
        assertThatThrownBy(() -> migrador(OUTRA, cadastrar(OUTRA, estranha))).hasMessageContaining("organizacao interna designada");
        migrador(INTERNA, cadastrar(INTERNA, operadora));
        assertThat(one("SELECT public.license_op_is_operator('" + operadora + "')")).isEqualTo("t");
        assertThatThrownBy(() -> migrador(INTERNA, cadastrar(INTERNA, operadora))).as("um vigente por conta").hasMessageContaining("ux_license_operators_active");
        assertThatThrownBy(() -> migrador(INTERNA, "UPDATE license_operators SET revoked_at = now() WHERE operator_user_id = '" + operadora + "'"))
                .hasMessageContaining("so a revogacao");
        assertThatThrownBy(() -> migrador(INTERNA, "UPDATE nekkora_internal_organization_designations SET withdrawn_at = now(), withdrawn_by = 'titular', "
                + "withdrawal_ticket = 'LAB-073-9', withdrawal_reason = 'retirada com operador vigente'")).hasMessageContaining("operador vigente");
        migrador(INTERNA, revogar(operadora));
        assertThat(one("SELECT public.license_op_is_operator('" + operadora + "')")).isEqualTo("f");
        assertThatThrownBy(() -> migrador(INTERNA, "UPDATE license_operators SET revoked_by = 'outra pessoa' WHERE operator_user_id = '" + operadora + "'"))
                .as("revogacao uma vez").hasMessageContaining("so a revogacao");
        // recadastro: cadastro NOVO, explicito; a revogacao anterior fica
        migrador(INTERNA, cadastrar(INTERNA, operadora));
        assertThat(one("SELECT count(*) || ' ' || count(*) FILTER (WHERE revoked_at IS NOT NULL) FROM license_operators WHERE operator_user_id = '"
                + operadora + "'")).isEqualTo("2 1");
        assertThat(one("SELECT public.license_op_is_operator('" + operadora + "')")).isEqualTo("t");
        assertThatThrownBy(() -> migrador(INTERNA, "DELETE FROM license_operators")).isInstanceOf(SQLException.class);
        migrador(INTERNA, revogar(operadora));
        migrador(INTERNA, "UPDATE nekkora_internal_organization_designations SET withdrawn_at = now(), withdrawn_by = 'titular (teste 073)', "
                + "withdrawal_ticket = 'LAB-073-4', withdrawal_reason = 'retirada da designacao no teste'");
        assertThatThrownBy(() -> migrador(INTERNA, cadastrar(INTERNA, operadora))).as("sem designacao vigente, sem cadastro")
                .hasMessageContaining("organizacao interna designada");
        assertThat(one("SELECT count(*) FROM nekkora_internal_organization_designations")).isEqualTo("1");
    }

    @Test
    @Order(6)
    void d7EBloqueioPeloMigrador() throws Exception {
        assertThat(migrador(null, "UPDATE license_rollout SET new_organizations_licensed = true, updated_by = 'titular (teste 073)', updated_at = now()",
                "SELECT new_organizations_licensed FROM license_rollout")).isEqualTo("t");
        migrador(null, "UPDATE license_rollout SET new_organizations_licensed = false, updated_by = 'titular (teste 073, restaurado)', updated_at = now()");
        assertThatThrownBy(() -> migrador(null, "INSERT INTO license_rollout (singleton, new_organizations_licensed, updated_by) VALUES (true, true, 'x')"))
                .isInstanceOf(SQLException.class);
        migrador(null, "INSERT INTO organization_nekkora_holds (organization_id, reason, registered_by) VALUES ('" + OUTRA
                + "', 'irregularidade apurada no teste 073', 'titular (teste 073)')");
        assertThatThrownBy(() -> migrador(null, "UPDATE organization_nekkora_holds SET reason = 'outro motivo qualquer' WHERE organization_id = '" + OUTRA + "'"))
                .hasMessageContaining("so o levantamento");
        assertThatThrownBy(() -> migrador(null, "UPDATE organization_nekkora_holds SET lifted_at = now(), lifted_by = 'titular (teste 073)' WHERE organization_id = '"
                + OUTRA + "'")).as("levantar sem motivo").hasMessageContaining("so o levantamento");
        migrador(null, "UPDATE organization_nekkora_holds SET lifted_at = now(), lifted_by = 'titular (teste 073)', lift_reason = 'irregularidade sanada no teste' "
                + "WHERE organization_id = '" + OUTRA + "'");
        assertThatThrownBy(() -> migrador(null, "UPDATE organization_nekkora_holds SET lifted_by = 'outra pessoa' WHERE organization_id = '" + OUTRA + "'"))
                .hasMessageContaining("so o levantamento");
        assertThatThrownBy(() -> migrador(null, "DELETE FROM organization_nekkora_holds")).isInstanceOf(SQLException.class);
        assertThat(one("SELECT rolsuper || ' ' || rolcreaterole || ' ' || rolbypassrls FROM pg_roles WHERE rolname = 'migrador_nekkora'"))
                .isEqualTo("false false false");
    }
}
