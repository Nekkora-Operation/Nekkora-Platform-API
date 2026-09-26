#!/usr/bin/env bash
# Validacao da 014_mfa_sessoes.sql com os papeis reais (contrato 8.6/8.7/8.10, revisao 4).
#
# Stack Compose NOVA e ISOLADA (projeto, container, volume, rede e porta proprios); 001-014 montadas
# EXPLICITAMENTE e aplicadas pelo entrypoint (instalacao limpa). Nunca toca a stack principal nem executa
# limpeza global; a stack fica no ar e o comando de remocao e impresso.
#
# Cobertura: catalogo exato da 014; diff de catalogo 001-013 x 001-014 (so acrescimos esperados);
# invariantes; triggers e RLS com nekkora_platform; 013 intacta com sessoes MFA (nekkora_portal);
# consumo concorrente de desafio / passo TOTP / codigo (duas sessoes, verificadas separadamente);
# mutacoes (triggers removidas) que a suite PRECISA detectar. A ATIVACAO sobre base populada (revogacao das
# sessoes pre-014) e testada na suite de atualizacao (db/tests/atualizacao-populada), nao aqui.
#
# Uso: db/tests/mfa-014/run.sh   |   TESTE_PROJETO=nekkora-mfa-014b TESTE_PORTA=55522 db/tests/mfa-014/run.sh
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
ASSERTS="$REPO/db/tests/seguranca-012/sql/_asserts.inc.sql"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
PROJETO="${TESTE_PROJETO:-nekkora-mfa-014}"
PORTA="${TESTE_PORTA:-55521}"
export TESTE_CONTAINER="${PROJETO}-postgres"
VOLUME="${PROJETO}_nekkora_postgres_data"
REDE="${PROJETO}_default"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
MUT="$SAIDA/mutacoes"

falha() { echo "ERRO: $*" >&2; exit 1; }
case "$PROJETO" in nekkora-platform) falha "projeto '$PROJETO' e a stack principal";; esac
[ "$TESTE_CONTAINER" != "nekkora-postgres" ] || falha "container da stack principal"
[ "$PORTA" != "5432" ] || falha "porta 5432 e da stack principal"
docker container inspect "$TESTE_CONTAINER" >/dev/null 2>&1 && falha "container $TESTE_CONTAINER ja existe"
docker volume inspect "$VOLUME" >/dev/null 2>&1 && falha "volume $VOLUME ja existe"
docker network inspect "$REDE" >/dev/null 2>&1 && falha "rede $REDE ja existe"
if docker ps --format '{{.Ports}}' | grep -q ":$PORTA->"; then falha "porta $PORTA publicada"; fi
if (exec 3<>"/dev/tcp/127.0.0.1/$PORTA") 2>/dev/null; then falha "porta $PORTA em uso"; fi

mkdir -p "$SAIDA" "$MUT"
MIGRACOES=()
for n in $(seq -w 1 14); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    MIGRACOES+=("${f[0]}")
done
MONT_REL="db/tests/mfa-014/saidas/$(basename "$SAIDA")/migracoes_montadas"
mkdir -p "$REPO/$MONT_REL"; cp "${MIGRACOES[@]}" "$REPO/$MONT_REL/"
export TESTE_MIGRACOES="./$MONT_REL"

