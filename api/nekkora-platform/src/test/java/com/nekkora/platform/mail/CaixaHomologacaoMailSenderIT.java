package com.nekkora.platform.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;
import com.nekkora.platform.PlatformApplication;
import com.nekkora.platform.PlatformTestBase;
import com.nekkora.platform.emergency.EmergencyNotices;

/**
 * P1c (desenho P1a/P1b v2.2, sec. 4 e 9): transporte de HOMOLOGACAO com o DESPACHANTE REAL. A Platform sobe com
 * {@code nekkora.mail.transport=homologacao} (bean escolhido pelo PlatformConfig, sem substituto de teste), numa base
 * 001-016 EXCLUSIVA desta classe (nenhuma outra classe deixa tentativas pendentes nela), e as tentativas saem por
 * {@link EmergencyNotices#dispatchPending()} -> {@code ops_notice_delivery_result}: estados e categorias SIMULADAS
 * gravados pelo caminho normal.
 *
 * <p>Caixa: diretorio temporario do host (sistema de arquivos com ligacoes fisicas). Dono e permissoes no Linux sao
 * conferidos a parte, no volume Docker (evidencia do P1c). Nenhum token, e-mail ou conteudo da caixa e impresso.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = PlatformApplication.class)
@ExtendWith(OutputCaptureExtension.class)
class CaixaHomologacaoMailSenderIT {

    static final OpsBanco DB = OpsBanco.nova(OpsBanco.Forma.padrao());
    static final Cenarios C = new Cenarios(DB);
    static final String AMBIENTE = "ensaio-p1c-caixa";
    static final Path CAIXA = caixaTemporaria();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    MailSender mail;

    @Autowired
    EmergencyNotices notices;

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("nekkora.database.url", DB::urlTls);
        r.add("nekkora.database.user", () -> "nekkora_platform");
        r.add("nekkora.database.password", () -> DB.senha("nekkora_platform"));
        r.add("spring.data.redis.host", () -> RealRedis.shared().host());
        r.add("spring.data.redis.port", () -> RealRedis.shared().port());
        r.add("nekkora.public-origin", () -> PlatformTestBase.ORIGIN);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.keys.aes-current", () -> "k1");
        r.add("nekkora.keys.aes-by-id", () -> "k1:" + b64(32));
        r.add("nekkora.keys.hmac-id", () -> "h1");
        r.add("nekkora.keys.hmac", () -> b64(32));
        // transporte de homologacao, como no compose.caixa.yml (o agendador continua desligado: entrega so quando o teste pede)
        r.add("nekkora.mail.transport", () -> "homologacao");
        r.add("nekkora.environment", () -> AMBIENTE);
        r.add("nekkora.mail.caixa-dir", CAIXA::toString);
    }

    private static String b64(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    private static Path caixaTemporaria() {
        try {
            return Files.createTempDirectory("nekkora-caixa-p1c-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void encerrar() throws IOException {
        DB.encerrar();
        try (Stream<Path> s = Files.walk(CAIXA)) {
            s.sorted(Collections.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @BeforeEach
    void semRegrasNemTrava() throws IOException {
        Files.deleteIfExists(CAIXA.resolve("controle.conf"));
        if (Files.isDirectory(CAIXA.resolve("controle.trava"))) {
            Files.delete(CAIXA.resolve("controle.trava"));
        }
    }

    // ------------------------------------------------------------------ apoio

    /** Uma solicitacao pre-aprovada sem outros admins: exatamente uma tentativa pendente (a do titular). */
    private static UUID tentativaPendente() {
        UUID s = C.preAprovada(C.equipe(), C.alvo());
        return DB.valor(UUID.class, "SELECT notice_id FROM ops.ops_mfa_notices WHERE request_id = ? AND state = 0", s);
    }

    private static String estadoECategoria(UUID aviso) {
        return DB.valor(String.class, "SELECT state || ':' || coalesce(category, '-') FROM ops.ops_mfa_notices WHERE notice_id = ?", aviso);
    }

    /** Registro do resultado na tabela de resultados (caminho de ops_notice_delivery_result): estado e categoria. */
    private static List<String> resultados(UUID aviso) {
        return DB.linhas("SELECT reported_state || ':' || coalesce(category, '-') || ':' || outcome FROM ops.ops_mfa_notice_results "
                        + "WHERE notice_id = ? ORDER BY 1", aviso)
                .stream().map(l -> (String) l.get(0)).toList();
    }

    private static void regra(String linha) throws IOException {
        Files.writeString(CAIXA.resolve("controle.conf"), linha + "\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private static List<String> indice() throws IOException {
        Path i = CAIXA.resolve("indice.jsonl");
        return Files.exists(i) ? Files.readAllLines(i, StandardCharsets.UTF_8) : List.of();
    }

    private static String ler(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CaixaHomologacaoMailSender transporte() {
        return new CaixaHomologacaoMailSender(CAIXA, AMBIENTE);
    }

    // ------------------------------------------------------------------ desfechos pelo despachante real

    @Test
    void beanDaPlatformEOTransporteDeHomologacaoESimulado() {
        assertThat(mail).isInstanceOf(CaixaHomologacaoMailSender.class);
        assertThat(mail.simulated()).isTrue();
        assertThat(new LocalMailSender().simulated()).as("LocalMailSender.simulated()").isFalse();
    }

    /** Os cinco desfechos simulados, cada um numa tentativa real, pelo despachante real: estado e categoria gravados. */
    @Test
    void cadaDesfechoSimuladoGravaEstadoECategoriaPeloCaminhoNormal(CapturedOutput saida) throws IOException {
        String[][] casos = {
            {"ACEITO", "4:ACEITE_SIMULADO"},
            {"ENFILEIRADO", "3:FILA_SIMULADA"},
            {"RECUSADO", "5:RECUSA_SIMULADA"},
            {"INCERTO", "6:INCERTO_SIMULADO"},
            {"EXCECAO", "6:INCERTO_SIMULADO"},
        };
        for (String[] caso : casos) {
            UUID aviso = tentativaPendente();
            regra("proximo " + caso[0]);
            assertThat(notices.dispatchPending()).as("desfecho %s: uma entrega a porta", caso[0]).isEqualTo(1);
            assertThat(estadoECategoria(aviso)).as("desfecho %s", caso[0]).isEqualTo(caso[1]);
            // historico: a reivindicacao (ENTREGANDO, sem categoria) e o desfecho do transporte, ambos aplicados
            assertThat(resultados(aviso)).as("resultados registrados (%s)", caso[0]).containsExactly("2:-:APLICADO", caso[1] + ":APLICADO");
            Path arq = CAIXA.resolve("avisos/" + aviso + ".json");
            assertThat(arq).as("mensagem publicada com o attempt_id (%s)", caso[0]).isRegularFile();
            String json = ler(arq);
            assertThat(json).startsWith("{\"simulado\":true,\"ambiente\":\"" + AMBIENTE + "\"").contains("\"attempt_id\":\"" + aviso + "\"")
                    .contains("\"token_confirmar\":\"").contains("\"token_contestar\":\"").endsWith("}\n");
            assertThat(indice()).anySatisfy(l -> assertThat(l).contains("\"arquivo\":\"avisos/" + aviso + ".json\"")
                    .contains("\"desfecho_simulado\":\"" + caso[0] + "\"").contains("\"repeticao\":false").contains("\"simulado\":true"));
            assertThat(Files.readAllLines(CAIXA.resolve("controle.conf"))).as("regra proximo consumida (%s)", caso[0]).isEmpty();
            // nenhuma nova entrega da mesma tentativa
            assertThat(notices.dispatchPending()).isZero();
        }
        assertThat(saida.getOut()).contains("evento=aviso_mfa_emergencia.entregue_a_porta").contains("simulado=sim")
                .doesNotContain("simulado=nao");
    }

    /** Sem regra: ACEITO; regra por destinatario (sha256 do e-mail normalizado) vale enquanto existir, sem consumo. */
    @Test
    void semRegraAceitaERegraPorDestinatarioNaoEConsumida() throws IOException {
        UUID a1 = tentativaPendente();
        assertThat(notices.dispatchPending()).isEqualTo(1);
        assertThat(estadoECategoria(a1)).isEqualTo("4:ACEITE_SIMULADO");

        UUID a2 = tentativaPendente();
        String email = DB.valor(String.class, "SELECT u.email FROM ops.ops_mfa_notices n JOIN users u ON u.user_id = n.recipient_user_id "
                + "WHERE n.notice_id = ?", a2);
        regra("destinatario " + CaixaHomologacaoMailSender.sha256(email) + " RECUSADO");
        assertThat(notices.dispatchPending()).isEqualTo(1);
        assertThat(estadoECategoria(a2)).isEqualTo("5:RECUSA_SIMULADA");
        assertThat(Files.readAllLines(CAIXA.resolve("controle.conf"))).hasSize(1).allSatisfy(l -> assertThat(l).startsWith("destinatario "));
    }

    // ------------------------------------------------------------------ publicacao: colisao, repeticao, sem attempt_id

    /** Nome final pre-existente (colisao): o existente NAO e tocado; a entrega vai para repeticao-1, com alerta. */
    @Test
    void colisaoDeNomeFinalNaoSobrescreve(CapturedOutput saida) throws IOException {
        UUID aviso = tentativaPendente();
        Path existente = CAIXA.resolve("avisos/" + aviso + ".json");
        String sentinela = "{\"sentinela\":\"" + UUID.randomUUID() + "\"}\n";
        Files.writeString(existente, sentinela, StandardCharsets.UTF_8);
        assertThat(notices.dispatchPending()).isEqualTo(1);
        assertThat(ler(existente)).as("arquivo pre-existente intacto").isEqualTo(sentinela);
        Path rep = CAIXA.resolve("avisos/" + aviso + ".repeticao-1.json");
        assertThat(rep).isRegularFile();
        assertThat(ler(rep)).contains("\"attempt_id\":\"" + aviso + "\"").endsWith("}\n");
        assertThat(indice()).anySatisfy(l -> assertThat(l).contains("avisos/" + aviso + ".repeticao-1.json").contains("\"repeticao\":true"));
        assertThat(estadoECategoria(aviso)).isEqualTo("4:ACEITE_SIMULADO");
        assertThat(saida.getOut()).contains("evento=mail.caixa.repeticao").contains("alerta=sim");
    }

    /** A mesma tentativa entregue de novo (proibido pelo protocolo): repeticao-1, repeticao-2, nunca sobrescrita. */
    @Test
    void repeticaoPorAttemptIdGeraNomesNovos() {
        CaixaHomologacaoMailSender t = transporte();
        UUID tentativa = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertThat(t.sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN, org, "rep-" + i + "@p1c.test", null, "tk-" + i, tentativa))
                    .isEqualTo(MailSender.Outcome.ACEITO_PELO_TRANSPORTE);
        }
        assertThat(ler(CAIXA.resolve("avisos/" + tentativa + ".json"))).contains("rep-0@p1c.test");
        assertThat(ler(CAIXA.resolve("avisos/" + tentativa + ".repeticao-1.json"))).contains("rep-1@p1c.test");
        assertThat(ler(CAIXA.resolve("avisos/" + tentativa + ".repeticao-2.json"))).contains("rep-2@p1c.test");
    }

    /**
     * Mensagens sem attempt_id (outras/), N threads em paralelo, e um leitor continuo: nomes distintos, nenhuma
     * sobrescrita, todo arquivo final visto pelo leitor ja esta completo (termina em "}\n" e tem o marcador do fim), e
     * nenhum temporario sobra em .tmp. Conteudo grande (256 KiB por mensagem) para exercitar a gravacao ate esgotar o buffer.
     */
    @Test
    void enviosConcorrentesSemAttemptIdNaoSobrescrevemNemExpoemArquivoParcial() throws Exception {
        CaixaHomologacaoMailSender t = transporte();
        int threads = 16;
        int porThread = 12;
        String grande = "x".repeat(256 * 1024);
        Set<Path> antes = new HashSet<>(listar("outras"));
        AtomicBoolean parar = new AtomicBoolean();
        AtomicInteger vistos = new AtomicInteger();
        List<String> parciais = Collections.synchronizedList(new ArrayList<>());
        Thread leitor = new Thread(() -> {
            while (!parar.get()) {
                for (Path p : listar("outras")) {
                    if (antes.contains(p)) {
                        continue;
                    }
                    String c = ler(p);
                    vistos.incrementAndGet();
                    // completo = o token grande inteiro seguido do marcador do fim e do fechamento do JSON
                    int fim = c.lastIndexOf("fim-");
                    if (!c.endsWith("\"}\n") || fim < grande.length() || !c.startsWith("x".repeat(64), fim - 64)) {
                        parciais.add(p.getFileName().toString());
                    }
                }
            }
        });
        leitor.start();
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int id = i;
            fs.add(ex.submit(() -> {
                largada.await();
                for (int j = 0; j < porThread; j++) {
                    t.send(MailSender.Kind.PASSWORD_RECOVERY, UUID.randomUUID(), "c" + id + "-" + j + "@p1c.test", grande + "fim-" + id + "-" + j);
                }
                return null;
            }));
        }
        largada.countDown();
        for (Future<?> f : fs) {
            f.get(2, TimeUnit.MINUTES);
        }
        ex.shutdown();
        parar.set(true);
        leitor.join();
        List<Path> novos = listar("outras").stream().filter(p -> !antes.contains(p)).toList();
        assertThat(novos).as("uma mensagem por chamada, nomes distintos").hasSize(threads * porThread);
        Set<String> destinatarios = ConcurrentHashMap.newKeySet();
        for (Path p : novos) {
            String c = ler(p);
            assertThat(c).endsWith("}\n").contains("\"token\":\"" + grande + "fim-");
            destinatarios.add(c.replaceAll("(?s).*\"destinatario\":\"([^\"]+)\".*", "$1"));
        }
        assertThat(destinatarios).as("nenhuma mensagem sobrescrita por outra").hasSize(threads * porThread);
        assertThat(parciais).as("arquivo final parcial visto pelo leitor").isEmpty();
        assertThat(vistos.get()).as("o leitor observou arquivos durante os envios").isPositive();
        assertThat(listar(".tmp")).as("temporarios removidos").isEmpty();
        long linhasOutras = indice().stream().filter(l -> l.contains("\"arquivo\":\"outras/")).count();
        assertThat(linhasOutras).as("uma linha de indice por mensagem, sem intercalacao").isGreaterThanOrEqualTo(threads * porThread);
        assertThat(indice()).allSatisfy(l -> assertThat(l).startsWith("{\"arquivo\":\"").endsWith("\"simulado\":true}"));
    }

    private static List<Path> listar(String sub) {
        try (Stream<Path> s = Files.list(CAIXA.resolve(sub))) {
            return s.filter(p -> !Files.isDirectory(p)).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ controle: consumo unico e trava

    /** N entregas concorrentes (threads da mesma JVM) com UMA regra proximo: exatamente uma a consome. */
    @Test
    void umaRegraProximoEConsumidaPorExatamenteUmaEntregaConcorrente() throws Exception {
        CaixaHomologacaoMailSender t = transporte();
        regra("proximo RECUSADO");
        int n = 24;
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<MailSender.Outcome>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int id = i;
            fs.add(ex.submit(() -> {
                largada.await();
                return t.sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN, UUID.randomUUID(), "conc-" + id + "@p1c.test", null, "tk",
                        UUID.randomUUID());
            }));
        }
        largada.countDown();
        List<MailSender.Outcome> r = new ArrayList<>();
        for (Future<MailSender.Outcome> f : fs) {
            r.add(f.get(1, TimeUnit.MINUTES));
        }
        ex.shutdown();
        assertThat(r.stream().filter(o -> o == MailSender.Outcome.RECUSADO_DEFINITIVO).count()).as("regra consumida uma vez").isEqualTo(1);
        assertThat(r.stream().filter(o -> o == MailSender.Outcome.ACEITO_PELO_TRANSPORTE).count()).isEqualTo(n - 1);
        assertThat(Files.readAllLines(CAIXA.resolve("controle.conf"))).isEmpty();
        assertThat(CAIXA.resolve("controle.trava")).doesNotExist();
    }

    /**
     * Dois transportes independentes (como duas instancias) e um escritor EXTERNO do controle com o protocolo da
     * ferramenta (mkdir da trava, anexa, rmdir), em paralelo: cada regra proximo gravada e consumida exatamente uma vez.
     */
    @Test
    void escritorExternoETransportesConcorrentesNaoPerdemNemDuplicamRegras() throws Exception {
        CaixaHomologacaoMailSender t1 = transporte();
        CaixaHomologacaoMailSender t2 = transporte();
        int regras = 20;
        AtomicInteger gravadas = new AtomicInteger();
        Thread escritor = new Thread(() -> {
            Path trava = CAIXA.resolve("controle.trava");
            while (gravadas.get() < regras) {
                try {
                    Files.createDirectory(trava);   // o "mkdir" da ferramenta: falha se ocupada
                } catch (java.nio.file.FileAlreadyExistsException ocupada) {
                    Thread.onSpinWait();
                    continue;
                } catch (java.nio.file.AccessDeniedException pendente) {
                    // mesma regra do transporte (WIN-TRANSPORT-1): no Windows, criar a trava enquanto a remocao dela por
                    // outro ator esta pendente devolve acesso negado; so para o caminho exato da trava e so no Windows
                    if (!CaixaHomologacaoMailSender.WINDOWS || pendente.getFile() == null || !Path.of(pendente.getFile()).equals(trava)) {
                        throw new IllegalStateException(pendente);
                    }
                    Thread.onSpinWait();
                    continue;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                try {
                    Files.writeString(CAIXA.resolve("controle.conf"), "proximo ENFILEIRADO\n", StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    gravadas.incrementAndGet();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                } finally {
                    try {
                        Files.delete(trava);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        });
        int envios = 60;
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<MailSender.Outcome>> fs = new ArrayList<>();
        escritor.start();
        for (int i = 0; i < envios; i++) {
            CaixaHomologacaoMailSender t = i % 2 == 0 ? t1 : t2;
            int id = i;
            fs.add(ex.submit(() -> t.sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN, UUID.randomUUID(), "ext-" + id + "@p1c.test",
                    null, "tk", UUID.randomUUID())));
        }
        long enfileirados = 0;
        for (Future<MailSender.Outcome> f : fs) {
            if (f.get(2, TimeUnit.MINUTES) == MailSender.Outcome.ENFILEIRADO) {
                enfileirados++;
            }
        }
        escritor.join(TimeUnit.MINUTES.toMillis(2));
        ex.shutdown();
        long restantes = Files.readAllLines(CAIXA.resolve("controle.conf")).stream().filter(l -> l.equals("proximo ENFILEIRADO")).count();
        assertThat(gravadas.get()).isEqualTo(regras);
        assertThat(enfileirados + restantes).as("cada regra gravada foi consumida uma vez ou ainda esta no arquivo").isEqualTo(regras);
        // consome o que sobrou: nenhuma regra perdida
        for (long i = 0; i < restantes; i++) {
            assertThat(t1.sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN, UUID.randomUUID(), "r@p1c.test", null, "tk", UUID.randomUUID()))
                    .isEqualTo(MailSender.Outcome.ENFILEIRADO);
        }
        assertThat(Files.readAllLines(CAIXA.resolve("controle.conf"))).isEmpty();
    }

    /**
     * Trava ocupada por fora (ferramenta em uso ou orfa de uma queda): a entrega pelo despachante real falha e fica
     * DESCONHECIDO/INCERTO_SIMULADO, sem mensagem publicada e sem consumir a regra. A trava NAO expira com o tempo: uma
     * segunda tentativa, depois da espera inteira, falha igual. So a liberacao EXPLICITA (a remocao que
     * "caixa liberar-trava" faz) devolve o funcionamento; a regra continua la e e consumida depois.
     */
    @Test
    void travaOcupadaPorForaFazAEntregaFalharAteALiberacaoExplicita() throws IOException {
        regra("proximo RECUSADO");
        Path trava = CAIXA.resolve("controle.trava");
        Files.createDirectory(trava);
        UUID a1 = tentativaPendente();
        long t0 = System.nanoTime();
        assertThat(notices.dispatchPending()).isEqualTo(1);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertThat(ms).as("esperou a trava (5 s) antes de desistir").isGreaterThanOrEqualTo(5_000);
        assertThat(estadoECategoria(a1)).isEqualTo("6:INCERTO_SIMULADO");
        assertThat(CAIXA.resolve("avisos/" + a1 + ".json")).doesNotExist();

        // sem expiracao: continua ocupada e a proxima tentativa falha igual
        UUID a2 = tentativaPendente();
        assertThat(notices.dispatchPending()).isEqualTo(1);
        assertThat(estadoECategoria(a2)).isEqualTo("6:INCERTO_SIMULADO");
        assertThat(trava).as("a trava nao foi removida pelo transporte").isDirectory();
        assertThat(Files.readAllLines(CAIXA.resolve("controle.conf"))).containsExactly("proximo RECUSADO");

        // liberacao explicita (a mesma operacao de "caixa liberar-trava": rmdir) -> volta a funcionar e consome a regra
        Files.delete(trava);
        UUID a3 = tentativaPendente();
        assertThat(notices.dispatchPending()).isEqualTo(1);
        assertThat(estadoECategoria(a3)).isEqualTo("5:RECUSA_SIMULADA");
        assertThat(Files.readAllLines(CAIXA.resolve("controle.conf"))).isEmpty();
    }

    /** Regra fora da lista fechada: a entrega falha (INCERTO_SIMULADO), nunca vira ACEITO silencioso. */
    @Test
    void regraInvalidaNaoViraAceite() throws IOException {
        regra("proximo ENTREGUE");
        UUID aviso = tentativaPendente();
        assertThat(notices.dispatchPending()).isEqualTo(1);
        assertThat(estadoECategoria(aviso)).isEqualTo("6:INCERTO_SIMULADO");
        assertThatThrownBy(() -> transporte().sendEmergencyNotice(MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN, UUID.randomUUID(), "x@p1c.test", null,
                "tk", UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ indice

    /**
     * O indice produzido por esta classe passa pela sanitizacao REAL (lib/indice_caixa.py) e nao contem nenhum token
     * nem endereco das mensagens; uma linha fora do formato recusa o indice inteiro (codigo 1, saida vazia).
     */
    @Test
    void indiceAceitoPelaSanitizacaoSemTokenNemEnderecoELinhaForaDoFormatoRecusada() throws Exception {
        UUID aviso = tentativaPendente();
        assertThat(notices.dispatchPending()).isEqualTo(1);
        transporte().send(MailSender.Kind.PASSWORD_RECOVERY, UUID.randomUUID(), "indice@p1c.test", "token-da-redefinicao-" + UUID.randomUUID());
        String json = ler(CAIXA.resolve("avisos/" + aviso + ".json"));
        List<String> segredos = new ArrayList<>();
        for (String campo : List.of("token_confirmar", "token_contestar", "destinatario")) {
            segredos.add(json.replaceAll("(?s).*\"" + campo + "\":\"([^\"]+)\".*", "$1"));
        }
        assertThat(segredos).allSatisfy(s -> assertThat(s).hasSizeGreaterThan(8));
        String bruto = String.join("\n", indice()) + "\n";
        Resultado r = sanitizar(bruto);
        assertThat(r.codigo()).as("sanitizacao: %s", r.erro()).isZero();
        assertThat(r.saida().lines().count()).isEqualTo(indice().size());
        for (String s : segredos) {
            assertThat(bruto).doesNotContain(s);
            assertThat(r.saida()).doesNotContain(s);
        }
        assertThat(bruto).doesNotContain("indice@p1c.test").doesNotContain("token-da-redefinicao-");

        Resultado recusado = sanitizar(bruto + "{\"arquivo\":\"avisos/" + UUID.randomUUID() + ".json\",\"attempt_id\":\"-\",\"tipo\":\"PASSWORD_RECOVERY\","
                + "\"destinatario_sha256\":\"" + "a".repeat(64) + "\",\"desfecho_simulado\":\"-\",\"repeticao\":false,\"simulado\":true,"
                + "\"token\":\"vazado\"}\n");
        assertThat(recusado.codigo()).isEqualTo(1);
        assertThat(recusado.saida()).isEmpty();
        assertThat(recusado.erro()).contains("campos inesperados");
    }

    record Resultado(int codigo, String saida, String erro) {
    }

    static Resultado sanitizar(String entrada) throws Exception {
        Path script = OpsBanco.raizDoRepositorio().resolve("ops/homologacao/lib/indice_caixa.py");
        Process p = new ProcessBuilder("python", script.toString()).start();
        p.getOutputStream().write(entrada.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(1, TimeUnit.MINUTES)).isTrue();
        return new Resultado(p.exitValue(), out, err);
    }
}
