package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

/**
 * PLATFORM-AGENT-V2-003, T12 (033): no maximo UMA autorizacao local VIGENTE por escopo. Escopo = organizacao + autorizacao
 * do Worker no workspace + versao (pacote) ou automacao (monitoramento). Pela API real (nekkora_platform), com a massa
 * DECLARADA como superusuario (organizacao, workspace, automacoes, versoes publicadas, Workers): clique repetido, resposta
 * perdida, duas abas, duas pessoas, chamada direta ao banco, revogacao, vencimento (com a fronteira), escopos legitimos
 * distintos, isolamento entre organizacoes, auditoria e historico preservados.
 */
class AutorizacaoVigenteUnicaIT extends PlatformTestBase {

    static final String FINGERPRINT = "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d";
    static final String MOTIVO = "\"reason\":\"local_run_authorization_exists\"";

    private record Setup(Org org, BrowserClient admin, UUID workspace, UUID automation, UUID version, UUID worker) {
        String base() {
            return "/api/v1/o/" + org.id() + "/workspaces/" + workspace + "/local-run-authorizations";
        }
    }

    // =========================================================================== massa declarada

    private Setup setup() throws Exception {
        Org o = newOrganization();
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES ('" + o.id() + "', " + code + ", 1, now(), 2, NULL, '" + o.adminId() + "')");
        }
        UUID ws = newWorkspace(o, newClient(o));
        UUID automation = automation(o, ws, "Biografias");
        UUID version = publishedVersion(o, ws, automation);
        UUID worker = worker(o, ws, "Estacao fiscal");
        BrowserClient b = browser();
        firstLogin(b, o.id(), new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
        stepUp(b, o, o.adminId());
        return new Setup(o, b, ws, automation, version, worker);
    }

    private static UUID automation(Org o, UUID ws, String name) throws Exception {
        return UUID.fromString(value("INSERT INTO automations (organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('" + o.id() + "', '"
                + ws + "', '" + name + "', 1, '" + o.adminMembership() + "') RETURNING automation_id"));
    }

