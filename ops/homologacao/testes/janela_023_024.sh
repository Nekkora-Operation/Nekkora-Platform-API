#!/usr/bin/env bash
# Janela de elevacao da 023 ou da 024 em RECUSA DO PRE-VOO, em FALHA e em SUCESSO, pelo runner real (missao de acessos de
# 2026-10-27, homologacao dirigida). Mesmo desenho de janela_022.sh. SO em laboratorio ensaio-* (nunca nos ambientes
# preservados ensaio-api-v1-002 e ensaio-api-v1-003) criado com lista alternativa (HOMOLOG_ELEVACAO_ARQ) com as linhas
# vinculadas AO PROPRIO id, LIBERADO na versao anterior e depois "atualizar" para a versao testada com a mesma lista.
#   P (recusa pelo PRE-VOO, antes de conceder): um papel criado por fora como membro de nekkora_identity_owner (conferencia
#     8 dos dois pre-voos). Esperado: codigo 2, "pre-voo recusou", nenhuma linha "inicio versao=<v>", 0|0|0, <v> fora do
#     historico; depois o papel e removido.
#   F (falha DENTRO da janela):
#     023: ALTER ROLE migrador_nekkora IN DATABASE nekkora SET default_transaction_read_only=on (como na 022);
#     024: um gatilho homonimo de um que a 024 cria (trg_client_workspace_grants_request), criado por fora: a 024 falha
#          DEPOIS do fechamento dos convites (sec. 2), dentro da mesma transacao. Prova, alem da retirada, que o
#          fechamento volta junto: os convites a fechar continuam abertos depois da falha.
#     Esperado: codigo 2, janela "inicio" e "fim ... revogado ... [falha]", 0|0|0, <v> fora do historico, nenhuma linha
#     success=false. Depois o artefato e removido.
#   S (sucesso): implantar sem parada. Esperado: "[fim da versao <v>]", <v>:true, 0|0|0, LIBERADO.
# Na 024, ANTES do P: massa declarada (sql/massa_024_convites.sql, como admin_cluster) e fotografia; DEPOIS do S, a
# conferencia da unica mudanca de dados (sql/conferencia_024_convites.sql): so os convites abertos de emissor sem
# autoridade fechados (motivo 4, sem revogador); convites validos, aceitos e o do emissor que perdeu e recuperou a
# autoridade ANTES da 024 intactos (limite declarado: a 024 olha a autoridade no momento da aplicacao); vinculos, papeis,
# concessoes e sessoes identicos.
# Uso: ops/homologacao/testes/janela_023_024.sh <id-do-laboratorio> <023|024> <diretorio de saida novo>
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
ID="${1:?id do laboratorio}"; v="${2:?versao (023 ou 024)}"; OUT="${3:?diretorio de saida}"
[[ "$ID" =~ ^ensaio- ]] && [ "$ID" != ensaio-api-v1-002 ] && [ "$ID" != ensaio-api-v1-003 ] || { echo "ERRO: so em laboratorio ensaio-* (nunca nos ambientes preservados ensaio-api-v1-002 e ensaio-api-v1-003)" >&2; exit 3; }
[ "$v" = 023 ] || [ "$v" = 024 ] || { echo "ERRO: versao $v (so 023 ou 024)" >&2; exit 3; }
[ ! -e "$OUT" ] || { echo "ERRO: $OUT ja existe" >&2; exit 3; }
mkdir -p "$OUT"
H="$AQUI/homologacao.sh"
set -a; . "$AQUI/saidas/$ID/ambiente.env"; set +a
[ "${HOMOLOG_ATE:-}" = "$v" ] || { echo "ERRO: laboratorio sem HOMOLOG_ATE=$v (rode o atualizar com a lista alternativa)" >&2; exit 3; }
[ "${HOMOLOG_TRANSPORTE:-}" = homologacao ] || { echo "ERRO: o laboratorio precisa do e-mail simulado (HOMOLOG_TRANSPORTE=homologacao)" >&2; exit 3; }
export HOMOLOG_ELEVACAO_ARQ="${HOMOLOG_ELEVACAO_ARQ:?defina HOMOLOG_ELEVACAO_ARQ (a lista alternativa usada no criar e no atualizar)}"
ant="$(printf '%03d' $((10#$v - 1)))"
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
implanta() {   # $1 rotulo -> codigo do implantar; saida em $OUT/<rotulo>.txt
    local rc=0
    sleep 1   # registros implantacao-<instante> com resolucao de segundos: um por execucao
    HOMOLOG_AUTORIZA_ELEVACAO=1 bash "$H" implantar "$ID" > "$OUT/$1.txt" 2>&1 || rc=$?
    cp "$(ultimo_registro)/janelas_de_elevacao.txt" "$OUT/$1_janelas.txt" 2>/dev/null || : > "$OUT/$1_janelas.txt"
    cp "$(ultimo_registro)/implantacao.txt" "$OUT/$1_runner.txt" 2>/dev/null || : > "$OUT/$1_runner.txt"
    cp "$(ultimo_registro)/preflight_$v.txt" "$OUT/$1_preflight_conferencias.txt" 2>/dev/null || :
    return "$rc"
}
[ "$(estado)" = "0|0|0" ] || { echo "ERRO: migrador fora de 0|0|0 no inicio" >&2; exit 3; }
h0="$(historico)"; echo "historico inicial: $h0" | tee "$OUT/historico_inicial.txt"
echo " $h0 " | grep -q " $ant:true " && ! echo " $h0 " | grep -q " $v:" || { echo "ERRO: historico inicial deveria ir ate a $ant" >&2; exit 3; }
# hashes completos: arquivo candidato no repositorio, copia do ambiente e linha da lista alternativa
REPO="$(cd "$AQUI/../.." && pwd)"
{ f=("$REPO"/db/migrations/${v}_*.sql)
  echo "repositorio $(sha256sum < "${f[0]}" | cut -c1-64) $(basename "${f[0]}")"
  echo "ambiente    $(sha256sum < "$AQUI/saidas/$ID/migracoes/$(basename "${f[0]}")" | cut -c1-64)"
  echo "lista       $(tr -d '\r' < "$HOMOLOG_ELEVACAO_ARQ" | awk -v v="$v" '$1 == v {print $2, $3, $4}')"
  echo "pre-voo     $(sha256sum < "$AQUI/implantacao/preflight/$v.sql" | cut -c1-64) implantacao/preflight/$v.sql"
} | tee "$OUT/hashes.txt"
[ "$(awk 'NR <= 3 {print $2}' "$OUT/hashes.txt" | sort -u | wc -l | tr -d ' ')" = 1 ] || { echo "ERRO: hashes divergentes (ver hashes.txt)" >&2; exit 3; }
grep -q "^lista       [0-9a-f]\{64\} tipo=superuser ambiente=$ID\$" "$OUT/hashes.txt" || { echo "ERRO: linha da $v nao vinculada exclusivamente a $ID" >&2; exit 3; }

