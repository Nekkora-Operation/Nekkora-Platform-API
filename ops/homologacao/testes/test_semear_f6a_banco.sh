#!/usr/bin/env bash
# Semeadura COMPLEMENTAR da F6a-V (lib/semear_f6a.py + sql/semear_f6a.sql) e fotos de preservacao (testes/validar_f6a.py
# foto/comparar) contra PostgreSQL REAL e DESCARTAVEL: migrations 001-016 pelo db/bootstrap/aplicar_como_migrador.sh,
# semeadura da F1.5 real como base (lib/semear_frontend.py), PHC Argon2id real (PhcTool).
#
# Cenarios (cada um com banco proprio, copia do banco ja com a base da F1.5):
#   F1 sem a base da F1.5 (segredos sem plano final) -> recusada (14), nada no banco
#   (correcao da V2 da F6a-V-E) caminhos validos F2 e F4 exigem o psql SEM erro: nenhum "psql codigo", nenhuma
#      reconciliacao por resultado ambiguo, SEMEADURA_F6A_APLICADA 4 e as duas linhas CONFERE completas. A recuperacao
#      legitima de resultado perdido DEPOIS do COMMIT continua provada a parte (F5).
#   F9 (F6a-V-E, STOP da V4.4) estado das sessoes pelo SQL do verificador (validar_f6a.sessoes_conta e estado_conta):
#      utilizavel, revogada, expirada (absoluta) e expirada por INATIVIDADE (30 min interna, 15 min externa); a
#      inatividade NAO preenche revoked_at
#   F2 caminho valido: AUSENTE -> CONCLUIDA; 4 contas, vinculos, papeis, 2 concessoes ativas, mapa publico sem senha;
#      repeticao recusada (3) sem mudar nada; o resto do banco (linhas nao f6av) IDENTICO ao de antes (foto)
#   F3 falha na gravacao das credenciais -> 8, nada no banco
#   F4 base fora do esperado no meio (workspace do plano suspenso) -> ROLLBACK (10), plano pendente mantido; restaurado,
#      a repeticao usa o MESMO plano e conclui
#   F5 resultado do psql perdido depois do COMMIT -> reconhecida pela conta do plano, promovida sem repetir
#   F6 conta f6av-* no banco sem plano no disco -> INCONSISTENTE (5), nada feito
#   F7 trava ocupada (a MESMA da semeadura da F1.5) -> 11, nada consultado nem gravado
#   F8 fotos: mudancas PERMITIDAS da atora (sessao + desafio + last_used_step + evento de step-up) e dos alvos ->
#      PRESERVADO; cada mudanca proibida (outra conta, outro campo da atora, evento de outro tipo, contagem diferente,
#      sessao nova ainda ativa), cada uma num banco PROPRIO copiado do estado permitido -> VIOLACAO; foto sem valor de
#      coluna sensivel e sem PHC (last_used_step para menos: o proprio banco recusa; regra provada em test_validar_f6a.py)
#      R1 (dono da linha): reset de OUTRA conta com uma f6av-* so como ATORA -> VIOLACAO; fator de OUTRA conta reapontado
#      para um alvo -> o banco recusa; forcado (gatilhos desligados, banco descartavel) -> VIOLACAO "trocou de dono";
#      concessao de outra conta com a admin so como RESPONSAVEL -> VIOLACAO (linha de outra identidade)
#
# Recursos: UM container rotulado nekkora.teste=semeadura-f6a-<execucao>, removido pelo ID COM os volumes (-v) no fim.
# Nenhum ambiente de homologacao e tocado. Uso: bash testes/test_semear_f6a_banco.sh [dir_de_saida]
#
# PRE-CONDICOES (R1): caminho de saida curto (MAX_PATH do Windows), container, migrations, jar/PhcTool, base da F1.5
# CONCLUIDA e copias dos bancos. Se UMA falhar: "PRECONDICAO FALHOU: ..." e saida 2, SEM rodar cenario nenhum - o
# resultado nao vale como teste nem como contraprova. Em qualquer saida diferente de 0, os temporarios ficam
# PRESERVADOS para diagnostico FORA das evidencias (tem credenciais do banco descartavel), com o log do postgres; nas
# evidencias vai so a listagem (nome, tamanho, data) desses arquivos.
set -uo pipefail
AQUI="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$AQUI/../.." && pwd)"
EXEC="sf6-$(date -u +%Y%m%dT%H%M%SZ)-$$"
SAIDA="${1:-$(mktemp -d)}"
mkdir -p "$SAIDA"
TMP="$(mktemp -d)"
CID=""
falhas=0
precondicao() { echo "PRECONDICAO FALHOU: $* (resultado INVALIDO como teste e como contraprova)"; exit 2; }
ok() { echo "OK    $*"; }
nok() { echo "FALHA $*"; falhas=$((falhas + 1)); }
confere() { if eval "$2"; then ok "$1"; else nok "$1"; fi; }
mixed() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
limpa() {
    local rc=$?
    if [ "$rc" != 0 ]; then
        # diagnostico: temporarios e log do postgres PRESERVADOS fora das evidencias; nas evidencias, so a listagem
        [ -n "$CID" ] && docker logs "$CID" > "$TMP/postgres.log" 2>&1
        (cd "$TMP" && find . -printf '%TY-%Tm-%TdT%TH:%TM:%TS %10s %p\n' | sort -k3) > "$SAIDA/diagnostico_temporarios.txt" 2>&1
        echo "saida $rc: temporarios PRESERVADOS para diagnostico (contem credenciais do banco descartavel; fora das evidencias): $TMP"
    fi
    [ -n "$CID" ] && docker rm -fv "$CID" > /dev/null 2>&1 && echo "container de teste removido pelo ID (com volumes): ${CID:0:12}"
    [ "$rc" = 0 ] && rm -rf "${TMP:?}"
    return "$rc"
}
trap limpa EXIT
BASH_EXE="$(mixed "$(command -v bash)")"
[ -f "C:/Program Files/Git/bin/bash.exe" ] && BASH_EXE="C:/Program Files/Git/bin/bash.exe"
export HOMOLOG_BASH="$BASH_EXE" PYTHONDONTWRITEBYTECODE=1

