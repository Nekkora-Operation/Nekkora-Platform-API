"""Semeadura do ensaio-frontend (F1.5, P-A): massa SINTETICA pequena para a aplicacao web e as jornadas.

Catalogo (este arquivo) -> plano JSON (ids, contas com PHC, estrutura do produto, execucoes) -> sql/semear_frontend.sql
(uma transacao, como admin_cluster). Nenhum dado real: e-mails @demo.nekkora.test, nomes e segredos gerados.

Subcomandos:
  semear         --segredos DIR --evid DIR --sql ARQ --psql CMD --phc CMD [--bash CAMINHO]
  estado         --segredos DIR --psql CMD [--evid DIR] [--bash CAMINHO]
  liberar-trava  --segredos DIR --confirmo-inativo   (trava orfa de uma execucao interrompida; recusa dono vivo)
  catalogo                                   (resumo do catalogo, sem segredos; usado pelos testes)

CMD sao comandos de shell (executados com o Git Bash): --psql recebe o SQL pela entrada padrao e imprime o resultado
(no runner: admin_sql nekkora); --phc recebe UMA senha pela entrada padrao e imprime o PHC Argon2id (PhcTool real).

ESTADOS (sempre derivados do disco E do banco; nunca de um arquivo so):
  AUSENTE        sem plano pendente, sem plano final e sem organizacao da semeadura no banco
  PENDENTE       plano pendente gravado (credenciais ja recuperaveis), organizacao AUSENTE no banco: a transacao nao
                 foi efetivada; repetir reaproveita o MESMO plano (mesmos ids e senhas: sem duplicacao)
  A_RECONCILIAR  plano pendente gravado e organizacao PRESENTE no banco: a transacao foi efetivada e a promocao do plano
                 nao terminou (queda depois do COMMIT ou falha ao renomear); repetir so promove, nunca semeia de novo
  CONCLUIDA      plano final gravado e organizacao PRESENTE no banco
  INCONSISTENTE  qualquer outra combinacao (ex.: organizacao da semeadura no banco sem plano no disco, ou plano final
                 sem a organizacao): STOP, nada e feito

EXCLUSAO MUTUA (P-A-R1): <segredos>/frontend.trava (mkdir atomico, dono = pid + host + inicio) e obtida ANTES de
consultar o estado e mantida ate a promocao terminar. Outra chamada no mesmo ambiente e RECUSADA (codigo 11) sem gravar
plano, sem consultar nem alterar o banco. Trava orfa (execucao morta): liberar-trava com --confirmo-inativo, recusado se
o dono ainda estiver vivo neste host.

Ordem que garante "credenciais recuperaveis" e "sem duplicacao":
  1. o plano (com as senhas) e gravado ATOMICAMENTE (temporario + fsync + rename) ANTES da transacao; se a gravacao
     falhar, nada vai para o banco;
  2. a transacao confere, dentro dela, que nenhuma organizacao da semeadura existe (recusa), grava tudo e confere as
     contagens esperadas antes do COMMIT (falha no meio = ROLLBACK, banco sem semeadura parcial);
  3. resultado do psql perdido ou ambiguo: consulta a organizacao pelo id do plano ANTES de qualquer repeticao;
  4. promocao (P-A-R1): confere a IDENTIDADE do plano pendente no disco (execucao e ids das organizacoes) contra o plano
     aplicado (diferente = STOP, codigo 12, nada promovido); grava o mapa publico na evidencia; SO DEPOIS renomeia o
     pendente para o final. Falha no mapa ou no rename deixa A_RECONCILIAR (o pendente continua, a repeticao so promove);
     interrupcao logo depois do rename deixa CONCLUIDA com o mapa ja gravado;
  5. semeadura CONCLUIDA sem o mapa publico (ou com mapa divergente): a repeticao so regrava o mapa a partir do plano
     final, sem SQL e sem credenciais novas (estado CONCLUIDA_SEM_MAPA quando consultado com --evid).
  6. o plano pendente e criado de forma EXCLUSIVA (nunca substitui um existente).
"""
import argparse
import base64
import datetime
import json
import os
import random
import secrets
import socket
import subprocess
import sys
import uuid

