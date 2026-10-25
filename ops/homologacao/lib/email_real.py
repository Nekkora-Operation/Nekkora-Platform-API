"""Conferencias do e-mail real em homologacao (acao "homologacao.sh email-real"). Nenhum valor secreto vai para a saida:
credenciais, chaves, tokens e enderecos sao conferidos e, no maximo, contados.

Subcomandos:
  credenciais <aws.env>        credenciais TEMPORARIAS da AWS: as tres variaveis e AWS_CREDENTIAL_EXPIRATION no futuro
                               (pelo menos --minimo minutos, padrao 20). Saida: expira_em e minutos restantes.
  destinatarios <arquivo>      lista fechada (um endereco por linha; '#' comenta): exato ou local+*@dominio, a mesma regra
                               da Platform (DestinatariosPermitidos). Saida (stdout): a lista separada por virgulas, para a
                               variavel do compose; contagem no stderr.
  chave-fila <fila.env>        cria a chave PROPRIA da fila (AES-256, id fila-<data>) se o arquivo nao existir; nunca
                               sobrescreve (a chave decifra as mensagens ja enfileiradas).
  conferir-eml <arquivo.eml> [--destinatarios <arquivo>]
                               cabecalhos de uma mensagem RECEBIDA: remetente, um destinatario (na lista), SPF, DKIM
                               (d=nekkora.com) e DMARC pelo Authentication-Results da caixa que recebeu. Saida: uma linha por
                               conferencia, sem o endereco do destinatario, sem o corpo e sem token.
"""
import argparse
import base64
import datetime
import email
import email.policy
import os
import re
import secrets
import sys

EXATO = re.compile(r'^[^@\s*+,]+(\+[A-Za-z0-9._-]{1,64})?@[^@\s*+,]+\.[^@\s*+,]+$')
COM_MARCADOR = re.compile(r'^([^@\s*+,]+)\+\*@([^@\s*+,]+\.[^@\s*+,]+)$')
REMETENTE = 'no-reply@nekkora.com'


def le_env(caminho):
    v = {}
    with open(caminho, encoding='utf-8') as h:
        for linha in h:
            linha = linha.strip()
            if not linha or linha.startswith('#'):
                continue
            if '=' not in linha:
                raise SystemExit(f'{os.path.basename(caminho)}: linha fora do formato NOME=valor')
            k, x = linha.split('=', 1)
            v[k.strip()] = x.strip().strip('"').strip("'")
    return v


def credenciais(a):
    v = le_env(a.arquivo)
    faltam = [k for k in ('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN', 'AWS_CREDENTIAL_EXPIRATION') if not v.get(k)]
    if faltam:
        raise SystemExit(f'credenciais incompletas: faltam {",".join(faltam)} (so credenciais temporarias)')
    extras = sorted(set(v) - {'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN', 'AWS_CREDENTIAL_EXPIRATION'})
    if extras:
        raise SystemExit(f'credenciais com variaveis a mais: {",".join(extras)}')
    if not v['AWS_ACCESS_KEY_ID'].startswith('ASIA'):
        raise SystemExit('AWS_ACCESS_KEY_ID nao e de credencial temporaria (ASIA...)')
    try:
        exp = datetime.datetime.fromisoformat(v['AWS_CREDENTIAL_EXPIRATION'].replace('Z', '+00:00'))
    except ValueError:
        raise SystemExit('AWS_CREDENTIAL_EXPIRATION ilegivel (ISO 8601)')
    if exp.tzinfo is None:
        raise SystemExit('AWS_CREDENTIAL_EXPIRATION sem fuso')
    resta = (exp - datetime.datetime.now(datetime.timezone.utc)).total_seconds() / 60
    if resta < a.minimo:
        raise SystemExit(f'credenciais vencem em {resta:.0f} min (minimo {a.minimo}): gere novas')
    print(f'credenciais_temporarias=OK expira_em={exp.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")} restante_min={resta:.0f}')


def le_destinatarios(caminho):
    itens = []
    with open(caminho, encoding='utf-8') as h:
        for linha in h:
            linha = linha.split('#', 1)[0].strip()
            if not linha:
                continue
            if not (EXATO.match(linha) or COM_MARCADOR.match(linha)):
                raise SystemExit('destinatarios: item que nao e um endereco exato nem local+*@dominio')
            itens.append(linha.lower())
    if not itens:
        raise SystemExit('destinatarios: lista vazia')
    return sorted(set(itens))


def permitido(lista, endereco):
    e = (endereco or '').strip().lower()
    if not EXATO.match(e):
        return False
    for i in lista:
        if i == e:
            return True
        m = COM_MARCADOR.match(i)
        if m and (e == f'{m.group(1)}@{m.group(2)}' or (e.startswith(m.group(1) + '+') and e.endswith('@' + m.group(2)))):
            return True
    return False


def destinatarios(a):
    lista = le_destinatarios(a.arquivo)
    print(','.join(lista))
    print(f'destinatarios={len(lista)}', file=sys.stderr)


