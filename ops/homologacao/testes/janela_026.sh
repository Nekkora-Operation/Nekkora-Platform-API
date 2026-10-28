#!/usr/bin/env bash
# Janela de elevacao da 026 em RECUSA DO PRE-VOO, em FALHA e em SUCESSO, pelo runner real, e a contabilizacao e o expurgo dos
# eventos do Agent depois dela (missao Platform-Agent, D-ELEV-026 aprovada em 28/10 SO para ensaio-agent-026). Mesmo desenho de
# janela_023_024.sh. SO em laboratorio ensaio-* (nunca nos preservados) criado com lista alternativa (HOMOLOG_ELEVACAO_ARQ) com
# a linha da 026 vinculada AO PROPRIO id, LIBERADO na 025 e depois "atualizar" para a 026 com a mesma lista.
#   M (massa declarada, antes de tudo): sql/massa_026_canal.sql (organizacao ativada, registro e 6 eventos pelos papeis reais).
#   P (recusa pelo PRE-VOO, antes de conceder): um papel criado por fora como membro de nekkora_retention_owner (conferencia 9
#     do pre-voo). Esperado: codigo 2, "pre-voo recusou", nenhuma linha "inicio versao=026", 0|0|0, 026 fora do historico.
#   F (falha DENTRO da janela): um gatilho homonimo do de redacao (trg_agent_run_events_enforce_redaction_or_reject), criado
#     por fora: a 026 falha DEPOIS dos ALTER de catalogo, das colunas novas e do gatilho de received_at, na mesma transacao.
#     Esperado: codigo 2, janela "inicio" e "fim ... revogado ... [falha]", 0|0|0, 026 fora do historico, nenhuma linha
#     success=false, eventos e registro do canal identicos (nada da 026 ficou).
#   S (sucesso): esperado "[fim da versao 026]", 026:true, 0|0|0, LIBERADO; eventos e registro identicos (colunas anteriores).
#   D (dados): sql/conferencia_026_retencao.sql - barreira do recurso 6 na ingestao, fechamento real do dia, expurgo da
#     categoria 3 em lotes (so o dia fechado), identidade/hash/instantes e ACK (agent_runs) inalterados, retencao identificada
#     como dimensao propria, EXECUTE do expurgo sem concessao, reenvio sem duplicar.
# Uso: ops/homologacao/testes/janela_026.sh <id-do-laboratorio> <diretorio de saida novo>
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
ID="${1:?id do laboratorio}"; OUT="${2:?diretorio de saida}"; v=026
[[ "$ID" =~ ^ensaio- ]] && [ "$ID" != ensaio-api-v1-002 ] && [ "$ID" != ensaio-api-v1-003 ] && [ "$ID" != ensaio-api-v1-004b ] \
    || { echo "ERRO: so em laboratorio ensaio-* (nunca nos ambientes preservados)" >&2; exit 3; }
