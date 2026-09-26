package com.nekkora.portal;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nekkora")
public record PortalProperties(List<String> trustedProxies, Database database, Limits limits) {

    public record Database(String url, String user, String password, int maxPoolSize) {
    }

    /** R7 do portal (configuracao inicial de teste; contrato 8.6, §2.3). */
    public record Limits(int r7Capacity, double r7PerSecond) {
    }
}
