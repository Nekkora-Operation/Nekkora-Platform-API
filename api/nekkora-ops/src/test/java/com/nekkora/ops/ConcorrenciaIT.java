package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.Credencial;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Grupo Concorrencia (contrato 7 e 10): quem trava primeiro vence. A "execucao primeiro" e reproduzida segurando a
 * transacao da execucao aberta DEPOIS da funcao retornar (locks mantidos) - a alteracao concorrente fica bloqueada
 * (conferido em pg_stat_activity) e so prossegue depois do commit. Nunca um reset com participante invalido no
 * instante do ato, nunca reset depois de cancelamento/contestacao confirmados, exatamente uma execucao.
 */
class ConcorrenciaIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    /** Ato numa transacao que fica aberta ate {@link #commit()}. */
    static final class Aberta implements AutoCloseable {
        final Credencial cred;
        final Connection con;

        Aberta(OpsBanco.Operador o) throws SQLException {
            cred = DB.credencial(o);
            con = DB.conectar(cred);
            con.setAutoCommit(false);
        }

        String[] chamar(String funcao, UUID s) throws SQLException {
            try (PreparedStatement ps = con.prepareStatement("SELECT r_resultado, r_motivo FROM public." + funcao + "(?)")) {
                ps.setObject(1, s);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new String[] {rs.getString(1), rs.getString(2)};
                }
            }
        }

        void commit() throws SQLException {
            con.commit();
        }

        @Override
        public void close() throws SQLException {
            con.close();
            cred.close();
        }
    }

    @Test
    void retiradaDeHabilitacaoConfirmadaAntesRecusaAExecucao() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        assertThat(DB.cadastro("SELECT r_resultado FROM public.ops_operator_revoke(?, 'VERIFICAR')", String.class, eq.verificador().id())).isEqualTo("OK");
        ExecutorAtos.Resultado r = eq.e().executar(s);
        assertThat(r.motivo()).isEqualTo("PARTICIPANTE_INABILITADO");
        assertThat(C.estado(s)).isEqualTo("RECUSADA");
        assertThat(C.resets(s)).isZero();
        assertThat(C.acesso(al.org().id(), al.titular())).isEqualTo("1|2|1|1");
    }

    @Test
    void execucaoQueTravaPrimeiroValeEARetiradaEsperaOCommit() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        try (Aberta exe = new Aberta(eq.executor())) {
            assertThat(exe.chamar("ops_mfa_execute", s)).containsExactly("OK", null);
            CompletableFuture<String> retirada = CompletableFuture.supplyAsync(() ->
                    DB.cadastro("SELECT r_resultado FROM public.ops_operator_revoke(?, 'VERIFICAR')", String.class, eq.verificador().id()));
            CertificadosIT.esperarBloqueado(DB.bootstrap().papel());
            assertThat(retirada).isNotDone();
            exe.commit();
            assertThat(retirada.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        }
        assertThat(C.resets(s)).isEqualTo(1);
        assertThat(DB.valor(Boolean.class, "SELECT p.revoked_at > r.executed_at FROM ops.ops_operator_permissions p, ops.ops_mfa_recovery_requests r "
                + "WHERE p.operator_id = ? AND p.act = 1 AND r.request_id = ?", eq.verificador().id(), s)).isTrue();
    }

    @Test
    void vinculoImpeditivoNovoConcorrenteComAExecucao() throws Exception {
        // vinculo primeiro: execucao recusada
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        assertThat(C.vincularConta(eq.aprovador(), C.provaDeConta(al.org().id(), DB.membro(al.org())))).isEqualTo("OK");
        assertThat(eq.e().executar(s).motivo()).isEqualTo("VINCULO_IMPEDITIVO");
        assertThat(C.resets(s)).isZero();

        // execucao primeiro: o vinculo espera o commit
        Cenarios.Equipe eq2 = C.equipe();
        Cenarios.Alvo al2 = C.alvo();
        UUID s2 = C.aprovada(eq2, al2);
        String prova = C.provaDeConta(al2.org().id(), DB.membro(al2.org()));
        // a credencial do aprovador e emitida ANTES: o registro de emissao tambem trava o operador e esperaria a execucao
        try (Credencial credAprovador = DB.credencial(eq2.aprovador()); Aberta exe = new Aberta(eq2.executor())) {
            assertThat(exe.chamar("ops_mfa_execute", s2)[0]).isEqualTo("OK");
            CompletableFuture<String> vinculo = CompletableFuture.supplyAsync(() -> {
                try (Connection c = DB.conectar(credAprovador);
                     PreparedStatement ps = c.prepareStatement("SELECT r_resultado FROM public.ops_link_platform_account(?)")) {
                    ps.setString(1, OpsBanco.sha256(prova));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return rs.getString(1);
                    }
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            CertificadosIT.esperarBloqueado(eq2.aprovador().papel());
            exe.commit();
            assertThat(vinculo.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        }
        assertThat(C.resets(s2)).isEqualTo(1);
    }

    @Test
    void cancelamentoEContestacaoConcorrentesNuncaPermitemResetDepois() throws Exception {
        // cancelamento primeiro
        Cenarios.Equipe eq = C.equipe();
        UUID s = C.aprovada(eq, C.alvo());
        Cenarios.exigirOk(eq.v().cancelar(s, "SUSPEITA_FRAUDE"), "cancelar");
        assertThat(eq.e().executar(s).motivo()).isEqualTo("ESTADO_INVALIDO");
        assertThat(C.resets(s)).isZero();

        // contestacao primeiro (Platform, destinatario notificado)
        Cenarios.Equipe eq2 = C.equipe();
        Cenarios.Alvo al2 = C.alvo();
        UUID admin = DB.outroAdmin(al2.org());
        UUID s2 = C.preAprovada(eq2, al2);
        List<Cenarios.Tokens> t = C.emitirAvisos(al2.org().id());
        String contestarAdmin = t.stream().filter(x -> x.destinatario().equals(admin)).findFirst().orElseThrow().contestar();
        assertThat(C.confirmar(al2.org().id(), t.stream().filter(x -> x.temConfirmar()).findFirst().orElseThrow().confirmar())).isTrue();
        DB.recuarSolicitacao(s2, Cenarios.ESPERA);
        Cenarios.exigirOk(eq2.a().aprovar(s2), "aprovar");
        assertThat(C.contestar(al2.org().id(), contestarAdmin)).isTrue();
        assertThat(eq2.e().executar(s2).motivo()).isEqualTo("ESTADO_INVALIDO");
        assertThat(C.resets(s2)).isZero();

        // execucao primeiro: cancelamento e contestacao esperam e chegam tarde
        Cenarios.Equipe eq3 = C.equipe();
        Cenarios.Alvo al3 = C.alvo();
        UUID admin3 = DB.outroAdmin(al3.org());
        UUID s3 = C.preAprovada(eq3, al3);
        List<Cenarios.Tokens> t3 = C.emitirAvisos(al3.org().id());
        String contestar3 = t3.stream().filter(x -> x.destinatario().equals(admin3)).findFirst().orElseThrow().contestar();
        assertThat(C.confirmar(al3.org().id(), t3.stream().filter(x -> x.temConfirmar()).findFirst().orElseThrow().confirmar())).isTrue();
        DB.recuarSolicitacao(s3, Cenarios.ESPERA);
        Cenarios.exigirOk(eq3.a().aprovar(s3), "aprovar");
        try (Aberta exe = new Aberta(eq3.executor())) {
            assertThat(exe.chamar("ops_mfa_execute", s3)[0]).isEqualTo("OK");
            CompletableFuture<ExecutorAtos.Resultado> cancelar = CompletableFuture.supplyAsync(() -> eq3.v().cancelar(s3, "SUSPEITA_FRAUDE"));
            CompletableFuture<Boolean> contestar = CompletableFuture.supplyAsync(() -> C.contestar(al3.org().id(), contestar3));
            CertificadosIT.esperarBloqueado(eq3.verificador().papel());
            CertificadosIT.esperarBloqueado("nekkora_platform");
            exe.commit();
            assertThat(cancelar.get(30, TimeUnit.SECONDS).motivo()).isEqualTo("ESTADO_INVALIDO");
            assertThat(contestar.get(30, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(C.estado(s3)).isEqualTo("EXECUTADA");
        assertThat(C.resets(s3)).isEqualTo(1);
    }

    @Test
    void execucoesParalelasProduzemExatamenteUmReset() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        OpsBanco.Operador e2 = DB.operador("exe2", "EXECUTAR");
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        List<ExecutorAtos> clis = List.of(eq.e(), DB.cli(e2), eq.e(), DB.cli(e2), eq.e(), DB.cli(e2));
        List<CompletableFuture<ExecutorAtos.Resultado>> fs = new ArrayList<>();
        for (ExecutorAtos cli : clis) {
            fs.add(CompletableFuture.supplyAsync(() -> cli.executar(s)));
        }
        long ok = 0;
        for (var f : fs) {
            ExecutorAtos.Resultado r = f.get(60, TimeUnit.SECONDS);
            if (r.classe() == Classe.OK) {
                ok++;
            } else {
                assertThat(r.motivo()).isEqualTo("JA_EXECUTADA");
            }
        }
        assertThat(ok).isEqualTo(1);
        assertThat(C.resets(s)).isEqualTo(1);
    }

    private static Connection plataforma(UUID org) throws SQLException {
        Connection c = DB.comoServico("nekkora_platform");
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('nekkora.organization_id', ?, true)")) {
            ps.setString(1, org.toString());
            ps.execute();
        }
        return c;
    }

    private static void travarConta(Connection c, UUID org, UUID user) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM public.users WHERE organization_id = '" + org + "' AND user_id = '" + user + "' FOR UPDATE");
        }
    }

    private static void criarSessao(Connection c, UUID org, UUID user) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("INSERT INTO public.user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES ('"
                    + org + "', '" + user + "', '" + OpsBanco.hex(32) + "', now() + interval '8 hours', now(), '" + OpsBanco.tokenBruto() + "')");
        }
    }

    @Test
    void execucaoConcorrenteComLoginDoTitular() throws Exception {
        // login primeiro (segura a conta): a execucao espera e depois revoga tambem a sessao nova
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        try (Connection login = plataforma(al.org().id())) {
            travarConta(login, al.org().id(), al.titular());
            CompletableFuture<ExecutorAtos.Resultado> f = CompletableFuture.supplyAsync(() -> eq.e().executar(s));
            CertificadosIT.esperarBloqueado(eq.executor().papel());
            criarSessao(login, al.org().id(), al.titular());
            login.commit();
            assertThat(f.get(30, TimeUnit.SECONDS).classe()).isEqualTo(Classe.OK);
        }
        assertThat(C.acesso(al.org().id(), al.titular())).isEqualTo("0|0|0|0");

        // execucao primeiro: o login espera e, sem fator ativo, nao cria sessao
        Cenarios.Equipe eq2 = C.equipe();
        Cenarios.Alvo al2 = C.alvo();
        UUID s2 = C.aprovada(eq2, al2);
        try (Aberta exe = new Aberta(eq2.executor()); Connection login = plataforma(al2.org().id())) {
            assertThat(exe.chamar("ops_mfa_execute", s2)[0]).isEqualTo("OK");
            CompletableFuture<Void> trava = CompletableFuture.runAsync(() -> {
                try {
                    travarConta(login, al2.org().id(), al2.titular());
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            CertificadosIT.esperarBloqueado("nekkora_platform");
            exe.commit();
            trava.get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> criarSessao(login, al2.org().id(), al2.titular()))
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
        }
        assertThat(C.acesso(al2.org().id(), al2.titular())).isEqualTo("0|0|0|0");
    }

    private static int resetAdministrativo(Connection c, UUID org, UUID alvo, UUID ator) throws SQLException {
        travarConta(c, org, alvo);
        int n;
        try (Statement st = c.createStatement()) {
            st.execute("UPDATE public.user_mfa_recovery_codes SET invalidated_at = clock_timestamp() WHERE user_id = '" + alvo
                    + "' AND used_at IS NULL AND invalidated_at IS NULL");
            n = st.executeUpdate("UPDATE public.user_mfa_factors SET disabled_at = clock_timestamp() WHERE user_id = '" + alvo + "' AND disabled_at IS NULL");
            if (n == 1) {
                st.execute("INSERT INTO public.mfa_events (organization_id, user_id, actor_user_id, event_type, correlation_id, reason_code) VALUES ('"
                        + org + "', '" + alvo + "', '" + ator + "', 3, gen_random_uuid(), 1)");
            }
        }
        c.commit();
        return n;
    }

    @Test
    void execucaoConcorrenteComResetAdministrativo() throws Exception {
        // reset administrativo primeiro: a execucao recusa por fator divergente
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID admin = DB.outroAdmin(al.org());
        UUID s = C.aprovada(eq, al);
        try (Connection c = plataforma(al.org().id())) {
            assertThat(resetAdministrativo(c, al.org().id(), al.titular(), admin)).isEqualTo(1);
        }
        assertThat(eq.e().executar(s).motivo()).isEqualTo("FATOR_DIVERGENTE");
        assertThat(C.resets(s)).isZero();

        // execucao primeiro: o reset administrativo espera e nao encontra fator ativo
        Cenarios.Equipe eq2 = C.equipe();
        Cenarios.Alvo al2 = C.alvo();
        UUID admin2 = DB.outroAdmin(al2.org());
        UUID s2 = C.aprovada(eq2, al2);
        try (Aberta exe = new Aberta(eq2.executor()); Connection c = plataforma(al2.org().id())) {
            assertThat(exe.chamar("ops_mfa_execute", s2)[0]).isEqualTo("OK");
            CompletableFuture<Integer> reset = CompletableFuture.supplyAsync(() -> {
                try {
                    return resetAdministrativo(c, al2.org().id(), al2.titular(), admin2);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            CertificadosIT.esperarBloqueado("nekkora_platform");
            exe.commit();
            assertThat(reset.get(30, TimeUnit.SECONDS)).isZero();
        }
        assertThat(C.resets(s2)).isEqualTo(1);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM mfa_events WHERE user_id = ? AND event_type IN (3, 4)", al2.titular())).isEqualTo(1);
    }

    /**
     * Ultima recuperacao da organizacao (limite de 3 executadas) disputada por dois alvos. Revisao 4 (item 5): cada
     * solicitacao fotografa os demais ORG_ADMIN na verificacao; por isso cada nova solicitacao e verificada DEPOIS que o
     * alvo anterior recadastrou o fator (fixture, como no primeiro login). Na disputa, a perdedora so enxerga o reset da
     * vencedora depois dos locks: o alvo vencedor, fotografado com fator, agora pode cadastrar - ADMIN_PODE_CADASTRAR,
     * conferido antes do limite. O limite continua coberto por uma solicitacao posterior, ja com a composicao estavel.
     */
    @Test
    void ultimaRecuperacaoDaOrganizacaoDisputadaPorDoisAlvos() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo base = C.alvo();
        OpsBanco.Org org = base.org();
        List<UUID> alvos = List.of(org.admin(), DB.outroAdmin(org), DB.outroAdmin(org), DB.outroAdmin(org));
        List<UUID> contatos = new ArrayList<>();
        for (UUID alvo : alvos) {
            contatos.add(alvo.equals(org.admin()) ? base.contatoTitular() : DB.contatoElegivel(org, alvo));
        }
        java.util.function.IntFunction<UUID> aprovada = i -> C.aprovada(eq, new Cenarios.Alvo(org, alvos.get(i), null, contatos.get(i), base.segundoContato()));
        for (int i = 0; i < 2; i++) {
            Cenarios.exigirOk(eq.e().executar(aprovada.apply(i)), (i + 1) + "a");
            DB.fatorConfirmado(org.id(), alvos.get(i));
        }
        UUID s2 = aprovada.apply(2);
        UUID s3 = aprovada.apply(3);
        OpsBanco.Operador e2 = DB.operador("exe2", "EXECUTAR");
        ExecutorAtos cli2 = DB.cli(e2);
        List<ExecutorAtos.Resultado> rs;
        try (Connection bloqueio = DB.superusuario()) {
            bloqueio.setAutoCommit(false);
            try (Statement st = bloqueio.createStatement()) {
                st.execute("SELECT 1 FROM organizations WHERE organization_id = '" + org.id() + "' FOR UPDATE");
            }
            CompletableFuture<ExecutorAtos.Resultado> a = CompletableFuture.supplyAsync(() -> eq.e().executar(s2));
            CompletableFuture<ExecutorAtos.Resultado> b = CompletableFuture.supplyAsync(() -> cli2.executar(s3));
            CertificadosIT.esperarBloqueado(eq.executor().papel());
            CertificadosIT.esperarBloqueado(e2.papel());
            bloqueio.commit();
            rs = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
        assertThat(rs.stream().filter(ExecutorAtos.Resultado::ok).count()).isEqualTo(1);
        assertThat(rs.stream().filter(r -> "ADMIN_PODE_CADASTRAR".equals(r.motivo())).count()).isEqualTo(1);
        int vencedor = rs.get(0).ok() ? 2 : 3;
        int perdedor = 5 - vencedor;
        DB.fatorConfirmado(org.id(), alvos.get(vencedor));
        // composicao estavel de novo: uma quarta recuperacao na organizacao esbarra no limite ja na verificacao
        Cenarios.Alvo quarto = new Cenarios.Alvo(org, alvos.get(perdedor), null, contatos.get(perdedor), base.segundoContato());
        UUID s4 = C.aberta(eq, quarto);
        assertThat(C.verificar(eq.v(), s4, base.segundoContato()).motivo()).isEqualTo("LIMITE_ORGANIZACAO");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_requests WHERE organization_id = ? AND state = 5", org.id()))
                .isEqualTo(3);
    }
}
