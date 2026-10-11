"""Contraprovas da F6a-V-T: cada MUTACAO de uma defesa das ferramentas da validacao administrativa tem de fazer o seu
teste-alvo REPROVAR; o arquivo volta ao original com sha256 conferido. Mesmo mecanismo (e mesmo relatorio) de
testes/contraprovas_frontend.py, com a lista propria (K45 em diante). R1: K57 reescrita para o codigo do dono da linha;
K71-K80 para as correcoes da R1 (identidade dona da linha e fator MFA original). K82-K88: correcao da F6a-V-E (STOP
da V1): index servido comparado pelos bytes exatos e retomada do aplicado (lib/frontend_atualizar.sh retomar). K89:
correcao da V2 (consulta posterior ao COMMIT com w.status qualificado; caminho valido limpo no teste de banco).
K90-K99: correcao da V4.4 (sessoes utilizavel x revogada x expirada; revogacao ligada a acao; sessoes renovadas).
K100-K102: ajuste da V4.4 (prazos da sessao antiga conferidos tambem no instante da consulta, com margem).
K103-K106: STOP da R8 (rotulos de recriacao do Compose aceitos so no gateway identificado e com os valores exatos).
K107-K110: identificacao do par (ids hexadecimais com 12 a 64 caracteres, distintos), na funcao e na CLI.

Uso: python testes/contraprovas_f6a.py <dir_de_saida> [ids...]
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import contraprovas_frontend as cf  # noqa: E402

FA = 'bash testes/test_frontend_atualizar.sh'
BANCO6 = 'bash testes/test_semear_f6a_banco.sh "$SAIDA_TESTE"'
UNIT6 = 'python testes/test_validar_f6a.py -v'
VC = 'bash homologacao.sh validar-config'

MUT = [
    # --- atualizacao do frontend (lib/frontend_atualizar.sh)
    ('K45-aplicar-sem-reconferir-a-referencia', 'lib/frontend_atualizar.sh',
     [("""    : > "$FA_D/aplicar.txt"
    fa_confere_referencia
