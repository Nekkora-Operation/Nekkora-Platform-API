#!/usr/bin/env python3
"""Inventario B12 da homologacao (PROPOSTA P4-R1, revisao 2): coleta SOMENTE LEITURA por ID de containers, redes, volumes
e imagens, com o codigo de saida e o erro de CADA comando, e avaliacao separada da coleta (testavel sem Docker).

  inventario_b12.py coletar <id-ambiente>                       > bruto.jsonl   (sempre codigo 0; o bruto registra tudo)
  inventario_b12.py comparar <id-ambiente> <antes.jsonl> <depois.jsonl>        (0 OK, 1 FALHA, 3 INCONCLUSIVO)

Origem (P4-R1, B12.1): a fotografia antiga do h016.sh perdia 4 imagens sem Config.Labels (template com .Id caia no JSON
bruto), descartava o codigo 1 e comparava dois inventarios igualmente incompletos.

Leitura (cada objeto):
  * inspect sem --format; o bruto guarda so os campos usados (nunca Env/Cmd), PRESERVANDO ausencia e tipo originais;
  * codigo != 0, saida vazia, JSON invalido, numero de objetos != 1 -> FALHA_LEITURA;
  * (rev. 2) estrutura validada por classe: tipos e campos obrigatorios, e IDENTIDADE = a solicitada (Id para containers,
    redes e imagens; Name para volumes). Objeto vazio ou de outro ID -> FALHA_LEITURA. Rotulos ausentes ou nulos
    continuam legitimos; identidade ausente nao;
  * erro "no such ..." E o ID fora de uma NOVA listagem -> DESAPARECIDO; com o ID ainda listado -> FALHA_LEITURA;
  * falha da propria listagem, ou objeto listado e nao inspecionado -> inventario incompleto.
Classificacao (por fotografia; a classe vem do comando de listagem, nunca do formato do ID):
  * containers, redes, imagens: do AMBIENTE se rotulo nekkora.homologacao=<id> (containers: tambem o rotulo da listagem);
  * volumes: do AMBIENTE se rotulo nekkora.homologacao=<id>, ou se for ANONIMO (rotulo com.docker.volume.anonymous)
    E montado por >= 1 container do ambiente E por NENHUM container protegido. Montagem pelo ensaio, sozinha, nao
    comprova propriedade (volume nomeado sem rotulo montado pelo ensaio continua protegido).
Comparacao:
  * (rev. 2) PROTECAO MONOTONICA: objeto presente nas duas fotografias com classificacao diferente -> FALHA
    ("classificacao mudou"); um objeto so e do ambiente se for do ambiente em toda fotografia em que foi classificado;
  * (rev. 2) atributos protegidos incluem as montagens dos containers e QUEM monta cada volume;
  * qualquer leitura incompleta -> INCONCLUSIVO (3); protegido novo, ausente, DESAPARECIDO, alterado ou com classificacao
    mudada -> FALHA (1); senao OK (0).
"""
import json
import re
import subprocess
import sys

ROTULO = 'nekkora.homologacao'
ANONIMO = 'com.docker.volume.anonymous'
CLASSES = ('containers', 'redes', 'volumes', 'imagens')
LISTAR = {
    'containers': ['docker', 'ps', '-a', '--no-trunc', '--format', '{{.ID}}\t{{.Label "nekkora.homologacao"}}'],
    'redes': ['docker', 'network', 'ls', '-q', '--no-trunc'],
    'volumes': ['docker', 'volume', 'ls', '-q'],
    'imagens': ['docker', 'images', '-q', '--no-trunc'],
}
INSPECIONAR = {
    'containers': ['docker', 'container', 'inspect'],
    'redes': ['docker', 'network', 'inspect'],
    'volumes': ['docker', 'volume', 'inspect'],
    'imagens': ['docker', 'image', 'inspect'],
}
SUMIU = re.compile(r'no such (container|object|image|volume|network)|network \S+ not found', re.I)
IDENTIDADE = {'containers': 'Id', 'redes': 'Id', 'volumes': 'Name', 'imagens': 'Id'}


def executar(cmd):
    """Executor real (substituido por um falso nos testes). Devolve (codigo, stdout, stderr)."""
    p = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
    return p.returncode, p.stdout, p.stderr


