package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.nekkora.core.testsupport.BrowserClient;
import com.nekkora.core.web.RequestContextFilter;

/**
 * Ciclo de vida do SecurityContext (revisao do SessionFilter): o SessionFilter NAO limpa o contexto; quem limpa e a
 * propria cadeia do Spring Security - o SecurityContextHolderFilter (registrado por http.securityContext(...),
 * antes do SessionFilter) e o FilterChainProxy, ambos com clearContext() em finally. Este teste chama a cadeia REAL
 * (bean springSecurityFilterChain) diretamente, TRES vezes na MESMA thread, e confere o ThreadLocal depois de cada
 * uma: requisicao autenticada com sucesso, requisicao autenticada que falha dentro da cadeia e requisicao anonima
 * seguinte (que precisa receber 401 sem alcancar o destino).
 */
class SecurityContextLifecycleIT extends PlatformTestBase {

    static Org org;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter securityChain;

    @BeforeAll
    static void fixture() {
        org = newOrganization();
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/v1/o/" + org.id() + "/me");
        r.setRemoteAddr("127.0.0.1");
        r.setAttribute(RequestContextFilter.ATTRIBUTE_IP, "10.200.0.1");
        if (token != null) {
            r.setCookies(new Cookie("__Host-nekkora_session", token));
        }
        return r;
    }

    @Test
    void contextIsClearedAfterSuccessAndErrorAndNotSeenByNextAnonymousRequestOnSameThread() throws Exception {
        TestUser u = internalUser(org, true, 2);
        BrowserClient b = browser();
        firstLogin(b, org.id(), u);
        String token = b.cookies().get("__Host-nekkora_session");
        long thread = Thread.currentThread().threadId();
        SecurityContextHolder.clearContext();

        // 1. sucesso: o destino ve a autenticacao; depois da cadeia, o ThreadLocal esta vazio
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain destination = (rq, rs) -> seen.set(SecurityContextHolder.getContext().getAuthentication());
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        securityChain.doFilter(request(token), r1, destination);
        assertThat(seen.get()).as("destino alcancado com a sessao autenticada").isNotNull();
        assertThat(seen.get().isAuthenticated()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("apos sucesso: contexto limpo").isNull();

        // 2. erro dentro da cadeia (excecao no destino): a excecao sobe e, mesmo assim, o contexto e limpo
        AtomicBoolean reachedOnError = new AtomicBoolean();
        FilterChain failing = (rq, rs) -> {
            reachedOnError.set(SecurityContextHolder.getContext().getAuthentication() != null);
            throw new IllegalStateException("falha simulada no destino");
        };
        assertThatThrownBy(() -> securityChain.doFilter(request(token), new MockHttpServletResponse(), failing))
                .hasMessageContaining("falha simulada no destino");
        assertThat(reachedOnError.get()).as("a requisicao com erro estava autenticada").isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("apos erro: contexto limpo").isNull();

        // 3. requisicao anonima seguinte na mesma thread: 401 e o destino nao e alcancado
        AtomicBoolean reachedAnonymous = new AtomicBoolean();
        MockHttpServletResponse r3 = new MockHttpServletResponse();
        securityChain.doFilter(request(null), r3, (rq, rs) -> reachedAnonymous.set(true));
        assertThat(reachedAnonymous.get()).as("anonimo nao alcanca o destino").isFalse();
        assertThat(r3.getStatus()).isEqualTo(401);
        assertThat(r3.getContentAsString()).isEqualTo("{\"error\":\"unauthenticated\"}");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        // 4. vazamento simulado: uma identidade autenticada deixada na thread POR FORA da cadeia nao e usada - na
        //    entrada, o SecurityContextHolderFilter substitui o contexto pelo do repositorio (atributo da requisicao,
        //    vazio para uma requisicao nova); a anonima continua recebendo 401 e o contexto sujo e descartado
        SecurityContextHolder.getContext().setAuthentication(seen.get());
        AtomicBoolean reachedWithLeak = new AtomicBoolean();
        MockHttpServletResponse r4 = new MockHttpServletResponse();
        securityChain.doFilter(request(null), r4, (rq, rs) -> reachedWithLeak.set(true));
        assertThat(reachedWithLeak.get()).as("identidade vazada na thread nao autentica a requisicao seguinte").isFalse();
        assertThat(r4.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("contexto sujo descartado ao fim").isNull();
        assertThat(Thread.currentThread().threadId()).as("todas as requisicoes na mesma thread").isEqualTo(thread);
    }
}
