package com.nekkora.core.db;

import java.sql.SQLException;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

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

    /**
     * Recusa de regra levantada por trigger ou funcao ({@code RAISE ... USING ERRCODE = '23514'}): SQLSTATE 23514 SEM nome
     * de restricao. Uma CHECK violada tambem e 23514, mas o servidor informa a restricao - isso nao e recusa de regra, e
     * falha a propagar (ex.: CHECK de ordem temporal com o relogio do banco recuado), nunca resposta de recusa.
     */
    public static boolean raisedRefusal(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PSQLException p && p.getSQLState() != null) {
                ServerErrorMessage m = p.getServerErrorMessage();
                return "23514".equals(p.getSQLState()) && m != null && m.getConstraint() == null;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}
