package com.nekkora.ops.testsupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

import com.nekkora.ops.ca.EmissorCertificados;
import com.nekkora.ops.cli.Credencial;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.cli.LogLocal;
import com.nekkora.ops.idp.VerificadorIdp;

/**
 * PostgreSQL 16 REAL e DESCARTAVEL para a recuperacao emergencial (016): TLS obrigatorio, papeis {@code op_*} so por
 * certificado de cliente da CA operacional de teste ({@code hostssl ... cert map=ops clientname=CN}), demais papeis
 * por senha aleatoria sobre TLS, sem conexao sem TLS. Migrations 001-NNN do repositorio aplicadas pelo entrypoint.
 *
 * <p>Fixtures (DECLARADAS, so preparacao): feitas como superusuario. Fixtures TEMPORAIS ({@code recuar*}) deslocam para
 * tras os instantes gravados, como superusuario e com {@code session_replication_role = replica} (sem triggers), SO
 * nesta base de teste: simulam a passagem do tempo sem encurtar os prazos do contrato e sem relogio configuravel.
 */
public final class OpsBanco {

    public static final String IMAGEM = "postgres:16-alpine";
    private static final List<String> SERVICOS = List.of("nekkora_platform", "nekkora_portal", "nekkora_provisioning", "nekkora_ops_ca",
            "nekkora_ops_intake");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static OpsBanco instancia;

    private final GenericContainer<?> pg;
    private final Path dir;
    private final Path raizServidor;
    private final String senhaSuper;
    private final Map<String, String> senhas = new ConcurrentHashMap<>();
    private final Pki.Ca caOps;
    private final IdpSimulado idp = new IdpSimulado();
    private final EmissorCertificados emissor;

    private Operador boot;
    private Credencial credBoot;
    private Instant credBootEm;

    public record Operador(String papel, UUID id) {
    }

    public record Org(UUID id, UUID admin, UUID adminMembership, UUID adminFator) {
    }

