-- SIMULACAO da decisao humana da base C, so para provar o caminho depois da recusa. NAO e procedimento automatico nem
-- recomendacao: aqui a pessoa responsavel escolheu manter, em cada automacao, um dos rascunhos e retirar os demais por um
-- procedimento aprovado. Executado como administrador do cluster, com os gatilhos desligados so neste passo.
\set ON_ERROR_STOP on
SET session_replication_role = replica;
DELETE FROM automation_versions WHERE automation_version_id IN ('00000033-0000-4000-8004-000000000051', '00000033-0000-4000-8004-000000000052',
                                                                '00000033-0000-4000-8004-000000000063');
SET session_replication_role = origin;
SELECT 'decisao simulada: mantidos 00000033-0000-4000-8004-000000000053 e 00000033-0000-4000-8004-000000000062';
