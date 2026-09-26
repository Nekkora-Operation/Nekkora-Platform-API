package com.nekkora.platform.limits;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.ratelimit.RedisLimiter;
import com.nekkora.core.ratelimit.RedisLimiter.LimiterUnavailable;
import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;
import com.nekkora.core.web.ClientIpResolver;
import com.nekkora.platform.config.PlatformProperties;

/**
 * Regras da camada 2 (contrato 8.6, §2.3), com a configuracao inicial de teste. Nada aqui dorme: atraso
 * progressivo = "nao antes de" no Redis + 429 com Retry-After (§2.1, item 2). Chaves por HMAC da identidade
 * (nunca e-mail em claro). Redis indisponivel -> 503 nas chamadas que falham fechado (§2.1, item 6).
 */
public final class Limits {

    private static final Logger LOG = LoggerFactory.getLogger(Limits.class);

    private final RedisLimiter redis;
    private final PlatformProperties.Limits cfg;

    public Limits(RedisLimiter redis, PlatformProperties.Limits cfg) {
        this.redis = redis;
        this.cfg = cfg;
    }

    /** Antes de verificar senha ou codigo: R6 (organizacao), R1 (identidade+IP), R2/R4 (nao antes de). */
    public void beforeAuthentication(UUID org, String identity, String ip, boolean mfa) {
        failClosed(() -> {
            long waitTime = redis.consume("nk:rl:r6:" + org, cfg.r6Capacity(), cfg.r6PerSecond());
            if (waitTime > 0) {
                LOG.warn("evento=auth.rate_limited regra=R6 org={} ip={}", org, ClientIpResolver.truncated(ip));
                throw rateLimited(waitTime);
            }
            if (redis.count(r1(org, identity, ip), cfg.r1Window()) >= cfg.r1Failures()) {
                LOG.info("evento=auth.rate_limited regra=R1 org={} identidade={} ip={}", org, identity, ClientIpResolver.truncated(ip));
                throw rateLimited(cfg.r1Window().toMillis());
            }
            long remainingWait = Math.max(redis.remainingMs(notBeforeKey("r2", org, identity)), mfa ? redis.remainingMs(notBeforeKey("r4", org, identity)) : 0);
            if (remainingWait > 0) {
                LOG.info("evento=auth.rate_limited regra={} org={} identidade={} ip={}", mfa ? "R2/R4" : "R2", org, identity,
                        ClientIpResolver.truncated(ip));
                throw rateLimited(remainingWait);
            }
            return null;
        });
    }

    /** Falha de senha: R1 e R2 (atraso progressivo a partir da r2Inicio-esima falha na janela). */
    public void passwordFailure(UUID org, String identity, String ip) {
        failClosed(() -> {
            redis.recordFailure(r1(org, identity, ip), cfg.r1Window());
            applyProgressiveDelay("r2", org, identity, redis.recordFailure("nk:rl:r2:" + org + ":" + identity, cfg.r2Window()), cfg.r2Threshold(), cfg.r2Window());
            return null;
        });
    }

    /** Falha de codigo MFA: R1 e R4. */
    public void mfaFailure(UUID org, String identity, String ip) {
        failClosed(() -> {
            redis.recordFailure(r1(org, identity, ip), cfg.r1Window());
            long n = redis.recordFailure("nk:rl:r4:" + org + ":" + identity, cfg.r4Window());
            applyProgressiveDelay("r4", org, identity, n, cfg.r4Failures() + 1, cfg.r4Window());
            return null;
        });
    }

    /** Login COMPLETO (senha + MFA, sessao criada): zera os contadores de autenticacao (§2.1, item 4). */
    public void fullLogin(UUID org, String identity, String ip) {
        try {
            redis.deleteKeys(r1(org, identity, ip), "nk:rl:r2:" + org + ":" + identity, notBeforeKey("r2", org, identity),
                    "nk:rl:r4:" + org + ":" + identity, notBeforeKey("r4", org, identity));
        } catch (LimiterUnavailable e) {
            LOG.warn("evento=limitador.indisponivel operacao=zerar_contadores");
        }
    }

    /** R5: pedido de recuperacao/reenvio. false = nao enviar (a resposta continua 202 identica). */
    public boolean recoveryAllowed(UUID org, String identity, String ip) {
        return failClosed(() -> {
            long byIdentity = redis.recordFailure("nk:rl:r5i:" + org + ":" + identity, cfg.r5Window());
            long byIp = redis.recordFailure("nk:rl:r5p:" + ip, cfg.r5Window());
            return byIdentity <= cfg.r5PerIdentity() && byIp <= cfg.r5PerIp();
        });
    }

    /** R7 (sessao validada ou IP). Retorna false se o Redis estiver indisponivel (quem chama decide). */
    public boolean bySessionOrIp(String key) {
        try {
            long waitTime = redis.consume("nk:rl:r7:" + key, cfg.r7Capacity(), cfg.r7PerSecond());
            if (waitTime > 0) {
                throw rateLimited(waitTime);
            }
            return true;
        } catch (LimiterUnavailable e) {
            LOG.warn("evento=limitador.indisponivel regra=R7");
            return false;
        }
    }

    private void applyProgressiveDelay(String rule, UUID org, String identity, long failures, int threshold, Duration window) {
        if (failures >= threshold) {
            long steps = Math.min(20, failures - threshold);
            long delay = Math.min(cfg.r2MaxDelay().toMillis(), cfg.r2InitialDelay().toMillis() << steps);
            redis.notBefore(notBeforeKey(rule, org, identity), Duration.ofMillis(delay), window);
        }
    }

    private static String r1(UUID org, String identity, String ip) {
        return "nk:rl:r1:" + org + ":" + identity + ":" + ip;
    }

    private static String notBeforeKey(String rule, UUID org, String identity) {
        return "nk:rl:" + rule + "nb:" + org + ":" + identity;
    }

    private static ApiException rateLimited(long waitMs) {
        return new ApiException(ApiError.RATE_LIMITED, "limite", Math.max(1, (waitMs + 999) / 1000));
    }

    private static <T> T failClosed(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (LimiterUnavailable e) {
            LOG.warn("evento=limitador.indisponivel efeito=falha_fechada");
            throw new ApiException(ApiError.SERVICE_UNAVAILABLE, "redis_indisponivel", 1L);
        }
    }
}