def listar(classe, run):
    rc, out, err = run(LISTAR[classe])
    pares = [x.rstrip(chr(13)).split(chr(9), 1) for x in out.splitlines() if x.strip()]
    ids = sorted(set(p[0].strip() for p in pares))
    # containers: o rotulo vem JA na listagem, para classificar um container que some antes da inspecao (ex.: --rm)
    rot = {p[0].strip(): p[1].strip() for p in pares if len(p) == 2 and p[1].strip()}
    return {'tipo': 'listagem', 'classe': classe, 'rc': rc, 'erro': err.strip()[:500], 'ids': ids, 'rotulo_listagem': rot}


def _pega(d, *ks):
    """Copia so as chaves pedidas, mantendo ausencia e o VALOR ORIGINAL (tipo errado continua errado para a validacao)."""
    return {k: d[k] for k in ks if k in d} if isinstance(d, dict) else d


def _sub(o, k, *ks):
    """o[k] reduzido as chaves ks se for dict; o valor cru se for outro tipo; ausente se ausente."""
    return {} if k not in o else {k: _pega(o[k], *ks)}


def _reduzir(classe, o):
    """So os campos que a avaliacao usa: o bruto nunca guarda Env, Cmd, caminhos de segredo etc."""
    if not isinstance(o, dict):
        return o
    if classe == 'containers':
        r = _pega(o, 'Id', 'Name', 'Image', 'RestartCount')
        r.update(_sub(o, 'State', 'Status', 'StartedAt'))
        r.update(_sub(o, 'Config', 'Labels'))
        if 'Mounts' in o:
            m = o['Mounts']
            r['Mounts'] = [_pega(x, 'Type', 'Name', 'Destination') for x in m] if isinstance(m, list) else m
        return r
    if classe == 'redes':
        r = _pega(o, 'Id', 'Name', 'Labels')
        if 'IPAM' in o:
            ip = o['IPAM']
            if isinstance(ip, dict) and isinstance(ip.get('Config'), list):
                r['IPAM'] = {'Config': [_pega(c, 'Subnet') for c in ip['Config']]}
            else:
                r['IPAM'] = _pega(ip, 'Config')
        return r
    if classe == 'volumes':
        return _pega(o, 'Name', 'CreatedAt', 'Driver', 'Labels')
    r = _pega(o, 'Id', 'RepoTags')
    r.update(_sub(o, 'Config', 'Labels'))
    return r


def coletar(run=executar):
    """Uma linha JSON por listagem e por objeto (reduzido). Nao avalia nada; registra codigo e erro de cada comando."""
    linhas = []
    for classe in CLASSES:
        lst = listar(classe, run)
        linhas.append(lst)
        if lst['rc'] != 0:
            continue
        for oid in lst['ids']:
            rc, out, err = run(INSPECIONAR[classe] + [oid])
            reg = {'tipo': 'objeto', 'classe': classe, 'id': oid, 'rc': rc, 'erro': err.strip()[:500], 'bytes': len(out or '')}
            try:
                arr = json.loads(out or '')
            except ValueError as e:
                reg['json_erro'] = str(e)                       # nunca o conteudo cru
            else:
                reg['n'] = len(arr) if isinstance(arr, list) else None
                reg['objetos'] = [_reduzir(classe, x) for x in arr] if isinstance(arr, list) else []
            if rc != 0 and SUMIU.search(err or ''):
                rel = listar(classe, run)
                reg['relistagem_rc'] = rel['rc']
                reg['ainda_listado'] = oid in rel['ids'] if rel['rc'] == 0 else None
            linhas.append(reg)
    return linhas


# ------------------------------------------------------------------------------------------ validacao (rev. 2)
def _texto(v, vazio_ok=False):
    return isinstance(v, str) and (vazio_ok or v != '')


def _rotulos_ok(v):
    """Rotulos: ausentes (o chamador nao passa), nulos ou dict texto -> texto. Qualquer outra forma e leitura invalida."""
    return v is None or (isinstance(v, dict) and all(isinstance(k, str) and isinstance(x, str) for k, x in v.items()))


