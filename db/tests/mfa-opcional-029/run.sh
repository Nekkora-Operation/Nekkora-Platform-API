#!/usr/bin/env bash
# Suite de banco da 029_mfa_opcional.sql (candidata; missao PLATFORM-LOCAL-002). Dois clusters PostgreSQL 16 DESCARTAVEIS e
# isolados (containers proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack principal, os
# laboratorios do frontend nem a homologacao.
#
# Modelo de aplicacao: db/bootstrap/aplicar_como_migrador.sh, SUPERUSER nas versoes do padrao (com a 029), BYPASSRLS so na
# 014, retirada conferida em 0|0|0.
#
#   A (limpa):    001-029 pelo entrypoint; janela da 029 com retirada; 016 §19 e 029 §9 DEPOIS da retirada; marcador 029.
#   B (populada): 001-012 + massa (p01), 013-015 + massa MFA (p02), 016-028, massa de identidades (p029: com fator ativo,
#                 codigos e sessao; com fator desabilitado; sem fator); instantaneo de dados com as colunas de ANTES.
#     B0 - 029 SEM a janela de SUPERUSER: recusada por privilegio, nada muda (dados, catalogo, migrador 0|0|0).
#     B1 - 029 com a janela: as colunas anteriores ficam identicas (nenhum fator, codigo ou sessao tocado); catalogo igual
#          ao da instalacao limpa A; invariantes depois da retirada; a sessao existente continua resolvendo; a auxiliar
#          classifica as tres identidades (so a sem fator "nunca teve fator"); a sessao sem MFA da identidade com fator e
#          recusada pela trigger.
#     B3 - reaplicar a 029 e recusado pela pre-condicao; nada muda.
# O comportamento (login sem segundo fator, reconfirmacao da senha, ativacao, step-up na organizacao) e coberto pelos testes
# Java com o papel real nekkora_platform: MfaOpcionalIT (nekkora-platform).
#
# Uso: db/tests/mfa-opcional-029/run.sh   (Git Bash no Windows: MSYS_NO_PATHCONV=1 e definido aqui)
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail
export MSYS_NO_PATHCONV=1

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
INSTANTANEO="$REPO/db/tests/relogio-018/sql/c_instantaneo_gera.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
ELEVAR="${TESTE_ELEVAR:-011 012 013 016 017 018 019 020 022 023 024 026 027 029}"
SEM_029="011 012 013 016 017 018 019 020 022 023 024 026 027"
PROJETO="${TESTE_PROJETO:-nekkora-c029}"
LIMPA="${PROJETO}-limpa"
POP="${PROJETO}-populada"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
IMAGEM=postgres:16-alpine

falha() {
    echo "ERRO: $*" >&2
    echo "containers mantidos para inspecao. Remocao: docker rm -f -v $LIMPA $POP" >&2
    exit 1
}
case "$PROJETO" in nekkora-platform|nekkora|*hml03*|*frontlab*) falha "projeto '$PROJETO' reservado";; esac
for c in "$LIMPA" "$POP"; do
    docker container inspect "$c" >/dev/null 2>&1 && falha "container $c ja existe"
done

mkdir -p "$SAIDA/mig"
for n in $(seq -w 1 29); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
M029="$SAIDA/mig/029_mfa_opcional.sql"
cp "$MODELO" "$SAIDA/modelo.sh"; chmod 644 "$SAIDA/modelo.sh"
sha256sum "$SAIDA"/mig/0*.sql | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt"
caminho() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

