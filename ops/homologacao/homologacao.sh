#!/usr/bin/env bash
# Homologacao INTERNA da API v1 - criar, testar e destruir um ambiente isolado e reproduzivel.
#
#   ops/homologacao/homologacao.sh criar    [id]   cria recursos, segredos (fora do Git), bootstrap, implantacao e servicos
#   ops/homologacao/homologacao.sh testar   <id>   executa H01-H09 e grava evidencias sanitizadas
#   ops/homologacao/homologacao.sh ensaio-interrupcao <id> ["I9 I10"]  controles da elevacao (so id ensaio-*)
#   ops/homologacao/homologacao.sh estado   <id>   LIBERADO ou BLOQUEADO (trava, elevacao/sessao residual, historico com falha)
#   ops/homologacao/homologacao.sh implantar <id>  aplica as versoes pendentes (ex.: concluir instalacao interrompida)
#   ops/homologacao/homologacao.sh recuperar <id>  recupera o MIGRADOR apos interrupcao (mesma trava no banco da
#                                                  implantacao); a liberacao exige tambem a instalacao completa
#   ops/homologacao/homologacao.sh destruir <id>   remove SO os recursos com o rotulo nekkora.homologacao=<id>
#
# Variaveis (opcionais): HOMOLOG_HOST (nome da origem; padrao homologacao.nekkora.test), HOMOLOG_PORTA (55643),
# HOMOLOG_BIND (interface publicada; padrao 127.0.0.1 - para a equipe, a interface da rede interna da empresa),
# HOMOLOG_SEGREDOS (padrao $HOME/.nekkora/homologacao/<id>, fora do repositorio).
# Nunca toca a stack principal (nekkora-postgres / 5432), nem recursos sem o rotulo desta execucao; nunca usa limpeza
# por prefixo, "down -v" generico ou "prune".
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../.." && pwd)"
ACAO="${1:?uso: homologacao.sh criar [id] | testar <id> | destruir <id>}"
falha() { echo "ERRO: $*" >&2; exit 1; }
mixed() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

carrega_ambiente() {
    HOMOLOG_ID="$1"
    ESTADO="$AQUI/saidas/$HOMOLOG_ID/ambiente.env"
    [ -f "$ESTADO" ] || falha "ambiente $HOMOLOG_ID desconhecido (sem $ESTADO)"
    set -a; . "$ESTADO"; set +a
}

prepara_funcoes() {
    export HOMOLOG_ID HOMOLOG_PROJETO HOMOLOG_SEGREDOS HOMOLOG_COMMIT HOMOLOG_PORTA HOMOLOG_BIND HOMOLOG_HOST HOMOLOG_MIGRACOES HOMOLOG_SQL
    PGPASSWORD="$(cat "$HOMOLOG_SEGREDOS_HOST/postgres_admin")"
    export PGPASSWORD
    COMPOSE_ARQ="$(mixed "$AQUI/compose.homolog.yml")"   # formato C:/...: vale com e sem MSYS_NO_PATHCONV
    export COMPOSE_ARQ
    compose() { docker compose -f "$COMPOSE_ARQ" "$@"; }
    admin_sql() { local db="$1"; shift; compose exec -T -e PGPASSWORD postgres psql -X -q -At -v ON_ERROR_STOP=1 -U admin_cluster -d "$db" "$@"; }
    export -f compose admin_sql
    export AQUI
    . "$AQUI/lib/estado.sh"
    TRAVA="$AQUI/saidas/$HOMOLOG_ID/implantacao.trava"
}

