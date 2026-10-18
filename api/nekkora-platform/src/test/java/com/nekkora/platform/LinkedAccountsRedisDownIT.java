package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.core.token.Tokens;

/**
 * Contas vinculadas (P3 e P4) com o Redis INDISPONIVEL: os limites falham fechado (503) sem conferir senha nem codigo e
 * sem efeito - nenhum desafio de vinculacao, nenhum vinculo, nenhum step-up, nenhum passo consumido. Redis EXCLUSIVO desta
 * classe, derrubado de proposito.
 */
// contexto proprio (propriedades da classe): fechado ao fim dela, para nao manter um pool a mais aberto contra o limite
// de conexoes do PostgreSQL de teste, compartilhado pela suite inteira
@DirtiesContext
class LinkedAccountsRedisDownIT extends IdentityTestBase {

    private static final RealRedis REDIS = RealRedis.exclusive();

    static {
        exclusiveRedis = REDIS;
    }

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.vinculacao.enabled", () -> "true");
        r.add("nekkora.test.exclusive-redis-contas-vinculadas", () -> "yes");
    }

    @AfterAll
    static void cleanUp() {
        exclusiveRedis = null;
    }

    @Test
    void vinculacaoEStepUpPorOrganizacaoFalhamFechadoSemRedis() throws Exception {
        // P3: conta antiga com fator local e identidade do mesmo e-mail, com desafio de vinculacao ja emitido
        Org o = newOrganization();
        TestUser antiga = internalUser(o, true, 2);
        firstLogin(browser(), o.id(), antiga);
        UUID identidade = identidadeAtiva(antiga.email(), SENHA);
        BrowserClient id = sessaoPelaEntradaGeral(antiga.email(), identidade);
        // P4: organizacao criada pelo cadastro e sessao de organizacao vinculada
        ComFator f = comFator();
        UUID org = LinkedAccountLocalEntryIT.organizacaoDa(f.browser(), "Org sem Redis");
        BrowserClient.Response troca = f.browser().post("/api/v1/sessions/organization", "{\"organizationId\":\"" + org + "\"}");
        assertThat(troca.field("status")).isEqualTo("sessao_criada");
        f.browser().csrf = troca.field("csrfToken");
        String sessaoOrg = value("SELECT session_id FROM user_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(f.browser().cookies().get("__Host-nekkora_session")) + "'");
        String passoLocal = value("SELECT last_used_step FROM user_mfa_factors WHERE user_id = '" + antiga.id() + "' AND disabled_at IS NULL");
        String passoIdentidade = value("SELECT last_used_step FROM identity_mfa_factors WHERE identity_id = '" + f.identity() + "' AND disabled_at IS NULL");
        REDIS.shutDown();

        BrowserClient.Response start = id.post("/api/v1/links/start",
                "{\"organizationId\":\"" + o.id() + "\",\"password\":\"" + PASSWORD + "\",\"code\":\"" + code(antiga.id()) + "\"}");
        assertThat(start.status()).as("etapa 1").isEqualTo(503);
        id.cookies().put("__Host-nekkora_link", Tokens.newToken());
        assertThat(id.post("/api/v1/links/confirm", "{\"organizationId\":\"" + o.id() + "\"}").status()).as("etapa 2").isEqualTo(503);
        assertThat(count("SELECT count(*) FROM identity_link_challenges WHERE identity_id = '" + identidade + "'")).as("sem desafio").isZero();
        assertThat(count("SELECT count(*) FROM identity_users WHERE user_id = '" + antiga.id() + "'")).as("sem vinculo").isZero();
        assertThat(value("SELECT last_used_step FROM user_mfa_factors WHERE user_id = '" + antiga.id() + "' AND disabled_at IS NULL"))
                .as("codigo local nao conferido").isEqualTo(passoLocal);

        BrowserClient.Response up = f.browser().post("/api/v1/o/" + org + "/auth/mfa/step-up", "{\"code\":\"" + code(f.identity()) + "\"}");
        assertThat(up.status()).as("step-up por organizacao").isEqualTo(503);
        assertThat(value("SELECT mfa_step_up_at IS NULL FROM user_sessions WHERE session_id = '" + sessaoOrg + "'")).as("sem step-up").isEqualTo("t");
        assertThat(value("SELECT last_used_step FROM identity_mfa_factors WHERE identity_id = '" + f.identity() + "' AND disabled_at IS NULL"))
                .as("codigo da identidade nao conferido").isEqualTo(passoIdentidade);
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + f.identity() + "' AND event_type = 14")).isZero();
    }
}
