package com.nekkora.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.nekkora.core.db.IdentityTransactions.EmailCadastro;
import com.nekkora.core.db.IdentityTransactions.IdentidadeResolvida;
import com.nekkora.core.db.IdentityTransactions.Mode;
import com.nekkora.core.db.IdentityTransactions.NovaIdentidade;
import com.nekkora.core.db.IdentityTransactions.TokenSessao;
import com.nekkora.core.testsupport.RealDatabase;
import com.nekkora.core.token.Tokens;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Contrato da integracao Java, §1 (R3 da 017): contexto de identidade transacional, com o papel REAL nekkora_platform
 * e pool real (HikariCP). Prova: (1) a MESMA conexao fisica atende duas identidades em sequencia sem que a segunda veja
 * a primeira; (2) o contexto local some no COMMIT e no ROLLBACK, provado num pool CRU (sem DISCARD ALL), com o mesmo
 * pid; (3) um SET de sessao esquecido faz a guarda
 * abortar (pool sem reset) e e apagado pelo DISCARD ALL (pool com {@link ConnectionResetDataSource}); (4) chave que nao
 * resolve nao executa o trabalho; (5) a identidade resolvida vale so na thread e na transacao de origem ATIVA: recusada em outra
 * thread, em transacao interna (origem suspensa) e depois do COMMIT/ROLLBACK, e valida de novo na retomada; (6) o identity_id de outra
 * pessoa vindo "da requisicao" nao alcanca nada; (7) outra thread nao herda contexto; (8) so a vinculacao tem os dois
 * contextos; (9) no cadastro, o INSERT so passa com o UUID gerado e posto no contexto.
 */
class IdentityTransactionsIT {

    private static final UUID A = UUID.fromString("70170000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("70170000-0000-0000-0000-00000000000b");
    private static final UUID ORG = UUID.fromString("70170000-0000-0000-0000-0000000000a1");
    private static final String TOKEN_A = Tokens.newToken();
    private static final String TOKEN_B = Tokens.newToken();
    private static final String TOKEN_A_REVOGADA = Tokens.newToken();

    private static RealDatabase db;
    private static HikariDataSource umaConexao;      // pool de UMA conexao, com o reset real (DISCARD ALL)
    private static HikariDataSource cruUmaConexao;   // pool de UMA conexao SEM reset (para provar a guarda)
    private static HikariDataSource duasConexoes;    // para a thread paralela
    private static ConnectionResetDataSource comReset;

    @BeforeAll
    static void setUp() {
        db = RealDatabase.shared();
        db.asSuperuser(
                "INSERT INTO organizations (organization_id, name, status) VALUES ('" + ORG + "', 'Org IdentityTransactionsIT', 1) ON CONFLICT DO NOTHING",
                identidade(A, "it-a@identidade.test"), identidade(B, "it-b@identidade.test"),
                sessao(A, TOKEN_A), sessao(B, TOKEN_B), sessao(A, TOKEN_A_REVOGADA),
                // a sessao nasce ativa (trigger da 017) e e revogada depois, pela transicao terminal
                "UPDATE identity_sessions SET revoked_at = now() WHERE session_token_hash = '" + Tokens.sha256Hex(TOKEN_A_REVOGADA) + "'");
        umaConexao = pool(1);
        cruUmaConexao = pool(1);
        duasConexoes = pool(2);
        comReset = new ConnectionResetDataSource(umaConexao);
    }

    @AfterAll
    static void tearDown() {
        umaConexao.close();
        cruUmaConexao.close();
        duasConexoes.close();
    }

    private static String identidade(UUID id, String email) {
        return "INSERT INTO identities (identity_id, email, display_name, email_verified_at, status) VALUES ('" + id + "', '" + email
                + "', 'Pessoa IT', now(), 2) ON CONFLICT DO NOTHING";
    }

    /** Fixture DECLARADA de superusuario: a sessao so nasce por funcao na aplicacao; aqui o alvo e o contexto, nao o login. */
    private static String sessao(UUID id, String token) {
        return "INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token) VALUES ('" + id
                + "', '" + Tokens.sha256Hex(token) + "', now() + interval '1 hour', now(), '" + Tokens.newToken() + "')";
    }

    private static HikariDataSource pool(int tamanho) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(db.url());
        cfg.setUsername("nekkora_platform");
        cfg.setPassword(db.password("nekkora_platform"));
        cfg.setMaximumPoolSize(tamanho);
        cfg.setMinimumIdle(tamanho);
        return new HikariDataSource(cfg);
    }

