# Organizações e acesso: demonstrações pela API (missão 002, item 2)

**Base:** `main` (`8e7ef18`). **Tipo:** só testes. Nenhum contrato, código de produção ou migration muda.

A missão pede que as jornadas de organização sejam demonstradas pela API. A maior parte já estava coberta pelos testes de integração da Missão 1 e dos contratos P1 a P4. Este PR fecha as duas lacunas encontradas no levantamento:
- a conta criada pela identidade não tem credencial local;
- suspensão da conta e retirada do vínculo derrubam o acesso, inclusive a sessão de organização já emitida.

## Mapa das demonstrações

| Exigência da missão | Demonstração |
|---|---|
| Identidade confirmada e autenticada cria organização pelos requisitos existentes | `OrganizationCreationIT.criaComStepUpOrgAdminEventosETrocaPelaSessaoDeOrganizacao`, `semStepUpRecusa403SemEfeitoEDevolveAReservaDeR11`, `tetoDeDezDentroDaFuncaoSobConcorrencia`, `r11TresCriacoesPorIdentidadeAQuartaE429`; E2E `LinkedAccountsE2E` e `IdentityCredentialsE2E` (PRs #10 a #12) |
| A conta criada recebe os vínculos e papéis corretos, **sem credencial local** | `OrganizationCreationIT` (vínculo ACTIVE, ORG_ADMIN, `identity_users` pelo canal de cadastro); **novo:** `IdentityOrganizationJourneysIT.contaCriadaPelaIdentidadeNaoTemCredencialLocal` (nenhuma linha em `user_credentials`; a senha da identidade não abre a entrada local, que responde 401 genérico) |
| A pessoa só lista e acessa organizações autorizadas | `IdentityOrgSessionIT.listaSoAsOrganizacoesVinculadasEElegiveis`, `semAcessoEUniformeParaQualquerCausa` (inexistente, alheia, conta antiga não vinculada e organização suspensa recebem a mesma resposta) |
| Suspensão, retirada de vínculo e revogação da sessão de identidade impedem o acesso | **novos:** `IdentityOrganizationJourneysIT.suspensaoDaContaDerrubaAcessoMasNaoAIdentidade`, `retiradaDoVinculoDerrubaAcesso` e `suspensaoDaOrganizacaoDerrubaAcesso`. Em cada caso: fora da lista, troca recusada, sessão já emitida derrubada na requisição seguinte (401), sessão de identidade preservada. Já existentes: `IdentityOrgSessionIT.logoutDaIdentidadeDerrubaASessaoDeOrganizacaoDerivadaSemEscritaNaOrganizacao`, `organizacaoSuspensaDuranteAEsperaDaEmissaoERecusada` |
| A vinculação exige as provas dos dois lados e encerra a entrada local | `AccountLinkIT.jornadaCompletaComTotpFechaAPortaLocalEAbreAEntradaGeral`, `falhasDaProvaRespondemOMesmo401SemEfeito`, `confirmacaoSemStepUpRecusaSemConsumirODesafio`, `loginLocalQueEsperaAVinculacaoERecusadoComO401Generico`; E2E `LinkedAccountsE2E` (sessão local revogada no ato, porta local fechada) |
| Ações administrativas funcionam com o step-up da sessão vinculada | `LinkedOrgStepUpIT.elevaSoASessaoDeOrganizacaoSolicitanteEHabilitaAAcaoProtegida`; E2E `LinkedAccountsE2E` (reset administrativo recusado sem step-up e aceito com o step-up vinculado) |
| Falhas e resultados incertos não provocam repetição automática da criação | `OrganizationCreationIT.confirmacaoPerdidaDepoisDoCommitMantemAReserva` (resultado incerto: a reserva de R11 é mantida e nada é repetido), `falhaAoGravarOEventoDesfazTudo`; `IdentityTransactions` não tem laço de nova tentativa |
| `canCreateOrganization` é indicação; a escrita reconfere | `IdentityOrgSessionIT.criacaoComAChavePropriaDesligadaE404EIndicacaoFalsa`; `OrganizationCreationIT.semStepUpRecusa403SemEfeitoEDevolveAReservaDeR11` e `tetoDeDezDentroDaFuncaoSobConcorrencia` (a função reconfere step-up e teto sob o lock da identidade) |

**O que estes testes demonstram, e o que não demonstram.** As alterações de estado que retiram o acesso (suspensão da conta, retirada do vínculo, suspensão da organização) são **preparadas diretamente no banco** pelo teste (superusuário, no contexto da organização). Os testes demonstram, pela API, a **reação** da Platform a esses estados: lista, troca de sessão e sessão já emitida. **Não** são jornadas administrativas completas executadas exclusivamente pela API: as rotas administrativas de suspensão e retirada de vínculo ficam fora desta demonstração.

**Fixture declarada nos testes novos.** Para suspender a conta ou retirar o vínculo da única administradora, o teste insere um segundo ORG_ADMIN. A regra da 003, "organização ficaria sem ORG_ADMIN efetivo", recusa a alteração sem ele, e continua valendo.

**Capacidade inicial de execução.** Segue como decisão de produto pendente. A proposta está no PR #9, §5. Estes testes não dependem dela.

## Evidências

- `IdentityOrganizationJourneysIT`: 4 testes, 0 falhas (local, sobre `main`).
- Regressão completa: no CI do PR (só testes foram acrescentados).
