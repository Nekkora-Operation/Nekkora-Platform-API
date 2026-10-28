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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.yaml.snakeyaml.Yaml;

import com.nekkora.platform.web.AgentController;

/**
 * Contrato publicado do lado das pessoas do canal do Agent (025): as rotas de {@link AgentController} (metodo + caminho) sao
 * EXATAMENTE as da tag "Canal do Agent" do OpenAPI, com os codigos que o AgentAdminIT exercita, a seguranca do contexto
 * (escrita com CSRF) e toda referencia {@code #/components/...} resolvendo.
 */
class AgentContractTest {

    @SuppressWarnings("unchecked")
    static Map<String, Object> spec() throws IOException {
        try (Reader r = Files.newBufferedReader(Path.of("../openapi/nekkora-api-v1.yaml"))) {
            return new Yaml().load(r);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rotasDoCanalSaoAsPublicadasComOsCodigosEASeguranca() throws IOException {
        Set<String> mapeadas = new TreeSet<>();
        for (Method m : AgentController.class.getDeclaredMethods()) {
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
            if (m.getAnnotation(PutMapping.class) != null) {
                for (String p : m.getAnnotation(PutMapping.class).value()) {
                    mapeadas.add("put " + p);
                }
            }
            if (m.getAnnotation(DeleteMapping.class) != null) {
                for (String p : m.getAnnotation(DeleteMapping.class).value()) {
                    mapeadas.add("delete " + p);
                }
            }
        }
        Map<String, Object> paths = (Map<String, Object>) spec().get("paths");
        Set<String> publicadas = new TreeSet<>();
        for (Map.Entry<String, Object> e : paths.entrySet()) {
            for (Map.Entry<String, Object> op : ((Map<String, Object>) e.getValue()).entrySet()) {
                Object tags = ((Map<String, Object>) op.getValue()).get("tags");
                if (tags != null && tags.toString().contains("Canal do Agent")) {
                    publicadas.add(op.getKey() + " " + e.getKey());
                }
            }
        }
        // o controlador usa nomes de variavel curtos ({agent}, {run}); o OpenAPI, os mesmos
        assertThat(mapeadas).as("rotas do controller = rotas publicadas").isEqualTo(publicadas).hasSize(14);

        Map<String, Set<String>> exercitados = Map.ofEntries(
                Map.entry("post /api/v1/o/{org}/agent-enrollments", Set.of("201", "400", "403", "409")),
                Map.entry("post /api/v1/o/{org}/agent-enrollments/{enrollment}/cancel", Set.of("204", "409")),
                Map.entry("post /api/v1/o/{org}/agents/{agent}/revoke", Set.of("204", "400", "409")),
                Map.entry("post /api/v1/o/{org}/agents/{agent}/credential-rotations", Set.of("201")),
                Map.entry("get /api/v1/o/{org}/agents", Set.of("200", "403")),
                Map.entry("put /api/v1/o/{org}/workers/{worker}/workspaces/{workspace}", Set.of("201")),
                Map.entry("post /api/v1/o/{org}/workspaces/{workspace}/local-run-authorizations", Set.of("201", "409")),
                Map.entry("post /api/v1/o/{org}/workspaces/{workspace}/local-run-authorizations/{authorization}/revoke", Set.of("204")),
                Map.entry("get /api/v1/o/{org}/agent-runs", Set.of("200")),
                Map.entry("get /api/v1/o/{org}/agent-runs/{run}", Set.of("200", "404")),
                Map.entry("get /api/v1/o/{org}/agent-runs/{run}/events", Set.of("200", "404")));
        for (Map.Entry<String, Set<String>> e : exercitados.entrySet()) {
            String[] mp = e.getKey().split(" ", 2);
            Map<String, Object> o = (Map<String, Object>) ((Map<String, Object>) paths.get(mp[1])).get(mp[0]);
            assertThat(o).as(e.getKey()).isNotNull();
            assertThat(((Map<String, Object>) o.get("responses")).keySet()).as(e.getKey()).containsAll(e.getValue()).contains("429", "503");
            String security = o.get("security").toString();
            assertThat(security).as(e.getKey() + ": sessao da organizacao").contains("sessionCookie");
            if (!mp[0].equals("get")) {
                assertThat(security).as(e.getKey() + ": escrita com CSRF").contains("csrfHeader");
            }
        }
    }

    @Test
    void referenciasResolvemNosDoisArquivos() throws IOException {
        for (String file : new String[] {"../openapi/nekkora-api-v1.yaml", "../openapi/nekkora-agent-v1.yaml"}) {
            String text = Files.readString(Path.of(file));
            Map<String, Object> s = new Yaml().load(text);
            Matcher m = Pattern.compile("\\$ref: '#/components/([A-Za-z]+)/([A-Za-z0-9_]+)'").matcher(text);
            while (m.find()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> group = (Map<String, Object>) ((Map<String, Object>) s.get("components")).get(m.group(1));
                assertThat(group).as(file + " " + m.group()).isNotNull();
                assertThat(group).as(file + " " + m.group()).containsKey(m.group(2));
            }
        }
    }
}
