#!/usr/bin/env bash
# Atualizacao do gateway do ensaio-frontend com os tipos dos estaticos (P-C-R1/R2; lib/gateway_frontend.py, TIPOS).
# RESTRITO AO GATEWAY: so o arquivo config/nginx.conf do ambiente (montado so leitura, como ARQUIVO, em
# /etc/nginx/nginx.conf) e "nginx -s reload" no container gateway. Platform, Portal, banco, Redis, segredos,
# certificados e build: intocados. NAO reinicia container.
#
# Uso (de ops/homologacao):  bash lib/gateway_tipos.sh preparar|aplicar|reverter|conferir <dir_de_evidencia>
#   preparar  gera o candidato a partir de config/nginx.base.conf; confere "gerado + bloco da aplicacao, e mais nada"
#             e que o candidato difere do atual SO pelos blocos types; grava a REFERENCIA (sha256 do atual, da base,
#             do candidato e de lib/gateway_frontend.py) em <dir>/referencia.txt
#   aplicar   1. REFERENCIA reconferida (sha256 do atual, da base, do candidato e da ferramenta = os do preparo; candidato
#                ainda "gerado + bloco"; diferenca atual x candidato ainda so os types) -> senao PARA sem escrita
#             2. PRE-CONDICAO: coleta do gateway VERIFICADA (coleta_gateway de lib/selecao_gateway.sh: gateway em
#                execucao, codigo do compose logs e do filtro conferidos SEPARADAMENTE da contagem) e 0 requisicoes
#                nos 31 s -> senao PARA sem escrita
#             3. sem copia de seguranca anterior; o nginx do container carrega o arquivo atual (nginx -T)
#             4. sha256 do atual reconferido IMEDIATAMENTE antes de escrever; copia de seguranca; escrita do candidato
#                NO MESMO arquivo; nginx -T = candidato e nginx -t -> senao reescreve o anterior e PARA sem reload
#             5. reload; JS text/javascript e CSS text/css
#   reverter  referencia da aplicacao (aplicado.txt) reconferida: atual = candidato aplicado e copia = a gravada;
#             mesma pre-condicao de trafego; escreve a copia no mesmo arquivo; nginx -T e nginx -t; reload; JS text/plain
#   conferir  so leitura: sha256 local, nginx -T do container e tipos servidos
# Qualquer conferencia que falhe: PARA (codigo 1), sem nova tentativa. "nginx -T" e lido de novo por ate 10 s (so
# leitura) porque o Docker Desktop pode atrasar a visao do arquivo reescrito; nenhuma ACAO e repetida.
#
# Para testes: "source" com GT_SO_FUNCOES=1 define so as funcoes; o chamador fornece compose (docker compose do
# ambiente), gt_tipo <caminho> ("status content-type"), GT_E (saidas/<id>), GT_OPS e, opcionalmente, GT_DORME.

gt_sha() { tr -d '\r' < "$1" | sha256sum | cut -c1-64; }
gt_log() { echo "$*" | tee -a "$GT_D/$GT_ACAO.txt"; }
gt_falha() { echo "PARADO: $*" | tee -a "$GT_D/$GT_ACAO.txt" >&2; exit 1; }
gt_caminhos() {
    GT_CONF="$GT_E/config/nginx.conf"; GT_BASE="$GT_E/config/nginx.base.conf"; GT_BKP="$GT_E/config/nginx.conf.antes-pc-r1"
    GT_CAND="$GT_D/nginx.conf.candidato"; GT_FERR="$GT_OPS/lib/gateway_frontend.py"
}
gt_so_types() {  # atual candidato -> 0 se o candidato so ACRESCENTA blocos types ao atual
    diff "$1" "$2" > "$GT_D/diff_atual_candidato.txt" || true
    grep -q '^>' "$GT_D/diff_atual_candidato.txt" || return 1
    ! grep -E '^[<>]' "$GT_D/diff_atual_candidato.txt" \
        | grep -vE '^> +(types \{|text/html html;|text/javascript js mjs;|text/css css;|image/svg\+xml svg;|image/x-icon ico;|\})$' | grep -q .
}
gt_nginx_carrega() {  # arquivo esperado -> 0 se "nginx -T" no container mostrar exatamente esse conteudo (le ate 10 s)
    local i
    for i in $(seq 1 10); do
        compose exec -T gateway nginx -T 2>/dev/null | tr -d '\r' | sed '1d;${/^$/d}' | diff -q - <(tr -d '\r' < "$1") > /dev/null && return 0
        ${GT_DORME:-sleep} 1
    done
    return 1
}
gt_tipos() { local u; for u in /index.html /assets/index-ZuNqN1Cn.js /assets/index-BapnPSoB.css; do gt_log "  GET $u -> $(gt_tipo "$u")"; done; }
gt_sem_trafego() {  # pre-condicao: coleta verificada (selecao_gateway.sh) e 0 requisicoes nos 31 s
    local agora desde n
    agora="$(date -u +%s)"; desde="$(date -u -d "@$((agora - 31))" +%Y-%m-%dT%H:%M:%SZ)"
    : > "$GT_D/$GT_ACAO.pre_condicao_erro.txt"
    coleta_gateway "$desde" "$GT_D/$GT_ACAO.pre_condicao_gateway.txt" "$GT_D/$GT_ACAO.pre_condicao_erro.txt" \
        || gt_falha "pre-condicao NAO conferida: falha na coleta dos logs do gateway ($(tr '\n' ' ' < "$GT_D/$GT_ACAO.pre_condicao_erro.txt")); nada escrito, sem reload"
    n="$(wc -l < "$GT_D/$GT_ACAO.pre_condicao_gateway.txt" | tr -d ' ')"
    [ "$n" = 0 ] || gt_falha "pre-condicao NAO atendida: $n requisicao(oes) no gateway desde $desde; nada escrito, sem reload"
    gt_log "pre-condicao conferida: coleta OK e 0 requisicoes no gateway desde $desde"
}
gt_escreve() { cat "$1" > "$GT_CONF"; }   # no MESMO arquivo: o bind mount de arquivo continua apontando para ele

