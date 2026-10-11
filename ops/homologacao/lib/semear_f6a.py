"""Semeadura COMPLEMENTAR da validacao administrativa (F6a-V-T): contas e concessoes DEDICADAS (f6av-*) no
ensaio-frontend, sobre a semeadura da F1.5 ja CONCLUIDA. Aditiva: nao altera nenhuma linha existente.

Catalogo (este arquivo) -> plano JSON (ids, contas com PHC) -> sql/semear_f6a.sql (UMA transacao, como admin_cluster).
Contas sinteticas @demo.nekkora.test, uso "f6a-alvo": NAO sao de navegacao (credencial_navegacao.py as recusa) nem das
jornadas. O MFA delas e cadastrado pela API (testes/validar_f6a.py preparar), nunca pela semeadura.

Subcomandos:
  semear         --segredos DIR --evid DIR --sql ARQ --psql CMD --phc CMD [--bash CAMINHO]
  estado         --segredos DIR --psql CMD [--evid DIR] [--bash CAMINHO]
  catalogo       (resumo do catalogo, sem segredos; usado pelos testes)

Garantias (as mesmas da semeadura da F1.5, lib/semear_frontend.py, de onde vem o codigo comum):
  - TRAVA: a MESMA de semear_frontend.py (<segredos>/frontend.trava): as duas semeaduras se excluem mutuamente;
  - BASE: exige a semeadura da F1.5 CONCLUIDA (plano final no disco E organizacao no banco); senao RECUSA (codigo 14);
  - ESTADOS (disco + banco): AUSENTE, PENDENTE, A_RECONCILIAR, CONCLUIDA, CONCLUIDA_SEM_MAPA (com --evid),
    INCONSISTENTE (ex.: conta f6av-* no banco sem plano no disco), SEM_BANCO;
  - o plano (com as senhas) e gravado ATOMICAMENTE e de forma EXCLUSIVA antes da transacao; falha = nada no banco;
  - a transacao recusa se qualquer f6av-* ja existir, grava, confere as contagens antes do COMMIT;
  - resultado do psql perdido: decide pela conta do plano no banco, nunca pelo codigo do psql;
  - promocao: confere a identidade do pendente, grava o mapa publico e SO ENTAO renomeia.
"""
import argparse
import json
import os
import random
import secrets
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import semear_frontend as sf  # noqa: E402

DOMINIO = sf.DOMINIO
PREFIXO = 'f6av-'
USO = 'f6a-alvo'
LEITURA = {'ws': [('sandbox', 'VIEWER')]}
CONTAS = [
    # chave, tipo, nome, papeis
    ('f6av-suspensao', 'interna', 'Validação F6a · Suspensão', LEITURA),
    ('f6av-reativacao', 'interna', 'Validação F6a · Reativação', LEITURA),
    ('f6av-mfa', 'interna', 'Validação F6a · Redefinição de MFA', LEITURA),
    ('f6av-cliente', 'externa', 'Validação F6a · Cliente externo', {'cliente': 'davila'}),
]
CONCESSOES = [
    # chave, conta externa, workspace (do cliente da conta), papel na validacao
    ('f6av-c1', 'f6av-cliente', 'exp-astral', 'revogar'),
    ('f6av-c2', 'f6av-cliente', 'exp-bmp', 'controle'),
]
ESPERADO = {'usuarios': 4, 'vinculos_org': 3, 'vinculos_cliente': 1, 'papeis_ws': 3, 'concessoes_ativas': 2}


def caminhos(segredos):
    base = os.path.join(segredos, 'frontend', 'f6a')
    return base, os.path.join(base, 'semeadura.pendente.json'), os.path.join(base, 'semeadura.json')


def base_da_f15(segredos):
    """Plano final da F1.5 (so leitura). None se ausente."""
    return sf.le(os.path.join(segredos, 'frontend', 'semeadura.json'))


