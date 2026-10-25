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
# Recuperacao emergencial de MFA - 016 (desenho P1a; SO ambientes ensaio-*):
#   ops/homologacao/homologacao.sh criar <id> com HOMOLOG_ATE=016|021: aplica SO 001-015 e PARA (016+ pendentes, BLOQUEADO)
#   HOMOLOG_AUTORIZA_ELEVACAO=1 ops/homologacao/homologacao.sh implantar <id>   aplica 016 (ou 016-021), pre-voo automatico
#                                                            em cada versao de vinculo obrigatorio (016-020; 021 sem janela)
#   ops/homologacao/homologacao.sh concluir-criacao <id>    depois da implantacao LIBERADA: senhas, volumes e servicos
#   ops/homologacao/homologacao.sh preparar-016 <id>        senhas dos servicos da 016, operadores (DBA + bootstrap),
#                                                            organizacao sintetica, credenciais e contatos
#   ops/homologacao/homologacao.sh testar-016 <id>          cenarios B5-B8a, B10-B12 do plano (testes/h016.sh)
#   ops/homologacao/homologacao.sh operacao <id> <classe> <args...>   job "operacao" (CLI real ou ferramenta)
#   ops/homologacao/homologacao.sh caixa <id> listar|ler <arquivo>|controle <regra>|exportar-indice|liberar-trava
#   ops/homologacao/homologacao.sh fixture <id> contatos <uuid,uuid,...> | solicitacao <uuid> [horas] | conferir <execucao>
#   ops/homologacao/homologacao.sh validar-config          validacao ESTATICA (sem criar recursos)
# Semeadura pre-014 (R2b-0; SO ensaio-*, com o runner pausado por HOMOLOG_ENSAIO_PAUSA=antes-de-elevar@014:<arquivo>):
#   ops/homologacao/homologacao.sh semear-pre014 <id>                 18 sessoes sinteticas (sql/fixture_014_pre.sql)
#   ops/homologacao/homologacao.sh conferir-semente014 <id> antes|depois   conferencia somente leitura (testes/semente_014.sh)
# Ensaio-frontend (F1.5; SO ambiente criado com a aplicacao web, HOMOLOG_FRONTEND_WEB, e LIBERADO):
#   ops/homologacao/homologacao.sh semear-frontend <id> [estado]   massa sintetica numa transacao (lib/semear_frontend.py,
#                                                    sql/semear_frontend.sql), como admin_cluster; credenciais em
#                                                    <segredos>/frontend (fora do Git), gravadas ANTES da transacao
#   ops/homologacao/homologacao.sh jornadas-frontend <id> preparar-contas|executar [--mutacoes] [--inatividade-real]
#                                                    jornadas HTTP pelo gateway (TLS conferido pela CA do ambiente)
#   ops/homologacao/homologacao.sh jornadas-frontend <id> executar --somente <lista> --vinculo-stop <texto>
#                                                    (P-B-C1) SO as jornadas nomeadas (continuacao de STOP), pelas
#                                                    guardas de lib/selecao_gateway.sh (P-B-C1-R1): coleta do gateway
#                                                    verificada; >= 31 s sem requisicao imediatamente antes; UMA chamada;
#                                                    conferencia de trafego separada do resultado funcional (codigos 3/4/5)
#   ops/homologacao/homologacao.sh frontend-sessao <id> absoluta    operacao DECLARADA: encurta o prazo absoluto das
#                                                    sessoes ativas SO da conta exclusiva expira@ (sql/sessao_frontend.sql)
# Validacao administrativa da F6a (F6a-V-T; mesmo ambiente, mesmas exigencias):
#   ops/homologacao/homologacao.sh semear-f6a <id> [estado]   massa COMPLEMENTAR f6av-* numa transacao (lib/semear_f6a.py,
#                                                    sql/semear_f6a.sql), como admin_cluster, sobre a da F1.5 CONCLUIDA
#   ops/homologacao/homologacao.sh validar-f6a <id> preparar|conferir <fase>   pela API, sob as guardas de
#                                                    lib/selecao_gateway.sh (pre-condicao de 31 s, UMA chamada, conferencia
#                                                    de trafego); fases: inicial|suspensao|reativacao|stepup-negado|mfa|revogacao
#   ops/homologacao/homologacao.sh validar-f6a <id> anti-replay | foto <rotulo> | comparar <antes> <depois> [opcoes] |
#                                                    intencoes <desde> [TIPO=N ...]   (sem trafego no gateway)
#   bash lib/frontend_atualizar.sh preparar|aplicar|conferir|reverter ...   atualizacao do build no gateway (D-V-1 (a))
#
# Variaveis (opcionais): HOMOLOG_HOST (nome da origem; padrao homologacao.nekkora.test), HOMOLOG_PORTA (55643),
# HOMOLOG_BIND (interface publicada; padrao 127.0.0.1 - para a equipe, a interface da rede interna da empresa),
# HOMOLOG_SEGREDOS (padrao $HOME/.nekkora/homologacao/<id>, fora do repositorio),
# HOMOLOG_ATE (ultima migration: 015, padrao, o desenho anterior; 016, SO para id ensaio-* e com a linha de P3; ou 021
# (missao PLATFORM-API-V1-003), SO para id ensaio-*, com as linhas 016-020 vinculadas ao id e o transporte de homologacao;
# 015 com HOMOLOG_REDE_INTERNA/BORDA fora do padrao SO em id ensaio-*, com pg_hba/nginx gerados - compose.015-rede.yml),
# HOMOLOG_REDE_INTERNA / HOMOLOG_REDE_BORDA (prefixos /24; padrao 10.252.10 / 10.252.11; com HOMOLOG_ATE=015 so o
# padrao, salvo em ensaio-*), HOMOLOG_TRANSPORTE (vazio ou "homologacao": transporte de e-mail de homologacao, so ensaio-* com a 016).
# Aplicacao web no gateway (ensaio-frontend, F1.5; SO ensaio-* com HOMOLOG_ATE=016): HOMOLOG_FRONTEND_WEB (repositorio do
# web, com node_modules/nekkora-lib ligado), HOMOLOG_FRONTEND_COMMIT e HOMOLOG_FRONTEND_LIB_COMMIT (sha completos do PAR
# validado; as duas copias de trabalho limpas e nesses commits). O criar compila a lib e o web, copia o build para
# saidas/<id>/frontend-dist (registro em frontend_dist.txt) e o gateway o serve (compose.frontend.yml, lib/gateway_frontend.py).
# Nunca toca a stack principal (nekkora-postgres / 5432), nem recursos sem o rotulo desta execucao; nunca usa limpeza
# por prefixo, "down -v" generico ou "prune".
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../.." && pwd)"
ACAO="${1:?uso: homologacao.sh criar [id] | testar <id> | destruir <id>}"
falha() { echo "ERRO: $*" >&2; exit 1; }
mixed() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
# ambiente com a 016 ou depois (HOMOLOG_ATE 016 ou 021): mesma topologia (pg_hba/nginx gerados, CA operacional, job de
# operacao, parada antes da 016). O desenho anterior (015) nao muda.
com_016() { [ "$((10#${HOMOLOG_ATE:-015}))" -ge 16 ]; }

carrega_ambiente() {
    HOMOLOG_ID="$1"
    ESTADO="$AQUI/saidas/$HOMOLOG_ID/ambiente.env"
    [ -f "$ESTADO" ] || falha "ambiente $HOMOLOG_ID desconhecido (sem $ESTADO)"
    set -a; . "$ESTADO"; set +a
}

prepara_funcoes() {
    export HOMOLOG_ID HOMOLOG_PROJETO HOMOLOG_SEGREDOS HOMOLOG_COMMIT HOMOLOG_PORTA HOMOLOG_BIND HOMOLOG_HOST HOMOLOG_MIGRACOES HOMOLOG_SQL
    # ambientes anteriores (ex.: hml03) nao tem estas variaveis: valem os padroes do desenho anterior
    HOMOLOG_REDE_INTERNA="${HOMOLOG_REDE_INTERNA:-10.252.10}"; HOMOLOG_REDE_BORDA="${HOMOLOG_REDE_BORDA:-10.252.11}"
    HOMOLOG_COMPOSE_EXTRA="${HOMOLOG_COMPOSE_EXTRA:-}"; HOMOLOG_CONFIG="${HOMOLOG_CONFIG:-}"; HOMOLOG_TRANSPORTE="${HOMOLOG_TRANSPORTE:-}"
    HOMOLOG_FRONTEND_DIST="${HOMOLOG_FRONTEND_DIST:-}"
    export HOMOLOG_REDE_INTERNA HOMOLOG_REDE_BORDA HOMOLOG_COMPOSE_EXTRA HOMOLOG_CONFIG HOMOLOG_TRANSPORTE HOMOLOG_FRONTEND_DIST
    PGPASSWORD="$(cat "$HOMOLOG_SEGREDOS_HOST/postgres_admin")"
    export PGPASSWORD
    COMPOSE_ARQ="$(mixed "$AQUI/compose.homolog.yml")"   # formato C:/...: vale com e sem MSYS_NO_PATHCONV
    # sobreposicoes do ambiente (compose.016.yml, compose.caixa.yml), gravadas em ambiente.env na criacao. Caminhos
    # sem espaco (o repositorio e a pasta ops/homologacao): a lista vai numa variavel exportavel, sem array.
    COMPOSE_F="-f $COMPOSE_ARQ"
    for x in $HOMOLOG_COMPOSE_EXTRA; do COMPOSE_F="$COMPOSE_F -f $(mixed "$AQUI/$x")"; done
    export COMPOSE_ARQ COMPOSE_F
    compose() { docker compose $COMPOSE_F "$@"; }
    admin_sql() { local db="$1"; shift; compose exec -T -e PGPASSWORD postgres psql -X -q -At -v ON_ERROR_STOP=1 -U admin_cluster -d "$db" "$@"; }
    export -f compose admin_sql
    export AQUI
    . "$AQUI/lib/estado.sh"
    TRAVA="$AQUI/saidas/$HOMOLOG_ID/implantacao.trava"
}

# passos da criacao DEPOIS da implantacao (senhas, volumes, liberacao, servicos): usados pelo criar e pelo
# concluir-criacao (ambientes com a 016, que param antes dela). Exige SEG e EVID.
pos_implantacao() {
    echo "== senhas dos servicos (verificadores SCRAM)"
    VP="$(python "$AQUI/lib/segredos.py" scram "$SEG/senhas/platform" | tr -d '\r')"
    VO="$(python "$AQUI/lib/segredos.py" scram "$SEG/senhas/portal" | tr -d '\r')"
    VOPS=()
    if [ -f "$SEG/senhas/ops_ca" ]; then
        VOPS=(-v verif_ops_ca="$(python "$AQUI/lib/segredos.py" scram "$SEG/senhas/ops_ca" | tr -d '\r')"
              -v verif_ops_intake="$(python "$AQUI/lib/segredos.py" scram "$SEG/senhas/ops_intake" | tr -d '\r')")
    fi
    admin_sql nekkora -v verif_platform="$VP" -v verif_portal="$VO" "${VOPS[@]}" < "$AQUI/sql/senhas_servicos.sql" > "$EVID/senhas_servicos.txt" 2>&1 || falha "senhas dos servicos"
    if com_016; then
        # volumes EXCLUSIVOS do ensaio: dono e permissao aplicados e CONFERIDOS no Linux do Docker
        r="$(compose --profile operacao run --rm -T operadores-init 2>/dev/null | tr -d '\r' | tail -1)"
        [ "$r" = "nekkora 700" ] || falha "volume de operadores sem dono/permissao esperados (obtido: $r)"
        echo "volume operadores: $r" > "$EVID/volumes.txt"
    fi
    if [ "$HOMOLOG_TRANSPORTE" = homologacao ]; then
        r="$(compose --profile caixa run --rm -T caixa-init 2>/dev/null | tr -d '\r' | tail -1)"
        [ "$r" = "nekkora 700" ] || falha "volume da caixa sem dono/permissao esperados (obtido: $r)"
        echo "volume caixa: $r" >> "$EVID/volumes.txt"
    fi

    confere_liberacao "$TRAVA" > "$EVID/liberacao.txt" 2>&1 || { cat "$EVID/liberacao.txt"; falha "ambiente BLOQUEADO depois da implantacao"; }
    echo "== Platform, Portal e gateway"
    # R2b-1a: a porta pode ter sido reservada pelo sistema depois do criar (faixas dinamicas do Windows); confere de novo
    # ANTES de subir qualquer servico, para nao deixar o ambiente com servicos de pe e o gateway sem porta (STOP da R2b-1)
    python "$AQUI/lib/porta.py" elegivel "$HOMOLOG_BIND" "$HOMOLOG_PORTA" > "$EVID/porta_antes_dos_servicos.txt" 2>&1 \
        || { cat "$EVID/porta_antes_dos_servicos.txt"; falha "porta $HOMOLOG_BIND:$HOMOLOG_PORTA indisponivel para o gateway; nenhum servico foi iniciado"; }
    compose up -d platform portal gateway > "$EVID/subida_servicos.txt" 2>&1 || falha "subida dos servicos"
    CA="$SEG/tls/ca.crt"
    for i in $(seq 1 90); do
        a="$(curl -s --ssl-no-revoke -o /dev/null -w '%{http_code}' --cacert "$CA" --resolve "$HOMOLOG_HOST:$HOMOLOG_PORTA:127.0.0.1" "https://$HOMOLOG_HOST:$HOMOLOG_PORTA/api/v1/csrf" || true)"
        b="$(curl -s --ssl-no-revoke -o /dev/null -w '%{http_code}' --cacert "$CA" --resolve "$HOMOLOG_HOST:$HOMOLOG_PORTA:127.0.0.1" "https://$HOMOLOG_HOST:$HOMOLOG_PORTA/portal/api/v1/o/00000000-0000-4000-8000-000000000000/workspaces" || true)"
        [ "$a" = 200 ] && [ "$b" = 401 ] && break
        [ "$i" -lt 90 ] || { compose logs --no-color platform portal gateway > "$EVID/falha_servicos.txt" 2>&1; falha "servicos nao responderam (platform=$a portal=$b; ver falha_servicos.txt)"; }
        sleep 2
    done
    if [ -n "$HOMOLOG_FRONTEND_DIST" ]; then
        f="$(curl -s --ssl-no-revoke -o "$EVID/aplicacao_index.html" -w '%{http_code}' --cacert "$CA" --resolve "$HOMOLOG_HOST:$HOMOLOG_PORTA:127.0.0.1" \
            "https://$HOMOLOG_HOST:$HOMOLOG_PORTA/o/00000000-0000-4000-8000-000000000000/execucoes" || true)"
        [ "$f" = 200 ] && cmp -s "$EVID/aplicacao_index.html" "$EVID/frontend-dist/index.html" \
            || falha "aplicacao web nao servida pelo gateway (status $f ou index diferente do build)"
        echo "aplicacao web: URL direta de tela -> 200 com o index do build" > "$EVID/aplicacao.txt"
    fi
    echo "AMBIENTE $HOMOLOG_ID CRIADO: https://$HOMOLOG_HOST:$HOMOLOG_PORTA (publicado em $HOMOLOG_BIND). Proximo passo: homologacao.sh testar $HOMOLOG_ID"
}

