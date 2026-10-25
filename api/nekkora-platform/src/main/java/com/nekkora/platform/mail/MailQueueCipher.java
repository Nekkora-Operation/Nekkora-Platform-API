package com.nekkora.platform.mail;

import com.nekkora.platform.auth.crypto.SecretCipher;

/** Cifra da fila de e-mail (021): a chave propria da fila ou, sem ela, a do MFA (ver {@code MailConfig}). */
public record MailQueueCipher(SecretCipher cipher) {
}
