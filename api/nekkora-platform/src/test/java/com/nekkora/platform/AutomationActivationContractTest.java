package com.nekkora.platform;

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

import com.nekkora.core.web.ApiError;
import com.nekkora.platform.web.AutomationActivationController;

/**
 * PLATFORM-FLUXO-002 - as rotas da ativacao unificada mapeadas pelo controller sao EXATAMENTE as publicadas, com a seguranca
 * do contexto (leituras: sessao; atos: sessao + CSRF) e os codigos que os testes HTTP exercitam; os motivos novos existem.
 */
class AutomationActivationContractTest {

    @SuppressWarnings("unchecked")
    @Test
    void rotasDaAtivacaoSaoAsPublicadasComASegurancaEOsCodigos() throws IOException {
        Map<String, Object> spec;
        try (Reader r = Files.newBufferedReader(Path.of("../openapi/nekkora-api-v1.yaml"))) {
            spec = new Yaml().load(r);
        }
        Set<String> mapeadas = new TreeSet<>();
        for (Method m : AutomationActivationController.class.getDeclaredMethods()) {
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
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            if (e.getKey().startsWith("/api/v1/o/{org}/automation-activations")) {
                for (String method : ((Map<String, Object>) e.getValue()).keySet()) {
                    publicadas.add(method + " " + e.getKey());
                }
            }
        }
        assertThat(mapeadas).as("rotas do controller = rotas publicadas").isEqualTo(publicadas).hasSize(6);
        for (String rota : publicadas) {
            String[] mp = rota.split(" ", 2);
            Map<String, Object> o = (Map<String, Object>) ((Map<String, Object>) paths.get(mp[1])).get(mp[0]);
            Set<String> codes = ((Map<String, Object>) o.get("responses")).keySet();
            assertThat(codes).as(rota).contains("200", "401", "403", "429", "503");
            String security = o.get("security").toString();
            if (mp[0].equals("post")) {
                assertThat(security).as(rota + ": sessao e CSRF").contains("sessionCookie").contains("csrfHeader");
                assertThat(codes).as(rota).contains("409");
            } else {
                assertThat(security).as(rota + ": sessao").contains("sessionCookie").doesNotContain("csrfHeader");
            }
        }
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        assertThat(schemas).containsKeys("AutomationActivation", "AutomationActivationList", "AutomationActivationOptions", "AutomationActivationRequest",
                "AutomationActivationOutcome", "LicenseCount");
        assertThat(ApiError.LICENSE_UNAVAILABLE.reason()).isEqualTo("license_unavailable");
        assertThat(ApiError.ACTIVATION_WORKER_NOT_IN_WORKSPACE.reason()).isEqualTo("activation_worker_not_in_workspace");
        assertThat(ApiError.ACTIVATION_RENEWAL_NOT_APPLICABLE.reason()).isEqualTo("activation_renewal_not_applicable");
    }
}
