"""Contraprovas da P-A do ensaio-frontend (F1.5): cada MUTACAO de uma defesa tem de fazer o seu teste-alvo REPROVAR; o
arquivo volta ao original com sha256 conferido. Uma mutacao cujo teste continua aprovando = contraprova INVALIDA (a
defesa nao e coberta). O teste-alvo e identificado por uma linha "FALHA <id>" (testes em bash) ou pelo codigo de saida
com o nome do teste no relatorio (unittest); controle sem mutacao tem de APROVAR.

Uso: python testes/contraprovas_frontend.py <dir_de_saida> [ids...]
"""
import hashlib
import json
import os
import subprocess
import sys

AQUI = os.path.dirname(os.path.abspath(__file__))
OPS = os.path.dirname(AQUI)
BASH = os.environ.get('HOMOLOG_BASH') or (r'C:\Program Files\Git\bin\bash.exe' if os.path.exists(r'C:\Program Files\Git\bin\bash.exe') else 'bash')
sha = lambda p: hashlib.sha256(open(p, 'rb').read()).hexdigest()

GW = 'bash testes/test_gateway_frontend.sh'
BANCO = 'bash testes/test_semear_frontend_banco.sh "$SAIDA_TESTE"'
UNIT = 'python testes/test_frontend_ferramentas.py -v'
# testes do seletor (P-B-C1) exigem o OpenAPI convertido: sem ele seriam PULADOS e a contraprova passaria sem provar
UNIT_SEL = 'OPENAPI_JSON="$OPENAPI_JSON_CONTRAPROVA" python testes/test_frontend_ferramentas.py -v SeletorJornadas'
VC = 'bash homologacao.sh validar-config'
SELGW = 'bash testes/test_selecao_gateway.sh'
CRED = 'python testes/test_credencial_navegacao.py -v'
GWTIPOS = 'bash testes/test_gateway_tipos.sh'

