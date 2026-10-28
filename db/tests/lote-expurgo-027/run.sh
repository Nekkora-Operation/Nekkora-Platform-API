#!/usr/bin/env bash
# Suite de banco da 027_lote_expurgo_categorias.sql (candidata; D-RET-2). Dois clusters PostgreSQL 16 DESCARTAVEIS e isolados
# (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack principal nem os ambientes
# de homologacao.
#
# Modelo de aplicacao: db/bootstrap/aplicar_como_migrador.sh, SUPERUSER em 011-013, 016-020, 022-024, 026 e 027; BYPASSRLS so
# na 014; a 025 sem janela.
#
#   A (limpa):    001-027 pelo entrypoint; 027 aplicada com a janela; invariantes da 026 e da 027 depois da retirada.
#   B (populada): 001-012 + massa (execucoes e eventos da 006/007), 013-015 + massa MFA, 016-026; fixture: 5 eventos e 5 logs
#                 recebidos num dia passado e FECHADO, politica finita de 1 dia nas categorias 1 e 2.
#     R  - REPRODUCAO DIRIGIDA antes da 027 (cada uma em BEGIN ... ROLLBACK): com o plano de laco aninhado forcado na sessao
#          (enable_hashjoin, enable_mergejoin, enable_hashagg, enable_material e enable_sort desligados),
#          purge_expired_records(..., lote 2) expurga MAIS de 2 logs (categoria 2: EXIGIDO). A categoria 1 e o plano natural
#          sao so registrados: nos eventos o planejador prefere executar a subconsulta uma vez (ver abaixo).
#     B0 - 027 SEM a janela: recusada por privilegio, nada muda.
#     B1 - 027: nenhum dado muda; diff de catalogo = so o corpo de purge_expired_records; invariantes.
#     C  - DEPOIS da 027, com o mesmo plano forcado: lote 2 respeitado em cada chamada (2, 2, 1, 0) nas categorias 1 e 2; o
#          total expurgado e exatamente o elegivel; teto (1000), lote zero e nulo recusados; dia aberto e prazo nao vencido
#          nao sao expurgados; auditoria em organization_purge_runs com o numero real de cada chamada.
#     B3 - reaplicar a 027 e recusado pela pre-condicao; nada muda.
#
# Uso: db/tests/lote-expurgo-027/run.sh   (Windows/Git Bash: ver README da suite)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
INSTANTANEO="$REPO/db/tests/relogio-018/sql/c_instantaneo_gera.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
ELEVAR="${TESTE_ELEVAR:-011 012 013 016 017 018 019 020 022 023 024 026 027}"
SEM_027="011 012 013 016 017 018 019 020 022 023 024 026"
PROJETO="${TESTE_PROJETO:-nekkora-c027}"
LIMPA="${PROJETO}-limpa"
POP="${PROJETO}-populada"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
IMAGEM=postgres:16-alpine

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
for n in $(seq -w 1 27); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
M026="$SAIDA/mig/026_retencao_eventos_agent.sql"
M027="$SAIDA/mig/027_lote_expurgo_categorias.sql"
cp "$MODELO" "$SAIDA/modelo.sh"; chmod 644 "$SAIDA/modelo.sh"
sha256sum "$SAIDA"/mig/0*.sql | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt"

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
}
modelo() {   # $1 container, $2 desde, $3 ate, $4 arquivo de saida, $5 lista de elevacao (padrao $ELEVAR)
    docker exec -e NEKKORA_DB=nekkora -e NEKKORA_DESDE="$2" -e NEKKORA_ATE="$3" -e NEKKORA_ELEVAR="${5-$ELEVAR}" \
        -e NEKKORA_MIGRACOES=/nekkora-migracoes "$1" sh /tmp/modelo.sh > "$4" 2>&1
}
estado_migrador() {
    consulta "$1" nekkora "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')"
}
marcador() { consulta "$1" nekkora "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()"; }
invariantes() {   # $1 container, $2 nome: invariantes da 026 e da 027 (sempre DEPOIS da retirada; em BEGIN ... ROLLBACK)
    { awk '/^-- === 8\. Invariantes /{f=1} f && /^COMMIT;/{exit} f' "$M026"
      awk '/^-- === 3\. Invariantes /{f=1} f && /^COMMIT;/{exit} f' "$M027"
    } > "$SAIDA/${2}_invariantes.sql"
    [ "$(grep -c '^DO \$\$' "$SAIDA/${2}_invariantes.sql")" = 2 ] || falha "extracao dos invariantes ($2) falhou"
    [ "$(estado_migrador "$1")" = "0|0|0" ] || falha "invariantes ($2) fora do estado pos-retirada"
    { echo "BEGIN;"; cat "$SAIDA/${2}_invariantes.sql"; echo "ROLLBACK;"; } | psqlc "$1" nekkora > "$SAIDA/${2}_invariantes.txt" 2>&1 \
        || { cat "$SAIDA/${2}_invariantes.txt"; falha "invariantes ($2) falharam"; }
}
FORCA="SET LOCAL enable_hashjoin = off; SET LOCAL enable_mergejoin = off; SET LOCAL enable_hashagg = off; SET LOCAL enable_material = off; SET LOCAL enable_sort = off;"
expurga() {   # $1 container, $2 categoria, $3 lote, $4 "forcado"|"natural", $5 "rollback"|"commit": devolve "afetados|redigidos_no_comando"
    local f=""; [ "$4" = forcado ] && f="$FORCA"
    local tabela=execution_events; [ "$2" = 2 ] && tabela=logs
    # a contagem dos redigidos e uma instrucao SEPARADA (na mesma instrucao ela enxergaria o retrato de antes da funcao)
    printf "BEGIN; %s\nSELECT 'afetados=' || public.purge_expired_records('%s', %s, '%s', %s);\nSELECT 'redigidos=' || count(*) FROM public.%s WHERE organization_id = '%s' AND purge_run_id = (SELECT purge_run_id FROM public.organization_purge_runs WHERE organization_id = '%s' AND category = %s ORDER BY occurred_at DESC LIMIT 1);\n%s;\n" \
        "$f" "$O" "$2" "$USR" "$3" "$tabela" "$O" "$O" "$2" "$([ "$5" = rollback ] && echo ROLLBACK || echo COMMIT)" \
        | psqlc "$1" nekkora | sed -nE 's/^(afetados|redigidos)=([0-9]+)$/\2/p' | paste -sd'|'
}

