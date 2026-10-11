"""Testes do auxiliar de credenciais das contas de navegacao (P-C-T, R1): vetores RFC 6238, recusas, anti-replay,
janela de validade, AUSENCIA de credencial nas saidas e LIMPEZA em todos os caminhos (falha depois de gravar o texto,
falha no esvaziamento, interrupcao durante a gravacao e durante a espera, falha de limpeza, conteudo substituido).

A logica de entrega e limpeza (AreaBase) e testada em qualquer sistema com primitivas EM MEMORIA e falhas injetaveis;
a classe LinhaDeComandoReal repete o essencial com a area de transferencia REAL do Windows (pulada fora dele).
Segredos de teste sinteticos, em diretorio temporario; nenhum ambiente e tocado.

Uso: python testes/test_credencial_navegacao.py -v
"""
import contextlib
import importlib.util
import io
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

AQUI = os.path.dirname(os.path.abspath(__file__))
OPS = os.path.dirname(AQUI)
FERR = os.path.join(OPS, 'lib', 'credencial_navegacao.py')
spec = importlib.util.spec_from_file_location('credencial_navegacao', FERR)
cn = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cn)

RFC_B32 = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'   # "12345678901234567890" (RFC 6238, SHA-1)
SENHA = 'Senha-Sintetica-de-Teste-7Qx!'
# oraculo INDEPENDENTE da ferramenta (nao a constante da propria ferramenta: uma mutacao dela mudaria o oraculo)
MARCAS_ESPERADAS = ('ExcludeClipboardContentFromMonitorProcessing', 'CanIncludeInClipboardHistory', 'CanUploadToCloudClipboard')


def le_json(p):
    with open(p, encoding='utf-8') as h:
        return json.load(h)


def grava_json(p, dados):
    with open(p, 'w', encoding='utf-8') as h:
        json.dump(dados, h)


def le_bytes(p):
    with open(p, 'rb') as h:
        return h.read()


def cofre_sintetico(d):
    contas = [{'chave': 'admin', 'uso': 'navegacao', 'senha': SENHA, 'email': 'admin@demo.nekkora.test'},
              {'chave': 'leitor', 'uso': 'navegacao', 'senha': 'Outra-Senha-Sintetica-9Kp?', 'email': 'leitor@demo.nekkora.test'},
              {'chave': 'j-admin', 'uso': 'jornada', 'senha': 'nao-deve-sair-1'},
              {'chave': 'primeiro-acesso-02', 'uso': 'primeiro-acesso', 'senha': 'nao-deve-sair-2'},
              {'chave': 'recuperacao-02', 'uso': 'recuperacao', 'senha': 'nao-deve-sair-3'},
              {'chave': 'sessoes', 'uso': 'sessoes', 'senha': 'nao-deve-sair-4'},
              {'chave': 'expira', 'uso': 'expira', 'senha': 'nao-deve-sair-5'}]
    os.makedirs(os.path.join(d, 'frontend', 'contas'))
    grava_json(os.path.join(d, 'frontend', 'semeadura.json'), {'contas': contas})
    for c in contas:
        grava_json(os.path.join(d, 'frontend', 'contas', f'{c["chave"]}.json'),
                   {'totp': RFC_B32, 'estado': 'confirmado', 'ultimo_passo': 10, 'codigos': ['cod-sint-1']})


class AreaMemoria(cn.AreaBase):
    """Area de transferencia em memoria com as primitivas da AreaBase e falhas injetaveis.
    falhas: conjunto de pontos ('abre', 'texto', <nome da marca>, 'le', 'fecha'); esvazia_ok: quantos esvaziamentos
    funcionam antes de falhar (None = sempre); interrompe_em: ponto que levanta KeyboardInterrupt."""

    def __init__(self, falhas=(), esvazia_ok=None, interrompe_em=None):
        self.texto, self.marcas, self.aberta = None, {}, False
        self.falhas, self.esvazia_ok, self.interrompe_em = set(falhas), esvazia_ok, interrompe_em
        self.colocados, self.visivel_ao_fechar, self.esvaziamentos = [], [], 0

    def _ponto(self, p):
        if self.interrompe_em == p:
            raise KeyboardInterrupt()
        if p in self.falhas:
            raise OSError(f'simulada em {p}')

    def _abre(self):
        self._ponto('abre')
        assert not self.aberta
        self.aberta = True

    def _fecha(self):
        self.aberta = False
        self.visivel_ao_fechar.append(self.texto)   # o que outra aplicacao passa a ver
        self._ponto('fecha')

    def _esvazia(self):
        if self.esvazia_ok is not None and self.esvaziamentos >= self.esvazia_ok:
            raise OSError('esvaziamento simulado falhou')
        self.esvaziamentos += 1
        self.texto, self.marcas = None, {}

    def _texto(self, valor):
        self._ponto('texto')
        self.texto = valor
        self.colocados.append(valor)

    def _marca(self, nome):
        self._ponto(nome)
        self.marcas[nome] = 0

    def _le_texto(self):
        self._ponto('le')
        return self.texto


