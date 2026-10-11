#!/usr/bin/env bash
# Prova das guardas do gateway da SELECAO de jornadas (P-B-C1-R1): lib/selecao_gateway.sh, a MESMA usada pelo runner
# (jornadas-frontend <id> executar --somente ...), com um COMPOSE CONTROLADO (funcao compose deste teste: ps e logs do
# servico gateway a partir de um arquivo de log simulado, com falhas programaveis) e jornadas FALSAS (contam as
# chamadas, registram requisicoes no log simulado e gravam resumo_executar.json). Nenhum container, rede, ambiente ou
# Platform e tocado.
#
# Uso: bash testes/test_selecao_gateway.sh [dir_de_saida]   (codigo 0 = todas as provas OK)
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
SAIDA="${1:-}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP:?}"' EXIT
falhas=0
ok() { echo "OK    $*"; }
nok() { echo "FALHA $*"; falhas=$((falhas + 1)); }
. "$AQUI/lib/selecao_gateway.sh"

ts() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%S.000000000Z; }
linha_gw() { echo "gateway-1  | $(ts "$1") 10.252.95.1 - - [x] \"GET /api/v1/me HTTP/1.1\" 200 12 \"-\" \"teste\""; }

# ---- compose controlado: estado em $T (modos por chamada; log simulado; registro das chamadas)
compose() {
    echo "compose $*" >> "$T/compose_chamadas.txt"
    case "$1" in
        ps)
            case "$(cat "$T/modo_ps" 2>/dev/null || echo ok)" in
                falha) echo "Error response from daemon: simulado (ps)" >&2; return 1 ;;
                parado) return 0 ;;
                *) echo "c0ffee000001"; return 0 ;;
            esac ;;
        logs)
            local modo since=""
            modo="$(head -1 "$T/modos_logs" 2>/dev/null || echo ok)"; sed -i '1d' "$T/modos_logs" 2>/dev/null || true
            [ "$modo" != falha ] || { echo "Error: cannot connect to the Docker daemon (simulado)" >&2; return 1; }
            while [ $# -gt 0 ]; do [ "$1" = --since ] && since="$2"; shift; done
            [ -n "$since" ] || { echo "teste: logs sem --since" >&2; return 1; }
            echo "gateway-1  | /docker-entrypoint.sh: Configuration complete; ready for start up"
            awk -v s="${since:0:19}" 'substr($3, 1, 19) >= s' "$T/gateway.log" 2>/dev/null
            # requisicao concorrente LOGO DEPOIS da consulta (antes das jornadas): so na primeira coleta, se programada
            if [ -f "$T/concorrente_apos_consulta" ]; then rm -f "$T/concorrente_apos_consulta"; linha_gw "$(date -u +%s)" >> "$T/gateway.log"; fi
            return 0 ;;
    esac
    echo "teste: compose $1 inesperado" >&2; return 1
}

# ---- jornadas falsas: N_REQ requisicoes no gateway, N_RESP respostas validadas, codigo RC
jornadas_falsas() {
    echo "chamada $*" >> "$T/jornadas_chamadas.txt"
    local i agora; agora="$(date -u +%s)"
    for i in $(seq 1 "$N_REQ"); do linha_gw "$agora" >> "$T/gateway.log"; done
    [ "${SEM_RESUMO:-}" = 1 ] || printf '{"codigo": %s, "respostas_validadas": %s}' "$RC" "$N_RESP" > "$T/j/resumo_executar.json"
    return "$RC"
}

# cenario <nome> [VAR=valor...]: roda selecao_executa num diretorio novo; deixa $T, $cod e $cham
cenario() {
    local nome="$1"; shift
    T="$TMP/$nome"; mkdir -p "$T/j"; : > "$T/gateway.log"; : > "$T/jornadas_chamadas.txt"; : > "$T/compose_chamadas.txt"
    N_REQ=11; N_RESP=11; RC=0; SEM_RESUMO=""
    local kv; for kv in "$@"; do
        case "$kv" in
            PS=*) echo "${kv#PS=}" > "$T/modo_ps" ;;
            LOGS=*) echo "${kv#LOGS=}" | tr ',' '\n' > "$T/modos_logs" ;;
            RECENTE=*) linha_gw "$(( $(date -u +%s) - ${kv#RECENTE=} ))" >> "$T/gateway.log" ;;
            CONCORRENTE=1) : > "$T/concorrente_apos_consulta" ;;
            *) eval "$kv" ;;
        esac
    done
    cod=0
    selecao_executa "$T/j" jornadas_falsas --somente sessoes-pos-revogacao,expiracao-absoluta > "$T/saida.txt" 2>&1 || cod=$?
    cham="$(grep -c '^chamada' "$T/jornadas_chamadas.txt")"
    if [ -n "$SAIDA" ]; then mkdir -p "$SAIDA/$nome"; cp -r "$T/." "$SAIDA/$nome/"; fi
}
tem() { grep -q -- "$2" "$T/j/$1" 2>/dev/null; }

