package com.nekkora.platform.admin;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.admin.AccessActions.Failure;
import com.nekkora.platform.artifact.ArtifactStore;
import com.nekkora.platform.artifact.PackageInspector;
import com.nekkora.platform.session.ValidSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Ciclo de vida das automacoes pelas PESSOAS (PLATFORM-AUTOMATION-LIFECYCLE-001): workspace, automacao, versao DRAFT,
 * artefato recebido e conferido pela API e publicacao DRAFT -> PUBLISHED. Cada ato: transacao da organizacao, ator e
 * permissao relidos dentro dela (workspace.lifecycle na organizacao; automation.manage/automation.publish no workspace, pela
 * mesma regra da 025), step-up recente so na publicacao, auditoria em {@code automation_admin_events} na mesma transacao.
 * A numeracao da versao, o nascimento em DRAFT e a imutabilidade da publicada sao do banco (005/008); o vinculo da versao
 * publicada com o artefato e a autoridade de quem publica sao reconferidos pelo gatilho da 028.
 * <p>
 * Publicar NAO autoriza executar: a autorizacao de execucao local continua sendo outro ato (025). Nada aqui fala com o Agent.
 */
public final class AutomationLifecycleService {

    private static final Logger LOG = LoggerFactory.getLogger(AutomationLifecycleService.class);
    static final String REFERENCE_PREFIX = "nekkora-artifact:";
    static final Pattern NAME = Pattern.compile("[^\\p{Cntrl}]{1,80}");
    static final Pattern DESCRIPTION = Pattern.compile("[^\\p{Cntrl}]{1,500}");
    static final Pattern RUNTIME = Pattern.compile("python3\\.[0-9]{1,2}");
    static final Pattern ARGUMENT = Pattern.compile("[^\\p{Cntrl}]{1,200}");
    static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AccessActions actions;
    private final WorkspaceAccess access;
    private final ArtifactStore store;

    public AutomationLifecycleService(AccessActions actions, WorkspaceAccess access, ArtifactStore store) {
        this.actions = actions;
        this.access = access;
        this.store = store;
    }

    public record Workspace(UUID workspaceId, String name, String status, OffsetDateTime createdAt) {
    }

    public record Automation(UUID automationId, UUID workspaceId, String name, String status, OffsetDateTime createdAt) {
    }

    /** {@code provenance}: DECLARED (o manifesto diz quem gerou; nao e prova de origem). */
    public record Artifact(UUID artifactId, String sha256, long sizeBytes, String fingerprintRule, String fingerprint, int filesCount,
                           String manifestVersion, String framework, String executionMode, String declaredGenerator, String provenance,
                           OffsetDateTime receivedAt) {
    }

    public record AgentPackage(String fingerprintRule, String fingerprint) {
    }

    public record Version(UUID automationVersionId, UUID automationId, UUID workspaceId, int versionNumber, String status, String runtime,
                          String entrypoint, List<String> arguments, String artifactReference, Artifact artifact, AgentPackage agentPackage,
                          OffsetDateTime createdAt, OffsetDateTime publishedAt, UUID publishedByMembershipId) {
    }

    public record Content(String sha256, byte[] bytes) {
    }

    private record Target(UUID workspaceId, UUID automationId, short status, String artifactReference) {
    }

    // ------------------------------------------------------------------ workspace e automacao

