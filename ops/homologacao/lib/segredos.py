"""Segredos da homologacao: gerados por ambiente, gravados SO em HOMOLOG_SEGREDOS (fora do Git), nunca impressos.

Uso:
  python segredos.py gerar <dir> <origem_publica> [<prefixo_interna> [<id_ensaio_016>]]
      cria senhas, chaves, ACL do Redis, env files e flyway.conf. <prefixo_interna> (padrao 10.252.10) define o IP
      do gateway nos proxies confiaveis. Com <id_ensaio_016> (so ensaio-*): senhas de nekkora_ops_ca e
      nekkora_ops_intake e o operacao.env do job de operacao (desenho P1a, sec. 6).
  python segredos.py scram <arquivo_senha>          # imprime o verificador SCRAM-SHA-256 (nao a senha)

Os papeis PostgreSQL recebem o VERIFICADOR SCRAM (ALTER ROLE ... PASSWORD 'SCRAM-SHA-256$...'): a senha em texto claro
nunca vai ao servidor, e portanto nunca pode aparecer em log do banco. O Redis recebe so o SHA-256 das senhas na ACL.
"""
import base64
import hashlib
import hmac
import os
import secrets
import sys


def aleatoria(n=24):
    return base64.urlsafe_b64encode(secrets.token_bytes(n)).decode().rstrip('=')


def scram(senha, iteracoes=4096):
    sal = secrets.token_bytes(16)
    salgada = hashlib.pbkdf2_hmac('sha256', senha.encode(), sal, iteracoes)
    cliente = hmac.new(salgada, b'Client Key', hashlib.sha256).digest()
    armazenada = hashlib.sha256(cliente).digest()
    servidor = hmac.new(salgada, b'Server Key', hashlib.sha256).digest()
    b64 = lambda b: base64.b64encode(b).decode()
    return f'SCRAM-SHA-256${iteracoes}:{b64(sal)}${b64(armazenada)}:{b64(servidor)}'


def grava(caminho, conteudo):
    os.makedirs(os.path.dirname(caminho), exist_ok=True)
    with open(caminho, 'w', encoding='utf-8', newline='\n') as h:
        h.write(conteudo)
    try:
        os.chmod(caminho, 0o600)
    except OSError:
        pass


