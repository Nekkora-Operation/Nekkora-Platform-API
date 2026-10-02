package com.nekkora.ops.homologacao.p1c;

import com.nekkora.ops.homologacao.FerramentaHomologacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.Transferable;

import com.nekkora.ops.testsupport.OpsBanco;

/**
 * P1c (desenho P1a/P1b v2.2, sec. 3.3, 6 e 9): AUTENTICACAO com o pg_hba e o pg_ident GERADOS pelas ferramentas reais
 * ({@code lib/redes.py gerar} sobre {@code postgres/pg_hba.modelo} e {@code pg_ident.modelo}), a CA operacional como
 * {@code ssl_ca_file}, e as sondas pela {@link FerramentaHomologacao} REAL (processo separado).
 *
 * <p><b>LIMITE EXPLICITO:</b> no container de teste os enderecos {@code <interna>.NN/32} do arquivo gerado sao trocados por
 * {@code 0.0.0.0/0} (as conexoes chegam pelo NAT do Docker). Isto comprova a AUTENTICACAO (metodo, mapa, CA, causa da
 * recusa), NAO a restricao por IP. A prova com as regras originais e as origens .33/.31 e da P4 (B6), com a recusa esperada
 * "pg_hba.conf rejects connection".
 *
 * <p>Contraprovas REAIS: CA estranha (unknown_ca + "certificate verify failed" no servidor), senha e CN diferente (28000),
 * porta errada e servidor pausado (FALHA_TECNICA), alerta TLS generico enviado pelo servidor (handshake_failure, cliente
 * so com suite ECDHE_RSA frente a chave EC do servidor) e falha TLS local do cliente. Contraprovas SIMULADAS (excecao montada,
 * sem conexao): os alertas genericos listados no desenho. Separadas e rotuladas na evidencia.
 */
class PgHbaHomologacaoIT {

    static final String PREFIXO = "10.252.44";
    static final String AMBIENTE = "ensaio-p1c-pghba";
    static BancoHomologacao HB;
    static OpsBanco DB;
    static String hbaGerado;
    static String hbaTeste;
    static String identGerado;
    static OpsBanco.Operador a;
    static OpsBanco.Operador b;
    static final Map<String, String> SENHAS = new java.util.HashMap<>();
    static String membrosAntes;
    static FerramentaProcesso F;

