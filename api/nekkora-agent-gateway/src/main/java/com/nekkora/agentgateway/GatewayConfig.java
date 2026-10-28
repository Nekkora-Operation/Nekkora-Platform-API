package com.nekkora.agentgateway;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.nekkora.core.db.ConnectionResetDataSource;
import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.ratelimit.RedisLimiter;
import com.nekkora.core.web.BodyLimitFilter;
import com.nekkora.core.web.ClientIpResolver;
import com.nekkora.core.web.RequestContextFilter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Datasource PROPRIO do canal (credencial do nekkora_ingestion, pool e processo separados da Platform). Como no portal,
 * {@code log_parameter_max_length_on_error=0} em toda conexao: nenhum parametro de comando vai para o log do servidor de
 * banco num erro (o canal so manda hashes, mas a regra e a mesma). O processo nao inicia com papel errado, superusuario
 * ou BYPASSRLS.
 */
@Configuration
public class GatewayConfig {

    /** Limite do corpo: um lote de ate 500 envelopes de ate 4 KiB cabe com folga; maior que isso e recusado sem ler. */
    static final long MAX_BODY_BYTES = 2L * 1024 * 1024;

    static final String LOG_CHECK_SQL = "SELECT CASE WHEN current_setting('log_parameter_max_length_on_error') = '0' THEN 1 "
            + "ELSE current_setting('nekkora.log_parameter_max_length_on_error_diferente_de_zero')::int END";

    @Bean(destroyMethod = "close")
    HikariDataSource hikariGateway(GatewayProperties p) {
        HikariConfig c = new HikariConfig();
        c.setPoolName("agent-gateway");
        c.setJdbcUrl(p.database().url());
        c.setUsername(p.database().user());
        c.setPassword(p.database().password());
        c.setMaximumPoolSize(p.database().maxPoolSize());
        c.addDataSourceProperty("options", "-c log_parameter_max_length_on_error=0");
        c.setConnectionInitSql(LOG_CHECK_SQL);
        HikariDataSource ds = new HikariDataSource(c);
        verifyAtStartup(ds, p.database().user());
        return ds;
    }

    static void verifyAtStartup(DataSource ds, String expectedRole) {
        try (Connection con = ds.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_user, r.rolsuper, r.rolbypassrls, current_setting('log_parameter_max_length_on_error') "
                     + "FROM pg_roles r WHERE r.rolname = current_user")) {
            rs.next();
            if (!"nekkora_ingestion".equals(expectedRole) || !expectedRole.equals(rs.getString(1)) || rs.getBoolean(2) || rs.getBoolean(3)) {
                throw new IllegalStateException("datasource do canal com papel inadequado");
            }
            if (!"0".equals(rs.getString(4))) {
                throw new IllegalStateException("log_parameter_max_length_on_error diferente de 0: o canal nao inicia");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("verificacao de inicio do canal falhou (sqlstate " + e.getSQLState() + ")");
        }
    }

    @Bean
    @Primary
    ConnectionResetDataSource dataSource(HikariDataSource hikariGateway) {
        return new ConnectionResetDataSource(hikariGateway);
    }

    @Bean
    PlatformTransactionManager transactionManager(ConnectionResetDataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    OrgTransactions orgTransactions(PlatformTransactionManager tm, ConnectionResetDataSource ds) {
        return new OrgTransactions(tm, JdbcClient.create(ds));
    }

    @Bean
    FilterRegistrationBean<RequestContextFilter> nekkoraRequestContextFilter(GatewayProperties p) {
        FilterRegistrationBean<RequestContextFilter> r = new FilterRegistrationBean<>(new RequestContextFilter(new ClientIpResolver(p.trustedProxies())));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return r;
    }

    @Bean
    FilterRegistrationBean<BodyLimitFilter> bodyLimitFilter() {
        FilterRegistrationBean<BodyLimitFilter> r = new FilterRegistrationBean<>(new BodyLimitFilter(MAX_BODY_BYTES));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return r;
    }

    @Bean
    RedisLimiter redisLimiter(StringRedisTemplate redis) {
        return new RedisLimiter(redis);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MachineAuth machineAuth() {
        return new MachineAuth();
    }

    @Bean
    GatewayService gatewayService(OrgTransactions tx, MachineAuth auth, GatewayProperties p) {
        return new GatewayService(tx, auth, p.limits().credentialDays());
    }

    /** Mesmo DataSource: dentro da transacao da organizacao, usa a conexao vinculada a thread (contexto e RLS valem). */
    @Bean
    JdbcTemplate jdbcTemplate(ConnectionResetDataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    EventIngestion eventIngestion(OrgTransactions tx, MachineAuth auth, JdbcTemplate jdbcTemplate) {
        return new EventIngestion(tx, auth, jdbcTemplate);
    }
}