case "$ACAO" in
# ======================================================================================================== criar
criar)
    HOMOLOG_ID="${2:-hml-$(date -u +%Y%m%d-%H%M%S)}"
    [[ "$HOMOLOG_ID" =~ ^[a-z0-9-]{3,40}$ ]] || falha "id invalido (a-z, 0-9, -)"
    if [ -n "${HOMOLOG_ELEVACAO_ARQ:-}" ]; then
        [[ "$HOMOLOG_ID" =~ ^ensaio- ]] || falha "lista de elevacao alternativa (HOMOLOG_ELEVACAO_ARQ) so em ambiente descartavel (id ensaio-*)"
        [ -f "$HOMOLOG_ELEVACAO_ARQ" ] || falha "lista de elevacao alternativa inexistente"
    fi
    HOMOLOG_PROJETO="nekkora-homologacao-$HOMOLOG_ID"
    HOMOLOG_HOST="${HOMOLOG_HOST:-homologacao.nekkora.test}"
    HOMOLOG_PORTA="${HOMOLOG_PORTA:-55643}"
    HOMOLOG_BIND="${HOMOLOG_BIND:-127.0.0.1}"
    SEG="${HOMOLOG_SEGREDOS:-$HOME/.nekkora/homologacao/$HOMOLOG_ID}"
    EVID="$AQUI/saidas/$HOMOLOG_ID"
    HOMOLOG_ATE="${HOMOLOG_ATE:-015}"   # padrao: o desenho anterior (001-015); a 016 so pedida explicitamente
    HOMOLOG_REDE_INTERNA="${HOMOLOG_REDE_INTERNA:-10.252.10}"; HOMOLOG_REDE_BORDA="${HOMOLOG_REDE_BORDA:-10.252.11}"
    HOMOLOG_TRANSPORTE="${HOMOLOG_TRANSPORTE:-}"
    python "$AQUI/lib/redes.py" prefixo "$HOMOLOG_REDE_INTERNA" || falha "HOMOLOG_REDE_INTERNA invalido (a.b.c)"
    python "$AQUI/lib/redes.py" prefixo "$HOMOLOG_REDE_BORDA" || falha "HOMOLOG_REDE_BORDA invalido (a.b.c)"
    case "$HOMOLOG_TRANSPORTE" in ""|homologacao) ;; *) falha "HOMOLOG_TRANSPORTE invalido (vazio ou homologacao)";; esac
    FRONTEND_WEB="${HOMOLOG_FRONTEND_WEB:-}"; FRONTEND_COMMIT="${HOMOLOG_FRONTEND_COMMIT:-}"; FRONTEND_LIB_COMMIT="${HOMOLOG_FRONTEND_LIB_COMMIT:-}"
    if [ -n "$FRONTEND_WEB" ]; then
        [[ "$HOMOLOG_ID" =~ ^ensaio- ]] || falha "aplicacao web no gateway so em ambiente descartavel (id ensaio-*)"
        [ "$HOMOLOG_ATE" = 016 ] || falha "aplicacao web no gateway exige HOMOLOG_ATE=016 (nginx gerado do modelo)"
        [ -n "$FRONTEND_COMMIT" ] && [ -n "$FRONTEND_LIB_COMMIT" ] || falha "aplicacao web exige HOMOLOG_FRONTEND_COMMIT e HOMOLOG_FRONTEND_LIB_COMMIT (par validado)"
        . "$AQUI/lib/frontend_dist.sh"
        m="$(frontend_conferir "$FRONTEND_WEB" "$FRONTEND_COMMIT" "$FRONTEND_LIB_COMMIT")" || falha "aplicacao web recusada: $m"
    elif [ -n "$FRONTEND_COMMIT$FRONTEND_LIB_COMMIT" ]; then
        falha "HOMOLOG_FRONTEND_COMMIT/HOMOLOG_FRONTEND_LIB_COMMIT sem HOMOLOG_FRONTEND_WEB"
    fi
    REDE_015_ENSAIO=""
    case "$HOMOLOG_ATE" in
        015) if [ "$HOMOLOG_REDE_INTERNA.$HOMOLOG_REDE_BORDA" != "10.252.10.10.252.11" ]; then
                 # P3-R015: 015 fora das sub-redes padrao SO em ambiente descartavel, com AS DUAS sub-redes proprias; pg_hba e
                 # nginx do caminho 015 sao gerados para o ambiente (os estaticos, da hml03 e do padrao, nao mudam)
                 [[ "$HOMOLOG_ID" =~ ^ensaio- ]] || falha "HOMOLOG_ATE=015 fora das sub-redes padrao so em ambiente descartavel (id ensaio-*)"
                 [ "$HOMOLOG_REDE_INTERNA" != 10.252.10 ] && [ "$HOMOLOG_REDE_BORDA" != 10.252.11 ] \
                     || falha "HOMOLOG_ATE=015 em sub-rede de ensaio exige HOMOLOG_REDE_INTERNA e HOMOLOG_REDE_BORDA fora do padrao"
                 REDE_015_ENSAIO=1
             fi
             [ -z "$HOMOLOG_TRANSPORTE" ] || falha "transporte de homologacao exige a 016";;
        016) [[ "$HOMOLOG_ID" =~ ^ensaio- ]] || falha "a 016 na homologacao so em ambiente descartavel (id ensaio-*)"
             # sem a linha autorizada (etapa P3), a 016 rodaria sem privilegio, falharia e poderia deixar historico do
             # Flyway com success=false: recusa ANTES de criar qualquer recurso
             # a MESMA lista que o runner usara (a revisada ou, so em ensaio-*, a alternativa HOMOLOG_ELEVACAO_ARQ); a linha da
             # 016 tem de estar VINCULADA exatamente a este id (sem a terceira coluna, ou com outro id, recusa)
             # gramatica de lib/lista_elevacao.sh (P4-R2a-C): legado "016 <sha> <id>" ou "016 <sha> tipo=superuser ambiente=<id>"
             . "$AQUI/lib/lista_elevacao.sh"
             lista_elevacao_normalizada "${HOMOLOG_ELEVACAO_ARQ:-$AQUI/implantacao/elevacao.txt}" "$VINCULO_OBRIGATORIO" 2>/dev/null \
                 | grep -qE "^016 [0-9a-f]{64} superuser $HOMOLOG_ID\$" \
                 || falha "a 016 exige a linha de elevacao autorizada em implantacao/elevacao.txt (etapa P3) para este id ($HOMOLOG_ID); para o desenho anterior, HOMOLOG_ATE=015";;
        021) [[ "$HOMOLOG_ID" =~ ^ensaio- ]] || falha "a 021 na homologacao so em ambiente descartavel (id ensaio-*)"
             # e-mail SIMULADO (caixa do ensaio): cadastro, recuperacao e avisos nunca saem do ambiente nesta forma
             [ "$HOMOLOG_TRANSPORTE" = homologacao ] || falha "HOMOLOG_ATE=021 exige HOMOLOG_TRANSPORTE=homologacao (e-mail simulado na caixa do ensaio)"
             # as cinco versoes de vinculo obrigatorio com linha VINCULADA exatamente a este id, na MESMA lista do runner;
             # sem qualquer uma delas, recusa ANTES de criar qualquer recurso (o runner tambem recusa, sem efeito)
             . "$AQUI/lib/lista_elevacao.sh"
             LN="$(lista_elevacao_normalizada "${HOMOLOG_ELEVACAO_ARQ:-$AQUI/implantacao/elevacao.txt}" "$VINCULO_OBRIGATORIO" 2>/dev/null)" \
                 || falha "lista de elevacao recusada pela gramatica (lib/lista_elevacao.sh)"
             for v in $VINCULO_OBRIGATORIO; do
                 [ "$((10#$v))" -le 21 ] || continue   # 022+: so pelo "atualizar" (linha conferida la)
                 printf '%s\n' "$LN" | grep -qE "^$v [0-9a-f]{64} superuser $HOMOLOG_ID\$" \
                     || falha "a $v exige a linha de elevacao autorizada e vinculada a este id ($HOMOLOG_ID) em implantacao/elevacao.txt"
             done;;
        *) falha "HOMOLOG_ATE invalido (015, 016 ou 021)";;
    esac

    # --- protecao: nada e criado antes destas conferencias ---
    # lista de elevacao que ESTA criacao usa (a mesma do runner): validada pela gramatica para qualquer HOMOLOG_ATE; e a
    # politica historica do ambiente (fotografada abaixo, antes de qualquer recurso; o H02 so usa a fotografia)
    LISTA_CRIACAO="${HOMOLOG_ELEVACAO_ARQ:-$AQUI/implantacao/elevacao.txt}"
    . "$AQUI/lib/lista_elevacao.sh"; . "$AQUI/lib/elevacao_historica.sh"
    lista_elevacao_normalizada "$LISTA_CRIACAO" "$VINCULO_OBRIGATORIO" > /dev/null || falha "lista de elevacao da criacao recusada ($LISTA_CRIACAO); nada foi criado"
    [ -e "$EVID" ] && falha "ja existe $EVID (id em uso)"
    [ -e "$SEG" ] && falha "ja existem segredos em $SEG (id em uso)"
    [ -z "$(docker ps -aq --filter "label=nekkora.homologacao=$HOMOLOG_ID")" ] || falha "ha containers com o rotulo $HOMOLOG_ID"
    [ -z "$(docker volume ls -q --filter "label=nekkora.homologacao=$HOMOLOG_ID")" ] || falha "ha volumes com o rotulo $HOMOLOG_ID"
    [ -z "$(docker network ls -q --filter "label=nekkora.homologacao=$HOMOLOG_ID")" ] || falha "ha redes com o rotulo $HOMOLOG_ID"
    [ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$HOMOLOG_PROJETO")" ] || falha "projeto $HOMOLOG_PROJETO ja existe"
    # sobreposicao de CIDR com QUALQUER rede Docker existente (igual, maior ou menor), antes de criar qualquer recurso
    docker network ls -q | xargs -r docker network inspect -f '{{range .IPAM.Config}}{{println .Subnet}}{{end}}' \
        | python "$AQUI/lib/redes.py" sobreposicao "$HOMOLOG_REDE_INTERNA.0/24" "$HOMOLOG_REDE_BORDA.0/24" \
        || falha "sub-rede do ambiente sobrepoe rede Docker existente (ver acima)"
    [ "$HOMOLOG_PORTA" != 5432 ] || falha "porta da stack principal"
    if docker ps --format '{{.Ports}}' | grep -q ":$HOMOLOG_PORTA->"; then falha "porta $HOMOLOG_PORTA publicada por outro container"; fi
    if (exec 3<>"/dev/tcp/127.0.0.1/$HOMOLOG_PORTA") 2>/dev/null; then falha "porta $HOMOLOG_PORTA em uso"; fi
    # elegibilidade REAL da porta publicada (R2b-1a, F-R2b1-1): listener, bind de verdade e, no Windows, faixas reservadas
    # (excludedportrange; tabela ilegivel = falha fechada). Sem uso nao basta: no STOP da R2b-1 a porta estava livre e
    # dentro de uma faixa reservada, e o gateway nao subiu
    python "$AQUI/lib/porta.py" elegivel "$HOMOLOG_BIND" "$HOMOLOG_PORTA" || falha "porta $HOMOLOG_BIND:$HOMOLOG_PORTA indisponivel para publicacao (ver acima); nada foi criado"
    # imagens (platform, portal, operacao) = conteudo do commit que identifica FONTES_IMAGENS, a MESMA lista na guarda
    # (alteracao, stage, arquivo novo ou ignorado) e na identificacao (lib/fontes_imagens.sh; G1/G1b)
    . "$AQUI/lib/fontes_imagens.sh"
    guarda_fontes_imagens "$REPO" >&2 || falha "entradas das imagens da API (FONTES_IMAGENS) diferentes do commit (ver acima)"
    HOMOLOG_COMMIT="$(commit_fontes_imagens "$REPO")"
    [ -n "$HOMOLOG_COMMIT" ] || falha "nenhum commit com as entradas das imagens (FONTES_IMAGENS)"

    mkdir -p "$EVID" "$SEG/tls"
    # FOTOGRAFIA HISTORICA da lista de elevacao (missao H02 x lista historica): bytes exatos + sha256 + origem, logo depois
    # dos diretorios vazios e ANTES de qualquer segredo, configuracao ou recurso Docker; sem ela a criacao nao avanca
    SHA_LISTA_CRIACAO="$(eh_registrar "$LISTA_CRIACAO" "$([ -n "${HOMOLOG_ELEVACAO_ARQ:-}" ] && echo alternativa-ensaio || echo operacional)" "$EVID" "$HOMOLOG_ID")" \
        && [[ "$SHA_LISTA_CRIACAO" =~ ^[0-9a-f]{64}$ ]] || falha "fotografia historica da lista de elevacao nao preservada; nenhum recurso foi criado"
    registro_fontes_imagens "$REPO" "$HOMOLOG_COMMIT" > "$EVID/fontes_imagens.txt"
    if [ -n "$FRONTEND_WEB" ]; then
        echo "== aplicacao web (par web $FRONTEND_COMMIT + lib $FRONTEND_LIB_COMMIT): build e copia imutavel"
        m="$(frontend_preparar "$FRONTEND_WEB" "$FRONTEND_COMMIT" "$FRONTEND_LIB_COMMIT" "$EVID/frontend-dist" "$EVID/frontend_dist.txt")" \
            || falha "build da aplicacao web: $m (nenhum segredo ou recurso Docker foi criado)"
    fi
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
HOMOLOG_ATE=$HOMOLOG_ATE
HOMOLOG_REDE_INTERNA=$HOMOLOG_REDE_INTERNA
HOMOLOG_REDE_BORDA=$HOMOLOG_REDE_BORDA
HOMOLOG_TRANSPORTE=$HOMOLOG_TRANSPORTE
HOMOLOG_REDE_015_ENSAIO=$REDE_015_ENSAIO
HOMOLOG_CONFIG="$({ com_016 || [ -n "$REDE_015_ENSAIO" ]; } && mixed "$EVID/config")"
HOMOLOG_COMPOSE_EXTRA="$(com_016 && echo compose.016.yml)$([ -n "$REDE_015_ENSAIO" ] && echo compose.015-rede.yml)$([ "$HOMOLOG_TRANSPORTE" = homologacao ] && echo " compose.caixa.yml")$([ -n "$FRONTEND_WEB" ] && echo " compose.frontend.yml")$([ "$HOMOLOG_ATE" = 021 ] && echo " compose.021.yml")"
HOMOLOG_FRONTEND_DIST="$([ -n "$FRONTEND_WEB" ] && mixed "$EVID/frontend-dist")"
HOMOLOG_FRONTEND_COMMIT=$FRONTEND_COMMIT
HOMOLOG_FRONTEND_LIB_COMMIT=$FRONTEND_LIB_COMMIT
HOMOLOG_CRIADO_EM=$(date -u +%Y-%m-%dT%H:%M:%SZ)
HOMOLOG_ELEVACAO_CRIACAO_SHA256=$SHA_LISTA_CRIACAO
EOF
    set -a; . "$EVID/ambiente.env"; set +a
    { echo "## inventario ANTES da criacao ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
      docker ps -a --format '{{.ID}} {{.Names}} {{.Image}} projeto={{.Label "com.docker.compose.project"}}'
      docker volume ls --format '{{.Name}}'; docker network ls --format '{{.ID}} {{.Name}}'; } > "$EVID/inventario_antes.txt"
    docker inspect nekkora-postgres --format 'principal: {{.Name}} started={{.State.StartedAt}} restarts={{.RestartCount}}' > "$EVID/principal_antes.txt" 2>/dev/null || echo "principal: ausente" > "$EVID/principal_antes.txt"

    echo "== segredos e certificados (fora do Git: $SEG)"
    if com_016; then
        python "$AQUI/lib/segredos.py" gerar "$SEG" "https://$HOMOLOG_HOST:$HOMOLOG_PORTA" "$HOMOLOG_REDE_INTERNA" "$HOMOLOG_ID"
    else
        # o prefixo da rede interna define o proxy confiavel (gateway .5); com o padrao, resultado identico ao anterior
        python "$AQUI/lib/segredos.py" gerar "$SEG" "https://$HOMOLOG_HOST:$HOMOLOG_PORTA" "$HOMOLOG_REDE_INTERNA"
    fi
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
        if com_016; then
            # CA OPERACIONAL descartavel (P-256), separada da CA do servidor: so ela valida certificados de operador
            openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 30 -subj "/O=Nekkora Homologacao/CN=Nekkora Ops CA $HOMOLOG_ID" \
                -addext "basicConstraints=critical,CA:true" -addext "keyUsage=critical,keyCertSign,cRLSign" -keyout ops-ca.key -out ops-ca.crt 2>/dev/null
            chmod 600 ops-ca.key 2>/dev/null || true
        fi
    ) || falha "certificados"
    if com_016; then
        echo "== configuracao gerada (pg_hba, pg_ident, nginx) para a rede $HOMOLOG_REDE_INTERNA.0/24"
        mkdir -p "$EVID/config"
        python "$AQUI/lib/redes.py" gerar "$AQUI/postgres/pg_hba.modelo" "$EVID/config/pg_hba.conf" INTERNA="$HOMOLOG_REDE_INTERNA" || falha "pg_hba gerado"
        python "$AQUI/lib/redes.py" gerar "$AQUI/postgres/pg_ident.modelo" "$EVID/config/pg_ident.conf" || falha "pg_ident gerado"
        if [ -n "$FRONTEND_WEB" ]; then
            python "$AQUI/lib/redes.py" gerar "$AQUI/gateway/nginx.modelo" "$EVID/config/nginx.base.conf" INTERNA="$HOMOLOG_REDE_INTERNA" || falha "nginx gerado"
            python "$AQUI/lib/gateway_frontend.py" aplicar "$EVID/config/nginx.base.conf" "$EVID/config/nginx.conf" || falha "nginx da aplicacao web"
            python "$AQUI/lib/gateway_frontend.py" conferir "$EVID/config/nginx.base.conf" "$EVID/config/nginx.conf" > "$EVID/config/nginx_aplicacao.txt" \
                || falha "nginx da aplicacao web diferente do gerado + bloco"
        else
            python "$AQUI/lib/redes.py" gerar "$AQUI/gateway/nginx.modelo" "$EVID/config/nginx.conf" INTERNA="$HOMOLOG_REDE_INTERNA" || falha "nginx gerado"
        fi
    fi
    if [ -n "$REDE_015_ENSAIO" ]; then
        echo "== configuracao gerada do caminho 015 (pg_hba, nginx) para a rede $HOMOLOG_REDE_INTERNA.0/24 (sub-rede de ensaio)"
        mkdir -p "$EVID/config"
        python "$AQUI/lib/redes.py" trocar-prefixo "$AQUI/postgres/pg_hba.conf" "$EVID/config/pg_hba.conf" 10.252.10 "$HOMOLOG_REDE_INTERNA" \
            > "$EVID/config/geracao.txt" || falha "pg_hba do caminho 015 gerado"
        python "$AQUI/lib/redes.py" trocar-prefixo "$AQUI/gateway/nginx.conf" "$EVID/config/nginx.conf" 10.252.10 "$HOMOLOG_REDE_INTERNA" \
            >> "$EVID/config/geracao.txt" || falha "nginx do caminho 015 gerado"
    fi

    echo "== migrations 001-$HOMOLOG_ATE (copia imutavel desta execucao)"
    mkdir -p "$EVID/migracoes"
    for n in $(seq -w 1 "$((10#$HOMOLOG_ATE))"); do
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
    if com_016; then
        (cd "$REPO/api" && ./mvnw -q -pl nekkora-ops dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dep-operacao \
            && ./mvnw -q -pl nekkora-ops test-compile) >> "$EVID/build.txt" 2>&1 || falha "build do job de operacao"
        compose --profile operacao build operacao >> "$EVID/build.txt" 2>&1 || falha "imagem do job de operacao"
    fi

    echo "== PostgreSQL e Redis"
    compose up -d --wait postgres redis > "$EVID/subida_base.txt" 2>&1 || { compose logs --no-color postgres redis > "$EVID/falha_subida.txt" 2>&1; falha "postgres/redis (ver falha_subida.txt)"; }

    echo "== bootstrap (admin_cluster, socket local)"
    VM="$(python "$AQUI/lib/segredos.py" scram "$SEG/senhas/migrador" | tr -d '\r')"
    VB="$(python "$AQUI/lib/segredos.py" scram "$SEG/senhas/backup" | tr -d '\r')"
    admin_sql postgres -v verif_migrador="$VM" -v verif_backup="$VB" < "$AQUI/sql/bootstrap.sql" > "$EVID/bootstrap.txt" 2>&1 || { cat "$EVID/bootstrap.txt"; falha "bootstrap"; }

    echo "== implantacao 001-$HOMOLOG_ATE pela identidade dedicada (elevacao so nas versoes declaradas; 016-020 so com linha vinculada)"
    if com_016; then
        # PARADA VERIFICAVEL (P3): so 001-015; as janelas de 016+ ficam para "implantar", com o pre-voo automatico
        HOMOLOG_IMPLANTAR_ATE=015 HOMOLOG_AUTORIZA_ELEVACAO=1 bash "$AQUI/implantacao/implantar.sh"
        { echo "== PARADA ANTES DA 016 ($(date -u +%Y-%m-%dT%H:%M:%SZ))"; confere_liberacao "$TRAVA"; } > "$EVID/parada_antes_016.txt" 2>&1 || true
        # a falta de 016..HOMOLOG_ATE tem de ser o UNICO motivo de bloqueio: migrador recuperado, uma unica linha BLOQUEIO,
        # exatamente a esperada, e BLOQUEADO ao final (outro motivo, inclusive consulta que falha, gera outra linha e recusa)
        pb="$(grep -c '^BLOQUEIO:' "$EVID/parada_antes_016.txt")"
        faltam_esp="$(for n in $(seq 16 "$((10#$HOMOLOG_ATE))"); do printf '%03d ' "$n"; done | sed 's/ $//')"
        { grep -q '^migrador recuperado: ' "$EVID/parada_antes_016.txt" && [ "$pb" = 1 ] \
            && grep -qx "BLOQUEIO: instalacao incompleta ou divergente (faltam: $faltam_esp; nao esperadas: nenhuma)" "$EVID/parada_antes_016.txt" \
            && [ "$(tail -1 "$EVID/parada_antes_016.txt")" = BLOQUEADO ]; } \
            || { cat "$EVID/parada_antes_016.txt"; falha "parada antes da 016 fora do esperado (ver parada_antes_016.txt)"; }
        echo "PARADA: 001-015 aplicadas, $faltam_esp pendente(s), migrador 0|0|0 (parada_antes_016.txt). Proximos passos:"
        echo "  HOMOLOG_AUTORIZA_ELEVACAO=1 homologacao.sh implantar $HOMOLOG_ID   (janelas com pre-voo automatico)"
        echo "  homologacao.sh concluir-criacao $HOMOLOG_ID"
        exit 0
    fi
    HOMOLOG_AUTORIZA_ELEVACAO=1 bash "$AQUI/implantacao/implantar.sh"
    pos_implantacao
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

# =================================================================================================== ensaio-p3it
# Testes REAIS do runner da P3-IT (vinculo, pre-voo, parada, pausa por versao, recuperacao) com versoes sinteticas
# fora do repositorio; so em ambiente descartavel (id ensaio-*). Ver testes/p3it_runner.sh.
ensaio-p3it)
    carrega_ambiente "${2:?id}"
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] || falha "os testes P3-IT so rodam em ambiente descartavel (id ensaio-*)"
    prepara_funcoes
    export HOMOLOG_P3IT_EV="${3:?dir de saida}" HOMOLOG_P3IT_CASOS="${4:-}"
    exec bash "$AQUI/testes/p3it_runner.sh"
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

