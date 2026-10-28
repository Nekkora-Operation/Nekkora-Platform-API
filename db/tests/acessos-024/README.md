# Suíte de banco da migration 024 (decisões de acesso, regra de renovação e reset de conta vinculada)

Valida a `db/migrations/024_acessos_decisoes_reset_vinculado.sql`, que é **candidata**. Contrato: `docs/relatorios/2026-10-27-acessos-convites-papeis-concessoes-contrato.md`, §§ 4.5, 7, 9 e 10.

Roda em dois clusters PostgreSQL 16 descartáveis e isolados:
- containers próprios, com `--network none`, sem porta publicada e sem volume nomeado;
- nunca toca a stack principal nem os ambientes de homologação.

```sh
db/tests/acessos-024/run.sh
TESTE_PROJETO=nekkora-a024b db/tests/acessos-024/run.sh
```

**Windows (Git Bash):** sem exclusão, o Git Bash converte os caminhos de container passados em `-e` e em `docker exec`. Rode com:

```sh
MSYS2_ARG_CONV_EXCL="NEKKORA_;/tmp/;/nekkora;/docker-entrypoint-initdb.d;/flyway" db/tests/acessos-024/run.sh
```

## Modelo de aplicação

`db/bootstrap/aplicar_como_migrador.sh`: SUPERUSER em 011–013, 016–020, 022, 023 e **024**; BYPASSRLS só na 014; retirada conferida em 0|0|0. A §19 da 016 roda de novo depois de todas as versões, inclusive da 024, **sem adaptação**. A 024 exige a janela porque transfere funções para `nekkora_identity_owner` e substitui funções do dono da 016.

## Casos

| Caso | O que confere |
|---|---|
| Forma das restrições | a instalação limpa (A) aplica a 024 sobre as restrições `NOT BETWEEN … OR …` da 023 na forma **instalada**. A base populada (B) as recebe na forma **restaurada** de um dump (OR achatado), que foi o achado da R015 na homologação. A 024 aceita as duas e deixa o mesmo catálogo nos dois casos |
| A | instalação limpa 001–024; invariantes de 012 a 024 depois da retirada. Adaptações declaradas: as da suíte da 023 (014, 017, 019 e 020) e, nas listas de funções da Platform da 017, 019 e 020, a função de fechamento por perda de autoridade (`identity_invitations_close_unauthorized`); marcador `versao=024` |
| B0 | a 024 sobre a base em 022, sem a 023: recusada pela pré-condição, sem efeito |
| B1 | a 024 sem a janela: recusada por privilégio, sem efeito em dados e catálogo |
| B1b | a 024 com a janela, mas falhando **depois** do fechamento dos convites (gatilho homônimo de um que ela cria, criado por fora). Ela avisa o fechamento de 2 convites e depois falha. Os 2 continuam abertos e dados e catálogo não mudam, o que prova que o fechamento está na mesma transação. Retirada em 0\|0\|0 |
| B2 | com a janela: dados de `public` e `ops` idênticos em todas as colunas anteriores, **exceto** `identity_invitations`. Nela mudam só `revoked_at` e `revoke_reason`, e só dos convites abertos de emissor que já estava sem autoridade (2 na massa): revogados com o motivo 4 e sem revogador, que é a única mudança de dados da 024. As linhas antigas continuam válidas, com as colunas novas nulas. O diff de catálogo é exatamente `esperado/B24_diff_esperado.txt` (41 linhas: restrições trocadas, colunas e chaves estrangeiras novas, funções, ACL e gatilhos). O catálogo fica igual ao da instalação limpa e as invariantes passam |
| B3 | reaplicar a 024 é recusado pela pré-condição, sem efeito em dados e catálogo |
| C1 | D-CONV-4 pela Platform: o convite cujo emissor perde o papel é revogado no ato (motivo 4, sem revogador). Devolver o papel não reabre nem esse convite nem os fechados na aplicação. O motivo 4 com o emissor ainda autorizado, ou com revogador, é recusado. O revogado continua congelado. Outro papel não executa o fechamento |
| C2 | D-GRANT-1: concessão sem solicitante, sem a próxima revisão pedida, já ativa ou com solicitante sem a permissão é recusada. A aprovação INITIAL pelo solicitante, ou com outro valor, é recusada. A concessão anterior à 024 (sem solicitante) não é aprovada. A aprovação por outra pessoa, com os valores pedidos, passa |
| C3 | D-GRANT-1a: os valores pedidos de uma pendente são imutáveis. São recusadas, sem pedido: extensão da revisão, extensão ou remoção do prazo, antecipação do início, renovação e reativação, inclusive de concessão anterior à 024. A redução passa. São recusados também: pedido que muda a validade no mesmo ato, pedido de quem não tem a permissão, RENEWAL pelo solicitante ou com outro valor, e aplicação de valor diferente do aprovado. A RENEWAL de outra pessoa, com os valores pedidos, passa e consome o pedido (que não serve duas vezes). A suspensão descarta o pedido pendente |
| C4 | reset vinculado: `ops.alvo_inelegivel` devolve `CONTA_VINCULADA` para a conta vinculada; o controle, com conta não vinculada, devolve outro motivo (`NAO_ORG_ADMIN`). `identity_user_is_linked` só pelo dono da 016 (`true`/`false`). Platform, Portal e provisionamento recebem 42501. O dono da 016 continua sem SELECT em `identity_users` |
| C5 | contraprovas das invariantes da 024, cada uma em `BEGIN … ROLLBACK`. O controle passa. São recusados: EXECUTE da consulta de ligação para a Platform, EXECUTE do fechamento para o Portal, função de identidade SECURITY INVOKER, SELECT do dono da 016 em `identity_users`, dono trocado numa função da 016, gatilho da solicitação desligado e coluna do pedido de renovação removida |

A execução do reset (abertura, execução depois da aprovação, controle positivo e as duas corridas com a vinculação **real** da 017) e as rotas são cobertas pelos testes Java:
- `ResetContaVinculadaIT` (`nekkora-ops`);
- `GrantLifecycleIT` e `AccessInvitationsIT` (`nekkora-platform`).

As saídas ficam em `saidas/` (não versionadas).
