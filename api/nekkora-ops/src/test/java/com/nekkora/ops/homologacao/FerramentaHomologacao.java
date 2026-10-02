package com.nekkora.ops.homologacao;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import com.nekkora.ops.ca.EmissorCertificados;
import com.nekkora.ops.cli.Credencial;
import com.nekkora.ops.idp.VerificadorIdp;
import com.nekkora.ops.testsupport.IdpSimulado;

/**
 * Ferramenta da HOMOLOGACAO descartavel (desenho P1a, §6) - escopo de TESTE, executada so no job {@code operacao} de um
 * ambiente {@code ensaio-*}. IdP e CA sao SIMULADOS (limite declarado; C1/C2 dependem de infraestrutura real):
 * o IdP e o {@link IdpSimulado} (chave gerada a cada execucao) e a CA e a biblioteca real {@link EmissorCertificados}
 * com a CA operacional descartavel do ambiente. O registro do certificado vai pelo papel {@code nekkora_ops_ca}
 * (SCRAM, TLS verify-full), como faria o servico da CA.
 *
 * <pre>
 *   credencial &lt;papel&gt;                                   PKCS#12 (15 min) em $HOMOLOG_OPERADORES/&lt;papel&gt;.p12 e .senha (0600)
 *   operador   &lt;papel_cadastrar&gt; &lt;papel_novo&gt; &lt;ATO...&gt;  ops_operator_create + ops_operator_grant (certificado do CADASTRAR)
 *   contato    &lt;papel_cadastrar&gt; &lt;org&gt; &lt;usuario&gt; &lt;e-mail&gt;  ops_contact_add (CONTRATACAO); imprime o contact_id
 *   entrada    &lt;papel_operador&gt; &lt;mecanismo 1|3&gt; [pseudonimo] ops_intake_register como nekkora_ops_intake; imprime o entry_id
 *   sonda      valida|senha|ca-estranha|cn-diferente &lt;papel&gt; [&lt;outro&gt;]  codigo 0 so se a CAUSA for a esperada
 * </pre>
 *
 * Variaveis: NEKKORA_AMBIENTE (ensaio-*), NEKKORA_OPS_DB_URL, NEKKORA_OPS_RAIZ_SERVIDOR, HOMOLOG_OPS_CA_CRT,
 * HOMOLOG_OPS_CA_KEY, HOMOLOG_OPS_CA_SENHA (nekkora_ops_ca), HOMOLOG_OPS_INTAKE_SENHA, HOMOLOG_OPS_INTAKE_VERSAO (versao
 * vigente da chave de pseudonimizacao no ensaio: o servico de entrada nao le tabelas), HOMOLOG_OPERADORES (diretorio).
 * Nenhum segredo e impresso: a saida padrao leva so ids e resultados.
 */
public final class FerramentaHomologacao {

    private static final Pattern AMBIENTE = Pattern.compile("^ensaio-[a-z0-9-]{1,34}$");
    private static final Pattern PAPEL = Pattern.compile("^op_[a-z0-9_]{2,40}$");

    private final Map<String, String> env;
    private final EmissorCertificados emissor;
    private final X509Certificate caOps;
    private final IdpSimulado idp = new IdpSimulado();
    private int tentativasCaEstranha;

    private FerramentaHomologacao(Map<String, String> env) throws Exception {
        this.env = env;
        String amb = exigir("NEKKORA_AMBIENTE");
        if (!AMBIENTE.matcher(amb).matches()) {
            throw new IllegalStateException("ferramenta de homologacao so roda em ambiente ensaio-*");
        }
        caOps = certificado(Path.of(exigir("HOMOLOG_OPS_CA_CRT")));
        PrivateKey chaveCa = chave(Path.of(exigir("HOMOLOG_OPS_CA_KEY")));
        VerificadorIdp verificador = new VerificadorIdp(IdpSimulado.EMISSOR, IdpSimulado.AUDIENCIA, idp.chavePublica(), Clock.systemUTC());
        emissor = new EmissorCertificados(chaveCa, caOps, verificador, () -> servico("nekkora_ops_ca", exigir("HOMOLOG_OPS_CA_SENHA")),
                Clock.systemUTC());
    }

