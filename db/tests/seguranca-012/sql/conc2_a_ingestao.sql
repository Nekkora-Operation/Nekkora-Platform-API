-- conc2 A - papel: nekkora_ingestion. Insere um evento HOJE e segura a transacao aberta: a barreira
-- (assign_received_at_and_lock_barrier) mantem FOR SHARE na linha de hoje ate o COMMIT.
\pset pager off
SELECT 'A' AS s, current_user, pg_backend_pid(), clock_timestamp() AS a_inicio;
BEGIN;
SET LOCAL nekkora.organization_id = 'aaaaaaaa-0000-0000-0000-00000000000a';
INSERT INTO execution_events (organization_id, workspace_id, execution_id, agent_id, sequence, event_type, occurred_at, payload, fingerprint)
VALUES ('aaaaaaaa-0000-0000-0000-00000000000a', 'aaaaaaaa-0000-0000-0003-000000000001', 'aaaaaaaa-0000-0000-000a-000000000001',
        'aaaaaaaa-0000-0000-0005-000000000001', 20, 5, now(), '{"schema_version":1,"process_started":true,"exit_code":130}', repeat('c2', 32));
SELECT clock_timestamp() AS a_inseriu_nao_confirmado;
SELECT pg_sleep(10);
SELECT clock_timestamp() AS a_antes_commit;
COMMIT;
SELECT clock_timestamp() AS a_depois_commit;
