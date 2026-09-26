package com.nekkora.platform.web;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.nekkora.core.ratelimit.RedisLimiter.LimiterUnavailable;
import com.nekkora.core.token.Tokens;

/**
 * Estado do token CSRF sincronizador no contexto PRE-AUTENTICACAO (contrato, §5.1): Redis
 * {@code nk:csrf:pre:<sha256(pre_id)>} -> token, TTL 30 min. Nao guarda principal nem permissao. Rotacionado: o
 * desafio e a sessao emitem tokens proprios, e este e apagado quando o desafio e criado.
 */
public final class PreAuthCsrf {

    public static final Duration VALIDITY = Duration.ofMinutes(30);

    public record Issued(String preId, String token) {
    }

    private final StringRedisTemplate redis;

    public PreAuthCsrf(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Issued issue() {
        String preId = Tokens.newToken();
        String token = Tokens.newToken();
        callRedis(() -> {
            redis.opsForValue().set(key(preId), token, VALIDITY);
            return null;
        });
        return new Issued(preId, token);
    }

    public boolean isValid(String preId, String token) {
        if (!Tokens.isValidFormat(preId) || token == null) {
            return false;
        }
        String expected = callRedis(() -> Optional.ofNullable(redis.opsForValue().get(key(preId))).orElse(""));
        return !expected.isEmpty() && Tokens.constantTimeEquals(expected, token);
    }

    public void discard(String preId) {
        if (Tokens.isValidFormat(preId)) {
            try {
                redis.delete(key(preId));
            } catch (RuntimeException e) {
                // expira sozinho em 30 min
            }
        }
    }

    private static String key(String preId) {
        return "nk:csrf:pre:" + Tokens.sha256Hex(preId);
    }

    private static <T> T callRedis(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (RuntimeException e) {
            throw new LimiterUnavailable();
        }
    }
}
