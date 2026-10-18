#!/usr/bin/env bash
# Suite de banco da 016_recuperacao_emergencial_mfa.sql (candidata). Dois clusters PostgreSQL 16 DESCARTAVEIS e
# isolados (containers proprios, sem porta publicada, sem volume nomeado); nunca toca a stack principal nem o hml03.
#
# MODELO SUPORTADO pela 016 revisao 5 (P4-R2a): toda base e montada pela identidade de implantacao migrador_nekkora,
# dona do banco, com SUPERUSER so nas versoes elevadas (011, 012, 013, 016) e retirada conferida depois de cada uma
# (db/bootstrap/aplicar_como_migrador.sh, o mesmo script das bases Java). A sec. 19 da 016 roda DEPOIS da retirada.
#
#   A (limpa):  001-016 pelo script do modelo no entrypoint. Catalogo de public x base 001-015 no mesmo cluster (so acrescimos previstos e
#               a unica remocao prevista: o INSERT de tabela inteira da Platform em mfa_events, trocado por colunas);
#               invariantes da 012 (§15), 013 (§8), 014 (§8, com a unica adaptacao prevista pela matriz 016) e 016 (§19);
#               listagem do schema ops (evidencia).
#   B (populada): 001-012 pelo script do modelo + massa (atualizacao-populada/p01), 013-015 pelo script + massa da era
#               MFA (p02, com RESET_BY_ADMIN historico SEM motivo), instantaneo dos dados, 016 pelo script, instantaneo de novo
#               (identico), catalogo igual ao da instalacao limpa, invariantes, historico preservado pelo NOT VALID e
#               restauracao logica (pg_dump/pg_restore) da base atualizada.
#
# Uso: db/tests/mfa-emergencia-016/run.sh  |  TESTE_PROJETO=nekkora-mfa016b db/tests/mfa-emergencia-016/run.sh
# Sucesso: os dois containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
# versoes elevadas: o MODELO (SUPERUSER 011 012 013 016; BYPASSRLS 014, padrao do script - P4-R2a-C). TESTE_ELEVAR so para
# DIAGNOSTICO (nao valida o modelo; vai ao ambiente.txt)
ELEVAR="${TESTE_ELEVAR:-011 012 013 016}"
PROJETO="${TESTE_PROJETO:-nekkora-mfa016}"
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
    [ "$c" != "nekkora-postgres" ] || falha "container da stack principal"
    docker container inspect "$c" >/dev/null 2>&1 && falha "container $c ja existe"
done