echo "== 1. coleta inicial falha -> nenhuma jornada (codigo 4, erro preservado)"
cenario c1-logs-falha LOGS=falha
[ "$cod" = 4 ] && [ "$cham" = 0 ] && tem pre_condicao.txt 'PRE-CONDICAO NAO CONFERIDA' && tem pre_condicao_erro.txt 'cannot connect to the Docker daemon (simulado)' \
    && tem pre_condicao_erro.txt 'compose logs terminou com codigo 1' && ! tem pre_condicao.txt 'conferida:' && [ ! -f "$T/j/resultado_funcional.txt" ] \
    && ok "C01 compose logs falha: recusada, 0 chamadas, erro do compose preservado" || nok "C01 compose logs falha (codigo $cod, chamadas $cham)"
cenario c2-ps-falha PS=falha
[ "$cod" = 4 ] && [ "$cham" = 0 ] && tem pre_condicao_erro.txt 'simulado (ps)' && ! grep -q '^compose logs' "$T/compose_chamadas.txt" \
    && ok "C02 compose ps falha: recusada antes dos logs, 0 chamadas" || nok "C02 compose ps falha (codigo $cod, chamadas $cham)"
cenario c3-gateway-parado PS=parado
[ "$cod" = 4 ] && [ "$cham" = 0 ] && tem pre_condicao_erro.txt 'gateway fora de execucao' \
    && ok "C03 gateway fora de execucao (logs vazios nao valem como ausencia de trafego): recusada, 0 chamadas" || nok "C03 gateway parado (codigo $cod, chamadas $cham)"

echo "== 2. trafego recente -> nenhuma jornada (codigo 5)"
cenario c4-trafego-recente RECENTE=10
[ "$cod" = 5 ] && [ "$cham" = 0 ] && tem pre_condicao.txt 'PRE-CONDICAO NAO ATENDIDA: 1 requisicao' && [ ! -f "$T/j/resultado_funcional.txt" ] \
    && ok "C04 requisicao 10 s antes: recusada, 0 chamadas" || nok "C04 trafego recente (codigo $cod, chamadas $cham)"
cenario c5-trafego-no-limite RECENTE=30
[ "$cod" = 5 ] && [ "$cham" = 0 ] && ok "C05 requisicao 30 s antes (dentro dos 31 s): recusada" || nok "C05 trafego a 30 s (codigo $cod, chamadas $cham)"

echo "== 3. coleta valida e vazia -> UMA chamada"
cenario c6-vazia
[ "$cham" = 1 ] && grep -q -- '--somente sessoes-pos-revogacao,expiracao-absoluta' "$T/jornadas_chamadas.txt" \
    && [ "$(grep -c '^compose logs' "$T/compose_chamadas.txt")" = 2 ] \
    && ok "C06 coleta valida e vazia: exatamente 1 chamada as jornadas, com os argumentos recebidos" || nok "C06 uma chamada (chamadas $cham)"
cenario c7-trafego-antigo RECENTE=40
[ "$cham" = 1 ] && [ "$cod" = 0 ] && ok "C07 requisicao 40 s antes (fora dos 31 s) nao bloqueia: 1 chamada" || nok "C07 trafego antigo (codigo $cod, chamadas $cham)"
# intervalo registrado: desde <= consulta - 31 s e ambos no arquivo; a coleta final usa o MESMO desde
cenario c8-intervalo
d="$(sed -n 's/.*intervalo consultado \[\([^,]*\), \([^]]*\)\].*/\1/p' "$T/j/pre_condicao.txt")"
q="$(sed -n 's/.*intervalo consultado \[\([^,]*\), \([^]]*\)\].*/\2/p' "$T/j/pre_condicao.txt")"
s1="$(grep '^compose logs' "$T/compose_chamadas.txt" | sed -n '1s/.*--since \([^ ]*\).*/\1/p')"
s2="$(grep '^compose logs' "$T/compose_chamadas.txt" | sed -n '2s/.*--since \([^ ]*\).*/\1/p')"
[ -n "$d" ] && [ -n "$q" ] && [ $(( $(date -u -d "$q" +%s) - $(date -u -d "$d" +%s) )) -ge 31 ] && [ "$s1" = "$d" ] && [ "$s2" = "$d" ] \
    && tem pre_condicao.txt "jornadas iniciadas em" \
    && ok "C08 pre_condicao.txt registra o intervalo consultado [$d, $q] (>= 31 s) e a conferencia final cobre desde o mesmo inicio" \
    || nok "C08 intervalo (desde=$d consulta=$q since1=$s1 since2=$s2)"

