# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 — plano de implantação das pastas do gateway

**Só plano. Nada daqui foi aplicado em homologação nem em produção nesta missão**, por orientação da titular (15/11). O que
existe hoje é a configuração do laboratório isolado e do ambiente de testes de ponta a ponta.

## O que a execução remota passa a exigir do ambiente

| Pasta | Variável | Platform | Gateway (canal da máquina) | Para quê |
|---|---|---|---|---|
| Pacotes publicados | `NEKKORA_ARTEFATOS_DIR` | leitura e escrita (já é assim) | **somente leitura** (novo) | entregar à máquina o pacote da tentativa |
| Resultados | `NEKKORA_RESULTADOS_DIR` | **somente leitura** (novo) | leitura e escrita (novo) | guardar os arquivos que a máquina envia; a pessoa baixa pela Platform |

Regras que o plano preserva:

- O gateway **nunca escreve** na pasta de pacotes. A montagem é somente leitura no nível do sistema, não só no código.
- A Platform **nunca escreve** na pasta de resultados.
- As duas pastas são **separadas**: um resultado não pode ocupar o lugar de um pacote.
- Dentro de cada pasta o conteúdo é separado por organização e endereçado pelo SHA-256 dos bytes; o serviço confere o hash
  em toda leitura.
- A escrita do gateway na pasta de resultados é limitada pela aplicação a 20 MiB por arquivo, 50 arquivos e 50 MiB por
  tentativa, e só para tentativa que iniciou, com a credencial da máquina, o token e a época da atribuição.

## O que já está pronto (laboratório e testes)

| Onde | Estado |
|---|---|
| `api/e2e/Dockerfile` | cria `/var/lib/nekkora/artefatos` e `/var/lib/nekkora/resultados` com o dono do serviço (um volume montado herda o dono) |
| `api/e2e/compose.artefatos.yml` | Platform: pacotes leitura/escrita, resultados somente leitura. Gateway: pacotes somente leitura, resultados leitura/escrita |
| Laboratório da Web (`tools/lab/compose.front.yml`, PRs da Web) | a mesma montagem. Conferido no laboratório isolado: o gateway não consegue criar arquivo na pasta de pacotes; a Platform não consegue criar arquivo na pasta de resultados |

## O que falta para homologação e produção (a fazer, com aprovação)

A produção é um único servidor com os serviços em contêineres e os dados num volume próprio. Os passos abaixo são os
previstos; os arquivos de implantação ficam fora deste repositório e **não foram alterados**.

1. **Imagem**: a imagem de produção precisa criar a pasta de resultados com o dono do serviço, como a de testes. Conferir
   se a imagem de produção é a mesma receita; se não for, replicar a linha.
2. **Volume**: criar a pasta de resultados no volume de dados, ao lado da de pacotes, com o mesmo dono e permissão 700.
3. **Composição**:
   - gateway: montar a pasta de pacotes **somente leitura** e a de resultados com escrita; definir as duas variáveis;
   - Platform: montar a pasta de resultados **somente leitura**; definir `NEKKORA_RESULTADOS_DIR`.
4. **Proxy**: a rota `PUT /agent/api/v1/remote/attempts/{…}/results/{n}` recebe até 20 MiB e a rota
   `GET …/package` devolve até 50 MiB. **A conferir antes de publicar**: o limite de corpo e os tempos do proxy para
   `/agent/api/`. No laboratório só passou um arquivo pequeno; o limite real do proxy **não foi testado**.
5. **Espaço em disco**: resultados podem chegar a 50 MiB por tentativa e **hoje nada é apagado** (a retenção não foi
   implementada). Definir espaço reservado e alarme antes de ligar o recurso.
6. **Cópia de segurança**: decidir se a pasta de resultados entra na cópia de segurança. Os metadados estão no banco; os
   bytes, só na pasta.
7. **Ordem**: migrations 042 a 050 (todas candidatas, sem elevação) → imagens novas → montagens → só então
   `NEKKORA_EXECUCAO_REMOTA=true`, que nasce desligada.

## Conferência depois de aplicar (proposta)

- No contêiner do gateway: criar arquivo na pasta de pacotes **tem de falhar**; na de resultados, tem de funcionar.
- No contêiner da Platform: criar arquivo na pasta de resultados **tem de falhar**.
- Com o recurso ainda desligado: nenhuma rota nova aceita nada.
- Com o recurso ligado, num ambiente de homologação: o roteiro integrado do laboratório (execução com um arquivo de
  resultado, download pela Web, arquivo igual ao gravado).

## Reversão

- Desligar `NEKKORA_EXECUCAO_REMOTA`: nenhuma solicitação é aceita e nenhuma máquina recebe nada. As pastas e as
  montagens podem ficar; não são usadas.
- Remover as montagens novas do gateway e da Platform devolve o ambiente ao estado anterior. A pasta de resultados pode
  ser guardada ou apagada; os metadados no banco continuam dizendo o que existiu.
- As migrations têm a reversão manual escrita em cada arquivo, válida enquanto as tabelas novas estiverem vazias.

## Decisões em aberto

| Ponto | Situação |
|---|---|
| Migration 048 (versão restrita: o pacote fixado na tentativa, visível só à máquina dela e só na preparação) | desenho aprovado pela titular em 16/11 para implementação e laboratório; **aplicação em homologação e produção não autorizada** |
| Retenção de resultados e de registros | não implementada; sem ela o disco só cresce |
| Limites do proxy para envio e download | não testados |
| Cópia de segurança dos resultados | a decidir |
