#!/usr/bin/env python3
"""Testes LOCAIS do inventario B12 (PROPOSTA P4-R1): docker FALSO em memoria, nenhum recurso Docker criado ou lido.
python -m unittest test_inventario_b12 -v   (no diretorio testes/)"""
import copy
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import inventario_b12 as inv  # noqa: E402

AMB = 'ensaio-016a'
ERRO_P4 = ('template parsing error: template: :1:15: executing "" at <.Config.Labels>: '
           'map has no entry for key "Labels"')


def h(c):
    return (c * 64)[:64]


def mundo_base():
    """Estado sintetico parecido com o do P4: ambiente com container/rede/volume nomeado/anonimo/imagem, mais protegidos
    com e sem rotulos (imagens oficiais sem Config.Labels, volume com Labels nulo)."""
    redis_anon = h('4')
    return {
        'containers': {
            h('a'): {'Id': h('a'), 'Name': '/ensaio-redis', 'Image': 'sha256:' + h('8'), 'RestartCount': 0,
                     'State': {'Status': 'running', 'StartedAt': '2026-09-29T22:58:07Z'},
                     'Config': {'Labels': {'nekkora.homologacao': AMB}},
                     'Mounts': [{'Type': 'volume', 'Name': redis_anon, 'Destination': '/data'}]},
            h('b'): {'Id': h('b'), 'Name': '/nekkora-postgres', 'Image': 'sha256:' + h('7'), 'RestartCount': 0,
                     'State': {'Status': 'running', 'StartedAt': '2026-09-29T19:54:48Z'},
                     'Config': {'Labels': {'com.docker.compose.project': 'nekkora-platform'}}, 'Mounts': []},
            h('c'): {'Id': h('c'), 'Name': '/nk016-smoke', 'Image': 'sha256:' + h('7'), 'RestartCount': 0,
                     'State': {'Status': 'exited', 'StartedAt': '2026-09-27T23:47:17Z'},
                     'Config': {'Labels': None}, 'Mounts': []},
        },
        'redes': {
            h('d'): {'Id': h('d'), 'Name': 'ensaio_interna', 'IPAM': {'Config': [{'Subnet': '10.252.80.0/24'}]},
                     'Labels': {'nekkora.homologacao': AMB}},
            h('e'): {'Id': h('e'), 'Name': 'bridge', 'IPAM': {'Config': [{'Subnet': '172.17.0.0/16'}]}, 'Labels': {}},
        },
        'volumes': {
            'ensaio_pgdata': {'Name': 'ensaio_pgdata', 'CreatedAt': '2026-09-29T22:58:06Z', 'Driver': 'local',
                              'Labels': {'nekkora.homologacao': AMB}},
            redis_anon: {'Name': redis_anon, 'CreatedAt': '2026-09-29T22:58:07Z', 'Driver': 'local',
                         'Labels': {'com.docker.volume.anonymous': ''}},
            'nekkora-platform_data': {'Name': 'nekkora-platform_data', 'CreatedAt': '2026-09-25T21:03:29Z',
                                      'Driver': 'local', 'Labels': None},
        },
        'imagens': {
            'sha256:' + h('1'): {'Id': 'sha256:' + h('1'), 'RepoTags': ['nekkora-homologacao-platform:327c4d4'],
                                 'Config': {'Labels': {'nekkora.homologacao': AMB}}},
            'sha256:' + h('7'): {'Id': 'sha256:' + h('7'), 'RepoTags': ['postgres:16-alpine'], 'Config': {}},
            'sha256:' + h('8'): {'Id': 'sha256:' + h('8'), 'RepoTags': ['redis:7-alpine'], 'Config': {'Env': []}},
        },
    }


