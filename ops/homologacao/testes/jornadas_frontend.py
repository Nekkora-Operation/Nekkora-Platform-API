"""Jornadas HTTP do ensaio-frontend (F1.5, P-A): referencia viva do contrato para a aplicacao web, por papel.

Fala com a API como o navegador fala: CSRF de pre-autenticacao, login, desafio de MFA com TOTP calculado do segredo
cadastrado (RFC 6238, biblioteca padrao), sessao por cookie __Host-, Origin e X-CSRF-Token nas escritas. Cada resposta e
validada contra o OpenAPI (operacao + status + esquema; validador declarado em Validador.SUPORTADAS). Para no PRIMEIRO
resultado inesperado (STOP): nada e corrigido nem repetido dentro da execucao.

Subcomandos:
  preparar-contas  cadastra o MFA (pela API) das contas com mfa=True do plano, guardando segredo TOTP e codigos de
                   recuperacao em <segredos>/frontend/contas/<chave>.json (gravacao atomica; segredo pendente gravado
                   ANTES da confirmacao, para nunca haver fator confirmado sem segredo recuperavel)
  executar         jornadas de leitura (contas j-*) e, com --mutacoes, as jornadas que mudam estado (contas exclusivas)
  executar --somente <lista> --vinculo-stop <texto>   (P-B-C1) SO as jornadas nomeadas, nada mais: continuacao de um
                   STOP. Nomes aceitos (JORNADAS_SELECIONAVEIS): sessoes-pos-revogacao (M.26: novo login de sessoes@
                   depois do revoke-all, /me, logout; SEM revoke-all) e expiracao-absoluta (M.30-M.32 com a O5). Nome
                   desconhecido, lista vazia ou repetida, falta do --vinculo-stop, combinacao com --mutacoes ou
                   --inatividade-real, preparar-contas com --somente, ou expiracao-absoluta sem --psql/--sql-sessao:
                   RECUSA (codigo 2) antes de qualquer requisicao ou arquivo - nunca cai na suite completa.
O5 (P-B-C1): cada execucao da operacao declarada de sessao e marcada no livro de consumo ANTES de chamar o banco; com
O5_MAXIMO execucoes ja marcadas, a jornada para (STOP) sem chamar o banco.

Alvo:
  --alvo https://<host>:<porta> --ca <ca.crt>     gateway do ensaio: TLS conferido pela CA e pelo NOME (SNI), conexao
                                                   em 127.0.0.1; Origin = o proprio alvo
  --teste-http http://127.0.0.1:<porta> --origem <origem publica>   SO testes automatizados contra a Platform em processo
                                                   (sem gateway, sem TLS): recusado para host que nao seja de loopback
Nunca grava corpo, cookie, CSRF, cursor, senha, segredo TOTP ou codigo de recuperacao na evidencia: so status, ids
sinteticos, contagens e o resultado da validacao.
"""
import argparse
import base64
import collections
import datetime
import hashlib
import hmac
import http.client
import json
import os
import random
import re
import secrets
import socket
import ssl
import struct
import subprocess
import sys
import time
import urllib.parse

