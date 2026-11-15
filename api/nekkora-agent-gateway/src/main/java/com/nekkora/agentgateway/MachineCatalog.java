package com.nekkora.agentgateway;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.agentgateway.MachineAuth.Machine;
import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.db.OrgTransactions.Mode;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

/**
 * Catalogo de METADADOS da propria maquina (PLATFORM-AGENT-CATALOGO-001, T01; migration 036). So leitura.
 *
 * <p>Politica (docs/platform-agent-catalogo-001/t01-catalogo-da-maquina.md): organizacao, Worker e vinculo vem SO da
 * credencial autenticada (nada do cliente vale como autoridade). Uma automacao aparece se, e somente se, o Worker da maquina
 * esta ATIVO e tem, AGORA, ao menos uma autorizacao local vigente para ela (nao revogada, no prazo, com a autorizacao do
 * Worker no workspace ativa), em workspace e automacao ativos. Uma versao aparece se, e somente se, esta PUBLICADA e tem uma
 * dessas autorizacoes de pacote. E um SUBCONJUNTO autorizado: nao e a lista da organizacao, e a ausencia de um projeto nao
 * prova que ele nao existe. Rascunhos, versoes sem autorizacao vigente e automacoes so de outros Workers nunca aparecem.
 *
 * <p>Cada pagina e cada consulta direta autentica de novo e reaplica o filtro inteiro na mesma transacao: revogacao,
 * vencimento, retirada do workspace, Worker inativo ou vinculo encerrado valem para a leitura seguinte. O cursor e so uma
 * POSICAO (workspace, automacao), preso a organizacao, ao Worker e ao Agent da credencial; nunca e permissao. Sem foto
 * consistente entre paginas: uma mudanca concorrente pode tirar ou incluir uma automacao entre duas paginas.
 *
 * <p>Nao concede nada: consultar nao autoriza publicar, baixar, aprovar nem executar (as rotas desses atos conferem por si).
 * Nao grava nada (nem presenca).
 */
public final class MachineCatalog {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    /** Versoes por automacao (as de maior numero); {@code versionsTruncated} diz se havia mais. */
    public static final int MAX_VERSIONS = 20;
    static final String COVERAGE = "WORKER_AUTHORIZED_SUBSET";

    private static final Pattern CURSOR = Pattern.compile("[A-Za-z0-9_-]{1,400}");

    private final OrgTransactions tx;
    private final MachineAuth auth;

    public MachineCatalog(OrgTransactions tx, MachineAuth auth) {
        this.tx = tx;
        this.auth = auth;
    }

    public record Organization(UUID organizationId, String displayName) {
    }

    public record MachineRef(UUID agentId, UUID workerId) {
    }

    /**
     * Versao visivel. {@code versionNumber}: numero interno da Platform (inteiro, 1, 2, ...), nao e semver.
     * {@code packageVersion}: versao DECLARADA no manifesto do pacote externo (semver), nula quando o artefato nao a tem
     * (pacote do Studio ou referencia declarada). A impressao e a da autorizacao, pela regra informada.
     */
    public record Version(UUID automationVersionId, int versionNumber, OffsetDateTime publishedAt, String sourceKind, String packageFingerprintRule,
                          String packageFingerprint, String packageName, String packageVersion) {
    }

    /** Automacao visivel. {@code versions} vazio: visivel so por autorizacao de monitoramento (nenhuma versao elegivel). */
    public record Item(UUID workspaceId, String workspaceName, UUID automationId, String automationName, List<Version> versions,
                       boolean versionsTruncated) {
    }

    public record Page(String catalogVersion, OffsetDateTime observedAt, Organization organization, MachineRef machine, String coverage, List<Item> items,
                       String nextCursor) {
    }

    public record Single(String catalogVersion, OffsetDateTime observedAt, Organization organization, MachineRef machine, String coverage, Item item) {
    }

    private record Key(UUID workspace, UUID automation) {
    }

    // ------------------------------------------------------------------ atos

