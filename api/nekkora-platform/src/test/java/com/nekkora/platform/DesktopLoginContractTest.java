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
import org.springframework.web.bind.annotation.PostMapping;

import com.nekkora.platform.web.DesktopLoginController;

/**
 * Contrato publicado do login do aplicativo desktop (AGENT-CLIENT-LOGIN-001): as rotas de {@link DesktopLoginController}
 * sao EXATAMENTE as da tag "Login do aplicativo desktop", com os codigos que o DesktopLoginIT exercita e a credencial de
 * cada grupo: navegador (cookies e CSRF) ou aplicativo (nenhum cookie; codigo com PKCE, ou Bearer). O contrato nao publica
 * metodo PKCE "plain", outro cliente, outro escopo, refresh_token nem redirect fora do loopback.
 */
class DesktopLoginContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void rotasDoLoginDesktopSaoAsPublicadasComCodigosESeguranca() throws IOException {
        Set<String> mapeadas = new TreeSet<>();
        for (Method m : DesktopLoginController.class.getDeclaredMethods()) {
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
                if (tags != null && tags.toString().contains("Login do aplicativo desktop")) {
                    publicadas.add(op.getKey() + " " + e.getKey());
                }
            }
            if (e.getKey().startsWith("/api/v1/desktop/")) {
                assertThat(e.getValue().toString()).as(e.getKey() + ": toda rota /desktop/ e desta tag").contains("Login do aplicativo desktop");
            }
        }
        assertThat(mapeadas).as("rotas do controller = rotas publicadas").isEqualTo(publicadas).hasSize(7);

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
        assertThat(((Map<String, Object>) pedido.get("scope")).get("enum")).isEqualTo(List.of("identity"));
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
    }
}
