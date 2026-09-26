package com.nekkora.platform.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.nekkora.core.web.ApiException;
import com.nekkora.platform.auth.PasswordPolicy;

/** Unitarios das primitivas (sem banco): complementam, nao substituem, os testes com PostgreSQL e papeis reais. */
class PrimitivesTest {

    /** RFC 6238, apendice B (SHA-1, segredo "12345678901234567890"); os 6 ultimos digitos dos vetores de 8. */
    @Test
    void totpRfc6238Vectors() {
        byte[] s = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        Map<Long, String> vectors = Map.of(59L, "287082", 1111111109L, "081804", 1111111111L, "050471", 1234567890L, "005924",
                2000000000L, "279037", 20000000000L, "353130");
        vectors.forEach((t, expected) -> assertThat(Totp.code(s, Totp.step(Instant.ofEpochSecond(t)))).as("t=%s", t).isEqualTo(expected));
        Instant currentTime = Instant.ofEpochSecond(1234567890L);
        assertThat(Totp.verify(s, "005924", currentTime)).hasValue(Totp.step(currentTime));
        assertThat(Totp.verify(s, Totp.code(s, Totp.step(currentTime) + 1), currentTime)).as("+1 passo aceito").isPresent();
        assertThat(Totp.verify(s, Totp.code(s, Totp.step(currentTime) + 2), currentTime)).as("+2 passos recusado").isEmpty();
        assertThat(Totp.verify(s, "12345", currentTime)).isEmpty();
    }

    @Test
    void base32RoundTrip() {
        byte[] b = Totp.newSecret();
        assertThat(Base32.decode(Base32.encode(b))).isEqualTo(b);
        assertThat(Base32.encode("foobar".getBytes())).isEqualTo("MZXW6YTBOI");
    }

    @Test
    void argon2FormatPolicyNoDowngradeAndDummy() {
        PasswordHasher h = new PasswordHasher(new PasswordHasher.Parameters(19456, 2, 1), 2, 2, 5000);
        String phc = h.generate("senha-longa-teste".toCharArray());
        assertThat(phc).matches("^\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}$");
        assertThat(h.verify("senha-longa-teste".toCharArray(), phc)).isTrue();
        assertThat(h.verify("senha-errada-xxx".toCharArray(), phc)).isFalse();
        assertThat(h.verify("x".toCharArray(), null)).as("sem hash: verifica o ficticio e nega").isFalse();
        assertThat(h.verify("x".toCharArray(), "$2a$10$bcrypt...")).as("formato desconhecido").isFalse();

        PasswordHasher current = new PasswordHasher(new PasswordHasher.Parameters(65536, 3, 1), 2, 2, 5000);
        assertThat(current.needsRehash(phc)).as("m e t menores: sobe").isTrue();
        assertThat(current.needsRehash("$argon2id$v=19$m=65536,t=3,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).isFalse();
        assertThat(current.needsRehash("$argon2id$v=19$m=131072,t=2,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .as("m maior e t menor: nao e 'so aumento' - mantem (regra de migracao propria)").isFalse();
        assertThat(current.needsRehash("$argon2id$v=19$m=19456,t=2,p=4$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .as("p diferente de 1: fora do perfil P1 - mantem").isFalse();
        assertThat(current.needsRehash("$argon2i$v=19$m=19456,t=2,p=1$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).isFalse();
        assertThatThrownBy(() -> new PasswordHasher.Parameters(16384, 2, 1)).as("padrao do Spring (16 MiB) abaixo do piso").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordHasher.Parameters(65536, 3, 4)).as("perfil P1 exige p=1").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void argon2SemaphoreLimitsConcurrencyAndQueue() throws Exception {
        PasswordHasher h = new PasswordHasher(new PasswordHasher.Parameters(65536, 3, 1), 1, 1, 150);
        ExecutorService ex = Executors.newFixedThreadPool(6);
        CountDownLatch startSignal = new CountDownLatch(1);
        AtomicInteger unavailable = new AtomicInteger();
        List<Future<?>> fs = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(ex.submit(() -> {
                startSignal.await();
                try {
                    h.generate("senha-longa-teste".toCharArray());
                } catch (ApiException e) {
                    if (e.apiError() == com.nekkora.core.web.ApiError.SERVICE_UNAVAILABLE) {
                        unavailable.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        startSignal.countDown();
        for (Future<?> f : fs) {
            f.get();
        }
        ex.shutdown();
        assertThat(unavailable.get()).as("1 simultaneo + 1 na fila (espera 150 ms): o excedente recebe 503").isGreaterThanOrEqualTo(3);
    }

    @Test
    void passwordPolicyCountsCodePoints() {
        PasswordPolicy.enforce("123456789012".toCharArray());
        assertThatThrownBy(() -> PasswordPolicy.enforce("12345678901".toCharArray())).isInstanceOf(ApiException.class);
        String emojis = "😀".repeat(12);
        assertThat(emojis.length()).isEqualTo(24);
        PasswordPolicy.enforce(emojis.toCharArray());
        assertThatThrownBy(() -> PasswordPolicy.enforce("a".repeat(129).toCharArray())).isInstanceOf(ApiException.class);
        PasswordPolicy.enforce("😀".repeat(128).toCharArray());
    }

    @Test
    void secretCipherAadPreventsDecryptionForAnotherAccount() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        SecretCipher c = new SecretCipher("k1", Map.of("k1", key));
        UUID org = UUID.randomUUID();
        UUID u = UUID.randomUUID();
        byte[] secret = Totp.newSecret();
        SecretCipher.Encrypted cf = c.encrypt(secret, org, u);
        assertThat(cf.ciphertext()).hasSize(36);
        assertThat(c.decrypt(cf, org, u)).isEqualTo(secret);
        assertThatThrownBy(() -> c.decrypt(cf, org, UUID.randomUUID())).as("ciphertext copiado para outra conta").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recoveryCodes80Bits() {
        List<String> cs = RecoveryCodes.generate();
        assertThat(cs).hasSize(10).doesNotHaveDuplicates();
        assertThat(RecoveryCodes.normalize(cs.get(0).toLowerCase())).hasSize(16);
        assertThat(RecoveryCodes.normalize("abc")).isNull();
    }
}
