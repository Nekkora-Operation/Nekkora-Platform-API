#!/usr/bin/env bash
# Suite de banco da 025_canal_agent.sql (candidata). Dois clusters PostgreSQL 16 DESCARTAVEIS e isolados (containers proprios,
# --network none, sem porta publicada, sem volume nomeado); nunca toca a stack principal nem os ambientes de homologacao.
#
# Modelo de aplicacao: o da 016 revisao 5 (db/bootstrap/aplicar_como_migrador.sh), SUPERUSER em 011-013, 016-020, 022, 023 e
# 024, BYPASSRLS so na 014; a 025 SEM janela (nenhum papel novo, nenhum dono novo, nenhuma funcao SECURITY DEFINER).
#
#   A (limpa):    001-025 pelo entrypoint; 025 aplicada sem elevacao; invariantes 012-025 DEPOIS da retirada (adaptacoes
#                 declaradas: as da suite da 024 e a contagem de tabelas de tenant da 017 e da 023, 43/44 -> 53).
#   B (populada): 001-012 + massa (Workers, Agents, execucoes e eventos da 004-007), 013-015 + massa MFA, 016-022, 023.
#     B0 - 025 sobre a base em 023 (sem a 024): recusada pela pre-condicao, nada muda.
#          024 aplicada (com a janela, como sempre).
#     B1 - 025 falhando no MEIO (funcao homonima de uma que ela cria, criada por fora): nada muda (dados e catalogo).
#     B2 - 025: nenhum dado anterior muda (instantaneo identico); tabelas novas vazias; diff de catalogo de public EXATAMENTE
#          o previsto (esperado/B24_diff_esperado.txt); catalogo = instalacao limpa A; invariantes; marcador versao=025.
#     B3 - reaplicar a 025 e recusado pela pre-condicao; nada muda.
#   C (comportamento no banco, papeis reais):
#     C1 - separacao das escritas: a Platform nao grava fatos da maquina; a ingestao nao cria codigo nem autorizacao, nao muda o
#          status do Agent, nao le e-mail, nao apaga nem altera historico;
#     C2 - RLS: sem contexto nada aparece; contexto de outra organizacao nao ve nem grava;
#     C3 - gatilhos: codigo com validade acima de 60 min, consumo depois do vencimento, cancelamento sem autoridade, autorizacao
#          com versao rascunho, pacote diferente da impressao declarada na versao (inclusive o sha256 dos bytes do artefato) ou
#          sem automation.execute; registro divergente da autorizacao;
#          transicao proibida; terminal alterado; contadores que voltam; sequence acima do limite;
#     C5 - contraprovas das invariantes da 025 (cada uma em BEGIN ... ROLLBACK): controle passa; recusam DELETE concedido,
#          papel de outro servico com acesso, RLS sem FORCE, gatilho desligado, funcao SECURITY DEFINER, EXECUTE extra e escrita
#          da Platform em fato da maquina.
#
# O comportamento das rotas (gateway e Platform) e coberto pelos testes Java: AgentGatewayIT, AgentGatewayLimitsIT
# (nekkora-agent-gateway) e AgentAdminIT (nekkora-platform).
#
# Uso: db/tests/canal-agent-025/run.sh   (Windows/Git Bash: ver README da suite)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
INSTANTANEO="$REPO/db/tests/relogio-018/sql/c_instantaneo_gera.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
ELEVAR="${TESTE_ELEVAR:-011 012 013 016 017 018 019 020 022 023 024}"
PROJETO="${TESTE_PROJETO:-nekkora-c025}"
LIMPA="${PROJETO}-limpa"
POP="${PROJETO}-populada"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
IMAGEM=postgres:16-alpine
PY=python3; "$PY" -c 1 >/dev/null 2>&1 || PY=python

falha() {
    echo "ERRO: $*" >&2
    echo "containers mantidos para inspecao. Remocao: docker rm -f -v $LIMPA $POP" >&2
    exit 1
}
case "$PROJETO" in nekkora-platform|nekkora|*hml03*) falha "projeto '$PROJETO' reservado";; esac
for c in "$LIMPA" "$POP"; do
    docker container inspect "$c" >/dev/null 2>&1 && falha "container $c ja existe"
done

mkdir -p "$SAIDA/mig"
for n in $(seq -w 1 25); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
M023="$SAIDA/mig/023_acessos_convites.sql"
M024="$SAIDA/mig/024_acessos_decisoes_reset_vinculado.sql"
M025="$SAIDA/mig/025_canal_agent.sql"
cp "$MODELO" "$SAIDA/modelo.sh"; chmod 644 "$SAIDA/modelo.sh"
sha256sum "$SAIDA"/mig/0*.sql 2>/dev/null | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt" \
    || shasum -a 256 "$SAIDA"/mig/0*.sql | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt"

