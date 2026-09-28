package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.ConcorrenciaIT.Aberta;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Revisao 4, item 10: tentativas de aviso numeradas, protocolo de estados, resultados idempotentes, tentativa vigente,
 * aceite fora de ordem com uso dos tokens, recuperacao de queda, condicao (2) da aprovacao final e reenvio depois da
 * aprovacao. Transporte simulado: os desfechos sao informados pela identidade da Platform (Cenarios.resultado).
 */
class Revisao4TentativasIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private static final short ENFILEIRADA = 3;
    private static final short ACEITA = 4;
    private static final short FALHOU = 5;
    private static final short DESCONHECIDO = 6;

    private record P(Cenarios.Equipe eq, Cenarios.Alvo al, UUID outro, UUID s) {
        UUID org() {
            return al.org().id();
        }
    }

    /** Pre-aprovada com um outro ORG_ADMIN: duas tentativas pendentes (titular e outro). */
    private static P preparado() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID b = DB.outroAdmin(al.org());
        UUID s = C.preAprovada(eq, al);
        return new P(eq, al, b, s);
    }

    private static Cenarios.Tokens de(List<Cenarios.Tokens> ts, UUID destinatario) {
        return ts.stream().filter(t -> t.destinatario().equals(destinatario)).findFirst().orElseThrow();
    }

    /** Reenvio ao destinatario e emissao (sem entregar): devolve os tokens da nova tentativa, em EMITIDA. */
    private static Cenarios.Tokens reenviarEEmitir(P p, UUID destinatario) {
        Cenarios.exigirOk(p.eq().v().reenviarAviso(p.s(), destinatario), "reenviar");
        return de(C.emitirAvisos(p.org(), (short) 0), destinatario);
    }

    private static void entregando(UUID aviso) {
        assertThat(C.reivindicar(aviso, UUID.randomUUID()).get(1)).isEqualTo(true);
    }

    private static long invalidados(UUID aviso) {
        return DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notice_tokens WHERE notice_id = ? AND invalidated_at IS NOT NULL", aviso);
    }

    private static UUID vigente(P p, UUID destinatario, int tipo) {
        return DB.valor(UUID.class, "SELECT ops.tentativa_vigente(?, ?, ?::smallint)", p.s(), destinatario, tipo);
    }

    private static String sqlstate(String sql) {
        try (Connection c = DB.superusuario(); Statement st = c.createStatement()) {
            st.execute(sql);
            return "00000";
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    // ------------------------------------------------------------------ protocolo e idempotencia

    @Test
    void transicoesPermitidasEProibidasEResultadosIdempotentes() {
        P p = preparado();
        List<Cenarios.Tokens> ts = C.emitirAvisos(p.org(), (short) 0);
        UUID t1 = de(ts, p.al().titular()).aviso();
        UUID b1 = de(ts, p.outro()).aviso();
        // EMITIDA: so FALHOU e aceito (ACEITA, ENFILEIRADA e DESCONHECIDO sem ENTREGANDO sao violacao do protocolo)
        for (short e : new short[] {ACEITA, ENFILEIRADA, DESCONHECIDO}) {
            assertThat(C.resultado(t1, e, null)).as("EMITIDA -> %s", e).isEqualTo("TRANSICAO_INVALIDA");
            assertThat(C.estadoDoAviso(t1)).isEqualTo((short) 1);
        }
        // ENTREGANDO -> ACEITA; repeticao; conflito; atraso
        entregando(b1);
        assertThat(C.resultado(b1, ACEITA, null)).isEqualTo("APLICADO");
        String aceite = DB.valor(String.class, "SELECT accepted_at::text FROM ops.ops_mfa_notices WHERE notice_id = ?", b1);
        long resultados = DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notice_results WHERE notice_id = ?", b1);
        assertThat(C.resultado(b1, ACEITA, null)).isEqualTo("REPETIDO");
        assertThat(DB.valor(String.class, "SELECT accepted_at::text FROM ops.ops_mfa_notices WHERE notice_id = ?", b1)).as("primeiro aceite").isEqualTo(aceite);
        assertThat(C.resultado(b1, FALHOU, "RECUSADO_PELO_TRANSPORTE")).isEqualTo("CONFLITANTE");
        assertThat(C.resultado(b1, ENFILEIRADA, null)).as("ENFILEIRADA depois do aceite so vai ao historico").isEqualTo("ATRASADO");
        assertThat(C.estadoDoAviso(b1)).isEqualTo(ACEITA);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notice_results WHERE notice_id = ?", b1)).isEqualTo(resultados + 3);
        // ENTREGANDO -> DESCONHECIDO -> (ENFILEIRADA atrasada) -> ACEITA
        entregando(t1);
        assertThat(C.resultado(t1, DESCONHECIDO, "RESULTADO_INCERTO")).isEqualTo("APLICADO");
        assertThat(C.resultado(t1, ENFILEIRADA, null)).isEqualTo("ATRASADO");
        assertThat(C.resultado(t1, ACEITA, null)).isEqualTo("APLICADO");
        // EMITIDA -> FALHOU invalida os tokens da propria tentativa
        Cenarios.Tokens t2 = reenviarEEmitir(p, p.al().titular());
        assertThat(C.resultado(t2.aviso(), FALHOU, "SEM_DESTINATARIO")).isEqualTo("APLICADO");
        assertThat(invalidados(t2.aviso())).isEqualTo(2);
        // ENTREGANDO -> ENFILEIRADA -> ENFILEIRADA (repetido) -> ACEITA
        Cenarios.Tokens t3 = reenviarEEmitir(p, p.al().titular());
        entregando(t3.aviso());
        assertThat(C.resultado(t3.aviso(), ENFILEIRADA, null)).isEqualTo("APLICADO");
        assertThat(C.resultado(t3.aviso(), ENFILEIRADA, null)).isEqualTo("REPETIDO");
        assertThat(C.resultado(t3.aviso(), ACEITA, null)).isEqualTo("APLICADO");
        // estados finais nao mudam nem pelo superusuario (trigger), e categoria nao aceita texto livre
        assertThat(sqlstate("UPDATE ops.ops_mfa_notices SET state = 5 WHERE notice_id = '" + b1 + "'")).isNotEqualTo("00000");
        try {
            C.resultado(t3.aviso(), FALHOU, "falha para fulano@exemplo.test");
            throw new AssertionError("categoria livre aceita");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("22023");
        }
    }

    @Test
    void recuperacaoDeTentativasParadas() {
        P p = preparado();
        List<Cenarios.Tokens> ts = C.emitirAvisos(p.org(), (short) 0);
        UUID emitida = de(ts, p.al().titular()).aviso();
        UUID entregando = de(ts, p.outro()).aviso();
        entregando(entregando);
        DB.recuarEstadoDoAviso(emitida, Duration.ofMinutes(11));
        DB.recuarEstadoDoAviso(entregando, Duration.ofMinutes(11));
        assertThat(recuperar(emitida)).isEqualTo("RECUPERADA");
        assertThat(recuperar(entregando)).isEqualTo("RECUPERADA");
        assertThat(C.estadoDoAviso(emitida)).as("nunca saiu").isEqualTo(FALHOU);
        assertThat(invalidados(emitida)).isEqualTo(2);
        assertThat(C.estadoDoAviso(entregando)).as("pode ter saido").isEqualTo(DESCONHECIDO);
        assertThat(invalidados(entregando)).as("tokens validos, nenhum aceite presumido").isZero();
        assertThat(DB.valor(Boolean.class, "SELECT accepted_at IS NULL FROM ops.ops_mfa_notices WHERE notice_id = ?", entregando)).isTrue();
        // ENFILEIRADA sem resultado ha 24 h -> DESCONHECIDO; antes disso, nada a fazer
        Cenarios.Tokens nova = reenviarEEmitir(p, p.outro());
        entregando(nova.aviso());
        assertThat(C.resultado(nova.aviso(), ENFILEIRADA, null)).isEqualTo("APLICADO");
        DB.recuarEstadoDoAviso(nova.aviso(), Duration.ofHours(23));
        assertThat(recuperar(nova.aviso())).isEqualTo("NADA_A_FAZER");
        DB.recuarEstadoDoAviso(nova.aviso(), Duration.ofHours(2));
        assertThat(recuperar(nova.aviso())).isEqualTo("RECUPERADA");
        assertThat(C.estadoDoAviso(nova.aviso())).isEqualTo(DESCONHECIDO);
        // nenhum aceite presumido: a condicao (2) continua insatisfeita
        assertThat(DB.valor(String.class, "SELECT ops.condicao_avisos_demais(?, clock_timestamp() + interval '100 hours')", p.s()))
                .isEqualTo("AVISO_DEMAIS_PENDENTE");
    }

    private static String recuperar(UUID aviso) {
        try (Connection c = DB.comoServico("nekkora_platform"); PreparedStatement ps = c.prepareStatement("SELECT r_resultado FROM public.ops_notice_recover(?)")) {
            ps.setObject(1, aviso);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Worker e varredura disputando a mesma tentativa EMITIDA parada, nas duas ordens: um unico desfecho. */
    @Test
    void workerEVarreduraTemDesfechoUnico() {
        P p = preparado();
        List<Cenarios.Tokens> ts = C.emitirAvisos(p.org(), (short) 0);
        UUID a = de(ts, p.al().titular()).aviso();
        UUID b = de(ts, p.outro()).aviso();
        DB.recuarEstadoDoAviso(a, Duration.ofMinutes(11));
        DB.recuarEstadoDoAviso(b, Duration.ofMinutes(11));
        assertThat(recuperar(a)).isEqualTo("RECUPERADA");
        assertThat(C.reivindicar(a, UUID.randomUUID())).as("varredura antes: nao entrega").containsExactly((short) 5, false);
        assertThat(C.reivindicar(b, UUID.randomUUID()).get(1)).isEqualTo(true);
        assertThat(recuperar(b)).as("worker antes: a reivindicacao e recente").isEqualTo("NADA_A_FAZER");
        assertThat(C.estadoDoAviso(b)).isEqualTo((short) 2);
    }

    // ------------------------------------------------------------------ vigente e aceite fora de ordem

    /** 1 ACEITA e 2 FALHOU -> a vigente volta a ACEITA: a condicao (2) e cumprida por ela; os tokens das que falharam nao valem. */
    @Test
    void vigenteIgnoraTentativasQueFalharam() {
        P p = preparado();
        List<Cenarios.Tokens> ts = C.emitirAvisos(p.org());
        Cenarios.Tokens aceita = de(ts, p.outro());
        Cenarios.Tokens f1 = reenviarEEmitir(p, p.outro());
        assertThat(C.resultado(f1.aviso(), FALHOU, "SEM_DESTINATARIO")).isEqualTo("APLICADO");
        Cenarios.Tokens f2 = reenviarEEmitir(p, p.outro());
        entregando(f2.aviso());
        assertThat(C.resultado(f2.aviso(), FALHOU, "RECUSADO_PELO_TRANSPORTE")).isEqualTo("APLICADO");
        assertThat(vigente(p, p.outro(), 2)).isEqualTo(aceita.aviso());
        assertThat(C.confirmar(p.org(), de(ts, p.al().titular()).confirmar())).isTrue();
        DB.recuarSolicitacao(p.s(), Cenarios.ESPERA);
        Cenarios.exigirOk(p.eq().a().aprovar(p.s()), "aprovar com a vigente aceita");
        assertThat(C.contestar(p.org(), f1.contestar())).isFalse();
        assertThat(C.contestar(p.org(), f2.contestar())).isFalse();
    }

    /** 1 ACEITA e 2 DESCONHECIDO -> a vigente e a ultima DESCONHECIDO e a condicao (2) fica insatisfeita. */
    @Test
    void desconhecidoTornaAVigentePendente() {
        P p = preparado();
        List<Cenarios.Tokens> ts = C.emitirAvisos(p.org());
        for (int i = 0; i < 2; i++) {
            Cenarios.Tokens d = reenviarEEmitir(p, p.outro());
            entregando(d.aviso());
            assertThat(C.resultado(d.aviso(), DESCONHECIDO, "RESULTADO_INCERTO")).isEqualTo("APLICADO");
        }
        assertThat(C.estadoDoAviso(vigente(p, p.outro(), 2))).isEqualTo(DESCONHECIDO);
        assertThat(C.confirmar(p.org(), de(ts, p.al().titular()).confirmar())).isTrue();
        DB.recuarSolicitacao(p.s(), Cenarios.ESPERA);
        assertThat(p.eq().a().aprovar(p.s()).motivo()).isEqualTo("AVISO_DEMAIS_PENDENTE");
        assertThat(C.estado(p.s())).isEqualTo("PRE_APROVADA");
    }

    /**
     * Aceite fora de ordem: a tentativa 2 e aceita (invalida os tokens da 1) e depois chega o aceite da 1, que NAO invalida
     * os da 2. CONTESTAR da 2 vale; o da 1 nao; a vigente continua a 2. Repetido para o CONFIRMAR do titular.
     */
    @Test
    void aceiteForaDeOrdemPreservaATentativaPosterior() {
        P p = preparado();
        List<Cenarios.Tokens> ts = C.emitirAvisos(p.org(), (short) 0);
        Cenarios.Tokens t1 = de(ts, p.al().titular());
        Cenarios.Tokens b1 = de(ts, p.outro());
        entregando(t1.aviso());
        entregando(b1.aviso());
        Cenarios.Tokens t2 = reenviarEEmitir(p, p.al().titular());
        Cenarios.Tokens b2 = reenviarEEmitir(p, p.outro());
        for (Cenarios.Tokens n : List.of(t2, b2)) {
            entregando(n.aviso());
            assertThat(C.resultado(n.aviso(), ACEITA, null)).isEqualTo("APLICADO");
        }
        assertThat(invalidados(t1.aviso())).isEqualTo(2);
        assertThat(invalidados(b1.aviso())).isEqualTo(1);
        for (Cenarios.Tokens n : List.of(t1, b1)) {
            assertThat(C.resultado(n.aviso(), ACEITA, null)).as("aceite atrasado da anterior").isEqualTo("APLICADO");
        }
        assertThat(invalidados(t2.aviso())).as("seq maior nunca e invalidado").isZero();
        assertThat(invalidados(b2.aviso())).isZero();
        assertThat(vigente(p, p.outro(), 2)).isEqualTo(b2.aviso());
        assertThat(C.confirmar(p.org(), t1.confirmar())).isFalse();
        assertThat(C.confirmar(p.org(), t2.confirmar())).isTrue();
        assertThat(C.contestar(p.org(), b1.contestar())).isFalse();
        assertThat(C.contestar(p.org(), b2.contestar())).isTrue();
        assertThat(C.estado(p.s())).isEqualTo("CANCELADA");
    }

    /** Reenvio que falha (antes da fila e no transporte): o token anterior continua utilizavel. */
    @Test
    void reenvioQueFalhaMantemOTokenAnterior() {
        P p = preparado();
        List<Cenarios.Tokens> ts = C.emitirAvisos(p.org());
        Cenarios.Tokens b1 = de(ts, p.outro());
        Cenarios.Tokens antes = reenviarEEmitir(p, p.outro());
        assertThat(C.resultado(antes.aviso(), FALHOU, "SEM_DESTINATARIO")).isEqualTo("APLICADO");
        Cenarios.Tokens transporte = reenviarEEmitir(p, p.outro());
        entregando(transporte.aviso());
        assertThat(C.resultado(transporte.aviso(), FALHOU, "RECUSADO_PELO_TRANSPORTE")).isEqualTo("APLICADO");
        assertThat(invalidados(b1.aviso())).isZero();
        assertThat(C.contestar(p.org(), b1.contestar())).isTrue();
    }

    // ------------------------------------------------------------------ condicao (2) e prazos

    @Test
    void enfileiradaNaoSatisfazACondicaoDosDemais() {
        P p = preparado();
        List<Cenarios.Tokens> ts = C.emitirAvisos(p.org(), ENFILEIRADA);
        assertThat(C.confirmar(p.org(), de(ts, p.al().titular()).confirmar())).isTrue();
        DB.recuarSolicitacao(p.s(), Cenarios.ESPERA.plusHours(1));
        assertThat(p.eq().a().aprovar(p.s()).motivo()).isEqualTo("AVISO_DEMAIS_PENDENTE");
    }

    /** Aceite dos demais ha menos de 24 h -> pendente; aceite com aceite + 24 h >= T_total -> a solicitacao expira em T_total. */
    @Test
    void aceiteTardioDosDemaisPendenteEDepoisExpira() {
        P p = preparado();
        List<Cenarios.Tokens> ts = C.emitirAvisos(p.org(), (short) 0);
        Cenarios.Tokens t = de(ts, p.al().titular());
        Cenarios.Tokens b = de(ts, p.outro());
        entregando(t.aviso());
        assertThat(C.resultado(t.aviso(), ACEITA, null)).isEqualTo("APLICADO");
        entregando(b.aviso());
        assertThat(C.confirmar(p.org(), t.confirmar())).isTrue();
        DB.recuarSolicitacao(p.s(), Duration.ofHours(50));
        assertThat(C.resultado(b.aviso(), ACEITA, null)).isEqualTo("APLICADO");   // aceite em T_total - 22 h
        assertThat(p.eq().a().aprovar(p.s()).motivo()).isEqualTo("AVISO_DEMAIS_PENDENTE");
        DB.recuarSolicitacao(p.s(), Duration.ofHours(22));
        assertThat(p.eq().a().aprovar(p.s()).motivo()).isEqualTo("PRAZO_ENCERRADO");
        assertThat(C.estado(p.s())).isEqualTo("EXPIRADA");
    }

    /**
     * Reenvio depois da aprovacao, ao titular e a um outro administrador, mesmo que a nova tentativa falhe: a execucao e
     * recusada (CONDICAO_DE_AVISO_ALTERADA), sem mudar a primeira confirmacao nem os prazos.
     */
    @Test
    void reenvioDepoisDaAprovacaoImpedeAExecucao() {
        for (boolean aoTitular : new boolean[] {true, false}) {
            Cenarios.Equipe eq = C.equipe();
            Cenarios.Alvo al = C.alvo();
            UUID b = DB.outroAdmin(al.org());
            UUID s = C.aprovada(eq, al);
            String antes = DB.valor(String.class, "SELECT notice_confirmed_at || '|' || t_total || '|' || final_approved_at FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", s);
            UUID dest = aoTitular ? al.titular() : b;
            Cenarios.exigirOk(eq.v().reenviarAviso(s, dest), "reenviar");
            Cenarios.Tokens nova = de(C.emitirAvisos(al.org().id(), (short) 0), dest);
            assertThat(C.resultado(nova.aviso(), FALHOU, "SEM_DESTINATARIO")).isEqualTo("APLICADO");
            assertThat(eq.e().executar(s).motivo()).as("ao titular=%s", aoTitular).isEqualTo("CONDICAO_DE_AVISO_ALTERADA");
            assertThat(C.estado(s)).isEqualTo("APROVADA");
            assertThat(C.resets(s)).isZero();
            assertThat(DB.valor(String.class, "SELECT notice_confirmed_at || '|' || t_total || '|' || final_approved_at FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", s))
                    .isEqualTo(antes);
        }
    }

    // ------------------------------------------------------------------ emissao de tentativa antiga depois do aceite da nova

    private record Emissao(boolean emitido, String confirmar, String contestar) {
    }

    /** Emissao de UMA tentativa pela identidade da Platform, com tokens brutos conhecidos (so o hash vai ao banco). */
    static Emissao emitirUma(UUID aviso) {
        String conf = OpsBanco.tokenBruto();
        String cont = OpsBanco.tokenBruto();
        try (Connection c = DB.comoServico("nekkora_platform");
             PreparedStatement ps = c.prepareStatement("SELECT r_emitido, r_confirmar, r_contestar FROM public.ops_notice_issue(?, ?, ?)")) {
            ps.setObject(1, aviso);
            ps.setString(2, OpsBanco.sha256(conf));
            ps.setString(3, OpsBanco.sha256(cont));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Emissao(rs.getBoolean(1), rs.getBoolean(2) ? conf : null, rs.getBoolean(3) ? cont : null);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Tentativa pendente do destinatario: a mais antiga ({@code maisNova = false}) ou a mais nova. */
    private static UUID pendente(P p, UUID destinatario, boolean maisNova) {
        return DB.valor(UUID.class, "SELECT notice_id FROM ops.ops_mfa_notices WHERE request_id = ? AND recipient_user_id = ? AND state = 0 "
                + "ORDER BY seq " + (maisNova ? "DESC" : "ASC") + " LIMIT 1", p.s(), destinatario);
    }

    /**
     * Tentativa 1 criada e ainda sem tokens; tentativa 2 emitida e ACEITA; so entao a 1 emite: nenhuma finalidade ja
     * substituida ganha token (a tentativa vai a FALHOU, SUBSTITUIDA_POR_TENTATIVA_ACEITA), o aceite tardio da 1 e
     * conflitante e sem efeito, e os tokens da 2 continuam utilizaveis. Outro administrador (CONTESTAR) e titular
     * (CONFIRMAR e CONTESTAR). Uso pela rota HTTP real: EmergencyRecoveryFlowIT; contraprova: MutacoesIT (M11).
     */
    @Test
    void tentativaAntigaNaoEmiteDepoisDoAceiteDaNova() {
        P p = preparado();
        for (UUID dest : List.of(p.outro(), p.al().titular())) {
            UUID antiga = pendente(p, dest, false);
            Cenarios.exigirOk(p.eq().v().reenviarAviso(p.s(), dest), "reenviar");
            UUID nova = pendente(p, dest, true);
            assertThat(nova).isNotEqualTo(antiga);
            Emissao en = emitirUma(nova);
            assertThat(en.emitido()).isTrue();
            entregando(nova);
            assertThat(C.resultado(nova, ACEITA, null)).isEqualTo("APLICADO");
            Emissao ea = emitirUma(antiga);
            assertThat(ea.emitido()).as("tentativa antiga depois do aceite da nova (%s)", dest).isFalse();
            assertThat(C.estadoDoAviso(antiga)).isEqualTo(FALHOU);
            assertThat(DB.valor(String.class, "SELECT category FROM ops.ops_mfa_notices WHERE notice_id = ?", antiga))
                    .isEqualTo("SUBSTITUIDA_POR_TENTATIVA_ACEITA");
            assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notice_tokens WHERE notice_id = ?", antiga)).isZero();
            assertThat(C.resultado(antiga, ACEITA, null)).as("aceite tardio da antiga").isEqualTo("CONFLITANTE");
            assertThat(invalidados(nova)).isZero();
            if (dest.equals(p.al().titular())) {
                assertThat(C.confirmar(p.org(), en.confirmar())).as("CONFIRMAR novo").isTrue();
            } else {
                assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notice_tokens WHERE notice_id = ? AND used_at IS NULL AND invalidated_at IS NULL", nova))
                        .as("CONTESTAR novo utilizavel (usado pela rota em EmergencyRecoveryFlowIT)").isEqualTo(1);
            }
        }
    }

    /**
     * Detector de M11 (MutacoesIT): o CONTESTAR que a tentativa antiga emitiria depois do aceite da nova e recusado pela
     * funcao de consumo, e o novo continua utilizavel.
     */
    static void detectorEmissaoAntiga() {
        P p = preparado();
        UUID antiga = pendente(p, p.outro(), false);
        Cenarios.exigirOk(p.eq().v().reenviarAviso(p.s(), p.outro()), "reenviar");
        UUID nova = pendente(p, p.outro(), true);
        Emissao en = emitirUma(nova);
        entregando(nova);
        assertThat(C.resultado(nova, ACEITA, null)).isEqualTo("APLICADO");
        Emissao ea = emitirUma(antiga);
        if (ea.contestar() != null) {
            assertThat(C.contestar(p.org(), ea.contestar())).as("token antigo nascido depois do aceite da nova").isFalse();
        }
        assertThat(C.contestar(p.org(), en.contestar())).as("token novo").isTrue();
    }

    @Test
    void tokenAntigoRecusadoENovoUtilizavel() {
        detectorEmissaoAntiga();
    }

    /** A correcao nao impede a entrega da antiga quando a mais recente falhou, ficou desconhecida ou ainda nao foi aceita. */
    @Test
    void tentativaAntigaEmiteQuandoANovaFalhouOuEIncertaOuPendente() {
        for (short desfechoNova : new short[] {FALHOU, DESCONHECIDO, ENFILEIRADA, (short) 2}) {
            P p = preparado();
            UUID antiga = pendente(p, p.outro(), false);
            Cenarios.exigirOk(p.eq().v().reenviarAviso(p.s(), p.outro()), "reenviar");
            UUID nova = pendente(p, p.outro(), true);
            assertThat(emitirUma(nova).emitido()).isTrue();
            entregando(nova);
            if (desfechoNova != 2) {
                assertThat(C.resultado(nova, desfechoNova, desfechoNova == FALHOU ? "RECUSADO_PELO_TRANSPORTE" : null)).isEqualTo("APLICADO");
            }
            Emissao ea = emitirUma(antiga);
            assertThat(ea.emitido()).as("nova em %s", desfechoNova).isTrue();
            entregando(antiga);
            assertThat(C.resultado(antiga, ACEITA, null)).isEqualTo("APLICADO");
            assertThat(C.contestar(p.org(), ea.contestar())).as("token da antiga, nova em %s", desfechoNova).isTrue();
        }
    }

    // ------------------------------------------------------------------ prazo x condicao de aviso na execucao

    private static String instantes(UUID s) {
        return DB.valor(String.class, "SELECT notice_confirmed_at || '|' || t_total || '|' || final_approved_at FROM ops.ops_mfa_recovery_requests "
                + "WHERE request_id = ?", s);
    }

    /**
     * Reenvio depois da aprovacao: antes do vencimento, CONDICAO_DE_AVISO_ALTERADA (recusa do ato, APROVADA); no
     * vencimento e depois, EXPIRADA; nunca reset; primeira confirmacao e prazos inalterados pelos atos. Detector de M12.
     */
    @Test
    void prazoTemPrecedenciaSobreACondicaoDeAviso() {
        detectorPrazo();
    }

    static void detectorPrazo() {
        assertThat(execucaoDepoisDoReenvio(Duration.ofMinutes(59))).isEqualTo("CONDICAO_DE_AVISO_ALTERADA|APROVADA");
        assertThat(execucaoDepoisDoReenvio(Duration.ofMinutes(60))).isEqualTo("PRAZO_ENCERRADO|EXPIRADA");
        assertThat(execucaoDepoisDoReenvio(Duration.ofHours(2))).isEqualTo("PRAZO_ENCERRADO|EXPIRADA");
    }

    /** Limite exato da janela de execucao (aprovacao + 60 min), por p_agora explicito. */
    @Test
    void limiteExatoDaJanelaDeExecucao() {
        Cenarios.Equipe eq = C.equipe();
        UUID s = C.aprovada(eq, C.alvo());
        String q = "SELECT ops.vencida(state, opened_at, t_total, final_approved_at, final_approved_at + interval '60 minutes' %s)::text "
                + "FROM ops.ops_mfa_recovery_requests WHERE request_id = ?";
        assertThat(DB.valor(String.class, String.format(q, "- interval '1 microsecond'"), s)).isEqualTo("false");
        assertThat(DB.valor(String.class, String.format(q, ""), s)).isEqualTo("true");
        assertThat(DB.valor(String.class, String.format(q, "+ interval '1 second'"), s)).isEqualTo("true");
    }

    static String execucaoDepoisDoReenvio(Duration passagem) {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID b = DB.outroAdmin(al.org());
        UUID s = C.aprovada(eq, al);
        Cenarios.exigirOk(eq.v().reenviarAviso(s, b), "reenviar depois da aprovacao");
        DB.recuarSolicitacao(s, passagem);
        String antes = instantes(s);
        var r = eq.e().executar(s);
        assertThat(C.resets(s)).as("sem reset").isZero();
        assertThat(instantes(s)).as("primeira confirmacao e prazos inalterados").isEqualTo(antes);
        String estado = C.estado(s);
        var r2 = eq.e().executar(s);
        assertThat(r2.motivo()).as("repeticao").isEqualTo("EXPIRADA".equals(estado) ? "ESTADO_INVALIDO" : r.motivo());
        assertThat(C.resets(s)).isZero();
        assertThat(instantes(s)).isEqualTo(antes);
        return r.motivo() + "|" + estado;
    }

    // ------------------------------------------------------------------ serializacao

    /** Registro de aceite com a execucao segurando a solicitacao, nas duas ordens: espera simples, sem impasse. */
    @Test
    void registroDeAceiteSerializaComAExecucao() throws Exception {
        for (boolean execucaoPrimeiro : new boolean[] {true, false}) {
            Cenarios.Equipe eq = C.equipe();
            Cenarios.Alvo al = C.alvo();
            UUID b = DB.outroAdmin(al.org());
            UUID s = C.aprovada(eq, al);
            UUID avisoB = DB.valor(UUID.class, "SELECT notice_id FROM ops.ops_mfa_notices WHERE request_id = ? AND recipient_user_id = ? AND kind = 2", s, b);
            try (Aberta exe = new Aberta(eq.executor()); Connection pl = DB.comoServico("nekkora_platform")) {
                pl.setAutoCommit(false);
                String registro = "SELECT r_resultado FROM public.ops_notice_delivery_result('" + avisoB + "', 4::smallint, NULL)";
                if (execucaoPrimeiro) {
                    assertThat(exe.chamar("ops_mfa_execute", s)[0]).isEqualTo("OK");
                    CompletableFuture<String> r = CompletableFuture.supplyAsync(() -> valor(pl, registro));
                    CertificadosIT.esperarBloqueado("nekkora_platform");
                    exe.commit();
                    assertThat(r.get(30, TimeUnit.SECONDS)).isEqualTo("REPETIDO");
                    pl.commit();
                } else {
                    assertThat(valor(pl, registro)).isEqualTo("REPETIDO");
                    CompletableFuture<String[]> r = CompletableFuture.supplyAsync(() -> {
                        try {
                            return exe.chamar("ops_mfa_execute", s);
                        } catch (SQLException e) {
                            return new String[] {"ERRO", e.getSQLState()};
                        }
                    });
                    CertificadosIT.esperarBloqueado(eq.executor().papel());
                    pl.commit();
                    assertThat(r.get(30, TimeUnit.SECONDS)[0]).isEqualTo("OK");
                    exe.commit();
                }
            }
            assertThat(C.estado(s)).isEqualTo("EXECUTADA");
        }
    }

    private static String valor(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            return "ERRO:" + e.getSQLState();
        }
    }
}