MUT = [
    # id, arquivo (relativo a ops/homologacao), trocas, comando, marcador de reprovacao esperado
    ('K01-gateway-sem-guarda-de-api', 'lib/gateway_frontend.py',
     [("""        location ^~ /api {
            default_type application/json;
            return 404 '{"error":"not_found"}';
        }

""", '')], GW, 'FALHA G21'),
    ('K02-gateway-assets-cai-na-aplicacao', 'lib/gateway_frontend.py',
     # (P-C-R1: TIPOS fica entre "root /spa;" e o try_files; a mutacao e a mesma: /assets/ cai na aplicacao)
     [("""            try_files $uri =404;
        }""", """            try_files $uri @spa;
        }""")], GW, 'FALHA G11'),
    ('K03-gateway-sem-regra-de-extensao', 'lib/gateway_frontend.py',
     [("""            if ($uri ~ "\\\\.[A-Za-z0-9]+$") {
                return 404;
            }
""", '')], GW, 'FALHA G12'),
    ('K04-sql-sem-recusa-de-semeadura-existente', 'sql/semear_frontend.sql',
     [("""        RAISE EXCEPTION 'SEMEADURA_RECUSADA: ja existe organizacao da semeadura (nao se repete; nada foi gravado)';""",
       """        RAISE NOTICE 'recusa desligada';""")], BANCO, 'FALHA S8'),
    ('K05-sql-sem-conferencia-antes-do-commit', 'sql/semear_frontend.sql',
     [("""    IF n <> (p->'esperado'->>'workspaces')::int THEN RAISE EXCEPTION 'conferencia: % workspaces', n; END IF;""",
       """    IF false THEN RAISE EXCEPTION 'conferencia: % workspaces', n; END IF;""")], BANCO, 'FALHA S9'),
    ('K06-orquestrador-ignora-falha-ao-gravar-credenciais', 'lib/semear_frontend.py',
     [("""            log(a.evid, f'STOP: plano com as credenciais NAO gravado ({e.__class__.__name__}: {e}); nada foi enviado ao banco')
            return 8""", """            log(a.evid, f'STOP: plano com as credenciais NAO gravado ({e.__class__.__name__}: {e}); nada foi enviado ao banco')""")],
     BANCO, 'FALHA S1'),
    ('K07-orquestrador-decide-pelo-codigo-do-psql', 'lib/semear_frontend.py',
     [("""    no_banco = org_no_banco(bash, a.psql, plano['orgs'][0]['id'])
    if no_banco is None:
        log(a.evid, 'STOP: resultado incerto""", """    no_banco = (rc == 0)
    if no_banco is None:
        log(a.evid, 'STOP: resultado incerto""")], BANCO, 'FALHA S4'),
    ('K08-sessao-sem-guarda-da-conta-exclusiva', 'sql/sessao_frontend.sql',
     [("""        RAISE EXCEPTION 'expiracao recusada: so a conta exclusiva expira@demo.nekkora.test da organizacao Nekkora Demo';""",
       """        RAISE NOTICE 'guarda desligada';""")], BANCO, 'FALHA S10'),
    ('K09-validador-ignora-additionalProperties', 'testes/jornadas_frontend.py',
     [("""                elif esq.get('additionalProperties') is False:
                    erros.append(f'{onde}: campo nao publicado {k}')""", """                elif esq.get('additionalProperties') is False:
                    pass""")], UNIT, 'test_cada_regra_reprova'),
    ('K10-validador-data-sem-calendario', 'testes/jornadas_frontend.py',
     [("""    try:
        datetime.date(ano, mes, dia)
    except ValueError:
        return False
    fuso = m.group(8)""", """    fuso = m.group(8)""")], UNIT, 'test_invalidas'),
    ('K11-validador-oneOf-aceita-dois-ramos', 'testes/jornadas_frontend.py',
     [("""            if n != 1:
                erros.append(f'{onde}: {n} ramos de oneOf (esperado 1)')""", """            if n < 1:
                erros.append(f'{onde}: {n} ramos de oneOf (esperado 1)')""")], UNIT, 'test_cada_regra_reprova'),
    ('K12-conferir-do-gateway-sempre-aprova', 'lib/gateway_frontend.py',
     [("""        if esperado != efetivo:
            print('FALHA: nginx da aplicacao difere do gerado + bloco da aplicacao')
            sys.exit(1)""", """        if False:
            sys.exit(1)""")], UNIT, 'test_conferir_reprova_alteracao_a_mais'),
    ('K13-par-web-lib-aceita-copia-suja', 'lib/frontend_dist.sh',
     [("""    [ -z "$(git -C "$web" -c core.autocrlf=false status --porcelain 2>/dev/null)" ] || { echo "aplicacao web: copia de trabalho com alteracoes ou arquivos nao ignorados"; return 1; }""",
       """    true""")], VC, 'FALHA recusa por outro motivo: web com arquivo nao ignorado'),
    ('K14-runner-jornadas-sem-ca', 'homologacao.sh',
     [("""--alvo "https://$HOMOLOG_HOST:$HOMOLOG_PORTA" --ca "$(mixed "$HOMOLOG_SEGREDOS_HOST/tls/ca.crt")" \\""",
       """--alvo "https://$HOMOLOG_HOST:$HOMOLOG_PORTA" \\""")], VC, 'FALHA jornadas pelo runner SO pelo gateway'),
    # ---- P-A-R1: promocao segura e exclusao mutua
    ('K15-promocao-renomeia-antes-do-mapa', 'lib/semear_frontend.py',
     [("""    grava_mapa(evid, plano)
    _renomeia(pend, fin)""", """    _renomeia(pend, fin)
    grava_mapa(evid, plano)""")], UNIT, 'test_falha_no_mapa_mantem_pendente_e_a_repeticao_so_promove'),
    ('K16-semeadura-sem-trava', 'lib/semear_frontend.py',
     [("""    if not trava.adquire():
        d = trava.dono()""", """    if not (trava.adquire() or True):
        d = trava.dono()""")], UNIT, 'test_duas_chamadas_simultaneas_uma_recusada'),
    ('K17-promocao-sem-conferir-identidade', 'lib/semear_frontend.py',
     [("""    if atual is None or identidade(atual) != identidade(plano):""", """    if atual is None:""")],
     UNIT, 'test_plano_trocado_antes_da_promocao_nao_e_promovido'),
    ('K18-concluida-sem-recuperar-o-mapa', 'lib/semear_frontend.py',
     [("""    if est == 'CONCLUIDA' and evid is not None and not mapa_confere(evid, plano):""", """    if False:""")],
     UNIT, 'test_concluida_sem_mapa_recupera_so_o_mapa'),
    ('K19-pendente-substitui-existente', 'lib/semear_frontend.py',
     [("""    if exclusivo:
        try:
            os.link(tmp, caminho)""", """    if False:
        try:
            os.link(tmp, caminho)""")], UNIT, 'test_pendente_criado_de_forma_exclusiva'),
    # ---- P-B-C1: seletor de jornadas
    ('K20-selecao-executa-outra-jornada', 'testes/jornadas_frontend.py',
     [("""            for nome in selecao:
                interrompida = nome""", """            j.sem_sessao()
            for nome in selecao:
                interrompida = nome""")], UNIT_SEL, 'test_executa_so_as_duas_selecionadas'),
    ('K21-nome-desconhecido-cai-na-suite-completa', 'testes/jornadas_frontend.py',
     [("""    if desconhecidos:
        raise SystemExit(""", """    if desconhecidos:
        return []
        raise SystemExit(""")], UNIT_SEL, 'test_recusas_antes_de_qualquer_requisicao_ou_arquivo'),
    ('K22-o5-sem-limite', 'testes/jornadas_frontend.py',
     [("""        if usadas >= O5_MAXIMO:""", """        if False:""")], UNIT_SEL, 'test_o5_esgotada_para_sem_chamar_o_banco'),
    ('K23-pos-revogacao-repete-o-revoke-all', 'testes/jornadas_frontend.py',
     [("""            metodo = {'sessoes-pos-revogacao': j.sessoes_pos_revogacao,""", """            metodo = {'sessoes-pos-revogacao': j.sessoes,""")],
     UNIT_SEL, 'test_executa_so_as_duas_selecionadas'),
    # ---- P-B-C1-R1: guardas do gateway da selecao (compose controlado)
    ('K24-coleta-que-falha-vale-como-vazia', 'lib/selecao_gateway.sh',
     [("""    [ "$st" = 0 ] || { echo "compose logs terminou com codigo $st" >> "$erro"; return 1; }""", """    true""")],
     SELGW, 'FALHA C01'),
    ('K25-gateway-parado-vale-como-sem-trafego', 'lib/selecao_gateway.sh',
     [("""    [ -n "$ids" ] || { echo "gateway fora de execucao (compose ps sem container em execucao)" >> "$erro"; return 1; }""", """    true""")],
     SELGW, 'FALHA C03'),
    ('K26-trafego-recente-ignorado', 'lib/selecao_gateway.sh',
     [("""    if [ "$n" != 0 ]; then""", """    if false; then""")], SELGW, 'FALHA C04'),
    ('K27-inconclusiva-termina-com-sucesso', 'lib/selecao_gateway.sh',
     [("""    [ "$inconclusiva" = 0 ] || return 3""", """    true""")], SELGW, 'FALHA C10'),
    ('K28-conferencia-final-so-desde-as-jornadas', 'lib/selecao_gateway.sh',
     [("""    if ! coleta_gateway "$desde" "$J/gateway_janela.txt" "$J/conferencia_erro.txt"; then""",
       """    if ! coleta_gateway "$inicio" "$J/gateway_janela.txt" "$J/conferencia_erro.txt"; then""")], SELGW, 'FALHA C08'),
    ('K29-jornadas-repetidas-apos-falha', 'lib/selecao_gateway.sh',
     [("""    "$@" || rc=$?""", """    "$@" || { rc=0; "$@" || rc=$?; }""")], SELGW, 'FALHA C13'),
    ('K30-runner-selecao-sem-as-guardas', 'homologacao.sh',
     [("""        [ -n "$SOMENTE" ] || exec "${JORN[@]}\"""", """        exec "${JORN[@]}\"""")], VC, 'FALHA runner (P-B-C1-R1)'),
    # ---- P-C-T: credenciais das contas de navegacao
    ('K31-credencial-de-conta-que-nao-e-de-navegacao', 'lib/credencial_navegacao.py',
     [("""    if c.get('uso') != NAVEGACAO:""", """    if False:""")], CRED, 'test_recusa_toda_conta_que_nao_e_de_navegacao'),
    ('K32-passo-reservado-so-depois-da-entrega', 'lib/credencial_navegacao.py',
     [("""        m['ultimo_passo'] = cand              # RESERVADO antes da entrega: nunca entregue duas vezes, mesmo se falhar
        grava_atomico(arq, m)
        valor = totp(m['totp'], cand)""", """        valor = totp(m['totp'], cand)"""),
      ("""            area.coloca(valor)
        except (EntregaDesfeita, LimpezaNaoConfirmada):""", """            area.coloca(valor)
            if tipo == 'totp':
                m['ultimo_passo'] = cand
                grava_atomico(arq, m)
        except (EntregaDesfeita, LimpezaNaoConfirmada):""")], CRED, 'test_passo_continua_reservado_quando_a_entrega_falha'),
    ('K33-valor-na-saida', 'lib/credencial_navegacao.py',
     [("""    escreve(f'ENTREGUE na area de transferencia (fora do historico): {meta}; """,
       """    escreve(f'ENTREGUE na area de transferencia (fora do historico): {meta} {valor}; """)], CRED, 'test_saidas_sem_credencial'),
    ('K34-passo-repetido', 'lib/credencial_navegacao.py',
     [("""    cand = max(p + 1, ultimo + 1)""", """    cand = max(p + 1, ultimo)""")], CRED, 'test_passo_escolhido'),
    ('K35-entra-no-historico-da-area-de-transferencia', 'lib/credencial_navegacao.py',
     [("""MARCAS = ('ExcludeClipboardContentFromMonitorProcessing', 'CanIncludeInClipboardHistory', 'CanUploadToCloudClipboard')""",
       """MARCAS = ('ExcludeClipboardContentFromMonitorProcessing',)""")], CRED, 'test_tres_marcas_gravadas_com_zero'),
    # ---- P-C-T-R1: limpeza em todos os caminhos
    ('K36-marca-com-valor-diferente-de-zero', 'lib/credencial_navegacao.py',
     [("""        if not f or not self.u.SetClipboardData(f, self._global(struct.pack('<I', 0))):""",
       """        if not f or not self.u.SetClipboardData(f, self._global(struct.pack('<I', 1))):""")], CRED, 'test_marcas_reais_com_valor_zero'),
    ('K37-entrega-parcial-nao-desfeita', 'lib/credencial_navegacao.py',
     [("""                # ainda com a area ABERTA: desfaz antes de libera-la (inclusive em interrupcao)
                try:
                    self._esvazia()""", """                # ainda com a area ABERTA: desfaz antes de libera-la (inclusive em interrupcao)
                try:
                    pass""")], CRED, 'test_falha_apos_gravar_o_texto_desfaz_antes_de_liberar'),
    ('K38-limpeza-so-no-fim-normal', 'lib/credencial_navegacao.py',
     [("""            dorme(min(1, max(0.0, fim - relogio())))
    finally:""", """            dorme(min(1, max(0.0, fim - relogio())))
    except BaseException:
        raise
    else:""")], CRED, 'test_interrupcao_durante_a_espera_limpa'),
    ('K39-conteudo-de-outra-aplicacao-apagado', 'lib/credencial_navegacao.py',
     [("""                if atual != valor:
                    return 'substituida'
""", "")], CRED, 'test_conteudo_substituido_e_preservado'),
    ('K40-falha-de-limpeza-silenciosa', 'lib/credencial_navegacao.py',
     [("""            escreve(f'limpeza NAO confirmada as {agora_utc(relogio())}: {e}')
            raise""", """            pass""")], CRED, 'test_falha_de_limpeza_relatada_sem_valor'),
    # ---- P-C-R1: tipos dos arquivos estaticos (tela em branco da P-C)
    ('K41-gateway-sem-tipos-dos-estaticos', 'lib/gateway_frontend.py',
     [("""TIPOS = \"\"\"            types {
                text/html html;
                text/javascript js mjs;
                text/css css;
                image/svg+xml svg;
                image/x-icon ico;
            }
\"\"\"""", """TIPOS = ''""")], GW, 'FALHA G26'),
    # ---- P-C-R2: procedimento de atualizacao do gateway
    ('K42-coleta-que-falha-libera-a-aplicacao', 'lib/gateway_tipos.sh',
     [("""        || gt_falha "pre-condicao NAO conferida: falha na coleta""", """        || true # "pre-condicao NAO conferida: falha na coleta""")],
     GWTIPOS, 'FALHA A-arquivo-anterior'),
    ('K43-aplicar-sem-reconferir-a-referencia', 'lib/gateway_tipos.sh',
     [("""    : > "$GT_D/aplicar.txt"
    gt_confere_referencia
""", """    : > "$GT_D/aplicar.txt"
""")], GWTIPOS, 'FALHA R-base'),
    ('K44-nginx-t-recusa-sem-reescrever-o-anterior', 'lib/gateway_tipos.sh',
     [("""|| { gt_escreve "$GT_BKP"; gt_falha "nginx -t recusou; anterior reescrito; sem reload"; }""",
       """|| { gt_falha "nginx -t recusou; anterior reescrito; sem reload"; }""")], GWTIPOS, 'FALHA F-nginx-t'),
]


