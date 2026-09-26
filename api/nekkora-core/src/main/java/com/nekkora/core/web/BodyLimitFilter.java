package com.nekkora.core.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limite de corpo (contrato, §4.4): no maximo {@code maxBytes} com Content-Length declarado; corpo por
 * Transfer-Encoding (sem tamanho conhecido) e recusado. 400 generico, sem ler o corpo.
 */
public final class BodyLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public BodyLimitFilter(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (req.getHeader("Transfer-Encoding") != null || req.getContentLengthLong() > maxBytes) {
            ErrorResponses.writeError(res, ApiError.INVALID_REQUEST, null);
            return;
        }
        chain.doFilter(req, res);
    }
}