def catalogo_plano(base, rnd=None):
    """Plano SEM senhas e SEM PHC. Referencias (org A, cliente, workspaces, responsavel) vem do plano da F1.5."""
    rnd = rnd or random.Random(secrets.randbits(64))
    u = lambda: str(uuid.UUID(int=rnd.getrandbits(128), version=4))
    org_a = next(o for o in base['orgs'] if o['chave'] == 'a')
    resp = next(c for c in base['contas'] if c['org'] == 'a' and c.get('primeiro_admin'))
    ws = {w['chave']: w for w in base['workspaces']}
    cli = {c['chave']: c for c in base['clientes']}
    refs = {
        'execucao_base': base.get('execucao'), 'org_a': org_a['id'], 'responsavel_membership': resp['membership_id'],
        'responsavel_chave': resp['chave'],
        'clientes': {'davila': cli['davila']['id']},
        'workspaces': {k: ws[k]['id'] for k in ('sandbox', 'exp-astral', 'exp-bmp')},
    }
    for k in ('exp-astral', 'exp-bmp'):
        if ws[k]['cliente'] != 'davila' or ws[k]['org'] != 'a':
            raise SystemExit(f'base da F1.5 inesperada: workspace {k} nao e do cliente davila na organizacao A')
    contas = [{'chave': k, 'org': 'a', 'tipo': t, 'local': k, 'email': f'{k}@{DOMINIO}', 'nome': n, 'uso': USO, 'mfa': True,
               'papeis': p, 'user_id': u(), 'membership_id': u()} for k, t, n, p in CONTAS]
    concessoes = [{'chave': k, 'conta': c, 'workspace': w, 'papel': p, 'grant_id': u()} for k, c, w, p in CONCESSOES]
    return {'versao': 1, 'refs': refs, 'contas': contas, 'concessoes': concessoes, 'esperado': ESPERADO}


def mapa_publico(plano):
    """O que pode ir para a evidencia: ids, chaves, e-mails sinteticos e nomes. Nunca senha nem PHC."""
    return {
        'execucao': plano.get('execucao'), 'refs': plano['refs'],
        'contas': [{k: c[k] for k in ('chave', 'org', 'tipo', 'email', 'nome', 'uso', 'papeis', 'user_id', 'membership_id')}
                   for c in plano['contas']],
        'concessoes': plano['concessoes'], 'esperado': plano['esperado'],
    }


def arq_mapa(evid):
    return os.path.join(evid, 'semeadura_f6a_mapa.json')


def mapa_confere(evid, plano):
    return sf.le(arq_mapa(evid)) == json.loads(json.dumps(mapa_publico(plano), ensure_ascii=False))


def grava_mapa(evid, plano):
    sf.grava_atomico(arq_mapa(evid), mapa_publico(plano))


def identidade(plano):
    return (plano.get('execucao'), tuple(c['user_id'] for c in plano['contas']))


def promove(pend, fin, evid, plano):
    atual = sf.le(pend)
    if atual is None or identidade(atual) != identidade(plano):
        raise sf.PlanoTrocado(f'plano pendente no disco ({atual.get("execucao") if atual else "ausente"}) '
                              f'diferente do aplicado ({plano.get("execucao")})')
    grava_mapa(evid, plano)
    sf._renomeia(pend, fin)


def contas_f6av_no_banco(bash, psql):
    rc, out, _ = sf.roda(bash, psql, f"SELECT 'F6AV=' || count(*) FROM users WHERE email LIKE '{PREFIXO}%@{DOMINIO}';\n", 120)
    linha = [x for x in out.splitlines() if x.startswith('F6AV=')]
    return int(linha[0][5:]) if rc == 0 and len(linha) == 1 else None


def conta_do_plano_no_banco(bash, psql, user_id):
    rc, out, _ = sf.roda(bash, psql, f"SELECT 'CONTA=' || count(*) FROM users WHERE user_id = '{user_id}';\n", 120)
    linha = [x for x in out.splitlines() if x.startswith('CONTA=')]
    if rc != 0 or len(linha) != 1:
        return None
    return linha[0] == 'CONTA=1'


def estado(seg, bash, psql, evid=None):
    est, plano = _estado(seg, bash, psql)
    if est == 'CONCLUIDA' and evid is not None and not mapa_confere(evid, plano):
        return 'CONCLUIDA_SEM_MAPA', plano
    return est, plano


