#!/usr/bin/env bash
# Jornada da ATIVACAO numa base populada atualizada de 021 para 022 (missao PLATFORM-API-V1-003, revisao: item 2).
# Chamado por "homologacao.sh jornada-ativacao <id>", que exporta HOMOLOG_*, compose e admin_sql. SO ensaio-* com a 022
# aplicada pelo runner (atualizar + implantar), LIBERADO, com a 016 preparada (preparar-016: operadores, organizacao
# sintetica e MFA dos ORG_ADMIN) e organizacoes criadas pelas jornadas da API ANTES da atualizacao.
#
# Atores reais, NESTA ORDEM: o DBA (admin_cluster) cria o papel do operador (padrao op_hml_atv; outro com H_ATIVADOR),
# membro SO de nekkora_ops_mfa (a 016 so cadastra papel com essa participacao exata, ops.papel_operador_invalido); o
# CADASTRAR (op_hml_cad) o cadastra pela funcao da 016; DEPOIS o DBA concede nekkora_org_activation; o titular de uma conta da organizacao sintetica emite, NA PROPRIA
# SESSAO pelo gateway, o codigo de vinculo; o operador vincula a conta e executa os atos pela CLI (job "operacao",
# certificado de cliente). Leituras de evidencia pelo admin_cluster, somente leitura, exceto a contraprova J8 (provisionamento
# direto pelo papel real, que TEM de ser recusado).
#
#   J0 classificacao das organizacoes existentes (estado x origem) e uma organizacao AGUARDANDO para cada ato
#   J1 operador do grupo: papel pelo DBA, cadastro pela 016, credencial
#   J2 vinculo da conta responsavel pela CLI (codigo emitido pelo titular); reuso do codigo recusado
#   J3 recusas sem efeito: operador fora do grupo (42501), responsavel nao vinculado, parametro invalido, revisao antes da
#      ativacao; estado e limites intactos, recusas auditadas
#   J4 ativacao: estado ATIVADA, limites explicitos, auditoria (operador, papel, certificado, responsavel, motivo, 5 revisoes
#      com a autoria do responsavel)
#   J5 repeticao: JA_ATIVADA, sem nova revisao
#   J6 concorrencia: duas ativacoes da mesma organizacao em paralelo (dois processos da CLI num job) -> exatamente uma OK
#      e uma JA_ATIVADA; limites da OK
#   J7 revisao de capacidade: limites novos, auditoria act=2; repeticao -> SEM_MUDANCA
#   J8 provisionamento direto em organizacao ativada recusado (42501), limites intactos
#   J9 indicacao para a API: org_activation_states pelo papel da Platform (ATIVADA x AGUARDANDO)
#   J10 nenhum codigo de vinculo na evidencia
set -uo pipefail
: "${HOMOLOG_ID:?}" "${HOMOLOG_SEGREDOS_HOST:?}" "${AQUI:?}"
[[ "$HOMOLOG_ID" =~ ^ensaio- ]] || { echo "ERRO: so ambiente ensaio-*" >&2; exit 1; }
H="$AQUI/homologacao.sh"
T="$HOMOLOG_SEGREDOS_HOST/teste016"
SA="$HOMOLOG_SEGREDOS_HOST/ativacao"
EV="$AQUI/saidas/$HOMOLOG_ID/jornada-ativacao-$(date -u +%Y%m%dT%H%M%SZ)"
[ -f "$T/fixture.json" ] || { echo "ERRO: 016 nao preparada ($T/fixture.json)" >&2; exit 1; }
mkdir -p "$EV" "$SA"; chmod 700 "$SA" 2>/dev/null || true
RESUMO="$EV/resumo.txt"; echo "caso|resultado|detalhe" > "$RESUMO"
falhas=0
registra() { echo "$1|$2|$3" >> "$RESUMO"; echo "[$1] $2 - $3"; [ "$2" = OK ] || falhas=$((falhas + 1)); }
export HOMOLOG_CA="$HOMOLOG_SEGREDOS_HOST/tls/ca.crt" HOMOLOG_SEGREDOS_VISTOS="$SA/vistos.txt" HOMOLOG_ESTADO="$T/estado.json"
touch "$HOMOLOG_SEGREDOS_VISTOS"
sq() { admin_sql nekkora -c "$1" | tr -d '\r'; }
fx() { python -c "import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2], {}, {'d': d}))" "$T/fixture.json" "$1" | tr -d '\r'; }
op_ferr() { bash "$H" operacao "$HOMOLOG_ID" ferramenta "$@" 2>&1 | tr -d '\r' | grep -vE '^ Container |^\s*$'; }
op_cli() { HOMOLOG_OPERADOR="$1" bash "$H" operacao "$HOMOLOG_ID" cli "${@:2}" 2>&1 | tr -d '\r' | grep -vE '^ Container |^\s*$'; }
campo() { sed -nE "s/.*(^| )$1=([^ ]+).*/\2/p" | head -1; }
cred() { op_ferr credencial "$1" | grep -q '^credencial=' || { echo "credencial de $1 nao emitida" >&2; return 1; }; }
limites() { sq "SELECT string_agg(c || ':' || coalesce((SELECT limit_mode || '/' || coalesce(limit_value::text, '-')
    FROM public.get_effective_resource_limit('$1', c)), 'ausente'), ' ' ORDER BY c) FROM generate_series(1, 5) c"; }
