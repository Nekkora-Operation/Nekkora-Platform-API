package com.nekkora.portal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Processo do portal externo (papel de banco nekkora_portal, D1/D4 da 8.2). So GET; so as 4 funcoes publicas da
 * 013; nunca le credenciais nem cria/revoga sessoes; nao tem as credenciais do nekkora_platform.
 */
@SpringBootApplication(scanBasePackages = {"com.nekkora.portal", "com.nekkora.core.web"})
@EnableConfigurationProperties(PortalProperties.class)
public class PortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortalApplication.class, args);
    }
}
