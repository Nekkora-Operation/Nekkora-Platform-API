package com.nekkora.platform.desktop;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.nekkora.core.ratelimit.RedisLimiter.LimiterUnavailable;
import com.nekkora.core.token.Tokens;

/**
 * Estado de curta duracao do login do aplicativo desktop (AGENT-CLIENT-LOGIN-001, contrato desktop-login/1), so no Redis
 * e sempre pela chave SHA-256 de um token opaco (o token bruto nunca e guardado):
 * <ul>
 * <li>{@code nk:desk:req:<hash>}: pedido de autorizacao trazido pelo navegador (cliente, redirect, state, desafio PKCE);</li>
 * <li>{@code nk:desk:code:<hash>}: codigo de autorizacao, de uso unico (lido e apagado no mesmo comando);</li>
 * <li>{@code nk:desk:used:<hash>}: marca do codigo ja trocado, com a sessao que ele emitiu (reuso revoga a sessao);</li>
 * <li>{@code nk:desk:sess:<hash>}: sessao do desktop (identidade e sessao de identidade de origem).</li>
 * </ul>
 * Os valores sao campos separados por quebra de linha; todos tem alfabeto fechado, validado antes de gravar. Redis fora:
 * {@link LimiterUnavailable} (503, falha fechada).
 *
 * <p>AGENT-CLIENT-NAVEGACAO-001 (contrato desktop-navigation/1): o pedido, o codigo e a sessao levam tambem o ESCOPO
 * concedido e, no escopo de navegacao, a lista de pares (organizacao, conta) resolvida na aprovacao. Sao campos ACRESCIDOS
 * ao fim do valor: um valor gravado pela versao anterior (sem eles) e lido como escopo {@code identity}, sem organizacoes -
 * um token antigo nunca e ampliado. O token da sessao do navegador nunca e guardado aqui.
 */
public final class DesktopStore {

    /** Pedido validado. {@code state} volta ao cliente sem alteracao. */
    public record Request(String clientId, String redirectUri, String state, String challenge, String scope) {
    }

    /**
     * Conta da pessoa numa organizacao. No codigo e na sessao guardados e o retrato da APROVACAO, que desde o contrato
     * desktop-navigation/2 (AGENT-CLIENT-NAVEGACAO-002) NAO decide mais nada: as rotas resolvem o par a cada requisicao
     * ({@code identity_session_organizations}, 039). O retrato continua gravado so para que uma instancia da revisao anterior,
     * durante a implantacao ou num retorno, ainda leia uma sessao emitida por esta.
     */
    public record OrgAccount(UUID organizationId, UUID userId) {
    }

    /** Codigo emitido: o pedido que o originou, a identidade, a sessao de identidade que autorizou e as contas do escopo. */
    public record Grant(Request request, UUID identityId, UUID originSessionId, List<OrgAccount> organizations) {
    }

    public record Session(UUID identityId, UUID originSessionId, long issuedAtEpochSecond, String scope, List<OrgAccount> organizations) {
    }

    /** Escopo dos valores gravados antes do escopo existir. */
    static final String LEGACY_SCOPE = "identity";

    private final StringRedisTemplate redis;

    public DesktopStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void saveRequest(String token, Request r, Duration ttl) {
        set("nk:desk:req:", token, encode(r), ttl);
    }

    public Optional<Request> request(String token) {
        return get("nk:desk:req:", token, false).map(DesktopStore::decodeRequest);
    }

    /** Le e apaga o pedido: aprovado ou negado, ele nao serve de novo. */
    public Optional<Request> takeRequest(String token) {
        return get("nk:desk:req:", token, true).map(DesktopStore::decodeRequest);
    }

    public void saveCode(String code, Grant g, Duration ttl) {
        set("nk:desk:code:", code, encode(g.request()) + "\n" + g.identityId() + "\n" + g.originSessionId() + "\n" + encode(g.organizations()), ttl);
    }

    /** Le e apaga o codigo num so comando (GETDEL): duas trocas concorrentes nunca veem o mesmo codigo. */
    public Optional<Grant> takeCode(String code) {
        return get("nk:desk:code:", code, true).map(v -> {
            String[] f = v.split("\n", -1);
            if (f.length == 6) {
                // gravado pela versao anterior: pedido de 4 campos, sem escopo nem organizacoes
                return new Grant(new Request(f[0], f[1], f[2], f[3], LEGACY_SCOPE), UUID.fromString(f[4]), UUID.fromString(f[5]), List.of());
            }
            return new Grant(new Request(f[0], f[1], f[2], f[3], f[4]), UUID.fromString(f[5]), UUID.fromString(f[6]), decodeOrganizations(f[7]));
        });
    }