estado() { sq "SELECT state FROM ops_activation.organization_states WHERE organization_id = '$1'"; }
revisoes() { sq "SELECT count(*) FROM public.organization_resource_limit_revisions WHERE organization_id = '$1'"; }
eventos() { sq "SELECT count(*) FROM ops_activation.activation_events WHERE organization_id = '$1' AND act = $2 AND result = '$3' AND reason_code IS NOT DISTINCT FROM ${4:-NULL}"; }

ATV="${H_ATIVADOR:-op_hml_atv}"
[[ "$ATV" =~ ^op_hml_[a-z]{3}$ ]] || { echo "ERRO: H_ATIVADOR invalido" >&2; exit 1; }
ORG016="$(fx "d['org']")"; RESP="$(fx "d['ids']['adm2']")"; NAO_VINC="$(fx "d['ids']['adm1']")"

# ------------------------------------------------------------------------------------------------------------- J0
{ echo "versao_022: $(sq "SELECT count(*) FROM public.flyway_schema_history WHERE version = '022' AND success")"
  echo "estado|origem|organizacoes"
  sq "SELECT s.state || '|' || s.origin || '|' || count(*) FROM ops_activation.organization_states s GROUP BY s.state, s.origin ORDER BY 1"
} > "$EV/J0_classificacao.txt"
read -r ORG_A ORG_B ORG_C <<< "$(sq "SELECT string_agg(organization_id::text, ' ' ORDER BY created_at) FROM (
    SELECT s.organization_id, s.created_at FROM ops_activation.organization_states s JOIN public.organizations o USING (organization_id)
    WHERE s.state = 1 AND o.status = 1 AND s.organization_id <> '$ORG016' ORDER BY s.created_at LIMIT 3) x")"
if grep -qx 'versao_022: 1' "$EV/J0_classificacao.txt" && [ -n "${ORG_C:-}" ]; then
    registra J0 OK "022 no historico; $(grep -cE '^[12]\|[12]\|' "$EV/J0_classificacao.txt") grupos estado|origem; 3 organizacoes AGUARDANDO escolhidas (A: ativacao e revisao, B: concorrencia, C: controle)"
else
    registra J0 FALHA "sem a 022 ou sem 3 organizacoes AGUARDANDO (ver J0_classificacao.txt)"; exit 1
fi
echo "A=$ORG_A B=$ORG_B C=$ORG_C org016=$ORG016 responsavel=$RESP" > "$EV/J0_escolha.txt"
REV_A0="$(revisoes "$ORG_A")"; REV_B0="$(revisoes "$ORG_B")"

