#!/usr/bin/env bash
# Prova da atualizacao do frontend do ensaio (F6a-V-T): lib/frontend_atualizar.sh, a MESMA usada contra o ambiente, com
# COMPOSE CONTROLADO (ps, logs, up --force-recreate e "nginx -T" simulados sobre arquivos), inspecao e HTTP simulados a
# partir da pasta "montada" e frontend_preparar substituido por uma copia de build ficticio. Ambiente FALSO em diretorio
# temporario; nenhum container, rede, ambiente ou build real.
# Correcao da F6a-V-E (STOP da V1): o HTTP simulado devolve o sha256 dos BYTES servidos (como o fa_http real); builds
# com index CRLF (secao 6) e a retomada do aplicado sem repetir o aplicar (secao 7).
#
# Uso: bash testes/test_frontend_atualizar.sh [dir_de_saida]   (codigo 0 = todas as provas OK)
set -uo pipefail
OPS="$(cd "$(dirname "$0")/.." && pwd)"
SAIDA="${1:-}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP:?}"' EXIT
falhas=0
ok() { echo "OK    $*"; }
nok() { echo "FALHA $*"; falhas=$((falhas + 1)); }
FA_SO_FUNCOES=1 . "$OPS/lib/frontend_atualizar.sh"
. "$OPS/lib/selecao_gateway.sh"
FA_OPS="$OPS"; FA_DORME=true
CW=7851e204ab262c2a276517c23462d119524fdbd4; CL=c4c479f55c96f5c3fc3e202e08ba71a01f058594

# ---- builds ficticios: o "anterior" (montado hoje) e o "novo" (o que frontend_preparar produziria)
mkdir -p "$TMP/fx/antigo/assets" "$TMP/fx/novo/assets"
printf '<html>antigo</html>' > "$TMP/fx/antigo/index.html"; printf 'velho()' > "$TMP/fx/antigo/assets/index-ZuNqN1Cn.js"
printf '.velho{}' > "$TMP/fx/antigo/assets/index-BapnPSoB.css"; printf '<svg/>' > "$TMP/fx/antigo/favicon.svg"
printf '<html>novo</html>' > "$TMP/fx/novo/index.html"; printf 'novo()' > "$TMP/fx/novo/assets/index-C8qHwIu-.js"
printf '.novo{}' > "$TMP/fx/novo/assets/index-wqRFJn0t.css"; printf '<svg/>' > "$TMP/fx/novo/favicon.svg"; printf 'ico' > "$TMP/fx/novo/favicon.ico"
fa_lista "$TMP/fx/novo" > "$TMP/fx/esperado.txt"
# o mesmo par com o index em CRLF (como o build real do web com w/crlf): os bytes servidos tem os CR
mkdir -p "$TMP/fxcrlf"; cp -r "$TMP/fx/antigo" "$TMP/fx/novo" "$TMP/fxcrlf/"
printf '<!doctype html>\r\n<html>antigo</html>\r\n' > "$TMP/fxcrlf/antigo/index.html"
printf '<!doctype html>\r\n<html>novo</html>\r\n' > "$TMP/fxcrlf/novo/index.html"
fa_lista "$TMP/fxcrlf/novo" > "$TMP/fxcrlf/esperado.txt"
FX="$TMP/fx"

frontend_preparar() {  # substituto: "compila" copiando o build ficticio (o real e provado por test_frontend_ferramentas/K13)
    [ ! -e "$4" ] || { echo "destino do build ja existe"; return 1; }
    [ -f "$T/build_diferente" ] && { cp -r "$FX/novo" "$4"; printf 'outro()' > "$4/assets/index-C8qHwIu-.js"; } || cp -r "$FX/novo" "$4"
    { echo "web=$2"; echo "lib=$3"; fa_lista "$4"; } > "$5"
}

