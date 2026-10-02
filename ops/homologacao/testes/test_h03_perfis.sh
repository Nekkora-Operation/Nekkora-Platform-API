#!/usr/bin/env bash
# Testes SEM Docker de testes/h03_perfis.sh (missao H03 pos-016): deteccao do modelo com falha fechada, perfis 015/016 e
# montagem dos invariantes (inclusive a troca EXATA da regra de politica da 014 no perfil 016). Usa copias temporarias de
# db/migrations; nao toca banco nem ambiente. Uso (raiz do repositorio): bash ops/homologacao/testes/test_h03_perfis.sh
set -uo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"; AQUI="$REPO/ops/homologacao"
. "$AQUI/testes/h03_perfis.sh"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
ok=0; falhas=0
c() { if eval "$2" > /dev/null 2>&1; then echo "OK    $1"; ok=$((ok + 1)); else echo "FALHA $1"; falhas=$((falhas + 1)); fi; }
copia() {   # $1 nome, $2 ultima versao
    mkdir -p "$T/$1"; for f in "$REPO"/db/migrations/0*.sql; do v="$(basename "$f" | cut -c1-3)"; [ "$((10#$v))" -le "$((10#$2))" ] && cp "$f" "$T/$1/"; done
}
copia m15 015; copia m16 016

# ---- modelo
c "modelo 001-015 com HOMOLOG_ATE=015 -> 015" '[ "$(h03_modelo "$T/m15" 015)" = 015 ]'
c "modelo 001-016 com HOMOLOG_ATE=016 -> 016" '[ "$(h03_modelo "$T/m16" 016)" = 016 ]'
c "copia 001-016 com HOMOLOG_ATE=015 -> recusa" '! h03_modelo "$T/m16" 015'
c "copia 001-015 com HOMOLOG_ATE=016 -> recusa" '! h03_modelo "$T/m15" 016'
c "HOMOLOG_ATE vazio -> recusa" '! h03_modelo "$T/m16" ""'
cp -r "$T/m16" "$T/lacuna"; rm "$T/lacuna"/014_*.sql
c "copia com lacuna (sem 014) -> recusa" '! h03_modelo "$T/lacuna" 016'
cp -r "$T/m16" "$T/m17"; echo "SELECT 1;" > "$T/m17/017_futura.sql"
c "modelo 017 sem perfil declarado -> recusa" '! h03_modelo "$T/m17" 017'
cp -r "$T/m16" "$T/dup"; cp "$T/dup/016_recuperacao_emergencial_mfa.sql" "$T/dup/016_outra.sql"
c "versao repetida -> recusa" '! h03_modelo "$T/dup" 016'
cp -r "$T/m16" "$T/estranho"; echo x > "$T/estranho/LEIA-ME.txt"
c "arquivo estranho na copia -> recusa" '! h03_modelo "$T/estranho" 016'
mkdir -p "$T/vazia"
c "copia vazia -> recusa" '! h03_modelo "$T/vazia" 016'
c "copia inexistente -> recusa" '! h03_modelo "$T/nao_existe" 016'

# ---- perfis
c "perfil 015: papeis com senha do desenho 015 e 4 blocos" 'h03_perfil 015 && [ "$H03_LOGIN_COM_SENHA" = admin_cluster,backup_nekkora,migrador_nekkora,nekkora_platform,nekkora_portal ] && [ "$H03_BLOCOS" = 4 ]'
c "perfil 016: inclui nekkora_ops_ca e nekkora_ops_intake e 5 blocos" 'h03_perfil 016 && [ "$H03_LOGIN_COM_SENHA" = admin_cluster,backup_nekkora,migrador_nekkora,nekkora_ops_ca,nekkora_ops_intake,nekkora_platform,nekkora_portal ] && [ "$H03_BLOCOS" = 5 ]'
c "perfil desconhecido -> recusa" '! h03_perfil 017'
c "invariantes sem perfil carregado -> recusa" 'H03_PERFIL="" ; ! h03_invariantes_sql "$T/m16"'

