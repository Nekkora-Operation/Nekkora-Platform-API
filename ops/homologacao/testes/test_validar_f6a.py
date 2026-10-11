"""Testes de unidade do verificador da F6a-V (testes/validar_f6a.py) e do reuso deterministico de TOTP
(lib/credencial_navegacao.py totp-reuso), sem rede, banco nem Docker: fotos sinteticas, psql e logs simulados por
comandos de shell, area de transferencia em memoria, API simulada.

Uso: python testes/test_validar_f6a.py [-v]
"""
import copy
import datetime
import json
import os
import shutil
import sys
import tempfile
import types
import unittest

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
sys.path.insert(0, os.path.join(os.path.dirname(AQUI), 'lib'))
import validar_f6a as vf  # noqa: E402
import credencial_navegacao as cn  # noqa: E402
import jornadas_frontend as jf  # noqa: E402

ADM_U, ADM_M = 'a0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000002'
OUTRO_U = 'b0000000-0000-4000-8000-000000000001'
ALVO_U = 'c0000000-0000-4000-8000-000000000001'
# ids de container no formato do Docker (os do caso real: 1feb161c8bae -> 1fbb8d271ea0)
GW_A = '1feb161c8baed8abdf659a2' + '0' * 41
GW_N = '1fbb8d271ea0eb0b6dee844bf8646b17590fe85cbfab21aed4a8b1fceaa0b265'


def contas_base():
    return {'contas': [{'chave': 'admin', 'user_id': ADM_U, 'membership_id': ADM_M}]}


def plano_f6a():
    contas = []
    for i, (k, t) in enumerate((('f6av-suspensao', 'interna'), ('f6av-reativacao', 'interna'), ('f6av-mfa', 'interna'), ('f6av-cliente', 'externa'))):
        contas.append({'chave': k, 'tipo': t, 'email': f'{k}@demo.nekkora.test', 'senha': 'x', 'user_id': f'c000000{i}-0000-4000-8000-000000000001',
                       'membership_id': f'c000000{i}-0000-4000-8000-000000000002'})
    return {'contas': contas, 'concessoes': [{'chave': 'f6av-c1', 'grant_id': 'd0000000-0000-4000-8000-000000000001'},
                                             {'chave': 'f6av-c2', 'grant_id': 'd0000000-0000-4000-8000-000000000002'}],
            'refs': {'org_a': 'e0000000-0000-4000-8000-000000000001',
                     'workspaces': {'sandbox': 'w1', 'exp-astral': 'w2', 'exp-bmp': 'w3'}}}


# ------------------------------------------------------------------------------------------------- docker
def docker_base():
    g = {'nome': vf.GATEWAY, 'imagem': 'sha256:img', 'criado': 't0', 'estado': 'running', 'iniciado': 't0', 'terminado': 'z',
         'reinicios': 0, 'mounts': [{'tipo': 'bind', 'origem': '/host/saidas/ensaio-frontend/frontend-dist', 'destino': '/spa', 'rw': False},
                                    {'tipo': 'bind', 'origem': '/host/saidas/ensaio-frontend/config/nginx.conf', 'destino': '/etc/nginx/nginx.conf', 'rw': False}],
         'redes': {'borda': '10.252.95.10'}, 'rotulos': {'com.docker.compose.config-hash': 'h1', 'outro': 'x'}, 'portas': {'443/tcp': [{'HostPort': '55741'}]}}
    p = {'nome': '/nekkora-homologacao-ensaio-frontend-platform-1', 'imagem': 'sha256:plat', 'criado': 't0', 'estado': 'running', 'iniciado': 't0',
         'terminado': 'z', 'reinicios': 0, 'mounts': [], 'redes': {'interna': '10.252.94.5'}, 'rotulos': {}, 'portas': {}}
    return {'containers': {GW_A: g, 'plat': p}, 'volumes': {'v1': {'driver': 'local', 'rotulos': {}, 'criado': 't', 'ponto': '/x'}},
            'redes': {'n1': {'nome': 'borda', 'driver': 'bridge', 'rotulos': {}, 'criado': 't', 'subredes': ['10.252.95.0/24']}},
            'imagens': {'sha256:img': {'tags': ['nginx'], 'criado': 't'}}}


def docker_substituido(mexe=None):
    d = copy.deepcopy(docker_base())
    g = d['containers'].pop(GW_A)
    g = copy.deepcopy(g)
    g.update({'criado': 't1', 'iniciado': 't1'})
    g['rotulos']['com.docker.compose.config-hash'] = 'h2'
    g['mounts'][0]['origem'] = '/host/saidas/ensaio-frontend/frontend-dist-7851e20'
    if mexe:
        mexe(g, d)
    d['containers'][GW_N] = g
    return d