DOMINIO = 'demo.nekkora.test'
NOME_ORG = {'a': 'Nekkora Demo', 'b': 'Nekkora Demo B'}
PHC_PARAMS = (65536, 3)   # os da configuracao da Platform (mesmos da fixture da 016)
REF = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)   # so para o gerador deterministico de tempos

# ---------------------------------------------------------------------------------------------------- catalogo
CLIENTES = [
    # chave, org, nome
    ('acme', 'a', 'ACME Logística'),
    ('banco', 'a', 'Banco Exemplo S.A.'),
    ('davila', 'a', "D'Ávila & Filhos Ltda."),
    ('vizinho', 'b', 'Cliente Vizinho B'),
]

WORKSPACES = [
    # chave, org, cliente, nome, estado final, descricao
    ('fin', 'a', 'banco', 'Financeiro — Conciliação bancária', 'ativo', 'Conciliação diária e fechamento do mês.'),
    ('reg-sul', 'a', 'banco', 'Relatórios regulatórios consolidados do semestre — Sul', 'ativo', None),
    ('reg-norte', 'a', 'banco', 'Relatórios regulatórios consolidados do semestre — Norte', 'ativo', None),
    ('log', 'a', 'acme', 'ACME — Logística e rastreio', 'ativo', 'Integração com transportadoras.'),
    ('notas', 'a', 'acme', 'ACME — Notas fiscais', 'suspenso', 'Suspenso até a revisão do contrato.'),
    ('exp-astral', 'a', 'davila', "D'Ávila — Exportação 𝔸", 'ativo', None),
    ('exp-bmp', 'a', 'davila', "D'Ávila — Exportação ｚ", 'ativo', None),
    ('ops', 'a', None, 'Operações (volume)', 'ativo', 'Cerca de 300 execuções, para a paginação real.'),
    ('sandbox', 'a', None, 'Sandbox da equipe', 'ativo', 'Ainda sem automações.'),
    ('legado', 'a', None, 'Integrações legadas', 'arquivado', 'Mantido só para consulta do histórico.'),
    ('viz', 'b', 'vizinho', 'Workspace da organização B', 'ativo', None),
]

AUTOMACOES = [
    # chave, workspace, nome, estado final, versoes (True = publicada, na ordem do numero), descricao
    ('conciliacao', 'fin', 'Conciliação bancária diária', 'ativa', [True, True, True, False], 'Rotina sintética de conciliação.'),
    ('fechamento', 'fin', 'Fechamento mensal', 'ativa', [True, True], None),
    ('auditoria', 'fin', 'Exportação para auditoria (rascunho)', 'ativa', [False], 'Nunca publicada.'),
    ('reg-sul', 'reg-sul', 'Geração do relatório regulatório', 'ativa', [True, True], None),
    ('reg-norte', 'reg-norte', 'Geração do relatório regulatório', 'ativa', [True, False], None),
    ('rastreio', 'log', 'Atualização de rastreio de entregas', 'ativa', [True, True, True, True, True], None),
    ('importacao', 'log', 'Importação de notas fiscais', 'ativa', [True, True], None),
    ('coleta', 'notas', 'Coleta de notas', 'ativa', [True], None),
    ('exp-astral', 'exp-astral', 'Exportação 𝔸', 'ativa', [True], None),
    ('exp-bmp', 'exp-bmp', 'Exportação ｚ', 'ativa', [True], None),
    ('backup', 'ops', 'Backup dos arquivos de operação', 'ativa', [True, True, True], 'Volume para paginação.'),
    ('limpeza', 'ops', 'Limpeza de temporários', 'ativa', [True], None),
    ('capacidade', 'ops', 'Relatório de capacidade', 'suspensa', [True, False], 'Suspensa depois das execuções.'),
    ('cobranca-a', 'ops', 'Ação de cobrança automática para clientes em atraso — carteira A', 'ativa', [True], None),
    ('cobranca-b', 'ops', 'Ação de cobrança automática para clientes em atraso — carteira B', 'ativa', [True], None),
    ('ftp', 'legado', 'Transferência FTP (descontinuada)', 'arquivada', [True, True, False], None),
    ('viz', 'viz', 'Rotina da organização B', 'ativa', [True], None),
]

