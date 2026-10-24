"""Jornadas integradas da API v1 pelo gateway do ensaio (missao PLATFORM-API-V1-003, item 7).

Fala SO com o gateway HTTPS, como um navegador: certificado conferido contra a CA do ambiente e pelo NOME da origem (SNI),
cookies __Host- guardados e apagados como num navegador (Max-Age/Expires), Origin e o CSRF do contexto. Os tokens de
e-mail vem da CAIXA do transporte de homologacao (ferramenta restrita do runner: "homologacao.sh caixa <id> ler"), como a
titular os receberia; nunca sao gravados na evidencia.

Separacao declarada (o relatorio marca cada linha):
  API    - a acao e o resultado foram obtidos pela API, pelo gateway;
  CAIXA  - mensagem na caixa simulada (tipo e destinatario; nunca o token);
  BANCO  - VERIFICACAO somente leitura no banco (admin_cluster), para o que a API nao expoe (limites, estado interno);
  PREP   - PREPARACAO no banco do que a API ainda nao cria (sql/semear_api_v1.sql: organizacao legada e contas antigas) ou
           estado que so a operacao altera (retirada de vinculo, suspensao da organizacao). Nunca conta como jornada.

Nenhum valor secreto (senha, token, cookie, CSRF, segredo TOTP, codigo de recuperacao) nem endereco de e-mail vai para a
evidencia: todos sao anotados em --vistos (fora do Git), lido depois pela varredura de logs e evidencias.

Uso (pelo runner): homologacao.sh jornadas-api <id> [--somente J1,J2,...]
Saida: jornadas.txt (uma linha "jornada|caso|tipo|OK/FALHA|esperado|obtido"), resumo.txt; codigo 1 se algum caso falhar.
"""
import argparse
import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import secrets
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
from email.utils import parsedate_to_datetime

JORNADAS = ['J1', 'J2', 'J3', 'J4', 'J5', 'J6', 'J7', 'J8', 'J10']


# ------------------------------------------------------------------------------------------------ evidencia e segredos
class Relatorio:
    def __init__(self, evid, vistos):
        self.evid = evid
        self.vistos = vistos
        self.falhas = 0
        self.total = 0
        self.atual = '-'
        os.makedirs(evid, exist_ok=True)
        self.arq = open(os.path.join(evid, 'jornadas.txt'), 'a', encoding='utf-8')
        self.arq.write('jornada|caso|tipo|resultado|esperado|obtido\n')
        os.makedirs(os.path.dirname(vistos), mode=0o700, exist_ok=True)

    def segredo(self, *valores):
        with open(self.vistos, 'a', encoding='utf-8') as h:
            for v in valores:
                if v and len(str(v)) >= 6:
                    h.write(str(v) + '\n')

    def confere(self, caso, tipo, ok, esperado, obtido):
        self.total += 1
        if not ok:
            self.falhas += 1
        linha = f'{self.atual}|{caso}|{tipo}|{"OK" if ok else "FALHA"}|{esperado}|{obtido}'
        self.arq.write(linha + '\n')
        self.arq.flush()
        print(linha, flush=True)
        return ok

    def nota(self, texto):
        self.arq.write(f'{self.atual}|nota|-|-|-|{texto}\n')
        self.arq.flush()
        print(f'{self.atual}|nota|{texto}', flush=True)


# ------------------------------------------------------------------------------------------------ HTTP como navegador
class Alvo:
    def __init__(self, alvo, ca):
        u = urllib.parse.urlsplit(alvo)
        self.host, self.porta = u.hostname, u.port
        self.origem = f'https://{self.host}:{self.porta}'
        self.ctx = ssl.create_default_context(cafile=ca)
        self.ctx.minimum_version = ssl.TLSVersion.TLSv1_2


class Navegador:
    """Um navegador: cookie jar proprio. csrf e passado por requisicao (cada contexto tem o seu)."""

    def __init__(self, alvo, rel, nome):
        self.alvo, self.rel, self.nome = alvo, rel, nome
        self.cookies = {}

    def _conexao(self):
        a = self.alvo

        class C(http.client.HTTPSConnection):
            def connect(self):
                s = socket.create_connection(('127.0.0.1', a.porta), timeout=30)
                self.sock = a.ctx.wrap_socket(s, server_hostname=a.host)
        return C(a.host, a.porta, context=a.ctx, timeout=60)

    def req(self, metodo, caminho, corpo=None, csrf=None, origem=True, sem_cookies=()):
        c = self._conexao()
        cab = {'Host': f'{self.alvo.host}:{self.alvo.porta}', 'Accept': 'application/json'}
        jar = {k: v for k, v in self.cookies.items() if k not in sem_cookies}
        if jar:
            cab['Cookie'] = '; '.join(f'{k}={v}' for k, v in jar.items())
        if origem:
            cab['Origin'] = self.alvo.origem
        if csrf:
            cab['X-CSRF-Token'] = csrf
        dados = None
        if corpo is not None:
            dados = json.dumps(corpo).encode()
            cab['Content-Type'] = 'application/json'
        elif metodo == 'POST':
            dados = b''
        c.request(metodo, caminho, body=dados, headers=cab)
        r = c.getresponse()
        texto = r.read().decode('utf-8', 'replace')
        novos = []
        for k, v in r.getheaders():
            if k.lower() == 'set-cookie':
                novos.append(self._set_cookie(v))
        c.close()
        try:
            js = json.loads(texto) if texto.strip() else None
        except ValueError:
            js = None
        for chave in ('csrfToken', 'otpauthUri'):
            if isinstance(js, dict) and js.get(chave):
                self.rel.segredo(js[chave])
        if isinstance(js, dict) and isinstance(js.get('recoveryCodes'), list):
            self.rel.segredo(*js['recoveryCodes'])
        return r.status, js, [n for n in novos if n]

    def _set_cookie(self, v):
        partes = [p.strip() for p in v.split(';')]
        nome, _, valor = partes[0].partition('=')
        apagar = valor == ''
        for p in partes[1:]:
            k, _, val = p.partition('=')
            if k.lower() == 'max-age' and val.strip().lstrip('-').isdigit() and int(val) <= 0:
                apagar = True
            if k.lower() == 'expires':
                try:
                    if parsedate_to_datetime(val).timestamp() < time.time():
                        apagar = True
                except (TypeError, ValueError):
                    pass
        if apagar:
            self.cookies.pop(nome, None)
            return f'-{nome}'
        self.cookies[nome] = valor
        self.rel.segredo(valor)
        return f'+{nome}'


