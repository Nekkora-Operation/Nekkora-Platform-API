package com.nekkora.core.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Contrato D2 (8.3): token opaco de 32 bytes aleatorios em base64url sem padding (43 caracteres); o banco
 * guarda so o SHA-256 em hex minusculo dos bytes UTF-8 do token. O MESMO formato vale para sessao, desafio de
 * MFA, CSRF, recuperacao de senha e verificacao de e-mail.
 */
public final class Tokens {

    /** 32 bytes em base64url canonico: o 43o caractere so carrega 4 bits (16 valores possiveis). */
    public static final Pattern FORMAT_D2 = Pattern.compile("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private Tokens() {
    }

    public static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return B64URL.encodeToString(b);
    }

    public static boolean isValidFormat(String token) {
        return token != null && token.length() == 43 && FORMAT_D2.matcher(token).matches();
    }

    /** SHA-256 hex minusculo dos bytes UTF-8 - igual a {@code encode(sha256(convert_to(t, 'UTF8')), 'hex')} da 013. */
    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Comparacao em tempo constante (CSRF, codigos). */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
