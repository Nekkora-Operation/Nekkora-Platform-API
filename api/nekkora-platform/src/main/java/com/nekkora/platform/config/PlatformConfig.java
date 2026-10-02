package com.nekkora.platform.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import com.nekkora.core.db.ConnectionResetDataSource;
import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.ratelimit.RedisLimiter;
import com.nekkora.core.web.BodyLimitFilter;
import com.nekkora.core.web.ClientIpResolver;
import com.nekkora.core.web.RequestContextFilter;
import com.nekkora.platform.admin.AdminService;
import com.nekkora.platform.auth.AuthService;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.account.AccountRepository;
import com.nekkora.platform.limits.Limits;
import com.nekkora.platform.mail.LocalMailSender;
import com.nekkora.platform.mail.MailSender;
import com.nekkora.platform.mfa.MfaRepository;
import com.nekkora.platform.session.SessionRepository;
import com.nekkora.platform.web.PreAuthCsrf;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Beans da Platform. Datasource proprio (papel nekkora_platform), com reset explicito na devolucao ao pool. */
@Configuration
public class PlatformConfig {

    @Bean(destroyMethod = "close")
    HikariDataSource hikariPlatform(PlatformProperties p) {
        HikariConfig c = new HikariConfig();
        c.setPoolName("platform");
        c.setJdbcUrl(p.database().url());
        c.setUsername(p.database().user());
        c.setPassword(p.database().password());
        c.setMaximumPoolSize(p.database().maxPoolSize());
        c.setAutoCommit(true);
        HikariDataSource ds = new HikariDataSource(c);
        verifyRole(ds, p.database().user());
        return ds;
    }

