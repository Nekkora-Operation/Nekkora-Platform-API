#!/usr/bin/env bash
# Cenarios da 016 na homologacao DESCARTAVEL (plano de homologacao da 016, B5-B8a, B8b opcional, B10-B12; desenho P1a).
# Chamado por "homologacao.sh preparar-016 <id>" (modo preparar) e "homologacao.sh testar-016 <id>" (modo testar), que
# exportam HOMOLOG_*, compose e admin_sql. SO ambiente ensaio-* com a 016 e (para B8a/B10) o transporte de homologacao.
#
# Valores secretos sinteticos (senhas, tokens lidos da caixa, CSRF) ficam SO em HOMOLOG_SEGREDOS/teste016 (fora do
# Git) e sao conferidos contra as evidencias no fim (nenhum pode aparecer). Evidencias em saidas/<id>/016-<modo>-<instante>/.
# B8b (fixture temporal DECLARADA de solicitacao) so com H016_B8B=1. B9 (tempo real) e mini missao posterior.
set -uo pipefail

# Avaliacao de B6 sobre o arquivo de saida (separada da coleta para ser exercitada no P1c com saidas reais e contraprovas;
# "h016.sh avaliar-b6 <arquivo>" roda so a avaliacao, sem ambiente). Codigo 0 = aprovado. Cada sonda precisa da CAUSA
# esperada; disponibilidade antes e depois (valida duas vezes e SELECT 1); a CA estranha so conta com as DUAS evidencias:
# unknown_ca no cliente E "certificate verify failed" no log do servidor no intervalo da sonda; qualquer FALHA_TECNICA
# reprova; do .31, a mensagem da regra EXPLICITA de recusa do fim do pg_hba.
avalia_b6() {
    local f="$1" ok=0
    [ "$(grep -c 'sonda=valida .*resultado=CONECTOU .*conforme=sim' "$f")" = 2 ] || ok=1
    grep -q 'sonda=senha .*resultado=RECUSADA_AUTENTICACAO:28.* conforme=sim' "$f" || ok=1
    grep -q 'sonda=ca-estranha .*resultado=RECUSADA_TLS:unknown_ca .*conforme=sim' "$f" || ok=1
    grep -qi 'certificate verify failed' "$f" || ok=1
    grep -q 'sonda=cn-diferente .*resultado=RECUSADA_AUTENTICACAO:28.* conforme=sim' "$f" || ok=1
    grep -q 'FALHA_TECNICA' "$f" && ok=1
    grep '^de .31 como ' "$f" | grep -qi 'pg_hba.conf rejects connection' || ok=1
    grep -q '^servidor disponivel depois: sim$' "$f" || ok=1
    return $ok
}
if [ "${1:-}" = avaliar-b6 ]; then
    avalia_b6 "${2:?arquivo}" && { echo "B6 APROVADO"; exit 0; }
    echo "B6 REPROVADO"; exit 1
fi

