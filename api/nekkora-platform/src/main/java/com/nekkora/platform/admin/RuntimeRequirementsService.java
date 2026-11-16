package com.nekkora.platform.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.platform.session.ValidSession;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.artifact.RequirementsText;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Dependencias de execucao DECLARADAS na Platform para uma automacao (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c parte 2).
 * <ul>
 * <li>a leitura e ESTATICA ({@link RequirementsText}, as mesmas regras do aplicativo): so {@code nome==versao} entra; uma
 * linha que nao se representa e dita por linha e IMPEDE salvar. Nada e instalado, resolvido ou baixado;</li>
 * <li>cada declaracao e uma REVISAO nova (append-only), com quem declarou e o hash da lista;</li>
 * <li>o EFETIVO e o manifesto do pacote publicado mais os acrescimos declarados. A mesma biblioteca com versao (ou extras)
 * diferente da do manifesto e CONFLITO: fica dito, e o manifesto nunca e enfraquecido nem substituido.</li>
 * </ul>
 * Declarar nao muda o pacote publicado, nao verifica maquina nenhuma e nao autoriza execucao.
 */
public class RuntimeRequirementsService {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeRequirementsService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> SOURCES = List.of("PACKAGE", "FILE", "TEXT", "NONE_CONFIRMED");
    static final int MAX_DEPENDENCIES = 200;
    static final int ACT_REQUIREMENTS_DECLARED = 10;

    /** Uma linha lida do texto. {@code problem}: impede salvar ate ser ajustada. */
    public record Line(int number, String text, String kind, boolean problem, String dependency) {
    }

    /** O que a leitura estatica de um texto encontrou. Nada foi gravado. */
    public record Analysis(List<Line> lines, List<String> dependencies, boolean resolved) {
    }

    /** A revisao vigente da declaracao. */
    public record Declared(int revision, String source, List<String> dependencies, String contentSha256, OffsetDateTime declaredAt) {
    }

    /** As dependencias do manifesto da versao publicada mais recente. */
    public record Published(UUID automationVersionId, int versionNumber, List<String> dependencies) {
    }

    /** A mesma biblioteca declarada com versao ou extras diferentes dos do manifesto. O manifesto prevalece. */
    public record Conflict(String name, String published, String declared) {
    }

    /** {@code resolved}: sem conflito. So um efetivo resolvido, com uma declaracao feita, pode ser usado numa execucao remota. */
    public record Effective(List<String> dependencies, List<Conflict> conflicts, boolean resolved) {
    }

    /** {@code declared} nulo: ninguem declarou ainda. {@code published} nulo: a automacao nao tem versao publicada. */
    public record View(UUID automationId, Declared declared, Published published, Effective effective) {
    }

    private final AccessActions actions;
    private final WorkspaceAccess access;

    public RuntimeRequirementsService(AccessActions actions, WorkspaceAccess access) {
        this.actions = actions;
        this.access = access;
    }