compose() { POSTGRES_PORT="$PORTA" docker compose -p "$PROJETO" -f "$REPO/docker-compose.yml" -f "$AQUI/compose.teste.yml" "$@"; }
psql_papel() { docker exec -i "$TESTE_CONTAINER" psql -X -d nekkora -U "$@"; }
consulta() { docker exec "$TESTE_CONTAINER" psql -X -At -d nekkora -U "$1" -c "$2"; }
com_asserts() { cat "$ASSERTS" "$AQUI/sql/_ids.inc.sql" "$AQUI/sql/$1"; }
gera_token() { head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n'; }
declare -A TOK
TOKV=()
for n in x a1 csrf_x csrf_a1; do
    TOK[$n]="$(gera_token)"
    [[ "${TOK[$n]}" =~ ^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$ ]] || falha "token fora do contrato D2"
    if [[ $n == csrf_* ]]; then TOKV+=(-v "$n=${TOK[$n]}"); else TOKV+=(-v "tok_$n=${TOK[$n]}"); fi
done
roda() {
    local papel="$1" arq="$2" nome="${3:-}"; shift 2; [ $# -gt 0 ] && shift
    [ -n "$nome" ] || nome="$(basename "$arq" .sql)"
    echo ">> $nome (papel: $papel)"
    com_asserts "$arq" | psql_papel "$papel" "${TOKV[@]}" "$@" > "$SAIDA/$nome.txt" 2>&1 || falha "$nome falhou (ver $SAIDA/$nome.txt)"
}
roda_st() { local dir="$1" papel="$2" arq="$3" nome="$4"; shift 4; local rc=0
    com_asserts "$arq" | psql_papel "$papel" "${TOKV[@]}" "$@" > "$dir/$nome.txt" 2>&1 || rc=$?; return $rc; }

{
    echo "data_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "commit=$(cd "$REPO" && git rev-parse HEAD 2>/dev/null || echo desconhecido)"
    echo "branch=$(cd "$REPO" && git branch --show-current 2>/dev/null || echo desconhecido)"
    echo "git_status_porcelain:"; (cd "$REPO" && git status --porcelain) | sed 's/^/  /'
    echo "projeto=$PROJETO container=$TESTE_CONTAINER volume=$VOLUME rede=$REDE porta=$PORTA"
    docker version --format 'docker={{.Server.Version}}'
    echo "migracoes_montadas:"; (cd "$REPO/$MONT_REL" && sha256sum 0*.sql | sed 's/^/  /')
} > "$SAIDA/ambiente.txt"

echo ">> subindo stack isolada $PROJETO (porta $PORTA)"
compose up -d > "$SAIDA/aplicacao_compose_up.txt" 2>&1
for _ in $(seq 1 90); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$TESTE_CONTAINER")" = healthy ] && break; sleep 2; done
[ "$(docker inspect -f '{{.State.Health.Status}}' "$TESTE_CONTAINER")" = healthy ] || falha "stack nao ficou healthy"
docker logs "$TESTE_CONTAINER" 2>&1 | grep -E "running /docker-entrypoint-initdb.d|ERROR|FATAL|init process complete" > "$SAIDA/aplicacao_entrypoint.txt" || true
grep -qE "ERROR|FATAL" "$SAIDA/aplicacao_entrypoint.txt" && falha "erro na aplicacao das migrations"
[ "$(grep -c 'running /docker-entrypoint-initdb.d' "$SAIDA/aplicacao_entrypoint.txt")" = 14 ] || falha "esperadas 14 migrations aplicadas"
[ "$(consulta nekkora 'SHOW cluster_name')" = nekkora-mfa-014-isolada ] || falha "cluster_name inesperado"
echo "server_version=$(consulta nekkora 'SHOW server_version')" >> "$SAIDA/ambiente.txt"

# --- catalogo ---
roda nekkora t01_catalogo.sql
echo ">> base de referencia nekkora_ref com 001-013 e diff de catalogo"
{ consulta nekkora "CREATE DATABASE nekkora_ref"
  for f in $(cd "$REPO/$MONT_REL" && ls -1 0*.sql | grep -v '^014_'); do
      echo "== $f"; docker exec -i "$TESTE_CONTAINER" psql -X -q -v ON_ERROR_STOP=1 -U nekkora -d nekkora_ref < "$REPO/$MONT_REL/$f" 2>&1
  done; } > "$SAIDA/01_ref_001_013.txt" 2>&1 || falha "001-013 na base de referencia falhou"
grep -qE "ERROR|FATAL" "$SAIDA/01_ref_001_013.txt" && falha "erro ao aplicar 001-013 em nekkora_ref"
lista() { docker exec -i "$TESTE_CONTAINER" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d "$1" < "$LISTAGEM"; }
lista nekkora_ref > "$SAIDA/01_catalogo_001_013.txt"; lista nekkora > "$SAIDA/01_catalogo_001_014.txt"
diff "$SAIDA/01_catalogo_001_013.txt" "$SAIDA/01_catalogo_001_014.txt" > "$SAIDA/01_diff_catalogo.txt" || true
ESPERADO='user_mfa_factors|user_mfa_recovery_codes|auth_challenges|mfa_events|prevent_mfa_|prevent_auth_challenge_change|enforce_user_session_mfa_on_insert|prevent_user_session_reactivation|user_sessions\|(mfa_verified_at|mfa_step_up_at|csrf_token)|user_sessions\|user_sessions_(step_up_requires_mfa|csrf_token_check)|trg_user_sessions_(enforce_mfa|prevent_reactivation)'
{ echo "removidas/alteradas (<): $(grep -c '^<' "$SAIDA/01_diff_catalogo.txt" || true)"
  echo "acrescentadas (>): $(grep -c '^>' "$SAIDA/01_diff_catalogo.txt" || true)"
  echo "acrescentadas fora do esperado: $(grep '^>' "$SAIDA/01_diff_catalogo.txt" | grep -vcE "$ESPERADO" || true)"
  echo "por tipo:"; grep '^>' "$SAIDA/01_diff_catalogo.txt" | cut -c3- | cut -d'|' -f1 | sort | uniq -c; } > "$SAIDA/01_diff_resumo.txt"
[ "$(grep -c '^<' "$SAIDA/01_diff_catalogo.txt" || true)" = 0 ] || falha "a 014 alterou/removeu objeto de 001-013 (ver 01_diff_catalogo.txt)"
[ "$(grep '^>' "$SAIDA/01_diff_catalogo.txt" | grep -vcE "$ESPERADO" || true)" = 0 ] || falha "a 014 acrescentou objeto fora do esperado"
{ awk '/^-- === 15\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/012_security_rls.sql"
  awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/013_portal_externo.sql"
  awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/014_mfa_sessoes.sql"; } > "$SAIDA/01_invariantes.sql"
[ "$(grep -c '^DO \$\$' "$SAIDA/01_invariantes.sql")" = 3 ] || falha "extracao dos invariantes falhou"
psql_papel nekkora -v ON_ERROR_STOP=1 < "$SAIDA/01_invariantes.sql" > "$SAIDA/01_invariantes.txt" 2>&1 || falha "invariantes falharam"

# --- fixture ---
psql_papel nekkora -v org_id=99999999-0000-0000-0000-000000000099 -v org_name='Nekkora Internal' \
    -v staff_user_id=99999999-0000-0000-0000-0000000000aa -v staff_email=staff@nekkora.internal \
    -v staff_name='Nekkora Staff' -v limites='{"(1,1,0)","(2,1,0)","(3,1,0)","(4,2,)","(5,2,)"}' \
    < "$REPO/db/bootstrap/001_organizacao_interna_nekkora.sql" > "$SAIDA/f00_bootstrap.txt" 2>&1 || falha "bootstrap falhou"
roda nekkora_provisioning f01_provisiona.sql
roda nekkora_platform     f02_fixture_platform.sql
roda nekkora_provisioning f03_bootstrap_admin.sql

# --- comportamento ---
roda nekkora_platform t02_platform_invariantes.sql
roda nekkora_portal   t03_portal_regressao.sql
roda nekkora          t04_verifica_toque.sql

# --- concorrencia: consumo do mesmo desafio / passo / codigo ---
roda nekkora_platform conc_prep.sql
echo ">> concorrencia (duas sessoes nekkora_platform consumindo os mesmos recursos)"
com_asserts conc_consome.sql | psql_papel nekkora_platform "${TOKV[@]}" -v sessao=A -v esperado=1 -v espera=6 > "$SAIDA/conc_a.txt" 2>&1 & PA=$!
sleep 2
com_asserts conc_consome.sql | psql_papel nekkora_platform "${TOKV[@]}" -v sessao=B -v esperado=0 -v espera=0 > "$SAIDA/conc_b.txt" 2>&1 & PB=$!
sleep 2
RC=0; com_asserts conc_observador.sql | psql_papel nekkora -v bloqueado=nekkora_platform -v bloqueador=nekkora_platform > "$SAIDA/conc_observador.txt" 2>&1 || RC=$?
RA=0; wait "$PA" || RA=$?; RB=0; wait "$PB" || RB=$?
echo "sessao A exit=$RA; sessao B exit=$RB; observador exit=$RC" > "$SAIDA/sessoes_concorrentes.txt"
[ "$RA" -eq 0 ] || falha "concorrencia: sessao A falhou (ver conc_a.txt)"
[ "$RB" -eq 0 ] || falha "concorrencia: sessao B falhou (ver conc_b.txt)"
[ "$RC" -eq 0 ] || falha "concorrencia: observador nao viu B bloqueada por A"
roda nekkora conc_verifica.sql

# --- mutacoes ---
for m in m1_sem_trigger_update m2_sem_trigger_insert; do
    echo ">> mutacao $m -> t02 precisa falhar"
    psql_papel nekkora -v ON_ERROR_STOP=1 < "$AQUI/mutacoes/$m.sql" > "$MUT/aplica_$m.txt" 2>&1 || falha "mutacao $m nao aplicou"
    RC=0; roda_st "$MUT" nekkora_platform t02_platform_invariantes.sql "${m}_t02" || RC=$?
    [ "$RC" -ne 0 ] || falha "MUTACAO $m NAO FOI DETECTADA"
    grep -q "FALHA DE ASSERCAO: operacao deveria ter falhado" "$MUT/${m}_t02.txt" || falha "mutacao $m: falha sem a mensagem esperada"
    echo "$m: DETECTADA ($(grep -m1 'FALHA DE ASSERCAO' "$MUT/${m}_t02.txt" | cut -c1-160))" >> "$MUT/resumo.txt"
    # restaura so a trigger removida
    case $m in m1*) sed -n '1p;3p' "$AQUI/mutacoes/restaura.sql";; m2*) sed -n '1p;2p' "$AQUI/mutacoes/restaura.sql";; esac \
        | psql_papel nekkora -v ON_ERROR_STOP=1 > "$MUT/restaura_$m.txt" 2>&1 || falha "restauracao de $m falhou"
    roda nekkora_platform t02_platform_invariantes.sql "${m}_t02_restaurado"
    echo "$m: restaurada; t02 passou de novo" >> "$MUT/resumo.txt"
