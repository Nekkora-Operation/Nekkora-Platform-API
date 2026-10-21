package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.platform.mail.MailSender;

/**
 * Conclusao do MFA x reset administrativo de conta NAO vinculada, com a ordem fixada por barreira (missao
 * API-FECHAMENTO-001). A conta do alvo fica travada por uma transacao do superusuario; a primeira requisicao e disparada
 * e a espera real dela pelo lock e observada em {@code pg_stat_activity}; a segunda entra na fila atras; liberada a conta,
 * as duas concluem nessa ordem. Nada depende de pausa arbitraria nem de repeticao.
 *
 * <p>O terceiro cenario reproduz o mecanismo do SQLSTATE 23514: a sessao nascida da conclusao do MFA com
 * {@code created_at} A FRENTE do relogio do banco no instante do reset - o estado que um passo para tras do relogio
 * (VM do Docker Desktop ressincronizada com o host) produz entre as duas transacoes. A revogacao gravava
 * {@code clock_timestamp()} e a CHECK {@code revoked_at >= created_at} da 002 recusava o reset inteiro.
 */
class MfaResetRaceIT extends PlatformTestBase {

    private record Cenario(Org org, BrowserClient admin, TestUser alvo, BrowserClient pendente, String codigo) {
        String base() {
            return "/api/v1/o/" + org.id();
        }
    }

