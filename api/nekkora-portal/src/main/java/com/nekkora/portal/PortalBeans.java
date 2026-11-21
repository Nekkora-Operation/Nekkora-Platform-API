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

    /**
     * 070: area do cliente. Os resultados das execucoes remotas sao so LIDOS (quem grava e o canal da maquina): a mesma pasta da
     * Platform ({@code NEKKORA_RESULTADOS_DIR}); em branco, o download responde 503 e o resto funciona.
     */
    @Bean
    PortalClientService portalClientService(OrgTransactions tx, JdbcTemplate jdbc, org.springframework.core.env.Environment env)
            throws java.io.IOException {
        return new PortalClientService(tx, jdbc, com.nekkora.core.artifact.ResultStore.at(
                env.getProperty("NEKKORA_RESULTADOS_DIR", env.getProperty("nekkora.resultados.dir", ""))));
    }
}
