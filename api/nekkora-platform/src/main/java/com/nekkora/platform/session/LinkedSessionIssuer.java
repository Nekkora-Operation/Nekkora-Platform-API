package com.nekkora.platform.session;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Lado da ORGANIZACAO da porta de emissao (levantamento de modularidade, §4.3; desenho §6.5 e §13.5): o unico codigo
 * que cria sessao de organizacao para conta vinculada. A identidade so o alcanca pela porta {@code OrgSessionIssuer},
 * ligada em {@code IdentityConfig}; este pacote nao conhece a identidade.
 *
 * <p>Roda DENTRO da transacao de quem chama (revisao 1 do bloco 3), para que a emissao e o evento dela sejam uma coisa
 * so: a transacao tem de estar em READ COMMITTED (R4b) e com o contexto da organizacao alvo - conferidos aqui, senao e
 * erro de programacao. Ordem, sem excecao (a identidade e a sessao de identidade ja vem travadas por quem chama, primeiro
 * na ordem global da 017 - pre-condicao da porta, revisao 2):
 * <ol>
 * <li>organizacao {@code FOR SHARE} (ordem global organizacao -> conta, a mesma de quem suspende);</li>
 * <li>conta {@code FOR UPDATE}, ANTES da politica de limite: duas emissoes concorrentes da mesma conta seguram juntas o
 * lock compartilhado da organizacao, e so o lock da conta as serializa antes de contar as sessoes;</li>
 * <li>revogacao das excedentes e {@code INSERT}. A trigger da 017 trava a conta de novo (sem custo) e reconfere vinculo,
 * sessao de identidade, identidade e elegibilidade.</li>
 * </ol>
 * A recusa da trigger (23514) e desfeita ate um savepoint e devolvida como vazio, sem distinguir a causa: a transacao
 * continua utilizavel para quem chama registrar a recusa. Qualquer outro erro sobe e desfaz a transacao inteira.
 */
public final class LinkedSessionIssuer {

    private static final String SAVEPOINT = "emissao_sessao_vinculada";

    private final SessionRepository sessions;

    public LinkedSessionIssuer(SessionRepository sessions) {
        this.sessions = sessions;
    }

    /** Prazo da sessao criada, ou vazio se a organizacao ou a conta nao aparecem, ou se o banco recusou. */
    public Optional<OffsetDateTime> issue(JdbcClient j, UUID org, UUID user, UUID identitySession, OffsetDateTime mfaVerifiedAt,
                                          OffsetDateTime notAfter, String tokenHash, String csrfToken) {
        Boolean ok = j.sql("SELECT current_setting('transaction_isolation') = 'read committed' AND current_setting('nekkora.organization_id', true) = ?")
                .param(org.toString()).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("emissao vinculada exige READ COMMITTED e o contexto da organizacao alvo");
        }
        if (j.sql("SELECT 1 FROM public.organizations WHERE organization_id = ? FOR SHARE").param(org).query(Integer.class).optional().isEmpty()) {
            return Optional.empty();
        }
        if (j.sql("SELECT 1 FROM public.users WHERE organization_id = ? AND user_id = ? FOR UPDATE").param(org).param(user)
                .query(Integer.class).optional().isEmpty()) {
            return Optional.empty();
        }
        j.sql("SAVEPOINT " + SAVEPOINT).update();
        try {
            OffsetDateTime expires = sessions.createLinked(j, org, user, identitySession, mfaVerifiedAt, notAfter, tokenHash, csrfToken);
            j.sql("RELEASE SAVEPOINT " + SAVEPOINT).update();
            return Optional.of(expires);
        } catch (DataAccessException e) {
            if (!"23514".equals(sqlState(e))) {
                throw e;
            }
            j.sql("ROLLBACK TO SAVEPOINT " + SAVEPOINT).update();
            return Optional.empty();
        }
    }

    private static String sqlState(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getSQLState() != null) {
                return s.getSQLState();
            }
        }
        return null;
    }
}
