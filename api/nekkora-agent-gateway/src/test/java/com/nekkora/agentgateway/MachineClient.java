package com.nekkora.agentgateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.nekkora.core.token.Tokens;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Agent SIMULADO, falando o contrato 1.0 por HTTP. Comprova o comportamento da API diante do contrato; nao comprova que o
 * Agent atual o implementa (ele nao tem transporte). O segredo da credencial e gerado aqui, como o contrato pede.
 */
final class MachineClient {

    static final JsonMapper JSON = JsonMapper.builder().build();

    record Response(int status, JsonNode body, String raw, java.net.http.HttpHeaders headers) {
    }

    final String base;
    final String ip;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    String credential;
    String secret;

    MachineClient(int port, String ip) {
        this.base = "http://localhost:" + port + "/agent/api/v1";
        this.ip = ip;
    }

    Response redeem(String code, String credentialSecret) {
        ObjectNode b = JSON.createObjectNode();
        b.put("code", code);
        b.put("credentialSecret", credentialSecret);
        b.put("agentVersion", "0.1.0+lab");
        b.putArray("contractVersions").add("1.0");
        return post("/enrollments/redeem", b.toString(), credential);
    }

    /** Resgate completo: guarda a credencial montada (nka1.org.cred.segredo). */
    JsonNode pair(String code) {
        secret = Tokens.newToken();
        Response r = redeem(code, secret);
        if (r.status() != 200) {
            throw new AssertionError("resgate " + r.status() + " " + r.raw());
        }
        credential = MachineToken.credential(java.util.UUID.fromString(r.body().get("organizationId").asString()),
                java.util.UUID.fromString(r.body().get("credentialId").asString()), secret);
        return r.body();
    }

    Response heartbeat() {
        return post("/heartbeat", "{\"agentVersion\":\"0.1.0+lab\",\"contractVersion\":\"1.0\",\"capabilities\":[\"RUN_LOCAL\",\"OBS_1_0\",\"SUPERVISOR_REPORT_1_0\"]}",
                credential);
    }

    Response authorizations() {
        return send(HttpRequest.newBuilder(URI.create(base + "/authorizations")).GET(), credential);
    }

    Response register(String agentExecutionId, String authorizationId, String fingerprint, String supervisorJson) {
        return post("/runs", "{\"agentExecutionId\":\"" + agentExecutionId + "\",\"authorizationId\":\"" + authorizationId + "\",\"packageFingerprint\":\""
                + fingerprint + "\",\"supervisor\":" + supervisorJson + "}", credential);
    }

    Response supervisor(String agentExecutionId, String supervisorJson) {
        return post("/runs/" + agentExecutionId + "/supervisor", supervisorJson, credential);
    }

    Response events(String agentExecutionId, List<String> envelopes, String declaredMissing) {
        ArrayNode arr = JSON.createArrayNode();
        for (String e : envelopes) {
            arr.add(JSON.readTree(e));
        }
        String body = "{\"events\":" + arr + (declaredMissing == null ? "" : ",\"declaredMissing\":" + declaredMissing) + "}";
        return post("/runs/" + agentExecutionId + "/events", body, credential);
    }

    Response post(String path, String body, String bearer) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)),
                bearer);
    }

    Response send(HttpRequest.Builder b, String bearer) {
        b.header("Nekkora-Agent-Contract", "1.0").header("X-Forwarded-For", ip).timeout(Duration.ofSeconds(60));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = null;
            try {
                body = r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body());
            } catch (RuntimeException e) {
                // corpo nao JSON
            }
            return new Response(r.statusCode(), body, r.body(), r.headers());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