# =================================================================================================== validador
UUID_RE = re.compile(r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')
DT_RE = re.compile(r'^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(\.\d+)?([Zz]|[+-]\d{2}:\d{2})$')


def data_hora_valida(s):
    """RFC 3339 date-time com PARSING REAL: data e hora existentes (calendario), fuso obrigatorio. Segundo 60 so em
    23:59:60 UTC (regra do ajv-formats, a mesma do cliente web); qualquer outra data inexistente reprova."""
    m = DT_RE.match(s)
    if not m:
        return False
    ano, mes, dia, h, mi, se = (int(m.group(i)) for i in range(1, 7))
    try:
        datetime.date(ano, mes, dia)
    except ValueError:
        return False
    fuso = m.group(8)
    off = 0 if fuso in ('Z', 'z') else (1 if fuso[0] == '+' else -1) * (int(fuso[1:3]) * 60 + int(fuso[4:6]))
    if fuso not in ('Z', 'z') and (int(fuso[1:3]) > 23 or int(fuso[4:6]) > 59):
        return False
    if h > 23 or mi > 59 or se > 60:
        return False
    if se == 60:
        utc = (h * 60 + mi - off) % 1440
        return utc == 23 * 60 + 59
    return True


class Validador:
    # palavras-chave de RESTRICAO verificadas (escopo declarado; o relatorio lista as nao verificadas encontradas)
    SUPORTADAS = {'$ref', 'type', 'required', 'properties', 'additionalProperties', 'enum', 'const', 'items', 'oneOf',
                  'anyOf', 'allOf', 'not', 'minLength', 'maxLength', 'minimum', 'maximum', 'exclusiveMinimum',
                  'exclusiveMaximum', 'minItems', 'maxItems', 'uniqueItems', 'pattern', 'format'}
    ANOTACOES = {'description', 'title', 'example', 'examples', 'deprecated', 'readOnly', 'writeOnly', 'default', 'nullable'}
    FORMATOS = {'uuid', 'date-time'}

    def __init__(self, spec):
        self.spec = spec
        self.nao_verificadas = collections.Counter()
        self.formatos_nao_verificados = collections.Counter()
        self.respostas = 0
        self.palavras = collections.Counter()
        self._ops = []
        for caminho, item in spec['paths'].items():
            rx = re.compile('^' + re.sub(r'\\\{[^/]+?\\\}', '[^/]+', re.escape(caminho)) + '$')
            for metodo, op in item.items():
                if metodo in ('get', 'post', 'put', 'delete', 'patch'):
                    self._ops.append((metodo.upper(), caminho, rx, op))

    def ref(self, r):
        assert r.startswith('#/'), r
        no = self.spec
        for parte in r[2:].split('/'):
            no = no[parte.replace('~1', '/').replace('~0', '~')]
        return no

    def operacao(self, metodo, caminho):
        c = [(t, op) for m, t, rx, op in self._ops if m == metodo and rx.match(caminho)]
        # caminho literal vence o com parametro (ex.: /me/permissions x /me/{x})
        c.sort(key=lambda x: x[0].count('{'))
        return c[0] if c else (None, None)

    def valida(self, esq, v, onde='$'):
        erros = []
        if '$ref' in esq:
            self.palavras['$ref'] += 1
            return self.valida(self.ref(esq['$ref']), v, onde)
        for k in esq:
            if k in self.SUPORTADAS:
                self.palavras[k] += 1
            elif k not in self.ANOTACOES and not k.startswith('x-'):
                self.nao_verificadas[k] += 1
        t = esq.get('type')
        if t is not None:
            tipos = t if isinstance(t, list) else [t]
            if not any(self._tipo(x, v) for x in tipos):
                return [f'{onde}: tipo {type(v).__name__} fora de {tipos}']
        if 'const' in esq and v != esq['const']:
            erros.append(f'{onde}: diferente de const')
        if 'enum' in esq and v not in esq['enum']:
            erros.append(f'{onde}: valor fora do enum')
        if isinstance(v, str):
            if 'minLength' in esq and len(v) < esq['minLength']:
                erros.append(f'{onde}: menor que minLength')
            if 'maxLength' in esq and len(v) > esq['maxLength']:
                erros.append(f'{onde}: maior que maxLength')
            if 'pattern' in esq and not re.search(esq['pattern'], v):
                erros.append(f'{onde}: fora do pattern')
            f = esq.get('format')
            if f is not None:
                if f == 'uuid' and not UUID_RE.match(v):
                    erros.append(f'{onde}: uuid invalido')
                elif f == 'date-time' and not data_hora_valida(v):
                    erros.append(f'{onde}: date-time invalido ou inexistente')
                elif f not in self.FORMATOS:
                    self.formatos_nao_verificados[f] += 1
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            if 'minimum' in esq and v < esq['minimum']:
                erros.append(f'{onde}: menor que minimum')
            if 'maximum' in esq and v > esq['maximum']:
                erros.append(f'{onde}: maior que maximum')
            if 'exclusiveMinimum' in esq and v <= esq['exclusiveMinimum']:
                erros.append(f'{onde}: nao maior que exclusiveMinimum')
            if 'exclusiveMaximum' in esq and v >= esq['exclusiveMaximum']:
                erros.append(f'{onde}: nao menor que exclusiveMaximum')
        if isinstance(v, list):
            if 'minItems' in esq and len(v) < esq['minItems']:
                erros.append(f'{onde}: menos itens que minItems')
            if 'maxItems' in esq and len(v) > esq['maxItems']:
                erros.append(f'{onde}: mais itens que maxItems')
            if esq.get('uniqueItems') and len({json.dumps(x, sort_keys=True) for x in v}) != len(v):
                erros.append(f'{onde}: itens repetidos (uniqueItems)')
            if 'items' in esq:
                for i, x in enumerate(v):
                    erros += self.valida(esq['items'], x, f'{onde}[{i}]')
        if isinstance(v, dict):
            for r in esq.get('required', []):
                if r not in v:
                    erros.append(f'{onde}: falta o campo obrigatorio {r}')
            props = esq.get('properties', {})
            for k, x in v.items():
                if k in props:
                    erros += self.valida(props[k], x, f'{onde}.{k}')
                elif esq.get('additionalProperties') is False:
                    erros.append(f'{onde}: campo nao publicado {k}')
                elif isinstance(esq.get('additionalProperties'), dict):
                    erros += self.valida(esq['additionalProperties'], x, f'{onde}.{k}')
        for x in esq.get('allOf', []):
            erros += self.valida(x, v, onde)
        if 'anyOf' in esq and not any(not self.valida(x, v, onde) for x in esq['anyOf']):
            erros.append(f'{onde}: nenhum ramo de anyOf')
        if 'oneOf' in esq:
            n = sum(1 for x in esq['oneOf'] if not self.valida(x, v, onde))
            if n != 1:
                erros.append(f'{onde}: {n} ramos de oneOf (esperado 1)')
        if 'not' in esq and not self.valida(esq['not'], v, onde):
            erros.append(f'{onde}: casou com not')
        return erros

    @staticmethod
    def _tipo(t, v):
        return {'string': isinstance(v, str), 'integer': isinstance(v, int) and not isinstance(v, bool),
                'number': isinstance(v, (int, float)) and not isinstance(v, bool), 'boolean': isinstance(v, bool),
                'array': isinstance(v, list), 'object': isinstance(v, dict), 'null': v is None}.get(t, False)

    def resposta(self, metodo, caminho, status, texto):
        """Lista de erros da resposta contra o contrato (vazia = conforme)."""
        self.respostas += 1
        tpl, op = self.operacao(metodo, caminho.split('?')[0])
        if op is None:
            return [f'operacao nao publicada: {metodo} {caminho.split("?")[0]}']
        resp = op.get('responses', {}).get(str(status)) or op.get('responses', {}).get('default')
        if resp is None:
            return [f'status {status} nao documentado em {metodo} {tpl}']
        if '$ref' in resp:
            resp = self.ref(resp['$ref'])
        conteudo = resp.get('content')
        if not conteudo:
            return [] if not texto.strip() else [f'{metodo} {tpl} {status}: corpo presente onde o contrato nao declara']
        esq = conteudo.get('application/json', {}).get('schema')
        if esq is None:
            return [f'{metodo} {tpl} {status}: sem esquema application/json']
        try:
            corpo = json.loads(texto)
        except ValueError:
            return [f'{metodo} {tpl} {status}: corpo nao e JSON']
        return [f'{metodo} {tpl} {status} {e}' for e in self.valida(esq, corpo)]

    def restricoes_do_contrato_nao_verificadas(self):
        """Palavras-chave de restricao presentes nos esquemas do CONTRATO (components.schemas) que este validador nao
        verifica: o relatorio as declara, para conferir alguns campos nao ser apresentado como validacao completa."""
        achadas = collections.Counter()
        for esq in self.spec.get('components', {}).get('schemas', {}).values():
            self._palavras_esquema(esq, achadas)
        return dict(achadas)

    def _palavras_esquema(self, esq, achadas):
        if isinstance(esq, dict):
            for k, v in esq.items():
                if k not in self.SUPORTADAS and k not in self.ANOTACOES and not k.startswith('x-'):
                    achadas[k] += 1
                if k == 'properties' and isinstance(v, dict):
                    for p in v.values():
                        self._palavras_esquema(p, achadas)
                elif k in ('items', 'not', 'additionalProperties'):
                    self._palavras_esquema(v, achadas)
                elif k in ('oneOf', 'anyOf', 'allOf'):
                    for p in v:
                        self._palavras_esquema(p, achadas)


# ============================================================================================== cliente HTTP
class Alvo:
    def __init__(self, a):
        if a.teste_http:
            u = urllib.parse.urlparse(a.teste_http)
            if u.scheme != 'http' or u.hostname not in ('127.0.0.1', 'localhost', '::1'):
                raise SystemExit('--teste-http so para a Platform em processo, em loopback')
            self.http, self.host, self.porta, self.origem = True, u.hostname, u.port, a.origem
            self.ctx = None
        else:
            u = urllib.parse.urlparse(a.alvo)
            if u.scheme != 'https' or not a.ca:
                raise SystemExit('--alvo https://<host>:<porta> com --ca <ca.crt>')
            self.http, self.host, self.porta, self.origem = False, u.hostname, u.port, f'https://{u.hostname}:{u.port}'
            self.ctx = ssl.create_default_context(cafile=a.ca)
            self.ctx.minimum_version = ssl.TLSVersion.TLSv1_2

    def conexao(self):
        if self.http:
            return http.client.HTTPConnection(self.host, self.porta, timeout=60)
        alvo = self

        class C(http.client.HTTPSConnection):
            def connect(self):
                sock = socket.create_connection(('127.0.0.1', alvo.porta), timeout=60)
                self.sock = alvo.ctx.wrap_socket(sock, server_hostname=alvo.host)
        return C(self.host, self.porta, context=self.ctx, timeout=60)


class Sessao:
    """Um 'navegador': cookies, CSRF do contexto, Origin. Em --teste-http, um IP proprio (proxy confiavel da Platform
    em processo) para os limites por IP nao misturarem sessoes."""

    def __init__(self, alvo, validador, rel):
        self.alvo, self.v, self.rel = alvo, validador, rel
        self.cookies, self.csrf = {}, None
        self.origem = alvo.origem
        self.ip = f'10.{random.randint(1, 250)}.{random.randint(1, 250)}.{random.randint(1, 250)}' if alvo.http else None

    def req(self, metodo, caminho, corpo=None, csrf=True, valida=True):
        c = self.alvo.conexao()
        cab = {'Host': f'{self.alvo.host}:{self.alvo.porta}', 'Accept': 'application/json'}
        if self.cookies:
            cab['Cookie'] = '; '.join(f'{k}={v}' for k, v in self.cookies.items())
        if self.origem:
            cab['Origin'] = self.origem
        if csrf and self.csrf:
            cab['X-CSRF-Token'] = self.csrf
        if self.ip:
            cab['X-Forwarded-For'] = self.ip
        dados = None
        if corpo is not None:
            dados = json.dumps(corpo).encode()
            cab['Content-Type'] = 'application/json'
        elif metodo == 'POST':
            dados = b''
        c.request(metodo, caminho, body=dados, headers=cab)
        r = c.getresponse()
        texto = r.read().decode('utf-8', 'replace')
        setc = r.headers.get_all('Set-Cookie') or []
        for sc in setc:
            nome, valor = sc.split(';', 1)[0].split('=', 1)
            if 'Max-Age=0' in sc:
                self.cookies.pop(nome.strip(), None)
            else:
                self.cookies[nome.strip()] = valor.strip()
        c.close()
        try:
            js = json.loads(texto) if texto.strip() else None
        except ValueError:
            js = None
        erros = self.v.resposta(metodo, caminho, r.status, texto) if valida else []
        self.rel.contrato(metodo, caminho, r.status, erros)
        return r.status, js, setc


# ============================================================================================ segredos e TOTP
def grava_atomico(caminho, dados):
    os.makedirs(os.path.dirname(caminho), mode=0o700, exist_ok=True)
    tmp = f'{caminho}.tmp-{os.getpid()}-{secrets.token_hex(4)}'
    with open(tmp, 'x', encoding='utf-8') as h:
        h.write(json.dumps(dados, ensure_ascii=False, indent=1))
        h.flush()
        os.fsync(h.fileno())
    os.replace(tmp, caminho)


def totp(segredo_b32, passo):
    chave = base64.b32decode(segredo_b32 + '=' * (-len(segredo_b32) % 8))
    d = hmac.new(chave, struct.pack('>Q', passo), hashlib.sha1).digest()
    o = d[-1] & 0x0F
    return f'{(struct.unpack(">I", d[o:o + 4])[0] & 0x7FFFFFFF) % 1000000:06d}'


class Cofre:
    """<segredos>/frontend: plano final (semeadura.json), contas/<chave>.json (MFA) e consumo.json (contas exclusivas)."""

    def __init__(self, segredos):
        self.base = os.path.join(segredos, 'frontend')
        p = os.path.join(self.base, 'semeadura.json')
        if not os.path.isfile(p):
            raise SystemExit('semeadura do frontend nao concluida (sem frontend/semeadura.json): rode semear-frontend')
        self.plano = json.load(open(p, encoding='utf-8'))
        self.contas = {c['chave']: c for c in self.plano['contas']}

    def arq_conta(self, chave):
        return os.path.join(self.base, 'contas', f'{chave}.json')

    def mfa(self, chave):
        p = self.arq_conta(chave)
        return json.load(open(p, encoding='utf-8')) if os.path.isfile(p) else None

    def codigo(self, chave):
        """Proximo codigo TOTP de um passo NUNCA usado pela conta (anti-replay da 014); espera o passo seguinte se preciso.
        O ultimo passo usado e gravado ANTES de devolver o codigo (outra execucao nunca o reusa)."""
        m = self.mfa(chave)
        while True:
            agora = int(time.time())
            passo = agora // 30
            cand = max(passo - 1 if agora % 30 < 25 else passo, m.get('ultimo_passo', -1) + 1)
            if cand <= passo + 1 and cand >= passo - 1:
                m['ultimo_passo'] = cand
                grava_atomico(self.arq_conta(chave), m)
                return totp(m['totp'], cand)
            time.sleep(1)

    def consumo(self):
        p = os.path.join(self.base, 'consumo.json')
        return json.load(open(p, encoding='utf-8')) if os.path.isfile(p) else {'primeiro-acesso': {}, 'recuperacao': {}}

    def o5_usadas(self):
        return len(self.consumo().get('o5', []))

    def marca_o5(self, registro):
        c = self.consumo()
        c.setdefault('o5', []).append(registro)
        grava_atomico(os.path.join(self.base, 'consumo.json'), c)
        return len(c['o5'])

    def atualiza_o5(self, indice, campos):
        c = self.consumo()
        c['o5'][indice - 1].update(campos)
        grava_atomico(os.path.join(self.base, 'consumo.json'), c)

    def marca(self, tipo, chave, valor):
        c = self.consumo()
        c.setdefault(tipo, {})[chave] = valor
        grava_atomico(os.path.join(self.base, 'consumo.json'), c)


# ================================================================================================= relatorio
class Stop(Exception):
    pass


class Relatorio:
    def __init__(self, evid):
        self.evid = evid
        os.makedirs(evid, exist_ok=True)
        self.h = open(os.path.join(evid, 'jornadas.txt'), 'a', encoding='utf-8')
        self.passos = 0
        self.mutacoes = []
        self.nao_executadas = []
        self.jornada = '-'

    def escreve(self, linha):
        print(linha, flush=True)
        self.h.write(linha + '\n')
        self.h.flush()

    def contrato(self, metodo, caminho, status, erros):
        if erros:
            self.escreve(f'FALHA CONTRATO [{self.jornada}] {metodo} {caminho.split("?")[0]} -> {status}: ' + ' | '.join(erros[:5]))
            raise Stop('resposta fora do contrato')

    def confere(self, pid, descricao, ok, obtido=''):
        self.passos += 1
        self.escreve(f'{"OK   " if ok else "FALHA"} {pid} [{self.jornada}] {descricao}' + (f' (obtido: {obtido})' if obtido != '' else ''))
        if not ok:
            raise Stop(f'{pid}: resultado inesperado')

    def nao_executada(self, pid, descricao):
        self.nao_executadas.append(f'{pid} [{self.jornada}] {descricao}')
        self.escreve(f'NAO EXECUTADA {pid} [{self.jornada}] {descricao}')

    def mutacao(self, descricao):
        self.mutacoes.append(f'[{self.jornada}] {descricao}')
        self.escreve(f'MUTACAO [{self.jornada}] {descricao}')


# =================================================================================================== jornadas
O5_MAXIMO = 2   # limite autorizado da O5 na rodada da P-B (preparacao, sec. 7.4)
JORNADAS_SELECIONAVEIS = ('sessoes-pos-revogacao', 'expiracao-absoluta')


def valida_selecao(a):
    """Selecao (--somente) validada ANTES de qualquer requisicao ou arquivo. Devolve a lista (vazia = sem selecao)."""
    if a.somente is None:
        if a.vinculo_stop:
            raise SystemExit('--vinculo-stop so com --somente')
        return []
    nomes = [x.strip() for x in a.somente.split(',')]
    if a.acao != 'executar':
        raise SystemExit('--somente so com executar')
    if not a.vinculo_stop or not a.vinculo_stop.strip():
        raise SystemExit('--somente exige --vinculo-stop (o STOP que a selecao continua)')
    if a.mutacoes or a.inatividade_real:
        raise SystemExit('--somente nao se combina com --mutacoes nem com --inatividade-real')
    if not nomes or any(not x for x in nomes):
        raise SystemExit('--somente com lista vazia ou nome vazio')
    if len(set(nomes)) != len(nomes):
        raise SystemExit('--somente com nome repetido')
    desconhecidos = [x for x in nomes if x not in JORNADAS_SELECIONAVEIS]
    if desconhecidos:
        raise SystemExit(f'--somente com jornada desconhecida: {",".join(desconhecidos)} (aceitas: {",".join(JORNADAS_SELECIONAVEIS)})')
    if 'expiracao-absoluta' in nomes and not (a.psql and a.sql_sessao):
        raise SystemExit('expiracao-absoluta exige --psql e --sql-sessao (a O5)')
    return nomes


def base(org):
    return f'/api/v1/o/{org}'


class Jornadas:
    def __init__(self, a, alvo, validador, cofre, rel):
        self.a, self.alvo, self.v, self.cofre, self.rel = a, alvo, validador, cofre, rel
        p = cofre.plano
        self.org = {o['chave']: o['id'] for o in p['orgs']}
        self.ws = {w['chave']: w for w in p['workspaces']}
        self.auto = {x['chave']: x for x in p['automacoes']}
        self.A, self.B = self.org['a'], self.org['b']

    def sessao(self):
        return Sessao(self.alvo, self.v, self.rel)

    def login(self, chave, codigo_recuperacao=None):
        """Login completo pelo caminho do navegador. Devolve a sessao autenticada."""
        c = self.cofre.contas[chave]
        org = self.org[c['org']]
        s = self.sessao()
        st, js, _ = s.req('GET', '/api/v1/csrf')
        self.rel.confere('L.01', f'{chave}: CSRF de pre-autenticacao', st == 200, st)
        s.csrf = js['csrfToken']
        st, js, _ = s.req('POST', base(org) + '/auth/login', {'email': c['email'], 'password': c['senha']})
        self.rel.confere('L.02', f'{chave}: login por senha -> desafio de MFA', st == 200 and js['status'] == 'mfa_required', st)
        s.csrf = js['csrfToken']
        corpo = {'recoveryCode': codigo_recuperacao} if codigo_recuperacao else {'code': self.cofre.codigo(chave)}
        st, js, _ = s.req('POST', base(org) + '/auth/mfa/verify', corpo)
        self.rel.confere('L.03', f'{chave}: verificacao de MFA ({"codigo de recuperacao" if codigo_recuperacao else "TOTP"})', st == 200, st)
        s.csrf = js['csrfToken']
        return s

    def logout(self, s, chave):
        org = self.org[self.cofre.contas[chave]['org']]
        st, _, _ = s.req('POST', base(org) + '/auth/logout')
        self.rel.confere('L.09', f'{chave}: logout', st == 204, st)
        self.rel.mutacao(f'{chave}: logout encerra a propria sessao da jornada')
        st, _, _ = s.req('GET', base(org) + '/me')
        self.rel.confere('L.10', f'{chave}: sessao encerrada -> /me 401', st == 401, st)

    def pagina_tudo(self, s, caminho, limite, chave_ordem=None):
        itens, cursor, paginas = [], None, 0
        while True:
            sep = '&' if '?' in caminho else '?'
            st, js, _ = s.req('GET', f'{caminho}{sep}limit={limite}' + (f'&cursor={urllib.parse.quote(cursor)}' if cursor else ''))
            if st != 200:
                return st, itens, paginas
            itens += js['items']
            paginas += 1
            cursor = js.get('nextCursor')
            if not cursor:
                return 200, itens, paginas
            if paginas > 100:
                raise Stop('paginacao sem fim')

    def ws_visiveis(self, chave):
        c = self.cofre.contas[chave]
        if 'ORG_ADMIN' in c['papeis'].get('org', []):
            return {w['id'] for w in self.ws.values() if w['org'] == c['org']}
        return {self.ws[w]['id'] for w, _ in c['papeis'].get('ws', [])}

    def execucoes_no_ws(self, wkey):
        n = sum(1 for e in self.cofre.plano['execucoes'] if self.auto[e['automacao']]['workspace'] == wkey)
        return n + (1 if wkey == 'fin' else 0)   # a agendada da 009 (conciliacao, em fin)

    # ------------------------------------------------------------------------------------------- leitura
    def sem_sessao(self):
        self.rel.jornada = 'sem-sessao'
        s = self.sessao()
        rotas = [f'{base(self.A)}/me', f'{base(self.A)}/me/permissions', f'{base(self.A)}/workspaces', f'{base(self.A)}/executions']
        if self.a.sem_portal:
            self.rel.nao_executada('S.01', f'sem sessao: GET /portal/api/v1/o/{{A}}/workspaces (sem o Portal neste alvo)')
        else:
            rotas.append(f'/portal/api/v1/o/{self.A}/workspaces')
        for c in rotas:
            st, _, _ = s.req('GET', c)
            self.rel.confere('S.01', f'sem sessao: GET {c.replace(self.A, "{A}")} -> 401', st == 401, st)

    def interna(self, chave):
        self.rel.jornada = chave
        c = self.cofre.contas[chave]
        org = self.org[c['org']]
        b = base(org)
        s = self.login(chave)
        st, js, _ = s.req('GET', b + '/me')
        self.rel.confere('R.01', f'{chave}: /me interna com o e-mail da conta', st == 200 and js['accountType'] == 'internal' and js['email'] == c['email'], st)
        st, p1, _ = s.req('GET', b + '/me/permissions')
        papeis_org = c['papeis'].get('org', [])
        self.rel.confere('R.02', f'{chave}: P01 com os papeis de organizacao {papeis_org}', st == 200 and sorted(p1['organization']['roles']) == sorted(papeis_org), st)
        if 'ORG_ADMIN' in papeis_org:
            self.rel.confere('R.03', f'{chave}: P01 do ORG_ADMIN: 23 permissoes de organizacao e as 13 de workspace em allWorkspaces (DC-01)',
                             len(p1['organization']['permissions']) == 23 and len(p1['allWorkspaces']['permissions']) == 13,
                             (len(p1['organization']['permissions']), len(p1['allWorkspaces']['permissions'])))
        else:
            self.rel.confere('R.03', f'{chave}: P01 sem acesso global (allWorkspaces vazio); atribuicoes de workspace = {bool(c["papeis"].get("ws"))}',
                             p1['allWorkspaces']['permissions'] == [] and p1['hasWorkspaceAssignments'] == bool(c['papeis'].get('ws')))
        st, p2, _ = s.req('GET', b + '/me/permissions/workspaces?limit=50')
        esperado_p2 = {self.ws[w]['id']: r for w, r in c['papeis'].get('ws', [])}
        obtido_p2 = {x['workspaceId']: x['roles'] for x in p2['items']} if st == 200 else None
        self.rel.confere('R.04', f'{chave}: P02 com os papeis por workspace', st == 200 and obtido_p2 == {k: [v] for k, v in esperado_p2.items()},
                         {k[:8]: v for k, v in (obtido_p2 or {}).items()})
        st, itens, pags = self.pagina_tudo(s, b + '/workspaces', 3)
        ids = [x['workspaceId'] for x in itens]
        self.rel.confere('R.05', f'{chave}: L01 paginada (limite 3, {pags} paginas) = workspaces visiveis do plano, sem repeticao',
                         st == 200 and set(ids) == self.ws_visiveis(chave) and len(ids) == len(set(ids)), len(ids))
        chaves_ord = [(list(map(ord, x['name'][:48])), x['workspaceId']) for x in itens]
        self.rel.confere('R.06', f'{chave}: L01 na ordem do contrato (48 primeiros pontos de codigo do nome, depois id)', chaves_ord == sorted(chaves_ord))
        visiveis = self.ws_visiveis(chave)
        fora = [w for w in self.ws.values() if w['org'] == c['org'] and w['id'] not in visiveis]
        if fora:
            st, _, _ = s.req('GET', f'{b}/workspaces/{fora[0]["id"]}')
            self.rel.confere('R.07', f'{chave}: L02 de workspace sem acesso ({fora[0]["chave"]}) -> 404', st == 404, st)
            st, _, _ = s.req('GET', f'{b}/executions?workspaceId={fora[0]["id"]}')
            self.rel.confere('R.08', f'{chave}: L06 filtrada por workspace sem acesso -> 404', st == 404, st)
        if not visiveis:
            st, js, _ = s.req('GET', b + '/executions?limit=10')
            self.rel.confere('R.09', f'{chave}: sem workspaces, L06 -> 200 vazia', st == 200 and js['items'] == [] and js['nextCursor'] is None, st)
        for w in sorted((x for x in self.ws.values() if x['id'] in visiveis), key=lambda x: x['chave']):
            st, js, _ = s.req('GET', f'{b}/workspaces/{w["id"]}')
            est = {'ativo': 'active', 'suspenso': 'suspended', 'arquivado': 'archived'}[w['estado']]
            self.rel.confere('R.10', f'{chave}: L02 {w["chave"]} ({est})', st == 200 and js['status'] == est and js['name'] == w['nome'], st)
            st, autos, _ = self.pagina_tudo(s, f'{b}/workspaces/{w["id"]}/automations', 2)
            esperadas = {a['id'] for a in self.auto.values() if a['workspace'] == w['chave']}
            self.rel.confere('R.11', f'{chave}: L03 {w["chave"]} = automacoes do plano ({len(esperadas)})', st == 200 and {a['automationId'] for a in autos} == esperadas, len(autos))
            st, exe, pags = self.pagina_tudo(s, f'{b}/executions?workspaceId={w["id"]}', 100)
            ordem = [(x['createdAt'], x['executionId']) for x in exe]
            self.rel.confere('R.12', f'{chave}: L06 {w["chave"]}: {self.execucoes_no_ws(w["chave"])} execucoes em {pags} pagina(s), createdAt e id decrescentes',
                             st == 200 and len(exe) == self.execucoes_no_ws(w['chave']) and len({x['executionId'] for x in exe}) == len(exe)
                             and ordem == sorted(ordem, key=lambda t: (datetime.datetime.fromisoformat(t[0].replace('Z', '+00:00')), t[1]), reverse=True),
                             len(exe))
        if 'ORG_ADMIN' in papeis_org and c['org'] == 'a':
            self.leitura_admin(s, chave, b)
        elif c['papeis'].get('ws') and c['org'] == 'a':
            st, _, _ = s.req('GET', b + '/members?limit=5')
            self.rel.confere('R.30', f'{chave}: M01 sem permissao de organizacao -> 403', st == 403, st)
        self.isolamento(s, chave, c)
        self.logout(s, chave)

    def leitura_admin(self, s, chave, b):
        a = self.auto['conciliacao']
        st, vs, _ = self.pagina_tudo(s, f'{b}/automations/{a["id"]}/versions', 2)
        nums = [x['versionNumber'] for x in vs]
        self.rel.confere('R.20', f'{chave}: L05 conciliacao: versoes 4..1 decrescentes, a 4 em rascunho',
                         st == 200 and nums == [4, 3, 2, 1] and vs[0]['status'] == 'draft' and vs[1]['status'] == 'published', nums)
        st, js, _ = s.req('GET', f'{b}/automations/{self.auto["capacidade"]["id"]}')
        self.rel.confere('R.21', f'{chave}: L04 automacao suspensa', st == 200 and js['status'] == 'suspended', st)
        st, exe, _ = self.pagina_tudo(s, f'{b}/executions?automationId={a["id"]}', 50)
        agendadas = [x for x in exe if x['origin'] == 'scheduled']
        self.rel.confere('R.22', f'{chave}: L06 por automacao: exatamente uma agendada (fluxo da 009)', st == 200 and len(agendadas) == 1, len(agendadas))
        st, d, _ = s.req('GET', f'{b}/executions/{agendadas[0]["executionId"]}')
        self.rel.confere('R.23', f'{chave}: L07 agendada: solicitante nulo, concluida', st == 200 and d['requestedBy'] is None and d['status'] == 'succeeded', st)
        r = next(e for e in self.cofre.plano['execucoes'] if e['retry_de'])
        st, d, _ = s.req('GET', f'{b}/executions/{r["id"]}')
        self.rel.confere('R.24', f'{chave}: L07 reexecucao aponta a execucao anterior', st == 200 and d['retryOfExecutionId'] == r['retry_de'] and d['requestedBy'] is not None, st)
        st, ev, _ = self.pagina_tudo(s, f'{b}/executions/{r["id"]}/events', 1)
        self.rel.confere('R.25', f'{chave}: L08 eventos em ordem (limite 1): process_started, process_succeeded',
                         st == 200 and [x['type'] for x in ev] == ['process_started', 'process_succeeded'] and [x['sequence'] for x in ev] == [1, 2], [x['type'] for x in ev])
        for estado in ('failed', 'cancelled', 'running'):
            st, js, _ = s.req('GET', f'{b}/executions?workspaceId={self.ws["fin"]["id"]}&status={estado}&limit=50')
            self.rel.confere('R.26', f'{chave}: L06 filtro de estado {estado}: so esse estado', st == 200 and js['items'] and all(x['status'] == estado for x in js['items']), st)
        desde = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=30)).strftime('%Y-%m-%dT%H:%M:%SZ')
        st, js, _ = s.req('GET', f'{b}/executions?workspaceId={self.ws["legado"]["id"]}&from={desde}&limit=10')
        self.rel.confere('R.27', f'{chave}: L06 legado nos ultimos 30 dias: vazia (so historico antigo)', st == 200 and js['items'] == [], st)
        st, mem, _ = self.pagina_tudo(s, f'{b}/members', 7)
        internas = {c['email'] for c in self.cofre.plano['contas'] if c['org'] == 'a' and c['tipo'] == 'interna'}
        self.rel.confere('R.28', f'{chave}: M01 membros = contas internas do plano ({len(internas)})', st == 200 and {m['email'] for m in mem} == internas, len(mem))
        st, gr, _ = self.pagina_tudo(s, f'{b}/client-grants', 4)
        self.rel.confere('R.29', f'{chave}: M02 concessoes: pending, active e revoked das duas contas do cliente',
                         st == 200 and sorted(g['status'] for g in gr) == ['active', 'active', 'pending', 'pending', 'revoked', 'revoked'], sorted(g['status'] for g in gr))
        st, cl, _ = self.pagina_tudo(s, f'{b}/clients', 2)
        self.rel.confere('R.31', f'{chave}: M03 clientes = os 3 do plano (nomes com acento e apostrofo)',
                         st == 200 and {x['name'] for x in cl} == {k['nome'] for k in self.cofre.plano['clientes'] if k['org'] == 'a'}, len(cl))

    def isolamento(self, s, chave, c):
        outra = 'b' if c['org'] == 'a' else 'a'
        o = self.org[outra]
        st, _, _ = s.req('GET', base(o) + '/me')
        self.rel.confere('I.01', f'{chave}: sessao da organizacao {c["org"].upper()} na rota de {outra.upper()} -> 401', st == 401, st)
        alheio = next(w for w in self.ws.values() if w['org'] == outra)
        st, _, _ = s.req('GET', f'{base(self.org[c["org"]])}/workspaces/{alheio["id"]}')
        self.rel.confere('I.02', f'{chave}: id de workspace da outra organizacao na propria rota -> 404', st == 404, st)
        e = next(x for x in self.cofre.plano['execucoes'] if self.ws[self.auto[x['automacao']]['workspace']]['org'] == outra)
        st, _, _ = s.req('GET', f'{base(self.org[c["org"]])}/executions/{e["id"]}')
        self.rel.confere('I.03', f'{chave}: id de execucao da outra organizacao na propria rota -> 404', st == 404, st)

    def externa(self, chave):
        self.rel.jornada = chave
        c = self.cofre.contas[chave]
        b, pb = base(self.A), f'/portal/api/v1/o/{self.A}'
        s = self.login(chave)
        st, js, _ = s.req('GET', b + '/me')
        self.rel.confere('E.01', f'{chave}: /me externa', st == 200 and js['accountType'] == 'external', st)
        for rota in ('/me/permissions', '/workspaces', '/executions'):
            st, _, _ = s.req('GET', b + rota)
            self.rel.confere('E.02', f'{chave}: rota interna {rota} -> 403', st == 403, st)
        if self.a.sem_portal:
            for pid, d in (('E.03', 'portal: workspaces com concessao ativa'), ('E.04', 'portal: execucoes dos concedidos'),
                           ('E.04b', 'portal: workspaceId omitido com um unico workspace'), ('E.05', 'portal: detalhe'),
                           ('E.06', 'portal: eventos'), ('E.07', 'portal: execucao sem concessao -> 404'), ('E.08', 'portal da outra organizacao -> 401')):
                self.rel.nao_executada(pid, f'{chave}: {d} (sem o Portal neste alvo)')
            self.logout(s, chave)
            return
        st, js, _ = s.req('GET', pb + '/workspaces')
        ativos = {self.ws[w]['id'] for w, est in c['papeis']['concessoes'] if est == 'ativa'}
        self.rel.confere('E.03', f'{chave}: portal: so os workspaces com concessao ATIVA', st == 200 and {w['workspaceId'] for w in js['items']} == ativos, st)
        st, js, _ = s.req('GET', pb + '/executions')
        unico = next(iter(ativos)) if len(ativos) == 1 else None
        self.rel.confere('E.04', f'{chave}: portal: execucoes so dos workspaces concedidos', st == 200 and js['items']
                         and all(x.get('workspaceId', unico) in ativos for x in js['items']), st)
        if unico:
            self.rel.confere('E.04b', f'{chave}: portal com UM workspace autorizado omite workspaceId (contrato)',
                             all('workspaceId' not in x for x in js['items']))
        x = js['items'][0]['executionId']
        st, _, _ = s.req('GET', f'{pb}/executions/{x}')
        self.rel.confere('E.05', f'{chave}: portal: detalhe de execucao concedida', st == 200, st)
        st, _, _ = s.req('GET', f'{pb}/executions/{x}/events')
        self.rel.confere('E.06', f'{chave}: portal: eventos de execucao concedida', st == 200, st)
        fin = next(e for e in self.cofre.plano['execucoes'] if self.auto[e['automacao']]['workspace'] == 'fin')
        st, _, _ = s.req('GET', f'{pb}/executions/{fin["id"]}')
        self.rel.confere('E.07', f'{chave}: portal: execucao de workspace sem concessao -> 404', st == 404, st)
        st, _, _ = s.req('GET', f'/portal/api/v1/o/{self.B}/workspaces')
        self.rel.confere('E.08', f'{chave}: portal da outra organizacao -> 401', st == 401, st)
        self.logout(s, chave)

    # -------------------------------------------------------------------------------------------- mutacoes
    def primeiro_acesso(self):
        self.rel.jornada = 'primeiro-acesso'
        cons = self.cofre.consumo()['primeiro-acesso']
        livre = next((c for c in sorted(self.cofre.contas) if self.cofre.contas[c]['uso'] == 'primeiro-acesso' and c not in cons), None)
        if livre is None:
            self.rel.escreve('STOP primeiro-acesso: contas exclusivas ESGOTADAS (repor e uma acao aditiva propria)')
            raise Stop('contas de primeiro acesso esgotadas')
        self.cofre.marca('primeiro-acesso', livre, 'em-uso')   # antes de usar: nunca reutilizada, mesmo se falhar
        self.rel.mutacao(f'{livre}: primeiro acesso cadastra o MFA da conta exclusiva (consumida)')
        c = self.cofre.contas[livre]
        b = base(self.org[c['org']])
        s = self.sessao()
        st, js, _ = s.req('GET', '/api/v1/csrf')
        s.csrf = js['csrfToken']
        st, js, _ = s.req('POST', b + '/auth/login', {'email': c['email'], 'password': c['senha']})
        self.rel.confere('M.01', f'{livre}: login -> cadastro de MFA exigido', st == 200 and js['status'] == 'mfa_enrollment_required', st)
        s.csrf = js['csrfToken']
        st, js, _ = s.req('POST', b + '/auth/mfa/enroll/start')
        self.rel.confere('M.02', f'{livre}: inicio do cadastro devolve otpauth', st == 200 and js['otpauthUri'].startswith('otpauth://totp/'), st)
        segredo = urllib.parse.parse_qs(urllib.parse.urlparse(js['otpauthUri']).query)['secret'][0]
        grava_atomico(self.cofre.arq_conta(livre), {'totp': segredo, 'estado': 'pendente', 'ultimo_passo': -1})
        st, js, _ = s.req('POST', b + '/auth/mfa/enroll/confirm', {'code': self.cofre.codigo(livre)})
        self.rel.confere('M.03', f'{livre}: confirmacao devolve os codigos de recuperacao', st == 200 and len(js['recoveryCodes']) >= 8, st)
        m = self.cofre.mfa(livre)
        m.update({'estado': 'confirmado', 'codigos': js['recoveryCodes']})
        grava_atomico(self.cofre.arq_conta(livre), m)
        s.csrf = js['csrfToken']
        st, _, _ = s.req('GET', b + '/me')
        self.rel.confere('M.04', f'{livre}: sessao valida depois do cadastro', st == 200, st)
        self.cofre.marca('primeiro-acesso', livre, 'consumida')
        self.logout(s, livre)

    def recuperacao(self):
        self.rel.jornada = 'recuperacao'
        cons = self.cofre.consumo()['recuperacao']
        for chave in sorted(k for k, c in self.cofre.contas.items() if c['uso'] == 'recuperacao'):
            m = self.cofre.mfa(chave)
            usados = cons.get(chave, [])
            livres = [i for i in range(len((m or {}).get('codigos', []))) if i not in usados]
            if livres:
                break
        else:
            self.rel.escreve('STOP recuperacao: codigos de recuperacao ESGOTADOS')
            raise Stop('codigos de recuperacao esgotados')
        i = livres[0]
        self.cofre.marca('recuperacao', chave, usados + [i])   # antes de usar
        self.rel.mutacao(f'{chave}: consome UM codigo de recuperacao (indice {i}); a plataforma encerra as demais sessoes da conta')
        s = self.login(chave, codigo_recuperacao=m['codigos'][i])
        st, _, _ = s.req('GET', base(self.A) + '/me')
        self.rel.confere('M.10', f'{chave}: sessao valida depois do codigo de recuperacao', st == 200, st)
        self.logout(s, chave)
        # o mesmo codigo nao vale de novo
        c = self.cofre.contas[chave]
        s2 = self.sessao()
        st, js, _ = s2.req('GET', '/api/v1/csrf')
        s2.csrf = js['csrfToken']
        st, js, _ = s2.req('POST', base(self.A) + '/auth/login', {'email': c['email'], 'password': c['senha']})
        s2.csrf = js['csrfToken']
        st, _, _ = s2.req('POST', base(self.A) + '/auth/mfa/verify', {'recoveryCode': m['codigos'][i]})
        self.rel.confere('M.11', f'{chave}: codigo ja usado e recusado', st in (400, 401), st)

    def sessoes(self):
        self.rel.jornada = 'sessoes'
        chave = 'sessoes'
        b = base(self.A)
        s1 = self.login(chave)
        s2 = self.login(chave)
        sem = self.sessao()
        sem.cookies = dict(s2.cookies)
        st, _, _ = sem.req('POST', b + '/auth/sessions/revoke-all', csrf=False)
        self.rel.confere('M.20', 'revoke-all sem CSRF -> 403 (nada revogado)', st == 403, st)
        estranho = self.sessao()
        estranho.cookies, estranho.csrf, estranho.origem = dict(s2.cookies), s2.csrf, 'https://atacante.example'
        st, _, _ = estranho.req('POST', b + '/auth/sessions/revoke-all')
        self.rel.confere('M.21', 'revoke-all com Origin estranho -> 403 (nada revogado)', st == 403, st)
        st, _, _ = s1.req('GET', b + '/me')
        self.rel.confere('M.22', 'sessao 1 continua valida depois das recusas', st == 200, st)
        self.rel.mutacao('sessoes: revoke-all a partir da sessao 2 revoga TODAS as sessoes da conta exclusiva (contrato: inclusive a atual)')
        st, _, _ = s2.req('POST', b + '/auth/sessions/revoke-all')
        self.rel.confere('M.23', 'revoke-all valido', st == 204, st)
        st, _, _ = s1.req('GET', b + '/me')
        self.rel.confere('M.24', 'sessao 1 revogada -> 401', st == 401, st)
        st, _, _ = s2.req('GET', b + '/me')
        self.rel.confere('M.25', 'sessao 2 (quem revogou) tambem revogada -> 401', st == 401, st)
        s3 = self.login(chave)
        self.rel.confere('M.26', 'depois do revoke-all, um novo login funciona', True)
        self.logout(s3, chave)

    def sessoes_pos_revogacao(self):
        """M.26 isolado (continuacao do STOP da P-B): novo login de sessoes@ depois do revoke-all ja ocorrido, /me,
        logout. NAO chama revoke-all."""
        self.rel.jornada = 'sessoes-pos-revogacao'
        chave = 'sessoes'
        s = self.login(chave)
        st, _, _ = s.req('GET', base(self.A) + '/me')
        self.rel.confere('M.26', 'sessoes: novo login depois do revoke-all funciona (/me 200)', st == 200, st)
        self.logout(s, chave)

    def expiracao_absoluta(self):
        """M.30-M.32: a O5 (operacao declarada que SO encurta expires_at das sessoes ativas de expira@), com o
        livro de consumo: marcada ANTES de chamar o banco; com O5_MAXIMO ja marcadas, STOP sem chamar o banco."""
        self.rel.jornada = 'expiracao-absoluta'
        chave = 'expira'
        c = self.cofre.contas[chave]
        b = base(self.A)
        if not self.a.psql:
            self.rel.nao_executada('M.30', 'expira: prazo absoluto (sem --psql para a operacao declarada)')
            return
        usadas = self.cofre.o5_usadas()
        if usadas >= O5_MAXIMO:
            self.rel.escreve(f'STOP expiracao-absoluta: O5 ESGOTADA ({usadas} de {O5_MAXIMO}); o banco nao foi chamado')
            raise Stop('O5 esgotada')
        s = self.login(chave)
        st, _, _ = s.req('GET', b + '/me')
        self.rel.confere('M.30', 'expira: sessao valida antes de encurtar o prazo absoluto', st == 200, st)
        agora = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')
        indice = self.cofre.marca_o5({'inicio': agora, 'estado': 'em-uso', 'conta': chave})
        self.rel.mutacao(f'expira: O5 execucao {indice} de {O5_MAXIMO} - encurta o prazo absoluto das sessoes ATIVAS SO desta conta')
        n = envelhece(self.a, c['user_id'], self.A, 'absoluta')
        self.cofre.atualiza_o5(indice, {'estado': 'executada', 'sessoes': n})
        self.rel.confere('M.31', 'expira: prazo absoluto encurtado so nas sessoes desta conta', n >= 1, n)
        st, _, _ = s.req('GET', b + '/me')
        self.rel.confere('M.32', 'expira: sessao com prazo absoluto vencido -> 401', st == 401, st)

    def expiracao(self):
        self.rel.jornada = 'expiracao'
        self.expiracao_absoluta()
        self.rel.jornada = 'expiracao'
        chave = 'expira'
        b = base(self.A)
        # inatividade: relogio REAL (sem simulacao: created_at e imutavel no banco)
        if not self.a.inatividade_real:
            self.rel.nao_executada('M.33', 'expira: inatividade com o relogio real (sem --inatividade-real; espera de 31 min)')
            return
        s = self.login(chave)
        st, _, _ = s.req('GET', b + '/me')
        self.rel.confere('M.33', 'expira: sessao valida antes da inatividade', st == 200, st)
        self.rel.escreve('expira: aguardando 31 min sem nenhuma requisicao nesta sessao (inatividade interna: 30 min)')
        time.sleep(31 * 60)
        st, _, _ = s.req('GET', b + '/me')
        self.rel.confere('M.34', 'expira: sessao inativa por mais de 30 min -> 401', st == 401, st)


