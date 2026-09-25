#!/usr/bin/env bash
# Verificacao curta de uma instalacao limpa (001-012 + procedimento inicial ja aplicados), com os
# papeis reais: catalogo x matriz, provisionamento, fluxo permitido do platform, isolamento e
# bootstrap de ORG_ADMIN. Cria dados de verificacao (uma organizacao "Organizacao de Verificacao")
# na base alvo - use numa base recem-instalada, nunca numa base com dados reais.
#
# Uso: db/tests/seguranca-012/instalacao-limpa/verifica.sh <container> <org_interna_uuid> <staff_uuid>
set -euo pipefail
AQUI="$(cd "$(dirname "$0")" && pwd)"
SQL="$AQUI/sql"
C="${1:?container}"; ORG_INT="${2:?org_interna}"; STAFF="${3:?staff}"
SAIDA="$AQUI/saidas"; mkdir -p "$SAIDA"
p() { docker exec -i "$C" psql -X -d nekkora -U "$@"; }

read -r ORG_V USER_V MEM_V WS_V <<< "$(docker exec "$C" psql -X -At -F ' ' -U nekkora -d nekkora -c 'SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid()')"
echo "org_v=$ORG_V user_v=$USER_V mem_v=$MEM_V ws_v=$WS_V" > "$SAIDA/04_ids_verificacao.txt"
V=(-v org_int="$ORG_INT" -v staff="$STAFF" -v org_v="$ORG_V" -v user_v="$USER_V" -v mem_v="$MEM_V" -v ws_v="$WS_V")

p nekkora < "$AQUI/../sql/00_catalogo_vs_matriz.sql" > "$SAIDA/05_catalogo_vs_matriz.txt" 2>&1
p nekkora_provisioning "${V[@]}" < "$SQL/v01_provisionamento.sql" > "$SAIDA/06_v01_provisionamento.txt" 2>&1 || { echo "falha em v01"; exit 1; }
p nekkora_platform     "${V[@]}" < "$SQL/v02_platform.sql"        > "$SAIDA/07_v02_platform.txt" 2>&1        || { echo "falha em v02"; exit 1; }
p nekkora_provisioning "${V[@]}" < "$SQL/v03_bootstrap_admin.sql" > "$SAIDA/08_v03_bootstrap_admin.txt" 2>&1 || { echo "falha em v03"; exit 1; }
p nekkora < "$SQL/v04_resumo.sql" > "$SAIDA/09_v04_resumo.txt" 2>&1
if grep -l -E "syntax error at or near|unterminated quoted" "$SAIDA"/0[5-9]_*.txt; then echo "erro de sintaxe nas saidas"; exit 1; fi
echo "OK - saidas em $SAIDA"