# ================================================================================================= preparar-016
preparar-016)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    com_016 || falha "ambiente sem a 016"
    confere_liberacao "$TRAVA" > /dev/null || falha "ambiente BLOQUEADO (ver homologacao.sh estado $HOMOLOG_ID)"
    exec bash "$AQUI/testes/h016.sh" preparar
    ;;

# ============================================================================================= concluir-criacao
# Ambiente com a 016 (criar parou antes dela): depois da 016 aplicada e do ambiente LIBERADO, executa os passos da
# criacao que ficaram pendentes. Recusa ambiente BLOQUEADO ou com servicos ja criados.
concluir-criacao)
    carrega_ambiente "${2:?id}"
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] && com_016 || falha "concluir-criacao so para ensaio-* com a 016 (ou 016-021)"
    prepara_funcoes
    SEG="$HOMOLOG_SEGREDOS_HOST"; EVID="$AQUI/saidas/$HOMOLOG_ID"
    [ -f "$EVID/parada_antes_016.txt" ] || falha "ambiente sem a parada antes da 016"
    [ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$HOMOLOG_PROJETO" --filter "label=com.docker.compose.service=platform")" ] \
        || falha "servicos ja criados"
    confere_liberacao "$TRAVA" > "$EVID/liberacao_antes_de_concluir.txt" 2>&1 || { cat "$EVID/liberacao_antes_de_concluir.txt"; falha "ambiente BLOQUEADO"; }
    pos_implantacao
    ;;

