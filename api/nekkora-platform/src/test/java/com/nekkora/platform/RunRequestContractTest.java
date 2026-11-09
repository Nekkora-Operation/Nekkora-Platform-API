package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.nekkora.platform.web.RunRequestController;

/**
 * Contrato publicado do pedido de execucao (030): as rotas de {@link RunRequestController} sao EXATAMENTE as da tag "Pedido
 * de execucao", com os codigos que o ExternalPythonRunRequestIT exercita e escrita com CSRF; o pedido nao transporta
 * comando, caminho nem segredo (o esquema nao tem esses campos) e a origem explicita esta publicada.
 */
class RunRequestContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void rotasDoPedidoSaoAsPublicadasComCodigosESeguranca() throws IOException {
        Set<String> mapeadas = new TreeSet<>();
        for (Method m : RunRequestController.class.getDeclaredMethods()) {
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
        Map<String, Object> spec = AgentContractTest.spec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Set<String> publicadas = new TreeSet<>();
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            for (Map.Entry<String, Object> op : ((Map<String, Object>) e.getValue()).entrySet()) {
                Object tags = ((Map<String, Object>) op.getValue()).get("tags");
                if (tags != null && tags.toString().contains("Pedido de execucao")) {
                    publicadas.add(op.getKey() + " " + e.getKey());
                }
            }
        }
        assertThat(mapeadas).isEqualTo(publicadas).hasSize(4);
        Map<String, Set<String>> exercitados = Map.of(
                "post /api/v1/o/{org}/workspaces/{workspace}/run-requests", Set.of("200", "201", "400", "403", "409"),
                "get /api/v1/o/{org}/workspaces/{workspace}/run-requests", Set.of("200"),
                "get /api/v1/o/{org}/run-requests/{runRequest}", Set.of("200"),
                "post /api/v1/o/{org}/run-requests/{runRequest}/revoke", Set.of("200", "409"));
        for (Map.Entry<String, Set<String>> e : exercitados.entrySet()) {
            String[] mp = e.getKey().split(" ", 2);
            Map<String, Object> o = (Map<String, Object>) ((Map<String, Object>) paths.get(mp[1])).get(mp[0]);
            assertThat(((Map<String, Object>) o.get("responses")).keySet()).as(e.getKey()).containsAll(e.getValue()).contains("429", "503");
            String security = o.get("security").toString();
            assertThat(security).contains("sessionCookie");
            if (!mp[0].equals("get")) {
                assertThat(security).as(e.getKey()).contains("csrfHeader");
            }
        }
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        Map<String, Object> create = (Map<String, Object>) ((Map<String, Object>) schemas.get("RunRequestCreate")).get("properties");
        assertThat(create.keySet()).containsExactlyInAnyOrder("authorizationId", "agentId", "validForSeconds", "requestKey");
        assertThat(schemas.get("SourceKind").toString()).contains("STUDIO_PACKAGE", "EXTERNAL_PACKAGE", "EXTERNAL_MONITORED");
        assertThat(schemas.get("LifecycleArtifact").toString()).contains("nekkora-agent/external-python-fingerprint/1");
    }
}
