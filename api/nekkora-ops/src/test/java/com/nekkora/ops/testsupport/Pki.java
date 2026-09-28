package com.nekkora.ops.testsupport;

import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * PKI DESCARTAVEL de teste: CA de infraestrutura (certificado do servidor PostgreSQL), CA operacional (certificados de
 * cliente dos operadores) e uma CA estranha (negativos). Chaves geradas por execucao, so em memoria e no diretorio
 * temporario do teste; nada versionado.
 */
public final class Pki {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Ca(KeyPair chaves, X509Certificate certificado) {
    }

    private Pki() {
    }

    public static KeyPair ec() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048, RANDOM);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** CA autoassinada; o nome e montado O depois CN (ordem codificada = ordem da sslinfo). */
    public static Ca ca(String cn) {
        try {
            KeyPair k = ec();
            X500Name nome = new X500Name("O=Nekkora Teste,CN=" + cn);
            Instant agora = Instant.now();
            JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(nome, serial(), Date.from(agora.minus(Duration.ofHours(1))),
                    Date.from(agora.plus(Duration.ofDays(2))), nome, k.getPublic());
            b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            X509Certificate c = new JcaX509CertificateConverter().getCertificate(
                    b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(k.getPrivate())));
            return new Ca(k, c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Certificado de servidor com SAN para os nomes do host de teste. */
    public static X509Certificate servidor(Ca ca, PublicKey chave, List<String> nomes) {
        try {
            Instant agora = Instant.now();
            JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(ca.certificado(), serial(), Date.from(agora.minus(Duration.ofHours(1))),
                    Date.from(agora.plus(Duration.ofDays(2))), new X500Name("CN=" + nomes.get(0)), chave);
            GeneralName[] san = nomes.stream().map(n -> n.matches("^[0-9.]+$") || n.contains(":")
                    ? new GeneralName(GeneralName.iPAddress, n) : new GeneralName(GeneralName.dNSName, n)).toArray(GeneralName[]::new);
            b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(san));
            b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            return new JcaX509CertificateConverter().getCertificate(
                    b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(ca.chaves().getPrivate())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Certificado de cliente assinado DIRETAMENTE (sem registro no banco) - so para negativos. */
    public static X509Certificate clienteSemRegistro(Ca ca, PublicKey chave, String cn, Instant inicio, Instant fim) {
        try {
            JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(ca.certificado(), serial(), Date.from(inicio), Date.from(fim),
                    new X500Name("CN=" + cn), chave);
            b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            return new JcaX509CertificateConverter().getCertificate(
                    b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(ca.chaves().getPrivate())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pem(Object objeto) {
        try (StringWriter w = new StringWriter(); JcaPEMWriter p = new JcaPEMWriter(w)) {
            p.writeObject(objeto);
            p.flush();
            return w.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Chave privada em PEM PKCS#8 (formato aceito pelo servidor). */
    public static String pemChave(PrivateKey k) {
        try {
            return pem(new org.bouncycastle.openssl.jcajce.JcaPKCS8Generator(k, null).generate());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Path escrever(Path dir, String nome, String conteudo) {
        try {
            return Files.writeString(dir.resolve(nome), conteudo);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigInteger serial() {
        return new BigInteger(120, RANDOM).setBit(119);
    }
}
