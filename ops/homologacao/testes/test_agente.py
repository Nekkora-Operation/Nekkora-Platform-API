"""Testes da configuracao do canal do Agent na homologacao (lib/agente.py, E-AP-1). Sem Docker e sem rede.

  python ops/homologacao/testes/test_agente.py
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

AQUI = os.path.dirname(os.path.abspath(__file__))
OPS = os.path.dirname(AQUI)
LIB = os.path.join(OPS, 'lib')
sys.path.insert(0, LIB)
import agente  # noqa: E402
import segredos  # noqa: E402


def gerar(modelo, saida, interna):
    subprocess.run([sys.executable, os.path.join(LIB, 'redes.py'), 'gerar', modelo, saida, f'INTERNA={interna}'],
                   check=True, capture_output=True)


class CanalDoAgent(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix='agente-')
        self.seg = os.path.join(self.d, 'seg')
        segredos.gerar(self.seg, 'https://homologacao.nekkora.test:55791', '10.252.120')
        self.hba = os.path.join(self.d, 'pg_hba.conf')
        self.ngx = os.path.join(self.d, 'nginx.conf')
        gerar(os.path.join(OPS, 'postgres', 'pg_hba.modelo'), self.hba, '10.252.120')
        gerar(os.path.join(OPS, 'gateway', 'nginx.modelo'), self.ngx, '10.252.120')

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def test_pg_hba_acrescenta_so_o_ip_do_canal_antes_da_recusa(self):
        agente.pg_hba(self.hba, '10.252.120')
        linhas = [l for l in agente.le(self.hba).splitlines() if l and not l.startswith('#')]
        canal = [i for i, l in enumerate(linhas) if 'nekkora_ingestion' in l]
        recusa = [i for i, l in enumerate(linhas) if l.startswith('host ') and '0.0.0.0/0' in l]
        self.assertEqual(len(canal), 1)
        self.assertEqual(linhas[canal[0]].split(), ['hostssl', 'nekkora', 'nekkora_ingestion', '10.252.120.22/32', 'scram-sha-256'])
        self.assertLess(canal[0], recusa[0])

    def test_pg_hba_recusa_segunda_edicao_e_arquivo_desconhecido(self):
        agente.pg_hba(self.hba, '10.252.120')
        with self.assertRaises(SystemExit):
            agente.pg_hba(self.hba, '10.252.120')
        outro = os.path.join(self.d, 'outro.conf')
        agente.escreve(outro, 'local all all reject\n')
        with self.assertRaises(SystemExit):
            agente.pg_hba(outro, '10.252.120')

    def test_nginx_rota_do_canal_antes_da_platform_e_com_limites_proprios(self):
        agente.nginx(self.ngx, '10.252.120')
        t = agente.le(self.ngx)
        self.assertIn('server 10.252.120.22:8083;', t)
        self.assertIn('zone=agente:10m rate=100r/s', t)
        rota = t[t.index('location /agent/api/v1/ {'):]
        rota = rota[:rota.index('}')]
        self.assertIn('client_max_body_size 2m;', rota)
        self.assertIn('proxy_pass http://agent;', rota)
        self.assertNotIn('proxy_set_header', rota)   # X-Forwarded-For continua sobrescrito no server (nao herdado do cliente)
        self.assertLess(t.index('location /agent/api/v1/'), t.index('location /api/v1/ {'))
        self.assertIn('client_max_body_size 8k;', t)   # limite geral intacto
        with self.assertRaises(SystemExit):
            agente.nginx(self.ngx, '10.252.120')

    def test_segredos_fora_do_git_acl_restrita_e_sem_sobrescrever(self):
        acl_antes = agente.le(os.path.join(self.seg, 'redis', 'users.acl'))
        agente.segredos(self.seg, '10.252.120')
        env = agente.le(os.path.join(self.seg, 'agent-gateway.env'))
        senha = agente.le(os.path.join(self.seg, 'senhas', 'agent_gateway'))
        self.assertIn(f'NEKKORA_GATEWAY_DB_SENHA={senha}\n', env)
        self.assertIn('sslmode=verify-full', env)
        self.assertIn('NEKKORA_PROXIES_CONFIAVEIS=10.252.120.5/32', env)
        self.assertNotIn(agente.le(os.path.join(self.seg, 'senhas', 'platform')), env)   # nada da Platform
        acl = agente.le(os.path.join(self.seg, 'redis', 'users.acl'))
        self.assertTrue(acl.startswith(acl_antes))
        nova = acl[len(acl_antes):]
        self.assertTrue(nova.startswith('user agent on #'))
        self.assertIn(' ~agent:* -@all ', nova)
        self.assertNotIn(agente.le(os.path.join(self.seg, 'senhas', 'redis_agent')), acl)   # so o SHA-256
        with self.assertRaises(SystemExit):
            agente.segredos(self.seg, '10.252.120')

    def test_varredura_acha_senha_e_nao_imprime_valor(self):
        agente.segredos(self.seg, '10.252.120')
        log = os.path.join(self.d, 'log.txt')
        senha = agente.le(os.path.join(self.seg, 'senhas', 'redis_agent'))
        agente.escreve(log, 'linha comum\n')
        self.assertEqual(agente.varrer(self.seg, [log]), 0)
        agente.escreve(log, f'vazou {senha}\n')
        r = subprocess.run([sys.executable, os.path.join(LIB, 'agente.py'), 'varrer', self.seg, log], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)
        self.assertIn('senhas/redis_agent', r.stdout)
        self.assertNotIn(senha, r.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
