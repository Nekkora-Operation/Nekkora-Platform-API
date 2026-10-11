#!/usr/bin/env bash
# Semeadura do ensaio-frontend (F1.5, P-A) contra PostgreSQL REAL e DESCARTAVEL: migrations 001-016 do repositorio
# aplicadas pelo proprio db/bootstrap/aplicar_como_migrador.sh (o mesmo modelo do RealDatabase dos testes Java: migrador
# dono, elevacao so nas versoes que exigem, retirada conferida), orquestrador real (lib/semear_frontend.py), SQL real
# (sql/semear_frontend.sql) e PHC Argon2id real (PhcTool, como na fixture da 016).
#
# Cenarios (cada um com banco proprio, copia do banco recem-migrado):
#   S1 falha na gravacao das credenciais (diretorio de segredos inutilizavel) -> nada vai para o banco
#   S2 falha no meio da transacao (funcao da 009 indisponivel so neste banco) -> ROLLBACK, banco sem semeadura parcial,
#      plano pendente mantido; restaurada a funcao, a repeticao usa o MESMO plano (mesmos ids) e conclui sem duplicar
#   S3 repeticao sobre semeadura concluida -> recusada, nada muda
#   S4 resultado do psql perdido depois do COMMIT -> conferencia pela organizacao do plano, promocao, sem repetir
#   S5 falha na promocao do plano (destino bloqueado) -> A_RECONCILIAR; desbloqueado, a repeticao so promove
#   S6 diretorio de segredos novo sobre banco ja semeado -> INCONSISTENTE, nada e feito
#   S7 integridade da massa (somente leitura): estados, eventos, agendada da 009, reexecucao, empates, concessoes,
#      nomes longos, e-mails sinteticos, PHC Argon2id; e nenhuma senha na evidencia
#
# Recursos: UM container rotulado nekkora.teste=semeadura-frontend-<execucao>, removido pelo ID no fim (trap). Nenhum
# ambiente de homologacao e tocado.
# Uso: bash testes/test_semear_frontend_banco.sh [dir_de_saida]
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$AQUI/../.." && pwd)"
EXEC="sfb-$(date -u +%Y%m%dT%H%M%SZ)-$$"
SAIDA="${1:-$(mktemp -d)}"
mkdir -p "$SAIDA"
TMP="$(mktemp -d)"
CID=""
falhas=0
ok() { echo "OK    $*"; }
nok() { echo "FALHA $*"; falhas=$((falhas + 1)); }
confere() { if eval "$2"; then ok "$1"; else nok "$1"; fi; }
mixed() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
limpa() {
    [ -n "$CID" ] && docker rm -f "$CID" > /dev/null 2>&1 && echo "container de teste removido pelo ID: ${CID:0:12}"
    rm -rf "${TMP:?}"
}
trap limpa EXIT
BASH_EXE="$(mixed "$(command -v bash)")"
[ -f "C:/Program Files/Git/bin/bash.exe" ] && BASH_EXE="C:/Program Files/Git/bin/bash.exe"
export HOMOLOG_BASH="$BASH_EXE"

echo "== 0. banco descartavel (postgres:16-alpine, 001-016 pelo aplicar_como_migrador.sh)"
CID="$(docker create --label "nekkora.teste=semeadura-frontend-$EXEC" -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
      -e POSTGRES_PASSWORD="$(python -c 'import secrets; print(secrets.token_urlsafe(18))')" postgres:16-alpine)" || { nok "container"; exit 1; }
mkdir -p "$TMP/nekkora/migracoes"
for n in $(seq -w 1 16); do cp "$REPO"/db/migrations/0${n}_*.sql "$TMP/nekkora/migracoes/"; done
MSYS_NO_PATHCONV=1 docker cp "$(mixed "$TMP/nekkora")" "$CID:/nekkora" > /dev/null \
    && MSYS_NO_PATHCONV=1 docker cp "$(mixed "$REPO/db/bootstrap/aplicar_como_migrador.sh")" "$CID:/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh" > /dev/null \
    || { nok "copia das migrations"; exit 1; }
docker start "$CID" > /dev/null
pronto=""
for i in $(seq 1 90); do
    m="$(docker exec "$CID" psql -X -At -U nekkora -d nekkora -c "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = 'nekkora'" 2>/dev/null | tr -d '\r')"
    case "$m" in nekkora-inicializacao-completa*) pronto="$m"; break;; esac
    sleep 2
