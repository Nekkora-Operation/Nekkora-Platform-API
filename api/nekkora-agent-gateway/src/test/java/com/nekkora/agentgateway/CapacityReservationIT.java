package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nekkora.core.token.Tokens;

import tools.jackson.databind.JsonNode;

/**
 * PLATFORM-LICENCIAMENTO-001, D2 (migration 066) - a VAGA de execucao da organizacao do modelo por licenca, pelo canal REAL das
 * maquinas (HTTP, papel nekkora_ingestion). A vaga e reservada quando o servidor ENTREGA o trabalho a maquina; com limite de
 * cinco, a sexta entrega nao acontece e o trabalho continua pendente, mesmo com as seis maquinas perguntando ao mesmo tempo.
 * O limite e um so e e compartilhado pelos fluxos: tentativa remota, ocupacao incerta, pedido e sessao do Agent anterior.
 * (A execucao da Platform, a fila e a leitura pela pessoa sao conferidas no modulo da Platform: RemoteExecutionIT.)
 */
class CapacityReservationIT extends RemoteEnabledGatewayIT {

    static final String ENVIRONMENT = "E".repeat(43);
    static final String CAPABLE = "{\"agentVersion\":\"0.2.0+client\",\"contractVersion\":\"1.0\",\"capabilities\":[\"REMOTE_RUN_1_0\",\"PY_INVENTORY_1_0\"]}";

    private record Machine(MachineClient client, String agent, UUID worker, UUID authorization, String credential) {
    }

    /** Organizacao no modelo por licenca, com a automacao licenciada por 40 dias, dependencias declaradas e pacote registrado. */
    private AgentFixture licensed() {
        AgentFixture fx = new AgentFixture(DB);
        String o = "'" + fx.org + "'";
        UUID act = UUID.randomUUID();
        UUID license = UUID.randomUUID();
        String sha = "c".repeat(64);
        DB.asSuperuser("INSERT INTO automation_runtime_requirements (automation_id, revision_number, organization_id, workspace_id, source, dependencies, "
                        + "content_sha256, declared_by_membership_id) VALUES ('" + fx.automation + "', 1, " + o + ", '" + fx.workspace + "', 3, '[]'::jsonb, '"
                        + "a".repeat(64) + "', '" + fx.adminMembership + "')",
                "INSERT INTO organization_license_acts (act_id, organization_id, act, operator_user_id, justification) VALUES ('" + act + "', " + o + ", 6, '"
                        + fx.admin + "', 'modelo por licenca ligado na massa do teste')",
                "INSERT INTO organization_license_enforcement (organization_id, model, act_id) VALUES (" + o + ", 2, '" + act + "')",
                "INSERT INTO organization_licenses (license_id, organization_id, license_number) VALUES ('" + license + "', " + o + ", 1)",
                "INSERT INTO organization_license_periods (organization_id, license_id, period_number, kind, starts_at, ends_at) VALUES (" + o + ", '" + license
                        + "', 1, 1, clock_timestamp() - interval '1 day', clock_timestamp() + interval '40 days')",
                "INSERT INTO organization_license_assignments (organization_id, license_id, workspace_id, automation_id, assigned_by_membership_id) VALUES ("
                        + o + ", '" + license + "', '" + fx.workspace + "', '" + fx.automation + "', '" + fx.adminMembership + "')");
        untriggered("INSERT INTO automation_artifacts (organization_id, workspace_id, automation_id, automation_version_id, sha256, size_bytes, package_kind, "
                + "fingerprint_rule, fingerprint, files_count, manifest_version, framework, execution_mode, storage_key, received_by_membership_id) SELECT "
                + o + ", '" + fx.workspace + "', '" + fx.automation + "', '" + fx.version + "', '" + sha + "', 10, 1, "
                + "command_json -> 'agent_package' ->> 'fingerprint_rule', command_json -> 'agent_package' ->> 'fingerprint', 3, '1.4.0', 'nekkora-rpa==1.0.0', "
                + "'single', '" + sha + "', '" + fx.adminMembership + "' FROM automation_versions WHERE automation_version_id = '" + fx.version + "'");
        return fx;
    }