case "$ACAO" in
# ======================================================================================================== criar
criar)
    HOMOLOG_ID="${2:-hml-$(date -u +%Y%m%d-%H%M%S)}"
    [[ "$HOMOLOG_ID" =~ ^[a-z0-9-]{3,40}$ ]] || falha "id invalido (a-z, 0-9, -)"
    HOMOLOG_PROJETO="nekkora-homologacao-$HOMOLOG_ID"
    HOMOLOG_HOST="${HOMOLOG_HOST:-homologacao.nekkora.test}"
    HOMOLOG_PORTA="${HOMOLOG_PORTA:-55643}"
    HOMOLOG_BIND="${HOMOLOG_BIND:-127.0.0.1}"
    SEG="${HOMOLOG_SEGREDOS:-$HOME/.nekkora/homologacao/$HOMOLOG_ID}"
    EVID="$AQUI/saidas/$HOMOLOG_ID"

    # --- protecao: nada e criado antes destas conferencias ---
    [ -e "$EVID" ] && falha "ja existe $EVID (id em uso)"
    [ -e "$SEG" ] && falha "ja existem segredos em $SEG (id em uso)"
    [ -z "$(docker ps -aq --filter "label=nekkora.homologacao=$HOMOLOG_ID")" ] || falha "ha containers com o rotulo $HOMOLOG_ID"
    [ -z "$(docker volume ls -q --filter "label=nekkora.homologacao=$HOMOLOG_ID")" ] || falha "ha volumes com o rotulo $HOMOLOG_ID"
    [ -z "$(docker network ls -q --filter "label=nekkora.homologacao=$HOMOLOG_ID")" ] || falha "ha redes com o rotulo $HOMOLOG_ID"
    [ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$HOMOLOG_PROJETO")" ] || falha "projeto $HOMOLOG_PROJETO ja existe"
    for sub in 10.252.10. 10.252.11.; do
        if docker network ls -q | xargs -r docker network inspect -f '{{range .IPAM.Config}}{{.Subnet}} {{end}}' | grep -q "$sub"; then
            falha "sub-rede ${sub}0/24 ja usada por outra rede Docker"
        fi
    done
    [ "$HOMOLOG_PORTA" != 5432 ] || falha "porta da stack principal"
    if docker ps --format '{{.Ports}}' | grep -q ":$HOMOLOG_PORTA->"; then falha "porta $HOMOLOG_PORTA publicada por outro container"; fi
    if (exec 3<>"/dev/tcp/127.0.0.1/$HOMOLOG_PORTA") 2>/dev/null; then falha "porta $HOMOLOG_PORTA em uso"; fi
    # imagem da API = conteudo do commit integrado (fontes principais sem alteracao desde ele)
    HOMOLOG_COMMIT="$(git -C "$REPO" rev-parse --short=7 HEAD)"
    BASE_API="$(git -C "$REPO" log -1 --format=%h -- api/pom.xml api/nekkora-core/src/main api/nekkora-platform/src/main api/nekkora-portal/src/main api/nekkora-core/pom.xml api/nekkora-platform/pom.xml api/nekkora-portal/pom.xml api/e2e/Dockerfile)"
    git -C "$REPO" diff --quiet HEAD -- api/pom.xml api/*/pom.xml api/*/src/main api/e2e/Dockerfile || falha "fontes principais da API com alteracao nao commitada"
    HOMOLOG_COMMIT="$BASE_API"

    mkdir -p "$EVID" "$SEG/tls"
    HOMOLOG_SEGREDOS="$(mixed "$SEG")"
    HOMOLOG_SEGREDOS_HOST="$SEG"
    HOMOLOG_MIGRACOES="$(mixed "$EVID/migracoes")"
    HOMOLOG_SQL="$(mixed "$AQUI/sql")"
    cat > "$EVID/ambiente.env" <<EOF
