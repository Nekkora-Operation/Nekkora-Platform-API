#!/usr/bin/env bash
# H01-H09 (e R015) da homologacao - chamado por "homologacao.sh testar <id>" (que exporta HOMOLOG_*, compose e admin_sql).
# Cada teste grava evidencias proprias em saidas/<id>/testes-<instante>/ e uma linha no resumo: teste | resultado |
# execucao (agora, evidencia anterior reutilizada, pendente) | detalhe. Valores secretos (sinteticos e do ambiente)
# ficam SO em HOMOLOG_SEGREDOS/teste (fora do Git); a H08 confere que nenhum deles aparece nas evidencias nem nos logs.
set -uo pipefail

: "${HOMOLOG_ID:?}" "${HOMOLOG_SEGREDOS_HOST:?}"
HOMOLOG_REDE_INTERNA="${HOMOLOG_REDE_INTERNA:-10.252.10}"   # prefixo da rede interna (padrao do desenho anterior)
REPO="$(cd "$AQUI/../.." && pwd)"
EV="$AQUI/saidas/$HOMOLOG_ID/testes-$(date -u +%Y%m%dT%H%M%SZ)"
T="$HOMOLOG_SEGREDOS_HOST/teste"
mkdir -p "$EV" "$T"
: > "$T/segredos_vistos.txt"
rm -f "$T/estado.json"
RESUMO="$EV/resumo.txt"
echo "teste|resultado|execucao|detalhe" > "$RESUMO"
CA="$HOMOLOG_SEGREDOS_HOST/tls/ca.crt"
URL="https://$HOMOLOG_HOST:$HOMOLOG_PORTA"
curl_h() { curl -s --ssl-no-revoke --cacert "$CA" --resolve "$HOMOLOG_HOST:$HOMOLOG_PORTA:127.0.0.1" "$@"; }
registra() { echo "$1|$2|$3|$4" >> "$RESUMO"; echo "[$1] $2 - $4"; }
rotulo="label=nekkora.homologacao=$HOMOLOG_ID"
# saida sem as linhas de progresso do compose ("Container ... Creating")
ferr() { MSYS_NO_PATHCONV=1 compose run --rm -T -e PGPASSWORD ferramentas "$@" 2>&1 | tr -d '\r' | grep -vE '^ Container |^\s*$'; }
export HOMOLOG_CA="$CA" HOMOLOG_SEGREDOS_VISTOS="$T/segredos_vistos.txt" HOMOLOG_ESTADO="$T/estado.json"
cliente() { python "$AQUI/testes/cliente.py" "$1" "$T/fixture.json"; }

# ================================================================================ H01 identidade e isolamento
h01() {
    local f="$EV/h01_identificacao.txt" ok=0
    {
        echo "integracao: HEAD=$(git -C "$REPO" rev-parse HEAD) origin/main=$(git -C "$REPO" rev-parse origin/main)"
        echo "imagem da API: commit $HOMOLOG_COMMIT (fontes principais da API sem alteracao desde ele)"
        for c in $(docker ps -a --filter "$rotulo" --format '{{.Names}}' | sort); do
            docker inspect "$c" --format '{{.Name}} imagem={{.Config.Image}} id={{.Image}} cpus={{.HostConfig.NanoCpus}} mem={{.HostConfig.Memory}} rotulo={{index .Config.Labels "nekkora.homologacao"}} portas={{json .HostConfig.PortBindings}}'
        done
        for img in postgres:16-alpine redis:7-alpine nginx:1.29-alpine flyway/flyway:11-alpine eclipse-temurin:21-jre-alpine; do
            docker image inspect "$img" --format "base $img {{index .RepoDigests 0}}"
        done
        docker network ls --filter "$rotulo" --format 'rede {{.Name}} interna={{.Internal}}'
        docker volume ls --filter "$rotulo" --format 'volume {{.Name}}'
    } > "$f"
    local sem_limite publicados
    sem_limite="$(grep -E ' (cpus|mem)=0 ' "$f" | grep -v -- '-run-' || true)"
    publicados="$(grep -E 'portas=\{"' "$f" | grep -vc 'gateway-1' || true)"
    [ -z "$sem_limite" ] || { echo "servico sem limite de CPU/memoria" >> "$f"; ok=1; }
    [ "$publicados" = 0 ] || { echo "servico alem do gateway com porta publicada" >> "$f"; ok=1; }
    grep -q 'gateway-1.*portas={"443/tcp":\[{"HostIp":"'"$HOMOLOG_BIND"'","HostPort":"'"$HOMOLOG_PORTA"'"}' "$f" || { echo "gateway nao publicado como esperado" >> "$f"; ok=1; }
    grep -q 'rede .*_interna interna=true' "$f" || { echo "rede interna sem internal=true" >> "$f"; ok=1; }
    # recursos preexistentes preservados
    local antes="$AQUI/saidas/$HOMOLOG_ID/inventario_antes.txt"
    for id in $(awk '/ projeto=/{print $1}' "$antes"); do   # so as linhas de container do inventario
        docker ps -a -q --no-trunc | grep -q "^$id" || { echo "container preexistente $id sumiu" >> "$f"; ok=1; }
    done
    docker inspect nekkora-postgres --format 'principal: {{.Name}} started={{.State.StartedAt}} restarts={{.RestartCount}}' > "$EV/h01_principal_depois.txt" 2>/dev/null
    cmp -s "$AQUI/saidas/$HOMOLOG_ID/principal_antes.txt" "$EV/h01_principal_depois.txt" || { echo "stack principal mudou" >> "$f"; ok=1; }
    [ $ok = 0 ] && registra H01 OK agora "commit $HOMOLOG_COMMIT; limites em todos; so o gateway publicado ($HOMOLOG_BIND:$HOMOLOG_PORTA); rede interna internal=true; preexistentes e principal preservados" \
               || registra H01 FALHA agora "ver h01_identificacao.txt"
}

