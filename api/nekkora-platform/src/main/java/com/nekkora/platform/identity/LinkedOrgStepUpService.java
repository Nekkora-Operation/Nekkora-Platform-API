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
import com.nekkora.platform.limits.Limits;

/**
 * Step-up por organizacao em sessao VINCULADA (contrato P4, parecer de 2026-10-14): a rota existente
 * {@code POST /api/v1/o/{org}/auth/mfa/step-up}, quando a sessao de organizacao nasceu da troca (tem
 * {@code identity_session_id}), confere o TOTP do FATOR DA IDENTIDADE de origem e eleva SO aquela sessao de organizacao.
 * A sessao de identidade nao recebe {@code mfa_step_up_at}, e nenhuma outra sessao de organizacao e elevada.
 *
 * <p>O CSRF da sessao de organizacao continua exigido pelo filtro (o CSRF global nao o substitui). O cookie
 * {@code __Host-nekkora_id} e exigencia ADICIONAL e tem de resolver EXATAMENTE a sessao de identidade de origem: ausente,
 * de outra identidade ou de outra sessao da mesma identidade -> 403, sem conferir o codigo (unica rota em que os dois
 * cookies valem juntos, excecao declarada ao §6.7 do desenho).
 *
 * <p>Uma so transacao READ COMMITTED com os dois contextos, na ordem global da 017: identidade {@code FOR UPDATE} ->
 * sessao de identidade de origem {@code FOR UPDATE}, reconferida (ativa, no prazo, nao restrita; identidade ACTIVE) ->
 * pela porta: organizacao {@code FOR SHARE} -> conta {@code FOR UPDATE} -> sessao de organizacao {@code FOR UPDATE},
 * revalidada (inclusive pela auxiliar da DC-17.4) e conferida contra a de origem -> fator da identidade lido sob o lock da
 * identidade -> TOTP -> anti-replay no fator da identidade (o passo so avanca: um codigo usado aqui nao serve para outro
 * step-up, global ou de outra organizacao, nem para o login) -> step-up SO na sessao de organizacao e evento 6 da
 * organizacao -> evento 14 ({@code ORG_STEP_UP}) da identidade, com a organizacao -> no fim, depois de todas as esperas: as
 * duas sessoes reconferidas pelo relogio do banco (caiu: rollback integral, 401) e a janela do passo (fora: rollback
 * integral, 403). Falha ao gravar qualquer evento desfaz tudo.
 *
 * <p>Respostas: 204; codigo errado, replay, sem fator ou cookie divergente: 403 generico, com o codigo errado e o replay
 * contados em R8m (identidade global, porque o fator e global); passo vencido durante as esperas: 403, sem contar; sessao
 * que caiu: 401, sem efeito. Log sem e-mail, codigo, token nem hash.
 */
public final class LinkedOrgStepUpService {

    private static final Logger LOG = LoggerFactory.getLogger(LinkedOrgStepUpService.class);

    private final IdentityTransactions tx;
    private final IdentityAuthRepository auth;
    private final LinkedOrgSessionPort orgSessions;
    private final SecretCipher cipher;
    private final IdentityHmac hmac;
    private final Limits limits;

    public LinkedOrgStepUpService(IdentityTransactions tx, IdentityAuthRepository auth, LinkedOrgSessionPort orgSessions, SecretCipher cipher,
                                  IdentityHmac hmac, Limits limits) {
        this.tx = tx;
        this.auth = auth;
        this.orgSessions = orgSessions;
        this.cipher = cipher;
        this.hmac = hmac;
        this.limits = limits;
    }

    /** Sessao de organizacao vinculada ja validada pelo filtro: id, conta, sessao de identidade de origem e token. */
    public record OrgSession(UUID organizationId, UUID sessionId, UUID userId, UUID identitySessionId, String token) {
    }

    private record Outcome(String rule, boolean wrongCode) {
        static final Outcome DONE = new Outcome(null, false);
    }

    /** Reconferencia final reprovada depois das escritas: desfaz a transacao inteira (passo, step-up, eventos). */
    private static final class StepRollback extends RuntimeException {
        final transient Outcome outcome;

