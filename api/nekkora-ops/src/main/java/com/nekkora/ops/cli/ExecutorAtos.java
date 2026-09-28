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

    public Resultado abrir(UUID org, UUID alvo, UUID contatoSolicitante) {
        return rodar("ABRIR", null, "SELECT r_resultado, r_motivo, r_solicitacao, r_estado FROM public.ops_mfa_open(?, ?, ?)", ps -> {
            ps.setObject(1, org);
            ps.setObject(2, alvo);
            ps.setObject(3, contatoSolicitante);
        });
    }

    public Resultado verificar(UUID solicitacao, boolean retornoOk, UUID segundoContato, boolean segundoOk) {
        return rodar("VERIFICAR", solicitacao, "SELECT r_resultado, r_motivo, r_solicitacao, r_estado FROM public.ops_mfa_verify(?, ?, ?, ?)", ps -> {
            ps.setObject(1, solicitacao);
            ps.setBoolean(2, retornoOk);
            ps.setObject(3, segundoContato);
            ps.setBoolean(4, segundoOk);
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

    public Resultado reenviarAviso(UUID solicitacao) {
        return porSolicitacao("REENVIAR_AVISO", "ops_mfa_resend_notice", solicitacao);
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
                log.registrar("CONSULTAR", solicitacao, "DESCONHECIDO", "TRAVA_NAO_OBTIDA", null, null);
                return new Resultado(solicitacao, Classe.DESCONHECIDO, "TRAVA_NAO_OBTIDA", null, Origem.BANCO, null, false);
            }
            Classe classe = "OK".equals(s.resultado) ? Classe.OK : Classe.RECUSA;
            log.registrar("CONSULTAR", solicitacao, classe.name(), s.motivo, null, null);
            return new Resultado(solicitacao, classe, s.motivo, s.estado, Origem.BANCO, null, false);
        } catch (SQLException e) {
            log.registrar("CONSULTAR", solicitacao, "DESCONHECIDO", "CONSULTA_IMPOSSIVEL", e.getSQLState(), null);
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
