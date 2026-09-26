# Serviço de credenciais — configuração e pendências (documento ativo)

**Data de abertura:** 2026-09-25
**Status:** documento ativo, sem implementação. Reúne a configuração prevista para o serviço de credenciais e as pendências. **Não** altera decisões já aprovadas nem reescreve relatórios históricos.
**Fontes:**
- `Radiant-Relatorio-Decisoes-Aprovadas-2026-09-24.md`, §13 (histórico, anterior à renomeação; mantido sem alteração);
- `db/migrations/008_parametros_secrets.sql`;
- `2026-09-25-seguranca-operacional-postgres.md`.

## 1. Estado atual no banco (008, sob a 012)

- `workspace_secrets` guarda **metadados e referência ao provedor** (`provider_key`, `provider_reference`), nunca o valor do secret.
- Auditoria de cadastro e alteração em `workspace_configuration_events`, sem valores.
- Sob a 012: a tabela está em RLS por organização. O `nekkora_platform` tem `SELECT`, `INSERT` e `UPDATE`, e o `nekkora_scheduler_owner` tem `SELECT` e `UPDATE` (checagem de revogação na criação de execução).

## 2. Direções já aprovadas (referência, sem mudança)

As direções estão na §13 do relatório de 24/09 e não são repetidas aqui como decisões novas:
- serviço com execução, identidade e permissões **próprias**, separadas da Platform;
- o PostgreSQL não guarda segunda cópia dos valores;
- o Agent obtém segredos via API, só os necessários à execução atribuída;
- listar metadados, alterar e obter valor são permissões distintas;
- direção de provedores: Parameter Store (parâmetros), SecureString (configurações sensíveis), Secrets Manager (credenciais e rotação).

## 3. Pendências

| # | Pendência | Origem | Observação |
|---|---|---|---|
| P1 | **Prefixo do caminho no provedor (SSM): `nekkora` no lugar de `radiant`** | A §13 de 24/09 sugere `/radiant/prod/organizations/{organization_id}/workspaces/{workspace_id}/NOME_VARIAVEL`, anterior à renomeação | O relatório de 24/09 é histórico e **não** é alterado. Troca registrada: prefixo `nekkora` no lugar de `radiant`. **Os demais segmentos do caminho ficam a definir na implementação.** Recursos já criados no provedor com o prefixo antigo, se existirem, precisarão de plano de migração próprio |
| P2 | Escolha final do provedor por tipo de dado | §13 de 24/09 ("permanece pendente") | — |
| P3 | Protocolo de chave temporária (escopo por Agent, execução e segredos, com validade curta), se adotada | §13 de 24/09 ("protocolo ainda pendente") | — |
| P4 | Consolidação do namespace de parâmetros e `configuration_sets` | §13 de 24/09 ("ainda precisa ser refinada") | — |
| P5 | Identidade e credenciais próprias do serviço de credenciais | Direção de identidade própria (§13) + C1 do relatório operacional | **Ainda não foi decidido se o serviço terá acesso ao banco.** Se precisar, isso exige papel próprio, migration e revisão da matriz aprovada; hoje nenhum papel da 012 o atende |

## 4. Fora de escopo deste documento

Implementação do serviço, escolha de SDK, rotas e o contrato com o Agent. Este documento só registra configuração prevista e pendências.