class Docker(unittest.TestCase):
    def test_identico_sem_substituicao(self):
        v, n = vf.compara_docker(docker_base(), docker_base(), None)
        self.assertEqual(v, [])

    def test_substituicao_esperada_do_gateway(self):
        v, n = vf.compara_docker(docker_base(), docker_substituido(), 'frontend-dist-7851e20')
        self.assertEqual(v, [])
        self.assertTrue(any('SUBSTITUIDO' in x for x in n) and any('frontend-dist -> frontend-dist-7851e20' in x for x in n))

    def test_substituicao_sem_ser_declarada_e_violacao(self):
        v, _ = vf.compara_docker(docker_base(), docker_substituido(), None)
        self.assertTrue(any('removido' in x for x in v) and any('criado' in x for x in v))

    def test_gateway_novo_com_imagem_rede_porta_ou_rotulo_diferente(self):
        for mexe, trecho in ((lambda g, d: g.update({'imagem': 'sha256:outra'}), 'imagem'),
                             (lambda g, d: g['redes'].update({'borda': '10.252.95.99'}), 'redes'),
                             (lambda g, d: g.update({'portas': {}}), 'portas'),
                             (lambda g, d: g['rotulos'].update({'outro': 'y'}), 'rotulos.outro')):
            v, _ = vf.compara_docker(docker_base(), docker_substituido(mexe), 'frontend-dist-7851e20')
            self.assertTrue(any(trecho in x for x in v), (trecho, v))

    def test_spa_errado_gravavel_ou_nginx_mudado(self):
        for mexe, trecho in ((lambda g, d: g['mounts'][0].update({'origem': '/host/saidas/ensaio-frontend/frontend-dist-outro'}), '/spa novo inesperado'),
                             (lambda g, d: g['mounts'][0].update({'rw': True}), '/spa novo inesperado'),
                             (lambda g, d: g['mounts'][0].update({'origem': '/outro/lugar/frontend-dist-7851e20'}), '/spa novo inesperado'),
                             (lambda g, d: g['mounts'][1].update({'origem': '/host/outro/nginx.conf'}), 'mount /etc/nginx/nginx.conf mudou')):
            v, _ = vf.compara_docker(docker_base(), docker_substituido(mexe), 'frontend-dist-7851e20')
            self.assertTrue(any(trecho in x for x in v), (trecho, v))

    def test_outro_container_volume_rede_ou_imagem_alterado(self):
        for mexe, trecho in ((lambda g, d: d['containers']['plat'].update({'iniciado': 't9'}), 'platform-1 alterado'),
                             (lambda g, d: d['volumes'].update({'v2': {}}), 'volumes v2'),
                             (lambda g, d: d['redes']['n1'].update({'subredes': ['10.0.0.0/8']}), 'redes n1'),
                             (lambda g, d: d['imagens'].pop('sha256:img'), 'imagens sha256:img')):
            v, _ = vf.compara_docker(docker_base(), docker_substituido(mexe), 'frontend-dist-7851e20')
            self.assertTrue(any(trecho in x for x in v), (trecho, v))

    # F6a-V-E (STOP da R8): rotulos que o Compose grava na recriacao, aceitos SO no gateway identificado e SO com os
    # valores exatos observados
    def recriado(self, dep='', rep='gateway-1', extra=None):
        def mexe(g, d):
            g['rotulos']['com.docker.compose.depends_on'] = dep
            if rep is not None:
                g['rotulos']['com.docker.compose.replace'] = rep
            if extra:
                extra(g, d)
        a = docker_base()
        a['containers'][GW_A]['rotulos']['com.docker.compose.depends_on'] = 'platform:service_started:false,portal:service_started:false'
        return a, docker_substituido(mexe)

    def test_rotulos_da_recriacao_aceitos_no_gateway_identificado(self):
        a, d = self.recriado()
        v, n = vf.compara_docker(a, d, 'frontend-dist-7851e20', (GW_A, GW_N))
        self.assertEqual(v, [])
        self.assertTrue(any("depends_on=''" in x for x in n) and any("replace='gateway-1'" in x for x in n), n)

    def test_rotulos_da_recriacao_sem_identificacao_ou_outro_par(self):
        a, d = self.recriado()
        v, _ = vf.compara_docker(a, d, 'frontend-dist-7851e20')
        self.assertTrue(any('rotulos.com.docker.compose.depends_on' in x and 'rotulos.com.docker.compose.replace' in x for x in v), v)
        v, _ = vf.compara_docker(a, d, 'frontend-dist-7851e20', ('aaaaaaaaaaaa', GW_N))
        self.assertTrue(any('nao e a identificada' in x for x in v), v)
        self.assertTrue(any('rotulos.com.docker.compose.replace' in x for x in v), v)

    def test_rotulos_da_recriacao_com_outro_valor_ou_outro_rotulo(self):
        for kw, trecho in (({'dep': 'platform:service_started:false'}, 'depends_on'), ({'rep': 'gateway-2'}, 'replace'),
                           ({'extra': lambda g, d: g['rotulos'].update({'com.docker.compose.project': 'outro'})}, 'project')):
            a, d = self.recriado(**kw)
            v, _ = vf.compara_docker(a, d, 'frontend-dist-7851e20', (GW_A, GW_N))
            self.assertTrue(any(trecho in x for x in v), (kw, v))
        a, d = self.recriado()
        a['containers'][GW_A]['rotulos']['com.docker.compose.replace'] = 'gateway-1'
        d['containers'][GW_N]['rotulos']['com.docker.compose.replace'] = 'gateway-1'
        d['containers'][GW_N]['rotulos']['com.docker.compose.depends_on'] = ''
        v, _ = vf.compara_docker(a, d, 'frontend-dist-7851e20', (GW_A, GW_N))
        self.assertEqual(v, [])                               # replace ja existente e igual: sem diferenca
        a, d = self.recriado()
        a['containers'][GW_A]['rotulos']['com.docker.compose.replace'] = 'gateway-0'
        v, _ = vf.compara_docker(a, d, 'frontend-dist-7851e20', (GW_A, GW_N))
        self.assertTrue(any('replace' in x for x in v), v)   # replace que JA existia antes nao e o da recriacao

    def test_rotulos_da_recriacao_em_outro_container_e_violacao(self):
        def mexe(g, d):
            d['containers']['plat']['rotulos'].update({'com.docker.compose.depends_on': '', 'com.docker.compose.replace': 'gateway-1'})
        a, d = self.recriado(extra=mexe)
        v, _ = vf.compara_docker(a, d, 'frontend-dist-7851e20', (GW_A, GW_N))
        self.assertTrue(any('platform-1 alterado' in x for x in v), v)

    def test_rotulos_da_recriacao_nao_liberam_imagem_rede_porta_ou_mount(self):
        for mexe, trecho in ((lambda g, d: g.update({'imagem': 'sha256:outra'}), 'imagem'),
                             (lambda g, d: g['redes'].update({'borda': '10.252.95.99'}), 'redes'),
                             (lambda g, d: g.update({'portas': {}}), 'portas'),
                             (lambda g, d: g['mounts'][1].update({'origem': '/host/outro/nginx.conf'}), 'nginx.conf')):
            a, d = self.recriado(extra=mexe)
            v, _ = vf.compara_docker(a, d, 'frontend-dist-7851e20', (GW_A, GW_N))
            self.assertTrue(any(trecho in x for x in v), (trecho, v))

    def test_identificacao_do_par_invalida_nao_libera_a_excecao(self):
        a, d = self.recriado()
        for ids in (('', ''), ('1', '1'), ('1feb161c8ba', '1fbb8d271ea0'), ('1FEB161C8BAE', '1fbb8d271ea0'),
                    ('1feb161c8bae', '1feb161c8bae'), ('1feb161c8bae', GW_A), ('zzzzzzzzzzzz', '1fbb8d271ea0'),
                    ('1feb161c8bae',), ('1feb161c8bae', '1fbb8d271ea0', 'x'), ('1feb161c8bae', None), ('', '1fbb8d271ea0')):
            v, _ = vf.compara_docker(a, d, 'frontend-dist-7851e20', ids)
            self.assertTrue(any('identificacao do gateway invalida' in x for x in v), (ids, v))
            self.assertTrue(any('rotulos.com.docker.compose.replace' in x for x in v), (ids, v))   # a excecao nao vale
        for ids in ((GW_A[:12], GW_N[:12]), (GW_A, GW_N)):                                        # controle: par real
            v, _ = vf.compara_docker(a, d, 'frontend-dist-7851e20', ids)
            self.assertEqual(v, [], ids)

    def test_identificacao_do_par_invalida_pela_cli(self):
        d = tempfile.mkdtemp()
        try:
            os.makedirs(os.path.join(d, 'frontend', 'f6a'))
            json.dump(contas_base(), open(os.path.join(d, 'frontend', 'semeadura.json'), 'w'))
            a, dd = self.recriado()
            vazio = {'tabelas': {}, 'linhas': {}}
            fa, fd = os.path.join(d, 'a.json'), os.path.join(d, 'd.json')
            json.dump({'instante': 't0', 'banco': vazio, 'docker': a}, open(fa, 'w'))
            json.dump({'instante': 't1', 'banco': vazio, 'docker': dd}, open(fd, 'w'))
            base = ['comparar', fa, fd, '--segredos', d, '--evid', os.path.join(d, 'ev'), '--logins-admin', '0', '--stepups', '0',
                    '--gateway-novo', 'frontend-dist-7851e20']
            for gi in ('', ':', '1:1', 'abc', '1feb161c8bae', '1feb161c8bae:1fbb8d271ea0:x', '1feb161c8ba:1fbb8d271ea0',
                       '1feb161c8bae:1feb161c8bae'):
                self.assertEqual(vf.main(base + ['--gateway-ids', gi]), 2, gi)
            self.assertEqual(vf.main(base + ['--gateway-ids', '1feb161c8bae:1fbb8d271ea0']), 0)   # controle: par real
            self.assertEqual(vf.main(base), 1)                                                     # sem identificacao: violacao
        finally:
            shutil.rmtree(d)

    def test_dois_gateways_novos(self):
        def mexe(g, d):
            d['containers']['gw-extra'] = copy.deepcopy(g)
        v, _ = vf.compara_docker(docker_base(), docker_substituido(mexe), 'frontend-dist-7851e20')
        self.assertTrue(any('1 saiu e 2 entrou' in x for x in v), v)


# -------------------------------------------------------------------------------------------------- banco
def linha(j, d='d', s='s'):
    return {'d': d, 's': s, 'j': j}


def foto_banco(**tabs):
    return {'tabelas': {t: {'pk': ['id'], 'sens': []} for t in tabs}, 'linhas': tabs}


