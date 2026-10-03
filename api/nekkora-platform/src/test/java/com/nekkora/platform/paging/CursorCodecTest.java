package com.nekkora.platform.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

/**
 * Codificador de cursor (contrato GAP-02/03/06, §1.5, §1.5.1 e §5.6; DC-15): derivacao da subchave, assinatura
 * invalida, chave desconhecida, rotacao, vinculos e tamanho com o serializador Java REAL (Jackson da aplicacao).
 */
class CursorCodecTest {

    private static final byte[] KEY1 = key(0);
    private static final byte[] KEY2 = key(100);
    private static final UUID ORG = UUID.fromString("7c1f0000-0000-4000-8000-000000000001");
    private static final UUID MEMBERSHIP = UUID.fromString("a41e0000-0000-4000-8000-000000000002");
    private static final UUID PARENT = UUID.fromString("e9b00000-0000-4000-8000-000000000003");

    private static byte[] key(int start) {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) {
            k[i] = (byte) (start + i);
        }
        return k;
    }

    private static CursorCodec.Scope scope() {
        return new CursorCodec.Scope("listWorkspaces", ORG, MEMBERSHIP, PARENT, CursorCodec.filters("status", List.of("active")), "name48.asc+id.asc");
    }

    private static void assertInvalid(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).apiError()).isEqualTo(ApiError.INVALID_REQUEST));
    }

    private static String flip(String b64, int index) {
        byte[] b = Base64.getUrlDecoder().decode(b64);
        b[index] ^= 1;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    // ------------------------------------------------------------------ derivacao (DC-15)

    @Test
    void derivedKeyMatchesFixedVectorAndDiffersFromServiceKey() {
        // vetor calculado fora da aplicacao (Python hmac): HMAC-SHA256(bytes 0..31, "nekkora/api-cursor/v1")
        assertThat(HexFormat.of().formatHex(CursorCodec.derive(KEY1)))
                .isEqualTo("23154eda2ccd4d6d058027177afd8b0278c7783c176b52872a32ecb0b4f3458b");
        assertThat(CursorCodec.derive(KEY1)).isNotEqualTo(KEY1);
        assertThat(CursorCodec.fingerprint("h1")).isEqualTo("MxEu4U7kacP").hasSize(11);
        assertThat(CursorCodec.fingerprint("x".repeat(5000))).hasSize(11);
        assertThatThrownBy(() -> CursorCodec.derive(new byte[31])).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tagMadeWithTheServiceKeyItselfIsRejected() throws Exception {
        CursorCodec c = new CursorCodec("h1", KEY1);
        String cursor = c.encode(scope(), List.of("Operacao", PARENT));
        String payload = cursor.substring(0, cursor.indexOf('.'));
        byte[] bytes = Base64.getUrlDecoder().decode(payload);
        // separacao de dominio: a mesma mensagem assinada com a chave de servico (uso de identidade) nao vale como cursor
        String withServiceKey = payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(KEY1, bytes));
        assertInvalid(() -> c.decode(withServiceKey, scope()));
        // e a tag do cursor nao e o HMAC de servico da mesma mensagem
        assertThat(cursor.substring(cursor.indexOf('.') + 1))
                .isNotEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(KEY1, bytes)));
    }

    // ------------------------------------------------------------------ ida e volta e tipos da posicao

    @Test
    void roundTripKeepsPositionTypes() {
        CursorCodec c = new CursorCodec("h1", KEY1);
        OffsetDateTime t = OffsetDateTime.parse("2026-10-02T21:09:36.123456789-03:00");
        String cursor = c.encode(scope(), List.of("nome", PARENT, 42L, t));
        CursorCodec.Position p = c.decode(cursor, scope()).expectSize(4);
        assertThat(p.text(0)).isEqualTo("nome");
        assertThat(p.uuid(1)).isEqualTo(PARENT);
        assertThat(p.integer(2)).isEqualTo(42L);
        assertThat(p.instant(3).toInstant()).isEqualTo(t.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertInvalid(() -> c.decode(cursor, scope()).expectSize(3));
        assertInvalid(() -> c.decode(cursor, scope()).integer(0));
        assertInvalid(() -> c.decode(cursor, scope()).uuid(0));
        assertInvalid(() -> c.decode(cursor, scope()).instant(0));
        assertInvalid(() -> c.decode(cursor, scope()).text(9));
        String upper = c.encode(scope(), List.of(PARENT.toString().toUpperCase()));
        assertInvalid(() -> c.decode(upper, scope()).uuid(0));
    }

    // ------------------------------------------------------------------ assinatura invalida

    @Test
    void tamperedTruncatedOrForeignTagsAreRejected() {
        CursorCodec c = new CursorCodec("h1", KEY1);
        String cursor = c.encode(scope(), List.of("Operacao", PARENT));
        String other = c.encode(scope(), List.of("Outra", PARENT));
        String payload = cursor.substring(0, cursor.indexOf('.'));
        String tag = cursor.substring(cursor.indexOf('.') + 1);
        assertThat(c.decode(cursor, scope()).text(0)).as("controle positivo").isEqualTo("Operacao");
        assertInvalid(() -> c.decode(flip(payload, 5) + "." + tag, scope()));
        assertInvalid(() -> c.decode(payload + "." + flip(tag, 0), scope()));
        assertInvalid(() -> c.decode(payload + "." + tag.substring(0, 20), scope()));
        assertInvalid(() -> c.decode(payload + "." + other.substring(other.indexOf('.') + 1), scope()));
        assertInvalid(() -> c.decode(payload + ".", scope()));
        assertInvalid(() -> c.decode(payload, scope()));
        assertInvalid(() -> c.decode("", scope()));
        assertInvalid(() -> c.decode("nao-e-cursor", scope()));
        assertInvalid(() -> c.decode("e30.e30", scope()));
        assertInvalid(() -> c.decode(payload + "=." + tag, scope()));
    }

    // ------------------------------------------------------------------ chave desconhecida e rotacao

    @Test
    void unknownKeyAndRotationInvalidateOldCursors() {
        CursorCodec old = new CursorCodec("h1", KEY1);
        CursorCodec rotated = new CursorCodec("h2", KEY2);
        String oldCursor = old.encode(scope(), List.of("a", PARENT));
        assertInvalid(() -> rotated.decode(oldCursor, scope()));
        String newCursor = rotated.encode(scope(), List.of("a", PARENT));
        assertThat(rotated.decode(newCursor, scope()).text(0)).isEqualTo("a");
        assertInvalid(() -> old.decode(newCursor, scope()));
        // mesmo id com material novo: a tag nao confere
        CursorCodec sameIdNewKey = new CursorCodec("h1", KEY2);
        assertInvalid(() -> sameIdNewKey.decode(oldCursor, scope()));
        // janela de transicao (se a configuracao aceitar a chave anterior): o antigo vale so enquanto ela estiver configurada
        CursorCodec transition = new CursorCodec("h2", Map.of("h1", KEY1, "h2", KEY2));
        assertThat(transition.decode(oldCursor, scope()).text(0)).isEqualTo("a");
        assertThat(transition.decode(transition.encode(scope(), List.of("b")), scope()).text(0)).isEqualTo("b");
        assertThatThrownBy(() -> new CursorCodec("h3", Map.of("h1", KEY1))).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ vinculos

    @Test
    void cursorIsBoundToRouteOrganizationMembershipParentFiltersAndSort() {
        CursorCodec c = new CursorCodec("h1", KEY1);
        CursorCodec.Scope s = scope();
        String cursor = c.encode(s, List.of("a", PARENT));
        UUID other = UUID.randomUUID();
        assertThat(c.decode(cursor, s).text(0)).as("controle positivo").isEqualTo("a");
        assertInvalid(() -> c.decode(cursor, new CursorCodec.Scope("listAutomations", s.organization(), s.membership(), s.parent(), s.filters(), s.sort())));
        assertInvalid(() -> c.decode(cursor, new CursorCodec.Scope(s.route(), other, s.membership(), s.parent(), s.filters(), s.sort())));
        assertInvalid(() -> c.decode(cursor, new CursorCodec.Scope(s.route(), s.organization(), other, s.parent(), s.filters(), s.sort())));
        assertInvalid(() -> c.decode(cursor, new CursorCodec.Scope(s.route(), s.organization(), s.membership(), other, s.filters(), s.sort())));
        assertInvalid(() -> c.decode(cursor, new CursorCodec.Scope(s.route(), s.organization(), s.membership(), null, s.filters(), s.sort())));
        assertInvalid(() -> c.decode(cursor, new CursorCodec.Scope(s.route(), s.organization(), s.membership(), s.parent(),
                CursorCodec.filters("status", List.of("archived")), s.sort())));
        assertInvalid(() -> c.decode(cursor, new CursorCodec.Scope(s.route(), s.organization(), s.membership(), s.parent(), s.filters(), "id.asc")));
        String noParent = c.encode(new CursorCodec.Scope(s.route(), s.organization(), s.membership(), null, s.filters(), s.sort()), List.of("a"));
        assertInvalid(() -> c.decode(noParent, s));
    }

    @Test
    void filterFingerprintIsCanonical() {
        UUID w = UUID.randomUUID();
        String a = CursorCodec.filters("status", List.of("active", "archived", "active"), "workspaceId", w,
                "from", OffsetDateTime.parse("2026-10-01T00:00:00-03:00"));
        String b = CursorCodec.filters("status", List.of("archived", "active"), "workspaceId", UUID.fromString(w.toString().toUpperCase()),
                "from", OffsetDateTime.parse("2026-10-01T03:00:00Z"));
        assertThat(a).isEqualTo(b).hasSize(43);
        assertThat(CursorCodec.filters("status", null)).isNotEqualTo(CursorCodec.filters("status", List.of()));
        assertThat(CursorCodec.filters("status", List.of("active"))).isNotEqualTo(CursorCodec.filters("status", List.of("archived")));
        assertThat(CursorCodec.filters()).hasSize(43);
    }

    // ------------------------------------------------------------------ tamanho (§1.5.1) com o serializador real

    @Test
    void worstCaseCursorStaysWithinTheCalculatedBound() {
        CursorCodec c = new CursorCodec("x".repeat(500), KEY1);
        String route = "r".repeat(32);
        String sort = "s".repeat(32);
        int largest = 0;
        for (String name : List.of("a".repeat(1000), "\u0001".repeat(1000), "\"".repeat(1000), "\\".repeat(1000),
                "😀".repeat(1000), "中".repeat(1000), " ".repeat(1000))) {
            String prefix = name.substring(0, name.offsetByCodePoints(0, 48)); // left(name, 48) do PostgreSQL: pontos de codigo
            CursorCodec.Scope s = new CursorCodec.Scope(route, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), CursorCodec.filters("a", "b"), sort);
            String cursor = c.encode(s, List.of(prefix, UUID.randomUUID()));
            largest = Math.max(largest, cursor.length());
            assertThat(c.decode(cursor, s).text(0)).isEqualTo(prefix);
        }
        assertThat(largest).as("maior cursor emitido no pior caso").isLessThanOrEqualTo(872);
        System.out.println("cursor_pior_caso_serializador_real=" + largest);
    }

    @Test
    void oversizedCursorsAreNeitherAcceptedNorEmitted() {
        CursorCodec c = new CursorCodec("h1", KEY1);
        assertInvalid(() -> c.decode("a".repeat(1020) + ".abcd", scope()));
        assertThatThrownBy(() -> c.encode(scope(), List.of("a".repeat(2000)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CursorCodec.Scope("x".repeat(33), ORG, MEMBERSHIP, null, CursorCodec.filters(), "s"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new String(Base64.getUrlDecoder().decode(c.encode(scope(), List.of("a")).split("\\.")[0]), StandardCharsets.UTF_8))
                .as("formato do payload").startsWith("{\"v\":1,\"k\":\"MxEu4U7kacP\",\"r\":\"listWorkspaces\"");
    }
}
