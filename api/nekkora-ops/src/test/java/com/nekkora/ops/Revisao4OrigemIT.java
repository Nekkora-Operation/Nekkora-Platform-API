package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.nekkora.ops.ConcorrenciaIT.Aberta;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.cli.ExecutorAtos.Resultado;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Revisao 4, item 4: abertura por ENTRADA (origem comprovada, cota de 3 por origem em 24 h entre organizacoes) ou por
 * RETORNO pelo canal registrado (sem cota). Os pseudonimos sao valores hexadecimais aleatorios (o HMAC fica no servico de
 * entrada, simulado). O estado das versoes de chave e global: todo teste que ativa uma versao encerra a transicao no fim
 * (fixture temporal), para nao afetar os demais.
 */
class Revisao4OrigemIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    @AfterEach
    void encerrarTransicao() {
        DB.recuarAtivacaoDaChave(Duration.ofHours(26));
    }

    private static int versaoVigente() {
        return DB.valor(Integer.class, "SELECT current_version FROM ops.ops_intake_key_state");
    }

    /** Ativa a proxima versao (a anterior precisa ter mais de 25 h: fixture) e devolve o numero dela. */
    private static int ativarProxima() {
        DB.recuarAtivacaoDaChave(Duration.ofHours(26));
        int v = versaoVigente() + 1;
        assertThat(ativar(v)).isEqualTo("OK");
        return v;
    }

    private static String ativar(int versao) {
        try (var c = DB.comoServico("nekkora_ops_intake");
             PreparedStatement ps = c.prepareStatement("SELECT r_resultado, r_motivo FROM public.ops_intake_key_activate(?)")) {
            ps.setInt(1, versao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return "OK".equals(rs.getString(1)) ? "OK" : rs.getString(2);
            }
        } catch (SQLException e) {
            return "ERRO:" + e.getSQLState();
        }
    }

    private static long solicitacoesDaOrigem(String pseudonimo) {
        return DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_requests WHERE origin_pseudonym = ? OR prev_pseudonym = ?",
                pseudonimo, pseudonimo);
    }

    private static Resultado abrirCom(Cenarios.Equipe eq, Cenarios.Alvo al, UUID entrada) {
        return eq.v().abrir(al.org().id(), al.titular(), al.contatoTitular(), new ExecutorAtos.Entrada(entrada));
    }

    // ------------------------------------------------------------------ origem nao comprovada e recusas da entrada

    @Test
    void entradaSemOrigemComprovadaNaoAbreNemConsomeCota() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        List<Object> r = C.registrarEntrada(2, 3, null, null, eq.verificador().papel());
        assertThat(r.get(0)).isEqualTo("OK");
        long baldes = DB.valor(Long.class, "SELECT count(*) FROM ops.ops_origin_buckets");
        Resultado ab = abrirCom(eq, al, (UUID) r.get(2));
        assertThat(ab.motivo()).isEqualTo("ORIGEM_NAO_COMPROVADA");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_requests WHERE target_user_id = ?", al.titular())).isZero();
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_origin_buckets")).as("nenhum balde criado").isEqualTo(baldes);
        // mecanismo 3 com pseudonimo e recusado no registro
        assertThat(C.registrarEntrada(2, 3, OpsBanco.hex(32), null, eq.verificador().papel()).get(1)).isEqualTo("ENTRADA_INVALIDA");
        // o atendimento segue pelo retorno ao canal registrado
        Cenarios.exigirOk(C.abrirPorRetorno(eq.v(), al.org().id(), al.titular(), al.contatoTitular()), "retorno");
    }

    @Test
    void entradaUsadaDeOutroOperadorVencidaOuInexistenteERecusada() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID e1 = C.entradaComprovada(eq.verificador(), OpsBanco.hex(32));
        Cenarios.exigirOk(abrirCom(eq, al, e1), "primeiro uso");
        assertThat(abrirCom(eq, al, e1).motivo()).isEqualTo("ENTRADA_USADA");
        UUID deOutro = C.entradaComprovada(eq.aprovador(), OpsBanco.hex(32));
        assertThat(abrirCom(eq, al, deOutro).motivo()).isEqualTo("ENTRADA_DE_OUTRO_OPERADOR");
        UUID vencida = C.entradaComprovada(eq.verificador(), OpsBanco.hex(32));
        DB.recuarEntrada(vencida, Duration.ofMinutes(60));
        assertThat(abrirCom(eq, al, vencida).motivo()).isEqualTo("ENTRADA_VENCIDA");
        assertThat(abrirCom(eq, al, UUID.randomUUID()).motivo()).isEqualTo("ENTRADA_INVALIDA");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_requests WHERE target_user_id = ?", al.titular())).isEqualTo(1);
    }

    @Test
    void retornoComCanalAlteradoDepoisDoRegistroERecusado() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        ExecutorAtos.RetornoRegistrado rr = eq.v().registrarRetorno(al.contatoTitular());
        assertThat(rr.classe()).isEqualTo(ExecutorAtos.Classe.OK);
        // o canal do contato muda (versao nova do canal) e volta a ser elegivel pela fixture temporal
        assertThat(DB.cadastro("SELECT r_resultado FROM public.ops_contact_change_channel(?, 2::smallint, ?, true, ?, true)", String.class,
                al.contatoTitular(), "novo-" + OpsBanco.hex(4) + "@contato.test", al.segundoContato())).isEqualTo("OK");
        DB.envelhecerContato(al.contatoTitular(), Duration.ofDays(31));
        Resultado ab = eq.v().abrir(al.org().id(), al.titular(), al.contatoTitular(), new ExecutorAtos.Retorno(rr.retorno()));
        assertThat(ab.motivo()).isEqualTo("RETORNO_INVALIDO");
        // retorno de outro operador tambem nao abre
        ExecutorAtos.RetornoRegistrado doAprovador = DB.cli(DB.operador("v2", "VERIFICAR")).registrarRetorno(al.contatoTitular());
        assertThat(eq.v().abrir(al.org().id(), al.titular(), al.contatoTitular(), new ExecutorAtos.Retorno(doAprovador.retorno())).motivo())
                .isEqualTo("RETORNO_INVALIDO");
    }

    // ------------------------------------------------------------------ cota concorrente

    /** 4 aberturas concorrentes da mesma origem comprovada na mesma organizacao -> exatamente 3. */
    @Test
    void quatroAberturasConcorrentesDaMesmaOrigemAbremExatamenteTres() throws Exception {
        Cenarios.Alvo al = C.alvo();
        String origem = OpsBanco.hex(32);
        List<CompletableFuture<Resultado>> fs = new ArrayList<>();
        List<Runnable> disparos = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            OpsBanco.Operador op = DB.operador("cota" + i, "VERIFICAR");
            ExecutorAtos v = DB.cli(op);
            UUID entrada = C.entradaComprovada(op, origem);
            CompletableFuture<Resultado> f = new CompletableFuture<>();
            fs.add(f);
            disparos.add(() -> f.complete(v.abrir(al.org().id(), al.titular(), al.contatoTitular(), new ExecutorAtos.Entrada(entrada))));
        }
        disparos.forEach(d -> CompletableFuture.runAsync(d));
        List<Resultado> rs = new ArrayList<>();
        for (CompletableFuture<Resultado> f : fs) {
            rs.add(f.get(60, TimeUnit.SECONDS));
        }
        assertThat(rs.stream().filter(Resultado::ok).count()).isEqualTo(3);
        assertThat(rs.stream().filter(r -> "LIMITE_ORIGEM".equals(r.motivo())).count()).isEqualTo(1);
        assertThat(solicitacoesDaOrigem(origem)).isEqualTo(3);
    }

    /** A mesma origem em duas organizacoes, concorrendo (2 + 2) -> 3 no total. */
    @Test
    void mesmaOrigemEmDuasOrganizacoesConcorrendoSomaTres() throws Exception {
        Cenarios.Alvo a = C.alvo();
        Cenarios.Alvo b = C.alvo();
        String origem = OpsBanco.hex(32);
        List<CompletableFuture<Resultado>> fs = new ArrayList<>();
        int i = 0;
        for (Cenarios.Alvo al : List.of(a, a, b, b)) {
            OpsBanco.Operador op = DB.operador("org" + i++, "VERIFICAR");
            ExecutorAtos v = DB.cli(op);
            UUID entrada = C.entradaComprovada(op, origem);
            fs.add(CompletableFuture.supplyAsync(() -> v.abrir(al.org().id(), al.titular(), al.contatoTitular(), new ExecutorAtos.Entrada(entrada))));
        }
        List<Resultado> rs = new ArrayList<>();
        for (CompletableFuture<Resultado> f : fs) {
            rs.add(f.get(60, TimeUnit.SECONDS));
        }
        assertThat(rs.stream().filter(Resultado::ok).count()).isEqualTo(3);
        assertThat(rs.stream().filter(r -> "LIMITE_ORIGEM".equals(r.motivo())).count()).isEqualTo(1);
        assertThat(solicitacoesDaOrigem(origem)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ rotacao

    /**
     * Entrada registrada 59 min antes da ativacao (so o pseudonimo antigo) e usada depois dela: aceita. As entradas novas
     * da mesma origem trazem os dois pseudonimos; a contagem une os dois e conta cada solicitacao UMA vez: 1 (antiga) + 2
     * (novas) = 3, e a quarta e recusada.
     */
    @Test
    void rotacaoPreservaAJanelaEContaCadaSolicitacaoUmaVez() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        String antigo = OpsBanco.hex(32);
        String novo = OpsBanco.hex(32);
        DB.recuarAtivacaoDaChave(Duration.ofHours(26));
        UUID velha = C.entradaComprovada(eq.verificador(), antigo);
        DB.recuarEntrada(velha, Duration.ofMinutes(59));
        int v = ativarProxima();
        Cenarios.exigirOk(abrirCom(eq, al, velha), "entrada anterior a ativacao, usada depois");
        for (int i = 0; i < 2; i++) {
            List<Object> r = C.registrarEntradaVersao(2, 1, v, novo, v - 1, antigo, eq.verificador().papel());
            assertThat(r.get(0)).isEqualTo("OK");
            Cenarios.exigirOk(abrirCom(eq, al, (UUID) r.get(2)), "nova " + i);
        }
        List<Object> quarta = C.registrarEntradaVersao(2, 1, v, novo, v - 1, antigo, eq.verificador().papel());
        assertThat(abrirCom(eq, al, (UUID) quarta.get(2)).motivo()).isEqualTo("LIMITE_ORIGEM");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_requests WHERE origin_pseudonym IN (?, ?) OR prev_pseudonym IN (?, ?)",
                antigo, novo, antigo, novo)).as("3, nao 5").isEqualTo(3);
    }

    /** Na transicao, entrada nova sem o pseudonimo vigente ou so com o anterior e recusada; depois de 25 h, so o vigente. */
    @Test
    void entradaNaTransicaoExigeOsDoisPseudonimosEDepoisSoOVigente() {
        Cenarios.Equipe eq = C.equipe();
        String papel = eq.verificador().papel();
        int v = ativarProxima();
        String p = OpsBanco.hex(32);
        String q = OpsBanco.hex(32);
        assertThat(C.registrarEntradaVersao(2, 1, v, p, null, null, papel).get(1)).as("sem o anterior").isEqualTo("ENTRADA_INVALIDA");
        assertThat(C.registrarEntradaVersao(2, 1, v - 1, p, null, null, papel).get(1)).as("so a versao anterior").isEqualTo("ENTRADA_INVALIDA");
        assertThat(C.registrarEntradaVersao(2, 1, v, p, v - 1, p, papel).get(1)).as("pseudonimos iguais").isEqualTo("ENTRADA_INVALIDA");
        assertThat(C.registrarEntradaVersao(2, 1, v, p, v - 1, q, papel).get(0)).isEqualTo("OK");
        // ativacao da versao seguinte durante a transicao: recusada (transicoes nao se sobrepoem)
        assertThat(ativar(v + 1)).isEqualTo("TRANSICAO_EM_CURSO");
        // limite: ativada ha 25 h menos 5 s -> ainda transicao; ha 25 h + 5 s -> so o vigente
        DB.recuarAtivacaoDaChave(Duration.ofHours(25).minusSeconds(5));
        assertThat(C.registrarEntradaVersao(2, 1, v, p, null, null, papel).get(1)).as("ainda na transicao").isEqualTo("ENTRADA_INVALIDA");
        DB.recuarAtivacaoDaChave(Duration.ofSeconds(10));
        assertThat(C.registrarEntradaVersao(2, 1, v, p, null, null, papel).get(0)).isEqualTo("OK");
        assertThat(C.registrarEntradaVersao(2, 1, v, p, v - 1, q, papel).get(1)).as("fora da transicao, dois pseudonimos").isEqualTo("ENTRADA_INVALIDA");
    }

    /**
     * Ativacao concorrente com uma abertura em curso: a ativacao espera a abertura (estado das versoes FOR SHARE x FOR
     * UPDATE) e, depois dela, nenhuma entrada nova so com a versao anterior e aceita.
     */
    @Test
    void ativacaoEsperaAberturaEmCurso() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        DB.recuarAtivacaoDaChave(Duration.ofHours(26));
        int v = versaoVigente();
        UUID entrada = C.entradaComprovada(eq.verificador(), OpsBanco.hex(32));
        CompletableFuture<String> ativacao;
        try (Aberta ab = new Aberta(eq.verificador())) {
            try (PreparedStatement ps = ab.con.prepareStatement("SELECT r_resultado FROM public.ops_mfa_open(?, ?, ?, ?, NULL)")) {
                ps.setObject(1, al.org().id());
                ps.setObject(2, al.titular());
                ps.setObject(3, al.contatoTitular());
                ps.setObject(4, entrada);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("OK");
                }
            }
            ativacao = CompletableFuture.supplyAsync(() -> ativar(v + 1));
            CertificadosIT.esperarBloqueado("nekkora_ops_intake");
            assertThat(ativacao.isDone()).isFalse();
            ab.commit();
        }
        assertThat(ativacao.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(C.registrarEntradaVersao(2, 1, v, OpsBanco.hex(32), null, null, eq.verificador().papel()).get(1))
                .as("so a versao anterior, depois da ativacao").isEqualTo("ENTRADA_INVALIDA");
    }
}
