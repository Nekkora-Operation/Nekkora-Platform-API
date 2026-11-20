# PLATFORM-FLUXO-002 — Ativação de automações e temas: decisões e contrato

Partida: `dev` integrada (API `4f518bcd2e887f64f7ffc46b46791017d4379c4b`, Web `ea6e23c7e1bcdf116f194db42e22a45cc6e3b623`).

## Decisões da titular (20/11/2026)

| Tema | Decisão |
|---|---|
| Quem ativa | Só quem administra a organização (`org.manage`), com confirmação reforçada. Os demais papéis veem o estado e solicitam execuções das automações já ativas. |
| O que a ativação cobre | Ambiente de trabalho → automação → Workers **escolhidos** (nenhum marcado automaticamente) → conferir licença, versão publicada atual e Workers → "Ativar execução e monitoramento". Uma licença cobre execução e monitoramento da automação, qualquer que seja o número de Workers; o Worker não consome licença. |
| Agent anterior | As autorizações são emitidas pela ativação, até o fim da cobertura vigente da licença e dentro dos limites técnicos de validade (366 dias). |
| Agent Client | Mantém o aceite da versão e da máquina na confirmação da execução, aproveitando autorizações válidas já existentes, sem confirmação redundante. |
| Gerenciar ativação | Acrescentar Worker e aceitar versão nova são ações curtas, sem consumir outra licença. Versão nova exige aceite para executar aquele pacote; o monitoramento já autorizado continua válido e não autoriza a versão nova. |
| Pendências | Sem Worker ou sem versão publicada: a licença é vinculada e a ativação fica "Configuração pendente", sem afirmar que está pronta. Falha de uma autorização: mostrar exatamente o que ficou pendente e concluir depois sem consumir outra licença. |
| Renovação | "Licença renovada até [data]" com "Renovar autorizações": as mesmas máquinas e a versão já aceita; autorizações novas, histórico preservado; sem nova licença nem pareamento; revalida Workers, ambiente e permissões; nunca reativa revogadas nem inclui máquinas novas; vale a partir da emissão, dentro da cobertura; repetir não duplica; MFA só quando a política existente exige. |
| Tema | Preferência guardada neste navegador, entre sessões. Padrão: Nekkora Light. |

## Como ficou no servidor

- **Rotas novas** (`AutomationActivationController`, contrato `nekkora-api-v1.yaml`, tag "Ativacao de automacoes"):
  `GET /automation-activations`, `GET /automation-activations/options?workspaceId=`, `GET /automation-activations/{automationId}`,
  `POST /automation-activations`, `POST /automation-activations/{automationId}/version`, `POST /automation-activations/{automationId}/renewal`.
- **Composição, não regra nova**: o passo único reaproveita o vínculo de licença (062) e as autorizações locais (025/030/033/064).
  O banco reconfere cada ato. A licença é um ato; cada autorização é outro: o que falha volta em `results` com o motivo, sem
  desfazer o resto. Repetir reaproveita a vigente do escopo (`already`) e não consome licença.
- **Ordem de conferência**: os Workers são conferidos antes de consumir a licença; sem licença disponível, nada é gravado.
- **Migration 069** (sem elevação): `revocation_reason` (1 = por pessoa, 2 = substituída) e `renewal_of_authorization_id`.
  A renovação revoga a anterior ainda vigente como substituída e emite a nova na mesma transação (a 033 só admite uma
  vigente por escopo). Revogada sem ser por substituição nunca é renovada (o banco recusa). Execuções iniciadas sob a
  anterior continuam aceitas (064, D8: a autorização tem de valer no início).
- **Modelo anterior** (organização que ainda não exige licença): a ativação funciona sem exigir licença (vincula uma
  disponível se houver), e as autorizações usam a validade informada, como hoje. Renovar não se aplica.

## Compatibilidade

- **Agent anterior**: nenhuma mudança. Continua baixando `/agent/api/v1/authorizations` no mesmo formato; as autorizações
  da ativação são as mesmas linhas de sempre (`source_kind` 1/2 por versão, 3 por automação). As colunas novas não são lidas
  pelo canal das máquinas. Após "Renovar autorizações", a máquina conectada recebe a nova no próximo download (até ~5 min);
  uma desconectada continua com a anterior em cache até a validade dela.
- **Agent Client**: nenhuma mudança. A execução remota continua aceitando a versão na confirmação (S2) e reaproveitando a
  autorização válida existente.
- **Rotas antigas de autorização local** (`createLocalRunAuthorization`, `revokeLocalRunAuthorization`, lista): continuam
  na API, por compatibilidade. A Web deixa de oferecer a criação como etapa separada.
- **Diferença a conhecer**: uma organização que hoje usa as autorizações manuais com validade escolhida passa, no modelo
  por licença, a ter validade até o fim da cobertura da licença (limite técnico de 366 dias).
