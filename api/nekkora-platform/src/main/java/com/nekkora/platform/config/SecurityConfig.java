package com.nekkora.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ErrorResponses;
import com.nekkora.platform.identity.IdentitySessionResolver;
import com.nekkora.platform.limits.Limits;
import com.nekkora.platform.mfa.MfaRepository;
import com.nekkora.platform.session.SessionRepository;
import com.nekkora.platform.web.CsrfFilter;
import com.nekkora.platform.web.IdentityFilter;
import com.nekkora.platform.web.PreAuthCsrf;
import com.nekkora.platform.web.SessionFilter;

/**
 * Cadeia de seguranca Spring (§9.2 do relatorio da API): STATELESS, SecurityContext so no atributo da requisicao
 * (nunca em HttpSession), sem request cache, sem logout/form/basic do framework. O CSRF do framework fica
 * desligado porque o contrato exige o sincronizador proprio por contexto (CsrfFilter). A sessao e autenticada a
 * cada requisicao pelo SessaoFilter; a autorizacao fina (matriz da 003) e feita nos servicos, dentro da transacao.
 */
@Configuration
public class SecurityConfig {

    private static final String[] PUBLIC_POST_ROUTES = {
            "/api/v1/o/*/auth/login", "/api/v1/o/*/auth/mfa/enroll/start", "/api/v1/o/*/auth/mfa/enroll/confirm",
            "/api/v1/o/*/auth/mfa/verify", "/api/v1/o/*/auth/logout", "/api/v1/o/*/auth/password-reset/request",
            "/api/v1/o/*/auth/password-reset/confirm", "/api/v1/o/*/auth/email-verification/confirm",
            "/api/v1/o/*/auth/mfa-emergencia/aviso/confirmar", "/api/v1/o/*/auth/mfa-emergencia/aviso/contestar",
            // cadastro web (Missao 1): sem sessao; CSRF de pre-autenticacao. Desligado: IdentityRoutesGate responde 404 antes daqui
            "/api/v1/signup", "/api/v1/signup/confirm", "/api/v1/signup/confirmation/resend",
            // entrada geral (Missao 1): sem sessao de organizacao; desafio e sessao de identidade conferidos pelo servico e
            // pelo CsrfFilter. Desligado: IdentityRoutesGate responde 404 antes daqui
            "/api/v1/auth/login", "/api/v1/auth/mfa/enroll/start", "/api/v1/auth/mfa/enroll/confirm", "/api/v1/auth/mfa/verify",
            "/api/v1/auth/logout",
            // recuperacao de senha da identidade (missao 002): sem sessao; CSRF de pre-autenticacao. Desligada: 404 antes daqui
            "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm"};

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, OrgTransactions tx, SessionRepository sessions, Limits limits, PreAuthCsrf pre,
                               MfaRepository mfa, PlatformProperties p, IdentitySessionResolver identities) throws Exception {
        http.securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .logout(l -> l.disable())
                .anonymous(Customizer.withDefaults())
                .headers(h -> h.frameOptions(f -> f.deny()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/v1/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST_ROUTES).permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> ErrorResponses.writeError(res, ApiError.UNAUTHENTICATED, null))
                        .accessDeniedHandler((req, res, ex) -> ErrorResponses.writeError(res, ApiError.FORBIDDEN, null)))
                .addFilterBefore(new SessionFilter(tx, sessions, limits), AuthorizationFilter.class)
                // entrada geral: sessao de identidade so nas rotas sem organizacao (desenho §6.7)
                .addFilterBefore(new IdentityFilter(identities), AuthorizationFilter.class)
                .addFilterAfter(new CsrfFilter(p.publicOrigin(), pre, tx, mfa, identities), AuthorizationFilter.class);
        return http.build();
    }

    /** Sem usuarios em memoria (evita o usuario/senha gerados pelo Spring Boot). */
    @Bean
    UserDetailsService noLocalUsers() {
        return name -> {
            throw new UsernameNotFoundException("nenhum");
        };
    }
}