# ================================================================== A. instalacao limpa 001-027
echo ">> A: instalacao limpa 001-027 ($LIMPA)"
sobe "$LIMPA" 27 027
grep -q "aplicada versao=027 elevada=SUPERUSER" "$SAIDA/entrypoint_$LIMPA.txt" || falha "027 deveria ser aplicada COM a janela"
grep -q "invariantes_016_pos_retirada=OK" "$SAIDA/entrypoint_$LIMPA.txt" || falha "016 §19 pos-retirada ausente (depois da 027)"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=027" ] || falha "marcador da instalacao limpa"
invariantes "$LIMPA" A01
lista "$LIMPA" > "$SAIDA/A02_catalogo_027.txt"

# ================================================================== B. base populada 026 -> 027
echo ">> B: base populada 001-026 ($POP)"
sobe "$POP" 12 012
HASH_PRE="$(head -c 32 /dev/urandom | sha256sum | cut -c1-64)"
psqlc "$POP" nekkora -v n_exec=60 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/B01_massa_001_012.txt"
modelo "$POP" 013 015 "$SAIDA/B02_013_015.txt" || { cat "$SAIDA/B02_013_015.txt"; falha "013-015 na populada"; }
psqlc "$POP" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/p02_popula_mfa.sql" | tail -1 > "$SAIDA/B03_massa_mfa.txt"
modelo "$POP" 016 026 "$SAIDA/B04_016_026.txt" || { cat "$SAIDA/B04_016_026.txt"; falha "016-026 na populada"; }
grep -q "aplicada versao=026 elevada=SUPERUSER" "$SAIDA/B04_016_026.txt" || falha "B: 026 deveria ser aplicada com a janela"