    /** Recusa iniciar com superusuario, BYPASSRLS ou papel diferente do configurado. */
    private static void verifyRole(DataSource ds, String expected) {
        try (Connection con = ds.getConnection(); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user")) {
            rs.next();
            if (!expected.equals(rs.getString(1)) || rs.getBoolean(2) || rs.getBoolean(3)) {
                throw new IllegalStateException("datasource da Platform com papel inadequado");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("nao foi possivel conferir o papel do datasource (sqlstate " + e.getSQLState() + ")");
        }
    }

    @Bean
    @Primary
    ConnectionResetDataSource dataSource(HikariDataSource hikariPlatform) {
        return new ConnectionResetDataSource(hikariPlatform);
    }

    @Bean
    PlatformTransactionManager transactionManager(ConnectionResetDataSource ds) {
        return new DataSourceTransactionManager(ds);
    }

    @Bean
    JdbcClient jdbcClient(ConnectionResetDataSource ds) {
        return JdbcClient.create(ds);
    }

    @Bean
    OrgTransactions orgTransactions(PlatformTransactionManager tm, JdbcClient jdbc) {
        return new OrgTransactions(tm, jdbc);
    }

    @Bean
    FilterRegistrationBean<RequestContextFilter> nekkoraRequestContextFilter(PlatformProperties p) {
        FilterRegistrationBean<RequestContextFilter> r = new FilterRegistrationBean<>(new RequestContextFilter(new ClientIpResolver(p.trustedProxies())));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return r;
    }

    @Bean
    FilterRegistrationBean<BodyLimitFilter> bodyLimitFilter() {
        FilterRegistrationBean<BodyLimitFilter> r = new FilterRegistrationBean<>(new BodyLimitFilter(4096));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        r.addUrlPatterns("/api/*");
        return r;
    }

    @Bean
    RedisLimiter redisLimiter(StringRedisTemplate redis) {
        return new RedisLimiter(redis);
    }

    @Bean
    Limits limits(RedisLimiter redis, PlatformProperties p) {
        return new Limits(redis, p.limits());
    }

    @Bean
    PreAuthCsrf preAuthCsrf(StringRedisTemplate redis) {
        return new PreAuthCsrf(redis);
    }

    @Bean
    PasswordHasher passwordHasher(PlatformProperties p) {
        PlatformProperties.Argon2 a = p.argon2();
        return new PasswordHasher(new PasswordHasher.Parameters(a.memoryKib(), a.iterations(), a.parallelism()), a.maxConcurrent(), a.queue(), a.waitMs());
    }

    @Bean
    SecretCipher secretCipher(PlatformProperties p) {
        Map<String, byte[]> keys = new HashMap<>();
        for (String pair : p.keys().aesById().split(",")) {
            String[] kv = pair.trim().split(":", 2);
            keys.put(kv[0], Base64.getDecoder().decode(kv[1]));
        }
        return new SecretCipher(p.keys().aesCurrent(), keys);
    }

    @Bean
    IdentityHmac identityHmac(PlatformProperties p) {
        return new IdentityHmac(p.keys().hmacId(), Base64.getDecoder().decode(p.keys().hmac()));
    }

    @Bean
    PlatformProperties.Session sessionConfig(PlatformProperties p) {
        return p.session();
    }

    @Bean
    AccountRepository accountRepository() {
        return new AccountRepository();
    }

    @Bean
    SessionRepository sessionRepository(PlatformProperties p) {
        return new SessionRepository(p.session());
    }

    @Bean
    MfaRepository mfaRepository() {
        return new MfaRepository();
    }

    /**
     * Porta de e-mail. Padrao: {@link LocalMailSender} (so a intencao). {@code nekkora.mail.transport=homologacao} liga
     * o transporte de HOMOLOGACAO (caixa local, sem envio externo, desfechos simulados), que so existe com
     * {@code nekkora.environment=ensaio-*} e {@code nekkora.mail.caixa-dir} gravavel: sem isso, a Platform NAO sobe
     * (nunca cai silenciosamente no local). Qualquer outro valor de transporte tambem impede a partida.
     */
    @Bean
    @ConditionalOnMissingBean(MailSender.class)
    MailSender mailSender(@org.springframework.beans.factory.annotation.Value("${nekkora.mail.transport:local}") String transport,
                          @org.springframework.beans.factory.annotation.Value("${nekkora.mail.caixa-dir:}") String caixaDir,
                          @org.springframework.beans.factory.annotation.Value("${nekkora.environment:}") String environment) {
        return switch (transport == null ? "" : transport.trim()) {
            case "", "local" -> new LocalMailSender();
            case "homologacao" -> {
                if (caixaDir == null || caixaDir.isBlank()) {
                    throw new IllegalStateException("nekkora.mail.transport=homologacao exige nekkora.mail.caixa-dir");
                }
                yield new com.nekkora.platform.mail.CaixaHomologacaoMailSender(java.nio.file.Path.of(caixaDir), environment);
            }
            default -> throw new IllegalStateException("nekkora.mail.transport desconhecido (use local ou homologacao)");
        };
    }

    @Bean
    AuthService authService(OrgTransactions tx, AccountRepository accounts, SessionRepository sessions, MfaRepository mfa, PasswordHasher hasher,
                            SecretCipher secretCipher, IdentityHmac hmac, Limits limits, MailSender mail, PlatformProperties p) {
        return new AuthService(tx, accounts, sessions, mfa, hasher, secretCipher, hmac, limits, mail, p.session(), p.limits());
    }

    @Bean
    com.nekkora.platform.emergency.EmergencyNotices emergencyNotices(OrgTransactions tx, MailSender mail) {
        return new com.nekkora.platform.emergency.EmergencyNotices(tx, mail);
    }

    /**
     * Emissao periodica dos avisos da recuperacao emergencial: DESLIGADA por padrao (candidato 016, sem producao antes
     * da entrega real de e-mail validada - D3). Ligar so com {@code nekkora.emergency-notices.enabled=true}.
     */
    @Bean(destroyMethod = "shutdownNow")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "nekkora.emergency-notices.enabled", havingValue = "true")
    java.util.concurrent.ScheduledExecutorService emergencyNoticeDispatcher(com.nekkora.platform.emergency.EmergencyNotices notices) {
        java.util.concurrent.ScheduledExecutorService s = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "avisos-mfa-emergencia");
            t.setDaemon(true);
            return t;
        });
        s.scheduleWithFixedDelay(() -> {
            try {
                notices.dispatchPending();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(PlatformConfig.class).warn("evento=aviso_mfa_emergencia.ciclo_falhou");
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
        return s;
    }

    @Bean
    AdminService adminService(OrgTransactions tx, AccountRepository accounts, SessionRepository sessions, MfaRepository mfa, MailSender mail,
                              PlatformProperties p) {
        return new AdminService(tx, accounts, sessions, mfa, mail, p.session().stepUp());
    }
}
