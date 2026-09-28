package com.nekkora.platform.web;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.nekkora.platform.emergency.EmergencyNotices;

/**
 * Rotas da recuperacao emergencial de MFA (016). Confirmacao e contestacao: POST explicito, CSRF de pre-autenticacao
 * e Origin (CsrfFilter), sem sessao; 204 no sucesso e a mesma resposta 400 {@code invalid_request} em qualquer
 * recusa. Nenhuma rota cria sessao, aprova ou executa reset.
 */
@RestController
public class EmergencyNoticeController {

    public record Token(String token) {
    }

    private final EmergencyNotices notices;

    public EmergencyNoticeController(EmergencyNotices notices) {
        this.notices = notices;
    }

    @PostMapping("/api/v1/o/{org}/auth/mfa-emergencia/aviso/confirmar")
    public ResponseEntity<Void> confirm(@PathVariable UUID org, @RequestBody(required = false) Token body) {
        notices.confirm(org, body == null ? null : body.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/o/{org}/auth/mfa-emergencia/aviso/contestar")
    public ResponseEntity<Void> contest(@PathVariable UUID org, @RequestBody(required = false) Token body) {
        notices.contest(org, body == null ? null : body.token());
        return ResponseEntity.noContent().build();
    }

    /** Codigo de prova de controle da conta para o vinculo impeditivo do operador (sessao + CSRF de sessao). */
    @PostMapping("/api/v1/o/{org}/me/operational-link-code")
    public Map<String, String> linkCode(@PathVariable UUID org, HttpServletRequest req) {
        EmergencyNotices.LinkCode c = notices.issueAccountLinkCode(org, SessionFilter.principal(req));
        return Map.of("code", c.code(), "expiresAt", c.expiresAt().toString());
    }
}