    /** Marca o codigo como trocado, com o hash da sessao emitida, para revoga-la se o codigo reaparecer. */
    public void markUsed(String code, String sessionToken, Duration ttl) {
        set("nk:desk:used:", code, Tokens.sha256Hex(sessionToken), ttl);
    }

    /** Codigo ja trocado: apaga a sessao que ele emitiu. {@code true} se era um reuso. */
    public boolean revokeIfReused(String code) {
        Optional<String> sessionHash = get("nk:desk:used:", code, false);
        sessionHash.ifPresent(h -> call(() -> redis.delete("nk:desk:sess:" + h)));
        return sessionHash.isPresent();
    }

    public void saveSession(String token, Session s, Duration ttl) {
        set("nk:desk:sess:", token, s.identityId() + "\n" + s.originSessionId() + "\n" + s.issuedAtEpochSecond() + "\n" + s.scope() + "\n"
                + encode(s.organizations()), ttl);
    }

    public Optional<Session> session(String token) {
        return get("nk:desk:sess:", token, false).map(v -> {
            String[] f = v.split("\n", -1);
            return f.length == 3 ? new Session(UUID.fromString(f[0]), UUID.fromString(f[1]), Long.parseLong(f[2]), LEGACY_SCOPE, List.of())
                    : new Session(UUID.fromString(f[0]), UUID.fromString(f[1]), Long.parseLong(f[2]), f[3], decodeOrganizations(f[4]));
        });
    }

    /** Prazo que resta a sessao, em segundos (0 se ja nao existe). */
    public long sessionRemainingSeconds(String token) {
        Long ttl = call(() -> redis.getExpire("nk:desk:sess:" + Tokens.sha256Hex(token)));
        return ttl == null || ttl < 0 ? 0 : ttl;
    }

    public boolean deleteSession(String token) {
        return Tokens.isValidFormat(token) && Boolean.TRUE.equals(call(() -> redis.delete("nk:desk:sess:" + Tokens.sha256Hex(token))));
    }

    private void set(String prefix, String token, String value, Duration ttl) {
        call(() -> {
            redis.opsForValue().set(prefix + Tokens.sha256Hex(token), value, ttl);
            return null;
        });
    }

    private Optional<String> get(String prefix, String token, boolean delete) {
        if (!Tokens.isValidFormat(token)) {
            return Optional.empty();
        }
        String key = prefix + Tokens.sha256Hex(token);
        return Optional.ofNullable(call(() -> delete ? redis.opsForValue().getAndDelete(key) : redis.opsForValue().get(key)));
    }

    private static String encode(Request r) {
        return r.clientId() + "\n" + r.redirectUri() + "\n" + r.state() + "\n" + r.challenge() + "\n" + r.scope();
    }

    private static Request decodeRequest(String v) {
        String[] f = v.split("\n", -1);
        return new Request(f[0], f[1], f[2], f[3], f.length > 4 ? f[4] : LEGACY_SCOPE);
    }

    /** Pares {@code organizacao:conta} separados por virgula (so UUIDs); vazio = nenhuma organizacao. */
    private static String encode(List<OrgAccount> organizations) {
        StringBuilder s = new StringBuilder();
        for (OrgAccount o : organizations) {
            s.append(s.length() > 0 ? "," : "").append(o.organizationId()).append(':').append(o.userId());
        }
        return s.toString();
    }

    private static List<OrgAccount> decodeOrganizations(String v) {
        List<OrgAccount> out = new ArrayList<>();
        if (!v.isEmpty()) {
            for (String par : v.split(",")) {
                String[] p = par.split(":", 2);
                out.add(new OrgAccount(UUID.fromString(p[0]), UUID.fromString(p[1])));
            }
        }
        return List.copyOf(out);
    }

    private static <T> T call(Supplier<T> op) {
        try {
            return op.get();
        } catch (RuntimeException e) {
            throw new LimiterUnavailable();
        }
    }
}
