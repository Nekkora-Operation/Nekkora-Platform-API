package com.nekkora.ops.cli;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Atos da CLI operacional (contrato 7). Cada ato roda numa transacao EXPLICITA: chamada da funcao, depois COMMIT.
 * Tres desfechos distintos, nunca confundidos:
 * <ul>
 * <li><b>RECUSA</b> (recusa prevista): a funcao devolveu a recusa e o registro dela foi confirmado (COMMIT);</li>
 * <li><b>FALHA_CONFIRMADA</b>: o servidor devolveu erro ANTES do commit (rollback integral); a CLI grava
 * {@code ops_mfa_record_failure} em OUTRA transacao (origem INFORMADO_PELA_CLI), com SQLSTATE e etapa;</li>
 * <li><b>DESCONHECIDO</b>: conexao ou resposta perdida. Nunca presume rollback: reconecta e consulta
 * {@code ops_mfa_status}, que espera a MESMA trava da solicitacao. Sem a trava ou sem consulta, fica desconhecido,
 * com log local e alerta, e nada e registrado como falha.</li>
 * </ul>
 * A autoria e do servidor ({@code session_user} da conexao por certificado): nenhum ato recebe o operador.
 */
public final class ExecutorAtos {

    /** Fonte de conexoes do operador (TLS + certificado). Nova conexao a cada uso. */
    @FunctionalInterface
    public interface Conexoes {
        Connection abrir() throws SQLException;
    }

    public enum Classe { OK, RECUSA, FALHA_CONFIRMADA, DESCONHECIDO }

    /** Origem da informacao: BANCO (resposta do proprio ato), RECONCILIADO (consulta posterior com a trava), CLI. */
    public enum Origem { BANCO, RECONCILIADO, CLI }

    /**
     * Desfecho de um ato. {@code solicitacao} e o UUID da solicitacao: o informado pelo operador ou, em ABRIR, o
     * devolvido pelo banco (nulo quando a abertura nao aconteceu ou o desfecho e desconhecido sem id), para que o
     * operador continue o procedimento sem consulta administrativa.
     */
    public record Resultado(UUID solicitacao, Classe classe, String motivo, String estado, Origem origem, String sqlstate, boolean falhaRegistrada) {
        public boolean ok() {
            return classe == Classe.OK;
        }
    }

    /** Modo de abertura (revisao 4, item 4): registro de entrada com origem comprovada, ou retorno pelo canal registrado. */
    public sealed interface Abertura permits Entrada, Retorno {
        UUID id();
    }

    public record Entrada(UUID id) implements Abertura {
    }

    public record Retorno(UUID id) implements Abertura {
    }

    /** Declaracao dos demais ORG_ADMIN na verificacao (revisao 4, item 5): somente estes dois valores. */
    public enum DemaisAdmins { NENHUM, TODOS_SEM_FATOR_E_CODIGOS_UTILIZAVEIS }

    /** Registro do retorno pelo canal registrado: o id do retorno (uso unico, 60 min) para a abertura. */
    public record RetornoRegistrado(Classe classe, String motivo, UUID retorno, String sqlstate) {
    }

    /** Espera maxima por locks em cada ato (a consulta de reconciliacao tem o proprio limite, no banco). */
    static final String LOCK_TIMEOUT = "30s";

    private static final Map<String, String> ESTADO_APOS = Map.of(
            "VERIFICAR", "VERIFICADA", "PRE_APROVAR", "PRE_APROVADA", "APROVAR", "APROVADA", "EXECUTAR", "EXECUTADA",
            "CANCELAR", "CANCELADA");
    private static final Map<String, String> ESTADO_ANTES = Map.of(
            "VERIFICAR", "SOLICITADA", "PRE_APROVAR", "VERIFICADA", "APROVAR", "PRE_APROVADA", "EXECUTAR", "APROVADA");

    private final Conexoes conexoes;
    private final LogLocal log;

    public ExecutorAtos(Conexoes conexoes, LogLocal log) {
        this.conexoes = Objects.requireNonNull(conexoes);
        this.log = Objects.requireNonNull(log);
    }

    // ------------------------------------------------------------------ atos

    public Resultado abrir(UUID org, UUID alvo, UUID contatoSolicitante, Abertura abertura) {
        return rodar("ABRIR", null, "SELECT r_resultado, r_motivo, r_solicitacao, r_estado FROM public.ops_mfa_open(?, ?, ?, ?, ?)", ps -> {
            ps.setObject(1, org);
            ps.setObject(2, alvo);
            ps.setObject(3, contatoSolicitante);
            ps.setObject(4, abertura instanceof Entrada e ? e.id() : null);
            ps.setObject(5, abertura instanceof Retorno r ? r.id() : null);
        });
    }

