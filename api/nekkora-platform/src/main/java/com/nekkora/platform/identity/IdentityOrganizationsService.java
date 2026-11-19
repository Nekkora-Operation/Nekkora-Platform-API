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
import com.nekkora.core.db.DbErrors;
import com.nekkora.core.web.ApiError;
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

    /**
     * 067: o perfil de quem cria, os limites e as organizacoes de que a pessoa e responsavel (para desativar e reativar).
     * So indicacao para a interface: a criacao, o pedido, a desativacao e a reativacao reconferem tudo no banco.
     */
    public record Creation(IdentityOrgRepository.CreationState state, List<IdentityOrgRepository.Responsible> responsibleFor) {
    }

    /** O motivo (texto do contrato) de {@link #creationRefusal}, ou nulo. */
    public static String creationReason(IdentityOrgRepository.CreationState s) {
        ApiError e = creationRefusal(s);
        return e == null ? null : e.reason();
    }

    /** 067: por que esta pessoa nao cria direto agora (nulo = pode). A funcao do banco reconfere tudo. */
    static ApiError creationRefusal(IdentityOrgRepository.CreationState s) {
        if (s == null) {
            return null;
        }
        return switch (s.profile()) {
            case "CLIENT" -> ApiError.ORGANIZATION_CREATION_CLIENT_PROFILE;
            case "MEMBER" -> ApiError.ORGANIZATION_CREATION_REQUIRES_REQUEST;
            // sem teto fixo de organizacoes criadas: so o limite configuravel de vazias segura
            default -> s.emptyOrganizations().size() >= s.emptyLimit() ? ApiError.EMPTY_ORGANIZATION_LIMIT_REACHED : null;
        };
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
        Optional<Listing> found = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
            if (!id.referencia().filter(who.sessionId()::equals).isPresent()) {
                return null;
            }
            // 067: "pode criar" passa a considerar o perfil (membro solicita; Cliente nao cria) e o limite de vazias
            IdentityOrgRepository.CreationState state = creationEnabled ? orgs.creationState(j, sessionToken).orElse(null) : null;
            return new Listing(orgs.organizations(j, sessionToken),
                    creationEnabled && orgs.creationAllowed(j, sessionToken) && state != null && creationRefusal(state) == null);
        });
        if (found.isEmpty()) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        // T10 (034): o identificador de cada organizacao da lista, lido no contexto DELA (uma transacao curta por organizacao,
        // com a mesma sessao de identidade; a lista ja passou pela elegibilidade da 017)
        List<IdentityOrgRepository.Organization> withHandles = found.get().organizations().stream()
                .map(o -> new IdentityOrgRepository.Organization(o.organizationId(), o.name(), o.roles(), o.activationState(),
                        tx.inIdentityAndOrganization(new TokenSessao(sessionToken), o.organizationId(), Mode.READ_ONLY,
                                (id, j) -> IdentityOrgRepository.handle(j, o.organizationId()).orElse(null)).orElse(null)))
                .toList();
        return new Listing(withHandles, found.get().canCreateOrganization());
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
    /** Indice unico da 034: nome unico entre as organizacoes criadas pela mesma identidade pelo cadastro. */
    static final String ORGANIZATION_NAME_KEY = "ux_organization_authorship_creator_name";

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
        ApiError[] refused = {null};
        Optional<Optional<UUID>> r;
        try {
            r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
                if (!id.identityId().equals(who.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()) {
                    return null;
                }
                // 067: o perfil e os limites, para responder com o motivo; a funcao reconfere sob a trava da identidade
                refused[0] = orgs.creationState(j, sessionToken).map(IdentityOrganizationsService::creationRefusal).orElse(null);
                if (refused[0] != null) {
                    return Optional.empty();
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
            // T10 (034): esta identidade ja criou, pelo cadastro, uma organizacao com esse nome; nada foi criado
            if (DbErrors.uniqueViolation(e, ORGANIZATION_NAME_KEY)) {
                LOG.info("evento=identidade.criacao_organizacao_negada regra=nome_repetido ip={}", ClientIpResolver.truncated(ip));
                throw new ApiException(ApiError.ORGANIZATION_NAME_TAKEN, "nome_organizacao");
            }
            throw e;
        }
        if (r.isEmpty() || r.get().isEmpty()) {
            limits.releaseOrganizationCreation(who.identityId(), reservation);
            if (r.isPresent() && refused[0] != null) {
                LOG.info("evento=identidade.criacao_organizacao_negada regra={} ip={}", refused[0].reason(), ClientIpResolver.truncated(ip));
                throw new ApiException(refused[0], String.valueOf(refused[0].reason()));
            }
            String rule = r.isEmpty() ? "sessao" : "recusa";
            LOG.info("evento=identidade.criacao_organizacao_negada regra={} ip={}", rule, ClientIpResolver.truncated(ip));
            throw r.isEmpty() ? ApiException.unauthenticated("sessao_identidade") : ApiException.forbidden("criacao_organizacao");
        }
        LOG.info("evento=identidade.organizacao_criada organizacao={} ip={} correlacao={}", r.get().get(), ClientIpResolver.truncated(ip),
                RequestContext.correlation());
        return r.get().get();
    }

    private void requireSession(IdentityPrincipal who, String sessionToken, String ip) {
        limits.identityRoutes(ip);
        if (who == null || !Tokens.isValidFormat(sessionToken)) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        if (who.restricted()) {
            throw ApiException.forbidden("sessao_restrita");
        }
    }

    /**
     * POST /api/v1/organization-requests (067, D9 a D11): o membro que nao administra nenhuma organizacao pede a criacao ao
     * administrador de uma organizacao em que e membro interno ativo. Exige a confirmacao reforcada da sessao. Cliente: 403 com
     * o motivo. Quem ja administra alguma organizacao cria direto (409). A funcao reconfere tudo sob a trava da identidade.
     */
    public UUID request(IdentityPrincipal who, String sessionToken, UUID organization, String name, String legalName, String ip) {
        requireSession(who, sessionToken, ip);
        ApiError[] refused = {null};
        Optional<Optional<UUID>> r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
            if (!id.identityId().equals(who.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()) {
                return null;
            }
            IdentityOrgRepository.CreationState state = orgs.creationState(j, sessionToken).orElse(null);
            String profile = state == null ? null : state.profile();
            if ("CLIENT".equals(profile)) {
                refused[0] = ApiError.ORGANIZATION_CREATION_CLIENT_PROFILE;
                return Optional.empty();
            }
            Optional<UUID> made = orgs.requestOrganization(j, sessionToken, organization, name, legalName);
            if (made.isPresent()) {
                return made;
            }
            if (!orgs.sessionActive(j, who.sessionId())) {
                return null;
            }
            // membro desta organizacao, com menos de tres pedidos em aberto: o que faltou foi a confirmacao reforcada
            boolean eligible = "MEMBER".equals(profile) && state.pendingRequests() < 3
                    && orgs.organizations(j, sessionToken).stream().anyMatch(o -> o.organizationId().equals(organization));
            refused[0] = eligible ? ApiError.STEP_UP_REQUIRED : ApiError.ORGANIZATION_REQUEST_NOT_AVAILABLE;
            return made;
        });
        if (r.isEmpty()) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        if (r.get().isEmpty()) {
            LOG.info("evento=identidade.pedido_organizacao_negado regra={} ip={}", refused[0].reason(), ClientIpResolver.truncated(ip));
            throw new ApiException(refused[0], String.valueOf(refused[0].reason()));
        }
        LOG.info("evento=identidade.organizacao_pedida pedido={} ip={} correlacao={}", r.get().get(), ClientIpResolver.truncated(ip),
                RequestContext.correlation());
        return r.get().get();
    }

    /** GET /api/v1/me/organization-creation (067): perfil, limites e as organizacoes de que a pessoa e responsavel. */
    public Creation creation(IdentityPrincipal who, String sessionToken, String ip) {
        requireSession(who, sessionToken, ip);
        return tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
            if (!id.referencia().filter(who.sessionId()::equals).isPresent()) {
                return null;
            }
            IdentityOrgRepository.CreationState state = orgs.creationState(j, sessionToken).orElse(null);
            return state == null ? null : new Creation(state, orgs.responsibleFor(j, sessionToken));
        }).orElseThrow(() -> ApiException.unauthenticated("sessao_identidade"));
    }

    /** GET /api/v1/me/organization-requests (067): os pedidos da pessoa. */
    public List<IdentityOrgRepository.Request> requests(IdentityPrincipal who, String sessionToken, String ip) {
        requireSession(who, sessionToken, ip);
        return tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE,
                        (id, j) -> id.referencia().filter(who.sessionId()::equals).isPresent() ? orgs.requests(j, sessionToken) : null)
                .orElseThrow(() -> ApiException.unauthenticated("sessao_identidade"));
    }

    /**
     * POST /api/v1/organization-deactivations e /api/v1/organization-reactivations (067, D13): so o responsavel pela organizacao, com a
     * confirmacao reforcada. Desativar e recusado com trabalho em andamento e nao apaga nem libera nada. Reativar reconfere o
     * limite de vazias e nao remove um bloqueio da Nekkora.
     */
    public void setActive(IdentityPrincipal who, String sessionToken, UUID organization, boolean active, String ip) {
        requireSession(who, sessionToken, ip);
        Optional<Optional<String>> r = tx.inIdentity(new TokenSessao(sessionToken), Mode.READ_WRITE, (id, j) -> {
            if (!id.identityId().equals(who.identityId()) || !id.referencia().filter(who.sessionId()::equals).isPresent()) {
                return null;
            }
            Optional<String> out = orgs.setActive(j, sessionToken, organization, active);
            return out.isPresent() || orgs.sessionActive(j, who.sessionId()) ? out : null;
        });
        if (r.isEmpty()) {
            throw ApiException.unauthenticated("sessao_identidade");
        }
        String out = r.get().orElse(null);
        if ("OK".equals(out)) {
            LOG.info("evento=identidade.organizacao_{} organizacao={} ip={} correlacao={}", active ? "reativada" : "desativada", organization,
                    ClientIpResolver.truncated(ip), RequestContext.correlation());
            return;
        }
        ApiError error = out == null ? null : switch (out) {
            case "NOT_RESPONSIBLE", "NOT_ADMIN" -> ApiError.ORGANIZATION_NOT_RESPONSIBLE;
            case "WORK_IN_PROGRESS" -> ApiError.ORGANIZATION_WORK_IN_PROGRESS;
            case "BLOCKED_BY_NEKKORA" -> ApiError.ORGANIZATION_BLOCKED_BY_NEKKORA;
            case "EMPTY_LIMIT" -> ApiError.EMPTY_ORGANIZATION_LIMIT_REACHED;
            default -> ApiError.ORGANIZATION_STATE_CONFLICT;
        };
        LOG.info("evento=identidade.organizacao_situacao_negada regra={} ip={}", error == null ? "confirmacao" : error.reason(), ClientIpResolver.truncated(ip));
        // sem motivo: falta a confirmacao reforcada (a interface a pede e reenvia)
        throw error == null ? new ApiException(ApiError.STEP_UP_REQUIRED, "step_up") : new ApiException(error, String.valueOf(error.reason()));
    }
}
