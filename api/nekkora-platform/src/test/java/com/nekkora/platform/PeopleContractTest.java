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

import com.nekkora.platform.web.PeopleController;

/**
 * PLATFORM-FLUXO-002 - a rota de "Pessoas e acessos" mapeada pelo controller e EXATAMENTE a publicada, so leituras
 * (sessao, sem CSRF), com os codigos de resposta.
 */
class PeopleContractTest {

    @SuppressWarnings("unchecked")
    @Test
    void rotaDePessoasEAPublicada() throws IOException {
        Map<String, Object> spec;
        try (Reader r = Files.newBufferedReader(Path.of("../openapi/nekkora-api-v1.yaml"))) {
            spec = new Yaml().load(r);
        }
        Set<String> mapeadas = new TreeSet<>();
        for (Method m : PeopleController.class.getDeclaredMethods()) {
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
            if (e.getKey().startsWith("/api/v1/o/{org}/people")) {
                for (String method : ((Map<String, Object>) e.getValue()).keySet()) {
                    publicadas.add(method + " " + e.getKey());
                }
            }
        }
        assertThat(mapeadas).as("rotas do controller = rotas publicadas").isEqualTo(publicadas).hasSize(1);
        for (String rota : publicadas) {
            String[] mp = rota.split(" ", 2);
            Map<String, Object> o = (Map<String, Object>) ((Map<String, Object>) paths.get(mp[1])).get(mp[0]);
            // as mesmas respostas da lista de membros que ela substitui na tela (GET, so leitura)
            assertThat(mp[0]).isEqualTo("get");
            assertThat(((Map<String, Object>) o.get("responses")).keySet()).as(rota).contains("200", "400", "401", "403", "429");
        }
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
        assertThat(schemas).containsKeys("PeoplePage", "PersonItem", "PersonMember", "PersonInvitation", "PersonSituation");
    }
}
