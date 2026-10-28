package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Contrato publicado do lado da maquina (openapi/nekkora-agent-v1.yaml): as rotas de {@link GatewayController} sao
 * EXATAMENTE as publicadas, com os codigos que AgentGatewayIT e AgentGatewayLimitsIT exercitam, o cabecalho de contrato e a
 * credencial de maquina (nunca cookie de sessao).
 */
class GatewayContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void rotasDaMaquinaSaoAsPublicadas() throws IOException {
        Map<String, Object> spec;
        try (Reader r = Files.newBufferedReader(Path.of("../openapi/nekkora-agent-v1.yaml"))) {
            spec = new Yaml().load(r);
        }
        Set<String> mapeadas = new TreeSet<>();
        for (Method m : GatewayController.class.getDeclaredMethods()) {
            if (m.getAnnotation(PostMapping.class) != null) {
                for (String p : m.getAnnotation(PostMapping.class).value()) {
                    mapeadas.add("post " + p);
                }
            }
            if (m.getAnnotation(GetMapping.class) != null) {
                for (String p : m.getAnnotation(GetMapping.class).value()) {
                    mapeadas.add("get " + p);
                }
            }
        }
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Set<String> publicadas = new TreeSet<>();
        paths.forEach((path, ops) -> ((Map<String, Object>) ops).keySet().forEach(method -> publicadas.add(method + " " + path)));
        assertThat(mapeadas).isEqualTo(publicadas).hasSize(6);

        Map<String, Set<String>> exercitados = Map.of(
                "post /agent/api/v1/enrollments/redeem", Set.of("200", "401", "409"),
                "post /agent/api/v1/heartbeat", Set.of("200", "401"),
                "get /agent/api/v1/authorizations", Set.of("200", "400", "401"),
                "post /agent/api/v1/runs", Set.of("200", "201", "403", "409"),
                "post /agent/api/v1/runs/{agentExecutionId}/supervisor", Set.of("200", "409"),
                "post /agent/api/v1/runs/{agentExecutionId}/events", Set.of("200", "400", "401", "429", "500", "503"));
        for (Map.Entry<String, Set<String>> e : exercitados.entrySet()) {
            String[] mp = e.getKey().split(" ", 2);
            Map<String, Object> o = (Map<String, Object>) ((Map<String, Object>) paths.get(mp[1])).get(mp[0]);
            assertThat(((Map<String, Object>) o.get("responses")).keySet()).as(e.getKey()).containsAll(e.getValue());
            assertThat(o.get("parameters").toString()).as(e.getKey()).contains("#/components/parameters/Contract");
        }
        assertThat(spec.toString()).doesNotContain("sessionCookie").doesNotContain("csrfHeader");
    }
}
