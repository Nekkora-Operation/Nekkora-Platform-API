# Exclusao de implantacao, estado do migrador e liberacao do ambiente - usado por implantacao/implantar.sh e por
# homologacao.sh (sourced; exige compose, admin_sql, HOMOLOG_ID, HOMOLOG_MIGRACOES e o diretorio da homologacao em AQUI).

# ------------------------------------------------------------------------------------------ exclusao no BANCO
# Um unico advisory lock de sessao no cluster da homologacao, disputado por TODO caminho de implantacao e de
# recuperacao, em qualquer host. Quem o obtem (pg_try_advisory_lock, sem espera) segura a sessao aberta durante toda
# a execucao, cobrindo a janela inteira; quem nao obtem sai ANTES de qualquer acao. Se o processo morrer (SIGKILL,
# queda), a sessao cai e o lock e liberado: o estado residual (elevacao, instalacao incompleta) continua sendo
# detectado pela liberacao, que nunca depende do lock para bloquear.
#
# PERDA DA SESSAO DA TRAVA (a sessao cai, mas o runner e o banco continuam vivos): o lock some e outro executor pode
# entrar. Por isso o executor antigo nao age por nenhuma outra conexao:
#   - toda acao privilegiada (conceder/revogar atributos, encerrar sessoes) roda DENTRO da propria sessao da trava
#     (sql_na_trava), num bloco que antes confere que esta mesma sessao ainda segura o lock. Sessao morta = acao
#     impossivel (nao ha conexao de reserva); sem resposta "OK" = falha;
#   - toda conexao do Flyway so abre se o lock estiver com o backend exato desta execucao (initSql com o pid);
#   - perdida a trava, o runner PARA sem revogar nem encerrar sessoes (podem ser de um novo vencedor), mantem a trava
#     local e deixa o ambiente BLOQUEADO para "recuperar" (codigo 6).
# Um executor novo so age com migrador 0|0|0: uma sessao do Flyway antigo ainda aberta ou uma elevacao antiga o faz
# recusar; uma conexao antiga que tente abrir depois dele encontra o lock com outro pid e e recusada pelo initSql.
CHAVE_TRAVA_BANCO="20260926, 1"
FILTRO_TRAVA="locktype = 'advisory' AND classid = 20260926 AND objid = 1 AND objsubid = 2 AND granted"
TRAVA_W=""; TRAVA_R=""; TRAVA_BACKEND=""; TRAVA_SEQ=0

adquire_trava_banco() {   # 0 = obtido (sessao aberta, pid do backend em TRAVA_BACKEND); 1 = ocupado ou banco sem resposta
    coproc TRAVA_PG { MSYS_NO_PATHCONV=1 compose exec -T -e PGPASSWORD postgres psql -X -q -At -U admin_cluster -d postgres 2>&1; }
    TRAVA_PROC="$TRAVA_PG_PID"
    # copias proprias dos descritores: o bash apaga TRAVA_PG quando o coprocesso termina
    exec {TRAVA_W}>&"${TRAVA_PG[1]}" {TRAVA_R}<&"${TRAVA_PG[0]}"
    ( printf '%s\n' "SELECT pg_try_advisory_lock($CHAVE_TRAVA_BANCO)::text || '|' || pg_backend_pid();" >&"$TRAVA_W" ) 2>/dev/null
    local r=""
    read -r -t 30 r <&"$TRAVA_R" || true
    r="${r%$'\r'}"
    if [ "${r%%|*}" = true ]; then
        TRAVA_BACKEND="${r#*|}"
        return 0
    fi
    libera_trava_banco
    return 1
}

libera_trava_banco() {
    [ -n "$TRAVA_W" ] || return 0
    ( printf '%s\n' "SELECT pg_advisory_unlock_all();" '\q' >&"$TRAVA_W" ) 2>/dev/null || true
    exec {TRAVA_W}>&- {TRAVA_R}<&-
    TRAVA_W=""; TRAVA_R=""; TRAVA_BACKEND=""
    wait "$TRAVA_PROC" 2>/dev/null || true
}