echo "== 0. banco descartavel (postgres:16-alpine, 001-016) e base da F1.5"
# o arquivo mais fundo das evidencias (temporario do mapa da base) tem ~85 caracteres alem de SAIDA; MAX_PATH = 260
[ "${#SAIDA}" -le 170 ] && [ "$(mixed "$SAIDA" | wc -c)" -le 171 ] \
    || precondicao "caminho de saida longo demais para o MAX_PATH do Windows ($(mixed "$SAIDA" | tr -d '\n' | wc -c) > 170 caracteres)"
CID="$(docker create --label "nekkora.teste=semeadura-f6a-$EXEC" -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora \
      -e POSTGRES_PASSWORD="$(python -c 'import secrets; print(secrets.token_urlsafe(18))')" postgres:16-alpine)" || precondicao "container"
mkdir -p "$TMP/nekkora/migracoes"
for n in $(seq -w 1 16); do cp "$REPO"/db/migrations/0${n}_*.sql "$TMP/nekkora/migracoes/"; done
MSYS_NO_PATHCONV=1 docker cp "$(mixed "$TMP/nekkora")" "$CID:/nekkora" > /dev/null \
    && MSYS_NO_PATHCONV=1 docker cp "$(mixed "$REPO/db/bootstrap/aplicar_como_migrador.sh")" "$CID:/docker-entrypoint-initdb.d/000_aplicar_como_migrador.sh" > /dev/null \
    || precondicao "copia das migrations"
docker start "$CID" > /dev/null
pronto=""
for i in $(seq 1 90); do
    m="$(docker exec "$CID" psql -X -At -U nekkora -d nekkora -c "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = 'nekkora'" 2>/dev/null | tr -d '\r')"
    case "$m" in nekkora-inicializacao-completa*) pronto="$m"; break;; esac
    sleep 2
done
[ -n "$pronto" ] && ok "F0 banco migrado ($pronto)" || precondicao "F0 banco nao inicializou (log do postgres nos temporarios preservados)"
psql_de() { echo "docker exec -i $CID psql -X -q -At -v ON_ERROR_STOP=1 -U nekkora -d $1"; }
q() { docker exec "$CID" psql -X -At -U nekkora -d "$1" -c "$2" 2>>"$SAIDA/erros_psql.txt" | tr -d '\r'; }
JAR="$(mixed "$REPO/api/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar")"
TC="$(mixed "$REPO/api/nekkora-platform/target/test-classes")"
[ -f "$JAR" ] && [ -f "$REPO/api/nekkora-platform/target/test-classes/com/nekkora/platform/auth/crypto/PhcTool.class" ] || precondicao "jar/PhcTool ausentes (mvnw package + test-compile)"
PHC="java -Dloader.path='$TC' -Dloader.main=com.nekkora.platform.auth.crypto.PhcTool -cp '$JAR' org.springframework.boot.loader.launch.PropertiesLauncher 65536 3"
SF="$(mixed "$AQUI/lib/semear_frontend.py")"; S6="$(mixed "$AQUI/lib/semear_f6a.py")"; VF="$(mixed "$AQUI/testes/validar_f6a.py")"
SQLB="$(mixed "$AQUI/sql/semear_frontend.sql")"; SQL6="$(mixed "$AQUI/sql/semear_f6a.sql")"
mkdir -p "$TMP/base" "$SAIDA/base"
python "$SF" semear --segredos "$(mixed "$TMP/base")" --evid "$(mixed "$SAIDA/base")" --sql "$SQLB" --psql "$(psql_de nekkora)" --phc "$PHC" > "$SAIDA/base/saida.txt" 2>&1
grep -q 'CONCLUIDA' "$SAIDA/base/saida.txt" && [ -f "$TMP/base/frontend/semeadura.json" ] \
    || precondicao "F0 base da F1.5 NAO semeada (ver base/saida.txt)"