def _estado(seg, bash, psql):
    _, pend, fin = caminhos(seg)
    p, f = sf.le(pend), sf.le(fin)
    if p is None and f is None:
        n = contas_f6av_no_banco(bash, psql)
        if n is None:
            return 'SEM_BANCO', None
        return ('AUSENTE' if n == 0 else 'INCONSISTENTE'), None
    if p is not None and f is not None:
        return 'INCONSISTENTE', None
    plano = p or f
    no_banco = conta_do_plano_no_banco(bash, psql, plano['contas'][0]['user_id'])
    if no_banco is None:
        return 'SEM_BANCO', plano
    if p is not None:
        return ('A_RECONCILIAR' if no_banco else 'PENDENTE'), plano
    return ('CONCLUIDA' if no_banco else 'INCONSISTENTE'), plano


def script_sql(plano, sql_arquivo):
    so_banco = dict(plano)
    so_banco['contas'] = [{k: v for k, v in c.items() if k != 'senha'} for c in plano['contas']]
    b64 = sf.base64.b64encode(json.dumps(so_banco, ensure_ascii=False).encode('utf-8')).decode('ascii')
    return f"\\set plano_b64 '{b64}'\n" + open(sql_arquivo, encoding='utf-8').read()


def log(evid, msg):
    linha = f'{sf.datetime.datetime.now(sf.datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")} {msg}'
    print(linha, flush=True)
    if evid:
        with open(os.path.join(evid, 'semeadura_f6a.txt'), 'a', encoding='utf-8') as h:
            h.write(linha + '\n')


def semear(a):
    bash = sf.bash_exe(a.bash)
    os.makedirs(a.evid, exist_ok=True)
    trava = sf.Trava(a.segredos)
    if not trava.adquire():
        d = trava.dono()
        log(a.evid, f'RECUSADA: outra semeadura em andamento neste ambiente (trava {trava.dir}; dono pid={d.get("pid")} '
                    f'host={d.get("host")} desde {d.get("inicio")}); nada foi gravado nem consultado no banco')
        return 11
    try:
        return _semear(a, bash)
    finally:
        trava.libera()