    private OpsBanco(Path migrations) throws Exception {
        dir = Files.createTempDirectory("nekkora-ops-banco-");
        senhaSuper = aleatoria();
        Pki.Ca caInfra = Pki.ca("Nekkora Infra CA Teste");
        caOps = Pki.ca("Nekkora Ops CA Teste");
        KeyPair chaveServidor = Pki.ec();
        String hba = String.join("\n",
                "local     all  all                     trust",
                "hostnossl all  all  0.0.0.0/0          reject",
                "hostnossl all  all  ::/0               reject",
                "hostssl   all  /^op_  0.0.0.0/0  cert map=ops clientname=CN",
                "hostssl   all  /^op_  ::/0       cert map=ops clientname=CN",
                "hostssl   all  all  0.0.0.0/0          scram-sha-256",
                "hostssl   all  all  ::/0               scram-sha-256", "");
        // regex entre aspas: a virgula de {2,40} separaria os campos do pg_ident
        String ident = "ops   \"/^(op_[a-z0-9_]{2,40})$\"   \\1\n";
        GenericContainer<?> c = new GenericContainer<>(IMAGEM)
                .withEnv("POSTGRES_DB", "nekkora").withEnv("POSTGRES_USER", "nekkora").withEnv("POSTGRES_PASSWORD", senhaSuper)
                .withExposedPorts(5432)
                .withCopyToContainer(Transferable.of(hba), "/etc/nekkora/pg_hba.conf")
                .withCopyToContainer(Transferable.of(ident), "/etc/nekkora/pg_ident.conf")
                .withCopyToContainer(Transferable.of(Pki.pem(caOps.certificado())), "/tls-src/ops-ca.crt")
                .withCopyToContainer(Transferable.of(Pki.pemChave(chaveServidor.getPrivate())), "/tls-src/server.key")
                .withCommand("sh", "-c", "mkdir -p /var/lib/postgresql/tls && cp /tls-src/* /var/lib/postgresql/tls/"
                        + " && chown -R postgres:postgres /var/lib/postgresql/tls && chmod 600 /var/lib/postgresql/tls/server.key"
                        + " && exec docker-entrypoint.sh postgres -c cluster_name=nekkora-ops-teste -c ssl=on"
                        + " -c ssl_cert_file=/var/lib/postgresql/tls/server.crt -c ssl_key_file=/var/lib/postgresql/tls/server.key"
                        + " -c ssl_ca_file=/var/lib/postgresql/tls/ops-ca.crt -c hba_file=/etc/nekkora/pg_hba.conf"
                        + " -c ident_file=/etc/nekkora/pg_ident.conf -c log_connections=on")
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        try (Stream<Path> files = Files.list(migrations)) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("^\\d{3}_.*\\.sql$")).sorted().toList()) {
                c = c.withCopyFileToContainer(MountableFile.forHostPath(f), "/docker-entrypoint-initdb.d/" + f.getFileName());
            }
        }
        // o certificado do servidor depende do host que o Testcontainers expoe: SAN com os nomes usuais do host local
        c = c.withCopyToContainer(Transferable.of(Pki.pem(Pki.servidor(caInfra, chaveServidor.getPublic(),
                List.of("localhost", "127.0.0.1", "::1", "host.docker.internal")))), "/tls-src/server.crt");
        pg = c;
        pg.start();
        raizServidor = Pki.escrever(dir, "raiz-servidor.pem", Pki.pem(caInfra.certificado()));
        try (Connection con = superusuario(); Statement st = con.createStatement()) {
            for (String papel : SERVICOS) {
                String s = aleatoria();
                senhas.put(papel, s);
                st.execute("ALTER ROLE " + papel + " PASSWORD '" + s + "'");
            }
        }
        VerificadorIdp verificador = new VerificadorIdp(IdpSimulado.EMISSOR, IdpSimulado.AUDIENCIA, idp.chavePublica(), Clock.systemUTC());
        emissor = new EmissorCertificados(caOps.chaves().getPrivate(), caOps.certificado(), verificador,
                () -> comoServico("nekkora_ops_ca"), Clock.systemUTC());
    }

    public static synchronized OpsBanco compartilhado() {
        if (instancia == null) {
            try {
                instancia = new OpsBanco(raizDoRepositorio().resolve("db/migrations"));
            } catch (Exception e) {
                throw new IllegalStateException("nao foi possivel subir o PostgreSQL com TLS de teste", e);
            }
        }
        return instancia;
    }

    public static Path raizDoRepositorio() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("db/migrations"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("raiz do repositorio (db/migrations) nao encontrada");
        }
        return p;
    }

    // ------------------------------------------------------------------ conexoes

    public String host() {
        return pg.getHost();
    }

    public int porta() {
        return pg.getMappedPort(5432);
    }

    public String url() {
        return url(host(), porta());
    }

    public static String url(String host, int porta) {
        return "jdbc:postgresql://" + host + ":" + porta + "/nekkora";
    }

    /** URL com TLS verificado (para a Platform: nekkora.database.url). */
    public String urlTls() {
        return url() + "?sslmode=verify-full&sslrootcert=" + raizServidor.toString().replace('\\', '/');
    }

    public Path raizServidor() {
        return raizServidor;
    }

    public Path diretorio() {
        return dir;
    }

    public Properties tls(String usuario, String senha) {
        Properties p = new Properties();
        p.setProperty("user", usuario);
        if (senha != null) {
            p.setProperty("password", senha);
        }
        p.setProperty("sslmode", "verify-full");
        p.setProperty("sslrootcert", raizServidor.toString());
        return p;
    }

    public Connection superusuario() throws SQLException {
        return DriverManager.getConnection(url(), tls("nekkora", senhaSuper));
    }

    public Connection comoServico(String papel) throws SQLException {
        return DriverManager.getConnection(url(), tls(papel, senha(papel)));
    }

    public String senha(String papel) {
        String s = senhas.get(papel);
        if (s == null) {
            throw new IllegalArgumentException("papel sem senha de teste: " + papel);
        }
        return s;
    }

    public Connection conectar(Credencial c) throws SQLException {
        return DriverManager.getConnection(url(), c.propriedades(raizServidor));
    }

    public String logDoServidor() {
        return pg.getLogs();
    }

    // ------------------------------------------------------------------ SQL como superusuario (fixture declarada)

    public void sql(String... comandos) {
        try (Connection con = superusuario(); Statement st = con.createStatement()) {
            for (String s : comandos) {
                st.execute(s);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("fixture falhou (sqlstate " + e.getSQLState() + "): " + e.getMessage(), e);
        }
    }

    public <T> T valor(Class<T> tipo, String consulta, Object... params) {
        try (Connection con = superusuario(); PreparedStatement ps = con.prepareStatement(consulta)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, tipo) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("consulta falhou (sqlstate " + e.getSQLState() + "): " + e.getMessage(), e);
        }
    }

    public List<List<Object>> linhas(String consulta, Object... params) {
        try (Connection con = superusuario(); PreparedStatement ps = con.prepareStatement(consulta)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            List<List<Object>> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<Object> l = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        l.add(rs.getObject(i));
                    }
                    out.add(l);
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("consulta falhou (sqlstate " + e.getSQLState() + "): " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ operadores e credenciais

    /** Credencial nova: token recente do IdP simulado, chave gerada localmente, certificado da CA (registrado). */
    public Credencial credencial(Operador o) {
        return credencial(o.papel());
    }

    public Credencial credencial(String papel) {
        KeyPair par = Credencial.novoParDeChaves();
        try {
            return Credencial.de(papel, par, emissor.emitir(idp.token(papel), par.getPublic()), caOps.certificado());
        } catch (EmissorCertificados.EmissaoNegada e) {
            throw new IllegalStateException("emissao negada: " + e.motivo());
        }
    }

    public EmissorCertificados emissor() {
        return emissor;
    }

    public IdpSimulado idp() {
        return idp;
    }

    public Pki.Ca caOps() {
        return caOps;
    }

    /** CLI do operador com credencial propria (renovada a cada chamada de {@link ExecutorAtos.Conexoes#abrir()}). */
    public ExecutorAtos cli(Operador o) {
        return new ExecutorAtos(conexoesRenovaveis(o), new LogLocal(dir.resolve("cli-" + o.papel() + ".log")));
    }

    public ExecutorAtos cli(Operador o, LogLocal log) {
        return new ExecutorAtos(conexoesRenovaveis(o), log);
    }

    public ExecutorAtos.Conexoes conexoesRenovaveis(Operador o) {
        Credencial c = credencial(o);
        return () -> conectar(c);
    }

    /** Operador de bootstrap (CADASTRAR), criado uma vez pelo superusuario. */
    public synchronized Operador bootstrap() {
        if (boot == null) {
            String papel = "op_boot_" + sufixo();
            papelOperador(papel);
            UUID id = valor(UUID.class, "SELECT public.ops_bootstrap_operator(?, ?, ?)", papel, IdpSimulado.EMISSOR, IdpSimulado.sujeito(papel));
            boot = new Operador(papel, id);
        }
        return boot;
    }

    private synchronized Credencial credBoot() {
        if (credBoot == null || credBootEm.isBefore(Instant.now().minus(Duration.ofMinutes(10)))) {
            if (credBoot != null) {
                credBoot.close();
            }
            credBoot = credencial(bootstrap());
            credBootEm = Instant.now();
        }
        return credBoot;
    }

    /** Papel LOGIN de operador (DBA): so membro de nekkora_ops_mfa, sem senha. */
    public void papelOperador(String papel) {
        sql("CREATE ROLE " + papel + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS",
            "GRANT nekkora_ops_mfa TO " + papel);
    }

    /** Operador novo, cadastrado e habilitado pelo bootstrap (caminho real das funcoes de cadastro). */
    public Operador operador(String apelido, String... atos) {
        String papel = "op_" + apelido + "_" + sufixo();
        papelOperador(papel);
        UUID id = cadastro("SELECT r_operador FROM public.ops_operator_create(?, ?, ?)", UUID.class, papel, IdpSimulado.EMISSOR,
                IdpSimulado.sujeito(papel));
        if (id == null) {
            throw new IllegalStateException("cadastro do operador recusado");
        }
        Operador o = new Operador(papel, id);
        for (String a : atos) {
            habilitar(o, a);
        }
        return o;
    }

    public void habilitar(Operador o, String ato) {
        String r = cadastro("SELECT r_resultado FROM public.ops_operator_grant(?, ?)", String.class, o.id(), ato);
        if (!"OK".equals(r)) {
            throw new IllegalStateException("habilitacao recusada");
        }
    }

    /** Executa uma funcao de cadastro com a credencial do bootstrap e devolve a primeira coluna. */
    public <T> T cadastro(String consulta, Class<T> tipo, Object... params) {
        try (Connection con = conectar(credBoot()); PreparedStatement ps = con.prepareStatement(consulta)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, tipo) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cadastro falhou (sqlstate " + e.getSQLState() + "): " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ organizacoes (fixture declarada)

    /** Organizacao ativa com um ORG_ADMIN (bootstrap da 012 por nekkora_provisioning) com fator confirmado. */
    public Org organizacao() {
        UUID org = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        sql("INSERT INTO organizations (organization_id, name, status) VALUES ('" + org + "', 'Org sintetica', 1)");
        usuario(org, admin);
        sql("INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('"
                + membership + "', '" + org + "', '" + admin + "', 1, 2)");
        try (Connection c = comoServico("nekkora_provisioning");
             PreparedStatement ps = c.prepareStatement("SELECT provision_first_org_admin(?, ?, ?, 'teste', gen_random_uuid())")) {
            ps.setObject(1, org);
            ps.setObject(2, membership);
            ps.setObject(3, admin);
            ps.execute();
        } catch (SQLException e) {
            throw new IllegalStateException("bootstrap do ORG_ADMIN falhou", e);
        }
        UUID fator = fatorConfirmado(org, admin);
        return new Org(org, admin, membership, fator);
    }

    /** Outro ORG_ADMIN efetivo (atribuido pelo admin inicial), com fator confirmado. */
    public UUID outroAdmin(Org o) {
        UUID u = UUID.randomUUID();
        UUID m = UUID.randomUUID();
        usuario(o.id(), u);
        sql("INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('"
                + m + "', '" + o.id() + "', '" + u + "', 1, 2)",
            "INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id) "
                + "SELECT '" + o.id() + "', '" + m + "', role_id, 1, 1, '" + o.adminMembership() + "' FROM roles WHERE code = 'ORG_ADMIN'");
        fatorConfirmado(o.id(), u);
        return u;
    }

    /** Conta interna ativa com vinculo ativo, sem papel. */
    public UUID membro(Org o) {
        UUID u = UUID.randomUUID();
        usuario(o.id(), u);
        sql("INSERT INTO organization_memberships (organization_id, user_id, account_type, status) VALUES ('" + o.id() + "', '" + u + "', 1, 2)");
        fatorConfirmado(o.id(), u);
        return u;
    }

    private void usuario(UUID org, UUID u) {
        sql("INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('"
                + u + "', '" + org + "', 1, 'p-" + u.toString().substring(0, 8) + "@org.test', now(), 2, 'Pessoa sintetica')",
            "INSERT INTO user_credentials (user_id, password_hash) VALUES ('" + u + "', 'hash-sintetico-" + hex(8) + "')");
    }

    public UUID fatorConfirmado(UUID org, UUID user) {
        UUID f = UUID.randomUUID();
        sql("INSERT INTO user_mfa_factors (factor_id, organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, created_at, confirmed_at) "
                + "VALUES ('" + f + "', '" + org + "', '" + user + "', 1, '\\x" + hex(36) + "', '\\x" + hex(12) + "', 'k1', now() - interval '1 day', now() - interval '1 day')");
        return f;
    }

    /** Sessao ativa, desafio ativo e dois codigos de recuperacao - para conferir os efeitos do reset. */
    public void estadoDeAcesso(UUID org, UUID user, UUID fator) {
        sql("INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES ('"
                + org + "', '" + user + "', '" + hex(32) + "', now() + interval '8 hours', now(), '" + csrf() + "')",
            "INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, credential_hash, csrf_token, expires_at) VALUES ('"
                + org + "', '" + user + "', '" + hex(32) + "', 1, 'hash-sintetico', '" + csrf() + "', now() + interval '4 minutes')",
            "INSERT INTO user_mfa_recovery_codes (organization_id, user_id, factor_id, code_hash) VALUES ('" + org + "', '" + user + "', '"
                + fator + "', '" + hex(32) + "'), ('" + org + "', '" + user + "', '" + fator + "', '" + hex(32) + "')");
    }

    // ------------------------------------------------------------------ contatos autorizados (caminho real + envelhecimento)

    /** Contato incluido pela funcao real (bootstrap CADASTRAR) e envelhecido para ser elegivel (canal >= 30 dias). */
    public UUID contatoElegivel(Org o, UUID usuario) {
        UUID c = contatoNovo(o, usuario);
        envelhecerContato(c, Duration.ofDays(31));
        return c;
    }

    public UUID contatoNovo(Org o, UUID usuario) {
        UUID c = cadastro("SELECT r_contato FROM public.ops_contact_add(?, ?, ?::smallint, ?, ?, 'CONTRATACAO', true)", UUID.class,
                o.id(), "Contato sintetico", 2, "c-" + hex(4) + "@contato.test", usuario);
        if (c == null) {
            throw new IllegalStateException("inclusao de contato recusada");
        }
        return c;
    }

    // ------------------------------------------------------------------ fixtures temporais (so nesta base de teste)

    private void replica(String... comandos) {
        try (Connection con = superusuario()) {
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
                for (String s : comandos) {
                    st.execute(s);
                }
            }
            con.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("fixture temporal falhou (sqlstate " + e.getSQLState() + "): " + e.getMessage(), e);
        }
    }

    /** Simula a passagem de {@code d} para a solicitacao: desloca para tras todos os seus instantes e os dos avisos. */
    public void recuarSolicitacao(UUID solicitacao, Duration d) {
        String i = "interval '" + d.toSeconds() + " seconds'";
        replica("UPDATE ops.ops_mfa_recovery_requests SET opened_at = opened_at - " + i + ", t_total = t_total - " + i
                + ", verified_at = verified_at - " + i + ", preapproved_at = preapproved_at - " + i
                + ", notice_confirmed_at = notice_confirmed_at - " + i + ", final_approved_at = final_approved_at - " + i
                + ", executed_at = executed_at - " + i + ", closed_at = closed_at - " + i + " WHERE request_id = '" + solicitacao + "'",
            "UPDATE ops.ops_mfa_notices SET created_at = created_at - " + i + ", issued_at = issued_at - " + i
                + ", state_at = state_at - " + i + ", claimed_at = claimed_at - " + i + ", accepted_at = accepted_at - " + i
                + " WHERE request_id = '" + solicitacao + "'",
            "UPDATE ops.ops_mfa_notice_tokens SET issued_at = issued_at - " + i + ", valid_until = valid_until - " + i
                + ", used_at = used_at - " + i + ", invalidated_at = invalidated_at - " + i + " WHERE request_id = '" + solicitacao + "'",
            // a fotografia (captured_at) NAO e deslocada: ela e comparada com fatos externos (sessoes, fatores, codigos),
            // que a fixture nao move; deslocar faria fatos anteriores a fotografia parecerem posteriores
            "UPDATE ops.ops_mfa_approval_attempts SET accepted_at = accepted_at - " + i + ", approved_at = approved_at - " + i
                + " WHERE request_id = '" + solicitacao + "'");
    }

    /** Desloca para tras so o aceite (e o estado) de UMA tentativa de aviso: aceite antigo sem mexer no resto. */
    public void recuarAceite(UUID aviso, Duration d) {
        String i = "interval '" + d.toSeconds() + " seconds'";
        replica("UPDATE ops.ops_mfa_notices SET accepted_at = accepted_at - " + i + ", state_at = state_at - " + i
                + " WHERE notice_id = '" + aviso + "'");
    }

    /** Desloca para tras o instante do estado de uma tentativa (tentativa parada ha d, para a varredura de recuperacao). */
    public void recuarEstadoDoAviso(UUID aviso, Duration d) {
        String i = "interval '" + d.toSeconds() + " seconds'";
        replica("UPDATE ops.ops_mfa_notices SET state_at = state_at - " + i + " WHERE notice_id = '" + aviso + "'");
    }

    /** Desloca para tras a ativacao da versao de chave vigente (transicao de 25 h que ja comecou ha d). */
    public void recuarAtivacaoDaChave(Duration d) {
        String i = "interval '" + d.toSeconds() + " seconds'";
        replica("UPDATE ops.ops_intake_key_state SET activated_at = activated_at - " + i);
    }

    /** Desloca para tras o registro de uma entrada (registrada ha d). */
    public void recuarEntrada(UUID entrada, Duration d) {
        String i = "interval '" + d.toSeconds() + " seconds'";
        replica("UPDATE ops.ops_intake_entries SET created_at = created_at - " + i + ", expires_at = expires_at - " + i
                + " WHERE entry_id = '" + entrada + "'");
    }

    /**
     * Desloca para tras SO a abertura (e a verificacao, que a CHECK amarra a abertura + 24 h), mantendo a
     * pre-aprovacao, os avisos e os tokens no instante real: "aberta ha d, pre-aprovada agora". Serve para a confirmacao
     * tardia, em que o token CONFIRMAR ainda e valido mas confirmacao + 24 h >= T_total.
     */
    public void recuarAbertura(UUID solicitacao, Duration d) {
        String i = "interval '" + d.toSeconds() + " seconds'";
        replica("UPDATE ops.ops_mfa_recovery_requests SET opened_at = opened_at - " + i + ", t_total = t_total - " + i
                + ", verified_at = verified_at - " + i + " WHERE request_id = '" + solicitacao + "'");
    }

    public void envelhecerContato(UUID contato, Duration d) {
        String i = "interval '" + d.toSeconds() + " seconds'";
        replica("UPDATE ops.org_authorized_contacts SET created_at = created_at - " + i + ", channel_set_at = channel_set_at - " + i
                + " WHERE contact_id = '" + contato + "'");
    }

    /** Leva o registro de emissao da credencial para alem do vencimento (a conexao TLS continua aberta). */
    public void vencerCertificado(Credencial c) {
        replica("UPDATE ops.ops_operator_certificates SET not_before = not_before - interval '16 minutes', not_after = not_after - interval '16 minutes', "
                + "registered_at = registered_at - interval '16 minutes', idp_auth_time = idp_auth_time - interval '16 minutes' WHERE serial = "
                + c.certificado().getSerialNumber());
    }

    // ------------------------------------------------------------------ utilitarios

    public static String hex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Token no formato da v1 (32 bytes base64url) e o SHA-256 hex que o banco guarda. */
    public static String tokenBruto() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String sha256(String bruto) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bruto.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String csrf() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String sufixo() {
        return hex(3);
    }

    private static String aleatoria() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
