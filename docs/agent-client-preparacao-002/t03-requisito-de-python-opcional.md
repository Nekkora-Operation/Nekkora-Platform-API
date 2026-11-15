# AGENT-CLIENT-PREPARACAO-002 · T03 — requisito de Python opcional no pacote Python externo

O `nekkora-external.json` exigia o campo `python`. Quem gerava um pacote era obrigado a declarar uma restrição, mesmo sem ter
uma. Este documento define o **descritor 1.1**, em que o requisito é opcional, e o que cada consumidor faz com ele.

## 1. Descritor 1.1

| Campo | Descritor 1.0.x | Descritor 1.1.x |
|---|---|---|
| `version` | `1.0.<n>` | `1.1.<n>` |
| `python` | **obrigatório**; formas `>=X.Y`, `==X.Y`, `==X.Y.*`, `~=X.Y`, cada uma com `,<X.Y` opcional | **opcional**; as formas da 1.0 e mais a versão exata `==X.Y.Z` |
| demais campos, hash por arquivo, regra da impressão digital | — | **sem mudança** (`nekkora-agent/external-python-fingerprint/1`) |

- **Ausência** é a chave `python` **não existir** no descritor 1.1. Quer dizer: *o autor não declarou restrição de Python*.
  Não quer dizer que o pacote foi testado em todos os Pythons.
- Chave presente com `null`, texto vazio, número ou qualquer texto fora das formas acima é **recusada** (`python_invalido`).
  Ninguém grava `>=0`, uma faixa arbitrária ou uma versão fictícia para satisfazer o formato.
- A versão exata `==X.Y.Z` compara a versão inteira do interpretador. As formas da 1.0 comparam só `X.Y`, como sempre.
- O gerador (Nekkora-Agent-Client) só grava `1.1.0` quando o pacote **precisa** da 1.1 (sem requisito, ou com versão exata).
  Com um requisito da 1.0 ele continua gravando `1.0.0`, byte a byte como antes.

## 2. O que a Platform faz

- `PackageInspector` aceita `1.0.x` e `1.1.x`. Qualquer outra versão: `manifesto_fora_do_contrato`.
- Artefato de descritor 1.1 sem requisito é gravado com `python_requirement` **NULL**; a resposta traz
  `"pythonRequirement": null` e `"manifestVersion": "1.1.0"`.
- Migration **041** (`db/migrations/041_requisito_de_python_opcional.sql`): só troca dois CHECKs de `automation_artifacts`.
  O banco continua recusando, por conta própria: descritor 1.0 sem requisito, versão exata num descritor 1.0, requisito fora
  das formas e versão de descritor desconhecida. Nenhuma linha existente é alterada; sem elevação.
- Nada mais muda: permissões, publicação, download, auditoria e o pacote do Studio ficam como estão.

## 3. Compatibilidade e ordem de integração

| Consumidor | Pacote 1.0 | Pacote 1.1 |
|---|---|---|
| Platform API **com** este PR e a 041 | aceita | aceita |
| Platform API anterior | aceita | recusa o envio (`manifesto_fora_do_contrato`) |
| Nekkora-Agent-Client com o T03 | executa | executa; sem requisito, o ambiente ainda precisa responder à sondagem e ter as dependências declaradas |
| Nekkora-Agent-Client até `0b89357` | executa | **recusa** como pacote inválido |
| Nekkora Agent (`9b1e7e1`, não alterado) | executa | **recusa** como pacote inválido (`AGT-EXE-021`) |

Nenhum consumidor antigo executa um pacote 1.1 ignorando o que não entende: todos recusam.

Ordem:

1. **Banco**: aplicar a 041 (exige a 040).
2. **API**: publicar este PR. Com a API nova e o banco sem a 041, o envio de um pacote 1.1 falha no CHECK e nada é gravado.
3. **Client**: o T03 do Nekkora-Agent-Client pode entrar antes ou depois; antes, os pacotes 1.1 que ele gera só não são
   aceitos pela Platform até os passos 1 e 2.

Limite conhecido: um pacote 1.1 publicado na Platform **não** é executado pelo Nekkora Agent antigo, que o recusa. Para uma
automação que precisa rodar nele, declare um requisito de Python nas formas da 1.0.

## 4. Testes

- `PackageInspectorExternalTest`: descritor 1.1 sem requisito e com versão exata; 1.0 sem requisito, chave `null`/vazia/número,
  `>=0` e versões desconhecidas recusados.
- `AutomationLifecycleIT.pacote_externo_sem_requisito_de_python_e_aceito_no_descritor_1_1_e_recusado_no_1_0`: envio real pela
  rota de artefato, com PostgreSQL e as migrations 001–041; `python_requirement` NULL no banco e `null` na resposta; os CHECKs
  novos conferidos numa cópia da linha (o artefato é imutável).
