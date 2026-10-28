package com.nekkora.platform.identity;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.core.db.IdentityTransactions.Mode;
import com.nekkora.core.db.IdentityTransactions.TokenSessao;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.ClientIpResolver;
import com.nekkora.core.web.RequestContext;
import com.nekkora.platform.auth.crypto.IdentityHmac;
import com.nekkora.platform.identity.IdentitySessionResolver.IdentityPrincipal;
import com.nekkora.platform.limits.Limits;

/**
 * Aceite de convite (contrato de 2026-10-27, §4.3), SO pela propria destinataria, com a sessao de identidade e o CSRF dela.
 * Uma transacao que chama so {@code identity_accept_invitation} (023): a funcao trava identidade -> sessao -> organizacao ->
 * convite -> contas, mede a hora depois dos locks e reconfere step-up, prazo, e-mail, organizacao ativa e a autoridade do
 * emissor; cria a conta, o vinculo, o papel e a ligacao (canal {@code convite}), ou readmite, ou recusa sem efeito.
 * Nenhuma conta existente e ligada por igualdade de e-mail.
 *
 * <p>Respostas: convite inexistente, vencido, revogado, ja aceito, de outro e-mail, de emissor sem autoridade ou de
 * organizacao nao ativa -> 404 unico (o motivo nao e revelado); ja membro, conta existente nao ligada e conta ligada
 * indisponivel -> 409 com o codigo proprio (so depois de provada a destinataria).
 */
public final class InvitationAcceptService {

    private static final Logger LOG = LoggerFactory.getLogger(InvitationAcceptService.class);

    private final IdentityTransactions tx;
    private final IdentityAuthRepository auth;
    private final IdentityHmac hmac;
    private final Limits limits;

    public InvitationAcceptService(IdentityTransactions tx, IdentityAuthRepository auth, IdentityHmac hmac, Limits limits) {
        this.tx = tx;
        this.auth = auth;
        this.hmac = hmac;
        this.limits = limits;
    }

    public void accept(IdentityPrincipal who, String sessionToken, UUID organizationId, String invitationToken, String ip) {
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
        String result = Tokens.isValidFormat(invitationToken)
                ? tx.preAuthentication(Mode.READ_WRITE, j -> accept(j, sessionToken, invitationToken, organizationId))
                : "INVALIDO";
        if (!"OK".equals(result)) {
            LOG.info("evento=convite.aceite_negado org={} identidade={} ip={} regra={}", organizationId, identity, ClientIpResolver.truncated(ip), result);
            throw switch (result) {
                case "JA_MEMBRO" -> new ApiException(ApiError.ALREADY_MEMBER, "ja_membro");
                case "CONTA_EXISTENTE" -> new ApiException(ApiError.ACCOUNT_EXISTS, "conta_existente");
                case "CONTA_INDISPONIVEL" -> new ApiException(ApiError.ACCOUNT_UNAVAILABLE, "conta_indisponivel");
                default -> new ApiException(ApiError.NOT_FOUND, "convite");
            };
        }
        LOG.info("evento=convite.aceito org={} identidade={} ip={} correlacao={}", organizationId, identity, ClientIpResolver.truncated(ip),
                RequestContext.correlation());
    }

    private static String accept(JdbcClient j, String sessionToken, String invitationToken, UUID organizationId) {
        return j.sql("SELECT public.identity_accept_invitation(?, ?, ?)").param(sessionToken).param(invitationToken).param(organizationId)
                .query(String.class).single();
    }
}