    private Cenario cenario() {
        Org o = newOrganization();
        TestUser adm = new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray());
        BrowserClient a = browser();
        firstLogin(a, o.id(), adm);
        assertThat(a.post("/api/v1/o/" + o.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(adm.id()) + "\"}").status()).isEqualTo(204);
        TestUser u = internalUser(o, true, 2);
        firstLogin(browser(), o.id(), u);
        BrowserClient p = browser();
        assertThat(passwordLogin(p, o.id(), u).field("status")).isEqualTo("mfa_required");
        return new Cenario(o, a, u, p, code(u.id()));
    }

    /**
     * Pelo proprio BrowserClient (prazo de 30 s, maior que a espera da barreira): a sessao emitida fica nos cookies, e as
     * conferencias de {@code /me} depois do ato provam que ESSA sessao deixou de valer (nao so que faltou o cookie).
     */
    private Callable<BrowserClient.Response> verificar(Cenario c) {
        return () -> {
            BrowserClient.Response r = c.pendente().post(c.base() + "/auth/mfa/verify", "{\"code\":\"" + c.codigo() + "\"}");
            if (r.status() == 200) {
                c.pendente().csrf = r.field("csrfToken");
            }
            return r;
        };
    }

    private Callable<BrowserClient.Response> resetar(Cenario c) {
        return () -> Barreiras.postLongo(port, c.admin(), c.base() + "/users/" + c.alvo().id() + "/mfa/reset", SessionsAndAdministrationIT.RESET_BODY);
    }

    /** Conta do alvo travada; {@code primeiro} dispara e espera; {@code segundo} dispara e espera atras; liberada, concluem nessa ordem. */
    private BrowserClient.Response[] emOrdem(Cenario c, Callable<BrowserClient.Response> primeiro, Callable<BrowserClient.Response> segundo) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try (Connection l = DB.connectAsSuperuser()) {
            l.setAutoCommit(false);
            Barreiras.sql(l, "SELECT 1 FROM users WHERE user_id = '" + c.alvo().id() + "' FOR UPDATE");
            Future<BrowserClient.Response> a = ex.submit(primeiro);
            assertThat(IdentityTestBase.platformWaitingOnLock(1)).as("o primeiro espera a conta").isTrue();
            Future<BrowserClient.Response> b = ex.submit(segundo);
            assertThat(IdentityTestBase.platformWaitingOnLock(2)).as("o segundo espera atras dele").isTrue();
            l.commit();
            return new BrowserClient.Response[] {a.get(), b.get()};
        } finally {
            ex.shutdownNow();
        }
    }

    /** Ato administrativo sem 204: as linhas de erro do PostgreSQL de teste (SQLSTATE, restricao), sanitizadas, vao para a saida do teste. */
    private static void diagnostico(BrowserClient.Response ato) {
        if (ato.status() != 204) {
            DiagnosticoPg.imprimir(DB, "ato administrativo " + ato.status());
        }
    }

    private static long count(String sql) throws Exception {
        return AuthenticationFlowIT.count(sql);
    }

    /** Efeitos do reset no contrato (§3.4): sem sessao, desafio, fator nem codigo vivos; um evento com motivo; aviso ao titular. */
    private void efeitosDoReset(Cenario c) throws Exception {
        UUID u = c.alvo().id();
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u + "' AND revoked_at IS NULL")).as("nenhuma sessao ativa").isZero();
        assertThat(count("SELECT count(*) FROM auth_challenges WHERE user_id = '" + u + "' AND consumed_at IS NULL AND invalidated_at IS NULL"))
                .as("nenhum desafio pendente").isZero();
        assertThat(count("SELECT count(*) FROM user_mfa_factors WHERE user_id = '" + u + "' AND disabled_at IS NULL")).as("fator desabilitado").isZero();
        assertThat(count("SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = '" + u + "' AND used_at IS NULL AND invalidated_at IS NULL"))
                .as("codigos de recuperacao invalidados").isZero();
        assertThat(count("SELECT count(*) FROM mfa_events WHERE user_id = '" + u + "' AND event_type = 3 AND reason_code = 1"))
                .as("um evento de reset, com o motivo").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u + "' AND revoked_at < created_at")).isZero();
        assertThat(testMail.sent.stream().filter(m -> m.kind() == MailSender.Kind.NOTICE_MFA_RESET && c.alvo().email().equals(m.recipient())).count())
                .as("aviso ao titular").isEqualTo(1);
        assertThat(c.pendente().get(c.base() + "/me").status()).as("a sessao nascida do MFA nao sobrevive").isEqualTo(401);
    }

    /** Ordem 1: a conclusao do MFA obtem a conta primeiro e cria a sessao; o reset, depois dela, revoga essa sessao. */
    @Test
    void conclusaoDoMfaPrimeiroResetDepois() throws Exception {
        Cenario c = cenario();
        BrowserClient.Response[] r = emOrdem(c, verificar(c), resetar(c));
        diagnostico(r[1]);
        assertThat(r[0].status()).as("conclusao do MFA").isEqualTo(200);
        assertThat(r[1].status()).as("reset depois dela").isEqualTo(204);
        assertThat(c.pendente().cookies()).as("a sessao do MFA foi emitida ao navegador").containsKey("__Host-nekkora_session");
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + c.alvo().id() + "'")).as("a sessao do MFA nasceu").isEqualTo(2);
        efeitosDoReset(c);
    }

    /** Ordem 2: o reset obtem a conta primeiro; a conclusao, depois dele, encontra o desafio invalidado: 401, sem sessao nova. */
    @Test
    void resetPrimeiroConclusaoDoMfaDepois() throws Exception {
        Cenario c = cenario();
        BrowserClient.Response[] r = emOrdem(c, resetar(c), verificar(c));
        diagnostico(r[0]);
        assertThat(r[0].status()).as("reset").isEqualTo(204);
        assertThat(r[1].status()).as("conclusao depois do reset").isEqualTo(401);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + c.alvo().id() + "'")).as("nenhuma sessao nova").isEqualTo(1);
        efeitosDoReset(c);
    }

    /**
     * Mecanismo do 23514: a conclusao do MFA confirma a sessao; antes do reset, o relogio do banco fica ATRAS do
     * {@code created_at} dela. Simulado aqui movendo o {@code created_at} para a frente (superusuario, so a linha da
     * sessao; triggers desligadas na conexao do teste, CHECKs valendo), porque o relogio do container nao pode voltar
     * dentro do teste. O reset precisa concluir com todos os efeitos - antes da correcao, respondia 500 (23514).
     */
    @Test
    void resetRevogaSessaoCriadaAFrenteDoRelogioDoBanco() throws Exception {
        Cenario c = cenario();
        assertThat(verificar(c).call().status()).as("conclusao do MFA").isEqualTo(200);
        assertThat(c.pendente().get(c.base() + "/me").status()).as("a sessao do MFA vale antes do ato").isEqualTo(200);
        sessaoAFrenteDoRelogio(c);
        BrowserClient.Response r = resetar(c).call();
        diagnostico(r);
        assertThat(r.status()).as("reset com a sessao a frente do relogio").isEqualTo(204);
        efeitosDoReset(c);
    }
    /**
     * Controle da mesma classe de defeito em outro caminho que revoga sessoes: a suspensao (org.manage), com a sessao
     * nascida do MFA a frente do relogio do banco. Mostra que a recusa nao depende do reset nem da leitura do vinculo
     * acrescentada na revisao 1.
     */
    @Test
    void suspensaoRevogaSessaoCriadaAFrenteDoRelogioDoBanco() throws Exception {
        Cenario c = cenario();
        assertThat(verificar(c).call().status()).as("conclusao do MFA").isEqualTo(200);
        assertThat(c.pendente().get(c.base() + "/me").status()).as("a sessao do MFA vale antes do ato").isEqualTo(200);
        sessaoAFrenteDoRelogio(c);
        BrowserClient.Response r = Barreiras.postLongo(port, c.admin(), c.base() + "/users/" + c.alvo().id() + "/suspend", "");
        diagnostico(r);
        assertThat(r.status()).as("suspensao com a sessao a frente do relogio").isEqualTo(204);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + c.alvo().id() + "' AND revoked_at IS NULL")).isZero();
        assertThat(c.pendente().get(c.base() + "/me").status()).isEqualTo(401);
    }

    /** Mesma classe de defeito no logout (revogacao pelo token): a sessao a frente do relogio e encerrada, sem 500. */
    @Test
    void logoutDeSessaoCriadaAFrenteDoRelogioDoBanco() throws Exception {
        Cenario c = cenario();
        assertThat(verificar(c).call().status()).as("conclusao do MFA").isEqualTo(200);
        assertThat(c.pendente().get(c.base() + "/me").status()).as("a sessao do MFA vale antes do ato").isEqualTo(200);
        sessaoAFrenteDoRelogio(c);
        BrowserClient.Response r = c.pendente().post(c.base() + "/auth/logout", null);
        diagnostico(r);
        assertThat(r.status()).as("logout com a sessao a frente do relogio").isEqualTo(204);
        assertThat(c.pendente().get(c.base() + "/me").status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + c.alvo().id() + "' AND revoked_at IS NULL")).as("so a do primeiro login").isEqualTo(1);
    }

    /**
     * Mesma classe no limite de sessoes por conta: com o limite (10) atingido por sessoes a frente do relogio, a conclusao
     * do MFA revoga a mais antiga delas antes de criar a nova. Sem a protecao, a revogacao recusava e o login inteiro falhava.
     */
    @Test
    void limiteDeSessoesRevogaSessaoCriadaAFrenteDoRelogioDoBanco() throws Exception {
        Cenario c = cenario();
        UUID u = c.alvo().id();
        try (Connection s = DB.connectAsSuperuser()) {
            Barreiras.sql(s, "SET session_replication_role = replica");
            Barreiras.sql(s, "INSERT INTO user_sessions (organization_id, user_id, session_token_hash, created_at, expires_at, last_seen_at, mfa_verified_at, csrf_token)"
                    + " SELECT '" + c.org().id() + "', '" + u + "', encode(sha256(convert_to(gen_random_uuid()::text, 'UTF8')), 'hex'), t, t + interval '1 hour', t, t,"
                    + " rpad(replace(gen_random_uuid()::text, '-', ''), 43, 'x') FROM (SELECT clock_timestamp() + interval '1 minute' AS t) x, generate_series(1, 9)");
            Barreiras.sql(s, "UPDATE user_sessions SET created_at = clock_timestamp() + interval '30 seconds', last_seen_at = clock_timestamp() + interval '30 seconds'"
                    + " WHERE user_id = '" + u + "' AND created_at < clock_timestamp()");
        }
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u + "' AND revoked_at IS NULL AND created_at > clock_timestamp()"))
                .as("limite atingido, todas a frente do relogio").isEqualTo(10);
        BrowserClient.Response r = verificar(c).call();
        if (r.status() != 200) {
            DiagnosticoPg.imprimir(DB, "conclusao do MFA " + r.status());
        }
        assertThat(r.status()).as("conclusao do MFA no limite").isEqualTo(200);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u + "' AND revoked_at IS NULL")).as("no maximo 10").isEqualTo(10);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u + "' AND revoked_at IS NOT NULL")).as("a mais antiga revogada").isEqualTo(1);
        assertThat(c.pendente().get(c.base() + "/me").status()).isEqualTo(200);
    }

    /** A sessao mais nova do alvo (a do MFA) passa a ter created_at 1 min a frente do relogio do banco. */
    private static void sessaoAFrenteDoRelogio(Cenario c) throws Exception {
        try (Connection s = DB.connectAsSuperuser()) {
            Barreiras.sql(s, "SET session_replication_role = replica");
            Barreiras.sql(s, "UPDATE user_sessions SET created_at = clock_timestamp() + interval '1 minute', last_seen_at = clock_timestamp() + interval '1 minute'"
                    + " WHERE user_id = '" + c.alvo().id() + "' AND revoked_at IS NULL AND created_at = (SELECT max(created_at) FROM user_sessions WHERE user_id = '"
                    + c.alvo().id() + "')");
        }
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + c.alvo().id() + "' AND created_at > clock_timestamp()"))
                .as("sessao a frente do relogio").isEqualTo(1);
    }
}