# =================================================================================================== testar-016
testar-016)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    com_016 || falha "ambiente sem a 016"
    confere_liberacao "$TRAVA" > /dev/null || falha "ambiente BLOQUEADO (ver homologacao.sh estado $HOMOLOG_ID)"
    exec bash "$AQUI/testes/h016.sh" testar
    ;;

# ===================================================================================================== operacao
# Job "operacao": <classe> = cli (com.nekkora.ops.cli.OpsCli) ou ferramenta (FerramentaHomologacao). A CLI recebe a
# credencial do operador do volume exclusivo "operadores" (emitida antes por "ferramenta credencial <papel>").
operacao)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    com_016 || falha "ambiente sem a 016"
    classe="${3:?cli|ferramenta}"; shift 3
    case "$classe" in
        ferramenta) MSYS_NO_PATHCONV=1 compose --profile operacao run --rm -T operacao com.nekkora.ops.homologacao.FerramentaHomologacao "$@" ;;
        cli)
            papel="${HOMOLOG_OPERADOR:?defina HOMOLOG_OPERADOR (papel op_hml_*)}"
            [[ "$papel" =~ ^op_hml_[a-z]{3}$ ]] || falha "operador invalido"
            # a senha do PKCS#12 so e lida DENTRO do container (arquivo do volume exclusivo); nunca passa pelo host
            MSYS_NO_PATHCONV=1 compose --profile operacao run --rm -T -e NEKKORA_OPS_PAPEL="$papel" -e NEKKORA_OPS_CREDENCIAL="/operadores/$papel.p12" \
                --entrypoint sh operacao -ec 'export NEKKORA_OPS_CREDENCIAL_SENHA="$(cat "/operadores/$NEKKORA_OPS_PAPEL.senha")"; exec java -cp "/app/lib/*:/app/classes" com.nekkora.ops.cli.OpsCli "$@"' -- "$@" ;;
        *) falha "classe invalida (cli|ferramenta)";;
    esac
    ;;

# ======================================================================================================== caixa
# Ferramenta RESTRITA da caixa do transporte de homologacao (volume exclusivo; container sem rede, somente leitura
# exceto o proprio volume). Nada da caixa vai para a evidencia, exceto o INDICE sanitizado (exportar-indice).
caixa)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    [ "${HOMOLOG_TRANSPORTE:-}" = homologacao ] || falha "ambiente sem o transporte de homologacao"
    sub="${3:?listar|ler|controle|exportar-indice|liberar-trava}"
    cx() { MSYS_NO_PATHCONV=1 compose --profile caixa run --rm -T caixa-ferramenta "$@" 2>/dev/null | tr -d '\r'; }
    case "$sub" in
        listar) cx sh -ec 'cd /caixa && ls -1 avisos outras 2>/dev/null' ;;
        ler)
            arq="${4:?arquivo}"
            [[ "$arq" =~ ^(avisos|outras)/[A-Za-z0-9_.:-]{1,120}\.json$ ]] || falha "arquivo invalido"
            cx cat "/caixa/$arq" ;;
        controle)
            regra="${*:4}"
            [[ "$regra" =~ ^(proximo\ (ACEITO|ENFILEIRADO|RECUSADO|INCERTO|EXCECAO)|destinatario\ [0-9a-f]{64}\ (ACEITO|ENFILEIRADO|RECUSADO|INCERTO|EXCECAO))$ ]] \
                || falha "regra invalida"
            # mesma trava do transporte (diretorio criado atomicamente); anexa a regra e libera
            # umask 077: o controle criado aqui nasce 600, como os demais arquivos da caixa (achado do P1c)
            cx sh -ec 'umask 077; mkdir /caixa/controle.trava && { printf "%s\n" "$1" >> /caixa/controle.conf; rmdir /caixa/controle.trava; }' -- "$regra" \
                || falha "trava do controle ocupada (o transporte esta consumindo; tente de novo)" ;;
        liberar-trava)
            # so depois de confirmar que a Platform nao esta enviando (trava orfa de uma queda)
            [ "${HOMOLOG_CONFIRMA_TRAVA_ORFA:-}" = 1 ] || falha "confirme com HOMOLOG_CONFIRMA_TRAVA_ORFA=1"
            cx sh -ec 'rmdir /caixa/controle.trava' ;;
        exportar-indice)
            cx cat /caixa/indice.jsonl | python "$AQUI/lib/indice_caixa.py" > "$AQUI/saidas/$HOMOLOG_ID/caixa_indice.jsonl" \
                || falha "indice da caixa recusado pela sanitizacao"
            echo "indice exportado: saidas/$HOMOLOG_ID/caixa_indice.jsonl ($(wc -l < "$AQUI/saidas/$HOMOLOG_ID/caixa_indice.jsonl") linhas)" ;;
        *) falha "subcomando invalido";;
    esac
    ;;

# ====================================================================================================== fixture
# Fixtures temporais DECLARADAS (desenho P1a, sec. 5): SO ensaio-*, SO a organizacao sintetica da 016 deste ambiente,
# pelo admin_cluster a partir do job de ferramentas (TLS verify-full). psql -v ON_ERROR_STOP=1 SEM --single-transaction:
# o proprio arquivo faz BEGIN/COMMIT, as verificacoes que provocam rollback ficam antes do COMMIT, e a sessao continua
# depois dele so para conferir persistencia e session_replication_role = origin.
fixture)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] || falha "fixture temporal so em ambiente ensaio-*"
    com_016 || falha "ambiente sem a 016"
    [ -n "$(docker ps -aq --filter "label=nekkora.homologacao=$HOMOLOG_ID" --filter "label=com.docker.compose.project=$HOMOLOG_PROJETO")" ] \
        || falha "projeto $HOMOLOG_PROJETO sem containers rotulados deste ambiente"
    ORG="$(python -c "import json,sys; print(json.load(open(sys.argv[1]))['ids']['org'])" "$HOMOLOG_SEGREDOS_HOST/teste016/fixture.json" | tr -d '\r')" \
        || falha "organizacao sintetica da 016 ainda nao criada (homologacao.sh preparar-016)"
    tipo="${3:?contatos|solicitacao|conferir}"
    conexao="host=postgres port=5432 dbname=nekkora user=admin_cluster sslmode=verify-full sslrootcert=/tls/ca.crt"
    FX="$AQUI/saidas/$HOMOLOG_ID/fixtures.txt"
    # solicitacoes que TENTARAM receber fixture: todas ficam improprias para B9 (preventivo, antes de executar; o
    # resultado e anexado depois). B9 exclui qualquer solicitacao listada aqui, qualquer que seja a classificacao.
    SOL="$AQUI/saidas/$HOMOLOG_ID/solicitacoes_com_fixture.txt"
    psql_ferr() { MSYS_NO_PATHCONV=1 compose run --rm -T -e PGPASSWORD ferramentas psql "$conexao" "$@" 2>&1 | tr -d '\r' | grep -vE '^ Container |^\s*$'; return "${PIPESTATUS[0]}"; }
    TMPB="$(mktemp)"; trap 'rm -f "$TMPB"' EXIT

    # ---- reconciliacao de uma execucao com resultado nao confirmado (somente leitura)
    if [ "$tipo" = conferir ]; then
        exe="${4:?execucao}"
        [[ "$exe" =~ ^[0-9a-f-]{36}$ ]] || falha "execucao invalida"
        cab="$(grep -E "^== fixture [a-z]+ execucao=$exe( |\$)" "$FX" 2>/dev/null | head -1)"
        [ -n "$cab" ] || falha "execucao $exe nao encontrada em $FX"
        t2="$(echo "$cab" | awk '{print $3}')"
        alvo="$(echo "$cab" | sed -nE 's/.* alvo=([^ ]+).*/\1/p')"
        if [ "$t2" = contatos ]; then vars=(-v tipo=contatos -v contatos="$alvo"); else vars=(-v tipo=solicitacao -v solicitacao="$alvo"); fi
        psql_ferr -X -At -v ON_ERROR_STOP=1 -v prefixo=ESTADO "${vars[@]}" -f /sql/fixture_016_estado.sql > "$TMPB" \
            || falha "estado atual nao lido (banco indisponivel?): nada concluido, repita a conferencia"
        rc_c=0; r="$(python "$AQUI/lib/fixture_conferencia.py" "$FX" "$exe" < "$TMPB")" || rc_c=$?
        { echo "== conferencia execucao=$exe $(date -u +%Y-%m-%dT%H:%M:%SZ)"; echo "$r"; } | tee -a "$FX"
        if [ "$t2" = solicitacao ]; then echo "$alvo|conferencia:$(echo "$r" | awk '{print $2}')|$exe" >> "$SOL"; fi
        exit "$rc_c"
    fi

    base=(-X -At -v ON_ERROR_STOP=1 -v ambiente="$HOMOLOG_ID" -v org="$ORG" -v ip_servidor="$HOMOLOG_REDE_INTERNA.10")
    exe="$(python -c 'import uuid; print(uuid.uuid4())' | tr -d '\r')"
    case "$tipo" in
        contatos)
            lista="${4:?uuids separados por virgula}"
            [[ "$lista" =~ ^[0-9a-f-]{36}(,[0-9a-f-]{36})*$ ]] || falha "lista de contatos invalida"
            alvo="$lista"; horas=721
            args=("${base[@]}" -v contatos="$lista" -f /sql/fixture_016_contatos.sql) ;;
        solicitacao)
            req="${4:?uuid}"; horas="${5:-24}"
            [[ "$req" =~ ^[0-9a-f-]{36}$ ]] && [[ "$horas" =~ ^[0-9]{1,2}$ ]] || falha "parametros invalidos"
            alvo="$req"
            echo "$req|tentativa|$exe|$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$SOL"   # preventivo: impropria para B9 desde ja
            args=("${base[@]}" -v solicitacao="$req" -v horas="$horas" -f /sql/fixture_016_solicitacao.sql) ;;
        *) falha "tipo invalido";;
    esac
    { echo "== fixture $tipo execucao=$exe $(date -u +%Y-%m-%dT%H:%M:%SZ) alvo=$alvo horas=$horas"
      c=0; psql_ferr "${args[@]}" || c=$?; echo "codigo=$c"; } | tee "$TMPB" >> "$FX"
    cat "$TMPB"
    # classificacao pela MARCA pos-COMMIT e pelo codigo do psql (lib/fixture_conferencia.py classificar; regras la)
    classe="$(python "$AQUI/lib/fixture_conferencia.py" classificar < "$TMPB" | tr -d '\r')"
    echo "classificacao=$classe execucao=$exe" | tee -a "$FX"
    if [ "$tipo" = solicitacao ]; then echo "$req|$classe|$exe" >> "$SOL"; fi
    case "$classe" in
        APLICADA) ;;
        NAO_APLICADA) falha "fixture recusada ANTES do COMMIT: transacao desfeita, nada foi alterado (ver $FX)";;
        *) falha "resultado a RECONCILIAR ($classe): homologacao.sh fixture $HOMOLOG_ID conferir $exe";;
    esac
    ;;