    /** Uma pagina do catalogo. {@code guard}: cota por maquina, aplicada depois da autenticacao (429 sem tocar o resto). */
    public Page page(MachineToken t, Integer limit, String cursor, Consumer<Machine> guard) {
        int n = limit == null ? DEFAULT_LIMIT : limit;
        if (n < 1 || n > MAX_LIMIT) {
            throw ApiException.invalid("limite");
        }
        // READ_WRITE so pela trava FOR SHARE da credencial (o PostgreSQL nao trava linha em transacao somente leitura); nada e gravado
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = authenticate(j, t, guard);
            Key after = cursor == null ? null : decode(cursor, m);
            OffsetDateTime now = now(j);
            JdbcClient.StatementSpec q = j.sql(ELIGIBLE + "SELECT DISTINCT e.workspace_id, e.automation_id FROM eligible e"
                    + (after == null ? "" : " WHERE (e.workspace_id, e.automation_id) > (?, ?)")
                    + " ORDER BY e.workspace_id, e.automation_id LIMIT ?").param(m.organization()).param(m.workerId());
            if (after != null) {
                q = q.param(after.workspace()).param(after.automation());
            }
            List<Key> keys = q.param(n + 1).query((rs, i) -> new Key(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list();
            boolean more = keys.size() > n;
            List<Key> shown = more ? keys.subList(0, n) : keys;
            List<Item> items = items(j, m, shown);
            String next = more ? encode(m, shown.get(n - 1)) : null;
            return new Page("1.0", now, organization(j, m), new MachineRef(m.agentId(), m.workerId()), COVERAGE, items, next);
        });
    }

