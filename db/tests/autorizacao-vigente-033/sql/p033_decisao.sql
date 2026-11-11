-- SIMULACAO da decisao humana da base C, so para provar o caminho depois da recusa. NAO e procedimento automatico nem
-- recomendacao: a pessoa responsavel escolheu, em cada escopo, qual vigente fica e REVOGOU as demais (o mesmo efeito do
-- "Revogar" da Web: revoked_at e quem revogou; nada e apagado). Executado como administrador do cluster.
\set ON_ERROR_STOP on
SET session_replication_role = replica;
UPDATE local_run_authorizations SET revoked_at = now(), revoked_by_membership_id = '00000033-0000-4000-9009-000000000001'
 WHERE authorization_id IN ('00000033-0000-4000-9007-000000000051', '00000033-0000-4000-9007-000000000052', '00000033-0000-4000-9007-000000000053');
SET session_replication_role = origin;
SELECT 'decisao simulada: revogadas 51, 52 e 53; mantidas 11 e 21 (historico preservado)';
