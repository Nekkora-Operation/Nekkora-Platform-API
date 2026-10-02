"""Redes e configuracao gerada da homologacao (desenho P1a, §3 e §7).

  python redes.py sobreposicao <cidr> [<cidr> ...] < subredes_existentes.txt
      Le as sub-redes das redes Docker existentes (uma por linha, IPv4 ou IPv6; linhas vazias ignoradas) e recusa
      (codigo 1) se QUALQUER candidata se sobrepuser a QUALQUER existente - igualdade, rede maior contendo a candidata
      ou rede menor contida nela (ipaddress.overlaps). Candidatas que se sobrepoem entre si tambem sao recusadas.
      Saida: uma linha por conflito; "sem sobreposicao" quando nao ha.

  python redes.py prefixo <a.b.c>
      Confere o formato do prefixo /24 (tres octetos 0-255, sem zeros a esquerda) - codigo 1 se invalido.

  python redes.py gerar <modelo> <saida> CHAVE=valor [CHAVE=valor ...]
      Substitui @@CHAVE@@ no modelo; recusa (codigo 1) se sobrar marcador sem valor ou se uma chave nao for usada.
      A saida e criada nova (recusa se ja existir).

  python redes.py trocar-prefixo <entrada> <saida> <prefixo_antigo> <prefixo_novo>
      Copia um arquivo ESTATICO (ex.: pg_hba.conf e nginx.conf do caminho 015) trocando o prefixo /24 de todos os
      enderecos; recusa sem troca ou se sobrar o prefixo antigo. Saida criada nova. (P3-R015: 015 em sub-rede de ensaio.)
"""
import ipaddress
import os
import re
import sys


def sobreposicao(candidatas, existentes):
    cand = [ipaddress.ip_network(c, strict=True) for c in candidatas]
    conflitos = []
    for i, a in enumerate(cand):
        for b in cand[i + 1:]:
            if a.version == b.version and a.overlaps(b):
                conflitos.append(f'candidatas sobrepostas: {a} x {b}')
    for linha in existentes:
        linha = linha.strip()
        if not linha:
            continue
        try:
            ex = ipaddress.ip_network(linha, strict=False)
        except ValueError:
            conflitos.append(f'sub-rede existente ilegivel: {linha!r} (recusa por seguranca)')
            continue
        for a in cand:
            if a.version == ex.version and a.overlaps(ex):
                conflitos.append(f'{a} sobrepoe a rede existente {ex}')
    return conflitos


def prefixo_valido(p):
    partes = p.split('.')
    return len(partes) == 3 and all(re.fullmatch(r'(0|[1-9][0-9]{0,2})', x) and int(x) <= 255 for x in partes)


def gerar(modelo, saida, pares):
    texto = open(modelo, encoding='utf-8').read()
    valores = dict(p.split('=', 1) for p in pares)
    for k, v in valores.items():
        marcador = f'@@{k}@@'
        if marcador not in texto:
            raise SystemExit(f'chave sem uso no modelo: {k}')
        texto = texto.replace(marcador, v)
    resto = re.findall(r'@@[A-Z_]+@@', texto)
    if resto:
        raise SystemExit(f'marcadores sem valor: {sorted(set(resto))}')
    with open(saida, 'x', encoding='utf-8', newline='\n') as h:
        h.write(texto)


def trocar_prefixo(entrada, saida, antigo, novo):
    """Copia <entrada> trocando o prefixo /24 <antigo> por <novo> em todo endereco IPv4 "a.b.c.d" (e "a.b.c.0/24");
    recusa se os prefixos forem invalidos ou iguais, se nao houver troca, ou se sobrar o prefixo antigo."""
    if not (prefixo_valido(antigo) and prefixo_valido(novo)) or antigo == novo:
        raise SystemExit('prefixos invalidos ou iguais')
    texto = open(entrada, encoding='utf-8').read()
    padrao = re.compile(r'(?<![0-9.])' + re.escape(antigo) + r'\.(?=[0-9])')
    novo_texto, n = padrao.subn(novo + '.', texto)
    if n == 0:
        raise SystemExit(f'nenhuma ocorrencia de {antigo}. em {entrada}')
    if padrao.search(novo_texto):
        raise SystemExit(f'sobrou {antigo}. em {saida}')
    with open(saida, 'x', encoding='utf-8', newline='\n') as h:
        h.write(novo_texto)
    print(f'{n} endereco(s) {antigo}.x -> {novo}.x em {os.path.basename(saida)}')


if __name__ == '__main__':
    acao = sys.argv[1]
    if acao == 'sobreposicao':
        c = sobreposicao(sys.argv[2:], sys.stdin.read().splitlines())
        print('\n'.join(c) if c else 'sem sobreposicao')
        sys.exit(1 if c else 0)
    elif acao == 'prefixo':
        sys.exit(0 if prefixo_valido(sys.argv[2]) else 1)
    elif acao == 'gerar':
        gerar(sys.argv[2], sys.argv[3], sys.argv[4:])
    elif acao == 'trocar-prefixo':
        trocar_prefixo(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5])
    else:
        raise SystemExit('acao desconhecida')