# ================================================================================ H02 instalacao e implantacao
# Janelas de elevacao do ambiente (P3-IT, F5), de TODOS os registros: criacao (saidas/<id>/janelas_de_elevacao.txt) e
# cada implantar (implantacao-*/janelas_de_elevacao.txt). Por registro, em ordem: "inicio" seguido de "fim" da mesma
# versao = CONCLUIDA; "inicio" seguido de OUTRO "inicio", ou sem "fim" = INTERROMPIDA; "fim" sem "inicio" aberto (ou de
# outra versao) = ORFA. CADA INTERROMPIDA exige a SUA recuperacao: um saidas/<id>/recuperacao-*.txt que conferiu 0|0|0
# CERTAMENTE depois do inicio dela e CERTAMENTE antes do inicio seguinte (qualquer registro); cada recuperacao comprova
# no maximo UMA interrupcao. Instantes como INTERVALOS: com milissegundos (janelas) = o proprio ms; so com segundos
# (cabecalho da recuperacao) = [ss.000, ss.999]. A antes de B so se A.fim < B.inicio (no mesmo registro, a ordem do
# arquivo). Sobreposicao entre registros, ou instante ilegivel, = AMBIGUA: a comprovacao e RECUSADA (conta como problema).
#   h02_janelas <base do ambiente> <dir de saida>  -> imprime "concluidas(versoes ordenadas)|n_interrompidas|n_problemas"
# e grava <dir>/h02_janelas_todas.txt, h02_janelas_brutas.txt, h02_recuperacoes.txt e h02_janelas_classificadas.txt
# (TIPO|versao|instante|registro|sequencia|situacao).
h02_janelas() {
    local base="$1" out="$2" arq rec t
    : > "$out/h02_janelas_todas.txt"; : > "$out/h02_janelas_brutas.txt"; : > "$out/h02_recuperacoes.txt"
    for arq in "$base/janelas_de_elevacao.txt" "$base"/implantacao-*/janelas_de_elevacao.txt; do
        [ -f "$arq" ] || continue
        echo "## ${arq#$base/}" >> "$out/h02_janelas_todas.txt"; cat "$arq" >> "$out/h02_janelas_todas.txt"
        # instante = primeiro campo da linha, COMPLETO (ex.: 2026-09-29T13:08:12.550Z); sequencia = linha no registro
        tr -d '\r' < "$arq" | awk -v reg="${arq#$base/}" '
            / inicio versao=/ { match($0, /versao=[0-9]+/); v = substr($0, RSTART + 7, RLENGTH - 7)
                               if (a != "") print "INTERROMPIDA|" a "|" ta "|" reg "|" sa
                               a = v; ta = $1; sa = NR; next }
            / fim +versao=.*revogado, sessoes encerradas, estado conferido/ {
                               match($0, /versao=[0-9]+/); b = substr($0, RSTART + 7, RLENGTH - 7)
                               if (a != "" && a == b) print "CONCLUIDA|" b "|" ta "|" reg "|" sa
                               else { if (a != "") print "INTERROMPIDA|" a "|" ta "|" reg "|" sa; print "ORFA|" b "|" $1 "|" reg "|" NR }
                               a = ""; next }
            END { if (a != "") print "INTERROMPIDA|" a "|" ta "|" reg "|" sa }' >> "$out/h02_janelas_brutas.txt"
    done
    # recuperacoes que conferiram 0|0|0, pelo instante do cabecalho ("== recuperacao <UTC> ..."), com a precisao que tiver
    for rec in "$base"/recuperacao-*.txt; do
        [ -f "$rec" ] || continue
        grep -q 'estado do migrador depois da revogacao: 0|0|0' "$rec" || continue
        t="$(tr -d '\r' < "$rec" | sed -nE '1s/^== recuperacao ([^ ]+).*/\1/p')"
        echo "${t:-ILEGIVEL}|${rec#$base/}" >> "$out/h02_recuperacoes.txt"
    done
    awk -F'|' -v REC="$out/h02_recuperacoes.txt" '
        # intervalo [LO, HI] em texto de largura fixa "AAAA-MM-DDTHH:MM:SS.mmm"; 0 se ilegivel
        function iv(ts,   d, f) {
            if (ts !~ /^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9](\.[0-9]+)?Z$/) return 0
            d = substr(ts, 1, 19)
            if (substr(ts, 20, 1) == ".") { f = substr(ts, 21); sub(/Z$/, "", f); f = substr(f "000", 1, 3); LO = d "." f; HI = LO }
            else { LO = d ".000"; HI = d ".999" }
            return 1
        }
        BEGIN { nr = 0
                while ((getline l < REC) > 0) { split(l, p, "|"); nr++; rts[nr] = p[1]; rarq[nr] = p[2]
                    rok[nr] = iv(p[1]); rlo[nr] = LO; rhi[nr] = HI; usada[nr] = 0 } }
        { n++; tipo[n] = $1; ver[n] = $2; ts[n] = $3; reg[n] = $4; seq[n] = $5; linha[n] = $0
          ok[n] = iv($3); lo[n] = LO; hi[n] = HI }
        END {
            # interrompidas em ordem de inicio (as ilegiveis ao fim), para o consumo 1:1 das recuperacoes
            m = 0; for (i = 1; i <= n; i++) if (tipo[i] == "INTERROMPIDA") ord[++m] = i
            for (x = 2; x <= m; x++) for (y = x; y > 1; y--) {
                a = ord[y - 1]; b = ord[y]
                if ((ok[a] ? lo[a] : "~") > (ok[b] ? lo[b] : "~")) { ord[y - 1] = b; ord[y] = a } else break }
            for (x = 1; x <= m; x++) {
                i = ord[x]; amb = ""; blo = ""; bhi = ""
                if (!ok[i]) amb = "instante ilegivel: " ts[i]
                for (j = 1; j <= n && amb == ""; j++) {
                    if (j == i || tipo[j] == "ORFA") continue
                    if (!ok[j]) { amb = "instante ilegivel em " reg[j]; break }
                    if (reg[j] == reg[i]) { depois = (seq[j] + 0 > seq[i] + 0) }
                    else if (lo[j] > hi[i]) depois = 1
                    else if (hi[j] < lo[i]) depois = 0
                    else { amb = "inicio em " reg[j] " (" ts[j] ") sem ordem estabelecida em relacao a " ts[i]; break }
                    if (depois && (blo == "" || lo[j] < blo)) { blo = lo[j]; bhi = hi[j]; bts = ts[j] }
                }
                achou = 0
                for (r = 1; r <= nr && amb == ""; r++) {
                    if (!rok[r]) { amb = "recuperacao com instante ilegivel (" rarq[r] ")"; break }
                    dentro = (rlo[r] > hi[i]) && (blo == "" || rhi[r] < blo)
                    fora = (rhi[r] < lo[i]) || (blo != "" && rlo[r] > bhi)
                    if (!dentro && !fora) { amb = "recuperacao " rts[r] " sem ordem estabelecida em relacao ao inicio " ts[i] (blo == "" ? "" : " ou ao inicio seguinte " bts); break }
                    if (dentro && !usada[r] && !achou) { usada[r] = 1; achou = r }
                }
                if (amb != "") sit[i] = "AMBIGUA (" amb "): comprovacao RECUSADA"
                else if (achou) sit[i] = "RECUPERADA em " rts[achou] " (" rarq[achou] ")"
                else sit[i] = "SEM RECUPERACAO PROPRIA (antes de " (blo == "" ? "o fim" : bts) ")"
            }
            for (i = 1; i <= n; i++) print linha[i] "|" (tipo[i] == "INTERROMPIDA" ? sit[i] : "-")
        }' "$out/h02_janelas_brutas.txt" > "$out/h02_janelas_classificadas.txt"
    printf '%s|%s|%s\n' \
        "$(grep '^CONCLUIDA|' "$out/h02_janelas_classificadas.txt" | cut -d'|' -f2 | sort | tr '\n' ' ' | sed 's/ $//')" \
        "$(grep -c '^INTERROMPIDA|' "$out/h02_janelas_classificadas.txt")" \
        "$(grep -cE '^ORFA\||SEM RECUPERACAO|AMBIGUA' "$out/h02_janelas_classificadas.txt")"
}

# Janelas CONCLUIDAS esperadas (P3-IT, F5; 014 desde a R2b-0b): 011, 012 e 013 (e a 016) quando a copia de migrations do
# ambiente as contem; a 014 SO quando, alem de estar na copia, a lista de elevacao HISTORICA do ambiente (a fotografia
# feita pelo criar, ou a fonte legada explicita: lib/elevacao_historica.sh, eh_lista_h02) autoriza a 014 com
# tipo=bypassrls sem vinculo ou vinculada a ESTE id - o mesmo leitor de lista do runner (lib/lista_elevacao.sh).
# Lista ilegivel ou fora do formato: codigo 1 (o H02 falha; nunca equivale a "sem 014").
# (O limite da R2b-0b - expectativa pela lista em vigor no TESTE - foi resolvido pela missao H02 x lista historica.)
#   h02_esperadas <dir de migrations do ambiente> <lista> <id>  -> imprime as versoes esperadas, separadas por espaco
h02_esperadas() {
    local mig="$1" lista="$2" id="$3" v esp="" n
    for v in 011 012 013 016; do ls "$mig/${v}_"*.sql > /dev/null 2>&1 && esp="$esp $v"; done
    . "$AQUI/lib/lista_elevacao.sh"
    n="$(lista_elevacao_normalizada "$lista" " 016 " 2>&1)" || { echo "lista de elevacao historica recusada ($lista): $n" >&2; return 1; }
    if ls "$mig/014_"*.sql > /dev/null 2>&1 \
       && printf '%s\n' "$n" | awk -v id="$id" '$1 == "014" && $3 == "bypassrls" && ($4 == "-" || $4 == id) { f = 1 } END { exit !f }'; then
        esp="$esp 014"
    fi
    echo $esp | tr ' ' '\n' | sort | tr '\n' ' ' | sed 's/ $//'
}

