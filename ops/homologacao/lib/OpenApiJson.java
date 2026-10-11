import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * OpenAPI (YAML) -> JSON para o validador das jornadas do ensaio-frontend (F1.5, P-A): o Python do ambiente nao tem
 * leitor de YAML (sem PyPI); a leitura e a mesma da validacao estrutural aprovada (SnakeYAML 2.6, construtor seguro).
 * Grava em "x-origem-yaml" o sha256 dos bytes do YAML lido, que as jornadas conferem contra o arquivo do repositorio.
 *
 * Uso: java -cp snakeyaml-2.6.jar OpenApiJson.java <openapi.yaml> <saida.json>   (a saida e criada nova)
 */
public final class OpenApiJson {

    public static void main(String[] a) throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(a[0]));
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(new String(bytes, StandardCharsets.UTF_8));
        if (!(doc instanceof Map<?, ?> raiz)) {
            throw new IllegalStateException("OpenAPI sem objeto raiz");
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        StringBuilder sb = new StringBuilder();
        sb.append("{\"x-origem-yaml\":{\"sha256\":").append(texto(sha)).append('}');
        for (Map.Entry<?, ?> e : raiz.entrySet()) {
            sb.append(',').append(texto(String.valueOf(e.getKey()))).append(':');
            json(sb, e.getValue());
        }
        sb.append('}');
        Path saida = Path.of(a[1]);
        if (Files.exists(saida)) {
            throw new IllegalStateException("saida ja existe: " + saida);
        }
        Files.writeString(saida, sb, StandardCharsets.UTF_8);
        System.out.println("OpenAPI convertido: yaml sha256=" + sha);
    }

    private static void json(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean primeiro = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!primeiro) {
                    sb.append(',');
                }
                primeiro = false;
                sb.append(texto(String.valueOf(e.getKey()))).append(':');
                json(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                json(sb, l.get(i));
            }
            sb.append(']');
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger) {
            sb.append(v);
        } else if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new IllegalStateException("numero nao representavel em JSON: " + d);
            }
            sb.append(d);
        } else if (v instanceof String s) {
            sb.append(texto(s));
        } else {
            // datas e outros escalares do YAML 1.1 viram texto: o contrato nao usa nenhum, e a conversao fica visivel
            throw new IllegalStateException("escalar YAML de tipo inesperado (" + v.getClass().getName() + "): " + v);
        }
    }

    private static String texto(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
