package com.nekkora.platform.identity;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.core.db.IdentityTransactions.EmailCadastro;
import com.nekkora.core.db.IdentityTransactions.Mode;
import com.nekkora.core.db.IdentityTransactions.TokenEmail;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.ClientIpResolver;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.limits.Limits;
import com.nekkora.platform.mail.MailIntents;
import com.nekkora.platform.mail.MailSender;

/**
 * Recuperacao de senha da IDENTIDADE por e-mail (missao PLATFORM-API-IDENTIDADE-002, PR 4; migration 020). Pre-autenticacao,
 * fora de {@code /api/v1/o/{org}}; chave propria {@code nekkora.recuperacao-senha.enabled}, so com a entrada geral ligada.
 *
 * <p><b>Pedido</b> ({@code POST /api/v1/auth/password-reset/request}): resposta UNIFORME (202 sem corpo no controller) e
 * piso de tempo de {@code recovery-response-floor} em qualquer desfecho; o e-mail sai da resposta ({@link SignupMailer}),
 * depois do commit. So a identidade ACTIVE com e-mail verificado recebe token: sob o lock dela, os tokens de recuperacao
 * vivos sao revogados, o novo e gravado (so o hash, prazo {@code nekkora.session.password-recovery}) e o evento 16 e
 * registrado. E-mail desconhecido, identidade PENDING, SUSPENDED ou CLOSED, ou R14 excedido: nada, a mesma resposta. E-mail
 * malformado: 400 (sem enumeracao: depende so do texto enviado). R12 responde 429; Redis fora, 503.
 *
 * <p><b>Confirmacao</b> ({@code POST /api/v1/auth/password-reset/confirm}): token e senha nova. Politica e Argon2 ANTES da
 * transacao; na transacao, {@code identity_password_reset} consome o token e troca a senha no mesmo comando, revoga TODAS
 * as sessoes de identidade, invalida os desafios e os demais tokens de recuperacao e grava o evento 17. Nenhuma sessao e
 * criada; o MFA nao muda (a proxima entrada exige o fator ou um codigo de recuperacao). Qualquer recusa: 400
 * {@code invalid_request}, sem efeito. Aviso {@code NOTICE_PASSWORD_CHANGED} depois do commit. Nunca registra e-mail,
 * token, senha nem hash.
 */