# $1: comandos PL/pgSQL. Executa na PROPRIA sessao da trava, num bloco DO que primeiro confere que esta sessao segura
# o lock. 0 so com a resposta "OK" desta chamada; sessao morta, lock ausente, erro ou 30 s sem resposta = 1.
sql_na_trava() {
    [ -n "$TRAVA_W" ] || return 1
    TRAVA_SEQ=$((TRAVA_SEQ + 1))
    local m="__trava_${TRAVA_SEQ}__" linha
    ( printf '%s\n' "DO \$\$BEGIN IF NOT EXISTS (SELECT 1 FROM pg_locks WHERE $FILTRO_TRAVA AND pid = pg_backend_pid()) THEN RAISE EXCEPTION 'trava no banco perdida'; END IF; $1; END\$\$;" \
        '\if :ERROR' "\\echo FALHA$m" '\else' "\\echo OK$m" '\endif' >&"$TRAVA_W" ) 2>/dev/null || return 1
    while read -r -t 30 linha <&"$TRAVA_R"; do
        case "${linha%$'\r'}" in
            "OK$m") return 0;;
            "FALHA$m") return 1;;
        esac
    done
    return 1
}

confere_trava() { sql_na_trava "PERFORM 1"; }   # 0 = esta execucao ainda segura a trava no banco

trava_banco_ocupada() {   # imprime "sim", "nao" ou vazio (consulta falhou)
    admin_sql postgres -c "SELECT CASE WHEN count(*) > 0 THEN 'sim' ELSE 'nao' END FROM pg_locks
        WHERE locktype = 'advisory' AND classid = 20260926 AND objid = 1 AND objsubid = 2 AND granted" 2>/dev/null | tr -d '\r'
}

# ------------------------------------------------------------------------------------------ Flyway (identidade de implantacao)
# Com a trava obtida por esta execucao, cada conexao do Flyway (identidade de implantacao) confere no initSql que o
# lock continua com o backend desta execucao; senao a conexao falha e nenhuma migration roda por ela.
flyway() {
    local cerca=()
    [ -n "$TRAVA_BACKEND" ] && cerca=("-initSql=DO \$\$BEGIN IF NOT EXISTS (SELECT 1 FROM pg_locks WHERE $FILTRO_TRAVA AND pid = $TRAVA_BACKEND) THEN RAISE EXCEPTION 'implantacao sem a trava no banco (esperada a sessao %)', $TRAVA_BACKEND; END IF; END\$\$")
    MSYS_NO_PATHCONV=1 compose run --rm -T flyway "${cerca[@]}" -configFiles=/run/secrets/flyway.conf -locations=filesystem:/flyway/sql \
        -sqlMigrationPrefix= -sqlMigrationSeparator=_ -baselineOnMigrate=false -validateOnMigrate=true -cleanDisabled=true \
        -outOfOrder=false -postgresql.transactional.lock=false "$@"
}

# ------------------------------------------------------------------------------------------ estado do migrador
# "atributos|memberships|sessoes" de migrador_nekkora (vazio se o banco nao responder). Limpo = "0|0|0".
estado_migrador() {
    admin_sql postgres -c "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' ||
        (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole OR roleid = 'migrador_nekkora'::regrole) || '|' ||
        (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')" 2>/dev/null | tr -d '\r'
}

# ------------------------------------------------------------------------------------------ liberacao em duas partes
# (1) MIGRADOR RECUPERADO: sem trava local de implantacao, sem trava no banco, migrador 0|0|0.
migrador_recuperado() {   # $1 trava local
    local n=0 e o
    if [ -d "$1" ]; then echo "BLOQUEIO: trava local de implantacao presente ($(cat "$1/dono" 2>/dev/null))"; n=$((n + 1)); fi
    o="$(trava_banco_ocupada)"
    case "$o" in
        nao) ;;
        sim) echo "BLOQUEIO: implantacao ou recuperacao em andamento (trava no banco ocupada)"; n=$((n + 1));;
        *) echo "BLOQUEIO: consulta da trava no banco falhou"; n=$((n + 1));;
    esac
    e="$(estado_migrador)"
    if [ -z "$e" ]; then echo "BLOQUEIO: banco sem resposta (estado do migrador desconhecido)"; n=$((n + 1))
    elif [ "$e" != "0|0|0" ]; then echo "BLOQUEIO: migrador_nekkora com estado '$e' (atributos elevados|memberships|sessoes ativas)"; n=$((n + 1)); fi
    [ $n = 0 ] && echo "migrador recuperado: sem trava local, trava no banco livre, migrador 0|0|0"
    return $n
}

