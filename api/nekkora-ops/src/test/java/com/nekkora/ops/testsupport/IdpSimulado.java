package com.nekkora.ops.testsupport;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * IdP CONTROLADO de teste: emite tokens RS256 com o {@code auth_time} e o {@code amr} que o teste escolher, para
 * exercitar identidade, metodo de MFA e recencia na emissao da CA. Chave RSA por execucao.
 */
public final class IdpSimulado {

    public static final String EMISSOR = "https://idp.nekkora.test";
    public static final String AUDIENCIA = "nekkora-ops-ca";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final KeyPair chaves = Pki.rsa();

    public PublicKey chavePublica() {
        return chaves.getPublic();
    }

    public static String sujeito(String papel) {
        return "sub-" + papel;
    }

    /** acr e amr de uma autenticacao que cumpre a garantia: resistente a phishing com hardware, MFA afirmado. */
    public static final String ACR_OK = "phrh";
    public static final List<String> AMR_OK = List.of("hwk", "pin", "mfa");

    /** Token valido: autenticacao agora, MFA resistente a phishing com chave de hardware. */
    public String token(String papel) {
        Instant agora = Instant.now();
        return token(EMISSOR, sujeito(papel), AUDIENCIA, agora, ACR_OK, AMR_OK, agora.plusSeconds(300), chaves.getPrivate());
    }

    /** acr = ACR_OK; so o amr varia. */
    public String token(String emissor, String sujeito, String audiencia, Instant authTime, List<String> amr, Instant exp) {
        return token(emissor, sujeito, audiencia, authTime, ACR_OK, amr, exp, chaves.getPrivate());
    }

    /** acr nulo = claim ausente. */
    public String token(String emissor, String sujeito, String audiencia, Instant authTime, String acr, List<String> amr, Instant exp) {
        return token(emissor, sujeito, audiencia, authTime, acr, amr, exp, chaves.getPrivate());
    }

    public static String token(String emissor, String sujeito, String audiencia, Instant authTime, List<String> amr, Instant exp,
                               PrivateKey chave) {
        return token(emissor, sujeito, audiencia, authTime, ACR_OK, amr, exp, chave);
    }

    public static String token(String emissor, String sujeito, String audiencia, Instant authTime, String acr, List<String> amr,
                               Instant exp, PrivateKey chave) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", emissor);
        c.put("sub", sujeito);
        c.put("aud", audiencia);
        c.put("iat", Instant.now().getEpochSecond());
        c.put("exp", exp.getEpochSecond());
        c.put("auth_time", authTime.getEpochSecond());
        if (acr != null) {
            c.put("acr", acr);
        }
        c.put("amr", amr);
        return assinar(Map.of("alg", "RS256", "typ", "JWT"), c, chave);
    }

    public static String assinar(Map<String, Object> cabecalho, Map<String, Object> corpo, PrivateKey chave) {
        try {
            String h = b64(JSON.writeValueAsBytes(cabecalho));
            String p = b64(JSON.writeValueAsBytes(corpo));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(chave);
            s.update((h + "." + p).getBytes(StandardCharsets.US_ASCII));
            return h + "." + p + "." + b64(s.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