h02() {
    # Expectativa DERIVADA do ambiente (P3-IT, F5): versoes = copia de migrations do ambiente; janelas esperadas = as
    # versoes da copia que exigem elevacao (011, 012, 013 e, com HOMOLOG_ATE=016, a 016), cada uma com EXATAMENTE uma
    # janela CONCLUIDA (inicio + fim revogado/conferido no mesmo arquivo) nos registros da criacao e das implantacoes
    # (saidas/<id>/janelas_de_elevacao.txt e implantacao-*/janelas_de_elevacao.txt). Janela INTERROMPIDA e contada a parte
    # e exige a SUA recuperacao com 0|0|0 (h02_janelas).
    # P3-R015: com versao sintetica de ensaio DECLARADA e valida (lib/estado.sh, sintetica_declarada; so ensaio-*), a
    # sintetica entra nas janelas concluidas esperadas, as interrompidas tem de ser EXATAMENTE o numero declarado (todas
    # com recuperacao propria), a declaracao tem de ser anterior a primeira janela da sintetica, e historico/reexecucao
    # usam o conjunto de laboratorio. A comparacao das migrations REAIS com db/migrations nao muda.
    . "$AQUI/lib/estado.sh"   # estado_migrador e sintetica_declarada (executar.sh roda por exec; nada e exportado)
    local base="$AQUI/saidas/$HOMOLOG_ID" ok=0 v arq esperadas="" concluidas interrompidas=0 mig
    cp "$base/implantacao.txt" "$EV/h02_implantacao_criacao.txt"; cp "$base/janelas_de_elevacao.txt" "$EV/h02_janelas_de_elevacao.txt"
    cp "$base/flyway_info.txt" "$EV/h02_flyway_info_criacao.txt"; cp "$base/migracoes_sha256.txt" "$EV/h02_migracoes_sha256.txt"
    # a copia do ambiente confere com os MESMOS arquivos de db/migrations (so os nomes presentes na copia)
    (cd "$REPO/db/migrations" && sha256sum $(cd "$base/migracoes" && ls *.sql)) | diff - "$EV/h02_migracoes_sha256.txt" > /dev/null \
        || { echo "copia difere de db/migrations" >> "$EV/h02_nota.txt"; ok=1; }
    # lista HISTORICA da criacao (missao H02 x lista historica; lib/elevacao_historica.sh): a fotografia do ambiente ou, so
    # em ambiente legado, a fonte historica EXPLICITA; NUNCA a lista vigente (elevacao.txt nem HOMOLOG_ELEVACAO_ARQ do teste)
    . "$AQUI/lib/elevacao_historica.sh"
    local lista_h02
    if ! lista_h02="$(eh_lista_h02 "$base" "$HOMOLOG_ID" "${HOMOLOG_ELEVACAO_CRIACAO_SHA256:-}" 2>> "$EV/h02_nota.txt")"; then
        echo "expectativa de janelas NAO determinada: sem lista historica valida (falha fechada)" >> "$EV/h02_nota.txt"; esperadas=""; ok=1
    elif esperadas="$(h02_esperadas "$base/migracoes" "$lista_h02" "$HOMOLOG_ID" 2>> "$EV/h02_nota.txt")"; then
        echo "expectativa de janelas pela lista HISTORICA $lista_h02 (sha256 $(sha256sum "$lista_h02" | cut -c1-64)): $esperadas" >> "$EV/h02_nota.txt"
    else
        esperadas=""; ok=1
    fi
    mig="$base/migracoes"
    if ! sintetica_declarada; then
        echo "declaracao de versao sintetica invalida: $SINT_ERRO" >> "$EV/h02_nota.txt"; ok=1
    elif [ -n "$SINT_VERSAO" ]; then
        cp "$base/ensaio_sintetica.txt" "$EV/h02_ensaio_sintetica.txt"
        esperadas="$esperadas $SINT_VERSAO"; mig="$SINT_CONJ"
        echo "ensaio: versao sintetica $SINT_VERSAO declarada em $SINT_EM ($SINT_ARQ, sha256 $SINT_SHA); interrompidas esperadas: $SINT_INTERR" >> "$EV/h02_nota.txt"
    fi
    local r_jan; r_jan="$(h02_janelas "$base" "$EV")"
    concluidas="$(echo "$r_jan" | cut -d'|' -f1)"; interrompidas="$(echo "$r_jan" | cut -d'|' -f2)"
    [ "$concluidas" = "$(echo $esperadas | tr ' ' '\n' | sort | tr '\n' ' ' | sed 's/ $//')" ] \
        || { echo "janelas concluidas '$concluidas' <> esperadas '$(echo $esperadas)'" >> "$EV/h02_nota.txt"; ok=1; }
    [ "$(echo "$r_jan" | cut -d'|' -f3)" = 0 ] || { echo "janelas com problema (ORFA, AMBIGUA ou INTERROMPIDA sem recuperacao propria): ver h02_janelas_classificadas.txt" >> "$EV/h02_nota.txt"; ok=1; }
    if [ -n "${SINT_VERSAO:-}" ]; then
        [ "$interrompidas" = "$SINT_INTERR" ] || { echo "interrompidas $interrompidas <> declaradas $SINT_INTERR" >> "$EV/h02_nota.txt"; ok=1; }
        local primeira; primeira="$(grep -E "^[A-Z]+\|$SINT_VERSAO\|" "$EV/h02_janelas_classificadas.txt" | cut -d'|' -f3 | sort | head -1)"
        [ -n "$primeira" ] && [[ "${SINT_EM%Z}" < "${primeira:0:19}" ]] \
            || { echo "declaracao ($SINT_EM) nao e anterior a primeira janela da sintetica (${primeira:-nenhuma})" >> "$EV/h02_nota.txt"; ok=1; }
    fi
    # reexecucao: nada pendente, so validacao (historico e checksums) pela identidade dedicada SEM elevacao
    HOMOLOG_EVID="$EV" HOMOLOG_MIGRACOES="$(cygpath -m "$mig" 2>/dev/null || echo "$mig")" HOMOLOG_MIGRACOES_HOST="$mig" \
        bash "$AQUI/implantacao/implantar.sh" > "$EV/h02_reexecucao.txt" 2>&1 || ok=1
    mv "$EV/implantacao.txt" "$EV/h02_reexecucao_detalhe.txt" 2>/dev/null; mv "$EV/flyway_info.txt" "$EV/h02_flyway_info.txt" 2>/dev/null
    local hist_esperado; hist_esperado="$(cd "$mig" && ls *.sql | sed -E 's/_.*//; s/$/:true/' | tr '\n' ' ' | sed 's/ $//')"
    grep -q "historico depois: $hist_esperado\$" "$EV/h02_reexecucao.txt" || { echo "historico <> '$hist_esperado'" >> "$EV/h02_nota.txt"; ok=1; }
    [ ! -s "$EV/janelas_de_elevacao.txt" ] || ok=1   # a reexecucao nao elevou nada
    [ "$(admin_sql postgres -c "SELECT rolsuper FROM pg_roles WHERE rolname = 'migrador_nekkora'")" = f ] || ok=1
    [ "$(estado_migrador)" = "0|0|0" ] || { echo "migrador fora de 0|0|0 ao fim" >> "$EV/h02_nota.txt"; ok=1; }
    [ $ok = 0 ] && registra H02 OK agora "historico = $([ -n "${SINT_VERSAO:-}" ] && echo "copia + sintetica declarada $SINT_VERSAO" || echo "copia do ambiente") ($hist_esperado); janelas concluidas: $concluidas (esperadas); interrompidas: $interrompidas, todas com recuperacao propria; reexecucao sem pendencias valida historico/checksums sem elevar; migrador 0|0|0" \
               || registra H02 FALHA agora "ver h02_*"
}

