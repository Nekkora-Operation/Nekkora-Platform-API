package com.nekkora.platform.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** Segredo {@code nekkora-m365/1}: campos exatos, tenant vinculado ao configurado, nada sensivel no {@code toString}. */
class M365SegredoTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TENANT = "ae4ed48d-e7d8-4f24-83f2-443e997c5a65";
    private static final String CLIENT = "00000000-0000-0000-0000-00000000bbbb";

    private static Map<String, String> valido() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("formato", "nekkora-m365/1");
        m.put("tenant_id", TENANT);
        m.put("client_id", CLIENT);
        m.put("certificado_pkcs12", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
        m.put("certificado_senha", "senha-de-teste");
        return m;
    }

    private static String json(Map<String, String> m) {
        return JSON.writeValueAsString(m);
    }

    @Test
    void leOFormatoValidoEApaga() {
        M365Segredo s = M365Segredo.ler(json(valido()), TENANT);
        assertThat(s.clientId()).isEqualTo(CLIENT);
        assertThat(s.pkcs12()).containsExactly(1, 2, 3);
        assertThat(new String(s.senha())).isEqualTo("senha-de-teste");
        assertThat(s.toString()).doesNotContain("senha-de-teste").doesNotContain("AQID");
        s.apagar();
        assertThat(s.pkcs12()).containsOnly(0);
        assertThat(s.senha()).containsOnly('\0');
    }

    @Test
    void outroTenantRecusa() {
        assertThatThrownBy(() -> M365Segredo.ler(json(valido()), "11111111-1111-1111-1111-111111111111")).hasMessageContaining("outro tenant");
    }

    @Test
    void camposDiferentesFormatoOuValoresInvalidosRecusam() {
        Map<String, String> a = valido();
        a.put("remetente", "no-reply@nekkora.com");
        assertThatThrownBy(() -> M365Segredo.ler(json(a), TENANT)).hasMessageContaining("campos diferentes");
        Map<String, String> b = valido();
        b.remove("certificado_senha");
        assertThatThrownBy(() -> M365Segredo.ler(json(b), TENANT)).hasMessageContaining("campos diferentes");
        Map<String, String> c = valido();
        c.put("formato", "nekkora-m365/2");
        assertThatThrownBy(() -> M365Segredo.ler(json(c), TENANT)).hasMessageContaining("formato");
        Map<String, String> d = valido();
        d.put("certificado_pkcs12", "nao e base64!");
        assertThatThrownBy(() -> M365Segredo.ler(json(d), TENANT)).hasMessageContaining("base64");
        Map<String, String> e = valido();
        e.put("client_id", "APP-ID");
        assertThatThrownBy(() -> M365Segredo.ler(json(e), TENANT)).hasMessageContaining("GUID");
        Map<String, String> f = valido();
        f.put("certificado_senha", " ");
        assertThatThrownBy(() -> M365Segredo.ler(json(f), TENANT)).hasMessageContaining("certificado_senha");
        assertThatThrownBy(() -> M365Segredo.ler("nao-json", TENANT)).hasMessageContaining("JSON");
        assertThatThrownBy(() -> M365Segredo.ler("[1]", TENANT)).hasMessageContaining("objeto");
    }

    @Test
    void mensagensDeErroNaoLevamOConteudo() {
        Map<String, String> d = valido();
        d.put("certificado_pkcs12", "nao e base64!");
        assertThatThrownBy(() -> M365Segredo.ler(json(d), TENANT)).message().doesNotContain("senha-de-teste").doesNotContain("nao e base64!");
    }
}
