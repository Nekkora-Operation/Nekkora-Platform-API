"""WIN-TRANSPORT-0: correlacao temporal entre as excecoes do transporte e as LIBERACOES (delete) da trava pelo
escritor externo, no CSV do harness (caso H4, que registra cada liberacao com inicio e duracao).

  python correlacao.py <H4.csv> [folga_us]

Para cada chamada do transporte que terminou em EXCECAO, verifica se a janela [inicio, inicio+dur] se sobrepoe (com a
folga dada, padrao 0) a alguma janela de liberacao do escritor na MESMA rodada. Compara com a taxa de acaso: a fracao
das chamadas SEM excecao que tambem se sobrepoem a uma liberacao. So mede; nao conclui sozinho.
"""
import bisect
import csv
import sys
from collections import defaultdict


def main(p, folga_us=0):
    folga = int(folga_us) * 1000
    lib = defaultdict(list)      # rodada -> [(ini, fim)]
    chamadas = []                # (rodada, ini, fim, excecao?)
    with open(p, encoding='utf-8') as f:
        for l in csv.DictReader(f):
            r, ini, dur = int(l['rodada']), int(l['inicio_ns']), int(l['dur_ns'])
            if l['ator'] == 'escritor_libera' and l['resultado'] == 'LIBEROU':
                lib[r].append((ini, ini + dur))
            elif l['ator'] in ('t1', 't2'):
                chamadas.append((r, ini, ini + dur, l['resultado'] == 'EXCECAO'))
    for r in lib:
        lib[r].sort()
    inicios = {r: [a for a, _ in v] for r, v in lib.items()}

    def sobrepoe(r, a, b):
        v = lib.get(r, [])
        i = bisect.bisect_left(inicios.get(r, []), b + folga)
        for j in range(max(0, i - 50), min(len(v), i + 1)):
            x, y = v[j]
            if x - folga <= b and a <= y + folga:
                return True
        return False

    exc = [c for c in chamadas if c[3]]
    ok = [c for c in chamadas if not c[3]]
    s_exc = sum(sobrepoe(r, a, b) for r, a, b, _ in exc)
    s_ok = sum(sobrepoe(r, a, b) for r, a, b, _ in ok)
    print(f'liberacoes do escritor: {sum(len(v) for v in lib.values())}')
    print(f'chamadas com EXCECAO: {len(exc)}; sobrepostas a uma liberacao: {s_exc} ({100 * s_exc / max(1, len(exc)):.1f}%)')
    print(f'chamadas sem excecao: {len(ok)}; sobrepostas a uma liberacao: {s_ok} ({100 * s_ok / max(1, len(ok)):.1f}%) <- taxa de acaso')
    print(f'folga: {folga_us} us')


if __name__ == '__main__':
    main(*sys.argv[1:])
