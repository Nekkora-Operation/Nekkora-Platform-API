package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.ca.EmissorCertificados;
import com.nekkora.ops.cli.Credencial;
import com.nekkora.ops.cli.ExecutorAtos;
import com.nekkora.ops.cli.ExecutorAtos.Classe;
import com.nekkora.ops.cli.LogLocal;
import com.nekkora.ops.testsupport.Cenarios;
import com.nekkora.ops.testsupport.IdpSimulado;
import com.nekkora.ops.testsupport.OpsBanco;
import com.nekkora.ops.testsupport.Pki;

/**
 * Grupo Certificados (contrato 3.2, 3.3 e 10): emissao so com MFA recente e resistente a phishing; registro pela CA;
 * validade e revogacao conferidas EM CADA ATO, inclusive com a conexao aberta e depois da espera por locks.
 */
class CertificadosIT {

    static final OpsBanco DB = OpsBanco.compartilhado();
    static final Cenarios C = new Cenarios(DB);

    private static String negada(String token) {
        KeyPair par = Credencial.novoParDeChaves();
        try {
            DB.emissor().emitir(token, par.getPublic());
            return "EMITIDO";
        } catch (EmissorCertificados.EmissaoNegada e) {
            return e.motivo();
        }
    }

    @Test
    void caNegaEmissaoSemIdentidadeValidaMfaRecenteOuMetodoResistente() {
        OpsBanco.Operador x = DB.operador("x", "CONSULTAR");
        String sub = IdpSimulado.sujeito(x.papel());
        Instant agora = Instant.now();
        IdpSimulado idp = DB.idp();
        assertThat(negada(idp.token(x.papel()))).isEqualTo("EMITIDO");
        // sessao antiga do IdP / token renovado (preserva o auth_time original)
        assertThat(negada(idp.token(IdpSimulado.EMISSOR, sub, IdpSimulado.AUDIENCIA, agora.minus(Duration.ofMinutes(6)), List.of("hwk"),
                agora.plusSeconds(300)))).isEqualTo("MFA_NAO_RECENTE");
        assertThat(negada(idp.token(IdpSimulado.EMISSOR, sub, IdpSimulado.AUDIENCIA, agora.minus(Duration.ofHours(8)), List.of("hwk"),
                agora.plusSeconds(3600)))).isEqualTo("MFA_NAO_RECENTE");
        // metodo: ver garantiaDoIdpExigeAcrResistenteMfaEProvaDePosse
        // identidade: emissor, audiencia, validade, assinatura, algoritmo
        assertThat(negada(idp.token("https://outro-idp.test", sub, IdpSimulado.AUDIENCIA, agora, List.of("hwk"), agora.plusSeconds(300))))
                .isEqualTo("EMISSOR_DIVERGENTE");
        assertThat(negada(idp.token(IdpSimulado.EMISSOR, sub, "outra-aud", agora, List.of("hwk"), agora.plusSeconds(300))))
                .isEqualTo("AUDIENCIA_DIVERGENTE");
        assertThat(negada(idp.token(IdpSimulado.EMISSOR, sub, IdpSimulado.AUDIENCIA, agora, List.of("hwk"), agora.minusSeconds(1))))
                .isEqualTo("TOKEN_VENCIDO");
        assertThat(negada(IdpSimulado.token(IdpSimulado.EMISSOR, sub, IdpSimulado.AUDIENCIA, agora, List.of("hwk"), agora.plusSeconds(300),
                Pki.rsa().getPrivate()))).isEqualTo("ASSINATURA_INVALIDA");
        String semAssinatura = IdpSimulado.b64("{\"alg\":\"none\"}".getBytes()) + "." + idp.token(x.papel()).split("\\.")[1] + ".";
        assertThat(negada(semAssinatura)).isEqualTo("ALGORITMO_NAO_ACEITO");
        // sujeito sem operador
        assertThat(negada(idp.token(IdpSimulado.EMISSOR, "sub-ninguem", IdpSimulado.AUDIENCIA, agora, IdpSimulado.AMR_OK, agora.plusSeconds(300))))
                .isEqualTo("OPERADOR_DESCONHECIDO_OU_INATIVO");
    }

