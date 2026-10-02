-- FIXTURE TEMPORAL DECLARADA - solicitacao (desenho P1a, sec. 5.3; SO plano B8b). SO ambiente ensaio-*, SO a solicitacao
-- indicada da organizacao sintetica da 016. Papel: admin_cluster, pela rede (job "ferramentas", TLS verify-full).
--
-- Altera EXCLUSIVAMENTE, nas linhas request_id = :solicitacao, os campos (deslocamento uniforme de -:horas h):
--   ops.ops_mfa_recovery_requests  opened_at, t_total, verified_at, preapproved_at, notice_confirmed_at, final_approved_at,
--                                  executed_at, closed_at
--   ops.ops_mfa_notices            created_at, issued_at, state_at, claimed_at, accepted_at
--   ops.ops_mfa_notice_tokens      issued_at, valid_until, used_at, invalidated_at
--   ops.ops_mfa_approval_attempts  accepted_at, approved_at
-- NAO altera ops.ops_mfa_request_admins (captured_at): a fotografia e comparada com fatos externos (sessoes, fatores,
-- codigos) que a fixture nao move (mesma regra de OpsBanco.recuarSolicitacao).
--
-- CONTROLE DE TRANSACAO: identico ao de fixture_016_contatos.sql (BEGIN/COMMIT neste arquivo, psql -v ON_ERROR_STOP=1,
-- SEM --single-transaction; verificacoes que provocam rollback ANTES do COMMIT; depois dele, so persistencia e
-- session_replication_role = origin).
--
-- -v: ambiente, org, solicitacao (uuid), horas (inteiro 1..72), ip_servidor.
-- CLASSIFICACAO pelo runner (homologacao.sh fixture): antes do BEGIN, este arquivo imprime o estado de partida dos
-- campos alvo (linhas ANTES|..., fixture_016_estado.sql); logo depois do COMMIT confirmado, imprime a marca
-- FIXTURE_COMMIT_CONCLUIDO (so alcancada se o COMMIT retornou sucesso). Sem a marca e com erro do servidor antes
-- dela: transacao desfeita. Com a marca e falha depois: alteracao aplicada, conferencia pos-commit falhou. Sem a
-- marca e sem erro do servidor (conexao perdida, resposta do COMMIT perdida): INDETERMINADA -> reconciliacao
-- (homologacao.sh fixture <id> conferir <execucao>) comparando o estado atual com as linhas ANTES.
\set ON_ERROR_STOP on
\set tipo solicitacao
\set prefixo ANTES
\ir fixture_016_estado.sql
BEGIN;
CREATE TEMP TABLE fx_param ON COMMIT PRESERVE ROWS AS
SELECT :'ambiente'::text AS ambiente, :'org'::uuid AS org, :'solicitacao'::uuid AS req, :'horas'::int AS horas,
       make_interval(hours => :'horas'::int) AS desloc, :'ip_servidor'::inet AS ip;

-- (1) isolamento
DO $$
DECLARE p fx_param%ROWTYPE;
BEGIN
    SELECT * INTO p FROM fx_param;
    IF p.ambiente !~ '^ensaio-[a-z0-9-]{1,34}$' THEN RAISE EXCEPTION 'fixture: ambiente nao descartavel'; END IF;
    IF p.horas < 1 OR p.horas > 72 THEN RAISE EXCEPTION 'fixture: deslocamento fora de 1..72 h'; END IF;
    IF current_setting('cluster_name') <> 'nekkora-homologacao' OR current_database() <> 'nekkora' THEN
        RAISE EXCEPTION 'fixture: cluster ou banco inesperado'; END IF;
    IF inet_server_addr() IS DISTINCT FROM p.ip THEN RAISE EXCEPTION 'fixture: servidor inesperado (%)', inet_server_addr(); END IF;
    IF NOT EXISTS (SELECT 1 FROM public.organizations o WHERE o.organization_id = p.org
                   AND o.name = 'Homologacao 016 sintetica ' || p.ambiente) THEN
        RAISE EXCEPTION 'fixture: organizacao nao e a sintetica da 016 deste ambiente'; END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.ops_mfa_recovery_requests r WHERE r.request_id = p.req AND r.organization_id = p.org) THEN
        RAISE EXCEPTION 'fixture: solicitacao fora da organizacao sintetica'; END IF;
