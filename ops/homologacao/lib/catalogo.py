"""Normaliza a listagem de catalogo (db/tests/portal-013/sql/c_listagem_catalogo.sql) para comparar instalacoes feitas
por identidades diferentes: o nome do INSTALADOR (dono dos objetos que as migrations nao reatribuem, dono dos
privilegios padrao e das extensoes) vira "INSTALADOR". So troca o nome como CAMPO inteiro (entre |, =, virgula, chaves
ou fim de linha): nekkora_platform nunca vira INSTALADOR_platform.

Uso: python catalogo.py <entrada> <saida> <nome1> [<nome2> ...]
"""
import re
import sys

entrada, saida, nomes = sys.argv[1], sys.argv[2], sys.argv[3:]
rx = re.compile(r'(?<=[|={,])(' + '|'.join(map(re.escape, nomes)) + r')(?=[|,}]|$)')
with open(entrada, encoding='utf-8') as e, open(saida, 'w', encoding='utf-8', newline='\n') as s:
    linhas = sorted({rx.sub('INSTALADOR', l.rstrip('\n')) for l in e if l.strip()})
    s.write('\n'.join(linhas) + '\n')