    @BeforeAll
    static void subir() throws Exception {
        Path tmp = Files.createTempDirectory("p1c-pghba-");
        Path hba = tmp.resolve("pg_hba.conf");
        Path ident = tmp.resolve("pg_ident.conf");
        Processos.Resultado g1 = Processos.python("redes.py", null, "gerar", Processos.HOMOLOGACAO.resolve("postgres/pg_hba.modelo").toString(),
                hba.toString(), "INTERNA=" + PREFIXO);
        Processos.Resultado g2 = Processos.python("redes.py", null, "gerar", Processos.HOMOLOGACAO.resolve("postgres/pg_ident.modelo").toString(),
                ident.toString());
        assertThat(g1.codigo()).as(g1.erro()).isZero();
        assertThat(g2.codigo()).as(g2.erro()).isZero();
        hbaGerado = Files.readString(hba, StandardCharsets.UTF_8);
        identGerado = Files.readString(ident, StandardCharsets.UTF_8);
        // LIMITE declarado: /32 -> 0.0.0.0/0 (autenticacao, nao restricao por IP)
        hbaTeste = hbaGerado.replaceAll(Pattern.quote(PREFIXO) + "\\.\\d{1,3}/32", "0.0.0.0/0");
        StringBuilder trocas = new StringBuilder("substituicoes /32 -> 0.0.0.0/0 (so no teste):\n");
        List<String> ga = hbaGerado.lines().toList();
        List<String> ta = hbaTeste.lines().toList();
        for (int i = 0; i < ga.size(); i++) {
            if (!ga.get(i).equals(ta.get(i))) {
                trocas.append("linha ").append(i + 1).append(": ").append(ga.get(i).trim()).append("  =>  ").append(ta.get(i).trim()).append('\n');
            }
        }
        Evidencia.anexar("pghba", "configuracao", "pg_hba gerado por redes.py (INTERNA=" + PREFIXO + ") sha256=" + FixtureHomologacaoIT.sha256(hbaGerado)
                + "\npg_ident gerado sha256=" + FixtureHomologacaoIT.sha256(identGerado) + "\n" + trocas
                + "LIMITE: comprova autenticacao, NAO restricao por IP (P4/B6 com as regras originais e origens .33/.31)");
        // o initdb aplica 001-016 pelo socket local: so durante a subida ha uma linha local trust na frente
        HB = BancoHomologacao.subir(PREFIXO, "local all all trust\n" + hbaTeste, "pghba", Map.of(), "ssl_min_protocol_version=TLSv1.2",
                "password_encryption=scram-sha-256", "log_disconnections=on");
        DB = HB.db;
        DB.container().copyFileToContainer(Transferable.of(hbaTeste), "/etc/nekkora/pg_hba.conf");
        DB.container().copyFileToContainer(Transferable.of(identGerado), "/etc/nekkora/pg_ident.conf");
        assertThat(HB.valor("SELECT pg_reload_conf()")).isEqualTo("t");
        HB.esperar("pg_hba recarregado sem a linha trust", "SELECT NOT EXISTS (SELECT 1 FROM pg_hba_file_rules WHERE auth_method = 'trust')",
                java.time.Duration.ofSeconds(30));
        // operadores ANTES de trocar as senhas dos servicos (o emissor da suite registra pelo nekkora_ops_ca)
        a = DB.operador("hmla", "VERIFICAR", "CONSULTAR");
        b = DB.operador("hmlb", "CONSULTAR");
        membrosAntes = membros();
        senhasPeloScriptReal(tmp);
        F = new FerramentaProcesso(DB, AMBIENTE, SENHAS.get("nekkora_ops_ca"), SENHAS.get("nekkora_ops_intake"));
    }

    /** sql/senhas_servicos.sql REAL (psql -v ON_ERROR_STOP=1) com verificadores de lib/segredos.py scram (senhas efemeras). */
    static void senhasPeloScriptReal(Path tmp) throws Exception {
        List<String> args = new ArrayList<>(List.of("-X", "-q", "-At", "-v", "ON_ERROR_STOP=1"));
        for (String[] p : new String[][] {{"platform", "nekkora_platform"}, {"portal", "nekkora_portal"}, {"ops_ca", "nekkora_ops_ca"},
                {"ops_intake", "nekkora_ops_intake"}}) {
            String senha = OpsBanco.tokenBruto();
            Path f = tmp.resolve(p[0]);
            Files.writeString(f, senha, StandardCharsets.UTF_8);
            Processos.Resultado v = Processos.python("segredos.py", null, "scram", f.toString());
            Files.delete(f);
            assertThat(v.codigo()).isZero();
            args.addAll(List.of("-v", "verif_" + p[0] + "=" + v.saida().trim()));
            SENHAS.put(p[1], senha);
        }
        args.addAll(List.of("-f", "/sql/senhas_servicos.sql"));
        BancoHomologacao.Exec e = HB.psql("p1c-senhas", args.toArray(String[]::new));
        assertThat(e.codigo()).as("senhas_servicos.sql").isZero();
        Evidencia.anexar("pghba", "scram", "sql/senhas_servicos.sql (psql -v ON_ERROR_STOP=1, verificadores de segredos.py scram) codigo=" + e.codigo());
    }

    static String membros() {
        return HB.valor("SELECT string_agg(r.rolname || '>' || g.rolname, ',' ORDER BY r.rolname, g.rolname) FROM pg_auth_members m "
                + "JOIN pg_roles r ON r.oid = m.member JOIN pg_roles g ON g.oid = m.roleid WHERE r.rolname IN ('nekkora_ops_ca', 'nekkora_ops_intake')");
    }

