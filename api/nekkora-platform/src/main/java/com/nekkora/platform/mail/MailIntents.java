package com.nekkora.platform.mail;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Intencao de envio de e-mail registrada PELO ATO DE NEGOCIO (missao 002, revisao do PR 5). Os servicos chamam
 * {@link #record} DENTRO da transacao que confirma o ato (cadastro, troca de senha, reset...):
 * <ul>
 * <li>transporte real ({@code m365}): a intencao e gravada na fila {@code mail_outbox} NA MESMA transacao - ou o ato e a
 * intencao confirmam juntos, ou nenhum. Uma queda depois do commit nao perde a mensagem: o despacho a envia depois de
 * reiniciar, sem repetir o ato. O envio continua fora de transacao;</li>
 * <li>transportes local e de homologacao (e testes): o envio e agendado para DEPOIS do commit (o comportamento anterior),
 * sem persistencia; falha do transporte so vai ao log.</li>
 * </ul>
 * {@link Intent#eventKey()} e a chave ESTAVEL do evento (tipo + fato de negocio unico: hash do token, fator novo, sessao
 * criada...): enquanto a linha existir, o mesmo evento nao vira dois trabalhos (UNIQUE no banco). A garantia nao e
 * permanente: depois da limpeza da retencao (30 dias apos o encerramento) a chave pode voltar. Nao promete entrega exatamente
 * uma vez.
 */
public interface MailIntents {

    /** Intencao de UMA mensagem. {@code toString} sem destinatario nem token. */
    record Intent(MailSender.Kind kind, UUID organization, String recipient, String token, String eventKey) {
        public Intent {
            if (eventKey == null || !eventKey.matches("[A-Za-z0-9._:-]{8,160}")) {
                throw new IllegalArgumentException("chave do evento invalida");
            }
        }

        @Override
        public String toString() {
            return "Intent[kind=" + kind + "]";
        }

        /** Chave completa gravada no banco: tipo + chave do evento. */
        public String fullKey() {
            return kind.name() + ":" + eventKey;
        }
    }

    /** Dentro da transacao do ato ({@code j} e o cliente dela). */
    void record(JdbcClient j, Intent intent);

    /** Mensagem sem ato de negocio a acompanhar (ex.: aviso de conta ja existente): transacao propria ou envio direto. */
    void recordAlone(Intent intent);
}
