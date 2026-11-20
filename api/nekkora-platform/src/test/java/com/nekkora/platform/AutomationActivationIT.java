package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

import tools.jackson.databind.JsonNode;

/**
 * PLATFORM-FLUXO-002 - ATIVACAO UNIFICADA, pela API real (nekkora_platform), com a massa declarada como superusuario
 * (organizacao, ambiente, automacoes, versoes publicadas, Workers e licencas: conceder e renovar sao atos do operador, com
 * testes proprios). Confere: um passo vincula a licenca e emite execucao + monitoramento so para os Workers escolhidos; uma
 * licenca, qualquer numero de Workers; repetir nao consome nem duplica; sem saldo, recusa antes de gravar; configuracao
 * pendente; versao nova so por aceite explicito; renovacao das autorizacoes (069) sem estender, sem reviver revogada e sem
 * maquina nova; autorizacoes ja existentes preservadas; quem nao administra a organizacao nao ativa; isolamento; e o modelo
 * anterior.
 */
class AutomationActivationIT extends ReadRoutesSupport {

    static final String FP1 = "1".repeat(64);
    static final String FP2 = "2".repeat(64);

    // =========================================================================== massa declarada

    private static String one(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long count(String sql) throws Exception {
        return Long.parseLong(one(sql));
    }

    private static Org organization() {
        Org o = newOrganization();
        for (int code = 1; code <= 5; code++) {
            DB.asSuperuser("INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, "
                    + "limit_value, provisioned_by_user_id) VALUES ('" + o.id() + "', " + code + ", 1, now(), 2, NULL, '" + o.adminId() + "')");
        }
        return o;
    }

    private static UUID automation(Org o, UUID ws, String name) throws Exception {
        return UUID.fromString(one("INSERT INTO automations (organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('" + o.id() + "', '"
                + ws + "', '" + name + "', 1, '" + o.adminMembership() + "') RETURNING automation_id"));
    }

    /** Versao publicada com impressao de pacote externo declarada (a regra que a execucao remota aceita). */
    private static UUID published(Org o, UUID ws, UUID automation, String fingerprint) {
        UUID version = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) "
                        + "VALUES ('" + version + "', '" + o.id() + "', '" + ws + "', '" + automation + "', 1, jsonb_build_object('contract_version',1,'artifact',"
                        + "jsonb_build_object('reference','robo','sha256','" + "ab".repeat(32) + "'),'agent_package',jsonb_build_object('fingerprint_rule',"
                        + "'nekkora-agent/external-python-fingerprint/1','fingerprint','" + fingerprint + "'),'runtime','python3.12','entrypoint','main.py',"
                        + "'arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '" + o.adminMembership() + "')",
                "UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '" + o.adminMembership()
                        + "' WHERE automation_version_id = '" + version + "'");
        return version;
    }

    private static UUID worker(Org o, UUID ws, String name) throws Exception {
        UUID worker = UUID.fromString(one("INSERT INTO workers (organization_id, name, status) VALUES ('" + o.id() + "', '" + name + "', 1) RETURNING worker_id"));
        DB.asSuperuser("INSERT INTO worker_workspace_assignments (organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('"
                + o.id() + "', '" + worker + "', '" + ws + "', 1, '" + o.adminMembership() + "')");
        return worker;
    }

    private void stepUp(BrowserClient b, UUID org, UUID user) {
        assertThat(b.post("/api/v1/o/" + org + "/auth/mfa/step-up", "{\"code\":\"" + code(user) + "\"}").status()).isEqualTo(204);
    }

    private static String activation(UUID automation, List<UUID> workers, String validUntil) {
        List<String> ids = new ArrayList<>();
        workers.forEach(w -> ids.add("\"" + w + "\""));
        return "{\"automationId\":\"" + automation + "\",\"workerIds\":[" + String.join(",", ids) + "]"
                + (validUntil == null ? "" : ",\"validUntil\":\"" + validUntil + "\"") + "}";
    }

    private static BrowserClient.Response activate(BrowserClient b, UUID org, UUID automation, List<UUID> workers) {
        return b.post(path(org, "automation-activations", null), activation(automation, workers, null));
    }

    private static JsonNode ok(BrowserClient.Response r) {
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private static void conflict(BrowserClient.Response r, String reason) {
        assertThat(r.status()).as(r.body()).isEqualTo(409);
        assertThat(JSON.readTree(r.body()).get("reason").asString()).isEqualTo(reason);
    }

    private static List<String> outcomes(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.get("results").forEach(x -> out.add(x.get("kind").asString() + ":" + x.get("outcome").asString()));
        return out;
    }

    private static long vigentes(UUID org, UUID automation) throws Exception {
        return count("SELECT count(*) FROM local_run_authorizations WHERE organization_id = '" + org + "' AND automation_id = '" + automation
                + "' AND revoked_at IS NULL AND valid_until > clock_timestamp()");
    }

    private static long assignments(UUID org) throws Exception {
        return count("SELECT count(*) FROM organization_license_assignments WHERE organization_id = '" + org + "' AND released_at IS NULL");
    }

    private static String days(int n) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(n).withNano(0).toString();
    }

    // =========================================================================== modelo por licenca

    @Test
    void umPassoAtivaExecucaoEMonitoramentoSoNosWorkersEscolhidosComUmaLicenca() throws Exception {
        Org o = organization();
        LicenseFixtures.enforce(o.id(), o.adminId());
        UUID ws = newWorkspace(o, newClient(o));
        UUID robo = automation(o, ws, "Robo de notas");
        UUID v1 = published(o, ws, robo, FP1);
        UUID w1 = worker(o, ws, "Estacao 1");
        UUID w2 = worker(o, ws, "Estacao 2");
        UUID w3 = worker(o, ws, "Estacao 3 (nao escolhida)");
        UUID l1 = LicenseFixtures.license(o.id(), 1, "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '30 days'");
        LicenseFixtures.license(o.id(), 2, "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '30 days'");
        BrowserClient admin = admin(o);

        // ---- a preparacao: a automacao, os Workers do ambiente e a licenca que seria usada; nada reservado
        Got opt = get(admin, path(o.id(), "automation-activations/options", "workspaceId=" + ws));
        assertOk(opt, "preparacao");
        assertThat(opt.body().get("model").asString()).isEqualTo("licensed");
        assertThat(opt.body().get("licenses").get("available").asLong()).isEqualTo(2);
        assertThat(opt.body().get("nextLicense").get("number").asInt()).isEqualTo(1);
        assertThat(field(opt.body().get("workers"), "name")).containsExactly("Estacao 1", "Estacao 2", "Estacao 3 (nao escolhida)");
        assertThat(opt.body().get("automations").get(0).get("licensed").asBoolean()).isFalse();
        assertThat(assignments(o.id())).isZero();

        // ---- sem confirmacao reforcada: recusado, nada gravado
        assertThat(activate(admin, o.id(), robo, List.of(w1, w2)).status()).isEqualTo(403);
        assertThat(assignments(o.id())).isZero();
        stepUp(admin, o.id(), o.adminId());

        // ---- um passo: UMA licenca e, para os DOIS Workers escolhidos, execucao da versao atual + monitoramento
        JsonNode r = ok(activate(admin, o.id(), robo, List.of(w1, w2)));
        assertThat(r.get("licenseConsumedNow").asBoolean()).isTrue();
        assertThat(outcomes(r)).containsExactly("execution:issued", "monitoring:issued", "execution:issued", "monitoring:issued");
        JsonNode act = r.get("activation");
        assertThat(act.get("state").asString()).isEqualTo("ACTIVE");
        assertThat(act.get("license").get("number").asInt()).isEqualTo(1);
        assertThat(act.get("license").get("state").asString()).isEqualTo("OK");
        assertThat(act.get("currentVersion").get("versionId").asString()).isEqualTo(v1.toString());
        assertThat(act.get("acceptedVersion").get("versionId").asString()).isEqualTo(v1.toString());
        assertThat(field(act.get("workers"), "name")).containsExactlyInAnyOrder("Estacao 1", "Estacao 2");
        assertThat(assignments(o.id())).as("uma licenca, qualquer numero de Workers").isEqualTo(1);
        assertThat(one("SELECT license_id FROM organization_license_assignments WHERE automation_id = '" + robo + "'")).isEqualTo(l1.toString());
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE automation_id = '" + robo + "' AND worker_id = '" + w3 + "'"))
                .as("Worker nao escolhido nao recebe nada").isZero();
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE automation_id = '" + robo + "' AND source_kind = 3")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE automation_id = '" + robo + "' AND source_kind = 2 AND automation_version_id = '"
                + v1 + "'")).isEqualTo(2);
        // a validade e a da cobertura da licenca (D3): nunca alem dela
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE automation_id = '" + robo
                + "' AND valid_until > license_coverage_end('" + o.id() + "', '" + robo + "', clock_timestamp())")).isZero();
        assertThat(count("SELECT count(*) FROM organization_license_acts WHERE organization_id = '" + o.id() + "' AND act = 4")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE organization_id = '" + o.id() + "' AND act = 10")).isEqualTo(4);

        // ---- repetir (clique duplo, resposta perdida): nada consumido, nada duplicado
        JsonNode again = ok(activate(admin, o.id(), robo, List.of(w1, w2)));
        assertThat(again.get("licenseConsumedNow").asBoolean()).isFalse();
        assertThat(outcomes(again)).containsOnly("execution:already", "monitoring:already");
        assertThat(assignments(o.id())).isEqualTo(1);
        assertThat(vigentes(o.id(), robo)).isEqualTo(4);

        // ---- gerenciar: acrescentar um Worker nao consome outra licenca
        JsonNode add = ok(activate(admin, o.id(), robo, List.of(w3)));
        assertThat(add.get("licenseConsumedNow").asBoolean()).isFalse();
        assertThat(outcomes(add)).containsExactly("execution:issued", "monitoring:issued");
        assertThat(assignments(o.id())).isEqualTo(1);

        // ---- a conta: 2 contratadas, 1 utilizada, 1 disponivel; a lista mostra ambiente e validade
        Got list = get(admin, path(o.id(), "automation-activations", null));
        assertOk(list, "lista");
        assertThat(list.body().get("licenses").get("used").asLong()).isEqualTo(1);
        assertThat(list.body().get("licenses").get("available").asLong()).isEqualTo(1);
        JsonNode item = list.body().get("items").get(0);
        assertThat(item.get("workspaceId").asString()).isEqualTo(ws.toString());
        assertThat(item.get("license").get("coverageUntil").isNull()).isFalse();
    }

    @Test
    void semSaldoRecusaAntesDeGravarEWorkerForaDoAmbienteNaoConsomeLicenca() throws Exception {
        Org o = organization();
        LicenseFixtures.enforce(o.id(), o.adminId());
        UUID ws = newWorkspace(o, newClient(o));
        UUID other = newWorkspace(o, newClient(o));
        UUID robo = automation(o, ws, "Robo");
        published(o, ws, robo, FP1);
        UUID w1 = worker(o, ws, "Estacao");
        UUID alheio = worker(o, other, "De outro ambiente");
        BrowserClient admin = admin(o);
        stepUp(admin, o.id(), o.adminId());

        // ---- sem licenca disponivel: recusa com o motivo, nada vinculado nem autorizado
        conflict(activate(admin, o.id(), robo, List.of(w1)), "license_unavailable");
        assertThat(assignments(o.id())).isZero();
        assertThat(vigentes(o.id(), robo)).isZero();
        Got opt = get(admin, path(o.id(), "automation-activations/options", "workspaceId=" + ws));
        assertThat(opt.body().get("nextLicense").isNull()).as("a tela sabe antes da confirmacao").isTrue();

        // ---- Worker de outro ambiente: recusa ANTES de consumir a licenca
        LicenseFixtures.license(o.id(), 1, "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '30 days'");
        conflict(activate(admin, o.id(), robo, List.of(alheio)), "activation_worker_not_in_workspace");
        assertThat(assignments(o.id())).isZero();

        // ---- sem Worker: a licenca e vinculada e a ativacao fica "configuracao pendente", sem dizer que esta pronta
        JsonNode r = ok(activate(admin, o.id(), robo, List.of()));
        assertThat(r.get("licenseConsumedNow").asBoolean()).isTrue();
        assertThat(r.get("results").size()).isZero();
        assertThat(r.get("activation").get("state").asString()).isEqualTo("CONFIGURATION_PENDING");
        assertThat(r.get("activation").get("pending").toString()).contains("NO_WORKER");
        // ---- concluir depois: o Worker entra, sem consumir outra licenca
        JsonNode done = ok(activate(admin, o.id(), robo, List.of(w1)));
        assertThat(done.get("licenseConsumedNow").asBoolean()).isFalse();
        assertThat(done.get("activation").get("state").asString()).isEqualTo("ACTIVE");
        assertThat(assignments(o.id())).isEqualTo(1);
    }

    @Test
    void semVersaoPublicadaFicaPendenteEVersaoNovaSoComAceiteExplicito() throws Exception {
        Org o = organization();
        LicenseFixtures.enforce(o.id(), o.adminId());
        UUID ws = newWorkspace(o, newClient(o));
        UUID robo = automation(o, ws, "Robo");
        UUID w1 = worker(o, ws, "Estacao");
        LicenseFixtures.license(o.id(), 1, "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '30 days'");
        BrowserClient admin = admin(o);
        stepUp(admin, o.id(), o.adminId());

        // ---- sem versao publicada: monitoramento emitido, execucao pendente
        JsonNode r = ok(activate(admin, o.id(), robo, List.of(w1)));
        assertThat(outcomes(r)).containsExactly("monitoring:issued");
        assertThat(r.get("activation").get("state").asString()).isEqualTo("CONFIGURATION_PENDING");
        assertThat(r.get("activation").get("pending").toString()).contains("NO_PUBLISHED_VERSION");

        // ---- a versao 1 e publicada: completar a ativacao a aceita
        UUID v1 = published(o, ws, robo, FP1);
        assertThat(outcomes(ok(activate(admin, o.id(), robo, List.of(w1))))).containsExactly("execution:issued", "monitoring:already");

        // ---- a versao 2 e publicada: NAO e autorizada sozinha; a tela avisa; o monitoramento continua valendo
        UUID v2 = published(o, ws, robo, FP2);
        JsonNode d = get(admin, path(o.id(), "automation-activations/" + robo, null)).body();
        assertThat(d.get("pending").toString()).contains("NEW_VERSION_AVAILABLE");
        assertThat(d.get("acceptedVersion").get("versionId").asString()).isEqualTo(v1.toString());
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE automation_version_id = '" + v2 + "'")).isZero();

        // ---- aceite explicito da versao 2: so execucao daquele pacote, nos Workers da ativacao; nenhuma licenca nova
        JsonNode acc = ok(admin.post(path(o.id(), "automation-activations/" + robo + "/version", null), "{\"automationVersionId\":\"" + v2 + "\"}"));
        assertThat(outcomes(acc)).containsExactly("execution:issued");
        assertThat(acc.get("activation").get("acceptedVersion").get("versionId").asString()).isEqualTo(v2.toString());
        assertThat(assignments(o.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE automation_id = '" + robo + "' AND source_kind = 3")).isEqualTo(1);
    }

    @Test
    void renovacaoEmiteNovasAteACoberturaSemEstenderSemReviverRevogadaESemDuplicar() throws Exception {
        Org o = organization();
        LicenseFixtures.enforce(o.id(), o.adminId());
        UUID ws = newWorkspace(o, newClient(o));
        UUID robo = automation(o, ws, "Robo");
        UUID v1 = published(o, ws, robo, FP1);
        UUID w1 = worker(o, ws, "Estacao 1");
        UUID w2 = worker(o, ws, "Estacao 2");
        UUID l1 = LicenseFixtures.license(o.id(), 1, "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '10 days'");
        BrowserClient admin = admin(o);
        stepUp(admin, o.id(), o.adminId());
        ok(activate(admin, o.id(), robo, List.of(w1, w2)));
        // ---- antes da renovacao da licenca: nada a renovar
        assertThat(get(admin, path(o.id(), "automation-activations/" + robo, null)).body().get("state").asString()).isEqualTo("ACTIVE");
        assertThat(ok(admin.post(path(o.id(), "automation-activations/" + robo + "/renewal", null), "{}")).get("results").size()).isZero();

        // ---- uma pessoa retira o monitoramento do Worker 2 (revogacao manual): essa decisao vale na renovacao
        UUID monW2 = UUID.fromString(one("SELECT authorization_id FROM local_run_authorizations WHERE worker_id = '" + w2 + "' AND source_kind = 3"));
        assertThat(admin.post("/api/v1/o/" + o.id() + "/workspaces/" + ws + "/local-run-authorizations/" + monW2 + "/revoke", "{}").status()).isEqualTo(204);
        assertThat(one("SELECT revocation_reason FROM local_run_authorizations WHERE authorization_id = '" + monW2 + "'")).isEqualTo("1");

        // ---- a licenca e renovada sem lacuna; uma versao mais nova e publicada; um Worker novo entra no ambiente
        LicenseFixtures.renew(o.id(), l1, 2, "(SELECT ends_at FROM organization_license_periods WHERE license_id = '" + l1 + "' AND period_number = 1)",
                "clock_timestamp() + interval '40 days'");
        UUID v2 = published(o, ws, robo, FP2);
        UUID w3 = worker(o, ws, "Estacao nova");
        JsonNode before = get(admin, path(o.id(), "automation-activations/" + robo, null)).body();
        assertThat(before.get("state").asString()).isEqualTo("RENEWAL_AVAILABLE");
        long beforeAuths = count("SELECT count(*) FROM local_run_authorizations WHERE automation_id = '" + robo + "'");

        // ---- renovar: as mesmas maquinas e a versao ja aceita (v1); nunca a revogada por pessoa, nunca o Worker novo, nunca a v2
        JsonNode r = ok(admin.post(path(o.id(), "automation-activations/" + robo + "/renewal", null), "{}"));
        assertThat(outcomes(r)).containsExactlyInAnyOrder("execution:issued", "monitoring:issued", "execution:issued");
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE automation_id = '" + robo + "'")).isEqualTo(beforeAuths + 3);
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE automation_version_id = '" + v2 + "'")).isZero();
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE worker_id = '" + w3 + "'")).isZero();
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE renewal_of_authorization_id = '" + monW2 + "'")).isZero();
        // as anteriores ficam no historico, substituidas (motivo 2), e cada nova aponta para a sua
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE automation_id = '" + robo + "' AND revocation_reason = 2")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM local_run_authorizations n JOIN local_run_authorizations p ON p.authorization_id = n.renewal_of_authorization_id "
                + "WHERE n.automation_id = '" + robo + "' AND p.revocation_reason = 2 AND n.valid_until > p.valid_until")).isEqualTo(3);
        // nada alem da cobertura atual
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE automation_id = '" + robo
                + "' AND valid_until > license_coverage_end('" + o.id() + "', '" + robo + "', clock_timestamp())")).isZero();
        assertThat(vigentes(o.id(), robo)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM local_run_authorizations WHERE automation_id = '" + robo + "' AND automation_version_id = '" + v1
                + "' AND revoked_at IS NULL")).isEqualTo(2);

        // ---- repetir: nada duplicado; o estado volta a ativo (com a versao nova so avisada)
        JsonNode again = ok(admin.post(path(o.id(), "automation-activations/" + robo + "/renewal", null), "{}"));
        assertThat(again.get("results").size()).isZero();
        assertThat(again.get("activation").get("pending").toString()).doesNotContain("RENEWAL_AVAILABLE").contains("NEW_VERSION_AVAILABLE");
        assertThat(assignments(o.id())).as("renovar nao consome licenca").isEqualTo(1);
    }

    @Test
    void autorizacoesJaExistentesSaoPreservadasEReaproveitadasSemNovoConsumo() throws Exception {
        Org o = organization();
        LicenseFixtures.enforce(o.id(), o.adminId());
        UUID ws = newWorkspace(o, newClient(o));
        UUID robo = automation(o, ws, "Ja licenciado");
        UUID v1 = published(o, ws, robo, FP1);
        UUID w1 = worker(o, ws, "Estacao");
        UUID l1 = LicenseFixtures.license(o.id(), 1, "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '30 days'");
        LicenseFixtures.assign(o.id(), l1, ws, robo, o.adminMembership());
        BrowserClient admin = admin(o);
        stepUp(admin, o.id(), o.adminId());
        // ---- a autorizacao de execucao feita pela rota antiga (como hoje)
        BrowserClient.Response old = admin.post("/api/v1/o/" + o.id() + "/workspaces/" + ws + "/local-run-authorizations",
                "{\"workerId\":\"" + w1 + "\",\"automationVersionId\":\"" + v1 + "\",\"validUntil\":\"" + days(10) + "\"}");
        assertThat(old.status()).as(old.body()).isEqualTo(201);
        String oldId = JSON.readTree(old.body()).get("authorizationId").asString();

        // ---- a lista ja mostra a automacao licenciada; ativar so completa o que falta (monitoramento)
        assertThat(field(get(admin, path(o.id(), "automation-activations", null)).body().get("items"), "name")).containsExactly("Ja licenciado");
        JsonNode r = ok(activate(admin, o.id(), robo, List.of(w1)));
        assertThat(r.get("licenseConsumedNow").asBoolean()).isFalse();
        assertThat(outcomes(r)).containsExactly("execution:already", "monitoring:issued");
        assertThat(one("SELECT revoked_at IS NULL FROM local_run_authorizations WHERE authorization_id = '" + oldId + "'")).isEqualTo("t");
        assertThat(assignments(o.id())).isEqualTo(1);
    }

    @Test
    void quemNaoAdministraAOrganizacaoNaoAtivaEOutraOrganizacaoNaoExiste() throws Exception {
        Org o = organization();
        LicenseFixtures.enforce(o.id(), o.adminId());
        UUID ws = newWorkspace(o, newClient(o));
        UUID robo = automation(o, ws, "Robo");
        published(o, ws, robo, FP1);
        UUID w1 = worker(o, ws, "Estacao");
        LicenseFixtures.license(o.id(), 1, "clock_timestamp() - interval '1 day'", "clock_timestamp() + interval '30 days'");
        for (String role : List.of("WORKSPACE_ADMIN", "OPERATOR", "VIEWER")) {
            TestUser u = internalUser(o, true, 2);
            AccessFixtures.assignWorkspaceRole(o.id(), AccessFixtures.membershipOf(o.id(), u.id()), ws, role, o.adminMembership());
            BrowserClient b = login(o, u);
            stepUp(b, o.id(), u.id());
            assertThat(activate(b, o.id(), robo, List.of(w1)).status()).as(role + " nao ativa").isEqualTo(403);
            assertThat(get(b, path(o.id(), "automation-activations", null)).status()).as(role + " nao le").isEqualTo(403);
            assertThat(b.post(path(o.id(), "automation-activations/" + robo + "/renewal", null), "{}").status()).isEqualTo(403);
        }
        assertThat(assignments(o.id())).isZero();
        assertThat(vigentes(o.id(), robo)).isZero();

        Org other = organization();
        UUID ows = newWorkspace(other, newClient(other));
        UUID orobo = automation(other, ows, "Da outra");
        BrowserClient admin = admin(o);
        stepUp(admin, o.id(), o.adminId());
        assertThat(activate(admin, o.id(), orobo, List.of()).status()).as("automacao de outra organizacao").isEqualTo(404);
        assertThat(get(admin, path(o.id(), "automation-activations/options", "workspaceId=" + ows)).status()).isEqualTo(404);
        assertThat(admin.post(path(o.id(), "automation-activations", null), "{\"automationId\":\"nao-e-uuid\"}").status()).isEqualTo(400);
    }

    // =========================================================================== modelo anterior

    @Test
    void modeloAnteriorAtivaSemExigirLicencaComAValidadeInformada() throws Exception {
        Org o = organization();
        UUID ws = newWorkspace(o, newClient(o));
        UUID robo = automation(o, ws, "Robo");
        published(o, ws, robo, FP1);
        UUID w1 = worker(o, ws, "Estacao");
        BrowserClient admin = admin(o);
        stepUp(admin, o.id(), o.adminId());
        assertThat(admin.post(path(o.id(), "automation-activations", null), activation(robo, List.of(w1), null)).status())
                .as("sem validade no modelo anterior").isEqualTo(400);
        JsonNode r = ok(admin.post(path(o.id(), "automation-activations", null), activation(robo, List.of(w1), days(20))));
        assertThat(outcomes(r)).containsExactly("execution:issued", "monitoring:issued");
        assertThat(r.has("licenseConsumedNow") && r.get("licenseConsumedNow").asBoolean()).isFalse();
        assertThat(assignments(o.id())).isZero();
        assertThat(r.get("activation").get("state").asString()).isEqualTo("ACTIVE");
        conflict(admin.post(path(o.id(), "automation-activations/" + robo + "/renewal", null), "{}"), "activation_renewal_not_applicable");
    }
}