    private static IdentityTransactions tx(DataSource ds) {
        return new IdentityTransactions(new DataSourceTransactionManager(ds), JdbcClient.create(ds));
    }

    private static String pid(JdbcClient j) {
        return j.sql("SELECT pg_backend_pid()::text").query(String.class).single();
    }

    private static int visiveis(JdbcClient j, UUID id) {
        return j.sql("SELECT (SELECT count(*) FROM identities WHERE identity_id = ?) + (SELECT count(*) FROM identity_sessions WHERE identity_id = ?)")
                .param(id).param(id).query(Integer.class).single();
    }

    @Test
    void mesmaConexaoDuasIdentidadesSemVazamento() {
        IdentityTransactions t = tx(comReset);
        AtomicReference<String> pidA = new AtomicReference<>();
        Optional<int[]> a = t.inIdentity(new TokenSessao(TOKEN_A), Mode.READ_ONLY, (r, j) -> {
            pidA.set(pid(j));
            assertThat(r.identityId()).isEqualTo(A);
            return new int[] {visiveis(j, A), visiveis(j, B)};
        });
        assertThat(a).hasValueSatisfying(v -> assertThat(v).as("contexto A: linhas de A, nenhuma de B").containsExactly(3, 0));
        Optional<int[]> b = t.inIdentity(new TokenSessao(TOKEN_B), Mode.READ_ONLY, (r, j) -> {
            assertThat(pid(j)).as("mesma conexao fisica reutilizada").isEqualTo(pidA.get());
            return new int[] {visiveis(j, B), visiveis(j, A)};
        });
        assertThat(b).hasValueSatisfying(v -> assertThat(v).as("contexto B: linhas de B, nenhuma de A").containsExactly(2, 0));
    }