""", """    : > "$FA_D/aplicar.txt"
""")], FA, 'FALHA R-env'),
    ('K46-coleta-que-falha-libera-a-recriacao', 'lib/frontend_atualizar.sh',
     [("""        || fa_falha "pre-condicao NAO conferida: falha na coleta""", """        || true "pre-condicao NAO conferida: falha na coleta""")], FA, 'FALHA A-logs_falha'),
    ('K47-trafego-recente-ignorado', 'lib/frontend_atualizar.sh',
     [("""    [ "$n" = 0 ] || fa_falha "pre-condicao NAO atendida""", """    true || fa_falha "pre-condicao NAO atendida""")], FA, 'FALHA A-recente'),
    ('K48-build-diferente-do-aprovado-aceito', 'lib/frontend_atualizar.sh',
     [("""        || fa_falha "build do par DIFERENTE do registro aprovado""", """        || true "build do par DIFERENTE do registro aprovado""")], FA, 'FALHA P02'),
    ('K49-pasta-nova-sobrescrita', 'lib/frontend_atualizar.sh',
     [("""    [ ! -e "$novo" ] || fa_falha "a pasta nova ja existe ($novo): imutavel, nao se sobrescreve"
""", "")], FA, 'FALHA P03'),
    ('K50-outros-containers-nao-conferidos', 'lib/frontend_atualizar.sh',
     [("""        [ "$s $linha" = "$(grep "^$s " "$FA_REF.containers")" ] || { fa_log "  $s mudou: $linha"; return 1; }""",
       """        true""")], FA, 'FALHA F-outro_reinicia'),
    ('K51-index-sem-no-store-aceito', 'lib/frontend_atualizar.sh',
     [("""[ "$cc" = no-store ] &&""", """true &&""")], FA, 'FALHA F-sem_nostore'),
    ('K52-reverter-sem-conferir-o-aplicado', 'lib/frontend_atualizar.sh',
     [("""    [ "$(fa_sha "$FA_ENV")" = "$(awk '$1=="aplicado"{print $2}' "$FA_APL")" ] || fa_falha "ambiente.env nao e o aplicado; nada escrito"
""", "")], FA, 'FALHA V04'),
    # --- semeadura complementar (banco real)
    ('K53-semeadura-f6a-sem-trava', 'lib/semear_f6a.py',
     [("""    if not trava.adquire():""", """    if False:""")], BANCO6, 'FALHA F7'),
    ('K54-semeadura-f6a-sem-exigir-a-base', 'lib/semear_f6a.py',
     [("""    if est_base != 'CONCLUIDA':""", """    if False:""")], BANCO6, 'FALHA F1'),
    # --- verificador: regras da atora, Docker, fases, intencoes, ritmo
    ('K55-atora-qualquer-campo', 'testes/validar_f6a.py',
     [("""                if mudou and mudou <= r['campos'] and""", """                if mudou and""")], UNIT6, 'test_fator_da_atora_outro_campo_ou_segredo'),
    ('K56-last-used-step-para-menos-aceito', 'testes/validar_f6a.py',
     [("""(not c or (rd['j'].get(c) or -1) > (ra['j'].get(c) or -1))""", """True""")], UNIT6, 'test_last_used_step_so_para_mais'),
    ('K57-outra-identidade-vira-alvo', 'testes/validar_f6a.py',
     [("""            if cat == 'outra':
                viol.append(f'{tab} {op} {pk} (linha de outra identidade)')
                continue""", """            if cat == 'outra':
                alvo.append(f'{tab} {op} {pk}')
                continue""")], UNIT6, 'test_delete_da_atora_e_insert_de_outra_identidade'),
    ('K58-gateway-novo-sem-comparar-com-o-anterior', 'testes/validar_f6a.py',
     [("""    if dif - permitido:""", """    if False:""")], UNIT6, 'test_gateway_novo_com_imagem_rede_porta_ou_rotulo_diferente'),
    ('K59-spa-novo-nao-conferido', 'testes/validar_f6a.py',
     [("""            ok = (w.rsplit('/', 1)[0] == o.rsplit('/', 1)[0]""", """            ok = True or (w.rsplit('/', 1)[0] == o.rsplit('/', 1)[0]""")],
     UNIT6, 'test_spa_errado_gravavel_ou_nginx_mudado'),
    ('K60-outro-container-alterado-ignorado', 'testes/validar_f6a.py',
     [("""        if ca[k] != cd[k]:
            dif = sorted(""", """        if False:
            dif = sorted(""")], UNIT6, 'test_outro_container_volume_rede_ou_imagem_alterado'),
    ('K61-anti-replay-aceita-passo-nao-aceito', 'testes/validar_f6a.py',
     [("""    if p < 0 or p > L:""", """    if p < 0:""")], UNIT6, 'test_passo_ainda_nao_aceito_recusa'),
    ('K62-mfa-sem-conferir-motivo-e-ator', 'testes/validar_f6a.py',
     [("""'eventos_reset': '1', 'reset_motivos': motivo}""", """'eventos_reset': '1'}""")], UNIT6, 'test_mfa_motivo_e_ator'),
    ('K63-revogacao-sem-o-controle', 'testes/validar_f6a.py',
     [("""        rel.confere('G.02', 'f6av-c2 (controle): continua ATIVA e nao revogada', e2 == {'status': '2', 'revogada': 'false', 'revogada_por': '-'}, e2)""",
       """        rel.confere('G.02', 'f6av-c2 (controle): continua ATIVA e nao revogada', True, e2)""")], UNIT6, 'test_revogacao_controle_intacto'),
    ('K64-intencao-de-outro-tipo-ignorada', 'testes/validar_f6a.py',
     [("""    extras = {k: v for k, v in cont.items() if a.esperado is not None and k not in dict(x.split('=') for x in a.esperado)}""",
       """    extras = {}""")], UNIT6, 'test_tipo_a_mais_ou_contagem_diferente'),
    ('K65-sem-ritmo-entre-logins', 'testes/validar_f6a.py',
     [("""            if falta > 0:""", """            if False:""")], UNIT6, 'test_ritmo_entre_logins'),
    # --- reuso deterministico do TOTP (lib/credencial_navegacao.py)
    ('K66-reuso-depois-de-nova-entrega', 'lib/credencial_navegacao.py',
     [("""        if passo != m.get('ultimo_passo'):
            raise Recusa(""", """        if False:
            raise Recusa(""")], UNIT6, 'test_entrega_depois_da_conferencia_recusa'),
    ('K67-reuso-nao-consumido', 'lib/credencial_navegacao.py',
     [("""            os.remove(aut)                     # USO UNICO: consumida ANTES da entrega""", """            pass""")], UNIT6, 'test_uso_unico'),
    ('K68-reuso-de-passo-nao-aceito', 'lib/credencial_navegacao.py',
     [("""or passo > aceito:""", """or False:""")], UNIT6, 'test_passo_maior_que_o_aceito_recusa'),
    # --- runner
    ('K69-runner-validar-sem-as-guardas', 'homologacao.sh',
     [("""                selecao_executa "$J" "${VF[@]}" "$sub" "$@" --segredos""", """                "${VF[@]}" "$sub" "$@" --segredos""")], VC,
     'FALHA validar-f6a preparar/conferir SO por selecao_executa'),
    ('K70-runner-f6a-sem-exigir-liberacao', 'homologacao.sh',
     [("""semear-f6a|validar-f6a)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] && [ -n "${HOMOLOG_FRONTEND_DIST:-}" ] \\
        || falha "$ACAO so em ambiente ensaio-* criado com a aplicacao web (HOMOLOG_FRONTEND_WEB)"
    confere_liberacao "$TRAVA" > /dev/null || falha "ambiente BLOQUEADO (ver homologacao.sh estado $HOMOLOG_ID)\"""",
       """semear-f6a|validar-f6a)
    carrega_ambiente "${2:?id}"
    prepara_funcoes
    [[ "$HOMOLOG_ID" =~ ^ensaio- ]] && [ -n "${HOMOLOG_FRONTEND_DIST:-}" ] \\
        || falha "$ACAO so em ambiente ensaio-* criado com a aplicacao web (HOMOLOG_FRONTEND_WEB)\"""")], VC,
     'FALHA bloco da F1.5 com o rotulo aprovado'),
    # --- R1: identidade DONA da linha (testes/validar_f6a.py)
    ('K71-alvo-por-qualquer-valor-da-linha', 'testes/validar_f6a.py',
     [("""    if all(v in ids_alvo for v in vals):
        return 'alvo'""", """    if any(v in ids_alvo for v in j.values()):
        return 'alvo'""")], UNIT6, 'test_r1_evento_de_outra_conta_com_alvo_so_como_ator'),
    ('K72-atora-por-qualquer-valor-da-linha', 'testes/validar_f6a.py',
     [("""    if all(v in ids_admin for v in vals):
        return 'atora'""", """    if any(v in ids_admin for v in j.values()):
        return 'atora'""")], UNIT6, 'test_r1_outra_conta_com_a_atora_so_como_responsavel'),
    ('K73-update-julgado-so-pelo-estado-novo', 'testes/validar_f6a.py',
     [("""            if op == 'UPDATE' and ca != cd:""", """            if False:"""),
      ("""            cat = cd if op == 'INSERT' else ca""", """            cat = cd if op != 'DELETE' else ca""")],
     UNIT6, 'test_r1_fator_de_outra_conta_reapontado_para_o_alvo'),
    ('K74-dono-misto-aceito', 'testes/validar_f6a.py',
     [("""    if all(v in ids_alvo for v in vals):
        return 'alvo'""", """    if vals[0] in ids_alvo:
        return 'alvo'""")], UNIT6, 'test_r1_dono_misto_e_protegido'),
    # --- R1: fator MFA ORIGINAL
    ('K75-inicial-sem-registrar-o-original', 'testes/validar_f6a.py',
     [("""        cofre.grava_estado({**cofre.estado(), 'fator_original': originais})""", """        pass""")],
     UNIT6, 'test_r1_inicial_registra_o_fator_original'),
    ('K76-stepup-negado-sem-conferir-o-mesmo-fator', 'testes/validar_f6a.py',
     [("""'eventos_reset': '0',
                          'fator_ativo_id': orig}, e)""", """'eventos_reset': '0'}, e)""")],
     UNIT6, 'test_r1_stepup_negado_exige_o_mesmo_fator'),
    ('K77-mfa-sem-original-registrado-segue', 'testes/validar_f6a.py',
     [("""        k = 'f6av-mfa'
        orig = cofre.estado().get('fator_original', {}).get(k)
        if not orig:""", """        k = 'f6av-mfa'
        orig = cofre.estado().get('fator_original', {}).get(k)
        if False:""")], UNIT6, 'test_r1_mfa_exige_o_original_registrado_antes'),
    ('K78-original-nao-conferido-depois-do-reset', 'testes/validar_f6a.py',
     [("""        rel.confere('M.01b', f'{k}: o fator ORIGINAL ({orig}) existe e esta DESATIVADO', fo == {""",
       """        rel.confere('M.01b', f'{k}: o fator ORIGINAL ({orig}) existe e esta DESATIVADO', True or fo == {""")],
     UNIT6, 'test_r1_mfa_recusa_original_nao_desativado_pelo_reset'),
    ('K79-fator-novo-comparado-so-com-traco', 'testes/validar_f6a.py',
     [("""e2['fator_ativo_id'] not in ('-', orig)""", """e2['fator_ativo_id'] not in ('-',)""")],
     UNIT6, 'test_r1_mfa_recusa_reutilizar_o_id_original'),
    ('K80-original-nao-conferido-depois-do-novo-cadastro', 'testes/validar_f6a.py',
     [("""        rel.confere('M.09', f'{k}: o fator ORIGINAL continua DESATIVADO depois do novo cadastro', fo == {""",
       """        rel.confere('M.09', f'{k}: o fator ORIGINAL continua DESATIVADO depois do novo cadastro', True or fo == {""")],
     UNIT6, 'test_r1_mfa_recusa_original_reativado_depois_do_novo_cadastro'),
    # --- R1 no banco REAL: a mesma falha da K73, acusada pela troca de dono forcada
    ('K81-banco-update-julgado-so-pelo-estado-novo', 'testes/validar_f6a.py',
     [("""            if op == 'UPDATE' and ca != cd:""", """            if False:"""),
      ("""            cat = cd if op == 'INSERT' else ca""", """            cat = cd if op != 'DELETE' else ca""")],
     BANCO6, 'FALHA F8 proibida (fator-reapontado)'),
    # --- correcao da F6a-V-E: index pelos bytes exatos e retomada (lib/frontend_atualizar.sh)
    ('K82-index-comparado-sem-os-cr', 'lib/frontend_atualizar.sh',
     [('''[ "$sh" = "$(sha256sum < "$dist/index.html" | cut -c1-64)" ]''', '''[ "$sh" = "$(fa_sha "$dist/index.html")" ]''')],
     FA, 'FALHA C01 aplicar CRLF'),
    ('K83-retomar-sem-os-assets-antigos', 'lib/frontend_atualizar.sh',
     [('''    fa_confere_servido "$pn" "$pa" || fa_falha "build novo nao servido como esperado"
    fa_log "RETOMADA CONFERIDA''', '''    fa_confere_servido "$pn" "" || fa_falha "build novo nao servido como esperado"
    fa_log "RETOMADA CONFERIDA''')], FA, 'FALHA T08 ('),
    ('K84-retomar-sem-exigir-o-aplicado', 'lib/frontend_atualizar.sh',
     [('''    [ "$(fa_sha "$FA_ENV")" = "$(awk '$1=="aplicado"{print $2}' "$FA_APL")" ] || fa_falha "ambiente.env nao e o aplicado"
    local k v atual pa pn antigo novo id''', '''    local k v atual pa pn antigo novo id''')], FA, 'FALHA T04 ('),
    ('K85-retomar-sem-a-pre-condicao-de-trafego', 'lib/frontend_atualizar.sh',
     [('''    fa_sem_trafego
    fa_log "conferencias do aplicado''', '''    fa_log "conferencias do aplicado''')], FA, 'FALHA T07 ('),
    ('K86-retomar-sem-conferir-o-gateway-recriado', 'lib/frontend_atualizar.sh',
     [('''    [ "${id:0:12}" = "$novo" ] || fa_falha "o gateway nao e o recriado pelo aplicar''',
       '''    true || fa_falha "o gateway nao e o recriado pelo aplicar''')], FA, 'FALHA T06 ('),
    ('K87-retomar-sem-reconferir-a-referencia', 'lib/frontend_atualizar.sh',
     [('''        [ "$atual" = "$v" ] || fa_falha "referencia divergente: $k mudou depois do preparo (preparo $v, agora $atual)"
    done < "$FA_REF"
    fa_log "referencia reconferida: builds''', '''        true
    done < "$FA_REF"
    fa_log "referencia reconferida: builds''')], FA, 'FALHA T05 ('),
    ('K88-retomar-que-recria-o-gateway', 'lib/frontend_atualizar.sh',
     [('''    fa_sem_trafego
    fa_log "conferencias do aplicado''', '''    fa_sem_trafego
    fa_recria
    fa_log "conferencias do aplicado''')], FA, 'FALHA T11 ('),
    # --- correcao da V2 da F6a-V-E: consulta posterior ao COMMIT (sql/semear_f6a.sql), banco real
    ('K89-conferencia-pos-commit-ambigua', 'sql/semear_f6a.sql',
     [('''string_agg(w.status::text, ',' ORDER BY w.created_at, w.grant_id)''', '''string_agg(status::text, ',' ORDER BY w.created_at, w.grant_id)''')],
     BANCO6, 'FALHA F2 caminho limpo'),
    # --- correcao da V4.4: sessoes e ligacao acao -> revogacao (testes/validar_f6a.py)
    ('K90-inatividade-ignorada-no-estado-da-sessao', 'testes/validar_f6a.py',
     [('''            WHEN s.last_seen_at <= now() - CASE''', '''            WHEN false AND s.last_seen_at <= now() - CASE''')],
     BANCO6, 'FALHA F9 sessoes classificadas'),
    ('K91-revogacao-aceita-sem-ligacao-a-acao', 'testes/validar_f6a.py',
     [('''    return t - datetime.timedelta(seconds=LIGACAO_ANTES) <= r <= t + datetime.timedelta(seconds=LIGACAO_DEPOIS)''',
       '''    return True''')], UNIT6, 'test_suspensao_revogacao_fora_do_instante_da_acao'),
    ('K92-sessao-expirada-tratada-como-revogada', 'testes/validar_f6a.py',
     [('''for s in antes if not ligada(s['revogada_em'], t_acao)]''',
       '''for s in antes if not (ligada(s['revogada_em'], t_acao) or s['estado'].startswith('expirada'))]''')],
     UNIT6, 'test_suspensao_expirada_sem_revoked_at_nao_prova_revogacao'),
    # (ajuste) a conferencia no instante da acao fica IMPLICADA pela do instante da consulta (depois da revogacao o
    # last_seen_at nao avanca): a K93 desliga a conferencia inteira; a K100 so a da consulta
    ('K93-401-de-sessao-ja-expirada-usado-como-prova', 'testes/validar_f6a.py',
     [('''        if motivo is None:
            api.me(api.restaura(k), k, esperado, pid)''', '''        if True:
            api.me(api.restaura(k), k, esperado, pid)''')], UNIT6, 'test_suspensao_sessao_ja_expirada_prova_pelo_banco_sem_requisicao'),
    ('K94-acao-repetida-ou-com-erro-aceita', 'testes/validar_f6a.py',
     [('''len(ts) == 1 and not outros,''', '''len(ts) >= 1,''')], UNIT6, 'test_suspensao_sem_acao_ou_com_duas_ou_com_erro_no_gateway'),
    ('K95-renovar-sem-a-pre-condicao-do-reset', 'testes/validar_f6a.py',
     [('''e == {**e, 'status': '2', 'fator_ativo': '1', 'fator_ativo_id': orig, 'eventos_reset': '0'}, e)''',
       '''True, e)''')], UNIT6, 'test_renovar_recusa_antes_de_qualquer_login'),
    ('K96-reset-sem-exigir-a-sessao-renovada-utilizavel', 'testes/validar_f6a.py',
     [('''len(sr) == 1 and utilizavel_em({**sr[0], 'revogada_em': '-'}, t_m) and ligada(sr[0]['revogada_em'], t_m),''',
       '''len(sr) == 1,''')], UNIT6, 'test_mfa_sessao_renovada_ligada_ao_reset'),
    ('K97-portal-consultado-com-sessao-expirada', 'testes/validar_f6a.py',
     [('''        if not (len(sr) == 1 and sr[0]['estado'] == 'utilizavel' and dentro_dos_prazos(sr[0], agora_utc())):''', '''        if False:''')],
     UNIT6, 'test_revogacao_ligada_e_sessao_renovada_utilizavel'),
    ('K98-logins-dos-alvos-nao-conferidos', 'testes/validar_f6a.py',
     [('''                if n != logins_alvos.get(k, 0):''', '''                if False:''')], UNIT6, 'test_logins_previstos_dos_alvos'),
    ('K99-prazo-de-inatividade-diferente-da-platform', 'testes/validar_f6a.py',
     [('''INATIVIDADE_MIN = {'interna': 30, 'externa': 15}''', '''INATIVIDADE_MIN = {'interna': 60, 'externa': 15}''')],
     UNIT6, 'test_prazo_igual_ao_da_platform'),
    # --- ajuste: o 401 da sessao antiga so discrimina se os prazos ainda valem na CONSULTA
    ('K100-prazo-da-consulta-ignorado-na-sessao-antiga', 'testes/validar_f6a.py',
     [('''            motivo = None if dentro_dos_prazos(s_ant, agora) else (''', '''            motivo = None if True else (''')],
     UNIT6, 'test_m02_expiracao_absoluta_entre_o_reset_e_a_consulta_para'),
    ('K101-portal-sem-conferir-o-prazo-na-consulta', 'testes/validar_f6a.py',
     [('''sr[0]['estado'] == 'utilizavel' and dentro_dos_prazos(sr[0], agora_utc())):''', '''sr[0]['estado'] == 'utilizavel'):''')],
     UNIT6, 'test_revogacao_ligada_e_sessao_renovada_utilizavel'),
    ('K102-consulta-sem-margem', 'testes/validar_f6a.py',
     [('''MARGEM_CONSULTA_S = 60''', '''MARGEM_CONSULTA_S = 0''')],
     UNIT6, 'test_m02_inatividade_entre_o_reset_e_a_consulta_para'),
    # --- STOP da R8: rotulos de recriacao do Compose no gateway substituido (compara_docker)
    ('K103-rotulo-de-recriacao-com-qualquer-valor', 'testes/validar_f6a.py',
     [('''                if rot in dif and fn.get(rot) == valor and antes_ok:''', '''                if rot in dif and antes_ok:''')],
     UNIT6, 'test_rotulos_da_recriacao_com_outro_valor_ou_outro_rotulo'),
    ('K104-gateway-substituido-sem-conferir-a-identificacao', 'testes/validar_f6a.py',
     [('''        if not (velhos[0].startswith(ant) and novos[0].startswith(nov)):''', '''        if False:''')],
     UNIT6, 'test_rotulos_da_recriacao_sem_identificacao_ou_outro_par'),
    ('K105-excecao-sem-identificacao-da-implantacao', 'testes/validar_f6a.py',
     [('''    elif gateway_ids is not None:
        ant, nov = gateway_ids''', '''    elif True:
        ant, nov = gateway_ids or ('', '')''')],
     UNIT6, 'test_rotulos_da_recriacao_sem_identificacao_ou_outro_par'),
    ('K106-replace-ja-existente-aceito', 'testes/validar_f6a.py',
     [('''                antes_ok = rot != 'rotulos.com.docker.compose.replace' or rot not in fv''', '''                antes_ok = True''')],
     UNIT6, 'test_rotulos_da_recriacao_com_outro_valor_ou_outro_rotulo'),
    # --- identificacao do par do gateway substituido
    ('K107-identificacao-do-par-nao-validada-na-comparacao', 'testes/validar_f6a.py',
     [('''    erro = ids_gateway_invalidos(gateway_ids) if gateway_ids is not None else None''', '''    erro = None''')],
     UNIT6, 'test_identificacao_do_par_invalida_nao_libera_a_excecao'),
    ('K108-identificacao-do-par-nao-validada-na-cli', 'testes/validar_f6a.py',
     [('''        erro = ids_gateway_invalidos(gids)
        if erro:
            print(f'RECUSADO: --gateway-ids''', '''        erro = None
        if erro:
            print(f'RECUSADO: --gateway-ids''')],
     UNIT6, 'test_identificacao_do_par_invalida_pela_cli'),
    ('K109-id-curto-aceito', 'testes/validar_f6a.py',
     [('''ID_GATEWAY = re.compile(r'[0-9a-f]{12,64}')''', '''ID_GATEWAY = re.compile(r'[0-9a-f]{1,64}')''')],
     UNIT6, 'test_identificacao_do_par_invalida_nao_libera_a_excecao'),
    ('K110-ids-iguais-ou-prefixo-aceitos', 'testes/validar_f6a.py',
     [('''    if ant.startswith(nov) or nov.startswith(ant):''', '''    if False:''')],
     UNIT6, 'test_identificacao_do_par_invalida_nao_libera_a_excecao'),
]

