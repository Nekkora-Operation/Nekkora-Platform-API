package com.nekkora.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * Ferramenta do ensaio-frontend (F1.5, P-A) de ponta a ponta contra a Platform REAL em processo e o PostgreSQL 16 REAL
 * e descartavel do teste. NAO faz parte da regressao: o nome nao casa com *Test/*IT; executar so por nome
 * ({@code -pl nekkora-platform -am -Dtest=EnsaioFrontendVerificacao -Dsurefire.failIfNoSpecifiedTests=false
 * -Dnekkora.ensaio.frontend.saida=<dir>}; o {@code -am} e obrigatorio: sem ele o Maven usa um test-jar antigo do
 * nekkora-core do repositorio local, com outro modelo de banco). Exige python e o Git Bash. Opcional:
 * {@code -Dnekkora.ensaio.frontend.inatividade=1} (expiracao por inatividade com o relogio real, 31 min).
 *
 * <p>Passos, todos com as ferramentas reais de ops/homologacao (nada reimplementado aqui):
 * <ol>
 * <li>semeadura: lib/semear_frontend.py + sql/semear_frontend.sql, psql DENTRO do container do teste (superusuario do
 * banco descartavel, como o admin_cluster do ensaio) e PHC pelo PhcTool real;</li>
 * <li>OpenAPI do repositorio convertido por lib/OpenApiJson.java (SnakeYAML do classpath);</li>
 * <li>testes/jornadas_frontend.py preparar-contas (MFA de verdade pela API) e executar --mutacoes, no modo
 * {@code --teste-http}: sem gateway e sem TLS (o gateway tem prova propria: testes/test_gateway_frontend.sh), com a
 * origem publica configurada nesta Platform e um X-Forwarded-For por sessao (proxy confiavel 127.0.0.1); com
 * {@code --sem-portal}: o Portal (outro processo) nao sobe aqui, e os passos dele saem como NAO EXECUTADOS no relatorio.</li>
 * </ol>
 * Cada resposta e validada contra o OpenAPI pelo validador das jornadas; o primeiro resultado inesperado para tudo.
 */
class EnsaioFrontendVerificacao extends PlatformTestBase {

    private static final String BASH = new File("C:/Program Files/Git/bin/bash.exe").exists() ? "C:/Program Files/Git/bin/bash.exe" : "bash";

