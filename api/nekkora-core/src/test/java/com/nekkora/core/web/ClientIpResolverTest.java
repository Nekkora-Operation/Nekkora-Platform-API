package com.nekkora.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** IP do cliente so do proxy confiavel (contrato 8.6, §2.1, item 5). */
class ClientIpResolverTest {

    private final ClientIpResolver r = new ClientIpResolver(List.of("10.0.0.0/8", "::1/128"));

    private static MockHttpServletRequest req(String remoteAddr, String xff) {
        MockHttpServletRequest q = new MockHttpServletRequest();
        q.setRemoteAddr(remoteAddr);
        if (xff != null) {
            q.addHeader("X-Forwarded-For", xff);
        }
        return q;
    }

    @Test
    void onlyTrustedProxyDefinesIp() {
        assertThat(r.resolver(req("10.1.2.3", "203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(r.resolver(req("198.51.100.7", "203.0.113.9"))).as("conexao direta de fora da lista: cabecalho ignorado").isEqualTo("198.51.100.7");
        assertThat(r.resolver(req("10.1.2.3", "1.1.1.1, 203.0.113.9"))).as("lista (cabecalho acrescentado, nao sobrescrito): recusado").isEqualTo("10.1.2.3");
        assertThat(r.resolver(req("10.1.2.3", "evil.example"))).as("nao e literal de IP").isEqualTo("10.1.2.3");
        assertThat(r.resolver(req("::1", "2001:db8::1"))).isEqualTo("2001:db8::1");
    }

    @Test
    void truncationForLog() {
        assertThat(ClientIpResolver.truncated("203.0.113.9")).isEqualTo("203.0.113.0/24");
        assertThat(ClientIpResolver.truncated("2001:db8:1:2:3::9")).startsWith("2001:db8:1:").endsWith("/48");
    }
}
