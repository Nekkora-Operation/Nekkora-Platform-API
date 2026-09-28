package com.nekkora.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import com.nekkora.ops.cli.Credencial;
import com.nekkora.ops.testsupport.OpsBanco;

/** Sanidade da infraestrutura de teste: TLS, autenticacao por certificado e formato do emissor lido pelo servidor. */
class InfraestruturaIT {

    static final OpsBanco DB = OpsBanco.compartilhado();

    @Test
    void operadorConectaPorCertificadoEOServidorLeEmissorESerial() throws Exception {
        OpsBanco.Operador op;
        try {
            op = DB.operador("infra", "CONSULTAR");
        } catch (RuntimeException e) {
            System.out.println(DB.logDoServidor().lines().filter(l -> l.contains("LOG") || l.contains("FATAL") || l.contains("DETAIL"))
                    .reduce("", (a, b) -> a + System.lineSeparator() + b));
            throw e;
        }
        try (Credencial c = DB.credencial(op); Connection con = DB.conectar(c); Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT session_user::text, current_setting('ssl_library', true)")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo(op.papel());
        }
        // o emissor registrado pela CA e o que a sslinfo le da conexao
        String registrado = DB.valor(String.class, "SELECT issuer_dn FROM ops.ops_operator_certificates c JOIN ops.ops_operators o USING (operator_id) "
                + "WHERE o.db_role = ? LIMIT 1", op.papel());
        assertThat(registrado).isEqualTo(DB.emissor().emissorNoServidor());
        System.out.println("emissor no servidor: " + registrado);
    }
}
