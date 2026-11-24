package com.nekkora.core.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AMBIENTE-PYTHON-001 (D5): pacotes que precisam de preparo FORA do ambiente temporario - navegadores, drivers, programas do
 * sistema. A Nekkora so IDENTIFICA, AVISA e, quando da, CONFERE; nunca instala navegador nem componente do sistema. O texto da
 * instrucao manual fica na tela (Web); aqui ficam o codigo, o pacote e o que a maquina sabe conferir.
 */
public final class ExternalPrerequisites {

    /** {@code checks}: o que a maquina sabe conferir para este preparo (vazio: so aviso). */
    public record Prerequisite(String code, String packageName, List<String> checks) {
    }

    public static final List<String> PLAYWRIGHT_CHECKS = List.of("PLAYWRIGHT_CHROMIUM", "PLAYWRIGHT_FIREFOX", "PLAYWRIGHT_WEBKIT");
    /** As verificacoes que um aceite pode pedir. */
    public static final Set<String> CHECKS = Set.copyOf(PLAYWRIGHT_CHECKS);

    /** Nome normalizado (PEP 503) -> codigo. */
    private static final Map<String, String> CATALOG = new LinkedHashMap<>();
    static {
        CATALOG.put("playwright", "PLAYWRIGHT_BROWSER");
        CATALOG.put("selenium", "SELENIUM_DRIVER");
        CATALOG.put("pyodbc", "ODBC_DRIVER");
        CATALOG.put("oracledb", "ORACLE_CLIENT");
        CATALOG.put("cx-oracle", "ORACLE_CLIENT");
        CATALOG.put("pytesseract", "TESSERACT");
        CATALOG.put("pdf2image", "POPPLER");
        CATALOG.put("pdfkit", "WKHTMLTOPDF");
        CATALOG.put("imgkit", "WKHTMLTOPDF");
        CATALOG.put("tabula-py", "JAVA_RUNTIME");
        CATALOG.put("jpype1", "JAVA_RUNTIME");
    }

    private ExternalPrerequisites() {
    }

    /** Os preparos externos dos pacotes indicados (nomes de lock ou de requisitos), na ordem do catalogo, sem repetir pacote. */
    public static List<Prerequisite> of(List<String> names) {
        Set<String> present = new java.util.HashSet<>();
        for (String n : names) {
            present.add(ManagedLock.normalize(n));
        }
        List<Prerequisite> out = new ArrayList<>();
        CATALOG.forEach((name, code) -> {
            if (present.contains(name)) {
                out.add(new Prerequisite(code, name, code.equals("PLAYWRIGHT_BROWSER") ? PLAYWRIGHT_CHECKS : List.of()));
            }
        });
        return List.copyOf(out);
    }

    /** O lock tem playwright? Entao o aceite precisa escolher ao menos um navegador a conferir. */
    public static boolean needsBrowserChoice(List<String> names) {
        return names.stream().map(ManagedLock::normalize).anyMatch("playwright"::equals);
    }
}
