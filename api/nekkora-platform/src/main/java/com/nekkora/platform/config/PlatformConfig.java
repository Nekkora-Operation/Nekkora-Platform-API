package com.nekkora.platform.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;

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
import com.nekkora.core.db.IdentityTransactions;
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

    /** Contexto de identidade da 017 (contrato da integracao Java, §1). Infraestrutura: as rotas que o usam vem depois. */
    @Bean
    IdentityTransactions identityTransactions(PlatformTransactionManager tm, JdbcClient jdbc) {
        return new IdentityTransactions(tm, jdbc);
    }

    @Bean
    FilterRegistrationBean<RequestContextFilter> nekkoraRequestContextFilter(PlatformProperties p) {
        FilterRegistrationBean<RequestContextFilter> r = new FilterRegistrationBean<>(new RequestContextFilter(new ClientIpResolver(p.trustedProxies())));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return r;
    }

    @Bean
    FilterRegistrationBean<BodyLimitFilter> bodyLimitFilter() {
        FilterRegistrationBean<BodyLimitFilter> r = new FilterRegistrationBean<>(new BodyLimitFilter(4096, "PUT",
                java.util.regex.Pattern.compile("/api/v1/o/[0-9a-f-]{36}/automation-versions/[0-9a-f-]{36}/artifact"),
                com.nekkora.platform.artifact.PackageInspector.MAX_ZIP_BYTES));
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
        return new SecretCipher(p.keys().aesCurrent(), SecretCipher.parseKeys(p.keys().aesById()));
    }

    @Bean
    IdentityHmac identityHmac(PlatformProperties p) {
        return new IdentityHmac(p.keys().hmacId(), Base64.getDecoder().decode(p.keys().hmac()));
    }

    /** Cursor das rotas internas: subchave derivada da chave HMAC de servico (DC-15); a rotacao dela invalida os cursores. */
    @Bean
    com.nekkora.platform.paging.CursorCodec cursorCodec(PlatformProperties p) {
        return new com.nekkora.platform.paging.CursorCodec(p.keys().hmacId(), Base64.getDecoder().decode(p.keys().hmac()));
    }

    /** Regra unica de acesso por workspace (contrato GAP-02/03/06, §1.2), compartilhada pelas rotas internas. */
    @Bean
    com.nekkora.platform.access.WorkspaceAccess workspaceAccess() {
        return new com.nekkora.platform.access.WorkspaceAccess();
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
     * (nunca cai silenciosamente no local). {@code m365} (missao 002, PR 5): fila persistente ({@code OutboxMailSender}) com o
     * transporte Microsoft Graph de {@link MailConfig}, que recusa a partida sem tenant, cliente, certificado ou remetente.
     * Qualquer outro valor de transporte tambem impede a partida.
     */
    @Bean
    @ConditionalOnMissingBean(MailSender.class)
    MailSender mailSender(@org.springframework.beans.factory.annotation.Value("${nekkora.mail.transport:local}") String transport,
                          @org.springframework.beans.factory.annotation.Value("${nekkora.mail.caixa-dir:}") String caixaDir,
                          @org.springframework.beans.factory.annotation.Value("${nekkora.environment:}") String environment,
                          org.springframework.beans.factory.ObjectProvider<com.nekkora.platform.mail.OutboxMailIntents> outbox) {
        return switch (transport == null ? "" : transport.trim()) {
            case "m365" -> new com.nekkora.platform.mail.OutboxMailSender(outbox.getObject());
            case "", "local" -> new LocalMailSender();
            case "homologacao" -> {
                if (caixaDir == null || caixaDir.isBlank()) {
                    throw new IllegalStateException("nekkora.mail.transport=homologacao exige nekkora.mail.caixa-dir");
                }
                yield new com.nekkora.platform.mail.CaixaHomologacaoMailSender(java.nio.file.Path.of(caixaDir), environment);
            }
            default -> throw new IllegalStateException("nekkora.mail.transport desconhecido (use local, homologacao ou m365)");
        };
    }

    @Bean
    AuthService authService(OrgTransactions tx, AccountRepository accounts, SessionRepository sessions, MfaRepository mfa, PasswordHasher hasher,
                            SecretCipher secretCipher, IdentityHmac hmac, Limits limits, com.nekkora.platform.mail.MailIntents mail, PlatformProperties p) {
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
    AdminService adminService(OrgTransactions tx, AccountRepository accounts, SessionRepository sessions, MfaRepository mfa, com.nekkora.platform.mail.MailIntents mail,
                              PlatformProperties p) {
        return new AdminService(tx, accounts, sessions, mfa, mail, p.session().stepUp());
    }

    /** Administracao de acesso (023): convites, papeis, vinculo e concessoes, com a mesma janela de step-up do reset de MFA. */
    @Bean
    com.nekkora.platform.admin.AccessActions accessActions(OrgTransactions tx, AccountRepository accounts, SessionRepository sessions, PlatformProperties p) {
        return new com.nekkora.platform.admin.AccessActions(tx, accounts, sessions, p.session().stepUp());
    }

    @Bean
    com.nekkora.platform.admin.AccessEvents accessEvents() {
        return new com.nekkora.platform.admin.AccessEvents();
    }

    @Bean
    com.nekkora.platform.admin.InvitationService invitationService(com.nekkora.platform.admin.AccessActions actions,
                                                                  com.nekkora.platform.admin.AccessEvents events, com.nekkora.platform.mail.MailIntents mail) {
        return com.nekkora.platform.admin.InvitationService.create(actions, events, mail);
    }

    @Bean
    com.nekkora.platform.admin.RoleAdminService roleAdminService(com.nekkora.platform.admin.AccessActions actions,
                                                                com.nekkora.platform.admin.AccessEvents events, MfaRepository mfa,
                                                                com.nekkora.platform.mail.MailIntents mail) {
        return com.nekkora.platform.admin.RoleAdminService.create(actions, events, mfa, mail);
    }

    @Bean
    com.nekkora.platform.admin.GrantAdminService grantAdminService(com.nekkora.platform.admin.AccessActions actions,
                                                                  com.nekkora.platform.admin.AccessEvents events) {
        return com.nekkora.platform.admin.GrantAdminService.create(actions, events);
    }

    /** Canal do Agent (025), lado das pessoas: codigos, revogacao, Worker no workspace, autorizacao de execucao local. */
    @Bean
    com.nekkora.platform.admin.AgentAdminService agentAdminService(com.nekkora.platform.admin.AccessActions actions,
                                                                  com.nekkora.platform.access.WorkspaceAccess access) {
        return new com.nekkora.platform.admin.AgentAdminService(actions, access);
    }

    /**
     * Armazenamento dos artefatos recebidos (PLATFORM-AUTOMATION-LIFECYCLE-001): pasta local SO se NEKKORA_ARTEFATOS_DIR estiver
     * definida (laboratorio); sem ela, as rotas de artefato respondem 503 e nada e gravado.
     */
    @Bean
    com.nekkora.platform.artifact.ArtifactStore artifactStore(org.springframework.core.env.Environment env) throws java.io.IOException {
        String dir = env.getProperty("NEKKORA_ARTEFATOS_DIR", env.getProperty("nekkora.artefatos.dir", ""));
        return dir.isBlank() ? com.nekkora.platform.artifact.ArtifactStore.NONE
                : new com.nekkora.platform.artifact.LocalArtifactStore(java.nio.file.Path.of(dir));
    }

    /** Ciclo de vida das automacoes (workspace, automacao, versao, artefato, publicacao). */
    @Bean
    com.nekkora.platform.admin.AutomationLifecycleService automationLifecycleService(com.nekkora.platform.admin.AccessActions actions,
                                                                                    com.nekkora.platform.access.WorkspaceAccess access,
                                                                                    com.nekkora.platform.artifact.ArtifactStore store) {
        return new com.nekkora.platform.admin.AutomationLifecycleService(actions, access, store);
    }
}