# ------------------------------------------------------------------------------------------------------------- J1
{
    admin_sql nekkora -c "CREATE ROLE $ATV LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS" 2>&1
    admin_sql nekkora -c "GRANT nekkora_ops_mfa TO $ATV" 2>&1
    cred op_hml_cad && op_ferr operador op_hml_cad "$ATV"
    admin_sql nekkora -c "GRANT nekkora_org_activation TO $ATV" 2>&1
    cred "$ATV" && echo "credencial $ATV emitida"
} > "$EV/J1_operador.txt" 2>&1
if grep -q "^operador=$ATV id=" "$EV/J1_operador.txt" && grep -q "credencial $ATV emitida" "$EV/J1_operador.txt" \
   && [ "$(sq "SELECT pg_has_role('$ATV', 'nekkora_org_activation', 'MEMBER')")" = t ]; then
    registra J1 OK "$ATV: papel pelo DBA em nekkora_ops_mfa, cadastrado pelo CADASTRAR, depois concedido nekkora_org_activation; credencial emitida"
else
    registra J1 FALHA "ver J1_operador.txt"
fi

# ------------------------------------------------------------------------------------------------------------- J2
python "$AQUI/testes/cliente.py" h_codigo_vinculo "$T/fixture.json" outro "$SA/codigo_vinculo.txt" > "$EV/J2_codigo.txt" 2>&1
COD="$(cat "$SA/codigo_vinculo.txt" 2>/dev/null)"
r1="$(op_cli "$ATV" vincular-conta "$COD")"; r2="$(op_cli "$ATV" vincular-conta "$COD")"
echo "$r1" | sed "s/$COD/<codigo>/g" > "$EV/J2_vinculo.txt"; echo "$r2" | sed "s/$COD/<codigo>/g" >> "$EV/J2_vinculo.txt"
if [ -n "$COD" ] && echo "$r1" | grep -q 'resultado=OK' && echo "$r2" | grep -q 'resultado=RECUSA motivo=PROVA_INVALIDA' \
   && [ "$(sq "SELECT count(*) FROM ops.ops_operator_platform_accounts l JOIN ops.ops_operators o USING (operator_id)
             WHERE o.db_role = '$ATV' AND l.user_id = '$RESP' AND l.removed_at IS NULL")" = 1 ]; then
    registra J2 OK "codigo emitido pelo titular na propria sessao (gateway); vincular-conta OK; reuso recusado (PROVA_INVALIDA)"
else
    registra J2 FALHA "ver J2_codigo.txt e J2_vinculo.txt"
fi

# ------------------------------------------------------------------------------------------------------------- J3
{
    cred op_hml_ver; echo "fora do grupo: $(op_cli op_hml_ver ativar-organizacao "$ORG_A" "$RESP" 1 1 1 - - fora do grupo)"
    cred "$ATV"
    echo "responsavel nao vinculado: $(op_cli "$ATV" ativar-organizacao "$ORG_A" "$NAO_VINC" 1 1 1 - - responsavel nao vinculado)"
    echo "parametro invalido: $(op_cli "$ATV" ativar-organizacao "$ORG_A" "$RESP" 0 1 1 - - workers zero)"
    echo "revisao antes da ativacao: $(op_cli "$ATV" revisar-capacidade "$ORG_A" "$RESP" 1 1 1 - - antes da ativacao)"
} > "$EV/J3_recusas.txt" 2>&1
if grep -q '^fora do grupo: .*resultado=FALHA_CONFIRMADA' "$EV/J3_recusas.txt" \
   && grep -q '^responsavel nao vinculado: .*resultado=RECUSA .*motivo=RESPONSAVEL_NAO_VINCULADO' "$EV/J3_recusas.txt" \
   && grep -q '^parametro invalido: .*resultado=RECUSA .*motivo=PARAMETRO_INVALIDO' "$EV/J3_recusas.txt" \
   && grep -q '^revisao antes da ativacao: .*resultado=RECUSA .*motivo=NAO_ATIVADA' "$EV/J3_recusas.txt" \
   && [ "$(estado "$ORG_A")" = 1 ] && [ "$(revisoes "$ORG_A")" = "$REV_A0" ] \
   && [ "$(eventos "$ORG_A" 1 RECUSA "'RESPONSAVEL_NAO_VINCULADO'")" = 1 ] && [ "$(eventos "$ORG_A" 2 RECUSA "'NAO_ATIVADA'")" = 1 ] \
   && [ "$(eventos "$ORG_A" 1 OK)" = 0 ]; then
    registra J3 OK "fora do grupo sem EXECUTE; responsavel nao vinculado, parametro invalido e revisao antes da ativacao recusados e auditados; estado e limites intactos"
