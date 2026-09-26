package com.nekkora.portal;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ErrorResponses;

/** O portal so aceita GET (contrato 8.7, §5.4): qualquer outro metodo -> 404 generico, sem tocar o banco. */
public final class ReadOnlyFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (!"GET".equals(req.getMethod())) {
            ErrorResponses.writeError(res, ApiError.NOT_FOUND, null);
            return;
        }
        chain.doFilter(req, res);
    }
}