# ---- compose, inspecao e HTTP controlados; estado em $T
compose() {
    echo "compose $*" >> "$T/chamadas.txt"
    case "$1 ${2:-} ${3:-} ${4:-} ${5:-}" in
        "ps -q --status running gateway") [ -f "$T/ps_falha" ] && return 1; [ -f "$T/parado" ] || cat "$T/gw_id"; return 0 ;;
        "logs "*) [ -f "$T/logs_falha" ] && { echo "Error: simulado" >&2; return 1; }
                  [ -f "$T/recente" ] && echo "gateway-1  | $(date -u +%Y-%m-%dT%H:%M:%S).000Z 10.252.95.1 \"GET /api/v1/csrf\" 200"; return 0 ;;
        "up -d --no-deps --force-recreate gateway") echo x >> "$T/ups"
                  [ -f "$T/up_falha" ] && { echo "Error: simulado" >&2; return 1; }
                  echo "gw-$(wc -l < "$T/ups" | tr -d ' ')" > "$T/gw_id"; date -u +%Y-%m-%dT%H:%M:%S.%NZ > "$T/gw_ini"
                  if [ -f "$T/monta_errado" ]; then echo "$FA_E/frontend-dist" > "$T/montado"; else fa_dir "$HOMOLOG_FRONTEND_DIST" > "$T/montado"; fi
                  [ -f "$T/outro_reinicia" ] && date -u +%s%N > "$T/ini_platform"
                  return 0 ;;
        "exec -T gateway nginx -T") echo "# configuration file /etc/nginx/nginx.conf:"; cat "$FA_CONF"; echo; return 0 ;;
    esac
    echo "compose inesperado: $*" >&2; return 1
}
fa_inspect() {
    case "$1" in
        gateway) echo "$(cat "$T/gw_id") sha256:img1 $(cat "$T/gw_ini") running $(cat "$T/montado") false /cfg/nginx.conf" ;;
        *) echo "id-$1 sha256:img-$1 $(cat "$T/ini_$1") running - - -" ;;
    esac
}
fa_http() {  # como o real: sha256 dos BYTES do corpo; index_divergente/serve_sem_cr simulam outro conteudo servido
    local m p sh; m="$(cat "$T/montado")"; p="$1"; echo "http $p" >> "$T/chamadas.txt"
    case "$p" in
        /o/*) sh="$(sha256sum < "$m/index.html" | cut -c1-64)"
              [ -f "$T/index_divergente" ] && sh="$(printf '<html>outro</html>' | sha256sum | cut -c1-64)"
              [ -f "$T/serve_sem_cr" ] && sh="$(tr -d '\r' < "$m/index.html" | sha256sum | cut -c1-64)"
              [ -f "$T/sem_nostore" ] && echo "200 text/html - $sh" || echo "200 text/html no-store $sh" ;;
        /api/v1/csrf) echo "200 application/json - x" ;;
        /portal/*) echo "401 application/json - x" ;;
        *) if [ -f "$T/antigo_servido" ] && [[ "$p" == /assets/* ]] && [ -f "$FA_E/frontend-dist$p" ]; then echo "200 $(fa_tipo_esperado "$p") - x"
           elif [ -f "$m$p" ]; then
               t="$(fa_tipo_esperado "$p")"; [ -f "$T/tipo_errado" ] && [[ "$p" == *.js ]] && t=text/plain
               echo "200 $t - $(sha256sum < "$m$p" | cut -c1-64)"
           else echo "404 text/html - x"; fi ;;
    esac
}

novo() {  # cenario novo: ambiente falso limpo (build anterior montado); com $2=sem-preparo, sem o preparo
    T="$TMP/$1"; FA_E="$T/amb"; FA_D="$T/evid"; mkdir -p "$FA_E/config" "$FA_D"; fa_caminhos
    cp -r "$FX/antigo" "$FA_E/frontend-dist"; echo "# nginx do ambiente" > "$FA_CONF"
    { echo "HOMOLOG_ID=ensaio-frontend"; echo "HOMOLOG_PORTA=55741"; echo "HOMOLOG_FRONTEND_DIST=\"$FA_E/frontend-dist\""
      echo "HOMOLOG_FRONTEND_COMMIT=eaca33f64eb4713f6761bc7775c837fa0a62d2f9"; echo "HOMOLOG_FRONTEND_LIB_COMMIT=bd6420c9d39814e47de8447421c7e8b0d19d9b88"
      echo "HOMOLOG_CRIADO_EM=2026-10-08T14:34:45Z"; } > "$FA_ENV"
    cp "$FA_ENV" "$T/env_original"
    echo gw-0 > "$T/gw_id"; echo 2026-10-09T01:10:00.000Z > "$T/gw_ini"; echo "$FA_E/frontend-dist" > "$T/montado"
    for s in platform portal postgres redis; do echo "2026-10-09T01:10:0$((RANDOM % 9)).000Z" > "$T/ini_$s"; done
    : > "$T/ups"; : > "$T/chamadas.txt"
    [ "${2:-}" = sem-preparo ] || ( FA_ACAO=preparar; fa_preparar "$TMP/web" "$CW" "$CL" "$FX/esperado.txt" ) > "$T/preparar.out" 2>&1
}
roda() { ( FA_ACAO="$1"; set -a; . "$FA_ENV"; set +a; "fa_$1" ) > "$T/$1.out" 2>&1; echo $?; }
ups() { wc -l < "$T/ups" | tr -d ' '; }
guarda() { if [ -n "$SAIDA" ]; then mkdir -p "$SAIDA/$1"; cp -r "$T/evid" "$T"/*.out "$SAIDA/$1/" 2>/dev/null; fi; }
envsha() { fa_sha "$FA_ENV"; }

echo "== 1. preparo"
novo p01; guarda p01
[ -d "$FA_E/frontend-dist-7851e20" ] && diff -q <(fa_lista "$FA_E/frontend-dist-7851e20") "$TMP/fx/esperado.txt" > /dev/null \
    && [ "$(awk '{print $1}' "$FA_REF" | tr '\n' ' ')" = "env dist_atual dist_novo nginx compose ferramenta containers pasta_atual pasta_nova commit_web commit_lib imagem_gateway origem_nginx " ] \
    && cmp -s "$FA_ENV" "$T/env_original" && [ "$(ups)" = 0 ] \
    && ok "P01 preparo: pasta nova = registro aprovado; referencia completa; ambiente.env intacto; nenhum up" || nok "P01 preparo ($(tail -2 "$T/preparar.out"))"
novo p02 sem-preparo; : > "$T/build_diferente"; c=$( ( FA_ACAO=preparar; fa_preparar "$TMP/web" "$CW" "$CL" "$TMP/fx/esperado.txt" ) > "$T/preparar.out" 2>&1; echo $?); guarda p02
[ "$c" != 0 ] && [ ! -e "$FA_E/frontend-dist-7851e20" ] && grep -q "build do par DIFERENTE do registro aprovado" "$T/preparar.out" \
    && ok "P02 build diferente do aprovado: recusado, pasta nova NAO criada" || nok "P02 ($c)"
novo p03 sem-preparo; mkdir -p "$FA_E/frontend-dist-7851e20"; c=$( ( FA_ACAO=preparar; fa_preparar "$TMP/web" "$CW" "$CL" "$TMP/fx/esperado.txt" ) > "$T/preparar.out" 2>&1; echo $?); guarda p03
[ "$c" != 0 ] && grep -q "imutavel, nao se sobrescreve" "$T/preparar.out" && ok "P03 pasta nova ja existente: recusado (imutavel)" || nok "P03 ($c)"

echo "== 2. coleta indisponivel ou trafego recente: nada escrito, gateway nao recriado"
for cen in logs_falha ps_falha parado recente; do
    novo "a-$cen"; antes="$(envsha)"; : > "$T/$cen"; c=$(roda aplicar); guarda "a-$cen"
    # a falha da COLETA e relatada como tal (nao vira contagem); trafego recente e "NAO atendida"
    msg="pre-condicao NAO conferida: falha na coleta"; [ "$cen" = recente ] && msg="pre-condicao NAO atendida"
    [ "$c" != 0 ] && [ "$(envsha)" = "$antes" ] && [ "$(ups)" = 0 ] && [ ! -e "$FA_BKP" ] && grep -q "$msg" "$T/aplicar.out" \
        && ok "A-$cen: recusado; ambiente.env intacto, 0 up, sem copia de seguranca" || nok "A-$cen (codigo $c; $(tail -1 "$T/aplicar.out"))"
done
novo a-arquivo-anterior; : > "$FA_D/aplicar.pre_condicao_gateway.txt"; : > "$T/logs_falha"; c=$(roda aplicar); guarda a-arquivo-anterior
[ "$c" != 0 ] && [ "$(ups)" = 0 ] && grep -q "falha na coleta" "$T/aplicar.out" && ok "A-arquivo-anterior: coleta falha com arquivo vazio anterior -> recusado pela coleta" || nok "A-arquivo-anterior ($c)"

echo "== 3. referencia divergente: recusa ANTES de qualquer coleta, sem escrita nem recriacao"
for alvo in env dist_atual dist_novo nginx containers; do
    novo "r-$alvo"
    case $alvo in
        env) echo "# mudou" >> "$FA_ENV";; dist_atual) echo mudou >> "$FA_E/frontend-dist/index.html";;
        dist_novo) echo mudou >> "$FA_E/frontend-dist-7851e20/index.html";; nginx) echo "# mudou" >> "$FA_CONF";;
        containers) date -u +%s%N > "$T/ini_postgres";;
    esac
    antes="$(envsha)"; c=$(roda aplicar); guarda "r-$alvo"
    [ "$c" != 0 ] && [ "$(envsha)" = "$antes" ] && [ "$(ups)" = 0 ] && grep -q "referencia divergente: $alvo mudou" "$T/aplicar.out" \
        && ! grep -q '^compose logs' "$T/chamadas.txt" && ok "R-$alvo: recusado antes de qualquer coleta; nada escrito, 0 up" || nok "R-$alvo (codigo $c; $(tail -1 "$T/aplicar.out"))"
done
novo r-ferramenta; sed -i 's/^ferramenta .*/ferramenta 0000000000000000000000000000000000000000000000000000000000000000/' "$FA_REF"; c=$(roda aplicar); guarda r-ferramenta
[ "$c" != 0 ] && [ "$(ups)" = 0 ] && grep -q "referencia divergente: ferramenta mudou" "$T/aplicar.out" && ok "R-ferramenta: ferramenta diferente da do preparo: recusado" || nok "R-ferramenta ($c)"
novo r-sem-preparo sem-preparo; c=$(roda aplicar); guarda r-sem-preparo
[ "$c" != 0 ] && [ "$(ups)" = 0 ] && grep -q "sem referencia do preparo" "$T/aplicar.out" && ok "R-sem-preparo: recusado" || nok "R-sem-preparo ($c)"
novo r-copia; : > "$FA_BKP"; antes="$(envsha)"; c=$(roda aplicar); guarda r-copia
[ "$c" != 0 ] && [ "$(envsha)" = "$antes" ] && [ "$(ups)" = 0 ] && grep -q "copia de seguranca ja existe" "$T/aplicar.out" && ok "R-copia: copia anterior (aplicacao nao revertida) -> recusado" || nok "R-copia ($c)"

echo "== 4. falhas depois da escrita: PARA com 'rode reverter' (sem nova tentativa); a reversao restaura"
novo f-up; : > "$T/up_falha"; c=$(roda aplicar)
[ "$c" != 0 ] && [ "$(ups)" = 1 ] && grep -q "compose up do gateway falhou.*rode reverter" "$T/aplicar.out" && [ -f "$FA_BKP" ] \
    && ok "F-up: compose up falha -> 1 tentativa so, PARA pedindo reverter" || nok "F-up ($c; $(tail -1 "$T/aplicar.out"))"
rm -f "$T/up_falha"; c=$(roda reverter); guarda f-up
[ "$c" = 0 ] && cmp -s "$FA_ENV" "$T/env_original" && [ "$(cat "$T/montado")" = "$FA_E/frontend-dist" ] && [ ! -e "$FA_BKP" ] \
    && ok "F-up reverter: ambiente.env original, build anterior montado e servido" || nok "F-up reverter ($c; $(tail -2 "$T/reverter.out"))"
for cen in monta_errado tipo_errado outro_reinicia sem_nostore; do
    novo "f-$cen"; : > "$T/$cen"; c=$(roda aplicar); guarda "f-$cen"
    [ "$c" != 0 ] && [ "$(ups)" = 1 ] && grep -q "rode reverter" "$T/aplicar.out" && ! grep -q "^APLICADO" "$T/aplicar.out" \
        && ok "F-$cen: conferencia depois da recriacao reprova -> PARA pedindo reverter (1 up)" || nok "F-$cen ($c; $(tail -1 "$T/aplicar.out"))"
done

echo "== 5. caminho valido, conferencia e reversao"
novo v01; c=$(roda aplicar)
n="$(diff "$T/env_original" "$FA_ENV" | grep -c '^>')"
[ "$c" = 0 ] && [ "$n" = 3 ] && [ "$(ups)" = 1 ] && [ "$(cat "$T/montado")" = "$FA_E/frontend-dist-7851e20" ] && cmp -s "$FA_BKP" "$T/env_original" \
    && grep -q "^APLICADO" "$T/aplicar.out" && grep -q "/assets/index-ZuNqN1Cn.js (so do outro build) -> 404" "$T/aplicar.out" \
    && grep -q "/assets/index-C8qHwIu-.js -> 200 text/javascript" "$T/aplicar.out" && grep -q "platform, portal, postgres e redis: mesmo id e mesmo inicio" "$T/aplicar.out" \
    && cmp -s "$FA_E/frontend-dist/index.html" "$TMP/fx/antigo/index.html" \
    && ok "V01 aplicar: 3 linhas do ambiente.env, 1 up, pasta nova montada e servida, assets antigos 404, outros intactos, pasta anterior intocada" \
    || nok "V01 aplicar ($c; linhas $n; $(tail -2 "$T/aplicar.out"))"
c=$(roda conferir)
[ "$c" = 0 ] && grep -q "ambiente.env aponta para frontend-dist-7851e20" "$T/conferir.out" && ok "V02 conferir: pasta nova apontada e servida" || nok "V02 conferir ($c)"
c=$(roda reverter); guarda v01
[ "$c" = 0 ] && cmp -s "$FA_ENV" "$T/env_original" && [ "$(ups)" = 2 ] && [ "$(cat "$T/montado")" = "$FA_E/frontend-dist" ] && [ ! -e "$FA_BKP" ] \
    && [ -f "$FA_D/ambiente.env.revertido-de" ] && grep -q "/assets/index-C8qHwIu-.js (so do outro build) -> 404" "$T/reverter.out" \
    && ok "V03 reverter: ambiente.env original, 2o up, build anterior servido, assets novos 404, copia na evidencia" || nok "V03 reverter ($c; $(tail -2 "$T/reverter.out"))"
novo v04; roda aplicar > /dev/null; echo "# mexido" >> "$FA_ENV"; antes="$(envsha)"; c=$(roda reverter); guarda v04
[ "$c" != 0 ] && [ "$(envsha)" = "$antes" ] && [ "$(ups)" = 1 ] && grep -q "ambiente.env nao e o aplicado" "$T/reverter.out" \
    && ok "V04 reverter recusa se o ambiente.env nao e o aplicado (sem escrita, sem up)" || nok "V04 ($c)"
novo v05; roda aplicar > /dev/null; : > "$T/recente"; antes="$(envsha)"; c=$(roda reverter); guarda v05
[ "$c" != 0 ] && [ "$(envsha)" = "$antes" ] && [ "$(ups)" = 1 ] && ok "V05 reverter com trafego recente: recusado sem escrita nem up" || nok "V05 ($c)"

echo "== 6. index em CRLF: o servido e comparado pelos BYTES exatos (aplicar, conferir, reverter); controle LF; divergente recusado"
FX="$TMP/fxcrlf"
novo c01; c=$(roda aplicar); guarda c01
[ "$c" = 0 ] && grep -q "^APLICADO" "$T/aplicar.out" && grep -q "URL direta de tela -> 200 text/html no-store, index de frontend-dist-7851e20" "$T/aplicar.out" \
    && grep -q "(so do outro build) -> 404" "$T/aplicar.out" && [ "$(ups)" = 1 ] \
    && ok "C01 aplicar com index CRLF: servido = bytes do build (com CR) -> APLICADO" || nok "C01 aplicar CRLF ($c; $(tail -2 "$T/aplicar.out"))"
c=$(roda conferir)
[ "$c" = 0 ] && grep -q "URL direta de tela -> 200 text/html no-store, index de frontend-dist-7851e20" "$T/conferir.out" \
    && ok "C02 conferir com index CRLF: pasta nova servida" || nok "C02 conferir CRLF ($c; $(tail -1 "$T/conferir.out"))"
c=$(roda reverter); guarda c01
[ "$c" = 0 ] && cmp -s "$FA_ENV" "$T/env_original" && [ "$(ups)" = 2 ] && grep -q "URL direta de tela -> 200 text/html no-store, index de frontend-dist$" "$T/reverter.out" \
    && grep -q "^REVERTIDO" "$T/reverter.out" && ok "C03 reverter com index CRLF: build anterior (CRLF) servido -> REVERTIDO" || nok "C03 reverter CRLF ($c; $(tail -2 "$T/reverter.out"))"
novo c04; : > "$T/serve_sem_cr"; c=$(roda aplicar); guarda c04
[ "$c" != 0 ] && [ "$(ups)" = 1 ] && grep -q "URL direta de tela -> .* (esperado 200 text/html no-store com o index de frontend-dist-7851e20)" "$T/aplicar.out" \
    && grep -q "rode reverter" "$T/aplicar.out" && ! grep -q "^APLICADO" "$T/aplicar.out" \
    && ok "C04 servido SEM os CR de um index CRLF (bytes diferentes): recusado, pede reverter" || nok "C04 ($c; $(tail -1 "$T/aplicar.out"))"
for x in crlf lf; do
    [ $x = crlf ] && FX="$TMP/fxcrlf" || FX="$TMP/fx"
    novo "c05-$x"; : > "$T/index_divergente"; c=$(roda aplicar); guarda "c05-$x"
    [ "$c" != 0 ] && [ "$(ups)" = 1 ] && grep -q "URL direta de tela -> .*(esperado" "$T/aplicar.out" && ! grep -q "^APLICADO" "$T/aplicar.out" \
        && ok "C05-$x index servido com conteudo divergente: aplicar recusa" || nok "C05-$x ($c)"
    rm -f "$T/index_divergente"; roda conferir > /dev/null; : > "$T/index_divergente"; c=$(roda conferir)
    [ "$c" != 0 ] && grep -q "nao e o servido" "$T/conferir.out" && ok "C06-$x index servido com conteudo divergente: conferir recusa" || nok "C06-$x ($c)"
done
FX="$TMP/fx"
novo c07; c=$(roda aplicar); c2=$(roda conferir); c3=$(roda reverter); guarda c07
[ "$c$c2$c3" = 000 ] && ! grep -q $'\r' "$FA_E/frontend-dist-7851e20/index.html" \
    && ok "C07 controle LF: aplicar, conferir e reverter OK com index sem CR" || nok "C07 controle LF ($c $c2 $c3)"

echo "== 7. retomar: conferencias do aplicado SEM repetir o aplicar (sem escrita, sem up)"
for x in crlf lf; do
    [ $x = crlf ] && FX="$TMP/fxcrlf" || FX="$TMP/fx"
    # o STOP real: o aplicar recriou o gateway e parou na conferencia do servido; depois, a retomada
    novo "t01-$x"; : > "$T/index_divergente"; roda aplicar > /dev/null; rm -f "$T/index_divergente"
    env_ap="$(envsha)"; u="$(ups)"; c=$(roda retomar); guarda "t01-$x"
    [ "$c" = 0 ] && [ "$(ups)" = "$u" ] && [ "$(envsha)" = "$env_ap" ] && [ -f "$FA_BKP" ] && grep -q "^RETOMADA CONFERIDA" "$T/retomar.out" \
        && grep -q "/assets/index-ZuNqN1Cn.js (so do outro build) -> 404" "$T/retomar.out" && grep -q "/assets/index-BapnPSoB.css (so do outro build) -> 404" "$T/retomar.out" \
        && grep -q "/assets/index-C8qHwIu-.js -> 200 text/javascript" "$T/retomar.out" && grep -q "platform, portal, postgres e redis: mesmo id e mesmo inicio" "$T/retomar.out" \
        && grep -q "/api/v1/csrf -> 200; /portal/api sem sessao -> 401" "$T/retomar.out" && grep -q "pre-condicao conferida" "$T/retomar.out" \
        && ! grep -q "force-recreate" <(sed -n '/retomar/,$p' "$T/chamadas.txt") \
        && ok "T01-$x retomar depois do STOP do aplicar: todas as conferencias (assets antigos 404 inclusive), 0 up, ambiente.env intacto" \
        || nok "T01-$x ($c; $(tail -2 "$T/retomar.out"))"
done
FX="$TMP/fxcrlf"
novo t02 sem-preparo; c=$(roda retomar); guarda t02
[ "$c" != 0 ] && grep -q "nada a retomar" "$T/retomar.out" && ! grep -q '^http' "$T/chamadas.txt" && ok "T02 sem preparo nem aplicacao: nada a retomar, nenhuma requisicao" || nok "T02 ($c)"
novo t03; c=$(roda retomar); guarda t03
[ "$c" != 0 ] && grep -q "nada a retomar" "$T/retomar.out" && [ "$(ups)" = 0 ] && ok "T03 preparo sem aplicacao: nada a retomar" || nok "T03 ($c)"
novo t04; roda aplicar > /dev/null; echo "# mexido" >> "$FA_ENV"; : > "$T/chamadas.txt"; c=$(roda retomar); guarda t04
[ "$c" != 0 ] && grep -q "ambiente.env nao e o aplicado" "$T/retomar.out" && ! grep -q '^http\|^compose logs' "$T/chamadas.txt" \
    && ok "T04 ambiente.env diferente do aplicado: recusado antes de coleta e de requisicao" || nok "T04 ($c)"
novo t05; roda aplicar > /dev/null; echo "mudou" >> "$FA_E/frontend-dist-7851e20/favicon.svg"; : > "$T/chamadas.txt"; c=$(roda retomar); guarda t05
[ "$c" != 0 ] && grep -q "referencia divergente: dist_novo mudou" "$T/retomar.out" && ! grep -q '^http' "$T/chamadas.txt" && ok "T05 pasta nova alterada depois do preparo: recusado sem requisicao" || nok "T05 ($c)"
novo t06; roda aplicar > /dev/null; echo gw-9 > "$T/gw_id"; : > "$T/chamadas.txt"; c=$(roda retomar); guarda t06
[ "$c" != 0 ] && grep -q "o gateway nao e o recriado pelo aplicar" "$T/retomar.out" && ! grep -q '^http' "$T/chamadas.txt" && ok "T06 gateway recriado de novo depois do aplicar: recusado" || nok "T06 ($c)"
novo t07; roda aplicar > /dev/null; : > "$T/recente"; : > "$T/chamadas.txt"; c=$(roda retomar); guarda t07
[ "$c" != 0 ] && grep -q "pre-condicao NAO atendida" "$T/retomar.out" && ! grep -q '^http' "$T/chamadas.txt" && ok "T07 trafego recente: recusado sem requisicao" || nok "T07 ($c)"
novo t08; roda aplicar > /dev/null; : > "$T/antigo_servido"; c=$(roda retomar); guarda t08
[ "$c" != 0 ] && grep -q "(so do outro build) -> 200" "$T/retomar.out" && ! grep -q "^RETOMADA" "$T/retomar.out" && ok "T08 asset do build anterior ainda servido: recusado" || nok "T08 ($c; $(tail -1 "$T/retomar.out"))"
novo t09; roda aplicar > /dev/null; date -u +%s%N > "$T/ini_redis"; c=$(roda retomar); guarda t09
[ "$c" != 0 ] && grep -q "redis mudou" "$T/retomar.out" && ok "T09 outro container reiniciado depois do aplicar: recusado" || nok "T09 ($c)"
novo t10; roda aplicar > /dev/null; : > "$T/serve_sem_cr"; c=$(roda retomar); guarda t10
[ "$c" != 0 ] && grep -q "build novo nao servido como esperado" "$T/retomar.out" && ok "T10 index servido sem os CR: recusado (bytes exatos)" || nok "T10 ($c)"
novo t11; roda aplicar > /dev/null; u="$(ups)"; for k in 1 2 3; do roda retomar > /dev/null; done; c=$(roda retomar); guarda t11
[ "$c" = 0 ] && [ "$(ups)" = "$u" ] && [ "$(grep -c 'force-recreate' "$T/chamadas.txt")" = 1 ] && ok "T11 retomar repetido: so conferencia, nenhum up alem do aplicar" || nok "T11 ($c)"
FX="$TMP/fx"

echo
[ "$falhas" = 0 ] && echo "RESULTADO: atualizacao do frontend OK" || echo "RESULTADO: $falhas falha(s)"
exit $(( falhas > 0 ))
