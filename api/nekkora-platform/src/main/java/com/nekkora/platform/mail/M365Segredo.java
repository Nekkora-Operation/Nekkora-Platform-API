package com.nekkora.platform.mail;

import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Conteudo do segredo do transporte real no AWS Secrets Manager (por exemplo {@code nekkora/homologacao/platform/m365}),
 * formato {@code nekkora-m365/1}. {@code SecretString} JSON com exatamente estes campos:
 * <pre>
 * {
 *   "formato": "nekkora-m365/1",
 *   "tenant_id": "&lt;Tenant ID do Entra&gt;",
 *   "client_id": "&lt;Application (client) ID do aplicativo dedicado&gt;",
 *   "certificado_pkcs12": "&lt;arquivo PKCS#12 em base64: UMA chave privada RSA e o certificado&gt;",
 *   "certificado_senha": "&lt;senha do PKCS#12&gt;"
 * }
 * </pre>
 * O remetente NAO esta no segredo: fica na configuracao versionada do ambiente, revisada. O {@code tenant_id} do segredo
 * tem de ser o configurado (um segredo de outro tenant recusa a partida). Campo a mais, a menos ou vazio: recusa.
 * {@code toString} nao mostra o certificado nem a senha.
 */
public record M365Segredo(String tenantId, String clientId, byte[] pkcs12, char[] senha) {

    public static final String FORMATO = "nekkora-m365/1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> CAMPOS = Set.of("formato", "tenant_id", "client_id", "certificado_pkcs12", "certificado_senha");
    private static final Pattern GUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** Le e confere o segredo; {@code tenantEsperado} e o da configuracao. */
    public static M365Segredo ler(String secretString, String tenantEsperado) {
        JsonNode n;
        try {
            n = JSON.readTree(secretString);
        } catch (RuntimeException e) {
            throw new IllegalStateException("segredo do transporte nao e JSON");
        }
        if (n == null || !n.isObject()) {
            throw new IllegalStateException("segredo do transporte nao e um objeto JSON");
        }
        Set<String> campos = new TreeSet<>(n.propertyNames());
        if (!campos.equals(new TreeSet<>(CAMPOS))) {
            throw new IllegalStateException("segredo do transporte com campos diferentes de " + new TreeSet<>(CAMPOS));
        }
        if (!FORMATO.equals(texto(n, "formato"))) {
            throw new IllegalStateException("segredo do transporte com formato diferente de " + FORMATO);
        }
        String tenant = texto(n, "tenant_id");
        String client = texto(n, "client_id");
        if (!GUID.matcher(tenant).matches() || !GUID.matcher(client).matches()) {
            throw new IllegalStateException("segredo do transporte: tenant_id e client_id devem ser GUID em minusculas");
        }
        if (!tenant.equals(tenantEsperado)) {
            throw new IllegalStateException("segredo do transporte de outro tenant (tenant_id diferente de nekkora.mail.m365.tenant-id)");
        }
        byte[] p12;
        try {
            p12 = Base64.getDecoder().decode(texto(n, "certificado_pkcs12"));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("segredo do transporte: certificado_pkcs12 nao e base64");
        }
        String senha = texto(n, "certificado_senha");
        if (p12.length == 0 || senha.isEmpty()) {
            throw new IllegalStateException("segredo do transporte: certificado ou senha vazios");
        }
        return new M365Segredo(tenant, client, p12, senha.toCharArray());
    }

    /** Apaga o certificado e a senha da memoria (depois da partida do transporte). */
    public void apagar() {
        Arrays.fill(pkcs12, (byte) 0);
        Arrays.fill(senha, '\0');
    }

    private static String texto(JsonNode n, String campo) {
        JsonNode v = n.get(campo);
        if (v == null || !v.isString() || v.asString().isBlank()) {
            throw new IllegalStateException("segredo do transporte: " + campo + " ausente ou vazio");
        }
        return v.asString();
    }

    @Override
    public String toString() {
        return "M365Segredo[tenant=" + tenantId + ", client=" + clientId + ", certificado=<omitido>]";
    }
}
