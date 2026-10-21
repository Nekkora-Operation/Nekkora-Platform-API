package com.nekkora.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.nekkora.core.testsupport.RealDatabase;

/**
 * {@link DbErrors#raisedRefusal} contra mensagens REAIS do servidor (PostgreSQL de teste, so objetos temporarios da
 * conexao): RAISE com 23514 numa funcao e recusa de regra; CHECK violada, com o mesmo SQLSTATE, nao e.
 */
class DbErrorsIT {

    static final RealDatabase DB = RealDatabase.shared();

    private static Throwable falha(String... sqls) throws Exception {
        try (Connection c = DB.connectAsSuperuser(); Statement st = c.createStatement()) {
            return catchThrowable(() -> {
                for (String s : sqls) {
                    st.execute(s);
                }
            });
        }
    }

    @Test
    void raiseComErrcode23514EmFuncaoERecusaDeRegra() throws Exception {
        Throwable t = falha("CREATE FUNCTION pg_temp.recusa() RETURNS void LANGUAGE plpgsql AS $$ BEGIN"
                + " RAISE EXCEPTION 'recusa de regra' USING ERRCODE = '23514'; END $$", "SELECT pg_temp.recusa()");
        assertThat(DbErrors.sqlState(t)).isEqualTo("23514");
        assertThat(DbErrors.raisedRefusal(t)).isTrue();
        assertThat(DbErrors.raisedRefusal(new RuntimeException("envolvida", t))).as("na cadeia de causas").isTrue();
    }

    @Test
    void checkVioladaTem23514MasNaoERecusaDeRegra() throws Exception {
        Throwable t = falha("CREATE TEMP TABLE ordem (criado timestamptz NOT NULL, encerrado timestamptz, CHECK (encerrado IS NULL OR encerrado >= criado))",
                "INSERT INTO ordem VALUES (clock_timestamp() + interval '1 minute', clock_timestamp())");
        assertThat(DbErrors.sqlState(t)).isEqualTo("23514");
        assertThat(DbErrors.raisedRefusal(t)).isFalse();
    }

    @Test
    void outroSqlstateOuSemFalhaDeBancoNaoERecusa() throws Exception {
        assertThat(DbErrors.raisedRefusal(falha("SELECT 1/0"))).isFalse();
        assertThat(DbErrors.raisedRefusal(new IllegalStateException("sem banco"))).isFalse();
        assertThat(DbErrors.raisedRefusal(null)).isFalse();
    }
}
