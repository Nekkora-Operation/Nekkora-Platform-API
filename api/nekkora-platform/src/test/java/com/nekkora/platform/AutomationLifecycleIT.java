package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.artifact.PackageInspectorTest;

/**
 * PLATFORM-AUTOMATION-LIFECYCLE-001 (L1, L2, L3, L5): workspace, automacao, versao DRAFT, pacote real do Studio recebido e
 * conferido pela API, publicacao com step-up, autorizacao de execucao local da versao publicada; e as recusas (permissao,
 * sessao, step-up, organizacao, workspace, estado, integridade, concorrencia, repeticao, falha no meio, identidade forjada e
 * pacote malicioso), cada uma sem objeto parcial nem autoridade a mais.
 */
class AutomationLifecycleIT extends PlatformTestBase {

    static final Path STORE;
    static final String REAL_FP = "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d";

    static {
        try {
            STORE = Files.createTempDirectory("nekkora-artefatos-it");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void artifacts(DynamicPropertyRegistry r) {
        r.add("nekkora.artefatos.dir", STORE::toString);
    }

    record Ctx(Org org, BrowserClient admin, String o) {
    }

    record Draft(String workspace, String automation, String version, String sha, String fingerprint) {
    }

    private Ctx login() {
        Org org = newOrganization();
        BrowserClient b = browser();
        firstLogin(b, org.id(), new TestUser(org.adminId(), org.adminEmail(), PASSWORD.toCharArray()));
        return new Ctx(org, b, "/api/v1/o/" + org.id());
    }

    private void stepUp(BrowserClient b, UUID org, UUID user) {
        assertThat(b.post("/api/v1/o/" + org + "/auth/mfa/step-up", "{\"code\":\"" + code(user) + "\"}").status()).isEqualTo(204);
    }

    private BrowserClient.Response putZip(BrowserClient b, String path, byte[] zip) {
        return b.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).PUT(HttpRequest.BodyPublishers.ofByteArray(zip))
                .header("Content-Type", "application/zip"));
    }

    private Draft draftWithArtifact(Ctx c) throws Exception {
        BrowserClient.Response ws = c.admin().post(c.o() + "/workspaces", "{\"name\":\"WS ciclo de vida\"}");
        assertThat(ws.status()).as(ws.body()).isEqualTo(201);
        String w = ws.field("workspaceId");
        BrowserClient.Response au = c.admin().post(c.o() + "/workspaces/" + w + "/automations", "{\"name\":\"Biografias\"}");
        assertThat(au.status()).as(au.body()).isEqualTo(201);
        String a = au.field("automationId");
        BrowserClient.Response v = c.admin().post(c.o() + "/automations/" + a + "/versions", "{\"runtime\":\"python3.12\",\"arguments\":[]}");
        assertThat(v.status()).as(v.body()).isEqualTo(201);
        assertThat(v.field("status")).isEqualTo("DRAFT");
        String id = v.field("automationVersionId");
        BrowserClient.Response up = putZip(c.admin(), c.o() + "/automation-versions/" + id + "/artifact", PackageInspectorTest.realZip());
        assertThat(up.status()).as(up.body()).isEqualTo(200);
        assertThat(up.body()).contains("\"fingerprint\":\"" + REAL_FP + "\"").contains("\"provenance\":\"DECLARED\"");
        String sha = value("SELECT sha256 FROM automation_artifacts WHERE automation_version_id = '" + id + "'");
        return new Draft(w, a, id, sha, REAL_FP);
    }

    private static String publishBody(String sha, String fp) {
        return "{\"expectedArtifactSha256\":\"" + sha + "\",\"expectedPackageFingerprint\":\"" + fp + "\"}";
    }

    // =========================================================================== caminho feliz

