package com.nekkora.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.nekkora.core.testsupport.RealDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Pool real (HikariCP, UMA conexao, para garantir reuso da mesma conexao fisica) como o papel real
 * nekkora_platform, pgJDBC com prepareThreshold=1 (prepared statements do SERVIDOR ja na primeira execucao).
 * Prova: (1) DISCARD ALL roda na devolucao; (2) apaga GUC de sessao, tabela temporaria e prepared statements do
 * servidor; (3) o pgJDBC continua funcionando depois (re-prepara); (4) o contexto nekkora.organization_id
 * definido por bind numa transacao nao passa para a seguinte; (5) sem contexto, nenhuma linha tenant-bound.
 */
class ConnectionResetIT {

    private static HikariDataSource hikari;
    private static ConnectionResetDataSource ds;
    private static final UUID ORG = UUID.fromString("70000000-0000-0000-0000-000000000007");

    @BeforeAll
    static void setUp() {
        RealDatabase db = RealDatabase.shared();
        db.asSuperuser("INSERT INTO organizations (organization_id, name, status) VALUES ('" + ORG + "', 'Org reset', 1) ON CONFLICT DO NOTHING");
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(db.url() + "?prepareThreshold=1");
        cfg.setUsername("nekkora_platform");
        cfg.setPassword(db.password("nekkora_platform"));
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        hikari = new HikariDataSource(cfg);
        ds = new ConnectionResetDataSource(hikari);
    }

    @AfterAll
    static void tearDown() {
        hikari.close();
    }

    @Test
    void discardAllOnReturnClearsStateAndPreparedStatementsRecover() throws Exception {
        String pid1;
        long before = ds.resets();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(user(c)).isEqualTo("nekkora_platform");
            pid1 = pid(c);
            st.execute("SET nekkora.lixo = 'sessao-anterior'");
            st.execute("CREATE TEMP TABLE resto (a int)");
            for (int i = 0; i < 5; i++) {
                assertThat(preparedQuery(c)).isEqualTo(1);
            }
            assertThat(preparedOnServer(c)).as("SELECT $1::int preparado no servidor pelo pgJDBC").isTrue();
        }
        assertThat(ds.resets()).isEqualTo(before + 1);

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            assertThat(pid(c)).as("mesma conexao fisica reutilizada").isEqualTo(pid1);
            try (ResultSet rs = st.executeQuery("SELECT coalesce(current_setting('nekkora.lixo', true), '')")) {
                rs.next();
                assertThat(rs.getString(1)).as("GUC de sessao apagado pelo DISCARD ALL").isEmpty();
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_class WHERE relname = 'resto' AND relpersistence = 't'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("tabela temporaria apagada").isZero();
            }
            assertThat(preparedOnServer(c)).as("prepared statement do servidor desalocado pelo DISCARD ALL").isFalse();
            for (int i = 0; i < 5; i++) {
                assertThat(preparedQuery(c)).as("pgJDBC re-prepara depois do DISCARD ALL").isEqualTo(1);
            }
            assertThat(preparedOnServer(c)).as("re-preparado depois do reset").isTrue();
        }
    }

    @Test
    void bindContextDoesNotLeakToNextTransaction() throws Exception {
        OrgTransactions tx = new OrgTransactions(new DataSourceTransactionManager(ds), JdbcClient.create(ds));
        Integer visible = tx.inOrganization(ORG, OrgTransactions.Mode.READ_ONLY,
                j -> j.sql("SELECT count(*) FROM organizations").query(Integer.class).single());
        assertThat(visible).as("com contexto: a propria organizacao").isEqualTo(1);

        String after = tx.withoutContext(j -> j.sql("SELECT coalesce(current_setting('nekkora.organization_id', true), '')").query(String.class).single());
        assertThat(after).as("contexto nao vaza para a transacao seguinte na mesma conexao").isEmpty();
        Integer withoutContext = tx.withoutContext(j -> j.sql("SELECT count(*) FROM organizations").query(Integer.class).single());
        assertThat(withoutContext).as("sem contexto: RLS falha fechado").isZero();

    }

    @Test
    void readOnlyTransactionDoesNotWrite() {
        OrgTransactions tx = new OrgTransactions(new DataSourceTransactionManager(ds), JdbcClient.create(ds));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tx.inOrganization(ORG, OrgTransactions.Mode.READ_ONLY,
                j -> j.sql("UPDATE organizations SET name = name WHERE organization_id = ?").param(ORG).update()))
                .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class)
                .satisfies(e -> assertThat(DbErrors.sqlState(e)).isEqualTo("25006"));
    }

    private static int preparedQuery(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT ?::int")) {
            ps.setInt(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean preparedOnServer(Connection c) throws Exception {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_prepared_statements WHERE statement = 'SELECT $1::int'")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private static String user(Connection c) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT current_user")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String pid(Connection c) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
