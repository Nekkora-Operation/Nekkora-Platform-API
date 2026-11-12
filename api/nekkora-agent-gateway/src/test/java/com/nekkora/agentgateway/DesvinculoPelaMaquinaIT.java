package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.core.token.Tokens;

import tools.jackson.databind.JsonNode;

/**
 * PLATFORM-AGENT-V2-004, T13: "Desvincular esta maquina" tambem na Platform, no processo REAL do gateway (papel
 * nekkora_ingestion), PostgreSQL 16 com todas as migrations (ate a 035) e Redis reais. Protocolo:
 * docs/platform-agent-v2-004/t13-desvinculo-remoto.md. A maquina encerra SO o proprio vinculo, uma vez, com autoria propria;
 * a repeticao depois do commit (resposta perdida) recebe o mesmo resultado; nada atinge outro Agent, Worker ou organizacao.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentGatewayApplication.class)
class DesvinculoPelaMaquinaIT {

    static final RealDatabase DB = RealDatabase.shared();
    static AgentFixture fx;
    static AgentFixture other;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        RealRedis redis = RealRedis.shared();
        r.add("nekkora.database.url", DB::url);
        r.add("nekkora.database.user", () -> "nekkora_ingestion");
        r.add("nekkora.database.password", () -> DB.password("nekkora_ingestion"));
        r.add("spring.data.redis.host", redis::host);
        r.add("spring.data.redis.port", redis::port);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.limits.batches-per-minute", () -> "100000");
        r.add("nekkora.limits.redeem-failures-per-hour", () -> "1000");
    }

    @BeforeAll
    static void fixture() {
        fx = new AgentFixture(DB);
        other = new AgentFixture(DB);
    }

    record Paired(MachineClient m, UUID worker, UUID wwa, UUID authorization, UUID agent, UUID credential) {
    }

    Paired paired(AgentFixture f) {
        UUID worker = f.worker("W-" + UUID.randomUUID().toString().substring(0, 6));
        UUID wwa = f.authorizeWorker(worker);
        UUID authorization = f.localRun(worker, wwa);
        return pairOn(f, worker, wwa, authorization);
    }

    Paired pairOn(AgentFixture f, UUID worker, UUID wwa, UUID authorization) {
        MachineClient m = new MachineClient(port, "10.62." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250)));
        JsonNode id = m.pair(f.pairingCode(worker, "Estacao T13", "15 minutes"));
        return new Paired(m, worker, wwa, authorization, UUID.fromString(id.get("agentId").asString()), UUID.fromString(id.get("credentialId").asString()));
    }

    static String op() {
        return UUID.randomUUID().toString();
    }

    String state(AgentFixture f, UUID agent) {
        return f.value("SELECT a.status || '|' || (SELECT count(*) FROM worker_agent_assignments wa WHERE wa.agent_id = a.agent_id AND wa.status = 1) || '|' "
                + "|| (SELECT count(*) FROM agent_credentials c WHERE c.agent_id = a.agent_id AND c.revoked_at IS NULL) FROM agents a WHERE a.agent_id = '" + agent + "'");
    }

    // =========================================================================== T13-2: online

    @Test
    void desvinculoOnlineEncerraSoOVinculoDaCredencialComAutoriaDaMaquinaELiberaOWorker() {
        Paired p = paired(fx);
        assertThat(p.m().heartbeat().status()).isEqualTo(200);
        String operation = op();
        MachineClient.Response r = p.m().unlink(operation, p.agent().toString(), List.of());
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(r.body().get("outcome").asString()).isEqualTo("UNLINKED");
        assertThat(r.body().get("operationId").asString()).isEqualTo(operation);
        assertThat(r.body().get("agentId").asString()).isEqualTo(p.agent().toString());
        assertThat(r.body().get("workerId").asString()).isEqualTo(p.worker().toString());
        assertThat(r.headers().firstValue("Cache-Control").orElse("")).contains("no-store");

        // a credencial antiga perde o acesso a toda rota de trabalho
        assertThat(p.m().heartbeat().status()).isEqualTo(401);
        assertThat(p.m().authorizations().status()).isEqualTo(401);
        // Agent revogado, sem associacao ativa, sem credencial aberta; autoria da maquina, sem pessoa
        assertThat(state(fx, p.agent())).isEqualTo("4|0|0");
        assertThat(fx.value("SELECT revocation_reason || '|' || (revoked_by_agent_id = agent_id) || '|' || (revoked_by_membership_id IS NULL) FROM agent_credentials "
                + "WHERE credential_id = '" + p.credential() + "'")).isEqualTo("5|true|true");
        assertThat(fx.value("SELECT status || '|' || (ended_by_agent_id = agent_id) || '|' || (ended_by_membership_id IS NULL) FROM worker_agent_assignments "
                + "WHERE agent_id = '" + p.agent() + "'")).isEqualTo("2|true|true");
        assertThat(fx.value("SELECT act || '|' || (actor_agent_id = agent_id) || '|' || (actor_membership_id IS NULL) || '|' || worker_id || '|' || reason_code "
                + "FROM agent_admin_events WHERE agent_id = '" + p.agent() + "' AND act = 14")).isEqualTo("14|true|true|" + p.worker() + "|MACHINE_UNLINK");
        // o Worker continua ativo, com o workspace, a autorizacao de execucao local e o historico
        assertThat(fx.value("SELECT status FROM workers WHERE worker_id = '" + p.worker() + "'")).isEqualTo("1");
        assertThat(fx.value("SELECT status FROM worker_workspace_assignments WHERE assignment_id = '" + p.wwa() + "'")).isEqualTo("1");
        assertThat(fx.value("SELECT revoked_at IS NULL FROM local_run_authorizations WHERE authorization_id = '" + p.authorization() + "'")).isEqualTo("t");
        assertThat(fx.count("SELECT count(*) FROM agent_admin_events WHERE agent_id = '" + p.agent() + "' AND act = 3")).isEqualTo(1);

        // um novo pareamento no MESMO Worker: outro Agent, que trabalha; recebe as autorizacoes do Worker (nao do vinculo)
        Paired q = pairOn(fx, p.worker(), p.wwa(), p.authorization());
        assertThat(q.agent()).isNotEqualTo(p.agent());
        assertThat(q.m().heartbeat().status()).isEqualTo(200);
        MachineClient.Response a = q.m().authorizations();
        assertThat(a.status()).isEqualTo(200);
        assertThat(a.raw()).contains(p.authorization().toString());
        assertThat(state(fx, q.agent())).isEqualTo("2|1|1");
    }

    // =========================================================================== T13-6/7: resposta perdida, repeticao, concorrencia

    @Test
    void repeticaoDaMesmaOperacaoDepoisDoCommitRecebeOMesmoResultadoSemNovoEfeito() {
        Paired p = paired(fx);
        String operation = op();
        MachineClient.Response first = p.m().unlink(operation, p.agent().toString(), List.of());
        assertThat(first.status()).isEqualTo(200);
        // a resposta "se perdeu": a maquina repete com a MESMA operacao e a MESMA credencial (ja revogada por ela)
        MachineClient.Response again = p.m().unlink(operation, p.agent().toString(), List.of());
        assertThat(again.status()).as(again.raw()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(fx.count("SELECT count(*) FROM agent_unlink_operations WHERE agent_id = '" + p.agent() + "'")).isEqualTo(1);
        assertThat(fx.count("SELECT count(*) FROM agent_admin_events WHERE agent_id = '" + p.agent() + "' AND act = 14")).isEqualTo(1);
        // a excecao vale SO nesta rota: a credencial revogada continua recusada nas demais
        assertThat(p.m().heartbeat().status()).isEqualTo(401);
        // outra operacao da mesma maquina (intencao perdida e refeita): conclusivo, sem efeito
        MachineClient.Response otherOp = p.m().unlink(op(), p.agent().toString(), List.of());
        assertThat(otherOp.status()).isEqualTo(409);
        assertThat(otherOp.body().get("reason").asString()).isEqualTo("link_ended_by_machine");
        assertThat(fx.count("SELECT count(*) FROM agent_unlink_operations WHERE agent_id = '" + p.agent() + "'")).isEqualTo(1);
    }

    @Test
    void chamadasConcorrentesTemUmEfeitoSo() throws Exception {
        Paired p = paired(fx);
        String operation = op();
        String second = op();
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<MachineClient.Response>> calls = List.of(operation, operation, operation, second).stream()
                .map(o -> CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    return p.m().unlink(o, p.agent().toString(), List.of());
                })).toList();
        go.countDown();
        List<MachineClient.Response> rs = calls.stream().map(c -> c.join()).toList();
        // uma das duas operacoes vence; as repeticoes dela recebem o mesmo 200; a outra, 409 link_ended_by_machine
        String winner = fx.value("SELECT operation_id FROM agent_unlink_operations WHERE agent_id = '" + p.agent() + "'");
        for (int i = 0; i < rs.size(); i++) {
            String o = i < 3 ? operation : second;
            if (o.equals(winner)) {
                assertThat(rs.get(i).status()).as(rs.get(i).raw()).isEqualTo(200);
                assertThat(rs.get(i).body().get("operationId").asString()).isEqualTo(winner);
            } else {
                assertThat(rs.get(i).status()).as(rs.get(i).raw()).isEqualTo(409);
                assertThat(rs.get(i).body().get("reason").asString()).isEqualTo("link_ended_by_machine");
            }
        }
        assertThat(fx.count("SELECT count(*) FROM agent_admin_events WHERE agent_id = '" + p.agent() + "' AND act = 14")).isEqualTo(1);
        assertThat(state(fx, p.agent())).isEqualTo("4|0|0");
    }

    // =========================================================================== T13-8: revogacao administrativa e pedido atrasado

    @Test
    void revogacaoAdministrativaAntesOuConcorrenteDaConclusaoSemDuplaAutoria() throws Exception {
        // antes: o vinculo ja terminou pela administracao
        Paired p = paired(fx);
        revokeAsAdmin(p, null);
        MachineClient.Response r = p.m().unlink(op(), p.agent().toString(), List.of());
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.body().get("reason").asString()).isEqualTo("link_ended_by_administration");
        assertThat(fx.count("SELECT count(*) FROM agent_unlink_operations WHERE agent_id = '" + p.agent() + "'")).isZero();
        assertThat(fx.value("SELECT ended_by_membership_id FROM worker_agent_assignments WHERE agent_id = '" + p.agent() + "'")).isEqualTo(fx.adminMembership.toString());

        // concorrente: a revogacao trava o Agent; o desvinculo espera (pg_stat_activity) e, depois do commit, recebe 409
        Paired q = paired(fx);
        revokeAsAdmin(q, () -> {
            CompletableFuture<MachineClient.Response> pending = CompletableFuture.supplyAsync(() -> q.m().unlink(op(), q.agent().toString(), List.of()));
            waitLock();
            assertThat(pending.isDone()).isFalse();
            return pending;
        });
    }

    interface Concurrent {
        CompletableFuture<MachineClient.Response> during() throws Exception;
    }

    /** A revogacao da Web (AgentAdminService.revokeAgent: Agent FOR UPDATE, credenciais, associacao, status), como nekkora_platform. */
    void revokeAsAdmin(Paired p, Concurrent during) throws Exception {
        try (Connection admin = DB.connectAs("nekkora_platform")) {
            admin.setAutoCommit(false);
            CompletableFuture<MachineClient.Response> pending = null;
            try (Statement st = admin.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + fx.org + "', true)");
                st.execute("SELECT status FROM agents WHERE agent_id = '" + p.agent() + "' FOR UPDATE");
                if (during != null) {
                    pending = during.during();
                }
                st.executeUpdate("UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_membership_id = '" + fx.adminMembership
                        + "', revocation_reason = 4 WHERE agent_id = '" + p.agent() + "' AND revoked_at IS NULL");
                st.executeUpdate("UPDATE worker_agent_assignments SET status = 2, ended_at = clock_timestamp(), ended_by_membership_id = '" + fx.adminMembership
                        + "' WHERE agent_id = '" + p.agent() + "' AND status = 1");
                st.executeUpdate("UPDATE agents SET status = 4 WHERE agent_id = '" + p.agent() + "'");
            }
            admin.commit();
            if (pending != null) {
                MachineClient.Response r = pending.get(30, TimeUnit.SECONDS);
                assertThat(r.status()).as(r.raw()).isEqualTo(409);
                assertThat(r.body().get("reason").asString()).isEqualTo("link_ended_by_administration");
                assertThat(fx.count("SELECT count(*) FROM agent_unlink_operations WHERE agent_id = '" + p.agent() + "'")).isZero();
            }
        }
    }

    static void waitLock() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE usename = 'nekkora_ingestion' AND wait_event_type = 'Lock'")) {
                rs.next();
                if (rs.getLong(1) > 0) {
                    return;
                }
            }
            assertThat(System.nanoTime()).as("o desvinculo devia esperar a trava").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    @Test
    void pedidoAtrasadoDepoisDeNovoPareamentoNaoAtingeONovoVinculo() {
        Paired p = paired(fx);
        String operation = op();
        assertThat(p.m().unlink(operation, p.agent().toString(), List.of()).status()).isEqualTo(200);
        Paired q = pairOn(fx, p.worker(), p.wwa(), p.authorization());
        // repeticao atrasada da operacao antiga e uma operacao nova com a credencial antiga: nenhuma toca o vinculo novo
        assertThat(p.m().unlink(operation, p.agent().toString(), List.of()).status()).isEqualTo(200);
        MachineClient.Response late = p.m().unlink(op(), p.agent().toString(), List.of());
        assertThat(late.status()).isEqualTo(409);
        // a credencial antiga pedindo o Agent NOVO: 400, sem efeito (o corpo nao escolhe alvo)
        assertThat(p.m().unlink(op(), q.agent().toString(), List.of()).status()).isEqualTo(400);
        assertThat(state(fx, q.agent())).isEqualTo("2|1|1");
        assertThat(q.m().heartbeat().status()).isEqualTo(200);
        // heartbeat atrasado da maquina antiga nao ressuscita o vinculo encerrado
        assertThat(p.m().heartbeat().status()).isEqualTo(401);
        assertThat(state(fx, p.agent())).isEqualTo("4|0|0");
    }

    // =========================================================================== T13-9: escopo

    @Test
    void tentativasContraOutroAgentWorkerOuOrganizacaoSaoRecusadasSemEfeito() {
        Paired p = paired(fx);
        Paired q = paired(fx);
        Paired o = paired(other);
        // o corpo aponta outro Agent da mesma organizacao e de outra: 400, nada muda em nenhum
        assertThat(p.m().unlink(op(), q.agent().toString(), List.of()).status()).isEqualTo(400);
        assertThat(p.m().unlink(op(), o.agent().toString(), List.of()).status()).isEqualTo(400);
        // credencial com a organizacao trocada (segredo certo, outra organizacao no token): 401
        String forged = MachineToken.credential(other.org, p.credential(), p.m().secret);
        assertThat(p.m().unlinkWith(forged, op(), p.agent().toString(), List.of()).status()).isEqualTo(401);
        // segredo errado: 401; sem credencial: 401
        String wrong = MachineToken.credential(fx.org, p.credential(), Tokens.newToken());
        assertThat(p.m().unlinkWith(wrong, op(), p.agent().toString(), List.of()).status()).isEqualTo(401);
        assertThat(p.m().unlinkWith(null, op(), p.agent().toString(), List.of()).status()).isEqualTo(401);
        // corpo invalido: 400
        assertThat(p.m().post("/link/unlink", "{\"operationId\":\"x\",\"agentId\":\"" + p.agent() + "\",\"launchedRunRequests\":[]}", p.m().credential).status())
                .isEqualTo(400);
        assertThat(p.m().post("/link/unlink", "{\"operationId\":\"" + op() + "\",\"agentId\":\"" + p.agent() + "\"}", p.m().credential).status()).isEqualTo(400);
        for (Paired x : List.of(p, q)) {
            assertThat(state(fx, x.agent())).isEqualTo("2|1|1");
        }
        assertThat(state(other, o.agent())).isEqualTo("2|1|1");
        assertThat(fx.count("SELECT count(*) FROM agent_unlink_operations WHERE agent_id IN ('" + p.agent() + "', '" + q.agent() + "', '" + o.agent() + "')"))
                .isZero();
    }

    @Test
    void credencialSubstituidaPorRotacaoNaoDesvinculaNemProvaDesvinculo() {
        Paired p = paired(fx);
        String old = p.m().credential;
        String code = fx.rotationCode(p.agent(), p.worker());
        String secret = Tokens.newToken();
        MachineClient.Response rot = p.m().redeemWithIntent(code, secret, "rotate");
        assertThat(rot.status()).as(rot.raw()).isEqualTo(200);
        MachineClient.Response r = p.m().unlinkWith(old, op(), p.agent().toString(), List.of());
        assertThat(r.status()).isEqualTo(401);
        assertThat(state(fx, p.agent())).isEqualTo("2|1|1");
    }

    // =========================================================================== T13-10/11: pedidos de execucao

    UUID request(UUID authorization, UUID worker, UUID agent) {
        return UUID.fromString(fx.value("INSERT INTO run_requests (organization_id, workspace_id, automation_id, automation_version_id, authorization_id, worker_id, "
                + "agent_id, source_kind, package_fingerprint, package_fingerprint_rule, request_key, requested_by_membership_id, valid_until) VALUES ('" + fx.org
                + "', '" + fx.workspace + "', '" + fx.automation + "', '" + fx.version + "', '" + authorization + "', '" + worker + "', '" + agent + "', 1, '"
                + AgentFixture.REAL_FINGERPRINT + "', 'nekkora-agent/package-fingerprint/1', gen_random_uuid(), '" + fx.adminMembership
                + "', now() + interval '10 minutes') RETURNING run_request_id"));
    }

    MachineClient.Response accept(Paired p, UUID request, String exec) {
        return p.m().post("/run-requests/" + request + "/decision", "{\"decision\":\"ACCEPTED\",\"agentExecutionId\":\"" + exec + "\"}", p.m().credential);
    }

    @Test
    void pedidosAbertosSaoEncerradosSemFingirExecucaoNemSeremReenviadosAOutraMaquina() {
        Paired p = paired(fx);
        UUID requested = request(p.authorization(), p.worker(), p.agent());
        UUID delivered = request(p.authorization(), p.worker(), p.agent());
        UUID acceptedNotStarted = request(p.authorization(), p.worker(), p.agent());
        UUID acceptedStarted = request(p.authorization(), p.worker(), p.agent());
        UUID expired = request(p.authorization(), p.worker(), p.agent());
        // entrega (lista) de todos, depois o vencido sai do prazo e dois sao aceitos
        assertThat(p.m().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/agent/api/v1/run-requests")).GET(),
                p.m().credential).status()).isEqualTo(200);
        DB.asSuperuser("SET session_replication_role = replica",
                "UPDATE run_requests SET state = 1, delivered_at = NULL, deliveries = 0 WHERE run_request_id = '" + requested + "'",
                "UPDATE run_requests SET requested_at = now() - interval '20 minutes', valid_until = now() - interval '1 minute' WHERE run_request_id = '" + expired + "'",
                "SET session_replication_role = origin");
        assertThat(accept(p, acceptedNotStarted, "ex-" + "a3".repeat(16)).status()).isEqualTo(200);
        assertThat(accept(p, acceptedStarted, "ex-" + "a4".repeat(16)).status()).isEqualTo(200);
        // pedido de OUTRA maquina listado como "iniciado": ignorado (nao conta, nao muda)
        Paired q = paired(fx);
        UUID foreign = request(q.authorization(), q.worker(), q.agent());

        MachineClient.Response r = p.m().unlink(op(), p.agent().toString(), List.of(acceptedStarted.toString(), foreign.toString()));
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        assertThat(r.body().get("runRequestsClosed").asInt()).isEqualTo(4);
        assertThat(r.body().get("runRequestsKeptAccepted").asInt()).isEqualTo(1);
        String s = "SELECT state || '|' || coalesce(decision_reason, '-') FROM run_requests WHERE run_request_id = '%s'";
        assertThat(fx.value(s.formatted(requested))).isEqualTo("4|AGENT_UNLINKED");
        assertThat(fx.value(s.formatted(delivered))).isEqualTo("4|AGENT_UNLINKED");
        assertThat(fx.value(s.formatted(expired))).isEqualTo("7|-");
        assertThat(fx.value(s.formatted(acceptedNotStarted))).isEqualTo("5|LAUNCH_NOT_ATTEMPTED");
        assertThat(fx.value(s.formatted(acceptedStarted))).isEqualTo("3|-"); // iniciado de fato: nao vira "nao iniciado"
        assertThat(fx.value(s.formatted(foreign))).isEqualTo("1|-");
        // os pedidos sao do Agent antigo: um vinculo novo do mesmo Worker nao os recebe
        Paired n = pairOn(fx, p.worker(), p.wwa(), p.authorization());
        MachineClient.Response list = n.m().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/agent/api/v1/run-requests"))
                .GET(), n.m().credential);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.raw()).doesNotContain(acceptedStarted.toString()).doesNotContain(requested.toString());
    }

    // =========================================================================== banco: a ingestao so encerra pelo desvinculo registrado

    @Test
    void aIngestaoNaoEncerraVinculoSemOperacaoNaMesmaTransacao() throws Exception {
        Paired p = paired(fx);
        Paired q = paired(fx);
        try (Connection c = DB.connectAs("nekkora_ingestion")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + fx.org + "', true)");
                for (String sql : List.of(
                        "UPDATE agents SET status = 4 WHERE agent_id = '" + p.agent() + "'",
                        "UPDATE agents SET status = 3 WHERE agent_id = '" + p.agent() + "'",
                        "UPDATE worker_agent_assignments SET status = 2, ended_at = clock_timestamp(), ended_by_agent_id = agent_id WHERE agent_id = '" + p.agent() + "'",
                        "UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_agent_id = agent_id, revocation_reason = 5 WHERE agent_id = '"
                                + p.agent() + "'")) {
                    st.execute("SAVEPOINT s");
                    try {
                        st.executeUpdate(sql);
                        throw new AssertionError("devia recusar: " + sql);
                    } catch (java.sql.SQLException e) {
                        assertThat(e.getSQLState()).as(sql).isIn("42501", "23514");
                    }
                    st.execute("ROLLBACK TO SAVEPOINT s");
                    st.execute("RELEASE SAVEPOINT s");
                }
                // a operacao de UM Agent nao autoriza encerrar OUTRO
                st.executeUpdate("INSERT INTO agent_unlink_operations (operation_id, organization_id, agent_id, worker_id, assignment_id, credential_id, "
                        + "run_requests_closed, run_requests_kept_accepted) SELECT gen_random_uuid(), organization_id, agent_id, worker_id, assignment_id, '"
                        + p.credential() + "', 0, 0 FROM worker_agent_assignments WHERE agent_id = '" + p.agent() + "' AND status = 1");
                st.execute("SAVEPOINT t");
                try {
                    st.executeUpdate("UPDATE agents SET status = 4 WHERE agent_id = '" + q.agent() + "'");
                    throw new AssertionError("encerrou outro Agent");
                } catch (java.sql.SQLException e) {
                    assertThat(e.getSQLState()).isEqualTo("42501");
                }
                st.execute("ROLLBACK TO SAVEPOINT t");
                st.execute("RELEASE SAVEPOINT t");
            }
            c.rollback();
        }
        assertThat(state(fx, p.agent())).isEqualTo("2|1|1");
        assertThat(state(fx, q.agent())).isEqualTo("2|1|1");
    }
}
