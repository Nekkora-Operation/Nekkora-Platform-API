package com.nekkora.portal;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nekkora.core.web.ApiException;

/**
 * Cursor keyset opaco (contrato 8.7, §5.4): base64url de {"c":createdAt,"i":executionId}. Nao e autoridade - a
 * funcao da 013 reaplica a concessao. Malformado -> 400.
 */
public record Cursor(OffsetDateTime createdAt, UUID execution) {

    private static final Pattern FORMAT = Pattern.compile("^\\{\"c\":\"([0-9T:.+\\-Z]{20,40})\",\"i\":\"([0-9a-f-]{36})\"}$");

    public String encode() {
        String json = "{\"c\":\"" + createdAt + "\",\"i\":\"" + execution + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String s) {
        if (s == null || s.length() > 200) {
            throw ApiException.invalid("cursor");
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
            Matcher m = FORMAT.matcher(json);
            if (!m.matches()) {
                throw ApiException.invalid("cursor");
            }
            return new Cursor(OffsetDateTime.parse(m.group(1)), UUID.fromString(m.group(2)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw ApiException.invalid("cursor");
        }
    }
}