    public static void main(String[] a) {
        try {
            System.exit(new FerramentaHomologacao(System.getenv()).executar(a));
        } catch (Exception e) {
            System.err.println("ERRO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(3);
        }
    }

    private int executar(String[] a) throws Exception {
        if (a.length < 2) {
            System.err.println("uso: credencial|operador|contato|entrada ...");
            return 64;
        }
        switch (a[0]) {
            case "credencial" -> {
                String papel = papel(a[1]);
                try (Credencial c = credencial(papel)) {
                    Path dir = Path.of(exigir("HOMOLOG_OPERADORES"));
                    Properties p = c.propriedades(Path.of(exigir("NEKKORA_OPS_RAIZ_SERVIDOR")));
                    Path p12 = dir.resolve(papel + ".p12");
                    Path senha = dir.resolve(papel + ".senha");
                    Files.copy(Path.of(p.getProperty("sslkey")), p12, StandardCopyOption.REPLACE_EXISTING);
                    Files.writeString(senha, p.getProperty("sslpassword"), StandardCharsets.UTF_8);
                    restringir(p12);
                    restringir(senha);
                    System.out.println("credencial=" + papel + " validade_ate=" + c.certificado().getNotAfter().toInstant());
                }
                return 0;
            }
            case "operador" -> {
                String cad = papel(a[1]);
                String novo = papel(a[2]);
                try (Credencial c = credencial(cad); Connection con = operador(c)) {
                    UUID id = um(con, UUID.class, "SELECT r_operador FROM public.ops_operator_create(?, ?, ?)", novo, IdpSimulado.EMISSOR,
                            IdpSimulado.sujeito(novo));
                    if (id == null) {
                        System.out.println("operador=" + novo + " resultado=RECUSA");
                        return 2;
                    }
                    for (int i = 3; i < a.length; i++) {
                        String r = um(con, String.class, "SELECT r_resultado FROM public.ops_operator_grant(?, ?)", id, a[i]);
                        System.out.println("habilitacao=" + a[i] + " resultado=" + r);
                    }
                    System.out.println("operador=" + novo + " id=" + id);
                }
                return 0;
            }
            case "contato" -> {
                try (Credencial c = credencial(papel(a[1])); Connection con = operador(c)) {
                    UUID id = um(con, UUID.class, "SELECT r_contato FROM public.ops_contact_add(?, ?, 2::smallint, ?, ?, 'CONTRATACAO', true)",
                            UUID.fromString(a[2]), "Contato sintetico homologacao", a[4], UUID.fromString(a[3]));
                    System.out.println("contato=" + (id == null ? "-" : id));
                    return id == null ? 2 : 0;
                }
            }
            case "entrada" -> {
                String op = papel(a[1]);
                short mecanismo = Short.parseShort(a[2]);
                // versao da chave pela CONFIGURACAO controlada do ensaio (operacao.env): nekkora_ops_intake nao le
                // tabelas; uma versao divergente e recusada pela propria funcao (ENTRADA_INVALIDA), sem ampliar privilegio
                Integer versao = mecanismo == 3 ? null : Integer.valueOf(exigir("HOMOLOG_OPS_INTAKE_VERSAO"));
                try (Connection con = servico("nekkora_ops_intake", exigir("HOMOLOG_OPS_INTAKE_SENHA"))) {
                    try (PreparedStatement ps = con.prepareStatement(
                            "SELECT r_resultado, r_motivo, r_entrada FROM public.ops_intake_register(?::smallint, ?::smallint, ?, ?, NULL, NULL, ?)")) {
                        ps.setShort(1, mecanismo == 2 ? (short) 1 : (short) 2);
                        ps.setShort(2, mecanismo);
                        ps.setObject(3, versao);
                        ps.setString(4, mecanismo == 3 ? null : a[3]);
                        ps.setString(5, op);
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            System.out.println("entrada=" + (rs.getObject(3) == null ? "-" : rs.getObject(3)) + " resultado=" + rs.getString(1)
                                    + " motivo=" + (rs.getString(2) == null ? "-" : rs.getString(2)));
                            return "OK".equals(rs.getString(1)) ? 0 : 2;
                        }
                    }
                }
            }
            case "sonda" -> {
                // B6: tentativas que o pg_hba/TLS/016 PRECISAM recusar, feitas do proprio job (IP .33, regras originais)
                String papel = papel(a[2]);
                // cada sonda tem UMA causa esperada; qualquer outra (inclusive indisponibilidade) e FALHA_TECNICA, que o
                // h016.sh trata como falha do cenario, nunca como prova de bloqueio
                String r = switch (a[1]) {
                    case "senha" -> tentar(() -> {
                        Properties p = new Properties();
                        p.setProperty("user", papel);
                        p.setProperty("password", "senha-qualquer-" + UUID.randomUUID());
                        p.setProperty("sslmode", "verify-full");
                        p.setProperty("sslrootcert", exigir("NEKKORA_OPS_RAIZ_SERVIDOR"));
                        return DriverManager.getConnection(exigir("NEKKORA_OPS_DB_URL"), p);
                    });
                    case "ca-estranha" -> {
                        var ca = com.nekkora.ops.testsupport.Pki.ca("CA estranha homologacao");
                        KeyPair par = Credencial.novoParDeChaves();
                        var cert = com.nekkora.ops.testsupport.Pki.clienteSemRegistro(ca, par.getPublic(), papel, java.time.Instant.now().minusSeconds(60),
                                java.time.Instant.now().plusSeconds(600));
                        // P1c: o gerenciador de chaves padrao do pgjdbc so APRESENTA o certificado se o emissor estiver na
                        // lista de CAs aceitas que o servidor envia; com CA estranha o cliente nao manda certificado e a
                        // recusa vem do pg_hba (28000), sem exercitar a verificacao de CA do servidor. A sonda APRESENTA o
                        // certificado estranho (fabrica propria; o servidor continua verificado pela raiz, verify-full),
                        // para que a recusa seja a do servidor: unknown_ca + "certificate verify failed" no log.
                        CertificadoForcado.definir(par.getPrivate(), cert, Path.of(exigir("NEKKORA_OPS_RAIZ_SERVIDOR")));
                        Properties p = new Properties();
                        p.setProperty("user", papel);
                        p.setProperty("sslmode", "verify-full");
                        p.setProperty("sslfactory", CertificadoForcado.class.getName());
                        // P1c: dentro do container Linux o alerta pode se perder numa corrida com o RST do servidor (que
                        // fecha com dados do cliente nao lidos): a tentativa vira falha de TRANSPORTE (08xxx, reset). So
                        // nesse caso a sonda repete (ate 5); qualquer resultado conclusivo (alerta TLS, recusa de
                        // autenticacao, conexao) encerra. So resets continuam FALHA_TECNICA: nunca contam como recusa.
                        String r1 = null;
                        int n = 0;
                        while (n < 5) {
                            n++;
                            r1 = tentar(() -> DriverManager.getConnection(exigir("NEKKORA_OPS_DB_URL"), p));
                            if (!r1.startsWith("FALHA_TECNICA:08")) {
                                break;
                            }
                        }
                        tentativasCaEstranha = n;
                        yield r1;
                    }
                    case "cn-diferente" -> {
                        // certificado valido de a[2], login como a[3]: o mapa "ops" exige CN = papel
                        String outro = papel(a[3]);
                        try (Credencial c = credencial(papel)) {
                            Properties p = c.propriedades(Path.of(exigir("NEKKORA_OPS_RAIZ_SERVIDOR")));
                            p.setProperty("user", outro);
                            yield tentar(() -> DriverManager.getConnection(exigir("NEKKORA_OPS_DB_URL"), p));
                        }
                    }
                    case "valida" -> {
                        try (Credencial c = credencial(papel)) {
                            yield tentar(() -> operador(c));
                        }
                    }
                    default -> throw new IllegalArgumentException("sonda desconhecida");
                };
                String esperado = switch (a[1]) {
                    case "valida" -> "CONECTOU";
                    case "senha", "cn-diferente" -> "RECUSADA_AUTENTICACAO";
                    default -> "RECUSADA_TLS:unknown_ca";   // ca-estranha: SO a rejeicao por CA desconhecida conta
                };
                boolean conforme = r.equals(esperado) || r.startsWith(esperado + ":");
                System.out.println("sonda=" + a[1] + " papel=" + papel + " resultado=" + r + " esperado=" + esperado
                        + " conforme=" + (conforme ? "sim" : "nao") + (tentativasCaEstranha > 0 ? " tentativas=" + tentativasCaEstranha : ""));
                return conforme ? 0 : 2;
            }
            default -> {
                System.err.println("subcomando desconhecido");
                return 64;
            }
        }
    }

