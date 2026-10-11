#!/usr/bin/env bash
# Atualizacao do build da aplicacao web servido pelo gateway do ensaio-frontend (F6a-V-T, decisao D-V-1 (a)): uma PASTA
# NOVA e IMUTAVEL (saidas/ensaio-frontend/frontend-dist-<commit7>) e a recriacao SO do container gateway, que monta a
# pasta em /spa (somente leitura; compose.frontend.yml, HOMOLOG_FRONTEND_DIST). A pasta do build anterior NUNCA e
# alterada. Platform, Portal, banco, Redis, segredos, certificados e config/nginx.conf: intocados.
#
# Uso (de ops/homologacao):
#   bash lib/frontend_atualizar.sh preparar <dir_evid> <web> <commit_web> <commit_lib> <esperado.txt>
#   bash lib/frontend_atualizar.sh aplicar|reverter|conferir|retomar <dir_evid>
#   preparar  build do par (frontend_preparar de lib/frontend_dist.sh) para <dir>/candidato; sha256 de cada arquivo =
#             <esperado.txt> (linhas "sha256 *./caminho", o registro aprovado) -> senao PARA; copia para a pasta nova
#             (que NAO pode existir) e reconfere; grava a REFERENCIA (sha256 de ambiente.env, do build atual, do novo,
#             de config/nginx.conf, do compose.frontend.yml, da ferramenta e id + inicio de cada container)
#   aplicar   1. REFERENCIA reconferida (inclusive containers: mesmo id e mesmo inicio) -> senao PARA sem escrita
#             2. PRE-CONDICAO: coleta do gateway VERIFICADA (coleta_gateway de lib/selecao_gateway.sh) e 0 requisicoes
#                nos 31 s -> senao PARA sem escrita
#             3. sem copia de seguranca anterior; copia de ambiente.env; ambiente.env novo com SO as 3 linhas do
#                frontend trocadas (diff conferido); aplicado.txt gravado ANTES de recriar
#             4. recria SO o gateway (compose up -d --no-deps --force-recreate gateway)
#             5. confere: gateway com id NOVO, mesma imagem, /spa = pasta nova (somente leitura), nginx.conf montado
#                igual e carregado (nginx -T); os outros containers com o MESMO id e o MESMO inicio; index servido = build
#                novo com no-store; cada arquivo do build com o tipo esperado e o conteudo do build; assets do build
#                anterior -> 404; /api e /portal/api respondendo (200 e 401)
#             Falha depois da escrita (passo 3 em diante): PARA com "rode reverter" (sem nova tentativa automatica)
#   reverter  aplicado.txt reconferido (ambiente.env = o aplicado; copia = a gravada); mesma pre-condicao de trafego;
#             ambiente.env anterior de volta; recria SO o gateway; /spa = pasta anterior e o build anterior servido;
#             copia movida para a evidencia
#   conferir  so leitura: qual pasta esta montada, id e inicio dos containers, tipos e conteudos servidos
#   retomar   (F6a-V-E, depois de um STOP do aplicar na conferencia do servido) SO conferencia, sem escrita e sem recriar:
#             referencia, copia e aplicado.txt reconferidos (ambiente.env = o APLICADO); o gateway e o MESMO recriado
#             pelo aplicar; mesma pre-condicao de trafego; e TODAS as conferencias do passo 5 do aplicar, inclusive os
#             assets do build anterior -> 404. Nao repete o aplicar.
# Conteudo servido: comparado pelo sha256 dos BYTES exatos (index inclusive; um build com CRLF e servido com CRLF).
# fa_sha (sem CR) fica so para as referencias de configuracao (ambiente.env, nginx.conf, compose, copia, aplicado).
# Qualquer conferencia que falhe: PARA (codigo 1), sem nova tentativa.
#
# Para testes: "source" com FA_SO_FUNCOES=1 define so as funcoes; o chamador fornece compose (docker compose do
# ambiente), fa_inspect <servico> ("id imagem inicio estado origem_spa rw_spa origem_nginx"), fa_http <caminho>
# ("status content-type cache-control sha256"), FA_E (saidas/<id>), FA_OPS, FA_D e, opcionalmente, FA_DORME.