class Relogio:
    def __init__(self, t, ao_dormir=None):
        self.t, self.ao_dormir = float(t), ao_dormir

    def agora(self):
        return self.t

    def dorme(self, s):
        if self.ao_dormir:
            f, self.ao_dormir = self.ao_dormir, None
            f()
        self.t += max(s, 0.01)


class Totp(unittest.TestCase):
    def test_vetores_rfc6238(self):
        # RFC 6238, apendice B (SHA-1, 8 digitos): os 6 ultimos digitos sao o codigo de 6 digitos
        for t, oito in [(59, '94287082'), (1111111109, '07081804'), (1111111111, '14050471'), (1234567890, '89005924'),
                        (2000000000, '69279037'), (20000000000, '65353130')]:
            self.assertEqual(cn.totp(RFC_B32, t // 30), oito[-6:], t)

    def test_igual_ao_das_jornadas(self):
        spec2 = importlib.util.spec_from_file_location('jornadas_frontend', os.path.join(AQUI, 'jornadas_frontend.py'))
        jf = importlib.util.module_from_spec(spec2)
        spec2.loader.exec_module(jf)
        for p in (0, 1, 37037036, 66666666, 99999999):
            self.assertEqual(cn.totp(RFC_B32, p), jf.totp(RFC_B32, p))

    def test_passo_escolhido(self):
        self.assertEqual(cn.escolhe_passo(-1, 3000), 101)        # agora+1 (maior validade)
        self.assertEqual(cn.escolhe_passo(100, 3000), 101)
        self.assertIsNone(cn.escolhe_passo(101, 3000))           # ja reservado: so no proximo passo
        self.assertEqual(cn.escolhe_passo(101, 3030), 102)


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.d = self.tmp.name
        cofre_sintetico(self.d)
        self.saidas = []

    def tearDown(self):
        self.tmp.cleanup()

    def arq(self, c):
        return os.path.join(self.d, 'frontend', 'contas', f'{c}.json')

    def roda(self, conta, tipo, area, rel, validade=30):
        return cn.entrega(self.d, conta, tipo, validade, area, escreve=self.saidas.append, relogio=rel.agora, dorme=rel.dorme)

    def sem_credencial(self, texto):
        for proibido in [SENHA, RFC_B32, 'cod-sint-1', 'nao-deve-sair'] + [cn.totp(RFC_B32, p) for p in range(95, 140)]:
            self.assertNotIn(proibido, texto)


class Entrega(Base):
    def test_recusa_toda_conta_que_nao_e_de_navegacao(self):
        for conta in ('j-admin', 'primeiro-acesso-02', 'recuperacao-02', 'sessoes', 'expira', 'nao-existe'):
            for tipo in ('senha', 'totp'):
                antes = le_bytes(self.arq(conta)) if os.path.exists(self.arq(conta)) else None
                area = AreaMemoria()
                with self.assertRaises(cn.Recusa, msg=conta):
                    self.roda(conta, tipo, area, Relogio(3000))
                self.assertEqual(area.colocados, [], conta)
                if antes is not None:
                    self.assertEqual(le_bytes(self.arq(conta)), antes, f'{conta}: arquivo da conta intocado')

    def test_totp_entregue_com_marcas_limpo_e_passo_reservado(self):
        area, rel = AreaMemoria(), Relogio(3000)
        self.assertEqual(self.roda('admin', 'totp', area, rel), 0)
        self.assertEqual(area.colocados, [cn.totp(RFC_B32, 101)])
        self.assertEqual(area.visivel_ao_fechar[0], cn.totp(RFC_B32, 101), 'entregue e visivel depois de fechar')
        self.assertEqual(le_json(self.arq('admin'))['ultimo_passo'], 101)
        self.assertIsNone(area.texto, 'limpa no fim')
        self.assertIn('LIMPA', self.saidas[-1])
        self.assertLessEqual(rel.t, 102 * 30 + 1, 'limpa dentro da janela aceita pelo servidor')

    def test_tres_marcas_gravadas_com_zero(self):
        area = AreaMemoria()
        area.coloca('valor-sintetico')
        self.assertEqual(area.texto, 'valor-sintetico')
        self.assertEqual(area.marcas, {n: 0 for n in MARCAS_ESPERADAS}, 'as tres marcacoes, todas com 0')

    def test_novo_login_usa_passo_posterior_e_espera_por_ele(self):
        rel = Relogio(3000)
        self.roda('admin', 'totp', AreaMemoria(), rel)           # passo 101; relogio avanca 30 s (limpeza)
        a2 = AreaMemoria()
        self.roda('admin', 'totp', a2, rel)
        p2 = le_json(self.arq('admin'))['ultimo_passo']
        self.assertGreater(p2, 101)
        self.assertEqual(a2.colocados, [cn.totp(RFC_B32, p2)])

    def test_senha_entregue_e_limpa(self):
        area = AreaMemoria()
        self.roda('admin', 'senha', area, Relogio(3000), validade=10)
        self.assertEqual(area.colocados, [SENHA])
        self.assertIsNone(area.texto)

    def test_saidas_sem_credencial(self):
        self.roda('admin', 'senha', AreaMemoria(), Relogio(3000), validade=5)
        self.roda('admin', 'totp', AreaMemoria(), Relogio(4000))
        self.sem_credencial('\n'.join(self.saidas))


class Limpeza(Base):
    """P-C-T-R1: nenhum caminho deixa a credencial na area de transferencia sem relatar."""

    def test_falha_apos_gravar_o_texto_desfaz_antes_de_liberar(self):
        for marca in MARCAS_ESPERADAS:
            area = AreaMemoria(falhas={marca})
            with self.assertRaises(cn.EntregaDesfeita, msg=marca):
                self.roda('admin', 'senha', area, Relogio(3000))
            self.assertEqual(area.colocados, [SENHA], f'{marca}: o texto chegou a ser gravado')
            self.assertEqual(area.visivel_ao_fechar, [None], f'{marca}: nada visivel ao liberar a area')
            self.assertFalse(any('ENTREGUE' in x for x in self.saidas))

    def test_passo_continua_reservado_quando_a_entrega_falha(self):
        area = AreaMemoria(falhas={'CanUploadToCloudClipboard'})
        with self.assertRaises(cn.EntregaDesfeita):
            self.roda('admin', 'totp', area, Relogio(3000))
        self.assertEqual(le_json(self.arq('admin'))['ultimo_passo'], 101, 'reservado, nunca reutilizado')
        self.assertEqual(area.visivel_ao_fechar, [None])

    def test_falha_apos_texto_e_no_esvaziamento_relata_limpeza_nao_confirmada(self):
        area = AreaMemoria(falhas={'CanIncludeInClipboardHistory'}, esvazia_ok=1)   # o 2o esvaziamento (desfazer) falha
        with self.assertRaises(cn.LimpezaNaoConfirmada) as ctx:
            self.roda('admin', 'senha', area, Relogio(3000))
        self.sem_credencial(str(ctx.exception) + '\n'.join(self.saidas))

    def test_falha_ao_abrir_nada_gravado(self):
        area = AreaMemoria(falhas={'abre'})
        with self.assertRaises(cn.EntregaDesfeita) as ctx:
            self.roda('admin', 'senha', area, Relogio(3000))
        self.assertIn('nada gravado', str(ctx.exception))
        self.assertEqual(area.colocados, [])

    def test_interrupcao_durante_a_gravacao_desfaz(self):
        area = AreaMemoria(interrompe_em='CanIncludeInClipboardHistory')
        with self.assertRaises(KeyboardInterrupt):
            self.roda('admin', 'senha', area, Relogio(3000))
        # 1o fechamento: a propria entrega desfeita; 2o: a finalizacao confere e encontra a area vazia
        self.assertEqual(area.visivel_ao_fechar, [None, None])
        self.assertIn('vazia', self.saidas[-1])
        self.assertFalse(any('ENTREGUE' in x for x in self.saidas))

    def test_interrupcao_durante_a_espera_limpa(self):
        area = AreaMemoria()

        def ctrl_c():
            raise KeyboardInterrupt()
        rel = Relogio(3000, ao_dormir=ctrl_c)
        with self.assertRaises(KeyboardInterrupt):
            self.roda('admin', 'senha', area, rel)
        self.assertEqual(area.colocados, [SENHA])
        self.assertIsNone(area.texto, 'limpa na finalizacao')
        self.assertIn('LIMPA', self.saidas[-1])
        self.sem_credencial('\n'.join(self.saidas))

    def test_erro_durante_a_espera_limpa(self):
        area = AreaMemoria()

        def erro():
            raise RuntimeError('erro simulado durante a espera')
        with self.assertRaises(RuntimeError):
            self.roda('admin', 'senha', area, Relogio(3000, ao_dormir=erro))
        self.assertIsNone(area.texto)

    def test_falha_de_limpeza_relatada_sem_valor(self):
        area = AreaMemoria()
        rel = Relogio(3000, ao_dormir=lambda: area.falhas.add('le'))
        with self.assertRaises(cn.LimpezaNaoConfirmada):
            self.roda('admin', 'senha', area, rel)
        self.assertIn('limpeza NAO confirmada', self.saidas[-1])
        self.sem_credencial('\n'.join(self.saidas))

    def test_conteudo_substituido_e_preservado(self):
        area = AreaMemoria()

        def outra_aplicacao():
            area.texto = 'conteudo de outra aplicacao'
        self.roda('admin', 'senha', area, Relogio(3000, ao_dormir=outra_aplicacao))
        self.assertEqual(area.texto, 'conteudo de outra aplicacao', 'preservado')
        self.assertIn('ja substituida', self.saidas[-1])

    def test_area_ja_vazia(self):
        area = AreaMemoria()

        def esvaziada():
            area.texto = None
        self.roda('admin', 'senha', area, Relogio(3000, ao_dormir=esvaziada))
        self.assertIn('vazia', self.saidas[-1])


class CodigosDaLinhaDeComando(Base):
    """main() com a area em memoria: codigos e mensagens de cada caminho, sem credencial na saida."""

    def cli(self, area, *args, sleep=None):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err), mock.patch.object(cn.time, 'sleep', sleep or (lambda s: None)):
            rc = cn.main(['--segredos', self.d, *args], area_fabrica=lambda: area)
        texto = out.getvalue() + err.getvalue()
        self.sem_credencial(texto)
        return rc, texto

    def test_codigos(self):
        rc, t = self.cli(AreaMemoria(), 'admin', 'senha', '--validade', '5')
        self.assertEqual(rc, 0, t)
        rc, t = self.cli(AreaMemoria(falhas={'CanUploadToCloudClipboard'}), 'admin', 'senha')
        self.assertEqual(rc, 4, t)
        self.assertIn('nada ficou disponivel', t)
        rc, t = self.cli(AreaMemoria(falhas={'CanUploadToCloudClipboard'}, esvazia_ok=1), 'admin', 'totp')
        self.assertEqual(rc, 6, t)
        self.assertIn('limpeza NAO confirmada', t)
        area = AreaMemoria()

        def ctrl_c(s):
            raise KeyboardInterrupt()
        rc, t = self.cli(area, 'leitor', 'senha', sleep=ctrl_c)
        self.assertEqual(rc, 5, t)
        self.assertIn('LIMPA', t)
        self.assertIsNone(area.texto)
        rc, t = self.cli(AreaMemoria(), 'j-admin', 'senha')
        self.assertEqual(rc, 2, t)

    def test_interrupcao_com_limpeza_falhando_da_6(self):
        area = AreaMemoria()

        def ctrl_c(s):
            area.falhas.add('le')
            raise KeyboardInterrupt()
        rc, t = self.cli(area, 'admin', 'senha', sleep=ctrl_c)
        self.assertEqual(rc, 6, t)


@unittest.skipUnless(os.name == 'nt', 'area de transferencia do Windows')
class LinhaDeComandoReal(unittest.TestCase):
    """A ferramenta de verdade, com a area de transferencia REAL (conteudo anterior restaurado)."""

    def setUp(self):
        self.area = cn.AreaWindows()
        self.anterior = self.area.le()
        self.tmp = tempfile.TemporaryDirectory()
        cofre_sintetico(self.tmp.name)

    def tearDown(self):
        if self.anterior is not None:
            self.area.coloca(self.anterior)
        self.tmp.cleanup()

    def cli(self, *args):
        return subprocess.run([sys.executable, FERR, '--segredos', self.tmp.name, *args], capture_output=True, text=True, timeout=120)

    def marca_real(self, nome):
        u, k = self.area.u, self.area.k
        self.area._abre()
        try:
            h = u.GetClipboardData(u.RegisterClipboardFormatW(nome))
            if not h:
                return None
            p = k.GlobalLock(h)
            try:
                return struct.unpack('<I', self.area.c.string_at(p, 4))[0]
            finally:
                k.GlobalUnlock(h)
        finally:
            self.area._fecha()

    def test_entrega_real_sem_credencial_na_saida_e_limpeza(self):
        p = subprocess.Popen([sys.executable, FERR, '--segredos', self.tmp.name, 'admin', 'senha', '--validade', '5'],
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        linha = p.stdout.readline()
        self.assertIn('ENTREGUE', linha)
        self.assertEqual(self.area.le(), SENHA, 'valor na area de transferencia durante a validade')
        for nome in MARCAS_ESPERADAS:
            self.assertEqual(self.marca_real(nome), 0, f'{nome} presente e com valor 0')
        out, err = p.communicate(timeout=60)
        self.assertEqual(p.returncode, 0, err)
        self.assertIsNone(self.area.le(), 'area de transferencia limpa no fim')
        for proibido in (SENHA, RFC_B32):
            self.assertNotIn(proibido, linha + out + err)

    def test_totp_real_sem_codigo_na_saida(self):
        r = self.cli('leitor', 'totp', '--validade', '5')
        self.assertEqual(r.returncode, 0, r.stderr)
        passo = le_json(os.path.join(self.tmp.name, 'frontend', 'contas', 'leitor.json'))['ultimo_passo']
        self.assertIn(f'passo reservado={passo}', r.stdout)
        self.assertNotIn(cn.totp(RFC_B32, passo), r.stdout + r.stderr)
        self.assertNotIn(RFC_B32, r.stdout + r.stderr)

    def test_recusas_pela_linha_de_comando(self):
        for args in (['j-admin', 'senha'], ['primeiro-acesso-02', 'totp'], ['recuperacao-02', 'senha'], ['expira', 'totp'],
                     ['nao-existe', 'senha'], ['admin', 'senha', '--validade', '600']):
            antes = self.area.le()
            r = self.cli(*args)
            self.assertEqual(r.returncode, 2, args)
            self.assertIn('RECUSADO', r.stderr, args)
            self.assertEqual(self.area.le(), antes, f'{args}: area de transferencia intocada')
            for proibido in ('nao-deve-sair', SENHA, RFC_B32):
                self.assertNotIn(proibido, r.stdout + r.stderr, args)

    def test_marcas_reais_com_valor_zero(self):
        self.area.coloca('valor-sintetico-de-marcacao')
        for nome in MARCAS_ESPERADAS:
            self.assertEqual(self.marca_real(nome), 0, nome)
        self.area.limpa_se('valor-sintetico-de-marcacao')

    def test_falha_real_apos_gravar_o_texto_desfaz(self):
        class Falha(cn.AreaWindows):
            def _marca(self, nome):
                if nome == 'CanUploadToCloudClipboard':
                    raise OSError('simulada')
                super()._marca(nome)
        with self.assertRaises(cn.EntregaDesfeita):
            Falha().coloca('valor-sintetico-parcial')
        self.assertIsNone(self.area.le(), 'nada disponivel depois da falha')

    def test_interrupcao_real_durante_a_espera_limpa(self):
        def ctrl_c(s):
            raise KeyboardInterrupt()
        saidas = []
        with self.assertRaises(KeyboardInterrupt):
            cn.entrega(self.tmp.name, 'admin', 'senha', 30, cn.AreaWindows(), escreve=saidas.append, dorme=ctrl_c)
        self.assertIsNone(self.area.le(), 'limpa na finalizacao')
        self.assertIn('LIMPA', saidas[-1])


if __name__ == '__main__':
    unittest.main()
