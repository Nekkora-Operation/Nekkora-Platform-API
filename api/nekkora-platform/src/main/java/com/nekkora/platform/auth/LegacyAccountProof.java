package com.nekkora.platform.auth;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.platform.account.AccountRepository;
import com.nekkora.platform.account.AccountRepository.Account;
import com.nekkora.platform.auth.crypto.RecoveryCodes;
import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.auth.crypto.Totp;
import com.nekkora.platform.mfa.MfaRepository;
import com.nekkora.platform.mfa.MfaRepository.Factor;
import com.nekkora.platform.session.ValidSession;

/**
 * Lado da ORGANIZACAO da etapa 1 da vinculacao (contrato P3): a prova da conta antiga, com as mesmas primitivas do login
 * por organizacao ({@code AccountRepository}, {@code MfaRepository}). A identidade so o alcanca pela porta
 * {@code LegacyAccountPort}, ligada em {@code IdentityConfig}; este pacote nao conhece a identidade.
 *
 * <p>Roda DENTRO da transacao de quem chama, que tem de estar em READ COMMITTED e com o contexto da organizacao alvo
 * (conferidos aqui). Quem chama ja travou identidade e sessao de identidade. Ordem daqui em diante: organizacao
 * {@code FOR SHARE} -> conta {@code FOR UPDATE} -> fator local ({@code FOR UPDATE}) -> codigo local. As conferencias sao
 * todas feitas depois do lock da conta; o consumo (passo ou codigo) e a ultima coisa, e so acontece se tudo passou.
 */
public final class LegacyAccountProof {

    private final AccountRepository accounts;
    private final MfaRepository mfa;
    private final SecretCipher secretCipher;

    public LegacyAccountProof(AccountRepository accounts, MfaRepository mfa, SecretCipher secretCipher) {
        this.accounts = accounts;
        this.mfa = mfa;
        this.secretCipher = secretCipher;
    }

    /** Desfecho: {@code totpStep} nulo com codigo de recuperacao; {@code wrongCode} quando o segundo fator foi recusado. */
    public record Result(boolean ok, Long totpStep, boolean wrongCode, boolean usedRecoveryCode) {
        static final Result REFUSED = new Result(false, null, false, false);
        static final Result WRONG_CODE = new Result(false, null, true, false);
    }

    /** Conta INTERNA da organizacao pelo e-mail normalizado (leitura sem lock): id e hash vigente (pode ser nulo). */
    public Optional<Account> find(JdbcClient j, UUID org, String normalizedEmail) {
        return accounts.byEmail(j, org, normalizedEmail).filter(a -> a.accountType() == ValidSession.INTERNAL);
    }

    public Result prove(JdbcClient j, UUID org, UUID user, String normalizedEmail, String verifiedHash, String code, String recoveryCode,
                        UUID correlation) {
        Boolean ok = j.sql("SELECT current_setting('transaction_isolation') = 'read committed' AND current_setting('nekkora.organization_id', true) = ?")
                .param(org.toString()).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("prova da conta antiga exige READ COMMITTED e o contexto da organizacao alvo");
        }
        if (verifiedHash == null || (code == null) == (recoveryCode == null)) {
            return Result.REFUSED;
        }
        if (j.sql("SELECT 1 FROM public.organizations WHERE organization_id = ? FOR SHARE").param(org).query(Integer.class).optional().isEmpty()
                || !accounts.lock(j, org, user)) {
            return Result.REFUSED;
        }
        // reconferencia sob o lock da conta: elegivel (inclui NAO vinculada e organizacao ativa), interna, mesmo e-mail e a
        // MESMA credencial que o Argon2 conferiu (como a reconferencia do login)
        Optional<Account> a = accounts.byId(j, org, user);
        if (a.isEmpty() || !a.get().eligible() || a.get().accountType() != ValidSession.INTERNAL
                || !normalizedEmail.equals(a.get().email().toLowerCase(java.util.Locale.ROOT))
                || !Objects.equals(a.get().passwordHash(), verifiedHash)) {
            return Result.REFUSED;
        }
        Optional<Factor> f = mfa.activeFactor(j, user);
        if (f.isEmpty() || !f.get().confirmed()) {
            return Result.REFUSED;
        }
        if (recoveryCode != null) {
            String normalized = RecoveryCodes.normalize(recoveryCode);
            if (normalized == null || mfa.consumeRecoveryCode(j, user, f.get().id(), RecoveryCodes.hash(normalized)) != 1) {
                return Result.WRONG_CODE;
            }
            mfa.recordEvent(j, org, user, null, MfaRepository.EV_RECOVERY_CODE, correlation);
            return new Result(true, null, false, true);
        }
        OptionalLong step = verifyTotp(f.get(), org, user, code);
        if (step.isEmpty() || mfa.consumeStep(j, f.get().id(), step.getAsLong()) != 1) {
            return Result.WRONG_CODE;
        }
        return new Result(true, step.getAsLong(), false, false);
    }

    private OptionalLong verifyTotp(Factor f, UUID org, UUID user, String code) {
        byte[] secret = secretCipher.decrypt(f.secret(), org, user);
        try {
            return Totp.verify(secret, code, Instant.now());
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
