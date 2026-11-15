package com.nekkora.platform.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c - a leitura estatica de dependencias na API da exatamente as situacoes dos
 * vetores comuns ({@code requirements-vectors.json}, copia identica no Nekkora-Agent-Client, onde o mesmo arquivo prende o
 * {@code RequirementsFile}). E o que garante que a Platform e o aplicativo leem um requirements.txt do mesmo jeito.
 */
class RequirementsTextTest {

    @Test
    void asSituacoesDeCadaLinhaSaoAsDosVetoresComuns() throws IOException {
        JsonNode vectors;
        try (InputStream in = getClass().getResourceAsStream("/requirements-vectors.json")) {
            vectors = JsonMapper.builder().build().readTree(in).get("vectors");
        }
        assertThat(vectors.size()).isGreaterThanOrEqualTo(10);
        for (JsonNode v : vectors) {
            String name = v.get("name").asString();
            RequirementsText.Parsed p = RequirementsText.parse(v.get("text").asString());
            List<String> got = new ArrayList<>();
            for (RequirementsText.Line l : p.lines()) {
                got.add(l.number() + " " + l.kind() + " " + l.dependency());
            }
            List<String> want = new ArrayList<>();
            for (JsonNode l : v.get("lines")) {
                want.add(l.get("number").asInt() + " " + l.get("kind").asString() + " " + (l.get("dependency").isNull() ? null : l.get("dependency").asString()));
            }
            assertThat(got).as(name).isEqualTo(want);
            List<String> deps = new ArrayList<>();
            v.get("dependencies").forEach(d -> deps.add(d.asString()));
            assertThat(p.dependencies()).as(name).isEqualTo(deps);
        }
    }

    @Test
    void oTextoMostradoESaneadoELimitado() {
        RequirementsText.Line l = RequirementsText.parse("pkg‮==1.0 " + "x".repeat(400)).lines().get(0);
        assertThat(l.text()).doesNotContain("‮").hasSizeLessThanOrEqualTo(120);
        assertThat(RequirementsText.parse(null).lines()).isEmpty();
        assertThat(RequirementsText.parse("requests==2.32.3\nnumpy\n").resolved()).isFalse();
        assertThat(RequirementsText.parse("requests==2.32.3\n").resolved()).isTrue();
        assertThat(RequirementsText.sameness("Pandas[performance,Excel]==2.2.2")).isEqualTo("pandas[excel,performance]==2.2.2");
        assertThat(RequirementsText.key("My_Pkg.x[a]==1")).isEqualTo("my-pkg-x");
    }
}
