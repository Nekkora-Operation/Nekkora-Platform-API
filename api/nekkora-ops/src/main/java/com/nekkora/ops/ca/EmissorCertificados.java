package com.nekkora.ops.ca;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import com.nekkora.ops.idp.VerificadorIdp;

/**
 * CA operacional (contrato 3.2), dependencia proposta aqui implementada como biblioteca: verifica o token do IdP
 * (MFA recente e resistente a phishing), resolve o sujeito para o papel do operador (1:1, so operador ativo),
 * REGISTRA a emissao no banco pelo papel {@code nekkora_ops_ca} e so entao assina um certificado de cliente de 15 min
 * com CN = papel do operador. A chave privada do operador nunca passa por aqui: a CA recebe so a chave publica.
 *
 * <p>Se o registro for recusado ou falhar, nada e assinado. Um serial registrado e nao assinado (falha depois do
 * registro) e inofensivo: vence em 15 min e nenhuma conexao o apresenta.
 */
public final class EmissorCertificados {

    /** Validade do certificado do operador (contrato 3.2). Fixa. */
    public static final Duration VALIDADE = Duration.ofMinutes(15);

    /** Conexao do papel da CA ({@code nekkora_ops_ca}) - aberta por chamada. */
    @FunctionalInterface
    public interface BancoDaCa {
        Connection abrir() throws SQLException;
    }

    public static final class EmissaoNegada extends Exception {
        private final String motivo;

        public EmissaoNegada(String motivo) {
            super(motivo);
            this.motivo = motivo;
        }

        public String motivo() {
            return motivo;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PrivateKey chaveCa;
    private final X509Certificate certificadoCa;
    private final String emissorNoServidor;
    private final VerificadorIdp idp;
    private final BancoDaCa banco;
    private final Clock relogio;

    public EmissorCertificados(PrivateKey chaveCa, X509Certificate certificadoCa, VerificadorIdp idp, BancoDaCa banco, Clock relogio) {
        this.chaveCa = Objects.requireNonNull(chaveCa);
        this.certificadoCa = Objects.requireNonNull(certificadoCa);
        this.idp = Objects.requireNonNull(idp);
        this.banco = Objects.requireNonNull(banco);
        this.relogio = Objects.requireNonNull(relogio);
        this.emissorNoServidor = formatoSslinfo(X500Name.getInstance(certificadoCa.getSubjectX500Principal().getEncoded()));
    }

    /** Emite o certificado do operador autenticado por {@code tokenIdp} para a chave publica {@code chaveOperador}. */
    public X509Certificate emitir(String tokenIdp, PublicKey chaveOperador) throws EmissaoNegada {
        VerificadorIdp.Identidade id;
        try {
            id = idp.verificar(tokenIdp);
        } catch (VerificadorIdp.TokenRecusado e) {
            throw new EmissaoNegada(e.motivo());
        }
        BigInteger serial = new BigInteger(158, RANDOM).setBit(157);
        Instant inicio = relogio.instant().minusSeconds(5);
        Instant fim = inicio.plus(VALIDADE);
        String papel;
        try (Connection c = banco.abrir()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); // as funcoes da 016 recusam outro isolamento
            c.setAutoCommit(true);
            try (PreparedStatement ps = c.prepareStatement("SELECT public.ops_ca_resolve_operator(?, ?)")) {
                ps.setString(1, id.emissor());
                ps.setString(2, id.sujeito());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    papel = rs.getString(1);
                }
            }
            if (papel == null) {
                throw new EmissaoNegada("OPERADOR_DESCONHECIDO_OU_INATIVO");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT r_resultado, r_motivo FROM public.ops_register_certificate(?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id.emissor());
                ps.setString(2, id.sujeito());
                ps.setString(3, papel);
                ps.setString(4, emissorNoServidor);
                ps.setBigDecimal(5, new java.math.BigDecimal(serial));
                ps.setTimestamp(6, Timestamp.from(inicio));
                ps.setTimestamp(7, Timestamp.from(fim));
                ps.setTimestamp(8, Timestamp.from(id.authTime()));
                ps.setString(9, id.metodo());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (!"OK".equals(rs.getString(1))) {
                        throw new EmissaoNegada(rs.getString(2));
                    }
                }
            }
        } catch (SQLException e) {
            throw new EmissaoNegada("REGISTRO_INDISPONIVEL");
        }
        try {
            return assinar(papel, serial, inicio, fim, chaveOperador);
        } catch (Exception e) {
            throw new EmissaoNegada("ASSINATURA_FALHOU");
        }
    }

    /** O emissor exatamente como a extensao sslinfo do servidor o apresenta ({@code /O=.../CN=...}). */
    public String emissorNoServidor() {
        return emissorNoServidor;
    }

    private X509Certificate assinar(String papel, BigInteger serial, Instant inicio, Instant fim, PublicKey chave) throws Exception {
        X500Name sujeito = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, papel).build();
        JcaX509ExtensionUtils ext = new JcaX509ExtensionUtils();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(certificadoCa, serial, Date.from(inicio), Date.from(fim), sujeito, chave);
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(chave));
        b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(certificadoCa));
        X509CertificateHolder h = b.build(new JcaContentSignerBuilder(algoritmo(chaveCa)).build(chaveCa));
        return new JcaX509CertificateConverter().getCertificate(h);
    }

    static String algoritmo(PrivateKey k) {
        return "EC".equals(k.getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
    }

    /** Formato da sslinfo (X509_NAME_to_text): "/SN=valor" por RDN, na ordem codificada. */
    public static String formatoSslinfo(X500Name nome) {
        StringBuilder sb = new StringBuilder();
        for (RDN rdn : nome.getRDNs()) {
            for (var atv : rdn.getTypesAndValues()) {
                String sn = BCStyle.INSTANCE.oidToDisplayName(atv.getType());
                sb.append('/').append(sn == null ? atv.getType().getId() : sn).append('=')
                        .append(IETFUtils.valueToString(atv.getValue()));
            }
        }
        return sb.toString();
    }
}