done
[ -n "$pronto" ] && ok "S0 banco migrado ($pronto)" || { nok "S0 banco nao inicializou"; docker logs "$CID" 2>&1 | tail -20; exit 1; }
docker logs "$CID" 2>&1 | grep 'aplicar_como_migrador:' > "$SAIDA/janelas_do_banco_de_teste.txt"
for b in s2 s4 s5 s9 s12; do docker exec "$CID" psql -X -q -U nekkora -d postgres -c "CREATE DATABASE nekkora_$b TEMPLATE nekkora" || nok "copia do banco $b"; done

psql_de() { echo "docker exec -i $CID psql -X -q -At -v ON_ERROR_STOP=1 -U nekkora -d $1"; }
JAR="$(mixed "$REPO/api/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar")"
TC="$(mixed "$REPO/api/nekkora-platform/target/test-classes")"
[ -f "$JAR" ] && [ -f "$REPO/api/nekkora-platform/target/test-classes/com/nekkora/platform/auth/crypto/PhcTool.class" ] || { nok "jar/PhcTool ausentes (mvnw package + test-compile)"; exit 1; }
PHC="java -Dloader.path='$TC' -Dloader.main=com.nekkora.platform.auth.crypto.PhcTool -cp '$JAR' org.springframework.boot.loader.launch.PropertiesLauncher 65536 3"
SEMEAR="$(mixed "$AQUI/lib/semear_frontend.py")"; SQL="$(mixed "$AQUI/sql/semear_frontend.sql")"
semeia() {  # $1 banco, $2 dir de segredos, $3 dir de evidencia, [$4 comando psql alternativo] -> codigo de saida
    python "$SEMEAR" semear --segredos "$(mixed "$2")" --evid "$(mixed "$3")" --sql "$SQL" --psql "${4:-$(psql_de "$1")}" --phc "$PHC" \
        >> "$3/saida.txt" 2>&1
}
q() { docker exec "$CID" psql -X -At -U nekkora -d "$1" -c "$2" 2>/dev/null | tr -d '\r'; }
ORGS="SELECT count(*) FROM organizations WHERE name IN ('Nekkora Demo', 'Nekkora Demo B')"
DEMO="SELECT count(*) FROM users WHERE email LIKE '%@demo.nekkora.test'"

echo "== S1. falha na gravacao das credenciais"
mkdir -p "$TMP/seg1" "$SAIDA/s1"; : > "$TMP/seg1/frontend"   # 'frontend' e ARQUIVO: o diretorio do plano nao pode ser criado
semeia nekkora "$TMP/seg1" "$SAIDA/s1"; rc=$?
confere "S1 codigo 8 (credenciais nao gravadas) e mensagem de STOP" "[ $rc = 8 ] && grep -q 'STOP: plano com as credenciais NAO gravado' '$SAIDA/s1/saida.txt'"
confere "S1 nada no banco (organizacoes e usuarios da semeadura = 0)" "[ \"\$(q nekkora \"$ORGS\")\" = 0 ] && [ \"\$(q nekkora \"$DEMO\")\" = 0 ]"

echo "== S2. falha no meio da transacao -> rollback; repeticao com o MESMO plano"
mkdir -p "$TMP/seg2" "$SAIDA/s2"
q nekkora_s2 "ALTER FUNCTION process_schedule_occurrence(uuid, boolean) RENAME TO process_schedule_occurrence_indisponivel" > /dev/null
semeia nekkora_s2 "$TMP/seg2" "$SAIDA/s2"; rc=$?
confere "S2 codigo 10 (NAO APLICADA) e plano pendente mantido" "[ $rc = 10 ] && [ -f '$TMP/seg2/frontend/semeadura.pendente.json' ] && [ ! -e '$TMP/seg2/frontend/semeadura.json' ]"
confere "S2 banco sem semeadura parcial (organizacoes, usuarios, workspaces, execucoes = 0)" \
    "[ \"\$(q nekkora_s2 \"$ORGS\")\" = 0 ] && [ \"\$(q nekkora_s2 \"$DEMO\")\" = 0 ] && [ \"\$(q nekkora_s2 'SELECT count(*) FROM workspaces')\" = 0 ] && [ \"\$(q nekkora_s2 'SELECT count(*) FROM executions')\" = 0 ]"
