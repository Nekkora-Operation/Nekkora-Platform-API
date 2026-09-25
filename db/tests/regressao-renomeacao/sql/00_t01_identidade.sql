-- T01 - papel: nekkora. Identidade da conexao apos aplicacao limpa de 001-011 (o log do entrypoint
-- e o healthcheck sao coletados pelo run.sh).
\pset pager off
SELECT current_database(), current_user, current_setting('cluster_name') AS cluster_name, version();
SELECT count(*) AS tabelas_public FROM pg_tables WHERE schemaname = 'public';
SELECT count(*) AS triggers_nao_internas, count(*) FILTER (WHERE tgenabled = 'O') AS habilitadas FROM pg_trigger WHERE NOT tgisinternal;
SELECT (SELECT count(*) FROM organizations) + (SELECT count(*) FROM users) AS linhas_de_dados_iniciais;
