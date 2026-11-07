package com.nekkora.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.mail.MailQueueCipher;

/**
 * Regras da partida do transporte real fora do Graph: a chave propria da fila, que continua decifrando o que a chave do
 * MFA cifrou.
 */
class MailConfigRestricoesTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static String chave() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    void semChavePropriaAFilaUsaADoMfa() {
        SecretCipher mfa = new SecretCipher("k1", SecretCipher.parseKeys("k1:" + chave()));
        assertThat(MailConfig.queueCipher(mfa, "k1:" + chave(), "", "").cipher()).isSameAs(mfa);
    }

    @Test
    void chavePropriaCifraSoComElaEDecifraOQueOMfaCifrou() {
        String mfaKeys = "k1:" + chave();
        SecretCipher mfa = new SecretCipher("k1", SecretCipher.parseKeys(mfaKeys));
        UUID job = UUID.randomUUID();
        SecretCipher.Encrypted antiga = mfa.encryptForMail("destinatario e token".getBytes(StandardCharsets.UTF_8), job);

        MailQueueCipher fila = MailConfig.queueCipher(mfa, mfaKeys, "fila1", "fila1:" + chave());
        assertThat(new String(fila.cipher().decryptForMail(antiga, job), StandardCharsets.UTF_8)).isEqualTo("destinatario e token");
        SecretCipher.Encrypted nova = fila.cipher().encryptForMail("novo".getBytes(StandardCharsets.UTF_8), job);
        assertThat(nova.keyId()).isEqualTo("fila1");
        // a chave do MFA nao decifra o que a fila cifrou com a chave propria
        assertThatThrownBy(() -> mfa.decryptForMail(nova, job)).hasMessageContaining("key_id desconhecido");
    }

    @Test
    void chaveDaFilaIncompletaOuComIdDoMfaNaoSobe() {
        String mfaKeys = "k1:" + chave();
        SecretCipher mfa = new SecretCipher("k1", SecretCipher.parseKeys(mfaKeys));
        assertThatThrownBy(() -> MailConfig.queueCipher(mfa, mfaKeys, "fila1", "")).hasMessageContaining("vao juntas");
        assertThatThrownBy(() -> MailConfig.queueCipher(mfa, mfaKeys, "fila2", "fila1:" + chave())).hasMessageContaining("fora de");
        assertThatThrownBy(() -> MailConfig.queueCipher(mfa, mfaKeys, "k1", "k1:" + chave())).hasMessageContaining("repetido");
        assertThatThrownBy(() -> SecretCipher.parseKeys("f1:" + chave() + ",f1:" + chave())).hasMessageContaining("repetido");
    }
}
