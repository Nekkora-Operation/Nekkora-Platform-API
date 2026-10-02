package com.nekkora.platform.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.ops.testsupport.OpsBanco;
import com.nekkora.platform.PlatformApplication;
import com.nekkora.platform.PlatformTestBase;

/**
 * P1c (desenho P1a/P1b v2.2, sec. 4.1): matriz de configuracao do transporte com a Platform REAL subindo (ou recusando
 * subir). As propriedades entram pelas MESMAS variaveis que o compose.caixa.yml usa (NEKKORA_MAIL_TRANSPORTE,
 * NEKKORA_AMBIENTE, NEKKORA_MAIL_CAIXA, NEKKORA_AVISOS_EMERGENCIA), resolvidas pelo application.yml.
 */
@ExtendWith(OutputCaptureExtension.class)
class CaixaHomologacaoConfigIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static String b64(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    /** Propriedades minimas da Platform (banco, Redis, chaves efemeras) e as variaveis do caso, como argumentos. */
    private static ConfigurableApplicationContext subir(String... caso) {
        List<String> a = new ArrayList<>(List.of(
                "--server.port=0",
                "--nekkora.database.url=" + DB.urlTls(),
                "--nekkora.database.user=nekkora_platform",
                "--nekkora.database.password=" + DB.senha("nekkora_platform"),
                "--spring.data.redis.host=" + RealRedis.shared().host(),
                "--spring.data.redis.port=" + RealRedis.shared().port(),
                "--nekkora.public-origin=" + PlatformTestBase.ORIGIN,
                "--nekkora.trusted-proxies=127.0.0.1/32,::1/128",
                "--nekkora.keys.aes-current=k1",
                "--nekkora.keys.aes-by-id=k1:" + b64(32),
                "--nekkora.keys.hmac-id=h1",
                "--nekkora.keys.hmac=" + b64(32)));
        for (String c : caso) {
            a.add("--" + c);
        }
        return new SpringApplicationBuilder(PlatformApplication.class).run(a.toArray(String[]::new));
    }

    /** A Platform NAO sobe: devolve a causa raiz (a mensagem nao carrega segredo). */
    private static Throwable naoSobe(String... caso) {
        Throwable t = catchThrowable(() -> {
            try (ConfigurableApplicationContext c = subir(caso)) {
                // subiu: o caso esperava recusa
            }
        });
        assertThat(t).as("a Platform subiu com %s", List.of(caso)).isNotNull();
        Throwable raiz = t;
        while (raiz.getCause() != null) {
            raiz = raiz.getCause();
        }
        return raiz;
    }

    private static Path caixa() throws IOException {
        return Files.createTempDirectory("nekkora-caixa-cfg-");
    }

    @Test
    void padraoSemPropriedadesUsaOTransporteLocalSemAgendador() {
        try (ConfigurableApplicationContext c = subir()) {
            MailSender m = c.getBean(MailSender.class);
            assertThat(m).isInstanceOf(LocalMailSender.class);
            assertThat(m.simulated()).isFalse();
            assertThat(c.getBeanNamesForType(ScheduledExecutorService.class)).as("agendador dos avisos desligado por padrao").isEmpty();
        }
    }

    @Test
    void localExplicitoContinuaLocalMesmoComCaixaEAmbiente() throws IOException {
        try (ConfigurableApplicationContext c = subir("NEKKORA_MAIL_TRANSPORTE=local", "NEKKORA_AMBIENTE=ensaio-p1c-cfg",
                "NEKKORA_MAIL_CAIXA=" + caixa())) {
            assertThat(c.getBean(MailSender.class)).isInstanceOf(LocalMailSender.class);
        }
    }

    @Test
    void configuracaoValidaHabilitaOTransporteDeHomologacao(CapturedOutput saida) throws IOException {
        Path cx = caixa();
        try (ConfigurableApplicationContext c = subir("NEKKORA_MAIL_TRANSPORTE=homologacao", "NEKKORA_AMBIENTE=ensaio-p1c-cfg",
                "NEKKORA_MAIL_CAIXA=" + cx, "NEKKORA_AVISOS_EMERGENCIA=true")) {
            MailSender m = c.getBean(MailSender.class);
            assertThat(m).isInstanceOf(CaixaHomologacaoMailSender.class);
            assertThat(m.simulated()).isTrue();
            assertThat(c.getBeanNamesForType(ScheduledExecutorService.class)).as("agendador ligado como no compose.caixa.yml").hasSize(1);
            assertThat(cx.resolve("avisos")).isDirectory();
            assertThat(cx.resolve("outras")).isDirectory();
        }
        assertThat(saida.getOut()).contains("evento=mail.transporte transporte=homologacao ambiente=ensaio-p1c-cfg simulado=sim envio_externo=nenhum");
    }

    @Test
    void homologacaoSemAmbienteOuForaDeEnsaioNaoSobe() throws IOException {
        String cx = caixa().toString();
        for (String amb : new String[] {"", "hml03", "producao", "ensaio-", "ensaio-ABC", "ensaio-" + "a".repeat(35), "x-ensaio-p1c"}) {
            Throwable t = naoSobe("NEKKORA_MAIL_TRANSPORTE=homologacao", "NEKKORA_AMBIENTE=" + amb, "NEKKORA_MAIL_CAIXA=" + cx);
            assertThat(t).as("ambiente '%s'", amb).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exige nekkora.environment=ensaio-*");
        }
    }

    @Test
    void homologacaoSemCaixaGravavelNaoSobe() throws IOException {
        Throwable vazio = naoSobe("NEKKORA_MAIL_TRANSPORTE=homologacao", "NEKKORA_AMBIENTE=ensaio-p1c-cfg");
        assertThat(vazio).isInstanceOf(IllegalStateException.class).hasMessageContaining("exige nekkora.mail.caixa-dir");
        Path inexistente = caixa().resolve("nao-existe");
        assertThat(naoSobe("NEKKORA_MAIL_TRANSPORTE=homologacao", "NEKKORA_AMBIENTE=ensaio-p1c-cfg", "NEKKORA_MAIL_CAIXA=" + inexistente))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("caixa do transporte de homologacao ausente ou sem escrita");
        Path arquivo = Files.createTempFile("nekkora-caixa-cfg-", ".txt");
        assertThat(naoSobe("NEKKORA_MAIL_TRANSPORTE=homologacao", "NEKKORA_AMBIENTE=ensaio-p1c-cfg", "NEKKORA_MAIL_CAIXA=" + arquivo))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("caixa do transporte de homologacao ausente ou sem escrita");
    }

    @Test
    void transporteDesconhecidoNaoSobeNemCaiNoLocal() throws IOException {
        String cx = caixa().toString();
        for (String t : new String[] {"smtp", "Homologacao", "homologacao2", "real"}) {
            assertThat(naoSobe("NEKKORA_MAIL_TRANSPORTE=" + t, "NEKKORA_AMBIENTE=ensaio-p1c-cfg", "NEKKORA_MAIL_CAIXA=" + cx))
                    .as("transporte '%s'", t).isInstanceOf(IllegalStateException.class).hasMessageContaining("nekkora.mail.transport desconhecido");
        }
    }
}
