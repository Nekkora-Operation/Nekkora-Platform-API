-- FIXTURE TEMPORAL DECLARADA - contatos (desenho P1a, sec. 5.2; plano B8a e B9 opcao ii). SO ambiente ensaio-*, SO dados
-- sinteticos da organizacao da 016. Papel: admin_cluster, pela rede (job "ferramentas", TLS verify-full).
--
-- Altera EXCLUSIVAMENTE: ops.org_authorized_contacts, linhas contact_id = ANY(:contatos) da organizacao :org, campos
-- created_at e channel_set_at, deslocados de -721 h. Nada mais.
--
-- CONTROLE DE TRANSACAO (inequivoco): o runner chama psql com -v ON_ERROR_STOP=1 e SEM --single-transaction. Este
-- arquivo abre e fecha a transacao (BEGIN ... COMMIT). Qualquer erro antes do COMMIT (inclusive RAISE das
-- verificacoes) faz o psql parar ali; a conexao fecha sem COMMIT e o servidor desfaz tudo. TODAS as verificacoes que
-- devem provocar rollback acontecem ANTES do COMMIT. Depois dele, na MESMA sessao, so: persistencia e
-- session_replication_role = origin (o SET LOCAL nao sobreviveu).
--
-- -v: ambiente, org, contatos (uuids separados por virgula), ip_servidor (IP do PostgreSQL do ambiente).
-- CLASSIFICACAO pelo runner (homologacao.sh fixture): antes do BEGIN, este arquivo imprime o estado de partida dos
-- campos alvo (linhas ANTES|..., fixture_016_estado.sql); logo depois do COMMIT confirmado, imprime a marca
-- FIXTURE_COMMIT_CONCLUIDO (so alcancada se o COMMIT retornou sucesso). Sem a marca e com erro do servidor antes
-- dela: transacao desfeita. Com a marca e falha depois: alteracao aplicada, conferencia pos-commit falhou. Sem a
-- marca e sem erro do servidor (conexao perdida, resposta do COMMIT perdida): INDETERMINADA -> reconciliacao
-- (homologacao.sh fixture <id> conferir <execucao>) comparando o estado atual com as linhas ANTES.
\set ON_ERROR_STOP on
\set tipo contatos
\set prefixo ANTES
\ir fixture_016_estado.sql
BEGIN;
CREATE TEMP TABLE fx_param ON COMMIT PRESERVE ROWS AS
SELECT :'ambiente'::text AS ambiente, :'org'::uuid AS org, string_to_array(:'contatos', ',')::uuid[] AS contatos,
       :'ip_servidor'::inet AS ip, interval '721 hours' AS desloc;

-- (1) isolamento: ambiente descartavel, servidor e banco deste ambiente, organizacao sintetica, antes de qualquer
--     solicitacao; contatos todos da organizacao e sem repeticao
DO $$
DECLARE p fx_param%ROWTYPE;
BEGIN
    SELECT * INTO p FROM fx_param;
    IF p.ambiente !~ '^ensaio-[a-z0-9-]{1,34}$' THEN RAISE EXCEPTION 'fixture: ambiente nao descartavel'; END IF;
    IF current_setting('cluster_name') <> 'nekkora-homologacao' OR current_database() <> 'nekkora' THEN
        RAISE EXCEPTION 'fixture: cluster ou banco inesperado'; END IF;
    IF inet_server_addr() IS DISTINCT FROM p.ip THEN RAISE EXCEPTION 'fixture: servidor inesperado (%)', inet_server_addr(); END IF;
    IF NOT EXISTS (SELECT 1 FROM public.organizations o WHERE o.organization_id = p.org
                   AND o.name = 'Homologacao 016 sintetica ' || p.ambiente) THEN
        RAISE EXCEPTION 'fixture: organizacao nao e a sintetica da 016 deste ambiente'; END IF;
    IF EXISTS (SELECT 1 FROM ops.ops_mfa_recovery_requests r WHERE r.organization_id = p.org) THEN
        RAISE EXCEPTION 'fixture de contatos: ja existe solicitacao na organizacao'; END IF;
    IF cardinality(p.contatos) = 0 OR cardinality(p.contatos) <> (SELECT count(DISTINCT x) FROM unnest(p.contatos) x) THEN
        RAISE EXCEPTION 'fixture: lista de contatos vazia ou repetida'; END IF;
    IF (SELECT count(*) FROM ops.org_authorized_contacts c WHERE c.contact_id = ANY (p.contatos) AND c.organization_id = p.org)
       <> cardinality(p.contatos) THEN
        RAISE EXCEPTION 'fixture: contato fora da organizacao sintetica'; END IF;