mkdir -p "$SAIDA/mig_limpa"
for n in $(seq -w 1 16); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig_limpa/"
done
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi; }
psqlc() { local c="$1" db="$2"; shift 2; docker exec -i "$c" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d "$db" "$@"; }
consulta() { psqlc "$1" "$2" -c "$3" < /dev/null; }
sobe() {   # $1 container, $2 diretorio de migrations, $3 quantidade esperada, $4 ultima versao aplicada no entrypoint
    MSYS_NO_PATHCONV=1 docker run -d --name "$1" --network none -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
        -e POSTGRES_PASSWORD="descartavel-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')" -e NEKKORA_ATE="$4" -e NEKKORA_ELEVAR="$ELEVAR" \
        -v "$(winpath "$2"):/nekkora/migracoes:ro" -v "$(winpath "$MODELO"):/nekkora/aplicar_como_migrador.sh:ro" \
        -v "$(winpath "$MODELO"):/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh:ro" \
        "$IMAGEM" postgres -c cluster_name="$1" > /dev/null
    for _ in $(seq 1 120); do
        [ "$(docker logs "$1" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
        docker ps -q -f "name=^$1\$" | grep -q . || break
        sleep 1
    done
    docker logs "$1" 2>&1 | grep -E "running /docker-entrypoint-initdb.d|aplicar_como_migrador|ERROR|FATAL|init process complete" > "$SAIDA/entrypoint_$1.txt" || true
    grep -qE "ERROR|FATAL|aplicar_como_migrador: FALHA" "$SAIDA/entrypoint_$1.txt" && falha "erro na aplicacao das migrations em $1 (ver entrypoint_$1.txt)"
    [ "$(grep -c 'aplicar_como_migrador: aplicada versao=' "$SAIDA/entrypoint_$1.txt")" = "$3" ] || falha "esperadas $3 migrations em $1"
    grep -q "aplicar_como_migrador: fim db=nekkora aplicadas=$3 estado=0|0|0" "$SAIDA/entrypoint_$1.txt" || falha "migrador nao terminou 0|0|0 em $1"
    [ "$(consulta "$1" nekkora 'SHOW cluster_name')" = "$1" ] || falha "cluster inesperado em $1"
}
modelo() {   # $1 container, $2 banco, $3 desde, $4 ate, $5 arquivo de saida: aplica no modelo suportado (servidor no ar)
    MSYS_NO_PATHCONV=1 docker exec -e NEKKORA_DB="$2" -e NEKKORA_DESDE="$3" -e NEKKORA_ATE="$4" -e NEKKORA_ELEVAR="$ELEVAR" "$1" sh /nekkora/aplicar_como_migrador.sh \
        > "$5" 2>&1 || { cat "$5"; falha "aplicacao $3-$4 em $1/$2 no modelo do migrador"; }
    grep -q "aplicar_como_migrador: fim db=$2 .* estado=0|0|0" "$5" || falha "migrador nao terminou 0|0|0 ($1/$2 $3-$4)"
}
estado_migrador() {   # $1 container: atributos|participacoes|sessoes do migrador e dono do banco nekkora
    consulta "$1" nekkora "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')
        || ' dono_do_banco=' || pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()"
}
invariantes() {   # 012 §15, 013 §8, 014 §8 (adaptacao unica e conferida), 016 §19 -- sempre DEPOIS da retirada
    local alvo="$1" nome="$2"
    { awk '/^-- === 15\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/012_security_rls.sql"
      awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/013_portal_externo.sql"
      awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/014_mfa_sessoes.sql" > "$SAIDA/_inv014.sql"
      "$PY" - "$SAIDA/_inv014.sql" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
a = "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) <> 1"
b = "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname AND p.policyname <> 'ops_function_context') <> 1"
assert s.count(a) == 1, 'trecho da 014 a adaptar nao encontrado exatamente uma vez'
sys.stdout.write(s.replace(a, b))
PY
      awk '/^-- === 19\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql"
    } > "$SAIDA/${nome}_invariantes.sql"
    [ "$(grep -c '^DO \$\$' "$SAIDA/${nome}_invariantes.sql")" = 4 ] || falha "extracao dos invariantes falhou"
    estado_migrador "$alvo" > "$SAIDA/${nome}_estado_migrador.txt"
    [ "$(cat "$SAIDA/${nome}_estado_migrador.txt")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "invariantes ($nome) fora do estado pos-retirada: $(cat "$SAIDA/${nome}_estado_migrador.txt")"
    psqlc "$alvo" nekkora < "$SAIDA/${nome}_invariantes.sql" > "$SAIDA/${nome}_invariantes.txt" 2>&1 || { cat "$SAIDA/${nome}_invariantes.txt"; falha "invariantes ($nome) falharam"; }
}

{
    echo "data_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit=$(cd "$REPO" && git rev-parse HEAD) branch=$(cd "$REPO" && git branch --show-current)"
    echo "git_status_porcelain:"; (cd "$REPO" && git status --porcelain) | sed 's/^/  /'
    echo "versoes_elevadas=$ELEVAR${TESTE_ELEVAR:+ (DIAGNOSTICO: diferente do modelo)} versoes_bypassrls=014 (padrao do script)"
    echo "containers=$LIMPA $POP (rede none, sem porta, sem volume nomeado) imagem=$IMAGEM"
    echo "migracoes:"; (cd "$SAIDA/mig_limpa" && sha256sum 0*.sql | sed 's/^/  /')
} > "$SAIDA/ambiente.txt"

# ================================================================== A. instalacao limpa 001-016
echo ">> A: instalacao limpa 001-016 ($LIMPA)"
sobe "$LIMPA" "$SAIDA/mig_limpa" 16 016
echo "server_version=$(consulta "$LIMPA" nekkora 'SHOW server_version')" >> "$SAIDA/ambiente.txt"
consulta "$LIMPA" nekkora "CREATE DATABASE nekkora_ref" > /dev/null
modelo "$LIMPA" nekkora_ref 001 015 "$SAIDA/A01_ref_001_015.txt"
psqlc "$LIMPA" nekkora_ref < "$LISTAGEM" > "$SAIDA/A01_catalogo_001_015.txt"
psqlc "$LIMPA" nekkora < "$LISTAGEM" > "$SAIDA/A01_catalogo_001_016.txt"
diff "$SAIDA/A01_catalogo_001_015.txt" "$SAIDA/A01_catalogo_001_016.txt" > "$SAIDA/A01_diff_catalogo.txt" || true
REMOCAO_PREVISTA='^< relacl\|mfa_events\|nekkora_platform\|INSERT\|grant=f$'
ACRESCIMO_PREVISTO='^> (col\|mfa_events\|(ops_request_id|reason_code)\||colacl\|[a-z_]+\|[a-z_]+\|nekkora_ops_mfa_owner\||colacl\|mfa_events\|[a-z_]+\|nekkora_platform\|INSERT\||con\|mfa_events\|mfa_events_(ops_request_id_key|ops_request_fk|ops_request_tipo_4|tipo_4_sem_ator|reason_code_so_admin|reset_admin_exige_motivo|reason_code_check)\||idx\|CREATE UNIQUE INDEX mfa_events_ops_request_id_key |rel\|mfa_events_ops_request_id_key\|i\||relacl\|mfa_events_ops_request_id_key\|migrador_nekkora\||pol\|[a-z_]+\|ops_function_context\||fn\|ops_|fnacl\|ops_|nsp\|ops\||ext\|sslinfo\|)'
{
    echo "removidas (<): $(grep -c '^<' "$SAIDA/A01_diff_catalogo.txt" || true)"
    echo "removidas fora do previsto: $(grep '^<' "$SAIDA/A01_diff_catalogo.txt" | grep -vcE "$REMOCAO_PREVISTA" || true)"
    echo "acrescentadas (>): $(grep -c '^>' "$SAIDA/A01_diff_catalogo.txt" || true)"
    echo "acrescentadas fora do previsto: $(grep '^>' "$SAIDA/A01_diff_catalogo.txt" | grep -vcE "$ACRESCIMO_PREVISTO" || true)"
    echo "por tipo:"; grep '^>' "$SAIDA/A01_diff_catalogo.txt" | cut -c3- | cut -d'|' -f1 | sort | uniq -c
} > "$SAIDA/A01_diff_resumo.txt"
[ "$(grep '^<' "$SAIDA/A01_diff_catalogo.txt" | grep -cE "$REMOCAO_PREVISTA" || true)" = 1 ] || falha "remocao prevista ausente"
[ "$(grep '^<' "$SAIDA/A01_diff_catalogo.txt" | grep -vcE "$REMOCAO_PREVISTA" || true)" = 0 ] || falha "a 016 alterou/removeu objeto de 001-015 fora do previsto"
[ "$(grep '^>' "$SAIDA/A01_diff_catalogo.txt" | grep -vcE "$ACRESCIMO_PREVISTO" || true)" = 0 ] || falha "a 016 acrescentou objeto fora do previsto"
[ "$(grep -c '^> pol|.*|ops_function_context|' "$SAIDA/A01_diff_catalogo.txt")" = 9 ] || falha "esperadas 9 politicas ops_function_context"
[ "$(grep -c '^> fn|ops_' "$SAIDA/A01_diff_catalogo.txt")" = 37 ] || falha "esperadas 37 funcoes publicas ops_*"
invariantes "$LIMPA" A02
psqlc "$LIMPA" nekkora < "$AQUI/sql/c_listagem_ops.sql" > "$SAIDA/A03_listagem_ops.txt"
[ "$(grep -c '^rel|.*|r|' "$SAIDA/A03_listagem_ops.txt")" = 21 ] || falha "esperadas 21 tabelas em ops"
grep -q '^nspacl|ops|nekkora_ops_mfa_owner|USAGE$' "$SAIDA/A03_listagem_ops.txt" || falha "USAGE do dono operacional em ops ausente"
[ "$(grep -c '^nspacl|ops|' "$SAIDA/A03_listagem_ops.txt")" = 3 ] || falha "ACL do schema ops inesperada (esperado: dono + USAGE/CREATE do migrador e USAGE do dono operacional)"
echo "   catalogo, invariantes e listagem de ops: OK"

# ================================================================== B. atualizacao de base populada 015 -> 016
echo ">> B: base populada 001-015 -> 016 ($POP)"
sobe "$POP" "$SAIDA/mig_limpa" 12 012
HASH_PRE="$(head -c 32 /dev/urandom | sha256sum | cut -c1-64)"
psqlc "$POP" nekkora -v n_exec=300 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/B01_massa_001_012.txt"
modelo "$POP" nekkora 013 015 "$SAIDA/B02_013_015.txt"
psqlc "$POP" nekkora < "$AQUI/sql/p02_popula_mfa.sql" | tail -1 > "$SAIDA/B03_massa_mfa.txt"
# instantaneo: md5 das linhas de cada tabela de public, com as colunas ANTERIORES a 016
consulta "$POP" nekkora "SELECT 'SELECT ' || quote_literal(c.relname) || ' || ''|'' || count(*) || ''|'' || coalesce(md5(string_agg(x::text, E''\\n'' ORDER BY x::text)), ''-'') FROM (SELECT '
    || string_agg(quote_ident(a.attname), ', ' ORDER BY a.attnum) || ' FROM public.' || quote_ident(c.relname) || ') x;'
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
    WHERE n.nspname = 'public' AND c.relkind = 'r' GROUP BY c.relname ORDER BY c.relname" > "$SAIDA/B04_instantaneo.sql"
[ "$(wc -l < "$SAIDA/B04_instantaneo.sql")" -ge 45 ] || falha "gerador de instantaneo incompleto"
psqlc "$POP" nekkora < "$SAIDA/B04_instantaneo.sql" > "$SAIDA/B04_dados_antes.txt"
RESETS_ANTES="$(consulta "$POP" nekkora "SELECT count(*) FROM mfa_events WHERE event_type = 3")"
[ "$RESETS_ANTES" -gt 0 ] || falha "massa sem reset administrativo historico"
modelo "$POP" nekkora 016 016 "$SAIDA/B05_aplica_016.txt"
grep -q 'invariantes_016_pos_retirada=OK' "$SAIDA/B05_aplica_016.txt" || falha "sec. 19 da 016 pos-retirada nao registrada na base populada"
psqlc "$POP" nekkora < "$SAIDA/B04_instantaneo.sql" > "$SAIDA/B06_dados_depois.txt"
diff "$SAIDA/B04_dados_antes.txt" "$SAIDA/B06_dados_depois.txt" > "$SAIDA/B06_diff_dados.txt" || falha "a 016 alterou dados existentes (ver B06_diff_dados.txt)"
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM mfa_events WHERE event_type = 3 AND reason_code IS NULL")" = "$RESETS_ANTES" ] || falha "historico de reset alterado"
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM mfa_events WHERE ops_request_id IS NOT NULL OR reason_code IS NOT NULL")" = 0 ] || falha "colunas novas preenchidas"
# reset novo sem motivo e recusado (transacao revertida)
if consulta "$POP" nekkora "BEGIN; INSERT INTO mfa_events (organization_id, user_id, actor_user_id, event_type, correlation_id)
      SELECT e.organization_id, e.user_id, e.actor_user_id, 3, gen_random_uuid() FROM mfa_events e WHERE e.event_type = 3 LIMIT 1; ROLLBACK" > "$SAIDA/B07_reset_sem_motivo.txt" 2>&1; then
    falha "reset administrativo novo sem motivo foi aceito"
