package com.nekkora.platform;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Falha injetada SO em teste: armada, a proxima transacao de ESCRITA faz o commit REAL no banco e, em seguida, a
 * aplicacao recebe uma falha, como se a confirmacao se perdesse na rede depois de o servidor confirmar. Troca o
 * {@code transactionManager} por uma subclasse do mesmo {@link DataSourceTransactionManager}, sobre o mesmo DataSource;
 * desarmada, se comporta igual. Transacoes somente leitura (a resolucao da sessao no filtro) nao sao afetadas.
 */
@TestConfiguration
class ConfirmacaoPerdida {

    static final AtomicBoolean ARMADA = new AtomicBoolean();
    /** Commits reais seguidos de falha injetada (a prova de que o commit aconteceu antes da falha). */
    static final AtomicInteger INJETADAS = new AtomicInteger();

    @Bean
    static BeanPostProcessor confirmacaoPerdidaNoCommit() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if ("transactionManager".equals(name) && bean.getClass() == DataSourceTransactionManager.class) {
                    return new Gerenciador(((DataSourceTransactionManager) bean).getDataSource());
                }
                return bean;
            }
        };
    }

    static final class Gerenciador extends DataSourceTransactionManager {

        Gerenciador(DataSource ds) {
            super(ds);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            if (!status.isReadOnly() && ARMADA.compareAndSet(true, false)) {
                INJETADAS.incrementAndGet();
                throw new TransactionSystemException("confirmacao do commit perdida (falha injetada no teste, depois do commit real)");
            }
        }
    }
}