ID1="$(python -c "import json,sys; print(json.load(open(sys.argv[1], encoding='utf-8'))['orgs'][0]['id'])" "$(mixed "$TMP/seg2/frontend/semeadura.pendente.json")")"
X1="$(python -c "import json,sys; print(json.load(open(sys.argv[1], encoding='utf-8'))['execucao'])" "$(mixed "$TMP/seg2/frontend/semeadura.pendente.json")")"
q nekkora_s2 "ALTER FUNCTION process_schedule_occurrence_indisponivel(uuid, boolean) RENAME TO process_schedule_occurrence" > /dev/null
semeia nekkora_s2 "$TMP/seg2" "$SAIDA/s2"; rc=$?
confere "S2 repeticao: codigo 0, mesmo plano (execucao $X1) e CONCLUIDA" \
    "[ $rc = 0 ] && grep -q 'PENDENTE: reaproveitando o MESMO plano (execucao $X1)' '$SAIDA/s2/saida.txt' && grep -q 'CONCLUIDA' '$SAIDA/s2/saida.txt'"
confere "S2 sem duplicacao: 2 organizacoes, org A = id do plano pendente, 23 usuarios" \
    "[ \"\$(q nekkora_s2 \"$ORGS\")\" = 2 ] && [ \"\$(q nekkora_s2 \"SELECT organization_id FROM organizations WHERE name = 'Nekkora Demo'\")\" = '$ID1' ] && [ \"\$(q nekkora_s2 \"$DEMO\")\" = 23 ]"

echo "== S3. repeticao sobre semeadura concluida"
antes="$(q nekkora_s2 'SELECT count(*) FROM executions')"
semeia nekkora_s2 "$TMP/seg2" "$SAIDA/s2"; rc=$?
confere "S3 recusada (codigo 3) e nada muda (execucoes = $antes)" "[ $rc = 3 ] && [ \"\$(q nekkora_s2 'SELECT count(*) FROM executions')\" = '$antes' ]"

echo "== S4. resultado do psql perdido depois do COMMIT"
mkdir -p "$TMP/seg4" "$SAIDA/s4"
cat > "$TMP/psql_perde.sh" <<EOF
#!/usr/bin/env bash
# executa de verdade, mas o resultado do SCRIPT da semeadura se perde (saida descartada, codigo 1)
f="\$(mktemp)"; cat > "\$f"
if grep -q plano_b64 "\$f"; then $(psql_de nekkora_s4) < "\$f" > /dev/null 2>&1; rm -f "\$f"; exit 1; fi
$(psql_de nekkora_s4) < "\$f"; r=\$?; rm -f "\$f"; exit \$r
EOF
semeia nekkora_s4 "$TMP/seg4" "$SAIDA/s4" "bash '$(mixed "$TMP/psql_perde.sh")'"; rc=$?
confere "S4 codigo 0: transacao efetivada reconhecida pela organizacao do plano, sem repetir" \
    "[ $rc = 0 ] && grep -q 'resultado do psql perdido ou ambiguo' '$SAIDA/s4/saida.txt' && [ -f '$TMP/seg4/frontend/semeadura.json' ] && [ \"\$(q nekkora_s4 \"$ORGS\")\" = 2 ]"

echo "== S5. falha na promocao do plano"
mkdir -p "$TMP/seg5/frontend/semeadura.json" "$SAIDA/s5"   # destino bloqueado por um DIRETORIO
semeia nekkora_s5 "$TMP/seg5" "$SAIDA/s5"; rc=$?
confere "S5 codigo 6 (A_RECONCILIAR), semeadura no banco e credenciais no plano pendente" \
    "[ $rc = 6 ] && [ \"\$(q nekkora_s5 \"$ORGS\")\" = 2 ] && [ -f '$TMP/seg5/frontend/semeadura.pendente.json' ]"
confere "S5 estado informado: A_RECONCILIAR" "[ \"\$(python '$SEMEAR' estado --segredos '$(mixed "$TMP/seg5")' --psql \"$(psql_de nekkora_s5)\" | tr -d '\r')\" = A_RECONCILIAR ]"
rmdir "$TMP/seg5/frontend/semeadura.json"
u5="$(q nekkora_s5 "$DEMO")"
semeia nekkora_s5 "$TMP/seg5" "$SAIDA/s5"; rc=$?
confere "S5 repeticao so promove (codigo 0, RECONCILIADA, usuarios = $u5)" \
    "[ $rc = 0 ] && grep -q 'RECONCILIADA' '$SAIDA/s5/saida.txt' && [ \"\$(q nekkora_s5 \"$DEMO\")\" = '$u5' ] && [ -f '$TMP/seg5/frontend/semeadura.json' ] && [ ! -e '$TMP/seg5/frontend/semeadura.pendente.json' ]"

