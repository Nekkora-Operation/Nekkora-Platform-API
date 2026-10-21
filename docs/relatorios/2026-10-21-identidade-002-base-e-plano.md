# Missão PLATFORM-API-IDENTIDADE-002: base, fechamento da 018 e plano

**Data:** 2026-10-21. **Tipo:** registro documental (PR 1 da missão). Nenhum código, migration ou teste muda neste PR.

## 1. Base conferida

| Item | Situação |
|---|---|
| `main` | `8e7ef18`, merge do PR #8 |
| PR #7 (contas vinculadas) | incorporado: merge `7548957`, head aprovado `77cd2db`, ancestral de `main` |
| PR #8 (018) | **mesclado** em 2026-10-21T23:20:51Z. Head mesclado `1f99c65`, o aprovado. A árvore de `8e7ef18` é idêntica à de `1f99c65` (`git diff` vazio) |
| CI do head aprovado | run 39647775143: core 21, ops 164, platform 311, portal 12; 508 testes, 0 falhas, 0 erros, 0 ignorados |

## 2. 018: o que fica preservado

O trabalho da 018 não é refeito. Ficam valendo:
- **Conferência integral dos quatro corpos antes da substituição:** SHA-256 do `prosrc` fixado na §1 da migration; caso B4 da suíte `db/tests/relogio-018`.
- **Privilégios mínimos:** só `SELECT (created_at)` em `user_mfa_factors` e `user_mfa_recovery_codes` para `nekkora_ops_mfa_owner`.
- **Elevação:** janela de SUPERUSER só na aplicação da 018, retirada conferida em `0|0|0`.
- **Instantes terminais:** ajustados com `greatest(instante, created_at)`. O valor gravado é o limite inferior aceito pela CHECK, não o instante exato do ato.
- **Esperas obrigatórias da recuperação operacional (016):** intactas.
- **Incidente original:** a causa do 23514 na corrida MFA × reset continua **inconclusiva** (`2026-10-20-fechamento-23514-mfa-reset.md`).

**Escritas de conclusão** (confirmação, consumo, uso) continuam sem ajuste. Nas jornadas novas, cada uma é avaliada no PR que a usa, conforme a §4. O critério é onde o timestamp participa da autorização. Não há ajuste indiscriminado, e nenhuma marca que abre janela (`mfa_step_up_at`, `recovery_used_at`, `mfa_verified_at`) é tocada.

### Ação pendente de ambiente (com a responsável)

`ops/homologacao/implantacao/elevacao.txt` autoriza hoje só 011–013. A 016 entra pela linha de P3, com decisão escrita. A homologação com 016–018 precisa das linhas abaixo, cada uma com a decisão da responsável e no formato de `lib/lista_elevacao.sh`. O sha256 é dos bytes LF versionados em `8e7ef18`:

```
016 f37d984128e8241474319a39c00fa54baed90519169663757c7f799e61b63732
017 90b8ddbcceac1319e9d4f71932e4af287ab1cdef67feacb6d71cfdc77db1c160
018 1bcfbacbe63d930e75d126c2d92ece5a4c85422ecb68c2e475d0c6e3f67c3418
```

- **Impacto:** sem a linha, o runner recusa elevar a versão e a implantação para antes dela, sem efeito parcial.
- **Medição:** o motivo da elevação está medido nas suítes. Para 016, 017 e 018, `CREATE OR REPLACE` e configuração de funções de donos sem membership.
- Este PR **não** altera o arquivo.

## 3. Organização da entrega

| PR | Objetivo | Depende de |
|---|---|---|
| 1 | Base, fechamento da 018 e plano (este) | — |
| 2 | Recuperação e substituição de MFA global: `/api/v1/auth/mfa/enroll/start` e `/confirm` em dois modos explícitos, liberação da sessão restrita, `restricted` nas respostas, aviso de troca de fator | 1 |
| 3 | Troca de senha da identidade e regeneração independente de códigos | 2 (modo sessão e avisos) |
| 4 | Recuperação de senha da identidade por e-mail (migration nova: uso do `purpose = 2` reservado) | 3 |
| 5 | Transporte real (Graph, `no-reply@nekkora.com`), fila persistente de envio e homologação integrada | 2–4 (tipos de mensagem) |

**Verificações de organizações:** listagem, criação, troca de sessão, vinculação e step-up por organização entram nos PRs que mudam esses contratos. O PR 2 muda o tratamento da sessão restrita, então é nele que essa verificação entra. Cada PR registra comportamento, evidências, limitações e decisões pendentes.

**Contas antigas por organização:** mantêm os fluxos atuais. Não recebem substituição de fator nem regeneração independente nesta missão.

