package com.nekkora.platform.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transporte de HOMOLOGACAO (desenho P1a, §4): nenhuma conexao de rede; cada mensagem vira um arquivo numa CAIXA local
 * (volume exclusivo do ambiente descartavel {@code ensaio-*}), lida so pela ferramenta restrita do runner. Todo desfecho
 * e simulado e assim identificado ({@link #simulated()}). Nunca e o padrao: so existe com
 * {@code nekkora.mail.transport=homologacao} e {@code nekkora.environment=ensaio-*} (PlatformConfig).
 *
 * <p><b>Publicacao sem sobrescrita e sem arquivo parcial:</b> o conteudo completo e escrito num arquivo temporario
 * unico ({@code .tmp/<uuid>.part}, {@code CREATE_NEW}), sincronizado ({@code force}) e so entao PUBLICADO com
 * {@link Files#createLink}: a ligacao para o nome final e atomica e falha com {@link FileAlreadyExistsException} se o
 * nome ja existir, sem tocar no existente. O temporario e removido depois. Quem le o nome final ve sempre o arquivo
 * inteiro. Exige sistema de arquivos com ligacoes (o volume Linux do ensaio); sem suporte, o envio falha.
 *
 * <p><b>Controle dos desfechos</b> ({@code controle.conf}, escrito so pela ferramenta do runner): {@code proximo
 * <DESFECHO>} (consumida UMA vez) e {@code destinatario <sha256> <DESFECHO>}; padrao ACEITO. A leitura e o consumo
 * acontecem sob a trava {@code controle.trava} (diretorio criado atomicamente; a ferramenta do runner usa a mesma
 * trava), dentro de um monitor da JVM: dois envios concorrentes nunca consomem a mesma regra.
 *
 * <p><b>SEGURAR</b> (B10, varredura com espera real): o aviso NAO e publicado e a entrega fica retida, como um transporte
 * que nao responde, por ate {@link #RETENCAO_PADRAO} (a tentativa continua ENTREGANDO no banco). Serve para o runner derrubar
 * a Platform no meio da entrega e deixar a tentativa parada de verdade. Se o processo nao cair, o desfecho e INCERTO
 * (a tentativa vai a DESCONHECIDO), nunca aceite.
 */
public final class CaixaHomologacaoMailSender implements MailSender {

    private static final Logger LOG = LoggerFactory.getLogger(CaixaHomologacaoMailSender.class);
    public static final Pattern AMBIENTE = Pattern.compile("^ensaio-[a-z0-9-]{1,34}$");
    private static final DateTimeFormatter INSTANTE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(java.time.ZoneOffset.UTC);
    private static final long ESPERA_TRAVA_MS = 5_000;

    enum Desfecho { ACEITO, ENFILEIRADO, RECUSADO, INCERTO, EXCECAO, SEGURAR }

    /** Retencao maxima do SEGURAR (o runner derruba a Platform muito antes). */
    public static final java.time.Duration RETENCAO_PADRAO = java.time.Duration.ofMinutes(3);

    private final Path caixa;
    private final String ambiente;
    private final Object monitor = new Object();
    private final java.time.Duration retencao;

    public CaixaHomologacaoMailSender(Path caixa, String ambiente) {
        this(caixa, ambiente, RETENCAO_PADRAO);
    }

    /** Retencao do SEGURAR configuravel so para o teste do transporte. */
    CaixaHomologacaoMailSender(Path caixa, String ambiente, java.time.Duration retencao) {
        this.retencao = retencao;
        if (ambiente == null || !AMBIENTE.matcher(ambiente).matches()) {
            throw new IllegalStateException("transporte de homologacao exige nekkora.environment=ensaio-*");
        }
        if (caixa == null || !Files.isDirectory(caixa) || !Files.isWritable(caixa)) {
            throw new IllegalStateException("caixa do transporte de homologacao ausente ou sem escrita: " + caixa);
        }
        this.caixa = caixa;
        this.ambiente = ambiente;
        try {
            for (String d : List.of("avisos", "outras", ".tmp")) {
                Path p = caixa.resolve(d);
                if (!Files.isDirectory(p)) {
                    Files.createDirectory(p);
                }
                restringir(p, "rwx------");
            }
        } catch (IOException e) {
            throw new IllegalStateException("caixa do transporte de homologacao: " + e.getClass().getSimpleName(), e);
        }
        LOG.warn("evento=mail.transporte transporte=homologacao ambiente={} simulado=sim envio_externo=nenhum", ambiente);
    }

    @Override
    public boolean simulated() {
        return true;
    }

    @Override
    public void send(Kind kind, UUID organization, String recipient, String tokenOrNull) {
        String nome = "outras/" + INSTANTE.format(Instant.now()) + "_" + kind.name() + "_" + UUID.randomUUID() + ".json";
        String json = json(kind, organization, recipient, tokenOrNull, null, null, null);
        publicar(nome, json);
        indice(nome, "-", kind, recipient, "-", false);
        LOG.info("evento=mail.caixa tipo={} org={} simulado=sim", kind, organization);
    }

    @Override
    public Outcome sendEmergencyNotice(Kind kind, UUID organization, String recipient, String confirmTokenOrNull, String contestTokenOrNull,
                                       UUID attemptId) {
        Desfecho d = desfecho(sha256(recipient));
        if (d == Desfecho.SEGURAR) {
            // retida sem publicar nada: o runner derruba o processo agora (a tentativa fica ENTREGANDO, parada de verdade)
            LOG.warn("evento=mail.caixa.segurando tipo={} org={} simulado=sim", kind, organization);
            try {
                Thread.sleep(retencao.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // nada entregue: nada na caixa nem no indice (o indice so lista mensagens publicadas)
            return Outcome.INCERTO;
        }
        String json = json(kind, organization, recipient, null, confirmTokenOrNull, contestTokenOrNull, attemptId);
        String nome = "avisos/" + attemptId + ".json";
        boolean repeticao = false;
        if (!publicar(nome, json)) {
            // a mesma tentativa entregue de novo: o protocolo proibe; nunca sobrescreve, torna a repeticao VISIVEL
            repeticao = true;
            for (int n = 1; ; n++) {
                nome = "avisos/" + attemptId + ".repeticao-" + n + ".json";
                if (publicar(nome, json)) {
                    break;
                }
            }
            LOG.warn("evento=mail.caixa.repeticao tipo={} org={} simulado=sim alerta=sim", kind, organization);
        }
        indice(nome, attemptId.toString(), kind, recipient, d.name(), repeticao);
        LOG.info("evento=mail.caixa tipo={} org={} desfecho={} simulado=sim", kind, organization, d);
        return switch (d) {
            case ACEITO -> Outcome.ACEITO_PELO_TRANSPORTE;
            case ENFILEIRADO -> Outcome.ENFILEIRADO;
            case RECUSADO -> Outcome.RECUSADO_DEFINITIVO;
            case INCERTO -> Outcome.INCERTO;
            case EXCECAO -> throw new IllegalStateException("excecao simulada do transporte de homologacao");
            case SEGURAR -> throw new IllegalStateException("SEGURAR tratado antes da publicacao");
        };
    }

    // ------------------------------------------------------------------ publicacao

    /** Publica {@code conteudo} em {@code relativo}: {@code true} se publicou, {@code false} se o nome ja existia. */
    boolean publicar(String relativo, String conteudo) {
        Path fim = caixa.resolve(relativo);
        Path tmp = caixa.resolve(".tmp").resolve(UUID.randomUUID() + ".part");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                gravarTudo(ch, conteudo);
                ch.force(true);
            }
            restringir(tmp, "rw-------");
            try {
                Files.createLink(fim, tmp);
                return true;
            } catch (FileAlreadyExistsException e) {
                return false;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("caixa: publicacao falhou (" + e.getClass().getSimpleName() + ")", e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignorado) {
                // temporario orfao em .tmp: sem efeito sobre as mensagens publicadas
            }
        }
    }

    /** Uma linha por mensagem, sem token, link nem endereco; anexada sob o monitor (linhas nunca intercaladas). */
    private void indice(String arquivo, String tentativa, Kind kind, String destinatario, String desfecho, boolean repeticao) {
        String linha = "{\"arquivo\":\"" + arquivo + "\",\"attempt_id\":\"" + tentativa + "\",\"tipo\":\"" + kind.name()
                + "\",\"destinatario_sha256\":\"" + sha256(destinatario) + "\",\"desfecho_simulado\":\"" + desfecho
                + "\",\"repeticao\":" + repeticao + ",\"simulado\":true}\n";
        synchronized (monitor) {
            Path idx = caixa.resolve("indice.jsonl");
            try {
                // criado com 600 (achado do P1c: com CREATE simples o indice nascia 644 no volume Linux)
                if (!Files.exists(idx)) {
                    try {
                        Files.createFile(idx, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                    } catch (UnsupportedOperationException semPosix) {
                        Files.createFile(idx);
                    } catch (FileAlreadyExistsException jaExiste) {
                        // outro processo criou antes: segue anexando
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("caixa: indice falhou (" + e.getClass().getSimpleName() + ")", e);
            }
            try (FileChannel ch = FileChannel.open(idx, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                gravarTudo(ch, linha);
                ch.force(true);
            } catch (IOException e) {
                throw new UncheckedIOException("caixa: indice falhou (" + e.getClass().getSimpleName() + ")", e);
            }
        }
    }

    // ------------------------------------------------------------------ controle

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** Criacao do diretorio da trava (Files::createDirectory; substituivel so nos testes da aquisicao). */
    @FunctionalInterface
    interface CriadorTrava {
        void criar(Path trava) throws IOException;
    }

    /**
     * Aquisicao da trava do controle (diretorio criado atomicamente; a ferramenta do runner usa a mesma trava), com UM
     * prazo total ({@code limite}, nunca reiniciado). Contencao, com nova tentativa ate o prazo:
     * <ul>
     *   <li>{@link FileAlreadyExistsException}: a trava existe (outro escritor a segura);</li>
     *   <li>{@link AccessDeniedException} SO no Windows e SO para o caminho exato da trava: criar um diretorio cuja
     *       remocao por outro ator ainda esta pendente falha com ERROR_ACCESS_DENIED (WIN-TRANSPORT-0, reproduzido entre
     *       threads e entre processos). Fora do Windows, ou para outro caminho, e falha imediata.</li>
     * </ul>
     * Qualquer outra IOException e falha imediata. Esgotado o prazo: se houve AccessDeniedException, ELA e a causa final
     * (uma permissao real nunca vira "trava ocupada" nem sucesso); senao, trava ocupada. Sem leitura de mensagem ou de
     * codigo de erro localizado.
     */
    static void adquirirTrava(Path trava, long limite, CriadorTrava criador, boolean windows) {
        AccessDeniedException acessoNegado = null;
        while (true) {
            try {
                criador.criar(trava);
                return;
            } catch (FileAlreadyExistsException e) {
                // contencao normal: a trava esta com outro escritor
            } catch (AccessDeniedException e) {
                if (!windows || e.getFile() == null || !Path.of(e.getFile()).equals(trava)) {
                    throw new UncheckedIOException(e);
                }
                acessoNegado = e;   // possivel remocao pendente da MESMA trava: contencao transitoria
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (System.currentTimeMillis() > limite) {
                if (acessoNegado != null) {
                    throw new UncheckedIOException("caixa: trava do controle nao adquirida no prazo (acesso negado)", acessoNegado);
                }
                throw new IllegalStateException("caixa: trava do controle ocupada (ferramenta do runner em uso?)");
            }
            Thread.onSpinWait();
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ie);
            }
        }
    }

    /** Desfecho da proxima entrega: regra "proximo" (consumida uma vez) > regra do destinatario > ACEITO. */
    Desfecho desfecho(String destinatarioSha256) {
        synchronized (monitor) {
            Path trava = caixa.resolve("controle.trava");
            adquirirTrava(trava, System.currentTimeMillis() + ESPERA_TRAVA_MS, Files::createDirectory, WINDOWS);
            try {
                Path controle = caixa.resolve("controle.conf");
                if (!Files.exists(controle)) {
                    return Desfecho.ACEITO;
                }
                List<String> linhas = Files.readAllLines(controle, StandardCharsets.UTF_8);
                List<String> restantes = new ArrayList<>();
                Desfecho escolhido = null;
                Desfecho porDestinatario = null;
                for (String l : linhas) {
                    String[] c = l.trim().split("\\s+");
                    if (escolhido == null && c.length == 2 && c[0].equals("proximo")) {
                        escolhido = Desfecho.valueOf(c[1].toUpperCase(Locale.ROOT));
                        continue;   // consumida: nao volta ao arquivo
                    }
                    if (porDestinatario == null && c.length == 3 && c[0].equals("destinatario") && c[1].equals(destinatarioSha256)) {
                        porDestinatario = Desfecho.valueOf(c[2].toUpperCase(Locale.ROOT));
                    }
                    restantes.add(l);
                }
                if (escolhido != null) {
                    Path novo = caixa.resolve(".tmp").resolve(UUID.randomUUID() + ".controle");
                    Files.write(novo, restantes, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    restringir(novo, "rw-------");   // o controle regravado continua 600 (achado do P1c)
                    // substituicao do PROPRIO controle sob a trava (unico escritor enquanto ela durar)
                    Files.move(novo, controle, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    return escolhido;
                }
                return porDestinatario != null ? porDestinatario : Desfecho.ACEITO;
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("caixa: regra de controle invalida");
            } catch (IOException e) {
                throw new UncheckedIOException("caixa: controle falhou (" + e.getClass().getSimpleName() + ")", e);
            } finally {
                try {
                    Files.deleteIfExists(trava);
                } catch (IOException ignorado) {
                    LOG.warn("evento=mail.caixa.trava_nao_liberada alerta=sim");
                }
            }
        }
    }

    // ------------------------------------------------------------------ apoio

    /**
     * {@link FileChannel#write} pode gravar MENOS que o buffer: repete ate esgota-lo. So depois disso o chamador
     * sincroniza e publica (arquivo completo garantido antes da ligacao para o nome final).
     */
    static void gravarTudo(FileChannel ch, String conteudo) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(conteudo.getBytes(StandardCharsets.UTF_8));
        while (b.hasRemaining()) {
            ch.write(b);
        }
    }

    private static void restringir(Path p, String perm) throws IOException {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perm));
        } catch (UnsupportedOperationException e) {
            // sistema de arquivos sem POSIX (fora do volume Linux do ensaio): a restricao e conferida pelo runner
        }
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (s == null ? "" : s.trim().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String json(Kind kind, UUID org, String destinatario, String token, String confirmar, String contestar, UUID tentativa) {
        StringBuilder b = new StringBuilder("{");
        campo(b, "simulado", "true", false);
        campo(b, "ambiente", ambiente, true);
        campo(b, "instante", Instant.now().toString(), true);
        campo(b, "tipo", kind.name(), true);
        campo(b, "organizacao", String.valueOf(org), true);
        campo(b, "destinatario", destinatario, true);
        if (tentativa != null) {
            campo(b, "attempt_id", tentativa.toString(), true);
        }
        if (token != null) {
            campo(b, "token", token, true);
        }
        if (confirmar != null) {
            campo(b, "token_confirmar", confirmar, true);
        }
        if (contestar != null) {
            campo(b, "token_contestar", contestar, true);
        }
        b.setLength(b.length() - 1);
        return b.append("}\n").toString();
    }

    private static void campo(StringBuilder b, String nome, String valor, boolean texto) {
        b.append('"').append(nome).append("\":");
        if (texto) {
            b.append('"');
            for (char c : valor.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    default -> {
                        if (c < 0x20) {
                            b.append(String.format("\\u%04x", (int) c));
                        } else {
                            b.append(c);
                        }
                    }
                }
            }
            b.append('"');
        } else {
            b.append(valor);
        }
        b.append(',');
    }
}
