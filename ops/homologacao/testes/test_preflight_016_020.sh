#!/usr/bin/env bash
# Pre-voos 016-020 (implantacao/preflight/<versao>.sql) contra uma base REAL em cada estagio da instalacao (missao
# PLATFORM-API-V1-003, item 4). Um cluster PostgreSQL 16 DESCARTAVEL (--network none, sem porta, sem volume nomeado),
# aplicado no modelo suportado (db/bootstrap/aplicar_como_migrador.sh: migrador dono do banco, SUPERUSER so na janela).
#
#   Matriz: para cada estagio (001-015, 001-016, ..., 001-020), roda os cinco pre-voos como o administrador do cluster.
#     Esperado: SO o pre-voo da versao seguinte termina em PREFLIGHT_OK; os demais em PREFLIGHT_FALHOU (ordem: o pre-voo de
#     uma versao ja aplicada ou de uma versao adiante recusa). No estagio 001-020, todos recusam.
#   Controles (no estagio 001-017, com o pre-voo da 018, depois desfeitos e reconferidos):
#     C1 - migrador com SUPERUSER residual: recusa no item migrador_sem_atributo_nem_acl_de_parametro;
#     C2 - corpo de ops_operator_disable alterado por fora (um comentario a mais): recusa no item quatro_corpos.
#   Historico do Flyway SIMULADO: o modelo das suites nao usa Flyway; depois de cada estagio o teste registra em
#   public.flyway_schema_history (colunas e tipos do Flyway 11, dono migrador_nekkora) uma linha success=true por versao
#   aplicada - o pre-voo so le version e success. Na homologacao quem grava e o proprio Flyway.
#   Os pre-voos sao SOMENTE LEITURA: o catalogo de public e ops (c_listagem_catalogo) e o mesmo antes e depois de cada rodada.
#
# Uso: ops/homologacao/testes/test_preflight_016_020.sh   (TESTE_PROJETO para rodar ao lado de outra execucao)
# Sucesso: o container e removido. Falha: fica no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
PF="$REPO/ops/homologacao/implantacao/preflight"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
PROJETO="${TESTE_PROJETO:-nekkora-preflight-016-020}"
C="$PROJETO"
SAIDA="$REPO/ops/homologacao/saidas/$(date -u +%Y%m%dT%H%M%SZ)_${PROJETO}"
IMAGEM=postgres:16-alpine

falha() { echo "ERRO: $*" >&2; echo "container mantido para inspecao. Remocao: docker rm -f -v $C" >&2; exit 1; }
docker container inspect "$C" >/dev/null 2>&1 && falha "container $C ja existe"
mkdir -p "$SAIDA/mig"
for n in $(seq -w 1 20); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
cp "$REPO/db/bootstrap/aplicar_como_migrador.sh" "$SAIDA/modelo.sh"; chmod 644 "$SAIDA/modelo.sh"
(cd "$PF" && sha256sum 01[6-9].sql 020.sql) > "$SAIDA/00_preflight_sha256.txt"
(cd "$SAIDA/mig" && sha256sum *.sql) > "$SAIDA/00_migracoes_sha256.txt"

SENHA="$(head -c 18 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
MSYS_NO_PATHCONV=1 docker run -d --name "$C" --network none --label nekkora.teste="$PROJETO" \
    -e POSTGRES_USER=nekkora -e POSTGRES_PASSWORD="$SENHA" -e POSTGRES_DB=nekkora "$IMAGEM" > /dev/null
