package com.nekkora.core.testsupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL 16 REAL para testes de integracao: container descartavel (Testcontainers, nome e porta
 * aleatorios; nunca a stack principal), migrations 001-NNN do repositorio aplicadas pelo entrypoint numa base
 * vazia, e senhas ALEATORIAS por execucao para os papeis LOGIN reais (nenhuma credencial versionada). Os testes
 * conectam COMO os papeis reais (nekkora_platform, nekkora_portal, ...), nunca como superusuario, exceto na
 * preparacao de fixture e nas verificacoes declaradas.
 */
public final class RealDatabase {

    public static final String IMAGE = "postgres:16-alpine";
    private static final List<String> ROLES = List.of("nekkora_platform", "nekkora_portal", "nekkora_provisioning", "nekkora_ingestion");

    private static RealDatabase instance;

    private final GenericContainer<?> pg;
    private final String superuserPassword;
    private final Map<String, String> passwords = new HashMap<>();

    private RealDatabase(Path migrations) throws Exception {
        superuserPassword = randomPassword();
        GenericContainer<?> c = new GenericContainer<>(IMAGE)
                .withEnv("POSTGRES_DB", "nekkora").withEnv("POSTGRES_USER", "nekkora").withEnv("POSTGRES_PASSWORD", superuserPassword)
                .withCommand("postgres", "-c", "cluster_name=nekkora-teste-java")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        try (Stream<Path> files = Files.list(migrations)) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("^\\d{3}_.*\\.sql$")).sorted().toList()) {
                c = c.withCopyFileToContainer(MountableFile.forHostPath(f), "/docker-entrypoint-initdb.d/" + f.getFileName());
            }
        }
        pg = c;
        pg.start();
        try (Connection con = connectAsSuperuser(); Statement st = con.createStatement()) {
            for (String role : ROLES) {
                String s = randomPassword();
                passwords.put(role, s);
                st.execute("ALTER ROLE " + role + " PASSWORD '" + s + "'");
            }
        }
    }

    /** Instancia unica por JVM de teste (as migrations levam alguns segundos). */
    public static synchronized RealDatabase shared() {
        if (instance == null) {
            try {
                instance = new RealDatabase(repositoryRoot().resolve("db/migrations"));
            } catch (Exception e) {
                throw new IllegalStateException("nao foi possivel subir o PostgreSQL de teste", e);
            }
        }
        return instance;
    }

    public static Path repositoryRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("db/migrations"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("raiz do repositorio (db/migrations) nao encontrada");
        }
        return p;
    }

    public String url() {
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/nekkora";
    }

    public String password(String role) {
        String s = passwords.get(role);
        if (s == null) {
            throw new IllegalArgumentException("papel sem senha de teste: " + role);
        }
        return s;
    }

    public Connection connectAsSuperuser() throws SQLException {
        return DriverManager.getConnection(url(), "nekkora", superuserPassword);
    }

    public Connection connectAs(String role) throws SQLException {
        return DriverManager.getConnection(url(), role, password(role));
    }

    /** Executa SQL como superusuario (so preparacao de fixture e verificacoes declaradas). */
    public void asSuperuser(String... sqls) {
        try (Connection con = connectAsSuperuser(); Statement st = con.createStatement()) {
            for (String sql : sqls) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("fixture falhou (sqlstate " + e.getSQLState() + ")", e);
        }
    }

    /** Linhas do log do servidor (stderr do container) - para as verificacoes de token fora do log. */
    public String serverLog() {
        return pg.getLogs();
    }

    private static String randomPassword() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