    /**
     * Garantia exigida do IdP (achado da revisao: {@code hwk} isolado nao comprova MFA resistente a phishing).
     * Cada caso difere da linha de base aceita em UM aspecto; a linha de base e o controle positivo de que a recusa
     * vem da garantia, e nao de outra conferencia.
     */
    @Test
    void garantiaDoIdpExigeAcrResistenteMfaEProvaDePosse() {
        OpsBanco.Operador x = DB.operador("x", "CONSULTAR");
        String sub = IdpSimulado.sujeito(x.papel());
        Instant agora = Instant.now();
        IdpSimulado idp = DB.idp();
        record Caso(String nome, String acr, List<String> amr, String esperado) {
        }
        List<Caso> casos = List.of(
                new Caso("linha de base: phrh + hwk + mfa", "phrh", List.of("hwk", "pin", "mfa"), "EMITIDO"),
                new Caso("phr + chave de software + mfa", "phr", List.of("swk", "mfa"), "EMITIDO"),
                new Caso("phr + pop + mfa", "phr", List.of("pop", "mfa"), "EMITIDO"),
                // o achado: hwk isolado
                new Caso("hwk isolado, sem acr", null, List.of("hwk"), "METODO_NAO_RESISTENTE"),
                new Caso("amr completo, sem acr", null, List.of("hwk", "pin", "mfa"), "METODO_NAO_RESISTENTE"),
                new Caso("acr fora da lista", "urn:nekkora:acr:senha", List.of("hwk", "pin", "mfa"), "METODO_NAO_RESISTENTE"),
                new Caso("acr 'mfa' nao e acr resistente", "mfa", List.of("hwk", "pin", "mfa"), "METODO_NAO_RESISTENTE"),
                new Caso("phrh com hwk isolado (sem mfa)", "phrh", List.of("hwk"), "MFA_NAO_AFIRMADO"),
                new Caso("phrh com hwk + pin, sem mfa afirmado", "phrh", List.of("hwk", "pin"), "MFA_NAO_AFIRMADO"),
                new Caso("phr com senha + otp + mfa (sem posse de chave)", "phr", List.of("pwd", "otp", "mfa"), "ASSERCAO_INCONSISTENTE"),
                new Caso("phr com senha + sms + mfa", "phr", List.of("pwd", "sms", "mfa"), "ASSERCAO_INCONSISTENTE"),
                new Caso("phrh sem hardware (swk)", "phrh", List.of("swk", "mfa"), "ASSERCAO_INCONSISTENTE"));
        for (Caso c : casos) {
            assertThat(negada(idp.token(IdpSimulado.EMISSOR, sub, IdpSimulado.AUDIENCIA, agora, c.acr(), c.amr(), agora.plusSeconds(300))))
                    .as(c.nome()).isEqualTo(c.esperado());
        }
        // o que fica registrado e o acr resistente com o mfa afirmado, nunca um amr isolado
        assertThat(DB.valor(String.class, "SELECT string_agg(DISTINCT idp_method, ',' ORDER BY idp_method) FROM ops.ops_operator_certificates c "
                + "JOIN ops.ops_operators o ON o.operator_id = c.operator_id WHERE o.db_role = ?", x.papel())).isEqualTo("phr+mfa,phrh+mfa");
    }

