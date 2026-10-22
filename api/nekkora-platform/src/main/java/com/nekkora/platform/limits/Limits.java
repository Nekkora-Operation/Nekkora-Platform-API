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

    /**
     * R12: balde das rotas de identidade sem organizacao, por IP, com a capacidade e a taxa de R6 (desenho da Missao 1,
     * secao 7). Esgotado -> 429; Redis fora -> 503 (falha fechada).
     */
    public void identityRoutes(String ip) {
        failClosed(() -> {
            long waitTime = redis.consume("nk:rl:r12:" + ip, cfg.r6Capacity(), cfg.r6PerSecond());
            if (waitTime > 0) {
                LOG.info("evento=identidade.rate_limited regra=R12 ip={}", ClientIpResolver.truncated(ip));
                throw rateLimited(waitTime);
            }
            return null;
        });
    }

    /** R9: pedido de cadastro por IP (valores de r5-per-ip). false = nao processar (a resposta continua 202 identica). */
    public boolean signupAllowed(String ip) {
        return failClosed(() -> redis.recordFailure("nk:rl:r9p:" + ip, cfg.r5Window()) <= cfg.r5PerIp());
    }

    /**
     * R9b: e-mail de cadastro (confirmacao reemitida ou aviso de conta existente) por identidade GLOBAL e por IP (valores
     * de r5-*). false = nao enviar (a resposta continua 202 identica).
     */
    public boolean signupMailAllowed(String globalIdentity, String ip) {
        return failClosed(() -> {
            long byIdentity = redis.recordFailure("nk:rl:r9bi:" + globalIdentity, cfg.r5Window());
            long byIp = redis.recordFailure("nk:rl:r9bp:" + ip, cfg.r5Window());
            return byIdentity <= cfg.r5PerIdentity() && byIp <= cfg.r5PerIp();
        });
    }

    /**
     * R14 (missao 002): pedido de recuperacao de senha da IDENTIDADE, por identidade GLOBAL e por IP, com os valores de
     * R5. false = nao emitir nem enviar (a resposta continua 202 identica). Redis fora: falha fechada (503).
     */
    public boolean identityRecoveryAllowed(String globalIdentity, String ip) {
        return failClosed(() -> {
            long byIdentity = redis.recordFailure("nk:rl:r14i:" + globalIdentity, cfg.r5Window());
            long byIp = redis.recordFailure("nk:rl:r14p:" + ip, cfg.r5Window());
            return byIdentity <= cfg.r5PerIdentity() && byIp <= cfg.r5PerIp();
        });
    }

    /**
     * R10: troca por sessao de organizacao, por sessao de IDENTIDADE (chave: hash do token, nunca o token), 30 por hora
     * (desenho da Missao 1, secao 7). Esgotado -> 429; Redis fora -> 503 (falha fechada).
     */
    public void orgSessionExchange(String identitySessionHash) {
        failClosed(() -> {
            if (redis.recordFailure("nk:rl:r10:" + identitySessionHash, cfg.r10Window()) > cfg.r10Exchanges()) {
                LOG.info("evento=identidade.rate_limited regra=R10");
                throw rateLimited(cfg.r10Window().toMillis());
            }
            return null;
        });
    }

    /**
     * R11: criacao de organizacao por identidade (contrato P2), {@code r11-creations} por {@code r11-window}. Reserva
     * atomica (confere e registra no mesmo script, sem corrida entre pedidos concorrentes); quem chama devolve a reserva
     * com {@link #releaseOrganizationCreation} so quando a criacao comprovadamente nao aconteceu. Em resultado
     * desconhecido ou falha da devolucao, a reserva fica, sem criacao confirmada, ate sair da janela. Esgotado ->
     * 429; Redis fora -> 503 (falha fechada). Chave pelo id da identidade (nunca e-mail).
     */
    public String reserveOrganizationCreation(UUID identity) {
        return failClosed(() -> {
            RedisLimiter.Reservation r = redis.reserve(r11(identity), cfg.r11Window(), cfg.r11Creations());
            if (!r.granted()) {
                LOG.info("evento=identidade.rate_limited regra=R11");
                throw rateLimited(r.waitMs());
            }
            return r.member();
        });
    }

    /** Devolve a reserva de R11. Redis fora: a reserva fica consumida ate sair da janela (erro para o lado seguro). */
    public void releaseOrganizationCreation(UUID identity, String member) {
        try {
            redis.release(r11(identity), member);
        } catch (LimiterUnavailable e) {
            LOG.warn("evento=limitador.indisponivel operacao=devolver_reserva regra=R11");
        }
    }

    private static String r11(UUID identity) {
        return "nk:rl:r11:" + identity;
    }

    /**
     * R13 (contrato P3): antes da prova da conta antiga, as falhas de vinculacao por identidade GLOBAL e por conta alvo
     * (HMAC da organizacao + e-mail, o mesmo de R1) tem de estar abaixo de {@code r13-failures} em {@code r13-window}.
     * Esgotado -> 429; Redis fora -> 503 (falha fechada).
     */
    public void linkBeforeProof(String globalIdentity, String targetAccount) {
        failClosed(() -> {
            if (redis.count("nk:rl:r13i:" + globalIdentity, cfg.r13Window()) >= cfg.r13Failures()
                    || redis.count("nk:rl:r13c:" + targetAccount, cfg.r13Window()) >= cfg.r13Failures()) {
                LOG.info("evento=identidade.rate_limited regra=R13 identidade={}", globalIdentity);
                throw rateLimited(cfg.r13Window().toMillis());
            }
            return null;
        });
    }

    /** R13: falha da prova da conta antiga (qualquer causa: a resposta e uma so), por identidade e por conta alvo. */
    public void linkFailure(String globalIdentity, String targetAccount) {
        failClosed(() -> {
            redis.recordFailure("nk:rl:r13i:" + globalIdentity, cfg.r13Window());
            redis.recordFailure("nk:rl:r13c:" + targetAccount, cfg.r13Window());
            return null;
        });
    }

    // ------------------------------------------------------------------ entrada geral (Missao 1, desenho secao 7)
    // Chaves pela identidade GLOBAL (IdentityHmac.global, nunca o e-mail): R8 com os valores de R1/R2 e R8m com os de R4.

    /** Antes de verificar senha ou codigo na entrada geral: R8 (identidade+IP), atraso R8 e, no MFA, atraso R8m. */
    public void identityBeforeAuthentication(String globalIdentity, String ip, boolean mfa) {
        failClosed(() -> {
            if (redis.count(r8(globalIdentity, ip), cfg.r1Window()) >= cfg.r1Failures()) {
                LOG.info("evento=identidade.rate_limited regra=R8 identidade={} ip={}", globalIdentity, ClientIpResolver.truncated(ip));
                throw rateLimited(cfg.r1Window().toMillis());
            }
            long wait = Math.max(redis.remainingMs("nk:rl:r8nb:" + globalIdentity), mfa ? redis.remainingMs("nk:rl:r8mnb:" + globalIdentity) : 0);
            if (wait > 0) {
                LOG.info("evento=identidade.rate_limited regra={} identidade={} ip={}", mfa ? "R8/R8m" : "R8", globalIdentity, ClientIpResolver.truncated(ip));
                throw rateLimited(wait);
            }
            return null;
        });
    }

    /** Senha errada na entrada geral: R8 (identidade+IP) e atraso progressivo (valores de R2). */
    public void identityPasswordFailure(String globalIdentity, String ip) {
        failClosed(() -> {
            redis.recordFailure(r8(globalIdentity, ip), cfg.r1Window());
            long n = redis.recordFailure("nk:rl:r8:" + globalIdentity, cfg.r2Window());
            identityDelay("nk:rl:r8nb:" + globalIdentity, n, cfg.r2Threshold(), cfg.r2Window());
            return null;
        });
    }

    /** Codigo de MFA errado na entrada geral: R8 (identidade+IP) e R8m (valores de R4). */
    public void identityMfaFailure(String globalIdentity, String ip) {
        failClosed(() -> {
            redis.recordFailure(r8(globalIdentity, ip), cfg.r1Window());
            long n = redis.recordFailure("nk:rl:r8m:" + globalIdentity, cfg.r4Window());
            identityDelay("nk:rl:r8mnb:" + globalIdentity, n, cfg.r4Failures() + 1, cfg.r4Window());
            return null;
        });
    }

    /** Entrada geral COMPLETA (sessao de identidade criada): zera os contadores da identidade. */
    public void identityFullLogin(String globalIdentity, String ip) {
        try {
            redis.deleteKeys(r8(globalIdentity, ip), "nk:rl:r8:" + globalIdentity, "nk:rl:r8nb:" + globalIdentity,
                    "nk:rl:r8m:" + globalIdentity, "nk:rl:r8mnb:" + globalIdentity);
        } catch (LimiterUnavailable e) {
            LOG.warn("evento=limitador.indisponivel operacao=zerar_contadores_identidade");
        }
    }

    private void identityDelay(String key, long failures, int threshold, Duration window) {
        if (failures >= threshold) {
            long steps = Math.min(20, failures - threshold);
            long delay = Math.min(cfg.r2MaxDelay().toMillis(), cfg.r2InitialDelay().toMillis() << steps);
            redis.notBefore(key, Duration.ofMillis(delay), window);
        }
    }

    private static String r8(String globalIdentity, String ip) {
        return "nk:rl:r8i:" + globalIdentity + ":" + ip;
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
