package com.nekkora.platform;

import java.util.UUID;

import com.nekkora.core.testsupport.RealDatabase;

/**
 * Massa de licencas gravada DIRETO no banco, para os testes dos fluxos que conferem a licenca (064). Os atos reais (conceder,
 * renovar, ligar a exigencia) sao do operador e tem os seus proprios testes ({@code LicenseOperatorIT}); aqui interessa so o
 * estado resultante. As regras do banco continuam valendo: nada aqui desliga gatilho, exceto {@link #endPeriodNow}, que
 * simula a passagem do tempo.
 */
final class LicenseFixtures {

    private static final RealDatabase DB = RealDatabase.shared();

    private LicenseFixtures() {
    }

    /** A organizacao passa ao modelo por licenca, com o ato que liga (6) na trilha. */
    static void enforce(UUID org, UUID operatorUser) {
        UUID act = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO organization_license_acts (act_id, organization_id, act, operator_user_id, justification) VALUES ('" + act + "', '" + org
                        + "', 6, '" + operatorUser + "', 'modelo por licenca ligado na massa do teste')",
                "INSERT INTO organization_license_enforcement (organization_id, model, act_id) VALUES ('" + org + "', 2, '" + act + "')");
    }

    /** Licenca com o primeiro periodo [from, to) (expressoes SQL). */
    static UUID license(UUID org, int number, String from, String to) {
        UUID id = UUID.randomUUID();
        DB.asSuperuser("INSERT INTO organization_licenses (license_id, organization_id, license_number) VALUES ('" + id + "', '" + org + "', " + number + ")",
                "INSERT INTO organization_license_periods (organization_id, license_id, period_number, kind, starts_at, ends_at) VALUES ('" + org + "', '" + id
                        + "', 1, 1, " + from + ", " + to + ")");
        return id;
    }

    /** Renovacao: o periodo seguinte [from, to) (expressoes SQL). */
    static void renew(UUID org, UUID license, int number, String from, String to) {
        DB.asSuperuser("INSERT INTO organization_license_periods (organization_id, license_id, period_number, kind, starts_at, ends_at) VALUES ('" + org + "', '"
                + license + "', " + number + ", 2, " + from + ", " + to + ")");
    }

    static void assign(UUID org, UUID license, UUID workspace, UUID automation, UUID membership) {
        DB.asSuperuser("INSERT INTO organization_license_assignments (organization_id, license_id, workspace_id, automation_id, assigned_by_membership_id) "
                + "VALUES ('" + org + "', '" + license + "', '" + workspace + "', '" + automation + "', '" + membership + "')");
    }

    /**
     * O TEMPO PASSOU: o periodo {@code number} da licenca termina agora (os periodos sao so de acrescimo; para simular o
     * vencimento sem esperar, o gatilho e contornado NESTA instrucao, como superusuario).
     */
    static void endPeriodNow(UUID license, int number) {
        DB.asSuperuser("SET session_replication_role = replica",
                "UPDATE organization_license_periods SET ends_at = clock_timestamp() WHERE license_id = '" + license + "' AND period_number = " + number);
    }
}
