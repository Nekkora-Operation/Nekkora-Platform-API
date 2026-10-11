"""Validacao administrativa da F6a contra a API REAL do ensaio-frontend (F6a-V-T): preparo dos alvos dedicados (f6av-*),
comprovacao dos efeitos de cada acao feita no navegador, recusa deterministica do step-up e fotos de preservacao (banco
e Docker) com as mudancas PERMITIDAS declaradas por identidade, tabela, campo e operacao.

Fala com a API como o navegador (mesmas classes das jornadas: testes/jornadas_frontend.py - Alvo, Sessao e o
Validador do OpenAPI; cada resposta e validada). Le o banco SO com SELECT, pelo comando --psql (no runner:
admin_sql nekkora). Para no PRIMEIRO resultado inesperado (STOP, codigo 1): nada e corrigido nem repetido.

Subcomandos (todos com --segredos; os que falam com a API: --evid --openapi-json --openapi-yaml --alvo --ca):
  preparar          cadastra o MFA dos 4 alvos pela API e deixa UMA sessao aberta em cada (guardada em
                    <segredos>/frontend/f6a/sessoes/<chave>.json, nunca na evidencia); confere o estado inicial do
                    cliente no portal. Alvo ja preparado = pulado (sem nova requisicao).
  conferir <fase>   comprova os efeitos de UMA etapa da navegacao: inicial | suspensao | reativacao | stepup-negado |
                    renovar | mfa | revogacao (banco + API; ver FASES)
                    Sessoes (F6a-V-E, STOP da V4.4): cada sessao e UTILIZAVEL, REVOGADA ou EXPIRADA (absoluta ou por
                    INATIVIDADE: 30 min interna, 15 min externa, como a Platform; a inatividade NAO preenche revoked_at).
                    A revogacao de uma acao administrativa e provada no banco e LIGADA a acao: revoked_at no intervalo do
                    POST da acao registrado no gateway (logs do gateway, sem requisicao). A prova pela sessao antiga
                    (401) so roda se a sessao era utilizavel no instante da acao E AINDA estaria dentro dos prazos
                    (absoluto e de inatividade, com margem) no instante da consulta, desconsiderando so a revogacao ja
                    provada; senao um 401 nao distingue revogacao de expiracao: fica NAO EXECUTADA (inconclusiva), sem
                    requisicao, e a M.02 (obrigatoria) PARA. "renovar" abre sessoes NOVAS (login + TOTP) de f6av-mfa e
                    f6av-cliente imediatamente antes do reset e da revogacao e registra ate quando ficam utilizaveis.
  anti-replay       (sem API) le o passo TOTP do login da admin (reservado por credencial_navegacao.py) e o
                    last_used_step do fator dela no banco; se o servidor JA aceitou esse passo (passo <= last_used_step),
                    grava a autorizacao de REUSO, de uso unico, em <segredos>/frontend/f6a/reuso_admin.json (so numeros):
                    o reuso desse codigo no step-up e recusado pelo anti-replay de forma deterministica
  foto <arquivo>    (sem API) foto do banco (todas as tabelas do schema public: digest por linha; JSON so das linhas que
                    referem a admin ou um f6av-*, sem colunas sensiveis) e do Docker (containers, volumes, redes, imagens)
  comparar <antes> <depois>   aplica as mudancas permitidas (REGRAS_ATOR, alvos f6av-* e a substituicao esperada do
                    gateway); qualquer outra diferenca = VIOLACAO (codigo 1)
  intencoes --desde <instante> --logs-cmd <cmd>   conta as linhas evento=mail.intencao do log da Platform por tipo
                    (transporte LOCAL: so a INTENCAO registrada, entrega=nenhuma; nunca "e-mail entregue")

Nunca grava corpo, cookie, CSRF, senha, segredo TOTP, codigo TOTP ou codigo de recuperacao na evidencia: so status,
ids sinteticos, estados, contagens e numeros de passo TOTP (derivados do relogio, nao sao segredo).
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.parse

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
sys.path.insert(0, os.path.join(os.path.dirname(AQUI), 'lib'))
import jornadas_frontend as jf  # noqa: E402
import semear_frontend as sf  # noqa: E402

INTERNAS = ('f6av-suspensao', 'f6av-reativacao', 'f6av-mfa')
EXTERNA = 'f6av-cliente'
ALVOS = INTERNAS + (EXTERNA,)
FASES = ('inicial', 'suspensao', 'reativacao', 'stepup-negado', 'renovar', 'mfa', 'revogacao')
# Prazo de inatividade das sessoes (api/nekkora-platform/src/main/resources/application.yml: nekkora.session
# internal-inactivity / external-inactivity; o ambiente nao sobrescreve). Conferido pelo teste de unidade.
INATIVIDADE_MIN = {'interna': 30, 'externa': 15}
# Janela da ligacao acao -> revogacao: revoked_at entre (instante do POST no gateway - ANTES) e (+ DEPOIS) segundos
LIGACAO_ANTES, LIGACAO_DEPOIS = 5.0, 1.0
# Margem da consulta da sessao antiga: os prazos tem de valer ate AGORA + margem (a requisicao acontece logo depois)
MARGEM_CONSULTA_S = 60
GATEWAY = '/nekkora-homologacao-ensaio-frontend-gateway-1'
MOTIVO_CODIGO = {'DISPOSITIVO_PERDIDO': 1, 'DISPOSITIVO_TROCADO': 2, 'SUSPEITA_COMPROMETIMENTO': 3, 'OUTRO': 4}
SENSIVEL = re.compile(r'(hash|token|secret|nonce|ciphertext|password|csrf)', re.I)


# ============================================================================================== estado local
def agora():
    return datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def le(p):
    return json.load(open(p, encoding='utf-8')) if os.path.isfile(p) else None


class Cofre:
    """<segredos>/frontend: plano da F1.5 (admin) e f6a/ (plano complementar, MFA e sessoes dos alvos)."""

    def __init__(self, segredos, relogio=None, dorme=None):
        self.seg = segredos
        self.base = le(os.path.join(segredos, 'frontend', 'semeadura.json'))
        self.f6a = le(os.path.join(segredos, 'frontend', 'f6a', 'semeadura.json'))
        if self.base is None:
            raise SystemExit('semeadura da F1.5 nao concluida (sem frontend/semeadura.json)')
        if self.f6a is None:
            raise SystemExit('semeadura complementar nao concluida (sem frontend/f6a/semeadura.json): rode semear-f6a')
        self.contas = {c['chave']: c for c in self.f6a['contas']}
        self.admin = next(c for c in self.base['contas'] if c['chave'] == 'admin')
        self.org = self.f6a['refs']['org_a']
        self.relogio = relogio or time.time
        self.dorme = dorme or time.sleep

    def dir(self, *p):
        return os.path.join(self.seg, 'frontend', 'f6a', *p)

    def mfa(self, chave):
        return le(self.dir('contas', f'{chave}.json'))

    def grava_mfa(self, chave, m):
        jf.grava_atomico(self.dir('contas', f'{chave}.json'), m)

    def codigo(self, chave):
        """Codigo de um passo NUNCA usado pela conta (anti-replay); o passo e gravado ANTES de devolver o codigo."""
        m = self.mfa(chave)
        while True:
            t = int(self.relogio())
            passo = t // 30
            cand = max(passo - 1 if t % 30 < 25 else passo, m.get('ultimo_passo', -1) + 1)
            if passo - 1 <= cand <= passo + 1:
                m['ultimo_passo'] = cand
                self.grava_mfa(chave, m)
                return jf.totp(m['totp'], cand)
            self.dorme(1)

    def sessao(self, chave):
        return le(self.dir('sessoes', f'{chave}.json'))

    def grava_sessao(self, chave, s):
        jf.grava_atomico(self.dir('sessoes', f'{chave}.json'), {'cookies': s.cookies, 'csrf': s.csrf, 'criada_em': agora()})

    def ids_f6av(self):
        ids = set()
        for c in self.f6a['contas']:
            ids |= {c['user_id'], c['membership_id']}
        ids |= {g['grant_id'] for g in self.f6a['concessoes']}
        return ids

    def ids_admin(self):
        return {self.admin['user_id'], self.admin['membership_id']}

    def estado(self):
        """Estado da validacao entre as fases (ids, nao segredos): o fator ORIGINAL de cada alvo, lido na fase inicial."""
        return le(self.dir('estado_validacao.json')) or {}

    def grava_estado(self, e):
        jf.grava_atomico(self.dir('estado_validacao.json'), e)


# ===================================================================================================== banco
def sql(bash, psql, texto):
    rc, out, err = sf.roda(bash, psql, texto, 600)
    if rc != 0:
        raise jf.Stop(f'consulta ao banco falhou (codigo {rc}): ' + ' | '.join(x for x in err.splitlines() if x.strip())[:300])
    return out


def chaves(out, prefixo='V|'):
    d = {}
    for x in out.splitlines():
        if x.startswith(prefixo):
            k, _, v = x[len(prefixo):].partition('=')
            d[k] = v
    return d


# estado de cada sessao, como a Platform a avalia (SessionRepository): revogada; expirada (absoluta); expirada por
# INATIVIDADE (last_seen_at + prazo do tipo de conta); senao utilizavel. A inatividade NAO preenche revoked_at.
ESTADO_SESSAO = f"""CASE WHEN s.revoked_at IS NOT NULL THEN 'revogada'
            WHEN s.expires_at <= now() THEN 'expirada_absoluta'
            WHEN s.last_seen_at <= now() - CASE WHEN u.account_type = 1 THEN interval '{INATIVIDADE_MIN['interna']} minutes'
                                             ELSE interval '{INATIVIDADE_MIN['externa']} minutes' END THEN 'expirada_inatividade'
            ELSE 'utilizavel' END"""
ISO = "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'"


def estado_conta(bash, psql, org, user_id):
    """Estado de uma conta, so SELECT: status, sessoes (UTILIZAVEIS, REVOGADAS, EXPIRADAS e total), fator
    (ativo/desativado), codigos de recuperacao ativos e eventos de MFA por tipo (com motivo e ator do reset)."""
    u = user_id
    out = sql(bash, psql, f"""
