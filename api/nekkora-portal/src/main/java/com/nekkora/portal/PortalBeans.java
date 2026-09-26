package com.nekkora.portal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.nekkora.core.db.OrgTransactions;

@Configuration
public class PortalBeans {

    @Bean
    PortalService portalService(OrgTransactions tx, JdbcTemplate jdbc) {
        return new PortalService(tx, jdbc);
    }
}