fa_sha() { tr -d '\r' < "$1" | sha256sum | cut -c1-64; }
fa_log() { echo "$*" | tee -a "$FA_D/$FA_ACAO.txt"; }
fa_falha() { echo "PARADO: $*" | tee -a "$FA_D/$FA_ACAO.txt" >&2; exit 1; }
fa_lista() { (cd "$1" && find . -type f | sort | xargs sha256sum) | tr -d '\r' | sed -E 's/^([0-9a-f]{64}) +\*?/\1 */'; }   # "sha *./caminho"
fa_lista_sha() { fa_lista "$1" | sha256sum | cut -c1-64; }
fa_caminhos() {
    FA_ENV="$FA_E/ambiente.env"; FA_BKP="$FA_E/ambiente.env.antes-f6av"; FA_CONF="$FA_E/config/nginx.conf"
    FA_COMP="$FA_OPS/compose.frontend.yml"; FA_REF="$FA_D/referencia.txt"; FA_APL="$FA_D/aplicado.txt"
}
fa_var() { sed -n "s/^$1=//p" "$2" | tail -1 | tr -d '"\r'; }    # valor de uma variavel do ambiente.env
fa_dir() { cygpath -u "$1" 2>/dev/null || echo "$1"; }
fa_ferramenta() { cat "$FA_OPS/lib/frontend_atualizar.sh" "$FA_OPS/lib/frontend_dist.sh" "$FA_OPS/lib/selecao_gateway.sh" | tr -d '\r' | sha256sum | cut -c1-64; }
fa_containers() {  # "servico id inicio" de cada container do ambiente, em ordem fixa
    local s
    for s in gateway platform portal postgres redis; do echo "$s $(fa_inspect "$s" | awk '{print $1, $3}')"; done
}
fa_sem_trafego() {  # pre-condicao: coleta verificada (selecao_gateway.sh) e 0 requisicoes nos 31 s
    local agora desde n
    agora="$(date -u +%s)"; desde="$(date -u -d "@$((agora - 31))" +%Y-%m-%dT%H:%M:%SZ)"
    : > "$FA_D/$FA_ACAO.pre_condicao_erro.txt"
    coleta_gateway "$desde" "$FA_D/$FA_ACAO.pre_condicao_gateway.txt" "$FA_D/$FA_ACAO.pre_condicao_erro.txt" \
        || fa_falha "pre-condicao NAO conferida: falha na coleta dos logs do gateway ($(tr '\n' ' ' < "$FA_D/$FA_ACAO.pre_condicao_erro.txt")); nada escrito, gateway nao recriado"
    n="$(wc -l < "$FA_D/$FA_ACAO.pre_condicao_gateway.txt" | tr -d ' ')"
    [ "$n" = 0 ] || fa_falha "pre-condicao NAO atendida: $n requisicao(oes) no gateway desde $desde; nada escrito, gateway nao recriado"
    fa_log "pre-condicao conferida: coleta OK e 0 requisicoes no gateway desde $desde"
}
fa_nginx_carrega() {  # 0 se "nginx -T" no container mostrar exatamente config/nginx.conf (le ate 10 s)
    local i
    for i in $(seq 1 10); do
        compose exec -T gateway nginx -T 2>/dev/null | tr -d '\r' | sed '1d;${/^$/d}' | diff -q - <(tr -d '\r' < "$FA_CONF") > /dev/null && return 0
        ${FA_DORME:-sleep} 1
    done
    return 1
}
fa_tipo_esperado() {
    case "$1" in
        *.js|*.mjs) echo text/javascript ;; *.css) echo text/css ;; *.svg) echo image/svg+xml ;;
        *.ico) echo image/x-icon ;; *.html) echo text/html ;; *) echo "" ;;
    esac
}
# confere_servido <pasta> <outra_pasta_ou_vazio>: o gateway serve <pasta> (index na URL direta de tela com no-store;
# cada arquivo com tipo e conteudo) e NAO serve os assets exclusivos de <outra_pasta>
fa_confere_servido() {
    local dist="$1" outra="$2" r st ct cc sh f t
    r="$(fa_http /o/00000000-0000-4000-8000-000000000000/execucoes)"; read -r st ct cc sh <<< "$r"
    [ "$st" = 200 ] && [ "${ct%%;*}" = text/html ] && [ "$cc" = no-store ] && [ "$sh" = "$(sha256sum < "$dist/index.html" | cut -c1-64)" ] \
        || { fa_log "  URL direta de tela -> $r (esperado 200 text/html no-store com o index de $(basename "$dist"))"; return 1; }
    fa_log "  URL direta de tela -> 200 text/html no-store, index de $(basename "$dist")"
    while read -r f; do
        f="${f#./}"; [ "$f" = index.html ] && continue
        t="$(fa_tipo_esperado "$f")"; r="$(fa_http "/$f")"; read -r st ct cc sh <<< "$r"
        [ "$st" = 200 ] && [ "${ct%%;*}" = "$t" ] && [ "$sh" = "$(sha256sum < "$dist/$f" | cut -c1-64)" ] \
            || { fa_log "  /$f -> $st $ct (esperado 200 $t com o conteudo do build)"; return 1; }
        fa_log "  /$f -> 200 $t (conteudo do build)"
    done < <(cd "$dist" && find . -type f | sort)
    if [ -n "$outra" ]; then
        while read -r f; do
            f="${f#./}"; [ -e "$dist/$f" ] && continue
            r="$(fa_http "/$f")"; [ "${r%% *}" = 404 ] || { fa_log "  /$f (so do outro build) -> $r (esperado 404)"; return 1; }
            fa_log "  /$f (so do outro build) -> 404"
        done < <(cd "$outra" && find ./assets -type f | sort)
    fi
    r="$(fa_http /api/v1/csrf)"; [ "${r%% *}" = 200 ] || { fa_log "  /api/v1/csrf -> $r"; return 1; }
    r="$(fa_http /portal/api/v1/o/00000000-0000-4000-8000-000000000000/workspaces)"; [ "${r%% *}" = 401 ] || { fa_log "  /portal/api -> $r"; return 1; }
    fa_log "  /api/v1/csrf -> 200; /portal/api sem sessao -> 401"
}
# confere_containers <pasta_esperada_em_/spa> <id_gateway_anterior>: gateway novo, os outros intactos (referencia)
fa_confere_containers() {
    local spa="$1" antigo="$2" g id img ini est osp rw ong s linha
    g="$(fa_inspect gateway)"; read -r id img ini est osp rw ong <<< "$g"
    [ "$id" != "$antigo" ] || { fa_log "  gateway NAO foi recriado (mesmo id)"; return 1; }
    [ "$est" = running ] || { fa_log "  gateway em estado $est"; return 1; }
    [ "$img" = "$(awk '$1=="imagem_gateway"{print $2}' "$FA_REF")" ] || { fa_log "  gateway com imagem diferente"; return 1; }
    [ "$(basename "${osp//\\//}")" = "$(basename "$spa")" ] && [ "$rw" = false ] || { fa_log "  /spa monta $(basename "${osp//\\//}") rw=$rw (esperado $(basename "$spa") somente leitura)"; return 1; }
    [ "$ong" = "$(awk '$1=="origem_nginx"{print $2}' "$FA_REF")" ] || { fa_log "  nginx.conf montado de outra origem"; return 1; }
    fa_nginx_carrega || { fa_log "  nginx -T do gateway novo diferente de config/nginx.conf"; return 1; }
    for s in platform portal postgres redis; do
        linha="$(fa_inspect "$s" | awk '{print $1, $3}')"
        [ "$s $linha" = "$(grep "^$s " "$FA_REF.containers")" ] || { fa_log "  $s mudou: $linha"; return 1; }
    done
    fa_log "  gateway recriado (${antigo:0:12} -> ${id:0:12}), mesma imagem, /spa = $(basename "$spa") somente leitura, nginx.conf igual e carregado"
    fa_log "  platform, portal, postgres e redis: mesmo id e mesmo inicio"
}

fa_preparar() {
    local web="$1" cw="$2" cl="$3" esp="$4" m novo
    : > "$FA_D/preparar.txt"; rm -f "$FA_REF" "$FA_REF.containers"
    [[ "$cw" =~ ^[0-9a-f]{40}$ ]] || fa_falha "commit do web deve ser o sha completo"
    [ -f "$esp" ] || fa_falha "registro esperado ausente: $esp"
    novo="$FA_E/frontend-dist-${cw:0:7}"
    [ ! -e "$novo" ] || fa_falha "a pasta nova ja existe ($novo): imutavel, nao se sobrescreve"
    [ ! -e "$FA_D/candidato" ] || fa_falha "candidato de um preparo anterior em $FA_D (use outra pasta de evidencia)"
    m="$(frontend_preparar "$web" "$cw" "$cl" "$FA_D/candidato" "$FA_D/frontend_dist_candidato.txt")" || fa_falha "build do par: $m"
    diff <(grep -E '^[0-9a-f]{64} \*\./' "$esp" | tr -d '\r') <(fa_lista "$FA_D/candidato") > "$FA_D/diff_build_esperado.txt" \
        || fa_falha "build do par DIFERENTE do registro aprovado (ver diff_build_esperado.txt); pasta nova NAO criada"
    cp -r "$FA_D/candidato" "$novo" || fa_falha "copia para a pasta nova"
    diff <(fa_lista "$FA_D/candidato") <(fa_lista "$novo") > /dev/null || fa_falha "copia na pasta nova difere do candidato"
    cp "$FA_D/frontend_dist_candidato.txt" "$FA_E/frontend_dist-${cw:0:7}.txt"
    fa_log "build do par = registro aprovado ($(grep -cE '^[0-9a-f]{64} \*\./' "$esp") arquivos); pasta nova: $(basename "$novo")"
    local atual; atual="$(fa_dir "$(fa_var HOMOLOG_FRONTEND_DIST "$FA_ENV")")"
    [ -d "$atual" ] || fa_falha "build atual nao encontrado: $atual"
    fa_containers > "$FA_REF.containers"
    local g; g="$(fa_inspect gateway)"
    { echo "env $(fa_sha "$FA_ENV")"; echo "dist_atual $(fa_lista_sha "$atual")"; echo "dist_novo $(fa_lista_sha "$novo")"
      echo "nginx $(fa_sha "$FA_CONF")"; echo "compose $(fa_sha "$FA_COMP")"; echo "ferramenta $(fa_ferramenta)"
      echo "containers $(sha256sum < "$FA_REF.containers" | cut -c1-64)"
      echo "pasta_atual $(basename "$atual")"; echo "pasta_nova $(basename "$novo")"; echo "commit_web $cw"; echo "commit_lib $cl"
      echo "imagem_gateway $(echo "$g" | awk '{print $2}')"; echo "origem_nginx $(echo "$g" | awk '{print $7}')"; } > "$FA_REF"
    fa_log "REFERENCIA gravada: $(tr '\n' ' ' < "$FA_REF")"
}
fa_confere_referencia() {
    [ -f "$FA_REF" ] && [ -f "$FA_REF.containers" ] || fa_falha "sem referencia do preparo (rode preparar)"
    local k v atual pa pn
    pa="$FA_E/$(awk '$1=="pasta_atual"{print $2}' "$FA_REF")"; pn="$FA_E/$(awk '$1=="pasta_nova"{print $2}' "$FA_REF")"
    while read -r k v; do
        case "$k" in
            env) atual="$(fa_sha "$FA_ENV")";; dist_atual) atual="$(fa_lista_sha "$pa")";; dist_novo) atual="$(fa_lista_sha "$pn")";;
            nginx) atual="$(fa_sha "$FA_CONF")";; compose) atual="$(fa_sha "$FA_COMP")";; ferramenta) atual="$(fa_ferramenta)";;
            containers) atual="$(fa_containers | sha256sum | cut -c1-64)";;
            pasta_atual|pasta_nova|commit_web|commit_lib|imagem_gateway|origem_nginx) continue;;
            *) fa_falha "referencia com item desconhecido: $k";;
        esac
        [ "$atual" = "$v" ] || fa_falha "referencia divergente: $k mudou depois do preparo (preparo $v, agora $atual); nada escrito, gateway nao recriado"
    done < "$FA_REF"
    [ "$(fa_dir "$(fa_var HOMOLOG_FRONTEND_DIST "$FA_ENV")")" = "$(fa_dir "$pa")" ] || fa_falha "ambiente.env nao aponta para o build atual da referencia"
    fa_log "referencia reconferida: ambiente.env, builds, nginx.conf, compose, ferramenta e containers = preparo"
}
fa_env_novo() {  # ambiente.env com SO as 3 linhas do frontend trocadas; diff conferido
    local pn cw cl n
    pn="$(awk '$1=="pasta_nova"{print $2}' "$FA_REF")"; cw="$(awk '$1=="commit_web"{print $2}' "$FA_REF")"; cl="$(awk '$1=="commit_lib"{print $2}' "$FA_REF")"
    local d; d="$(fa_var HOMOLOG_FRONTEND_DIST "$FA_ENV")"; d="${d%/*}/$pn"
    sed -e "s#^HOMOLOG_FRONTEND_DIST=.*#HOMOLOG_FRONTEND_DIST=\"$d\"#" -e "s#^HOMOLOG_FRONTEND_COMMIT=.*#HOMOLOG_FRONTEND_COMMIT=$cw#" \
        -e "s#^HOMOLOG_FRONTEND_LIB_COMMIT=.*#HOMOLOG_FRONTEND_LIB_COMMIT=$cl#" "$FA_BKP" > "$FA_D/ambiente.env.novo"
    n="$(diff "$FA_BKP" "$FA_D/ambiente.env.novo" | grep -c '^>')"
    [ "$n" = 3 ] && ! diff "$FA_BKP" "$FA_D/ambiente.env.novo" | grep '^[<>]' | grep -vqE '^[<>] HOMOLOG_FRONTEND_(DIST|COMMIT|LIB_COMMIT)=' \
        || return 1
}
fa_recria() { (set -a; . "$FA_ENV"; set +a; compose up -d --no-deps --force-recreate gateway) > "$FA_D/$FA_ACAO.compose_up.txt" 2>&1; }