ok "F0 base da F1.5 semeada (CONCLUIDA)"
for b in f2 f3 f4 f5 f6 f7 f8; do docker exec "$CID" psql -X -q -U nekkora -d postgres -c "CREATE DATABASE nekkora_$b TEMPLATE nekkora" || precondicao "copia do banco $b"; done
docker exec "$CID" psql -X -q -U nekkora -d postgres -c "CREATE DATABASE nekkora_f1 TEMPLATE template1" > /dev/null || precondicao "banco vazio da F1"
ok "PRECONDICOES atendidas (caminho, banco migrado, base da F1.5, copias)"
seg() { mkdir -p "$TMP/$1/frontend"; cp "$TMP/base/frontend/semeadura.json" "$TMP/$1/frontend/"; }   # segredos do cenario com a base
semeia6() { python "$S6" semear --segredos "$(mixed "$2")" --evid "$(mixed "$3")" --sql "$SQL6" --psql "${4:-$(psql_de "$1")}" --phc "$PHC" >> "$3/saida.txt" 2>&1; }
F6AV="SELECT count(*) FROM users WHERE email LIKE 'f6av-%@demo.nekkora.test'"
NAOF6="SELECT md5(string_agg(u::text, ',' ORDER BY user_id)) FROM users u WHERE email NOT LIKE 'f6av-%'"
# caminho LIMPO de uma execucao da semeadura (log a partir da linha $2): psql sem erro e CONFERE completa
limpo() {
    local t; t="$(tail -n +"${2:-1}" "$1" | tr -d '\r')"
    ! grep -q 'psql codigo' <<< "$t" && ! grep -q 'perdido ou ambiguo' <<< "$t" \
        && grep -qE ' psql: SEMEADURA_F6A_APLICADA 4$' <<< "$t" \
        && grep -qE ' psql: CONFERE contas_f6av=f6av-cliente@demo\.nekkora\.test:2,f6av-mfa@demo\.nekkora\.test:2,f6av-reativacao@demo\.nekkora\.test:2,f6av-suspensao@demo\.nekkora\.test:2$' <<< "$t" \
        && grep -qE ' psql: CONFERE concessoes_f6av=2,2$' <<< "$t" \
        && grep -q 'CONCLUIDA: semeadura complementar efetivada' <<< "$t"
}

echo "== F1. sem a base da F1.5"
mkdir -p "$TMP/seg1" "$SAIDA/f1"
semeia6 nekkora_f1 "$TMP/seg1" "$SAIDA/f1"; rc=$?
confere "F1 recusada (codigo 14) e nada gravado" "[ $rc = 14 ] && grep -q 'semeadura da F1.5 nao esta CONCLUIDA' '$SAIDA/f1/saida.txt' && [ ! -e '$TMP/seg1/frontend/f6a' ]"

echo "== F2. caminho valido"
seg seg2; mkdir -p "$SAIDA/f2"
antes="$(q nekkora_f2 "$NAOF6")"
python "$VF" foto "$(mixed "$TMP/f2_antes.json")" --segredos "$(mixed "$TMP/seg2")" --psql "$(psql_de nekkora_f2)" > "$SAIDA/f2/foto_antes.txt" 2>&1
confere "F2 estado AUSENTE" "[ \"\$(python '$S6' estado --segredos '$(mixed "$TMP/seg2")' --psql \"$(psql_de nekkora_f2)\" | tr -d '\r')\" = AUSENTE ]"
semeia6 nekkora_f2 "$TMP/seg2" "$SAIDA/f2"; rc=$?
A="(SELECT organization_id FROM organizations WHERE name = 'Nekkora Demo')"
confere "F2 CONCLUIDA (codigo 0): 4 contas f6av-* ativas e verificadas" \
    "[ $rc = 0 ] && [ \"\$(q nekkora_f2 \"$F6AV AND status = 2 AND email_verified_at IS NOT NULL AND organization_id = $A\")\" = 4 ]"
confere "F2 caminho limpo: psql codigo 0, CONFERE completa (4 contas ativas; concessoes 2,2), sem reconciliacao por resultado ambiguo" \
    "limpo '$SAIDA/f2/saida.txt'"
