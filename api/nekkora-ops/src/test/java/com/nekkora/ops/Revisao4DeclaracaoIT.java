package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.ConcorrenciaIT.Aberta;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.cli.ExecutorAtos.Resultado;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Revisao 4, item 5: declaracao dos demais ORG_ADMIN na verificacao, fotografia dos considerados e reconferencia nos atos
 * seguintes, sob os locks das contas. Os "fluxos do considerado" sao reproduzidos em SQL (superusuario) na forma da
 * autenticacao da Platform: conta FOR UPDATE e depois a escrita do sinal (passo TOTP, codigo usado, sessao, troca de
 * fator). Preparacao declarada; o login real e coberto na Platform.
 */
class Revisao4DeclaracaoIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private record Cena(Cenarios.Equipe eq, Cenarios.Alvo al, UUID outro, UUID fatorOutro) {
    }

    private static Cena cena() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID b = DB.outroAdmin(al.org());
        UUID f = DB.valor(UUID.class, "SELECT factor_id FROM user_mfa_factors WHERE user_id = ? AND disabled_at IS NULL", b);
        DB.estadoDeAcesso(al.org().id(), b, f);
        return new Cena(eq, al, b, f);
    }

    private enum Sinal { PASSO_TOTP, CODIGO_USADO, SESSAO_NOVA, FATOR_TROCADO }

    /** Escrita do sinal, na forma da autenticacao: conta FOR UPDATE -> sinal. */
    private static String[] sinal(Cena c, Sinal s) {
        String conta = "SELECT 1 FROM users WHERE user_id = '" + c.outro() + "' FOR UPDATE";
        String escrita = switch (s) {
            case PASSO_TOTP -> "UPDATE user_mfa_factors SET last_used_step = coalesce(last_used_step, 0) + 100 WHERE factor_id = '" + c.fatorOutro() + "'";
            case CODIGO_USADO -> "UPDATE user_mfa_recovery_codes SET used_at = now() WHERE ctid = (SELECT ctid FROM user_mfa_recovery_codes WHERE user_id = '"
                    + c.outro() + "' AND used_at IS NULL AND invalidated_at IS NULL LIMIT 1)";
            case SESSAO_NOVA -> "INSERT INTO user_sessions (organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, "
                    + "mfa_verified_at, csrf_token) VALUES ('" + c.al().org().id() + "', '" + c.outro() + "', '" + OpsBanco.hex(32)
                    + "', clock_timestamp(), clock_timestamp() + interval '8 hours', clock_timestamp(), clock_timestamp(), '" + OpsBanco.tokenBruto().substring(0, 43) + "')";
            case FATOR_TROCADO -> "UPDATE user_mfa_factors SET disabled_at = now() WHERE factor_id = '" + c.fatorOutro() + "'; "
                    + "INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, created_at, confirmed_at) "
                    + "VALUES ('" + c.al().org().id() + "', '" + c.outro() + "', 1, '\\x" + OpsBanco.hex(36) + "', '\\x" + OpsBanco.hex(12) + "', 'k1', now(), now())";
        };
        return new String[] {conta, escrita};
    }

    private static void executar(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getSQLState() + " " + e.getMessage(), e);
        }
    }

    private static void esperarAguardando(int n) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            Long k = DB.valor(Long.class, "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()");
            if (k != null && k >= n) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("nao houve " + n + " backends aguardando lock");
    }

    private static long recusas(UUID s, String motivo) {
        return DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND result = 'RECUSA' AND reason = ?", s, motivo);
    }

    // ------------------------------------------------------------------ declaracao

    @Test
    void declaracaoEConferidaNaVerificacao() {
        Cena c = cena();
        UUID s = C.aberta(c.eq(), c.al());
        Resultado nenhum = c.eq().v().verificar(s, true, c.al().segundoContato(), true, ExecutorAtos.DemaisAdmins.NENHUM);
        assertThat(nenhum.motivo()).as("NENHUM com outro admin").isEqualTo("DECLARACAO_DIVERGENTE");
        assertThat(C.estado(s)).as("recusa so do ato").isEqualTo("SOLICITADA");
        assertThat(recusas(s, "DECLARACAO_DIVERGENTE")).isEqualTo(1);
        for (String invalida : new String[] {null, "ALGUNS", "nenhum"}) {
            assertThat(C.comoOperador(c.eq().verificador(), "SELECT r_motivo FROM public.ops_mfa_verify(?, true, ?, true, ?)", s,
                    c.al().segundoContato(), invalida)).as("declaracao %s", invalida).isEqualTo("PARAMETRO_INVALIDO");
        }
        // outro admin sem fator confirmado e ativo: pode cadastrar -> nao ha cenario C
        UUID semFator = DB.outroAdmin(c.al().org());
        DB.sql("UPDATE user_mfa_factors SET disabled_at = now() WHERE user_id = '" + semFator + "' AND disabled_at IS NULL");
        Resultado todos = c.eq().v().verificar(s, true, c.al().segundoContato(), true, ExecutorAtos.DemaisAdmins.TODOS_SEM_FATOR_E_CODIGOS_UTILIZAVEIS);
        assertThat(todos.motivo()).isEqualTo("ADMIN_PODE_CADASTRAR");
        assertThat(C.estado(s)).isEqualTo("SOLICITADA");

        // TODOS sem nenhum outro admin: divergente
        Cenarios.Equipe eq2 = C.equipe();
        Cenarios.Alvo sozinho = C.alvo();
        UUID s2 = C.aberta(eq2, sozinho);
        assertThat(eq2.v().verificar(s2, true, sozinho.segundoContato(), true, ExecutorAtos.DemaisAdmins.TODOS_SEM_FATOR_E_CODIGOS_UTILIZAVEIS).motivo())
                .isEqualTo("DECLARACAO_DIVERGENTE");
        Cenarios.exigirOk(eq2.v().verificar(s2, true, sozinho.segundoContato(), true, ExecutorAtos.DemaisAdmins.NENHUM), "NENHUM sozinho");
    }

    @Test
    void fotografiaCompletaDosConsiderados() {
        Cena c = cena();
        DB.sql("UPDATE user_mfa_factors SET last_used_step = 4242 WHERE factor_id = '" + c.fatorOutro() + "'");
        UUID s = C.verificada(c.eq(), c.al());
        List<List<Object>> linhas = DB.linhas("SELECT user_id, active_factor_id, factor_confirmed_active, totp_last_step, used_codes, active_codes, "
                + "active_sessions, captured_at = (SELECT other_admins_captured_at FROM ops.ops_mfa_recovery_requests WHERE request_id = ?) "
                + "FROM ops.ops_mfa_request_admins WHERE request_id = ?", s, s);
        assertThat(linhas).hasSize(1);
        List<Object> l = linhas.get(0);
        assertThat(l.get(0)).isEqualTo(c.outro());
        assertThat(l.get(1)).isEqualTo(c.fatorOutro());
        assertThat(l.get(2)).isEqualTo(true);
        assertThat(((Number) l.get(3)).longValue()).isEqualTo(4242L);
        assertThat(((Number) l.get(4)).longValue()).isZero();
        assertThat(((Number) l.get(5)).longValue()).isEqualTo(2L);
        assertThat(((Number) l.get(6)).longValue()).isEqualTo(1L);
        assertThat(l.get(7)).isEqualTo(true);
        assertThat(DB.valor(String.class, "SELECT other_admins_declaration || '|' || other_admins_count FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", s))
                .isEqualTo("2|1");
        // a fotografia e imutavel
        assertThat(sqlstate("UPDATE ops.ops_mfa_request_admins SET used_codes = 9 WHERE request_id = '" + s + "'")).isNotEqualTo("00000");
    }

    private static String sqlstate(String sql) {
        try (Connection c = DB.superusuario(); Statement st = c.createStatement()) {
            st.execute(sql);
            return "00000";
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    // ------------------------------------------------------------------ sinais: fluxo confirmado antes do ato

    @Test
    void sinalConfirmadoAntesDoAtoEncerraComDeclaracaoContradita() throws Exception {
        for (Sinal sn : Sinal.values()) {
            Cena c = cena();
            UUID s = C.verificada(c.eq(), c.al());
            try (Connection f = DB.superusuario()) {
                f.setAutoCommit(false);
                for (String sql : sinal(c, sn)) {
                    executar(f, sql);
                }
                f.commit();
            }
            Resultado r = c.eq().a().preAprovar(s);
            assertThat(r.motivo()).as(sn.name()).isEqualTo("DECLARACAO_CONTRADITA");
            assertThat(C.estado(s)).as(sn.name()).isEqualTo("RECUSADA");
            assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notices WHERE request_id = ?", s)).as("sem avisos").isZero();
        }
    }

    /** Considerado que perdeu o fator depois da fotografia: pode cadastrar -> terminal no ato seguinte. */
    @Test
    void consideradoSemFatorDepoisDaFotografiaEncerra() {
        Cena c = cena();
        UUID s = C.aprovada(c.eq(), c.al());
        DB.sql("UPDATE user_mfa_factors SET disabled_at = now() WHERE factor_id = '" + c.fatorOutro() + "'");
        Resultado r = c.eq().e().executar(s);
        assertThat(r.motivo()).isEqualTo("ADMIN_PODE_CADASTRAR");
        assertThat(C.estado(s)).isEqualTo("RECUSADA");
        assertThat(C.resets(s)).isZero();
    }

    // ------------------------------------------------------------------ sinais: as duas ordens com a execucao

    /** O ato segura os locks: o fluxo do considerado ESPERA, a execucao conclui, e o sinal fica depois dela. */
    @Test
    void execucaoSegurandoOsLocksFazOFluxoDoConsideradoEsperar() throws Exception {
        Cena c = cena();
        UUID s = C.aprovada(c.eq(), c.al());
        CompletableFuture<Void> fluxo;
        try (Aberta exe = new Aberta(c.eq().executor()); Connection f = DB.superusuario()) {
            assertThat(exe.chamar("ops_mfa_execute", s)[0]).isEqualTo("OK");
            f.setAutoCommit(false);
            String[] passos = sinal(c, Sinal.SESSAO_NOVA);
            fluxo = CompletableFuture.runAsync(() -> {
                for (String sql : passos) {
                    executar(f, sql);
                }
            });
            esperarAguardando(1);
            assertThat(fluxo.isDone()).as("o fluxo espera a execucao").isFalse();
            exe.commit();
            fluxo.get(30, TimeUnit.SECONDS);
            f.commit();
        }
        assertThat(C.estado(s)).isEqualTo("EXECUTADA");
        assertThat(C.resets(s)).isEqualTo(1);
    }

    /**
     * O fluxo do considerado segura a conta com o sinal ainda nao confirmado; a execucao chega e espera a conta; o fluxo
     * confirma; a execucao, ao obter os locks, VE o sinal -> DECLARACAO_CONTRADITA. (Detector da mutacao "sinais lidos
     * antes dos locks", MutacoesIT.)
     */
    @Test
    void sinalConfirmadoDuranteAEsperaDaExecucaoEVisto() throws Exception {
        assertThat(execucaoComSinalDuranteAEspera()).isEqualTo("DECLARACAO_CONTRADITA");
    }

    static String execucaoComSinalDuranteAEspera() {
        try {
            Cena c = cena();
            UUID s = C.aprovada(c.eq(), c.al());
            CompletableFuture<Resultado> exe;
            try (Connection f = DB.superusuario()) {
                f.setAutoCommit(false);
                for (String sql : sinal(c, Sinal.PASSO_TOTP)) {
                    executar(f, sql);
                }
                exe = CompletableFuture.supplyAsync(() -> c.eq().e().executar(s));
                esperarAguardando(1);
                f.commit();
            }
            Resultado r = exe.get(30, TimeUnit.SECONDS);
            if (r.ok()) {
                assertThat(C.resets(s)).isEqualTo(1);
                return "OK";
            }
            assertThat(C.resets(s)).isZero();
            return r.motivo();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ CONTAS_EM_MUDANCA

    /**
     * Papel ORG_ADMIN inserido (com responsavel: nao trava a organizacao) enquanto o ato espera a conta de um considerado:
     * o ato encontra uma conta necessaria que nao travou e e recusado sem escrever (recusa do ato); o proximo ato ve a
     * composicao alterada e encerra.
     */
    @Test
    void contaNecessariaQueApareceuDepoisDaAquisicaoRecusaOAto() throws Exception {
        Cena c = cena();
        UUID s = C.verificada(c.eq(), c.al());
        UUID novo = DB.membro(c.al().org());
        CompletableFuture<Resultado> ato;
        try (Connection f = DB.superusuario()) {
            f.setAutoCommit(false);
            executar(f, "SELECT 1 FROM users WHERE user_id = '" + c.outro() + "' FOR UPDATE");
            ato = CompletableFuture.supplyAsync(() -> c.eq().a().preAprovar(s));
            esperarAguardando(1);
            DB.sql("INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                    + "SELECT '" + c.al().org().id() + "', m.membership_id, r.role_id, 1, 1, '" + c.al().org().adminMembership() + "' "
                    + "FROM organization_memberships m, roles r WHERE m.user_id = '" + novo + "' AND r.code = 'ORG_ADMIN'");
            f.commit();
        }
        Resultado r = ato.get(30, TimeUnit.SECONDS);
        assertThat(r.motivo()).isEqualTo("CONTAS_EM_MUDANCA");
        assertThat(C.estado(s)).as("recusa so do ato").isEqualTo("VERIFICADA");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notices WHERE request_id = ?", s)).isZero();
        Resultado depois = c.eq().a().preAprovar(s);
        assertThat(depois.motivo()).isEqualTo("COMPOSICAO_ALTERADA");
        assertThat(C.estado(s)).isEqualTo("RECUSADA");
    }
}
