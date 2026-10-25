package com.nekkora.platform.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.beans.factory.ObjectProvider;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.emergency.EmergencyNotices;
import com.nekkora.platform.identity.SignupMailer;
import com.nekkora.platform.mail.AfterCommitMailIntents;
import com.nekkora.platform.mail.CaixaHomologacaoMailSender;
import com.nekkora.platform.mail.DestinatariosPermitidos;
import com.nekkora.platform.mail.M365Segredo;
import com.nekkora.platform.mail.MailQueueCipher;
import com.nekkora.platform.mail.MailTransport;
import com.nekkora.platform.secrets.AwsSecretsManager;
import com.nekkora.platform.mail.GraphMailTransport;
import com.nekkora.platform.mail.MailDispatcher;
import com.nekkora.platform.mail.MailIntents;
import com.nekkora.platform.mail.MailSender;
import com.nekkora.platform.mail.MailTemplates;
import com.nekkora.platform.mail.OutboxMailIntents;

/**
 * Transporte real de e-mail (missao 002, PR 5; desenho de 2026-10-11): so com {@code nekkora.mail.transport=m365}. Sem
 * tenant, cliente, certificado legivel ou remetente, a Platform NAO sobe (nunca cai no transporte local). Cliente e
 * certificado vem de um arquivo PKCS#12 montado ou, com {@code nekkora.mail.m365.segredo-aws}, do AWS Secrets Manager
 * (lido uma vez, na partida). A porta
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
                                          @Value("${nekkora.mail.m365.proxy:}") String proxy,
                                          @Value("${nekkora.mail.m365.segredo-aws:}") String awsSecret,
                                          @Value("${nekkora.mail.m365.aws-regiao:}") String awsRegion) {
        GraphMailTransport t;
        if (!awsSecret.isBlank()) {
            // cliente e certificado SO do segredo: uma unica origem, sem mistura com arquivo ou variavel
            if (!certificate.isBlank() || !certificatePassword.isEmpty() || !client.isBlank()) {
                throw new IllegalStateException("com nekkora.mail.m365.segredo-aws, client-id, certificado e certificado-senha ficam vazios (vem do segredo)");
            }
            AwsSecretsManager.Secret secret = new AwsSecretsManager(awsRegion, AwsSecretsManager.Credentials.fromEnvironment(System.getenv()),
                    GraphMailTransport.client(timeout, proxy), timeout).getSecretString(awsSecret);
            M365Segredo m = M365Segredo.ler(secret.value(), tenant);
            try {
                t = new GraphMailTransport(new GraphMailTransport.Config(tenant, m.clientId(), sender, null, m.senha(), timeout, login, graph, proxy),
                        m.pkcs12());
            } finally {
                m.apagar();
            }
            LOG.info("evento=mail.segredo_carregado origem=aws-secrets-manager versao={}", secret.versionId());
        } else {
            if (certificate == null || certificate.isBlank()) {
                throw new IllegalStateException("nekkora.mail.transport=m365 exige nekkora.mail.m365.certificado ou nekkora.mail.m365.segredo-aws");
            }
            t = new GraphMailTransport(new GraphMailTransport.Config(tenant, client, sender, Path.of(certificate),
                    certificatePassword.toCharArray(), timeout, login, graph, proxy));
        }
        logCertificate(t);
        return t;
    }

    /**
     * Cifra da fila (021): chave PROPRIA ({@code nekkora.mail.fila.chave-id} e {@code nekkora.mail.fila.chaves}, formato de
     * {@code NEKKORA_MFA_CHAVES}), separada da chave do MFA. Sem ela, a fila continua com a chave do MFA (como antes). Com ela,
     * a fila cifra so com a chave propria e ainda DECIFRA com as do MFA: as mensagens ja enfileiradas continuam legiveis
     * (o key_id gravado em cada linha escolhe a chave). Um id que exista nas duas listas recusa a partida.
     */
    @Bean
    @ConditionalOnProperty(name = "nekkora.mail.transport", havingValue = "m365")
    MailQueueCipher mailQueueCipher(SecretCipher mfa, PlatformProperties p, @Value("${nekkora.mail.fila.chave-id:}") String current,
                                    @Value("${nekkora.mail.fila.chaves:}") String keys) {
        return queueCipher(mfa, p.keys().aesById(), current, keys);
    }

    static MailQueueCipher queueCipher(SecretCipher mfa, String mfaKeys, String current, String keys) {
        if (current.isBlank() && keys.isBlank()) {
            LOG.info("evento=mail.fila_cifra chave=mfa");
            return new MailQueueCipher(mfa);
        }
        if (current.isBlank() || keys.isBlank()) {
            throw new IllegalStateException("nekkora.mail.fila.chave-id e nekkora.mail.fila.chaves vao juntas");
        }
        Map<String, byte[]> all = SecretCipher.parseKeys(keys);
        Map<String, byte[]> legacy = SecretCipher.parseKeys(mfaKeys);
        if (!all.containsKey(current)) {
            throw new IllegalStateException("nekkora.mail.fila.chave-id fora de nekkora.mail.fila.chaves");
        }
        for (Map.Entry<String, byte[]> e : legacy.entrySet()) {
            if (all.putIfAbsent(e.getKey(), e.getValue()) != null) {
                throw new IllegalStateException("id de chave da fila repetido na lista do MFA");
            }
        }
        LOG.info("evento=mail.fila_cifra chave=propria id={} decifra_legado_mfa={}", current, legacy.size());
        return new MailQueueCipher(new SecretCipher(current, all));
    }

    /**
     * Intencao duravel (m365): a fila gravada na transacao do ato. Prazo da mensagem = prazo do token que ela leva (recuperacao
     * de senha 30 min, cadastro 24 h); avisos 24 h; avisos da recuperacao emergencial 23 h (a 016 encerra a tentativa
     * ENFILEIRADA como DESCONHECIDO em 24 h).
     */
    @Bean
    @ConditionalOnProperty(name = "nekkora.mail.transport", havingValue = "m365")
    OutboxMailIntents outboxMailIntents(OrgTransactions tx, MailQueueCipher cipher, IdentityHmac hmac, PlatformProperties p) {
        return new OutboxMailIntents(tx, cipher.cipher(), hmac, kind -> switch (kind) {
            case PASSWORD_RECOVERY -> p.session().passwordRecovery();
            case IDENTITY_SIGNUP_CONFIRMATION -> p.session().emailVerification();
            case NOTICE_MFA_EMERGENCY_HOLDER, NOTICE_MFA_EMERGENCY_ADMIN, NOTICE_MFA_EMERGENCY_EXECUTED -> Duration.ofHours(23);
            default -> Duration.ofHours(24);
        });
    }

    /**
     * Porta das intencoes, usada por todos os servicos DENTRO da transacao do ato: duravel no m365; nos demais transportes,
     * envio depois do commit (cadastro e recuperacao de senha da identidade pela fila em memoria do cadastro).
     */
    @Bean
    @org.springframework.context.annotation.Primary
    MailIntents mailIntents(@Value("${nekkora.mail.transport:local}") String transport, ObjectProvider<OutboxMailIntents> outbox, MailSender mail,
                            SignupMailer mailer) {
        return "m365".equals(transport == null ? "" : transport.trim()) ? outbox.getObject() : new AfterCommitMailIntents(mail, mailer::enqueue);
    }

    @Bean
    @ConditionalOnProperty(name = "nekkora.mail.transport", havingValue = "m365")
    MailDispatcher mailDispatcher(OrgTransactions tx, MailQueueCipher cipher, GraphMailTransport transport, MailTemplates templates,
                                  ObjectProvider<EmergencyNotices> notices,
                                  @Value("${nekkora.environment:}") String environment,
                                  @Value("${nekkora.mail.destinatarios-permitidos:}") String allowed,
                                  @Value("${nekkora.mail.despacho.por-minuto:25}") int perMinute,
                                  @Value("${nekkora.mail.despacho.posse:2m}") Duration lease,
                                  @Value("${nekkora.mail.m365.timeout:5s}") Duration timeout) {
        // a posse precisa cobrir o token e o envio com folga: menos que 3x o tempo maximo do transporte, recusa a partida
        if (lease.compareTo(timeout.multipliedBy(3)) < 0) {
            throw new IllegalStateException("nekkora.mail.despacho.posse menor que 3x nekkora.mail.m365.timeout");
        }
        return new MailDispatcher(tx, cipher.cipher(), restrict(transport, environment, allowed), templates, perMinute, lease,
                (notice, outcome) -> notices.getObject().recordTransportResult(notice, outcome));
    }

    /** Ciclo da fila a cada 5 s; retencao a cada hora; alarme do certificado uma vez por dia (60 e 30 dias, DC-M2.4). */
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
            s.scheduleWithFixedDelay(() -> {
                try {
                    dispatcher.purgeFinished();
                } catch (RuntimeException e) {
                    LOG.warn("evento=mail.retencao_falhou causa={}", e.getClass().getSimpleName());
                }
            }, 1, 60, TimeUnit.MINUTES);
        }
        s.scheduleWithFixedDelay(() -> logCertificate(transport), 1, 1, TimeUnit.DAYS);
        return s;
    }

    /**
     * Destinatarios do transporte real. Em {@code ensaio-*} (homologacao), a lista fechada e OBRIGATORIA: sem ela a Platform
     * nao sobe. Fora dele, so se configurada.
     */
    static MailTransport restrict(MailTransport transport, String environment, String allowed) {
        Set<String> list = DestinatariosPermitidos.ler(allowed);
        boolean homologation = environment != null && CaixaHomologacaoMailSender.AMBIENTE.matcher(environment).matches();
        if (list.isEmpty()) {
            if (homologation) {
                throw new IllegalStateException("transporte m365 em ensaio-* exige nekkora.mail.destinatarios-permitidos");
            }
            return transport;
        }
        DestinatariosPermitidos d = new DestinatariosPermitidos(transport, list);
        LOG.info("evento=mail.destinatarios_restritos quantidade={}", d.tamanho());
        return d;
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