psqlc() { local c="$1" db="$2"; shift 2; docker exec -i "$c" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d "$db" "$@"; }
consulta() { psqlc "$1" "$2" -c "$3" < /dev/null; }
lista() { psqlc "$1" "$2" < "$LISTAGEM" | awk '/^(rel|relacl|col|colacl|con|idx|trg|pol|fn|fnacl|type|nsp|nspacl|ext|defacl|dbacl)\|/ { if (l != "") print l; l = $0; next } { l = l " " $0 } END { if (l != "") print l }'; }
sobe() {   # $1 container, $2 quantidade esperada, $3 ultima versao aplicada no entrypoint
    docker create --name "$1" --network none -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
        -e POSTGRES_PASSWORD="descartavel-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')" -e NEKKORA_ATE="$3" \
        -e NEKKORA_ELEVAR="$ELEVAR" -e NEKKORA_MIGRACOES=/nekkora-migracoes \
        "$IMAGEM" postgres -c cluster_name="$1" > /dev/null
    docker cp "$(caminho "$SAIDA/mig")" "$1":/nekkora-migracoes > /dev/null
    docker cp "$(caminho "$SAIDA/modelo.sh")" "$1":/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh > /dev/null
    docker cp "$(caminho "$SAIDA/modelo.sh")" "$1":/tmp/modelo.sh > /dev/null
    docker start "$1" > /dev/null
    for _ in $(seq 1 240); do
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
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')"
}
marcador() { consulta "$1" nekkora "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()"; }
invariantes() {   # $1 container, $2 nome; sempre DEPOIS da retirada: retrato da secao 1 + secao 9 da 029
    { awk '/^CREATE TEMPORARY TABLE n029_antes/{f=1} f{print} f && /;$/{exit}' "$M029"
      awk '/^-- === 9\. /{f=1} f && /^COMMIT;/{exit} f' "$M029"
    } > "$SAIDA/${2}_invariantes.sql"
    [ "$(grep -c '^DO \$\$' "$SAIDA/${2}_invariantes.sql")" = 1 ] || falha "extracao dos invariantes ($2) falhou"
    [ "$(estado_migrador "$1")" = "0|0|0" ] || falha "invariantes ($2) fora do estado pos-retirada"
    { echo "BEGIN;"; cat "$SAIDA/${2}_invariantes.sql"; echo "ROLLBACK;"; } | psqlc "$1" nekkora > "$SAIDA/${2}_invariantes.txt" 2>&1 \
        || { cat "$SAIDA/${2}_invariantes.txt"; falha "invariantes ($2) falharam"; }
}

# ================================================================== A. instalacao limpa 001-029
echo ">> A: instalacao limpa 001-029 ($LIMPA)"
sobe "$LIMPA" 29 029
echo "server_version=$(consulta "$LIMPA" nekkora 'SHOW server_version')" > "$SAIDA/ambiente.txt"
grep -q "fim    versao=029 SUPERUSER revogado, estado 0|0|0" "$SAIDA/entrypoint_$LIMPA.txt" || falha "janela da 029 sem retirada 0|0|0"
grep -q "invariantes_016_pos_retirada=OK" "$SAIDA/entrypoint_$LIMPA.txt" || falha "016 §19 pos-retirada ausente"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=029" ] || falha "marcador da instalacao limpa"
invariantes "$LIMPA" A01
lista "$LIMPA" nekkora > "$SAIDA/A02_catalogo_029.txt"

# ================================================================== B. base populada 028 -> 029
echo ">> B: base populada 001-028 ($POP)"
sobe "$POP" 12 012
HASH_PRE="$(head -c 32 /dev/urandom | sha256sum | cut -c1-64)"
psqlc "$POP" nekkora -v n_exec=60 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/B01_massa_001_012.txt"
modelo "$POP" 013 015 "$SAIDA/B02_013_015.txt" || { cat "$SAIDA/B02_013_015.txt"; falha "013-015 na populada"; }
psqlc "$POP" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/p02_popula_mfa.sql" | tail -1 > "$SAIDA/B03_massa_mfa.txt"
modelo "$POP" 016 028 "$SAIDA/B04_016_028.txt" || { cat "$SAIDA/B04_016_028.txt"; falha "016-028 na populada"; }
psqlc "$POP" nekkora < "$AQUI/sql/p029_popula_identidades.sql" | tail -1 > "$SAIDA/B05_massa_identidades.txt"
grep -q "identidades=3 fatores_ativos=1 codigos=10 sessoes=1" "$SAIDA/B05_massa_identidades.txt" || falha "massa de identidades incompleta"
psqlc "$POP" nekkora < "$INSTANTANEO" > "$SAIDA/B06_instantaneo.sql"
[ "$(wc -l < "$SAIDA/B06_instantaneo.sql")" -ge 60 ] || falha "gerador de instantaneo incompleto"
psqlc "$POP" nekkora < "$SAIDA/B06_instantaneo.sql" > "$SAIDA/B06_dados_028.txt"
lista "$POP" nekkora > "$SAIDA/B06_catalogo_028.txt"
SESSAO="$(consulta "$POP" nekkora "SELECT session_token_hash FROM identity_sessions s JOIN identities i USING (identity_id) WHERE i.email = 'com-fator@p029.test'")"

