package com.nekkora.core.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Redis 7 real e descartavel para os testes (limitador distribuido e CSRF pre-autenticacao). */
public final class RealRedis {

    private static RealRedis shared;
    private final GenericContainer<?> redis;

    private RealRedis() {
        redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379).waitingFor(Wait.forListeningPort());
        redis.start();
    }

    /** Instancia compartilhada pela JVM de teste. */
    public static synchronized RealRedis shared() {
        if (shared == null) {
            shared = new RealRedis();
        }
        return shared;
    }

    /** Instancia exclusiva (testes que derrubam o Redis). */
    public static RealRedis exclusive() {
        return new RealRedis();
    }

    public String host() {
        return redis.getHost();
    }

    public int port() {
        return redis.getMappedPort(6379);
    }

    public void shutDown() {
        redis.stop();
    }
}
