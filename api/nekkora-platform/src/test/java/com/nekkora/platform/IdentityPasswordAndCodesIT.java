package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.auth.crypto.RecoveryCodes;
import com.nekkora.platform.mail.MailSender;

/**
 * Troca de senha e regeneracao independente dos codigos de recuperacao da identidade (missao PLATFORM-API-IDENTIDADE-002,
 * PR 3): {@code POST /api/v1/auth/password} (017, identity_password_change) e {@code POST /api/v1/auth/mfa/recovery-codes}
 * (019, identity_recovery_codes_regenerate). So sessao comum com step-up recente. Cada recusa confere a resposta E o
 * estado persistido (superusuario, sem RLS).
 */
class IdentityPasswordAndCodesIT extends IdentityTestBase {

    private static final String PASSWORD = "/api/v1/auth/password";
    private static final String CODES = "/api/v1/auth/mfa/recovery-codes";
    private static final String NOVA = "senha-nova-da-identidade-2";

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.entrada-geral.enabled", () -> "true");
    }

    @AfterEach
    void mailBack() {
        testMail.fail = false;
    }

    // ------------------------------------------------------------------ troca de senha

    /**
     * Troca completa: hash novo, evento 12, demais sessoes revogadas e desafios pendentes invalidados, a sessao da requisicao
     * continua, aviso ao titular. Depois: senha antiga recusada; a nova abre o desafio de MFA (fator preservado).
     */
    @Test
    void trocaDeSenhaComStepUpRevogaAsDemaisEPreservaOMfa() throws Exception {
        ComFator f = comFator();
        BrowserClient outra = segundaSessao(f);
        BrowserClient pendente = client();
        assertThat(login(pendente, f.email(), SENHA).status()).isEqualTo(200);
        String hashAntes = hash(f.identity());
        stepUp(f);

        BrowserClient.Response r = troca(f.browser(), SENHA, NOVA);
        assertThat(r.status()).as(r.body()).isEqualTo(204);
        assertThat(hash(f.identity())).isNotEqualTo(hashAntes);
        assertThat(eventos(f.identity(), 12)).isEqualTo(1);
        assertThat(outra.get("/api/v1/me/organizations").status()).as("outra sessao revogada").isEqualTo(401);
        assertThat(f.browser().get("/api/v1/me/organizations").status()).as("a da requisicao continua").isEqualTo(200);
        assertThat(count("SELECT count(*) FROM identity_auth_challenges WHERE identity_id = '" + f.identity() + "' AND consumed_at IS NULL AND invalidated_at IS NULL"))
                .as("desafio pendente invalidado").isZero();
        long sessoes = count("SELECT count(*) FROM identity_sessions WHERE identity_id = '" + f.identity() + "'");
        // desafio invalidado: o CSRF dele nao resolve mais (403 generico) e nenhuma sessao nasce
        assertThat(pendente.post("/api/v1/auth/mfa/verify", "{\"code\":\"" + code(f.identity()) + "\"}").status()).as("desafio anterior a troca").isEqualTo(403);
        assertThat(count("SELECT count(*) FROM identity_sessions WHERE identity_id = '" + f.identity() + "'")).isEqualTo(sessoes);
        assertThat(mails(f.email()).stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_PASSWORD_CHANGED).count()).isEqualTo(1);
        assertThat(mails(f.email()).stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_PASSWORD_CHANGED).allMatch(m -> m.token() == null && m.org() == null))
                .isTrue();

        assertThat(login(client(), f.email(), SENHA).status()).as("senha antiga").isEqualTo(401);
        BrowserClient nova = client();
        assertThat(login(nova, f.email(), NOVA).field("status")).as("MFA preservado").isEqualTo("mfa_required");
        assertThat(verifyTotp(nova, f.identity()).status()).isEqualTo(200);
    }

    @Test
    void semStepUpSenhaAtualErradaOuSenhaNovaForaDaPoliticaNaoTrocam() throws Exception {
        ComFator f = comFator();
        Estado antes = estado(f.identity());
        assertThat(troca(f.browser(), SENHA, NOVA).status()).as("sem step-up").isEqualTo(403);
        stepUp(f);
        assertThat(troca(f.browser(), "senha-errada-qualquer-9", NOVA).status()).as("senha atual errada").isEqualTo(403);
        assertThat(troca(f.browser(), SENHA, "curta").status()).as("politica").isEqualTo(400);
        assertThat(f.browser().post(PASSWORD, "{\"newPassword\":\"" + NOVA + "\"}").status()).as("sem senha atual").isEqualTo(400);
        assertThat(f.browser().post(PASSWORD, null).status()).isEqualTo(400);
        assertThat(estado(f.identity())).isEqualTo(antes);
        assertThat(troca(f.browser(), SENHA, NOVA).status()).as("controle").isEqualTo(204);
    }

    /** Sessao restrita: troca de senha e regeneracao recusadas (403), sem efeito. */
    @Test
    void sessaoRestritaNaoTrocaSenhaNemRegeneraCodigos() throws Exception {
        ComFator f = comFator();
        BrowserClient b = restrita(f);
        Estado antes = estado(f.identity());
        assertThat(troca(b, SENHA, NOVA).status()).isEqualTo(403);
        assertThat(b.post(CODES, null).status()).isEqualTo(403);
        assertThat(estado(f.identity())).isEqualTo(antes);
    }

    /**
     * Senha trocada por outro caminho enquanto a troca espera o lock da identidade: a funcao so troca se o hash vigente for
     * o que o Argon2 conferiu - 403 sem efeito (nenhum evento 12, nenhuma revogacao).
     */
    @Test
    void senhaAlteradaDuranteAEsperaNaoESobrescrita() throws Exception {
        ComFator f = comFator();
        BrowserClient outra = segundaSessao(f);
        stepUp(f);
        String outroHash = HASHER.generate("outra-senha-qualquer-77".toCharArray());
        BrowserClient.Response r = comIdentidadeTravada(f.identity(), () -> troca(f.browser(), SENHA, NOVA),
                "UPDATE identity_credentials SET password_hash = '" + outroHash + "' WHERE identity_id = '" + f.identity() + "'");
        assertThat(r.status()).isEqualTo(403);
        assertThat(hash(f.identity())).as("nao sobrescrita").isEqualTo(outroHash);
        assertThat(eventos(f.identity(), 12)).isZero();
        assertThat(outra.get("/api/v1/me/organizations").status()).as("nenhuma revogacao").isEqualTo(200);
    }

    /** Step-up que vence durante a espera: a funcao reconfere a prova depois dos locks - 403 sem efeito. */
    @Test
    void stepUpVencidoDuranteAEsperaRecusaAsDuasOperacoes() throws Exception {
        ComFator f = comFator();
        String s = sessao(f.browser());
        String vence = "UPDATE identity_sessions SET mfa_step_up_at = mfa_step_up_at - interval '6 minutes' WHERE identity_session_id = '" + s + "'";
        stepUp(f);
        Estado antes = estado(f.identity());
        assertThat(comIdentidadeTravada(f.identity(), () -> troca(f.browser(), SENHA, NOVA), vence).status()).isEqualTo(403);
        assertThat(estado(f.identity())).isEqualTo(antes);
        stepUp(f);
        Estado antes2 = estado(f.identity());
        assertThat(comIdentidadeTravada(f.identity(), () -> f.browser().post(CODES, null), vence).status()).isEqualTo(403);
        assertThat(estado(f.identity())).isEqualTo(antes2);
    }

    // ------------------------------------------------------------------ regeneracao dos codigos

    /**
     * Regeneracao: conjunto novo devolvido uma vez, so ele vivo (para o fator vigente), o anterior invalidado (inclusive na
     * entrada), evento 15 e aviso. Um codigo antigo nao entra; um novo entra (sessao restrita, como todo codigo).
     */
    @Test
    void regeneracaoSubstituiOConjuntoInteiro() throws Exception {
        ComFator f = comFator();
        stepUp(f);
        BrowserClient.Response r = f.browser().post(CODES, null);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        List<String> novos = r.listField("recoveryCodes");
        assertThat(novos).hasSizeBetween(8, 16).doesNotHaveDuplicates().doesNotContainAnyElementsOf(f.codes());
        assertThat(vivos(f.identity())).as("so o conjunto novo vivo, no fator vigente").isEqualTo(hashes(novos));
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes WHERE identity_id = '" + f.identity()
                + "' AND invalidated_at IS NOT NULL")).isEqualTo(f.codes().size());
        assertThat(count("SELECT count(*) FROM identity_mfa_recovery_codes c JOIN identity_mfa_factors x ON x.factor_id = c.factor_id WHERE c.identity_id = '"
                + f.identity() + "' AND c.used_at IS NULL AND c.invalidated_at IS NULL AND (x.confirmed_at IS NULL OR x.disabled_at IS NOT NULL)")).isZero();
        assertThat(eventos(f.identity(), 15)).isEqualTo(1);
        assertThat(mails(f.email()).stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_RECOVERY_CODES_REGENERATED).count()).isEqualTo(1);

        BrowserClient antigo = client();
        login(antigo, f.email(), SENHA);
        assertThat(antigo.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}").status()).as("codigo antigo").isEqualTo(401);
        BrowserClient novo = client();
        login(novo, f.email(), SENHA);
        BrowserClient.Response v = novo.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + novos.get(0) + "\"}");
        assertThat(v.status()).as("codigo novo").isEqualTo(200);
        assertThat(v.body()).contains("\"restricted\":true");
    }

    @Test
    void regeneracaoSemStepUpOuSemSessaoNaoMudaNada() throws Exception {
        ComFator f = comFator();
        Estado antes = estado(f.identity());
        assertThat(f.browser().post(CODES, null).status()).as("sem step-up").isEqualTo(403);
        String csrf = f.browser().csrf;
        f.browser().csrf = Tokens.newToken();
        assertThat(f.browser().post(CODES, null).status()).as("CSRF de outro contexto").isEqualTo(403);
        f.browser().csrf = csrf;
        assertThat(client().post(CODES, null).status()).as("sem sessao").isEqualTo(401);
        assertThat(estado(f.identity())).isEqualTo(antes);
    }

    /** Duas regeneracoes concorrentes da mesma sessao serializam na identidade: no fim, exatamente um conjunto vivo, e e um dos devolvidos. */
    @Test
    void regeneracoesConcorrentesDeixamUmSoConjuntoVivo() throws Exception {
        ComFator f = comFator();
        stepUp(f);
        BrowserClient copia = client();
        copia.cookies().put("__Host-nekkora_id", f.browser().cookies().get("__Host-nekkora_id"));
        copia.csrf = f.browser().csrf;
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<BrowserClient.Response> a = ex.submit(() -> f.browser().post(CODES, null));
            Future<BrowserClient.Response> b = ex.submit(() -> copia.post(CODES, null));
            BrowserClient.Response ra = a.get(30, TimeUnit.SECONDS);
            BrowserClient.Response rb = b.get(30, TimeUnit.SECONDS);
            assertThat(List.of(ra.status(), rb.status())).containsOnly(200);
            Set<String> vivos = vivos(f.identity());
            assertThat(vivos).isIn(hashes(ra.listField("recoveryCodes")), hashes(rb.listField("recoveryCodes")));
            assertThat(eventos(f.identity(), 15)).isEqualTo(2);
        } finally {
            ex.shutdownNow();
        }
    }

    /** Falha do aviso depois do commit nao desfaz a troca de senha nem a regeneracao. */
    @Test
    void falhaDoAvisoNaoDesfazAsAlteracoes() throws Exception {
        ComFator f = comFator();
        stepUp(f);
        testMail.fail = true;
        assertThat(f.browser().post(CODES, null).status()).isEqualTo(200);
        assertThat(troca(f.browser(), SENHA, NOVA).status()).isEqualTo(204);
        assertThat(eventos(f.identity(), 15)).isEqualTo(1);
        assertThat(eventos(f.identity(), 12)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ apoio

    private record Estado(String hash, String codigos, String sessoes, long eventos) {
    }

    private static Estado estado(UUID identity) throws Exception {
        String w = " WHERE identity_id = '" + identity + "'";
        return new Estado(hash(identity),
                value("SELECT string_agg(code_id || ':' || (used_at IS NOT NULL) || (invalidated_at IS NOT NULL), ',' ORDER BY code_id) FROM identity_mfa_recovery_codes" + w),
                value("SELECT string_agg(identity_session_id || ':' || (revoked_at IS NOT NULL), ',' ORDER BY identity_session_id) FROM identity_sessions" + w),
                count("SELECT count(*) FROM identity_events" + w + " AND event_type IN (12, 15)"));
    }

    private static String hash(UUID identity) throws Exception {
        return value("SELECT password_hash FROM identity_credentials WHERE identity_id = '" + identity + "'");
    }

    private static Set<String> vivos(UUID identity) throws Exception {
        String v = value("SELECT string_agg(code_hash, ',') FROM identity_mfa_recovery_codes WHERE identity_id = '" + identity
                + "' AND used_at IS NULL AND invalidated_at IS NULL");
        return v == null ? Set.of() : new HashSet<>(List.of(v.split(",")));
    }

    private static Set<String> hashes(List<String> codes) {
        Set<String> s = new HashSet<>();
        for (String c : codes) {
            s.add(RecoveryCodes.hash(RecoveryCodes.normalize(c)));
        }
        return s;
    }

    private static BrowserClient.Response troca(BrowserClient b, String atual, String nova) {
        return b.post(PASSWORD, "{\"currentPassword\":" + q(atual) + ",\"newPassword\":" + q(nova) + "}");
    }

    private static void stepUp(ComFator f) {
        assertThat(f.browser().post("/api/v1/auth/step-up", "{\"code\":\"" + code(f.identity()) + "\"}").status()).isEqualTo(204);
    }

    private BrowserClient segundaSessao(ComFator f) {
        BrowserClient outra = client();
        assertThat(login(outra, f.email(), SENHA).field("status")).isEqualTo("mfa_required");
        BrowserClient.Response v = verifyTotp(outra, f.identity());
        assertThat(v.status()).isEqualTo(200);
        outra.csrf = v.field("csrfToken");
        return outra;
    }

    private BrowserClient restrita(ComFator f) {
        BrowserClient b = client();
        login(b, f.email(), SENHA);
        BrowserClient.Response v = b.post("/api/v1/auth/mfa/verify", "{\"recoveryCode\":\"" + f.codes().get(0) + "\"}");
        assertThat(v.status()).isEqualTo(200);
        b.csrf = v.field("csrfToken");
        return b;
    }

    private static String sessao(BrowserClient b) throws Exception {
        return value("SELECT identity_session_id FROM identity_sessions WHERE session_token_hash = '" + Tokens.sha256Hex(b.cookies().get("__Host-nekkora_id")) + "'");
    }

    private static long eventos(UUID identity, int tipo) throws Exception {
        return count("SELECT count(*) FROM identity_events WHERE identity_id = '" + identity + "' AND event_type = " + tipo);
    }

    /**
     * Segura o lock da identidade numa transacao do superusuario, dispara a requisicao (que espera por ele), aplica
     * {@code durante} (sem triggers, CHECKs valendo) e solta o lock; devolve a resposta.
     */
    private static BrowserClient.Response comIdentidadeTravada(UUID identity, java.util.concurrent.Callable<BrowserClient.Response> req, String durante)
            throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM identities WHERE identity_id = '" + identity + "' FOR UPDATE")) {
                ps.execute();
            }
            Future<BrowserClient.Response> f = ex.submit(req);
            assertThat(platformWaitingOnLock()).as("requisicao esperando a identidade").isTrue();
            try (Connection d = DB.connectAsSuperuser()) {
                d.setAutoCommit(false);
                try (PreparedStatement ps = d.prepareStatement("SET LOCAL session_replication_role = replica")) {
                    ps.execute();
                }
                try (PreparedStatement ps = d.prepareStatement(durante)) {
                    ps.execute();
                }
                d.commit();
            }
            c.commit();
            return f.get(30, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
    }
}