confere "F2 vinculos: 3 de organizacao + 1 do cliente davila; 3 papeis VIEWER no sandbox atribuidos pela admin" \
    "[ \"\$(q nekkora_f2 \"SELECT count(*) FROM organization_memberships m JOIN users u USING (organization_id, user_id) WHERE u.email LIKE 'f6av-%' AND m.status = 2\")\" = 3 ] \
     && [ \"\$(q nekkora_f2 \"SELECT count(*) FROM client_memberships m JOIN users u USING (organization_id, user_id) JOIN clients c USING (organization_id, client_id) WHERE u.email = 'f6av-cliente@demo.nekkora.test' AND c.name LIKE 'D''Ávila%'\")\" = 1 ] \
     && [ \"\$(q nekkora_f2 \"SELECT count(*) FROM workspace_role_assignments r JOIN roles ro USING (role_id) JOIN workspaces w USING (organization_id, workspace_id) JOIN organization_memberships m USING (organization_id, membership_id) JOIN users u USING (organization_id, user_id) JOIN organization_memberships a ON a.membership_id = r.assigned_by_membership_id JOIN users au ON au.user_id = a.user_id WHERE u.email LIKE 'f6av-%' AND ro.code = 'VIEWER' AND w.name = 'Sandbox da equipe' AND au.email = 'admin@demo.nekkora.test'\")\" = 3 ]"
confere "F2 concessoes: f6av-c1 (Exportacao A) e f6av-c2 (Exportacao Z) ATIVAS, com o grant_id do plano" \
    "python - '$(mixed "$TMP/seg2/frontend/f6a/semeadura.json")' <<'PY' > '$TMP/f2_g.txt' && [ \"\$(q nekkora_f2 \"SELECT count(*) FROM client_workspace_grants WHERE status = 2 AND revoked_at IS NULL AND grant_id IN (\$(cat '$TMP/f2_g.txt'))\")\" = 2 ]
