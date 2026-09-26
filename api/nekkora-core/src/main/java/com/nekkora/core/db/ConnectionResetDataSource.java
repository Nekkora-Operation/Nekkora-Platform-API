package com.nekkora.core.db;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Reset explicito da conexao ao devolve-la ao pool (C5 do relatorio operacional; §3.2 do relatorio da API).
 *
 * <p>O HikariCP nao executa SQL na devolucao. Este wrapper intercepta {@link Connection#close()} e, antes de
 * devolver a conexao, executa {@code DISCARD ALL} FORA de qualquer transacao (o Spring ja restaurou o
 * autocommit ao encerrar a transacao; se nao, faz rollback antes). {@code DISCARD ALL} desfaz GUCs de sessao,
 * prepared statements do servidor, tabelas temporarias, locks consultivos e LISTEN. O pgJDBC reconhece o
 * status de comando {@code DISCARD ALL} e invalida o proprio cache de prepared statements, entao consultas
 * seguintes voltam a prepara-los (provado em teste com prepareThreshold baixo).
 *
 * <p>Se o reset falhar, a conexao e ABORTADA (o Hikari a descarta do pool) em vez de voltar suja.
 */
public final class ConnectionResetDataSource extends DelegatingDataSource {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionResetDataSource.class);

    private final AtomicLong resets = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();

    public ConnectionResetDataSource(DataSource target) {
        super(target);
    }

    /** Quantidade de DISCARD ALL executados com sucesso (observabilidade e testes). */
    public long resets() {
        return resets.get();
    }

    /** Conexoes descartadas porque o reset falhou. */
    public long discarded() {
        return discarded.get();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrapConnection(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        throw new SQLException("credenciais por chamada nao sao suportadas", "0A000");
    }

    private Connection wrapConnection(Connection real) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                new Reset(real));
    }

    private final class Reset implements InvocationHandler {
        private final Connection real;
        private boolean closed;

        Reset(Connection real) {
            this.real = real;
        }

        @Override
        public Object invoke(Object proxy, Method invokedMethod, Object[] args) throws Throwable {
            switch (invokedMethod.getName()) {
                case "close":
                    discardConnection();
                    return null;
                case "isClosed":
                    return closed || real.isClosed();
                case "unwrap":
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return proxy;
                    }
                    break;
                case "isWrapperFor":
                    if (((Class<?>) args[0]).isInstance(proxy)) {
                        return true;
                    }
                    break;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                default:
                    break;
            }
            if (closed) {
                throw new SQLException("conexao ja devolvida ao pool", "08003");
            }
            try {
                return invokedMethod.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void discardConnection() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (!real.isClosed()) {
                    if (!real.getAutoCommit()) {
                        real.rollback();
                        real.setAutoCommit(true);
                    }
                    try (Statement st = real.createStatement()) {
                        st.execute("DISCARD ALL");
                    }
                    resets.incrementAndGet();
                }
            } catch (SQLException e) {
                discarded.incrementAndGet();
                // so SQLSTATE: a mensagem do servidor pode conter valores (C10)
                LOG.warn("evento=pool.reset_falhou sqlstate={} acao=conexao_abortada", e.getSQLState());
                try {
                    real.abort(Runnable::run);
                } catch (SQLException ignored) {
                    // a conexao ja esta perdida; o pool a descarta ao fechar
                }
            } finally {
                real.close();
            }
        }
    }
}
