CREATE OR REPLACE FUNCTION refuse_local_entry_for_linked_account() RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_org UUID;
    v_old JSONB;
    v_new JSONB;
    v_term TEXT[];
    v_limpa TEXT[];
    v_terminal BOOLEAN := false;
    v_bad TEXT;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF TG_ARGV[0] = 'senha' THEN
            IF NEW.password_hash = OLD.password_hash AND NEW.password_changed_at = OLD.password_changed_at THEN
                RETURN NEW;
            END IF;
        ELSE
            v_old := pg_catalog.to_jsonb(OLD);
            v_new := pg_catalog.to_jsonb(NEW);
            v_term := pg_catalog.string_to_array(TG_ARGV[0], ',');
            v_limpa := coalesce(pg_catalog.string_to_array(NULLIF(TG_ARGV[1], ''), ','), ARRAY[]::TEXT[]);
            SELECT coalesce(bool_or(v_old ->> c IS NULL AND v_new ->> c IS NOT NULL), false) INTO v_terminal
              FROM pg_catalog.unnest(v_term) c;
            IF v_terminal THEN
                SELECT string_agg(k, ', ' ORDER BY k) INTO v_bad
                  FROM pg_catalog.jsonb_object_keys(v_new) k
                 WHERE (v_new -> k) IS DISTINCT FROM (v_old -> k)
                   AND NOT (k = ANY (v_term) AND v_old ->> k IS NULL)
                   AND NOT (k = ANY (v_limpa) AND v_new -> k = 'null'::jsonb);
                IF v_bad IS NOT NULL THEN
                    RAISE EXCEPTION 'transicao terminal em % so altera as colunas terminais e a limpeza prevista (alteradas junto: %)',
                        TG_TABLE_NAME, v_bad USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END IF;
        END IF;
    END IF;
    SELECT u.organization_id INTO v_org FROM public.users u WHERE u.user_id = NEW.user_id FOR UPDATE;
    IF v_org IS NULL THEN
        RAISE EXCEPTION 'conta nao encontrada no contexto ao escrever em %', TG_TABLE_NAME USING ERRCODE = '23514';
    END IF;
    IF public.identity_account_is_linked(v_org, NEW.user_id) THEN
        RAISE EXCEPTION 'conta vinculada a identidade nao autentica nem cadastra fator por credencial local (%)', TG_TABLE_NAME
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