# ================================================================================ H03 privilegio minimo
h03() {
    # H03 pos-016 (F-R2b1c-1): expectativas por PERFIL do modelo de migrations do ambiente (testes/h03_perfis.sh), nunca
    # supondo 001-015. Modelo nao determinado = FALHA fechada (nada e avaliado). Cada conferencia que falha deixa o motivo
    # em h03_nota.txt.
    local ok=0 f="$EV/h03_estado.txt" nota="$EV/h03_nota.txt" mig="$AQUI/saidas/$HOMOLOG_ID/migracoes" modelo hmax n
    : > "$nota"
    falha3() { echo "FALHA: $*" >> "$nota"; ok=1; }
    . "$AQUI/testes/h03_perfis.sh"
    if ! modelo="$(h03_modelo "$mig" "${HOMOLOG_ATE:-015}" 2>> "$nota")" || ! h03_perfil "$modelo" 2>> "$nota"; then
        echo "FALHA: modelo de migrations nao determinado; H03 nao avalia nada (falha fechada)" >> "$nota"
        registra H03 FALHA agora "modelo de migrations nao determinado (ver h03_nota.txt)"
        return
    fi
    echo "modelo $modelo (copia $mig, HOMOLOG_ATE=${HOMOLOG_ATE:-015}): perfil H03-$modelo" >> "$nota"
    hmax="$(admin_sql nekkora -c "SELECT max(version) FROM public.flyway_schema_history WHERE success" 2>&1 | tr -d '\r')"
    [ "$hmax" = "$modelo" ] || falha3 "historico do banco termina em '$hmax', modelo da copia $modelo"
    admin_sql nekkora < "$AQUI/sql/h03_estado.sql" > "$f" 2>&1 || falha3 "h03_estado.sql nao executou"
    ! grep -q '^senha_nao_scram|' "$f" || falha3 "senha nao SCRAM"
    [ "$(grep '^membership|' "$f")" = "membership|pg_read_all_data->backup_nekkora|admin=false|inherit=true|set=true" ] || falha3 "memberships inesperadas"
    { [ "$(grep '^atributos|' "$f" | grep -c 'super=true')" = 1 ] && grep -q '^atributos|admin_cluster|login=true|super=true' "$f"; } || falha3 "superusuario diferente de so admin_cluster"
    grep -q '^atributos|migrador_nekkora|login=true|super=false|createrole=false|createdb=false|bypassrls=false' "$f" || falha3 "migrador com atributo"
    grep -q '^atributos|backup_nekkora|login=true|super=false|createrole=false|createdb=false|bypassrls=true' "$f" || falha3 "backup_nekkora fora do esperado"
    ! grep '^atributos|nekkora_' "$f" | grep -qE 'super=true|createrole=true|createdb=true|bypassrls=true|replication=true' || falha3 "papel nekkora_* com atributo elevado"
    { grep -q "^conexao_servico|nekkora_platform|$HOMOLOG_REDE_INTERNA.20|ssl=true|TLSv1.3" "$f" && grep -q "^conexao_servico|nekkora_portal|$HOMOLOG_REDE_INTERNA.21|ssl=true|TLSv1.3" "$f"; } || falha3 "conexoes dos servicos fora do esperado"
    ! grep '^hba|' "$f" | grep -qiE '\|trust\||\|error' || falha3 "pg_hba com trust ou erro"
    grep -qx "login_com_senha|$H03_LOGIN_COM_SENHA" "$f" \
        || falha3 "papeis com LOGIN e senha: '$(grep '^login_com_senha|' "$f" | cut -d'|' -f2)' <> perfil $modelo '$H03_LOGIN_COM_SENHA'"
    # invariantes das migrations do PERFIL, reexecutados sobre o estado atual (transacao desfeita)
    if h03_invariantes_sql "$mig" > "$T/invariantes.sql" 2>> "$nota"; then
        admin_sql nekkora < "$T/invariantes.sql" > "$EV/h03_invariantes.txt" 2>&1 \
            && echo "$H03_BLOCOS blocos de invariantes do perfil $modelo sem erro" >> "$EV/h03_invariantes.txt" \
            || { echo "INVARIANTE FALHOU" >> "$EV/h03_invariantes.txt"; falha3 "invariante do perfil $modelo: $(grep -m1 ERROR "$EV/h03_invariantes.txt" | cut -c1-160)"; }
    else
        falha3 "SQL de invariantes do perfil $modelo nao montado"
    fi
    # catalogo x referencia DESCARTAVEL instalada pelo modelo SUPORTADO (aplicar_como_migrador.sh), com o instalador normalizado
    h03_referencia "$HOMOLOG_ID" "$mig" "$modelo" "$REPO/db/bootstrap/aplicar_como_migrador.sh" "$T/cat_ref.txt" \
        "$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql" "$EV" || falha3 "referencia do modelo $modelo invalida (ver h03_referencia.txt)"
    admin_sql nekkora < "$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql" > "$T/cat_homolog.txt" 2>&1 || falha3 "listagem do catalogo do ambiente"
    python "$AQUI/lib/catalogo.py" "$T/cat_ref.txt" "$EV/h03_catalogo_referencia_normalizado.txt" migrador_nekkora nekkora 2> /dev/null || : > "$EV/h03_catalogo_referencia_normalizado.txt"
    python "$AQUI/lib/catalogo.py" "$T/cat_homolog.txt" "$EV/h03_catalogo_homologacao_normalizado.txt" migrador_nekkora admin_cluster
    diff "$EV/h03_catalogo_referencia_normalizado.txt" "$EV/h03_catalogo_homologacao_normalizado.txt" > "$EV/h03_catalogo_diff.txt"
    grep -E '^[<>]' "$EV/h03_catalogo_diff.txt" | grep -v flyway_schema_history > "$EV/h03_catalogo_diff_sem_flyway.txt"
    [ -s "$EV/h03_catalogo_referencia_normalizado.txt" ] || falha3 "catalogo de referencia vazio"
    [ ! -s "$EV/h03_catalogo_diff_sem_flyway.txt" ] || falha3 "catalogo difere da referencia ($(wc -l < "$EV/h03_catalogo_diff_sem_flyway.txt") linhas fora da tabela do Flyway)"
    n="$(wc -l < "$EV/h03_catalogo_referencia_normalizado.txt")"
    [ $ok = 0 ] && registra H03 OK agora "perfil H03-$modelo; catalogo = referencia 001-$modelo instalada pelo modelo suportado ($n linhas; so difere a tabela do Flyway); invariantes do perfil ($H03_BLOCOS blocos) reexecutados; papeis com senha do perfil; so admin_cluster superusuario; membership unica pg_read_all_data->backup_nekkora; servicos pelo proprio IP com TLSv1.3; pg_hba sem trust" \
               || registra H03 FALHA agora "ver h03_nota.txt e h03_*"
}