END $$;

-- (2) instantaneos ANTES: por tabela, campos autorizados das linhas alvo, resto das linhas alvo, e demais linhas
CREATE TEMP TABLE fx_antes ON COMMIT PRESERVE ROWS AS
SELECT 'req' AS t, r.request_id::text AS id,
       jsonb_build_object('opened_at', r.opened_at, 't_total', r.t_total, 'verified_at', r.verified_at, 'preapproved_at', r.preapproved_at,
                          'notice_confirmed_at', r.notice_confirmed_at, 'final_approved_at', r.final_approved_at,
                          'executed_at', r.executed_at, 'closed_at', r.closed_at) AS campos,
       md5((to_jsonb(r) - 'opened_at' - 't_total' - 'verified_at' - 'preapproved_at' - 'notice_confirmed_at' - 'final_approved_at'
            - 'executed_at' - 'closed_at')::text) AS resto
FROM ops.ops_mfa_recovery_requests r, fx_param p WHERE r.request_id = p.req
UNION ALL
SELECT 'not', n.notice_id::text,
       jsonb_build_object('created_at', n.created_at, 'issued_at', n.issued_at, 'state_at', n.state_at, 'claimed_at', n.claimed_at,
                          'accepted_at', n.accepted_at),
       md5((to_jsonb(n) - 'created_at' - 'issued_at' - 'state_at' - 'claimed_at' - 'accepted_at')::text)
FROM ops.ops_mfa_notices n, fx_param p WHERE n.request_id = p.req
UNION ALL
SELECT 'tok', k.token_id::text,
       jsonb_build_object('issued_at', k.issued_at, 'valid_until', k.valid_until, 'used_at', k.used_at, 'invalidated_at', k.invalidated_at),
       md5((to_jsonb(k) - 'issued_at' - 'valid_until' - 'used_at' - 'invalidated_at')::text)
FROM ops.ops_mfa_notice_tokens k, fx_param p WHERE k.request_id = p.req
UNION ALL
SELECT 'apr', a.request_id::text || '/' || a.notice_id::text,
       jsonb_build_object('accepted_at', a.accepted_at, 'approved_at', a.approved_at),
       md5((to_jsonb(a) - 'accepted_at' - 'approved_at')::text)
FROM ops.ops_mfa_approval_attempts a, fx_param p WHERE a.request_id = p.req;

CREATE TEMP TABLE fx_demais_antes ON COMMIT PRESERVE ROWS AS
SELECT 'req' AS t, md5(coalesce(string_agg(to_jsonb(r)::text, '|' ORDER BY r.request_id), '')) AS h
  FROM ops.ops_mfa_recovery_requests r, fx_param p WHERE r.request_id <> p.req
UNION ALL SELECT 'not', md5(coalesce(string_agg(to_jsonb(n)::text, '|' ORDER BY n.notice_id), ''))
  FROM ops.ops_mfa_notices n, fx_param p WHERE n.request_id <> p.req
UNION ALL SELECT 'tok', md5(coalesce(string_agg(to_jsonb(k)::text, '|' ORDER BY k.token_id), ''))
  FROM ops.ops_mfa_notice_tokens k, fx_param p WHERE k.request_id <> p.req
UNION ALL SELECT 'apr', md5(coalesce(string_agg(to_jsonb(a)::text, '|' ORDER BY a.request_id, a.notice_id), ''))
  FROM ops.ops_mfa_approval_attempts a, fx_param p WHERE a.request_id <> p.req
UNION ALL SELECT 'adm', md5(coalesce(string_agg(to_jsonb(x)::text, '|' ORDER BY x.request_id, x.user_id), ''))
  FROM ops.ops_mfa_request_admins x;

