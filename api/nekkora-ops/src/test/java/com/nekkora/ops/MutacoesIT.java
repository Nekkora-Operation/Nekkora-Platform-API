package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.Credencial;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Contraprovas por mutacao (missao, item 5): cada detector passa com o codigo da 016, PRECISA falhar com a funcao
 * mutada (CREATE OR REPLACE como superusuario, so nesta base de teste) e volta a passar depois da restauracao, conferida
 * pela definicao identica. Mutacoes: autoria, validade por ato, contestacao durante a espera, atomicidade e guarda de
 * isolamento (REPEATABLE READ com snapshot anterior a revogacao).
 */
class MutacoesIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private static String definicao(String funcao) {
        return DB.valor(String.class, "SELECT pg_get_functiondef(?::regprocedure)", funcao);
    }

    /** Aplica as substituicoes (cada uma precisa ocorrer), roda o detector esperando falha e restaura. */
    private static void contraprova(Map<String, List<String[]>> mutacao, Runnable detector) {
        detector.run();
        Map<String, String> originais = new LinkedHashMap<>();
        for (var e : mutacao.entrySet()) {
            String def = definicao(e.getKey());
            originais.put(e.getKey(), def);
            String mutada = def;
            for (String[] troca : e.getValue()) {
                assertThat(mutada).as("trecho a mutar em %s", e.getKey()).contains(troca[0]);
                mutada = mutada.replace(troca[0], troca[1]);
            }
            DB.sql(mutada);
        }
        Throwable detectado = null;
        try {
            detector.run();
        } catch (AssertionError e) {
            detectado = e;
        } finally {
            for (var e : originais.entrySet()) {
                DB.sql(e.getValue());
                assertThat(definicao(e.getKey())).as("restauracao de %s", e.getKey()).isEqualTo(e.getValue());
            }
        }
        assertThat(detectado).as("a mutacao PRECISA ser detectada").isNotNull();
        System.out.println("[mutacao] " + String.join(",", mutacao.keySet()) + " detectada por: "
                + detectado.getMessage().strip().replaceAll("\\s+", " "));
        detector.run();
    }

    // ------------------------------------------------------------------ M1 autoria

    /** Detector: com a credencial de X (sem APROVAR), nenhum ajuste de sessao faz o ato valer como Y. */
    private static void detectorAutoria() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.verificada(eq, al);
        OpsBanco.Operador x = DB.operador("mx", "CONSULTAR");
        try (Credencial c = DB.credencial(x); Connection con = DB.conectar(c)) {
            try (PreparedStatement ps = con.prepareStatement("SELECT set_config('nekkora.ops_autor', ?, false)")) {
                ps.setString(1, eq.aprovador().papel());
                ps.execute();
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT r_resultado FROM public.ops_mfa_preapprove(?)")) {
                ps.setObject(1, s);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("RECUSA");
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(C.estado(s)).isEqualTo("VERIFICADA");
        assertThat(DB.valor(UUID.class, "SELECT operator_id FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND act = 'PRE_APROVAR'", s))
                .isEqualTo(x.id());
    }

    @Test
    void mutacaoDeAutoriaEDetectada() {
        String autor = "coalesce(nullif(current_setting('nekkora.ops_autor', true), ''), session_user::text)";
        contraprova(Map.of(
                "ops.operador_da_sessao()", List.<String[]>of(new String[] {"o.db_role = session_user::text", "o.db_role = " + autor}),
                "ops.autorizar(uuid,smallint,timestamp with time zone)", List.of(
                        new String[] {"ops.ssl_client_dn_field('commonName') IS DISTINCT FROM session_user::text", "false"},
                        new String[] {"o.db_role = session_user::text", "o.db_role = " + autor},
                        new String[] {" OR v_cert.operator_id <> p_operator", ""})),
                MutacoesIT::detectorAutoria);
    }

    // ------------------------------------------------------------------ M2 validade por ato

    private static void detectorValidadePorAto() {
        Cenarios.Equipe eq = C.equipe();
        UUID s = C.aberta(eq, C.alvo());
        try (Credencial c = DB.credencial(eq.verificador()); Connection con = DB.conectar(c)) {
            DB.vencerCertificado(c);
            try (PreparedStatement ps = con.prepareStatement("SELECT r_resultado, r_motivo FROM public.ops_mfa_status(?)")) {
                ps.setObject(1, s);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("RECUSA");
                    assertThat(rs.getString(2)).isEqualTo("CERTIFICADO_FORA_DA_VALIDADE");
                }
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void mutacaoDaValidadePorAtoEDetectada() {
        contraprova(Map.of("ops.autorizar(uuid,smallint,timestamp with time zone)", List.<String[]>of(new String[] {
                "IF NOT (v_cert.not_before <= p_agora AND p_agora < v_cert.not_after) THEN", "IF NOT (v_cert.not_before <= p_agora) THEN"})),
                MutacoesIT::detectorValidadePorAto);
    }

    // ------------------------------------------------------------------ M3 contestacao durante a espera

    private static void detectorContestacaoNaEspera() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID admin = DB.outroAdmin(al.org());
        UUID s = C.preAprovada(eq, al);
        List<Cenarios.Tokens> t = C.emitirAvisos(al.org().id());
        DB.recuarSolicitacao(s, Duration.ofHours(8));
        assertThat(C.confirmar(al.org().id(), t.stream().filter(Cenarios.Tokens::temConfirmar).findFirst().orElseThrow().confirmar())).isTrue();
        DB.recuarSolicitacao(s, Duration.ofHours(18));
        String contestar = t.stream().filter(x -> x.destinatario().equals(admin)).findFirst().orElseThrow().contestar();
        assertThat(C.contestar(al.org().id(), contestar)).isTrue();
        assertThat(C.estado(s)).isEqualTo("CANCELADA");
    }

    @Test
    void mutacaoDaValidadeDaContestacaoEDetectada() {
        contraprova(Map.of("public.ops_notice_issue(uuid,text,text)", List.<String[]>of(new String[] {
                "2, p_contest_hash, v_agora, r.t_total);", "2, p_contest_hash, v_agora, least(r.t_total, v_agora + interval '24 hours'));"})),
                MutacoesIT::detectorContestacaoNaEspera);
    }

    // ------------------------------------------------------------------ M4 atomicidade

    private static void detectorAtomicidade() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        String antes = C.acesso(al.org().id(), al.titular());
        DB.sql("CREATE OR REPLACE FUNCTION public.teste_falha_injetada() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $$ "
                + "BEGIN IF NEW.event_type = 4 AND NEW.ops_request_id = '" + s + "' THEN RAISE EXCEPTION 'falha tecnica injetada (teste)'; END IF; "
                + "RETURN NEW; END $$",
            "CREATE TRIGGER teste_falha_injetada BEFORE INSERT ON public.mfa_events FOR EACH ROW EXECUTE FUNCTION public.teste_falha_injetada()");
        ExecutorAtos.Resultado r;
        try {
            r = eq.e().executar(s);
        } finally {
            DB.sql("DROP TRIGGER IF EXISTS teste_falha_injetada ON public.mfa_events", "DROP FUNCTION IF EXISTS public.teste_falha_injetada()");
        }
        assertThat(C.acesso(al.org().id(), al.titular())).as("nenhum efeito parcial").isEqualTo(antes);
        assertThat(C.estado(s)).isEqualTo("APROVADA");
        assertThat(r.classe()).isEqualTo(Classe.FALHA_CONFIRMADA);
    }

    @Test
    void mutacaoDeAtomicidadeEDetectada() {
        contraprova(Map.of("public.ops_mfa_execute(uuid)", List.<String[]>of(new String[] {
                "    INSERT INTO public.mfa_events (organization_id, user_id, event_type, correlation_id, ops_request_id)\n"
                        + "    VALUES (v_org, v_target, 4, gen_random_uuid(), p_request);",
                "    BEGIN\n        INSERT INTO public.mfa_events (organization_id, user_id, event_type, correlation_id, ops_request_id)\n"
                        + "        VALUES (v_org, v_target, 4, gen_random_uuid(), p_request);\n    EXCEPTION WHEN OTHERS THEN NULL;\n    END;"})),
                MutacoesIT::detectorAtomicidade);
    }

    // ------------------------------------------------------------------ M5 isolamento

    /**
     * Detector: com o snapshot de REPEATABLE READ fixado ANTES da revogacao do proprio certificado, a execucao e
     * recusada pela guarda (25N01) e nada muda. Sem a guarda, as conferencias leem o snapshot antigo, o certificado
     * revogado autoriza e o reset acontece: a mutacao demonstra o achado.
     */
    private static void detectorIsolamento() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        try (Credencial c = DB.credencial(eq.executor()); Connection con = DB.conectar(c)) {
            IsolamentoIT.abrirComSnapshot(con, "REPEATABLE READ");
            IsolamentoIT.revogarCertificado(c);
            String estado;
            try {
                String[] r = IsolamentoIT.executar(con, s);
                con.commit();
                estado = "executou:" + r[0];
            } catch (java.sql.SQLException e) {
                con.rollback();
                estado = e.getSQLState();
            }
            assertThat(estado).as("execucao com certificado revogado depois do snapshot").isEqualTo(IsolamentoIT.ISOLAMENTO_RECUSADO);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(C.resets(s)).as("nenhum reset com credencial revogada").isZero();
    }

    @Test
    void mutacaoDaGuardaDeIsolamentoEDetectada() {
        contraprova(Map.of("public.ops_mfa_execute(uuid)", List.<String[]>of(new String[] {
                "    PERFORM ops.exigir_read_committed();\n", ""})),
                MutacoesIT::detectorIsolamento);
    }

    // ------------------------------------------------------------------ revisao 4 (itens 3, 4, 5 e 10)

    /** M6 (item 4): cota contada tambem pelo contato alegado. Detector: outra origem alegando o mesmo contato continua abrindo. */
    @Test
    void mutacaoDaCotaPorContatoEDetectada() {
        contraprova(Map.of("public.ops_mfa_open(uuid,uuid,uuid,uuid,uuid)", List.<String[]>of(new String[] {
                "AND ((r.origin_version = v_ent.origin_version AND r.origin_pseudonym = v_ent.origin_pseudonym)",
                "AND (r.requester_contact_id = p_requester_contact OR (r.origin_version = v_ent.origin_version AND r.origin_pseudonym = v_ent.origin_pseudonym)"})),
                () -> new EstadosEPrazosIT().origemAlegadaNaoEsgotaACotaDoContatoLegitimoEOLimiteEPorOrigem());
    }

    /** M7 (item 10): o aceite invalida tambem tentativas POSTERIORES. Detector: aceite fora de ordem com uso dos tokens. */
    @Test
    void mutacaoDaInvalidacaoDeTentativasPosterioresEDetectada() {
        contraprova(Map.of("public.ops_notice_delivery_result(uuid,smallint,text)", List.<String[]>of(new String[] {
                "AND o.seq < n.seq", "AND o.seq <> n.seq"})),
                () -> new Revisao4TentativasIT().aceiteForaDeOrdemPreservaATentativaPosterior());
    }

    /** M8 (item 10): a execucao ignora notice_seq_at_approval. Detector: reenvio depois da aprovacao (titular e outro admin). */
    @Test
    void mutacaoQueIgnoraASequenciaDaAprovacaoEDetectada() {
        contraprova(Map.of("public.ops_mfa_execute(uuid)", List.<String[]>of(new String[] {
                "r.notice_seq > r.notice_seq_at_approval", "false"})),
                () -> new Revisao4TentativasIT().reenvioDepoisDaAprovacaoImpedeAExecucao());
    }

    /**
     * M9 (item 5): os sinais dos considerados sao lidos ANTES dos locks e usados depois. Detector: fluxo do considerado
     * que confirma o sinal enquanto a execucao espera a conta dele.
     */
    @Test
    void mutacaoDaLeituraDosSinaisAntesDosLocksEDetectada() {
        contraprova(Map.of("public.ops_mfa_execute(uuid)", List.<String[]>of(
                new String[] {"    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;\n",
                        "    PERFORM set_config('nekkora.mut_pre', coalesce(ops.declaracao_invalida(p_request, v_org, v_target), ''), true);\n"
                                + "    PERFORM 1 FROM public.organizations WHERE organization_id = v_org FOR UPDATE;\n"},
                new String[] {"IF v_motivo IS NULL THEN v_motivo := ops.declaracao_invalida(p_request, v_org, v_target); END IF;",
                        "IF v_motivo IS NULL THEN v_motivo := nullif(current_setting('nekkora.mut_pre'), ''); END IF;"})),
                () -> assertThat(Revisao4DeclaracaoIT.execucaoComSinalDuranteAEspera()).isEqualTo("DECLARACAO_CONTRADITA"));
    }

    /** M10 (item 3): encerrar nao encerra. Detector: recusa terminal (EVIDENCIA_NEGATIVA) precisa deixar RECUSADA. */
    @Test
    void mutacaoDeEncerrarEDetectada() {
        contraprova(Map.of("ops.encerrar(uuid,smallint,text,uuid,timestamp with time zone)", List.<String[]>of(
                new String[] {"WHERE request_id = p_request AND state IN (1, 2, 3, 4);", "WHERE false;"},
                new String[] {"IF NOT FOUND THEN", "IF false THEN"})),
                Revisao4PrazosEImpedimentosIT::detectorEncerrar);
    }

    /**
     * M11 (item 10, achado da revisao da rodada 3): a emissao ignora a substituicao ja confirmada por tentativa posterior
     * aceita. Detector: tentativa antiga emitida depois do aceite da nova - o token antigo precisa ser recusado.
     */
    @Test
    void mutacaoDaEmissaoDeTentativaSubstituidaEDetectada() {
        contraprova(Map.of("public.ops_notice_issue(uuid,text,text)", List.<String[]>of(
                new String[] {"AND o.state = 4 AND t.purpose = 1),", "AND false AND t.purpose = 1),"},
                new String[] {"AND o.state = 4 AND t.purpose = 2)", "AND false AND t.purpose = 2)"})),
                Revisao4TentativasIT::detectorEmissaoAntiga);
    }

    /**
     * M12 (item 10, achado da revisao da rodada 3): a condicao de aviso volta a ser conferida ANTES do prazo (ordem
     * anterior). Detector: reenvio depois da aprovacao e execucao no vencimento - a solicitacao precisa EXPIRAR.
     */
    @Test
    void mutacaoDaCondicaoDeAvisoAntesDoPrazoEDetectada() {
        contraprova(Map.of("public.ops_mfa_execute(uuid)", List.<String[]>of(new String[] {
                "    -- prazo antes da condicao de aviso:",
                "    IF r.notice_seq > r.notice_seq_at_approval THEN\n"
                        + "        RETURN QUERY SELECT 'RECUSA'::text, 'CONDICAO_DE_AVISO_ALTERADA'::text, p_request, ops.estado_nome(r.state);\n"
                        + "        RETURN;\n"
                        + "    END IF;\n"
                        + "    -- prazo antes da condicao de aviso:"})),
                Revisao4TentativasIT::detectorPrazo);
    }
}