# ================================================================================================ semear-pre014
# Semeadura DECLARADA pre-014 (R2b-0, T2): SO ensaio-*, SO com o runner PAUSADO antes de conceder a janela da 014
# (HOMOLOG_ENSAIO_PAUSA=antes-de-elevar@014:<arquivo>, o MESMO valor passado ao criar/implantar: o runner criou
# <arquivo>.chegou e espera <arquivo>), migrador 0|0|0 e nenhuma semente anterior. Grava 18 sessoes sinteticas
# (sql/fixture_014_pre.sql, que reconfere historico 001-013, ausencia da 014 e da semente dentro da transacao). Nao libera
# a pausa: quem conduz o ensaio confere (conferir-semente014 antes) e so entao cria <arquivo>.
semear-pre014)
    [[ "${2:?id}" =~ ^ensaio- ]] || falha "semeadura pre-014 so em ambiente descartavel (id ensaio-*)"
    carrega_ambiente "$2"
    prepara_funcoes
    p="${HOMOLOG_ENSAIO_PAUSA:-}"
    [[ "$p" == antes-de-elevar@014:?* ]] || falha "semeadura exige HOMOLOG_ENSAIO_PAUSA=antes-de-elevar@014:<arquivo> (a mesma pausa do runner)"
    f="${p#*:}"
    [ -e "$f.chegou" ] || falha "o runner nao chegou a pausa antes de elevar a 014 ($f.chegou ausente)"
    [ ! -e "$f" ] || falha "a pausa ja foi liberada ($f existe): semeadura recusada"
    [ -d "$TRAVA" ] || falha "sem trava local da implantacao: o runner nao esta em execucao"
    e="$(estado_migrador)"
    [ "$e" = "0|0|0" ] || falha "estado do migrador '$e' (esperado 0|0|0 antes de conceder)"
    SEM="$AQUI/saidas/$HOMOLOG_ID/semente_014.txt"
    [ ! -e "$SEM" ] || falha "semente ja registrada ($SEM): nao se repete"
    { echo "== semear-pre014 $(date -u +%Y-%m-%dT%H:%M:%S.%3NZ) (pausa $f, migrador $e)"
      c=0; admin_sql nekkora -v ambiente="$HOMOLOG_ID" < "$AQUI/sql/fixture_014_pre.sql" > "$SEM.saida" 2>&1 || c=$?
      tr -d '\r' < "$SEM.saida"; rm -f "$SEM.saida"
      echo "codigo=$c"; } > "$SEM.tmp"
    cat "$SEM.tmp"
    if grep -qx 'codigo=0' "$SEM.tmp" && grep -qx SEMENTE_APLICADA "$SEM.tmp"; then mv "$SEM.tmp" "$SEM"; echo "semente registrada em $SEM"
    else mv "$SEM.tmp" "$SEM.recusada-$(date -u +%Y%m%dT%H%M%SZ)"; falha "semeadura recusada ou incompleta (transacao desfeita; ver acima)"; fi
    ;;

# ========================================================================================== conferir-semente014
# Conferencia somente leitura da semeadura (R2b-0, T3): "antes" (antes da 014; grava o manifesto) e "depois" (depois da
# retirada da janela da 014; a janela vem do registro do runner: ultima linha "inicio/fim versao=014 BYPASSRLS").
conferir-semente014)
    [[ "${2:?id}" =~ ^ensaio- ]] || falha "conferencia da semeadura so em ambiente descartavel (id ensaio-*)"
    carrega_ambiente "$2"
    prepara_funcoes
    MAN="$AQUI/saidas/$HOMOLOG_ID/semente_014_manifesto.txt"
    case "${3:?antes|depois}" in
        antes) exec bash "$AQUI/testes/semente_014.sh" antes "$MAN" ;;
        depois)
            # registro mais recente com a janela da 014: o do criar (raiz) e, depois, os de "implantar" (implantacao-<instante>,
            # em ordem de nome = ordem de tempo); so arquivos existentes (com set -e/pipefail, um glob sem correspondencia
            # nao pode derrubar o comando - achado F-R2b0-1)
            J=""
            for j in "$AQUI/saidas/$HOMOLOG_ID/janelas_de_elevacao.txt" "$AQUI/saidas/$HOMOLOG_ID"/implantacao-*/janelas_de_elevacao.txt; do
                if [ -f "$j" ] && grep -q 'versao=014 BYPASSRLS' "$j"; then J="$j"; fi
            done
            [ -n "$J" ] || falha "janela da 014 nao registrada"
            ini="$(grep 'inicio versao=014 BYPASSRLS concedido' "$J" | tail -1 | cut -d' ' -f1)"
            fim="$(grep 'fim    versao=014 BYPASSRLS revogado' "$J" | tail -1 | cut -d' ' -f1)"
            [ -n "$ini" ] && [ -n "$fim" ] || falha "janela da 014 sem inicio e fim registrados ($J)"
            echo "janela da 014 ($J): $ini .. $fim"
            exec bash "$AQUI/testes/semente_014.sh" depois "$MAN" "$ini" "$fim" ;;
        *) falha "modo invalido (antes|depois)" ;;
    esac
    ;;

# ============================================================================================== semear-frontend
# Massa SINTETICA do ensaio-frontend. Como admin_cluster (o administrador do cluster DO ENSAIO, socket local, o mesmo da
# fixture da 016): nao e janela de elevacao do migrador. Estados e garantias em lib/semear_frontend.py.
semear-frontend|jornadas-frontend|frontend-sessao)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] && [ -n "${HOMOLOG_FRONTEND_DIST:-}" ] \
        || falha "$ACAO so em ambiente ensaio-* criado com a aplicacao web (HOMOLOG_FRONTEND_WEB)"
    confere_liberacao "$TRAVA" > /dev/null || falha "ambiente BLOQUEADO (ver homologacao.sh estado $HOMOLOG_ID)"
    export HOMOLOG_BASH="${HOMOLOG_BASH:-$(mixed "$(command -v bash)")}"
    [ -f "C:/Program Files/Git/bin/bash.exe" ] && HOMOLOG_BASH="C:/Program Files/Git/bin/bash.exe"
    EVF="$AQUI/saidas/$HOMOLOG_ID/frontend"; mkdir -p "$EVF"
    case "$ACAO" in
    semear-frontend)
        if [ "${3:-}" = estado ]; then
            exec python "$AQUI/lib/semear_frontend.py" estado --segredos "$HOMOLOG_SEGREDOS" --psql "admin_sql nekkora"
        fi
        [ -z "${3:-}" ] || falha "uso: semear-frontend <id> [estado]"
        jar="$(mixed "$REPO/api/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar")"
        tc="$(mixed "$REPO/api/nekkora-platform/target/test-classes")"
        [ -f "$jar" ] && [ -f "$REPO/api/nekkora-platform/target/test-classes/com/nekkora/platform/auth/crypto/PhcTool.class" ] \
            || falha "PhcTool indisponivel (jar e test-classes do build do criar)"
        exec python "$AQUI/lib/semear_frontend.py" semear --segredos "$HOMOLOG_SEGREDOS" --evid "$(mixed "$EVF/semeadura")" \
            --sql "$(mixed "$AQUI/sql/semear_frontend.sql")" --psql "admin_sql nekkora" \
            --phc "java -Dloader.path='$tc' -Dloader.main=com.nekkora.platform.auth.crypto.PhcTool -cp '$jar' org.springframework.boot.loader.launch.PropertiesLauncher 65536 3"
        ;;
    jornadas-frontend)
        sub="${3:?preparar-contas|executar}"; shift 3
        case "$sub" in preparar-contas|executar) ;; *) falha "subcomando invalido (preparar-contas|executar)";; esac
        SOMENTE=""; VINCULO=""; OPC=()
        while [ $# -gt 0 ]; do
            case "$1" in
                --mutacoes|--inatividade-real) OPC+=("$1"); shift ;;
                --somente) [ $# -ge 2 ] || falha "--somente sem valor"; SOMENTE="$2"; OPC+=(--somente "$2"); shift 2 ;;
                --vinculo-stop) [ $# -ge 2 ] || falha "--vinculo-stop sem valor"; VINCULO="$2"; OPC+=(--vinculo-stop "$2"); shift 2 ;;
                *) falha "opcao invalida: $1" ;;
            esac
        done
        set -- "${OPC[@]}"
        SNAKE="$(ls "$HOME"/.m2/repository/org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar 2>/dev/null)" || falha "SnakeYAML 2.6 ausente no repositorio Maven local"
        J="$EVF/jornadas-$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$J"
        java -cp "$(mixed "$SNAKE")" "$(mixed "$AQUI/lib/OpenApiJson.java")" "$(mixed "$REPO/api/openapi/nekkora-api-v1.yaml")" "$(mixed "$J/openapi.json")" \
            > "$J/openapi_conversao.txt" 2>&1 || falha "conversao do OpenAPI (ver $J/openapi_conversao.txt)"
        JORN=(python "$AQUI/testes/jornadas_frontend.py" "$sub" --segredos "$HOMOLOG_SEGREDOS" --evid "$(mixed "$J")" \
            --openapi-json "$(mixed "$J/openapi.json")" --openapi-yaml "$(mixed "$REPO/api/openapi/nekkora-api-v1.yaml")" \
            --alvo "https://$HOMOLOG_HOST:$HOMOLOG_PORTA" --ca "$(mixed "$HOMOLOG_SEGREDOS_HOST/tls/ca.crt")" \
            --psql "admin_sql nekkora" --sql-sessao "$(mixed "$AQUI/sql/sessao_frontend.sql")" "$@")
        [ -n "$SOMENTE" ] || exec "${JORN[@]}"
        # SELECAO (P-B-C1-R1): pre-condicao imediatamente antes das jornadas, UMA chamada e conferencia de trafego,
        # tudo em lib/selecao_gateway.sh (codigo: o das jornadas; 3 = conferencia inconclusiva; 4/5 = nenhuma jornada)
        . "$AQUI/lib/selecao_gateway.sh"
        rc=0
        selecao_executa "$J" "${JORN[@]}" || rc=$?
        exit "$rc"
        ;;
    frontend-sessao)
        [ "${3:-}" = absoluta ] || falha "uso: frontend-sessao <id> absoluta (inatividade so com o relogio real, nas jornadas)"
        PLANO="$HOMOLOG_SEGREDOS_HOST/frontend/semeadura.json"
        [ -f "$PLANO" ] || falha "semeadura do frontend nao concluida"
        read -r U O < <(python -c "import json,sys; p=json.load(open(sys.argv[1], encoding='utf-8')); c=next(x for x in p['contas'] if x['chave']=='expira'); print(c['user_id'], p['orgs'][0]['id'])" "$(mixed "$PLANO")" | tr -d '\r')
        { echo "== frontend-sessao absoluta $(date -u +%Y-%m-%dT%H:%M:%SZ) conta=expira usuario=$U"
          admin_sql nekkora -v usuario="$U" -v organizacao="$O" -v tipo=absoluta < "$AQUI/sql/sessao_frontend.sql" 2>&1 | tr -d '\r'; } | tee -a "$EVF/operacoes_sessao.txt"
        grep -q "SESSOES_ENVELHECIDAS=" "$EVF/operacoes_sessao.txt" || falha "operacao de sessao recusada (ver $EVF/operacoes_sessao.txt)"
        ;;
    esac
    ;;
