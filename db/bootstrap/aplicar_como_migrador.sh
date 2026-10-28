#!/bin/sh
# Aplica as migrations de db/migrations NO MODELO SUPORTADO pela 016 (revisao 5): identidade de implantacao
# migrador_nekkora (LOGIN, sem atributos) DONA do banco; SUPERUSER so durante as versoes que exigem elevacao e BYPASSRLS
# so durante a 014 (P4-R2a-C); depois de cada janela, retirada e conferencia (0|0|0). E o modelo de ops/homologacao
# (sql/bootstrap.sql + implantacao/implantar.sh) reproduzido em base DESCARTAVEL de teste e no bootstrap de
# desenvolvimento. Ao final, se a 016 estiver no intervalo, a sec. 19 da 016 roda de novo (em BEGIN ... ROLLBACK) DEPOIS
# da retirada.
#
# Diferenca registrada em relacao a homologacao: a aplicacao roda numa sessao do ADMINISTRADOR do cluster com
# SET ROLE migrador_nekkora (current_user = migrador: donos, privilegios e perda do SUPERUSER/BYPASSRLS fora da janela sao
# os do migrador; session_user e o administrador). Nenhuma migration usa session_user/current_user fora de corpo de funcao.
# Motivo: no entrypoint o servidor so escuta no socket e o pg_hba da forma "homologacao" recusa o migrador no socket.
#
# Contextos:
#   * entrypoint do postgres:16-alpine: copiado como /docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh, com as
#     migrations em /nekkora/migracoes (NAO em /docker-entrypoint-initdb.d, que as aplicaria como superusuario);
#   * servidor no ar: docker exec <c> env NEKKORA_DB=... NEKKORA_DESDE=... sh /nekkora/aplicar_como_migrador.sh
# Variaveis: NEKKORA_MIGRACOES (padrao /nekkora/migracoes), NEKKORA_DB (padrao $POSTGRES_DB ou nekkora),
#   NEKKORA_ADMIN (padrao $POSTGRES_USER ou nekkora), NEKKORA_DESDE/NEKKORA_ATE (versoes, inclusive; padrao todas),
#   NEKKORA_ELEVAR (SUPERUSER; padrao "011 012 013 016 017 018 019 020 022 023 024": as tres de ops/homologacao/implantacao/elevacao.txt,
#   a 016 e as candidatas 017-020 e 022-024, cujos cabecalhos exigem superusuario - a 018 e a 024 substituem funcoes dos donos da
#   016 e da 017; a 019, a 020, a 023 e a 024 criam funcoes do dono da 017; a 022 cria papel e transfere funcoes ao dono operacional
#   da 016. A 021 nao tem janela. 016-020 tem linha vinculada ao ensaio-api-v1-002 em elevacao.txt (missao PLATFORM-API-V1-003);
#   a 022, a 023 e a 024 ainda nao), NEKKORA_BYPASS (BYPASSRLS; padrao "014": a 014 so precisa atravessar a RLS
#   forcada de user_sessions), NEKKORA_REGISTRO (padrao /tmp/nekkora_janelas.txt). Uma versao nao pode estar nas duas.
# MARCADOR de inicializacao completa (P4-R2a-C, C4): no inicio o comentario do banco e APAGADO; so no fim do sucesso, se
# esta execucao aplicou ate a ULTIMA migration disponivel no diretorio, grava "nekkora-inicializacao-completa versao=NNN".
# Volume com initdb falho ou interrompido fica SEM marcador (o entrypoint nao reexecuta initdb.d ao reiniciar; a verificacao
# de saude de docker-compose.modelo.yml recusa o volume sem marcador). Local: db/bootstrap/ (P4-R2a-C2): usado pelas
# suites de teste (db/tests, bases Java) E pelo bootstrap de desenvolvimento em volume novo (docker-compose.modelo.yml).
# Retirada: no sucesso e na falha da migration (antes de abortar). Interrupcao abrupta (SIGKILL) nao executa nada: o
# proximo inicio deste script RECUSA o estado != 0|0|0 e o volume fica sem marcador.
# Saida: uma linha "aplicar_como_migrador: ..." por evento (vai para o log do container). Falha: codigo != 0.
# Sem "exit" no caminho de sucesso: o entrypoint pode carregar este arquivo com "." se ele nao for executavel.

