package com.nekkora.platform.auth;

import com.nekkora.core.web.ApiException;

/**
 * Senha nova (contrato, §1): 12-128 PONTOS DE CODIGO Unicode, sem regras de composicao e sem normalizacao
 * (os bytes UTF-8 exatos sao usados no Argon2).
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    public static void enforce(char[] password) {
        if (password == null) {
            throw ApiException.invalid("senha_nova");
        }
        int codePoints = Character.codePointCount(password, 0, password.length);
        if (codePoints < MIN_LENGTH || codePoints > MAX_LENGTH) {
            throw ApiException.invalid("senha_nova");
        }
    }
}
