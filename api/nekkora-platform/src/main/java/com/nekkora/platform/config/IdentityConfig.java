package com.nekkora.platform.config;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.platform.account.AccountRepository;
import com.nekkora.platform.auth.LegacyAccountProof;
import com.nekkora.platform.auth.PasswordPolicy;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.identity.AccountLinkService;
import com.nekkora.platform.identity.IdentityAuthRepository;
import com.nekkora.platform.identity.IdentityCredentialService;
import com.nekkora.platform.identity.IdentityLinkRepository;
import com.nekkora.platform.identity.IdentityLoginService;
import com.nekkora.platform.identity.IdentityOrgRepository;
import com.nekkora.platform.identity.IdentityOrganizationsService;
import com.nekkora.platform.identity.IdentityRepository;
import com.nekkora.platform.identity.IdentitySessionResolver;
import com.nekkora.platform.identity.IdentityStepUpService;
import com.nekkora.platform.identity.LegacyAccountPort;
import com.nekkora.platform.identity.LinkedOrgSessionPort;
import com.nekkora.platform.identity.LinkedOrgStepUpService;
import com.nekkora.platform.identity.OrgSessionExchange;
import com.nekkora.platform.identity.OrgSessionIssuer;
import com.nekkora.platform.identity.PasswordRule;
import com.nekkora.platform.identity.SignupMailer;
import com.nekkora.platform.identity.SignupService;
import com.nekkora.platform.limits.Limits;
import com.nekkora.platform.mail.MailSender;
import com.nekkora.platform.mfa.LinkedSessionStepUp;
import com.nekkora.platform.mfa.MfaRepository;
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

    /**
     * Credenciais pela sessao de identidade: substituicao do fator (DC-17.13), troca de senha (politica da Platform pela
     * porta {@link PasswordRule}) e regeneracao dos codigos; avisos ao titular depois do commit.
     */
    @Bean
    IdentityCredentialService identityCredentialService(IdentityTransactions tx, IdentityAuthRepository auth, SecretCipher cipher, IdentityHmac hmac,
                                                        Limits limits, MailSender mail, PasswordHasher hasher) {
        return new IdentityCredentialService(tx, auth, cipher, hmac, limits, mail, hasher, PasswordPolicy::enforce);
    }

    /**
     * Porta do step-up por organizacao em sessao vinculada (P4): o lado da organizacao ({@link LinkedSessionStepUp}) ligado
     * aqui a interface da identidade, que nao o conhece. A conferencia da sessao de origem (igualdade das duas sessoes)
     * fica nesta ligacao, sobre a sessao ja travada e revalidada.
     */
    @Bean
    LinkedOrgSessionPort linkedOrgSessionPort(SessionRepository sessions, MfaRepository mfa) {
        LinkedSessionStepUp org = new LinkedSessionStepUp(sessions, mfa);
        return new LinkedOrgSessionPort() {
            @Override
            public State lock(JdbcClient j, UUID organizationId, UUID userId,
                              UUID orgSessionId, String orgSessionTokenHash, UUID identitySessionId) {
                return org.lock(j, organizationId, userId, orgSessionTokenHash)
                        .map(s -> s.sessionId().equals(orgSessionId) && identitySessionId.equals(s.identitySessionId()) ? State.VALID : State.OTHER_ORIGIN)
                        .orElse(State.GONE);
            }

            @Override
            public void recordStepUp(JdbcClient j, UUID organizationId, UUID userId,
                                     UUID orgSessionId, UUID correlation) {
                org.recordStepUp(j, organizationId, userId, orgSessionId, correlation);
            }

            @Override
            public boolean stillValid(JdbcClient j, UUID orgSessionId) {
                return org.stillValid(j, orgSessionId);
            }
        };
    }

    /** Step-up por organizacao em sessao vinculada (P4): TOTP do fator da identidade de origem, anti-replay, eventos 6 e 14. */
    @Bean
    LinkedOrgStepUpService linkedOrgStepUpService(IdentityTransactions tx, IdentityAuthRepository auth, LinkedOrgSessionPort orgSessions,
                                                  SecretCipher cipher, IdentityHmac hmac, Limits limits) {
        return new LinkedOrgStepUpService(tx, auth, orgSessions, cipher, hmac, limits);
    }

    @Bean
    IdentityLinkRepository identityLinkRepository() {
        return new IdentityLinkRepository();
    }

    /**
     * Porta da prova da conta antiga (P3, etapa 1): o lado da organizacao ({@link LegacyAccountProof}, com as primitivas do
     * login por organizacao) ligado aqui a interface da identidade, que nao o conhece.
     */
    @Bean
    LegacyAccountPort legacyAccountPort(AccountRepository accounts, MfaRepository mfa, SecretCipher cipher) {
        LegacyAccountProof org = new LegacyAccountProof(accounts, mfa, cipher);
        return new LegacyAccountPort() {
            @Override
            public java.util.Optional<Candidate> find(JdbcClient j, UUID organizationId, String normalizedEmail) {
                return org.find(j, organizationId, normalizedEmail).map(a -> new Candidate(a.userId(), a.passwordHash()));
            }

            @Override
            public Proof prove(JdbcClient j, UUID organizationId, UUID userId, String normalizedEmail, String verifiedHash, String code,
                               String recoveryCode, UUID correlation) {
                LegacyAccountProof.Result r = org.prove(j, organizationId, userId, normalizedEmail, verifiedHash, code, recoveryCode, correlation);
                return new Proof(r.ok(), r.totpStep(), r.wrongCode(), r.usedRecoveryCode());
            }
        };
    }

    /** Vinculacao de conta antiga (P3): duas etapas, R13, aviso depois do commit. */
    @Bean
    AccountLinkService accountLinkService(IdentityTransactions tx, IdentityAuthRepository auth, IdentityLinkRepository links, LegacyAccountPort accounts,
                                          PasswordHasher hasher, IdentityHmac hmac, Limits limits, MailSender mail) {
        return new AccountLinkService(tx, auth, links, accounts, hasher, hmac, limits, mail);
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

    /** Chaves separadas do cadastro, da entrada geral, da criacao de organizacao e da vinculacao: antes da cadeia de seguranca, depois do contexto e do limite de corpo. */
    @Bean
    FilterRegistrationBean<IdentityRoutesGate> identityRoutesGate(@Value("${nekkora.cadastro.enabled:false}") boolean signupEnabled,
                                                                  @Value("${nekkora.entrada-geral.enabled:false}") boolean entryEnabled,
                                                                  @Value("${nekkora.criacao-organizacao.enabled:false}") boolean creationEnabled,
                                                                  @Value("${nekkora.vinculacao.enabled:false}") boolean linkEnabled) {
        FilterRegistrationBean<IdentityRoutesGate> r = new FilterRegistrationBean<>(new IdentityRoutesGate(signupEnabled, entryEnabled, creationEnabled,
                linkEnabled));
        r.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        r.addUrlPatterns("/api/*");
        return r;
    }
}
