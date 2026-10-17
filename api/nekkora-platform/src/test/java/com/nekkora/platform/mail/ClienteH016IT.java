package com.nekkora.platform.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;
import com.nekkora.ops.testsupport.Pki;
import com.nekkora.platform.PlatformApplication;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.emergency.EmergencyNotices;

/**
 * P1c: os cenarios NOVOS do cliente da homologacao ({@code ops/homologacao/testes/cliente.py}: h016_mfa, h016_confirmar,
 * h016_novo_mfa) executados ISOLADAMENTE, sem alteracao, contra a Platform REAL em HTTPS com o nome da origem
 * (certificado de teste; o cliente confere cadeia e SNI como faria com o gateway), transporte de homologacao ligado e
 * caixa em diretorio temporario; base 001-016 exclusiva. Isto NAO e o h016.sh integrado (gateway, compose, runner): este
 * continua pertencendo ao ensaio.
 *
 * <p>Preparacao declarada: organizacao sintetica com dois ORG_ADMIN e senha Argon2 real (como fixture_016.sql +
 * provision_first_org_admin), contatos e operadores pelos caminhos reais da suite, espera de 24 h pela fixture temporal
 * da suite (OpsBanco.recuarSolicitacao). O token CONFIRMAR sai da caixa para um arquivo temporario (nunca para a evidencia).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, classes = PlatformApplication.class)
class ClienteH016IT {

    static final OpsBanco DB = OpsBanco.nova(OpsBanco.Forma.padrao());
    static final Cenarios C = new Cenarios(DB);
    static final String HOST = "homologacao.nekkora.test";
    static final int PORTA = portaLivre();
    static final Path TMP = temporario();
    static final PasswordHasher HASHER = new PasswordHasher(new PasswordHasher.Parameters(65536, 3, 1), 4, 8, 5000);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    EmergencyNotices notices;

    static int portaLivre() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Path temporario() {
        try {
            Path d = Files.createTempDirectory("p1c-cliente-");
            Files.createDirectories(d.resolve("caixa"));
            Pki.Ca ca = Pki.ca("Nekkora Homologacao CA P1c");
            KeyPair k = Pki.ec();
            Pki.escrever(d, "ca.crt", Pki.pem(ca.certificado()));
            Pki.escrever(d, "gateway.crt", Pki.pem(Pki.servidor(ca, k.getPublic(), List.of(HOST))));
            Pki.escrever(d, "gateway.key", Pki.pemChave(k.getPrivate()));
            return d;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("server.port", () -> PORTA);
        r.add("server.ssl.enabled", () -> "true");
        r.add("server.ssl.certificate", () -> TMP.resolve("gateway.crt").toUri().toString());
        r.add("server.ssl.certificate-private-key", () -> TMP.resolve("gateway.key").toUri().toString());
        r.add("nekkora.database.url", DB::urlTls);
        r.add("nekkora.database.user", () -> "nekkora_platform");
        r.add("nekkora.database.password", () -> DB.senha("nekkora_platform"));
        r.add("spring.data.redis.host", () -> RealRedis.shared().host());
        r.add("spring.data.redis.port", () -> RealRedis.shared().port());
        r.add("nekkora.public-origin", () -> "https://" + HOST + ":" + PORTA);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.keys.aes-current", () -> "k1");
        r.add("nekkora.keys.aes-by-id", () -> "k1:" + b64(32));
        r.add("nekkora.keys.hmac-id", () -> "h1");
        r.add("nekkora.keys.hmac", () -> b64(32));
        r.add("nekkora.mail.transport", () -> "homologacao");
        r.add("nekkora.environment", () -> "ensaio-p1c-cliente");
        r.add("nekkora.mail.caixa-dir", () -> TMP.resolve("caixa").toString());
    }

    private static String b64(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    @AfterAll
    static void encerrar() throws IOException {
        DB.encerrar();
        try (var w = Files.walk(TMP)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    record Saida(int codigo, String texto) {
    }

    /** cliente.py REAL (sem alteracao) com as variaveis que o h016.sh exporta. */
    static Saida cliente(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("python", OpsBanco.raizDoRepositorio().resolve("ops/homologacao/testes/cliente.py").toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().putAll(Map.of("HOMOLOG_HOST", HOST, "HOMOLOG_PORTA", String.valueOf(PORTA), "HOMOLOG_CA", TMP.resolve("ca.crt").toString(),
                "HOMOLOG_SEGREDOS_VISTOS", TMP.resolve("segredos_vistos.txt").toString(), "HOMOLOG_ESTADO", TMP.resolve("estado.json").toString()));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(5, TimeUnit.MINUTES)).isTrue();
        evidencia("cliente.py " + args[0] + " -> codigo=" + p.exitValue() + "\n" + out);
        return new Saida(p.exitValue(), out);
    }

    static final List<String> EVIDENCIA = new ArrayList<>();

    static void evidencia(String s) {
        EVIDENCIA.add(s);
        try {
            Path d = Path.of(System.getProperty("nekkora.p1c.evidencias", "target/p1c-evidencias")).resolve("cliente");
            Files.createDirectories(d);
            Files.writeString(d.resolve("cenarios_h016.txt"), s + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void cenariosNovosDoClienteContraAPlatformReal() throws Exception {
        // organizacao sintetica (como fixture_016.sql): titular e outro ORG_ADMIN com senha real, sem fator (1o acesso)
        UUID org = UUID.randomUUID();
        UUID adm1 = UUID.randomUUID();
        UUID adm2 = UUID.randomUUID();
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        String s1 = "sintetica-" + b64(9).replaceAll("[^A-Za-z0-9]", "x");
        String s2 = "sintetica-" + b64(9).replaceAll("[^A-Za-z0-9]", "x");
        String e1 = "titular-" + adm1.toString().substring(0, 8) + "@homologacao-016.test";
        String e2 = "outro-" + adm2.toString().substring(0, 8) + "@homologacao-016.test";
        DB.sqlNa(org, "INSERT INTO organizations (organization_id, name, status) VALUES ('" + org + "', 'Homologacao 016 sintetica ensaio-p1c-cliente', 1)",
                "INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + adm1 + "', '"
                        + org + "', 1, '" + e1 + "', now(), 2, 'Titular 016'), ('" + adm2 + "', '" + org + "', 1, '" + e2 + "', now(), 2, 'Outro admin 016')",
                "INSERT INTO user_credentials (user_id, password_hash) VALUES ('" + adm1 + "', '" + HASHER.generate(s1.toCharArray()) + "'), ('" + adm2
                        + "', '" + HASHER.generate(s2.toCharArray()) + "')",
                "INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('" + m1 + "', '" + org
                        + "', '" + adm1 + "', 1, 2), ('" + m2 + "', '" + org + "', '" + adm2 + "', 1, 2)",
                "SELECT provision_first_org_admin('" + org + "', '" + m1 + "', '" + adm1 + "', 'homologacao-016', gen_random_uuid())",
                "INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) SELECT '"
                        + org + "', '" + m2 + "', role_id, 1, 1, '" + m1 + "' FROM roles WHERE code = 'ORG_ADMIN'");
        Path fx = TMP.resolve("fixture.json");
        Files.writeString(fx, "{\"org\": \"" + org + "\", \"titular\": {\"email\": \"" + e1 + "\", \"senha\": \"" + s1 + "\"}, \"outro\": {\"email\": \""
                + e2 + "\", \"senha\": \"" + s2 + "\"}}", StandardCharsets.UTF_8);
        Files.writeString(TMP.resolve("segredos_vistos.txt"), s1 + "\n" + s2 + "\n", StandardCharsets.UTF_8);

        // h016_mfa: login e cadastro do MFA dos dois ORG_ADMIN
        Saida mfa = cliente("h016_mfa", fx.toString());
        assertThat(mfa.codigo()).as(mfa.texto()).isZero();
        assertThat(mfa.texto()).contains("OK    H016.mfa titular com fator confirmado").contains("OK    H016.mfa outro com fator confirmado");
        UUID fator = DB.valor(UUID.class, "SELECT factor_id FROM user_mfa_factors WHERE user_id = ? AND confirmed_at IS NOT NULL", adm1);
        assertThat(fator).as("fator real confirmado pela API").isNotNull();

        // solicitacao pre-aprovada pelos caminhos reais; avisos pelo despachante real com o transporte de homologacao
        OpsBanco.Org o = new OpsBanco.Org(org, adm1, m1, fator);
        UUID c1 = DB.contatoElegivel(o, adm1);
        UUID c2 = DB.contatoElegivel(o, null);
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = new Cenarios.Alvo(o, adm1, fator, c1, c2);
        UUID s = C.preAprovada(eq, al);
        assertThat(notices.dispatchPending()).isEqualTo(2);
        UUID aviso = DB.valor(UUID.class, "SELECT notice_id FROM ops.ops_mfa_notices WHERE request_id = ? AND kind = 1 AND state = 4", s);
        assertThat(DB.valor(String.class, "SELECT string_agg(DISTINCT category, ',') FROM ops.ops_mfa_notices WHERE request_id = ?", s))
                .isEqualTo("ACEITE_SIMULADO");
        String json = Files.readString(TMP.resolve("caixa/avisos/" + aviso + ".json"), StandardCharsets.UTF_8);
        String token = json.replaceAll("(?s).*\"token_confirmar\":\"([^\"]+)\".*", "$1");
        Path arqToken = TMP.resolve("token_confirmar");
        Files.writeString(arqToken, token, StandardCharsets.UTF_8);

        // h016_confirmar: POST real com CSRF de pre-autenticacao e Origin -> 204; o mesmo token de novo -> recusado (400)
        Saida conf = cliente("h016_confirmar", fx.toString(), arqToken.toString(), "204");
        assertThat(conf.codigo()).as(conf.texto()).isZero();
        assertThat(conf.texto()).contains("OK    H016.confirmar pela rota");
        Saida rep = cliente("h016_confirmar", fx.toString(), arqToken.toString(), "204");
        assertThat(rep.codigo()).as("token de uso unico: a repeticao nao e aceita").isEqualTo(1);
        assertThat(rep.texto()).contains("FALHA H016.confirmar pela rota esperado=204");
        assertThat(eq.a().aprovar(s).motivo()).as("aprovacao imediata").isEqualTo("ESPERA_NAO_CUMPRIDA");

        // espera de 24 h pela fixture temporal da SUITE; aprovacao e execucao pela CLI; h016_novo_mfa pelo cliente
        DB.recuarSolicitacao(s, Cenarios.ESPERA);
        Cenarios.exigirOk(eq.a().aprovar(s), "aprovar");
        ExecutorAtos.Resultado ex = eq.e().executar(s);
        Cenarios.exigirOk(ex, "executar");
        Saida novo = cliente("h016_novo_mfa", fx.toString());
        assertThat(novo.codigo()).as(novo.texto()).isZero();
        assertThat(novo.texto()).contains("OK    H016.novo_mfa login exige cadastro").contains("OK    H016.novo_mfa cadastro e sessao");

        // nenhum valor secreto visto pelo cliente (senhas, token, cookies, CSRF, segredos TOTP) na saida registrada
        List<String> segredos = Files.readAllLines(TMP.resolve("segredos_vistos.txt")).stream().filter(v -> v.length() >= 8).toList();
        assertThat(segredos).contains(token);
        for (String v : segredos) {
            for (String e : EVIDENCIA) {
                assertThat(e).doesNotContain(v);
            }
        }
        evidencia("segredos vistos pelo cliente: " + segredos.size() + " valores, nenhum na saida registrada");
    }
}
