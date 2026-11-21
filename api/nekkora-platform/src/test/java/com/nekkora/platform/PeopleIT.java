package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.mail.MailSender;


import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PLATFORM-FLUXO-002 — "Pessoas e acessos" (GET /people): membros e convites numa lista so, com os convites reais (emitir,
 * aceitar, revogar, vencer). Ordem da lista inteira com cursor (pendentes primeiro, do mais recente; depois membros, da
 * entrada mais recente); o convite aceito nao repete o membro na visualizacao padrao e continua no historico pelo filtro;
 * busca por nome/e-mail; filtro por situacao; so org.manage; isolamento entre organizacoes.
 */
@DirtiesContext
class PeopleIT extends IdentityTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.convites.enabled", () -> "true");
    }

    private record Admin(Org org, TestUser user, BrowserClient browser) {
    }

    private Admin admin() {
        Org o = newOrganization();
        TestUser u = new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray());
        BrowserClient b = browser();
        firstLogin(b, o.id(), u);
        assertThat(b.post("/api/v1/o/" + o.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(u.id()) + "\"}").status()).isEqualTo(204);
        return new Admin(o, u, b);
    }

    private String invite(Admin a, String email) {
        BrowserClient.Response r = a.browser().post("/api/v1/o/" + a.org().id() + "/invitations", "{\"email\":" + q(email) + "}");
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.field("invitationId");
    }

    private String lastToken(String email) {
        List<PlatformTestBase.SentMessage> m = mails(email).stream().filter(x -> x.kind() == MailSender.Kind.ORG_INVITATION).toList();
        return m.get(m.size() - 1).token();
    }

    private static JsonNode page(BrowserClient b, UUID org, String query) throws Exception {
        BrowserClient.Response r = b.get("/api/v1/o/" + org + "/people" + (query == null ? "" : "?" + query));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    /** Linhas na ordem: "kind:email:situacao". */
    private static List<String> rows(JsonNode page) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : page.get("items")) {
            if (n.get("kind").asString().equals("member")) {
                out.add("member:" + n.get("member").get("email").asString().toLowerCase() + ":" + n.get("member").get("membershipStatus").asString());
            } else {
                out.add("invitation:" + n.get("invitation").get("email").asString() + ":" + n.get("invitation").get("state").asString());
            }
        }
        return out;
    }

    @Test
    void membrosEConvitesNumaListaSoComOrdemBuscaFiltroEAceiteSemDuplicar() throws Exception {
        Admin a = admin();
        UUID org = a.org().id();
        String admin = a.org().adminEmail().toLowerCase();

        // tres convites, em ordem de criacao (o mais recente vem primeiro na lista)
        String velho = "velho-" + UUID.randomUUID() + "@exemplo.test";
        String revogar = "revogar-" + UUID.randomUUID() + "@exemplo.test";
        String vencer = "vencer-" + UUID.randomUUID() + "@exemplo.test";
        String vInv = invite(a, velho);
        Thread.sleep(5);
        String rInv = invite(a, revogar);
        Thread.sleep(5);
        String xInv = invite(a, vencer);
        // quem aceita: identidade com fator, convidada por ultimo
        ComFator f = comFator();
        String fInv = invite(a, f.email());

        JsonNode p = page(a.browser(), org, null);
        assertThat(p.get("invitationsEnabled").asBoolean()).isTrue();
        assertThat(rows(p)).containsExactly("invitation:" + f.email().toLowerCase() + ":PENDENTE", "invitation:" + vencer + ":PENDENTE",
                "invitation:" + revogar + ":PENDENTE", "invitation:" + velho + ":PENDENTE", "member:" + admin + ":active");

        // revogar um, vencer outro, aceitar um
        assertThat(a.browser().post("/api/v1/o/" + org + "/invitations/" + rInv + "/revoke", null).status()).isEqualTo(204);
        // passagem do tempo: o convite vence (so o prazo muda; replica, como na massa dos demais testes)
        exec("SET session_replication_role = replica; UPDATE identity_invitations SET created_at = now() - interval '8 days', expires_at = now() - interval '1 minute' WHERE invitation_id = '" + xInv
                + "'; SET session_replication_role = origin");
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
        BrowserClient.Response ac = f.browser().post("/api/v1/invitations/accept", "{\"organizationId\":\"" + org + "\",\"token\":" + q(lastToken(f.email())) + "}");
        assertThat(ac.status()).as(ac.body()).isEqualTo(200);

        // padrao: o pendente que sobrou primeiro; depois os membros, da entrada mais recente — o aceito vira membro, uma vez so
        p = page(a.browser(), org, null);
        assertThat(rows(p)).containsExactly("invitation:" + velho + ":PENDENTE", "member:" + f.email().toLowerCase() + ":active", "member:" + admin + ":active");
        JsonNode novo = p.get("items").get(1).get("member");
        assertThat(novo.get("roles").size()).isZero();
        assertThat(novo.get("joinedAt").isString()).isTrue();

        // historico pelo filtro: o aceito aponta para a conta criada; vencido e revogado consultaveis
        p = page(a.browser(), org, "situation=invitation_accepted&situation=invitation_revoked&situation=invitation_expired");
        assertThat(rows(p)).containsExactlyInAnyOrder("invitation:" + f.email().toLowerCase() + ":ACEITO", "invitation:" + revogar + ":REVOGADO",
                "invitation:" + vencer + ":EXPIRADO");
        for (JsonNode n : p.get("items")) {
            if (n.get("invitation").get("state").asString().equals("ACEITO")) {
                assertThat(n.get("invitation").get("acceptedUserId").asString()).isEqualTo(novo.get("userId").asString());
            }
        }
        assertThat(rows(page(a.browser(), org, "situation=invitation_pending"))).containsExactly("invitation:" + velho + ":PENDENTE");
        assertThat(rows(page(a.browser(), org, "situation=member_active"))).hasSize(2);

        // busca por e-mail (parte, sem diferenciar maiusculas) e por nome
        assertThat(rows(page(a.browser(), org, "q=VELHO-"))).containsExactly("invitation:" + velho + ":PENDENTE");
        assertThat(rows(page(a.browser(), org, "q=" + f.email().substring(0, 6)))).containsExactly("member:" + f.email().toLowerCase() + ":active");
        assertThat(rows(page(a.browser(), org, "q=nao-existe-ninguem"))).isEmpty();

        // a mesma ordem pela lista inteira, pagina a pagina (com o historico incluido no filtro)
        String all = "situation=invitation_pending&situation=member_active&situation=invitation_accepted&situation=invitation_revoked&situation=invitation_expired";
        List<String> inteira = rows(page(a.browser(), org, all + "&limit=100"));
        List<String> paginada = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode pg = page(a.browser(), org, all + "&limit=2" + (cursor == null ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8)));
            paginada.addAll(rows(pg));
            cursor = pg.get("nextCursor").isNull() ? null : pg.get("nextCursor").asString();
        } while (cursor != null);
        assertThat(paginada).isEqualTo(inteira).hasSize(6);
        assertThat(inteira.get(0)).startsWith("invitation:" + velho);
        assertThat(inteira.subList(1, 3)).allMatch(x -> x.startsWith("member:"));
        assertThat(inteira.subList(3, 6)).allMatch(x -> x.startsWith("invitation:"));

        // parametros invalidos; cursor de outro filtro recusado
        assertThat(a.browser().get("/api/v1/o/" + org + "/people?situation=outra").status()).isEqualTo(400);
        assertThat(a.browser().get("/api/v1/o/" + org + "/people?q=").status()).isEqualTo(400);
        JsonNode p1 = page(a.browser(), org, all + "&limit=2");
        assertThat(a.browser().get("/api/v1/o/" + org + "/people?limit=2&cursor="
                + java.net.URLEncoder.encode(p1.get("nextCursor").asString(), java.nio.charset.StandardCharsets.UTF_8)).status()).isEqualTo(400);

        // quem nao administra a organizacao (conta interna sem papel): 403
        TestUser semPapel = internalUser(a.org(), true, 2);
        BrowserClient sb = browser();
        firstLogin(sb, org, semPapel);
        assertThat(sb.get("/api/v1/o/" + org + "/people").status()).isEqualTo(403);
        // isolamento: o administrador de outra organizacao nao le esta
        Admin outro = admin();
        assertThat(outro.browser().get("/api/v1/o/" + org + "/people").status()).isIn(401, 403, 404);
        assertThat(rows(page(outro.browser(), outro.org().id(), null))).containsExactly("member:" + outro.org().adminEmail().toLowerCase() + ":active");
        assertThat(vInv).isNotNull();
        assertThat(fInv).isNotNull();
    }
}