class Banco(unittest.TestCase):
    IDS_ADM, IDS_ALVO = {ADM_U, ADM_M}, {ALVO_U}

    def compara(self, A, D, logins=0, stepups=0):
        return vf.compara_banco(A, D, self.IDS_ADM, self.IDS_ALVO, logins, stepups)

    def fator(self, passo, d='d1', key='k1'):
        return linha({'user_id': ADM_U, 'last_used_step': passo, 'key_id': key}, d=d)

    def test_last_used_step_so_para_mais(self):
        A = foto_banco(user_mfa_factors={'f': self.fator(10)})
        v, _, ator = self.compara(A, foto_banco(user_mfa_factors={'f': self.fator(12, d='d2')}))
        self.assertEqual(v, [])
        self.assertTrue(ator)
        v, _, _ = self.compara(A, foto_banco(user_mfa_factors={'f': self.fator(9, d='d2')}))
        self.assertTrue(any('fora da regra' in x for x in v), v)

    def test_fator_da_atora_outro_campo_ou_segredo(self):
        A = foto_banco(user_mfa_factors={'f': self.fator(10)})
        v, _, _ = self.compara(A, foto_banco(user_mfa_factors={'f': self.fator(12, d='d2', key='k2')}))
        self.assertTrue(any('fora da regra' in x for x in v), v)
        D = foto_banco(user_mfa_factors={'f': {**self.fator(12, d='d2'), 's': 'outro'}})
        v, _, _ = self.compara(A, D)
        self.assertTrue(v)

    def test_linha_existente_da_atora_em_sessoes_nao_muda(self):
        A = foto_banco(user_sessions={'s1': linha({'user_id': ADM_U, 'revoked_at': 't'})})
        D = foto_banco(user_sessions={'s1': linha({'user_id': ADM_U, 'revoked_at': 't2'}, d='d2')})
        v, _, _ = self.compara(A, D)
        self.assertTrue(any('user_sessions UPDATE s1 da atora fora das regras' in x for x in v), v)

    def test_delete_da_atora_e_insert_de_outra_identidade(self):
        A = foto_banco(auth_challenges={'c1': linha({'user_id': ADM_U})}, users={})
        D = foto_banco(auth_challenges={}, users={'u9': {'d': 'x', 's': 's'}})
        v, _, _ = self.compara(A, D)
        self.assertTrue(any('auth_challenges DELETE c1' in x for x in v) and any('users INSERT u9 (linha de outra identidade)' in x for x in v), v)

    def test_linhas_de_alvo_sao_listadas_nao_violam(self):
        A = foto_banco(users={'u1': linha({'user_id': ALVO_U, 'status': 2})})
        D = foto_banco(users={'u1': linha({'user_id': ALVO_U, 'status': 3}, d='d2')})
        v, alvo, _ = self.compara(A, D)
        self.assertEqual(v, [])
        self.assertEqual(alvo, ['users UPDATE u1'])

    OUTRA_U = OUTRO_U

    def test_r1_evento_de_outra_conta_com_alvo_so_como_ator(self):
        A = foto_banco(mfa_events={})
        D = foto_banco(mfa_events={'e': linha({'user_id': self.OUTRA_U, 'actor_user_id': ALVO_U, 'event_type': 3})})
        v, alvo, _ = self.compara(A, D)
        self.assertTrue(any('mfa_events INSERT e (linha de outra identidade)' in x for x in v), v)
        self.assertEqual(alvo, [])

    def test_r1_fator_de_outra_conta_reapontado_para_o_alvo(self):
        # foto real: a versao ANTES nao tem JSON (nao referia identidade observada)
        A = foto_banco(user_mfa_factors={'f': {'d': 'd1', 's': 's'}})
        D = foto_banco(user_mfa_factors={'f': linha({'user_id': ALVO_U, 'last_used_step': 1}, d='d2')})
        v, alvo, _ = self.compara(A, D)
        self.assertTrue(any('user_mfa_factors UPDATE f trocou de dono (outra -> alvo)' in x for x in v), v)
        self.assertEqual(alvo, [])
        # mesmo com JSON nos dois estados
        A = foto_banco(user_mfa_factors={'f': linha({'user_id': self.OUTRA_U, 'actor_user_id': ALVO_U})})
        v, _, _ = self.compara(A, D)
        self.assertTrue(any('trocou de dono (outra -> alvo)' in x for x in v), v)

    def test_r1_linha_da_atora_ou_do_alvo_reapontada(self):
        A = foto_banco(user_sessions={'s': linha({'user_id': ADM_U})})
        D = foto_banco(user_sessions={'s': linha({'user_id': ALVO_U}, d='d2')})
        self.assertTrue(any('trocou de dono (atora -> alvo)' in x for x in self.compara(A, D)[0]))
        self.assertTrue(any('trocou de dono (alvo -> atora)' in x for x in self.compara(D, A)[0]))

    def test_r1_outra_conta_com_a_atora_so_como_responsavel(self):
        A = foto_banco(workspace_role_assignments={'w': linha({'membership_id': 'm-outra', 'assigned_by_membership_id': ADM_M, 'status': 1})})
        D = foto_banco(workspace_role_assignments={'w': linha({'membership_id': 'm-outra', 'assigned_by_membership_id': ADM_M, 'status': 2}, d='d2')})
        v, _, ator = self.compara(A, D)
        self.assertTrue(any('workspace_role_assignments UPDATE w (linha de outra identidade)' in x for x in v), v)
        self.assertEqual(ator, [])

    def test_r1_dono_misto_e_protegido(self):
        A = foto_banco(organization_memberships={'m': linha({'user_id': ALVO_U, 'membership_id': 'm-outra'})})
        D = foto_banco(organization_memberships={'m': linha({'user_id': ALVO_U, 'membership_id': 'm-outra', 'status': 3}, d='d2')})
        self.assertTrue(any('(linha de outra identidade)' in x for x in self.compara(A, D)[0]))

    def test_r1_controles_mudancas_legitimas_dos_alvos(self):
        G, CM, AM = 'd0000000-0000-4000-8000-000000000001', 'c0000003-0000-4000-8000-000000000002', 'c0000000-0000-4000-8000-000000000002'
        ids_alvo = {ALVO_U, G, CM, AM}
        A = foto_banco(users={'u': linha({'user_id': ALVO_U, 'status': 2})},
                       client_workspace_grants={'g': linha({'grant_id': G, 'client_membership_id': CM, 'status': 2, 'revoked_by_membership_id': None, 'approved_by_membership_id': ADM_M})},
                       mfa_events={}, workspace_role_assignments={})
        D = foto_banco(users={'u': linha({'user_id': ALVO_U, 'status': 3}, d='d2')},
                       client_workspace_grants={'g': linha({'grant_id': G, 'client_membership_id': CM, 'status': 4, 'revoked_by_membership_id': ADM_M, 'approved_by_membership_id': ADM_M}, d='d2')},
                       mfa_events={'e': linha({'user_id': ALVO_U, 'actor_user_id': ADM_U, 'event_type': 3, 'reason_code': 1})},
                       workspace_role_assignments={'w': linha({'membership_id': AM, 'assigned_by_membership_id': ADM_M})})
        v, alvo, ator = vf.compara_banco(A, D, self.IDS_ADM, ids_alvo, 0, 0)
        self.assertEqual(v, [])
        self.assertEqual(sorted(alvo), sorted(['users UPDATE u', 'client_workspace_grants UPDATE g', 'mfa_events INSERT e', 'workspace_role_assignments INSERT w']))
        self.assertEqual(ator, [])

    def test_contagens_declaradas(self):
        A = foto_banco(user_sessions={}, auth_challenges={}, mfa_events={})
        D = foto_banco(user_sessions={'s': linha({'user_id': ADM_U, 'revoked_at': 't'})}, auth_challenges={'c': linha({'user_id': ADM_U})},
                       mfa_events={'e': linha({'user_id': ADM_U, 'event_type': 6, 'actor_user_id': None})})
        self.assertEqual(self.compara(A, D, 1, 1)[0], [])
        v, _, _ = self.compara(A, D, 1, 2)
        self.assertTrue(any('eventos de step-up da atora: 1 (esperado 2)' in x for x in v), v)

    def test_logins_previstos_dos_alvos(self):
        # F6a-V-E: sessoes e desafios NOVOS de cada alvo = logins previstos; sem a declaracao, so listados
        A = foto_banco(user_sessions={}, auth_challenges={})
        D = foto_banco(user_sessions={'s1': linha({'user_id': ALVO_U}), 's2': linha({'user_id': ALVO_U})},
                       auth_challenges={'c1': linha({'user_id': ALVO_U}), 'c2': linha({'user_id': ALVO_U})})
        contas = {ALVO_U: 'f6av-x'}
        ok = vf.compara_banco(A, D, self.IDS_ADM, self.IDS_ALVO, 0, 0, {'f6av-x': 2}, contas)
        self.assertEqual(ok[0], [])
        self.assertEqual(len(ok[1]), 4)
        v = vf.compara_banco(A, D, self.IDS_ADM, self.IDS_ALVO, 0, 0, {'f6av-x': 3}, contas)[0]
        self.assertTrue(any('user_sessions novas do alvo f6av-x: 2 (esperado 3' in x for x in v), v)
        self.assertTrue(any('auth_challenges novas do alvo f6av-x: 2 (esperado 3' in x for x in v), v)
        v = vf.compara_banco(A, D, self.IDS_ADM, self.IDS_ALVO, 0, 0, {'f6av-y': 1}, contas)[0]
        self.assertTrue(any('do alvo f6av-x: 2 (esperado 0' in x for x in v), v)       # alvo nao declarado com login = violacao
        self.assertEqual(vf.compara_banco(A, D, self.IDS_ADM, self.IDS_ALVO, 0, 0)[0], [])   # sem declaracao: so listadas