echo "== S6. diretorio de segredos novo sobre banco ja semeado"
mkdir -p "$TMP/seg6" "$SAIDA/s6"
semeia nekkora_s2 "$TMP/seg6" "$SAIDA/s6"; rc=$?
confere "S6 INCONSISTENTE (codigo 5), nada gravado no disco nem no banco" \
    "[ $rc = 5 ] && [ ! -e '$TMP/seg6/frontend' ] && [ \"\$(q nekkora_s2 \"$ORGS\")\" = 2 ]"

echo "== S7. integridade da massa (banco nekkora_s2, somente leitura)"
A="(SELECT organization_id FROM organizations WHERE name = 'Nekkora Demo')"
B="(SELECT organization_id FROM organizations WHERE name = 'Nekkora Demo B')"
confere "S7 todos os estados de execucao presentes" "[ \"\$(q nekkora_s2 \"SELECT count(DISTINCT (status, coalesce(status_reason, 0))) FROM executions WHERE organization_id = $A\")\" = 9 ]"
confere "S7 execucoes: 370 na A (369 do plano + a agendada) e 2 na B" "[ \"\$(q nekkora_s2 \"SELECT count(*) FROM executions WHERE organization_id = $A\")\" = 370 ] && [ \"\$(q nekkora_s2 \"SELECT count(*) FROM executions WHERE organization_id = $B\")\" = 2 ]"
confere "S7 agendada pela 009: origem 2, sem solicitante, concluida, com ocorrencia" \
    "[ \"\$(q nekkora_s2 \"SELECT count(*) FROM executions WHERE origin = 2 AND requested_by_membership_id IS NULL AND status = 4 AND schedule_occurrence_id IS NOT NULL\")\" = 1 ]"
confere "S7 reexecucao: concluida com retry_of de uma falha de processo" \
    "[ \"\$(q nekkora_s2 \"SELECT count(*) FROM executions x JOIN executions o ON o.execution_id = x.retry_of_execution_id WHERE o.status = 5 AND o.status_reason = 3 AND x.status = 4\")\" = 1 ]"
confere "S7 empate exato de data (4 execucoes com o mesmo created_at)" \
    "[ \"\$(q nekkora_s2 \"SELECT max(c) FROM (SELECT count(*) c FROM executions WHERE organization_id = $A GROUP BY created_at) t\")\" -ge 4 ]"
confere "S7 eventos coerentes: iniciada/terminada tem evento; pendente, cancelada antes e prazo de fila sem evento" \
    "[ \"\$(q nekkora_s2 \"SELECT count(*) FROM executions x WHERE x.organization_id = $A AND ((x.started_at IS NOT NULL) <> EXISTS (SELECT 1 FROM execution_events e WHERE e.execution_id = x.execution_id AND e.event_type = 1))\")\" = 0 ] \
     && [ \"\$(q nekkora_s2 \"SELECT count(*) FROM executions x WHERE (x.status = 1 OR x.status_reason IN (4, 6)) AND EXISTS (SELECT 1 FROM execution_events e WHERE e.execution_id = x.execution_id)\")\" = 0 ]"
confere "S7 nada termina no futuro e horarios em ordem" \
    "[ \"\$(q nekkora_s2 \"SELECT count(*) FROM executions WHERE finished_at > now() OR started_at > now() OR reserved_at < created_at\")\" = 0 ]"
confere "S7 concessoes do cliente: pendente, ativa e revogada" "[ \"\$(q nekkora_s2 \"SELECT string_agg(DISTINCT status::text, ',' ORDER BY status::text) FROM client_workspace_grants WHERE organization_id = $A\")\" = '1,2,4' ]"
confere "S7 primeiro ORG_ADMIN de cada organizacao pelo provision_first_org_admin (2 atribuicoes sem responsavel)" \
    "[ \"\$(q nekkora_s2 \"SELECT count(*) FROM organization_role_assignments WHERE organization_id IN ($A, $B) AND assigned_by_membership_id IS NULL\")\" = 2 ]"
confere "S7 nomes longos com o mesmo prefixo de 48 pontos de codigo e nomes nao ASCII" \
    "[ \"\$(q nekkora_s2 \"SELECT count(*) FROM (SELECT left(name, 48) p FROM workspaces WHERE organization_id = $A AND length(name) > 48 GROUP BY 1 HAVING count(*) = 2) t\")\" = 1 ] && [ \"\$(q nekkora_s2 \"SELECT count(*) FROM workspaces WHERE name LIKE '%𝔸%' OR name LIKE '%ｚ%'\")\" = 2 ]"
