"""Canal do Agent (025) na homologacao (E-AP-1): configuracao do processo nekkora-agent-gateway num ambiente ensaio-*.

Usado SO por `homologacao.sh agente <id> ligar|conferir|desligar`. Cada edicao e idempotente, recusa o que nao reconhece
e nunca imprime segredo. Os arquivos originais sao copiados pelo runner ANTES de qualquer edicao (desligar = restaurar).

  python agente.py segredos <dir_segredos> <prefixo_interna>
      Cria senhas/agent_gateway (papel nekkora_ingestion) e senhas/redis_agent (usuario Redis "agent", so ~agent:*),
      grava agent-gateway.env e acrescenta o usuario na ACL do Redis. Recusa se ja existirem (substituicao = desligar).
  python agente.py pg_hba <arquivo> <prefixo_interna>
      Acrescenta `hostssl nekkora nekkora_ingestion <interna>.22/32 scram-sha-256` antes da recusa final.
  python agente.py nginx <arquivo> <prefixo_interna>
      Acrescenta a zona de taxa do canal, o upstream <interna>.22:8083 e a rota /agent/api/v1/ (lotes de ate 2 MiB),
      no mesmo desenho do gateway de referencia do E2E (api/e2e/nginx.conf).
  python agente.py varrer <dir_segredos> <arquivo> [<arquivo> ...]
      Codigo 1 se qualquer senha de <dir_segredos>/senhas aparecer em algum arquivo (logs, evidencias). Imprime so
      a contagem e o NOME do arquivo de senha encontrado, nunca o valor.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from segredos import aleatoria, grava  # noqa: E402

MARCA = '# canal do Agent (025), acrescentado por homologacao.sh agente ligar (E-AP-1)'
CMDS = '+@connection +get +set +del +eval +evalsha +hmget +hset +incr +pexpire +time +zadd +zcard +zremrangebyscore'


def le(caminho):
    with open(caminho, encoding='utf-8', newline='') as h:
        return h.read()


def escreve(caminho, conteudo):
    with open(caminho, 'w', encoding='utf-8', newline='') as h:
        h.write(conteudo)


def segredos(d, interna):
    import hashlib
    for k in ('agent_gateway', 'redis_agent'):
        if os.path.exists(os.path.join(d, 'senhas', k)):
            raise SystemExit(f'senhas/{k} ja existe: nao sobrescrevo (desligue o canal antes)')
    acl = os.path.join(d, 'redis', 'users.acl')
    atual = le(acl)
    if '\nuser agent ' in '\n' + atual:
        raise SystemExit('a ACL do Redis ja tem o usuario agent: nao sobrescrevo')
    s = {k: aleatoria() for k in ('agent_gateway', 'redis_agent')}
    for k, v in s.items():
        grava(os.path.join(d, 'senhas', k), v)
    url = 'jdbc:postgresql://postgres:5432/nekkora?sslmode=verify-full&sslrootcert=/tls/ca.crt'
    grava(os.path.join(d, 'agent-gateway.env'),
          f'NEKKORA_GATEWAY_DB_URL={url}\nNEKKORA_GATEWAY_DB_SENHA={s["agent_gateway"]}\n'
          f'NEKKORA_PROXIES_CONFIAVEIS={interna}.5/32\n'
          'NEKKORA_REDIS_HOST=redis\nNEKKORA_REDIS_PORT=6379\nSPRING_DATA_REDIS_SSL_BUNDLE=redis\n'
          'SPRING_SSL_BUNDLE_PEM_REDIS_TRUSTSTORE_CERTIFICATE=file:/tls/ca.crt\n'
          f'SPRING_DATA_REDIS_USERNAME=agent\nSPRING_DATA_REDIS_PASSWORD={s["redis_agent"]}\n')
    sha = hashlib.sha256(s['redis_agent'].encode()).hexdigest()
    if not atual.endswith('\n'):
        atual += '\n'
    grava(acl, atual + f'user agent on #{sha} resetchannels ~agent:* -@all {CMDS}\n')
    print('segredos do canal gerados (agent_gateway, redis_agent) e usuario agent na ACL do Redis')


def pg_hba(arq, interna):
    texto = le(arq)
    if 'nekkora_ingestion' in texto:
        raise SystemExit('pg_hba ja tem linha do nekkora_ingestion: nao edito')
    recusa = 'host      all        all                 0.0.0.0/0           reject'
    if texto.count(recusa) != 1:
        raise SystemExit('pg_hba sem a recusa final reconhecida: nao edito')
    linha = f'hostssl   nekkora    nekkora_ingestion   {interna}.22/32     scram-sha-256'
    escreve(arq, texto.replace(recusa, f'{MARCA}\n{linha}\n{recusa}'))
    print(f'pg_hba: nekkora_ingestion so de {interna}.22/32 (SCRAM e TLS), antes da recusa final')


def nginx(arq, interna):
    texto = le(arq)
    if '/agent/api/v1/' in texto or 'upstream agent' in texto:
        raise SystemExit('nginx ja tem o canal do Agent: nao edito')
    zona = 'limit_req_zone $binary_remote_addr zone=geral:10m rate=30r/s;'
    portal_up = 'upstream portal {'
    portal_loc = 'location /portal/api/v1/ {'
    for ancora in (zona, portal_up, portal_loc):
        if texto.count(ancora) != 1:
            raise SystemExit(f'nginx sem a ancora reconhecida ({ancora!r}): nao edito')
    texto = texto.replace(zona, f'{zona}\n    {MARCA}\n    limit_req_zone $binary_remote_addr zone=agente:10m rate=100r/s;')
    texto = texto.replace(portal_up, f'upstream agent {{\n        server {interna}.22:8083;\n    }}\n\n    {portal_up}')
    rota = ('location /agent/api/v1/ {\n'
            '            # canal da maquina (025): lotes de eventos de ate 2 MiB; processo fora falha rapido (502) e a\n'
            '            # maquina reenvia os mesmos fatos depois\n'
            '            client_max_body_size 2m;\n'
            '            proxy_connect_timeout 2s;\n'
            '            limit_req zone=agente burst=200 nodelay;\n'
            '            proxy_pass http://agent;\n'
            '        }\n\n        ')
    texto = texto.replace(portal_loc, rota + portal_loc)
    escreve(arq, texto)
    print(f'nginx: rota /agent/api/v1/ -> {interna}.22:8083 (zona de taxa propria, corpo ate 2 MiB)')


def varrer(d, arquivos):
    pasta = os.path.join(d, 'senhas')
    valores = {n: le(os.path.join(pasta, n)).strip() for n in sorted(os.listdir(pasta))}
    achados = 0
    for a in arquivos:
        with open(a, "rb") as h:
            conteudo = h.read()
        for n, v in valores.items():
            if len(v) >= 8 and v.encode() in conteudo:
                print(f'SEGREDO ENCONTRADO: senhas/{n} em {os.path.basename(a)}')
                achados += 1
    print(f'varredura: {len(valores)} senhas x {len(arquivos)} arquivos, {achados} ocorrencias')
    return 1 if achados else 0


if __name__ == '__main__':
    acao = sys.argv[1]
    if acao == 'segredos':
        segredos(sys.argv[2], sys.argv[3])
    elif acao == 'pg_hba':
        pg_hba(sys.argv[2], sys.argv[3])
    elif acao == 'nginx':
        nginx(sys.argv[2], sys.argv[3])
    elif acao == 'varrer':
        sys.exit(varrer(sys.argv[2], sys.argv[3:]))
    else:
        raise SystemExit(__doc__)
