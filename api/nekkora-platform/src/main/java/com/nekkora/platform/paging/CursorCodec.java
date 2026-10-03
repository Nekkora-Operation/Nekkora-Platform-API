package com.nekkora.platform.paging;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.nekkora.core.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cursor opaco e assinado das rotas internas paginadas (contrato GAP-02/03/06, §1.5 e §1.5.1; DC-15):
 * {@code base64url(payload) "." base64url(HMAC-SHA256(K_cursor, payload))}. O payload JSON vincula versao, impressao do id
 * da chave, rota, organizacao, vinculo da sessao, recurso pai, impressao dos filtros e ordenacao, e guarda a posicao (a
 * chave de ordenacao, de tamanho limitado). K_cursor e subchave derivada da chave HMAC de servico com finalidade propria;
 * a rotacao da chave de servico invalida os cursores emitidos. Qualquer falha de leitura -> 400 sem dizer qual. O
 * cursor nao e autoridade: as rotas reaplicam a autorizacao a cada pagina.
 */
public final class CursorCodec {

    /** Maior cursor aceito; o servidor nunca emite cursor acima disso (pior caso calculado: 872, §1.5.1). */
    public static final int MAX_LENGTH = 1024;
    public static final String DERIVATION_LABEL = "nekkora/api-cursor/v1";
    static final int VERSION = 1;

    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
    private static final Pattern ROUTE = Pattern.compile("^[A-Za-z]{1,32}$");
    private static final Pattern SORT = Pattern.compile("^[A-Za-z0-9.+_]{1,32}$");
    private static final List<String> FIELDS = List.of("v", "k", "r", "o", "m", "p", "f", "s", "pos");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final String currentFingerprint;
    private final Map<String, byte[]> keysByFingerprint;

    /** Uma chave de servico (a atual). Cursores de outra chave sao recusados. */
    public CursorCodec(String serviceKeyId, byte[] serviceKey) {
        this(serviceKeyId, Map.of(serviceKeyId, serviceKey));
    }

