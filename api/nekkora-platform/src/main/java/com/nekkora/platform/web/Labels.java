package com.nekkora.platform.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nekkora.core.web.ApiError;
import com.nekkora.core.web.ApiException;

/**
 * Rotulos estaveis em ingles minusculo das rotas internas (contrato GAP-02/03/06, §1.7), a partir dos codigos das colunas
 * (comentarios das migrations 001-003). Codigo desconhecido vindo do banco -> 500 com log (nunca omitido); valor de filtro
 * desconhecido, vazio, repetido ou acima do maximo -> 400.
 */
public final class Labels {

    private static final Logger LOG = LoggerFactory.getLogger(Labels.class);

    public enum Kind {
        /** users.status: 1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=CLOSED. */
        ACCOUNT(Map.of(1, "pending", 2, "active", 3, "suspended", 4, "closed")),
        /** organization_memberships.status e client_memberships.status: 1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=REVOKED. */
        MEMBERSHIP(Map.of(1, "pending", 2, "active", 3, "suspended", 4, "revoked")),
        /** client_workspace_grants.status: 1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=REVOKED. */
        GRANT(Map.of(1, "pending", 2, "active", 3, "suspended", 4, "revoked")),
        /** clients.status e workspaces.status: 1=ACTIVE, 2=SUSPENDED, 3=ARCHIVED. */
        RESOURCE(Map.of(1, "active", 2, "suspended", 3, "archived"));

        private final Map<Integer, String> labels;

        Kind(Map<Integer, String> labels) {
            this.labels = labels;
        }
    }

    private Labels() {
    }

    public static String label(Kind kind, int code) {
        String l = kind.labels.get(code);
        if (l == null) {
            LOG.error("evento=api.rotulo_desconhecido tipo={}", kind);
            throw new ApiException(ApiError.INTERNAL_ERROR, "rotulo_" + kind);
        }
        return l;
    }

    /**
     * Filtro {@code status} (repetivel): ausente -> nulo (sem filtro); senao codigos do banco. Valor desconhecido ou vazio,
     * repeticao, ou mais de {@code max} valores -> 400.
     */
    public static List<Short> statusFilter(Kind kind, List<String> values, int max) {
        if (values == null) {
            return null;
        }
        if (values.isEmpty() || values.size() > max) {
            throw ApiException.invalid("status");
        }
        Set<String> seen = new HashSet<>();
        List<Short> codes = new ArrayList<>();
        for (String v : values) {
            if (!seen.add(v)) {
                throw ApiException.invalid("status");
            }
            Integer code = kind.labels.entrySet().stream().filter(e -> e.getValue().equals(v)).map(Map.Entry::getKey).findFirst()
                    .orElseThrow(() -> ApiException.invalid("status"));
            codes.add(code.shortValue());
        }
        return codes;
    }
}
