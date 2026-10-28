package com.nekkora.agentgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.nekkora.agentgateway.SupervisorReport.Outcome;
import com.nekkora.agentgateway.SupervisorReport.State;
import com.nekkora.core.web.ApiException;

import tools.jackson.databind.json.JsonMapper;

/** Regras do relatorio do supervisor (contrato, sec. 7): as transicoes do Agent, atrasado, inalterado e conflito. */
class SupervisorReportTest {

    static SupervisorReport r(State s, String code, Integer exit) {
        return new SupervisorReport(s, code, exit, Map.of(), null, null, null, false, 0, 0);
    }

    @Test
    void transicoesDoAgent() {
        assertEquals(Outcome.APPLIED, SupervisorReport.decide(r(State.READY, null, null), r(State.STARTING, null, null)));
        assertEquals(Outcome.APPLIED, SupervisorReport.decide(r(State.STARTING, null, null), r(State.RUNNING, null, null)));
        assertEquals(Outcome.APPLIED, SupervisorReport.decide(r(State.RUNNING, null, null), r(State.SUCCEEDED, null, 0)));
        assertEquals(Outcome.APPLIED, SupervisorReport.decide(r(State.RUNNING, null, null), r(State.OUTCOME_UNKNOWN, "AGT-EXE-025", null)));
        assertEquals(Outcome.APPLIED, SupervisorReport.decide(r(State.STARTING, null, null), r(State.OUTCOME_UNKNOWN, "AGT-EXE-025", null)));
        assertEquals(Outcome.APPLIED, SupervisorReport.decide(r(State.READY, null, null), r(State.BLOCKED, "AGT-EXE-013", null)));
    }

    @Test
    void sucessoSoDepoisDeRunning() {
        assertEquals(Outcome.CONFLICT, SupervisorReport.decide(r(State.READY, null, null), r(State.SUCCEEDED, null, 0)));
        assertEquals(Outcome.CONFLICT, SupervisorReport.decide(r(State.STARTING, null, null), r(State.SUCCEEDED, null, 0)));
    }

    @Test
    void terminalNaoMudaENaoEContraditoEmSilencio() {
        SupervisorReport done = r(State.SUCCEEDED, null, 0);
        assertEquals(Outcome.UNCHANGED, SupervisorReport.decide(done, r(State.SUCCEEDED, null, 0)));
        assertEquals(Outcome.CONFLICT, SupervisorReport.decide(done, r(State.FAILED, "AGT-EXE-022", 1)));
        assertEquals(Outcome.CONFLICT, SupervisorReport.decide(done, r(State.SUCCEEDED, null, 1)));
        assertEquals(Outcome.CONFLICT, SupervisorReport.decide(r(State.OUTCOME_UNKNOWN, "AGT-EXE-025", null), r(State.SUCCEEDED, null, 0)));
    }

    @Test
    void relatorioAtrasadoEStale() {
        assertEquals(Outcome.STALE, SupervisorReport.decide(r(State.SUCCEEDED, null, 0), r(State.RUNNING, null, null)));
        assertEquals(Outcome.STALE, SupervisorReport.decide(r(State.RUNNING, null, null), r(State.STARTING, null, null)));
        assertEquals(Outcome.STALE, SupervisorReport.decide(r(State.CANCELLED, "AGT-EXE-024", 1), r(State.READY, null, null)));
    }

    @Test
    void mesmoEstadoNaoTerminalComDadosNovosEAplicado() {
        SupervisorReport running = r(State.RUNNING, null, null);
        SupervisorReport adopted = new SupervisorReport(State.RUNNING, null, null, Map.of(), null, Instant.parse("2026-10-28T12:00:00Z"), null, true, 0, 0);
        assertEquals(Outcome.APPLIED, SupervisorReport.decide(running, adopted));
    }

    @Test
    void instanteTruncadoEmMicrossegundosEOCodigoSoNoTerminal() {
        JsonMapper m = JsonMapper.builder().build();
        SupervisorReport p = SupervisorReport.parse(m.readTree("{\"state\":\"SUCCEEDED\",\"exitCode\":0,\"ended\":\"2026-10-28T12:35:14.265516789Z\"}"));
        assertEquals(Instant.parse("2026-10-28T12:35:14.265516Z"), p.ended());
        assertThrows(ApiException.class, () -> SupervisorReport.parse(m.readTree("{\"state\":\"RUNNING\",\"code\":\"AGT-EXE-024\"}")));
        assertThrows(ApiException.class, () -> SupervisorReport.parse(m.readTree("{\"state\":\"FEITO\"}")));
        assertThrows(ApiException.class, () -> SupervisorReport.parse(m.readTree("{\"state\":\"FAILED\",\"mensagem\":\"x\"}")));
    }
}