def validar(classe, oid, o):
    """-> lista de problemas (vazia = objeto completo e e o objeto pedido)."""
    if not isinstance(o, dict):
        return ['objeto nao e um mapa']
    p = []
    chave = IDENTIDADE[classe]
    if chave not in o:
        p.append(f'identidade ausente ({chave})')
    elif o[chave] != oid:
        p.append(f'identidade divergente: pedido {oid}, devolvido {o[chave]!r}')
    if classe == 'containers':
        if not _texto(o.get('Name')):
            p.append('Name ausente ou invalido')
        if not _texto(o.get('Image')):
            p.append('Image ausente ou invalido')
        rcnt = o.get('RestartCount')
        if not isinstance(rcnt, int) or isinstance(rcnt, bool):
            p.append('RestartCount ausente ou invalido')
        st = o.get('State')
        if not isinstance(st, dict) or not _texto(st.get('Status')) or not _texto(st.get('StartedAt')):
            p.append('State/Status/StartedAt ausente ou invalido')
        cfg = o.get('Config')
        if not isinstance(cfg, dict):
            p.append('Config ausente ou invalido')
        elif 'Labels' in cfg and not _rotulos_ok(cfg['Labels']):
            p.append('Config.Labels invalido')
        mts = o.get('Mounts')
        if not isinstance(mts, list) or not all(isinstance(m, dict) and _texto(m.get('Type')) and _texto(m.get('Destination'))
                                                 and (m.get('Type') != 'volume' or _texto(m.get('Name'))) for m in mts):
            p.append('Mounts ausente ou invalido')
    elif classe == 'redes':
        if not _texto(o.get('Name')):
            p.append('Name ausente ou invalido')
        if 'Labels' in o and not _rotulos_ok(o['Labels']):
            p.append('Labels invalido')
        ip = o.get('IPAM')
        cfg = ip.get('Config') if isinstance(ip, dict) else 'x'
        if not isinstance(ip, dict) or not (cfg is None or (isinstance(cfg, list) and all(
                isinstance(c, dict) and (('Subnet' not in c) or _texto(c['Subnet'])) for c in cfg))):
            p.append('IPAM ausente ou invalido')
    elif classe == 'volumes':
        if not _texto(o.get('CreatedAt')):
            p.append('CreatedAt ausente ou invalido')
        if not _texto(o.get('Driver')):
            p.append('Driver ausente ou invalido')
        if 'Labels' in o and not _rotulos_ok(o['Labels']):
            p.append('Labels invalido')
    else:
        tags = o.get('RepoTags', 'ausente')
        if not (tags is None or (isinstance(tags, list) and all(_texto(t) for t in tags))):
            p.append('RepoTags ausente ou invalido')
        cfg = o.get('Config')
        if 'Config' in o and cfg is not None and not isinstance(cfg, dict):
            p.append('Config invalido')
        elif isinstance(cfg, dict) and 'Labels' in cfg and not _rotulos_ok(cfg['Labels']):
            p.append('Config.Labels invalido')
    return p


def _rotulos(classe, o):
    lab = (o.get('Config') or {}).get('Labels') if classe in ('containers', 'imagens') else o.get('Labels')
    return lab or {}                                    # ausente ou nulo: ausencia legitima


def _atributos(classe, o):
    """O que tem de ficar identico num recurso protegido (volumes: 'MontadoPor' e acrescentado na normalizacao)."""
    if classe == 'containers':
        st = o['State']
        return {'Id': o['Id'], 'Name': o['Name'], 'Image': o['Image'], 'Status': st['Status'], 'StartedAt': st['StartedAt'],
                'RestartCount': o['RestartCount'],
                'Mounts': sorted((m['Type'], m.get('Name') or '', m['Destination']) for m in o['Mounts'])}
    if classe == 'redes':
        return {'Id': o['Id'], 'Name': o['Name'],
                'Subnets': sorted(c.get('Subnet', '') for c in (o['IPAM'].get('Config') or []))}
    if classe == 'volumes':
        return {'Name': o['Name'], 'CreatedAt': o['CreatedAt'], 'Driver': o['Driver']}
    return {'Id': o['Id'], 'RepoTags': sorted(o.get('RepoTags') or [])}


