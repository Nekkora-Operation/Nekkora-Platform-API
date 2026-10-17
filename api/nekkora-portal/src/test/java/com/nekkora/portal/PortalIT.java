package com.nekkora.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.nekkora.core.db.ConnectionResetDataSource;
import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.core.token.Tokens;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Processo do portal real (porta aleatoria) com o papel real nekkora_portal, PostgreSQL 16 com 001-014 e Redis
 * reais. A fixture (organizacao, cliente, workspaces, automacao publicada, execucoes, eventos, conta externa com
 * fator e sessao com o marcador de MFA) e preparada como superusuario - DECLARADO como preparacao; os triggers da
 * 006/007/014 continuam valendo. Tudo o que e verificado passa pela API do portal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = PortalApplication.class)
@ExtendWith(OutputCaptureExtension.class)
class PortalIT {

    static final RealDatabase DB = RealDatabase.shared();
    static final UUID ORG = UUID.randomUUID();
    static final UUID CLIENT = UUID.randomUUID();
    static final UUID CLIENT2 = UUID.randomUUID();
    static final UUID WS1 = UUID.randomUUID();
    static final UUID WS2 = UUID.randomUUID();
    static final UUID WS3 = UUID.randomUUID();
    static final UUID ADMIN = UUID.randomUUID();
    static final UUID ADMIN_MEMBERSHIP = UUID.randomUUID();
    static final List<UUID> EXEC_WS1 = new ArrayList<>();
    static UUID execWs3;
    static UUID execWithEvents;

    @LocalServerPort
    int port;

    @Autowired
    PortalService portalService;