def envelhece(a, user_id, org_id, tipo):
    """Mesma SQL da acao declarada do runner (sql/sessao_frontend.sql), pelo comando psql informado. Erro do psql vai
    para a mensagem do STOP (so a linha de ERROR; sem dados)."""
    sql = open(a.sql_sessao, encoding='utf-8').read()
    entrada = f"\\set usuario '{user_id}'\n\\set organizacao '{org_id}'\n\\set tipo '{tipo}'\n" + sql
    bash = a.bash or os.environ.get('HOMOLOG_BASH') or r'C:\Program Files\Git\bin\bash.exe'
    r = subprocess.run([bash, '-c', a.psql], input=entrada.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    out = r.stdout.decode('utf-8', 'replace').replace('\r', '')
    m = re.search(r'SESSOES_ENVELHECIDAS=(\d+)', out)
    if r.returncode != 0 or not m:
        erro = [x for x in r.stderr.decode('utf-8', 'replace').splitlines() if 'ERROR' in x or 'ERRO' in x][:1]
        raise Stop(f'operacao declarada de sessao falhou (codigo {r.returncode}): {" ".join(erro)}')
    return int(m.group(1))


# ============================================================================================ preparar-contas
def preparar_contas(a, alvo, v, cofre, rel):
    rel.jornada = 'preparar-contas'
    j = Jornadas(a, alvo, v, cofre, rel)
    for chave, c in sorted(cofre.contas.items()):
        if not c['mfa']:
            continue
        m = cofre.mfa(chave)
        if m and m.get('estado') == 'confirmado':
            rel.escreve(f'JA PREPARADA {chave}')
            continue
        b = base(j.org[c['org']])
        s = j.sessao()
        st, js, _ = s.req('GET', '/api/v1/csrf')
        s.csrf = js['csrfToken']
        st, js, _ = s.req('POST', b + '/auth/login', {'email': c['email'], 'password': c['senha']})
        rel.confere('P.01', f'{chave}: login por senha', st == 200, st)
        s.csrf = js['csrfToken']
        if js['status'] == 'mfa_required':
            # fator ja confirmado: so e recuperavel se o segredo pendente foi gravado antes da confirmacao
            if not (m and m.get('totp')):
                rel.escreve(f'STOP {chave}: MFA confirmado sem segredo guardado (reemissao pelo caminho autorizado de reset)')
                raise Stop('fator sem segredo')
            st, js, _ = s.req('POST', b + '/auth/mfa/verify', {'code': cofre.codigo(chave)})
            rel.confere('P.02', f'{chave}: segredo pendente confere com o fator confirmado (codigos de recuperacao perdidos)', st == 200, st)
            m = cofre.mfa(chave)
            m.update({'estado': 'confirmado', 'codigos': [], 'codigos_perdidos': True})
            grava_atomico(cofre.arq_conta(chave), m)
        else:
            st, js, _ = s.req('POST', b + '/auth/mfa/enroll/start')
            rel.confere('P.03', f'{chave}: inicio do cadastro de MFA', st == 200, st)
            segredo = urllib.parse.parse_qs(urllib.parse.urlparse(js['otpauthUri']).query)['secret'][0]
            grava_atomico(cofre.arq_conta(chave), {'totp': segredo, 'estado': 'pendente', 'ultimo_passo': -1})
            st, js, _ = s.req('POST', b + '/auth/mfa/enroll/confirm', {'code': cofre.codigo(chave)})
            rel.confere('P.04', f'{chave}: cadastro de MFA confirmado', st == 200, st)
            m = cofre.mfa(chave)
            m.update({'estado': 'confirmado', 'codigos': js['recoveryCodes']})
            grava_atomico(cofre.arq_conta(chave), m)
        s.csrf = js['csrfToken']
        st, _, _ = s.req('POST', b + '/auth/logout')
        rel.confere('P.05', f'{chave}: logout da sessao de preparacao', st == 204, st)
    rel.mutacao('preparar-contas: MFA cadastrado (uma vez) nas contas com mfa=True; sessoes de preparacao encerradas')


# ===================================================================================================== main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('acao', choices=['preparar-contas', 'executar'])
    ap.add_argument('--segredos', required=True)
    ap.add_argument('--evid', required=True)
    ap.add_argument('--openapi-json', required=True)
    ap.add_argument('--openapi-yaml', required=True, help='o YAML do repositorio: o sha256 tem de ser o registrado no JSON')
    ap.add_argument('--alvo')
    ap.add_argument('--ca')
    ap.add_argument('--teste-http')
    ap.add_argument('--origem')
    ap.add_argument('--mutacoes', action='store_true')
    ap.add_argument('--inatividade-real', action='store_true', help='espera real de 31 min para provar a expiracao por inatividade')
    ap.add_argument('--sem-portal', action='store_true', help='alvo sem o servico do Portal (Platform em processo): passos do portal NAO EXECUTADOS')
    ap.add_argument('--psql')
    ap.add_argument('--sql-sessao')
    ap.add_argument('--bash')
    ap.add_argument('--somente', help='lista (virgula) de jornadas selecionaveis; ver JORNADAS_SELECIONAVEIS')
    ap.add_argument('--vinculo-stop', help='identificacao do STOP que a selecao continua (registrada no resultado)')
    a = ap.parse_args()
    try:
        selecao = valida_selecao(a)
    except SystemExit as e:
        print(f'RECUSADO: {e}', file=sys.stderr)
        sys.exit(2)
    spec = json.load(open(a.openapi_json, encoding='utf-8'))
    sha = hashlib.sha256(open(a.openapi_yaml, 'rb').read()).hexdigest()
    if spec.get('x-origem-yaml', {}).get('sha256') != sha:
        raise SystemExit(f'OpenAPI JSON nao corresponde ao YAML ({sha})')
    alvo = Alvo(a)
    v = Validador(spec)
    rel = Relatorio(a.evid)
    cofre = Cofre(a.segredos)
    rel.escreve(f'== {a.acao} {datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")} '
                f'alvo={"teste-http (Platform em processo, sem gateway nem TLS)" if alvo.http else a.alvo} openapi_yaml_sha256={sha}')
    executadas, interrompida = [], None
    if selecao:
        rel.escreve(f'SELECAO: somente {",".join(selecao)}; continuacao do STOP: {a.vinculo_stop}')
    rc = 0
    try:
        if a.acao == 'preparar-contas':
            preparar_contas(a, alvo, v, cofre, rel)
        elif selecao:
            j = Jornadas(a, alvo, v, cofre, rel)
            metodo = {'sessoes-pos-revogacao': j.sessoes_pos_revogacao, 'expiracao-absoluta': j.expiracao_absoluta}
            for nome in selecao:
                interrompida = nome
                metodo[nome]()
                executadas.append(nome)
                interrompida = None
        else:
            j = Jornadas(a, alvo, v, cofre, rel)
            j.sem_sessao()
            for chave in ('j-admin', 'j-wsadmin', 'j-operador', 'j-leitor', 'j-semacesso', 'j-admin-b'):
                j.interna(chave)
            j.externa('j-cliente')
            if a.mutacoes:
                j.primeiro_acesso()
                j.recuperacao()
                j.sessoes()
                j.expiracao()
        rel.escreve(f'RESULTADO: {rel.passos} verificacoes OK; {v.respostas} respostas validadas contra o OpenAPI; '
                    f'{len(rel.nao_executadas)} nao executadas')
    except Stop as e:
        rel.escreve(f'STOP no primeiro resultado inesperado: {e}')
        rc = 1
    resumo = {
        'acao': a.acao, 'codigo': rc, 'verificacoes_ok': rel.passos, 'respostas_validadas': v.respostas,
        'mutacoes': rel.mutacoes, 'nao_executadas': rel.nao_executadas,
        'selecao': None if not selecao else {
            'vinculo_stop': a.vinculo_stop, 'selecionadas': selecao, 'executadas': executadas,
            'interrompida': interrompida,
            'nao_executadas': [x for x in selecao if x not in executadas and x != interrompida],
            'o5_usadas_depois': cofre.o5_usadas(), 'o5_maximo': O5_MAXIMO},
        'validador': {
            'palavras_verificadas': sorted(Validador.SUPORTADAS), 'formatos_verificados': sorted(Validador.FORMATOS),
            'date_time': 'parsing real (calendario, fuso obrigatorio; segundo 60 so em 23:59:60 UTC)',
            'uso_por_palavra': dict(v.palavras), 'restricoes_encontradas_nao_verificadas': dict(v.nao_verificadas),
            'formatos_encontrados_nao_verificados': dict(v.formatos_nao_verificados),
            'restricoes_do_contrato_nao_verificadas': v.restricoes_do_contrato_nao_verificadas(),
            'nao_coberto': ['headers alem de Set-Cookie (nao conferidos)', 'formatos alem de uuid e date-time',
                            'corpos de requisicao (so as respostas sao validadas)'],
        },
    }
    with open(os.path.join(a.evid, f'resumo_{a.acao}.json'), 'w', encoding='utf-8') as h:
        json.dump(resumo, h, ensure_ascii=False, indent=1)
    sys.exit(rc)


if __name__ == '__main__':
    main()
