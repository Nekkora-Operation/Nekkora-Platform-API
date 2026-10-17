package com.nekkora.platform.identity;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.core.db.IdentityTransactions.Mode;
import com.nekkora.core.db.IdentityTransactions.TokenSessao;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.ClientIpResolver;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.auth.crypto.Totp;
import com.nekkora.platform.identity.IdentitySessionResolver.IdentityPrincipal;
import com.nekkora.platform.limits.Limits;

/**
 * Step-up da sessao de identidade (P1 do contrato de 2026-10-13, aprovado em 2026-10-14): {@code POST /api/v1/auth/step-up}
 * com o TOTP do fator da identidade. Codigo de recuperacao nao e alternativa. Sessao restrita a recuperacao: 403
 * (DC-17.13). Habilita, por 5 min, o que a 017 exige com {@code identity_session_proof} no modo {@code step_up}.
 *
 * <p>Uma so transacao READ COMMITTED com o contexto da sessao, na ordem global da 017 (identidade -> sessao de
 * identidade): identidade {@code FOR UPDATE} -> sessao {@code FOR UPDATE} e reconferida sob o lock (ativa, no prazo, nao
 * restrita; identidade ACTIVE e verificada; o prazo e medido num comando posterior a aquisicao do lock) -> fator confirmado e
 * habilitado lido sob o lock -> TOTP -> anti-replay (o passo so avanca, 1 linha; pode esperar o lock do fator) ->
 * {@code mfa_step_up_at} -> evento 13 ({@code STEP_UP}) -> no fim, depois de todas as esperas: sessao reconferida pelo
 * relogio do banco (revogada ou vencida: rollback integral, 401) e janela do passo reconferida (fora dela: rollback
 * integral, 403). As escritas que referenciam {@code identities} e
 * {@code identity_sessions} (evento) so acontecem com essas linhas ja travadas (desenho §13.5, requisitos 1 e 7).
 *
 * <p>Respostas: 204; codigo errado, replay ou sem fator confirmado: 403 generico, com o codigo errado e o replay contados
 * em R8m (identidade global); passo vencido durante as esperas: 403, sem contar; sessao que caiu, inclusive vencida durante as
 * esperas: 401, sem efeito e sem contar. Log sem e-mail,
 * codigo, token nem hash.
 */
public final class IdentityStepUpService {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityStepUpService.class);

    private final IdentityTransactions tx;
    private final IdentityAuthRepository auth;
    private final SecretCipher cipher;
    private final IdentityHmac hmac;
    private final Limits limits;

    public IdentityStepUpService(IdentityTransactions tx, IdentityAuthRepository auth, SecretCipher cipher, IdentityHmac hmac, Limits limits) {
        this.tx = tx;
        this.auth = auth;
        this.cipher = cipher;
        this.hmac = hmac;
        this.limits = limits;
    }

    /** Desfecho: concluido, ou recusa com a regra (e se conta como codigo errado). */
    private record Outcome(String rule, boolean wrongCode) {
        static final Outcome DONE = new Outcome(null, false);
    }

    /** Reconferencia final reprovada depois das escritas: desfaz a transacao inteira (passo, step-up, evento). */
    private static final class StepRollback extends RuntimeException {
        final transient Outcome outcome;

        StepRollback(Outcome outcome) {
            super(outcome.rule(), null, false, false);
            this.outcome = outcome;
        }
    }

    /** POST /api/v1/auth/step-up. Sem sessao valida: 401; restrita: 403; codigo ausente: 400 (no controller). */
    public void stepUp(IdentityPrincipal who, String sessionToken, String code, String ip) {
        limits.identityRoutes(ip);
        if (who == null || !Tokens.isValidFormat(sessionToken)) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        if (who.restricted()) {
            throw ApiException.forbidden("sessao_restrita");
        }
        Optional<String> email = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_ONLY,
                (id, j) -> id.referencia().filter(who.sessionId()::equals).isPresent() ? auth.email(j, id.identityId()).orElse(null) : null);
        if (email.isEmpty()) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        String identity = hmac.global(email.get());
        limits.identityBeforeAuthentication(identity, ip, true);
        UUID correlation = correlation();
        Outcome r;
        try {
            r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
                if (!id.identityId().equals(who.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()
                        || !auth.lockIdentity(j, id.identityId())) {
                    return new Outcome("sessao", false);
                }
                // reconferencia da sessao e da identidade SOB os locks (logout, suspensao ou T0 de outra sessao no intervalo)
                Optional<IdentityAuthRepository.LockedSession> s = auth.lockSession(j, who.sessionId());
                if (s.isEmpty() || !s.get().active() || !auth.identityActive(j, id.identityId())) {
                    return new Outcome("sessao", false);
                }
                if (s.get().restricted()) {
                    return new Outcome("sessao_restrita", false);
                }
                Optional<IdentityAuthRepository.Factor> f = auth.factor(j, id.identityId(), true);
                if (f.isEmpty()) {
                    return new Outcome("sem_fator", false);
                }
                OptionalLong step = verifyTotp(f.get(), id.identityId(), code);
                if (step.isEmpty() || auth.consumeStep(j, f.get().id(), step.getAsLong()) != 1) {
                    return new Outcome("codigo", true);
                }
                if (auth.recordStepUp(j, who.sessionId()) != 1) {
                    throw new IllegalStateException("sessao travada e ativa sem step-up gravado");
                }
                auth.event(j, id.identityId(), IdentityAuthRepository.EV_STEP_UP, correlation);
                // sessao e passo foram conferidos antes das esperas (o anti-replay pode esperar o lock do fator): sao
                // reconferidos agora, depois de todas elas, antes do commit
                if (!auth.sessionStillValid(j, who.sessionId())) {
                    throw new StepRollback(new Outcome("sessao_vencida", false));
                }
                if (!stillAccepted(step.getAsLong())) {
                    throw new StepRollback(new Outcome("passo_vencido", false));
                }
                return Outcome.DONE;
            }).orElse(new Outcome("sessao", false));
        } catch (StepRollback e) {
            r = e.outcome;
        }
        if (r.rule() == null) {
            LOG.info("evento=identidade.step_up identidade={} ip={} correlacao={}", identity, ClientIpResolver.truncated(ip), correlation);
            return;
        }
        if (r.wrongCode()) {
            limits.identityMfaFailure(identity, ip);
        }
        LOG.info("evento=identidade.step_up_negado identidade={} ip={} regra={}", identity, ClientIpResolver.truncated(ip), r.rule());
        throw "sessao".equals(r.rule()) || "sessao_vencida".equals(r.rule()) ? ApiException.unauthenticated("sessao_identidade") : ApiException.forbidden("step_up");
    }

    /** Mesma tolerancia de {@code Totp.verify}, no instante medido AGORA. */
    private static boolean stillAccepted(long step) {
        long now = Totp.step(Instant.now());
        return step >= now - 1 && step <= now + 1;
    }

    private OptionalLong verifyTotp(IdentityAuthRepository.Factor f, UUID identity, String code) {
        byte[] secret = cipher.decryptForIdentity(f.secret(), identity);
        try {
            return Totp.verify(secret, code, Instant.now());
        } finally {
            Arrays.fill(secret, (byte) 0);
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
