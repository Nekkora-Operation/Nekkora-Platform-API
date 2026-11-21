package com.nekkora.portal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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

/**
 * Area do cliente (PLATFORM-FLUXO-002, 070/071): leituras pela SESSAO DE IDENTIDADE, so pelas funcoes {@code portal_client_*}. O
 * cliente ve so o DASHBOARD de execucao (indicadores e estado em linguagem simples) e os ARQUIVOS DE RETORNO autorizados; registros
 * tecnicos, maquinas, ambientes e detalhe de tentativa nao existem aqui (o banco nem expoe). Cada chamada reconfere, no banco, a
 * sessao, a conta de cliente ligada, o vinculo, o cliente, a organizacao e as concessoes vigentes por automacao (e maquina), com o
 * conteudo pedido: revogar, reduzir, suspender ou retirar barra a proxima consulta, mesmo com a sessao aberta. Traducao: NK401 -> 401;
 * NK403 -> 403; NK404 -> 404 (fora do escopo e inexistente respondem igual); 22023 -> 400.
 */
public final class PortalClientService {

    private static final Logger LOG = LoggerFactory.getLogger(PortalClientService.class);
    static final int MAX_PAGE = 100;
    static final int DEFAULT_PAGE = 25;
    static final Duration DEFAULT_PERIOD = Duration.ofDays(30);
    static final Duration MAX_PERIOD = Duration.ofDays(400);

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

    /** Periodo [de, ate): padrao, os ultimos 30 dias; no maximo 400 dias. */
    public record Period(OffsetDateTime from, OffsetDateTime to) {
        static Period of(String rawFrom, String rawTo) {
            OffsetDateTime to = rawTo == null ? OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(1) : instant(rawTo, "to");
            OffsetDateTime from = rawFrom == null ? to.minus(DEFAULT_PERIOD) : instant(rawFrom, "from");
            if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
                throw ApiException.invalid("periodo");
            }
            return new Period(from, to);
        }

        private static OffsetDateTime instant(String raw, String rule) {
            try {
                return OffsetDateTime.parse(raw);
            } catch (DateTimeParseException e) {
                throw ApiException.invalid(rule);
            }
        }
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