# Contas. uso: navegacao (pessoas; nunca usadas por testes automaticos), jornada (jornadas HTTP e suite do navegador,
# so leitura depois do login), e as EXCLUSIVAS de operacoes que mudam estado: primeiro-acesso (cadastro de MFA no
# primeiro login; uma por execucao), recuperacao (consumo de codigo de recuperacao), sessoes (logout e revoke-all) e
# expira (sessao envelhecida pela operacao declarada "frontend-sessao"). mfa: True = cadastrado antes, pela API
# (jornadas_frontend.py preparar-contas); False = a propria conta cadastra no primeiro acesso.
_PAPEIS = {
    'admin': {'org': ['ORG_ADMIN']},
    'wsadmin': {'ws': [('fin', 'WORKSPACE_ADMIN')]},
    'operador': {'ws': [('fin', 'OPERATOR'), ('ops', 'OPERATOR')]},
    'leitor': {'ws': [('log', 'VIEWER'), ('legado', 'VIEWER')]},
    'semacesso': {},
    'cliente': {'cliente': 'acme', 'concessoes': [('log', 'ativa'), ('notas', 'pendente'), ('log', 'revogada')]},
    'admin-b': {'org': ['ORG_ADMIN']},
}
_NOMES = {
    'admin': 'Ana Administradora', 'wsadmin': 'Bruno Gestor', 'operador': 'Carla Operadora', 'leitor': 'Davi Leitor',
    'semacesso': 'Eva Sem Atribuições', 'cliente': "Fábio D'Ávila (cliente)", 'admin-b': 'Gina Administradora B',
}


def _contas():
    contas = []
    for uso, prefixo, sufixo in (('navegacao', '', ''), ('jornada', 'j-', ' · jornada')):
        for k in ('admin', 'wsadmin', 'operador', 'leitor', 'semacesso', 'cliente', 'admin-b'):
            contas.append({
                'chave': f'{prefixo}{k}', 'org': 'b' if k == 'admin-b' else 'a',
                'tipo': 'externa' if k == 'cliente' else 'interna', 'local': f'{prefixo}{k}',
                'nome': _NOMES[k] + sufixo, 'uso': uso, 'mfa': True, 'papeis': _PAPEIS[k],
                # o primeiro ORG_ADMIN de cada organizacao: so pelo caminho autorizado da 012
                'primeiro_admin': uso == 'navegacao' and k in ('admin', 'admin-b'),
            })
    leitura = {'ws': [('log', 'VIEWER')]}
    for i in range(1, 6):
        contas.append({'chave': f'primeiro-acesso-{i:02d}', 'org': 'a', 'tipo': 'interna', 'local': f'primeiro-acesso-{i:02d}',
                       'nome': f'Primeiro Acesso {i:02d}', 'uso': 'primeiro-acesso', 'mfa': False, 'papeis': leitura,
                       'primeiro_admin': False})
    for chave, nome in (('recuperacao-01', 'Recuperação 01'), ('recuperacao-02', 'Recuperação 02'),
                        ('sessoes', 'Sessões (logout e revoke-all)'), ('expira', 'Expiração de sessão')):
        uso = 'recuperacao' if chave.startswith('recuperacao') else chave
        contas.append({'chave': chave, 'org': 'a', 'tipo': 'interna', 'local': chave, 'nome': nome, 'uso': uso,
                       'mfa': True, 'papeis': leitura, 'primeiro_admin': False})
    return contas


CONTAS = _contas()

ESTADOS = ['pendente', 'iniciando', 'executando', 'concluida', 'falha_inicio', 'falha_processo', 'cancelada_antes',
           'cancelada_confirmada', 'prazo_fila']