    @AfterAll
    static void encerrar() {
        if (F != null) {
            F.apagar();
        }
        if (HB != null) {
            HB.close();
        }
    }

    // ------------------------------------------------------------------ apoio

    /** Marca no log do servidor (RAISE LOG) para delimitar o intervalo de uma sonda. */
    static String marca() {
        String m = "p1c-marca-" + UUID.randomUUID();
        try (Connection c = DB.superusuario(); var st = c.createStatement()) {
            st.execute("DO $$ BEGIN RAISE LOG '" + m + "'; END $$");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return m;
    }

    /** Linhas do log do servidor entre duas marcas. */
    static String logEntre(String m1, String m2) {
        String log = DB.logDoServidor();
        int i = log.indexOf(m1);
        int j = log.indexOf(m2);
        assertThat(i).isNotNegative();
        assertThat(j).isGreaterThan(i);
        return log.substring(i, j);
    }

    static String sonda(Map<String, String> sobrepor, List<String> jvm, String... args) {
        Processos.Resultado r = F.rodar(sobrepor, jvm, args);
        String linha = r.saida().lines().filter(l -> l.startsWith("sonda=")).findFirst().orElse("(sem linha de sonda) erro=" + r.erro().trim());
        Evidencia.anexar("pghba", "sondas", "ferramenta " + String.join(" ", args) + (jvm.isEmpty() ? "" : " jvm=" + jvm)
                + (sobrepor.isEmpty() ? "" : " sobreposicoes=" + sobrepor.keySet()) + "\ncodigo=" + r.codigo() + " " + linha);
        return r.codigo() + "|" + linha;
    }

    static String sonda(String... args) {
        return sonda(Map.of(), List.of(), args);
    }

    // ------------------------------------------------------------------ casos

    @Test
    void regrasGeradasCarregadasSemErroTerminandoNasRecusasExplicitas() {
        String erros = HB.valor("SELECT count(*) FROM pg_hba_file_rules WHERE error IS NOT NULL");
        assertThat(erros).isEqualTo("0");
        long linhasArquivo = hbaTeste.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).count();
        assertThat(HB.valor("SELECT count(*) FROM pg_hba_file_rules")).isEqualTo(String.valueOf(linhasArquivo));
        String regras = HB.valor("SELECT string_agg(rule_number || ' ' || type || ' ' || array_to_string(database, ',') || ' ' || array_to_string(user_name, ',')"
                + " || ' ' || coalesce(address, '-') || ' ' || auth_method || ' ' || coalesce(array_to_string(options, ','), ''), E'\\n' ORDER BY rule_number) "
                + "FROM pg_hba_file_rules");
        Evidencia.anexar("pghba", "configuracao", "pg_hba_file_rules (carregado, sem erro):\n" + regras + "\npg_ident_file_mappings: "
                + HB.valor("SELECT string_agg(map_name || ' ' || sys_name || ' ' || pg_username || ' erro=' || coalesce(error, '-'), '; ') FROM pg_ident_file_mappings"));
        // a visao normaliza as opcoes (clientname=CN e o padrao do metodo cert; clientcert=verify-full e implicito): o
        // arquivo carregado traz clientname=CN explicito
        assertThat(hbaTeste).containsPattern("hostssl\\s+nekkora\\s+/\\^op_\\s+0\\.0\\.0\\.0/0\\s+cert map=ops clientname=CN");
        assertThat(regras).contains("hostssl nekkora /^op_ 0.0.0.0 cert map=ops,clientcert=verify-full")
                .contains("hostssl nekkora nekkora_ops_ca 0.0.0.0 scram-sha-256")
                .contains("hostssl nekkora nekkora_ops_intake 0.0.0.0 scram-sha-256")
                .doesNotContain("trust");
        List<String> r = regras.lines().map(String::trim).toList();
        assertThat(r.get(r.size() - 2)).endsWith("host all all 0.0.0.0 reject");
        assertThat(r.get(r.size() - 1)).endsWith("host all all :: reject");
        // op_* nao tem nenhuma regra de senha
        assertThat(r.stream().filter(l -> l.contains("/^op_") || l.contains(" op_")).toList()).allSatisfy(l -> assertThat(l).contains(" cert "));
        assertThat(HB.valor("SELECT count(*) FROM pg_ident_file_mappings WHERE error IS NULL AND map_name = 'ops' AND pg_username = '\\1'")).isEqualTo("1");
    }

