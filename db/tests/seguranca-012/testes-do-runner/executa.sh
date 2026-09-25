#!/usr/bin/env bash
# Testes do proprio runner (revisao da 012): cada caso introduz de proposito um defeito numa stack
# nova e isolada e EXIGE que a suite falhe de forma inequivoca, com a mensagem esperada. Nos casos
# de concessao indevida, o bloco de invariantes da secao 15 da 012 tambem e executado contra a base
# mutada e precisa abortar.
#
# Protecao operacional (os nomes das stacks sao fixos: nekkora-mut-<caso>):
#   1. verificacao previa: se QUALQUER container, volume ou rede de QUALQUER caso ja existir, aborta
#      antes de executar qualquer coisa - nada e criado, alterado ou removido;
#   2. a mesma verificacao imediatamente antes de cada caso (recurso surgido durante a execucao);
#   3. verificacao de invariantes e limpeza so atuam sobre recursos COMPROVADAMENTE criados pelo caso
#      atual: rotulo com.docker.compose.project igual ao projeto do caso E criacao posterior ao
#      inicio do caso. Qualquer outro recurso com o mesmo nome e deixado intacto (e reportado).
#
# Uso: db/tests/seguranca-012/testes-do-runner/executa.sh
set -uo pipefail
AQUI="$(cd "$(dirname "$0")" && pwd)"
SUITE="$AQUI/.."
REPO="$(cd "$SUITE/../../.." && pwd)"
CASOS=(m1 m2 m3 m4a m4b m4c m4d)

container_de() { echo "nekkora-mut-$1-postgres"; }
volume_de()    { echo "nekkora-mut-$1_nekkora_postgres_data"; }
rede_de()      { echo "nekkora-mut-$1_default"; }

# lista os recursos existentes (container/volume/rede) de um caso; vazio = nenhum
recursos_existentes() {
    local id="$1"
    docker container inspect "$(container_de "$id")" >/dev/null 2>&1 && echo "container $(container_de "$id")"
    docker volume inspect "$(volume_de "$id")" >/dev/null 2>&1 && echo "volume $(volume_de "$id")"
    docker network inspect "$(rede_de "$id")" >/dev/null 2>&1 && echo "rede $(rede_de "$id")"
    return 0
}

# --- 1. verificacao previa de TODOS os casos, antes de criar qualquer coisa (inclusive a pasta de saida) ---
conflitos=""
for id in "${CASOS[@]}"; do
    r="$(recursos_existentes "$id")"
    [ -n "$r" ] && conflitos="$conflitos$r"$'\n'
done
if [ -n "$conflitos" ]; then
    echo "ERRO: recursos com nomes reservados pelos testes do runner ja existem. Nada foi executado," >&2
    echo "criado, alterado ou removido. Remova-os conscientemente ou investigue antes de rodar:" >&2
    printf '%s' "$conflitos" | sed 's/^/  - /' >&2
    exit 2
fi

SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)"
mkdir -p "$SAIDA"
RESUMO="$SAIDA/resumo.txt"
PORTA=55460
falhas=0

# bloco de invariantes da secao 15, extraido da propria migration (sem o COMMIT final)
awk '/^-- === 15\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/012_security_rls.sql" > "$SAIDA/invariantes_secao15.sql"

# epoch (segundos) de um timestamp do Docker (RFC3339, com ou sem fracao)
epoch() { date -u -d "$(echo "$1" | sed -E 's/\.[0-9]+//')" +%s 2>/dev/null || echo 0; }

# criado_pelo_caso <tipo> <nome> <projeto> <inicio_epoch>: rotulo do projeto E criacao >= inicio
criado_pelo_caso() {
    local tipo="$1" nome="$2" proj="$3" inicio="$4" rotulo criado
    case "$tipo" in
        container) rotulo="$(docker container inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$nome" 2>/dev/null)"
                   criado="$(docker container inspect -f '{{.Created}}' "$nome" 2>/dev/null)" ;;
        volume)    rotulo="$(docker volume inspect -f '{{index .Labels "com.docker.compose.project"}}' "$nome" 2>/dev/null)"
                   criado="$(docker volume inspect -f '{{.CreatedAt}}' "$nome" 2>/dev/null)" ;;
        rede)      rotulo="$(docker network inspect -f '{{index .Labels "com.docker.compose.project"}}' "$nome" 2>/dev/null)"
                   criado="$(docker network inspect -f '{{.Created}}' "$nome" 2>/dev/null)" ;;
    esac
    [ -n "$criado" ] && [ "$rotulo" = "$proj" ] && [ "$(epoch "$criado")" -ge "$inicio" ]
}

