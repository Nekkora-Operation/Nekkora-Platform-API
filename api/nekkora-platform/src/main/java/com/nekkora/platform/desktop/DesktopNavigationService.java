package com.nekkora.platform.desktop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.DbErrors;
import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.core.db.IdentityTransactions.Mode;
import com.nekkora.core.db.IdentityTransactions.SessaoDeOrigem;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.access.WorkspaceAccess;
import com.nekkora.platform.admin.AutomationLifecycleService;
import com.nekkora.platform.desktop.DesktopLoginService.Authenticated;
import com.nekkora.platform.desktop.DesktopStore.OrgAccount;
import com.nekkora.platform.identity.IdentityOrgRepository;
import com.nekkora.platform.paging.CursorCodec;
import com.nekkora.platform.paging.Paging;
import com.nekkora.platform.web.ProductReads;

/**
 * Contexto da pessoa para o aplicativo desktop (AGENT-CLIENT-NAVEGACAO-001, contrato desktop-navigation/1): quem ela e, em
 * quais organizacoes esta, e os workspaces, automacoes e versoes que pode ver; mais a renomeacao de automacao. So para
 * sessao do desktop com o escopo {@code identity navigation}.
 *
 * <p>Toda requisicao, nesta ordem:
 * <ol>
 * <li>a sessao do desktop e a sessao de identidade de origem, exatamente como em {@code GET /desktop/session} (401);</li>
 * <li>o escopo da sessao (403 sem navegacao);</li>
 * <li>nas rotas de organizacao: a organizacao do caminho tem de ser uma das que a APROVACAO resolveu para esta sessao (404).
 * O par (organizacao, conta) guardado e so a candidata;</li>
 * <li>numa UNICA transacao com os contextos da identidade e da organizacao ({@link IdentityTransactions#inIdentityAndOrganization}):
 * sessao de origem ainda ativa (401); {@code identity_org_session_allowed} (017/029: a conta e desta identidade, a sessao de
 * origem esta ativa e nao restrita, conta interna ativa e verificada, organizacao ativa, vinculo ativo), falso -> 404; vinculo
 * ativo da conta lido das tabelas da organizacao; e entao a MESMA autorizacao e as MESMAS consultas da Web
 * ({@link WorkspaceAccess} e {@link ProductReads}), com esse vinculo.</li>
 * </ol>
 * Nada do que decide o acesso vem do cliente: ele so envia o token e os ids do caminho. Nada fica em cache entre requisicoes:
 * vinculo retirado, papel revogado ou sessao encerrada valem na requisicao seguinte.
 *
 * <p>Por que uma transacao so, e de {@link IdentityTransactions}: a conferencia precisa do contexto da identidade (a RLS de
 * {@code identity_sessions}) e do contexto da organizacao (exigido por {@code identity_org_session_allowed} e pela RLS das
 * tabelas lidas depois). {@code ProductReads} e {@code WorkspaceAccess} so dependem de {@code nekkora.organization_id}, que
 * esta transacao define do mesmo modo que {@code OrgTransactions}; o contexto da identidade nao amplia a RLS das tabelas de
 * organizacao. Abrir {@code OrgTransactions.inOrganization} por dentro suspenderia esta transacao e usaria outra conexao; abrir
 * depois separaria a conferencia da leitura. A guarda de contexto vazado roda ao abrir, com os dois contextos ainda vazios.
 */
public final class DesktopNavigationService {

    private static final Logger LOG = LoggerFactory.getLogger(DesktopNavigationService.class);

