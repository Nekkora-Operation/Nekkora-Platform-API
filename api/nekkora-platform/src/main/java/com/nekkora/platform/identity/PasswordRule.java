package com.nekkora.platform.identity;

/**
 * Porta da politica de senha nova. A regra continua sendo a da Platform ({@code auth.PasswordPolicy}, contrato §1), mas a
 * identidade nao importa {@code platform.auth} fora de {@code auth.crypto} (levantamento de modularidade, §4.2): a
 * montagem (config) liga esta porta a {@code PasswordPolicy::enforce}. Recusa com a mesma excecao da politica (400).
 */
@FunctionalInterface
public interface PasswordRule {

    void enforce(char[] password);
}
