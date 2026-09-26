package com.nekkora.core.db;

import java.sql.SQLException;

/** Extrai so o SQLSTATE de uma falha de banco (a mensagem do servidor nunca vai para log nem resposta - C10). */
public final class DbErrors {

    private DbErrors() {
    }

    /** SQLSTATE da primeira {@link SQLException} na cadeia de causas, ou {@code null}. */
    public static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException s && s.getSQLState() != null) {
                return s.getSQLState();
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }
}