class DockerFalso:
    """Responde aos comandos de listagem e inspecao a partir de um 'mundo'. Ganchos: falha de listagem, falha/saida
    crua de inspecao por ID, e objeto que SOME depois da listagem."""

    def __init__(self, mundo, listagem_falha=(), inspecao=None, some=()):
        self.m = mundo
        self.listagem_falha = set(listagem_falha)
        self.inspecao = inspecao or {}          # id -> (rc, stdout, stderr)
        self.some = set(some)                   # ids removidos logo apos a 1a listagem
        self.listou = set()
        self.chamadas = []

    def _classe(self, cmd):
        for c, base in inv.LISTAR.items():
            if cmd == base:
                return 'listar', c
        for c, base in inv.INSPECIONAR.items():
            if cmd[:len(base)] == base:
                return 'inspecionar', c
        raise AssertionError(cmd)

    def __call__(self, cmd):
        self.chamadas.append(cmd)
        op, c = self._classe(cmd)
        if op == 'listar':
            if c in self.listagem_falha:
                return 1, '', 'Cannot connect to the Docker daemon'
            vivos = {k: v for k, v in self.m[c].items() if not (k in self.some and c in self.listou)}
            self.listou.add(c)
            if c == 'containers':
                return 0, ''.join(f"{k}\t{(v.get('Config') or {}).get('Labels', {}) and (v['Config']['Labels'] or {}).get('nekkora.homologacao', '')}\n"
                                  for k, v in vivos.items()), ''
            return 0, ''.join(k + '\n' for k in vivos), ''
        oid = cmd[-1]
        if oid in self.inspecao:
            return self.inspecao[oid]
        if oid in self.some:
            nome = {'containers': 'container', 'redes': 'network', 'volumes': 'volume', 'imagens': 'image'}[c]
            return 1, '', f'Error response from daemon: No such {nome}: {oid}'
        return 0, json.dumps([self.m[c][oid]]), ''


def coleta(mundo, **kw):
    return inv.coletar(DockerFalso(mundo, **kw))


