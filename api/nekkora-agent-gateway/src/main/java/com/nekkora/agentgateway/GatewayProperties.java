package com.nekkora.agentgateway;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nekkora")
public record GatewayProperties(List<String> trustedProxies, Database database, Limits limits) {

    public record Database(String url, String user, String password, int maxPoolSize) {
    }

    /**
     * Limites do canal (contrato, sec. 4.2 e 8). {@code batchesPerMinute}: lotes de eventos por organizacao;
     * {@code redeemFailuresPerHour}: resgates recusados por IP de origem; {@code credentialDays}: validade da credencial.
     */
    public record Limits(int batchesPerMinute, int redeemFailuresPerHour, int credentialDays) {
    }
}
