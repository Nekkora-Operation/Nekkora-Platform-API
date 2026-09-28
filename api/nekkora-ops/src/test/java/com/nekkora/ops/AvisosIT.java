package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Grupo Avisos (contrato 6.1 e 10), pela identidade de servico da Platform: tokens por destinatario e finalidade, uso
 * unico, validade propria (CONFIRMAR ate min(T_total, emissao + 24 h); CONTESTAR ate T_total enquanto nao terminal),
 * primeira confirmacao imutavel, nenhum CONFIRMAR depois dela, reenvio invalida o anterior. Toda recusa devolve a
 * mesma resposta (false), sem efeito.
 */
class AvisosIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private record Preparado(Cenarios.Equipe eq, Cenarios.Alvo al, UUID s, UUID admin, Cenarios.Tokens titular, Cenarios.Tokens outro) {
    }

    private Preparado preparado() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID admin = DB.outroAdmin(al.org());
        UUID s = C.preAprovada(eq, al);
        List<Cenarios.Tokens> t = C.emitirAvisos(al.org().id());
        return new Preparado(eq, al, s, admin, t.stream().filter(x -> x.destinatario().equals(al.titular())).findFirst().orElseThrow(),
                t.stream().filter(x -> x.destinatario().equals(admin)).findFirst().orElseThrow());
    }

    private boolean confirmada(UUID s) {
        return DB.valor(Boolean.class, "SELECT notice_confirmed_at IS NOT NULL FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", s);
    }

    @Test
    void tokenDeOutraFinalidadeDeOutroDestinatarioOuDesconhecidoNaoConfirma() {
        Preparado p = preparado();
        UUID org = p.al().org().id();
        assertThat(C.confirmar(org, p.titular().contestar())).as("CONTESTAR do titular como confirmacao").isFalse();
        assertThat(C.confirmar(org, p.outro().contestar())).as("token de outro destinatario").isFalse();
        assertThat(C.confirmar(org, OpsBanco.tokenBruto())).as("desconhecido").isFalse();
        assertThat(C.confirmar(org, "nao-e-token")).as("malformado").isFalse();
        OpsBanco.Org outraOrg = DB.organizacao();
        assertThat(C.usarToken("ops_notice_confirm", outraOrg.id(), outraOrg.id(), p.titular().confirmar())).as("outra organizacao").isFalse();
        assertThat(C.contestar(org, p.titular().confirmar())).as("CONFIRMAR usado para contestar").isFalse();
        assertThat(confirmada(p.s())).isFalse();
        assertThat(C.estado(p.s())).isEqualTo("PRE_APROVADA");
        // tentativas com token conhecido ficam auditadas (destinatario e finalidade, nunca o token)
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND act = 'AVISO_INVALIDO'", p.s()))
                .isEqualTo(3);
        // e o token certo continua valido
        assertThat(C.confirmar(org, p.titular().confirmar())).isTrue();
        assertThat(C.confirmar(org, p.titular().confirmar())).as("uso unico").isFalse();
    }

    @Test
    void confirmacaoVencidaNaoContaEAEsperaNaoComeca() {
        Preparado p = preparado();
        DB.recuarSolicitacao(p.s(), Duration.ofHours(24));
        assertThat(C.confirmar(p.al().org().id(), p.titular().confirmar())).isFalse();
        assertThat(confirmada(p.s())).isFalse();
        assertThat(p.eq().a().aprovar(p.s()).motivo()).isEqualTo("AVISO_NAO_CONFIRMADO");
        // a contestacao continua valida (ate T_total)
        assertThat(C.contestar(p.al().org().id(), p.outro().contestar())).isTrue();
        assertThat(C.estado(p.s())).isEqualTo("CANCELADA");
        assertThat(C.encerramento(p.s())).isEqualTo("CONTESTADA");
    }

    /**
     * Contrato 6 e 6.1: CONFIRMAR usado dentro da propria validade, mas com confirmacao + 24 h >= T_total. A confirmacao
     * e aceita e nao muda T_total; a aprovacao final e recusada ate T_total (espera nao cumprida) e, depois dele, a
     * solicitacao termina EXPIRADA; nenhum reset.
     */
    @Test
    void confirmacaoValidaTardiaDemaisNaoPermiteAprovarEExpiraSemReset() {
        Preparado p = preparado();
        UUID org = p.al().org().id();
        // aberta ha 50 h, pre-aprovada e avisada agora: T_total = agora + 22 h; CONFIRMAR valido ate min(T_total, agora + 24 h)
        DB.recuarAbertura(p.s(), Duration.ofHours(50));
        String acessoAntes = C.acesso(org, p.al().titular());
        String identidadeAntes = C.identidade(org, p.al().titular());
        java.time.OffsetDateTime tTotal = DB.valor(java.time.OffsetDateTime.class,
                "SELECT t_total FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", p.s());

        assertThat(C.confirmar(org, p.titular().confirmar())).as("token CONFIRMAR ainda valido e aceito").isTrue();
        assertThat(confirmada(p.s())).isTrue();
        assertThat(DB.valor(java.time.OffsetDateTime.class, "SELECT t_total FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", p.s()))
                .as("a confirmacao nao altera T_total").isEqualTo(tTotal);
        assertThat(DB.valor(Boolean.class, "SELECT t_total = opened_at + interval '72 hours' FROM ops.ops_mfa_recovery_requests WHERE request_id = ?",
                p.s())).as("T_total continua abertura + 72 h").isTrue();
        assertThat(DB.valor(Boolean.class,
                "SELECT notice_confirmed_at + interval '24 hours' >= t_total FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", p.s()))
                .as("pre-condicao: confirmacao + 24 h >= T_total").isTrue();

        // logo depois da confirmacao e a 1 h de T_total: aprovacao final recusada, sem encerrar
        assertThat(p.eq().a().aprovar(p.s()).motivo()).isEqualTo("ESPERA_NAO_CUMPRIDA");
        DB.recuarSolicitacao(p.s(), Duration.ofHours(21));
        assertThat(p.eq().a().aprovar(p.s()).motivo()).as("a 1 h de T_total").isEqualTo("ESPERA_NAO_CUMPRIDA");
        assertThat(C.estado(p.s())).isEqualTo("PRE_APROVADA");

        // T_total passou: a aprovacao encerra a solicitacao como EXPIRADA, e a execucao e recusada
        DB.recuarSolicitacao(p.s(), Duration.ofHours(2));
        assertThat(p.eq().a().aprovar(p.s()).motivo()).isEqualTo("PRAZO_ENCERRADO");
        assertThat(C.estado(p.s())).isEqualTo("EXPIRADA");
        assertThat(C.encerramento(p.s())).isEqualTo("PRAZO_ENCERRADO");
        assertThat(p.eq().e().executar(p.s()).motivo()).isEqualTo("ESTADO_INVALIDO");
        assertThat(C.estado(p.s())).isEqualTo("EXPIRADA");

        // nenhum reset: nenhum evento tipo 4, fator/codigos/desafios/sessoes e identidade da conta intactos
        assertThat(C.resets(p.s())).isZero();
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM public.mfa_events WHERE organization_id = ? AND user_id = ? AND event_type = 4",
                org, p.al().titular())).isZero();
        assertThat(C.acesso(org, p.al().titular())).isEqualTo(acessoAntes);
        assertThat(C.identidade(org, p.al().titular())).isEqualTo(identidadeAntes);
        assertThat(DB.valor(Boolean.class, "SELECT disabled_at IS NULL FROM public.user_mfa_factors WHERE factor_id = ?", p.al().fator()))
                .as("fator aprovado continua ativo").isTrue();
    }

    /** Contrato 10: aviso as 10h, confirmado as 18h, contestacao as 12h do dia seguinte - aceita e cancela. */
    @Test
    void confirmacaoTardiaEContestacaoDentroDaEsperaCancela() {
        Preparado p = preparado();
        UUID org = p.al().org().id();
        DB.recuarSolicitacao(p.s(), Duration.ofHours(8));
        assertThat(C.confirmar(org, p.titular().confirmar())).isTrue();
        DB.recuarSolicitacao(p.s(), Duration.ofHours(18));
        // 26 h depois da emissao (alem de emissao + 24 h), antes do fim da espera
        assertThat(C.contestar(org, p.outro().contestar())).isTrue();
        assertThat(C.estado(p.s())).isEqualTo("CANCELADA");
        assertThat(C.encerramento(p.s())).isEqualTo("CONTESTADA");
        DB.recuarSolicitacao(p.s(), Duration.ofHours(7));
        assertThat(p.eq().a().aprovar(p.s()).motivo()).isEqualTo("ESTADO_INVALIDO");
        assertThat(p.eq().e().executar(p.s()).motivo()).isEqualTo("ESTADO_INVALIDO");
        assertThat(C.resets(p.s())).isZero();
        assertThat(C.contestar(org, p.titular().contestar())).as("ja terminal").isFalse();
    }

    @Test
    void primeiraConfirmacaoFixaOInicioDaEsperaENaoHaConfirmarDepois() {
        Preparado p = preparado();
        UUID org = p.al().org().id();
        assertThat(C.confirmar(org, p.titular().confirmar())).isTrue();
        String instante = DB.valor(String.class, "SELECT notice_confirmed_at::text FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", p.s());
        Cenarios.exigirOk(p.eq().v().reenviarAviso(p.s()), "reenviar");
        List<Cenarios.Tokens> novos = C.emitirAvisos(org);
        assertThat(novos).hasSize(1);
        assertThat(novos.get(0).temConfirmar()).as("nenhum CONFIRMAR depois da confirmacao").isFalse();
        assertThat(novos.get(0).temContestar()).isTrue();
        assertThat(C.contestar(org, p.titular().contestar())).as("CONTESTAR anterior do titular invalidado pelo reenvio").isFalse();
        assertThat(C.confirmar(org, p.titular().confirmar())).isFalse();
        assertThat(DB.valor(String.class, "SELECT notice_confirmed_at::text FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", p.s()))
                .isEqualTo(instante);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notice_tokens WHERE request_id = ? AND purpose = 1", p.s())).isEqualTo(1);
        // o CONTESTAR reemitido vale ate T_total (mesma validade)
        assertThat(DB.valor(Boolean.class, "SELECT bool_and(t.valid_until = r.t_total) FROM ops.ops_mfa_notice_tokens t "
                + "JOIN ops.ops_mfa_recovery_requests r USING (request_id) WHERE t.request_id = ? AND t.purpose = 2", p.s())).isTrue();
    }

    @Test
    void reenvioAntesDaConfirmacaoInvalidaOTokenAnterior() {
        Preparado p = preparado();
        UUID org = p.al().org().id();
        Cenarios.exigirOk(p.eq().v().reenviarAviso(p.s()), "reenviar");
        Cenarios.Tokens novo = C.emitirAvisos(org).get(0);
        assertThat(novo.temConfirmar()).isTrue();
        assertThat(C.confirmar(org, p.titular().confirmar())).as("CONFIRMAR anterior").isFalse();
        assertThat(C.confirmar(org, novo.confirmar())).isTrue();
        assertThat(DB.valor(String.class, "SELECT reason FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND act = 'AVISO_INVALIDO'", p.s()))
                .isEqualTo("TOKEN_INVALIDADO");
    }

    @Test
    void contestacaoDepoisDoPrazoTotalNaoTemEfeitoEAvisoDeSolicitacaoEncerradaEDescartado() {
        Preparado p = preparado();
        DB.recuarSolicitacao(p.s(), Duration.ofHours(72));
        assertThat(C.contestar(p.al().org().id(), p.outro().contestar())).isFalse();
        assertThat(C.estado(p.s())).isEqualTo("PRE_APROVADA");

        Preparado q = preparado();
        Cenarios.exigirOk(q.eq().v().reenviarAviso(q.s()), "reenviar");
        Cenarios.exigirOk(q.eq().v().cancelar(q.s(), "SOLICITANTE_DESISTIU"), "cancelar");
        assertThat(C.emitirAvisos(q.al().org().id())).as("aviso de solicitacao terminal nao e emitido").isEmpty();
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notices WHERE request_id = ? AND discarded", q.s())).isEqualTo(1);
    }

    @Test
    void bancoGuardaSoOHashDosTokens() {
        Preparado p = preparado();
        for (String bruto : List.of(p.titular().confirmar(), p.titular().contestar(), p.outro().contestar())) {
            assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notice_tokens WHERE token_hash = ?", OpsBanco.sha256(bruto))).isEqualTo(1);
            assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_notice_tokens t WHERE t::text LIKE ?", "%" + bruto + "%")).isZero();
        }
    }
}
