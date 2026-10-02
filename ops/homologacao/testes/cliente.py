"""Cliente da homologacao (H05 e H07): fala SO com o gateway HTTPS, como a equipe falaria.

Verifica o certificado contra a CA da homologacao e o NOME da origem (SNI), mesmo conectando em 127.0.0.1. Guarda
cookies como um navegador (inclusive __Host-), envia Origin e o token CSRF do contexto. Todo valor secreto sintetico
visto (senhas, cookies, CSRF, segredo TOTP, codigos de recuperacao) vai para SEGREDOS_VISTOS (arquivo FORA do Git),
usado pela H08. Estado entre etapas (cookies, passos TOTP) em ESTADO, tambem fora do Git.

Uso: python cliente.py <cenario> <fixture.json>   (cenarios: h05, h07_prep, h07_redis_fora, h07_r7)
Saida: uma linha por verificacao "OK|FALHA <caso> esperado=<x> obtido=<y>"; codigo de saida 1 se alguma falhar.
"""
import base64
import hashlib
import hmac
import http.client
import json
import os
import socket
import ssl
import struct
import sys
import time
import urllib.parse

HOST = os.environ['HOMOLOG_HOST']
PORTA = int(os.environ['HOMOLOG_PORTA'])
CA = os.environ['HOMOLOG_CA']
SEGREDOS_VISTOS = os.environ['HOMOLOG_SEGREDOS_VISTOS']
ESTADO = os.environ['HOMOLOG_ESTADO']
ORIGEM = f'https://{HOST}:{PORTA}'
falhas = 0


def confere(caso, esperado, obtido):
    global falhas
    ok = esperado(obtido) if callable(esperado) else obtido == esperado
    if not ok:
        falhas += 1
    desc = (esperado.__doc__ or 'condicao') if callable(esperado) else esperado
    print(f'{"OK   " if ok else "FALHA"} {caso} esperado={desc} obtido={obtido}', flush=True)
    return ok


def visto(*valores):
    with open(SEGREDOS_VISTOS, 'a', encoding='utf-8') as h:
        for v in valores:
            if v and len(v) >= 8:
                h.write(v + '\n')


class Conexao(http.client.HTTPSConnection):
    def connect(self):
        sock = socket.create_connection(('127.0.0.1', PORTA), timeout=30)
        self.sock = self._context.wrap_socket(sock, server_hostname=HOST)


CTX = ssl.create_default_context(cafile=CA)
CTX.minimum_version = ssl.TLSVersion.TLSv1_2


class Navegador:
    def __init__(self):
        self.cookies = {}
        self.csrf = None
        self.origem = ORIGEM

    def req(self, metodo, caminho, corpo=None):
        c = Conexao(HOST, PORTA, context=CTX, timeout=30)
        cab = {'Host': f'{HOST}:{PORTA}', 'Accept': 'application/json'}
        if self.cookies:
            cab['Cookie'] = '; '.join(f'{k}={v}' for k, v in self.cookies.items())
        if self.origem:
            cab['Origin'] = self.origem
        if self.csrf:
            cab['X-CSRF-Token'] = self.csrf
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
                visto(valor.strip())
        c.close()
        try:
            js = json.loads(texto) if texto else {}
        except ValueError:
            js = {}
        return r.status, js, setc, dict(r.headers)


def totp(segredo_b32, passo):
    chave = base64.b32decode(segredo_b32 + '=' * (-len(segredo_b32) % 8))
    d = hmac.new(chave, struct.pack('>Q', passo), hashlib.sha1).digest()
    o = d[-1] & 0x0F
    return f'{(struct.unpack(">I", d[o:o + 4])[0] & 0x7FFFFFFF) % 1000000:06d}'


def estado():
    return json.load(open(ESTADO, encoding='utf-8')) if os.path.exists(ESTADO) else {'segredos': {}, 'passos': {}, 'sessoes': {}}


def salva(e):
    with open(ESTADO, 'w', encoding='utf-8') as h:
        json.dump(e, h)


def proximo_codigo(e, usuario):
    """Codigo TOTP de um passo ainda nao usado pela conta (anti-replay da 014); espera o proximo passo se preciso."""
    while True:
        agora = int(time.time())
        passo = agora // 30
        inicio = passo - 1 if agora % 30 < 25 else passo
        cand = max(inicio, e['passos'].get(usuario, -1) + 1)
        if cand <= passo + 1:
            e['passos'][usuario] = cand
            return totp(e['segredos'][usuario], cand)
        time.sleep(1)


def atributos_host(setc, nome, samesite):
    sc = next((s for s in setc if s.startswith(nome + '=')), '')
    return all(x in sc for x in ('; Secure', '; HttpOnly', '; Path=/', f'SameSite={samesite}')) and 'domain=' not in sc.lower()


