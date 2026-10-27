package com.nekkora.platform.emergency;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.platform.mail.MailSender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Gatilho MANUAL da varredura de tentativas de aviso paradas da recuperacao emergencial (016; plano de homologacao, B10:
 * "varredura acionada manualmente"). Roda no container da Platform, com a identidade e as variaveis dela (a Platform e a
 * unica identidade de servico com EXECUTE em {@code ops_notice_stalled} e {@code ops_notice_recover}, e so conecta do
 * proprio container):
 * <pre>
 * java -cp app.jar -Dloader.main=com.nekkora.platform.emergency.VarreduraAvisos org.springframework.boot.loader.launch.PropertiesLauncher
 * </pre>
 * Chama {@link EmergencyNotices#recoverStalled()} UMA vez: as tentativas EMITIDA/ENTREGANDO paradas ha mais de 10 min e as
 * ENFILEIRADA ha mais de 24 h (criterios da 016, no banco) vao para DESCONHECIDO, nunca para ACEITA; nada e entregue. Nao
 * agenda nada: o agendamento em producao continua sendo a decisao C9 (monitoracao e alerta). Imprime so a contagem; nunca
 * token, hash ou destinatario. Codigo de saida: 0 executada (mesmo com zero recuperadas), 64 configuracao ausente, 70 falha.
 */
public final class VarreduraAvisos {

    private VarreduraAvisos() {
    }

    public static void main(String[] args) {
        System.exit(run(System.getenv(), System.out, System.err));
    }

    static int run(Map<String, String> env, java.io.PrintStream out, java.io.PrintStream err) {
        String url = env.get("NEKKORA_DB_URL");
        String user = env.getOrDefault("NEKKORA_DB_USUARIO", "nekkora_platform");
        String password = env.get("NEKKORA_DB_SENHA");
        if (url == null || url.isBlank() || password == null) {
            err.println("uso: no container da Platform (NEKKORA_DB_URL e NEKKORA_DB_SENHA obrigatorias)");
            return 64;
        }
        HikariConfig c = new HikariConfig();
        c.setPoolName("varredura-avisos");
        c.setJdbcUrl(url);
        c.setUsername(user);
        c.setPassword(password);
        c.setMaximumPoolSize(1);
        c.setAutoCommit(true);
        try (HikariDataSource ds = new HikariDataSource(c)) {
            // mesma recusa da Platform: nunca com superusuario, BYPASSRLS ou outro papel
            try (Connection con = ds.getConnection(); Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
                rs.next();
                if (!user.equals(rs.getString(1)) || rs.getBoolean(2) || rs.getBoolean(3)) {
                    err.println("varredura_avisos papel_inadequado");
                    return 64;
                }
            }
            EmergencyNotices notices = new EmergencyNotices(new OrgTransactions(new DataSourceTransactionManager(ds), JdbcClient.create(ds)),
                    new SemEntrega());
            int n = notices.recoverStalled();
            out.println("varredura_avisos executada recuperadas=" + n);
            return 0;
        } catch (RuntimeException | java.sql.SQLException e) {
            // so a classe e o SQLSTATE: a mensagem do driver pode citar a URL
            err.println("varredura_avisos falha classe=" + e.getClass().getSimpleName()
                    + (e instanceof java.sql.SQLException s ? " sqlstate=" + s.getSQLState() : ""));
            return 70;
        }
    }

    /** A varredura nunca entrega: qualquer chamada ao transporte e um defeito. */
    private static final class SemEntrega implements MailSender {
        @Override
        public void send(Kind kind, UUID organization, String recipient, String tokenOrNull) {
            throw new IllegalStateException("a varredura nao entrega mensagens");
        }

        @Override
        public Outcome sendEmergencyNotice(Kind kind, UUID organization, String recipient, String confirmTokenOrNull, String contestTokenOrNull,
                                           UUID attempt) {
            throw new IllegalStateException("a varredura nao entrega mensagens");
        }
    }
}