def main():
    saida = sys.argv[1]
    so = set(sys.argv[2:])
    os.makedirs(saida, exist_ok=True)
    resultados = []
    env = dict(os.environ, PYTHONIOENCODING='utf-8', HOMOLOG_BASH=BASH, PYTHONDONTWRITEBYTECODE='1')
    # OpenAPI convertido (mesma ferramenta das jornadas) para os testes do seletor
    oa = os.path.join(saida, 'openapi.json')
    if not os.path.exists(oa):
        snake = os.path.expanduser(os.path.join('~', '.m2', 'repository', 'org', 'yaml', 'snakeyaml', '2.6', 'snakeyaml-2.6.jar'))
        subprocess.run(['java', '-cp', snake, os.path.join(OPS, 'lib', 'OpenApiJson.java'),
                        os.path.join(OPS, '..', '..', 'api', 'openapi', 'nekkora-api-v1.yaml'), oa], check=True, capture_output=True)
    env['OPENAPI_JSON_CONTRAPROVA'] = oa
    for mid, rel, trocas, cmd, marca in MUT:
        if so and mid.split('-')[0] not in so and mid not in so:
            continue
        p = os.path.join(OPS, rel)
        original = open(p, 'rb').read()
        h0 = sha(p)
        t = original.decode('utf-8')
        for a, b in trocas:
            assert t.count(a) == 1, (mid, a[:60], t.count(a))
            t = t.replace(a, b)
        log = os.path.join(saida, f'{mid}.log')
        try:
            open(p, 'wb').write(t.encode('utf-8'))
            env['SAIDA_TESTE'] = os.path.join(saida, f'{mid}.saida').replace('\\', '/')
            r = subprocess.run([BASH, '-c', cmd], cwd=OPS, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=3600)
            texto = r.stdout.decode('utf-8', 'replace')
        finally:
            open(p, 'wb').write(original)
        h1 = sha(p)
        open(log, 'w', encoding='utf-8').write(texto)
        if marca is None:
            reprovou = r.returncode != 0 and ('FAILED' in texto or 'FAIL:' in texto)
        elif marca.startswith('test_'):
            reprovou = r.returncode != 0 and any(marca in x and ('FAIL' in x or 'ERROR' in x) for x in texto.splitlines())
        else:
            reprovou = r.returncode != 0 and marca in texto
        resultados.append({'contraprova': mid, 'arquivo': rel, 'teste': cmd.split()[1] if cmd.startswith(('bash', 'python')) else cmd,
                           'marcador': marca, 'reprovou_com_mutacao': reprovou, 'codigo': r.returncode,
                           'sha256_antes': h0, 'sha256_restaurado': h1, 'restaurado': h0 == h1})
        print(f'{mid}: {"REPROVOU (esperado)" if reprovou else "APROVOU (contraprova INVALIDA)"}; {"restaurado" if h0 == h1 else "NAO RESTAURADO"}', flush=True)
    json.dump(resultados, open(os.path.join(saida, 'contraprovas.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    sys.exit(0 if all(x['reprovou_com_mutacao'] and x['restaurado'] for x in resultados) else 1)


if __name__ == '__main__':
    main()
