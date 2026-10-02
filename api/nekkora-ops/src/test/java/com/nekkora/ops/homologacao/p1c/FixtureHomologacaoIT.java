package com.nekkora.ops.homologacao.p1c;


import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * P1c (desenho P1a/P1b v2.2, sec. 5 e 9): as DUAS fixtures temporais REAIS ({@code sql/fixture_016_contatos.sql} e
 * {@code sql/fixture_016_solicitacao.sql}) executadas como o runner executa: {@code psql -v ON_ERROR_STOP=1}, SEM
 * {@code --single-transaction} (o BEGIN/COMMIT e do proprio arquivo), pela conexao do job de ferramentas (TLS
 * verify-full, {@code admin_cluster}), a partir de um container cliente na rede da base ({@code <prefixo>.31} ->
 * {@code postgres}/{@code <prefixo>.10}), numa base 001-016 descartavel com {@code cluster_name=nekkora-homologacao}.
 *
 * <p>Confere: deslocamentos EXATOS (microssegundos), contagens, campos nao autorizados das linhas alteradas, demais linhas
 * e a fotografia ({@code captured_at} nunca deslocado); as recusas previstas (rollback, nada alterado); a CONTRAPROVA
 * obrigatoria (copia de teste com alteracao proibida injetada depois do UPDATE autorizado e antes das verificacoes, na
 * mesma transacao, lida para provar a injecao, recusada, rollback integral); {@code session_replication_role} na sessao
 * original e em conexao nova; e a classificacao e a reconciliacao ({@code lib/fixture_conferencia.py}) com resultados
 * OBSERVADOS (falhas controladas no banco) - os casos so sinteticos do classificador ficam separados e rotulados.
 *
 * <p>Preparacao declarada (nao e evidencia): organizacoes, operadores, contatos e solicitacoes pelos caminhos reais da
 * suite (Cenarios/OpsBanco), inclusive as fixtures temporais da suite; a organizacao alvo recebe o nome sintetico da 016.
 */
class FixtureHomologacaoIT {

    static final String PREFIXO = "10.252.40";
    static final long H = 3_600_000_000L;
    static BancoHomologacao HB;
    static OpsBanco DB;
    static Cenarios C;

    @BeforeAll
    static void subir() {
        HB = BancoHomologacao.subir(PREFIXO, OpsBanco.HBA_PADRAO, "fixture", Map.of());
        DB = HB.db;
        C = new Cenarios(DB);
    }

    @AfterAll
    static void encerrar() {
        if (HB != null) {
            HB.close();
        }
    }

    // ================================================================== preparacao e execucao

    static String ambiente() {
        return "ensaio-p1c-fx-" + OpsBanco.hex(3);
    }

    static void sintetica(UUID org, String amb) {
        DB.sql("UPDATE organizations SET name = 'Homologacao 016 sintetica " + amb + "' WHERE organization_id = '" + org + "'");
    }

    static BancoHomologacao.Exec contatos(String app, String amb, UUID org, String lista, String ip) {
        return HB.psql(app, "-X", "-At", "-v", "ON_ERROR_STOP=1", "-v", "ambiente=" + amb, "-v", "org=" + org, "-v", "contatos=" + lista,
                "-v", "ip_servidor=" + ip, "-f", "/sql/fixture_016_contatos.sql");
    }

    static BancoHomologacao.Exec solicitacao(String app, String arquivo, String amb, UUID org, UUID req, int horas, String ip, String... extra) {
        List<String> a = new ArrayList<>(List.of("-X", "-At", "-v", "ON_ERROR_STOP=1", "-v", "ambiente=" + amb, "-v", "org=" + org,
                "-v", "solicitacao=" + req, "-v", "horas=" + horas, "-v", "ip_servidor=" + ip));
        for (String e : extra) {
            a.addAll(List.of("-v", e));
        }
        a.addAll(List.of("-f", "/sql/" + arquivo));
        return HB.psql(app, a.toArray(String[]::new));
    }

    static String classificar(BancoHomologacao.Exec e) {
        Processos.Resultado r = Processos.python("fixture_conferencia.py", e.paraClassificar(), "classificar");
        assertThat(r.codigo()).as("classificador: %s", r.erro()).isZero();
        return r.saida().trim();
    }

    /** Estado atual dos campos alvo (mesma consulta da reconciliacao do runner, prefixo ESTADO, somente leitura). */
    static String estado(String tipo, String alvo) {
        BancoHomologacao.Exec e = HB.psql(null, "-X", "-At", "-v", "ON_ERROR_STOP=1", "-v", "prefixo=ESTADO", "-v", "tipo=" + tipo, "-v",
                (tipo.equals("contatos") ? "contatos=" : "solicitacao=") + alvo, "-f", "/sql/fixture_016_estado.sql");
        assertThat(e.codigo()).as("leitura do estado: %s", e.saida()).isZero();
        return e.saida();
    }

    /** Reconciliacao pela biblioteca real: bloco da execucao (cabecalho do runner + saida) e o estado atual. */
    static Processos.Resultado reconciliar(String tipo, String alvo, int horas, BancoHomologacao.Exec execucao, String estadoAtual) throws Exception {
        String exe = UUID.randomUUID().toString();
        Path fx = Files.createTempFile("p1c-fixtures-", ".txt");
        Files.writeString(fx, "== fixture " + tipo + " execucao=" + exe + " " + Instant.now() + " alvo=" + alvo + " horas=" + horas + "\n"
                + execucao.paraClassificar(), StandardCharsets.UTF_8);
        try {
            return Processos.python("fixture_conferencia.py", estadoAtual, fx.toString(), exe);
        } finally {
            Files.delete(fx);
        }
    }

    // ================================================================== fotografias (conexao nova, superusuario)

    record Linha(boolean alvo, String resto, Map<String, Long> campos) {
    }

