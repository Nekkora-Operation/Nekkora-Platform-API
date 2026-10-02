"""Testes da evidencia de fontes da regressao Maven (lib/fontes_maven.py e regressao_maven.sh), SEM Maven real e sem
Docker: um repositorio SINTETICO temporario (Git proprio) com um api/mvnw FALSO que simula a regressao e, conforme
NEKKORA_TESTE_MODO, muta/adiciona/remove uma fonte DURANTE a execucao, falha testes ou nao executa nenhum.
O runner e o nucleo sao COPIADOS para o repositorio sintetico (a raiz e derivada da localizacao do runner).
Uso: python -B ops/homologacao/testes/test_fontes_maven.py"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

AQUI = os.path.dirname(os.path.abspath(__file__))
HOMOL = os.path.normpath(os.path.join(AQUI, '..'))
sys.path.insert(0, os.path.join(HOMOL, 'lib'))
import fontes_maven  # noqa: E402

MVNW_FALSO = r'''#!/usr/bin/env bash
# mvnw FALSO (teste): registra se o manifesto ja existia ao iniciar e simula a regressao
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
ls "$NEKKORA_TESTE_SAIDA"/fontes.sha256 > /dev/null 2>&1 && echo "MANIFESTO_JA_EXISTIA" || echo "MANIFESTO_AUSENTE"
case "${NEKKORA_TESTE_MODO:-estavel}" in
  muta)     echo "// mutacao durante a execucao" >> "$RAIZ/api/m1/src/main/java/A.java" ;;
  adiciona) echo "class Nova {}" > "$RAIZ/api/m1/src/main/java/Nova.java" ;;
  remove)   rm "$RAIZ/db/migrations/002_y.sql" ;;
  saida)    mkdir -p "$RAIZ/api/m1/target/classes"; echo x > "$RAIZ/api/m1/target/classes/A.class" ;;
esac
case "${NEKKORA_TESTE_MODO:-estavel}" in
  falha)     echo "[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0"; exit 1 ;;
  semtestes) echo "[INFO] BUILD SUCCESS"; exit 0 ;;
  *)         echo "[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 1"; echo "[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0"
             echo "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s - in X (linha de classe: nao conta)"; exit 0 ;;
esac
'''


def escreve(raiz, rel, txt, modo=None):
    p = os.path.join(raiz, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w', encoding='utf-8', newline='\n') as f:
        f.write(txt)
    if modo:
        os.chmod(p, modo)


def ler(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


def git(raiz, *a):
    return subprocess.run(['git', '-C', raiz, *a], capture_output=True, text=True)


def repo_sintetico(t, ordem_reversa=False):
    raiz = os.path.join(t, 'repo')
    arqs = [
        ('api/pom.xml', '<project><modules>\n  <module>m1</module>\n</modules></project>\n'),
        ('api/mvnw', MVNW_FALSO), ('api/mvnw.cmd', '@echo off\r\n'), ('api/.mvn/wrapper/maven-wrapper.properties', 'x=1\n'),
        ('api/m1/pom.xml', '<project/>\n'), ('api/m1/src/main/java/A.java', 'class A {}\r\n'),
        ('api/m1/src/test/resources/arquivo com espaco.txt', 'espaco\n'),
        ('api/m1/target/gerado.class', 'nao entra\n'), ('api/m1/src/main/node_modules/x.js', 'nao entra\n'),
        ('db/migrations/001_x.sql', 'SELECT 1;\n'), ('db/migrations/002_y.sql', 'SELECT 2;\n'),
        ('db/bootstrap/aplicar_como_migrador.sh', '#!/bin/sh\n'),
        ('ops/homologacao/saidas/run/x.txt', 'nao entra\n'), ('ops/homologacao/evidencias/e.txt', 'nao entra\n'),
        ('ops/homologacao/lib/__pycache__/c.pyc', 'nao entra\n'), ('api/fora_do_conjunto.txt', 'nao entra\n'),
    ]
    for rel, txt in (reversed(arqs) if ordem_reversa else arqs):
        escreve(raiz, rel, txt, 0o755 if rel.endswith('mvnw') else None)
    escreve(raiz, 'ops/homologacao/regressao_maven.sh', ler(os.path.join(HOMOL, 'regressao_maven.sh')), 0o755)
    escreve(raiz, 'ops/homologacao/lib/fontes_maven.py', ler(os.path.join(HOMOL, 'lib', 'fontes_maven.py')))
    git(raiz, 'init', '-q'); git(raiz, 'config', 'core.autocrlf', 'false')
    git(raiz, '-c', 'user.name=teste', '-c', 'user.email=teste@exemplo.invalid', 'add', '-A')
    git(raiz, '-c', 'user.name=teste', '-c', 'user.email=teste@exemplo.invalid', 'commit', '-q', '-m', 'sintetico')
    escreve(raiz, 'db/migrations/003_nao_rastreada.sql', 'SELECT 3;\n')   # entrada NAO rastreada: tem de entrar
    return raiz


def bash():
    """O bash do Git (no Windows, 'bash' no PATH pode ser o do WSL, que nao enxerga este sistema de arquivos)."""
    if os.name == 'nt':
        ex = subprocess.run(['git', '--exec-path'], capture_output=True, text=True).stdout.strip()   # .../mingw64/libexec/git-core
        for cand in (os.path.join(ex, '..', '..', '..', 'bin', 'bash.exe'), os.path.join(ex, '..', '..', '..', 'usr', 'bin', 'bash.exe')):
            if os.path.isfile(cand):
                return os.path.normpath(cand)
    return shutil.which('bash') or 'bash'


def roda(raiz, saida, modo='estavel', args=()):
    s = saida.replace('\\', '/')
    env = dict(os.environ, NEKKORA_TESTE_MODO=modo, NEKKORA_TESTE_SAIDA=s)
    return subprocess.run([bash(), os.path.join(raiz, 'ops/homologacao/regressao_maven.sh').replace('\\', '/'), s, *args],
                          capture_output=True, text=True, env=env)


def res(saida):
    return dict(l.split('=', 1) for l in ler(os.path.join(saida, 'resultado.txt')).splitlines() if '=' in l and not l.startswith(' '))


class Manifesto(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.mkdtemp(); self.raiz = repo_sintetico(self.t)

    def tearDown(self):
        shutil.rmtree(self.t, ignore_errors=True)

    def man(self, raiz=None):
        b, l = os.path.join(self.t, 'b'), os.path.join(self.t, 'l')
        fontes_maven.manifesto(raiz or self.raiz, b, l)
        return ler(b), ler(l)

    def caminhos(self):
        return [l[66:] for l in self.man()[0].splitlines()]

    def test_conjunto_exclusoes_espaco_e_nao_rastreado(self):
        c = self.caminhos()
        for esperado in ('api/m1/src/test/resources/arquivo com espaco.txt', 'db/migrations/003_nao_rastreada.sql', 'api/mvnw',
                         'api/.mvn/wrapper/maven-wrapper.properties', 'api/m1/pom.xml', 'db/bootstrap/aplicar_como_migrador.sh'):
            self.assertIn(esperado, c)
        for fora in ('api/m1/target/gerado.class', 'api/m1/src/main/node_modules/x.js', 'ops/homologacao/saidas/run/x.txt',
                     'ops/homologacao/evidencias/e.txt', 'ops/homologacao/lib/__pycache__/c.pyc', 'api/fora_do_conjunto.txt'):
            self.assertNotIn(fora, c)

    def test_ordem_deterministica_por_bytes(self):
        c = self.caminhos()
        self.assertEqual(c, sorted(c, key=lambda x: x.encode('utf-8')))
        a1 = self.man(); a2 = self.man()
        self.assertEqual(a1, a2)
        t2 = tempfile.mkdtemp()
        try:
            r2 = repo_sintetico(t2, ordem_reversa=True)
            self.assertEqual(self.man()[0], self.man(r2)[0])   # independe da ordem de criacao
        finally:
            shutil.rmtree(t2, ignore_errors=True)

    def test_bruto_e_lf(self):
        b, l = self.man()
        hb = dict((x[66:], x[:64]) for x in b.splitlines()); hl = dict((x[66:], x[:64]) for x in l.splitlines())
        self.assertNotEqual(hb['api/m1/src/main/java/A.java'], hl['api/m1/src/main/java/A.java'])   # CRLF no disco
        self.assertEqual(hb['db/migrations/001_x.sql'], hl['db/migrations/001_x.sql'])             # ja LF

    def test_sha256sum_compativel_com_espaco(self):
        b, _ = self.man()
        p = os.path.join(self.t, 'b')
        r = subprocess.run(['sha256sum', '-c', p], cwd=self.raiz, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_comparar(self):
        a = os.path.join(self.t, 'a.sha'); b = os.path.join(self.t, 'b.sha')
        fontes_maven.manifesto(self.raiz, a, a + '.lf')
        escreve(self.raiz, 'api/m1/src/main/java/A.java', 'class A { int x; }\n')
        os.remove(os.path.join(self.raiz, 'db/migrations/002_y.sql'))
        escreve(self.raiz, 'api/m1/src/main/java/B.java', 'class B {}\n')
        fontes_maven.manifesto(self.raiz, b, b + '.lf')
        d = fontes_maven.comparar(a, b)
        self.assertEqual(d, ['ALTERADO api/m1/src/main/java/A.java', 'REMOVIDO db/migrations/002_y.sql', 'ADICIONADO api/m1/src/main/java/B.java'])
        self.assertEqual(fontes_maven.main(['x', 'comparar', a, a]), 0)
        self.assertEqual(fontes_maven.main(['x', 'comparar', a, b]), 1)

    def test_caminho_nao_representavel_recusado(self):
        orig = fontes_maven._andar
        try:
            fontes_maven._andar = lambda raiz, rel: iter(['api/linha\nquebrada.java'])
            with self.assertRaises(fontes_maven.Recusa):
                fontes_maven.conjunto(self.raiz)
            fontes_maven._andar = lambda raiz, rel: iter(['api\\barra.java'])
            with self.assertRaises(fontes_maven.Recusa):
                fontes_maven.conjunto(self.raiz)
        finally:
            fontes_maven._andar = orig

    def test_sem_modules_recusado(self):
        escreve(self.raiz, 'api/pom.xml', '<project/>\n')
        with self.assertRaises(fontes_maven.Recusa):
            fontes_maven.conjunto(self.raiz)

    def test_manifesto_ilegivel_recusado(self):
        p = os.path.join(self.t, 'ruim.sha'); escreve(self.t, 'ruim.sha', 'nao e manifesto\n')
        self.assertEqual(fontes_maven.main(['x', 'comparar', p, p]), 3)


class Runner(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.mkdtemp(); self.raiz = repo_sintetico(self.t); self.saida = os.path.join(self.t, 'execucao')

    def tearDown(self):
        shutil.rmtree(self.t, ignore_errors=True)

    def test_estavel_permite_reaproveitamento(self):
        r = roda(self.raiz, self.saida)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        x = res(self.saida)
        self.assertEqual((x['RESULTADO_TESTES'], x['FONTES_ESTAVEIS'], x['REAPROVEITAMENTO']), ('OK', 'OK', 'PERMITIDO'))
        self.assertEqual(x['TESTES'], '5 FALHAS=0 ERROS=0 IGNORADOS=1')   # linhas de classe ("Time elapsed") nao contam
        m = ler(os.path.join(self.saida, 'maven.txt'))
        self.assertIn('MANIFESTO_JA_EXISTIA', m)                          # manifesto gerado ANTES do Maven
        c = ler(os.path.join(self.saida, 'contexto.txt'))
        for k in ('inicio=', 'head=', 'arvore=', 'arquivos_no_manifesto=', 'sha256_fontes=', 'comando=api/mvnw -B verify',
                  'entradas_nao_rastreadas_no_manifesto=1'):
            self.assertIn(k, c)
        self.assertIn('db/migrations/003_nao_rastreada.sql', ler(os.path.join(self.saida, 'entradas_nao_rastreadas.txt')))
        v = subprocess.run([sys.executable, '-B', os.path.join(HOMOL, 'lib', 'fontes_maven.py'), 'verificar', self.saida, self.raiz], capture_output=True, text=True)
        self.assertEqual(v.returncode, 0, v.stdout)
        self.assertIn('REAPROVEITAMENTO_NA_ARVORE_ATUAL=PERMITIDO', v.stdout)

    def esperar_instavel(self, modo, marca):
        r = roda(self.raiz, self.saida, modo)
        self.assertNotEqual(r.returncode, 0)
        x = res(self.saida)
        self.assertEqual((x['RESULTADO_TESTES'], x['FONTES_ESTAVEIS'], x['REAPROVEITAMENTO']), ('OK', 'FALHA', 'PROIBIDO'))
        self.assertIn('fontes alteradas durante a execucao', x['MOTIVO'])
        self.assertIn(marca, ler(os.path.join(self.saida, 'resultado.txt')))

    def test_mutacao_durante(self):
        self.esperar_instavel('muta', 'ALTERADO api/m1/src/main/java/A.java')

    def test_arquivo_adicionado_durante(self):
        self.esperar_instavel('adiciona', 'ADICIONADO api/m1/src/main/java/Nova.java')

    def test_arquivo_removido_durante(self):
        self.esperar_instavel('remove', 'REMOVIDO db/migrations/002_y.sql')

    def test_saida_em_target_nao_e_entrada(self):
        r = roda(self.raiz, self.saida, 'saida')
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(res(self.saida)['FONTES_ESTAVEIS'], 'OK')

    def test_falha_de_testes_proibe(self):
        roda(self.raiz, self.saida, 'falha')
        x = res(self.saida)
        self.assertEqual((x['RESULTADO_TESTES'], x['FONTES_ESTAVEIS'], x['REAPROVEITAMENTO']), ('FALHA', 'OK', 'PROIBIDO'))

    def test_sem_testes_proibe(self):
        roda(self.raiz, self.saida, 'semtestes')
        x = res(self.saida)
        self.assertEqual(x['REAPROVEITAMENTO'], 'PROIBIDO'); self.assertIn('nenhum teste executado', x['MOTIVO'])

    def test_saida_existente_recusada_sem_rodar_maven(self):
        os.makedirs(self.saida)
        r = roda(self.raiz, self.saida)
        self.assertEqual(r.returncode, 3); self.assertFalse(os.path.exists(os.path.join(self.saida, 'maven.txt')))

    def test_mutacao_depois_proibe_reaproveitamento_na_arvore_atual(self):
        roda(self.raiz, self.saida)
        escreve(self.raiz, 'db/migrations/001_x.sql', 'SELECT 99;\n')
        v = subprocess.run([sys.executable, '-B', os.path.join(HOMOL, 'lib', 'fontes_maven.py'), 'verificar', self.saida, self.raiz], capture_output=True, text=True)
        self.assertEqual(v.returncode, 1); self.assertIn('ALTERADO db/migrations/001_x.sql', v.stdout)
        self.assertIn('REAPROVEITAMENTO_NA_ARVORE_ATUAL=PROIBIDO', v.stdout)

    def test_evidencia_adulterada_proibe(self):
        roda(self.raiz, self.saida, 'falha')
        p = os.path.join(self.saida, 'resultado.txt')
        s = ler(p).replace('REAPROVEITAMENTO=PROIBIDO', 'REAPROVEITAMENTO=PERMITIDO')
        escreve(os.path.dirname(p), os.path.basename(p), s)
        v = subprocess.run([sys.executable, '-B', os.path.join(HOMOL, 'lib', 'fontes_maven.py'), 'verificar', self.saida, self.raiz], capture_output=True, text=True)
        self.assertIn('INTEGRIDADE_EXECUCAO=FALHA', v.stdout); self.assertIn('REAPROVEITAMENTO_NA_ARVORE_ATUAL=PROIBIDO', v.stdout)


if __name__ == '__main__':
    unittest.main()