done

# --- final ---
roda nekkora t01_catalogo.sql 99_t01_catalogo_final
lista nekkora > "$SAIDA/99_catalogo_001_014_final.txt"
diff <(grep -v '^trg|user_sessions|' "$SAIDA/01_catalogo_001_014.txt") <(grep -v '^trg|user_sessions|' "$SAIDA/99_catalogo_001_014_final.txt") > /dev/null \
    || falha "catalogo final difere do da instalacao"
diff <(grep '^trg|user_sessions|' "$SAIDA/01_catalogo_001_014.txt") <(grep '^trg|user_sessions|' "$SAIDA/99_catalogo_001_014_final.txt") > /dev/null \
    || falha "triggers de user_sessions restauradas diferem das instaladas"
if grep -l -E "syntax error at or near|unterminated quoted|invalid command \\\\|FALHA DE ASSERCAO" "$SAIDA"/*.txt; then
    falha "saida com erro de sintaxe ou falha de assercao"
fi
docker logs "$TESTE_CONTAINER" > "$SAIDA/99_log_servidor.log" 2>&1
for n in "${!TOK[@]}"; do grep -rlF -- "${TOK[$n]}" "$SAIDA" >/dev/null && falha "token ($n) apareceu numa saida ou no log"; done
echo "nenhum dos ${#TOK[@]} tokens aparece nas saidas nem no log do servidor" > "$SAIDA/zz_tokens_fora_das_saidas.txt"
echo "OK - saidas em $SAIDA"
echo "Remocao: docker rm -f $TESTE_CONTAINER && docker volume rm $VOLUME && docker network rm $REDE"
