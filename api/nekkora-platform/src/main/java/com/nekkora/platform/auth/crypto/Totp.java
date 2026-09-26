package com.nekkora.platform.auth.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238) com os parametros aprovados (contrato, §1): HMAC-SHA1, 6 digitos, passo de 30 s, tolerancia de
 * +/-1 passo, segredo de 160 bits. {@link #verificar} devolve o PASSO aceito; o anti-replay (passo > ultimo
 * aceito) e aplicado atomicamente no banco pelo chamador (UPDATE ... WHERE last_used_step < passo RETURNING).
 */
public final class Totp {

    public static final int SECRET_BYTES = 20;
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static byte[] newSecret() {
        byte[] s = new byte[SECRET_BYTES];
        RANDOM.nextBytes(s);
        return s;
    }

    public static long step(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    /** Codigo do passo informado (6 digitos, com zeros a esquerda). */
    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0F;
            int bin = ((h[o] & 0x7F) << 24) | ((h[o + 1] & 0xFF) << 16) | ((h[o + 2] & 0xFF) << 8) | (h[o + 3] & 0xFF);
            return String.format("%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Passo aceito (agora-1, agora, agora+1), comparando em tempo constante; vazio se nenhum confere. */
    public static OptionalLong verify(byte[] secret, String code, Instant currentTime) {
        if (code == null || !code.matches("^\\d{" + DIGITS + "}$")) {
            return OptionalLong.empty();
        }
        long p = step(currentTime);
        long accepted = -1;
        for (long candidate = p - 1; candidate <= p + 1; candidate++) {
            if (MessageDigest.isEqual(code(secret, candidate).getBytes(), code.getBytes()) && accepted < 0) {
                accepted = candidate;
            }
        }
        return accepted < 0 ? OptionalLong.empty() : OptionalLong.of(accepted);
    }

    public static String uri(byte[] secret, String account) {
        String label = java.net.URLEncoder.encode("Nekkora:" + account, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + label + "?secret=" + Base32.encode(secret) + "&issuer=Nekkora&algorithm=SHA1&digits=6&period=30";
    }
}
