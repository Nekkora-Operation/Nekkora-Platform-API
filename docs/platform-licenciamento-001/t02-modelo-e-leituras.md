# PLATFORM-LICENCIAMENTO-001 — T02: modelo de licenças e leituras do administrador (E1 + E2)

O inventário, o contrato e as decisões estão em `t00-inventario-e-contrato.md` e `t01-decisoes-e-esclarecimentos.md`
(PR da documentação). Este documento descreve só o que esta PR entrega.

## O que entra

- **Migration 061** (sem elevação): cinco tabelas novas, todas vazias.
- **Duas rotas de leitura** para quem administra a organização (`org.manage`).
- **Nenhuma conferência de licença.** Nenhum caminho de execução ou de monitoração lê estas tabelas. Toda organização
  continua no modelo anterior.

## O que não entra

- Nenhuma licença é criada, por migration ou por código.
- Ninguém ganha rota de escrita: o vínculo pelo administrador (D1) e os atos do operador (D5) são as próximas etapas.
- A ativação, os limites e as autorizações de hoje não são tocados.

## Modelo (migration 061)

| Tabela | O que guarda | Regra garantida pelo banco |
|---|---|---|
| `organization_licenses` | uma linha por licença, com número sequencial por organização | só muda para ser encerrada, uma vez; não é apagada |
| `organization_license_periods` | períodos de validade: 1 = concessão, 2 = renovação | só acréscimo; o período novo não começa antes do fim do anterior; licença encerrada não renova |
| `organization_license_assignments` | vínculo licença → automação | no máximo um vínculo aberto por licença e uma licença por automação (índices únicos); só abre em licença vigente; só muda para ser encerrado |
| `organization_license_acts` | trilha dos atos | só acréscimo; ato do operador exige justificativa; ato de transferência exige origem, destino e solicitante |
| `organization_license_enforcement` | modelo da organização: 1 = anterior, 2 = por licença | só muda acompanhado do ato que liga ou desliga; sem linha = modelo anterior |

**Vigente**, em toda leitura: a licença não foi encerrada e tem um período com `início <= agora < fim`.

Como as regras da missão aparecem no modelo:

- *Versões, Workers, máquinas e usuários não multiplicam o consumo*: a chave do vínculo é a automação; nenhum deles
  entra.
- *Pausar, renomear ou arquivar não libera a licença*: o vínculo não depende da situação nem do nome da automação.
- *Ativações simultâneas não consomem a mesma licença*: índice único por licença; a segunda transação é recusada.
- *Renovar mantém o vínculo e preserva os períodos anteriores* (D4): renovar é acrescentar um período.
- *Isolamento*: todas as tabelas têm isolamento por organização forçado; o serviço só lê as linhas da organização da
  sessão, e as chaves estrangeiras são sempre compostas com a organização.

A Platform recebe **só leitura** nas cinco tabelas. Reversão: enquanto estiverem vazias, apagar as tabelas e as funções
(comandos no cabeçalho da migration).

## Leituras

### `GET /api/v1/o/{org}/licenses/summary`

```json
{ "model": "legacy", "modelSince": null,
  "contracted": 3, "used": 2, "available": 1,
  "licensedAutomations": 2, "expiredWithAutomation": 1,
  "asOf": "2026-11-19T12:00:00Z" }
```

- É uma agregação no banco sobre todas as licenças; não depende de página.
- `contracted` = vigentes; `used` = vigentes com automação; `available` = vigentes sem automação.
- `licensedAutomations` = automações com licença ativa (igual a `used`: uma licença atende uma automação).
- `expiredWithAutomation` = licenças vencidas que continuam vinculadas, à espera de renovação.
- `model`: `legacy` ou `licensed`, com `modelSince`. É como a interface mostra quem está em qual modelo.

Texto que a tela monta com isso: "3 licenças contratadas · 2 utilizadas · 1 disponível — 2 automações com licença
ativa".

### `GET /api/v1/o/{org}/licenses`

Uma linha por licença, em ordem de número, com paginação por cursor. Cada item traz situação (`active`, `scheduled`,
`expired`, `ended`), validade, renovações e a automação vinculada com o ambiente de trabalho, a situação da automação e
`executionInProgress`. **Licença ativa e execução em andamento são campos separados**: uma automação parada continua
consumindo a licença.

Quem lê: só administrador da organização. Administrador de workspace, operador, leitor e conta de Cliente recebem 403.

## Testes (`LicenseReadsIT`, banco real descartável com todas as migrations, papel real do serviço)

| Requisito | O que o teste faz |
|---|---|
| Nada é concedido automaticamente | organização nova: resumo zerado, modelo anterior, tabelas vazias |
| Contagens da API e conjunto inteiro | seis licenças em situações diferentes; o resumo é comparado com a lista lida em páginas de 2 |
| Licença ativa ≠ execução em andamento | uma automação licenciada com execução em andamento e outra arquivada, parada, ainda consumindo |
| Renovação | acrescenta período, preserva o anterior, mantém o vínculo |
| Isolamento entre organizações | outra organização só vê as suas; sessão de uma não lê a outra; licença de uma não atende automação da outra |
| Permissões de leitura | administrador de workspace, Cliente e sem sessão não leem |
| O serviço só lê | com o papel real: sem contexto, nenhuma linha; inserir, vincular, encerrar e ligar a exigência são negados |
| Concorrência sem consumo duplicado | duas transações simultâneas na mesma licença, e duas licenças para a mesma automação: exatamente uma vence |
| Pausa e arquivamento não liberam | a licença da automação suspensa ou arquivada não aceita outra automação |
| Só acréscimo | período, vínculo, licença e ato não são alterados nem apagados |
| Modelo visível | ligado por ato, o resumo passa a dizer `licensed` |

Cada recusa do banco é conferida pelo motivo, não só pelo fato de falhar.

Limite destes testes: como ainda não existe rota de escrita, a massa é gravada direto no banco. A concorrência provada
aqui é a do banco; a da rota de vínculo será provada na etapa que a cria.

## Próximas etapas

1. Vínculo pelo administrador (D1): rota, confirmação reforçada, registro do ato.
2. Migration 062, **com elevação**: cadastro de operadores e atos de conceder, renovar, encerrar e ligar a exigência com
   prévia (t01, seções 4 e 5). Em laboratório; homologação e produção só com autorização.
3. Conferência no servidor, tela do administrador e demais etapas do t00, conforme as decisões pendentes fecharem.
