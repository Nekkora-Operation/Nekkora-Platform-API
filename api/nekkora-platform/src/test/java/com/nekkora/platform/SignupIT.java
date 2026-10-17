package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;
import com.nekkora.platform.identity.SignupMailer;
import com.nekkora.platform.mail.MailSender;

/**
 * Cadastro web da identidade (Missao 1, desenho §6.1 e §6.2) com a chave LIGADA, contexto proprio. Papel real
 * nekkora_platform, 017 R4b, Redis real.
 *
 * <p><b>Transporte de e-mail SIMULADO:</b> {@link PlatformTestBase.TestMailSender}, em memoria e so de teste (com atraso e
 * falha deliberados quando o teste os liga). Nenhuma mensagem sai da JVM; o token e lido dele como a pessoa o leria do
 * e-mail. O envio e assincrono ({@link SignupMailer}): toda leitura das mensagens espera a fila esvaziar.
 *
 * <p>Cada recusa confere a resposta HTTP E a ausencia de efeito persistido (lido como superusuario, sem RLS).
 */
class SignupIT extends PlatformTestBase {

    @DynamicPropertySource
    static void ownContext(DynamicPropertyRegistry r) {
        r.add("nekkora.cadastro.enabled", () -> "true");
    }

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    SignupMailer mailer;

    private static final String SENHA_TITULAR = "senha-da-titular-longa-1";
    private static final String SENHA_OUTRA = "senha-de-outra-pessoa-2";

    private static final String INVALID = "{\"error\":\"invalid_request\"}";
    private static final long FLOOR_MS = 300;

    private static String email() {
        return "cad-" + UUID.randomUUID().toString().substring(0, 12) + "@exemplo.test";
    }

    private BrowserClient client() {
        BrowserClient b = browser();
        fetchPreAuthCsrf(b);
        return b;
    }

    private static String jsonConfirm(String token, String password, String name) {
        return "{\"token\":" + q(token) + ",\"password\":" + q(password) + ",\"displayName\":" + q(name) + "}";
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
    }

    private BrowserClient.Response signup(BrowserClient b, String email) {
        return b.post("/api/v1/signup", "{\"email\":" + q(email) + "}");
    }

    private BrowserClient.Response resend(BrowserClient b, String email) {
        return b.post("/api/v1/signup/confirmation/resend", "{\"email\":" + q(email) + "}");
    }

    private BrowserClient.Response confirm(BrowserClient b, String token) {
        return confirm(b, token, PASSWORD, "Pessoa Teste");
    }

    private BrowserClient.Response confirm(BrowserClient b, String token, String password, String name) {
        return b.post("/api/v1/signup/confirm", jsonConfirm(token, password, name));
    }

    /** O envio e assincrono: espera a fila do cadastro esvaziar (ate 15 s). */
    private void idle() {
        for (int i = 0; i < 300 && mailer.pending() > 0; i++) {
            pause(50);
        }
        assertThat(mailer.pending()).as("fila de e-mail do cadastro vazia").isZero();
    }