: "${HOMOLOG_ID:?}" "${HOMOLOG_SEGREDOS_HOST:?}" "${HOMOLOG_REDE_INTERNA:?}" "${AQUI:?}"
[[ "$HOMOLOG_ID" =~ ^ensaio- ]] || { echo "ERRO: so ambiente ensaio-*" >&2; exit 1; }
MODO="${1:?preparar|testar}"
REPO="$(cd "$AQUI/../.." && pwd)"
H="$AQUI/homologacao.sh"
T="$HOMOLOG_SEGREDOS_HOST/teste016"
EV="$AQUI/saidas/$HOMOLOG_ID/016-$MODO-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$EV" "$T"
chmod 700 "$T" 2>/dev/null || true
RESUMO="$EV/resumo.txt"
echo "cenario|resultado|detalhe" > "$RESUMO"
registra() { echo "$1|$2|$3" >> "$RESUMO"; echo "[$1] $2 - $3"; }
export HOMOLOG_CA="$HOMOLOG_SEGREDOS_HOST/tls/ca.crt" HOMOLOG_SEGREDOS_VISTOS="$T/segredos_vistos.txt" HOMOLOG_ESTADO="$T/estado.json"
touch "$HOMOLOG_SEGREDOS_VISTOS"
cliente() { python "$AQUI/testes/cliente.py" "$1" "$T/fixture.json" "${@:2}"; }
ferr() { MSYS_NO_PATHCONV=1 compose run --rm -T -e PGPASSWORD ferramentas "$@" 2>&1 | tr -d '\r' | grep -vE '^ Container |^\s*$'; }
op_ferr() { bash "$H" operacao "$HOMOLOG_ID" ferramenta "$@" 2>&1 | tr -d '\r' | grep -vE '^ Container |^\s*$'; }
op_cli() { HOMOLOG_OPERADOR="$1" bash "$H" operacao "$HOMOLOG_ID" cli "${@:2}" 2>&1 | tr -d '\r' | grep -vE '^ Container |^\s*$'; }
campo() { sed -nE "s/.*(^| )$1=([^ ]+).*/\2/p" | head -1; }   # campo=valor de uma linha de saida da CLI/ferramenta
fx() { python -c "import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2], {}, {'d': d}))" "$T/fixture.json" "$1" | tr -d '\r'; }
sq() { admin_sql nekkora -c "$1" | tr -d '\r'; }
# credencial nova (15 min) antes de cada grupo de atos do operador
cred() { op_ferr credencial "$1" | grep -q '^credencial=' || { echo "credencial de $1 nao emitida" >&2; return 1; }; }

# ======================================================================================================== preparar
preparar() {
    [ -f "$T/fixture.json" ] && { echo "ERRO: ja preparado ($T/fixture.json)" >&2; exit 1; }
    python - "$T" "$HOMOLOG_ID" <<'PY'
import json, secrets, sys, uuid
t, amb = sys.argv[1], sys.argv[2]
u = lambda: str(uuid.uuid4())
ids = {k: u() for k in ('org', 'adm1', 'adm2', 'membro', 'm_adm1', 'm_adm2', 'm_membro')}
senha = lambda: 'sintetica-' + secrets.token_urlsafe(12)
f = {'ambiente': amb, 'ids': ids, 'org': ids['org'],
     'titular': {'id': ids['adm1'], 'email': f'titular-{ids["adm1"][:8]}@homologacao-016.test', 'senha': senha()},
     'outro': {'id': ids['adm2'], 'email': f'outro-{ids["adm2"][:8]}@homologacao-016.test', 'senha': senha()},
     'membro': {'id': ids['membro'], 'email': f'membro-{ids["membro"][:8]}@homologacao-016.test'}}
json.dump(f, open(f'{t}/fixture.json', 'w'))
PY
    for k in titular outro; do fx "d['$k']['senha']" >> "$HOMOLOG_SEGREDOS_VISTOS"; done
    local jar test phc1 phc2 ok=0
    jar="$(cygpath -m "$REPO/api/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar" 2>/dev/null || echo "$REPO/api/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar")"
    test="$(cygpath -m "$REPO/api/nekkora-platform/target/test-classes" 2>/dev/null || echo "$REPO/api/nekkora-platform/target/test-classes")"
    phc() { fx "d['$1']['senha']" | java -Dloader.path="$test" -Dloader.main=com.nekkora.platform.auth.crypto.PhcTool -cp "$jar" \
        org.springframework.boot.loader.launch.PropertiesLauncher 65536 3 | tr -d '\r'; }
    phc1="$(phc titular)"; phc2="$(phc outro)"
    admin_sql nekkora -v ambiente="$HOMOLOG_ID" -v org="$(fx "d['ids']['org']")" -v adm1="$(fx "d['ids']['adm1']")" -v adm2="$(fx "d['ids']['adm2']")" \
        -v membro="$(fx "d['ids']['membro']")" -v m_adm1="$(fx "d['ids']['m_adm1']")" -v m_adm2="$(fx "d['ids']['m_adm2']")" \
        -v m_membro="$(fx "d['ids']['m_membro']")" -v email_adm1="$(fx "d['titular']['email']")" -v email_adm2="$(fx "d['outro']['email']")" \
        -v email_membro="$(fx "d['membro']['email']")" -v phc_adm1="$phc1" -v phc_adm2="$phc2" \
        < "$AQUI/sql/fixture_016.sql" > "$EV/fixture_016.txt" 2>&1 || ok=1
    admin_sql nekkora < "$AQUI/sql/operadores_016.sql" > "$EV/operadores_016.txt" 2>&1 || ok=1
    {
        op_ferr credencial op_hml_cad | sed 's/validade_ate=.*/validade_ate=(omitida)/'
        op_ferr operador op_hml_cad op_hml_ver VERIFICAR CANCELAR CONSULTAR
        op_ferr operador op_hml_cad op_hml_apr APROVAR
        op_ferr operador op_hml_cad op_hml_exe EXECUTAR
    } > "$EV/operadores_cadastro.txt" 2>&1
    grep -c 'resultado=OK' "$EV/operadores_cadastro.txt" | grep -qx 5 || ok=1
    local c1 c2
    c1="$(op_ferr contato op_hml_cad "$(fx "d['org']")" "$(fx "d['ids']['adm1']")" "$(fx "d['titular']['email']")" | campo contato)"
    c2="$(op_ferr contato op_hml_cad "$(fx "d['org']")" "$(fx "d['ids']['membro']")" "$(fx "d['membro']['email']")" | campo contato)"
    [[ "$c1" =~ ^[0-9a-f-]{36}$ && "$c2" =~ ^[0-9a-f-]{36}$ ]] || ok=1
    python -c "import json,sys; f=json.load(open(sys.argv[1])); f['contatos']=[sys.argv[2], sys.argv[3]]; json.dump(f, open(sys.argv[1],'w'))" "$T/fixture.json" "$c1" "$c2"
    echo "contatos=$c1,$c2" >> "$EV/operadores_cadastro.txt"
    cliente h016_mfa > "$EV/mfa_admins.txt" 2>&1 || ok=1
    [ $ok = 0 ] && registra PREP OK "organizacao sintetica, operadores (bootstrap + 3 cadastrados e habilitados), 2 contatos, MFA dos 2 ORG_ADMIN pelo gateway" \
               || registra PREP FALHA "ver $EV"
    return $ok
}