gt_preparar() {
    : > "$GT_D/preparar.txt"; rm -f "$GT_CAND" "$GT_D/referencia.txt"
    python "$GT_FERR" aplicar "$GT_BASE" "$GT_CAND" > /dev/null || gt_falha "geracao do candidato"
    python "$GT_FERR" conferir "$GT_BASE" "$GT_CAND" > "$GT_D/conferir_candidato.txt" 2>&1 || gt_falha "candidato nao confere"
    gt_log "$(cat "$GT_D/conferir_candidato.txt")"
    gt_so_types "$GT_CONF" "$GT_CAND" || gt_falha "o candidato difere do atual em algo alem dos blocos types (ver diff_atual_candidato.txt)"
    { echo "conf $(gt_sha "$GT_CONF")"; echo "base $(gt_sha "$GT_BASE")"; echo "candidato $(gt_sha "$GT_CAND")"; echo "ferramenta $(gt_sha "$GT_FERR")"; } > "$GT_D/referencia.txt"
    gt_log "candidato difere do atual SO por $(grep -c '^> *types {' "$GT_D/diff_atual_candidato.txt") blocos types"
    gt_log "REFERENCIA gravada: $(tr '\n' ' ' < "$GT_D/referencia.txt")"
}
gt_confere_referencia() {
    [ -f "$GT_D/referencia.txt" ] && [ -f "$GT_CAND" ] || gt_falha "sem referencia do preparo (rode preparar)"
    local k v atual
    while read -r k v; do
        case "$k" in conf) atual="$(gt_sha "$GT_CONF")";; base) atual="$(gt_sha "$GT_BASE")";; candidato) atual="$(gt_sha "$GT_CAND")";;
                     ferramenta) atual="$(gt_sha "$GT_FERR")";; *) gt_falha "referencia com item desconhecido: $k";; esac
        [ "$atual" = "$v" ] || gt_falha "referencia divergente: $k mudou depois do preparo (preparo $v, agora $atual); nada escrito, sem reload"
    done < "$GT_D/referencia.txt"
    python "$GT_FERR" conferir "$GT_BASE" "$GT_CAND" > /dev/null || gt_falha "candidato nao confere com a base; nada escrito"
    gt_so_types "$GT_CONF" "$GT_CAND" || gt_falha "atual x candidato nao e mais so os blocos types; nada escrito"
    gt_log "referencia reconferida: atual, base, candidato e ferramenta = preparo"
}
gt_aplicar() {
    : > "$GT_D/aplicar.txt"
    gt_confere_referencia
    gt_sem_trafego
    [ ! -e "$GT_BKP" ] || gt_falha "copia de seguranca ja existe ($GT_BKP): aplicacao anterior nao revertida? nada escrito"
    gt_nginx_carrega "$GT_CONF" || gt_falha "o nginx do container nao carrega o arquivo atual; nada escrito"
    [ "$(gt_sha "$GT_CONF")" = "$(awk '$1=="conf"{print $2}' "$GT_D/referencia.txt")" ] || gt_falha "atual mudou durante a aplicacao; nada escrito"
    cp -p "$GT_CONF" "$GT_BKP"
    { echo "copia $(gt_sha "$GT_BKP")"; echo "aplicado $(gt_sha "$GT_CAND")"; } > "$GT_D/aplicado.txt"
    gt_log "copia de seguranca: $GT_BKP"; gt_log "antes:"; gt_tipos
    gt_escreve "$GT_CAND"
    gt_nginx_carrega "$GT_CAND" || { gt_escreve "$GT_BKP"; gt_falha "o container nao enxergou o candidato; anterior reescrito; sem reload"; }
    compose exec -T gateway nginx -t > "$GT_D/nginx_t_aplicar.txt" 2>&1 || { gt_escreve "$GT_BKP"; gt_falha "nginx -t recusou; anterior reescrito; sem reload"; }
    compose exec -T gateway nginx -s reload > /dev/null 2>&1 || gt_falha "reload falhou (configuracao ja escrita: reverter)"
    ${GT_DORME:-sleep} 2
    gt_log "depois do reload:"; gt_tipos
    [ "$(gt_tipo /assets/index-ZuNqN1Cn.js)" = "200 text/javascript" ] && [ "$(gt_tipo /assets/index-BapnPSoB.css)" = "200 text/css" ] \
        || gt_falha "tipos nao conferem depois do reload (reverter)"
    gt_log "APLICADO"
}
gt_reverter() {
    : > "$GT_D/reverter.txt"
    [ -f "$GT_BKP" ] && [ -f "$GT_D/aplicado.txt" ] || gt_falha "sem copia de seguranca ou sem registro da aplicacao; nada escrito"
    [ "$(gt_sha "$GT_BKP")" = "$(awk '$1=="copia"{print $2}' "$GT_D/aplicado.txt")" ] || gt_falha "copia de seguranca mudou desde a aplicacao; nada escrito"
    [ "$(gt_sha "$GT_CONF")" = "$(awk '$1=="aplicado"{print $2}' "$GT_D/aplicado.txt")" ] || gt_falha "o atual nao e o candidato aplicado; nada escrito"
    gt_sem_trafego
    gt_escreve "$GT_BKP"
    gt_nginx_carrega "$GT_BKP" || gt_falha "o container nao enxergou o arquivo revertido; sem reload"
    compose exec -T gateway nginx -t > "$GT_D/nginx_t_reverter.txt" 2>&1 || gt_falha "nginx -t recusou o arquivo anterior; sem reload"
    compose exec -T gateway nginx -s reload > /dev/null 2>&1 || gt_falha "reload da reversao falhou"
    ${GT_DORME:-sleep} 2
    gt_log "depois da reversao:"; gt_tipos
    [ "$(gt_tipo /assets/index-ZuNqN1Cn.js)" = "200 text/plain" ] || gt_falha "comportamento anterior nao voltou"
    mv "$GT_BKP" "$GT_D/nginx.conf.revertido-de"; gt_log "REVERTIDO (copia de seguranca movida para a evidencia)"
}
gt_conferir() {
    : > "$GT_D/conferir.txt"
    gt_log "sha256 local $(gt_sha "$GT_CONF"); nginx -T do container = local: $(gt_nginx_carrega "$GT_CONF" && echo sim || echo NAO)"
    gt_tipos
}

