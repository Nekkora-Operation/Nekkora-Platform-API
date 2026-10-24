package com.nekkora.ops.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Entrada da CLI operacional. Configuracao por ambiente (nenhum segredo em argumento):
 * <ul>
 * <li>{@code NEKKORA_OPS_DB_URL} - {@code jdbc:postgresql://host:porta/base} (TLS obrigatorio, verify-full);</li>
 * <li>{@code NEKKORA_OPS_RAIZ_SERVIDOR} - certificado raiz do servidor;</li>
 * <li>{@code NEKKORA_OPS_PAPEL} - papel do operador ({@code op_*}), o CN do certificado;</li>
 * <li>{@code NEKKORA_OPS_CREDENCIAL} e {@code NEKKORA_OPS_CREDENCIAL_SENHA} - PKCS#12 da credencial de 15 min
 * (chave gerada no dispositivo, certificado da CA operacional);</li>
 * <li>{@code NEKKORA_OPS_LOG} - log local sanitizado.</li>
 * </ul>
 * Uso: {@code retorno <contato>} | {@code abrir <org> <alvo> <contato> entrada|retorno <id>}
 * | {@code verificar <solicitacao> <retorno-ok> <segundo-contato> <segundo-ok> NENHUM|TODOS_SEM_FATOR_E_CODIGOS_UTILIZAVEIS}
 * | {@code pre-aprovar|aprovar|executar|consultar <solicitacao>} | {@code reenviar-aviso <solicitacao> <destinatario>}
 * | {@code cancelar <solicitacao> <motivo>}
 * | {@code vincular-conta <codigo>} (016: vinculo da conta da Platform que o operador controla, pelo codigo de uso unico que o
 * titular emite na propria sessao; so o sha256 vai ao banco)
 * | {@code ativar-organizacao <org> <responsavel> <workers> <simultaneas> <pendentes> <alerta-logs|-> <alerta-eventos|-> <motivo...>}
 * | {@code revisar-capacidade <org> <responsavel> <workers> <simultaneas> <pendentes> <alerta-logs|-> <alerta-eventos|-> <motivo...>}
 * (022: ativacao da organizacao e revisao da capacidade depois de ativada, com os limites explicitos; o motivo e o resto
 * da linha).
 * Saida: {@code solicitacao=<uuid> resultado=... origem=... estado=... motivo=...}; em ABRIR, o UUID devolvido pelo
 * banco e o que o operador usa nos atos seguintes. Em ATIVAR-ORGANIZACAO e REVISAR-CAPACIDADE: {@code organizacao=<uuid> resultado=... evento=<uuid>
 * motivo=...} (evento = auditoria gravada pelo banco, OK ou RECUSA).
 * Codigo de saida: 0 OK, 2 recusa, 3 falha confirmada, 4 desconhecido, 64 uso invalido.
 */
public final class OpsCli {

    private OpsCli() {
    }

    public static void main(String[] args) {
        System.exit(executar(args, System.getenv(), System.out, System.err));
    }

    static int executar(String[] a, Map<String, String> env, PrintStream out, PrintStream err) {
        if (a.length < 2) {
            err.println("uso: nekkora-ops <ato> <argumentos>");
            return 64;
        }
        String url;
        Properties p = new Properties();
        try {
            url = exigir(env, "NEKKORA_OPS_DB_URL");
            p.setProperty("user", exigir(env, "NEKKORA_OPS_PAPEL"));
            p.setProperty("sslmode", "verify-full");
            p.setProperty("sslrootcert", Path.of(exigir(env, "NEKKORA_OPS_RAIZ_SERVIDOR")).toString());
            p.setProperty("sslkey", exigir(env, "NEKKORA_OPS_CREDENCIAL"));
            p.setProperty("sslpassword", exigir(env, "NEKKORA_OPS_CREDENCIAL_SENHA"));
            p.setProperty("ApplicationName", "nekkora-ops-cli");
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return 64;
        }
        LogLocal log = new LogLocal(Path.of(env.getOrDefault("NEKKORA_OPS_LOG", "nekkora-ops.log")));
        ExecutorAtos ex = new ExecutorAtos(() -> DriverManager.getConnection(url, p), log);
        if ("retorno".equals(a[0])) {
            ExecutorAtos.RetornoRegistrado rr;
            try {
                rr = ex.registrarRetorno(UUID.fromString(a[1]));
            } catch (RuntimeException e) {
                err.println("uso invalido");
                return 64;
            }
            out.println("retorno=" + (rr.retorno() == null ? "-" : rr.retorno()) + " resultado=" + rr.classe()
                    + " motivo=" + LogLocal.codigo(rr.motivo()));
            return codigo(rr.classe());
        }
        if ("vincular-conta".equals(a[0])) {
            if (a.length != 2 || a[1].isBlank()) {
                err.println("uso invalido");
                return 64;
            }
            ExecutorAtos.VinculoConta v = ex.vincularConta(a[1]);
            out.println("vinculo=conta resultado=" + v.classe() + " motivo=" + LogLocal.codigo(v.motivo()));
            return codigo(v.classe());
        }
        if ("ativar-organizacao".equals(a[0]) || "revisar-capacidade".equals(a[0])) {
            ExecutorAtos.ResultadoAtivacao ra;
            try {
                if (a.length < 9) {
                    throw new IllegalArgumentException("argumentos");
                }
                String motivo = String.join(" ", Arrays.copyOfRange(a, 8, a.length));
                ra = "ativar-organizacao".equals(a[0])
                        ? ex.ativarOrganizacao(UUID.fromString(a[1]), UUID.fromString(a[2]), motivo, Long.parseLong(a[3]), Long.parseLong(a[4]),
                                Long.parseLong(a[5]), limiar(a[6]), limiar(a[7]))
                        : ex.revisarCapacidade(UUID.fromString(a[1]), UUID.fromString(a[2]), motivo, Long.parseLong(a[3]), Long.parseLong(a[4]),
                                Long.parseLong(a[5]), limiar(a[6]), limiar(a[7]));
            } catch (RuntimeException e) {
                err.println("uso invalido");
                return 64;
            }
            out.println("organizacao=" + ra.organizacao() + " resultado=" + ra.classe() + " evento=" + (ra.evento() == null ? "-" : ra.evento())
                    + " motivo=" + LogLocal.codigo(ra.motivo()));
            return codigo(ra.classe());
        }
        ExecutorAtos.Resultado r;
        try {
            r = switch (a[0]) {
                case "abrir" -> ex.abrir(UUID.fromString(a[1]), UUID.fromString(a[2]), UUID.fromString(a[3]), switch (a[4]) {
                    case "entrada" -> new ExecutorAtos.Entrada(UUID.fromString(a[5]));
                    case "retorno" -> new ExecutorAtos.Retorno(UUID.fromString(a[5]));
                    default -> throw new IllegalArgumentException("modo");
                });
                case "verificar" -> ex.verificar(UUID.fromString(a[1]), Boolean.parseBoolean(a[2]), UUID.fromString(a[3]),
                        Boolean.parseBoolean(a[4]), ExecutorAtos.DemaisAdmins.valueOf(a[5]));
                case "pre-aprovar" -> ex.preAprovar(UUID.fromString(a[1]));
                case "aprovar" -> ex.aprovar(UUID.fromString(a[1]));
                case "executar" -> ex.executar(UUID.fromString(a[1]));
                case "reenviar-aviso" -> ex.reenviarAviso(UUID.fromString(a[1]), UUID.fromString(a[2]));
                case "consultar" -> ex.consultar(UUID.fromString(a[1]));
                case "cancelar" -> ex.cancelar(UUID.fromString(a[1]), a[2]);
                default -> null;
            };
        } catch (RuntimeException e) {
            err.println("uso invalido");
            return 64;
        }
        if (r == null) {
            err.println("ato desconhecido");
            return 64;
        }
        out.println("solicitacao=" + (r.solicitacao() == null ? "-" : r.solicitacao()) + " resultado=" + r.classe() + " origem=" + r.origem()
                + " estado=" + LogLocal.codigo(r.estado()) + " motivo=" + LogLocal.codigo(r.motivo()));
        return codigo(r.classe());
    }

    private static int codigo(ExecutorAtos.Classe classe) {
        return switch (classe) {
            case OK -> 0;
            case RECUSA -> 2;
            case FALHA_CONFIRMADA -> 3;
            case DESCONHECIDO -> 4;
        };
    }

    /** Limiar de alerta dos recursos 4-5: "-" = sem limiar (UNLIMITED); senao o valor (a validacao final e do banco). */
    private static Long limiar(String s) {
        return "-".equals(s) ? null : Long.valueOf(s);
    }

    private static String exigir(Map<String, String> env, String nome) {
        String v = env.get(nome);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("variavel de ambiente ausente: " + nome);
        }
        return v;
    }
}