echo "== 4. coleta final falha ou contagem diverge -> INCONCLUSIVA (codigo 3), sem nova chamada"
cenario c9-final-falha LOGS=ok,falha
[ "$cod" = 3 ] && [ "$cham" = 1 ] && tem resultado_funcional.txt 'jornadas: codigo 0' && tem conferencia_trafego.txt '^INCONCLUSIVA: coleta final' \
    && tem conferencia_erro.txt 'compose logs terminou com codigo 1' \
    && ok "C09 coleta final falha: inconclusiva (3), funcional 0 preservado em separado, 1 chamada" || nok "C09 coleta final falha (codigo $cod, chamadas $cham)"
cenario c10-diverge N_REQ=12 N_RESP=11
[ "$cod" = 3 ] && [ "$cham" = 1 ] && tem conferencia_trafego.txt 'INCONCLUSIVA: gateway 12 requisicoes .* jornadas 11 respostas validadas (DIVERGENTES' \
    && tem resultado_funcional.txt 'jornadas: codigo 0' \
    && ok "C10 12 requisicoes x 11 respostas: inconclusiva (3), 1 chamada" || nok "C10 divergencia (codigo $cod, chamadas $cham)"
cenario c11-concorrente-antes-das-jornadas CONCORRENTE=1
[ "$cod" = 3 ] && [ "$cham" = 1 ] && tem conferencia_trafego.txt 'INCONCLUSIVA: gateway 12 requisicoes' \
    && ok "C11 requisicao entre a consulta e o inicio das jornadas: detectada na conferencia final (inconclusiva)" || nok "C11 trecho ate o inicio (codigo $cod)"
cenario c12-sem-resumo SEM_RESUMO=1
[ "$cod" = 3 ] && [ "$cham" = 1 ] && tem conferencia_trafego.txt 'respostas validadas das jornadas indisponiveis' \
    && ok "C12 jornadas sem resumo: inconclusiva (3)" || nok "C12 sem resumo (codigo $cod)"
cenario c13-jornadas-falham-e-diverge RC=1 N_REQ=3 N_RESP=2
[ "$cod" = 1 ] && [ "$cham" = 1 ] && tem resultado_funcional.txt 'jornadas: codigo 1' && tem conferencia_trafego.txt '^INCONCLUSIVA' \
    && tem resultado.txt 'resultado funcional: codigo 1; conferencia de trafego: INCONCLUSIVA' \
    && ok "C13 STOP das jornadas: codigo delas (1), conferencia registrada em separado, sem nova chamada" || nok "C13 jornadas falham (codigo $cod, chamadas $cham)"

echo "== 5. caminho valido -> sucesso"
cenario c14-sucesso
[ "$cod" = 0 ] && [ "$cham" = 1 ] && tem resultado_funcional.txt 'jornadas: codigo 0' \
    && tem conferencia_trafego.txt '^COMPATIVEL com trafego exclusivo: gateway 11 requisicoes .* jornadas 11 respostas validadas (nao e prova absoluta' \
    && tem resultado.txt 'conferencia de trafego: COMPATIVEL' && ! grep -rq 'IGUAIS\|sem trafego concorrente' "$T/j" \
    && ok "C14 caminho valido: codigo 0, conferencia COMPATIVEL (sem afirmar ausencia absoluta)" || nok "C14 sucesso (codigo $cod, chamadas $cham)"

echo
[ "$falhas" = 0 ] && echo "RESULTADO: guardas da selecao OK" || echo "RESULTADO: $falhas falha(s)"
exit $(( falhas > 0 ))