SELECT 'V|status=' || status FROM users WHERE organization_id = '{org}' AND user_id = '{u}';
SELECT 'V|sessoes_utilizaveis=' || count(*) FILTER (WHERE e = 'utilizavel') || chr(10) ||
       'V|sessoes_revogadas=' || count(*) FILTER (WHERE e = 'revogada') || chr(10) ||
       'V|sessoes_expiradas=' || count(*) FILTER (WHERE e LIKE 'expirada%') || chr(10) ||
       'V|sessoes_total=' || count(*)
  FROM (SELECT {ESTADO_SESSAO} AS e FROM user_sessions s JOIN users u USING (organization_id, user_id)
         WHERE s.organization_id = '{org}' AND s.user_id = '{u}') x;
SELECT 'V|fator_ativo=' || count(*) FILTER (WHERE disabled_at IS NULL AND confirmed_at IS NOT NULL) || chr(10) ||
       'V|fator_desativado=' || count(*) FILTER (WHERE disabled_at IS NOT NULL) || chr(10) ||
       'V|fator_ativo_id=' || coalesce(max(factor_id::text) FILTER (WHERE disabled_at IS NULL AND confirmed_at IS NOT NULL), '-')
  FROM user_mfa_factors WHERE organization_id = '{org}' AND user_id = '{u}';
SELECT 'V|codigos_ativos=' || count(*) FROM user_mfa_recovery_codes WHERE organization_id = '{org}' AND user_id = '{u}'
   AND used_at IS NULL AND invalidated_at IS NULL;
SELECT 'V|eventos_reset=' || count(*) FILTER (WHERE event_type = 3) || chr(10) ||
       'V|reset_motivos=' || coalesce(string_agg(reason_code::text || '@' || actor_user_id::text, ',' ORDER BY created_at) FILTER (WHERE event_type = 3), '-')
  FROM mfa_events WHERE organization_id = '{org}' AND user_id = '{u}';
""")
    return chaves(out)


def estado_concessao(bash, psql, org, grant_id):
    out = sql(bash, psql, f"""
SELECT 'V|status=' || status || chr(10) || 'V|revogada=' || (revoked_at IS NOT NULL) || chr(10) ||
       'V|revogada_por=' || coalesce(revoked_by_membership_id::text, '-')
  FROM client_workspace_grants WHERE organization_id = '{org}' AND grant_id = '{grant_id}';
""")
    return chaves(out)


def revogada_em_concessao(bash, psql, org, grant_id):
    out = sql(bash, psql, f"""
SELECT 'V|revogada_em=' || coalesce(to_char(revoked_at AT TIME ZONE 'UTC', {ISO}), '-')
  FROM client_workspace_grants WHERE organization_id = '{org}' AND grant_id = '{grant_id}';
""")
    return chaves(out).get('revogada_em', '-')


def sessoes_conta(bash, psql, org, user_id):
    """Cada sessao da conta, em ordem de criacao (so SELECT): id, criada, ultimo uso, revogada_em, expira, prazo de
    inatividade (min) e estado (revogada | expirada_absoluta | expirada_inatividade | utilizavel). Ids e instantes,
    nunca token nem CSRF."""
    out = sql(bash, psql, f"""
SELECT 'S|' || s.session_id || '|' || to_char(s.created_at AT TIME ZONE 'UTC', {ISO}) || '|' ||
       to_char(s.last_seen_at AT TIME ZONE 'UTC', {ISO}) || '|' || coalesce(to_char(s.revoked_at AT TIME ZONE 'UTC', {ISO}), '-') || '|' ||
       to_char(s.expires_at AT TIME ZONE 'UTC', {ISO}) || '|' ||
       CASE WHEN u.account_type = 1 THEN {INATIVIDADE_MIN['interna']} ELSE {INATIVIDADE_MIN['externa']} END || '|' || {ESTADO_SESSAO}
  FROM user_sessions s JOIN users u USING (organization_id, user_id)
 WHERE s.organization_id = '{org}' AND s.user_id = '{user_id}' ORDER BY s.created_at, s.session_id;
""")
    r = []
    for x in out.splitlines():
        if x.startswith('S|'):
            sid, cri, ult, rev, exp, inat, est = x[2:].split('|')
            r.append({'id': sid, 'criada': cri, 'ultimo_uso': ult, 'revogada_em': rev, 'expira': exp, 'inatividade_min': int(inat), 'estado': est})
    return r


def instante(iso):
    """'2026-10-10T19:36:51.064807459Z' (ate nanossegundos) -> datetime UTC (microssegundos)."""
    m = re.fullmatch(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?Z', iso)
    if not m:
        raise jf.Stop(f'instante invalido: {iso}')
    frac = (m.group(2) or '0')[:6].ljust(6, '0')
    return datetime.datetime.strptime(f'{m.group(1)}.{frac}', '%Y-%m-%dT%H:%M:%S.%f').replace(tzinfo=datetime.timezone.utc)


def utilizavel_em(sess, t):
    """A sessao era UTILIZAVEL no instante t (sem revogacao ate t, antes da expiracao absoluta e dentro do prazo de
    inatividade contado do ultimo uso registrado)? O ultimo uso registrado e um limite inferior (a Platform so atualiza
    last_seen_at de minuto em minuto): resposta conservadora."""
    if sess['revogada_em'] != '-' and instante(sess['revogada_em']) <= t:
        return False
    if instante(sess['expira']) <= t:
        return False
    return instante(sess['ultimo_uso']) + datetime.timedelta(minutes=sess['inatividade_min']) > t


def agora_utc():
    """Relogio da conferencia (substituivel nos testes)."""
    return datetime.datetime.now(datetime.timezone.utc)


def dentro_dos_prazos(sess, agora, margem=MARGEM_CONSULTA_S):
    """Os prazos ABSOLUTO e de INATIVIDADE da sessao ainda valem em agora + margem, DESCONSIDERANDO a revogacao (ja
    provada no banco pelo chamador). Se nao valem, um 401 nessa sessao pode ser so expiracao."""
    return utilizavel_em({**sess, 'revogada_em': '-'}, agora + datetime.timedelta(seconds=margem))


def prazos(sess):
    fim_inat = instante(sess['ultimo_uso']) + datetime.timedelta(minutes=sess['inatividade_min'])
    return f'expira {sess["expira"]}; inatividade ate {fim_inat.strftime("%Y-%m-%dT%H:%M:%SZ")}'


def acoes_gateway(a, bash, desde):
    """POSTs registrados no log do gateway desde 'desde' (instante, metodo, caminho, status). Le os LOGS (sem
    requisicao). Comando: --logs-gateway-cmd (padrao: compose logs do ambiente, funcao exportada pelo runner)."""
    cmd = (a.logs_gateway_cmd or 'compose logs --no-color --timestamps --since {desde} gateway').replace('{desde}', desde)
    rc = subprocess.run([bash, '-c', cmd], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if rc.returncode != 0:
        raise jf.Stop(f'coleta dos logs do gateway falhou (codigo {rc.returncode})')
    r = []
    for x in rc.stdout.decode('utf-8', 'replace').splitlines():
        m = re.search(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z) \S+ "(POST) ([^" ?]+)[^"]*" (\d{3})', x)
        if m:
            r.append((m.group(1), m.group(2), m.group(3), int(m.group(4))))
    return r


def acao_unica(rel, pid, acoes, sufixo, descricao):
    """Exatamente UM POST <sufixo> com 204 no gateway: devolve o instante (datetime)."""
    ts = [x[0] for x in acoes if x[1] == 'POST' and x[2].endswith(sufixo) and x[3] == 204]
    outros = [f'{x[3]}' for x in acoes if x[1] == 'POST' and x[2].endswith(sufixo) and x[3] != 204]
    rel.confere(pid, f'{descricao}: exatamente 1 POST ...{sufixo[-40:]} com 204 no gateway', len(ts) == 1 and not outros,
                f'{len(ts)} com 204{"; outros status " + ",".join(outros) if outros else ""}{"; " + ts[0] if len(ts) == 1 else ""}')
    return instante(ts[0])


def ligada(rev_iso, t):
    """revoked_at ligado ao POST da acao: entre t - LIGACAO_ANTES e t + LIGACAO_DEPOIS segundos."""
    if rev_iso == '-':
        return False
    r = instante(rev_iso)
    return t - datetime.timedelta(seconds=LIGACAO_ANTES) <= r <= t + datetime.timedelta(seconds=LIGACAO_DEPOIS)


def estado_fator(bash, psql, org, user_id, factor_id):
    """Um fator ESPECIFICO (pelo id): existe para a conta, confirmado e desativado (so SELECT)."""
    out = sql(bash, psql, f"""
