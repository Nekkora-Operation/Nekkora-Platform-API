#!/usr/bin/env bash
# Prova do procedimento de atualizacao do gateway (P-C-R2): lib/gateway_tipos.sh, a MESMA usada contra o ambiente, com
# COMPOSE CONTROLADO (funcao compose deste teste: ps, logs, "nginx -T", "nginx -t" e reload simulados sobre arquivos)
# e leitura de tipos simulada. O "arquivo montado" e o proprio config/nginx.conf de um ambiente FALSO em diretorio
# temporario, gerado do modelo real (redes.py gerar + gateway_frontend.py). Nenhum container, rede ou ambiente.
#
# Uso: bash testes/test_gateway_tipos.sh [dir_de_saida]   (codigo 0 = todas as provas OK)
set -uo pipefail
OPS="$(cd "$(dirname "$0")/.." && pwd)"
SAIDA="${1:-}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP:?}"' EXIT
falhas=0
ok() { echo "OK    $*"; }
nok() { echo "FALHA $*"; falhas=$((falhas + 1)); }
GT_SO_FUNCOES=1 . "$OPS/lib/gateway_tipos.sh"
. "$OPS/lib/selecao_gateway.sh"
GT_OPS="$OPS"; GT_DORME=true

# ---- ambiente falso: base gerada do modelo real; "atual" = gerado + bloco SEM os types (o que o ensaio-frontend tem)
python "$OPS/lib/redes.py" gerar "$OPS/gateway/nginx.modelo" "$TMP/base.conf" INTERNA=10.252.99 > /dev/null || { nok "geracao da base"; exit 1; }
python "$OPS/lib/gateway_frontend.py" aplicar "$TMP/base.conf" "$TMP/com_tipos.conf" > /dev/null || { nok "aplicacao do bloco"; exit 1; }
python - "$TMP/com_tipos.conf" "$TMP/atual_original.conf" <<'PY' || { nok "atual sem types"; exit 1; }
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
a = re.sub(r'            types \{\n(?:                [^\n]*\n)+            \}\n', '', s)
assert s.count('types {') == 4 and a.count('types {') == 0
open(sys.argv[2], 'w', encoding='utf-8', newline='\n').write(a)
PY

# ---- compose controlado: o container "monta" $GT_CONF; estado e falhas programaveis em $T
compose() {
    echo "compose $*" >> "$T/chamadas.txt"
    case "$1 ${2:-} ${3:-} ${4:-} ${5:-}" in
        "ps -q --status running gateway") [ -f "$T/ps_falha" ] && return 1; [ -f "$T/parado" ] || echo c0ffee; return 0 ;;
        "logs "*) [ -f "$T/logs_falha" ] && { echo "Error: simulado" >&2; return 1; }
                  [ -f "$T/recente" ] && echo "gateway-1  | $(date -u +%Y-%m-%dT%H:%M:%S).000Z 10.252.95.1 \"GET /api/v1/csrf\" 200"; return 0 ;;
        "exec -T gateway nginx -T") echo "# configuration file /etc/nginx/nginx.conf:"
                  if [ -f "$T/atrasado" ]; then cat "$T/visto_antes"; else cat "$GT_CONF"; fi; echo; return 0 ;;
        "exec -T gateway nginx -t") [ -f "$T/nginx_t_falha" ] && { echo "nginx: [emerg] simulado"; return 1; }; echo "syntax is ok"; return 0 ;;
        "exec -T gateway nginx -s") cp "$GT_CONF" "$T/carregado"; echo x >> "$T/reloads"; return 0 ;;
    esac
    echo "compose inesperado: $*" >&2; return 1
}
gt_tipo() {
    case "$1" in
        *.js) grep -q 'text/javascript js mjs;' "$T/carregado" && echo "200 text/javascript" || echo "200 text/plain" ;;
        *.css) grep -q 'text/css css;' "$T/carregado" && echo "200 text/css" || echo "200 text/plain" ;;
        *) echo "200 text/html" ;;
    esac
}

novo() {  # cenario novo: ambiente falso limpo, preparo feito
    T="$TMP/$1"; GT_E="$T/amb"; GT_D="$T/evid"; mkdir -p "$GT_E/config" "$GT_D"
    cp "$TMP/base.conf" "$GT_E/config/nginx.base.conf"; cp "$TMP/atual_original.conf" "$GT_E/config/nginx.conf"
    gt_caminhos; cp "$GT_CONF" "$T/carregado"; cp "$GT_CONF" "$T/visto_antes"; : > "$T/reloads"; : > "$T/chamadas.txt"
    ( GT_ACAO=preparar; gt_preparar ) > "$T/preparar.out" 2>&1
}
roda() { ( GT_ACAO="$1"; "gt_$1" ) > "$T/$1.out" 2>&1; echo $?; }
reloads() { wc -l < "$T/reloads" | tr -d ' '; }
igual() { cmp -s "$1" "$2"; }
guarda() { if [ -n "$SAIDA" ]; then mkdir -p "$SAIDA/$1"; cp -r "$T/." "$SAIDA/$1/"; fi; }

