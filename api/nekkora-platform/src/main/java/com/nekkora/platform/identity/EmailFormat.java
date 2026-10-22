package com.nekkora.platform.identity;

/**
 * Forma do e-mail recebido nas rotas publicas da identidade (cadastro e recuperacao de senha): ate 254 caracteres, sem
 * espaco nem controle, exatamente um {@code @} com texto dos dois lados. So a forma; a existencia nunca e revelada por ela.
 * Regra unica para os servicos da identidade, que nao dependem uns dos outros (regra R6 de dependencias).
 */
final class EmailFormat {

    static final int MAX = 254;

    private EmailFormat() {
    }

    static boolean valid(String email) {
        if (email == null || email.isEmpty() || email.length() > MAX || !email.equals(email.strip())) {
            return false;
        }
        for (int i = 0; i < email.length(); i++) {
            char c = email.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        int at = email.indexOf('@');
        return at > 0 && at == email.lastIndexOf('@') && at < email.length() - 1;
    }
}
