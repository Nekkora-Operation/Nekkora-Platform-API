#!/usr/bin/env bash
# Testes do proprio runner (revisao da 012): cada caso introduz de proposito um defeito numa stack
# nova e isolada e EXIGE que a suite falhe de forma inequivoca, com a mensagem esperada. Nos casos
# de concessao indevida, o bloco de invariantes da secao 15 da 012 tambem e executado contra a base
# mutada e precisa abortar. Cada stack e removida ao final do seu caso (recurso por recurso).
#
# Uso: db/tests/seguranca-012/testes-do-runner/executa.sh
set -uo pipefail
AQUI="$(cd "$(dirname "$0")" && pwd)"
SUITE="$AQUI/.."
REPO="$(cd "$SUITE/../../.." && pwd)"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)"
mkdir -p "$SAIDA"
RESUMO="$SAIDA/resumo.txt"
PORTA=55460
falhas=0

# bloco de invariantes da secao 15, extraido da propria migration (sem o COMMIT final)
awk '/^-- === 15\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/012_security_rls.sql" > "$SAIDA/invariantes_secao15.sql"

remove_stack() {
    local proj="$1"
    docker rm -f "${proj}-postgres" >/dev/null 2>&1
    docker volume rm "${proj}_nekkora_postgres_data" >/dev/null 2>&1
    docker network rm "${proj}_default" >/dev/null 2>&1
}

# caso <id> <mensagem-esperada-na-saida-do-runner> <checar-invariantes:sim|nao> [VAR=valor ...]
caso() {
    local id="$1" esperado="$2" inv="$3"; shift 3
    local proj="nekkora-mut-$id" log="$SAIDA/$id.runner.txt" rc=0
    PORTA=$((PORTA + 1))
    echo ">> caso $id"
    env "$@" TESTE_PROJETO="$proj" TESTE_PORTA="$PORTA" "$SUITE/run.sh" > "$log" 2>&1 || rc=$?
    # guarda as saidas da suite desta execucao junto com o caso
    local dir; dir="$(ls -d "$SUITE"/saidas/*_"$proj" 2>/dev/null | tail -1)"
    [ -n "$dir" ] && mv "$dir" "$SAIDA/$id.saidas-da-suite"
    local veredito="REPROVADO"
    if [ "$rc" -ne 0 ] && grep -rqF -- "$esperado" "$log" "$SAIDA/$id.saidas-da-suite"; then veredito="ok"; fi
    echo "$id: runner exit=$rc; $(grep -m1 'ERRO:' "$log"); mensagem esperada [$esperado]: $veredito" | tee -a "$RESUMO"
    [ "$veredito" = ok ] || falhas=$((falhas + 1))
    if [ "$inv" = sim ]; then
        local rci=0
        docker exec -i "${proj}-postgres" psql -X -v ON_ERROR_STOP=1 -U nekkora -d nekkora \
            < "$SAIDA/invariantes_secao15.sql" > "$SAIDA/$id.invariantes.txt" 2>&1 || rci=$?
        local v2="REPROVADO"; [ "$rci" -ne 0 ] && grep -q "ERROR" "$SAIDA/$id.invariantes.txt" && v2="ok"
        echo "$id: invariantes da secao 15 exit=$rci ($(grep -m1 ERROR "$SAIDA/$id.invariantes.txt")): $v2" | tee -a "$RESUMO"
        [ "$v2" = ok ] || falhas=$((falhas + 1))
    fi
    remove_stack "$proj"
}

M="$AQUI/mutacoes"
caso m1 "nenhum privilegio de tabela alem da matriz" nao TESTE_MUTACAO="$M/m1_catalogo_grant_extra.sql"
caso m2 "operacao deveria ter falhado" nao TESTE_MUTACAO="$M/m2_negativa_passa.sql"
caso m3 "concorrencia 1: sessao A falhou" nao TESTE_FORCAR_FALHA_A=1
caso m4a "nenhuma membership envolvendo papel protegido" sim TESTE_MUTACAO="$M/m4a_membership_owner_para_outro.sql"
caso m4b "nenhuma membership envolvendo papel protegido" sim TESTE_MUTACAO="$M/m4b_membership_outro_para_servico.sql"
caso m4c "nenhum papel protegido com CREATE efetivo" sim TESTE_MUTACAO="$M/m4c_create_schema_servico.sql"
caso m4d "nenhum papel protegido com CREATE efetivo" sim TESTE_MUTACAO="$M/m4d_create_banco_owner.sql"

echo "falhas=$falhas" | tee -a "$RESUMO"
[ "$falhas" -eq 0 ] || { echo "ERRO: algum caso nao produziu a falha esperada"; exit 1; }
echo "OK - todos os defeitos introduzidos foram detectados (saidas em $SAIDA)"