def normalizar(linhas, ambiente):
    """-> (objetos {(classe, id): {...}}, problemas [texto]). Estado: OK | DESAPARECIDO | FALHA_LEITURA.
    'ambiente': True/False por esta fotografia; None quando nao ha como classificar (desaparecido sem rotulo)."""
    objs, problemas = {}, []
    listados = {c: None for c in CLASSES}
    rot_lst = {}
    for ln in linhas:
        if ln.get('tipo') == 'listagem':
            if ln.get('rc') != 0:
                problemas.append(f"listagem de {ln['classe']} falhou (codigo {ln.get('rc')}): {ln.get('erro', '')}")
            else:
                listados[ln['classe']] = set(ln.get('ids') or [])
                rot_lst.update({(ln['classe'], k): v for k, v in (ln.get('rotulo_listagem') or {}).items()})
    for c in CLASSES:
        if listados[c] is None and not any(p.startswith(f'listagem de {c} ') for p in problemas):
            problemas.append(f'listagem de {c} ausente do bruto')
    for ln in linhas:
        if ln.get('tipo') != 'objeto':
            continue
        c, oid = ln['classe'], ln['id']
        reg = {'classe': c, 'id': oid, 'estado': 'FALHA_LEITURA', 'rotulos': {}, 'attrs': None, 'detalhe': '', 'montagens': []}
        if ln.get('rc') != 0:
            if SUMIU.search(ln.get('erro') or '') and ln.get('ainda_listado') is False:
                reg['estado'], reg['detalhe'] = 'DESAPARECIDO', ln.get('erro', '')
            else:
                reg['detalhe'] = f"codigo {ln.get('rc')}: {ln.get('erro', '')}"
        elif 'json_erro' in ln:
            reg['detalhe'] = f"JSON invalido ou saida vazia/incompleta ({ln.get('bytes')} bytes): {ln['json_erro']}"
        elif ln.get('n') != 1 or len(ln.get('objetos') or []) != 1:
            reg['detalhe'] = f"saida com {ln.get('n')} objetos (esperado 1)"
        else:
            o = ln['objetos'][0]
            erros = validar(c, oid, o)
            if erros:
                reg['detalhe'] = 'objeto incompleto ou nao corresponde ao pedido: ' + '; '.join(erros)
            else:
                reg.update(estado='OK', rotulos=_rotulos(c, o), attrs=_atributos(c, o))
                if c == 'containers':
                    reg['montagens'] = [m['Name'] for m in o['Mounts'] if m['Type'] == 'volume']
        objs[(c, oid)] = reg
        if reg['estado'] == 'FALHA_LEITURA':
            problemas.append(f'{c} {oid}: leitura incompleta ({reg["detalhe"]})')
    for c in CLASSES:
        for oid in sorted(listados[c] or ()):
            if (c, oid) not in objs:
                problemas.append(f'{c} {oid}: listado e nao inspecionado (coleta incompleta)')
    # classificacao desta fotografia
    for r in objs.values():
        if r['classe'] == 'volumes':
            continue
        rot = r['rotulos'].get(ROTULO) if r['estado'] == 'OK' else rot_lst.get((r['classe'], r['id']))
        r['ambiente'] = (rot == ambiente) if (r['estado'] == 'OK' or rot is not None) else None
    montado_por = {}
    for r in objs.values():
        if r['classe'] == 'containers' and r['estado'] == 'OK':
            for v in r['montagens']:
                montado_por.setdefault(v, []).append((r['id'], r['ambiente']))
    for r in objs.values():
        if r['classe'] != 'volumes':
            continue
        if r['estado'] != 'OK':
            r['ambiente'] = None
            continue
        usos = montado_por.get(r['id'], [])
        anonimo_do_ambiente = (ANONIMO in r['rotulos'] and any(a is True for _, a in usos)
                               and all(a is True for _, a in usos))
        r['ambiente'] = r['rotulos'].get(ROTULO) == ambiente or anonimo_do_ambiente
        r['attrs']['MontadoPor'] = sorted(cid for cid, _ in usos)
    return objs, problemas


