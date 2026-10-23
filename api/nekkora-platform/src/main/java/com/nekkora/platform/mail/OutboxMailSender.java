package com.nekkora.platform.mail;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Porta {@link MailSender} do transporte real (missao 002, PR 5). Os servicos registram a intencao pela
 * {@link MailIntents} DENTRO da transacao do ato; esta porta so atende quem ainda chama {@link MailSender} diretamente:
 * <ul>
 * <li>{@link #send}: mensagem avulsa, gravada na fila em transacao propria (sem deduplicacao: cada chamada e um evento);</li>
 * <li>{@link #sendEmergencyNotice}: aviso da recuperacao emergencial (016). E ENFILEIRADO na fila persistente com a
 * tentativa de origem e devolve {@link Outcome#ENFILEIRADO} na hora; o despacho respeita o {@code Retry-After} do
 * provedor (a tentativa fica para depois, sem prender quem chamou) e devolve o desfecho a tentativa
 * ({@code EmergencyNotices.recordTransportResult}).</li>
 * </ul>
 */
public final class OutboxMailSender implements MailSender {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OutboxMailIntents intents;

    public OutboxMailSender(OutboxMailIntents intents) {
        this.intents = intents;
    }

    @Override
    public void send(Kind kind, UUID organization, String recipient, String tokenOrNull) {
        if (kind == Kind.NOTICE_MFA_EMERGENCY_HOLDER || kind == Kind.NOTICE_MFA_EMERGENCY_ADMIN || kind == Kind.NOTICE_MFA_EMERGENCY_EXECUTED) {
            throw new IllegalArgumentException("aviso de recuperacao emergencial usa sendEmergencyNotice");
        }
        intents.recordAlone(new MailIntents.Intent(kind, organization, recipient, tokenOrNull, "avulsa:" + UUID.randomUUID()));
    }

    @Override
    public Outcome sendEmergencyNotice(Kind kind, UUID organization, String recipient, String confirmTokenOrNull, String contestTokenOrNull,
                                       UUID attemptId) {
        intents.recordEmergency(kind, organization, recipient, confirmTokenOrNull, contestTokenOrNull, attemptId);
        return Outcome.ENFILEIRADO;
    }

    /** Payload decifrado: destinatario e ate dois tokens. */
    static Map<String, String> decode(byte[] plain) {
        Map<String, String> m = new LinkedHashMap<>();
        JsonNode n = JSON.readTree(new String(plain, StandardCharsets.UTF_8));
        for (String k : new String[] {"to", "token", "contest"}) {
            m.put(k, n.path(k).isNull() || n.path(k).isMissingNode() ? null : n.path(k).asString(null));
        }
        return m;
    }
}
