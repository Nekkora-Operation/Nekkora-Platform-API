"""Reconciliacao de uma fixture declarada com resultado nao confirmado (desenho P1a, sec. 5.1).

  python fixture_conferencia.py <fixtures.txt> <execucao> < estado_atual.txt
  python fixture_conferencia.py classificar < saida_da_execucao.txt

classificar: le a saida de UMA execucao (linhas do psql e a ultima "codigo=<n>") e imprime a classificacao:
  - codigo 0, marca FIXTURE_COMMIT_CONCLUIDO e "verificacoes=OK"  -> APLICADA
  - marca presente (qualquer outro caso)                           -> APLICADA_COM_FALHA_POS_COMMIT (reconciliar)
  - sem marca, codigo 3 (erro do script, ON_ERROR_STOP) e ERROR: -> NAO_APLICADA (o psql parou antes do COMMIT)
  - qualquer outra coisa (conexao/resposta perdida, job falhou)    -> INDETERMINADA (reconciliar)

Le em fixtures.txt o bloco da execucao (cabecalho "== fixture <tipo> execucao=<id> ... horas=<h>") com as linhas
ANTES|<tabela>|<id>|<campo>|<epoch_us|NULL> gravadas antes do BEGIN, e compara com o estado atual (linhas
ESTADO|..., mesma consulta, somente leitura):
  - todos iguais ao ANTES                   -> NAO_APLICADA (codigo 0)
  - todos iguais a ANTES - deslocamento     -> APLICADA     (codigo 0; nulos continuam nulos)
  - qualquer outra coisa                    -> DIVERGENTE   (codigo 1: exige analise manual)
  - bloco sem linhas ANTES                  -> SEM_ESTADO_DE_PARTIDA (codigo 1)
Deslocamento: contatos 721 h; solicitacao, as horas do cabecalho.
"""
import re
import sys

US_H = 3600 * 1000000


def bloco(caminho, execucao):
    linhas = open(caminho, encoding='utf-8', errors='replace').read().splitlines()
    ini = next((i for i, l in enumerate(linhas) if l.startswith('== fixture ') and f'execucao={execucao}' in l.split()), None)
    if ini is None:
        raise SystemExit(f'execucao {execucao} nao encontrada em {caminho}')
    fim = next((i for i in range(ini + 1, len(linhas)) if linhas[i].startswith('== ')), len(linhas))
    return linhas[ini], linhas[ini + 1:fim]


def valores(linhas, prefixo):
    d = {}
    for l in linhas:
        if l.startswith(prefixo + '|'):
            _, t, i, c, v = l.split('|', 4)
            d[(t, i, c)] = None if v == 'NULL' else int(v)
    return d


def classificar(texto):
    linhas = texto.splitlines()
    codigos = [l[len('codigo='):] for l in linhas if l.startswith('codigo=')]
    rc = codigos[-1] if codigos else None
    marca = 'FIXTURE_COMMIT_CONCLUIDO' in linhas
    if rc == '0' and marca and any('verificacoes=OK' in l for l in linhas):
        return 'APLICADA'
    if marca:
        return 'APLICADA_COM_FALHA_POS_COMMIT'
    if rc == '3' and any('ERROR:' in l for l in linhas):
        return 'NAO_APLICADA'
    return 'INDETERMINADA'


def main():
    if sys.argv[1] == 'classificar':
        print(classificar(sys.stdin.read()))
        return 0
    cab, corpo = bloco(sys.argv[1], sys.argv[2])
    tipo = cab.split()[2]
    m = re.search(r'\bhoras=(\d+)', cab)
    desloc = 721 * US_H if tipo == 'contatos' else int(m.group(1)) * US_H if m else None
    antes = valores(corpo, 'ANTES')
    atual = valores(sys.stdin.read().splitlines(), 'ESTADO')
    if not antes:
        print('reconciliacao: SEM_ESTADO_DE_PARTIDA (o bloco nao tem linhas ANTES; analise manual)')
        return 1
    if set(antes) != set(atual):
        print(f'reconciliacao: DIVERGENTE (linhas alvo diferentes: antes={len(antes)} atual={len(atual)})')
        return 1
    if all(atual[k] == antes[k] for k in antes):
        print(f'reconciliacao: NAO_APLICADA ({len(antes)} campos iguais ao estado de partida)')
        return 0
    if desloc is not None and all((antes[k] is None and atual[k] is None) or
                                  (antes[k] is not None and atual[k] == antes[k] - desloc) for k in antes):
        print(f'reconciliacao: APLICADA ({len(antes)} campos = estado de partida - deslocamento declarado)')
        return 0
    print('reconciliacao: DIVERGENTE (nem o estado de partida nem o deslocamento declarado; analise manual)')
    return 1


if __name__ == '__main__':
    sys.exit(main())
