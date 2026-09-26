-- t04a - papel: nekkora_portal (LOGIN real; chamadas por bind). Teste 4(a) do desenho e grupos
-- "Estados"/"Sessao e atividade" da missao: sessao invalida -> NK401 com mensagem fixa, em todos os
-- casos, sem distinguir a causa. O t04_verifica (superusuario) confere depois que NENHUMA dessas
-- sessoes teve last_seen_at alterado (sessao invalida nao atualiza timestamps nem revive).
-- Roda tambem contra a mutacao m3 (toque antes da validacao) e PRECISA falhar nela.
-- Gerado por laco; editar o gerador em vez dos casos um a um.
\pset pager off
SELECT pg_temp.afirma(current_user = 'nekkora_portal' AND session_user = 'nekkora_portal', 'papel real nekkora_portal (sem SET ROLE)');
-- tokens sinteticos (nao sao credenciais): formato invalido ou valido-mas-inexistente
SELECT repeat('A', 42) || 'B' AS tk_nao_canonico, repeat('A', 42) || '+' AS tk_char_invalido, repeat('A', 44) AS tk_44,
       'abc' AS tk_curto, repeat('Q', 42) || 'A' AS tk_inexistente,
       encode(sha256(convert_to(:'tok_x', 'UTF8')), 'hex') AS tk_hash_de_x \gset

\echo === formato do token (validado antes do hash)
\set ON_ERROR_STOP off
:q_ws \bind :o1 '' \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sem token (NULL)');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tk_curto \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'token curto');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tk_44 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', '44 caracteres');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tk_char_invalido \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', '43 caracteres com caractere fora do base64url');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tk_nao_canonico \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', '43 caracteres base64url nao canonico para 32 bytes (ultimo caractere)');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tk_hash_de_x \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'hash SHA-256 de um token valido usado como credencial');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tk_inexistente \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'formato valido, sessao inexistente');

\echo === sessao revogada, expirada (12 h), inativa (> 15 min) - nas 4 funcoes
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_xrev \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao revogada (ws)');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_xexp \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao com prazo absoluto de 12 h vencido, atividade recente (ws)');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_xina \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao inativa ha 16 min (ws)');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_xrev '' '' '' '' '' '' 100 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao revogada (ex)');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_xexp '' '' '' '' '' '' 100 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao com prazo absoluto de 12 h vencido, atividade recente (ex)');
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_xina '' '' '' '' '' '' 100 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao inativa ha 16 min (ex)');
\set ON_ERROR_STOP off
:q_get \bind :o1 :tok_xrev :e12 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao revogada (get)');
\set ON_ERROR_STOP off
:q_get \bind :o1 :tok_xexp :e12 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao com prazo absoluto de 12 h vencido, atividade recente (get)');
\set ON_ERROR_STOP off
:q_get \bind :o1 :tok_xina :e12 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao inativa ha 16 min (get)');
\set ON_ERROR_STOP off
:q_ev \bind :o1 :tok_xrev :e15 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao revogada (ev)');
\set ON_ERROR_STOP off
:q_ev \bind :o1 :tok_xexp :e15 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao com prazo absoluto de 12 h vencido, atividade recente (ev)');
\set ON_ERROR_STOP off
:q_ev \bind :o1 :tok_xina :e15 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao inativa ha 16 min (ev)');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_xb15 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao inativa ha 15 min e 5 s (limite)');

\echo === conta, vinculo, cliente, organizacao
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_adm \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'conta interna (account_type = 1)');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_us \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'conta suspensa (users.status = 3)');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_un \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'e-mail nao verificado');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_ms \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'vinculo suspenso (client_memberships.status = 3)');
\set ON_ERROR_STOP off
:q_ws \bind :o1 :tok_q \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'cliente suspenso (clients.status = 2)');
\set ON_ERROR_STOP off
:q_ws \bind :o3 :tok_r \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'organizacao suspensa (O3)');
\set ON_ERROR_STOP off
:q_ex \bind :o3 :tok_r '' '' '' '' '' '' 100 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'organizacao suspensa (O3), list_executions');

\echo === autenticacao antes da validacao de parametros: sessao invalida + p_limit invalido -> NK401 (nao 22023)
\set ON_ERROR_STOP off
:q_ex \bind :o1 :tok_xrev '' '' '' '' '' '' 0 \gset r_
\set ON_ERROR_STOP on
SELECT pg_temp.nk401(:'SQLSTATE', :'LAST_ERROR_MESSAGE', 'sessao revogada com p_limit = 0');

\echo === controle: limite interno da janela de inatividade (14 min) continua valido
:q_ws \bind :o1 :tok_xb14 \gset r_
SELECT pg_temp.afirma(:'r_n' = '1' AND :'r_ids' = :'ws1', 'sessao com ultima atividade ha 14 min: valida (WS1)');
