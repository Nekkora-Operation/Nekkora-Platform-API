package com.nekkora.ops.cli;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Properties;

/**
 * Credencial temporaria do operador (contrato 3.1): chave privada gerada NO DISPOSITIVO do operador (so a publica vai a
 * CA) e o certificado de 15 min emitido para ela. Materializada num PKCS#12 temporario, com senha aleatoria em
 * memoria, porque o driver le a chave de arquivo; o arquivo e apagado no {@link #close()}.
 */
public final class Credencial implements AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String papel;
    private final Path arquivo;
    private final String senha;
    private final X509Certificate certificado;

    private Credencial(String papel, Path arquivo, String senha, X509Certificate certificado) {
        this.papel = papel;
        this.arquivo = arquivo;
        this.senha = senha;
        this.certificado = certificado;
    }

    /** Par de chaves do operador (P-256), gerado localmente. */
    public static KeyPair novoParDeChaves() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("geracao de chave falhou", e);
        }
    }

    /** Monta a credencial a partir do par local e do certificado devolvido pela CA. */
    public static Credencial de(String papel, KeyPair par, X509Certificate certificado, X509Certificate ca) {
        try {
            byte[] b = new byte[24];
            RANDOM.nextBytes(b);
            String senha = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            // o driver (PKCS12KeyManager) procura o alias "user"
            ks.setKeyEntry("user", par.getPrivate(), senha.toCharArray(), new Certificate[] {certificado, ca});
            Path f = Files.createTempFile("nekkora-ops-", ".p12");
            try {
                Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                f.toFile().setReadable(false, false);
                f.toFile().setReadable(true, true);
            }
            try (OutputStream out = Files.newOutputStream(f)) {
                ks.store(out, senha.toCharArray());
            }
            return new Credencial(papel, f, senha, certificado);
        } catch (Exception e) {
            throw new IllegalStateException("credencial local falhou", e);
        }
    }

    public String papel() {
        return papel;
    }

    public X509Certificate certificado() {
        return certificado;
    }

    /** Propriedades JDBC: TLS com verificacao do servidor e autenticacao por certificado. Sem senha de banco. */
    public Properties propriedades(Path raizDoServidor) {
        Properties p = new Properties();
        p.setProperty("user", papel);
        p.setProperty("sslmode", "verify-full");
        p.setProperty("sslrootcert", raizDoServidor.toString());
        p.setProperty("sslkey", arquivo.toString());
        p.setProperty("sslpassword", senha);
        p.setProperty("ApplicationName", "nekkora-ops-cli");
        return p;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(arquivo);
        } catch (Exception e) {
            // melhor esforco: arquivo temporario com senha aleatoria que nunca foi persistida
        }
    }
}
