-- H03 - estado de privilegios da homologacao depois da implantacao - papel: admin_cluster (somente leitura).
\pset pager off
SELECT 'atributos|' || rolname || '|login=' || rolcanlogin || '|super=' || rolsuper || '|createrole=' || rolcreaterole || '|createdb=' || rolcreatedb
       || '|bypassrls=' || rolbypassrls || '|replication=' || rolreplication || '|config=' || coalesce(array_to_string(rolconfig, ';'), '-')
FROM pg_roles WHERE rolname NOT LIKE 'pg\_%' ORDER BY rolname;
SELECT 'membership|' || pg_get_userbyid(roleid) || '->' || pg_get_userbyid(member) || '|admin=' || admin_option || '|inherit=' || inherit_option || '|set=' || set_option
FROM pg_auth_members WHERE pg_get_userbyid(member) NOT LIKE 'pg\_%' ORDER BY 1;
SELECT 'senha_nao_scram|' || rolname FROM pg_authid WHERE rolpassword IS NOT NULL AND rolpassword NOT LIKE 'SCRAM-SHA-256$%';
SELECT 'login_com_senha|' || string_agg(rolname, ',' ORDER BY rolname) FROM pg_authid WHERE rolcanlogin AND rolpassword IS NOT NULL;
SELECT 'dono_banco|' || pg_get_userbyid(datdba) FROM pg_database WHERE datname = 'nekkora';
SELECT 'donos|' || tipo || '|' || dono || '|' || n FROM (
    SELECT 'tabela' AS tipo, pg_get_userbyid(c.relowner) AS dono, count(*) AS n FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace
    WHERE s.nspname = 'public' AND c.relkind IN ('r', 'p') GROUP BY 2
    UNION ALL
    SELECT CASE WHEN p.prosecdef THEN 'funcao_secdef' ELSE 'funcao' END, pg_get_userbyid(p.proowner), count(*) FROM pg_proc p
    JOIN pg_namespace s ON s.oid = p.pronamespace WHERE s.nspname = 'public' GROUP BY 1, 2) t ORDER BY 1;
SELECT 'hba|' || line_number || '|' || type || '|' || array_to_string(database, ',') || '|' || array_to_string(user_name, ',') || '|'
       || coalesce(address, '-') || '|' || coalesce(auth_method, '-') || '|' || coalesce(error, 'ok')
FROM pg_hba_file_rules ORDER BY line_number;
SELECT 'config|' || name || '=' || setting FROM pg_settings WHERE name IN ('ssl', 'ssl_min_protocol_version', 'password_encryption', 'listen_addresses', 'log_statement',
       'log_parameter_max_length', 'log_parameter_max_length_on_error') ORDER BY name;
SELECT 'conexao_servico|' || a.usename || '|' || host(a.client_addr) || '|ssl=' || s.ssl || '|' || coalesce(s.version, '-') || '|n=' || count(*)
FROM pg_stat_activity a JOIN pg_stat_ssl s ON s.pid = a.pid
WHERE a.usename IN ('nekkora_platform', 'nekkora_portal') GROUP BY a.usename, a.client_addr, s.ssl, s.version ORDER BY 1;