    /** O que esta declarado, o que o pacote publicado declara e o efetivo. Exige automation.read no ambiente da automacao. */
    public View get(UUID org, ValidSession actor, String token, UUID automation) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireAutomation(j, org, actor.membershipId(), automation, "automation.read");
            return view(j, org, automation);
        });
    }

    /** Le um texto no formato do requirements.txt e diz, por linha, o que e. Nada e gravado. Exige automation.manage. */
    public Analysis analyze(UUID org, ValidSession actor, String token, UUID automation, String text) {
        if (text == null || text.length() > RequirementsText.MAX_CHARS) {
            throw ApiException.invalid("texto");
        }
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireAutomation(j, org, actor.membershipId(), automation, "automation.manage");
            return analysis(RequirementsText.parse(text));
        });
    }

    /**
     * Declara as dependencias: cria a revisao seguinte. {@code source} PACKAGE ou NONE_CONFIRMED: sem texto (nada e
     * acrescentado). FILE ou TEXT: o texto e lido; qualquer linha com problema recusa a declaracao inteira (409
     * runtime_requirements_unresolved) e nada e gravado. {@code expectedRevision}: a revisao que a pessoa viu (nulo = nenhuma);
     * se outra pessoa declarou nesse meio tempo, 409 runtime_requirements_changed.
     */
    public View declare(UUID org, ValidSession actor, String token, UUID automation, String source, String text, Integer expectedRevision) {
        if (source == null || !SOURCES.contains(source)) {
            throw ApiException.invalid("origem");
        }
        boolean withText = source.equals("FILE") || source.equals("TEXT");
        if (withText != (text != null) || (text != null && text.length() > RequirementsText.MAX_CHARS)) {
            throw ApiException.invalid("texto");
        }
        List<String> dependencies = List.of();
        if (withText) {
            RequirementsText.Parsed parsed = RequirementsText.parse(text);
            if (!parsed.resolved() || parsed.dependencies().size() > MAX_DEPENDENCIES) {
                throw new ApiException(ApiError.RUNTIME_REQUIREMENTS_UNRESOLVED, "linhas_com_problema");
            }
            dependencies = parsed.dependencies();
        }
        List<String> added = dependencies;
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            UUID workspace = access.requireAutomation(j, org, actor.membershipId(), automation, "automation.manage");
            actions.lockOrganizationAndActor(j, org, actor.userId());
            // a automacao e a trava da sequencia de revisoes
            j.sql("SELECT 1 FROM public.automations WHERE organization_id = ? AND automation_id = ? FOR UPDATE").param(org).param(automation).query(Integer.class).single();
            int current = j.sql("SELECT coalesce(max(revision_number), 0) FROM public.automation_runtime_requirements WHERE organization_id = ? AND automation_id = ?")
                    .param(org).param(automation).query(Integer.class).single();
            if ((expectedRevision == null ? 0 : expectedRevision) != current) {
                throw new ApiException(ApiError.RUNTIME_REQUIREMENTS_CHANGED, "revisao_mudou");
            }
            ArrayNode array = JSON.createArrayNode();
            added.forEach(array::add);
            j.sql("INSERT INTO public.automation_runtime_requirements (automation_id, revision_number, organization_id, workspace_id, source, dependencies, "
                            + "content_sha256, declared_by_membership_id) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)")
                    .param(automation).param(current + 1).param(org).param(workspace).param((short) (SOURCES.indexOf(source) + 1)).param(array.toString())
                    .param(sha256(source + "\n" + String.join("\n", added))).param(actor.membershipId()).update();
            j.sql("INSERT INTO public.automation_admin_events (organization_id, act, actor_membership_id, workspace_id, automation_id, correlation_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?)").param(org).param((short) ACT_REQUIREMENTS_DECLARED).param(actor.membershipId()).param(workspace).param(automation)
                    .param(AccessActions.correlation()).update();
            LOG.info("evento=automacao.dependencias_declaradas automacao={} revisao={} origem={} acrescimos={}", automation, current + 1, source, added.size());
            return view(j, org, automation);
        });
    }

    private static Analysis analysis(RequirementsText.Parsed p) {
        List<Line> lines = new ArrayList<>();
        for (RequirementsText.Line l : p.lines()) {
            lines.add(new Line(l.number(), l.text(), l.kind().name(), l.kind().problem(), l.dependency()));
        }
        return new Analysis(lines, p.dependencies(), p.resolved() && p.dependencies().size() <= MAX_DEPENDENCIES);
    }

    private static View view(JdbcClient j, UUID org, UUID automation) {
        Declared declared = j.sql("SELECT revision_number, source, dependencies::text, content_sha256, declared_at FROM public.automation_runtime_requirements "
                        + "WHERE organization_id = ? AND automation_id = ? ORDER BY revision_number DESC LIMIT 1").param(org).param(automation)
                .query((rs, n) -> new Declared(rs.getInt(1), SOURCES.get(rs.getShort(2) - 1), strings(rs.getString(3)), rs.getString(4),
                        rs.getObject(5, OffsetDateTime.class))).optional().orElse(null);
        Published published = j.sql("SELECT v.automation_version_id, v.version_number, coalesce(a.dependencies::text, '[]') FROM public.automation_versions v "
                        + "LEFT JOIN public.automation_artifacts a ON a.organization_id = v.organization_id AND a.automation_version_id = v.automation_version_id "
                        + "WHERE v.organization_id = ? AND v.automation_id = ? AND v.status = 2 ORDER BY v.version_number DESC LIMIT 1").param(org).param(automation)
                .query((rs, n) -> new Published(rs.getObject(1, UUID.class), rs.getInt(2), strings(rs.getString(3)))).optional().orElse(null);
        return new View(automation, declared, published, effective(published == null ? List.of() : published.dependencies(),
                declared == null ? List.of() : declared.dependencies()));
    }

    /** Manifesto primeiro, depois os acrescimos que nao repetem o manifesto. Mesma biblioteca com declaracao diferente: conflito. */
    static Effective effective(List<String> published, List<String> declared) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (String d : published) {
            byName.putIfAbsent(RequirementsText.key(d), d);
        }
        List<String> out = new ArrayList<>(byName.values());
        List<Conflict> conflicts = new ArrayList<>();
        for (String d : declared) {
            String name = RequirementsText.key(d);
            String mine = byName.get(name);
            if (mine == null) {
                byName.put(name, d);
                out.add(d);
            } else if (!RequirementsText.sameness(mine).equals(RequirementsText.sameness(d))) {
                conflicts.add(new Conflict(name, mine, d));
            }
        }
        return new Effective(List.copyOf(out), List.copyOf(conflicts), conflicts.isEmpty());
    }

    private static List<String> strings(String json) {
        List<String> out = new ArrayList<>();
        JsonNode n = JSON.readTree(json);
        if (n != null && n.isArray()) {
            n.forEach(x -> out.add(x.asString()));
        }
        return List.copyOf(out);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
