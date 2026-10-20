package com.nekkora.platform.mfa;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.nekkora.platform.session.SessionRepository;
import com.nekkora.platform.session.ValidSession;

/**
 * Lado da ORGANIZACAO do step-up por organizacao em sessao vinculada (contrato P4): validacao e step-up da sessao de
 * organizacao e o evento em {@code mfa_events}. A identidade so o alcanca pela porta {@code LinkedOrgSessionPort}, ligada
 * em {@code IdentityConfig}; este pacote nao conhece a identidade.
 *
 * <p>Roda DENTRO da transacao de quem chama, que tem de estar em READ COMMITTED e com o contexto da organizacao alvo
 * (conferidos aqui; senao e erro de programacao). Quem chama ja travou identidade e sessao de identidade, primeiro na ordem
 * global da 017. Ordem daqui em diante: organizacao {@code FOR SHARE} -> conta {@code FOR UPDATE} -> sessao de
 * organizacao {@code FOR UPDATE}, a mesma de quem suspende (organizacao -> conta) e do step-up do login por organizacao
 * (conta -> sessao).
 */
public final class LinkedSessionStepUp {

    private final SessionRepository sessions;
    private final MfaRepository mfa;

    public LinkedSessionStepUp(SessionRepository sessions, MfaRepository mfa) {
        this.sessions = sessions;
        this.mfa = mfa;
    }

    /**
     * Trava e revalida a sessao do token; vazio se a organizacao, a conta ou a sessao valida nao aparecem. A validacao
     * mede o prazo depois do lock da sessao e inclui a auxiliar da DC-17.4 (sessao de identidade de origem ativa e nao
     * restrita, vinculo, fator da identidade, elegibilidade).
     */
    public Optional<ValidSession> lock(JdbcClient j, UUID org, UUID user, String tokenHash) {
        Boolean ok = j.sql("SELECT current_setting('transaction_isolation') = 'read committed' AND current_setting('nekkora.organization_id', true) = ?")
                .param(org.toString()).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("step-up vinculado exige READ COMMITTED e o contexto da organizacao alvo");
        }
        if (j.sql("SELECT 1 FROM public.organizations WHERE organization_id = ? FOR SHARE").param(org).query(Integer.class).optional().isEmpty()) {
            return Optional.empty();
        }
        if (j.sql("SELECT 1 FROM public.users WHERE organization_id = ? AND user_id = ? FOR UPDATE").param(org).param(user)
                .query(Integer.class).optional().isEmpty()) {
            return Optional.empty();
        }
        return sessions.validate(j, org, tokenHash, true).filter(s -> s.userId().equals(user));
    }

    /** {@code mfa_step_up_at} so na sessao travada e o evento 6 ({@code STEP_UP}) da organizacao, na mesma transacao. */
    public void recordStepUp(JdbcClient j, UUID org, UUID user, UUID session, UUID correlation) {
        if (sessions.recordStepUp(j, session) != 1) {
            throw new IllegalStateException("sessao travada e valida sem step-up gravado");
        }
        mfa.recordEvent(j, org, user, null, MfaRepository.EV_STEP_UP, correlation);
    }

    public boolean stillValid(JdbcClient j, UUID session) {
        return sessions.stillValid(j, session);
    }
}
