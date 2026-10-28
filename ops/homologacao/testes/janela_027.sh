#!/usr/bin/env bash
# Janela de elevacao da 027 (lote do expurgo das categorias 1 e 2, D-RET-2) em RECUSA DO PRE-VOO, em FALHA e em SUCESSO, pelo
# runner real, e os lotes depois dela. Mesmo desenho de janela_026.sh. SO em laboratorio ensaio-* (nunca nos preservados) com a
# linha da 027 vinculada AO PROPRIO id na lista alternativa, LIBERADO na 026 (com a massa de sql/massa_026_canal.sql) e depois
# "atualizar" para a 027 com a mesma lista.
#   M (massa declarada): sql/massa_027_expurgo.sql - 5 execucoes com um evento cada e 5 logs, recebidos num dia passado e
#     FECHADO pelo fechamento real; politica finita de 1 dia nas categorias 1 e 2.
#   P (recusa pelo PRE-VOO, antes de conceder): um papel criado por fora como membro de nekkora_retention_owner (conferencia 7).
#     Esperado: codigo 2, "pre-voo recusou", nenhuma linha "inicio versao=027", 0|0|0, 027 fora do historico.
#   F (falha DENTRO da janela): ALTER ROLE migrador_nekkora IN DATABASE nekkora SET default_transaction_read_only=on (como na
#     023). Esperado: codigo 2, janela "inicio" e "fim ... revogado ... [falha]", 0|0|0, 027 fora do historico, nenhuma linha
#     success=false, funcao na forma da 026 (um lote materializado) e nada expurgado.
#   S (sucesso): "[fim da versao 027]", 027:true, 0|0|0, LIBERADO; funcao com os tres lotes materializados.
#   D (lotes, plano forcado como na suite): lote 2 -> 2, 2, 1, 0 nas categorias 1 e 2; lotes 0, 1001 e nulo recusados; auditoria
#     com o numero real; EXECUTE do expurgo sem concessao (D-RET-1: expurgo desligado fora do laboratorio).
# Uso: ops/homologacao/testes/janela_027.sh <id-do-laboratorio> <diretorio de saida novo>
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
ID="${1:?id do laboratorio}"; OUT="${2:?diretorio de saida}"; v=027
APROVADO=632d1ff26492a89ca1f21f73c304aa4ce738a7a0d6e301c62dcd772f10a210aa
[[ "$ID" =~ ^ensaio- ]] && [ "$ID" != ensaio-api-v1-002 ] && [ "$ID" != ensaio-api-v1-003 ] && [ "$ID" != ensaio-api-v1-004b ] \
    || { echo "ERRO: so em laboratorio ensaio-* (nunca nos ambientes preservados)" >&2; exit 3; }
