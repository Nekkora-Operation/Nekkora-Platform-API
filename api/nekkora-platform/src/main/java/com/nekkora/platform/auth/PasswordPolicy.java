package com.nekkora.platform.auth;

import com.nekkora.core.web.ApiException;
import java.util.EnumSet;
import java.util.Set;

/**
 * Senha nova (contrato, §1; PLATFORM-SENHA-001): de 12 a 128 PONTOS DE CODIGO Unicode, com ao menos uma letra maiuscula, uma
 * minuscula, um numero e um simbolo. Vale onde uma senha e CRIADA OU SUBSTITUIDA (cadastro, inclusive o primeiro acesso pelo
 * convite de cliente; troca; recuperacao) — em todas as rotas, pois todas passam por aqui. O login NAO aplica esta regra: as
 * senhas existentes continuam valendo como foram criadas.
 *
 * <p>Sem normalizacao: nada e cortado, aparado ou trocado (os bytes UTF-8 exatos vao ao Argon2). As classes seguem as
 * categorias gerais do Unicode — as mesmas da Web ({@code \p{Lu}}, {@code \p{Ll}}, {@code \p{Nd}}, {@code \p{P}}/{@code \p{S}}):
 * maiuscula = Lu; minuscula = Ll; numero = Nd; simbolo = pontuacao ou simbolo. Espaco e permitido, mas nao conta como simbolo.
 * A senha nunca entra em log nem na mensagem de erro.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    /** Requisito da senha nova, na ordem em que a tela os mostra. */
    public enum Requirement {
        LENGTH, UPPERCASE, LOWERCASE, DIGIT, SYMBOL
    }

    private PasswordPolicy() {
    }

    /** Requisitos que a senha NAO cumpre (vazio = cumpre todos). {@code null} nao cumpre nenhum. */
    public static Set<Requirement> missing(char[] password) {
        Set<Requirement> missing = EnumSet.allOf(Requirement.class);
        if (password == null) {
            return missing;
        }
        int codePoints = 0;
        for (int i = 0; i < password.length; ) {
            int cp = Character.codePointAt(password, i);
            i += Character.charCount(cp);
            codePoints++;
            switch (Character.getType(cp)) {
                case Character.UPPERCASE_LETTER -> missing.remove(Requirement.UPPERCASE);
                case Character.LOWERCASE_LETTER -> missing.remove(Requirement.LOWERCASE);
                case Character.DECIMAL_DIGIT_NUMBER -> missing.remove(Requirement.DIGIT);
                case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                     Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                     Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                     Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> missing.remove(Requirement.SYMBOL);
                default -> {
                }
            }
        }
        if (codePoints >= MIN_LENGTH && codePoints <= MAX_LENGTH) {
            missing.remove(Requirement.LENGTH);
        }
        return missing;
    }

    /** Recusa (400 {@code senha_nova}) a senha nova que nao cumpre todos os requisitos. */
    public static void enforce(char[] password) {
        if (!missing(password).isEmpty()) {
            throw ApiException.invalid("senha_nova");
        }
    }
}