else
    registra J3 FALHA "ver J3_recusas.txt"
fi

# ------------------------------------------------------------------------------------------------------------- J4
cred "$ATV"
r="$(op_cli "$ATV" ativar-organizacao "$ORG_A" "$RESP" 2 1 10 - - homologacao: ativacao da organizacao A)"; echo "$r" > "$EV/J4_ativacao.txt"
EVT="$(echo "$r" | campo evento)"
limites "$ORG_A" > "$EV/J4_limites.txt"
sq "SELECT e.act || '|' || e.result || '|' || e.session_role || '|' || (e.cert_serial IS NOT NULL) || '|' || (e.cert_issuer_dn IS NOT NULL) || '|'
    || (e.responsible_user_id = '$RESP') || '|' || e.motive || '|' || cardinality(e.limit_revision_ids) || '|' || (s.activation_event_id = e.event_id)
    || '|' || (SELECT count(*) FROM public.organization_resource_limit_revisions r WHERE r.revision_id = ANY (e.limit_revision_ids)
               AND r.provisioned_by_user_id = '$RESP')
    FROM ops_activation.activation_events e JOIN ops_activation.organization_states s USING (organization_id) WHERE e.event_id = '$EVT'" > "$EV/J4_auditoria.txt" 2>&1
if echo "$r" | grep -q 'resultado=OK' && [ "$(estado "$ORG_A")" = 2 ] && [ "$(cat "$EV/J4_limites.txt")" = "1:1/2 2:1/1 3:1/10 4:2/- 5:2/-" ] \
   && [ "$(cat "$EV/J4_auditoria.txt")" = "1|OK|$ATV|true|true|true|homologacao: ativacao da organizacao A|5|true|5" ] \
   && [ "$(revisoes "$ORG_A")" = "$((REV_A0 + 5))" ]; then
    registra J4 OK "ATIVADA; limites 1:1/2 2:1/1 3:1/10 4:2/- 5:2/-; auditoria com operador, papel da sessao, certificado, responsavel, motivo e as 5 revisoes com a autoria do responsavel"
else
    registra J4 FALHA "ver J4_ativacao.txt, J4_limites.txt e J4_auditoria.txt"
fi

# ------------------------------------------------------------------------------------------------------------- J5
r="$(op_cli "$ATV" ativar-organizacao "$ORG_A" "$RESP" 9 9 99 - - repeticao)"; echo "$r" > "$EV/J5_repeticao.txt"
if echo "$r" | grep -q 'resultado=RECUSA .*motivo=JA_ATIVADA' && [ "$(limites "$ORG_A")" = "1:1/2 2:1/1 3:1/10 4:2/- 5:2/-" ] \
   && [ "$(revisoes "$ORG_A")" = "$((REV_A0 + 5))" ] && [ "$(eventos "$ORG_A" 1 OK)" = 1 ]; then
    registra J5 OK "repeticao recusada com JA_ATIVADA; nenhuma revisao nova"
else
    registra J5 FALHA "ver J5_repeticao.txt"
