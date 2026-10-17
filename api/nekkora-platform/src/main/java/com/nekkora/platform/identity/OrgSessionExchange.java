package com.nekkora.platform.identity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
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
import com.nekkora.platform.identity.IdentitySessionResolver.IdentityPrincipal;
import com.nekkora.platform.limits.Limits;

/**
 * Troca da sessao de identidade por sessao de organizacao (desenho §6.5, DC-17.6; porta do levantamento de
 * modularidade, §4.3).
 *
 * <ol>
 * <li>Resolucao inicial, em transacao de identidade: {@code identity_org_session_target} indica a CANDIDATA (organizacao,
 * conta). A candidata nao autoriza nada; a recusa aqui e registrada nesta transacao.</li>
 * <li>Emissao pela porta {@link OrgSessionIssuer}, numa transacao READ COMMITTED com os contextos da identidade e da
 * organizacao, na ordem global de locks da 017 (identidade -> sessao de identidade -> organizacao -> conta; revisao 2):
 * a candidata e resolvida de novo, travando identidade e sessao de identidade; dela saem o marcador de MFA herdado e o
 * prazo maximo; so depois a porta trava organizacao e conta, antes da politica de limite; e a reconferencia completa no banco, pela trigger
 * da 017 (vinculo, sessao de identidade ativa e nao restrita, identidade, elegibilidade da conta, da organizacao e do
 * vinculo interno). O evento (emissao ou recusa) e gravado NA MESMA transacao: falhou o evento, nada foi emitido.</li>
 * </ol>
 * Resposta uniforme (DC-17.6): {@code sessao_criada} ou {@code sem_acesso}, este para QUALQUER causa (organizacao
 * inexistente, suspensa ou encerrada, sem vinculo, vinculo inativo, conta inelegivel, mudanca entre as duas transacoes),
 * as duas com o mesmo piso de tempo. Sessao restrita a recuperacao: 403 (DC-17.13), antes de qualquer consulta.
 */
public final class OrgSessionExchange {

    private static final Logger LOG = LoggerFactory.getLogger(OrgSessionExchange.class);

    public record Result(String status, String token, String csrfToken, OffsetDateTime expiresAt) {
        static final Result NO_ACCESS = new Result("sem_acesso", null, null, null);

        public boolean issued() {
            return token != null;
        }
    }

    private record Candidate(UUID identityId, UUID userId) {
    }

    private final IdentityTransactions tx;
    private final IdentityOrgRepository orgs;
    private final OrgSessionIssuer issuer;
    private final Limits limits;
    private final Duration responseFloor;

    public OrgSessionExchange(IdentityTransactions tx, IdentityOrgRepository orgs, OrgSessionIssuer issuer, Limits limits, Duration responseFloor) {
        this.tx = tx;
        this.orgs = orgs;
        this.issuer = issuer;
        this.limits = limits;
        this.responseFloor = responseFloor;
    }

    /** POST /api/v1/sessions/organization. Sem sessao valida: 401; restrita: 403; corpo sem organizacao valida: 400. */
    public Result exchange(IdentityPrincipal who, String sessionToken, UUID organizationId, String ip) {
        limits.identityRoutes(ip);
        if (who == null || !Tokens.isValidFormat(sessionToken)) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        limits.orgSessionExchange(Tokens.sha256Hex(sessionToken));
        if (who.restricted()) {
            throw ApiException.forbidden("sessao_restrita");
        }
        if (organizationId == null) {
            throw ApiException.invalid("organizacao");
        }
        long start = System.nanoTime();
        try {
            Result r = issue(who, sessionToken, organizationId);
            LOG.info("evento=identidade.troca_sessao resultado={} ip={} correlacao={}", r.status(), ClientIpResolver.truncated(ip),
                    RequestContext.correlation());
            return r;
        } finally {
            floor(start);
        }
    }

    private Result issue(IdentityPrincipal who, String sessionToken, UUID organizationId) {
        UUID correlation = correlation();
        Optional<Candidate> c = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
            if (!id.referencia().filter(who.sessionId()::equals).isPresent()) {
                return null;
            }
            Optional<IdentityOrgRepository.Target> t = orgs.target(j, sessionToken, organizationId);
            Optional<IdentityOrgRepository.SessionClock> clock = orgs.sessionClock(j, who.sessionId());
            if (t.isEmpty() || clock.isEmpty()) {
                orgs.event(j, id.identityId(), IdentityOrgRepository.EV_ORG_SESSION_DENIED, null, correlation);
                return null;
            }
            return new Candidate(id.identityId(), t.get().userId());
        });
        if (c.isEmpty()) {
            return Result.NO_ACCESS;
        }
        String token = Tokens.newToken();
        String csrf = Tokens.newToken();
        // emissao e evento numa so transacao, com os dois contextos e em READ COMMITTED: a sessao e o registro dela (ou da
        // recusa) sao confirmados juntos, ou nada e. Sessao de identidade que deixou de resolver aqui: nada emitido
        Optional<OffsetDateTime> expires = tx.inIdentityAndOrganization(new TokenSessao(sessionToken), organizationId, Mode.READ_WRITE, (id, j) -> {
            if (!id.identityId().equals(c.get().identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()) {
                return Optional.<OffsetDateTime>empty();
            }
            // revisao 2: ordem global da 017 (identidade -> sessao de identidade -> organizacao -> conta). A candidata e
            // resolvida de novo pela funcao da 017, que trava identidade e sessao de identidade FOR UPDATE ANTES da porta
            // travar organizacao e conta. Sem isso, as chaves estrangeiras do INSERT da sessao (identity_session_id) e do
            // evento (identity_id) pediriam essas linhas DEPOIS da organizacao - ciclo com a vinculacao, que trava
            // identidade e sessao e depois espera a organizacao
            Optional<IdentityOrgRepository.Target> t = orgs.target(j, sessionToken, organizationId);
            Optional<IdentityOrgRepository.SessionClock> clock = orgs.sessionClock(j, who.sessionId());
            Optional<OffsetDateTime> e = t.isEmpty() || !t.get().userId().equals(c.get().userId()) || clock.isEmpty()
                    ? Optional.<OffsetDateTime>empty()
                    : issuer.issue(j, organizationId, c.get().userId(), who.sessionId(), clock.get().mfaVerifiedAt(), clock.get().expiresAt(),
                            Tokens.sha256Hex(token), csrf);
            orgs.event(j, id.identityId(), e.isPresent() ? IdentityOrgRepository.EV_ORG_SESSION_ISSUED : IdentityOrgRepository.EV_ORG_SESSION_DENIED,
                    e.isPresent() ? organizationId : null, correlation);
            return e;
        }).flatMap(e -> e);
        return expires.map(e -> new Result("sessao_criada", token, csrf, e)).orElse(Result.NO_ACCESS);
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
