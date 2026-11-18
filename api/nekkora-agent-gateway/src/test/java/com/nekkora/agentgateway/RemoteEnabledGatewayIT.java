package com.nekkora.agentgateway;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.testsupport.RealRedis;

/**
 * Base dos testes do gateway com a execucao remota LIGADA (PLATFORM-CLIENT-EXECUCAO-REMOTA-001). As classes que a estendem
 * usam o MESMO contexto do Spring (uma aplicacao, um conjunto de conexoes): cada classe com configuracao propria subiria
 * outro contexto, que fica vivo ate o fim da JVM de teste e esgota as conexoes do PostgreSQL de teste.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = AgentGatewayApplication.class)
abstract class RemoteEnabledGatewayIT {

    static final RealDatabase DB = RealDatabase.shared();

    /** A pasta dos pacotes publicados (a mesma que a Platform usaria), so deste contexto de teste. */
    static final java.nio.file.Path PACKAGES = packages();

    private static java.nio.file.Path packages() {
        try {
            return java.nio.file.Files.createTempDirectory("gateway-pacotes");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry r) {
        RealRedis redis = RealRedis.shared();
        r.add("nekkora.database.url", DB::url);
        r.add("nekkora.database.user", () -> "nekkora_ingestion");
        r.add("nekkora.database.password", () -> DB.password("nekkora_ingestion"));
        r.add("spring.data.redis.host", redis::host);
        r.add("spring.data.redis.port", redis::port);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.limits.batches-per-minute", () -> "100000");
        r.add("nekkora.limits.redeem-failures-per-hour", () -> "1000");
        r.add("nekkora.remote-execution.enabled", () -> "true");
        r.add("nekkora.artefatos.dir", PACKAGES::toString);
    }
}