# estado NAO sensivel do ambiente $HOMOLOG_ID (os segredos ficam em HOMOLOG_SEGREDOS, fora do Git)
HOMOLOG_ID=$HOMOLOG_ID
HOMOLOG_PROJETO=$HOMOLOG_PROJETO
HOMOLOG_HOST=$HOMOLOG_HOST
HOMOLOG_PORTA=$HOMOLOG_PORTA
HOMOLOG_BIND=$HOMOLOG_BIND
HOMOLOG_COMMIT=$HOMOLOG_COMMIT
HOMOLOG_SEGREDOS=$HOMOLOG_SEGREDOS
HOMOLOG_SEGREDOS_HOST=$HOMOLOG_SEGREDOS_HOST
HOMOLOG_MIGRACOES=$HOMOLOG_MIGRACOES
HOMOLOG_SQL=$HOMOLOG_SQL
HOMOLOG_CRIADO_EM=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
    { echo "## inventario ANTES da criacao ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
      docker ps -a --format '{{.ID}} {{.Names}} {{.Image}} projeto={{.Label "com.docker.compose.project"}}'
      docker volume ls --format '{{.Name}}'; docker network ls --format '{{.ID}} {{.Name}}'; } > "$EVID/inventario_antes.txt"
    docker inspect nekkora-postgres --format 'principal: {{.Name}} started={{.State.StartedAt}} restarts={{.RestartCount}}' > "$EVID/principal_antes.txt" 2>/dev/null || echo "principal: ausente" > "$EVID/principal_antes.txt"

    echo "== segredos e certificados (fora do Git: $SEG)"
    python "$AQUI/lib/segredos.py" gerar "$SEG" "https://$HOMOLOG_HOST:$HOMOLOG_PORTA"
    (
        cd "$SEG/tls"
        export MSYS2_ARG_CONV_EXCL='*'
        printf 'basicConstraints=critical,CA:true\nkeyUsage=critical,keyCertSign,cRLSign\n' > ca.ext
        openssl req -x509 -newkey rsa:3072 -nodes -days 30 -subj "/CN=Nekkora Homologacao CA $HOMOLOG_ID" -addext "basicConstraints=critical,CA:true" \
            -addext "keyUsage=critical,keyCertSign,cRLSign" -keyout ca.key -out ca.crt 2>/dev/null
        for nome in gateway:"$HOMOLOG_HOST" postgres:postgres redis:redis; do
            arq="${nome%%:*}"; dns="${nome#*:}"
            printf 'subjectAltName=DNS:%s\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature,keyEncipherment\n' "$dns" > "$arq.ext"
            openssl req -newkey rsa:2048 -nodes -subj "/CN=$dns" -keyout "$arq.key" -out "$arq.csr" 2>/dev/null
            openssl x509 -req -in "$arq.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days 30 -extfile "$arq.ext" -out "$arq.crt" 2>/dev/null
            rm -f "$arq.csr" "$arq.ext"
        done
        rm -f ca.ext ca.srl
    ) || falha "certificados"

    echo "== migrations 001-015 (copia imutavel desta execucao)"
    mkdir -p "$EVID/migracoes"
    for n in $(seq -w 1 15); do
        f=("$REPO"/db/migrations/0${n}_*.sql)
        [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
        cp "${f[0]}" "$EVID/migracoes/"
    done
    (cd "$EVID/migracoes" && sha256sum *.sql) > "$EVID/migracoes_sha256.txt"
    export HOMOLOG_MIGRACOES_HOST="$EVID/migracoes" HOMOLOG_EVID="$EVID"

    prepara_funcoes

    echo "== build (jars e imagens identificados por $HOMOLOG_COMMIT)"
    (cd "$REPO/api" && ./mvnw -q -DskipTests package && ./mvnw -q -DskipTests test-compile -pl nekkora-platform) > "$EVID/build.txt" 2>&1 || falha "build (ver build.txt)"
    compose build platform portal >> "$EVID/build.txt" 2>&1 || falha "build das imagens"

    echo "== PostgreSQL e Redis"
    compose up -d --wait postgres redis > "$EVID/subida_base.txt" 2>&1 || { compose logs --no-color postgres redis > "$EVID/falha_subida.txt" 2>&1; falha "postgres/redis (ver falha_subida.txt)"; }

    echo "== bootstrap (admin_cluster, socket local)"
    VM="$(python "$AQUI/lib/segredos.py" scram "$SEG/senhas/migrador" | tr -d '\r')"
    VB="$(python "$AQUI/lib/segredos.py" scram "$SEG/senhas/backup" | tr -d '\r')"
    admin_sql postgres -v verif_migrador="$VM" -v verif_backup="$VB" < "$AQUI/sql/bootstrap.sql" > "$EVID/bootstrap.txt" 2>&1 || { cat "$EVID/bootstrap.txt"; falha "bootstrap"; }

    echo "== implantacao 001-015 pela identidade dedicada (elevacao so nas versoes declaradas)"
    HOMOLOG_AUTORIZA_ELEVACAO=1 bash "$AQUI/implantacao/implantar.sh"

    echo "== senhas dos servicos (verificadores SCRAM)"
    VP="$(python "$AQUI/lib/segredos.py" scram "$SEG/senhas/platform" | tr -d '\r')"
    VO="$(python "$AQUI/lib/segredos.py" scram "$SEG/senhas/portal" | tr -d '\r')"
    admin_sql nekkora -v verif_platform="$VP" -v verif_portal="$VO" < "$AQUI/sql/senhas_servicos.sql" > "$EVID/senhas_servicos.txt" 2>&1 || falha "senhas dos servicos"

    confere_liberacao "$TRAVA" > "$EVID/liberacao.txt" 2>&1 || { cat "$EVID/liberacao.txt"; falha "ambiente BLOQUEADO depois da implantacao"; }
    echo "== Platform, Portal e gateway"
    compose up -d platform portal gateway > "$EVID/subida_servicos.txt" 2>&1 || falha "subida dos servicos"
    CA="$SEG/tls/ca.crt"
    for i in $(seq 1 90); do
        a="$(curl -s --ssl-no-revoke -o /dev/null -w '%{http_code}' --cacert "$CA" --resolve "$HOMOLOG_HOST:$HOMOLOG_PORTA:127.0.0.1" "https://$HOMOLOG_HOST:$HOMOLOG_PORTA/api/v1/csrf" || true)"
        b="$(curl -s --ssl-no-revoke -o /dev/null -w '%{http_code}' --cacert "$CA" --resolve "$HOMOLOG_HOST:$HOMOLOG_PORTA:127.0.0.1" "https://$HOMOLOG_HOST:$HOMOLOG_PORTA/portal/api/v1/o/00000000-0000-4000-8000-000000000000/workspaces" || true)"
        [ "$a" = 200 ] && [ "$b" = 401 ] && break
        [ "$i" -lt 90 ] || { compose logs --no-color platform portal gateway > "$EVID/falha_servicos.txt" 2>&1; falha "servicos nao responderam (platform=$a portal=$b; ver falha_servicos.txt)"; }
        sleep 2
    done
    echo "AMBIENTE $HOMOLOG_ID CRIADO: https://$HOMOLOG_HOST:$HOMOLOG_PORTA (publicado em $HOMOLOG_BIND). Proximo passo: homologacao.sh testar $HOMOLOG_ID"
    ;;

# ======================================================================================================= testar
testar)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    confere_liberacao "$TRAVA" || falha "ambiente BLOQUEADO: testes e liberacao recusados (homologacao.sh recuperar $HOMOLOG_ID)"
    exec bash "$AQUI/testes/executar.sh"
    ;;

