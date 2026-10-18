package com.nekkora.platform.identity;

import java.time.Duration;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Vinculacao de conta antiga (contrato P3), SEM ESTADO: o desafio de vinculacao ({@code identity_link_challenges}, RLS por
 * identidade) e a funcao de vinculacao da 017. O consumo do desafio, o fechamento da DC-17.12, o vinculo e o evento 9 sao
 * todos de {@code identity_link_account}; a Platform so a chama.
 */
public final class IdentityLinkRepository {

    /** Prazo do desafio de vinculacao (o CHECK da 017 limita a 5 min contados de created_at). */
    public static final Duration CHALLENGE_VALIDITY = Duration.ofMinutes(5);

    /**
     * Revoga todo desafio de vinculacao vivo da identidade do contexto (com a limpeza da credencial que o CHECK da 017
     * exige). So um desafio vivo por identidade: o de uma etapa 1 anterior deixa de valer.
     */
    public int revokeLive(JdbcClient j, UUID identity) {
        return j.sql("""
                UPDATE public.identity_link_challenges SET revoked_at = clock_timestamp(), account_credential_hash = NULL
                 WHERE identity_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
                """).param(identity).update();
    }

    /**
     * Desafio novo, amarrado ao par EXATO identidade/conta (colunas imutaveis pela trigger da 017), com o hash da credencial
     * conferida e so o hash do token. A trigger de INSERT da 017 trava a conta de novo e confere e-mail igual e ausencia de
     * vinculo (23514 se nao). created_at e expires_at saem do mesmo instante da transacao.
     */
    public void create(JdbcClient j, UUID identity, UUID user, String tokenHash, String accountCredentialHash) {
        j.sql("""
                INSERT INTO public.identity_link_challenges (identity_id, user_id, token_hash, account_credential_hash, expires_at)
                VALUES (?, ?, ?, ?, now() + ?::interval)
                """).param(identity).param(user).param(tokenHash).param(accountCredentialHash)
                .param(CHALLENGE_VALIDITY.toSeconds() + " seconds").update();
    }

    /**
     * {@code identity_link_account}: trava identidade -> sessao -> organizacao -> desafio -> conta -> linhas do fechamento,
     * mede a hora depois dos locks, reconfere step-up, conta, e-mail e ausencia de vinculo, consome o desafio (so o da
     * identidade da sessao, no prazo, com a credencial ainda vigente) e executa o fechamento inteiro e o evento 9.
     * {@code false}: recusa, sem efeito.
     */
    public boolean link(JdbcClient j, String sessionToken, String linkToken, UUID organization) {
        return Boolean.TRUE.equals(j.sql("SELECT public.identity_link_account(?, ?, ?)")
                .param(sessionToken).param(linkToken).param(organization).query(Boolean.class).single());
    }
}
