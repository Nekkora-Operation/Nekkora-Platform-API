package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.RealRedis;

import tools.jackson.databind.JsonNode;

/**
 * PLATFORM-AGENT-CATALOGO-001 T01: catalogo da maquina (036) no processo REAL do gateway (papel nekkora_ingestion), com
 * PostgreSQL e Redis reais. Prova a politica do documento do contrato: so o subconjunto autorizado ao Worker, nada de outra
 * organizacao ou de outro Worker, rascunhos e versoes sem autorizacao vigente fora, revalidacao a cada pagina e consulta,
 * cursor preso ao vinculo, nenhuma escrita, nenhum segredo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentGatewayApplication.class)
class MachineCatalogIT {

    static final RealDatabase DB = RealDatabase.shared();
    static final String EXTERNAL_RULE = "nekkora-agent/external-python-fingerprint/1";
    static final String STUDIO_RULE = "nekkora-agent/package-fingerprint/1";
    static final int QUOTA = 40;
    /** Duas organizacoes com o MESMO nome de workspace e de automacao ("WS canal" / "Biografias", do AgentFixture). */
    static AgentFixture a;
    static AgentFixture b;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        RealRedis redis = RealRedis.shared();
        r.add("nekkora.database.url", DB::url);
        r.add("nekkora.database.user", () -> "nekkora_ingestion");
        r.add("nekkora.database.password", () -> DB.password("nekkora_ingestion"));
        r.add("spring.data.redis.host", redis::host);
        r.add("spring.data.redis.port", redis::port);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.limits.batches-per-minute", () -> "100000");
        r.add("nekkora.limits.redeem-failures-per-hour", () -> "1000");
        r.add("nekkora.limits.catalog-reads-per-minute", () -> String.valueOf(QUOTA));
    }

    @BeforeAll
    static void fixture() {
        a = new AgentFixture(DB);
        b = new AgentFixture(DB);
    }

    // ------------------------------------------------------------------ apoio

    record Paired(MachineClient m, UUID worker, UUID wwa, UUID agent, AgentFixture fx) {
    }

    Paired paired(AgentFixture fx) {
        UUID worker = fx.worker("W-" + UUID.randomUUID().toString().substring(0, 6));
        UUID wwa = fx.authorizeWorker(worker);
        MachineClient m = new MachineClient(port, "10.62." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250)));
        JsonNode id = m.pair(fx.pairingCode(worker, "Estacao cat", "15 minutes"));
        return new Paired(m, worker, wwa, UUID.fromString(id.get("agentId").asString()), fx);
    }

    MachineClient.Response get(MachineClient m, String pathAndQuery) {
        return m.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/agent/api/v1" + pathAndQuery)).GET(), m.credential);
    }

    static String hex(char c) {
        return String.valueOf(c).repeat(64);
    }

    static String random64() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    static UUID workspace(AgentFixture fx, String name) {
        return UUID.fromString(fx.value("INSERT INTO workspaces (organization_id, name, status) VALUES ('" + fx.org + "', '" + name + "', 1) RETURNING workspace_id"));
    }

    static UUID automation(AgentFixture fx, UUID workspace, String name) {
        return UUID.fromString(fx.value("INSERT INTO automations (organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('" + fx.org + "', '"
                + workspace + "', '" + name + "', 1, '" + fx.adminMembership + "') RETURNING automation_id"));
    }

    static String command(String rule, String fingerprint) {
        return "jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','pacote-externo','sha256','" + "cd".repeat(32)
                + "'),'agent_package',jsonb_build_object('fingerprint_rule','" + rule + "','fingerprint','" + fingerprint + "'),'runtime','python3.12',"
                + "'entrypoint','segredo_de_entrada.py','arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb)";
    }

    /** Versao (rascunho). Externa: com artefato recebido (nome e versao declarados), como a Platform grava. */
    static UUID draft(AgentFixture fx, UUID workspace, UUID automation, String rule, String fingerprint, String packageName, String packageVersion) {
        UUID v = UUID.fromString(fx.value("INSERT INTO automation_versions (organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                + "VALUES ('" + fx.org + "', '" + workspace + "', '" + automation + "', 1, " + command(rule, fingerprint) + ", '" + fx.adminMembership
                + "') RETURNING automation_version_id"));
        if (packageVersion != null) {
            String sha = random64();
            fx.db.asSuperuser("INSERT INTO automation_artifacts (organization_id, workspace_id, automation_id, automation_version_id, sha256, size_bytes, package_kind, "
                    + "fingerprint_rule, fingerprint, files_count, manifest_version, python_requirement, dependencies, package_name, package_version, storage_key, "
                    + "received_by_membership_id) VALUES ('" + fx.org + "', '" + workspace + "', '" + automation + "', '" + v + "', '" + sha + "', 1234, 2, '" + rule
                    + "', '" + fingerprint + "', 3, '1.0.0', '>=3.10', '[\"requests==2.31.0\"]'::jsonb, '" + packageName + "', '" + packageVersion + "', '" + sha
                    + "', '" + fx.adminMembership + "')");
        }
        return v;
    }

    static void publish(AgentFixture fx, UUID version) {
        fx.db.asSuperuser("UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + fx.adminMembership
                + "' WHERE automation_version_id = '" + version + "'");
    }

    static UUID published(AgentFixture fx, UUID workspace, UUID automation, String rule, String fingerprint, String packageName, String packageVersion) {
        UUID v = draft(fx, workspace, automation, rule, fingerprint, packageName, packageVersion);
        publish(fx, v);
        return v;
    }

    static UUID assign(AgentFixture fx, UUID worker, UUID workspace) {
        return UUID.fromString(fx.value("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('"
                + fx.org + "', '" + worker + "', '" + workspace + "', 1, '" + fx.adminMembership + "') RETURNING assignment_id"));
    }

    static UUID authorize(AgentFixture fx, UUID worker, UUID wwa, UUID workspace, UUID automation, UUID version, String rule, String fingerprint, String validity) {
        int kind = EXTERNAL_RULE.equals(rule) ? 2 : 1;
        return UUID.fromString(fx.value("INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id, "
                + "automation_version_id, package_fingerprint, package_fingerprint_rule, source_kind, granted_by_membership_id, valid_until) VALUES ('" + fx.org + "', '"
                + workspace + "', '" + worker + "', '" + wwa + "', '" + automation + "', '" + version + "', '" + fingerprint + "', '" + rule + "', " + kind + ", '"
                + fx.adminMembership + "', clock_timestamp() + interval '" + validity + "') RETURNING authorization_id"));
    }

    static UUID monitor(AgentFixture fx, UUID worker, UUID wwa, UUID workspace, UUID automation) {
        return UUID.fromString(fx.value("INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id, worker_workspace_assignment_id, automation_id, "
                + "automation_version_id, package_fingerprint, package_fingerprint_rule, source_kind, granted_by_membership_id, valid_until) VALUES ('" + fx.org + "', '"
                + workspace + "', '" + worker + "', '" + wwa + "', '" + automation + "', NULL, NULL, NULL, 3, '" + fx.adminMembership
                + "', now() + interval '30 days') RETURNING authorization_id"));
    }

    static void revoke(AgentFixture fx, UUID authorization) {
        fx.db.asSuperuser("UPDATE local_run_authorizations SET revoked_at = clock_timestamp(), revoked_by_membership_id = '" + fx.adminMembership
                + "' WHERE authorization_id = '" + authorization + "'");
    }

    static List<String> automations(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("automationId").asString()));
        return out;
    }

    static JsonNode item(JsonNode page, UUID automation) {
        for (JsonNode i : page.get("items")) {
            if (i.get("automationId").asString().equals(automation.toString())) {
                return i;
            }
        }
        return null;
    }

    /** Todas as paginas, seguindo nextCursor. */
    List<JsonNode> all(MachineClient m, int limit) {
        List<JsonNode> pages = new ArrayList<>();
        String cursor = null;
        do {
            MachineClient.Response r = get(m, "/catalog?limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(r.status()).as(r.raw()).isEqualTo(200);
            pages.add(r.body());
            cursor = r.body().get("nextCursor").isNull() ? null : r.body().get("nextCursor").asString();
        } while (cursor != null && pages.size() < 50);
        return pages;
    }

    // ------------------------------------------------------------------ politica

    @Test
    void soOSubconjuntoElegivelComNomesENumerosSeparados() {
        Paired p = paired(a);
        UUID ws = workspace(a, "Financeiro " + UUID.randomUUID().toString().substring(0, 4));
        UUID wwa = assign(a, p.worker(), ws);
        UUID auto = automation(a, ws, "relatorio-modular");
        String f1 = random64();
        String f2 = random64();
        String f3 = random64();
        UUID v1 = published(a, ws, auto, EXTERNAL_RULE, f1, "relatorio-modular", "1.9.0");
        UUID v2 = published(a, ws, auto, EXTERNAL_RULE, f2, "relatorio-modular", "1.10.0");
        UUID v3 = published(a, ws, auto, EXTERNAL_RULE, f3, "relatorio-modular", "1.10.1"); // publicada SEM autorizacao: fora
        draft(a, ws, auto, EXTERNAL_RULE, random64(), "relatorio-modular", "2.0.0"); // rascunho: fora
        authorize(a, p.worker(), wwa, ws, auto, v1, EXTERNAL_RULE, f1, "30 days");
        authorize(a, p.worker(), wwa, ws, auto, v2, EXTERNAL_RULE, f2, "30 days");
        // automacao do mesmo workspace sem nenhuma autorizacao: nao aparece
        UUID hidden = automation(a, ws, "so-rascunho");
        published(a, ws, hidden, EXTERNAL_RULE, random64(), "so-rascunho", "1.0.0");
        // monitoramento so: visivel sem versao elegivel
        UUID mon = automation(a, ws, "monitorada");
        monitor(a, p.worker(), wwa, ws, mon);

        MachineClient.Response r = get(p.m(), "/catalog");
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode body = r.body();
        assertThat(body.get("catalogVersion").asString()).isEqualTo("1.0");
        assertThat(body.get("coverage").asString()).isEqualTo("WORKER_AUTHORIZED_SUBSET");
        assertThat(body.get("organization").get("organizationId").asString()).isEqualTo(a.org.toString());
        assertThat(body.get("organization").get("displayName").asString()).startsWith("Org canal ");
        assertThat(body.get("machine").get("agentId").asString()).isEqualTo(p.agent().toString());
        assertThat(body.get("machine").get("workerId").asString()).isEqualTo(p.worker().toString());
        assertThat(body.get("observedAt").asString()).isNotBlank();
        assertThat(automations(body)).containsExactlyInAnyOrder(auto.toString(), mon.toString());

        JsonNode it = item(body, auto);
        assertThat(it.get("automationName").asString()).isEqualTo("relatorio-modular");
        assertThat(it.get("workspaceId").asString()).isEqualTo(ws.toString());
        assertThat(it.get("workspaceName").asString()).startsWith("Financeiro ");
        assertThat(it.get("versionsTruncated").asBoolean()).isFalse();
        JsonNode vs = it.get("versions");
        assertThat(vs.size()).isEqualTo(2);
        // maior numero da Platform primeiro; o numero interno nao e a versao declarada
        assertThat(vs.get(0).get("automationVersionId").asString()).isEqualTo(v2.toString());
        assertThat(vs.get(0).get("versionNumber").asInt()).isEqualTo(2);
        assertThat(vs.get(0).get("packageVersion").asString()).isEqualTo("1.10.0");
        assertThat(vs.get(0).get("packageName").asString()).isEqualTo("relatorio-modular");
        assertThat(vs.get(0).get("packageFingerprint").asString()).isEqualTo(f2);
        assertThat(vs.get(0).get("packageFingerprintRule").asString()).isEqualTo(EXTERNAL_RULE);
        assertThat(vs.get(0).get("sourceKind").asString()).isEqualTo("EXTERNAL_PACKAGE");
        assertThat(vs.get(0).get("publishedAt").asString()).isNotBlank();
        assertThat(vs.get(1).get("automationVersionId").asString()).isEqualTo(v1.toString());
        assertThat(vs.get(1).get("packageVersion").asString()).isEqualTo("1.9.0");
        assertThat(r.raw()).doesNotContain(v3.toString()).doesNotContain("2.0.0").doesNotContain(hidden.toString());
        assertThat(item(body, mon).get("versions").size()).isZero();

        // pacote do Studio (fixture): sem versao declarada
        UUID studio = fx_studio(p);
        JsonNode s = item(get(p.m(), "/catalog").body(), studio);
        assertThat(s.get("versions").get(0).get("sourceKind").asString()).isEqualTo("STUDIO_PACKAGE");
        assertThat(s.get("versions").get(0).get("packageVersion").isNull()).isTrue();
        assertThat(s.get("versions").get(0).get("packageFingerprint").asString()).isEqualTo(AgentFixture.REAL_FINGERPRINT);
    }

    /** A automacao "Biografias" do AgentFixture, autorizada a esta maquina pelo pacote do Studio. */
    UUID fx_studio(Paired p) {
        p.fx().localRun(p.worker(), p.wwa());
        return p.fx().automation;
    }

    @Test
    void nadaSensivelNaResposta() {
        Paired p = paired(a);
        fx_studio(p);
        MachineClient.Response r = get(p.m(), "/catalog");
        assertThat(r.status()).isEqualTo(200);
        String raw = r.raw();
        assertThat(raw).doesNotContain("segredo_de_entrada").doesNotContain("main.py").doesNotContain("entrypoint").doesNotContain("command")
                .doesNotContain("cdcdcdcd").doesNotContain("abababab").doesNotContain("@o.test").doesNotContain("membership").doesNotContain("credential")
                .doesNotContain(p.m().secret).doesNotContain("requests==").doesNotContain("pythonRequirement").doesNotContain("storage")
                .doesNotContain("legal").doesNotContain("description");
        JsonNode body = r.body();
        List<String> top = new ArrayList<>();
        body.propertyNames().forEach(top::add);
        assertThat(top).containsExactlyInAnyOrder("catalogVersion", "observedAt", "organization", "machine", "coverage", "items", "nextCursor");
        List<String> version = new ArrayList<>();
        body.get("items").get(0).get("versions").get(0).propertyNames().forEach(version::add);
        assertThat(version).containsExactlyInAnyOrder("automationVersionId", "versionNumber", "publishedAt", "sourceKind", "packageFingerprintRule",
                "packageFingerprint", "packageName", "packageVersion");
    }

    @Test
    void duasOrganizacoesComNomesIguaisNaoSeMisturam() {
        Paired pa = paired(a);
        Paired pb = paired(b);
        fx_studio(pa);
        fx_studio(pb);
        JsonNode ca = get(pa.m(), "/catalog").body();
        JsonNode cb = get(pb.m(), "/catalog").body();
        assertThat(automations(ca)).containsExactly(a.automation.toString());
        assertThat(automations(cb)).containsExactly(b.automation.toString());
        // mesmo nome de projeto e de workspace, identificadores diferentes
        assertThat(item(ca, a.automation).get("automationName").asString()).isEqualTo(item(cb, b.automation).get("automationName").asString());
        assertThat(ca.get("organization").get("organizationId").asString()).isEqualTo(a.org.toString());
        assertThat(cb.get("organization").get("organizationId").asString()).isEqualTo(b.org.toString());
        // consulta direta pelo id da outra organizacao: 404, o mesmo corpo de um id inexistente
        MachineClient.Response cross = get(pa.m(), "/catalog/automations/" + b.automation);
        MachineClient.Response none = get(pa.m(), "/catalog/automations/" + UUID.randomUUID());
        assertThat(cross.status()).isEqualTo(404);
        assertThat(none.status()).isEqualTo(404);
        assertThat(cross.raw()).isEqualTo(none.raw());
        assertThat(cross.raw()).doesNotContain(b.automation.toString()).doesNotContain("Biografias");
    }

    @Test
    void doisWorkersDaMesmaOrganizacaoRecebemSubconjuntosProprios() {
        Paired p1 = paired(a);
        Paired p2 = paired(a);
        UUID ws = workspace(a, "Compartilhado " + UUID.randomUUID().toString().substring(0, 4));
        UUID w1 = assign(a, p1.worker(), ws);
        UUID w2 = assign(a, p2.worker(), ws);
        UUID x = automation(a, ws, "so-do-um");
        UUID y = automation(a, ws, "dos-dois");
        String fx = random64();
        String fy1 = random64();
        String fy2 = random64();
        UUID vx = published(a, ws, x, EXTERNAL_RULE, fx, "so-do-um", "1.0.0");
        UUID vy1 = published(a, ws, y, EXTERNAL_RULE, fy1, "dos-dois", "1.0.0");
        UUID vy2 = published(a, ws, y, EXTERNAL_RULE, fy2, "dos-dois", "1.1.0");
        authorize(a, p1.worker(), w1, ws, x, vx, EXTERNAL_RULE, fx, "30 days");
        authorize(a, p1.worker(), w1, ws, y, vy1, EXTERNAL_RULE, fy1, "30 days");
        authorize(a, p2.worker(), w2, ws, y, vy2, EXTERNAL_RULE, fy2, "30 days");

        JsonNode c1 = get(p1.m(), "/catalog").body();
        JsonNode c2 = get(p2.m(), "/catalog").body();
        assertThat(automations(c1)).containsExactlyInAnyOrder(x.toString(), y.toString());
        assertThat(automations(c2)).containsExactly(y.toString());
        // "mais recente" e entre as VISIVEIS a cada Worker
        assertThat(item(c1, y).get("versions").get(0).get("automationVersionId").asString()).isEqualTo(vy1.toString());
        assertThat(item(c2, y).get("versions").get(0).get("automationVersionId").asString()).isEqualTo(vy2.toString());
        assertThat(item(c2, y).get("versions").size()).isEqualTo(1);
        assertThat(get(p2.m(), "/catalog/automations/" + x).status()).isEqualTo(404);
        assertThat(get(p1.m(), "/catalog/automations/" + x).status()).isEqualTo(200);
    }

    @Test
    void consultaDiretaRevalidaRevogacaoEVencimento() throws InterruptedException {
        Paired p = paired(a);
        UUID ws = workspace(a, "Direta " + UUID.randomUUID().toString().substring(0, 4));
        UUID wwa = assign(a, p.worker(), ws);
        UUID auto = automation(a, ws, "direta");
        String f = random64();
        UUID v = published(a, ws, auto, EXTERNAL_RULE, f, "direta", "3.0.0");
        UUID z = authorize(a, p.worker(), wwa, ws, auto, v, EXTERNAL_RULE, f, "30 days");
        MachineClient.Response ok = get(p.m(), "/catalog/automations/" + auto);
        assertThat(ok.status()).as(ok.raw()).isEqualTo(200);
        assertThat(ok.body().get("item").get("automationName").asString()).isEqualTo("direta");
        assertThat(ok.body().get("organization").get("organizationId").asString()).isEqualTo(a.org.toString());
        assertThat(ok.body().get("machine").get("agentId").asString()).isEqualTo(p.agent().toString());
        revoke(a, z);
        assertThat(get(p.m(), "/catalog/automations/" + auto).status()).isEqualTo(404);

        // vencimento: autorizacao curta
        UUID auto2 = automation(a, ws, "vence");
        String f2 = random64();
        UUID v2 = published(a, ws, auto2, EXTERNAL_RULE, f2, "vence", "1.0.0");
        authorize(a, p.worker(), wwa, ws, auto2, v2, EXTERNAL_RULE, f2, "2 seconds");
        assertThat(get(p.m(), "/catalog/automations/" + auto2).status()).isEqualTo(200);
        Thread.sleep(2500);
        assertThat(get(p.m(), "/catalog/automations/" + auto2).status()).isEqualTo(404);
        assertThat(automations(get(p.m(), "/catalog").body())).doesNotContain(auto2.toString(), auto.toString());

        // identificador malformado: 400
        assertThat(get(p.m(), "/catalog/automations/nao-e-uuid").status()).isEqualTo(400);
    }

    @Test
    void paginasOrdenadasERevalidadasEntreUmaEOutra() {
        Paired p = paired(a);
        UUID ws = workspace(a, "Paginas " + UUID.randomUUID().toString().substring(0, 4));
        UUID wwa = assign(a, p.worker(), ws);
        List<UUID> autos = new ArrayList<>();
        List<UUID> auths = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID au = automation(a, ws, "pag-" + i);
            String f = random64();
            UUID v = published(a, ws, au, EXTERNAL_RULE, f, "pag-" + i, "1.0." + i);
            auths.add(authorize(a, p.worker(), wwa, ws, au, v, EXTERNAL_RULE, f, "30 days"));
            autos.add(au);
        }
        List<JsonNode> pages = all(p.m(), 2);
        assertThat(pages).hasSize(3);
        List<String> seen = new ArrayList<>();
        pages.forEach(pg -> seen.addAll(automations(pg)));
        assertThat(seen).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(autos.stream().map(UUID::toString).toList());
        // ordem deterministica (workspace, automacao): a mesma consulta, a mesma sequencia
        List<String> again = new ArrayList<>();
        all(p.m(), 2).forEach(pg -> again.addAll(automations(pg)));
        assertThat(again).isEqualTo(seen);
        assertThat(seen).isSorted();
        assertThat(pages.get(2).get("nextCursor").isNull()).isTrue();

        // revogacao entre paginas: a pagina seguinte ja nao traz a revogada (o cursor nao guarda permissao)
        MachineClient.Response first = get(p.m(), "/catalog?limit=2");
        String cursor = first.body().get("nextCursor").asString();
        UUID third = UUID.fromString(seen.get(2));
        revoke(a, auths.get(autos.indexOf(third)));
        MachineClient.Response second = get(p.m(), "/catalog?limit=2&cursor=" + cursor);
        assertThat(second.status()).isEqualTo(200);
        assertThat(automations(second.body())).doesNotContain(third.toString()).containsExactly(seen.get(3), seen.get(4));

        // retirada do Worker do workspace entre paginas: nada mais desse workspace
        a.db.asSuperuser("UPDATE worker_workspace_assignments SET status = 2, revoked_at = now(), revoked_by_membership_id = '" + a.adminMembership
                + "' WHERE assignment_id = '" + wwa + "'");
        MachineClient.Response after = get(p.m(), "/catalog?limit=2&cursor=" + cursor);
        assertThat(after.status()).isEqualTo(200);
        assertThat(after.body().get("items").size()).isZero();
        assertThat(after.body().get("nextCursor").isNull()).isTrue();
    }

    @Test
    void catalogoVazioLimitesECursorInvalido() {
        Paired p = paired(a);
        MachineClient.Response empty = get(p.m(), "/catalog");
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.body().get("items").size()).isZero();
        assertThat(empty.body().get("nextCursor").isNull()).isTrue();
        assertThat(empty.body().get("coverage").asString()).isEqualTo("WORKER_AUTHORIZED_SUBSET");
        for (String bad : List.of("0", "101", "-1", "abc", "1000", "")) {
            assertThat(get(p.m(), "/catalog?limit=" + bad).status()).as("limit=" + bad).isEqualTo(400);
        }
        assertThat(get(p.m(), "/catalog?limit=100").status()).isEqualTo(200);
        assertThat(get(p.m(), "/catalog?limit=1").status()).isEqualTo(200);
        for (String bad : List.of("xx", "eHh4", "a.b", "%2Fetc")) {
            assertThat(get(p.m(), "/catalog?cursor=" + bad).status()).as("cursor=" + bad).isEqualTo(400);
        }
    }

    @Test
    void cursorDeOutroVinculoOuOrganizacaoERecusado() {
        Paired p1 = paired(a);
        Paired p2 = paired(a);
        Paired pb = paired(b);
        for (Paired p : List.of(p1, p2, pb)) {
            UUID ws = workspace(p.fx(), "Cursor " + UUID.randomUUID().toString().substring(0, 4));
            UUID wwa = assign(p.fx(), p.worker(), ws);
            for (int i = 0; i < 2; i++) {
                UUID au = automation(p.fx(), ws, "cur-" + i);
                String f = random64();
                UUID v = published(p.fx(), ws, au, EXTERNAL_RULE, f, "cur-" + i, "1.0.0");
                authorize(p.fx(), p.worker(), wwa, ws, au, v, EXTERNAL_RULE, f, "30 days");
            }
        }
        String c1 = get(p1.m(), "/catalog?limit=1").body().get("nextCursor").asString();
        String cb = get(pb.m(), "/catalog?limit=1").body().get("nextCursor").asString();
        assertThat(get(p1.m(), "/catalog?limit=1&cursor=" + c1).status()).isEqualTo(200);
        MachineClient.Response otherWorker = get(p2.m(), "/catalog?limit=1&cursor=" + c1);
        MachineClient.Response otherOrg = get(p1.m(), "/catalog?limit=1&cursor=" + cb);
        assertThat(otherWorker.status()).isEqualTo(400);
        assertThat(otherOrg.status()).isEqualTo(400);
        assertThat(otherOrg.raw()).isEqualTo(otherWorker.raw()).doesNotContain(b.org.toString());
    }

    @Test
    void credencialRevogadaVinculoEncerradoEWorkerInativo() {
        // credencial revogada pela administracao: 401
        Paired p = paired(a);
        fx_studio(p);
        assertThat(get(p.m(), "/catalog").status()).isEqualTo(200);
        a.db.asSuperuser("UPDATE agent_credentials SET revoked_at = now(), revoked_by_membership_id = '" + a.adminMembership
                + "', revocation_reason = 3 WHERE agent_id = '" + p.agent() + "' AND revoked_at IS NULL");
        assertThat(get(p.m(), "/catalog").status()).isEqualTo(401);
        assertThat(get(p.m(), "/catalog/automations/" + a.automation).status()).isEqualTo(401);

        // vinculo encerrado pela propria maquina (T13): 401 depois
        Paired q = paired(a);
        fx_studio(q);
        MachineClient.Response u = q.m().unlink(UUID.randomUUID().toString(), q.agent().toString(), List.of());
        assertThat(u.status()).as(u.raw()).isEqualTo(200);
        assertThat(get(q.m(), "/catalog").status()).isEqualTo(401);

        // Worker suspenso: 403 (a credencial vale, o Worker nao)
        Paired w = paired(a);
        fx_studio(w);
        a.db.asSuperuser("UPDATE workers SET status = 2 WHERE worker_id = '" + w.worker() + "'");
        MachineClient.Response s = get(w.m(), "/catalog");
        assertThat(s.status()).as(s.raw()).isEqualTo(403);
        assertThat(get(w.m(), "/catalog/automations/" + a.automation).status()).isEqualTo(403);
        assertThat(s.raw()).doesNotContain("Biografias");

        // sem credencial
        MachineClient anon = new MachineClient(port, "10.63.0.9");
        assertThat(get(anon, "/catalog").status()).isEqualTo(401);
    }

    @Test
    void consultaRepetidaNaoGravaNada() {
        Paired p = paired(a);
        fx_studio(p);
        String before = snapshot(p);
        for (int i = 0; i < 5; i++) {
            assertThat(get(p.m(), "/catalog").status()).isEqualTo(200);
            assertThat(get(p.m(), "/catalog/automations/" + a.automation).status()).isEqualTo(200);
        }
        assertThat(snapshot(p)).isEqualTo(before);
    }

    String snapshot(Paired p) {
        return a.value("SELECT (SELECT count(*) FROM automations) || '|' || (SELECT count(*) FROM automation_versions) || '|' || "
                + "(SELECT count(*) FROM automation_versions WHERE status = 1) || '|' || (SELECT count(*) FROM automation_artifacts) || '|' || "
                + "(SELECT count(*) FROM local_run_authorizations) || '|' || (SELECT count(*) FROM agent_runs) || '|' || (SELECT count(*) FROM run_requests) || '|' || "
                + "(SELECT count(*) FROM agent_admin_events) || '|' || (SELECT count(*) FROM automation_admin_events) || '|' || "
                + "(SELECT coalesce(last_heartbeat_at::text, '-') || ':' || coalesce(agent_version, '-') FROM agents WHERE agent_id = '" + p.agent() + "') || '|' || "
                + "(SELECT count(*) FROM agent_presence WHERE agent_id = '" + p.agent() + "')");
    }

    @Test
    void cotaPorMaquinaCom429ERetryAfter() {
        Paired p = paired(a);
        Paired other = paired(a);
        int status = 200;
        MachineClient.Response last = null;
        for (int i = 0; i < QUOTA + 5 && status == 200; i++) {
            last = get(p.m(), "/catalog?limit=1");
            status = last.status();
        }
        assertThat(status).isEqualTo(429);
        assertThat(last.headers().firstValue("Retry-After")).isPresent();
        // a cota e por maquina: outra maquina continua lendo
        assertThat(get(other.m(), "/catalog").status()).isEqualTo(200);
    }

    @Test
    void rotasAnterioresContinuamIguais() {
        Paired p = paired(a);
        UUID z = p.fx().localRun(p.worker(), p.wwa());
        MachineClient.Response r = p.m().authorizations();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.raw()).contains(z.toString());
        List<String> fields = new ArrayList<>();
        r.body().get("items").get(0).propertyNames().forEach(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("authorizationId", "workspaceId", "automationId", "automationVersionId", "packageFingerprint",
                "packageFingerprintRule", "validUntil", "sourceKind");
        assertThat(p.m().heartbeat().status()).isEqualTo(200);
    }
}
