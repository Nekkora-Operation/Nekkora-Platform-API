package com.nekkora.core.artifact;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A UNICA forma de remover bytes do armazenamento de RESULTADOS de execucoes remotas (057). E um tipo a parte de proposito: quem
 * recebe um {@link ResultStore} le (a Platform) ou grava (o canal das maquinas), e nao remove; so a retencao recebe este tipo.
 * Pacote publicado nao tem remocao nenhuma.
 *
 * <p>{@link #remove} so retorna depois de conferir que os bytes nao estao mais la; repetir e seguro (ausente ja e removido).
 */
public final class ResultPurge {

    private final LocalArtifactStore store;

    private ResultPurge(LocalArtifactStore store) {
        this.store = store;
    }

    /** {@code dir} em branco: sem armazenamento, nada pode ser removido ({@link #available} falso). */
    public static ResultPurge at(String dir) throws IOException {
        return new ResultPurge(dir == null || dir.isBlank() ? null : new LocalArtifactStore(Path.of(dir)));
    }

    public boolean available() {
        return store != null;
    }

    public void remove(UUID organization, String sha256) throws IOException {
        if (store == null) {
            throw new IOException("armazenamento de resultados nao configurado");
        }
        store.remove(organization, sha256);
    }
}
