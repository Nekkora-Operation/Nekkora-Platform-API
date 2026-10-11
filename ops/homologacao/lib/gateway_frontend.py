"""Gateway do ensaio-frontend (F1.5, decisao F-6 (a)): a aplicacao web servida pelo MESMO gateway HTTPS das APIs.

  python gateway_frontend.py aplicar <nginx_gerado> <saida>
      Copia o nginx.conf GERADO do ambiente (redes.py gerar, a partir de gateway/nginx.modelo) trocando o bloco final
      "location / { return 404; }" pelo bloco da aplicacao (BLOCO_APLICACAO). Recusa (codigo 1) se a ancora nao
      aparecer EXATAMENTE uma vez, se o bloco ja estiver aplicado ou se a saida ja existir. Nada mais muda: as rotas
      /api/v1/ e /portal/api/v1/, o limite por rota, os cabecalhos de proxy e o log continuam os do modelo.

  python gateway_frontend.py conferir <nginx_gerado> <nginx_frontend>
      Confere (codigo 0/1) que <nginx_frontend> = <nginx_gerado> com a troca acima e mais nada (comparacao exata).

Propriedades do bloco (provadas por testes/test_gateway_frontend.sh num nginx descartavel):
  - /api/v1/ e /portal/api/v1/ continuam nas locations do modelo (prefixo mais longo vence; a regex de /auth/ do
    modelo continua valendo para /api/v1/); nenhum erro dessas APIs e trocado (sem error_page, sem
    proxy_intercept_errors);
  - qualquer outro caminho comecando por /api ou /portal responde 404 JSON proprio, nunca a aplicacao;
  - /assets/ so serve arquivo existente (404 se nao existir);
  - caminho com extensao de arquivo (ex.: /favicon.ico) inexistente: 404;
  - qualquer outro caminho (rotas da aplicacao, ex.: /o/<org>/execucoes/<id>) recebe o index.html, sem cache;
  - (P-C-R1) os arquivos do build saem com o tipo da extensao (TIPOS): html text/html, js/mjs text/javascript,
    css text/css, svg image/svg+xml, ico image/x-icon; sem isso o navegador recusa o script de modulo (tela em branco).
"""
import os
import sys

ANCORA = """        location / {
            return 404;
        }
"""

# P-C-R1: tipos dos arquivos do build, EXPLICITOS e proprios (o nginx.conf gerado nao inclui /etc/nginx/mime.types;
# sem isto o nginx serve js/css como text/plain e o navegador recusa o script de modulo: tela em branco). So nas
# locations que servem /spa; as respostas de /api e /portal nao mudam. Extensao fora da lista: default do nginx.
TIPOS = """            types {
                text/html html;
                text/javascript js mjs;
                text/css css;
                image/svg+xml svg;
                image/x-icon ico;
            }
"""

BLOCO_APLICACAO = """        # ---- aplicacao web (ensaio-frontend, F1.5): build imutavel desta execucao em /spa (somente leitura). Mesma
        # origem das APIs: os cookies __Host- e o Origin conferido pelo CSRF continuam os homologados.
        # Caminhos de API desconhecidos nunca recebem a aplicacao (404 JSON proprio).
        location ^~ /api {
            default_type application/json;
            return 404 '{"error":"not_found"}';
        }

        location ^~ /portal {
            default_type application/json;
            return 404 '{"error":"not_found"}';
        }

        # arquivos do build (nomes com hash): so o que existe; inexistente = 404
        location ^~ /assets/ {
            root /spa;
""" + TIPOS + """            try_files $uri =404;
        }

        location = /index.html {
            root /spa;
""" + TIPOS + """            add_header Cache-Control "no-store" always;
        }

        # arquivo existente do build; senao, rota da aplicacao (@spa)
        location / {
            root /spa;
""" + TIPOS + """            try_files $uri @spa;
        }

        # caminho com extensao de arquivo que nao existe: 404 (nunca a aplicacao); rotas da aplicacao: index.html
        location @spa {
            if ($uri ~ "\\.[A-Za-z0-9]+$") {
                return 404;
            }
            root /spa;
""" + TIPOS + """            add_header Cache-Control "no-store" always;
            try_files /index.html =404;
        }
"""


def aplicar(texto):
    if 'location @spa' in texto or '/spa' in texto:
        raise SystemExit('bloco da aplicacao ja presente: recuso aplicar de novo')
    n = texto.count(ANCORA)
    if n != 1:
        raise SystemExit(f'ancora "location / {{ return 404; }}" encontrada {n} vez(es); esperado exatamente 1')
    return texto.replace(ANCORA, BLOCO_APLICACAO)


def main():
    acao = sys.argv[1] if len(sys.argv) > 1 else ''
    if acao == 'aplicar' and len(sys.argv) == 4:
        entrada, saida = sys.argv[2], sys.argv[3]
        if os.path.exists(saida):
            raise SystemExit(f'saida ja existe: {saida}')
        texto = open(entrada, encoding='utf-8', newline='').read()
        novo = aplicar(texto)
        with open(saida, 'x', encoding='utf-8', newline='') as h:
            h.write(novo)
        print(f'bloco da aplicacao aplicado: {saida}')
        return
    if acao == 'conferir' and len(sys.argv) == 4:
        base = open(sys.argv[2], encoding='utf-8', newline='').read()
        efetivo = open(sys.argv[3], encoding='utf-8', newline='').read()
        try:
            esperado = aplicar(base)
        except SystemExit as e:
            print(f'FALHA: {e}')
            sys.exit(1)
        if esperado != efetivo:
            print('FALHA: nginx da aplicacao difere do gerado + bloco da aplicacao')
            sys.exit(1)
        print('OK: nginx da aplicacao = gerado + bloco da aplicacao, e mais nada')
        return
    raise SystemExit(__doc__)


if __name__ == '__main__':
    main()