def comparar(antes, depois, ambiente):
    """-> (codigo, linhas de relatorio). 0 OK, 1 FALHA, 3 INCONCLUSIVO."""
    oa, pa = normalizar(antes, ambiente)
    od, pd = normalizar(depois, ambiente)
    rel = []
    mudou, amb = [], set()
    for k in sorted(set(oa) | set(od)):
        flags = [x[k]['ambiente'] for x in (oa, od) if k in x and x[k]['ambiente'] is not None]
        if len(flags) == 2 and flags[0] != flags[1]:
            mudou.append(f"{k[0]} {k[1]}: classificacao mudou entre as fotografias "
                         f"({'ambiente' if flags[0] else 'protegido'} -> {'ambiente' if flags[1] else 'protegido'})")
        elif flags and all(flags):
            amb.add(k)                                  # do ambiente em TODA fotografia em que foi classificado
    prot_a = {k: r for k, r in oa.items() if k not in amb}
    prot_d = {k: r for k, r in od.items() if k not in amb}
    for c in CLASSES:
        rel.append(f"{c}: antes {sum(1 for k in oa if k[0] == c)} (protegidos {sum(1 for k in prot_a if k[0] == c)}), "
                   f"depois {sum(1 for k in od if k[0] == c)} (protegidos {sum(1 for k in prot_d if k[0] == c)}), "
                   f"do ambiente {sum(1 for k in amb if k[0] == c)}")
    sem_rotulo = sum(1 for r in list(oa.values()) + list(od.values()) if r['estado'] == 'OK' and not r['rotulos'])
    rel.append(f'objetos sem rotulo (ausencia legitima, lidos por completo): {sem_rotulo}')
    if pa or pd:
        rel += [f'INCOMPLETO antes: {p}' for p in pa] + [f'INCOMPLETO depois: {p}' for p in pd]
        rel.append('B12 INCONCLUSIVO: inventario incompleto; nenhuma conclusao de identidade')
        return 3, rel
    dif = list(mudou)
    for k in sorted(set(prot_a) | set(prot_d)):
        a, d = prot_a.get(k), prot_d.get(k)
        if a is None:
            dif.append(f'{k[0]} {k[1]}: protegido NOVO')
        elif d is None:
            dif.append(f'{k[0]} {k[1]}: protegido AUSENTE depois')
        elif 'DESAPARECIDO' in (a['estado'], d['estado']):
            dif.append(f'{k[0]} {k[1]}: protegido DESAPARECEU durante a coleta ({a["estado"]} -> {d["estado"]})')
        elif a['attrs'] != d['attrs']:
            dif.append(f'{k[0]} {k[1]}: protegido ALTERADO {a["attrs"]} -> {d["attrs"]}')
    desap_amb = sorted(f'{k[0]} {k[1]}' for k in amb if 'DESAPARECIDO' in (oa.get(k, {}).get('estado'), od.get(k, {}).get('estado')))
    if desap_amb:
        rel.append('do ambiente, desaparecidos durante a coleta (esperado, ex. --rm): ' + ', '.join(desap_amb))
    if dif:
        rel += dif + ['B12 FALHA: diferenca em recurso protegido ou mudanca de classificacao']
        return 1, rel
    rel.append('B12 OK: inventario completo e identico por ID fora do ambiente (containers, redes, volumes, imagens)')
    return 0, rel


def _ler(path):
    with open(path, encoding='utf-8') as f:
        return [json.loads(x) for x in f if x.strip()]


def main(argv):
    if len(argv) >= 3 and argv[1] == 'coletar':
        for ln in coletar():
            print(json.dumps(ln, ensure_ascii=False, sort_keys=True))
        return 0
    if len(argv) == 5 and argv[1] == 'comparar':
        try:
            antes, depois = _ler(argv[3]), _ler(argv[4])
            codigo, rel = comparar(antes, depois, argv[2])
        except Exception as e:   # P4-R2a: qualquer erro na avaliacao e INCONCLUSIVO, nunca o codigo 1 (= FALHA)
            print(f'B12 INCONCLUSIVO: bruto ilegivel, malformado ou erro na avaliacao ({type(e).__name__}: {e})')
            return 3
        print('\n'.join(rel))
        return codigo
    print(__doc__, file=sys.stderr)
    return 64


if __name__ == '__main__':
    sys.exit(main(sys.argv))
