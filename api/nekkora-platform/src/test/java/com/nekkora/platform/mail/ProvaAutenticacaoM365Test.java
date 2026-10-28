package com.nekkora.platform.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.nekkora.platform.secrets.AwsSecretsManager;

import tools.jackson.databind.json.JsonMapper;

/**
 * Prova do caminho de autenticacao ({@link ProvaAutenticacaoM365}) com leitor de segredo falso e Entra falso
 * ({@link FakeGraph}): leitura utilizavel (formato, tenant, uma chave, SHA-256 igual ao publicado), recusas com codigos
 * proprios e o token conferido (tenant, aplicativo, nenhum papel de aplicacao). Nunca envia mensagem; a saida nunca traz
 * o segredo, a senha, a chave ou o token.
 */
class ProvaAutenticacaoM365Test {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SENHA = "senha-do-certificado-de-teste";
    private static final String TENANT = "00000000-0000-0000-0000-00000000aaaa";
    private static final String CLIENT = "00000000-0000-0000-0000-00000000bbbb";
    private static Path dir;
    private static Path p12;
    private static String segredo;
    private static String certificadoHex;
    private FakeGraph fake;
    private ByteArrayOutputStream saida;
    private PrintStream ps;

    @BeforeAll
    static void certificado() throws Exception {
        dir = Files.createTempDirectory("nekkora-prova-aut-");
        p12 = FakeGraph.testCertificate(dir, SENHA);
        segredo = JSON.writeValueAsString(Map.of("formato", "nekkora-m365/1", "tenant_id", TENANT, "client_id", CLIENT,
                "certificado_pkcs12", Base64.getEncoder().encodeToString(Files.readAllBytes(p12)), "certificado_senha", SENHA));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(p12)) {
            ks.load(in, SENHA.toCharArray());
        }
        byte[] d = MessageDigest.getInstance("SHA-256").digest(((X509Certificate) ks.getCertificate("nekkora-teste")).getEncoded());
        certificadoHex = HexFormat.ofDelimiter(":").withUpperCase().formatHex(d);
    }

    @AfterAll
    static void limpa() throws Exception {
        Files.deleteIfExists(p12);
        Files.deleteIfExists(dir);
    }

    @BeforeEach
    void entra() throws Exception {
        fake = new FakeGraph();
        saida = new ByteArrayOutputStream();
        ps = new PrintStream(saida, true, StandardCharsets.UTF_8);
    }

    @AfterEach
    void para() {
        fake.close();
    }

    private Map<String, String> env() {
        return Map.of("NEKKORA_M365_TENANT", TENANT, "NEKKORA_M365_SEGREDO_AWS", "nekkora/homologacao/platform/m365", "NEKKORA_M365_AWS_REGIAO",
                "sa-east-1", "NEKKORA_M365_LOGIN_URL", fake.base(), "NEKKORA_M365_GRAPH_URL", fake.base());
    }

    private int prova(String valor, String... args) {
        return ProvaAutenticacaoM365.run(args, env(), ps, ps, id -> new AwsSecretsManager.Secret(valor, "versao-1"));
    }

    private String texto() {
        return saida.toString(StandardCharsets.UTF_8);
    }

    private static String jwt(Map<String, Object> claims) {
        Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
        return b.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b.encodeToString(JSON.writeValueAsBytes(claims)) + ".assinatura-falsa";
    }

    @Test
    void leituraUtilizavelComConteudoIgualAoPublicado() {
        assertThat(prova(segredo, "--segredo", "--esperado-sha256", ProvaAutenticacaoM365.sha256(segredo))).isZero();
        assertThat(texto()).contains("autenticacao_segredo leitura=OK versao=versao-1 formato=OK chave_privada=1 certificado_sha256=" + certificadoHex)
                .contains("conteudo=IGUAL");
        assertThat(fake.tokenRequests).as("--segredo nao fala com o Entra").isEmpty();
        assertThat(fake.sends()).isZero();
        String pkcs12 = JSON.readTree(segredo).path("certificado_pkcs12").asString();
        assertThat(texto()).doesNotContain(SENHA).doesNotContain(pkcs12.substring(0, 40));
    }

    @Test
    void conteudoDiferenteDoPublicadoRecusa() {
        String outro = "0".repeat(64);
        assertThat(prova(segredo, "--segredo", "--esperado-sha256", outro)).isEqualTo(3);
        assertThat(texto()).contains("conteudo=DIFERENTE").doesNotContain(ProvaAutenticacaoM365.sha256(segredo));
    }

    @Test
    void falhasDeLeituraEDeFormatoTemCodigosProprios() {
        assertThat(ProvaAutenticacaoM365.run(new String[] {"--segredo"}, env(), ps, ps, id -> {
            throw new IllegalStateException("segredo da AWS recusado (status 400, AccessDeniedException)");
        })).isEqualTo(5);
        assertThat(texto()).contains("leitura=FALHOU causa=segredo da AWS recusado (status 400, AccessDeniedException)");
        String outroTenant = segredo.replace(TENANT, "00000000-0000-0000-0000-00000000cccc");
        assertThat(prova(outroTenant, "--segredo")).isEqualTo(6);
        assertThat(texto()).contains("formato=INVALIDO");
        assertThat(prova(segredo, "--segredo", "--esperado-sha256", "ABC")).isEqualTo(64);
        assertThat(prova(segredo)).isEqualTo(64);
        assertThat(texto()).doesNotContain(SENHA);
    }

    @Test
    void tokenConferidoSemPapelDeAplicacao() {
        long exp = Instant.now().getEpochSecond() + 3600;
        fake.accessToken = jwt(Map.of("tid", TENANT, "appid", CLIENT, "aud", "https://graph.microsoft.com", "exp", exp));
        assertThat(prova(segredo, "--token")).isZero();
        assertThat(texto()).contains("autenticacao_token token=OBTIDO tid=OK aplicativo=OK aud=https://graph.microsoft.com papeis_de_aplicacao=0")
                .doesNotContain(fake.accessToken).doesNotContain(SENHA);
        assertThat(fake.tokenRequests).hasSize(1);
        assertThat(fake.sends()).as("a prova de autenticacao nao envia").isZero();
    }

    @Test
    void tokenComPapelDeAplicacaoOuDeOutroAplicativoReprova() {
        long exp = Instant.now().getEpochSecond() + 3600;
        fake.accessToken = jwt(Map.of("tid", TENANT, "appid", CLIENT, "aud", "https://graph.microsoft.com", "exp", exp, "roles", List.of("Mail.Send")));
        assertThat(prova(segredo, "--token")).as("papel do Graph consentido: o envio nao dependeria so do RBAC").isEqualTo(8);
        assertThat(texto()).contains("papeis_de_aplicacao=1");
        fake.accessToken = jwt(Map.of("tid", TENANT, "azp", "00000000-0000-0000-0000-00000000dddd", "exp", exp));
        assertThat(prova(segredo, "--token")).isEqualTo(8);
        assertThat(texto()).contains("aplicativo=DIFERENTE");
    }

    @Test
    void tokenRecusadoPeloEntra() {
        fake.tokenStatus = 401;
        assertThat(prova(segredo, "--token")).isEqualTo(7);
        assertThat(texto()).contains("autenticacao_token token=RECUSADO_OU_SEM_CONEXAO status=401");
    }
}