    @Test
    void bancoRecusaRegistroForaDaPoliticaMesmoVindoDaCa() throws Exception {
        OpsBanco.Operador x = DB.operador("x", "CONSULTAR");
        String sub = IdpSimulado.sujeito(x.papel());
        Instant agora = Instant.now();
        // chave = motivo esperado + variante; metodo 'hwk' (amr isolado) e 'phrh' (acr sem mfa) nao valem mesmo vindo da CA
        Map<String, Object[]> casos = new java.util.LinkedHashMap<>();
        casos.put("VALIDADE_INVALIDA", new Object[] {agora, agora.plus(Duration.ofMinutes(16)), agora, "phrh+mfa"});
        casos.put("MFA_NAO_RECENTE", new Object[] {agora, agora.plus(Duration.ofMinutes(10)), agora.minus(Duration.ofMinutes(6)), "phrh+mfa"});
        casos.put("METODO_NAO_RESISTENTE", new Object[] {agora, agora.plus(Duration.ofMinutes(10)), agora, "otp"});
        casos.put("METODO_NAO_RESISTENTE#hwk", new Object[] {agora, agora.plus(Duration.ofMinutes(10)), agora, "hwk"});
        casos.put("METODO_NAO_RESISTENTE#phrh", new Object[] {agora, agora.plus(Duration.ofMinutes(10)), agora, "phrh"});
        try (Connection c = DB.comoServico("nekkora_ops_ca")) {
            for (var caso : casos.entrySet()) {
                try (PreparedStatement ps = c.prepareStatement("SELECT r_resultado, r_motivo FROM public.ops_register_certificate(?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, IdpSimulado.EMISSOR);
                    ps.setString(2, sub);
                    ps.setString(3, x.papel());
                    ps.setString(4, DB.emissor().emissorNoServidor());
                    ps.setBigDecimal(5, new java.math.BigDecimal(new java.math.BigInteger(100, new java.security.SecureRandom()).setBit(99)));
                    ps.setTimestamp(6, Timestamp.from((Instant) caso.getValue()[0]));
                    ps.setTimestamp(7, Timestamp.from((Instant) caso.getValue()[1]));
                    ps.setTimestamp(8, Timestamp.from((Instant) caso.getValue()[2]));
                    ps.setString(9, (String) caso.getValue()[3]);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        assertThat(rs.getString(1)).as(caso.getKey()).isEqualTo("RECUSA");
                        assertThat(rs.getString(2)).isEqualTo(caso.getKey().split("#")[0]);
                    }
                }
            }
            // a CA nao le nem altera o cadastro diretamente
            try (Statement st = c.createStatement()) {
                assertThatThrownBy(() -> st.execute("SELECT count(*) FROM ops.ops_operator_certificates"))
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
            }
        }
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_operator_certificates WHERE operator_id = ?", x.id())).isZero();
    }