def base(org):
    return f'/api/v1/o/{org}'


def login(e, org, u, novo=False):
    """Login completo; com novo=True faz o cadastro do MFA (primeiro acesso)."""
    n = Navegador()
    st, js, setc, _ = n.req('GET', '/api/v1/csrf')
    n.csrf = js.get('csrfToken')
    visto(n.csrf)
    st, js, setc, _ = n.req('POST', base(org) + '/auth/login', {'email': u['email'], 'password': u['senha']})
    if st != 200:
        return n, st, js, setc
    n.csrf = js.get('csrfToken')
    visto(n.csrf)
    if js.get('status') == 'mfa_enrollment_required':
        st, js2, _, _ = n.req('POST', base(org) + '/auth/mfa/enroll/start')
        segredo = urllib.parse.parse_qs(urllib.parse.urlparse(js2['otpauthUri']).query)['secret'][0]
        e['segredos'][u['email']] = segredo
        visto(segredo)
        st, js, setc, _ = n.req('POST', base(org) + '/auth/mfa/enroll/confirm', {'code': proximo_codigo(e, u['email'])})
        visto(*js.get('recoveryCodes', []))
    else:
        st, js, setc, _ = n.req('POST', base(org) + '/auth/mfa/verify', {'code': proximo_codigo(e, u['email'])})
    if st == 200:
        n.csrf = js.get('csrfToken')
        visto(n.csrf)
    return n, st, js, setc


def restaura(dados):
    n = Navegador()
    n.cookies = dict(dados['cookies'])
    n.csrf = dados['csrf']
    return n


def guarda(n):
    return {'cookies': n.cookies, 'csrf': n.csrf}


def h05(f):
    e = estado()
    A, B = f['org_a'], f['org_b']
    x, y, i = f['externo_a'], f['externo_b'], f['interno_a']
    visto(x['senha'], y['senha'], i['senha'])
    p = Navegador()
    st, js, setc, _ = p.req('GET', '/api/v1/csrf')
    confere('H05.01 CSRF pre-autenticacao pelo gateway', 200, st)
    confere('H05.02 cookie __Host-nekkora_pre: Secure, HttpOnly, Path=/, SameSite=Strict, sem Domain', True, atributos_host(setc, '__Host-nekkora_pre', 'Strict'))

    nx, st, js, setc = login(e, A, x, novo=True)
    confere('H05.03 externo: primeiro acesso com cadastro de MFA (Platform)', 200, st)
    confere('H05.04 cookie __Host-nekkora_session: Secure, HttpOnly, Path=/, SameSite=Lax, sem Domain', True, atributos_host(setc, '__Host-nekkora_session', 'Lax'))
    confere('H05.05 externo: /me na Platform', 200, nx.req('GET', base(A) + '/me')[0])
    st, js, _, _ = nx.req('GET', f'/portal/api/v1/o/{A}/workspaces')
    ids = [w['workspaceId'] for w in js.get('items', [])]
    confere('H05.06 Portal (outro processo) aceita o cookie: lista de workspaces', 200, st)
    confere('H05.07 so o workspace concedido', [f['ws_concedido']], ids)
    st, js, _, _ = nx.req('GET', f'/portal/api/v1/o/{A}/executions')
    ex = [x_['executionId'] for x_ in js.get('items', [])]
    confere('H05.08 execucoes do workspace concedido', True, f['exec_concedida'] in ex and f['exec_outro_cliente'] not in ex)
    confere('H05.09 detalhe da execucao concedida', 200, nx.req('GET', f'/portal/api/v1/o/{A}/executions/{f["exec_concedida"]}')[0])
    confere('H05.10 execucao de outro cliente da mesma organizacao: 404', 404, nx.req('GET', f'/portal/api/v1/o/{A}/executions/{f["exec_outro_cliente"]}')[0])
    confere('H05.11 organizacao B pela URL com sessao de A (Portal): 401', 401, nx.req('GET', f'/portal/api/v1/o/{B}/workspaces')[0])
    confere('H05.12 organizacao B pela URL com sessao de A (Platform): 401', 401, nx.req('GET', base(B) + '/me')[0])
    confere('H05.13 Portal so GET: POST -> 404', 404, nx.req('POST', f'/portal/api/v1/o/{A}/workspaces')[0])

    ny, st, _, _ = login(e, B, y, novo=True)
    confere('H05.14 externo da organizacao B: login com MFA', 200, st)
    st, js, _, _ = ny.req('GET', f'/portal/api/v1/o/{B}/workspaces')
    confere('H05.15 B ve so o proprio workspace', [f['ws_b']], [w['workspaceId'] for w in js.get('items', [])])
    confere('H05.16 B nao le execucao de A: 401 (org da sessao)', 401, ny.req('GET', f'/portal/api/v1/o/{A}/executions/{f["exec_concedida"]}')[0])
    ni, st, _, _ = login(e, A, i, novo=True)
    confere('H05.17 interno da organizacao A: login com MFA', 200, st)
    confere('H05.18 interno: /me', 200, ni.req('GET', base(A) + '/me')[0])

    antigo = dict(nx.cookies)
    confere('H05.19 logout na Platform', 204, nx.req('POST', base(A) + '/auth/logout')[0])
    velho = Navegador()
    velho.cookies = antigo
    confere('H05.20 cookie apos logout: Portal 401', 401, velho.req('GET', f'/portal/api/v1/o/{A}/workspaces')[0])
    confere('H05.21 cookie apos logout: Platform 401', 401, velho.req('GET', base(A) + '/me')[0])

    nx2, st, js, _ = login(e, A, x)
    confere('H05.22 segundo login (mfa_required + verificacao)', 200, st)
    sem_csrf = Navegador()
    sem_csrf.cookies = dict(nx2.cookies)
    confere('H05.23 revoke-all sem CSRF: 403', 403, sem_csrf.req('POST', base(A) + '/auth/sessions/revoke-all')[0])
    outra = Navegador()
    outra.cookies, outra.csrf, outra.origem = dict(nx2.cookies), nx2.csrf, 'https://atacante.example'
    confere('H05.24 revoke-all com Origin estranho: 403', 403, outra.req('POST', base(A) + '/auth/sessions/revoke-all')[0])
    guardado = dict(nx2.cookies)
    confere('H05.25 revoke-all valido', 204, nx2.req('POST', base(A) + '/auth/sessions/revoke-all')[0])
    velho2 = Navegador()
    velho2.cookies = guardado
    confere('H05.26 apos revoke-all: Portal 401', 401, velho2.req('GET', f'/portal/api/v1/o/{A}/workspaces')[0])
    salva(e)


