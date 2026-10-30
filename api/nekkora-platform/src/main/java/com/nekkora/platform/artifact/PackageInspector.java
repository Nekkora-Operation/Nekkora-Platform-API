package com.nekkora.platform.artifact;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Confere um pacote do Studio recebido como ZIP SEM executar nada (PLATFORM-AUTOMATION-LIFECYCLE-001, L2) e calcula as duas
 * identidades, que nunca se confundem:
 * <ul>
 *   <li>{@code sha256}: SHA-256 dos BYTES recebidos (o ZIP como chegou);</li>
 *   <li>{@code fingerprint}: impressao {@code nekkora-agent/package-fingerprint/1} (contrato Platform-Agent, sec. 6.4), a mesma
 *   que o Agent calcula ({@code ExecutionPackage.fingerprint}), sobre o manifesto e SOMENTE os arquivos listados nele.</li>
 * </ul>
 * O ZIP e lido pelo diretorio central e cada entrada e conferida contra o cabecalho local (nome igual, metodo conhecido,
 * tamanho e CRC conferidos ao descompactar com teto). Recusa: cifragem, ZIP64, multi-disco, ligacao simbolica, caminho
 * absoluto, com {@code ..}, barra invertida, dois-pontos ou controle, nome repetido (inclusive so na caixa), arquivo listado
 * ausente ou com hash divergente, acima dos limites, manifesto fora do contrato do Agent e pacote que pede segredo.
 * As mesmas regras do Agent (Nekkora-Agent {@code ExecutionPackage}) para que o que a API aceita o Agent tambem aceite.
 */
public final class PackageInspector {

