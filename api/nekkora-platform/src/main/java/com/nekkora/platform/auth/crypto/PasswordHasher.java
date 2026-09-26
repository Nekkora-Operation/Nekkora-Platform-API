package com.nekkora.platform.auth.crypto;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

/**
 * Argon2id (decisao 8.10, contrato revisao 4, §4). Formato PHC autocontido e versionado:
 * {@code $argon2id$v=19$m=<KiB>,t=<it>,p=<par>$<salt b64>$<hash b64>} (salt 16 bytes, hash 32 bytes, base64 sem
 * padding) - compativel com o Argon2PasswordEncoder do Spring Security.
 *
 * <p>TODA operacao Argon2 (verificacao real, verificacao ficticia e geracao) passa pelo mesmo semaforo:
 * {@code simultaneos} permissoes e no maximo {@code fila} a espera por {@code esperaMs}; fila cheia ou tempo
 * esgotado -> 503 (§2.1, item 3). Nada disso roda dentro de transacao (quem chama garante).
 *
 * <p>Perfil vigente P1: argon2id v19 com p = 1 (§4.3). A atualizacao de hash so ocorre por aumento de memoria
 * ou iteracoes, nunca por reducao, e nunca muda p, versao ou algoritmo.
 */
public final class PasswordHasher {

    private static final Pattern PHC = Pattern.compile(
            "^\\$argon2id\\$v=(\\d+)\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$([A-Za-z0-9+/]+)\\$([A-Za-z0-9+/]+)$");
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Limite de entrada no login: acima disso, 401 sem calcular Argon2 (§4.4). */
    public static final int MAX_BYTES_LOGIN = 1024;

    public record Parameters(int memoryKib, int iterations, int parallelism) {
        public Parameters {
            if (parallelism != 1) {
                throw new IllegalArgumentException("perfil P1 exige paralelismo 1");
            }
            if (memoryKib < 19456 || iterations < 2 && memoryKib < 47104) {
                throw new IllegalArgumentException("parametros abaixo do piso OWASP");
            }
        }
    }

    private final Parameters current;
    private final Semaphore slots;
    private final AtomicInteger waiting = new AtomicInteger();
    private final int queue;
    private final long waitMs;
    private final String dummy;

    public PasswordHasher(Parameters current, int maxConcurrent, int queue, long waitMs) {
        this.current = current;
        this.slots = new Semaphore(maxConcurrent, true);
        this.queue = queue;
        this.waitMs = waitMs;
        // hash ficticio: parametros vigentes, senha e salt aleatorios nunca persistidos (§4.4)
        byte[] randomPassword = new byte[32];
        RANDOM.nextBytes(randomPassword);
        this.dummy = generateWithoutSemaphore(B64.encodeToString(randomPassword).toCharArray());
    }

    public Parameters current() {
        return current;
    }

    /** Gera o hash de uma senha nova (pelo semaforo). */
    public String generate(char[] password) {
        return withPermission(() -> generateWithoutSemaphore(password));
    }

    /**
     * Verifica a senha contra o hash armazenado. Se o hash for nulo ou de formato desconhecido, verifica contra o
     * hash FICTICIO (mesmo custo) e devolve false.
     */
    public boolean verify(char[] password, String stored) {
        Matcher m = stored == null ? null : PHC.matcher(stored);
        boolean known = m != null && m.matches() && "19".equals(m.group(1)) && "1".equals(m.group(4));
        String target = known ? stored : dummy;
        boolean ok = withPermission(() -> check(password, target));
        return known && ok;
    }

    /** Verificacao do caminho de conta inexistente (sempre false, mesmo custo). */
    public void verifyDummy(char[] password) {
        withPermission(() -> check(password, dummy));
    }

    /**
     * Politica de atualizacao (§4.3): so refaz se for argon2id v19 p=1 com m <= vigente e t <= vigente e pelo
     * menos um estritamente menor. Qualquer outro caso: mantem (e o chamador pode alertar).
     */
    public boolean needsRehash(String stored) {
        Matcher m = PHC.matcher(stored == null ? "" : stored);
        if (!m.matches() || !"19".equals(m.group(1)) || !"1".equals(m.group(4))) {
            return false;
        }
        long mem = Long.parseLong(m.group(2));
        long it = Long.parseLong(m.group(3));
        return mem <= current.memoryKib() && it <= current.iterations() && (mem < current.memoryKib() || it < current.iterations());
    }

    private String generateWithoutSemaphore(char[] password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] h = compute(password, salt, current.memoryKib(), current.iterations(), current.parallelism());
        return "$argon2id$v=19$m=" + current.memoryKib() + ",t=" + current.iterations() + ",p=" + current.parallelism()
                + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(h);
    }

    private static boolean check(char[] password, String phc) {
        Matcher m = PHC.matcher(phc);
        if (!m.matches()) {
            return false;
        }
        byte[] salt = B64D.decode(m.group(5));
        byte[] expected = B64D.decode(m.group(6));
        byte[] actual = compute(password, salt, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)),
                expected.length);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] compute(char[] password, byte[] salt, int m, int t, int p) {
        return compute(password, salt, m, t, p, 32);
    }

    private static byte[] compute(char[] password, byte[] salt, int m, int t, int p, int outputLength) {
        Argon2Parameters prm = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(m).withIterations(t).withParallelism(p).withSalt(salt).build();
        Argon2BytesGenerator g = new Argon2BytesGenerator();
        g.init(prm);
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[bb.remaining()];
        bb.get(bytes);
        try {
            byte[] out = new byte[outputLength];
            g.generateBytes(bytes, out);
            return out;
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (bb.hasArray()) {
                Arrays.fill(bb.array(), (byte) 0);
            }
        }
    }

    private <T> T withPermission(java.util.function.Supplier<T> op) {
        if (waiting.incrementAndGet() > queue + slots.availablePermits()) {
            waiting.decrementAndGet();
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "argon2_fila_cheia", 1L);
        }
        boolean obtained = false;
        try {
            obtained = slots.tryAcquire(waitMs, TimeUnit.MILLISECONDS);
            if (!obtained) {
                throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "argon2_espera_esgotada", 1L);
            }
            waiting.decrementAndGet();
            return op.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "argon2_interrompido", 1L);
        } finally {
            if (obtained) {
                slots.release();
            } else {
                waiting.updateAndGet(v -> v > 0 ? v - 1 : 0);
            }
        }
    }

    private void withPermission(Runnable op) {
        withPermission(() -> {
            op.run();
            return null;
        });
    }
}
