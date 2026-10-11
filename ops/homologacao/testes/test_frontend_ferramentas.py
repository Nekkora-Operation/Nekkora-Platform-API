"""Testes unitarios das ferramentas do ensaio-frontend (F1.5, P-A), so biblioteca padrao (sem banco, sem Docker):
validador do OpenAPI (com controles: cada regra tem um caso que TEM de reprovar), date-time por parsing real, TOTP pelos
vetores da RFC 6238, catalogo da semeadura, estados do orquestrador que o teste de banco nao provoca (banco sem resposta,
resultado incerto), transformacao do gateway e recusa do modo de teste fora de loopback.

Uso: python testes/test_frontend_ferramentas.py [-v]     (opcional: OPENAPI_JSON=<openapi convertido> para os casos reais)
"""
import json
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
sys.path.insert(0, os.path.join(AQUI, '..', 'lib'))
import gateway_frontend as gf  # noqa: E402
import jornadas_frontend as jf  # noqa: E402
import semear_frontend as sf  # noqa: E402

BASH = os.environ.get('HOMOLOG_BASH') or (r'C:\Program Files\Git\bin\bash.exe' if os.path.exists(r'C:\Program Files\Git\bin\bash.exe') else 'bash')


class DataHora(unittest.TestCase):
    def test_validas(self):
        for s in ('2026-10-04T12:00:00Z', '2026-10-04T12:00:00.123456Z', '2026-10-04T09:00:00-03:00', '2024-02-29T00:00:00Z',
                  '2016-12-31T23:59:60Z', '2016-12-31T20:59:60-03:00', '2026-10-04t12:00:00z'):
            self.assertTrue(jf.data_hora_valida(s), s)

    def test_invalidas(self):
        for s in ('2026-02-29T00:00:00Z', '2026-02-30T00:00:00Z', '2026-13-01T00:00:00Z', '2026-10-04T24:00:00Z',
                  '2026-10-04T12:60:00Z', '2026-10-04T12:00:00', '2026-10-04 12:00:00Z', '2026-10-04T12:00:60Z',
                  '2016-12-31T23:59:60+01:00', '2026-10-04T12:00:00+24:00', '26-10-04T12:00:00Z', ''):
            self.assertFalse(jf.data_hora_valida(s), s)


