package com.nekkora.platform.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.platform.PlatformApplication;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.identity.SignupMailer;

import tools.jackson.databind.json.JsonMapper;

/**
 * Fila persistente de e-mail com o transporte real (missao 002, PR 5; migration 021), com {@code nekkora.mail.transport=m365}
 * apontado para o Entra e o Graph FALSOS ({@link FakeGraph}), banco e Redis reais, papel real nekkora_platform. O despacho
 * periodico fica desligado: cada teste chama {@link MailDispatcher#dispatchPending()} e confere o banco e o transporte.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = PlatformApplication.class)
@ExtendWith(OutputCaptureExtension.class)
class MailOutboxIT {

    private static final RealDatabase DB = RealDatabase.shared();
    private static final String ORIGIN = "https://portal.nekkora.test";
    private static final String SENHA_CERT = "senha-cert-outbox";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FakeGraph FAKE;
    private static final Path P12;

    static {
        try {
            FAKE = new FakeGraph();
            P12 = FakeGraph.testCertificate(Files.createTempDirectory("nekkora-outbox-"), SENHA_CERT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        SecureRandom rnd = new SecureRandom();
        byte[] aes = new byte[32];
        byte[] hm = new byte[32];
        rnd.nextBytes(aes);
        rnd.nextBytes(hm);
        r.add("nekkora.database.url", DB::url);
        r.add("nekkora.database.user", () -> "nekkora_platform");
        r.add("nekkora.database.password", () -> DB.password("nekkora_platform"));
        r.add("spring.data.redis.host", () -> RealRedis.shared().host());
        r.add("spring.data.redis.port", () -> RealRedis.shared().port());
        r.add("nekkora.public-origin", () -> ORIGIN);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.keys.aes-current", () -> "k1");
        r.add("nekkora.keys.aes-by-id", () -> "k1:" + Base64.getEncoder().encodeToString(aes));
        r.add("nekkora.keys.hmac-id", () -> "h1");
        r.add("nekkora.keys.hmac", () -> Base64.getEncoder().encodeToString(hm));
        r.add("nekkora.cadastro.enabled", () -> "true");
        r.add("nekkora.mail.transport", () -> "m365");
        r.add("nekkora.mail.remetente", () -> "no-reply@exemplo.test");
        r.add("nekkora.mail.m365.tenant-id", () -> "00000000-0000-0000-0000-00000000aaaa");
        r.add("nekkora.mail.m365.client-id", () -> "00000000-0000-0000-0000-00000000bbbb");
        r.add("nekkora.mail.m365.certificado", P12::toString);
        r.add("nekkora.mail.m365.certificado-senha", () -> SENHA_CERT);
        r.add("nekkora.mail.m365.login-url", FAKE::base);
        r.add("nekkora.mail.m365.graph-url", FAKE::base);
        r.add("nekkora.mail.m365.timeout", () -> "1s");
        r.add("nekkora.mail.despacho.enabled", () -> "false");
        // o orcamento por minuto e da instancia: alto aqui para os testes nao dependerem da ordem (o ritmo tem teste proprio)
        r.add("nekkora.mail.despacho.por-minuto", () -> "1000");
    }

    @LocalServerPort
    int port;
    @Autowired
    MailSender mail;
    @Autowired
    MailDispatcher dispatcher;
    @Autowired
    IdentityHmac hmac;
    @Autowired
    SignupMailer signupMailer;

    /** Fila isolada por teste: trabalhos ainda vivos de outro teste sao encerrados (o despacho pega tudo o que esta vencido). */
    @BeforeEach
    void reset() {
        FAKE.script.clear();
        DB.asSuperuser("UPDATE mail_outbox SET state = 4, lease_until = NULL, finished_at = clock_timestamp(), payload_ciphertext = NULL,"
                + " payload_nonce = NULL, key_id = NULL WHERE state IN (1, 2)");
    }

    // ------------------------------------------------------------------ caminho completo

    /**
     * Cadastro real -> trabalho na fila com destinatario e token CIFRADOS (nada em claro no banco nem no log) -> despacho ->
     * 202 do Graph -> ACEITO com o payload apagado. O token do link funciona uma vez (a confirmacao pela API conclui).
     */
    @Test
    void cadastroEnfileiraCifradoEODespachoEntregaEApagaOPayload(CapturedOutput out) throws Exception {
        assertThat(mail).isInstanceOf(OutboxMailSender.class);
        String email = "fila-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.test";
        BrowserClient b = browser();
        assertThat(b.post("/api/v1/signup", "{\"email\":\"" + email + "\"}").status()).isEqualTo(202);
        for (int i = 0; i < 100 && signupMailer.pending() > 0; i++) {
            Thread.sleep(50);
        }
        String hash = hmac.global(email);
        assertThat(sql("SELECT kind || '|' || state || '|' || attempts FROM mail_outbox WHERE recipient_hmac = '" + hash + "'"))
                .isEqualTo("IDENTITY_SIGNUP_CONFIRMATION|1|0");
        byte[] payload = bytes("SELECT payload_ciphertext FROM mail_outbox WHERE recipient_hmac = '" + hash + "'");
        assertThat(contains(payload, email.getBytes(StandardCharsets.UTF_8))).as("destinatario cifrado").isFalse();

        int antes = FAKE.sends();
        assertThat(dispatcher.dispatchPending()).isGreaterThanOrEqualTo(1);
        assertThat(FAKE.sends()).isEqualTo(antes + 1);
        String body = FAKE.sendRequests.get(FAKE.sends() - 1).body();
        assertThat(JSON.readTree(body).path("message").path("toRecipients").get(0).path("emailAddress").path("address").asString()).isEqualTo(email);
        Matcher m = Pattern.compile(Pattern.quote(ORIGIN) + "/cadastro/confirmar#token=([A-Za-z0-9_-]{43})").matcher(body);
        assertThat(m.find()).as("link na origem publica, token no fragmento").isTrue();
        String token = m.group(1);
        assertThat(contains(payload, token.getBytes(StandardCharsets.UTF_8))).as("token cifrado na fila").isFalse();
        assertThat(sql("SELECT state || '|' || attempts || '|' || last_status || '|' || (payload_ciphertext IS NULL) || (finished_at IS NOT NULL)"
                + " FROM mail_outbox WHERE recipient_hmac = '" + hash + "'")).isEqualTo("3|1|202|truetrue");

        BrowserClient c = browser();
        assertThat(c.post("/api/v1/signup/confirm", "{\"token\":\"" + token + "\",\"password\":\"senha-pela-fila-de-email-1\",\"displayName\":\"Pessoa\"}")
                .status()).as("o token do e-mail conclui o cadastro").isEqualTo(204);
        assertThat(out.getAll()).doesNotContain(email).doesNotContain(token).contains("evento=mail.enfileirado").contains("evento=mail.desfecho");
    }

    // ------------------------------------------------------------------ tentativas e desfechos

    @Test
    void retentavelVoltaAFilaEDepoisEAceito() throws Exception {
        UUID job = enqueue();
        FAKE.script.add(new FakeGraph.Reply(503, "0", 0));
        dispatcher.dispatchPending();
        assertThat(state(job)).isEqualTo("1|1|503|false");
        assertThat(sql("SELECT next_attempt_at > clock_timestamp() FROM mail_outbox WHERE job_id = '" + job + "'")).as("espera antes da proxima").isEqualTo("t");
        vencerEspera(job);
        dispatcher.dispatchPending();
        assertThat(state(job)).isEqualTo("3|2|202|true");
    }

    @Test
    void recusaEIncertezaSaoTerminaisENaoSaoRepetidas() throws Exception {
        UUID recusado = enqueue();
        FAKE.script.add(FakeGraph.Reply.of(403));
        dispatcher.dispatchPending();
        assertThat(state(recusado)).isEqualTo("4|1|403|true");

        UUID incerto = enqueue();
        FAKE.script.add(new FakeGraph.Reply(202, null, 2500));
        dispatcher.dispatchPending();
        assertThat(state(incerto)).as("resposta depois do prazo").startsWith("5|1|");
        int depois = FAKE.sends();
        dispatcher.dispatchPending();
        assertThat(FAKE.sends()).as("nenhum terminal e reenviado").isEqualTo(depois);
    }

    @Test
    void tentativasEsgotadasViramIncerto() throws Exception {
        UUID job = enqueue();
        for (int i = 1; i <= MailDispatcher.MAX_ATTEMPTS; i++) {
            FAKE.script.add(FakeGraph.Reply.of(503));
            vencerEspera(job);
            dispatcher.dispatchPending();
        }
        assertThat(state(job)).isEqualTo("5|5|503|true");
    }

    /** Recuperacao apos reinicio: posse vencida de um trabalho ENVIANDO vira INCERTO, sem reenvio. */
    @Test
    void posseVencidaDepoisDeQuedaViraIncertoSemReenvio() throws Exception {
        UUID job = enqueue();
        DB.asSuperuser("UPDATE mail_outbox SET state = 2, attempts = 1, lease_until = clock_timestamp() - interval '1 minute' WHERE job_id = '" + job + "'");
        int antes = FAKE.sends();
        dispatcher.dispatchPending();
        assertThat(FAKE.sends()).isEqualTo(antes);
        assertThat(state(job)).isEqualTo("5|1|null|true");
    }

    /** Prazo da mensagem vencido antes do envio: EXPIRADO, sem envio, payload apagado. */
    @Test
    void prazoVencidoExpiraSemEnviar() throws Exception {
        UUID job = enqueue();
        DB.asSuperuser("SET session_replication_role = replica; UPDATE mail_outbox SET created_at = clock_timestamp() - interval '2 days',"
                + " deadline_at = clock_timestamp() - interval '1 minute' WHERE job_id = '" + job + "'; SET session_replication_role = origin");
        int antes = FAKE.sends();
        dispatcher.dispatchPending();
        assertThat(FAKE.sends()).isEqualTo(antes);
        assertThat(state(job)).isEqualTo("6|0|null|true");
    }

    /** Dois despachos concorrentes (duas instancias): cada trabalho e enviado exatamente uma vez. */
    @Test
    void despachosConcorrentesEnviamCadaTrabalhoUmaVez() throws Exception {
        List<UUID> jobs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            jobs.add(enqueue());
        }
        int antes = FAKE.sends();
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = ex.submit(dispatcher::dispatchPending);
            Future<Integer> b = ex.submit(dispatcher::dispatchPending);
            a.get(60, TimeUnit.SECONDS);
            b.get(60, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
        dispatcher.dispatchPending();
        for (UUID j : jobs) {
            assertThat(state(j)).startsWith("3|1|202");
        }
        List<FakeGraph.Request> novos = FAKE.sendRequests.subList(antes, FAKE.sends());
        assertThat(new HashSet<>(novos.stream().map(FakeGraph.Request::clientRequestId).toList())).as("um envio por trabalho").hasSize(novos.size());
        assertThat(novos.size()).isGreaterThanOrEqualTo(12);
    }

    /** Ritmo: com 3 por minuto, um ciclo envia 3 de 5 e o seguinte, no mesmo minuto, nenhum; os demais seguem PENDENTES. */
    @Test
    void ritmoPorMinutoLimitaOsEnvios(@Autowired com.nekkora.core.db.OrgTransactions tx, @Autowired com.nekkora.platform.auth.crypto.SecretCipher cipher,
                                      @Autowired MailTemplates templates, @Autowired GraphMailTransport transport) throws Exception {
        MailDispatcher lento = new MailDispatcher(tx, cipher, transport, templates, 3);
        List<UUID> jobs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            jobs.add(enqueue());
        }
        int antes = FAKE.sends();
        assertThat(lento.dispatchPending()).isEqualTo(3);
        assertThat(lento.dispatchPending()).as("mesmo minuto: sem orcamento").isZero();
        assertThat(FAKE.sends()).isEqualTo(antes + 3);
        assertThat(jobs.stream().filter(j -> {
            try {
                return state(j).startsWith("1|0|");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).count()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ avisos da recuperacao emergencial (016)

    @Test
    void avisoEmergencialVaiDiretoAoTransporteComTentativasCurtas() {
        UUID org = UUID.randomUUID();
        FAKE.script.add(FakeGraph.Reply.of(503));
        FAKE.script.add(FakeGraph.Reply.of(202));
        assertThat(mail.sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_HOLDER, org, "titular@exemplo.test", "a".repeat(43), "b".repeat(43),
                UUID.randomUUID())).isEqualTo(MailSender.Outcome.ACEITO_PELO_TRANSPORTE);
        FAKE.script.add(FakeGraph.Reply.of(403));
        assertThat(mail.sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN, org, "admin@exemplo.test", null, "c".repeat(43), UUID.randomUUID()))
                .isEqualTo(MailSender.Outcome.RECUSADO_DEFINITIVO);
        for (int i = 0; i < 3; i++) {
            FAKE.script.add(FakeGraph.Reply.of(503));
        }
        assertThat(mail.sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_EXECUTED, org, "x@exemplo.test", null, null, UUID.randomUUID()))
                .as("esgotadas: INCERTO").isEqualTo(MailSender.Outcome.INCERTO);
    }

    // ------------------------------------------------------------------ protecoes do banco

    @Test
    void bancoProtegeOrigemTerminalEApagamento() throws Exception {
        UUID job = enqueue();
        assertThatThrownBy(() -> DB.asSuperuser("UPDATE mail_outbox SET kind = 'PASSWORD_RECOVERY' WHERE job_id = '" + job + "'"))
                .hasStackTraceContaining("origem do trabalho");
        assertThatThrownBy(() -> DB.asSuperuser("DELETE FROM mail_outbox WHERE job_id = '" + job + "'")).hasStackTraceContaining("nao e apagado");
        FAKE.script.add(FakeGraph.Reply.of(202));
        dispatcher.dispatchPending();
        assertThatThrownBy(() -> DB.asSuperuser("UPDATE mail_outbox SET state = 1 WHERE job_id = '" + job + "'")).hasStackTraceContaining("terminal");
        try (Connection c = DB.connectAs("nekkora_platform"); PreparedStatement ps = c.prepareStatement("DELETE FROM mail_outbox WHERE job_id = ?")) {
            ps.setObject(1, job);
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    // ------------------------------------------------------------------ apoio

    private UUID enqueue() throws Exception {
        String email = "aviso-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.test";
        mail.send(MailSender.Kind.NOTICE_PASSWORD_CHANGED, null, email, null);
        return UUID.fromString(sql("SELECT job_id FROM mail_outbox WHERE recipient_hmac = '" + hmac.global(email) + "'"));
    }

    private static void vencerEspera(UUID job) {
        DB.asSuperuser("UPDATE mail_outbox SET next_attempt_at = clock_timestamp() WHERE job_id = '" + job + "' AND state = 1");
    }

    /** estado|tentativas|ultimo status|payload apagado */
    private static String state(UUID job) throws Exception {
        return sql("SELECT state || '|' || attempts || '|' || coalesce(last_status::text, 'null') || '|' || (payload_ciphertext IS NULL)"
                + " FROM mail_outbox WHERE job_id = '" + job + "'");
    }

    private BrowserClient browser() {
        BrowserClient b = new BrowserClient("http://localhost:" + port);
        b.forwardedIp = "10.9." + (1 + new SecureRandom().nextInt(250)) + "." + (1 + new SecureRandom().nextInt(250));
        b.csrf = b.get("/api/v1/csrf").field("csrfToken");
        return b;
    }

    private static String sql(String q) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement(q); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static byte[] bytes(String q) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); PreparedStatement ps = c.prepareStatement(q); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getBytes(1) : null;
        }
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (hay[i + k] != needle[k]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
