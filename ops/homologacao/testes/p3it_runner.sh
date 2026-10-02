#!/usr/bin/env bash
# P3-IT - testes REAIS do runner (implantacao/implantar.sh + Flyway + PostgreSQL do ambiente) para o vinculo por id, o
# pre-voo, a parada (HOMOLOG_IMPLANTAR_ATE), a pausa por versao e a recuperacao, com versoes SINTETICAS 9xx fora do
# repositorio (a 016 nunca entra aqui). SO em ambiente descartavel (id ensaio-*):
#
#   ops/homologacao/homologacao.sh ensaio-p3it <id> <dir_saida> ["R2 R4 R1 R3 R6"]
#
# Migrations do ensaio = 001-015 da copia do ambiente + sinteticas (sem a 016). Listas e pre-voos sinteticos ficam no
# diretorio de saida (lista alternativa, so ensaio-*). Cada caso registra comando, codigo, historico, janelas e
# estado do migrador (0|0|0 = soma dos 5 atributos | participacoes | sessoes). Sem repair/baseline/clean.
set -uo pipefail
: "${HOMOLOG_ID:?}" "${HOMOLOG_P3IT_EV:?}"
[[ "$HOMOLOG_ID" =~ ^ensaio- ]] || { echo "ERRO: so em ambiente descartavel (id ensaio-*)" >&2; exit 1; }
. "$AQUI/lib/estado.sh"
EV="$HOMOLOG_P3IT_EV"; mkdir -p "$EV"
CASOS="${HOMOLOG_P3IT_CASOS:-R2 R4 R1 R3 R6}"
RES="$EV/resumo.txt"; [ -f "$RES" ] || echo "caso|resultado|detalhe" > "$RES"
reg() { echo "$1|$2|$3" >> "$RES"; echo "[$1] $2 - $3"; }
BASE="$AQUI/saidas/$HOMOLOG_ID/migracoes"
M="$EV/migracoes_lab"; LAB="$EV/lab"
TRAVA="$AQUI/saidas/$HOMOLOG_ID/implantacao.trava"
agora() { date -u +%Y-%m-%dT%H:%M:%SZ; }

if [ ! -d "$M" ]; then
    mkdir -p "$M" "$LAB/preflight"
    for f in "$BASE"/0*.sql; do case "$(basename "$f")" in 016_*) ;; *) cp "$f" "$M/";; esac; done
fi
sint() { printf -- '-- P3-IT: migration SINTETICA de ensaio (fora do repositorio)\n%s\n' "$2" > "$M/$1"; }
sha() { sha256sum "$M/$1" | cut -d' ' -f1; }
# $1 arquivo de pre-voo; demais: uma linha SQL por argumento, gravadas LITERALMENTE (sem interpretar barras)
pf() { local f="$1"; shift; mkdir -p "$(dirname "$f")"; printf '%s\n' "$@" > "$f"; }
BS="$(printf '\134')"   # barra invertida literal, para o meta-comando do psql no pre-voo de erro de conexao
PF_OK=("SELECT 'sintetica|OK|-';" "SELECT 'PREFLIGHT_OK';")
hist() { admin_sql nekkora -c "SELECT string_agg(coalesce(version, '-') || ':' || success, ' ' ORDER BY installed_rank) FROM flyway_schema_history" 2>&1 | tr -d '\r'; }
existe() { admin_sql nekkora -c "SELECT to_regclass('public.$1') IS NOT NULL" 2>&1 | tr -d '\r'; }
# roda <caso> <lista> [VAR=valor ...]: implantar.sh REAL; limpa as variaveis de controle antes (so as dadas valem)
roda() {
    local c="$1" l="$2"; shift 2
    mkdir -p "$EV/$c"
    { echo "comando: env $* HOMOLOG_ELEVACAO_ARQ=<lab>/$(basename "$l") HOMOLOG_MIGRACOES_HOST=<lab>/migracoes_lab bash implantacao/implantar.sh"
      echo "inicio_utc=$(agora)"; echo "historico_antes=$(hist)"; echo "migrador_antes=$(estado_migrador)"
      echo "lista ($(sha256sum "$l" | cut -c1-64)):"; sed 's/^/  /' "$l"; } > "$EV/$c/caso.txt"
    env -u HOMOLOG_ENSAIO_PAUSA -u HOMOLOG_AUTORIZA_ELEVACAO -u HOMOLOG_IMPLANTAR_ATE "$@" HOMOLOG_ELEVACAO_ARQ="$l" \
        HOMOLOG_MIGRACOES="$(cygpath -m "$M")" HOMOLOG_MIGRACOES_HOST="$M" HOMOLOG_EVID="$EV/$c" \
        bash "$AQUI/implantacao/implantar.sh" > "$EV/$c/saida.txt" 2>&1
    local rc=$?
    { echo "codigo=$rc"; echo "fim_utc=$(agora)"; echo "historico_depois=$(hist)"; echo "migrador_depois=$(estado_migrador)"
      echo "janelas:"; sed 's/^/  /' "$EV/$c/janelas_de_elevacao.txt" 2>/dev/null; } >> "$EV/$c/caso.txt"
    return $rc
}
sem_janela() { [ ! -s "$EV/$1/janelas_de_elevacao.txt" ]; }
limpo() { [ "$(estado_migrador)" = "0|0|0" ]; }

