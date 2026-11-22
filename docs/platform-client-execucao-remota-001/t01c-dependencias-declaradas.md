# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 — T01c, parte 2: dependências declaradas da automação (API)

Vem depois da parte 1 (inventário, 044) na numeração. Não depende das tabelas da execução remota: só das automações.

## Para que serve

Uma máquina só pode executar uma automação a pedido da Platform se alguém tiver dito **quais bibliotecas ela precisa**.
O pacote publicado já traz as do manifesto; esta parte deixa uma pessoa **acrescentar** ou **confirmar** as dependências
na Platform, sem tocar no pacote. A verificação de que uma máquina as tem instaladas e a escolha do ambiente vêm depois
(dependem do canal de entrega da T02).

**Nada é instalado, resolvido ou baixado**: a leitura é só de texto.

## O que muda

| Onde | Mudança |
|---|---|
| Banco | migration `045_dependencias_declaradas_da_automacao.sql` |
| Platform (pessoas) | `GET` e `PUT /api/v1/o/{org}/automations/{automation}/runtime-requirements`; `POST …/runtime-requirements/analysis` |
| Contrato | `openapi/nekkora-api-v1.yaml` (aditivo) |

Nada muda no canal da máquina.

## Origens de uma declaração

| `source` | O que a pessoa está dizendo | Texto |
|---|---|---|
| `PACKAGE` | Só as do pacote | não |
| `NONE_CONFIRMED` | Sem dependências externas, confirmado | não |
| `TEXT` | Lista digitada | sim |
| `FILE` | Conteúdo de um `requirements.txt` enviado | sim |

## Leitura estática, com as regras do aplicativo

A leitura (`RequirementsText`) foi **portada** do `RequirementsFile` do Nekkora-Agent-Client. As duas implementações são
presas pelo **mesmo arquivo de vetores** (`requirements-vectors.json`, cópia idêntica nos dois repositórios): a Platform
e o aplicativo leem um `requirements.txt` do mesmo jeito.

- Só `nome==versão` (com extras) entra. Repetida igual é usada uma vez.
- Faixa, sem versão, marcador de ambiente, endereço remoto, caminho local, inclusão de outro arquivo, opção do pip, hash e
  a mesma biblioteca com duas versões são **ditos por linha** e **impedem salvar**. Nenhuma versão é inventada.
- `POST …/analysis` devolve a situação de cada linha **sem gravar**, para a tela mostrar antes de a pessoa declarar.
- `PUT` com alguma linha com problema: `409 conflict`, `reason: runtime_requirements_unresolved`, nada gravado.

## Revisões

Cada declaração é uma revisão nova, numerada, com o hash da lista, quem declarou e quando. Nada é reescrito nem apagado.

`expectedRevision` é a revisão que a pessoa viu (ausente = nenhuma). Se outra pessoa declarou nesse meio tempo:
`409 conflict`, `reason: runtime_requirements_changed`, nada gravado.

## O efetivo

`efetivo = dependências do manifesto do pacote publicado ∪ acréscimos declarados`.

A mesma biblioteca declarada com versão ou extras diferentes dos do manifesto é **conflito**: aparece em
`effective.conflicts`, `effective.resolved` fica `false`, e o que vale continua sendo o do manifesto. **O manifesto nunca
é enfraquecido nem substituído.** Salvar com conflito é permitido (a pessoa vê o conflito na tela); a solicitação de
execução remota (T02) é que vai exigir um efetivo resolvido.

## O que não muda

- O pacote publicado e a versão (o manifesto é imutável).
- Gerar pacote e executar localmente pelo aplicativo.
- Declarar **não verifica máquina nenhuma e não autoriza execução**.

## Permissões

Ler: `automation.read` no workspace da automação. Analisar e declarar: `automation.manage`. Fora do alcance: `404`.
O banco reconfere a autoria (`automation.manage`) e a sequência das revisões por gatilho.

## Banco (045)

- `automation_runtime_requirements`: revisões, append-only. `nekkora_platform` lê e insere; não altera nem apaga. A
  máquina (`nekkora_ingestion`) **ainda não lê** esta tabela: a entrega do efetivo à máquina é da T02.
- `automation_admin_events`: ato **10** (dependências declaradas). O ato 9 já existia (download de pacote, 040).

Aditiva; reversão no cabeçalho. **Só aplicada no banco descartável dos testes.**

## Testes (executados neste PR)

| Requisito | Teste |
|---|---|
| A leitura da API dá as mesmas situações da do aplicativo | `RequirementsTextTest` (vetores comuns; o mesmo arquivo roda no Client) |
| Efetivo: manifesto primeiro, acréscimos depois; conflito não enfraquece o manifesto | `RuntimeRequirementsEffectiveTest` |
| Declarar cria revisões com autoria; as quatro origens; auditoria | `RuntimeRequirementsIT.declararCriaRevisoesComAutoriaESoNomeIgualVersaoEntra` |
| Linha com problema impede salvar; a análise diz por linha sem gravar; pedidos malformados | `…linhaComProblemaImpedeSalvarEAAnaliseDizPorLinhaSemGravar` |
| Revisão vista, permissões, outra organização, guardas do banco, a máquina não lê | `…revisaoVistaPermissoesIsolamentoEGuardasDoBanco` |

## Fora deste PR / não provado

- Web (tela de dependências na automação): PR próprio.
- O conflito com o manifesto de um **pacote publicado de verdade** só é conferido pela regra (`RuntimeRequirementsEffectiveTest`),
  não por um teste de integração com publicação.
- Verificação na máquina e escolha do ambiente; uso do efetivo numa solicitação (T02).
