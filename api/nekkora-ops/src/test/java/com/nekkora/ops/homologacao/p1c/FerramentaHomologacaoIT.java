package com.nekkora.ops.homologacao.p1c;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nekkora.ops.testsupport.OpsBanco;

/**
 * P1c (desenho P1a/P1b v2.2, sec. 6 e 9): a {@code FerramentaHomologacao} REAL em processo separado (como no job
 * "operacao"): {@code credencial} (PKCS#12 de ate 15 min, REGISTRADA pelo nekkora_ops_ca, aceita num ato real da CLI
 * {@code OpsCli}; recusada depois de vencida - aqui pela fixture declarada que leva o registro da emissao para alem do
 * vencimento; o vencimento em TEMPO REAL e conferido a parte com a imagem "operacao"), {@code operador}, {@code contato},
 * {@code entrada} (versao da chave pela configuracao; versao divergente recusada pela propria funcao, sem o servico ler
 * tabelas) e {@code sonda}. Base 001-016 exclusiva. Nenhuma senha/credencial vai para a saida da ferramenta nem para a evidencia.
 */
class FerramentaHomologacaoIT {

    static OpsBanco DB;
    static FerramentaProcesso F;
    static OpsBanco.Operador boot;

    @BeforeAll
    static void subir() {
        DB = OpsBanco.nova(OpsBanco.Forma.padrao());
        F = new FerramentaProcesso(DB, "ensaio-p1c-ferramenta", DB.senha("nekkora_ops_ca"), DB.senha("nekkora_ops_intake"));
        boot = DB.bootstrap();
    }

    @AfterAll
    static void encerrar() {
        if (F != null) {
            F.apagar();
        }
        if (DB != null) {
            DB.encerrar();
        }
    }

    static Processos.Resultado ferr(String... args) {
        return ferr(Map.of(), args);
    }

    static Processos.Resultado ferr(Map<String, String> sobrepor, String... args) {
        Processos.Resultado r = F.rodar(sobrepor, List.of(), args);
        String saida = r.saida().replaceAll("validade_ate=\\S+", "validade_ate=(registrada abaixo)");
        Evidencia.anexar("ferramenta", "execucoes", "ferramenta " + String.join(" ", args) + (sobrepor.isEmpty() ? "" : " sobreposicoes=" + sobrepor.keySet())
                + "\ncodigo=" + r.codigo() + "\n" + saida + (r.erro().isBlank() ? "" : "stderr: " + r.erro()));
        // nenhum segredo do ambiente de teste na saida
        for (String s : List.of(DB.senha("nekkora_ops_ca"), DB.senha("nekkora_ops_intake"))) {
            assertThat(r.saida() + r.erro()).doesNotContain(s);
        }
        return r;
    }

    static String campo(String saida, String nome) {
        Matcher m = Pattern.compile("(?:^| )" + nome + "=(\\S+)").matcher(saida);
        assertThat(m.find()).as("campo %s em: %s", nome, saida).isTrue();
        return m.group(1);
    }

