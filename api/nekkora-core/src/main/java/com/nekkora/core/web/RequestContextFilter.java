package com.nekkora.core.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Primeiro filtro de toda requisicao: correlacao gerada no servidor, IP do cliente (so do proxy confiavel) e
 * cabecalhos de seguranca do contrato 8.7 (§5.1). Nao registra cabecalhos, cookies, parametros nem corpo.
 */
public final class RequestContextFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE_IP = "nekkora.ip";

    private final ClientIpResolver ips;

    public RequestContextFilter(ClientIpResolver ips) {
        this.ips = ips;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String correlation = UUID.randomUUID().toString();
        MDC.put(RequestContext.MDC_CORRELATION, correlation);
        try {
            req.setAttribute(ATTRIBUTE_IP, ips.resolver(req));
            res.setHeader("X-Request-Id", correlation);
            res.setHeader("Cache-Control", "no-store");
            res.setHeader("X-Content-Type-Options", "nosniff");
            res.setHeader("Referrer-Policy", "no-referrer");
            chain.doFilter(req, res);
        } finally {
            MDC.remove(RequestContext.MDC_CORRELATION);
        }
    }

    public static String ip(HttpServletRequest req) {
        Object ip = req.getAttribute(ATTRIBUTE_IP);
        return ip == null ? req.getRemoteAddr() : ip.toString();
    }
}