    /**
     * Registra o retorno feito pelo canal registrado do contato (o verificador liga ou escreve e registra logo depois).
     * Sem efeito irreversivel: numa falha ou resposta perdida, o operador registra outro retorno.
     */
    public RetornoRegistrado registrarRetorno(UUID contato) {
        Connection c;
        try {
            c = abrirConexao();
        } catch (SQLException e) {
            log.registrar("REGISTRAR_RETORNO", null, "FALHA_CONFIRMADA", "CONEXAO_NAO_ESTABELECIDA", e.getSQLState(), "CONEXAO");
            return new RetornoRegistrado(Classe.FALHA_CONFIRMADA, "CONEXAO_NAO_ESTABELECIDA", null, e.getSQLState());
        }
        try (c) {
            c.setAutoCommit(false);
            String resultado;
            String motivo;
            UUID id;
            try (PreparedStatement ps = c.prepareStatement("SELECT r_resultado, r_motivo, r_retorno FROM public.ops_mfa_callback_register(?)")) {
                ps.setObject(1, contato);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    resultado = rs.getString(1);
                    motivo = rs.getString(2);
                    id = rs.getObject(3, UUID.class);
                }
            }
            c.commit();
            Classe classe = "OK".equals(resultado) ? Classe.OK : Classe.RECUSA;
            log.registrar("REGISTRAR_RETORNO", null, classe.name(), motivo, null, null);
            return new RetornoRegistrado(classe, motivo, id, null);
        } catch (SQLException e) {
            Classe classe = conexaoPerdida(e) ? Classe.DESCONHECIDO : Classe.FALHA_CONFIRMADA;
            log.registrar("REGISTRAR_RETORNO", null, classe.name(), "FALHA_TECNICA", e.getSQLState(), null);
            return new RetornoRegistrado(classe, "FALHA_TECNICA", null, e.getSQLState());
        }
    }

    public Resultado verificar(UUID solicitacao, boolean retornoOk, UUID segundoContato, boolean segundoOk, DemaisAdmins demais) {
        return rodar("VERIFICAR", solicitacao, "SELECT r_resultado, r_motivo, r_solicitacao, r_estado FROM public.ops_mfa_verify(?, ?, ?, ?, ?)", ps -> {
            ps.setObject(1, solicitacao);
            ps.setBoolean(2, retornoOk);
            ps.setObject(3, segundoContato);
            ps.setBoolean(4, segundoOk);
            ps.setString(5, demais == null ? null : demais.name());
        });
    }

    public Resultado preAprovar(UUID solicitacao) {
        return porSolicitacao("PRE_APROVAR", "ops_mfa_preapprove", solicitacao);
    }

    public Resultado aprovar(UUID solicitacao) {
        return porSolicitacao("APROVAR", "ops_mfa_final_approve", solicitacao);
    }

    public Resultado executar(UUID solicitacao) {
        return porSolicitacao("EXECUTAR", "ops_mfa_execute", solicitacao);
    }

    /** Reenvio a um destinatario notificado (titular ou outro ORG_ADMIN), enquanto a solicitacao nao for terminal. */
    public Resultado reenviarAviso(UUID solicitacao, UUID destinatario) {
        return rodar("REENVIAR_AVISO", solicitacao, "SELECT r_resultado, r_motivo, r_solicitacao, r_estado FROM public.ops_mfa_resend_notice(?, ?)", ps -> {
            ps.setObject(1, solicitacao);
            ps.setObject(2, destinatario);
        });
    }

    public Resultado cancelar(UUID solicitacao, String motivo) {
        return rodar("CANCELAR", solicitacao, "SELECT r_resultado, r_motivo, r_solicitacao, r_estado FROM public.ops_mfa_cancel(?, ?)", ps -> {
            ps.setObject(1, solicitacao);
            ps.setString(2, motivo);
        });
    }

    /** Consulta com a trava da solicitacao (so leitura; tambem usada na reconciliacao). */
    public Resultado consultar(UUID solicitacao) {
        try (Connection c = abrirConexao()) {
            c.setAutoCommit(true);
            Status s = status(c, solicitacao);
            if ("TRAVA_NAO_OBTIDA".equals(s.motivo)) {
                // contrato 7 (item 7): desconhecido, log local E alerta - tambem na consulta avulsa
                log.registrar("CONSULTAR", solicitacao, "DESCONHECIDO", "TRAVA_NAO_OBTIDA", null, null);
                log.alerta("CONSULTAR", solicitacao, "RESULTADO_DESCONHECIDO");
                return new Resultado(solicitacao, Classe.DESCONHECIDO, "TRAVA_NAO_OBTIDA", null, Origem.BANCO, null, false);
            }
            Classe classe = "OK".equals(s.resultado) ? Classe.OK : Classe.RECUSA;
            log.registrar("CONSULTAR", solicitacao, classe.name(), s.motivo, null, null);
            return new Resultado(solicitacao, classe, s.motivo, s.estado, Origem.BANCO, null, false);
        } catch (SQLException e) {
            log.registrar("CONSULTAR", solicitacao, "DESCONHECIDO", "CONSULTA_IMPOSSIVEL", e.getSQLState(), null);
            log.alerta("CONSULTAR", solicitacao, "RESULTADO_DESCONHECIDO");
            return new Resultado(solicitacao, Classe.DESCONHECIDO, "CONSULTA_IMPOSSIVEL", null, Origem.CLI, e.getSQLState(), false);
        }
    }

    // ------------------------------------------------------------------ nucleo

    /**
     * Toda conexao da CLI fixa READ COMMITTED: as funcoes da 016 recusam outro isolamento (SQLSTATE 25N01), porque um
     * snapshot anterior aos locks esconderia revogacoes de certificado ou de habilitacao. Fixar aqui evita depender do
     * {@code default_transaction_isolation} do servidor.
     */
    private Connection abrirConexao() throws SQLException {
        Connection c = conexoes.abrir();
        try {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    @FunctionalInterface
    private interface Parametros {
        void aplicar(PreparedStatement ps) throws SQLException;
    }

    private Resultado porSolicitacao(String ato, String funcao, UUID solicitacao) {
        return rodar(ato, solicitacao, "SELECT r_resultado, r_motivo, r_solicitacao, r_estado FROM public." + funcao + "(?)",
                ps -> ps.setObject(1, solicitacao));
    }

    private Resultado rodar(String ato, UUID solicitacao, String sql, Parametros parametros) {
        Connection c;
        try {
            c = abrirConexao();
        } catch (SQLException e) {
            // nada foi enviado: sem efeito possivel
            log.registrar(ato, solicitacao, "FALHA_CONFIRMADA", "CONEXAO_NAO_ESTABELECIDA", e.getSQLState(), "CONEXAO");
            return new Resultado(solicitacao, Classe.FALHA_CONFIRMADA, "CONEXAO_NAO_ESTABELECIDA", null, Origem.CLI, e.getSQLState(), false);
        }
        try (c) {
            String resultado;
            String motivo;
            String estado;
            UUID id;
            try {
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    st.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'");
                }
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    parametros.aplicar(ps);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        resultado = rs.getString(1);
                        motivo = rs.getString(2);
                        id = rs.getObject(3, UUID.class);
                        estado = rs.getString(4);
                    }
                }
            } catch (SQLException e) {
                if (conexaoPerdida(e)) {
                    return reconciliar(ato, solicitacao, "CHAMADA");
                }
                reverter(c);
                return falhaConfirmada(ato, solicitacao, e.getSQLState(), "CHAMADA");
            }
            try {
                c.commit();
            } catch (SQLException e) {
                if (conexaoPerdida(e)) {
                    return reconciliar(ato, solicitacao == null ? id : solicitacao, "COMMIT");
                }
                return falhaConfirmada(ato, solicitacao == null ? id : solicitacao, e.getSQLState(), "COMMIT");
            }
            Classe classe = "OK".equals(resultado) ? Classe.OK : Classe.RECUSA;
            log.registrar(ato, id != null ? id : solicitacao, classe.name(), motivo, null, null);
            return new Resultado(id != null ? id : solicitacao, classe, motivo, estado, Origem.BANCO, null, false);
        } catch (SQLException e) {
            // falha ao fechar depois do desfecho ja apurado nao muda o desfecho; so chega aqui antes dele
            return reconciliar(ato, solicitacao, "CHAMADA");
        }
    }

    private Resultado falhaConfirmada(String ato, UUID solicitacao, String sqlstate, String etapa) {
        String estado = sqlstate != null && sqlstate.matches("^[0-9A-Z]{5}$") ? sqlstate : "XX000";
        boolean gravada = false;
        if (solicitacao != null) {
            try (Connection c = abrirConexao()) {
                c.setAutoCommit(true);
                try (PreparedStatement ps = c.prepareStatement("SELECT r_resultado FROM public.ops_mfa_record_failure(?, ?, ?, ?)")) {
                    ps.setObject(1, solicitacao);
                    ps.setString(2, ato);
                    ps.setString(3, estado);
                    ps.setString(4, etapa);
                    try (ResultSet rs = ps.executeQuery()) {
                        gravada = rs.next() && "OK".equals(rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                gravada = false;
            }
        }
        log.registrar(ato, solicitacao, "FALHA_CONFIRMADA", gravada ? "FALHA_REGISTRADA" : "REGISTRO_INDISPONIVEL", estado, etapa);
        if (!gravada) {
            log.alerta(ato, solicitacao, "REGISTRO_SEPARADO_INDISPONIVEL");
        }
        return new Resultado(solicitacao, Classe.FALHA_CONFIRMADA, gravada ? "FALHA_REGISTRADA" : "REGISTRO_INDISPONIVEL", null, Origem.CLI, estado, gravada);
    }

    /** Resultado desconhecido: consulta com a trava; nunca presume rollback nem registra "falhou". */
    private Resultado reconciliar(String ato, UUID solicitacao, String etapa) {
        if (solicitacao == null) {
            log.registrar(ato, null, "DESCONHECIDO", "SEM_SOLICITACAO_PARA_CONSULTAR", null, etapa);
            log.alerta(ato, null, "RESULTADO_DESCONHECIDO");
            return new Resultado(solicitacao, Classe.DESCONHECIDO, "SEM_SOLICITACAO_PARA_CONSULTAR", null, Origem.CLI, null, false);
        }
        Status s;
        try (Connection c = abrirConexao()) {
            c.setAutoCommit(true);
            s = status(c, solicitacao);
        } catch (SQLException e) {
            s = null;
        }
        if (s == null || !"OK".equals(s.resultado)) {
            String motivo = s == null ? "CONSULTA_IMPOSSIVEL" : s.motivo;
            log.registrar(ato, solicitacao, "DESCONHECIDO", motivo, null, etapa);
            log.alerta(ato, solicitacao, "RESULTADO_DESCONHECIDO");
            return new Resultado(solicitacao, Classe.DESCONHECIDO, motivo, null, Origem.CLI, null, false);
        }
        Resultado r;
        if ("EXECUTAR".equals(ato)) {
            if ("EXECUTADA".equals(s.estado) && s.resetRegistrado) {
                r = new Resultado(solicitacao, Classe.OK, null, s.estado, Origem.RECONCILIADO, null, false);
            } else if ("APROVADA".equals(s.estado) && !s.resetRegistrado) {
                r = new Resultado(solicitacao, Classe.FALHA_CONFIRMADA, "NAO_EXECUTADA_CONFIRMADO_AGORA", s.estado, Origem.RECONCILIADO, null, false);
            } else if (!s.resetRegistrado && s.encerramento != null) {
                r = new Resultado(solicitacao, Classe.RECUSA, s.encerramento, s.estado, Origem.RECONCILIADO, null, false);
            } else {
                r = new Resultado(solicitacao, Classe.DESCONHECIDO, "ESTADO_INCONSISTENTE", s.estado, Origem.RECONCILIADO, null, false);
            }
        } else if (s.estado != null && s.estado.equals(ESTADO_APOS.get(ato))) {
            r = new Resultado(solicitacao, Classe.OK, null, s.estado, Origem.RECONCILIADO, null, false);
        } else if (s.estado != null && s.estado.equals(ESTADO_ANTES.get(ato))) {
            r = new Resultado(solicitacao, Classe.FALHA_CONFIRMADA, "NAO_APLICADO_CONFIRMADO_AGORA", s.estado, Origem.RECONCILIADO, null, false);
        } else if (s.encerramento != null) {
            r = new Resultado(solicitacao, Classe.RECUSA, s.encerramento, s.estado, Origem.RECONCILIADO, null, false);
        } else {
            r = new Resultado(solicitacao, Classe.DESCONHECIDO, "ESTADO_AMBIGUO", s.estado, Origem.RECONCILIADO, null, false);
        }
        log.registrar(ato, solicitacao, r.classe().name(), r.motivo() == null ? "RECONCILIADO" : r.motivo(), null, etapa);
        if (r.classe() == Classe.DESCONHECIDO) {
            log.alerta(ato, solicitacao, "RESULTADO_DESCONHECIDO");
        }
        return r;
    }

    private record Status(String resultado, String motivo, String estado, boolean resetRegistrado, String encerramento) {
    }

    /** Resposta de {@code ops_mfa_status}; {@code TRAVA_NAO_OBTIDA} = sem resposta utilizavel. */
    private static Status status(Connection c, UUID solicitacao) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT r_resultado, r_motivo, r_estado, r_reset_registrado, r_encerramento FROM public.ops_mfa_status(?)")) {
            ps.setObject(1, solicitacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Status(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5));
            }
        }
    }

    static boolean conexaoPerdida(SQLException e) {
        String s = e.getSQLState();
        if (s != null && s.startsWith("08")) {
            return true;
        }
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private static void reverter(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignorada) {
            // o servidor ja reverteu a transacao com erro; fechar a conexao encerra o resto
        }
    }
}