class Totp(unittest.TestCase):
    # RFC 6238, apendice B (SHA-1, segredo "12345678901234567890"); os 6 ultimos digitos do valor de 8 digitos
    VETORES = {59: '287082', 1111111109: '081804', 1111111111: '050471', 1234567890: '005924', 2000000000: '279037'}

    def test_rfc6238(self):
        segredo = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'
        for t, esperado in self.VETORES.items():
            self.assertEqual(jf.totp(segredo, t // 30), esperado, t)


MINI = {
    'paths': {
        '/x/{id}': {'get': {'responses': {
            '200': {'content': {'application/json': {'schema': {'$ref': '#/components/schemas/Obj'}}}},
            '204': {'description': 'vazio'},
            '404': {'$ref': '#/components/responses/NotFound'}}}},
        '/x/lista': {'get': {'responses': {'200': {'content': {'application/json': {'schema': {
            'type': 'object', 'required': ['items'], 'properties': {'items': {'type': 'array', 'maxItems': 2, 'uniqueItems': True,
                                                                              'items': {'type': 'integer', 'minimum': 1}}}}}}}}}},
    },
    'components': {
        'responses': {'NotFound': {'content': {'application/json': {'schema': {'type': 'object', 'required': ['error'],
                                                                                'properties': {'error': {'type': 'string', 'enum': ['not_found']}}}}}}},
        'schemas': {'Obj': {'type': 'object', 'required': ['id', 'quando', 'opcional', 'tipo', 'ref'], 'additionalProperties': False,
                            'properties': {'id': {'type': 'string', 'format': 'uuid'},
                                           'quando': {'type': 'string', 'format': 'date-time'},
                                           'opcional': {'type': ['string', 'null'], 'minLength': 2},
                                           'tipo': {'type': 'string', 'enum': ['a', 'b']},
                                           'ref': {'oneOf': [{'type': 'object', 'required': ['k'], 'properties': {'k': {'type': 'string'}}}, {'type': 'null'}]},
                                           'todos': {'allOf': [{'type': 'integer'}, {'not': {'const': 0}}]},
                                           'duplo': {'oneOf': [{'type': 'integer'}, {'type': 'number'}]}}}},
    },
}
BOM = {'id': '0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d', 'quando': '2026-10-04T12:00:00Z', 'opcional': None, 'tipo': 'a', 'ref': {'k': 'v'}}


class ValidadorControles(unittest.TestCase):
    def setUp(self):
        self.v = jf.Validador(MINI)

    def erros(self, corpo, status=200, caminho='/x/abc'):
        return self.v.resposta('GET', caminho, status, json.dumps(corpo) if corpo is not None else '')

    def test_conforme(self):
        self.assertEqual(self.erros(BOM), [])
        self.assertEqual(self.erros(dict(BOM, opcional='ok', ref=None, todos=5, duplo=1.5)), [])
        self.assertEqual(self.erros(None, 204), [])
        self.assertEqual(self.erros({'error': 'not_found'}, 404), [])
        self.assertEqual(self.erros({'items': [1, 2]}, caminho='/x/lista'), [])

    def test_cada_regra_reprova(self):
        casos = {
            'required': {k: v for k, v in BOM.items() if k != 'tipo'},
            'additionalProperties false': dict(BOM, extra=1),
            'format uuid': dict(BOM, id='nao-uuid'),
            'format date-time (data inexistente)': dict(BOM, quando='2026-02-30T00:00:00Z'),
            'type anulavel': dict(BOM, opcional=3),
            'minLength': dict(BOM, opcional='x'),
            'enum': dict(BOM, tipo='c'),
            'oneOf (nenhum ramo)': dict(BOM, ref='texto'),
            'oneOf (dois ramos)': dict(BOM, duplo=1),
            'allOf/not': dict(BOM, todos=0),
            'allOf/type': dict(BOM, todos='1'),
        }
        for regra, corpo in casos.items():
            self.assertNotEqual(self.erros(corpo), [], regra)
        for regra, corpo in {'maxItems': {'items': [1, 2, 3]}, 'uniqueItems': {'items': [1, 1]}, 'minimum': {'items': [0]},
                             'type integer': {'items': ['1']}, 'required (lista)': {}}.items():
            self.assertNotEqual(self.erros(corpo, caminho='/x/lista'), [], regra)

    def test_status_e_operacao(self):
        self.assertNotEqual(self.erros(BOM, 500), [], 'status nao documentado')
        self.assertNotEqual(self.v.resposta('POST', '/x/abc', 200, '{}'), [], 'operacao nao publicada')
        self.assertNotEqual(self.erros({'a': 1}, 204), [], 'corpo onde o contrato nao declara')
        self.assertNotEqual(self.v.resposta('GET', '/x/abc', 200, '<html>'), [], 'corpo nao JSON')

    def test_caminho_literal_vence_parametro(self):
        self.assertEqual(self.v.operacao('GET', '/x/lista')[0], '/x/lista')
        self.assertEqual(self.v.operacao('GET', '/x/qualquer')[0], '/x/{id}')

    def test_relatorio_de_restricoes_nao_verificadas(self):
        spec = json.loads(json.dumps(MINI))
        spec['components']['schemas']['Obj']['properties']['id']['contentEncoding'] = 'base64'
        self.assertEqual(jf.Validador(spec).restricoes_do_contrato_nao_verificadas(), {'contentEncoding': 1})


@unittest.skipUnless(os.environ.get('OPENAPI_JSON'), 'OPENAPI_JSON nao informado')
class ValidadorContratoReal(unittest.TestCase):
    def setUp(self):
        self.v = jf.Validador(json.load(open(os.environ['OPENAPI_JSON'], encoding='utf-8')))

    def test_erro_e_paginas(self):
        org = '0a1b2c3d-4e5f-4a6b-8c7d-8e9f0a1b2c3d'
        self.assertEqual(self.v.resposta('GET', f'/api/v1/o/{org}/me', 401, '{"error":"unauthenticated"}'), [])
        self.assertNotEqual(self.v.resposta('GET', f'/api/v1/o/{org}/me', 401, '{"error":"desconhecido"}'), [])
        self.assertEqual(self.v.resposta('GET', f'/api/v1/o/{org}/workspaces', 200, '{"items":[],"nextCursor":null}'), [])
        self.assertNotEqual(self.v.resposta('GET', f'/api/v1/o/{org}/workspaces', 200, '{"items":[]}'), [])

    def test_contrato_sem_restricoes_fora_do_escopo(self):
        self.assertEqual(self.v.restricoes_do_contrato_nao_verificadas(), {})


class Catalogo(unittest.TestCase):
    def setUp(self):
        self.p = sf.catalogo_plano(random.Random(7))

    def test_contas(self):
        emails = [c['email'] for c in self.p['contas']]
        self.assertEqual(len(emails), len(set(emails)))
        self.assertTrue(all(e.endswith('@demo.nekkora.test') for e in emails))
        for org in ('a', 'b'):
            self.assertEqual(sum(1 for c in self.p['contas'] if c['org'] == org and c['primeiro_admin']), 1, org)
        nav = {c['chave']: c['papeis'] for c in self.p['contas'] if c['uso'] == 'navegacao'}
        jor = {c['chave'][2:]: c['papeis'] for c in self.p['contas'] if c['uso'] == 'jornada'}
        self.assertEqual(nav, jor, 'jornada e navegacao com os MESMOS papeis, em contas separadas')
        exclusivas = [c for c in self.p['contas'] if c['uso'] not in ('navegacao', 'jornada')]
        self.assertTrue(all(c['papeis'] == {'ws': [('log', 'VIEWER')]} for c in exclusivas))
        self.assertEqual({c['uso'] for c in exclusivas}, {'primeiro-acesso', 'recuperacao', 'sessoes', 'expira'})
        self.assertTrue(all(not c['mfa'] for c in exclusivas if c['uso'] == 'primeiro-acesso'))

    def test_execucoes(self):
        e = self.p['execucoes']
        recentes = {x['estado'] for x in e if x['automacao'] == 'conciliacao' and x['criada_s'] < 30 * 86400}
        self.assertEqual(recentes, set(sf.ESTADOS), 'todos os estados dentro dos 30 dias padrao')
        self.assertGreaterEqual(sum(1 for x in e if x['automacao'] == 'backup'), 300)
        self.assertTrue(all(x['atraso_s'] + 1 + x['duracao_s'] < x['criada_s'] for x in e), 'nada termina no futuro')
        ids = {x['id'] for x in e}
        for x in e:
            if x['retry_de']:
                self.assertIn(x['retry_de'], ids)
        self.assertEqual(sum(1 for x in e if x['retry_de']), 1)

    def test_mapa_publico_sem_segredos(self):
        p = json.loads(json.dumps(self.p))
        for c in p['contas']:
            c['senha'], c['phc'] = 'SENHA-SECRETA', '$argon2id$PHC'
        t = json.dumps(sf.mapa_publico(p))
        self.assertNotIn('SENHA-SECRETA', t)
        self.assertNotIn('argon2id', t)


class OrquestradorEstados(unittest.TestCase):
    """Estados que o teste de banco nao provoca, com um psql FALSO (script) e um PHC falso."""

    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.evid = os.path.join(self.d, 'evid')
        self.estado = os.path.join(self.d, 'banco.json')
        json.dump({'org': None, 'responde': True, 'transacao': 'commit-e-perde-resposta'}, open(self.estado, 'w'))
        falso = os.path.join(self.d, 'psql_falso.py')
        open(falso, 'w').write('''import json, re, sys
st = json.load(open(sys.argv[1])); entrada = sys.stdin.read()
if not st['responde']: sys.exit(2)
if 'plano_b64' in entrada:
    if st['transacao'] == 'commit-e-cai-o-banco':
        import base64
        b64 = re.search(r"plano_b64 '([^']+)'", entrada).group(1)
        st['org'] = json.loads(base64.b64decode(b64))['orgs'][0]['id']; st['responde'] = False
        json.dump(st, open(sys.argv[1], 'w')); sys.exit(1)
    sys.exit(1)
m = re.search(r"organization_id = '([0-9a-f-]+)'", entrada)
if m: print('ORG=' + ('1' if st['org'] == m.group(1) else '0'))
else: print('NOMES=' + ('2' if st['org'] else '0'))
''')
        self.psql = f'python "{falso}" "{self.estado}"'
        self.phc = "read s; echo '$argon2id$v=19$m=65536,t=3,p=1$falso$falso'"
        self.sql = os.path.join(AQUI, '..', 'sql', 'semear_frontend.sql')

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def semear(self):
        a = type('A', (), {'segredos': self.d, 'evid': self.evid, 'sql': self.sql, 'psql': self.psql, 'phc': self.phc, 'bash': BASH})
        return sf.semear(a)

    def muda(self, **k):
        st = json.load(open(self.estado))
        st.update(k)
        json.dump(st, open(self.estado, 'w'))

    def test_banco_sem_resposta_nao_faz_nada(self):
        self.muda(responde=False)
        self.assertEqual(self.semear(), 4)
        self.assertFalse(os.path.exists(os.path.join(self.d, 'frontend')))

    def test_transacao_sem_efeito_mantem_pendente(self):
        self.assertEqual(self.semear(), 10)
        self.assertTrue(os.path.isfile(os.path.join(self.d, 'frontend', 'semeadura.pendente.json')))

    def test_resultado_incerto_e_banco_caido_para_sem_decidir(self):
        self.muda(transacao='commit-e-cai-o-banco')
        self.assertEqual(self.semear(), 9)
        self.assertTrue(os.path.isfile(os.path.join(self.d, 'frontend', 'semeadura.pendente.json')))
        self.muda(responde=True)   # o banco volta: a repeticao reconhece a transacao efetivada e so promove
        self.assertEqual(self.semear(), 0)
        self.assertTrue(os.path.isfile(os.path.join(self.d, 'frontend', 'semeadura.json')))

    def test_plano_final_sem_a_organizacao_e_inconsistente(self):
        self.muda(transacao='commit-e-cai-o-banco')
        self.semear()
        self.muda(responde=True)
        self.semear()
        self.muda(org=None)   # banco "perdeu" a semeadura (restaurado de copia antiga, p. ex.)
        self.assertEqual(self.semear(), 5)


class OrquestradorR1(unittest.TestCase):
    """P-A-R1: promocao segura (mapa antes do rename; mapa ausente recuperado sem SQL) e exclusao mutua por ambiente.
    psql FALSO com contagem de transacoes; PHC falso. Concorrencia com DOIS PROCESSOS reais."""

    FALSO = r"""import json, os, re, sys, time
est = sys.argv[1]; st = json.load(open(est)); entrada = sys.stdin.read()
if 'plano_b64' in entrada:
    st['transacoes'] = st.get('transacoes', 0) + 1
    import base64
    plano = json.loads(base64.b64decode(re.search(r"plano_b64 '([^']+)'", entrada).group(1)))
    if st['modo'] == 'commit-lento':
        time.sleep(4)
    if st['modo'] == 'commit-e-troca-plano':
        pend = st['pendente']; p = json.load(open(pend, encoding='utf-8')); p['execucao'] = 'outra-execucao'
        json.dump(p, open(pend, 'w', encoding='utf-8'))
    st['org'] = plano['orgs'][0]['id']
    json.dump(st, open(est, 'w'))
    print('SEMEADURA_FRONTEND_APLICADA ' + st['org'])
    sys.exit(0)
st['consultas'] = st.get('consultas', 0) + 1
json.dump(st, open(est, 'w'))
m = re.search(r"organization_id = '([0-9a-f-]+)'", entrada)
if m: print('ORG=' + ('1' if st.get('org') == m.group(1) else '0'))
else: print('NOMES=' + ('2' if st.get('org') else '0'))
"""

    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.evid = os.path.join(self.d, 'evid')
        self.estado_db = os.path.join(self.d, 'banco.json')
        self.pend = os.path.join(self.d, 'frontend', 'semeadura.pendente.json')
        self.fin = os.path.join(self.d, 'frontend', 'semeadura.json')
        self.mapa = os.path.join(self.evid, 'semeadura_frontend_mapa.json')
        self.banco(modo='commit')
        falso = os.path.join(self.d, 'psql_falso.py')
        with open(falso, 'w', encoding='utf-8') as h:
            h.write(self.FALSO)
        self.psql = f'python "{falso}" "{self.estado_db}"'
        self.phc = "read s; echo '$argon2id$v=19$m=65536,t=3,p=1$falso$falso'"
        self.sql = os.path.join(AQUI, '..', 'sql', 'semear_frontend.sql')
        self.script = os.path.join(AQUI, '..', 'lib', 'semear_frontend.py')

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def banco(self, **k):
        st = {}
        if os.path.exists(self.estado_db):
            with open(self.estado_db) as h:
                st = json.load(h)
        st.update(k)
        st['pendente'] = self.pend
        with open(self.estado_db, 'w') as h:
            json.dump(st, h)

    def db(self):
        with open(self.estado_db) as h:
            return json.load(h)

    def semear(self):
        a = type('A', (), {'segredos': self.d, 'evid': self.evid, 'sql': self.sql, 'psql': self.psql, 'phc': self.phc, 'bash': BASH})
        return sf.semear(a)

    def cli(self, *args):
        return subprocess.run([sys.executable, self.script, *args], capture_output=True, text=True)

    def sha(self, p):
        import hashlib
        with open(p, 'rb') as h:
            return hashlib.sha256(h.read()).hexdigest()

    # ---------------------------------------------------------------- 1. falha e interrupcao em torno do rename
    def test_falha_no_mapa_mantem_pendente_e_a_repeticao_so_promove(self):
        os.makedirs(self.mapa)                       # mapa publico impossivel de gravar (destino e diretorio)
        self.assertEqual(self.semear(), 6)
        self.assertTrue(os.path.isfile(self.pend), 'credenciais continuam no pendente')
        self.assertFalse(os.path.exists(self.fin))
        self.assertEqual(self.cli('estado', '--segredos', self.d, '--psql', self.psql, '--evid', self.evid).stdout.strip(), 'A_RECONCILIAR')
        os.rmdir(self.mapa)
        self.assertEqual(self.semear(), 0)
        self.assertTrue(os.path.isfile(self.fin) and os.path.isfile(self.mapa) and not os.path.exists(self.pend))
        self.assertEqual(self.db()['transacoes'], 1, 'nenhuma transacao nova')

    def test_interrupcao_logo_depois_do_rename(self):
        # processo filho: semeia de verdade, mas MORRE (os._exit) imediatamente depois do rename do pendente
        filho = f"""import os, sys
sys.path.insert(0, {os.path.join(AQUI, '..', 'lib')!r})
import semear_frontend as sf
orig = sf._renomeia
def morre(a, b):
    orig(a, b); os._exit(137)
sf._renomeia = morre
a = type('A', (), {{'segredos': {self.d!r}, 'evid': {self.evid!r}, 'sql': {self.sql!r}, 'psql': {self.psql!r}, 'phc': {self.phc!r}, 'bash': {BASH!r}}})
sys.exit(sf.semear(a))
"""
        r = subprocess.run([sys.executable, '-c', filho], capture_output=True)
        self.assertEqual(r.returncode, 137)
        self.assertTrue(os.path.isfile(self.fin) and not os.path.exists(self.pend))
        self.assertTrue(os.path.isfile(self.mapa), 'o mapa e gravado ANTES do rename')
        self.assertTrue(os.path.isdir(os.path.join(self.d, 'frontend.trava')), 'a morte deixa a trava orfa')
        self.assertEqual(self.semear(), 11, 'trava orfa: recusa ate liberar')
        self.assertNotEqual(self.cli('liberar-trava', '--segredos', self.d).returncode, 0, 'sem --confirmo-inativo')
        self.assertEqual(self.cli('liberar-trava', '--segredos', self.d, '--confirmo-inativo').returncode, 0)
        self.assertEqual(self.cli('estado', '--segredos', self.d, '--psql', self.psql, '--evid', self.evid).stdout.strip(), 'CONCLUIDA')
        self.assertEqual(self.semear(), 3, 'concluida: nao se repete')
        self.assertEqual(self.db()['transacoes'], 1)

    def test_concluida_sem_mapa_recupera_so_o_mapa(self):
        self.assertEqual(self.semear(), 0)
        cred = self.sha(self.fin)
        os.remove(self.mapa)                         # o estado do achado da revisao: final presente, mapa ausente
        self.assertEqual(self.cli('estado', '--segredos', self.d, '--psql', self.psql, '--evid', self.evid).stdout.strip(), 'CONCLUIDA_SEM_MAPA')
        self.assertEqual(self.semear(), 0)
        self.assertTrue(os.path.isfile(self.mapa))
        self.assertEqual(self.sha(self.fin), cred, 'credenciais intactas (nada gerado de novo)')
        self.assertEqual(self.db()['transacoes'], 1, 'sem SQL')
        self.assertEqual(self.semear(), 3)

    # ---------------------------------------------------------------- 2. concorrencia e identidade do plano
    def test_trava_ocupada_recusa_sem_gravar_nem_consultar(self):
        trava = os.path.join(self.d, 'frontend.trava')
        os.makedirs(trava)
        with open(os.path.join(trava, 'dono.json'), 'w') as h:
            json.dump({'pid': os.getpid(), 'host': __import__('socket').gethostname(), 'inicio': 'agora'}, h)
        self.assertEqual(self.semear(), 11)
        self.assertFalse(os.path.exists(os.path.join(self.d, 'frontend')), 'nenhum plano gravado')
        self.assertNotIn('consultas', self.db(), 'nem o estado do banco foi consultado')
        self.assertNotIn('transacoes', self.db())
        r = self.cli('liberar-trava', '--segredos', self.d, '--confirmo-inativo')
        self.assertNotEqual(r.returncode, 0, 'dono vivo neste host: recusado')

    def test_duas_chamadas_simultaneas_uma_recusada(self):
        self.banco(modo='commit-lento')
        cmd = [sys.executable, self.script, 'semear', '--segredos', self.d, '--evid', self.evid, '--sql', self.sql,
               '--psql', self.psql, '--phc', self.phc, '--bash', BASH]
        a = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        b = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        codigos = sorted([a.wait(timeout=300), b.wait(timeout=300)])
        self.assertEqual(codigos, [0, 11])
        self.assertEqual(self.db()['transacoes'], 1, 'uma transacao so')
        with open(self.fin, encoding='utf-8') as h:
            final = json.load(h)
        self.assertEqual(final['orgs'][0]['id'], self.db()['org'], 'o plano promovido e o plano aplicado')
        self.assertFalse(os.path.exists(self.pend))

    def test_plano_trocado_antes_da_promocao_nao_e_promovido(self):
        self.banco(modo='commit-e-troca-plano')     # durante a transacao, o pendente no disco vira OUTRO plano
        self.assertEqual(self.semear(), 12)
        self.assertFalse(os.path.exists(self.fin), 'nada promovido')
        with open(self.pend, encoding='utf-8') as h:
            self.assertEqual(json.load(h)['execucao'], 'outra-execucao', 'o arquivo alheio fica intacto')

    def test_pendente_criado_de_forma_exclusiva(self):
        os.makedirs(os.path.join(self.d, 'frontend'))
        with open(self.pend, 'w', encoding='utf-8') as h:
            h.write('{"existente": true}')
        with self.assertRaises(FileExistsError):
            sf.grava_atomico(self.pend, {'novo': True}, exclusivo=True)
        with open(self.pend, encoding='utf-8') as h:
            self.assertEqual(json.load(h), {'existente': True})
        self.assertEqual([x for x in os.listdir(os.path.dirname(self.pend)) if '.tmp-' in x], [], 'sem temporario restante')


@unittest.skipUnless(os.environ.get('OPENAPI_JSON'), 'OPENAPI_JSON nao informado')
class SeletorJornadas(unittest.TestCase):
    """P-B-C1: --somente executa SO sessoes-pos-revogacao e expiracao-absoluta, contra um servidor FALSO que responde no
    formato do contrato (as respostas passam pelo validador real) e registra cada requisicao; psql FALSO registra cada
    chamada. Prova o que e chamado e, sobretudo, o que NAO e."""

    CSRF = 'C' * 43

    def setUp(self):
        import http.server
        import threading
        self.d = tempfile.mkdtemp()
        self.seg = os.path.join(self.d, 'seg')
        self.evid = os.path.join(self.d, 'evid')
        self.req = []          # (metodo, caminho, corpo)
        self.estado = {'sessoes': {}, 'csrf429': False}
        p = sf.catalogo_plano(random.Random(11))
        for c in p['contas']:
            c['senha'] = 'senha-' + c['chave']
        self.plano = p
        self.org = p['orgs'][0]['id']
        os.makedirs(os.path.join(self.seg, 'frontend', 'contas'))
        with open(os.path.join(self.seg, 'frontend', 'semeadura.json'), 'w', encoding='utf-8') as h:
            json.dump(p, h)
        for chave in ('sessoes', 'expira'):
            with open(os.path.join(self.seg, 'frontend', 'contas', f'{chave}.json'), 'w', encoding='utf-8') as h:
                json.dump({'totp': 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 'estado': 'confirmado', 'codigos': [], 'ultimo_passo': -1}, h)
        teste = self
        org = self.org

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def responde(self, st, corpo=None, cookies=()):
                dados = json.dumps(corpo).encode() if corpo is not None else b''
                self.send_response(st)
                if corpo is not None:
                    self.send_header('Content-Type', 'application/json')
                for c in cookies:
                    self.send_header('Set-Cookie', c)
                self.send_header('Content-Length', str(len(dados)))
                self.end_headers()
                self.wfile.write(dados)

            def sessao(self):
                ck = self.headers.get('Cookie') or ''
                m = re.search(r'__Host-nekkora_session=([^;]+)', ck)
                return m.group(1) if m else None

            def do_GET(self):
                teste.req.append(('GET', self.path, ''))
                if self.path == '/api/v1/csrf':
                    if teste.estado['csrf429']:
                        return self.responde(429, {'error': 'rate_limited'})
                    return self.responde(200, {'csrfToken': teste.CSRF})
                if self.path == f'/api/v1/o/{org}/me':
                    s_ = self.sessao()
                    info = teste.estado['sessoes'].get(s_)
                    expirado = os.path.exists(teste.flag)   # a O5 falsa grava a marca ANTES de responder
                    if not info or info['revogada'] or (info['conta'] == 'expira' and expirado):
                        return self.responde(401, {'error': 'unauthenticated'})
                    c = next(x for x in teste.plano['contas'] if x['chave'] == info['conta'])
                    return self.responde(200, {'accountType': 'internal', 'displayName': c['nome'], 'email': c['email'], 'organizationId': org})
                return self.responde(404, {'error': 'not_found'})

            def do_POST(self):
                n = int(self.headers.get('Content-Length') or 0)
                corpo = self.rfile.read(n).decode() if n else ''
                teste.req.append(('POST', self.path, corpo))
                if self.path == f'/api/v1/o/{org}/auth/login':
                    email = json.loads(corpo)['email']
                    teste.estado['login'] = next(x['chave'] for x in teste.plano['contas'] if x['email'] == email)
                    return self.responde(200, {'status': 'mfa_required', 'csrfToken': teste.CSRF})
                if self.path == f'/api/v1/o/{org}/auth/mfa/verify':
                    sid = f's{len(teste.estado["sessoes"]) + 1}'
                    teste.estado['sessoes'][sid] = {'conta': teste.estado['login'], 'revogada': False}
                    return self.responde(200, {'csrfToken': teste.CSRF}, [f'__Host-nekkora_session={sid}; Path=/; Secure; HttpOnly; SameSite=Lax'])
                if self.path == f'/api/v1/o/{org}/auth/logout':
                    s_ = self.sessao()
                    if s_ in teste.estado['sessoes']:
                        teste.estado['sessoes'][s_]['revogada'] = True
                    return self.responde(204, None, ['__Host-nekkora_session=; Path=/; Max-Age=0'])
                return self.responde(404, {'error': 'not_found'})

        self.srv = http.server.ThreadingHTTPServer(('127.0.0.1', 0), H)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.porta = self.srv.server_address[1]
        # psql FALSO: registra a chamada e o tipo; "encurta" o prazo (expira as sessoes de expira@)
        self.psql_log = os.path.join(self.d, 'psql.log')
        self.flag = os.path.join(self.d, 'expirado')
        falso = os.path.join(self.d, 'psql_falso.py')
        with open(falso, 'w', encoding='utf-8') as h:
            h.write('import re, sys\nt = sys.stdin.read()\n'
                    'tipo = re.search(r"\\\\set tipo \'([^\']+)\'", t).group(1)\n'
                    f'open({self.psql_log!r}, "a").write(tipo + ("|semeadura" if "plano_b64" in t else "") + "\\n")\n'
                    f'open({self.flag!r}, "w").write("1")\n'
                    'print("SESSOES_ENVELHECIDAS=1")\n')
        self.psql = f'python "{falso}"'

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()
        shutil.rmtree(self.d, ignore_errors=True)

    def roda(self, *extra, psql=True):
        args = [sys.executable, os.path.join(AQUI, 'jornadas_frontend.py'), 'executar', '--segredos', self.seg, '--evid', self.evid,
                '--openapi-json', os.environ['OPENAPI_JSON'], '--openapi-yaml', os.path.join(AQUI, '..', '..', '..', 'api', 'openapi', 'nekkora-api-v1.yaml'),
                '--teste-http', f'http://127.0.0.1:{self.porta}', '--origem', 'https://ensaio.test', '--bash', BASH]
        if psql:
            args += ['--psql', self.psql, '--sql-sessao', os.path.join(AQUI, '..', 'sql', 'sessao_frontend.sql')]
        return subprocess.run(args + list(extra), capture_output=True, text=True, timeout=300)

    def resumo(self):
        with open(os.path.join(self.evid, 'resumo_executar.json'), encoding='utf-8') as h:
            return json.load(h)

    def caminhos(self):
        return [re.sub(r'[0-9a-f-]{36}', '{org}', c) for _, c, _ in self.req]

    def psql_chamadas(self):
        if not os.path.exists(self.psql_log):
            return []
        with open(self.psql_log, encoding='utf-8') as h:
            return h.read().split()

    def texto(self):
        with open(os.path.join(self.evid, 'jornadas.txt'), encoding='utf-8') as h:
            return h.read()

    # ---------------------------------------------------------------------------------------------- execucao
    def test_executa_so_as_duas_selecionadas(self):
        r = self.roda('--somente', 'sessoes-pos-revogacao,expiracao-absoluta', '--vinculo-stop', 'STOP P-B 2026-10-08T14:39:44Z M.26')
        self.assertEqual(r.returncode, 0, r.stdout[-800:] + r.stderr[-800:])
        permitidas = {'/api/v1/csrf', '/api/v1/o/{org}/auth/login', '/api/v1/o/{org}/auth/mfa/verify', '/api/v1/o/{org}/me', '/api/v1/o/{org}/auth/logout'}
        self.assertTrue(set(self.caminhos()) <= permitidas, set(self.caminhos()) - permitidas)
        self.assertEqual(self.caminhos().count('/api/v1/o/{org}/auth/login'), 2, 'um login por jornada: sessoes@ e expira@')
        corpos = ' '.join(c for _, _, c in self.req)
        for proibido in ('revoke-all', 'enroll', 'recoveryCode'):
            self.assertNotIn(proibido, ' '.join(self.caminhos()) + corpos, proibido)
        emails = [json.loads(c)['email'] for m, p_, c in self.req if p_.endswith('/auth/login')]
        self.assertEqual(emails, ['sessoes@demo.nekkora.test', 'expira@demo.nekkora.test'])
        self.assertEqual(self.psql_chamadas(), ['absoluta'], 'uma O5, nenhuma semeadura')
        rs = self.resumo()['selecao']
        self.assertEqual((rs['selecionadas'], rs['executadas'], rs['interrompida'], rs['nao_executadas']),
                         (['sessoes-pos-revogacao', 'expiracao-absoluta'], ['sessoes-pos-revogacao', 'expiracao-absoluta'], None, []))
        self.assertEqual(rs['vinculo_stop'], 'STOP P-B 2026-10-08T14:39:44Z M.26')
        self.assertEqual((rs['o5_usadas_depois'], rs['o5_maximo']), (1, 2))
        texto = self.texto()
        self.assertIn('SELECAO: somente sessoes-pos-revogacao,expiracao-absoluta; continuacao do STOP: STOP P-B', texto)
        for pid in ('M.26', 'M.30', 'M.31', 'M.32'):
            self.assertIn(f'OK    {pid} ', texto)
        for nunca in ('[sem-sessao]', '[j-admin]', '[primeiro-acesso]', '[recuperacao]', '[sessoes]', '[preparar-contas]'):
            self.assertNotIn(nunca, texto)

    def test_so_uma_selecionada(self):
        r = self.roda('--somente', 'sessoes-pos-revogacao', '--vinculo-stop', 'STOP x', psql=False)
        self.assertEqual(r.returncode, 0, r.stdout[-500:])
        self.assertEqual(self.caminhos().count('/api/v1/o/{org}/auth/login'), 1)
        self.assertEqual(self.psql_chamadas(), [])

    # ------------------------------------------------------------------------------------------------ recusas
    def test_recusas_antes_de_qualquer_requisicao_ou_arquivo(self):
        casos = {
            'desconhecida': ['--somente', 'sessoes', '--vinculo-stop', 'x'],
            'uma valida e uma desconhecida': ['--somente', 'sessoes-pos-revogacao,primeiro-acesso', '--vinculo-stop', 'x'],
            'repetida': ['--somente', 'expiracao-absoluta,expiracao-absoluta', '--vinculo-stop', 'x'],
            'vazia': ['--somente', '', '--vinculo-stop', 'x'],
            'nome vazio': ['--somente', 'sessoes-pos-revogacao,', '--vinculo-stop', 'x'],
            'sem vinculo': ['--somente', 'sessoes-pos-revogacao'],
            'com --mutacoes': ['--somente', 'sessoes-pos-revogacao', '--vinculo-stop', 'x', '--mutacoes'],
            'com --inatividade-real': ['--somente', 'expiracao-absoluta', '--vinculo-stop', 'x', '--inatividade-real'],
            'vinculo sem selecao': ['--vinculo-stop', 'x'],
        }
        for desc, extra in casos.items():
            r = self.roda(*extra)
            self.assertEqual(r.returncode, 2, desc)
            self.assertIn('RECUSADO', r.stderr, desc)
        r = self.roda('--somente', 'expiracao-absoluta', '--vinculo-stop', 'x', psql=False)
        self.assertEqual(r.returncode, 2, 'expiracao-absoluta sem --psql')
        self.assertEqual(self.req, [], 'nenhuma requisicao em nenhuma recusa')
        self.assertEqual(self.psql_chamadas(), [])
        self.assertFalse(os.path.exists(self.evid), 'nenhum arquivo de evidencia criado')

    def test_preparar_contas_com_selecao_recusado(self):
        args = [sys.executable, os.path.join(AQUI, 'jornadas_frontend.py'), 'preparar-contas', '--segredos', self.seg, '--evid', self.evid,
                '--openapi-json', os.environ['OPENAPI_JSON'], '--openapi-yaml', 'x', '--teste-http', f'http://127.0.0.1:{self.porta}',
                '--origem', 'https://x', '--somente', 'sessoes-pos-revogacao', '--vinculo-stop', 'x']
        r = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)
        self.assertEqual(self.req, [])

    # ------------------------------------------------------------------------------------------ O5, STOP e 429
    def test_o5_esgotada_para_sem_chamar_o_banco(self):
        c = {'primeiro-acesso': {}, 'recuperacao': {}, 'o5': [{'estado': 'executada'}, {'estado': 'executada'}]}
        with open(os.path.join(self.seg, 'frontend', 'consumo.json'), 'w', encoding='utf-8') as h:
            json.dump(c, h)
        r = self.roda('--somente', 'expiracao-absoluta', '--vinculo-stop', 'x')
        self.assertEqual(r.returncode, 1)
        self.assertEqual(self.psql_chamadas(), [], 'o banco nao e chamado com a O5 esgotada')
        self.assertEqual(self.req, [], 'nem login')
        rs = self.resumo()['selecao']
        self.assertEqual((rs['executadas'], rs['interrompida'], rs['o5_usadas_depois']), ([], 'expiracao-absoluta', 2))

    def test_o5_marcada_antes_e_contada(self):
        self.roda('--somente', 'expiracao-absoluta', '--vinculo-stop', 'x')
        with open(os.path.join(self.seg, 'frontend', 'consumo.json'), encoding='utf-8') as h:
            o5 = json.load(h)['o5']
        self.assertEqual([x['estado'] for x in o5], ['executada'])
        self.flag_reset()
        self.roda('--somente', 'expiracao-absoluta', '--vinculo-stop', 'x')
        self.flag_reset()
        r = self.roda('--somente', 'expiracao-absoluta', '--vinculo-stop', 'x')
        self.assertEqual(r.returncode, 1, 'a terceira O5 e recusada')
        self.assertEqual(self.psql_chamadas(), ['absoluta', 'absoluta'])

    def flag_reset(self):
        if os.path.exists(self.flag):
            os.remove(self.flag)

    def test_429_para_sem_repetir_e_registra_o_restante(self):
        self.estado['csrf429'] = True
        r = self.roda('--somente', 'sessoes-pos-revogacao,expiracao-absoluta', '--vinculo-stop', 'STOP original')
        self.assertEqual(r.returncode, 1)
        self.assertEqual(self.req, [('GET', '/api/v1/csrf', '')], 'uma unica requisicao: nada de repeticao automatica')
        rs = self.resumo()['selecao']
        self.assertEqual((rs['executadas'], rs['interrompida'], rs['nao_executadas']), ([], 'sessoes-pos-revogacao', ['expiracao-absoluta']))
        self.assertEqual(self.psql_chamadas(), [])
        self.assertIn('STOP no primeiro resultado inesperado', self.texto())

    def test_resposta_fora_do_contrato_para(self):
        # o servidor viola o CONTRATO (csrfToken numerico no verify): o validador tem de parar a selecao
        r = None
        h = self.srv.RequestHandlerClass
        original = h.do_POST

        def do_POST(handler):
            if handler.path.endswith('/auth/mfa/verify'):
                n = int(handler.headers.get('Content-Length') or 0)
                handler.rfile.read(n)
                self.req.append(('POST', handler.path, ''))
                return handler.responde(200, {'csrfToken': 123})
            return original(handler)
        h.do_POST = do_POST
        try:
            r = self.roda('--somente', 'sessoes-pos-revogacao', '--vinculo-stop', 'x', psql=False)
        finally:
            h.do_POST = original
        self.assertEqual(r.returncode, 1)
        self.assertIn('FALHA CONTRATO', self.texto())


class Gateway(unittest.TestCase):
    def test_ancora(self):
        base = 'http {\n    server {\n' + gf.ANCORA + '    }\n}\n'
        novo = gf.aplicar(base)
        self.assertIn('location @spa', novo)
        with self.assertRaises(SystemExit):
            gf.aplicar(novo)                       # aplicar de novo
        with self.assertRaises(SystemExit):
            gf.aplicar(base.replace(gf.ANCORA, ''))  # sem ancora
        with self.assertRaises(SystemExit):
            gf.aplicar(base.replace(gf.ANCORA, gf.ANCORA + gf.ANCORA))  # ancora duplicada

    def test_conferir_reprova_alteracao_a_mais(self):
        d = tempfile.mkdtemp()
        try:
            base = os.path.join(d, 'base.conf')
            efetivo = os.path.join(d, 'efetivo.conf')
            open(base, 'w', encoding='utf-8', newline='').write('http {\n    server {\n' + gf.ANCORA + '    }\n}\n')
            script = os.path.join(AQUI, '..', 'lib', 'gateway_frontend.py')
            self.assertEqual(subprocess.run([sys.executable, script, 'aplicar', base, efetivo], capture_output=True).returncode, 0)
            self.assertEqual(subprocess.run([sys.executable, script, 'conferir', base, efetivo], capture_output=True).returncode, 0)
            with open(efetivo, 'a', encoding='utf-8', newline='') as h:
                h.write('# alteracao a mais\n')
            self.assertEqual(subprocess.run([sys.executable, script, 'conferir', base, efetivo], capture_output=True).returncode, 1)
        finally:
            shutil.rmtree(d, ignore_errors=True)


class ModoTeste(unittest.TestCase):
    def test_teste_http_so_em_loopback(self):
        for alvo in ('http://10.0.0.5:8080', 'http://exemplo.test:80', 'https://127.0.0.1:443'):
            a = type('A', (), {'teste_http': alvo, 'origem': 'https://x', 'alvo': None, 'ca': None})
            with self.assertRaises(SystemExit, msg=alvo):
                jf.Alvo(a)
        a = type('A', (), {'teste_http': 'http://127.0.0.1:8080', 'origem': 'https://x', 'alvo': None, 'ca': None})
        self.assertTrue(jf.Alvo(a).http)

    def test_alvo_real_exige_ca(self):
        a = type('A', (), {'teste_http': None, 'origem': None, 'alvo': 'https://ensaio.test:55741', 'ca': None})
        with self.assertRaises(SystemExit):
            jf.Alvo(a)


if __name__ == '__main__':
    unittest.main()