confere "S7 estados finais: workspace suspenso e arquivado, automacao suspensa e arquivada, sandbox sem automacoes" \
    "[ \"\$(q nekkora_s2 \"SELECT string_agg(status::text, ',' ORDER BY status) FROM workspaces WHERE organization_id = $A AND status <> 1\")\" = '2,3' ] && [ \"\$(q nekkora_s2 \"SELECT string_agg(status::text, ',' ORDER BY status) FROM automations WHERE organization_id = $A AND status <> 1\")\" = '2,3' ] && [ \"\$(q nekkora_s2 \"SELECT count(*) FROM automations a JOIN workspaces w USING (workspace_id) WHERE w.name = 'Sandbox da equipe'\")\" = 0 ]"
confere "S7 so e-mails sinteticos e todas as senhas em PHC Argon2id" \
    "[ \"\$(q nekkora_s2 \"SELECT count(*) FROM users WHERE organization_id IN ($A, $B) AND email NOT LIKE '%@demo.nekkora.test'\")\" = 0 ] && [ \"\$(q nekkora_s2 \"SELECT count(*) FROM user_credentials c JOIN users u USING (user_id) WHERE u.organization_id IN ($A, $B) AND c.password_hash NOT LIKE chr(36) || 'argon2id' || chr(36) || '%'\")\" = 0 ]"
confere "S7 contas sem MFA (nenhum fator; o cadastro e pela API depois)" "[ \"\$(q nekkora_s2 \"SELECT count(*) FROM user_mfa_factors f JOIN users u USING (user_id) WHERE u.organization_id IN ($A, $B)\")\" = 0 ]"
# nenhuma senha do plano final em qualquer arquivo de evidencia deste teste
python - "$(mixed "$TMP/seg2/frontend/semeadura.json")" "$(mixed "$SAIDA")" > "$TMP/vaz.txt" <<'PY'
import json, os, sys
senhas = [c['senha'] for c in json.load(open(sys.argv[1], encoding='utf-8'))['contas']]
achados = []
for d, _, fs in os.walk(sys.argv[2]):
    for f in fs:
        t = open(os.path.join(d, f), encoding='utf-8', errors='replace').read()
        achados += [f for s in senhas if s in t]
print(len(senhas), len(achados))
PY
confere "S7 nenhuma das 23 senhas aparece na evidencia (saidas, logs, mapa publico)" "[ \"\$(cat '$TMP/vaz.txt' | tr -d '\r')\" = '23 0' ]"
cp "$TMP/seg2/frontend/semeadura.json" /dev/null   # o plano final (com senhas) NAO vai para a evidencia

echo "== S12. duas semeaduras SIMULTANEAS no mesmo ambiente e no mesmo banco (P-A-R1: trava por ambiente)"
mkdir -p "$TMP/seg12" "$SAIDA/s12a" "$SAIDA/s12b"
semeia nekkora_s12 "$TMP/seg12" "$SAIDA/s12a" & PA=$!
semeia nekkora_s12 "$TMP/seg12" "$SAIDA/s12b" & PB=$!
wait $PA; ra=$?; wait $PB; rb=$?
confere "S12 uma conclui e a outra e recusada pela trava (codigos $ra e $rb)" \
    "[ \"\$(printf '%s\n' $ra $rb | sort -n | tr '\n' ' ')\" = '0 11 ' ] && grep -qh 'RECUSADA: outra semeadura em andamento' '$SAIDA/s12a/saida.txt' '$SAIDA/s12b/saida.txt'"
confere "S12 uma semeadura so no banco (2 organizacoes, 23 usuarios) e o plano final e o aplicado" \
    "[ \"\$(q nekkora_s12 \"$ORGS\")\" = 2 ] && [ \"\$(q nekkora_s12 \"$DEMO\")\" = 23 ] && [ \"\$(q nekkora_s12 \"SELECT organization_id FROM organizations WHERE name = 'Nekkora Demo'\")\" = \
     \"\$(python -c \"import json,sys; print(json.load(open(sys.argv[1], encoding='utf-8'))['orgs'][0]['id'])\" '$(mixed "$TMP/seg12/frontend/semeadura.json")' | tr -d '\r')\" ] \
     && [ ! -e '$TMP/seg12/frontend/semeadura.pendente.json' ] && [ ! -e '$TMP/seg12/frontend.trava' ]"

