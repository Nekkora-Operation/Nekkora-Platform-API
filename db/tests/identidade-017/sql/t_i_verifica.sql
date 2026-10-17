-- t_i_verifica - papel: nekkora (superusuario, so leitura): o que a Platform nao le. identity_users da identidade ivo
-- contem exatamente a conta criada, com link_channel cadastro_web, e o evento de provisionamento e o da 012.
\pset pager off
SELECT pg_temp.afirma((SELECT count(*) FROM identity_users iu WHERE iu.identity_id = pg_temp.identidade('ivo@ex.test')) = 1
    AND NOT EXISTS (SELECT 1 FROM identity_users iu JOIN users u ON u.user_id = iu.user_id WHERE u.email = 'ivo@ex.test' AND u.organization_id = :'o1')
    AND (SELECT iu.link_channel FROM identity_users iu WHERE iu.identity_id = pg_temp.identidade('ivo@ex.test')) = 'cadastro_web',
    'I-1/I-2 identity_users da identidade ivo: so a conta criada');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_provisioning_events e JOIN identity_users iu ON iu.user_id = e.provisioned_by_user_id
                       WHERE iu.identity_id = pg_temp.identidade('ivo@ex.test') AND e.channel = 'cadastro_web') = 1, 'I-2 evento de provisionamento cadastro_web');
SELECT pg_temp.afirma((SELECT count(*) FROM identity_users WHERE identity_id = pg_temp.identidade('c19@ex.test') AND link_channel = 'cadastro_web') = 10, 'I-5 teto');
SELECT 'grupo I (verificacao): ok' AS resultado;