# ==================================================================================== semear-f6a / validar-f6a
# Validacao administrativa da F6a (F6a-V-T): massa COMPLEMENTAR f6av-* (lib/semear_f6a.py, sobre a da F1.5) e o
# verificador (testes/validar_f6a.py). Mesmas exigencias do bloco acima (ensaio-* com a aplicacao web e LIBERADO).
semear-f6a|validar-f6a)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] && [ -n "${HOMOLOG_FRONTEND_DIST:-}" ] \
        || falha "$ACAO so em ambiente ensaio-* criado com a aplicacao web (HOMOLOG_FRONTEND_WEB)"
    confere_liberacao "$TRAVA" > /dev/null || falha "ambiente BLOQUEADO (ver homologacao.sh estado $HOMOLOG_ID)"
    export HOMOLOG_BASH="${HOMOLOG_BASH:-$(mixed "$(command -v bash)")}"
    [ -f "C:/Program Files/Git/bin/bash.exe" ] && HOMOLOG_BASH="C:/Program Files/Git/bin/bash.exe"
    EVF="$AQUI/saidas/$HOMOLOG_ID/frontend"; mkdir -p "$EVF"
    case "$ACAO" in
    semear-f6a)
        if [ "${3:-}" = estado ]; then
            exec python "$AQUI/lib/semear_f6a.py" estado --segredos "$HOMOLOG_SEGREDOS" --psql "admin_sql nekkora" --evid "$(mixed "$EVF/semeadura-f6a")"
        fi
        [ -z "${3:-}" ] || falha "uso: semear-f6a <id> [estado]"
        jar="$(mixed "$REPO/api/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar")"
        tc="$(mixed "$REPO/api/nekkora-platform/target/test-classes")"
        [ -f "$jar" ] && [ -f "$REPO/api/nekkora-platform/target/test-classes/com/nekkora/platform/auth/crypto/PhcTool.class" ] \
            || falha "PhcTool indisponivel (jar e test-classes do build do criar)"
        exec python "$AQUI/lib/semear_f6a.py" semear --segredos "$HOMOLOG_SEGREDOS" --evid "$(mixed "$EVF/semeadura-f6a")" \
            --sql "$(mixed "$AQUI/sql/semear_f6a.sql")" --psql "admin_sql nekkora" \
            --phc "java -Dloader.path='$tc' -Dloader.main=com.nekkora.platform.auth.crypto.PhcTool -cp '$jar' org.springframework.boot.loader.launch.PropertiesLauncher 65536 3"
        ;;
    validar-f6a)
        sub="${3:?preparar|conferir|anti-replay|foto|comparar|intencoes}"; shift 3
        V="$EVF/validacao-f6a"; mkdir -p "$V"
        VF=(python "$AQUI/testes/validar_f6a.py")
        case "$sub" in
            preparar|conferir)
                if [ "$sub" = preparar ]; then [ $# = 0 ] || falha "uso: validar-f6a <id> preparar"; else [ $# = 1 ] || falha "uso: validar-f6a <id> conferir <fase>"; fi
                SNAKE="$(ls "$HOME"/.m2/repository/org/yaml/snakeyaml/2.6/snakeyaml-2.6.jar 2>/dev/null)" || falha "SnakeYAML 2.6 ausente no repositorio Maven local"
                J="$V/$sub${1:+-$1}-$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$J"
                java -cp "$(mixed "$SNAKE")" "$(mixed "$AQUI/lib/OpenApiJson.java")" "$(mixed "$REPO/api/openapi/nekkora-api-v1.yaml")" "$(mixed "$J/openapi.json")" \
                    > "$J/openapi_conversao.txt" 2>&1 || falha "conversao do OpenAPI (ver $J/openapi_conversao.txt)"
                # mesmas guardas da selecao de jornadas: pre-condicao, UMA chamada e conferencia de trafego (codigos 3/4/5)
                . "$AQUI/lib/selecao_gateway.sh"
                rc=0
                selecao_executa "$J" "${VF[@]}" "$sub" "$@" --segredos "$HOMOLOG_SEGREDOS" --evid "$(mixed "$J")" \
                    --openapi-json "$(mixed "$J/openapi.json")" --openapi-yaml "$(mixed "$REPO/api/openapi/nekkora-api-v1.yaml")" \
                    --alvo "https://$HOMOLOG_HOST:$HOMOLOG_PORTA" --ca "$(mixed "$HOMOLOG_SEGREDOS_HOST/tls/ca.crt")" --psql "admin_sql nekkora" || rc=$?
                exit "$rc"
                ;;
            anti-replay)
                [ $# = 0 ] || falha "uso: validar-f6a <id> anti-replay"
                exec "${VF[@]}" anti-replay --segredos "$HOMOLOG_SEGREDOS" --psql "admin_sql nekkora"
                ;;
            foto)
                [[ "${1:-}" =~ ^[a-z0-9-]+$ ]] && [ $# = 1 ] || falha "uso: validar-f6a <id> foto <rotulo>"
                [ ! -e "$V/foto-$1.json" ] || falha "foto $1 ja existe (nao se sobrescreve)"
                exec "${VF[@]}" foto "$(mixed "$V/foto-$1.json")" --segredos "$HOMOLOG_SEGREDOS" --psql "admin_sql nekkora"
                ;;
            comparar)
                [ $# -ge 2 ] || falha "uso: validar-f6a <id> comparar <rotulo_antes> <rotulo_depois> [--logins-admin N --stepups N --gateway-novo PASTA]"
                a1="$1"; a2="$2"; shift 2
                exec "${VF[@]}" comparar "$(mixed "$V/foto-$a1.json")" "$(mixed "$V/foto-$a2.json")" --segredos "$HOMOLOG_SEGREDOS" \
                    --evid "$(mixed "$V/comparacao-$a1-$a2")" "$@"
                ;;
            intencoes)
                [ $# -ge 1 ] || falha "uso: validar-f6a <id> intencoes <desde> [TIPO=N ...]"
                d="$1"; shift
                [[ "$d" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || falha "desde: instante UTC (AAAA-MM-DDTHH:MM:SSZ)"
                ESP=(); [ $# = 0 ] || ESP=(--esperado "$@")
                # compose e exportada (export -f em prepara_funcoes): o bash chamado pelo verificador a enxerga
                exec "${VF[@]}" intencoes --segredos "$HOMOLOG_SEGREDOS" --desde "$d" \
                    --logs-cmd "compose logs --no-color --since $d platform" "${ESP[@]}"
                ;;
            *) falha "subcomando invalido: $sub" ;;
        esac
        ;;
    esac
    ;;

# ========================================================================================================= parar
# Para os containers DESTE ambiente (rotulo e projeto), sem remover nada: volumes, redes, imagens, segredos e evidencias
# ficam. Os jobs efemeros nao tem container parado.
parar)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    compose stop 2>&1 | tail -n +1
    echo "parado: $(docker ps -aq --filter "label=com.docker.compose.project=$HOMOLOG_PROJETO" | wc -l | tr -d ' ') container(es) de $HOMOLOG_PROJETO mantidos (docker compose stop)"
    ;;

# ========================================================================================================= subir
# Sobe de novo um ambiente parado: banco e Redis primeiro; Platform, Portal e gateway SO com o ambiente LIBERADO (mesma
# exigencia da criacao) e com a porta ainda apta.
subir)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    compose up -d --wait postgres redis > /dev/null 2>&1 || falha "postgres/redis nao subiram"
    confere_liberacao "$TRAVA" || falha "ambiente BLOQUEADO: servicos nao iniciados (homologacao.sh estado $HOMOLOG_ID)"
    python "$AQUI/lib/porta.py" elegivel "$HOMOLOG_BIND" "$HOMOLOG_PORTA" > /dev/null || falha "porta $HOMOLOG_BIND:$HOMOLOG_PORTA indisponivel"
    compose up -d platform portal gateway > /dev/null 2>&1 || falha "servicos nao subiram"
    CA="$HOMOLOG_SEGREDOS_HOST/tls/ca.crt"
    for i in $(seq 1 90); do
        a="$(curl -s --ssl-no-revoke -o /dev/null -w '%{http_code}' --cacert "$CA" --resolve "$HOMOLOG_HOST:$HOMOLOG_PORTA:127.0.0.1" "https://$HOMOLOG_HOST:$HOMOLOG_PORTA/api/v1/csrf" || true)"
        [ "$a" = 200 ] && break
        [ "$i" -lt 90 ] || falha "Platform nao respondeu pelo gateway (status $a)"
        sleep 2
    done
    echo "AMBIENTE $HOMOLOG_ID NO AR: https://$HOMOLOG_HOST:$HOMOLOG_PORTA (publicado em $HOMOLOG_BIND)"
    ;;

# ================================================================================================= jornadas-api
# Jornadas integradas da API v1 pelo gateway (missao PLATFORM-API-V1-003, item 7; testes/jornadas_api.py): SO ensaio-*
# com 016-021 e o transporte de homologacao, ambiente LIBERADO. Tokens de e-mail lidos da caixa pela ferramenta restrita;
# o que a API ainda nao cria vem da semeadura declarada sql/semear_api_v1.sql (PREP no relatorio). Ao fim, VARREDURA:
# nenhum valor visto (senha, token, cookie, CSRF, segredo TOTP, codigo, e-mail) nos logs da Platform, do Portal e do
# gateway nem na evidencia; os logs ficam no diretorio de segredos (fora do Git), a evidencia so com as contagens.
jornadas-api)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] && [[ "${HOMOLOG_ATE:-015}" =~ ^02[12]$ ]] && [ "${HOMOLOG_TRANSPORTE:-}" = homologacao ] \
        || falha "jornadas-api so em ensaio-* com HOMOLOG_ATE=021 (ou atualizado para 022) e o transporte de homologacao"
    confere_liberacao "$TRAVA" > /dev/null || falha "ambiente BLOQUEADO (ver homologacao.sh estado $HOMOLOG_ID)"
    export HOMOLOG_BASH="${HOMOLOG_BASH:-$(mixed "$(command -v bash)")}"
    [ -f "C:/Program Files/Git/bin/bash.exe" ] && HOMOLOG_BASH="C:/Program Files/Git/bin/bash.exe"
    jar="$(mixed "$REPO/api/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar")"
    tc="$(mixed "$REPO/api/nekkora-platform/target/test-classes")"
    [ -f "$jar" ] && [ -f "$REPO/api/nekkora-platform/target/test-classes/com/nekkora/platform/auth/crypto/PhcTool.class" ] \
        || falha "PhcTool indisponivel (jar e test-classes do build do criar)"
    INICIO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    J="$AQUI/saidas/$HOMOLOG_ID/jornadas-api-$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$J"
    SJ="$HOMOLOG_SEGREDOS_HOST/jornadas-api/$(basename "$J")"; mkdir -p "$SJ"
    rc=0
    python "$AQUI/testes/jornadas_api.py" --alvo "https://$HOMOLOG_HOST:$HOMOLOG_PORTA" --ca "$(mixed "$HOMOLOG_SEGREDOS_HOST/tls/ca.crt")" \
        --evid "$(mixed "$J")" --vistos "$(mixed "$SJ/vistos.txt")" --psql "admin_sql nekkora" \
        --phc "java -Dloader.path='$tc' -Dloader.main=com.nekkora.platform.auth.crypto.PhcTool -cp '$jar' org.springframework.boot.loader.launch.PropertiesLauncher 65536 3" \
        --caixa "bash '$(mixed "$AQUI/homologacao.sh")' caixa $HOMOLOG_ID" --sql-semente "$(mixed "$AQUI/sql/semear_api_v1.sql")" "${@:3}" || rc=$?
    # varredura: logs dos servicos desde o inicio desta execucao (fora do Git) e a evidencia, contra cada valor visto
    compose logs --no-color --since "$INICIO" platform portal gateway > "$SJ/logs_servicos.txt" 2>&1 || falha "logs dos servicos nao coletados (varredura impossivel)"
    n_vistos=0; achados=0
    while IFS= read -r v; do
        [ -n "$v" ] || continue
        n_vistos=$((n_vistos + 1))
        if grep -qF -- "$v" "$SJ/logs_servicos.txt" || grep -rqF -- "$v" "$J"; then achados=$((achados + 1)); fi
    done < <(sort -u "$SJ/vistos.txt" 2>/dev/null)
    { echo "valores_vistos=$n_vistos (senhas, tokens, cookies, CSRF, segredos TOTP, codigos, e-mails)"
      echo "linhas_de_log_varridas=$(wc -l < "$SJ/logs_servicos.txt" | tr -d ' ') (platform, portal, gateway desde $INICIO)"
      echo "achados=$achados"
      [ "$achados" = 0 ] && echo "VARREDURA_OK" || echo "VARREDURA_FALHOU"; } | tee "$J/varredura.txt"
    [ "$achados" = 0 ] || rc=1
    [ "$n_vistos" -gt 0 ] || { echo "varredura sem valores vistos" | tee -a "$J/varredura.txt"; rc=1; }
    # controle positivo: um valor NAO secreto que tem de estar nos logs coletados (id da organizacao do J6, no acesso do
    # gateway); sem ele a coleta ou o detector nao provaram nada
    if [ -s "$SJ/controle.txt" ]; then
        if grep -qF -- "$(head -1 "$SJ/controle.txt")" "$SJ/logs_servicos.txt"; then echo "controle_positivo=OK (id de organizacao no log do gateway)" | tee -a "$J/varredura.txt"
        else echo "controle_positivo=FALHOU" | tee -a "$J/varredura.txt"; rc=1; fi
    else
        echo "controle_positivo=NAO_EXECUTADO (J6 fora da selecao)" | tee -a "$J/varredura.txt"
    fi
    exit "$rc"
    ;;

