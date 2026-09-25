-- Nekkora Platform - procedimento inicial (bootstrap) de uma base recem-criada com 001-012.
--
-- Executado UMA vez pelo executor administrativo (o papel de migrations), nunca por um servico.
-- Por que existe: provision_organization exige provisioned_by_user_id de um usuario existente, e todo
-- usuario exige uma organizacao - o primeiro par (organizacao interna da Nekkora + primeiro usuario
-- staff) nao pode nascer pelos caminhos de provisionamento. Tudo o que vem depois usa exclusivamente
-- provision_organization / provision_first_org_admin / provision_resource_limit /
-- provision_retention_policy, com os papeis de servico.
--
-- Uma unica transacao: organizacao interna + primeiro usuario staff + cinco revisoes de limite
-- explicitas (sem valor padrao). Recusa rodar se ja existir qualquer organizacao.
--
-- Nao fica em db/migrations: o entrypoint da imagem nao deve executa-lo automaticamente, e os
-- valores (ids, e-mail, limites) sao decisao operacional de cada ambiente.
--
-- Uso (todas as variaveis obrigatorias):
--   psql -v ON_ERROR_STOP=1 -U <executor> -d nekkora \
--        -v org_id=<uuid> -v org_name='Nekkora' \
--        -v staff_user_id=<uuid> -v staff_email=<email> -v staff_name='<nome>' \
--        -v limites='{"(1,1,0)","(2,1,0)","(3,1,0)","(4,2,)","(5,2,)"}' \
--        -f db/bootstrap/001_organizacao_interna_nekkora.sql
--   limites: resource_limit_input[] - exatamente 5 elementos, resource_code 1..5 sem repeticao;
--   (codigo, 1=FINITE, valor>=0) ou (codigo, 2=UNLIMITED, vazio).
\set ON_ERROR_STOP on
\pset pager off

\if :{?org_id}
\else
  \echo 'ERRO: defina -v org_id=<uuid>'
  DO $$ BEGIN RAISE EXCEPTION 'variavel psql obrigatoria ausente (ver cabecalho)'; END $$;
\endif
\if :{?org_name}
\else
  \echo 'ERRO: defina -v org_name=<nome>'
  DO $$ BEGIN RAISE EXCEPTION 'variavel psql obrigatoria ausente (ver cabecalho)'; END $$;
\endif
\if :{?staff_user_id}
\else
  \echo 'ERRO: defina -v staff_user_id=<uuid>'
  DO $$ BEGIN RAISE EXCEPTION 'variavel psql obrigatoria ausente (ver cabecalho)'; END $$;
\endif
\if :{?staff_email}
\else
  \echo 'ERRO: defina -v staff_email=<email>'
  DO $$ BEGIN RAISE EXCEPTION 'variavel psql obrigatoria ausente (ver cabecalho)'; END $$;
\endif
\if :{?staff_name}
\else
  \echo 'ERRO: defina -v staff_name=<nome>'
  DO $$ BEGIN RAISE EXCEPTION 'variavel psql obrigatoria ausente (ver cabecalho)'; END $$;
\endif
\if :{?limites}
\else
  \echo 'ERRO: defina -v limites=<resource_limit_input[]>'
  DO $$ BEGIN RAISE EXCEPTION 'variavel psql obrigatoria ausente (ver cabecalho)'; END $$;
\endif

BEGIN;

-- Parametros levados para dentro do bloco DO (psql nao interpola variaveis dentro de $$).
SELECT set_config('nekkora_bootstrap.org_id', :'org_id', true) IS NOT NULL AS org_id_ok,
       set_config('nekkora_bootstrap.org_name', :'org_name', true) IS NOT NULL AS org_name_ok,
       set_config('nekkora_bootstrap.staff_user_id', :'staff_user_id', true) IS NOT NULL AS staff_id_ok,
       set_config('nekkora_bootstrap.staff_email', :'staff_email', true) IS NOT NULL AS staff_email_ok,
       set_config('nekkora_bootstrap.staff_name', :'staff_name', true) IS NOT NULL AS staff_name_ok,
       set_config('nekkora_bootstrap.limites', :'limites', true) IS NOT NULL AS limites_ok;

