package com.nekkora.ops.homologacao.p1c;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Base DESCARTAVEL com a forma da homologacao, para o P1c: PostgreSQL 16 (001-016 pelo mecanismo da suite, como
 * superusuario no initdb) com {@code cluster_name=nekkora-homologacao}, superusuario {@code admin_cluster} (senha
 * efemera), TLS, numa rede Docker PROPRIA com sub-rede fixa ({@code <prefixo>.0/24}, conferida contra as redes existentes
 * antes de criar), servidor em {@code <prefixo>.10} com o alias {@code postgres}, e um container CLIENTE psql em
 * {@code <prefixo>.31} com {@code ops/homologacao/sql} em {@code /sql} e a raiz do servidor em {@code /tls/ca.crt} -
 * os mesmos caminhos e a mesma conexao que o job "ferramentas" do runner usa. Recursos rotulados {@code nekkora.p1c}.
 */
final class BancoHomologacao implements AutoCloseable {

    static final String CONEXAO = "host=postgres port=5432 dbname=nekkora user=admin_cluster sslmode=verify-full sslrootcert=/tls/ca.crt";

    final String prefixo;
    final Network rede;
    final OpsBanco db;
    final GenericContainer<?> cliente;

    private BancoHomologacao(String prefixo, Network rede, OpsBanco db, GenericContainer<?> cliente) {
        this.prefixo = prefixo;
        this.rede = rede;
        this.db = db;
        this.cliente = cliente;
    }

    /**
     * Sobe a base e o cliente. {@code rotulosCliente}: rotulos extras do container cliente (ex.: os que o runner procura
     * em "fixture"); {@code argumentos}: parametros extras do servidor ({@code -c}).
     */
    static BancoHomologacao subir(String prefixo, String hba, String teste, Map<String, String> rotulosCliente, String... argumentos) {
        String cidr = prefixo + ".0/24";
        Processos.exigirSemSobreposicao(cidr);
        Map<String, String> rotulos = Map.of("nekkora.p1c", teste);
        Network rede = Network.builder().createNetworkCmdModifier(cmd -> cmd
                .withIpam(new com.github.dockerjava.api.model.Network.Ipam().withConfig(
                        new com.github.dockerjava.api.model.Network.Ipam.Config().withSubnet(cidr)))
                .withLabels(rotulos)).build();
        OpsBanco db = OpsBanco.nova(OpsBanco.Forma.homologacao(hba, rotulos).comRede(rede, "postgres", prefixo + ".10").comArgumentos(argumentos));
        Map<String, String> rc = new LinkedHashMap<>(rotulos);
        rc.putAll(rotulosCliente);
        GenericContainer<?> c = new GenericContainer<>(OpsBanco.IMAGEM)
                .withNetwork(rede)
                .withCreateContainerCmdModifier(cmd -> cmd.withIpv4Address(prefixo + ".31"))
                .withLabels(rc)
                .withEnv("PGPASSWORD", db.senhaSuperusuario())
                .withCommand("tail", "-f", "/dev/null")
                .withCopyToContainer(Transferable.of(db.raizServidorPem()), "/tls/ca.crt");
        try (Stream<Path> fs = Files.list(OpsBanco.raizDoRepositorio().resolve("ops/homologacao/sql"))) {
            for (Path f : fs.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                c = c.withCopyFileToContainer(MountableFile.forHostPath(f), "/sql/" + f.getFileName());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        c.start();
        BancoHomologacao h = new BancoHomologacao(prefixo, rede, db, c);
        if (!(prefixo + ".10").equals(db.ipNaRede())) {
            throw new IllegalStateException("servidor fora do IP fixo: " + db.ipNaRede());
        }
        return h;
    }

    String ipServidor() {
        return prefixo + ".10";
    }

    /** Saida combinada (stdout+stderr, na ordem, como o runner com 2>&1) e codigo de um comando psql no cliente. */
    record Exec(int codigo, String saida) {
        /** A saida no formato que o runner grava para classificar: linhas do psql e a ultima "codigo=<n>". */
        String paraClassificar() {
            return saida + (saida.endsWith("\n") || saida.isEmpty() ? "" : "\n") + "codigo=" + codigo + "\n";
        }
    }

    /** psql no cliente com a conexao do runner; {@code app} vira application_name (para achar o backend). */
    Exec psql(String app, String... args) {
        return psqlConexao(app, CONEXAO, args);
    }

    Exec psqlConexao(String app, String conexao, String... args) {
        List<String> cmd = new ArrayList<>(List.of("sh", "-c", "PGAPPNAME=\"$1\"; export PGAPPNAME; shift; exec psql \"$@\" 2>&1", "sh",
                app == null ? "p1c" : app, conexao));
        cmd.addAll(List.of(args));
        try {
            Container.ExecResult r = cliente.execInContainer(StandardCharsets.UTF_8, cmd.toArray(String[]::new));
            return new Exec(r.getExitCode(), r.getStdout() + r.getStderr());
        } catch (Exception e) {
            throw new IllegalStateException("exec do psql falhou", e);
        }
    }

    /** Copia um arquivo (ex.: copia de TESTE de um script) para o diretorio /sql do cliente. */
    void copiarParaSql(String nome, String conteudo) {
        cliente.copyFileToContainer(Transferable.of(conteudo), "/sql/" + nome);
    }

    /** Uma consulta como superusuario por conexao NOVA (valor da primeira coluna da primeira linha). */
    String valor(String sql) {
        try (Connection c = db.superusuario(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException("consulta falhou: " + e.getMessage(), e);
        }
    }

    /** Espera ate a consulta devolver "t" (ou falha). */
    void esperar(String descricao, String sqlBooleano, Duration limite) {
        long fim = System.nanoTime() + limite.toNanos();
        while (System.nanoTime() < fim) {
            if ("t".equals(valor(sqlBooleano))) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("tempo esgotado esperando: " + descricao);
    }

    @Override
    public void close() {
        cliente.stop();
        db.encerrar();
        rede.close();
    }
}
