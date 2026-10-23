#!/usr/bin/env bash
# Suite de banco da 021_correio_fila.sql (candidata). Dois clusters PostgreSQL 16 DESCARTAVEIS e isolados (containers
# proprios, --network none, sem porta publicada, sem volume nomeado); nunca toca a stack principal, o hml03 nem o ambiente
# preservado.
#
# Modelo de aplicacao: o da 016 revisao 5 (db/bootstrap/aplicar_como_migrador.sh), SUPERUSER so em 011, 012, 013, 016,
# 017, 018, 019 e 020, BYPASSRLS so na 014, retirada conferida em 0|0|0. A 021 NAO precisa de janela: a tabela e a
# trigger pertencem ao migrador, dono do banco.
#
#   A (limpa):    001-021 pelo entrypoint. Invariantes 012 (§15), 013 (§8), 014 (§8, adaptacao da 017), 016 (§19),
#                 017 (§12) e 019 (§4) com as adaptacoes da 020, 020 (§4) e 021 (§5) DEPOIS da retirada. Marcador
#                 versao=021.
#   B (populada): 001-012 + massa (atualizacao-populada/p01), 013-015 + massa da era MFA (mfa-emergencia-016/p02),
#                 016-020; instantaneo de dados (public e ops) e catalogo de public.
#     B2 - 021 SEM nenhuma janela: aplicada; dados identicos; diff de catalogo EXATAMENTE o previsto
#          (esperado/B22_diff_esperado.txt: tabela, colunas, CHECKs, indices, triggers, funcao de protecao e privilegios
#          da Platform); catalogo = instalacao limpa A; invariantes; marcador versao=021.
#     B3 - reaplicar a 021 e recusado pela pre-condicao; nada muda.
# O comportamento (fila cifrada, tentativas, posse vencida, prazo, concorrencia, protecoes) e coberto por MailOutboxIT
# (nekkora-platform), com o papel real nekkora_platform.
#
# Uso: db/tests/correio-021/run.sh  |  TESTE_PROJETO=nekkora-m021b db/tests/correio-021/run.sh
# Sucesso: os containers sao removidos. Falha: ficam no ar para inspecao e o comando de remocao e impresso.
set -euo pipefail

AQUI="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$AQUI/../../.." && pwd)"
LISTAGEM="$REPO/db/tests/portal-013/sql/c_listagem_catalogo.sql"
INSTANTANEO="$REPO/db/tests/relogio-018/sql/c_instantaneo_gera.sql"
MODELO="$REPO/db/bootstrap/aplicar_como_migrador.sh"
ELEVAR="${TESTE_ELEVAR:-011 012 013 016 017 018 019 020}"
PROJETO="${TESTE_PROJETO:-nekkora-m021}"
LIMPA="${PROJETO}-limpa"
POP="${PROJETO}-populada"
BKP="${PROJETO}-backup"
SAIDA="$AQUI/saidas/$(date -u +%Y-%m-%dT%H%M%SZ)_${PROJETO}"
IMAGEM=postgres:16-alpine
PY=python3; "$PY" -c 1 >/dev/null 2>&1 || PY=python

falha() {
    echo "ERRO: $*" >&2
    echo "containers mantidos para inspecao. Remocao: docker rm -f -v $LIMPA $POP $BKP" >&2
    exit 1
}
case "$PROJETO" in nekkora-platform|nekkora|*hml03*) falha "projeto '$PROJETO' reservado";; esac
for c in "$LIMPA" "$POP" "$BKP"; do
    docker container inspect "$c" >/dev/null 2>&1 && falha "container $c ja existe"
done

