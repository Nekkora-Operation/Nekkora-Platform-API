package com.nekkora.platform.paging;

import com.nekkora.core.web.ApiException;

/** Regras comuns de pagina das rotas internas (contrato GAP-02/03/06, §1.4): {@code limit} de 1 a 100, padrao 25. */
public final class Paging {

    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 100;

    private Paging() {
    }

    /** {@code limit} nao inteiro ja vira 400 na conversao do Spring; aqui, fora da faixa -> 400. */
    public static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.invalid("limit");
        }
        return limit;
    }
}
