package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

/**
 * Corridas do MFA pela API real (processo, PostgreSQL e Redis reais, papel nekkora_platform). Cada cenario roda
 * varias vezes, com uma conta nova por rodada e as duas requisicoes liberadas juntas (CountDownLatch) mais um atraso
 * aleatorio pequeno em um dos lados, para exercitar as duas ordens de chegada. O que se afirma e o INVARIANTE no
 * estado final, qualquer que seja a ordem; a distribuicao das ordens observadas e impressa (nao e afirmada).
 *
 * <p>Complementa a suite SQL db/tests/mfa-014 (que prova as primitivas atomicas com o MESMO desafio) com os casos de
 * desafios DIFERENTES disputando o mesmo passo TOTP e o mesmo codigo de recuperacao, e com as corridas entre a
 * conclusao do MFA e troca de senha, suspensao e reset administrativo.
 */
class MfaConcurrencyIT extends PlatformTestBase {

    static final int ROUNDS = 10;
    static Org org;
    static ExecutorService pool;

    @BeforeAll
    static void fixture() {
        org = newOrganization();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void cleanUp() {
        pool.shutdownNow();
    }

    private String base() {
        return "/api/v1/o/" + org.id();
    }

    private static long count(String sql) throws Exception {
        return AuthenticationFlowIT.count(sql);
    }

    private static long activeSessions(UUID user) throws Exception {
        return count("SELECT count(*) FROM user_sessions WHERE user_id = '" + user + "' AND revoked_at IS NULL");
    }

    /**
     * Duas requisicoes liberadas juntas. Rodada par: o lado B recebe 0 a 15 ms de atraso; rodada impar: o lado A
     * recebe 0 a {@code maxDelayA} ms (para que a operacao concorrente, se for mais lenta antes do lock - troca de
     * senha faz dois Argon2 -, tambem possa chegar primeiro).
     */
    private int[] race(int round, int maxDelayA, Callable<Integer> a, Callable<Integer> b) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        int delayA = round % 2 == 1 ? ThreadLocalRandom.current().nextInt(maxDelayA + 1) : 0;
        int delayB = round % 2 == 0 ? ThreadLocalRandom.current().nextInt(16) : 0;
        Future<Integer> fa = pool.submit(() -> {
            start.await();
            Thread.sleep(delayA);
            return a.call();
        });
        Future<Integer> fb = pool.submit(() -> {
            start.await();
            Thread.sleep(delayB);
            return b.call();
        });
        start.countDown();
        return new int[] {fa.get(), fb.get()};
    }

    private BrowserClient pendingChallenge(TestUser u) {
        BrowserClient n = browser();
        BrowserClient.Response r = passwordLogin(n, org.id(), u);
        assertThat(r.field("status")).isEqualTo("mfa_required");
        return n;
    }

    private Callable<Integer> verifyCode(BrowserClient n, String json) {
        return () -> n.post(base() + "/auth/mfa/verify", json).status();
    }