mkdir -p "$SAIDA/mig"
for n in $(seq -w 1 21); do
    f=("$REPO"/db/migrations/0${n}_*.sql)
    [ -f "${f[0]}" ] && [ ${#f[@]} -eq 1 ] || falha "migration 0$n ausente ou ambigua"
    cp "${f[0]}" "$SAIDA/mig/"
done
M021="$SAIDA/mig/021_correio_fila.sql"
[ -f "$M021" ] || falha "021 ausente"
cp "$MODELO" "$SAIDA/modelo.sh"; chmod 644 "$SAIDA/modelo.sh"
sha256sum "$SAIDA"/mig/0*.sql 2>/dev/null | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt" \
    || shasum -a 256 "$SAIDA"/mig/0*.sql | sed "s#$SAIDA/mig/##" > "$SAIDA/00_migracoes_sha256.txt"

psqlc() { local c="$1" db="$2"; shift 2; docker exec -i "$c" psql -X -At -v ON_ERROR_STOP=1 -U nekkora -d "$db" "$@"; }
consulta() { psqlc "$1" "$2" -c "$3" < /dev/null; }
lista() { psqlc "$1" "$2" < "$LISTAGEM" | awk '/^(rel|relacl|col|colacl|con|idx|trg|pol|fn|fnacl|type|nsp|nspacl|ext|defacl|dbacl)\|/ { if (l != "") print l; l = $0; next } { l = l " " $0 } END { if (l != "") print l }'; }
sobe() {   # $1 container, $2 quantidade esperada, $3 ultima versao aplicada no entrypoint
    docker create --name "$1" --network none -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
        -e POSTGRES_PASSWORD="descartavel-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')" -e NEKKORA_ATE="$3" \
        -e NEKKORA_ELEVAR="$ELEVAR" -e NEKKORA_MIGRACOES=/nekkora-migracoes \
        "$IMAGEM" postgres -c cluster_name="$1" > /dev/null
    docker cp "$SAIDA/mig" "$1":/nekkora-migracoes > /dev/null
    docker cp "$SAIDA/modelo.sh" "$1":/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh > /dev/null
    docker cp "$SAIDA/modelo.sh" "$1":/tmp/modelo.sh > /dev/null
    docker start "$1" > /dev/null
    for _ in $(seq 1 180); do
        [ "$(docker logs "$1" 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ] && break
        docker ps -q -f "name=^$1\$" | grep -q . || break
        sleep 1
    done
    docker logs "$1" 2>&1 | grep -E "running /docker-entrypoint-initdb.d|sourcing|aplicar_como_migrador|ERROR|FATAL|init process complete" > "$SAIDA/entrypoint_$1.txt" || true
    grep -qE "ERROR|FATAL|aplicar_como_migrador: FALHA" "$SAIDA/entrypoint_$1.txt" && falha "erro na aplicacao das migrations em $1 (ver entrypoint_$1.txt)"
    [ "$(grep -c 'aplicar_como_migrador: aplicada versao=' "$SAIDA/entrypoint_$1.txt")" = "$2" ] || falha "esperadas $2 migrations em $1"
    grep -q "aplicar_como_migrador: fim db=nekkora aplicadas=$2 estado=0|0|0" "$SAIDA/entrypoint_$1.txt" || falha "migrador nao terminou 0|0|0 em $1"
    [ "$(consulta "$1" nekkora 'SHOW cluster_name')" = "$1" ] || falha "cluster inesperado em $1"
}
modelo() {   # $1 container, $2 desde, $3 ate, $4 arquivo de saida, $5 lista de elevacao (padrao $ELEVAR)
    docker exec -e NEKKORA_DB=nekkora -e NEKKORA_DESDE="$2" -e NEKKORA_ATE="$3" -e NEKKORA_ELEVAR="${5-$ELEVAR}" \
        -e NEKKORA_MIGRACOES=/nekkora-migracoes "$1" sh /tmp/modelo.sh > "$4" 2>&1
}
estado_migrador() {
    consulta "$1" nekkora "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' || (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole
        OR roleid = 'migrador_nekkora'::regrole) || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')
        || ' dono_do_banco=' || pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()"
}
adapta() {   # $1 arquivo, $2 trecho, $3 substituto: substitui um trecho que precisa existir exatamente uma vez
    "$PY" -c 'import sys
s = open(sys.argv[1], encoding="utf-8").read()
assert s.count(sys.argv[2]) == 1, "trecho a adaptar nao encontrado exatamente uma vez: " + sys.argv[2][:60]
sys.stdout.write(s.replace(sys.argv[2], sys.argv[3]))' "$1" "$2" "$3"
}
invariantes() {   # $1 container, $2 nome; sempre DEPOIS da retirada
    awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/014_mfa_sessoes.sql" > "$SAIDA/_inv014.sql"
    awk '/^-- === 12\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/017_identidade_cadastro.sql" > "$SAIDA/_inv017.sql"
    awk '/^-- === 4\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/019_identidade_regeneracao_codigos.sql" > "$SAIDA/_inv019.sql"
    { awk '/^-- === 15\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/012_security_rls.sql"
      awk '/^-- === 8\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/013_portal_externo.sql"
      # 014: adaptacao ja usada na suite da 017 (politicas de funcao da 016/017)
      adapta "$SAIDA/_inv014.sql" \
          "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) <> 1" \
          "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname AND p.policyname NOT IN ('ops_function_context', 'identity_function_context')) <> 1"
      awk '/^-- === 19\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/016_recuperacao_emergencial_mfa.sql"
      # 017 (invariante 3) e 019 (invariante b): a lista exata de funcoes executaveis pela Platform e a da 020
      adapta "$SAIDA/_inv017.sql" "        'identity_password_change(text,text,text), '" \
          "        'identity_password_change(text,text,text), '
        'identity_password_reset(text,text), '
        'identity_recovery_codes_regenerate(text,text[]), '"
      adapta "$SAIDA/_inv019.sql" "        'identity_password_change(text,text,text), '" \
          "        'identity_password_change(text,text,text), '
        'identity_password_reset(text,text), '" > "$SAIDA/_inv019b.sql"
      # 019 (invariante c): a CHECK do evento passa a ser a da 020
      adapta "$SAIDA/_inv019b.sql" "'CHECK (((event_type >= 1) AND (event_type <= 15)))'" "'CHECK (((event_type >= 1) AND (event_type <= 17)))'"
      awk '/^-- === 4\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/020_identidade_recuperacao_senha.sql"
      awk '/^-- === 5\. /{f=1} f && /^COMMIT;/{exit} f' "$REPO/db/migrations/021_correio_fila.sql"
    } > "$SAIDA/${2}_invariantes.sql"
    [ "$(grep -c '^DO \$\$' "$SAIDA/${2}_invariantes.sql")" = 8 ] || falha "extracao dos invariantes ($2) falhou"
    estado_migrador "$1" > "$SAIDA/${2}_estado_migrador.txt"
    [ "$(cat "$SAIDA/${2}_estado_migrador.txt")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "invariantes ($2) fora do estado pos-retirada"
    { echo "BEGIN;"; cat "$SAIDA/${2}_invariantes.sql"; echo "ROLLBACK;"; } | psqlc "$1" nekkora > "$SAIDA/${2}_invariantes.txt" 2>&1 \
        || { cat "$SAIDA/${2}_invariantes.txt"; falha "invariantes ($2) falharam"; }
}
marcador() { consulta "$1" nekkora "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()"; }

# ================================================================== A. instalacao limpa 001-021
echo ">> A: instalacao limpa 001-021 ($LIMPA)"
sobe "$LIMPA" 21 021
echo "server_version=$(consulta "$LIMPA" nekkora 'SHOW server_version')" > "$SAIDA/ambiente.txt"
grep -q "aplicada versao=021 elevada=nao" "$SAIDA/entrypoint_$LIMPA.txt" || falha "021 aplicada com elevacao (nao precisa)"
grep -q "invariantes_016_pos_retirada=OK" "$SAIDA/entrypoint_$LIMPA.txt" || falha "016 §19 pos-retirada ausente"
[ "$(marcador "$LIMPA")" = "nekkora-inicializacao-completa versao=021" ] || falha "marcador da instalacao limpa"
invariantes "$LIMPA" A01
lista "$LIMPA" nekkora > "$SAIDA/A02_catalogo_021.txt"

# ================================================================== B. base populada 020 -> 021
echo ">> B: base populada 001-020 -> 021 ($POP)"
sobe "$POP" 12 012
HASH_PRE="$(head -c 32 /dev/urandom | shasum -a 256 2>/dev/null | cut -c1-64 || head -c 32 /dev/urandom | sha256sum | cut -c1-64)"
psqlc "$POP" nekkora -v n_exec=300 -v tok_pre="$HASH_PRE" < "$REPO/db/tests/atualizacao-populada/sql/p01_popula.sql" | tail -1 > "$SAIDA/B01_massa_001_012.txt"
modelo "$POP" 013 015 "$SAIDA/B02_013_015.txt" || { cat "$SAIDA/B02_013_015.txt"; falha "013-015 na populada"; }
psqlc "$POP" nekkora < "$REPO/db/tests/mfa-emergencia-016/sql/p02_popula_mfa.sql" | tail -1 > "$SAIDA/B03_massa_mfa.txt"
modelo "$POP" 016 020 "$SAIDA/B04_016_020.txt" || { cat "$SAIDA/B04_016_020.txt"; falha "016-020 na populada"; }
psqlc "$POP" nekkora < "$INSTANTANEO" > "$SAIDA/B05_instantaneo.sql"
[ "$(wc -l < "$SAIDA/B05_instantaneo.sql")" -ge 60 ] || falha "gerador de instantaneo incompleto"
psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B05_dados_020.txt"
lista "$POP" nekkora > "$SAIDA/B05_catalogo_020.txt"

echo "   B2: 021 sem janela"
modelo "$POP" 021 021 "$SAIDA/B20_021.txt" || { cat "$SAIDA/B20_021.txt"; falha "021 na populada"; }
grep -q "aplicada versao=021 elevada=nao" "$SAIDA/B20_021.txt" || falha "B2: 021 aplicada com elevacao"
[ "$(estado_migrador "$POP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "B2: migrador fora de 0|0|0"
[ "$(marcador "$POP")" = "nekkora-inicializacao-completa versao=021" ] || falha "B2: marcador"
psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B21_dados_021.txt"
diff "$SAIDA/B05_dados_020.txt" "$SAIDA/B21_dados_021.txt" > "$SAIDA/B21_diff_dados.txt" || falha "B2: a 021 alterou dados (ver B21_diff_dados.txt)"
lista "$POP" nekkora > "$SAIDA/B22_catalogo_021.txt"
diff "$SAIDA/B05_catalogo_020.txt" "$SAIDA/B22_catalogo_021.txt" > "$SAIDA/B22_diff_catalogo.txt" || true
grep -E '^[<>]' "$SAIDA/B22_diff_catalogo.txt" | sed -E 's/\|md5=[0-9a-f]+/|md5=.../' | sort > "$SAIDA/B22_diff_resumo.txt"
sort "$AQUI/esperado/B22_diff_esperado.txt" > "$SAIDA/_b.txt"
diff "$SAIDA/_b.txt" "$SAIDA/B22_diff_resumo.txt" > "$SAIDA/B22_fora_do_previsto.txt" || { cat "$SAIDA/B22_fora_do_previsto.txt"; falha "B2: catalogo 020 -> 021 fora do previsto"; }
diff "$SAIDA/A02_catalogo_021.txt" "$SAIDA/B22_catalogo_021.txt" > "$SAIDA/B23_diff_limpa.txt" || falha "B2: catalogo da atualizada difere da instalacao limpa (ver B23)"
invariantes "$POP" B24

echo "   B3: reaplicacao da 021"
if psqlc "$POP" nekkora < "$M021" > "$SAIDA/B30_reaplica.txt" 2>&1; then falha "B3: 021 reaplicada"; fi
grep -qF "021: mail_outbox ja existe" "$SAIDA/B30_reaplica.txt" || { cat "$SAIDA/B30_reaplica.txt"; falha "B3: recusa diferente da pre-condicao"; }
psqlc "$POP" nekkora < "$SAIDA/B05_instantaneo.sql" > "$SAIDA/B31_dados.txt"
diff "$SAIDA/B21_dados_021.txt" "$SAIDA/B31_dados.txt" > /dev/null || falha "B3: dados alterados"
lista "$POP" nekkora > "$SAIDA/B31_catalogo.txt"
diff "$SAIDA/B22_catalogo_021.txt" "$SAIDA/B31_catalogo.txt" > /dev/null || falha "B3: catalogo alterado"

# ================================================================== C. identidade de backup da topologia de implantacao
# (missao PLATFORM-API-V1-003): a homologacao (ops/homologacao/sql/bootstrap.sql, IMPLANTACAO.md sec. 1) tem backup_nekkora
# LOGIN BYPASSRLS membro de pg_read_all_data. Na revisao 1 a invariante 1 da sec. 5 contava o SELECT HERDADO de
# pg_read_all_data como acesso a mail_outbox e a 021 falhava na implantacao do ensaio-api-v1-002 (relatorio da missao).
echo ">> C: 001-020 + identidade de backup (pg_read_all_data) -> 021 ($BKP)"
sobe "$BKP" 20 020
consulta "$BKP" nekkora "CREATE ROLE backup_nekkora LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION BYPASSRLS" > /dev/null
consulta "$BKP" nekkora "GRANT pg_read_all_data TO backup_nekkora" > /dev/null
lista "$BKP" nekkora > "$SAIDA/C00_catalogo_020.txt"
echo "   C0: revisao 1 da 021 (bf977d7) com a identidade de backup: recusada pela invariante, nada muda"
docker exec "$BKP" mkdir -p /nekkora-r1 > /dev/null
git -C "$REPO" show bf977d7:db/migrations/021_correio_fila.sql > "$SAIDA/C01_021_revisao1.sql"
[ "$(sha256sum "$SAIDA/C01_021_revisao1.sql" | cut -c1-64)" = 2aeaf4b9315b131110589ed507dd1464cf7af891280e8e394bdf22c55ab9207f ] || falha "C0: revisao 1 com outro sha256"
docker cp "$SAIDA/C01_021_revisao1.sql" "$BKP":/nekkora-r1/021_correio_fila.sql > /dev/null
if docker exec -e NEKKORA_DB=nekkora -e NEKKORA_DESDE=021 -e NEKKORA_ATE=021 -e NEKKORA_ELEVAR="$ELEVAR" -e NEKKORA_MIGRACOES=/nekkora-r1 \
       "$BKP" sh /tmp/modelo.sh > "$SAIDA/C02_revisao1.txt" 2>&1; then falha "C0: a revisao 1 aplicou com a identidade de backup (reproducao esperada)"; fi
grep -qF "021: papel com acesso a mail_outbox alem da Platform: backup_nekkora" "$SAIDA/C02_revisao1.txt" || { cat "$SAIDA/C02_revisao1.txt"; falha "C0: recusa diferente da reproduzida"; }
[ "$(consulta "$BKP" nekkora "SELECT to_regclass('public.mail_outbox') IS NULL")" = t ] || falha "C0: mail_outbox ficou"
lista "$BKP" nekkora > "$SAIDA/C03_catalogo.txt"
diff "$SAIDA/C00_catalogo_020.txt" "$SAIDA/C03_catalogo.txt" > /dev/null || falha "C0: catalogo alterado pela recusa"
[ "$(estado_migrador "$BKP")" = "0|0|0 dono_do_banco=migrador_nekkora" ] || falha "C0: migrador fora de 0|0|0"
echo "   C1: revisao atual com a identidade de backup: aplicada sem janela; invariantes depois da retirada"
modelo "$BKP" 021 021 "$SAIDA/C10_021.txt" || { cat "$SAIDA/C10_021.txt"; falha "C1: 021 recusada com a identidade de backup"; }
grep -q "aplicada versao=021 elevada=nao" "$SAIDA/C10_021.txt" || falha "C1: 021 aplicada com elevacao"
[ "$(consulta "$BKP" nekkora "SELECT has_table_privilege('backup_nekkora', 'public.mail_outbox', 'SELECT')")" = t ] || falha "C1: backup sem a leitura herdada (o caso nao exercita a heranca)"
invariantes "$BKP" C11
lista "$BKP" nekkora > "$SAIDA/C12_catalogo_021.txt"
diff "$SAIDA/A02_catalogo_021.txt" "$SAIDA/C12_catalogo_021.txt" > "$SAIDA/C12_diff_limpa.txt" || falha "C1: catalogo difere da instalacao limpa (ver C12)"
echo "   C2: a invariante continua recusando todo acesso que nao vem de papel predefinido (cada caso em BEGIN ... ROLLBACK)"
awk '/^-- === 5\. /{f=1} f && /^COMMIT;/{exit} f' "$M021" > "$SAIDA/C20_inv021.sql"
[ "$(grep -c '^DO \$\$' "$SAIDA/C20_inv021.sql")" = 1 ] || falha "C2: extracao da sec. 5 da 021"
c2() {   # $1 rotulo, $2 alteracao (vazia = controle), $3 trecho esperado na recusa (vazio = deve passar)
    local o rc=0
    o="$({ echo "BEGIN;"; printf '%s\n' "$2"; cat "$SAIDA/C20_inv021.sql"; echo "ROLLBACK;"; } | psqlc "$BKP" nekkora 2>&1)" || rc=$?
    echo "$1|rc=$rc|$(echo "$o" | grep -m1 -o '021: .*' || echo '-')" >> "$SAIDA/C21_contraprovas.txt"
    if [ -z "$3" ]; then [ "$rc" = 0 ] || falha "C2 $1: controle recusado ($o)"
    else [ "$rc" != 0 ] && echo "$o" | grep -qF "$3" || falha "C2 $1: esperado '$3' (rc=$rc: $o)"; fi
}
c2 controle "" ""
c2 grant_explicito_ao_backup "GRANT SELECT ON public.mail_outbox TO backup_nekkora;" "alem da Platform: backup_nekkora"
c2 grant_de_coluna_ao_portal "GRANT SELECT (kind) ON public.mail_outbox TO nekkora_portal;" "alem da Platform: nekkora_portal"
c2 grant_a_public "GRANT SELECT ON public.mail_outbox TO PUBLIC;" "alem da Platform: PUBLIC"
c2 membro_da_platform "CREATE ROLE c2_herdeiro NOLOGIN; GRANT nekkora_platform TO c2_herdeiro;" "alem da Platform: c2_herdeiro"
c2 membro_do_dono "CREATE ROLE c2_dono NOLOGIN; GRANT migrador_nekkora TO c2_dono;" "alem da Platform: c2_dono"
c2 escrita_ao_backup "GRANT INSERT ON public.mail_outbox TO backup_nekkora;" "alem da Platform: backup_nekkora"
lista "$BKP" nekkora > "$SAIDA/C22_catalogo.txt"
diff "$SAIDA/C12_catalogo_021.txt" "$SAIDA/C22_catalogo.txt" > /dev/null || falha "C2: catalogo alterado pelas contraprovas"

{
    echo "A: 21 migrations (001-021); janelas SUPERUSER com retirada 0|0|0: $(grep -c 'SUPERUSER revogado, estado 0|0|0' "$SAIDA/entrypoint_$LIMPA.txt") (021 sem janela); invariantes 012/013/014(adaptada)/016/017(adaptada)/019(adaptada)/020/021 DEPOIS da retirada ($(cat "$SAIDA/A01_estado_migrador.txt")): OK; marcador versao=021"
    echo "B: $(cat "$SAIDA/B01_massa_001_012.txt")"
    echo "B: $(cat "$SAIDA/B03_massa_mfa.txt")"
    echo "B: tabelas no instantaneo (public e ops): $(wc -l < "$SAIDA/B05_dados_020.txt" | tr -d ' ')"
    echo "B2: 021 sem janela: diff de dados vazio; catalogo 020->021 = exatamente $(wc -l < "$SAIDA/B22_diff_resumo.txt" | tr -d ' ') linhas previstas; = instalacao limpa; invariantes ($(cat "$SAIDA/B24_estado_migrador.txt")): OK; marcador versao=021"
    echo "B3: reaplicacao recusada pela pre-condicao; nada muda"
    echo "C0: revisao 1 (2aeaf4b9...) com backup_nekkora em pg_read_all_data: recusada ('021: papel com acesso a mail_outbox alem da Platform: backup_nekkora'), sem efeito"
    echo "C1: revisao atual com a mesma identidade: aplicada sem janela, leitura herdada do backup presente, invariantes depois da retirada OK, catalogo = instalacao limpa"
    echo "C2: $(grep -c . "$SAIDA/C21_contraprovas.txt") casos (controle + grant explicito, de coluna, PUBLIC, membro da Platform, membro do dono, escrita): todos como esperado"
} | tee "$SAIDA/resumo.txt"
rm -f "$SAIDA/_b.txt"
docker rm -f -v "$LIMPA" "$POP" "$BKP" > /dev/null
echo "saidas: $SAIDA"
