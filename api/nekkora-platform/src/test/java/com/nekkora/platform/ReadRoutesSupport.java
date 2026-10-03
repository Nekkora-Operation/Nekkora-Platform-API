package com.nekkora.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nekkora.core.testsupport.BrowserClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Apoio dos testes HTTP da GAP-06: requisicao, diagnostico de falha SEM o corpo completo (status, erro, quantidade de itens;
 * nextCursor so como presente/nulo - parecer da GAP-03), paginacao completa e extracao de campos.
 */
abstract class ReadRoutesSupport extends PlatformTestBase {

    static final JsonMapper JSON = JsonMapper.builder().build();

    record Got(int status, JsonNode body) {
    }

    static Got get(BrowserClient n, String path) {
        BrowserClient.Response r = n.get(path);
        return new Got(r.status(), r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body()));
    }

    static String diag(Got g) {
        JsonNode b = g.body();
        String error = b != null && b.has("error") ? b.get("error").asString() : "-";
        String items = b != null && b.has("items") ? String.valueOf(b.get("items").size()) : "-";
        String next = b != null && b.has("nextCursor") ? (b.get("nextCursor").isNull() ? "nulo" : "presente (ocultado)") : "-";
        return "status=" + g.status() + " error=" + error + " items=" + items + " nextCursor=" + next;
    }

    static void assertError(Got g, int status, String code) {
        assertThat(g.status()).as(diag(g)).isEqualTo(status);
        assertThat(g.body().get("error").asString()).isEqualTo(code);
    }

    static void assertOk(Got g, String what) {
        assertThat(g.status()).as(what + " " + diag(g)).isEqualTo(200);
    }

    static List<String> field(JsonNode items, String name) {
        List<String> out = new ArrayList<>();
        items.forEach(x -> out.add(x.get(name).asString()));
        return out;
    }

    static List<String> field(List<JsonNode> items, String name) {
        List<String> out = new ArrayList<>();
        items.forEach(x -> out.add(x.get(name).isNumber() ? String.valueOf(x.get(name).asLong()) : x.get(name).asString()));
        return out;
    }

    static List<String> keys(JsonNode n) {
        return n.properties().stream().map(Map.Entry::getKey).toList();
    }

    static String path(UUID org, String route, String query) {
        return "/api/v1/o/" + org + "/" + route + (query == null ? "" : "?" + query);
    }

    /** Todas as paginas; nenhuma intermediaria vazia; ultima com nextCursor nulo. */
    static List<JsonNode> allPages(BrowserClient n, UUID org, String route, String filters, int limit) {
        List<JsonNode> items = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 1000; page++) {
            String q = (filters == null ? "" : filters + "&") + "limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor);
            Got g = get(n, path(org, route, q));
            assertOk(g, "pagina " + page);
            g.body().get("items").forEach(items::add);
            if (g.body().get("nextCursor").isNull()) {
                return items;
            }
            assertThat(g.body().get("items").size()).as("pagina intermediaria cheia").isEqualTo(limit);
            cursor = g.body().get("nextCursor").asString();
        }
        throw new AssertionError("paginacao nao terminou");
    }

    BrowserClient login(Org o, TestUser u) {
        BrowserClient n = browser();
        firstLogin(n, o.id(), u);
        return n;
    }

    BrowserClient admin(Org o) {
        return login(o, new TestUser(o.adminId(), o.adminEmail(), PASSWORD.toCharArray()));
    }
}
