package com.nekkora.platform.emergency;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.core.token.Tokens;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Gatilho manual da varredura ({@link VarreduraAvisos}, B10), com o banco real da 016 e a identidade REAL da Platform (TLS,
 * nekkora_platform): uma tentativa reivindicada (ENTREGANDO) e nunca concluida, como na queda do processo, so e
 * recuperada depois de 10 min parada. O tempo parado e uma FIXTURE TEMPORAL DECLARADA (recuo de {@code state_at}); a
 * espera real fica para a homologacao (h016.sh, B10). Controle: tentativa ENTREGANDO recente nao e tocada.
 */
class VarreduraAvisosIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private static UUID entregandoParada() {
        UUID s = C.preAprovada(C.equipe(), C.alvo());
        UUID aviso = DB.valor(UUID.class, "SELECT notice_id FROM ops.ops_mfa_notices WHERE request_id = ? AND state = 0", s);
        // como a Platform, ate a reivindicacao (EMITIDA -> ENTREGANDO), e o processo "cai" antes de chamar o transporte
        try (Connection c = DB.comoServico("nekkora_platform")) {
            try (PreparedStatement ps = c.prepareStatement("SELECT r_emitido FROM public.ops_notice_issue(?, ?, ?)")) {
                ps.setObject(1, aviso);
                ps.setString(2, Tokens.sha256Hex(Tokens.newToken()));
                ps.setString(3, Tokens.sha256Hex(Tokens.newToken()));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBoolean(1)).as("emitida").isTrue();
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT r_minha FROM public.ops_notice_claim(?, ?)")) {
                ps.setObject(1, aviso);
                ps.setObject(2, UUID.randomUUID());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getBoolean(1)).as("reivindicada").isTrue();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(estado(aviso)).isEqualTo((short) 2);
        return aviso;
    }

    private static short estado(UUID aviso) {
        return DB.valor(Short.class, "SELECT state FROM ops.ops_mfa_notices WHERE notice_id = ?", aviso);
    }

    private record Saida(int codigo, String out, String err) {
    }

    private static Saida varrer(Map<String, String> env) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int c = VarreduraAvisos.run(env, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Saida(c, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static Map<String, String> comoAPlatform() {
        return Map.of("NEKKORA_DB_URL", DB.urlTls(), "NEKKORA_DB_USUARIO", "nekkora_platform", "NEKKORA_DB_SENHA", DB.senha("nekkora_platform"));
    }

    /**
     * Parada ha 11 min (fixture declarada) -> DESCONHECIDO pela varredura; a recente (controle) continua ENTREGANDO. A
     * segunda varredura nao recupera nada de novo. Nada e entregue (a porta da varredura recusa qualquer envio). A saida
     * so tem a contagem.
     */
    @Test
    void recuperaSoAParadaHaMaisDeDezMinutosSemEntregar() {
        UUID parada = entregandoParada();
        UUID recente = entregandoParada();
        DB.recuarEstadoDoAviso(parada, Duration.ofMinutes(11));

        Saida s = varrer(comoAPlatform());
        assertThat(s.codigo()).as(s.err()).isZero();
        assertThat(s.out()).matches("(?s)varredura_avisos executada recuperadas=[1-9][0-9]*\\R");
        assertThat(estado(parada)).as("parada: DESCONHECIDO, nunca ACEITA").isEqualTo((short) 6);
        assertThat(estado(recente)).as("controle: recente nao e tocada").isEqualTo((short) 2);
        assertThat(DB.valor(String.class, "SELECT coalesce(category, '-') FROM ops.ops_mfa_notices WHERE notice_id = ?", parada)).isNotEqualTo("ACEITE_SIMULADO");

        Saida de_novo = varrer(comoAPlatform());
        assertThat(de_novo.codigo()).isZero();
        assertThat(estado(parada)).isEqualTo((short) 6);
        assertThat(estado(recente)).isEqualTo((short) 2);
        assertThat(s.out() + s.err() + de_novo.out() + de_novo.err()).doesNotContain(DB.senha("nekkora_platform")).doesNotContain("@");
    }

    /** Sem configuracao: 64, nada feito. Com o superusuario: recusa (64), como a Platform recusa iniciar. */
    @Test
    void recusaSemConfiguracaoOuComPapelInadequado() {
        assertThat(varrer(Map.of()).codigo()).isEqualTo(64);
        Saida su = varrer(Map.of("NEKKORA_DB_URL", DB.urlTls(), "NEKKORA_DB_USUARIO", "nekkora", "NEKKORA_DB_SENHA", DB.senhaSuperusuario()));
        assertThat(su.codigo()).isEqualTo(64);
        assertThat(su.err()).contains("papel_inadequado").doesNotContain(DB.senhaSuperusuario());
    }
}