# ======================================================================================================== testar
b5() {
    # catalogo + o bloco de invariantes da 016 (sec. 19) rodado de novo no ambiente, a partir da COPIA imutavel da 016
    # usada na implantacao; politicas ops_function_context conferidas a parte
    local f="$EV/b5_catalogo.txt" ok=0 n_fn n_tb n_pol mig
    n_fn="$(sq "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND p.proname LIKE 'ops\_%'")"
    n_tb="$(sq "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'ops' AND c.relkind = 'r'")"
    n_pol="$(sq "SELECT count(*) FROM pg_policies WHERE schemaname = 'public' AND policyname = 'ops_function_context'")"
    echo "funcoes_publicas_ops=$n_fn tabelas_ops=$n_tb politicas_ops_function_context=$n_pol" > "$f"
    [ "$n_fn" = 37 ] && [ "$n_tb" = 21 ] && [ "$n_pol" = 9 ] || ok=1
    mig="$(ls "$AQUI/saidas/$HOMOLOG_ID/migracoes/"016_*.sql 2>/dev/null | head -1)"
    if [ -n "$mig" ]; then
        awk '/^-- === 19\. /{f=1} f && /^COMMIT;/{exit} f' "$mig" > "$T/invariantes_016.sql"
        if [ "$(grep -c '^DO \$\$' "$T/invariantes_016.sql")" = 1 ] \
           && { echo "BEGIN;"; cat "$T/invariantes_016.sql"; echo "ROLLBACK;"; } | admin_sql nekkora >> "$f" 2>&1; then
            echo "invariantes_016=OK" >> "$f"
        else
            echo "invariantes_016=FALHA" >> "$f"; ok=1
        fi
    else
        echo "invariantes_016=SEM_COPIA_DA_016" >> "$f"; ok=1
    fi
    [ $ok = 0 ] && registra B5 OK "37 funcoes ops_*, 21 tabelas em ops, 9 politicas ops_function_context e o bloco de invariantes da 016 (sec. 19) no ambiente" \
               || registra B5 FALHA "ver b5_catalogo.txt"
}