echo "   B0: 029 sem a janela de SUPERUSER"
if modelo "$POP" 029 029 "$SAIDA/B00_029_sem_janela.txt" "$SEM_029"; then falha "B0: 029 aplicada sem a janela"; fi
grep -qE "ERROR:  (must be owner of|must be able to SET ROLE \"nekkora_identity_owner\"|must be member of role \"nekkora_identity_owner\"|permission denied)" \
    "$SAIDA/B00_029_sem_janela.txt" || { cat "$SAIDA/B00_029_sem_janela.txt"; falha "B0: recusa diferente da esperada (privilegio)"; }
[ "$(estado_migrador "$POP")" = "0|0|0" ] || falha "B0: migrador fora de 0|0|0"
psqlc "$POP" nekkora < "$SAIDA/B06_instantaneo.sql" | diff -q "$SAIDA/B06_dados_028.txt" - > /dev/null || falha "B0: dados alterados"
lista "$POP" nekkora | diff -q "$SAIDA/B06_catalogo_028.txt" - > /dev/null || falha "B0: catalogo alterado"

echo "   B1: 029 com a janela"
modelo "$POP" 029 029 "$SAIDA/B10_029.txt" || { cat "$SAIDA/B10_029.txt"; falha "029 na populada"; }
grep -q "fim    versao=029 SUPERUSER revogado, estado 0|0|0" "$SAIDA/B10_029.txt" || falha "B1: janela da 029 sem retirada 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=029" ] || falha "B1: marcador"
psqlc "$POP" nekkora < "$SAIDA/B06_instantaneo.sql" > "$SAIDA/B11_dados_029.txt"
diff "$SAIDA/B06_dados_028.txt" "$SAIDA/B11_dados_029.txt" > "$SAIDA/B11_diff_dados.txt" || falha "B1: a 029 alterou dados existentes (ver B11_diff_dados.txt)"
lista "$POP" nekkora > "$SAIDA/B12_catalogo_029.txt"
diff "$SAIDA/B06_catalogo_028.txt" "$SAIDA/B12_catalogo_029.txt" > "$SAIDA/B12_diff_catalogo_028_029.txt" || true
diff "$SAIDA/A02_catalogo_029.txt" "$SAIDA/B12_catalogo_029.txt" > "$SAIDA/B13_diff_limpa.txt" || falha "B1: catalogo da atualizada difere da instalacao limpa (ver B13)"
invariantes "$POP" B14
{
    echo "lookup_sessao_existente=$(consulta "$POP" nekkora "SELECT count(*) FROM identity_sessions s WHERE s.session_token_hash = '$SESSAO' AND s.revoked_at IS NULL AND s.mfa_verified_at IS NOT NULL AND s.password_step_up_at IS NULL")"
    consulta "$POP" nekkora "SELECT email || ' nunca_teve_fator=' || identity_never_had_factor(identity_id) FROM identities WHERE email LIKE '%@p029.test' ORDER BY email"
    echo "fatores_ativos=$(consulta "$POP" nekkora "SELECT count(*) FROM identity_mfa_factors WHERE key_id = 'p029' AND confirmed_at IS NOT NULL AND disabled_at IS NULL")"
} > "$SAIDA/B15_comportamento.txt"
grep -qx "lookup_sessao_existente=1" "$SAIDA/B15_comportamento.txt" || falha "B1: sessao existente deixou de valer"
grep -qx "com-fator@p029.test nunca_teve_fator=false" "$SAIDA/B15_comportamento.txt" || falha "B1: auxiliar (com fator)"
grep -qx "fator-desabilitado@p029.test nunca_teve_fator=false" "$SAIDA/B15_comportamento.txt" || falha "B1: auxiliar (fator desabilitado)"
grep -qx "sem-fator@p029.test nunca_teve_fator=true" "$SAIDA/B15_comportamento.txt" || falha "B1: auxiliar (sem fator)"
grep -qx "fatores_ativos=1" "$SAIDA/B15_comportamento.txt" || falha "B1: fator ativo alterado"
for alvo in 29000000-0000-4000-8000-000000000001 29000000-0000-4000-8000-000000000002; do
    if psqlc "$POP" nekkora -c "INSERT INTO identity_sessions (identity_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
        VALUES ('$alvo', md5('$alvo') || md5('b$alvo'), now() + interval '1 hour', NULL, rpad('b', 43, 'b'))" < /dev/null > "$SAIDA/B16_sessao_sem_mfa_$alvo.txt" 2>&1; then
        falha "B1: sessao sem MFA aceita para identidade que ja teve fator ($alvo)"
    fi
    grep -q "sessao sem segundo fator para identidade com fator confirmado" "$SAIDA/B16_sessao_sem_mfa_$alvo.txt" || falha "B1: recusa da sessao sem MFA diferente da esperada"
