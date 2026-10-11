# Guardas do gateway para a SELECAO de jornadas (P-B-C1-R1), usadas pelo runner em
# "jornadas-frontend <id> executar --somente ...". Arquivo para "source"; exige do chamador a funcao compose (docker
# compose do ambiente; nos testes, um compose controlado).
#
# selecao_executa <dir_evidencia> <comando das jornadas...>
#   1. PRE-CONDICAO, conferida imediatamente antes das jornadas: coleta dos logs do gateway desde DESDE = (agora - 31 s,
#      truncado ao segundo) ate o instante da consulta. A COLETA e verificada separadamente da contagem: gateway fora
#      de execucao, falha do compose ps, do compose logs ou do filtro = pre-condicao NAO CONFERIDA (codigo 4), com o
#      erro preservado; requisicao no intervalo = trafego recente (codigo 5). Em ambos, NENHUMA jornada.
#   2. UMA chamada as jornadas (sem repeticao). Codigo registrado em resultado_funcional.txt.
#   3. CONFERENCIA de trafego: nova coleta desde o MESMO DESDE (cobre o trecho entre a consulta e o inicio das jornadas
#      e toda a execucao). Coleta indisponivel, resumo sem respostas_validadas ou contagem divergente = INCONCLUSIVA.
#      Contagens iguais = COMPATIVEL com trafego exclusivo (nao e prova absoluta de ausencia de concorrencia).
#      Resultado em conferencia_trafego.txt, separado do funcional. Nada e repetido nem desfeito.
# Codigo: o das jornadas quando != 0; senao 3 se a conferencia for inconclusiva; senao 0.
# (jornadas_frontend.py usa 0, 1 e 2; 3, 4 e 5 sao so deste arquivo.)

SELECAO_PADRAO_REQ='"[A-Z]* /'

# coleta_gateway <desde> <arquivo_linhas> <arquivo_erro>: 0 = coleta valida (linhas de requisicao em arquivo_linhas)
coleta_gateway() {
    local desde="$1" saida="$2" erro="$3" ids="" st=0
    ids="$(compose ps -q --status running gateway 2>>"$erro")" || st=$?
    [ "$st" = 0 ] || { echo "compose ps terminou com codigo $st" >> "$erro"; return 1; }
    [ -n "$ids" ] || { echo "gateway fora de execucao (compose ps sem container em execucao)" >> "$erro"; return 1; }
    compose logs --no-color --timestamps --since "$desde" gateway > "$saida.bruto" 2>>"$erro" || st=$?
    [ "$st" = 0 ] || { echo "compose logs terminou com codigo $st" >> "$erro"; return 1; }
    grep -- "$SELECAO_PADRAO_REQ" "$saida.bruto" > "$saida" 2>>"$erro" || st=$?
    [ "$st" -le 1 ] || { echo "filtro das linhas terminou com codigo $st" >> "$erro"; return 1; }
    return 0
}

selecao_executa() {
    local J="$1"; shift
    local agora desde consulta inicio fim n rc=0 linhas respostas conf inconclusiva=0
    agora="$(date -u +%s)"
    desde="$(date -u -d "@$((agora - 31))" +%Y-%m-%dT%H:%M:%SZ)"
    : > "$J/pre_condicao_erro.txt"
    if ! coleta_gateway "$desde" "$J/pre_condicao_gateway.txt" "$J/pre_condicao_erro.txt"; then
        consulta="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "PRE-CONDICAO NAO CONFERIDA: falha na coleta dos logs do gateway (intervalo [$desde, $consulta]); NENHUMA jornada executada; erro em pre_condicao_erro.txt" \
            | tee "$J/pre_condicao.txt" >&2
        sed 's/^/  /' "$J/pre_condicao_erro.txt" >&2
        return 4
    fi
    consulta="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    n="$(wc -l < "$J/pre_condicao_gateway.txt" | tr -d ' ')"
    if [ "$n" != 0 ]; then
        echo "PRE-CONDICAO NAO ATENDIDA: $n requisicao(oes) no gateway no intervalo [$desde, $consulta]; NENHUMA jornada executada (aguarde e confira a ausencia de trafego concorrente)" \
            | tee "$J/pre_condicao.txt" >&2
        return 5
    fi
    inicio="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    { echo "pre-condicao conferida: 0 requisicoes no gateway no intervalo consultado [$desde, $consulta] (>= 31 s)"
      echo "jornadas iniciadas em $inicio; o trecho [$consulta, $inicio] e a execucao sao cobertos pela conferencia final (desde $desde)"; } \
        | tee "$J/pre_condicao.txt" >&2

    "$@" || rc=$?
    fim="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "jornadas: codigo $rc (inicio $inicio, fim $fim)" > "$J/resultado_funcional.txt"

    : > "$J/conferencia_erro.txt"
    if ! coleta_gateway "$desde" "$J/gateway_janela.txt" "$J/conferencia_erro.txt"; then
        conf="INCONCLUSIVA: coleta final dos logs do gateway indisponivel (erro em conferencia_erro.txt)"; inconclusiva=1
    else
        linhas="$(wc -l < "$J/gateway_janela.txt" | tr -d ' ')"
        respostas="$(python -c "import json,sys; print(int(json.load(open(sys.argv[1], encoding='utf-8'))['respostas_validadas']))" \
            "$(cygpath -m "$J/resumo_executar.json" 2>/dev/null || echo "$J/resumo_executar.json")" 2>>"$J/conferencia_erro.txt" | tr -d '\r')" || respostas=""
        if [ -z "$respostas" ]; then
            conf="INCONCLUSIVA: gateway $linhas requisicoes desde $desde; respostas validadas das jornadas indisponiveis"; inconclusiva=1
        elif [ "$linhas" = "$respostas" ]; then
            conf="COMPATIVEL com trafego exclusivo: gateway $linhas requisicoes desde $desde; jornadas $respostas respostas validadas (nao e prova absoluta de ausencia de concorrencia)"
        else
            conf="INCONCLUSIVA: gateway $linhas requisicoes desde $desde; jornadas $respostas respostas validadas (DIVERGENTES: trafego concorrente ou requisicao nao validada)"; inconclusiva=1
        fi
    fi
    echo "$conf" | tee "$J/conferencia_trafego.txt"
    echo "resultado funcional: codigo $rc; conferencia de trafego: ${conf%%:*}" | tee "$J/resultado.txt"
    [ "$rc" = 0 ] || return "$rc"
    [ "$inconclusiva" = 0 ] || return 3
    return 0
}