b6() {
    # cada sonda precisa da CAUSA esperada (a ferramenta devolve codigo 0 so com conforme=sim); disponibilidade
    # comprovada ANTES e DEPOIS pela sonda valida, para que uma queda nao passe por bloqueio. A CA estranha so conta com
    # as DUAS evidencias: alerta unknown_ca no cliente E "certificate verify failed" no log do servidor, no intervalo da
    # sonda (um alerta TLS generico, sozinho, nao prova que a causa foi o certificado)
    local f="$EV/b6_autenticacao.txt" ok=0 r desde pg
    pg="$(compose ps -q postgres | tr -d '\r')"
    {
        op_ferr sonda valida op_hml_ver
        op_ferr sonda senha op_hml_ver
        desde="$(date -u +%Y-%m-%dT%H:%M:%SZ)"; sleep 1
        op_ferr sonda ca-estranha op_hml_ver
        sleep 1
        echo "log do servidor no intervalo da sonda ca-estranha:"
        docker logs --since "$desde" "$pg" 2>&1 | grep -iE 'could not accept SSL connection|certificate verify failed' | sed -E 's/^[^ ]+ [^ ]+ [^ ]+ //' | cut -c1-160
        op_ferr sonda cn-diferente op_hml_ver op_hml_apr
        op_ferr sonda valida op_hml_ver
    } > "$f" 2>&1
    # regra por ENDERECO: o mesmo papel a partir do job de ferramentas (.31) NAO casa nenhuma regra de aceite e cai na
    # regra EXPLICITA de recusa do fim do pg_hba ("host all all 0.0.0.0/0 reject"); a mensagem esperada do servidor e a
    # dessa regra ("pg_hba.conf rejects connection"), nunca falha de conexao; o servidor responde logo depois (SELECT 1)
    r="$(PGPASSWORD=qualquer ferr psql -X -At "host=postgres port=5432 dbname=nekkora user=op_hml_ver sslmode=verify-full sslrootcert=/tls/ca.crt connect_timeout=5" -c "SELECT 1" 2>&1)"
    echo "$r" | sed 's/^/de .31 como op_hml_ver: /' | cut -c1-240 >> "$f"
    [ "$(sq "SELECT 1")" = 1 ] && echo "servidor disponivel depois: sim" >> "$f" || echo "servidor disponivel depois: nao" >> "$f"
    avalia_b6 "$f" || ok=1
    [ $ok = 0 ] && registra B6 OK "certificado da CA operacional aceito; senha e CN diferente recusados na autenticacao (28xxx); CA estranha recusada no TLS (unknown_ca no cliente e certificate verify failed no servidor); op_* do .31 na regra explicita de recusa; disponibilidade antes e depois" \
               || registra B6 FALHA "ver b6_autenticacao.txt"
}

b7() {
    local f="$EV/b7_servicos.txt" ok=0
    op_ferr entrada op_hml_ver 3 > "$f" 2>&1
    grep -q 'resultado=OK' "$f" || ok=1
    sq "SELECT 'intake_open=' || has_function_privilege('nekkora_ops_intake', 'public.ops_mfa_open(uuid,uuid,uuid,uuid,uuid)', 'EXECUTE')
        || ' ca_open=' || has_function_privilege('nekkora_ops_ca', 'public.ops_mfa_open(uuid,uuid,uuid,uuid,uuid)', 'EXECUTE')
        || ' intake_select=' || has_table_privilege('nekkora_ops_intake', 'ops.ops_intake_entries', 'SELECT')" >> "$f"
    grep -q 'intake_open=false ca_open=false intake_select=false' "$f" || ok=1
    [ $ok = 0 ] && registra B7 OK "entrada registrada por nekkora_ops_intake (SCRAM, .33); servicos sem funcoes de operador nem leitura de tabelas" \
               || registra B7 FALHA "ver b7_servicos.txt"
}

