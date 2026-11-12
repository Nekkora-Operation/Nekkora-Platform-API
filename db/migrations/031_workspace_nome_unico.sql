-- Nekkora Platform - nome de workspace unico por organizacao (PLATFORM-V2-001, T03). Contrato: api/openapi/nekkora-api-v1.yaml
-- (POST e PATCH /api/v1/o/{org}/workspaces, 409 workspace_name_taken) e docs/platform-v2-001/t03-workspace-nome.md.
--
-- CANDIDATA ate a aprovacao do PR. Numero 031 conferido em 10/11 contra as branches remotas (nenhuma tinha 031); se outra
-- migration entrar antes, esta e renumerada sem mudar o conteudo.
--
-- O que muda:
--   1. CHAVE do nome: (organization_id, lower(btrim(name))). O nome visivel continua como foi digitado (sem os espacos das
--      extremidades, que a API ja retira); a comparacao ignora maiusculas/minusculas e espacos nas extremidades. Responsavel,
--      criador, cliente e situacao NAO participam da chave: workspace arquivado ou suspenso continua ocupando o nome, e trocar
--      o responsavel nao libera o nome.
--   2. RESTRICAO NO BANCO (indice unico): duas criacoes simultaneas resultam em uma gravacao e um 23505 (a API responde 409).
--      O indice guarda md5(chave), de tamanho fixo: o nome nao tem limite de tamanho no banco (so a API limita a 80), e uma
--      chave longa estouraria a entrada do btree. Chaves iguais sempre tem o mesmo md5; uma colisao de chaves diferentes so
--      produziria um 409 indevido, nunca deixaria passar um nome repetido. Conferencias e diagnostico usam a chave exata.
--   3. Auditoria: automation_admin_events ganha o ato 7 (WORKSPACE_RENAMED).
--
-- DADOS EXISTENTES: se ja houver nomes repetidos numa organizacao, esta migration PARA antes de qualquer alteracao e lista os
-- registros (organizacao, chave, workspaces). Nada e apagado, fundido, renomeado nem tem o responsavel trocado: a decisao e
-- humana. Diagnostico somente leitura, para rodar antes: db/diagnosticos/031_workspaces_nomes_repetidos.sql.
--
-- Papeis: nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo, nenhum privilegio novo: SEM elevacao (como
-- 025, 028 e 030).

-- Transacao UNICA: a leitura de todas as organizacoes na pre-condicao exige que o dono (migrador) atravesse a RLS FORCADA de
-- workspaces; ela e desligada so dentro desta transacao e religada antes do COMMIT (conferido nos invariantes). Qualquer falha
-- (inclusive a recusa por nomes repetidos) desfaz tudo, a RLS forcada inclusive.
BEGIN;

-- === 0. Pre-condicoes ===
ALTER TABLE public.workspaces NO FORCE ROW LEVEL SECURITY;
DO $$
DECLARE
    v_repetidos TEXT;
BEGIN
    IF to_regclass('public.automation_admin_events') IS NULL OR to_regclass('public.run_requests') IS NULL THEN
        RAISE EXCEPTION '031: exige 028 e 030';
    END IF;
    IF to_regclass('public.ux_workspaces_organization_name_key') IS NOT NULL THEN
        RAISE EXCEPTION '031: ja aplicada';
    END IF;
    -- a chave depende da caixa do banco (LC_CTYPE): sem caixa Unicode, "ACAO" e "acao" com acento nao seriam iguais
    IF lower(U&'\00C1\00C7\00C3O \00C4') <> U&'\00E1\00E7\00E3o \00E4' THEN
        RAISE EXCEPTION '031: lower() do banco nao trata letras acentuadas (LC_CTYPE %); a chave do nome nao seria confiavel',
            (SELECT datctype FROM pg_database WHERE datname = current_database());
    END IF;
    SELECT string_agg(format('organizacao %s, chave %L: %s', organization_id, chave, workspaces), E'\n' ORDER BY organization_id, chave)
      INTO v_repetidos
      FROM (SELECT organization_id, lower(btrim(name)) AS chave,
                   string_agg(format('%s (%L, situacao %s)', workspace_id, name, status), ', ' ORDER BY created_at, workspace_id) AS workspaces
              FROM public.workspaces
             GROUP BY organization_id, lower(btrim(name))
            HAVING count(*) > 1) r;
    IF v_repetidos IS NOT NULL THEN
        RAISE EXCEPTION '031: ha workspaces com o mesmo nome na mesma organizacao; nada foi alterado. Decida (renomear ou arquivar e renomear) e aplique de novo'
            USING DETAIL = v_repetidos,
                  HINT = 'diagnostico: db/diagnosticos/031_workspaces_nomes_repetidos.sql';
    END IF;
END;
$$;

-- === 1. Chave do nome ===
CREATE UNIQUE INDEX ux_workspaces_organization_name_key ON public.workspaces (organization_id, md5(lower(btrim(name))));
ALTER TABLE public.workspaces FORCE ROW LEVEL SECURITY;
COMMENT ON INDEX public.ux_workspaces_organization_name_key IS
    'nome de workspace unico por organizacao (031): md5(lower(btrim(name))); situacao, cliente e responsavel nao participam';

-- === 2. Auditoria: ato 7 ===
ALTER TABLE public.automation_admin_events DROP CONSTRAINT automation_admin_events_act_check;
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_act_check CHECK (act BETWEEN 1 AND 7);
-- a 028 exige automacao em todo ato que nao seja 1 (CHECK sem nome): o ato 7, como o 1, e so do workspace
DO $$
DECLARE
    v_nome TEXT;
BEGIN
    SELECT conname INTO STRICT v_nome FROM pg_constraint
     WHERE conrelid = 'public.automation_admin_events'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) = 'CHECK (((act = 1) OR (automation_id IS NOT NULL)))';
    EXECUTE format('ALTER TABLE public.automation_admin_events DROP CONSTRAINT %I', v_nome);
END;
$$;
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_automation_required
    CHECK (act IN (1, 7) OR automation_id IS NOT NULL);
ALTER TABLE public.automation_admin_events ADD CONSTRAINT automation_admin_events_rename_workspace CHECK (act <> 7 OR automation_id IS NULL);
COMMENT ON COLUMN public.automation_admin_events.act IS
    '1=WORKSPACE_CREATED, 2=AUTOMATION_CREATED, 3=VERSION_CREATED, 4=DRAFT_UPDATED, 5=ARTIFACT_RECEIVED, 6=VERSION_PUBLISHED, 7=WORKSPACE_RENAMED';

-- === 3. Invariantes ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                    WHERE c.relname = 'ux_workspaces_organization_name_key' AND i.indisunique AND i.indisvalid) THEN
        RAISE EXCEPTION '031: indice unico do nome ausente ou invalido';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = 'public.workspaces'::regclass AND relrowsecurity AND relforcerowsecurity) THEN
        RAISE EXCEPTION '031: RLS forcada de workspaces nao foi religada';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.workspaces', 'DELETE')
       OR has_table_privilege('nekkora_platform', 'public.automation_admin_events', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.automation_admin_events', 'DELETE') THEN
        RAISE EXCEPTION '031: privilegios de nekkora_platform mudaram';
    END IF;
END;
$$;

COMMIT;
