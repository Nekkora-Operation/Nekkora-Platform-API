package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.yaml.snakeyaml.Yaml;

import com.nekkora.platform.web.IdentityAuthController;
import com.nekkora.platform.web.IdentityOrganizationsController;
import com.nekkora.platform.web.SignupController;

/**
 * Contrato HTTP do cadastro web: as rotas mapeadas em {@link SignupController} sao exatamente as tres publicadas no
 * OpenAPI ({@code api/openapi/nekkora-api-v1.yaml}), todas POST, com os codigos que {@code SignupIT} e
 * {@code SignupDisabledIT} exercitam documentados.
 */
class SignupContractTest {

    @Test
    @SuppressWarnings("unchecked")
    void rotasDoControllerSaoAsPublicadasComOsCodigosExercitados() throws IOException {
        Set<String> mapeadas = new TreeSet<>();
        for (Method m : SignupController.class.getDeclaredMethods()) {
            PostMapping p = m.getAnnotation(PostMapping.class);
            if (p != null) {
                mapeadas.addAll(Arrays.asList(p.value()));
            }
        }
        Map<String, Object> spec;
        try (Reader r = Files.newBufferedReader(Path.of("../openapi/nekkora-api-v1.yaml"))) {
            spec = new Yaml().load(r);
        }
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Set<String> publicadas = new TreeSet<>();
        for (String k : paths.keySet()) {
            if (k.startsWith("/api/v1/signup")) {
                publicadas.add(k);
            }
        }
        assertThat(mapeadas).as("rotas do controller = rotas publicadas").isEqualTo(publicadas)
                .containsExactlyInAnyOrder(SignupController.SIGNUP, SignupController.CONFIRM, SignupController.RESEND);
        Map<String, Set<String>> exercitados = Map.of(
                SignupController.SIGNUP, Set.of("202", "400", "403", "404"),
                SignupController.CONFIRM, Set.of("204", "400", "403", "404"),
                SignupController.RESEND, Set.of("202", "403", "404"));
        for (Map.Entry<String, Set<String>> e : exercitados.entrySet()) {
            Map<String, Object> item = (Map<String, Object>) paths.get(e.getKey());
            assertThat(item.keySet()).as(e.getKey() + ": so POST").containsExactly("post");
            Map<String, Object> post = (Map<String, Object>) item.get("post");
            Map<String, Object> respostas = (Map<String, Object>) post.get("responses");
            assertThat(respostas.keySet()).as(e.getKey() + ": codigos documentados").containsAll(e.getValue()).contains("429", "503");
            assertThat(post.get("security").toString()).as(e.getKey() + ": CSRF de pre-autenticacao").contains("preAuthCookie").contains("csrfHeader");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotasDaEntradaGeralSaoAsPublicadasComOsCodigosExercitados() throws IOException {
        Set<String> mapeadas = new TreeSet<>();
        for (Method m : IdentityAuthController.class.getDeclaredMethods()) {
            PostMapping p = m.getAnnotation(PostMapping.class);
            if (p != null) {
                mapeadas.addAll(Arrays.asList(p.value()));
            }
        }
        Map<String, Object> spec;
        try (Reader r = Files.newBufferedReader(Path.of("../openapi/nekkora-api-v1.yaml"))) {
            spec = new Yaml().load(r);
        }
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Set<String> publicadas = new TreeSet<>();
        for (String k : paths.keySet()) {
            if (k.startsWith("/api/v1/auth/")) {
                publicadas.add(k);
            }
        }
        assertThat(mapeadas).as("rotas do controller = rotas publicadas").isEqualTo(publicadas);
        Map<String, List<String>> exercitados = Map.of(
                IdentityAuthController.LOGIN, List.of("200", "401", "403", "404", "429", "preAuthCookie"),
                // dois modos (missao 002): desafio de login ou sessao de identidade; os dois cookies, 400
                IdentityAuthController.ENROLL_START, List.of("200", "400", "401", "403", "404", "identityChallengeCookie", "identitySessionCookie", "csrfHeader"),
                IdentityAuthController.ENROLL_CONFIRM, List.of("200", "400", "401", "403", "404", "identityChallengeCookie", "identitySessionCookie", "csrfHeader"),
                IdentityAuthController.VERIFY, List.of("200", "400", "401", "403", "404", "identityChallengeCookie"),
                IdentityAuthController.LOGOUT, List.of("204", "403", "identitySessionCookie"),
                IdentityAuthController.STEP_UP, List.of("204", "400", "401", "403", "404", "429", "503", "identitySessionCookie", "csrfHeader"));
        for (Map.Entry<String, List<String>> e : exercitados.entrySet()) {
            Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) paths.get(e.getKey())).get("post");
            Map<String, Object> respostas = (Map<String, Object>) post.get("responses");
            for (String x : e.getValue()) {
                if (x.matches("\\d{3}")) {
                    assertThat(respostas).as(e.getKey() + " documenta " + x).containsKey(x);
                } else {
                    assertThat(post.get("security").toString()).as(e.getKey() + " declara " + x).contains(x);
                }
            }
        }
        Set<String> gets = new TreeSet<>();
        for (Method m : IdentityAuthController.class.getDeclaredMethods()) {
            GetMapping g = m.getAnnotation(GetMapping.class);
            if (g != null) {
                gets.addAll(Arrays.asList(g.value()));
            }
        }
        assertThat(gets).as("unico GET do controller: estado da sessao").containsExactly(IdentityAuthController.ME_SESSION);
        Map<String, Object> estado = (Map<String, Object>) paths.get(IdentityAuthController.ME_SESSION);
        assertThat(estado.keySet()).as("me/session: so GET").containsExactly("get");
        Map<String, Object> get = (Map<String, Object>) estado.get("get");
        assertThat((Map<String, Object>) get.get("responses")).containsKeys("200", "401", "404");
        assertThat(get.get("security").toString()).contains("identitySessionCookie");
        Map<String, Object> logout = (Map<String, Object>) ((Map<String, Object>) paths.get(IdentityAuthController.LOGOUT)).get("post");
        assertThat((Map<String, Object>) logout.get("responses")).as("logout de identidade nao tem chave: sem 404").doesNotContainKey("404");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotasDaVinculacaoSaoAsPublicadasComOsCodigosExercitados() throws IOException {
        java.util.Set<String> mapeadas = new java.util.TreeSet<>();
        for (Method m : com.nekkora.platform.web.AccountLinkController.class.getDeclaredMethods()) {
            PostMapping p = m.getAnnotation(PostMapping.class);
            if (p != null) {
                mapeadas.addAll(Arrays.asList(p.value()));
            }
            assertThat(m.getAnnotation(GetMapping.class)).as(m.getName() + ": so POST").isNull();
        }
        assertThat(mapeadas).containsExactlyInAnyOrder(com.nekkora.platform.web.AccountLinkController.START, com.nekkora.platform.web.AccountLinkController.CONFIRM);
        Map<String, Object> spec;
        try (Reader r = Files.newBufferedReader(Path.of("../openapi/nekkora-api-v1.yaml"))) {
            spec = new Yaml().load(r);
        }
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, List<String>> exercitados = Map.of(
                com.nekkora.platform.web.AccountLinkController.START, List.of("204", "400", "401", "403", "404", "429", "503", "identitySessionCookie", "csrfHeader"),
                com.nekkora.platform.web.AccountLinkController.CONFIRM, List.of("204", "400", "401", "403", "404", "503", "identitySessionCookie", "linkChallengeCookie", "csrfHeader"));
        for (Map.Entry<String, List<String>> e : exercitados.entrySet()) {
            Map<String, Object> item = (Map<String, Object>) paths.get(e.getKey());
            assertThat(item).as(e.getKey() + " publicada").isNotNull();
            assertThat(item.keySet()).as(e.getKey() + ": metodo").containsExactly("post");
            Map<String, Object> op = (Map<String, Object>) item.get("post");
            for (String x : e.getValue()) {
                if (x.matches("\\d{3}")) {
                    assertThat((Map<String, Object>) op.get("responses")).as(e.getKey() + " documenta " + x).containsKey(x);
                } else {
                    assertThat(op.get("security").toString()).as(e.getKey() + " declara " + x).contains(x);
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotasDasOrganizacoesDaIdentidadeSaoAsPublicadasComOsCodigosExercitados() throws IOException {
        Map<String, String> mapeadas = new java.util.TreeMap<>();
        for (Method m : IdentityOrganizationsController.class.getDeclaredMethods()) {
            GetMapping g = m.getAnnotation(GetMapping.class);
            PostMapping p = m.getAnnotation(PostMapping.class);
            if (g != null) {
                Arrays.stream(g.value()).forEach(v -> mapeadas.put(v, "get"));
            }
            if (p != null) {
                Arrays.stream(p.value()).forEach(v -> mapeadas.put(v, "post"));
            }
        }
        assertThat(mapeadas).containsExactlyInAnyOrderEntriesOf(Map.of(IdentityOrganizationsController.MY_ORGANIZATIONS, "get",
                IdentityOrganizationsController.ORG_SESSION, "post", IdentityOrganizationsController.ORGANIZATIONS, "post"));
        Map<String, Object> spec;
        try (Reader r = Files.newBufferedReader(Path.of("../openapi/nekkora-api-v1.yaml"))) {
            spec = new Yaml().load(r);
        }
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, List<String>> exercitados = Map.of(
                IdentityOrganizationsController.MY_ORGANIZATIONS, List.of("200", "401", "403", "404", "429", "503", "identitySessionCookie"),
                IdentityOrganizationsController.ORG_SESSION, List.of("200", "400", "401", "403", "404", "429", "503", "identitySessionCookie", "csrfHeader"),
                IdentityOrganizationsController.ORGANIZATIONS, List.of("201", "400", "401", "403", "404", "429", "503", "identitySessionCookie", "csrfHeader"));
        for (Map.Entry<String, List<String>> e : exercitados.entrySet()) {
            Map<String, Object> item = (Map<String, Object>) paths.get(e.getKey());
            assertThat(item).as(e.getKey() + " publicada").isNotNull();
            assertThat(item.keySet()).as(e.getKey() + ": metodo").containsExactly(mapeadas.get(e.getKey()));
            Map<String, Object> op = (Map<String, Object>) item.get(mapeadas.get(e.getKey()));
            for (String x : e.getValue()) {
                if (x.matches("\\d{3}")) {
                    assertThat((Map<String, Object>) op.get("responses")).as(e.getKey() + " documenta " + x).containsKey(x);
                } else {
                    assertThat(op.get("security").toString()).as(e.getKey() + " declara " + x).contains(x);
                }
            }
        }
    }
}