# ============================================================================================ ensaio-interrupcao
ensaio-interrupcao)
    carrega_ambiente "${2:?id}"
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] || falha "o ensaio de interrupcao so roda em ambiente descartavel (id ensaio-*)"
    prepara_funcoes
    export -f estado_migrador
    export HOMOLOG_ENSAIO_CASOS="${3:-}"   # opcional: casos a executar, ex. "I9 I10" (padrao: todos)
    exec bash "$AQUI/testes/interrupcao.sh"
    ;;

# ======================================================================================================= estado
estado)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    confere_liberacao "$TRAVA"
    ;;

# ==================================================================================================== implantar
implantar)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    export HOMOLOG_EVID="$AQUI/saidas/$HOMOLOG_ID/implantacao-$(date -u +%Y%m%dT%H%M%SZ)" HOMOLOG_MIGRACOES_HOST="$AQUI/saidas/$HOMOLOG_ID/migracoes"
    mkdir -p "$HOMOLOG_EVID"
    set +e
    bash "$AQUI/implantacao/implantar.sh"; rc=$?
    echo "-- liberacao depois da implantacao:"
    confere_liberacao "$TRAVA"; rc_lib=$?
    # codigo 0 SO com implantacao concluida E ambiente LIBERADO na conferencia final
    [ "$rc" = 0 ] || exit "$rc"
    [ "$rc_lib" = 0 ] || { echo "ERRO: implantacao concluida, mas a conferencia final recusou a liberacao: ambiente BLOQUEADO" >&2; exit 7; }
    exit 0
    ;;

