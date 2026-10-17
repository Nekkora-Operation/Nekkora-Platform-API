package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.nekkora.core.db.OrgTransactions;
import com.nekkora.core.testsupport.RealRedis;
import com.nekkora.core.token.Tokens;
import com.nekkora.core.web.ApiException;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;
import com.nekkora.platform.admin.AdminService;
import com.nekkora.platform.auth.AuthService;
import com.nekkora.platform.session.SessionRepository;
import com.nekkora.platform.session.ValidSession;

/**
 * Inventario de locks, achados da Platform (I-1 e I-5), com os servicos REAIS da aplicacao (papel nekkora_platform)
 * e o banco com as migrations 001-016.
 *
 * <p>Contraprovas declaradas: os ensaios "ordem anterior" reproduzem, em SQL direto e com duas conexoes, a sequencia de
 * locks que o codigo anterior fazia (descrita no inventario, §4); eles provam que aquela ordem produz 40P01, nao
 * executam o codigo antigo. Os testes "depois" usam o codigo corrigido e mostram espera simples, conferida em
 * pg_stat_activity (wait_event_type = 'Lock'), sem impasse.
 *
 * <p>Sessoes dos atores: criadas pelo repositorio real ({@link SessionRepository#create}) e com step-up registrado por
 * {@link SessionRepository#recordStepUp} (preparacao declarada; o login por TOTP e coberto em outros testes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = {PlatformApplication.class, PlatformTestBase.TestMailConfig.class})
class InventarioLocksPlatformIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    OrgTransactions tx;

    @Autowired
    SessionRepository sessions;

    @Autowired
    AdminService admin;

    @Autowired
    AuthService auth;

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("nekkora.database.url", DB::urlTls);
        r.add("nekkora.database.user", () -> "nekkora_platform");
        r.add("nekkora.database.password", () -> DB.senha("nekkora_platform"));
        r.add("spring.data.redis.host", () -> RealRedis.shared().host());
        r.add("spring.data.redis.port", () -> RealRedis.shared().port());
        r.add("nekkora.public-origin", () -> PlatformTestBase.ORIGIN);
        r.add("nekkora.trusted-proxies", () -> "127.0.0.1/32,::1/128");
        r.add("nekkora.keys.aes-current", () -> "k1");
        r.add("nekkora.keys.aes-by-id", () -> "k1:" + b64(32));
        r.add("nekkora.keys.hmac-id", () -> "h1");
        r.add("nekkora.keys.hmac", () -> b64(32));
    }

    private static String b64(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    // ------------------------------------------------------------------ apoio

    /** Sessao interna valida com step-up recente para {@code user}. */
    private record Ator(UUID user, String token, ValidSession sessao) {
    }

    private Ator ator(UUID org, UUID user) {
        String token = Tokens.newToken();
        String hash = Tokens.sha256Hex(token);
        tx.inOrganization(org, OrgTransactions.Mode.READ_WRITE, j -> {
            UUID id = sessions.create(j, org, user, ValidSession.INTERNAL, hash, Tokens.newToken());
            sessions.recordStepUp(j, id);
            return id;
        });
        ValidSession v = tx.inOrganization(org, OrgTransactions.Mode.READ_ONLY, j -> sessions.validate(j, org, hash, false).orElseThrow());
        return new Ator(user, token, v);
    }

    /** Reset administrativo pelo servico real; devolve "OK" ou o motivo interno da recusa. */
    private String reset(UUID org, Ator a, UUID alvo) {
        try {
            admin.resetMfa(org, a.sessao(), a.token(), alvo, AdminService.ResetReason.DISPOSITIVO_PERDIDO);
            return "OK";
        } catch (ApiException e) {
            return e.internalReason();
        } catch (RuntimeException e) {
            return "ERRO:" + com.nekkora.core.db.DbErrors.sqlState(e);
        }
    }

    private CompletableFuture<String> resetAssincrono(UUID org, Ator a, UUID alvo) {
        return CompletableFuture.supplyAsync(() -> reset(org, a, alvo));
    }

    /** Espera ate haver {@code n} backends aguardando lock (qualquer papel). */
    private static void esperarAguardando(int n) {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < limite) {
            Long k = DB.valor(Long.class, "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()");
            if (k != null && k >= n) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("nao houve " + n + " backends aguardando lock");
    }

    /** Conexao superusuario com transacao aberta segurando a organizacao FOR UPDATE (o "portao" dos ensaios). */
    private static Connection portao(UUID org) throws SQLException {
        Connection c = DB.superusuario();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("SELECT 1 FROM organizations WHERE organization_id = '" + org + "' FOR UPDATE");
        }
        return c;
    }

    private static void executar(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e.getSQLState(), e);
        }
    }

    /** Executa {@code sql} e devolve o SQLSTATE ("00000" se nao falhou). */
    private static String sqlstate(Connection c, String sql) {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
            return "00000";
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static long resetsDe(UUID user) {
        return DB.valor(Long.class, "SELECT count(*) FROM mfa_events WHERE user_id = ? AND event_type = 3", user);
    }

    private static long fatoresAtivos(UUID user) {
        return DB.valor(Long.class, "SELECT count(*) FROM user_mfa_factors WHERE user_id = ? AND disabled_at IS NULL", user);
    }

    // ------------------------------------------------------------------ I-1 confirmEmail

    /**
     * Contraprova (ordem anterior): conta -> UPDATE users (o trigger de presenca da 003 pede a organizacao) contra a
     * ordem da suspensao, organizacao -> conta. Duas conexoes, passos intercalados: 40P01 em uma delas.
     */
    @Test
    void i1OrdemAnteriorDoConfirmEmailProduzImpasse() throws Exception {
        OpsBanco.Org org = DB.organizacao();
        UUID u = DB.membro(org);
        try (Connection confirmacao = DB.superusuario(); Connection suspensao = DB.superusuario()) {
            confirmacao.setAutoCommit(false);
            suspensao.setAutoCommit(false);
            executar(confirmacao, "SELECT 1 FROM users WHERE user_id = '" + u + "' FOR UPDATE");
            executar(suspensao, "SELECT 1 FROM organizations WHERE organization_id = '" + org.id() + "' FOR UPDATE");
            CompletableFuture<String> a = CompletableFuture.supplyAsync(() -> sqlstate(confirmacao,
                    "UPDATE users SET email_verified_at = coalesce(email_verified_at, clock_timestamp()) WHERE user_id = '" + u + "'"));
            esperarAguardando(1);
            String b = sqlstate(suspensao, "SELECT 1 FROM users WHERE user_id = '" + u + "' FOR UPDATE");
            String ra = a.get(30, TimeUnit.SECONDS);
            assertThat(List.of(ra, b)).as("a ordem anterior produz impasse").contains("40P01");
            confirmacao.rollback();
            suspensao.rollback();
        }
    }

    /** Depois (codigo corrigido): confirmEmail espera a organizacao segurada por quem ja tem organizacao -> conta e conclui. */
    @Test
    void i1ConfirmEmailCorrigidoEsperaSemImpasse() throws Exception {
        OpsBanco.Org org = DB.organizacao();
        UUID u = DB.membro(org);
        String bruto = Tokens.newToken();
        DB.sqlNa(org.id(), "INSERT INTO email_verification_tokens (user_id, target_email, token_hash, expires_at) SELECT user_id, email, '"
                + Tokens.sha256Hex(bruto) + "', now() + interval '1 hour' FROM users WHERE user_id = '" + u + "'");
        try (Connection suspensao = portao(org.id())) {
            executar(suspensao, "SELECT 1 FROM users WHERE user_id = '" + u + "' FOR UPDATE");
            CompletableFuture<String> conf = CompletableFuture.supplyAsync(() -> {
                try {
                    auth.confirmEmail(org.id(), bruto);
                    return "OK";
                } catch (ApiException e) {
                    return e.internalReason();
                } catch (RuntimeException e) {
                    return "ERRO:" + com.nekkora.core.db.DbErrors.sqlState(e);
                }
            });
            esperarAguardando(1);
            assertThat(DB.valor(Long.class, "SELECT count(*) FROM email_verification_tokens WHERE token_hash = ? AND consumed_at IS NOT NULL",
                    Tokens.sha256Hex(bruto))).as("token nao consumido antes dos locks").isZero();
            // a "suspensao" termina (so os locks; o efeito nao interessa aqui)
            suspensao.commit();
            assertThat(conf.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        }
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM email_verification_tokens WHERE token_hash = ? AND consumed_at IS NOT NULL",
                Tokens.sha256Hex(bruto))).isEqualTo(1);
    }

    private static String tokenDeEmail(UUID user, String extra) {
        String bruto = Tokens.newToken();
        UUID org = DB.valor(UUID.class, "SELECT organization_id FROM users WHERE user_id = ?", user);
        DB.sqlNa(org, "INSERT INTO email_verification_tokens (user_id, target_email, token_hash, created_at, expires_at" + (extra == null ? "" : ", revoked_at")
                + ") SELECT user_id, email, '" + Tokens.sha256Hex(bruto) + "', "
                + ("VENCIDO".equals(extra) ? "now() - interval '2 hours', now() - interval '1 hour'" : "now(), now() + interval '1 hour'")
                + (extra == null ? "" : "REVOGADO".equals(extra) ? ", now()" : ", NULL")
                + " FROM users WHERE user_id = '" + user + "'");
        return bruto;
    }

    private String confirmar(UUID org, String bruto) {
        try {
            auth.confirmEmail(org, bruto);
            return "OK";
        } catch (ApiException e) {
            return e.internalReason();
        } catch (RuntimeException e) {
            return "ERRO:" + com.nekkora.core.db.DbErrors.sqlState(e);
        }
    }

    private static long consumido(String bruto) {
        return DB.valor(Long.class, "SELECT count(*) FROM email_verification_tokens WHERE token_hash = ? AND consumed_at IS NOT NULL", Tokens.sha256Hex(bruto));
    }

    /** Dois confirmEmail concorrentes com o mesmo token: serializam na organizacao; o segundo nao encontra token livre. */
    @Test
    void i1DoisConfirmEmailComOMesmoTokenConsomemUmaVez() throws Exception {
        OpsBanco.Org org = DB.organizacao();
        UUID u = DB.membro(org);
        String bruto = tokenDeEmail(u, null);
        CompletableFuture<String> a;
        CompletableFuture<String> b;
        try (Connection p = portao(org.id())) {
            a = CompletableFuture.supplyAsync(() -> confirmar(org.id(), bruto));
            b = CompletableFuture.supplyAsync(() -> confirmar(org.id(), bruto));
            esperarAguardando(2);
            p.commit();
        }
        assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder("OK", "token");
        assertThat(consumido(bruto)).isEqualTo(1);
    }

    /** Token vencido, revogado, ja consumido ou de conta de outra organizacao: recusa generica, nada consumido. */
    @Test
    void i1TokenInvalidoTemRecusaGenericaSemConsumo() {
        OpsBanco.Org org = DB.organizacao();
        OpsBanco.Org outra = DB.organizacao();
        UUID u = DB.membro(org);
        String vencido = tokenDeEmail(u, "VENCIDO");
        String revogado = tokenDeEmail(u, "REVOGADO");
        String usado = tokenDeEmail(u, null);
        assertThat(confirmar(org.id(), usado)).isEqualTo("OK");
        String deOutra = tokenDeEmail(DB.membro(outra), null);
        for (String t : List.of(vencido, revogado, usado)) {
            assertThat(confirmar(org.id(), t)).isEqualTo("token");
        }
        assertThat(confirmar(org.id(), deOutra)).as("rota de outra organizacao").isEqualTo("token");
        assertThat(consumido(vencido) + consumido(revogado) + consumido(deOutra)).isZero();
    }

    /** confirmEmail do titular x ato da 016 sobre a mesma conta (pre-aprovacao), nas duas ordens: serializam na organizacao. */
    @Test
    void i1ConfirmEmailConcorrenteComAtoDa016NasDuasOrdens() throws Exception {
        for (boolean atoPrimeiro : List.of(true, false)) {
            Cenarios.Equipe eq = C.equipe();
            Cenarios.Alvo al = C.alvo();
            UUID s = C.verificada(eq, al);
            UUID org = al.org().id();
            String bruto = tokenDeEmail(al.titular(), null);
            CompletableFuture<ExecutorAtos.Resultado> ato;
            CompletableFuture<String> conf;
            try (Connection p = portao(org)) {
                if (atoPrimeiro) {
                    ato = CompletableFuture.supplyAsync(() -> eq.a().preAprovar(s));
                    esperarAguardando(1);
                    conf = CompletableFuture.supplyAsync(() -> confirmar(org, bruto));
                } else {
                    conf = CompletableFuture.supplyAsync(() -> confirmar(org, bruto));
                    esperarAguardando(1);
                    ato = CompletableFuture.supplyAsync(() -> eq.a().preAprovar(s));
                }
                esperarAguardando(2);
                p.commit();
            }
            assertThat(ato.get(30, TimeUnit.SECONDS).ok()).isTrue();
            assertThat(conf.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        }
    }

    // ------------------------------------------------------------------ I-5 reset administrativo

    /** Contraprova (ordem anterior): cada reset trava o seu alvo FOR UPDATE e depois o ator FOR KEY SHARE (FK do evento). */
    @Test
    void i5OrdemAnteriorDosResetsCruzadosProduzImpasse() throws Exception {
        OpsBanco.Org org = DB.organizacao();
        UUID a = org.admin();
        UUID b = DB.outroAdmin(org);
        try (Connection r1 = DB.superusuario(); Connection r2 = DB.superusuario()) {
            r1.setAutoCommit(false);
            r2.setAutoCommit(false);
            executar(r1, "SELECT 1 FROM users WHERE user_id = '" + b + "' FOR UPDATE");   // R1: ator A, alvo B
            executar(r2, "SELECT 1 FROM users WHERE user_id = '" + a + "' FOR UPDATE");   // R2: ator B, alvo A
            CompletableFuture<String> x = CompletableFuture.supplyAsync(() -> sqlstate(r1, "SELECT 1 FROM users WHERE user_id = '" + a + "' FOR KEY SHARE"));
            esperarAguardando(1);
            String y = sqlstate(r2, "SELECT 1 FROM users WHERE user_id = '" + b + "' FOR KEY SHARE");
            assertThat(List.of(x.get(30, TimeUnit.SECONDS), y)).contains("40P01");
            r1.rollback();
            r2.rollback();
        }
    }

    /**
     * Depois: resets cruzados concorrentes nas duas ordens de chegada. Exatamente um conclui; o outro, ao obter os locks,
     * reconfere a sessao do ator (revogada pelo primeiro) e e recusado sem efeito.
     */
    @Test
    void i5ResetsCruzadosNasDuasOrdensConcluemExatamenteUm() throws Exception {
        for (boolean menorPrimeiro : List.of(true, false)) {
            OpsBanco.Org org = DB.organizacao();
            UUID x = org.admin();
            UUID y = DB.outroAdmin(org);
            UUID menor = x.compareTo(y) < 0 ? x : y;
            UUID maior = menor.equals(x) ? y : x;
            Ator am = ator(org.id(), menor);
            Ator aM = ator(org.id(), maior);
            CompletableFuture<String> primeiro;
            CompletableFuture<String> segundo;
            try (Connection p = portao(org.id())) {
                primeiro = menorPrimeiro ? resetAssincrono(org.id(), am, maior) : resetAssincrono(org.id(), aM, menor);
                esperarAguardando(1);
                segundo = menorPrimeiro ? resetAssincrono(org.id(), aM, menor) : resetAssincrono(org.id(), am, maior);
                esperarAguardando(2);
                p.commit();
            }
            List<String> rs = List.of(primeiro.get(30, TimeUnit.SECONDS), segundo.get(30, TimeUnit.SECONDS));
            assertThat(rs).as("ordem menor-primeiro=%s", menorPrimeiro).containsExactlyInAnyOrder("OK", "sessao");
            assertThat(resetsDe(menor) + resetsDe(maior)).isEqualTo(1);
            // o ator do reset concluido manteve o fator; o alvo perdeu
            UUID alvoConcluido = rs.get(0).equals("OK") ? (menorPrimeiro ? maior : menor) : (menorPrimeiro ? menor : maior);
            UUID atorConcluido = alvoConcluido.equals(menor) ? maior : menor;
            assertThat(fatoresAtivos(alvoConcluido)).isZero();
            assertThat(fatoresAtivos(atorConcluido)).isEqualTo(1);
        }
    }

    /** Variante sem sobreposicao: o segundo comeca depois do commit do primeiro e cai na reconferencia (TOCTOU). */
    @Test
    void i5SegundoResetDepoisDoCommitDoPrimeiroERecusado() {
        OpsBanco.Org org = DB.organizacao();
        UUID a = org.admin();
        UUID b = DB.outroAdmin(org);
        Ator aa = ator(org.id(), a);
        Ator ab = ator(org.id(), b);
        assertThat(reset(org.id(), aa, b)).isEqualTo("OK");
        assertThat(reset(org.id(), ab, a)).isEqualTo("sessao");
        assertThat(fatoresAtivos(a)).isEqualTo(1);
    }

    private enum Mudanca { SESSAO_REVOGADA, PERMISSAO_RETIRADA, STEP_UP_VENCIDO }

    /**
     * Recusa depois de revogacao da sessao, perda da permissao ou vencimento do step-up DURANTE a espera pelos locks: a
     * verificacao preliminar passou, o reset espera a organizacao, a mudanca e confirmada por quem a segura, e a
     * reconferencia depois dos locks recusa sem efeito no alvo.
     */
    @Test
    void i5MudancasDuranteAEsperaSaoVistasNaReconferencia() throws Exception {
        for (Mudanca m : Mudanca.values()) {
            OpsBanco.Org org = DB.organizacao();
            UUID atorId = DB.outroAdmin(org);
            UUID alvo = DB.outroAdmin(org);
            Ator a = ator(org.id(), atorId);
            CompletableFuture<String> r;
            try (Connection p = portao(org.id())) {
                r = resetAssincrono(org.id(), a, alvo);
                esperarAguardando(1);
                switch (m) {
                    case SESSAO_REVOGADA -> executar(p, "UPDATE user_sessions SET revoked_at = clock_timestamp() WHERE session_id = '"
                            + a.sessao().sessionId() + "'");
                    case PERMISSAO_RETIRADA -> executar(p, "UPDATE organization_role_assignments SET status = 2, revoked_at = clock_timestamp(), revoked_by_membership_id = '"
                            + org.adminMembership() + "' WHERE organization_id = '" + org.id()
                            + "' AND status = 1 AND membership_id = (SELECT membership_id FROM organization_memberships WHERE user_id = '" + atorId + "')");
                    // o tempo "passa" 6 min para a sessao (a trigger da 014 so deixa avancar: modo replica nesta preparacao,
                    // sem DDL, que esperaria pelo reset e fecharia um ciclo artificial)
                    case STEP_UP_VENCIDO -> {
                        executar(p, "SET LOCAL session_replication_role = replica");
                        executar(p, "UPDATE user_sessions SET created_at = created_at - interval '6 minutes', last_seen_at = last_seen_at - interval '6 minutes', "
                                + "expires_at = expires_at - interval '6 minutes', mfa_verified_at = mfa_verified_at - interval '6 minutes', "
                                + "mfa_step_up_at = mfa_step_up_at - interval '6 minutes' WHERE session_id = '" + a.sessao().sessionId() + "'");
                    }
                }
                p.commit();
            }
            String esperado = switch (m) {
                case SESSAO_REVOGADA -> "sessao";
                case PERMISSAO_RETIRADA -> "PERMISSION";
                case STEP_UP_VENCIDO -> "STEP_UP";
            };
            assertThat(r.get(30, TimeUnit.SECONDS)).as(m.name()).isEqualTo(esperado);
            assertThat(resetsDe(alvo)).as(m.name()).isZero();
            assertThat(fatoresAtivos(alvo)).as(m.name()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------ I-5 x caminhos da 016

    private record Cena(Cenarios.Equipe eq, Cenarios.Alvo al, UUID outro) {
    }

    /** Organizacao com o titular A (alvo da emergencia, com sessao e step-up - improvavel, mas o banco nao supoe) e B. */
    private Cena cena() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID b = DB.outroAdmin(al.org());
        return new Cena(eq, al, b);
    }

    private static boolean falhaTecnica(ExecutorAtos.Resultado r) {
        return r.classe() != ExecutorAtos.Classe.OK && r.classe() != ExecutorAtos.Classe.RECUSA;
    }

    /**
     * Execucao (alvo A) x reset de B por A, nas duas ordens de chegada: sem impasse e exatamente um efeito. Se a
     * execucao vem antes, as sessoes de A sao revogadas e o reset e recusado; se o reset vem antes, B fica sem fator e a
     * execucao e recusada (ADMIN_PODE_CADASTRAR).
     */
    @Test
    void i5ExecucaoConcorrenteComResetDoOutroAdminPeloTitular() throws Exception {
        for (boolean execucaoPrimeiro : List.of(true, false)) {
            Cena c = cena();
            UUID s = C.aprovada(c.eq(), c.al());
            UUID org = c.al().org().id();
            Ator a = ator(org, c.al().titular());
            CompletableFuture<ExecutorAtos.Resultado> exe;
            CompletableFuture<String> rst;
            try (Connection p = portao(org)) {
                if (execucaoPrimeiro) {
                    exe = CompletableFuture.supplyAsync(() -> c.eq().e().executar(s));
                    esperarAguardando(1);
                    rst = resetAssincrono(org, a, c.outro());
                } else {
                    rst = resetAssincrono(org, a, c.outro());
                    esperarAguardando(1);
                    exe = CompletableFuture.supplyAsync(() -> c.eq().e().executar(s));
                }
                esperarAguardando(2);
                p.commit();
            }
            ExecutorAtos.Resultado re = exe.get(30, TimeUnit.SECONDS);
            String rr = rst.get(30, TimeUnit.SECONDS);
            assertThat(falhaTecnica(re)).as("execucao sem falha tecnica: %s", re).isFalse();
            assertThat(rr).as("reset sem falha tecnica").doesNotStartWith("ERRO");
            assertThat(re.ok() ^ "OK".equals(rr)).as("exatamente um efeito: execucao=%s reset=%s", re, rr).isTrue();
            if (re.ok()) {
                assertThat(rr).isEqualTo("sessao");
            } else {
                assertThat(re.motivo()).isEqualTo("ADMIN_PODE_CADASTRAR");
            }
        }
    }

    /** Pre-aprovacao (aviso a B) x reset de B por A, nas duas ordens: sem impasse; o reset conclui; a pre-aprovacao ou conclui ou ve B sem fator. */
    @Test
    void i5PreAprovacaoConcorrenteComResetDoOutroAdmin() throws Exception {
        for (boolean atoPrimeiro : List.of(true, false)) {
            Cena c = cena();
            UUID s = C.verificada(c.eq(), c.al());
            UUID org = c.al().org().id();
            Ator a = ator(org, c.al().titular());
            CompletableFuture<ExecutorAtos.Resultado> ato;
            CompletableFuture<String> rst;
            try (Connection p = portao(org)) {
                if (atoPrimeiro) {
                    ato = CompletableFuture.supplyAsync(() -> c.eq().a().preAprovar(s));
                    esperarAguardando(1);
                    rst = resetAssincrono(org, a, c.outro());
                } else {
                    rst = resetAssincrono(org, a, c.outro());
                    esperarAguardando(1);
                    ato = CompletableFuture.supplyAsync(() -> c.eq().a().preAprovar(s));
                }
                esperarAguardando(2);
                p.commit();
            }
            ExecutorAtos.Resultado r = ato.get(30, TimeUnit.SECONDS);
            assertThat(falhaTecnica(r)).as("pre-aprovacao: %s", r).isFalse();
            assertThat(rst.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
            if (!r.ok()) {
                assertThat(r.motivo()).isEqualTo("ADMIN_PODE_CADASTRAR");
            }
        }
    }

    /** Reenvio a B (item 10) x reset de B por A, nas duas ordens: sem impasse; os dois terminam com desfecho definido. */
    @Test
    void i5ReenvioConcorrenteComResetDoDestinatario() throws Exception {
        for (boolean atoPrimeiro : List.of(true, false)) {
            Cena c = cena();
            UUID s = C.preAprovada(c.eq(), c.al());
            UUID org = c.al().org().id();
            C.emitirAvisos(org);
            Ator a = ator(org, c.al().titular());
            CompletableFuture<ExecutorAtos.Resultado> ato;
            CompletableFuture<String> rst;
            try (Connection p = portao(org)) {
                if (atoPrimeiro) {
                    ato = CompletableFuture.supplyAsync(() -> c.eq().v().reenviarAviso(s, c.outro()));
                    esperarAguardando(1);
                    rst = resetAssincrono(org, a, c.outro());
                } else {
                    rst = resetAssincrono(org, a, c.outro());
                    esperarAguardando(1);
                    ato = CompletableFuture.supplyAsync(() -> c.eq().v().reenviarAviso(s, c.outro()));
                }
                esperarAguardando(2);
                p.commit();
            }
            ExecutorAtos.Resultado r = ato.get(30, TimeUnit.SECONDS);
            assertThat(falhaTecnica(r)).as("reenvio: %s", r).isFalse();
            assertThat(rst.get(30, TimeUnit.SECONDS)).isEqualTo("OK");
        }
    }
}