    @Test
    void semeaduraPreparacaoEJornadas() throws Exception {
        Path repo = com.nekkora.core.testsupport.RealDatabase.repositoryRoot();
        Path ops = repo.resolve("ops/homologacao");
        Path saida = Path.of(System.getProperty("nekkora.ensaio.frontend.saida",
                repo.resolve("api/nekkora-platform/target/ensaio-frontend-" + Instant.now().toString().replace(":", "")).toString()));
        Path segredos = Files.createTempDirectory("ensaio-frontend-segredos");
        Files.createDirectories(saida);

        String container = conteinerDoBanco();
        String psql = "docker exec -i " + container + " psql -X -q -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora";
        Path jar = repo.resolve("api/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar");
        Path testes = repo.resolve("api/nekkora-platform/target/test-classes");
        assertTrue(Files.exists(jar), "jar da Platform ausente (mvnw package): o PhcTool roda por ele, como na fixture da 016");
        String phc = "java -Dloader.path='" + barra(testes) + "' -Dloader.main=com.nekkora.platform.auth.crypto.PhcTool -cp '" + barra(jar)
                + "' org.springframework.boot.loader.launch.PropertiesLauncher 65536 3";

        // 1. semeadura
        int rc = roda(saida.resolve("1_semeadura.txt"), "python", barra(ops.resolve("lib/semear_frontend.py")), "semear",
                "--segredos", barra(segredos), "--evid", barra(saida.resolve("semeadura")), "--sql", barra(ops.resolve("sql/semear_frontend.sql")),
                "--psql", psql, "--phc", phc, "--bash", BASH);
        assertEquals(0, rc, "semeadura (ver 1_semeadura.txt)");

        // 2. OpenAPI -> JSON pela ferramenta do runner
        Path yaml = repo.resolve("api/openapi/nekkora-api-v1.yaml");
        Path json = saida.resolve("openapi.json");
        Files.deleteIfExists(json);
        String snake = Path.of(Yaml.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        rc = roda(saida.resolve("2_openapi.txt"), "java", "-cp", snake, barra(ops.resolve("lib/OpenApiJson.java")), barra(yaml), barra(json));
        assertEquals(0, rc, "conversao do OpenAPI");

        // 3. jornadas (preparar-contas e executar com as mutacoes)
        List<String> comum = List.of("--segredos", barra(segredos), "--evid", barra(saida.resolve("jornadas")), "--openapi-json", barra(json),
                "--openapi-yaml", barra(yaml), "--teste-http", "http://127.0.0.1:" + port, "--origem", ORIGIN, "--bash", BASH, "--sem-portal");
        List<String> c = new ArrayList<>(List.of("python", barra(ops.resolve("testes/jornadas_frontend.py")), "preparar-contas"));
        c.addAll(comum);
        rc = roda(saida.resolve("3_preparar_contas.txt"), c.toArray(String[]::new));
        assertEquals(0, rc, "preparar-contas (ver 3_preparar_contas.txt)");
        c = new ArrayList<>(List.of("python", barra(ops.resolve("testes/jornadas_frontend.py")), "executar", "--mutacoes",
                "--psql", psql, "--sql-sessao", barra(ops.resolve("sql/sessao_frontend.sql"))));
        // inatividade com o relogio REAL (31 min de espera): so com -Dnekkora.ensaio.frontend.inatividade=1
        if ("1".equals(System.getProperty("nekkora.ensaio.frontend.inatividade"))) {
            c.add("--inatividade-real");
        }
        c.addAll(comum);
        rc = roda(saida.resolve("4_jornadas.txt"), c.toArray(String[]::new));
        assertEquals(0, rc, "jornadas (ver 4_jornadas.txt)");

        // 4. P-B-C1: a SELECAO (continuacao de STOP) contra a Platform real: depois da suite completa (revoke-all ja feito
        // e O5 1 de 2), so sessoes-pos-revogacao e expiracao-absoluta; a O5 chega a 2 de 2
        List<String> sel = new ArrayList<>(List.of("--segredos", barra(segredos), "--evid", barra(saida.resolve("selecao")), "--openapi-json",
                barra(json), "--openapi-yaml", barra(yaml), "--teste-http", "http://127.0.0.1:" + port, "--origem", ORIGIN, "--bash", BASH));
        c = new ArrayList<>(List.of("python", barra(ops.resolve("testes/jornadas_frontend.py")), "executar",
                "--somente", "sessoes-pos-revogacao,expiracao-absoluta", "--vinculo-stop", "verificacao P-B-C1 (Platform em processo)",
                "--psql", psql, "--sql-sessao", barra(ops.resolve("sql/sessao_frontend.sql"))));
        c.addAll(sel);
        rc = roda(saida.resolve("5_selecao.txt"), c.toArray(String[]::new));
        assertEquals(0, rc, "selecao (ver 5_selecao.txt)");
        // o Python grava em modo texto (CRLF no Windows): compara com o fim de linha normalizado
        String resumo = Files.readString(saida.resolve("selecao/resumo_executar.json"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertTrue(resumo.contains("\"executadas\": [\n   \"sessoes-pos-revogacao\",\n   \"expiracao-absoluta\"\n  ]"), "executadas: " + resumo);
        assertTrue(resumo.contains("\"o5_usadas_depois\": 2"), "O5 2 de 2: " + resumo);
        String log = Files.readString(saida.resolve("selecao/jornadas.txt"), StandardCharsets.UTF_8);
        for (String nunca : List.of("[sem-sessao]", "[j-admin]", "[primeiro-acesso]", "[recuperacao]", "[sessoes]", " M.20 ", " M.21 ", " M.23 ", " M.24 ", " M.25 ")) {
            assertTrue(!log.contains(nunca), "a selecao executou o que nao foi selecionado: " + nunca);
        }
    }

    /** O container do PostgreSQL DESTE processo de teste (rotulo de sessao do Testcontainers + imagem do RealDatabase). */
    private static String conteinerDoBanco() throws Exception {
        Process p = new ProcessBuilder("docker", "ps", "-q", "--filter", "label=org.testcontainers.sessionId=" + DockerClientFactory.SESSION_ID,
                "--filter", "ancestor=" + com.nekkora.core.testsupport.RealDatabase.IMAGE).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor(60, TimeUnit.SECONDS);
        String[] ids = out.split("\\s+");
        assertEquals(1, ids.length, "esperado exatamente um PostgreSQL desta sessao do Testcontainers: " + out);
        return ids[0];
    }

    private static int roda(Path log, String... comando) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(comando).redirectErrorStream(true).redirectOutput(log.toFile());
        pb.environment().put("HOMOLOG_BASH", BASH);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        Process p = pb.start();
        // 60 min: a espera REAL de inatividade (31 min) cabe com folga; a primeira versao (30 min) encerrava as jornadas
        if (!p.waitFor(60, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    private static String barra(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }
}