# ================================================================================ H04 rede e TLS
h04() {
    local ok=0 f="$EV/h04_rede_tls.txt"
    : > "$f"
    local c; c="$(curl_h -o /dev/null -w '%{http_code}' "$URL/api/v1/csrf")"; echo "gateway HTTPS com a CA da homologacao: $c" >> "$f"; [ "$c" = 200 ] || ok=1
    MSYS2_ARG_CONV_EXCL='*' openssl s_client -connect "127.0.0.1:$HOMOLOG_PORTA" -servername "$HOMOLOG_HOST" -CAfile "$(cygpath -w "$CA")" -verify_return_error </dev/null > "$T/s_client.txt" 2>&1
    grep -q 'Verify return code: 0 (ok)' "$T/s_client.txt" && echo "gateway: cadeia verificada (Verify return code 0), $(grep -m1 -oE 'TLSv1\.[0-9]' "$T/s_client.txt")" >> "$f" || ok=1
    if MSYS2_ARG_CONV_EXCL='*' openssl s_client -connect "127.0.0.1:$HOMOLOG_PORTA" -servername "$HOMOLOG_HOST" -tls1_1 </dev/null > "$T/s_client11.txt" 2>&1; then
        echo "gateway aceitou TLS 1.1" >> "$f"; ok=1; else echo "gateway recusa TLS 1.1" >> "$f"; fi
    # PostgreSQL, a partir do container de ferramentas ($HOMOLOG_REDE_INTERNA.31)
    local pg="host=postgres port=5432 dbname=nekkora connect_timeout=5"
    local q="SELECT 'tls='||ssl||' '||version FROM pg_stat_ssl WHERE pid = pg_backend_pid()"
    r="$(ferr psql -X -At "$pg user=admin_cluster sslmode=verify-full sslrootcert=/tls/ca.crt" -c "$q" 2>&1)"; echo "admin verify-full (CA correta): $r" >> "$f"; [ "$r" = "tls=true TLSv1.3" ] || ok=1
    r="$(ferr psql -X -At "$pg user=admin_cluster sslmode=disable" -c "SELECT 1" 2>&1)"; echo "texto claro (sslmode=disable): $(echo "$r" | tail -1 | cut -c1-160)" >> "$f"; echo "$r" | grep -q 'pg_hba.conf rejects connection\|no pg_hba.conf entry' || ok=1
    r="$(ferr psql -X -At "$pg user=admin_cluster sslmode=verify-full sslrootcert=/etc/ssl/certs/ca-certificates.crt" -c "SELECT 1" 2>&1)"; echo "CA errada: $(echo "$r" | tail -1 | cut -c1-160)" >> "$f"; echo "$r" | grep -qi 'certificate verify failed' || ok=1
    r="$(ferr psql -X -At "hostaddr=$HOMOLOG_REDE_INTERNA.10 host=$HOMOLOG_REDE_INTERNA.10 port=5432 dbname=nekkora user=admin_cluster sslmode=verify-full sslrootcert=/tls/ca.crt connect_timeout=5" -c "SELECT 1" 2>&1)"; echo "nome do servidor diferente do certificado: $(echo "$r" | tail -1 | cut -c1-160)" >> "$f"; echo "$r" | grep -qi 'does not match host name' || ok=1
    r="$(PGPASSWORD=senha-errada-de-proposito ferr psql -X -At "$pg user=admin_cluster sslmode=verify-full sslrootcert=/tls/ca.crt" -c "SELECT 1" 2>&1)"; echo "senha errada: $(echo "$r" | tail -1 | cut -c1-160)" >> "$f"; echo "$r" | grep -q 'password authentication failed' || ok=1
    r="$(PGPASSWORD="$(cat "$HOMOLOG_SEGREDOS_HOST/senhas/platform")" ferr psql -X -At "$pg user=nekkora_platform sslmode=verify-full sslrootcert=/tls/ca.crt" -c "SELECT 1" 2>&1)"; echo "papel da Platform fora do proprio container: $(echo "$r" | tail -1 | cut -c1-160)" >> "$f"; echo "$r" | grep -q 'pg_hba.conf rejects connection' || ok=1
    r="$(ferr sh -c 'pg_isready -h 1.1.1.1 -p 443 -t 3; echo rc=$?' 2>&1 | tail -1)"; echo "rede interna sem saida (pg_isready 1.1.1.1:443): $r" >> "$f"; [ "$r" = "rc=2" ] || ok=1
    # Redis: so TLS, usuario default desligado, ACL por servico
    local rc="docker exec" rp; rp="$(cat "$HOMOLOG_SEGREDOS_HOST/senhas/redis_platform")"; local ro; ro="$(cat "$HOMOLOG_SEGREDOS_HOST/senhas/redis_portal")"
    red() { MSYS_NO_PATHCONV=1 compose exec -T "$@" 2>&1 | tr -d '\r' | grep -v '^\s*$' | tail -1; }
    r="$(red redis redis-cli -h 127.0.0.1 -p 6379 ping)"; echo "Redis sem TLS: $r" >> "$f"; [ "$r" != PONG ] || ok=1
    r="$(red redis redis-cli --tls --cacert /tls/ca.crt -h 127.0.0.1 ping)"; echo "Redis TLS anonimo: $r" >> "$f"; echo "$r" | grep -q NOAUTH || ok=1
    r="$(red -e REDISCLI_AUTH="$rp" redis redis-cli --tls --cacert /tls/ca.crt -h 127.0.0.1 --user platform ping)"; echo "Redis TLS usuario platform: $r" >> "$f"; [ "$r" = PONG ] || ok=1
    r="$(red -e REDISCLI_AUTH="$rp" redis redis-cli --tls --cacert /tls/ca.crt -h 127.0.0.1 --user platform get fora:do:prefixo)"; echo "platform em chave fora de nk:*: $r" >> "$f"; echo "$r" | grep -q NOPERM || ok=1
    r="$(red -e REDISCLI_AUTH="$rp" redis redis-cli --tls --cacert /tls/ca.crt -h 127.0.0.1 --user platform flushall)"; echo "platform FLUSHALL: $r" >> "$f"; echo "$r" | grep -q NOPERM || ok=1
    r="$(red -e REDISCLI_AUTH="$ro" redis redis-cli --tls --cacert /tls/ca.crt -h 127.0.0.1 --user portal get nk:csrf:pre:x)"; echo "portal em chave de CSRF da Platform: $r" >> "$f"; echo "$r" | grep -q NOPERM || ok=1
    # sem senha de fallback no perfil: nenhuma variavel de segredo com valor padrao na configuracao versionada
    if grep -nE '\$\{NEKKORA_[A-Z_]*(SENHA|CHAVE|CHAVES)[^}]*:' "$REPO"/api/nekkora-*/src/main/resources/application.yml; then echo "segredo com valor padrao" >> "$f"; ok=1; else echo "application.yml: nenhum segredo com valor padrao" >> "$f"; fi
    [ $ok = 0 ] && registra H04 OK agora "gateway HTTPS (cadeia verificada, TLS 1.1 recusado); PostgreSQL so TLS verify-full + SCRAM, texto claro/CA errada/nome errado/senha errada/papel fora do proprio IP recusados; rede interna sem saida; Redis so TLS com ACL por servico" \
               || registra H04 FALHA agora "ver h04_rede_tls.txt"
}