    /** pid e os dois contextos, lidos sem a guarda (conexao crua do pool): a prova nao depende do IdentityTransactions. */
    private static String[] pidEContextos(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT pg_backend_pid()::text, coalesce(current_setting('nekkora.identity_id', true), ''), "
                     + "coalesce(current_setting('nekkora.organization_id', true), '')")) {
            rs.next();
            return new String[] {rs.getString(1), rs.getString(2), rs.getString(3)};
        }
    }

    /**
     * Limpeza TRANSACIONAL, sem DISCARD ALL: pool cru de UMA conexao, exclusivo deste teste. Depois do COMMIT e depois
     * do ROLLBACK, a MESMA conexao (mesmo pid) volta com os dois contextos vazios.
     */
    @Test
    void contextoLocalSomeNoCommitENoRollbackSemReset() throws Exception {
        try (HikariDataSource cru = pool(1)) {
            IdentityTransactions t = tx(cru);
            String pid = pidEContextos(cru)[0];

            String[] dentro = t.inIdentityAndOrganization(new TokenSessao(TOKEN_A), ORG, Mode.READ_ONLY, (r, j) -> new String[] {pid(j),
                    j.sql("SELECT current_setting('nekkora.identity_id')").query(String.class).single(),
                    j.sql("SELECT current_setting('nekkora.organization_id')").query(String.class).single()}).orElseThrow();
            assertThat(dentro).as("dentro: os dois contextos, na mesma conexao").containsExactly(pid, A.toString(), ORG.toString());
            assertThat(pidEContextos(cru)).as("depois do COMMIT, sem reset").containsExactly(pid, "", "");

            AtomicReference<String> pidNoRollback = new AtomicReference<>();
            assertThatThrownBy(() -> t.inIdentityAndOrganization(new TokenSessao(TOKEN_A), ORG, Mode.READ_WRITE, (r, j) -> {
                pidNoRollback.set(pid(j));
                assertThat(j.sql("SELECT current_setting('nekkora.identity_id')").query(String.class).single()).isEqualTo(A.toString());
                throw new IllegalArgumentException("desfaz");
            })).isInstanceOf(IllegalArgumentException.class);
            assertThat(pidNoRollback.get()).isEqualTo(pid);
            assertThat(pidEContextos(cru)).as("depois do ROLLBACK, sem reset").containsExactly(pid, "", "");

            Object[] semContexto = t.preAuthentication(Mode.READ_ONLY, j -> new Object[] {pid(j), visiveis(j, A)});
            assertThat(semContexto).as("a guarda passa e, sem contexto, nenhuma linha").containsExactly(pid, 0);
        }
    }

    @Test
    void setDeSessaoEsquecidoAbortaAGuardaEODiscardAllOApaga() throws Exception {
        for (String guc : new String[] {"nekkora.identity_id", "nekkora.organization_id"}) {
            try (Connection c = cruUmaConexao.getConnection(); Statement st = c.createStatement()) {
                st.execute("SET " + guc + " = '" + B + "'");   // nivel de SESSAO: o pool cru devolve a conexao suja
            }
            AtomicBoolean executou = new AtomicBoolean();
            assertThatThrownBy(() -> tx(cruUmaConexao).inIdentity(new TokenSessao(TOKEN_A), Mode.READ_ONLY, (r, j) -> {
                executou.set(true);
                return 0;
            })).as("guarda com %s vazado", guc).isInstanceOf(IdentityTransactions.ContextoVazado.class);
            assertThat(executou).as("trabalho nao executado").isFalse();
            try (Connection c = cruUmaConexao.getConnection(); Statement st = c.createStatement()) {
                st.execute("RESET " + guc);
            }
        }
        // com o reset real, o mesmo SET de sessao e apagado na devolucao e a transacao seguinte passa
        try (Connection c = comReset.getConnection(); Statement st = c.createStatement()) {
            st.execute("SET nekkora.identity_id = '" + B + "'");
        }
        assertThat(tx(comReset).inIdentity(new TokenSessao(TOKEN_A), Mode.READ_ONLY, (r, j) -> visiveis(j, B))).contains(0);
    }

    @Test
    void chaveQueNaoResolveNaoExecutaOTrabalho() {
        AtomicBoolean executou = new AtomicBoolean();
        for (String token : new String[] {Tokens.newToken(), TOKEN_A_REVOGADA, "nao-e-token"}) {
            assertThat(tx(comReset).inIdentity(new TokenSessao(token), Mode.READ_WRITE, (r, j) -> {
                executou.set(true);
                return 1;
            })).isEmpty();
        }
        assertThat(executou).isFalse();
    }

    @Test
    void identidadeResolvidaNaoAtravessaTransacoes() {
        AtomicReference<IdentidadeResolvida> fora = new AtomicReference<>();
        tx(comReset).inIdentity(new TokenSessao(TOKEN_A), Mode.READ_ONLY, (r, j) -> {
            fora.set(r);
            return r.identityId();
        });
        assertThatThrownBy(() -> fora.get().identityId()).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------ vinculo da IdentidadeResolvida a thread e a transacao

    @Test
    void identidadeResolvidaUsoNormalNaTransacaoDeOrigem() {
        Optional<Object[]> r = tx(comReset).inIdentity(new TokenSessao(TOKEN_A), Mode.READ_ONLY, (id, j) -> {
            UUID antes = id.identityId();
            int linhas = visiveis(j, A);
            return new Object[] {antes, id.identityId(), id.referencia().isPresent(), linhas};
        });
        assertThat(r).hasValueSatisfying(v -> assertThat(v).containsExactly(A, A, true, 3));
    }

    @Test
    void identidadeResolvidaRecusadaEmOutraThreadComAOrigemAberta() {
        AtomicReference<Throwable> naThread = new AtomicReference<>();
        UUID depois = tx(comReset).inIdentity(new TokenSessao(TOKEN_A), Mode.READ_ONLY, (r, j) -> {
            Thread th = new Thread(() -> {
                try {
                    r.identityId();
                } catch (Throwable e) {
                    naThread.set(e);
                }
            });
            th.start();
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return r.identityId();
        }).orElseThrow();
        assertThat(naThread.get()).as("outra thread, com a transacao de origem ainda aberta")
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("thread");
        assertThat(depois).as("a thread de origem continua usando").isEqualTo(A);
    }

    /**
     * Transacao interna REQUIRES_NEW (suspende a de origem): a identidade externa e recusada dentro dela - inclusive
     * numa interna desfeita - e volta a valer quando a externa e retomada. A interna tem a sua propria resolucao.
     */
    @Test
    void identidadeResolvidaRecusadaNaTransacaoInternaEValidaNaRetomada() {
        IdentityTransactions t = tx(duasConexoes);
        List<String> registro = new ArrayList<>();
        t.inIdentity(new TokenSessao(TOKEN_A), Mode.READ_ONLY, (externa, j) -> {
            registro.add("externa:" + externa.identityId());
            t.preAuthentication(Mode.READ_ONLY, ji -> {
                registro.add("interna-pre:" + recusa(externa::identityId));
                return null;
            });
            registro.add("retomada-1:" + externa.identityId());
            t.inIdentity(new TokenSessao(TOKEN_B), Mode.READ_ONLY, (interna, ji) -> {
                registro.add("interna-B:" + interna.identityId() + ":externa-" + recusa(externa::identityId));
                return null;
            });
            registro.add("retomada-2:" + externa.identityId());
            assertThatThrownBy(() -> t.preAuthentication(Mode.READ_WRITE, ji -> {
                registro.add("interna-desfeita:" + recusa(externa::identityId));
                throw new IllegalArgumentException("desfaz a interna");
            })).isInstanceOf(IllegalArgumentException.class);
            registro.add("retomada-3:" + externa.identityId() + ":" + visiveis(j, A));
            return null;
        });
        assertThat(registro).containsExactly(
                "externa:" + A,
                "interna-pre:suspensa",
                "retomada-1:" + A,
                "interna-B:" + B + ":externa-suspensa",
                "retomada-2:" + A,
                "interna-desfeita:suspensa",
                "retomada-3:" + A + ":3");
    }

    @Test
    void identidadeResolvidaEncerradaPorCommitEPorRollback() {
        AtomicReference<IdentidadeResolvida> noCommit = new AtomicReference<>();
        tx(comReset).inIdentity(new TokenSessao(TOKEN_A), Mode.READ_WRITE, (r, j) -> {
            noCommit.set(r);
            return r.identityId();
        });
        assertThat(recusa(() -> noCommit.get().identityId())).as("depois do COMMIT").isEqualTo("encerrada");

        AtomicReference<IdentidadeResolvida> noRollback = new AtomicReference<>();
        assertThatThrownBy(() -> tx(comReset).inIdentity(new TokenSessao(TOKEN_A), Mode.READ_WRITE, (r, j) -> {
            noRollback.set(r);
            r.identityId();
            throw new IllegalArgumentException("desfaz");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(recusa(() -> noRollback.get().identityId())).as("depois do ROLLBACK").isEqualTo("encerrada");
        assertThat(recusa(() -> noRollback.get().referencia())).isEqualTo("encerrada");
    }

    /** "suspensa", "encerrada", "thread" ou "aceita" - classifica o acesso pela mensagem da recusa. */
    private static String recusa(Runnable acesso) {
        try {
            acesso.run();
            return "aceita";
        } catch (IllegalStateException e) {
            String m = e.getMessage();
            return m.contains("suspensa") ? "suspensa" : m.contains("thread") ? "thread" : m.contains("fora da transacao") ? "encerrada" : "outra:" + m;
        }
    }

    @Test
    void identityIdDeOutraPessoaVindoDaRequisicaoNaoAlcancaNada() {
        int[] r = tx(comReset).inIdentity(new TokenSessao(TOKEN_A), Mode.READ_WRITE, (id, j) -> new int[] {
                j.sql("UPDATE identities SET display_name = 'invadido' WHERE identity_id = ?").param(B).update(),
                j.sql("UPDATE identity_sessions SET revoked_at = now() WHERE identity_id = ?").param(B).update()}).orElseThrow();
        assertThat(r).containsExactly(0, 0);
        assertThat(tx(comReset).inIdentity(new TokenSessao(TOKEN_B), Mode.READ_ONLY, (id, j) -> visiveis(j, B))).contains(2);
    }

    @Test
    void outraThreadNaoHerdaContexto() throws Exception {
        JdbcClient livre = JdbcClient.create(duasConexoes);
        AtomicReference<Integer> naThread = new AtomicReference<>();
        Integer naTransacao = tx(duasConexoes).inIdentity(new TokenSessao(TOKEN_A), Mode.READ_ONLY, (r, j) -> {
            Thread th = new Thread(() -> naThread.set(visiveis(livre, A)));
            th.start();
            try {
                th.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return visiveis(j, A);
        }).orElseThrow();
        assertThat(naTransacao).isEqualTo(3);
        assertThat(naThread.get()).as("fora da thread da transacao: sem contexto, falha fechada").isZero();
    }

    @Test
    void soAVinculacaoTemOsDoisContextos() {
        String[] ambos = tx(comReset).inIdentityAndOrganization(new TokenSessao(TOKEN_A), ORG, Mode.READ_ONLY, (r, j) -> new String[] {
                j.sql("SELECT current_setting('nekkora.identity_id')").query(String.class).single(),
                j.sql("SELECT current_setting('nekkora.organization_id')").query(String.class).single()}).orElseThrow();
        assertThat(ambos).containsExactly(A.toString(), ORG.toString());
        String so = tx(comReset).inIdentity(new TokenSessao(TOKEN_A), Mode.READ_ONLY,
                (r, j) -> j.sql("SELECT coalesce(current_setting('nekkora.organization_id', true), '')").query(String.class).single()).orElseThrow();
        assertThat(so).as("inIdentity nao define organizacao").isEmpty();
    }

    @Test
    void cadastroSoInsereComOUuidGeradoEPostoNoContexto() {
        String email = "it-novo-" + UUID.randomUUID().toString().substring(0, 8) + "@identidade.test";
        UUID criado = tx(comReset).inIdentity(new NovaIdentidade(), Mode.READ_WRITE, (r, j) -> {
            j.sql("INSERT INTO identities (identity_id, email, display_name, status) VALUES (?, ?, 'Nova', 1)").param(r.identityId()).param(email).update();
            return r.identityId();
        }).orElseThrow();
        // outro identity_id que nao o posto no contexto: WITH CHECK da RLS recusa (transacao propria, desfeita inteira)
        assertThatThrownBy(() -> tx(comReset).inIdentity(new NovaIdentidade(), Mode.READ_WRITE, (r, j) ->
                j.sql("INSERT INTO identities (identity_id, email, display_name, status) VALUES (?, ?, 'Intrusa', 1)")
                        .param(UUID.randomUUID()).param("x-" + email).update()))
                .satisfies(e -> assertThat(DbErrors.sqlState(e)).isEqualTo("42501"));
        Optional<Short> status = tx(comReset).inIdentity(new EmailCadastro(email), Mode.READ_ONLY, (r, j) -> {
            assertThat(r.identityId()).isEqualTo(criado);
            return r.status().orElseThrow();
        });
        assertThat(status).contains((short) 1);
    }
}
