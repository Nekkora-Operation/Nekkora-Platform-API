package com.nekkora.platform.emergency;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;
import com.nekkora.platform.PlatformApplication;
import com.nekkora.platform.PlatformTestBase;
import com.nekkora.platform.mail.MailSender;

/**
 * Item 10, protocolo ENTREGANDO e perda da confirmacao do commit da reivindicacao (§6.8, com a restricao da v6), com o
 * {@link EmergencyNotices} REAL sobre um DataSource que injeta falhas (proxy de Connection): resposta do commit perdida
 * com o commit aplicado ou desfeito, banco indisponivel na reconciliacao, instrucao que falha.
 *
 * <p>Alcance: o transporte simulado ({@link PlatformTestBase.TestMailSender}) conta as CHAMADAS observadas pelo harness
 * por {@code attempt_id}. Isso nao e garantia de recebimento exatamente uma vez pelo destinatario.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {PlatformApplication.class, PlatformTestBase.TestMailConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class EntregaReivindicacaoIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    PlatformTestBase.TestMailSender mail;

    @Autowired
    DataSource dataSource;

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("nekkora.database.url", DB::urlTls);
        r.add("nekkora.database.user", () -> "nekkora_platform");
        r.add("nekkora.database.password", () -> DB.senha("nekkora_platform"));
        r.add("spring.data.redis.host", () -> RealRedis.shared().host());
        r.add("spring.data.redis.port", () -> RealRedis.shared().port());
        r.add("nekkora.public-origin", () -> PlatformTestBase.ORIGIN);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.keys.aes-current", () -> "k1");
        r.add("nekkora.keys.aes-by-id", () -> "k1:" + b64(32));
        r.add("nekkora.keys.hmac-id", () -> "h1");
        r.add("nekkora.keys.hmac", () -> b64(32));
    }

    private static String b64(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    // ------------------------------------------------------------------ DataSource com falhas injetadas

    /** Proxy de Connection: perde a resposta do commit da transacao que executou {@code alvo}; falha instrucoes; recusa conexoes. */
    static final class Falhas extends DelegatingDataSource {
        volatile String alvo;
        volatile boolean aplicar;
        volatile Runnable antesDaFalha;
        volatile boolean indisponivel;
        volatile String falharSql;
        final java.util.concurrent.atomic.AtomicInteger disparos = new java.util.concurrent.atomic.AtomicInteger();

        Falhas(DataSource d) {
            super(d);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (indisponivel) {
                throw new SQLException("banco indisponivel (simulado)", "08001");
            }
            Connection real = super.getConnection();
            boolean[] tocou = {false};
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (p, m, a) -> {
                switch (m.getName()) {
                    case "prepareStatement", "prepareCall" -> {
                        String sql = (String) a[0];
                        String f = falharSql;
                        if (f != null && sql.contains(f)) {
                            falharSql = null;
                            disparos.incrementAndGet();
                            throw new SQLException("conexao perdida (simulada)", "08006");
                        }
                        String t = alvo;
                        if (t != null && sql.contains(t)) {
                            tocou[0] = true;
                        }
                    }
                    case "commit" -> {
                        if (tocou[0] && alvo != null) {
                            boolean ap = aplicar;
                            Runnable r = antesDaFalha;
                            alvo = null;
                            antesDaFalha = null;
                            tocou[0] = false;
                            if (ap) {
                                real.commit();
                            } else {
                                real.rollback();
                            }
                            if (r != null) {
                                r.run();
                            }
                            disparos.incrementAndGet();
                            throw new SQLException("resposta do commit perdida (simulada)", "08006");
                        }
                    }
                    default -> {
                    }
                }
                try {
                    return m.invoke(real, a);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        }
    }

    private record Montado(EmergencyNotices avisos, Falhas falhas) {
    }

    private Montado montar() {
        Falhas f = new Falhas(dataSource);
        OrgTransactions tx = new OrgTransactions(new DataSourceTransactionManager(f), JdbcClient.create(f));
        return new Montado(new EmergencyNotices(tx, mail), f);
    }

    /** Solicitacao pre-aprovada (sem outros admins): uma tentativa pendente, a do titular. */
    private static UUID tentativaPendente() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.preAprovada(eq, al);
        return DB.valor(UUID.class, "SELECT notice_id FROM ops.ops_mfa_notices WHERE request_id = ? AND state = 0", s);
    }

    private static short estado(UUID aviso) {
        return DB.valor(Short.class, "SELECT state FROM ops.ops_mfa_notices WHERE notice_id = ?", aviso);
    }

    /** Varredura de recuperacao so desta tentativa (parada ha mais de 10 min pela fixture temporal), como a Platform. */
    private static String recuperar(UUID aviso) {
        DB.recuarEstadoDoAviso(aviso, Duration.ofMinutes(11));
        try (Connection c = DB.comoServico("nekkora_platform"); PreparedStatement ps = c.prepareStatement("SELECT r_resultado FROM public.ops_notice_recover(?)")) {
            ps.setObject(1, aviso);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ casos

    /** Resposta do commit de ENTREGANDO perdida, commit aplicado: a mesma execucao encontra a propria reivindicacao -> uma chamada. */
    @Test
    void confirmacaoPerdidaComCommitAplicadoEntregaUmaVez() {
        Montado m = montar();
        UUID aviso = tentativaPendente();
        m.falhas().alvo = "ops_notice_claim";
        m.falhas().aplicar = true;
        assertThat(m.avisos().dispatch(aviso)).isTrue();
        assertThat(m.falhas().disparos.get()).as("falha injetada").isEqualTo(1);
        assertThat(mail.callsFor(aviso)).isEqualTo(1);
        assertThat(estado(aviso)).isEqualTo((short) 4);
    }

    /** Resposta perdida SEM o commit aplicado: a reconciliacao encontra EMITIDA, reivindica de novo e entrega uma vez. */
    @Test
    void confirmacaoPerdidaSemCommitReivindicaDeNovoEEntregaUmaVez() {
        Montado m = montar();
        UUID aviso = tentativaPendente();
        m.falhas().alvo = "ops_notice_claim";
        m.falhas().aplicar = false;
        assertThat(m.avisos().dispatch(aviso)).isTrue();
        assertThat(m.falhas().disparos.get()).as("falha injetada").isEqualTo(1);
        assertThat(mail.callsFor(aviso)).isEqualTo(1);
        assertThat(estado(aviso)).isEqualTo((short) 4);
    }

    /** Banco inacessivel na reconciliacao: zero chamadas e alerta; a varredura leva ENTREGANDO a DESCONHECIDO e EMITIDA a FALHOU. */
    @Test
    void bancoInacessivelNaReconciliacaoNaoEntrega(CapturedOutput saida) {
        for (boolean aplicado : new boolean[] {true, false}) {
            Montado m = montar();
            UUID aviso = tentativaPendente();
            m.falhas().alvo = "ops_notice_claim";
            m.falhas().aplicar = aplicado;
            m.falhas().antesDaFalha = () -> m.falhas().indisponivel = true;
            assertThat(m.avisos().dispatch(aviso)).isFalse();
            assertThat(m.falhas().disparos.get()).isEqualTo(1);
            assertThat(mail.callsFor(aviso)).isZero();
            m.falhas().indisponivel = false;
            assertThat(estado(aviso)).isEqualTo(aplicado ? (short) 2 : (short) 1);
            assertThat(recuperar(aviso)).isEqualTo("RECUPERADA");
            assertThat(estado(aviso)).as("aplicado=%s", aplicado).isEqualTo(aplicado ? (short) 6 : (short) 5);
            assertThat(mail.callsFor(aviso)).isZero();
        }
        assertThat(saida.getOut()).contains("reivindicacao_incerta alerta=sim");
    }

    /** A varredura chega antes da reconciliacao: DESCONHECIDO -> a execucao nao entrega. */
    @Test
    void varreduraAntesDaReconciliacaoImpedeAEntrega() {
        Montado m = montar();
        UUID aviso = tentativaPendente();
        m.falhas().alvo = "ops_notice_claim";
        m.falhas().aplicar = true;
        m.falhas().antesDaFalha = () -> assertThat(recuperar(aviso)).isEqualTo("RECUPERADA");
        assertThat(m.avisos().dispatch(aviso)).isFalse();
        assertThat(m.falhas().disparos.get()).isEqualTo(1);
        assertThat(mail.callsFor(aviso)).isZero();
        assertThat(estado(aviso)).isEqualTo((short) 6);
    }

    /** Dois processos com a mesma tentativa EMITIDA: so o dono da reivindicacao pode entregar; o outro nao chama o transporte. */
    @Test
    void doisProcessosSoODonoDaReivindicacaoEntrega() {
        Montado a = montar();
        Montado b = montar();
        UUID aviso = tentativaPendente();
        emitir(aviso);
        assertThat(a.avisos().claim(aviso, UUID.randomUUID())).isEqualTo(EmergencyNotices.Claim.MINHA);
        assertThat(b.avisos().claim(aviso, UUID.randomUUID())).isEqualTo(EmergencyNotices.Claim.NAO_AUTORIZADA);
        assertThat(b.avisos().dispatch(aviso)).as("nao pendente: nao emite nem entrega").isFalse();
        assertThat(mail.callsFor(aviso)).isZero();
    }

    private static void emitir(UUID aviso) {
        try (Connection c = DB.comoServico("nekkora_platform");
             PreparedStatement ps = c.prepareStatement("SELECT r_emitido FROM public.ops_notice_issue(?, ?, ?)")) {
            ps.setObject(1, aviso);
            ps.setString(2, OpsBanco.sha256(OpsBanco.tokenBruto()));
            ps.setString(3, OpsBanco.sha256(OpsBanco.tokenBruto()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Queda depois de entregar e antes de registrar o resultado: reinicio nao reentrega; varredura -> DESCONHECIDO; uma chamada. */
    @Test
    void quedaDepoisDaEntregaNaoReentrega() {
        Montado m = montar();
        UUID aviso = tentativaPendente();
        m.falhas().falharSql = "ops_notice_delivery_result";
        assertThat(m.avisos().dispatch(aviso)).isTrue();
        assertThat(m.falhas().disparos.get()).isEqualTo(1);
        assertThat(estado(aviso)).isEqualTo((short) 2);
        Montado reiniciado = montar();
        assertThat(reiniciado.avisos().dispatch(aviso)).isFalse();
        assertThat(recuperar(aviso)).isEqualTo("RECUPERADA");
        assertThat(estado(aviso)).isEqualTo((short) 6);
        assertThat(reiniciado.avisos().dispatch(aviso)).isFalse();
        assertThat(mail.callsFor(aviso)).isEqualTo(1);
    }

    /** Transporte incerto: DESCONHECIDO, nenhuma repeticao automatica. */
    @Test
    void transporteIncertoFicaDesconhecidoSemRepeticao() {
        Montado m = montar();
        UUID aviso = tentativaPendente();
        MailSender.Outcome antes = mail.outcome;
        try {
            mail.outcome = MailSender.Outcome.INCERTO;
            assertThat(m.avisos().dispatch(aviso)).isTrue();
        } finally {
            mail.outcome = antes;
        }
        assertThat(estado(aviso)).isEqualTo((short) 6);
        assertThat(m.avisos().dispatch(aviso)).isFalse();
        assertThat(mail.callsFor(aviso)).isEqualTo(1);
    }
    /**
     * Achado da revisao da rodada 3, com o despachante real: a tentativa antiga (criada antes, ainda pendente) nao sai
     * depois que a nova foi aceita pelo transporte - nenhuma chamada ao transporte para ela; se a nova foi RECUSADA pelo
     * transporte (FALHOU), a antiga sai normalmente.
     */
    @Test
    void tentativaAntigaSoSaiSeANovaNaoFoiAceita() {
        for (boolean novaAceita : new boolean[] {true, false}) {
            Montado m = montar();
            Cenarios.Equipe eq = C.equipe();
            Cenarios.Alvo al = C.alvo();
            UUID b = DB.outroAdmin(al.org());
            UUID s = C.preAprovada(eq, al);
            UUID antiga = DB.valor(UUID.class, "SELECT notice_id FROM ops.ops_mfa_notices WHERE request_id = ? AND recipient_user_id = ? AND state = 0", s, b);
            Cenarios.exigirOk(eq.v().reenviarAviso(s, b), "reenviar");
            UUID nova = DB.valor(UUID.class, "SELECT notice_id FROM ops.ops_mfa_notices WHERE request_id = ? AND recipient_user_id = ? AND state = 0 "
                    + "ORDER BY seq DESC LIMIT 1", s, b);
            MailSender.Outcome antes = mail.outcome;
            try {
                mail.outcome = novaAceita ? MailSender.Outcome.ACEITO_PELO_TRANSPORTE : MailSender.Outcome.RECUSADO_DEFINITIVO;
                assertThat(m.avisos().dispatch(nova)).isTrue();
            } finally {
                mail.outcome = antes;
            }
            assertThat(estado(nova)).isEqualTo(novaAceita ? (short) 4 : (short) 5);
            assertThat(m.avisos().dispatch(antiga)).as("nova aceita=%s", novaAceita).isEqualTo(!novaAceita);
            assertThat(mail.callsFor(antiga)).isEqualTo(novaAceita ? 0 : 1);
            assertThat(estado(antiga)).isEqualTo(novaAceita ? (short) 5 : (short) 4);
            assertThat(mail.callsFor(nova)).isEqualTo(1);
        }
    }
}
