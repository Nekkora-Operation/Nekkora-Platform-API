-- Nekkora Platform - pacote Python externo: requisito de Python OPCIONAL e versao exata (descritor nekkora-external.json 1.1)
-- (AGENT-CLIENT-PREPARACAO-002, T03). Contrato: docs/agent-client-preparacao-002/t03-requisito-de-python-opcional.md e
-- api/openapi/nekkora-api-v1.yaml (LifecycleArtifact.pythonRequirement e LifecycleArtifact.manifestVersion).
--
-- CANDIDATA ate a aprovacao do PR. Numero 041 conferido em 14/11 contra a dev (2e03383; a maior era 040) e os PRs abertos
-- (nenhum traz migration). As ferramentas do laboratorio e da homologacao exigem numeracao contigua: se outra 041 entrar
-- antes, esta e renumerada sem mudar o conteudo.
--
-- O QUE FAZ: so troca dois CHECKs de automation_artifacts (a tabela, as colunas e os dados ficam como estao).
--   1. automation_artifacts_python_requirement_check: alem das formas do descritor 1.0
--      ((>=|==|~=)X.Y, com ".*" e ",<X.Y" opcionais), aceita a versao EXATA "==X.Y.Z" (descritor 1.1).
--   2. automation_artifacts_kind_rule, so no ramo do pacote externo (package_kind = 2):
--        - descritor 1.0.x: como era - python_requirement obrigatorio, e nunca a versao exata (ela nao existe na 1.0);
--        - descritor 1.1.x: python_requirement pode ser NULL. NULL quer dizer "o autor nao declarou restricao de Python".
--          Nao e gravada faixa arbitraria, ">=0" nem versao ficticia no lugar.
--      O ramo do pacote do Studio (package_kind = 1) nao muda.
--
-- O QUE NAO FAZ: nenhuma tabela, coluna, funcao, papel, dono, politica ou privilegio novo; nenhuma linha alterada.
--
-- DADOS EXISTENTES: todo artefato gravado ate aqui satisfaz os CHECKs novos (eles so ampliam o que e aceito): os externos
-- sao 1.0.x com requisito nas formas da 1.0. O ADD CONSTRAINT confere todas as linhas e falha, sem alterar nada, se nao for.
-- Diagnostico somente leitura, para rodar antes (como migrador ou superusuario; a tabela tem RLS forcada):
--   SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conrelid = 'public.automation_artifacts'::regclass
--      AND conname IN ('automation_artifacts_kind_rule', 'automation_artifacts_python_requirement_check');
--   SELECT package_kind, manifest_version, python_requirement IS NULL AS sem_requisito, count(*)
--     FROM public.automation_artifacts GROUP BY 1, 2, 3 ORDER BY 1, 2, 3;
--
-- COMPATIBILIDADE: consumidores que so conhecem o descritor 1.0 (a API anterior, o Nekkora Agent 9b1e7e1 e o
-- Nekkora-Agent-Client ate 0b89357) RECUSAM um pacote 1.1 como fora do contrato; nenhum deles o executa ignorando o campo.
-- Um pacote 1.0 continua valendo em todos, sem mudanca de um byte.
--
-- REVERSAO (manual, so enquanto nao houver artefato 1.1 gravado; o artefato e imutavel, entao depois disso a reversao exige
-- decisao humana sobre as linhas e nao e oferecida aqui):
--   BEGIN;
--   ALTER TABLE public.automation_artifacts DROP CONSTRAINT automation_artifacts_kind_rule;
--   ALTER TABLE public.automation_artifacts DROP CONSTRAINT automation_artifacts_python_requirement_check;
--   ALTER TABLE public.automation_artifacts ADD CONSTRAINT automation_artifacts_python_requirement_check
--       CHECK (python_requirement ~ '^(>=|==|~=)[0-9]{1,2}\.[0-9]{1,2}(\.\*)?(,<[0-9]{1,2}\.[0-9]{1,2})?$');
--   ALTER TABLE public.automation_artifacts ADD CONSTRAINT automation_artifacts_kind_rule CHECK (
--          (package_kind = 1 AND fingerprint_rule = 'nekkora-agent/package-fingerprint/1' AND manifest_version ~ '^1\.[4-9]\.[0-9]{1,3}$'
--               AND framework IS NOT NULL AND execution_mode IS NOT NULL
--               AND python_requirement IS NULL AND dependencies IS NULL AND package_name IS NULL AND package_version IS NULL)
--       OR (package_kind = 2 AND fingerprint_rule = 'nekkora-agent/external-python-fingerprint/1' AND manifest_version ~ '^1\.0\.[0-9]{1,3}$'
--               AND framework IS NULL AND execution_mode IS NULL
--               AND python_requirement IS NOT NULL AND dependencies IS NOT NULL AND package_name IS NOT NULL AND package_version IS NOT NULL));
--   COMMIT;
--   (os ADD CONSTRAINT falham, sem alterar nada, se ja existir artefato 1.1.) A API anterior recusa o envio de pacotes 1.1.
--
-- SEM ELEVACAO: so ALTER TABLE de constraints, pelo dono da tabela (como a 030, a 038 e a 040).
-- Nenhum papel novo, nenhuma funcao SECURITY DEFINER, nenhum dono novo, nenhum privilegio novo.

BEGIN;