# ------------------------------------------------------------------------------------------ versao SINTETICA de ensaio
# P3-R015: declaracao EXPLICITA saidas/<id>/ensaio_sintetica.txt (escrita ANTES da execucao sintetica) de UMA versao
# sintetica 9xx fora do repositorio, aplicada pelo runner a partir do conjunto de laboratorio saidas/<id>/migracoes_ensaio
# (= copia registrada do ambiente + a sintetica). Campos: versao, sha256, arquivo, id, conjunto (=migracoes_ensaio),
# interrompidas (janelas interrompidas esperadas da sintetica), registrada_em. Honrada SO se: id do ambiente ensaio-* e
# igual ao declarado; versao 9xx; arquivo "<versao>_*.sql" com o sha256 declarado; conjunto = copia registrada (byte a
# byte, migracoes_sha256.txt) + a sintetica, nada mais. Sem arquivo: SINT_VERSAO vazio (comportamento normal).
# Arquivo presente e invalido: codigo 1 e SINT_ERRO (quem chama BLOQUEIA; nunca aceita em silencio).
sintetica_declarada() {
    local base="$AQUI/saidas/$HOMOLOG_ID" d k v c
    SINT_VERSAO=""; SINT_SHA=""; SINT_ARQ=""; SINT_CONJ=""; SINT_INTERR=""; SINT_EM=""; SINT_ERRO=""
    d="$base/ensaio_sintetica.txt"
    [ -f "$d" ] || return 0
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] || { SINT_ERRO="declaracao de versao sintetica fora de ambiente ensaio-*"; return 1; }
    while IFS='=' read -r k v; do
        v="${v%$'\r'}"
        case "$k" in
            versao) SINT_VERSAO="$v";; sha256) SINT_SHA="$v";; arquivo) SINT_ARQ="$v";; conjunto) c="$v";;
            interrompidas) SINT_INTERR="$v";; registrada_em) SINT_EM="$v";;
            id) [ "$v" = "$HOMOLOG_ID" ] || { SINT_ERRO="declaracao para outro id ($v)"; return 1; };;
            ''|\#*) ;;
            *) SINT_ERRO="campo desconhecido na declaracao: $k"; return 1;;
        esac
    done < "$d"
    grep -q "^id=$HOMOLOG_ID\$" <(tr -d '\r' < "$d") || { SINT_ERRO="declaracao sem id"; return 1; }
    [[ "$SINT_VERSAO" =~ ^9[0-9]{2}$ ]] || { SINT_ERRO="versao sintetica invalida (so 9xx): $SINT_VERSAO"; return 1; }
    [[ "$SINT_SHA" =~ ^[0-9a-f]{64}$ ]] || { SINT_ERRO="sha256 invalido"; return 1; }
    [[ "$SINT_ARQ" =~ ^${SINT_VERSAO}_[a-z0-9_]+\.sql$ ]] || { SINT_ERRO="arquivo invalido: $SINT_ARQ"; return 1; }
    [[ "$SINT_INTERR" =~ ^[0-9]$ ]] || { SINT_ERRO="numero de interrompidas invalido"; return 1; }
    [[ "$SINT_EM" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || { SINT_ERRO="registrada_em invalido"; return 1; }
    [ "${c:-}" = migracoes_ensaio ] || { SINT_ERRO="conjunto deve ser migracoes_ensaio"; return 1; }
    SINT_CONJ="$base/migracoes_ensaio"
    [ -f "$SINT_CONJ/$SINT_ARQ" ] && [ "$(sha256sum "$SINT_CONJ/$SINT_ARQ" | cut -d' ' -f1)" = "$SINT_SHA" ] \
        || { SINT_ERRO="sintetica ausente ou com sha256 diferente do declarado"; return 1; }
    [ "$(cd "$SINT_CONJ" && ls *.sql | sort | tr '\n' ' ')" = "$( (cd "$base/migracoes" && ls *.sql; echo "$SINT_ARQ") | sort | tr '\n' ' ')" ] \
        || { SINT_ERRO="conjunto de laboratorio diferente de copia do ambiente + sintetica"; return 1; }
    (cd "$SINT_CONJ" && sha256sum $(cd "$base/migracoes" && ls *.sql)) | diff -q - "$base/migracoes_sha256.txt" > /dev/null \
        || { SINT_ERRO="migrations reais do conjunto de laboratorio diferentes da copia registrada"; return 1; }
    return 0
}

# (2) AMBIENTE APTO: conjunto aprovado integro, historico = exatamente as versoes esperadas com sucesso, flyway validate.
# Com versao sintetica de ensaio declarada e valida (sintetica_declarada): esperadas = copia + a sintetica, e o validate
# usa o conjunto de laboratorio. Declaracao invalida BLOQUEIA.
ambiente_apto() {
    local n=0 dir esperado obtido falhas v
    dir="$AQUI/saidas/$HOMOLOG_ID/migracoes"
    if ! (cd "$dir" 2>/dev/null && sha256sum *.sql) | diff -q - "$AQUI/saidas/$HOMOLOG_ID/migracoes_sha256.txt" > /dev/null 2>&1; then
        echo "BLOQUEIO: conjunto de migrations do ambiente ausente ou diferente do registrado na criacao"; n=$((n + 1))
    fi
    esperado="$(ls "$dir" 2>/dev/null | grep -E '^[0-9]+_.*\.sql$' | sed -E 's/_.*//' | sort | tr '\n' ' ' | sed 's/ $//')"
    [ -n "$esperado" ] || { echo "BLOQUEIO: lista de versoes esperadas vazia"; n=$((n + 1)); }
    local sint_mig=""
    if ! sintetica_declarada; then
        echo "BLOQUEIO: declaracao de versao sintetica de ensaio invalida ($SINT_ERRO)"; n=$((n + 1))
    elif [ -n "$SINT_VERSAO" ]; then
        esperado="$(printf '%s\n' $esperado "$SINT_VERSAO" | sort | tr '\n' ' ' | sed 's/ $//')"; sint_mig="$SINT_CONJ"
        echo "ensaio: versao sintetica $SINT_VERSAO declarada ($SINT_ARQ, sha256 ${SINT_SHA:0:12}...) incluida nas esperadas"
    fi
    if [ "$(admin_sql nekkora -c "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL" 2>/dev/null | tr -d '\r')" != t ]; then
        echo "BLOQUEIO: historico do Flyway ausente ou consulta falhou (instalacao nao iniciada ou incompleta)"; return $((n + 1))
    fi
    obtido="$(admin_sql nekkora -c "SELECT coalesce(string_agg(version, ' ' ORDER BY version), '') FROM public.flyway_schema_history
        WHERE success AND version IS NOT NULL AND type = 'SQL'" 2>/dev/null | tr -d '\r')" || obtido="__falhou__"
    falhas="$(admin_sql nekkora -c "SELECT count(*) FROM public.flyway_schema_history WHERE NOT success" 2>/dev/null | tr -d '\r')"
    [ "$falhas" = 0 ] || { echo "BLOQUEIO: historico com ${falhas:-?} migration(s) success=false ou consulta falhou"; n=$((n + 1)); }
    if [ "$obtido" != "$esperado" ]; then
        local faltam="" sobram=""
        for v in $esperado; do case " $obtido " in *" $v "*) ;; *) faltam="$faltam $v";; esac; done
        for v in $obtido; do case " $esperado " in *" $v "*) ;; *) sobram="$sobram $v";; esac; done
        echo "BLOQUEIO: instalacao incompleta ou divergente (faltam:${faltam:- nenhuma}; nao esperadas:${sobram:- nenhuma})"; n=$((n + 1))
    fi
    if [ $n = 0 ]; then
        if [ -n "$sint_mig" ]; then   # validate com o conjunto de laboratorio (copia + sintetica declarada), so aqui
            if ( export HOMOLOG_MIGRACOES="$(cygpath -m "$sint_mig" 2>/dev/null || echo "$sint_mig")"; flyway validate > /dev/null 2>&1 ); then :
            else echo "BLOQUEIO: flyway validate falhou (checksums ou historico; conjunto de laboratorio)"; n=$((n + 1)); fi
        elif flyway validate > /dev/null 2>&1; then :; else echo "BLOQUEIO: flyway validate falhou (checksums ou historico)"; n=$((n + 1)); fi
    fi
    [ $n = 0 ] && echo "ambiente apto: versoes $(echo "$esperado" | awk '{print $1"-"$NF}') todas com sucesso, conjunto integro, flyway validate OK"
    return $n
}

# 0 = LIBERADO (migrador recuperado E ambiente apto). O ambiente apto so e avaliado com o migrador recuperado.
confere_liberacao() {   # $1 trava local
    if migrador_recuperado "$1" && ambiente_apto; then
        echo "LIBERADO"
        return 0
    fi
    echo "BLOQUEADO"
    return 1
}
