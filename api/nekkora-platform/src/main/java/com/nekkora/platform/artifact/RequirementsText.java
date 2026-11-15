package com.nekkora.platform.artifact;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitura ESTATICA de um texto no formato do {@code requirements.txt} (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c): so
 * texto. Nada e instalado, nada e resolvido na rede, nenhum arquivo incluido ({@code -r}, {@code -c}) e nenhum endereco
 * remoto sao seguidos.
 *
 * <p>PORTADA de {@code com.nekkora.client.pack.RequirementsFile} (Nekkora-Agent-Client, AGENT-CLIENT-PREPARACAO-002 T04), com
 * as MESMAS regras: as duas implementacoes sao presas pelos mesmos vetores de teste ({@code requirements-vectors.json},
 * copia identica nos dois repositorios). Mudou a regra numa, muda na outra e nos vetores.
 *
 * <p>So {@code nome==versao} (com extras opcionais) e representavel, sem repetir. Cada linha recebe uma situacao: ou e uma
 * dependencia exata, ou e dita POR LINHA como algo que nao se representa (faixa, sem versao, marcador de ambiente, endereco
 * remoto, caminho local, opcao do pip, hash, inclusao de outro arquivo). Nenhuma linha e descartada em silencio, nenhuma
 * versao e inventada, e um texto com problema nunca e tratado como "sem dependencias".
 */
public final class RequirementsText {

    private RequirementsText() {
    }

    /** Tamanho maximo lido, em caracteres (o do Client e 1 MiB em bytes; aqui o texto ja chegou decodificado). */
    public static final int MAX_CHARS = 256 * 1024;

    /** A situacao de uma linha. {@code problem}: impede salvar ate ser ajustada. */
    public enum Kind {
        EXACT(false), DUPLICATE(false), CONFLICT(true), RANGE(true), NO_VERSION(true), MARKER(true), REMOTE(true), LOCAL_PATH(true), INCLUDE(true),
        OPTION(true), HASH(true), UNKNOWN(true);

        private final boolean problem;

        Kind(boolean problem) {
            this.problem = problem;
        }

        public boolean problem() {
            return problem;
        }
    }

    /**
     * Uma linha com conteudo (comentarios e linhas em branco nao aparecem).
     *
     * @param number numero da linha no texto (a primeira, quando a linha continua com {@code \})
     * @param text a linha sem o comentario, saneada para exibicao e limitada
     * @param dependency {@code nome[extras]==versao} quando a linha e uma dependencia exata; senao {@code null}
     */
    public record Line(int number, String text, Kind kind, String dependency) {
    }

    /** {@code dependencies}: as dependencias exatas, sem repeticao, na ordem em que aparecem. */
    public record Parsed(List<Line> lines, List<String> dependencies) {
        public Parsed {
            lines = List.copyOf(lines);
            dependencies = List.copyOf(dependencies);
        }

        /** Todas as linhas sao representaveis. */
        public boolean resolved() {
            return lines.stream().noneMatch(l -> l.kind().problem());
        }
    }