# ================================================================================ H05 fluxo integrado
h05() {
    local ok=0
    python - "$T" <<'PY'
import json, secrets, sys, uuid
t = sys.argv[1]
u = lambda: str(uuid.uuid4())
senha = lambda: 'sintetica-' + secrets.token_urlsafe(12)
ids = {k: u() for k in ('org_a', 'org_b', 'adm_a', 'adm_b', 'adm_a_m', 'adm_b_m', 'int_a', 'ext_a', 'ext_b', 'cli_1', 'cli_2', 'cli_b',
                        'ws_1', 'ws_2', 'ws_b', 'cm_a', 'cm_b', 'aut_1', 'aut_2', 'ex_1', 'ex_2')}
f = {'ids': ids, 'org_a': ids['org_a'], 'org_b': ids['org_b'], 'ws_concedido': ids['ws_1'], 'ws_b': ids['ws_b'],
     'exec_concedida': ids['ex_1'], 'exec_outro_cliente': ids['ex_2'],
     'interno_a': {'email': f'interno-{ids["int_a"][:8]}@homologacao.test', 'senha': senha()},
     'externo_a': {'email': f'externo-{ids["ext_a"][:8]}@cliente-a.test', 'senha': senha()},
     'externo_b': {'email': f'externo-{ids["ext_b"][:8]}@cliente-b.test', 'senha': senha()}}
json.dump(f, open(f'{t}/fixture.json', 'w'))
PY
    local jar test phc_i phc_x phc_y
    jar="$(cygpath -m "$REPO/api/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar")"; test="$(cygpath -m "$REPO/api/nekkora-platform/target/test-classes")"
    phc() { python -c "import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]]['senha'])" "$T/fixture.json" "$1" \
        | tr -d '\r' | java -Dloader.path="$test" -Dloader.main=com.nekkora.platform.auth.crypto.PhcTool -cp "$jar" org.springframework.boot.loader.launch.PropertiesLauncher 65536 3 | tr -d '\r'; }
    phc_i="$(phc interno_a)"; phc_x="$(phc externo_a)"; phc_y="$(phc externo_b)"
    local vars=()
    while IFS='=' read -r k v; do vars+=(-v "$k=$v"); done < <(python -c "import json,sys; f=json.load(open(sys.argv[1])); [print(f'{k}={v}') for k,v in f['ids'].items()]; print('email_int_a='+f['interno_a']['email']); print('email_ext_a='+f['externo_a']['email']); print('email_ext_b='+f['externo_b']['email'])" "$T/fixture.json" | tr -d '\r')
    admin_sql nekkora "${vars[@]}" -v phc_int_a="$phc_i" -v phc_ext_a="$phc_x" -v phc_ext_b="$phc_y" < "$AQUI/sql/fixture.sql" > "$EV/h05_fixture.txt" 2>&1 || { cat "$EV/h05_fixture.txt"; ok=1; }
    cliente h05 > "$EV/h05_fluxo.txt" 2>&1 || ok=1
    local n; n="$(grep -c '^OK' "$EV/h05_fluxo.txt")"
    [ $ok = 0 ] && registra H05 OK agora "$n verificacoes pelo gateway: login/MFA (externo, interno, outra organizacao), Portal com o cookie da Platform, concessao e cliente isolados, organizacao B recusada, logout e revoke-all derrubando o Portal, CSRF/Origin" \
               || registra H05 FALHA agora "ver h05_fluxo.txt"
}

# ================================================================================ H06 conexao e sessao
h06() {
    local ok=0 desde
    admin_sql postgres -c "ALTER SYSTEM SET log_statement = 'all'" -c "ALTER SYSTEM SET log_parameter_max_length = 0" -c "SELECT pg_reload_conf()" > /dev/null || ok=1
    sleep 2
    desde="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    cliente h06 > "$EV/h06_fluxo.txt" 2>&1 || ok=1
    sleep 1
    admin_sql postgres -c "ALTER SYSTEM RESET log_statement" -c "ALTER SYSTEM RESET log_parameter_max_length" -c "SELECT pg_reload_conf()" > /dev/null || ok=1
    sleep 1
    [ "$(admin_sql postgres -c "SHOW log_statement")" = none ] && [ "$(admin_sql postgres -c "SHOW log_parameter_max_length")" = -1 ] || { echo "configuracao de log NAO restaurada" >> "$EV/h06_resultado.txt"; ok=1; }
    docker logs --since "$desde" "$(compose ps -q postgres)" > "$T/h06_pg.log" 2>&1
    python - "$T/h06_pg.log" >> "$EV/h06_resultado.txt" <<'PY' || ok=1
import re, sys, collections
eventos = collections.defaultdict(list)
for l in open(sys.argv[1], encoding='utf-8', errors='replace'):
    m = re.search(r'\[(\d+)\] nekkora_platform@nekkora\s*LOG:\s+(?:statement|execute [^:]+):\s*(.*)', l)
    if not m:
        continue
    pid, sql = m.group(1), m.group(2).strip().upper()
    if 'SET_CONFIG(\'NEKKORA.ORGANIZATION_ID\'' in sql:
        eventos[pid].append('CTX')
    elif sql.startswith('DISCARD ALL'):
        eventos[pid].append('DISCARD')
    elif sql in ('COMMIT', 'ROLLBACK'):
        eventos[pid].append('FIM')
    elif sql == 'BEGIN':
        eventos[pid].append('BEGIN')
ctx = sum(e.count('CTX') for e in eventos.values())
disc = sum(e.count('DISCARD') for e in eventos.values())
violacoes = 0
for pid, ev in eventos.items():
    pendente = False
    for e in ev:
        if e == 'CTX':
            if pendente:
                violacoes += 1   # novo contexto sem DISCARD ALL desde o anterior na mesma conexao
            pendente = True
        elif e == 'DISCARD':
            pendente = False
        elif e == 'BEGIN' and pendente:
            violacoes += 1       # nova transacao na mesma conexao sem reset desde o contexto anterior
print(f'conexoes fisicas da Platform observadas: {len(eventos)}')
print(f'transacoes com set_config(nekkora.organization_id): {ctx}')
print(f'DISCARD ALL na devolucao ao pool: {disc}')
print(f'contexto seguido de nova transacao/contexto sem DISCARD ALL na mesma conexao: {violacoes}')
sys.exit(0 if ctx > 0 and disc >= ctx and violacoes == 0 and len(eventos) >= 1 else 1)
PY
    [ $ok = 0 ] && registra H06 OK agora "$(tr '\n' ';' < "$EV/h06_resultado.txt" | sed 's/;$//'); pool Hikari + pgJDBC do ambiente; logs de instrucao ligados so durante o teste (parametros nunca registrados) e restaurados" \
               || registra H06 FALHA agora "ver h06_resultado.txt"
}

# ================================================================================ H07 disponibilidade e reducao de acesso
h07() {
    local ok=0
    cliente h07_prep > "$EV/h07_fluxo.txt" 2>&1 || ok=1
    compose stop redis > /dev/null 2>&1
    cliente h07_redis_fora >> "$EV/h07_fluxo.txt" 2>&1 || ok=1
    compose up -d --wait redis > /dev/null 2>&1 || ok=1
    sleep 5
    cliente h07_r7 >> "$EV/h07_fluxo.txt" 2>&1 || ok=1
    local n; n="$(grep -c '^OK' "$EV/h07_fluxo.txt")"
    [ $ok = 0 ] && registra H07 OK agora "$n verificacoes: Redis fora (503 no pre-login e na escrita, leitura e revoke-all seguem); com a R7 esgotada logout e revoke-all -> 204 e revogacao efetiva; logout sem sessao 204 (idempotente); revoke-all sem sessao 401" \
               || registra H07 FALHA agora "ver h07_fluxo.txt"
}