    @FunctionalInterface
    public interface Conexao {
        Connection abrir() throws SQLException;
    }

    /**
     * Fabrica TLS SO da sonda "ca-estranha" (P1c): apresenta SEMPRE o certificado de cliente indicado (o da CA estranha),
     * sem o filtro pelo emissor do gerenciador padrao do pgjdbc, e verifica o SERVIDOR pela raiz do ambiente (o nome do
     * servidor continua conferido pelo pgjdbc em verify-full). Uma conexao por execucao da ferramenta.
     */
    public static final class CertificadoForcado extends org.postgresql.ssl.WrappedFactory {

        private static volatile javax.net.ssl.SSLSocketFactory atual;

        static void definir(PrivateKey chave, X509Certificate cert, Path raiz) throws Exception {
            java.security.KeyStore confiaveis = java.security.KeyStore.getInstance("PKCS12");
            confiaveis.load(null, null);
            confiaveis.setCertificateEntry("raiz-servidor", certificado(raiz));
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(confiaveis);
            javax.net.ssl.X509ExtendedKeyManager km = new javax.net.ssl.X509ExtendedKeyManager() {
                @Override
                public String[] getClientAliases(String tipo, java.security.Principal[] emissores) {
                    return new String[] {"estranho"};
                }

                @Override
                public String chooseClientAlias(String[] tipo, java.security.Principal[] emissores, java.net.Socket s) {
                    return "estranho";
                }

                @Override
                public String chooseEngineClientAlias(String[] tipo, java.security.Principal[] emissores, javax.net.ssl.SSLEngine e) {
                    return "estranho";
                }

                @Override
                public String[] getServerAliases(String tipo, java.security.Principal[] emissores) {
                    return null;
                }

                @Override
                public String chooseServerAlias(String tipo, java.security.Principal[] emissores, java.net.Socket s) {
                    return null;
                }

                @Override
                public X509Certificate[] getCertificateChain(String alias) {
                    return new X509Certificate[] {cert};
                }

                @Override
                public PrivateKey getPrivateKey(String alias) {
                    return chave;
                }
            };
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(new javax.net.ssl.KeyManager[] {km}, tmf.getTrustManagers(), null);
            atual = ctx.getSocketFactory();
        }

