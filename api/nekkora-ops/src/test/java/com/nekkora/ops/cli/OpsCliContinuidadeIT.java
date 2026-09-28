package com.nekkora.ops.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Continuidade pela CLI (achado da revisao): o UUID da solicitacao sai do proprio ato ABRIR, no {@link ExecutorAtos.Resultado}
 * e na saida da CLI, e os atos seguintes usam SO esse valor. Nenhuma consulta administrativa entra no caminho; a
 * leitura como superusuario aparece so DEPOIS, como conferencia declarada.
 *
 * <p>Contraprova embutida: duas solicitacoes abertas em sequencia para alvos diferentes. Um id tirado de "ultima
 * solicitacao aberta" (o que os testes faziam antes) apontaria a segunda; o fluxo continua a PRIMEIRA pelo UUID da
 * saida e confere que o alvo verificado e o dela.
 */
class OpsCliContinuidadeIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);
    private static final Pattern SAIDA = Pattern.compile(
            "^solicitacao=(\\S+) resultado=(\\S+) origem=(\\S+) estado=(\\S+) motivo=(\\S+)$");

    private record Execucao(int codigo, String solicitacao, String resultado, String estado, String motivo) {
    }

    private static Execucao cli(OpsBanco.Operador o, String... args) throws Exception {
        try (Credencial cred = DB.credencial(o)) {
            Properties p = cred.propriedades(DB.raizServidor());
            Path log = Files.createTempFile("nekkora-ops-cli-", ".log");
            Map<String, String> env = new HashMap<>();
            env.put("NEKKORA_OPS_DB_URL", DB.url());
            env.put("NEKKORA_OPS_PAPEL", o.papel());
            env.put("NEKKORA_OPS_RAIZ_SERVIDOR", DB.raizServidor().toString());
            env.put("NEKKORA_OPS_CREDENCIAL", p.getProperty("sslkey"));
            env.put("NEKKORA_OPS_CREDENCIAL_SENHA", p.getProperty("sslpassword"));
            env.put("NEKKORA_OPS_LOG", log.toString());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int codigo = OpsCli.executar(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(err, true, StandardCharsets.UTF_8));
            String linha = out.toString(StandardCharsets.UTF_8).strip();
            Matcher m = SAIDA.matcher(linha);
            assertThat(m.matches()).as("formato da saida da CLI: [%s] (erro: %s)", linha, err.toString(StandardCharsets.UTF_8)).isTrue();
            // a senha da credencial nunca aparece na saida nem no log local
            assertThat(linha).doesNotContain(p.getProperty("sslpassword"));
            assertThat(Files.readString(log)).doesNotContain(p.getProperty("sslpassword"));
            Files.deleteIfExists(log);
            return new Execucao(codigo, m.group(1), m.group(2), m.group(4), m.group(5));
        }
    }

    @Test
    void abrirDevolveOUuidEOsAtosSeguintesContinuamSoComEle() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al1 = C.alvo();
        Cenarios.Alvo al2 = C.alvo();

        Execucao a1 = cli(eq.verificador(), "abrir", al1.org().id().toString(), al1.titular().toString(), al1.contatoTitular().toString());
        assertThat(a1.codigo()).isZero();
        assertThat(a1.resultado()).isEqualTo("OK");
        assertThat(a1.estado()).isEqualTo("SOLICITADA");
        UUID s1 = UUID.fromString(a1.solicitacao());
        // contraprova: uma segunda abertura, para outro alvo, torna-se a "ultima solicitacao"
        Execucao a2 = cli(eq.verificador(), "abrir", al2.org().id().toString(), al2.titular().toString(), al2.contatoTitular().toString());
        assertThat(a2.codigo()).isZero();
        UUID s2 = UUID.fromString(a2.solicitacao());
        assertThat(s2).isNotEqualTo(s1);

        // continuidade: so o UUID da saida de abrir
        Execucao v = cli(eq.verificador(), "verificar", s1.toString(), "true", al1.segundoContato().toString(), "true");
        assertThat(v.codigo()).isZero();
        assertThat(v.solicitacao()).isEqualTo(s1.toString());
        assertThat(v.estado()).isEqualTo("VERIFICADA");
        Execucao pa = cli(eq.aprovador(), "pre-aprovar", s1.toString());
        assertThat(pa.codigo()).isZero();
        assertThat(pa.estado()).isEqualTo("PRE_APROVADA");
        Execucao q = cli(eq.executor(), "consultar", s1.toString());
        assertThat(q.codigo()).isZero();
        assertThat(q.solicitacao()).isEqualTo(s1.toString());
        assertThat(q.estado()).isEqualTo("PRE_APROVADA");

        // conferencia declarada (superusuario, depois do fluxo): o UUID devolvido e o da solicitacao do alvo 1
        assertThat(DB.valor(UUID.class, "SELECT target_user_id FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", s1)).isEqualTo(al1.titular());
        assertThat(DB.valor(UUID.class, "SELECT target_user_id FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", s2)).isEqualTo(al2.titular());
        assertThat(C.estado(s2)).as("a outra solicitacao nao foi tocada").isEqualTo("SOLICITADA");
        assertThat(DB.valor(UUID.class, "SELECT request_id FROM ops.ops_mfa_recovery_requests WHERE target_user_id IN (?, ?) ORDER BY opened_at DESC LIMIT 1",
                al1.titular(), al2.titular())).as("a 'ultima solicitacao' teria dado o id errado").isEqualTo(s2);
    }

    @Test
    void abrirRecusadoNaoInventaSolicitacao() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        Cenarios.Alvo outra = C.alvo();
        // contato de outra organizacao: recusa, sem solicitacao
        Execucao r = cli(eq.verificador(), "abrir", al.org().id().toString(), al.titular().toString(), outra.contatoTitular().toString());
        assertThat(r.codigo()).isEqualTo(2);
        assertThat(r.resultado()).isEqualTo("RECUSA");
        assertThat(r.solicitacao()).isEqualTo("-");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_requests WHERE target_user_id = ?", al.titular())).isZero();
    }
}
