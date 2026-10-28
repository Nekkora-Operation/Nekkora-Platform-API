#!/usr/bin/env bash
# Implantacao das migrations pela identidade dedicada (migrador_nekkora) com Flyway - homologacao.
#
# Chamado por ops/homologacao/homologacao.sh (que exporta HOMOLOG_*, compose e admin_sql). Controles:
#   - EXCLUSAO no banco (lib/estado.sh): advisory lock de sessao disputado por TODO caminho de implantacao e de
#     recuperacao, de qualquer host, obtido ANTES de qualquer acao e segurado ate o fim (cobre a janela inteira). Quem
#     nao obtem sai sem fazer nada (nao revoga nem encerra recursos de quem esta ativo). Alem disso: trava LOCAL
#     (diretorio atomico com dono) que marca implantacao interrompida neste host, e a pre-condicao 0|0|0 do migrador;
#   - PERDA DA TRAVA (so a sessao da trava cai): conceder, revogar e encerrar sessoes rodam DENTRO da sessao da trava,
#     que antes confere que ainda segura o lock; o Flyway so conecta com o lock no backend desta execucao (initSql).
#     Perdida a trava, o runner PARA (codigo 6) sem revogar nem encerrar sessoes (podem ser de um novo vencedor),
#     mantem a trava local e o ambiente fica BLOQUEADO ate "homologacao.sh recuperar";
#   - versoes PENDENTES aplicadas uma a uma (flyway migrate -target=<versao>), modo transacional por arquivo:
#     sem BEGIN proprio (001-011) -> executeInTransaction=true; com BEGIN/COMMIT proprio (012+) -> false;
#   - ELEVACAO so para versao listada em implantacao/elevacao.txt COM o sha256 exato do arquivo, e com
#     HOMOLOG_AUTORIZA_ELEVACAO=1; com terceira coluna na linha, so no ambiente com exatamente aquele id. Janela = uma
#     versao. Depois de cada janela e ao final: SUPERUSER revogado, sessoes do migrador encerradas, atributos e
#     memberships finais conferidos;
#   - FALHA NA REVOGACAO OU NA CONFERENCIA e falha da implantacao: o runner para (codigo 4), MANTEM a trava e exige
#     "homologacao.sh recuperar". Falha de migration: tenta revogar e conferir; so libera a trava se o estado estiver limpo;
#   - revogacao EXECUTADA E CONFERIDA nos caminhos testados (fim normal, falha de migration, sinal capturavel). Um
#     encerramento abrupto (SIGKILL, queda do host) nao executa nada: a trava e a elevacao podem ficar, e a recuperacao
#     independente ("homologacao.sh recuperar") detecta, revoga e confere; enquanto isso o ambiente fica BLOQUEADO;
#   - FALHA: sem nova tentativa, sem "flyway repair", sem baseline, sem clean.
#   - PRE-VOO (P3): versao cuja linha na lista tem terceira coluna (vinculada a um id) so e elevada se
#     implantacao/preflight/<versao>.sql existir (versao sintetica sem pre-voo operacional: <dir da lista alternativa>/preflight)
#     e a consulta terminar com codigo 0 E PREFLIGHT_OK na ultima linha; roda com a trava
#     obtida, depois do sha256 e do 0|0|0 e ANTES de conceder (saida em preflight_<versao>.txt);
#   - PARADA (P3): HOMOLOG_IMPLANTAR_ATE=<versao> aplica so ate ela (inclusive); as seguintes ficam pendentes e o
#     "flyway validate" final ignora SO as pendentes.
set -uo pipefail

: "${HOMOLOG_EVID:?}" "${HOMOLOG_MIGRACOES_HOST:?}" "${HOMOLOG_ID:?}"
DIR_IMPL="$(cd "$(dirname "$0")" && pwd)"
# HOMOLOG_ELEVACAO_ARQ: lista alternativa SO em ambiente descartavel (id ensaio-*); o padrao e a lista revisada
if [ -n "${HOMOLOG_ELEVACAO_ARQ:-}" ] && [[ ! "$HOMOLOG_ID" =~ ^ensaio- ]]; then
    echo "FALHA: lista de elevacao alternativa (HOMOLOG_ELEVACAO_ARQ) so em ambiente descartavel (id ensaio-*)" >&2; exit 3