    private static final Pattern DEPENDENCY = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{0,79})(\\[[A-Za-z0-9,._-]{1,80}])?==([0-9][0-9A-Za-z.+!-]{0,40})");
    private static final Pattern EXACT = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{0,79})\\s*(\\[\\s*[A-Za-z0-9,._\\s-]{1,80}])?\\s*==\\s*([0-9][0-9A-Za-z.+!-]{0,40})");
    private static final Pattern NAME_ONLY = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{0,79})\\s*(\\[[^\\]]*])?");
    private static final Pattern NAME_THEN_SPEC = Pattern.compile("([A-Za-z0-9][A-Za-z0-9._-]{0,79})\\s*(\\[[^\\]]*])?\\s*(===|==|~=|!=|>=|<=|>|<).*");
    private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:[\\\\/].*");

    public static Parsed parse(String text) {
        List<Line> lines = new ArrayList<>();
        String[] raw = (text == null ? "" : text).split("\\r?\\n|\\r", -1);
        for (int i = 0; i < raw.length; i++) {
            int number = i + 1;
            StringBuilder logical = new StringBuilder();
            // continuacao de linha do pip: "\" no fim junta com a proxima
            while (true) {
                String part = raw[i];
                if (part.endsWith("\\") && i + 1 < raw.length) {
                    logical.append(part, 0, part.length() - 1).append(' ');
                    i++;
                } else {
                    logical.append(part);
                    break;
                }
            }
            String content = stripComment(logical.toString()).strip();
            if (content.isEmpty()) {
                continue;
            }
            lines.add(classify(number, content));
        }
        return dedupe(lines);
    }

    /** Comentario do pip: {@code #} no comeco da linha ou depois de um espaco (um {@code #} dentro de um endereco nao e comentario). */
    private static String stripComment(String line) {
        if (line.stripLeading().startsWith("#")) {
            return "";
        }
        for (int i = 1; i < line.length(); i++) {
            if (line.charAt(i) == '#' && Character.isWhitespace(line.charAt(i - 1))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static Line classify(int number, String content) {
        String shown = display(content.length() > 200 ? content.substring(0, 200) + "…" : content);
        String low = content.toLowerCase(Locale.ROOT);
        if (low.startsWith("-r ") || low.startsWith("-r\t") || low.startsWith("--requirement") || low.startsWith("-c ") || low.startsWith("-c\t")
                || low.startsWith("--constraint") || low.equals("-r") || low.equals("-c")) {
            return new Line(number, shown, Kind.INCLUDE, null);
        }
        if (low.startsWith("-e ") || low.startsWith("-e\t") || low.startsWith("--editable")) {
            return new Line(number, shown, Kind.LOCAL_PATH, null);
        }
        if (content.startsWith("-")) {
            return new Line(number, shown, Kind.OPTION, null);
        }
        if (low.contains("--hash")) {
            return new Line(number, shown, Kind.HASH, null);
        }
        if (low.contains("://") || low.startsWith("git+") || content.contains(" @ ") || content.matches("[^\\s;]+@\\S+.*") && content.contains("/")) {
            return new Line(number, shown, Kind.REMOTE, null);
        }
        if (content.startsWith(".") || content.startsWith("/") || content.startsWith("\\") || content.startsWith("~") || DRIVE.matcher(content).matches()
                || low.endsWith(".whl") || low.endsWith(".tar.gz") || low.endsWith(".zip")) {
            return new Line(number, shown, Kind.LOCAL_PATH, null);
        }
        if (content.contains(";")) {
            return new Line(number, shown, Kind.MARKER, null);
        }
        if (low.contains(" --") || content.matches(".*\\s-[A-Za-z]\\b.*")) {
            return new Line(number, shown, Kind.OPTION, null);
        }
        Matcher exact = EXACT.matcher(content);
        if (exact.matches()) {
            String extras = exact.group(2) == null ? "" : exact.group(2).replaceAll("\\s+", "");
            String dependency = exact.group(1) + extras + "==" + exact.group(3);
            if (DEPENDENCY.matcher(dependency).matches()) {
                return new Line(number, shown, Kind.EXACT, dependency);
            }
            return new Line(number, shown, Kind.UNKNOWN, null);
        }
        if (NAME_ONLY.matcher(content).matches()) {
            return new Line(number, shown, Kind.NO_VERSION, null);
        }
        if (NAME_THEN_SPEC.matcher(content).matches()) {
            return new Line(number, shown, Kind.RANGE, null);
        }
        return new Line(number, shown, Kind.UNKNOWN, null);
    }

    /** Mesmo nome (normalizado): igual em tudo, a segunda e so "repetida"; diferente em versao ou extras, as duas sao conflito. */
    private static Parsed dedupe(List<Line> lines) {
        Map<String, Integer> first = new LinkedHashMap<>();
        Map<String, String> canonical = new LinkedHashMap<>();
        Line[] out = lines.toArray(new Line[0]);
        Set<String> conflicted = new HashSet<>();
        for (int i = 0; i < out.length; i++) {
            Line l = out[i];
            if (l.kind() != Kind.EXACT) {
                continue;
            }
            String key = key(l.dependency());
            String same = sameness(l.dependency());
            if (!first.containsKey(key)) {
                first.put(key, i);
                canonical.put(key, same);
            } else if (canonical.get(key).equals(same) && !conflicted.contains(key)) {
                out[i] = new Line(l.number(), l.text(), Kind.DUPLICATE, null);
            } else {
                conflicted.add(key);
                out[i] = new Line(l.number(), l.text(), Kind.CONFLICT, null);
                Line f = out[first.get(key)];
                out[first.get(key)] = new Line(f.number(), f.text(), Kind.CONFLICT, null);
            }
        }
        List<String> dependencies = new ArrayList<>();
        for (Line l : out) {
            if (l.kind() == Kind.EXACT) {
                dependencies.add(l.dependency());
            }
        }
        return new Parsed(List.of(out), dependencies);
    }

    /** Nome de distribuicao normalizado (PEP 503, como no validador do pacote): sem caixa, {@code _} e {@code .} viram {@code -}. */
    public static String normalized(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', '-').replace('.', '-');
    }

    /** A chave de uma dependencia {@code nome[extras]==versao}: o nome normalizado. */
    public static String key(String dependency) {
        return normalized(dependency.replaceAll("\\[.*", "").split("==", 2)[0]);
    }

    /** Nome normalizado + extras (sem caixa, ordenados) + versao: duas declaracoes com isto igual sao a mesma. */
    public static String sameness(String dependency) {
        String[] nv = dependency.split("==", 2);
        String extras = "";
        int at = nv[0].indexOf('[');
        if (at >= 0) {
            List<String> e = new ArrayList<>(List.of(nv[0].substring(at + 1, nv[0].length() - 1).toLowerCase(Locale.ROOT).split(",")));
            e.sort(null);
            extras = String.join(",", e);
        }
        return key(dependency) + "[" + extras + "]==" + nv[1];
    }

    /** Texto para exibicao: controles, marcas de direcao e caracteres de formato viram {@code ?}; limitado a 120 caracteres. */
    static String display(String name) {
        StringBuilder b = new StringBuilder();
        name.codePoints().forEach(cp -> {
            int t = Character.getType(cp);
            boolean bidi = cp >= 0x202A && cp <= 0x202E || cp >= 0x2066 && cp <= 0x2069 || cp == 0x200E || cp == 0x200F;
            b.appendCodePoint(Character.isISOControl(cp) || bidi || t == Character.FORMAT ? '?' : cp);
        });
        String s = b.toString();
        return s.length() > 120 ? s.substring(0, 60) + "…" + s.substring(s.length() - 59) : s;
    }
}
