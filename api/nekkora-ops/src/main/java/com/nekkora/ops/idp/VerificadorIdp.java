package com.nekkora.ops.idp;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verificacao do token de identidade do IdP corporativo antes de a CA operacional emitir um certificado (contrato
 * 3.2): assinatura RS256 com a chave configurada, emissor, audiencia, validade e, sobretudo, autenticacao RECENTE
 * ({@code auth_time} ate 5 min) com MFA RESISTENTE A PHISHING. Uma sessao antiga do IdP, ou um token renovado (que
 * preserva o {@code auth_time} original), e negada.
 *
 * <p><b>Garantia exigida do IdP</b> (todas, para ESTE evento de autenticacao):
 * <ol>
 * <li>{@code acr} = {@code phr} ou {@code phrh} (OpenID Connect EAP ACR Values 1.0): o IdP afirma autenticacao
 * resistente a phishing (phrh: com chave protegida por hardware). Sem isso: {@code METODO_NAO_RESISTENTE};</li>
 * <li>{@code amr} contem {@code mfa} (RFC 8176): o IdP afirma MULTIPLOS fatores. Sem isso: {@code MFA_NAO_AFIRMADO};</li>
 * <li>{@code amr} contem prova de posse de chave ({@code hwk}, {@code swk} ou {@code pop}); para {@code phrh}, {@code hwk}.
 * Uma afirmacao de resistencia sem prova de posse e contraditoria: {@code ASSERCAO_INCONSISTENTE}.</li>
 * </ol>
 * Um {@code amr} isolado (por exemplo so {@code hwk}, que prova posse de chave de hardware mas nao multiplos fatores nem
 * resistencia a phishing) NAO basta. A garantia e tao boa quanto a configuracao do IdP que emite o {@code acr}:
 * dependencia de implantacao (o IdP so pode emitir {@code phr}/{@code phrh} quando a politica de acesso exigiu o metodo
 * resistente nesta autenticacao). O metodo registrado no banco e {@code <acr>+mfa}.
 *
 * <p>Os motivos de negacao sao codigos; o token nunca e registrado.
 */
public final class VerificadorIdp {

    /** Idade maxima da autenticacao no IdP (contrato 3.2). Fixa. */
    public static final Duration MFA_RECENTE = Duration.ofMinutes(5);
    /** Tolerancia de relogio para {@code iat}/{@code nbf}/{@code auth_time} no futuro. */
    private static final Duration TOLERANCIA = Duration.ofSeconds(30);
    /** Valores de {@code acr} aceitos (OpenID Connect EAP ACR Values 1.0). Fixos. */
    public static final Set<String> ACR_RESISTENTES = Set.of("phr", "phrh");
    /** Metodos de {@code amr} que provam posse de chave (RFC 8176 hwk/swk; EAP pop). */
    public static final Set<String> AMR_POSSE = Set.of("hwk", "swk", "pop");

    public record Identidade(String emissor, String sujeito, Instant authTime, String metodo) {
    }

    public static final class TokenRecusado extends Exception {
        private final String motivo;

        public TokenRecusado(String motivo) {
            super(motivo);
            this.motivo = motivo;
        }