echo "== 1. preparo"
novo p01; guarda p01
[ "$(awk '{print $1}' "$GT_D/referencia.txt" | tr '\n' ' ')" = "conf base candidato ferramenta " ] && grep -q "SO por 4 blocos types" "$T/preparar.out" \
    && ok "P01 preparo: referencia com sha256 do atual, da base, do candidato e da ferramenta; diferenca = 4 blocos types" || nok "P01 preparo ($(cat "$T/preparar.out"))"
novo p02; echo "# alteracao estranha" >> "$GT_CONF"; c=$(roda preparar); guarda p02
[ "$c" != 0 ] && grep -q "difere do atual em algo alem" "$T/preparar.out" && ok "P02 preparo recusa atual com alteracao alem dos types" || nok "P02 preparo ($c)"

echo "== 2. coleta indisponivel ou trafego recente: nenhuma escrita e nenhum reload"
for cen in logs_falha ps_falha parado recente; do
    novo "a-$cen"; antes="$(gt_sha "$GT_CONF")"; : > "$T/$cen"; c=$(roda aplicar); guarda "a-$cen"
    [ "$c" != 0 ] && [ "$(gt_sha "$GT_CONF")" = "$antes" ] && [ "$(reloads)" = 0 ] && [ ! -e "$GT_BKP" ] && grep -q "pre-condicao NAO" "$T/aplicar.out" \
        && ok "A-$cen: recusado; arquivo intacto, 0 reloads, sem copia de seguranca" || nok "A-$cen (codigo $c; $(tail -1 "$T/aplicar.out"))"
done
# coleta que falha com arquivo de linhas VAZIO deixado por uma execucao anterior (ex.: repetir o aplicar depois de um
# STOP): sem a conferencia explicita da coleta, a contagem desse arquivo velho seria "0 requisicoes"
novo a-arquivo-anterior; antes="$(gt_sha "$GT_CONF")"; : > "$GT_D/aplicar.pre_condicao_gateway.txt"; : > "$T/logs_falha"; c=$(roda aplicar); guarda a-arquivo-anterior
[ "$c" != 0 ] && [ "$(gt_sha "$GT_CONF")" = "$antes" ] && [ "$(reloads)" = 0 ] && grep -q "pre-condicao NAO conferida: falha na coleta" "$T/aplicar.out" \
    && ok "A-arquivo-anterior: coleta falha com arquivo vazio de execucao anterior -> recusado pela coleta, nada escrito" || nok "A-arquivo-anterior (codigo $c; $(tail -1 "$T/aplicar.out"))"
novo a-logs-msg; : > "$T/logs_falha"; roda aplicar > /dev/null
grep -q "falha na coleta" "$T/aplicar.out" && grep -q "compose logs terminou com codigo 1" "$T/aplicar.out" \
    && ok "A-logs: a falha da coleta e relatada com o erro do compose (nao vira contagem zero)" || nok "A-logs mensagem ($(tail -1 "$T/aplicar.out"))"

echo "== 3. atual, base, candidato ou ferramenta alterado depois do preparo: recusa sem escrita nem reload"
for alvo in conf base candidato; do
    novo "r-$alvo"; case $alvo in conf) f="$GT_CONF";; base) f="$GT_BASE";; candidato) f="$GT_CAND";; esac
    echo "# mudou depois do preparo" >> "$f"; antes="$(gt_sha "$GT_CONF")"; c=$(roda aplicar); guarda "r-$alvo"
    [ "$c" != 0 ] && [ "$(gt_sha "$GT_CONF")" = "$antes" ] && [ "$(reloads)" = 0 ] && [ ! -e "$GT_BKP" ] && grep -q "referencia divergente: $alvo mudou" "$T/aplicar.out" \
        && ! grep -q '^compose logs' "$T/chamadas.txt" \
        && ok "R-$alvo: recusado ANTES de qualquer coleta; arquivo intacto, 0 reloads" || nok "R-$alvo (codigo $c; $(tail -1 "$T/aplicar.out"))"
