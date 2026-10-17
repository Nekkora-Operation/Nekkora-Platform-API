-- Grupo T (R5, 2026-10-14) - papel: nekkora_platform, SQL direto.
-- identity_org_creation_allowed: SO indicacao para a interface (canCreateOrganization). A criacao continua reconferindo
-- tudo dentro de identity_create_organization. E os eventos 13 (STEP_UP) e 14 (ORG_STEP_UP) aceitos em identity_events.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_platform', 'papel nekkora_platform');
CREATE TEMP TABLE t AS SELECT pg_temp.nova_identidade('t1@ex.test') AS id1, pg_temp.nova_identidade('t2@ex.test') AS id2,
                              pg_temp.nova_identidade('t3@ex.test') AS id3, pg_temp.nova_identidade('t4@ex.test') AS id4;

-- T-1: controle positivo - sessao ativa, nao restrita, identidade ACTIVE e verificada, sem organizacao criada
SELECT pg_temp.nova_sessao_id((SELECT id1 FROM t), 'T/comum');
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/comum')), 'T-1 sessao comum: pode criar');
-- T-2: token nulo, fora do formato ou desconhecido -> falso, sem erro
SELECT pg_temp.afirma(identity_org_creation_allowed(NULL) IS FALSE, 'T-2 token nulo');
SELECT pg_temp.afirma(identity_org_creation_allowed('nao-e-token') IS FALSE, 'T-2 token fora do formato');
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/inexistente')) IS FALSE, 'T-2 token desconhecido');
-- T-3: sessao EXPIRADA (prazo de 1 s, vencido)
SELECT pg_temp.nova_sessao_id((SELECT id1 FROM t), 'T/curta', false, false, interval '1 second');
SELECT pg_sleep(1.5);
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/curta')) IS FALSE, 'T-3 sessao expirada');
-- T-4: sessao REVOGADA (logout)
SELECT pg_temp.nova_sessao_id((SELECT id1 FROM t), 'T/revogada');
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/revogada')), 'T-4 antes da revogacao: pode');
UPDATE identity_sessions SET revoked_at = now() WHERE identity_session_id = pg_temp.sessao_id('T/revogada');
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/revogada')) IS FALSE, 'T-4 sessao revogada');
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/comum')), 'T-4 controle: a outra sessao continua');
-- T-5: sessao RESTRITA a recuperacao (DC-17.13), mesmo com step-up gravado
SELECT pg_temp.nova_sessao_id((SELECT id2 FROM t), 'T/restrita', false, true);
UPDATE identity_sessions SET mfa_step_up_at = now() WHERE identity_session_id = pg_temp.sessao_id('T/restrita');
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/restrita')) IS FALSE, 'T-5 sessao restrita');
-- T-6: identidade SUSPENSA com sessao ainda ativa
SELECT pg_temp.nova_sessao_id((SELECT id3 FROM t), 'T/suspensa');
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/suspensa')), 'T-6 antes da suspensao: pode');
SELECT pg_temp.como((SELECT id3 FROM t));
UPDATE identities SET status = 3 WHERE identity_id = (SELECT id3 FROM t);
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/suspensa')) IS FALSE, 'T-6 identidade suspensa');
-- T-7: teto de 10 organizacoes criadas pelo cadastro. A consulta acompanha o teto; a criacao reconfere por conta propria
SELECT pg_temp.nova_sessao_id((SELECT id4 FROM t), 'T/teto', true);
DO $$
BEGIN
    FOR n IN 1..10 LOOP
        PERFORM pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/teto')), format('T-7 antes da criacao %s: pode', n));
        PERFORM pg_temp.afirma(identity_create_organization(pg_temp.tok('T/teto'), format('Org T-7 %s', n), NULL) IS NOT NULL, format('T-7 criacao %s', n));
    END LOOP;
END $$;
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/teto')) IS FALSE, 'T-7 teto atingido: nao pode');
SELECT pg_temp.afirma(identity_create_organization(pg_temp.tok('T/teto'), 'Org T-7 11', NULL) IS NULL, 'T-7 a criacao recusa por conta propria');
-- T-8: so indicacao - verdadeiro na consulta NAO dispensa o step-up exigido pela criacao
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/comum')), 'T-8 consulta: pode');
SELECT pg_temp.afirma(identity_create_organization(pg_temp.tok('T/comum'), 'Org T-8', NULL) IS NULL, 'T-8 sem step-up a criacao recusa');
-- T-9: so leitura - responde dentro de transacao READ ONLY, e nao trava a identidade
BEGIN READ ONLY;
SELECT pg_temp.afirma(identity_org_creation_allowed(pg_temp.tok('T/comum')), 'T-9 em transacao READ ONLY');
COMMIT;
SELECT pg_temp.afirma((SELECT p.provolatile = 's' AND p.prosecdef FROM pg_proc p WHERE p.oid = 'public.identity_org_creation_allowed(text)'::regprocedure),
                      'T-9 STABLE e SECURITY DEFINER');
\! sh /tmp/e.sh T-9

-- T-E: eventos 13 e 14 aceitos; fora de 1..14 recusado (CHECK)
SELECT pg_temp.como((SELECT id1 FROM t));
INSERT INTO identity_events (identity_id, event_type, correlation_id) VALUES ((SELECT id1 FROM t), 13, gen_random_uuid());
INSERT INTO identity_events (identity_id, event_type, target_organization_id, correlation_id)
VALUES ((SELECT id1 FROM t), 14, :'o1', gen_random_uuid());
-- (a Platform so tem INSERT em identity_events: a contagem e conferida pelo superusuario, no run.sh)
SELECT pg_temp.espera_erro(format('INSERT INTO identity_events (identity_id, event_type, correlation_id) VALUES (%L, 15, gen_random_uuid())', (SELECT id1 FROM t)),
                           '23514', 'identity_events_event_type_check');
SELECT pg_temp.espera_erro(format('INSERT INTO identity_events (identity_id, event_type, correlation_id) VALUES (%L, 0, gen_random_uuid())', (SELECT id1 FROM t)),
                           '23514', 'identity_events_event_type_check');
\! sh /tmp/e.sh T-E
SELECT 'grupo T: ok' AS resultado;
