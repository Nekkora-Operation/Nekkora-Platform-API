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
 * AAD = organization_id || user_id || key_id - um ciphertext copiado para outra linha nao decifra. O fator da IDENTIDADE
 * (Missao 1, DC-17.3) usa AAD = "identity" || identity_id || key_id, sem organizacao: o prefixo separa os dois dominios. As chaves vem
 * do servico de segredos (na stack local, variavel de ambiente), identificadas por key_id para rotacao; nunca
 * versionadas. So a chave vigente cifra; qualquer chave conhecida decifra. A fila de e-mail (021) usa o prefixo "mail" no AAD.
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

    /** Fator da identidade (DC-17.3): AAD = "identity|" + identity_id + "|" + key_id. */
    public Encrypted encryptForIdentity(byte[] secret, UUID identity) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        return new Encrypted(runCipher(Cipher.ENCRYPT_MODE, currentKey, nonce, identityAad(identity, currentKey), secret), nonce, currentKey);
    }

    public byte[] decryptForIdentity(Encrypted c, UUID identity) {
        if (!keys.containsKey(c.keyId())) {
            throw new IllegalStateException("key_id desconhecido");
        }
        return runCipher(Cipher.DECRYPT_MODE, c.keyId(), c.nonce(), identityAad(identity, c.keyId()), c.ciphertext());
    }

    /**
     * Fila de e-mail (missao 002, migration 021): destinatario e token de um trabalho, em repouso. AAD = "mail|" + job_id +
     * "|" + key_id: o payload copiado para outro trabalho nao decifra.
     */
    public Encrypted encryptForMail(byte[] payload, UUID job) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        return new Encrypted(runCipher(Cipher.ENCRYPT_MODE, currentKey, nonce, "mail|" + job + "|" + currentKey, payload), nonce, currentKey);
    }

    public byte[] decryptForMail(Encrypted c, UUID job) {
        if (!keys.containsKey(c.keyId())) {
            throw new IllegalStateException("key_id desconhecido");
        }
        return runCipher(Cipher.DECRYPT_MODE, c.keyId(), c.nonce(), "mail|" + job + "|" + c.keyId(), c.ciphertext());
    }

    private static String identityAad(UUID identity, String keyId) {
        return "identity|" + identity + "|" + keyId;
    }

    private byte[] runCipher(int mode, String keyId, byte[] nonce, UUID org, UUID user, byte[] input) {
        return runCipher(mode, keyId, nonce, org + "|" + user + "|" + keyId, input);
    }

    private byte[] runCipher(int mode, String keyId, byte[] nonce, String aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(keys.get(keyId), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("falha de cifra do segredo TOTP");
        }
    }
}
