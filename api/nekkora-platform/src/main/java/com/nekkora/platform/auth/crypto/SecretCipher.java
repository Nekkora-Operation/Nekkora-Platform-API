package com.nekkora.platform.auth.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifra do segredo TOTP em repouso (contrato, §3.3): AES-256-GCM, IV de 96 bits aleatorio por segredo,
 * AAD = organization_id || user_id || key_id - um ciphertext copiado para outra linha nao decifra. As chaves vem
 * do servico de segredos (na stack local, variavel de ambiente), identificadas por key_id para rotacao; nunca
 * versionadas. So a chave vigente cifra; qualquer chave conhecida decifra.
 */
public final class SecretCipher {

    public record Encrypted(byte[] ciphertext, byte[] nonce, String keyId) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private final String currentKey;
    private final Map<String, byte[]> keys;

    public SecretCipher(String currentKey, Map<String, byte[]> keys) {
        if (!keys.containsKey(currentKey)) {
            throw new IllegalArgumentException("chave vigente ausente");
        }
        keys.values().forEach(k -> {
            if (k.length != 32) {
                throw new IllegalArgumentException("chave AES precisa de 32 bytes");
            }
        });
        this.currentKey = currentKey;
        this.keys = Map.copyOf(keys);
    }

    public Encrypted encrypt(byte[] secret, UUID org, UUID user) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        return new Encrypted(runCipher(Cipher.ENCRYPT_MODE, currentKey, nonce, org, user, secret), nonce, currentKey);
    }

    public byte[] decrypt(Encrypted c, UUID org, UUID user) {
        if (!keys.containsKey(c.keyId())) {
            throw new IllegalStateException("key_id desconhecido");
        }
        return runCipher(Cipher.DECRYPT_MODE, c.keyId(), c.nonce(), org, user, c.ciphertext());
    }

    private byte[] runCipher(int mode, String keyId, byte[] nonce, UUID org, UUID user, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(keys.get(keyId), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD((org + "|" + user + "|" + keyId).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("falha de cifra do segredo TOTP");
        }
    }
}