fi
grep -q "mfa_events_reset_admin_exige_motivo" "$SAIDA/B07_reset_sem_motivo.txt" || falha "recusa do reset sem motivo sem a restricao esperada"
psqlc "$POP" nekkora < "$LISTAGEM" > "$SAIDA/B08_catalogo_atualizada.txt"
diff "$SAIDA/A01_catalogo_001_016.txt" "$SAIDA/B08_catalogo_atualizada.txt" > "$SAIDA/B08_diff_catalogo_limpa.txt" || falha "catalogo da atualizada difere da instalacao limpa"
psqlc "$POP" nekkora < "$AQUI/sql/c_listagem_ops.sql" > "$SAIDA/B08_listagem_ops.txt"
diff "$SAIDA/A03_listagem_ops.txt" "$SAIDA/B08_listagem_ops.txt" > /dev/null || falha "schema ops da atualizada difere da instalacao limpa"
invariantes "$POP" B09
# restauracao logica da base atualizada
docker exec "$POP" sh -c "psql -X -q -U nekkora -d postgres -c 'CREATE DATABASE restaurada_016' && pg_dump -U nekkora -Fc nekkora | pg_restore -U nekkora -d restaurada_016 --exit-on-error" \
    > "$SAIDA/B10_restauracao_logica.txt" 2>&1 || { cat "$SAIDA/B10_restauracao_logica.txt"; falha "restauracao logica da base atualizada"; }