# fixture: organizacao com eventos reais da massa; 5 eventos e 5 logs (canal AGENT, pelas regras da ingestao) recebidos ha 5
# dias; esse dia FECHADO pelo fechamento real; politica finita de 1 dia vigente desde antes, nas categorias 1 e 2. O
# superusuario usa session_replication_role=replica SO para escrever o passado (received_at e vigencia da politica).
O="$(consulta "$POP" nekkora "SELECT organization_id FROM execution_events GROUP BY 1 HAVING count(*) >= 6 ORDER BY count(*) DESC, 1 LIMIT 1")"
[ -n "$O" ] || falha "massa sem organizacao com eventos suficientes"
USR="$(consulta "$POP" nekkora "SELECT user_id FROM users WHERE organization_id = '$O' ORDER BY user_id LIMIT 1")"
AGENTE="$(consulta "$POP" nekkora "SELECT agent_id FROM agents WHERE organization_id = '$O' ORDER BY agent_id LIMIT 1")"
DIA="$(consulta "$POP" nekkora "SELECT (clock_timestamp() AT TIME ZONE 'UTC')::date - 5")"
psqlc "$POP" nekkora > "$SAIDA/B05_fixture.txt" 2>&1 <<SQL || { cat "$SAIDA/B05_fixture.txt"; falha "fixture"; }
BEGIN;
SELECT set_config('nekkora.organization_id', '$O', true);
INSERT INTO logs (organization_id, agent_id, channel, occurred_at, payload, fingerprint)
  SELECT '$O', '$AGENTE', 1, now() - interval '5 days', jsonb_build_object('schema_version', 1, 'message', 'fixture ' || g, 'level', 2), 'fx-027-' || g
  FROM generate_series(1, 5) g;
COMMIT;
BEGIN;
SET LOCAL session_replication_role = replica;
CREATE TEMP TABLE alvo_ev ON COMMIT DROP AS
  SELECT event_id FROM execution_events WHERE organization_id = '$O' ORDER BY received_at, event_id LIMIT 5;
UPDATE execution_events SET received_at = ('$DIA'::date + time '10:00') AT TIME ZONE 'UTC' + (sequence * interval '1 second')
  WHERE event_id IN (SELECT event_id FROM alvo_ev);
UPDATE logs SET received_at = ('$DIA'::date + time '11:00') AT TIME ZONE 'UTC' + (substr(fingerprint, 8)::int * interval '1 second')
  WHERE organization_id = '$O' AND fingerprint LIKE 'fx-027-%';
INSERT INTO organization_retention_policy_revisions (organization_id, category, revision_number, effective_from, retention_mode, retention_days, provisioned_by_user_id)
  SELECT '$O', c, coalesce((SELECT max(revision_number) FROM organization_retention_policy_revisions WHERE organization_id = '$O' AND category = c), 0) + 1,
         now() - interval '30 days', 1, 1, '$USR' FROM generate_series(1, 2) c;
COMMIT;
SQL
for r in 5 4; do
    consulta "$POP" nekkora "BEGIN; SET LOCAL ROLE nekkora_retention_executor; SELECT public.close_usage_day('$O', $r, '$DIA'); COMMIT;" > /dev/null \
        || falha "fechamento do dia (recurso $r)"
done
ELEG_EV="$(consulta "$POP" nekkora "SELECT count(*) FROM execution_events WHERE organization_id = '$O' AND (received_at AT TIME ZONE 'UTC')::date = '$DIA' AND purged_at IS NULL")"
ELEG_LG="$(consulta "$POP" nekkora "SELECT count(*) FROM logs WHERE organization_id = '$O' AND (received_at AT TIME ZONE 'UTC')::date = '$DIA' AND purged_at IS NULL")"
echo "elegiveis eventos=$ELEG_EV logs=$ELEG_LG dia=$DIA" > "$SAIDA/B06_elegiveis.txt"
[ "$ELEG_EV" = 5 ] && [ "$ELEG_LG" = 5 ] || falha "fixture: elegiveis inesperados (eventos=$ELEG_EV logs=$ELEG_LG)"