# ================================================================================ H09 recuperacao operacional
h09() {
    local ok=0 f="$EV/h09_recuperacao.txt" base="$AQUI/saidas/$HOMOLOG_ID"
    : > "$f"
    local antes; antes="$(docker ps -aq --filter "$rotulo" | sort | tr '\n' ' ')"
    local hist0; hist0="$(admin_sql nekkora -c "SELECT string_agg(version || ':' || success || ':' || checksum, ' ' ORDER BY installed_rank) FROM flyway_schema_history")"
    # (a) recriar com o mesmo id: recusado antes de criar qualquer recurso
    if bash "$AQUI/homologacao.sh" criar "$HOMOLOG_ID" > "$T/h09a.txt" 2>&1; then echo "(a) criar com id em uso NAO foi recusado" >> "$f"; ok=1
    else echo "(a) criar com id em uso: recusado ($(grep -m1 ERRO "$T/h09a.txt"))" >> "$f"; fi
    [ "$(docker ps -aq --filter "$rotulo" | sort | tr '\n' ' ')" = "$antes" ] || { echo "(a) recursos mudaram" >> "$f"; ok=1; }
    # (b) checksum divergente: a implantacao para na validacao; sem repair
    local d="$T/migracoes_checksum"; rm -rf "$d"; cp -r "$base/migracoes" "$d"; echo "-- alteracao de ensaio (checksum)" >> "$d/015_restauracao_logica.sql"
    if HOMOLOG_MIGRACOES="$(cygpath -m "$d")" HOMOLOG_MIGRACOES_HOST="$d" HOMOLOG_EVID="$T" bash "$AQUI/implantacao/implantar.sh" > "$T/h09b.txt" 2>&1; then
        echo "(b) checksum divergente NAO foi detectado" >> "$f"; ok=1
    else echo "(b) checksum divergente: implantacao recusada ($(grep -m1 -oiE 'checksum mismatch[^.]*|flyway validate falhou' "$T/h09b.txt" "$T/implantacao.txt" | head -1))" >> "$f"; fi
    # (c) migration de ensaio que falha (fora do repositorio, versao 900): rollback completo, sem linha no historico
    local e="$T/migracoes_falha"; rm -rf "$e"; cp -r "$base/migracoes" "$e"
    printf -- '-- ENSAIO DA H09 (fora do repositorio): falha de implantacao simulada\nCREATE TABLE ensaio_falha_h09 (x int);\nSELECT 1/0;\n' > "$e/900_falha_simulada.sql"
    if HOMOLOG_MIGRACOES="$(cygpath -m "$e")" HOMOLOG_MIGRACOES_HOST="$e" HOMOLOG_EVID="$T" bash "$AQUI/implantacao/implantar.sh" > "$T/h09c.txt" 2>&1; then
        echo "(c) falha simulada NAO interrompeu a implantacao" >> "$f"; ok=1
    else echo "(c) falha simulada (sem elevacao): implantacao parou (rc=$?), sem nova tentativa" >> "$f"; fi
    [ "$(grep -c 'migrate -target\|-- 900_falha' "$T/h09c.txt")" -le 1 ] || { echo "(c) houve nova tentativa" >> "$f"; ok=1; }
    # (d) a mesma falha DENTRO de uma janela de elevacao: o trap revoga a elevacao
    printf '900 %s\n' "$(sha256sum "$e/900_falha_simulada.sql" | cut -d' ' -f1)" > "$T/elevacao_ensaio.txt"; : > "$T/janelas_de_elevacao.txt"
    HOMOLOG_ELEVACAO_ARQ="$T/elevacao_ensaio.txt" HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_MIGRACOES="$(cygpath -m "$e")" HOMOLOG_MIGRACOES_HOST="$e" HOMOLOG_EVID="$T" \
        bash "$AQUI/implantacao/implantar.sh" > "$T/h09d.txt" 2>&1; rd=$?
    if [[ "$HOMOLOG_ID" =~ ^ensaio- ]]; then
        if [ $rd = 0 ]; then echo "(d) falha elevada NAO interrompeu" >> "$f"; ok=1
        else echo "(d) falha simulada dentro da janela de elevacao: parou; janela: $(tr '\n' ' ' < "$T/janelas_de_elevacao.txt" | sed -E 's/[0-9T:.-]+Z //g')" >> "$f"; fi
        grep -q 'fim    versao=900 SUPERUSER revogado, sessoes encerradas, estado conferido' "$T/janelas_de_elevacao.txt" || { echo "(d) elevacao nao revogada pelo trap" >> "$f"; ok=1; }
    else
        # fora de ambiente descartavel a lista alternativa e recusada antes de qualquer acao (codigo 3, nenhuma janela)
        if [ $rd = 3 ] && grep -q 'so em ambiente descartavel' "$T/h09d.txt" && [ ! -s "$T/janelas_de_elevacao.txt" ]; then
            echo "(d) lista alternativa recusada fora de ensaio-* (codigo 3, nenhuma janela)" >> "$f"
        else echo "(d) lista alternativa NAO recusada fora de ensaio-* (rc=$rd)" >> "$f"; ok=1; fi
    fi
    [ "$(admin_sql postgres -c "SELECT rolsuper FROM pg_roles WHERE rolname = 'migrador_nekkora'")" = f ] || { echo "migrador ficou elevado" >> "$f"; ok=1; }
    # estado depois de (b)-(d): historico e esquema intactos
    [ "$(admin_sql nekkora -c "SELECT string_agg(version || ':' || success || ':' || checksum, ' ' ORDER BY installed_rank) FROM flyway_schema_history")" = "$hist0" ] \
        && echo "historico do Flyway identico ao de antes (versoes, sucesso, checksums); sem linha 900" >> "$f" || { echo "historico mudou" >> "$f"; ok=1; }
    [ "$(admin_sql nekkora -c "SELECT to_regclass('public.ensaio_falha_h09') IS NULL")" = t ] && echo "tabela do ensaio ausente (rollback)" >> "$f" || ok=1
    grep -nE 'flyway[^|]* (repair|baseline|clean)\b|-baselineOnMigrate=true' "$AQUI/implantacao/implantar.sh" "$AQUI/homologacao.sh" | grep -vE '^[^:]+:[0-9]+:\s*#' > "$T/h09_comandos.txt" && { echo "runner contem repair/baseline/clean" >> "$f"; ok=1; } \
        || echo "runner sem repair, baseline ou clean (baselineOnMigrate=false, cleanDisabled=true)" >> "$f"
    [ $ok = 0 ] && registra H09 OK agora "id em uso recusado sem criar recursos; checksum divergente recusado; falha simulada (sem e com elevacao) parou sem nova tentativa, revogou a elevacao e deixou historico e esquema intactos; runner sem repair/baseline/clean" \
               || registra H09 FALHA agora "ver h09_recuperacao.txt"
}