for i in $(seq 1 60); do
    [ "$(docker logs "$C" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
    [ "$i" -lt 60 ] || falha "postgres nao subiu"
    sleep 2
done
MSYS_NO_PATHCONV=1 docker exec "$C" mkdir -p /nekkora/migracoes
for f in "$SAIDA"/mig/*.sql "$SAIDA/modelo.sh"; do
    MSYS_NO_PATHCONV=1 docker cp "$(cygpath -m "$f" 2>/dev/null || echo "$f")" "$C:/nekkora/$( [ "$(basename "$f")" = modelo.sh ] && echo aplicar_como_migrador.sh || echo "migracoes/$(basename "$f")")" > /dev/null
done
adm() { MSYS_NO_PATHCONV=1 docker exec -i "$C" psql -X -q -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora "$@"; }
aplicar() {   # $1 desde, $2 ate
    MSYS_NO_PATHCONV=1 docker exec "$C" env NEKKORA_DB=nekkora NEKKORA_ADMIN=nekkora NEKKORA_DESDE="$1" NEKKORA_ATE="$2" \
        sh /nekkora/aplicar_como_migrador.sh >> "$SAIDA/aplicacao.txt" 2>&1 || falha "aplicacao $1-$2 (ver aplicacao.txt)"
}
historico() {   # $1 desde, $2 ate: linhas success=true, como o Flyway gravaria
    adm -c "CREATE TABLE IF NOT EXISTS public.flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50),
        description VARCHAR(200) NOT NULL, type VARCHAR(20) NOT NULL, script VARCHAR(1000) NOT NULL, checksum INT,
        installed_by VARCHAR(100) NOT NULL, installed_on TIMESTAMP NOT NULL DEFAULT now(), execution_time INT NOT NULL,
        success BOOLEAN NOT NULL); ALTER TABLE public.flyway_schema_history OWNER TO migrador_nekkora"
    for n in $(seq "$((10#$1))" "$((10#$2))"); do
        v="$(printf '%03d' "$n")"
        adm -c "INSERT INTO public.flyway_schema_history VALUES ($n, '$v', 'simulado', 'SQL', '${v}_simulado.sql', 0, 'migrador_nekkora', now(), 0, true)"
    done
}
catalogo() { adm < "$LISTAGEM" > "$SAIDA/$1" 2>&1 || falha "listagem do catalogo ($1)"; }
# roda o pre-voo $1 e devolve OK/FALHOU pela ULTIMA linha (as duas condicoes do runner: codigo 0 E PREFLIGHT_OK)
preflight() {
    local v="$1" arq="$SAIDA/$2_preflight_$1.txt" rc=0
    adm < "$PF/$v.sql" > "$arq" 2>&1 || rc=$?
    if [ "$rc" = 0 ] && [ "$(tr -d '\r' < "$arq" | tail -1)" = PREFLIGHT_OK ]; then echo OK; else echo FALHOU; fi
}

RES="$SAIDA/resultado.txt"
echo "estagio|016|017|018|019|020|esperado|confere" > "$RES"
erros=0
for ate in 015 016 017 018 019 020; do
    if [ "$ate" = 015 ]; then aplicar 001 015; historico 001 015; else aplicar "$ate" "$ate"; historico "$ate" "$ate"; fi
    catalogo "catalogo_${ate}_antes.txt"
    linha="001-$ate"; esp=""
    for v in 016 017 018 019 020; do
        r="$(preflight "$v" "e$ate")"
        linha="$linha|$r"
        if [ "$((10#$v))" = "$((10#$ate + 1))" ]; then esp="$esp|OK"; else esp="$esp|FALHOU"; fi
    done
    catalogo "catalogo_${ate}_depois.txt"
    cmp -s "$SAIDA/catalogo_${ate}_antes.txt" "$SAIDA/catalogo_${ate}_depois.txt" || falha "pre-voo alterou o catalogo no estagio $ate"
    obt="${linha#*|}"; esp="${esp#|}"
    if [ "$obt" = "$esp" ]; then echo "$linha|$esp|SIM" >> "$RES"; else echo "$linha|$esp|NAO" >> "$RES"; erros=$((erros + 1)); fi
    if [ "$ate" = 017 ]; then
        # C1: SUPERUSER residual do migrador (o runner ja recusa pelo 0|0|0; o pre-voo e a segunda camada)
        adm -c "ALTER ROLE migrador_nekkora SUPERUSER"
        r1="$(preflight 018 c1)"
        adm -c "ALTER ROLE migrador_nekkora NOSUPERUSER"
        grep -q '^migrador_sem_atributo_nem_acl_de_parametro|FALHA|' "$SAIDA/c1_preflight_018.txt" && [ "$r1" = FALHOU ] \
            && echo "C1|018 com SUPERUSER residual|FALHOU no item migrador_sem_atributo|SIM" >> "$RES" \
            || { echo "C1|018 com SUPERUSER residual|$r1|NAO" >> "$RES"; erros=$((erros + 1)); }
        # C2: corpo alterado por fora (definicao identica + um comentario no fim do corpo), depois restaurado byte a byte
        adm -c "SELECT pg_get_functiondef('public.ops_operator_disable(uuid)'::regprocedure)" > "$SAIDA/c2_original.sql"
        adm <<'SQL'
DO $c2$
DECLARE d TEXT := pg_get_functiondef('public.ops_operator_disable(uuid)'::regprocedure);
BEGIN
    EXECUTE regexp_replace(d, '\$function\$\s*$', '-- alterado por fora (controle C2)' || chr(10) || '$function$');
END $c2$;
SQL
        r2="$(preflight 018 c2)"
        { tr -d '\r' < "$SAIDA/c2_original.sql"; echo ";"; } | adm
        r2b="$(preflight 018 c2_restaurado)"
        grep -q '^quatro_corpos_iguais_aos_da_016_017|FALHA|' "$SAIDA/c2_preflight_018.txt" && [ "$r2" = FALHOU ] && [ "$r2b" = OK ] \
            && echo "C2|018 com corpo alterado por fora|FALHOU no item quatro_corpos; restaurado: OK|SIM" >> "$RES" \
            || { echo "C2|018 com corpo alterado por fora|$r2 (restaurado: $r2b)|NAO" >> "$RES"; erros=$((erros + 1)); }
    fi
done
cat "$RES"
[ "$erros" = 0 ] || falha "$erros linha(s) fora do esperado (ver $RES)"
docker rm -f -v "$C" > /dev/null
echo "OK: matriz e controles conferidos; container removido. Evidencia: $SAIDA"