    /**
     * Fotografia de uma tabela inteira: por chave, se e linha alvo, o JSON da linha SEM os campos autorizados e os campos
     * autorizados em microssegundos desde a epoca (null = NULL).
     */
    static Map<String, Linha> foto(String tabela, String chave, List<String> campos, String alvo, Object param) {
        StringBuilder sql = new StringBuilder("SELECT ").append(chave).append(", (").append(alvo).append(")::boolean, (to_jsonb(t)");
        for (String c : campos) {
            sql.append(" - '").append(c).append("'");
        }
        sql.append(")::text");
        for (String c : campos) {
            sql.append(", (extract(epoch FROM ").append(c).append(") * 1000000)::bigint");
        }
        sql.append(" FROM ").append(tabela).append(" t");
        Map<String, Linha> m = new LinkedHashMap<>();
        try (Connection c = DB.superusuario(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            if (sql.indexOf("?") >= 0) {
                ps.setObject(1, param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Long> v = new LinkedHashMap<>();
                    for (int i = 0; i < campos.size(); i++) {
                        long x = rs.getLong(4 + i);
                        v.put(campos.get(i), rs.wasNull() ? null : x);
                    }
                    m.put(rs.getString(1), new Linha(rs.getBoolean(2), rs.getString(3), v));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    static final List<String> C_CONTATO = List.of("created_at", "channel_set_at");
    static final Map<String, List<String>> C_SOLICITACAO = new LinkedHashMap<>();
    static final Map<String, String> CHAVES = new LinkedHashMap<>();

    static {
        C_SOLICITACAO.put("ops.ops_mfa_recovery_requests", List.of("opened_at", "t_total", "verified_at", "preapproved_at", "notice_confirmed_at",
                "final_approved_at", "executed_at", "closed_at"));
        C_SOLICITACAO.put("ops.ops_mfa_notices", List.of("created_at", "issued_at", "state_at", "claimed_at", "accepted_at"));
        C_SOLICITACAO.put("ops.ops_mfa_notice_tokens", List.of("issued_at", "valid_until", "used_at", "invalidated_at"));
        C_SOLICITACAO.put("ops.ops_mfa_approval_attempts", List.of("accepted_at", "approved_at"));
        C_SOLICITACAO.put("ops.ops_mfa_request_admins", List.of());   // fotografia: nada autorizado (captured_at inclusive)
        CHAVES.put("ops.ops_mfa_recovery_requests", "request_id::text");
        CHAVES.put("ops.ops_mfa_notices", "notice_id::text");
        CHAVES.put("ops.ops_mfa_notice_tokens", "token_id::text");
        CHAVES.put("ops.ops_mfa_approval_attempts", "request_id::text || '/' || notice_id::text");
        CHAVES.put("ops.ops_mfa_request_admins", "request_id::text || '/' || user_id::text");
    }

    static Map<String, Linha> fotoContatos(String lista) {
        return foto("ops.org_authorized_contacts", "contact_id::text", C_CONTATO, "contact_id = ANY (string_to_array(?, ',')::uuid[])", lista);
    }

    /** As cinco tabelas da solicitacao; alvo = linhas da solicitacao (na fotografia, marcadas mas sem campo autorizado). */
    static Map<String, Map<String, Linha>> fotoSolicitacao(UUID req) {
        Map<String, Map<String, Linha>> m = new LinkedHashMap<>();
        for (var e : C_SOLICITACAO.entrySet()) {
            m.put(e.getKey(), foto(e.getKey(), CHAVES.get(e.getKey()), e.getValue(), "request_id = ?", req));
        }
        return m;
    }

    static String md5Eventos() {
        return HB.valor("SELECT md5(coalesce(string_agg(to_jsonb(e)::text, '|' ORDER BY event_id), '')) || ':' || count(*) "
                + "FROM ops.org_authorized_contact_events e");
    }

    /** Linhas alvo: campos autorizados = antes - deslocamento (nulos continuam nulos) e resto identico; demais identicas. */
    static int conferirDeslocamento(String tabela, Map<String, Linha> antes, Map<String, Linha> depois, long desloc) {
        assertThat(depois.keySet()).as("%s: mesmas linhas", tabela).isEqualTo(antes.keySet());
        int alvos = 0;
        for (var e : antes.entrySet()) {
            Linha a = e.getValue();
            Linha d = depois.get(e.getKey());
            assertThat(d.resto()).as("%s %s: campos NAO autorizados identicos", tabela, e.getKey()).isEqualTo(a.resto());
            if (a.alvo()) {
                alvos++;
                for (var c : a.campos().entrySet()) {
                    Long esperado = c.getValue() == null ? null : c.getValue() - desloc;
                    assertThat(d.campos().get(c.getKey())).as("%s %s.%s = antes - deslocamento", tabela, e.getKey(), c.getKey()).isEqualTo(esperado);
                }
            } else {
                assertThat(d.campos()).as("%s %s: linha fora do alvo identica", tabela, e.getKey()).isEqualTo(a.campos());
            }
        }
        return alvos;
    }

    static void semMarca(BancoHomologacao.Exec e) {
        assertThat(e.saida()).doesNotContain("FIXTURE_COMMIT_CONCLUIDO").doesNotContain("verificacoes=OK");
    }

    /** session_replication_role em CONEXAO NOVA (e sem configuracao persistente por papel/banco). */
    static void origemEmConexaoNova(String rotulo) {
        assertThat(HB.valor("SHOW session_replication_role")).as("%s: conexao nova", rotulo).isEqualTo("origin");
        assertThat(HB.valor("SELECT count(*) FROM pg_db_role_setting s, unnest(s.setconfig) c WHERE c LIKE 'session_replication_role%'"))
                .as("%s: sem configuracao persistente", rotulo).isEqualTo("0");
        Evidencia.anexar("fixture", "session_replication_role", rotulo + ": conexao nova SHOW session_replication_role=origin; "
                + "pg_db_role_setting sem session_replication_role");
    }

    // ================================================================== contatos: aplicada

    @Test
    void contatosAplicadaDeslocaExatamente721hSoOsCamposDeclarados() throws Exception {
        String amb = ambiente();
        OpsBanco.Org o = DB.organizacao();
        sintetica(o.id(), amb);
        UUID c1 = DB.contatoNovo(o, o.admin());
        UUID c2 = DB.contatoNovo(o, null);
        OpsBanco.Org outra = DB.organizacao();
        DB.contatoNovo(outra, outra.admin());   // linha de OUTRA organizacao: nao pode mudar
        String lista = c1 + "," + c2;
        Map<String, Linha> antes = fotoContatos(lista);
        String eventos = md5Eventos();

        BancoHomologacao.Exec e = contatos("fx-aplicada", amb, o.id(), lista, HB.ipServidor());
        Evidencia.anexar("fixture", "contatos_aplicada", "psql -X -At -v ON_ERROR_STOP=1 (sem --single-transaction) -f /sql/fixture_016_contatos.sql\n"
                + "contatos=" + lista + "\ncodigo=" + e.codigo() + "\n" + e.saida());
        assertThat(e.codigo()).as(e.saida()).isZero();
        assertThat(e.saida().lines().filter(l -> l.startsWith("ANTES|contato|")).count()).as("estado de partida antes do BEGIN").isEqualTo(4);
        assertThat(e.saida()).contains("FIXTURE_COMMIT_CONCLUIDO")
                .contains("fixture_contatos: org=" + o.id() + " contatos=" + lista + " linhas=2 deslocamento=721h verificacoes=OK session_replication_role=origin");
        assertThat(e.saida().indexOf("ANTES|")).isLessThan(e.saida().indexOf("FIXTURE_COMMIT_CONCLUIDO"));

        Map<String, Linha> depois = fotoContatos(lista);
        assertThat(conferirDeslocamento("org_authorized_contacts", antes, depois, 721 * H)).as("linhas alteradas").isEqualTo(2);
        assertThat(md5Eventos()).as("org_authorized_contact_events nao muda").isEqualTo(eventos);
        origemEmConexaoNova("contatos APLICADA (a sessao original imprimiu session_replication_role=origin depois do COMMIT)");

        assertThat(classificar(e)).isEqualTo("APLICADA");
        Processos.Resultado r = reconciliar("contatos", lista, 721, e, estado("contatos", lista));
        assertThat(r.saida()).startsWith("reconciliacao: APLICADA");
        assertThat(r.codigo()).isZero();
        Evidencia.anexar("fixture", "contatos_aplicada", "classificacao=APLICADA\n" + r.saida());
    }

    // ================================================================== contatos: recusas previstas

    record Recusa(String nome, String mensagem, BancoHomologacao.Exec exec, String alvo) {
        Recusa(String nome, String mensagem, BancoHomologacao.Exec exec) {
            this(nome, mensagem, exec, null);
        }
    }

    static Map<String, Linha> sem(Map<String, Linha> m, Object... chaves) {
        Map<String, Linha> r = new LinkedHashMap<>(m);
        for (Object k : chaves) {
            r.remove(k.toString());
        }
        return r;
    }

    static void exigirRecusa(Recusa r) {
        Evidencia.anexar("fixture", "recusas", "caso=" + r.nome() + " codigo=" + r.exec().codigo() + "\n" + r.exec().saida());
        assertThat(r.exec().codigo()).as("%s: codigo 3 (ON_ERROR_STOP)\n%s", r.nome(), r.exec().saida()).isEqualTo(3);
        assertThat(r.exec().saida()).as(r.nome()).contains("ERROR:").contains(r.mensagem());
        semMarca(r.exec());
        assertThat(classificar(r.exec())).as(r.nome()).isEqualTo("NAO_APLICADA");
    }

    @Test
    void contatosRecusasPrevistasDesfazemTudo() throws Exception {
        String amb = ambiente();
        OpsBanco.Org o = DB.organizacao();
        sintetica(o.id(), amb);
        UUID c1 = DB.contatoNovo(o, o.admin());
        UUID c2 = DB.contatoNovo(o, null);
        OpsBanco.Org naoSintetica = DB.organizacao();
        UUID cx = DB.contatoNovo(naoSintetica, naoSintetica.admin());
        String lista = c1 + "," + c2;
        Map<String, Linha> antes = fotoContatos(lista + "," + cx);
        String eventos = md5Eventos();

        List<Recusa> casos = List.of(
            new Recusa("organizacao_nao_sintetica", "organizacao nao e a sintetica da 016 deste ambiente",
                    contatos("fx-r1", amb, naoSintetica.id(), cx.toString(), HB.ipServidor()), cx.toString()),
            new Recusa("servidor_divergente", "servidor inesperado", contatos("fx-r2", amb, o.id(), lista, PREFIXO + ".99"), lista),
            new Recusa("ambiente_nao_descartavel", "ambiente nao descartavel", contatos("fx-r3", "hml03", o.id(), lista, HB.ipServidor()), lista),
            new Recusa("contato_de_outra_organizacao", "contato fora da organizacao sintetica",
                    contatos("fx-r4", amb, o.id(), c1 + "," + cx, HB.ipServidor()), c1 + "," + cx),
            new Recusa("lista_repetida", "lista de contatos vazia ou repetida", contatos("fx-r5", amb, o.id(), c1 + "," + c1, HB.ipServidor()),
                    c1 + "," + c1));
        for (Recusa r : casos) {
            exigirRecusa(r);
            assertThat(fotoContatos(lista + "," + cx)).as("%s: nada alterado", r.nome()).isEqualTo(antes);
            Processos.Resultado rec = reconciliar("contatos", r.alvo(), 721, r.exec(), estado("contatos", r.alvo()));
            assertThat(rec.saida()).as(r.nome()).startsWith("reconciliacao: NAO_APLICADA");
        }
        assertThat(md5Eventos()).isEqualTo(eventos);
        origemEmConexaoNova("contatos recusas (sessao original encerrada pelo psql no erro)");
    }

    @Test
    void contatosRecusaComSolicitacaoExistente() {
        String amb = ambiente();
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        sintetica(al.org().id(), amb);
        C.aberta(eq, al);
        String lista = al.contatoTitular() + "," + al.segundoContato();
        Map<String, Linha> antes = fotoContatos(lista);
        exigirRecusa(new Recusa("solicitacao_existente", "ja existe solicitacao na organizacao",
                contatos("fx-r6", amb, al.org().id(), lista, HB.ipServidor())));
        assertThat(fotoContatos(lista)).isEqualTo(antes);
    }

    /**
     * Contagem divergente com o script REAL: uma sessao concorrente segura a linha de c2 (FOR UPDATE), a fixture espera no
     * UPDATE; a concorrente remove c2 e confirma. O UPDATE da fixture altera 1 linha, nao 2 -> RAISE -> rollback.
     */
    @Test
    void contatosContagemDivergenteComRemocaoConcorrente() throws Exception {
        String amb = ambiente();
        OpsBanco.Org o = DB.organizacao();
        sintetica(o.id(), amb);
        UUID c1 = DB.contatoNovo(o, o.admin());
        UUID c2 = DB.contatoNovo(o, null);
        String lista = c1 + "," + c2;
        Map<String, Linha> antes = fotoContatos(c1.toString());
        try (Connection s = DB.superusuario(); Statement st = s.createStatement()) {
            s.setAutoCommit(false);
            st.execute("SELECT 1 FROM ops.org_authorized_contacts WHERE contact_id = '" + c2 + "' FOR UPDATE");
            CompletableFuture<BancoHomologacao.Exec> fx = CompletableFuture.supplyAsync(() -> contatos("fx-contagem", amb, o.id(), lista, HB.ipServidor()));
            HB.esperar("fixture esperando a linha de c2", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'fx-contagem' "
                    + "AND wait_event_type = 'Lock')", Duration.ofSeconds(60));
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("DELETE FROM ops.org_authorized_contacts WHERE contact_id = '" + c2 + "'");
            s.commit();
            BancoHomologacao.Exec e = fx.get(2, TimeUnit.MINUTES);
            exigirRecusa(new Recusa("contagem_divergente", "fixture: 1 linhas alteradas, esperado 2", e));
        }
        // c2 foi removida pela sessao CONCORRENTE (nao pela fixture); todo o resto, c1 inclusive, igual a fotografia
        assertThat(fotoContatos(c1.toString())).as("c1 intacto (rollback)").isEqualTo(sem(antes, c2));
    }

    /**
     * Linha extra alterada durante a fixture, com o script REAL: a concorrente segura c1, a fixture espera no UPDATE; a
     * concorrente altera OUTRA linha da tabela e confirma. As verificacoes antes do COMMIT veem a outra linha diferente
     * da fotografia -> RAISE -> rollback dos campos autorizados.
     */
    @Test
    void contatosOutraLinhaAlteradaDuranteAFixtureDesfazTudo() throws Exception {
        String amb = ambiente();
        OpsBanco.Org o = DB.organizacao();
        sintetica(o.id(), amb);
        UUID c1 = DB.contatoNovo(o, o.admin());
        UUID c2 = DB.contatoNovo(o, null);
        OpsBanco.Org outra = DB.organizacao();
        UUID cx = DB.contatoNovo(outra, outra.admin());
        String lista = c1 + "," + c2;
        Map<String, Linha> antes = fotoContatos(lista);
        try (Connection s = DB.superusuario(); Statement st = s.createStatement()) {
            s.setAutoCommit(false);
            st.execute("SELECT 1 FROM ops.org_authorized_contacts WHERE contact_id = '" + c1 + "' FOR UPDATE");
            CompletableFuture<BancoHomologacao.Exec> fx = CompletableFuture.supplyAsync(() -> contatos("fx-extra", amb, o.id(), lista, HB.ipServidor()));
            HB.esperar("fixture esperando a linha de c1", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'fx-extra' "
                    + "AND wait_event_type = 'Lock')", Duration.ofSeconds(60));
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("UPDATE ops.org_authorized_contacts SET display_name = display_name || ' concorrente' WHERE contact_id = '" + cx + "'");
            s.commit();
            exigirRecusa(new Recusa("linha_extra_concorrente", "fixture: outra linha alterada", fx.get(2, TimeUnit.MINUTES)));
        }
        assertThat(sem(fotoContatos(lista), cx)).as("linhas alvo intactas (inclusive campos autorizados)").isEqualTo(sem(antes, cx));
    }

    // ================================================================== solicitacao: aplicada

    record Solicitacao(String amb, UUID org, UUID req, UUID outra) {
    }

    /**
     * Solicitacao APROVADA (linhas nas quatro tabelas alvo e fotografia com o outro admin), na organizacao sintetica; e
     * uma solicitacao de OUTRA organizacao (demais linhas das cinco tabelas).
     */
    static Solicitacao aprovadaSintetica() {
        String amb = ambiente();
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        DB.outroAdmin(al.org());
        UUID s = C.aprovada(eq, al);
        sintetica(al.org().id(), amb);
        Cenarios.Alvo al2 = C.alvo();
        DB.outroAdmin(al2.org());
        UUID s2 = C.aprovada(eq, al2);
        return new Solicitacao(amb, al.org().id(), s, s2);
    }

    @Test
    void solicitacaoAplicadaDeslocaOsCamposDeclaradosNasQuatroTabelasEPreservaAFotografia() throws Exception {
        Solicitacao so = aprovadaSintetica();
        Map<String, Map<String, Linha>> antes = fotoSolicitacao(so.req());
        for (String t : List.of("ops.ops_mfa_recovery_requests", "ops.ops_mfa_notices", "ops.ops_mfa_notice_tokens", "ops.ops_mfa_approval_attempts",
                "ops.ops_mfa_request_admins")) {
            assertThat(antes.get(t).values().stream().filter(Linha::alvo).count()).as("%s: linhas da solicitacao", t).isPositive();
            assertThat(antes.get(t).values().stream().filter(l -> !l.alvo()).count()).as("%s: demais linhas", t).isPositive();
        }
        BancoHomologacao.Exec e = solicitacao("fx-sol", "fixture_016_solicitacao.sql", so.amb(), so.org(), so.req(), 25, HB.ipServidor());
        Evidencia.anexar("fixture", "solicitacao_aplicada", "psql -X -At -v ON_ERROR_STOP=1 (sem --single-transaction) -f /sql/fixture_016_solicitacao.sql horas=25\n"
                + "codigo=" + e.codigo() + "\n" + e.saida());
        assertThat(e.codigo()).as(e.saida()).isZero();
        assertThat(e.saida()).contains("FIXTURE_COMMIT_CONCLUIDO").contains("fixture_solicitacao: solicitacao=" + so.req() + " deslocamento=25h")
                .contains("verificacoes=OK session_replication_role=origin");
        Map<String, Map<String, Linha>> depois = fotoSolicitacao(so.req());
        StringBuilder contagens = new StringBuilder();
        Map<String, Integer> alvos = new LinkedHashMap<>();
        for (String t : C_SOLICITACAO.keySet()) {
            int n = conferirDeslocamento(t, antes.get(t), depois.get(t), 25 * H);
            alvos.put(t, n);
            contagens.append(t).append(" linhas_alvo=").append(n).append(" demais=").append(antes.get(t).size() - n).append('\n');
        }
        // fotografia inteira identica (captured_at inclusive), tambem as linhas desta solicitacao
        assertThat(depois.get("ops.ops_mfa_request_admins")).isEqualTo(antes.get("ops.ops_mfa_request_admins"));
        // as contagens que o script imprime ("linhas=") sao as linhas alvo contadas aqui, de forma independente
        assertThat(e.saida()).contains(" linhas=apr:" + alvos.get("ops.ops_mfa_approval_attempts") + " not:" + alvos.get("ops.ops_mfa_notices")
                + " req:1 tok:" + alvos.get("ops.ops_mfa_notice_tokens") + " ");
        Evidencia.anexar("fixture", "solicitacao_aplicada", contagens + "fotografia (ops_mfa_request_admins, captured_at) identica");
        origemEmConexaoNova("solicitacao APLICADA (a sessao original imprimiu session_replication_role=origin depois do COMMIT)");
        assertThat(classificar(e)).isEqualTo("APLICADA");
        Processos.Resultado r = reconciliar("solicitacao", so.req().toString(), 25, e, estado("solicitacao", so.req().toString()));
        assertThat(r.saida()).startsWith("reconciliacao: APLICADA");
    }

    @Test
    void solicitacaoRecusasPrevistasDesfazemTudo() throws Exception {
        Solicitacao so = aprovadaSintetica();
        Map<String, Map<String, Linha>> antes = fotoSolicitacao(so.req());
        UUID orgOutra = DB.valor(UUID.class, "SELECT organization_id FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", so.outra());
        List<Recusa> casos = List.of(
            new Recusa("horas_zero", "deslocamento fora de 1..72 h",
                    solicitacao("fx-s1", "fixture_016_solicitacao.sql", so.amb(), so.org(), so.req(), 0, HB.ipServidor())),
            new Recusa("horas_73", "deslocamento fora de 1..72 h",
                    solicitacao("fx-s2", "fixture_016_solicitacao.sql", so.amb(), so.org(), so.req(), 73, HB.ipServidor())),
            new Recusa("organizacao_nao_sintetica", "organizacao nao e a sintetica da 016 deste ambiente",
                    solicitacao("fx-s3", "fixture_016_solicitacao.sql", so.amb(), orgOutra, so.outra(), 24, HB.ipServidor())),
            new Recusa("solicitacao_de_outra_organizacao", "solicitacao fora da organizacao sintetica",
                    solicitacao("fx-s4", "fixture_016_solicitacao.sql", so.amb(), so.org(), so.outra(), 24, HB.ipServidor())),
            new Recusa("servidor_divergente", "servidor inesperado",
                    solicitacao("fx-s5", "fixture_016_solicitacao.sql", so.amb(), so.org(), so.req(), 24, PREFIXO + ".99")));
        for (Recusa r : casos) {
            exigirRecusa(r);
            assertThat(fotoSolicitacao(so.req())).as("%s: nada alterado", r.nome()).isEqualTo(antes);
        }
        origemEmConexaoNova("solicitacao recusas");
    }

    /** Contagem divergente com o script REAL: remocao concorrente de uma tentativa enquanto a fixture espera por ela. */
    @Test
    void solicitacaoContagemDivergenteComRemocaoConcorrente() throws Exception {
        Solicitacao so = aprovadaSintetica();
        UUID aviso = DB.valor(UUID.class, "SELECT notice_id FROM ops.ops_mfa_notices WHERE request_id = ? ORDER BY seq DESC LIMIT 1", so.req());
        Map<String, Linha> reqAntes = fotoSolicitacao(so.req()).get("ops.ops_mfa_recovery_requests");
        try (Connection s = DB.superusuario(); Statement st = s.createStatement()) {
            s.setAutoCommit(false);
            st.execute("SELECT 1 FROM ops.ops_mfa_notices WHERE notice_id = '" + aviso + "' FOR UPDATE");
            CompletableFuture<BancoHomologacao.Exec> fx = CompletableFuture.supplyAsync(
                    () -> solicitacao("fx-s-contagem", "fixture_016_solicitacao.sql", so.amb(), so.org(), so.req(), 24, HB.ipServidor()));
            HB.esperar("fixture esperando a tentativa", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'fx-s-contagem' "
                    + "AND wait_event_type = 'Lock')", Duration.ofSeconds(60));
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("DELETE FROM ops.ops_mfa_notice_results WHERE notice_id = '" + aviso + "'");
            st.execute("DELETE FROM ops.ops_mfa_notices WHERE notice_id = '" + aviso + "'");
            s.commit();
            exigirRecusa(new Recusa("contagem_divergente_solicitacao", "fixture: contagem de tentativas divergente", fx.get(2, TimeUnit.MINUTES)));
        }
        assertThat(fotoSolicitacao(so.req()).get("ops.ops_mfa_recovery_requests")).as("solicitacao intacta (rollback)").isEqualTo(reqAntes);
    }

    // ================================================================== CONTRAPROVA obrigatoria (copia de TESTE do SQL)

    static final String PONTO = "-- (4) verificacoes ANTES do COMMIT";

    /**
     * Copia de TESTE do script real com um bloco injetado imediatamente antes das verificacoes (depois do UPDATE autorizado,
     * na MESMA transacao). Nenhum gatilho: com session_replication_role=replica gatilhos comuns nao disparariam.
     */
    static String copiaComInjecao(String original, String nomeCopia, String bloco) throws Exception {
        String sql = Files.readString(Processos.HOMOLOGACAO.resolve("sql").resolve(original), StandardCharsets.UTF_8);
        int i = sql.indexOf(PONTO);
        assertThat(i).as("ponto de insercao unico em %s", original).isPositive().isEqualTo(sql.lastIndexOf(PONTO));
        String inj = "-- ===== INJECAO DE TESTE (P1c) - SO nesta copia, nunca no script real =====\n" + bloco
                + "SELECT 'NA_TRANSACAO|session_replication_role=' || current_setting('session_replication_role') || ' xid=' || "
                + "coalesce(txid_current_if_assigned()::text, '-');\n-- ===== FIM DA INJECAO =====\n";
        String copia = sql.substring(0, i) + inj + sql.substring(i);
        HB.copiarParaSql(nomeCopia, copia);
        Evidencia.anexar("fixture", "contraprova_copias", "copia=" + nomeCopia + " original=sql/" + original + " sha256_original="
                + sha256(sql) + " linha_de_insercao=" + (sql.substring(0, i).lines().count() + 1) + "\nbloco injetado:\n" + inj);
        return nomeCopia;
    }

    static String sha256(String s) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void contraprovaContatosCampoNaoAutorizadoInjetadoEDesfeitoInclusiveOsAutorizados() throws Exception {
        String amb = ambiente();
        OpsBanco.Org o = DB.organizacao();
        sintetica(o.id(), amb);
        UUID c1 = DB.contatoNovo(o, o.admin());
        UUID c2 = DB.contatoNovo(o, null);
        OpsBanco.Org outra = DB.organizacao();
        UUID cx = DB.contatoNovo(outra, outra.admin());
        String lista = c1 + "," + c2;
        String todos = lista + "," + cx;
        Map<String, Linha> antes = fotoContatos(todos);
        long antesCanal = antes.get(c1.toString()).campos().get("channel_set_at");

        String[][] injecoes = {
            {"campo_nao_autorizado", "teste_injecao_contatos_campo.sql",
             "UPDATE ops.org_authorized_contacts SET display_name = display_name || ' INJETADO' WHERE contact_id = :'alvo_injecao'::uuid;\n"
             + "SELECT 'INJECAO_LIDA|display_name=' || display_name FROM ops.org_authorized_contacts WHERE contact_id = :'alvo_injecao'::uuid;\n",
             c1.toString(), "fixture: campo nao autorizado alterado"},
            {"linha_extra", "teste_injecao_contatos_linha.sql",
             "UPDATE ops.org_authorized_contacts SET display_name = display_name || ' INJETADO' WHERE contact_id = :'alvo_injecao'::uuid;\n"
             + "SELECT 'INJECAO_LIDA|display_name=' || display_name FROM ops.org_authorized_contacts WHERE contact_id = :'alvo_injecao'::uuid;\n",
             cx.toString(), "fixture: outra linha alterada"},
        };
        for (String[] inj : injecoes) {
            String bloco = inj[2] + "SELECT 'AUTORIZADO_LIDO|channel_set_at_us=' || (extract(epoch FROM channel_set_at) * 1000000)::bigint "
                    + "FROM ops.org_authorized_contacts WHERE contact_id = '" + c1 + "';\n";
            copiaComInjecao("fixture_016_contatos.sql", inj[1], bloco);
            BancoHomologacao.Exec e = HB.psql("fx-contraprova", "-X", "-At", "-v", "ON_ERROR_STOP=1", "-v", "ambiente=" + amb, "-v", "org=" + o.id(),
                    "-v", "contatos=" + lista, "-v", "ip_servidor=" + HB.ipServidor(), "-v", "alvo_injecao=" + inj[3], "-f", "/sql/" + inj[1]);
            // prova de que a injecao ocorreu dentro da transacao, junto com a alteracao autorizada
            assertThat(e.saida()).as(inj[0]).contains("INJECAO_LIDA|display_name=").contains(" INJETADO")
                    .contains("AUTORIZADO_LIDO|channel_set_at_us=" + (antesCanal - 721 * H))
                    .contains("NA_TRANSACAO|session_replication_role=replica xid=");
            exigirRecusa(new Recusa("contraprova_contatos_" + inj[0], inj[4], e));
            // rollback INTEGRAL: tudo igual a fotografia anterior, inclusive os campos autorizados das linhas alvo
            assertThat(fotoContatos(todos)).as("%s: rollback integral", inj[0]).isEqualTo(antes);
            assertThat(HB.valor("SELECT count(*) FROM ops.org_authorized_contacts WHERE display_name LIKE '%INJETADO%'")).isEqualTo("0");
        }
        origemEmConexaoNova("contraprova contatos (sessao original encerrada no erro)");
    }

    @Test
    void contraprovaSolicitacaoCampoFotografiaELinhaExtraInjetadosEDesfeitos() throws Exception {
        Solicitacao so = aprovadaSintetica();
        Map<String, Map<String, Linha>> antes = fotoSolicitacao(so.req());
        long antesAbertura = antes.get("ops.ops_mfa_recovery_requests").get(so.req().toString()).campos().get("opened_at");
        String lerAutorizado = "SELECT 'AUTORIZADO_LIDO|opened_at_us=' || (extract(epoch FROM opened_at) * 1000000)::bigint "
                + "FROM ops.ops_mfa_recovery_requests WHERE request_id = '" + so.req() + "';\n";
        String[][] injecoes = {
            {"campo_nao_autorizado", "teste_injecao_solicitacao_campo.sql",
             "UPDATE ops.ops_mfa_recovery_requests SET notice_seq = notice_seq + 1 WHERE request_id = :'alvo_injecao'::uuid;\n"
             + "SELECT 'INJECAO_LIDA|notice_seq=' || notice_seq FROM ops.ops_mfa_recovery_requests WHERE request_id = :'alvo_injecao'::uuid;\n",
             so.req().toString(), "fixture: campo nao autorizado alterado"},
            {"fotografia", "teste_injecao_solicitacao_fotografia.sql",
             "UPDATE ops.ops_mfa_request_admins SET captured_at = captured_at - interval '1 hour' WHERE request_id = :'alvo_injecao'::uuid;\n"
             + "SELECT 'INJECAO_LIDA|captured_at_us=' || (extract(epoch FROM captured_at) * 1000000)::bigint FROM ops.ops_mfa_request_admins "
             + "WHERE request_id = :'alvo_injecao'::uuid;\n",
             so.req().toString(), "fixture: outra linha (ou a fotografia) alterada"},
            {"linha_extra", "teste_injecao_solicitacao_linha.sql",
             "UPDATE ops.ops_mfa_recovery_requests SET notice_seq = notice_seq + 1 WHERE request_id = :'alvo_injecao'::uuid;\n"
             + "SELECT 'INJECAO_LIDA|notice_seq=' || notice_seq FROM ops.ops_mfa_recovery_requests WHERE request_id = :'alvo_injecao'::uuid;\n",
             so.outra().toString(), "fixture: outra linha (ou a fotografia) alterada"},
            {"deslocamento_diferente", "teste_injecao_solicitacao_desloc.sql",
             "UPDATE ops.ops_mfa_notices SET state_at = state_at - interval '1 second' WHERE request_id = :'alvo_injecao'::uuid;\n"
             + "SELECT 'INJECAO_LIDA|state_at_extra=1s' FROM ops.ops_mfa_notices WHERE request_id = :'alvo_injecao'::uuid LIMIT 1;\n",
             so.req().toString(), "fixture: campo autorizado com valor diferente do deslocamento declarado"},
        };
        for (String[] inj : injecoes) {
            copiaComInjecao("fixture_016_solicitacao.sql", inj[1], inj[2] + lerAutorizado);
            BancoHomologacao.Exec e = solicitacao("fx-contraprova-s", inj[1], so.amb(), so.org(), so.req(), 25, HB.ipServidor(), "alvo_injecao=" + inj[3]);
            assertThat(e.saida()).as(inj[0]).contains("INJECAO_LIDA|").contains("AUTORIZADO_LIDO|opened_at_us=" + (antesAbertura - 25 * H))
                    .contains("NA_TRANSACAO|session_replication_role=replica xid=");
            exigirRecusa(new Recusa("contraprova_solicitacao_" + inj[0], inj[4], e));
            assertThat(fotoSolicitacao(so.req())).as("%s: rollback integral (cinco tabelas, inclusive campos autorizados)", inj[0]).isEqualTo(antes);
        }
        origemEmConexaoNova("contraprova solicitacao");
    }

    // ================================================================== classificacao e reconciliacao (resultados OBSERVADOS)

    static void terminar(String app) {
        assertThat(HB.valor("SELECT bool_and(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name = '" + app + "'"))
                .as("backend %s encerrado", app).isEqualTo("t");
    }

    /**
     * INDETERMINADA observada, SEM commit: a fixture espera uma linha (FOR UPDATE de outra sessao) e o backend dela e
     * encerrado pelo servidor antes do COMMIT (conexao perdida, codigo 2, sem marca). Reconciliacao: NAO_APLICADA.
     */
    @Test
    void indeterminadaObservadaSemCommitReconciliaComoNaoAplicada() throws Exception {
        String amb = ambiente();
        OpsBanco.Org o = DB.organizacao();
        sintetica(o.id(), amb);
        UUID c1 = DB.contatoNovo(o, o.admin());
        String lista = c1.toString();
        Map<String, Linha> antes = fotoContatos(lista);
        BancoHomologacao.Exec e;
        try (Connection s = DB.superusuario(); Statement st = s.createStatement()) {
            s.setAutoCommit(false);
            st.execute("SELECT 1 FROM ops.org_authorized_contacts WHERE contact_id = '" + c1 + "' FOR UPDATE");
            CompletableFuture<BancoHomologacao.Exec> fx = CompletableFuture.supplyAsync(() -> contatos("fx-ind1", amb, o.id(), lista, HB.ipServidor()));
            HB.esperar("fixture no UPDATE", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'fx-ind1' AND wait_event_type = 'Lock')",
                    Duration.ofSeconds(60));
            terminar("fx-ind1");
            e = fx.get(2, TimeUnit.MINUTES);
            s.rollback();
        }
        Evidencia.anexar("fixture", "classificacao_observada", "caso=INDETERMINADA_sem_commit (backend encerrado no UPDATE, antes do COMMIT)\ncodigo="
                + e.codigo() + "\n" + e.saida());
        assertThat(e.codigo()).as(e.saida()).isEqualTo(2);
        semMarca(e);
        assertThat(e.saida()).contains("terminating connection due to administrator command");
        assertThat(classificar(e)).isEqualTo("INDETERMINADA");
        assertThat(fotoContatos(lista)).isEqualTo(antes);
        Processos.Resultado r = reconciliar("contatos", lista, 721, e, estado("contatos", lista));
        Evidencia.anexar("fixture", "classificacao_observada", "reconciliacao: " + r.saida());
        assertThat(r.saida()).startsWith("reconciliacao: NAO_APLICADA");
    }

    /**
     * APLICADA_COM_FALHA_POS_COMMIT observada: o COMMIT e confirmado (marca impressa) e a sessao cai na conferencia
     * posterior. Uma sessao pede ACCESS EXCLUSIVE na tabela enquanto a fixture esta na transacao; no COMMIT ela obtem a
     * trava e a conferencia pos-COMMIT da fixture fica esperando; o backend e encerrado. Nunca "rollback": reconcilia APLICADA.
     */
    @Test
    void falhaPosCommitObservadaEClassificadaComoAplicadaComFalhaEReconciliaAplicada() throws Exception {
        String amb = ambiente();
        OpsBanco.Org o = DB.organizacao();
        sintetica(o.id(), amb);
        UUID c1 = DB.contatoNovo(o, o.admin());
        String lista = c1.toString();
        Map<String, Linha> antes = fotoContatos(lista);
        BancoHomologacao.Exec e;
        try (Connection linha = DB.superusuario(); Statement sl = linha.createStatement();
             Connection tabela = DB.superusuario(); Statement stab = tabela.createStatement()) {
            linha.setAutoCommit(false);
            tabela.setAutoCommit(false);
            sl.execute("SELECT 1 FROM ops.org_authorized_contacts WHERE contact_id = '" + c1 + "' FOR UPDATE");
            String pidTabela;
            try (ResultSet rs = stab.executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                pidTabela = rs.getString(1);
            }
            CompletableFuture<BancoHomologacao.Exec> fx = CompletableFuture.supplyAsync(() -> contatos("fx-pos", amb, o.id(), lista, HB.ipServidor()));
            HB.esperar("fixture no UPDATE", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'fx-pos' AND wait_event_type = 'Lock')",
                    Duration.ofSeconds(60));
            CompletableFuture<Void> trava = CompletableFuture.runAsync(() -> {
                try {
                    stab.execute("LOCK TABLE ops.org_authorized_contacts IN ACCESS EXCLUSIVE MODE");
                } catch (SQLException x) {
                    throw new IllegalStateException(x);
                }
            });
            HB.esperar("ACCESS EXCLUSIVE na fila", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = " + pidTabela + " AND wait_event_type = 'Lock')",
                    Duration.ofSeconds(60));
            linha.commit();   // a fixture segue, faz o COMMIT e para na conferencia posterior, atras do ACCESS EXCLUSIVE
            trava.get(1, TimeUnit.MINUTES);
            HB.esperar("conferencia pos-COMMIT bloqueada", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'fx-pos' "
                    + "AND wait_event_type = 'Lock' AND backend_xid IS NULL)", Duration.ofSeconds(60));
            terminar("fx-pos");
            e = fx.get(2, TimeUnit.MINUTES);
            tabela.rollback();
        }
        Evidencia.anexar("fixture", "classificacao_observada", "caso=APLICADA_COM_FALHA_POS_COMMIT (backend encerrado na conferencia depois do COMMIT)\n"
                + "codigo=" + e.codigo() + "\n" + e.saida());
        assertThat(e.saida()).as(e.saida()).contains("FIXTURE_COMMIT_CONCLUIDO").doesNotContain("verificacoes=OK");
        assertThat(e.codigo()).isEqualTo(2);
        assertThat(classificar(e)).isEqualTo("APLICADA_COM_FALHA_POS_COMMIT");
        Map<String, Linha> depois = fotoContatos(lista);
        assertThat(conferirDeslocamento("org_authorized_contacts", antes, depois, 721 * H)).isEqualTo(1);
        Processos.Resultado r = reconciliar("contatos", lista, 721, e, estado("contatos", lista));
        Evidencia.anexar("fixture", "classificacao_observada", "reconciliacao: " + r.saida());
        assertThat(r.saida()).startsWith("reconciliacao: APLICADA");
    }

    /**
     * INDETERMINADA observada COM commit: a resposta do COMMIT se perde. A base passa a exigir replica sincrona inexistente
     * (synchronous_standby_names), o COMMIT grava localmente e fica esperando a replica (SyncRep); o backend e encerrado
     * nessa espera: o servidor avisa que a transacao ja foi confirmada localmente, o cliente perde a conexao sem a marca.
     * Reconciliacao: APLICADA (o que o runner exige antes de concluir qualquer coisa).
     */
    @Test
    void indeterminadaObservadaComCommitAplicadoReconciliaComoAplicada() throws Exception {
        String amb = ambiente();
        OpsBanco.Org o = DB.organizacao();
        sintetica(o.id(), amb);
        UUID c1 = DB.contatoNovo(o, o.admin());
        String lista = c1.toString();
        Map<String, Linha> antes = fotoContatos(lista);
        BancoHomologacao.Exec e;
        executar("ALTER SYSTEM SET synchronous_standby_names = 'p1c_replica_inexistente'");
        try {
            executar("SELECT pg_reload_conf()");
            HB.esperar("configuracao recarregada", "SELECT current_setting('synchronous_standby_names') = 'p1c_replica_inexistente'", Duration.ofSeconds(30));
            CompletableFuture<BancoHomologacao.Exec> fx = CompletableFuture.supplyAsync(() -> contatos("fx-sync", amb, o.id(), lista, HB.ipServidor()));
            HB.esperar("COMMIT esperando a replica", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = 'fx-sync' AND wait_event = 'SyncRep')",
                    Duration.ofSeconds(60));
            terminar("fx-sync");
            // o backend encerrado ainda descarta as tabelas temporarias da fixture ao sair, numa transacao que tambem
            // espera a replica sincrona (e ja sem o pedido de encerramento pendente): a conexao so fecha quando a
            // exigencia de replica e retirada. O cliente nao recebeu a resposta do COMMIT em nenhum momento.
            HB.esperar("backend preso na saida (descarte das temporarias)", "SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                    + "WHERE application_name = 'fx-sync' AND wait_event = 'SyncRep' AND state = 'idle')", Duration.ofSeconds(30));
            assertThat(fx).as("cliente sem resposta enquanto a replica e exigida").isNotDone();
            executar("ALTER SYSTEM RESET synchronous_standby_names");
            executar("SELECT pg_reload_conf()");
            try {
                e = fx.get(90, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException t) {
                String log = DB.logDoServidor();
                Evidencia.anexar("fixture", "diagnostico_syncrep", "psql nao terminou em 90 s\npg_stat_activity:\n"
                        + HB.valor("SELECT string_agg(coalesce(application_name,'-') || '|' || coalesce(wait_event,'-') || '|' || coalesce(state,'-') "
                        + "|| '|' || coalesce(left(query, 60),'-'), E'\\n') FROM pg_stat_activity WHERE backend_type = 'client backend'")
                        + "\nlog do servidor (fim):\n" + log.substring(Math.max(0, log.length() - 4000)));
                throw t;
            }
        } finally {
            executar("ALTER SYSTEM RESET synchronous_standby_names");
            executar("SELECT pg_reload_conf()");
        }
        HB.esperar("configuracao restaurada", "SELECT current_setting('synchronous_standby_names') = ''", Duration.ofSeconds(30));
        Evidencia.anexar("fixture", "classificacao_observada", "caso=INDETERMINADA_com_commit (resposta do COMMIT perdida: backend encerrado na espera "
                + "da replica sincrona)\ncodigo=" + e.codigo() + "\n" + e.saida());
        assertThat(e.codigo()).as(e.saida()).isEqualTo(2);
        semMarca(e);
        assertThat(e.saida()).contains("already committed locally");
        assertThat(classificar(e)).isEqualTo("INDETERMINADA");
        assertThat(conferirDeslocamento("org_authorized_contacts", antes, fotoContatos(lista), 721 * H)).isEqualTo(1);
        Processos.Resultado r = reconciliar("contatos", lista, 721, e, estado("contatos", lista));
        Evidencia.anexar("fixture", "classificacao_observada", "reconciliacao: " + r.saida());
        assertThat(r.saida()).startsWith("reconciliacao: APLICADA");
    }

    static void executar(String sql) {
        try (Connection c = DB.superusuario(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reconciliacao: DIVERGENTE (estado que nao e nem o de partida nem o deslocado), SEM_ESTADO_DE_PARTIDA (execucao sem
     * linhas ANTES: conexao recusada antes do BEGIN, observada com a porta errada) e leitura sem linhas (falha de leitura
     * do estado nao permite concluir: DIVERGENTE, codigo 1, nunca NAO_APLICADA/APLICADA).
     */
    @Test
    void reconciliacaoDivergenteSemEstadoDePartidaELeituraVazia() throws Exception {
        String amb = ambiente();
        OpsBanco.Org o = DB.organizacao();
        sintetica(o.id(), amb);
        UUID c1 = DB.contatoNovo(o, o.admin());
        String lista = c1.toString();
        BancoHomologacao.Exec aplicada = contatos("fx-rec", amb, o.id(), lista, HB.ipServidor());
        assertThat(classificar(aplicada)).isEqualTo("APLICADA");
        // fixture DECLARADA desta prova: mais 1 h so em created_at, fora do deslocamento declarado (a CHECK exige
        // channel_set_at >= created_at, por isso o recuo extra vai em created_at)
        try (Connection c = DB.superusuario(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("UPDATE ops.org_authorized_contacts SET created_at = created_at - interval '1 hour' WHERE contact_id = '" + c1 + "'");
            c.commit();
        }
        Processos.Resultado div = reconciliar("contatos", lista, 721, aplicada, estado("contatos", lista));
        assertThat(div.saida()).startsWith("reconciliacao: DIVERGENTE");
        assertThat(div.codigo()).isEqualTo(1);

        // porta errada: o psql nao conecta (codigo 2), nenhuma linha ANTES -> INDETERMINADA e SEM_ESTADO_DE_PARTIDA
        BancoHomologacao.Exec semConexao = HB.psqlConexao("fx-porta", BancoHomologacao.CONEXAO.replace("port=5432", "port=5433"), "-X", "-At", "-v",
                "ON_ERROR_STOP=1", "-v", "ambiente=" + amb, "-v", "org=" + o.id(), "-v", "contatos=" + lista, "-v", "ip_servidor=" + HB.ipServidor(),
                "-f", "/sql/fixture_016_contatos.sql");
        assertThat(semConexao.codigo()).as(semConexao.saida()).isEqualTo(2);
        assertThat(semConexao.saida()).doesNotContain("ANTES|");
        assertThat(classificar(semConexao)).isEqualTo("INDETERMINADA");
        Processos.Resultado sem = reconciliar("contatos", lista, 721, semConexao, estado("contatos", lista));
        assertThat(sem.saida()).startsWith("reconciliacao: SEM_ESTADO_DE_PARTIDA");
        assertThat(sem.codigo()).isEqualTo(1);

        Processos.Resultado vazio = reconciliar("contatos", lista, 721, aplicada, "");
        assertThat(vazio.saida()).startsWith("reconciliacao: DIVERGENTE");
        assertThat(vazio.codigo()).isEqualTo(1);
        Evidencia.anexar("fixture", "reconciliacao", "divergente: " + div.saida() + "sem estado de partida (porta errada, codigo "
                + semConexao.codigo() + "): " + sem.saida() + "estado atual vazio (leitura sem linhas): " + vazio.saida()
                + "saida do psql com porta errada:\n" + semConexao.saida());
    }

    /** Casos SO SINTETICOS do classificador (texto montado, sem banco): rotulados como tal na evidencia. */
    @Test
    void classificadorCasosSinteticos() {
        String[][] casos = {
            {"codigo=0 sem marca", "fixture_contatos: ...\ncodigo=0\n", "INDETERMINADA"},
            {"codigo=3 sem ERROR do servidor", "psql: aviso qualquer\ncodigo=3\n", "INDETERMINADA"},
            {"codigo=0 com marca e sem verificacoes=OK", "FIXTURE_COMMIT_CONCLUIDO\ncodigo=0\n", "APLICADA_COM_FALHA_POS_COMMIT"},
            {"marca e codigo=3 (erro depois do COMMIT)", "FIXTURE_COMMIT_CONCLUIDO\npsql:x:1: ERROR:  fixture: alteracao nao persistida\ncodigo=3\n",
             "APLICADA_COM_FALHA_POS_COMMIT"},
            {"sem linha codigo (job falhou)", "ANTES|contato|x|created_at|1\n", "INDETERMINADA"},
        };
        StringBuilder ev = new StringBuilder("SINTETICOS (texto montado, sem execucao no banco):\n");
        for (String[] c : casos) {
            Processos.Resultado r = Processos.python("fixture_conferencia.py", c[1], "classificar");
            assertThat(r.saida().trim()).as(c[0]).isEqualTo(c[2]);
            ev.append(c[0]).append(" -> ").append(r.saida().trim()).append('\n');
        }
        Evidencia.anexar("fixture", "classificacao_sintetica", ev.toString());
        assertThat(Objects.requireNonNull(ev)).isNotNull();
    }
}
