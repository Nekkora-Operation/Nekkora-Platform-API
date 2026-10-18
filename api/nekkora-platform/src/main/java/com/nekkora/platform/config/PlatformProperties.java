package com.nekkora.platform.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao da Platform. Valores numericos de limites e Argon2 sao a CONFIGURACAO INICIAL DE TESTE aprovada
 * (contrato 8.6/8.10, revisao 4), sujeita a calibracao. Segredos (senha do banco, chaves AES e HMAC) chegam por
 * variavel de ambiente / servico de segredos, nunca por arquivo versionado.
 */
@ConfigurationProperties(prefix = "nekkora")
public record PlatformProperties(
        String publicOrigin,
        List<String> trustedProxies,
        Database database,
        Argon2 argon2,
        Keys keys,
        Session session,
        Limits limits) {

    public record Database(String url, String user, String password, int maxPoolSize) {
    }

    public record Argon2(int memoryKib, int iterations, int parallelism, int maxConcurrent, int queue, long waitMs) {
    }

    /** Chaves em base64. aesPorId: "k1:<base64>,k0:<base64>". */
    public record Keys(String aesCurrent, String aesById, String hmacId, String hmac) {
    }

    public record Session(Duration absolute, Duration internalInactivity, Duration externalInactivity, int maxPerAccount,
                         Duration challenge, Duration passwordRecovery, Duration emailVerification, Duration stepUp) {
    }

    public record Limits(
            int r1Failures, Duration r1Window,
            int r2Threshold, Duration r2Window, Duration r2InitialDelay, Duration r2MaxDelay,
            int r4Failures, Duration r4Window,
            int r5PerIdentity, int r5PerIp, Duration r5Window,
            int r6Capacity, double r6PerSecond,
            int r7Capacity, double r7PerSecond,
            Duration recoveryResponseFloor,
            int r10Exchanges, Duration r10Window,
            int r11Creations, Duration r11Window,
            int r13Failures, Duration r13Window) {
    }
}