[ "${GT_SO_FUNCOES:-}" = 1 ] && return 0

set -euo pipefail
GT_ACAO="${1:?preparar|aplicar|reverter|conferir}"; GT_D="${2:?dir}"; mkdir -p "$GT_D"
GT_OPS="$(pwd)"; [ -f "$GT_OPS/homologacao.sh" ] || { echo "rode de ops/homologacao"; exit 2; }
GT_E="$GT_OPS/saidas/ensaio-frontend"; gt_caminhos
set -a; . "$GT_E/ambiente.env"; set +a
GT_CF="-f $(cygpath -m "$GT_OPS/compose.homolog.yml")"; for x in $HOMOLOG_COMPOSE_EXTRA; do GT_CF="$GT_CF -f $(cygpath -m "$GT_OPS/$x")"; done
compose() { MSYS_NO_PATHCONV=1 docker compose $GT_CF "$@"; }
gt_tipo() {
    curl -s --ssl-no-revoke --cacert "$HOMOLOG_SEGREDOS_HOST/tls/ca.crt" --resolve "$HOMOLOG_HOST:$HOMOLOG_PORTA:127.0.0.1" \
        -o /dev/null -w '%{http_code} %{content_type}' "https://$HOMOLOG_HOST:$HOMOLOG_PORTA$1"
}
. "$GT_OPS/lib/selecao_gateway.sh"
case "$GT_ACAO" in
    preparar) gt_preparar ;; aplicar) gt_aplicar ;; reverter) gt_reverter ;; conferir) gt_conferir ;;
    *) echo "acao invalida"; exit 2 ;;
esac