    @Test
    void sameTotpStepAcrossDifferentChallengesIsAcceptedOnce() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            TestUser u = internalUser(org, true, 2);
            firstLogin(browser(), org.id(), u);
            BrowserClient c1 = pendingChallenge(u);
            BrowserClient c2 = pendingChallenge(u);
            assertThat(count("SELECT count(*) FROM auth_challenges WHERE user_id = '" + u.id() + "' AND consumed_at IS NULL AND invalidated_at IS NULL"))
                    .as("dois desafios distintos pendentes").isEqualTo(2);
            long before = activeSessions(u.id());
            String code = code(u.id());
            int[] s = race(i, 15, verifyCode(c1, "{\"code\":\"" + code + "\"}"), verifyCode(c2, "{\"code\":\"" + code + "\"}"));
            assertThat(List.of(s[0], s[1])).as("rodada %d: mesmo passo TOTP em desafios diferentes", i).containsExactlyInAnyOrder(200, 401);
            assertThat(activeSessions(u.id()) - before).as("uma sessao nova").isEqualTo(1);
        }
    }

    @Test
    void sameRecoveryCodeAcrossDifferentChallengesIsAcceptedOnce() throws Exception {
        for (int i = 0; i < ROUNDS; i++) {
            TestUser u = internalUser(org, true, 2);
            List<String> codes = firstLogin(browser(), org.id(), u);
            BrowserClient c1 = pendingChallenge(u);
            BrowserClient c2 = pendingChallenge(u);
            String json = "{\"recoveryCode\":\"" + codes.get(0) + "\"}";
            int[] s = race(i, 15, verifyCode(c1, json), verifyCode(c2, json));
            assertThat(List.of(s[0], s[1])).as("rodada %d: mesmo codigo de recuperacao em desafios diferentes", i).containsExactlyInAnyOrder(200, 401);
            assertThat(count("SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = '" + u.id() + "' AND used_at IS NOT NULL"))
                    .as("codigo usado uma vez").isEqualTo(1);
            assertThat(activeSessions(u.id())).as("o codigo revoga as outras sessoes: so a nova").isEqualTo(1);
        }
    }

    @Test
    void mfaCompletionVersusPasswordChangeNeverLeavesAnOldCredentialSession() throws Exception {
        Map<String, Integer> outcomes = new TreeMap<>();
        for (int i = 0; i < ROUNDS; i++) {
            TestUser u = internalUser(org, true, 2);
            BrowserClient owner = browser();
            firstLogin(owner, org.id(), u);
            BrowserClient pending = pendingChallenge(u);
            String code = code(u.id());
            String newPassword = "senha-trocada-na-corrida-" + i;
            int[] s = race(i, 900, verifyCode(pending, "{\"code\":\"" + code + "\"}"),
                    () -> owner.post(base() + "/auth/password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + newPassword + "\"}").status());
            outcomes.merge("verify=" + s[0] + " troca=" + s[1], 1, Integer::sum);
            assertThat(s[1]).as("rodada %d: a troca de senha sempre conclui", i).isEqualTo(204);
            assertThat(s[0]).isIn(200, 401);
            assertThat(pending.get(base() + "/me").status()).as("rodada %d: a sessao nascida da credencial antiga nao sobrevive", i).isEqualTo(401);
            assertThat(activeSessions(u.id())).as("so a sessao que trocou a senha").isEqualTo(1);
            assertThat(owner.get(base() + "/me").status()).isEqualTo(200);
            assertThat(count("SELECT count(*) FROM auth_challenges WHERE user_id = '" + u.id() + "' AND consumed_at IS NULL AND invalidated_at IS NULL"))
                    .as("nenhum desafio pendente sobrevive a troca").isZero();
        }
        System.out.println("corrida MFA x troca de senha: " + outcomes);
    }

    @Test
    void mfaCompletionVersusSuspensionNeverLeavesAnActiveSession() throws Exception {
        TestUser admin = new TestUser(org.adminId(), org.adminEmail(), PASSWORD.toCharArray());
        BrowserClient a = browser();
        firstLogin(a, org.id(), admin);
        Map<String, Integer> outcomes = new TreeMap<>();
        for (int i = 0; i < ROUNDS; i++) {
            TestUser u = internalUser(org, true, 2);
            firstLogin(browser(), org.id(), u);
            BrowserClient pending = pendingChallenge(u);
            String code = code(u.id());
            int[] s = race(i, 150, verifyCode(pending, "{\"code\":\"" + code + "\"}"),
                    () -> a.post(base() + "/users/" + u.id() + "/suspend", null).status());
            outcomes.merge("verify=" + s[0] + " suspensao=" + s[1], 1, Integer::sum);
            diagnostico(i, s);
            assertThat(s[1]).as("rodada %d: a suspensao sempre conclui", i).isEqualTo(204);
            assertThat(s[0]).isIn(200, 401);
            assertThat(activeSessions(u.id())).as("rodada %d: conta suspensa sem sessao ativa", i).isZero();
            assertThat(pending.get(base() + "/me").status()).isEqualTo(401);
        }
        System.out.println("corrida MFA x suspensao: " + outcomes);
    }

    @Test
    void mfaCompletionVersusAdminResetNeverLeavesAnActiveSessionOrFactor() throws Exception {
        Org orgAdmin = newOrganization();
        TestUser admin = new TestUser(orgAdmin.adminId(), orgAdmin.adminEmail(), PASSWORD.toCharArray());
        BrowserClient a = browser();
        firstLogin(a, orgAdmin.id(), admin);
        assertThat(a.post("/api/v1/o/" + orgAdmin.id() + "/auth/mfa/step-up", "{\"code\":\"" + code(admin.id()) + "\"}").status()).isEqualTo(204);
        Map<String, Integer> outcomes = new TreeMap<>();
        for (int i = 0; i < ROUNDS; i++) {
            TestUser u = internalUser(orgAdmin, true, 2);
            firstLogin(browser(), orgAdmin.id(), u);
            BrowserClient pending = browser();
            assertThat(passwordLogin(pending, orgAdmin.id(), u).field("status")).isEqualTo("mfa_required");
            String code = code(u.id());
            int[] s = race(i, 150, () -> pending.post("/api/v1/o/" + orgAdmin.id() + "/auth/mfa/verify", "{\"code\":\"" + code + "\"}").status(),
                    () -> a.post("/api/v1/o/" + orgAdmin.id() + "/users/" + u.id() + "/mfa/reset", SessionsAndAdministrationIT.RESET_BODY).status());
            outcomes.merge("verify=" + s[0] + " reset=" + s[1], 1, Integer::sum);
            diagnostico(i, s);
            assertThat(s[1]).as("rodada %d: o reset sempre conclui", i).isEqualTo(204);
            assertThat(s[0]).isIn(200, 401);
            assertThat(activeSessions(u.id())).as("rodada %d: alvo sem sessao ativa", i).isZero();
            assertThat(count("SELECT count(*) FROM user_mfa_factors WHERE user_id = '" + u.id() + "' AND disabled_at IS NULL")).as("fator desabilitado").isZero();
            assertThat(count("SELECT count(*) FROM auth_challenges WHERE user_id = '" + u.id() + "' AND consumed_at IS NULL AND invalidated_at IS NULL"))
                    .as("nenhum desafio pendente").isZero();
            assertThat(pending.get("/api/v1/o/" + orgAdmin.id() + "/me").status()).isEqualTo(401);
        }
        System.out.println("corrida MFA x reset administrativo: " + outcomes);
    }

    /**
     * Diagnostico de falha intermitente (revisao 1 de P3/P4): o servidor responde so o SQLSTATE, e a mensagem do banco se perde
     * com o container compartilhado ao fim da suite. Se o ato administrativo nao concluiu, as linhas de erro do log do
     * PostgreSQL de teste vao para a saida do teste, sanitizadas ({@link DiagnosticoPg}: sem os valores da linha recusada).
     */
    private static void diagnostico(int rodada, int[] s) {
        if (s[1] == 204) {
            return;
        }
        System.out.println("diagnostico rodada " + rodada + ": status=" + s[0] + "/" + s[1]);
        DiagnosticoPg.imprimir(DB, "rodada " + rodada);
    }
}