N_DIR="${NEKKORA_MIGRACOES:-/nekkora/migracoes}"
N_DB="${NEKKORA_DB:-${POSTGRES_DB:-nekkora}}"
N_ADMIN="${NEKKORA_ADMIN:-${POSTGRES_USER:-nekkora}}"
N_DESDE="${NEKKORA_DESDE:-000}"
N_ATE="${NEKKORA_ATE:-999}"
N_ELEVAR="${NEKKORA_ELEVAR:-011 012 013 016 017 018 019 020 022 023 024}"
N_BYPASS="${NEKKORA_BYPASS-014}"   # sem ":": NEKKORA_BYPASS="" (definida e vazia) = nenhuma janela BYPASSRLS
N_REG="${NEKKORA_REGISTRO:-/tmp/nekkora_janelas.txt}"

n_log() { echo "aplicar_como_migrador: $*"; { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" >> "$N_REG"; } 2>/dev/null || true; }
n_adm() { psql -X -q -At -v ON_ERROR_STOP=1 --username "$N_ADMIN" --no-password --dbname "$N_DB" "$@"; }
n_estado() {
    n_adm -c "SELECT (SELECT rolsuper::int + rolcreaterole::int + rolcreatedb::int + rolbypassrls::int + rolreplication::int
        FROM pg_roles WHERE rolname = 'migrador_nekkora') || '|' ||
        (SELECT count(*) FROM pg_auth_members WHERE member = 'migrador_nekkora'::regrole OR roleid = 'migrador_nekkora'::regrole)
        || '|' || (SELECT count(*) FROM pg_stat_activity WHERE usename = 'migrador_nekkora')"
}
n_falha() { n_log "FALHA: $*"; exit 1; }
n_num() { echo "$1" | sed 's/^0*\([0-9]\)/\1/'; }   # "016" -> 16 (sem leitura octal)
n_revoga() {   # $1 versao, $2 atributo: retirada de TODOS os atributos e conferencia; nunca deixa o migrador elevado
    n_adm -c "ALTER ROLE migrador_nekkora NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS NOREPLICATION" \
        || n_falha "revogacao da elevacao ($1) falhou"
    N_E="$(n_estado)"
    n_log "fim    versao=$1 $2 revogado, estado $N_E"
    [ "$N_E" = "0|0|0" ] || n_falha "estado do migrador depois de $1: $N_E"
}

mkdir -p "$(dirname "$N_REG")" 2>/dev/null || true
# RECUPERACAO (NEKKORA_RECUPERAR=1, so por "docker exec", nunca no entrypoint): depois de uma interrupcao abrupta, retira
# TODOS os atributos do migrador e confere 0|0|0; nao aplica nada e NAO grava marcador (o banco continua incompleto)
if [ "${NEKKORA_RECUPERAR:-}" = 1 ]; then
    n_log "recuperacao: estado antes $(n_estado)"
    n_revoga "(recuperacao)" "TODOS"
    n_log "recuperacao concluida: estado $(n_estado); marcador $(n_adm -c "SELECT coalesce(shobj_description(oid, 'pg_database'), 'AUSENTE') FROM pg_database WHERE datname = current_database()")"
    exit 0
fi
[ -d "$N_DIR" ] || n_falha "diretorio de migrations ausente: $N_DIR"
for N_V in $N_BYPASS; do
    case " $N_ELEVAR " in *" $N_V "*) n_falha "versao $N_V em NEKKORA_ELEVAR e NEKKORA_BYPASS ao mesmo tempo" ;; esac
done
# 1. identidade de implantacao (como ops/homologacao/sql/bootstrap.sql): LOGIN, sem atributos, dona do banco
n_adm -c "DO \$\$ BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'migrador_nekkora') THEN
                CREATE ROLE migrador_nekkora LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS;
            END IF;
          END \$\$" -c "ALTER DATABASE \"$N_DB\" OWNER TO migrador_nekkora" || n_falha "bootstrap do migrador em $N_DB"
[ "$(n_adm -c "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()")" = migrador_nekkora ] \
    || n_falha "dono de $N_DB nao e migrador_nekkora"
