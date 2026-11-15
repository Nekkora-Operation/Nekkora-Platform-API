-- Nekkora Platform - auditoria do login do aplicativo desktop pelo navegador (AGENT-CLIENT-LOGIN-001, T02). Contrato:
-- docs/agent-client-login-001/t02-login-do-aplicativo-desktop.md e api/openapi/nekkora-api-v1.yaml (/api/v1/desktop/...).
--
-- CANDIDATA ate a aprovacao do PR. Numero 037 conferido em 13/11 contra a dev (01194ef; a maior era 036) e os PRs abertos
-- (nenhum na API). As ferramentas do laboratorio e da homologacao exigem numeracao contigua: se outra migration ocupar 037
-- antes, esta e renumerada sem mudar o conteudo.
--
-- O QUE FAZ: so amplia o CHECK de identity_events.event_type, de 1-20 para 1-23, com tres eventos novos:
--   21 = DESKTOP_LOGIN_AUTHORIZED  - a pessoa, com a sessao de identidade, autorizou a entrada no aplicativo (codigo emitido);
--   22 = DESKTOP_SESSION_ISSUED    - o codigo foi trocado e a sessao do aplicativo emitida;
--   23 = DESKTOP_SESSION_REVOKED   - o aplicativo encerrou a propria sessao.
-- Os eventos nao levam codigo, verificador PKCE, token nem state: so identidade, tipo e correlacao, como os demais.
--
-- O QUE NAO FAZ: nenhuma tabela, coluna, funcao, papel, dono, politica ou privilegio novo. O pedido, o codigo e a sessao do
-- aplicativo (no maximo 15 min) ficam no Redis; a sessao do aplicativo so vale enquanto a sessao de identidade que a
-- autorizou continuar ativa nesta base, conferida a cada uso sob a RLS de identidade que ja existe. A plataforma grava os
-- eventos com o INSERT de coluna que a 017 ja concedeu a nekkora_platform.
--
-- SEM ELEVACAO: so ALTER TABLE de uma constraint e um COMMENT, pelo dono da tabela (como o trecho de eventos da 029, que
-- so precisou da janela por causa das funcoes que redefiniu).

BEGIN;

-- === 1. Pre-condicao: 036 aplicada; o CHECK e o da 029 ===
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policy WHERE polname = 'machine_catalog_read' AND polrelid = 'public.automation_artifacts'::regclass) THEN
        RAISE EXCEPTION '037 exige a 036 aplicada';
    END IF;
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.identity_events'::regclass AND c.conname = 'identity_events_event_type_check')
       IS DISTINCT FROM 'CHECK (((event_type >= 1) AND (event_type <= 20)))' THEN
        RAISE EXCEPTION '037: CHECK identity_events_event_type_check diferente da 029';
    END IF;
END;
$$;

-- === 2. Eventos 21-23 ===
ALTER TABLE public.identity_events DROP CONSTRAINT identity_events_event_type_check;
ALTER TABLE public.identity_events ADD CONSTRAINT identity_events_event_type_check CHECK (event_type BETWEEN 1 AND 23);
COMMENT ON COLUMN public.identity_events.event_type IS '1=SIGNUP_REQUESTED, 2=SIGNUP_CONFIRMED, 3=MFA_ENROLLED, 4=MFA_VERIFIED, 5=RECOVERY_CODE_USED, '
    '6=ORG_CREATED, 7=ORG_SESSION_ISSUED, 8=ORG_SESSION_DENIED, 9=IDENTITY_LINKED, 10=LOGOUT, 11=MFA_REPLACED, 12=PASSWORD_CHANGED, '
    '13=STEP_UP, 14=ORG_STEP_UP, 15=RECOVERY_CODES_REGENERATED, 16=PASSWORD_RECOVERY_REQUESTED, 17=PASSWORD_RESET, '
    '18=LOGIN_WITHOUT_SECOND_FACTOR, 19=PASSWORD_RECONFIRMED, 20=MFA_ACTIVATED, '
    '21=DESKTOP_LOGIN_AUTHORIZED, 22=DESKTOP_SESSION_ISSUED, 23=DESKTOP_SESSION_REVOKED';

-- === 3. Invariantes: o CHECK novo; nada mais mudou na tabela (continua append-only, com RLS forcada) ===
DO $$
BEGIN
    IF (SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
         WHERE c.conrelid = 'public.identity_events'::regclass AND c.conname = 'identity_events_event_type_check')
       IS DISTINCT FROM 'CHECK (((event_type >= 1) AND (event_type <= 23)))' THEN
        RAISE EXCEPTION '037: CHECK novo diferente do esperado';
    END IF;
    IF NOT (SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'public.identity_events'::regclass) THEN
        RAISE EXCEPTION '037: identity_events sem RLS forcada';
    END IF;
    IF (SELECT count(*) FROM pg_trigger WHERE tgrelid = 'public.identity_events'::regclass AND NOT tgisinternal
          AND tgname IN ('trg_identity_events_prevent_update', 'trg_identity_events_prevent_delete', 'trg_identity_events_prevent_truncate')) <> 3 THEN
        RAISE EXCEPTION '037: identity_events deixou de ser append-only';
    END IF;
    IF has_table_privilege('nekkora_platform', 'public.identity_events', 'UPDATE, DELETE, TRUNCATE') THEN
        RAISE EXCEPTION '037: a plataforma ganhou escrita alem do INSERT em identity_events';
    END IF;
END;
$$;

COMMIT;
