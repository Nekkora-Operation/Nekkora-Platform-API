package com.nekkora.core.db;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unico caminho de acesso a dados tenant-bound (§6 do relatorio da API; C5).
 *
 * <p>Cada chamada abre UMA transacao READ COMMITTED e, como primeiro comando, define o contexto com
 * {@code SELECT set_config('nekkora.organization_id', ?, true)} - valor por bind, efeito so na transacao.
 * O trabalho recebe um {@link JdbcClient} que usa a MESMA conexao (a transacao do Spring a vincula a
 * thread). Nada de @Async/CompletableFuture/reativo aqui dentro: fora da thread, o trabalho nao teria a
 * transacao nem o contexto (e a RLS devolveria zero linhas - falha fechada).
 */
public final class OrgTransactions {

    public enum Mode { READ_ONLY, READ_WRITE }

    private final TransactionTemplate readOnlyTx;
    private final TransactionTemplate readWriteTx;
    private final JdbcClient jdbc;

    public OrgTransactions(PlatformTransactionManager tm, JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.readOnlyTx = template(tm, true);
        this.readWriteTx = template(tm, false);
    }

    private static TransactionTemplate template(PlatformTransactionManager tm, boolean readOnly) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setReadOnly(readOnly);
        return t;
    }

    /** Executa {@code trabalho} numa transacao nova com o contexto da organizacao informada. */
    public <T> T inOrganization(UUID organization, Mode mode, Function<JdbcClient, T> work) {
        Objects.requireNonNull(organization, "organizacao");
        TransactionTemplate t = mode == Mode.READ_ONLY ? readOnlyTx : readWriteTx;
        return t.execute(status -> {
            jdbc.sql("SELECT set_config('nekkora.organization_id', ?, true)").param(organization.toString()).query(String.class).single();
            return work.apply(jdbc);
        });
    }

    /** Transacao de escrita SEM contexto de organizacao (portal: a funcao da 013 define o proprio contexto). */
    public <T> T withoutContext(Function<JdbcClient, T> work) {
        return readWriteTx.execute(status -> work.apply(jdbc));
    }
}