class TestInventarioB12(unittest.TestCase):
    def avalia(self, antes, depois):
        cod, rel = inv.comparar(antes, depois, AMB)
        self.rel = '\n'.join(rel)
        return cod

    # 1. rotulos presentes; 2. rotulos ausentes ou nulos -> completo, sem erro
    def test_01_rotulos_presentes_e_ausentes_completo_identico(self):
        m = mundo_base()
        self.assertEqual(self.avalia(coleta(m), coleta(m)), 0, self.rel)
        self.assertIn('B12 OK', self.rel)
        self.assertRegex(self.rel, r'objetos sem rotulo \(ausencia legitima, lidos por completo\): [1-9]')

    def test_02_imagem_sem_config_labels_nao_e_erro(self):
        objs, problemas = inv.normalizar(coleta(mundo_base()), AMB)
        self.assertEqual(problemas, [])
        r = objs[('imagens', 'sha256:' + h('7'))]
        self.assertEqual((r['estado'], r['rotulos']), ('OK', {}))
        self.assertEqual(objs[('volumes', 'nekkora-platform_data')]['rotulos'], {})   # Labels null

    # 3. falha de inspecao (o erro EXATO do P4) -> INCONCLUSIVO, nunca OK, mesmo com antes == depois
    def test_03_falha_de_inspecao_igual_dos_dois_lados_e_inconclusivo(self):
        m = mundo_base()
        falha = {'sha256:' + h('7'): (1, '\n', ERRO_P4)}
        cod = self.avalia(coleta(m, inspecao=falha), coleta(m, inspecao=falha))
        self.assertEqual(cod, 3, self.rel)
        self.assertIn('leitura incompleta', self.rel)

    def test_03b_codigo_nao_zero_com_json_valido_na_saida_e_inconclusivo(self):
        m = mundo_base()
        img = 'sha256:' + h('8')
        parcial = {img: (1, json.dumps([m['imagens'][img]]), 'erro depois de escrever a saida')}
        self.assertEqual(self.avalia(coleta(m), coleta(m, inspecao=parcial)), 3, self.rel)

    # 4. saida vazia ou incompleta
    def test_04_saida_vazia_truncada_ou_multipla(self):
        m = mundo_base()
        for saida in ('', '[{"Id": "x"', json.dumps([m['redes'][h('e')], m['redes'][h('e')]]), '[]'):
            with self.subTest(saida=saida[:20]):
                ruim = coleta(m, inspecao={h('e'): (0, saida, '')})
                self.assertEqual(self.avalia(coleta(m), ruim), 3, self.rel)

    def test_04b_listado_e_nao_inspecionado(self):
        m = mundo_base()
        bruto = [x for x in coleta(m) if not (x['tipo'] == 'objeto' and x['id'] == h('e'))]
        self.assertEqual(self.avalia(coleta(m), bruto), 3, self.rel)
        self.assertIn('listado e nao inspecionado', self.rel)

    # 5. objeto que some entre listagem e inspecao
    def test_05_container_do_ambiente_some_rm_e_ok(self):
        m = mundo_base()
        m['containers'][h('f')] = {'Id': h('f'), 'Name': '/ensaio-ferramentas-run', 'Image': 'x', 'RestartCount': 0,
                                   'State': {'Status': 'running', 'StartedAt': 't'},
                                   'Config': {'Labels': {'nekkora.homologacao': AMB}}, 'Mounts': []}
        depois = coleta(m, some={h('f')})
        objs, _ = inv.normalizar(depois, AMB)
        self.assertEqual(objs[('containers', h('f'))]['estado'], 'DESAPARECIDO')
        self.assertEqual(self.avalia(coleta(mundo_base()), depois), 0, self.rel)
        self.assertIn('desaparecidos durante a coleta (esperado', self.rel)

    def test_05b_protegido_some_durante_a_coleta_e_falha(self):
        m = mundo_base()
        self.assertEqual(self.avalia(coleta(m), coleta(m, some={h('c')})), 1, self.rel)
        self.assertIn('DESAPARECEU', self.rel)

    def test_05c_erro_no_such_mas_ainda_listado_e_falha_de_leitura(self):
        m = mundo_base()
        depois = coleta(m, inspecao={h('c'): (1, '', 'Error: No such container: ' + h('c'))})
        self.assertEqual(self.avalia(coleta(m), depois), 3, self.rel)

    # 6. diferenca real em recurso protegido
    def test_06_diferencas_reais_em_protegidos(self):
        casos = {
            'container reiniciado': lambda m: m['containers'][h('b')]['State'].__setitem__('StartedAt', 'outro'),
            'imagem sem rotulo retaguada': lambda m: m['imagens']['sha256:' + h('7')].__setitem__('RepoTags', []),
            'volume protegido removido': lambda m: m['volumes'].pop('nekkora-platform_data'),
            'rede protegida nova': lambda m: m['redes'].__setitem__(h('9'), {'Id': h('9'), 'Name': 'n', 'IPAM': {}, 'Labels': None}),
            'volume anonimo novo sem dono no ambiente': lambda m: m['volumes'].__setitem__(h('5'), {'Name': h('5'), 'CreatedAt': 't', 'Driver': 'local', 'Labels': None}),
        }
        for nome, muda in casos.items():
            with self.subTest(nome):
                m = mundo_base()
                d = copy.deepcopy(m)
                muda(d)
                self.assertEqual(self.avalia(coleta(m), coleta(d)), 1, self.rel)

    def test_06b_mudancas_so_no_ambiente_sao_ok(self):
        m = mundo_base()
        d = copy.deepcopy(m)
        d['containers'][h('a')]['State']['StartedAt'] = 'reiniciado'
        d['volumes'].pop('ensaio_pgdata')
        self.assertEqual(self.avalia(coleta(m), coleta(d)), 0, self.rel)

    # 7. listagem falha (daemon fora)
    def test_07_listagem_falha_e_inconclusivo(self):
        m = mundo_base()
        self.assertEqual(self.avalia(coleta(m), coleta(m, listagem_falha={'imagens'})), 3, self.rel)

    # 8. volume anonimo montado por container do ambiente e do ambiente (caso do /data do Redis no P4)
    def test_08_volume_anonimo_do_redis_e_do_ambiente(self):
        objs, _ = inv.normalizar(coleta(mundo_base()), AMB)
        self.assertTrue(objs[('volumes', h('4'))]['ambiente'])
        self.assertFalse(objs[('volumes', 'nekkora-platform_data')]['ambiente'])

    # 9. o mascaramento do P4: imagem protegida SEM rotulos trocada no mesmo lugar da ordenacao
    def test_09_legado_mascarava_e_proposta_detecta(self):
        m = mundo_base()
        d = copy.deepcopy(m)
        img = d['imagens'].pop('sha256:' + h('8'))
        img['Id'] = 'sha256:' + h('8')[:-1] + '9'                      # novo ID, mesmo "lugar" e sem rotulos
        d['imagens'][img['Id']] = img

        def legado(mundo):   # emula a fotografia antiga: linha VAZIA para imagem sem Config.Labels, codigo descartado
            return sorted('' if 'Labels' not in (v.get('Config') or {}) else f"{k}|{','.join(v['RepoTags'])}"
                          for k, v in mundo['imagens'].items()
                          if ((v.get('Config') or {}).get('Labels') or {}).get('nekkora.homologacao') != AMB)
        self.assertEqual(legado(m), legado(d))                         # o diff antigo: "identico"
        self.assertEqual(self.avalia(coleta(m), coleta(d)), 1, self.rel)  # a proposta: FALHA

    # 10. o bruto registra o codigo de saida de CADA comando
    def test_10_bruto_tem_codigo_por_comando(self):
        bruto = coleta(mundo_base(), inspecao={'sha256:' + h('7'): (1, '\n', ERRO_P4)})
        self.assertTrue(all('rc' in x for x in bruto))
        ruim = [x for x in bruto if x['tipo'] == 'objeto' and x['rc'] != 0]
        self.assertEqual([x['id'] for x in ruim], ['sha256:' + h('7')])
        self.assertIn('map has no entry for key', ruim[0]['erro'])

    # 12. o bruto e reduzido: Env/Cmd do container nunca vao para a evidencia
    def test_12_bruto_nao_guarda_env(self):
        m = mundo_base()
        m['containers'][h('b')]['Config']['Env'] = ['POSTGRES_PASSWORD=segredo-sintetico-123']
        m['containers'][h('b')]['Config']['Cmd'] = ['postgres', '-c', 'x']
        bruto = json.dumps(coleta(m))
        self.assertNotIn('segredo-sintetico-123', bruto)
        self.assertNotIn('"Cmd"', bruto)
        self.assertEqual(self.avalia(coleta(m), coleta(m)), 0, self.rel)

    # ---------------- revisao 2 (P4-R1, apontamentos da revisao) ----------------
    # 13. inspecao com codigo 0 mas objeto sem identidade/estrutura -> INCONCLUSIVO
    def test_13_objeto_vazio_e_inconclusivo(self):
        m = mundo_base()
        for c, oid in (('containers', h('b')), ('redes', h('e')), ('volumes', 'nekkora-platform_data'),
                       ('imagens', 'sha256:' + h('7'))):
            with self.subTest(c):
                depois = coleta(m, inspecao={oid: (0, '[{}]', '')})
                self.assertEqual(self.avalia(coleta(m), depois), 3, self.rel)

    def test_14_identidade_diferente_da_solicitada_e_inconclusivo(self):
        m = mundo_base()
        outros = {
            h('b'): copy.deepcopy(m['containers'][h('c')]),                       # container: Id de outro
            h('e'): copy.deepcopy(m['redes'][h('d')]),                            # rede: Id de outra
            'nekkora-platform_data': copy.deepcopy(m['volumes']['ensaio_pgdata']),  # volume: Name de outro
            'sha256:' + h('7'): copy.deepcopy(m['imagens']['sha256:' + h('8')]),  # imagem: Id de outra
        }
        for oid, obj in outros.items():
            with self.subTest(oid[:12]):
                depois = coleta(m, inspecao={oid: (0, json.dumps([obj]), '')})
                self.assertEqual(self.avalia(coleta(m), depois), 3, self.rel)
                self.assertIn('identidade', self.rel)

    def test_15_tipos_invalidos_e_campos_obrigatorios(self):
        m = mundo_base()
        casos = {
            'State texto': ('containers', h('b'), lambda o: o.__setitem__('State', 'running')),
            'sem Name': ('containers', h('b'), lambda o: o.pop('Name')),
            'StartedAt ausente': ('containers', h('b'), lambda o: o['State'].pop('StartedAt')),
            'Labels lista': ('redes', h('e'), lambda o: o.__setitem__('Labels', ['x'])),
            'RepoTags texto': ('imagens', 'sha256:' + h('7'), lambda o: o.__setitem__('RepoTags', 'postgres:16')),
            'CreatedAt ausente': ('volumes', 'nekkora-platform_data', lambda o: o.pop('CreatedAt')),
            'Mounts objeto': ('containers', h('b'), lambda o: o.__setitem__('Mounts', {})),
        }
        for nome, (c, oid, muda) in casos.items():
            with self.subTest(nome):
                obj = copy.deepcopy(m[c][oid])
                muda(obj)
                self.assertEqual(self.avalia(coleta(m), coleta(m, inspecao={oid: (0, json.dumps([obj]), '')})), 3, self.rel)

    def test_15b_rotulos_ausentes_ou_nulos_continuam_legitimos(self):
        m = mundo_base()
        m['redes'][h('e')].pop('Labels')
        m['containers'][h('c')]['Config'] = {}
        self.assertEqual(self.avalia(coleta(m), coleta(m)), 0, self.rel)

    # 16. protecao monotonica: o que era protegido continua protegido
    def test_16_volume_protegido_passa_a_ser_montado_pelo_ensaio_e_alterado(self):
        m = mundo_base()
        d = copy.deepcopy(m)
        d['containers'][h('a')]['Mounts'].append({'Type': 'volume', 'Name': 'nekkora-platform_data', 'Destination': '/x'})
        d['volumes']['nekkora-platform_data']['CreatedAt'] = 'recriado'
        self.assertEqual(self.avalia(coleta(m), coleta(d)), 1, self.rel)

    def test_16b_volume_protegido_passa_a_ser_montado_pelo_ensaio_sem_alteracao(self):
        m = mundo_base()
        d = copy.deepcopy(m)
        d['containers'][h('a')]['Mounts'].append({'Type': 'volume', 'Name': 'nekkora-platform_data', 'Destination': '/x'})
        self.assertEqual(self.avalia(coleta(m), coleta(d)), 1, self.rel)
        self.assertIn("'MontadoPor': ['" + h('a'), self.rel)       # continua protegido; o novo uso e a diferenca

    def test_16c_anonimo_protegido_antes_passa_a_ser_so_do_ensaio(self):
        # anonimo montado por container PROTEGIDO antes; depois so pelo ensaio -> classificacao muda -> FALHA
        m = mundo_base()
        m['volumes'][h('5')] = {'Name': h('5'), 'CreatedAt': 't', 'Driver': 'local', 'Labels': {'com.docker.volume.anonymous': ''}}
        m['containers'][h('c')]['Mounts'] = [{'Type': 'volume', 'Name': h('5'), 'Destination': '/v'}]
        d = copy.deepcopy(m)
        d['containers'][h('c')]['Mounts'] = []
        d['containers'][h('a')]['Mounts'].append({'Type': 'volume', 'Name': h('5'), 'Destination': '/v'})
        self.assertEqual(self.avalia(coleta(m), coleta(d)), 1, self.rel)
        self.assertIn('classificacao mudou', self.rel)

    def test_17_volume_anonimo_compartilhado_com_protegido_e_protegido(self):
        m = mundo_base()
        m['containers'][h('b')]['Mounts'] = [{'Type': 'volume', 'Name': h('4'), 'Destination': '/d'}]
        objs, _ = inv.normalizar(coleta(m), AMB)
        self.assertFalse(objs[('volumes', h('4'))]['ambiente'])

    def test_18_volume_nomeado_sem_rotulo_montado_so_pelo_ensaio_e_protegido(self):
        m = mundo_base()
        d = copy.deepcopy(m)
        d['volumes']['dados_sem_rotulo'] = {'Name': 'dados_sem_rotulo', 'CreatedAt': 't', 'Driver': 'local', 'Labels': None}
        d['containers'][h('a')]['Mounts'].append({'Type': 'volume', 'Name': 'dados_sem_rotulo', 'Destination': '/y'})
        objs, _ = inv.normalizar(coleta(d), AMB)
        self.assertFalse(objs[('volumes', 'dados_sem_rotulo')]['ambiente'])
        self.assertEqual(self.avalia(coleta(m), coleta(d)), 1, self.rel)

    def test_19_volume_anonimo_do_ensaio_passa_a_ser_montado_por_protegido(self):
        m = mundo_base()
        d = copy.deepcopy(m)
        d['containers'][h('b')]['Mounts'] = [{'Type': 'volume', 'Name': h('4'), 'Destination': '/d'}]
        self.assertEqual(self.avalia(coleta(m), coleta(d)), 1, self.rel)

    def test_20_container_protegido_passa_a_montar_volume(self):
        m = mundo_base()
        d = copy.deepcopy(m)
        d['containers'][h('c')]['Mounts'] = [{'Type': 'volume', 'Name': 'nekkora-platform_data', 'Destination': '/z'}]
        self.assertEqual(self.avalia(coleta(m), coleta(d)), 1, self.rel)

    def test_21_anonimo_sem_marca_de_anonimo_montado_pelo_ensaio_e_protegido(self):
        m = mundo_base()
        m['volumes'][h('4')]['Labels'] = None
        objs, _ = inv.normalizar(coleta(m), AMB)
        self.assertFalse(objs[('volumes', h('4'))]['ambiente'])

    def test_22_volume_anonimo_do_ensaio_criado_durante_o_teste_e_ok(self):
        m = mundo_base()
        d = copy.deepcopy(m)
        d['volumes'][h('6')] = {'Name': h('6'), 'CreatedAt': 't', 'Driver': 'local', 'Labels': {'com.docker.volume.anonymous': ''}}
        d['containers'][h('a')]['Mounts'].append({'Type': 'volume', 'Name': h('6'), 'Destination': '/w'})
        self.assertEqual(self.avalia(coleta(m), coleta(d)), 0, self.rel)

    # 23. (P4-R2a) excecao inesperada na avaliacao: INCONCLUSIVO (3), nunca o codigo 1 do Python (= FALHA)
    def test_23_excecao_na_avaliacao_e_inconclusivo(self):
        m = mundo_base()
        original = inv.comparar

        def quebra(*a, **k):
            raise RuntimeError('erro inesperado do avaliador')
        with tempfile.TemporaryDirectory() as t:
            p = os.path.join(t, 'a.jsonl')
            with open(p, 'w', encoding='utf-8') as f:
                f.write(''.join(json.dumps(x) + '\n' for x in coleta(m)))
            inv.comparar = quebra
            try:
                self.assertEqual(inv.main(['x', 'comparar', AMB, p, p]), 3)
            finally:
                inv.comparar = original

    # 11. CLI: codigos 0/1/3 e bruto ilegivel
    def test_11_cli_codigos(self):
        m = mundo_base()
        d = copy.deepcopy(m)
        d['containers'][h('b')]['RestartCount'] = 1
        with tempfile.TemporaryDirectory() as t:
            def grava(nome, linhas):
                p = os.path.join(t, nome)
                with open(p, 'w', encoding='utf-8') as f:
                    f.write(''.join(json.dumps(x) + '\n' for x in linhas))
                return p
            a = grava('a.jsonl', coleta(m))
            self.assertEqual(inv.main(['x', 'comparar', AMB, a, a]), 0)
            self.assertEqual(inv.main(['x', 'comparar', AMB, a, grava('d.jsonl', coleta(d))]), 1)
            self.assertEqual(inv.main(['x', 'comparar', AMB, a, grava('f.jsonl', coleta(m, listagem_falha={'redes'}))]), 3)
            ilegivel = os.path.join(t, 'i.jsonl')
            with open(ilegivel, 'w', encoding='utf-8') as f:
                f.write('{nao e json\n')
            self.assertEqual(inv.main(['x', 'comparar', AMB, a, ilegivel]), 3)


if __name__ == '__main__':
    unittest.main()
