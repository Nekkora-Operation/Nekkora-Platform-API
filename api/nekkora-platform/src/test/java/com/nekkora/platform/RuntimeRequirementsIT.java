package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c (parte 2) - dependencias de execucao declaradas para uma automacao, pelo papel
 * real nekkora_platform e pela API real: leitura estatica (so nome==versao entra; linha com problema impede salvar),
 * revisoes append-only com autoria, permissoes e guardas do banco. A regra do efetivo com o manifesto do pacote e conferida
 * em RuntimeRequirementsEffectiveTest; a igualdade com a leitura do aplicativo, em RequirementsTextTest (vetores comuns).
 */
class RuntimeRequirementsIT extends PlatformTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Ctx(Org org, BrowserClient admin, String o, String workspace, String automation) {
        String path() {
            return o + "/automations/" + automation + "/runtime-requirements";
        }
    }

    private Ctx setup() {
        Org org = newOrganization();
        BrowserClient b = browser();
        firstLogin(b, org.id(), new TestUser(org.adminId(), org.adminEmail(), PASSWORD.toCharArray()));
        String o = "/api/v1/o/" + org.id();
        BrowserClient.Response ws = b.post(o + "/workspaces", "{\"name\":\"WS dependencias\"}");
        assertThat(ws.status()).as(ws.body()).isEqualTo(201);
        BrowserClient.Response au = b.post(o + "/workspaces/" + ws.field("workspaceId") + "/automations", "{\"name\":\"Conciliacao\"}");
        assertThat(au.status()).as(au.body()).isEqualTo(201);
        return new Ctx(org, b, o, ws.field("workspaceId"), au.field("automationId"));
    }

    private BrowserClient.Response put(BrowserClient b, String path, String body) {
        return b.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json"));
    }

    private static String text(String s) {
        return JSON.writeValueAsString(s);
    }

    private static String value(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String asPlatform(UUID org, String... sqls) {
        try (Connection c = DB.connectAs("nekkora_platform")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
                for (String sql : sqls) {
                    st.execute(sql);
                }
                c.commit();
                return "OK";
            } catch (SQLException e) {
                c.rollback();
                return e.getSQLState();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void grantWorkspaceRole(Org o, TestUser u, UUID ws, String role) throws Exception {
        String m = value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + u.id() + "'");
        DB.asSuperuser("INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                + "SELECT '" + o.id() + "', '" + ws + "', '" + m + "', role_id, 2, 1, '" + o.adminMembership() + "' FROM roles WHERE code = '" + role + "'");
    }

    @Test
    void declararCriaRevisoesComAutoriaESoNomeIgualVersaoEntra() throws Exception {
        Ctx c = setup();
        // ninguem declarou; a automacao nao tem versao publicada
        JsonNode v = JSON.readTree(c.admin().get(c.path()).body());
        assertThat(v.get("declared").isNull()).isTrue();
        assertThat(v.get("published").isNull()).isTrue();
        assertThat(v.get("effective").toString()).isEqualTo("{\"dependencies\":[],\"conflicts\":[],\"resolved\":true}");

        // texto: lido estaticamente; repetida e usada uma vez; a ordem e a do texto
        String good = "requests==2.32.3\n# planilhas\npandas [excel] == 2.2.2\nRequests==2.32.3\n";
        BrowserClient.Response r = put(c.admin(), c.path(), "{\"source\":\"TEXT\",\"text\":" + text(good) + "}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        JsonNode d = JSON.readTree(r.body()).get("declared");
        assertThat(d.get("revision").asInt()).isEqualTo(1);
        assertThat(d.get("source").asString()).isEqualTo("TEXT");
        assertThat(d.get("dependencies").toString()).isEqualTo("[\"requests==2.32.3\",\"pandas[excel]==2.2.2\"]");
        assertThat(d.get("contentSha256").asString()).matches("[0-9a-f]{64}");
        assertThat(JSON.readTree(r.body()).get("effective").get("dependencies").toString()).isEqualTo("[\"requests==2.32.3\",\"pandas[excel]==2.2.2\"]");
        assertThat(value("SELECT declared_by_membership_id || '|' || source || '|' || workspace_id FROM automation_runtime_requirements WHERE automation_id = '"
                + c.automation() + "' AND revision_number = 1")).isEqualTo(c.org().adminMembership() + "|3|" + c.workspace());
        assertThat(value("SELECT count(*) FROM automation_admin_events WHERE automation_id = '" + c.automation() + "' AND act = 10 AND actor_membership_id = '"
                + c.org().adminMembership() + "'")).isEqualTo("1");

        // outra declaracao cria a revisao seguinte; a anterior fica
        BrowserClient.Response none = put(c.admin(), c.path(), "{\"source\":\"NONE_CONFIRMED\",\"expectedRevision\":1}");
        assertThat(none.status()).as(none.body()).isEqualTo(201);
        assertThat(JSON.readTree(none.body()).get("declared").get("revision").asInt()).isEqualTo(2);
        assertThat(JSON.readTree(none.body()).get("declared").get("dependencies").toString()).isEqualTo("[]");
        assertThat(value("SELECT count(*) FROM automation_runtime_requirements WHERE automation_id = '" + c.automation() + "'")).isEqualTo("2");
        assertThat(value("SELECT dependencies::text FROM automation_runtime_requirements WHERE automation_id = '" + c.automation() + "' AND revision_number = 1"))
                .contains("requests==2.32.3");
        // arquivo enviado (o conteudo do requirements.txt) e "so as do pacote"
        assertThat(put(c.admin(), c.path(), "{\"source\":\"FILE\",\"text\":" + text("idna==3.7\r\n") + ",\"expectedRevision\":2}").status()).isEqualTo(201);
        assertThat(put(c.admin(), c.path(), "{\"source\":\"PACKAGE\",\"expectedRevision\":3}").status()).isEqualTo(201);
        assertThat(JSON.readTree(c.admin().get(c.path()).body()).get("declared").get("source").asString()).isEqualTo("PACKAGE");
        assertThat(value("SELECT count(*) FROM automation_admin_events WHERE automation_id = '" + c.automation() + "' AND act = 10")).isEqualTo("4");
    }

    @Test
    void linhaComProblemaImpedeSalvarEAAnaliseDizPorLinhaSemGravar() throws Exception {
        Ctx c = setup();
        String bad = "requests==2.32.3\nnumpy\npandas>=2\n-r outro.txt\ngit+https://example.com/x.git\nflask==3.0.3 --hash=sha256:abc\ncolorama==0.4.6 ; sys_platform == 'win32'\n";
        BrowserClient.Response a = c.admin().post(c.path() + "/analysis", "{\"text\":" + text(bad) + "}");
        assertThat(a.status()).as(a.body()).isEqualTo(200);
        JsonNode an = JSON.readTree(a.body());
        assertThat(an.get("resolved").asBoolean()).isFalse();
        assertThat(an.get("dependencies").toString()).isEqualTo("[\"requests==2.32.3\"]");
        Map<Integer, String> kinds = new java.util.TreeMap<>();
        for (JsonNode l : an.get("lines")) {
            kinds.put(l.get("number").asInt(), l.get("kind").asString() + (l.get("problem").asBoolean() ? "!" : ""));
        }
        assertThat(kinds.toString()).isEqualTo("{1=EXACT, 2=NO_VERSION!, 3=RANGE!, 4=INCLUDE!, 5=REMOTE!, 6=HASH!, 7=MARKER!}");
        // a analise nao grava nada
        assertThat(value("SELECT count(*) FROM automation_runtime_requirements WHERE automation_id = '" + c.automation() + "'")).isEqualTo("0");
        // declarar o mesmo texto: recusado inteiro, com o motivo; nenhuma versao e inventada, nada parcial e gravado
        for (String source : new String[] {"TEXT", "FILE"}) {
            BrowserClient.Response r = put(c.admin(), c.path(), "{\"source\":\"" + source + "\",\"text\":" + text(bad) + "}");
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.body()).isEqualTo("{\"error\":\"conflict\",\"reason\":\"runtime_requirements_unresolved\"}");
        }
        // a mesma biblioteca com duas versoes no texto: conflito, tambem recusa
        assertThat(put(c.admin(), c.path(), "{\"source\":\"TEXT\",\"text\":" + text("requests==2.32.3\nrequests==2.31.0\n") + "}").status()).isEqualTo(409);
        assertThat(value("SELECT count(*) FROM automation_runtime_requirements WHERE automation_id = '" + c.automation() + "'")).isEqualTo("0");
        assertThat(value("SELECT count(*) FROM automation_admin_events WHERE automation_id = '" + c.automation() + "' AND act = 10")).isEqualTo("0");
        // pedidos malformados
        String[] invalid = {"{}", "{\"source\":\"PIP\"}", "{\"source\":\"TEXT\"}", "{\"source\":\"PACKAGE\",\"text\":\"requests==2.32.3\"}",
                "{\"source\":\"NONE_CONFIRMED\",\"text\":\"\"}", "{\"source\":\"TEXT\",\"text\":" + text("x".repeat(262145)) + "}"};
        for (String body : invalid) {
            assertThat(put(c.admin(), c.path(), body).status()).as(body.length() > 80 ? "texto grande demais" : body).isEqualTo(400);
        }
        assertThat(c.admin().post(c.path() + "/analysis", "{}").status()).isEqualTo(400);
        // texto vazio (so comentarios) com TEXT e uma declaracao valida: nada acrescentado
        assertThat(put(c.admin(), c.path(), "{\"source\":\"TEXT\",\"text\":" + text("# nada\n") + "}").status()).isEqualTo(201);
    }

    @Test
    void revisaoVistaPermissoesIsolamentoEGuardasDoBanco() throws Exception {
        Ctx c = setup();
        assertThat(put(c.admin(), c.path(), "{\"source\":\"TEXT\",\"text\":" + text("requests==2.32.3\n") + "}").status()).isEqualTo(201);
        // quem viu "nenhuma" ou uma revisao antiga nao sobrescreve a declaracao de outra pessoa
        for (String stale : new String[] {"{\"source\":\"PACKAGE\"}", "{\"source\":\"PACKAGE\",\"expectedRevision\":7}"}) {
            BrowserClient.Response r = put(c.admin(), c.path(), stale);
            assertThat(r.status()).isEqualTo(409);
            assertThat(r.body()).isEqualTo("{\"error\":\"conflict\",\"reason\":\"runtime_requirements_changed\"}");
        }
        assertThat(value("SELECT count(*) FROM automation_runtime_requirements WHERE automation_id = '" + c.automation() + "'")).isEqualTo("1");

        // OPERATOR e VIEWER leem (automation.read) e nao declaram nem analisam (automation.manage)
        for (String role : new String[] {"OPERATOR", "VIEWER"}) {
            TestUser u = internalUser(c.org(), true, 2);
            grantWorkspaceRole(c.org(), u, UUID.fromString(c.workspace()), role);
            BrowserClient b = browser();
            firstLogin(b, c.org().id(), u);
            assertThat(b.get(c.path()).status()).as(role).isEqualTo(200);
            assertThat(put(b, c.path(), "{\"source\":\"PACKAGE\",\"expectedRevision\":1}").status()).as(role).isEqualTo(404);
            assertThat(b.post(c.path() + "/analysis", "{\"text\":\"requests==2.32.3\"}").status()).as(role).isEqualTo(404);
        }
        // WORKSPACE_ADMIN de OUTRO ambiente, outra organizacao e sem sessao: nada
        TestUser other = internalUser(c.org(), true, 2);
        grantWorkspaceRole(c.org(), other, newWorkspace(c.org(), newClient(c.org())), "WORKSPACE_ADMIN");
        BrowserClient ob = browser();
        firstLogin(ob, c.org().id(), other);
        assertThat(ob.get(c.path()).status()).isEqualTo(404);
        assertThat(put(ob, c.path(), "{\"source\":\"PACKAGE\",\"expectedRevision\":1}").status()).isEqualTo(404);
        Ctx foreign = setup();
        assertThat(foreign.admin().get(foreign.o() + "/automations/" + c.automation() + "/runtime-requirements").status()).isEqualTo(404);
        assertThat(browser().get(c.path()).status()).isEqualTo(401);
        assertThat(c.admin().get(c.o() + "/automations/nao-e-uuid/runtime-requirements").status()).isEqualTo(400);

        // o banco: revisao fora de ordem, formato invalido, autoria sem permissao, alteracao e exclusao
        String viewerMembership = value("SELECT m.membership_id FROM organization_memberships m JOIN workspace_role_assignments w ON w.membership_id = m.membership_id "
                + "JOIN roles r ON r.role_id = w.role_id WHERE w.workspace_id = '" + c.workspace() + "' AND r.code = 'VIEWER' LIMIT 1");
        String insert = "INSERT INTO automation_runtime_requirements (automation_id, revision_number, organization_id, workspace_id, source, dependencies, content_sha256, "
                + "declared_by_membership_id) VALUES ('" + c.automation() + "', %d, '" + c.org().id() + "', '" + c.workspace() + "', %d, '%s'::jsonb, '" + "a".repeat(64)
                + "', '%s')";
        String admin = c.org().adminMembership().toString();
        assertThat(asPlatform(c.org().id(), insert.formatted(5, 3, "[\"idna==3.7\"]", admin))).as("revisao fora de ordem").isEqualTo("23514");
        assertThat(asPlatform(c.org().id(), insert.formatted(2, 3, "[\"idna>=3\"]", admin))).as("faixa").isEqualTo("23514");
        assertThat(asPlatform(c.org().id(), insert.formatted(2, 3, "[\"https://example.com/x.whl\"]", admin))).as("endereco").isEqualTo("23514");
        assertThat(asPlatform(c.org().id(), insert.formatted(2, 1, "[\"idna==3.7\"]", admin))).as("so as do pacote nao acrescenta nada").isEqualTo("23514");
        assertThat(asPlatform(c.org().id(), insert.formatted(2, 3, "[\"idna==3.7\"]", viewerMembership))).as("sem automation.manage").isEqualTo("42501");
        assertThat(asPlatform(c.org().id(), "UPDATE automation_runtime_requirements SET dependencies = '[]'::jsonb WHERE automation_id = '" + c.automation() + "'"))
                .isEqualTo("42501");
        assertThat(asPlatform(c.org().id(), "DELETE FROM automation_runtime_requirements WHERE automation_id = '" + c.automation() + "'")).isEqualTo("42501");
        assertThat(asPlatform(c.org().id(), insert.formatted(2, 3, "[\"idna==3.7\"]", admin))).as("a seguinte, por quem pode").isEqualTo("OK");
        // a maquina (nekkora_ingestion) ainda nao le nem grava esta tabela
        try (Connection con = DB.connectAs("nekkora_ingestion"); Statement st = con.createStatement()) {
            st.execute("SELECT set_config('nekkora.organization_id', '" + c.org().id() + "', false)");
            st.executeQuery("SELECT count(*) FROM automation_runtime_requirements");
            throw new AssertionError("a ingestao nao deveria ler");
        } catch (SQLException e) {
            assertThat(e.getSQLState()).isEqualTo("42501");
        }
    }
}