# ------------------------------------------------------------------------------------------------ R2 vinculo e recusas
if [[ " $CASOS " == *" R2 "* ]]; then
    sint 901_p3it_ok.sql "CREATE TABLE p3it_901 (x int);"
    pf "$LAB/preflight/901.sql" "${PF_OK[@]}"
    S901="$(sha 901_p3it_ok.sql)"
    printf '901 %s %s\n' "$S901" "$HOMOLOG_ID" > "$LAB/l901_ok.txt"
    printf '901 %s ensaio-p3it-outro\n' "$S901" > "$LAB/l901_outro_id.txt"
    printf '901 %s %s\n' "$(printf 'outro conteudo' | sha256sum | cut -c1-64)" "$HOMOLOG_ID" > "$LAB/l901_hash_errado.txt"
    mkdir -p "$LAB/sem_preflight"; cp "$LAB/l901_ok.txt" "$LAB/sem_preflight/l901.txt"
    h0="$(hist)"
    neg() {   # $1 caso $2 lista $3 trecho esperado da recusa, demais: VAR=valor
        local c="$1" l="$2" msg="$3"; shift 3
        roda "$c" "$l" "$@"; local rc=$?
        if [ $rc = 2 ] && grep -q "$msg" "$EV/$c/saida.txt" && sem_janela "$c" && [ "$(existe p3it_901)" = f ] && [ "$(hist)" = "$h0" ] && limpo; then
            reg "$c" OK "recusa antes de conceder (codigo 2: $msg); sem janela; 901 nao executada; historico inalterado; 0|0|0"
        else reg "$c" FALHA "rc=$rc (ver $c/)"; fi
    }
    neg R2a_id_diferente "$LAB/l901_outro_id.txt" "autorizada so para o ambiente ensaio-p3it-outro" HOMOLOG_AUTORIZA_ELEVACAO=1
    neg R2b_hash_errado "$LAB/l901_hash_errado.txt" "diferente do autorizado" HOMOLOG_AUTORIZA_ELEVACAO=1
    neg R2c_sem_autorizacao "$LAB/l901_ok.txt" "HOMOLOG_AUTORIZA_ELEVACAO nao foi dado"
    neg R2d_sem_preflight "$LAB/sem_preflight/l901.txt" "sem pre-voo" HOMOLOG_AUTORIZA_ELEVACAO=1
    # positivo, com HOMOLOG_ENSAIO_PAUSA AUSENTE (R1): id correto, hash, autorizacao e pre-voo real (admin_sql) aprovados
    roda R2e_id_correto_pausa_ausente "$LAB/l901_ok.txt" HOMOLOG_AUTORIZA_ELEVACAO=1; rc=$?
    J="$EV/R2e_id_correto_pausa_ausente"
    if [ $rc = 0 ] && ! grep -q 'unbound variable' "$J/saida.txt" && grep -q 'pre-voo da 901: codigo 0 e PREFLIGHT_OK' "$J/implantacao.txt" \
       && [ "$(tr -d '\r' < "$J/preflight_901.txt" | tail -1)" = PREFLIGHT_OK ] && grep -q ' inicio versao=901 ' "$J/janelas_de_elevacao.txt" \
       && grep -q 'fim    versao=901 SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0)' "$J/janelas_de_elevacao.txt" \
       && [ "$(existe p3it_901)" = t ] && [[ "$(hist)" == *" 901:true" ]] && limpo; then
        reg R2e_id_correto_pausa_ausente OK "id exato: pre-voo real (codigo 0 + PREFLIGHT_OK), janela 901 aberta e fechada, 901 aplicada, 0|0|0; sem erro de set -u com a pausa ausente"
    else reg R2e_id_correto_pausa_ausente FALHA "rc=$rc (ver R2e_*/)"; fi
    # versao FORA da lista que exige privilegio: executada SEM elevacao, falha e nao entra no historico (limite abaixo)
    sint 903_p3it_exige_superusuario.sql "CREATE ROLE p3it_sintetico NOLOGIN;"
    h1="$(hist)"
    roda R2f_fora_da_lista "$LAB/l901_ok.txt" HOMOLOG_AUTORIZA_ELEVACAO=1; rc=$?
    papel="$(admin_sql postgres -c "SELECT count(*) FROM pg_roles WHERE rolname = 'p3it_sintetico'" | tr -d '\r')"
    if [ $rc = 2 ] && sem_janela R2f_fora_da_lista && grep -q 'permission denied to create role' "$EV/R2f_fora_da_lista/implantacao.txt" \
       && [ "$papel" = 0 ] && [ "$(hist)" = "$h1" ] && limpo; then
        reg R2f_fora_da_lista OK "versao fora da lista NAO foi elevada: executada sem privilegio, 42501, sem linha no historico (sem BEGIN proprio: transacional), papel nao criado, 0|0|0. LIMITE: com BEGIN proprio (ex.: 016) a falha pode deixar success=false; nao reproduzido aqui"
    else reg R2f_fora_da_lista FALHA "rc=$rc papel=$papel (ver R2f_*/)"; fi
    rm -f "$M/903_p3it_exige_superusuario.sql"