fi

# ------------------------------------------------------------------------------------------------------------- J6
cred "$ATV"
# Dois processos da CLI em paralelo DENTRO de um job "operacao": o job tem IP fixo (regra do pg_hba para op_*), entao dois
# jobs nao sobem ao mesmo tempo ("Address already in use"); as duas conexoes saem do mesmo container, com o mesmo certificado.
MSYS_NO_PATHCONV=1 compose --profile operacao run --rm -T -e NEKKORA_OPS_PAPEL="$ATV" -e NEKKORA_OPS_CREDENCIAL="/operadores/$ATV.p12" \
    --entrypoint sh operacao -ec 'export NEKKORA_OPS_CREDENCIAL_SENHA="$(cat "/operadores/$NEKKORA_OPS_PAPEL.senha")"
        c() { java -cp "/app/lib/*:/app/classes" com.nekkora.ops.cli.OpsCli "$@"; }
        c ativar-organizacao "$1" "$2" 3 2 30 - - concorrente X > /tmp/x.txt 2>&1 &
        c ativar-organizacao "$1" "$2" 4 3 40 - - concorrente Y > /tmp/y.txt 2>&1 &
        wait; echo "X: $(cat /tmp/x.txt)"; echo "Y: $(cat /tmp/y.txt)"' -- "$ORG_B" "$RESP" 2>&1 | tr -d '\r' | grep -E '^[XY]: ' > "$SA/j6.txt"
grep '^X: ' "$SA/j6.txt" > "$SA/j6_x.txt"; grep '^Y: ' "$SA/j6.txt" > "$SA/j6_y.txt"
cat "$SA/j6_x.txt" "$SA/j6_y.txt" > "$EV/J6_concorrencia.txt"
nok="$(grep -c 'resultado=OK' "$EV/J6_concorrencia.txt")"; nja="$(grep -c 'resultado=RECUSA .*motivo=JA_ATIVADA' "$EV/J6_concorrencia.txt")"
LB="$(limites "$ORG_B")"; echo "limites=$LB" >> "$EV/J6_concorrencia.txt"
sq "SELECT string_agg(to_char(created_at, 'HH24:MI:SS.MS') || ' ' || result || ' ' || coalesce(reason_code, '-') || ' ' || motive, '; ' ORDER BY created_at)
    FROM ops_activation.activation_events WHERE organization_id = '$ORG_B'" >> "$EV/J6_concorrencia.txt"
venc="$(grep 'resultado=OK' "$SA/j6_x.txt" > /dev/null && echo X || echo Y)"
esp="$([ "$venc" = X ] && echo "1:1/3 2:1/2 3:1/30 4:2/- 5:2/-" || echo "1:1/4 2:1/3 3:1/40 4:2/- 5:2/-")"
if [ "$nok" = 1 ] && [ "$nja" = 1 ] && [ "$LB" = "$esp" ] && [ "$(eventos "$ORG_B" 1 OK)" = 1 ] && [ "$(revisoes "$ORG_B")" = "$((REV_B0 + 5))" ]; then
    registra J6 OK "duas ativacoes em paralelo (dois processos da CLI no mesmo job): uma OK ($venc) e uma JA_ATIVADA; limites e 5 revisoes so da vencedora (a espera pelo lock com intercalamento controlado e provada no OrgActivationIT)"
else
    registra J6 FALHA "ver J6_concorrencia.txt (ok=$nok ja=$nja)"
fi