# ---------------------------------------------------------------------------------------- anti-replay e reuso
class Base(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.d, 'frontend', 'f6a'))
        os.makedirs(os.path.join(self.d, 'frontend', 'contas'))
        b = contas_base()
        b['contas'][0].update({'uso': 'navegacao', 'senha': 's', 'org': 'a'})
        json.dump(b, open(os.path.join(self.d, 'frontend', 'semeadura.json'), 'w'))
        json.dump(plano_f6a(), open(os.path.join(self.d, 'frontend', 'f6a', 'semeadura.json'), 'w'))
        self.admin_mfa(ultimo=1000)

    def tearDown(self):
        shutil.rmtree(self.d)

    def admin_mfa(self, ultimo):
        json.dump({'totp': 'JBSWY3DPEHPK3PXP', 'estado': 'confirmado', 'ultimo_passo': ultimo},
                  open(os.path.join(self.d, 'frontend', 'contas', 'admin.json'), 'w'))

    def args(self, psql):
        class A:
            pass
        a = A()
        a.segredos, a.psql, a.bash = self.d, psql, None
        return a

    def reuso(self):
        return vf.le(os.path.join(self.d, 'frontend', 'f6a', 'reuso_admin.json'))


class AntiReplay(Base):
    def test_passo_ja_aceito_autoriza_reuso(self):
        a = self.args("printf 'V|last_used_step=1000\\nV|fatores_ativos=1\\n'")
        self.assertEqual(vf.anti_replay(a, vf.Cofre(self.d), vf.sf.bash_exe(None)), 0)
        self.assertEqual({k: self.reuso()[k] for k in ('passo', 'last_used_step')}, {'passo': 1000, 'last_used_step': 1000})

    def test_passo_ainda_nao_aceito_recusa(self):
        a = self.args("printf 'V|last_used_step=999\\nV|fatores_ativos=1\\n'")
        self.assertEqual(vf.anti_replay(a, vf.Cofre(self.d), vf.sf.bash_exe(None)), 1)
        self.assertIsNone(self.reuso())

    def test_fator_ausente_ou_duplicado_recusa(self):
        for n in ('0', '2'):
            a = self.args(f"printf 'V|last_used_step=2000\\nV|fatores_ativos={n}\\n'")
            self.assertEqual(vf.anti_replay(a, vf.Cofre(self.d), vf.sf.bash_exe(None)), 1)
            self.assertIsNone(self.reuso())


class AreaMem(cn.AreaBase):
    def __init__(self):
        self.valor = None

    def _abre(self):
        pass

    def _fecha(self):
        pass

    def _esvazia(self):
        self.valor = None

    def _texto(self, v):
        self.valor = v

    def _marca(self, nome):
        pass

    def _le_texto(self):
        return self.valor


class Reuso(Base):
    def entrega(self, **kw):
        area = AreaMem()
        vistos = []
        orig = area._texto
        area._texto = lambda v: (vistos.append(v), orig(v))[1]
        saida = []
        t = [1000 * 30.0]
        r = cn.entrega(self.d, 'admin', 'totp-reuso', 5, area, escreve=saida.append, relogio=lambda: t[0],
                       dorme=lambda s: t.__setitem__(0, t[0] + s))
        return r, vistos, saida

    def autoriza(self, passo=1000, aceito=1000):
        json.dump({'passo': passo, 'last_used_step': aceito}, open(os.path.join(self.d, 'frontend', 'f6a', 'reuso_admin.json'), 'w'))

    def test_entrega_o_codigo_do_passo_aceito_e_consome_a_autorizacao(self):
        self.autoriza()
        r, vistos, saida = self.entrega()
        self.assertEqual(r, 0)
        self.assertEqual(vistos, [jf.totp('JBSWY3DPEHPK3PXP', 1000)])
        self.assertIsNone(self.reuso())
        self.assertTrue(any('tipo=totp-reuso passo=1000' in x for x in saida) and not any(vistos[0] in x for x in saida))
        m = json.load(open(os.path.join(self.d, 'frontend', 'contas', 'admin.json')))
        self.assertEqual(m['ultimo_passo'], 1000)     # nenhum passo novo reservado

    def test_sem_autorizacao_recusa(self):
        with self.assertRaises(cn.Recusa):
            self.entrega()

    def test_entrega_depois_da_conferencia_recusa(self):
        self.autoriza(passo=999, aceito=1000)            # o ultimo entregue (1000) nao e o autorizado
        with self.assertRaises(cn.Recusa):
            self.entrega()
        self.assertIsNotNone(self.reuso())

    def test_passo_maior_que_o_aceito_recusa(self):
        self.autoriza(passo=1000, aceito=999)
        with self.assertRaises(cn.Recusa):
            self.entrega()

    def test_uso_unico(self):
        self.autoriza()
        self.assertEqual(self.entrega()[0], 0)
        with self.assertRaises(cn.Recusa):
            self.entrega()

    def test_conta_que_nao_e_de_navegacao_recusa(self):
        self.autoriza()
        with self.assertRaises(cn.Recusa):
            cn.entrega(self.d, 'f6av-mfa', 'totp-reuso', 5, AreaMem())


# ------------------------------------------------------------------------------------------- intencoes
class Intencoes(Base):
    def roda(self, linhas, esperado):
        class A:
            pass
        a = A()
        a.bash, a.desde, a.esperado = None, '2026-10-09T00:00:00Z', esperado
        arq = os.path.join(self.d, 'logs.txt')
        open(arq, 'w').write('\n'.join(linhas) + '\n')
        a.logs_cmd = f"cat '{arq.replace(os.sep, '/')}'"
        return vf.intencoes(a)

    L1 = 'platform-1 | 2026 INFO evento=mail.intencao tipo=NOTICE_MFA_RESET org=x entrega=nenhuma(local)'
    L2 = 'platform-1 | 2026 INFO evento=mail.intencao tipo=NOTICE_MFA_RESET_ADMIN_TARGET org=x entrega=nenhuma(local)'

    def test_conta_por_tipo_e_confere_o_esperado(self):
        self.assertEqual(self.roda([self.L1, 'outra linha'], ['NOTICE_MFA_RESET=1']), 0)

    def test_tipo_a_mais_ou_contagem_diferente(self):
        self.assertEqual(self.roda([self.L1, self.L2], ['NOTICE_MFA_RESET=1']), 1)
        self.assertEqual(self.roda([self.L1, self.L1], ['NOTICE_MFA_RESET=1']), 1)
        self.assertEqual(self.roda([], ['NOTICE_MFA_RESET=1']), 1)


# ------------------------------------------------------------------------------------- cofre, ritmo e fases
class CofreRitmo(Base):
    def test_codigo_reserva_o_passo_antes_e_nunca_repete(self):
        json.dump({'totp': 'JBSWY3DPEHPK3PXP', 'estado': 'confirmado', 'ultimo_passo': -1},
                  open(os.path.join(self.d, 'frontend', 'f6a', 'contas.tmp'), 'w'))
        os.makedirs(os.path.join(self.d, 'frontend', 'f6a', 'contas'), exist_ok=True)
        os.replace(os.path.join(self.d, 'frontend', 'f6a', 'contas.tmp'), os.path.join(self.d, 'frontend', 'f6a', 'contas', 'f6av-mfa.json'))
        t = [1000 * 30 + 5.0]
        c = vf.Cofre(self.d, relogio=lambda: t[0], dorme=lambda s: t.__setitem__(0, t[0] + s))
        k1 = c.codigo('f6av-mfa')
        p1 = c.mfa('f6av-mfa')['ultimo_passo']
        k2 = c.codigo('f6av-mfa')
        p2 = c.mfa('f6av-mfa')['ultimo_passo']
        self.assertGreater(p2, p1)
        self.assertNotEqual(k1, k2)

    def test_ritmo_entre_logins(self):
        class A:
            ritmo = 5.0
            teste_http = 'http://127.0.0.1:1'
            origem = 'http://x'
        dormiu = []
        api = vf.Api(A(), vf.Cofre(self.d), None, None, dorme=dormiu.append)
        api.ritmo()
        api.ritmo()
        self.assertEqual(len(dormiu), 1)
        self.assertGreater(dormiu[0], 4.0)