fi

# ------------------------------------------------------------------------------------------------ R4 pre-voo real
if [[ " $CASOS " == *" R4 "* ]]; then
    sint 904_p3it_preflight.sql "CREATE TABLE p3it_904 (x int);"
    S904="$(sha 904_p3it_preflight.sql)"
    h0="$(hist)"
    pfneg() {   # $1 caso, $2 descricao, demais: linhas do pre-voo
        local c="$1" d="$LAB/$1" desc="$2"; shift 2
        pf "$d/preflight/904.sql" "$@"
        printf '901 %s %s\n904 %s %s\n' "$(sha 901_p3it_ok.sql)" "$HOMOLOG_ID" "$S904" "$HOMOLOG_ID" > "$d/lista.txt"
        roda "$c" "$d/lista.txt" HOMOLOG_AUTORIZA_ELEVACAO=1; local rc=$?
        local msg; msg="$(grep -o 'pre-voo recusou (codigo [0-9]*, ultima linha [^;]*' "$EV/$c/saida.txt" | head -1)"
        if [ $rc = 2 ] && [ -n "$msg" ] && sem_janela "$c" && [ "$(existe p3it_904)" = f ] && [ "$(hist)" = "$h0" ] && limpo; then
            reg "$c" OK "$desc: recusa antes de conceder ($msg); sem janela; 904 nao executada; historico inalterado; 0|0|0"
        else reg "$c" FALHA "rc=$rc (ver $c/)"; fi
    }
    pfneg R4a_marcador_negativo "consulta OK com marcador negativo" "SELECT 'sintetica|FALHA|-';" "SELECT 'PREFLIGHT_FALHOU';"
    pfneg R4b_erro_sql "erro REAL de SQL (tabela inexistente)" "SELECT * FROM p3it_tabela_inexistente;" "SELECT 'PREFLIGHT_OK';"
    pfneg R4c_erro_conexao "erro REAL de conexao (meta-comando connect a banco inexistente)" "${BS}connect p3it_banco_inexistente" "SELECT 'PREFLIGHT_OK';"
    pfneg R4d_saida_vazia "saida vazia com codigo 0" "SELECT 1 WHERE false;"
    rm -f "$M/904_p3it_preflight.sql"
fi

# ------------------------------------------------------------------------------------------------ R1 pausa VAZIA
if [[ " $CASOS " == *" R1 "* ]]; then
    sint 905_p3it_pausa_vazia.sql "CREATE TABLE p3it_905 (x int);"
    pf "$LAB/preflight/905.sql" "${PF_OK[@]}"
    printf '905 %s %s\n' "$(sha 905_p3it_pausa_vazia.sql)" "$HOMOLOG_ID" > "$LAB/l905.txt"
    roda R1_pausa_vazia "$LAB/l905.txt" HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_ENSAIO_PAUSA=; rc=$?
    J="$EV/R1_pausa_vazia"
    if [ $rc = 0 ] && ! grep -q 'unbound variable' "$J/saida.txt" && grep -q 'fim    versao=905 SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0)' "$J/janelas_de_elevacao.txt" \
       && [ "$(existe p3it_905)" = t ] && limpo; then
        reg R1_pausa_vazia OK "HOMOLOG_ENSAIO_PAUSA vazia: sem erro de set -u, sem pausa; janela 905 aberta e fechada; 0|0|0"
    else reg R1_pausa_vazia FALHA "rc=$rc (ver R1_pausa_vazia/)"; fi
fi

