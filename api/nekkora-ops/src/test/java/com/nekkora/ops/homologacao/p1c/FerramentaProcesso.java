package com.nekkora.ops.homologacao.p1c;

import com.nekkora.ops.homologacao.FerramentaHomologacao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.nekkora.ops.testsupport.OpsBanco;
import com.nekkora.ops.testsupport.Pki;

/**
 * A {@link FerramentaHomologacao} REAL num processo Java separado (main, variaveis de ambiente, codigo de saida e saida
 * padrao, como no job "operacao"), contra uma base de teste. A CA operacional (certificado e chave) e as senhas dos
 * servicos da 016 vao para arquivos/variaveis EFEMEROS num diretorio temporario fora do repositorio; nada disso e
 * gravado na evidencia (so a saida da ferramenta, que nao imprime segredo).
 */
final class FerramentaProcesso {

    final Path dir;
    final Map<String, String> env = new HashMap<>();

    FerramentaProcesso(OpsBanco db, String ambiente, String senhaCa, String senhaIntake) {
        try {
            dir = Files.createTempDirectory("p1c-ferramenta-");
            Path operadores = Files.createDirectories(dir.resolve("operadores"));
            Path crt = Pki.escrever(dir, "ops-ca.crt", Pki.pem(db.caOps().certificado()));
            Path key = Pki.escrever(dir, "ops-ca.key", Pki.pemChave(db.caOps().chaves().getPrivate()));
            env.put("NEKKORA_AMBIENTE", ambiente);
            env.put("NEKKORA_OPS_DB_URL", db.url());
            env.put("NEKKORA_OPS_RAIZ_SERVIDOR", db.raizServidor().toString());
            env.put("HOMOLOG_OPS_CA_CRT", crt.toString());
            env.put("HOMOLOG_OPS_CA_KEY", key.toString());
            env.put("HOMOLOG_OPS_CA_SENHA", senhaCa);
            env.put("HOMOLOG_OPS_INTAKE_SENHA", senhaIntake);
            env.put("HOMOLOG_OPS_INTAKE_VERSAO", "1");
            env.put("HOMOLOG_OPERADORES", operadores.toString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Path operadores() {
        return Path.of(env.get("HOMOLOG_OPERADORES"));
    }

    static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Roda a ferramenta; {@code jvm}: opcoes extras da JVM (ex.: protocolos TLS do cliente). */
    Processos.Resultado rodar(Map<String, String> sobrepor, List<String> jvm, String... args) {
        List<String> cmd = new ArrayList<>(List.of(java()));
        cmd.addAll(jvm);
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), FerramentaHomologacao.class.getName()));
        cmd.addAll(List.of(args));
        Map<String, String> e = new HashMap<>(env);
        e.putAll(sobrepor);
        return Processos.rodar(cmd, null, e, null, 180);
    }

    Processos.Resultado rodar(String... args) {
        return rodar(Map.of(), List.of(), args);
    }

    /** Uma CLI real (OpsCli) com a credencial PKCS#12 gravada pela ferramenta no diretorio de operadores. */
    Processos.Resultado cli(String papel, Map<String, String> sobrepor, String... args) {
        List<String> cmd = new ArrayList<>(List.of(java(), "-cp", System.getProperty("java.class.path"), "com.nekkora.ops.cli.OpsCli"));
        cmd.addAll(List.of(args));
        Map<String, String> e = new HashMap<>();
        e.put("NEKKORA_OPS_DB_URL", env.get("NEKKORA_OPS_DB_URL"));
        e.put("NEKKORA_OPS_RAIZ_SERVIDOR", env.get("NEKKORA_OPS_RAIZ_SERVIDOR"));
        e.put("NEKKORA_OPS_PAPEL", papel);
        e.put("NEKKORA_OPS_CREDENCIAL", operadores().resolve(papel + ".p12").toString());
        try {
            e.put("NEKKORA_OPS_CREDENCIAL_SENHA", Files.readString(operadores().resolve(papel + ".senha"), StandardCharsets.UTF_8));
        } catch (IOException x) {
            throw new IllegalStateException(x);
        }
        e.put("NEKKORA_OPS_LOG", dir.resolve("cli.log").toString());
        e.putAll(sobrepor);
        return Processos.rodar(cmd, null, e, null, 180);
    }

    void apagar() {
        try (var w = Files.walk(dir)) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