fi
LISTA="${HOMOLOG_ELEVACAO_ARQ:-$DIR_IMPL/elevacao.txt}"
# Versoes com VINCULO e PRE-VOO OFICIAL obrigatorios (VINCULO_OBRIGATORIO, de lib/lista_elevacao.sh: 016-020, 022-024 e 026): a linha
# precisa do id exato do ambiente e o pre-voo vem SO de implantacao/preflight/<versao>.sql (nunca da lista alternativa).
# 011-013 seguem no formato legado.
# A lista e validada ANTES de qualquer acao (trava, elevacao ou migration) pela gramatica de lib/lista_elevacao.sh
# (P4-R2a-C): legado "VVV <sha256> [<id>]" = SUPERUSER; forma nova "VVV <sha256> tipo=superuser|bypassrls [ambiente=<id>]";
# 014 so bypassrls; versao de vinculo obrigatorio so superuser e com ambiente. Erro de leitura ou de formato recusa
# (codigo 3); NUNCA equivale a "versao fora da lista". LISTA_NORMAL: "VVV SHA256 TIPO ALVO" (ALVO "-" sem vinculo).
. "$DIR_IMPL/../lib/lista_elevacao.sh"   # tambem define VINCULO_OBRIGATORIO
LISTA_NORMAL="$(lista_elevacao_normalizada "$LISTA" "$VINCULO_OBRIGATORIO")" || { echo "FALHA: lista de elevacao recusada (acima); nada foi feito" >&2; exit 3; }
TRAVA="${HOMOLOG_TRAVA:-$(cd "$DIR_IMPL/.." && pwd)/saidas/$HOMOLOG_ID/implantacao.trava}"
LOG="$HOMOLOG_EVID/implantacao.txt"
JANELA="$HOMOLOG_EVID/janelas_de_elevacao.txt"
ATE_MAX="${HOMOLOG_IMPLANTAR_ATE:-}"
[ -z "$ATE_MAX" ] || [[ "$ATE_MAX" =~ ^[0-9]{3}$ ]] || { echo "FALHA: HOMOLOG_IMPLANTAR_ATE invalido ($ATE_MAX)" >&2; exit 3; }
ELEVADO=""
ELEVADO_TIPO=""   # SUPERUSER | BYPASSRLS (texto dos registros; o de SUPERUSER e o mesmo de antes)
TRAVA_MINHA=""

agora() { date -u +%Y-%m-%dT%H:%M:%S.%3NZ; }
registra() { echo "$*" | tee -a "$LOG"; }


. "$DIR_IMPL/../lib/estado.sh"   # estado_migrador

# revoga, encerra sessoes e confere; codigo 0 so com estado limpo conferido (0 atributos, 0 memberships, 0 sessoes)
# (acoes pela sessao da trava: sem a trava, nada e revogado nem encerrado)
revoga_e_confere() {
    local motivo="$1" e
    sql_na_trava "ALTER ROLE migrador_nekkora NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS NOREPLICATION;
        PERFORM pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'migrador_nekkora'" || return 1
    sleep 1
    e="$(estado_migrador)"
    [ "$e" = "0|0|0" ] || return 1
    [ -n "$ELEVADO" ] && echo "$(agora) fim    versao=$ELEVADO $ELEVADO_TIPO revogado, sessoes encerradas, estado conferido (0|0|0) [$motivo]" >> "$JANELA"
    ELEVADO=""; ELEVADO_TIPO=""
    return 0
}

libera_trava() { [ -n "$TRAVA_MINHA" ] && rm -rf "$TRAVA" && TRAVA_MINHA=""; }

# trava no banco perdida: nenhuma acao adicional, por nenhuma conexao
trava_perdida() {
    registra "TRAVA NO BANCO PERDIDA ($1): nenhuma acao adicional - sem revogar e sem encerrar sessoes, que podem ser de outro executor. Trava local MANTIDA ($TRAVA). Ambiente BLOQUEADO ate 'homologacao.sh recuperar $HOMOLOG_ID'."
    [ -n "$ELEVADO" ] && echo "$(agora) TRAVA NO BANCO PERDIDA versao=$ELEVADO - sem revogacao por este executor; trava local mantida" >> "$JANELA"
    exit 6
}