    /** Consulta direta: a automacao, se visivel a esta maquina AGORA; senao 404 (mesmo corpo para outra organizacao ou inexistente). */
    public Single one(MachineToken t, UUID automationId, Consumer<Machine> guard) {
        return tx.inOrganization(t.organization(), Mode.READ_WRITE, j -> {
            Machine m = authenticate(j, t, guard);
            OffsetDateTime now = now(j);
            List<Key> keys = j.sql(ELIGIBLE + "SELECT DISTINCT e.workspace_id, e.automation_id FROM eligible e WHERE e.automation_id = ?")
                    .param(m.organization()).param(m.workerId()).param(automationId)
                    .query((rs, i) -> new Key(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list();
            if (keys.isEmpty()) {
                throw new ApiException(ApiError.NOT_FOUND, "catalogo_automacao");
            }
            return new Single("1.0", now, organization(j, m), new MachineRef(m.agentId(), m.workerId()), COVERAGE, items(j, m, keys).get(0));
        });
    }

    // ------------------------------------------------------------------ consultas

    /**
     * Autorizacoes locais VIGENTES do Worker (o mesmo criterio de GET /authorizations: nao revogada, no prazo, autorizacao do
     * Worker no workspace ativa), em workspace e automacao ATIVOS. Parametros: organizacao, Worker.
     */
    private static final String ELIGIBLE = """
            WITH eligible AS (
                SELECT z.workspace_id, z.automation_id, z.automation_version_id, z.source_kind, z.package_fingerprint_rule, z.package_fingerprint
                  FROM public.local_run_authorizations z
                  JOIN public.worker_workspace_assignments x ON x.organization_id = z.organization_id
                                                             AND x.assignment_id = z.worker_workspace_assignment_id AND x.status = 1
                  JOIN public.workspaces w ON w.organization_id = z.organization_id AND w.workspace_id = z.workspace_id AND w.status = 1
                  JOIN public.automations a ON a.organization_id = z.organization_id AND a.automation_id = z.automation_id AND a.status = 1
                 WHERE z.organization_id = ? AND z.worker_id = ? AND z.revoked_at IS NULL AND z.valid_until > clock_timestamp()
            )
            """;

    private static List<Item> items(JdbcClient j, Machine m, List<Key> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        String in = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
        JdbcClient.StatementSpec names = j.sql("""
                SELECT a.automation_id, a.name, w.name FROM public.automations a
                  JOIN public.workspaces w ON w.organization_id = a.organization_id AND w.workspace_id = a.workspace_id
                 WHERE a.organization_id = ? AND a.automation_id IN (""" + in + ")").param(m.organization());
        for (Key k : keys) {
            names = names.param(k.automation());
        }
        Map<UUID, String[]> named = new LinkedHashMap<>();
        names.query((rs, i) -> named.put(rs.getObject(1, UUID.class), new String[] {rs.getString(2), rs.getString(3)})).list();

        JdbcClient.StatementSpec vs = j.sql(ELIGIBLE + """
                SELECT DISTINCT ON (e.automation_id, v.version_number)
                       e.automation_id, v.automation_version_id, v.version_number, v.published_at, e.source_kind, e.package_fingerprint_rule,
                       e.package_fingerprint, art.package_name, art.package_version
                  FROM eligible e
                  JOIN public.automation_versions v ON v.organization_id = ? AND v.automation_version_id = e.automation_version_id AND v.status = 2
                  LEFT JOIN LATERAL (
                        SELECT aa.package_name, aa.package_version FROM public.automation_artifacts aa
                         WHERE aa.organization_id = v.organization_id AND aa.automation_version_id = v.automation_version_id
                           AND aa.fingerprint = e.package_fingerprint AND aa.fingerprint_rule = e.package_fingerprint_rule
                         ORDER BY aa.received_at LIMIT 1) art ON true
                 WHERE e.source_kind IN (1, 2) AND e.automation_id IN (""" + in + """
                )
                 ORDER BY e.automation_id, v.version_number DESC
                """).param(m.organization()).param(m.workerId()).param(m.organization());
        for (Key k : keys) {
            vs = vs.param(k.automation());
        }
        Map<UUID, List<Version>> versions = new LinkedHashMap<>();
        vs.query((rs, i) -> versions.computeIfAbsent(rs.getObject(1, UUID.class), x -> new ArrayList<>())
                .add(new Version(rs.getObject(2, UUID.class), rs.getInt(3), rs.getObject(4, OffsetDateTime.class), GatewayService.sourceKind(rs.getShort(5)),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9)))).list();

        List<Item> out = new ArrayList<>(keys.size());
        for (Key k : keys) {
            String[] nm = named.get(k.automation());
            List<Version> all = versions.getOrDefault(k.automation(), List.of());
            all = new ArrayList<>(all);
            all.sort((a, b) -> Integer.compare(b.versionNumber(), a.versionNumber()));
            boolean truncated = all.size() > MAX_VERSIONS;
            out.add(new Item(k.workspace(), nm == null ? null : nm[1], k.automation(), nm == null ? null : nm[0],
                    List.copyOf(truncated ? all.subList(0, MAX_VERSIONS) : all), truncated));
        }
        return out;
    }

    private Machine authenticate(JdbcClient j, MachineToken t, Consumer<Machine> guard) {
        Machine m = auth.authenticate(j, t);
        guard.accept(m);
        Short worker = j.sql("SELECT status FROM public.workers WHERE organization_id = ? AND worker_id = ?").param(m.organization()).param(m.workerId())
                .query(Short.class).optional().orElse(null);
        if (worker == null || worker != 1) {
            throw ApiException.forbidden("worker_inativo");
        }
        return m;
    }

    private static Organization organization(JdbcClient j, Machine m) {
        String name = j.sql("SELECT name FROM public.organizations WHERE organization_id = ?").param(m.organization()).query(String.class).single();
        return new Organization(m.organization(), name);
    }

    private static OffsetDateTime now(JdbcClient j) {
        return j.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single();
    }

    // ------------------------------------------------------------------ cursor (posicao, nunca permissao)

    static String encode(Machine m, Key k) {
        String raw = "1|" + m.organization() + "|" + m.workerId() + "|" + m.agentId() + "|" + k.workspace() + "|" + k.automation();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** Cursor ilegivel ou de outro vinculo (organizacao, Worker ou Agent): 400 sem dizer qual. */
    static Key decode(String cursor, Machine m) {
        if (!CURSOR.matcher(cursor).matches()) {
            throw ApiException.invalid("cursor");
        }
        try {
            String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split("\\|", -1);
            if (p.length != 6 || !"1".equals(p[0]) || !m.organization().toString().equals(p[1]) || !m.workerId().toString().equals(p[2])
                    || !m.agentId().toString().equals(p[3])) {
                throw ApiException.invalid("cursor");
            }
            return new Key(GatewayController.uuid(p[4]), GatewayController.uuid(p[5]));
        } catch (RuntimeException e) { // inclui o ApiException do uuid: sempre o mesmo motivo
            throw ApiException.invalid("cursor");
        }
    }
}
