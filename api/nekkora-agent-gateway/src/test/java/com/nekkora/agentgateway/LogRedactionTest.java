package com.nekkora.agentgateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T05 - a remocao de segredos das linhas de registro. O aplicativo da maquina ja remove
 * antes de enviar e o servidor remove de novo: a segunda passada NAO pode alterar o que a primeira deixou.
 */
class LogRedactionTest {

    @Test
    void segredosSaemEOTextoAoRedorFica() {
        assertThat(LogRedaction.redact("cred nka1." + "A".repeat(20) + ".x fim")).isEqualTo("cred [removido] fim");
        assertThat(LogRedaction.redact("codigo nkp1.org.enr." + "B".repeat(43))).isEqualTo("codigo [removido]");
        assertThat(LogRedaction.redact("Authorization: Bearer abcdefghijklmnop e o resto")).isEqualTo("Authorization: [removido] e o resto");
        assertThat(LogRedaction.redact("proxy-authorization=Basic dXNlcjpzZW5oYQ== fim")).isEqualTo("proxy-authorization=[removido] fim");
        assertThat(LogRedaction.redact("chamando com bearer abcdefghijklmnop agora")).isEqualTo("chamando com bearer [removido] agora");
        assertThat(LogRedaction.redact("token " + "t".repeat(43) + " ok", "t".repeat(43))).isEqualTo("token [removido] ok");
        assertThat(LogRedaction.redact("linha comum, sem segredo: autorizado em 3 passos")).isEqualTo("linha comum, sem segredo: autorizado em 3 passos");
    }

    @Test
    void repetirARemocaoNaoMudaMaisNada() {
        for (String line : new String[] {"Authorization: Bearer abcdefghijklmnop e o resto", "cred nka1." + "A".repeat(20) + " e Authorization: Basic abc== fim",
                "authorization = token-unico seguido de texto", "bearer abcdefghijklmnop e depois Authorization: x y z"}) {
            String once = LogRedaction.redact(line);
            assertThat(LogRedaction.redact(once)).as(line).isEqualTo(once);
            assertThat(LogRedaction.redact(LogRedaction.redact(once))).isEqualTo(once);
        }
        // o caso que o aceite integrado encontrou: a segunda passada comia a palavra seguinte
        assertThat(LogRedaction.redact("cabecalho Authorization: [removido] e o resto")).isEqualTo("cabecalho Authorization: [removido] e o resto");
    }
}