# ------------------------------------------------------------------------------------------------------------- J7
cred "$ATV"
r1="$(op_cli "$ATV" revisar-capacidade "$ORG_A" "$RESP" 4 2 40 1073741824 - homologacao: aditivo da organizacao A)"
r2="$(op_cli "$ATV" revisar-capacidade "$ORG_A" "$RESP" 4 2 40 1073741824 - repeticao do aditivo)"
printf '%s\n%s\n' "$r1" "$r2" > "$EV/J7_revisao.txt"
EVR="$(echo "$r1" | campo evento)"
if echo "$r1" | grep -q 'resultado=OK' && echo "$r2" | grep -q 'resultado=RECUSA .*motivo=SEM_MUDANCA' \
   && [ "$(limites "$ORG_A")" = "1:1/4 2:1/2 3:1/40 4:1/1073741824 5:2/-" ] && [ "$(revisoes "$ORG_A")" = "$((REV_A0 + 10))" ] \
   && [ "$(sq "SELECT act || '|' || result || '|' || session_role || '|' || (responsible_user_id = '$RESP') || '|' || cardinality(limit_revision_ids) FROM ops_activation.activation_events WHERE event_id = '$EVR'")" = "2|OK|$ATV|true|5" ] \
   && [ "$(estado "$ORG_A")" = 2 ]; then
    registra J7 OK "revisao auditada (act=2) com os limites novos; repeticao recusada com SEM_MUDANCA"
else
    registra J7 FALHA "ver J7_revisao.txt"
fi

# ------------------------------------------------------------------------------------------------------------- J8
o="$(printf "SET ROLE nekkora_provisioning;\nSELECT provision_resource_limit('%s', 2, 1::smallint, 99, '%s');\n" "$ORG_A" "$RESP" | admin_sql nekkora 2>&1)"; rc=$?
echo "rc=$rc $o" | tr -d '\r' | head -3 > "$EV/J8_provisionamento_direto.txt"
if [ "$rc" != 0 ] && echo "$o" | grep -q 'organizacao ativada: limite (recurso 2) so pela revisao autorizada' \
   && [ "$(limites "$ORG_A")" = "1:1/4 2:1/2 3:1/40 4:1/1073741824 5:2/-" ]; then
    registra J8 OK "provisionamento direto pelo papel real recusado na organizacao ativada; limites intactos"
else
    registra J8 FALHA "ver J8_provisionamento_direto.txt"
fi

# ------------------------------------------------------------------------------------------------------------- J9
o="$(printf "SET ROLE nekkora_platform;\nSELECT organization_id || '|' || activation_state FROM public.org_activation_states(ARRAY['%s', '%s', '%s']::uuid[]);\n" "$ORG_A" "$ORG_B" "$ORG_C" | admin_sql nekkora 2>&1 | tr -d '\r' | grep '|' | sort)"
echo "$o" > "$EV/J9_estados_pela_platform.txt"
if [ "$o" = "$(printf '%s|2\n%s|2\n%s|1\n' "$ORG_A" "$ORG_B" "$ORG_C" | sort)" ]; then
    registra J9 OK "Platform le pela funcao da API: A e B ATIVADA, C AGUARDANDO_ATIVACAO"
else
    registra J9 FALHA "ver J9_estados_pela_platform.txt"
fi

# ------------------------------------------------------------------------------------------------------------- J10
sq "SELECT organization_id || '|' || act || '|' || result || '|' || coalesce(reason_code, '-') || '|' || session_role || '|' || coalesce(cardinality(limit_revision_ids), 0)
    FROM ops_activation.activation_events WHERE organization_id IN ('$ORG_A', '$ORG_B', '$ORG_C') ORDER BY created_at" > "$EV/J10_auditoria.txt"
if [ -n "$COD" ] && [ "$(grep -c . "$EV/J10_auditoria.txt")" -ge 9 ] && ! grep -rqF -- "$COD" "$EV"; then
    registra J10 OK "auditoria das 3 organizacoes exportada ($(wc -l < "$EV/J10_auditoria.txt" | tr -d ' ') eventos); codigo de vinculo ausente da evidencia"
else
    registra J10 FALHA "codigo de vinculo na evidencia ou nao emitido"
fi
echo "RESULTADO: $(grep -c '|OK|' "$RESUMO") OK, $falhas FALHA (evidencia: $EV)"
[ "$falhas" = 0 ]