if [ "$v" = 024 ]; then
    adm < "$AQUI/sql/massa_024_convites.sql" > "$OUT/massa.txt" 2>&1 || { cat "$OUT/massa.txt"; echo "ERRO: massa da 024" >&2; exit 3; }
    adm < "$AQUI/sql/conferencia_024_convites.sql" > "$OUT/conferencia_antes.txt" 2>&1 || { cat "$OUT/conferencia_antes.txt"; exit 3; }
    grep -q '^a_fechar=2$' "$OUT/conferencia_antes.txt" || { cat "$OUT/conferencia_antes.txt"; echo "ERRO: esperados 2 convites a fechar antes da 024" >&2; exit 3; }
fi

adm -c "CREATE ROLE zz_prevoo_${v} NOLOGIN; GRANT nekkora_identity_owner TO zz_prevoo_${v}"
rc=0; implanta "${v}_P_preflight" || rc=$?
adm -c "DROP ROLE zz_prevoo_${v}"
e="$(estado)"; h="$(historico)"
ok=NAO
grep -q "pre-voo recusou" "$OUT/${v}_P_preflight.txt" && [ "$rc" = 2 ] && ! grep -q "inicio versao=$v" "$OUT/${v}_P_preflight_janelas.txt" \
    && [ "$e" = "0|0|0" ] && ! echo " $h " | grep -q " $v:" && ok=SIM
anota "$v|P recusa pelo pre-voo antes de conceder|$rc|$(grep -c "versao=$v" "$OUT/${v}_P_preflight_janelas.txt") linhas da $v|$e|$h|$ok"

if [ "$v" = 023 ]; then
    adm -c "ALTER ROLE migrador_nekkora IN DATABASE nekkora SET default_transaction_read_only = on"
else
    adm -c "CREATE FUNCTION public.zz_janela_024() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END';
            CREATE TRIGGER trg_client_workspace_grants_request BEFORE UPDATE ON public.client_workspace_grants FOR EACH ROW EXECUTE FUNCTION public.zz_janela_024()"