def chave_fila(a):
    if os.path.exists(a.arquivo):
        v = le_env(a.arquivo)
        if not v.get('NEKKORA_FILA_CHAVE_ID') or not v.get('NEKKORA_FILA_CHAVES'):
            raise SystemExit('fila.env existente sem NEKKORA_FILA_CHAVE_ID/NEKKORA_FILA_CHAVES: conferir manualmente (nunca sobrescrito)')
        print(f'chave_fila=EXISTENTE id={v["NEKKORA_FILA_CHAVE_ID"]}')
        return
    kid = 'fila-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d')
    chave = base64.b64encode(secrets.token_bytes(32)).decode()
    fd = os.open(a.arquivo, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8', newline='\n') as h:
        h.write(f'NEKKORA_FILA_CHAVE_ID={kid}\nNEKKORA_FILA_CHAVES={kid}:{chave}\n')
    print(f'chave_fila=CRIADA id={kid}')


def resultados_autenticacao(msg):
    """Metodos do Authentication-Results da caixa que recebeu (o PRIMEIRO cabecalho: o mais recente, do receptor)."""
    ar = msg.get_all('Authentication-Results') or []
    if not ar:
        return None, {}
    h = ' '.join(str(ar[0]).split())
    r = {}
    for metodo in ('spf', 'dkim', 'dmarc'):
        m = re.search(rf'\b{metodo}=([a-z]+)', h)
        r[metodo] = m.group(1) if m else 'ausente'
    # Exchange Online: header.d=nekkora.com; Gmail: header.i=@nekkora.com
    m = re.search(r'header\.d=([A-Za-z0-9.-]+)', h) or re.search(r'header\.i=[^@\s;]*@([A-Za-z0-9.-]+)', h)
    r['dkim_d'] = m.group(1).lower() if m else 'ausente'
    m = re.search(r'header\.from=([A-Za-z0-9.-]+)', h)
    r['dmarc_from'] = m.group(1).lower() if m else 'ausente'
    return h.split(';', 1)[0].strip(), r


def conferir_eml(a):
    with open(a.arquivo, 'rb') as h:
        msg = email.message_from_binary_file(h, policy=email.policy.default)
    ok = True

    def linha(nome, cond, obtido):
        nonlocal ok
        ok = ok and cond
        print(f'{nome}|{"OK" if cond else "FALHA"}|{obtido}')

    de = msg.get('From')
    end_de = de.addresses[0].addr_spec.lower() if de and de.addresses else ''
    nome_de = de.addresses[0].display_name if de and de.addresses else ''
    linha('remetente', end_de == REMETENTE, f'{nome_de} <{end_de}>' if end_de == REMETENTE else 'outro')
    para = msg.get('To')
    ends = [x.addr_spec for x in para.addresses] if para else []
    cc = msg.get('Cc')
    linha('um_destinatario_sem_copia', len(ends) == 1 and not cc, f'para={len(ends)} cc={"sim" if cc else "nao"}')
    if a.destinatarios:
        lista = le_destinatarios(a.destinatarios)
        linha('destinatario_na_lista', bool(ends) and permitido(lista, ends[0]), 'sim' if ends and permitido(lista, ends[0]) else 'nao')
    receptor, r = resultados_autenticacao(msg)
    if receptor is None:
        linha('authentication_results', False, 'ausente (salve a mensagem com os cabecalhos completos)')
    else:
        linha('authentication_results', True, f'receptor={receptor}')
        linha('spf', r['spf'] == 'pass', r['spf'])
        linha('dkim', r['dkim'] == 'pass' and r['dkim_d'] == 'nekkora.com', f'{r["dkim"]} d={r["dkim_d"]}')
        linha('dmarc', r['dmarc'] == 'pass' and r['dmarc_from'] in ('nekkora.com', 'ausente'), f'{r["dmarc"]} from={r["dmarc_from"]}')
    assin = msg.get_all('DKIM-Signature') or []
    ds = sorted({m.group(1).lower() for s in assin for m in [re.search(r'\bd=([A-Za-z0-9.-]+)', ' '.join(str(s).split()))] if m})
    linha('assinatura_dkim_nekkora', 'nekkora.com' in ds, ','.join(ds) or 'nenhuma')
    linha('message_id_presente', bool(msg.get('Message-ID')), 'sim' if msg.get('Message-ID') else 'nao')
    print(f'RESULTADO|{"OK" if ok else "FALHA"}|assunto={msg.get("Subject", "")}')
    sys.exit(0 if ok else 1)


def main():
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest='cmd', required=True)
    c = sp.add_parser('credenciais')
    c.add_argument('arquivo')
    c.add_argument('--minimo', type=int, default=20)
    c.set_defaults(f=credenciais)
    d = sp.add_parser('destinatarios')
    d.add_argument('arquivo')
    d.set_defaults(f=destinatarios)
    k = sp.add_parser('chave-fila')
    k.add_argument('arquivo')
    k.set_defaults(f=chave_fila)
    e = sp.add_parser('conferir-eml')
    e.add_argument('arquivo')
    e.add_argument('--destinatarios')
    e.set_defaults(f=conferir_eml)
    a = ap.parse_args()
    a.f(a)


if __name__ == '__main__':
    main()