-- === 1. Pre-condicao: 040 aplicada; os dois CHECKs sao os da 030 ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint c
                    WHERE c.conrelid = 'public.automation_admin_events'::regclass AND c.conname = 'automation_admin_events_package_download') THEN
        RAISE EXCEPTION '041 exige a 040 aplicada';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.automation_artifacts'::regclass AND c.conname = 'automation_artifacts_python_requirement_check')
       IS DISTINCT FROM 'CHECK ((python_requirement ~ ''^(>=|==|~=)[0-9]{1,2}\.[0-9]{1,2}(\.\*)?(,<[0-9]{1,2}\.[0-9]{1,2})?$''::text))' THEN
        RAISE EXCEPTION '041: CHECK automation_artifacts_python_requirement_check diferente do da 030';
    END IF;
    IF (SELECT position('(python_requirement IS NOT NULL)' IN pg_get_constraintdef(c.oid)) = 0
               OR position('1\.1\.' IN pg_get_constraintdef(c.oid)) > 0
          FROM pg_constraint c
         WHERE c.conrelid = 'public.automation_artifacts'::regclass AND c.conname = 'automation_artifacts_kind_rule') IS NOT FALSE THEN
        RAISE EXCEPTION '041: CHECK automation_artifacts_kind_rule diferente do da 030';
    END IF;
END;
$$;

-- === 2. Requisito: as formas da 1.0 ou a versao exata da 1.1 ===
ALTER TABLE public.automation_artifacts DROP CONSTRAINT automation_artifacts_kind_rule;
ALTER TABLE public.automation_artifacts DROP CONSTRAINT automation_artifacts_python_requirement_check;
ALTER TABLE public.automation_artifacts ADD CONSTRAINT automation_artifacts_python_requirement_check CHECK (
    python_requirement ~ '^((>=|==|~=)[0-9]{1,2}\.[0-9]{1,2}(\.\*)?(,<[0-9]{1,2}\.[0-9]{1,2})?|==[0-9]{1,2}\.[0-9]{1,2}\.[0-9]{1,3})$');

-- === 3. Tipo x descritor: externo 1.0 exige o requisito (formas da 1.0); externo 1.1 aceita a ausencia ===
ALTER TABLE public.automation_artifacts ADD CONSTRAINT automation_artifacts_kind_rule CHECK (
       (package_kind = 1 AND fingerprint_rule = 'nekkora-agent/package-fingerprint/1' AND manifest_version ~ '^1\.[4-9]\.[0-9]{1,3}$'
            AND framework IS NOT NULL AND execution_mode IS NOT NULL
            AND python_requirement IS NULL AND dependencies IS NULL AND package_name IS NULL AND package_version IS NULL)
    OR (package_kind = 2 AND fingerprint_rule = 'nekkora-agent/external-python-fingerprint/1'
            AND framework IS NULL AND execution_mode IS NULL
            AND dependencies IS NOT NULL AND package_name IS NOT NULL AND package_version IS NOT NULL
            AND (   (manifest_version ~ '^1\.0\.[0-9]{1,3}$' AND python_requirement IS NOT NULL
                         AND python_requirement !~ '^==[0-9]{1,2}\.[0-9]{1,2}\.[0-9]{1,3}$')
                 OR manifest_version ~ '^1\.1\.[0-9]{1,3}$')));
COMMENT ON COLUMN public.automation_artifacts.python_requirement IS
    'So EXTERNAL_PYTHON. Requisito de Python declarado no descritor. NULL (so no descritor 1.1) = o autor nao declarou restricao; '
    'nao e promessa de compatibilidade com qualquer Python.';

-- === 4. Invariantes: os CHECKs novos; nada mais mudou na tabela (RLS forcada, sem privilegio novo) ===
DO $$
DECLARE
    regra text;
BEGIN
    SELECT pg_get_constraintdef(c.oid) INTO regra FROM pg_constraint c
     WHERE c.conrelid = 'public.automation_artifacts'::regclass AND c.conname = 'automation_artifacts_kind_rule';
    IF regra IS NULL OR position('1\.1\.' IN regra) = 0 OR position('(python_requirement IS NOT NULL)' IN regra) = 0 THEN
        RAISE EXCEPTION '041: CHECK automation_artifacts_kind_rule novo diferente do esperado';
    END IF;
    IF (SELECT position('==[0-9]{1,2}\.[0-9]{1,2}\.[0-9]{1,3}' IN pg_get_constraintdef(c.oid)) FROM pg_constraint c
         WHERE c.conrelid = 'public.automation_artifacts'::regclass AND c.conname = 'automation_artifacts_python_requirement_check') IS NOT DISTINCT FROM 0 THEN
        RAISE EXCEPTION '041: CHECK automation_artifacts_python_requirement_check novo diferente do esperado';
    END IF;
    IF NOT (SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'public.automation_artifacts'::regclass) THEN
        RAISE EXCEPTION '041: automation_artifacts sem RLS forcada';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.automation_artifacts', 'UPDATE')
       OR has_table_privilege('nekkora_platform', 'public.automation_artifacts', 'DELETE')
       OR has_table_privilege('nekkora_platform', 'public.automation_artifacts', 'TRUNCATE') THEN
        RAISE EXCEPTION '041: a plataforma tem escrita alem do INSERT em automation_artifacts';
    END IF;
END;
$$;

COMMIT;
