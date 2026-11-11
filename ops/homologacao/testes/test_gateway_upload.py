"""Limite de corpo dos gateways nginx (PLATFORM-AGENT-V2-002 R1). Sem Docker e sem rede.

  python ops/homologacao/testes/test_gateway_upload.py

Os gateways de e2e/laboratorio (api/e2e/nginx.conf) e da homologacao (gateway/nginx.conf e o nginx.conf gerado de
gateway/nginx.modelo, inclusive depois do bloco da aplicacao web e da rota do canal do Agent) precisam:
- manter o limite geral de 8 KiB;
- aceitar ate 50 MiB SO nos PUT exatos de pacote: artefato de uma versao e pacote do rascunho;
- nao estender os 50 MiB a nenhum outro caminho.
"""
import os
import re
import subprocess
import sys
import tempfile
import unittest

AQUI = os.path.dirname(os.path.abspath(__file__))
OPS = os.path.dirname(AQUI)
RAIZ = os.path.dirname(os.path.dirname(OPS))
LIB = os.path.join(OPS, 'lib')
sys.path.insert(0, LIB)
import agente  # noqa: E402
import gateway_frontend  # noqa: E402

ORG = '0a6e1f9c-3b2d-4c5e-8f71-2a9b3c4d5e6f'
ID = '7f8091a2-3b4c-4d5e-8f60-718293a4b5c6'
ACEITOS = [
    f'/api/v1/o/{ORG}/automation-versions/{ID}/artifact',
    f'/api/v1/o/{ORG}/automations/{ID}/draft/artifact',
]
RECUSADOS = [
    f'/api/v1/o/{ORG}/automations/{ID}/versions',
    f'/api/v1/o/{ORG}/automations/{ID}/draft/artifact/x',
    f'/api/v1/o/{ORG}/automations/{ID}/draft',
    f'/api/v1/o/{ORG}/automation-versions/{ID}/publish',
    f'/api/v1/o/{ORG}/workspaces/{ID}/automations',
    f'/api/v1/o/{ORG}/auth/login',
    f'/api/v1/o/../automations/{ID}/draft/artifact',
    f'/x/api/v1/o/{ORG}/automations/{ID}/draft/artifact',
]


def le(caminho):
    with open(caminho, encoding='utf-8', newline='') as f:
        return f.read().replace('\r\n', '\n')


def locais_50m(texto):
    """(regex, bloco) de cada location com client_max_body_size 50m."""
    achados = []
    for m in re.finditer(r'location ~ (\S+) \{(.*?)\n        \}', texto, re.S):
        if 'client_max_body_size 50m;' in m.group(2):
            achados.append((m.group(1), m.group(2)))
    return achados


class LimiteDeCorpo(unittest.TestCase):
    def conferir(self, texto, nome):
        http = texto.split('server {', 1)[0]
        self.assertIn('client_max_body_size 8k;', http, f'{nome}: limite geral de 8 KiB')
        self.assertEqual(texto.count('client_max_body_size 50m;'), 1, f'{nome}: um unico bloco de 50 MiB')
        locais = locais_50m(texto)
        self.assertEqual(len(locais), 1, nome)
        regex, bloco = locais[0]
        self.assertIn('proxy_pass http://platform;', bloco, nome)
        self.assertIn('limit_req zone=geral', bloco, nome)
        r = re.compile(regex)
        for p in ACEITOS:
            self.assertTrue(r.search(p), f'{nome}: {p} deveria aceitar 50 MiB')
        for p in RECUSADOS:
            self.assertFalse(r.search(p), f'{nome}: {p} nao pode receber 50 MiB')
        # o bloco de 50 MiB vem antes da regex de /auth/ e do prefixo /api/v1/ (regex vence prefixo; ordem entre regex)
        self.assertLess(texto.index('client_max_body_size 50m;'), texto.index('location ~ ^/api/v1/o/[^/]+/auth/'), nome)

    def test_e2e_e_laboratorio(self):
        self.conferir(le(os.path.join(RAIZ, 'api', 'e2e', 'nginx.conf')), 'api/e2e/nginx.conf')

    def test_homologacao_estatico(self):
        self.conferir(le(os.path.join(OPS, 'gateway', 'nginx.conf')), 'gateway/nginx.conf')

    def test_homologacao_gerado_com_aplicacao_e_canal_do_agent(self):
        d = tempfile.mkdtemp(prefix='gateway-')
        saida = os.path.join(d, 'nginx.conf')
        subprocess.run([sys.executable, os.path.join(LIB, 'redes.py'), 'gerar', os.path.join(OPS, 'gateway', 'nginx.modelo'), saida,
                        'INTERNA=10.252.120'], check=True, capture_output=True)
        self.conferir(le(saida), 'gerado do modelo')
        com_web = gateway_frontend.aplicar(le(saida))
        self.conferir(com_web, 'gerado + aplicacao web')
        with open(saida, 'w', encoding='utf-8', newline='') as f:
            f.write(com_web)
        agente.nginx(saida, '10.252.120')
        final = le(saida)
        self.assertIn('location /agent/api/v1/', final)
        self.conferir(final, 'gerado + aplicacao web + canal do Agent')


if __name__ == '__main__':
    unittest.main(verbosity=2)