        StepRollback(Outcome outcome) {
            super(outcome.rule(), null, false, false);
            this.outcome = outcome;
        }
    }

    public void stepUp(OrgSession s, String identityToken, String code, String ip) {
        UUID org = s.organizationId();
        // cookie de identidade: exigencia adicional, e so a sessao de origem EXATA. Sem conferir o codigo
        if (!Tokens.isValidFormat(identityToken)) {
            throw refused(org, "cookie_identidade_ausente");
        }
        Optional<String> email = tx.inIdentity(new TokenSessao(identityToken), Mode.READ_ONLY,
                (id, j) -> id.referencia().filter(s.identitySessionId()::equals).isPresent() ? auth.email(j, id.identityId()).orElse(null) : null);
        if (email.isEmpty()) {
            throw refused(org, "sessao_de_origem_divergente");
        }
        String identity = hmac.global(email.get());
        limits.identityBeforeAuthentication(identity, ip, true);
        UUID correlation = correlation();
        String orgTokenHash = Tokens.sha256Hex(s.token());
        Outcome r;
        try {
            r = tx.inIdentityAndOrganization(new TokenSessao(identityToken), org, Mode.READ_WRITE, (id, j) -> {
                if (!id.referencia().filter(s.identitySessionId()::equals).isPresent()) {
                    return new Outcome("sessao_de_origem_divergente", false);
                }
                if (!auth.lockIdentity(j, id.identityId())) {
                    return new Outcome("sessao", false);
                }
                // sessao de identidade de origem e identidade reconferidas SOB os locks (logout, suspensao, T0 no intervalo)
                Optional<IdentityAuthRepository.LockedSession> origin = auth.lockSession(j, s.identitySessionId());
                if (origin.isEmpty() || !origin.get().active() || origin.get().restricted() || !auth.identityActive(j, id.identityId())) {
                    return new Outcome("sessao", false);
                }
                switch (orgSessions.lock(j, org, s.userId(), s.sessionId(), orgTokenHash, s.identitySessionId())) {
                    case GONE -> {
                        return new Outcome("sessao", false);
                    }
                    case OTHER_ORIGIN -> {
                        return new Outcome("sessao_de_origem_divergente", false);
                    }
                    case VALID -> { }
                }
                Optional<IdentityAuthRepository.Factor> f = auth.factor(j, id.identityId(), true);
                if (f.isEmpty()) {
                    return new Outcome("sem_fator", false);
                }
                OptionalLong step = verifyTotp(f.get(), id.identityId(), code);
                if (step.isEmpty() || auth.consumeStep(j, f.get().id(), step.getAsLong()) != 1) {
                    return new Outcome("codigo", true);
                }
                orgSessions.recordStepUp(j, org, s.userId(), s.sessionId(), correlation);
                auth.event(j, id.identityId(), IdentityAuthRepository.EV_ORG_STEP_UP, org, correlation);
                // sessoes e passo conferidos antes das esperas (o anti-replay pode esperar o lock do fator): reconferidos
                // agora, depois de todas elas, antes do commit
                if (!auth.sessionStillValid(j, s.identitySessionId()) || !orgSessions.stillValid(j, s.sessionId())) {
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
            LOG.info("evento=identidade.step_up_organizacao org={} identidade={} ip={} correlacao={}", org, identity, ClientIpResolver.truncated(ip), correlation);
            return;
        }
        if (r.wrongCode()) {
            limits.identityMfaFailure(identity, ip);
        }
        LOG.info("evento=identidade.step_up_organizacao_negado org={} identidade={} ip={} regra={}", org, identity, ClientIpResolver.truncated(ip), r.rule());
        throw "sessao".equals(r.rule()) || "sessao_vencida".equals(r.rule()) ? ApiException.unauthenticated("sessao") : ApiException.forbidden("step_up");
    }

    private static ApiException refused(UUID org, String rule) {
        LOG.info("evento=identidade.step_up_organizacao_negado org={} regra={}", org, rule);
        return ApiException.forbidden("step_up");
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