    /** Certificado de dentro do PKCS#12 gravado pela ferramenta (senha lida do arquivo 0600 do mesmo diretorio). */
    static X509Certificate certificado(String papel) throws Exception {
        char[] senha = Files.readString(F.operadores().resolve(papel + ".senha"), StandardCharsets.UTF_8).toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(F.operadores().resolve(papel + ".p12"))) {
            ks.load(in, senha);
        }
        for (String alias : Collections.list(ks.aliases())) {
            if (ks.isKeyEntry(alias)) {
                return (X509Certificate) ks.getCertificate(alias);
            }
        }
        throw new IllegalStateException("PKCS#12 sem chave");
    }

    // ------------------------------------------------------------------ casos

    @Test
    void operadorContatoCredencialAtoRealEVencimento() throws Exception {
        String papel = "op_hmlv_" + OpsBanco.sufixo();
        DB.papelOperador(papel);   // DBA (operadores_016.sql faz o mesmo para op_hml_*)

        // operador: ops_operator_create + ops_operator_grant com o certificado do CADASTRAR
        Processos.Resultado op = ferr("operador", boot.papel(), papel, "VERIFICAR", "CONSULTAR");
        assertThat(op.codigo()).as(op.erro()).isZero();
        assertThat(op.saida()).contains("habilitacao=VERIFICAR resultado=OK").contains("habilitacao=CONSULTAR resultado=OK");
        UUID idOp = UUID.fromString(campo(op.saida(), "id"));
        assertThat(DB.valor(String.class, "SELECT db_role FROM ops.ops_operators WHERE operator_id = ?", idOp)).isEqualTo(papel);

        // contato: ops_contact_add (CONTRATACAO) pelo CADASTRAR
        OpsBanco.Org o = DB.organizacao();
        Processos.Resultado ct = ferr("contato", boot.papel(), o.id().toString(), o.admin().toString(), "contato-" + OpsBanco.hex(3) + "@p1c.test");
        assertThat(ct.codigo()).as(ct.erro()).isZero();
        UUID contato = UUID.fromString(campo(ct.saida(), "contato"));
        assertThat(DB.valor(String.class, "SELECT e.origin || '|' || (e.operator_id = ?)::text FROM ops.org_authorized_contact_events e "
                + "WHERE e.contact_id = ? AND e.kind = 1", boot.id(), contato)).isEqualTo("CONTRATACAO|true");

        // credencial: PKCS#12 de ate 15 min, registrada pelo servico da CA
        Instant antes = Instant.now();
        Processos.Resultado cr = ferr("credencial", papel);
        assertThat(cr.codigo()).as(cr.erro()).isZero();
        assertThat(cr.saida().trim()).matches("credencial=" + papel + " validade_ate=\\S+");
        Instant validade = Instant.parse(campo(cr.saida(), "validade_ate"));
        X509Certificate cert = certificado(papel);
        assertThat(cert.getNotAfter().toInstant()).isEqualTo(validade);
        assertThat(Duration.between(cert.getNotBefore().toInstant(), cert.getNotAfter().toInstant())).isLessThanOrEqualTo(Duration.ofMinutes(15));
        assertThat(validade).isBefore(antes.plus(Duration.ofMinutes(15)).plusSeconds(5));
        assertThat(cert.getSubjectX500Principal().getName()).contains("CN=" + papel);
        String registro = DB.valor(String.class, "SELECT (operator_id = ?)::text || '|' || (extract(epoch FROM not_after - not_before))::int "
                + "FROM ops.ops_operator_certificates WHERE serial = ?", idOp, new java.math.BigDecimal(cert.getSerialNumber()));
        assertThat(registro).as("emissao registrada").isEqualTo("true|900");
        Evidencia.anexar("ferramenta", "credencial", "papel=" + papel + " validade=" + Duration.between(cert.getNotBefore().toInstant(), cert.getNotAfter()
                .toInstant()) + " registrada=sim (operador certo, not_after - not_before = 900 s)");
        for (Path p : List.of(F.operadores().resolve(papel + ".p12"), F.operadores().resolve(papel + ".senha"))) {
            assertThat(p).isRegularFile();
        }

        // preparacao declarada (fixture temporal da suite): canal do contato com mais de 30 dias, para o ato ser elegivel
        DB.envelhecerContato(contato, Duration.ofDays(31));
        // ato REAL com a credencial (CLI OpsCli, PKCS#12 do diretorio de operadores): registro de retorno (VERIFICAR)
        Processos.Resultado ato = F.cli(papel, Map.of(), "retorno", contato.toString());
        Evidencia.anexar("ferramenta", "credencial", "OpsCli retorno com a credencial emitida -> codigo=" + ato.codigo() + " " + ato.saida().trim());
        assertThat(ato.codigo()).as(ato.saida() + ato.erro()).isZero();
        assertThat(ato.saida()).contains("resultado=OK");

        // vencida (fixture DECLARADA: registro da emissao levado para alem dos 15 min, como OpsBanco.vencerCertificado)
        try (Connection c = DB.superusuario(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SET LOCAL session_replication_role = replica");
            st.execute("UPDATE ops.ops_operator_certificates SET not_before = not_before - interval '16 minutes', not_after = not_after - interval '16 minutes', "
                    + "registered_at = registered_at - interval '16 minutes', idp_auth_time = idp_auth_time - interval '16 minutes' WHERE serial = "
                    + cert.getSerialNumber());
            c.commit();
        }
        Processos.Resultado vencida = F.cli(papel, Map.of(), "retorno", contato.toString());
        Evidencia.anexar("ferramenta", "credencial", "OpsCli retorno com a credencial VENCIDA (registro deslocado 16 min) -> codigo=" + vencida.codigo() + " "
                + vencida.saida().trim());
        assertThat(vencida.codigo()).isEqualTo(2);
        assertThat(vencida.saida()).contains("resultado=RECUSA").contains("motivo=CERTIFICADO_FORA_DA_VALIDADE");
    }

    @Test
    void entradaComVersaoDaConfiguracaoEVersaoDivergenteRecusada() {
        OpsBanco.Operador ver = DB.operador("hmlve", "VERIFICAR");
        assertThat(DB.valor(Integer.class, "SELECT current_version FROM ops.ops_intake_key_state")).isEqualTo(1);
        Processos.Resultado ok = ferr("entrada", ver.papel(), "1", OpsBanco.hex(32));
        assertThat(ok.codigo()).as(ok.saida() + ok.erro()).isZero();
        assertThat(ok.saida()).contains("resultado=OK");
        UUID entrada = UUID.fromString(campo(ok.saida(), "entrada"));
        assertThat(DB.valor(Integer.class, "SELECT origin_version FROM ops.ops_intake_entries WHERE entry_id = ?", entrada)).isEqualTo(1);

        Processos.Resultado div = ferr(Map.of("HOMOLOG_OPS_INTAKE_VERSAO", "2"), "entrada", ver.papel(), "1", OpsBanco.hex(32));
        assertThat(div.codigo()).isEqualTo(2);
        assertThat(div.saida()).contains("entrada=- ").contains("motivo=ENTRADA_INVALIDA");

        Processos.Resultado m3 = ferr("entrada", ver.papel(), "3");
        assertThat(m3.codigo()).as(m3.saida() + m3.erro()).isZero();
        // o servico de entrada nao le a tabela da chave (nem outras): a versao so pode vir da configuracao
        assertThat(DB.valor(Boolean.class, "SELECT has_table_privilege('nekkora_ops_intake', 'ops.ops_intake_key_state', 'SELECT')")).isFalse();
        assertThat(DB.valor(Boolean.class, "SELECT has_table_privilege('nekkora_ops_intake', 'ops.ops_intake_entries', 'SELECT')")).isFalse();
    }

    @Test
    void sondasPelaFerramentaERecusasDeUso() {
        OpsBanco.Operador x = DB.operador("hmlsx", "CONSULTAR");
        OpsBanco.Operador y = DB.operador("hmlsy", "CONSULTAR");
        assertThat(ferr("sonda", "valida", x.papel()).saida()).contains("resultado=CONECTOU").contains("conforme=sim");
        assertThat(ferr("sonda", "senha", x.papel()).saida()).contains("resultado=RECUSADA_AUTENTICACAO:28000").contains("conforme=sim");
        assertThat(ferr("sonda", "ca-estranha", x.papel()).saida()).contains("resultado=RECUSADA_TLS:unknown_ca").contains("conforme=sim");
        assertThat(ferr("sonda", "cn-diferente", x.papel(), y.papel()).saida()).contains("resultado=RECUSADA_AUTENTICACAO:28000").contains("conforme=sim");
        // so ensaio-*; papel fora do formato; subcomando desconhecido
        Processos.Resultado fora = ferr(Map.of("NEKKORA_AMBIENTE", "hml03"), "sonda", "valida", x.papel());
        assertThat(fora.codigo()).isEqualTo(3);
        assertThat(fora.erro()).contains("so roda em ambiente ensaio-*");
        assertThat(ferr("credencial", "admin_cluster").erro()).contains("papel de operador invalido");
        assertThat(ferr("inexistente", x.papel()).codigo()).isEqualTo(64);
    }
}