    public static final String RULE = "nekkora-agent/package-fingerprint/1";
    public static final String MANIFEST = "manifest.json";
    public static final long MAX_ZIP_BYTES = 50L * 1024 * 1024;
    static final long MAX_CONTENT_BYTES = 50L * 1024 * 1024;
    static final int MAX_FILES = 500;
    static final int MAX_ENTRIES = 1000;
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}");
    private static final Pattern SHA = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern OPTION = Pattern.compile("--[a-z][a-z-]{0,30}");
    private static final Pattern FRAMEWORK = Pattern.compile("nekkora-rpa(\\[excel])?==([0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3})");
    private static final Pattern MANIFEST_VERSION = Pattern.compile("1\\.[4-9]\\.[0-9]{1,3}");
    private static final Pattern GENERATOR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/+-]{0,79}");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();

    /** Recusa com motivo de vocabulario fechado (nunca texto do pacote). */
    public static final class Rejected extends Exception {
        private final String reason;

        public Rejected(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** Resultado da conferencia. {@code declaredGenerator}: procedencia DECLARADA no manifesto, nunca verificada. */
    public record Inspection(String sha256, long sizeBytes, String fingerprint, int filesCount, int ignoredFiles, String manifestVersion,
                             String framework, String executionMode, String declaredGenerator) {
    }

    private record Entry(String name, int method, int flags, long compressedSize, long size, long crc, long localOffset) {
    }

    private PackageInspector() {
    }

    public static Inspection inspect(byte[] zip) throws Rejected {
        if (zip == null || zip.length == 0) {
            throw new Rejected("vazio");
        }
        if (zip.length > MAX_ZIP_BYTES) {
            throw new Rejected("tamanho_excedido");
        }
        Map<String, byte[]> content = read(zip);
        String prefix = root(content.keySet());
        Map<String, byte[]> files = new TreeMap<>();
        content.forEach((k, v) -> files.put(k.substring(prefix.length()), v));
        byte[] manifestBytes = files.get(MANIFEST);
        if (manifestBytes == null) {
            throw new Rejected("manifesto_ausente");
        }
        if (manifestBytes.length > MAX_MANIFEST_BYTES) {
            throw new Rejected("manifesto_grande");
        }
        JsonNode m;
        try {
            m = JSON.readTree(manifestBytes);
        } catch (Exception e) {
            throw new Rejected("manifesto_malformado");
        }
        if (m == null || !m.isObject()) {
            throw new Rejected("manifesto_malformado");
        }
        String version = text(m, "version");
        if (!"nekkora-studio/codegen-manifest".equals(text(m, "schema")) || version == null || !MANIFEST_VERSION.matcher(version).matches()) {
            throw new Rejected("manifesto_fora_do_contrato");
        }
        String mode = text(m, "execution");
        if (!List.of("single", "list", "tabular").contains(String.valueOf(mode))) {
            throw new Rejected("modo_de_execucao_invalido");
        }
        String framework = text(m, "framework");
        Matcher fw = FRAMEWORK.matcher(String.valueOf(framework));
        if (!fw.matches()) {
            throw new Rejected("framework_invalido");
        }
        options(m, mode);
        JsonNode steps = m.get("steps");
        if (steps != null && steps.isArray()) {
            for (JsonNode s : steps) {
                if (s.isObject() && s.path("credential").isBoolean() && s.path("credential").asBoolean()) {
                    throw new Rejected("segredos_nao_suportados");
                }
            }
        }
        Map<String, String> declared = declared(m.get("files"));
        if (!declared.containsKey("main.py")) {
            throw new Rejected("entrada_ausente");
        }
        long total = 0;
        for (Map.Entry<String, String> e : declared.entrySet()) {
            byte[] data = files.get(e.getKey());
            if (data == null) {
                throw new Rejected("arquivo_listado_ausente");
            }
            total += data.length;
            if (total > MAX_CONTENT_BYTES) {
                throw new Rejected("tamanho_excedido");
            }
            if (!e.getValue().equals(fileHash(data))) {
                throw new Rejected("hash_divergente");
            }
        }
        String fingerprint = fingerprint(manifestBytes, declared);
        int ignored = (int) files.keySet().stream().filter(k -> !k.equals(MANIFEST) && !declared.containsKey(k)).count();
        String generator = text(m, "generator");
        if (generator != null && !GENERATOR.matcher(generator).matches()) {
            generator = null; // texto fora do formato nao e guardado
        }
        return new Inspection(hex(sha256(zip)), zip.length, fingerprint, declared.size(), ignored, version, framework, mode, generator);
    }

    /** Hash de um arquivo como o gerador do Studio e o Agent calculam: texto UTF-8 com CRLF trocado por LF; senao os bytes. */
    public static String fileHash(byte[] data) {
        byte[] bytes = data;
        try {
            String t = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
            bytes = t.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            // binario: os bytes como estao
        }
        return "sha256:" + hex(sha256(bytes));
    }

    /** Impressao (sem o prefixo sha256:) pelo texto canonico da regra /1, tipo STUDIO_PYTHON. */
    public static String fingerprint(byte[] manifest, Map<String, String> declared) {
        StringBuilder b = new StringBuilder(RULE).append('\n').append("STUDIO_PYTHON").append('\n');
        b.append(MANIFEST).append('\t').append(fileHash(manifest)).append('\n');
        new TreeMap<>(declared).forEach((k, v) -> b.append(k).append('\t').append(v).append('\n'));
        return hex(sha256(b.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void options(JsonNode m, String mode) throws Rejected {
        JsonNode opts = m.path("cli").path("options");
        if (!opts.isObject() || opts.isEmpty()) {
            throw new Rejected("linha_de_comando_invalida");
        }
        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, JsonNode> e : opts.properties()) {
            if (!List.of("input", "spreadsheet", "output").contains(e.getKey()) || !e.getValue().isString()
                    || !OPTION.matcher(e.getValue().asString()).matches()) {
                throw new Rejected("linha_de_comando_invalida");
            }
            keys.add(e.getKey());
        }
        if (!keys.contains("output") || "list".equals(mode) && !(keys.contains("input") && keys.contains("spreadsheet"))) {
            throw new Rejected("linha_de_comando_invalida");
        }
    }

    private static Map<String, String> declared(JsonNode files) throws Rejected {
        if (files == null || !files.isObject() || files.isEmpty()) {
            throw new Rejected("lista_de_arquivos_ausente");
        }
        if (files.size() > MAX_FILES) {
            throw new Rejected("arquivos_demais");
        }
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, JsonNode> e : files.properties()) {
            String rel = e.getKey();
            if (!e.getValue().isString() || !SHA.matcher(e.getValue().asString()).matches()) {
                throw new Rejected("hash_declarado_invalido");
            }
            if (!safeName(rel) || rel.endsWith("/")) {
                throw new Rejected("caminho_inseguro");
            }
            out.put(rel, e.getValue().asString());
        }
        return out;
    }

    static boolean safeName(String rel) {
        return NAME.matcher(rel).matches() && !rel.contains("..") && !rel.contains("//") && !rel.startsWith("/");
    }

    /** Prefixo comum quando o ZIP traz a pasta do pacote (um unico nivel) em vez dos arquivos na raiz. */
    private static String root(Set<String> names) throws Rejected {
        if (names.contains(MANIFEST)) {
            return "";
        }
        String first = names.iterator().hasNext() ? names.iterator().next() : "";
        int slash = first.indexOf('/');
        if (slash <= 0) {
            throw new Rejected("manifesto_ausente");
        }
        String prefix = first.substring(0, slash + 1);
        for (String n : names) {
            if (!n.startsWith(prefix)) {
                throw new Rejected("manifesto_ausente");
            }
        }
        return prefix;
    }

    // ------------------------------------------------------------------ ZIP (diretorio central + cabecalhos locais)

    private static Map<String, byte[]> read(byte[] zip) throws Rejected {
        ByteBuffer b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);
        int eocd = -1;
        for (int i = zip.length - 22; i >= Math.max(0, zip.length - 22 - 65535); i--) {
            if (b.getInt(i) == 0x06054b50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new Rejected("zip_malformado");
        }
        int disk = Short.toUnsignedInt(b.getShort(eocd + 4));
        int cdDisk = Short.toUnsignedInt(b.getShort(eocd + 6));
        int count = Short.toUnsignedInt(b.getShort(eocd + 10));
        long cdSize = Integer.toUnsignedLong(b.getInt(eocd + 12));
        long cdOffset = Integer.toUnsignedLong(b.getInt(eocd + 16));
        if (disk != 0 || cdDisk != 0 || count == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw new Rejected("zip_nao_suportado");
        }
        if (count > MAX_ENTRIES) {
            throw new Rejected("arquivos_demais");
        }
        if (cdOffset + cdSize > eocd) {
            throw new Rejected("zip_malformado");
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        Set<String> folded = new HashSet<>();
        long total = 0;
        int files = 0;
        int p = (int) cdOffset;
        for (int i = 0; i < count; i++) {
            if (p + 46 > eocd || b.getInt(p) != 0x02014b50) {
                throw new Rejected("zip_malformado");
            }
            int madeBy = Short.toUnsignedInt(b.getShort(p + 4));
            int flags = Short.toUnsignedInt(b.getShort(p + 8));
            int method = Short.toUnsignedInt(b.getShort(p + 10));
            long crc = Integer.toUnsignedLong(b.getInt(p + 16));
            long csize = Integer.toUnsignedLong(b.getInt(p + 20));
            long size = Integer.toUnsignedLong(b.getInt(p + 24));
            int nameLen = Short.toUnsignedInt(b.getShort(p + 28));
            int extraLen = Short.toUnsignedInt(b.getShort(p + 30));
            int commentLen = Short.toUnsignedInt(b.getShort(p + 32));
            long external = Integer.toUnsignedLong(b.getInt(p + 38));
            long local = Integer.toUnsignedLong(b.getInt(p + 42));
            if (p + 46 + nameLen > eocd) {
                throw new Rejected("zip_malformado");
            }
            String name = utf8(zip, p + 46, nameLen);
            p += 46 + nameLen + extraLen + commentLen;
            if ((flags & 1) != 0) {
                throw new Rejected("zip_cifrado");
            }
            if (method != 0 && method != 8) {
                throw new Rejected("zip_nao_suportado");
            }
            if (csize == 0xFFFFFFFFL || size == 0xFFFFFFFFL || local == 0xFFFFFFFFL) {
                throw new Rejected("zip_nao_suportado");
            }
            int unixMode = (int) (external >>> 16) & 0xF000;
            if ((madeBy >>> 8) == 3 && unixMode == 0xA000) {
                throw new Rejected("ligacao_simbolica");
            }
            boolean dir = name.endsWith("/");
            String plain = dir ? name.substring(0, name.length() - 1) : name;
            if (!safeName(plain)) {
                throw new Rejected("caminho_inseguro");
            }
            if (!folded.add(name.toLowerCase(Locale.ROOT))) {
                throw new Rejected("nome_repetido");
            }
            if (dir) {
                if (size != 0) {
                    throw new Rejected("zip_malformado");
                }
                continue;
            }
            if (++files > MAX_FILES) {
                throw new Rejected("arquivos_demais");
            }
            total += size;
            if (size > MAX_CONTENT_BYTES || total > MAX_CONTENT_BYTES) {
                throw new Rejected("tamanho_excedido");
            }
            out.put(name, data(zip, b, new Entry(name, method, flags, csize, size, crc, local), (int) cdOffset));
        }
        return out;
    }

    private static byte[] data(byte[] zip, ByteBuffer b, Entry e, int cdOffset) throws Rejected {
        int p = (int) e.localOffset();
        if (p + 30 > cdOffset || b.getInt(p) != 0x04034b50) {
            throw new Rejected("zip_malformado");
        }
        int nameLen = Short.toUnsignedInt(b.getShort(p + 26));
        int extraLen = Short.toUnsignedInt(b.getShort(p + 28));
        if (p + 30 + nameLen > cdOffset || !utf8(zip, p + 30, nameLen).equals(e.name())) {
            throw new Rejected("zip_ambiguo"); // nome do cabecalho local diferente do diretorio central
        }
        long start = (long) p + 30 + nameLen + extraLen;
        if (start + e.compressedSize() > cdOffset) {
            throw new Rejected("zip_malformado");
        }
        byte[] out;
        if (e.method() == 0) {
            if (e.compressedSize() != e.size()) {
                throw new Rejected("zip_malformado");
            }
            out = java.util.Arrays.copyOfRange(zip, (int) start, (int) (start + e.size()));
        } else {
            Inflater inf = new Inflater(true);
            try {
                inf.setInput(zip, (int) start, (int) e.compressedSize());
                ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.min(e.size(), 1 << 20));
                byte[] buf = new byte[64 * 1024];
                long produced = 0;
                while (!inf.finished()) {
                    int n = inf.inflate(buf);
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
                        throw new Rejected("zip_malformado");
                    }
                    produced += n;
                    if (produced > e.size()) {
                        throw new Rejected("zip_malformado"); // mais bytes do que o declarado (bomba ou adulteracao)
                    }
                    o.write(buf, 0, n);
                }
                out = o.toByteArray();
            } catch (DataFormatException ex) {
                throw new Rejected("zip_malformado");
            } finally {
                inf.end();
            }
        }
        if (out.length != e.size()) {
            throw new Rejected("zip_malformado");
        }
        CRC32 crc = new CRC32();
        crc.update(out);
        if (crc.getValue() != e.crc()) {
            throw new Rejected("zip_corrompido");
        }
        return out;
    }

    private static String utf8(byte[] zip, int off, int len) throws Rejected {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(zip, off, len)).toString();
            for (int i = 0; i < s.length(); i++) {
                if (Character.isISOControl(s.charAt(i))) {
                    throw new Rejected("caminho_inseguro");
                }
            }
            return s;
        } catch (CharacterCodingException e) {
            throw new Rejected("caminho_inseguro");
        }
    }

    private static String text(JsonNode m, String field) {
        JsonNode n = m.get(field);
        return n != null && n.isString() ? n.asString() : null;
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