public final class IdentityPasswordRecoveryService {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityPasswordRecoveryService.class);

    private final IdentityTransactions tx;
    private final IdentityRepository identities;
    private final IdentityAuthRepository auth;
    private final PasswordHasher hasher;
    private final PasswordRule passwordRule;
    private final IdentityHmac hmac;
    private final Limits limits;
    private final MailIntents mail;
    private final Duration tokenValidity;
    private final Duration responseFloor;

    public IdentityPasswordRecoveryService(IdentityTransactions tx, IdentityRepository identities, IdentityAuthRepository auth, PasswordHasher hasher,
                                           PasswordRule passwordRule, IdentityHmac hmac, Limits limits, MailIntents mail,
                                           Duration tokenValidity, Duration responseFloor) {
        this.tx = tx;
        this.identities = identities;
        this.auth = auth;
        this.hasher = hasher;
        this.passwordRule = passwordRule;
        this.hmac = hmac;
        this.limits = limits;
        this.mail = mail;
        this.tokenValidity = tokenValidity;
        this.responseFloor = responseFloor;
    }

    /** POST /api/v1/auth/password-reset/request. E-mail malformado: 400. Qualquer outro desfecho: retorno normal (202). */
    public void request(String email, String ip) {
        long start = System.nanoTime();
        try {
            limits.identityRoutes(ip);
            if (!EmailFormat.valid(email)) {
                throw ApiException.invalid("email");
            }
            String normalized = IdentityHmac.normalize(email);
            String identity = hmac.global(normalized);
            if (!limits.identityRecoveryAllowed(identity, ip)) {
                LOG.info("evento=identidade.recuperacao_limitada regra=R14 identidade={} ip={}", identity, ClientIpResolver.truncated(ip));
                return;
            }
            String token = Tokens.newToken();
            UUID correlation = correlation();
            Optional<String> target = tx.inIdentity(new EmailCadastro(normalized), Mode.READ_WRITE, (id, j) -> {
                // so identidade ACTIVE e verificada, conferida SOB o lock da identidade (suspensao concorrente: nada emitido)
                if (!auth.lockIdentity(j, id.identityId()) || !auth.identityActive(j, id.identityId())) {
                    return null;
                }
                String to = identities.state(j, id.identityId()).map(IdentityRepository.State::email).orElse(null);
                if (to == null) {
                    return null;
                }
                identities.revokeLiveRecoveryTokens(j, id.identityId());
                identities.insertRecoveryToken(j, id.identityId(), to, Tokens.sha256Hex(token), tokenValidity);
                identities.event(j, id.identityId(), IdentityRepository.EV_PASSWORD_RECOVERY_REQUESTED, correlation);
                // e-mail com o token NA transacao que o emite; chave = hash do token
                mail.record(j, new MailIntents.Intent(MailSender.Kind.PASSWORD_RECOVERY, null, to, token, "token:" + Tokens.sha256Hex(token)));
                return to;
            });
            if (target.isPresent()) {
                LOG.info("evento=identidade.recuperacao_emitida identidade={} ip={} correlacao={}", identity, ClientIpResolver.truncated(ip), correlation);
            }
        } finally {
            floor(start);
        }
    }

    /**
     * POST /api/v1/auth/password-reset/confirm. Token malformado, senha fora da politica: 400 antes do banco. Token
     * desconhecido, de outro proposito, consumido, revogado ou vencido (inclusive durante a espera por lock), identidade
     * fora de ACTIVE: 400, sem efeito.
     */
    public void confirm(String token, char[] newPassword, String ip) {
        limits.identityRoutes(ip);
        if (!Tokens.isValidFormat(token)) {
            throw ApiException.invalid("token");
        }
        passwordRule.enforce(newPassword);
        String newHash = hasher.generate(newPassword);
        // a chave resolve a identidade do token (qualquer proposito); a funcao confere o proposito 2 e tudo o mais sob os locks
        Optional<String> email;
        try {
            email = tx.inIdentity(new TokenEmail(token), Mode.READ_WRITE, (id, j) -> {
                String to = auth.email(j, id.identityId()).orElse(null);
                if (to == null) {
                    return null;
                }
                // aviso NA transacao (chave = o token, de uso unico), ANTES da funcao: as decisoes dela (prazo do token,
                // estado da identidade), medidas depois dos locks, ficam sendo as ultimas; recusa desfaz tambem a intencao
                mail.record(j, new MailIntents.Intent(MailSender.Kind.NOTICE_PASSWORD_CHANGED, null, to, null, "redefinicao:" + Tokens.sha256Hex(token)));
                if (!identities.passwordReset(j, token, newHash)) {
                    throw new ResetRefused();
                }
                return to;
            });
        } catch (ResetRefused e) {
            email = Optional.empty();
        }
        if (email.isEmpty()) {
            LOG.info("evento=identidade.recuperacao_negada ip={}", ClientIpResolver.truncated(ip));
            throw ApiException.invalid("token");
        }
        LOG.info("evento=identidade.senha_redefinida identidade={} ip={}", hmac.global(email.get()), ClientIpResolver.truncated(ip));
    }

    /** Recusa da funcao depois de a intencao de e-mail ser gravada: desfaz a transacao inteira. */
    private static final class ResetRefused extends RuntimeException {
        ResetRefused() {
            super(null, null, false, false);
        }
    }

    private void floor(long startNanos) {
        long remaining = responseFloor.toNanos() - (System.nanoTime() - startNanos);
        if (remaining > 0) {
            try {
                Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static UUID correlation() {
        try {
            return UUID.fromString(RequestContext.correlation());
        } catch (IllegalArgumentException | NullPointerException e) {
            return UUID.randomUUID();
        }
    }
}