# R1: contraprova de BANCO so conta se as pre-condicoes passaram (base da F1.5 inclusive) e se a reprovacao foi
# EXATAMENTE a verificacao que a mutacao pretende derrubar. Codigo diferente de zero, sozinho, nao comprova nada.
EXIGE = {
    'K81-banco-update-julgado-so-pelo-estado-novo': {
        'precondicoes': 'OK    PRECONDICOES atendidas',
        'falhas': ['FALHA F8 proibida (fator-reapontado) -> VIOLACAO, e so ela'],
    },
    'K89-conferencia-pos-commit-ambigua': {
        'precondicoes': 'OK    PRECONDICOES atendidas',
        'falhas': ['FALHA F2 caminho limpo: psql codigo 0, CONFERE completa (4 contas ativas; concessoes 2,2), sem reconciliacao por resultado ambiguo',
                   'FALHA F4 repeticao com caminho limpo: psql codigo 0, CONFERE completa, sem reconciliacao por resultado ambiguo'],
    },
    'K90-inatividade-ignorada-no-estado-da-sessao': {
        'precondicoes': 'OK    PRECONDICOES atendidas',
        'falhas': ['FALHA F9 sessoes classificadas como a Platform (utilizavel, revogada, expirada absoluta e por inatividade 30/15 min); '
                   'a inatividade NAO preenche revoked_at; estado_conta conta cada estado'],
    },
}


