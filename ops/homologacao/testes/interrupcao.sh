#!/usr/bin/env bash
# Ensaio de controles da elevacao temporaria - SO em ambiente DESCARTAVEL (id "ensaio-*"), nunca no ambiente em revisao.
# Chamado por "homologacao.sh ensaio-interrupcao <id>" (exporta HOMOLOG_*, compose, admin_sql, estado_migrador).
#
# Uma migration de ENSAIO (versao 900, fora do repositorio, com o sha256 autorizado numa lista alternativa) pausa 25 s
# DENTRO da janela de elevacao e depois falha (SELECT 1/0: rollback, sem linha no historico). Casos:
#   I1 sha256 nao autorizado: recusa ANTES de elevar;
#   I2 implantacao simultanea no mesmo host, com o primeiro ja elevado: recusada pela trava no banco;
#   I3 idem com outra trava local ("outro host"): recusada pela trava no banco;
#   I4 interrupcao abrupta: kill -9 no runner e docker kill no container do Flyway durante a janela -> elevacao residual e
#      trava ficam; ambiente BLOQUEADO (estado, testar e nova implantacao recusados) ate "recuperar", que revoga, encerra
#      sessoes, confere e so entao libera;
#   I5 banco indisponivel na janela: a sessao da trava cai junto; o runner para com codigo 6 (trava perdida), sem agir, e mantem a trava;
#      depois do banco voltar: BLOQUEADO ate "recuperar";
#   I6 corrida NA ENTRADA (antes de qualquer elevacao), 5 rodadas: dois runners com travas locais diferentes largam
#      juntos; so um prossegue; o perdedor sai sem agir, e o vencedor continua elevado e termina pela falha de ensaio
#      (division by zero), nao por conexao encerrada;
#   I7 recuperacao durante implantacao ativa: recusada pela trava no banco, sem tocar no vencedor;
#   I9 perda SO da sessao da trava (runner e banco vivos): (a) com o Flyway de A em execucao; (b) com A parado logo
#      antes de conceder a elevacao, enquanto B entra e executa; (c) com A elevado, logo depois da conferencia pontual
#      e antes do Flyway, e outro executor dono da trava. Em todos: A para com codigo 6, sem conceder, revogar nem
#      encerrar nada; o outro executor nao sofre interferencia;
#   I10 "implantar" com runner concluido e conferencia final recusada: codigo de saida diferente de zero.
set -uo pipefail

[[ "$HOMOLOG_ID" =~ ^ensaio- ]] || { echo "ERRO: este ensaio so roda em ambiente descartavel (id ensaio-*)" >&2; exit 1; }
. "$AQUI/lib/estado.sh"   # adquire_trava_banco/libera_trava_banco (I9c: o ensaio faz o papel de novo vencedor)
# casos a executar (padrao: todos); ex.: homologacao.sh ensaio-interrupcao ensaio-x "I9 I10"
CASOS="${HOMOLOG_ENSAIO_CASOS:-}"; CASOS="${CASOS:-I1 I2 I3 I4 I5 I6 I7 I9 I10}"
quer() { case " $CASOS " in *" $1 "*) return 0;; esac; return 1; }
EV="$AQUI/saidas/$HOMOLOG_ID/interrupcao-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$EV"
RES="$EV/resumo.txt"
echo "caso|resultado|detalhe" > "$RES"
ok_geral=0
registra() { echo "$1|$2|$3" >> "$RES"; echo "[$1] $2 - $3"; [ "$2" = OK ] || ok_geral=1; }

