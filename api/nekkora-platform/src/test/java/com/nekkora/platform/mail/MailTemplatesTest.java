package com.nekkora.platform.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Modelos de mensagem (missao 002, PR 5; desenho de 2026-10-11, §4): todo tipo tem assunto e corpo; links SO na origem
 * publica, com o token no FRAGMENTO; avisos sem token nem link; nenhum tipo novo sem modelo.
 */
class MailTemplatesTest {

    private static final String ORIGEM = "https://app.nekkora.test";
    private static final String TOKEN = "T".repeat(43);
    private final MailTemplates t = new MailTemplates(ORIGEM + "/");

    @Test
    void mensagensComTokenLevamOLinkNaOrigemPublicaComOTokenNoFragmento() {
        UUID org = UUID.randomUUID();
        assertThat(t.render(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, null, TOKEN).body()).contains(ORIGEM + "/cadastro/confirmar#token=" + TOKEN);
        assertThat(t.render(MailSender.Kind.PASSWORD_RECOVERY, null, TOKEN).body()).contains(ORIGEM + "/recuperar-senha#token=" + TOKEN);
        assertThat(t.render(MailSender.Kind.PASSWORD_RECOVERY, org, TOKEN).body()).contains(ORIGEM + "/o/" + org + "/recuperar-senha#token=" + TOKEN);
        assertThat(t.render(MailSender.Kind.ORG_INVITATION, org, TOKEN).body()).as("convite (023): pagina por organizacao, token no fragmento")
                .contains(ORIGEM + "/o/" + org + "/convite#token=" + TOKEN).doesNotContain("?token");
        MailTemplates.Rendered e = t.renderEmergency(MailSender.Kind.NOTICE_MFA_EMERGENCY_HOLDER, org, "C".repeat(43), "D".repeat(43));
        assertThat(e.body()).contains(ORIGEM + "/o/" + org + "/mfa-emergencia/confirmar#token=" + "C".repeat(43))
                .contains(ORIGEM + "/o/" + org + "/mfa-emergencia/contestar#token=" + "D".repeat(43));
        for (String body : new String[] {t.render(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, null, TOKEN).body(), e.body()}) {
            assertThat(body).doesNotContain("?token=").doesNotContain("//cadastro");
        }
        assertThatThrownBy(() -> t.render(MailSender.Kind.PASSWORD_RECOVERY, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void todoTipoTemModeloEOsAvisosNaoLevamTokenNemLink() {
        Set<MailSender.Kind> comToken = EnumSet.of(MailSender.Kind.IDENTITY_SIGNUP_CONFIRMATION, MailSender.Kind.PASSWORD_RECOVERY,
                MailSender.Kind.ORG_INVITATION);
        Set<MailSender.Kind> emergencia = EnumSet.of(MailSender.Kind.NOTICE_MFA_EMERGENCY_HOLDER, MailSender.Kind.NOTICE_MFA_EMERGENCY_ADMIN);
        for (MailSender.Kind k : MailSender.Kind.values()) {
            if (emergencia.contains(k)) {
                continue;
            }
            MailTemplates.Rendered r = t.render(k, UUID.randomUUID(), comToken.contains(k) ? TOKEN : null);
            assertThat(r.subject()).as(k.name()).isNotBlank();
            assertThat(r.body()).as(k.name()).isNotBlank().contains("Nao responda");
            if (!comToken.contains(k)) {
                assertThat(r.body()).as(k + ": aviso sem link").doesNotContain("http").doesNotContain("token");
            }
            assertThat(r.toString()).doesNotContain(r.subject());
        }
    }
}
