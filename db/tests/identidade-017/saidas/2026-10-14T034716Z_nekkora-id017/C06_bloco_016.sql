    UPDATE public.auth_challenges SET invalidated_at = v_agora, credential_hash = NULL, pending_password_hash = NULL
     WHERE organization_id = v_org AND user_id = v_target AND consumed_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.user_mfa_recovery_codes SET invalidated_at = v_agora
     WHERE organization_id = v_org AND user_id = v_target AND used_at IS NULL AND invalidated_at IS NULL;
    UPDATE public.user_mfa_factors SET disabled_at = v_agora
     WHERE organization_id = v_org AND user_id = v_target AND disabled_at IS NULL;
    GET DIAGNOSTICS v_n = ROW_COUNT;
    IF v_n <> 1 THEN
        RAISE EXCEPTION 'execucao: fator aprovado nao desabilitado' USING ERRCODE = 'P0001';
    END IF;
    UPDATE public.user_sessions SET revoked_at = v_agora
     WHERE organization_id = v_org AND user_id = v_target AND revoked_at IS NULL;