echo "   R: reproducao dirigida antes da 027 (BEGIN ... ROLLBACK)"
# a reproducao depende do plano: o defeito so aparece quando o planejador escolhe o semi-join com a subconsulta REEXECUTADA
# (sem Materialize nem Unique). Nos logs o plano forcado chega la; nos eventos (chave com indice, estimativa maior) o
# planejador ainda prefere executar a subconsulta uma vez. Nenhuma estatistica nem dado e manipulado para forcar o plano.
for c in 1 2; do
    nat="$(expurga "$POP" $c 2 natural rollback)"; forc="$(expurga "$POP" $c 2 forcado rollback)"
    echo "categoria=$c lote=2 natural=$nat forcado=$forc" >> "$SAIDA/R_reproducao.txt"
done
forc2="$(grep '^categoria=2 ' "$SAIDA/R_reproducao.txt" | sed -E 's/.*forcado=//')"
[ "${forc2%%|*}" -gt 2 ] && [ "${forc2##*|}" -gt 2 ] || falha "R: defeito nao reproduzido na categoria 2 com o plano forcado ($forc2)"
consulta "$POP" nekkora "SELECT count(*) FROM organization_purge_runs WHERE organization_id = '$O'" | grep -qx 0 || falha "R: reproducao deixou auditoria (deveria ter voltado)"
psqlc "$POP" nekkora < "$INSTANTANEO" > "$SAIDA/B07_instantaneo.sql"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B07_dados_026.txt"
lista "$POP" > "$SAIDA/B07_catalogo_026.txt"

echo "   B0: 027 sem a janela"
if modelo "$POP" 027 027 "$SAIDA/B00_027_sem_janela.txt" "$SEM_027"; then falha "B0: 027 aplicada sem a janela"; fi
grep -qF "must be owner of function purge_expired_records" "$SAIDA/B00_027_sem_janela.txt" || { cat "$SAIDA/B00_027_sem_janela.txt"; falha "B0: recusa diferente da esperada"; }
[ "$(estado_migrador "$POP")" = "0|0|0" ] || falha "B0: migrador fora de 0|0|0"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" | diff -q "$SAIDA/B07_dados_026.txt" - > /dev/null || falha "B0: dados alterados"
lista "$POP" | diff -q "$SAIDA/B07_catalogo_026.txt" - > /dev/null || falha "B0: catalogo alterado"

echo "   B1: 027"
modelo "$POP" 027 027 "$SAIDA/B10_027.txt" || { cat "$SAIDA/B10_027.txt"; falha "027 na populada"; }
grep -q "aplicada versao=027 elevada=SUPERUSER" "$SAIDA/B10_027.txt" || falha "B1: 027 deveria ser aplicada com a janela"
[ "$(estado_migrador "$POP")" = "0|0|0" ] || falha "B1: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=027" ] || falha "B1: marcador"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B11_dados_027.txt"
diff "$SAIDA/B07_dados_026.txt" "$SAIDA/B11_dados_027.txt" > /dev/null || falha "B1: a 027 alterou dados"
lista "$POP" > "$SAIDA/B12_catalogo_027.txt"
diff "$SAIDA/B07_catalogo_026.txt" "$SAIDA/B12_catalogo_027.txt" | grep -E '^[<>]' | sed -E 's/\|md5=[0-9a-f]+/|md5=.../' | cut -d'|' -f1-2 | sort -u > "$SAIDA/B12_diff_resumo.txt" || true
[ "$(cat "$SAIDA/B12_diff_resumo.txt")" = "$(printf '< fn|purge_expired_records(uuid,integer,uuid,integer)\n> fn|purge_expired_records(uuid,integer,uuid,integer)')" ] \
    || { cat "$SAIDA/B12_diff_resumo.txt"; falha "B1: catalogo mudou alem do corpo de purge_expired_records"; }