echo "== S8/S9. defesas DENTRO da transacao (SQL direto, sem o orquestrador, que recusaria antes)"
script_direto() {  # $1 = 'normal' ou 'esperado-errado' -> script SQL completo na saida padrao (plano novo, PHC ficticio)
    python - "$(mixed "$AQUI/lib")" "$SQL" "$1" <<'PY'
import random, sys
sys.path.insert(0, sys.argv[1])
import semear_frontend as sf
p = sf.catalogo_plano(random.Random())
for c in p['contas']:
    c['phc'] = '$argon2id$v=19$m=65536,t=3,p=1$ficticio$ficticio'
if sys.argv[3] == 'esperado-errado':
    orig = sf.esperado
    sf.esperado = lambda x: dict(orig(x), workspaces=orig(x)['workspaces'] + 1)
sys.stdout.buffer.write(sf.script_sql(p, sys.argv[2]).encode('utf-8'))
PY
}
antes_u="$(q nekkora_s2 'SELECT count(*) FROM users')"; antes_e="$(q nekkora_s2 'SELECT count(*) FROM executions')"
script_direto normal | docker exec -i "$CID" psql -X -q -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora_s2 > "$TMP/s8.txt" 2>&1; rc=$?; cp "$TMP/s8.txt" "$SAIDA/s8_psql.txt"
confere "S8 SQL sobre banco ja semeado: recusado (SEMEADURA_RECUSADA) e nada gravado" \
    "[ $rc != 0 ] && grep -q SEMEADURA_RECUSADA '$TMP/s8.txt' && [ \"\$(q nekkora_s2 'SELECT count(*) FROM users')\" = '$antes_u' ] && [ \"\$(q nekkora_s2 'SELECT count(*) FROM executions')\" = '$antes_e' ]"
script_direto esperado-errado | docker exec -i "$CID" psql -X -q -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora_s9 > "$TMP/s9.txt" 2>&1; rc=$?; cp "$TMP/s9.txt" "$SAIDA/s9_psql.txt"
confere "S9 contagem diferente do esperado: conferencia antes do COMMIT desfaz tudo (banco vazio)" \
    "[ $rc != 0 ] && grep -q 'conferencia: .* workspaces' '$TMP/s9.txt' && [ \"\$(q nekkora_s9 \"$ORGS\")\" = 0 ] && [ \"\$(q nekkora_s9 'SELECT count(*) FROM executions')\" = 0 ] && [ \"\$(q nekkora_s9 'SELECT count(*) FROM users')\" = 0 ]"

echo "== S10/S11. operacao declarada de sessao (sql/sessao_frontend.sql) so na conta exclusiva"
SQLX="$AQUI/sql/sessao_frontend.sql"
sessao() {  # $1 email da conta, $2 tipo -> saida do psql
    local u o
    u="$(q nekkora_s2 "SELECT user_id FROM users WHERE email = '$1'")"; o="$(q nekkora_s2 "SELECT organization_id FROM organizations WHERE name = 'Nekkora Demo'")"
    docker exec -i "$CID" psql -X -q -At -v ON_ERROR_STOP=1 -U nekkora -d nekkora_s2 -v usuario="$u" -v organizacao="$o" -v tipo="$2" < "$SQLX" 2>&1
}
sessao j-admin@demo.nekkora.test absoluta > "$SAIDA/s10_psql.txt"; sessao expira@demo.nekkora.test inatividade > "$SAIDA/s11_psql.txt"
confere "S10 outra conta (j-admin@) recusada" "grep -q 'expiracao recusada' '$SAIDA/s10_psql.txt'"
confere "S11 tipo inatividade recusado (so o relogio real)" "grep -q 'tipo invalido' '$SAIDA/s11_psql.txt'"
confere "S11b conta exclusiva com tipo absoluta: aceita (sem sessoes ativas: 0 envelhecidas)" "sessao expira@demo.nekkora.test absoluta | grep -qx 'SESSOES_ENVELHECIDAS=0'"

docker exec "$CID" psql -X -At -U nekkora -d nekkora_s2 -c "SELECT status, coalesce(status_reason, 0), count(*) FROM executions GROUP BY 1, 2 ORDER BY 1, 2" \
    | tr -d '\r' > "$SAIDA/contagem_por_estado.txt"
echo
[ "$falhas" = 0 ] && echo "RESULTADO: semeadura do ensaio-frontend OK" || echo "RESULTADO: $falhas falha(s)"
exit $([ "$falhas" = 0 ] && echo 0 || echo 1)