done

echo "   B3: reaplicacao da 029"
psqlc "$POP" nekkora < "$SAIDA/B06_instantaneo.sql" > "$SAIDA/B30_dados_antes.txt"
if psqlc "$POP" nekkora < "$M029" > "$SAIDA/B30_reaplica.txt" 2>&1; then falha "B3: 029 reaplicada"; fi
grep -qF "029: funcoes da 029 ja existem" "$SAIDA/B30_reaplica.txt" || { cat "$SAIDA/B30_reaplica.txt"; falha "B3: recusa diferente da pre-condicao"; }
psqlc "$POP" nekkora < "$SAIDA/B06_instantaneo.sql" | diff -q "$SAIDA/B30_dados_antes.txt" - > /dev/null || falha "B3: dados alterados"
lista "$POP" nekkora | diff -q "$SAIDA/B12_catalogo_029.txt" - > /dev/null || falha "B3: catalogo alterado"

{
    echo "A: 29 migrations (001-029); janelas SUPERUSER com retirada 0|0|0: $(grep -c 'SUPERUSER revogado, estado 0|0|0' "$SAIDA/entrypoint_$LIMPA.txt"); 016 §19 e 029 §9 DEPOIS da retirada: OK; marcador versao=029"
    echo "B: $(cat "$SAIDA/B01_massa_001_012.txt")"
    echo "B: $(cat "$SAIDA/B03_massa_mfa.txt")"
    echo "B: $(cat "$SAIDA/B05_massa_identidades.txt")"
    echo "B: tabelas no instantaneo (public e ops): $(wc -l < "$SAIDA/B06_dados_028.txt" | tr -d ' ')"
    echo "B0: 029 sem SUPERUSER recusada: $(grep -m1 "ERROR:" "$SAIDA/B00_029_sem_janela.txt" | sed "s/^ERROR: *//"); dados e catalogo identicos; migrador 0|0|0"
    echo "B1: 029 com a janela: colunas anteriores identicas (diff vazio); catalogo = instalacao limpa; $(grep -cE '^[<>]' "$SAIDA/B12_diff_catalogo_028_029.txt") linhas de catalogo 028->029 (B12); invariantes DEPOIS da retirada: OK"
    echo "B1: sessao existente continua; auxiliar: com fator=false, desabilitado=false, sem fator=true; sessao sem MFA recusada para as duas que ja tiveram fator"
    echo "B3: reaplicacao recusada pela pre-condicao; nada muda"
} | tee "$SAIDA/resumo.txt"
docker rm -f -v "$LIMPA" "$POP" > /dev/null
echo "saidas: $SAIDA"