# ------------------------------------------------------------------------------------------------ TOTP
def totp(segredo_b32, passo):
    chave = base64.b32decode(segredo_b32 + '=' * (-len(segredo_b32) % 8))
    d = hmac.new(chave, struct.pack('>Q', passo), hashlib.sha1).digest()
    o = d[-1] & 0x0F
    return f'{(struct.unpack(">I", d[o:o + 4])[0] & 0x7FFFFFFF) % 1000000:06d}'


class Fator:
    """Segredo TOTP de um fator e o ultimo passo usado (o servidor aceita p-1..p+1 e recusa passo <= o ultimo aceito)."""

    def __init__(self, otpauth, rel):
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(otpauth).query)
        self.segredo = q['secret'][0]
        self.ultimo = -1
        rel.segredo(self.segredo)

    def codigo(self):
        while True:
            p = int(time.time()) // 30
            s = max(p - 1, self.ultimo + 1)
            if s <= p + 1:
                self.ultimo = s
                return totp(self.segredo, s)
            time.sleep(1)

    def errado(self):
        p = int(time.time()) // 30
        boa = {totp(self.segredo, x) for x in (p - 1, p, p + 1)}
        for _ in range(100):
            c = f'{secrets.randbelow(1000000):06d}'
            if c not in boa:
                return c


