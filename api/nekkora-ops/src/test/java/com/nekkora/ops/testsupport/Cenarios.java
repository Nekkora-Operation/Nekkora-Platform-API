package com.nekkora.ops.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.cli.ExecutorAtos.Resultado;

/**
 * Cenarios reutilizaveis: equipe de operadores (papeis reais por certificado), alvo elegivel (cenario C) e conducao
 * de uma solicitacao ate cada estado pelos caminhos reais. Avisos pela identidade de servico da Platform
 * ({@code nekkora_platform}, com o contexto de requisicao), como a Platform faz.
 */
public final class Cenarios {

    public static final Duration ESPERA = Duration.ofHours(24).plusMinutes(1);

    private final OpsBanco db;

    public Cenarios(OpsBanco db) {
        this.db = db;
    }

    public record Equipe(OpsBanco.Operador verificador, OpsBanco.Operador aprovador, OpsBanco.Operador executor,
                         ExecutorAtos v, ExecutorAtos a, ExecutorAtos e) {
    }

    /** Alvo: o ORG_ADMIN inicial da organizacao, com contato autorizado proprio e um segundo contato, ambos elegiveis. */
    public record Alvo(OpsBanco.Org org, UUID titular, UUID fator, UUID contatoTitular, UUID segundoContato) {
    }

    /** Tokens de uma tentativa de aviso emitida (item 10: {@code aviso} = tentativa). */
    public record Tokens(UUID destinatario, String confirmar, String contestar, boolean temConfirmar, boolean temContestar, UUID aviso) {
    }

    public Equipe equipe() {
        OpsBanco.Operador v = db.operador("ver", "VERIFICAR", "CONSULTAR", "CANCELAR");
        OpsBanco.Operador a = db.operador("apr", "APROVAR", "CONSULTAR");
        OpsBanco.Operador e = db.operador("exe", "EXECUTAR", "CONSULTAR");
        return new Equipe(v, a, e, db.cli(v), db.cli(a), db.cli(e));
    }

    public Alvo alvo() {
        OpsBanco.Org o = db.organizacao();
        db.estadoDeAcesso(o.id(), o.admin(), o.adminFator());
        UUID c1 = db.contatoElegivel(o, o.admin());
        UUID c2 = db.contatoElegivel(o, null);
        return new Alvo(o, o.admin(), o.adminFator(), c1, c2);
    }

    /** Abertura pelo caminho legitimo garantido: retorno pelo canal registrado do contato do titular (item 4). */
    public UUID aberta(Equipe eq, Alvo al) {
        Resultado r = abrirPorRetorno(eq.v(), al.org().id(), al.titular(), al.contatoTitular());
        assertThat(r.classe()).as("abrir: %s", r).isEqualTo(Classe.OK);
        // o UUID vem do proprio resultado da CLI: nenhuma consulta administrativa para continuar
        assertThat(r.solicitacao()).as("abrir devolve a solicitacao").isNotNull();
        return r.solicitacao();
    }

    /** Retorno registrado pelo verificador e abertura com ele. Se o registro do retorno for recusado, devolve a recusa. */
    public Resultado abrirPorRetorno(ExecutorAtos v, UUID org, UUID alvo, UUID contato) {
        ExecutorAtos.RetornoRegistrado rr = v.registrarRetorno(contato);
        if (rr.classe() != Classe.OK) {
            return new Resultado(null, rr.classe(), rr.motivo(), null, ExecutorAtos.Origem.BANCO, rr.sqlstate(), false);
        }
        return v.abrir(org, alvo, contato, new ExecutorAtos.Retorno(rr.retorno()));
    }

    /** Abertura por ENTRADA com origem comprovada nova (pseudonimo aleatorio): exercita as recusas da propria abertura. */
    public Resultado abrirPorEntrada(OpsBanco.Operador op, ExecutorAtos v, UUID org, UUID alvo, UUID contato) {
        return v.abrir(org, alvo, contato, new ExecutorAtos.Entrada(entradaComprovada(op, OpsBanco.hex(32))));
    }

    public Resultado abrirPorEntrada(Equipe eq, UUID org, UUID alvo, UUID contato) {
        return abrirPorEntrada(eq.verificador(), eq.v(), org, alvo, contato);
    }

    /** Abertura por ENTRADA com uma origem comprovada escolhida (mesmo pseudonimo = mesma origem para a cota). */
    public Resultado abrirPorEntradaDaOrigem(Equipe eq, UUID org, UUID alvo, UUID contato, String pseudonimo) {
        return eq.v().abrir(org, alvo, contato, new ExecutorAtos.Entrada(entradaComprovada(eq.verificador(), pseudonimo)));
    }

