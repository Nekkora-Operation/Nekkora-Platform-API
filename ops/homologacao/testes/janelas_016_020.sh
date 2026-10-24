#!/usr/bin/env bash
# Janelas de elevacao 016-020 em FALHA e em SUCESSO, pelo runner real (missao PLATFORM-API-V1-003, item 4). SO em ambiente
# de laboratorio ensaio-* criado com HOMOLOG_ATE=021 e lista alternativa (HOMOLOG_ELEVACAO_ARQ) com 016-020 vinculadas
# AO PROPRIO id, parado antes da 016 (o "criar" ja faz essa parada). Nunca nos ambientes de homologacao (ensaio-api-v1-002, ensaio-api-v1-003).
#
# Para cada versao v de 016 a 020, em ordem:
#   F (falha DENTRO da janela): o banco recusa a migration depois de concedida a elevacao. Recusa real do servidor, sem
#     tocar no arquivo nem no runner: ALTER ROLE migrador_nekkora IN DATABASE nekkora SET default_transaction_read_only=on
#     (so as conexoes do migrador; o pre-voo e a revogacao rodam como admin_cluster). Esperado: codigo 2, janela "inicio"
#     e "fim ... revogado ... [falha]" da v no registro desta execucao, migrador 0|0|0, v fora do historico e nenhuma linha
#     success=false. Depois: RESET da configuracao (estado anterior).
#   S (sucesso): HOMOLOG_IMPLANTAR_ATE=v. Esperado: janela da v concluida "[fim da versao v]", v:true no historico, 0|0|0.
#   Na 019, ANTES de F: P (recusa pelo PRE-VOO, antes de conceder): funcao identity_recovery_codes_regenerate(text,text[])
#     criada por fora; esperado codigo 2, "pre-voo recusou", NENHUMA linha "inicio versao=019" nesta execucao, 0|0|0;
#     depois a funcao e removida.
# Ao fim: implantar sem parada (021, sem janela) e o ambiente LIBERADO.
# Uso: ops/homologacao/testes/janelas_016_020.sh <id-do-laboratorio> <diretorio de saida novo>
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
ID="${1:?id do laboratorio}"; OUT="${2:?diretorio de saida}"
[[ "$ID" =~ ^ensaio- ]] && [ "$ID" != ensaio-api-v1-002 ] && [ "$ID" != ensaio-api-v1-003 ] || { echo "ERRO: so em laboratorio ensaio-* (nunca nos ambientes de homologacao ensaio-api-v1-002 e ensaio-api-v1-003)" >&2; exit 3; }
[ ! -e "$OUT" ] || { echo "ERRO: $OUT ja existe" >&2; exit 3; }
mkdir -p "$OUT"
H="$AQUI/homologacao.sh"
set -a; . "$AQUI/saidas/$ID/ambiente.env"; set +a
[ "${HOMOLOG_ATE:-}" = 021 ] || { echo "ERRO: laboratorio sem HOMOLOG_ATE=021" >&2; exit 3; }
export HOMOLOG_ELEVACAO_ARQ="${HOMOLOG_ELEVACAO_ARQ:?defina HOMOLOG_ELEVACAO_ARQ (a lista alternativa usada no criar)}"
export PGPASSWORD; PGPASSWORD="$(cat "$HOMOLOG_SEGREDOS_HOST/postgres_admin")"
CF="-f $(cygpath -m "$AQUI/compose.homolog.yml" 2>/dev/null || echo "$AQUI/compose.homolog.yml")"
for x in $HOMOLOG_COMPOSE_EXTRA; do CF="$CF -f $(cygpath -m "$AQUI/$x" 2>/dev/null || echo "$AQUI/$x")"; done
adm() { docker compose $CF exec -T -e PGPASSWORD postgres psql -X -q -At -v ON_ERROR_STOP=1 -U admin_cluster -d nekkora "$@"; }
estado() { adm -c "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')" | tr -d '\r'; }
historico() { adm -c "SELECT string_agg(version || ':' || success, ' ' ORDER BY installed_rank) FROM flyway_schema_history" | tr -d '\r'; }
ultimo_registro() { ls -d "$AQUI/saidas/$ID"/implantacao-* 2>/dev/null | sort | tail -1; }
RES="$OUT/resultado.txt"; echo "versao|caso|codigo|janela|estado|historico|confere" > "$RES"
erros=0
anota() { echo "$*" >> "$RES"; echo "$*"; case "$*" in *"|NAO") erros=$((erros + 1));; esac; }
implanta() {   # $1 rotulo, $2 parada (vazio = sem parada) -> codigo do implantar; saida em $OUT/<rotulo>.txt
    local rc=0
    sleep 1   # registros implantacao-<instante> com resolucao de segundos: um por execucao
    if [ -n "$2" ]; then HOMOLOG_IMPLANTAR_ATE="$2" HOMOLOG_AUTORIZA_ELEVACAO=1 bash "$H" implantar "$ID" > "$OUT/$1.txt" 2>&1 || rc=$?
    else HOMOLOG_AUTORIZA_ELEVACAO=1 bash "$H" implantar "$ID" > "$OUT/$1.txt" 2>&1 || rc=$?; fi
    cp "$(ultimo_registro)/janelas_de_elevacao.txt" "$OUT/$1_janelas.txt" 2>/dev/null || : > "$OUT/$1_janelas.txt"
    cp "$(ultimo_registro)/implantacao.txt" "$OUT/$1_runner.txt" 2>/dev/null || : > "$OUT/$1_runner.txt"
    return "$rc"
}
[ "$(estado)" = "0|0|0" ] || { echo "ERRO: migrador fora de 0|0|0 no inicio" >&2; exit 3; }
echo "historico inicial: $(historico)" | tee "$OUT/historico_inicial.txt"
for v in 016 017 018 019 020; do
    if [ "$v" = 019 ]; then
        adm -c "CREATE FUNCTION public.identity_recovery_codes_regenerate(text, text[]) RETURNS boolean LANGUAGE sql AS 'SELECT false'"
        rc=0; implanta "${v}_P_preflight" "$v" || rc=$?
        e="$(estado)"; h="$(historico)"
        ok=NAO
        grep -q "pre-voo recusou" "$OUT/${v}_P_preflight.txt" && [ "$rc" = 2 ] && ! grep -q "inicio versao=$v" "$OUT/${v}_P_preflight_janelas.txt" \
            && [ "$e" = "0|0|0" ] && ! echo " $h " | grep -q " $v:" && ok=SIM
        anota "$v|P recusa pelo pre-voo antes de conceder|$rc|$(grep -c "versao=$v" "$OUT/${v}_P_preflight_janelas.txt") linhas da $v|$e|$h|$ok"
        adm -c "DROP FUNCTION public.identity_recovery_codes_regenerate(text, text[])"
    fi
    adm -c "ALTER ROLE migrador_nekkora IN DATABASE nekkora SET default_transaction_read_only = on"
    rc=0; implanta "${v}_F_falha" "$v" || rc=$?
    adm -c "ALTER ROLE migrador_nekkora IN DATABASE nekkora RESET default_transaction_read_only"
    e="$(estado)"; h="$(historico)"
    ok=NAO
    [ "$rc" = 2 ] && grep -q "inicio versao=$v SUPERUSER concedido" "$OUT/${v}_F_falha_janelas.txt" \
        && grep -q "fim    versao=$v SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0) \[falha\]" "$OUT/${v}_F_falha_janelas.txt" \
        && [ "$e" = "0|0|0" ] && ! echo " $h " | grep -qE " $v:| [0-9]+:false " && ok=SIM
    anota "$v|F falha DENTRO da janela (servidor recusa a escrita)|$rc|$(grep "versao=$v" "$OUT/${v}_F_falha_janelas.txt" | sed -E 's/^[^ ]+ //; s/ \(sha256.*//' | tr '\n' ';')|$e|$h|$ok"
    rc=0; implanta "${v}_S_sucesso" "$v" || rc=$?
    e="$(estado)"; h="$(historico)"
    ok=NAO
    grep -q "fim    versao=$v SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0) \[fim da versao $v\]" "$OUT/${v}_S_sucesso_janelas.txt" \
        && [ "$e" = "0|0|0" ] && echo " $h " | grep -q " $v:true " && ok=SIM
    anota "$v|S sucesso (parada em $v)|$rc|$(grep -c "versao=$v" "$OUT/${v}_S_sucesso_janelas.txt") linhas da $v|$e|$h|$ok"
done
rc=0; implanta "021_final" "" || rc=$?
e="$(estado)"; h="$(historico)"
ok=NAO; [ "$rc" = 0 ] && grep -q '^LIBERADO$' "$OUT/021_final.txt" && ! grep -q "versao=021" "$OUT/021_final_janelas.txt" && [ "$e" = "0|0|0" ] && ok=SIM
anota "021|implantacao final sem janela; ambiente LIBERADO|$rc|$(grep -c 'versao=021' "$OUT/021_final_janelas.txt") linhas da 021|$e|$h|$ok"
# motivos da falha registrados pelo runner/Flyway (so a mensagem do servidor, para a evidencia)
for v in 016 017 018 019 020; do echo "$v: $(grep -m1 -oE 'cannot execute [A-Za-z ]+ in a read-only transaction' "$OUT/${v}_F_falha_runner.txt" || echo '(mensagem nao encontrada)')"; done > "$OUT/motivos_da_falha.txt"
cat "$OUT/motivos_da_falha.txt"
echo "RESULTADO: $erros linha(s) fora do esperado" | tee -a "$RES"
[ "$erros" = 0 ]
