package com.nekkora.platform.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Porta do lado da ORGANIZACAO para a etapa 1 da vinculacao (contrato P3): achar a conta antiga e provar a posse dela
 * (senha conferida pela identidade fora da transacao; segundo fator local aqui). A implementacao fica do lado da
 * organizacao ({@code auth.LegacyAccountProof}) e e ligada em {@code IdentityConfig}; este pacote nao conhece conta,
 * credencial nem MFA por organizacao.
 *
 * <p>Tudo roda na transacao de quem chama ({@code IdentityTransactions.inIdentityAndOrganization}, READ COMMITTED, com os
 * dois contextos). Pre-condicao de {@link #prove}: quem chama ja SEGURA a identidade e a sessao de identidade
 * ({@code FOR UPDATE}), primeiro na ordem global da 017.
 */
public interface LegacyAccountPort {

    /** Conta interna da organizacao com o e-mail normalizado, e o hash vigente da credencial (pode ser nulo). */
    record Candidate(UUID userId, String passwordHash) {
    }

    /**
     * Desfecho da prova. {@code ok}: senha, conta e segundo fator conferidos sob o lock, e o fator ja consumido (passo
     * avancado ou codigo usado). {@code totpStep}: passo aceito, para a janela final (nulo com codigo de recuperacao).
     * {@code wrongCode}: o segundo fator foi conferido e recusado.
     */
    record Proof(boolean ok, Long totpStep, boolean wrongCode, boolean usedRecoveryCode) {
        public static final Proof REFUSED = new Proof(false, null, false, false);
        public static final Proof WRONG_CODE = new Proof(false, null, true, false);
    }

    /** Leitura sem lock (transacao somente leitura): a conta interna pelo e-mail, ou vazio. */
    Optional<Candidate> find(JdbcClient j, UUID organizationId, String normalizedEmail);

    /**
     * Na ordem global, depois da identidade e da sessao de identidade: organizacao {@code FOR SHARE} -> conta
     * {@code FOR UPDATE} -> fator local. Sob o lock da conta: conta interna elegivel (ACTIVE, e-mail verificado, organizacao
     * e vinculo interno ativos, NAO vinculada), e-mail igual ao da identidade, credencial vigente IGUAL a
     * {@code verifiedHash}; fator local confirmado e habilitado; TOTP com anti-replay (o passo so avanca) ou codigo de
     * recuperacao consumido, com o evento de uso de codigo da organizacao. Nada e gravado se a prova falhar.
     */
    Proof prove(JdbcClient j, UUID organizationId, UUID userId, String normalizedEmail, String verifiedHash, String code, String recoveryCode,
                UUID correlation);
}