    @Test
    void ciclo_completo_publica_versao_genuina_e_autoriza_sem_executar() throws Exception {
        Ctx c = login();
        Draft d = draftWithArtifact(c);
        String base = c.o() + "/automation-versions/" + d.version();
        // a versao mostra as duas identidades separadas e a referencia interna (nunca caminho)
        BrowserClient.Response got = c.admin().get(base);
        assertThat(got.status()).isEqualTo(200);
        assertThat(got.body()).contains("\"artifactReference\":\"nekkora-artifact:").contains("\"sha256\":\"" + d.sha() + "\"")
                .contains("\"fingerprintRule\":\"nekkora-agent/package-fingerprint/1\"");
        assertThat(d.sha()).isNotEqualTo(d.fingerprint());
        // DRAFT nao recebe autorizacao de execucao local
        UUID worker = workerInWorkspace(c.org(), UUID.fromString(d.workspace()));
        String auth = "{\"workerId\":\"" + worker + "\",\"automationVersionId\":\"" + d.version() + "\",\"validUntil\":\"" + future(10) + "\"}";
        // publicar exige step-up recente NESTA sessao (ainda nao feito): 403 e nada muda
        assertThat(c.admin().post(base + "/publish", publishBody(d.sha(), d.fingerprint())).status()).isEqualTo(403);
        assertThat(value("SELECT status FROM automation_versions WHERE automation_version_id = '" + d.version() + "'")).isEqualTo("1");
        stepUp(c.admin(), c.org().id(), c.org().adminId());
        assertThat(c.admin().post(c.o() + "/workspaces/" + d.workspace() + "/local-run-authorizations", auth).status()).isEqualTo(409);
        // expectativa diferente do conteudo (a pessoa conferiu outra coisa): nada publicado
        assertThat(c.admin().post(base + "/publish", publishBody("0".repeat(64), d.fingerprint())).status()).isEqualTo(409);
        BrowserClient.Response pub = c.admin().post(base + "/publish", publishBody(d.sha(), d.fingerprint()));
        assertThat(pub.status()).as(pub.body()).isEqualTo(200);
        assertThat(pub.field("status")).isEqualTo("PUBLISHED");
        assertThat(pub.field("publishedByMembershipId")).isEqualTo(c.org().adminMembership().toString());
        // repeticao depois de resposta perdida: mesma versao, nada muda; outra expectativa: 409
        assertThat(c.admin().post(base + "/publish", publishBody(d.sha(), d.fingerprint())).status()).isEqualTo(200);
        assertThat(c.admin().post(base + "/publish", publishBody(d.sha(), "1".repeat(64))).status()).isEqualTo(409);
        // publicada e imutavel: nem artefato novo nem alteracao
        assertThat(putZip(c.admin(), base + "/artifact", PackageInspectorTest.realZip()).status()).isEqualTo(409);
        assertThat(c.admin().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + base))
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"runtime\":\"python3.13\"}")).header("Content-Type", "application/json")).status())
                .isEqualTo(409);
        // publicar nao executa nem autoriza nada
        assertThat(count("SELECT count(*) FROM executions WHERE organization_id = '" + c.org().id() + "'")).isZero();
        assertThat(count("SELECT count(*) FROM agent_runs WHERE organization_id = '" + c.org().id() + "'")).isZero();
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE organization_id = '" + c.org().id() + "'")).isZero();
        // autorizacao de execucao local: a impressao vem da versao publicada (agent_package), nao do sha256 dos bytes
        BrowserClient.Response a = c.admin().post(c.o() + "/workspaces/" + d.workspace() + "/local-run-authorizations", auth);
        assertThat(a.status()).as(a.body()).isEqualTo(201);
        assertThat(a.field("packageFingerprint")).isEqualTo(REAL_FP);
        // validade fora de (agora, agora + 366 dias] e automacao suspensa: recusas tratadas (antes, 500 pelo gatilho)
        assertThat(c.admin().post(c.o() + "/workspaces/" + d.workspace() + "/local-run-authorizations", "{\"workerId\":\"" + worker + "\",\"automationVersionId\":\"" + d.version() + "\",\"validUntil\":\"" + future(400) + "\"}").status())
                .isEqualTo(400);
        DB.asSuperuser("UPDATE automations SET status = 2 WHERE automation_id = '" + d.automation() + "'");
        assertThat(c.admin().post(c.o() + "/workspaces/" + d.workspace() + "/local-run-authorizations", auth).status()).isEqualTo(409);
        DB.asSuperuser("UPDATE automations SET status = 1 WHERE automation_id = '" + d.automation() + "'");
        // recuperacao autorizada dos bytes preservados
        BrowserClient.Response content = c.admin().get(base + "/artifact/content");
        assertThat(content.status()).isEqualTo(200);
        assertThat(content.headers().firstValue("X-Artifact-Sha256")).contains(d.sha());
        // trilha: um evento por ato, com a pessoa
        assertThat(value("SELECT string_agg(act::text, ',' ORDER BY created_at) FROM automation_admin_events WHERE organization_id = '" + c.org().id() + "'"))
                .isEqualTo("1,2,3,5,6");
        assertThat(count("SELECT count(*) FROM automation_admin_events WHERE organization_id = '" + c.org().id() + "' AND actor_membership_id <> '"
                + c.org().adminMembership() + "'")).isZero();
    }

    @Test
    void numeracao_concorrente_e_publicacao_concorrente_sem_duplicar() throws Exception {
        Ctx c = login();
        Draft d = draftWithArtifact(c);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Callable<Integer>> creates = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                creates.add(() -> {
                    go.await();
                    return c.admin().post(c.o() + "/automations/" + d.automation() + "/versions", "{}").status();
                });
            }
            List<Future<Integer>> f = new ArrayList<>();
            for (Callable<Integer> x : creates) {
                f.add(pool.submit(x));
            }
            go.countDown();
            for (Future<Integer> x : f) {
                assertThat(x.get()).isEqualTo(201);
            }
            assertThat(value("SELECT string_agg(version_number::text, ',' ORDER BY version_number) FROM automation_versions WHERE automation_id = '"
                    + d.automation() + "'")).isEqualTo("1,2,3,4,5");
            stepUp(c.admin(), c.org().id(), c.org().adminId());
            CountDownLatch go2 = new CountDownLatch(1);
            List<Future<Integer>> p = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                p.add(pool.submit(() -> {
                    go2.await();
                    return c.admin().post(c.o() + "/automation-versions/" + d.version() + "/publish", publishBody(d.sha(), d.fingerprint())).status();
                }));
            }
            go2.countDown();
            for (Future<Integer> x : p) {
                assertThat(x.get()).isEqualTo(200); // a primeira publica; as demais veem a mesma publicacao
            }
            assertThat(count("SELECT count(*) FROM automation_admin_events WHERE act = 6 AND automation_version_id = '" + d.version() + "'")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // =========================================================================== autoridade e sessao

    @Test
    void sem_permissao_outro_workspace_outra_organizacao_sessao_e_conta_suspensa() throws Exception {
        Ctx c = login();
        Draft d = draftWithArtifact(c);
        String base = c.o() + "/automation-versions/" + d.version();
        // VIEWER no workspace: le, mas nao gerencia nem publica (404 como a leitura sem acesso, sem revelar existencia)
        TestUser viewer = internalUser(c.org(), true, 2);
        grantWorkspaceRole(c.org(), viewer, UUID.fromString(d.workspace()), "VIEWER");
        BrowserClient v = browser();
        firstLogin(v, c.org().id(), viewer);
        assertThat(v.get(base).status()).isEqualTo(200);
        assertThat(v.post(c.o() + "/workspaces/" + d.workspace() + "/automations", "{\"name\":\"x\"}").status()).isEqualTo(404);
        assertThat(putZip(v, base + "/artifact", PackageInspectorTest.realZip()).status()).isEqualTo(404);
        stepUp(v, c.org().id(), viewer.id());
        assertThat(v.post(base + "/publish", publishBody(d.sha(), d.fingerprint())).status()).isEqualTo(404);
        assertThat(v.post(c.o() + "/workspaces", "{\"name\":\"y\"}").status()).isEqualTo(403); // workspace.lifecycle e da organizacao
        // WORKSPACE_ADMIN de OUTRO workspace: nada neste
        TestUser other = internalUser(c.org(), true, 2);
        UUID ws2 = newWorkspace(c.org(), newClient(c.org()));
        grantWorkspaceRole(c.org(), other, ws2, "WORKSPACE_ADMIN");
        BrowserClient o2 = browser();
        firstLogin(o2, c.org().id(), other);
        stepUp(o2, c.org().id(), other.id());
        assertThat(o2.post(base + "/publish", publishBody(d.sha(), d.fingerprint())).status()).isEqualTo(404);
        // outra organizacao: a versao nao existe para ela
        Ctx f = login();
        assertThat(f.admin().get(f.o() + "/automation-versions/" + d.version()).status()).isEqualTo(404);
        stepUp(f.admin(), f.org().id(), f.org().adminId());
        assertThat(f.admin().post(f.o() + "/automation-versions/" + d.version() + "/publish", publishBody(d.sha(), d.fingerprint())).status()).isEqualTo(404);
        // sessao encerrada e conta suspensa: 401
        DB.asSuperuser("UPDATE user_sessions SET revoked_at = clock_timestamp() WHERE user_id = '" + viewer.id() + "' AND revoked_at IS NULL");
        assertThat(v.get(base).status()).isEqualTo(401);
        DB.asSuperuser("UPDATE organization_memberships SET status = 3 WHERE user_id = '" + other.id() + "'");
        assertThat(o2.post(c.o() + "/automations/" + d.automation() + "/versions", "{}").status()).isIn(401, 403, 404);
        assertThat(value("SELECT status FROM automation_versions WHERE automation_version_id = '" + d.version() + "'")).isEqualTo("1");
    }

    @Test
    void identidade_ou_permissao_forjadas_direto_no_banco_sao_recusadas_pelos_gatilhos() throws Exception {
        Ctx c = login();
        Draft d = draftWithArtifact(c);
        TestUser viewer = internalUser(c.org(), true, 2);
        grantWorkspaceRole(c.org(), viewer, UUID.fromString(d.workspace()), "VIEWER");
        String vm = value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + viewer.id() + "'");
        String org = c.org().id().toString();
        // publicar como o papel da aplicacao, atribuindo a autoria a quem nao tem automation.publish
        assertThat(asPlatform(org, "UPDATE automation_versions SET status = 2, published_at = clock_timestamp(), published_by_membership_id = '" + vm
                + "' WHERE automation_version_id = '" + d.version() + "'")).isEqualTo("42501");
        // artefato registrado por quem nao tem automation.manage
        assertThat(asPlatform(org, "INSERT INTO automation_artifacts (organization_id, workspace_id, automation_id, automation_version_id, sha256, size_bytes, "
                + "package_kind, fingerprint_rule, fingerprint, files_count, manifest_version, framework, execution_mode, storage_key, received_by_membership_id) "
                + "VALUES ('" + org + "', '" + d.workspace() + "', '" + d.automation() + "', '" + d.version() + "', '" + "a".repeat(64) + "', 10, 1, "
                + "'nekkora-agent/package-fingerprint/1', '" + "b".repeat(64) + "', 1, '1.5.0', 'nekkora-rpa==0.3.0', 'list', '" + "a".repeat(64) + "', '"
                + vm + "')")).isEqualTo("42501");
        // artefato e auditoria imutaveis
        assertThat(asPlatform(org, "UPDATE automation_artifacts SET size_bytes = 1 WHERE automation_version_id = '" + d.version() + "'")).isNotEqualTo("OK");
        assertThat(asPlatform(org, "DELETE FROM automation_admin_events WHERE organization_id = '" + org + "'")).isNotEqualTo("OK");
        // versao apontando para artefato com outra impressao: o gatilho recusa a publicacao mesmo com autoria valida
        assertThat(asPlatform(org, "UPDATE automation_versions SET command_json = jsonb_set(command_json, '{agent_package,fingerprint}', to_jsonb('"
                + "c".repeat(64) + "'::text)) WHERE automation_version_id = '" + d.version() + "'")).isEqualTo("OK");
        assertThat(asPlatform(org, "UPDATE automation_versions SET status = 2, published_at = clock_timestamp(), published_by_membership_id = '"
                + c.org().adminMembership() + "' WHERE automation_version_id = '" + d.version() + "'")).isEqualTo("23514");
        assertThat(value("SELECT status FROM automation_versions WHERE automation_version_id = '" + d.version() + "'")).isEqualTo("1");
    }

    // =========================================================================== estado e integridade

    @Test
    void automacao_suspensa_workspace_arquivado_artefato_ausente_ou_corrompido_e_falha_no_meio() throws Exception {
        Ctx c = login();
        Draft d = draftWithArtifact(c);
        String base = c.o() + "/automation-versions/" + d.version();
        stepUp(c.admin(), c.org().id(), c.org().adminId());
        Path file = STORE.resolve(c.org().id().toString()).resolve(d.sha() + ".zip");
        byte[] good = Files.readAllBytes(file);
        // corrompido no armazenamento
        Files.write(file, new byte[] {1, 2, 3});
        assertThat(c.admin().post(base + "/publish", publishBody(d.sha(), d.fingerprint())).status()).isEqualTo(409);
        // ausente
        Files.delete(file);
        assertThat(c.admin().post(base + "/publish", publishBody(d.sha(), d.fingerprint())).status()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM automation_admin_events WHERE act = 6 AND automation_version_id = '" + d.version() + "'")).isZero();
        Files.write(file, good);
        // falha no meio da publicacao (gatilho de teste que derruba o UPDATE): nada publicado, nenhuma auditoria
        DB.asSuperuser("CREATE FUNCTION public.it_falha_publicacao() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falha injetada'; END $$",
                "CREATE TRIGGER it_falha_publicacao BEFORE UPDATE ON automation_versions FOR EACH ROW WHEN (NEW.automation_version_id = '" + d.version()
                        + "') EXECUTE FUNCTION public.it_falha_publicacao()");
        try {
            assertThat(c.admin().post(base + "/publish", publishBody(d.sha(), d.fingerprint())).status()).isGreaterThanOrEqualTo(400);
        } finally {
            DB.asSuperuser("DROP TRIGGER it_falha_publicacao ON automation_versions", "DROP FUNCTION public.it_falha_publicacao()");
        }
        assertThat(value("SELECT status FROM automation_versions WHERE automation_version_id = '" + d.version() + "'")).isEqualTo("1");
        assertThat(count("SELECT count(*) FROM automation_admin_events WHERE act = 6 AND automation_version_id = '" + d.version() + "'")).isZero();
        // automacao suspensa: nem versao nova nem publicacao
        DB.asSuperuser("UPDATE automations SET status = 2 WHERE automation_id = '" + d.automation() + "'");
        assertThat(c.admin().post(c.o() + "/automations/" + d.automation() + "/versions", "{}").status()).isEqualTo(409);
        assertThat(c.admin().post(base + "/publish", publishBody(d.sha(), d.fingerprint())).status()).isEqualTo(409);
        DB.asSuperuser("UPDATE automations SET status = 1 WHERE automation_id = '" + d.automation() + "'");
        // workspace arquivado: nem automacao nova
        DB.asSuperuser("UPDATE workspaces SET status = 3 WHERE workspace_id = '" + d.workspace() + "'");
        assertThat(c.admin().post(c.o() + "/workspaces/" + d.workspace() + "/automations", "{\"name\":\"z\"}").status()).isEqualTo(409);
        DB.asSuperuser("UPDATE workspaces SET status = 1 WHERE workspace_id = '" + d.workspace() + "'");
        // tudo de volta: publica
        assertThat(c.admin().post(base + "/publish", publishBody(d.sha(), d.fingerprint())).status()).isEqualTo(200);
    }

    @Test
    void pacote_malicioso_ou_malformado_e_recusado_com_motivo_e_nada_muda() throws Exception {
        Ctx c = login();
        Draft d = draftWithArtifact(c);
        String base = c.o() + "/automation-versions/" + d.version();
        String before = value("SELECT command_json::text FROM automation_versions WHERE automation_version_id = '" + d.version() + "'");
        Map<String, byte[]> evil = PackageInspectorTest.realFiles();
        evil.put("../fora.py", new byte[] {1});
        BrowserClient.Response r = putZip(c.admin(), base + "/artifact", PackageInspectorTest.zip(evil));
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.body()).isEqualTo("{\"error\":\"package_rejected\",\"reason\":\"caminho_inseguro\"}");
        Map<String, byte[]> changed = PackageInspectorTest.realFiles();
        changed.put("main.py", "print('outro')\n".getBytes());
        assertThat(putZip(c.admin(), base + "/artifact", PackageInspectorTest.zip(changed)).body()).contains("hash_divergente");
        assertThat(putZip(c.admin(), base + "/artifact", "nao e zip".getBytes()).status()).isEqualTo(422);
        assertThat(value("SELECT command_json::text FROM automation_versions WHERE automation_version_id = '" + d.version() + "'")).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM automation_artifacts WHERE automation_version_id = '" + d.version() + "'")).isEqualTo(1);
        // os MESMOS bytes de novo (os guardados): idempotente, mesmo artefato, sem auditoria nova
        byte[] same = Files.readAllBytes(STORE.resolve(c.org().id().toString()).resolve(d.sha() + ".zip"));
        assertThat(putZip(c.admin(), base + "/artifact", same).status()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM automation_admin_events WHERE act = 5 AND automation_version_id = '" + d.version() + "'")).isEqualTo(1);
        // outro ZIP do mesmo pacote (outra compactacao): outro artefato, mesma impressao; a versao passa a apontar o novo
        assertThat(putZip(c.admin(), base + "/artifact", PackageInspectorTest.realZip()).status()).isEqualTo(200);
        assertThat(count("SELECT count(DISTINCT fingerprint) FROM automation_artifacts WHERE automation_version_id = '" + d.version() + "'")).isEqualTo(1);
    }

    // =========================================================================== apoio

    private static UUID workerInWorkspace(Org o, UUID ws) throws Exception {
        // limites da organizacao (fixture declarada, como no AgentAdminIT): a 010 exige revisao para registrar Worker
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES ('" + o.id() + "', " + code + ", 1, now(), 2, NULL, '" + o.adminId() + "')");
        }
        UUID worker = UUID.fromString(value("INSERT INTO workers (organization_id, name, status) VALUES ('" + o.id() + "', 'W-ciclo', 1) RETURNING worker_id"));
        DB.asSuperuser("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('"
                + o.id() + "', '" + worker + "', '" + ws + "', 1, '" + o.adminMembership() + "')");
        return worker;
    }

    private static void grantWorkspaceRole(Org o, TestUser u, UUID ws, String role) throws Exception {
        String m = value("SELECT membership_id FROM organization_memberships WHERE user_id = '" + u.id() + "'");
        DB.asSuperuser("INSERT INTO workspace_role_assignments (organization_id, workspace_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                + "SELECT '" + o.id() + "', '" + ws + "', '" + m + "', role_id, 2, 1, '" + o.adminMembership() + "' FROM roles WHERE code = '" + role + "'");
    }

    private static String future(int days) {
        return java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(days).withNano(0).toString();
    }

    /** Como o papel REAL da aplicacao, no contexto da organizacao; "OK" ou o SQLSTATE. */
    private static String asPlatform(String org, String sql) {
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
        String v = value(sql);
        return v == null ? 0 : Long.parseLong(v);
    }
}
