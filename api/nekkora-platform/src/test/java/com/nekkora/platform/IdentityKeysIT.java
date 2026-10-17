package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/**
 * Chaves SEPARADAS (revisao do bloco 2): cadastro DESLIGADO e entrada geral LIGADA. Novos cadastros suspensos nao impedem
 * quem ja tem identidade de entrar (login e MFA) e de sair (logout).
 */
class IdentityKeysIT extends IdentityTestBase {

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.entrada-geral.enabled", () -> "true");
    }

    private static final String NOT_FOUND = "{\"error\":\"not_found\"}";

    @Test
    void cadastroDesligadoNaoImpedeEntrarESair() throws Exception {
        for (String rota : new String[] {"/api/v1/signup", "/api/v1/signup/confirm", "/api/v1/signup/confirmation/resend"}) {
            BrowserClient.Response r = client().post(rota, "{\"email\":\"x@exemplo.test\"}");
            assertThat(r.status()).as(rota + " desligada").isEqualTo(404);
            assertThat(r.body()).isEqualTo(NOT_FOUND);
        }
        String e = email();
        UUID id = identidadeAtiva(e, SENHA);
        BrowserClient b = sessaoPelaEntradaGeral(e, id);
        assertThat(sessoesAtivas(id)).as("login e primeiro fator disponiveis").isEqualTo(1);

        BrowserClient c = client();
        assertThat(login(c, e, SENHA).field("status")).isEqualTo("mfa_required");
        assertThat(verifyTotp(c, id).status()).as("verificacao disponivel").isEqualTo(200);
        assertThat(sessoesAtivas(id)).isEqualTo(2);

        String hash = Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id"));
        assertThat(b.post("/api/v1/auth/logout", null).status()).as("logout disponivel").isEqualTo(204);
        assertThat(value("SELECT revoked_at IS NOT NULL FROM identity_sessions WHERE session_token_hash = '" + hash + "'")).isEqualTo("t");
        assertThat(sessoesAtivas(id)).isEqualTo(1);
    }
}
