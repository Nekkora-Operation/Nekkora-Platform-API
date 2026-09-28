package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.OpsBanco;

/**
 * Grupos Integridade e Auditoria (contrato 8 e 10): vinculos estruturais compostos (evento <-> solicitacao <->
 * organizacao <-> alvo <-> fator), organizacao cruzada sem efeito, a Platform sem o vinculo operacional, motivo do
 * reset administrativo; todos os atos registrados e nenhum segredo no banco, no log do servidor nem no log da CLI
 * (varredura com controle positivo).
 */
class IntegridadeEAuditoriaIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private static String sqlstate(String... sqls) {
        try (Connection c = DB.superusuario(); Statement st = c.createStatement()) {
            for (String s : sqls) {
                st.execute(s);
            }
            return "00000";
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    @Test
    void eventoDeResetSoApontaParaSolicitacaoDaMesmaOrganizacaoEAlvo() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        UUID outroAlvo = DB.outroAdmin(al.org());
        Cenarios.Alvo al2 = C.alvo();
        // prova ESTRUTURAL (superusuario, que ignora privilegios mas nao as restricoes): evento valido isoladamente,
        // associado a solicitacao de outro alvo ou de outra organizacao
        assertThat(sqlstate("INSERT INTO mfa_events (organization_id, user_id, event_type, correlation_id, ops_request_id) VALUES ('"
                + al.org().id() + "', '" + outroAlvo + "', 4, gen_random_uuid(), '" + s + "')")).isEqualTo("23503");
        assertThat(sqlstate("INSERT INTO mfa_events (organization_id, user_id, event_type, correlation_id, ops_request_id) VALUES ('"
                + al2.org().id() + "', '" + al2.titular() + "', 4, gen_random_uuid(), '" + s + "')")).isEqualTo("23503");
        assertThat(sqlstate("INSERT INTO mfa_events (organization_id, user_id, event_type, correlation_id) VALUES ('"
                + al.org().id() + "', '" + al.titular() + "', 4, gen_random_uuid())")).as("tipo 4 sem solicitacao").isEqualTo("23514");
        assertThat(sqlstate("INSERT INTO mfa_events (organization_id, user_id, event_type, correlation_id, ops_request_id) VALUES ('"
                + al.org().id() + "', '" + al.titular() + "', 5, gen_random_uuid(), '" + s + "')")).as("vinculo em outro tipo").isEqualTo("23514");
        assertThat(C.resets(s)).isZero();
        Cenarios.exigirOk(eq.e().executar(s), "executar");
        assertThat(sqlstate("INSERT INTO mfa_events (organization_id, user_id, event_type, correlation_id, ops_request_id) VALUES ('"
                + al.org().id() + "', '" + al.titular() + "', 4, gen_random_uuid(), '" + s + "')")).as("um reset por solicitacao").isEqualTo("23505");
    }

    @Test
    void fatorAprovadoDeOutraContaViolaAFkComposta() {
        Cenarios.Alvo al = C.alvo();
        UUID outro = DB.outroAdmin(al.org());
        UUID fatorDoOutro = DB.valor(UUID.class, "SELECT factor_id FROM user_mfa_factors WHERE user_id = ? AND disabled_at IS NULL", outro);
        OpsBanco.Operador op = DB.bootstrap();
        assertThat(sqlstate("INSERT INTO ops.ops_mfa_recovery_requests (organization_id, target_user_id, requester_contact_id, requester_kind, state, "
                + "opened_at, opened_by, t_total, verified_at, verified_by, callback_ok, second_contact_id, second_contact_ok, preapproved_at, "
                + "preapproved_by, approved_factor_id) SELECT '" + al.org().id() + "', '" + al.titular() + "', '" + al.contatoTitular() + "', 1, 3, "
                + "now(), '" + op.id() + "', now() + interval '72 hours', now(), '" + op.id() + "', true, '" + al.segundoContato() + "', true, now(), "
                + "o.operator_id, '" + fatorDoOutro + "' FROM ops.ops_operators o WHERE o.operator_id <> '" + op.id() + "' LIMIT 1")).isEqualTo("23503");
    }

    @Test
    void organizacaoCruzadaERecusadaENadaMudaNasDuas() {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo a = C.alvo();
        Cenarios.Alvo b = C.alvo();
        String fa = C.acesso(a.org().id(), a.titular());
        String fb = C.acesso(b.org().id(), b.titular());
        assertThat(eq.v().abrir(a.org().id(), b.titular(), a.contatoTitular()).motivo()).isEqualTo("CONTA_INELEGIVEL");
        assertThat(eq.v().abrir(a.org().id(), a.titular(), b.contatoTitular()).motivo()).isEqualTo("CONTATO_INVALIDO");
        UUID s = C.aberta(eq, a);
        assertThat(eq.v().verificar(s, true, b.segundoContato(), true).motivo()).isEqualTo("SEGUNDO_CONTATO_INELEGIVEL");
        assertThat(C.estado(s)).isEqualTo("SOLICITADA");
        // (token de uma organizacao na rota de outra: AvisosIT)
        assertThat(C.acesso(a.org().id(), a.titular())).isEqualTo(fa);
        assertThat(C.acesso(b.org().id(), b.titular())).isEqualTo(fb);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_requests WHERE organization_id = ?", b.org().id())).isZero();
    }