# fim por erro: revogar e conferir; so libera a trava com estado limpo
encerra_com_falha() {
    local cod="$1" msg="$2"
    registra "FALHA: $msg"
    confere_trava || trava_perdida "depois de: $msg"
    if revoga_e_confere "falha"; then
        libera_trava
        registra "estado do migrador conferido limpo; trava liberada. Sem nova tentativa, sem repair, sem baseline."
        exit "$cod"
    fi
    confere_trava || trava_perdida "na revogacao depois de: $msg"
    registra "FALHA NA REVOGACAO OU NA CONFERENCIA: elevacao residual possivel. Trava MANTIDA ($TRAVA). Ambiente BLOQUEADO ate 'homologacao.sh recuperar $HOMOLOG_ID'."
    [ -n "$ELEVADO" ] && echo "$(agora) FALHA NA REVOGACAO versao=$ELEVADO - trava mantida" >> "$JANELA"
    exit 4
}

# ---------------------------------------------------------------------------------------------- exclusao e pre-condicoes
# ganchos SO de ensaio (recusados fora de ambiente descartavel): largada comum, para disputar a entrada ao mesmo tempo;
# pausa num ponto ("antes-de-elevar" ou "antes-do-flyway", em qualquer versao; ou "<ponto>@<versao>", so naquela
# versao), para derrubar a sessao da trava ou interromper o runner ali
if [ -n "${HOMOLOG_ENSAIO_LARGADA:-}${HOMOLOG_ENSAIO_PAUSA:-}" ] && [[ ! "$HOMOLOG_ID" =~ ^ensaio- ]]; then
    echo "FALHA: ganchos de ensaio so em ambiente descartavel (id ensaio-*)" >&2; exit 3
fi
if [ -n "${HOMOLOG_ENSAIO_LARGADA:-}" ]; then while [ ! -e "$HOMOLOG_ENSAIO_LARGADA" ]; do sleep 0.05; done; fi
ensaio_pausa() {   # $1 ponto, $2 versao (set -u: a variavel so e expandida depois de conferida)
    [ -n "${HOMOLOG_ENSAIO_PAUSA:-}" ] || return 0
    local ponto="${HOMOLOG_ENSAIO_PAUSA%%:*}"
    [ "$ponto" = "$1" ] || [ "$ponto" = "$1@$2" ] || return 0
    local f="${HOMOLOG_ENSAIO_PAUSA#*:}"
    touch "$f.chegou"
    while [ ! -e "$f" ]; do sleep 0.05; done
}
mkdir -p "$HOMOLOG_EVID"
if ! adquire_trava_banco; then
    registra "FALHA: outra implantacao ou recuperacao ativa (trava no banco ocupada) ou banco sem resposta. Nada foi feito: nenhuma elevacao, revogacao ou encerramento de sessao."
    exit 3
fi
trap 'libera_trava_banco' EXIT
mkdir -p "$(dirname "$TRAVA")"
if ! mkdir "$TRAVA" 2>/dev/null; then
    registra "FALHA: implantacao interrompida neste host (trava local $TRAVA: $(cat "$TRAVA/dono" 2>/dev/null)). Nada foi feito. homologacao.sh recuperar $HOMOLOG_ID"
    exit 3
fi
TRAVA_MINHA=1
printf 'pid=%s host=%s inicio=%s id=%s\n' "$$" "$(hostname)" "$(agora)" "$HOMOLOG_ID" > "$TRAVA/dono"
# sinais capturaveis (INT, TERM, HUP), SO depois de obtidas as travas: mesmo tratamento de falha. SIGKILL nao e
# capturavel: fica para a recuperacao (a sessao da trava no banco cai com o processo).
trap 'encerra_com_falha 5 "sinal recebido"' INT TERM HUP