    /** Maquina do Agent Client habilitada para execucao remota, com a autorizacao da versao e o Python escolhido. */
    private Machine remote(AgentFixture fx) {
        UUID worker = fx.worker("W-cap-" + UUID.randomUUID().toString().substring(0, 6));
        UUID ww = fx.authorizeWorker(worker);
        UUID authorization = fx.localRun(worker, ww);
        MachineClient m = new MachineClient(port, "10.66." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250)));
        String secret = Tokens.newToken();
        MachineClient.Response r = m.redeemWithIntent(fx.remotePairingCode(worker, "Estacao " + worker.toString().substring(0, 6)), secret, "pair-remote");
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        String credential = MachineToken.credential(fx.org, UUID.fromString(r.body().get("credentialId").asString()), secret);
        assertThat(m.post("/heartbeat", CAPABLE, credential).status()).isEqualTo(200);
        String agent = r.body().get("agentId").asString();
        DB.asSuperuser("INSERT INTO agent_python_environments (agent_id, environment_id, organization_id, python_version, kind, observed_at) VALUES ('" + agent
                        + "', '" + ENVIRONMENT + "', '" + fx.org + "', '3.12.10', 1, clock_timestamp())",
                "INSERT INTO automation_python_selections (automation_id, agent_id, organization_id, workspace_id, environment_id, python_version, "
                        + "selected_by_membership_id) VALUES ('" + fx.automation + "', '" + agent + "', '" + fx.org + "', '" + fx.workspace + "', '" + ENVIRONMENT
                        + "', '3.0.0', '" + fx.adminMembership + "')");
        return new Machine(m, agent, worker, authorization, credential);
    }

    /** Solicitacao e primeira tentativa na fila (os gatilhos da 046, da 064 e da 066 valem). Devolve o id da tentativa. */
    private String queued(AgentFixture fx, Machine m) {
        String execution = fx.value("INSERT INTO remote_executions (organization_id, workspace_id, automation_id, automation_version_id, package_fingerprint, "
                + "package_fingerprint_rule, requirements_revision, effective_dependencies, request_key, requested_by_membership_id) SELECT '" + fx.org + "', '"
                + fx.workspace + "', '" + fx.automation + "', '" + fx.version + "', command_json -> 'agent_package' ->> 'fingerprint', "
                + "command_json -> 'agent_package' ->> 'fingerprint_rule', 1, '[]'::jsonb, gen_random_uuid(), '" + fx.adminMembership
                + "' FROM automation_versions WHERE automation_version_id = '" + fx.version + "' RETURNING remote_execution_id");
        return fx.value("INSERT INTO remote_attempts (organization_id, remote_execution_id, attempt_number, agent_id, worker_id, authorization_id, "
                + "created_by_membership_id, offer_valid_until) VALUES ('" + fx.org + "', '" + execution + "', 1, '" + m.agent() + "', '" + m.worker() + "', '"
                + m.authorization() + "', '" + fx.adminMembership + "', clock_timestamp() + interval '15 minutes') RETURNING attempt_id");
    }

    private JsonNode next(Machine m) {
        MachineClient.Response r = m.client().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/agent/api/v1/remote/next")).GET(), m.credential());
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        return r.body().get("attempt");
    }

    private static boolean offered(JsonNode attempt) {
        return attempt != null && !attempt.isNull();
    }

    private static String grant(JsonNode offer) {
        return "\"token\":\"" + offer.get("token").asString() + "\",\"epoch\":" + offer.get("epoch").asInt();
    }

    private void move(Machine m, String attempt, String grant, String... states) {
        for (String state : states) {
            String extra = "SUCCEEDED".equals(state) ? ",\"exitCode\":0" : "";
            MachineClient.Response r = "RECEIVED".equals(state) ? m.client().post("/remote/attempts/" + attempt + "/ack", "{" + grant + "}", m.credential())
                    : m.client().post("/remote/attempts/" + attempt + "/state", "{" + grant + ",\"state\":\"" + state + "\"" + extra + "}", m.credential());
            assertThat(r.status()).as(state + " " + r.raw()).isEqualTo(200);
        }
    }

    /** Todas as maquinas perguntam AO MESMO TEMPO o que ha para elas; devolve a resposta de cada uma, na ordem da lista. */
    private List<JsonNode> together(List<Machine> machines) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(machines.size());
        CountDownLatch ready = new CountDownLatch(machines.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<JsonNode>> asked = new ArrayList<>();
            for (Machine m : machines) {
                asked.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return next(m);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<JsonNode> out = new ArrayList<>();
            for (Future<JsonNode> f : asked) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String capacity(AgentFixture fx) {
        return fx.value("SELECT concurrent_used || '/' || concurrent_limit || ' remote=' || concurrent_remote || ' incerta=' || concurrent_uncertain "
                + "|| ' platform=' || concurrent_platform || ' anterior=' || concurrent_previous_agent || ' pendentes=' || pending_used FROM public.capacity_state('"
                + fx.org + "')");
    }

    private static String states(AgentFixture fx) {
        return fx.value("SELECT string_agg(n || 'x' || state, ',' ORDER BY state) FROM (SELECT state, count(*) AS n FROM remote_attempts WHERE organization_id = '"
                + fx.org + "' GROUP BY state) s");
    }

    @Test
    void comLimiteDeCincoASextaExecucaoPermanecePendenteMesmoComIniciosConcorrentes() throws Exception {
        AgentFixture fx = licensed();
        assertThat(fx.value("SELECT limit_value FROM technical_capacity_limits WHERE resource_code = 2")).as("limite decidido").isEqualTo("5");
        List<Machine> machines = new ArrayList<>();
        List<String> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Machine m = remote(fx);
            machines.add(m);
            attempts.add(queued(fx, m));
        }
        assertThat(capacity(fx)).as("seis na fila, nenhuma vaga ocupada").isEqualTo("0/5 remote=0 incerta=0 platform=0 anterior=0 pendentes=6");

        // ---- as seis maquinas perguntam ao mesmo tempo: cinco recebem, a sexta nao
        List<JsonNode> first = together(machines);
        assertThat(first.stream().filter(CapacityReservationIT::offered).count()).as("entregas concorrentes com limite 5").isEqualTo(5);
        assertThat(states(fx)).as("cinco oferecidas, uma na fila").isEqualTo("1x1,5x2");
        assertThat(capacity(fx)).isEqualTo("5/5 remote=5 incerta=0 platform=0 anterior=0 pendentes=1");
        int waiting = -1;
        for (int i = 0; i < 6; i++) {
            if (!offered(first.get(i))) {
                waiting = i;
            }
        }
        Machine sixth = machines.get(waiting);
        // a sexta NAO foi recusada nem encerrada: continua pendente, e continua sem receber enquanto nao houver vaga
        assertThat(fx.value("SELECT state || ':' || (ended_at IS NULL) || ':' || (refusal_reason IS NULL) FROM remote_attempts WHERE attempt_id = '"
                + attempts.get(waiting) + "'")).isEqualTo("1:true:true");
        assertThat(offered(next(sixth))).as("sem vaga, nada e entregue").isFalse();
        // perguntar de novo (resposta perdida) reentrega a MESMA tentativa as cinco, sem ocupar outra vaga; a sexta segue esperando
        List<JsonNode> again = together(machines);
        assertThat(again.stream().filter(CapacityReservationIT::offered).count()).isEqualTo(5);
        assertThat(offered(again.get(waiting))).isFalse();
        assertThat(capacity(fx)).isEqualTo("5/5 remote=5 incerta=0 platform=0 anterior=0 pendentes=1");

        // ---- as cinco seguem ate executar: a vaga continua ocupada do recebimento ao fim
        List<Integer> running = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            if (i != waiting) {
                move(machines.get(i), attempts.get(i), grant(again.get(i)), "RECEIVED", "PREPARING", "STARTING", "RUNNING");
                running.add(i);
            }
        }
        assertThat(states(fx)).isEqualTo("1x1,5x6");
        assertThat(offered(next(sixth))).as("cinco em execucao: a sexta continua pendente").isFalse();

        // ---- uma termina: a vaga volta e a sexta e entregue
        int done = running.get(0);
        move(machines.get(done), attempts.get(done), grant(again.get(done)), "SUCCEEDED");
        JsonNode late = next(sixth);
        assertThat(offered(late)).as("vaga liberada: a pendente e entregue").isTrue();
        assertThat(late.get("attemptId").asString()).isEqualTo(attempts.get(waiting));
        assertThat(capacity(fx)).isEqualTo("5/5 remote=5 incerta=0 platform=0 anterior=0 pendentes=0");

        // ---- UMA vaga, DUAS pendentes perguntando ao mesmo tempo: so uma recebe
        int alsoDone = running.get(1);
        move(machines.get(alsoDone), attempts.get(alsoDone), grant(again.get(alsoDone)), "SUCCEEDED");
        Machine x = remote(fx);
        Machine y = remote(fx);
        queued(fx, x);
        queued(fx, y);
        List<JsonNode> race = together(List.of(x, y));
        assertThat(race.stream().filter(CapacityReservationIT::offered).count()).as("uma vaga, dois inicios concorrentes").isEqualTo(1);
        assertThat(capacity(fx)).isEqualTo("5/5 remote=5 incerta=0 platform=0 anterior=0 pendentes=1");

        // ---- o banco garante mesmo sem a aplicacao: a entrega direta, com o papel do canal, e recusada sem vaga
        Machine z = offered(race.get(0)) ? y : x;
        assertThat(asIngestion(fx.org, "UPDATE remote_attempts SET state = 2, token_hash = repeat('a', 64), epoch = epoch + 1 WHERE organization_id = '" + fx.org
                + "' AND agent_id = '" + z.agent() + "' AND state = 1")).as("gatilho da 066").isEqualTo("23514");
        // capacidade nao e licenca: a automacao continua com direito e nenhuma tentativa foi recusada
        assertThat(fx.value("SELECT public.license_state('" + fx.org + "', '" + fx.automation + "')")).isEqualTo("OK");
        assertThat(fx.count("SELECT count(*) FROM remote_attempts WHERE organization_id = '" + fx.org + "' AND state = 11")).isZero();
    }

    @Test
    void osFluxosDeExecucaoCompartilhamOLimiteEAOcupacaoIncertaSeguraAVaga() throws Exception {
        AgentFixture fx = licensed();
        // (1) sessao do Agent anterior iniciada NA MAQUINA: o servidor nao tem como segurar; o registro e aceito e ocupa vaga
        UUID oldWorker = fx.worker("W-ant-" + UUID.randomUUID().toString().substring(0, 6));
        UUID oldAuthorization = fx.localRun(oldWorker, fx.authorizeWorker(oldWorker));
        MachineClient old = new MachineClient(port, "10.67.1." + (1 + (int) (Math.random() * 250)));
        String oldAgent = old.pair(fx.pairingCode(oldWorker, "Estacao anterior", "15 minutes")).get("agentId").asString();
        MachineClient.Response local = old.register(execution(), oldAuthorization.toString(), AgentFixture.REAL_FINGERPRINT, runningNow());
        assertThat(local.status()).as(local.raw()).isEqualTo(201);
        assertThat(capacity(fx)).isEqualTo("1/5 remote=0 incerta=0 platform=0 anterior=1 pendentes=0");

        // (2) pedido ao Agent anterior: pendente ate ser ENTREGUE; a entrega ocupa vaga
        String request = request(fx, oldAuthorization, oldWorker, oldAgent);
        assertThat(capacity(fx)).isEqualTo("1/5 remote=0 incerta=0 platform=0 anterior=1 pendentes=1");
        assertThat(requests(old).toString()).contains(request);
        assertThat(capacity(fx)).isEqualTo("2/5 remote=0 incerta=0 platform=0 anterior=2 pendentes=0");

        // (3) duas tentativas remotas entregues e (4) uma terceira que executa e termina SEM CONFIRMACAO (ocupacao incerta)
        Machine a = remote(fx);
        Machine b = remote(fx);
        Machine c = remote(fx);
        queued(fx, a);
        queued(fx, b);
        String lost = queued(fx, c);
        assertThat(offered(next(a))).isTrue();
        assertThat(offered(next(b))).isTrue();
        move(c, lost, grant(next(c)), "RECEIVED", "PREPARING", "STARTING", "RUNNING");
        assertThat(capacity(fx)).isEqualTo("5/5 remote=3 incerta=0 platform=0 anterior=2 pendentes=0");
        // a comunicacao e perdida: o servidor encerra como "resultado nao confirmado" (massa: o estado que o encerramento da 053 grava)
        untriggered("UPDATE remote_attempts SET state = 13, closure_cause = 'COMMUNICATION_LOST', ended_at = clock_timestamp(), lease_expires_at = NULL "
                + "WHERE attempt_id = '" + lost + "'");
        assertThat(capacity(fx)).as("terminou sem confirmacao: o processo pode estar vivo, a vaga continua ocupada")
                .isEqualTo("5/5 remote=2 incerta=1 platform=0 anterior=2 pendentes=0");

        // ---- cheio. Nada novo e ENTREGUE, em nenhum fluxo; nada e recusado
        Machine d = remote(fx);
        String waitingAttempt = queued(fx, d);
        assertThat(offered(next(d))).as("tentativa remota espera").isFalse();
        UUID otherWorker = fx.worker("W-ant-" + UUID.randomUUID().toString().substring(0, 6));
        UUID otherAuthorization = fx.localRun(otherWorker, fx.authorizeWorker(otherWorker));
        MachineClient other = new MachineClient(port, "10.67.2." + (1 + (int) (Math.random() * 250)));
        String otherAgent = other.pair(fx.pairingCode(otherWorker, "Estacao anterior 2", "15 minutes")).get("agentId").asString();
        String heldRequest = request(fx, otherAuthorization, otherWorker, otherAgent);
        assertThat(requests(other).toString()).as("pedido ao Agent anterior nao e entregue sem vaga").doesNotContain(heldRequest);
        assertThat(fx.value("SELECT state || ':' || deliveries FROM run_requests WHERE run_request_id = '" + heldRequest + "'")).as("continua pendente")
                .isEqualTo("1:0");
        assertThat(requests(old).toString()).as("o pedido ja entregue continua vindo para a maquina dele").contains(request);
        assertThat(asIngestion(fx.org, "UPDATE run_requests SET state = 2, deliveries = 1 WHERE run_request_id = '" + heldRequest + "'"))
                .as("o banco recusa a entrega direta sem vaga").isEqualTo("23514");
        assertThat(capacity(fx)).isEqualTo("5/5 remote=2 incerta=1 platform=0 anterior=2 pendentes=2");
        // a sessao que o Agent anterior inicia na maquina nao pode ser segurada: e registrada, e a ocupacao passa do limite
        assertThat(other.register(execution(), otherAuthorization.toString(), AgentFixture.REAL_FINGERPRINT, runningNow()).status()).isEqualTo(201);
        assertThat(capacity(fx)).isEqualTo("6/5 remote=2 incerta=1 platform=0 anterior=3 pendentes=2");

        // ---- liberar: a declaracao do administrador reconcilia a ocupacao incerta (6 -> 5: ainda cheio)
        // (massa: a declaracao real e feita pela pessoa, na Platform - RemoteExecutionIT)
        untriggered("INSERT INTO remote_attempt_process_reports (attempt_id, kind, organization_id, agent_id, reason, verification, by_membership_id) VALUES ('"
                + lost + "', 2, '" + fx.org + "', '" + c.agent() + "', 'OTHER_VERIFIED', 'conferido na maquina: nenhum processo da tentativa', '"
                + fx.adminMembership + "')");
        assertThat(capacity(fx)).isEqualTo("5/5 remote=2 incerta=0 platform=0 anterior=3 pendentes=2");
        assertThat(offered(next(d))).isFalse();
        // a primeira sessao do Agent anterior termina (4 de 5): a proxima pergunta recebe - e so uma
        untriggered("UPDATE agent_runs SET supervisor_state = 4 WHERE organization_id = '" + fx.org + "' AND agent_id = '" + oldAgent + "'");
        assertThat(capacity(fx)).isEqualTo("4/5 remote=2 incerta=0 platform=0 anterior=2 pendentes=2");
        assertThat(requests(other).toString()).as("com vaga, o pedido pendente e entregue").contains(heldRequest);
        JsonNode stillWaiting = next(d);
        assertThat(offered(stillWaiting)).as("a vaga foi para o pedido: a tentativa remota continua pendente").isFalse();
        assertThat(fx.value("SELECT state FROM remote_attempts WHERE attempt_id = '" + waitingAttempt + "'")).isEqualTo("1");
        assertThat(capacity(fx)).isEqualTo("5/5 remote=2 incerta=0 platform=0 anterior=3 pendentes=1");

        // ---- a organizacao do lado, no modelo anterior, nao tem vaga a reservar: tudo como antes
        AgentFixture legacy = new AgentFixture(DB);
        assertThat(legacy.value("SELECT public.capacity_reserve('" + legacy.org + "')")).isEqualTo("t");
        assertThat(legacy.value("SELECT concurrent_limit IS NULL AND pending_limit IS NULL FROM public.capacity_state('" + legacy.org + "')")).isEqualTo("t");
    }

    private static String execution() {
        return "ex-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String runningNow() {
        return "{\"state\":\"RUNNING\",\"started\":\"" + java.time.Instant.now() + "\"}";
    }

    private static String request(AgentFixture fx, UUID authorization, UUID worker, String agent) {
        return fx.value("INSERT INTO run_requests (organization_id, workspace_id, automation_id, automation_version_id, authorization_id, worker_id, "
                + "agent_id, source_kind, package_fingerprint, package_fingerprint_rule, request_key, requested_by_membership_id, valid_until) VALUES ('" + fx.org
                + "', '" + fx.workspace + "', '" + fx.automation + "', '" + fx.version + "', '" + authorization + "', '" + worker + "', '" + agent + "', 1, '"
                + AgentFixture.REAL_FINGERPRINT + "', 'nekkora-agent/package-fingerprint/1', gen_random_uuid(), '" + fx.adminMembership
                + "', now() + interval '10 minutes') RETURNING run_request_id");
    }

    private JsonNode requests(MachineClient m) {
        MachineClient.Response r = m.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/agent/api/v1/run-requests")).GET(), m.credential);
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        return r.body();
    }

    private static void untriggered(String sql) {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("fixture falhou (sqlstate " + e.getSQLState() + ")", e);
        }
    }

    private static String asIngestion(UUID org, String sql) {
        try (Connection c = DB.connectAs("nekkora_ingestion")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
                st.execute(sql);
                c.commit();
                return "OK";
            } catch (SQLException e) {
                c.rollback();
                return e.getSQLState();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
