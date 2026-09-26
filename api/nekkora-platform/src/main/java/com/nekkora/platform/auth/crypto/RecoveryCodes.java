package com.nekkora.platform.auth.crypto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import com.nekkora.core.token.Tokens;

/**
 * Codigos de recuperacao de MFA (contrato, §1): 10 codigos de 80 bits (16 caracteres base32), uso unico,
 * mostrados uma vez; o banco guarda so o SHA-256 da forma normalizada (maiusculas, sem separadores).
 */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    public static List<String> generate() {
        List<String> codes = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            byte[] b = new byte[10];
            RANDOM.nextBytes(b);
            String c = Base32.encode(b);
            codes.add(c.substring(0, 4) + "-" + c.substring(4, 8) + "-" + c.substring(8, 12) + "-" + c.substring(12, 16));
        }
        return codes;
    }

    /** Forma normalizada ou null se nao tiver o formato de um codigo. */
    public static String normalize(String code) {
        if (code == null) {
            return null;
        }
        String n = code.replace("-", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        return n.matches("^[A-Z2-7]{16}$") ? n : null;
    }

    public static String hash(String normalized) {
        return Tokens.sha256Hex(normalized);
    }
}