class FakeApi:
    """API simulada para as fases: devolve os status programados e registra as chamadas pelo Rel real."""

    def __init__(self, rel, st):
        self.rel, self.st, self.chamadas = rel, st, []

    def restaura(self, k):
        return k

    def me(self, s, k, esperado, pid):
        self.chamadas.append(('me', k))
        v = self.st.get(('me', k), esperado)
        v = v.pop(0) if isinstance(v, list) else v       # lista = respostas em sequencia
        self.rel.confere(pid, f'{k} /me -> {esperado}', v == esperado)

    def login_senha(self, k):
        self.chamadas.append(('login', k))
        st, status = self.st.get(('login', k), (200, 'mfa_required'))
        return types.SimpleNamespace(cookies={}, csrf='x', chave=k), st, {'status': status}

    def verifica(self, k, s):
        self.chamadas.append(('verifica', k))

    def cadastra(self, k, s):
        self.chamadas.append(('cadastra', k))

    def logout(self, s, k, pid):
        self.chamadas.append(('logout', k))

    def portal_ws(self, s, k, ids, pid):
        self.chamadas.append(('portal', k, tuple(sorted(ids))))


P = plano_f6a()
UID = {c['chave']: c['user_id'] for c in P['contas']}
G1 = P['concessoes'][0]['grant_id']


def sess(i, criada='2026-10-10T17:33:25.000000Z', ultimo='2026-10-10T17:42:22.846794Z', rev='-', expira='2026-10-11T05:33:25.000000Z',
         inat=30, estado=None):
    if estado is None:
        estado = 'revogada' if rev != '-' else 'expirada_inatividade'
    return {'id': f'5e55{i:04d}-0000-4000-8000-000000000000', 'criada': criada, 'ultimo_uso': ultimo, 'revogada_em': rev,
            'expira': expira, 'inatividade_min': inat, 'estado': estado}


def post(t, caminho, st=204):
    return (t, 'POST', f'/api/v1/o/{P["refs"]["org_a"]}{caminho}', st)


T_SUSP = '2026-10-10T19:36:51.064807459Z'
REV_SUSP = '2026-10-10T19:36:51.061335Z'


