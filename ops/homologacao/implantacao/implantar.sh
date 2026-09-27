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
#     HOMOLOG_AUTORIZA_ELEVACAO=1. Janela = uma versao. Depois de cada janela e ao final: SUPERUSER revogado, sessoes do
#     migrador encerradas, atributos e memberships finais conferidos;
#   - FALHA NA REVOGACAO OU NA CONFERENCIA e falha da implantacao: o runner para (codigo 4), MANTEM a trava e exige
#     "homologacao.sh recuperar". Falha de migration: tenta revogar e conferir; so libera a trava se o estado estiver limpo;
#   - revogacao EXECUTADA E CONFERIDA nos caminhos testados (fim normal, falha de migration, sinal capturavel). Um
#     encerramento abrupto (SIGKILL, queda do host) nao executa nada: a trava e a elevacao podem ficar, e a recuperacao
#     independente ("homologacao.sh recuperar") detecta, revoga e confere; enquanto isso o ambiente fica BLOQUEADO;
#   - FALHA: sem nova tentativa, sem "flyway repair", sem baseline, sem clean.
set -uo pipefail

: "${HOMOLOG_EVID:?}" "${HOMOLOG_MIGRACOES_HOST:?}" "${HOMOLOG_ID:?}"
DIR_IMPL="$(cd "$(dirname "$0")" && pwd)"
# HOMOLOG_ELEVACAO_ARQ: so nos ensaios (lista alternativa); o padrao e a lista revisada
LISTA="${HOMOLOG_ELEVACAO_ARQ:-$DIR_IMPL/elevacao.txt}"
TRAVA="${HOMOLOG_TRAVA:-$(cd "$DIR_IMPL/.." && pwd)/saidas/$HOMOLOG_ID/implantacao.trava}"
LOG="$HOMOLOG_EVID/implantacao.txt"
JANELA="$HOMOLOG_EVID/janelas_de_elevacao.txt"
ELEVADO=""
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
    [ -n "$ELEVADO" ] && echo "$(agora) fim    versao=$ELEVADO SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0) [$motivo]" >> "$JANELA"
    ELEVADO=""
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
# pausa num ponto ("antes-de-elevar" ou "antes-do-flyway"), para derrubar a sessao da trava ali
if [ -n "${HOMOLOG_ENSAIO_LARGADA:-}${HOMOLOG_ENSAIO_PAUSA:-}" ] && [[ ! "$HOMOLOG_ID" =~ ^ensaio- ]]; then
    echo "FALHA: ganchos de ensaio so em ambiente descartavel (id ensaio-*)" >&2; exit 3
fi
if [ -n "${HOMOLOG_ENSAIO_LARGADA:-}" ]; then while [ ! -e "$HOMOLOG_ENSAIO_LARGADA" ]; do sleep 0.05; done; fi
ensaio_pausa() {
    [ -n "${HOMOLOG_ENSAIO_PAUSA:-}" ] && [ "${HOMOLOG_ENSAIO_PAUSA%%:*}" = "$1" ] || return 0
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

registra "== implantacao $(agora) (lista de elevacao: $(basename "$LISTA"))"
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
    modo=true
    grep -qE '^\s*BEGIN\s*;' "$f" && modo=false
    autorizado="$(awk -v v="$v" '$1 == v {print $2}' <(grep -vE '^\s*(#|$)' "$LISTA"))"
    if [ -n "$autorizado" ]; then
        [ "${HOMOLOG_AUTORIZA_ELEVACAO:-}" = 1 ] || encerra_com_falha 2 "versao $v exige elevacao e HOMOLOG_AUTORIZA_ELEVACAO nao foi dado"
        real="$(sha256sum "$f" | cut -d' ' -f1)"
        [ "$real" = "$autorizado" ] || encerra_com_falha 2 "versao $v: sha256 $real diferente do autorizado ($autorizado); elevacao RECUSADA antes de conceder"
        ei="$(estado_migrador)"
        [ "$ei" = "0|0|0" ] || encerra_com_falha 2 "versao $v: estado '$ei' antes de elevar (sessao ativa de outra implantacao?)"
        ensaio_pausa antes-de-elevar
        sql_na_trava "ALTER ROLE migrador_nekkora SUPERUSER" || encerra_com_falha 2 "nao foi possivel conceder a elevacao para $v"
        ELEVADO="$v"
        echo "$(agora) inicio versao=$v SUPERUSER concedido (sha256 $real autorizado)" >> "$JANELA"
    fi
    registra "-- $n (executeInTransaction=$modo${ELEVADO:+, ELEVADA})"
    confere_trava || encerra_com_falha 6 "trava no banco perdida antes de $n"
    # (conferencia pontual; a garantia para o Flyway e o initSql, que confere o lock a cada conexao)
    ensaio_pausa antes-do-flyway
    if ! flyway -executeInTransaction="$modo" migrate -target="$v" >> "$LOG" 2>&1; then
        encerra_com_falha 2 "migration $n"
    fi
    if [ -n "$ELEVADO" ]; then
        revoga_e_confere "fim da versao $v" || encerra_com_falha 4 "revogacao ou conferencia depois de $v"
    fi
done

flyway validate >> "$LOG" 2>&1 || encerra_com_falha 1 "flyway validate"
flyway info > "$HOMOLOG_EVID/flyway_info.txt" 2>&1
ef="$(estado_migrador)"
[ "$ef" = "0|0|0" ] || encerra_com_falha 4 "conferencia final do migrador: '$ef'"
registra "estado final do migrador: 0 atributos elevados, 0 memberships, 0 sessoes"
registra "historico depois: $(admin_sql nekkora -c "SELECT string_agg(coalesce(version, '-') || ':' || success, ' ' ORDER BY installed_rank) FROM public.flyway_schema_history")"
libera_trava
registra "== implantacao OK"
