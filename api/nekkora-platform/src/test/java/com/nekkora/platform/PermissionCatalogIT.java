package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.access.PermissionCatalog;

import tools.jackson.databind.json.JsonMapper;

/**
 * IT de catalogo (contrato GAP-02/03/06, §0.1 e §2): o catalogo da aplicacao e a matriz papel x permissao do banco
 * (migrations 001-016) coincidem; papeis de escopo workspace so tem permissoes de workspace. Uma migration futura que
 * crie permissao ou papel, ou conceda permissao a papel de workspace, sem atualizar o contrato falha aqui. Excecao UNICA:
 * os papeis-fixture fora do catalogo criados pelos testes desta JVM, excluidos pelo ID EXATO
 * ({@link AccessFixtures#ROLES_CREATED_BY_TESTS}); qualquer outro papel reprova (contraprova no proprio teste).
 */
@ExtendWith(OutputCaptureExtension.class)
class PermissionCatalogIT extends PlatformTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** §0.1, transcrita: papel -> permissoes (oraculo independente do catalogo da aplicacao). */
    private static final Map<String, Set<String>> MATRIX = Map.of(
            "ORG_ADMIN", new TreeSet<>(MyPermissionsRouteIT.ALL_23),
            "WORKSPACE_ADMIN", new TreeSet<>(MyPermissionsRouteIT.WORKSPACE_13),
            "OPERATOR", new TreeSet<>(MyPermissionsRouteIT.OPERATOR),
            "VIEWER", new TreeSet<>(MyPermissionsRouteIT.VIEWER));

    private record CatalogRow(UUID roleId, String role, int scope, String permission) {
    }

    private static List<CatalogRow> roleRows() throws Exception {
        try (Connection c = DB.connectAs("nekkora_platform");
             PreparedStatement ps = c.prepareStatement("""
                     SELECT r.role_id, r.code, r.scope, p.code FROM public.roles r
                       LEFT JOIN public.role_permissions rp ON rp.role_id = r.role_id
                       LEFT JOIN public.permissions p ON p.permission_id = rp.permission_id
                     """);
             ResultSet rs = ps.executeQuery()) {
            List<CatalogRow> out = new java.util.ArrayList<>();
            while (rs.next()) {
                out.add(new CatalogRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getString(4)));
            }
            return out;
        }
    }

    /** Papel -> permissoes, excluindo SO os IDs exatos dos papeis-fixture registrados. */
    private static Map<String, Set<String>> matrixExcludingFixtures() throws Exception {
        Map<String, Set<String>> out = new TreeMap<>();
        for (CatalogRow r : roleRows()) {
            if (AccessFixtures.ROLES_CREATED_BY_TESTS.contains(r.roleId())) {
                continue;
            }
            Set<String> s = out.computeIfAbsent(r.role(), k -> new TreeSet<>());
            if (r.permission() != null) {
                s.add(r.permission());
            }
        }
        return out;
    }

    @Test
    void databaseCatalogMatchesTheContract() throws Exception {
        Set<String> dbPermissions = new TreeSet<>();
        try (Connection c = DB.connectAs("nekkora_platform"); PreparedStatement ps = c.prepareStatement("SELECT code FROM public.permissions");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                dbPermissions.add(rs.getString(1));
            }
        }
        assertThat(dbPermissions).as("23 codigos, os mesmos do catalogo da aplicacao").hasSize(23).isEqualTo(PermissionCatalog.permissions());
        assertThat(PermissionCatalog.permissions(PermissionCatalog.Scope.ORGANIZATION)).hasSize(10);
        assertThat(PermissionCatalog.permissions(PermissionCatalog.Scope.WORKSPACE)).hasSize(13).isEqualTo(MATRIX.get("WORKSPACE_ADMIN"));

        for (CatalogRow r : roleRows()) {
            if (!AccessFixtures.ROLES_CREATED_BY_TESTS.contains(r.roleId())) {
                assertThat(r.scope()).as("escopo de " + r.role())
                        .isEqualTo(PermissionCatalog.roleScope(r.role()).orElseThrow() == PermissionCatalog.Scope.ORGANIZATION ? 1 : 2);
            }
        }
        Map<String, Set<String>> matrix = matrixExcludingFixtures();
        assertThat(matrix).as("papel x permissao = §0.1 (so os IDs exatos das fixtures ficam fora)").isEqualTo(new TreeMap<>(MATRIX));
        for (String role : List.of("WORKSPACE_ADMIN", "OPERATOR", "VIEWER")) {
            assertThat(matrix.get(role)).as("papel de workspace so com permissoes de workspace: " + role)
                    .allMatch(p -> PermissionCatalog.permissionScope(p).orElseThrow() == PermissionCatalog.Scope.WORKSPACE);
        }
    }

    /** Contraprova da exclusao: um papel inesperado (nao registrado, inclusive com prefixo parecido) reprova a comparacao. */
    @Test
    void unexpectedRoleOutsideTheFixturesFailsTheComparison() throws Exception {
        String code = "TESTE_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        UUID id = UUID.fromString(AccessFixtures.value("INSERT INTO roles (code, scope, description) VALUES ('" + code
                + "', 2, 'papel inesperado (contraprova)') RETURNING role_id"));
        try {
            assertThat(matrixExcludingFixtures()).as("papel inesperado aparece na comparacao").containsKey(code)
                    .isNotEqualTo(new TreeMap<>(MATRIX));
        } finally {
            // so depois da contraprova ele passa a ser fixture registrada (pelo ID exato), para nao contaminar outros testes
            AccessFixtures.ROLES_CREATED_BY_TESTS.add(id);
        }
        assertThat(matrixExcludingFixtures()).as("com o ID registrado, volta a coincidir").isEqualTo(new TreeMap<>(MATRIX));
    }

    @Test
    void unknownRoleCodeIsAServerErrorWithLogInBothRoutes(CapturedOutput output) {
        Org o = newOrganization();
        UUID w = newWorkspace(o, null);
        AccessFixtures.TestRole wsRole = AccessFixtures.roleOutsideCatalog(2, "automation.read");
        AccessFixtures.TestRole orgRole = AccessFixtures.roleOutsideCatalog(1, "automation.read");
        TestUser wsUser = internalUser(o, true, 2);
        AccessFixtures.assignWorkspaceRole(o.id(), AccessFixtures.membershipOf(o.id(), wsUser.id()), w, wsRole.code(), o.adminMembership());
        TestUser orgUser = internalUser(o, true, 2);
        AccessFixtures.assignOrgRole(o.id(), AccessFixtures.membershipOf(o.id(), orgUser.id()), orgRole.code(), o.adminMembership());
        TestUser valid = internalUser(o, true, 2);
        AccessFixtures.assignWorkspaceRole(o.id(), AccessFixtures.membershipOf(o.id(), valid.id()), w, "VIEWER", o.adminMembership());

        BrowserClient control = browser();
        firstLogin(control, o.id(), valid);
        assertThat(control.get("/api/v1/o/" + o.id() + "/me/permissions").status()).as("controle positivo").isEqualTo(200);
        assertThat(control.get("/api/v1/o/" + o.id() + "/me/permissions/workspaces").status()).as("controle positivo").isEqualTo(200);
        int logsBefore = count(output.getOut(), "evento=api.catalogo_desconhecido");

        for (TestUser u : List.of(wsUser, orgUser)) {
            BrowserClient n = browser();
            firstLogin(n, o.id(), u);
            for (String path : List.of("/me/permissions", "/me/permissions/workspaces")) {
                BrowserClient.Response r = n.get("/api/v1/o/" + o.id() + path);
                assertThat(r.status()).as(path + " (mesma politica em todos os caminhos)").isEqualTo(500);
                assertThat(JSON.readTree(r.body()).get("error").asString()).isEqualTo("internal_error");
            }
        }
        assertThat(count(output.getOut(), "evento=api.catalogo_desconhecido") - logsBefore).as("um log por recusa").isEqualTo(4);
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
