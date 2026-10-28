package com.nekkora.platform.mail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Teto GLOBAL de tentativas de envio ao Graph (homologacao do e-mail real, relatorio de 2026-10-24, secao 4.7): cada
 * chamada {@code sendMail}, da fila ou da prova, reserva ANTES uma vaga num registro em disco; sem vaga, nada sai.
 *
 * <p>Registro: um diretorio montado no container da Platform (a fila e a {@code ProvaEnvioM365} rodam no mesmo container
 * e veem o mesmo diretorio), uma vaga por arquivo {@code tentativa-NNN} criado com {@code CREATE_NEW} (criacao exclusiva:
 * dois processos nunca ganham a mesma vaga). O arquivo leva o instante, a origem e a correlacao; o desfecho e acrescentado
 * depois. Sobrevive a reinicios, a "desligar" e a recriacao do container: a contagem e da homologacao inteira, nao do
 * processo. Conta toda tentativa que pode chegar ao Graph, aceita, recusada ou sem conexao; o token do Entra nao conta.
 *
 * <p>Falha fechada: diretorio ausente, ilegivel ou sem permissao de escrita recusa a reserva (nada sai). Nenhum destinatario,
 * assunto ou conteudo vai para o registro.
 */
public final class TentativasEnvio {

    private static final Logger LOG = LoggerFactory.getLogger(TentativasEnvio.class);

    /** Sem teto (fora de ensaio-*, quando nao configurado). */
    public static final TentativasEnvio SEM_TETO = new TentativasEnvio(null, 0, "-", Clock.systemUTC());

    /** Vaga reservada (numero) ou recusa sem rede. */
    public record Reserva(int numero, Path arquivo) {
        public boolean concedida() {
            return numero > 0;
        }
    }

    private static final Reserva SEM_LIMITE = new Reserva(Integer.MAX_VALUE, null);
    private static final Reserva RECUSADA = new Reserva(0, null);

    private final Path dir;
    private final int maximo;
    private final String origem;
    private final Clock clock;

    private TentativasEnvio(Path dir, int maximo, String origem, Clock clock) {
        this.dir = dir;
        this.maximo = maximo;
        this.origem = origem;
        this.clock = clock;
    }

    /**
     * Teto em {@code dir} com {@code maximo} vagas; {@code origem} ({@code platform} ou {@code prova}) vai para o registro.
     * Confere na criacao que o diretorio existe e aceita escrita (partida recusada se nao).
     */
    public static TentativasEnvio em(Path dir, int maximo, String origem) {
        return em(dir, maximo, origem, Clock.systemUTC());
    }

    static TentativasEnvio em(Path dir, int maximo, String origem, Clock clock) {
        if (dir == null || maximo < 1 || maximo > 999) {
            throw new IllegalStateException("teto de tentativas: diretorio e maximo (1 a 999) obrigatorios");
        }
        if (!Files.isDirectory(dir) || !Files.isWritable(dir)) {
            throw new IllegalStateException("teto de tentativas: diretorio ausente ou sem escrita");
        }
        return new TentativasEnvio(dir, maximo, origem, clock);
    }

    /**
     * Regra de configuracao (a mesma na partida da Platform e na prova): em {@code ensaio-*} o teto e OBRIGATORIO (sem
     * diretorio e maximo, recusa); fora dele, so se configurado.
     */
    public static TentativasEnvio daConfiguracao(String ambiente, String dir, String maximo, String origem) {
        boolean homologacao = ambiente != null && CaixaHomologacaoMailSender.AMBIENTE.matcher(ambiente).matches();
        if (dir == null || dir.isBlank()) {
            if (homologacao) {
                throw new IllegalStateException("transporte m365 em ensaio-* exige o teto de tentativas (nekkora.mail.tentativas)");
            }
            return SEM_TETO;
        }
        int max;
        try {
            max = Integer.parseInt(maximo == null ? "" : maximo.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("nekkora.mail.tentativas.maximo invalido");
        }
        return em(Path.of(dir), max, origem);
    }

    public boolean limitado() {
        return dir != null;
    }

    public int maximo() {
        return maximo;
    }

    /** Vagas ja usadas (arquivos {@code tentativa-NNN}); com o diretorio ilegivel, o maximo (falha fechada). */
    public int usadas() {
        if (dir == null) {
            return 0;
        }
        int n = 0;
        for (int i = 1; i <= maximo; i++) {
            if (Files.exists(arquivo(i))) {
                n++;
            }
        }
        return Files.isDirectory(dir) ? n : maximo;
    }

    /** Reserva a primeira vaga livre; sem vaga ou com erro de disco, recusa (nada deve sair). */
    public Reserva reservar(UUID correlacao) {
        if (dir == null) {
            return SEM_LIMITE;
        }
        byte[] linha = ("instante=" + clock.instant() + " origem=" + origem + " correlacao=" + correlacao + "\n").getBytes(StandardCharsets.US_ASCII);
        for (int i = 1; i <= maximo; i++) {
            Path f = arquivo(i);
            try {
                Files.write(f, linha, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                LOG.info("evento=mail.tentativa numero={} maximo={} origem={} correlacao={}", i, maximo, origem, correlacao);
                return new Reserva(i, f);
            } catch (FileAlreadyExistsException e) {
                // vaga ocupada: proxima
            } catch (IOException e) {
                LOG.error("evento=mail.tentativa_registro_falhou causa={} correlacao={}", e.getClass().getSimpleName(), correlacao);
                return RECUSADA;
            }
        }
        LOG.warn("evento=mail.teto_tentativas maximo={} origem={} correlacao={}", maximo, origem, correlacao);
        return RECUSADA;
    }

    /** Acrescenta o desfecho a vaga (melhor esforco: a vaga ja foi contada). */
    public void registrar(Reserva r, MailTransport.Result resultado, Integer status) {
        if (r.arquivo() == null) {
            return;
        }
        try {
            Files.writeString(r.arquivo(), "desfecho=" + resultado + " status=" + status + "\n", StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("evento=mail.tentativa_desfecho_nao_registrado numero={}", r.numero());
        }
    }

    private Path arquivo(int i) {
        return dir.resolve(String.format("tentativa-%03d", i));
    }
}
