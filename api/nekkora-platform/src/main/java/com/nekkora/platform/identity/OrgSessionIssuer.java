package com.nekkora.platform.identity;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Porta estreita de emissao da sessao de organizacao (levantamento de modularidade, §4.2 e §4.3): o UNICO ponto em que a
 * identidade alcanca codigo de organizacao. A implementacao fica do lado da organizacao ({@code session.LinkedSessionIssuer})
 * e e ligada em {@code IdentityConfig}; este pacote nao importa {@code OrgTransactions}, sessao, conta nem MFA por
 * organizacao.
 *
 * <p>A emissao roda na transacao de quem chama ({@code IdentityTransactions.inIdentityAndOrganization}, READ COMMITTED,
 * com os dois contextos), para que o evento da identidade seja gravado no mesmo commit da sessao (revisao 1). A candidata
 * recebida e so isso: a implementacao trava organizacao e conta antes da politica de limite, e o banco reconfere, no
 * {@code INSERT}, vinculo, sessao de identidade e elegibilidade. Vazio = recusa, qualquer que seja a causa, com a
 * transacao ainda utilizavel.
 *
 * <p>Pre-condicao de quem chama (revisao 2): a ordem global de locks da 017 e identidade -> sessao de identidade ->
 * organizacao -> conta. Quem chama ja SEGURA a identidade e a sessao de identidade ({@code FOR UPDATE}, pela funcao da
 * 017 que resolve a candidata) quando chama a porta: as chaves estrangeiras do {@code INSERT} da sessao
 * ({@code identity_session_id}) e do evento ({@code identity_id}) pedem essas linhas, e pedidas depois da organizacao
 * formariam ciclo com a vinculacao.
 */
@FunctionalInterface
public interface OrgSessionIssuer {

    /**
     * @param mfaVerifiedAt marcador herdado da sessao de identidade
     * @param notAfter prazo da sessao de identidade: a de organizacao nao vive alem dela
     * @return prazo da sessao criada, ou vazio se recusada
     */
    Optional<OffsetDateTime> issue(JdbcClient j, UUID organizationId, UUID userId, UUID identitySessionId, OffsetDateTime mfaVerifiedAt,
                                   OffsetDateTime notAfter, String tokenHash, String csrfToken);
}
