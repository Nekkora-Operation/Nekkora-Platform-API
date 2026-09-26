package com.nekkora.platform.session;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Principal reconstruido do banco a CADA requisicao (nunca guardado em HttpSession). {@code vinculoId} e o
 * organization_memberships.membership_id (conta interna) ou client_memberships.client_membership_id (externa).
 */
public record ValidSession(UUID sessionId, UUID organizationId, UUID userId, short accountType, UUID membershipId,
                           String csrfToken, OffsetDateTime stepUpAt, OffsetDateTime expiresAt) {

    public static final short INTERNAL = 1;
    public static final short EXTERNAL = 2;

    public boolean isInternal() {
        return accountType == INTERNAL;
    }
}
