package com.nekkora.portal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.nekkora.core.artifact.ArtifactStore;
import com.nekkora.core.artifact.ResultStore;
import com.nekkora.core.db.DbErrors;
import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;

import tools.jackson.databind.json.JsonMapper;

/**
 * Area do cliente (PLATFORM-FLUXO-002, 070): leituras pela SESSAO DE IDENTIDADE, so pelas funcoes {@code portal_client_*}. Cada
 * chamada reconfere, no banco, a sessao (lookup da identidade), a conta de cliente ligada, o vinculo, o cliente, a organizacao e
 * as concessoes vigentes por automacao (e maquina): revogar, suspender ou retirar barra a proxima consulta, mesmo com a sessao
 * aberta. Nenhuma sessao de organizacao e usada ou criada. Traducao: NK401 -> 401; NK403 -> 403; NK404 -> 404 (fora do escopo e
 * inexistente respondem igual); 22023 -> 400.
 */
public final class PortalClientService {

    private static final Logger LOG = LoggerFactory.getLogger(PortalClientService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int MAX_PAGE = 100;
    static final int DEFAULT_PAGE = 25;
    static final int MAX_LOG_PAGE = 500;
    static final List<String> KINDS = List.of("platform", "local_agent", "local_request", "remote");
    private static final List<String> LOG_CHANNELS = List.of("STDOUT", "STDERR", "EXECUTOR");

    private final OrgTransactions tx;
    private final JdbcTemplate jdbc;
    private final ResultStore results;

    public PortalClientService(OrgTransactions tx, JdbcTemplate jdbc, ResultStore results) {
        this.tx = tx;
        this.jdbc = jdbc;
        this.results = results;
    }

    public record Download(String fileName, String sha256, byte[] bytes) {
    }

    public List<Map<String, Object>> organizations(String token) {
        return call(() -> jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_client_organizations(?)");
            ps.setMaxRows(201);
            ps.setString(1, token);
            return ps;
        }, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("organizationId", rs.getObject("organization_id", UUID.class).toString());
            m.put("organizationName", rs.getString("organization_name"));
            m.put("clientName", rs.getString("client_name"));
            m.put("sharedAutomations", rs.getLong("shared_automations"));
            return m;
        }));
    }

    public List<Map<String, Object>> automations(UUID org, String token) {
        return call(() -> jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_client_automations(?, ?)");
            ps.setMaxRows(501);
            ps.setObject(1, org);
            ps.setString(2, token);
            return ps;
        }, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("automationId", rs.getObject("automation_id", UUID.class).toString());
            m.put("name", rs.getString("automation_name"));
            m.put("workspaceName", rs.getString("workspace_name"));
            m.put("allMachines", rs.getBoolean("all_machines"));
            m.put("machines", JSON.readValue(rs.getString("machines"), Object.class));
            m.put("canReadLogs", rs.getBoolean("can_read_logs"));
            m.put("canDownloadResults", rs.getBoolean("can_download_results"));
            m.put("executionsLast30Days", rs.getLong("executions_30d"));
            m.put("lastRequestedAt", rs.getObject("last_requested_at", OffsetDateTime.class));
            m.put("lastOutcome", rs.getString("last_outcome"));
            m.put("clientName", rs.getString("client_name"));
            return m;
        }));
    }

    public Map<String, Object> executions(UUID org, String token, UUID automation, String cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.invalid("limit");
        }
        Object[] after = decode(cursor);
        List<Map<String, Object>> items = call(() -> jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_client_list_executions(?, ?, ?, ?, ?, ?)");
            ps.setObject(1, org);
            ps.setString(2, token);
            ps.setObject(3, automation);
            ps.setObject(4, after == null ? null : after[0]);
            ps.setObject(5, after == null ? null : after[1]);
            ps.setInt(6, limit + 1);
            return ps;
        }, (rs, n) -> execution(rs, false)));
        String next = null;
        if (items.size() > limit) {
            Map<String, Object> last = items.get(limit - 1);
            next = encode((OffsetDateTime) last.get("requestedAt"), UUID.fromString((String) last.get("id")));
            items = new ArrayList<>(items.subList(0, limit));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", items);
        r.put("nextCursor", next);
        return r;
    }

    public Map<String, Object> execution(UUID org, String token, String kind, UUID id) {
        if (!KINDS.contains(kind)) {
            throw new ApiException(ApiError.NOT_FOUND, "execucao");
        }
        List<Map<String, Object>> r = call(() -> jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_client_get_execution(?, ?, ?, ?)");
            ps.setObject(1, org);
            ps.setString(2, token);
            ps.setString(3, kind);
            ps.setObject(4, id);
            return ps;
        }, (rs, n) -> execution(rs, true)));
        return r.get(0);
    }

    public Map<String, Object> logs(UUID org, String token, UUID execution, UUID attempt, int after, int limit) {
        if (after < 0 || limit < 1 || limit > MAX_LOG_PAGE) {
            throw ApiException.invalid("pagina");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        call(() -> { jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_client_list_logs(?, ?, ?, ?, ?, ?)");
            ps.setObject(1, org);
            ps.setString(2, token);
            ps.setObject(3, execution);
            ps.setObject(4, attempt);
            ps.setInt(5, after);
            ps.setInt(6, limit);
            return ps;
        }, (ResultSet rs) -> {
            out.put("total", rs.getInt("total"));
            out.put("closedAt", rs.getObject("closed_at", OffsetDateTime.class));
            Object dropped = rs.getObject("dropped_lines");
            out.put("droppedLines", dropped == null ? null : ((Number) dropped).longValue());
            int seq = rs.getInt("sequence");
            if (!rs.wasNull()) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("sequence", seq);
                l.put("capturedAt", rs.getObject("captured_at", OffsetDateTime.class));
                l.put("channel", LOG_CHANNELS.get(rs.getShort("channel") - 1));
                l.put("truncated", rs.getBoolean("truncated"));
                l.put("message", rs.getString("message"));
                l.put("textRemoved", rs.getBoolean("text_removed"));
                lines.add(l);
            }
        }); return null; });
        out.put("items", lines);
        int total = out.get("total") == null ? 0 : (Integer) out.get("total");
        out.put("nextAfter", lines.size() == limit && (Integer) lines.get(lines.size() - 1).get("sequence") < total
                ? lines.get(lines.size() - 1).get("sequence") : null);
        return out;
    }

    public Map<String, Object> results(UUID org, String token, UUID execution, UUID attempt) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();
        call(() -> { jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_client_list_results(?, ?, ?, ?)");
            ps.setObject(1, org);
            ps.setString(2, token);
            ps.setObject(3, execution);
            ps.setObject(4, attempt);
            return ps;
        }, (ResultSet rs) -> {
            out.put("closedAt", rs.getObject("closed_at", OffsetDateTime.class));
            Object skipped = rs.getObject("skipped_by_limit");
            out.put("skippedByLimit", skipped == null ? null : ((Number) skipped).intValue());
            int number = rs.getInt("result_number");
            if (!rs.wasNull()) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("number", number);
                f.put("fileName", rs.getString("file_name"));
                f.put("sizeBytes", rs.getLong("size_bytes"));
                f.put("sha256", rs.getString("sha256"));
                f.put("receivedAt", rs.getObject("received_at", OffsetDateTime.class));
                f.put("available", rs.getBoolean("available"));
                files.add(f);
            }
        }); return null; });
        out.put("items", files);
        return out;
    }

    /** Bytes de um arquivo de resultado: a permissao e o escopo sao reconferidos pela listagem (mesma chamada), depois os bytes. */
    public Download download(UUID org, String token, UUID execution, UUID attempt, int number) {
        Map<String, Object> list = results(org, token, execution, attempt);
        @SuppressWarnings("unchecked")
        Map<String, Object> file = ((List<Map<String, Object>>) list.get("items")).stream().filter(f -> ((Integer) f.get("number")) == number)
                .findFirst().orElseThrow(() -> new ApiException(ApiError.NOT_FOUND, "resultado"));
        if (!Boolean.TRUE.equals(file.get("available"))) {
            throw new ApiException(ApiError.REMOTE_RESULT_UNAVAILABLE, "resultado_removido");
        }
        if (!results.available()) {
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento_de_resultados");
        }
        try {
            byte[] bytes = results.get(org, (String) file.get("sha256"))
                    .orElseThrow(() -> new ApiException(ApiError.REMOTE_RESULT_UNAVAILABLE, "resultado_ausente"));
            LOG.info("evento=portal.cliente.resultado_baixado tentativa={} numero={} correlacao={}", attempt, number, RequestContext.correlation());
            return new Download((String) file.get("fileName"), (String) file.get("sha256"), bytes);
        } catch (ArtifactStore.Corrupted e) {
            throw new ApiException(ApiError.REMOTE_RESULT_UNAVAILABLE, "resultado_corrompido");
        } catch (IOException e) {
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento_de_resultados");
        }
    }

    private static Map<String, Object> execution(ResultSet rs, boolean detail) throws SQLException {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", rs.getString("kind"));
        e.put("id", rs.getObject("id", UUID.class).toString());
        if (detail) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("automationId", rs.getObject("automation_id", UUID.class).toString());
            a.put("name", rs.getString("automation_name"));
            e.put("automation", a);
        }
        UUID worker = rs.getObject("worker_id", UUID.class);
        if (worker == null) {
            e.put("machine", null);
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("workerId", worker.toString());
            m.put("name", rs.getString("worker_name"));
            e.put("machine", m);
        }
        e.put("requestedAt", rs.getObject("requested_at", OffsetDateTime.class));
        e.put("startedAt", rs.getObject("started_at", OffsetDateTime.class));
        e.put("endedAt", rs.getObject("ended_at", OffsetDateTime.class));
        e.put("outcome", rs.getString("outcome"));
        if (detail) {
            e.put("attempts", JSON.readValue(rs.getString("attempts"), Object.class));
        }
        return e;
    }

    private static String encode(OffsetDateTime at, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((at.toString() + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** Cursor opaco "instante|id" (o escopo e reconferido a cada pagina no banco); malformado: 400. */
    private static Object[] decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            return new Object[] { OffsetDateTime.parse(p[0]), UUID.fromString(p[1]) };
        } catch (RuntimeException e) {
            throw ApiException.invalid("cursor");
        }
    }

    static void requireFormat(String token) {
        if (!Tokens.isValidFormat(token)) {
            throw ApiException.unauthenticated("sem_sessao_identidade");
        }
    }

    private <T> T call(java.util.function.Supplier<T> op) {
        try {
            return tx.withoutContext(j -> op.get());
        } catch (DataAccessException e) {
            String state = DbErrors.sqlState(e);
            switch (state == null ? "" : state) {
                case "NK401" -> throw ApiException.unauthenticated("nk401");
                case "NK403" -> throw ApiException.forbidden("sem_acesso_de_cliente");
                case "NK404" -> throw new ApiException(ApiError.NOT_FOUND, "fora_do_escopo");
                case "22023" -> throw ApiException.invalid("22023");
                default -> {
                    LOG.error("evento=portal.cliente.erro_banco sqlstate={} classe={} correlacao={}", state, e.getClass().getName(),
                            RequestContext.correlation());
                    throw new ApiException(ApiError.INTERNAL_ERROR, "banco");
                }
            }
        }
    }
}
