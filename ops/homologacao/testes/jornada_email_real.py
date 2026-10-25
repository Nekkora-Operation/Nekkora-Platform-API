"""Jornada do e-mail REAL em homologacao (acao "homologacao.sh email-real <id> jornada"): cadastro, recuperacao de senha
e avisos de seguranca pela API do ensaio com o transporte m365 ligado, e as mensagens RECEBIDAS na caixa controlada.

Reaproveita as jornadas da API (testes/jornadas_api.py: navegador, CSRF, fator TOTP, separacao API/CAIXA/BANCO) e troca a
caixa simulada pela CAIXA REAL: a operadora salva cada mensagem recebida, com os cabecalhos completos, como .eml na pasta
--entrada (diretorio de segredos do ambiente, fora do Git). A jornada espera a mensagem do tipo e do destinatario (prazo
--prazo minutos), confere remetente, destinatario, SPF, DKIM e DMARC (lib/email_real.py), tira o token do link (#token=)
so em memoria e segue pela API. Os enderecos sao variacoes com marcador (local+<caso>-<execucao>@dominio) do item
local+*@dominio da lista fechada, para uma identidade nova por execucao na MESMA caixa controlada.

Tres estados de cada mensagem, sempre separados (nunca "entregue exatamente uma vez"):
  ENFILEIRADA - linha na mail_outbox (BANCO, somente leitura);
  ACEITA      - estado 3 com last_status 202 (aceite do Graph; NAO e entrega);
  RECEBIDA    - o .eml salvo da caixa controlada, com os cabecalhos conferidos.

Casos: R1 cadastro (pedido, mensagem, confirmacao, token de uso unico); R2 primeira entrada com o fator; R3 recuperacao de
senha (mensagem, troca, token de uso unico, sessoes revogadas, MFA preservado) e o aviso de senha alterada; R4 entrada com
codigo de recuperacao e o aviso de codigo usado. Saida como a das jornadas da API (jornadas.txt, resumo.txt, --vistos).
"""
import argparse
import datetime
import email
import email.policy
import os
import re
import sys
import time

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)
sys.path.insert(0, os.path.join(os.path.dirname(AQUI), 'lib'))
import email_real  # noqa: E402
import jornadas_api as ja  # noqa: E402

# assunto -> tipo (api/nekkora-platform/.../MailTemplates.java; ASCII, sem acento)
ASSUNTOS = {
    'Confirme seu cadastro na Nekkora': 'IDENTITY_SIGNUP_CONFIRMATION',
    'Pedido de cadastro na Nekkora': 'IDENTITY_SIGNUP_ALREADY_REGISTERED',
    'Recuperacao de senha da Nekkora': 'PASSWORD_RECOVERY',
    'A senha da sua conta foi alterada': 'NOTICE_PASSWORD_CHANGED',
    'Um codigo de recuperacao foi usado': 'NOTICE_RECOVERY_CODE_USED',
    'O fator de autenticacao da sua conta foi trocado': 'NOTICE_MFA_REPLACED',
    'Novos codigos de recuperacao': 'NOTICE_RECOVERY_CODES_REGENERATED',
}
TOKEN = re.compile(r'#token=([A-Za-z0-9_-]{16,256})')