SELECT 'V|existe=' || count(*) || chr(10) || 'V|desativado=' || coalesce(bool_and(disabled_at IS NOT NULL), false) || chr(10) ||
       'V|confirmado=' || coalesce(bool_and(confirmed_at IS NOT NULL), false)
  FROM user_mfa_factors WHERE organization_id = '{org}' AND user_id = '{user_id}' AND factor_id = '{factor_id}';
""")
    return chaves(out)


def passo_aceito_admin(bash, psql, org, user_id):
    out = sql(bash, psql, f"""
SELECT 'V|last_used_step=' || coalesce(max(last_used_step), -1) || chr(10) || 'V|fatores_ativos=' || count(*)
  FROM user_mfa_factors WHERE organization_id = '{org}' AND user_id = '{user_id}' AND disabled_at IS NULL AND confirmed_at IS NOT NULL;
""")
    return chaves(out)


# ================================================================================================ relatorio
class Rel:
    """Compativel com jornadas_frontend.Sessao (contrato()); OK/FALHA com STOP na primeira falha."""

    def __init__(self, evid, nome):
        self.evid = evid
        os.makedirs(evid, exist_ok=True)
        self.h = open(os.path.join(evid, f'{nome}.txt'), 'a', encoding='utf-8')
        self.passos = 0
        self.jornada = nome

    def escreve(self, linha):
        print(linha, flush=True)
        self.h.write(linha + '\n')
        self.h.flush()

    def contrato(self, metodo, caminho, status, erros):
        if erros:
            self.escreve(f'FALHA CONTRATO {metodo} {caminho.split("?")[0]} -> {status}: ' + ' | '.join(erros[:5]))
            raise jf.Stop('resposta fora do contrato')

    def confere(self, pid, descricao, ok, obtido=''):
        self.passos += 1
        self.escreve(f'{"OK   " if ok else "FALHA"} {pid} {descricao}' + (f' (obtido: {obtido})' if obtido != '' else ''))
        if not ok:
            raise jf.Stop(f'{pid}: resultado inesperado')


# ====================================================================================================== API
class Api:
    def __init__(self, a, cofre, rel, v, dorme=None):
        self.a, self.cofre, self.rel, self.v = a, cofre, rel, v
        self.alvo = jf.Alvo(a)
        self.b = f'/api/v1/o/{cofre.org}'
        self.portal = f'/portal/api/v1/o/{cofre.org}/workspaces'
        self.dorme = dorme or time.sleep
        self.ultimo_login = None

    def nova(self):
        return jf.Sessao(self.alvo, self.v, self.rel)

    def restaura(self, chave):
        d = self.cofre.sessao(chave)
        if not d:
            raise jf.Stop(f'{chave}: sem sessao guardada (rode preparar)')
        s = self.nova()
        s.cookies, s.csrf = dict(d['cookies']), d['csrf']
        return s

    def ritmo(self):
        """Pelo menos --ritmo segundos entre logins (limitador R7 por IP; STOP M.26 da P-B)."""
        if self.ultimo_login is not None:
            falta = self.a.ritmo - (time.time() - self.ultimo_login)
            if falta > 0:
                self.dorme(falta)
        self.ultimo_login = time.time()

    def login_senha(self, chave):
        """CSRF de pre-autenticacao + POST login. Devolve (sessao, status, corpo)."""
        self.ritmo()
        c = self.cofre.contas[chave]
        s = self.nova()
        st, js, _ = s.req('GET', '/api/v1/csrf')
        self.rel.confere('A.01', f'{chave}: CSRF de pre-autenticacao', st == 200, st)
        s.csrf = js['csrfToken']
        st, js, _ = s.req('POST', self.b + '/auth/login', {'email': c['email'], 'password': c['senha']})
        if st == 200:
            s.csrf = js['csrfToken']
        return s, st, js

    def cadastra(self, chave, s):
        """Cadastro de MFA numa sessao de desafio de cadastro: segredo PENDENTE gravado antes da confirmacao."""
        st, js, _ = s.req('POST', self.b + '/auth/mfa/enroll/start')
        self.rel.confere('A.10', f'{chave}: inicio do cadastro de MFA', st == 200, st)
        segredo = urllib.parse.parse_qs(urllib.parse.urlparse(js['otpauthUri']).query)['secret'][0]
        anterior = self.cofre.mfa(chave)
        self.cofre.grava_mfa(chave, {'totp': segredo, 'estado': 'pendente', 'ultimo_passo': -1,
                                     'cadastros': (anterior or {}).get('cadastros', 0)})
        st, js, _ = s.req('POST', self.b + '/auth/mfa/enroll/confirm', {'code': self.cofre.codigo(chave)})
        self.rel.confere('A.11', f'{chave}: cadastro de MFA confirmado', st == 200, st)
        m = self.cofre.mfa(chave)
        m.update({'estado': 'confirmado', 'codigos': js['recoveryCodes'], 'cadastros': m.get('cadastros', 0) + 1})
        self.cofre.grava_mfa(chave, m)
        s.csrf = js['csrfToken']

    def verifica(self, chave, s):
        st, js, _ = s.req('POST', self.b + '/auth/mfa/verify', {'code': self.cofre.codigo(chave)})
        self.rel.confere('A.12', f'{chave}: verificacao TOTP', st == 200, st)
        s.csrf = js['csrfToken']

    def me(self, s, chave, esperado, pid):
        st, _, _ = s.req('GET', self.b + '/me')
        self.rel.confere(pid, f'{chave}: GET /me -> {esperado}', st == esperado, st)

    def portal_ws(self, s, chave, esperado_ids, pid):
        st, js, _ = s.req('GET', self.portal)
        ids = {w['workspaceId'] for w in (js or {}).get('items', [])} if st == 200 else None
        self.rel.confere(pid, f'{chave}: portal lista exatamente {sorted(esperado_ids)}', st == 200 and ids == esperado_ids, f'{st} {sorted(ids) if ids else ids}')

    def logout(self, s, chave, pid):
        st, _, _ = s.req('POST', self.b + '/auth/logout')
        self.rel.confere(pid, f'{chave}: logout', st == 204, st)


# ================================================================================================= preparar
def preparar(a, api, cofre, rel):
    """Uma passada por alvo. Recuperacao de um preparo interrompido (mesmas regras das jornadas, preparar-contas):
    fator confirmado sem sessao -> login + verificacao; segredo PENDENTE gravado e o servidor ja pede verificacao ->
    o fator foi confirmado com esse segredo (resposta perdida): verifica e marca; servidor ainda pede cadastro -> o
    cadastro pendente nao valeu: cadastra de novo. Nada e repetido dentro da mesma execucao."""
    ws = cofre.f6a['refs']['workspaces']
    for chave in ALVOS:
        m, sessao = cofre.mfa(chave), cofre.sessao(chave)
        if m and m.get('estado') == 'confirmado' and sessao:
            rel.escreve(f'JA PREPARADA {chave} (nenhuma requisicao)')
            continue
        s, st, js = api.login_senha(chave)
        rel.confere('P.01', f'{chave}: login por senha', st == 200 and js['status'] in ('mfa_enrollment_required', 'mfa_required'),
                    f'{st} {js.get("status") if js else ""}')
        if js['status'] == 'mfa_enrollment_required':
            if m and m.get('estado') == 'confirmado':
                raise jf.Stop(f'{chave}: cofre diz MFA confirmado e o servidor pede cadastro (fator desativado fora do preparo)')
            api.cadastra(chave, s)
        else:
            if not (m and m.get('totp')):
                raise jf.Stop(f'{chave}: servidor pede verificacao e o cofre nao tem segredo (fator sem segredo recuperavel)')
            api.verifica(chave, s)
            if m.get('estado') != 'confirmado':
                m = cofre.mfa(chave)
                m.update({'estado': 'confirmado', 'codigos': [], 'codigos_perdidos': True})
                cofre.grava_mfa(chave, m)
                rel.escreve(f'RECUPERADO {chave}: o segredo pendente confere com o fator confirmado (codigos de recuperacao perdidos)')
        cofre.grava_sessao(chave, s)
        rel.escreve(f'SESSAO ABERTA {chave} (guardada nos segredos do ambiente)')
        api.me(s, chave, 200, 'P.02')
        if chave == EXTERNA:
            api.portal_ws(s, chave, {ws['exp-astral'], ws['exp-bmp']}, 'P.03')


# ================================================================================================== conferir
def conferir(a, fase, api, cofre, rel, bash):
    org, ws = cofre.org, cofre.f6a['refs']['workspaces']
    uid = {k: cofre.contas[k]['user_id'] for k in ALVOS}
    g = {x['chave']: x['grant_id'] for x in cofre.f6a['concessoes']}
    adm_u, adm_m = cofre.admin['user_id'], cofre.admin['membership_id']
    ec = lambda k: estado_conta(bash, a.psql, org, uid[k])
    eg = lambda k: estado_concessao(bash, a.psql, org, g[k])
    ss = lambda k: sessoes_conta(bash, a.psql, org, uid[k])

    ncod = lambda k: str(len(cofre.mfa(k).get('codigos') or []))
    ef = lambda k, fid: estado_fator(bash, a.psql, org, uid[k], fid)

    def desde(ses):
        return (ses[0]['criada'][:19] + 'Z') if ses else agora()

    def antigas_revogadas_pela(pid, k, ses, t_acao, descricao):
        """Toda sessao criada ANTES da acao esta REVOGADA, com revoked_at LIGADO ao POST da acao (a inatividade nao
        conta: so revoked_at prova a revogacao)."""
        antes = [s for s in ses if instante(s['criada']) < t_acao]
        ruins = [f'{s["id"][:8]} {s["estado"]} revogada_em={s["revogada_em"]}' for s in antes if not ligada(s['revogada_em'], t_acao)]
        rel.confere(pid, f'{k}: {descricao}: {len(antes)} sessao(oes) anteriores, todas REVOGADAS no instante da acao',
                    bool(antes) and not ruins, '; '.join(ruins) or f'{len(antes)} ligadas')
        return antes

    def consulta_sessao_antiga(pid, k, s_ant, t_acao, esperado, obrigatoria=False):
        """A sessao antiga so DISCRIMINA (401 = revogacao) se (1) era UTILIZAVEL imediatamente antes da acao e (2) os
        prazos absoluto e de inatividade AINDA valem no instante da consulta (agora + margem). Nos dois casos so a
        revogacao ja provada ligada a acao e desconsiderada. Senao: sem requisicao; NAO EXECUTADA (inconclusiva), ou
        STOP se a prova e obrigatoria."""
        if s_ant is None:
            motivo = 'nao ha exatamente 1 sessao antiga'
        elif not utilizavel_em({**s_ant, 'revogada_em': '-'}, t_acao):
            motivo = f'a sessao NAO era utilizavel no instante da acao ({s_ant["estado"]}; {prazos(s_ant)})'
        else:
            agora = agora_utc()
            motivo = None if dentro_dos_prazos(s_ant, agora) else (
                f'o prazo da sessao termina antes de {(agora + datetime.timedelta(seconds=MARGEM_CONSULTA_S)).strftime("%Y-%m-%dT%H:%M:%SZ")} '
                f'({prazos(s_ant)}): um 401 nao distinguiria revogacao de expiracao')
        if motivo is None:
            api.me(api.restaura(k), k, esperado, pid)
        elif obrigatoria:
            rel.confere(pid, f'{k}: sessao antiga utilizavel na acao e dentro dos prazos na consulta (prova do 401)', False, motivo)
        else:
            rel.escreve(f'NAO EXECUTADA {pid} {k}: {motivo}; a revogacao esta provada no banco')

    def prova_sessao_antiga(pid, k, antes, t_acao, esperado):
        consulta_sessao_antiga(pid, k, antes[0] if len(antes) == 1 else None, t_acao, esperado)

    if fase == 'inicial':
        originais = {}
        for k in INTERNAS:
            e = ec(k)
            rel.confere('I.01', f'{k}: ativa, 1 sessao UTILIZAVEL, fator ativo, {ncod(k)} codigos ativos, sem reset',
                        e == {**e, 'status': '2', 'sessoes_utilizaveis': '1', 'fator_ativo': '1', 'codigos_ativos': ncod(k), 'eventos_reset': '0'}
                        and e.get('fator_ativo_id', '-') != '-', e)
            originais[k] = e['fator_ativo_id']
            api.me(api.restaura(k), k, 200, 'I.02')
        # o fator ORIGINAL de cada alvo, registrado ANTES de qualquer acao: a fase mfa compara contra ELE
        cofre.grava_estado({**cofre.estado(), 'fator_original': originais})
        rel.escreve(f'FATORES ORIGINAIS registrados: {originais}')
        e = ec(EXTERNA)
        rel.confere('I.03', f'{EXTERNA}: ativa, 1 sessao UTILIZAVEL, fator ativo', e == {**e, 'status': '2', 'sessoes_utilizaveis': '1', 'fator_ativo': '1'}, e)
        for k, st in (('f6av-c1', '2'), ('f6av-c2', '2')):
            e = eg(k)
            rel.confere('I.04', f'{k}: ativa, nao revogada', e == {'status': st, 'revogada': 'false', 'revogada_por': '-'}, e)
        api.portal_ws(api.restaura(EXTERNA), EXTERNA, {ws['exp-astral'], ws['exp-bmp']}, 'I.05')

    elif fase == 'suspensao':
        k = 'f6av-suspensao'
        e = ec(k)
        rel.confere('S.01', f'{k}: SUSPENSA (status 3) e nenhuma sessao utilizavel', e['status'] == '3' and e['sessoes_utilizaveis'] == '0', e)
        ses = ss(k)
        t_s = acao_unica(rel, 'S.01a', acoes_gateway(a, bash, desde(ses)), f'/users/{uid[k]}/suspend', k)
        antes = antigas_revogadas_pela('S.01b', k, ses, t_s, 'suspensao')
        prova_sessao_antiga('S.02', k, antes, t_s, 401)
        _, st, _ = api.login_senha(k)
        rel.confere('S.03', f'{k}: novo login recusado (401 generico)', st == 401, st)

    elif fase == 'reativacao':
        k = 'f6av-reativacao'
        e = ec(k)
        rel.confere('R.01', f'{k}: ATIVA (status 2) e nenhuma sessao utilizavel (a anterior continua revogada)', e['status'] == '2' and e['sessoes_utilizaveis'] == '0', e)
        ses = ss(k)
        acoes = acoes_gateway(a, bash, desde(ses))
        t_s = acao_unica(rel, 'R.01a', acoes, f'/users/{uid[k]}/suspend', f'{k} (suspensao)')
        t_r = acao_unica(rel, 'R.01b', acoes, f'/users/{uid[k]}/reactivate', f'{k} (reativacao)')
        rel.confere('R.01c', f'{k}: a reativacao veio DEPOIS da suspensao', t_r > t_s, f'{t_s.isoformat()} -> {t_r.isoformat()}')
        antes = antigas_revogadas_pela('R.01d', k, ses, t_s, 'suspensao (a reativacao nao restaura sessao)')
        prova_sessao_antiga('R.02', k, antes, t_s, 401)
        s, st, js = api.login_senha(k)
        rel.confere('R.03', f'{k}: novo login -> desafio de MFA', st == 200 and js['status'] == 'mfa_required', st)
        api.verifica(k, s)
        api.me(s, k, 200, 'R.04')
        api.logout(s, k, 'R.05')

    elif fase == 'stepup-negado':
        k = 'f6av-mfa'
        e = ec(k)
        orig = cofre.estado().get('fator_original', {}).get(k)
        if not orig:
            raise jf.Stop(f'{k}: fator original nao registrado (rode conferir inicial antes)')
        rel.confere('N.01', f'{k}: INTACTA depois do step-up recusado (o MESMO fator ativo, nenhuma sessao revogada, {ncod(k)} codigos, sem reset)',
                    e == {**e, 'status': '2', 'fator_ativo': '1', 'sessoes_revogadas': '0', 'codigos_ativos': ncod(k), 'eventos_reset': '0',
                          'fator_ativo_id': orig}, e)
        ses = ss(k)
        if len(ses) == 1 and ses[0]['estado'] == 'utilizavel':
            api.me(api.restaura(k), k, 200, 'N.02')
        else:
            rel.escreve(f'NAO EXECUTADA N.02 {k}: a sessao guardada nao esta utilizavel ({", ".join(s["estado"] for s in ses)}): '
                        f'um 401 nao identificaria a causa')

    elif fase == 'renovar':
        # sessoes NOVAS, imediatamente antes do reset (f6av-mfa) e da revogacao (f6av-cliente): login + TOTP; a sessao
        # anterior NAO e tocada (expirada por inatividade continua sem revoked_at)
        orig = cofre.estado().get('fator_original', {}).get('f6av-mfa')
        if not orig:
            raise jf.Stop('f6av-mfa: fator original nao registrado (rode conferir inicial antes)')
        e = ec('f6av-mfa')
        rel.confere('V.01', 'f6av-mfa: ativa, o MESMO fator original ativo, sem reset (pre-condicao do reset)',
                    e == {**e, 'status': '2', 'fator_ativo': '1', 'fator_ativo_id': orig, 'eventos_reset': '0'}, e)
        e = ec(EXTERNA)
        rel.confere('V.02', f'{EXTERNA}: ativa, fator ativo', e == {**e, 'status': '2', 'fator_ativo': '1'}, e)
        for kc in ('f6av-c1', 'f6av-c2'):
            ecn = eg(kc)
            rel.confere('V.03', f'{kc}: ativa, nao revogada (pre-condicao da revogacao)', ecn == {'status': '2', 'revogada': 'false', 'revogada_por': '-'}, ecn)
        renov = {}
        for k in ('f6av-mfa', EXTERNA):
            anteriores = ss(k)
            rel.escreve(f'SESSOES ANTERIORES {k}: ' + ', '.join(f'{s["id"][:8]} {s["estado"]} ultimo_uso={s["ultimo_uso"]} revogada_em={s["revogada_em"]}' for s in anteriores))
            s, st, js = api.login_senha(k)
            rel.confere('V.04', f'{k}: novo login -> desafio de MFA', st == 200 and js['status'] == 'mfa_required', f'{st} {js.get("status") if js else ""}')
            api.verifica(k, s)
            cofre.grava_sessao(k, s)
            api.me(s, k, 200, 'V.05')
            if k == EXTERNA:
                api.portal_ws(s, k, {ws['exp-astral'], ws['exp-bmp']}, 'V.06')
            ids_ant = {x['id'] for x in anteriores}
            novas = [x for x in ss(k) if x['id'] not in ids_ant]
            rel.confere('V.07', f'{k}: exatamente 1 sessao NOVA e UTILIZAVEL', len(novas) == 1 and novas[0]['estado'] == 'utilizavel',
                        ', '.join(f'{x["id"][:8]} {x["estado"]}' for x in novas) or 'nenhuma')
            n = novas[0]
            ate = min(instante(n['ultimo_uso']) + datetime.timedelta(minutes=n['inatividade_min']), instante(n['expira']))
            renov[k] = {'sessao_id': n['id'], 'criada': n['criada'], 'utilizavel_ate': ate.strftime('%Y-%m-%dT%H:%M:%SZ'),
                        'inatividade_min': n['inatividade_min'], 'expira': n['expira']}
            rel.escreve(f'SESSAO RENOVADA {k}: {n["id"][:8]} criada {n["criada"]}; UTILIZAVEL ATE {renov[k]["utilizavel_ate"]} '
                        f'(inatividade {n["inatividade_min"]} min; expiracao absoluta {n["expira"]}) -> a acao e a conferencia '
                        f'posterior tem de ocorrer antes disso')
        cofre.grava_estado({**cofre.estado(), 'sessoes_renovadas': renov})

    elif fase == 'mfa':
        k = 'f6av-mfa'
        orig = cofre.estado().get('fator_original', {}).get(k)
        if not orig:
            raise jf.Stop(f'{k}: fator original nao registrado (rode conferir inicial antes)')
        ren = cofre.estado().get('sessoes_renovadas', {}).get(k)
        if not ren:
            raise jf.Stop(f'{k}: sessao renovada nao registrada (rode conferir renovar antes do reset)')
        e = ec(k)
        motivo = f'{MOTIVO_CODIGO[a.motivo]}@{adm_u}'
        rel.confere('M.01', f'{k}: fator desativado, nenhum codigo ativo, nenhuma sessao utilizavel, 1 reset com motivo {a.motivo} pela admin',
                    e == {**e, 'status': '2', 'fator_ativo': '0', 'codigos_ativos': '0', 'sessoes_utilizaveis': '0', 'eventos_reset': '1', 'reset_motivos': motivo}
                    and int(e['fator_desativado']) >= 1, e)
        fo = ef(k, orig)
        rel.confere('M.01b', f'{k}: o fator ORIGINAL ({orig}) existe e esta DESATIVADO', fo == {'existe': '1', 'desativado': 'true', 'confirmado': 'true'}, fo)
        ses = ss(k)
        t_m = acao_unica(rel, 'M.01c', acoes_gateway(a, bash, desde(ses)), f'/users/{uid[k]}/mfa/reset', k)
        sr = [x for x in ses if x['id'] == ren['sessao_id']]
        rel.confere('M.01d', f'{k}: a sessao RENOVADA era utilizavel no instante do reset e foi REVOGADA por ele',
                    len(sr) == 1 and utilizavel_em({**sr[0], 'revogada_em': '-'}, t_m) and ligada(sr[0]['revogada_em'], t_m),
                    f'{sr[0]["estado"]} ultimo_uso={sr[0]["ultimo_uso"]} revogada_em={sr[0]["revogada_em"]}' if sr else 'ausente')
        antes = antigas_revogadas_pela('M.01e', k, [x for x in ses if x['estado'] != 'expirada_inatividade' or x['revogada_em'] != '-'], t_m,
                                       'reset (sessoes que nao estavam expiradas)')
        # a sessao renovada era utilizavel e foi revogada pelo reset: o 401 so discrimina se os prazos ainda valem AGORA
        consulta_sessao_antiga('M.02', k, sr[0], t_m, 401, obrigatoria=True)
        s, st, js = api.login_senha(k)
        rel.confere('M.03', f'{k}: novo login -> cadastro de MFA exigido', st == 200 and js['status'] == 'mfa_enrollment_required', st)
        api.cadastra(k, s)
        api.me(s, k, 200, 'M.04')
        api.logout(s, k, 'M.05')
        s, st, js = api.login_senha(k)
        rel.confere('M.06', f'{k}: login com o fator NOVO -> desafio de MFA', st == 200 and js['status'] == 'mfa_required', st)
        api.verifica(k, s)
        api.logout(s, k, 'M.07')
        e2 = ec(k)
        rel.confere('M.08', f'{k}: UM fator ativo e NOVO (id diferente do original {orig})',
                    e2['fator_ativo'] == '1' and e2['fator_ativo_id'] not in ('-', orig), e2)
        fo = ef(k, orig)
        rel.confere('M.09', f'{k}: o fator ORIGINAL continua DESATIVADO depois do novo cadastro', fo == {'existe': '1', 'desativado': 'true', 'confirmado': 'true'}, fo)

    elif fase == 'revogacao':
        ren = cofre.estado().get('sessoes_renovadas', {}).get(EXTERNA)
        if not ren:
            raise jf.Stop(f'{EXTERNA}: sessao renovada nao registrada (rode conferir renovar antes da revogacao)')
        e1, e2 = eg('f6av-c1'), eg('f6av-c2')
        rel.confere('G.01', 'f6av-c1: REVOGADA (status 4) pela admin', e1 == {'status': '4', 'revogada': 'true', 'revogada_por': adm_m}, e1)
        rel.confere('G.02', 'f6av-c2 (controle): continua ATIVA e nao revogada', e2 == {'status': '2', 'revogada': 'false', 'revogada_por': '-'}, e2)
        ses = ss(EXTERNA)
        t_g = acao_unica(rel, 'G.01a', acoes_gateway(a, bash, desde(ses)), f'/client-grants/{g["f6av-c1"]}/revoke', 'f6av-c1')
        rev = revogada_em_concessao(bash, a.psql, org, g['f6av-c1'])
        rel.confere('G.01b', 'f6av-c1: revoked_at LIGADO ao POST da revogacao', ligada(rev, t_g), rev)
        sr = [x for x in ses if x['id'] == ren['sessao_id']]
        if not (len(sr) == 1 and sr[0]['estado'] == 'utilizavel' and dentro_dos_prazos(sr[0], agora_utc())):
            rel.confere('G.03', f'{EXTERNA}: a sessao RENOVADA esta UTILIZAVEL e dentro dos prazos na consulta (senao o portal nao discriminaria)',
                        False, f'{sr[0]["estado"]}; {prazos(sr[0])}' if sr else 'ausente')
        api.portal_ws(api.restaura(EXTERNA), EXTERNA, {ws['exp-bmp']}, 'G.03')
        s, st, js = api.login_senha(EXTERNA)
        rel.confere('G.04', f'{EXTERNA}: novo login -> desafio de MFA', st == 200 and js['status'] == 'mfa_required', st)
        api.verifica(EXTERNA, s)
        api.portal_ws(s, EXTERNA, {ws['exp-bmp']}, 'G.05')
        api.logout(s, EXTERNA, 'G.06')


# =============================================================================================== anti-replay
def anti_replay(a, cofre, bash):
    arq = os.path.join(a.segredos, 'frontend', 'contas', 'admin.json')
    m = le(arq)
    if not m or m.get('estado') != 'confirmado':
        print('RECUSADO: admin sem MFA confirmado no cofre da F1.5')
        return 1
    p = m.get('ultimo_passo', -1)
    e = passo_aceito_admin(bash, a.psql, cofre.org, cofre.admin['user_id'])
    L = int(e.get('last_used_step', '-1'))
    if e.get('fatores_ativos') != '1':
        print(f'RECUSADO: admin com {e.get("fatores_ativos")} fatores ativos (esperado 1)')
        return 1
    if p < 0 or p > L:
        print(f'RECUSADO: passo {p} (o ultimo entregue) AINDA NAO foi aceito pelo servidor (last_used_step {L}); reuso nao seria deterministico')
        return 1
    jf.grava_atomico(cofre.dir('reuso_admin.json'), {'passo': p, 'last_used_step': L, 'conferido_em': agora()})
    print(f'REUSO AUTORIZADO (uso unico): passo {p} <= last_used_step {L}: o anti-replay do servidor RECUSA esse codigo; '
          f'credencial_navegacao.py admin totp-reuso')
    return 0


# ===================================================================================================== fotos
def catalogo_tabelas(bash, psql):
    out = sql(bash, psql, r"""