# ==================================================================================================== recuperar
# Recupera o MIGRADOR (revoga, encerra sessoes, confere, remove a trava local), disputando a MESMA trava no banco da
# implantacao: nunca age enquanto uma implantacao ou outra recuperacao estiver ativa. Nao declara o ambiente apto:
# isso e a liberacao (migrador recuperado E instalacao completa e integra). Codigo 0 = migrador recuperado.
recuperar)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    REC="$AQUI/saidas/$HOMOLOG_ID/recuperacao-$(date -u +%Y%m%dT%H%M%SZ).txt"
    {
        set +e
        echo "== recuperacao $(date -u +%Y-%m-%dT%H:%M:%SZ) (independente do runner)"
        echo "antes (migrador):"; migrador_recuperado "$TRAVA" | sed 's/^/  /'
        if [ -d "$TRAVA" ]; then
            dono="$(cat "$TRAVA/dono" 2>/dev/null)"
            pid="$(echo "$dono" | sed -nE 's/.*pid=([0-9]+).*/\1/p')"; maq="$(echo "$dono" | sed -nE 's/.*host=([^ ]+).*/\1/p')"
            if [ "$maq" = "$(hostname)" ] && [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
                echo "RECUSADO: o runner da trava local ainda esta ativo (pid $pid). Nada foi alterado."; exit 1
            fi
            if [ "$maq" != "$(hostname)" ] && [ "${HOMOLOG_CONFIRMA_RUNNER_INATIVO:-}" != 1 ]; then
                echo "RECUSADO: trava local de outro host ($maq); confirme que o runner nao esta ativo (HOMOLOG_CONFIRMA_RUNNER_INATIVO=1)."; exit 1
            fi
        fi
        # containers de implantacao DESTE ambiente que ja existiam ANTES de obter a trava (de um executor anterior); um
        # container criado depois (por um novo vencedor, se esta sessao cair) nunca entra na lista
        restantes="$(docker ps -aq --filter "label=com.docker.compose.project=$HOMOLOG_PROJETO" --filter "label=com.docker.compose.service=flyway")"
        if ! adquire_trava_banco; then
            echo "RECUSADO: implantacao ou outra recuperacao ativa (trava no banco ocupada) ou banco sem resposta. Nada foi alterado."; exit 1
        fi
        echo "trava no banco obtida: nenhuma implantacao ou recuperacao ativa"
        for c in $restantes; do
            echo "removendo container de implantacao restante $c"; docker rm -f "$c" > /dev/null
        done
        # revogar e encerrar sessoes PELA SESSAO DA TRAVA: se ela cair, nada e revogado nem encerrado
        if ! sql_na_trava "ALTER ROLE migrador_nekkora NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS NOREPLICATION;
                PERFORM pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'migrador_nekkora'"; then
            libera_trava_banco; echo "FALHA: revogacao nao executada (trava no banco perdida ou banco sem resposta). Nada mais foi feito. Ambiente continua BLOQUEADO."; exit 1
        fi
        echo "revogacao e encerramento das sessoes do migrador executados pela sessao da trava"
        sleep 1
        e="$(estado_migrador)"
        echo "estado do migrador depois da revogacao: $e"
        if [ "$e" != "0|0|0" ]; then libera_trava_banco; echo "FALHA: estado nao conferiu limpo. Ambiente continua BLOQUEADO; trava local mantida."; exit 1; fi
        if ! confere_trava; then libera_trava_banco; echo "FALHA: trava no banco perdida antes de remover a trava local. Ambiente continua BLOQUEADO."; exit 1; fi
        [ -d "$TRAVA" ] && rm -rf "$TRAVA" && echo "trava local removida (estado conferido)"
        libera_trava_banco
        echo "depois (migrador):"; migrador_recuperado "$TRAVA" | sed 's/^/  /'
        echo "depois (ambiente):"
        if ambiente_apto | sed 's/^/  /'; then :; fi
        if confere_liberacao "$TRAVA" > /dev/null; then echo "RESULTADO: migrador recuperado; ambiente LIBERADO"
        else echo "RESULTADO: migrador recuperado; ambiente CONTINUA BLOQUEADO (ver 'depois (ambiente)'; instalacao incompleta -> homologacao.sh implantar $HOMOLOG_ID)"; fi
    } 2>&1 | tee "$REC"
    rc_rec=${PIPESTATUS[0]}
    [ "$rc_rec" = 0 ] || exit "$rc_rec"
    migrador_recuperado "$TRAVA" > /dev/null || exit 1
    ;;

# ===================================================================================================== destruir
destruir)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    echo "Recursos com o rotulo nekkora.homologacao=$HOMOLOG_ID:"
    docker ps -a --filter "label=nekkora.homologacao=$HOMOLOG_ID" --format '  container {{.Names}}'
    docker volume ls --filter "label=nekkora.homologacao=$HOMOLOG_ID" --format '  volume {{.Name}}'
    docker network ls --filter "label=nekkora.homologacao=$HOMOLOG_ID" --format '  rede {{.Name}}'
    docker images --filter "label=nekkora.homologacao=$HOMOLOG_ID" --format '  imagem {{.Repository}}:{{.Tag}}'
    [ "${HOMOLOG_CONFIRMA_DESTRUIR:-}" = "$HOMOLOG_ID" ] || falha "para remover, repita com HOMOLOG_CONFIRMA_DESTRUIR=$HOMOLOG_ID"
    for c in $(docker ps -aq --filter "label=nekkora.homologacao=$HOMOLOG_ID"); do docker rm -f "$c" >/dev/null; done
    for v in $(docker volume ls -q --filter "label=nekkora.homologacao=$HOMOLOG_ID"); do docker volume rm "$v" >/dev/null; done
    for n in $(docker network ls -q --filter "label=nekkora.homologacao=$HOMOLOG_ID"); do docker network rm "$n" >/dev/null; done
    for i in $(docker images -q --filter "label=nekkora.homologacao=$HOMOLOG_ID" | sort -u); do docker rmi -f "$i" >/dev/null; done
    echo "removido. Os segredos em $HOMOLOG_SEGREDOS_HOST NAO sao apagados por este comando (descarte manual, README)."
    ;;
*) falha "acao desconhecida: $ACAO";;
esac