# ---- invariantes montados
h03_perfil 015; h03_invariantes_sql "$T/m15" > "$T/inv15.sql"; r15=$?
c "perfil 015: monta 4 blocos DO com a regra ORIGINAL da 014 (uma politica)" '[ $r15 = 0 ] && [ "$(grep -c "^DO \\\$\\\$" "$T/inv15.sql")" = 4 ] && grep -qF -- "$H03_ANCORA_POL014" "$T/inv15.sql" && ! grep -qF -- "$H03_TROCA_POL014" "$T/inv15.sql"'
c "perfil 015: comeca em BEGIN e termina em ROLLBACK" '[ "$(head -1 "$T/inv15.sql")" = "BEGIN;" ] && [ "$(tail -1 "$T/inv15.sql")" = "ROLLBACK;" ]'
h03_perfil 016; h03_invariantes_sql "$T/m16" > "$T/inv16.sql"; r16=$?
c "perfil 016: monta 5 blocos DO, inclusive a sec. 19 da 016" '[ $r16 = 0 ] && [ "$(grep -c "^DO \\\$\\\$" "$T/inv16.sql")" = 5 ] && grep -q "v_identidade_impl CONSTANT" "$T/inv16.sql"'
c "perfil 016: regra da 014 trocada pelo CONJUNTO EXATO (sem a contagem <> 1)" '! grep -qF -- "$H03_ANCORA_POL014" "$T/inv16.sql" && [ "$(grep -cF -- "$H03_TROCA_POL014" "$T/inv16.sql")" = 1 ]'
c "perfil 016: o restante da sec. 8 da 014 e identico ao original" 'diff <(grep -vF -- "$H03_ANCORA_POL014" "$T/inv15.sql" | sed -n "/tabelas novas: RLS ENABLE/,\$p" | head -30) <(grep -vF -- "$H03_TROCA_POL014" "$T/inv16.sql" | sed -n "/tabelas novas: RLS ENABLE/,\$p" | head -30) > /dev/null'
cp -r "$T/m16" "$T/sem_ancora"; sed -i 's/p.tablename = c.relname) <> 1);/p.tablename = c.relname) <> 2);/' "$T/sem_ancora"/014_*.sql
c "perfil 016 com a regra da 014 alterada (ancora ausente) -> recusa, nunca roda sem a troca" 'h03_perfil 016; ! h03_invariantes_sql "$T/sem_ancora"'
cp -r "$T/m16" "$T/ancora2"; python - "$T/ancora2" <<'PY' > /dev/null
import glob, sys
p = glob.glob(sys.argv[1] + '/014_*.sql')[0]; s = open(p, encoding='utf-8').read()
a = "OR (SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) <> 1);"
open(p, 'w', encoding='utf-8', newline='\n').write(s.replace(a, a + '\n' + a, 1))
PY
c "perfil 016 com a regra da 014 duplicada -> recusa" 'h03_perfil 016; ! h03_invariantes_sql "$T/ancora2"'
cp -r "$T/m16" "$T/sem19"; sed -i 's/^-- === 19\. /-- === XIX. /' "$T/sem19"/016_*.sql
c "perfil 016 sem a sec. 19 da 016 -> recusa (o H03 nao pula a 016)" 'h03_perfil 016; ! h03_invariantes_sql "$T/sem19"'
c "perfil 016 sobre copia 001-015 -> recusa (016 ausente)" 'h03_perfil 016; ! h03_invariantes_sql "$T/m15"'
c "transformacao desconhecida -> recusa" 'h03_perfil 016; H03_INVARIANTES="014_mfa_sessoes.sql|^-- === 8\\. |outra"; H03_BLOCOS=1; ! h03_invariantes_sql "$T/m16"'

echo "RESULTADO: $ok OK, $falhas FALHA"
[ "$falhas" = 0 ]