    /** Cards: o que foi compartilhado de cada automacao e, so com o dashboard, os indicadores dos ultimos 30 dias. */
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
            boolean dashboard = rs.getBoolean("can_view_dashboard");
            boolean files = rs.getBoolean("can_download_results");
            m.put("canViewDashboard", dashboard);
            m.put("canDownloadResults", files);
            if (dashboard) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("executionsLast30Days", rs.getLong("executions_30d"));
                d.put("succeededLast30Days", rs.getLong("succeeded_30d"));
                d.put("failedLast30Days", rs.getLong("failed_30d"));
                d.put("inProgress", rs.getLong("in_progress"));
                d.put("lastRequestedAt", rs.getObject("last_requested_at", OffsetDateTime.class));
                d.put("lastOutcome", rs.getString("last_outcome"));
                m.put("dashboard", d);
            } else {
                m.put("dashboard", null);
            }
            m.put("filesAvailable", files ? rs.getLong("files_available") : null);
            m.put("clientName", rs.getString("client_name"));
            return m;
        }));
    }

    public Map<String, Object> summary(UUID org, String token, UUID automation, Period p) {
        List<Map<String, Object>> r = call(() -> jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_client_summary(?, ?, ?, ?, ?)");
            ps.setObject(1, org);
            ps.setString(2, token);
            ps.setObject(3, automation);
            ps.setObject(4, p.from());
            ps.setObject(5, p.to());
            return ps;
        }, (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", p.from());
            m.put("to", p.to());
            m.put("total", rs.getLong("total"));
            m.put("succeeded", rs.getLong("succeeded"));
            m.put("failed", rs.getLong("failed"));
            m.put("inProgress", rs.getLong("in_progress"));
            m.put("notStarted", rs.getLong("not_started"));
            m.put("cancelled", rs.getLong("cancelled"));
            m.put("unconfirmed", rs.getLong("unconfirmed"));
            m.put("noErrorSignaled", rs.getLong("no_error_signaled"));
            m.put("lastRequestedAt", rs.getObject("last_requested_at", OffsetDateTime.class));
            m.put("lastOutcome", rs.getString("last_outcome"));
            return m;
        }));
        return r.get(0);
    }

    /** Execucoes do dashboard (sem maquina, origem ou tentativa), da mais recente, com cursor (instante, id). */
    public Map<String, Object> executions(UUID org, String token, UUID automation, Period p, String cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.invalid("limit");
        }
        String[] after = decode(cursor, 2);
        List<Map<String, Object>> items = call(() -> jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_client_list_executions(?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setObject(1, org);
            ps.setString(2, token);
            ps.setObject(3, automation);
            ps.setObject(4, p.from());
            ps.setObject(5, p.to());
            ps.setObject(6, after == null ? null : OffsetDateTime.parse(after[0]));
            ps.setObject(7, after == null ? null : UUID.fromString(after[1]));
            ps.setInt(8, limit + 1);
            return ps;
        }, (rs, n) -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", rs.getObject("id", UUID.class).toString());
            e.put("requestedAt", rs.getObject("requested_at", OffsetDateTime.class));
            e.put("startedAt", rs.getObject("started_at", OffsetDateTime.class));
            e.put("endedAt", rs.getObject("ended_at", OffsetDateTime.class));
            e.put("outcome", rs.getString("outcome"));
            Object files = rs.getObject("files");
            e.put("files", files == null ? null : ((Number) files).longValue());
            return e;
        }));
        String next = null;
        if (items.size() > limit) {
            Map<String, Object> last = items.get(limit - 1);
            next = encode(last.get("requestedAt").toString(), (String) last.get("id"));
            items = new ArrayList<>(items.subList(0, limit));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", items);
        r.put("nextCursor", next);
        return r;
    }

    /** Arquivos de retorno no escopo (todas as automacoes com arquivos, ou uma), por periodo da execucao e busca no nome. */
    public Map<String, Object> files(UUID org, String token, UUID automation, Period p, String query, String cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw ApiException.invalid("limit");
        }
        if (query != null && query.length() > 100) {
            throw ApiException.invalid("q");
        }
        String[] after = decode(cursor, 3);
        List<Map<String, Object>> items = call(() -> jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_client_list_files(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setObject(1, org);
            ps.setString(2, token);
            ps.setObject(3, automation);
            ps.setObject(4, p.from());
            ps.setObject(5, p.to());
            ps.setString(6, query == null || query.isBlank() ? null : query.strip());
            ps.setObject(7, after == null ? null : OffsetDateTime.parse(after[0]));
            ps.setObject(8, after == null ? null : UUID.fromString(after[1]));
            if (after == null) {
                ps.setNull(9, java.sql.Types.INTEGER);
            } else {
                ps.setInt(9, Integer.parseInt(after[2]));
            }
            ps.setInt(10, limit + 1);
            return ps;
        }, (rs, n) -> file(rs)));
        String next = null;
        if (items.size() > limit) {
            Map<String, Object> last = items.get(limit - 1);
            next = encode(last.get("executionRequestedAt").toString(), (String) last.get("attemptId"), String.valueOf(last.get("number")));
            items = new ArrayList<>(items.subList(0, limit));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", items);
        r.put("nextCursor", next);
        return r;
    }

    /** Bytes de um arquivo de retorno: escopo e permissao reconferidos no banco (mesma chamada), depois os bytes conferidos pelo SHA-256. */
    public Download download(UUID org, String token, UUID attempt, int number) {
        record Row(String fileName, String sha256, boolean available) {
        }
        Row file = call(() -> jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("SELECT * FROM public.portal_client_get_file(?, ?, ?, ?)");
            ps.setObject(1, org);
            ps.setString(2, token);
            ps.setObject(3, attempt);
            ps.setInt(4, number);
            return ps;
        }, (rs, n) -> new Row(rs.getString("file_name"), rs.getString("sha256"), rs.getBoolean("available")))).get(0);
        if (!file.available()) {
            throw new ApiException(ApiError.REMOTE_RESULT_UNAVAILABLE, "resultado_removido");
        }
        if (!results.available()) {
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento_de_resultados");
        }
        try {
            byte[] bytes = results.get(org, file.sha256()).orElseThrow(() -> new ApiException(ApiError.REMOTE_RESULT_UNAVAILABLE, "resultado_ausente"));
            LOG.info("evento=portal.cliente.resultado_baixado tentativa={} numero={} correlacao={}", attempt, number, RequestContext.correlation());
            return new Download(file.fileName(), file.sha256(), bytes);
        } catch (ArtifactStore.Corrupted e) {
            throw new ApiException(ApiError.REMOTE_RESULT_UNAVAILABLE, "resultado_corrompido");
        } catch (IOException e) {
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento_de_resultados");
        }
    }

    private static Map<String, Object> file(ResultSet rs) throws SQLException {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("attemptId", rs.getObject("attempt_id", UUID.class).toString());
        f.put("number", (int) rs.getShort("result_number"));
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("automationId", rs.getObject("automation_id", UUID.class).toString());
        a.put("name", rs.getString("automation_name"));
        f.put("automation", a);
        f.put("executionRequestedAt", rs.getObject("execution_requested_at", OffsetDateTime.class));
        String name = rs.getString("file_name");
        f.put("fileName", name.substring(name.lastIndexOf('/') + 1));
        f.put("sizeBytes", rs.getLong("size_bytes"));
        f.put("receivedAt", rs.getObject("received_at", OffsetDateTime.class));
        f.put("available", rs.getBoolean("available"));
        return f;
    }

    private static String encode(String... parts) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
    }

    /** Cursor opaco (o escopo e reconferido a cada pagina no banco); malformado: 400. */
    private static String[] decode(String cursor, int parts) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (p.length != parts) {
                throw new IllegalArgumentException();
            }
            OffsetDateTime.parse(p[0]);
            UUID.fromString(p[1]);
            if (parts == 3) {
                Integer.parseInt(p[2]);
            }
            return p;
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
