package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.nekkora.platform.web.DesktopLoginController;
import com.nekkora.platform.web.DesktopNavigationController;

/**
 * Contrato publicado do login do aplicativo desktop (AGENT-CLIENT-LOGIN-001) e da navegacao dele (AGENT-CLIENT-NAVEGACAO-001):
 * as rotas de {@link DesktopLoginController} e de {@link DesktopNavigationController} sao EXATAMENTE as da tag "Login do
 * aplicativo desktop", com os codigos que o DesktopLoginIT e o DesktopNavigationIT exercitam e a credencial de cada grupo:
 * navegador (cookies e CSRF) ou aplicativo (nenhum cookie; codigo com PKCE, ou Bearer). O contrato nao publica metodo PKCE
 * "plain", outro cliente, escopo alem dos dois, refresh_token nem redirect fora do loopback.
 */
class DesktopLoginContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void rotasDoLoginDesktopSaoAsPublicadasComCodigosESeguranca() throws IOException {
        Set<String> mapeadas = new TreeSet<>();
        for (Class<?> controller : List.of(DesktopLoginController.class, DesktopNavigationController.class)) {
            for (Method m : controller.getDeclaredMethods()) {
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
            }
        }
        Map<String, Object> spec = AgentContractTest.spec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Set<String> publicadas = new TreeSet<>();
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            for (Map.Entry<String, Object> op : ((Map<String, Object>) e.getValue()).entrySet()) {
                Object tags = ((Map<String, Object>) op.getValue()).get("tags");
                if (tags != null && tags.toString().contains("Login do aplicativo desktop")) {
                    publicadas.add(op.getKey() + " " + e.getKey());
                }
            }
            if (e.getKey().startsWith("/api/v1/desktop/")) {
                assertThat(e.getValue().toString()).as(e.getKey() + ": toda rota /desktop/ e desta tag").contains("Login do aplicativo desktop");
            }
        }
        assertThat(mapeadas).as("rotas dos controllers = rotas publicadas").isEqualTo(publicadas).hasSize(14);

        Map<String, List<String>> exercitados = Map.of(
                "post " + DesktopLoginController.AUTHORIZATIONS, List.of("204", "400", "403", "404", "429", "503", "preAuthCookie", "csrfHeader"),
                "get " + DesktopLoginController.AUTHORIZATION, List.of("200", "401", "403", "404", "identitySessionCookie", "desktopRequestCookie"),
                "post " + DesktopLoginController.APPROVE, List.of("200", "401", "403", "404", "identitySessionCookie", "desktopRequestCookie", "csrfHeader"),
                "post " + DesktopLoginController.DENY, List.of("200", "403", "404", "preAuthCookie", "desktopRequestCookie", "csrfHeader"),
                "post " + DesktopLoginController.TOKEN, List.of("200", "400", "404", "429", "503"),
                "get " + DesktopLoginController.SESSION, List.of("200", "401", "404", "desktopBearer"),
                "post " + DesktopLoginController.REVOKE, List.of("204", "desktopBearer"));
        for (Map.Entry<String, List<String>> e : exercitados.entrySet()) {
            String[] mp = e.getKey().split(" ", 2);
            Map<String, Object> o = (Map<String, Object>) ((Map<String, Object>) paths.get(mp[1])).get(mp[0]);
            Map<String, Object> respostas = (Map<String, Object>) o.get("responses");
            for (String x : e.getValue()) {
                if (x.matches("\\d{3}")) {
                    assertThat(respostas).as(e.getKey() + " documenta " + x).containsKey(x);
                } else {
                    assertThat(o.get("security").toString()).as(e.getKey() + " declara " + x).contains(x);
                }
            }
        }

        // as rotas do APLICATIVO nao declaram cookie nem CSRF; a troca nao declara credencial nenhuma (codigo + PKCE no corpo)
        for (String rota : List.of(DesktopLoginController.TOKEN, DesktopLoginController.SESSION, DesktopLoginController.REVOKE)) {
            Map<String, Object> item = (Map<String, Object>) paths.get(rota);
            String sec = ((Map<String, Object>) item.values().iterator().next()).get("security").toString();
            assertThat(sec).as(rota + ": sem cookie e sem CSRF").doesNotContain("Cookie").doesNotContain("csrfHeader");
        }
        assertThat(((Map<String, Object>) ((Map<String, Object>) paths.get(DesktopLoginController.TOKEN)).get("post")).get("security").toString())
                .isEqualTo("[]");
        Map<String, Object> revoke = (Map<String, Object>) ((Map<String, Object>) paths.get(DesktopLoginController.REVOKE)).get("post");
        assertThat((Map<String, Object>) revoke.get("responses")).as("encerrar nao tem chave nem limitador: so 204").containsOnlyKeys("204");

        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        Map<String, Object> pedido = (Map<String, Object>) ((Map<String, Object>) schemas.get("DesktopAuthorizationRequest")).get("properties");
        assertThat(((Map<String, Object>) pedido.get("codeChallengeMethod")).get("enum")).as("PKCE: so S256").isEqualTo(List.of("S256"));
        assertThat(((Map<String, Object>) pedido.get("clientId")).get("enum")).isEqualTo(List.of("nekkora-agent-client"));
        assertThat(((Map<String, Object>) pedido.get("scope")).get("enum")).as("os dois escopos, texto exato").isEqualTo(List.of("identity", "identity navigation", "identity navigation packages"));
        assertThat(((Map<String, Object>) pedido.get("responseType")).get("enum")).isEqualTo(List.of("code"));
        Pattern redirect = Pattern.compile((String) ((Map<String, Object>) pedido.get("redirectUri")).get("pattern"));
        assertThat(redirect.matcher("http://127.0.0.1:49152/callback").matches()).isTrue();
        for (String ruim : List.of("http://localhost:49152/callback", "https://127.0.0.1:49152/callback", "http://127.0.0.1:49152/outro",
                "http://127a0b0c1:49152/callback", "http://example.com:49152/callback")) {
            assertThat(redirect.matcher(ruim).matches()).as(ruim).isFalse();
        }
        Map<String, Object> token = (Map<String, Object>) ((Map<String, Object>) schemas.get("DesktopToken")).get("properties");
        assertThat(token).as("sem refresh_token nem id_token").containsOnlyKeys("access_token", "token_type", "expires_in", "scope");
        Map<String, Object> sessao = (Map<String, Object>) ((Map<String, Object>) schemas.get("DesktopSession")).get("properties");
        assertThat(sessao).as("so a propria pessoa: nenhuma organizacao, Worker ou permissao").containsOnlyKeys("account", "scope", "expiresIn");
        for (String schema : List.of("DesktopToken", "DesktopSession", "DesktopAuthorizationPending")) {
            Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) schemas.get(schema)).get("properties");
            assertThat(((Map<String, Object>) props.get("scope")).get("enum")).as(schema + ".scope").isEqualTo(List.of("identity", "identity navigation", "identity navigation packages"));
        }
        Map<String, Object> pendente = (Map<String, Object>) schemas.get("DesktopAuthorizationPending");
        assertThat((List<String>) pendente.get("required")).as("a pagina de confirmacao recebe o escopo pedido").containsExactly("application", "account", "scope");

        // ---- navegacao (desktop-navigation/1): so Bearer; codigos exercitados pelo DesktopNavigationIT
        Map<String, List<String>> navegacao = Map.of(
                "get " + DesktopNavigationController.PACKAGE, List.of("200", "400", "401", "403", "404", "409", "429", "500", "503"),
                "get " + DesktopNavigationController.ME, List.of("200", "401", "403", "404", "429", "503"),
                "get " + DesktopNavigationController.ORGANIZATIONS, List.of("200", "401", "403", "404", "429", "503"),
                "get " + DesktopNavigationController.WORKSPACES, List.of("200", "400", "401", "403", "404", "429", "500", "503"),
                "get " + DesktopNavigationController.AUTOMATIONS, List.of("200", "400", "401", "403", "404", "429", "500", "503"),
                "get " + DesktopNavigationController.VERSIONS, List.of("200", "400", "401", "403", "404", "429", "500", "503"),
                "patch " + DesktopNavigationController.AUTOMATION, List.of("200", "400", "401", "403", "404", "409", "429", "500", "503"));
        for (Map.Entry<String, List<String>> e : navegacao.entrySet()) {
            String[] mp = e.getKey().split(" ", 2);
            Map<String, Object> o = (Map<String, Object>) ((Map<String, Object>) paths.get(mp[1])).get(mp[0]);
            assertThat((Map<String, Object>) o.get("responses")).as(e.getKey()).containsOnlyKeys(e.getValue().toArray(String[]::new));
            assertThat(o.get("security").toString()).as(e.getKey() + ": so o token do aplicativo, sem cookie e sem CSRF").isEqualTo("[{desktopBearer=[]}]");
            assertThat(o.get("description").toString()).as(e.getKey() + " declara o escopo exigido").contains("identity navigation");
        }
        Map<String, Object> schemaOf = Map.of(
                "DesktopPerson", List.of("email", "displayName"),
                "DesktopOrganization", List.of("organizationId", "name", "handle", "activationState"),
                "DesktopOrganizationList", List.of("items", "asOf"),
                "DesktopWorkspace", List.of("workspaceId", "name"),
                "DesktopWorkspacePage", List.of("items", "nextCursor"),
                "DesktopAutomation", List.of("automationId", "name", "latestPublishedVersion"),
                "DesktopAutomationPage", List.of("items", "nextCursor", "capabilities"),
                "DesktopAutomationVersion", List.of("automationVersionId", "versionNumber", "status", "publishedAt", "package"),
                "DesktopAutomationRenamed", List.of("automationId", "name"));
        for (Map.Entry<String, Object> e : schemaOf.entrySet()) {
            Map<String, Object> s = (Map<String, Object>) schemas.get(e.getKey());
            assertThat(s).as("schema " + e.getKey()).isNotNull();
            assertThat(((Map<String, Object>) s.get("properties")).keySet()).as(e.getKey() + ": campos publicados = campos da resposta")
                    .containsExactlyElementsOf((List<String>) e.getValue());
            assertThat((List<String>) s.get("required")).as(e.getKey() + ": todos presentes (nulo quando nao ha)").containsExactlyElementsOf((List<String>) e.getValue());
        }
        assertThat(((Map<String, Object>) ((Map<String, Object>) schemas.get("DesktopPackage")).get("properties")).keySet())
                .as("pacote: nome, versao, impressao e o que conferir depois de baixar").containsExactly("name", "version", "fingerprint", "sha256", "sizeBytes");
        assertThat(((Map<String, Object>) ((Map<String, Object>) schemas.get("DesktopCapabilities")).get("properties")).keySet()).containsExactly("rename", "execute", "download");
        assertThat(((Map<String, Object>) ((Map<String, Object>) schemas.get("DesktopAutomationRename")).get("properties")).keySet())
                .as("o aplicativo so envia o nome").containsExactly("name");
        assertThat(((Map<String, Object>) schemas.get("DesktopAutomationRename")).get("additionalProperties")).isEqualTo(Boolean.FALSE);
        Map<String, Object> bearer = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("securitySchemes"))
                .get("desktopBearer");
        assertThat(bearer.get("description").toString()).contains("identity navigation").contains("/api/v1/desktop/");
    }
}