    private static String value(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        }
    }

    private static String identity(String email) throws Exception {
        return value("SELECT identity_id FROM identities WHERE email_normalized = lower('" + email + "')");
    }

    private static long count(String sql) throws Exception {
        return Long.parseLong(value(sql));
    }

    private List<PlatformTestBase.SentMessage> mails(String email) {
        idle();
        return testMail.sent.stream().filter(m -> email.equalsIgnoreCase(m.recipient())).toList();
    }

    private String lastToken(String email) {
        List<PlatformTestBase.SentMessage> m = mails(email);
        return m.get(m.size() - 1).token();
    }

    private String hashDa(String id) throws Exception {
        return value("SELECT password_hash FROM identity_credentials WHERE identity_id = '" + id + "'");
    }

    /** Espera, em pg_stat_activity, uma sessao de nekkora_platform esperando lock com a consulta dada ($1... do JDBC). */
    private boolean esperandoLock(String trechoDaConsulta) throws Exception {
        for (int i = 0; i < 100; i++) {
            if ("t".equals(value("SELECT count(*) > 0 FROM pg_stat_activity WHERE usename = 'nekkora_platform' AND wait_event_type = 'Lock'"
                    + " AND query LIKE '%" + trechoDaConsulta + "%'"))) {
                return true;
            }
            pause(50);
        }
        return false;
    }

    // ------------------------------------------------------------------ pedido

    @Test
    void emailLivreCriaIdentidadePendenteSemCredencialEEnviaAConfirmacao() throws Exception {
        String e = email();
        long t0 = System.nanoTime();
        BrowserClient.Response r = signup(client(), e);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body()).as("202 sem corpo").isEmpty();
        assertThat(r.setCookies()).as("nenhum cookie de sessao").noneMatch(c -> c.startsWith("__Host-nekkora_session=") || c.startsWith("__Host-nekkora_id"));
        assertThat(ms).as("piso de tempo").isGreaterThanOrEqualTo(FLOOR_MS);
        String id = identity(e);
        assertThat(id).as("identidade criada").isNotNull();
        assertThat(value("SELECT status || '|' || (email_verified_at IS NULL) || '|' || (display_name = email) FROM identities WHERE identity_id = '" + id + "'"))
                .as("PENDING, nao verificada, nome provisorio = e-mail").isEqualTo("1|true|true");
        assertThat(hashDa(id)).as("o pedido NAO grava credencial").isNull();
        assertThat(count("SELECT count(*) FROM identity_email_tokens WHERE identity_id = '" + id + "' AND consumed_at IS NULL AND revoked_at IS NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + id + "' AND event_type = 1")).as("SIGNUP_REQUESTED").isEqualTo(1);
        List<PlatformTestBase.SentMessage> m = mails(e);
        assertThat(m).hasSize(1);
        assertThat(m.get(0).kind()).isEqualTo(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION);
        assertThat(m.get(0).org()).as("sem organizacao").isNull();
        assertThat(Tokens.isValidFormat(m.get(0).token())).isTrue();
        assertThat(value("SELECT count(*) FROM identity_email_tokens WHERE token_hash = '" + Tokens.sha256Hex(m.get(0).token()) + "'"))
                .as("so o hash do token no banco").isEqualTo("1");
        assertThat(count("SELECT count(*) FROM users u WHERE lower(u.email) = lower('" + e + "')")).as("nenhuma conta de organizacao").isZero();
    }

    @Test
    void emailComIdentidadeAtivaRespondeIgualENaoCriaNada() throws Exception {
        String e = email();
        signup(client(), e);
        assertThat(confirm(client(), lastToken(e)).status()).isEqualTo(204);
        String id = identity(e);
        String hash = hashDa(id);
        long tokens = count("SELECT count(*) FROM identity_email_tokens WHERE identity_id = '" + id + "'");
        BrowserClient.Response nova = signup(client(), email());
        long t0 = System.nanoTime();
        BrowserClient.Response existente = signup(client(), e.toUpperCase());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(existente.status()).isEqualTo(nova.status()).isEqualTo(202);
        assertThat(existente.body()).isEqualTo(nova.body()).isEmpty();
        assertThat(existente.setCookies()).isEqualTo(nova.setCookies());
        assertThat(ms).as("piso de tempo tambem no caminho existente").isGreaterThanOrEqualTo(FLOOR_MS);
        assertThat(count("SELECT count(*) FROM identities WHERE email_normalized = lower('" + e + "')")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_email_tokens WHERE identity_id = '" + id + "'")).as("nenhum token novo").isEqualTo(tokens);
        assertThat(hashDa(id)).as("credencial intocada").isEqualTo(hash);
        PlatformTestBase.SentMessage aviso = mails(e).get(mails(e).size() - 1);
        assertThat(aviso.kind()).isEqualTo(MailSender.Kind.IDENTITY_SIGNUP_ALREADY_REGISTERED);
        assertThat(aviso.token()).as("aviso sem token").isNull();
    }

    @Test
    void emailPendenteReemiteORevogaOAnterior() throws Exception {
        String e = email();
        signup(client(), e);
        String primeiro = lastToken(e);
        assertThat(signup(client(), e).status()).isEqualTo(202);
        String segundo = lastToken(e);
        assertThat(segundo).isNotEqualTo(primeiro);
        String id = identity(e);
        assertThat(count("SELECT count(*) FROM identities WHERE email_normalized = lower('" + e + "')")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_email_tokens WHERE identity_id = '" + id + "' AND consumed_at IS NULL AND revoked_at IS NULL"))
                .as("no maximo um vivo").isEqualTo(1);
        assertThat(confirm(client(), primeiro).body()).as("o anterior foi revogado").isEqualTo(INVALID);
        assertThat(confirm(client(), segundo).status()).isEqualTo(204);
    }

    @Test
    void cadastroConcorrenteDoMesmoEmailCriaUmaSoIdentidade() throws Exception {
        String e = email();
        ExecutorService ex = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                BrowserClient b = client();
                fs.add(ex.submit((Callable<Integer>) () -> signup(b, e).status()));
            }
            for (Future<Integer> f : fs) {
                assertThat(f.get()).isEqualTo(202);
            }
        } finally {
            ex.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM identities WHERE email_normalized = lower('" + e + "')")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_credentials c JOIN identities i USING (identity_id) WHERE i.email_normalized = lower('" + e + "')"))
                .as("nenhuma credencial antes da confirmacao").isZero();
    }

    @Test
    void pedidoRecusaSenhaENomeEFormatoInvalido() throws Exception {
        String e = email();
        assertThat(client().post("/api/v1/signup", "{\"email\":" + q(e) + ",\"password\":" + q(SENHA_OUTRA) + "}").body())
                .as("senha no pedido: pertence a confirmacao").isEqualTo(INVALID);
        assertThat(client().post("/api/v1/signup", "{\"email\":" + q(e) + ",\"displayName\":\"Nome\"}").body())
                .as("nome no pedido: pertence a confirmacao").isEqualTo(INVALID);
        assertThat(signup(client(), " " + e).body()).as("e-mail com espaco").isEqualTo(INVALID);
        assertThat(signup(client(), "sem-arroba.test").body()).as("e-mail sem @").isEqualTo(INVALID);
        assertThat(client().post("/api/v1/signup", "{}").body()).as("corpo vazio").isEqualTo(INVALID);
        assertThat(identity(e)).as("nada criado").isNull();
        assertThat(mails(e)).isEmpty();
    }

    @Test
    void csrfEOrigemSaoExigidos() throws Exception {
        String e = email();
        BrowserClient semToken = browser();
        assertThat(semToken.post("/api/v1/signup", "{\"email\":" + q(e) + "}").status()).as("sem CSRF").isEqualTo(403);
        BrowserClient origem = client();
        origem.origin = "https://atacante.example";
        assertThat(signup(origem, e).status()).as("Origin estranho").isEqualTo(403);
        assertThat(resend(origem, e).status()).isEqualTo(403);
        assertThat(confirm(origem, Tokens.newToken()).status()).isEqualTo(403);
        assertThat(identity(e)).isNull();
    }

    // ------------------------------------------------------------------ confirmacao

    @Test
    void confirmacaoDefineSenhaENomeAtivaSemSessaoEUmaSoVez() throws Exception {
        String e = email();
        signup(client(), e);
        String token = lastToken(e);
        BrowserClient.Response ok = confirm(client(), token, SENHA_TITULAR, "Titular");
        assertThat(ok.status()).isEqualTo(204);
        assertThat(ok.setCookies()).as("nao cria sessao").noneMatch(c -> c.startsWith("__Host-nekkora_session=") || c.startsWith("__Host-nekkora_id"));
        String id = identity(e);
        assertThat(value("SELECT status || '|' || (email_verified_at IS NOT NULL) || '|' || display_name FROM identities WHERE identity_id = '" + id + "'"))
                .isEqualTo("2|true|Titular");
        assertThat(hashDa(id)).startsWith("$argon2id$");
        assertThat(HASHER.verify(SENHA_TITULAR.toCharArray(), hashDa(id))).as("credencial = senha da confirmacao").isTrue();
        assertThat(value("SELECT consumed_at IS NOT NULL FROM identity_email_tokens WHERE token_hash = '" + Tokens.sha256Hex(token) + "'")).isEqualTo("t");
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + id + "' AND event_type = 2")).as("SIGNUP_CONFIRMED").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM identity_sessions WHERE identity_id = '" + id + "'")).as("nenhuma sessao de identidade").isZero();
        assertThat(count("SELECT count(*) FROM identity_users WHERE identity_id = '" + id + "'")).as("nenhuma organizacao").isZero();
        BrowserClient.Response segunda = confirm(client(), token, SENHA_OUTRA, "Outra");
        assertThat(segunda.status()).isEqualTo(400);
        assertThat(segunda.body()).isEqualTo(INVALID);
        assertThat(HASHER.verify(SENHA_TITULAR.toCharArray(), hashDa(id))).as("segunda confirmacao nao troca a senha").isTrue();
    }

    /**
     * O cenario da revisao: outra pessoa pede o cadastro do e-mail da titular (e tenta escolher a senha); a titular pede
     * de novo e confirma com a SUA senha. A senha do pedido anterior nao autoriza a identidade concluida.
     */
    @Test
    void senhaDeOutraPessoaNoPedidoAnteriorNaoAutorizaAIdentidadeConcluida() throws Exception {
        String e = email();
        BrowserClient outra = client();
        assertThat(outra.post("/api/v1/signup", "{\"email\":" + q(e) + ",\"password\":" + q(SENHA_OUTRA) + "}").body())
                .as("o pedido nao aceita senha").isEqualTo(INVALID);
        assertThat(signup(outra, e).status()).as("o pedido sem senha e aceito").isEqualTo(202);
        String id = identity(e);
        String tokenDoPrimeiroPedido = lastToken(e);
        assertThat(hashDa(id)).as("nenhuma credencial escolhida por quem pediu").isNull();
        assertThat(confirm(outra, Tokens.newToken(), SENHA_OUTRA, "Outra").body()).as("sem o token, nao confirma").isEqualTo(INVALID);

        assertThat(signup(client(), e).status()).as("a titular pede o cadastro").isEqualTo(202);
        String tokenDaTitular = lastToken(e);
        assertThat(confirm(client(), tokenDaTitular, SENHA_TITULAR, "Titular").status()).isEqualTo(204);

        String hash = hashDa(id);
        assertThat(HASHER.verify(SENHA_TITULAR.toCharArray(), hash)).as("vale a senha da titular").isTrue();
        assertThat(HASHER.verify(SENHA_OUTRA.toCharArray(), hash)).as("a senha da outra pessoa NAO autoriza").isFalse();
        assertThat(value("SELECT display_name FROM identities WHERE identity_id = '" + id + "'")).isEqualTo("Titular");
        assertThat(confirm(client(), tokenDoPrimeiroPedido, SENHA_OUTRA, "Outra").body()).as("o token do primeiro pedido foi revogado").isEqualTo(INVALID);
        assertThat(count("SELECT count(*) FROM identity_credentials WHERE identity_id = '" + id + "'")).isEqualTo(1);
    }

    @Test
    void confirmacaoComSenhaOuNomeInvalidoNaoConsomeOToken() throws Exception {
        String e = email();
        signup(client(), e);
        String token = lastToken(e);
        assertThat(confirm(client(), token, "curta", "Nome").body()).as("senha fora da politica").isEqualTo(INVALID);
        assertThat(confirm(client(), token, null, "Nome").body()).as("sem senha").isEqualTo(INVALID);
        assertThat(confirm(client(), token, PASSWORD, "  ").body()).as("nome em branco").isEqualTo(INVALID);
        assertThat(confirm(client(), token, PASSWORD, "x".repeat(121)).body()).as("nome longo").isEqualTo(INVALID);
        assertThat(confirm(client(), token, PASSWORD, null).body()).as("sem nome").isEqualTo(INVALID);
        String id = identity(e);
        assertThat(value("SELECT consumed_at IS NULL FROM identity_email_tokens WHERE token_hash = '" + Tokens.sha256Hex(token) + "'")).isEqualTo("t");
        assertThat(value("SELECT status FROM identities WHERE identity_id = '" + id + "'")).isEqualTo("1");
        assertThat(hashDa(id)).isNull();
        assertThat(confirm(client(), token).status()).as("o token continua valido").isEqualTo(204);
    }

    @Test
    void tokenInvalidoVencidoOuRevogadoRecusaSemEfeito() throws Exception {
        assertThat(confirm(client(), "curto").body()).as("formato").isEqualTo(INVALID);
        assertThat(confirm(client(), null).body()).as("ausente").isEqualTo(INVALID);
        assertThat(confirm(client(), Tokens.newToken()).body()).as("desconhecido").isEqualTo(INVALID);

        String e = email();
        signup(client(), e);
        String id = identity(e);
        String vencido = Tokens.newToken();
        exec("INSERT INTO identity_email_tokens (purpose, identity_id, target_email, token_hash, created_at, expires_at) VALUES (1, '" + id + "', '" + e
                + "', '" + Tokens.sha256Hex(vencido) + "', now() - interval '25 hours', now() - interval '1 hour')");
        assertThat(confirm(client(), vencido).body()).as("vencido").isEqualTo(INVALID);
        String vivo = lastToken(e);
        exec("UPDATE identity_email_tokens SET revoked_at = now() WHERE token_hash = '" + Tokens.sha256Hex(vivo) + "'");
        assertThat(confirm(client(), vivo).body()).as("revogado").isEqualTo(INVALID);
        assertThat(value("SELECT status FROM identities WHERE identity_id = '" + id + "'")).as("continua PENDING").isEqualTo("1");
        assertThat(count("SELECT count(*) FROM identity_email_tokens WHERE identity_id = '" + id + "' AND consumed_at IS NOT NULL")).isZero();
        assertThat(hashDa(id)).isNull();
    }

    @Test
    void tokenVivoDeIdentidadeForaDePendenteNaoEConsumido() throws Exception {
        String e = email();
        signup(client(), e);
        String id = identity(e);
        String token = lastToken(e);
        exec("UPDATE identities SET status = 3 WHERE identity_id = '" + id + "'");
        assertThat(confirm(client(), token).body()).isEqualTo(INVALID);
        assertThat(value("SELECT consumed_at IS NULL FROM identity_email_tokens WHERE token_hash = '" + Tokens.sha256Hex(token) + "'"))
                .as("consumo desfeito com a transacao").isEqualTo("t");
        assertThat(hashDa(id)).as("credencial desfeita com a transacao").isNull();
        assertThat(value("SELECT status || '|' || (email_verified_at IS NULL) FROM identities WHERE identity_id = '" + id + "'")).isEqualTo("3|true");
    }

    /**
     * Revogacao DEPOIS da resolucao do token e ANTES do consumo: outra transacao trava a identidade e revoga o token; a
     * confirmacao espera o lock da identidade (observada em pg_stat_activity). Depois do commit, recusa.
     */
    @Test
    void revogacaoDuranteAEsperaDoLockDaIdentidadeRecusaAConfirmacao() throws Exception {
        String e = email();
        signup(client(), e);
        String id = identity(e);
        String token = lastToken(e);
        BrowserClient b = client();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM identities WHERE identity_id = '" + id + "' FOR UPDATE")) {
                ps.execute();
            }
            Future<BrowserClient.Response> f = ex.submit(() -> confirm(b, token));
            assertThat(esperandoLock("FROM public.identities WHERE identity_id = $1 FOR UPDATE")).as("a confirmacao espera o lock da identidade").isTrue();
            try (PreparedStatement ps = c.prepareStatement("UPDATE identity_email_tokens SET revoked_at = now() WHERE token_hash = '"
                    + Tokens.sha256Hex(token) + "'")) {
                ps.execute();
            }
            c.commit();
            BrowserClient.Response r = f.get();
            assertThat(r.status()).as("token revogado durante a espera").isEqualTo(400);
            assertThat(r.body()).isEqualTo(INVALID);
        } finally {
            ex.shutdownNow();
        }
        assertThat(value("SELECT (consumed_at IS NULL) || '|' || (revoked_at IS NOT NULL) FROM identity_email_tokens WHERE token_hash = '"
                + Tokens.sha256Hex(token) + "'")).isEqualTo("true|true");
        assertThat(value("SELECT status FROM identities WHERE identity_id = '" + id + "'")).as("continua PENDING").isEqualTo("1");
        assertThat(hashDa(id)).isNull();
    }

    /**
     * Vencimento DURANTE a espera pelo lock do PROPRIO token: outra transacao so TRAVA a linha do token (sem altera-la) ate
     * ele vencer. A confirmacao trava a identidade, espera o token e so depois confere a validade: nao ha consumo,
     * credencial, ativacao nem evento de confirmacao.
     */
    @Test
    void vencimentoDuranteAEsperaDoLockDoTokenRecusaSemEfeito() throws Exception {
        String e = email();
        signup(client(), e);
        String id = identity(e);
        String curto = Tokens.newToken();
        String hashCurto = Tokens.sha256Hex(curto);
        exec("INSERT INTO identity_email_tokens (purpose, identity_id, target_email, token_hash, expires_at) VALUES (1, '" + id + "', '" + e + "', '"
                + hashCurto + "', clock_timestamp() + interval '3 seconds')");
        BrowserClient b = client();
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection c = DB.connectAsSuperuser()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM identity_email_tokens WHERE token_hash = '" + hashCurto + "' FOR UPDATE")) {
                ps.execute();
            }
            Future<BrowserClient.Response> f = ex.submit(() -> confirm(b, curto));
            assertThat(esperandoLock("public.identity_email_tokens")).as("a confirmacao espera o lock do token").isTrue();
            assertThat(value("SELECT expires_at > clock_timestamp() FROM identity_email_tokens WHERE token_hash = '" + hashCurto + "'"))
                    .as("o token ainda era valido quando a espera comecou").isEqualTo("t");
            while ("t".equals(value("SELECT expires_at > clock_timestamp() FROM identity_email_tokens WHERE token_hash = '" + hashCurto + "'"))) {
                pause(100);
            }
            c.commit();
            BrowserClient.Response r = f.get();
            assertThat(r.status()).as("vencido durante a espera").isEqualTo(400);
            assertThat(r.body()).isEqualTo(INVALID);
        } finally {
            ex.shutdownNow();
        }
        assertThat(value("SELECT consumed_at IS NULL FROM identity_email_tokens WHERE token_hash = '" + hashCurto + "'")).as("sem consumo").isEqualTo("t");
        assertThat(hashDa(id)).as("sem credencial").isNull();
        assertThat(value("SELECT status || '|' || (email_verified_at IS NULL) FROM identities WHERE identity_id = '" + id + "'")).as("sem ativacao")
                .isEqualTo("1|true");
        assertThat(count("SELECT count(*) FROM identity_events WHERE identity_id = '" + id + "' AND event_type = 2")).as("sem evento").isZero();
    }

    // ------------------------------------------------------------------ envio fora do caminho da resposta

    /**
     * Transporte SIMULADO lento (1,5 s por mensagem): os caminhos que enviam (livre, PENDING, ACTIVE) e os que nao enviam
     * (SUSPENDED, desconhecido) respondem no mesmo intervalo, longe da duracao do envio; as mensagens chegam depois.
     */
    @Test
    void transporteLentoNaoDistingueOsCaminhosPeloTempo() throws Exception {
        String ativa = email();
        signup(client(), ativa);
        confirm(client(), lastToken(ativa));
        String pendente = email();
        signup(client(), pendente);
        String suspensa = email();
        signup(client(), suspensa);
        exec("UPDATE identities SET status = 3 WHERE email_normalized = lower('" + suspensa + "')");
        idle();
        int antesAtiva = mails(ativa).size();
        int antesPendente = mails(pendente).size();
        int antesSuspensa = mails(suspensa).size();
        String livre = email();
        testMail.delayMs = 1500;
        try {
            long[] ms = new long[5];
            String[] casos = {livre, pendente, ativa, suspensa, email()};
            for (int i = 0; i < casos.length; i++) {
                long t0 = System.nanoTime();
                assertThat(signup(client(), casos[i]).status()).isEqualTo(202);
                ms[i] = (System.nanoTime() - t0) / 1_000_000;
            }
            for (int i = 0; i < ms.length; i++) {
                assertThat(ms[i]).as("caso " + i + ": piso, sem esperar o envio").isBetween(FLOOR_MS, 1200L);
            }
            assertThat(mailer.pending()).as("envios ainda em andamento depois das respostas").isPositive();
        } finally {
            idle();
            testMail.delayMs = 0;
        }
        assertThat(mails(livre)).as("livre: confirmacao entregue depois").hasSize(1);
        assertThat(mails(pendente)).as("pendente: reemissao entregue depois").hasSize(antesPendente + 1);
        assertThat(mails(ativa)).as("ativa: aviso entregue depois").hasSize(antesAtiva + 1);
        assertThat(mails(suspensa)).as("suspensa: nada").hasSize(antesSuspensa);
    }

    /** Falha do transporte: a resposta nao muda, nada e reenviado sozinho, e o reenvio recupera. */
    @Test
    void falhaDoTransporteNaoMudaARespostaEOReenvioRecupera() throws Exception {
        String e = email();
        testMail.fail = true;
        try {
            assertThat(signup(client(), e).status()).isEqualTo(202);
            idle();
        } finally {
            testMail.fail = false;
        }
        assertThat(identity(e)).as("a identidade foi criada").isNotNull();
        assertThat(mails(e)).as("a mensagem falhou e nao foi repetida").isEmpty();
        assertThat(resend(client(), e).status()).isEqualTo(202);
        assertThat(mails(e)).as("o reenvio entrega").hasSize(1);
        assertThat(confirm(client(), lastToken(e)).status()).isEqualTo(204);
    }

    // ------------------------------------------------------------------ reenvio

    @Test
    void reenvioSoAgeEmIdentidadePendenteERespondeSempreIgual() throws Exception {
        String pendente = email();
        signup(client(), pendente);
        String primeiro = lastToken(pendente);
        BrowserClient.Response r = resend(client(), pendente);
        assertThat(r.status()).isEqualTo(202);
        assertThat(r.body()).isEmpty();
        assertThat(lastToken(pendente)).isNotEqualTo(primeiro);
        assertThat(confirm(client(), primeiro).body()).as("reemissao revoga o anterior").isEqualTo(INVALID);

        String desconhecido = email();
        BrowserClient.Response d = resend(client(), desconhecido);
        assertThat(d.status()).isEqualTo(202);
        assertThat(d.body()).isEqualTo(r.body());
        assertThat(mails(desconhecido)).isEmpty();
        assertThat(identity(desconhecido)).isNull();

        String ativa = email();
        signup(client(), ativa);
        confirm(client(), lastToken(ativa));
        int antes = mails(ativa).size();
        assertThat(resend(client(), ativa).status()).isEqualTo(202);
        assertThat(mails(ativa)).as("ativa: reenvio nao envia").hasSize(antes);

        assertThat(resend(client(), "nao e e-mail").status()).as("malformado: 202 tambem").isEqualTo(202);
        assertThat(client().post("/api/v1/signup/confirmation/resend", "{}").status()).isEqualTo(202);
    }

    // ------------------------------------------------------------------ limites

    @Test
    void r9bLimitaOsEmailsPorIdentidadeSemMudarAResposta() throws Exception {
        String e = email();
        signup(client(), e);
        for (int i = 0; i < 4; i++) {
            assertThat(resend(client(), e).status()).isEqualTo(202);
        }
        assertThat(mails(e)).as("1 do cadastro + 3 reemissoes (r5-per-identity); a 4a nao envia").hasSize(4);
        assertThat(count("SELECT count(*) FROM identity_email_tokens WHERE identity_id = '" + identity(e) + "'")).isEqualTo(4);
    }

    @Test
    void r9LimitaOsCadastrosPorIpSemMudarAResposta() throws Exception {
        BrowserClient b = client();
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            String e = email();
            emails.add(e);
            assertThat(signup(b, e).status()).isEqualTo(202);
        }
        for (int i = 0; i < 10; i++) {
            assertThat(identity(emails.get(i))).as("cadastro " + (i + 1) + " dentro da R9").isNotNull();
        }
        assertThat(identity(emails.get(10))).as("11o cadastro do mesmo IP: nada criado").isNull();
        assertThat(mails(emails.get(10))).isEmpty();
    }

    @Test
    void r12EAplicadaPorIpNasTresRotas() {
        BrowserClient b = client();
        String chave = "nk:rl:r12:" + b.forwardedIp;
        assertThat(redis.hasKey(chave)).isFalse();
        confirm(b, "curto");
        long t1 = carimbo(chave);
        resend(b, email());
        long t2 = carimbo(chave);
        signup(b, "invalido");
        long t3 = carimbo(chave);
        // o script do balde grava o instante (ms do Redis) a CADA consumo: tres rotas, tres carimbos crescentes
        assertThat(t1).as("R12 consumida pela confirmacao").isPositive();
        assertThat(t2).as("R12 consumida pelo reenvio").isGreaterThan(t1);
        assertThat(t3).as("R12 consumida pelo pedido").isGreaterThan(t2);
    }

    private long carimbo(String chave) {
        Object t = redis.opsForHash().get(chave, "t");
        return t == null ? 0 : Long.parseLong(t.toString());
    }
}