# ------------------------------------------------------------------------------------------------ R3 parada
if [[ " $CASOS " == *" R3 "* ]]; then
    sint 906_p3it_ate.sql "CREATE TABLE p3it_906 (x int);"
    sint 907_p3it_pendente.sql "CREATE TABLE p3it_907 (x int);"
    cp "$LAB/l905.txt" "$LAB/l_r3.txt"   # nenhuma de 906/907 e elevada
    roda R3a_parada_906 "$LAB/l_r3.txt" HOMOLOG_IMPLANTAR_ATE=906; rc=$?
    J="$EV/R3a_parada_906"
    grep -A30 'Pending\|Versions' "$J/flyway_info.txt" > /dev/null 2>&1
    if [ $rc = 0 ] && grep -q 'PARADA: 907_p3it_pendente.sql e seguintes NAO aplicadas' "$J/implantacao.txt" && grep -q '== implantacao OK ate 906' "$J/implantacao.txt" \
       && [[ "$(hist)" == *" 906:true" ]] && [ "$(existe p3it_906)" = t ] && [ "$(existe p3it_907)" = f ] && grep -q '907' "$J/flyway_info.txt" && grep -qi 'pending' "$J/flyway_info.txt" && limpo; then
        reg R3a_parada_906 OK "HOMOLOG_IMPLANTAR_ATE=906: 906 aplicada, 907 pendente (flyway info: Pending), validate REAL com pendente aprovado (codigo 0), 0|0|0"
    else reg R3a_parada_906 FALHA "rc=$rc (ver R3a_parada_906/)"; fi
    # erro ADICIONAL nao e mascarado pela parada: checksum da 906 aplicada alterado -> validate falha
    cp "$M/906_p3it_ate.sql" "$EV/906_original.sql"; printf -- '-- alterada depois de aplicada (P3-IT R3b)\n' >> "$M/906_p3it_ate.sql"
    h0="$(hist)"
    roda R3b_parada_com_checksum_divergente "$LAB/l_r3.txt" HOMOLOG_IMPLANTAR_ATE=906; rc=$?
    cp "$EV/906_original.sql" "$M/906_p3it_ate.sql"
    if [ $rc = 1 ] && grep -q 'FALHA: flyway validate (pendentes ignoradas' "$EV/R3b_parada_com_checksum_divergente/saida.txt" \
       && grep -qi 'checksum mismatch' "$EV/R3b_parada_com_checksum_divergente/implantacao.txt" && [ "$(hist)" = "$h0" ] && limpo; then
        reg R3b_parada_com_checksum_divergente OK "com parada, a divergencia de checksum da 906 NAO e ignorada: validate falha (codigo 1); so as pendentes sao ignoradas"
    else reg R3b_parada_com_checksum_divergente FALHA "rc=$rc (ver R3b_*/)"; fi
    roda R3c_valor_invalido "$LAB/l_r3.txt" HOMOLOG_IMPLANTAR_ATE=16; rc=$?
    [ $rc = 3 ] && grep -q 'HOMOLOG_IMPLANTAR_ATE invalido' "$EV/R3c_valor_invalido/saida.txt" && [ "$(hist)" = "$h0" ] \
        && reg R3c_valor_invalido OK "valor invalido recusado antes de qualquer acao (codigo 3)" || reg R3c_valor_invalido FALHA "rc=$rc"
    roda R3d_sem_parada "$LAB/l_r3.txt"; rc=$?
    [ $rc = 0 ] && [[ "$(hist)" == *" 907:true" ]] && limpo \
        && reg R3d_sem_parada OK "sem a variavel, a pendente 907 e aplicada; validate normal" || reg R3d_sem_parada FALHA "rc=$rc"
fi

