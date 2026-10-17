package com.nekkora.platform.identity;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.core.db.IdentityTransactions.EmailCadastro;
import com.nekkora.core.db.IdentityTransactions.Mode;
import com.nekkora.core.db.IdentityTransactions.NovaIdentidade;
import com.nekkora.core.db.IdentityTransactions.TokenEmail;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.PasswordHasher;
import com.nekkora.platform.limits.Limits;
import com.nekkora.platform.mail.MailSender;

/**
 * Cadastro web da identidade (Missao 1, desenho §6.1 e §6.2): pedido, confirmacao e reenvio da confirmacao. So este
 * fluxo; entrada geral, MFA, organizacoes e troca de sessao ficam em servicos proprios (levantamento, §4.1).
 *
 * <p><b>A titular escolhe a credencial na confirmacao</b> (revisao de 2026-10-13). O pedido publico recebe so o e-mail e
 * nao grava credencial: a senha (e o nome) so entram na CONFIRMACAO, numa unica transacao autorizada pelo token -
 * identidade e token travados, token consumido, credencial inserida, identidade ativada. Quem fez um pedido para o e-mail
 * de outra pessoa nao escolhe a senha da identidade que a titular conclui.
 *
 * <p><b>Sem enumeracao.</b> Pedido e reenvio respondem sempre da mesma forma (202 sem corpo no controller):
 * <ul>
 * <li>e-mail livre: identidade PENDING sem credencial, token e e-mail de confirmacao;</li>
 * <li>identidade PENDING: reemissao (revoga o token vivo e emite outro), sujeita a R9b;</li>
 * <li>identidade ACTIVE: so o aviso de conta existente, sem token, sujeito a R9b; nada e criado;</li>
 * <li>SUSPENDED ou CLOSED: nada.</li>
 * </ul>
 * Nenhum dos dois executa Argon2 (nao ha senha neles). O piso de tempo de {@code requestRecovery} vale para os dois, e o
 * e-mail sai do caminho da resposta ({@link SignupMailer}), depois do commit. Limites excedidos (R9, R9b) nao mudam a
 * resposta; R12 responde 429 e Redis fora responde 503 (falha fechada). Nunca registra e-mail, token ou hash.
 *
 * <p><b>Confirmacao.</b> Validacao e Argon2 ANTES da transacao (sem segurar lock durante o hash). Na transacao, na ordem
 * da 017: lock da identidade, lock do token, e so DEPOIS dos locks a validade e o consumo; entao credencial, ativacao e
 * evento. Qualquer recusa: 400 {@code invalid_request}, sem efeito. Nao cria sessao nem organizacao.
 */
public final class SignupService {

    private static final Logger LOG = LoggerFactory.getLogger(SignupService.class);

    static final int EMAIL_MAX = 254;
    static final int DISPLAY_NAME_MAX = 120;

    private final IdentityTransactions tx;
    private final IdentityRepository identities;
    private final PasswordHasher hasher;
    private final PasswordRule passwordRule;
    private final IdentityHmac hmac;
    private final Limits limits;
    private final SignupMailer mailer;
    private final Duration tokenValidity;
    private final Duration responseFloor;

    public SignupService(IdentityTransactions tx, IdentityRepository identities, PasswordHasher hasher, PasswordRule passwordRule, IdentityHmac hmac,
                         Limits limits, SignupMailer mailer, Duration tokenValidity, Duration responseFloor) {
        this.tx = tx;
        this.identities = identities;
        this.hasher = hasher;
        this.passwordRule = passwordRule;
        this.hmac = hmac;
        this.limits = limits;
        this.mailer = mailer;
        this.tokenValidity = tokenValidity;
        this.responseFloor = responseFloor;
    }

    /** POST /api/v1/signup. E-mail malformado: 400. Qualquer outro desfecho: retorno normal (202 no controller). */
    public void signup(String email, String ip) {
        long start = System.nanoTime();
        try {
            limits.identityRoutes(ip);
            if (!validEmail(email)) {
                throw ApiException.invalid("email");
            }
            if (!limits.signupAllowed(ip)) {
                return;
            }
            String normalized = IdentityHmac.normalize(email);
            Optional<IdentityRepository.State> current = tx.inIdentity(new EmailCadastro(normalized), Mode.READ_ONLY,
                    (id, j) -> identities.state(j, id.identityId()).orElse(null));
            if (current.isEmpty()) {
                create(email);
            } else if (current.get().status() == IdentityRepository.PENDING) {
                reissue(normalized, ip);
            } else if (current.get().status() == IdentityRepository.ACTIVE) {
                if (limits.signupMailAllowed(hmac.global(normalized), ip)) {
                    mailer.enqueue(MailSender.Kind.IDENTITY_SIGNUP_ALREADY_REGISTERED, current.get().email(), null);
                }
            }
        } finally {
            floor(start);
        }
    }