psqlc() { local c="$1" db="$2"; shift 2; docker exec -i "$c" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d "$db" "$@"; }
consulta() { psqlc "$1" "$2" -c "$3" < /dev/null; }
lista() { psqlc "$1" nekkora < "$LISTAGEM" | awk '/^(rel|relacl|col|colacl|con|idx|trg|pol|fn|fnacl|type|nsp|nspacl|ext|defacl|dbacl)\|/ { if (l != "") print l; l = $0; next } { l = l " " $0 } END { if (l != "") print l }'; }
sobe() {   # $1 container, $2 quantidade esperada, $3 ultima versao aplicada no entrypoint
    docker create --name "$1" --network none -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
        -e POSTGRES_PASSWORD="descartavel-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')" -e NEKKORA_ATE="$3" \
        -e NEKKORA_ELEVAR="$ELEVAR" -e NEKKORA_MIGRACOES=/nekkora-migracoes \
        "$IMAGEM" postgres -c cluster_name="$1" > /dev/null
    docker cp "$SAIDA/mig" "$1":/nekkora-migracoes > /dev/null
    docker cp "$SAIDA/modelo.sh" "$1":/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh > /dev/null
    docker cp "$SAIDA/modelo.sh" "$1":/tmp/modelo.sh > /dev/null
    docker start "$1" > /dev/null
    for _ in $(seq 1 180); do
        [ "$(docker logs "$1" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
        docker ps -q -f "name=^$1\$" | grep -q . || break
        sleep 1
    done
    docker logs "$1" 2>&1 | grep -E "running /docker-entrypoint-initdb.d|sourcing|aplicar_como_migrador|ERROR|FATAL|init process complete" > "$SAIDA/entrypoint_$1.txt" || true
    grep -qE "ERROR|FATAL|aplicar_como_migrador: FALHA" "$SAIDA/entrypoint_$1.txt" && falha "erro na aplicacao das migrations em $1 (ver entrypoint_$1.txt)"
    [ "$(grep -c 'aplicar_como_migrador: aplicada versao=' "$SAIDA/entrypoint_$1.txt")" = "$2" ] || falha "esperadas $2 migrations em $1"
    grep -q "aplicar_como_migrador: fim db=nekkora aplicadas=$2 estado=0|0|0" "$SAIDA/entrypoint_$1.txt" || falha "migrador nao terminou 0|0|0 em $1"
    [ "$(consulta "$1" nekkora 'SHOW cluster_name')" = "$1" ] || falha "cluster inesperado em $1"
}
modelo() {   # $1 container, $2 desde, $3 ate, $4 arquivo de saida, $5 lista de elevacao (padrao $ELEVAR)
    docker exec -e NEKKORA_DB=nekkora -e NEKKORA_DESDE="$2" -e NEKKORA_ATE="$3" -e NEKKORA_ELEVAR="${5-$ELEVAR}" \
        -e NEKKORA_MIGRACOES=/nekkora-migracoes "$1" sh /tmp/modelo.sh > "$4" 2>&1
}
estado_migrador() {
    consulta "$1" nekkora "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')
        || ' dono_do_banco=' || pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()"
}
adapta() {   # $1 arquivo, $2 trecho, $3 substituto: substitui um trecho que precisa existir exatamente uma vez
    "$PY" -c 'import sys
s = open(sys.argv[1], encoding="utf-8").read()
assert s.count(sys.argv[2]) == 1, "trecho a adaptar nao encontrado exatamente uma vez: " + sys.argv[2][:60]
sys.stdout.write(s.replace(sys.argv[2], sys.argv[3]))' "$1" "$2" "$3"
}
ACEITE_ANTES="'identity_account_is_linked(uuid,uuid), identity_challenge_lookup(text), '"
ACEITE_DEPOIS="'identity_accept_invitation(text,text,uuid), identity_account_is_linked(uuid,uuid), identity_challenge_lookup(text), '"
FECHA_ANTES="        'identity_link_account(text,text,uuid), '"
FECHA_DEPOIS="        'identity_invitations_close_unauthorized(uuid,uuid[]), '
        'identity_link_account(text,text,uuid), '"
invariantes() {   # $1 container, $2 nome; sempre DEPOIS da retirada
    awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/014_mfa_sessoes.sql" > "$SAIDA/_inv014.sql"
    awk '/^-- === 12\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/017_identidade_cadastro.sql" > "$SAIDA/_inv017.sql"
    awk '/^-- === 4\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/019_identidade_regeneracao_codigos.sql" > "$SAIDA/_inv019.sql"
    awk '/^-- === 4\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/020_identidade_recuperacao_senha.sql" > "$SAIDA/_inv020.sql"
    # 017: as adaptacoes da suite da 024 e, desta, a contagem de tabelas de tenant (44 + as 9 do canal = 53)
    adapta "$SAIDA/_inv017.sql" "        'identity_password_change(text,text,text), '" \
        "        'identity_password_change(text,text,text), '
        'identity_password_reset(text,text), '
        'identity_recovery_codes_regenerate(text,text[]), '" > "$SAIDA/_inv017b.sql"
    adapta "$SAIDA/_inv017b.sql" "IF v_n <> 43 THEN RAISE EXCEPTION 'invariante 1: % tabelas com organization_id (esperadas 43)', v_n; END IF;" \
        "IF v_n <> 53 THEN RAISE EXCEPTION 'invariante 1: % tabelas com organization_id (esperadas 53: 43 + access_events da 023 + 9 do canal da 025)', v_n; END IF;" > "$SAIDA/_inv017c.sql"
    adapta "$SAIDA/_inv019.sql" "        'identity_password_change(text,text,text), '" \
        "        'identity_password_change(text,text,text), '
        'identity_password_reset(text,text), '" > "$SAIDA/_inv019b.sql"
    adapta "$SAIDA/_inv019b.sql" "'CHECK (((event_type >= 1) AND (event_type <= 15)))'" "'CHECK (((event_type >= 1) AND (event_type <= 17)))'" > "$SAIDA/_inv019c.sql"
    adapta "$SAIDA/_inv017c.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS" > "$SAIDA/_inv017d.sql"
    adapta "$SAIDA/_inv019c.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS" > "$SAIDA/_inv019d.sql"
    adapta "$SAIDA/_inv020.sql" "$ACEITE_ANTES" "$ACEITE_DEPOIS" > "$SAIDA/_inv020d.sql"
    awk '/^-- === 11\. /{f=1} f && /^COMMIT;/{exit} f' "$M023" > "$SAIDA/_inv023.sql"
    # 023: a mesma contagem de tabelas de tenant (44 + as 9 do canal = 53)
    adapta "$SAIDA/_inv023.sql" "IF v_n <> 44 THEN RAISE EXCEPTION '023: % tabelas com organization_id (esperadas 44)', v_n; END IF;"         "IF v_n <> 53 THEN RAISE EXCEPTION '023: % tabelas com organization_id (esperadas 53: 44 + 9 do canal da 025)', v_n; END IF;" > "$SAIDA/_inv023b.sql"
    { awk '/^-- === 15\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/012_security_rls.sql"
      awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/013_portal_externo.sql"
      adapta "$SAIDA/_inv014.sql" \
          "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) <> 1" \
          "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname AND p.policyname NOT IN ('ops_function_context', 'identity_function_context')) <> 1"
      awk '/^-- === 19\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql"
      adapta "$SAIDA/_inv017d.sql" "$FECHA_ANTES" "$FECHA_DEPOIS"
      adapta "$SAIDA/_inv019d.sql" "$FECHA_ANTES" "$FECHA_DEPOIS"
      adapta "$SAIDA/_inv020d.sql" "$FECHA_ANTES" "$FECHA_DEPOIS"
      awk '/^-- === 5\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/021_correio_fila.sql"
      awk '/^-- === 10\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/022_ativacao_organizacoes.sql"
      cat "$SAIDA/_inv023b.sql"
      awk '/^-- === 6\. Invariantes /{f=1} f && /^COMMIT;/{exit} f' "$M024"
      awk '/^-- === 7\. Invariantes /{f=1} f && /^COMMIT;/{exit} f' "$M025"
    } > "$SAIDA/${2}_invariantes.sql"
    [ "$(grep -c '^DO \$\$' "$SAIDA/${2}_invariantes.sql")" = 12 ] || falha "extracao dos invariantes ($2) falhou"
    estado_migrador "$1" > "$SAIDA/${2}_estado_migrador.txt"
    [ "$(cat "$SAIDA/${2}_estado_migrador.txt")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "invariantes ($2) fora do estado pos-retirada"
    { echo "BEGIN;"; cat "$SAIDA/${2}_invariantes.sql"; echo "ROLLBACK;"; } | psqlc "$1" nekkora > "$SAIDA/${2}_invariantes.txt" 2>&1 \
        || { cat "$SAIDA/${2}_invariantes.txt"; falha "invariantes ($2) falharam"; }
}
marcador() { consulta "$1" nekkora "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()"; }
recusa_sql() {   # $1 container, $2 rotulo, $3 trecho esperado, $4 sql (numa transacao propria)
    local o rc=0
    o="$(printf '%s\n' "$4" | psqlc "$1" nekkora 2>&1)" || rc=$?
    echo "$2|rc=$rc|$(echo "$o" | grep -m1 -oE 'ERROR: .*' | cut -c1-160)" >> "$SAIDA/C_recusas.txt"
    [ "$rc" != 0 ] && echo "$o" | grep -qF "$3" || falha "$2: esperado '$3' (rc=$rc: $o)"
}
passa_sql() {   # $1 container, $2 rotulo, $3 sql (numa transacao propria; deve passar)
    local o rc=0
    o="$(printf '%s\n' "$3" | psqlc "$1" nekkora 2>&1)" || rc=$?
    echo "$2|rc=$rc" >> "$SAIDA/C_passam.txt"
    [ "$rc" = 0 ] || falha "$2: deveria passar (rc=$rc: $o)"
}
como() { printf "BEGIN; SET LOCAL ROLE %s; SELECT set_config('nekkora.organization_id', '%s', true);\n%s\nCOMMIT;" "$1" "$2" "$3"; }

# ================================================================== A. instalacao limpa 001-025
echo ">> A: instalacao limpa 001-025 ($LIMPA)"
sobe "$LIMPA" 25 025
echo "server_version=$(consulta "$LIMPA" nekkora 'SHOW server_version')" > "$SAIDA/ambiente.txt"
grep -q "aplicada versao=025 elevada=nao" "$SAIDA/entrypoint_$LIMPA.txt" || falha "025 deveria ser aplicada SEM janela"
grep -q "invariantes_016_pos_retirada=OK" "$SAIDA/entrypoint_$LIMPA.txt" || falha "016 §19 pos-retirada ausente (depois da 025)"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=025" ] || falha "marcador da instalacao limpa"
invariantes "$LIMPA" A01
lista "$LIMPA" > "$SAIDA/A02_catalogo_025.txt"

# ================================================================== B. base populada 024 -> 025
echo ">> B: base populada 001-023 (+ 024) -> 025 ($POP)"
sobe "$POP" 12 012
HASH_PRE="$(head -c 32 /dev/urandom | sha256sum | cut -c1-64)"
psqlc "$POP" nekkora -v n_exec=300 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/B01_massa_001_012.txt"
modelo "$POP" 013 015 "$SAIDA/B02_013_015.txt" || { cat "$SAIDA/B02_013_015.txt"; falha "013-015 na populada"; }
psqlc "$POP" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/p02_popula_mfa.sql" | tail -1 > "$SAIDA/B03_massa_mfa.txt"
modelo "$POP" 016 023 "$SAIDA/B04_016_023.txt" || { cat "$SAIDA/B04_016_023.txt"; falha "016-023 na populada"; }
consulta "$POP" nekkora "CREATE ROLE backup_nekkora LOGIN BYPASSRLS; GRANT pg_read_all_data TO backup_nekkora" > /dev/null
[ "$(consulta "$POP" nekkora "SELECT (SELECT count(*) FROM agents) > 0 AND (SELECT count(*) FROM executions) > 0 AND (SELECT count(*) FROM execution_events) > 0")" = t ] \
    || falha "massa sem Agents, execucoes ou eventos"

echo "   B0: 025 sem a 024"
psqlc "$POP" nekkora < "$INSTANTANEO" > "$SAIDA/B00_instantaneo.sql"
psqlc "$POP" nekkora < "$SAIDA/B00_instantaneo.sql" > "$SAIDA/B00_dados_023.txt"
lista "$POP" > "$SAIDA/B00_catalogo_023.txt"
if modelo "$POP" 025 025 "$SAIDA/B00_025_sem_024.txt"; then falha "B0: 025 aplicada sem a 024"; fi
grep -qF "025 exige a 024 aplicada" "$SAIDA/B00_025_sem_024.txt" || { cat "$SAIDA/B00_025_sem_024.txt"; falha "B0: recusa diferente da pre-condicao"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B0: migrador fora de 0|0|0"
psqlc "$POP" nekkora < "$SAIDA/B00_instantaneo.sql" > "$SAIDA/B00b_dados.txt"
diff "$SAIDA/B00_dados_023.txt" "$SAIDA/B00b_dados.txt" > /dev/null || falha "B0: dados alterados"
lista "$POP" > "$SAIDA/B00b_catalogo.txt"
diff "$SAIDA/B00_catalogo_023.txt" "$SAIDA/B00b_catalogo.txt" > /dev/null || falha "B0: catalogo alterado"

modelo "$POP" 024 024 "$SAIDA/B04b_024.txt" || { cat "$SAIDA/B04b_024.txt"; falha "024 na populada"; }
psqlc "$POP" nekkora < "$INSTANTANEO" > "$SAIDA/B07_instantaneo.sql"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B07_dados_024.txt"
lista "$POP" > "$SAIDA/B07_catalogo_024.txt"

echo "   B1: 025 falhando no meio"
# funcao homonima da ULTIMA funcao de gatilho que a 025 cria (sec. 4), criada por fora: as tabelas, a RLS e as funcoes
# anteriores ja foram criadas quando a 025 falha; esperado: nada fica (uma transacao), retirada 0|0|0
consulta "$POP" nekkora "CREATE FUNCTION public.agent_run_on_update() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'" > /dev/null
if modelo "$POP" 025 025 "$SAIDA/B10_025_falha.txt"; then falha "B1: 025 aplicada com a funcao homonima"; fi
grep -qF 'function "agent_run_on_update" already exists' "$SAIDA/B10_025_falha.txt" || { cat "$SAIDA/B10_025_falha.txt"; falha "B1: recusa diferente da esperada"; }
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B1: migrador fora de 0|0|0"
consulta "$POP" nekkora "DROP FUNCTION public.agent_run_on_update()" > /dev/null
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM pg_class WHERE relname LIKE 'agent\_%' AND relname <> 'agent_credentials' AND relkind = 'r' AND relnamespace = 'public'::regnamespace")" = 0 ] \
    || falha "B1: tabelas do canal ficaram"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B11_dados.txt"
diff "$SAIDA/B07_dados_024.txt" "$SAIDA/B11_dados.txt" > /dev/null || falha "B1: dados alterados"
lista "$POP" > "$SAIDA/B11_catalogo.txt"
diff "$SAIDA/B07_catalogo_024.txt" "$SAIDA/B11_catalogo.txt" > /dev/null || falha "B1: catalogo alterado"

echo "   B2: 025"
modelo "$POP" 025 025 "$SAIDA/B20_025.txt" || { cat "$SAIDA/B20_025.txt"; falha "025 na populada"; }
grep -q "aplicada versao=025 elevada=nao" "$SAIDA/B20_025.txt" || falha "B2: 025 deveria ser aplicada sem janela"
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B2: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=025" ] || falha "B2: marcador"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B21_dados_025.txt"
diff "$SAIDA/B07_dados_024.txt" "$SAIDA/B21_dados_025.txt" > "$SAIDA/B21_diff_dados.txt" || falha "B2: a 025 alterou dados anteriores (ver B21_diff_dados.txt)"
[ "$(consulta "$POP" nekkora "SELECT (SELECT count(*) FROM agent_enrollments) + (SELECT count(*) FROM agent_runs) + (SELECT count(*) FROM agent_run_events)
    + (SELECT count(*) FROM local_run_authorizations) + (SELECT count(*) FROM agent_presence) + (SELECT count(*) FROM agent_admin_events)")" = 0 ] \
    || falha "B2: tabelas novas nao nasceram vazias"
lista "$POP" > "$SAIDA/B24_catalogo_025.txt"
diff "$SAIDA/B07_catalogo_024.txt" "$SAIDA/B24_catalogo_025.txt" > "$SAIDA/B24_diff_catalogo.txt" || true
grep -E '^[<>]' "$SAIDA/B24_diff_catalogo.txt" | sed -E 's/\|md5=[0-9a-f]+/|md5=.../' | sort > "$SAIDA/B24_diff_resumo.txt"
if [ -f "$AQUI/esperado/B24_diff_esperado.txt" ]; then
    sort "$AQUI/esperado/B24_diff_esperado.txt" > "$SAIDA/_b.txt"
    diff "$SAIDA/_b.txt" "$SAIDA/B24_diff_resumo.txt" > "$SAIDA/B24_fora_do_previsto.txt" || { cat "$SAIDA/B24_fora_do_previsto.txt"; falha "B2: catalogo 024 -> 025 fora do previsto"; }
else
    falha "B2: esperado/B24_diff_esperado.txt ausente (resumo gerado em $SAIDA/B24_diff_resumo.txt para revisao)"
fi
# o diff nunca REMOVE nada do catalogo anterior (a 025 e aditiva)
[ "$(grep -c '^<' "$SAIDA/B24_diff_resumo.txt" || true)" = "$(grep -c '^< \(colacl\|relacl\|fnacl\)' "$SAIDA/B24_diff_resumo.txt" || true)" ] \
    || falha "B2: a 025 removeu algo do catalogo alem de trocar ACL"
diff "$SAIDA/A02_catalogo_025.txt" "$SAIDA/B24_catalogo_025.txt" > "$SAIDA/B25_diff_limpa.txt" || falha "B2: catalogo da atualizada difere da instalacao limpa (ver B25)"
invariantes "$POP" B26

echo "   B3: reaplicacao da 025"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B30_dados_antes.txt"
if psqlc "$POP" nekkora < "$M025" > "$SAIDA/B30_reaplica.txt" 2>&1; then falha "B3: 025 reaplicada"; fi
grep -qF "025: objetos do canal do Agent ja existem" "$SAIDA/B30_reaplica.txt" || { cat "$SAIDA/B30_reaplica.txt"; falha "B3: recusa diferente da pre-condicao"; }
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B30_dados_depois.txt"
diff "$SAIDA/B30_dados_antes.txt" "$SAIDA/B30_dados_depois.txt" > /dev/null || falha "B3: dados alterados"
lista "$POP" > "$SAIDA/B31_catalogo.txt"
diff "$SAIDA/B24_catalogo_025.txt" "$SAIDA/B31_catalogo.txt" > /dev/null || falha "B3: catalogo alterado"

# ================================================================== C. comportamento no banco (base limpa A)
echo ">> C: comportamento no banco ($LIMPA)"
u() { consulta "$LIMPA" nekkora "SELECT gen_random_uuid()"; }
O="$(u)"; O2="$(u)"; ADM="$(u)"; ADMM="$(u)"; MEM="$(u)"; MEMM="$(u)"; WS="$(u)"; AUT="$(u)"; VER="$(u)"; RASC="$(u)"
WK="$(u)"; AG="$(u)"; WAA="$(u)"; WWA="$(u)"; CRED="$(u)"; AUTZ="$(u)"; RUN="$(u)"; ENR="$(u)"
FP=5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d
ART=$(printf 'ab%.0s' $(seq 32))  # sha256 dos bytes do artefato: OUTRA medida, nunca a impressao
psqlc "$LIMPA" nekkora > "$SAIDA/C00_massa.txt" 2>&1 <<SQL || { cat "$SAIDA/C00_massa.txt"; falha "massa C"; }
BEGIN;
INSERT INTO organizations (organization_id, name, status) VALUES ('$O', 'Org canal', 1), ('$O2', 'Outra org', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES
  ('$ADM', '$O', 1, 'adm-c025@o.test', now(), 2, 'Admin'), ('$MEM', '$O', 1, 'mem-c025@o.test', now(), 2, 'Membro');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('$ADMM', '$O', '$ADM', 1, 2), ('$MEMM', '$O', '$MEM', 1, 2);
INSERT INTO organization_role_assignments (organization_id, membership_id, role_id, scope, status, assigned_by_membership_id)
  SELECT '$O', '$ADMM', role_id, 1, 1, '$ADMM' FROM roles WHERE code = 'ORG_ADMIN';
INSERT INTO organization_resource_limit_revisions (organization_id, resource_code, revision_number, effective_from, limit_mode, limit_value, provisioned_by_user_id)
  SELECT '$O', c, 1, now(), 2, NULL, '$ADM' FROM generate_series(1, 5) c;
INSERT INTO workspaces (workspace_id, organization_id, name, status) VALUES ('$WS', '$O', 'WS', 1);
INSERT INTO automations (automation_id, organization_id, workspace_id, name, status, created_by_membership_id) VALUES ('$AUT', '$O', '$WS', 'Biografias', 1, '$ADMM');
INSERT INTO automation_versions (automation_version_id, organization_id, workspace_id, automation_id, status, command_json, created_by_membership_id) VALUES
  ('$VER', '$O', '$WS', '$AUT', 1, jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','p','sha256','$ART'),
   'agent_package',jsonb_build_object('fingerprint_rule','nekkora-agent/package-fingerprint/1','fingerprint','$FP'),'runtime','python3.12',
   'entrypoint','main.py','arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '$ADMM'),
  ('$RASC', '$O', '$WS', '$AUT', 1, jsonb_build_object('contract_version',1,'artifact',jsonb_build_object('reference','p','sha256','$ART'),
   'agent_package',jsonb_build_object('fingerprint_rule','nekkora-agent/package-fingerprint/1','fingerprint','$FP'),'runtime','python3.12',
   'entrypoint','main.py','arguments','[]'::jsonb,'parameters','{}'::jsonb,'secret_bindings','{}'::jsonb), '$ADMM');
UPDATE automation_versions SET status = 2, published_at = now(), published_by_membership_id = '$ADMM' WHERE automation_version_id = '$VER';
INSERT INTO workers (worker_id, organization_id, name, status) VALUES ('$WK', '$O', 'W', 1);
INSERT INTO agents (agent_id, organization_id, name, agent_version, status) VALUES ('$AG', '$O', 'A', '0.1.0', 2);
INSERT INTO worker_agent_assignments (assignment_id, organization_id, worker_id, agent_id, status, assigned_by_membership_id) VALUES ('$WAA', '$O', '$WK', '$AG', 1, '$ADMM');
INSERT INTO worker_workspace_assignments (assignment_id, organization_id, worker_id, workspace_id, status, authorized_by_membership_id) VALUES ('$WWA', '$O', '$WK', '$WS', 1, '$ADMM');
INSERT INTO agent_credentials (credential_id, organization_id, agent_id, credential_hash, issued_by_membership_id, expires_at) VALUES ('$CRED', '$O', '$AG', repeat('c', 64), '$ADMM', now() + interval '90 days');
COMMIT;
SQL
passa_sql "$LIMPA" "C0_platform_autoriza" "$(como nekkora_platform "$O" "INSERT INTO local_run_authorizations (authorization_id, organization_id, workspace_id, worker_id,
    worker_workspace_assignment_id, automation_id, automation_version_id, package_fingerprint, granted_by_membership_id, valid_until) VALUES ('$AUTZ', '$O', '$WS', '$WK',
    '$WWA', '$AUT', '$VER', '$FP', '$ADMM', now() + interval '30 days');")"
passa_sql "$LIMPA" "C0_platform_codigo" "$(como nekkora_platform "$O" "INSERT INTO agent_enrollments (enrollment_id, organization_id, purpose, worker_id, agent_name, code_hash,
    created_by_membership_id, expires_at) VALUES ('$ENR', '$O', 1, '$WK', 'M', repeat('a', 64), '$ADMM', clock_timestamp() + interval '15 minutes');")"
passa_sql "$LIMPA" "C0_ingestao_registra" "$(como nekkora_ingestion "$O" "INSERT INTO agent_runs (agent_run_id, organization_id, workspace_id, automation_id, automation_version_id,
    authorization_id, agent_id, worker_id, worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration, supervisor_state)
    VALUES ('$RUN', '$O', '$WS', '$AUT', '$VER', '$AUTZ', '$AG', '$WK', '$WAA', 'ex-$(printf 'ab%.0s' $(seq 16))', '$FP', 1, 3);
    INSERT INTO agent_run_events (organization_id, agent_run_id, sequence, content_sha256, envelope, source, event_code, severity, category, observed_utc)
    VALUES ('$O', '$RUN', 1, repeat('e', 64), '{\"sequence\":1,\"event_code\":\"RUNNING\"}', 1, 'RUNNING', 1, 1, now());")"

echo "   C1: separacao das escritas"
recusa_sql "$LIMPA" "C1_platform_registro" "permission denied" "$(como nekkora_platform "$O" "UPDATE agent_runs SET supervisor_state = 4 WHERE agent_run_id = '$RUN';")"
recusa_sql "$LIMPA" "C1_platform_evento" "permission denied" "$(como nekkora_platform "$O" "INSERT INTO agent_run_events (organization_id, agent_run_id, sequence, content_sha256,
    envelope, source, event_code, severity, category, observed_utc) VALUES ('$O', '$RUN', 2, repeat('e', 64), '{\"sequence\":2,\"event_code\":\"X\"}', 1, 'X', 1, 1, now());")"
recusa_sql "$LIMPA" "C1_ingestao_codigo" "permission denied" "$(como nekkora_ingestion "$O" "INSERT INTO agent_enrollments (organization_id, purpose, worker_id, agent_name, code_hash,
    created_by_membership_id, expires_at) VALUES ('$O', 1, '$WK', 'M', repeat('b', 64), '$ADMM', clock_timestamp() + interval '15 minutes');")"
recusa_sql "$LIMPA" "C1_ingestao_autorizacao" "permission denied" "$(como nekkora_ingestion "$O" "UPDATE local_run_authorizations SET valid_until = now() + interval '300 days';")"
recusa_sql "$LIMPA" "C1_ingestao_status_agent" "permission denied" "$(como nekkora_ingestion "$O" "UPDATE agents SET status = 2 WHERE agent_id = '$AG';")"
recusa_sql "$LIMPA" "C1_ingestao_email" "permission denied" "$(como nekkora_ingestion "$O" "SELECT email FROM users;")"
recusa_sql "$LIMPA" "C1_ingestao_apaga_evento" "permission denied" "$(como nekkora_ingestion "$O" "DELETE FROM agent_run_events;")"
recusa_sql "$LIMPA" "C1_ingestao_altera_evento" "permission denied" "$(como nekkora_ingestion "$O" "UPDATE agent_run_events SET severity = 3;")"
recusa_sql "$LIMPA" "C1_portal_canal" "permission denied" "$(como nekkora_portal "$O" "SELECT count(*) FROM agent_runs;")"

echo "   C2: RLS"
[ "$(printf "BEGIN; SET LOCAL ROLE nekkora_ingestion; SELECT count(*) FROM agent_runs; COMMIT;" | psqlc "$LIMPA" nekkora | grep -E '^[0-9]+$')" = 0 ] || falha "C2: sem contexto ve linhas"
[ "$(como nekkora_ingestion "$O2" "SELECT count(*) FROM agent_runs;" | psqlc "$LIMPA" nekkora | grep -E '^[0-9]+$' | tail -1)" = 0 ] || falha "C2: outra organizacao ve linhas"
recusa_sql "$LIMPA" "C2_grava_outra_org" "row-level security" "$(como nekkora_ingestion "$O2" "INSERT INTO agent_presence (agent_id, organization_id, contract_version, capabilities,
    reported_at) VALUES ('$AG', '$O', '1.0', '{}', now());")"

echo "   C3: gatilhos"
recusa_sql "$LIMPA" "C3_codigo_validade" "validade do codigo" "$(como nekkora_platform "$O" "INSERT INTO agent_enrollments (organization_id, purpose, worker_id, agent_name, code_hash,
    created_by_membership_id, expires_at) VALUES ('$O', 1, '$WK', 'M', repeat('d', 64), '$ADMM', clock_timestamp() + interval '61 minutes');")"
recusa_sql "$LIMPA" "C3_codigo_sem_autoridade" "sem autoridade para criar o codigo" "$(como nekkora_platform "$O" "INSERT INTO agent_enrollments (organization_id, purpose, worker_id,
    agent_name, code_hash, created_by_membership_id, expires_at) VALUES ('$O', 1, '$WK', 'M', repeat('f', 64), '$MEMM', clock_timestamp() + interval '15 minutes');")"
recusa_sql "$LIMPA" "C3_cancela_sem_autoridade" "sem autoridade para cancelar" "$(como nekkora_platform "$O" "UPDATE agent_enrollments SET cancelled_at = now(),
    cancelled_by_membership_id = '$MEMM' WHERE enrollment_id = '$ENR';")"
recusa_sql "$LIMPA" "C3_autorizacao_rascunho" "versao nao publicada" "$(como nekkora_platform "$O" "INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id,
    worker_workspace_assignment_id, automation_id, automation_version_id, package_fingerprint, granted_by_membership_id, valid_until) VALUES ('$O', '$WS', '$WK', '$WWA',
    '$AUT', '$RASC', '$FP', '$ADMM', now() + interval '30 days');")"
recusa_sql "$LIMPA" "C3_autorizacao_pacote" "pacote diferente" "$(como nekkora_platform "$O" "INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id,
    worker_workspace_assignment_id, automation_id, automation_version_id, package_fingerprint, granted_by_membership_id, valid_until) VALUES ('$O', '$WS', '$WK', '$WWA',
    '$AUT', '$VER', repeat('0', 64), '$ADMM', now() + interval '30 days');")"
# o sha256 dos BYTES do artefato nao e a impressao do pacote: a autorizacao com ele e recusada (contrato, sec. 6.4)
recusa_sql "$LIMPA" "C3_autorizacao_sha_do_artefato" "pacote diferente" "$(como nekkora_platform "$O" "INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id,
    worker_workspace_assignment_id, automation_id, automation_version_id, package_fingerprint, granted_by_membership_id, valid_until) VALUES ('$O', '$WS', '$WK', '$WWA',
    '$AUT', '$VER', '$ART', '$ADMM', now() + interval '30 days');")"
recusa_sql "$LIMPA" "C3_autorizacao_sem_permissao" "sem automation.execute" "$(como nekkora_platform "$O" "INSERT INTO local_run_authorizations (organization_id, workspace_id, worker_id,
    worker_workspace_assignment_id, automation_id, automation_version_id, package_fingerprint, granted_by_membership_id, valid_until) VALUES ('$O', '$WS', '$WK', '$WWA',
    '$AUT', '$VER', '$FP', '$MEMM', now() + interval '30 days');")"
recusa_sql "$LIMPA" "C3_registro_divergente" "registro diverge da autorizacao" "$(como nekkora_ingestion "$O" "INSERT INTO agent_runs (organization_id, workspace_id, automation_id,
    automation_version_id, authorization_id, agent_id, worker_id, worker_agent_assignment_id, agent_execution_id, package_fingerprint, authorization_state_at_registration,
    supervisor_state) VALUES ('$O', '$WS', '$AUT', '$VER', '$AUTZ', '$AG', '$WK', '$WAA', 'ex-$(printf 'cd%.0s' $(seq 16))', repeat('1', 64), 1, 3);")"
recusa_sql "$LIMPA" "C3_transicao_proibida" "transicao do supervisor nao permitida" "$(como nekkora_ingestion "$O" "UPDATE agent_runs SET supervisor_state = 1 WHERE agent_run_id = '$RUN';")"
passa_sql "$LIMPA" "C3_terminal" "$(como nekkora_ingestion "$O" "UPDATE agent_runs SET supervisor_state = 9, supervisor_code = 'AGT-EXE-025' WHERE agent_run_id = '$RUN';")"
recusa_sql "$LIMPA" "C3_terminal_alterado" "desfecho terminal do supervisor e imutavel" "$(como nekkora_ingestion "$O" "UPDATE agent_runs SET supervisor_state = 4,
    supervisor_code = NULL WHERE agent_run_id = '$RUN';")"
passa_sql "$LIMPA" "C3_contador" "$(como nekkora_ingestion "$O" "UPDATE agent_runs SET events_stored = 1, stored_through = 1, settled_through = 1, max_sequence = 1 WHERE agent_run_id = '$RUN';")"
recusa_sql "$LIMPA" "C3_contador_volta" "so avancam" "$(como nekkora_ingestion "$O" "UPDATE agent_runs SET events_stored = 0 WHERE agent_run_id = '$RUN';")"
recusa_sql "$LIMPA" "C3_sequence_limite" "agent_run_events_sequence_check" "$(como nekkora_ingestion "$O" "INSERT INTO agent_run_events (organization_id, agent_run_id, sequence,
    content_sha256, envelope, source, event_code, severity, category, observed_utc) VALUES ('$O', '$RUN', 1000001, repeat('e', 64), '{\"sequence\":1000001,\"event_code\":\"XY\"}',
    1, 'XY', 1, 1, now());")"
recusa_sql "$LIMPA" "C3_projecao_incoerente" "agent_run_events_check" "$(como nekkora_ingestion "$O" "INSERT INTO agent_run_events (organization_id, agent_run_id, sequence,
    content_sha256, envelope, source, event_code, severity, category, observed_utc) VALUES ('$O', '$RUN', 2, repeat('e', 64), '{\"sequence\":3,\"event_code\":\"XY\"}', 1, 'XY', 1, 1, now());")"

echo "   C5: contraprovas das invariantes da 025"
awk '/^-- === 7\. Invariantes /{f=1} f && /^COMMIT;/{exit} f' "$M025" > "$SAIDA/C50_inv025.sql"
# o C limpou as tabelas? nao: a invariante 6 (vazias) vale so na aplicacao; aqui ela e retirada da extracao
adapta "$SAIDA/C50_inv025.sql" "    IF EXISTS (SELECT 1 FROM public.agent_enrollments) OR EXISTS (SELECT 1 FROM public.agent_runs)
       OR EXISTS (SELECT 1 FROM public.local_run_authorizations) THEN
        RAISE EXCEPTION '025: tabelas do canal nao nascem vazias';
    END IF;" "" > "$SAIDA/C51_inv025_sem_vazias.sql"
contra() {   # $1 rotulo, $2 violacao, $3 trecho esperado
    local o rc=0
    o="$({ echo "BEGIN;"; echo "$2"; cat "$SAIDA/C51_inv025_sem_vazias.sql"; echo "ROLLBACK;"; } | psqlc "$LIMPA" nekkora 2>&1)" || rc=$?
    echo "$1|rc=$rc|$(echo "$o" | grep -m1 -oE 'ERROR: .*' | cut -c1-160)" >> "$SAIDA/C5_contraprovas.txt"
    [ "$rc" != 0 ] && echo "$o" | grep -qF "$3" || falha "C5 $1: esperado '$3' (rc=$rc: $o)"
}
{ echo "BEGIN;"; cat "$SAIDA/C51_inv025_sem_vazias.sql"; echo "ROLLBACK;"; } | psqlc "$LIMPA" nekkora > "$SAIDA/C5_controle.txt" 2>&1 || { cat "$SAIDA/C5_controle.txt"; falha "C5: controle"; }
contra delete "GRANT DELETE ON public.agent_run_events TO nekkora_platform;" "exclusao possivel"
contra outro_servico "GRANT SELECT ON public.agent_runs TO nekkora_portal;" "papel de outro servico"
contra sem_force "ALTER TABLE public.agent_runs NO FORCE ROW LEVEL SECURITY;" "RLS ou politicas divergentes"
contra gatilho "ALTER TABLE public.agent_runs DISABLE TRIGGER trg_agent_runs_on_update;" "gatilhos do canal ligados"
contra definer "ALTER FUNCTION public.agent_workspace_permission(uuid, uuid, uuid, text) SECURITY DEFINER;" "funcao do canal fora do padrao"
contra execute_extra "GRANT EXECUTE ON FUNCTION public.agent_workspace_permission(uuid, uuid, uuid, text) TO nekkora_ingestion;" "EXECUTE de agent_workspace_permission divergente"
contra platform_fato "GRANT INSERT ON public.agent_run_events TO nekkora_platform;" "nekkora_platform grava fatos da maquina"
contra ingestao_alem "GRANT UPDATE (status) ON public.agents TO nekkora_ingestion;" "nekkora_ingestion com privilegio alem do canal"

# ================================================================== fim
docker rm -f -v "$LIMPA" "$POP" > /dev/null
echo "OK: suite da 025 completa (saidas em $SAIDA)"