fi
rc=0; implanta "${v}_F_falha" || rc=$?
if [ "$v" = 023 ]; then
    adm -c "ALTER ROLE migrador_nekkora IN DATABASE nekkora RESET default_transaction_read_only"
else
    adm < "$AQUI/sql/conferencia_024_convites.sql" > "$OUT/conferencia_depois_da_falha.txt" 2>&1
    adm -c "DROP TRIGGER trg_client_workspace_grants_request ON public.client_workspace_grants; DROP FUNCTION public.zz_janela_024()"
fi
e="$(estado)"; h="$(historico)"
ok=NAO
[ "$rc" = 2 ] && grep -q "inicio versao=$v SUPERUSER concedido" "$OUT/${v}_F_falha_janelas.txt" \
    && grep -q "fim    versao=$v SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0) \[falha\]" "$OUT/${v}_F_falha_janelas.txt" \
    && [ "$e" = "0|0|0" ] && ! echo " $h " | grep -qE " $v:| [0-9]+:false " && ok=SIM
if [ "$v" = 024 ] && [ "$ok" = SIM ]; then
    # o fechamento rodou dentro da transacao que falhou: os convites a fechar continuam abertos, nada mudou nos dados
    diff <(grep -v '^#' "$OUT/conferencia_antes.txt") <(grep -v '^#' "$OUT/conferencia_depois_da_falha.txt") > "$OUT/diff_falha.txt" || ok=NAO
fi
anota "$v|F falha DENTRO da janela$([ "$v" = 024 ] && echo ' (depois do fechamento dos convites; dados inalterados)')|$rc|$(grep "versao=$v" "$OUT/${v}_F_falha_janelas.txt" | sed -E 's/^[^ ]+ //; s/ \(sha256.*//' | tr '\n' ';')|$e|$h|$ok"

rc=0; implanta "${v}_S_sucesso" || rc=$?
e="$(estado)"; h="$(historico)"
ok=NAO
[ "$rc" = 0 ] && grep -q '^LIBERADO$' "$OUT/${v}_S_sucesso.txt" \
    && grep -q "fim    versao=$v SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0) \[fim da versao $v\]" "$OUT/${v}_S_sucesso_janelas.txt" \
    && [ "$e" = "0|0|0" ] && echo " $h " | grep -q " $v:true " && ok=SIM
anota "$v|S sucesso; ambiente LIBERADO|$rc|$(grep -c "versao=$v" "$OUT/${v}_S_sucesso_janelas.txt") linhas da $v|$e|$h|$ok"

if [ "$v" = 024 ]; then
    adm < "$AQUI/sql/conferencia_024_convites.sql" > "$OUT/conferencia_depois.txt" 2>&1
    ok=NAO
    grep -q '^a_fechar=0$' "$OUT/conferencia_depois.txt" \
        && grep -q '^fechados_motivo_4_sem_revogador=2$' "$OUT/conferencia_depois.txt" \
        && diff <(grep '^intactos|' "$OUT/conferencia_antes.txt") <(grep '^intactos|' "$OUT/conferencia_depois.txt") > /dev/null \
        && diff <(grep '^acessos|' "$OUT/conferencia_antes.txt") <(grep '^acessos|' "$OUT/conferencia_depois.txt") > /dev/null \
        && grep -q '^perdeu_e_recuperou_antes=aberto$' "$OUT/conferencia_depois.txt" && ok=SIM
    anota "024|D dados: so os 2 convites de emissor sem autoridade fechados (motivo 4); validos, aceito, o de quem perdeu e recuperou antes e os acessos intactos|-|-|-|-|$ok"
    grep -E '^(a_fechar|fechados|perdeu)' "$OUT/conferencia_depois.txt"
fi
echo "$v: $(grep -m1 -oE 'cannot execute [A-Za-z ]+ in a read-only transaction|trigger "trg_client_workspace_grants_request" for relation "client_workspace_grants" already exists' "$OUT/${v}_F_falha_runner.txt" || echo '(mensagem nao encontrada)')" > "$OUT/motivo_da_falha.txt"
echo "$v: pre-voo $(grep -m1 '|FALHA|' "$OUT/${v}_P_preflight_preflight_conferencias.txt" 2>/dev/null || echo '(conferencia em falha nao encontrada)')" >> "$OUT/motivo_da_falha.txt"
cat "$OUT/motivo_da_falha.txt"
echo "RESULTADO: $erros linha(s) fora do esperado" | tee -a "$RES"
[ "$erros" = 0 ]