-- (3) alteracao sob SET LOCAL, contagem conferida por comando
SET LOCAL session_replication_role = replica;
DO $$
DECLARE p fx_param%ROWTYPE; n bigint; d interval;
BEGIN
    SELECT * INTO p FROM fx_param;
    d := p.desloc;
    UPDATE ops.ops_mfa_recovery_requests SET opened_at = opened_at - d, t_total = t_total - d, verified_at = verified_at - d,
           preapproved_at = preapproved_at - d, notice_confirmed_at = notice_confirmed_at - d, final_approved_at = final_approved_at - d,
           executed_at = executed_at - d, closed_at = closed_at - d
     WHERE request_id = p.req AND organization_id = p.org;
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 1 THEN RAISE EXCEPTION 'fixture: % solicitacoes alteradas, esperado 1', n; END IF;
    UPDATE ops.ops_mfa_notices SET created_at = created_at - d, issued_at = issued_at - d, state_at = state_at - d,
           claimed_at = claimed_at - d, accepted_at = accepted_at - d WHERE request_id = p.req;
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> (SELECT count(*) FROM fx_antes WHERE t = 'not') THEN RAISE EXCEPTION 'fixture: contagem de tentativas divergente'; END IF;
    UPDATE ops.ops_mfa_notice_tokens SET issued_at = issued_at - d, valid_until = valid_until - d, used_at = used_at - d,
           invalidated_at = invalidated_at - d WHERE request_id = p.req;
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> (SELECT count(*) FROM fx_antes WHERE t = 'tok') THEN RAISE EXCEPTION 'fixture: contagem de tokens divergente'; END IF;
    UPDATE ops.ops_mfa_approval_attempts SET accepted_at = accepted_at - d, approved_at = approved_at - d WHERE request_id = p.req;
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> (SELECT count(*) FROM fx_antes WHERE t = 'apr') THEN RAISE EXCEPTION 'fixture: contagem de tentativas da aprovacao divergente'; END IF;
END $$;

