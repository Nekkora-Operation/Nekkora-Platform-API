package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.Credencial;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.cli.ExecutorAtos.Resultado;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Revisao 4, itens 1 (720 h; janelas de 168 h e 720 h), 2 (impedimento por qualquer contato da organizacao) e 3 (recusa
 * do ato x recusa terminal; erros tecnicos nao viram recusa).
 *
 * <p>Limites exatos por {@code p_agora} explicito nas funcoes internas (superusuario, so nesta base de teste), em
 * {@code limite - 1 us}, {@code limite} e {@code limite + 1 s}, repetidos com TimeZone de sessao que atravessa a mudanca
 * de horario de verao. A fixture e deslocada para tras ate que a janela de 720 h comece 10 dias antes da ultima mudanca de
 * horario de Europe/Berlin ({@link #recuoAteAntesDaMudancaDeHorario}): a travessia vale em qualquer data de execucao (antes,
 * as janelas eram contadas a partir de hoje e so atravessavam a mudanca de 25/10/2026).
 */
class Revisao4PrazosEImpedimentosIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private static final List<String> FUSOS = List.of("UTC", "Europe/Berlin", "America/Sao_Paulo", "America/New_York");

    /**
     * Recuo que leva um instante de agora para 10 dias antes da ultima mudanca de horario de Europe/Berlin: a janela de
     * 720 h (30 dias) a partir dele atravessa a mudanca, qualquer que seja a data de execucao.
     */
    private static Duration recuoAteAntesDaMudancaDeHorario() {
        Instant agora = Instant.now();
        Instant mudanca = ZoneId.of("Europe/Berlin").getRules().previousTransition(agora).getInstant();
        return Duration.between(mudanca.minus(Duration.ofDays(10)), agora);
    }

    /** Consulta sob um TimeZone de sessao. */
    private static String sob(String fuso, String sql, Object... params) {
        try (Connection c = DB.superusuario()) {
            try (Statement st = c.createStatement()) {
                st.execute("SET TIME ZONE '" + fuso + "'");
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ item 1

    @Test
    void item1CarenciaDe720HorasNosLimitesEEmQualquerFuso() {
        Cenarios.Alvo al = C.alvo();
        UUID c = DB.contatoNovo(al.org(), DB.membro(al.org()));
        // todos os contatos da organizacao recuam juntos: as idades relativas (os dois do cenario 31 dias mais velhos) ficam
        Duration recuo = recuoAteAntesDaMudancaDeHorario();
        DB.semTriggers("UPDATE ops.org_authorized_contacts SET created_at = created_at - interval '" + recuo.toSeconds() + " seconds'"
                + ", channel_set_at = channel_set_at - interval '" + recuo.toSeconds() + " seconds' WHERE organization_id = '" + al.org().id() + "'");
        String base = "(SELECT channel_set_at FROM ops.org_authorized_contacts WHERE contact_id = ?)";
        for (String fuso : FUSOS) {
            assertThat(sob(fuso, "SELECT coalesce(ops.contato_inelegivel(?, ?, " + base + " + interval '720 hours' - interval '1 microsecond'), 'ELEGIVEL')",
                    al.org().id(), c, c)).as(fuso).isEqualTo("CANAL_RECENTE");
            assertThat(sob(fuso, "SELECT coalesce(ops.contato_inelegivel(?, ?, " + base + " + interval '720 hours'), 'ELEGIVEL')",
                    al.org().id(), c, c)).as(fuso).isEqualTo("ELEGIVEL");
            assertThat(sob(fuso, "SELECT coalesce(ops.contato_inelegivel(?, ?, " + base + " + interval '720 hours' + interval '1 second'), 'ELEGIVEL')",
                    al.org().id(), c, c)).as(fuso).isEqualTo("ELEGIVEL");
            // contagem de contatos elegiveis no mesmo limite (os dois do cenario ja tem 31 dias)
            assertThat(sob(fuso, "SELECT ops.contatos_elegiveis(?, " + base + " + interval '720 hours' - interval '1 microsecond')", al.org().id(), c))
                    .as(fuso).isEqualTo("2");
            assertThat(sob(fuso, "SELECT ops.contatos_elegiveis(?, " + base + " + interval '720 hours')", al.org().id(), c)).as(fuso).isEqualTo("3");
        }
        // a fixture atravessa a mudanca de horario: em Europe/Berlin, 30 dias nao sao 720 h a partir deste canal
        assertThat(sob("Europe/Berlin", "SELECT (" + base + " + interval '30 days') <> (" + base + " + interval '720 hours')", c, c))
                .as("o fuso de teste atravessa a mudanca de horario").isEqualTo("t");
    }

    @Test
    void item1JanelasDe168E720HorasNosLimites() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo base = C.alvo();
        OpsBanco.Org org = base.org();
        List<UUID> alvos = List.of(org.admin(), DB.outroAdmin(org), DB.outroAdmin(org));
        List<UUID> contatos = new ArrayList<>();
        for (UUID a : alvos) {
            contatos.add(a.equals(org.admin()) ? base.contatoTitular() : DB.contatoElegivel(org, a));
        }
        List<UUID> solicitacoes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID s = C.aprovada(eq, new Cenarios.Alvo(org, alvos.get(i), null, contatos.get(i), base.segundoContato()));
            Cenarios.exigirOk(eq.e().executar(s), "executar " + i);
            DB.fatorConfirmado(org.id(), alvos.get(i));
            solicitacoes.add(s);
        }
        // as tres execucoes recuam juntas (intervalos entre elas preservados)
        Duration recuo = recuoAteAntesDaMudancaDeHorario();
        for (UUID s : solicitacoes) {
            DB.recuarSolicitacao(s, recuo);
        }
        UUID s0 = solicitacoes.get(0);
        String e0 = "(SELECT executed_at FROM ops.ops_mfa_recovery_requests WHERE request_id = ?)";
        String emin = "(SELECT min(executed_at) FROM ops.ops_mfa_recovery_requests WHERE organization_id = ? AND state = 5)";
        UUID outro = UUID.randomUUID();
        for (String fuso : FUSOS) {
            // alvo: a execucao anterior deixa de contar quando executed_at = p_agora - 168 h
            assertThat(sob(fuso, "SELECT coalesce(ops.limite_executado(?, ?, " + e0 + " + interval '168 hours' - interval '1 microsecond'), '-')",
                    org.id(), alvos.get(0), s0)).as(fuso).isEqualTo("LIMITE_ALVO");
            assertThat(sob(fuso, "SELECT coalesce(ops.limite_executado(?, ?, " + e0 + " + interval '168 hours'), '-')",
                    org.id(), alvos.get(0), s0)).as(fuso).isNotEqualTo("LIMITE_ALVO");
            // organizacao: 3 em 720 h; a mais antiga deixa de contar em executed_at + 720 h
            assertThat(sob(fuso, "SELECT coalesce(ops.limite_executado(?, ?, " + emin + " + interval '720 hours' - interval '1 microsecond'), '-')",
                    org.id(), outro, org.id())).as(fuso).isEqualTo("LIMITE_ORGANIZACAO");
            assertThat(sob(fuso, "SELECT coalesce(ops.limite_executado(?, ?, " + emin + " + interval '720 hours'), '-')",
                    org.id(), outro, org.id())).as(fuso).isEqualTo("-");
            assertThat(sob(fuso, "SELECT coalesce(ops.limite_executado(?, ?, " + emin + " + interval '720 hours' + interval '1 second'), '-')",
                    org.id(), outro, org.id())).as(fuso).isEqualTo("-");
        }
        assertThat(sob("Europe/Berlin", "SELECT (" + emin + " + interval '30 days') <> (" + emin + " + interval '720 hours')", org.id(), org.id()))
                .as("a janela de 720 h atravessa a mudanca de horario em Europe/Berlin").isEqualTo("t");
    }

    /** Fluxo: contato solicitante com 719 h de canal e recusado; com 720 h (e alguns ms), abre. */
    @Test
    void item1FluxoComContatoA719hEA720h() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID c = DB.contatoNovo(al.org(), al.titular());
        DB.envelhecerContato(c, Duration.ofHours(719));
        Resultado r = C.abrirPorRetorno(eq.v(), al.org().id(), al.titular(), c);
        assertThat(r.motivo()).isEqualTo("CANAL_RECENTE");
        DB.envelhecerContato(c, Duration.ofHours(1));
        Cenarios.exigirOk(C.abrirPorRetorno(eq.v(), al.org().id(), al.titular(), c), "com 720 h");
    }

    // ------------------------------------------------------------------ item 2

    /** Operador vinculado a um TERCEIRO contato da organizacao (nem solicitante, nem segundo): impedido em cada ato. */
    @Test
    void item2VinculoComTerceiroContatoImpedeCadaAtoEAReconferencia() {
        Cenarios.Alvo al = C.alvo();
        UUID terceiro = DB.contatoElegivel(al.org(), DB.membro(al.org()));
        // ABRIR
        Cenarios.Equipe e1 = C.equipe();
        vincular(e1.verificador(), terceiro);
        assertThat(C.abrirPorRetorno(e1.v(), al.org().id(), al.titular(), al.contatoTitular()).motivo()).isEqualTo("VINCULO_IMPEDITIVO");
        // VERIFICAR
        Cenarios.Equipe e2 = C.equipe();
        UUID s2 = C.aberta(e2, al);
        vincular(e2.verificador(), terceiro);
        assertThat(C.verificar(e2.v(), s2, al.segundoContato()).motivo()).isEqualTo("VINCULO_IMPEDITIVO");
        assertThat(C.estado(s2)).isEqualTo("SOLICITADA");
        Cenarios.exigirOk(e2.v().cancelar(s2, "OUTRO"), "limpar");
        // PRE_APROVAR
        Cenarios.Equipe e3 = C.equipe();
        UUID s3 = C.verificada(e3, al);
        vincular(e3.aprovador(), terceiro);
        assertThat(e3.a().preAprovar(s3).motivo()).isEqualTo("VINCULO_IMPEDITIVO");
        assertThat(C.estado(s3)).isEqualTo("VERIFICADA");
        Cenarios.exigirOk(e3.v().cancelar(s3, "OUTRO"), "limpar");
        // APROVAR
        Cenarios.Equipe e4 = C.equipe();
        UUID s4 = C.confirmada(e4, al);
        DB.recuarSolicitacao(s4, Cenarios.ESPERA);
        vincular(e4.aprovador(), terceiro);
        assertThat(e4.a().aprovar(s4).motivo()).isEqualTo("VINCULO_IMPEDITIVO");
        assertThat(C.estado(s4)).isEqualTo("PRE_APROVADA");
        Cenarios.exigirOk(e4.v().cancelar(s4, "OUTRO"), "limpar");
        // EXECUTAR (executor) - recusa do ato
        Cenarios.Equipe e5 = C.equipe();
        UUID s5 = C.aprovada(e5, al);
        OpsBanco.Operador exe2 = DB.operador("exet", "EXECUTAR");
        vincular(exe2, terceiro);
        assertThat(DB.cli(exe2).executar(s5).motivo()).isEqualTo("VINCULO_IMPEDITIVO");
        assertThat(C.estado(s5)).isEqualTo("APROVADA");
        // reconferencia na execucao: o verificador (participante) se vincula ao terceiro contato depois de agir
        vincular(e5.verificador(), terceiro);
        Resultado r = e5.e().executar(s5);
        assertThat(r.motivo()).isEqualTo("VINCULO_IMPEDITIVO");
        assertThat(C.estado(s5)).isEqualTo("RECUSADA");
        assertThat(C.resets(s5)).isZero();
    }

    private static void vincular(OpsBanco.Operador op, UUID contato) {
        assertThat(C.vincularContato(op, C.provaDeContato(contato))).isEqualTo("OK");
    }

    // ------------------------------------------------------------------ item 3

    private static long eventos(UUID s) {
        return DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ?", s);
    }

    private static void recusa(UUID s, Resultado r, String ato, String motivo, String estado) {
        assertThat(r.classe()).as("%s %s", ato, motivo).isEqualTo(Classe.RECUSA);
        assertThat(r.motivo()).as(ato).isEqualTo(motivo);
        assertThat(C.estado(s)).as("%s %s -> estado", ato, motivo).isEqualTo(estado);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND act = ? AND result = 'RECUSA' AND reason = ?",
                s, ato, motivo)).as("%s %s registrada", ato, motivo).isEqualTo(1);
    }

    /** Matriz de recusas: cada linha -> estado resultante e evento. Recusa do ato mantem o estado; terminal encerra. */
    @Test
    void item3MatrizDeRecusas() {
        Cenarios.Equipe eq = C.equipe();
        // VERIFICAR: ato (DECLARACAO_DIVERGENTE) x terminal (EVIDENCIA_NEGATIVA)
        Cenarios.Alvo a1 = C.alvo();
        DB.outroAdmin(a1.org());
        UUID s1 = C.aberta(eq, a1);
        recusa(s1, eq.v().verificar(s1, true, a1.segundoContato(), true, ExecutorAtos.DemaisAdmins.NENHUM), "VERIFICAR", "DECLARACAO_DIVERGENTE", "SOLICITADA");
        recusa(s1, eq.a().preAprovar(s1), "PRE_APROVAR", "ESTADO_INVALIDO", "SOLICITADA");
        recusa(s1, eq.v().reenviarAviso(s1, a1.titular()), "REENVIAR_AVISO", "ESTADO_INVALIDO", "SOLICITADA");
        recusa(s1, eq.v().cancelar(s1, "MOTIVO_QUE_NAO_EXISTE"), "CANCELAR", "PARAMETRO_INVALIDO", "SOLICITADA");
        recusa(s1, eq.v().verificar(s1, false, a1.segundoContato(), true, ExecutorAtos.DemaisAdmins.TODOS_SEM_FATOR_E_CODIGOS_UTILIZAVEIS),
                "VERIFICAR", "EVIDENCIA_NEGATIVA", "RECUSADA");
        // APROVAR: ato (AVISO_NAO_CONFIRMADO) x terminal (FATOR_DIVERGENTE)
        Cenarios.Alvo a2 = C.alvo();
        UUID s2 = C.preAprovada(eq, a2);
        recusa(s2, eq.a().aprovar(s2), "APROVAR", "AVISO_NAO_CONFIRMADO", "PRE_APROVADA");
        Cenarios.Tokens t = C.emitirAvisos(a2.org().id()).get(0);
        assertThat(C.confirmar(a2.org().id(), t.confirmar())).isTrue();
        recusa(s2, eq.a().aprovar(s2), "APROVAR", "ESPERA_NAO_CUMPRIDA", "PRE_APROVADA");
        DB.recuarSolicitacao(s2, Cenarios.ESPERA);
        DB.sql("UPDATE user_mfa_factors SET disabled_at = now() WHERE user_id = '" + a2.titular() + "' AND disabled_at IS NULL");
        DB.fatorConfirmado(a2.org().id(), a2.titular());
        recusa(s2, eq.a().aprovar(s2), "APROVAR", "FATOR_DIVERGENTE", "RECUSADA");
        // EXECUTAR: ato (JA_EXECUTADA) ; REENVIAR terminal por prazo
        Cenarios.Alvo a3 = C.alvo();
        UUID s3 = C.aprovada(eq, a3);
        Cenarios.exigirOk(eq.e().executar(s3), "executar");
        recusa(s3, eq.e().executar(s3), "EXECUTAR", "JA_EXECUTADA", "EXECUTADA");
        Cenarios.Alvo a4 = C.alvo();
        UUID s4 = C.preAprovada(eq, a4);
        DB.recuarSolicitacao(s4, Duration.ofHours(72));
        recusa(s4, eq.v().reenviarAviso(s4, a4.titular()), "REENVIAR_AVISO", "PRAZO_ENCERRADO", "EXPIRADA");
    }

    /** Mutacao de encerrar (MutacoesIT): detector - recusa terminal precisa encerrar a solicitacao. */
    static void detectorEncerrar() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aberta(eq, al);
        Resultado r = eq.v().verificar(s, false, al.segundoContato(), true, C.declaracao(s));
        assertThat(r.motivo()).isEqualTo("EVIDENCIA_NEGATIVA");
        assertThat(C.estado(s)).as("recusa terminal encerra").isEqualTo("RECUSADA");
    }

    /** Erros tecnicos nao viram recusa: guarda de isolamento (25N01) e falha injetada -> estado inalterado, nenhum evento de recusa. */
    @Test
    void item3ErrosTecnicosNaoViramRecusa() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aberta(eq, al);
        long antes = eventos(s);
        try (Credencial cred = DB.credencial(eq.verificador()); Connection c = DB.conectar(cred)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT r_resultado FROM public.ops_mfa_verify(?, true, ?, true, ?)")) {
                ps.setObject(1, s);
                ps.setObject(2, al.segundoContato());
                ps.setString(3, "NENHUM");
                ps.executeQuery();
                throw new AssertionError("a guarda de isolamento nao recusou");
            } catch (SQLException e) {
                assertThat(e.getSQLState()).isEqualTo("25N01");
            }
            c.rollback();
        }
        assertThat(C.estado(s)).isEqualTo("SOLICITADA");
        assertThat(eventos(s)).as("nenhum evento").isEqualTo(antes);

        // falha injetada no meio do ato (restricao violada por trigger de teste) -> rollback; a CLI registra FALHA, nunca RECUSA
        DB.sql("CREATE OR REPLACE FUNCTION public.teste_falha_verificar() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $$ "
                + "BEGIN IF NEW.request_id = '" + s + "' AND NEW.state = 2 THEN RAISE EXCEPTION 'falha tecnica injetada (teste)' USING ERRCODE = '23514'; "
                + "END IF; RETURN NEW; END $$",
            "REVOKE ALL ON FUNCTION public.teste_falha_verificar() FROM PUBLIC",
            "CREATE TRIGGER teste_falha_verificar BEFORE UPDATE ON ops.ops_mfa_recovery_requests FOR EACH ROW EXECUTE FUNCTION public.teste_falha_verificar()");
        Resultado r;
        try {
            r = C.verificar(eq.v(), s, al.segundoContato());
        } finally {
            DB.sql("DROP TRIGGER IF EXISTS teste_falha_verificar ON ops.ops_mfa_recovery_requests", "DROP FUNCTION IF EXISTS public.teste_falha_verificar()");
        }
        assertThat(r.classe()).isEqualTo(Classe.FALHA_CONFIRMADA);
        assertThat(C.estado(s)).isEqualTo("SOLICITADA");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND result = 'RECUSA'", s)).isZero();
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND act = 'FALHA'", s)).isEqualTo(1);
        Cenarios.exigirOk(C.verificar(eq.v(), s, al.segundoContato()), "depois de remover a falha");
    }
}