espera_avisos() {   # $1 solicitacao, $2 quantidade de tentativas ACEITAS esperada
    local i n
    for i in $(seq 1 30); do
        n="$(sq "SELECT count(*) FROM ops.ops_mfa_notices WHERE request_id = '$1' AND state = 4")"
        [ "$n" -ge "$2" ] && return 0
        sleep 3
    done
    return 1
}

b8a() {
    local f="$EV/b8a_fluxo.txt" ok=0 c1 c2 org adm1 adm2 rid s r aviso
    org="$(fx "d['org']")"; adm1="$(fx "d['ids']['adm1']")"; adm2="$(fx "d['ids']['adm2']")"
    c1="$(fx "d['contatos'][0]")"; c2="$(fx "d['contatos'][1]")"
    {
        echo "== fixture declarada de contatos (unica fixture de B8a)"
        bash "$H" fixture "$HOMOLOG_ID" contatos "$c1,$c2" 2>&1 | grep -E 'fixture_contatos:|classificacao=|ERRO'
        cred op_hml_ver
        r="$(op_cli op_hml_ver retorno "$c1")"; echo "$r"; rid="$(echo "$r" | campo retorno)"
        r="$(op_cli op_hml_ver abrir "$org" "$adm1" "$c1" retorno "$rid")"; echo "$r"; s="$(echo "$r" | campo solicitacao)"
        echo "$s" > "$T/solicitacao"
        op_cli op_hml_ver verificar "$s" true "$c2" true TODOS_SEM_FATOR_E_CODIGOS_UTILIZAVEIS
        cred op_hml_apr
        op_cli op_hml_apr pre-aprovar "$s"
        echo "== avisos pelo despachante real com o transporte de homologacao"
        espera_avisos "$s" 2 && echo "tentativas aceitas: 2" || echo "tentativas aceitas: TEMPO ESGOTADO"
        sq "SELECT 'categorias=' || string_agg(DISTINCT coalesce(category, '-'), ',') FROM ops.ops_mfa_notices WHERE request_id = '$s' AND state = 4"
        aviso="$(sq "SELECT notice_id FROM ops.ops_mfa_notices WHERE request_id = '$s' AND kind = 1 AND state = 4 ORDER BY seq LIMIT 1")"
        # token lido da caixa pela ferramenta restrita, direto para o arquivo fora do Git (nunca para a evidencia)
        bash "$H" caixa "$HOMOLOG_ID" ler "avisos/$aviso.json" \
            | python -c "import json,sys; print(json.load(sys.stdin)['token_confirmar'])" > "$T/token_confirmar"
        chmod 600 "$T/token_confirmar" 2>/dev/null || true
        cat "$T/token_confirmar" >> "$HOMOLOG_SEGREDOS_VISTOS"
        cliente h016_confirmar "$T/token_confirmar" 204
        op_cli op_hml_apr aprovar "$s"
        sq "SELECT 'estado=' || state FROM ops.ops_mfa_recovery_requests WHERE request_id = '$s'"
    } > "$f" 2>&1
    grep -q 'fixture_contatos: .*verificacoes=OK' "$f" && grep -q 'classificacao=APLICADA ' "$f" || ok=1
    grep -q 'estado=VERIFICADA' "$f" || ok=1
    grep -q 'estado=PRE_APROVADA motivo=-' "$f" || ok=1
    grep -q 'tentativas aceitas: 2' "$f" || ok=1
    grep -q '^categorias=ACEITE_SIMULADO$' "$f" || ok=1
    grep -q '^OK .*H016.confirmar' "$f" || ok=1
    grep -q 'motivo=ESPERA_NAO_CUMPRIDA' "$f" || ok=1
    grep -q '^estado=3$' "$f" || ok=1
    [ $ok = 0 ] && registra B8a OK "abertura por retorno, verificacao, pre-aprovacao, avisos ACEITE_SIMULADO, CONFIRMAR pelo gateway, aprovacao imediata recusada (ESPERA_NAO_CUMPRIDA)" \
               || registra B8a FALHA "ver b8a_fluxo.txt"
}