[ ! -e "$OUT" ] || { echo "ERRO: $OUT ja existe" >&2; exit 3; }
mkdir -p "$OUT"
H="$AQUI/homologacao.sh"
set -a; . "$AQUI/saidas/$ID/ambiente.env"; set +a
[ "${HOMOLOG_ATE:-}" = 027 ] || { echo "ERRO: laboratorio sem HOMOLOG_ATE=027 (rode o atualizar 026 -> 027 com a lista alternativa)" >&2; exit 3; }
export HOMOLOG_ELEVACAO_ARQ="${HOMOLOG_ELEVACAO_ARQ:?defina HOMOLOG_ELEVACAO_ARQ (a lista alternativa usada no criar e no atualizar)}"
export PGPASSWORD; PGPASSWORD="$(cat "$HOMOLOG_SEGREDOS_HOST/postgres_admin")"
CF="-f $(cygpath -m "$AQUI/compose.homolog.yml" 2>/dev/null || echo "$AQUI/compose.homolog.yml")"
for x in $HOMOLOG_COMPOSE_EXTRA; do CF="$CF -f $(cygpath -m "$AQUI/$x" 2>/dev/null || echo "$AQUI/$x")"; done
adm() { docker compose $CF exec -T -e PGPASSWORD postgres psql -X -q -At -v ON_ERROR_STOP=1 -U admin_cluster -d nekkora "$@"; }
estado() { adm -c "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')" | tr -d '\r'; }
historico() { adm -c "SELECT string_agg(version || ':' || success, ' ' ORDER BY installed_rank) FROM flyway_schema_history" | tr -d '\r'; }
lotes_na_funcao() { adm -c "SELECT count(*) FROM regexp_matches(pg_get_functiondef('public.purge_expired_records(uuid,integer,uuid,integer)'::regprocedure), 'AS MATERIALIZED', 'g')" | tr -d '\r'; }
expurgados() { adm -c "SELECT (SELECT count(*) FROM execution_events WHERE organization_id = '00000000-0026-4000-8000-000000000001' AND purged_at IS NOT NULL) || '|' || (SELECT count(*) FROM logs WHERE organization_id = '00000000-0026-4000-8000-000000000001' AND purged_at IS NOT NULL)" | tr -d '\r'; }
ultimo_registro() { ls -d "$AQUI/saidas/$ID"/implantacao-* 2>/dev/null | sort | tail -1; }
RES="$OUT/resultado.txt"; echo "versao|caso|codigo|janela|estado|historico|confere" > "$RES"
erros=0
anota() { echo "$*" >> "$RES"; echo "$*"; case "$*" in *"|NAO") erros=$((erros + 1));; esac; }
implanta() {
    local rc=0
    sleep 1
    HOMOLOG_AUTORIZA_ELEVACAO=1 bash "$H" implantar "$ID" > "$OUT/$1.txt" 2>&1 || rc=$?
    cp "$(ultimo_registro)/janelas_de_elevacao.txt" "$OUT/$1_janelas.txt" 2>/dev/null || : > "$OUT/$1_janelas.txt"
    cp "$(ultimo_registro)/implantacao.txt" "$OUT/$1_runner.txt" 2>/dev/null || : > "$OUT/$1_runner.txt"
    cp "$(ultimo_registro)/preflight_$v.txt" "$OUT/$1_preflight_conferencias.txt" 2>/dev/null || :
    return "$rc"
}
[ "$(estado)" = "0|0|0" ] || { echo "ERRO: migrador fora de 0|0|0 no inicio" >&2; exit 3; }
h0="$(historico)"; echo "historico inicial: $h0" | tee "$OUT/historico_inicial.txt"
echo " $h0 " | grep -q " 026:true " && ! echo " $h0 " | grep -q " 027:" || { echo "ERRO: historico inicial deveria ir ate a 026" >&2; exit 3; }
REPO="$(cd "$AQUI/../.." && pwd)"
{ echo "commit      $(git -C "$REPO" rev-parse HEAD) (arvore limpa: $([ -z "$(git -C "$REPO" status --porcelain -- db ops/homologacao)" ] && echo sim || echo NAO))"
  echo "repositorio $(sha256sum < "$REPO"/db/migrations/027_lote_expurgo_categorias.sql | cut -c1-64) db/migrations/027_lote_expurgo_categorias.sql"
  echo "ambiente    $(sha256sum < "$AQUI/saidas/$ID/migracoes/027_lote_expurgo_categorias.sql" | cut -c1-64)"
  echo "lista       $(tr -d '\r' < "$HOMOLOG_ELEVACAO_ARQ" | awk '$1 == "027" {print $2, $3, $4}')"
  echo "aprovado    $APROVADO (D-ELEV-027, 28/10)"
  echo "pre-voo     $(sha256sum < "$AQUI/implantacao/preflight/027.sql" | cut -c1-64) ops/homologacao/implantacao/preflight/027.sql"
} | tee "$OUT/hashes.txt"
[ "$(awk 'NR >= 2 && NR <= 5 {print $2}' "$OUT/hashes.txt" | sort -u | wc -l | tr -d ' ')" = 1 ] || { echo "ERRO: hashes divergentes do aprovado (ver hashes.txt)" >&2; exit 3; }
grep -q "^lista       [0-9a-f]\{64\} tipo=superuser ambiente=$ID\$" "$OUT/hashes.txt" || { echo "ERRO: linha da 027 nao vinculada exclusivamente a $ID" >&2; exit 3; }
grep -q "(arvore limpa: sim)" "$OUT/hashes.txt" || { echo "ERRO: arvore do repositorio com mudancas em db/ ou ops/homologacao/" >&2; exit 3; }

