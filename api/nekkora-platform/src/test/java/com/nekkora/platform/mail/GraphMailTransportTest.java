package com.nekkora.platform.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transporte Graph (missao 002, PR 5) contra o Entra e o Graph FALSOS ({@link FakeGraph}): assercao de certificado
 * (PS256, x5t#S256, reivindicacoes, assinatura conferida com a chave publica), formulario do token sem segredo de cliente,
 * token em cache, pedido de sendMail (remetente na URL, um destinatario, saveToSentItems=false) e o mapeamento dos
 * desfechos do desenho de 2026-10-11 (§6.1). Partida recusada sem configuracao.
 */
class GraphMailTransportTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SENHA = "senha-do-certificado-de-teste";
    private static final String TENANT = "00000000-0000-0000-0000-00000000aaaa";
    private static final String CLIENT = "00000000-0000-0000-0000-00000000bbbb";
    private static Path dir;
    private static Path p12;
    private static X509Certificate cert;
    private FakeGraph fake;

    @BeforeAll
    static void certificate() throws Exception {
        dir = Files.createTempDirectory("nekkora-m365-");
        p12 = FakeGraph.testCertificate(dir, SENHA);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) {
            ks.load(in, SENHA.toCharArray());
        }
        cert = (X509Certificate) ks.getCertificate("nekkora-teste");
    }

    @AfterAll
    static void cleanUp() throws Exception {
        Files.deleteIfExists(p12);
        Files.deleteIfExists(dir);
    }

    @BeforeEach
    void server() throws Exception {
        fake = new FakeGraph();
    }

    @org.junit.jupiter.api.AfterEach
    void stop() {
        fake.close();
    }

    private GraphMailTransport transport(Duration timeout) {
        return new GraphMailTransport(new GraphMailTransport.Config(TENANT, CLIENT, "no-reply@exemplo.test", p12, SENHA.toCharArray(), timeout,
                fake.base(), fake.base()));
    }

    private static MailTransport.Message message() {
        return new MailTransport.Message(UUID.randomUUID(), "destino@exemplo.test", "Assunto", "Corpo da mensagem");
    }

    @Test
    void assercaoDeCertificadoSegueADocumentacaoEAAssinaturaConfere() throws Exception {
        GraphMailTransport t = transport(Duration.ofSeconds(5));
        assertThat(t.deliver(message()).result()).isEqualTo(MailTransport.Result.ACEITO);
        assertThat(fake.tokenRequests).hasSize(1);
        Map<String, String> form = form(fake.tokenRequests.get(0).body());
        assertThat(form).containsEntry("grant_type", "client_credentials").containsEntry("client_id", CLIENT)
                .containsEntry("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                .containsEntry("scope", fake.base() + "/.default").doesNotContainKey("client_secret");
        assertThat(fake.tokenRequests.get(0).path()).isEqualTo("/" + TENANT + "/oauth2/v2.0/token");

        String[] jwt = form.get("client_assertion").split("\\.");
        assertThat(jwt).hasSize(3);
        JsonNode h = JSON.readTree(Base64.getUrlDecoder().decode(jwt[0]));
        JsonNode c = JSON.readTree(Base64.getUrlDecoder().decode(jwt[1]));
        assertThat(h.path("alg").asString()).isEqualTo("PS256");
        assertThat(h.path("typ").asString()).isEqualTo("JWT");
        assertThat(h.path("x5t#S256").asString())
                .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())));
        assertThat(h.has("x5t")).as("sem a impressao SHA-1").isFalse();
        assertThat(c.path("aud").asString()).isEqualTo(fake.base() + "/" + TENANT + "/oauth2/v2.0/token");
        assertThat(c.path("iss").asString()).isEqualTo(CLIENT);
        assertThat(c.path("sub").asString()).isEqualTo(CLIENT);
        assertThat(UUID.fromString(c.path("jti").asString())).isNotNull();
        assertThat(c.path("exp").asLong() - c.path("nbf").asLong()).as("vida curta (5 min)").isEqualTo(300);
        Signature s = Signature.getInstance("RSASSA-PSS");
        s.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        s.initVerify(cert.getPublicKey());
        s.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(s.verify(Base64.getUrlDecoder().decode(jwt[2]))).as("assinatura PS256 com a chave do certificado").isTrue();
    }

    @Test
    void pedidoDeEnvioPeloRemetenteComUmDestinatarioSemCopiaNemItensEnviados() throws Exception {
        MailTransport.Message m = message();
        assertThat(transport(Duration.ofSeconds(5)).deliver(m).status()).isEqualTo(202);
        FakeGraph.Request r = fake.sendRequests.get(0);
        assertThat(r.path()).isEqualTo("/v1.0/users/no-reply@exemplo.test/sendMail");
        assertThat(r.authorization()).isEqualTo("Bearer falso-1");
        assertThat(r.clientRequestId()).isEqualTo(m.correlation().toString());
        JsonNode b = JSON.readTree(r.body());
        assertThat(b.path("saveToSentItems").asBoolean(true)).isFalse();
        assertThat(b.path("message").path("toRecipients")).hasSize(1);
        assertThat(b.path("message").path("toRecipients").get(0).path("emailAddress").path("address").asString()).isEqualTo("destino@exemplo.test");
        assertThat(b.path("message").has("ccRecipients") || b.path("message").has("bccRecipients") || b.path("message").has("from")).isFalse();
        assertThat(b.path("message").path("body").path("contentType").asString()).isEqualTo("Text");
    }

    @Test
    void tokenEmCacheEInvalidadoPor401() {
        GraphMailTransport t = transport(Duration.ofSeconds(5));
        t.deliver(message());
        t.deliver(message());
        t.deliver(message());
        assertThat(fake.tokenRequests).as("um token para tres envios").hasSize(1);
        fake.script.add(FakeGraph.Reply.of(401));
        assertThat(t.deliver(message()).result()).isEqualTo(MailTransport.Result.RECUSADO);
        t.deliver(message());
        assertThat(fake.tokenRequests).as("401 descarta o token em cache").hasSize(2);
    }

    @Test
    void desfechosSeguemODesenho() {
        GraphMailTransport t = transport(Duration.ofSeconds(1));
        fake.script.add(FakeGraph.Reply.of(403));
        fake.script.add(FakeGraph.Reply.of(404));
        fake.script.add(FakeGraph.Reply.of(400));
        fake.script.add(new FakeGraph.Reply(429, "7", 0));
        fake.script.add(FakeGraph.Reply.of(503));
        fake.script.add(FakeGraph.Reply.of(504));
        fake.script.add(FakeGraph.Reply.of(500));
        fake.script.add(new FakeGraph.Reply(202, null, 2500));
        assertThat(t.deliver(message()).result()).as("403").isEqualTo(MailTransport.Result.RECUSADO);
        assertThat(t.deliver(message()).result()).as("404").isEqualTo(MailTransport.Result.RECUSADO);
        assertThat(t.deliver(message()).result()).as("400").isEqualTo(MailTransport.Result.RECUSADO);
        MailTransport.Delivery limit = t.deliver(message());
        assertThat(limit.result()).as("429").isEqualTo(MailTransport.Result.RETENTAVEL);
        assertThat(limit.retryAfter()).isEqualTo(Duration.ofSeconds(7));
        assertThat(t.deliver(message()).result()).as("503").isEqualTo(MailTransport.Result.RETENTAVEL);
        assertThat(t.deliver(message()).result()).as("504").isEqualTo(MailTransport.Result.RETENTAVEL);
        assertThat(t.deliver(message()).result()).as("500: o provedor pode ter processado").isEqualTo(MailTransport.Result.INCERTO);
        assertThat(t.deliver(message()).result()).as("resposta depois do prazo: pode ter saido").isEqualTo(MailTransport.Result.INCERTO);
    }

    @Test
    void semConexaoERetentavelETokenRecusadoNaoEnvia() throws Exception {
        GraphMailTransport t = transport(Duration.ofSeconds(1));
        fake.tokenStatus = 401;
        assertThat(t.deliver(message()).result()).as("Entra recusa: definitivo").isEqualTo(MailTransport.Result.RECUSADO);
        assertThat(fake.sends()).isZero();
        fake.tokenStatus = 200;
        t.deliver(message());
        fake.close();
        assertThat(t.deliver(message()).result()).as("conexao recusada: nada saiu").isEqualTo(MailTransport.Result.RETENTAVEL);
    }

    @Test
    void partidaRecusadaSemConfiguracao() {
        assertThatThrownBy(() -> new GraphMailTransport(new GraphMailTransport.Config("", CLIENT, "r@x", p12, SENHA.toCharArray(), Duration.ofSeconds(1),
                fake.base(), fake.base()))).hasMessageContaining("tenant-id");
        assertThatThrownBy(() -> new GraphMailTransport(new GraphMailTransport.Config(TENANT, CLIENT, "", p12, SENHA.toCharArray(), Duration.ofSeconds(1),
                fake.base(), fake.base()))).hasMessageContaining("remetente");
        assertThatThrownBy(() -> new GraphMailTransport(new GraphMailTransport.Config(TENANT, CLIENT, "r@x", dir.resolve("ausente.p12"), SENHA.toCharArray(),
                Duration.ofSeconds(1), fake.base(), fake.base()))).hasMessageContaining("certificado");
        assertThatThrownBy(() -> new GraphMailTransport(new GraphMailTransport.Config(TENANT, CLIENT, "r@x", p12, "errada".toCharArray(), Duration.ofSeconds(1),
                fake.base(), fake.base()))).hasMessageContaining("certificado");
        assertThat(transport(Duration.ofSeconds(1)).certificateDaysLeft()).isBetween(0L, 2L);
        assertThat(new GraphMailTransport.Config(TENANT, CLIENT, "r@x", p12, SENHA.toCharArray(), Duration.ofSeconds(1), "a", "b").toString())
                .doesNotContain(SENHA).doesNotContain("r@x");
    }

    /** Saida por proxy: o pedido vai ao proxy (aqui, o servidor falso) com a URL absoluta do destino. */
    @Test
    void saidaPorProxyConfigurado() {
        String proxy = fake.base().substring("http://".length());
        GraphMailTransport t = new GraphMailTransport(new GraphMailTransport.Config(TENANT, CLIENT, "no-reply@exemplo.test", p12, SENHA.toCharArray(),
                Duration.ofSeconds(5), "http://login.destino.invalid", "http://graph.destino.invalid", proxy));
        assertThat(t.deliver(message()).result()).isEqualTo(MailTransport.Result.ACEITO);
        assertThat(fake.tokenRequests.get(0).path()).isEqualTo("/" + TENANT + "/oauth2/v2.0/token");
        assertThat(fake.sendRequests.get(0).path()).isEqualTo("/v1.0/users/no-reply@exemplo.test/sendMail");
        assertThatThrownBy(() -> new GraphMailTransport(new GraphMailTransport.Config(TENANT, CLIENT, "r@x", p12, SENHA.toCharArray(), Duration.ofSeconds(1),
                "a", "b", "sem-porta"))).hasMessageContaining("host:porta");
    }

    /**
     * Comando de prova de homologacao: remetente autorizado aceito (0); recusa do Graph a outro remetente (403 -> 2); uso
     * incorreto (64). A saida nunca traz o token de acesso nem a senha do certificado.
     */
    @Test
    void comandoDeProvaDeHomologacao() {
        Map<String, String> env = Map.of("NEKKORA_M365_TENANT", TENANT, "NEKKORA_M365_CLIENT", CLIENT, "NEKKORA_M365_CERT", p12.toString(),
                "NEKKORA_M365_CERT_SENHA", SENHA, "NEKKORA_M365_LOGIN_URL", fake.base(), "NEKKORA_M365_GRAPH_URL", fake.base());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(out, true, StandardCharsets.UTF_8);
        assertThat(ProvaEnvioM365.run(new String[] {"--remetente", "no-reply@exemplo.test", "--para", "controle@exemplo.test"}, env, ps, ps)).isZero();
        assertThat(fake.sendRequests.get(0).path()).isEqualTo("/v1.0/users/no-reply@exemplo.test/sendMail");
        fake.script.add(FakeGraph.Reply.of(403));
        assertThat(ProvaEnvioM365.run(new String[] {"--remetente", "outra@exemplo.test", "--para", "controle@exemplo.test"}, env, ps, ps)).isEqualTo(2);
        assertThat(fake.sendRequests.get(1).path()).isEqualTo("/v1.0/users/outra@exemplo.test/sendMail");
        assertThat(ProvaEnvioM365.run(new String[] {"--para", "x"}, env, ps, ps)).isEqualTo(64);
        String saida = out.toString(StandardCharsets.UTF_8);
        assertThat(saida).contains("prova_envio desfecho=ACEITO status=202").contains("prova_envio desfecho=RECUSADO status=403")
                .doesNotContain("falso-").doesNotContain(SENHA);
    }

    /**
     * Cliente e certificado do segredo da AWS (formato nekkora-m365/1): o PKCS#12 em base64 do segredo monta o mesmo transporte
     * que o arquivo, a mesma assercao confere, e o segredo e apagado da memoria depois da partida.
     */
    @Test
    void transportePeloSegredoNoFormatoNekkoraM365() throws Exception {
        String secret = JSON.writeValueAsString(Map.of("formato", "nekkora-m365/1", "tenant_id", TENANT, "client_id", CLIENT,
                "certificado_pkcs12", Base64.getEncoder().encodeToString(Files.readAllBytes(p12)), "certificado_senha", SENHA));
        String tenant = TENANT;
        M365Segredo m = M365Segredo.ler(secret, tenant);
        GraphMailTransport t;
        try {
            t = new GraphMailTransport(new GraphMailTransport.Config(tenant, m.clientId(), "no-reply@exemplo.test", null, m.senha(), Duration.ofSeconds(5),
                    fake.base(), fake.base()), m.pkcs12());
        } finally {
            m.apagar();
        }
        assertThat(m.pkcs12()).containsOnly(0);
        assertThat(t.deliver(message()).result()).isEqualTo(MailTransport.Result.ACEITO);
        assertThat(form(fake.tokenRequests.get(0).body())).containsEntry("client_id", CLIENT);
        assertThat(t.certificateThumbprint())
                .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())));
        assertThatThrownBy(() -> new GraphMailTransport(new GraphMailTransport.Config(TENANT, CLIENT, "r@x", null, SENHA.toCharArray(), Duration.ofSeconds(1),
                fake.base(), fake.base()), new byte[0])).hasMessageContaining("certificado");
    }

    /**
     * Comando de prova com a lista fechada de destinatarios: fora da lista, nada e enviado (64, nenhum pedido ao Graph); em
     * ensaio-* sem lista, tambem 64. Na lista, o envio segue.
     */
    @Test
    void comandoDeProvaRespeitaAListaDeDestinatarios() {
        Map<String, String> base = Map.of("NEKKORA_M365_TENANT", TENANT, "NEKKORA_M365_CLIENT", CLIENT, "NEKKORA_M365_CERT", p12.toString(),
                "NEKKORA_M365_CERT_SENHA", SENHA, "NEKKORA_M365_LOGIN_URL", fake.base(), "NEKKORA_M365_GRAPH_URL", fake.base());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream ps = new java.io.PrintStream(out, true, StandardCharsets.UTF_8);
        Map<String, String> ensaioSemLista = new HashMap<>(base);
        ensaioSemLista.put("NEKKORA_AMBIENTE", "ensaio-api-v1-003");
        assertThat(ProvaEnvioM365.run(new String[] {"--remetente", "no-reply@exemplo.test", "--para", "controle@exemplo.test"}, ensaioSemLista, ps, ps))
                .isEqualTo(64);
        Map<String, String> comLista = new HashMap<>(ensaioSemLista);
        comLista.put("NEKKORA_MAIL_DESTINATARIOS_PERMITIDOS", "controle@exemplo.test");
        assertThat(ProvaEnvioM365.run(new String[] {"--remetente", "no-reply@exemplo.test", "--para", "outro@exemplo.test"}, comLista, ps, ps))
                .isEqualTo(64);
        assertThat(fake.tokenRequests).as("nada saiu").isEmpty();
        assertThat(fake.sendRequests).isEmpty();
        assertThat(ProvaEnvioM365.run(new String[] {"--remetente", "no-reply@exemplo.test", "--para", "Controle@exemplo.test"}, comLista, ps, ps)).isZero();
        assertThat(fake.sendRequests).hasSize(1);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("fora de NEKKORA_MAIL_DESTINATARIOS_PERMITIDOS").contains("ensaio-* exige");
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
