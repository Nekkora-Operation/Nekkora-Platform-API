package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.core.token.Tokens;

import tools.jackson.databind.JsonNode;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01b - a maquina habilitada renova a propria credencial, pelo gateway REAL (HTTP,
 * papel nekkora_ingestion, PostgreSQL com as migrations ate a 043, recurso LIGADO, janela padrao de 30 dias). O vencimento
 * proximo e preparado por fixture (como superusuario): a aplicacao nao tem como encurtar a validade de uma credencial.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentGatewayApplication.class)
class CredentialRenewalIT {

    static final RealDatabase DB = RealDatabase.shared();
    static AgentFixture fx;

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        RealRedis redis = RealRedis.shared();
        r.add("nekkora.database.url", DB::url);
        r.add("nekkora.database.user", () -> "nekkora_ingestion");
        r.add("nekkora.database.password", () -> DB.password("nekkora_ingestion"));
        r.add("spring.data.redis.host", redis::host);
        r.add("spring.data.redis.port", redis::port);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.limits.batches-per-minute", () -> "100000");
        r.add("nekkora.limits.redeem-failures-per-hour", () -> "1000");
        r.add("nekkora.remote-execution.enabled", () -> "true");
    }

    @BeforeAll
    static void fixture() {
        fx = new AgentFixture(DB);
    }

    static final String HEARTBEAT = "{\"agentVersion\":\"0.2.0+client\",\"contractVersion\":\"1.0\",\"capabilities\":[]}";

    /** Uma maquina com a identidade, a credencial atual e o segredo dela. */
    private record Machine(MachineClient client, String agent, String credentialId, String secret) {
        String credential() {
            return MachineToken.credential(fx.org, UUID.fromString(credentialId), secret);
        }
    }

    private MachineClient client() {
        return new MachineClient(port, "10.63." + (int) (Math.random() * 250) + "." + (1 + (int) (Math.random() * 250)));
    }

    private Machine paired(boolean remote) {
        UUID worker = fx.worker("W-ren-" + UUID.randomUUID().toString().substring(0, 6));
        MachineClient m = client();
        String secret = Tokens.newToken();
        MachineClient.Response r = remote ? m.redeemWithIntent(fx.remotePairingCode(worker, "Estacao remota"), secret, "pair-remote")
                : m.redeemWithIntent(fx.pairingCode(worker, "Estacao comum", "15 minutes"), secret, "pair");
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        return new Machine(m, r.body().get("agentId").asString(), r.body().get("credentialId").asString(), secret);
    }

    /**
     * Fixture: a credencial passa a vencer daqui a {@code days} dias (negativo: ja venceu). As datas de uma credencial sao
     * imutaveis (gatilho da 004, que vale ate para o superusuario); so esta preparacao de teste o contorna, na propria sessao.
     */
    private void expiresIn(Machine m, int days) {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE agent_credentials SET issued_at = clock_timestamp() - interval '100 days', expires_at = clock_timestamp() + interval '" + days
                    + " days' WHERE credential_id = '" + m.credentialId() + "'");
        } catch (SQLException e) {
            throw new IllegalStateException("fixture falhou (sqlstate " + e.getSQLState() + ")", e);
        }
    }

    private MachineClient.Response renew(Machine m, String bearer, String newSecret) {
        return m.client().post("/remote/credential/renew", "{\"credentialSecret\":\"" + newSecret + "\"}", bearer);
    }

    private int heartbeat(Machine m, String bearer) {
        return m.client().post("/heartbeat", HEARTBEAT, bearer).status();
    }

    private long count(String sql) {
        return Long.parseLong(fx.value(sql));
    }

    private static String asRole(String role, UUID org, String... sqls) {
        try (Connection c = DB.connectAs(role)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + org + "', true)");
                for (String sql : sqls) {
                    st.execute(sql);
                }
                c.commit();
                return "OK";
            } catch (SQLException e) {
                c.rollback();
                return e.getSQLState();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void dentroDaJanelaAMaquinaTrocaAPropriaCredencialEOVinculoContinua() {
        Machine m = paired(true);
        // pausada antes da renovacao: a habilitacao e o recebimento nao mudam com a troca
        assertThat(m.client().post("/remote/receiving", "{\"receiving\":\"PAUSED\"}", m.credential()).status()).isEqualTo(200);
        expiresIn(m, 10);
        String issuer = fx.value("SELECT issued_by_membership_id FROM agent_credentials WHERE credential_id = '" + m.credentialId() + "'");
        String newSecret = Tokens.newToken();
        MachineClient.Response r = renew(m, m.credential(), newSecret);
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        String newId = r.body().get("credentialId").asString();
        assertThat(newId).isNotEqualTo(m.credentialId());
        assertThat(r.raw()).as("o servidor nunca devolve segredo").doesNotContain(newSecret).doesNotContain(m.secret());
        assertThat(r.body().propertyNames()).containsExactlyInAnyOrder("credentialId", "credentialExpiresAt");
        // a nova vale pelo prazo cheio; a anterior nao vale mais
        assertThat(fx.value("SELECT expires_at > clock_timestamp() + interval '89 days' FROM agent_credentials WHERE credential_id = '" + newId + "'")).isEqualTo("t");
        String renewed = MachineToken.credential(fx.org, UUID.fromString(newId), newSecret);
        assertThat(heartbeat(m, renewed)).isEqualTo(200);
        assertThat(heartbeat(m, m.credential())).as("a anterior nunca volta a valer").isEqualTo(401);
        // no banco: anterior revogada pela propria maquina (motivo 6, sem pessoa); nova com o MESMO emissor; uma renovacao; ato 18
        assertThat(fx.value("SELECT revocation_reason || '|' || (revoked_by_agent_id = agent_id) || '|' || (revoked_by_membership_id IS NULL) FROM agent_credentials "
                + "WHERE credential_id = '" + m.credentialId() + "'")).isEqualTo("6|true|true");
        assertThat(fx.value("SELECT issued_by_membership_id FROM agent_credentials WHERE credential_id = '" + newId + "'")).isEqualTo(issuer);
        assertThat(count("SELECT count(*) FROM agent_credential_renewals WHERE from_credential_id = '" + m.credentialId() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE agent_id = '" + m.agent() + "' AND act = 18 AND credential_id = '" + newId
                + "' AND actor_membership_id IS NULL AND actor_agent_id = agent_id")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_credentials WHERE agent_id = '" + m.agent() + "' AND revoked_at IS NULL")).isEqualTo(1);
        // vinculo, Worker e habilitacao intactos; o recebimento continua pausado
        assertThat(fx.value("SELECT a.status || '|' || r.receiving FROM agents a JOIN agent_remote_enablements r ON r.agent_id = a.agent_id WHERE a.agent_id = '"
                + m.agent() + "'")).isEqualTo("2|2");
        assertThat(count("SELECT count(*) FROM worker_agent_assignments WHERE agent_id = '" + m.agent() + "' AND status = 1")).isEqualTo(1);
        // a nova acabou de nascer: ainda nao esta na janela
        MachineClient.Response again = renew(m, renewed, Tokens.newToken());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("reason").asString()).isEqualTo("credential_renewal_not_due");
    }

    @Test
    void respostaPerdidaARepeticaoComOMesmoSegredoDevolveAMesmaCredencial() {
        Machine m = paired(true);
        expiresIn(m, 5);
        String newSecret = Tokens.newToken();
        JsonNode first = renew(m, m.credential(), newSecret).body();
        // a maquina nao recebeu a resposta: repete com a credencial ANTERIOR (ja revogada por esta troca) e o MESMO segredo
        MachineClient.Response repeat = renew(m, m.credential(), newSecret);
        assertThat(repeat.status()).as(repeat.raw()).isEqualTo(200);
        assertThat(repeat.body().get("credentialId")).isEqualTo(first.get("credentialId"));
        assertThat(repeat.body().get("credentialExpiresAt")).isEqualTo(first.get("credentialExpiresAt"));
        assertThat(count("SELECT count(*) FROM agent_credential_renewals WHERE agent_id = '" + m.agent() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_admin_events WHERE agent_id = '" + m.agent() + "' AND act = 18")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM agent_credentials WHERE agent_id = '" + m.agent() + "'")).isEqualTo(2);
        // a credencial revogada so serve para repetir ESTA troca: outro segredo, ou outra rota, 401
        assertThat(renew(m, m.credential(), Tokens.newToken()).status()).isEqualTo(401);
        assertThat(heartbeat(m, m.credential())).isEqualTo(401);
        assertThat(m.client().post("/remote/receiving", "{\"receiving\":\"PAUSED\"}", m.credential()).status()).isEqualTo(401);
        // a nova revogada por uma pessoa: a repeticao nao a ressuscita
        DB.asSuperuser("UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_membership_id = '" + fx.adminMembership
                + "', revocation_reason = 4 WHERE credential_id = '" + first.get("credentialId").asString() + "'");
        assertThat(renew(m, m.credential(), newSecret).status()).isEqualTo(401);
    }

    @Test
    void foraDaJanelaVencidaRevogadaOuNaoHabilitadaNadaERenovado() {
        // longe do vencimento: 409, nada muda
        Machine fresh = paired(true);
        MachineClient.Response notDue = renew(fresh, fresh.credential(), Tokens.newToken());
        assertThat(notDue.status()).isEqualTo(409);
        assertThat(notDue.body().get("error").asString()).isEqualTo("conflict");
        assertThat(notDue.body().get("reason").asString()).isEqualTo("credential_renewal_not_due");
        expiresIn(fresh, 31);
        assertThat(renew(fresh, fresh.credential(), Tokens.newToken()).body().get("reason").asString()).as("31 dias: fora da janela de 30").isEqualTo("credential_renewal_not_due");
        assertThat(heartbeat(fresh, fresh.credential())).isEqualTo(200);
        // segredo malformado, ou o mesmo segredo de novo: 400
        expiresIn(fresh, 10);
        assertThat(renew(fresh, fresh.credential(), "curto").status()).isEqualTo(400);
        assertThat(renew(fresh, fresh.credential(), fresh.secret()).status()).isEqualTo(400);
        assertThat(fresh.client().post("/remote/credential/renew", "{}", fresh.credential()).status()).isEqualTo(400);
        // sem credencial, ou com o segredo errado: 401
        assertThat(renew(fresh, null, Tokens.newToken()).status()).isEqualTo(401);
        assertThat(renew(fresh, MachineToken.credential(fx.org, UUID.fromString(fresh.credentialId()), Tokens.newToken()), Tokens.newToken()).status()).isEqualTo(401);

        // VENCIDA: nunca e renovada
        Machine expired = paired(true);
        expiresIn(expired, -1);
        assertThat(renew(expired, expired.credential(), Tokens.newToken()).status()).isEqualTo(401);

        // revogada por uma pessoa: 401
        Machine revoked = paired(true);
        expiresIn(revoked, 10);
        DB.asSuperuser("UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_membership_id = '" + fx.adminMembership
                + "', revocation_reason = 4 WHERE credential_id = '" + revoked.credentialId() + "'");
        assertThat(renew(revoked, revoked.credential(), Tokens.newToken()).status()).isEqualTo(401);

        // maquina pareada mas NAO habilitada para execucao remota (o Agent anterior): continua precisando do codigo de rotacao
        Machine plain = paired(false);
        expiresIn(plain, 10);
        MachineClient.Response no = renew(plain, plain.credential(), Tokens.newToken());
        assertThat(no.status()).isEqualTo(409);
        assertThat(no.body().get("reason").asString()).isEqualTo("remote_execution_not_enabled");
        assertThat(heartbeat(plain, plain.credential())).isEqualTo(200);

        for (Machine m : new Machine[] {fresh, expired, revoked, plain}) {
            assertThat(count("SELECT count(*) FROM agent_credential_renewals WHERE agent_id = '" + m.agent() + "'")).isZero();
            assertThat(count("SELECT count(*) FROM agent_credentials WHERE agent_id = '" + m.agent() + "'")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM agent_admin_events WHERE agent_id = '" + m.agent() + "' AND act = 18")).isZero();
        }
    }

    @Test
    void oBancoSoAceitaATrocaCompletaDaCredencialDeUmaMaquinaHabilitada() {
        Machine m = paired(true);
        expiresIn(m, 10);
        String org = fx.org.toString();
        String hash = Tokens.sha256Hex(Tokens.newToken());
        String issuer = fx.value("SELECT issued_by_membership_id FROM agent_credentials WHERE credential_id = '" + m.credentialId() + "'");
        String renewal = "INSERT INTO agent_credential_renewals (organization_id, agent_id, from_credential_id, to_credential_hash) VALUES ('" + org + "', '"
                + m.agent() + "', '" + m.credentialId() + "', '" + hash + "')";
        String revoke = "UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_agent_id = agent_id, revocation_reason = 6 WHERE credential_id = '"
                + m.credentialId() + "'";
        String issue = "INSERT INTO agent_credentials (organization_id, agent_id, credential_hash, issued_by_membership_id, issued_at, expires_at) VALUES ('" + org
                + "', '" + m.agent() + "', '%s', '%s', clock_timestamp(), clock_timestamp() + interval '90 days')";
        // revogar com o motivo da renovacao SEM a renovacao registrada
        assertThat(asRole("nekkora_ingestion", fx.org, revoke)).isEqualTo("42501");
        // renovacao registrada e nada mais: desfeita no fim da transacao
        assertThat(asRole("nekkora_ingestion", fx.org, renewal)).isEqualTo("23514");
        // renovacao + revogacao, sem a credencial nova
        assertThat(asRole("nekkora_ingestion", fx.org, renewal, revoke)).isEqualTo("23514");
        // credencial nova com OUTRO hash, ou com outro emissor
        assertThat(asRole("nekkora_ingestion", fx.org, renewal, revoke, issue.formatted(Tokens.sha256Hex(Tokens.newToken()), issuer))).isEqualTo("42501");
        // outra pessoa da mesma organizacao (fixture)
        UUID otherUser = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('" + otherUser + "', '"
                        + org + "', 1, 'outra-" + otherUser.toString().substring(0, 8) + "@o.test', now(), 2, 'Outra')",
                "INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('" + other + "', '" + org + "', '"
                        + otherUser + "', 1, 2)");
        assertThat(asRole("nekkora_ingestion", fx.org, renewal, revoke, issue.formatted(hash, other))).isEqualTo("42501");
        // as pessoas (nekkora_platform) nao registram renovacao
        assertThat(asRole("nekkora_platform", fx.org, renewal)).isEqualTo("42501");
        // maquina NAO habilitada, ou credencial vencida: a renovacao nem nasce
        Machine plain = paired(false);
        expiresIn(plain, 10);
        assertThat(asRole("nekkora_ingestion", fx.org, renewal.replace(m.agent(), plain.agent()).replace(m.credentialId(), plain.credentialId()))).isEqualTo("23514");
        Machine expired = paired(true);
        expiresIn(expired, -1);
        assertThat(asRole("nekkora_ingestion", fx.org, renewal.replace(m.agent(), expired.agent()).replace(m.credentialId(), expired.credentialId()))).isEqualTo("23514");
        // nada mudou: a credencial original continua valendo, e a troca completa pelo gateway funciona
        assertThat(heartbeat(m, m.credential())).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM agent_credential_renewals WHERE agent_id = '" + m.agent() + "'")).isZero();
        assertThat(renew(m, m.credential(), Tokens.newToken()).status()).isEqualTo(200);
        // a renovacao registrada nao e alterada nem apagada
        assertThat(asRole("nekkora_ingestion", fx.org, "UPDATE agent_credential_renewals SET to_credential_hash = '" + hash + "' WHERE agent_id = '" + m.agent() + "'"))
                .isEqualTo("42501");
        assertThat(asRole("nekkora_ingestion", fx.org, "DELETE FROM agent_credential_renewals WHERE agent_id = '" + m.agent() + "'")).isEqualTo("42501");
        // o desvinculo pela maquina (motivo 5) continua exigindo o desvinculo registrado
        Machine linked = paired(true);
        assertThat(asRole("nekkora_ingestion", fx.org, "UPDATE agent_credentials SET revoked_at = clock_timestamp(), revoked_by_agent_id = agent_id, "
                + "revocation_reason = 5 WHERE credential_id = '" + linked.credentialId() + "'")).isEqualTo("42501");
    }
}