DO $$
DECLARE
    v_org_id UUID := current_setting('nekkora_bootstrap.org_id')::uuid;
    v_org_name TEXT := current_setting('nekkora_bootstrap.org_name');
    v_staff_id UUID := current_setting('nekkora_bootstrap.staff_user_id')::uuid;
    v_staff_email TEXT := current_setting('nekkora_bootstrap.staff_email');
    v_staff_name TEXT := current_setting('nekkora_bootstrap.staff_name');
    v_limites public.resource_limit_input[] := current_setting('nekkora_bootstrap.limites')::public.resource_limit_input[];
    v_elem public.resource_limit_input;
    v_codes INTEGER[];
    v_idx INTEGER := 0;
BEGIN
    -- 0) so o executor administrativo, e so numa base sem nenhuma organizacao
    IF NOT (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
        RAISE EXCEPTION 'bootstrap deve ser executado pelo executor administrativo (current_user=%)', current_user;
    END IF;
    IF EXISTS (SELECT 1 FROM public.organizations) THEN
        RAISE EXCEPTION 'bootstrap recusado: a base ja tem organizacoes - use os caminhos de provisionamento';
    END IF;

    -- 1) mesmas regras de provision_organization (012) para os cinco limites
    IF v_limites IS NULL OR array_ndims(v_limites) IS DISTINCT FROM 1 OR array_length(v_limites, 1) <> 5 THEN
        RAISE EXCEPTION 'limites: exatamente 5 elementos, array unidimensional';
    END IF;
    FOREACH v_elem IN ARRAY v_limites LOOP
        v_idx := v_idx + 1;
        IF v_elem.resource_code IS NULL OR v_elem.limit_mode IS NULL THEN
            RAISE EXCEPTION 'limites: elemento % sem resource_code/limit_mode', v_idx;
        END IF;
        IF v_elem.limit_mode = 1 AND (v_elem.limit_value IS NULL OR v_elem.limit_value < 0) THEN
            RAISE EXCEPTION 'limites: elemento % (resource_code=%) FINITE exige limit_value >= 0', v_idx, v_elem.resource_code;
        ELSIF v_elem.limit_mode = 2 AND v_elem.limit_value IS NOT NULL THEN
            RAISE EXCEPTION 'limites: elemento % (resource_code=%) UNLIMITED exige limit_value vazio', v_idx, v_elem.resource_code;
        ELSIF v_elem.limit_mode NOT IN (1, 2) THEN
            RAISE EXCEPTION 'limites: elemento % limit_mode invalido (%)', v_idx, v_elem.limit_mode;
        END IF;
    END LOOP;
    SELECT array_agg(DISTINCT e.resource_code ORDER BY e.resource_code) INTO v_codes FROM unnest(v_limites) e;
    IF v_codes IS DISTINCT FROM ARRAY[1, 2, 3, 4, 5] THEN
        RAISE EXCEPTION 'limites: resource_code deve cobrir exatamente {1,2,3,4,5} sem repeticao (recebido=%)', v_codes;
    END IF;

    -- 2) organizacao interna, primeiro usuario staff (INTERNAL, ACTIVE), cinco revisoes de limite
    INSERT INTO public.organizations (organization_id, name, status) VALUES (v_org_id, v_org_name, 1);
    INSERT INTO public.users (user_id, organization_id, account_type, email, status, display_name)
    VALUES (v_staff_id, v_org_id, 1, v_staff_email, 2, v_staff_name);
    INSERT INTO public.organization_resource_limit_revisions (organization_id, resource_code, limit_mode, limit_value, provisioned_by_user_id)
    SELECT v_org_id, e.resource_code, e.limit_mode, e.limit_value, v_staff_id
    FROM unnest(v_limites) e ORDER BY e.resource_code;
END;
$$;

COMMIT;

SELECT o.organization_id, o.name, o.status,
       (SELECT count(*) FROM public.users u WHERE u.organization_id = o.organization_id) AS usuarios,
       (SELECT count(*) FROM public.organization_resource_limit_revisions r WHERE r.organization_id = o.organization_id) AS limites
FROM public.organizations o;
