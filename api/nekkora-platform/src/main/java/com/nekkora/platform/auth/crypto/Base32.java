package com.nekkora.platform.auth.crypto;

/** Base32 RFC 4648 sem padding (segredo TOTP no otpauth:// e codigos de recuperacao). */
public final class Base32 {

    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer >> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        }
        return sb.toString();
    }

    public static byte[] decode(String s) {
        String t = s.trim().toUpperCase().replace("=", "");
        byte[] out = new byte[t.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int i = 0;
        for (char c : t.toCharArray()) {
            int v = c >= 'A' && c <= 'Z' ? c - 'A' : c >= '2' && c <= '7' ? c - '2' + 26 : -1;
            if (v < 0) {
                throw new IllegalArgumentException("base32 invalido");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[i++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }
}
