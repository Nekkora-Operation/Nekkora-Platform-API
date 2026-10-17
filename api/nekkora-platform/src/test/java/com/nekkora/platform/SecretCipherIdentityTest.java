package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.platform.auth.crypto.SecretCipher;

/**
 * Segredo TOTP da IDENTIDADE (DC-17.3): AAD = "identity" || identity_id || key_id, sem organizacao. Um ciphertext de uma
 * identidade nao decifra para outra, nem pela via de organizacao; a via de organizacao continua como era.
 */
class SecretCipherIdentityTest {

    private static SecretCipher cipher() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return new SecretCipher("k1", Map.of("k1", k));
    }

    @Test
    void aadDaIdentidadeSeparaOsDominios() {
        SecretCipher c = cipher();
        byte[] secret = "segredo-totp-20-bytes".getBytes();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        SecretCipher.Encrypted e = c.encryptForIdentity(secret, a);
        assertThat(c.decryptForIdentity(e, a)).as("ida e volta").isEqualTo(secret);
        assertThatThrownBy(() -> c.decryptForIdentity(e, b)).as("outra identidade").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.decrypt(e, a, a)).as("via de organizacao").isInstanceOf(IllegalStateException.class);
        SecretCipher.Encrypted org = c.encrypt(secret, a, b);
        assertThat(c.decrypt(org, a, b)).as("via de organizacao inalterada").isEqualTo(secret);
        assertThatThrownBy(() -> c.decryptForIdentity(org, a)).as("ciphertext de organizacao nao vale como de identidade")
                .isInstanceOf(IllegalStateException.class);
    }
}