# remove, recurso por recurso, SO o que foi criado por este caso; o resto e reportado e preservado
remove_criados() {
    local id="$1" proj="$2" inicio="$3" tipo nome
    for par in "container $(container_de "$id")" "volume $(volume_de "$id")" "rede $(rede_de "$id")"; do
        set -- $par; tipo="$1"; nome="$2"
        case "$tipo" in
            container) docker container inspect "$nome" >/dev/null 2>&1 || continue ;;
            volume)    docker volume inspect "$nome" >/dev/null 2>&1 || continue ;;
            rede)      docker network inspect "$nome" >/dev/null 2>&1 || continue ;;
        esac
        if criado_pelo_caso "$tipo" "$nome" "$proj" "$inicio"; then
            case "$tipo" in
                container) docker rm -f "$nome" >/dev/null ;;
                volume)    docker volume rm "$nome" >/dev/null ;;
                rede)      docker network rm "$nome" >/dev/null ;;
            esac
            echo "$id: removido $tipo $nome (criado por este caso)" >> "$RESUMO"
        else
            echo "$id: PRESERVADO $tipo $nome (nao comprovadamente criado por este caso)" | tee -a "$RESUMO"
        fi
    done
}

# caso <id> <mensagem-esperada> <checar-invariantes:sim|nao> [VAR=valor ...]
caso() {
    local id="$1" esperado="$2" inv="$3"; shift 3
    local proj="nekkora-mut-$id" log="$SAIDA/$id.runner.txt" rc=0
    PORTA=$((PORTA + 1))
    echo ">> caso $id"

    # --- 2. nova verificacao imediatamente antes do caso ---
    local r; r="$(recursos_existentes "$id")"
    if [ -n "$r" ]; then
        echo "ERRO: recurso do caso $id surgiu durante a execucao; abortando sem tocar nele:" | tee -a "$RESUMO" >&2
        echo "$r" | sed 's/^/  - /' | tee -a "$RESUMO" >&2
        exit 2
    fi

    local antes; antes="$(ls -d "$SUITE"/saidas/*/ 2>/dev/null)"
    local inicio; inicio="$(date -u +%s)"
    env "$@" TESTE_PROJETO="$proj" TESTE_PORTA="$PORTA" "$SUITE/run.sh" > "$log" 2>&1 || rc=$?

    # saidas da suite: so o diretorio criado nesta execucao (nunca um preexistente de mesmo nome)
    local dir; dir="$(comm -13 <(printf '%s\n' "$antes" | sort) <(ls -d "$SUITE"/saidas/*/ 2>/dev/null | sort) | grep "_${proj}/$" | tail -1)"
    [ -n "$dir" ] && mv "${dir%/}" "$SAIDA/$id.saidas-da-suite"

    local veredito="REPROVADO"
    if [ "$rc" -ne 0 ] && grep -rqF -- "$esperado" "$log" "$SAIDA/$id.saidas-da-suite" 2>/dev/null; then veredito="ok"; fi
    echo "$id: runner exit=$rc; $(grep -m1 'ERRO:' "$log"); mensagem esperada [$esperado]: $veredito" | tee -a "$RESUMO"
    [ "$veredito" = ok ] || falhas=$((falhas + 1))

    # --- 3. invariantes so contra o container comprovadamente criado por este caso ---
    if [ "$inv" = sim ]; then
        if criado_pelo_caso container "$(container_de "$id")" "$proj" "$inicio"; then
            local rci=0
            docker exec -i "$(container_de "$id")" psql -X -v ON_ERROR_STOP=1 -U nekkora -d nekkora \
                < "$SAIDA/invariantes_secao15.sql" > "$SAIDA/$id.invariantes.txt" 2>&1 || rci=$?
            local v2="REPROVADO"; [ "$rci" -ne 0 ] && grep -q "ERROR" "$SAIDA/$id.invariantes.txt" && v2="ok"
            echo "$id: invariantes da secao 15 exit=$rci ($(grep -m1 ERROR "$SAIDA/$id.invariantes.txt")): $v2" | tee -a "$RESUMO"
            [ "$v2" = ok ] || falhas=$((falhas + 1))
        else
            echo "$id: invariantes NAO executados - container nao foi criado por este caso" | tee -a "$RESUMO"
            falhas=$((falhas + 1))
        fi
    fi
    remove_criados "$id" "$proj" "$inicio"
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
