# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 — T01b: continuidade da credencial da máquina (API)

Segunda parte da T01. Depende da T01a (`t01a-habilitacao-e-elegibilidade.md`, migration 042).

## O problema

A credencial da máquina vale 90 dias. Até aqui, só era trocada com um **código de rotação criado por uma pessoa**. Uma
máquina habilitada para execução remota precisa continuar valendo sem que alguém refaça a habilitação a cada 90 dias — e
sem transformar o token de uma pessoa em credencial permanente.

## O que muda

| Onde | Mudança |
|---|---|
| Banco | migration `043_renovacao_da_credencial_da_maquina.sql` |
| Gateway (máquina) | `POST /agent/api/v1/remote/credential/renew` |
| Configuração | `NEKKORA_EXECUCAO_REMOTA_RENOVACAO_DIAS` (padrão 30), só no gateway |
| Contrato | `openapi/nekkora-agent-v1.yaml` (aditivo) |

Nada muda no lado das pessoas, na Web nem no contrato das pessoas.

## Como funciona

A máquina prova a posse da credencial **atual** e envia um **segredo novo, gerado nela**. Numa transação só:

1. registra a renovação;
2. revoga a credencial anterior, com autoria **da própria máquina** (motivo novo `MACHINE_RENEWAL`, sem pessoa);
3. emite a nova, com o prazo cheio e o **mesmo emissor** da anterior.

A renovação **herda** a autoridade de quem autorizou o vínculo; não cria autoridade nova. O vínculo, o Worker, a
habilitação e o recebimento (inclusive pausado) não mudam.

Por que uma transação só, e não "emite a nova, confirma, revoga a antiga" (como o plano do T00 descrevia): o banco
garante, desde a 004, **uma única credencial não revogada por máquina**. Manter essa regra é mais seguro do que abrir
uma janela com duas credenciais válidas; a repetição segura (abaixo) cobre o que a confirmação em dois passos cobriria.

## Regras

| Situação | Resposta |
|---|---|
| Dentro da janela (faltam até 30 dias) | `200` com a credencial nova |
| Fora da janela | `409 conflict`, `reason: credential_renewal_not_due`; nada muda |
| Credencial **vencida** | `401`. Nunca é renovada |
| Credencial revogada (pessoa, rotação, desvínculo) | `401` |
| Máquina vinculada mas **não habilitada** para execução remota | `409`, `remote_execution_not_enabled` |
| Recurso desligado | `409`, `remote_execution_unavailable` |
| Segredo novo malformado ou igual ao atual | `400` |

Vencida ou revogada, a credencial **nunca volta a valer**: a saída é habilitar a máquina de novo, com um código novo
criado por quem administra. Não há atalho pelo token de uma pessoa: essa rota só aceita credencial de máquina.

## Resposta perdida

Se a resposta se perder, a máquina já não sabe o identificador da credencial nova. Ela repete o pedido com a credencial
**anterior** (já revogada por esta troca) e o **mesmo** segredo novo, e recebe a mesma credencial nova.

É a única situação em que uma credencial revogada é aceita nesta rota, e só para repetir a troca que a revogou: com
outro segredo, ou em qualquer outra rota, a resposta é `401`. Se a credencial nova já tiver sido revogada por uma
pessoa, a repetição também recebe `401`.

## Banco (043)

- `agent_credential_renewals`: uma linha por credencial de origem. Só nasce para máquina habilitada, ativa e associada,
  com a credencial ainda não revogada e não vencida.
- `agent_credentials`: motivo de revogação 6 (`MACHINE_RENEWAL`), aceito só na transação que registrou a renovação
  daquela credencial. Nessa transação, a credencial nova só nasce com o hash registrado e o emissor da anterior.
- Verificação no fim da transação: renovação registrada sem a troca completa desfaz tudo.
- `agent_admin_events`: ato 18 (credencial renovada pela máquina).
- A máquina (`nekkora_ingestion`) registra; as pessoas (`nekkora_platform`) só leem; ninguém altera nem apaga.

A função de gatilho da 035 (`agent_credential_unlink_guard`) é substituída para conhecer os dois atos da máquina: 5 pelo
desvínculo (como antes) e 6 pela renovação. A rotação por código (motivo 1) e a revogação por pessoas não mudam.

**Não foi aplicada em nenhum ambiente além do banco descartável dos testes.** Aditiva; reversão no cabeçalho.

## Compatibilidade com o Agent antigo

O Agent antigo não é habilitado para execução remota, então não renova por esta rota (`409`) e continua usando o código
de rotação, exatamente como hoje. Nenhuma rota, resposta ou regra dele foi alterada.

## Testes (executados neste PR)

| Requisito | Teste |
|---|---|
| Troca dentro da janela; anterior não vale mais; mesmo emissor; vínculo, habilitação e pausa preservados; auditoria | `CredentialRenewalIT.dentroDaJanelaAMaquinaTrocaAPropriaCredencialEOVinculoContinua` |
| Resposta perdida; credencial revogada só repete a própria troca | `…respostaPerdidaARepeticaoComOMesmoSegredoDevolveAMesmaCredencial` |
| Fora da janela, vencida, revogada, não habilitada, segredo inválido: nada é renovado | `…foraDaJanelaVencidaRevogadaOuNaoHabilitadaNadaERenovado` |
| O banco só aceita a troca completa de uma máquina habilitada | `…oBancoSoAceitaATrocaCompletaDaCredencialDeUmaMaquinaHabilitada` |
| Recurso desligado | `RemoteEnablementDisabledIT` |
| Rota e motivo publicados | `GatewayContractTest` |

Os testes aproximam o vencimento por preparação de dados: as datas de uma credencial são imutáveis para a aplicação.

## Fora deste PR / não provado

- O lado do Client (renovar sozinho, guardar o segredo novo antes de pedir, repetir após queda): PR próprio.
- Renovação real ao longo de 60 dias de calendário: não é possível provar em laboratório sem adiantar datas.
- Trabalho em andamento quando a credencial é revogada: depende da execução (T03/T04).
