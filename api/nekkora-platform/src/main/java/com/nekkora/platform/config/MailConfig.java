package com.nekkora.platform.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.mail.GraphMailTransport;
import com.nekkora.platform.mail.MailDispatcher;
import com.nekkora.platform.mail.MailTemplates;

/**
 * Transporte real de e-mail (missao 002, PR 5; desenho de 2026-10-11): so com {@code nekkora.mail.transport=m365}. Sem
 * tenant, cliente, certificado legivel ou remetente, a Platform NAO sobe (nunca cai no transporte local). A porta
 * {@code MailSender} desse modo e a fila persistente ({@code OutboxMailSender}, em {@link PlatformConfig}); aqui ficam o
 * transporte Graph, os modelos de mensagem, o despacho periodico da fila e o alarme de vencimento do certificado.
 */
@Configuration
public class MailConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    MailTemplates mailTemplates(PlatformProperties p) {
        return new MailTemplates(p.publicOrigin());
    }

    @Bean
    @ConditionalOnProperty(name = "nekkora.mail.transport", havingValue = "m365")
    GraphMailTransport graphMailTransport(@Value("${nekkora.mail.m365.tenant-id:}") String tenant,
                                          @Value("${nekkora.mail.m365.client-id:}") String client,
                                          @Value("${nekkora.mail.remetente:}") String sender,
                                          @Value("${nekkora.mail.m365.certificado:}") String certificate,
                                          @Value("${nekkora.mail.m365.certificado-senha:}") String certificatePassword,
                                          @Value("${nekkora.mail.m365.timeout:5s}") Duration timeout,
                                          @Value("${nekkora.mail.m365.login-url:https://login.microsoftonline.com}") String login,
                                          @Value("${nekkora.mail.m365.graph-url:https://graph.microsoft.com}") String graph,
                                          @Value("${nekkora.mail.m365.proxy:}") String proxy) {
        if (certificate == null || certificate.isBlank()) {
            throw new IllegalStateException("nekkora.mail.transport=m365 exige nekkora.mail.m365.certificado");
        }
        GraphMailTransport t = new GraphMailTransport(new GraphMailTransport.Config(tenant, client, sender, Path.of(certificate),
                certificatePassword.toCharArray(), timeout, login, graph, proxy));
        logCertificate(t);
        return t;
    }

    @Bean
    @ConditionalOnProperty(name = "nekkora.mail.transport", havingValue = "m365")
    MailDispatcher mailDispatcher(OrgTransactions tx, SecretCipher cipher, GraphMailTransport transport, MailTemplates templates,
                                  @Value("${nekkora.mail.despacho.por-minuto:25}") int perMinute) {
        return new MailDispatcher(tx, cipher, transport, templates, perMinute);
    }

    /** Ciclo da fila a cada 5 s; alarme do certificado uma vez por dia (60 e 30 dias, DC-M2.4). Desligavel para testes. */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(name = "nekkora.mail.transport", havingValue = "m365")
    ScheduledExecutorService mailDispatcherScheduler(MailDispatcher dispatcher, GraphMailTransport transport,
                                                     @Value("${nekkora.mail.despacho.enabled:true}") boolean enabled) {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "despacho-email");
            th.setDaemon(true);
            return th;
        });
        if (enabled) {
            s.scheduleWithFixedDelay(() -> {
                try {
                    dispatcher.dispatchPending();
                } catch (RuntimeException e) {
                    LOG.warn("evento=mail.ciclo_falhou causa={}", e.getClass().getSimpleName());
                }
            }, 5, 5, TimeUnit.SECONDS);
        }
        s.scheduleWithFixedDelay(() -> logCertificate(transport), 1, 1, TimeUnit.DAYS);
        return s;
    }

    private static void logCertificate(GraphMailTransport t) {
        long days = t.certificateDaysLeft();
        if (days <= 30) {
            LOG.error("evento=mail.certificado_vence_em dias={} impressao={} alerta=critico", days, t.certificateThumbprint());
        } else if (days <= 60) {
            LOG.warn("evento=mail.certificado_vence_em dias={} impressao={} alerta=sim", days, t.certificateThumbprint());
        } else {
            LOG.info("evento=mail.certificado_vence_em dias={} impressao={}", days, t.certificateThumbprint());
        }
    }
}
