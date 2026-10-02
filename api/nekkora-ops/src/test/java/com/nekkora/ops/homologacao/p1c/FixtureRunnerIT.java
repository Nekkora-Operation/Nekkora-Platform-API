package com.nekkora.ops.homologacao.p1c;


import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * P1c (desenho P1a/P1b v2.2, sec. 5.1): a acao {@code homologacao.sh fixture} REAL (bash do runner, sem alteracao) sobre
 * a base descartavel: registro PREVENTIVO da solicitacao como impropria para B9 antes de tentar, classificacao pela
 * marca e pelo codigo do psql com resultados OBSERVADOS (aplicada, recusada, conexao impossivel, falha depois do COMMIT),
 * mensagens do runner (falha depois do COMMIT nunca e "nada foi alterado") e a reconciliacao {@code conferir}, inclusive
 * com o banco indisponivel (nada concluido).
 *
 * <p>Unica substituicao: um shim de {@code docker} no PATH do runner redireciona {@code docker compose run ... ferramentas
 * psql ...} para o container cliente do teste (mesma conexao, mesmos {@code /sql} e {@code /tls}); o resto vai ao docker
 * real. O ambiente e so um id {@code ensaio-p1c-rn-*} com {@code saidas/<id>/ambiente.env} (sem compose, sem segredos no
 * repositorio): nao e o ensaio-016a. Segredos efemeros num diretorio temporario FORA do repositorio, apagado no fim.
 */
class FixtureRunnerIT {

    static final String PREFIXO = "10.252.42";
    static final String ID = "ensaio-p1c-rn-" + OpsBanco.hex(3);
    static final Path SAIDAS = Processos.HOMOLOGACAO.resolve("saidas").resolve(ID);
    static final Path FX = SAIDAS.resolve("fixtures.txt");
    static final Path SOL = SAIDAS.resolve("solicitacoes_com_fixture.txt");
    static BancoHomologacao HB;
    static OpsBanco DB;
    static Cenarios C;
    static Path segredos;
    static Path shimDir;

    @BeforeAll
    static void subir() throws IOException {
        HB = BancoHomologacao.subir(PREFIXO, OpsBanco.HBA_PADRAO, "runner",
                Map.of("nekkora.homologacao", ID, "com.docker.compose.project", "nekkora-homologacao-" + ID));
        DB = HB.db;
        C = new Cenarios(DB);
        segredos = Files.createTempDirectory("p1c-segredos-");
        Files.createDirectories(segredos.resolve("teste016"));
        Files.writeString(segredos.resolve("postgres_admin"), DB.senhaSuperusuario(), StandardCharsets.UTF_8);
        Files.createDirectories(SAIDAS);
        String s = mixed(segredos);
        Files.writeString(SAIDAS.resolve("ambiente.env"), String.join("\n",
                "# ambiente de TESTE do P1c (FixtureRunnerIT): so para a acao fixture do runner; sem compose, sem segredos aqui",
                "HOMOLOG_ID=" + ID, "HOMOLOG_PROJETO=nekkora-homologacao-" + ID, "HOMOLOG_HOST=homologacao.nekkora.test", "HOMOLOG_PORTA=0",
                "HOMOLOG_BIND=127.0.0.1", "HOMOLOG_COMMIT=p1c-teste", "HOMOLOG_SEGREDOS=" + s, "HOMOLOG_SEGREDOS_HOST=" + s,
                "HOMOLOG_MIGRACOES=-", "HOMOLOG_SQL=-", "HOMOLOG_ATE=016", "HOMOLOG_REDE_INTERNA=" + PREFIXO, "HOMOLOG_REDE_BORDA=10.252.43",
                "HOMOLOG_TRANSPORTE=", "HOMOLOG_CONFIG=", "HOMOLOG_COMPOSE_EXTRA=", ""), StandardCharsets.UTF_8);
        shimDir = Files.createTempDirectory("p1c-shim-");
        try (InputStream in = FixtureRunnerIT.class.getResourceAsStream("/homologacao/docker-shim.sh")) {
            Files.write(shimDir.resolve("docker"), in.readAllBytes());
        }
        shimDir.resolve("docker").toFile().setExecutable(true);
    }

