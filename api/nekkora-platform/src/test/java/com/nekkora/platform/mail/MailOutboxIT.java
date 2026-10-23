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
import java.time.Duration;
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

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.platform.PlatformApplication;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.SecretCipher;

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
    MailIntents intents;
    @Autowired
    MailDispatcher dispatcher;
    @Autowired
    IdentityHmac hmac;
    @Autowired
    OrgTransactions tx;
    @Autowired
    SecretCipher cipher;
    @Autowired
    MailTemplates templates;
    @Autowired
    GraphMailTransport transport;

    /** Fila isolada por teste: trabalhos ainda vivos de outro teste sao encerrados (o despacho pega tudo o que esta vencido). */
    @BeforeEach
    void reset() {
        FAKE.script.clear();
        DB.asSuperuser("UPDATE mail_outbox SET state = 4, lease_until = NULL, finished_at = clock_timestamp(), payload_ciphertext = NULL,"
                + " payload_nonce = NULL, key_id = NULL WHERE state IN (1, 2)");
    }

    // ------------------------------------------------------------------ intencao duravel junto do ato

    /**
     * Cadastro real: a intencao e gravada NA transacao do ato (existe logo apos a resposta, sem despacho e sem fila em
     * memoria), com destinatario e token cifrados. "Queda" depois do commit = nenhum despacho desta instancia; um despachante
     * NOVO (o reinicio) a envia, e o ato nao se repete (um evento de pedido de cadastro). O token do e-mail conclui o cadastro.
     */
    @Test
    void intencaoGravadaComOAtoSobreviveAoReinicioSemRepetirOAto(CapturedOutput out) throws Exception {
        assertThat(mail).isInstanceOf(OutboxMailSender.class);
        assertThat(intents).isInstanceOf(OutboxMailIntents.class);
        String email = "fila-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.test";
        assertThat(browser().post("/api/v1/signup", "{\"email\":\"" + email + "\"}").status()).isEqualTo(202);
        String hash = hmac.global(email);
        assertThat(sql("SELECT kind || '|' || state || '|' || attempts || '|' || (event_key LIKE 'IDENTITY_SIGNUP_CONFIRMATION:token:%')"
                + " FROM mail_outbox WHERE recipient_hmac = '" + hash + "'")).as("intencao ja duravel na resposta").isEqualTo("IDENTITY_SIGNUP_CONFIRMATION|1|0|true");
        byte[] payload = bytes("SELECT payload_ciphertext FROM mail_outbox WHERE recipient_hmac = '" + hash + "'");
        assertThat(contains(payload, email.getBytes(StandardCharsets.UTF_8))).as("destinatario cifrado").isFalse();

        MailDispatcher reiniciado = new MailDispatcher(tx, cipher, transport, templates, 1000, Duration.ofMinutes(2), (n, o) -> { });
        int antes = FAKE.sends();
        assertThat(reiniciado.dispatchPending()).isGreaterThanOrEqualTo(1);
        assertThat(FAKE.sends()).isEqualTo(antes + 1);
        String body = FAKE.sendRequests.get(FAKE.sends() - 1).body();
        Matcher m = Pattern.compile(Pattern.quote(ORIGIN) + "/cadastro/confirmar#token=([A-Za-z0-9_-]{43})").matcher(body);
        assertThat(m.find()).as("link na origem publica, token no fragmento").isTrue();
        String token = m.group(1);
        assertThat(contains(payload, token.getBytes(StandardCharsets.UTF_8))).as("token cifrado na fila").isFalse();
        assertThat(sql("SELECT state || '|' || (payload_ciphertext IS NULL) FROM mail_outbox WHERE recipient_hmac = '" + hash + "'")).isEqualTo("3|true");
        assertThat(sql("SELECT count(*) FROM identity_events e JOIN identities i ON i.identity_id = e.identity_id WHERE i.email = '" + email
                + "' AND e.event_type = 1")).as("o ato nao se repete").isEqualTo("1");
        assertThat(browser().post("/api/v1/signup/confirm", "{\"token\":\"" + token + "\",\"password\":\"senha-pela-fila-de-email-1\",\"displayName\":\"Pessoa\"}")
                .status()).as("o token do e-mail conclui o cadastro").isEqualTo(204);
        assertThat(out.getAll()).doesNotContain(email).doesNotContain(token);
    }

    /** Se a intencao nao puder ser gravada, o ato tambem nao confirma: nenhuma identidade nasce sem a sua mensagem. */
    @Test
    void semIntencaoGravadaOAtoNaoConfirma() throws Exception {
        String email = "atomico-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.test";
        DB.asSuperuser("CREATE FUNCTION pg_temp_falha_fila() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'falha simulada da fila'; END $$",
                "CREATE TRIGGER trg_teste_falha_fila BEFORE INSERT ON mail_outbox FOR EACH ROW EXECUTE FUNCTION pg_temp_falha_fila()");
        try {
            assertThat(browser().post("/api/v1/signup", "{\"email\":\"" + email + "\"}").status()).isEqualTo(500);
        } finally {
            DB.asSuperuser("DROP TRIGGER trg_teste_falha_fila ON mail_outbox", "DROP FUNCTION pg_temp_falha_fila()");
        }
        assertThat(sql("SELECT count(*) FROM identities WHERE email = '" + email + "'")).as("ato desfeito com a intencao").isEqualTo("0");
        assertThat(browser().post("/api/v1/signup", "{\"email\":\"" + email + "\"}").status()).as("controle").isEqualTo(202);
        assertThat(sql("SELECT count(*) FROM identities WHERE email = '" + email + "'")).isEqualTo("1");
    }

    /** Deduplicacao persistente: o mesmo evento (mesma chave) nunca vira dois trabalhos. */
    @Test
    void mesmaChaveDeEventoGeraUmTrabalho() throws Exception {
        String email = "dedup-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.test";
        MailIntents.Intent i = new MailIntents.Intent(MailSender.Kind.NOTICE_PASSWORD_CHANGED, null, email, null, "senha:" + UUID.randomUUID());
        intents.recordAlone(i);
        intents.recordAlone(i);
        assertThat(sql("SELECT count(*) FROM mail_outbox WHERE recipient_hmac = '" + hmac.global(email) + "'")).isEqualTo("1");
    }

    // ------------------------------------------------------------------ posse

    /**
     * Posse vencida ENTRE a reivindicacao e o envio (posse de 1 ms): o trabalho NAO e enviado e volta a PENDENTE
     * (sabidamente nao enviado); depois, com posse normal, sai uma vez.
     */
    @Test
    void trabalhoNaoComecaDepoisDePerderAPosse() throws Exception {
        UUID job = enqueue();
        MailDispatcher instantaneo = new MailDispatcher(tx, cipher, transport, templates, 1000, Duration.ofMillis(1), (n, o) -> { });
        int antes = FAKE.sends();
        Thread.sleep(5);
        assertThat(instantaneo.dispatchPending()).isZero();
        assertThat(FAKE.sends()).as("nenhum envio sem posse").isEqualTo(antes);
        assertThat(state(job)).as("de volta a fila").startsWith("1|1|");
        assertThat(sql("SELECT next_attempt_at > clock_timestamp() FROM mail_outbox WHERE job_id = '" + job + "'")).as("com espera").isEqualTo("t");
        vencerEspera(job);
        dispatcher.dispatchPending();
        assertThat(state(job)).startsWith("3|2|202");
        assertThat(FAKE.sends()).isEqualTo(antes + 1);
    }

    /**
     * Dois despachantes, um com transporte LENTO (1,5 s por envio) e posse CURTA (1 s). Para todo envio que comeca, o
     * trabalho estava ENVIANDO e com posse valida naquele instante; nenhum trabalho e enviado duas vezes; no fim, todos
     * terminais. O envio lento que perde a posse no meio fica INCERTO (nunca reenviado).
     */
    @Test
    void doisDespachantesComTransporteLentoNuncaEnviamSemPosse() throws Exception {
        List<UUID> jobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            jobs.add(enqueue());
        }
        java.util.concurrent.ConcurrentLinkedQueue<String> violacoes = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.concurrent.ConcurrentHashMap<UUID, Integer> envios = new java.util.concurrent.ConcurrentHashMap<>();
        MailTransport lento = conferindoPosse(1500, violacoes, envios);
        MailTransport rapido = conferindoPosse(0, violacoes, envios);
        MailDispatcher a = new MailDispatcher(tx, cipher, lento, templates, 1000, Duration.ofSeconds(1), (n, o) -> { });
        MailDispatcher b = new MailDispatcher(tx, cipher, rapido, templates, 1000, Duration.ofMinutes(2), (n, o) -> { });
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<?> fa = ex.submit(a::dispatchPending);
            Future<?> fb = ex.submit(() -> {
                long fim = System.nanoTime() + 5_000_000_000L;
                while (System.nanoTime() < fim) {
                    b.dispatchPending();
                    pause(100);
                }
                return null;
            });
            fa.get(60, TimeUnit.SECONDS);
            fb.get(60, TimeUnit.SECONDS);
        } finally {
            ex.shutdownNow();
        }
        b.dispatchPending();
        assertThat(violacoes).as("envio comecado sem posse").isEmpty();
        assertThat(envios.values()).as("nenhum envio duplicado").allMatch(n -> n == 1);
        for (UUID j : jobs) {
            assertThat(state(j)).as("terminal").matches("^[35]\\|.*");
        }
    }

    /**
     * Reconferencia com ESPERA pelo lock da linha (outra transacao a trava sem modifica-la, entre a reivindicacao e a
     * reconferencia), vencendo durante a espera: (a) a posse; (b) o prazo da mensagem. Nenhum dos dois chama o transporte.
     * (c) Controle: o lock e liberado antes do vencimento e o envio acontece.
     */
    @Test
    void reconferenciaDepoisDaEsperaPeloLockNaoEnviaVencidos() throws Exception {
        java.util.concurrent.atomic.AtomicInteger chamadas = new java.util.concurrent.atomic.AtomicInteger();
        MailTransport contando = m -> {
            chamadas.incrementAndGet();
            return new MailTransport.Delivery(MailTransport.Result.ACEITO, 202, null);
        };
        // (a) posse de 1,5 s; lock segurado 2,5 s
        UUID a = enqueue();
        MailDispatcher da = new MailDispatcher(tx, cipher, contando, templates, 1000, Duration.ofMillis(1500), (n, o) -> { });
        da.testHooks(() -> travarLinha(a, 2500), () -> { });
        da.dispatchPending();
        assertThat(chamadas.get()).as("(a) posse vencida na espera: nenhum envio").isZero();
        assertThat(state(a)).as("(a) de volta a fila, nao enviado").startsWith("1|1|");
        // (b) prazo da mensagem a 1,5 s; posse longa; lock segurado 2,5 s
        UUID b = enqueue();
        DB.asSuperuser("SET session_replication_role = replica; UPDATE mail_outbox SET deadline_at = clock_timestamp() + interval '1500 milliseconds'"
                + " WHERE job_id = '" + b + "'; SET session_replication_role = origin");
        MailDispatcher db = new MailDispatcher(tx, cipher, contando, templates, 1000, Duration.ofMinutes(2), (n, o) -> { });
        db.testHooks(() -> travarLinha(b, 2500), () -> { });
        db.dispatchPending();
        assertThat(chamadas.get()).as("(b) prazo vencido na espera: nenhum envio").isZero();
        db.testHooks(() -> { }, () -> { });
        db.dispatchPending();
        assertThat(state(b)).as("(b) expira sem envio").startsWith("6|");
        // (c) controle: lock liberado antes do vencimento da posse
        UUID c = enqueue();
        MailDispatcher dc = new MailDispatcher(tx, cipher, contando, templates, 1000, Duration.ofMillis(1500), (n, o) -> { });
        dc.testHooks(() -> travarLinha(c, 300), () -> { });
        DB.asSuperuser("UPDATE mail_outbox SET next_attempt_at = clock_timestamp() + interval '1 hour' WHERE state = 1 AND job_id <> '" + c + "'");
        dc.dispatchPending();
        assertThat(chamadas.get()).as("(c) controle: envia").isEqualTo(1);
        assertThat(state(c)).startsWith("3|1|202");
    }

    /**
     * Segura o lock da linha (FOR UPDATE, sem modificar) numa transacao do superusuario por {@code ms}, em outra thread;
     * so retorna depois que o lock esta tomado.
     */
    private static void travarLinha(UUID job, long ms) {
        java.util.concurrent.CountDownLatch travado = new java.util.concurrent.CountDownLatch(1);
        Thread t = new Thread(() -> {
            try (Connection c = DB.connectAsSuperuser()) {
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM mail_outbox WHERE job_id = ? FOR UPDATE")) {
                    ps.setObject(1, job);
                    ps.executeQuery().close();
                }
                travado.countDown();
                Thread.sleep(ms);
                c.commit();
            } catch (Exception e) {
                travado.countDown();
            }
        });
        t.setDaemon(true);
        t.start();
        try {
            travado.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    @Test
    void ritmoPorMinutoLimitaOsEnvios() throws Exception {
        MailDispatcher lento = new MailDispatcher(tx, cipher, transport, templates, 3, Duration.ofMinutes(2), (n, o) -> { });
        List<UUID> jobs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            jobs.add(enqueue());
        }
        int antes = FAKE.sends();
        assertThat(lento.dispatchPending()).isEqualTo(3);
        assertThat(lento.dispatchPending()).as("mesmo minuto: sem orcamento").isZero();
        assertThat(FAKE.sends()).isEqualTo(antes + 3);
        long pendentes = 0;
        for (UUID j : jobs) {
            pendentes += state(j).startsWith("1|0|") ? 1 : 0;
        }
        assertThat(pendentes).isEqualTo(2);
    }

    // ------------------------------------------------------------------ avisos da recuperacao emergencial (016)

    /**
     * Aviso emergencial: ENFILEIRADO na hora (ninguem fica esperando); o Retry-After do provedor e respeitado POR INTEIRO
     * (60 s e 1 h), sem nova tentativa antes; o desfecho terminal volta a tentativa de origem.
     */
    @Test
    void avisoEmergencialRespeitaORetryAfterEDevolveODesfecho() throws Exception {
        java.util.concurrent.ConcurrentHashMap<UUID, MailSender.Outcome> devolvidos = new java.util.concurrent.ConcurrentHashMap<>();
        MailDispatcher d = new MailDispatcher(tx, cipher, transport, templates, 1000, Duration.ofMinutes(2), devolvidos::put);
        UUID tentativa = UUID.randomUUID();
        long t0 = System.nanoTime();
        assertThat(mail.sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_HOLDER, UUID.randomUUID(), "titular@exemplo.test", "a".repeat(43),
                "b".repeat(43), tentativa)).isEqualTo(MailSender.Outcome.ENFILEIRADO);
        assertThat((System.nanoTime() - t0) / 1_000_000).as("sem espera sincrona").isLessThan(2000);
        UUID job = UUID.fromString(sql("SELECT job_id FROM mail_outbox WHERE notice_ref = '" + tentativa + "'"));

        FAKE.script.add(new FakeGraph.Reply(429, "60", 0));
        d.dispatchPending();
        assertThat(sql("SELECT state || '|' || (next_attempt_at >= clock_timestamp() + interval '59 seconds') FROM mail_outbox WHERE job_id = '" + job + "'"))
                .as("60 s respeitados").isEqualTo("1|true");
        int antes = FAKE.sends();
        d.dispatchPending();
        assertThat(FAKE.sends()).as("nenhuma tentativa antes do prazo").isEqualTo(antes);
        vencerEspera(job);
        FAKE.script.add(new FakeGraph.Reply(503, "3600", 0));
        d.dispatchPending();
        assertThat(sql("SELECT next_attempt_at >= clock_timestamp() + interval '3599 seconds' FROM mail_outbox WHERE job_id = '" + job + "'"))
                .as("1 h respeitada (antes, acima de 1 h era ignorado)").isEqualTo("t");
        vencerEspera(job);
        d.dispatchPending();
        assertThat(state(job)).startsWith("3|3|202");
        assertThat(devolvidos).containsEntry(tentativa, MailSender.Outcome.ACEITO_PELO_TRANSPORTE);
        String corpo = FAKE.sendRequests.get(FAKE.sends() - 1).body();
        assertThat(corpo).contains("/mfa-emergencia/confirmar#token=" + "a".repeat(43)).contains("/mfa-emergencia/contestar#token=" + "b".repeat(43));
    }

    // ------------------------------------------------------------------ protecoes e retencao

    @Test
    void bancoProtegeOrigemTerminalEApagamento() throws Exception {
        UUID job = enqueue();
        assertThatThrownBy(() -> DB.asSuperuser("UPDATE mail_outbox SET kind = 'PASSWORD_RECOVERY' WHERE job_id = '" + job + "'"))
                .hasStackTraceContaining("origem do trabalho");
        assertThatThrownBy(() -> DB.asSuperuser("DELETE FROM mail_outbox WHERE job_id = '" + job + "'")).hasStackTraceContaining("30 dias");
        FAKE.script.add(FakeGraph.Reply.of(202));
        dispatcher.dispatchPending();
        assertThatThrownBy(() -> DB.asSuperuser("UPDATE mail_outbox SET state = 1 WHERE job_id = '" + job + "'")).hasStackTraceContaining("terminal");
    }

    /** Retencao: terminal recente nao sai; terminal encerrado ha mais de 30 dias sai pela limpeza; pendente nunca. */
    @Test
    void retencaoApagaSoTerminaisAntigos() throws Exception {
        UUID recente = enqueue();
        dispatcher.dispatchPending();
        UUID antigo = enqueue();
        dispatcher.dispatchPending();
        DB.asSuperuser("SET session_replication_role = replica; UPDATE mail_outbox SET created_at = clock_timestamp() - interval '40 days',"
                + " deadline_at = clock_timestamp() - interval '39 days', finished_at = clock_timestamp() - interval '31 days' WHERE job_id = '" + antigo
                + "'; SET session_replication_role = origin");
        UUID pendente = enqueue();
        dispatcher.purgeFinished();
        assertThat(sql("SELECT count(*) FROM mail_outbox WHERE job_id = '" + antigo + "'")).as("antigo apagado").isEqualTo("0");
        assertThat(sql("SELECT count(*) FROM mail_outbox WHERE job_id = '" + recente + "'")).as("recente fica").isEqualTo("1");
        assertThat(sql("SELECT count(*) FROM mail_outbox WHERE job_id = '" + pendente + "'")).as("pendente fica").isEqualTo("1");
        try (Connection c = DB.connectAs("nekkora_platform"); PreparedStatement ps = c.prepareStatement("DELETE FROM mail_outbox WHERE job_id = ?")) {
            ps.setObject(1, recente);
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class).hasMessageContaining("30 dias");
        }
    }

    // ------------------------------------------------------------------ apoio

    /** Transporte de teste: confere a posse no INICIO de cada envio, conta envios por trabalho e responde 202 depois de {@code ms}. */
    private MailTransport conferindoPosse(long ms, java.util.Queue<String> violacoes, java.util.Map<UUID, Integer> envios) {
        return m -> {
            try {
                String ok = sql("SELECT state = 2 AND lease_until > clock_timestamp() FROM mail_outbox WHERE correlation_id = '" + m.correlation() + "'");
                if (!"t".equals(ok)) {
                    violacoes.add(m.correlation() + ":" + ok);
                }
            } catch (Exception e) {
                violacoes.add("consulta: " + e.getClass().getSimpleName());
            }
            envios.merge(m.correlation(), 1, Integer::sum);
            pause(ms);
            return new MailTransport.Delivery(MailTransport.Result.ACEITO, 202, null);
        };
    }

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

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
