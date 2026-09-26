package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.token.Tokens;

/** Limites da camada 2 (contrato 8.6, §2) e ausencia de segredos nos logs do processo e do PostgreSQL. */
@ExtendWith(OutputCaptureExtension.class)
class LimitsAndLogsIT extends PlatformTestBase {

    private static Org org;

    @BeforeAll
    static void fixture() {
        org = newOrganization();
    }

    private BrowserClient.Response attempt(BrowserClient n, TestUser u, String password) {
        fetchPreAuthCsrf(n);
        return n.post("/api/v1/o/" + org.id() + "/auth/login", "{\"email\":\"" + u.email() + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    void r1PerIdentityAndIpWithRetryAfterWhileOtherIpContinues() {
        TestUser u = internalUser(org, true, 2);
        BrowserClient n = browser();
        for (int i = 0; i < 5; i++) {
            assertThat(attempt(n, u, "errada-errada-" + i).status()).isEqualTo(401);
        }
        BrowserClient.Response locked = attempt(n, u, PASSWORD);
        assertThat(locked.status()).as("6a tentativa do mesmo IP, mesmo com a senha certa").isEqualTo(429);
        assertThat(locked.body()).isEqualTo("{\"error\":\"rate_limited\"}");
        assertThat(locked.headers().firstValue("Retry-After")).isPresent();
        assertThat(attempt(browser(), u, PASSWORD).status()).as("outro IP: sem bloqueio da conta").isEqualTo(200);
    }

    @Test
    void r2ProgressiveDelayWithoutPermanentLockResetOnFullLogin() {
        TestUser u = internalUser(org, true, 2);
        firstLogin(browser(), org.id(), u);
        for (int i = 0; i < 6; i++) {
            assertThat(attempt(browser(), u, "errada-errada-" + i).status()).as("falha %s de IPs distintos", i).isEqualTo(401);
        }
        BrowserClient owner = browser();
        BrowserClient.Response delayed = attempt(owner, u, PASSWORD);
        assertThat(delayed.status()).as("atraso progressivo: 429 imediato, nenhuma thread dormindo").isEqualTo(429);
        long waitTime = Long.parseLong(delayed.headers().firstValue("Retry-After").orElseThrow());
        assertThat(waitTime).isBetween(1L, 10L);
        pause(waitTime * 1000 + 200);
        assertThat(fullLogin(owner, org.id(), u).status()).as("depois do atraso, o titular entra (sem bloqueio permanente)").isEqualTo(200);
        assertThat(attempt(browser(), u, "errada-depois").status()).as("contadores zerados pelo login completo: sem atraso").isEqualTo(401);
        assertThat(attempt(browser(), u, PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void countersDoNotResetOnCorrectPasswordAlone() {
        TestUser u = internalUser(org, true, 2);
        firstLogin(browser(), org.id(), u);
        BrowserClient n = browser();
        for (int i = 0; i < 4; i++) {
            attempt(n, u, "errada-" + i);
        }
        assertThat(attempt(n, u, PASSWORD).status()).as("senha certa, sem completar o MFA").isEqualTo(200);
        assertThat(attempt(n, u, "errada-5").status()).isEqualTo(401);
        assertThat(attempt(n, u, PASSWORD).status()).as("5 falhas no IP: o passo da senha nao zerou o contador").isEqualTo(429);
    }

    @Test
    void noSecretsInProcessOrServerLogs(CapturedOutput output) {
        TestUser u = externalUser(org, newClient(org), null);
        BrowserClient n = browser();
        List<String> codes = firstLogin(n, org.id(), u);
        String session = n.cookies().get("__Host-nekkora_session");
        String csrf = n.csrf;
        BrowserClient sentinel = browser();
        String sentinelToken = Tokens.newToken();
        sentinel.cookies().put("__Host-nekkora_session", sentinelToken);
        sentinel.get("/api/v1/o/" + org.id() + "/me");
        attempt(browser(), u, "senha-sentinela-XYZ-123");
        BrowserClient s2 = browser();
        passwordLogin(s2, org.id(), u);
        s2.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"code\":\"000000\"}");
        String challenge = s2.cookies().get("__Host-nekkora_mfa");

        List<String> secrets = new ArrayList<>(List.of(session, csrf, sentinelToken, challenge, PASSWORD, "senha-sentinela-XYZ-123", u.email(),
                com.nekkora.platform.auth.crypto.Base32.encode(SECRETS.get(u.id()))));
        secrets.addAll(codes);
        String logApp = output.getAll();
        String logPg = DB.serverLog();
        for (String s : secrets) {
            assertThat(logApp).as("log do processo").doesNotContain(s);
            assertThat(logPg).as("log do PostgreSQL").doesNotContain(s);
        }
        assertThat(logApp).contains("evento=auth.denied").contains("evento=auth.authenticated");
        assertThat(logApp).as("sem motivo que distinga conta inexistente").doesNotContain("inexistente").doesNotContain("nao_encontrada");
    }
}
