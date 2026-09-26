package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.BrowserClient;

/**
 * §3.2, passo 4: o caminho do codigo CORRETO e atomico. Se o desafio deixa de ser consumivel depois que o segundo
 * fator ja foi consumido na mesma transacao (aqui: o desafio expira entre as duas etapas), a transacao inteira e
 * desfeita - o codigo de recuperacao e o passo TOTP continuam validos, nenhuma sessao nasce e a resposta e 401.
 *
 * <p>Instrumentacao DECLARADA, so na base descartavel do teste e removida ao final: (1) o prazo do desafio e
 * encurtado para ~2 s, com a trigger de imutabilidade da 014 desligada so durante esse UPDATE (superusuario);
 * (2) um trigger AFTER UPDATE na tabela do segundo fator, restrito a conta do teste, espera 4 s - o consumo do
 * codigo/passo acontece, o desafio expira durante a espera e o consumo do desafio encontra 0 linhas.
 */
class MfaAtomicCompletionIT extends PlatformTestBase {

    static Org org;

    @BeforeAll
    static void fixture() {
        org = newOrganization();
    }

    private static long count(String sql) throws Exception {
        return AuthenticationFlowIT.count(sql);
    }

    private static String text(String sql) throws Exception {
        return AuthenticationFlowIT.queryText(sql);
    }

    /** Encurta o prazo do unico desafio pendente da conta para ~2 s. */
    private static void expireChallengeSoon(UUID user) {
        DB.asSuperuser("ALTER TABLE auth_challenges DISABLE TRIGGER trg_auth_challenges_prevent_change",
                "UPDATE auth_challenges SET expires_at = clock_timestamp() + interval '2 seconds' WHERE user_id = '" + user
                        + "' AND consumed_at IS NULL AND invalidated_at IS NULL",
                "ALTER TABLE auth_challenges ENABLE TRIGGER trg_auth_challenges_prevent_change");
    }

    /** Trigger que atrasa em 4 s o consumo do segundo fator da conta (tabela e coluna dadas). */
    private static void delayConsumption(String table, String column, UUID user) {
        DB.asSuperuser("CREATE OR REPLACE FUNCTION public.teste_atrasa_consumo() RETURNS trigger LANGUAGE plpgsql AS "
                        + "$f$ BEGIN PERFORM pg_sleep(4); RETURN NEW; END $f$",
                "CREATE TRIGGER teste_atrasa_consumo AFTER UPDATE OF " + column + " ON public." + table + " FOR EACH ROW WHEN (NEW.user_id = '"
                        + user + "') EXECUTE FUNCTION public.teste_atrasa_consumo()");
    }

    private static void removeDelay(String table) {
        DB.asSuperuser("DROP TRIGGER IF EXISTS teste_atrasa_consumo ON public." + table, "DROP FUNCTION IF EXISTS public.teste_atrasa_consumo()");
    }

    private BrowserClient pendingChallenge(TestUser u) {
        BrowserClient n = browser();
        assertThat(passwordLogin(n, org.id(), u).field("status")).isEqualTo("mfa_required");
        return n;
    }

    @Test
    void recoveryCodeIsNotBurnedWhenChallengeExpiresBetweenConsumptions() throws Exception {
        TestUser u = internalUser(org, true, 2);
        List<String> codes = firstLogin(browser(), org.id(), u);
        String activeBefore = text("SELECT string_agg(session_id::text, ',' ORDER BY session_id) FROM user_sessions WHERE user_id = '" + u.id()
                + "' AND revoked_at IS NULL");
        long sessionsBefore = count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u.id() + "'");
        BrowserClient n = pendingChallenge(u);
        expireChallengeSoon(u.id());
        delayConsumption("user_mfa_recovery_codes", "used_at", u.id());
        BrowserClient.Response r;
        try {
            r = n.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"recoveryCode\":\"" + codes.get(0) + "\"}");
        } finally {
            removeDelay("user_mfa_recovery_codes");
        }
        assertThat(r.status()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM user_mfa_recovery_codes WHERE user_id = '" + u.id() + "' AND used_at IS NOT NULL"))
                .as("codigo de recuperacao NAO consumido").isZero();
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u.id() + "'")).as("nenhuma sessao nova").isEqualTo(sessionsBefore);
        assertThat(text("SELECT string_agg(session_id::text, ',' ORDER BY session_id) FROM user_sessions WHERE user_id = '" + u.id()
                + "' AND revoked_at IS NULL")).as("sessoes existentes intactas (o codigo nao revogou nada)").isEqualTo(activeBefore);
        assertThat(count("SELECT count(*) FROM mfa_events WHERE user_id = '" + u.id() + "' AND event_type = 2"))
                .as("nenhum evento de uso de codigo de recuperacao").isZero();
        assertThat(testMail.sent).noneMatch(m -> m.recipient().equals(u.email()) && m.kind() == com.nekkora.platform.mail.MailSender.Kind.NOTICE_RECOVERY_CODE_USED);

        // o mesmo codigo continua valendo num login novo
        BrowserClient again = pendingChallenge(u);
        assertThat(again.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"recoveryCode\":\"" + codes.get(0) + "\"}").status())
                .as("codigo preservado: vale no login seguinte").isEqualTo(200);
    }

    @Test
    void totpStepIsNotBurnedWhenChallengeExpiresBetweenConsumptions() throws Exception {
        TestUser u = internalUser(org, true, 2);
        firstLogin(browser(), org.id(), u);
        String stepBefore = text("SELECT coalesce(last_used_step::text, 'null') FROM user_mfa_factors WHERE user_id = '" + u.id() + "' AND disabled_at IS NULL");
        long sessionsBefore = count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u.id() + "'");
        BrowserClient n = pendingChallenge(u);
        String code = code(u.id());
        expireChallengeSoon(u.id());
        delayConsumption("user_mfa_factors", "last_used_step", u.id());
        BrowserClient.Response r;
        try {
            r = n.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"code\":\"" + code + "\"}");
        } finally {
            removeDelay("user_mfa_factors");
        }
        assertThat(r.status()).isEqualTo(401);
        assertThat(text("SELECT coalesce(last_used_step::text, 'null') FROM user_mfa_factors WHERE user_id = '" + u.id() + "' AND disabled_at IS NULL"))
                .as("passo TOTP NAO consumido").isEqualTo(stepBefore);
        assertThat(count("SELECT count(*) FROM user_sessions WHERE user_id = '" + u.id() + "'")).as("nenhuma sessao nova").isEqualTo(sessionsBefore);

        // o mesmo codigo (mesmo passo) continua valendo num login novo, dentro da janela de +-1 passo
        BrowserClient again = pendingChallenge(u);
        assertThat(again.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"code\":\"" + code + "\"}").status())
                .as("passo preservado: o mesmo codigo vale no login seguinte").isEqualTo(200);
    }

    @Test
    void wrongCodeAttemptIsStillCommittedSeparately() throws Exception {
        TestUser u = internalUser(org, true, 2);
        firstLogin(browser(), org.id(), u);
        BrowserClient n = pendingChallenge(u);
        assertThat(n.post("/api/v1/o/" + org.id() + "/auth/mfa/verify", "{\"recoveryCode\":\"AAAAAAAAAAAAAAAA\"}").status()).isEqualTo(401);
        assertThat(count("SELECT max(attempts) FROM auth_challenges WHERE user_id = '" + u.id() + "' AND consumed_at IS NULL AND invalidated_at IS NULL"))
                .as("tentativa errada confirmada (COMMIT), independente do rollback do caminho correto").isEqualTo(1);
    }
}