adm < "$AQUI/sql/massa_027_expurgo.sql" > "$OUT/massa.txt" 2>&1 || { cat "$OUT/massa.txt"; echo "ERRO: massa da 027" >&2; exit 3; }
grep -q '^elegiveis=5|5$' "$OUT/massa.txt" && grep -q '^dias_fechados=4:5:2,5:5:2$' "$OUT/massa.txt" || { cat "$OUT/massa.txt"; echo "ERRO: massa da 027 fora do esperado" >&2; exit 3; }
[ "$(lotes_na_funcao)" = 1 ] || { echo "ERRO: funcao fora da forma da 026" >&2; exit 3; }

adm -c "CREATE ROLE zz_prevoo_027 NOLOGIN; GRANT nekkora_retention_owner TO zz_prevoo_027"
rc=0; implanta "027_P_preflight" || rc=$?
adm -c "DROP ROLE zz_prevoo_027"
e="$(estado)"; h="$(historico)"
ok=NAO
grep -q "pre-voo recusou" "$OUT/027_P_preflight.txt" && [ "$rc" = 2 ] && ! grep -q "inicio versao=027" "$OUT/027_P_preflight_janelas.txt" \
    && [ "$e" = "0|0|0" ] && ! echo " $h " | grep -q " 027:" && [ "$(lotes_na_funcao)" = 1 ] && ok=SIM
anota "027|P recusa pelo pre-voo antes de conceder|$rc|$(grep -c "versao=027" "$OUT/027_P_preflight_janelas.txt") linhas da 027|$e|$h|$ok"

adm -c "ALTER ROLE migrador_nekkora IN DATABASE nekkora SET default_transaction_read_only = on"
rc=0; implanta "027_F_falha" || rc=$?
adm -c "ALTER ROLE migrador_nekkora IN DATABASE nekkora RESET default_transaction_read_only"
e="$(estado)"; h="$(historico)"
ok=NAO
[ "$rc" = 2 ] && grep -q "inicio versao=027 SUPERUSER concedido" "$OUT/027_F_falha_janelas.txt" \
    && grep -q "fim    versao=027 SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0) \[falha\]" "$OUT/027_F_falha_janelas.txt" \
    && [ "$e" = "0|0|0" ] && ! echo " $h " | grep -qE " 027:| [0-9]+:false " && [ "$(lotes_na_funcao)" = 1 ] && [ "$(expurgados)" = "0|0" ] && ok=SIM
anota "027|F falha DENTRO da janela (funcao na forma da 026; nada expurgado)|$rc|$(grep "versao=027" "$OUT/027_F_falha_janelas.txt" | sed -E 's/^[^ ]+ //; s/ \(sha256.*//' | tr '\n' ';')|$e|$h|$ok"

rc=0; implanta "027_S_sucesso" || rc=$?
e="$(estado)"; h="$(historico)"
ok=NAO
[ "$rc" = 0 ] && grep -q '^LIBERADO$' "$OUT/027_S_sucesso.txt" \
    && grep -q "fim    versao=027 SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0) \[fim da versao 027\]" "$OUT/027_S_sucesso_janelas.txt" \
    && [ "$e" = "0|0|0" ] && echo " $h " | grep -q " 027:true " && [ "$(lotes_na_funcao)" = 3 ] && [ "$(expurgados)" = "0|0" ] && ok=SIM
