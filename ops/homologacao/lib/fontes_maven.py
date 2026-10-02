"""Manifesto de FONTES da regressao Maven (missao "manifesto de fontes Maven"): prova verificavel de qual arvore
sustenta um resultado de testes, sem depender de mtime.

  python fontes_maven.py manifesto <raiz do repositorio> <fontes.sha256> <fontes_lf.sha256>
      Lista, em ordem deterministica (caminho relativo com "/", ordenado por bytes UTF-8), TODOS os arquivos do conjunto de
      entrada (abaixo), rastreados ou nao pelo Git, e grava dois manifestos no formato do sha256sum ("<sha256>  <caminho>"):
        fontes.sha256     sha256 dos BYTES do arquivo como estao no disco (o que o Maven e os testes leram);
        fontes_lf.sha256  sha256 do conteudo com fim de linha normalizado para LF (comparacao entre checkouts com
                          core.autocrlf diferente).
      Caminho com quebra de linha ou barra invertida: recusa (codigo 3; o formato nao os representa sem ambiguidade).
      Espaco no caminho e permitido (o caminho e o resto da linha depois de dois espacos).
  python fontes_maven.py comparar <manifesto a> <manifesto b>
      ALTERADO/ADICIONADO/REMOVIDO por caminho; codigo 0 se identicos, 1 se diferentes, 3 se ilegiveis.
  python fontes_maven.py verificar <diretorio da execucao> <raiz do repositorio>
      Confere a integridade do diretorio (manifesto.sha256) e se a arvore ATUAL ainda e a do inicio da execucao;
      imprime REAPROVEITAMENTO_NA_ARVORE_ATUAL=PERMITIDO so se o resultado da execucao permitia e a arvore e identica.

CONJUNTO DE ENTRADA (declarado; mudanca so com revisao):
  api/pom.xml, api/mvnw, api/mvnw.cmd, api/.mvn/**;
  para cada modulo de <modules> em api/pom.xml: api/<modulo>/pom.xml e api/<modulo>/src/**;
  db/migrations/** e db/bootstrap/**     (RealDatabase e OpsBanco copiam migrations e o script do modelo);
  ops/homologacao/**                     (testes de nekkora-ops e nekkora-platform executam homologacao.sh, lib/, sql/,
                                          postgres/*.modelo, testes/h016.sh, testes/cliente.py, lib/indice_caixa.py)
  EXCLUIDOS em qualquer nivel: target/, node_modules/, __pycache__/, *.pyc; e ops/homologacao/saidas/ e
  ops/homologacao/evidencias/ (saidas de execucao, nao entradas). api/e2e fica fora: o surefire so inclui *Test/*IT e o
  E2E exige ambiente proprio (E2E_BASE).
"""
import hashlib
import os
import re
import sys

EXCLUIR_DIR = {'target', 'node_modules', '__pycache__'}
EXCLUIR_PREFIXO = ('ops/homologacao/saidas/', 'ops/homologacao/evidencias/')


class Recusa(Exception):
    pass