diff "$SAIDA/A02_catalogo_027.txt" "$SAIDA/B12_catalogo_027.txt" > "$SAIDA/B13_diff_limpa.txt" || falha "B1: catalogo da atualizada difere da instalacao limpa (ver B13)"
invariantes "$POP" B14

echo "   C: lote respeitado depois da 027 (plano forcado)"
for c in 1 2; do
    seq_res=""
    for _ in 1 2 3 4; do seq_res="$seq_res $(expurga "$POP" $c 2 forcado commit)"; done
    echo "categoria=$c lote=2 chamadas=$seq_res" >> "$SAIDA/C_lotes.txt"
    [ "$seq_res" = " 2|2 2|2 1|1 0|0" ] || falha "C: lote nao respeitado na categoria $c (obtido:$seq_res; esperado 2|2 2|2 1|1 0|0)"
done
[ "$(consulta "$POP" nekkora "SELECT string_agg(category || ':' || records_purged, ',' ORDER BY occurred_at) FROM organization_purge_runs WHERE organization_id = '$O'")" \
    = "1:2,1:2,1:1,1:0,2:2,2:2,2:1,2:0" ] || falha "C: auditoria nao corresponde ao numero real de cada chamada"
# so o elegivel: os demais eventos da organizacao (dias abertos) e os logs de hoje continuam
[ "$(consulta "$POP" nekkora "SELECT count(*) FILTER (WHERE purged_at IS NOT NULL) || '|' || count(*) FILTER (WHERE purged_at IS NULL AND (received_at AT TIME ZONE 'UTC')::date <> '$DIA') FROM execution_events WHERE organization_id = '$O'")" \
    = "5|$(consulta "$POP" nekkora "SELECT count(*) FROM execution_events WHERE organization_id = '$O' AND (received_at AT TIME ZONE 'UTC')::date <> '$DIA'")" ] \
    || falha "C: expurgo alcancou evento fora do dia fechado"
[ "$(consulta "$POP" nekkora "SELECT count(*) FROM execution_events WHERE organization_id = '$O' AND purged_at IS NOT NULL AND payload IS NULL AND fingerprint IS NOT NULL")" = 5 ] \
    || falha "C: redacao dos eventos fora da forma (payload nulo, fingerprint mantido)"
echo "   C: controles do lote"
for l in 0 1001 NULL; do
    o="$(printf "BEGIN; SELECT public.purge_expired_records('%s', 1, '%s', %s); ROLLBACK;" "$O" "$USR" "$l" | psqlc "$POP" nekkora 2>&1 || true)"
    echo "lote=$l|$(echo "$o" | grep -m1 -oE 'ERROR: .*' | cut -c1-120)" >> "$SAIDA/C_controles.txt"
    echo "$o" | grep -qE "lote de expurgo (deve ser positivo|excede o teto)" || falha "C: lote $l nao recusado"
done
# dia aberto (hoje) com a mesma politica: nada; prazo nao vencido: politica nova de 30 dias numa organizacao sem expurgo previo
[ "$(expurga "$POP" 1 1000 natural rollback)" = "0|0" ] || falha "C: dia aberto expurgado"

echo "   B3: reaplicacao da 027"
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" > "$SAIDA/B30_dados_antes.txt"
if psqlc "$POP" nekkora < "$M027" > "$SAIDA/B30_reaplica.txt" 2>&1; then falha "B3: 027 reaplicada"; fi
grep -qF "027: lote do expurgo das categorias 1 e 2 ja corrigido" "$SAIDA/B30_reaplica.txt" || { cat "$SAIDA/B30_reaplica.txt"; falha "B3: recusa diferente da pre-condicao"; }
psqlc "$POP" nekkora < "$SAIDA/B07_instantaneo.sql" | diff -q "$SAIDA/B30_dados_antes.txt" - > /dev/null || falha "B3: dados alterados"

docker rm -f -v "$LIMPA" "$POP" > /dev/null
echo "OK: suite da 027 completa (saidas em $SAIDA)"
