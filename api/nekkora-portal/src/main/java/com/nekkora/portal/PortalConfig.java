package com.nekkora.portal;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

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
import com.nekkora.core.web.ClientIpResolver;
import com.nekkora.core.web.RequestContextFilter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Datasource PROPRIO do portal (credencial do nekkora_portal, pool separado, processo separado).
 *
 * <p>Protecao do token em mensagens de erro do lado do cliente (desenho da 8.2, §4.5; missao 2.4):
 * {@code log_parameter_max_length_on_error=0} e fixado na abertura de cada conexao (opcao de inicializacao do
 * pgJDBC), conferido na CRIACAO de cada conexao fisica (connectionInitSql que falha se o valor for outro) e
 * conferido no startup: diferente -> o processo nao inicia. O DISCARD ALL da devolucao volta ao valor de
 * inicializacao da conexao (0) - provado em teste.
 */
@Configuration
public class PortalConfig {

    /**
     * Falha a criacao da conexao se o parametro nao for 0. O ramo de erro e uma expressao ESTAVEL (parametro
     * inexistente, 42704), avaliada so se escolhida: uma constante como 1/0 seria dobrada pelo planejador e falharia
     * sempre (bug encontrado no teste).
     */
    static final String LOG_CHECK_SQL = "SELECT CASE WHEN current_setting('log_parameter_max_length_on_error') = '0' THEN 1 "
            + "ELSE current_setting('nekkora.log_parameter_max_length_on_error_diferente_de_zero')::int END";

    @Bean(destroyMethod = "close")
    HikariDataSource hikariPortal(PortalProperties p) {
        HikariConfig c = new HikariConfig();
        c.setPoolName("portal");
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

    /** Recusa iniciar: papel errado, superusuario/BYPASSRLS, ou log_parameter_max_length_on_error diferente de 0. */
    static void verifyAtStartup(DataSource ds, String expectedRole) {
        try (Connection con = ds.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_user, r.rolsuper, r.rolbypassrls, current_setting('log_parameter_max_length_on_error') "
                     + "FROM pg_roles r WHERE r.rolname = current_user")) {
            rs.next();
            if (!expectedRole.equals(rs.getString(1)) || rs.getBoolean(2) || rs.getBoolean(3)) {
                throw new IllegalStateException("datasource do portal com papel inadequado");
            }
            if (!"0".equals(rs.getString(4))) {
                throw new IllegalStateException("log_parameter_max_length_on_error diferente de 0: o portal nao inicia");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("verificacao de inicio do portal falhou (sqlstate " + e.getSQLState() + ")");
        }
    }

    @Bean
    @Primary
    ConnectionResetDataSource dataSource(HikariDataSource hikariPortal) {
        return new ConnectionResetDataSource(hikariPortal);
    }

    @Bean
    PlatformTransactionManager transactionManager(ConnectionResetDataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    JdbcTemplate jdbcTemplate(ConnectionResetDataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    OrgTransactions orgTransactions(PlatformTransactionManager tm, ConnectionResetDataSource ds) {
        return new OrgTransactions(tm, JdbcClient.create(ds));
    }

    @Bean
    FilterRegistrationBean<RequestContextFilter> nekkoraRequestContextFilter(PortalProperties p) {
        FilterRegistrationBean<RequestContextFilter> r = new FilterRegistrationBean<>(new RequestContextFilter(new ClientIpResolver(p.trustedProxies())));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return r;
    }

    @Bean
    FilterRegistrationBean<ReadOnlyFilter> readOnlyFilter() {
        FilterRegistrationBean<ReadOnlyFilter> r = new FilterRegistrationBean<>(new ReadOnlyFilter());
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return r;
    }

    @Bean
    RedisLimiter redisLimiter(StringRedisTemplate redis) {
        return new RedisLimiter(redis);
    }
}