def _texto(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


def _bytes(p):
    with open(p, 'rb') as f:
        return f.read()


def modulos(raiz):
    p = os.path.join(raiz, 'api', 'pom.xml')
    if not os.path.isfile(p):
        raise Recusa('api/pom.xml ausente')
    t = _texto(p)
    bloco = re.search(r'<modules>(.*?)</modules>', t, re.S)
    ms = re.findall(r'<module>\s*([^<\s]+)\s*</module>', bloco.group(1)) if bloco else []
    if not ms:
        raise Recusa('api/pom.xml sem <modules>')
    return sorted(ms)


def _andar(raiz, rel):
    base = os.path.join(raiz, rel)
    if os.path.isfile(base):
        yield rel.replace(os.sep, '/')
        return
    if not os.path.isdir(base):
        return
    for d, subdirs, arqs in os.walk(base):
        subdirs[:] = sorted(s for s in subdirs if s not in EXCLUIR_DIR)
        for a in arqs:
            if a.endswith('.pyc'):
                continue
            yield os.path.relpath(os.path.join(d, a), raiz).replace(os.sep, '/')


def conjunto(raiz):
    raizes = ['api/pom.xml', 'api/mvnw', 'api/mvnw.cmd', 'api/.mvn', 'db/migrations', 'db/bootstrap', 'ops/homologacao']
    for m in modulos(raiz):
        raizes += [f'api/{m}/pom.xml', f'api/{m}/src']
    caminhos = set()
    for r in raizes:
        for c in _andar(raiz, r):
            if c.startswith(EXCLUIR_PREFIXO):
                continue
            if '\n' in c or '\r' in c or '\\' in c:
                raise Recusa(f'caminho nao representavel no manifesto: {c!r}')
            caminhos.add(c)
    return sorted(caminhos, key=lambda c: c.encode('utf-8'))


def _sha(b):
    return hashlib.sha256(b).hexdigest()


def manifesto(raiz, saida_bruto, saida_lf):
    cs = conjunto(raiz)
    if not cs:
        raise Recusa('conjunto de entrada vazio')
    with open(saida_bruto, 'w', encoding='utf-8', newline='\n') as fb, open(saida_lf, 'w', encoding='utf-8', newline='\n') as fl:
        for c in cs:
            b = _bytes(os.path.join(raiz, c))
            lf = b.replace(b'\r\n', b'\n')
            fb.write(_sha(b) + '  ' + c + '\n')
            fl.write(_sha(lf) + '  ' + c + '\n')
    return len(cs)


def ler(p):
    d = {}
    for n, l in enumerate(_texto(p).split('\n'), 1):
        if not l:
            continue
        m = re.fullmatch(r'([0-9a-f]{64})  (.+)', l)
        if not m or m.group(2) in d:
            raise Recusa(f'{p}: linha {n} ilegivel ou caminho repetido')
        d[m.group(2)] = m.group(1)
    return d


def comparar(a, b):
    x, y = ler(a), ler(b)
    dif = [f'ALTERADO {c}' for c in sorted(x.keys() & y.keys()) if x[c] != y[c]]
    dif += [f'REMOVIDO {c}' for c in sorted(x.keys() - y.keys())]
    dif += [f'ADICIONADO {c}' for c in sorted(y.keys() - x.keys())]
    return dif


def main(argv):
    try:
        if len(argv) == 5 and argv[1] == 'manifesto':
            n = manifesto(argv[2], argv[3], argv[4])
            print(f'{n} arquivos no conjunto de entrada')
            return 0
        if len(argv) == 4 and argv[1] == 'comparar':
            dif = comparar(argv[2], argv[3])
            print('\n'.join(dif) if dif else 'IDENTICOS')
            return 1 if dif else 0
        if len(argv) == 4 and argv[1] == 'verificar':
            ex, raiz = argv[2], argv[3]
            ok = True
            for c, h in ler(os.path.join(ex, 'manifesto.sha256')).items():
                p = os.path.join(ex, c)
                if not os.path.isfile(p) or _sha(_bytes(p)) != h:
                    print(f'INTEGRIDADE FALHA {c}'); ok = False
            print(f'INTEGRIDADE_EXECUCAO={"OK" if ok else "FALHA"}')
            res = _texto(os.path.join(ex, 'resultado.txt')) if ok else ''
            import tempfile
            with tempfile.TemporaryDirectory() as t:
                manifesto(raiz, os.path.join(t, 'b'), os.path.join(t, 'l'))
                dif = comparar(os.path.join(ex, 'fontes.sha256'), os.path.join(t, 'b'))
            for d in dif[:50]:
                print(d)
            print(f'ARVORE_ATUAL={"IDENTICA" if not dif else f"DIFERENTE ({len(dif)} caminhos)"}')
            permitido = ok and not dif and 'REAPROVEITAMENTO=PERMITIDO' in res.split('\n')
            print(f'REAPROVEITAMENTO_NA_ARVORE_ATUAL={"PERMITIDO" if permitido else "PROIBIDO"}')
            return 0 if permitido else 1
    except Recusa as e:
        print(f'RECUSADO: {e}', file=sys.stderr)
        return 3
    print(__doc__, file=sys.stderr)
    return 3


if __name__ == '__main__':
    sys.exit(main(sys.argv))
