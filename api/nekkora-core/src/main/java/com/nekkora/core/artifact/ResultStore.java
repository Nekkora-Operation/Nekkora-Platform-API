package com.nekkora.core.artifact;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Armazenamento dos bytes dos RESULTADOS de execucoes remotas (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T03): os arquivos que a
 * automacao gravou na pasta de saida e a maquina enviou. Mesmas garantias do armazenamento de pacotes (chave = SHA-256 dos
 * bytes, por organizacao; gravacao duravel e atomica; leitura sempre confere o hash), numa pasta PROPRIA: resultado nunca se
 * mistura com pacote publicado. E um tipo a parte de proposito, para que nenhum componente receba um armazenamento pelo outro.
 *
 * <p>O canal da maquina grava; a Platform le. Sem a pasta configurada, nada e aceito nem lido.
 */
public final class ResultStore {

    private final ArtifactStore store;

    private ResultStore(ArtifactStore store) {
        this.store = store;
    }

    /** {@code dir} em branco: armazenamento ausente (as rotas de resultado respondem 503). */
    public static ResultStore at(String dir) throws IOException {
        return new ResultStore(dir == null || dir.isBlank() ? ArtifactStore.NONE : new LocalArtifactStore(Path.of(dir)));
    }

    public boolean available() {
        return store.available();
    }

    /** Grava (ou confere que ja existe igual) e so retorna com os bytes duraveis. */
    public void put(UUID organization, String sha256, byte[] data) throws IOException {
        store.put(organization, sha256, data);
    }

    /** Bytes conferidos pelo hash; vazio se ausentes; {@link ArtifactStore.Corrupted} se o conteudo nao bate com a chave. */
    public Optional<byte[]> get(UUID organization, String sha256) throws IOException {
        return store.get(organization, sha256);
    }
}