base="$AQUI/saidas/$HOMOLOG_ID/migracoes"
M="$EV/migracoes_ensaio"; mkdir -p "$M"; cp "$base"/*.sql "$M/"
printf -- '-- ENSAIO (fora do repositorio): pausa dentro da janela de elevacao e falha\nSELECT pg_sleep(25);\nSELECT 1/0;\n' > "$M/900_pausa_ensaio.sql"
SHA900="$(sha256sum "$M/900_pausa_ensaio.sql" | cut -d' ' -f1)"
printf '900 %s\n' "$SHA900" > "$EV/lista_autorizada.txt"
printf '900 %s\n' "0000000000000000000000000000000000000000000000000000000000000000" > "$EV/lista_sha_errado.txt"
M6="$EV/migracoes_i6"; mkdir -p "$M6"; cp "$base"/*.sql "$M6/"
printf -- '-- ENSAIO I6/I9, pausa curta (fora do repositorio)\nSELECT pg_sleep(8);\nSELECT 1/0;\n' > "$M6/900_pausa_ensaio.sql"
printf '900 %s\n' "$(sha256sum "$M6/900_pausa_ensaio.sql" | cut -d' ' -f1)" > "$EV/lista_i6.txt"
TRAVA_PADRAO="$AQUI/saidas/$HOMOLOG_ID/implantacao.trava"
HIST0="$(admin_sql nekkora -c "SELECT string_agg(version || ':' || success || ':' || checksum, ' ' ORDER BY installed_rank) FROM flyway_schema_history")"

impl() {   # $1 caso, $2 lista, demais: variaveis extras (VAR=valor)
    local caso="$1" lista="$2"; shift 2
    mkdir -p "$EV/$caso"
    env "$@" HOMOLOG_ELEVACAO_ARQ="$lista" HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_MIGRACOES="$(cygpath -m "$M")" HOMOLOG_MIGRACOES_HOST="$M" \
        HOMOLOG_EVID="$EV/$caso" bash "$AQUI/implantacao/implantar.sh"
}
# em segundo plano com exec: o PID devolvido ($!) e o do PROPRIO runner (o mesmo registrado na trava), nao o de um subshell
impl_bg() {
    local caso="$1" lista="$2"; shift 2
    mkdir -p "$EV/$caso"
    ( exec env "$@" HOMOLOG_ELEVACAO_ARQ="$lista" HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_MIGRACOES="$(cygpath -m "$M")" HOMOLOG_MIGRACOES_HOST="$M"         HOMOLOG_EVID="$EV/$caso" bash "$AQUI/implantacao/implantar.sh" > "$EV/${caso}_saida.txt" 2>&1 ) &
}
espera_elevado() {
    for i in $(seq 1 90); do [ "$(estado_migrador | cut -d'|' -f1)" = 1 ] && return 0; sleep 1; done; return 1
}
trava_banco() { admin_sql postgres -c "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = 20260926 AND objid = 1 AND granted" 2>/dev/null | tr -d '\r'; }
limpo() { [ "$(estado_migrador)" = "0|0|0" ] && [ ! -d "$TRAVA_PADRAO" ] && [ "$(trava_banco)" = 0 ]; }
intacto() {
    [ "$(admin_sql nekkora -c "SELECT string_agg(version || ':' || success || ':' || checksum, ' ' ORDER BY installed_rank) FROM flyway_schema_history")" = "$HIST0" ]
}
flyway_do_ambiente() { docker ps -q --filter "label=com.docker.compose.project=$HOMOLOG_PROJETO" --filter "label=com.docker.compose.service=flyway"; }

# ---------------------------------------------------------------- I1 sha256 nao autorizado
if quer I1; then
impl I1 "$EV/lista_sha_errado.txt" > "$EV/I1_saida.txt" 2>&1; rc=$?
if [ $rc = 2 ] && grep -q 'elevacao RECUSADA antes de conceder' "$EV/I1/implantacao.txt" && [ ! -s "$EV/I1/janelas_de_elevacao.txt" ] && limpo && intacto; then
    registra I1 OK "sha256 diferente do autorizado: recusado antes de conceder (rc=2), nenhuma janela aberta, estado limpo, trava liberada"
else registra I1 FALHA "rc=$rc (ver I1/)"; fi
fi

# ---------------------------------------------------------------- I2 simultanea no mesmo host (trava)
if quer I2; then
impl_bg I2A "$EV/lista_autorizada.txt"; pa=$!
espera_elevado || registra I2 FALHA "janela nao abriu"
impl I2B "$AQUI/implantacao/elevacao.txt" > "$EV/I2B_saida.txt" 2>&1; rb=$?
wait $pa; ra=$?
if [ $rb = 3 ] && grep -q 'trava no banco ocupada' "$EV/I2B/implantacao.txt" && [ $ra = 2 ] && limpo && intacto; then
    registra I2 OK "segunda implantacao durante a janela recusada pela trava no banco (rc=3, nada feito); a primeira terminou pela falha de ensaio (rc=2) com revogacao conferida"
else registra I2 FALHA "B rc=$rb, A rc=$ra (ver I2A/, I2B/)"; fi
fi

# ---------------------------------------------------------------- I3 simultanea de "outro host" (trava diferente)
if quer I3; then
impl_bg I3A "$EV/lista_autorizada.txt"; pa=$!
espera_elevado || registra I3 FALHA "janela nao abriu"
impl I3B "$AQUI/implantacao/elevacao.txt" HOMOLOG_TRAVA="$EV/trava_de_outro_host" > "$EV/I3B_saida.txt" 2>&1; rb=$?
wait $pa; ra=$?
if [ $rb = 3 ] && grep -q 'trava no banco ocupada' "$EV/I3B/implantacao.txt" && [ ! -d "$EV/trava_de_outro_host" ] && [ $ra = 2 ] && limpo && intacto; then
    registra I3 OK "implantacao com outra trava local (outro host) recusada pela trava no banco, sem fazer nada; a primeira terminou limpa"
else registra I3 FALHA "B rc=$rb, A rc=$ra (ver I3A/, I3B/)"; fi
fi

# ---------------------------------------------------------------- I4 interrupcao abrupta (SIGKILL + queda do container)
if quer I4; then
impl_bg I4 "$EV/lista_autorizada.txt"; pa=$!
espera_elevado || registra I4 FALHA "janela nao abriu"
sleep 3
grep -q "pid=$pa " "$TRAVA_PADRAO/dono" && echo "PID morto = dono da trava ($pa)" > "$EV/I4_pid.txt" || echo "PID $pa NAO e o dono da trava: $(cat "$TRAVA_PADRAO/dono")" > "$EV/I4_pid.txt"
kill -9 $pa 2>/dev/null
for c in $(flyway_do_ambiente); do docker kill "$c" > /dev/null; done
sleep 2
{
    echo "depois do kill -9 e do docker kill: estado do migrador $(estado_migrador); trava local $( [ -d "$TRAVA_PADRAO" ] && echo presente || echo ausente); trava no banco ocupada: $(trava_banco)"
    echo "-- homologacao.sh estado:"; bash "$AQUI/homologacao.sh" estado "$HOMOLOG_ID"; echo "rc=$?"
    echo "-- homologacao.sh testar (tem de recusar):"; bash "$AQUI/homologacao.sh" testar "$HOMOLOG_ID" 2>&1 | tail -3; echo "rc=${PIPESTATUS[0]}"
} > "$EV/I4_bloqueio.txt" 2>&1
impl I4N "$AQUI/implantacao/elevacao.txt" > "$EV/I4N_saida.txt" 2>&1; rn=$?
residual="$(estado_migrador | cut -d'|' -f1)"
bash "$AQUI/homologacao.sh" recuperar "$HOMOLOG_ID" > "$EV/I4_recuperacao.txt" 2>&1; rr=$?
bash "$AQUI/homologacao.sh" estado "$HOMOLOG_ID" > "$EV/I4_estado_final.txt" 2>&1; re=$?
if [ "$residual" = 1 ] && grep -q 'BLOQUEIO: trava' "$EV/I4_bloqueio.txt" && grep -q "BLOQUEIO: migrador_nekkora com estado '1|0|" "$EV/I4_bloqueio.txt" \
   && grep -q 'testes e liberacao recusados' "$EV/I4_bloqueio.txt" && grep -q '^PID morto = dono da trava' "$EV/I4_pid.txt" \
   && grep -q 'trava no banco ocupada: 0' "$EV/I4_bloqueio.txt" \
   && [ $rn = 3 ] && [ $rr = 0 ] && [ $re = 0 ] && limpo && intacto; then
    registra I4 OK "kill -9 do runner + docker kill do Flyway na janela: elevacao residual e trava detectadas; estado/testar/implantacao BLOQUEADOS; 'recuperar' revogou, encerrou sessoes, conferiu 0|0|0 e liberou; a trava no banco caiu com o processo; historico intacto"
else registra I4 FALHA "residual=$residual nova=$rn recuperar=$rr estado=$re (ver I4_*)"; fi
fi

# ---------------------------------------------------------------- I5 banco indisponivel durante a revogacao
if quer I5; then
impl_bg I5 "$EV/lista_autorizada.txt"; pa=$!
espera_elevado || registra I5 FALHA "janela nao abriu"
sleep 3
compose stop postgres > /dev/null 2>&1
wait $pa; ra=$?
compose start postgres > /dev/null 2>&1
for i in $(seq 1 60); do [ -n "$(estado_migrador)" ] && break; sleep 2; done
residual="$(estado_migrador | cut -d'|' -f1)"
bash "$AQUI/homologacao.sh" estado "$HOMOLOG_ID" > "$EV/I5_bloqueio.txt" 2>&1; rbq=$?
bash "$AQUI/homologacao.sh" recuperar "$HOMOLOG_ID" > "$EV/I5_recuperacao.txt" 2>&1; rr=$?
if [ $ra = 6 ] && grep -q 'TRAVA NO BANCO PERDIDA' "$EV/I5/implantacao.txt" && [ "$residual" = 1 ] && grep -q "trava local de implantacao presente" "$EV/I5_bloqueio.txt" && [ $rbq != 0 ] && [ $rr = 0 ] && limpo && intacto; then
    registra I5 OK "banco parado na janela: a sessao da trava caiu junto; o runner PAROU com codigo 6 (trava perdida) sem tentar revogar por outra conexao e manteve a trava local; com o banco de volta, elevacao residual detectada e ambiente BLOQUEADO ate 'recuperar', que liberou com 0|0|0"
else registra I5 FALHA "runner rc=$ra residual=$residual estado=$rbq recuperar=$rr (ver I5*)"; fi
fi

# ---------------------------------------------------------------- I6 corrida na entrada (5 rodadas)
if quer I6; then
ok6=0; det6=""
for r in 1 2 3 4 5; do
    L="$EV/I6_r$r.largada"; rm -f "$L"
    for lado in a b; do
        mkdir -p "$EV/I6_r${r}_$lado"
        ( exec env HOMOLOG_ENSAIO_LARGADA="$L" HOMOLOG_TRAVA="$EV/I6_r${r}_trava_$lado" HOMOLOG_ELEVACAO_ARQ="$EV/lista_i6.txt" HOMOLOG_AUTORIZA_ELEVACAO=1 \
            HOMOLOG_MIGRACOES="$(cygpath -m "$M6")" HOMOLOG_MIGRACOES_HOST="$M6" HOMOLOG_EVID="$EV/I6_r${r}_$lado" \
            bash "$AQUI/implantacao/implantar.sh" > "$EV/I6_r${r}_${lado}_saida.txt" 2>&1 ) &
        if [ "$lado" = a ]; then p_a=$!; else p_b=$!; fi
    done
    sleep 1; touch "$L"
    # o perdedor sai logo (nada a fazer); o vencedor segue ate a falha de ensaio
    perdedor=""
    for i in $(seq 1 150); do
        if ! kill -0 "$p_a" 2>/dev/null; then perdedor=a; break; fi
        if ! kill -0 "$p_b" 2>/dev/null; then perdedor=b; break; fi
        sleep 0.1
    done
    if [ "$perdedor" = a ]; then vencedor=b; wait "$p_a"; rp=$?; else vencedor=a; wait "$p_b"; rp=$?; fi
    espera_elevado; elev_depois="$(estado_migrador | cut -d'|' -f1)"
    if [ "$vencedor" = a ]; then wait "$p_a"; rv=$?; else wait "$p_b"; rv=$?; fi
    lp="$EV/I6_r${r}_$perdedor/implantacao.txt"; lv="$EV/I6_r${r}_$vencedor/implantacao.txt"
    if [ -n "$perdedor" ] && [ "$rp" = 3 ] && grep -q 'trava no banco ocupada' "$lp" && [ "$(grep -c . "$lp")" = 1 ] \
       && [ ! -s "$EV/I6_r${r}_$perdedor/janelas_de_elevacao.txt" ] && [ ! -d "$EV/I6_r${r}_trava_$perdedor" ] \
       && [ "$elev_depois" = 1 ] && [ "$rv" = 2 ] && grep -q 'division by zero' "$lv" && ! grep -qi 'terminating connection' "$lv" && limpo && intacto; then
        det6="$det6 r$r:venceu=$vencedor"
    else
        ok6=1; det6="$det6 r$r:FALHA(perdedor=$perdedor rp=$rp elev=$elev_depois rv=$rv)"
    fi
done
if [ $ok6 = 0 ]; then
    registra I6 OK "5 rodadas de largada simultanea antes da elevacao:$det6; em todas, o perdedor saiu com rc=3 sem revogar, encerrar sessao nem abrir janela; o vencedor seguiu elevado depois disso e terminou pela falha de ensaio (division by zero), sem conexao encerrada"
else
    registra I6 FALHA "$det6"
fi
fi

# ---------------------------------------------------------------- I7 recuperacao durante implantacao ativa
if quer I7; then
impl_bg I7A "$EV/lista_autorizada.txt" HOMOLOG_TRAVA="$EV/I7_trava_alternativa"; pa=$!
espera_elevado || registra I7 FALHA "janela nao abriu"
sleep 2
bash "$AQUI/homologacao.sh" recuperar "$HOMOLOG_ID" > "$EV/I7_recuperacao.txt" 2>&1; rr=$?
elev="$(estado_migrador | cut -d'|' -f1)"
wait $pa; ra=$?
if [ $rr != 0 ] && grep -q 'RECUSADO: implantacao ou outra recuperacao ativa (trava no banco ocupada)' "$EV/I7_recuperacao.txt" && [ "$elev" = 1 ] \
   && [ $ra = 2 ] && grep -q 'division by zero' "$EV/I7A/implantacao.txt" && ! grep -qi 'terminating connection' "$EV/I7A/implantacao.txt" && limpo && intacto; then
    registra I7 OK "recuperar durante implantacao ativa: RECUSADO pela trava no banco, nada alterado; o migrador seguiu elevado e a implantacao terminou pela falha de ensaio, sem conexao encerrada"
else
    registra I7 FALHA "recuperar rc=$rr elev=$elev runner rc=$ra (ver I7*)"
fi
fi

# ---------------------------------------------------------------- I9 perda SO da sessao da trava (runner e banco vivos)
# derruba apenas o backend que segura o lock; devolve o pid derrubado
derruba_sessao_da_trava() {
    admin_sql postgres -c "SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND classid = 20260926 AND objid = 1 AND objsubid = 2 AND granted" | tr -d '\r' > "$1"
    admin_sql postgres -c "SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype = 'advisory' AND classid = 20260926 AND objid = 1 AND objsubid = 2 AND granted" > /dev/null
    sleep 1
}
espera_arquivo() { for i in $(seq 1 1200); do [ -e "$1" ] && return 0; sleep 0.1; done; return 1; }
# executor extra com as migrations de pausa curta (M6), a lista delas e trava local propria
impl6_bg() {
    local caso="$1"; shift
    mkdir -p "$EV/$caso"
    ( exec env "$@" HOMOLOG_ELEVACAO_ARQ="$EV/lista_i6.txt" HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_MIGRACOES="$(cygpath -m "$M6")" \
        HOMOLOG_MIGRACOES_HOST="$M6" HOMOLOG_EVID="$EV/$caso" bash "$AQUI/implantacao/implantar.sh" > "$EV/${caso}_saida.txt" 2>&1 ) &
}
if quer I9; then
    # --- I9a: sessao da trava derrubada com o Flyway do runner A em execucao, dentro da janela
    impl6_bg I9aA HOMOLOG_TRAVA="$EV/I9a_trava_A"; pa=$!
    espera_elevado || registra I9a FALHA "janela nao abriu"
    sleep 2
    derruba_sessao_da_trava "$EV/I9a_pid_derrubado.txt"
    vivo_a="$(kill -0 $pa 2>/dev/null && echo sim || echo nao)"
    impl6_bg I9aB HOMOLOG_TRAVA="$EV/I9a_trava_B"; pb=$!
    wait $pb; rb=$?
    wait $pa; ra=$?
    e_depois_a="$(estado_migrador)"
    { echo "pid do backend da trava derrubado: $(cat "$EV/I9a_pid_derrubado.txt")"; echo "runner A vivo depois da queda: $vivo_a"
      echo "B: rc=$rb; A: rc=$ra"; echo "estado do migrador ao fim de A (antes de recuperar): $e_depois_a"; } > "$EV/I9a_resumo.txt"
    if [ -s "$EV/I9a_pid_derrubado.txt" ] && [ "$vivo_a" = sim ] && [ $rb = 3 ] && grep -q "pre-condicao - estado do migrador '1|0|[1-9]" "$EV/I9aB/implantacao.txt" \
       && [ ! -s "$EV/I9aB/janelas_de_elevacao.txt" ] && [ $ra = 6 ] && grep -q 'TRAVA NO BANCO PERDIDA' "$EV/I9aA/implantacao.txt" \
       && grep -q 'division by zero' "$EV/I9aA/implantacao.txt" && ! grep -q 'fim    versao' "$EV/I9aA/janelas_de_elevacao.txt" \
       && [ "${e_depois_a%%|*}" = 1 ] && [ -d "$EV/I9a_trava_A" ]; then
        bash "$AQUI/homologacao.sh" recuperar "$HOMOLOG_ID" > "$EV/I9a_recuperacao.txt" 2>&1
        rm -rf "$EV/I9a_trava_A"   # trava local alternativa do ensaio (a padrao e tratada pelo recuperar)
        if limpo && intacto; then
            registra I9a OK "sessao da trava derrubada com o Flyway de A em execucao (A e banco vivos): B obteve a trava, viu o migrador '1|0|1' e saiu sem agir (rc=3); A terminou a migration e PAROU com codigo 6 sem revogar nem encerrar sessoes (elevacao residual mantida, trava local mantida); 'recuperar' limpou"
        else registra I9a FALHA "estado depois do recuperar: $(estado_migrador)"; fi
    else registra I9a FALHA "B rc=$rb, A rc=$ra, A vivo=$vivo_a, estado=$e_depois_a (ver I9a*)"; fi

    # --- I9b: A segura a trava e para logo ANTES de conceder a elevacao; a sessao da trava cai; B entra, eleva e
    #          executa; A retoma durante a janela de B e tenta elevar
    P="$EV/I9b_pausa"
    impl6_bg I9bA HOMOLOG_TRAVA="$EV/I9b_trava_A" HOMOLOG_ENSAIO_PAUSA="antes-de-elevar:$P"; pa=$!
    espera_arquivo "$P.chegou" || registra I9b FALHA "A nao chegou ao ponto de pausa"
    derruba_sessao_da_trava "$EV/I9b_pid_derrubado.txt"
    impl6_bg I9bB HOMOLOG_TRAVA="$EV/I9b_trava_B"; pb=$!
    espera_elevado || registra I9b FALHA "B nao elevou"
    touch "$P"                       # A retoma com B elevado e com o Flyway de B em execucao
    wait $pa; ra=$?
    b_vivo="$(kill -0 $pb 2>/dev/null && echo sim || echo nao)"; e_meio="$(estado_migrador)"
    wait $pb; rb=$?
    { echo "pid do backend da trava de A derrubado: $(cat "$EV/I9b_pid_derrubado.txt")"; echo "A: rc=$ra; B vivo quando A terminou: $b_vivo; estado nesse momento: $e_meio; B: rc=$rb"; } > "$EV/I9b_resumo.txt"
    if [ $ra = 6 ] && grep -q 'TRAVA NO BANCO PERDIDA' "$EV/I9bA/implantacao.txt" && [ ! -s "$EV/I9bA/janelas_de_elevacao.txt" ] \
       && ! grep -q '^-- 900' "$EV/I9bA/implantacao.txt" && [ "$b_vivo" = sim ] && [ "${e_meio%%|*}" = 1 ] \
       && [ $rb = 2 ] && grep -q 'division by zero' "$EV/I9bB/implantacao.txt" && ! grep -qi 'terminating connection' "$EV/I9bB/implantacao.txt" \
       && grep -q 'fim    versao=900 SUPERUSER revogado' "$EV/I9bB/janelas_de_elevacao.txt" && [ "$(estado_migrador)" = "0|0|0" ] && intacto; then
        rm -rf "$EV/I9b_trava_A"
        registra I9b OK "sessao da trava de A derrubada logo antes da elevacao; B entrou, elevou e executou; A retomou durante a janela de B e PAROU com codigo 6 sem conceder, revogar nem encerrar nada (nenhuma janela de A); B seguiu elevado e terminou pela falha de ensaio, sem conexao encerrada, com a propria revogacao conferida"
    else registra I9b FALHA "A rc=$ra, B rc=$rb, B vivo=$b_vivo, estado=$e_meio (ver I9b*)"; fi

    # --- I9c: A elevado passa na conferencia pontual e para logo ANTES do Flyway; a sessao da trava cai e um novo
    #          vencedor (este ensaio) obtem a trava; A retoma: o Flyway de A precisa ser recusado pelo initSql
    P="$EV/I9c_pausa"
    impl6_bg I9cA HOMOLOG_TRAVA="$EV/I9c_trava_A" HOMOLOG_ENSAIO_PAUSA="antes-do-flyway:$P"; pa=$!
    espera_arquivo "$P.chegou" || registra I9c FALHA "A nao chegou ao ponto de pausa"
    derruba_sessao_da_trava "$EV/I9c_pid_derrubado.txt"
    adquire_trava_banco && novo="obtida (backend $TRAVA_BACKEND)" || novo="NAO obtida"
    touch "$P"
    wait $pa; ra=$?
    e_c="$(estado_migrador)"; h900="$(admin_sql nekkora -c "SELECT count(*) FROM flyway_schema_history WHERE version = '900'")"
    libera_trava_banco
    { echo "pid do backend da trava de A derrubado: $(cat "$EV/I9c_pid_derrubado.txt")"; echo "trava do novo vencedor (ensaio): $novo"
      echo "A: rc=$ra; estado ao fim de A: $e_c; linhas 900 no historico: $h900"; } > "$EV/I9c_resumo.txt"
    if [[ "$novo" == obtida* ]] && [ $ra = 6 ] && grep -q 'implantacao sem a trava no banco' "$EV/I9cA/implantacao.txt" \
       && grep -q 'TRAVA NO BANCO PERDIDA' "$EV/I9cA/implantacao.txt" && ! grep -q 'pg_sleep\|division by zero' "$EV/I9cA/implantacao.txt" \
       && [ "$h900" = 0 ] && [ "${e_c%%|*}" = 1 ]; then
        bash "$AQUI/homologacao.sh" recuperar "$HOMOLOG_ID" > "$EV/I9c_recuperacao.txt" 2>&1
        rm -rf "$EV/I9c_trava_A"
        if limpo && intacto; then
            registra I9c OK "conferencia pontual aprovada e a sessao da trava cai logo depois, com outro executor ja dono da trava: o Flyway de A foi recusado na conexao pelo initSql (nenhuma migration executada); A PAROU com codigo 6 sem revogar a elevacao; 'recuperar' limpou"
        else registra I9c FALHA "estado depois do recuperar: $(estado_migrador)"; fi
    else registra I9c FALHA "novo=$novo, A rc=$ra, estado=$e_c, h900=$h900 (ver I9c*)"; fi
fi

# ---------------------------------------------------------------- I10 implantar: codigo de saida segue a conferencia final
if quer I10; then
    REG="$AQUI/saidas/$HOMOLOG_ID/migracoes_sha256.txt"
    cp "$REG" "$EV/I10_registro_original.txt"
    echo "0000000000000000000000000000000000000000000000000000000000000000  999_divergencia_de_ensaio.sql" >> "$REG"
    bash "$AQUI/homologacao.sh" implantar "$HOMOLOG_ID" > "$EV/I10_implantar_bloqueado.txt" 2>&1; r1=$?
    cp "$EV/I10_registro_original.txt" "$REG"
    bash "$AQUI/homologacao.sh" implantar "$HOMOLOG_ID" > "$EV/I10_implantar_liberado.txt" 2>&1; r2=$?
    if [ $r1 != 0 ] && grep -q '== implantacao OK' "$EV/I10_implantar_bloqueado.txt" && grep -q '^BLOQUEADO' "$EV/I10_implantar_bloqueado.txt" \
       && grep -q 'conferencia final recusou a liberacao' "$EV/I10_implantar_bloqueado.txt" \
       && [ $r2 = 0 ] && grep -q '^LIBERADO' "$EV/I10_implantar_liberado.txt" && limpo && intacto; then
        registra I10 OK "runner concluido (implantacao OK) com a conferencia final recusada (registro de migrations divergente): implantar retornou $r1 (diferente de zero); com o registro restaurado: implantar retornou 0 e LIBERADO"
    else registra I10 FALHA "bloqueado rc=$r1, liberado rc=$r2 (ver I10_*)"; fi
fi

# ---------------------------------------------------------------- estado final do ambiente descartavel
{
    echo "historico do Flyway identico ao inicio: $(intacto && echo sim || echo NAO)"
    echo "tabela/linha da versao 900: $(admin_sql nekkora -c "SELECT count(*) FROM flyway_schema_history WHERE version = '900'")"
    echo "estado do migrador: $(estado_migrador); trava: $( [ -d "$TRAVA_PADRAO" ] && echo presente || echo ausente)"
    c=$(curl -s --ssl-no-revoke -o /dev/null -w '%{http_code}' --cacert "$HOMOLOG_SEGREDOS_HOST/tls/ca.crt" --resolve "$HOMOLOG_HOST:$HOMOLOG_PORTA:127.0.0.1" \
        "https://$HOMOLOG_HOST:$HOMOLOG_PORTA/portal/api/v1/o/00000000-0000-4000-8000-000000000000/workspaces")
    echo "Portal pelo gateway depois da volta do banco (NK401 esperado): $c"
} > "$EV/estado_final.txt"
cat "$EV/estado_final.txt"
column -t -s '|' "$RES" 2>/dev/null || cat "$RES"
echo "evidencias: $EV"
exit $ok_geral
