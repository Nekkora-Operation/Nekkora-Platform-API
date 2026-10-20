package com.nekkora.platform.identity;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Porta do lado da ORGANIZACAO para o step-up por organizacao em sessao vinculada (contrato P4; mesma estrutura da
 * {@link OrgSessionIssuer}). A implementacao fica do lado da organizacao ({@code mfa.LinkedSessionStepUp}) e e ligada em
 * {@code IdentityConfig}; este pacote nao conhece sessao de organizacao, conta nem MFA por organizacao.
 *
 * <p>Tudo roda na transacao de quem chama ({@code IdentityTransactions.inIdentityAndOrganization}, READ COMMITTED, com os
 * dois contextos), para que o step-up, o evento da organizacao e o da identidade sejam confirmados juntos.
 *
 * <p>Pre-condicao de quem chama: a ordem global de locks da 017 e identidade -> sessao de identidade -> organizacao ->
 * conta. Quem chama ja SEGURA a identidade e a sessao de identidade de origem ({@code FOR UPDATE}) quando chama
 * {@link #lock}.
 */
public interface LinkedOrgSessionPort {

    /** Estado da sessao de organizacao depois dos locks. */
    enum State {
        /** valida (revalidada sob o lock, inclusive pela auxiliar da DC-17.4), da conta, e nascida da sessao de origem */
        VALID,
        /** caiu: revogada, vencida, inativa, conta ou organizacao inelegivel, ou sessao de identidade de origem inativa */
        GONE,
        /** valida, mas nao e a sessao pedida ou nasceu de outra sessao de identidade */
        OTHER_ORIGIN
    }

    /**
     * Trava organizacao {@code FOR SHARE} -> conta {@code FOR UPDATE} -> sessao de organizacao {@code FOR UPDATE} e a
     * revalida, nesta ordem, conferindo que e a sessao {@code orgSessionId}, da conta {@code userId}, nascida de
     * {@code identitySessionId}.
     */
    State lock(JdbcClient j, UUID organizationId, UUID userId, UUID orgSessionId, String orgSessionTokenHash, UUID identitySessionId);

    /** Step-up SO na sessao de organizacao travada, e o evento de step-up da organizacao ({@code mfa_events}). */
    void recordStepUp(JdbcClient j, UUID organizationId, UUID userId, UUID orgSessionId, UUID correlation);

    /** Reconferencia antes do commit: sessao de organizacao ainda nao revogada e no prazo, pelo relogio do banco. */
    boolean stillValid(JdbcClient j, UUID orgSessionId);
}