    @Autowired
    ConnectionResetDataSource resetDataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    DataSource dataSource;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        RealRedis redis = RealRedis.shared();
        r.add("nekkora.database.url", DB::url);
        r.add("nekkora.database.user", () -> "nekkora_portal");
        r.add("nekkora.database.password", () -> DB.password("nekkora_portal"));
        r.add("spring.data.redis.host", redis::host);
        r.add("spring.data.redis.port", redis::port);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
    }

    @BeforeAll
    static void fixture() {
        String o = "'" + ORG + "'";
        DB.asSuperuser(
                "INSERT INTO organizations (organization_id, name, status) VALUES (" + o + ", 'Org portal', 1)",
                "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + ADMIN + "', " + o
                        + ", 1, 'adm-" + ADMIN.toString().substring(0, 6) + "@o.test', now(), 2, 'Adm')",
                "INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('" + ADMIN_MEMBERSHIP + "', " + o + ", '" + ADMIN + "', 1, 2)",
                "INSERT INTO clients (client_id, organization_id, name, status) VALUES ('" + CLIENT + "', " + o + ", 'C1', 1), ('" + CLIENT2 + "', " + o + ", 'C2', 1)",
                "INSERT INTO workspaces (workspace_id, organization_id, client_id, name, description, status) VALUES ('" + WS1 + "', " + o + ", '" + CLIENT
                        + "', 'WS1', 'MARCADOR_DESCRICAO', 1), ('" + WS2 + "', " + o + ", '" + CLIENT + "', 'WS2', NULL, 1), ('" + WS3 + "', " + o + ", '" + CLIENT2 + "', 'WS3', NULL, 1)");
        // cinco limites (010) ilimitados: sem eles a 006/010 recusa criar execucao
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES (" + o + ", " + code + ", 1, now(), 2, NULL, '" + ADMIN + "')");
        }
        UUID automation1 = createAutomation(WS1);
        UUID automation3 = createAutomation(WS3);
        for (int i = 0; i < 30; i++) {
            // 30 execucoes; pares com created_at EMPATADO (keyset precisa desempatar por execution_id)
            EXEC_WS1.add(execution(WS1, automation1, "now() - interval '" + (100 - i / 2) + " minutes'"));
        }
        execWs3 = execution(WS3, automation3, "now() - interval '5 minutes'");
        execWithEvents = EXEC_WS1.get(29);
        // Worker/Agent e alocacao para receber eventos; falha com exit_code e error_summary-marcador
        UUID worker = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID waa = UUID.randomUUID();
        UUID wwa = UUID.randomUUID();
        DB.asSuperuser(
                "INSERT INTO workers (worker_id, organization_id, name, status) VALUES ('" + worker + "', " + o + ", 'W', 1)",
                "INSERT INTO agents (agent_id, organization_id, name, agent_version, status) VALUES ('" + agent + "', " + o + ", 'A', '1.0.0', 2)",
                "INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id) VALUES ('"
                        + waa + "', " + o + ", '" + worker + "', '" + agent + "', 1, '" + ADMIN_MEMBERSHIP + "')",
                "INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('"
                        + wwa + "', " + o + ", '" + worker + "', '" + WS1 + "', 1, '" + ADMIN_MEMBERSHIP + "')",
                "UPDATE executions SET status = 2, worker_id = '" + worker + "', worker_agent_assignment_id = '" + waa + "', worker_workspace_assignment_id = '"
                        + wwa + "', reserved_at = clock_timestamp() WHERE execution_id = '" + execWithEvents + "'",
                "UPDATE executions SET status = 3, dispatched_at = clock_timestamp(), started_at = clock_timestamp() WHERE execution_id = '" + execWithEvents + "'",
                "UPDATE executions SET status = 5, status_reason = 3, finished_at = clock_timestamp() + interval '2 seconds', exit_code = 97, "
                        + "error_summary = 'MARCADOR_ERROR_SUMMARY' WHERE execution_id = '" + execWithEvents + "'",
                "BEGIN; SET LOCAL nekkora.organization_id = " + o + "; "
                        + "INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint) VALUES ("
                        + o + ", '" + WS1 + "', '" + execWithEvents + "', '" + agent + "', 2, 3, now(), '{\"schema_version\":1,\"exit_code\":97,\"error_summary\":\"MARCADOR_PAYLOAD\"}', repeat('a', 64)); "
                        + "INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint) VALUES ("
                        + o + ", '" + WS1 + "', '" + execWithEvents + "', '" + agent + "', 1, 1, now(), '{\"schema_version\":1,\"pid\":7}', repeat('b', 64)); COMMIT");
    }

    static UUID createAutomation(UUID ws) {
        UUID a = UUID.randomUUID();
        UUID v = UUID.randomUUID();
        DB.asSuperuser(
                "INSERT INTO automations (automation_id, organization_id, workspace_id, name, description, status, created_by_membership_id) VALUES ('" + a + "', '"
                        + ORG + "', '" + ws + "', 'Automacao', 'MARCADOR_DESCRICAO_AUTOMACAO', 1, '" + ADMIN_MEMBERSHIP + "')",
                "INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) VALUES ('"
                        + v + "', '" + ORG + "', '" + ws + "', '" + a + "', 1, jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','v1','sha256',repeat('a',64)),"
                        + "'runtime','python3.11','entrypoint','main.py','arguments','[\"MARCADOR_ARGUMENTO\"]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + ADMIN_MEMBERSHIP + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + ADMIN_MEMBERSHIP + "' WHERE automation_version_id = '" + v + "'");
        return a;
    }

    static UUID execution(UUID ws, UUID automation, String created) {
        UUID e = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO executions (execution_id, organization_id, workspace_id, automation_id, automation_version_id, request_key, "
                + "request_fingerprint, requested_by_membership_id, command_snapshot, status, origin, created_at) SELECT '" + e + "', '" + ORG + "', '" + ws
                + "', '" + automation + "', v.automation_version_id, 'req-" + e + "', repeat('b', 64), '" + ADMIN_MEMBERSHIP + "', v.command_json, 1, 1, " + created
                + " FROM automation_versions v WHERE v.automation_id = '" + automation + "'");
        return e;
    }

    /** Conta externa do cliente CLI com concessao nos workspaces dados, fator confirmado e sessao com marcador de MFA. */
    static String externalSession(UUID... workspaces) {
        UUID u = UUID.randomUUID();
        UUID cm = UUID.randomUUID();
        String token = Tokens.newToken();
        List<String> sql = new ArrayList<>(List.of(
                "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + u + "', '" + ORG
                        + "', 2, 'x-" + u.toString().substring(0, 8) + "@c.test', now(), 2, 'MARCADOR_NOME')",
                "INSERT INTO client_memberships (client_membership_id, organization_id, client_id, user_id, account_type, status) VALUES ('" + cm + "', '" + ORG
                        + "', '" + CLIENT + "', '" + u + "', 2, 2)",
                "INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, confirmed_at) VALUES ('" + ORG + "', '" + u
                        + "', 1, decode(repeat('ab', 36), 'hex'), decode(repeat('01', 12), 'hex'), 'k1', now())",
                "INSERT INTO user_sessions (organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, mfa_verified_at, csrf_token) VALUES ('"
                        + ORG + "', '" + u + "', '" + Tokens.sha256Hex(token) + "', now() - interval '5 minutes', now() + interval '11 hours', now() - interval '3 minutes', now(), '"
                        + Tokens.newToken() + "')"));
        for (UUID ws : workspaces) {
            sql.add("INSERT INTO client_workspace_grants (organization_id, client_id, client_membership_id, workspace_id, status, approved_by_membership_id, approved_at, "
                    + "starts_at, next_review_at) VALUES ('" + ORG + "', '" + CLIENT + "', '" + cm + "', '" + ws + "', 2, '" + ADMIN_MEMBERSHIP + "', now() - interval '1 day', "
                    + "now() - interval '1 day', now() + interval '30 days')");
        }
        DB.asSuperuserIn(ORG, sql.toArray(String[]::new));
        return token;
    }

    BrowserClient browser(String token) {
        BrowserClient n = new BrowserClient("http://localhost:" + port);
        n.origin = null;
        n.forwardedIp = "10.50." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250));
        if (token != null) {
            n.cookies().put("__Host-nekkora_session", token);
        }
        return n;
    }

    String base() {
        return "/portal/api/v1/o/" + ORG;
    }

    @Test
    void workspacesAndExecutionsOmitWorkspaceIdWhenSingle() {
        BrowserClient first = browser(externalSession(WS1));
        BrowserClient.Response ws = first.get(base() + "/workspaces");
        assertThat(ws.status()).isEqualTo(200);
        assertThat(ws.body()).contains(WS1.toString()).doesNotContain(WS2.toString()).doesNotContain(WS3.toString());
        BrowserClient.Response ex = first.get(base() + "/executions");
        assertThat(ex.status()).isEqualTo(200);
        assertThat(ex.body()).as("um so workspace concedido: workspaceId omitido (11.5, decisao 5)").doesNotContain("workspaceId");
        BrowserClient second = browser(externalSession(WS1, WS2));
        assertThat(second.get(base() + "/executions").body()).as("dois workspaces: workspaceId presente").contains("\"workspaceId\":\"" + WS1 + "\"");
    }

    @Test
    void keysetPaginationStableWithTiesNoDuplicatesOrGaps() {
        BrowserClient n = browser(externalSession(WS1));
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            BrowserClient.Response r = n.get(base() + "/executions?limit=7" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(r.status()).isEqualTo(200);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"executionId\":\"([0-9a-f-]{36})\"").matcher(r.body());
            while (m.find()) {
                ids.add(m.group(1));
            }
            cursor = r.field("nextCursor");
            pages++;
        } while (cursor != null && pages < 20);
        assertThat(ids).as("30 execucoes, cada uma uma vez").hasSize(30).doesNotHaveDuplicates();
        assertThat(ids).containsExactlyInAnyOrderElementsOf(EXEC_WS1.stream().map(UUID::toString).toList());
        List<String> order = queryColumn("SELECT string_agg(execution_id::text, ',' ORDER BY created_at DESC, execution_id DESC) FROM executions WHERE workspace_id = '" + WS1 + "'");
        assertThat(String.join(",", ids)).as("ordem created_at DESC, execution_id DESC atravessando empates").isEqualTo(order.get(0));
    }

    @Test
    void parameterAndFilterValidation() {
        BrowserClient n = browser(externalSession(WS1));
        assertThat(n.get(base() + "/executions?limit=100").status()).isEqualTo(200);
        assertThat(n.get(base() + "/executions?limit=101").status()).isEqualTo(400);
        assertThat(n.get(base() + "/executions?limit=0").status()).isEqualTo(400);
        assertThat(n.get(base() + "/executions?cursor=lixo").status()).isEqualTo(400);
        assertThat(n.get(base() + "/executions?status=inexistente").status()).isEqualTo(400);
        assertThat(n.get(base() + "/executions?from=2026-01-01T00:00:00Z&to=2027-06-01T00:00:00Z").status()).as("periodo > 366 dias").isEqualTo(400);
        assertThat(n.get(base() + "/executions?workspaceId=" + WS3).body()).as("filtro fora da concessao so restringe").isEqualTo("{\"items\":[],\"nextCursor\":null}");
        BrowserClient.Response failures = n.get(base() + "/executions?status=failed");
        assertThat(failures.body()).contains(execWithEvents.toString()).contains("\"statusReason\":\"process_failed\"");
        assertThat(n.get(base() + "/executions?status=pending&limit=100").body()).doesNotContain(execWithEvents.toString());
    }

    @Test
    void detailEventsAndAllowlistedCatalog() {
        BrowserClient n = browser(externalSession(WS1));
        BrowserClient.Response d = n.get(base() + "/executions/" + execWithEvents);
        assertThat(d.status()).isEqualTo(200);
        assertThat(d.body()).contains("\"status\":\"failed\"").contains("\"durationMs\":").contains("\"automation\":");
        BrowserClient.Response ev = n.get(base() + "/executions/" + execWithEvents + "/events");
        assertThat(ev.body()).matches(".*\"type\":\"process_started\".*\"type\":\"process_failed\".*").as("ordem por sequence");
        for (BrowserClient.Response r : List.of(d, ev, n.get(base() + "/executions?limit=100"), n.get(base() + "/workspaces"))) {
            assertThat(r.body()).doesNotContain("MARCADOR").doesNotContain(ORG.toString()).doesNotContain(CLIENT.toString())
                    .doesNotContain(ADMIN_MEMBERSHIP.toString()).doesNotContain("@c.test");
            java.util.Set<String> keys = new java.util.TreeSet<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([A-Za-z]+)\":").matcher(r.body());
            while (m.find()) {
                keys.add(m.group(1));
            }
            assertThat(keys).as("so chaves da allowlist 11.5 (nada de exit_code, error_summary, payload, IDs internos)")
                    .isSubsetOf("items", "nextCursor", "workspaceId", "name", "executionId", "automation", "id", "createdAt", "startedAt",
                            "finishedAt", "durationMs", "status", "statusReason", "type", "receivedAt");
        }
        assertThat(n.get(base() + "/executions/" + execWs3).status()).as("execucao de outro cliente: 404 sem revelar").isEqualTo(404);
        assertThat(n.get(base() + "/executions/" + UUID.randomUUID()).status()).as("inexistente: mesma resposta").isEqualTo(404);
        assertThat(n.get(base() + "/executions/" + execWs3 + "/events").body()).isEqualTo("{\"items\":[]}");
    }

    @Test
    void invalidSessionNk401WithoutCookieAndGetOnly() throws Exception {
        String token = externalSession(WS1);
        BrowserClient n = browser(token);
        assertThat(n.get(base() + "/workspaces").status()).isEqualTo(200);
        String seen = queryColumn("SELECT last_seen_at::text FROM user_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(token) + "'").get(0);
        DB.asSuperuser("UPDATE user_sessions SET revoked_at = now() WHERE session_token_hash = '" + Tokens.sha256Hex(token) + "'");
        BrowserClient.Response r = n.get(base() + "/workspaces");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).isEqualTo("{\"error\":\"unauthenticated\"}");
        assertThat(queryColumn("SELECT last_seen_at::text FROM user_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(token) + "'").get(0))
                .as("NK401 nao toca last_seen_at").isEqualTo(seen);
        assertThat(browser(null).get(base() + "/workspaces").status()).isEqualTo(401);
        assertThat(browser(Tokens.newToken()).get("/portal/api/v1/o/" + UUID.randomUUID() + "/workspaces").status()).isEqualTo(401);
        assertThat(browser(token).post(base() + "/workspaces", null).status()).as("portal so aceita GET").isEqualTo(404);
    }

    @Test
    void activityTouchThroughFunction() {
        String token = externalSession(WS1);
        String before = queryColumn("SELECT last_seen_at::text FROM user_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(token) + "'").get(0);
        browser(token).get(base() + "/executions?workspaceId=" + WS3);
        String after = queryColumn("SELECT last_seen_at::text FROM user_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(token) + "'").get(0);
        assertThat(after).as("sessao valida com recurso fora do escopo: toque confirmado (transacao de escrita)").isNotEqualTo(before);
    }

    /**
     * As duas funcoes de uma requisicao de execucao usam a MESMA conexao e a MESMA transacao: o JdbcTemplate e o
     * gerenciador de transacoes estao sobre o mesmo DataSource (sem isso, o JdbcTemplate pegaria outra conexao do pool
     * dentro do TransactionTemplate), e uma chamada ao servico retira do pool uma unica conexao (o wrapper de reset
     * conta cada devolucao). As duas chamadas estao dentro de um unico tx.withoutContext (TransactionTemplate).
     */
    @Test
    void executionRequestUsesOneConnectionForBothFunctionCalls() throws Exception {
        assertThat(jdbcTemplate.getDataSource()).as("JdbcTemplate sobre o DataSource do gerenciador de transacoes")
                .isSameAs(((DataSourceTransactionManager) transactionManager).getDataSource()).isSameAs(resetDataSource);
        String token = externalSession(WS1);
        long before = resetDataSource.resets();
        portalService.executions(ORG, token, new PortalService.ExecutionFilter(null, null, null, null, null, 25));
        assertThat(resetDataSource.resets() - before).as("lista de execucoes: duas funcoes, uma conexao").isEqualTo(1);
        before = resetDataSource.resets();
        portalService.execution(ORG, token, EXEC_WS1.get(0));
        assertThat(resetDataSource.resets() - before).as("detalhe: duas funcoes, uma conexao").isEqualTo(1);
    }

    /**
     * Requisicao de execucao = DUAS funcoes da 013 na mesma transacao (portal_list_workspaces + a da rota). O toque
     * continua no maximo 1 por minuto: a primeira resolucao toca; a segunda, na mesma transacao, ja ve last_seen_at
     * novo. Instrumentacao DECLARADA, so na base descartavel do teste: trigger AFTER UPDATE OF last_seen_at que conta
     * as atualizacoes (criado e removido pelo superusuario).
     */
    @Test
    void activityTouchAtMostOncePerMinuteWithTwoFunctionCallsPerRequest() throws Exception {
        String token = externalSession(WS1);
        String hash = Tokens.sha256Hex(token);
        DB.asSuperuser("CREATE TABLE IF NOT EXISTS public.teste_toques (session_token_hash TEXT NOT NULL, em TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp())",
                "CREATE OR REPLACE FUNCTION public.teste_conta_toque() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS "
                        + "$f$ BEGIN INSERT INTO public.teste_toques (session_token_hash) VALUES (NEW.session_token_hash); RETURN NEW; END $f$",
                "CREATE TRIGGER teste_conta_toque AFTER UPDATE OF last_seen_at ON public.user_sessions FOR EACH ROW "
                        + "WHEN (OLD.last_seen_at IS DISTINCT FROM NEW.last_seen_at) EXECUTE FUNCTION public.teste_conta_toque()");
        try {
            String touches = "SELECT count(*)::text FROM public.teste_toques WHERE session_token_hash = '" + hash + "'";
            BrowserClient b = browser(token);
            assertThat(b.get(base() + "/executions").status()).isEqualTo(200);
            assertThat(queryColumn(touches).get(0)).as("uma requisicao de execucao (duas funcoes): um toque").isEqualTo("1");
            assertThat(b.get(base() + "/executions/" + EXEC_WS1.get(0)).status()).isEqualTo(200);
            assertThat(b.get(base() + "/workspaces").status()).isEqualTo(200);
            assertThat(queryColumn(touches).get(0)).as("requisicoes seguintes no mesmo minuto: nenhum toque novo").isEqualTo("1");
        } finally {
            DB.asSuperuser("DROP TRIGGER IF EXISTS teste_conta_toque ON public.user_sessions", "DROP FUNCTION IF EXISTS public.teste_conta_toque()",
                    "DROP TABLE IF EXISTS public.teste_toques");
        }
    }

    @Test
    void logParameterMaxLengthOnErrorPinnedCheckedAndRestoredByDiscardAll() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(queryValue(st, "SELECT current_user")).isEqualTo("nekkora_portal");
            assertThat(queryValue(st, "SHOW log_parameter_max_length_on_error")).isEqualTo("0");
            st.execute("SET log_parameter_max_length_on_error = -1");
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            assertThat(queryValue(st, "SHOW log_parameter_max_length_on_error")).as("DISCARD ALL volta ao valor de inicio da conexao").isEqualTo("0");
        }
        HikariConfig wrong = new HikariConfig();
        wrong.setJdbcUrl(DB.url());
        wrong.setUsername("nekkora_portal");
        wrong.setPassword(DB.password("nekkora_portal"));
        wrong.addDataSourceProperty("options", "-c log_parameter_max_length_on_error=100");
        wrong.setInitializationFailTimeout(-1);
        try (HikariDataSource ds = new HikariDataSource(wrong)) {
            assertThatThrownBy(() -> PortalConfig.verifyAtStartup(ds, "nekkora_portal")).as("o portal recusa iniciar")
                    .hasMessageContaining("log_parameter_max_length_on_error diferente de 0");
        }
        wrong.setConnectionInitSql(PortalConfig.LOG_CHECK_SQL);
        wrong.setPoolName("errado2");
        wrong.setInitializationFailTimeout(1);
        assertThatThrownBy(() -> new HikariDataSource(wrong).close()).as("cada conexao fisica e conferida na criacao");
    }

    @Test
    void tokenNotInProcessOrPostgresLogs(CapturedOutput output) {
        String valid = externalSession(WS1);
        String sentinel = Tokens.newToken();
        browser(valid).get(base() + "/executions?limit=5");
        browser(valid).get(base() + "/executions?cursor=lixo");
        browser(sentinel).get(base() + "/workspaces");
        for (String t : List.of(valid, sentinel)) {
            assertThat(output.getAll()).doesNotContain(t);
            assertThat(DB.serverLog()).doesNotContain(t);
        }
    }

    static List<String> queryColumn(String sql) {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            List<String> r = new ArrayList<>();
            while (rs.next()) {
                r.add(rs.getString(1));
            }
            return r;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String queryValue(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