    /**
     * B6 com conexoes REAIS pela ferramenta: valida, senha, CA estranha (com a evidencia do servidor no intervalo), CN
     * diferente, valida de novo e SELECT 1. O arquivo no formato do b6 passa pela avaliacao REAL do h016.sh (avaliar-b6);
     * contraprovas sobre copias do arquivo: sem a evidencia do servidor, com alerta generico, sem a regra explicita do .31,
     * sem a disponibilidade -> REPROVADO.
     */
    @Test
    void sondasReaisComCausaEsperadaEAvaliacaoDoB6() throws Exception {
        List<String> b6 = new ArrayList<>();
        String v1 = sonda("sonda", "valida", a.papel());
        assertThat(v1).startsWith("0|").contains("resultado=CONECTOU").contains("conforme=sim");
        String s = sonda("sonda", "senha", a.papel());
        assertThat(s).startsWith("0|").contains("resultado=RECUSADA_AUTENTICACAO:28000").contains("conforme=sim");
        String m1 = marca();
        String ca = sonda("sonda", "ca-estranha", a.papel());
        String m2 = marca();
        assertThat(ca).startsWith("0|").contains("resultado=RECUSADA_TLS:unknown_ca").contains("conforme=sim");
        String janela = logEntre(m1, m2);
        List<String> servidor = janela.lines().filter(l -> l.toLowerCase().contains("certificate verify failed")
                || l.contains("could not accept SSL connection")).map(l -> l.replaceFirst("^\\S+ \\S+ \\S+ ", "")).toList();
        assertThat(servidor).as("evidencia do servidor no intervalo da sonda").anySatisfy(l -> assertThat(l).containsIgnoringCase("certificate verify failed"));
        String cn = sonda("sonda", "cn-diferente", a.papel(), b.papel());
        assertThat(cn).startsWith("0|").contains("resultado=RECUSADA_AUTENTICACAO:28000").contains("conforme=sim");
        String v2 = sonda("sonda", "valida", a.papel());
        assertThat(v2).startsWith("0|").contains("resultado=CONECTOU");
        assertThat(HB.valor("SELECT 1")).isEqualTo("1");

        // mensagem REAL da regra explicita de recusa (outro usuario/banco sem regra de aceite: cai no "host all all 0.0.0.0/0 reject")
        BancoHomologacao.Exec rej = HB.psqlConexao("p1c-rej", "host=postgres port=5432 dbname=postgres user=nekkora_platform sslmode=verify-full "
                + "sslrootcert=/tls/ca.crt connect_timeout=5", "-X", "-At", "-c", "SELECT 1");
        assertThat(rej.saida()).contains("pg_hba.conf rejects connection");
        Evidencia.anexar("pghba", "sondas", "regra explicita de recusa (real, nekkora_platform no banco postgres): " + rej.saida().trim());

        for (String x : List.of(v1, s, ca)) {
            b6.add(x.substring(x.indexOf('|') + 1));
        }
        b6.add("log do servidor no intervalo da sonda ca-estranha:");
        b6.addAll(servidor);
        b6.add(cn.substring(cn.indexOf('|') + 1));
        b6.add(v2.substring(v2.indexOf('|') + 1));
        // COMPOSTA (declarado): a linha do .31 leva a mensagem real da regra explicita obtida acima com outro usuario/banco;
        // a prova com op_* a partir do .31 e da P4
        b6.add("de .31 como op_hml_ver: " + rej.saida().trim().lines().reduce((p, q) -> q).orElse(""));
        b6.add("servidor disponivel depois: sim");
        String base = String.join("\n", b6) + "\n";
        assertThat(avaliarB6("real", base)).isEqualTo("B6 APROVADO");

        String semServidor = base.lines().filter(l -> !l.toLowerCase().contains("certificate verify failed")).reduce("", (p, q) -> p + q + "\n");
        assertThat(avaliarB6("sem_evidencia_do_servidor", semServidor)).isEqualTo("B6 REPROVADO");
        assertThat(avaliarB6("alerta_generico", base.replace("resultado=RECUSADA_TLS:unknown_ca", "resultado=FALHA_TECNICA:TLS:handshake_failure")))
                .isEqualTo("B6 REPROVADO");
        assertThat(avaliarB6("sem_regra_explicita_31", base.replaceAll("pg_hba.conf rejects connection[^\\n]*", "no pg_hba.conf entry for host")))
                .isEqualTo("B6 REPROVADO");
        assertThat(avaliarB6("servidor_indisponivel_depois", base.replace("servidor disponivel depois: sim", "servidor disponivel depois: nao")))
                .isEqualTo("B6 REPROVADO");
        String umaValida = base.replaceFirst("sonda=valida [^\\n]*\\n", "");
        assertThat(avaliarB6("so_uma_valida", umaValida)).isEqualTo("B6 REPROVADO");
    }