class CaixaReal:
    """Mensagens .eml salvas pela operadora em --entrada; cada arquivo e usado uma vez."""

    def __init__(self, entrada, destinatarios, rel, prazo_min):
        self.entrada, self.destinatarios, self.rel, self.prazo = entrada, destinatarios, rel, prazo_min * 60
        self.lidas = set(os.listdir(entrada))  # o que ja estava na pasta nao conta para esta execucao

    def _le(self, caminho):
        with open(caminho, 'rb') as h:
            return email.message_from_binary_file(h, policy=email.policy.default)

    def espera(self, tipo, destinatario, prazo=None):
        fim = time.time() + (self.prazo if prazo is None else max(prazo, self.prazo))
        print(f'AGUARDANDO: salve como .eml em {self.entrada} a mensagem "{next(k for k, v in ASSUNTOS.items() if v == tipo)}" '
              f'recebida pela caixa controlada (ate {self.prazo // 60} min)', flush=True)
        while time.time() < fim:
            for n in sorted(os.listdir(self.entrada)):
                if n in self.lidas or not n.lower().endswith('.eml'):
                    continue
                p = os.path.join(self.entrada, n)
                try:
                    msg = self._le(p)
                except Exception:  # noqa: BLE001 - arquivo ainda sendo gravado; tenta de novo no proximo ciclo
                    continue
                para = msg.get('To')
                ends = [x.addr_spec.lower() for x in para.addresses] if para else []
                if ASSUNTOS.get(str(msg.get('Subject', '')).strip()) != tipo or ends != [destinatario.lower()]:
                    continue
                self.lidas.add(n)
                corpo = msg.get_body(preferencelist=('plain',))
                texto = corpo.get_content() if corpo else ''
                tk = TOKEN.findall(texto)
                for t in tk:
                    self.rel.segredo(t)
                self.confere_cabecalhos(msg, tipo)
                return {'destinatario': destinatario, 'tipo': tipo, 'token': tk[0] if tk else None, 'arquivo': n,
                        'message_id': str(msg.get('Message-ID', ''))}
            time.sleep(3)
        return None

    def confere_cabecalhos(self, msg, tipo):
        r = self.rel
        de = msg.get('From')
        end = de.addresses[0].addr_spec.lower() if de and de.addresses else ''
        r.confere(f'{tipo}: remetente no-reply@nekkora.com', 'RECEBIDA', end == email_real.REMETENTE, email_real.REMETENTE,
                  end if end == email_real.REMETENTE else 'outro')
        receptor, res = email_real.resultados_autenticacao(msg)
        if receptor is None:
            r.confere(f'{tipo}: Authentication-Results da caixa que recebeu', 'RECEBIDA', False, 'presente', 'ausente')
            return
        r.confere(f'{tipo}: SPF', 'RECEBIDA', res['spf'] == 'pass', 'pass', res['spf'])
        r.confere(f'{tipo}: DKIM d=nekkora.com', 'RECEBIDA', res['dkim'] == 'pass' and res['dkim_d'] == 'nekkora.com', 'pass d=nekkora.com',
                  f'{res["dkim"]} d={res["dkim_d"]}')
        r.confere(f'{tipo}: DMARC', 'RECEBIDA', res['dmarc'] == 'pass', 'pass', res['dmarc'])


