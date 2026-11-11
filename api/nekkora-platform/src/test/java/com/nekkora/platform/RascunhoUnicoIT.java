package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * PLATFORM-AGENT-V2-002, T09: no maximo um rascunho por automacao, que so passa a existir quando o servidor ACEITA o ZIP
 * (PUT /automations/{id}/draft/artifact). Os cenarios da prova da missao, do lado da API: ZIP invalido nao cria nada;
 * ZIP valido cria v2 de uma vez; repeticao apos resposta perdida devolve o mesmo rascunho; envios concorrentes nao duplicam
 * nem sobrescrevem em silencio; ZIP invalido preserva o pacote anterior; publicar congela e nao cria v3 nem executa nada.
 */
class RascunhoUnicoIT extends PlatformTestBase {

    static final Path STORE;

    static {
        try {
            STORE = Files.createTempDirectory("nekkora-artefatos-t09");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void artifacts(DynamicPropertyRegistry r) {
        r.add("nekkora.artefatos.dir", STORE::toString);
    }

    record Ctx(Org org, BrowserClient admin, String o, String automation) {
    }

    private Ctx automationWithPublishedV1() throws Exception {
        Org org = newOrganization();
        BrowserClient b = browser();
        firstLogin(b, org.id(), new TestUser(org.adminId(), org.adminEmail(), PASSWORD.toCharArray()));
        String o = "/api/v1/o/" + org.id();
        BrowserClient.Response ws = b.post(o + "/workspaces", "{\"name\":\"WS T09\"}");
        assertThat(ws.status()).as(ws.body()).isEqualTo(201);
        String w = ws.field("workspaceId");
        BrowserClient.Response au = b.post(o + "/workspaces/" + w + "/automations", "{\"name\":\"Biografias\"}");
        assertThat(au.status()).as(au.body()).isEqualTo(201);
        String a = au.field("automationId");
        Ctx c = new Ctx(org, b, o, a);
        BrowserClient.Response v1 = put(c, null, null, pkg(1), "");
        assertThat(v1.status()).as(v1.body()).isEqualTo(201);
        assertThat(v1.body()).contains("\"versionNumber\":1,");
        assertThat(b.post("/api/v1/o/" + org.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(org.adminId()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response pub = b.post(o + "/automation-versions/" + v1.field("automationVersionId") + "/publish", publishBody(c, v1.field("automationVersionId")));
        assertThat(pub.status()).as(pub.body()).isEqualTo(200);
        return c;
    }

    private BrowserClient.Response put(Ctx c, String expectedVersion, String expectedSha, byte[] zip, String extraQuery) {
        StringBuilder q = new StringBuilder();
        if (expectedVersion != null) {
            q.append("expectedDraftVersionId=").append(expectedVersion);
        }
        if (expectedSha != null) {
            q.append(q.isEmpty() ? "" : "&").append("expectedSha256=").append(expectedSha);
        }
        if (!extraQuery.isEmpty()) {
            q.append(q.isEmpty() ? "" : "&").append(extraQuery);
        }
        String path = c.o() + "/automations/" + c.automation() + "/draft/artifact" + (q.isEmpty() ? "" : "?" + q);
        return c.admin().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).PUT(HttpRequest.BodyPublishers.ofByteArray(zip))
                .header("Content-Type", "application/zip"));
    }

    private String publishBody(Ctx c, String version) throws Exception {
        String sha = value("SELECT command_json->'artifact'->>'sha256' FROM automation_versions WHERE automation_version_id = '" + version + "'");
        String fp = value("SELECT command_json->'agent_package'->>'fingerprint' FROM automation_versions WHERE automation_version_id = '" + version + "'");
        return "{\"expectedArtifactSha256\":\"" + sha + "\",\"expectedPackageFingerprint\":\"" + fp + "\"}";
    }

    private String versions(Ctx c) throws Exception {
        return value("SELECT string_agg(version_number::text || ':' || status, ',' ORDER BY version_number) FROM automation_versions WHERE automation_id = '"
                + c.automation() + "'");
    }

    private String draftSha(String version) throws Exception {
        return value("SELECT command_json->'artifact'->>'sha256' FROM automation_versions WHERE automation_version_id = '" + version + "'");
    }

    /**
     * O pacote real do Studio, valido, com a data das entradas fixada em {@code n}: o mesmo conteudo (mesma impressao) em
     * bytes diferentes por variante (sha256 diferente), deterministico.
     */
    private static byte[] pkg(int n) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(b)) {
            for (Map.Entry<String, byte[]> e : PackageInspectorTest.realFiles().entrySet()) {
                java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(e.getKey());
                ze.setTime(1_700_000_000_000L + n * 86_400_000L);
                z.putNextEntry(ze);
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return b.toByteArray();
    }

    private static byte[] invalidZip() throws Exception {
        Map<String, byte[]> evil = PackageInspectorTest.realFiles();
        evil.put("../fora.py", new byte[] {1});
        return PackageInspectorTest.zip(evil);
    }

    // =========================================================================== prova da missao (lado da API)

    @Test
    void zipInvalidoNaoCriaNadaEZipValidoCriaAV2DeUmaVezComOsCampos() throws Exception {
        Ctx c = automationWithPublishedV1();
        // "Nova versao" aberta e abandonada varias vezes nao chama a API; aqui, dez envios RECUSADOS: nada persiste
        for (int i = 0; i < 10; i++) {
            BrowserClient.Response r = put(c, null, null, invalidZip(), "runtime=python3.11&argument=--dia");
            assertThat(r.status()).isEqualTo(422);
            assertThat(r.body()).isEqualTo("{\"error\":\"package_rejected\",\"reason\":\"caminho_inseguro\"}");
        }
        assertThat(put(c, null, null, "nao e zip".getBytes(), "").status()).isEqualTo(422);
        assertThat(versions(c)).isEqualTo("1:2");
        assertThat(count("SELECT count(*) FROM automation_artifacts WHERE automation_id = '" + c.automation() + "'")).isEqualTo(1);
        // o primeiro ZIP aceito cria a v2 (o proximo numero continuou sendo 2), ja com os campos do formulario
        BrowserClient.Response v2 = put(c, null, null, pkg(2), "runtime=python3.11&argument=--dia&argument=2026-11-10");
        assertThat(v2.status()).as(v2.body()).isEqualTo(201);
        assertThat(v2.body()).contains("\"versionNumber\":2,");
        assertThat(v2.field("status")).isEqualTo("DRAFT");
        assertThat(v2.field("runtime")).isEqualTo("python3.11");
        assertThat(v2.body()).contains("\"arguments\":[\"--dia\",\"2026-11-10\"]");
        assertThat(versions(c)).isEqualTo("1:2,2:1");
        // salvo no mesmo instante: a leitura (como ao voltar, fechar e reabrir) encontra o mesmo ID e o mesmo pacote
        BrowserClient.Response again = c.admin().get(c.o() + "/automation-versions/" + v2.field("automationVersionId"));
        assertThat(again.status()).isEqualTo(200);
        assertThat(draftSha(v2.field("automationVersionId"))).isNotNull();
        // nada foi executado nem autorizado
        assertThat(count("SELECT count(*) FROM agent_runs WHERE organization_id = '" + c.org().id() + "'")).isZero();
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE organization_id = '" + c.org().id() + "'")).isZero();
    }

    @Test
    void repeticaoAposRespostaPerdidaEEnviosConcorrentesDeixamUmaUnicaV2SemSobrescritaSilenciosa() throws Exception {
        Ctx c = automationWithPublishedV1();
        byte[] a = pkg(3);
        // resposta perdida: a mesma requisicao (expectativa "nenhum") repetida devolve o MESMO rascunho
        BrowserClient.Response first = put(c, null, null, a, "");
        assertThat(first.status()).isEqualTo(201);
        String v2 = first.field("automationVersionId");
        BrowserClient.Response repeat = put(c, null, null, a, "");
        assertThat(repeat.status()).isEqualTo(200);
        assertThat(repeat.field("automationVersionId")).isEqualTo(v2);
        assertThat(versions(c)).isEqualTo("1:2,2:1");
        String shaA = draftSha(v2);
        // outra aba/pessoa com expectativa desatualizada ("nenhum") e OUTRO pacote: 409, nada sobrescrito
        byte[] b = pkg(4);
        BrowserClient.Response stale = put(c, null, null, b, "");
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body()).isEqualTo("{\"error\":\"conflict\",\"reason\":\"draft_changed\"}");
        assertThat(draftSha(v2)).isEqualTo(shaA);
        // expectativa com o sha errado: tambem 409
        assertThat(put(c, v2, "0".repeat(64), b, "").status()).isEqualTo(409);
        assertThat(draftSha(v2)).isEqualTo(shaA);
        // corrida: quatro envios simultaneos, todos esperando a v2 com o pacote A; um substitui, os demais veem 409 ou o mesmo
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<BrowserClient.Response>> f = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                byte[] zip = pkg(100 + i + 5);
                f.add(pool.submit(() -> {
                    go.await();
                    return put(c, v2, shaA, zip, "");
                }));
            }
            go.countDown();
            int ok = 0;
            for (Future<BrowserClient.Response> x : f) {
                BrowserClient.Response r = x.get();
                assertThat(r.status()).isIn(200, 409);
                if (r.status() == 200) {
                    ok++;
                    assertThat(r.field("automationVersionId")).isEqualTo(v2);
                }
            }
            assertThat(ok).isGreaterThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(versions(c)).isEqualTo("1:2,2:1");
        assertThat(count("SELECT count(*) FROM automation_versions WHERE automation_id = '" + c.automation() + "' AND status = 1")).isEqualTo(1);
        // a pessoa ve qual pacote permaneceu: a leitura do rascunho mostra o sha atual
        assertThat(c.admin().get(c.o() + "/automation-versions/" + v2).body()).contains(draftSha(v2));
    }

    @Test
    void zipInvalidoPreservaOPacoteAnteriorEOValidoAtualizaAMesmaV2() throws Exception {
        Ctx c = automationWithPublishedV1();
        BrowserClient.Response first = put(c, null, null, pkg(6), "");
        String v2 = first.field("automationVersionId");
        String shaA = draftSha(v2);
        String before = value("SELECT command_json::text FROM automation_versions WHERE automation_version_id = '" + v2 + "'");
        BrowserClient.Response bad = put(c, v2, shaA, invalidZip(), "");
        assertThat(bad.status()).isEqualTo(422);
        assertThat(value("SELECT command_json::text FROM automation_versions WHERE automation_version_id = '" + v2 + "'")).isEqualTo(before);
        BrowserClient.Response good = put(c, v2, shaA, pkg(7), "");
        assertThat(good.status()).as(good.body()).isEqualTo(200);
        assertThat(good.field("automationVersionId")).isEqualTo(v2);
        assertThat(versions(c)).isEqualTo("1:2,2:1");
        // expectativa sem sha quando o rascunho ja tem pacote: desatualizada
        assertThat(put(c, v2, null, invalidZip(), "").status()).isEqualTo(422); // o ZIP e inspecionado antes
        assertThat(put(c, v2, null, pkg(8), "").status()).isIn(200, 409);
        // expectativa malformada
        assertThat(put(c, null, shaA, pkg(9), "").status()).isEqualTo(400);
        assertThat(put(c, v2, "XYZ", pkg(10), "").status()).isEqualTo(400);
    }

    @Test
    void publicarAV2CongelaENaoCriaV3NemExecutaNada() throws Exception {
        Ctx c = automationWithPublishedV1();
        BrowserClient.Response first = put(c, null, null, pkg(11), "");
        String v2 = first.field("automationVersionId");
        String shaV2 = draftSha(v2);
        assertThat(c.admin().post(c.o() + "/automation-versions/" + v2 + "/publish", publishBody(c, v2)).status()).isEqualTo(200);
        String frozen = value("SELECT command_json::text FROM automation_versions WHERE automation_version_id = '" + v2 + "'");
        // a aba antiga ainda espera a v2 como rascunho: 409, nada criado (nenhuma v3 em silencio)
        BrowserClient.Response staleTab = put(c, v2, shaV2, pkg(12), "");
        assertThat(staleTab.status()).isEqualTo(409);
        assertThat(staleTab.body()).isEqualTo("{\"error\":\"conflict\",\"reason\":\"draft_changed\"}");
        // a rota por versao tambem nao muda a publicada
        BrowserClient.Response perVersion = c.admin().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + c.o() + "/automation-versions/" + v2
                + "/artifact")).PUT(HttpRequest.BodyPublishers.ofByteArray(pkg(13))).header("Content-Type", "application/zip"));
        assertThat(perVersion.status()).isEqualTo(409);
        assertThat(value("SELECT command_json::text FROM automation_versions WHERE automation_version_id = '" + v2 + "'")).isEqualTo(frozen);
        // abrir e abandonar a proxima: nenhuma v3 ate o proximo ZIP aceito
        assertThat(put(c, null, null, invalidZip(), "").status()).isEqualTo(422);
        assertThat(versions(c)).isEqualTo("1:2,2:2");
        BrowserClient.Response v3 = put(c, null, null, pkg(14), "");
        assertThat(v3.status()).isEqualTo(201);
        assertThat(v3.body()).contains("\"versionNumber\":3,");
        assertThat(count("SELECT count(*) FROM agent_runs WHERE organization_id = '" + c.org().id() + "'")).isZero();
    }

    @Test
    void rotaAntigaCriaNoMaximoUmRascunhoVazioESemPermissaoOuOutraOrganizacaoNadaMuda() throws Exception {
        Ctx c = automationWithPublishedV1();
        // Web V1: "Nova versao" ainda cria UM rascunho vazio (limitacao transitoria); o segundo clique recebe 409
        BrowserClient.Response legacy = c.admin().post(c.o() + "/automations/" + c.automation() + "/versions", "{}");
        assertThat(legacy.status()).isEqualTo(201);
        assertThat(c.admin().post(c.o() + "/automations/" + c.automation() + "/versions", "{}").body())
                .isEqualTo("{\"error\":\"conflict\",\"reason\":\"draft_exists\"}");
        String v2 = legacy.field("automationVersionId");
        // a Web nova continua o rascunho vazio da V1: expectativa (v2, sem pacote)
        BrowserClient.Response cont = put(c, v2, null, pkg(15), "");
        assertThat(cont.status()).as(cont.body()).isEqualTo(200);
        assertThat(cont.field("automationVersionId")).isEqualTo(v2);
        assertThat(versions(c)).isEqualTo("1:2,2:1");
        // outra organizacao: 404 e nada criado la nem aqui
        Org other = newOrganization();
        BrowserClient ob = browser();
        firstLogin(ob, other.id(), new TestUser(other.adminId(), other.adminEmail(), PASSWORD.toCharArray()));
        BrowserClient.Response cross = ob.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/o/" + other.id() + "/automations/"
                + c.automation() + "/draft/artifact")).PUT(HttpRequest.BodyPublishers.ofByteArray(pkg(16))).header("Content-Type", "application/zip"));
        assertThat(cross.status()).isEqualTo(404);
        // conta sem automation.manage: 403
        TestUser plain = internalUser(c.org(), true, 2);
        BrowserClient p = browser();
        firstLogin(p, c.org().id(), plain);
        BrowserClient.Response denied = p.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + c.o() + "/automations/" + c.automation()
                + "/draft/artifact")).PUT(HttpRequest.BodyPublishers.ofByteArray(pkg(17))).header("Content-Type", "application/zip"));
        assertThat(denied.status()).isIn(403, 404);
        assertThat(versions(c)).isEqualTo("1:2,2:1");
    }

    // =========================================================================== apoio

    private static String value(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long count(String sql) throws Exception {
        String v = value(sql);
        return v == null ? 0 : Long.parseLong(v);
    }

    @SuppressWarnings("unused")
    private static UUID uuid(String s) {
        return UUID.fromString(s);
    }
}