# ==================================================================================================== atualizar
# Atualizacao de um ensaio-* LIBERADO para versoes posteriores das migrations e das imagens (missao PLATFORM-API-V1-003,
# revisao: prova da atualizacao 021 -> 022 numa base populada pelo caminho real de implantacao). SO ensaio-*, SO 021 -> 022.
# Nada e aplicado no banco aqui: o "implantar" seguinte aplica as versoes novas com os controles de sempre (linha
# vinculada, pre-voo, janela, retirada conferida), e o "subir" sobe os servicos com as imagens novas.
#   1. recusa, ANTES de qualquer mudanca: ambiente BLOQUEADO; versao alvo fora do suportado; copia de migrations do
#      ambiente com QUALQUER arquivo de bytes diferentes do repositorio (revisao anterior aplicada nesta base: o Flyway
#      recusaria por checksum e o runner nao repara, IMPLANTACAO.md item 3); versao nova de vinculo obrigatorio sem a linha
#      vinculada a este id com o sha256 do arquivo (na lista revisada ou, num laboratorio ensaio-*, na alternativa
#      HOMOLOG_ELEVACAO_ARQ, a mesma que o "implantar" usara); entradas das imagens diferentes do commit;
#   2. preserva a copia e o registro anteriores (migracoes-ate-<versao>-<instante>, somente leitura) e grava a nova copia
#      (a anterior byte a byte, conferida, mais as versoes novas) e o novo registro;
#   3. build das imagens do commit atual (tag = commit; as anteriores ficam) e parada de Platform, Portal e gateway (banco
#      e Redis continuam no ar);
#   4. ambiente.env com HOMOLOG_ATE e HOMOLOG_COMMIT novos (o anterior fica em atualizacao-<instante>/ambiente.env.antes).
atualizar)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    ALVO="${HOMOLOG_ATUALIZAR_ATE:-}"
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] || falha "atualizar so em ambiente descartavel (id ensaio-*)"
    [ "$HOMOLOG_ATE" = 021 ] && [ "$ALVO" = 022 ] || falha "atualizacao suportada: 021 -> 022 com HOMOLOG_ATUALIZAR_ATE=022 (ambiente em $HOMOLOG_ATE, alvo '${ALVO}')"
    [ "${HOMOLOG_TRANSPORTE:-}" = homologacao ] || falha "atualizar exige o transporte de homologacao"
    novas=""
    for n in $(seq $((10#$HOMOLOG_ATE + 1)) $((10#$ALVO))); do
        v="$(printf '%03d' "$n")"; f=("$REPO"/db/migrations/${v}_*.sql)
        [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration $v ausente ou ambigua; nada alterado"
        novas="$novas ${f[0]}"
    done
    . "$AQUI/lib/lista_elevacao.sh"
    # a mesma lista que o "implantar" seguinte usara: a revisada ou, so em ensaio-* (ja exigido acima), a alternativa
    # HOMOLOG_ELEVACAO_ARQ de um laboratorio (como no criar e no implantar). Conferencias SO DE ARQUIVO (versoes novas e
    # lista) antes das que leem o banco e a copia do ambiente; todas antes de qualquer mudanca (testes/test_atualizar_lista.sh
    # executa este trecho sem Docker)
    LISTA_AT="${HOMOLOG_ELEVACAO_ARQ:-$AQUI/implantacao/elevacao.txt}"
    [ -f "$LISTA_AT" ] || falha "lista de elevacao inexistente ($LISTA_AT); nada alterado"
    LN="$(lista_elevacao_normalizada "$LISTA_AT" "$VINCULO_OBRIGATORIO" 2>/dev/null)" || falha "lista de elevacao recusada pela gramatica; nada alterado"
    for f in $novas; do
        v="$(basename "$f")"; v="${v%%_*}"
        case "$VINCULO_OBRIGATORIO" in *" $v "*)
            printf '%s\n' "$LN" | grep -qE "^$v $(sha256sum < "$f" | cut -c1-64) superuser $HOMOLOG_ID\$" \
                || falha "a $v exige a linha de elevacao vinculada a este id ($HOMOLOG_ID) com o sha256 do arquivo; nada alterado";;
        esac
    done
    EVID="$AQUI/saidas/$HOMOLOG_ID"; INST="$(date -u +%Y%m%dT%H%M%SZ)"; AT="$EVID/atualizacao-$INST"
    confere_liberacao "$TRAVA" > /dev/null || falha "ambiente BLOQUEADO: nada alterado (homologacao.sh estado $HOMOLOG_ID)"
    div=""
    for f in "$EVID/migracoes"/*.sql; do
        n="$(basename "$f")"; r="$REPO/db/migrations/$n"
        if [ ! -f "$r" ]; then div="$div ${n%%_*}(ausente no repositorio)"
        elif [ "$(sha256sum < "$f" | cut -c1-64)" != "$(sha256sum < "$r" | cut -c1-64)" ]; then
            div="$div ${n%%_*}(ambiente $(sha256sum < "$f" | cut -c1-12)... repositorio $(sha256sum < "$r" | cut -c1-12)...)"
        fi
    done
    [ -z "$div" ] || falha "copia de migrations do ambiente diferente do repositorio:$div. Revisao anterior aplicada nesta base: o Flyway recusaria por checksum e o runner nao repara (IMPLANTACAO.md, item 3). Nada alterado"
    . "$AQUI/lib/fontes_imagens.sh"
    guarda_fontes_imagens "$REPO" >&2 || falha "entradas das imagens da API diferentes do commit; nada alterado"
    NOVO_COMMIT="$(commit_fontes_imagens "$REPO")"; [ -n "$NOVO_COMMIT" ] || falha "commit das imagens nao identificado; nada alterado"

    echo "== copia nova das migrations (a anterior preservada, somente leitura)"
    mkdir -p "$AT"
    cp "$EVID/ambiente.env" "$AT/ambiente.env.antes"
    ANT="$EVID/migracoes-ate-$HOMOLOG_ATE-$INST"
    mv "$EVID/migracoes" "$ANT"; mv "$EVID/migracoes_sha256.txt" "$ANT.sha256.txt"
    mkdir -p "$EVID/migracoes"; cp "$ANT"/*.sql "$EVID/migracoes/"
    for f in $novas; do cp "$f" "$EVID/migracoes/"; done
    chmod -R a-w "$ANT" "$ANT.sha256.txt" 2>/dev/null || true
    (cd "$EVID/migracoes" && sha256sum *.sql) > "$EVID/migracoes_sha256.txt"
    (cd "$EVID/migracoes" && sha256sum $(cd "$ANT" && ls *.sql)) | diff -q - "$ANT.sha256.txt" > /dev/null || falha "copia nova nao preserva a anterior byte a byte"
    { echo "atualizacao=$INST de=$HOMOLOG_ATE para=$ALVO"
      echo "commit_antes=$HOMOLOG_COMMIT commit_depois=$NOVO_COMMIT"
      echo "lista_de_elevacao=$(basename "$LISTA_AT")${HOMOLOG_ELEVACAO_ARQ:+ (ALTERNATIVA DE ENSAIO)} sha256 $(sha256sum < "$LISTA_AT" | cut -c1-64)"
      echo "copia_anterior=$(basename "$ANT") (somente leitura; registro $(basename "$ANT").sha256.txt)"
      echo "versoes_novas:"; (cd "$EVID/migracoes" && for f in $novas; do sha256sum "$(basename "$f")"; done)
    } > "$AT/registro.txt"

    echo "== build (jars e imagens identificados por $NOVO_COMMIT)"
    HOMOLOG_COMMIT="$NOVO_COMMIT"; export HOMOLOG_COMMIT
    registro_fontes_imagens "$REPO" "$HOMOLOG_COMMIT" > "$AT/fontes_imagens.txt"
    (cd "$REPO/api" && ./mvnw -q -DskipTests package && ./mvnw -q -DskipTests test-compile -pl nekkora-platform) > "$AT/build.txt" 2>&1 || falha "build (ver $AT/build.txt)"
    compose build platform portal >> "$AT/build.txt" 2>&1 || falha "build das imagens"
    (cd "$REPO/api" && ./mvnw -q -pl nekkora-ops dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dep-operacao \
        && ./mvnw -q -pl nekkora-ops test-compile) >> "$AT/build.txt" 2>&1 || falha "build do job de operacao"
    compose --profile operacao build operacao >> "$AT/build.txt" 2>&1 || falha "imagem do job de operacao"
    compose stop platform portal gateway > "$AT/parada_servicos.txt" 2>&1 || falha "parada dos servicos"

    sed -e "s/^HOMOLOG_ATE=.*/HOMOLOG_ATE=$ALVO/" -e "s/^HOMOLOG_COMMIT=.*/HOMOLOG_COMMIT=$NOVO_COMMIT/" "$AT/ambiente.env.antes" > "$EVID/ambiente.env"
    echo "HOMOLOG_ATUALIZADO_EM=$INST" >> "$EVID/ambiente.env"
    echo "ATUALIZADO: $HOMOLOG_ID copia e imagens de $HOMOLOG_ATE para $ALVO (commit $NOVO_COMMIT); servicos parados; base intacta ($AT). Proximos passos:"
    echo "  HOMOLOG_AUTORIZA_ELEVACAO=1 homologacao.sh implantar $HOMOLOG_ID   (pre-voo e janela da $ALVO)"
    echo "  homologacao.sh subir $HOMOLOG_ID"
    ;;

# ============================================================================================= jornada-ativacao
# Jornada da ativacao e da revisao de capacidade numa base populada atualizada de 021 para 022 (testes/jornada_ativacao.sh):
# SO ensaio-* com a 022 aplicada pelo runner, LIBERADO e com a 016 preparada.
jornada-ativacao)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] && [ "${HOMOLOG_ATE:-015}" = 022 ] || falha "jornada-ativacao so em ensaio-* atualizado para a 022"
    confere_liberacao "$TRAVA" > /dev/null || falha "ambiente BLOQUEADO (ver homologacao.sh estado $HOMOLOG_ID)"
    exec bash "$AQUI/testes/jornada_ativacao.sh"
    ;;

# ==================================================================================================== email-real
# Transporte REAL de e-mail num ensaio-* LIBERADO com 021/022 (missao do e-mail real em homologacao; relatorio
# docs/relatorios/2026-10-24-email-real-homologacao.md). Pre-condicoes conferidas ANTES de qualquer mudanca. Segredos no
# diretorio de segredos do ambiente, subpasta m365/ (fora do Git): aws.env (credenciais TEMPORARIAS), destinatarios.txt
# (lista fechada), fila.env (chave propria da fila, gerada aqui), entrada/ (mensagens recebidas salvas como .eml).
#   fila           SOMENTE LEITURA: mensagens da mail_outbox por estado e tipo (nunca destinatario nem conteudo)
#   imagens        imagens da Platform/Portal/operacao do commit atual SEM mudar migrations (copia do ambiente igual ao
#                  repositorio e nenhuma versao nova); servicos parados; depois "subir"
#   ligar          troca compose.caixa.yml por compose.m365.yml: exige imagens com a leitura do segredo da AWS, credenciais
#                  temporarias validas por >= 20 min, lista de destinatarios valida e a fila SEM mensagens pendentes ou em
#                  envio (nada antigo sai). Sobe o proxy-m365 e recria a Platform; se ela nao subir, desliga sozinho
#   desligar       INTERRUPCAO: para o proxy-m365 primeiro (corta toda a saida), volta ao transporte de homologacao, apaga
#                  as credenciais temporarias; a fila fica como esta (relatada)
#   prova <remetente> <destinatario>   ProvaEnvioM365 na Platform (F1 aceito pela no-reply, F2 recusado por outra caixa)
#   conferir-eml <arquivo>             cabecalhos de uma mensagem recebida (remetente, destinatario, SPF, DKIM, DMARC)
#   jornada [--somente R1,...]         testes/jornada_email_real.py, com a varredura de logs das jornadas da API
email-real)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    sub="${3:?fila|imagens|ligar|desligar|prova|conferir-eml|jornada}"
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] || falha "email-real so em ambiente descartavel (id ensaio-*)"
    [[ "${HOMOLOG_ATE:-015}" =~ ^02[12]$ ]] || falha "email-real exige ambiente com 021 ou 022 (fila de e-mail)"
    M="$HOMOLOG_SEGREDOS_HOST/m365"; EVM="$AQUI/saidas/$HOMOLOG_ID/email-real"
    mkdir -p "$EVM"; (umask 077; mkdir -p "$M/entrada")
    INST="$(date -u +%Y%m%dT%H%M%SZ)"
    fila_estado() {
        admin_sql nekkora <<'SQL'
SELECT CASE state WHEN 1 THEN 'PENDENTE' WHEN 2 THEN 'ENVIANDO' WHEN 3 THEN 'ACEITO' WHEN 4 THEN 'RECUSADO' WHEN 5 THEN 'INCERTO' WHEN 6 THEN 'EXPIRADO' END
       || '|' || kind || '|' || count(*) || '|' || to_char(min(created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
  FROM public.mail_outbox GROUP BY state, kind ORDER BY state, kind;
SQL
    }
    fila_ativas() { admin_sql nekkora -c "SELECT count(*) FROM public.mail_outbox WHERE state IN (1, 2)"; }
    troca_extra() { local de="$1" para="$2"; printf '%s\n' "$HOMOLOG_COMPOSE_EXTRA" | sed -e "s#$de#$para#"; }
    grava_ambiente() {   # transporte e lista de sobreposicoes novos; o anterior fica em email-real/ambiente.env.<instante>
        cp "$ESTADO" "$EVM/ambiente.env.$INST"
        sed -e "s#^HOMOLOG_TRANSPORTE=.*#HOMOLOG_TRANSPORTE=$1#" -e "s#^HOMOLOG_COMPOSE_EXTRA=.*#HOMOLOG_COMPOSE_EXTRA=\"$2\"#" "$EVM/ambiente.env.$INST" > "$ESTADO"
    }
    espera_gateway() {
        local CA="$HOMOLOG_SEGREDOS_HOST/tls/ca.crt" a=""
        for i in $(seq 1 60); do
            a="$(curl -s --ssl-no-revoke -o /dev/null -w '%{http_code}' --cacert "$CA" --resolve "$HOMOLOG_HOST:$HOMOLOG_PORTA:127.0.0.1" "https://$HOMOLOG_HOST:$HOMOLOG_PORTA/api/v1/csrf" || true)"
            [ "$a" = 200 ] && return 0
            sleep 2
        done
        return 1
    }
    case "$sub" in
    fila)
        compose up -d --wait postgres > /dev/null 2>&1 || falha "postgres nao subiu"
        { echo "fila_em=$INST transporte=${HOMOLOG_TRANSPORTE:-} commit=$HOMOLOG_COMMIT versoes_ate=$HOMOLOG_ATE"
          echo "total=$(admin_sql nekkora -c 'SELECT count(*) FROM public.mail_outbox') pendentes_ou_enviando=$(fila_ativas)"
          echo "estado|tipo|quantidade|mais_antiga"; fila_estado; } | tee "$EVM/fila-$INST.txt"
        ;;
    imagens)
        [ "${HOMOLOG_TRANSPORTE:-}" = homologacao ] || falha "imagens so com o transporte de homologacao (desligue o real antes)"
        compose up -d --wait postgres redis > /dev/null 2>&1 || falha "postgres/redis nao subiram"
        confere_liberacao "$TRAVA" > /dev/null || falha "ambiente BLOQUEADO: nada alterado (homologacao.sh estado $HOMOLOG_ID)"
        EVID="$AQUI/saidas/$HOMOLOG_ID"
        div=""
        for f in "$EVID/migracoes"/*.sql; do
            n="$(basename "$f")"; r="$REPO/db/migrations/$n"
            [ -f "$r" ] && [ "$(sha256sum < "$f" | cut -c1-64)" = "$(sha256sum < "$r" | cut -c1-64)" ] || div="$div ${n%%_*}"
        done
        [ -z "$div" ] || falha "copia de migrations do ambiente diferente do repositorio:$div; nada alterado"
        for r in "$REPO"/db/migrations/[0-9][0-9][0-9]_*.sql; do
            v="$(basename "$r")"; v="${v%%_*}"
            [ "$((10#$v))" -le "$((10#$HOMOLOG_ATE))" ] || falha "o repositorio tem a $v, posterior ao ambiente ($HOMOLOG_ATE): use atualizar; nada alterado"
        done
        . "$AQUI/lib/fontes_imagens.sh"
        guarda_fontes_imagens "$REPO" >&2 || falha "entradas das imagens da API diferentes do commit; nada alterado"
        NOVO_COMMIT="$(commit_fontes_imagens "$REPO")"; [ -n "$NOVO_COMMIT" ] || falha "commit das imagens nao identificado; nada alterado"
        AT="$EVM/imagens-$INST"; mkdir -p "$AT"
        cp "$ESTADO" "$AT/ambiente.env.antes"
        echo "imagens=$INST commit_antes=$HOMOLOG_COMMIT commit_depois=$NOVO_COMMIT migracoes=iguais (ate $HOMOLOG_ATE)" > "$AT/registro.txt"
        HOMOLOG_COMMIT="$NOVO_COMMIT"; export HOMOLOG_COMMIT
        registro_fontes_imagens "$REPO" "$HOMOLOG_COMMIT" > "$AT/fontes_imagens.txt"
        (cd "$REPO/api" && ./mvnw -q -DskipTests package && ./mvnw -q -DskipTests test-compile -pl nekkora-platform) > "$AT/build.txt" 2>&1 || falha "build (ver $AT/build.txt)"
        compose build platform portal >> "$AT/build.txt" 2>&1 || falha "build das imagens"
        (cd "$REPO/api" && ./mvnw -q -pl nekkora-ops dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dep-operacao \
            && ./mvnw -q -pl nekkora-ops test-compile) >> "$AT/build.txt" 2>&1 || falha "build do job de operacao"
        compose --profile operacao build operacao >> "$AT/build.txt" 2>&1 || falha "imagem do job de operacao"
        compose stop platform portal gateway > "$AT/parada_servicos.txt" 2>&1 || falha "parada dos servicos"
        sed -e "s/^HOMOLOG_COMMIT=.*/HOMOLOG_COMMIT=$NOVO_COMMIT/" "$AT/ambiente.env.antes" > "$ESTADO"
        echo "IMAGENS: $HOMOLOG_ID com o commit $NOVO_COMMIT (migrations iguais); servicos parados. Proximo: homologacao.sh subir $HOMOLOG_ID"
        ;;
    ligar)
        [ "${HOMOLOG_TRANSPORTE:-}" = homologacao ] || falha "o transporte atual nao e o de homologacao (${HOMOLOG_TRANSPORTE:-}); nada alterado"
        case " $HOMOLOG_COMPOSE_EXTRA " in *" compose.caixa.yml "*) ;; *) falha "ambiente sem compose.caixa.yml; nada alterado";; esac
        git -C "$REPO" cat-file -e "$HOMOLOG_COMMIT:api/nekkora-platform/src/main/java/com/nekkora/platform/secrets/AwsSecretsManager.java" 2>/dev/null \
            || falha "imagens do ambiente ($HOMOLOG_COMMIT) sem a leitura do segredo da AWS: rode email-real $HOMOLOG_ID imagens e subir; nada alterado"
        [ -f "$M/aws.env" ] || falha "credenciais temporarias ausentes ($M/aws.env); nada alterado"
        python "$AQUI/lib/email_real.py" credenciais "$M/aws.env" --minimo 20 || falha "credenciais temporarias recusadas; nada alterado"
        [ -f "$M/destinatarios.txt" ] || falha "lista de destinatarios ausente ($M/destinatarios.txt); nada alterado"
        HOMOLOG_M365_DESTINATARIOS="$(python "$AQUI/lib/email_real.py" destinatarios "$M/destinatarios.txt")" || falha "lista de destinatarios recusada; nada alterado"
        export HOMOLOG_M365_DESTINATARIOS
        compose up -d --wait postgres redis > /dev/null 2>&1 || falha "postgres/redis nao subiram"
        confere_liberacao "$TRAVA" > /dev/null || falha "ambiente BLOQUEADO: nada alterado (homologacao.sh estado $HOMOLOG_ID)"
        { echo "fila_antes_de_ligar=$INST"; echo "estado|tipo|quantidade|mais_antiga"; fila_estado; } | tee "$EVM/fila-antes-de-ligar-$INST.txt"
        [ "$(fila_ativas)" = 0 ] || falha "fila com mensagens PENDENTES ou ENVIANDO (testes anteriores): nada alterado, nada enviado"
        python "$AQUI/lib/email_real.py" chave-fila "$M/fila.env" || falha "chave da fila"
        EXTRA_M365="$(troca_extra compose.caixa.yml compose.m365.yml)"
        CF="-f $COMPOSE_ARQ"; for x in $EXTRA_M365; do CF="$CF -f $(mixed "$AQUI/$x")"; done
        docker compose $CF config -q 2>/dev/null || falha "sobreposicao m365 nao renderiza; nada alterado"
        grava_ambiente m365 "$EXTRA_M365"
        COMPOSE_F="$CF"; export COMPOSE_F
        INI="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        compose up -d proxy-m365 > /dev/null 2>&1 || { bash "$0" email-real "$HOMOLOG_ID" desligar; falha "proxy-m365 nao subiu (desligado de novo)"; }
        compose up -d --force-recreate --no-deps platform > /dev/null 2>&1 || true
        if ! espera_gateway; then
            compose logs --no-color --since "$INI" platform 2>&1 | grep -oE 'evento=mail\.[a-z_]+[^"]*|IllegalStateException: [^"]{0,200}|Failure: [^"]{0,200}' | sort -u > "$EVM/ligar-falhou-$INST.txt" || true
            bash "$0" email-real "$HOMOLOG_ID" desligar
            falha "Platform nao subiu com o transporte real (eventos em $EVM/ligar-falhou-$INST.txt); desligado de novo"
        fi
        compose logs --no-color --since "$INI" platform 2>&1 | grep -oE 'evento=mail\.(segredo_carregado|fila_cifra|destinatarios_restritos|certificado_vence_em)[^"]*' \
            | sort -u | tee "$EVM/ligar-$INST.txt"
        grep -q 'evento=mail.segredo_carregado origem=aws-secrets-manager' "$EVM/ligar-$INST.txt" && grep -q 'evento=mail.destinatarios_restritos' "$EVM/ligar-$INST.txt" \
            || { bash "$0" email-real "$HOMOLOG_ID" desligar; falha "partida sem os eventos esperados (segredo, destinatarios); desligado de novo"; }
        echo "TRANSPORTE REAL LIGADO em $HOMOLOG_ID (proxy-m365 e Platform recriada). Interromper: homologacao.sh email-real $HOMOLOG_ID desligar"
        ;;
    desligar)
        INI="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        compose stop proxy-m365 > /dev/null 2>&1 || true   # 1o: corta toda a saida (Entra, Graph, Secrets Manager)
        compose rm -f proxy-m365 > /dev/null 2>&1 || true
        if [ "${HOMOLOG_TRANSPORTE:-}" = m365 ]; then
            EXTRA_CX="$(troca_extra compose.m365.yml compose.caixa.yml)"
            grava_ambiente homologacao "$EXTRA_CX"
            COMPOSE_F="-f $COMPOSE_ARQ"; for x in $EXTRA_CX; do COMPOSE_F="$COMPOSE_F -f $(mixed "$AQUI/$x")"; done; export COMPOSE_F
            compose up -d --force-recreate --no-deps platform > /dev/null 2>&1 || echo "AVISO: Platform nao recriada com o transporte de homologacao (homologacao.sh subir $HOMOLOG_ID)" >&2
        fi
        rm -f "$M/aws.env"
        { echo "desligado_em=$INST (proxy-m365 parado e removido; credenciais temporarias apagadas; transporte de homologacao)"
          echo "estado|tipo|quantidade|mais_antiga"; fila_estado; } | tee "$EVM/desligar-$INST.txt"
        ;;
    prova)
        [ "${HOMOLOG_TRANSPORTE:-}" = m365 ] || falha "prova exige o transporte real ligado (email-real $HOMOLOG_ID ligar)"
        rem="${4:?remetente}"; para="${5:?destinatario controlado}"
        rc=0
        out="$(compose exec -T platform java -Dloader.main=com.nekkora.platform.mail.ProvaEnvioM365 -cp /app/app.jar \
            org.springframework.boot.loader.launch.PropertiesLauncher --remetente "$rem" --para "$para" 2>&1)" || rc=$?
        # so as linhas da propria prova (desfecho, status, identificador): nada de pilha nem de ambiente
        printf '%s\n' "$out" | tr -d '\r' | grep -E '^prova_envio |fora de NEKKORA|ensaio-\* exige|^uso:' | tee -a "$EVM/prova-$INST.txt" || true
        echo "remetente=$( [ "$rem" = no-reply@nekkora.com ] && echo no-reply@nekkora.com || echo outra-caixa) codigo=$rc" | tee -a "$EVM/prova-$INST.txt"
        exit "$rc"
        ;;
    conferir-eml)
        arq="${4:?arquivo .eml}"
        rc=0
        python "$AQUI/lib/email_real.py" conferir-eml "$arq" --destinatarios "$M/destinatarios.txt" > "$EVM/eml-$INST.txt" || rc=$?
        cat "$EVM/eml-$INST.txt"
        exit "$rc"
        ;;
    jornada)
        [ "${HOMOLOG_TRANSPORTE:-}" = m365 ] || falha "jornada exige o transporte real ligado (email-real $HOMOLOG_ID ligar)"
        confere_liberacao "$TRAVA" > /dev/null || falha "ambiente BLOQUEADO (homologacao.sh estado $HOMOLOG_ID)"
        export HOMOLOG_BASH="${HOMOLOG_BASH:-$(mixed "$(command -v bash)")}"
        [ -f "C:/Program Files/Git/bin/bash.exe" ] && HOMOLOG_BASH="C:/Program Files/Git/bin/bash.exe"
        INICIO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        J="$EVM/jornada-$INST"; mkdir -p "$J"
        SJ="$M/jornada-$INST"; (umask 077; mkdir -p "$SJ")
        rc=0
        python "$AQUI/testes/jornada_email_real.py" --alvo "https://$HOMOLOG_HOST:$HOMOLOG_PORTA" --ca "$(mixed "$HOMOLOG_SEGREDOS_HOST/tls/ca.crt")" \
            --evid "$(mixed "$J")" --vistos "$(mixed "$SJ/vistos.txt")" --psql "admin_sql nekkora" --entrada "$(mixed "$M/entrada")" \
            --destinatarios "$(mixed "$M/destinatarios.txt")" "${@:4}" || rc=$?
        # varredura (como em jornadas-api): nenhum valor visto (senha, token, codigo, endereco) nos logs nem na evidencia, e
        # nenhum valor das credenciais temporarias e da chave da fila
        compose logs --no-color --since "$INICIO" platform portal gateway proxy-m365 > "$SJ/logs_servicos.txt" 2>&1 || falha "logs dos servicos nao coletados (varredura impossivel)"
        python - "$M/aws.env" "$M/fila.env" >> "$SJ/vistos.txt" <<'PY' || true