CONTA="SELECT string_agg(format('%s.%s=%s', n.nspname, c.relname, (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname), false, true, '')))[1]::text), ',' ORDER BY n.nspname, c.relname) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname IN ('public', 'ops') AND c.relkind = 'r'"
[ "$(consulta "$POP" nekkora "$CONTA")" = "$(consulta "$POP" restaurada_016 "$CONTA")" ] || falha "contagens da restauracao logica divergem"
echo "   dados identicos, historico preservado, catalogo = limpa, invariantes e restauracao logica: OK"

{
    echo "A: 16 migrations; diff de catalogo: $(sed -n 1p "$SAIDA/A01_diff_resumo.txt"); $(sed -n 3p "$SAIDA/A01_diff_resumo.txt")"
    echo "A: modelo do migrador (dono do banco; SUPERUSER so em 011/012/013/016, BYPASSRLS so na 014): $(grep -c 'SUPERUSER revogado, estado 0|0|0' "$SAIDA/entrypoint_$LIMPA.txt") janelas SUPERUSER e $(grep -c 'versao=014 BYPASSRLS revogado, estado 0|0|0' "$SAIDA/entrypoint_$LIMPA.txt") BYPASSRLS, todas com retirada 0|0|0"
    echo "A: invariantes 012/013/014(adaptada)/016 DEPOIS da retirada ($(cat "$SAIDA/A02_estado_migrador.txt")): OK"
    echo "B: $(cat "$SAIDA/B01_massa_001_012.txt")"
    echo "B: $(cat "$SAIDA/B03_massa_mfa.txt")"
    echo "B: tabelas no instantaneo: $(wc -l < "$SAIDA/B04_dados_antes.txt"); diff de dados: vazio; resets historicos sem motivo preservados: $RESETS_ANTES"
    echo "B: catalogo e schema ops identicos a instalacao limpa; invariantes DEPOIS da retirada ($(cat "$SAIDA/B09_estado_migrador.txt")): OK; restauracao logica OK"
} > "$SAIDA/resumo.txt"
cat "$SAIDA/resumo.txt"
docker rm -f -v "$LIMPA" "$POP" > /dev/null   # -v: sem deixar o volume anonimo de dados (O-R2a-4)
echo "containers descartaveis removidos: $LIMPA $POP"
echo "saidas: $SAIDA"
