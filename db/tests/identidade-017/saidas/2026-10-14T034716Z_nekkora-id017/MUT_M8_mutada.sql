CREATE OR REPLACE FUNCTION enforce_user_session_mfa_on_insert() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    IF NEW.mfa_verified_at IS NULL OR NEW.csrf_token IS NULL OR NEW.mfa_step_up_at IS NOT NULL OR NEW.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'sessao nova exige marcador de MFA e CSRF, sem step-up nem revogacao'
            USING ERRCODE = '23514';
    END IF;
    -- R4: ramo da conta vinculada (identity_session_id preenchido): a organizacao FOR SHARE ANTES da conta, na ordem
    -- global organizacao -> conta. Serializa a emissao com a desativacao da conta, do vinculo e da organizacao (cabecalho,
    -- R4). Ramo 1 (identity_session_id nulo): nenhum lock novo.
    IF NEW.identity_session_id IS NOT NULL THEN
        -- R4b: a reconferencia depois dos locks so le o estado confirmado mais recente em READ COMMITTED
        IF pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
            RAISE EXCEPTION 'sessao de organizacao vinculada exige transaction_isolation=read committed (atual=%)',
                pg_catalog.current_setting('transaction_isolation') USING ERRCODE = '25N01';
        END IF;
    END IF;
    PERFORM 1 FROM public.users u WHERE u.organization_id = NEW.organization_id AND u.user_id = NEW.user_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'sessao nova exige conta visivel no contexto' USING ERRCODE = '23514';
    END IF;
    IF NOT public.identity_org_session_allowed(NEW.organization_id, NEW.user_id, NEW.identity_session_id) THEN
        RAISE EXCEPTION 'sessao nova exige fator de MFA confirmado e ativo'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