    static String avaliarB6(String caso, String conteudo) throws Exception {
        Path f = Files.createTempFile("p1c-b6-", ".txt");
        Files.writeString(f, conteudo, StandardCharsets.UTF_8);
        try {
            Processos.Resultado r = Processos.rodar(List.of(Processos.bash(), Processos.HOMOLOGACAO.resolve("testes/h016.sh").toString().replace('\\', '/'),
                    "avaliar-b6", f.toString().replace('\\', '/')), null, null, null, 60);
            Evidencia.anexar("pghba", "avaliacao_b6", "caso=" + caso + " -> " + r.saida().trim() + " (codigo " + r.codigo() + ")\n--- arquivo avaliado\n" + conteudo);
            assertThat(r.codigo()).isEqualTo(r.saida().contains("APROVADO") && !r.saida().contains("REPROVADO") ? 0 : 1);
            return r.saida().trim();
        } finally {
            Files.delete(f);
        }
    }

    /**
     * DEFEITO encontrado no P1c (documentado por este teste): com o gerenciador de chaves PADRAO do pgjdbc, um certificado
     * de CA estranha NAO e apresentado (o emissor nao esta na lista de CAs aceitas enviada pelo servidor); o servidor ve
     * a conexao sem certificado e recusa pelo pg_hba (28000, "connection requires a valid client certificate"), sem
     * "certificate verify failed". Por isso a sonda ca-estranha passou a apresentar o certificado (fabrica propria).
     */
    @Test
    void semAFabricaDaSondaOPgjdbcNaoApresentaOCertificadoDeCaEstranha() {
        com.nekkora.ops.testsupport.Pki.Ca estranha = com.nekkora.ops.testsupport.Pki.ca("CA estranha P1c");
        java.security.KeyPair par = com.nekkora.ops.cli.Credencial.novoParDeChaves();
        var cert = com.nekkora.ops.testsupport.Pki.clienteSemRegistro(estranha, par.getPublic(), a.papel(), java.time.Instant.now().minusSeconds(30),
                java.time.Instant.now().plusSeconds(600));
        String m1 = marca();
        String r;
        try (com.nekkora.ops.cli.Credencial c = com.nekkora.ops.cli.Credencial.de(a.papel(), par, cert, estranha.certificado())) {
            r = FerramentaHomologacao.tentar(() -> DB.conectar(c));
        }
        String m2 = marca();
        String janela = logEntre(m1, m2);
        Evidencia.anexar("pghba", "defeito_ca_estranha", "pgjdbc padrao com certificado de CA estranha -> " + r + "\nlog do servidor no intervalo:\n"
                + janela.lines().filter(l -> l.contains("FATAL") || l.contains("certificate")).map(l -> l.replaceFirst("^\\S+ \\S+ \\S+ ", ""))
                .reduce("", (p, q) -> p + q + "\n"));
        assertThat(r).isEqualTo("RECUSADA_AUTENTICACAO:28000");
        assertThat(janela).contains("connection requires a valid client certificate").doesNotContainIgnoringCase("certificate verify failed");
    }

