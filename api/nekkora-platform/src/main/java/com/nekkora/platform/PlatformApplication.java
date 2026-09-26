package com.nekkora.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.nekkora.platform.config.PlatformProperties;

/** Processo da Platform (papel de banco nekkora_platform). Spring MVC bloqueante. */
@SpringBootApplication(scanBasePackages = {"com.nekkora.platform", "com.nekkora.core.web"})
@EnableConfigurationProperties(PlatformProperties.class)
public class PlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