-- (4) verificacoes ANTES do COMMIT: campos autorizados = antes - deslocamento (nulos continuam nulos); resto das linhas
--     alvo identico; demais linhas das cinco tabelas identicas (inclusive a fotografia inteira)
DO $$
DECLARE p fx_param%ROWTYPE;
BEGIN
    SELECT * INTO p FROM fx_param;
    IF EXISTS (
        SELECT 1 FROM fx_antes a
        JOIN LATERAL (
            SELECT CASE a.t
                WHEN 'req' THEN (SELECT jsonb_build_object('opened_at', r.opened_at, 't_total', r.t_total, 'verified_at', r.verified_at,
                                    'preapproved_at', r.preapproved_at, 'notice_confirmed_at', r.notice_confirmed_at,
                                    'final_approved_at', r.final_approved_at, 'executed_at', r.executed_at, 'closed_at', r.closed_at)
                                 FROM ops.ops_mfa_recovery_requests r WHERE r.request_id::text = a.id)
                WHEN 'not' THEN (SELECT jsonb_build_object('created_at', n.created_at, 'issued_at', n.issued_at, 'state_at', n.state_at,
                                    'claimed_at', n.claimed_at, 'accepted_at', n.accepted_at)
                                 FROM ops.ops_mfa_notices n WHERE n.notice_id::text = a.id)
                WHEN 'tok' THEN (SELECT jsonb_build_object('issued_at', k.issued_at, 'valid_until', k.valid_until, 'used_at', k.used_at,
                                    'invalidated_at', k.invalidated_at)
                                 FROM ops.ops_mfa_notice_tokens k WHERE k.token_id::text = a.id)
                ELSE (SELECT jsonb_build_object('accepted_at', x.accepted_at, 'approved_at', x.approved_at)
                      FROM ops.ops_mfa_approval_attempts x WHERE x.request_id::text || '/' || x.notice_id::text = a.id)
            END AS depois) d ON true
        CROSS JOIN LATERAL jsonb_each(a.campos) e
        WHERE (e.value = 'null'::jsonb) IS DISTINCT FROM (d.depois -> e.key = 'null'::jsonb)
           OR (e.value <> 'null'::jsonb AND (d.depois ->> e.key)::timestamptz <> (e.value #>> '{}')::timestamptz - p.desloc)) THEN
        RAISE EXCEPTION 'fixture: campo autorizado com valor diferente do deslocamento declarado'; END IF;
    IF EXISTS (
        SELECT 1 FROM fx_antes a WHERE a.resto IS DISTINCT FROM CASE a.t
            WHEN 'req' THEN (SELECT md5((to_jsonb(r) - 'opened_at' - 't_total' - 'verified_at' - 'preapproved_at' - 'notice_confirmed_at'
                                          - 'final_approved_at' - 'executed_at' - 'closed_at')::text)
                             FROM ops.ops_mfa_recovery_requests r WHERE r.request_id::text = a.id)
            WHEN 'not' THEN (SELECT md5((to_jsonb(n) - 'created_at' - 'issued_at' - 'state_at' - 'claimed_at' - 'accepted_at')::text)
                             FROM ops.ops_mfa_notices n WHERE n.notice_id::text = a.id)
            WHEN 'tok' THEN (SELECT md5((to_jsonb(k) - 'issued_at' - 'valid_until' - 'used_at' - 'invalidated_at')::text)
                             FROM ops.ops_mfa_notice_tokens k WHERE k.token_id::text = a.id)
            ELSE (SELECT md5((to_jsonb(x) - 'accepted_at' - 'approved_at')::text)
                  FROM ops.ops_mfa_approval_attempts x WHERE x.request_id::text || '/' || x.notice_id::text = a.id)
        END) THEN
        RAISE EXCEPTION 'fixture: campo nao autorizado alterado'; END IF;
    IF EXISTS (
        SELECT 1 FROM fx_demais_antes a WHERE a.h IS DISTINCT FROM CASE a.t
            WHEN 'req' THEN (SELECT md5(coalesce(string_agg(to_jsonb(r)::text, '|' ORDER BY r.request_id), ''))
                             FROM ops.ops_mfa_recovery_requests r WHERE r.request_id <> p.req)
            WHEN 'not' THEN (SELECT md5(coalesce(string_agg(to_jsonb(n)::text, '|' ORDER BY n.notice_id), ''))
                             FROM ops.ops_mfa_notices n WHERE n.request_id <> p.req)
            WHEN 'tok' THEN (SELECT md5(coalesce(string_agg(to_jsonb(k)::text, '|' ORDER BY k.token_id), ''))
                             FROM ops.ops_mfa_notice_tokens k WHERE k.request_id <> p.req)
            WHEN 'apr' THEN (SELECT md5(coalesce(string_agg(to_jsonb(x)::text, '|' ORDER BY x.request_id, x.notice_id), ''))
                             FROM ops.ops_mfa_approval_attempts x WHERE x.request_id <> p.req)
            ELSE (SELECT md5(coalesce(string_agg(to_jsonb(y)::text, '|' ORDER BY y.request_id, y.user_id), '')) FROM ops.ops_mfa_request_admins y)
        END) THEN
        RAISE EXCEPTION 'fixture: outra linha (ou a fotografia) alterada'; END IF;
END $$;
COMMIT;
\echo FIXTURE_COMMIT_CONCLUIDO

-- (5) depois do COMMIT, na mesma sessao: so persistencia e o SET LOCAL encerrado
DO $$
DECLARE p fx_param%ROWTYPE;
BEGIN
    SELECT * INTO p FROM fx_param;
    IF current_setting('session_replication_role') <> 'origin' THEN RAISE EXCEPTION 'fixture: session_replication_role persistiu'; END IF;
    IF NOT EXISTS (SELECT 1 FROM ops.ops_mfa_recovery_requests r JOIN fx_antes a ON a.t = 'req' AND a.id = r.request_id::text
                   WHERE r.opened_at = (a.campos ->> 'opened_at')::timestamptz - p.desloc) THEN
        RAISE EXCEPTION 'fixture: alteracao nao persistida'; END IF;
END $$;
SELECT 'fixture_solicitacao: solicitacao=' || p.req || ' deslocamento=' || p.horas || 'h linhas=' ||
       (SELECT string_agg(t || ':' || n, ' ') FROM (SELECT t, count(*) n FROM fx_antes GROUP BY t ORDER BY t) c)
       || ' verificacoes=OK session_replication_role=' || current_setting('session_replication_role')
FROM fx_param p;
