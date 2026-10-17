package com.nekkora.platform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.platform.auth.PasswordPolicy;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.identity.IdentityAuthRepository;
import com.nekkora.platform.identity.IdentityLoginService;
import com.nekkora.platform.identity.IdentityOrgRepository;
import com.nekkora.platform.identity.IdentityOrganizationsService;
import com.nekkora.platform.identity.IdentityRepository;
import com.nekkora.platform.identity.IdentitySessionResolver;
import com.nekkora.platform.identity.IdentityStepUpService;
import com.nekkora.platform.identity.OrgSessionExchange;
import com.nekkora.platform.identity.OrgSessionIssuer;
import com.nekkora.platform.identity.PasswordRule;
import com.nekkora.platform.identity.SignupMailer;
import com.nekkora.platform.identity.SignupService;
import com.nekkora.platform.limits.Limits;
import com.nekkora.platform.mail.MailSender;
import com.nekkora.platform.session.LinkedSessionIssuer;
import com.nekkora.platform.session.SessionRepository;
import com.nekkora.platform.web.IdentityRoutesGate;

/**
 * Montagem dos servicos de identidade (Missao 1), separada de {@link PlatformConfig} para nao aumentar os ciclos de
 * {@code config} (levantamento de modularidade, §5). E aqui, e nao em {@code platform.identity}, que a porta
 * {@link PasswordRule} e ligada a {@link PasswordPolicy}: a identidade nao importa {@code platform.auth} fora de
 * {@code auth.crypto}.
 */
@Configuration
public class IdentityConfig {

    @Bean
    IdentityRepository identityRepository() {
        return new IdentityRepository();
    }

    @Bean
    IdentityAuthRepository identityAuthRepository() {
        return new IdentityAuthRepository();
    }

    /** Sessao e desafio de identidade a cada requisicao, para o IdentityFilter e o CsrfFilter. */
    @Bean
    IdentitySessionResolver identitySessionResolver(IdentityTransactions tx, IdentityAuthRepository auth) {
        return new IdentitySessionResolver(tx, auth);
    }

    /** Entrada geral (login, desafio, MFA, logout): desafio de 5 min e sessao de 12 h, os mesmos prazos da organizacao. */
    @Bean
    IdentityLoginService identityLoginService(IdentityTransactions tx, IdentityAuthRepository auth, PasswordHasher hasher, SecretCipher cipher,
                                              IdentityHmac hmac, Limits limits, MailSender mail, PlatformProperties p) {
        return new IdentityLoginService(tx, auth, hasher, cipher, hmac, limits, mail, p.session().challenge(), p.session().absolute());
    }

    /** Step-up da sessao de identidade (P1): TOTP do fator da identidade, anti-replay, evento 13. */
    @Bean
    IdentityStepUpService identityStepUpService(IdentityTransactions tx, IdentityAuthRepository auth, SecretCipher cipher, IdentityHmac hmac,
                                                Limits limits) {
        return new IdentityStepUpService(tx, auth, cipher, hmac, limits);
    }

    @Bean
    IdentityOrgRepository identityOrgRepository() {
        return new IdentityOrgRepository();
    }

    @Bean
    IdentityOrganizationsService identityOrganizationsService(IdentityTransactions tx, IdentityOrgRepository orgs, Limits limits,
                                                              @Value("${nekkora.entrada-geral.enabled:false}") boolean entryEnabled,
                                                              @Value("${nekkora.criacao-organizacao.enabled:false}") boolean creationEnabled) {
        return new IdentityOrganizationsService(tx, orgs, limits, entryEnabled && creationEnabled);
    }

    /**
     * Porta de emissao (levantamento de modularidade, §4.3): o lado da organizacao ({@link LinkedSessionIssuer}) ligado aqui a
     * interface da identidade, que nao o conhece. A transacao e de quem chama (emissao e evento no mesmo commit).
     */
    @Bean
    OrgSessionIssuer orgSessionIssuer(SessionRepository sessions) {
        return new LinkedSessionIssuer(sessions)::issue;
    }

    /** Troca por sessao de organizacao: piso de tempo igual ao de requestRecovery nas duas respostas (DC-17.6). */
    @Bean
    OrgSessionExchange orgSessionExchange(IdentityTransactions tx, IdentityOrgRepository orgs, OrgSessionIssuer issuer, Limits limits,
                                          PlatformProperties p) {
        return new OrgSessionExchange(tx, orgs, issuer, limits, p.limits().recoveryResponseFloor());
    }

    /** E-mails do cadastro fora do caminho da resposta: 2 threads, fila de 1000 (cheia: descarta e registra). */
    @Bean(destroyMethod = "close")
    SignupMailer signupMailer(MailSender mail) {
        return new SignupMailer(mail, 2, 1000);
    }

    @Bean
    SignupService signupService(IdentityTransactions tx, IdentityRepository identities, PasswordHasher hasher, IdentityHmac hmac, Limits limits,
                                SignupMailer mailer, PlatformProperties p) {
        return new SignupService(tx, identities, hasher, PasswordPolicy::enforce, hmac, limits, mailer, p.session().emailVerification(),
                p.limits().recoveryResponseFloor());
    }

    /** Chaves separadas do cadastro, da entrada geral e da criacao de organizacao: antes da cadeia de seguranca, depois do contexto e do limite de corpo. */
    @Bean
    FilterRegistrationBean<IdentityRoutesGate> identityRoutesGate(@Value("${nekkora.cadastro.enabled:false}") boolean signupEnabled,
                                                                  @Value("${nekkora.entrada-geral.enabled:false}") boolean entryEnabled,
                                                                  @Value("${nekkora.criacao-organizacao.enabled:false}") boolean creationEnabled) {
        FilterRegistrationBean<IdentityRoutesGate> r = new FilterRegistrationBean<>(new IdentityRoutesGate(signupEnabled, entryEnabled, creationEnabled));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        r.addUrlPatterns("/api/*");
        return r;
    }
}