anota "027|S sucesso; ambiente LIBERADO; tres lotes materializados; nada expurgado na aplicacao|$rc|$(grep -c "versao=027" "$OUT/027_S_sucesso_janelas.txt") linhas da 027|$e|$h|$ok"

# D: lotes sob o plano forcado da suite (laco aninhado sem Materialize, Unique nem hash)
FORCA="SET LOCAL enable_hashjoin = off; SET LOCAL enable_mergejoin = off; SET LOCAL enable_hashagg = off; SET LOCAL enable_material = off; SET LOCAL enable_sort = off;"
for c in 1 2; do
    seq_res=""
    for _ in 1 2 3 4; do
        seq_res="$seq_res $(adm -c "BEGIN; $FORCA SELECT public.purge_expired_records('00000000-0026-4000-8000-000000000001', $c, '00000000-0026-4000-8000-000000000002', 2); COMMIT;" | grep -E '^[0-9]+$')"
    done
    echo "categoria=$c lote=2 chamadas=$seq_res" | tee -a "$OUT/lotes.txt"
done
for l in 0 1001 NULL; do
    o="$(adm -c "BEGIN; SELECT public.purge_expired_records('00000000-0026-4000-8000-000000000001', 1, '00000000-0026-4000-8000-000000000002', $l); ROLLBACK;" 2>&1 || true)"
    echo "lote=$l|$(echo "$o" | grep -m1 -oE 'ERROR: .*' | cut -c1-120)" | tee -a "$OUT/controles.txt"
done
aud="$(adm -c "SELECT string_agg(category || ':' || records_purged, ',' ORDER BY occurred_at) FROM organization_purge_runs WHERE organization_id = '00000000-0026-4000-8000-000000000001' AND category IN (1, 2)" | tr -d '\r')"
exe="$(adm -c "SELECT count(*) FROM pg_roles r WHERE NOT r.rolsuper AND r.rolname <> 'nekkora_retention_owner' AND has_function_privilege(r.oid, 'public.purge_expired_records(uuid,integer,uuid,integer)', 'EXECUTE')" | tr -d '\r')"
echo "auditoria=$aud execute_concedido=$exe expurgados=$(expurgados)" | tee "$OUT/auditoria.txt"
ok=NAO
grep -qx 'categoria=1 lote=2 chamadas= 2 2 1 0' "$OUT/lotes.txt" && grep -qx 'categoria=2 lote=2 chamadas= 2 2 1 0' "$OUT/lotes.txt" \
    && [ "$(grep -cE 'lote de expurgo (deve ser positivo|excede o teto)' "$OUT/controles.txt")" = 3 ] \
    && [ "$aud" = "1:2,1:2,1:1,1:0,2:2,2:2,2:1,2:0" ] && [ "$exe" = 0 ] && [ "$(expurgados)" = "5|5" ] && ok=SIM
anota "027|D lotes 2,2,1,0 nas categorias 1 e 2 (plano forcado); 0, 1001 e nulo recusados; auditoria real; sem EXECUTE|-|-|$(estado)|-|$ok"
echo "027: $(grep -m1 -oE 'cannot execute [A-Za-z ]+ in a read-only transaction' "$OUT/027_F_falha_runner.txt" || echo '(mensagem nao encontrada)')" > "$OUT/motivo_da_falha.txt"
echo "027: pre-voo $(grep -m1 '|FALHA|' "$OUT/027_P_preflight_preflight_conferencias.txt" 2>/dev/null || echo '(conferencia em falha nao encontrada)')" >> "$OUT/motivo_da_falha.txt"
cat "$OUT/motivo_da_falha.txt"
echo "RESULTADO: $erros linha(s) fora do esperado" | tee -a "$RES"
[ "$erros" = 0 ]
