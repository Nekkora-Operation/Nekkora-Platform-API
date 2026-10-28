#!/usr/bin/env bash
# Prova dos pre-voos PROPOSTOS implantacao/preflight/023.sql e 024.sql num cluster PostgreSQL 16 DESCARTAVEL (container
# proprio, --network none, sem porta publicada, sem volume nomeado). Nunca toca ambiente de homologacao. As migrations vao
# pelo modelo do migrador (db/bootstrap/aplicar_como_migrador.sh); o historico do Flyway e SINTETICO (tabela criada so para o
# teste, com as versoes aplicadas pelo modelo), porque o modelo nao usa o Flyway.
#
# Casos (ultima linha esperada):
#   P1 023 sobre 001-022               PREFLIGHT_OK
#   P2 024 sobre 001-022               PREFLIGHT_FALHOU (historico, marca da 023, restricoes; sem erro de SQL)
#   P3 023 sobre 001-023               PREFLIGHT_FALHOU (historico, objetos, restricoes)
#   P4 024 sobre 001-023               PREFLIGHT_OK (0 convites a fechar)
#   P5 024 com um convite aberto de emissor sem autoridade   PREFLIGHT_OK (1 convite a fechar; a 024 avisa 1 na aplicacao)
#   P6 024 sobre 001-024               PREFLIGHT_FALHOU (historico, objetos, restricoes)
#
# Uso: ops/homologacao/testes/prevoo_023_024.sh [diretorio de saida]   (Git Bash: a exclusao MSYS2 e exportada aqui)
set -euo pipefail
AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
OUT="${1:-$AQUI/../saidas/prevoo-023-024-$(date -u +%Y%m%dT%H%M%SZ)}"
mkdir -p "$OUT"
C="${TESTE_CONTAINER:-npa-prevoo-024}"
export MSYS2_ARG_CONV_EXCL="NEKKORA_;/tmp/;/nekkora;/docker-entrypoint-initdb.d;/pf"
falha() { echo "ERRO: $*" >&2; echo "container mantido para inspecao. Remocao: docker rm -f -v $C" >&2; exit 1; }
docker container inspect "$C" >/dev/null 2>&1 && { echo "container $C ja existe" >&2; exit 1; }
docker create --name "$C" --network none -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
    -e POSTGRES_PASSWORD="descartavel-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')" -e NEKKORA_ATE=022 \
    -e NEKKORA_MIGRACOES=/nekkora-migracoes postgres:16-alpine > /dev/null
docker cp "$REPO/db/migrations" "$C":/nekkora-migracoes > /dev/null
docker cp "$REPO/db/bootstrap/aplicar_como_migrador.sh" "$C":/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh > /dev/null
docker cp "$REPO/db/bootstrap/aplicar_como_migrador.sh" "$C":/tmp/modelo.sh > /dev/null
docker cp "$REPO/ops/homologacao/implantacao/preflight" "$C":/pf > /dev/null
docker start "$C" > /dev/null
for _ in $(seq 1 180); do
    [ "$(docker logs "$C" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
    sleep 1
done
docker logs "$C" 2>&1 | grep -qE "fim db=nekkora aplicadas=22 estado=0\|0\|0" || falha "001-022 pelo modelo"
sha256sum "$REPO"/db/migrations/02[34]_*.sql "$REPO"/ops/homologacao/implantacao/preflight/02[34].sql | sed "s#$REPO/##" > "$OUT/00_sha256.txt"
q() { docker exec -i "$C" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora "$@"; }
hist() { q -c "INSERT INTO public.flyway_schema_history (installed_rank, version, success) SELECT n, lpad(n::text, 3, '0'), true FROM generate_series($1, $2) n" > /dev/null; }
q -c "CREATE TABLE public.flyway_schema_history (installed_rank int PRIMARY KEY, version text, success boolean NOT NULL)" > /dev/null
hist 1 22
pf() {   # $1 versao do pre-voo, $2 rotulo, $3 ultima linha esperada
    q -f "/pf/$1.sql" > "$OUT/$2.txt" 2>&1 || true
    local u; u="$(tail -1 "$OUT/$2.txt")"
    echo "$2: $u" | tee -a "$OUT/resumo.txt"
    [ "$u" = "$3" ] || falha "$2: esperado $3"
}
modelo() { docker exec -e NEKKORA_DB=nekkora -e NEKKORA_DESDE="$1" -e NEKKORA_ATE="$1" -e NEKKORA_MIGRACOES=/nekkora-migracoes "$C" sh /tmp/modelo.sh > "$OUT/aplica_$1.txt" 2>&1 \
    || falha "aplicacao da $1"; }
: > "$OUT/resumo.txt"
pf 023 P1_023_em_022 PREFLIGHT_OK
pf 024 P2_024_em_022 PREFLIGHT_FALHOU
modelo 023; hist 23 23
pf 023 P3_023_em_023 PREFLIGHT_FALHOU
pf 024 P4_024_em_023 PREFLIGHT_OK
grep -q '^convites_abertos_que_a_024_fechara|OK|0 ' "$OUT/P4_024_em_023.txt" || falha "P4: contagem"
O=a0000000-0000-4000-8000-000000000001; U=a0000000-0000-4000-8000-000000000002; M=a0000000-0000-4000-8000-000000000003
q > /dev/null <<SQL
BEGIN;
SELECT set_config('nekkora.organization_id', '$O', true);
INSERT INTO organizations (organization_id, name, status) VALUES ('$O', 'Org pre-voo', 1);
INSERT INTO users (user_id, organization_id, account_type, email, email_verified_at, status, display_name) VALUES ('$U', '$O', 1, 'emi-prevoo@o.test', now(), 2, 'Emissor');
INSERT INTO organization_memberships (membership_id, organization_id, user_id, account_type, status) VALUES ('$M', '$O', '$U', 1, 2);
INSERT INTO identity_invitations (organization_id, invited_account_type, email_normalized, issued_by, token_hash, expires_at, issued_by_membership_id)
VALUES ('$O', 1, 'x-prevoo@o.test', '$U', md5('p1') || md5('p2'), now() + interval '2 days', '$M');
COMMIT;
SQL
pf 024 P5_024_com_convite_a_fechar PREFLIGHT_OK
grep -q '^convites_abertos_que_a_024_fechara|OK|1 ' "$OUT/P5_024_com_convite_a_fechar.txt" || falha "P5: contagem"
modelo 024; hist 24 24
grep -qF "024: convites abertos de emissor sem autoridade fechados na aplicacao: 1" "$OUT/aplica_024.txt" || falha "aviso do fechamento na aplicacao"
pf 024 P6_024_em_024 PREFLIGHT_FALHOU
docker rm -f -v "$C" > /dev/null
echo "saidas: $OUT"
