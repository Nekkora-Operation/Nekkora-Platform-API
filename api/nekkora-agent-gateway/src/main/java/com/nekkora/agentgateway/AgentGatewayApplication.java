package com.nekkora.agentgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Canal da maquina (contrato Platform-Agent 1.0, docs/relatorios/2026-10-28-platform-agent-contrato.md): papel de banco
 * {@code nekkora_ingestion}, credencial de maquina propria (nunca cookie, sessao ou CSRF), processo e datasource separados
 * da Platform. Recebe FATOS (registro, relatorios do supervisor, eventos); nunca pede reexecucao.
 */
@SpringBootApplication(scanBasePackages = {"com.nekkora.agentgateway", "com.nekkora.core.web"})
@EnableConfigurationProperties(GatewayProperties.class)
public class AgentGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentGatewayApplication.class, args);
    }
}
