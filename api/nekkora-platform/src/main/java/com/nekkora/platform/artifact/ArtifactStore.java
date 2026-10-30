package com.nekkora.platform.artifact;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * Armazenamento dos bytes dos artefatos recebidos (PLATFORM-AUTOMATION-LIFECYCLE-001, L2). A chave e SEMPRE o sha256 dos
 * bytes calculado pelo servidor, por organizacao: nenhum caminho, nome ou URL vindo do cliente chega aqui. Conteudo
 * enderecado por hash: gravar o mesmo conteudo de novo e idempotente; ler confere o hash (bytes trocados ou corrompidos no
 * armazenamento nunca voltam como validos).
 */
public interface ArtifactStore {

    /** Grava (ou confere que ja existe igual) e so retorna com os bytes duraveis. */
    void put(UUID organization, String sha256, byte[] data) throws IOException;

    /** Bytes conferidos pelo hash; vazio se ausentes; {@link Corrupted} se o conteudo nao bate com a chave. */
    Optional<byte[]> get(UUID organization, String sha256) throws IOException;

    /** Armazenamento configurado neste processo (sem ele as rotas de artefato respondem 503). */
    boolean available();

    final class Corrupted extends IOException {
        public Corrupted() {
            super("conteudo do artefato nao confere com a chave");
        }
    }

    /** Sem armazenamento configurado (padrao): nada e aceito nem lido. */
    ArtifactStore NONE = new ArtifactStore() {
        @Override
        public void put(UUID organization, String sha256, byte[] data) throws IOException {
            throw new IOException("armazenamento de artefatos nao configurado");
        }

        @Override
        public Optional<byte[]> get(UUID organization, String sha256) throws IOException {
            throw new IOException("armazenamento de artefatos nao configurado");
        }

        @Override
        public boolean available() {
            return false;
        }
    };
}
