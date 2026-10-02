"""Testes de lib/porta.py (R2b-1a), SEM Docker e sem alterar reservas do sistema. As conferencias de listener e de bind
usam sockets reais so em portas efemeras escolhidas pelo proprio sistema; a tabela do netsh e simulada (executar=...).
Uso: python -B -m unittest ops/homologacao/testes/test_porta.py  (ou: python -B test_porta.py)"""
import os
import socket
import subprocess
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'lib'))
import porta  # noqa: E402

PT = """
Protocolo tcp Intervalos de Exclusão de Porta

Porta Inicial    Porta Final
----------    --------
     27339       27339
     56526       56625
     56626       56725
     50000       50059     *

* - Exclusões de porta administradas.
"""
EN = """
Protocol tcp Port Exclusion Ranges

Start Port    End Port
----------    --------
      5357        5357
     50000       50059     *

* - Administered port exclusions.
"""
VAZIA = """
Protocol tcp Port Exclusion Ranges

Start Port    End Port
----------    --------

* - Administered port exclusions.
"""


class R:   # resultado simulado de subprocess.run
    def __init__(self, rc, out):
        self.returncode, self.stdout = rc, out


def exe(rc=0, out=PT):
    return lambda: R(rc, out)


def porta_livre():
    s = socket.socket(); s.bind(('127.0.0.1', 0)); p = s.getsockname()[1]; s.close(); return p


class Parser(unittest.TestCase):
    def test_portugues(self):
        self.assertEqual(porta.faixas_excluidas(PT), [(27339, 27339), (56526, 56625), (56626, 56725), (50000, 50059)])

    def test_ingles(self):
        self.assertEqual(porta.faixas_excluidas(EN), [(5357, 5357), (50000, 50059)])

    def test_tabela_vazia_e_legivel(self):
        self.assertEqual(porta.faixas_excluidas(VAZIA), [])

    def test_crlf(self):
        self.assertEqual(porta.faixas_excluidas(PT.replace('\n', '\r\n'))[1], (56526, 56625))

    def test_sem_separador_e_ilegivel(self):
        self.assertIsNone(porta.faixas_excluidas('O comando nao foi encontrado.\n'))
        self.assertIsNone(porta.faixas_excluidas(''))

    def test_lixo_depois_das_faixas_e_ilegivel(self):
        self.assertIsNone(porta.faixas_excluidas(PT.replace('* - Exclus', 'texto inesperado\n* - Exclus')))

    def test_faixa_invalida_e_ilegivel(self):
        self.assertIsNone(porta.faixas_excluidas(PT.replace('56626       56725', '56726       56626')))
        self.assertIsNone(porta.faixas_excluidas(PT.replace('27339       27339', '0       27339')))


class Excluidas(unittest.TestCase):
    def test_dentro_inicio_fim_e_fora(self):
        for p, ok in [(56721, False), (56626, False), (56725, False), (56625, False), (56726, True), (55643, True), (27339, False)]:
            with self.subTest(p=p):
                self.assertEqual(porta.conf_excluidas(p, exe())[0], ok)

    def test_falha_fechada(self):
        self.assertFalse(porta.conf_excluidas(55643, exe(rc=1, out=PT))[0])          # codigo != 0
        self.assertFalse(porta.conf_excluidas(55643, exe(out='saida inesperada'))[0])  # sem tabela

        def quebra():
            raise FileNotFoundError('netsh')
        self.assertFalse(porta.conf_excluidas(55643, quebra)[0])                        # comando ausente

        def lento():
            raise subprocess.TimeoutExpired('netsh', 20)
        self.assertFalse(porta.conf_excluidas(55643, lento)[0])                         # sem resposta

    def test_tabela_vazia_aprova(self):
        self.assertTrue(porta.conf_excluidas(55643, exe(out=VAZIA))[0])


class Sockets(unittest.TestCase):
    def test_porta_livre_aprova_listener_e_bind(self):
        p = porta_livre()
        self.assertTrue(porta.conf_listener('127.0.0.1', p)[0])
        self.assertTrue(porta.conf_bind('127.0.0.1', p)[0])

    def test_porta_ocupada_reprova_listener_e_bind(self):
        s = socket.socket(); s.bind(('127.0.0.1', 0)); s.listen(1); p = s.getsockname()[1]
        try:
            self.assertFalse(porta.conf_listener('127.0.0.1', p)[0])
            self.assertFalse(porta.conf_bind('127.0.0.1', p)[0])
        finally:
            s.close()

    def test_bind_libera_a_porta(self):
        p = porta_livre()
        self.assertTrue(porta.conf_bind('127.0.0.1', p)[0])
        self.assertTrue(porta.conf_bind('127.0.0.1', p)[0])   # a primeira sondagem nao deixou a porta presa


class Decisao(unittest.TestCase):
    OK = staticmethod(lambda *a: (True, 'ok'))
    NOK = staticmethod(lambda *a: (False, 'nao'))

    def test_apta_so_com_tudo_ok(self):
        self.assertTrue(porta.elegivel('127.0.0.1', 1, self.OK, self.OK, self.OK)[0])

    def test_qualquer_falha_reprova(self):
        for i in range(3):
            fs = [self.OK, self.OK, self.OK]; fs[i] = self.NOK
            with self.subTest(conferencia=i):
                self.assertFalse(porta.elegivel('127.0.0.1', 1, *fs)[0])

    def test_bind_reprova_mesmo_sem_listener_e_fora_das_faixas(self):
        # o caso do STOP da R2b-1 visto pelo bind: sem uso, fora de faixa conhecida, e mesmo assim sem bind
        self.assertFalse(porta.elegivel('127.0.0.1', 1, self.OK, self.NOK, self.OK)[0])

    def test_argumentos(self):
        for a in (['porta.py'], ['porta.py', 'elegivel', '127.0.0.1', '0'], ['porta.py', 'elegivel', '127.0.0.1', '70000'],
                  ['porta.py', 'elegivel', 'x.y', '80'], ['porta.py', 'outro', '127.0.0.1', '80']):
            with self.subTest(a=a):
                self.assertEqual(porta.main(a), 3)


if __name__ == '__main__':
    unittest.main()