[ ! -e "$OUT" ] || { echo "ERRO: $OUT ja existe" >&2; exit 3; }
mkdir -p "$OUT"
H="$AQUI/homologacao.sh"
set -a; . "$AQUI/saidas/$ID/ambiente.env"; set +a
[ "${HOMOLOG_ATE:-}" = 026 ] || { echo "ERRO: laboratorio sem HOMOLOG_ATE=026 (rode o atualizar 025 -> 026 com a lista alternativa)" >&2; exit 3; }
export HOMOLOG_ELEVACAO_ARQ="${HOMOLOG_ELEVACAO_ARQ:?defina HOMOLOG_ELEVACAO_ARQ (a lista alternativa usada no criar e no atualizar)}"
export PGPASSWORD; PGPASSWORD="$(cat "$HOMOLOG_SEGREDOS_HOST/postgres_admin")"
CF="-f $(cygpath -m "$AQUI/compose.homolog.yml" 2>/dev/null || echo "$AQUI/compose.homolog.yml")"
for x in $HOMOLOG_COMPOSE_EXTRA; do CF="$CF -f $(cygpath -m "$AQUI/$x" 2>/dev/null || echo "$AQUI/$x")"; done
adm() { docker compose $CF exec -T -e PGPASSWORD postgres psql -X -q -At -v ON_ERROR_STOP=1 -U admin_cluster -d nekkora "$@"; }
estado() { adm -c "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')" | tr -d '\r'; }
historico() { adm -c "SELECT string_agg(version || ':' || success, ' ' ORDER BY installed_rank) FROM flyway_schema_history" | tr -d '\r'; }
canal() {   # retrato do canal da massa nas colunas ANTERIORES a 026
    adm -c "SELECT md5(string_agg(organization_id || agent_run_id::text || sequence || content_sha256 || envelope::text || source || event_code || severity || category
              || coalesce(step_id, '-') || observed_utc || received_at, ',' ORDER BY sequence)) || '|' || (SELECT md5(row(r.*)::text) FROM agent_runs r
              WHERE agent_run_id = '00000000-0026-4000-8000-00000000000d') FROM agent_run_events WHERE agent_run_id = '00000000-0026-4000-8000-00000000000d'" | tr -d '\r'
}
ultimo_registro() { ls -d "$AQUI/saidas/$ID"/implantacao-* 2>/dev/null | sort | tail -1; }
RES="$OUT/resultado.txt"; echo "versao|caso|codigo|janela|estado|historico|confere" > "$RES"
erros=0
anota() { echo "$*" >> "$RES"; echo "$*"; case "$*" in *"|NAO") erros=$((erros + 1));; esac; }
implanta() {   # $1 rotulo -> codigo do implantar; saida em $OUT/<rotulo>.txt
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
echo " $h0 " | grep -q " 025:true " && ! echo " $h0 " | grep -q " 026:" || { echo "ERRO: historico inicial deveria ir ate a 025" >&2; exit 3; }
# hashes COMPLETOS: candidato no repositorio (e o commit), copia do ambiente, linha da lista alternativa e pre-voo
REPO="$(cd "$AQUI/../.." && pwd)"
{ echo "commit      $(git -C "$REPO" rev-parse HEAD) (arvore limpa: $([ -z "$(git -C "$REPO" status --porcelain -- db ops/homologacao/implantacao)" ] && echo sim || echo NAO))"
  echo "repositorio $(sha256sum < "$REPO"/db/migrations/026_retencao_eventos_agent.sql | cut -c1-64) 026_retencao_eventos_agent.sql"
  echo "ambiente    $(sha256sum < "$AQUI/saidas/$ID/migracoes/026_retencao_eventos_agent.sql" | cut -c1-64)"
  echo "lista       $(tr -d '\r' < "$HOMOLOG_ELEVACAO_ARQ" | awk '$1 == "026" {print $2, $3, $4}')"
  echo "pre-voo     $(sha256sum < "$AQUI/implantacao/preflight/026.sql" | cut -c1-64) implantacao/preflight/026.sql"
  echo "aprovado    5f6579e66606329992b529c4e4b92498e75350312cfdb6a047bcce14b64904a6 (D-ELEV-026, 28/10)"
} | tee "$OUT/hashes.txt"
[ "$(awk 'NR >= 2 && NR <= 4 {print $2} NR == 6 {print $2}' "$OUT/hashes.txt" | sort -u | wc -l | tr -d ' ')" = 1 ] || { echo "ERRO: hashes divergentes do aprovado (ver hashes.txt)" >&2; exit 3; }
grep -q "^lista       [0-9a-f]\{64\} tipo=superuser ambiente=$ID\$" "$OUT/hashes.txt" || { echo "ERRO: linha da 026 nao vinculada exclusivamente a $ID" >&2; exit 3; }
grep -q "(arvore limpa: sim)" "$OUT/hashes.txt" || { echo "ERRO: arvore do repositorio com mudancas em db/ ou implantacao/" >&2; exit 3; }

adm < "$AQUI/sql/massa_026_canal.sql" > "$OUT/massa.txt" 2>&1 || { cat "$OUT/massa.txt"; echo "ERRO: massa da 026" >&2; exit 3; }
grep -q '^massa_026=6$' "$OUT/massa.txt" || { cat "$OUT/massa.txt"; echo "ERRO: massa da 026 sem os 6 eventos" >&2; exit 3; }
c0="$(canal)"; echo "$c0" > "$OUT/canal_antes.txt"

adm -c "CREATE ROLE zz_prevoo_026 NOLOGIN; GRANT nekkora_retention_owner TO zz_prevoo_026"
rc=0; implanta "026_P_preflight" || rc=$?
adm -c "DROP ROLE zz_prevoo_026"
e="$(estado)"; h="$(historico)"
ok=NAO
grep -q "pre-voo recusou" "$OUT/026_P_preflight.txt" && [ "$rc" = 2 ] && ! grep -q "inicio versao=026" "$OUT/026_P_preflight_janelas.txt" \
    && [ "$e" = "0|0|0" ] && ! echo " $h " | grep -q " 026:" && ok=SIM
anota "026|P recusa pelo pre-voo antes de conceder|$rc|$(grep -c "versao=026" "$OUT/026_P_preflight_janelas.txt") linhas da 026|$e|$h|$ok"

adm -c "CREATE TRIGGER trg_agent_run_events_enforce_redaction_or_reject BEFORE UPDATE ON public.agent_run_events FOR EACH ROW EXECUTE FUNCTION public.prevent_update()"
rc=0; implanta "026_F_falha" || rc=$?
adm -c "DROP TRIGGER trg_agent_run_events_enforce_redaction_or_reject ON public.agent_run_events"
e="$(estado)"; h="$(historico)"; c1="$(canal)"
ok=NAO
[ "$rc" = 2 ] && grep -q "inicio versao=026 SUPERUSER concedido" "$OUT/026_F_falha_janelas.txt" \
    && grep -q "fim    versao=026 SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0) \[falha\]" "$OUT/026_F_falha_janelas.txt" \
    && [ "$e" = "0|0|0" ] && ! echo " $h " | grep -qE " 026:| [0-9]+:false " && [ "$c1" = "$c0" ] \
    && [ "$(adm -c "SELECT count(*) FROM pg_attribute WHERE attrelid = 'public.agent_run_events'::regclass AND attname = 'purged_at' AND NOT attisdropped")" = 0 ] && ok=SIM
anota "026|F falha DENTRO da janela (depois dos ALTER; nada da 026 ficou; canal identico)|$rc|$(grep "versao=026" "$OUT/026_F_falha_janelas.txt" | sed -E 's/^[^ ]+ //; s/ \(sha256.*//' | tr '\n' ';')|$e|$h|$ok"

rc=0; implanta "026_S_sucesso" || rc=$?
e="$(estado)"; h="$(historico)"; c2="$(canal)"
ok=NAO
[ "$rc" = 0 ] && grep -q '^LIBERADO$' "$OUT/026_S_sucesso.txt" \
    && grep -q "fim    versao=026 SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0) \[fim da versao 026\]" "$OUT/026_S_sucesso_janelas.txt" \
    && [ "$e" = "0|0|0" ] && echo " $h " | grep -q " 026:true " && [ "$c2" = "$c0" ] && ok=SIM
anota "026|S sucesso; ambiente LIBERADO; canal identico nas colunas anteriores|$rc|$(grep -c "versao=026" "$OUT/026_S_sucesso_janelas.txt") linhas da 026|$e|$h|$ok"

adm < "$AQUI/sql/conferencia_026_retencao.sql" > "$OUT/conferencia_retencao.txt" 2>&1 || { cat "$OUT/conferencia_retencao.txt"; anota "026|D conferencia da retencao executada|-|-|-|-|NAO"; }
grep -E '^[a-z_]+=' "$OUT/conferencia_retencao.txt" | tee "$OUT/conferencia_retencao_resumo.txt"
ok=NAO
grep -qx 'barreira_recurso_6_hoje=1' "$OUT/conferencia_retencao.txt" && grep -qx 'novos_recebidos_hoje=2' "$OUT/conferencia_retencao.txt" \
    && grep -qx 'fechamento=6|true|2' "$OUT/conferencia_retencao.txt" && grep -qx 'expurgo_lotes=4,2,0' "$OUT/conferencia_retencao.txt" \
    && grep -qx 'redigidos=6|intactos=2' "$OUT/conferencia_retencao.txt" && grep -qx 'identidade_hash_instantes_iguais=true' "$OUT/conferencia_retencao.txt" \
    && grep -qx 'agent_runs_igual=true|ack=8|8|ack_antes=8|8' "$OUT/conferencia_retencao.txt" && grep -qx 'retencao=PARTIALLY_PURGED|6' "$OUT/conferencia_retencao.txt" \
    && grep -qx 'auditoria=4,2,0' "$OUT/conferencia_retencao.txt" && grep -qx 'execute_purge_concedido=0' "$OUT/conferencia_retencao.txt" \
    && grep -qx 'reenvio=1|true' "$OUT/conferencia_retencao.txt" && ok=SIM
anota "026|D contabilizacao (barreira, fechamento) e expurgo (lotes 4,2,0; identidade, hash e ACK inalterados; retencao PARTIALLY_PURGED; sem EXECUTE; reenvio sem duplicar)|-|-|$(estado)|-|$ok"
echo "026: $(grep -m1 -oE 'trigger "trg_agent_run_events_enforce_redaction_or_reject" for relation "agent_run_events" already exists' "$OUT/026_F_falha_runner.txt" || echo '(mensagem nao encontrada)')" > "$OUT/motivo_da_falha.txt"
echo "026: pre-voo $(grep -m1 '|FALHA|' "$OUT/026_P_preflight_preflight_conferencias.txt" 2>/dev/null || echo '(conferencia em falha nao encontrada)')" >> "$OUT/motivo_da_falha.txt"
cat "$OUT/motivo_da_falha.txt"
echo "RESULTADO: $erros linha(s) fora do esperado" | tee -a "$RES"
[ "$erros" = 0 ]
