package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.Credencial;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.cli.ExecutorAtos.Origem;
import com.nekkora.ops.cli.LogLocal;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;
import com.nekkora.ops.testsupport.ProxyTcp;

/**
 * Grupos Reconciliacao e Atomicidade (contrato 7 e 10): resposta perdida depois do commit (obrigatorio), execucao
 * ainda em andamento segurando a trava, consulta impossivel, falha tecnica antes do commit (rollback integral +
 * registro INFORMADO_PELA_CLI) e registro separado indisponivel. A CLI nunca presume rollback nem repete efeitos.
 */
class ReconciliacaoEAtomicidadeIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private static Connection viaProxy(ProxyTcp proxy, Credencial c) throws SQLException {
        return DriverManager.getConnection(OpsBanco.url(proxy.host(), proxy.porta()), c.propriedades(DB.raizServidor()));
    }

    private static long falhasRegistradas(UUID s) {
        return DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND act = 'FALHA'", s);
    }

    /** Espera a sessao do papel sair de "idle in transaction" (o COMMIT chegou e foi processado). */
    private static void esperarCommitNoServidor(String papel) {
        for (int i = 0; i < 200; i++) {
            Long n = DB.valor(Long.class, "SELECT count(*) FROM pg_stat_activity WHERE usename = ? AND state <> 'idle'", papel);
            if (n != null && n == 0) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("o COMMIT nao foi processado no servidor");
    }

    @Test
    void respostaPerdidaDepoisDoCommitEReconciliadaComoExecutadaSemFalhaNemRepeticao() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        LogLocal log = new LogLocal(DB.diretorio().resolve("reconc-1-" + OpsBanco.sufixo() + ".log"));
        try (ProxyTcp proxy = new ProxyTcp(DB.host(), DB.porta()); Credencial cred = DB.credencial(eq.executor())) {
            AtomicInteger n = new AtomicInteger();
            ExecutorAtos exe = new ExecutorAtos(() -> {
                if (n.getAndIncrement() > 0) {
                    return DB.conectar(cred);
                }
                return ProxyTcp.comGanchoNoCommit(viaProxy(proxy, cred), () -> {
                    proxy.engolirRespostas();
                    Thread t = new Thread(() -> {
                        esperarCommitNoServidor(eq.executor().papel());
                        proxy.cortarCliente();
                    });
                    t.setDaemon(true);
                    t.start();
                });
            }, log);
            ExecutorAtos.Resultado r = exe.executar(s);
            assertThat(r.classe()).isEqualTo(Classe.OK);
            assertThat(r.origem()).as("confirmado pelo banco na reconciliacao").isEqualTo(Origem.RECONCILIADO);
            assertThat(r.estado()).isEqualTo("EXECUTADA");
            assertThat(n.get()).as("uma conexao perdida e uma de consulta").isEqualTo(2);
        }
        assertThat(C.resets(s)).isEqualTo(1);
        assertThat(falhasRegistradas(s)).isZero();
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND act = 'EXECUTAR'", s)).isEqualTo(1);
        assertThat(Files.readString(log.arquivo())).contains("ato=EXECUTAR", "resultado=OK", "motivo=RECONCILIADO", "etapa=COMMIT");
    }

    @Test
    void execucaoAindaSegurandoATravaMantemDesconhecidoNuncaNaoExecutada() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        LogLocal log = new LogLocal(DB.diretorio().resolve("reconc-2-" + OpsBanco.sufixo() + ".log"));
        try (Credencial cred = DB.credencial(eq.executor())) {
            ProxyTcp proxy = new ProxyTcp(DB.host(), DB.porta());
            try {
                AtomicInteger n = new AtomicInteger();
                ExecutorAtos exe = new ExecutorAtos(() -> n.getAndIncrement() > 0 ? DB.conectar(cred)
                        : ProxyTcp.comGanchoNoCommit(viaProxy(proxy, cred), proxy::cortarCliente), log);
                ExecutorAtos.Resultado r = exe.executar(s);
                assertThat(r.classe()).isEqualTo(Classe.DESCONHECIDO);
                assertThat(r.motivo()).isEqualTo("TRAVA_NAO_OBTIDA");
                assertThat(log.alertas()).hasSize(1);
                assertThat(falhasRegistradas(s)).as("nada registrado como falha").isZero();
            } finally {
                proxy.close();
            }
            // o servidor aborta a transacao orfa quando o lado dele cai; so entao a consulta com a trava confirma
            for (int i = 0; i < 100 && DB.valor(Long.class, "SELECT count(*) FROM pg_stat_activity WHERE usename = ?", eq.executor().papel()) > 0; i++) {
                Thread.sleep(50);
            }
            ExecutorAtos.Resultado depois = new ExecutorAtos(() -> DB.conectar(cred), log).consultar(s);
            assertThat(depois.estado()).isEqualTo("APROVADA");
        }
        assertThat(C.resets(s)).isZero();
        assertThat(C.acesso(al.org().id(), al.titular())).isEqualTo("1|2|1|1");
        Cenarios.exigirOk(eq.e().executar(s), "execucao posterior dentro dos prazos");
    }

    @Test
    void semConsultaPossivelDepoisDaPerdaFicaDesconhecidoComLogEAlerta() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        LogLocal log = new LogLocal(DB.diretorio().resolve("reconc-3-" + OpsBanco.sufixo() + ".log"));
        try (Credencial cred = DB.credencial(eq.executor())) {
            ProxyTcp proxy = new ProxyTcp(DB.host(), DB.porta());
            try {
                ExecutorAtos exe = new ExecutorAtos(() -> ProxyTcp.comGanchoNoCommit(viaProxy(proxy, cred), () -> {
                    proxy.cortarCliente();
                    proxy.recusarNovas();
                }), log);
                ExecutorAtos.Resultado r = exe.executar(s);
                assertThat(r.classe()).isEqualTo(Classe.DESCONHECIDO);
                assertThat(r.motivo()).isEqualTo("CONSULTA_IMPOSSIVEL");
            } finally {
                proxy.close();
            }
        }
        assertThat(log.alertas()).singleElement().asString().contains("RESULTADO_DESCONHECIDO");
        assertThat(Files.readString(log.arquivo())).contains("resultado=DESCONHECIDO", "motivo=CONSULTA_IMPOSSIVEL");
        assertThat(falhasRegistradas(s)).isZero();
    }

    private static void injetarFalha(UUID s) {
        DB.sql("CREATE OR REPLACE FUNCTION public.teste_falha_injetada() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $$ "
                + "BEGIN IF NEW.event_type = 4 AND NEW.ops_request_id = '" + s + "' THEN RAISE EXCEPTION 'falha tecnica injetada (teste)'; END IF; "
                + "RETURN NEW; END $$",
            "REVOKE ALL ON FUNCTION public.teste_falha_injetada() FROM PUBLIC",
            "CREATE TRIGGER teste_falha_injetada BEFORE INSERT ON public.mfa_events FOR EACH ROW EXECUTE FUNCTION public.teste_falha_injetada()");
    }

    private static void removerFalha() {
        DB.sql("DROP TRIGGER IF EXISTS teste_falha_injetada ON public.mfa_events", "DROP FUNCTION IF EXISTS public.teste_falha_injetada()");
    }

    /** Falha no meio dos efeitos (depois de fator, codigos, desafios e sessoes): rollback integral. */
    @Test
    void falhaTecnicaAntesDoCommitNaoDeixaEfeitoParcialEERegistradaPelaCli() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        String antes = C.acesso(al.org().id(), al.titular());
        injetarFalha(s);
        try {
            ExecutorAtos.Resultado r = eq.e().executar(s);
            assertThat(r.classe()).isEqualTo(Classe.FALHA_CONFIRMADA);
            assertThat(r.sqlstate()).isEqualTo("P0001");
            assertThat(r.falhaRegistrada()).isTrue();
        } finally {
            removerFalha();
        }
        assertThat(C.acesso(al.org().id(), al.titular())).isEqualTo(antes);
        assertThat(C.estado(s)).isEqualTo("APROVADA");
        assertThat(C.resets(s)).isZero();
        assertThat(DB.linhas("SELECT origin, failed_act, sqlstate, stage, operator_id FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND act = 'FALHA'", s))
                .singleElement().satisfies(l -> {
                    assertThat(((Number) l.get(0)).intValue()).as("INFORMADO_PELA_CLI").isEqualTo(2);
                    assertThat(l.get(1)).isEqualTo("EXECUTAR");
                    assertThat(l.get(2)).isEqualTo("P0001");
                    assertThat(l.get(3)).isEqualTo("CHAMADA");
                    assertThat(l.get(4)).isEqualTo(eq.executor().id());
                });
        Cenarios.exigirOk(eq.e().executar(s), "depois de removida a falha");
        assertThat(C.acesso(al.org().id(), al.titular())).isEqualTo("0|0|0|0");
    }

    @Test
    void registroSeparadoIndisponivelSaiComErroLogEAlerta() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        UUID s = C.aprovada(eq, C.alvo());
        LogLocal log = new LogLocal(DB.diretorio().resolve("reconc-5-" + OpsBanco.sufixo() + ".log"));
        injetarFalha(s);
        try (Credencial cred = DB.credencial(eq.executor())) {
            AtomicInteger n = new AtomicInteger();
            ExecutorAtos exe = new ExecutorAtos(() -> {
                if (n.getAndIncrement() > 0) {
                    throw new SQLException("banco indisponivel (simulado)", "08001");
                }
                return DB.conectar(cred);
            }, log);
            ExecutorAtos.Resultado r = exe.executar(s);
            assertThat(r.classe()).isEqualTo(Classe.FALHA_CONFIRMADA);
            assertThat(r.motivo()).isEqualTo("REGISTRO_INDISPONIVEL");
            assertThat(r.falhaRegistrada()).isFalse();
        } finally {
            removerFalha();
        }
        assertThat(log.alertas()).singleElement().asString().contains("REGISTRO_SEPARADO_INDISPONIVEL");
        assertThat(falhasRegistradas(s)).isZero();
    }
}