    /** Versao publicada com impressao declarada; o numero e o proximo da automacao (005). */
    private static UUID publishedVersion(Org o, UUID ws, UUID automation) throws Exception {
        UUID version = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                        + "VALUES ('" + version + "', '" + o.id() + "', '" + ws + "', '" + automation + "', 1, jsonb_build_object('contract_version',1,'artifact',"
                        + "jsonb_build_object('reference','biografias-python','sha256','" + "ab".repeat(32) + "'),'agent_package',jsonb_build_object('fingerprint_rule',"
                        + "'nekkora-agent/package-fingerprint/1','fingerprint','" + FINGERPRINT + "'),'runtime','python3.12','entrypoint','main.py',"
                        + "'arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + o.adminMembership() + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + o.adminMembership()
                        + "' WHERE automation_version_id = '" + version + "'");
        return version;
    }

    /** Worker ativo e autorizado no workspace (a autorizacao do Worker no workspace e parte do escopo). */
    private static UUID worker(Org o, UUID ws, String name) throws Exception {
        UUID worker = UUID.fromString(value("INSERT INTO workers (organization_id, name, status) VALUES ('" + o.id() + "', '" + name + "', 1) RETURNING worker_id"));
        DB.asSuperuser("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('"
                + o.id() + "', '" + worker + "', '" + ws + "', 1, '" + o.adminMembership() + "')");
        return worker;
    }

    private void stepUp(BrowserClient b, Org o, UUID user) {
        assertThat(b.post("/api/v1/o/" + o.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(user) + "\"}").status()).isEqualTo(204);
    }

    /** Segunda pessoa: OPERATOR no workspace (automation.execute), com sessao propria e step-up. */
    private BrowserClient operator(Setup s) throws Exception {
        TestUser u = internalUser(s.org(), true, 2);
        AccessFixtures.assignWorkspaceRole(s.org().id(), AccessFixtures.membershipOf(s.org().id(), u.id()), s.workspace(), "OPERATOR", s.org().adminMembership());
        BrowserClient b = browser();
        firstLogin(b, s.org().id(), u);
        stepUp(b, s.org(), u.id());
        return b;
    }

    private static String pkg(UUID worker, UUID version, String until) {
        return "{\"workerId\":\"" + worker + "\",\"automationVersionId\":\"" + version + "\",\"validUntil\":\"" + until + "\"}";
    }

    private static String monitor(UUID worker, UUID automation, String until) {
        return "{\"workerId\":\"" + worker + "\",\"automationId\":\"" + automation + "\",\"validUntil\":\"" + until + "\",\"sourceKind\":\"EXTERNAL_MONITORED\"}";
    }

    private static String days(int n) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(n).withNano(0).toString();
    }

    private static long vigentes(Setup s) throws Exception {
        return count("SELECT count(*) FROM local_run_authorizations WHERE organization_id = '" + s.org().id() + "' AND revoked_at IS NULL AND valid_until > clock_timestamp()");
    }

    private static long concedidas(Setup s) throws Exception {
        return count("SELECT count(*) FROM agent_admin_events WHERE organization_id = '" + s.org().id() + "' AND act = 10");
    }

    // =========================================================================== repeticao

    /** Clique repetido e resposta perdida (o mesmo pedido de novo): 409 com o motivo; nada gravado, estendido nem revogado. */
    @Test
    void repeticaoDoMesmoEscopoRecusadaSemGravarNemMudarAVigente() throws Exception {
        Setup s = setup();
        BrowserClient.Response first = s.admin().post(s.base(), pkg(s.worker(), s.version(), days(30)));
        assertThat(first.status()).as(first.body()).isEqualTo(201);
        String antes = value("SELECT authorization_id || '|' || granted_at || '|' || valid_until || '|' || coalesce(revoked_at::text, '-') FROM local_run_authorizations "
                + "WHERE authorization_id = '" + first.field("authorizationId") + "'");

        // o mesmo pedido (como depois de perder a resposta) e outro com validade MAIOR: nenhum vira nova autorizacao nem extensao
        for (String body : List.of(pkg(s.worker(), s.version(), days(30)), pkg(s.worker(), s.version(), days(300)))) {
            BrowserClient.Response again = s.admin().post(s.base(), body);
            assertThat(again.status()).as(again.body()).isEqualTo(409);
            assertThat(again.body()).contains("\"error\":\"conflict\"").contains(MOTIVO);
        }
        assertThat(vigentes(s)).isEqualTo(1);
        assertThat(concedidas(s)).as("auditoria: so a concessao real").isEqualTo(1);
        assertThat(value("SELECT authorization_id || '|' || granted_at || '|' || valid_until || '|' || coalesce(revoked_at::text, '-') FROM local_run_authorizations "
                + "WHERE authorization_id = '" + first.field("authorizationId") + "'")).as("a vigente nao mudou").isEqualTo(antes);
        // a lista mostra a vigente (a Web rele depois do 409)
        assertThat(s.admin().get(s.base()).body()).contains(first.field("authorizationId"));

        // monitoramento: a mesma regra pela automacao
        BrowserClient.Response m = s.admin().post(s.base(), monitor(s.worker(), s.automation(), days(10)));
        assertThat(m.status()).as("monitoramento e outro escopo que o pacote").isEqualTo(201);
        BrowserClient.Response m2 = s.admin().post(s.base(), monitor(s.worker(), s.automation(), days(10)));
        assertThat(m2.status()).isEqualTo(409);
        assertThat(m2.body()).contains(MOTIVO);
        assertThat(vigentes(s)).isEqualTo(2);
        assertThat(concedidas(s)).isEqualTo(2);
    }

    // =========================================================================== concorrencia

    /** Duas abas da mesma pessoa e duas pessoas ao mesmo tempo, no mesmo escopo: uma gravacao, uma recusa, sempre. */
    @Test
    void duasAbasEDuasPessoasAoMesmoTempoGravamUmaSo() throws Exception {
        Setup s = setup();
        BrowserClient tab2 = browser();
        assertThat(fullLogin(tab2, s.org().id(), new TestUser(s.org().adminId(), s.org().adminEmail(), PASSWORD.toCharArray())).status()).isEqualTo(200);
        stepUp(tab2, s.org(), s.org().adminId());
        BrowserClient other = operator(s);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // rodada 1: duas abas (pacote); rodada 2: duas pessoas (monitoramento); rodadas 3-6: mais pacotes em versoes novas
            List<List<BrowserClient>> pares = List.of(List.of(s.admin(), tab2), List.of(s.admin(), other));
            for (int round = 0; round < 6; round++) {
                List<BrowserClient> par = pares.get(round % 2);
                String body;
                if (round == 1) {
                    body = monitor(s.worker(), s.automation(), days(5));
                } else {
                    UUID automation = round == 0 ? s.automation() : automation(s.org(), s.workspace(), "Concorrencia " + round);
                    UUID version = round == 0 ? s.version() : publishedVersion(s.org(), s.workspace(), automation);
                    body = pkg(s.worker(), version, days(5));
                }
                CountDownLatch start = new CountDownLatch(1);
                List<Future<BrowserClient.Response>> rs = new ArrayList<>();
                for (BrowserClient b : par) {
                    rs.add(pool.submit(() -> {
                        start.await();
                        return b.post(s.base(), body);
                    }));
                }
                start.countDown();
                List<Integer> status = new ArrayList<>();
                for (Future<BrowserClient.Response> f : rs) {
                    BrowserClient.Response r = f.get();
                    status.add(r.status());
                    if (r.status() == 409) {
                        assertThat(r.body()).contains(MOTIVO);
                    }
                }
                assertThat(status).as("rodada " + round).containsExactlyInAnyOrder(201, 409);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(vigentes(s)).isEqualTo(6);
        assertThat(concedidas(s)).isEqualTo(6);
    }

    /** Chamada direta ao banco com o papel REAL da Platform: o banco recusa (23505) mesmo sem passar pela API. */
    @Test
    void insercaoDiretaNoBancoTambemRecusada() throws Exception {
        Setup s = setup();
        assertThat(s.admin().post(s.base(), pkg(s.worker(), s.version(), days(30))).status()).isEqualTo(201);
        String assignment = value("SELECT assignment_id FROM worker_workspace_assignments WHERE worker_id = '" + s.worker() + "' AND status = 1");
        String sql = "INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id, "
                + "automation_version_id, package_fingerprint, package_fingerprint_rule, source_kind, granted_by_membership_id, valid_until) VALUES ('" + s.org().id()
                + "', '" + s.workspace() + "', '" + s.worker() + "', '" + assignment + "', '" + s.automation() + "', '" + s.version() + "', '" + FINGERPRINT
                + "', 'nekkora-agent/package-fingerprint/1', 1, '" + s.org().adminMembership() + "', now() + interval '1 day')";
        assertThat(asPlatform(s.org().id(), sql)).isEqualTo("23505");
        assertThat(vigentes(s)).isEqualTo(1);
    }

    // =========================================================================== depois da revogacao e do vencimento

    @Test
    void novaAutorizacaoDepoisDaRevogacaoComHistorico() throws Exception {
        Setup s = setup();
        BrowserClient.Response a = s.admin().post(s.base(), pkg(s.worker(), s.version(), days(30)));
        assertThat(a.status()).isEqualTo(201);
        assertThat(s.admin().post(s.base() + "/" + a.field("authorizationId") + "/revoke", null).status()).isEqualTo(204);
        BrowserClient.Response b = s.admin().post(s.base(), pkg(s.worker(), s.version(), days(30)));
        assertThat(b.status()).as(b.body()).isEqualTo(201);
        assertThat(b.field("authorizationId")).isNotEqualTo(a.field("authorizationId"));
        // historico: as duas continuam, a primeira revogada
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE organization_id = '" + s.org().id() + "'")).isEqualTo(2);
        assertThat(value("SELECT revoked_at IS NOT NULL FROM local_run_authorizations WHERE authorization_id = '" + a.field("authorizationId") + "'")).isEqualTo("t");
        assertThat(s.admin().get(s.base()).body()).contains(a.field("authorizationId")).contains(b.field("authorizationId"));
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE organization_id = '" + s.org().id() + "' AND act IN (10, 11)")).isEqualTo(3);
    }

    /**
     * Vencimento pelo horario do SERVIDOR: imediatamente antes do fim da validade, recusada; depois, aceita. A anterior
     * continua no historico, sem revogacao.
     */
    @Test
    void novaAutorizacaoSoDepoisDoVencimentoNoHorarioDoServidor() throws Exception {
        Setup s = setup();
        String until = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(4).toString();
        BrowserClient.Response a = s.admin().post(s.base(), monitor(s.worker(), s.automation(), until));
        assertThat(a.status()).as(a.body()).isEqualTo(201);
        // antes do vencimento (a vigente ainda vale pelo relogio do banco): recusada
        assertThat(value("SELECT valid_until > clock_timestamp() FROM local_run_authorizations WHERE authorization_id = '" + a.field("authorizationId") + "'"))
                .isEqualTo("t");
        assertThat(s.admin().post(s.base(), monitor(s.worker(), s.automation(), days(3))).status()).isEqualTo(409);
        // espera o vencimento pelo relogio do banco (nunca pelo do teste)
        for (int i = 0; i < 100 && "f".equals(value("SELECT valid_until <= clock_timestamp() FROM local_run_authorizations WHERE authorization_id = '"
                + a.field("authorizationId") + "'")); i++) {
            Thread.sleep(100);
        }
        BrowserClient.Response b = s.admin().post(s.base(), monitor(s.worker(), s.automation(), days(3)));
        assertThat(b.status()).as(b.body()).isEqualTo(201);
        assertThat(value("SELECT revoked_at IS NULL FROM local_run_authorizations WHERE authorization_id = '" + a.field("authorizationId") + "'"))
                .as("a vencida fica no historico, sem revogacao").isEqualTo("t");
        assertThat(vigentes(s)).isEqualTo(1);
    }

    // =========================================================================== escopos legitimos e isolamento

    /** Outro Worker, outra versao, outra automacao e outro workspace: autorizacoes distintas continuam aceitas. */
    @Test
    void escoposDistintosContinuamAutorizaveis() throws Exception {
        Setup s = setup();
        assertThat(s.admin().post(s.base(), pkg(s.worker(), s.version(), days(30))).status()).isEqualTo(201);
        // outro Worker, mesma versao
        UUID w2 = worker(s.org(), s.workspace(), "Estacao 2");
        assertThat(s.admin().post(s.base(), pkg(w2, s.version(), days(30))).status()).as("outro Worker").isEqualTo(201);
        // outra versao publicada da MESMA automacao, mesmo Worker
        UUID v2 = publishedVersion(s.org(), s.workspace(), s.automation());
        assertThat(s.admin().post(s.base(), pkg(s.worker(), v2, days(30))).status()).as("outra versao").isEqualTo(201);
        // monitoramento da mesma automacao (outra origem) e de outra automacao
        assertThat(s.admin().post(s.base(), monitor(s.worker(), s.automation(), days(30))).status()).as("monitoramento").isEqualTo(201);
        UUID a2 = automation(s.org(), s.workspace(), "Outra automacao");
        assertThat(s.admin().post(s.base(), monitor(s.worker(), a2, days(30))).status()).as("outra automacao").isEqualTo(201);
        // outro workspace da mesma organizacao, com o mesmo Worker autorizado nele
        UUID ws2 = newWorkspace(s.org(), newClient(s.org()));
        UUID a3 = automation(s.org(), ws2, "No outro workspace");
        DB.asSuperuser("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('"
                + s.org().id() + "', '" + s.worker() + "', '" + ws2 + "', 1, '" + s.org().adminMembership() + "')");
        assertThat(s.admin().post("/api/v1/o/" + s.org().id() + "/workspaces/" + ws2 + "/local-run-authorizations", monitor(s.worker(), a3, days(30))).status())
                .as("outro workspace").isEqualTo(201);
        assertThat(vigentes(s)).isEqualTo(6);
    }

    /**
     * Isolamento: outra organizacao com a sua vigente nao bloqueia nem enxerga esta; o 409 desta nao revela nada da outra.
     * Worker que perde e recebe de novo a autorizacao no workspace e outro escopo (a anterior deixa de chegar ao Agent).
     */
    @Test
    void isolamentoEntreOrganizacoesENovaAutorizacaoDoWorkerNoWorkspace() throws Exception {
        Setup a = setup();
        Setup b = setup();
        assertThat(a.admin().post(a.base(), pkg(a.worker(), a.version(), days(30))).status()).isEqualTo(201);
        assertThat(b.admin().post(b.base(), pkg(b.worker(), b.version(), days(30))).status()).isEqualTo(201);
        BrowserClient.Response dup = b.admin().post(b.base(), pkg(b.worker(), b.version(), days(30)));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body()).doesNotContain(a.org().id().toString()).doesNotContain(a.worker().toString());
        assertThat(b.admin().get(b.base()).body()).doesNotContain(a.org().id().toString());
        // a organizacao B nao autoriza no workspace de A
        assertThat(b.admin().post("/api/v1/o/" + b.org().id() + "/workspaces/" + a.workspace() + "/local-run-authorizations",
                pkg(a.worker(), a.version(), days(30))).status()).isIn(403, 404);

        // Worker sai do workspace e volta: nova autorizacao do Worker no workspace = escopo novo
        DB.asSuperuser("UPDATE worker_workspace_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '" + a.org().adminMembership()
                + "' WHERE worker_id = '" + a.worker() + "' AND status = 1");
        DB.asSuperuser("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('"
                + a.org().id() + "', '" + a.worker() + "', '" + a.workspace() + "', 1, '" + a.org().adminMembership() + "')");
        assertThat(a.admin().post(a.base(), pkg(a.worker(), a.version(), days(30))).status()).isEqualTo(201);
    }

    // =========================================================================== apoio

    /** Executa como o papel REAL da Platform, no contexto da organizacao; devolve "OK" ou o SQLSTATE da recusa. */
    private static String asPlatform(UUID org, String sql) {
        try (Connection c = DB.connectAs("nekkora_platform")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
                st.execute(sql);
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

    private static String value(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long count(String sql) throws Exception {
        return Long.parseLong(value(sql));
    }
}
