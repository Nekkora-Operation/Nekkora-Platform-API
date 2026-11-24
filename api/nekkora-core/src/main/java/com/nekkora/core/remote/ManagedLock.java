package com.nekkora.core.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AMBIENTE-PYTHON-001 (076, D1): a LISTA COMPLETA que a maquina resolve (nome, versao, sha256 e arquivo de cada wheel) e que a
 * pessoa aceita. Regras unicas para o canal da maquina (que recebe a resposta) e para a Platform (que mostra e aceita).
 *
 * <p>O hash do lock e calculado pela Platform: SHA-256 das linhas {@code nome-normalizado==versao sha256}, ordenadas, separadas
 * por {@code \n}. O nome do arquivo nao entra no hash (o sha256 ja identifica o wheel); a maquina nao manda o hash.
 */
public final class ManagedLock {

    /** Uma linha do lock. {@code fileName}: o nome do wheel (sem caminho), so para mostrar. */
    public record Item(String name, String version, String sha256, String fileName) {
    }

    public static final int MAX_ITEMS = 400;
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9._-]{0,78}[A-Za-z0-9])?$");
    private static final Pattern VERSION = Pattern.compile("^[0-9][0-9A-Za-z.+!-]{0,40}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern WHEEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+!-]{0,195}\\.whl$");
    /** {@code nome[extras]==versao}, como a 045 aceita (a lista efetiva). */
    private static final Pattern REQUIREMENT = Pattern.compile("^([A-Za-z0-9][A-Za-z0-9._-]{0,79})(\\[[A-Za-z0-9,._-]{1,80}\\])?==([0-9][0-9A-Za-z.+!-]{0,40})$");

    private ManagedLock() {
    }

    /** PEP 503: minusculas, e qualquer sequencia de {@code -}, {@code _} e {@code .} vira um {@code -}. */
    public static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    /**
     * O lock e valido para a lista pedida? Itens bem formados, sem nome repetido (normalizado), no maximo 400, e CADA dependencia
     * pedida presente com a MESMA versao. Devolve o motivo da recusa, ou {@code null}.
     */
    public static String problem(List<Item> lock, List<String> requested) {
        if (lock == null || lock.size() > MAX_ITEMS) {
            return "lock";
        }
        Map<String, String> versions = new HashMap<>();
        for (Item i : lock) {
            if (i == null || i.name() == null || !NAME.matcher(i.name()).matches() || i.version() == null || !VERSION.matcher(i.version()).matches()
                    || i.sha256() == null || !SHA256.matcher(i.sha256()).matches() || i.fileName() == null || !WHEEL.matcher(i.fileName()).matches()) {
                return "item";
            }
            if (versions.put(normalize(i.name()), i.version()) != null) {
                return "repetido";
            }
        }
        for (String r : requested) {
            java.util.regex.Matcher m = REQUIREMENT.matcher(r);
            if (!m.matches()) {
                return "pedido";
            }
            if (!m.group(3).equals(versions.get(normalize(m.group(1))))) {
                return "pedido_fora_do_lock";
            }
        }
        return null;
    }

    /** O hash do lock (a Platform calcula; a maquina nao manda). */
    public static String sha256(List<Item> lock) {
        List<String> lines = new ArrayList<>();
        for (Item i : lock) {
            lines.add(normalize(i.name()) + "==" + i.version() + " " + i.sha256());
        }
        lines.sort(Comparator.naturalOrder());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Os nomes normalizados da lista efetiva (para dizer quais itens do lock foram declarados e quais sao indiretos). */
    public static List<String> declaredNames(List<String> requested) {
        List<String> out = new ArrayList<>();
        for (String r : requested) {
            java.util.regex.Matcher m = REQUIREMENT.matcher(r);
            if (m.matches()) {
                out.add(normalize(m.group(1)));
            }
        }
        return out;
    }
}