import sys
for p in sys.argv[1:]:
    try:
        for l in open(p, encoding='utf-8'):
            if '=' in l:
                v = l.split('=', 1)[1].strip()
                for x in [v] + v.split(':')[1:]:
                    if len(x) >= 12:
                        print(x)
    except OSError:
        pass
PY
        n_vistos=0; achados=0
        while IFS= read -r v; do
            [ -n "$v" ] || continue
            n_vistos=$((n_vistos + 1))
            if grep -qF -- "$v" "$SJ/logs_servicos.txt" || grep -rqF -- "$v" "$J" "$EVM"/*.txt 2>/dev/null; then achados=$((achados + 1)); fi
        done < <(sort -u "$SJ/vistos.txt" 2>/dev/null)
        { echo "valores_vistos=$n_vistos (senhas, tokens, codigos, enderecos, credenciais temporarias, chave da fila)"
          echo "linhas_de_log_varridas=$(wc -l < "$SJ/logs_servicos.txt" | tr -d ' ') (platform, portal, gateway, proxy-m365 desde $INICIO)"
          echo "achados=$achados"
          [ "$achados" = 0 ] && echo "VARREDURA_OK" || echo "VARREDURA_FALHOU"; } | tee "$J/varredura.txt"
        [ "$achados" = 0 ] || rc=1
        if grep -q 'evento=mail.graph status=202' "$SJ/logs_servicos.txt"; then echo "controle_positivo=OK (evento de aceite do Graph no log da Platform)" | tee -a "$J/varredura.txt"
        else echo "controle_positivo=FALHOU" | tee -a "$J/varredura.txt"; rc=1; fi
        exit "$rc"
        ;;
    *) falha "subcomando invalido";;
    esac
    ;;

# =============================================================================================== validar-config
# Validacao ESTATICA (P1b): nao cria container, rede, volume nem imagem. Gera a configuracao num diretorio temporario
# e confere com "docker compose config" (so renderiza) usando valores de exemplo, sem segredo.
validar-config)
    exec bash "$AQUI/testes/validar_config.sh"
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