    public Workspace createWorkspace(UUID org, ValidSession actor, String token, String name, String description) {
        if (!valid(NAME, name) || description != null && !valid(DESCRIPTION, description)) {
            throw ApiException.invalid("workspace");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            actions.requirePermission(j, org, actor, "workspace.lifecycle");
            actions.lockOrganizationAndActor(j, org, actor.userId());
            Workspace w = j.sql("INSERT INTO public.workspaces (organization_id, name, description, status) VALUES (?, ?, ?, 1) "
                            + "RETURNING workspace_id, name, status, created_at").param(org).param(name.strip()).param(description == null ? null : description.strip())
                    .query((rs, n) -> new Workspace(rs.getObject(1, UUID.class), rs.getString(2), status(rs.getShort(3)), rs.getObject(4, OffsetDateTime.class)))
                    .single();
            audit(j, org, 1, actor.membershipId(), w.workspaceId(), null, null, null);
            return w;
        });
    }

    public Automation createAutomation(UUID org, ValidSession actor, String token, UUID workspace, String name) {
        if (!valid(NAME, name)) {
            throw ApiException.invalid("automacao");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            access.requireWorkspace(j, org, actor.membershipId(), workspace, "automation.manage");
            if (!active(j, "SELECT status FROM public.workspaces WHERE organization_id = ? AND workspace_id = ?", org, workspace)) {
                throw AccessActions.refuse(Failure.CONFLICT, "workspace_inativo");
            }
            actions.lockOrganizationAndActor(j, org, actor.userId());
            Automation a = j.sql("INSERT INTO public.automations (organization_id, workspace_id, name, status, created_by_membership_id) "
                            + "VALUES (?, ?, ?, 1, ?) RETURNING automation_id, workspace_id, name, status, created_at")
                    .param(org).param(workspace).param(name.strip()).param(actor.membershipId())
                    .query((rs, n) -> new Automation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), status(rs.getShort(4)),
                            rs.getObject(5, OffsetDateTime.class))).single();
            audit(j, org, 2, actor.membershipId(), workspace, a.automationId(), null, null);
            return a;
        });
    }

    // ------------------------------------------------------------------ versao DRAFT

    public Version createVersion(UUID org, ValidSession actor, String token, UUID automation, String runtime, List<String> arguments) {
        String rt = runtime == null ? "python3.12" : runtime;
        List<String> args = arguments == null ? List.of() : arguments;
        validCommand(rt, args);
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            UUID workspace = access.requireAutomation(j, org, actor.membershipId(), automation, "automation.manage");
            requireActive(j, org, automation);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            ObjectNode cmd = JSON.createObjectNode();
            cmd.put("contract_version", 1);
            cmd.put("runtime", rt);
            cmd.put("entrypoint", "main.py");
            ArrayNode a = cmd.putArray("arguments");
            args.forEach(a::add);
            cmd.putObject("parameters");
            cmd.putObject("secret_bindings");
            // version_number: o gatilho da 005 atribui MAX+1 com a automacao travada (o valor enviado e substituido)
            UUID id = j.sql("INSERT INTO public.automation_versions (organization_id, workspace_id, automation_id, version_number, status, command_json, "
                            + "created_by_membership_id) VALUES (?, ?, ?, 1, 1, ?::jsonb, ?) RETURNING automation_version_id")
                    .param(org).param(workspace).param(automation).param(JSON.writeValueAsString(cmd)).param(actor.membershipId()).query(UUID.class).single();
            audit(j, org, 3, actor.membershipId(), workspace, automation, id, null);
            return version(j, org, id);
        });
    }

    public Version updateDraft(UUID org, ValidSession actor, String token, UUID versionId, String runtime, List<String> arguments) {
        if (runtime == null && arguments == null) {
            throw ApiException.invalid("rascunho");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            Target t = target(j, org, versionId);
            access.requireAutomation(j, org, actor.membershipId(), t.automationId(), "automation.manage");
            actions.lockOrganizationAndActor(j, org, actor.userId());
            Version current = lockVersion(j, org, versionId);
            if (!"DRAFT".equals(current.status())) {
                throw AccessActions.refuse(Failure.CONFLICT, "versao_publicada_imutavel");
            }
            String rt = runtime == null ? current.runtime() : runtime;
            List<String> args = arguments == null ? current.arguments() : arguments;
            validCommand(rt, args);
            ArrayNode a = JSON.createArrayNode();
            args.forEach(a::add);
            j.sql("UPDATE public.automation_versions SET command_json = jsonb_set(jsonb_set(command_json, '{runtime}', to_jsonb(?::text)), '{arguments}', ?::jsonb) "
                    + "WHERE organization_id = ? AND automation_version_id = ? AND status = 1")
                    .param(rt).param(JSON.writeValueAsString(a)).param(org).param(versionId).update();
            audit(j, org, 4, actor.membershipId(), t.workspaceId(), t.automationId(), versionId, null);
            return version(j, org, versionId);
        });
    }

    public Version getVersion(UUID org, ValidSession actor, String token, UUID versionId) {
        return actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            Target t = target(j, org, versionId);
            access.requireAutomation(j, org, actor.membershipId(), t.automationId(), "automation.read");
            return version(j, org, versionId);
        });
    }

    // ------------------------------------------------------------------ artefato

    /**
     * Recebe o ZIP do pacote para uma versao DRAFT: autoridade conferida ANTES de ler o conteudo; o servidor calcula o sha256
     * dos bytes e a impressao; os bytes vao para o armazenamento (chave = sha256) antes da transacao; a linha do artefato e o
     * command_json da versao mudam juntos. Uma falha entre o arquivo e a transacao deixa so um arquivo enderecado por hash,
     * sem linha, que nunca e servido como artefato de versao e e reaproveitado por um envio igual.
     */
    public Version attachArtifact(UUID org, ValidSession actor, String token, UUID versionId, byte[] zip) throws PackageInspector.Rejected {
        requireStore();
        actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            Target t = target(j, org, versionId);
            access.requireAutomation(j, org, actor.membershipId(), t.automationId(), "automation.manage");
            if (t.status() != 1) {
                throw AccessActions.refuse(Failure.CONFLICT, "versao_publicada_imutavel");
            }
            return null;
        });
        PackageInspector.Inspection in = PackageInspector.inspect(zip);
        try {
            store.put(org, in.sha256(), zip);
        } catch (IOException e) {
            LOG.warn("evento=artefato.armazenamento_falhou correlacao={}", RequestContext.correlation());
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            Target t = target(j, org, versionId);
            access.requireAutomation(j, org, actor.membershipId(), t.automationId(), "automation.manage");
            actions.lockOrganizationAndActor(j, org, actor.userId());
            Version current = lockVersion(j, org, versionId);
            if (!"DRAFT".equals(current.status())) {
                throw AccessActions.refuse(Failure.CONFLICT, "versao_publicada_imutavel");
            }
            requireActive(j, org, t.automationId());
            Optional<UUID> existing = j.sql("SELECT artifact_id FROM public.automation_artifacts WHERE organization_id = ? AND automation_version_id = ? AND sha256 = ?")
                    .param(org).param(versionId).param(in.sha256()).query(UUID.class).optional();
            UUID artifact = existing.orElseGet(() -> j.sql("""
                    INSERT INTO public.automation_artifacts (organization_id, workspace_id, automation_id, automation_version_id, sha256, size_bytes,
                        package_kind, fingerprint_rule, fingerprint, files_count, manifest_version, framework, execution_mode, declared_generator,
                        storage_key, received_by_membership_id)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING artifact_id
                    """).param(org).param(t.workspaceId()).param(t.automationId()).param(versionId).param(in.sha256()).param(in.sizeBytes())
                    .param(PackageInspector.RULE).param(in.fingerprint()).param(in.filesCount()).param(in.manifestVersion()).param(in.framework())
                    .param(in.executionMode()).param(in.declaredGenerator()).param(in.sha256()).param(actor.membershipId()).query(UUID.class).single());
            ObjectNode patch = JSON.createObjectNode();
            patch.put("contract_version", 1);
            patch.put("entrypoint", "main.py");
            ObjectNode art = patch.putObject("artifact");
            art.put("reference", REFERENCE_PREFIX + artifact);
            art.put("sha256", in.sha256());
            ObjectNode pkg = patch.putObject("agent_package");
            pkg.put("fingerprint_rule", PackageInspector.RULE);
            pkg.put("fingerprint", in.fingerprint());
            j.sql("UPDATE public.automation_versions SET command_json = command_json || ?::jsonb WHERE organization_id = ? AND automation_version_id = ? AND status = 1")
                    .param(JSON.writeValueAsString(patch)).param(org).param(versionId).update();
            if (existing.isEmpty()) {
                audit(j, org, 5, actor.membershipId(), t.workspaceId(), t.automationId(), versionId, artifact);
            }
            return version(j, org, versionId);
        });
    }

    public Content artifactContent(UUID org, ValidSession actor, String token, UUID versionId) {
        requireStore();
        Version v = actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            Target t = target(j, org, versionId);
            access.requireAutomation(j, org, actor.membershipId(), t.automationId(), "automation.manage");
            return version(j, org, versionId);
        });
        if (v.artifact() == null) {
            throw new ApiException(ApiError.NOT_FOUND, "artefato");
        }
        return new Content(v.artifact().sha256(), verifiedBytes(org, v.artifact()));
    }

    // ------------------------------------------------------------------ publicacao

    /**
     * DRAFT -> PUBLISHED. A pessoa confirma o que conferiu ({@code expectedSha256} dos bytes e {@code expectedFingerprint}):
     * se o rascunho mudou entre a conferencia e a publicacao, nada e publicado (409). Os bytes guardados sao relidos e
     * conferidos de novo (hash e impressao) antes da transacao. Repetir a mesma publicacao (resposta perdida) devolve a versao
     * ja publicada; outra expectativa sobre a versao publicada e 409. Publicacoes concorrentes: a versao e travada; so uma
     * muda o estado.
     */
    public Version publish(UUID org, ValidSession actor, String token, UUID versionId, String expectedSha256, String expectedFingerprint) {
        if (!valid(HEX64, expectedSha256) || !valid(HEX64, expectedFingerprint)) {
            throw ApiException.invalid("publicacao");
        }
        requireStore();
        Version before = actions.read(org, j -> {
            actions.requireActor(j, org, actor, token);
            Target t = target(j, org, versionId);
            access.requireAutomation(j, org, actor.membershipId(), t.automationId(), "automation.publish");
            return version(j, org, versionId);
        });
        if ("PUBLISHED".equals(before.status())) {
            return samePublication(before, expectedSha256, expectedFingerprint);
        }
        if (before.artifact() == null) {
            throw new ApiException(ApiError.CONFLICT, "versao_sem_artefato");
        }
        byte[] bytes = verifiedBytes(org, before.artifact());
        try {
            if (!PackageInspector.inspect(bytes).fingerprint().equals(before.artifact().fingerprint())) {
                throw new ApiException(ApiError.CONFLICT, "artefato_divergente");
            }
        } catch (PackageInspector.Rejected e) {
            throw new ApiException(ApiError.CONFLICT, "artefato_divergente");
        }
        return actions.write(org, j -> {
            actions.requireActor(j, org, actor, token);
            Target t = target(j, org, versionId);
            access.requireAutomation(j, org, actor.membershipId(), t.automationId(), "automation.publish");
            actions.requireStepUp(j, actor);
            actions.lockOrganizationAndActor(j, org, actor.userId());
            Version v = lockVersion(j, org, versionId);
            if ("PUBLISHED".equals(v.status())) {
                return samePublication(v, expectedSha256, expectedFingerprint);
            }
            if (v.artifact() == null || !v.artifact().artifactId().equals(before.artifact().artifactId())
                    || !expectedSha256.equals(v.artifact().sha256()) || !expectedFingerprint.equals(v.artifact().fingerprint())
                    || v.agentPackage() == null || !expectedFingerprint.equals(v.agentPackage().fingerprint())) {
                throw AccessActions.refuse(Failure.CONFLICT, "conteudo_diferente_do_conferido");
            }
            requireActive(j, org, t.automationId());
            int n = j.sql("UPDATE public.automation_versions SET status = 2, published_at = clock_timestamp(), published_by_membership_id = ? "
                    + "WHERE organization_id = ? AND automation_version_id = ? AND status = 1").param(actor.membershipId()).param(org).param(versionId).update();
            if (n != 1) {
                throw AccessActions.refuse(Failure.CONFLICT, "publicacao_concorrente");
            }
            audit(j, org, 6, actor.membershipId(), t.workspaceId(), t.automationId(), versionId, v.artifact().artifactId());
            LOG.info("evento=automacao.versao_publicada versao={} correlacao={}", versionId, RequestContext.correlation());
            return version(j, org, versionId);
        });
    }

    private static Version samePublication(Version v, String sha, String fingerprint) {
        if (v.artifact() != null && sha.equals(v.artifact().sha256()) && v.agentPackage() != null && fingerprint.equals(v.agentPackage().fingerprint())) {
            return v; // repeticao da mesma publicacao: nada muda
        }
        throw new ApiException(ApiError.CONFLICT, "versao_ja_publicada");
    }

    // ------------------------------------------------------------------ apoio

    private byte[] verifiedBytes(UUID org, Artifact a) {
        try {
            return store.get(org, a.sha256()).orElseThrow(() -> new ApiException(ApiError.CONFLICT, "artefato_ausente"));
        } catch (ArtifactStore.Corrupted e) {
            LOG.warn("evento=artefato.corrompido artefato={} correlacao={}", a.artifactId(), RequestContext.correlation());
            throw new ApiException(ApiError.CONFLICT, "artefato_corrompido");
        } catch (IOException e) {
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento");
        }
    }

    private void requireStore() {
        if (!store.available()) {
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "armazenamento_nao_configurado");
        }
    }

    private static Target target(JdbcClient j, UUID org, UUID versionId) {
        return j.sql("SELECT workspace_id, automation_id, status, command_json -> 'artifact' ->> 'reference' FROM public.automation_versions "
                        + "WHERE organization_id = ? AND automation_version_id = ?").param(org).param(versionId)
                .query((rs, n) -> new Target(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getShort(3), rs.getString(4))).optional()
                .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "versao"));
    }

    private static Version lockVersion(JdbcClient j, UUID org, UUID versionId) {
        j.sql("SELECT 1 FROM public.automation_versions WHERE organization_id = ? AND automation_version_id = ? FOR UPDATE").param(org).param(versionId)
                .query(Integer.class).optional().orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "versao"));
        return version(j, org, versionId);
    }

    private static void requireActive(JdbcClient j, UUID org, UUID automation) {
        Boolean ok = j.sql("SELECT a.status = 1 AND w.status = 1 FROM public.automations a JOIN public.workspaces w ON w.organization_id = a.organization_id "
                + "AND w.workspace_id = a.workspace_id WHERE a.organization_id = ? AND a.automation_id = ?").param(org).param(automation)
                .query(Boolean.class).optional().orElse(false);
        if (!ok) {
            throw AccessActions.refuse(Failure.CONFLICT, "automacao_ou_workspace_inativo");
        }
    }

    private static boolean active(JdbcClient j, String sql, UUID org, UUID id) {
        return j.sql(sql).param(org).param(id).query(Short.class).optional().map(s -> s == 1).orElse(false);
    }

    static Version version(JdbcClient j, UUID org, UUID versionId) {
        return j.sql("SELECT automation_version_id, automation_id, workspace_id, version_number, status, command_json::text, created_at, published_at, "
                        + "published_by_membership_id FROM public.automation_versions WHERE organization_id = ? AND automation_version_id = ?")
                .param(org).param(versionId).query((rs, n) -> row(j, org, rs)).optional()
                .orElseThrow(() -> AccessActions.refuse(Failure.TARGET_NOT_FOUND, "versao"));
    }

    private static Version row(JdbcClient j, UUID org, ResultSet rs) throws SQLException {
        JsonNode cmd = JSON.readTree(rs.getString(6));
        List<String> args = new ArrayList<>();
        cmd.path("arguments").forEach(a -> args.add(a.asString()));
        String ref = cmd.path("artifact").path("reference").isString() ? cmd.path("artifact").path("reference").asString() : null;
        Artifact artifact = null;
        if (ref != null && ref.startsWith(REFERENCE_PREFIX)) {
            UUID id = UUID.fromString(ref.substring(REFERENCE_PREFIX.length()));
            artifact = j.sql("""
                    SELECT artifact_id, sha256, size_bytes, fingerprint_rule, fingerprint, files_count, manifest_version, framework, execution_mode,
                           declared_generator, received_at
                      FROM public.automation_artifacts WHERE organization_id = ? AND artifact_id = ?
                    """).param(org).param(id).query((r, n) -> new Artifact(r.getObject(1, UUID.class), r.getString(2), r.getLong(3), r.getString(4),
                    r.getString(5), r.getInt(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), "DECLARED",
                    r.getObject(11, OffsetDateTime.class))).optional().orElse(null);
        }
        JsonNode pkg = cmd.path("agent_package");
        AgentPackage agentPackage = pkg.path("fingerprint").isString()
                ? new AgentPackage(pkg.path("fingerprint_rule").asString(), pkg.path("fingerprint").asString()) : null;
        return new Version(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getInt(4),
                rs.getShort(5) == 2 ? "PUBLISHED" : "DRAFT", str(cmd.path("runtime")), str(cmd.path("entrypoint")), args, ref, artifact,
                agentPackage, rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class), rs.getObject(9, UUID.class));
    }

    private static String str(JsonNode n) {
        return n != null && n.isString() ? n.asString() : null;
    }

    private static void validCommand(String runtime, List<String> arguments) {
        if (!valid(RUNTIME, runtime) || arguments.size() > 20 || arguments.stream().anyMatch(a -> !valid(ARGUMENT, a))) {
            throw ApiException.invalid("comando");
        }
    }

    private static boolean valid(Pattern p, String s) {
        return s != null && !s.isBlank() && p.matcher(s).matches();
    }

    private static String status(short s) {
        return switch (s) {
            case 1 -> "ACTIVE";
            case 2 -> "SUSPENDED";
            default -> "ARCHIVED";
        };
    }

    private static void audit(JdbcClient j, UUID org, int act, UUID actor, UUID workspace, UUID automation, UUID version, UUID artifact) {
        j.sql("""
                INSERT INTO public.automation_admin_events (organization_id, act, actor_membership_id, workspace_id, automation_id, automation_version_id,
                    artifact_id, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).param(org).param((short) act).param(actor).param(workspace).param(automation).param(version).param(artifact)
                .param(AccessActions.correlation()).update();
    }
}
