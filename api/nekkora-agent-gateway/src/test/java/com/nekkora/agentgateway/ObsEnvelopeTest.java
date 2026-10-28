package com.nekkora.agentgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validacao do envelope OBS 1.0.0. Os casos positivos sao os registros REAIS do Agent empacotado (cd04a7c4, missao
 * STUDIO-AGENT-COMPAT-001, 28/10): sucesso (294 envelopes), cancelamento (420) e queda do Agent (668). As recusas usam
 * sentinelas de dado sensivel e as regras do Agent (obs/ActivityEvent).
 */
class ObsEnvelopeTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String EX = "ex-6f670400dd5d9a00d9569fdec8d2c4f4";

    static List<String> real(String name) throws IOException {
        try (InputStream in = ObsEnvelopeTest.class.getResourceAsStream("/agent-real/" + name + "-atividade.jsonl")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a1-sucesso", "a2-cancelamento", "a5-agent-derrubado"})
    void registrosReaisDoAgentSaoValidosESemCamposIgnorados(String name) throws IOException {
        List<String> lines = real(name);
        String id = JSON.readTree(lines.get(0)).get("agent_execution_id").asString();
        long seq = 0;
        for (String l : lines) {
            ObsEnvelope.Result r = ObsEnvelope.validate(JSON.readTree(l), id);
            assertNull(r.invalid(), () -> name + ": " + r.invalid());
            assertEquals(++seq, r.valid().sequence());
            assertEquals(0, r.valid().ignoredFields());
            // a forma canonica e o proprio conteudo (mesmos campos e valores), so com as chaves ordenadas
            assertEquals(JSON.readTree(l), JSON.readTree(r.valid().canonical()));
        }
        assertTrue(seq > 200);
    }

    @Test
    void canonicaIndependeDaOrdemDasChaves() throws IOException {
        String l = real("a1-sucesso").get(120);
        ObjectNode original = (ObjectNode) JSON.readTree(l);
        ObjectNode reversed = JSON.createObjectNode();
        List<String> keys = new java.util.ArrayList<>(original.propertyNames());
        java.util.Collections.reverse(keys);
        keys.forEach(k -> reversed.set(k, original.get(k)));
        assertEquals(ObsEnvelope.validate(original, EX).valid().sha256(), ObsEnvelope.validate(reversed, EX).valid().sha256());
    }

    @Test
    void projecoesDoEventoDoFramework() throws IOException {
        ObsEnvelope.Valid v = ObsEnvelope.validate(JSON.readTree(real("a1-sucesso").get(120)), EX).valid();
        assertEquals(2, v.source());
        assertEquals("TRANSACTION_COMPLETED", v.eventCode());
        assertEquals("ex-d1dd0f7020ff40aeb12b", v.frameworkExecutionId());
        assertNotNull(v.timestampUtc());
    }

    static ObjectNode base() {
        ObjectNode n = JSON.createObjectNode();
        n.put("schema", "nekkora-agent/observability");
        n.put("version", "1.0.0");
        n.put("agent_execution_id", EX);
        n.put("sequence", 7);
        n.put("observed_utc", "2026-10-28T12:35:06.006315900Z");
        n.put("source", "FRAMEWORK");
        n.put("event_code", "ACTION_COMPLETED");
        n.put("severity", "INFO");
        n.put("category", "ACTIVITY");
        return n;
    }

    static String reason(JsonNode n) {
        ObsEnvelope.Result r = ObsEnvelope.validate(n, EX);
        return r.invalid() == null ? null : r.invalid().reason();
    }

    @Test
    void controleValido() {
        assertNull(reason(base()));
    }

    @Test
    void recusas() {
        assertEquals("unknown_field", reason(base().put("message", "texto livre do cliente")));
        assertEquals("unknown_field", reason(base().put("detail", "x")));
        assertEquals("version_unsupported", reason(base().put("version", "2.0.0")));
        assertEquals("schema", reason(base().put("schema", "outra/coisa")));
        assertEquals("execution_mismatch", reason(base().put("agent_execution_id", "ex-00000000000000000000000000000000")));
        assertEquals("sequence", reason(base().put("sequence", 0)));
        assertEquals("sequence", reason(base().put("sequence", 1_000_001)));
        assertEquals("sequence", reason(base().put("sequence", "7")));
        assertEquals("observed_utc", reason(base().put("observed_utc", "2026-10-28T12:35:06+03:00")));
        assertEquals("source", reason(base().put("source", "PLATFORM")));
        assertEquals("event_code", reason(base().put("event_code", "acao concluida")));
        // sentinelas de dado sensivel: documento, e-mail, caminho e numero longo nunca formam identificador aceito
        assertEquals("status", reason(base().put("status", "CPF_12345678900")));
        assertEquals("step_id", reason(base().put("step_id", "cliente.pedido_98765")));
        assertEquals("step_id", reason(base().put("step_id", "fulano@exemplo.com")));
        assertEquals("step_id", reason(base().put("step_id", "C:\\Users\\fulano")));
        assertEquals("error_code", reason(base().put("error_code", "ERRO 4111111111111111")));
        assertEquals("transaction_ref", reason(base().put("transaction_ref", "pedido-123")));
        ObjectNode counts = base();
        ObjectNode c = counts.putObject("counts");
        for (int i = 0; i < 17; i++) {
            c.put("k" + (char) ('a' + i), i);
        }
        assertEquals("counts", reason(counts));
        ObjectNode nested = base();
        nested.putObject("counts").putObject("itens").put("a", 1);
        assertEquals("counts", reason(nested));
        ObjectNode corr = base();
        corr.putObject("correlation").put("framework_execution_id", "outro-formato");
        assertEquals("framework_execution_id", reason(corr));
    }

    @Test
    void versaoMenorMaiorAceitaDescartandoCamposNovos() {
        ObsEnvelope.Result r = ObsEnvelope.validate(base().put("version", "1.1.0").put("campo_futuro", "x"), EX);
        assertNull(r.invalid());
        assertEquals(1, r.valid().ignoredFields());
        assertTrue(!r.valid().canonical().contains("campo_futuro"));
    }
}