    static final String ROUTE_WORKSPACES = "desktopListWorkspaces";
    static final String ROUTE_AUTOMATIONS = "desktopListAutomations";
    static final String ROUTE_VERSIONS = "desktopListAutomationVersions";
    static final String SORT_NAME48 = "name48.asc+id.asc";
    static final String SORT_VERSION_DESC = "versionNumber.desc";
    /** workspaces.status e automations.status: 1 = ACTIVE (005). O aplicativo so navega pelo que esta ativo. */
    private static final List<Short> ACTIVE = List.of((short) 1);
    private static final String ACTIVE_FILTER = CursorCodec.filters("status", List.of("active"));
    private static final String MANAGE = "automation.manage";
    private static final String EXECUTE = "automation.execute";

    /** Vinculo ativo da pessoa na organizacao, resolvido NESTA transacao. */
    private record Member(UUID organizationId, UUID userId, UUID membershipId) {
    }

    /** Sessao de origem encerrada, vista dentro da transacao da organizacao. */
    private static final class OriginEnded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        OriginEnded() {
            super(null, null, false, false);
        }
    }

    /** Organizacao da lista que nao passou na conferencia de agora: fica fora da resposta. */
    private static final class NoAccess extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NoAccess() {
            super(null, null, false, false);
        }
    }

    private final DesktopLoginService login;
    private final IdentityTransactions tx;
    private final WorkspaceAccess access;
    private final ProductReads reads;
    private final AutomationLifecycleService lifecycle;
    private final CursorCodec cursors;

    public DesktopNavigationService(DesktopLoginService login, IdentityTransactions tx, WorkspaceAccess access, AutomationLifecycleService lifecycle,
                                    CursorCodec cursors) {
        this.login = login;
        this.tx = tx;
        this.access = access;
        this.reads = new ProductReads(access);
        this.lifecycle = lifecycle;
        this.cursors = cursors;
    }

    // ------------------------------------------------------------------ pessoa e organizacoes

    public Map<String, Object> me(String accessToken) {
        Authenticated a = navigation(accessToken);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("email", a.email());
        r.put("displayName", a.displayName());
        return r;
    }

    /**
     * As organizacoes resolvidas na aprovacao que passam na conferencia de AGORA, cada uma lida no proprio contexto (uma
     * transacao curta por organizacao, como a lista da Web faz para o identificador). Ordem da lista da Web: nome e id, na
     * ordenacao do banco. Sem cursor: no maximo {@link DesktopLoginService#MAX_ORGANIZATIONS}.
     */
    public Map<String, Object> organizations(String accessToken) {
        Authenticated a = navigation(accessToken);
        List<Map<String, Object>> found = new ArrayList<>();
        for (OrgAccount o : a.session().organizations()) {
            try {
                found.add(inOrganization(accessToken, a, o, Mode.READ_ONLY, (j, m) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("organizationId", o.organizationId().toString());
                    item.put("name", j.sql("SELECT name FROM public.organizations WHERE organization_id = ?").param(o.organizationId())
                            .query(String.class).single());
                    item.put("handle", IdentityOrgRepository.handle(j, o.organizationId()).orElse(null));
                    item.put("activationState", IdentityOrgRepository.activationStates(j, List.of(o.organizationId()))
                            .getOrDefault(o.organizationId(), IdentityOrgRepository.AGUARDANDO_ATIVACAO));
                    return item;
                }, NoAccess::new));
            } catch (NoAccess e) {
                // vinculo retirado, conta ou organizacao suspensa depois da aprovacao: some da lista, sem erro
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", found.size() < 2 ? found : sorted(a, found));
        return r;
    }

    /** Ordena por nome e id NO BANCO (mesma ordenacao de {@code identity_organizations}); so parametros, nenhuma tabela. */
    private List<Map<String, Object>> sorted(Authenticated a, List<Map<String, Object>> items) {
        String[] names = items.stream().map(i -> (String) i.get("name")).toArray(String[]::new);
        String[] ids = items.stream().map(i -> (String) i.get("organizationId")).toArray(String[]::new);
        List<Integer> order = tx.inIdentity(new SessaoDeOrigem(a.session().identityId(), a.session().originSessionId()), Mode.READ_ONLY,
                (id, j) -> j.sql("SELECT t.i FROM unnest(?::text[], ?::uuid[]) WITH ORDINALITY AS t(name, id, i) ORDER BY t.name, t.id")
                        .param(names).param(ids).query(Integer.class).list()).orElseThrow();
        if (order.size() != items.size()) {
            throw new IllegalStateException("ordenacao das organizacoes devolveu outra quantidade");
        }
        return order.stream().map(i -> items.get(i - 1)).toList();
    }

    // ------------------------------------------------------------------ workspaces, automacoes e versoes

    /** Workspaces ATIVOS que o vinculo ve com {@code automation.read}: a consulta de {@code GET /o/{org}/workspaces?status=active}. */
    public Map<String, Object> workspaces(String accessToken, UUID org, String cursor, Integer limit) {
        Authenticated a = navigation(accessToken);
        int pageSize = Paging.limit(limit);
        return inOrganization(accessToken, a, org, Mode.READ_ONLY, (j, m) -> {
            CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_WORKSPACES, org, m.membershipId(), null, ACTIVE_FILTER, SORT_NAME48);
            CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
            List<Map<String, Object>> rows = reads.listWorkspaces(j, org, m.membershipId(), ACTIVE, after == null ? null : after.text(0),
                    after == null ? null : after.uuid(1), pageSize + 1);
            return page(rows, pageSize, last -> cursors.encode(scope, List.of(last.get(ProductReads.SORT_KEY), last.get("workspaceId"))), w -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("workspaceId", w.get("workspaceId"));
                item.put("name", w.get("name"));
                return item;
            });
        });
    }

    /**
     * Automacoes ATIVAS do workspace (a consulta de {@code GET /o/{org}/workspaces/{w}/automations?status=active}; workspace
     * fora do alcance: 404), com o pacote da ultima versao publicada e o que o vinculo pode fazer nesse workspace.
     */
    public Map<String, Object> automations(String accessToken, UUID org, UUID workspace, String cursor, Integer limit) {
        Authenticated a = navigation(accessToken);
        int pageSize = Paging.limit(limit);
        return inOrganization(accessToken, a, org, Mode.READ_ONLY, (j, m) -> {
            CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_AUTOMATIONS, org, m.membershipId(), workspace, ACTIVE_FILTER, SORT_NAME48);
            CursorCodec.Position after = cursor == null ? null : cursors.decode(cursor, scope).expectSize(2);
            List<Map<String, Object>> rows = reads.listAutomations(j, org, m.membershipId(), workspace, ACTIVE, after == null ? null : after.text(0),
                    after == null ? null : after.uuid(1), pageSize + 1);
            List<UUID> versions = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                if (row.get("latestPublishedVersion") instanceof Map<?, ?> v) {
                    versions.add(UUID.fromString((String) v.get("automationVersionId")));
                }
            }
            Map<UUID, Map<String, Object>> packages = packages(j, org, versions);
            Map<String, Object> r = page(rows, pageSize, last -> cursors.encode(scope, List.of(last.get(ProductReads.SORT_KEY), last.get("automationId"))),
                    row -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("automationId", row.get("automationId"));
                        item.put("name", row.get("name"));
                        Map<String, Object> latest = null;
                        if (row.get("latestPublishedVersion") instanceof Map<?, ?> v) {
                            latest = new LinkedHashMap<>();
                            latest.put("automationVersionId", v.get("automationVersionId"));
                            latest.put("versionNumber", v.get("versionNumber"));
                            latest.put("publishedAt", v.get("publishedAt"));
                            latest.put("package", packages.get(UUID.fromString((String) v.get("automationVersionId"))));
                        }
                        item.put("latestPublishedVersion", latest);
                        return item;
                    });
            Map<String, Object> capabilities = new LinkedHashMap<>();
            capabilities.put("rename", access.hasWorkspacePermission(j, org, m.membershipId(), workspace, MANAGE));
            capabilities.put("execute", access.hasWorkspacePermission(j, org, m.membershipId(), workspace, EXECUTE));
            r.put("capabilities", capabilities);
            return r;
        });
    }

    /** Versoes da automacao (a consulta de {@code GET /o/{org}/automations/{a}/versions}), com o pacote de cada uma. */
    public Map<String, Object> versions(String accessToken, UUID org, UUID automation, String cursor, Integer limit) {
        Authenticated a = navigation(accessToken);
        int pageSize = Paging.limit(limit);
        return inOrganization(accessToken, a, org, Mode.READ_ONLY, (j, m) -> {
            CursorCodec.Scope scope = new CursorCodec.Scope(ROUTE_VERSIONS, org, m.membershipId(), automation, CursorCodec.filters(), SORT_VERSION_DESC);
            Long before = cursor == null ? null : cursors.decode(cursor, scope).expectSize(1).integer(0);
            List<Map<String, Object>> rows = reads.listVersions(j, org, m.membershipId(), automation, before, pageSize + 1);
            Map<UUID, Map<String, Object>> packages = packages(j, org,
                    rows.stream().map(v -> UUID.fromString((String) v.get("automationVersionId"))).toList());
            return page(rows, pageSize, last -> cursors.encode(scope, List.of(((Number) last.get("versionNumber")).longValue())), v -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("automationVersionId", v.get("automationVersionId"));
                item.put("versionNumber", v.get("versionNumber"));
                item.put("status", v.get("status"));
                item.put("publishedAt", v.get("publishedAt"));
                item.put("package", packages.get(UUID.fromString((String) v.get("automationVersionId"))));
                return item;
            });
        });
    }

    // ------------------------------------------------------------------ renomear automacao

    /**
     * Renomeia a automacao. Corpo invalido: 400 antes de qualquer leitura da organizacao. A autorizacao, o estado e a
     * auditoria ficam em {@link AutomationLifecycleService#renameAutomation}, na mesma transacao da conferencia da pessoa.
     */
    public Map<String, Object> renameAutomation(String accessToken, UUID org, UUID automation, String name) {
        Authenticated a = navigation(accessToken);
        if (!AutomationLifecycleService.validAutomationName(name)) {
            throw ApiException.invalid("automacao");
        }
        return inOrganization(accessToken, a, org, Mode.READ_WRITE, (j, m) -> {
            AutomationLifecycleService.Automation renamed = lifecycle.renameAutomation(j, org, m.membershipId(), m.userId(), automation, name);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("automationId", renamed.automationId().toString());
            r.put("name", renamed.name());
            return r;
        });
    }

    // ------------------------------------------------------------------ conferencia da pessoa

    /** Passos 1 e 2: sessao do desktop valida (401) e com o escopo de navegacao (403). */
    private Authenticated navigation(String accessToken) {
        Authenticated a = login.authenticate(accessToken);
        if (!a.navigation()) {
            LOG.info("evento=desktop.escopo_insuficiente correlacao={}", RequestContext.correlation());
            throw ApiException.forbidden("desktop_escopo");
        }
        return a;
    }

    /** Passo 3 pelo id do caminho: organizacao fora da lista desta sessao -> 404, igual a inexistente. */
    private <T> T inOrganization(String accessToken, Authenticated a, UUID org, Mode mode, BiFunction<JdbcClient, Member, T> work) {
        UUID user = a.session().userIn(org).orElseThrow(DesktopNavigationService::notFound);
        return inOrganization(accessToken, a, new OrgAccount(org, user), mode, work, DesktopNavigationService::notFound);
    }

    /** Passo 4. {@code refused}: o que lancar quando a pessoa nao tem (mais) acesso a organizacao. */
    private <T> T inOrganization(String accessToken, Authenticated a, OrgAccount o, Mode mode, BiFunction<JdbcClient, Member, T> work,
                                 java.util.function.Supplier<? extends RuntimeException> refused) {
        UUID origin = a.session().originSessionId();
        try {
            return tx.inIdentityAndOrganization(new SessaoDeOrigem(a.session().identityId(), origin), o.organizationId(), mode, (id, j) -> {
                if (!DesktopLoginService.originActive(j, origin)) {
                    throw new OriginEnded();
                }
                UUID membership = membership(j, o, origin).orElseThrow(refused);
                return work.apply(j, new Member(o.organizationId(), o.userId(), membership));
            }).orElseThrow(() -> new IllegalStateException("rota do aplicativo sem resultado"));
        } catch (OriginEnded e) {
            throw login.ended(accessToken);
        }
    }

    /**
     * A conferencia de agora, no banco: {@code identity_org_session_allowed} (a mesma funcao que mantem valida a sessao de
     * organizacao nascida da troca) e o vinculo ATIVO da conta. A funcao recusa com 42501 a conta que nao e da organizacao
     * do contexto: tratado como sem acesso. Vazio = sem acesso.
     */
    private static Optional<UUID> membership(JdbcClient j, OrgAccount o, UUID originSession) {
        boolean allowed;
        try {
            allowed = Boolean.TRUE.equals(j.sql("SELECT public.identity_org_session_allowed(?, ?, ?)")
                    .param(o.organizationId()).param(o.userId()).param(originSession).query(Boolean.class).single());
        } catch (RuntimeException e) {
            if (!"42501".equals(DbErrors.sqlState(e))) {
                throw e;
            }
            allowed = false;
        }
        if (!allowed) {
            return Optional.empty();
        }
        return j.sql("SELECT membership_id FROM public.organization_memberships WHERE organization_id = ? AND user_id = ? AND account_type = 1 AND status = 2")
                .param(o.organizationId()).param(o.userId()).query(UUID.class).optional();
    }

    private static ApiException notFound() {
        return new ApiException(ApiError.NOT_FOUND, "desktop_organizacao");
    }

    // ------------------------------------------------------------------ apoio

    /**
     * Pacote (artefato recebido, 028/030) de cada versao que tem um: o artefato que o comando da versao referencia. So nome,
     * versao e impressao do pacote; nenhum conteudo, hash de arquivo ou dado de quem enviou.
     */
    private static Map<UUID, Map<String, Object>> packages(JdbcClient j, UUID org, List<UUID> versions) {
        Map<UUID, Map<String, Object>> out = new HashMap<>();
        if (versions.isEmpty()) {
            return out;
        }
        j.sql("""
                SELECT v.automation_version_id, x.package_name, x.package_version, x.fingerprint
                  FROM public.automation_versions v
                  JOIN public.automation_artifacts x ON x.organization_id = v.organization_id AND x.automation_version_id = v.automation_version_id
                                                    AND v.command_json -> 'artifact' ->> 'reference' = 'nekkora-artifact:' || x.artifact_id::text
                 WHERE v.organization_id = :org AND v.automation_version_id IN (:versions)
                """).param("org", org).param("versions", versions).query((rs, n) -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("name", rs.getString(2));
                    p.put("version", rs.getString(3));
                    p.put("fingerprint", rs.getString(4));
                    return out.put(rs.getObject(1, UUID.class), p);
                }).list();
        return out;
    }

    /** Le {@code limit + 1}: nextCursor so se havia mais itens no momento da consulta (como nas rotas da Web). */
    private static Map<String, Object> page(List<Map<String, Object>> rows, int pageSize, Function<Map<String, Object>, String> cursorOf,
                                            Function<Map<String, Object>, Map<String, Object>> item) {
        String next = rows.size() > pageSize ? cursorOf.apply(rows.get(pageSize - 1)) : null;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("items", rows.subList(0, Math.min(pageSize, rows.size())).stream().map(item).toList());
        r.put("nextCursor", next);
        return r;
    }
}
