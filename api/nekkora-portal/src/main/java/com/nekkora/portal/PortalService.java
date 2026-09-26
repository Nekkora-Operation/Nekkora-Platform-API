package com.nekkora.portal;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.nekkora.core.db.DbErrors;
import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;

/**
 * Leitura externa (desenho da 8.2 e contrato 8.7, §5.4). Cada requisicao = UMA transacao READ COMMITTED de
 * ESCRITA (a 013 toca last_seen_at; readOnly faria a funcao falhar), sem nenhum GUC definido pela aplicacao,
 * chamando SO as funcoes publicas da 013 com o token bruto como parametro de bind. Nunca SQL concatenado.
 *
 * <p>Traducao: NK401 -> 401; 22023 -> 400; detalhe sem linha -> 404; lista vazia -> 200. Log: so SQLSTATE.
 *
 * <p>Limites de resposta v1: 200 workspaces e 1000 eventos; acima disso -> 500 + alerta, sem truncar. O
 * setMaxRows so limita transferencia e memoria no processo Java, nao o trabalho do PostgreSQL (§5.4).
 *
 * <p>Omissao de workspaceId quando o vinculo tem um so workspace concedido (§11.5, decisao 5): exige saber
 * quantos workspaces o vinculo tem - por isso as rotas de execucao chamam tambem portal_list_workspaces NA MESMA
 * transacao (duas funcoes da 013 por requisicao; ver relatorio).
 */
public final class PortalService {

    private static final Logger LOG = LoggerFactory.getLogger(PortalService.class);
    static final int MAX_WORKSPACES = 200;
    static final int MAX_EVENTS = 1000;
    static final int MAX_PAGE = 100;
    static final int DEFAULT_PAGE_SIZE = 25;

    private final OrgTransactions tx;
    private final JdbcTemplate jdbc;

    public PortalService(OrgTransactions tx, JdbcTemplate jdbc) {
        this.tx = tx;
        this.jdbc = jdbc;
    }

    public record ExecutionFilter(UUID workspace, short[] status, OffsetDateTime fromTime, OffsetDateTime toTime, Cursor cursor, int pageLimit) {
    }

    public List<Map<String, Object>> workspaces(UUID org, String token) {
        requireFormat(token);
        return translatingDbErrors(() -> tx.withoutContext(j -> listWorkspaces(org, token)));
    }