def _execucoes(rnd, u):
    """Especificacao das execucoes (sem a agendada, criada pelo fluxo da 009 dentro do SQL). u() gera os ids."""
    ex = []
    solicitantes = {'a': ['admin', 'operador', 'wsadmin'], 'b': ['admin-b']}

    def nova(auto, criada_s, estado, solicitante=None, retry_de=None, duracao_s=None):
        a = next(x for x in AUTOMACOES if x[0] == auto)
        org = next(w for w in WORKSPACES if w[0] == a[1])[1]
        versoes = a[4]
        ultima_pub = max(i + 1 for i, p in enumerate(versoes) if p)
        atraso = rnd.randint(1, 20)
        dur = duracao_s if duracao_s is not None else rnd.randint(4, 900)
        # nada termina no futuro: reserva + 1 s de inicio + duracao < tempo desde a criacao
        dur = max(1, min(dur, int(criada_s) - atraso - 5))
        e = {'id': u(), 'automacao': auto, 'versao': ultima_pub, 'criada_s': int(criada_s), 'estado': estado,
             'solicitante': solicitante or rnd.choice(solicitantes[org]), 'retry_de': retry_de,
             'atraso_s': atraso, 'duracao_s': dur}
        ex.append(e)
        return e

    dia = 86400
    # todos os estados, recentes (dentro dos 30 dias padrao), na conciliacao
    for i, est in enumerate(ESTADOS):
        nova('conciliacao', 3600 * (2 + i * 5), est)
    # reexecucao: falha de processo seguida de reexecucao manual concluida
    f = nova('conciliacao', 2 * dia, 'falha_processo', solicitante='operador')
    nova('conciliacao', 2 * dia - 1800, 'concluida', solicitante='operador', retry_de=f['id'])
    # empate exato de data (o desempate e pelo id, decrescente)
    for _ in range(4):
        nova('fechamento', 3 * dia, 'concluida', solicitante='admin')
    # volume: ~300 no workspace de operacoes, espalhadas por 120 dias
    tempos = sorted((rnd.randint(600, 120 * dia) for _ in range(300)), reverse=True)
    for t in tempos:
        r = rnd.random()
        est = 'concluida' if r < 0.78 else 'falha_processo' if r < 0.88 else 'falha_inicio' if r < 0.92 else \
            'cancelada_confirmada' if r < 0.96 else 'cancelada_antes' if r < 0.98 else 'prazo_fila'
        nova('backup', t, est)
    for auto, n, ate in (('rastreio', 20, 40), ('importacao', 6, 20), ('limpeza', 8, 25), ('capacidade', 3, 50),
                         ('reg-sul', 4, 90), ('reg-norte', 2, 60), ('exp-astral', 1, 10), ('exp-bmp', 1, 12),
                         ('cobranca-a', 1, 5), ('cobranca-b', 1, 6), ('coleta', 2, 15)):
        for _ in range(n):
            nova(auto, rnd.randint(3600, ate * dia), rnd.choice(['concluida', 'concluida', 'concluida', 'falha_processo']))
    # legado: so historico antigo (fora dos 30 dias padrao)
    for _ in range(5):
        nova('ftp', rnd.randint(40 * dia, 110 * dia), 'concluida')
    # organizacao B
    nova('viz', 5 * dia, 'concluida', solicitante='admin-b')
    nova('viz', 1 * dia, 'falha_processo', solicitante='admin-b')
    return ex


