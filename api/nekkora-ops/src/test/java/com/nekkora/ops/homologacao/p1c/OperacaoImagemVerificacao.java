package com.nekkora.ops.homologacao.p1c;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import com.nekkora.ops.testsupport.OpsBanco;
import com.nekkora.ops.testsupport.Pki;

/**
 * P1c (desenho P1a/P1b v2.2, sec. 6; plano, preparar-016): a IMAGEM "operacao" de TESTE ({@code nekkora-p1c-operacao:p1c},
 * construida de {@code api/e2e/Dockerfile.operacao}) executada como o job "operacao" do compose.016.yml: {@code docker run
 * --rm --read-only --tmpfs /tmp}, IP fixo {@code <prefixo>.33} na rede da base, volume EXCLUSIVO "operadores" (rotulado)
 * preparado pelo MESMO comando do operadores-init, CA operacional e senhas montadas/passadas so para ele (arquivos
 * temporarios fora do repositorio). SQL real da homologacao pelo cliente ({@code operadores_016.sql}, {@code fixture_016.sql}).
 *
 * <p>NAO faz parte da regressao Maven (nome sem IT): depende da imagem construida antes; execucao explicita
 * ({@code -Dtest=OperacaoImagemVerificacao}). O ultimo caso espera o vencimento REAL da credencial (15 min).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperacaoImagemVerificacao {

    static final String PREFIXO = "10.252.46";
    static final String AMBIENTE = "ensaio-p1c-operacao";
    static final String IMAGEM = System.getProperty("nekkora.p1c.imagem.operacao", "nekkora-p1c-operacao:p1c");
    static BancoHomologacao HB;
    static OpsBanco DB;
    static Path tmp;
    static String volume;
    static String org;
    static String adm1;
    static String contato;
    static Instant credencialVer;

    @BeforeAll
    static void subir() throws Exception {
        HB = BancoHomologacao.subir(PREFIXO, OpsBanco.HBA_PADRAO, "operacao", Map.of());
        DB = HB.db;
        tmp = Files.createTempDirectory("p1c-operacao-");
        Pki.escrever(tmp, "ca.crt", DB.raizServidorPem());
        Pki.escrever(tmp, "ops-ca.crt", Pki.pem(DB.caOps().certificado()));
        Pki.escrever(tmp, "ops-ca.key", Pki.pemChave(DB.caOps().chaves().getPrivate()));
        // o mesmo conteudo que lib/segredos.py grava em operacao.env (senhas efemeras da base de teste)
        Files.writeString(tmp.resolve("operacao.env"), String.join("\n", "NEKKORA_AMBIENTE=" + AMBIENTE,
                "NEKKORA_OPS_DB_URL=jdbc:postgresql://postgres:5432/nekkora", "NEKKORA_OPS_RAIZ_SERVIDOR=/tls/ca.crt", "HOMOLOG_OPS_CA_CRT=/tls/ops-ca.crt",
                "HOMOLOG_OPS_CA_KEY=/tls/ops-ca.key", "HOMOLOG_OPS_CA_SENHA=" + DB.senha("nekkora_ops_ca"),
                "HOMOLOG_OPS_INTAKE_SENHA=" + DB.senha("nekkora_ops_intake"), "HOMOLOG_OPERADORES=/operadores", "NEKKORA_OPS_LOG=/operadores/cli.log",
                "HOMOLOG_OPS_INTAKE_VERSAO=1", ""), StandardCharsets.UTF_8);
        volume = "nekkora-p1c-operadores-" + OpsBanco.hex(3);
        Processos.Resultado v = docker("volume", "create", "--label", "nekkora.p1c=operacao", volume);
        assertThat(v.codigo()).as(v.erro()).isZero();
        Evidencia.anexar("operacao", "recursos", "imagem=" + IMAGEM + " volume=" + volume + " (rotulo nekkora.p1c=operacao) rede=" + HB.rede.getId()
                + " prefixo=" + PREFIXO);
    }

    @AfterAll
    static void encerrar() throws Exception {
        if (volume != null) {
            Processos.Resultado r = docker("volume", "rm", volume);
            Evidencia.anexar("operacao", "recursos", "volume " + volume + " removido: codigo=" + r.codigo());
        }
        if (HB != null) {
            HB.close();
        }
        if (tmp != null) {
            try (var w = Files.walk(tmp)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    static Processos.Resultado docker(String... args) {
        List<String> c = new ArrayList<>(List.of(Processos.dockerReal()));
        // no Windows o ProcessBuilder NAO escapa aspas internas: sem isto o script do "sh -ec" chegava truncado ao docker
        // (erro de sintaxe do sh, observado na tentativa t12 do P1c)
        boolean windows = java.io.File.separatorChar == '\\';
        for (String x : args) {
            c.add(windows ? x.replace("\"", "\\\"") : x);
        }
        return Processos.rodar(c, null, Map.of("MSYS_NO_PATHCONV", "1"), null, 1200);
    }

    static String bind(String arquivo, String destino) {
        return "type=bind,src=" + tmp.resolve(arquivo).toAbsolutePath() + ",dst=" + destino + ",readonly";
    }

    /** Como "compose --profile operacao run --rm -T operacao ..." (compose.016.yml). */
    static Processos.Resultado operacao(List<String> extra, String... args) {
        List<String> c = new ArrayList<>(List.of("run", "--rm", "--label", "nekkora.p1c=operacao", "--network", HB.rede.getId(), "--ip", PREFIXO + ".33",
                "--read-only", "--tmpfs", "/tmp", "--env-file", tmp.resolve("operacao.env").toString(),
                "--mount", bind("ca.crt", "/tls/ca.crt"), "--mount", bind("ops-ca.crt", "/tls/ops-ca.crt"), "--mount", bind("ops-ca.key", "/tls/ops-ca.key"),
                "-v", volume + ":/operadores"));
        c.addAll(extra);
        c.add(IMAGEM);
        c.addAll(List.of(args));
        Processos.Resultado r = docker(c.toArray(String[]::new));
        String saida = r.saida().replaceAll("validade_ate=\\S+", "validade_ate=(omitida)");
        Evidencia.anexar("operacao", "execucoes", "operacao " + String.join(" ", args) + "\ncodigo=" + r.codigo() + "\n" + saida
                + (r.erro().isBlank() ? "" : "stderr: " + r.erro().lines().filter(l -> !l.isBlank()).reduce("", (p, q) -> p + q + "\n")));
        for (String s : List.of(DB.senha("nekkora_ops_ca"), DB.senha("nekkora_ops_intake"))) {
            assertThat(r.saida() + r.erro()).doesNotContain(s);
        }
        return r;
    }

    static Processos.Resultado ferramenta(String... args) {
        List<String> a = new ArrayList<>(List.of("com.nekkora.ops.homologacao.FerramentaHomologacao"));
        a.addAll(List.of(args));
        return operacao(List.of(), a.toArray(String[]::new));
    }

    /** Como "homologacao.sh operacao <id> cli ...": a senha do PKCS#12 so e lida DENTRO do container. */
    static Processos.Resultado cli(String papel, String... args) {
        List<String> a = new ArrayList<>(List.of("-ec", "export NEKKORA_OPS_CREDENCIAL_SENHA=\"$(cat \"/operadores/$NEKKORA_OPS_PAPEL.senha\")\"; "
                + "exec java -cp \"/app/lib/*:/app/classes\" com.nekkora.ops.cli.OpsCli \"$@\"", "--"));
        a.addAll(List.of(args));
        return operacao(List.of("-e", "NEKKORA_OPS_PAPEL=" + papel, "-e", "NEKKORA_OPS_CREDENCIAL=/operadores/" + papel + ".p12", "--entrypoint", "sh"),
                a.toArray(String[]::new));
    }

    static String campo(String s, String nome) {
        Matcher m = Pattern.compile("(?:^| )" + nome + "=(\\S+)").matcher(s);
        assertThat(m.find()).as("%s em %s", nome, s).isTrue();
        return m.group(1);
    }

    // ------------------------------------------------------------------ casos

    @Test
    @Order(1)
    void volumeDeOperadoresComDonoEPermissaoConferidosNoLinux() {
        // entrypoint EXATO do operadores-init (compose.016.yml): root, sem rede
        Processos.Resultado r = docker("run", "--rm", "--label", "nekkora.p1c=operacao", "--user", "root", "--network", "none", "-v", volume + ":/operadores",
                "--entrypoint", "sh", IMAGEM, "-ec", "install -d -o nekkora -g nekkora -m 700 /operadores && stat -c '%U %a' /operadores");
        Evidencia.anexar("operacao", "volumes", "operadores-init -> codigo=" + r.codigo() + " saida=" + r.saida().trim());
        assertThat(r.saida().trim()).isEqualTo("nekkora 700");
    }

    @Test
    @Order(2)
    void sqlRealDosOperadoresEDaOrganizacaoSintetica() {
        BancoHomologacao.Exec op = HB.psql("p1c-operadores", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-f", "/sql/operadores_016.sql");
        Evidencia.anexar("operacao", "sql", "operadores_016.sql codigo=" + op.codigo() + "\n" + op.saida());
        assertThat(op.codigo()).as(op.saida()).isZero();
        assertThat(op.saida()).containsPattern("bootstrap=[0-9a-f-]{36}");
        org = UUID.randomUUID().toString();
        adm1 = UUID.randomUUID().toString();
        List<String> a = new ArrayList<>(List.of("-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-v", "ambiente=" + AMBIENTE, "-v", "org=" + org,
                "-v", "adm1=" + adm1));
        for (String k : List.of("adm2", "membro", "m_adm1", "m_adm2", "m_membro")) {
            a.addAll(List.of("-v", k + "=" + UUID.randomUUID()));
        }
        a.addAll(List.of("-v", "email_adm1=titular-p1c@homologacao-016.test", "-v", "email_adm2=outro-p1c@homologacao-016.test", "-v",
                "email_membro=membro-p1c@homologacao-016.test", "-v", "phc_adm1=hash-sintetico-" + OpsBanco.hex(8), "-v",
                "phc_adm2=hash-sintetico-" + OpsBanco.hex(8), "-f", "/sql/fixture_016.sql"));
        BancoHomologacao.Exec fx = HB.psql("p1c-fixture016", a.toArray(String[]::new));
        Evidencia.anexar("operacao", "sql", "fixture_016.sql codigo=" + fx.codigo() + "\n" + fx.saida());
        assertThat(fx.codigo()).as(fx.saida()).isZero();
        assertThat(fx.saida()).contains("fixture_016: organizacao=" + org + " usuarios=3");
        assertThat(HB.valor("SELECT count(*) FROM organization_role_assignments a JOIN roles r ON r.role_id = a.role_id WHERE a.organization_id = '"
                + org + "' AND r.code = 'ORG_ADMIN' AND a.status = 1")).isEqualTo("2");
    }

    @Test
    @Order(3)
    void credencialOperadoresEContatoPelaImagem() {
        Processos.Resultado c = ferramenta("credencial", "op_hml_cad");
        assertThat(c.codigo()).as(c.erro()).isZero();
        assertThat(c.saida().trim()).matches("credencial=op_hml_cad validade_ate=\\S+");
        int ok = 0;
        for (String[] o : new String[][] {{"op_hml_ver", "VERIFICAR", "CANCELAR", "CONSULTAR"}, {"op_hml_apr", "APROVAR"}, {"op_hml_exe", "EXECUTAR"}}) {
            List<String> a = new ArrayList<>(List.of("operador", "op_hml_cad"));
            a.addAll(List.of(o));
            Processos.Resultado r = ferramenta(a.toArray(String[]::new));
            assertThat(r.codigo()).as(r.saida() + r.erro()).isZero();
            ok += (int) r.saida().lines().filter(l -> l.contains("resultado=OK")).count();
        }
        assertThat(ok).as("5 habilitacoes (como h016.sh preparar)").isEqualTo(5);
        Processos.Resultado ct = ferramenta("contato", "op_hml_cad", org, adm1, "titular-p1c@homologacao-016.test");
        assertThat(ct.codigo()).as(ct.erro()).isZero();
        contato = campo(ct.saida(), "contato");
        assertThat(HB.valor("SELECT count(*) FROM ops.ops_operator_certificates c JOIN ops.ops_operators o USING (operator_id) WHERE o.db_role = 'op_hml_cad'"))
                .as("credenciais do CADASTRAR registradas (a emitida + as de cada chamada)").isNotEqualTo("0");
    }

    @Test
    @Order(4)
    void entradaESondasPelaImagem() {
        Processos.Resultado e = ferramenta("entrada", "op_hml_ver", "1", OpsBanco.hex(32));
        assertThat(e.codigo()).as(e.saida() + e.erro()).isZero();
        assertThat(e.saida()).contains("resultado=OK");
        Processos.Resultado div = operacao(List.of("-e", "HOMOLOG_OPS_INTAKE_VERSAO=2"), "com.nekkora.ops.homologacao.FerramentaHomologacao", "entrada",
                "op_hml_ver", "1", OpsBanco.hex(32));
        assertThat(div.codigo()).isEqualTo(2);
        assertThat(div.saida()).contains("motivo=ENTRADA_INVALIDA");
        assertThat(ferramenta("sonda", "valida", "op_hml_ver").saida()).contains("resultado=CONECTOU esperado=CONECTOU conforme=sim");
        assertThat(ferramenta("sonda", "senha", "op_hml_ver").saida()).contains("resultado=RECUSADA_AUTENTICACAO:28000").contains("conforme=sim");
        // repetida: a perda do alerta observada antes era uma corrida (RST); a sonda precisa ser deterministica no container
        for (int i = 0; i < 10; i++) {
            Processos.Resultado ca = ferramenta("sonda", "ca-estranha", "op_hml_ver");
            assertThat(ca.codigo()).as("repeticao %s: %s", i, ca.saida() + ca.erro()).isZero();
            assertThat(ca.saida()).contains("resultado=RECUSADA_TLS:unknown_ca").contains("conforme=sim");
        }
        assertThat(ferramenta("sonda", "cn-diferente", "op_hml_ver", "op_hml_apr").saida()).contains("resultado=RECUSADA_AUTENTICACAO:28000")
                .contains("conforme=sim");
        assertThat(ferramenta("sonda", "valida", "op_hml_ver").saida()).contains("conforme=sim");
    }

    /**
     * Estresse da sonda ca-estranha no container (execucao explicita: -Dnekkora.p1c.estresse=N): distribuicao das
     * tentativas por execucao; todas precisam terminar em unknown_ca (a repeticao so cobre falha de transporte).
     */
    @Test
    @Order(4)
    void estresseDaSondaCaEstranha() {
        int n = Integer.getInteger("nekkora.p1c.estresse", 0);
        java.util.Map<String, Integer> dist = new java.util.TreeMap<>();
        for (int i = 0; i < n; i++) {
            Processos.Resultado ca = ferramenta("sonda", "ca-estranha", "op_hml_ver");
            assertThat(ca.saida()).as("execucao %s", i).contains("resultado=RECUSADA_TLS:unknown_ca").contains("conforme=sim");
            dist.merge(campo(ca.saida(), "tentativas"), 1, Integer::sum);
        }
        Evidencia.anexar("operacao", "estresse_ca_estranha", "execucoes=" + n + " distribuicao de tentativas por execucao (tentativas=quantidade): " + dist);
    }

    @Test
    @Order(5)
    void cliComCredencialDaImagemEPermissoesNoVolume() {
        Processos.Resultado c = ferramenta("credencial", "op_hml_ver");
        assertThat(c.codigo()).isZero();
        credencialVer = Instant.parse(campo(c.saida(), "validade_ate"));
        DB.envelhecerContato(UUID.fromString(contato), Duration.ofDays(31));   // preparacao declarada: canal elegivel
        Processos.Resultado ato = cli("op_hml_ver", "retorno", contato);
        assertThat(ato.codigo()).as(ato.saida() + ato.erro()).isZero();
        assertThat(ato.saida()).contains("resultado=OK");
        Processos.Resultado st = docker("run", "--rm", "--label", "nekkora.p1c=operacao", "--network", "none", "--read-only", "-v", volume + ":/operadores",
                "--entrypoint", "sh", IMAGEM, "-ec", "stat -c '%U %a %n' /operadores /operadores/*");
        Evidencia.anexar("operacao", "volumes", "permissoes no volume operadores:\n" + st.saida());
        assertThat(st.saida()).contains("nekkora 700 /operadores").contains("nekkora 600 /operadores/op_hml_ver.p12")
                .contains("nekkora 600 /operadores/op_hml_ver.senha").contains("nekkora 600 /operadores/op_hml_cad.p12");
    }

    /** Vencimento em TEMPO REAL: depois de validade_ate, o mesmo PKCS#12 nao autoriza mais nenhum ato. */
    @Test
    @Order(6)
    void credencialVencidaEmTempoRealERecusada() throws Exception {
        assertThat(credencialVer).isNotNull();
        long espera = Duration.between(Instant.now(), credencialVer.plusSeconds(30)).toMillis();
        Evidencia.anexar("operacao", "vencimento_real", "validade_ate da credencial de op_hml_ver: " + credencialVer + "; espera ate " + credencialVer.plusSeconds(30));
        if (espera > 0) {
            Thread.sleep(espera);
        }
        int antes = DB.logDoServidor().length();
        Processos.Resultado ato = cli("op_hml_ver", "retorno", contato);
        String janela = DB.logDoServidor().substring(antes);
        String linhas = janela.lines().filter(l -> l.toLowerCase().contains("certificate")).map(l -> l.replaceFirst("^\\S+ \\S+ \\S+ ", ""))
                .reduce("", (p, q) -> p + q + "\n");
        Evidencia.anexar("operacao", "vencimento_real", "agora=" + Instant.now() + " OpsCli retorno com o PKCS#12 vencido -> codigo=" + ato.codigo() + " "
                + ato.saida().trim() + "\nlog do servidor durante o ato:\n" + linhas);
        assertThat(Instant.now()).isAfter(credencialVer);
        assertThat(ato.erro()).as("a CLI executou (sem erro do shell)").doesNotContain("syntax error");
        assertThat(ato.saida()).as("a CLI respondeu").contains("retorno=- resultado=").doesNotContain("resultado=OK");
        assertThat(ato.codigo()).isNotZero();
        assertThat(janela).as("o servidor recusou o certificado vencido").contains("certificate has expired");
    }
}
