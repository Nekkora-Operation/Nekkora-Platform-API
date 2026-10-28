# Suíte de banco da migration 023 (convites, autoridade sobre papéis e concessões, auditoria de acesso)

Valida a `db/migrations/023_acessos_convites.sql`, que é **candidata**. Contrato: `docs/relatorios/2026-10-27-acessos-convites-papeis-concessoes-contrato.md`.

Roda em dois clusters PostgreSQL 16 descartáveis e isolados:
- containers próprios, com `--network none`, sem porta publicada e sem volume nomeado;
- nunca toca a stack principal nem os ambientes de homologação.

```sh
db/tests/acessos-023/run.sh
TESTE_PROJETO=nekkora-a023b db/tests/acessos-023/run.sh
```

**Windows (Git Bash):** sem exclusão, o Git Bash converte os caminhos de container passados em `-e` e em `docker exec`. Rode com:

```sh
MSYS2_ARG_CONV_EXCL="NEKKORA_;/tmp/;/nekkora;/docker-entrypoint-initdb.d;/flyway" db/tests/acessos-023/run.sh
```

## Modelo de aplicação

`db/bootstrap/aplicar_como_migrador.sh`: SUPERUSER em 011–013, 016–020, 022 e **023**; BYPASSRLS só na 014; retirada conferida em 0|0|0. A §19 da 016 roda de novo depois de todas as versões, inclusive da 023, **sem adaptação**. A 023 exige a janela porque transfere as funções do aceite para `nekkora_identity_owner`.

## Casos

| Caso | O que confere |
|---|---|
| A | instalação limpa 001–023; invariantes de 012 a 023 depois da retirada. Adaptações declaradas: 014 (políticas próprias dos donos), 017 (44 tabelas de tenant, com `access_events`; o aceite na lista de funções da Platform), 019 e 020 (o aceite na lista); marcador `versao=023` |
| B0 | base populada 001–022 com **dois convites abertos para o mesmo e-mail**: a 023 é recusada pela pré-condição, sem efeito |
| B1 | a 023 sem a janela é recusada por privilégio, sem efeito |
| B2 | com a janela: dados de `public` e `ops` idênticos (todas as colunas anteriores à 023); os convites antigos (aberto, revogado e aceito, no modelo da 002) continuam válidos, com as colunas novas nulas; diff de catálogo exatamente `esperado/B23_diff_esperado.txt` (188 linhas: auditoria, colunas, restrições e índices dos convites, as duas restrições trocadas, funções, gatilhos, políticas e concessões); catálogo igual ao da instalação limpa; invariantes |
| B3 | reaplicar a 023 é recusado pela pré-condição |
| C1 | convites pela Platform: emissor sem `org.manage`, emissor de outra conta, prazo acima de 7 dias, convite externo e papel de workspace sem workspace são recusados; emissão autorizada passa; duplicado aberto recusado pelo índice; aceite fora da função, revogação sem autoridade e mudança de origem recusados; revogado congelado; sem DELETE nem TRUNCATE |
| C2 | papéis pela Platform: autoatribuição, autor sem autoridade, WORKSPACE_ADMIN fora do próprio workspace ou concedendo papel de administração e revogação sem autoridade são recusados (42501); WORKSPACE_ADMIN no próprio workspace e ORG_ADMIN passam; o superusuário não é barrado; o último ORG_ADMIN continua protegido pela 003 (P0001) |
| C3 | concessões: aprovação, revisão, aprovador, revogação e **reativação** (SUSPENSA → ATIVA, com o aprovador gravado) por quem não tem `external_access.manage` são recusados (a reativação com o ORG_ADMIN como aprovador passa); ATIVA sem aprovação continua recusada pela restrição; **PENDENTE revogada sem aprovação passa** (correção da 003) |
| C4 | auditoria: a Platform grava e lê só na organização do contexto; UPDATE, DELETE e TRUNCATE são recusados, inclusive para o superusuário (gatilho) |
| C5 | `identity_accept_invitation`: só a Platform executa; sessão inválida volta `INVALIDO`, sem efeito |
| C6 | contraprovas das invariantes da 023, cada uma em `BEGIN … ROLLBACK`: o controle passa; são recusados UPDATE na auditoria, EXECUTE do aceite para o Portal, gatilho de autoridade desligado, função de autoridade SECURITY DEFINER, política extra na auditoria e escrita da Platform em `identity_users` |

O aceite completo (os casos da destinatária, as recusas, a readmissão e a concorrência) e as rotas são cobertos pelos testes Java de `nekkora-platform`: `AccessInvitationsIT`, `RoleAdministrationIT`, `GrantLifecycleIT` e `AccessContractTest`.

As saídas ficam em `saidas/` (não versionadas).