# ------------------------------------------------------------------------------------------------ R6 pausa por versao e recuperacao
if [[ " $CASOS " == *" R6 "* ]]; then
    sint 908_p3it_antes.sql "CREATE TABLE p3it_908 (x int);"
    sint 909_p3it_janela.sql "CREATE TABLE p3it_909 (x int);"
    pf "$LAB/preflight/909.sql" "${PF_OK[@]}"
    printf '909 %s %s\n' "$(sha 909_p3it_janela.sql)" "$HOMOLOG_ID" > "$LAB/l909.txt"
    P="$EV/R6_pausa"; mkdir -p "$EV/R6a_interrompida"; rm -f "$P" "$P.chegou"
    { echo "comando (segundo plano): env HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_ENSAIO_PAUSA=antes-do-flyway@909:<arq> ... bash implantacao/implantar.sh"
      echo "inicio_utc=$(agora)"; echo "historico_antes=$(hist)"; echo "migrador_antes=$(estado_migrador)"; } > "$EV/R6a_interrompida/caso.txt"
    ( exec env -u HOMOLOG_IMPLANTAR_ATE HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_ENSAIO_PAUSA="antes-do-flyway@909:$P" HOMOLOG_ELEVACAO_ARQ="$LAB/l909.txt" \
        HOMOLOG_MIGRACOES="$(cygpath -m "$M")" HOMOLOG_MIGRACOES_HOST="$M" HOMOLOG_EVID="$EV/R6a_interrompida" \
        bash "$AQUI/implantacao/implantar.sh" > "$EV/R6a_interrompida/saida.txt" 2>&1 ) &
    pa=$!
    for i in $(seq 1 300); do [ -e "$P.chegou" ] && break; sleep 1; done
    {
        echo "pausa atingida: $( [ -e "$P.chegou" ] && echo sim || echo NAO) ($(agora))"
        echo "historico na pausa: $(hist)"
        echo "estado do migrador na pausa: $(estado_migrador)"
        echo "janelas na pausa:"; sed 's/^/  /' "$EV/R6a_interrompida/janelas_de_elevacao.txt" 2>/dev/null
        echo "dono da trava local: $(cat "$TRAVA/dono" 2>/dev/null)"; echo "pid do runner em segundo plano: $pa"
    } >> "$EV/R6a_interrompida/caso.txt"
    na_pausa_hist="$(hist)"; na_pausa_estado="$(estado_migrador)"
    grep -q "pid=$pa " "$TRAVA/dono" 2>/dev/null && dono=sim || dono=nao
    kill -9 "$pa" 2>/dev/null
    for c in $(docker ps -q --filter "label=com.docker.compose.project=$HOMOLOG_PROJETO" --filter "label=com.docker.compose.service=flyway"); do docker kill "$c" > /dev/null; done
    sleep 3
    {
        echo "depois do kill -9 ($(agora)): migrador $(estado_migrador); trava local $( [ -d "$TRAVA" ] && echo presente || echo ausente); trava no banco ocupada: $(trava_banco_ocupada)"
        echo "-- homologacao.sh estado:"; bash "$AQUI/homologacao.sh" estado "$HOMOLOG_ID" 2>&1; echo "rc=$?"
    } > "$EV/R6b_bloqueio.txt" 2>&1
    bash "$AQUI/homologacao.sh" recuperar "$HOMOLOG_ID" > "$EV/R6c_recuperacao.txt" 2>&1; rr=$?
    { echo "reconciliacao ($(agora)): historico=$(hist); p3it_909 existe=$(existe p3it_909); migrador=$(estado_migrador); trava local $( [ -d "$TRAVA" ] && echo presente || echo ausente)"; } > "$EV/R6d_reconciliacao.txt"
    nao_aplicada=nao; [[ "$(hist)" != *"909:"* ]] && [ "$(existe p3it_909)" = f ] && nao_aplicada=sim
    echo "classificacao: $( [ $nao_aplicada = sim ] && echo 'NAO APLICADA (909 fora do historico e sem objeto)' || echo DIVERGENTE)" >> "$EV/R6d_reconciliacao.txt"
    ok6=1
    [ -e "$P.chegou" ] && [[ "$na_pausa_hist" == *" 908:true" ]] && [[ "$na_pausa_hist" != *"909:"* ]] && [ "${na_pausa_estado%%|*}" = 1 ] && [ $dono = sim ] \
        && grep -q ' inicio versao=909 ' "$EV/R6a_interrompida/janelas_de_elevacao.txt" && ! grep -q 'fim    versao=909' "$EV/R6a_interrompida/janelas_de_elevacao.txt" \
        && grep -q '^BLOQUEADO' "$EV/R6b_bloqueio.txt" && grep -q "BLOQUEIO: trava local" "$EV/R6b_bloqueio.txt" && grep -q "migrador_nekkora com estado '1|0|" "$EV/R6b_bloqueio.txt" \
        && [ $rr = 0 ] && grep -q 'estado do migrador depois da revogacao: 0|0|0' "$EV/R6c_recuperacao.txt" && [ $nao_aplicada = sim ] && limpo && ok6=0
    if [ $ok6 = 0 ]; then reg R6a_pausa_interrupcao_recuperacao OK "pausa antes-do-flyway@909 NAO parou na 908 (aplicada) e parou na 909 com a janela aberta (${na_pausa_estado}); kill -9 do dono da trava; BLOQUEADO (trava local, elevacao residual); recuperar: 0|0|0; reconciliacao: nao aplicada"
    else reg R6a_pausa_interrupcao_recuperacao FALHA "chegou=$( [ -e "$P.chegou" ] && echo s || echo n) dono=$dono estado_pausa=$na_pausa_estado recuperar=$rr nao_aplicada=$nao_aplicada (ver R6*)"; fi
    roda R6e_retomada "$LAB/l909.txt" HOMOLOG_AUTORIZA_ELEVACAO=1; rc=$?
    J="$EV/R6e_retomada"
    if [ $rc = 0 ] && grep -q 'pre-voo da 909: codigo 0 e PREFLIGHT_OK' "$J/implantacao.txt" && grep -q 'fim    versao=909 SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0)' "$J/janelas_de_elevacao.txt" \
       && [[ "$(hist)" == *" 909:true" ]] && [ "$(existe p3it_909)" = t ] && limpo; then
        reg R6e_retomada OK "retomada depois da reconciliacao: pre-voo real OK, janela 909 concluida, 909 aplicada uma unica vez, 0|0|0 (1 janela interrompida + 1 concluida da 909)"
    else reg R6e_retomada FALHA "rc=$rc (ver R6e_retomada/)"; fi
fi