class Fases(Base):
    ORIGINAL = 'f0000000-0000-4000-8000-00000000000a'
    DESATIVADO = {'existe': '1', 'desativado': 'true', 'confirmado': 'true'}

    def conferir(self, fase, estados, st=None, concessoes=None, motivo='DISPOSITIVO_PERDIDO', fatores=None, original=ORIGINAL,
                 sessoes=None, acoes=None, revogada_em='-', renovadas=None, agora=None):
        """sessoes: {chave: [sessao, ...]} ou {chave: ([...], [...])} (tupla = respostas em sequencia).
        agora: relogio da conferencia (padrao: 60 s depois da ultima acao do gateway; sem acao, 2026-10-10T21:00:00Z)."""
        e0 = {}
        if original is not None:
            e0['fator_original'] = {'f6av-mfa': original}
        if renovadas is not None:
            e0['sessoes_renovadas'] = renovadas
        arq = os.path.join(self.d, 'frontend', 'f6a', 'estado_validacao.json')
        if e0:
            json.dump(e0, open(arq, 'w'))
        elif os.path.exists(arq):
            os.remove(arq)
        rel = vf.Rel(self.d, 'teste')
        api = FakeApi(rel, st or {})
        cofre = vf.Cofre(self.d)
        uid = {c['user_id']: c['chave'] for c in cofre.f6a['contas']}
        gid = {g['grant_id']: g['chave'] for g in cofre.f6a['concessoes']}
        orig = {n: getattr(vf, n) for n in ('estado_conta', 'estado_concessao', 'estado_fator', 'sessoes_conta', 'acoes_gateway',
                                            'revogada_em_concessao')}
        orig_gs, orig_ag = vf.Cofre.grava_sessao, getattr(vf, 'agora_utc', None)
        if agora is None:
            ts = [vf.instante(x[0]) for x in (acoes or [])]
            relogio = max(ts) + datetime.timedelta(seconds=60) if ts else vf.instante('2026-10-10T21:00:00Z')
        else:
            relogio = vf.instante(agora)
        vf.agora_utc = lambda: relogio
        seq, seq_s = {}, {}
        fatores = {k: list(v) if isinstance(v, list) else v for k, v in (fatores or {self.ORIGINAL: self.DESATIVADO}).items()}
        self.acoes_lidas = []

        def ef(bash, psql, org, u, fid):
            v = fatores.get(fid, {'existe': '0', 'desativado': 'false', 'confirmado': 'false'})
            return v.pop(0) if isinstance(v, list) else v

        def ec(bash, psql, org, u):
            k = uid[u]
            seq[k] = seq.get(k, 0) + 1
            e = estados[k]
            return e[min(seq[k], len(e)) - 1] if isinstance(e, list) else e

        def sc(bash, psql, org, u):
            k = uid[u]
            v = (sessoes or {}).get(k, [])
            if isinstance(v, tuple):
                seq_s[k] = seq_s.get(k, 0) + 1
                return copy.deepcopy(v[min(seq_s[k], len(v)) - 1])
            return copy.deepcopy(v)

        def ag(a, bash, desde):
            self.acoes_lidas.append(desde)
            return list(acoes or [])
        vf.estado_conta, vf.estado_fator, vf.sessoes_conta, vf.acoes_gateway = ec, ef, sc, ag
        vf.estado_concessao = lambda bash, psql, org, g: (concessoes or {})[gid[g]]
        vf.revogada_em_concessao = lambda bash, psql, org, g: revogada_em
        vf.Cofre.grava_sessao = lambda self_, k, s: None

        class A:
            psql = 'x'
            logs_gateway_cmd = None
        A.motivo = motivo
        try:
            vf.conferir(A(), fase, api, cofre, rel, None)
            return 0, api
        except jf.Stop as e:
            return str(e), api
        finally:
            for n, f in orig.items():
                setattr(vf, n, f)
            vf.Cofre.grava_sessao = orig_gs
            if orig_ag is not None:
                vf.agora_utc = orig_ag
            else:
                del vf.agora_utc
            rel.h.close()

    def parou(self, r, marca):
        self.assertIsInstance(r, str, f'a fase deveria PARAR em {marca}, mas passou')
        self.assertIn(marca, r)

    def log(self):
        return open(os.path.join(self.d, 'teste.txt'), encoding='utf-8').read()

    def estado_validacao(self):
        return vf.le(os.path.join(self.d, 'frontend', 'f6a', 'estado_validacao.json')) or {}

    # ------------------------------------------------------------------ suspensao
    def susp(self, e=None, ses=None, acoes=None, st=None, agora=None):
        k = 'f6av-suspensao'
        return self.conferir('suspensao', {k: e or {'status': '3', 'sessoes_utilizaveis': '0'}},
                             st or {('login', k): (401, None)},
                             sessoes={k: ses if ses is not None else [sess(1, rev=REV_SUSP)]},
                             acoes=acoes if acoes is not None else [post(T_SUSP, f'/users/{UID[k]}/suspend')], agora=agora)

    def test_suspensao_sessao_ja_expirada_prova_pelo_banco_sem_requisicao(self):
        ok, api = self.susp()
        self.assertEqual(ok, 0, ok)
        self.assertEqual(api.chamadas, [('login', 'f6av-suspensao')])      # nenhum /me: o 401 nao discriminaria
        self.assertIn('NAO EXECUTADA S.02', self.log())
        self.assertIn('OK    S.01b', self.log())

    def test_suspensao_sessao_utilizavel_na_acao_faz_a_prova_401(self):
        ok, api = self.susp(ses=[sess(1, ultimo='2026-10-10T19:30:00.000000Z', rev=REV_SUSP)])
        self.assertEqual(ok, 0, ok)
        self.assertEqual(api.chamadas, [('me', 'f6av-suspensao'), ('login', 'f6av-suspensao')])
        r, _ = self.susp(ses=[sess(1, ultimo='2026-10-10T19:30:00.000000Z', rev=REV_SUSP)], st={('me', 'f6av-suspensao'): 200})
        self.parou(r, 'S.02')

    def test_suspensao_sessao_expirada_entre_a_acao_e_a_consulta_nao_vira_prova(self):
        # utilizavel na acao (19:36:51; inatividade ate 20:00), mas a consulta e depois do prazo: sem requisicao
        ses = [sess(1, ultimo='2026-10-10T19:30:00.000000Z', rev=REV_SUSP)]
        ok, api = self.susp(ses=ses, agora='2026-10-10T20:05:00Z')
        self.assertEqual(ok, 0, ok)
        self.assertEqual(api.chamadas, [('login', 'f6av-suspensao')])
        self.assertIn('NAO EXECUTADA S.02', self.log())
        self.assertIn('nao distinguiria revogacao de expiracao', self.log())
        ok, api = self.susp(ses=[sess(1, ultimo='2026-10-10T19:30:00.000000Z', rev=REV_SUSP, expira='2026-10-10T19:40:00.000000Z')],
                            agora='2026-10-10T19:39:30Z')      # expiracao absoluta dentro da margem de 60 s
        self.assertEqual(ok, 0, ok)
        self.assertEqual(api.chamadas, [('login', 'f6av-suspensao')])
        ok, api = self.susp(ses=ses, agora='2026-10-10T19:58:59Z')   # controle: dentro dos prazos -> a prova e feita
        self.assertEqual(ok, 0, ok)
        self.assertEqual(api.chamadas, [('me', 'f6av-suspensao'), ('login', 'f6av-suspensao')])

    def test_suspensao_expirada_sem_revoked_at_nao_prova_revogacao(self):
        r, _ = self.susp(ses=[sess(1, estado='expirada_inatividade')])
        self.parou(r, 'S.01b')

    def test_suspensao_revogacao_fora_do_instante_da_acao(self):
        for rev in ('2026-10-10T19:00:00.000000Z', '2026-10-10T19:36:53.000000Z', '2026-10-10T19:36:45.000000Z'):
            r, _ = self.susp(ses=[sess(1, rev=rev)])
            self.parou(r, 'S.01b')

    def test_suspensao_sem_acao_ou_com_duas_ou_com_erro_no_gateway(self):
        k = 'f6av-suspensao'
        for acoes in ([], [post(T_SUSP, f'/users/{UID[k]}/suspend')] * 2,
                      [post(T_SUSP, f'/users/{UID[k]}/suspend'), post(T_SUSP, f'/users/{UID[k]}/suspend', 409)],
                      [post(T_SUSP, f'/users/{UID["f6av-mfa"]}/suspend')]):
            r, _ = self.susp(acoes=acoes)
            self.parou(r, 'S.01a')

    def test_suspensao_estado_e_login(self):
        r, _ = self.susp(e={'status': '2', 'sessoes_utilizaveis': '0'})
        self.parou(r, 'S.01')
        r, _ = self.susp(e={'status': '3', 'sessoes_utilizaveis': '1'})
        self.parou(r, 'S.01')
        r, _ = self.susp(st={('login', 'f6av-suspensao'): (200, 'mfa_required')})
        self.parou(r, 'S.03')

    # ------------------------------------------------------------------ reativacao
    def reat(self, ses=None, acoes=None):
        k = 'f6av-reativacao'
        u = UID[k]
        return self.conferir('reativacao', {k: {'status': '2', 'sessoes_utilizaveis': '0'}},
                             sessoes={k: ses if ses is not None else [sess(2, rev='2026-10-10T19:37:08.825572Z')]},
                             acoes=acoes if acoes is not None else [post('2026-10-10T19:37:08.828860260Z', f'/users/{u}/suspend'),
                                                                     post('2026-10-10T19:37:20.942786862Z', f'/users/{u}/reactivate')])

    def test_reativacao_ligada_a_suspensao_e_novo_login(self):
        ok, api = self.reat()
        self.assertEqual(ok, 0, ok)
        self.assertEqual([c[0] for c in api.chamadas], ['login', 'verifica', 'me', 'logout'])
        self.assertIn('NAO EXECUTADA R.02', self.log())

    def test_reativacao_antes_da_suspensao_ou_revogacao_pela_reativacao(self):
        u = UID['f6av-reativacao']
        r, _ = self.reat(acoes=[post('2026-10-10T19:37:20.9Z', f'/users/{u}/suspend'), post('2026-10-10T19:37:08.8Z', f'/users/{u}/reactivate')])
        self.parou(r, 'R.01c')
        r, _ = self.reat(ses=[sess(2, rev='2026-10-10T19:37:20.930000Z')])
        self.parou(r, 'R.01d')
        r, _ = self.reat(acoes=[post('2026-10-10T19:37:08.8Z', f'/users/{u}/suspend')])
        self.parou(r, 'R.01b')

    # ------------------------------------------------------------------ renovar
    def renovar(self, emfa=None, ecli=None, conc=None, novas=True, original=ORIGINAL):
        mfa_old = sess(3, criada='2026-10-10T17:33:35.000000Z', ultimo='2026-10-10T17:42:23.847921Z')
        cli_old = sess(4, criada='2026-10-10T17:33:40.000000Z', ultimo='2026-10-10T17:42:25.580869Z', inat=15)
        mfa_new = sess(5, criada='2026-10-10T21:00:00.000000Z', ultimo='2026-10-10T21:00:01.000000Z', estado='utilizavel')
        cli_new = sess(6, criada='2026-10-10T21:00:10.000000Z', ultimo='2026-10-10T21:00:11.000000Z', inat=15, estado='utilizavel')
        c2 = {'status': '2', 'revogada': 'false', 'revogada_por': '-'}
        return self.conferir('renovar', {'f6av-mfa': emfa or {'status': '2', 'fator_ativo': '1', 'fator_ativo_id': self.ORIGINAL, 'eventos_reset': '0'},
                                         'f6av-cliente': ecli or {'status': '2', 'fator_ativo': '1'}},
                             concessoes=conc or {'f6av-c1': c2, 'f6av-c2': c2}, original=original,
                             sessoes={'f6av-mfa': ([mfa_old], [mfa_old, mfa_new] if novas else [mfa_old]),
                                      'f6av-cliente': ([cli_old], [cli_old, cli_new])})

    def test_renovar_abre_sessoes_novas_e_registra_o_prazo(self):
        ok, api = self.renovar()
        self.assertEqual(ok, 0, ok)
        self.assertEqual(api.chamadas, [('login', 'f6av-mfa'), ('verifica', 'f6av-mfa'), ('me', 'f6av-mfa'),
                                        ('login', 'f6av-cliente'), ('verifica', 'f6av-cliente'), ('me', 'f6av-cliente'),
                                        ('portal', 'f6av-cliente', ('w2', 'w3'))])
        r = self.estado_validacao()['sessoes_renovadas']
        self.assertEqual(r['f6av-mfa']['utilizavel_ate'], '2026-10-10T21:30:01Z')        # 30 min de inatividade
        self.assertEqual(r['f6av-cliente']['utilizavel_ate'], '2026-10-10T21:15:11Z')    # 15 min (externa)
        self.assertEqual(r['f6av-mfa']['sessao_id'], sess(5)['id'])
        self.assertEqual(self.estado_validacao()['fator_original'], {'f6av-mfa': self.ORIGINAL})

    def test_renovar_recusa_antes_de_qualquer_login(self):
        for kw, marca in (({'emfa': {'status': '2', 'fator_ativo': '1', 'fator_ativo_id': 'outro', 'eventos_reset': '0'}}, 'V.01'),
                          ({'emfa': {'status': '2', 'fator_ativo': '0', 'fator_ativo_id': '-', 'eventos_reset': '1'}}, 'V.01'),
                          ({'ecli': {'status': '3', 'fator_ativo': '1'}}, 'V.02'),
                          ({'conc': {'f6av-c1': {'status': '4', 'revogada': 'true', 'revogada_por': ADM_M},
                                     'f6av-c2': {'status': '2', 'revogada': 'false', 'revogada_por': '-'}}}, 'V.03'),
                          ({'original': None}, 'fator original nao registrado')):
            r, api = self.renovar(**kw)
            self.parou(r, marca)
            self.assertEqual(api.chamadas, [], kw)

    def test_renovar_sem_sessao_nova_utilizavel(self):
        r, _ = self.renovar(novas=False)
        self.parou(r, 'V.07')

    # ------------------------------------------------------------------ mfa
    def mfa(self, depois_id='f0000000-0000-4000-8000-00000000000b', fatores=None, original=ORIGINAL, renovada=None, ses=None, acoes=None,
            antes_kw=None, agora=None):
        cofre = vf.Cofre(self.d)
        adm = cofre.admin['user_id']
        antes = {'status': '2', 'fator_ativo': '0', 'codigos_ativos': '0', 'sessoes_utilizaveis': '0', 'eventos_reset': '1',
                 'reset_motivos': f'1@{adm}', 'fator_desativado': '1', 'fator_ativo_id': '-', **(antes_kw or {})}
        depois = {**antes, 'fator_ativo': '1', 'fator_ativo_id': depois_id}
        seq = [(200, 'mfa_enrollment_required'), (200, 'mfa_required')]
        nova = sess(5, criada='2026-10-10T21:00:00.000000Z', ultimo='2026-10-10T21:00:01.000000Z', rev='2026-10-10T21:05:00.100000Z')
        ren = {'sessao_id': nova['id'], 'criada': nova['criada'], 'utilizavel_ate': '2026-10-10T21:30:01Z'}

        class St(dict):
            def get(self, k, d=None):
                if k == ('login', 'f6av-mfa'):
                    return seq.pop(0)
                return dict.get(self, k, d)
        return self.conferir('mfa', {'f6av-mfa': [antes, depois]}, St({('me', 'f6av-mfa'): [401, 200]}), fatores=fatores, original=original,
                             renovadas={'f6av-mfa': ren} if renovada is None else renovada,
                             sessoes={'f6av-mfa': ses if ses is not None else [sess(3, rev='2026-10-10T21:05:00.100000Z'), nova]},
                             acoes=acoes if acoes is not None else [post('2026-10-10T21:05:00.150000000Z', f'/users/{UID["f6av-mfa"]}/mfa/reset')],
                             agora=agora)

    def test_mfa_motivo_e_ator(self):
        ok, api = self.mfa()
        self.assertEqual(ok, 0, ok)
        self.assertEqual([c[0] for c in api.chamadas], ['me', 'login', 'cadastra', 'me', 'logout', 'login', 'verifica', 'logout'])
        for errado in ({'reset_motivos': '3@' + ADM_U}, {'reset_motivos': '1@outro'}, {'eventos_reset': '2'}, {'sessoes_utilizaveis': '1'}):
            r, _ = self.mfa(antes_kw=errado)
            self.parou(r, 'M.01')

    def test_mfa_sessao_renovada_ligada_ao_reset(self):
        r, _ = self.mfa(renovada={})
        self.parou(r, 'sessao renovada nao registrada')
        nova_velha = sess(5, criada='2026-10-10T20:00:00.000000Z', ultimo='2026-10-10T20:00:01.000000Z', rev='2026-10-10T21:05:00.100000Z')
        r, _ = self.mfa(ses=[nova_velha])                    # nao era utilizavel no reset (inativa desde 20:00)
        self.parou(r, 'M.01d')
        r, _ = self.mfa(ses=[sess(5, criada='2026-10-10T21:00:00.000000Z', ultimo='2026-10-10T21:00:01.000000Z', rev='2026-10-10T21:09:00.000000Z')])
        self.parou(r, 'M.01d')                               # revogada, mas nao pelo reset
        r, _ = self.mfa(acoes=[])
        self.parou(r, 'M.01c')

    def renovada(self, ultimo='2026-10-10T21:00:01.000000Z', expira='2026-10-11T05:33:25.000000Z'):
        return sess(5, criada='2026-10-10T21:00:00.000000Z', ultimo=ultimo, rev='2026-10-10T21:05:00.100000Z', expira=expira)

    def test_m02_expiracao_absoluta_entre_o_reset_e_a_consulta_para(self):
        # reproducao da revisao: valida no reset (21:05), expiracao absoluta 21:06, conferencia depois
        r, api = self.mfa(ses=[self.renovada(expira='2026-10-10T21:06:00.000000Z')], agora='2026-10-10T21:07:00Z')
        self.parou(r, 'M.02')
        self.assertNotIn(('me', 'f6av-mfa'), api.chamadas)
        self.assertEqual([c for c in api.chamadas if c[0] == 'login'], [])

    def test_m02_inatividade_entre_o_reset_e_a_consulta_para(self):
        # inatividade ate 21:10 (ultimo uso 20:40): valida no reset; consulta as 21:10:30 e as 21:09:30 (margem de 60 s)
        for agora in ('2026-10-10T21:10:30Z', '2026-10-10T21:09:30Z'):
            r, api = self.mfa(ses=[self.renovada(ultimo='2026-10-10T20:40:00.000000Z')], agora=agora)
            self.parou(r, 'M.02')
            self.assertNotIn(('me', 'f6av-mfa'), api.chamadas)

    def test_m02_controle_dentro_dos_prazos(self):
        ok, api = self.mfa(ses=[self.renovada(ultimo='2026-10-10T20:40:00.000000Z')], agora='2026-10-10T21:08:59Z')
        self.assertEqual(ok, 0, ok)
        self.assertEqual(api.chamadas[0], ('me', 'f6av-mfa'))

    def test_r1_mfa_novo_fator_e_original_desativado(self):
        ok, api = self.mfa()
        self.assertEqual(ok, 0)

    def test_r1_mfa_recusa_reutilizar_o_id_original(self):
        r, _ = self.mfa(depois_id=self.ORIGINAL)
        self.parou(r, 'M.08')

    def test_r1_mfa_recusa_original_reativado_depois_do_novo_cadastro(self):
        r, _ = self.mfa(fatores={self.ORIGINAL: [self.DESATIVADO, {'existe': '1', 'desativado': 'false', 'confirmado': 'true'}]})
        self.parou(r, 'M.09')

    def test_r1_mfa_recusa_original_nao_desativado_pelo_reset(self):
        r, _ = self.mfa(fatores={self.ORIGINAL: {'existe': '1', 'desativado': 'false', 'confirmado': 'true'}})
        self.parou(r, 'M.01b')

    def test_r1_mfa_exige_o_original_registrado_antes(self):
        r, _ = self.mfa(original=None)
        self.parou(r, 'fator original nao registrado')

    # ------------------------------------------------------------------ inicial e step-up
    def test_r1_inicial_registra_o_fator_original(self):
        def e(fid):
            return {'status': '2', 'sessoes_utilizaveis': '1', 'fator_ativo': '1', 'codigos_ativos': '0', 'eventos_reset': '0', 'fator_ativo_id': fid}
        for k in ('f6av-suspensao', 'f6av-reativacao', 'f6av-mfa'):
            os.makedirs(os.path.join(self.d, 'frontend', 'f6a', 'contas'), exist_ok=True)
            json.dump({'totp': 'X', 'estado': 'confirmado', 'codigos': []}, open(os.path.join(self.d, 'frontend', 'f6a', 'contas', f'{k}.json'), 'w'))
        c2 = {'status': '2', 'revogada': 'false', 'revogada_por': '-'}
        ok, _ = self.conferir('inicial', {'f6av-suspensao': e('s1'), 'f6av-reativacao': e('r1'), 'f6av-mfa': e('m1'),
                                          'f6av-cliente': {'status': '2', 'sessoes_utilizaveis': '1', 'fator_ativo': '1'}},
                              concessoes={'f6av-c1': c2, 'f6av-c2': c2}, original=None)
        self.assertEqual(ok, 0)
        self.assertEqual(self.estado_validacao().get('fator_original'), {'f6av-suspensao': 's1', 'f6av-reativacao': 'r1', 'f6av-mfa': 'm1'})
        r, _ = self.conferir('inicial', {'f6av-suspensao': {**e('s1'), 'sessoes_utilizaveis': '0'}, 'f6av-reativacao': e('r1'), 'f6av-mfa': e('m1'),
                                         'f6av-cliente': {'status': '2', 'sessoes_utilizaveis': '1', 'fator_ativo': '1'}},
                             concessoes={'f6av-c1': c2, 'f6av-c2': c2}, original=None)
        self.parou(r, 'I.01')

    def test_r1_stepup_negado_exige_o_mesmo_fator(self):
        os.makedirs(os.path.join(self.d, 'frontend', 'f6a', 'contas'), exist_ok=True)
        json.dump({'totp': 'X', 'estado': 'confirmado', 'codigos': []}, open(os.path.join(self.d, 'frontend', 'f6a', 'contas', 'f6av-mfa.json'), 'w'))
        base = {'status': '2', 'fator_ativo': '1', 'sessoes_revogadas': '0', 'codigos_ativos': '0', 'eventos_reset': '0'}
        util = [sess(3, ultimo='2026-10-10T19:30:00Z', estado='utilizavel')]
        ok, api = self.conferir('stepup-negado', {'f6av-mfa': {**base, 'fator_ativo_id': self.ORIGINAL}}, sessoes={'f6av-mfa': util})
        self.assertEqual(ok, 0)
        self.assertEqual(api.chamadas, [('me', 'f6av-mfa')])
        r, _ = self.conferir('stepup-negado', {'f6av-mfa': {**base, 'fator_ativo_id': 'outro-fator'}}, sessoes={'f6av-mfa': util})
        self.parou(r, 'N.01')
        ok, api = self.conferir('stepup-negado', {'f6av-mfa': {**base, 'fator_ativo_id': self.ORIGINAL}}, sessoes={'f6av-mfa': [sess(3)]})
        self.assertEqual(ok, 0)
        self.assertEqual(api.chamadas, [])                   # sessao expirada: N.02 NAO executada (inconclusiva)
        self.assertIn('NAO EXECUTADA N.02', self.log())

    # ------------------------------------------------------------------ revogacao
    def revog(self, c1=None, c2=None, ses=None, revogada_em='2026-10-10T21:06:00.100000Z', acoes=None, renovada=True, agora=None):
        cofre = vf.Cofre(self.d)
        adm_m = cofre.admin['membership_id']
        nova = sess(6, criada='2026-10-10T21:00:10.000000Z', ultimo='2026-10-10T21:00:11.000000Z', inat=15, estado='utilizavel')
        ren = {'f6av-cliente': {'sessao_id': nova['id'], 'criada': nova['criada'], 'utilizavel_ate': '2026-10-10T21:15:11Z'}} if renovada else {}
        return self.conferir('revogacao', {}, concessoes={'f6av-c1': c1 or {'status': '4', 'revogada': 'true', 'revogada_por': adm_m},
                                                          'f6av-c2': c2 or {'status': '2', 'revogada': 'false', 'revogada_por': '-'}},
                             sessoes={'f6av-cliente': ses if ses is not None else [sess(4, inat=15), nova]}, revogada_em=revogada_em,
                             acoes=acoes if acoes is not None else [post('2026-10-10T21:06:00.150000000Z', f'/client-grants/{G1}/revoke')],
                             renovadas=ren, agora=agora)

    def test_revogacao_controle_intacto(self):
        ok, api = self.revog()
        self.assertEqual(ok, 0, ok)
        self.assertIn(('portal', 'f6av-cliente', ('w3',)), api.chamadas)
        r, _ = self.revog(c2={'status': '4', 'revogada': 'false', 'revogada_por': '-'})
        self.parou(r, 'G.02')
        r, _ = self.revog(c1={'status': '4', 'revogada': 'true', 'revogada_por': 'outro'})
        self.parou(r, 'G.01')

    def test_revogacao_ligada_e_sessao_renovada_utilizavel(self):
        r, _ = self.revog(revogada_em='2026-10-10T21:00:00.000000Z')
        self.parou(r, 'G.01b')
        r, _ = self.revog(acoes=[])
        self.parou(r, 'G.01a')
        r, api = self.revog(ses=[sess(4, inat=15), sess(6, criada='2026-10-10T21:00:10.000000Z', ultimo='2026-10-10T21:00:11.000000Z', inat=15)])
        self.parou(r, 'G.03')
        self.assertEqual(api.chamadas, [])                   # expirada: nenhuma requisicao ao portal
        r, _ = self.revog(renovada=False)
        self.parou(r, 'sessao renovada nao registrada')
        # utilizavel no banco, mas o prazo de inatividade (21:15:11) cai dentro da margem da consulta: sem portal
        r, api = self.revog(agora='2026-10-10T21:14:30Z')
        self.parou(r, 'G.03')
        self.assertEqual(api.chamadas, [])
        ok, api = self.revog(agora='2026-10-10T21:14:00Z')   # controle
        self.assertEqual(ok, 0, ok)


