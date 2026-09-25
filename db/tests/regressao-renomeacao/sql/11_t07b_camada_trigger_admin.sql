-- T07 (informativo, segunda camada) - papel: nekkora (superusuario, ignora privilegios).
-- Mostra que, mesmo sem a barreira de privilegio, as triggers rejeitam. Tudo dentro de
-- transacoes com ROLLBACK para nao alterar o estado da fixture.
\pset pager off
SELECT current_user AS papel;
BEGIN; UPDATE execution_events SET fingerprint = repeat('99',32) WHERE sequence = 1; ROLLBACK;
BEGIN; DELETE FROM execution_events WHERE sequence = 1; ROLLBACK;
BEGIN; TRUNCATE execution_events CASCADE; ROLLBACK;
BEGIN; UPDATE logs SET fingerprint = 'x' WHERE log_id = 'ffffffff-0000-0000-0000-000000000001'; ROLLBACK;
BEGIN; DELETE FROM logs WHERE log_id = 'ffffffff-0000-0000-0000-000000000001'; ROLLBACK;
BEGIN; TRUNCATE logs; ROLLBACK;
SELECT (SELECT count(*) FROM execution_events) AS eventos, (SELECT count(*) FROM logs) AS logs;
