package com.nekkora.core.log;

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;

/**
 * Substitui a impressao padrao de excecoes do Logback: so a CLASSE da excecao e das causas, e o SQLSTATE quando
 * houver - nunca a mensagem nem o stack trace (uma mensagem do PostgreSQL ou do pgJDBC pode conter valores de
 * bind, e um framework pode registrar excecoes por conta propria).
 */
public class ExceptionClassConverter extends ThrowableHandlingConverter {

    @Override
    public String convert(ILoggingEvent event) {
        IThrowableProxy t = event.getThrowableProxy();
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" excecao=");
        for (int i = 0; t != null && i < 8; t = t.getCause(), i++) {
            if (i > 0) {
                sb.append("<-");
            }
            sb.append(t.getClassName());
        }
        return sb.toString();
    }
}
