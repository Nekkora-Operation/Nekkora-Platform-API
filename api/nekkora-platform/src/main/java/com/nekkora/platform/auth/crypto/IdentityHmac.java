package com.nekkora.platform.auth.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 da identidade (organizacao + e-mail normalizado) para chaves do limitador e para o log
 * (contrato, §2.3/§2.4): o e-mail em claro nunca vai para o Redis nem para o log. Chave do servico de segredos,
 * com key_id para rotacao.
 */
public final class IdentityHmac {

    private final byte[] key;
    private final String keyId;

    public IdentityHmac(String keyId, byte[] key) {
        if (key.length < 32) {
            throw new IllegalArgumentException("chave HMAC precisa de pelo menos 32 bytes");
        }
        this.keyId = keyId;
        this.key = key.clone();
    }

    /** E-mail normalizado como o banco o compara: lower(email) (§2 do relatorio da API; sem trim). */
    public static String normalize(String email) {
        return email == null ? "" : email.toLowerCase(Locale.ROOT);
    }

    public String fromTime(UUID org, String email) {
        return mac(org + "|" + normalize(email));
    }

    /** Variante GLOBAL (entrada geral e cadastro, sem organizacao; desenho da Missao 1, secao 7): "global|" + e-mail normalizado. */
    public String global(String email) {
        return mac("global|" + normalize(email));
    }

    private String mac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return keyId + "." + HexFormat.of().formatHex(h, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
