package com.nekkora.platform.identity;

import java.util.List;
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
 * Organizacoes disponiveis para a identidade (desenho §6.4) e criacao de organizacao (§6.6, contrato P2).
 *
 * <p>A lista vem inteira de {@code identity_organizations} (017), com a mesma definicao de elegibilidade da emissao:
 * vinculo interno ACTIVE, conta interna ACTIVE com e-mail verificado, organizacao ativa. Sessao restrita a recuperacao
 * (DC-17.13): 403 aqui, e zero linhas no banco de qualquer forma.
 *
 * <p>{@code canCreateOrganization} e SO indicacao para a interface: chave da criacao ligada, sessao nao restrita e
 * {@code identity_org_creation_allowed} (R5) verdadeira. O step-up nao entra (e pedido na hora). A criacao reconfere
 * tudo dentro de {@code identity_create_organization}, sob o lock da identidade, qualquer que seja o valor mostrado.
 */
public final class IdentityOrganizationsService {

    public record Listing(List<IdentityOrgRepository.Organization> organizations, boolean canCreateOrganization) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(IdentityOrganizationsService.class);

    private final IdentityTransactions tx;
    private final IdentityOrgRepository orgs;
    private final Limits limits;
    private final boolean creationEnabled;

    public IdentityOrganizationsService(IdentityTransactions tx, IdentityOrgRepository orgs, Limits limits, boolean creationEnabled) {
        this.tx = tx;
        this.orgs = orgs;
        this.limits = limits;
        this.creationEnabled = creationEnabled;
    }

    /** GET /api/v1/me/organizations. Sem sessao valida: 401; sessao restrita: 403. */
    public Listing list(IdentityPrincipal who, String sessionToken, String ip) {
        limits.identityRoutes(ip);
        if (who == null || !Tokens.isValidFormat(sessionToken)) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        if (who.restricted()) {
            throw ApiException.forbidden("sessao_restrita");
        }
        Optional<Listing> found = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE,
                (id, j) -> id.referencia().filter(who.sessionId()::equals).isPresent()
                        ? new Listing(orgs.organizations(j, sessionToken), creationEnabled && orgs.creationAllowed(j, sessionToken)) : null);
        if (found.isEmpty()) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        return found.get();
    }

    /**
     * POST /api/v1/organizations (contrato P2). Corpo ja validado no controller (400). Ordem: R12 -> sessao (401) e
     * restricao (403) pelo principal -> R11 (reserva atomica; 429; Redis fora: 503) -> uma transacao READ COMMITTED que
     * chama SO {@code identity_create_organization}. Recusa da funcao (sem step-up, teto, identidade inelegivel, sessao
     * restrita): 403 generico; se a recusa veio de a sessao ter caido (revogada ou vencida), 401. Sessao que nao resolve
     * ao abrir a transacao: 401.
     *
     * <p>Reserva de R11 por desfecho (revisao 1 do P2):
     * <ul>
     * <li>recusa da funcao (nulo: ela nao escreve nada), mesmo que o commit falhe depois: devolvida;</li>
     * <li>excecao ANTES de a funcao criar (inclusive dentro dela): o commit nunca foi enviado, rollback - devolvida;</li>
     * <li>commit confirmado: mantida;</li>
     * <li>excecao DEPOIS de a funcao ter criado (no commit ou depois dele): o banco pode ter confirmado e a resposta se
     * perdido. Resultado desconhecido - mantida, com registro da incerteza. Erra para o lado seguro.</li>
     * </ul>
     */
    public UUID create(IdentityPrincipal who, String sessionToken, String name, String legalName, String ip) {
        limits.identityRoutes(ip);
        if (who == null || !Tokens.isValidFormat(sessionToken)) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        if (who.restricted()) {
            throw ApiException.forbidden("sessao_restrita");
        }
        String reservation = limits.reserveOrganizationCreation(who.identityId());
        // a funcao devolveu uma organizacao nesta transacao: daqui em diante uma excecao nao prova rollback
        boolean[] created = {false};
        Optional<Optional<UUID>> r;
        try {
            r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
                if (!id.identityId().equals(who.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()) {
                    return null;
                }
                Optional<UUID> org = orgs.createOrganization(j, sessionToken, name, legalName);
                if (org.isPresent()) {
                    created[0] = true;
                    return org;
                }
                // recusa: a sessao caiu (401) ou outra condicao da funcao (403); a funcao nao gravou nada
                return orgs.sessionActive(j, who.sessionId()) ? org : null;
            });
        } catch (RuntimeException e) {
            if (created[0]) {
                LOG.warn("evento=identidade.criacao_organizacao_incerta regra=R11 efeito=reserva_mantida ip={} correlacao={}",
                        ClientIpResolver.truncated(ip), RequestContext.correlation());
            } else {
                limits.releaseOrganizationCreation(who.identityId(), reservation);
            }
            throw e;
        }
        if (r.isEmpty() || r.get().isEmpty()) {
            limits.releaseOrganizationCreation(who.identityId(), reservation);
            String rule = r.isEmpty() ? "sessao" : "recusa";
            LOG.info("evento=identidade.criacao_organizacao_negada regra={} ip={}", rule, ClientIpResolver.truncated(ip));
            throw r.isEmpty() ? ApiException.unauthenticated("sessao_identidade") : ApiException.forbidden("criacao_organizacao");
        }
        LOG.info("evento=identidade.organizacao_criada organizacao={} ip={} correlacao={}", r.get().get(), ClientIpResolver.truncated(ip),
                RequestContext.correlation());
        return r.get().get();
    }
}