def _semear(a, bash):
    est_base, plano_base = sf.estado(a.segredos, bash, a.psql)
    if est_base != 'CONCLUIDA':
        log(a.evid, f'RECUSADA: a semeadura da F1.5 nao esta CONCLUIDA (estado {est_base}); nada foi feito')
        return 14
    base, pend, fin = caminhos(a.segredos)
    est, plano = estado(a.segredos, bash, a.psql, a.evid)
    log(a.evid, f'estado inicial: {est}')
    if est == 'SEM_BANCO':
        log(a.evid, 'STOP: o banco nao respondeu a consulta de estado; nada foi feito')
        return 4
    if est == 'CONCLUIDA_SEM_MAPA':
        try:
            grava_mapa(a.evid, plano)
        except OSError as e:
            log(a.evid, f'CONCLUIDA_SEM_MAPA: o mapa publico nao foi gravado de novo ({e.__class__.__name__}); repita')
            return 13
        log(a.evid, 'MAPA PUBLICO RECUPERADO a partir do plano final: sem SQL e sem credenciais novas')
        return 0
    if est in ('CONCLUIDA', 'INCONSISTENTE'):
        log(a.evid, 'RECUSADA: ' + ('a semeadura complementar ja esta concluida; nao se repete' if est == 'CONCLUIDA' else
                                     'estado inconsistente entre o disco e o banco; STOP, nada foi feito'))
        return 3 if est == 'CONCLUIDA' else 5
    if est == 'A_RECONCILIAR':
        try:
            promove(pend, fin, a.evid, plano)
        except sf.PlanoTrocado as e:
            log(a.evid, f'STOP: {e}; nada promovido')
            return 12
        except OSError as e:
            log(a.evid, f'A_RECONCILIAR: o banco tem a semeadura e a promocao do plano falhou de novo ({e.__class__.__name__}); credenciais continuam no plano pendente')
            return 6
        log(a.evid, 'RECONCILIADA: a transacao ja estava efetivada; plano promovido sem semear de novo')
        return 0
    if est == 'AUSENTE':
        plano = catalogo_plano(plano_base)
        plano['execucao'] = str(uuid.uuid4())
        for c in plano['contas']:
            c['senha'] = 'demo-' + secrets.token_urlsafe(18)
            rc, out, err = sf.roda(bash, a.phc, c['senha'] + '\n', 300)
            phc = out.strip().splitlines()[-1] if out.strip() else ''
            if rc != 0 or not phc.startswith('$argon2id$'):
                log(a.evid, f'STOP: PHC nao gerado para {c["chave"]} (codigo {rc}); nada foi gravado')
                return 7
            c['phc'] = phc
        try:
            sf.grava_atomico(pend, plano, exclusivo=True)
        except OSError as e:
            log(a.evid, f'STOP: plano com as credenciais NAO gravado ({e.__class__.__name__}: {e}); nada foi enviado ao banco')
            return 8
        log(a.evid, f'plano pendente gravado (execucao {plano["execucao"]}, {len(plano["contas"])} contas, {len(plano["concessoes"])} concessoes)')
    else:
        log(a.evid, f'PENDENTE: reaproveitando o MESMO plano (execucao {plano["execucao"]}); nenhum id ou senha novo')
    rc, out, err = sf.roda(bash, a.psql, script_sql(plano, a.sql), 600)
    for x in [x for x in out.splitlines() if x.startswith(('SEMEADURA_', 'CONFERE '))]:
        log(a.evid, 'psql: ' + x)
    if rc != 0:
        erro = [x for x in err.splitlines() if 'ERROR' in x or 'ERRO' in x][:3]
        log(a.evid, f'psql codigo {rc}: ' + ' | '.join(erro))
    no_banco = conta_do_plano_no_banco(bash, a.psql, plano['contas'][0]['user_id'])
    if no_banco is None:
        log(a.evid, 'STOP: resultado incerto e o banco nao respondeu a conferencia; estado continua PENDENTE ou A_RECONCILIAR (rode de novo)')
        return 9
    if not no_banco:
        log(a.evid, 'NAO APLICADA: a transacao nao foi efetivada (rollback); o plano pendente fica para repetir com os mesmos ids')
        return 10
    if rc != 0 or 'SEMEADURA_F6A_APLICADA' not in out:
        log(a.evid, 'resultado do psql perdido ou ambiguo, mas a conta do plano ESTA no banco: transacao efetivada')
    try:
        promove(pend, fin, a.evid, plano)
    except sf.PlanoTrocado as e:
        log(a.evid, f'STOP: {e}; nada promovido')
        return 12
    except OSError as e:
        log(a.evid, f'A_RECONCILIAR: semeadura efetivada e promocao do plano falhou ({e.__class__.__name__}); credenciais no plano pendente; rode de novo')
        return 6
    log(a.evid, 'CONCLUIDA: semeadura complementar efetivada e plano final gravado')
    return 0


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='acao', required=True)
    s = sub.add_parser('semear')
    for x in ('--segredos', '--evid', '--sql', '--psql', '--phc'):
        s.add_argument(x, required=True)
    s.add_argument('--bash')
    e = sub.add_parser('estado')
    e.add_argument('--segredos', required=True)
    e.add_argument('--psql', required=True)
    e.add_argument('--evid')
    e.add_argument('--bash')
    sub.add_parser('catalogo')
    a = ap.parse_args()
    if a.acao == 'semear':
        sys.exit(semear(a))
    if a.acao == 'estado':
        if os.path.isdir(sf.Trava(a.segredos).dir):
            print('EM_ANDAMENTO')
            sys.exit(1)
        est, _ = estado(a.segredos, sf.bash_exe(a.bash), a.psql, a.evid)
        print(est)
        sys.exit(0 if est in ('CONCLUIDA', 'AUSENTE') else 1)
    print(json.dumps({'contas': [(k, t, USO) for k, t, _, _ in CONTAS], 'concessoes': [(k, w, p) for k, _, w, p in CONCESSOES],
                      'esperado': ESPERADO}, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