    private static String[] ato(Connection con, UUID solicitacao) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT r_resultado, r_motivo FROM public.ops_mfa_status(?)")) {
            ps.setObject(1, solicitacao);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new String[] {rs.getString(1), rs.getString(2)};
            }
        }
    }

    @Test
    void conexaoAbertaAtravessandoOVencimentoNaoAceitaONovoAto() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        UUID s = C.aberta(eq, C.alvo());
        try (Credencial c = DB.credencial(eq.verificador()); Connection con = DB.conectar(c)) {
            assertThat(ato(con, s)[0]).isEqualTo("OK");
            DB.vencerCertificado(c);
            assertThat(ato(con, s)).containsExactly("RECUSA", "CERTIFICADO_FORA_DA_VALIDADE");
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                assertThat(rs.next()).as("a conexao continua aberta").isTrue();
            }
        }
        assertThat(DB.valor(Long.class, "SELECT count(*) FROM ops.ops_mfa_recovery_events WHERE request_id = ? AND act = 'CONSULTAR' "
                + "AND result = 'RECUSA' AND reason = 'CERTIFICADO_FORA_DA_VALIDADE'", s)).isEqualTo(1);
    }

    @Test
    void certificadoRevogadoComSessaoTlsVivaRecusaOProximoAto() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        UUID s = C.aberta(eq, C.alvo());
        try (Credencial c = DB.credencial(eq.verificador()); Connection con = DB.conectar(c)) {
            assertThat(ato(con, s)[0]).isEqualTo("OK");
            try (Connection ca = DB.comoServico("nekkora_ops_ca");
                 PreparedStatement ps = ca.prepareStatement("SELECT r_resultado FROM public.ops_revoke_certificate(?, ?)")) {
                ps.setString(1, DB.emissor().emissorNoServidor());
                ps.setBigDecimal(2, new java.math.BigDecimal(c.certificado().getSerialNumber()));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("OK");
                }
            }
            assertThat(ato(con, s)).containsExactly("RECUSA", "CERTIFICADO_REVOGADO");
        }
    }

    @Test
    void retirarHabilitacaoRevogaOsCertificadosVigentes() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        UUID s = C.aberta(eq, C.alvo());
        try (Credencial c = DB.credencial(eq.verificador()); Connection con = DB.conectar(c)) {
            assertThat(ato(con, s)[0]).isEqualTo("OK");
            assertThat(DB.cadastro("SELECT r_resultado FROM public.ops_operator_revoke(?, 'CANCELAR')", String.class, eq.verificador().id())).isEqualTo("OK");
            assertThat(ato(con, s)).containsExactly("RECUSA", "CERTIFICADO_REVOGADO");
        }
    }

    /** Espera por locks atravessando a validade do certificado: recusa pelo v_agora capturado DEPOIS dos locks. */
    @Test
    void esperaPorLocksAtravessandoAValidadeDoCertificado() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        try (Credencial c = DB.credencial(eq.executor()); Connection bloqueio = DB.superusuario()) {
            ExecutorAtos exe = new ExecutorAtos(() -> DB.conectar(c), new LogLocal(DB.diretorio().resolve("espera1.log")));
            bloqueio.setAutoCommit(false);
            try (Statement st = bloqueio.createStatement()) {
                st.execute("SELECT 1 FROM organizations WHERE organization_id = '" + al.org().id() + "' FOR UPDATE");
            }
            CompletableFuture<ExecutorAtos.Resultado> f = CompletableFuture.supplyAsync(() -> exe.executar(s));
            esperarBloqueado(eq.executor().papel());
            DB.vencerCertificado(c);
            bloqueio.commit();
            ExecutorAtos.Resultado r = f.get(60, TimeUnit.SECONDS);
            assertThat(r.classe()).isEqualTo(Classe.RECUSA);
            assertThat(r.motivo()).isEqualTo("CERTIFICADO_FORA_DA_VALIDADE");
        }
        assertThat(C.resets(s)).isZero();
        assertThat(C.estado(s)).as("recusa do ato nao encerra a solicitacao").isEqualTo("APROVADA");
        assertThat(C.acesso(al.org().id(), al.titular())).isEqualTo("1|2|1|1");
    }

    /** Espera por locks atravessando a janela de 60 min da aprovacao final: o menor prazo vale no instante pos-locks. */
    @Test
    void esperaPorLocksAtravessandoAJanelaDaAprovacao() throws Exception {
        Cenarios.Equipe eq = C.equipe();
        Cenarios.Alvo al = C.alvo();
        UUID s = C.aprovada(eq, al);
        try (Connection bloqueio = DB.superusuario()) {
            bloqueio.setAutoCommit(false);
            try (Statement st = bloqueio.createStatement()) {
                st.execute("SELECT 1 FROM organizations WHERE organization_id = '" + al.org().id() + "' FOR UPDATE");
            }
            CompletableFuture<ExecutorAtos.Resultado> f = CompletableFuture.supplyAsync(() -> eq.e().executar(s));
            esperarBloqueado(eq.executor().papel());
            DB.recuarSolicitacao(s, Duration.ofMinutes(61));
            bloqueio.commit();
            ExecutorAtos.Resultado r = f.get(60, TimeUnit.SECONDS);
            assertThat(r.classe()).isEqualTo(Classe.RECUSA);
            assertThat(r.motivo()).isEqualTo("PRAZO_ENCERRADO");
        }
        assertThat(C.estado(s)).isEqualTo("EXPIRADA");
        assertThat(C.resets(s)).isZero();
    }

    /** Espera ate a sessao do papel estar bloqueada por lock (pg_stat_activity), sem dormir as cegas. */
    static void esperarBloqueado(String papel) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Long n = DB.valor(Long.class, "SELECT count(*) FROM pg_stat_activity WHERE usename = ? AND wait_event_type = 'Lock'", papel);
            if (n != null && n > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("a sessao de " + papel + " nao ficou bloqueada");
    }
}
