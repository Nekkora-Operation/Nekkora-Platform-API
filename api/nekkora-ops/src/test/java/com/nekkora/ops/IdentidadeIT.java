package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.ca.EmissorCertificados;
import com.nekkora.ops.cli.Credencial;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.IdpSimulado;
import com.nekkora.ops.testsupport.OpsBanco;
import com.nekkora.ops.testsupport.Pki;

/**
 * Grupo Identidade (contrato 3.1 e 10, personificacao obrigatoria): autoria pelo servidor; com a credencial de X
 * nao se age como Y - sem parametro de autoria, SET ROLE, SET SESSION AUTHORIZATION ou certificado de X com usuario
 * op_y; papeis de servico nao agem como operador nem o contrario.
 */
class IdentidadeIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    @Test
    void atoERegistradoComoOOperadorDaConexaoComEmissorESerialLidosPeloServidor() {
        OpsBanco.Operador v = DB.operador("x", "VERIFICAR");
        Cenarios.Alvo al = C.alvo();
        try (Credencial c = DB.credencial(v)) {
            var cli = new com.nekkora.ops.cli.ExecutorAtos(() -> DB.conectar(c), new com.nekkora.ops.cli.LogLocal(DB.diretorio().resolve("id1.log")));
            Cenarios.exigirOk(C.abrirPorEntrada(v, cli, al.org().id(), al.titular(), al.contatoTitular()), "abrir");
            List<List<Object>> ev = DB.linhas("SELECT operator_id, session_role, cert_serial::text, cert_issuer_dn, origin FROM ops.ops_mfa_recovery_events "
                    + "WHERE act = 'ABRIR' AND target_user_id = ?", al.titular());
            assertThat(ev).hasSize(1);
            assertThat(ev.get(0).get(0)).isEqualTo(v.id());
            assertThat(ev.get(0).get(1)).isEqualTo(v.papel());
            assertThat(ev.get(0).get(2)).isEqualTo(c.certificado().getSerialNumber().toString());
            assertThat(ev.get(0).get(3)).isEqualTo(DB.emissor().emissorNoServidor());
            assertThat(((Number) ev.get(0).get(4)).intValue()).as("CONFIRMADO_PELO_BANCO").isEqualTo(1);
        }
    }

    @Test
    void nenhumaFuncaoDoOperadorRecebeAutoriaEmissorOuSerial() {
        // entradas exatas das 21 funcoes do grupo nekkora_ops_mfa: nenhuma recebe autor, emissor, serial ou instante
        java.util.Map<String, String> esperado = new java.util.TreeMap<>(java.util.Map.ofEntries(
                java.util.Map.entry("ops_mfa_open", "p_org,p_target,p_requester_contact,p_entry,p_callback"),
                java.util.Map.entry("ops_mfa_callback_register", "p_contact"),
                java.util.Map.entry("ops_mfa_verify", "p_request,p_callback_ok,p_second_contact,p_second_contact_ok,p_other_admins"),
                java.util.Map.entry("ops_mfa_preapprove", "p_request"),
                java.util.Map.entry("ops_mfa_final_approve", "p_request"),
                java.util.Map.entry("ops_mfa_execute", "p_request"),
                java.util.Map.entry("ops_mfa_cancel", "p_request,p_reason"),
                java.util.Map.entry("ops_mfa_resend_notice", "p_request,p_recipient"),
                java.util.Map.entry("ops_mfa_status", "p_request"),
                java.util.Map.entry("ops_mfa_record_failure", "p_request,p_failed_act,p_sqlstate,p_stage"),
                java.util.Map.entry("ops_operator_create", "p_db_role,p_idp_issuer,p_idp_subject"),
                java.util.Map.entry("ops_operator_grant", "p_operator,p_act"),
                java.util.Map.entry("ops_operator_revoke", "p_operator,p_act"),
                java.util.Map.entry("ops_operator_disable", "p_operator"),
                java.util.Map.entry("ops_link_platform_account", "p_code_hash"),
                java.util.Map.entry("ops_contact_link_code_issue", "p_contact,p_code_hash"),
                java.util.Map.entry("ops_link_contact", "p_code_hash"),
                java.util.Map.entry("ops_link_remove", "p_kind,p_link"),
                java.util.Map.entry("ops_contact_add", "p_org,p_display_name,p_channel_type,p_channel_value,p_user,p_origin,p_signatory_confirmed"),
                java.util.Map.entry("ops_contact_change_channel", "p_contact,p_channel_type,p_channel_value,p_previous_confirmed,p_other_contact,p_other_confirmed"),
                java.util.Map.entry("ops_contact_remove", "p_contact,p_previous_confirmed,p_other_contact,p_other_confirmed")));
        java.util.Map<String, String> real = new java.util.TreeMap<>();
        for (List<Object> a : DB.linhas("SELECT p.proname, coalesce(array_to_string(p.proargnames[1:p.pronargs], ','), '') FROM pg_proc p "
                + "JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'ops\\_%' "
                + "AND has_function_privilege('nekkora_ops_mfa', p.oid, 'EXECUTE')")) {
            real.put((String) a.get(0), (String) a.get(1));
        }
        assertThat(real).isEqualTo(esperado);
    }

    @Test
    void setRoleESetSessionAuthorizationNaoTrocamAAutoria() throws Exception {
        OpsBanco.Operador x = DB.operador("x", "VERIFICAR");
        OpsBanco.Operador y = DB.operador("y", "VERIFICAR", "APROVAR");
        Cenarios.Alvo al = C.alvo();
        try (Credencial c = DB.credencial(x); Connection con = DB.conectar(c); Statement st = con.createStatement()) {
            assertThatThrownBy(() -> st.execute("SET ROLE " + y.papel())).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> st.execute("SET SESSION AUTHORIZATION " + y.papel())).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
            // SET ROLE para o grupo muda so current_user; a autoria continua session_user = X
            st.execute("SET ROLE nekkora_ops_mfa");
            UUID entrada = C.entradaComprovada(x, OpsBanco.hex(32));
            try (PreparedStatement ps = con.prepareStatement("SELECT r_resultado FROM public.ops_mfa_open(?, ?, ?, ?, NULL)")) {
                ps.setObject(1, al.org().id());
                ps.setObject(2, al.titular());
                ps.setObject(3, al.contatoTitular());
                ps.setObject(4, entrada);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("OK");
                }
            }
        }
        UUID s = DB.valor(UUID.class, "SELECT request_id FROM ops.ops_mfa_recovery_requests WHERE target_user_id = ?", al.titular());
        assertThat(DB.valor(UUID.class, "SELECT opened_by FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", s)).isEqualTo(x.id());
        // X nao tem APROVAR; nem com SET ROLE consegue pre-aprovar como Y
        try (Credencial c = DB.credencial(x); Connection con = DB.conectar(c); Statement st = con.createStatement()) {
            st.execute("SET ROLE nekkora_ops_mfa");
            try (ResultSet rs = st.executeQuery("SELECT r_resultado, r_motivo FROM public.ops_mfa_preapprove('" + s + "')")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("RECUSA");
            }
        }
    }

    @Test
    void certificadoDeXComUsuarioYESemCertificadoOuSemTlsSaoRecusadosNaConexao() {
        OpsBanco.Operador x = DB.operador("x", "VERIFICAR");
        OpsBanco.Operador y = DB.operador("y", "VERIFICAR");
        try (Credencial c = DB.credencial(x)) {
            Properties p = c.propriedades(DB.raizServidor());
            p.setProperty("user", y.papel());
            assertThatThrownBy(() -> DriverManager.getConnection(DB.url(), p).close())
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("28000"));
        }
        // senha nao autentica operador (so certificado), mesmo com senha definida pelo DBA
        DB.sql("ALTER ROLE " + x.papel() + " PASSWORD 'senha-sintetica-longa'");
        assertThatThrownBy(() -> DriverManager.getConnection(DB.url(), DB.tls(x.papel(), "senha-sintetica-longa")).close())
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("28000"));
        DB.sql("ALTER ROLE " + x.papel() + " PASSWORD NULL");
        Properties semTls = new Properties();
        semTls.setProperty("user", x.papel());
        semTls.setProperty("sslmode", "disable");
        assertThatThrownBy(() -> DriverManager.getConnection(DB.url(), semTls).close()).isInstanceOf(SQLException.class);
    }

    @Test
    void certificadoDeCaEstranhaNaoConecta() {
        OpsBanco.Operador x = DB.operador("x", "VERIFICAR");
        Pki.Ca estranha = Pki.ca("CA Estranha");
        KeyPair par = Credencial.novoParDeChaves();
        var cert = Pki.clienteSemRegistro(estranha, par.getPublic(), x.papel(), Instant.now().minusSeconds(5), Instant.now().plus(Duration.ofMinutes(10)));
        try (Credencial c = Credencial.de(x.papel(), par, cert, estranha.certificado())) {
            assertThatThrownBy(() -> DB.conectar(c).close()).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void certificadoDaCaOperacionalSemRegistroNaoAutorizaNenhumAto() throws Exception {
        OpsBanco.Operador x = DB.operador("x", "VERIFICAR", "CONSULTAR");
        Cenarios.Alvo al = C.alvo();
        KeyPair par = Credencial.novoParDeChaves();
        // assinado com a chave da CA de teste, mas sem passar pelo registro de emissao
        var cert = Pki.clienteSemRegistro(DB.caOps(), par.getPublic(), x.papel(), Instant.now().minusSeconds(5), Instant.now().plus(Duration.ofMinutes(10)));
        UUID entrada = C.entradaComprovada(x, OpsBanco.hex(32));
        try (Credencial c = Credencial.de(x.papel(), par, cert, DB.caOps().certificado()); Connection con = DB.conectar(c);
             PreparedStatement ps = con.prepareStatement("SELECT r_resultado, r_motivo FROM public.ops_mfa_open(?, ?, ?, ?, NULL)")) {
            ps.setObject(1, al.org().id());
            ps.setObject(2, al.titular());
            ps.setObject(3, al.contatoTitular());
            ps.setObject(4, entrada);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("RECUSA");
                assertThat(rs.getString(2)).isEqualTo("CERTIFICADO_NAO_REGISTRADO");
            }
        }
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_requests WHERE target_user_id = ?", al.titular())).isZero();
    }

    @Test
    void papelSemCadastroOperacionalNaoRecebeCertificado() {
        String papel = "op_semcadastro_" + OpsBanco.sufixo();
        DB.papelOperador(papel);
        KeyPair par = Credencial.novoParDeChaves();
        assertThatThrownBy(() -> DB.emissor().emitir(DB.idp().token(papel), par.getPublic()))
                .isInstanceOf(EmissorCertificados.EmissaoNegada.class)
                .satisfies(e -> assertThat(((EmissorCertificados.EmissaoNegada) e).motivo()).isEqualTo("OPERADOR_DESCONHECIDO_OU_INATIVO"));
        assertThat(IdpSimulado.sujeito(papel)).isNotBlank();
    }

    @Test
    void servicosNaoAgemComoOperadorEOperadorNaoRegistraConfirmacaoNemLeTabelas() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.preAprovada(eq, al);
        for (String papel : List.of("nekkora_platform", "nekkora_ops_ca", "nekkora_portal", "nekkora_provisioning", "nekkora_ops_intake")) {
            try (Connection con = DB.comoServico(papel); Statement st = con.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("SELECT * FROM public.ops_mfa_execute('" + s + "')"))
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).as(papel).isEqualTo("42501"));
                assertThatThrownBy(() -> st.executeQuery("SELECT count(*) FROM ops.ops_mfa_recovery_requests"))
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).as(papel).isEqualTo("42501"));
            }
        }
        try (Credencial c = DB.credencial(eq.verificador()); Connection con = DB.conectar(c); Statement st = con.createStatement()) {
            for (String sql : List.of("SELECT public.ops_notice_confirm('" + al.org().id() + "', repeat('a', 64))",
                    "SELECT * FROM public.ops_notice_issue(gen_random_uuid(), repeat('a', 64), repeat('b', 64))",
                    // operador nao registra resultado de entrega, nao reivindica entrega e nao registra entrada
                    "SELECT * FROM public.ops_notice_delivery_result(gen_random_uuid(), 4::smallint, NULL)",
                    "SELECT * FROM public.ops_notice_claim(gen_random_uuid(), gen_random_uuid())",
                    "SELECT * FROM public.ops_intake_register(2::smallint, 1::smallint, 1, repeat('a', 64), NULL, NULL, 'op_x')",
                    "SELECT * FROM public.ops_intake_key_activate(2)",
                    "SELECT count(*) FROM ops.ops_mfa_notice_tokens",
                    "UPDATE ops.ops_mfa_recovery_requests SET notice_confirmed_at = now()",
                    "SELECT count(*) FROM public.user_mfa_factors")) {
                assertThatThrownBy(() -> st.execute(sql)).as(sql)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
            }
        }
        assertThat(DB.valor(Boolean.class, "SELECT notice_confirmed_at IS NULL FROM ops.ops_mfa_recovery_requests WHERE request_id = ?", s)).isTrue();
    }
}
