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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.yaml.snakeyaml.Yaml;

import com.nekkora.platform.web.AccessAdminController;
import com.nekkora.platform.web.InvitationAcceptController;

/**
 * Contrato publicado das rotas de acesso (023): as rotas mapeadas por {@link AccessAdminController} e
 * {@link InvitationAcceptController} (metodo + caminho) sao EXATAMENTE as publicadas no OpenAPI, com os codigos que os
 * testes HTTP exercitam e a seguranca do contexto; toda referencia {@code #/components/...} do arquivo resolve.
 */
class AccessContractTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> spec() throws IOException {
        try (Reader r = Files.newBufferedReader(Path.of("../openapi/nekkora-api-v1.yaml"))) {
            return new Yaml().load(r);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotasDeAcessoSaoAsPublicadasComOsCodigosEASeguranca() throws IOException {
        Set<String> mapeadas = new TreeSet<>();
        for (Class<?> c : new Class<?>[] { AccessAdminController.class, InvitationAcceptController.class }) {
            for (Method m : c.getDeclaredMethods()) {
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
        }
        Map<String, Object> paths = (Map<String, Object>) spec().get("paths");
        Set<String> publicadas = new TreeSet<>();
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            boolean acesso = e.getKey().equals(InvitationAcceptController.ACCEPT) || e.getKey().matches("/api/v1/o/\\{org}/(invitations|role-assignments).*")
                    || e.getKey().matches("/api/v1/o/\\{org}/users/\\{user}/(roles|membership/revoke)")
                    || e.getKey().matches("/api/v1/o/\\{org}/client-grants(/\\{grant}/(approve|renew|renew/approve|review))?");
            if (!acesso) {
                continue;
            }
            for (String method : ((Map<String, Object>) e.getValue()).keySet()) {
                if (!(e.getKey().equals("/api/v1/o/{org}/client-grants") && method.equals("get"))) {   // a lista e da GAP-03
                    publicadas.add(method + " " + e.getKey());
                }
            }
        }
        assertThat(mapeadas).as("rotas dos controllers = rotas publicadas").isEqualTo(publicadas).hasSize(15);

        Map<String, Set<String>> exercitados = Map.ofEntries(
                Map.entry("post /api/v1/o/{org}/invitations", Set.of("201", "400", "403", "404", "409")),
                Map.entry("post /api/v1/o/{org}/invitations/{invitation}/resend", Set.of("201", "409")),
                Map.entry("post /api/v1/o/{org}/invitations/{invitation}/revoke", Set.of("204", "404", "409")),
                Map.entry("post /api/v1/invitations/accept", Set.of("200", "404", "409")),
                Map.entry("post /api/v1/o/{org}/users/{user}/roles", Set.of("200", "403", "404")),
                Map.entry("post /api/v1/o/{org}/role-assignments/{assignment}/replace", Set.of("200", "400")),
                Map.entry("post /api/v1/o/{org}/users/{user}/membership/revoke", Set.of("204", "403")),
                Map.entry("post /api/v1/o/{org}/client-grants", Set.of("201", "400", "404", "409")),
                Map.entry("post /api/v1/o/{org}/client-grants/{grant}/approve", Set.of("200", "403", "409")),
                Map.entry("post /api/v1/o/{org}/client-grants/{grant}/renew", Set.of("202", "400", "409")),
                Map.entry("post /api/v1/o/{org}/client-grants/{grant}/renew/approve", Set.of("200", "403", "409")),
                Map.entry("post /api/v1/o/{org}/client-grants/{grant}/review", Set.of("200", "409")));
        for (Map.Entry<String, Set<String>> e : exercitados.entrySet()) {
            String[] mp = e.getKey().split(" ", 2);
            Map<String, Object> o = (Map<String, Object>) ((Map<String, Object>) paths.get(mp[1])).get(mp[0]);
            assertThat(((Map<String, Object>) o.get("responses")).keySet()).as(e.getKey()).containsAll(e.getValue()).contains("429", "503");
            String security = o.get("security").toString();
            if (mp[1].equals(InvitationAcceptController.ACCEPT)) {
                assertThat(security).as("aceite: sessao de identidade e CSRF").contains("identitySessionCookie").contains("csrfHeader");
            } else {
                assertThat(security).as(e.getKey() + ": sessao de organizacao e CSRF").contains("sessionCookie").contains("csrfHeader");
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void todaReferenciaDoArquivoResolveEOsCodigosDeErroNovosEstaoNoEsquema() throws IOException {
        Map<String, Object> spec = spec();
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        String texto = Files.readString(Path.of("../openapi/nekkora-api-v1.yaml"));
        Matcher m = Pattern.compile("#/components/(\\w+)/(\\w+)").matcher(texto);
        Set<String> faltando = new TreeSet<>();
        while (m.find()) {
            Map<String, Object> grupo = (Map<String, Object>) components.get(m.group(1));
            if (grupo == null || !grupo.containsKey(m.group(2))) {
                faltando.add(m.group());
            }
        }
        assertThat(faltando).as("referencias sem destino").isEmpty();
        Map<String, Object> error = (Map<String, Object>) ((Map<String, Object>) components.get("schemas")).get("Error");
        String codigos = error.get("properties").toString();
        for (com.nekkora.core.web.ApiError e : com.nekkora.core.web.ApiError.values()) {
            assertThat(codigos).as("codigo de erro publicado: " + e.code()).contains(e.code());
        }
    }
}