class JornadaEmailReal(ja.Jornadas):
    def __init__(self, a):
        super().__init__(a)
        self.destinatarios = email_real.le_destinatarios(a.destinatarios)
        base = [i for i in self.destinatarios if email_real.COM_MARCADOR.match(i)]
        if not base:
            raise SystemExit('a jornada exige um item local+*@dominio na lista de destinatarios (identidade nova por execucao)')
        self.local, self.dominio = email_real.COM_MARCADOR.match(base[0]).groups()
        self.caixa = CaixaReal(a.entrada, self.destinatarios, self.rel, a.prazo)
        self.inicio = datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M:%S+00')

    def email(self, rotulo):
        e = f'{self.local}+{rotulo}-{self.sufixo}@{self.dominio}'
        self.rel.segredo(e)
        return e

    def fila(self, tipo, minimo=1):
        """BANCO (somente leitura): as mensagens do tipo desde o inicio desta execucao, por estado (nunca o conteudo)."""
        out = self.banco.q("SELECT state, coalesce(last_status::text, '-'), attempts FROM public.mail_outbox "
                           "WHERE kind = :'tipo' AND created_at >= :'inicio'::timestamptz ORDER BY created_at",
                           ('tipo', tipo), ('inicio', self.inicio))
        linhas = [x.split('|') for x in out.splitlines() if x]
        ok = len(linhas) >= minimo and all(e == '3' and s == '202' for e, s, _ in linhas)
        self.rel.confere(f'{tipo}: ENFILEIRADA e ACEITA pelo Graph (estado 3, 202; aceite nao e entrega)', 'BANCO', ok,
                         f'>= {minimo} linha(s) em 3|202', ';'.join('|'.join(x) for x in linhas) or 'nenhuma')

    def espera_aceite(self, tipo, prazo=90):
        fim = time.time() + prazo
        while time.time() < fim:
            out = self.banco.q("SELECT count(*) FILTER (WHERE state IN (1, 2)) FROM public.mail_outbox WHERE kind = :'tipo' "
                               "AND created_at >= :'inicio'::timestamptz", ('tipo', tipo), ('inicio', self.inicio))
            if out.strip() == '0':
                return
            time.sleep(3)

    def r1(self):
        email_, senha = self.cria_identidade('r1')
        self.espera_aceite('IDENTITY_SIGNUP_CONFIRMATION')
        self.fila('IDENTITY_SIGNUP_CONFIRMATION')
        self.estado['r1'] = {'email': email_, 'senha': senha}

    def r2(self):
        s = self.estado['r1']
        n, csrf, fator, codigos = self.entra_primeiro(s['email'], s['senha'])
        st, _ = self.sessao_ok(n)
        self.rel.confere('primeira entrada com o fator cadastrado', 'API', st == 200, 200, st)
        s.update(nav=n, csrf=csrf, fator=fator, codigos=codigos)
        for c in codigos:
            self.rel.segredo(c)

    def r3(self):
        r, s = self.rel, self.estado['r1']
        n = self.nav('r3')
        st, _, _ = n.req('POST', '/api/v1/auth/password-reset/request', {'email': s['email']}, csrf=self.pre(n))
        r.confere('recuperacao: pedido da titular 202', 'API', st == 202, 202, st)
        m = self.caixa.espera('PASSWORD_RECOVERY', s['email'])
        r.confere('recuperacao: mensagem RECEBIDA com token', 'RECEBIDA', bool(m and m.get('token')), 'token', 'sim' if m and m.get('token') else 'nao')
        if not (m and m.get('token')):
            raise RuntimeError('mensagem de recuperacao nao recebida')
        nova = self.senha()
        st, _, cks = n.req('POST', '/api/v1/auth/password-reset/confirm', {'token': m['token'], 'newPassword': nova}, csrf=self.pre(n))
        r.confere('recuperacao: senha trocada, nenhuma sessao criada', 'API', st == 204 and '+__Host-nekkora_id' not in cks, '204 sem cookie',
                  f'{st} {",".join(cks)}')
        st, _, _ = n.req('POST', '/api/v1/auth/password-reset/confirm', {'token': m['token'], 'newPassword': self.senha()}, csrf=self.pre(n))
        r.confere('recuperacao: token de uso unico (reuso recusado)', 'API', st == 400, 400, st)
        st, _ = self.sessao_ok(s['nav'])
        r.confere('recuperacao: sessoes de identidade revogadas', 'API', st == 401, 401, st)
        n2 = self.nav('r3-entrada')
        st, js, _ = n2.req('POST', '/api/v1/auth/login', {'email': s['email'], 'password': nova}, csrf=self.pre(n2))
        r.confere('recuperacao: MFA preservado (a entrada pede o fator, nao o cadastro)', 'API', st == 200 and js.get('status') == 'mfa_required',
                  '200 mfa_required', f'{st} {js and js.get("status")}')
        st, _, _ = n2.req('POST', '/api/v1/auth/mfa/verify', {'code': s['fator'].codigo()}, csrf=js['csrfToken'])
        r.confere('recuperacao: entrada com a senha nova e o MESMO fator', 'API', st == 200, 200, st)
        s['senha'] = nova
        self.espera_aceite('PASSWORD_RECOVERY')
        self.fila('PASSWORD_RECOVERY')
        aviso = self.caixa.espera('NOTICE_PASSWORD_CHANGED', s['email'])
        r.confere('aviso de seguranca: senha alterada RECEBIDO, sem link nem token', 'RECEBIDA', bool(aviso) and not aviso.get('token'),
                  'recebido sem token', 'nao recebido' if not aviso else ('com token' if aviso.get('token') else 'recebido sem token'))
        self.espera_aceite('NOTICE_PASSWORD_CHANGED')
        self.fila('NOTICE_PASSWORD_CHANGED')

    def r4(self):
        r, s = self.rel, self.estado['r1']
        st, _, _ = self.entra(s['email'], s['senha'], codigo_recuperacao=s['codigos'][0])
        r.confere('entrada com codigo de recuperacao', 'API', st == 200, 200, st)
        aviso = self.caixa.espera('NOTICE_RECOVERY_CODE_USED', s['email'])
        r.confere('aviso de seguranca: codigo de recuperacao usado RECEBIDO', 'RECEBIDA', bool(aviso), 'recebido', 'sim' if aviso else 'nao')
        st, _, _ = self.entra(s['email'], s['senha'], codigo_recuperacao=s['codigos'][0])
        r.confere('codigo de recuperacao de uso unico (reuso recusado)', 'API', st != 200, 'diferente de 200', st)
        self.espera_aceite('NOTICE_RECOVERY_CODE_USED')
        self.fila('NOTICE_RECOVERY_CODE_USED')


def main():
    ap = argparse.ArgumentParser()
    for x in ('--alvo', '--ca', '--evid', '--vistos', '--psql', '--entrada', '--destinatarios'):
        ap.add_argument(x, required=True)
    ap.add_argument('--bash', default=os.environ.get('HOMOLOG_BASH') or r'C:\Program Files\Git\bin\bash.exe')
    ap.add_argument('--prazo', type=int, default=15, help='minutos de espera por mensagem recebida')
    ap.add_argument('--somente', default='')
    a = ap.parse_args()
    # campos que a classe base exige e que a jornada real nao usa (sem caixa simulada, sem PHC, sem semente)
    a.caixa, a.phc, a.sql_semente = 'true', 'true', ''
    nomes = [x for x in a.somente.split(',') if x] or ['R1', 'R2', 'R3', 'R4']
    for x in nomes:
        if x not in ('R1', 'R2', 'R3', 'R4'):
            raise SystemExit(f'caso desconhecido: {x}')
    if nomes != ['R1', 'R2', 'R3', 'R4'][:len(nomes)]:
        raise SystemExit('os casos dependem do anterior: R1, R1+R2, R1+R2+R3 ou todos')
    sys.exit(JornadaEmailReal(a).executa(nomes))


if __name__ == '__main__':
    main()