import json, sys
p = json.load(open(sys.argv[1], encoding='utf-8'))
print(','.join(\"'\" + g['grant_id'] + \"'\" for g in p['concessoes']))
PY"
confere "F2 mapa publico sem senha nem PHC; plano final com as credenciais nos segredos" \
    "[ -f '$SAIDA/f2/semeadura_f6a_mapa.json' ] && ! grep -qE 'senha|argon2|phc' '$SAIDA/f2/semeadura_f6a_mapa.json' && grep -q argon2id '$TMP/seg2/frontend/f6a/semeadura.json'"
confere "F2 linhas de users fora de f6av-* IDENTICAS" "[ \"\$(q nekkora_f2 \"$NAOF6\")\" = '$antes' ]"
python "$VF" foto "$(mixed "$TMP/f2_depois.json")" --segredos "$(mixed "$TMP/seg2")" --psql "$(psql_de nekkora_f2)" > "$SAIDA/f2/foto_depois.txt" 2>&1
python "$VF" comparar "$(mixed "$TMP/f2_antes.json")" "$(mixed "$TMP/f2_depois.json")" --segredos "$(mixed "$TMP/seg2")" --evid "$(mixed "$SAIDA/f2")" --logins-admin 0 --stepups 0 > "$SAIDA/f2/comparar.txt" 2>&1; rc=$?
confere "F2 foto antes x depois da semeadura: so linhas de alvos (f6av-*) mudaram -> PRESERVADO" \
    "[ $rc = 0 ] && grep -q 'RESULTADO: PRESERVADO' '$SAIDA/f2/comparar.txt' && grep -q '^ALVO users INSERT' '$SAIDA/f2/comparar.txt' && grep -q '^ALVO client_workspace_grants INSERT' '$SAIDA/f2/comparar.txt'"
c2="$(q nekkora_f2 "$F6AV")"
semeia6 nekkora_f2 "$TMP/seg2" "$SAIDA/f2"; rc=$?
confere "F2 repeticao recusada (codigo 3), nada muda" "[ $rc = 3 ] && [ \"\$(q nekkora_f2 \"$F6AV\")\" = '$c2' ]"

echo "== F3. falha na gravacao das credenciais"
seg seg3; : > "$TMP/seg3/frontend/f6a"; mkdir -p "$SAIDA/f3"   # 'f6a' e ARQUIVO: o diretorio do plano nao pode ser criado
semeia6 nekkora_f3 "$TMP/seg3" "$SAIDA/f3"; rc=$?
confere "F3 codigo 8 e nada no banco" "[ $rc = 8 ] && grep -q 'STOP: plano com as credenciais NAO gravado' '$SAIDA/f3/saida.txt' && [ \"\$(q nekkora_f3 \"$F6AV\")\" = 0 ]"

echo "== F4. base fora do esperado no meio da transacao -> rollback; repeticao com o MESMO plano"
seg seg4; mkdir -p "$SAIDA/f4"
q nekkora_f4 "UPDATE workspaces SET status = 2 WHERE name = 'D''Ávila — Exportação ｚ'" > /dev/null
semeia6 nekkora_f4 "$TMP/seg4" "$SAIDA/f4"; rc=$?
confere "F4 codigo 10 (NAO APLICADA), plano pendente mantido, banco sem f6av-*" \
    "[ $rc = 10 ] && [ -f '$TMP/seg4/frontend/f6a/semeadura.pendente.json' ] && [ \"\$(q nekkora_f4 \"$F6AV\")\" = 0 ] && grep -q 'workspaces da base fora do esperado' '$SAIDA/f4/saida.txt'"
X4="$(python -c "import json,sys; print(json.load(open(sys.argv[1], encoding='utf-8'))['execucao'])" "$(mixed "$TMP/seg4/frontend/f6a/semeadura.pendente.json")")"
q nekkora_f4 "UPDATE workspaces SET status = 1 WHERE name = 'D''Ávila — Exportação ｚ'" > /dev/null
n4="$(wc -l < "$SAIDA/f4/saida.txt")"
semeia6 nekkora_f4 "$TMP/seg4" "$SAIDA/f4"; rc=$?
confere "F4 repeticao: mesmo plano (execucao $X4), CONCLUIDA, 4 contas" \
    "[ $rc = 0 ] && grep -q 'PENDENTE: reaproveitando o MESMO plano (execucao $X4)' '$SAIDA/f4/saida.txt' && [ \"\$(q nekkora_f4 \"$F6AV\")\" = 4 ]"
confere "F4 repeticao com caminho limpo: psql codigo 0, CONFERE completa, sem reconciliacao por resultado ambiguo" \
    "limpo '$SAIDA/f4/saida.txt' $((n4 + 1))"

echo "== F5. resultado do psql perdido depois do COMMIT"
seg seg5; mkdir -p "$SAIDA/f5"
cat > "$TMP/psql_perde.sh" <<EOF
#!/usr/bin/env bash
f="\$(mktemp)"; cat > "\$f"
if grep -q plano_b64 "\$f"; then $(psql_de nekkora_f5) < "\$f" > /dev/null 2>&1; rm -f "\$f"; exit 1; fi
$(psql_de nekkora_f5) < "\$f"; r=\$?; rm -f "\$f"; exit \$r
EOF
semeia6 nekkora_f5 "$TMP/seg5" "$SAIDA/f5" "bash '$(mixed "$TMP/psql_perde.sh")'"; rc=$?
confere "F5 codigo 0: efetivada reconhecida pela conta do plano, promovida sem repetir" \
    "[ $rc = 0 ] && grep -q 'resultado do psql perdido ou ambiguo' '$SAIDA/f5/saida.txt' && [ -f '$TMP/seg5/frontend/f6a/semeadura.json' ] && [ \"\$(q nekkora_f5 \"$F6AV\")\" = 4 ]"

echo "== F6. conta f6av-* no banco sem plano no disco"
seg seg6; mkdir -p "$SAIDA/f6"
semeia6 nekkora_f6 "$TMP/seg6" "$SAIDA/f6" > /dev/null; rm -rf "$TMP/seg6/frontend/f6a"; : > "$SAIDA/f6/saida.txt"
semeia6 nekkora_f6 "$TMP/seg6" "$SAIDA/f6"; rc=$?
confere "F6 INCONSISTENTE (codigo 5), nada gravado" "[ $rc = 5 ] && [ ! -e '$TMP/seg6/frontend/f6a' ] && [ \"\$(q nekkora_f6 \"$F6AV\")\" = 4 ]"

echo "== F7. trava ocupada (a mesma da semeadura da F1.5)"
seg seg7; mkdir -p "$SAIDA/f7" "$TMP/seg7/frontend.trava"; echo '{"pid": 1, "host": "outro", "inicio": "x"}' > "$TMP/seg7/frontend.trava/dono.json"
semeia6 nekkora_f7 "$TMP/seg7" "$SAIDA/f7" "bash -c 'echo CONSULTOU >> \"$(mixed "$TMP/f7_psql.txt")\"; exit 1'"; rc=$?
confere "F7 recusada (codigo 11) sem consultar o banco nem gravar plano" "[ $rc = 11 ] && [ ! -e '$TMP/f7_psql.txt' ] && [ ! -e '$TMP/seg7/frontend/f6a' ]"

echo "== F8. fotos de preservacao: mudancas permitidas x proibidas"
seg seg8; mkdir -p "$SAIDA/f8"; semeia6 nekkora_f8 "$TMP/seg8" "$SAIDA/f8" > /dev/null
foto() { python "$VF" foto "$(mixed "$TMP/$1.json")" --segredos "$(mixed "$TMP/seg8")" --psql "$(psql_de nekkora_f8)" > "$SAIDA/f8/$1.txt" 2>&1; }
compara() { python "$VF" comparar "$(mixed "$TMP/$1.json")" "$(mixed "$TMP/$2.json")" --segredos "$(mixed "$TMP/seg8")" --evid "$(mixed "$SAIDA/f8/$3")" --logins-admin "${4:-1}" --stepups "${5:-1}" > "$SAIDA/f8/$3.txt" 2>&1; }
ADM="(SELECT user_id FROM users WHERE email = 'admin@demo.nekkora.test')"
ORGA="(SELECT organization_id FROM users WHERE email = 'admin@demo.nekkora.test')"
q nekkora_f8 "INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, confirmed_at, last_used_step)
              SELECT $ORGA, $ADM, 1, decode(repeat('ab', 36), 'hex'), decode(repeat('cd', 12), 'hex'), 'chave-teste', now(), 100" > /dev/null
LEI="(SELECT user_id FROM users WHERE email = 'leitor@demo.nekkora.test')"
F6M="(SELECT user_id FROM users WHERE email = 'f6av-mfa@demo.nekkora.test')"
q nekkora_f8 "INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, confirmed_at, disabled_at)
              SELECT $ORGA, $LEI, 1, decode(repeat('ef', 36), 'hex'), decode(repeat('01', 12), 'hex'), 'chave-teste-r1', now(), now()" > /dev/null
foto antes
# mudancas da navegacao: login da admin (desafio consumido + sessao, depois encerrada), TOTP do login e do step-up,
# evento de step-up; e os efeitos nos alvos (suspensao, revogacao)
q nekkora_f8 "INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, csrf_token, expires_at, consumed_at)
              SELECT $ORGA, $ADM, repeat('a', 64), 1, repeat('B', 43), now() + interval '4 minutes', now()" > /dev/null
q nekkora_f8 "INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
              SELECT $ORGA, $ADM, repeat('c', 64), now() + interval '8 hours', now(), repeat('E', 43)" > /dev/null
q nekkora_f8 "UPDATE user_sessions SET mfa_step_up_at = now() WHERE session_token_hash = repeat('c', 64)" > /dev/null   # step-up
q nekkora_f8 "UPDATE user_sessions SET revoked_at = now() WHERE session_token_hash = repeat('c', 64)" > /dev/null      # logout
q nekkora_f8 "UPDATE user_mfa_factors SET last_used_step = last_used_step + 2 WHERE user_id = $ADM AND disabled_at IS NULL" > /dev/null
q nekkora_f8 "INSERT INTO mfa_events (organization_id, user_id, event_type, correlation_id) SELECT $ORGA, $ADM, 6, gen_random_uuid()" > /dev/null
q nekkora_f8 "UPDATE users SET status = 3 WHERE email = 'f6av-suspensao@demo.nekkora.test'" > /dev/null
q nekkora_f8 "UPDATE client_workspace_grants g SET status = 4, revoked_at = now(), revoked_by_membership_id = m.membership_id FROM organization_memberships m JOIN users u USING (organization_id, user_id)
              WHERE u.email = 'admin@demo.nekkora.test' AND g.grant_id = (SELECT (c->>'grant_id')::uuid FROM jsonb_array_elements('$(python -c "import json,sys; print(json.dumps(json.load(open(sys.argv[1], encoding='utf-8'))['concessoes']))" "$(mixed "$TMP/seg8/frontend/f6a/semeadura.json")")'::jsonb) c WHERE c->>'chave' = 'f6av-c1')" > /dev/null
foto depois
compara antes depois permitido; rc=$?
confere "F8a mudancas permitidas da atora e dos alvos -> PRESERVADO (sessao, desafio, last_used_step, step-up; suspensao e revogacao como ALVO)" \
    "[ $rc = 0 ] && grep -q 'RESULTADO: PRESERVADO' '$SAIDA/f8/permitido.txt' && grep -q 'PERMITIDA (atora) user_mfa_factors UPDATE' '$SAIDA/f8/permitido.txt' \
     && grep -q 'PERMITIDA (atora) mfa_events INSERT' '$SAIDA/f8/permitido.txt' && grep -q '^ALVO users UPDATE' '$SAIDA/f8/permitido.txt' && grep -q '^ALVO client_workspace_grants UPDATE' '$SAIDA/f8/permitido.txt'"
compara antes depois contagem 2 1; rc=$?
confere "F8b contagem de logins diferente da declarada -> VIOLACAO" "[ $rc = 1 ] && grep -q 'VIOLACAO sessoes novas da atora: 1 (esperado 2)' '$SAIDA/f8/contagem.txt'"
proibe() {  # $1 nome, $2 SQL proibido, $3 trecho esperado, [$4 logins da atora]: banco PROPRIO, copia do estado permitido;
    # comparado com a foto ANTES: todas as mudancas permitidas + exatamente UMA proibida = exatamente UMA violacao
    docker exec "$CID" psql -X -q -U nekkora -d postgres -c "CREATE DATABASE \"nekkora_p_${1//-/_}\" TEMPLATE nekkora_f8" > /dev/null 2>>"$SAIDA/erros_psql.txt" || { nok "copia do banco $1"; return; }
    q "nekkora_p_${1//-/_}" "$2" > /dev/null
    python "$VF" foto "$(mixed "$TMP/p_$1.json")" --segredos "$(mixed "$TMP/seg8")" --psql "$(psql_de "nekkora_p_${1//-/_}")" > "$SAIDA/f8/p_$1.txt" 2>&1
    compara antes "p_$1" "proibido_$1" "${4:-1}" 1; local rc=$?
    confere "F8 proibida ($1) -> VIOLACAO, e so ela" "[ $rc = 1 ] && grep -q \"VIOLACAO .*$3\" '$SAIDA/f8/proibido_$1.txt' && [ \"\$(grep -c '^VIOLACAO' '$SAIDA/f8/proibido_$1.txt')\" = 1 ]"
}
proibe outra-conta "UPDATE users SET display_name = display_name || ' x' WHERE email = 'leitor@demo.nekkora.test'" "users UPDATE .*(linha de outra identidade)"
proibe atora-outro-campo "UPDATE users SET display_name = display_name || ' x' WHERE email = 'admin@demo.nekkora.test'" "users UPDATE .*da atora fora das regras"
proibe evento-de-outro-tipo "INSERT INTO mfa_events (organization_id, user_id, event_type, correlation_id) SELECT $ORGA, $ADM, 1, gen_random_uuid()" "mfa_events INSERT .*fora da regra"
proibe sessao-ativa "INSERT INTO auth_challenges (organization_id, user_id, token_hash, purpose, csrf_token, expires_at, consumed_at)
                      SELECT $ORGA, $ADM, repeat('f', 64), 1, repeat('G', 43), now() + interval '4 minutes', now();
                    INSERT INTO user_sessions (organization_id, user_id, session_token_hash, expires_at, mfa_verified_at, csrf_token)
                      SELECT $ORGA, $ADM, repeat('e', 64), now() + interval '8 hours', now(), repeat('H', 43)" "sessao nova da atora ainda ativa" 2
proibe grant-de-outro "UPDATE client_workspace_grants SET next_review_at = next_review_at + interval '1 day' WHERE status = 2 AND client_membership_id NOT IN (SELECT client_membership_id FROM client_memberships m JOIN users u USING (organization_id, user_id) WHERE u.email LIKE 'f6av-%') AND grant_id = (SELECT min(grant_id::text)::uuid FROM client_workspace_grants g2 WHERE g2.status = 2 AND g2.client_membership_id NOT IN (SELECT client_membership_id FROM client_memberships m JOIN users u USING (organization_id, user_id) WHERE u.email LIKE 'f6av-%'))" "client_workspace_grants UPDATE .*(linha de outra identidade)"
# R1: a f6av-* como ATORA de um reset de OUTRA conta nao torna a linha "do alvo"
proibe evento-outra-ator-alvo "INSERT INTO mfa_events (organization_id, user_id, actor_user_id, event_type, reason_code, correlation_id) SELECT $ORGA, $LEI, $F6M, 3, 1, gen_random_uuid()" "mfa_events INSERT .*(linha de outra identidade)"
# R1: fator de OUTRA conta reapontado para um alvo. O banco recusa (origem imutavel)...
docker exec "$CID" psql -X -q -U nekkora -d postgres -c "CREATE DATABASE nekkora_p_recusa_r1 TEMPLATE nekkora_f8" > /dev/null 2>>"$SAIDA/erros_psql.txt"
docker exec -i "$CID" psql -X -q -v ON_ERROR_STOP=1 -U nekkora -d nekkora_p_recusa_r1 > "$SAIDA/f8/recusa_reapontar.txt" 2>&1 <<SQL
UPDATE user_mfa_factors SET user_id = $F6M WHERE user_id = $LEI;
SQL
rc=$?
confere "F8 R1 reapontar o fator de outra conta para um alvo: o proprio banco RECUSA (origem do fator imutavel)" "[ $rc != 0 ] && grep -q 'origem do fator de MFA e imutavel\|desabilitado e imutavel' '$SAIDA/f8/recusa_reapontar.txt'"
# ...forcado num banco descartavel (gatilhos desligados): o verificador acusa a troca de dono
proibe fator-reapontado "SET session_replication_role = replica; UPDATE user_mfa_factors SET user_id = $F6M WHERE user_id = $LEI" "user_mfa_factors UPDATE .*trocou de dono (outra -> alvo)"
confere "F8 fotos sem valor de coluna sensivel (nenhuma linha guarda hash, token, segredo, nonce ou senha) e sem PHC" \
    "python - '$TMP'/antes.json '$TMP'/depois.json '$TMP'/p_*.json <<'PY'
import json, sys
for f in sys.argv[1:]:
    s = open(f, encoding='utf-8').read()
    assert 'argon2id' not in s, f
    d = json.loads(s)['banco']
    for tab, linhas in d['linhas'].items():
        sens = set(d['tabelas'][tab]['sens'])
        for pk, r in linhas.items():
            assert not (set(r.get('j', {})) & sens), (f, tab, pk)
PY"

echo "== F9. estado das sessoes: utilizavel x revogada x expirada (absoluta e por inatividade)"
docker exec "$CID" psql -X -q -U nekkora -d postgres -c "CREATE DATABASE nekkora_f9 TEMPLATE nekkora_f8" > /dev/null 2>>"$SAIDA/erros_psql.txt" || nok "copia do banco f9"
UM="(SELECT user_id FROM users WHERE email = 'f6av-mfa@demo.nekkora.test')"; UC="(SELECT user_id FROM users WHERE email = 'f6av-cliente@demo.nekkora.test')"
for u in "$UM" "$UC"; do
    q nekkora_f9 "INSERT INTO user_mfa_factors (organization_id, user_id, factor_type, secret_ciphertext, secret_nonce, key_id, confirmed_at)
                  SELECT $ORGA, $u, 1, decode(repeat('ab', 36), 'hex'), decode(repeat('cd', 12), 'hex'), 'chave-f9', now()" > /dev/null
done
sessao9() {  # $1 usuario, $2 caractere do hash, $3 criada, $4 ultimo uso, $5 expira
    q nekkora_f9 "INSERT INTO user_sessions (organization_id, user_id, session_token_hash, created_at, last_seen_at, expires_at, mfa_verified_at, csrf_token)
                  SELECT $ORGA, $1, repeat('$2', 64), $3, $4, $5, $3, repeat('K', 43)" > /dev/null
}
sessao9 "$UM" 1 "now() - interval '2 hours'" "now() - interval '1 minute'" "now() - interval '1 minute'"   # expirada (absoluta)
sessao9 "$UM" 2 "now() - interval '31 minutes'" "now() - interval '31 minutes'" "now() + interval '8 hours'"  # inatividade (interna 30)
sessao9 "$UM" 3 "now() - interval '29 minutes'" "now() - interval '29 minutes'" "now() + interval '8 hours'"  # utilizavel
sessao9 "$UM" 4 "now()" "now()" "now() + interval '8 hours'"
q nekkora_f9 "UPDATE user_sessions SET revoked_at = now() WHERE session_token_hash = repeat('4', 64)" > /dev/null   # revogada
sessao9 "$UC" 5 "now() - interval '16 minutes'" "now() - interval '16 minutes'" "now() + interval '8 hours'"  # inatividade (externa 15)
sessao9 "$UC" 6 "now() - interval '14 minutes'" "now() - interval '14 minutes'" "now() + interval '8 hours'"  # utilizavel
confere "F9 sessoes classificadas como a Platform (utilizavel, revogada, expirada absoluta e por inatividade 30/15 min); a inatividade NAO preenche revoked_at; estado_conta conta cada estado" \
    "python - '$(mixed "$AQUI/testes")' \"\$(q nekkora_f9 \"SELECT organization_id FROM users WHERE email = 'admin@demo.nekkora.test'\")\" \"\$(q nekkora_f9 \"SELECT $UM\")\" \"\$(q nekkora_f9 \"SELECT $UC\")\" \"$(psql_de nekkora_f9)\" > '$SAIDA/f9.txt' 2>&1 <<'PY'
import sys
sys.dont_write_bytecode = True
sys.path.insert(0, sys.argv[1])
import validar_f6a as vf
org, um, uc, psql = sys.argv[2:6]
bash = vf.sf.bash_exe(None)
sm = vf.sessoes_conta(bash, psql, org, um)
sc = vf.sessoes_conta(bash, psql, org, uc)
print('mfa', [(s['estado'], s['revogada_em'], s['inatividade_min']) for s in sm])
print('cliente', [(s['estado'], s['revogada_em'], s['inatividade_min']) for s in sc])
assert [s['estado'] for s in sm] == ['expirada_absoluta', 'expirada_inatividade', 'utilizavel', 'revogada'], sm
assert sm[1]['revogada_em'] == '-' and sm[0]['revogada_em'] == '-' and sm[3]['revogada_em'] != '-'
assert {s['inatividade_min'] for s in sm} == {30} and {s['inatividade_min'] for s in sc} == {15}
assert [s['estado'] for s in sc] == ['expirada_inatividade', 'utilizavel'], sc
em, ecl = vf.estado_conta(bash, psql, org, um), vf.estado_conta(bash, psql, org, uc)
print('estado_conta', em, ecl)
assert (em['sessoes_utilizaveis'], em['sessoes_revogadas'], em['sessoes_expiradas'], em['sessoes_total']) == ('1', '1', '2', '4'), em
assert (ecl['sessoes_utilizaveis'], ecl['sessoes_revogadas'], ecl['sessoes_expiradas'], ecl['sessoes_total']) == ('1', '0', '1', '2'), ecl
t = vf.instante(sm[2]['ultimo_uso'])
import datetime
assert vf.utilizavel_em(sm[2], t + datetime.timedelta(minutes=29)) and not vf.utilizavel_em(sm[2], t + datetime.timedelta(minutes=30))
print('F9 OK')
PY"

echo
[ "$falhas" = 0 ] && echo "RESULTADO: semeadura complementar e fotos OK" || echo "RESULTADO: $falhas falha(s)"
exit $(( falhas > 0 ))
