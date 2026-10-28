"""Teste ESTATICO de lib/email_real.py (sem rede, sem Docker): credenciais temporarias da AWS, lista fechada de
destinatarios (mesma regra da Platform), chave propria da fila (nunca sobrescrita) e conferencia dos cabecalhos de uma
mensagem recebida (Exchange Online e Gmail), com casos que tem de passar e casos que tem de falhar. Nenhum valor de
credencial ou de chave pode aparecer na saida. Uso: python testes/test_email_real.py (ligado ao validar-config).
"""
import datetime
import os
import subprocess
import sys
import tempfile

AQUI = os.path.dirname(os.path.abspath(__file__))
LIB = os.path.join(os.path.dirname(AQUI), 'lib', 'email_real.py')
falhas = 0
total = 0


def roda(*args):
    r = subprocess.run([sys.executable, '-I', LIB, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    return r.returncode, r.stdout.decode('utf-8', 'replace'), r.stderr.decode('utf-8', 'replace')


def caso(nome, cond):
    global falhas, total
    total += 1
    if not cond:
        falhas += 1
    print(f'{"OK   " if cond else "FALHA"} {nome}')


def escreve(d, nome, texto):
    p = os.path.join(d, nome)
    with open(p, 'w', encoding='utf-8', newline='\n') as h:
        h.write(texto)
    return p


def eml(de, para, ar, dkim_d='nekkora.com', assunto='Confirme seu cadastro na Nekkora', cc=None):
    linhas = []
    if ar:
        linhas.append(f'Authentication-Results: {ar}')
    linhas += [f'DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed; d={dkim_d}; s=selector1; h=From:Date:Subject; bh=x; b=y',
               f'From: {de}', f'To: {para}']
    if cc:
        linhas.append(f'Cc: {cc}')
    linhas += [f'Subject: {assunto}', 'Message-ID: <abc@nekkora.com>', 'Date: Sat, 24 Oct 2026 12:00:00 +0000',
               'MIME-Version: 1.0', 'Content-Type: text/plain; charset=utf-8', '',
               'Para concluir o cadastro, abra o link:', 'https://homologacao.nekkora.test:55763/cadastro/confirmar#token=TOKENDETESTE1234567890', '']
    return '\r\n'.join(linhas)


EXO = ('spf=pass (sender IP is 40.107.1.1) smtp.mailfrom=nekkora.com; dkim=pass (signature was verified) header.d=nekkora.com;'
       'dmarc=pass action=none header.from=nekkora.com;compauth=pass reason=100')
GMAIL = ('mx.google.com; dkim=pass header.i=@nekkora.com header.s=selector1 header.b=abc; spf=pass (google.com: domain of '
         'no-reply@nekkora.com designates 40.107.1.1 as permitted sender) smtp.mailfrom=no-reply@nekkora.com; dmarc=pass (p=NONE sp=NONE dis=NONE) header.from=nekkora.com')

with tempfile.TemporaryDirectory() as d:
    agora = datetime.datetime.now(datetime.timezone.utc)
    fut = (agora + datetime.timedelta(hours=1)).strftime('%Y-%m-%dT%H:%M:%SZ')
    perto = (agora + datetime.timedelta(minutes=5)).strftime('%Y-%m-%dT%H:%M:%SZ')
    SEGREDO = 'segredoDeTesteNaoPodeAparecer0123456789'
    TOKEN_S = 'tokenDeSessaoNaoPodeAparecer0123456789'
    base = f'AWS_ACCESS_KEY_ID=ASIAEXEMPLO1234567890\nAWS_SECRET_ACCESS_KEY={SEGREDO}\nAWS_SESSION_TOKEN={TOKEN_S}\n'
    rc, out, err = roda('credenciais', escreve(d, 'ok.env', base + f'AWS_CREDENTIAL_EXPIRATION={fut}\n'))
    caso('credenciais temporarias validas por 1 h aceitas', rc == 0 and 'credenciais_temporarias=OK' in out)
    caso('saida das credenciais sem nenhum valor', SEGREDO not in out + err and TOKEN_S not in out + err and 'ASIAEXEMPLO' not in out + err)
    rc, out, err = roda('credenciais', escreve(d, 'perto.env', base + f'AWS_CREDENTIAL_EXPIRATION={perto}\n'))
    caso('credenciais que vencem em 5 min recusadas (minimo 20)', rc != 0 and 'vencem' in err)
    rc, out, err = roda('credenciais', escreve(d, 'longa.env', base.replace('ASIA', 'AKIA') + f'AWS_CREDENTIAL_EXPIRATION={fut}\n'))
    caso('chave de longo prazo (AKIA) recusada', rc != 0 and 'temporaria' in err)
    rc, out, err = roda('credenciais', escreve(d, 'sem.env', base.replace(f'AWS_SESSION_TOKEN={TOKEN_S}\n', '') + f'AWS_CREDENTIAL_EXPIRATION={fut}\n'))
    caso('sem token de sessao recusada', rc != 0 and 'AWS_SESSION_TOKEN' in err)
    rc, out, err = roda('credenciais', escreve(d, 'extra.env', base + f'AWS_CREDENTIAL_EXPIRATION={fut}\nAWS_PROFILE=x\n'))
    caso('variavel a mais recusada', rc != 0 and 'a mais' in err)
    caso('mensagens de erro sem valores', SEGREDO not in err and TOKEN_S not in err)

    lst = escreve(d, 'dest.txt', '# caixa controlada\ncontrole+*@exemplo.test\nOutra@Exemplo.test\n')
    rc, out, err = roda('destinatarios', lst)
    caso('lista valida: exato e local+*@dominio, em minusculas', rc == 0 and out.strip() == 'controle+*@exemplo.test,outra@exemplo.test')
    for ruim in ('*@exemplo.test', '@exemplo.test', 'exemplo.test', 'controle+*@*.test', 'a,b@exemplo.test'):
        rc, _, _ = roda('destinatarios', escreve(d, 'ruim.txt', ruim + '\n'))
        caso(f'item recusado: {ruim}', rc != 0)
    rc, _, _ = roda('destinatarios', escreve(d, 'vazia.txt', '# nada\n'))
    caso('lista vazia recusada', rc != 0)

    fila = os.path.join(d, 'fila.env')
    rc, out, _ = roda('chave-fila', fila)
    conteudo = open(fila, encoding='utf-8').read()
    caso('chave da fila criada com id e 32 bytes', rc == 0 and 'CRIADA' in out and conteudo.startswith('NEKKORA_FILA_CHAVE_ID=fila-'))
    chave = conteudo.split('NEKKORA_FILA_CHAVES=')[1].split(':', 1)[1].strip()
    caso('valor da chave fora da saida', chave not in out)
    rc, out, _ = roda('chave-fila', fila)
    caso('chave da fila existente nunca sobrescrita', rc == 0 and 'EXISTENTE' in out and open(fila, encoding='utf-8').read() == conteudo)

    p12 = escreve(d, 'teste.p12', 'pkcs12-de-exemplo')
    sf = escreve(d, 'senha.txt', 'senhaDoPkcs12NaoPodeAparecer\n')
    alvo = os.path.join(d, 'segredo.json')
    rc, out, err = roda('montar-segredo', '--p12', p12, '--senha-arquivo', sf, '--tenant', 'ae4ed48d-e7d8-4f24-83f2-443e997c5a65',
                        '--client', '00000000-0000-0000-0000-00000000bbbb', '--saida', alvo)
    import json as _json
    seg = _json.load(open(alvo, encoding='utf-8')) if os.path.exists(alvo) else {}
    caso('segredo montado no formato nekkora-m365/1 com os cinco campos', rc == 0 and sorted(seg) == ['certificado_pkcs12', 'certificado_senha', 'client_id', 'formato', 'tenant_id']
         and seg.get('formato') == 'nekkora-m365/1' and seg.get('certificado_senha') == 'senhaDoPkcs12NaoPodeAparecer')
    caso('saida do montar-segredo sem a senha nem o PKCS#12', 'senhaDoPkcs12' not in out + err and 'cGtjczEy' not in out + err)
    rc, _, _ = roda('montar-segredo', '--p12', p12, '--senha-arquivo', sf, '--tenant', 'ae4ed48d-e7d8-4f24-83f2-443e997c5a65',
                    '--client', '00000000-0000-0000-0000-00000000bbbb', '--saida', alvo)
    caso('montar-segredo nunca sobrescreve', rc != 0)
    rc, _, _ = roda('montar-segredo', '--p12', p12, '--senha-arquivo', sf, '--tenant', 'TENANT', '--client', 'x', '--saida', alvo + '2')
    caso('montar-segredo recusa tenant/client que nao sao GUID', rc != 0)

    de = 'Nekkora <no-reply@nekkora.com>'
    rc, out, _ = roda('conferir-eml', escreve(d, 'exo.eml', eml(de, 'controle+r1-x@exemplo.test', EXO)), '--destinatarios', lst)
    caso('Exchange Online: SPF, DKIM d=nekkora.com e DMARC pass', rc == 0 and 'spf|OK|pass' in out and 'dkim|OK|pass d=nekkora.com' in out and 'dmarc|OK' in out)
    caso('saida sem o destinatario e sem o token', 'controle+r1-x' not in out and 'TOKENDETESTE' not in out)
    rc, out, _ = roda('conferir-eml', escreve(d, 'gmail.eml', eml(de, 'outra@exemplo.test', GMAIL)), '--destinatarios', lst)
    caso('Gmail (header.i=@nekkora.com): SPF, DKIM e DMARC pass', rc == 0 and 'dkim|OK|pass d=nekkora.com' in out)
    rc, out, _ = roda('conferir-eml', escreve(d, 'dkimfalha.eml', eml(de, 'outra@exemplo.test', EXO.replace('dkim=pass', 'dkim=fail'))), '--destinatarios', lst)
    caso('DKIM fail reprovado', rc != 0 and 'dkim|FALHA' in out)
    rc, out, _ = roda('conferir-eml', escreve(d, 'dmarc.eml', eml(de, 'outra@exemplo.test', EXO.replace('dmarc=pass', 'dmarc=none'))), '--destinatarios', lst)
    caso('DMARC none reprovado (sem registro DMARC)', rc != 0 and 'dmarc|FALHA' in out)
    rc, out, _ = roda('conferir-eml', escreve(d, 'outro.eml', eml('Outra <outra@nekkora.com>', 'outra@exemplo.test', EXO)), '--destinatarios', lst)
    caso('outro remetente reprovado', rc != 0 and 'remetente|FALHA' in out)
    rc, out, _ = roda('conferir-eml', escreve(d, 'fora.eml', eml(de, 'alguem@cliente.test', EXO)), '--destinatarios', lst)
    caso('destinatario fora da lista reprovado', rc != 0 and 'destinatario_na_lista|FALHA' in out)
    rc, out, _ = roda('conferir-eml', escreve(d, 'cc.eml', eml(de, 'outra@exemplo.test', EXO, cc='x@exemplo.test')), '--destinatarios', lst)
    caso('copia (Cc) reprovada', rc != 0 and 'um_destinatario_sem_copia|FALHA' in out)
    rc, out, _ = roda('conferir-eml', escreve(d, 'semar.eml', eml(de, 'outra@exemplo.test', None)), '--destinatarios', lst)
    caso('sem Authentication-Results reprovado', rc != 0 and 'authentication_results|FALHA' in out)
    rc, out, _ = roda('conferir-eml', escreve(d, 'dkimoutro.eml', eml(de, 'outra@exemplo.test', EXO.replace('header.d=nekkora.com', 'header.d=radiantsai.onmicrosoft.com'),
                                                                      dkim_d='radiantsai.onmicrosoft.com')), '--destinatarios', lst)
    caso('DKIM so do dominio inicial (onmicrosoft) reprovado', rc != 0 and 'dkim|FALHA' in out)

# caixa real da jornada (testes/jornada_email_real.py): escolhe pelo tipo (assunto) e pelo destinatario, ignora o que ja
# estava na pasta, tira o token so em memoria e o anota em --vistos
sys.path.insert(0, AQUI)
import jornada_email_real as jr  # noqa: E402


class RelFalso:
    def __init__(self):
        self.vistos, self.linhas = [], []

    def segredo(self, *v):
        self.vistos += list(v)

    def confere(self, caso, tipo, ok, esperado, obtido):
        self.linhas.append((caso, ok, str(obtido)))


with tempfile.TemporaryDirectory() as d:
    escreve(d, 'antiga.eml', eml('Nekkora <no-reply@nekkora.com>', 'controle+r1-x@exemplo.test', EXO))
    rel = RelFalso()
    cx = jr.CaixaReal(d, ['controle+*@exemplo.test'], rel, 1)
    escreve(d, 'outro.eml', eml('Nekkora <no-reply@nekkora.com>', 'controle+r9-x@exemplo.test', EXO))
    escreve(d, 'nova.eml', eml('Nekkora <no-reply@nekkora.com>', 'controle+r1-x@exemplo.test', EXO))
    m = cx.espera('IDENTITY_SIGNUP_CONFIRMATION', 'controle+r1-x@exemplo.test', prazo=0)
    caso('caixa real: acha a mensagem NOVA do tipo e do destinatario', bool(m) and m['arquivo'] == 'nova.eml')
    caso('caixa real: token tirado do link e anotado em vistos', bool(m) and m['token'] == 'TOKENDETESTE1234567890' and 'TOKENDETESTE1234567890' in rel.vistos)
    caso('caixa real: cabecalhos conferidos (SPF, DKIM, DMARC, remetente) sem levar o token', len(rel.linhas) == 4 and all(ok for _, ok, _ in rel.linhas)
         and not any('TOKENDETESTE' in o for _, _, o in rel.linhas))
    cx.prazo = 0
    caso('caixa real: arquivo ja usado nao volta', cx.espera('IDENTITY_SIGNUP_CONFIRMATION', 'controle+r1-x@exemplo.test', prazo=0) is None)
    caso('caixa real: outro tipo nao e confundido', cx.espera('PASSWORD_RECOVERY', 'controle+r9-x@exemplo.test', prazo=0) is None)

print(f'casos={total} falhas={falhas}')
print('RESULTADO: email_real OK' if falhas == 0 else 'RESULTADO: email_real FALHOU')
sys.exit(1 if falhas else 0)
