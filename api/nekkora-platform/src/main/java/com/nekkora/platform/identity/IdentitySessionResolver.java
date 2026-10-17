package com.nekkora.platform.identity;

import java.util.Optional;
import java.util.UUID;

import com.nekkora.core.db.IdentityTransactions;
import com.nekkora.core.db.IdentityTransactions.Mode;
import com.nekkora.core.db.IdentityTransactions.TokenDesafio;
import com.nekkora.core.db.IdentityTransactions.TokenSessao;
import com.nekkora.core.token.Tokens;

/**
 * Resolucao, A CADA REQUISICAO e a partir do banco, da sessao e do desafio de identidade dos cookies (DC-17.4, desenho
 * §6.7): nada em HttpSession. Usado pelos filtros da borda HTTP, que assim nao acessam o banco nem dependem de um servico
 * de fluxo. So leitura, pelas funcoes de consulta por chave exata da 017 e com o contexto da identidade resolvida.
 */
public final class IdentitySessionResolver {

    /** Principal da sessao de identidade. {@code restricted}: nascida de codigo de recuperacao, antes de T1 (DC-17.13). */
    public record IdentityPrincipal(UUID sessionId, UUID identityId, String csrfToken, boolean restricted) {
    }

    private final IdentityTransactions tx;
    private final IdentityAuthRepository auth;

    public IdentitySessionResolver(IdentityTransactions tx, IdentityAuthRepository auth) {
        this.tx = tx;
        this.auth = auth;
    }

    /** Sessao de identidade ativa e no prazo, ou vazio (token malformado, desconhecido, revogado, vencido). */
    public Optional<IdentityPrincipal> session(String token) {
        if (!Tokens.isValidFormat(token)) {
            return Optional.empty();
        }
        return tx.inIdentity(new TokenSessao(token), Mode.READ_ONLY, (id, j) -> id.referencia()
                .flatMap(s -> auth.session(j, s))
                .map(s -> new IdentityPrincipal(s.id(), s.identityId(), s.csrfToken(), s.restricted()))
                .orElse(null));
    }

    /** CSRF do desafio de identidade vivo do cookie, ou vazio. */
    public Optional<String> challengeCsrf(String token) {
        if (!Tokens.isValidFormat(token)) {
            return Optional.empty();
        }
        return tx.inIdentity(new TokenDesafio(token), Mode.READ_ONLY, (id, j) -> id.referencia()
                .flatMap(c -> auth.challenge(j, c))
                .map(IdentityAuthRepository.Challenge::csrfToken)
                .orElse(null));
    }
}
