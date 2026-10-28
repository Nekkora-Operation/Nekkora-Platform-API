# Missão de consolidação da API, gestão de acessos e recuperação operacional (2026-10-27)

**Situação:** implementado e testado. **Nada mesclado**, nada implantado e nenhuma liberação pública; o merge fica com a responsável. Nenhum envio real de e-mail. Nenhuma alteração na AWS, no Microsoft 365, no DNS ou em ambiente preservado.

**Revisão de 2026-10-27 (noite), depois da revisão da responsável:**
- CI dos HEADs finais conferido.
- Decisões de acesso registradas. Duas mudaram o código, na migration 024: o convite de emissor que perdeu a autoridade não ressuscita, e a aprovação de concessão externa exige uma segunda pessoa.
- Recusa explícita do reset operacional de conta vinculada, também na 024.
- B11 com prova de privilégios. A comparação achou e corrigiu um defeito real do procedimento.
- Base exata do #25 informada.
- Este relatório, o contrato e os documentos anteriores (inventário de 23/10 e e-mail) foram atualizados.

**Segunda revisão da noite (2026-10-27), depois da resposta da responsável:**
- CI dos HEADs `c4caeeb` (#24) e `9eed1fe` (#25) conferido, sem reaproveitar resultado de commit anterior (§5).
- Regra de renovação e KEEP aceita (D-GRANT-1a) e implementada na 024, com a aprovação vinculada aos valores pedidos (`8527954`, contrato §7).
- Suíte de banco da 024 (`db/tests/acessos-024`): instalação, atualização populada, privilégios, invariantes, contraprovas e reaplicação recusada.
- Corrida vinculação × execução do reset, nos dois ordenamentos, com a vinculação real da 017 (contrato §10).
- E2E completo com 001–024, com a 024 final.
- Proposta das linhas de elevação da 023 e da 024: hashes finais, ambiente e pré-voos (§8). Nenhuma linha foi acrescentada a `elevacao.txt`.
- A7 confirmada pela responsável: a restauração do B11 em cluster descartável está coberta por esta missão (não vale para produção nem para ambiente preservado).

Legenda: **implementado** (código commitado e enviado); **testado** (local e/ou CI, com o commit indicado); **homologado** (em ambiente de homologação descartável); **mesclado** e **implantado** (nada nesta missão).

## 1. Ordem de merge e dependências

**Ordem:** #22 → #20 → #21 → (#25 e #23, em qualquer ordem) → #24. O #25 parte **exatamente** de `75640b1`, o HEAD do #21; ele não contém nem precisa do #23 e do #24. A migration 024 fica no #24, porque a numeração segue a 023; o #25 não tem migration.

| Ordem | PR | Branch | Base (branch @ commit) | Commits próprios | HEAD | CI do HEAD (core + ops + platform + portal) |
|---|---|---|---|---|---|---|
| 1 | #22 teste de data | `fix/ops-teste-prazos-mudanca-horario` | `main` @ `b036dc0` | `e9f56bf` | `e9f56bf` | verde |
| 2 | #20 consolidação | `ops/api-v1-003-homologacao` | `main` @ `b036dc0` | os da consolidação + `0f23355` (merge do #22) | `0f23355` | 21 + 177 + 374 + 12 = **584**, 0 falhas, 0 ignorados |
| 3 | #21 e-mail real | `feat/email-real-homologacao` | `main` @ `b036dc0` | os do e-mail + `75640b1` (merge do #20) | `75640b1` | 21 + 177 + 408 + 12 = **618**, 0/0 |
| 4 | #25 recuperação da 016 | `fix/016-b10-varredura-b11-restauracao` | `feat/email-real-homologacao` @ `75640b1` | `9afd1a1`, `d51a05c`, `a627f0a` (privilégios do B11), `9eed1fe` (fim de linha), commit da revisão do texto do B11 (ver o PR) | ver o PR | `9eed1fe`: 21 + 177 + 411 + 12 = **621**, 0 falhas, 0 ignorados |
| 5 | #23 acessos, parte 1 | `feat/acessos-023-banco` | `feat/email-real-homologacao` @ `75640b1` | `dbc4366` | `dbc4366` | **618**, 0/0 |
| 6 | #24 acessos, parte 2 | `feat/acessos-convites-papeis` | `feat/acessos-023-banco` @ `dbc4366` | `8471e15` (rotas), `0d3f8c5` (E2E e documentos), `c4caeeb` (024: decisões e reset vinculado), `8527954` (regra de renovação e KEEP, corridas, suíte e pré-voos), commit destes documentos | ver o PR | `c4caeeb`: 21 + 180 + 434 + 12 = **647**, 0 falhas, 0 ignorados. Os commits seguintes rodam o CI no PR |

**Atenção à dependência:** a conclusão do B10/B11 está no #25, mas a proteção explícita do reset de conta vinculada (`CONTA_VINCULADA`) está na 024, dentro do #24. Mesclar só o #25 **não** incorpora essa proteção.

Com o #22 mesclado antes, o merge dele no #20 e no #21 vira no-op. Depois de cada merge, a base do PR seguinte passa a ser a `main`. O GitHub retarga quando a branch base é apagada; se não for apagada, é preciso retargetar à mão.

## 2. Frente A: consolidação (#22 → #20 → #21)

- As falhas do CI do #20 (`8dd22fc`) e do #21 (`a25332d`) eram **só** as duas do `Revisao4PrazosEImpedimentosIT` (conferido nos logs dos jobs), e por causa delas o reator **pulava** `nekkora-platform` e `nekkora-portal`.
- O #22 foi trazido ao #20 por merge (`0f23355`), e o #20 ao #21 (`75640b1`), sem force-push. Junto veio o #18, que já está na `main`.
- Revisão dos diffs: nenhum segredo, identificador privado ou `.eml` (o repositório é público); o tenant, que aparece, é público e já está na `main`.
- #20 conferido: 022, `atualizar`, elevação vinculada ao ambiente, pré-voos, retirada da elevação em sucesso e falha, semeaduras, jornada da ativação. Validação estática local OK.
- #21 conferido: certificado confirmado antes de apagar a cópia; chave própria da fila; lista fechada e teto persistente; desligamento conferido pelo Docker; homologação real **não** repetida (nada que a afete mudou desde `a25332d`).

## 3. Frente B: convites, papéis e concessões (#23, #24)

Contrato: `2026-10-27-acessos-convites-papeis-concessoes-contrato.md` (matriz antes/depois, regras, garantias do banco separadas das do serviço, decisões assumidas como proposta).

| Jornada | Implementado | Testado |
|---|---|---|
| Convites: emitir, listar, revogar, reenviar | sim (API e 023) | ITs, suíte da 023, E2E |
| Aceite pela identidade (conta nova, readmissão; recusas sem efeito; sem vinculação por e-mail) | sim (função da 023) | ITs (com concorrência), E2E |
| Papéis: consultar, atribuir, alterar, revogar | sim | ITs (com concorrência), suíte |
| Retirada de vínculo (sessões revogadas, identidade intacta) | sim | ITs, E2E |
| Concessões: criar, aprovar (segunda pessoa, 024), pedir e aprovar renovação (segunda pessoa e valores vinculados, 024), revisar, revogar, **sobre cliente e conta externa já existentes** | sim | ITs, suítes da 023 e da 024 |
| Jornada externa completa: criar cliente, entrada da pessoa externa, convite externo | **não** (D-EXT: direção definida, contrato a fechar) | — |
| Auditoria (`access_events`) e avisos na fila | sim | ITs, suíte, E2E |

**Defeito anterior encontrado:** a revogação de concessão PENDENTE respondia 500 (CHECK da 003); corrigido na 023, com o 500 demonstrado antes.

**Decisões aceitas e o que mudou (024, PR #24):**
- **D-CONV-1, 2 e 3:** prazo de 7 dias conferido no aceite; step-up só para ampliar acesso (reduzir continua exigindo sessão, CSRF, autoridade e auditoria); readmissão sem restaurar papéis. Já era o comportamento.
- **D-CONV-4:** antes, o convite de emissor sem autoridade só ficava inaceitável enquanto durasse a falta. Devolver o papel o fazia valer de novo. Agora o banco revoga o convite no mesmo ato da perda (motivo `EMISSOR_SEM_AUTORIDADE`), e ele não volta.
- **D-GRANT-1:** a concessão grava o solicitante, e a aprovação INITIAL exige outro ORG_ADMIN, no serviço (403) e no banco (42501). Uma organização com um único ORG_ADMIN não aprova concessão externa.
- **D-GRANT-1a (aceita na segunda revisão):** aprovação inicial, renovação que estende, reativação e ampliação com duas pessoas; KEEP com uma pessoa só sem estender nada; redução e revogação com uma pessoa autorizada e auditoria. A aprovação fica vinculada aos valores pedidos: qualquer mudança de validade descarta o pedido pendente. Nenhum ato amplia escopo ou permissões (origem imutável desde a 003). Tabela completa no contrato, §7.
- **Convites já inaceitáveis antes da 024:** a 024 fecha na aplicação, com o motivo 4, os convites abertos cujo emissor já estava sem autoridade. Sem isso, eles voltariam a valer se o emissor recuperasse o papel. É a única mudança de dados da 024 (suíte, B2).
- **Reset operacional de conta vinculada:** recusa declarada `CONTA_VINCULADA`, com as corridas da vinculação nos dois ordenamentos (contrato, §10).

## 4. Frente C: recuperação operacional da 016 (#25)

Relatório: `2026-10-27-recuperacao-016-b10-b11.md`.

B10 e B11 **concluídos** em homologação descartável (`ensaio-c016-b10b11`, lista de elevação alternativa fora do repositório). B11 revisto à noite: agora há prova de privilégios, além das contagens:
- **B10:** gatilho manual da varredura (`VarreduraAvisos`) e espera **real** (SIGKILL durante a entrega segurada; nada antes dos 10 min; DESCONHECIDO/`QUEDA_DURANTE_ENTREGA` depois de 641 s reais; nada publicado);
- **B11:** restauração num cluster descartável com:
  - contagens iguais em 82 tabelas;
  - invariantes da 016 OK;
  - **742 itens comparados iguais, com a exceção declarada do concedente das pertinências** (papéis, pertinências, donos, ACL, RLS, políticas, funções e extensões). Isso **não** quer dizer que todo o catálogo de segurança ficou idêntico: o concedente (`GRANTED BY`) das 5 pertinências é normalizado na comparação e, na restaurada, é o superusuário da restauração.

Achados reais do procedimento, todos corrigidos e explicados no relatório da frente C (§4.2):
- as 5 pertinências com `GRANTED BY` (o concedente passa a ser o superusuário da restauração: limite declarado);
- o dono da `sslinfo`;
- um achado novo da comparação de privilégios: criada dentro da janela, a `pgcrypto` deixava o migrador dono das 36 funções dela.

**Origem da recusa da restauração:** decisão da responsável na P4 (A7, "não autorizada agora; mini missão própria"). Não foi proteção do roteiro nem recusa do mecanismo de aprovação da ferramenta. **Confirmado pela responsável na segunda revisão:** esta missão inclui expressamente o B11 e testes em ambientes descartáveis, então a restauração descrita está coberta e o B11 não volta a "parcial". Isso não autoriza restauração em produção nem em ambiente preservado.

Continuam abertos: C9 (agendamento), B4 completo, B9 (≥ 73 h), 5b, P5 e ativação (HOLD) e as decisões externas. A recusa explícita do reset de conta vinculada foi **fechada** na 024.

## 5. Testes executados (por commit)

| Prova | Onde | Commit | Resultado |
|---|---|---|---|
| CI (regressão inteira) | GitHub Actions | #20 `0f23355` | 21 + 177 + 374 + 12 = **584**, 0 falhas, 0 ignorados |
| CI | GitHub Actions | #21 `75640b1` | 21 + 177 + 408 + 12 = **618**, 0/0 |
| CI | GitHub Actions | #23 `dbc4366` | **618**, 0/0 |
| CI | GitHub Actions | #24 `8471e15` | 21 + 177 + 433 + 12 = **643**, 0/0 |
| CI | GitHub Actions | #25 `d51a05c` | 21 + 177 + 411 + 12 = **621**, 0/0 |
| CI | GitHub Actions | #24 `0d3f8c5` | 21 + 177 + 433 + 12 = **643**, 0/0 |
| CI | GitHub Actions | #24 `c4caeeb` (024 com as decisões e o reset vinculado) | 21 + **180** + **434** + 12 = **647**, 0 falhas, 0 ignorados (ops +3: `ResetContaVinculadaIT`; platform +1: `bancoExigeSegundaPessoaEOSolicitanteGravado`) |
| CI | GitHub Actions | #25 `9eed1fe` | 21 + 177 + 411 + 12 = **621**, 0 falhas, 0 ignorados |
| CI | GitHub Actions | #24 `8527954` e o commit destes documentos | rodam no PR: ver o PR |
| Regressão local `-pl nekkora-platform -am` | Windows, Docker | árvore do #24 antes do commit | core 21, ops 177, platform 433 com **1 falha minha** (`MailTemplatesTest`: o convite leva token), corrigida e repetida (2/2) antes do commit |
| Suíte de banco `db/tests/acessos-023` | local | `dbc4366` | A, B0–B3, C (34 recusas, 9 controles positivos), C6 (7 contraprovas): OK |
| E2E entre processos (`api/e2e/run.sh`) | local, stack descartável | árvore do #24 + `AccessInvitationsE2E` (commit seguinte do #24) | `AccessInvitationsE2E` 5/5, `CrossProcessE2E` 6/6, `IdentityCredentialsE2E` 7/7, `LinkedAccountsE2E` 6/6 |
| Homologação da 016 (laboratório) | local, descartável | `9afd1a1` e `d51a05c` | execução 1: B5–B8a, B10, B12 OK, B11 FALHA (achados); execução 2: B11, B12 OK |
| Validação estática | local | #20 `0f23355`; #25 `d51a05c` | 274 OK; OK com 3 verificações novas |
| Homologação externa (e-mail real) | — | — | **não repetida**: nada que a afete mudou desde `a25332d` |
| ITs da 024 (Testcontainers, papéis reais) | Windows, Docker | árvore do commit da 024 | `ResetContaVinculadaIT` 3/3; `GrantLifecycleIT` 4/4; `AccessInvitationsIT` 12/12; `RoleAdministrationIT` 8/8; `AdminReadRoutesIT` 13/13; `AccessContractTest` 2/2 |
| Contraprova sem a 024 (arquivo retirado de propósito) | local | idem | `ResetContaVinculadaIT`: a abertura volta a `SEM_FATOR_CONFIRMADO` e a execução a `FATOR_DIVERGENTE` (recusa por efeito); 2 falhas esperadas |
| E2E entre processos com 001–024 | local, stack descartável (removida) | idem | 24/24 (5 + 6 + 7 + 6) |
| B11 com privilégios (laboratório) | local, descartável | `a627f0a` | execução 3: FALHA (achado da `pgcrypto`); execução 4: OK, 742 itens comparados iguais, com a exceção declarada do concedente das pertinências; validação estática 297 OK |
| Suíte de banco `db/tests/acessos-024` (rodada `2026-10-28T004618Z_nekkora-a024`) | local, dois clusters descartáveis (removidos) | árvore de `8527954` (024 `b6390fe8…`) | **A:** 001–024, 11 janelas com retirada 0\|0\|0, §19 da 016 depois de tudo, invariantes 012–024 depois da retirada. **B0:** a 024 sobre 022 é recusada pela pré-condição, sem efeito. **B1:** sem janela, recusada por privilégio, sem efeito. **B2:** dados idênticos, exceto os 2 convites previstos (motivo 4); catálogo 023 → 024 com exatamente 41 linhas previstas e igual ao da instalação limpa; invariantes. **B3:** reaplicação recusada, sem efeito. **C:** 30 recusas e 16 controles positivos. **C5:** controle e 7 contraprovas. Tudo OK |
| ITs da regra de renovação e do reset (024 final) | Windows, Docker | árvore de `8527954` | `ResetContaVinculadaIT` **5/5** (3 + as 2 corridas); `GrantLifecycleIT` **6/6** (ciclo com pedido e aprovação de renovação; descarte e substituição do pedido; garantias do banco); `AccessContractTest` 2/2; `AccessInvitationsIT` 12/12; `RoleAdministrationIT` 8/8; `AdminReadRoutesIT` 13/13; `ClientGrantRevokeRouteIT` 4/4; `PortalIT` 10/10 |
| Contraprova das corridas sem a 024 | local | idem | vinculação primeiro: `FATOR_DIVERGENTE` (recusa por efeito); execução primeiro: a abertura nova cai em `SEM_FATOR_CONFIRMADO`; 2 falhas esperadas |
| E2E entre processos com 001–024 (rodada `2026-10-28T004820Z_nekkora-e2e`) | local, stack descartável (removida) | árvore de `8527954` (023 `dcb47661…`, 024 `b6390fe8…`) | `AccessInvitationsE2E` 5/5, `CrossProcessE2E` 6/6, `IdentityCredentialsE2E` 7/7, `LinkedAccountsE2E` 6/6: **24/24**. Migrations pelo modelo do migrador: 24 aplicadas, 023 e 024 com janela, §19 da 016 OK, fim 0\|0\|0 |
| Pré-voos propostos 023 e 024 (`ops/homologacao/testes/prevoo_023_024.sh`) | local, cluster descartável (removido), histórico do Flyway sintético | idem | P1 023 sobre 022: OK; P2 024 sobre 022: FALHOU (sem erro de SQL); P3 023 sobre 023: FALHOU; P4 024 sobre 023: OK (0 convites a fechar); P5 com um convite de emissor sem autoridade: OK (1 a fechar; a 024 avisa 1); P6 024 sobre 024: FALHOU |

Testes ignorados: nenhum nos CIs acima. O E2E fica fora do padrão do surefire (só roda pelo `run.sh`).

## 6. Decisões restantes

| Id | Decisão | Estado |
|---|---|---|
| D-CONV-1 a 4 | prazo, step-up, readmissão, perda de autoridade do emissor | **aceitas**; a D-CONV-4 mudou o código (024) |
| D-GRANT-1 | segunda pessoa na aprovação de concessão externa | **decidida**; código na 024 |
| D-GRANT-1a | renovação, reativação e ampliação com duas pessoas; KEEP só sem estender; aprovação vinculada aos valores | **aceita**; código na 024 (`8527954`) |
| D-EXT | entrada da pessoa externa: identidade global reutilizada, conta e permissões externas delimitadas, sem senha implícita; convite externo só depois do contrato | **direção definida**; contrato a fechar antes de habilitar |
| D-ELEV-023/024 | linhas de elevação da 023 e da 024 para aplicar em homologação | **proposta (§8), sem aprovação**: a responsável escolhe o ambiente e autoriza ou não cada linha |
| C9 | agendamento da varredura de avisos em produção, com monitoração e alerta | pendente |
| A7 (B11) | a missão cobre a restauração em cluster descartável | **confirmada** pela responsável (não vale para produção nem ambiente preservado) |

## 7. O que falta para a V1 (não está completa)

Base: `2026-10-23-api-v1-003-inventario-v1.md`, com o que mudou nesta missão.

| Requisito | Antes | Agora |
|---|---|---|
| Convite e onboarding (D-INV-02) | decisão; nenhuma rota | **PR #23/#24**: convites internos completos. Falta D-EXT (convite externo) e o e-mail real ligado fora do ensaio |
| Atribuição e remoção de papéis (GAP-14) | nenhuma rota | **PR #24** |
| Clientes e concessões (GAP-15) | só leitura e revogação | **PR #24**: criar, aprovar (segunda pessoa), pedir e aprovar renovação (segunda pessoa), revisar e revogar, com auditoria e aprovação vinculada aos valores, **sobre registros existentes**. A jornada externa completa **não** está entregue: faltam criar cliente, a entrada da pessoa externa e o convite externo (D-EXT) |
| Reset operacional de conta vinculada | recusa só por efeito | **PR #24 (024)**: recusa declarada `CONTA_VINCULADA` |
| Retirada de vínculo | sem rota | **PR #24** (administrativa). Suspensão de organização continua sem rota (operação) |
| Emissão do token de verificação de e-mail da conta (GAP-04) | decisão | inalterado (com a identidade global, provavelmente desnecessária; decidir) |
| Portões da 016 | B10/B11 parciais; nenhum portão operacional | **B10 e B11 concluídos em homologação descartável (#25)**; portões operacionais (5b, P5, ativação) continuam em HOLD; C9, B4, B9 abertos |
| E-mail real (GAP-10) | homologado (#21) | inalterado; ligar fora do ensaio é decisão de liberação |
| Origem do cliente atrás do proxy (B10 do inventário) | aberto (D-BORDA) | inalterado |
| L06 | decisão D-L06 | inalterado |
| Sessões individuais (GAP-13) | decisão (recomendado fora) | inalterado |
| **GAP-09 canal do Agent** | desenho pendente | **inalterado: fora desta missão** (será planejado a partir do relatório do Agent) |
| GAP-07 execução manual e cancelamento; GAP-08 workspaces, agendamentos e Workers | dependem do GAP-09 | inalterados |
| Capacidade sob carga; saúde e prontidão (GAP-11) | abertos | inalterados |

**A V1 não está completa:** sem o canal Platform–Agent (GAP-09) e as rotas de execução e agendamento (GAP-07/08), ela não pode ser declarada completa.

## 8. Proposta das linhas de elevação da 023 e da 024 (D-ELEV-023/024)

**Não há aprovação genérica dessas linhas.** Nada foi acrescentado a `ops/homologacao/implantacao/elevacao.txt`, nenhum ambiente foi criado ou alterado. Os pré-voos novos ficam inertes: o runner só os executa para uma versão com linha autorizada.

**Linhas propostas** (sha256 dos bytes LF versionados; os mesmos arquivos testados pela suíte, pelo E2E e pelas ITs):

```
023 dcb47661867fa451a20460e6b941a003999569d3c0185dd4962f75c1e8843498 tipo=superuser ambiente=<id escolhido>
024 b6390fe89d19f1e192335ed7fc22e05d11954944d7df7a5b787e63be8a5227cf tipo=superuser ambiente=<id escolhido>
```

A 023 é a de `dbc4366`, inalterada no #24. A 024 é a de `8527954`; qualquer mudança nela troca o hash e invalida esta proposta.

**Por que a janela é necessária** (medido pelas suítes, caso B1: sem a janela, cada uma é recusada por privilégio e nada muda; `mapa_privilegios.sh` não foi rodado para estas versões):
- **023:** transfere as funções do aceite para `nekkora_identity_owner` (`ALTER FUNCTION … OWNER TO`, papel do qual o migrador não é membro).
- **024:** transfere `identity_user_is_linked` e `identity_invitations_close_unauthorized` para `nekkora_identity_owner` e substitui `ops.alvo_inelegivel` e `ops_mfa_execute`, que são do dono da 016 (`nekkora_ops_mfa_owner`).

**Pré-voos** (somente leitura, formato dos de 016–022; executados como `admin_cluster`, com a trava obtida, depois do sha256 e do 0|0|0 e antes de conceder):

| Arquivo | sha256 | Conferências |
|---|---|---|
| `implantacao/preflight/023.sql` | `52ebb88a7f0d2dc9cf245e8fc92266b87a59344a67a469e133f3a07fb8103028` | 9: PostgreSQL 16; histórico exatamente 001–022 com sucesso; migrador sem atributo nem ACL de parâmetro; migrador dono do banco; 022 presente; objetos dos convites ausentes; **nenhum par de convites abertos duplicados** (a 023 recusaria); `nekkora_identity_owner` sem login nem participação; as duas restrições que a 023 confere com a definição esperada |
| `implantacao/preflight/024.sql` | `30396817f45c25a18e8b504c8079021235a8814cbb4384a7c0611c6ed23e8dc1` | 10: os quatro comuns (histórico 001–023); 023 presente (inclusive `access_can_invite`); objetos da 024 ausentes; as três restrições que a 024 troca com a definição da 023; `nekkora_identity_owner` e `nekkora_ops_mfa_owner` sem login nem participação; `ops.alvo_inelegivel` e `ops_mfa_execute` do dono da 016; **quantos convites abertos a 024 fechará na aplicação** (informativo, sempre OK) |

Prova: `ops/homologacao/testes/prevoo_023_024.sh` (§5), em cluster descartável, com 6 casos.

**Ambiente: decisão da responsável.** Duas opções:

| Opção | Como | Prós | Contras |
|---|---|---|---|
| **(a) recomendada:** ambiente descartável novo (por exemplo `ensaio-acessos-024`) | lista de elevação de laboratório (`HOMOLOG_ELEVACAO_ARQ`, só em `ensaio-*`, fora do repositório, como no `ensaio-c016-b10b11`) com as linhas de 016–020 e 022 e as duas propostas, todas vinculadas ao id novo | não toca o `ensaio-api-v1-003`, que guarda a homologação do e-mail real; `elevacao.txt` revisado fica como está | precisa de uma linha por versão vinculada ao id novo, aprovada para esse laboratório |
| (b) `ensaio-api-v1-003` (preservado, em 022) | linhas 023 e 024 em `elevacao.txt`, vinculadas a `ensaio-api-v1-003` | reaproveita o ambiente que já está em 022 | altera um ambiente preservado: exige autorização específica |

**Lacuna do runner (para qualquer opção):** hoje o `atualizar` só aceita 021 → 022, e o `criar` só aceita 015, 016 e 021. Para homologar a 023 e a 024 é preciso estender o runner com 022 → 023 e 023 → 024 e incluir 023 e 024 em `VINCULO_OBRIGATORIO`. A extensão não foi feita aqui. Ela entra na mesma etapa da homologação, depois da decisão sobre o ambiente e as linhas.

**Condições de cada janela** (iguais às da 022): sha256 conferido, pré-voo com `PREFLIGHT_OK`, uma janela por versão, retirada conferida em 0|0|0 depois de cada janela e também em falha, histórico do Flyway sem falha. **Produção:** decisão separada.
