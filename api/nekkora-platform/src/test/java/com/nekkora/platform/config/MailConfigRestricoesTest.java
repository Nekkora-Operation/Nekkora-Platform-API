package com.nekkora.platform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.nekkora.platform.auth.crypto.SecretCipher;
import com.nekkora.platform.mail.DestinatariosPermitidos;
import com.nekkora.platform.mail.MailQueueCipher;
import com.nekkora.platform.mail.MailTransport;

/**
 * Regras da partida do transporte real fora do Graph: lista fechada de destinatarios (obrigatoria em {@code ensaio-*}) e a
 * chave propria da fila, que continua decifrando o que a chave do MFA cifrou.
 */
class MailConfigRestricoesTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static String chave() {
        byte[] k = new byte[32];
        RANDOM.nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    /** Transporte que so registra o que recebeu (nada sai). */
    static final class Registro implements MailTransport {
        final List<String> recebidos = new CopyOnWriteArrayList<>();

        @Override
        public Delivery deliver(Message m) {
            recebidos.add(m.recipient());
            return new Delivery(Result.ACEITO, 202, null);
        }
    }

    private static MailTransport.Message para(String to) {
        return new MailTransport.Message(UUID.randomUUID(), to, "assunto", "corpo");
    }

    @Test
    void ensaioSemListaNaoSobe() {
        assertThatThrownBy(() -> MailConfig.restrict(new Registro(), "ensaio-api-v1-003", "")).hasMessageContaining("destinatarios-permitidos");
        assertThatThrownBy(() -> MailConfig.restrict(new Registro(), "ensaio-api-v1-003", " , ")).hasMessageContaining("destinatarios-permitidos");
    }

    @Test
    void foraDoEnsaioSemListaNaoRestringe() {
        Registro r = new Registro();
        assertThat(MailConfig.restrict(r, "producao", "")).isSameAs(r);
    }

    @Test
    void soEnderecosDaListaChegamAoTransporte() {
        Registro r = new Registro();
        MailTransport t = MailConfig.restrict(r, "ensaio-api-v1-003", "teste1@nekkora.com, Teste2@Exemplo.com");
        assertThat(t.deliver(para("TESTE1@nekkora.com")).result()).isEqualTo(MailTransport.Result.ACEITO);
        assertThat(t.deliver(para("teste2@exemplo.com")).result()).isEqualTo(MailTransport.Result.ACEITO);
        MailTransport.Delivery fora = t.deliver(para("titular@cliente.test"));
        assertThat(fora.result()).isEqualTo(MailTransport.Result.RECUSADO);
        assertThat(fora.status()).isNull();
        assertThat(t.deliver(para("teste1@nekkora.com.evil.test")).result()).isEqualTo(MailTransport.Result.RECUSADO);
        assertThat(r.recebidos).containsExactly("TESTE1@nekkora.com", "teste2@exemplo.com");
    }

    @Test
    void itemComMarcadorAceitaSoAsVariacoesDaMesmaCaixa() {
        Registro r = new Registro();
        MailTransport t = MailConfig.restrict(r, "ensaio-api-v1-003", "controle+*@exemplo.com");
        assertThat(t.deliver(para("controle@exemplo.com")).result()).isEqualTo(MailTransport.Result.ACEITO);
        assertThat(t.deliver(para("controle+j1-20261024@exemplo.com")).result()).isEqualTo(MailTransport.Result.ACEITO);
        assertThat(t.deliver(para("CONTROLE+R3.x_y@Exemplo.com")).result()).isEqualTo(MailTransport.Result.ACEITO);
        for (String fora : new String[] {"controle+@exemplo.com", "controlex@exemplo.com", "outro+controle@exemplo.com", "controle+a@exemplo.com.evil.test",
                "controle+a@sub.exemplo.com", "controle+a+b@exemplo.com", "controle+a b@exemplo.com", "x@controle+a@exemplo.com"}) {
            assertThat(t.deliver(para(fora)).result()).as(fora).isEqualTo(MailTransport.Result.RECUSADO);
        }
        assertThat(r.recebidos).hasSize(3);
    }

    @Test
    void listaSoComEnderecosExatos() {
        assertThatThrownBy(() -> DestinatariosPermitidos.ler("*@nekkora.com")).hasMessageContaining("endereco exato");
        assertThatThrownBy(() -> DestinatariosPermitidos.ler("@nekkora.com")).hasMessageContaining("endereco exato");
        assertThatThrownBy(() -> DestinatariosPermitidos.ler("nekkora.com")).hasMessageContaining("endereco exato");
        assertThatThrownBy(() -> DestinatariosPermitidos.ler("controle+*@*.com")).hasMessageContaining("endereco exato");
        assertThatThrownBy(() -> DestinatariosPermitidos.ler("*+*@exemplo.com")).hasMessageContaining("endereco exato");
        assertThatThrownBy(() -> DestinatariosPermitidos.ler("controle*@exemplo.com")).hasMessageContaining("endereco exato");
    }

    @Test
    void semChavePropriaAFilaUsaADoMfa() {
        SecretCipher mfa = new SecretCipher("k1", SecretCipher.parseKeys("k1:" + chave()));
        assertThat(MailConfig.queueCipher(mfa, "k1:" + chave(), "", "").cipher()).isSameAs(mfa);
    }

    @Test
    void chavePropriaCifraSoComElaEDecifraOQueOMfaCifrou() {
        String mfaKeys = "k1:" + chave();
        SecretCipher mfa = new SecretCipher("k1", SecretCipher.parseKeys(mfaKeys));
        UUID job = UUID.randomUUID();
        SecretCipher.Encrypted antiga = mfa.encryptForMail("destinatario e token".getBytes(StandardCharsets.UTF_8), job);

        MailQueueCipher fila = MailConfig.queueCipher(mfa, mfaKeys, "fila1", "fila1:" + chave());
        assertThat(new String(fila.cipher().decryptForMail(antiga, job), StandardCharsets.UTF_8)).isEqualTo("destinatario e token");
        SecretCipher.Encrypted nova = fila.cipher().encryptForMail("novo".getBytes(StandardCharsets.UTF_8), job);
        assertThat(nova.keyId()).isEqualTo("fila1");
        // a chave do MFA nao decifra o que a fila cifrou com a chave propria
        assertThatThrownBy(() -> mfa.decryptForMail(nova, job)).hasMessageContaining("key_id desconhecido");
    }

    @Test
    void chaveDaFilaIncompletaOuComIdDoMfaNaoSobe() {
        String mfaKeys = "k1:" + chave();
        SecretCipher mfa = new SecretCipher("k1", SecretCipher.parseKeys(mfaKeys));
        assertThatThrownBy(() -> MailConfig.queueCipher(mfa, mfaKeys, "fila1", "")).hasMessageContaining("vao juntas");
        assertThatThrownBy(() -> MailConfig.queueCipher(mfa, mfaKeys, "fila2", "fila1:" + chave())).hasMessageContaining("fora de");
        assertThatThrownBy(() -> MailConfig.queueCipher(mfa, mfaKeys, "k1", "k1:" + chave())).hasMessageContaining("repetido");
        assertThatThrownBy(() -> SecretCipher.parseKeys("f1:" + chave() + ",f1:" + chave())).hasMessageContaining("repetido");
    }
}