[ "$(n_estado)" = "0|0|0" ] || n_falha "estado inicial do migrador: $(n_estado) (residuo de execucao interrompida?)"
n_adm -c "COMMENT ON DATABASE \"$N_DB\" IS NULL" || n_falha "remocao do marcador de inicializacao"
n_log "inicio db=$N_DB intervalo=$N_DESDE-$N_ATE elevar=[$N_ELEVAR] bypassrls=[$N_BYPASS] dono_do_banco=migrador_nekkora estado=0|0|0"

# 2. migrations em ordem, como migrador; SUPERUSER/BYPASSRLS so nas versoes das listas
N_QTD=0
N_016=""
N_ULT=""
for N_F in "$N_DIR"/[0-9][0-9][0-9]_*.sql; do
    [ -f "$N_F" ] || continue
    N_V="$(basename "$N_F" | cut -c1-3)"
    [ "$(n_num "$N_V")" -lt "$(n_num "$N_DESDE")" ] && continue
    [ "$(n_num "$N_V")" -gt "$(n_num "$N_ATE")" ] && continue
    [ "$(n_estado)" = "0|0|0" ] || n_falha "estado do migrador antes de $N_V: $(n_estado)"
    N_ELEVA=nao
    case " $N_ELEVAR " in *" $N_V "*) N_ELEVA=SUPERUSER ;; esac
    case " $N_BYPASS " in *" $N_V "*) N_ELEVA=BYPASSRLS ;; esac
    if [ "$N_ELEVA" != nao ]; then
        n_adm -c "ALTER ROLE migrador_nekkora $N_ELEVA" || n_falha "concessao da elevacao para $N_V"
        n_log "inicio versao=$N_V $N_ELEVA concedido ($(sha256sum "$N_F" | cut -c1-64))"
    fi
    N_RC=0
    { echo "SET ROLE migrador_nekkora;"; cat "$N_F"; echo; echo "RESET ROLE;"; } | n_adm > /dev/null || N_RC=$?
    # retirada no sucesso E na falha da migration, antes de abortar
    [ "$N_ELEVA" != nao ] && n_revoga "$N_V" "$N_ELEVA"
    [ "$N_RC" = 0 ] || n_falha "aplicacao de $(basename "$N_F") (codigo $N_RC)"
    n_log "aplicada versao=$N_V elevada=$N_ELEVA arquivo=$(basename "$N_F")"
    N_QTD=$((N_QTD + 1))
    N_ULT="$N_V"
    case "$(basename "$N_F")" in 016_*) N_016="$N_F" ;; esac
done
[ "$N_QTD" -gt 0 ] || n_falha "nenhuma migration no intervalo $N_DESDE-$N_ATE"
[ "$(n_estado)" = "0|0|0" ] || n_falha "estado final do migrador: $(n_estado)"

# 3. sec. 19 da 016 DEPOIS da retirada (o estado que o P4 mostrou que nenhum teste exercitava)
if [ -n "$N_016" ]; then
    awk '/^-- === 19\. /{f=1} f && /^COMMIT;/{exit} f' "$N_016" > /tmp/n_invariantes_016.sql
    [ "$(grep -c '^DO \$\$' /tmp/n_invariantes_016.sql)" = 1 ] || n_falha "extracao da sec. 19 da 016"
    { echo "BEGIN;"; cat /tmp/n_invariantes_016.sql; echo "ROLLBACK;"; } | n_adm > /tmp/n_invariantes_016.txt 2>&1 \
        || { cat /tmp/n_invariantes_016.txt; n_falha "sec. 19 da 016 depois da retirada"; }
    n_log "invariantes_016_pos_retirada=OK estado=$(n_estado) super=$(n_adm -c "SELECT rolsuper FROM pg_roles WHERE rolname = 'migrador_nekkora'")"
fi
# 4. marcador: so se esta execucao chegou a ULTIMA migration disponivel no diretorio
N_DISP="$(ls "$N_DIR" | grep -E '^[0-9]{3}_.*[.]sql$' | sort | tail -1 | cut -c1-3)"
if [ -n "$N_ULT" ] && [ "$N_ULT" = "$N_DISP" ]; then
    n_adm -c "COMMENT ON DATABASE \"$N_DB\" IS 'nekkora-inicializacao-completa versao=$N_DISP'" || n_falha "gravacao do marcador"
    n_log "marcador: nekkora-inicializacao-completa versao=$N_DISP"
fi
n_log "fim db=$N_DB aplicadas=$N_QTD estado=$(n_estado)"