def valida(saida):
    """Reavalia, com EXIGE, as contraprovas que rodaram; regrava contraprovas.json com 'valida' e o motivo."""
    import json
    arq = os.path.join(saida, 'contraprovas.json')
    res = json.load(open(arq, encoding='utf-8'))
    for r in res:
        e = EXIGE.get(r['contraprova'])
        if not e:
            r['valida'] = r['reprovou_com_mutacao']
            continue
        texto = open(os.path.join(saida, r['contraprova'] + '.log'), encoding='utf-8').read()
        falhas = [x for x in texto.splitlines() if x.startswith('FALHA ')]
        if 'PRECONDICAO FALHOU' in texto or e['precondicoes'] not in texto:
            r['valida'], r['motivo'] = False, 'INVALIDA: pre-condicao nao atendida'
        elif falhas != e['falhas']:
            r['valida'], r['motivo'] = False, f'INVALIDA: falhas diferentes das esperadas: {falhas}'
        else:
            r['valida'], r['motivo'] = r['reprovou_com_mutacao'], 'so a verificacao alvo reprovou'
        print(f"{r['contraprova']}: {r['motivo']}; valida={r['valida']}", flush=True)
    json.dump(res, open(arq, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    return all(r['valida'] and r['restaurado'] for r in res)


if __name__ == '__main__':
    cf.MUT = MUT
    try:
        cf.main()
    except SystemExit:
        pass
    sys.exit(0 if valida(sys.argv[1]) else 1)