fa_aplicar() {
    : > "$FA_D/aplicar.txt"
    fa_confere_referencia
    fa_sem_trafego
    [ ! -e "$FA_BKP" ] || fa_falha "copia de seguranca ja existe ($FA_BKP): aplicacao anterior nao revertida? nada escrito"
    local antigo pn pa; antigo="$(fa_inspect gateway | awk '{print $1}')"
    pn="$FA_E/$(awk '$1=="pasta_nova"{print $2}' "$FA_REF")"; pa="$FA_E/$(awk '$1=="pasta_atual"{print $2}' "$FA_REF")"
    [ "$(fa_sha "$FA_ENV")" = "$(awk '$1=="env"{print $2}' "$FA_REF")" ] || fa_falha "ambiente.env mudou durante a aplicacao; nada escrito"
    cp -p "$FA_ENV" "$FA_BKP"
    fa_env_novo || { rm -f "$FA_BKP"; fa_falha "ambiente.env novo difere do atual em algo alem das 3 linhas do frontend; nada escrito"; }
    { echo "copia $(fa_sha "$FA_BKP")"; echo "aplicado $(fa_sha "$FA_D/ambiente.env.novo")"; echo "gateway_anterior $antigo"; } > "$FA_APL"
    cat "$FA_D/ambiente.env.novo" > "$FA_ENV"
    fa_log "ambiente.env: SO HOMOLOG_FRONTEND_DIST, HOMOLOG_FRONTEND_COMMIT e HOMOLOG_FRONTEND_LIB_COMMIT trocados (copia: $(basename "$FA_BKP"))"
    fa_recria || fa_falha "compose up do gateway falhou (ver $FA_ACAO.compose_up.txt); ambiente.env NOVO ja escrito: rode reverter"
    ${FA_DORME:-sleep} 2
    fa_log "depois da recriacao:"
    fa_confere_containers "$pn" "$antigo" || fa_falha "containers nao conferem depois da recriacao: rode reverter"
    fa_confere_servido "$pn" "$pa" || fa_falha "build novo nao servido como esperado: rode reverter"
    fa_log "APLICADO"
}
fa_reverter() {
    : > "$FA_D/reverter.txt"
    [ -f "$FA_BKP" ] && [ -f "$FA_APL" ] && [ -f "$FA_REF" ] || fa_falha "sem copia de seguranca, sem registro da aplicacao ou sem referencia; nada escrito"
    [ "$(fa_sha "$FA_BKP")" = "$(awk '$1=="copia"{print $2}' "$FA_APL")" ] || fa_falha "copia de seguranca mudou desde a aplicacao; nada escrito"
    [ "$(fa_sha "$FA_ENV")" = "$(awk '$1=="aplicado"{print $2}' "$FA_APL")" ] || fa_falha "ambiente.env nao e o aplicado; nada escrito"
    fa_sem_trafego
    local antigo pa pn; antigo="$(fa_inspect gateway | awk '{print $1}')"
    pa="$FA_E/$(awk '$1=="pasta_atual"{print $2}' "$FA_REF")"; pn="$FA_E/$(awk '$1=="pasta_nova"{print $2}' "$FA_REF")"
    cat "$FA_BKP" > "$FA_ENV"
    fa_recria || fa_falha "compose up do gateway (reversao) falhou (ver reverter.compose_up.txt); ambiente.env anterior ja escrito"
    ${FA_DORME:-sleep} 2
    fa_log "depois da reversao:"
    fa_confere_containers "$pa" "$antigo" || fa_falha "containers nao conferem depois da reversao"
    fa_confere_servido "$pa" "$pn" || fa_falha "build anterior nao servido depois da reversao"
    mv "$FA_BKP" "$FA_D/ambiente.env.revertido-de"; fa_log "REVERTIDO (copia de seguranca movida para a evidencia)"
}
fa_retomar() {
    : > "$FA_D/retomar.txt"
    [ -f "$FA_REF" ] && [ -f "$FA_REF.containers" ] && [ -f "$FA_APL" ] && [ -f "$FA_BKP" ] \
        || fa_falha "sem referencia, sem registro da aplicacao ou sem copia de seguranca: nada a retomar"
    [ "$(fa_sha "$FA_BKP")" = "$(awk '$1=="copia"{print $2}' "$FA_APL")" ] || fa_falha "copia de seguranca mudou desde a aplicacao"
    [ "$(fa_sha "$FA_ENV")" = "$(awk '$1=="aplicado"{print $2}' "$FA_APL")" ] || fa_falha "ambiente.env nao e o aplicado"
    local k v atual pa pn antigo novo id
    pa="$FA_E/$(awk '$1=="pasta_atual"{print $2}' "$FA_REF")"; pn="$FA_E/$(awk '$1=="pasta_nova"{print $2}' "$FA_REF")"
    while read -r k v; do
        case "$k" in
            dist_atual) atual="$(fa_lista_sha "$pa")";; dist_novo) atual="$(fa_lista_sha "$pn")";;
            nginx) atual="$(fa_sha "$FA_CONF")";; compose) atual="$(fa_sha "$FA_COMP")";;
            *) continue;;
        esac
        [ "$atual" = "$v" ] || fa_falha "referencia divergente: $k mudou depois do preparo (preparo $v, agora $atual)"
    done < "$FA_REF"
    fa_log "referencia reconferida: builds, nginx.conf e compose = preparo; ambiente.env = o aplicado; copia = a gravada"
    fa_log "ferramenta: preparo $(awk '$1=="ferramenta"{print $2}' "$FA_REF"), agora $(fa_ferramenta) (a retomada nao exige a mesma)"
    antigo="$(awk '$1=="gateway_anterior"{print $2}' "$FA_APL")"
    novo="$(sed -n 's/.*gateway recriado ([^ ]* -> \([^ )]*\)).*/\1/p' "$FA_D/aplicar.txt" 2>/dev/null | tail -1)"
    [ -n "$antigo" ] && [ -n "$novo" ] || fa_falha "aplicado.txt ou aplicar.txt sem o gateway anterior ou o recriado"
    id="$(fa_inspect gateway | awk '{print $1}')"
    [ "${id:0:12}" = "$novo" ] || fa_falha "o gateway nao e o recriado pelo aplicar (aplicar ${novo}, agora ${id:0:12})"
    fa_sem_trafego
    fa_log "conferencias do aplicado (sem escrita, sem recriar):"
    fa_confere_containers "$pn" "$antigo" || fa_falha "containers nao conferem com o aplicado"
    fa_confere_servido "$pn" "$pa" || fa_falha "build novo nao servido como esperado"
    fa_log "RETOMADA CONFERIDA: aplicado completo (nada escrito, gateway nao recriado)"
}
fa_conferir() {
    : > "$FA_D/conferir.txt"
    local d; d="$(fa_dir "$(fa_var HOMOLOG_FRONTEND_DIST "$FA_ENV")")"
    fa_log "ambiente.env aponta para $(basename "$d"); containers:"; fa_containers | sed 's/^/  /' | tee -a "$FA_D/conferir.txt"
    fa_log "gateway: $(fa_inspect gateway | awk '{print "id", substr($1,1,12), "/spa", $5, "rw=" $6}')"
    fa_confere_servido "$d" "" || fa_falha "o build apontado pelo ambiente.env nao e o servido"
}