done
novo r-ferramenta; sed -i 's/^ferramenta .*/ferramenta 0000000000000000000000000000000000000000000000000000000000000000/' "$GT_D/referencia.txt"
c=$(roda aplicar); guarda r-ferramenta
[ "$c" != 0 ] && [ "$(reloads)" = 0 ] && grep -q "referencia divergente: ferramenta mudou" "$T/aplicar.out" && ok "R-ferramenta: ferramenta diferente da do preparo: recusado" || nok "R-ferramenta ($c)"
novo r-sem-preparo; rm -f "$GT_D/referencia.txt"; c=$(roda aplicar); guarda r-sem-preparo
[ "$c" != 0 ] && [ "$(reloads)" = 0 ] && grep -q "sem referencia do preparo" "$T/aplicar.out" && ok "R-sem-preparo: recusado" || nok "R-sem-preparo ($c)"

echo "== 4. falhas depois de escrever: anterior reescrito, sem reload"
novo f-nginx-t; orig="$(gt_sha "$GT_CONF")"; : > "$T/nginx_t_falha"; c=$(roda aplicar); guarda f-nginx-t
[ "$c" != 0 ] && [ "$(gt_sha "$GT_CONF")" = "$orig" ] && [ "$(reloads)" = 0 ] && grep -q "nginx -t recusou; anterior reescrito" "$T/aplicar.out" \
    && ok "F-nginx-t: nginx -t recusa -> anterior reescrito, 0 reloads" || nok "F-nginx-t ($c)"
novo f-atrasado; orig="$(gt_sha "$GT_CONF")"; : > "$T/atrasado"; c=$(roda aplicar); guarda f-atrasado
[ "$c" != 0 ] && [ "$(gt_sha "$GT_CONF")" = "$orig" ] && [ "$(reloads)" = 0 ] && grep -q "nao enxergou o candidato" "$T/aplicar.out" \
    && ok "F-atrasado: container nao ve o candidato -> anterior reescrito, 0 reloads" || nok "F-atrasado ($c)"
novo f-copia; : > "$GT_BKP"; antes="$(gt_sha "$GT_CONF")"; c=$(roda aplicar); guarda f-copia
[ "$c" != 0 ] && [ "$(gt_sha "$GT_CONF")" = "$antes" ] && [ "$(reloads)" = 0 ] && ok "F-copia: copia de seguranca anterior -> recusado sem escrita" || nok "F-copia ($c)"

echo "== 5. caminho valido e reversao"
novo v01; orig="$(gt_sha "$GT_CONF")"; c=$(roda aplicar)
[ "$c" = 0 ] && igual "$GT_CONF" "$GT_CAND" && [ "$(reloads)" = 1 ] && [ "$(gt_sha "$GT_BKP")" = "$orig" ] && grep -q "APLICADO" "$T/aplicar.out" \
    && grep -q "/assets/index-ZuNqN1Cn.js -> 200 text/javascript" "$T/aplicar.out" \
    && ok "V01 aplicar: candidato escrito, 1 reload, JS text/javascript, copia de seguranca = original" || nok "V01 aplicar ($c; $(tail -2 "$T/aplicar.out"))"
c=$(roda reverter); guarda v01
[ "$c" = 0 ] && [ "$(gt_sha "$GT_CONF")" = "$orig" ] && [ "$(reloads)" = 2 ] && [ ! -e "$GT_BKP" ] && [ -f "$GT_D/nginx.conf.revertido-de" ] \
    && grep -q "/assets/index-ZuNqN1Cn.js -> 200 text/plain" "$T/reverter.out" \
    && ok "V02 reverter: original de volta, 2o reload, JS text/plain, copia movida para a evidencia" || nok "V02 reverter ($c; $(tail -2 "$T/reverter.out"))"
novo v03; roda aplicar > /dev/null; echo "# mexido" >> "$GT_CONF"; antes="$(gt_sha "$GT_CONF")"; c=$(roda reverter); guarda v03
[ "$c" != 0 ] && [ "$(gt_sha "$GT_CONF")" = "$antes" ] && [ "$(reloads)" = 1 ] && grep -q "o atual nao e o candidato aplicado" "$T/reverter.out" \
    && ok "V03 reverter recusa se o atual nao e o candidato aplicado (sem escrita, sem reload)" || nok "V03 ($c)"
novo v04; roda aplicar > /dev/null; : > "$T/recente"; antes="$(gt_sha "$GT_CONF")"; c=$(roda reverter); guarda v04
[ "$c" != 0 ] && [ "$(gt_sha "$GT_CONF")" = "$antes" ] && [ "$(reloads)" = 1 ] && ok "V04 reverter com trafego recente: recusado sem escrita nem reload" || nok "V04 ($c)"

echo
[ "$falhas" = 0 ] && echo "RESULTADO: procedimento do gateway OK" || echo "RESULTADO: $falhas falha(s)"
exit $(( falhas > 0 ))