## 4. Timestamps de conclusão nas jornadas novas: critério

Para cada escrita de confirmação, consumo ou uso que uma jornada nova passar a exercitar, o PR registra:
1. a CHECK envolvida e se o recuo do relógio a viola;
2. se o instante participa de alguma autorização posterior (janela, prazo, ordem de eventos);
3. a decisão:
   - manter a falha fechada (o ato é refeito);
   - ajustar, só se o instante não autoriza nada e a falha bloqueia a jornada sem saída.

A reprodução vem antes da correção, como na 018. Marcas que abrem janela nunca são ajustadas.

## 5. Capacidade inicial de execução: proposta para decisão

**Situação hoje.** `identity_create_organization` (017) provisiona a organização criada por cadastro com os mesmos limites do bootstrap da organização interna:

| Recurso | Limite |
|---|---|
| 1 `WORKERS_REGISTERED` | FINITE 0 |
| 2 `CONCURRENT_EXECUTIONS` | FINITE 0 |
| 3 `PENDING_EXECUTIONS` | FINITE 0 |
| 4 `LOG_BYTES_DAILY` | UNLIMITED (sem limiar de alerta) |
| 5 `EVENT_BYTES_DAILY` | UNLIMITED (sem limiar de alerta) |

Pela 010, FINITE 0 é "indisponível": a organização nasce sem poder registrar Worker nem criar execução. Nenhuma rota ou comando da aplicação muda esses limites. Só o papel `nekkora_provisioning`, por `provision_resource_limit`, pode fazê-lo.

O zero foi registrado como **provisório** desde a 017. A recomendação já registrada pela responsável é:
- cadastro imediato;
- execução dependente de ativação explícita;
- essa condição visível na web.

**Proposta concreta** (requer decisão de produto; nada disto é implementado nesta missão):

1. **Estado inicial:** manter recursos 1–3 em FINITE 0, agora como **regra declarada** ("aguardando ativação"), não mais como resíduo de teste.
   - **Vantagem:** nenhuma organização criada por cadastro consome capacidade antes de uma decisão humana.
   - **Custo:** a pessoa não consegue executar nada sozinha.
2. **Ativação explícita** pela operação, com autoria e motivo. Usa `provision_resource_limit` (já audita revisões em `organization_resource_limit_revisions`, com `provisioned_by_user_id`) por um comando operacional futuro, no molde dos atos da 016.
   - Valores iniciais propostos para a ativação padrão:
     - 1 Worker registrado;
     - 1 execução simultânea;
     - 10 pendentes;
     - limiares de alerta de 1 GiB/dia para logs e 256 MiB/dia para eventos (4–5 são alerta, nunca bloqueio, pela 010).
   - Esses números são **ponto de partida para discussão**, não medidos. A capacidade sob carga continua gate aberto (`2026-09-26-decisoes-8-6-8-7-8-10.md` §7).
3. **Visibilidade:** acrescentar a `GET /api/v1/me/organizations`, por organização, um campo derivado `executionCapacity: "PENDENTE_ATIVACAO" | "ATIVA"`. A forma é calculada no banco, sem expor valores internos de limite.
   - Exige função nova de leitura (migration nova) e mudança de contrato.
   - Fica para depois da decisão.
4. **Alternativa**, se a decisão for autosserviço: valores pequenos não nulos já na criação (por exemplo, 1/1/5). Exige migration nova substituindo `identity_create_organization` com a conferência integral do corpo, como a 018.
   - **Risco:** capacidade consumida por cadastros não verificados além do e-mail.

**Decisão necessária da responsável:** 1+2+3 (recomendado, alinhado à recomendação registrada) ou 4. Até lá, o zero continua provisório. Os testes continuam provisionando limites ilimitados nas fixtures (`ProductFixtures`), sem que isso vire regra.

## 6. Decisões desta missão já tomadas (do enunciado)

- `/api/v1/auth/mfa/enroll/start` e `/confirm` reutilizadas com dois modos explícitos (desafio de login; sessão de identidade), recusando a requisição ambígua e exigindo CSRF no modo sessão.
- Recuperação de senha da identidade por e-mail: em PR próprio. MFA preservado; nenhuma sessão criada.
- Nenhum administrador de organização recebe autoridade sobre o MFA global.
- Contas antigas por organização: sem novos fluxos.

## 7. Observação lateral

`api/e2e/saidas/2026-09-26T192328Z_nekkora-e2e/` tem 21 arquivos versionados desde `d608a96`, contra a regra de não versionar `saidas/`. São cópias de migrations montadas pelo E2E. Não foram tocados; a remoção fica a critério da responsável.