[ "${FA_SO_FUNCOES:-}" = 1 ] && return 0

set -euo pipefail
FA_ACAO="${1:?preparar|aplicar|reverter|conferir|retomar}"; FA_D="${2:?dir}"; mkdir -p "$FA_D"
FA_OPS="$(pwd)"; [ -f "$FA_OPS/homologacao.sh" ] || { echo "rode de ops/homologacao"; exit 2; }
FA_E="$FA_OPS/saidas/ensaio-frontend"; fa_caminhos
set -a; . "$FA_ENV"; set +a
FA_CF="-f $(cygpath -m "$FA_OPS/compose.homolog.yml")"; for x in $HOMOLOG_COMPOSE_EXTRA; do FA_CF="$FA_CF -f $(cygpath -m "$FA_OPS/$x")"; done
compose() { MSYS_NO_PATHCONV=1 docker compose $FA_CF "$@"; }
fa_inspect() {
    local id; id="$(compose ps -q "$1" 2>/dev/null | head -1)"
    [ -n "$id" ] || { echo "- - - ausente - - -"; return 0; }
    docker inspect "$id" | python -c "
import json, sys
c = json.load(sys.stdin)[0]
m = {x['Destination']: x for x in c.get('Mounts', [])}
spa, ng = m.get('/spa', {}), m.get('/etc/nginx/nginx.conf', {})
print(c['Id'], c['Image'], c['State']['StartedAt'], c['State']['Status'], (spa.get('Source') or '-').replace(' ', '%20'),
      str(spa.get('RW', '-')).lower(), (ng.get('Source') or '-').replace(' ', '%20'))"
}
fa_http() {  # "status content-type cache-control sha256" (cabecalho ausente = -)
    local h b st ct cc; h="$(mktemp)"; b="$(mktemp)"
    st="$(curl -s --ssl-no-revoke --cacert "$HOMOLOG_SEGREDOS_HOST/tls/ca.crt" --resolve "$HOMOLOG_HOST:$HOMOLOG_PORTA:127.0.0.1" \
        -D "$h" -o "$b" -w '%{http_code}' "https://$HOMOLOG_HOST:$HOMOLOG_PORTA$1")" || st=000
    ct="$(grep -i '^content-type:' "$h" | head -1 | cut -d: -f2- | tr -d '\r ')"; cc="$(grep -i '^cache-control:' "$h" | head -1 | cut -d: -f2- | tr -d '\r ')"
    echo "$st ${ct:--} ${cc:--} $(sha256sum < "$b" | cut -c1-64)"
    rm -f "$h" "$b"
}
. "$FA_OPS/lib/selecao_gateway.sh"
. "$FA_OPS/lib/frontend_dist.sh"
case "$FA_ACAO" in
    preparar) fa_preparar "${3:?web}" "${4:?commit_web}" "${5:?commit_lib}" "${6:?esperado}" ;;
    aplicar) fa_aplicar ;; reverter) fa_reverter ;; conferir) fa_conferir ;; retomar) fa_retomar ;;
    *) echo "acao invalida"; exit 2 ;;
esac