b10() {
    # desfechos simulados pelo caminho normal, um por reenvio ao outro admin; o ultimo volta a ACEITO para que a vigente
    # seja uma tentativa aceita (condicao 2 de B8b). A VARREDURA de tentativas paradas nao tem gatilho no ambiente
    # (nao ha agendamento nem rota; a identidade da Platform so conecta do proprio container): fica PENDENTE.
    local f="$EV/b10_desfechos.txt" ok=0 s adm2 d esperado st i
    s="$(cat "$T/solicitacao")"; adm2="$(fx "d['ids']['adm2']")"
    : > "$f"
    for par in RECUSADO:5:RECUSA_SIMULADA ENFILEIRADO:3:FILA_SIMULADA INCERTO:6:INCERTO_SIMULADO EXCECAO:6:INCERTO_SIMULADO ACEITO:4:ACEITE_SIMULADO; do
        d="${par%%:*}"; esperado="${par#*:}"
        {
            [ "$d" = ACEITO ] || bash "$H" caixa "$HOMOLOG_ID" controle proximo "$d"
            cred op_hml_ver
            op_cli op_hml_ver reenviar-aviso "$s" "$adm2"
            for i in $(seq 1 30); do
                st="$(sq "SELECT state || ':' || coalesce(category, '-') FROM ops.ops_mfa_notices WHERE request_id = '$s' AND recipient_user_id = '$adm2' ORDER BY seq DESC LIMIT 1")"
                [ "${st%%:*}" != 0 ] && [ "${st%%:*}" != 1 ] && [ "${st%%:*}" != 2 ] && break
                sleep 3
            done
            echo "desfecho=$d tentativa=$st esperado=$esperado"
        } >> "$f" 2>&1
        grep -q "desfecho=$d tentativa=$esperado esperado=$esperado" "$f" || ok=1
    done
    [ $ok = 0 ] && registra B10 PARCIAL "RECUSADO, ENFILEIRADO, INCERTO, EXCECAO e ACEITO simulados pelo caminho normal com as categorias esperadas (ACEITO tambem em B8a); VARREDURA pendente (sem gatilho no ambiente)" \
               || registra B10 FALHA "ver b10_desfechos.txt"
}

b8b() {
    local f="$EV/b8b_conclusao.txt" ok=0 s
    s="$(cat "$T/solicitacao")"
    {
        echo "== fixture declarada de solicitacao (B8b; esta solicitacao nunca entra em B9)"
        bash "$H" fixture "$HOMOLOG_ID" solicitacao "$s" 25 2>&1 | grep -E 'fixture_solicitacao:|classificacao=|ERRO'
        cred op_hml_apr
        op_cli op_hml_apr aprovar "$s"
        cred op_hml_exe
        op_cli op_hml_exe executar "$s"
        cliente h016_novo_mfa
    } > "$f" 2>&1
    grep -q 'fixture_solicitacao: .*verificacoes=OK' "$f" && grep -q 'classificacao=APLICADA ' "$f" || ok=1
    grep -q 'estado=APROVADA' "$f" || ok=1
    grep -q 'estado=EXECUTADA' "$f" || ok=1
    grep -q '^OK .*H016.novo_mfa cadastro' "$f" || ok=1
    [ $ok = 0 ] && registra B8b OK "fixture declarada, aprovacao, execucao e novo MFA do titular pelo gateway" || registra B8b FALHA "ver b8b_conclusao.txt"
}

