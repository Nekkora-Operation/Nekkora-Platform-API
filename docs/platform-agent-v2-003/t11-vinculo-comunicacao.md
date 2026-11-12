# PLATFORM-AGENT-V2-003 · T11: vínculo e comunicação do Agent apresentados corretamente

## Problema
Depois de desvincular a máquina no Agent, a Web continuava mostrando "ativo". O diagnóstico encontrou quatro causas:
1. **O "ativo" é administrativo.** O que a Web mostrava era a situação administrativa (`agents.status = ACTIVE`, "Agent ativo") ou a do Worker ("Ativo"), não a comunicação.
2. **O desvínculo do Agent é local por decisão do T06**, mantida nesta missão. A máquina não tem rota para se desvincular no servidor, e a Platform só encerra o vínculo quando uma pessoa revoga o Agent.
3. **"Conectada" durava até 180 s depois do desvínculo.** A prévia do desvínculo consulta a Platform com um heartbeat real.
4. **A Web não atualizava sozinha**, e uma falha de consulta trocava a lista inteira por um erro.

A API também tinha um defeito: um Agent **revogado** aparecia `CONNECTED` por até 180 s depois da revogação, porque a comunicação considerava só o último sinal.

## Três dimensões, sempre separadas
| Dimensão | Origem | Valores |
|---|---|---|
| Vínculo | `agents.status` + associação ativa ao Worker | vinculado (`ACTIVE`), pendente, suspenso, revogado; o Worker sem associação ativa fica "sem máquina vinculada" |
| Comunicação | último contato **autenticado**, `agents.last_heartbeat_at`, gravado pelo gateway no heartbeat aceito com o relógio do servidor | online (`CONNECTED`), offline (`DISCONNECTED` com último contato), ainda sem comunicação (`DISCONNECTED` com `lastHeartbeatAt` nulo) |
| Administração do Worker | `workers.status` | ativo, suspenso, aposentado |

## Regra de comunicação (API)
- **`CONNECTED`** só quando as três condições valem:
  - o Agent está `ACTIVE`;
  - está **vinculado** (associação ativa ao Worker);
  - o último contato autenticado foi há no máximo **180 s**.
- **Revogado, suspenso ou sem associação:** sempre `DISCONNECTED`. Um sinal antigo, ou um gravado depois da revogação, não reativa o vínculo nem a comunicação. Vale em `listAgents`, `listWorkers`, nos pedidos de execução e nas execuções do Agent.
- **Antes do primeiro contato:** nunca `CONNECTED`.
- **A expiração da comunicação só muda a leitura.** Não revoga, não libera o Worker para outra máquina, não retira workspaces, não encerra execuções e não mexe em autorizações. É o mesmo princípio do contrato Platform-Agent (§5).
- **Reconexão:** é o mesmo Agent, com a mesma identidade e o mesmo histórico.

## Cadência medida e documentada
- **Heartbeat do Agent:** a cada **60 s** (`PlatformService.HEARTBEAT`, Agent `cee016a`). A prévia do desvínculo também consulta a Platform.
- **Tolerância da Platform:** **180 s**, ou seja, duas batidas perdidas (`CONNECTED_SECONDS`, nas três leituras). O Agent usa o mesmo critério (`CONTACT_FRESH`).
- **Web:** relê a lista a cada **30 s** enquanto a página está visível, e não lê com a aba oculta.
- **Prazo máximo para a tela mostrar offline:** cerca de **210 s** depois do último contato aceito (180 s de tolerância mais um ciclo de 30 s da Web), contados pelo relógio do servidor. O aceite em laboratório registra o tempo medido.

## Desvínculo no Agent (T06, sem mudança nesta missão)
- **Online ou offline,** o desvínculo é local. A Platform continua com o vínculo (`ACTIVE`) até uma pessoa revogar em Workers, e a comunicação expira para offline.
- **A Web diz isso:** "vinculada, sem comunicação". Não alega desvínculo nem revogação que o servidor não confirmou.
- **Depois da revogação pela pessoa,** a Web mostra "Revogado", e o Worker fica sem máquina vinculada.
- **Novo pareamento** não transfere eventos pendentes de outra organização (provado no T06/R1).

## Testes (`AgentComunicacaoIT`)
| Requisito | Teste |
|---|---|
| Nunca online antes do primeiro contato; vínculo e Worker separados | `semNenhumContatoNuncaAparecConectado` |
| Janela pelo relógio do servidor (175 s online, 185 s offline); offline não é desvinculado; último contato conhecido; expiração não muda o servidor; reconexão preserva a identidade | `comunicacaoExpiraEm180sSemMudarNadaEVoltaComONovoContato` |
| Revogado com sinal recente: `REVOKED` e `DISCONNECTED` na hora; um heartbeat gravado depois da revogação não ressuscita o vínculo | `revogadoNuncaApareceConectadoNemComSinalAtrasado`. **Falha no código da `dev` `df92772`**, que mostrava `CONNECTED`, e passa com a correção |
| Leituras anteriores sem mudança | `AgentAdminIT`, `ExternalPythonRunRequestIT` |

## Contrato
Não há campo nem valor novo. As descrições de `listAgents` e de `WorkerList.connectivity` passam a trazer a regra e a cadência. A Web continua derivando "ainda sem comunicação" de `lastHeartbeatAt` nulo.

## Limites
- **Rota de autodesvínculo:** a máquina continua sem uma, por decisão de 11/11. "Desvinculado no Agent" não chega à Platform: o servidor vê só a comunicação expirar.
- **Sem migration:** a coluna e o sinal existentes são reaproveitados.