# ------------------------------------------------------------------------------------------------ caixa e banco
class Caixa:
    def __init__(self, cmd, bash, rel):
        self.cmd, self.bash, self.rel = cmd, bash, rel
        self.lidas = set()

    def _roda(self, args):
        r = subprocess.run([self.bash, '-c', f'{self.cmd} {args}'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
        return r.returncode, r.stdout.decode('utf-8', 'replace')

    def listar(self):
        rc, out = self._roda('listar')
        nomes = []
        sub = None
        for linha in out.splitlines():
            linha = linha.strip()
            if linha in ('avisos', 'outras'):
                sub = linha
                continue
            if linha.endswith('.json'):
                nomes.append(linha if '/' in linha else f'{sub or "outras"}/{linha}')
        return nomes

    def espera(self, tipo, destinatario, prazo=40):
        """Primeira mensagem NOVA (nao lida antes) do tipo, para o destinatario. Devolve o JSON (com o token, so em memoria)."""
        fim = time.time() + prazo
        while time.time() < fim:
            for n in sorted(self.listar()):
                if n in self.lidas or f'_{tipo}_' not in n:
                    continue
                rc, out = self._roda(f"ler '{n}'")
                if rc != 0:
                    continue
                try:
                    m = json.loads(out)
                except ValueError:
                    continue
                if m.get('destinatario') == destinatario:
                    self.lidas.add(n)
                    for k in ('token', 'token_confirmar', 'token_contestar'):
                        if m.get(k):
                            self.rel.segredo(m[k])
                    return m
            time.sleep(2)
        return None


class Banco:
    def __init__(self, psql, bash):
        self.psql, self.bash = psql, bash

    def q(self, sql, *vars_):
        cmd = self.psql + ''.join(f" -v {k}='{v}'" for k, v in vars_)
        r = subprocess.run([self.bash, '-c', cmd], input=sql.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
        if r.returncode != 0:
            raise RuntimeError(f'psql falhou ({r.returncode}): {r.stderr.decode("utf-8", "replace")[:300]}')
        return r.stdout.decode('utf-8', 'replace').strip()


# ------------------------------------------------------------------------------------------------ jornadas
class Jornadas:
    def __init__(self, a):
        self.a = a
        self.rel = Relatorio(a.evid, a.vistos)
        self.alvo = Alvo(a.alvo, a.ca)
        self.caixa = Caixa(a.caixa, a.bash, self.rel)
        self.banco = Banco(a.psql, a.bash)
        self.sufixo = time.strftime('%Y%m%d%H%M%S', time.gmtime()) + secrets.token_hex(2)
        self.estado = {}

    # -------------------------------------------------------------- utilitarios
    def nav(self, nome):
        return Navegador(self.alvo, self.rel, nome)

    def email(self, rotulo):
        e = f'api-{rotulo}-{self.sufixo}@homologacao.nekkora.test'
        self.rel.segredo(e)
        return e

    def senha(self):
        s = 'Hml-' + secrets.token_urlsafe(18)
        self.rel.segredo(s)
        return s

    def pre(self, n):
        st, js, _ = n.req('GET', '/api/v1/csrf', origem=False)
        return js['csrfToken'] if st == 200 and js else None

    def sessao_ok(self, n):
        st, js, _ = n.req('GET', '/api/v1/me/session', origem=False)
        return st, js

    def cria_identidade(self, rotulo, conferir=True):
        """Cadastro completo pela API (pedido, caixa, confirmacao). Devolve (email, senha)."""
        r = self.rel
        n = self.nav(f'cadastro-{rotulo}')
        email, senha = self.email(rotulo), self.senha()
        st, _, _ = n.req('POST', '/api/v1/signup', {'email': email}, csrf=self.pre(n))
        if conferir:
            r.confere('cadastro: pedido', 'API', st == 202, 202, st)
        m = self.caixa.espera('IDENTITY_SIGNUP_CONFIRMATION', email)
        if conferir:
            r.confere('cadastro: mensagem de confirmacao com token, para a titular', 'CAIXA', bool(m and m.get('token')), 'token presente', 'sim' if m and m.get('token') else 'nao')
        if not (m and m.get('token')):
            raise RuntimeError('confirmacao de cadastro nao chegou a caixa')
        st, _, cks = n.req('POST', '/api/v1/signup/confirm', {'token': m['token'], 'password': senha, 'displayName': f'Titular {rotulo}'},
                           csrf=self.pre(n))
        if conferir:
            r.confere('cadastro: confirmacao pela titular', 'API', st == 204, 204, st)
            r.confere('cadastro: nenhuma sessao automatica (sem cookie de sessao de identidade)', 'API',
                      '+__Host-nekkora_id' not in cks, 'sem +__Host-nekkora_id', ','.join(cks) or 'nenhum')
            st2, _ = self.sessao_ok(n)
            r.confere('cadastro: GET /me/session sem sessao', 'API', st2 == 401, 401, st2)
            st3, _, _ = n.req('POST', '/api/v1/signup/confirm', {'token': m['token'], 'password': self.senha(), 'displayName': 'Outro'},
                              csrf=self.pre(n))
            r.confere('cadastro: token de uso unico (reuso recusado)', 'API', st3 == 400, 400, st3)
        return email, senha

    def entra_primeiro(self, email, senha):
        """Primeira entrada: senha, cadastro do fator e confirmacao. Devolve (navegador, csrf da sessao, fator, codigos)."""
        n = self.nav('entrada')
        st, js, _ = n.req('POST', '/api/v1/auth/login', {'email': email, 'password': senha}, csrf=self.pre(n))
        if st != 200 or js.get('status') != 'mfa_enrollment_required':
            raise RuntimeError(f'login inicial: {st} {js and js.get("status")}')
        c = js['csrfToken']
        st, js, _ = n.req('POST', '/api/v1/auth/mfa/enroll/start', csrf=c)
        f = Fator(js['otpauthUri'], self.rel)
        st, js, _ = n.req('POST', '/api/v1/auth/mfa/enroll/confirm', {'code': f.codigo()}, csrf=c)
        if st != 200:
            raise RuntimeError(f'confirmacao do fator: {st}')
        return n, js['csrfToken'], f, js['recoveryCodes']

    def entra(self, email, senha, fator=None, codigo_recuperacao=None):
        """Entrada com fator existente. Devolve (status_final, navegador, corpo)."""
        n = self.nav('entrada')
        st, js, _ = n.req('POST', '/api/v1/auth/login', {'email': email, 'password': senha}, csrf=self.pre(n))
        if st != 200:
            return st, n, js
        corpo = {'recoveryCode': codigo_recuperacao} if codigo_recuperacao else {'code': fator.codigo()}
        st, js2, _ = n.req('POST', '/api/v1/auth/mfa/verify', corpo, csrf=js['csrfToken'])
        return st, n, js2 if js2 is not None else js

    def step_up(self, n, csrf, fator):
        st, _, _ = n.req('POST', '/api/v1/auth/step-up', {'code': fator.codigo()}, csrf=csrf)
        return st

    # -------------------------------------------------------------- J1
    def j1(self):
        email, senha = self.cria_identidade('j1')
        r = self.rel
        est = self.banco.q("SELECT status || '|' || (email_verified_at IS NOT NULL) FROM identities WHERE email_normalized = lower(:'e')", ('e', email))
        r.confere('identidade ACTIVE e e-mail verificado depois da confirmacao', 'BANCO', est == '2|true', '2|true', est)
        self.estado['j1'] = (email, senha)

    # -------------------------------------------------------------- J2
    def j2(self):
        r = self.rel
        email, senha = self.estado.get('j1') or self.cria_identidade('j2', conferir=False)
        n = self.nav('j2')
        st, _, _ = n.req('POST', '/api/v1/auth/login', {'email': email, 'password': senha + 'x'}, csrf=self.pre(n))
        r.confere('senha errada: 401 generico', 'API', st == 401, 401, st)
        st, js, cks = n.req('POST', '/api/v1/auth/login', {'email': email, 'password': senha}, csrf=self.pre(n))
        r.confere('senha certa, identidade sem fator: mfa_enrollment_required', 'API', st == 200 and js.get('status') == 'mfa_enrollment_required',
                  '200 mfa_enrollment_required', f'{st} {js and js.get("status")}')
        c = js['csrfToken']
        st, js, _ = n.req('POST', '/api/v1/auth/mfa/enroll/start', csrf=c)
        r.confere('cadastro do primeiro fator: otpauth', 'API', st == 200 and js['otpauthUri'].startswith('otpauth://totp/'), 200, st)
        f = Fator(js['otpauthUri'], r)
        st, _, _ = n.req('POST', '/api/v1/auth/mfa/enroll/confirm', {'code': f.errado()}, csrf=c)
        r.confere('confirmacao com codigo errado: 401', 'API', st == 401, 401, st)
        st, js, cks = n.req('POST', '/api/v1/auth/mfa/enroll/confirm', {'code': f.codigo()}, csrf=c)
        r.confere('confirmacao do fator emite a sessao (cookie) com codigos de recuperacao', 'API',
                  st == 200 and '+__Host-nekkora_id' in cks and len(js.get('recoveryCodes', [])) >= 8 and js.get('restricted') is False,
                  '200 +cookie codigos restricted=false', f'{st} {",".join(cks)} {len((js or {}).get("recoveryCodes", []))}')
        codigos = js['recoveryCodes']
        st, js = self.sessao_ok(n)
        r.confere('sessao de identidade valida e nao restrita', 'API', st == 200 and js['restricted'] is False, '200 restricted=false', st)
        st, _, _ = n.req('POST', '/api/v1/auth/logout', csrf=js['csrfToken'])
        r.confere('logout', 'API', st == 204, 204, st)
        st, _ = self.sessao_ok(n)
        r.confere('depois do logout: sem sessao', 'API', st == 401, 401, st)
        st, n2, js = self.entra(email, senha, fator=f)
        r.confere('nova entrada: senha + TOTP do fator confirmado', 'API', st == 200 and js.get('restricted') is False, '200 restricted=false', st)
        # replay do MESMO passo numa entrada nova
        n3 = self.nav('j2-replay')
        st, js3, _ = n3.req('POST', '/api/v1/auth/login', {'email': email, 'password': senha}, csrf=self.pre(n3))
        repetido = totp(f.segredo, f.ultimo)
        st, _, _ = n3.req('POST', '/api/v1/auth/mfa/verify', {'code': repetido}, csrf=js3['csrfToken'])
        r.confere('passo TOTP ja aceito nao serve de novo (anti-replay)', 'API', st == 401, 401, st)
        self.estado['j2'] = dict(email=email, senha=senha, fator=f, codigos=codigos, nav=n2, csrf=js['csrfToken'])

    # -------------------------------------------------------------- J3
    def j3(self):
        r = self.rel
        s = self.estado['j2']
        st, n, js = self.entra(s['email'], s['senha'], codigo_recuperacao=s['codigos'][0])
        r.confere('entrada por codigo de recuperacao: sessao RESTRITA', 'API', st == 200 and js.get('restricted') is True, '200 restricted=true', f'{st} {js and js.get("restricted")}')
        c = js['csrfToken']
        st, _ = self.sessao_ok(s['nav'])
        r.confere('demais sessoes revogadas no uso do codigo', 'API', st == 401, 401, st)
        st, _, _ = n.req('GET', '/api/v1/me/organizations', origem=False)
        r.confere('sessao restrita nao lista organizacoes', 'API', st == 403, 403, st)
        st = self.step_up(n, c, s['fator'])
        r.confere('sessao restrita nao faz step-up', 'API', st == 403, 403, st)
        st, js, _ = n.req('POST', '/api/v1/auth/mfa/enroll/start', csrf=c)
        r.confere('substituicao do fator na sessao restrita: otpauth novo', 'API', st == 200, 200, st)
        novo = Fator(js['otpauthUri'], r)
        st, _, _ = n.req('POST', '/api/v1/auth/mfa/enroll/confirm', {'code': s['fator'].codigo()}, csrf=c)
        r.confere('confirmar com o fator ANTIGO: recusado', 'API', st == 403, 403, st)
        st, js, _ = n.req('POST', '/api/v1/auth/mfa/enroll/confirm', {'code': novo.codigo()}, csrf=c)
        r.confere('confirmar com o fator NOVO: restricao levantada, codigos novos', 'API',
                  st == 200 and js.get('restricted') is False and len(js.get('recoveryCodes', [])) >= 8, '200 restricted=false', st)
        codigos = js['recoveryCodes']
        st, js = self.sessao_ok(n)
        r.confere('mesma sessao, agora sem restricao', 'API', st == 200 and js['restricted'] is False, '200 restricted=false', st)
        c = js['csrfToken']
        st, _, _ = n.req('GET', '/api/v1/me/organizations', origem=False)
        r.confere('sessao liberada lista organizacoes', 'API', st == 200, 200, st)
        st = self.step_up(n, c, s['fator'])
        r.confere('TOTP do fator antigo nao vale mais (step-up)', 'API', st == 403, 403, st)
        st = self.step_up(n, c, novo)
        r.confere('TOTP do fator novo vale (step-up)', 'API', st == 204, 204, st)
        st, _, _ = self.entra(s['email'], s['senha'], codigo_recuperacao=s['codigos'][1])
        r.confere('codigos antigos invalidados pela substituicao', 'API', st == 401, 401, st)
        for tipo in ('NOTICE_RECOVERY_CODE_USED', 'NOTICE_MFA_REPLACED'):
            m = self.caixa.espera(tipo, s['email'], prazo=30)
            r.confere(f'aviso {tipo} a titular', 'CAIXA', m is not None, 'presente', 'sim' if m else 'nao')
        s.update(fator=novo, codigos=codigos, nav=n, csrf=c)

    # -------------------------------------------------------------- J4
    def j4(self):
        r = self.rel
        s = self.estado['j2']
        st, n, js = self.entra(s['email'], s['senha'], fator=s['fator'])
        if st != 200:
            raise RuntimeError(f'entrada J4: {st}')
        c = js['csrfToken']
        st, nb, jb = self.entra(s['email'], s['senha'], fator=s['fator'])
        r.confere('segunda sessao (outro navegador)', 'API', st == 200, 200, st)
        nova = self.senha()
        st, _, _ = n.req('POST', '/api/v1/auth/password', {'currentPassword': s['senha'], 'newPassword': nova}, csrf=c)
        r.confere('troca de senha SEM step-up: recusada', 'API', st == 403, 403, st)
        st = self.step_up(n, c, s['fator'])
        r.confere('step-up com TOTP', 'API', st == 204, 204, st)
        st, _, _ = n.req('POST', '/api/v1/auth/password', {'currentPassword': s['senha'] + 'x', 'newPassword': nova}, csrf=c)
        r.confere('troca com senha atual errada: recusada', 'API', st == 403, 403, st)
        st, _, _ = n.req('POST', '/api/v1/auth/password', {'currentPassword': s['senha'], 'newPassword': nova}, csrf=c)
        r.confere('troca de senha com step-up', 'API', st == 204, 204, st)
        st, _ = self.sessao_ok(nb)
        r.confere('a outra sessao foi revogada', 'API', st == 401, 401, st)
        st, _ = self.sessao_ok(n)
        r.confere('a sessao da requisicao continua', 'API', st == 200, 200, st)
        st, _, _ = self.entra(s['email'], s['senha'], fator=s['fator'])
        r.confere('senha antiga nao entra', 'API', st == 401, 401, st)
        s['senha'] = nova
        st, js, _ = n.req('POST', '/api/v1/auth/mfa/recovery-codes', csrf=c)
        r.confere('regeneracao dos codigos (step-up ainda valido)', 'API', st == 200 and len(js.get('recoveryCodes', [])) >= 8, 200, st)
        antigos, s['codigos'] = s['codigos'], js['recoveryCodes']
        st, _, _ = self.entra(s['email'], s['senha'], codigo_recuperacao=antigos[0])
        r.confere('conjunto anterior de codigos invalidado', 'API', st == 401, 401, st)
        for tipo in ('NOTICE_PASSWORD_CHANGED', 'NOTICE_RECOVERY_CODES_REGENERATED'):
            m = self.caixa.espera(tipo, s['email'], prazo=30)
            r.confere(f'aviso {tipo} a titular', 'CAIXA', m is not None, 'presente', 'sim' if m else 'nao')
        fator = self.banco.q("SELECT count(*) FROM identity_mfa_factors f JOIN identities i USING (identity_id) WHERE i.email_normalized = lower(:'e') AND f.confirmed_at IS NOT NULL AND f.disabled_at IS NULL", ('e', s['email']))
        r.confere('MFA intacto depois das trocas (um fator confirmado e ativo)', 'BANCO', fator == '1', 1, fator)
        s.update(nav=n, csrf=c)

    # -------------------------------------------------------------- J5
    def j5(self):
        r = self.rel
        s = self.estado['j2']
        n = self.nav('j5')
        st, _, _ = n.req('POST', '/api/v1/auth/password-reset/request', {'email': self.email('desconhecido')}, csrf=self.pre(n))
        r.confere('pedido para e-mail desconhecido: 202 uniforme', 'API', st == 202, 202, st)
        st, _, _ = n.req('POST', '/api/v1/auth/password-reset/request', {'email': s['email']}, csrf=self.pre(n))
        r.confere('pedido da titular: 202', 'API', st == 202, 202, st)
        m = self.caixa.espera('PASSWORD_RECOVERY', s['email'])
        r.confere('mensagem de recuperacao com token para a titular', 'CAIXA', bool(m and m.get('token')), 'token', 'sim' if m and m.get('token') else 'nao')
        nova = self.senha()
        st, _, cks = n.req('POST', '/api/v1/auth/password-reset/confirm', {'token': m['token'], 'newPassword': nova}, csrf=self.pre(n))
        r.confere('confirmacao: senha trocada, nenhuma sessao criada', 'API', st == 204 and '+__Host-nekkora_id' not in cks, '204 sem cookie', f'{st} {",".join(cks)}')
        st, _, _ = n.req('POST', '/api/v1/auth/password-reset/confirm', {'token': m['token'], 'newPassword': self.senha()}, csrf=self.pre(n))
        r.confere('token de recuperacao de uso unico', 'API', st == 400, 400, st)
        st, _ = self.sessao_ok(s['nav'])
        r.confere('todas as sessoes de identidade revogadas', 'API', st == 401, 401, st)
        n2 = self.nav('j5-entrada')
        st, js, _ = n2.req('POST', '/api/v1/auth/login', {'email': s['email'], 'password': nova}, csrf=self.pre(n2))
        r.confere('MFA preservado: a nova entrada pede o fator (nao o cadastro)', 'API', st == 200 and js.get('status') == 'mfa_required',
                  '200 mfa_required', f'{st} {js and js.get("status")}')
        st, js2, _ = n2.req('POST', '/api/v1/auth/mfa/verify', {'code': s['fator'].codigo()}, csrf=js['csrfToken'])
        r.confere('entrada com a senha nova e o mesmo fator', 'API', st == 200, 200, st)
        s.update(senha=nova, nav=n2, csrf=js2['csrfToken'])

    # -------------------------------------------------------------- J6
    def j6(self):
        r = self.rel
        email, senha = self.cria_identidade('j6', conferir=False)
        n, c, f, _ = self.entra_primeiro(email, senha)
        st, js, _ = n.req('GET', '/api/v1/me/organizations', origem=False)
        r.confere('identidade nova: lista vazia e canCreateOrganization', 'API',
                  st == 200 and js['organizations'] == [] and js['canCreateOrganization'] is True, '200 [] true', f'{st} {js}')
        nome = f'Organizacao API {self.sufixo}'
        st, _, _ = n.req('POST', '/api/v1/organizations', {'name': nome}, csrf=c)
        r.confere('criacao sem step-up: recusada', 'API', st == 403, 403, st)
        st = self.step_up(n, c, f)
        st, js, _ = n.req('POST', '/api/v1/organizations', {'name': nome}, csrf=c)
        ok = st == 201 and js and re.fullmatch(r'[0-9a-f-]{36}', js.get('organizationId', ''))
        r.confere('criacao com step-up: 201', 'API', bool(ok), 201, st)
        org = js['organizationId']
        st, js, _ = n.req('GET', '/api/v1/me/organizations', origem=False)
        item = next((o for o in (js or {}).get('organizations', []) if o['organizationId'] == org), None)
        r.confere('a organizacao criada aparece com ORG_ADMIN', 'API', bool(item and item['roles'] == ['ORG_ADMIN']), "['ORG_ADMIN']", item and item['roles'])
        st, js, cks = n.req('POST', '/api/v1/sessions/organization', {'organizationId': org}, csrf=c)
        r.confere('troca pela sessao de organizacao', 'API', st == 200 and js['status'] == 'sessao_criada' and '+__Host-nekkora_session' in cks,
                  'sessao_criada +cookie', f'{st} {js and js.get("status")}')
        st, me, _ = n.req('GET', f'/api/v1/o/{org}/me', origem=False)
        r.confere('GET /o/{org}/me na sessao de organizacao', 'API', st == 200 and me['organizationId'] == org, 200, st)
        st, js, _ = n.req('POST', '/api/v1/sessions/organization', {'organizationId': str(uuid.uuid4())}, csrf=c)
        r.confere('organizacao alheia/inexistente: sem_acesso', 'API', st == 200 and js['status'] == 'sem_acesso', 'sem_acesso', js and js.get('status'))
        lim = self.banco.q("SELECT string_agg(c || ':' || coalesce((SELECT limit_mode || '/' || coalesce(limit_value::text, '-') FROM get_effective_resource_limit(:'o'::uuid, c)), 'ausente'), ' ' ORDER BY c) FROM generate_series(1, 5) c", ('o', org))
        r.confere('capacidade inicial: 1-3 FINITE 0 (sem execucao), 4-5 UNLIMITED', 'BANCO', lim == '1:1/0 2:1/0 3:1/0 4:2/- 5:2/-', '1:1/0 2:1/0 3:1/0 4:2/- 5:2/-', lim)
        sem_cred = self.banco.q("SELECT count(*) FROM user_credentials uc JOIN users u USING (user_id) WHERE u.organization_id = :'o'::uuid", ('o', org))
        r.confere('conta criada pela identidade sem credencial local', 'BANCO', sem_cred == '0', 0, sem_cred)
        # controle POSITIVO da varredura (nao secreto): o id da organizacao aparece no log de acesso do gateway (/o/{org}/me)
        with open(os.path.join(os.path.dirname(self.a.vistos), 'controle.txt'), 'a', encoding='utf-8') as h:
            h.write(org + chr(10))
        self.estado['j6'] = dict(email=email, senha=senha, fator=f, nav=n, csrf=c, org=org)

    # -------------------------------------------------------------- J7
    def phc(self, senha):
        rr = subprocess.run([self.a.bash, '-c', self.a.phc], input=(senha + '\n').encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
        out = rr.stdout.decode().strip().splitlines()
        if rr.returncode != 0 or not out or not out[-1].startswith('$argon2id$'):
            raise RuntimeError('PhcTool falhou')
        return out[-1]

    def legada_entra_primeiro(self, org, email, senha):
        n = self.nav('legada')
        st, js, _ = n.req('POST', f'/api/v1/o/{org}/auth/login', {'email': email, 'password': senha}, csrf=self.pre(n))
        if st != 200:
            return st, n, None, None
        c = js['csrfToken']
        st, js, _ = n.req('POST', f'/api/v1/o/{org}/auth/mfa/enroll/start', csrf=c)
        f = Fator(js['otpauthUri'], self.rel)
        st, js, _ = n.req('POST', f'/api/v1/o/{org}/auth/mfa/enroll/confirm', {'code': f.codigo()}, csrf=c)
        return st, n, f, js

    def j7(self):
        r = self.rel
        email_adm, email_mb = self.email('legada-adm'), self.email('legada-membro')
        s_adm, s_mb = self.senha(), self.senha()
        ids = {k: str(uuid.uuid4()) for k in ('org', 'adm', 'membro', 'm_adm', 'm_membro')}
        out = self.banco.q(open(self.a.sql_semente, encoding='utf-8').read(),
                           ('org', ids['org']), ('nome', f'Legada API {self.sufixo}'), ('adm', ids['adm']), ('membro', ids['membro']),
                           ('m_adm', ids['m_adm']), ('m_membro', ids['m_membro']), ('email_adm', email_adm), ('email_membro', email_mb),
                           ('phc_adm', self.phc(s_adm)), ('phc_membro', self.phc(s_mb)))
        r.confere('organizacao legada com duas contas antigas (semear_api_v1.sql)', 'PREP', out.splitlines()[-1:] == ['SEMEADURA_API_V1_OK'], 'SEMEADURA_API_V1_OK', out.splitlines()[-1:])
        org = ids['org']
        st, nl, fl, js = self.legada_entra_primeiro(org, email_adm, s_adm)
        r.confere('conta antiga: primeiro acesso pela entrada local (fator cadastrado pela API)', 'API', st == 200, 200, st)
        # identidade com o MESMO e-mail da conta antiga
        n = self.nav('j7-cadastro')
        st, _, _ = n.req('POST', '/api/v1/signup', {'email': email_adm}, csrf=self.pre(n))
        m = self.caixa.espera('IDENTITY_SIGNUP_CONFIRMATION', email_adm)
        s_id = self.senha()
        st, _, _ = n.req('POST', '/api/v1/signup/confirm', {'token': m['token'], 'password': s_id, 'displayName': 'Titular legada'}, csrf=self.pre(n))
        r.confere('identidade com o e-mail da conta antiga', 'API', st == 204, 204, st)
        ni, ci, fi, _ = self.entra_primeiro(email_adm, s_id)
        st, _, _ = ni.req('POST', '/api/v1/links/start', {'organizationId': org, 'password': s_adm + 'x', 'code': fl.codigo()}, csrf=ci)
        r.confere('vinculacao etapa 1 com prova errada da conta antiga: 401 unico', 'API', st == 401, 401, st)
        st, _, cks = ni.req('POST', '/api/v1/links/start', {'organizationId': org, 'password': s_adm, 'code': fl.codigo()}, csrf=ci)
        r.confere('vinculacao etapa 1: prova da conta antiga (senha + TOTP dela)', 'API', st == 204 and '+__Host-nekkora_link' in cks, '204 +link', f'{st} {",".join(cks)}')
        st, _, cks = ni.req('POST', '/api/v1/links/confirm', {'organizationId': org}, csrf=ci)
        r.confere('vinculacao etapa 2 sem step-up da identidade: recusada e o cookie do desafio expira', 'API',
                  st == 403 and '-__Host-nekkora_link' in cks, '403 -link', f'{st} {",".join(cks)}')
        st = self.step_up(ni, ci, fi)
        r.confere('step-up da identidade', 'API', st == 204, 204, st)
        # o desafio anterior nao foi consumido no banco, mas o navegador o perdeu: a etapa 1 e refeita
        st, _, cks = ni.req('POST', '/api/v1/links/start', {'organizationId': org, 'password': s_adm, 'code': fl.codigo()}, csrf=ci)
        r.confere('vinculacao etapa 1 refeita', 'API', st == 204 and '+__Host-nekkora_link' in cks, '204 +link', st)
        st, _, _ = ni.req('POST', '/api/v1/links/confirm', {'organizationId': org}, csrf=ci)
        r.confere('vinculacao etapa 2 com step-up: vinculada', 'API', st == 204, 204, st)
        st, _, _ = nl.req('GET', f'/api/v1/o/{org}/me', origem=False)
        r.confere('sessao local antiga revogada no ato', 'API', st == 401, 401, st)
        nx = self.nav('j7-local')
        st, _, _ = nx.req('POST', f'/api/v1/o/{org}/auth/login', {'email': email_adm, 'password': s_adm}, csrf=self.pre(nx))
        r.confere('entrada local da conta vinculada fechada (401 generico)', 'API', st == 401, 401, st)
        st, js, _ = ni.req('GET', '/api/v1/me/organizations', origem=False)
        r.confere('a organizacao legada aparece para a identidade', 'API', any(o['organizationId'] == org for o in (js or {}).get('organizations', [])), 'presente', st)
        st, js, cks = ni.req('POST', '/api/v1/sessions/organization', {'organizationId': org}, csrf=ci)
        r.confere('troca pela sessao da organizacao legada (sessao vinculada)', 'API', st == 200 and js['status'] == 'sessao_criada', 'sessao_criada', js and js.get('status'))
        m = self.caixa.espera('NOTICE_IDENTITY_LINKED', email_adm, prazo=30)
        r.confere('aviso de vinculacao a titular', 'CAIXA', m is not None, 'presente', 'sim' if m else 'nao')
        self.estado['j7'] = dict(org=org, ids=ids, nav=ni, csrf_org=js['csrfToken'], csrf_id=ci, fator_id=fi, email_mb=email_mb, senha_mb=s_mb)

    # -------------------------------------------------------------- J8
    def j8(self):
        r = self.rel
        s = self.estado['j7']
        org, ids = s['org'], s['ids']
        st, nm, fm, js = self.legada_entra_primeiro(org, s['email_mb'], s['senha_mb'])
        r.confere('membro: sessao local ativa', 'API', st == 200, 200, st)
        st, _, _ = nm.req('GET', f'/api/v1/o/{org}/me', origem=False)
        r.confere('membro acessa /o/{org}/me', 'API', st == 200, 200, st)
        na = s['nav']
        st, _, _ = na.req('POST', f'/api/v1/o/{org}/users/{ids["membro"]}/suspend', csrf=s['csrf_org'])
        r.confere('administradora (sessao vinculada) suspende o membro pela API', 'API', st == 204, 204, st)
        st, _, _ = nm.req('GET', f'/api/v1/o/{org}/me', origem=False)
        r.confere('sessao do membro suspenso cai na requisicao seguinte', 'API', st == 401, 401, st)
        nx = self.nav('j8-relogin')
        st, _, _ = nx.req('POST', f'/api/v1/o/{org}/auth/login', {'email': s['email_mb'], 'password': s['senha_mb']}, csrf=self.pre(nx))
        r.confere('membro suspenso nao entra (401 generico)', 'API', st == 401, 401, st)
        st, _, _ = na.req('POST', f'/api/v1/o/{org}/users/{ids["membro"]}/reactivate', csrf=s['csrf_org'])
        r.confere('reativacao pela API', 'API', st == 204, 204, st)
        st, nm2, _ = self._legada_entra(org, s['email_mb'], s['senha_mb'], fm)
        r.confere('membro reativado volta a entrar (sessoes antigas nao sao restauradas)', 'API', st == 200, 200, st)
        # retirada do vinculo do membro (REVOKED): a API v1 nao tem a rota; estado preparado no banco, reacao pela API
        out = self.banco.q("BEGIN; SELECT set_config('nekkora.organization_id', :'o', true) IS NOT NULL AS c \\gset\n"
                           "UPDATE organization_memberships SET status = 4 WHERE organization_id = :'o' AND membership_id = :'m'; COMMIT;\n"
                           "SELECT status FROM organization_memberships WHERE membership_id = :'m';",
                           ('o', org), ('m', ids['m_membro']))
        r.confere('vinculo do membro retirado (estado preparado no banco: a API v1 nao retira vinculo)', 'PREP',
                  out.splitlines()[-1:] == ['4'], 'status 4 (REVOKED)', out.splitlines()[-1:])
        st, _, _ = nm2.req('GET', f'/api/v1/o/{org}/me', origem=False)
        r.confere('sessao do membro sem vinculo cai na requisicao seguinte', 'API', st == 401, 401, st)
        # identidade: logout derruba a sessao de organizacao derivada
        st, _, _ = na.req('POST', '/api/v1/auth/logout', csrf=s['csrf_id'])
        st, _, _ = na.req('GET', f'/api/v1/o/{org}/me', origem=False)
        r.confere('logout da identidade derruba a sessao de organizacao derivada', 'API', st == 401, 401, st)
        # estados que a API v1 nao altera (operacao/banco): retirada do vinculo e suspensao da organizacao, so a REACAO pela API
        s6 = self.estado.get('j6')
        if s6:
            n6, c6, o6 = s6['nav'], s6['csrf'], s6['org']
            st, js, _ = n6.req('POST', '/api/v1/sessions/organization', {'organizationId': o6}, csrf=c6)
            antes = st == 200 and js['status'] == 'sessao_criada'
            out = self.banco.q("BEGIN; SELECT set_config('nekkora.organization_id', :'o', true) IS NOT NULL AS c \\gset\nUPDATE organizations SET status = 2 WHERE organization_id = :'o'; COMMIT;", ('o', o6))
            r.confere('organizacao suspensa (estado preparado no banco: a API v1 nao suspende organizacao)', 'PREP', antes, 'sessao_criada antes', antes)
            st, _, _ = n6.req('GET', f'/api/v1/o/{o6}/me', origem=False)
            r.confere('sessao de organizacao ja emitida cai com a organizacao suspensa', 'API', st == 401, 401, st)
            st, js, _ = n6.req('GET', '/api/v1/me/organizations', origem=False)
            r.confere('organizacao suspensa sai da lista', 'API', st == 200 and all(o['organizationId'] != o6 for o in js['organizations']), 'ausente', st)
            st, js, _ = n6.req('POST', '/api/v1/sessions/organization', {'organizationId': o6}, csrf=c6)
            r.confere('troca recusada (sem_acesso, resposta unica)', 'API', st == 200 and js['status'] == 'sem_acesso', 'sem_acesso', js and js.get('status'))
            st, _ = self.sessao_ok(n6)
            r.confere('a sessao de identidade continua', 'API', st == 200, 200, st)
            self.banco.q("BEGIN; SELECT set_config('nekkora.organization_id', :'o', true) IS NOT NULL AS c \\gset\nUPDATE organizations SET status = 1 WHERE organization_id = :'o'; COMMIT;", ('o', o6))
            r.nota('organizacao do J6 reativada no banco ao fim do caso (preparacao declarada)')

    def _legada_entra(self, org, email, senha, fator):
        n = self.nav('legada-entra')
        st, js, _ = n.req('POST', f'/api/v1/o/{org}/auth/login', {'email': email, 'password': senha}, csrf=self.pre(n))
        if st != 200:
            return st, n, js
        st, js2, _ = n.req('POST', f'/api/v1/o/{org}/auth/mfa/verify', {'code': fator.codigo()}, csrf=js['csrfToken'])
        return st, n, js2

    # -------------------------------------------------------------- J10
    def j10(self):
        r = self.rel
        n = self.nav('j10')
        st, _, _ = n.req('POST', '/api/v1/signup', {'email': self.email('j10'), 'password': 'x' * 14}, csrf=self.pre(n))
        r.confere('cadastro com senha no pedido: 400, sem efeito', 'API', st == 400, 400, st)
        st, _, _ = n.req('POST', '/api/v1/signup', {'email': self.email('j10b')}, csrf=None)
        r.confere('sem CSRF: 403', 'API', st == 403, 403, st)
        st, _, _ = n.req('POST', '/api/v1/signup/confirm', {'token': secrets.token_urlsafe(32), 'password': self.senha(), 'displayName': 'X'}, csrf=self.pre(n))
        r.confere('token desconhecido: 400, sem efeito', 'API', st == 400, 400, st)
        # concorrencia: o MESMO token de recuperacao confirmado por dois pedidos ao mesmo tempo -> exatamente um troca
        s = self.estado.get('j2')
        if s:
            ev_sql = ("SELECT count(*) FROM identity_events e JOIN identities i USING (identity_id) "
                      "WHERE i.email_normalized = lower(:'e') AND e.event_type = 17")
            ev0 = int(self.banco.q(ev_sql, ('e', s['email'])))
            st, _, _ = n.req('POST', '/api/v1/auth/password-reset/request', {'email': s['email']}, csrf=self.pre(n))
            m = self.caixa.espera('PASSWORD_RECOVERY', s['email'])
            novas = [self.senha(), self.senha()]
            navs = [self.nav('j10-a'), self.nav('j10-b')]
            csrfs = [self.pre(x) for x in navs]
            res = [None, None]
            largada = threading.Barrier(2)

            def vai(i):
                largada.wait()
                res[i] = navs[i].req('POST', '/api/v1/auth/password-reset/confirm', {'token': m['token'], 'newPassword': novas[i]}, csrf=csrfs[i])[0]
            ts = [threading.Thread(target=vai, args=(i,)) for i in (0, 1)]
            [t.start() for t in ts]
            [t.join() for t in ts]
            r.confere('mesmo token confirmado em paralelo: exatamente uma troca (204) e uma recusa (400)', 'API', sorted(res) == [204, 400], '[204, 400]', sorted(res))
            vencedora = novas[res.index(204)] if 204 in res else None
            ev = int(self.banco.q(ev_sql, ('e', s['email']))) - ev0
            r.confere('exatamente um evento 17 (recuperacao concluida) pelas duas confirmacoes paralelas', 'BANCO', ev == 1, 1, ev)
            if vencedora:
                st, _, _ = self.entra(s['email'], vencedora, fator=s['fator'])
                r.confere('a senha que venceu e a que vale', 'API', st == 200, 200, st)
                s['senha'] = vencedora

    # -------------------------------------------------------------- execucao
    def executa(self, nomes):
        for j in nomes:
            self.rel.atual = j
            t0 = time.time()
            try:
                getattr(self, j.lower())()
            except Exception as e:  # noqa: BLE001 - a jornada para; o motivo vai para a evidencia sem segredos
                self.rel.confere('execucao da jornada', '-', False, 'sem excecao', f'{type(e).__name__}: {str(e)[:160]}')
            self.rel.nota(f'duracao {time.time() - t0:.1f} s')
        with open(os.path.join(self.a.evid, 'resumo.txt'), 'w', encoding='utf-8') as h:
            h.write(f'casos={self.rel.total} falhas={self.rel.falhas} jornadas={",".join(nomes)} alvo={self.alvo.origem}\n')
        print(f'RESULTADO: {self.rel.total} casos, {self.rel.falhas} falha(s)')
        return 1 if self.rel.falhas else 0


def main():
    ap = argparse.ArgumentParser()
    for x in ('--alvo', '--ca', '--evid', '--vistos', '--psql', '--phc', '--caixa', '--sql-semente'):
        ap.add_argument(x, required=True)
    ap.add_argument('--bash', default=os.environ.get('HOMOLOG_BASH') or r'C:\Program Files\Git\bin\bash.exe')
    ap.add_argument('--somente', default='')
    a = ap.parse_args()
    nomes = [x for x in a.somente.split(',') if x] or JORNADAS
    for x in nomes:
        if x not in JORNADAS:
            raise SystemExit(f'jornada desconhecida: {x}')
    # dependencias entre jornadas (estado em memoria): J3-J5 e J10 usam a identidade do J2; J8 usa a do J7
    exig = {'J3': 'J2', 'J4': 'J2', 'J5': 'J2', 'J8': 'J7'}
    for x in nomes:
        if x in exig and exig[x] not in nomes:
            raise SystemExit(f'{x} exige {exig[x]} na mesma execucao')
    sys.exit(Jornadas(a).executa(nomes))


if __name__ == '__main__':
    main()