    /** Porta errada, servidor pausado, alerta TLS generico enviado pelo servidor e falha TLS local: FALHA_TECNICA, nunca recusa. */
    @Test
    void falhasTecnicasReaisNuncaContamComoRecusa() throws Exception {
        int porta;
        try (ServerSocket ss = new ServerSocket(0)) {
            porta = ss.getLocalPort();
        }
        Map<String, String> portaErrada = Map.of("NEKKORA_OPS_DB_URL", OpsBanco.url(DB.host(), porta));
        for (String t : List.of("senha", "ca-estranha")) {
            String r = sonda(portaErrada, List.of(), "sonda", t, a.papel());
            assertThat(r).as("porta errada, %s", t).startsWith("2|").contains("resultado=FALHA_TECNICA:08001").contains("conforme=nao");
        }
        // servidor indisponivel durante a sonda (container pausado; tempo de conexao curto)
        Map<String, String> curto = Map.of("NEKKORA_OPS_DB_URL", DB.url() + "?connectTimeout=5&socketTimeout=5&loginTimeout=8");
        String id = DB.container().getContainerId();
        DockerClientFactory.instance().client().pauseContainerCmd(id).exec();
        try {
            for (String t : List.of("senha", "ca-estranha")) {
                String r = sonda(curto, List.of(), "sonda", t, a.papel());
                assertThat(r).as("servidor pausado, %s", t).startsWith("2|").contains("resultado=FALHA_TECNICA:").contains("conforme=nao");
            }
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(id).exec();
        }
        // alerta TLS GENERICO enviado pelo SERVIDOR: cliente so com TLS 1.2 e suite ECDHE_RSA; a chave do servidor e EC
        List<String> soRsa = List.of("-Djdk.tls.client.protocols=TLSv1.2", "-Djdk.tls.client.cipherSuites=TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        for (String t : List.of("senha", "ca-estranha")) {
            String r = sonda(Map.of(), soRsa, "sonda", t, a.papel());
            assertThat(r).as("alerta generico, %s", t).startsWith("2|").contains("resultado=FALHA_TECNICA:TLS:handshake_failure").contains("conforme=nao");
        }
        // falha TLS LOCAL do cliente (protocolo que o cliente nao habilita): sem alerta do servidor
        String local = sonda(Map.of(), List.of("-Djdk.tls.client.protocols=TLSv1.1"), "sonda", "ca-estranha", a.papel());
        assertThat(local).startsWith("2|").contains("resultado=FALHA_TECNICA:TLS:").contains("conforme=nao");
        // disponibilidade depois
        assertThat(sonda("sonda", "valida", a.papel())).startsWith("0|").contains("resultado=CONECTOU");
    }

    /** Contraprovas SIMULADAS do classificador (excecao montada, sem conexao): cada alerta generico -> FALHA_TECNICA:TLS:<alerta>. */
    @Test
    void classificacaoDeAlertasSimulados() {
        StringBuilder ev = new StringBuilder("SIMULADAS (excecao montada, sem conexao; FerramentaHomologacao.tentar):\n");
        for (String alerta : List.of("handshake_failure", "decrypt_error", "access_denied", "bad_certificate", "certificate_unknown")) {
            String r = FerramentaHomologacao.tentar(() -> {
                throw new SQLException("SSL error", "08006", new SSLHandshakeException("Received fatal alert: " + alerta));
            });
            assertThat(r).isEqualTo("FALHA_TECNICA:TLS:" + alerta);
            ev.append(alerta).append(" -> ").append(r).append('\n');
        }
        String ca = FerramentaHomologacao.tentar(() -> {
            throw new SQLException("SSL error", "08006", new SSLHandshakeException("Received fatal alert: unknown_ca"));
        });
        assertThat(ca).isEqualTo("RECUSADA_TLS:unknown_ca");
        String auth = FerramentaHomologacao.tentar(() -> {
            throw new SQLException("FATAL", "28P01");
        });
        assertThat(auth).isEqualTo("RECUSADA_AUTENTICACAO:28P01");
        String outra = FerramentaHomologacao.tentar(() -> {
            throw new SQLException("conexao recusada", "08001", new java.net.ConnectException("Connection refused"));
        });
        assertThat(outra).isEqualTo("FALHA_TECNICA:08001:ConnectException");
        ev.append("unknown_ca -> ").append(ca).append("\n28P01 -> ").append(auth).append("\nConnectException -> ").append(outra).append('\n');
        Evidencia.anexar("pghba", "classificacao_simulada", ev.toString());
    }