    /** Chave atual mais chaves aceitas na leitura (janela de transicao, se a configuracao a tiver). */
    public CursorCodec(String currentServiceKeyId, Map<String, byte[]> serviceKeysById) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        serviceKeysById.forEach((id, key) -> keys.put(fingerprint(id), derive(key)));
        this.currentFingerprint = fingerprint(Objects.requireNonNull(currentServiceKeyId));
        if (!keys.containsKey(currentFingerprint)) {
            throw new IllegalStateException("chave atual do cursor ausente");
        }
        this.keysByFingerprint = Collections.unmodifiableMap(keys);
    }

    /** K_cursor = HMAC-SHA256(chave de servico, "nekkora/api-cursor/v1"). */
    public static byte[] derive(byte[] serviceKey) {
        if (serviceKey == null || serviceKey.length < 32) {
            throw new IllegalStateException("chave HMAC de servico precisa de pelo menos 32 bytes");
        }
        return hmac(serviceKey, DERIVATION_LABEL.getBytes(StandardCharsets.US_ASCII));
    }

    /** Impressao de tamanho fixo (11 caracteres) do id da chave: o hmacId da configuracao nao tem limite de tamanho. */
    public static String fingerprint(String keyId) {
        return B64.encodeToString(sha256(keyId.getBytes(StandardCharsets.UTF_8))).substring(0, 11);
    }

    /** Vinculo do cursor a requisicao. {@code parent} nulo nas rotas sem recurso pai; {@code filters} vem de {@link #filters}. */
    public record Scope(String route, UUID organization, UUID membership, UUID parent, String filters, String sort) {
        public Scope {
            if (route == null || !ROUTE.matcher(route).matches() || sort == null || !SORT.matcher(sort).matches()
                    || organization == null || membership == null || filters == null || filters.length() != 43) {
                throw new IllegalStateException("escopo de cursor invalido (erro de programacao)");
            }
        }
    }

    /**
     * Impressao canonica dos filtros (nomes na ordem fixa da rota): colecoes sem repeticao e ordenadas; datas como
     * instante UTC com precisao de microssegundo; ausente = null. {@code limit} nunca entra.
     */
    public static String filters(Object... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalStateException("filtros em pares nome/valor");
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            canonical.put((String) nameValuePairs[i], normalize(nameValuePairs[i + 1]));
        }
        return B64.encodeToString(sha256(JSON.writeValueAsBytes(canonical)));
    }

    private static Object normalize(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Collection<?> c) {
            List<String> out = new ArrayList<>(new TreeSet<>(c.stream().map(x -> String.valueOf(normalize(x))).toList()));
            return out;
        }
        if (v instanceof OffsetDateTime t) {
            return t.toInstant().truncatedTo(ChronoUnit.MICROS).toString();
        }
        if (v instanceof Instant t) {
            return t.truncatedTo(ChronoUnit.MICROS).toString();
        }
        return v.toString();
    }

    /** Cursor da proxima pagina. {@code position}: valores String, UUID, Long/Integer ou OffsetDateTime. */
    public String encode(Scope scope, List<?> position) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", VERSION);
        payload.put("k", currentFingerprint);
        payload.put("r", scope.route());
        payload.put("o", scope.organization().toString());
        payload.put("m", scope.membership().toString());
        payload.put("p", scope.parent() == null ? null : scope.parent().toString());
        payload.put("f", scope.filters());
        payload.put("s", scope.sort());
        List<Object> pos = new ArrayList<>();
        for (Object o : position) {
            pos.add(o instanceof Number n ? (Object) n.longValue()
                    : o instanceof OffsetDateTime t ? normalize(t) : o.toString());
        }
        payload.put("pos", pos);
        byte[] bytes = JSON.writeValueAsBytes(payload);
        String cursor = B64.encodeToString(bytes) + "." + B64.encodeToString(hmac(keysByFingerprint.get(currentFingerprint), bytes));
        if (cursor.length() > MAX_LENGTH) {
            // invariante do contrato (§1.5.1): com os campos limitados nao ocorre; se ocorrer e erro de programacao (500)
            throw new IllegalStateException("cursor acima do limite: " + cursor.length());
        }
        return cursor;
    }

    /** Le e confere o cursor contra o escopo da requisicao atual. Qualquer falha -> 400 (sem dizer qual). */
    public Position decode(String cursor, Scope expected) {
        try {
            if (cursor == null || cursor.length() > MAX_LENGTH || !FORMAT.matcher(cursor).matches()) {
                throw invalid();
            }
            int dot = cursor.indexOf('.');
            byte[] payload = B64D.decode(cursor.substring(0, dot));
            byte[] tag = B64D.decode(cursor.substring(dot + 1));
            JsonNode n = JSON.readTree(payload);
            if (n == null || !n.isObject() || n.size() != FIELDS.size()) {
                throw invalid();
            }
            for (String f : FIELDS) {
                if (!n.has(f)) {
                    throw invalid();
                }
            }
            byte[] key = n.get("k").isString() ? keysByFingerprint.get(n.get("k").asString()) : null;
            if (key == null || tag.length != 32 || !MessageDigest.isEqual(tag, hmac(key, payload))) {
                throw invalid();
            }
            if (!n.get("v").isIntegralNumber() || n.get("v").asInt() != VERSION
                    || !text(n, "r").equals(expected.route())
                    || !text(n, "o").equals(expected.organization().toString())
                    || !text(n, "m").equals(expected.membership().toString())
                    || !Objects.equals(n.get("p").isNull() ? null : text(n, "p"), expected.parent() == null ? null : expected.parent().toString())
                    || !text(n, "f").equals(expected.filters())
                    || !text(n, "s").equals(expected.sort())
                    || !n.get("pos").isArray()) {
                throw invalid();
            }
            List<JsonNode> pos = new ArrayList<>();
            n.get("pos").forEach(pos::add);
            return new Position(pos);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isString()) {
            throw invalid();
        }
        return v.asString();
    }

    static ApiException invalid() {
        return ApiException.invalid("cursor");
    }

    /** Posicao lida do cursor; cada acessor confere o tipo (divergencia -> 400). */
    public static final class Position {
        private final List<JsonNode> values;

        Position(List<JsonNode> values) {
            this.values = values;
        }

        public Position expectSize(int n) {
            if (values.size() != n) {
                throw invalid();
            }
            return this;
        }

        private JsonNode at(int i) {
            if (i < 0 || i >= values.size()) {
                throw invalid();
            }
            return values.get(i);
        }

        public String text(int i) {
            JsonNode v = at(i);
            if (!v.isString()) {
                throw invalid();
            }
            return v.asString();
        }

        public UUID uuid(int i) {
            String s = text(i);
            try {
                UUID u = UUID.fromString(s);
                if (u.toString().equals(s)) {
                    return u;
                }
            } catch (IllegalArgumentException e) {
                // cai no 400 abaixo
            }
            throw invalid();
        }

        public long integer(int i) {
            JsonNode v = at(i);
            if (!v.isIntegralNumber() || !v.canConvertToLong()) {
                throw invalid();
            }
            return v.asLong();
        }

        public OffsetDateTime instant(int i) {
            try {
                return OffsetDateTime.parse(text(i));
            } catch (java.time.DateTimeException e) {
                throw invalid();
            }
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponivel", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }
}