# ================================================================================ R015 restauracao com as identidades da homologacao
r015() {
    local ok=0 f="$EV/r015_restauracao.txt" dst="nekkora-homologacao-$HOMOLOG_ID-restauracao" pa
    : > "$f"
    pa="$(cat "$HOMOLOG_SEGREDOS_HOST/postgres_admin")"
    local pb; pb="$(cat "$HOMOLOG_SEGREDOS_HOST/senhas/backup")"
    # backup logico pela identidade de backup (TLS verify-full, a partir do container de backup)
    MSYS_NO_PATHCONV=1 compose run --rm -T -e PGPASSWORD="$pb" backup pg_dump "host=postgres port=5432 dbname=nekkora user=backup_nekkora sslmode=verify-full sslrootcert=/tls/ca.crt" -Fc > "$T/backup.dump" 2> "$T/r015_pgdump.txt" || { echo "pg_dump como backup_nekkora falhou: $(tail -2 "$T/r015_pgdump.txt")" >> "$f"; ok=1; }
    MSYS_NO_PATHCONV=1 compose run --rm -T -e PGPASSWORD="$pb" backup pg_dumpall --roles-only --no-role-passwords -l nekkora -d "host=postgres port=5432 dbname=nekkora user=backup_nekkora sslmode=verify-full sslrootcert=/tls/ca.crt" > "$T/papeis.sql" 2>> "$T/r015_pgdump.txt" || ok=1
    echo "backup: pg_dump -Fc e pg_dumpall --roles-only --no-role-passwords como backup_nekkora ($(wc -c < "$T/backup.dump") bytes)" >> "$f"
    # restauracao completa noutro cluster isolado, pelo administrador DESSE cluster (identidade de restauracao)
    MSYS_NO_PATHCONV=1 docker run -d --name "$dst" --label "nekkora.homologacao=$HOMOLOG_ID" --label nekkora.homologacao.papel=restauracao \
        -e POSTGRES_USER=admin_cluster -e POSTGRES_PASSWORD="$pa" -e POSTGRES_DB=postgres -e POSTGRES_INITDB_ARGS="--auth-local=scram-sha-256 --auth-host=scram-sha-256" postgres:16-alpine > /dev/null
    for i in $(seq 1 90); do [ "$(docker logs "$dst" 2>&1 | grep -c 'ready to accept connections')" -ge 2 ] && break; sleep 2; done
    grep -vE '^(CREATE|ALTER) ROLE admin_cluster( |;)' "$T/papeis.sql" | docker exec -i -e PGPASSWORD="$pa" "$dst" psql -X -q -v ON_ERROR_STOP=1 -U admin_cluster -d postgres > "$T/r015_papeis.txt" 2>&1 || { echo "papeis: $(tail -2 "$T/r015_papeis.txt")" >> "$f"; ok=1; }
    MSYS_NO_PATHCONV=1 docker cp "$(cygpath -w "$T/backup.dump")" "$dst:/tmp/backup.dump" > /dev/null
    MSYS_NO_PATHCONV=1 docker exec -e PGPASSWORD="$pa" "$dst" pg_restore -U admin_cluster -d postgres --create --exit-on-error /tmp/backup.dump > "$T/r015_restore.txt" 2>&1 \
        && echo "restauracao completa (pg_restore --create --exit-on-error): rc=0" >> "$f" || { echo "restauracao falhou: $(tail -3 "$T/r015_restore.txt")" >> "$f"; ok=1; }
    docker exec "$dst" rm -f /tmp/backup.dump
    # comparacoes: catalogo (sem normalizar: os mesmos nomes existem nos dois clusters) e dados linha a linha
    dsql() { docker exec -i -e PGPASSWORD="$pa" "$dst" psql -X -q -At -v ON_ERROR_STOP=1 -U admin_cluster -d nekkora "$@"; }
    admin_sql nekkora < "$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql" > "$T/r015_cat_a.txt" 2>&1
    dsql < "$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql" > "$T/r015_cat_b.txt" 2>&1
    diff "$T/r015_cat_a.txt" "$T/r015_cat_b.txt" > "$EV/r015_catalogo_diff.txt" && echo "catalogo identico ($(wc -l < "$T/r015_cat_a.txt") linhas), inclusive donos migrador_nekkora e os owners protegidos" >> "$f" || { echo "catalogo difere" >> "$f"; ok=1; }
    local tabelas; tabelas="$(admin_sql nekkora -c "SELECT string_agg(tablename, ' ' ORDER BY tablename) FROM pg_tables WHERE schemaname = 'public'")"
    local dif=0 linhas=0
    for t in $tabelas; do
        admin_sql nekkora -c "COPY public.$t TO STDOUT" < /dev/null | LC_ALL=C sort > "$T/ra.txt"
        dsql -c "COPY public.$t TO STDOUT" < /dev/null | LC_ALL=C sort > "$T/rb.txt"
        cmp -s "$T/ra.txt" "$T/rb.txt" || { dif=$((dif + 1)); echo "dados diferem: $t" >> "$f"; }
        linhas=$((linhas + $(wc -l < "$T/ra.txt")))
    done
    [ $dif = 0 ] && echo "dados identicos: $(echo $tabelas | wc -w) tabelas, $linhas linhas" >> "$f" || ok=1
    [ "$(dsql -c "SELECT array_to_string(proconfig, ';') FROM pg_proc WHERE oid = 'public.is_valid_command_contract(jsonb)'::regprocedure")" = 'search_path=pg_catalog, public' ] || ok=1
    docker rm -f -v "$dst" > /dev/null
    rm -f "$T/backup.dump" "$T/ra.txt" "$T/rb.txt"
    [ $ok = 0 ] && registra R015 OK agora "backup logico pela identidade backup_nekkora (TLS, BYPASSRLS, so leitura) restaurado por completo noutro cluster pelo administrador dele; catalogo e dados identicos (donos migrador_nekkora preservados)" \
               || registra R015 FALHA agora "ver r015_restauracao.txt"
}

# ================================================================================ H08 observabilidade (por ultimo)
h08() {
    local ok=0 f="$EV/h08_segredos.txt" m="$T/marcadores.txt"
    { cat "$T/segredos_vistos.txt"
      cat "$HOMOLOG_SEGREDOS_HOST/postgres_admin"; echo
      for s in "$HOMOLOG_SEGREDOS_HOST"/senhas/*; do cat "$s"; echo; done
      grep -hoE '(NEKKORA_MFA_CHAVES=k1:|NEKKORA_HMAC_CHAVE=)[^ ]+' "$HOMOLOG_SEGREDOS_HOST"/platform.env | sed -E 's/^[A-Z_]+=(k1:)?//'
      python -c "import json,sys; f=json.load(open(sys.argv[1])); [print(f[k]['senha']) for k in ('interno_a','externo_a','externo_b')]" "$T/fixture.json"
    } | grep -E '.{8,}' | sort -u > "$m"
    compose logs --no-color > "$T/logs_servicos.txt" 2>&1
    local n; n="$(wc -l < "$m")"
    # controle positivo: um marcador sintetico plantado num arquivo temporario TEM de ser encontrado
    local pos="$T/controle_positivo"; mkdir -p "$pos"; sed -n '1p' "$T/segredos_vistos.txt" > "$pos/plantado.txt"
    grep -rqFf "$m" "$pos" && echo "controle positivo: marcador plantado encontrado pelo detector" >> "$f" || { echo "controle positivo FALHOU" >> "$f"; ok=1; }
    rm -rf "$pos"
    local achados_logs achados_evid achados_criacao
    achados_logs="$(grep -cFf "$m" "$T/logs_servicos.txt" || true)"
    achados_evid="$(grep -rlFf "$m" "$EV" 2>/dev/null | wc -l)"
    achados_criacao="$(grep -rlFf "$m" --exclude-dir='testes-*' --exclude-dir=migracoes "$AQUI/saidas/$HOMOLOG_ID" 2>/dev/null | wc -l)"
    echo "marcadores: $n (segredos sinteticos vistos pelo cliente, senhas e chaves do ambiente); logs de gateway, Platform, Portal, PostgreSQL e Redis: $(wc -l < "$T/logs_servicos.txt") linhas" >> "$f"
    echo "linhas de log com marcador: $achados_logs; arquivos de evidencia com marcador: $achados_evid (testes) + $achados_criacao (criacao)" >> "$f"
    [ "$achados_logs" = 0 ] && [ "$achados_evid" = 0 ] && [ "$achados_criacao" = 0 ] || ok=1
    [ $ok = 0 ] && registra H08 OK agora "$n marcadores ausentes de $(wc -l < "$T/logs_servicos.txt") linhas de log de todos os servicos e de todas as evidencias; detector com controle positivo" \
               || registra H08 FALHA agora "ver h08_segredos.txt (so contagens; nenhum valor impresso)"
}

# HOMOLOG_TESTES (opcional, suporte de teste P3-IT): subconjunto, ex. "h02"; padrao = a suite inteira, como antes
for t_ in ${HOMOLOG_TESTES:-h01 h02 h03 h04 h05 h06 h07 h09 r015 h08}; do
    case " h01 h02 h03 h04 h05 h06 h07 h09 r015 h08 " in *" $t_ "*) "$t_";; *) echo "teste desconhecido: $t_" >&2; exit 2;; esac
done
registra "reutilizada" "-" "evidencia anterior" "suites SQL 012/013/014, restauracao-015 original, atualizacao-populada, 58 testes Maven e E2E 6/6 do commit $HOMOLOG_COMMIT (docs/relatorios/2026-09-26-validacao-api-v1.md); nao repetidas: a homologacao nao muda o schema nem o codigo"
echo
column -t -s '|' "$RESUMO" 2>/dev/null || cat "$RESUMO"
rm -f "$T/logs_servicos.txt" "$T/h06_pg.log" "$T/cat_ref.txt" "$T/cat_homolog.txt" "$T/r015_cat_a.txt" "$T/r015_cat_b.txt"
grep -q '|FALHA|' "$RESUMO" && exit 1
echo "evidencias: $EV"
