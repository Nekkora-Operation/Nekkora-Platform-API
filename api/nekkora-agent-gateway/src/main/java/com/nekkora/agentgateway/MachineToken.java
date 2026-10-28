package com.nekkora.agentgateway;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.nekkora.core.token.Tokens;

/**
 * Material da maquina (contrato, sec. 4): {@code <prefixo>.<organization_id>.<id>.<segredo>}. Prefixo {@code nka1} =
 * credencial (id = credential_id); {@code nkp1} = codigo de pareamento ou rotacao (id = enrollment_id). O segredo e um
 * token D2 (32 bytes em base64url canonico). Os identificadores sao UUID minusculos canonicos. Nada disso concede acesso
 * por si: a organizacao so serve para escolher o contexto da RLS, e a linha so e encontrada se for dela E o hash conferir.
 */
public record MachineToken(String prefix, UUID organization, UUID id, String secret, String raw) {

    public static final String CREDENTIAL = "nka1";
    public static final String ENROLLMENT = "nkp1";
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern FORMAT = Pattern.compile("(nka1|nkp1)\\.(" + UUID_RE + ")\\.(" + UUID_RE + ")\\.([A-Za-z0-9_-]{43})");

    public static Optional<MachineToken> parse(String raw, String expectedPrefix) {
        if (raw == null || raw.length() > 200) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(raw);
        if (!m.matches() || !m.group(1).equals(expectedPrefix) || !Tokens.isValidFormat(m.group(4))) {
            return Optional.empty();
        }
        return Optional.of(new MachineToken(m.group(1), UUID.fromString(m.group(2)), UUID.fromString(m.group(3)), m.group(4), raw));
    }

    /** {@code Authorization: Bearer nka1...}. */
    public static Optional<MachineToken> bearer(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        return parse(header.substring("Bearer ".length()), CREDENTIAL);
    }

    public static String credential(UUID organization, UUID credentialId, String secret) {
        return CREDENTIAL + "." + organization + "." + credentialId + "." + secret;
    }

    public static String enrollment(UUID organization, UUID enrollmentId, String secret) {
        return ENROLLMENT + "." + organization + "." + enrollmentId + "." + secret;
    }

    @Override
    public String toString() {
        return prefix + "." + organization + "." + id + ".<segredo>";
    }
}