END $$;

-- (2) instantaneo ANTES: campos NAO autorizados das linhas alvo, e todas as demais linhas da tabela
CREATE TEMP TABLE fx_alvo_antes ON COMMIT PRESERVE ROWS AS
SELECT c.contact_id, c.created_at, c.channel_set_at,
       md5((to_jsonb(c) - 'created_at' - 'channel_set_at')::text) AS resto
FROM ops.org_authorized_contacts c, fx_param p WHERE c.contact_id = ANY (p.contatos);
CREATE TEMP TABLE fx_demais_antes ON COMMIT PRESERVE ROWS AS
SELECT md5(coalesce(string_agg(to_jsonb(c)::text, '|' ORDER BY c.contact_id), '')) AS h, count(*) AS n
FROM ops.org_authorized_contacts c, fx_param p WHERE NOT (c.contact_id = ANY (p.contatos));

-- (3) alteracao: triggers desligados SO nesta transacao (SET LOCAL), contagem conferida por comando
SET LOCAL session_replication_role = replica;
DO $$
DECLARE p fx_param%ROWTYPE; n bigint;
BEGIN
    SELECT * INTO p FROM fx_param;
    UPDATE ops.org_authorized_contacts c SET created_at = c.created_at - p.desloc, channel_set_at = c.channel_set_at - p.desloc
     WHERE c.contact_id = ANY (p.contatos) AND c.organization_id = p.org;
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> cardinality(p.contatos) THEN RAISE EXCEPTION 'fixture: % linhas alteradas, esperado %', n, cardinality(p.contatos); END IF;
END $$;

-- (4) verificacoes ANTES do COMMIT (qualquer divergencia -> RAISE -> rollback)
DO $$
DECLARE p fx_param%ROWTYPE;
BEGIN
    SELECT * INTO p FROM fx_param;
    -- campos autorizados: exatamente o deslocamento declarado
    IF EXISTS (SELECT 1 FROM fx_alvo_antes a JOIN ops.org_authorized_contacts c USING (contact_id)
               WHERE c.created_at <> a.created_at - p.desloc OR c.channel_set_at <> a.channel_set_at - p.desloc) THEN
        RAISE EXCEPTION 'fixture: deslocamento diferente do declarado'; END IF;
    -- campos NAO autorizados das proprias linhas alteradas: identicos
    IF EXISTS (SELECT 1 FROM fx_alvo_antes a JOIN ops.org_authorized_contacts c USING (contact_id)
               WHERE md5((to_jsonb(c) - 'created_at' - 'channel_set_at')::text) <> a.resto) THEN
        RAISE EXCEPTION 'fixture: campo nao autorizado alterado'; END IF;
    -- demais linhas da tabela: identicas
    IF (SELECT h FROM fx_demais_antes) <> (SELECT md5(coalesce(string_agg(to_jsonb(c)::text, '|' ORDER BY c.contact_id), ''))
                                           FROM ops.org_authorized_contacts c WHERE NOT (c.contact_id = ANY (p.contatos))) THEN
        RAISE EXCEPTION 'fixture: outra linha alterada'; END IF;
END $$;
COMMIT;
\echo FIXTURE_COMMIT_CONCLUIDO

-- (5) depois do COMMIT, na mesma sessao: so persistencia e o SET LOCAL encerrado
DO $$
DECLARE p fx_param%ROWTYPE;
BEGIN
    SELECT * INTO p FROM fx_param;
    IF current_setting('session_replication_role') <> 'origin' THEN RAISE EXCEPTION 'fixture: session_replication_role persistiu'; END IF;
    IF EXISTS (SELECT 1 FROM fx_alvo_antes a JOIN ops.org_authorized_contacts c USING (contact_id)
               WHERE c.channel_set_at <> a.channel_set_at - p.desloc) THEN
        RAISE EXCEPTION 'fixture: alteracao nao persistida'; END IF;
END $$;
SELECT 'fixture_contatos: org=' || p.org || ' contatos=' || array_to_string(p.contatos, ',') || ' linhas=' || cardinality(p.contatos)
       || ' deslocamento=721h verificacoes=OK session_replication_role=' || current_setting('session_replication_role')
FROM fx_param p;