    /** POST /api/v1/signup/confirmation/resend. Sempre retorno normal (202), inclusive com e-mail malformado. */
    public void resend(String email, String ip) {
        long start = System.nanoTime();
        try {
            limits.identityRoutes(ip);
            if (!validEmail(email)) {
                return;
            }
            reissue(IdentityHmac.normalize(email), ip);
        } finally {
            floor(start);
        }
    }

    /**
     * POST /api/v1/signup/confirm. Token, senha e nome da titular. Token invalido, vencido (inclusive durante a espera por
     * lock), revogado, consumido, identidade fora de PENDING ou ja com credencial: 400, sem efeito.
     */
    public void confirm(String token, char[] password, String displayName, String ip) {
        limits.identityRoutes(ip);
        if (!Tokens.isValidFormat(token)) {
            throw ApiException.invalid("token");
        }
        if (!validDisplayName(displayName)) {
            throw ApiException.invalid("nome");
        }
        passwordRule.enforce(password);
        String passwordHash = hasher.generate(password);
        String tokenHash = Tokens.sha256Hex(token);
        boolean confirmed;
        try {
            confirmed = tx.inIdentity(new TokenEmail(token), Mode.READ_WRITE, (id, j) -> {
                if (identities.lock(j, id.identityId()).isEmpty() || !identities.lockToken(j, id.identityId(), tokenHash)) {
                    return false;
                }
                Optional<String> target = identities.consumeToken(j, id.identityId(), tokenHash);
                if (target.isEmpty()) {
                    return false;
                }
                identities.insertCredential(j, id.identityId(), passwordHash);
                if (identities.activate(j, id.identityId(), target.get(), displayName) != 1) {
                    // token vivo de identidade que nao esta mais PENDING: desfaz consumo e credencial com a transacao
                    throw ApiException.invalid("token");
                }
                identities.event(j, id.identityId(), IdentityRepository.SIGNUP_CONFIRMED, correlation());
                return true;
            }).orElse(false);
        } catch (DuplicateKeyException e) {
            // identidade PENDING que ja tem credencial (so por dado anterior a este contrato): recusa, sem efeito
            LOG.warn("evento=cadastro.confirmacao_com_credencial correlacao={}", RequestContext.correlation());
            confirmed = false;
        }
        if (!confirmed) {
            throw ApiException.invalid("token");
        }
    }

    private void create(String email) {
        String token = Tokens.newToken();
        try {
            tx.inIdentity(new NovaIdentidade(), Mode.READ_WRITE, (id, j) -> {
                identities.insertPending(j, id.identityId(), email);
                identities.insertToken(j, id.identityId(), email, Tokens.sha256Hex(token), tokenValidity);
                identities.event(j, id.identityId(), IdentityRepository.SIGNUP_REQUESTED, correlation());
                return Boolean.TRUE;
            });
        } catch (DuplicateKeyException e) {
            // cadastro concorrente do mesmo e-mail: o outro pedido criou a identidade; este nao cria nada (resposta igual)
            LOG.info("evento=cadastro.concorrente correlacao={}", RequestContext.correlation());
            return;
        }
        mailer.enqueue(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, email, token);
    }

    /** Reemissao para identidade PENDING: R9b, lock da identidade, revogacao do token vivo, token novo e e-mail. */
    private void reissue(String normalized, String ip) {
        if (!limits.signupMailAllowed(hmac.global(normalized), ip)) {
            return;
        }
        String token = Tokens.newToken();
        Optional<String> target = tx.inIdentity(new EmailCadastro(normalized), Mode.READ_WRITE, (id, j) -> {
            Optional<IdentityRepository.State> s = identities.lock(j, id.identityId());
            if (s.isEmpty() || s.get().status() != IdentityRepository.PENDING) {
                return null;
            }
            identities.revokeLiveTokens(j, id.identityId());
            identities.insertToken(j, id.identityId(), s.get().email(), Tokens.sha256Hex(token), tokenValidity);
            identities.event(j, id.identityId(), IdentityRepository.SIGNUP_REQUESTED, correlation());
            return s.get().email();
        });
        target.ifPresent(e -> mailer.enqueue(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, e, token));
    }

    static boolean validEmail(String email) {
        if (email == null || email.isEmpty() || email.length() > EMAIL_MAX || !email.equals(email.strip())) {
            return false;
        }
        for (int i = 0; i < email.length(); i++) {
            char c = email.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        int at = email.indexOf('@');
        return at > 0 && at == email.lastIndexOf('@') && at < email.length() - 1;
    }

    static boolean validDisplayName(String name) {
        if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > DISPLAY_NAME_MAX) {
            return false;
        }
        return name.codePoints().noneMatch(Character::isISOControl);
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