    @Test
    void plataformaNaoGravaOVinculoOperacionalEResetAdministrativoExigeMotivo() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID admin = DB.outroAdmin(al.org());
        UUID s = C.aprovada(eq, al);
        try (Connection c = DB.comoServico("nekkora_platform")) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + al.org().id() + "', true)");
                assertThatThrownBy(() -> st.execute("INSERT INTO public.mfa_events (organization_id, user_id, event_type, correlation_id, ops_request_id) VALUES ('"
                        + al.org().id() + "', '" + al.titular() + "', 4, gen_random_uuid(), '" + s + "')"))
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
            }
            c.rollback();
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('nekkora.organization_id', '" + al.org().id() + "', true)");
                assertThatThrownBy(() -> st.execute("INSERT INTO public.mfa_events (organization_id, user_id, actor_user_id, event_type, correlation_id) VALUES ('"
                        + al.org().id() + "', '" + al.titular() + "', '" + admin + "', 3, gen_random_uuid())"))
                        .as("reset administrativo novo sem motivo")
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
            }
            c.rollback();
        }
        assertThat(DB.valor(Boolean.class, "SELECT convalidated FROM pg_constraint WHERE conname = 'mfa_events_reset_admin_exige_motivo'"))
                .as("NOT VALID: preserva o historico").isFalse();
    }

    @Test
    void todosOsAtosRegistradosENenhumSegredoNoBancoNoLogDoServidorOuNaCli() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID admin = DB.outroAdmin(al.org());
        List<String> segredos = new ArrayList<>();
        UUID s = C.preAprovada(eq, al);
        List<Cenarios.Tokens> t = C.emitirAvisos(al.org().id());
        for (Cenarios.Tokens x : t) {
            if (x.confirmar() != null) {
                segredos.add(x.confirmar());
            }
            if (x.contestar() != null) {
                segredos.add(x.contestar());
            }
        }
        Cenarios.Tokens tt = t.stream().filter(x -> x.temConfirmar()).findFirst().orElseThrow();
        assertThat(C.confirmar(al.org().id(), tt.confirmar())).isTrue();
        assertThat(C.confirmar(al.org().id(), t.stream().filter(x -> x.destinatario().equals(admin)).findFirst().orElseThrow().contestar())).isFalse();
        DB.recuarSolicitacao(s, Cenarios.ESPERA);
        Cenarios.exigirOk(eq.a().aprovar(s), "aprovar");
        Cenarios.exigirOk(eq.e().executar(s), "executar");
        String provaConta = C.provaDeConta(al.org().id(), DB.membro(al.org()));
        segredos.add(provaConta);
        assertThat(C.vincularConta(eq.executor(), provaConta)).isEqualTo("OK");
        String provaContato = C.provaDeContato(al.segundoContato());
        segredos.add(provaContato);
        assertThat(C.vincularContato(eq.aprovador(), provaContato)).isEqualTo("OK");

        // todos os atos da solicitacao, com operador, emissor e serial lidos pelo servidor (atos de operador)
        assertThat(DB.valor(String.class, "SELECT string_agg(act || ':' || result, ',' ORDER BY created_at) FROM ops.ops_mfa_recovery_events WHERE request_id = ?", s))
                .isEqualTo("ABRIR:OK,VERIFICAR:OK,PRE_APROVAR:OK,AVISO_EMITIDO:OK,AVISO_EMITIDO:OK,AVISO_CONFIRMADO:OK,AVISO_INVALIDO:RECUSA,APROVAR:OK,EXECUTAR:OK");
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events e JOIN ops.ops_operator_certificates c "
                + "ON c.issuer_dn = e.cert_issuer_dn AND c.serial = e.cert_serial AND c.operator_id = e.operator_id "
                + "WHERE e.request_id = ? AND e.operator_id IS NOT NULL", s)).isEqualTo(5);
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_registry_events WHERE kind IN ('VINCULO_CONTA', 'VINCULO_CONTATO') "
                + "AND result = 'OK' AND actor_operator_id IN (?, ?)", eq.executor().id(), eq.aprovador().id())).isEqualTo(2);

        // varredura: banco (ops inteiro + mfa_events), log do servidor e logs da CLI; controle positivo
        String controle = OpsBanco.tokenBruto();
        StringBuilder texto = new StringBuilder();
        try (Connection c = DB.superusuario(); Statement st = c.createStatement()) {
            st.execute("CREATE TEMP TABLE controle_varredura (valor text)");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO controle_varredura VALUES (?)")) {
                ps.setString(1, "prefixo " + controle + " sufixo");
                ps.execute();
            }
            List<String> tabelas = new ArrayList<>(List.of("pg_temp.controle_varredura", "public.mfa_events"));
            try (ResultSet rs = st.executeQuery("SELECT 'ops.' || relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = 'ops' AND c.relkind = 'r'")) {
                while (rs.next()) {
                    tabelas.add(rs.getString(1));
                }
            }
            assertThat(tabelas).hasSize(16);
            for (String tab : tabelas) {
                try (Statement s2 = c.createStatement(); ResultSet rs = s2.executeQuery("SELECT t::text FROM " + tab + " t")) {
                    while (rs.next()) {
                        texto.append(rs.getString(1)).append('\n');
                    }
                }
            }
        }
        texto.append(DB.logDoServidor());
        try (Stream<Path> logs = Files.list(DB.diretorio())) {
            for (Path p : logs.filter(p -> p.getFileName().toString().endsWith(".log")).toList()) {
                texto.append(Files.readString(p));
            }
        }
        String tudo = texto.toString();
        assertThat(tudo).as("controle positivo: a varredura encontra um valor bruto presente").contains(controle);
        for (String segredo : segredos) {
            assertThat(tudo).as("segredo bruto fora do banco e dos logs").doesNotContain(segredo);
        }
        assertThat(tudo).doesNotContain("BEGIN CERTIFICATE", "PRIVATE KEY", "sslpassword");
    }
}
