package com.nekkora.core.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Primitivas do limitador distribuido (contrato 8.6, camada 2). Todas usam o relogio do Redis ({@code TIME}),
 * nao o das instancias, e sao atomicas (scripts Lua). Qualquer falha de acesso ao Redis vira
 * {@link LimiterIndisponivel}: quem chama decide entre falhar fechado (503) ou seguir com alerta (§2.1, item 6).
 *
 * <p>As chaves nunca contem e-mail em claro: o chamador passa o HMAC (§2.3).
 */
public final class RedisLimiter {

    /** Janela deslizante: remove entradas antigas; opcionalmente registra uma; devolve a contagem. */
    private static final DefaultRedisScript<Long> WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local agora = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local janela = tonumber(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', agora - janela)
            if ARGV[2] == '1' then
              local seq = redis.call('INCR', KEYS[2])
              redis.call('PEXPIRE', KEYS[2], janela)
              redis.call('ZADD', KEYS[1], agora, agora .. ':' .. seq)
            end
            redis.call('PEXPIRE', KEYS[1], janela)
            return redis.call('ZCARD', KEYS[1])
            """, Long.class);

    /** Balde de fichas: devolve {permitido(0/1), espera_ms}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final DefaultRedisScript<List> BUCKET_SCRIPT = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local agora = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local cap = tonumber(ARGV[1])
            local taxa = tonumber(ARGV[2]) / 1000.0
            local b = redis.call('HMGET', KEYS[1], 'f', 't')
            local f = tonumber(b[1]) or cap
            local ts = tonumber(b[2]) or agora
            f = math.min(cap, f + math.max(0, agora - ts) * taxa)
            local ok, espera = 0, 0
            if f >= 1 then f = f - 1; ok = 1 else espera = math.ceil((1 - f) / taxa) end
            redis.call('HSET', KEYS[1], 'f', tostring(f), 't', agora)
            redis.call('PEXPIRE', KEYS[1], math.ceil(cap / taxa) + 1000)
            return {ok, espera}
            """, List.class);

    /** "Nao antes de": devolve ms que faltam (0 se liberado). */
    private static final DefaultRedisScript<Long> REMAINING_SCRIPT = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local agora = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local nb = tonumber(redis.call('GET', KEYS[1]) or '0')
            if nb > agora then return nb - agora end
            return 0
            """, Long.class);

    /** Define "nao antes de" = agora + atraso (ms), com TTL. */
    private static final DefaultRedisScript<Long> DELAY_SCRIPT = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local agora = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            redis.call('SET', KEYS[1], agora + tonumber(ARGV[1]), 'PX', tonumber(ARGV[2]))
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long count(String key, Duration window) {
        return callRedis(() -> redis.execute(WINDOW_SCRIPT, List.of(key, key + ":seq"), Long.toString(window.toMillis()), "0"));
    }

    public long recordFailure(String key, Duration window) {
        return callRedis(() -> redis.execute(WINDOW_SCRIPT, List.of(key, key + ":seq"), Long.toString(window.toMillis()), "1"));
    }

    /** @return 0 se permitido; senao, ms ate a proxima ficha. */
    public long consume(String key, int capacity, double tokensPerSecond) {
        List<?> r = callRedis(() -> redis.execute(BUCKET_SCRIPT, List.of(key), Integer.toString(capacity), Double.toString(tokensPerSecond)));
        long ok = ((Number) r.get(0)).longValue();
        return ok == 1 ? 0 : Math.max(1, ((Number) r.get(1)).longValue());
    }

    public long remainingMs(String key) {
        return callRedis(() -> redis.execute(REMAINING_SCRIPT, List.of(key)));
    }

    public void notBefore(String key, Duration delay, Duration ttl) {
        callRedis(() -> redis.execute(DELAY_SCRIPT, List.of(key), Long.toString(delay.toMillis()), Long.toString(ttl.toMillis())));
    }

    public void deleteKeys(String... keys) {
        callRedis(() -> redis.delete(List.of(keys)));
    }

    private <T> T callRedis(java.util.function.Supplier<T> op) {
        try {
            T r = op.get();
            if (r == null) {
                throw new LimiterUnavailable();
            }
            return r;
        } catch (LimiterUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LimiterUnavailable();
        }
    }

    /** Redis indisponivel (distinto de limite excedido: 503, nao 429). */
    public static final class LimiterUnavailable extends RuntimeException {
        public LimiterUnavailable() {
            super("limitador indisponivel", null, false, false);
        }
    }
}