b11() {
    local f="$EV/b11_backup.txt" ok=0 pb
    pb="$(cat "$HOMOLOG_SEGREDOS_HOST/senhas/backup")"
    MSYS_NO_PATHCONV=1 compose run --rm -T -e PGPASSWORD="$pb" backup pg_dump "host=postgres port=5432 dbname=nekkora user=backup_nekkora sslmode=verify-full sslrootcert=/tls/ca.crt" -Fc \
        > "$T/backup016.dump" 2> "$f" || ok=1
    MSYS_NO_PATHCONV=1 compose run --rm -T backup pg_restore -l < "$T/backup016.dump" 2>>"$f" | grep -cE ' TABLE ops ' | sed 's/^/tabelas_ops_no_dump=/' >> "$f"
    grep -q '^tabelas_ops_no_dump=21$' "$f" || ok=1
    [ $ok = 0 ] && registra B11 PARCIAL "dump por backup_nekkora inclui as 21 tabelas de ops; restauracao num segundo cluster PENDENTE (P4)" \
               || registra B11 FALHA "ver b11_backup.txt"
}

fotografia() {
    # inventario por ID de containers, redes, volumes e imagens (P4-R1): bruto JSONL REDUZIDO, com o codigo de saida de
    # cada listagem e inspecao; rotulo ausente vale vazio; falha de leitura nunca vira "identico" (testes/inventario_b12.py)
    python "$AQUI/testes/inventario_b12.py" coletar "$HOMOLOG_ID" > "$1"
}

testar() {
    [ -f "$T/fixture.json" ] || { echo "ERRO: rode antes homologacao.sh preparar-016 $HOMOLOG_ID" >&2; exit 1; }
    fotografia "$EV/inventario_b12_antes.jsonl"
    b5; b6; b7
    if [ "${HOMOLOG_TRANSPORTE:-}" = homologacao ]; then
        b8a; b10
        if [ "${H016_B8B:-}" = 1 ]; then b8b; else registra B8b NAO_EXECUTADO "so com H016_B8B=1 (fixture declarada de solicitacao)"; fi
        bash "$H" caixa "$HOMOLOG_ID" exportar-indice > "$EV/caixa_exportacao.txt" 2>&1
    else
        registra B8a NAO_EXECUTADO "ambiente sem o transporte de homologacao"
    fi
    b11
    fotografia "$EV/inventario_b12_depois.jsonl"
    python "$AQUI/testes/inventario_b12.py" comparar "$HOMOLOG_ID" "$EV/inventario_b12_antes.jsonl" \
        "$EV/inventario_b12_depois.jsonl" > "$EV/b12_inventario.txt" 2>&1
    case $? in
        0) registra B12 OK "inventario COMPLETO por ID fora do ambiente (containers, redes, volumes, imagens) identico antes e depois" ;;
        # codigo 1 so e FALHA com o veredito escrito pelo avaliador (excecao/erro do interpretador tambem sai com 1)
        1) if grep -q '^B12 FALHA:' "$EV/b12_inventario.txt"; then
               registra B12 FALHA "diferenca em recurso protegido; ver b12_inventario.txt"
           else
               registra B12 INCONCLUSIVO "avaliador do inventario terminou com erro (codigo 1 sem veredito); ver b12_inventario.txt"
           fi ;;
        *) registra B12 INCONCLUSIVO "leitura incompleta ou falha de comando no inventario; ver b12_inventario.txt" ;;
    esac
}

case "$MODO" in
    preparar) preparar ;;
    testar) testar ;;
    *) echo "modo invalido" >&2; exit 64 ;;
esac

# nenhum valor secreto visto (senhas sinteticas, tokens da caixa, CSRF, segredos TOTP) nas evidencias desta execucao
if [ -s "$HOMOLOG_SEGREDOS_VISTOS" ]; then
    if grep -rlF -f <(grep -E '.{8,}' "$HOMOLOG_SEGREDOS_VISTOS") "$EV" "$AQUI/saidas/$HOMOLOG_ID/caixa_indice.jsonl" 2>/dev/null; then
        registra SEGREDOS FALHA "valor secreto encontrado nas evidencias (arquivos acima)"
    else
        registra SEGREDOS OK "nenhum dos $(wc -l < "$HOMOLOG_SEGREDOS_VISTOS") valores secretos vistos aparece nas evidencias"
    fi
fi
echo "resumo: $RESUMO"
! grep -qE '\|(FALHA|INCONCLUSIVO)\|' "$RESUMO"
