package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.yaml.snakeyaml.Yaml;

import com.nekkora.platform.web.ClientAccessController;

/**
 * PLATFORM-FLUXO-002 (070/071): as rotas de "Clientes e acessos" mapeadas pelo controller sao EXATAMENTE as publicadas com a tag
 * propria; escritas com CSRF, leituras so com a sessao; os motivos novos de conflito publicados; a area do cliente publicada so com
 * GET e a sessao de identidade.
 */
class ClientAccessContractTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec() throws IOException {
        try (Reader r = Files.newBufferedReader(Path.of("../openapi/nekkora-api-v1.yaml"))) {
            return new Yaml().load(r);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> tagged(Map<String, Object> paths, String tag) {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            for (Map.Entry<String, Object> op : ((Map<String, Object>) e.getValue()).entrySet()) {
                Object tags = ((Map<String, Object>) op.getValue()).get("tags");
                if (tags instanceof List<?> l && l.contains(tag)) {
                    out.add(op.getKey() + " " + e.getKey());
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    @Test
    void rotasDeClientesEAcessosSaoAsPublicadas() throws IOException {
        Map<String, Object> spec = spec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Set<String> mapped = new TreeSet<>();
        for (Method m : ClientAccessController.class.getDeclaredMethods()) {
            if (m.getAnnotation(PostMapping.class) != null) {
                for (String p : m.getAnnotation(PostMapping.class).value()) {
                    mapped.add("post " + p);
                }
            }
            if (m.getAnnotation(GetMapping.class) != null) {
                for (String p : m.getAnnotation(GetMapping.class).value()) {
                    mapped.add("get " + p);
                }
            }
            if (m.getAnnotation(PatchMapping.class) != null) {
                for (String p : m.getAnnotation(PatchMapping.class).value()) {
                    mapped.add("patch " + p);
                }
            }
        }
        assertThat(mapped).as("controller = publicadas (tag Clientes e acessos)").isEqualTo(tagged(paths, "Clientes e acessos")).hasSize(19);
        for (String rota : mapped) {
            String[] mp = rota.split(" ", 2);
            Map<String, Object> o = (Map<String, Object>) ((Map<String, Object>) paths.get(mp[1])).get(mp[0]);
            String security = String.valueOf(o.get("security"));
            Set<String> responses = ((Map<String, Object>) o.get("responses")).keySet();
            assertThat(responses).as(rota).contains("401", "403", "429");
            if (mp[0].equals("get")) {
                assertThat(security).as(rota).contains("sessionCookie").doesNotContain("csrfHeader");
            } else {
                assertThat(security).as(rota).contains("sessionCookie", "csrfHeader");
            }
        }
        String reasons = String.valueOf(((Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas")).get("Error"));
        assertThat(reasons).contains("client_invitation_internal_member", "client_invitation_legacy_account", "client_invitation_other_client",
                "client_grant_exists", "client_scope_unavailable", "client_grant_widening_requires_request");
    }

    @SuppressWarnings("unchecked")
    @Test
    void areaDoClienteSoLeituraPelaSessaoDeIdentidade() throws IOException {
        Map<String, Object> paths = (Map<String, Object>) spec().get("paths");
        Set<String> portal = tagged(paths, "Area do cliente");
        assertThat(portal).containsExactlyInAnyOrder(
                "get /portal/api/v1/me/client-organizations",
                "get /portal/api/v1/o/{org}/shared-automations",
                "get /portal/api/v1/o/{org}/shared-automations/{automation}/summary",
                "get /portal/api/v1/o/{org}/shared-automations/{automation}/executions",
                "get /portal/api/v1/o/{org}/shared-files",
                "get /portal/api/v1/o/{org}/shared-files/{attempt}/{number}/content");
        // 071: nada de registros tecnicos nem detalhe de tentativa na area do cliente
        assertThat(portal).noneMatch(r -> r.contains("logs") || r.contains("attempts") || r.contains("shared-executions"));
        for (String rota : portal) {
            String[] mp = rota.split(" ", 2);
            Map<String, Object> o = (Map<String, Object>) ((Map<String, Object>) paths.get(mp[1])).get(mp[0]);
            assertThat(String.valueOf(o.get("security"))).as(rota).isEqualTo("[{identitySessionCookie=[]}]");
        }
    }
}
