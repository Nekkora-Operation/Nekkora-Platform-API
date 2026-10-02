# H03: PERFIS DE EXPECTATIVA por modelo de migrations (missao H03 pos-016; achado F-R2b1c-1). Arquivo "sourced" por
# testes/executar.sh (h03) e pelos testes da ferramenta; so define funcoes e dados, sem efeito ao carregar.
#
# O H03 deixa de supor que todo ambiente termina na 015: o MODELO e derivado da copia de migrations do ambiente
# (saidas/<id>/migracoes), conferido contra HOMOLOG_ATE, e cada modelo tem UM perfil declarado aqui. Modelo novo (017...)
# = perfil novo neste arquivo; modelo sem perfil, copia com lacuna ou divergencia com HOMOLOG_ATE = FALHA FECHADA.
#
# O que cada perfil declara:
#   H03_LOGIN_COM_SENHA   papeis com LOGIN e senha esperados (lista exata, ordenada, separada por virgula);
#   H03_INVARIANTES       blocos de invariantes das migrations, reexecutados (BEGIN ... ROLLBACK) sobre o estado atual,
#                         "arquivo|marcador de inicio|transformacao" (transformacao "-" = bloco literal);
#   H03_BLOCOS            numero de blocos DO esperado no SQL montado.
# Transformacao "pol014_016" (so no perfil 016): a sec. 8 da 014 exige EXATAMENTE UMA politica nas 4 tabelas da 014; a 016
# cria nelas, de proposito, a segunda (ops_function_context). A regra NAO e relaxada: a contagem "<> 1" vira o CONJUNTO
# EXATO de politicas do modelo 016 (ops_function_context e tenant_request_context). O trecho da 014 tem de aparecer
# exatamente uma vez; senao o perfil falha (nunca roda a regra antiga nem pula o bloco).
#
# Referencia do catalogo: instalacao DESCARTAVEL pelo caminho SUPORTADO do produto (db/bootstrap/aplicar_como_migrador.sh:
# migrador_nekkora dono do banco, janelas SUPERUSER/BYPASSRLS so nas versoes declaradas, retirada conferida, sec. 19 da
# 016 depois da retirada, marcador "nekkora-inicializacao-completa versao=<ultima>" so no fim do sucesso) - o mesmo modelo
# de docker-compose.modelo.yml e das bases Java. Nenhum superusuario instala migrations na referencia.

H03_MODELOS_SUPORTADOS="015 016"
H03_ANCORA_POL014="OR (SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) <> 1);"
H03_TROCA_POL014="OR (SELECT string_agg(p.policyname, ',' ORDER BY p.policyname) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) IS DISTINCT FROM 'ops_function_context,tenant_request_context');"

# h03_modelo <dir de migrations> <HOMOLOG_ATE> -> imprime o modelo (ex.: 016); codigo 1 com o motivo em stderr
h03_modelo() {
    local dir="$1" ate="$2" vs n esperado i
    [ -d "$dir" ] || { echo "H03 modelo: copia de migrations ausente ($dir)" >&2; return 1; }
    vs="$(cd "$dir" && ls | grep -E '^[0-9]{3}_.+\.sql$' | cut -c1-3 | sort)"
    n="$(echo "$vs" | grep -c .)"
    [ "$n" -gt 0 ] || { echo "H03 modelo: copia sem migrations" >&2; return 1; }
    [ "$(echo "$vs" | uniq -d | grep -c .)" = 0 ] || { echo "H03 modelo: versao repetida na copia" >&2; return 1; }
    [ "$(ls "$dir" | grep -cvE '^[0-9]{3}_.+\.sql$')" = 0 ] || { echo "H03 modelo: arquivo estranho na copia" >&2; return 1; }
    esperado="$(for i in $(seq 1 "$n"); do printf '%03d\n' "$i"; done)"
    [ "$vs" = "$esperado" ] || { echo "H03 modelo: copia com lacuna ou fora de 001..$(printf '%03d' "$n")" >&2; return 1; }
    local ultima; ultima="$(echo "$vs" | tail -1)"
    case " $H03_MODELOS_SUPORTADOS " in *" $ultima "*) ;; *) echo "H03 modelo: $ultima sem perfil declarado (suportados: $H03_MODELOS_SUPORTADOS)" >&2; return 1;; esac
    [ "$ultima" = "$ate" ] || { echo "H03 modelo: copia termina em $ultima, mas HOMOLOG_ATE=${ate:-vazio}" >&2; return 1; }
    echo "$ultima"
}

# h03_perfil <modelo> -> define H03_PERFIL, H03_LOGIN_COM_SENHA, H03_INVARIANTES, H03_BLOCOS; codigo 1 se nao houver perfil
h03_perfil() {
    H03_PERFIL="" H03_LOGIN_COM_SENHA="" H03_INVARIANTES="" H03_BLOCOS=""
    case "$1" in
        015)
            H03_LOGIN_COM_SENHA="admin_cluster,backup_nekkora,migrador_nekkora,nekkora_platform,nekkora_portal"
            H03_INVARIANTES="012_security_rls.sql|^-- === 15\. |-
013_portal_externo.sql|^-- === 8\. |-
014_mfa_sessoes.sql|^-- === 8\. |-
015_restauracao_logica.sql|^-- === Invariantes ===|-"
            H03_BLOCOS=4 ;;
        016)
            # desenho 016: nekkora_ops_ca e nekkora_ops_intake fazem login com senha (SCRAM; os demais papeis da 016 nao)
            H03_LOGIN_COM_SENHA="admin_cluster,backup_nekkora,migrador_nekkora,nekkora_ops_ca,nekkora_ops_intake,nekkora_platform,nekkora_portal"
            H03_INVARIANTES="012_security_rls.sql|^-- === 15\. |-
