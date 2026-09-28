package com.nekkora.platform.account;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL de conta/credencial. Tudo dentro de transacao com contexto RLS da organizacao da rota. */
public final class AccountRepository {

    /**
     * Situacao da conta para autenticacao. {@code elegivel}: ACTIVE, e-mail verificado, organizacao ativa e vinculo
     * ativo (interno: organization_memberships; externo: client_memberships + cliente ativo). {@code hashSenha}
     * pode ser nulo (conta sem credencial).
     */
    public record Account(UUID userId, short accountType, String email, boolean eligible, String passwordHash, boolean hasConfirmedFactor) {
    }

    private static final String SELECT_ACCOUNT = """
            SELECT u.user_id, u.account_type, u.email, cr.password_hash,
                   (u.status = 2 AND u.email_verified_at IS NOT NULL AND o.status = 1 AND
                    CASE WHEN u.account_type = 1 THEN
                        EXISTS (SELECT 1 FROM public.organization_memberships om
                                 WHERE om.user_id = u.user_id AND om.organization_id = u.organization_id AND om.status = 2)
                    ELSE
                        EXISTS (SELECT 1 FROM public.client_memberships cm
                                  JOIN public.clients c ON c.client_id = cm.client_id AND c.organization_id = cm.organization_id
                                 WHERE cm.user_id = u.user_id AND cm.organization_id = u.organization_id AND cm.status = 2 AND c.status = 1)
                    END) AS elegivel,
                   EXISTS (SELECT 1 FROM public.user_mfa_factors f
                            WHERE f.user_id = u.user_id AND f.organization_id = u.organization_id
                              AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL) AS tem_fator
              FROM public.users u
              JOIN public.organizations o ON o.organization_id = u.organization_id
              LEFT JOIN public.user_credentials cr ON cr.user_id = u.user_id
            """;

    /** Busca tenant-scoped por organizacao + e-mail normalizado (lower(email)); sem lookup global (§3.1). */
    public Optional<Account> byEmail(JdbcClient j, UUID org, String normalizedEmail) {
        return j.sql(SELECT_ACCOUNT + " WHERE u.organization_id = ? AND u.email_normalized = ?")
                .param(org).param(normalizedEmail).query(this::mapRow).optional();
    }

    public Optional<Account> byId(JdbcClient j, UUID org, UUID user) {
        return j.sql(SELECT_ACCOUNT + " WHERE u.organization_id = ? AND u.user_id = ?").param(org).param(user).query(this::mapRow).optional();
    }

    private Account mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Account(rs.getObject("user_id", UUID.class), rs.getShort("account_type"), rs.getString("email"),
                rs.getBoolean("elegivel"), rs.getString("password_hash"), rs.getBoolean("tem_fator"));
    }

    private static final String EFFECTIVE_ORG_ADMIN = """
            FROM public.organization_role_assignments a
            JOIN public.organization_memberships m ON m.organization_id = a.organization_id AND m.membership_id = a.membership_id
            JOIN public.users u ON u.organization_id = m.organization_id AND u.user_id = m.user_id
            JOIN public.roles r ON r.role_id = a.role_id
            WHERE a.organization_id = ? AND a.status = 1 AND m.status = 2 AND u.status = 2 AND r.code = 'ORG_ADMIN'
            """;

    /** ORG_ADMIN efetivo (definicao da 003): atribuicao ativa, vinculo ativo, conta ativa. */
    public boolean isEffectiveOrgAdmin(JdbcClient j, UUID org, UUID user) {
        return j.sql("SELECT EXISTS (SELECT 1 " + EFFECTIVE_ORG_ADMIN + " AND m.user_id = ?)").param(org).param(user).query(Boolean.class).single();
    }

    /** E-mails dos demais ORG_ADMIN efetivos (exceto {@code except}). */
    public java.util.List<String> effectiveOrgAdminEmails(JdbcClient j, UUID org, UUID except) {
        return j.sql("SELECT DISTINCT u.email " + EFFECTIVE_ORG_ADMIN + " AND m.user_id <> ? ORDER BY 1").param(org).param(except)
                .query(String.class).list();
    }

    /** Lock da conta: primeiro lock de todo caminho que cria sessao ou muda credencial/fator/desafios/sessoes (§3.2). */
    public boolean lock(JdbcClient j, UUID org, UUID user) {
        return j.sql("SELECT 1 FROM public.users WHERE organization_id = ? AND user_id = ? FOR UPDATE").param(org).param(user)
                .query(Integer.class).optional().isPresent();
    }

    /** Lock da organizacao (caminhos protegidos pela 003: suspensao de conta/vinculo) - antes do lock da conta. */
    public void lockOrganization(JdbcClient j, UUID org) {
        j.sql("SELECT 1 FROM public.organizations WHERE organization_id = ? FOR UPDATE").param(org).query(Integer.class).optional();
    }

    public Optional<String> currentHash(JdbcClient j, UUID user) {
        return j.sql("SELECT password_hash FROM public.user_credentials WHERE user_id = ?").param(user).query(String.class).optional();
    }

    /** Troca o hash SOMENTE se o armazenado ainda for {@code esperado}. */
    public boolean replaceHashIfUnchanged(JdbcClient j, UUID user, String expected, String replacement, boolean passwordChanged) {
        return j.sql("UPDATE public.user_credentials SET password_hash = ?"
                        + (passwordChanged ? ", password_changed_at = clock_timestamp()" : "")
                        + " WHERE user_id = ? AND password_hash = ?")
                .param(replacement).param(user).param(expected).update() == 1;
    }

    public int updateStatus(JdbcClient j, UUID org, UUID user, short status) {
        return j.sql("UPDATE public.users SET status = ? WHERE organization_id = ? AND user_id = ? AND status <> ?")
                .param(status).param(org).param(user).param(status).update();
    }

    /** Permissao pela matriz da 003 (papel de organizacao ativo, vinculo interno e conta ativos). */
    public boolean hasPermission(JdbcClient j, UUID org, UUID user, String permission) {
        return j.sql("""
                SELECT EXISTS (
                  SELECT 1 FROM public.organization_role_assignments ra
                    JOIN public.organization_memberships om ON om.organization_id = ra.organization_id AND om.membership_id = ra.membership_id
                    JOIN public.users u ON u.organization_id = om.organization_id AND u.user_id = om.user_id
                    JOIN public.role_permissions rp ON rp.role_id = ra.role_id
                    JOIN public.permissions p ON p.permission_id = rp.permission_id
                   WHERE ra.organization_id = ? AND u.user_id = ? AND ra.status = 1 AND om.status = 2 AND u.status = 2 AND p.code = ?)
                """).param(org).param(user).param(permission).query(Boolean.class).single();
    }
}