def catalogo_plano(rnd=None):
    """Plano SEM senhas e SEM PHC (estrutura + ids). Deterministico com rnd semeado (testes)."""
    rnd = rnd or random.Random(secrets.randbits(64))
    u = lambda: str(uuid.UUID(int=rnd.getrandbits(128), version=4))
    ids = {'org': {k: u() for k in ('a', 'b')}}
    plano = {
        'versao': 1,
        'orgs': [{'chave': k, 'id': ids['org'][k], 'nome': NOME_ORG[k]} for k in ('a', 'b')],
        'clientes': [{'chave': c, 'org': o, 'id': u(), 'nome': n} for c, o, n in CLIENTES],
        'workspaces': [{'chave': w, 'org': o, 'cliente': c, 'id': u(), 'nome': n, 'estado': e, 'descricao': d}
                       for w, o, c, n, e, d in WORKSPACES],
        'automacoes': [{'chave': a, 'workspace': w, 'id': u(), 'nome': n, 'estado': e, 'versoes': v, 'descricao': d}
                       for a, w, n, e, v, d in AUTOMACOES],
        'contas': [],
    }
    for c in CONTAS:
        plano['contas'].append({**c, 'email': f'{c["local"]}@{DOMINIO}', 'user_id': u(), 'membership_id': u()})
    # solicitante: chave curta da conta de NAVEGACAO (nome legivel na tela)
    plano['execucoes'] = _execucoes(rnd, u)
    return plano


def esperado(plano):
    """Contagens que a propria transacao confere antes do COMMIT."""
    por_org = {o['chave']: {'usuarios': 0, 'execucoes': 0} for o in plano['orgs']}
    for c in plano['contas']:
        por_org[c['org']]['usuarios'] += 1
    ws_org = {w['chave']: w['org'] for w in plano['workspaces']}
    auto_ws = {a['chave']: a['workspace'] for a in plano['automacoes']}
    estados = {}
    for e in plano['execucoes']:
        por_org[ws_org[auto_ws[e['automacao']]]]['execucoes'] += 1
        estados[e['estado']] = estados.get(e['estado'], 0) + 1
    por_org['a']['execucoes'] += 1   # a agendada (fluxo real da 009), criada dentro do SQL
    estados['concluida'] = estados.get('concluida', 0) + 1
    return {'por_org': por_org, 'estados': estados,
            'workspaces': len(plano['workspaces']), 'automacoes': len(plano['automacoes']),
            'versoes': sum(len(a['versoes']) for a in plano['automacoes'])}


# ------------------------------------------------------------------------------------------------ orquestracao
def bash_exe(arg):
    if arg:
        return arg
    for c in (os.environ.get('HOMOLOG_BASH'), r'C:\Program Files\Git\bin\bash.exe', '/usr/bin/bash', '/bin/bash'):
        if c and os.path.exists(c):
            return c
    raise SystemExit('bash nao encontrado (defina HOMOLOG_BASH com o caminho do Git Bash)')


def roda(bash, cmd, entrada, timeout=900):
    r = subprocess.run([bash, '-c', cmd], input=entrada.encode('utf-8'), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       timeout=timeout)
    return r.returncode, r.stdout.decode('utf-8', 'replace').replace('\r', ''), r.stderr.decode('utf-8', 'replace').replace('\r', '')


def grava_atomico(caminho, dados, exclusivo=False):
    """Temporario no MESMO diretorio + fsync + rename. Falha = excecao (o chamador decide; nada e meio gravado).
    exclusivo=True: o destino NAO pode existir (os.link falha com FileExistsError; o existente fica intacto)."""
    d = os.path.dirname(caminho)
    os.makedirs(d, mode=0o700, exist_ok=True)
    tmp = f'{caminho}.tmp-{os.getpid()}-{secrets.token_hex(4)}'
    with open(tmp, 'x', encoding='utf-8') as h:
        h.write(json.dumps(dados, ensure_ascii=False, indent=1))
        h.flush()
        os.fsync(h.fileno())
    try:
        os.chmod(tmp, 0o600)
    except OSError:
        pass
    if exclusivo:
        try:
            os.link(tmp, caminho)
        finally:
            os.remove(tmp)
        return
    os.replace(tmp, caminho)