        public CertificadoForcado() {
            if (atual == null) {
                throw new IllegalStateException("certificado da sonda nao definido");
            }
            factory = atual;
        }

        /**
         * Handshake e LEITURA antes de o pgjdbc escrever (achado do P1c, dentro do container Linux): o servidor recusa o
         * certificado e fecha com dados do cliente ainda nao lidos (RST). Se o cliente estiver ESCREVENDO nesse momento
         * (resto do voo TLS 1.2 ou a mensagem inicial do PostgreSQL no 1.3), a escrita falha com "Connection reset" antes
         * de ler o alerta, e a causa se perde. Aqui o cliente termina o handshake (TLS 1.3) e LE primeiro, com tempo curto:
         * o alerta ja recebido (unknown_ca) aparece como excecao. Sem alerta (o servidor aceitou), o tempo esgota e a
         * conexao segue normalmente. Um "reset" continua sendo falha tecnica na classificacao.
         */
        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) throws IOException {
            javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) factory.createSocket(s, host, port, autoClose);
            ssl.setUseClientMode(true);
            ssl.startHandshake();
            int anterior = ssl.getSoTimeout();
            ssl.setSoTimeout(2000);
            try {
                java.io.InputStream in = ssl.getInputStream();
                if (in.markSupported()) {
                    in.mark(1);
                }
                int b = in.read();
                if (b >= 0) {
                    throw new IOException("sonda ca-estranha: dado inesperado do servidor antes da mensagem inicial");
                }
            } catch (java.net.SocketTimeoutException semAlerta) {
                // o servidor nao recusou no TLS: segue (a classificacao mostrara CONECTOU ou a recusa de autenticacao)
            } finally {
                if (!ssl.isClosed()) {
                    ssl.setSoTimeout(anterior);
                }
            }
            return ssl;
        }
    }

    /**
     * Classifica a tentativa pela CAUSA, sem mensagem do servidor (que pode citar o papel):
     * <ul>
     * <li>{@code CONECTOU};</li>
     * <li>{@code RECUSADA_AUTENTICACAO:<sqlstate>}: o servidor respondeu e recusou a autenticacao (classe 28:
     * 28000 - pg_hba/metodo/mapa de certificado; 28P01 - senha);</li>
     * <li>{@code RECUSADA_TLS:unknown_ca}: SO o alerta unknown_ca - o servidor nao reconhece a CA emissora do
     * certificado apresentado (a causa esperada da sonda "CA estranha"; o h016.sh exige tambem a evidencia do servidor);</li>
     * <li>{@code FALHA_TECNICA:TLS:<alerta>}: qualquer outro erro de TLS, inclusive alertas genericos (handshake_failure,
     * decrypt_error, access_denied, bad_certificate, certificate_unknown), que nao demonstram a causa;</li>
     * <li>{@code FALHA_TECNICA:<sqlstate>:<classe>}: qualquer outra coisa (conexao recusada, tempo esgotado, servidor
     * indisponivel). Falha tecnica NUNCA conta como prova de bloqueio.</li>
     * </ul>
     */
    public static String tentar(Conexao c) {
        try (Connection con = c.abrir()) {
            return "CONECTOU";
        } catch (SQLException e) {
            String estado = e.getSQLState() == null ? "-" : e.getSQLState();
            if (estado.startsWith("28")) {
                return "RECUSADA_AUTENTICACAO:" + estado;
            }
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof javax.net.ssl.SSLException && t.getMessage() != null) {
                    // SO unknown_ca prova a causa esperada da sonda "CA estranha" (o servidor nao reconhece a CA emissora
                    // do certificado). Alertas genericos (handshake_failure, decrypt_error, access_denied,
                    // bad_certificate, certificate_unknown...) nao demonstram essa causa: ficam como falha tecnica, e o
                    // h016.sh ainda exige a evidencia do servidor ("certificate verify failed") no intervalo da sonda
                    if (t.getMessage().contains("unknown_ca")) {
                        return "RECUSADA_TLS:unknown_ca";
                    }
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("([a-z_]+_(?:failure|error|denied|certificate|unknown))")
                            .matcher(t.getMessage());
                    return "FALHA_TECNICA:TLS:" + (m.find() ? m.group(1) : t.getClass().getSimpleName());
                }
            }
            Throwable raiz = e;
            while (raiz.getCause() != null) {
                raiz = raiz.getCause();
            }
            return "FALHA_TECNICA:" + estado + ":" + raiz.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------------ apoio

    private Credencial credencial(String papel) throws EmissorCertificados.EmissaoNegada {
        KeyPair par = Credencial.novoParDeChaves();
        return Credencial.de(papel, par, emissor.emitir(idp.token(papel), par.getPublic()), caOps);
    }

    private Connection operador(Credencial c) throws SQLException {
        return DriverManager.getConnection(exigir("NEKKORA_OPS_DB_URL"), c.propriedades(Path.of(exigir("NEKKORA_OPS_RAIZ_SERVIDOR"))));
    }

    /** Papel de servico (SCRAM), TLS com verificacao do servidor. Aberta em READ COMMITTED (guarda da 016). */
    private Connection servico(String papel, String senha) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", papel);
        p.setProperty("password", senha);
        p.setProperty("sslmode", "verify-full");
        p.setProperty("sslrootcert", exigir("NEKKORA_OPS_RAIZ_SERVIDOR"));
        p.setProperty("ApplicationName", "nekkora-homologacao-" + papel);
        Connection c = DriverManager.getConnection(exigir("NEKKORA_OPS_DB_URL"), p);
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return c;
    }

    private static <T> T um(Connection con, Class<T> tipo, String sql, Object... params) throws SQLException {
        con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, tipo) : null;
            }
        }
    }

    private String exigir(String nome) {
        String v = env.get(nome);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("variavel ausente: " + nome);
        }
        return v;
    }

    private static String papel(String p) {
        if (!PAPEL.matcher(p).matches()) {
            throw new IllegalArgumentException("papel de operador invalido");
        }
        return p;
    }

    private static X509Certificate certificado(Path pem) throws Exception {
        try (InputStream in = Files.newInputStream(pem)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static PrivateKey chave(Path pem) throws Exception {
        try (Reader r = Files.newBufferedReader(pem); PEMParser p = new PEMParser(r)) {
            Object o = p.readObject();
            if (o instanceof PrivateKeyInfo info) {
                return new JcaPEMKeyConverter().getPrivateKey(info);
            }
            if (o instanceof org.bouncycastle.openssl.PEMKeyPair par) {
                return new JcaPEMKeyConverter().getKeyPair(par).getPrivate();
            }
            throw new IllegalStateException("chave da CA operacional em formato inesperado");
        }
    }

    private static void restringir(Path f) throws Exception {
        try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // fora de sistema POSIX: o diretorio de operadores fica so no volume do ensaio
        }
    }
}