        public String motivo() {
            return motivo;
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String emissor;
    private final String audiencia;
    private final PublicKey chave;
    private final Clock relogio;

    public VerificadorIdp(String emissor, String audiencia, PublicKey chave, Clock relogio) {
        this.emissor = Objects.requireNonNull(emissor);
        this.audiencia = Objects.requireNonNull(audiencia);
        this.chave = Objects.requireNonNull(chave);
        this.relogio = Objects.requireNonNull(relogio);
    }

    public Identidade verificar(String token) throws TokenRecusado {
        if (token == null) {
            throw new TokenRecusado("TOKEN_AUSENTE");
        }
        String[] partes = token.split("\\.", -1);
        if (partes.length != 3) {
            throw new TokenRecusado("TOKEN_MALFORMADO");
        }
        JsonNode cabecalho = json(partes[0]);
        if (!"RS256".equals(texto(cabecalho, "alg"))) {
            throw new TokenRecusado("ALGORITMO_NAO_ACEITO");
        }
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(chave);
            s.update((partes[0] + "." + partes[1]).getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(Base64.getUrlDecoder().decode(partes[2]))) {
                throw new TokenRecusado("ASSINATURA_INVALIDA");
            }
        } catch (TokenRecusado e) {
            throw e;
        } catch (Exception e) {
            throw new TokenRecusado("ASSINATURA_INVALIDA");
        }
        JsonNode c = json(partes[1]);
        Instant agora = relogio.instant();
        if (!emissor.equals(texto(c, "iss"))) {
            throw new TokenRecusado("EMISSOR_DIVERGENTE");
        }
        if (!audienciaContem(c.get("aud"))) {
            throw new TokenRecusado("AUDIENCIA_DIVERGENTE");
        }
        Instant exp = instante(c, "exp");
        if (exp == null || !agora.isBefore(exp)) {
            throw new TokenRecusado("TOKEN_VENCIDO");
        }
        Instant nbf = instante(c, "nbf");
        Instant iat = instante(c, "iat");
        if ((nbf != null && nbf.isAfter(agora.plus(TOLERANCIA))) || iat == null || iat.isAfter(agora.plus(TOLERANCIA))) {
            throw new TokenRecusado("TOKEN_NO_FUTURO");
        }
        String sujeito = texto(c, "sub");
        if (sujeito == null || sujeito.isBlank()) {
            throw new TokenRecusado("SUJEITO_AUSENTE");
        }
        Instant authTime = instante(c, "auth_time");
        if (authTime == null) {
            throw new TokenRecusado("AUTH_TIME_AUSENTE");
        }
        if (authTime.isAfter(agora.plus(TOLERANCIA)) || authTime.isBefore(agora.minus(MFA_RECENTE))) {
            throw new TokenRecusado("MFA_NAO_RECENTE");
        }
        return new Identidade(emissor, sujeito, authTime, garantiaResistente(texto(c, "acr"), c.get("amr")));
    }

    private boolean audienciaContem(JsonNode aud) {
        if (aud == null) {
            return false;
        }
        if (aud.isString()) {
            return audiencia.equals(aud.asString());
        }
        if (aud.isArray()) {
            for (JsonNode a : aud) {
                if (a.isString() && audiencia.equals(a.asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Confere a garantia exigida (ver a classe) e devolve o metodo registrado, {@code <acr>+mfa}. */
    private static String garantiaResistente(String acr, JsonNode amr) throws TokenRecusado {
        if (acr == null || !ACR_RESISTENTES.contains(acr)) {
            throw new TokenRecusado("METODO_NAO_RESISTENTE");
        }
        Set<String> metodos = new java.util.HashSet<>();
        if (amr != null && amr.isArray()) {
            for (JsonNode m : amr) {
                if (m.isString()) {
                    metodos.add(m.asString());
                }
            }
        }
        if (!metodos.contains("mfa")) {
            throw new TokenRecusado("MFA_NAO_AFIRMADO");
        }
        boolean posse = "phrh".equals(acr) ? metodos.contains("hwk") : metodos.stream().anyMatch(AMR_POSSE::contains);
        if (!posse) {
            throw new TokenRecusado("ASSERCAO_INCONSISTENTE");
        }
        return acr + "+mfa";
    }

    private static JsonNode json(String parte) throws TokenRecusado {
        try {
            JsonNode n = JSON.readTree(Base64.getUrlDecoder().decode(parte));
            if (n == null || !n.isObject()) {
                throw new TokenRecusado("TOKEN_MALFORMADO");
            }
            return n;
        } catch (TokenRecusado e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TokenRecusado("TOKEN_MALFORMADO");
        }
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static Instant instante(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        return v != null && v.isIntegralNumber() ? Instant.ofEpochSecond(v.asLong()) : null;
    }
}