def h07_prep(f):
    """So a sessao da fase Redis fora (o revoke-all dela revoga todas as sessoes da conta: a fase R7 faz logins novos)."""
    e = estado()
    n, st, _, _ = login(e, f['org_a'], f['interno_a'])
    confere('H07.prep sessao para a fase Redis fora', 200, st)
    e['sessoes']['redis_fora'] = guarda(n)
    salva(e)


def h07_redis_fora(f):
    e = estado()
    A = f['org_a']
    n = restaura(e['sessoes']['redis_fora'])
    st, js, _, _ = Navegador().req('GET', '/api/v1/csrf')
    confere('H07.01 Redis fora: CSRF de pre-autenticacao -> 503', 503, st)
    confere('H07.02 Redis fora: corpo generico', {'error': 'service_unavailable'}, js)
    confere('H07.03 Redis fora: leitura autenticada (Platform) segue', 200, n.req('GET', base(A) + '/me')[0])
    confere('H07.04 Redis fora: escrita autenticada comum -> 503', 503, n.req('POST', base(A) + '/auth/mfa/step-up', {'code': '000000'})[0])
    confere('H07.05 Redis fora: revoke-all segue (reduz acesso)', 204, n.req('POST', base(A) + '/auth/sessions/revoke-all')[0])
    confere('H07.06 Redis fora: sessao revogada', 401, n.req('GET', base(A) + '/me')[0])


def h07_r7(f):
    e = estado()
    A = f['org_a']

    def esgota(n):
        """ritmo de 20 req/s: abaixo do limite do gateway (30 r/s), acima da reposicao da R7 (2/s, capacidade 60). A
        primeira resposta TEM de ser 200: com a sessao valida, a chave da R7 e a da sessao (nao a do IP)."""
        if n.req('GET', base(A) + '/me')[0] != 200:
            return False
        limitadas = 0
        for _ in range(120):
            st, js, _, _ = n.req('GET', base(A) + '/me')
            if st == 429 and js.get('error') == 'rate_limited':
                limitadas += 1
                if limitadas >= 3:
                    return True
            time.sleep(0.05)
        return False

    # logins NOVOS, em contas separadas: sessao do logout (interno A) e duas sessoes do externo A (revoke-all)
    n, st1, _, _ = login(e, A, f['interno_a'])
    r, st2, _, _ = login(e, A, f['externo_a'])
    outra, st3, _, _ = login(e, A, f['externo_a'])
    confere('H07.prep logins novos para a fase R7', (200, 200, 200), (st1, st2, st3))
    salva(e)
    confere('H07.07 cota R7 da sessao esgotada (sessao valida, depois 429 rate_limited da aplicacao)', True, esgota(n))
    st, _, _, h = n.req('POST', base(A) + '/auth/mfa/step-up', {'code': '000000'})
    confere('H07.08 controle: escrita comum com a cota esgotada -> 429 com Retry-After', True, st == 429 and 'Retry-After' in h)
    guardado = dict(n.cookies)
    confere('H07.09 logout com a cota esgotada', 204, n.req('POST', base(A) + '/auth/logout')[0])
    v = Navegador()
    v.cookies = guardado
    confere('H07.10 logout revogou de fato', 401, v.req('GET', base(A) + '/me')[0])

    confere('H07.11 cota R7 esgotada na sessao do revoke-all (sessao valida, depois 429)', True, esgota(r))
    confere('H07.12 revoke-all com a cota esgotada', 204, r.req('POST', base(A) + '/auth/sessions/revoke-all')[0])
    confere('H07.13 revoke-all revogou a outra sessao da conta', 401, outra.req('GET', base(A) + '/me')[0])
    confere('H07.14 logout sem sessao: idempotente (204)', 204, Navegador().req('POST', base(A) + '/auth/logout')[0])
    anon = Navegador()
    st, js, _, _ = anon.req('GET', '/api/v1/csrf')
    anon.csrf = js.get('csrfToken')
    confere('H07.15 revoke-all sem sessao: 401', 401, anon.req('POST', base(A) + '/auth/sessions/revoke-all')[0])


