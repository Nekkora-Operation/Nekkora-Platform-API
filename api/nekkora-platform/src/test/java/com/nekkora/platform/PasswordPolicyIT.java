package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.mail.MailSender;

/**
 * PLATFORM-SENHA-001: a mesma politica (12 a 20 pontos de codigo, maiuscula, minuscula, numero e simbolo) em TODAS as rotas
 * que definem senha, chamadas diretamente: cadastro (que tambem e o primeiro acesso pelo convite de cliente), troca e
 * recuperacao. A recusa nao consome o token (o link continua valendo), nada e cortado nem aparado, a senha nao entra em log e
 * a senha EXISTENTE — criada antes, fora da regra — continua abrindo a sessao.
 */
@ExtendWith(OutputCaptureExtension.class)
class PasswordPolicyIT extends IdentityTestBase {

    /** Uma senha recusada por requisito (rotulo -> senha). */
    private static final List<String[]> FORA_DA_REGRA = List.of(
            new String[] {"curta", "Nk#2026ok"},
            new String[] {"longa (21)", "Nekkora#2026ok-maisum"},
            new String[] {"sem maiuscula", "nekkora#2026ok"},
            new String[] {"sem minuscula", "NEKKORA#2026OK"},
            new String[] {"sem numero", "Nekkora#Teste!"},
            new String[] {"sem simbolo", "Nekkora2026ok7"});
    private static final String VALIDA = "Valida#Senha2026";
    private static final String ANTIGA = "senha antiga sem regra nenhuma e bem longa";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
        r.add("nekkora.recuperacao-senha.enabled", () -> "true");
    }

    private String tokenDoCadastro(String email) {
        assertThat(client().post("/api/v1/signup", "{\"email\":" + q(email) + "}").status()).isEqualTo(202);
        List<SentMessage> m = mails(email);
        return m.get(m.size() - 1).token();
    }

    private BrowserClient.Response confirmarCadastro(String token, String senha) {
        return client().post("/api/v1/signup/confirm", "{\"token\":" + q(token) + ",\"password\":" + q(senha) + ",\"displayName\":\"Pessoa\"}");
    }

    private static String credencial(UUID identity) throws Exception {
        return value("SELECT coalesce(max(password_hash), '-') FROM identity_credentials WHERE identity_id = '" + identity + "'");
    }

    /** Cadastro: cada requisito ausente e recusado (400 senha_nova) sem consumir o token; a valida conclui com o mesmo link. */
    @Test
    void cadastroRecusaCadaRequisitoAusenteEOLinkContinuaValendo(CapturedOutput output) throws Exception {
        String email = email();
        String token = tokenDoCadastro(email);
        for (String[] c : FORA_DA_REGRA) {
            BrowserClient.Response r = confirmarCadastro(token, c[1]);
            assertThat(r.status()).as(c[0]).isEqualTo(400);
            assertThat(r.body()).as(c[0]).isEqualTo("{\"error\":\"invalid_request\"}");
        }
        assertThat(value("SELECT status FROM identities WHERE email_normalized = lower('" + email + "')")).as("segue pendente").isEqualTo("1");
        assertThat(confirmarCadastro(token, VALIDA).status()).as("o mesmo link conclui").isEqualTo(204);
        assertThat(login(client(), email, VALIDA).field("status")).isEqualTo("authenticated");

        String app = output.getAll();
        for (String[] c : FORA_DA_REGRA) {
            assertThat(app).as("log sem a senha recusada (" + c[0] + ")").doesNotContain(c[1]);
        }
        assertThat(app).doesNotContain(VALIDA);
        assertThat(DB.serverLog()).doesNotContain(VALIDA);
    }

    /** Nada e aparado nem cortado: espacos nas pontas fazem parte da senha; 21 pontos de codigo sao recusados, nao truncados. */
    @Test
    void semCorteNemAparo() throws Exception {
        String email = email();
        String token = tokenDoCadastro(email);
        assertThat(confirmarCadastro(token, " Valida#Senha26 ").status()).isEqualTo(204);
        assertThat(login(client(), email, "Valida#Senha26").status()).as("sem os espacos: outra senha").isEqualTo(401);
        assertThat(login(client(), email, " Valida#Senha26 ").field("status")).isEqualTo("authenticated");

        String outro = email();
        String t2 = tokenDoCadastro(outro);
        String longa = VALIDA + "12345"; // 21
        assertThat(confirmarCadastro(t2, longa).status()).isEqualTo(400);
        assertThat(confirmarCadastro(t2, longa.substring(0, 20)).status()).as("os 20 primeiros, enviados de fato, valem").isEqualTo(204);
        assertThat(login(client(), outro, longa).status()).as("a de 21 nunca foi gravada cortada").isEqualTo(401);
    }

    /** Senha existente fora da regra (criada antes): continua entrando; trocar exige a regra so na NOVA; a atual antiga serve. */
    @Test
    void senhaExistenteForaDaRegraContinuaEntrandoETrocaAplicaARegraSoNaNova() throws Exception {
        String email = email();
        UUID id = identidadeAtiva(email, ANTIGA);
        BrowserClient b = client();
        assertThat(login(b, email, ANTIGA).field("status")).as("senha antiga (42, sem maiuscula/numero/simbolo) entra").isEqualTo("authenticated");
        BrowserClient.Response r = enroll(b, id, ANTIGA);
        assertThat(r.status()).isEqualTo(200);
        b.csrf = r.field("csrfToken");
        BrowserClient n = client();
        assertThat(login(n, email, ANTIGA).field("status")).as("com MFA: a senha antiga segue valendo").isEqualTo("mfa_required");
        assertThat(verifyTotp(n, id).status()).isEqualTo(200);

        assertThat(b.post("/api/v1/auth/step-up", "{\"code\":\"" + code(id) + "\"}").status()).isEqualTo(204);
        String antes = credencial(id);
        for (String[] c : FORA_DA_REGRA) {
            BrowserClient.Response t = b.post("/api/v1/auth/password", "{\"currentPassword\":" + q(ANTIGA) + ",\"newPassword\":" + q(c[1]) + "}");
            assertThat(t.status()).as("troca: " + c[0]).isEqualTo(400);
            assertThat(t.body()).isEqualTo("{\"error\":\"invalid_request\"}");
        }
        assertThat(credencial(id)).as("nada mudou").isEqualTo(antes);
        assertThat(b.post("/api/v1/auth/password", "{\"currentPassword\":" + q(ANTIGA) + ",\"newPassword\":" + q(VALIDA) + "}").status()).isEqualTo(204);
        assertThat(login(client(), email, ANTIGA).status()).isEqualTo(401);
        assertThat(login(client(), email, VALIDA).field("status")).isEqualTo("mfa_required");
    }

    /** Recuperacao: fora da regra e recusada sem consumir o token; MFA preservado; a valida conclui com o mesmo link. */
    @Test
    void recuperacaoRecusaForaDaRegraSemConsumirOLink() throws Exception {
        ComFator f = comFator();
        String mfaAntes = value("SELECT string_agg(factor_id::text, ',') FROM identity_mfa_factors WHERE identity_id = '" + f.identity() + "' AND disabled_at IS NULL");
        BrowserClient p = client();
        p.forwardedIp = "198.51.100." + (1 + (int) (Math.random() * 250));
        assertThat(p.post("/api/v1/auth/password-reset/request", "{\"email\":" + q(f.email()) + "}").status()).isEqualTo(202);
        List<SentMessage> m = mails(f.email()).stream().filter(x -> x.kind() == MailSender.Kind.PASSWORD_RECOVERY).toList();
        String token = m.get(m.size() - 1).token();
        String antes = credencial(f.identity());
        for (String[] c : FORA_DA_REGRA) {
            BrowserClient.Response r = client().post("/api/v1/auth/password-reset/confirm", "{\"token\":" + q(token) + ",\"newPassword\":" + q(c[1]) + "}");
            assertThat(r.status()).as("recuperacao: " + c[0]).isEqualTo(400);
        }
        assertThat(credencial(f.identity())).isEqualTo(antes);
        assertThat(client().post("/api/v1/auth/password-reset/confirm", "{\"token\":" + q(token) + ",\"newPassword\":" + q(VALIDA) + "}").status())
                .as("o mesmo link conclui").isEqualTo(204);
        BrowserClient n = client();
        assertThat(login(n, f.email(), VALIDA).field("status")).as("MFA exigido").isEqualTo("mfa_required");
        assertThat(value("SELECT string_agg(factor_id::text, ',') FROM identity_mfa_factors WHERE identity_id = '" + f.identity() + "' AND disabled_at IS NULL"))
                .as("fator preservado").isEqualTo(mfaAntes);
    }
}
