package com.nekkora.platform.artifact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLATFORM-AUTOMATION-LIFECYCLE-001, L2: o servidor confere o pacote sem executa-lo e reproduz a impressao do contrato
 * (vetores sintetico e real) e o sha256 dos bytes, que sao medidas diferentes. Pacotes maliciosos ou malformados sao
 * recusados com motivo de vocabulario fechado.
 */
public class PackageInspectorTest {

    static final Path REAL = Path.of("src/test/resources/pacote-studio-real");
    static final String REAL_FINGERPRINT = "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d";

    // ------------------------------------------------------------------ montagem de ZIP

    public static Map<String, byte[]> realFiles() throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(REAL)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                m.put(REAL.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return m;
    }

    public static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return b.toByteArray();
    }

    public static byte[] realZip() throws IOException {
        return zip(realFiles());
    }

    /** Posicao do cabecalho do diretorio central da entrada {@code name}. */
    static int central(byte[] zip, String name) {
        ByteBuffer b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i + 46 < zip.length; i++) {
            if (b.getInt(i) == 0x02014b50) {
                int n = Short.toUnsignedInt(b.getShort(i + 28));
                if (new String(zip, i + 46, n, StandardCharsets.UTF_8).equals(name)) {
                    return i;
                }
            }
        }
        throw new AssertionError("entrada ausente: " + name);
    }

    static String reason(byte[] zip) {
        return assertThrows(PackageInspector.Rejected.class, () -> PackageInspector.inspect(zip)).reason();
    }

    // ------------------------------------------------------------------ vetores do contrato

    @Test
    void vetorSinteticoDoContratoComCrlfEBinario() {
        byte[] main = "print('ola')\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] dados = {(byte) 0xff, (byte) 0xfe, 0x00, 0x01};
        String mainSha = "sha256:dbccf933401a15cfe345e90891e20b5a05a495f552e4577b80c052276604e24f";
        String dadosSha = "sha256:d2ad9277baaee14856d20ec2b21f87a0cb8a7f86c6ef090fd5a082b1e85135ac";
        byte[] manifest = ("{\"schema\": \"nekkora-studio/codegen-manifest\", \"version\": \"1.4.0\", \"files\": {\"main.py\": \"" + mainSha
                + "\", \"dados.bin\": \"" + dadosSha + "\"}}\r\n").getBytes(StandardCharsets.UTF_8);
        assertEquals(mainSha, PackageInspector.fileHash(main));
        assertEquals(dadosSha, PackageInspector.fileHash(dados));
        assertEquals("sha256:779965c156b7c0f0921285933008850b19d592ce990ba7dffb42b8dd22eae868", PackageInspector.fileHash(manifest));
        assertEquals("cada6a642bc7001a314b513a9dd56a312b4ff7612149c0ee29d523e641dff828",
                PackageInspector.fingerprint(manifest, Map.of("main.py", mainSha, "dados.bin", dadosSha)));
    }

    @Test
    void pacoteRealDoStudioDaAImpressaoDoContratoEOsBytesSaoOutraMedida() throws Exception {
        byte[] zip = realZip();
        PackageInspector.Inspection in = PackageInspector.inspect(zip);
        assertEquals(REAL_FINGERPRINT, in.fingerprint());
        assertEquals(PackageInspector.hex(PackageInspector.sha256(zip)), in.sha256());
        assertNotEquals(in.fingerprint(), in.sha256());
        assertEquals(9, in.filesCount());
        assertEquals("list", in.executionMode());
        assertEquals("nekkora-rpa[excel]==0.3.0", in.framework());
        // outro ZIP do mesmo pacote (pasta como raiz e CRLF trocado em texto): bytes diferentes, impressao igual
        Map<String, byte[]> crlf = new LinkedHashMap<>();
        realFiles().forEach((k, v) -> crlf.put("pacote/" + k, k.endsWith(".py") ? new String(v, StandardCharsets.UTF_8).replace("\r\n", "\n")
                .replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8) : v));
        PackageInspector.Inspection other = PackageInspector.inspect(zip(crlf));
        assertEquals(REAL_FINGERPRINT, other.fingerprint());
        assertNotEquals(in.sha256(), other.sha256());
    }

    @Test
    void procedenciaDeclaradaFicaComoDeclaradaETextoForaDoFormatoNaoEGuardado() throws Exception {
        PackageInspector.Inspection in = PackageInspector.inspect(realZip());
        assertTrue(in.declaredGenerator() == null || in.declaredGenerator().matches("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,79}"));
        Map<String, byte[]> f = realFiles();
        String m = new String(f.get("manifest.json"), StandardCharsets.UTF_8);
        String bad = m.replaceFirst("\"generator\"\\s*:\\s*\"[^\"]*\"", "\"generator\": \"Studio <script>\\u0007\"");
        f.put("manifest.json", bad.getBytes(StandardCharsets.UTF_8));
        // o manifesto mudou: o hash dele nao e declarado em files (ele mesmo entra na impressao), entao a conferencia segue
        assertNull(PackageInspector.inspect(zip(f)).declaredGenerator());
    }

    // ------------------------------------------------------------------ recusas

    @Test
    void conteudoAlteradoDepoisDoRegistroNoManifestoERecusado() throws Exception {
        Map<String, byte[]> f = realFiles();
        f.put("main.py", (new String(f.get("main.py"), StandardCharsets.UTF_8) + "\n# alterado\n").getBytes(StandardCharsets.UTF_8));
        assertEquals("hash_divergente", reason(zip(f)));
    }

    @Test
    void arquivoListadoAusenteManifestoAusenteOuMalformado() throws Exception {
        Map<String, byte[]> f = realFiles();
        f.remove("automation/pages.py");
        assertEquals("arquivo_listado_ausente", reason(zip(f)));
        Map<String, byte[]> g = realFiles();
        g.remove("manifest.json");
        assertEquals("manifesto_ausente", reason(zip(g)));
        Map<String, byte[]> h = realFiles();
        h.put("manifest.json", "{\"schema\": \"x\", \"schema\": \"y\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("manifesto_malformado", reason(zip(h)), "chave repetida no JSON");
        Map<String, byte[]> k = realFiles();
        k.put("manifest.json", new String(k.get("manifest.json"), StandardCharsets.UTF_8).replace("nekkora-studio/codegen-manifest", "outro")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("manifesto_fora_do_contrato", reason(zip(k)));
    }

    @Test
    void pacoteQuePedeSegredoERecusado() throws Exception {
        Map<String, byte[]> f = realFiles();
        String m = new String(f.get("manifest.json"), StandardCharsets.UTF_8);
        assertTrue(m.contains("\"steps\""));
        f.put("manifest.json", m.replaceFirst("\"steps\"\\s*:\\s*\\[", "\"steps\": [{\"credential\": true},").getBytes(StandardCharsets.UTF_8));
        assertEquals("segredos_nao_suportados", reason(zip(f)));
    }

    @Test
    void caminhosInsegurosERepetidosSaoRecusados() throws Exception {
        for (String evil : new String[] {"../fora.py", "/abs.py", "a\\b.py", "c:x.py", "dir//x.py", ".oculto"}) {
            Map<String, byte[]> f = realFiles();
            f.put(evil, new byte[] {1});
            assertEquals("caminho_inseguro", reason(zip(f)), evil);
        }
        Map<String, byte[]> f = realFiles();
        f.put("Main.py", new byte[] {1});
        assertEquals("nome_repetido", reason(zip(f)), "mesmo nome so na caixa");
    }

    @Test
    void nomeRepetidoExatoLigacaoSimbolicaCifragemEAmbiguidadeDeCabecalho() throws Exception {
        Map<String, byte[]> f = realFiles();
        f.put("aaaa.txt", new byte[] {1});
        f.put("bbbb.txt", new byte[] {2});
        // nome repetido exato: troca o nome da segunda entrada (central e local) pelo da primeira
        byte[] dup = zip(f);
        int c = central(dup, "bbbb.txt");
        int local = ByteBuffer.wrap(dup).order(ByteOrder.LITTLE_ENDIAN).getInt(c + 42);
        System.arraycopy("aaaa.txt".getBytes(StandardCharsets.UTF_8), 0, dup, c + 46, 8);
        System.arraycopy("aaaa.txt".getBytes(StandardCharsets.UTF_8), 0, dup, local + 30, 8);
        assertEquals("nome_repetido", reason(dup));
        // ligacao simbolica (feita no Unix: modo S_IFLNK nos atributos externos)
        byte[] link = zip(f);
        ByteBuffer lb = ByteBuffer.wrap(link).order(ByteOrder.LITTLE_ENDIAN);
        int cl = central(link, "aaaa.txt");
        lb.putShort(cl + 4, (short) (3 << 8 | 20));
        lb.putInt(cl + 38, 0xA1FF << 16);
        assertEquals("ligacao_simbolica", reason(link));
        // cifragem
        byte[] enc = zip(f);
        ByteBuffer eb = ByteBuffer.wrap(enc).order(ByteOrder.LITTLE_ENDIAN);
        int ce = central(enc, "aaaa.txt");
        eb.putShort(ce + 8, (short) (eb.getShort(ce + 8) | 1));
        assertEquals("zip_cifrado", reason(enc));
        // nome do cabecalho local diferente do central (o que um descompactador ingenuo usaria)
        byte[] amb = zip(f);
        int ca = central(amb, "aaaa.txt");
        int la = ByteBuffer.wrap(amb).order(ByteOrder.LITTLE_ENDIAN).getInt(ca + 42);
        System.arraycopy("zzzz.txt".getBytes(StandardCharsets.UTF_8), 0, amb, la + 30, 8);
        assertEquals("zip_ambiguo", reason(amb));
    }

    @Test
    void bombaDeDescompressaoECrcAdulteradoSaoRecusados() throws Exception {
        Map<String, byte[]> f = realFiles();
        f.put("grande.txt", new byte[200_000]); // comprime muito
        byte[] bomb = zip(f);
        int c = central(bomb, "grande.txt");
        ByteBuffer.wrap(bomb).order(ByteOrder.LITTLE_ENDIAN).putInt(c + 24, 1000); // declara 1000 bytes descompactados
        assertEquals("zip_malformado", reason(bomb));
        byte[] crc = zip(f);
        int cc = central(crc, "grande.txt");
        ByteBuffer cb = ByteBuffer.wrap(crc).order(ByteOrder.LITTLE_ENDIAN);
        cb.putInt(cc + 16, cb.getInt(cc + 16) ^ 1);
        assertEquals("zip_corrompido", reason(crc));
    }

    @Test
    void naoEZipVazioOuAcimaDoLimite() {
        assertEquals("vazio", reason(new byte[0]));
        assertEquals("zip_malformado", reason("isto nao e um zip".getBytes(StandardCharsets.UTF_8)));
        assertEquals("tamanho_excedido", reason(new byte[(int) PackageInspector.MAX_ZIP_BYTES + 1]));
    }

    @Test
    void nomesSegurosSaoOsMesmosDoAgent() {
        assertTrue(PackageInspector.safeName("automation/pages.py"));
        assertFalse(PackageInspector.safeName("a/../b.py"));
        assertFalse(PackageInspector.safeName("-x.py"));
    }

    // ------------------------------------------------------------------ armazenamento local

    @TempDir
    Path tmp;

    @Test
    void armazenamentoAtomicoIdempotenteIsoladoEConferidoNaLeitura() throws Exception {
        LocalArtifactStore s = new LocalArtifactStore(tmp.resolve("artefatos"));
        UUID org = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        byte[] data = realZip();
        String sha = PackageInspector.hex(PackageInspector.sha256(data));
        s.put(org, sha, data);
        s.put(org, sha, data); // idempotente
        assertArrayEquals(data, s.get(org, sha).orElseThrow());
        assertTrue(s.get(other, sha).isEmpty(), "outra organizacao nao ve o arquivo");
        assertThrows(IllegalArgumentException.class, () -> s.put(org, "0".repeat(64), data), "chave diferente do conteudo");
        assertThrows(IllegalArgumentException.class, () -> s.get(org, "../../etc/passwd"));
        try (Stream<Path> files = Files.list(tmp.resolve("artefatos").resolve(org.toString()))) {
            assertEquals(1, files.count(), "sem temporarios sobrando");
        }
        Path f = tmp.resolve("artefatos").resolve(org.toString()).resolve(sha + ".zip");
        Files.write(f, new byte[] {1, 2, 3});
        assertThrows(ArtifactStore.Corrupted.class, () -> s.get(org, sha));
        s.put(org, sha, data); // regravacao recupera o conteudo
        assertArrayEquals(data, s.get(org, sha).orElseThrow());
        Files.delete(f);
        assertTrue(s.get(org, sha).isEmpty());
    }
}
