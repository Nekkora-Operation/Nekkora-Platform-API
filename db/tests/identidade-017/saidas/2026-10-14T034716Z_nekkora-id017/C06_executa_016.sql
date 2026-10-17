SET ROLE nekkora_ops_mfa_owner;
DO $$ DECLARE v_org UUID := '17000000-0000-0000-0000-000000000001'; v_target UUID := '12896fc8-db3a-4cff-94cf-f214f5879e64'; v_agora TIMESTAMPTZ := clock_timestamp(); v_n INTEGER; BEGIN
PERFORM set_config('nekkora.fn_organization_id', v_org::text, true);
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
END $$;
RESET ROLE;
