package com.nekkora.core.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.nekkora.core.db.DbErrors;

/**
 * Traducao de TODA falha para o corpo generico do contrato 8.7 (§5.2). Nenhuma mensagem, causa, SQL ou stack
 * vai ao cliente. O log registra so codigo, SQLSTATE, classe da excecao e correlacao (C10/8.8) - nunca o texto
 * da mensagem do servidor, que pode conter valores de bind.
 */
@RestControllerAdvice
public class GenericErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GenericErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> api(ApiException e) {
        if (e.apiError() == ApiError.INTERNAL_ERROR || e.apiError() == ApiError.SERVICE_UNAVAILABLE) {
            LOG.warn("evento=api.erro codigo={} motivo={} correlacao={}", e.apiError().code(), e.internalReason(), RequestContext.correlation());
        }
        return response(e.apiError(), e.retryAfterSeconds());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMediaTypeNotSupportedException.class, IllegalArgumentException.class})
    public ResponseEntity<String> invalid(Exception e) {
        return response(ApiError.INVALID_REQUEST, null);
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<String> notFound(Exception e) {
        return response(ApiError.NOT_FOUND, null);
    }

    /** Redis indisponivel onde nao ha tratamento especifico: 503 (distinto de 429), contrato 8.6 §2.1 item 6. */
    @ExceptionHandler(com.nekkora.core.ratelimit.RedisLimiter.LimiterUnavailable.class)
    public ResponseEntity<String> unavailable(Exception e) {
        LOG.warn("evento=api.erro codigo=service_unavailable motivo=redis_indisponivel correlacao={}", RequestContext.correlation());
        return response(ApiError.SERVICE_UNAVAILABLE, 1L);
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<String> internal(Throwable e) {
        LOG.error("evento=api.erro_interno classe={} sqlstate={} correlacao={}", e.getClass().getName(), DbErrors.sqlState(e),
                RequestContext.correlation());
        return response(ApiError.INTERNAL_ERROR, null);
    }

    public static ResponseEntity<String> response(ApiError apiError, Long retryAfter) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(apiError.status()).contentType(MediaType.APPLICATION_JSON);
        if (retryAfter != null) {
            b.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfter)));
        }
        return b.body(apiError.body());
    }
}
