package com.nekkora.platform.artifact;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Armazenamento LOCAL de laboratorio (sem servico pago): {@code <raiz>/<organizacao>/<sha256>.zip}. Garantias:
 * <ul>
 *   <li>durabilidade: o temporario recebe {@code force} antes da troca e a pasta e sincronizada depois (onde o sistema permite);</li>
 *   <li>atomicidade: troca atomica do temporario pelo nome final; um leitor nunca ve arquivo pela metade;</li>
 *   <li>isolamento: pasta por organizacao; nome so de UUID e hex validados aqui; nunca segue ligacao;</li>
 *   <li>recuperacao: conteudo enderecado por hash; uma gravacao que sobra sem linha no banco (falha entre o arquivo e a
 *   transacao) e inofensiva e reaproveitada pelo proximo envio igual; leitura sempre confere o hash.</li>
 * </ul>
 */
public final class LocalArtifactStore implements ArtifactStore {

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private final Path root;

    public LocalArtifactStore(Path root) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath();
    }

    private Path file(UUID organization, String sha256) {
        if (organization == null || sha256 == null || !SHA.matcher(sha256).matches()) {
            throw new IllegalArgumentException("chave invalida");
        }
        return root.resolve(organization.toString()).resolve(sha256 + ".zip");
    }

    @Override
    public void put(UUID organization, String sha256, byte[] data) throws IOException {
        if (!sha256.equals(PackageInspector.hex(PackageInspector.sha256(data)))) {
            throw new IllegalArgumentException("chave diferente do conteudo");
        }
        Path target = file(organization, sha256);
        Path dir = target.getParent();
        Files.createDirectories(dir);
        if (Files.isSymbolicLink(dir) || !dir.toRealPath().startsWith(root)) {
            throw new IOException("pasta da organizacao fora da raiz");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (get(organization, sha256).isPresent()) {
                    return; // ja existe e confere: idempotente
                }
            } catch (Corrupted e) {
                // conteudo trocado ou corrompido no disco: o envio igual o regrava (recuperacao)
            }
        }
        Path tmp = Files.createTempFile(dir, "." + sha256 + ".", ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer b = ByteBuffer.wrap(data);
                while (b.hasRemaining()) {
                    ch.write(b);
                }
                ch.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            syncDirectory(dir);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public Optional<byte[]> get(UUID organization, String sha256) throws IOException {
        Path f = file(organization, sha256);
        byte[] data;
        try {
            if (Files.isSymbolicLink(f) || !Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            data = Files.readAllBytes(f);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        if (!Arrays.equals(PackageInspector.sha256(data), java.util.HexFormat.of().parseHex(sha256))) {
            throw new Corrupted();
        }
        return Optional.of(data);
    }

    @Override
    public boolean available() {
        return true;
    }

    private static void syncDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows nao abre pasta como canal; o NTFS registra a troca no journal
        }
    }
}