SELECT 'T|' || c.relname || '|' || coalesce((
         SELECT string_agg(a.attname, ',' ORDER BY k.ord) FROM pg_index i
           CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum, ord)
           JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
          WHERE i.indrelid = c.oid AND i.indisprimary), '') || '|' ||
       (SELECT string_agg(a.attname, ',' ORDER BY a.attnum) FROM pg_attribute a
         WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped)
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY c.relname;
""")
    tabs = []
    for x in out.splitlines():
        if x.startswith('T|'):
            _, nome, pk, cols = x.split('|', 3)
            cols = cols.split(',')
            tabs.append({'nome': nome, 'pk': pk.split(',') if pk else [], 'cols': cols, 'sens': [c for c in cols if SENSIVEL.search(c)]})
    return tabs


def foto_banco(bash, psql, ids_observados):
    tabs = catalogo_tabelas(bash, psql)
    partes = []
    for t in tabs:
        sens = "ARRAY[" + ','.join(f"'{c}'" for c in t['sens']) + "]::text[]" if t['sens'] else "ARRAY[]::text[]"
        pk = " || '/' || ".join(f'coalesce(t."{c}"::text, \'\')' for c in t['pk']) if t['pk'] else "md5(to_jsonb(t)::text)"
        svals = " || '|' || ".join(f'coalesce(t."{c}"::text, \'\')' for c in t['sens']) if t['sens'] else "''"
        partes.append(
            f"SELECT 'R|{t['nome']}|' || {pk} || '|' || encode(sha256(convert_to((to_jsonb(t) - {sens})::text, 'UTF8')), 'hex') || '|' || "
            f"encode(sha256(convert_to({svals}, 'UTF8')), 'hex') || '|' || (to_jsonb(t) - {sens})::text FROM public.\"{t['nome']}\" t;")
    out = sql(bash, psql, '\n'.join(partes) + '\n')
    foto = {t['nome']: {} for t in tabs}
    for x in out.splitlines():
        if not x.startswith('R|'):
            continue
        _, tab, pk, d, s, j = x.split('|', 5)
        dados = json.loads(j)
        ref = any(isinstance(v, str) and v in ids_observados for v in dados.values())
        if pk in foto[tab]:
            raise jf.Stop(f'chave repetida na foto: {tab} {pk}')
        foto[tab][pk] = {'d': d, 's': s, **({'j': dados} if ref else {})}
    return {'tabelas': {t['nome']: {'pk': t['pk'], 'sens': t['sens']} for t in tabs}, 'linhas': foto}


def docker_json(args):
    r = subprocess.run(['docker'] + args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode != 0:
        raise jf.Stop(f'docker {" ".join(args[:2])} falhou: {r.stderr.decode("utf-8", "replace")[:200]}')
    return r.stdout.decode('utf-8', 'replace')


def foto_docker(docker=docker_json):
    def inspect(tipo, ids):
        return json.loads(docker([tipo, 'inspect'] + ids)) if ids else []
    cids = docker(['ps', '-aq', '--no-trunc']).split()
    vids = docker(['volume', 'ls', '-q']).split()
    nids = docker(['network', 'ls', '-q', '--no-trunc']).split()
    iids = sorted(set(docker(['image', 'ls', '-aq', '--no-trunc']).split()))
    cont = {}
    for c in inspect('container', cids):
        cont[c['Id']] = {
            'nome': c['Name'], 'imagem': c['Image'], 'criado': c['Created'], 'estado': c['State']['Status'],
            'iniciado': c['State']['StartedAt'], 'terminado': c['State']['FinishedAt'], 'reinicios': c.get('RestartCount'),
            'mounts': sorted([{'tipo': m['Type'], 'origem': m.get('Source', ''), 'destino': m['Destination'], 'rw': m['RW']} for m in c.get('Mounts', [])],
                             key=lambda m: m['destino']),
            'redes': {n: v.get('IPAddress') for n, v in (c.get('NetworkSettings', {}).get('Networks') or {}).items()},
            'rotulos': c['Config'].get('Labels') or {}, 'portas': c['HostConfig'].get('PortBindings') or {},
        }
    vol = {v['Name']: {'driver': v['Driver'], 'rotulos': v.get('Labels') or {}, 'criado': v.get('CreatedAt'), 'ponto': v.get('Mountpoint')}
           for v in inspect('volume', vids)}
    red = {n['Id']: {'nome': n['Name'], 'driver': n['Driver'], 'rotulos': n.get('Labels') or {}, 'criado': n.get('Created'),
                     'subredes': sorted(x.get('Subnet', '') for x in ((n.get('IPAM') or {}).get('Config') or []))}
           for n in inspect('network', nids)}
    img = {i['Id']: {'tags': sorted(i.get('RepoTags') or []), 'criado': i.get('Created')} for i in inspect('image', iids)}
    return {'containers': cont, 'volumes': vol, 'redes': red, 'imagens': img}


def foto(a, cofre_ou_none, bash, docker=docker_json):
    ids = set()
    base = le(os.path.join(a.segredos, 'frontend', 'semeadura.json'))
    adm = next(c for c in base['contas'] if c['chave'] == 'admin')
    ids |= {adm['user_id'], adm['membership_id']}
    f6a = le(os.path.join(a.segredos, 'frontend', 'f6a', 'semeadura.json'))
    if f6a:
        for c in f6a['contas']:
            ids |= {c['user_id'], c['membership_id']}
        ids |= {g['grant_id'] for g in f6a['concessoes']}
    dados = {'instante': agora(), 'banco': foto_banco(bash, a.psql, ids), 'docker': foto_docker(docker)}
    jf.grava_atomico(a.arquivo, dados)
    n = sum(len(v) for v in dados['banco']['linhas'].values())
    print(f'FOTO gravada: {len(dados["banco"]["linhas"])} tabelas, {n} linhas; {len(dados["docker"]["containers"])} containers, '
          f'{len(dados["docker"]["volumes"])} volumes, {len(dados["docker"]["redes"])} redes, {len(dados["docker"]["imagens"])} imagens')
    return 0


# ================================================================================================= comparar
# Mudancas PERMITIDAS em linhas da ATORA (a admin que navega: user_id ou membership_id dela, e nenhuma f6av-*), por
# tabela e operacao. Linhas que ja existiam na foto ANTES tem de ficar IDENTICAS, salvo o campo declarado aqui.
#   user_sessions    INSERT (sessoes do login da navegacao; esperadas: --logins-admin)
#   auth_challenges  INSERT (desafio de MFA de cada login; esperados: --logins-admin)
#   user_mfa_factors UPDATE so de last_used_step, so para MAIS, no fator ativo dela (TOTP do login e do step-up)
#   mfa_events       INSERT so de event_type 6 (step-up aceito), user_id = admin, sem ator (esperados: --stepups)
# Linhas de alvo (referem um id f6av-*): permitidas aqui e comprovadas uma a uma pelo "conferir" de cada fase.
# Qualquer outra linha (nem atora nem alvo): IDENTICA (sem INSERT, UPDATE ou DELETE).
# DONO da linha (R1): so campos de DONO decidem a identidade; ator e responsavel (actor_user_id, *_by_membership_id,
# requested_by_*, created_by_*) NUNCA. Tabelas de identidade explicitas; nas demais, os campos de dono presentes.
CAMPOS_DONO = ('user_id', 'membership_id', 'client_membership_id', 'grant_id')
DONO_POR_TABELA = {
    'users': ('user_id',), 'user_credentials': ('user_id',), 'user_sessions': ('user_id',), 'auth_challenges': ('user_id',),
    'user_mfa_factors': ('user_id',), 'user_mfa_recovery_codes': ('user_id',),
    'mfa_events': ('user_id',),                                    # actor_user_id = ATOR, nao dono
    'organization_memberships': ('user_id', 'membership_id'), 'client_memberships': ('user_id', 'client_membership_id'),
    'organization_role_assignments': ('membership_id',),           # assigned_by_membership_id = RESPONSAVEL
    'workspace_role_assignments': ('membership_id',),
    'client_workspace_grants': ('grant_id', 'client_membership_id'),   # approved_by/revoked_by = RESPONSAVEL
}


def dono(tab, j, ids_admin, ids_alvo):
    """Categoria da identidade DONA de UMA versao da linha: 'alvo' (todos os campos de dono sao de f6av-*), 'atora'
    (todos sao da admin) ou 'outra' (qualquer outro caso, inclusive linha sem campo de dono, dono misto ou versao sem
    JSON - a foto so guarda JSON de linha que refere uma identidade observada)."""
    if not j:
        return 'outra'
    cols = DONO_POR_TABELA.get(tab) or tuple(c for c in CAMPOS_DONO if c in j)
    vals = [j.get(c) for c in cols if j.get(c) is not None]
    if not vals:
        return 'outra'
    if all(v in ids_alvo for v in vals):
        return 'alvo'
    if all(v in ids_admin for v in vals):
        return 'atora'
    return 'outra'


REGRAS_ATOR = {
    'user_sessions': {'insert': True},
    'auth_challenges': {'insert': True},
    'user_mfa_factors': {'campos': {'last_used_step'}, 'so_aumenta': 'last_used_step'},
    'mfa_events': {'insert': True, 'insert_se': {'event_type': 6, 'actor_user_id': None}},
}


def compara_banco(A, D, ids_admin, ids_alvo, logins_admin, stepups, logins_alvos=None, contas_alvo=None):
    """logins_alvos (opcional): {chave: N} - sessoes e desafios NOVOS de cada alvo = N (os logins previstos no roteiro;
    contas_alvo: {user_id: chave}). Sem ele, as linhas de alvo so sao listadas (comprovadas fase a fase)."""
    viol, alvo, ator = [], [], []
    ins = {'user_sessions': 0, 'auth_challenges': 0, 'mfa_events': 0}
    ins_alvo = {}
    tabs = set(A['linhas']) | set(D['linhas'])
    if set(A['linhas']) != set(D['linhas']):
        viol.append(f'conjunto de tabelas mudou: {sorted(set(A["linhas"]) ^ set(D["linhas"]))}')
    for tab in sorted(tabs):
        a, d = A['linhas'].get(tab, {}), D['linhas'].get(tab, {})
        for pk in sorted(set(a) | set(d)):
            ra, rd = a.get(pk), d.get(pk)
            if ra and rd and ra['d'] == rd['d'] and ra['s'] == rd['s']:
                continue
            op = 'INSERT' if not ra else 'DELETE' if not rd else 'UPDATE'
            # dono conferido nos DOIS estados: passar a apontar para um alvo (ou para a atora) nao libera uma linha
            # que antes era protegida, e vice-versa
            ca = dono(tab, ra.get('j'), ids_admin, ids_alvo) if ra else None
            cd = dono(tab, rd.get('j'), ids_admin, ids_alvo) if rd else None
            if op == 'UPDATE' and ca != cd:
                viol.append(f'{tab} UPDATE {pk} trocou de dono ({ca} -> {cd})')
                continue
            cat = cd if op == 'INSERT' else ca
            if cat == 'alvo':
                alvo.append(f'{tab} {op} {pk}')
                if op == 'INSERT' and tab in ('user_sessions', 'auth_challenges') and contas_alvo:
                    kk = (tab, contas_alvo.get(rd['j'].get('user_id'), '?'))
                    ins_alvo[kk] = ins_alvo.get(kk, 0) + 1
                continue
            if cat == 'outra':
                viol.append(f'{tab} {op} {pk} (linha de outra identidade)')
                continue
            r = REGRAS_ATOR.get(tab)
            if op == 'INSERT' and r and r.get('insert'):
                cond = r.get('insert_se', {})
                if all(rd['j'].get(k) == v for k, v in cond.items()):
                    ins[tab] = ins.get(tab, 0) + 1
                    ator.append(f'{tab} INSERT {pk}')
                    continue
                viol.append(f'{tab} INSERT {pk} da atora fora da regra ({ {k: rd["j"].get(k) for k in cond} })')
                continue
            if op == 'UPDATE' and r and r.get('campos') and ra['s'] == rd['s'] and ra.get('j') and rd.get('j'):
                mudou = {k for k in set(ra['j']) | set(rd['j']) if ra['j'].get(k) != rd['j'].get(k)}
                c = r.get('so_aumenta')
                if mudou and mudou <= r['campos'] and (not c or (rd['j'].get(c) or -1) > (ra['j'].get(c) or -1)):
                    ator.append(f'{tab} UPDATE {pk} ({",".join(sorted(mudou))})')
                    continue
                viol.append(f'{tab} UPDATE {pk} da atora fora da regra (campos {sorted(mudou)})')
                continue
            viol.append(f'{tab} {op} {pk} da atora fora das regras')
    # contagens declaradas
    if ins['user_sessions'] != logins_admin:
        viol.append(f'sessoes novas da atora: {ins["user_sessions"]} (esperado {logins_admin})')
    if ins['auth_challenges'] != logins_admin:
        viol.append(f'desafios novos da atora: {ins["auth_challenges"]} (esperado {logins_admin})')
    if ins['mfa_events'] != stepups:
        viol.append(f'eventos de step-up da atora: {ins["mfa_events"]} (esperado {stepups})')
    if logins_alvos is not None:
        for tab in ('user_sessions', 'auth_challenges'):
            for k in sorted(set(logins_alvos) | {c for (tb, c) in ins_alvo if tb == tab}):
                n = ins_alvo.get((tab, k), 0)
                if n != logins_alvos.get(k, 0):
                    viol.append(f'{tab} novas do alvo {k}: {n} (esperado {logins_alvos.get(k, 0)}, logins previstos)')
    # sessoes novas da atora terminam encerradas (logout)
    for pk, rd in D['linhas'].get('user_sessions', {}).items():
        if pk not in A['linhas'].get('user_sessions', {}) and rd.get('j') and rd['j'].get('user_id') in ids_admin and rd['j'].get('revoked_at') is None:
            viol.append(f'sessao nova da atora ainda ativa no fim: {pk}')
    return viol, alvo, ator


def _plana(o, prefixo=''):
    if isinstance(o, dict):
        r = {}
        for k, v in o.items():
            r.update(_plana(v, f'{prefixo}{k}.'))
        return r
    return {prefixo[:-1]: o}


# Rotulos que o PROPRIO Compose grava ao recriar o gateway com "up -d --no-deps --force-recreate gateway" (F6a-V-E, STOP
# da R8): aceitos SO no gateway substituido IDENTIFICADO (--gateway-ids ANTIGO:NOVO) e SO com estes valores exatos.
ROTULOS_RECRIACAO = {'rotulos.com.docker.compose.depends_on': '', 'rotulos.com.docker.compose.replace': 'gateway-1'}
# Identificacao do par: ids de container do Docker (hexadecimal minusculo), cada um com 12 a 64 caracteres, distintos e
# sem um ser prefixo do outro. Vazio, curto, malformado ou incompleto = identificacao INVALIDA (a excecao nao se aplica).
ID_GATEWAY = re.compile(r'[0-9a-f]{12,64}')


def ids_gateway_invalidos(ids):
    """None se (antigo, novo) identifica o par; senao, o motivo."""
    if not isinstance(ids, (tuple, list)) or len(ids) != 2:
        return f'esperado (antigo, novo), obtido {ids!r}'
    ant, nov = ids
    for nome, v in (('antigo', ant), ('novo', nov)):
        if not isinstance(v, str) or not ID_GATEWAY.fullmatch(v):
            return f'id {nome} {v!r} nao e hexadecimal minusculo com 12 a 64 caracteres'
    if ant.startswith(nov) or nov.startswith(ant):
        return f'ids antigo e novo iguais ou um prefixo do outro ({ant!r}, {nov!r})'
    return None


def compara_docker(A, D, gateway_novo, gateway_ids=None):
    """gateway_novo: None = nenhuma substituicao esperada; senao, o nome da pasta montada em /spa no gateway novo.
    gateway_ids: (prefixo do id antigo, prefixo do id novo) da substituicao desta implantacao, cada um com 12 a 64
    caracteres hexadecimais (ids_gateway_invalidos); so com ele VALIDO, e so nesse par, os ROTULOS_RECRIACAO (valores
    exatos; o replace ausente antes) deixam de ser violacao."""
    viol, notas = [], []
    for tipo in ('volumes', 'redes', 'imagens'):
        a, d = A[tipo], D[tipo]
        for k in sorted(set(a) | set(d)):
            if a.get(k) != d.get(k):
                viol.append(f'{tipo} {k[:24]}: ' + ('removido' if k not in d else 'criado' if k not in a else 'alterado'))
    ca, cd = A['containers'], D['containers']
    for k in sorted(set(ca) & set(cd)):
        if ca[k] != cd[k]:
            dif = sorted(x for x in set(_plana(ca[k])) | set(_plana(cd[k])) if _plana(ca[k]).get(x) != _plana(cd[k]).get(x))
            viol.append(f'container {ca[k]["nome"]} alterado: {dif}')
    saiu = [k for k in ca if k not in cd]
    entrou = [k for k in cd if k not in ca]
    if gateway_novo is None:
        for k in saiu:
            viol.append(f'container {ca[k]["nome"]} removido')
        for k in entrou:
            viol.append(f'container {cd[k]["nome"]} criado')
        return viol, notas
    velhos = [k for k in saiu if ca[k]['nome'] == GATEWAY]
    novos = [k for k in entrou if cd[k]['nome'] == GATEWAY]
    for k in saiu:
        if k not in velhos:
            viol.append(f'container {ca[k]["nome"]} removido')
    for k in entrou:
        if k not in novos:
            viol.append(f'container {cd[k]["nome"]} criado')
    if len(velhos) != 1 or len(novos) != 1:
        viol.append(f'substituicao do gateway: {len(velhos)} saiu e {len(novos)} entrou (esperado 1 e 1)')
        return viol, notas
    v, n = ca[velhos[0]], cd[novos[0]]
    fv, fn = _plana({k: x for k, x in v.items() if k != 'mounts'}), _plana({k: x for k, x in n.items() if k != 'mounts'})
    dif = {x for x in set(fv) | set(fn) if fv.get(x) != fn.get(x)}
    permitido = {'criado', 'iniciado', 'terminado', 'rotulos.com.docker.compose.config-hash'}
    erro = ids_gateway_invalidos(gateway_ids) if gateway_ids is not None else None
    if erro:
        viol.append(f'identificacao do gateway invalida: {erro} (a excecao dos rotulos de recriacao nao se aplica)')
    elif gateway_ids is not None:
        ant, nov = gateway_ids
        if not (velhos[0].startswith(ant) and novos[0].startswith(nov)):
            viol.append(f'substituicao do gateway nao e a identificada ({velhos[0][:12]} -> {novos[0][:12]}; esperado {ant} -> {nov})')
        else:
            for rot, valor in ROTULOS_RECRIACAO.items():
                antes_ok = rot != 'rotulos.com.docker.compose.replace' or rot not in fv
                if rot in dif and fn.get(rot) == valor and antes_ok:
                    permitido = permitido | {rot}
                    notas.append(f'gateway: rotulo de recriacao do Compose {rot.split(".", 1)[1]}={valor!r} (valor exato esperado)')
    if dif - permitido:
        viol.append(f'gateway novo difere do anterior alem do permitido: {sorted(dif - permitido)}')
    if n['estado'] != 'running' or v['imagem'] != n['imagem']:
        viol.append(f'gateway novo: estado {n["estado"]}, imagem {"igual" if v["imagem"] == n["imagem"] else "DIFERENTE"}')
    mv = {m['destino']: m for m in v['mounts']}
    mn = {m['destino']: m for m in n['mounts']}
    if set(mv) != set(mn):
        viol.append(f'gateway: destinos de mount diferentes {sorted(set(mv) ^ set(mn))}')
    for dst in sorted(set(mv) & set(mn)):
        if dst == '/spa':
            o, w = mv[dst]['origem'].replace('\\', '/'), mn[dst]['origem'].replace('\\', '/')
            ok = (w.rsplit('/', 1)[0] == o.rsplit('/', 1)[0] and w.rsplit('/', 1)[-1] == gateway_novo
                  and mn[dst]['rw'] is False and mn[dst]['tipo'] == mv[dst]['tipo'] == 'bind')
            if not ok:
                viol.append(f'gateway: /spa novo inesperado ({w.rsplit("/", 1)[-1]}, rw={mn[dst]["rw"]})')
            else:
                notas.append(f'gateway: /spa {o.rsplit("/", 1)[-1]} -> {w.rsplit("/", 1)[-1]} (somente leitura); imagem e redes iguais')
        elif mv[dst] != mn[dst]:
            viol.append(f'gateway: mount {dst} mudou')
    notas.append(f'gateway SUBSTITUIDO como esperado: {velhos[0][:12]} -> {novos[0][:12]}')
    return viol, notas


def comparar(a):
    A, D = le(a.antes), le(a.depois)
    seg = a.segredos
    base = le(os.path.join(seg, 'frontend', 'semeadura.json'))
    adm = next(c for c in base['contas'] if c['chave'] == 'admin')
    ids_admin = {adm['user_id'], adm['membership_id']}
    f6a = le(os.path.join(seg, 'frontend', 'f6a', 'semeadura.json'))
    ids_alvo = set()
    if f6a:
        for c in f6a['contas']:
            ids_alvo |= {c['user_id'], c['membership_id']}
        ids_alvo |= {g['grant_id'] for g in f6a['concessoes']}
    la = None
    if a.logins_alvos:
        la = {k: int(v) for k, v in (x.split('=') for x in a.logins_alvos.split(','))}
    contas = {c['user_id']: c['chave'] for c in (f6a or {}).get('contas', [])}
    vb, alvo, ator = compara_banco(A['banco'], D['banco'], ids_admin, ids_alvo, a.logins_admin, a.stepups, la, contas)
    gids = None
    if a.gateway_ids is not None:
        gids = tuple(a.gateway_ids.split(':'))
        erro = ids_gateway_invalidos(gids)
        if erro:
            print(f'RECUSADO: --gateway-ids ANTIGO:NOVO invalido: {erro}')
            return 2
    vd, notas = compara_docker(A['docker'], D['docker'], a.gateway_novo, gids)
    rel = os.path.join(a.evid, 'comparacao.txt')
    os.makedirs(a.evid, exist_ok=True)
    with open(rel, 'w', encoding='utf-8') as h:
        def out(x):
            print(x)
            h.write(x + '\n')
        out(f'== comparacao {A["instante"]} -> {D["instante"]} (logins da atora {a.logins_admin}, step-ups {a.stepups}, '
            f'logins dos alvos {a.logins_alvos or "nao declarados"}, gateway novo {a.gateway_novo or "nenhum"})')
        for x in ator:
            out(f'PERMITIDA (atora) {x}')
        for x in alvo:
            out(f'ALVO {x}')
        for x in notas:
            out(f'DOCKER {x}')
        for x in vb + vd:
            out(f'VIOLACAO {x}')
        out(f'RESULTADO: {"PRESERVADO" if not (vb or vd) else f"{len(vb) + len(vd)} VIOLACAO(OES)"}; '
            f'{len(ator)} mudancas permitidas da atora; {len(alvo)} mudancas de alvos')
    return 0 if not (vb or vd) else 1


# ================================================================================================ intencoes
def intencoes(a):
    rc = subprocess.run([sf.bash_exe(a.bash), '-c', a.logs_cmd], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if rc.returncode != 0:
        print(f'STOP: coleta dos logs da Platform falhou (codigo {rc.returncode})')
        return 1
    cont = {}
    for x in rc.stdout.decode('utf-8', 'replace').splitlines():
        m = re.search(r'evento=mail\.intencao tipo=([A-Z_]+) .*entrega=nenhuma\(local\)', x)
        if m:
            cont[m.group(1)] = cont.get(m.group(1), 0) + 1
    print(f'INTENCOES de aviso registradas desde {a.desde} (transporte LOCAL, entrega=nenhuma; nao sao e-mails entregues): '
          + (', '.join(f'{k}={v}' for k, v in sorted(cont.items())) or 'nenhuma'))
    falta = {k: v for k, v in (x.split('=') for x in (a.esperado or [])) if cont.get(k, 0) != int(v)}
    extras = {k: v for k, v in cont.items() if a.esperado is not None and k not in dict(x.split('=') for x in a.esperado)}
    if a.esperado is not None and (falta or extras):
        print(f'DIVERGENTE do esperado {a.esperado}: {cont}')
        return 1
    return 0


# ====================================================================================================== main
def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('acao', choices=['preparar', 'conferir', 'anti-replay', 'foto', 'comparar', 'intencoes'])
    ap.add_argument('args', nargs='*')
    ap.add_argument('--segredos', required=True)
    ap.add_argument('--evid')
    ap.add_argument('--openapi-json')
    ap.add_argument('--openapi-yaml')
    ap.add_argument('--alvo')
    ap.add_argument('--ca')
    ap.add_argument('--teste-http')
    ap.add_argument('--origem')
    ap.add_argument('--psql')
    ap.add_argument('--bash')
    ap.add_argument('--ritmo', type=float, default=5.0, help='segundos minimos entre logins (limitador R7)')
    ap.add_argument('--motivo', default='DISPOSITIVO_PERDIDO', choices=sorted(MOTIVO_CODIGO))
    ap.add_argument('--logins-admin', type=int, default=1)
    ap.add_argument('--stepups', type=int, default=1)
    ap.add_argument('--gateway-novo', help='pasta montada em /spa no gateway novo (ex.: frontend-dist-7851e20)')
    ap.add_argument('--gateway-ids', help='ANTIGO:NOVO (ids hexadecimais com 12 a 64 caracteres) da substituicao do gateway desta implantacao')
    ap.add_argument('--logins-alvos', help='logins previstos por alvo: chave=N,... (sessoes e desafios novos de cada alvo)')
    ap.add_argument('--logs-gateway-cmd', help='comando dos logs do gateway ({desde} = instante); padrao: compose logs')
    ap.add_argument('--desde')
    ap.add_argument('--logs-cmd')
    ap.add_argument('--esperado', nargs='*')
    a = ap.parse_args(argv)
    bash = sf.bash_exe(a.bash) if a.acao != 'comparar' else None

    if a.acao == 'comparar':
        if len(a.args) != 2 or not a.evid:
            print('RECUSADO: comparar <antes> <depois> --evid DIR')
            return 2
        a.antes, a.depois = a.args
        return comparar(a)
    if a.acao == 'intencoes':
        if not (a.desde and a.logs_cmd):
            print('RECUSADO: intencoes --desde <instante> --logs-cmd <comando>')
            return 2
        return intencoes(a)
    if a.acao == 'foto':
        if len(a.args) != 1 or not a.psql:
            print('RECUSADO: foto <arquivo> --psql CMD')
            return 2
        a.arquivo = a.args[0]
        try:
            return foto(a, None, bash)
        except jf.Stop as e:
            print(f'STOP: {e}')
            return 1
    cofre = Cofre(a.segredos)
    if a.acao == 'anti-replay':
        if a.args or not a.psql:
            print('RECUSADO: anti-replay --psql CMD')
            return 2
        try:
            return anti_replay(a, cofre, bash)
        except jf.Stop as e:
            print(f'STOP: {e}')
            return 1
    # preparar / conferir: falam com a API
    if a.acao == 'conferir' and (len(a.args) != 1 or a.args[0] not in FASES):
        print(f'RECUSADO: conferir <fase> com fase em {",".join(FASES)}')
        return 2
    if a.acao == 'preparar' and a.args:
        print('RECUSADO: preparar nao recebe argumentos')
        return 2
    for x in ('evid', 'openapi_json', 'openapi_yaml', 'psql'):
        if not getattr(a, x):
            print(f'RECUSADO: falta --{x.replace("_", "-")}')
            return 2
    spec = json.load(open(a.openapi_json, encoding='utf-8'))
    sha = hashlib.sha256(open(a.openapi_yaml, 'rb').read()).hexdigest()
    if spec.get('x-origem-yaml', {}).get('sha256') != sha:
        print(f'RECUSADO: OpenAPI JSON nao corresponde ao YAML ({sha})')
        return 2
    v = jf.Validador(spec)
    nome = a.acao if a.acao == 'preparar' else f'conferir-{a.args[0]}'
    rel = Rel(a.evid, nome)
    api = Api(a, cofre, rel, v)
    rel.escreve(f'== {nome} {agora()} alvo={a.alvo or "teste-http"} openapi_yaml_sha256={sha}')
    rc = 0
    try:
        if a.acao == 'preparar':
            preparar(a, api, cofre, rel)
        else:
            conferir(a, a.args[0], api, cofre, rel, bash)
        rel.escreve(f'RESULTADO: {rel.passos} verificacoes OK; {v.respostas} respostas validadas contra o OpenAPI')
    except jf.Stop as e:
        rel.escreve(f'STOP no primeiro resultado inesperado: {e}')
        rc = 1
    # resumo no formato que lib/selecao_gateway.sh confere (respostas_validadas = requisicoes desta execucao)
    with open(os.path.join(a.evid, 'resumo_executar.json'), 'w', encoding='utf-8') as h:
        json.dump({'acao': nome, 'codigo': rc, 'verificacoes_ok': rel.passos, 'respostas_validadas': v.respostas}, h, ensure_ascii=False, indent=1)
    return rc


if __name__ == '__main__':
    sys.exit(main())
