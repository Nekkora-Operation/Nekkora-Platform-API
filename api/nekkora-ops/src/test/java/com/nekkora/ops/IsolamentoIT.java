package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.Credencial;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Isolamento (achado da revisao). Em REPEATABLE READ ou SERIALIZABLE o snapshot fica fixo no primeiro comando da
 * transacao: uma revogacao de certificado ou de habilitacao confirmada DEPOIS dele seria invisivel as conferencias do
 * ato, mesmo com os locks obtidos. A 016 recusa qualquer isolamento que nao seja READ COMMITTED, como PRIMEIRA
 * instrucao de toda funcao publica (SQLSTATE 25N01), sem efeito. Controle positivo: o mesmo cenario em READ COMMITTED
 * e recusado pela conferencia do ato. A contraprova por mutacao (guarda retirada = reset com credencial revogada)
 * esta em {@link MutacoesIT}.
 */
class IsolamentoIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);
    static final String ISOLAMENTO_RECUSADO = "25N01";

    static void revogarCertificado(Credencial c) throws SQLException {
        try (Connection ca = DB.comoServico("nekkora_ops_ca");
             PreparedStatement ps = ca.prepareStatement("SELECT r_resultado FROM public.ops_revoke_certificate(?, ?)")) {
            ps.setString(1, DB.emissor().emissorNoServidor());
            ps.setBigDecimal(2, new BigDecimal(c.certificado().getSerialNumber()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("OK");
            }
        }
    }

    static String[] executar(Connection con, UUID s) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT r_resultado, r_motivo FROM public.ops_mfa_execute(?)")) {
            ps.setObject(1, s);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new String[] {rs.getString(1), rs.getString(2)};
            }
        }
    }

    /** Abre a transacao no isolamento dado e FIXA o snapshot com um comando qualquer, antes da revogacao. */
    static void abrirComSnapshot(Connection con, String isolamento) throws SQLException {
        con.setAutoCommit(false);
        try (Statement st = con.createStatement()) {
            st.execute("SET TRANSACTION ISOLATION LEVEL " + isolamento);
            st.execute("SELECT 1");
        }
    }

    static void semEfeito(UUID s, Cenarios.Alvo al, String acessoAntes) {
        assertThat(C.resets(s)).as("nenhum reset").isZero();
        assertThat(C.estado(s)).as("solicitacao intacta").isEqualTo("APROVADA");
        assertThat(C.acesso(al.org().id(), al.titular())).as("fator, codigos, desafios e sessoes intactos").isEqualTo(acessoAntes);
    }

    @Test
    void certificadoRevogadoDepoisDoSnapshotNaoAutorizaEmRepeatableReadNemSerializable() throws Exception {
        for (String iso : List.of("REPEATABLE READ", "SERIALIZABLE")) {
            Cenarios.Equipe eq = C.equipe();
            Cenarios.Alvo al = C.alvo();
            UUID s = C.aprovada(eq, al);
            String acessoAntes = C.acesso(al.org().id(), al.titular());
            try (Credencial c = DB.credencial(eq.executor()); Connection con = DB.conectar(c)) {
                abrirComSnapshot(con, iso);
                revogarCertificado(c); // confirmada depois do snapshot
                assertThatThrownBy(() -> executar(con, s)).as(iso)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(ISOLAMENTO_RECUSADO));
                con.rollback();
                semEfeito(s, al, acessoAntes);
                // controle positivo: a mesma credencial revogada, em READ COMMITTED, e recusada pela conferencia do ato
                con.setAutoCommit(true);
                con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                assertThat(executar(con, s)).containsExactly("RECUSA", "CERTIFICADO_REVOGADO");
                semEfeito(s, al, acessoAntes);
            }
        }
    }

    @Test
    void habilitacaoDeParticipanteRetiradaDepoisDoSnapshotNaoAutorizaEmRepeatableRead() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        String acessoAntes = C.acesso(al.org().id(), al.titular());
        OpsBanco.Operador seguranca = DB.operador("seg", "CADASTRAR");
        try (Credencial c = DB.credencial(eq.executor()); Connection con = DB.conectar(c)) {
            abrirComSnapshot(con, "REPEATABLE READ");
            // a seguranca retira APROVAR do aprovador (participante), confirmada depois do snapshot do executor
            try (Credencial cs = DB.credencial(seguranca); Connection sc = DB.conectar(cs);
                 PreparedStatement ps = sc.prepareStatement("SELECT r_resultado FROM public.ops_operator_revoke(?, ?)")) {
                ps.setObject(1, eq.aprovador().id());
                ps.setString(2, "APROVAR");
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("OK");
                }
            }
            assertThatThrownBy(() -> executar(con, s))
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(ISOLAMENTO_RECUSADO));
            con.rollback();
            semEfeito(s, al, acessoAntes);
            // controle positivo em READ COMMITTED: o participante inabilitado recusa (e encerra) a solicitacao
            con.setAutoCommit(true);
            con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            assertThat(executar(con, s)).containsExactly("RECUSA", "PARTICIPANTE_INABILITADO");
            assertThat(C.resets(s)).isZero();
            assertThat(C.estado(s)).isEqualTo("RECUSADA");
        }
    }

    /** Isolamento padrao da sessao alterado (sem BEGIN explicito): o comando isolado tambem e recusado. */
    @Test
    void isolamentoPadraoDaSessaoTambemERecusado() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        String acessoAntes = C.acesso(al.org().id(), al.titular());
        try (Credencial c = DB.credencial(eq.executor()); Connection con = DB.conectar(c); Statement st = con.createStatement()) {
            st.execute("SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            assertThatThrownBy(() -> executar(con, s))
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(ISOLAMENTO_RECUSADO));
            st.execute("SET default_transaction_isolation = 'serializable'");
            assertThatThrownBy(() -> executar(con, s))
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(ISOLAMENTO_RECUSADO));
        }
        semEfeito(s, al, acessoAntes);
    }

    private record Funcao(String nome, String chamada, String chamador) {
    }

    /**
     * TODAS as funcoes publicas ops_* (pelo catalogo, nao por lista) recusam REPEATABLE READ e SERIALIZABLE antes de
     * qualquer outra coisa, cada uma pelo seu chamador real; em READ COMMITTED, a mesma chamada nao cai na guarda.
     */
    @Test
    void todasAsFuncoesPublicasRecusamOutroIsolamento() throws Exception {
        List<Funcao> funcoes = new ArrayList<>();
        try (Connection su = DB.superusuario(); Statement st = su.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT p.proname,
                            (SELECT string_agg('NULL::' || format_type(t, NULL), ', ' ORDER BY i)
                               FROM unnest(p.proargtypes::oid[]) WITH ORDINALITY AS a(t, i)),
                            CASE WHEN has_function_privilege('nekkora_ops_mfa', p.oid, 'EXECUTE') THEN 'operador'
                                 WHEN has_function_privilege('nekkora_ops_ca', p.oid, 'EXECUTE') THEN 'nekkora_ops_ca'
                                 WHEN has_function_privilege('nekkora_platform', p.oid, 'EXECUTE') THEN 'nekkora_platform'
                                 ELSE 'superusuario' END
                       FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                      WHERE n.nspname = 'public' AND p.proname LIKE 'ops\\_%' ORDER BY 1""")) {
            while (rs.next()) {
                String args = rs.getString(2) == null ? "" : rs.getString(2);
                funcoes.add(new Funcao(rs.getString(1), "SELECT * FROM public." + rs.getString(1) + "(" + args + ")", rs.getString(3)));
            }
        }
        assertThat(funcoes).hasSize(29);
        OpsBanco.Operador op = DB.operador("iso", "CONSULTAR");
        try (Credencial c = DB.credencial(op)) {
            for (Funcao f : funcoes) {
                for (String iso : List.of("REPEATABLE READ", "SERIALIZABLE", "READ COMMITTED")) {
                    try (Connection con = conexao(f.chamador(), c)) {
                        con.setAutoCommit(false);
                        try (Statement st = con.createStatement()) {
                            st.execute("SET TRANSACTION ISOLATION LEVEL " + iso);
                            String estado = null;
                            try {
                                st.execute(f.chamada());
                            } catch (SQLException e) {
                                estado = e.getSQLState();
                            }
                            if (iso.equals("READ COMMITTED")) {
                                assertThat(estado).as("%s em READ COMMITTED nao cai na guarda", f.nome()).isNotEqualTo(ISOLAMENTO_RECUSADO);
                            } else {
                                assertThat(estado).as("%s em %s", f.nome(), iso).isEqualTo(ISOLAMENTO_RECUSADO);
                            }
                        } finally {
                            con.rollback();
                        }
                    }
                }
            }
        }
    }

    private static Connection conexao(String chamador, Credencial c) throws SQLException {
        return switch (chamador) {
            case "operador" -> DB.conectar(c);
            case "superusuario" -> DB.superusuario();
            default -> DB.comoServico(chamador);
        };
    }
}