013_portal_externo.sql|^-- === 8\. |-
014_mfa_sessoes.sql|^-- === 8\. |pol014_016
015_restauracao_logica.sql|^-- === Invariantes ===|-
016_recuperacao_emergencial_mfa.sql|^-- === 19\. |-"
            H03_BLOCOS=5 ;;
        *) echo "H03 perfil: modelo '$1' sem perfil" >&2; return 1 ;;
    esac
    H03_PERFIL="$1"
}

# h03_invariantes_sql <dir de migrations> -> SQL "BEGIN; <blocos do perfil>; ROLLBACK;" em stdout; codigo 1 se um bloco
# nao for encontrado, uma transformacao nao casar exatamente uma vez, ou o numero de blocos DO divergir do perfil
h03_invariantes_sql() {
    local mig="$1" linha arq ini tr bloco total=0
    [ -n "$H03_PERFIL" ] || { echo "H03 invariantes: perfil nao carregado" >&2; return 1; }
    echo "BEGIN;"
    while IFS='|' read -r arq ini tr; do
        [ -f "$mig/$arq" ] || { echo "H03 invariantes: $arq ausente da copia" >&2; return 1; }
        bloco="$(H03_RE="$ini" awk 'BEGIN { re = ENVIRON["H03_RE"] } $0 ~ re {f=1} f && /^COMMIT;/{exit} f' "$mig/$arq")"   # ENVIRON: "\." literal
        [ "$(printf '%s\n' "$bloco" | grep -c '^DO \$\$')" = 1 ] || { echo "H03 invariantes: bloco de $arq nao encontrado" >&2; return 1; }
        case "$tr" in
            -) ;;
            pol014_016)
                [ "$(printf '%s\n' "$bloco" | grep -cF -- "$H03_ANCORA_POL014")" = 1 ] \
                    || { echo "H03 invariantes: regra de politica da 014 nao encontrada exatamente uma vez (perfil $H03_PERFIL)" >&2; return 1; }
                bloco="${bloco/"$H03_ANCORA_POL014"/"$H03_TROCA_POL014"}" ;;
            *) echo "H03 invariantes: transformacao desconhecida '$tr'" >&2; return 1 ;;
        esac
        printf '%s\n' "$bloco"; total=$((total + 1))
    done <<< "$H03_INVARIANTES"
    [ "$total" = "$H03_BLOCOS" ] || { echo "H03 invariantes: $total blocos montados, perfil exige $H03_BLOCOS" >&2; return 1; }
    echo "ROLLBACK;"
}

# h03_referencia <id> <dir de migrations> <modelo> <script do modelo> <saida do catalogo> <sql da listagem> <dir de evidencia>
# Instala a referencia pelo modelo suportado num container DESCARTAVEL (rotulos do ambiente + papel referencia-catalogo),
# exige o marcador da ultima versao do modelo, lista o catalogo e remove o container com o volume. Codigo 1 = referencia
# invalida (sem marcador, container parado, listagem com erro); a saida registra o motivo e o fim do log do container.
h03_referencia() {
    local id="$1" mig="$2" modelo="$3" script="$4" saida="$5" lst="$6" ev="$7" ref pr cid i marca ok=0
    ref="nekkora-homologacao-$id-referencia"; pr="$(head -c 18 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
    [ -f "$script" ] || { echo "H03 referencia: script do modelo ausente ($script)" > "$ev/h03_referencia.txt"; return 1; }
    cid="$(MSYS_NO_PATHCONV=1 docker run -d --init --name "$ref" --label "nekkora.homologacao=$id" --label nekkora.homologacao.papel=referencia-catalogo \
        -e POSTGRES_USER=nekkora -e POSTGRES_DB=nekkora -e POSTGRES_PASSWORD="$pr" \
        -v "$(cygpath -w "$mig" 2>/dev/null || echo "$mig"):/nekkora/migracoes:ro" \
        -v "$(cygpath -w "$script" 2>/dev/null || echo "$script"):/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh:ro" postgres:16-alpine)" \
        || { echo "H03 referencia: container nao criado" > "$ev/h03_referencia.txt"; return 1; }
    for i in $(seq 1 150); do
        marca="$(MSYS_NO_PATHCONV=1 docker exec "$cid" psql -X -At -U nekkora -d nekkora -c "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = 'nekkora'" 2>/dev/null | tr -d '\r')"
        [ "$marca" = "nekkora-inicializacao-completa versao=$modelo" ] && break
        [ "$(docker inspect "$cid" --format '{{.State.Running}}' 2>/dev/null)" = true ] || break
        sleep 2
    done
    { echo "referencia $ref ($cid) pelo modelo suportado ($(basename "$script")), modelo $modelo"
      echo "marcador: ${marca:-AUSENTE}"; docker logs "$cid" 2>&1 | grep 'aplicar_como_migrador:' | tail -25; } > "$ev/h03_referencia.txt"
    if [ "$marca" = "nekkora-inicializacao-completa versao=$modelo" ]; then
        MSYS_NO_PATHCONV=1 docker exec -i -e PGPASSWORD="$pr" "$cid" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora < "$lst" > "$saida" 2>&1 || ok=1
    else
        echo "H03 referencia: sem o marcador da versao $modelo (instalacao incompleta ou recusada)" >> "$ev/h03_referencia.txt"; ok=1
    fi
    docker rm -f -v "$cid" > /dev/null 2>&1
    return $ok
}