    /** Registro de entrada pelo servico de atendimento: origem comprovada por desafio de e-mail, pseudonimo informado. */
    public UUID entradaComprovada(OpsBanco.Operador op, String pseudonimo) {
        List<Object> r = registrarEntrada(2, 1, pseudonimo, null, op.papel());
        assertThat(r.get(0)).as("registro de entrada: %s", r).isEqualTo("OK");
        return (UUID) r.get(2);
    }

    /**
     * Chama {@code ops_intake_register} como {@code nekkora_ops_intake}, usando a versao vigente (e, na transicao, a
     * anterior com {@code pseudonimoAnterior}). Devolve [resultado, motivo, entrada].
     */
    public List<Object> registrarEntrada(int canal, int mecanismo, String pseudonimo, String pseudonimoAnterior, String papelOperador) {
        Integer vigente = db.valor(Integer.class, "SELECT current_version FROM ops.ops_intake_key_state");
        return registrarEntradaVersao(canal, mecanismo, mecanismo == 3 ? null : vigente, pseudonimo,
                pseudonimoAnterior == null ? null : vigente - 1, pseudonimoAnterior, papelOperador);
    }

    public List<Object> registrarEntradaVersao(int canal, int mecanismo, Integer versao, String pseudonimo, Integer versaoAnterior,
                                               String pseudonimoAnterior, String papelOperador) {
        try (Connection c = db.comoServico("nekkora_ops_intake");
             PreparedStatement ps = c.prepareStatement("SELECT r_resultado, r_motivo, r_entrada FROM public.ops_intake_register(?::smallint, ?::smallint, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, canal);
            ps.setInt(2, mecanismo);
            ps.setObject(3, versao);
            ps.setString(4, pseudonimo);
            ps.setObject(5, versaoAnterior);
            ps.setString(6, pseudonimoAnterior);
            ps.setString(7, papelOperador);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                List<Object> out = new ArrayList<>();
                out.add(rs.getString(1));
                out.add(rs.getString(2));
                out.add(rs.getObject(3, UUID.class));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("registro de entrada falhou (sqlstate " + e.getSQLState() + ")", e);
        }
    }

    /** Declaracao dos demais ORG_ADMIN coerente com a organizacao da solicitacao (item 5). */
    public ExecutorAtos.DemaisAdmins declaracao(UUID solicitacao) {
        Long n = db.valor(Long.class, "SELECT count(*) FROM ops.demais_admins((SELECT organization_id FROM ops.ops_mfa_recovery_requests "
                + "WHERE request_id = ?), (SELECT target_user_id FROM ops.ops_mfa_recovery_requests WHERE request_id = ?))", solicitacao, solicitacao);
        return n == 0 ? ExecutorAtos.DemaisAdmins.NENHUM : ExecutorAtos.DemaisAdmins.TODOS_SEM_FATOR_E_CODIGOS_UTILIZAVEIS;
    }

    public Resultado verificar(ExecutorAtos v, UUID s, UUID segundoContato) {
        return v.verificar(s, true, segundoContato, true, declaracao(s));
    }

    public UUID verificada(Equipe eq, Alvo al) {
        UUID s = aberta(eq, al);
        exigirOk(verificar(eq.v(), s, al.segundoContato()), "verificar");
        return s;
    }

    public UUID preAprovada(Equipe eq, Alvo al) {
        UUID s = verificada(eq, al);
        exigirOk(eq.a().preAprovar(s), "pre-aprovar");
        return s;
    }

    /** Pre-aprovada, avisos emitidos e confirmacao do titular (instante = agora). */
    public UUID confirmada(Equipe eq, Alvo al) {
        UUID s = preAprovada(eq, al);
        Tokens t = emitirAvisos(al.org().id()).stream().filter(x -> x.destinatario().equals(al.titular()) && x.temConfirmar()).findFirst().orElseThrow();
        assertThat(confirmar(al.org().id(), t.confirmar())).as("confirmacao do titular").isTrue();
        return s;
    }

    /** Confirmada, espera de 24 h cumprida (fixture temporal) e aprovacao final. */
    public UUID aprovada(Equipe eq, Alvo al) {
        UUID s = confirmada(eq, al);
        db.recuarSolicitacao(s, ESPERA);
        exigirOk(eq.a().aprovar(s), "aprovar");
        return s;
    }

    public static void exigirOk(Resultado r, String ato) {
        assertThat(r.classe()).as("%s: %s", ato, r).isEqualTo(Classe.OK);
    }

    // ------------------------------------------------------------------ Platform (nekkora_platform)

    /**
     * Emite e entrega os avisos pendentes da organizacao como a Platform, pelo protocolo do item 10: emissao (tokens so
     * aqui, hash no banco) -> reivindicacao ENTREGANDO -> transporte simulado que ACEITA. Devolve os tokens emitidos.
     */
    public List<Tokens> emitirAvisos(UUID org) {
        return emitirAvisos(org, (short) 4);
    }

    /** Idem, com o desfecho do transporte simulado ({@code 3} ENFILEIRADA, {@code 4} ACEITA, {@code 5} FALHOU, {@code 6} DESCONHECIDO; 0 = nao entregar). */
    public List<Tokens> emitirAvisos(UUID org, short desfecho) {
        List<Tokens> out = new ArrayList<>();
        try (Connection c = db.comoServico("nekkora_platform")) {
            List<UUID> pendentes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT r_aviso, r_organizacao FROM public.ops_notice_pending(100)");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (org.equals(rs.getObject(2, UUID.class))) {
                        pendentes.add(rs.getObject(1, UUID.class));
                    }
                }
            }
            for (UUID aviso : pendentes) {
                String conf = OpsBanco.tokenBruto();
                String cont = OpsBanco.tokenBruto();
                boolean emitido;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT r_emitido, r_confirmar, r_contestar, r_destinatario FROM public.ops_notice_issue(?, ?, ?)")) {
                    ps.setObject(1, aviso);
                    ps.setString(2, OpsBanco.sha256(conf));
                    ps.setString(3, OpsBanco.sha256(cont));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        emitido = rs.getBoolean(1);
                        if (emitido) {
                            out.add(new Tokens(rs.getObject(4, UUID.class), rs.getBoolean(2) ? conf : null, rs.getBoolean(3) ? cont : null,
                                    rs.getBoolean(2), rs.getBoolean(3), aviso));
                        }
                    }
                }
                if (emitido && desfecho != 0) {
                    reivindicar(aviso, UUID.randomUUID());
                    resultado(aviso, desfecho, null);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("emissao de avisos falhou (sqlstate " + e.getSQLState() + ")", e);
        }
        return out;
    }

    /** {@code ops_notice_claim} como a Platform: [estado, minha]. */
    public List<Object> reivindicar(UUID aviso, UUID claim) {
        try (Connection c = db.comoServico("nekkora_platform");
             PreparedStatement ps = c.prepareStatement("SELECT r_estado, r_minha FROM public.ops_notice_claim(?, ?)")) {
            ps.setObject(1, aviso);
            ps.setObject(2, claim);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                List<Object> out = new ArrayList<>();
                out.add(rs.getObject(1, Short.class));
                out.add(rs.getBoolean(2));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("reivindicacao falhou (sqlstate " + e.getSQLState() + ")", e);
        }
    }

    /** {@code ops_notice_delivery_result} como a Platform: devolve o resultado (APLICADO, REPETIDO, ...). */
    public String resultado(UUID aviso, short estado, String categoria) {
        try (Connection c = db.comoServico("nekkora_platform");
             PreparedStatement ps = c.prepareStatement("SELECT r_resultado FROM public.ops_notice_delivery_result(?, ?::smallint, ?)")) {
            ps.setObject(1, aviso);
            ps.setShort(2, estado);
            ps.setString(3, categoria);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("resultado de entrega falhou (sqlstate " + e.getSQLState() + ")", e);
        }
    }

    /** Estado da tentativa (leitura de evidencia, superusuario). */
    public short estadoDoAviso(UUID aviso) {
        return db.valor(Short.class, "SELECT state FROM ops.ops_mfa_notices WHERE notice_id = ?", aviso);
    }

    public boolean confirmar(UUID org, String bruto) {
        return usarToken("ops_notice_confirm", org, org, bruto);
    }

    public boolean contestar(UUID org, String bruto) {
        return usarToken("ops_notice_contest", org, org, bruto);
    }

    /** Uso de token como a Platform: aceito so com a categoria interna ACEITO (a resposta publica e a mesma em toda recusa). */
    public boolean usarToken(String funcao, UUID ctx, UUID org, String bruto) {
        return "ACEITO".equals(categoriaDoToken(funcao, ctx, org, bruto));
    }

    /** Categoria interna devolvida a Platform (item 6): ACEITO | RECUSADO_REGISTRADO | NAO_ATRIBUIVEL. */
    public String categoriaDoToken(String funcao, UUID ctx, UUID org, String bruto) {
        try (Connection c = db.comoServico("nekkora_platform")) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('nekkora.organization_id', ?, true)")) {
                ps.setString(1, ctx.toString());
                ps.execute();
            }
            String r;
            try (PreparedStatement ps = c.prepareStatement("SELECT public." + funcao + "(?, ?)")) {
                ps.setObject(1, org);
                ps.setString(2, bruto == null ? null : OpsBanco.sha256(bruto));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    r = rs.getString(1);
                }
            }
            c.commit();
            return r;
        } catch (SQLException e) {
            throw new IllegalStateException("uso de token falhou (sqlstate " + e.getSQLState() + ")", e);
        }
    }

    /** Prova de controle de conta emitida pela Platform (sessao do titular da conta); devolve o codigo bruto. */
    public String provaDeConta(UUID org, UUID usuario) {
        String bruto = OpsBanco.tokenBruto();
        try (Connection c = db.comoServico("nekkora_platform")) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('nekkora.organization_id', ?, true)")) {
                ps.setString(1, org.toString());
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT public.ops_account_proof_issue(?, ?, ?)")) {
                ps.setObject(1, org);
                ps.setObject(2, usuario);
                ps.setString(3, OpsBanco.sha256(bruto));
                ps.execute();
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("prova de conta falhou (sqlstate " + e.getSQLState() + ")", e);
        }
        return bruto;
    }

    /** O operador vincula a conta que controla (a CLI envia so o hash do codigo). */
    public String vincularConta(OpsBanco.Operador o, String bruto) {
        return comoOperador(o, "SELECT r_resultado FROM public.ops_link_platform_account(?)", OpsBanco.sha256(bruto));
    }

    /** Prova de contato emitida por um operador CADASTRAR (bootstrap); devolve o codigo bruto entregue pelo canal. */
    public String provaDeContato(UUID contato) {
        String bruto = OpsBanco.tokenBruto();
        String r = db.cadastro("SELECT r_resultado FROM public.ops_contact_link_code_issue(?, ?)", String.class, contato, OpsBanco.sha256(bruto));
        if (!"OK".equals(r)) {
            throw new IllegalStateException("prova de contato recusada");
        }
        return bruto;
    }

    public String vincularContato(OpsBanco.Operador o, String bruto) {
        return comoOperador(o, "SELECT r_resultado FROM public.ops_link_contact(?)", OpsBanco.sha256(bruto));
    }

    public String comoOperador(OpsBanco.Operador o, String sql, Object... params) {
        try (var cred = db.credencial(o); Connection c = db.conectar(cred); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("ato do operador falhou (sqlstate " + e.getSQLState() + ")", e);
        }
    }

    // ------------------------------------------------------------------ leituras de evidencia (superusuario, declaradas)

    public String estado(UUID solicitacao) {
        return db.valor(String.class, "SELECT ops.estado_nome(state) FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", solicitacao);
    }

    public String encerramento(UUID solicitacao) {
        return db.valor(String.class, "SELECT close_reason FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", solicitacao);
    }

    public long resets(UUID solicitacao) {
        return db.valor(Long.class, "SELECT count(*) FROM public.mfa_events WHERE ops_request_id = ?", solicitacao);
    }

    /** Fotografia do acesso da conta: fator ativo, codigos validos, desafios ativos, sessoes ativas. */
    public String acesso(UUID org, UUID user) {
        String sql = ("SELECT concat_ws('|', "
                + "(SELECT count(*) FROM user_mfa_factors WHERE organization_id = ?1 AND user_id = ?2 AND disabled_at IS NULL), "
                + "(SELECT count(*) FROM user_mfa_recovery_codes WHERE organization_id = ?1 AND user_id = ?2 AND used_at IS NULL AND invalidated_at IS NULL), "
                + "(SELECT count(*) FROM auth_challenges WHERE organization_id = ?1 AND user_id = ?2 AND consumed_at IS NULL AND invalidated_at IS NULL), "
                + "(SELECT count(*) FROM user_sessions WHERE organization_id = ?1 AND user_id = ?2 AND revoked_at IS NULL))")
                .replace("?1", "'" + org + "'").replace("?2", "'" + user + "'");
        return db.valor(String.class, sql);
    }

    /** Senha, papeis, vinculos, status e e-mail da conta (nao podem mudar com a recuperacao). */
    public String identidade(UUID org, UUID user) {
        String o = "'" + org + "'";
        String u = "'" + user + "'";
        return db.valor(String.class, "SELECT concat_ws('|', "
                + "(SELECT password_hash || password_changed_at::text FROM user_credentials WHERE user_id = " + u + "), "
                + "(SELECT status || email || coalesce(email_verified_at::text, '') FROM users WHERE user_id = " + u + "), "
                + "(SELECT string_agg(status::text || membership_id::text, ',') FROM organization_memberships WHERE user_id = " + u + "), "
                + "(SELECT string_agg(a.assignment_id::text || a.status || coalesce(a.revoked_at::text, ''), ',' ORDER BY a.assignment_id) "
                + "   FROM organization_role_assignments a JOIN organization_memberships m USING (organization_id, membership_id) "
                + "   WHERE a.organization_id = " + o + " AND m.user_id = " + u + "), "
                + "(SELECT count(*) FROM client_memberships WHERE user_id = " + u + "), "
                + "(SELECT count(*) FROM client_workspace_grants WHERE organization_id = " + o + "))");
    }
}
