package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import com.nekkora.platform.web.AutomationLifecycleController;

/**
 * Contrato publicado do ciclo de vida (PLATFORM-AUTOMATION-LIFECYCLE-001): as rotas de {@link AutomationLifecycleController}
 * sao EXATAMENTE as da tag "Ciclo de vida das automacoes", com os codigos que o AutomationLifecycleIT exercita, a seguranca
 * (escrita com CSRF) e o texto corrigido da autorizacao de execucao local (impressao do pacote, nunca o sha256 dos bytes).
 */
class AutomationLifecycleContractTest {

    static final String TAG = "Ciclo de vida das automacoes";

    @Test
    @SuppressWarnings("unchecked")
    void rotasPublicadasCodigosESeguranca() throws IOException {
        Set<String> mapeadas = new TreeSet<>();
        for (Method m : AutomationLifecycleController.class.getDeclaredMethods()) {
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
            if (m.getAnnotation(PatchMapping.class) != null) {
                for (String p : m.getAnnotation(PatchMapping.class).value()) {
                    mapeadas.add("patch " + p);
                }
            }
            if (m.getAnnotation(PutMapping.class) != null) {
                for (String p : m.getAnnotation(PutMapping.class).path()) {
                    mapeadas.add("put " + p);
                }
            }
        }
        Map<String, Object> paths = (Map<String, Object>) AgentContractTest.spec().get("paths");
        Set<String> publicadas = new TreeSet<>();
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            for (Map.Entry<String, Object> op : ((Map<String, Object>) e.getValue()).entrySet()) {
                if (op.getValue() instanceof Map<?, ?> o && String.valueOf(o.get("tags")).contains(TAG)) {
                    publicadas.add(op.getKey() + " " + e.getKey());
                }
            }
        }
        assertThat(mapeadas).as("rotas do controller = rotas publicadas").isEqualTo(publicadas).hasSize(8);

        Map<String, Set<String>> exercitados = Map.of(
                "post /api/v1/o/{org}/workspaces", Set.of("201", "403"),
                "post /api/v1/o/{org}/workspaces/{workspaceId}/automations", Set.of("201", "404", "409"),
                "post /api/v1/o/{org}/automations/{automationId}/versions", Set.of("201", "409"),
                "get /api/v1/o/{org}/automation-versions/{version}", Set.of("200", "401", "404"),
                "patch /api/v1/o/{org}/automation-versions/{version}", Set.of("409"),
                "put /api/v1/o/{org}/automation-versions/{version}/artifact", Set.of("200", "404", "409", "422"),
                "get /api/v1/o/{org}/automation-versions/{version}/artifact/content", Set.of("200"),
                "post /api/v1/o/{org}/automation-versions/{version}/publish", Set.of("200", "403", "404", "409"));
        for (Map.Entry<String, Set<String>> e : exercitados.entrySet()) {
            String[] mp = e.getKey().split(" ", 2);
            Map<String, Object> o = (Map<String, Object>) ((Map<String, Object>) paths.get(mp[1])).get(mp[0]);
            assertThat(o).as(e.getKey()).isNotNull();
            assertThat(((Map<String, Object>) o.get("responses")).keySet()).as(e.getKey()).containsAll(e.getValue()).contains("429", "503");
            String security = o.get("security").toString();
            assertThat(security).as(e.getKey()).contains("sessionCookie");
            if (!mp[0].equals("get")) {
                assertThat(security).as(e.getKey() + ": escrita com CSRF").contains("csrfHeader");
            }
        }
        Map<String, Object> auth = (Map<String, Object>) ((Map<String, Object>) paths.get("/api/v1/o/{org}/workspaces/{workspace}/local-run-authorizations"))
                .get("post");
        assertThat(String.valueOf(auth.get("description"))).contains("command_json.agent_package.fingerprint")
                .doesNotContain("packageFingerprint = command_json.artifact.sha256");
    }
}