    public Map<String, Object> executions(UUID org, String token, ExecutionFilter f) {
        requireFormat(token);
        return translatingDbErrors(() -> tx.withoutContext(j -> {
            boolean omit = listWorkspaces(org, token).size() == 1;
            List<Map<String, Object>> items = jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "SELECT * FROM public.portal_list_executions(?, ?, ?, ?, ?, ?, ?, ?, ?)");
                ps.setObject(1, org);
                ps.setString(2, token);
                ps.setObject(3, f.workspace());
                if (f.status() == null) {
                    ps.setNull(4, Types.ARRAY);
                } else {
                    Short[] s = new Short[f.status().length];
                    for (int i = 0; i < s.length; i++) {
                        s[i] = f.status()[i];
                    }
                    ps.setArray(4, con.createArrayOf("int2", s));
                }
                ps.setObject(5, f.fromTime());
                ps.setObject(6, f.toTime());
                ps.setObject(7, f.cursor() == null ? null : f.cursor().createdAt());
                ps.setObject(8, f.cursor() == null ? null : f.cursor().execution());
                ps.setInt(9, f.pageLimit());
                return ps;
            }, execution(omit));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("items", items);
            r.put("nextCursor", items.size() == f.pageLimit() ? cursorOf(items.get(items.size() - 1)) : null);
            return r;
        }));
    }

    public Map<String, Object> execution(UUID org, String token, UUID id) {
        requireFormat(token);
        List<Map<String, Object>> r = translatingDbErrors(() -> tx.withoutContext(j -> {
            boolean omit = listWorkspaces(org, token).size() == 1;
            return jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_get_execution(?, ?, ?)");
                ps.setObject(1, org);
                ps.setString(2, token);
                ps.setObject(3, id);
                return ps;
            }, execution(omit));
        }));
        if (r.isEmpty()) {
            throw new ApiException(ApiError.NOT_FOUND, "execucao");
        }
        return r.get(0);
    }

    public List<Map<String, Object>> events(UUID org, String token, UUID id) {
        requireFormat(token);
        List<Map<String, Object>> r = translatingDbErrors(() -> tx.withoutContext(j -> jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_list_execution_events(?, ?, ?)");
            ps.setMaxRows(MAX_EVENTS + 1);
            ps.setObject(1, org);
            ps.setString(2, token);
            ps.setObject(3, id);
            return ps;
        }, (rs, n) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", Labels.event(rs.getInt("event_type")));
            e.put("receivedAt", rs.getObject("received_at", OffsetDateTime.class));
            return e;
        })));
        if (r.size() > MAX_EVENTS) {
            throw overLimit("eventos");
        }
        return r;
    }

    private List<Map<String, Object>> listWorkspaces(UUID org, String token) {
        List<Map<String, Object>> r = jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_list_workspaces(?, ?)");
            ps.setMaxRows(MAX_WORKSPACES + 1);
            ps.setObject(1, org);
            ps.setString(2, token);
            return ps;
        }, (rs, n) -> {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("workspaceId", rs.getObject("workspace_id", UUID.class).toString());
            w.put("name", rs.getString("name"));
            return w;
        });
        if (r.size() > MAX_WORKSPACES) {
            throw overLimit("workspaces");
        }
        return r;
    }

    private static RowMapper<Map<String, Object>> execution(boolean omitWorkspaceId) {
        return (ResultSet rs, int n) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("executionId", rs.getObject("execution_id", UUID.class).toString());
            if (!omitWorkspaceId) {
                e.put("workspaceId", rs.getObject("workspace_id", UUID.class).toString());
            }
            e.put("automation", Map.of("id", rs.getObject("automation_id", UUID.class).toString(), "name", rs.getString("automation_name")));
            OffsetDateTime created = rs.getObject("created_at", OffsetDateTime.class);
            OffsetDateTime startedAt = rs.getObject("started_at", OffsetDateTime.class);
            OffsetDateTime end = rs.getObject("finished_at", OffsetDateTime.class);
            e.put("createdAt", created);
            e.put("startedAt", startedAt);
            e.put("finishedAt", end);
            e.put("durationMs", startedAt != null && end != null ? Duration.between(startedAt, end).toMillis() : null);
            e.put("status", Labels.status(rs.getInt("status")));
            int reason = rs.getInt("status_reason");
            e.put("statusReason", rs.wasNull() ? null : Labels.reason(reason));
            return e;
        };
    }

    private static String cursorOf(Map<String, Object> last) {
        return new Cursor((OffsetDateTime) last.get("createdAt"), UUID.fromString((String) last.get("executionId"))).encode();
    }

    private static void requireFormat(String token) {
        if (!Tokens.isValidFormat(token)) {
            throw ApiException.unauthenticated("sem_sessao");
        }
    }

    private ApiException overLimit(String listField) {
        LOG.error("evento=portal.list_limit_exceeded lista={} correlacao={}", listField, RequestContext.correlation());
        return new ApiException(ApiError.INTERNAL_ERROR, "list_limit_exceeded");
    }

    private static <T> T translatingDbErrors(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (DataAccessException e) {
            String state = DbErrors.sqlState(e);
            if ("NK401".equals(state)) {
                throw ApiException.unauthenticated("nk401");
            }
            if ("22023".equals(state)) {
                throw ApiException.invalid("22023");
            }
            LOG.error("evento=portal.erro_banco sqlstate={} classe={} correlacao={}", state, e.getClass().getName(), RequestContext.correlation());
            throw new ApiException(ApiError.INTERNAL_ERROR, "banco");
        } catch (RuntimeException e) {
            if (e instanceof ApiException a) {
                throw a;
            }
            Throwable c = e.getCause();
            if (c instanceof SQLException) {
                throw new ApiException(ApiError.INTERNAL_ERROR, "banco");
            }
            throw e;
        }
    }
}