# ------------------------------------------------------------------------------------------------ R015H (P3-R015)
# H02 INTEGRADO com interrupcao REAL de versao sintetica, recuperacao e reexecucao. So ambiente ensaio-* 015 NOVO (sem
# declaracao anterior). Registros reais nos caminhos que o H02 consulta: saidas/<id>/implantacao-*-950-*/ (janelas do
# runner) e saidas/<id>/recuperacao-*.txt (recuperar). Declaracao de ensaio (lib/estado.sh) escrita ANTES da execucao.
if [[ " $CASOS " == *" R015H "* ]]; then
    B="$AQUI/saidas/$HOMOLOG_ID"; LABS="$B/migracoes_ensaio"; L="$EV/R015H_lab"; H="$AQUI/homologacao.sh"
    reg0() { echo "$1|$2|$3" >> "$RES"; echo "[$1] $2 - $3"; }
    [ ! -e "$B/ensaio_sintetica.txt" ] && [ ! -e "$LABS" ] || { reg0 R015H_0 FALHA "ambiente ja tem declaracao/conjunto de ensaio (usar ambiente novo)"; exit 1; }
    [ "$(grep -c '^HOMOLOG_ATE=015$' "$B/ambiente.env")" = 1 ] || { reg0 R015H_0 FALHA "R015H exige ambiente 015"; exit 1; }
    # 1-2. sintetica fora de db/migrations, conjunto de laboratorio, lista vinculada, pre-voo e DECLARACAO (antes)
    mkdir -p "$LABS" "$L/preflight"; cp "$B/migracoes/"*.sql "$LABS/"
    printf -- '-- P3-R015: migration SINTETICA de ensaio (fora de db/migrations); efeito pequeno e verificavel\nCREATE TABLE p3r015_950 (x int);\nINSERT INTO p3r015_950 VALUES (950);\n' > "$LABS/950_p3r015_sintetica.sql"
    S950="$(sha256sum "$LABS/950_p3r015_sintetica.sql" | cut -d' ' -f1)"
    printf '950 %s %s\n' "$S950" "$HOMOLOG_ID" > "$L/lista.txt"
    pf "$L/preflight/950.sql" \
        "SELECT 'p3r015_950_ausente|' || CASE WHEN to_regclass('public.p3r015_950') IS NULL THEN 'OK' ELSE 'FALHA' END || '|-';" \
        "SELECT 'historico_sem_950|' || CASE WHEN NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '950') THEN 'OK' ELSE 'FALHA' END || '|-';" \
        "SELECT CASE WHEN to_regclass('public.p3r015_950') IS NULL AND NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '950') THEN 'PREFLIGHT_OK' ELSE 'PREFLIGHT_FALHOU' END;"
    printf '# DECLARACAO DE ENSAIO (P3-R015), escrita ANTES da execucao sintetica; nao derivada do resultado\nversao=950\nsha256=%s\narquivo=950_p3r015_sintetica.sql\nid=%s\nconjunto=migracoes_ensaio\ninterrompidas=1\nregistrada_em=%s\n' \
        "$S950" "$HOMOLOG_ID" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$B/ensaio_sintetica.txt"
    cp "$B/ensaio_sintetica.txt" "$EV/R015H_1_declaracao.txt"
    { echo "EXPECTATIVA (registrada antes da execucao): janelas reais concluidas 011 012 013 (criar); sintetica 950: 1 janela INTERROMPIDA com recuperacao propria + 1 CONCLUIDA; historico = copia 001-015 + 950; reexecucao sem elevacao; migrador 0|0|0"
      echo "declaracao sha256=$(sha256sum "$B/ensaio_sintetica.txt" | cut -c1-64)"; echo "--- conjunto de laboratorio (sha256)"; (cd "$LABS" && sha256sum *.sql)
      echo "--- lista ($(sha256sum "$L/lista.txt" | cut -c1-64))"; cat "$L/lista.txt"; } > "$EV/R015H_2_expectativa_e_conjunto.txt"
    (sintetica_declarada && [ "$SINT_VERSAO" = 950 ]) && reg0 R015H_1 OK "declaracao valida registrada antes (950, sha256 ${S950:0:12}..., id exato, conjunto = copia + sintetica)" || reg0 R015H_1 FALHA "declaracao invalida: $SINT_ERRO"
    # 3. janela sintetica pelo runner, pausa DEPOIS da elevacao e ANTES do Flyway; interrupcao SO do processo deste ensaio
    P="$EV/R015H_pausa"; rm -f "$P" "$P.chegou"; J1="$B/implantacao-$(date -u +%Y%m%dT%H%M%SZ)-950-interrompida"; mkdir -p "$J1"
    ( exec env -u HOMOLOG_IMPLANTAR_ATE HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_ENSAIO_PAUSA="antes-do-flyway@950:$P" HOMOLOG_ELEVACAO_ARQ="$L/lista.txt" \
        HOMOLOG_MIGRACOES="$(cygpath -m "$LABS")" HOMOLOG_MIGRACOES_HOST="$LABS" HOMOLOG_EVID="$J1" \
        bash "$AQUI/implantacao/implantar.sh" > "$EV/R015H_3_runner_interrompido.txt" 2>&1 ) &
    pa=$!
    for i in $(seq 1 300); do [ -e "$P.chegou" ] && break; kill -0 "$pa" 2>/dev/null || break; sleep 1; done
    e_pausa="$(estado_migrador)"; dono="$(cat "$TRAVA/dono" 2>/dev/null)"
    { echo "pausa atingida: $( [ -e "$P.chegou" ] && echo sim || echo NAO) ($(agora)); PID do runner deste ensaio: $pa; dono da trava local: $dono"
      echo "estado do migrador na pausa: $e_pausa"; echo "janelas:"; cat "$J1/janelas_de_elevacao.txt" 2>/dev/null; } > "$EV/R015H_3_pausa.txt"
    if [ -e "$P.chegou" ] && echo "$dono" | grep -q "pid=$pa "; then
        kill -9 "$pa"; echo "kill -9 $pa ($(agora)) - processo exato deste ensaio (dono da trava conferido)" >> "$EV/R015H_3_pausa.txt"
    else echo "NAO interrompido: pausa nao atingida ou PID divergente" >> "$EV/R015H_3_pausa.txt"; fi
    for c in $(docker ps -q --filter "label=com.docker.compose.project=$HOMOLOG_PROJETO" --filter "label=com.docker.compose.service=flyway"); do docker kill "$c" > /dev/null; echo "docker kill $c (Flyway deste projeto)" >> "$EV/R015H_3_pausa.txt"; done
    sleep 3
    grep -q ' inicio versao=950 ' "$J1/janelas_de_elevacao.txt" && ! grep -q 'fim    versao=950' "$J1/janelas_de_elevacao.txt" && [ "${e_pausa%%|*}" = 1 ] \
        && grep -q "kill -9 $pa" "$EV/R015H_3_pausa.txt" && reg0 R015H_3 OK "janela 950 aberta pelo runner (${e_pausa}), pausa antes do Flyway, kill -9 so no PID $pa (dono da trava); janela residual" \
        || reg0 R015H_3 FALHA "pausa/interrupcao fora do esperado (ver R015H_3_*)"
    # 4. BLOQUEADO e fluxos protegidos recusados antes da recuperacao
    { echo "migrador $(estado_migrador); trava local $( [ -d "$TRAVA" ] && echo presente || echo ausente); trava no banco ocupada: $(trava_banco_ocupada)"
      echo "-- estado:"; bash "$H" estado "$HOMOLOG_ID" 2>&1; echo "rc=$?"
      echo "-- testar (tem de recusar):"; bash "$H" testar "$HOMOLOG_ID" 2>&1 | tail -2; echo "rc=${PIPESTATUS[0]}"
      echo "-- implantar (tem de recusar; saida COMPLETA em R015H_4_implantar.txt)"; } > "$EV/R015H_4_bloqueio.txt" 2>&1
    HOMOLOG_AUTORIZA_ELEVACAO=1 bash "$H" implantar "$HOMOLOG_ID" > "$EV/R015H_4_implantar.txt" 2>&1; ri=$?
    echo "implantar rc=$ri" >> "$EV/R015H_4_bloqueio.txt"
    grep -q '^BLOQUEADO' "$EV/R015H_4_bloqueio.txt" && grep -q 'BLOQUEIO: trava local' "$EV/R015H_4_bloqueio.txt" && grep -q "migrador_nekkora com estado '1|0|0'" "$EV/R015H_4_bloqueio.txt" \
        && grep -q 'testes e liberacao recusados' "$EV/R015H_4_bloqueio.txt" && [ $ri = 3 ] && grep -q 'FALHA: implantacao interrompida neste host' "$EV/R015H_4_implantar.txt" \
        && reg0 R015H_4 OK "BLOQUEADO (trava local, elevacao residual 1|0|0); testar e implantar recusados" || reg0 R015H_4 FALHA "ver R015H_4_bloqueio.txt"
    # 5. recuperar real e reconciliacao (historico e catalogo)
    bash "$H" recuperar "$HOMOLOG_ID" > "$EV/R015H_5_recuperar.txt" 2>&1; rr=$?
    { echo "reconciliacao ($(agora)): historico=$(hist); p3r015_950 existe=$(existe p3r015_950); migrador=$(estado_migrador)"; } > "$EV/R015H_5_reconciliacao.txt"
    [ $rr = 0 ] && grep -q 'estado do migrador depois da revogacao: 0|0|0' "$EV/R015H_5_recuperar.txt" && [[ "$(hist)" != *"950:"* ]] && [ "$(existe p3r015_950)" = f ] && limpo \
        && { echo "classificacao: NAO APLICADA" >> "$EV/R015H_5_reconciliacao.txt"; reg0 R015H_5 OK "recuperar: 0|0|0; 950 NAO aplicada (fora do historico, sem tabela)"; } \
        || { echo "classificacao: DIVERGENTE/INDETERMINADA" >> "$EV/R015H_5_reconciliacao.txt"; reg0 R015H_5 FALHA "recuperar rc=$rr (ver R015H_5_*)"; }
    # 6. retomada pelo runner: 950 uma unica vez
    J2="$B/implantacao-$(date -u +%Y%m%dT%H%M%SZ)-950-retomada"; mkdir -p "$J2"
    env -u HOMOLOG_ENSAIO_PAUSA -u HOMOLOG_IMPLANTAR_ATE HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_ELEVACAO_ARQ="$L/lista.txt" HOMOLOG_MIGRACOES="$(cygpath -m "$LABS")" \
        HOMOLOG_MIGRACOES_HOST="$LABS" HOMOLOG_EVID="$J2" bash "$AQUI/implantacao/implantar.sh" > "$EV/R015H_6_retomada.txt" 2>&1; rj=$?
    ck="$(admin_sql nekkora -c "SELECT count(*) || '|' || coalesce(max(checksum)::text, '-') FROM flyway_schema_history WHERE version = '950' AND success" | tr -d '\r')"
    val="$(admin_sql nekkora -c "SELECT string_agg(x::text, ',') FROM p3r015_950" 2>&1 | tr -d '\r')"
    bash "$H" estado "$HOMOLOG_ID" > "$EV/R015H_6_estado.txt" 2>&1; re=$?
    { echo "codigo=$rj; historico 950 (linhas|checksum)=$ck; p3r015_950=$val; migrador=$(estado_migrador); estado rc=$re"; echo "janelas:"; cat "$J2/janelas_de_elevacao.txt"; } > "$EV/R015H_6_conferencia.txt"
    [ $rj = 0 ] && grep -q 'pre-voo da 950: codigo 0 e PREFLIGHT_OK' "$J2/implantacao.txt" && grep -q 'fim    versao=950 SUPERUSER revogado, sessoes encerradas, estado conferido (0|0|0)' "$J2/janelas_de_elevacao.txt" \
        && [ "${ck%%|*}" = 1 ] && [ "$val" = 950 ] && limpo && [ $re = 0 ] && grep -q '^LIBERADO' "$EV/R015H_6_estado.txt" \
        && reg0 R015H_6 OK "retomada: pre-voo OK, janela 950 concluida, 950 aplicada uma vez (checksum ${ck#*|}), tabela com 950, 0|0|0, LIBERADO (declaracao valida)" \
        || reg0 R015H_6 FALHA "retomada rc=$rj ck=$ck val=$val estado=$re (ver R015H_6_*)"
    # 7. H02 COMPLETO pelo testar, com a expectativa declarada
    h02_testar() {   # $1 rotulo -> imprime "codigo|resultado H02 do resumo"; sem NOVO diretorio de testes = "codigo|TECNICA"
        local rc t antes; antes="$(ls -d "$B"/testes-* 2>/dev/null | wc -l)"
        bash "$H" testar "$HOMOLOG_ID" > "$EV/R015H_$1_testar.txt" 2>&1; rc=$?
        [ "$(ls -d "$B"/testes-* 2>/dev/null | wc -l)" -gt "$antes" ] || { echo "$rc|TECNICA"; return; }
        t="$(ls -d "$B"/testes-* 2>/dev/null | tail -1)"; cp -r "$t" "$EV/R015H_$1_$(basename "$t")"
        echo "$rc|$(grep '^H02|' "$t/resumo.txt" 2>/dev/null | cut -d'|' -f2)"
    }
    export HOMOLOG_TESTES=h02
    r7="$(h02_testar 7_h02)"
    [ "$r7" = "0|OK" ] && grep -q 'janelas concluidas: 011 012 013 950' "$EV/R015H_7_h02_testar.txt" && grep -q 'interrompidas: 1' "$EV/R015H_7_h02_testar.txt" \
        && reg0 R015H_7 OK "H02 completo (testar): concluidas 011 012 013 950; 1 interrompida com recuperacao propria; historico copia+950; reexecucao sem elevacao; codigo 0" \
        || reg0 R015H_7 FALHA "H02 = $r7 (ver R015H_7_*)"
    # 8. contraprova integrada: registro de recuperacao desta execucao movido para FORA do caminho consultado
    rec="$(ls "$B"/recuperacao-*.txt)"; [ "$(echo "$rec" | wc -l)" = 1 ] || { reg0 R015H_8 FALHA "esperado exatamente 1 registro de recuperacao"; exit 1; }
    AREA="$EV/R015H_8_area_fora_do_caminho"; mkdir -p "$AREA"; h_rec="$(sha256sum "$rec" | cut -c1-64)"
    mv "$rec" "$AREA/"; echo "movido $(basename "$rec") (sha256 $h_rec) para $(basename "$AREA") em $(agora)" > "$EV/R015H_8_movimentacao.txt"
    r8a="$(h02_testar 8a_sem_recuperacao)"; e8a="$(estado_migrador)"
    mv "$AREA/$(basename "$rec")" "$B/"; h_rest="$(sha256sum "$rec" | cut -c1-64)"; echo "restaurado em $(agora); sha256 $h_rest ($( [ "$h_rest" = "$h_rec" ] && echo identico || echo DIFERENTE))" >> "$EV/R015H_8_movimentacao.txt"
    r8b="$(h02_testar 8b_restaurado)"
    [ "$r8a" = "1|FALHA" ] && [ "$e8a" = "0|0|0" ] && grep -rq 'SEM RECUPERACAO PROPRIA' "$EV"/R015H_8a_sem_recuperacao_testes-*/h02_janelas_classificadas.txt \
        && [ "$h_rest" = "$h_rec" ] && [ "$r8b" = "0|OK" ] \
        && reg0 R015H_8 OK "contraprova: sem o registro de recuperacao, H02 REPROVADO (resultado de teste: resumo H02|FALHA, codigo 1, SEM RECUPERACAO PROPRIA) com migrador 0|0|0; restaurado byte a byte, H02 OK" \
        || reg0 R015H_8 FALHA "sem recuperacao=$r8a (migrador $e8a), restaurado=$r8b, hash ${h_rest:0:12}/${h_rec:0:12}"
    unset HOMOLOG_TESTES
fi

echo "estado final do migrador: $(estado_migrador)" | tee -a "$EV/estado_final.txt"
echo "historico final: $(hist)" | tee -a "$EV/estado_final.txt"
column -t -s'|' "$RES" 2>/dev/null || cat "$RES"
grep -q '|FALHA|' "$RES" && exit 1
exit 0