    /** nekkora_ops_ca e nekkora_ops_intake: SCRAM, cada um com a sua senha, pela regra propria; sem ampliar permissoes. */
    @Test
    void servicosDa016PorScramCadaUmComASuaCredencial() throws Exception {
        StringBuilder ev = new StringBuilder();
        for (String papel : List.of("nekkora_ops_ca", "nekkora_ops_intake")) {
            String m1 = marca();
            try (Connection c = DriverManager.getConnection(DB.url(), DB.tls(papel, SENHAS.get(papel))); var st = c.createStatement();
                 var rs = st.executeQuery("SELECT current_user || '|' || ssl || '|' || version FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
                rs.next();
                assertThat(rs.getString(1)).startsWith(papel + "|true|TLSv1.3");
            }
            String m2 = marca();
            String janela = logEntre(m1, m2);
            Matcher mm = Pattern.compile("connection authenticated: identity=\"" + papel + "\" method=scram-sha-256 \\(/etc/nekkora/pg_hba.conf:(\\d+)\\)")
                    .matcher(janela);
            assertThat(mm.find()).as("%s autenticado por scram no log", papel).isTrue();
            int linha = Integer.parseInt(mm.group(1));
            assertThat(hbaTeste.lines().toList().get(linha - 1)).as("regra propria de %s", papel).containsPattern("hostssl\\s+nekkora\\s+" + papel + "\\s");
            String outra = papel.equals("nekkora_ops_ca") ? SENHAS.get("nekkora_ops_intake") : SENHAS.get("nekkora_ops_ca");
            assertThatThrownBy(() -> DriverManager.getConnection(DB.url(), DB.tls(papel, outra)).close())
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("28P01"));
            assertThat(HB.valor("SELECT rolpassword LIKE 'SCRAM-SHA-256$%' FROM pg_authid WHERE rolname = '" + papel + "'")).isEqualTo("t");
            assertThat(HB.valor("SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls OR rolreplication FROM pg_roles WHERE rolname = '"
                    + papel + "'")).isEqualTo("f");
            ev.append(papel).append(": TLSv1.3, scram-sha-256 pela linha ").append(linha).append(" do pg_hba; senha do outro servico -> 28P01\n");
        }
        assertThat(membros()).as("participacoes em papeis inalteradas pelas senhas").isEqualTo(membrosAntes);
        String priv = HB.valor("SELECT 'intake_open=' || has_function_privilege('nekkora_ops_intake', 'public.ops_mfa_open(uuid,uuid,uuid,uuid,uuid)', 'EXECUTE')"
                + " || ' ca_open=' || has_function_privilege('nekkora_ops_ca', 'public.ops_mfa_open(uuid,uuid,uuid,uuid,uuid)', 'EXECUTE')"
                + " || ' intake_select_entradas=' || has_table_privilege('nekkora_ops_intake', 'ops.ops_intake_entries', 'SELECT')"
                + " || ' intake_select_chave=' || has_table_privilege('nekkora_ops_intake', 'ops.ops_intake_key_state', 'SELECT')");
        assertThat(priv).isEqualTo("intake_open=false ca_open=false intake_select_entradas=false intake_select_chave=false");
        Evidencia.anexar("pghba", "scram", ev + priv + "\nparticipacoes: " + membrosAntes);
    }
}