class Trava:
    """Exclusao mutua por ambiente: diretorio <segredos>/frontend.trava criado com mkdir (atomico)."""

    def __init__(self, segredos):
        self.dir = os.path.join(segredos, 'frontend.trava')

    def adquire(self):
        os.makedirs(os.path.dirname(self.dir), exist_ok=True)
        try:
            os.mkdir(self.dir)
        except FileExistsError:
            return False
        with open(os.path.join(self.dir, 'dono.json'), 'w', encoding='utf-8') as h:
            json.dump({'pid': os.getpid(), 'host': socket.gethostname(),
                       'inicio': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}, h)
        return True

    def dono(self):
        try:
            with open(os.path.join(self.dir, 'dono.json'), encoding='utf-8') as h:
                return json.load(h)
        except (OSError, ValueError):
            return {}

    def libera(self):
        try:
            os.remove(os.path.join(self.dir, 'dono.json'))
        except OSError:
            pass
        os.rmdir(self.dir)


def pid_vivo(pid):
    if not pid:
        return False
    if os.name == 'nt':
        r = subprocess.run(['tasklist', '/FI', f'PID eq {int(pid)}', '/NH'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        return str(int(pid)) in r.stdout.decode('utf-8', 'replace')
    try:
        os.kill(int(pid), 0)
        return True
    except OSError:
        return False


def caminhos(segredos):
    base = os.path.join(segredos, 'frontend')
    return base, os.path.join(base, 'semeadura.pendente.json'), os.path.join(base, 'semeadura.json')


def le(p):
    if not os.path.isfile(p):
        return None
    with open(p, encoding='utf-8') as h:
        return json.load(h)


def org_no_banco(bash, psql, org_id):
    """True/False pela organizacao A do plano; None se o banco nao respondeu (nada se decide)."""
    rc, out, err = roda(bash, psql, f"SELECT 'ORG=' || count(*) FROM organizations WHERE organization_id = '{org_id}';\n", 120)
    linha = [x for x in out.splitlines() if x.startswith('ORG=')]
    if rc != 0 or len(linha) != 1:
        return None
    return linha[0] == 'ORG=1'


def orgs_da_semeadura(bash, psql):
    rc, out, _ = roda(bash, psql, "SELECT 'NOMES=' || count(*) FROM organizations WHERE name IN ('Nekkora Demo', 'Nekkora Demo B');\n", 120)
    linha = [x for x in out.splitlines() if x.startswith('NOMES=')]
    return int(linha[0][6:]) if rc == 0 and len(linha) == 1 else None


def estado(seg, bash, psql, evid=None):
    """Estado do disco + banco. Com evid: CONCLUIDA sem o mapa publico correto vira CONCLUIDA_SEM_MAPA."""
    est, plano = _estado(seg, bash, psql)
    if est == 'CONCLUIDA' and evid is not None and not mapa_confere(evid, plano):
        return 'CONCLUIDA_SEM_MAPA', plano
    return est, plano


def _estado(seg, bash, psql):
    _, pend, fin = caminhos(seg)
    p, f = le(pend), le(fin)
    if p is None and f is None:
        n = orgs_da_semeadura(bash, psql)
        if n is None:
            return 'SEM_BANCO', None
        return ('AUSENTE' if n == 0 else 'INCONSISTENTE'), None
    if p is not None and f is not None:
        return 'INCONSISTENTE', None
    plano = p or f
    no_banco = org_no_banco(bash, psql, plano['orgs'][0]['id'])
    if no_banco is None:
        return 'SEM_BANCO', plano
    if p is not None:
        return ('A_RECONCILIAR' if no_banco else 'PENDENTE'), plano
    return ('CONCLUIDA' if no_banco else 'INCONSISTENTE'), plano


def mapa_publico(plano):
    """O que pode ir para a evidencia: ids, chaves, e-mails sinteticos e nomes. Nunca senha, PHC ou TOTP."""
    return {
        'orgs': plano['orgs'], 'clientes': plano['clientes'],
        'workspaces': [{k: w[k] for k in ('chave', 'org', 'cliente', 'id', 'nome', 'estado')} for w in plano['workspaces']],
        'automacoes': [{k: a[k] for k in ('chave', 'workspace', 'id', 'nome', 'estado', 'versoes')} for a in plano['automacoes']],
        'contas': [{k: c[k] for k in ('chave', 'org', 'tipo', 'email', 'nome', 'uso', 'mfa', 'user_id', 'membership_id')} for c in plano['contas']],
        'execucoes_por_estado': esperado(plano)['estados'],
        'execucao_reexecutada': next(e['retry_de'] for e in plano['execucoes'] if e['retry_de']),
    }


def script_sql(plano, sql_arquivo):
    so_banco = dict(plano)
    so_banco['contas'] = [{k: v for k, v in c.items() if k != 'senha'} for c in plano['contas']]
    so_banco['esperado'] = esperado(plano)
    b64 = base64.b64encode(json.dumps(so_banco, ensure_ascii=False).encode('utf-8')).decode('ascii')
    # psql: variavel com o plano em base64 (alfabeto seguro); o arquivo SQL le com convert_from(decode(...))
    return f"\\set plano_b64 '{b64}'\n" + open(sql_arquivo, encoding='utf-8').read()


def log(evid, msg):
    linha = f'{datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")} {msg}'
    print(linha, flush=True)
    if evid:
        with open(os.path.join(evid, 'semeadura_frontend.txt'), 'a', encoding='utf-8') as h:
            h.write(linha + '\n')


class PlanoTrocado(Exception):
    pass


def arq_mapa(evid):
    return os.path.join(evid, 'semeadura_frontend_mapa.json')


def mapa_confere(evid, plano):
    return le(arq_mapa(evid)) == json.loads(json.dumps(mapa_publico(plano), ensure_ascii=False))


def grava_mapa(evid, plano):
    """Mapa publico (sem segredos), gravado atomicamente; idempotente (mesmo plano = mesmo conteudo)."""
    grava_atomico(arq_mapa(evid), mapa_publico(plano))


def identidade(plano):
    return (plano.get('execucao'), tuple(o['id'] for o in plano['orgs']))


def _renomeia(pend, fin):
    if os.path.exists(fin):
        raise FileExistsError(fin)
    os.replace(pend, fin)


def promove(pend, fin, evid, plano):
    """1. o pendente no disco tem de ser O MESMO plano aplicado; 2. mapa publico; 3. so entao o rename."""
    atual = le(pend)
    if atual is None or identidade(atual) != identidade(plano):
        raise PlanoTrocado(f'plano pendente no disco ({identidade(atual)[0] if atual else "ausente"}) '
                           f'diferente do aplicado ({plano.get("execucao")})')
    grava_mapa(evid, plano)
    _renomeia(pend, fin)


def semear(a):
    bash = bash_exe(a.bash)
    os.makedirs(a.evid, exist_ok=True)
    trava = Trava(a.segredos)
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
        log(a.evid, 'RECUSADA: ' + ('a semeadura ja esta concluida; nao se repete' if est == 'CONCLUIDA' else
                                     'estado inconsistente entre o disco e o banco; STOP, nada foi feito'))
        return 3 if est == 'CONCLUIDA' else 5
    if est == 'A_RECONCILIAR':
        try:
            promove(pend, fin, a.evid, plano)
        except PlanoTrocado as e:
            log(a.evid, f'STOP: {e}; nada promovido')
            return 12
        except OSError as e:
            log(a.evid, f'A_RECONCILIAR: o banco tem a semeadura e a promocao do plano falhou de novo ({e.__class__.__name__}); credenciais continuam no plano pendente')
            return 6
        log(a.evid, 'RECONCILIADA: a transacao ja estava efetivada; plano promovido sem semear de novo')
        return 0
    if est == 'AUSENTE':
        plano = catalogo_plano()
        plano['execucao'] = str(uuid.uuid4())
        for c in plano['contas']:
            c['senha'] = 'demo-' + secrets.token_urlsafe(18)
            rc, out, err = roda(bash, a.phc, c['senha'] + '\n', 300)
            phc = out.strip().splitlines()[-1] if out.strip() else ''
            if rc != 0 or not phc.startswith('$argon2id$'):
                log(a.evid, f'STOP: PHC nao gerado para {c["chave"]} (codigo {rc}); nada foi gravado')
                return 7
            c['phc'] = phc
        try:
            grava_atomico(pend, plano, exclusivo=True)
        except OSError as e:
            log(a.evid, f'STOP: plano com as credenciais NAO gravado ({e.__class__.__name__}: {e}); nada foi enviado ao banco')
            return 8
        log(a.evid, f'plano pendente gravado (execucao {plano["execucao"]}, {len(plano["contas"])} contas, {len(plano["execucoes"])} execucoes)')
    else:
        log(a.evid, f'PENDENTE: reaproveitando o MESMO plano (execucao {plano["execucao"]}); nenhum id ou senha novo')
    rc, out, err = roda(bash, a.psql, script_sql(plano, a.sql), 1800)
    resumo = [x for x in out.splitlines() if x.startswith(('SEMEADURA_', 'CONFERE '))]
    for x in resumo:
        log(a.evid, 'psql: ' + x)
    if rc != 0:
        erro = [x for x in err.splitlines() if 'ERROR' in x or 'ERRO' in x][:3]
        log(a.evid, f'psql codigo {rc}: ' + ' | '.join(erro))
    no_banco = org_no_banco(bash, a.psql, plano['orgs'][0]['id'])
    if no_banco is None:
        log(a.evid, 'STOP: resultado incerto e o banco nao respondeu a conferencia; estado continua PENDENTE ou A_RECONCILIAR (rode de novo)')
        return 9
    if not no_banco:
        log(a.evid, 'NAO APLICADA: a transacao nao foi efetivada (rollback); o plano pendente fica para repetir com os mesmos ids')
        return 10
    if rc != 0 or 'SEMEADURA_FRONTEND_APLICADA' not in out:
        log(a.evid, 'resultado do psql perdido ou ambiguo, mas a organizacao do plano ESTA no banco: transacao efetivada')
    try:
        promove(pend, fin, a.evid, plano)
    except PlanoTrocado as e:
        log(a.evid, f'STOP: {e}; a semeadura aplicada corresponde ao plano da execucao {plano["execucao"]}; nada promovido')
        return 12
    except OSError as e:
        log(a.evid, f'A_RECONCILIAR: semeadura efetivada e promocao do plano falhou ({e.__class__.__name__}); credenciais no plano pendente; rode de novo')
        return 6
    log(a.evid, 'CONCLUIDA: semeadura efetivada e plano final gravado')
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
    t = sub.add_parser('liberar-trava')
    t.add_argument('--segredos', required=True)
    t.add_argument('--confirmo-inativo', action='store_true')
    sub.add_parser('catalogo')
    a = ap.parse_args()
    if a.acao == 'semear':
        sys.exit(semear(a))
    if a.acao == 'estado':
        if os.path.isdir(Trava(a.segredos).dir):
            print('EM_ANDAMENTO')   # outra execucao segura a trava (ou trava orfa): nada se conclui pelo estado
            sys.exit(1)
        est, _ = estado(a.segredos, bash_exe(a.bash), a.psql, a.evid)
        print(est)
        sys.exit(0 if est in ('CONCLUIDA', 'AUSENTE') else 1)
    if a.acao == 'liberar-trava':
        tr = Trava(a.segredos)
        if not os.path.isdir(tr.dir):
            print('sem trava')
            sys.exit(0)
        d = tr.dono()
        if not a.confirmo_inativo:
            raise SystemExit(f'trava de pid={d.get("pid")} host={d.get("host")}: confirme que a execucao terminou (--confirmo-inativo)')
        if d.get('host') == socket.gethostname() and pid_vivo(d.get('pid')):
            raise SystemExit(f'RECUSADO: o dono da trava (pid {d.get("pid")}) ainda esta vivo neste host')
        tr.libera()
        print(f'trava orfa liberada (dono pid={d.get("pid")} host={d.get("host")} desde {d.get("inicio")})')
        sys.exit(0)
    p = catalogo_plano(random.Random(1))
    print(json.dumps({'esperado': esperado(p), 'contas': [(c['chave'], c['uso'], c['mfa']) for c in p['contas']]}, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