    @AfterAll
    static void encerrar() throws IOException {
        if (HB != null) {
            HB.close();
        }
        for (Path d : new Path[] {segredos, shimDir}) {
            if (d != null) {
                try (Stream<Path> w = Files.walk(d)) {
                    w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        }
        Evidencia.anexar("runner", "ambiente", "id=" + ID + " saidas=ops/homologacao/saidas/" + ID + " (fixtures.txt, solicitacoes_com_fixture.txt, "
                + ".sonda); segredos efemeros e shim apagados");
    }

    static String mixed(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------ runner

    static Processos.Resultado runner(Map<String, String> extra, String... args) {
        List<String> cmd = new ArrayList<>(List.of(Processos.bash(), mixed(Processos.HOMOLOGACAO.resolve("homologacao.sh"))));
        cmd.addAll(List.of(args));
        Map<String, String> env = new HashMap<>();
        env.put("PATH", shimDir.toAbsolutePath() + java.io.File.pathSeparator + System.getenv("PATH"));
        env.put("P1C_DOCKER_REAL", Processos.dockerReal().replace('\\', '/'));
        env.put("P1C_CLIENTE", HB.cliente.getContainerId());
        env.put("P1C_SONDA_SOL", mixed(SOL));
        env.putAll(extra);
        Processos.Resultado r = Processos.rodar(cmd, null, env, Processos.REPO, 240);
        Evidencia.anexar("runner", "execucoes", "homologacao.sh " + String.join(" ", args) + "\ncodigo=" + r.codigo() + "\n--- saida\n" + r.saida()
                + "--- erro\n" + r.erro());
        return r;
    }

    static final Pattern CLASSE = Pattern.compile("classificacao=([A-Z_]+) execucao=([0-9a-f-]{36})");

    record Execucao(String classe, String id) {
    }

    static Execucao execucao(Processos.Resultado r) {
        Matcher m = CLASSE.matcher(r.saida());
        assertThat(m.find()).as("runner imprime a classificacao:\n%s\n%s", r.saida(), r.erro()).isTrue();
        return new Execucao(m.group(1), m.group(2));
    }

    static List<String> sol() throws IOException {
        return Files.exists(SOL) ? Files.readAllLines(SOL) : List.of();
    }

    static String texto(Path p) throws IOException {
        return Files.exists(p) ? Files.readString(p) : "";
    }

    /** Organizacao nova com o nome sintetico deste id, registrada no fixture.json do ambiente (o runner le a cada chamada). */
    static void usarOrganizacao(UUID org) throws IOException {
        DB.sql("UPDATE organizations SET name = 'Homologacao 016 sintetica " + ID + "' WHERE organization_id = '" + org + "'");
        Files.writeString(segredos.resolve("teste016/fixture.json"), "{\"ambiente\": \"" + ID + "\", \"ids\": {\"org\": \"" + org + "\"}}",
                StandardCharsets.UTF_8);
    }

    static UUID solicitacaoConfirmada() throws IOException {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.confirmada(eq, al);
        usarOrganizacao(al.org().id());
        return s;
    }

    static String opened(UUID s) {
        return HB.valor("SELECT (extract(epoch FROM opened_at) * 1000000)::bigint FROM ops.ops_mfa_recovery_requests WHERE request_id = '" + s + "'");
    }

    static void pausar(boolean p) {
        String id = DB.container().getContainerId();
        if (p) {
            DockerClientFactory.instance().client().pauseContainerCmd(id).exec();
        } else {
            DockerClientFactory.instance().client().unpauseContainerCmd(id).exec();
        }
    }

    // ------------------------------------------------------------------ casos

    /** APLICADA pelo runner: registro preventivo ANTES do psql, classificacao, anexo, e a reconciliacao conferir. */
    @Test
    void solicitacaoAplicadaComRegistroPreventivoEReconciliacao() throws Exception {
        UUID s = solicitacaoConfirmada();
        String antes = opened(s);
        Processos.Resultado r = runner(Map.of(), "fixture", ID, "solicitacao", s.toString(), "24");
        assertThat(r.codigo()).as(r.saida() + r.erro()).isZero();
        Execucao e = execucao(r);
        assertThat(e.classe()).isEqualTo("APLICADA");
        assertThat(r.saida()).contains("fixture_solicitacao: solicitacao=" + s + " deslocamento=24h").contains("verificacoes=OK");
        assertThat(Long.parseLong(opened(s))).isEqualTo(Long.parseLong(antes) - 24 * 3_600_000_000L);
        List<String> linhas = sol().stream().filter(l -> l.contains(e.id())).toList();
        assertThat(linhas).hasSize(2);
        assertThat(linhas.get(0)).startsWith(s + "|tentativa|" + e.id() + "|");
        assertThat(linhas.get(1)).isEqualTo(s + "|APLICADA|" + e.id());
        // no instante em que o psql foi chamado, a tentativa desta execucao ja estava registrada
        assertThat(texto(Path.of(SOL + ".sonda"))).contains(s + "|tentativa|" + e.id());
        assertThat(texto(FX)).contains("== fixture solicitacao execucao=" + e.id()).contains("classificacao=APLICADA execucao=" + e.id());

        Processos.Resultado c = runner(Map.of(), "fixture", ID, "conferir", e.id());
        assertThat(c.codigo()).as(c.saida() + c.erro()).isZero();
        assertThat(c.saida()).contains("reconciliacao: APLICADA");
        assertThat(sol()).contains(s + "|conferencia:APLICADA|" + e.id());

        // banco INDISPONIVEL na reconciliacao: nada concluido, nada anexado
        String fxAntes = texto(FX);
        List<String> solAntes = sol();
        pausar(true);
        Processos.Resultado ind;
        try {
            ind = runner(Map.of("P1C_CONNECT_TIMEOUT", "5"), "fixture", ID, "conferir", e.id());
        } finally {
            pausar(false);
        }
        assertThat(ind.codigo()).isNotZero();
        assertThat(ind.erro()).contains("estado atual nao lido (banco indisponivel?): nada concluido, repita a conferencia");
        assertThat(ind.saida()).doesNotContain("reconciliacao:");
        assertThat(texto(FX)).isEqualTo(fxAntes);
        assertThat(sol()).isEqualTo(solAntes);
    }

    /** NAO_APLICADA (recusa antes do COMMIT, horas=0): mensagem de transacao desfeita; ainda assim impropria para B9. */
    @Test
    void solicitacaoRecusadaFicaImpropriaParaB9() throws Exception {
        UUID s = solicitacaoConfirmada();
        String antes = opened(s);
        Processos.Resultado r = runner(Map.of(), "fixture", ID, "solicitacao", s.toString(), "0");
        assertThat(r.codigo()).isEqualTo(1);
        Execucao e = execucao(r);
        assertThat(e.classe()).isEqualTo("NAO_APLICADA");
        assertThat(r.erro()).contains("fixture recusada ANTES do COMMIT: transacao desfeita, nada foi alterado");
        assertThat(opened(s)).isEqualTo(antes);
        assertThat(sol()).contains(s + "|NAO_APLICADA|" + e.id()).anySatisfy(l -> assertThat(l).startsWith(s + "|tentativa|" + e.id() + "|"));
        Processos.Resultado c = runner(Map.of(), "fixture", ID, "conferir", e.id());
        assertThat(c.saida()).contains("reconciliacao: NAO_APLICADA");
        assertThat(c.codigo()).isZero();
    }

    /**
     * Banco indisponivel na execucao (container pausado, conexao com tempo esgotado): sem estado de partida e sem marca
     * -> INDETERMINADA, "a RECONCILIAR", nunca "nada foi alterado"; a solicitacao ja estava registrada antes do psql. A
     * reconciliacao depois fica SEM_ESTADO_DE_PARTIDA (analise manual), codigo 1.
     */
    @Test
    void bancoIndisponivelNaExecucaoFicaIndeterminadaEImpropriaParaB9() throws Exception {
        UUID s = solicitacaoConfirmada();
        String antes = opened(s);
        pausar(true);
        Processos.Resultado r;
        try {
            r = runner(Map.of("P1C_CONNECT_TIMEOUT", "5"), "fixture", ID, "solicitacao", s.toString(), "24");
        } finally {
            pausar(false);
        }
        assertThat(r.codigo()).isEqualTo(1);
        Execucao e = execucao(r);
        assertThat(e.classe()).isEqualTo("INDETERMINADA");
        assertThat(r.erro()).contains("resultado a RECONCILIAR (INDETERMINADA): homologacao.sh fixture " + ID + " conferir " + e.id())
                .doesNotContain("nada foi alterado").doesNotContain("desfeita");
        assertThat(sol()).contains(s + "|INDETERMINADA|" + e.id());
        assertThat(texto(Path.of(SOL + ".sonda"))).contains(s + "|tentativa|" + e.id());
        assertThat(opened(s)).isEqualTo(antes);
        Processos.Resultado c = runner(Map.of(), "fixture", ID, "conferir", e.id());
        assertThat(c.saida()).contains("reconciliacao: SEM_ESTADO_DE_PARTIDA");
        assertThat(c.codigo()).isEqualTo(1);
        assertThat(sol()).contains(s + "|conferencia:SEM_ESTADO_DE_PARTIDA|" + e.id());
    }

    /**
     * Falha DEPOIS do COMMIT, observada pelo runner real: o COMMIT e confirmado (marca) e a sessao cai na conferencia
     * posterior. O runner classifica APLICADA_COM_FALHA_POS_COMMIT, exige reconciliar e NUNCA diz rollback/nada alterado;
     * conferir -> APLICADA.
     */
    @Test
    void falhaDepoisDoCommitNuncaEDescritaComoRollback() throws Exception {
        UUID s = solicitacaoConfirmada();
        String antes = opened(s);
        Processos.Resultado r;
        try (Connection linha = DB.superusuario(); Statement sl = linha.createStatement();
             Connection tabela = DB.superusuario(); Statement stab = tabela.createStatement()) {
            linha.setAutoCommit(false);
            tabela.setAutoCommit(false);
            sl.execute("SELECT 1 FROM ops.ops_mfa_recovery_requests WHERE request_id = '" + s + "' FOR UPDATE");
            String pid;
            try (ResultSet rs = stab.executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                pid = rs.getString(1);
            }
            CompletableFuture<Processos.Resultado> fx = CompletableFuture.supplyAsync(
                    () -> runner(Map.of("P1C_APPNAME", "rn-pos"), "fixture", ID, "solicitacao", s.toString(), "24"));
            HB.esperar("fixture no UPDATE", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'rn-pos' AND wait_event_type = 'Lock')",
                    Duration.ofSeconds(90));
            CompletableFuture<Void> trava = CompletableFuture.runAsync(() -> {
                try {
                    stab.execute("LOCK TABLE ops.ops_mfa_recovery_requests IN ACCESS EXCLUSIVE MODE");
                } catch (SQLException x) {
                    throw new IllegalStateException(x);
                }
            });
            HB.esperar("ACCESS EXCLUSIVE na fila", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = " + pid + " AND wait_event_type = 'Lock')",
                    Duration.ofSeconds(60));
            linha.commit();
            trava.get(1, TimeUnit.MINUTES);
            HB.esperar("conferencia pos-COMMIT bloqueada", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'rn-pos' "
                    + "AND wait_event_type = 'Lock' AND backend_xid IS NULL)", Duration.ofSeconds(60));
            assertThat(HB.valor("SELECT bool_and(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = 'rn-pos'")).isEqualTo("t");
            r = fx.get(3, TimeUnit.MINUTES);
            tabela.rollback();
        }
        assertThat(r.codigo()).isEqualTo(1);
        Execucao e = execucao(r);
        assertThat(e.classe()).isEqualTo("APLICADA_COM_FALHA_POS_COMMIT");
        assertThat(r.saida()).contains("FIXTURE_COMMIT_CONCLUIDO");
        assertThat(r.saida() + r.erro()).contains("resultado a RECONCILIAR (APLICADA_COM_FALHA_POS_COMMIT)")
                .doesNotContain("nada foi alterado").doesNotContain("desfeita").doesNotContain("rollback");
        assertThat(sol()).contains(s + "|APLICADA_COM_FALHA_POS_COMMIT|" + e.id());
        assertThat(Long.parseLong(opened(s))).isEqualTo(Long.parseLong(antes) - 24 * 3_600_000_000L);
        Processos.Resultado c = runner(Map.of(), "fixture", ID, "conferir", e.id());
        assertThat(c.saida()).contains("reconciliacao: APLICADA");
        assertThat(sol()).contains(s + "|conferencia:APLICADA|" + e.id());
    }

    /** Contatos pelo runner (B8a): APLICADA; nao mexe no registro de B9 (que e so de solicitacoes). */
    @Test
    void contatosPeloRunnerNaoTocamORegistroDeB9() throws Exception {
        OpsBanco.Org o = DB.organizacao();
        usarOrganizacao(o.id());
        UUID c1 = DB.contatoNovo(o, o.admin());
        UUID c2 = DB.contatoNovo(o, null);
        List<String> solAntes = sol();
        Processos.Resultado r = runner(Map.of(), "fixture", ID, "contatos", c1 + "," + c2);
        assertThat(r.codigo()).as(r.saida() + r.erro()).isZero();
        assertThat(execucao(r).classe()).isEqualTo("APLICADA");
        assertThat(r.saida()).contains("linhas=2 deslocamento=721h verificacoes=OK session_replication_role=origin");
        assertThat(sol()).isEqualTo(solAntes);
        // recusas do proprio runner ANTES de conectar: lista fora do formato e tipo invalido
        assertThat(runner(Map.of(), "fixture", ID, "contatos", "nao-e-uuid").erro()).contains("lista de contatos invalida");
        assertThat(runner(Map.of(), "fixture", ID, "solicitacao", c1.toString(), "240").erro()).contains("parametros invalidos");
        assertThat(sol()).isEqualTo(solAntes);
    }
}