def gerar(d, origem, interna='10.252.10', ensaio=None):
    if os.path.exists(os.path.join(d, 'postgres_admin')):
        raise SystemExit(f'segredos ja existem em {d}: nao sobrescrevo (substituicao e um procedimento proprio)')
    if ensaio is not None and not ensaio.startswith('ensaio-'):
        raise SystemExit('segredos da 016 (operacao.env) so para ambiente ensaio-*')
    s = {k: aleatoria() for k in ('postgres_admin', 'migrador', 'platform', 'portal', 'backup', 'redis_platform', 'redis_portal',
                                  'ops_ca', 'ops_intake')}
    grava(os.path.join(d, 'postgres_admin'), s['postgres_admin'])
    for k in ('migrador', 'platform', 'portal', 'backup', 'redis_platform', 'redis_portal') + (('ops_ca', 'ops_intake') if ensaio else ()):
        grava(os.path.join(d, 'senhas', k), s[k])
    aes = base64.b64encode(secrets.token_bytes(32)).decode()
    hmac_chave = base64.b64encode(secrets.token_bytes(32)).decode()
    url = 'jdbc:postgresql://postgres:5432/nekkora?sslmode=verify-full&sslrootcert=/tls/ca.crt'
    redis_comum = ('NEKKORA_REDIS_HOST=redis\nNEKKORA_REDIS_PORT=6379\nSPRING_DATA_REDIS_SSL_BUNDLE=redis\n'
                   'SPRING_SSL_BUNDLE_PEM_REDIS_TRUSTSTORE_CERTIFICATE=file:/tls/ca.crt\n')
    grava(os.path.join(d, 'platform.env'),
          f'NEKKORA_DB_URL={url}\nNEKKORA_DB_SENHA={s["platform"]}\nNEKKORA_ORIGEM_PUBLICA={origem}\n'
          f'NEKKORA_PROXIES_CONFIAVEIS={interna}.5/32\nNEKKORA_MFA_CHAVE_ID=k1\nNEKKORA_MFA_CHAVES=k1:{aes}\n'
          f'NEKKORA_HMAC_ID=h1\nNEKKORA_HMAC_CHAVE={hmac_chave}\n{redis_comum}'
          f'SPRING_DATA_REDIS_USERNAME=platform\nSPRING_DATA_REDIS_PASSWORD={s["redis_platform"]}\n')
    grava(os.path.join(d, 'portal.env'),
          f'NEKKORA_PORTAL_DB_URL={url}\nNEKKORA_PORTAL_DB_SENHA={s["portal"]}\nNEKKORA_PROXIES_CONFIAVEIS={interna}.5/32\n'
          f'{redis_comum}SPRING_DATA_REDIS_USERNAME=portal\nSPRING_DATA_REDIS_PASSWORD={s["redis_portal"]}\n')
    grava(os.path.join(d, 'flyway.conf'),
          f'flyway.url={url.replace("/tls/ca.crt", "/tls/ca.crt")}\nflyway.user=migrador_nekkora\nflyway.password={s["migrador"]}\n')
    if ensaio:
        # job de operacao (so ensaio-*): URL JDBC sem parametros de TLS (a CLI e a ferramenta fixam verify-full com a raiz
        # do servidor); CA OPERACIONAL descartavel (so este job a monta); senhas dos servicos da 016 simulados no job
        grava(os.path.join(d, 'operacao.env'),
              f'NEKKORA_AMBIENTE={ensaio}\nNEKKORA_OPS_DB_URL=jdbc:postgresql://postgres:5432/nekkora\n'
              'NEKKORA_OPS_RAIZ_SERVIDOR=/tls/ca.crt\nHOMOLOG_OPS_CA_CRT=/tls/ops-ca.crt\nHOMOLOG_OPS_CA_KEY=/tls/ops-ca.key\n'
              f'HOMOLOG_OPS_CA_SENHA={s["ops_ca"]}\nHOMOLOG_OPS_INTAKE_SENHA={s["ops_intake"]}\nHOMOLOG_OPERADORES=/operadores\n'
              'NEKKORA_OPS_LOG=/operadores/cli.log\n'
              # versao vigente da chave de pseudonimizacao no ensaio (a 016 semeia a versao 1; nenhuma ativacao no ensaio).
              # Configuracao CONTROLADA: o servico de entrada nao le tabelas; versao divergente -> ENTRADA_INVALIDA
              'HOMOLOG_OPS_INTAKE_VERSAO=1\n')
    # ACL minima por servico: comandos usados pelo limitador (scripts Lua), pelo CSRF de pre-autenticacao e pela conexao
    cmds = '+@connection +get +set +del +eval +evalsha +hmget +hset +incr +pexpire +time +zadd +zcard +zremrangebyscore'
    sha = lambda x: hashlib.sha256(x.encode()).hexdigest()
    grava(os.path.join(d, 'redis', 'users.acl'),
          'user default off resetkeys -@all\n'
          f'user platform on #{sha(s["redis_platform"])} resetchannels ~nk:* -@all {cmds}\n'
          f'user portal on #{sha(s["redis_portal"])} resetchannels ~nk:rl:* -@all {cmds}\n')
    grava(os.path.join(d, 'LEIA-ME.txt'),
          'Segredos da homologacao Nekkora - gerados por ops/homologacao/homologacao.sh. NAO versionar, NAO copiar para outro\n'
          'ambiente. Substituicao: procedimento em ops/homologacao/README.md (secao Segredos).\n'
          'postgres_admin: administrador do cluster (bootstrap/operacao). senhas/: papeis e usuarios Redis por servico.\n'
          'platform.env / portal.env: configuracao sensivel de cada processo. flyway.conf: identidade de implantacao.\n'
          'tls/: CA da homologacao (ca.key e o ativo mais sensivel) e certificados do gateway, PostgreSQL e Redis.\n'
          'Com a 016 (ensaio-*): tls/ops-ca.* = CA OPERACIONAL descartavel; operacao.env = job de operacao (senhas de\n'
          'nekkora_ops_ca/ops_intake). Caixa do transporte e credenciais emitidas ficam em VOLUMES Docker do ensaio.\n')


if __name__ == '__main__':
    if sys.argv[1] == 'gerar':
        gerar(sys.argv[2], sys.argv[3], *(sys.argv[4:6]))
    elif sys.argv[1] == 'scram':
        print(scram(open(sys.argv[2], encoding='utf-8').read().strip()))
    else:
        raise SystemExit('uso: segredos.py gerar <dir> <origem> | scram <arquivo>')
