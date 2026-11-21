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

## Máquinas na interface (pedido da titular, 20/11/2026)

- **Máquina** (interface) = **Worker** (contrato): o nome amigável que a pessoa cadastra; é o alvo da ativação.
- **Computador** = onde o Agent está instalado; vinculado a uma máquina por vez, pelo código de vinculação.
- **Nome da máquina** (decisão da titular, 20/11, etapa de acompanhamento): o valor informado pela pessoa no pareamento
  (`agentName`) aparece na interface como "Nome da máquina" — no campo do pareamento, na coluna de Máquinas, nos detalhes
  das execuções e como complemento do nome amigável ("Estação 1 (nome da máquina: NOTE-01)"). Nunca é apresentado como
  nome detectado: a Platform **não coleta** o nome do computador. A coleta automática (pelo Agent e pelo Agent Client,
  com a decisão de privacidade) fica para outra etapa.
- **Estado** (`machineState`, calculado pelo servidor): REGISTERED (cadastrada), AWAITING_CONNECTION (aguardando conexão:
  código válido ou computador ainda sem contato), CONNECTED (conectada: contato nos últimos 180 s), NO_CONTACT (sem
  contato, com o último contato), LINK_SUSPENDED, INACTIVE.
- Cadastrar ou conectar uma máquina não consome licença; a licença continua vinculada à automação.


## Clientes e acessos (decisões da titular, 21/11/2026; migration 070)

**D-EXT (fechada).** O cliente entra com a **identidade Nekkora** (o mesmo e-mail verificado do convite, com o MFA previsto). Quem
ainda não tem identidade faz o cadastro normal; não existe senha implícita. O aceite cria ou reutiliza a conta de cliente
(`users.account_type = 2`) e o vínculo de cliente (`client_memberships`) **só naquela organização**, ligados à identidade
(`identity_users`, canal `convite`). A identidade é global; os acessos são de cada organização. Recusas no aceite: identidade que
já é membro interno da organização (`MEMBRO_INTERNO`, o convite não reduziria privilégios), conta da organização com o mesmo
e-mail e não ligada (as contas de cliente antigas, `CONTA_EXISTENTE`; nunca ligadas por coincidência de e-mail), outro cadastro
de cliente (`CLIENTE_DIFERENTE`), a mesma automação já compartilhada (`CONCESSAO_EXISTENTE`) e escopo que deixou de existir
(`ESCOPO_INDISPONIVEL`). Ser membro interno de outra organização não impede. Retirar ou suspender o vínculo afeta só aquela
organização.

**D-GRANT-1 (exceção).** O convite de Cliente restrito ao acompanhamento de **uma automação** é aprovado por quem convida:
administradora da organização (`org.manage` e `external_access.manage`), com a confirmação reforçada (step-up). A confirmação
mostra cliente, automação, ambiente de trabalho, máquinas e permissões de registros e resultados. O acesso só começa no aceite,
que reconfere identidade, convite, autoridade de quem convidou e escopo; a concessão nasce ativa com `basis = 2`
(CONVITE_DE_CLIENTE) **somente pela função de aceite** (o gatilho recusa a Platform e qualquer rota direta). Reenviar não amplia
o escopo. Qualquer outra concessão — inclusive compartilhar outra automação depois, ou ligar registros/resultados de uma
existente — é um pedido PENDENTE aprovado por **outra pessoa** (D-GRANT-1). Permissões de uma concessão só diminuem. O uso da
exceção fica em `access_events.grant_exception` (convite emitido, reenviado e concessão aplicada no aceite). Concessões antigas
pendentes não são aprovadas.

**Escopo por automação.** A concessão (`client_automation_grants`) aponta uma automação de qualquer ambiente de trabalho ativo da
organização, sem exigir que o ambiente pertença ao cliente, com a restrição opcional a uma máquina (nula = todas as máquinas
desta automação). Não dá acesso ao ambiente, às outras automações nem às outras atividades da máquina. As concessões por ambiente
inteiro (`client_workspace_grants`) continuam com a regra de pertencimento ao cliente e com as decisões de antes.

**Área do cliente pelo Portal (8.2 preservado).** As funções `portal_client_*` (dono `nekkora_portal_owner`, SECURITY DEFINER)
autenticam pela sessão de identidade (`identity_session_lookup`) e, em cada chamada, reconferem conta, vínculo, cliente,
organização e as concessões vigentes; listas, contadores, detalhes, registros e downloads usam o mesmo escopo. Nenhuma sessão de
organização é emitida para a conta de cliente: as rotas da Platform continuam fechadas para ela. A execução remota vale pela
máquina da última tentativa; os registros e resultados aparecem só das tentativas nas máquinas concedidas.

**Impacto da 070 nos vínculos existentes.** Nenhuma linha existente muda: contas de cliente antigas, vínculos, concessões por
ambiente (inclusive as pendentes) e convites internos ficam como estão; as funções do Portal antigo (013) continuam para as contas
antigas. A 070 cria funções de outros donos e exige a janela de SUPERUSER do migrador (incluída em `aplicar_como_migrador.sh`,
como a 067); não está em `ops/homologacao/implantacao/elevacao.txt` — homologação e produção dependem de autorização própria.
Implantação fora do laboratório: o Portal precisa ler a pasta de resultados (`NEKKORA_RESULTADOS_DIR`, somente leitura) para o
download do cliente, e o proxy precisa encaminhar `/portal/api/v1/` ao Portal (já presente nas configurações de e2e e de
homologação).

**Limitações declaradas.** Não há prazo nem revisão periódica nas concessões por automação (a suspensão é do vínculo); o download
do cliente fica no log do Portal, não em `automation_admin_events`; a permissão própria de auditoria vale como recorte
(`org.manage` vê tudo; só `external_access.manage` vê os atos de cliente), mas no catálogo atual só ORG_ADMIN tem
`external_access.manage`.
