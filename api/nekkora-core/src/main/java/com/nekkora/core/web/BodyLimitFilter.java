package com.nekkora.core.web;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite de corpo (contrato, §4.4): no maximo {@code maxBytes} com Content-Length declarado; corpo por
 * Transfer-Encoding (sem tamanho conhecido) e recusado. 400 generico, sem ler o corpo. Excecao opcional e EXATA: um metodo e um
 * caminho (expressao inteira) com teto proprio, tambem com Content-Length declarado (ex.: envio do artefato de uma versao).
 */
public final class BodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final String largeMethod;
    private final Pattern largePath;
    private final long largeMaxBytes;

    public BodyLimitFilter(long maxBytes) {
        this(maxBytes, null, null, 0);
    }

    public BodyLimitFilter(long maxBytes, String largeMethod, Pattern largePath, long largeMaxBytes) {
        this.maxBytes = maxBytes;
        this.largeMethod = largeMethod;
        this.largePath = largePath;
        this.largeMaxBytes = largeMaxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        boolean large = largePath != null && largeMethod.equals(req.getMethod()) && largePath.matcher(req.getRequestURI()).matches();
        if (req.getHeader("Transfer-Encoding") != null || req.getContentLengthLong() > (large ? largeMaxBytes : maxBytes)) {
            ErrorResponses.writeError(res, ApiError.INVALID_REQUEST, null);
            return;
        }
        chain.doFilter(req, res);
    }
}