class Sessoes(unittest.TestCase):
    def test_utilizavel_em(self):
        s = sess(1, ultimo='2026-10-10T17:42:22.000000Z', expira='2026-10-11T05:00:00.000000Z')
        t = vf.instante
        self.assertTrue(vf.utilizavel_em(s, t('2026-10-10T18:12:21Z')))
        self.assertFalse(vf.utilizavel_em(s, t('2026-10-10T18:12:22Z')))          # 30 min depois do ultimo uso
        self.assertFalse(vf.utilizavel_em({**s, 'inatividade_min': 15}, t('2026-10-10T17:57:30Z')))
        self.assertFalse(vf.utilizavel_em({**s, 'revogada_em': '2026-10-10T17:50:00Z'}, t('2026-10-10T17:55:00Z')))
        self.assertTrue(vf.utilizavel_em({**s, 'revogada_em': '2026-10-10T17:50:00Z'}, t('2026-10-10T17:45:00Z')))
        self.assertFalse(vf.utilizavel_em({**s, 'expira': '2026-10-10T17:44:00Z'}, t('2026-10-10T17:45:00Z')))

    def test_ligacao_a_acao(self):
        t = vf.instante('2026-10-10T19:36:51.064807459Z')
        self.assertTrue(vf.ligada('2026-10-10T19:36:51.061335Z', t))
        self.assertTrue(vf.ligada('2026-10-10T19:36:46.100000Z', t))
        self.assertFalse(vf.ligada('2026-10-10T19:36:46.000000Z', t))
        self.assertFalse(vf.ligada('2026-10-10T19:36:52.100000Z', t))
        self.assertFalse(vf.ligada('-', t))

    def test_prazo_igual_ao_da_platform(self):
        yml = open(os.path.join(AQUI, '..', '..', '..', 'api', 'nekkora-platform', 'src', 'main', 'resources', 'application.yml'), encoding='utf-8').read()
        self.assertIn(f"internal-inactivity: {vf.INATIVIDADE_MIN['interna']}m", yml)
        self.assertIn(f"external-inactivity: {vf.INATIVIDADE_MIN['externa']}m", yml)

    def test_acoes_do_log_do_gateway(self):
        d = tempfile.mkdtemp()
        try:
            f = os.path.join(d, 'g.txt')
            open(f, 'w').write('gateway-1  | 2026-10-10T19:36:51.064807459Z 10.252.95.1 "POST /api/v1/o/o1/users/u1/suspend HTTP/1.1" 204 0 0.01\n'
                               'gateway-1  | 2026-10-10T19:36:29.751042574Z 10.252.95.1 "GET /api/v1/o/o1/members?limit=25 HTTP/1.1" 200 9 0.1\n'
                               'gateway-1  | ruido\n')
            a = types.SimpleNamespace(logs_gateway_cmd=f"cat '{f}' # {{desde}}")
            r = vf.acoes_gateway(a, vf.sf.bash_exe(None), '2026-10-10T17:00:00Z')
            self.assertEqual(r, [('2026-10-10T19:36:51.064807459Z', 'POST', '/api/v1/o/o1/users/u1/suspend', 204)])
            a.logs_gateway_cmd = 'exit 3'
            with self.assertRaises(jf.Stop):
                vf.acoes_gateway(a, vf.sf.bash_exe(None), 'x')
        finally:
            shutil.rmtree(d)


class Cli(unittest.TestCase):
    def test_recusas_antes_de_qualquer_acao(self):
        d = tempfile.mkdtemp()
        try:
            os.makedirs(os.path.join(d, 'frontend', 'f6a'))
            json.dump(contas_base(), open(os.path.join(d, 'frontend', 'semeadura.json'), 'w'))
            json.dump(plano_f6a(), open(os.path.join(d, 'frontend', 'f6a', 'semeadura.json'), 'w'))
            self.assertEqual(vf.main(['conferir', 'outra-fase', '--segredos', d]), 2)
            self.assertEqual(vf.main(['conferir', '--segredos', d]), 2)
            self.assertEqual(vf.main(['comparar', 'a', '--segredos', d]), 2)
            self.assertEqual(vf.main(['intencoes', '--segredos', d]), 2)
            self.assertEqual(vf.main(['preparar', 'x', '--segredos', d]), 2)
            self.assertEqual(vf.main(['conferir', 'inicial', '--segredos', d]), 2)   # sem --evid/--openapi/--psql
        finally:
            shutil.rmtree(d)


if __name__ == '__main__':
    unittest.main()
