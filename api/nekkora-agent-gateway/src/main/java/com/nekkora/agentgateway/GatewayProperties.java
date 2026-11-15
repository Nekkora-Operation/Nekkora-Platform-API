package com.nekkora.agentgateway;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nekkora")
public record GatewayProperties(List<String> trustedProxies, Database database, Limits limits, RemoteExecution remoteExecution) {

    /**
     * PLATFORM-CLIENT-EXECUCAO-REMOTA-001: chave do recurso (NEKKORA_EXECUCAO_REMOTA; ausente = desligado). Desligado, nenhuma
     * maquina e habilitada para execucao remota; o resto do canal nao muda.
     */
    public record RemoteExecution(boolean enabled, Integer renewalWindowDays, Integer leaseSeconds) {
    }

    /** T02: prazo da comunicacao de uma tentativa, renovado a cada escrita aceita (NEKKORA_EXECUCAO_REMOTA_LEASE_SEGUNDOS; padrao 90). */
    public int leaseSeconds() {
        return remoteExecution == null || remoteExecution.leaseSeconds() == null ? 90 : remoteExecution.leaseSeconds();
    }

    /**
     * T01b: a maquina habilitada so renova a propria credencial quando faltarem ate estes dias para o vencimento
     * (NEKKORA_EXECUCAO_REMOTA_RENOVACAO_DIAS; padrao 30; de 1 ao prazo da credencial).
     */
    public int renewalWindowDays() {
        int days = remoteExecution == null || remoteExecution.renewalWindowDays() == null ? 30 : remoteExecution.renewalWindowDays();
        return Math.max(1, Math.min(days, limits.credentialDays()));
    }

    public boolean remoteExecutionEnabled() {
        return remoteExecution != null && remoteExecution.enabled();
    }

    public record Database(String url, String user, String password, int maxPoolSize) {
    }

    /**
     * Limites do canal (contrato, sec. 4.2 e 8). {@code batchesPerMinute}: lotes de eventos por organizacao;
     * {@code redeemFailuresPerHour}: resgates recusados por IP de origem; {@code credentialDays}: validade da credencial;
     * {@code catalogReadsPerMinute}: leituras do catalogo por Agent (PLATFORM-AGENT-CATALOGO-001; ausente = 60).
     */
    public record Limits(int batchesPerMinute, int redeemFailuresPerHour, int credentialDays, int catalogReadsPerMinute) {
        public Limits {
            if (catalogReadsPerMinute <= 0) {
                catalogReadsPerMinute = 60;
            }
        }
    }
}
