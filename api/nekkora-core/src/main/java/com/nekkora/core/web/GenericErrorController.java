package com.nekkora.core.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Substitui a pagina de erro padrao ({@code /error}): mesmo corpo generico, sem mensagem, trace nem caminho.
 * So e alcancado quando algo escapa dos handlers (ex.: erro do container antes do DispatcherServlet).
 */
@RestController
public class GenericErrorController implements org.springframework.boot.webmvc.error.ErrorController {

    @RequestMapping("/error")
    public ResponseEntity<String> apiError(HttpServletRequest req) {
        Object status = req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = status instanceof Integer i ? i : 500;
        ApiError apiError = switch (code) {
            case 400 -> ApiError.INVALID_REQUEST;
            case 401 -> ApiError.UNAUTHENTICATED;
            case 403 -> ApiError.FORBIDDEN;
            case 404, 405 -> ApiError.NOT_FOUND;
            case 429 -> ApiError.RATE_LIMITED;
            case 503 -> ApiError.SERVICE_UNAVAILABLE;
            default -> ApiError.INTERNAL_ERROR;
        };
        return GenericErrorHandler.response(apiError, null);
    }
}
