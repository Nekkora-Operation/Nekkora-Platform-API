#!/usr/bin/env python3
"""Varredura da evidencia sanitizada da 016 (tokens da v1, chaves e senhas).

Uso:
  varredura.py varrer <dir-evidencia> <repo>                  -> relatorio; codigo 0 = sem achados, 1 = achados
  varredura.py controle <dir-evidencia> <repo> <tokens.txt>   -> controle positivo; codigo 0 = todos detectados
  varredura.py classificar <dir-evidencia> <repo>             -> classifica TODO candidato (superconjunto)

Regra de token: palavra INTEIRA de 43 caracteres base64url cujo ultimo caractere e valido para 32 bytes
(formato de token da v1, contrato 8.3) e com ao menos uma maiuscula. Excluem-se so identificadores que aparecem
literalmente no codigo do repositorio (catalogo, nomes de teste). Um token aleatorio escapa da regra com
probabilidade ~ (38/64)^43 ~ 2e-10 (nenhuma maiuscula). Marcadores de chave e senha sao montados em partes para
o proprio relatorio nao casar consigo mesmo.
"""
import os
import re
import sys

TOKEN = re.compile(r'(?<![A-Za-z0-9_-])([A-Za-z0-9_-]{42}[AEIMQUYcgkosw048])(?![A-Za-z0-9_-])')
CANDIDATO = re.compile(r'[A-Za-z0-9_-]{43,}')
MARCAS = ['BEGIN ' + x for x in ('CERTIFICATE', 'PRIVATE KEY', 'EC PRIVATE KEY')] + ['ssl' + 'password', 'pass' + 'word=']
FONTES = ('.java', '.sql', '.sh', '.py', '.md', '.xml', '.yml', '.html')
IGNORAR_DIRS = {'.git', 'target', 'node_modules', 'saidas'}


def identificadores_do_codigo(repo):
    ids = set()
    for d, dirs, fs in os.walk(repo):
        dirs[:] = [x for x in dirs if x not in IGNORAR_DIRS]
        for f in fs:
            if f.endswith(FONTES):
                try:
                    t = open(os.path.join(d, f), encoding='utf-8', errors='replace').read()
                except OSError:
                    continue
                ids.update(CANDIDATO.findall(t))
    return ids


def arquivos(raiz, pular=('varredura.txt', 'maven.txt')):
    for d, _, fs in os.walk(raiz):
        for f in sorted(fs):
            if f in pular:
                continue
            p = os.path.join(d, f)
            yield os.path.relpath(p, raiz).replace(os.sep, '/'), open(p, encoding='utf-8', errors='replace').read()


def tokens_em(texto, ids):
    return [m for m in TOKEN.findall(texto) if re.search('[A-Z]', m) and m not in ids]


def varrer(raiz, repo, pular=('varredura.txt', 'maven.txt')):
    ids = identificadores_do_codigo(repo)
    achados, n = [], 0
    for rel, t in arquivos(raiz, pular):
        n += 1
        k = len(tokens_em(t, ids))
        if k:
            achados.append(f'formato de token: {k} em {rel}')
        for m in MARCAS:
            if m in t:
                achados.append(f'marcador de chave ou senha em {rel}')
    return n, achados


def classe(w, ids):
    if set(w) == {'-'}:
        return 'SEPARADOR (so tracos)'
    if re.fullmatch(r'[0-9a-f]+', w):
        return 'HEX (hash SHA-256 ou similar)'
    if w in ids:
        return 'IDENTIFICADOR presente no codigo do repositorio'
    if re.fullmatch(r'[a-z0-9_-]+', w):
        return 'MINUSCULAS/DIGITOS (nome de arquivo ou identificador; sem maiuscula)'
    if TOKEN.fullmatch(w) and re.search('[A-Z]', w):
        return 'FORMATO DE TOKEN (achado real)'
    return 'OUTRO (comprimento ou ultimo caractere incompativeis com token de 32 bytes)'


def main():
    modo, raiz, repo = sys.argv[1], sys.argv[2], sys.argv[3]
    if modo == 'varrer':
        n, achados = varrer(raiz, repo)
        bruto = 0
        mv = os.path.join(raiz, 'maven.txt')
        if os.path.exists(mv):
            bruto = len(tokens_em(open(mv, encoding='utf-8', errors='replace').read(), identificadores_do_codigo(repo)))
        print(f'arquivos varridos: {n} (log bruto do Maven varrido a parte: {bruto} ocorrencias)')
        print('achados: ' + str(len(achados) + (1 if bruto else 0)))
        for a in achados:
            print('  ' + a)
        sys.exit(1 if achados or bruto else 0)
    if modo == 'controle':
        # evidencia real + arquivo plantado com tokens gerados pela classe de producao (com.nekkora.core.token.Tokens)
        plantados = [x.strip() for x in open(sys.argv[4], encoding='utf-8') if x.strip()]
        ids = identificadores_do_codigo(repo)
        base = sum(len(tokens_em(t, ids)) for _, t in arquivos(raiz))
        texto = '\n'.join(f'linha de log sintetica token={tk} fim' for tk in plantados) + '\n' + '\n'.join(MARCAS) + '\n'
        detectados = len(tokens_em(texto, ids))
        marcas = sum(1 for m in MARCAS if m in texto)
        print(f'tokens plantados: {len(plantados)} (gerados por Tokens.newToken da v1)')
        print(f'tokens detectados: {detectados}; marcadores plantados: {len(MARCAS)}; detectados: {marcas}')
        print(f'achados na evidencia real antes do plantio: {base}')
        ok = len(plantados) >= 100 and detectados == len(plantados) and marcas == len(MARCAS) and base == 0
        print('controle positivo: ' + ('OK' if ok else 'FALHOU'))
        sys.exit(0 if ok else 1)
    if modo == 'classificar':
        ids = identificadores_do_codigo(repo)
        from collections import Counter
        por_classe, linhas = Counter(), []
        for rel, t in arquivos(raiz):
            for w in CANDIDATO.findall(t):
                c = classe(w, ids)
                por_classe[c] += 1
                amostra = w if not c.startswith('FORMATO') else w[:6] + '...(omitido)'
                linhas.append(f'{rel}\t{c}\t{amostra}')
        print(f'candidatos (toda sequencia >= 43 caracteres do alfabeto base64url): {sum(por_classe.values())}')
        for c, k in sorted(por_classe.items()):
            print(f'  {k:4d}  {c}')
        print('--- por ocorrencia (arquivo, classe, valor) ---')
        print('\n'.join(linhas))
        sys.exit(1 if any(c.startswith('FORMATO') for c in por_classe) else 0)


if __name__ == '__main__':
    main()