registra "== implantacao $(agora) (lista de elevacao: $(basename "$LISTA")${HOMOLOG_ELEVACAO_ARQ:+ ALTERNATIVA DE ENSAIO} sha256 $(sha256sum "$LISTA" 2>/dev/null | cut -c1-64))"
e0="$(estado_migrador)"
if [ "$e0" != "0|0|0" ]; then
    libera_trava
    registra "FALHA: pre-condicao - estado do migrador '${e0:-banco sem resposta}' (esperado 0|0|0: sem atributo elevado, sem membership, sem sessao ativa; residuo de implantacao interrompida). Nada foi feito."
    exit 3
fi
if [ "$(admin_sql nekkora -c "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")" = t ]; then
    h="$(admin_sql nekkora -c "SELECT string_agg(coalesce(version, '-') || ':' || success, ' ' ORDER BY installed_rank) FROM public.flyway_schema_history")"
else
    h=""
fi
registra "historico antes: ${h:-vazio}"
case " $h " in *":false "*) libera_trava; registra "FALHA: historico com migration success=false: intervencao manual revisada (IMPLANTACAO.md); o runner nao repara"; exit 3;; esac

# ---------------------------------------------------------------------------------------------- versoes pendentes
for f in "$HOMOLOG_MIGRACOES_HOST"/[0-9]*.sql; do
    n="$(basename "$f")"
    v="${n%%_*}"
    case " $h " in *" $v:true "*|"$v:true "*) continue;; esac
    if [ -n "$ATE_MAX" ] && [ "$((10#$v))" -gt "$((10#$ATE_MAX))" ]; then
        registra "-- PARADA: $n e seguintes NAO aplicadas (HOMOLOG_IMPLANTAR_ATE=$ATE_MAX)"
        break
    fi
    modo=true
    grep -qE '^\s*BEGIN\s*;' "$f" && modo=false
    autorizado="$(printf '%s\n' "$LISTA_NORMAL" | awk -v v="$v" '$1 == v {print $2}')"
    tipo="$(printf '%s\n' "$LISTA_NORMAL" | awk -v v="$v" '$1 == v {print $3}')"
    alvo="$(printf '%s\n' "$LISTA_NORMAL" | awk -v v="$v" '$1 == v && $4 != "-" {print $4}')"
    # versao de vinculo obrigatorio SEM linha na lista: a migration exige superusuario (medido) e, sem a janela, falharia
    # dentro do Flyway (com BEGIN proprio, historico success=false). Recusa ANTES de chamar o Flyway, sem efeito.
    if [ -z "$autorizado" ]; then
        case "$VINCULO_OBRIGATORIO" in *" $v "*) encerra_com_falha 2 "versao $v exige elevacao vinculada e nao esta na lista de elevacao; nada aplicado";; esac
    fi
    if [ -n "$autorizado" ]; then
        # terceira coluna: a elevacao desta versao so vale no ambiente com exatamente este id; OBRIGATORIA para as versoes
        # de VINCULO_OBRIGATORIO (defesa em profundidade: valida_lista ja recusou a linha sem id)
        case "$VINCULO_OBRIGATORIO" in *" $v "*) [ -n "$alvo" ] || encerra_com_falha 2 "versao $v exige vinculo ao id exato do ambiente na lista; elevacao RECUSADA antes de conceder";; esac
        [ -z "$alvo" ] || [ "$alvo" = "$HOMOLOG_ID" ] || encerra_com_falha 2 "versao $v: elevacao autorizada so para o ambiente $alvo (este: $HOMOLOG_ID); RECUSADA antes de conceder"
        [ "${HOMOLOG_AUTORIZA_ELEVACAO:-}" = 1 ] || encerra_com_falha 2 "versao $v exige elevacao e HOMOLOG_AUTORIZA_ELEVACAO nao foi dado"
        real="$(sha256sum "$f" | cut -d' ' -f1)"
        [ "$real" = "$autorizado" ] || encerra_com_falha 2 "versao $v: sha256 $real diferente do autorizado ($autorizado); elevacao RECUSADA antes de conceder"
        ei="$(estado_migrador)"
        [ "$ei" = "0|0|0" ] || encerra_com_falha 2 "versao $v: estado '$ei' antes de elevar (sessao ativa de outra implantacao?)"
        if [ -n "$alvo" ]; then
            pf="$DIR_IMPL/preflight/$v.sql"
            # Pre-voo alternativo SO para versoes SINTETICAS de ensaio (9xx, fora do repositorio), com lista alternativa (so
            # ensaio-*) e sem pre-voo oficial. Versao de vinculo obrigatorio (016-020, 022-024, 026) nunca usa o alternativo: sem o oficial, recusa.
            if [ ! -f "$pf" ] && [ -n "${HOMOLOG_ELEVACAO_ARQ:-}" ] && [[ "$v" =~ ^9[0-9]{2}$ ]]; then pf="$(dirname "$LISTA")/preflight/$v.sql"; fi
            [ -f "$pf" ] || encerra_com_falha 2 "versao $v vinculada a $alvo sem pre-voo ($pf); elevacao RECUSADA antes de conceder"
            # as DUAS condicoes: consulta com codigo 0 E marcador final PREFLIGHT_OK
            admin_sql nekkora < "$pf" > "$HOMOLOG_EVID/preflight_$v.txt" 2>&1; rc_pf=$?
            ult_pf="$(tr -d '\r' < "$HOMOLOG_EVID/preflight_$v.txt" | tail -1)"
            [ "$rc_pf" = 0 ] && [ "$ult_pf" = PREFLIGHT_OK ] \
                || encerra_com_falha 2 "versao $v: pre-voo recusou (codigo $rc_pf, ultima linha '$ult_pf'; ver preflight_$v.txt); elevacao RECUSADA antes de conceder"
            registra "pre-voo da $v: codigo 0 e PREFLIGHT_OK (preflight_$v.txt)"
        fi
        ensaio_pausa antes-de-elevar "$v"
        case "$tipo" in
            superuser) atributo=SUPERUSER ;;
            bypassrls) atributo=BYPASSRLS ;;   # P4-R2a-C: so atravessa RLS (a 014), sem os demais poderes de superusuario
            *) encerra_com_falha 2 "versao $v: tipo de elevacao '$tipo' desconhecido; RECUSADA antes de conceder" ;;
        esac
        sql_na_trava "ALTER ROLE migrador_nekkora $atributo" || encerra_com_falha 2 "nao foi possivel conceder a elevacao para $v"
        ELEVADO="$v"; ELEVADO_TIPO="$atributo"
        echo "$(agora) inicio versao=$v $atributo concedido (sha256 $real autorizado)" >> "$JANELA"
    fi
    registra "-- $n (executeInTransaction=$modo${ELEVADO:+, ELEVADA})"
    confere_trava || encerra_com_falha 6 "trava no banco perdida antes de $n"
    # (conferencia pontual; a garantia para o Flyway e o initSql, que confere o lock a cada conexao)
    ensaio_pausa antes-do-flyway "$v"
    if ! flyway -executeInTransaction="$modo" migrate -target="$v" >> "$LOG" 2>&1; then
        encerra_com_falha 2 "migration $n"
    fi
    if [ -n "$ELEVADO" ]; then
        revoga_e_confere "fim da versao $v" || encerra_com_falha 4 "revogacao ou conferencia depois de $v"
    fi
done

if [ -n "$ATE_MAX" ]; then
    flyway -ignoreMigrationPatterns='*:pending' validate >> "$LOG" 2>&1 || encerra_com_falha 1 "flyway validate (pendentes ignoradas: parada em $ATE_MAX)"
else
    flyway validate >> "$LOG" 2>&1 || encerra_com_falha 1 "flyway validate"
fi
flyway info > "$HOMOLOG_EVID/flyway_info.txt" 2>&1
ef="$(estado_migrador)"
[ "$ef" = "0|0|0" ] || encerra_com_falha 4 "conferencia final do migrador: '$ef'"
registra "estado final do migrador: 0 atributos elevados, 0 memberships, 0 sessoes"
registra "historico depois: $(admin_sql nekkora -c "SELECT string_agg(coalesce(version, '-') || ':' || success, ' ' ORDER BY installed_rank) FROM public.flyway_schema_history")"
libera_trava
registra "== implantacao OK${ATE_MAX:+ ate $ATE_MAX (PARADA: versoes seguintes pendentes)}"