def h06(f):
    """Alterna requisicoes autenticadas de DUAS organizacoes no mesmo pool da Platform (H06: contexto por requisicao)."""
    e = estado()
    na, st1, _, _ = login(e, f['org_a'], f['interno_a'])
    nb, st2, _, _ = login(e, f['org_b'], f['externo_b'])
    confere('H06.prep logins em A e em B', (200, 200), (st1, st2))
    ok = True
    for _ in range(15):
        ok &= na.req('GET', base(f['org_a']) + '/me')[0] == 200
        ok &= nb.req('GET', base(f['org_b']) + '/me')[0] == 200
        ok &= nb.req('GET', base(f['org_a']) + '/me')[0] == 401
    confere('H06.01 leituras alternadas A/B corretas e sessao de B recusada em A (15 ciclos)', True, ok)
    salva(e)


# ---------------------------------------------------------------- 016 (desenho P1a; testes/h016.sh)
# fixture.json da 016: {'org': ..., 'titular': {email, senha}, 'outro': {email, senha}}. Tokens de aviso chegam por
# ARQUIVO (lido da caixa pela ferramenta restrita para HOMOLOG_SEGREDOS/teste016, fora do Git) e vao para
# SEGREDOS_VISTOS, conferidos contra as evidencias.

def h016_mfa(f):
    """Primeiro acesso dos dois ORG_ADMIN sinteticos: login e cadastro do MFA pelo gateway (fatores confirmados reais)."""
    e = estado()
    for chave in ('titular', 'outro'):
        _, st, js, _ = login(e, f['org'], f[chave], novo=True)
        confere(f'H016.mfa {chave} com fator confirmado', 200, st)
    salva(e)


def h016_confirmar(f, arquivo_token, esperado='204'):
    """CONFIRMAR do titular pela rota real (POST com CSRF de pre-autenticacao e Origin): status esperado."""
    token = open(arquivo_token, encoding='utf-8').read().strip()
    visto(token)
    n = Navegador()
    st, js, _, _ = n.req('GET', '/api/v1/csrf')
    n.csrf = js.get('csrfToken')
    visto(n.csrf)
    st, _, _, _ = n.req('POST', base(f['org']) + '/auth/mfa-emergencia/aviso/confirmar', {'token': token})
    confere('H016.confirmar pela rota', int(esperado), st)


def h016_novo_mfa(f):
    """Depois da execucao (B8b): login do titular com a senha atual exige novo cadastro de MFA; cadastro conclui."""
    e = estado()
    e['segredos'].pop(f['titular']['email'], None)
    n = Navegador()
    st, js, _, _ = n.req('GET', '/api/v1/csrf')
    n.csrf = js.get('csrfToken')
    st, js, _, _ = n.req('POST', base(f['org']) + '/auth/login', {'email': f['titular']['email'], 'password': f['titular']['senha']})
    confere('H016.novo_mfa login exige cadastro', 'mfa_enrollment_required', js.get('status'))
    _, st, _, _ = login(e, f['org'], f['titular'], novo=True)
    confere('H016.novo_mfa cadastro e sessao', 200, st)
    salva(e)


if __name__ == '__main__':
    fixture = json.load(open(sys.argv[2], encoding='utf-8'))
    cenarios = {'h05': h05, 'h06': h06, 'h07_prep': h07_prep, 'h07_redis_fora': h07_redis_fora, 'h07_r7': h07_r7,
                'h016_mfa': h016_mfa, 'h016_confirmar': h016_confirmar, 'h016_novo_mfa': h016_novo_mfa}
    cenarios[sys.argv[1]](fixture, *sys.argv[3:])
    sys.exit(1 if falhas else 0)
