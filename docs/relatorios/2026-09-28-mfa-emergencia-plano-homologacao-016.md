# Recuperação emergencial de MFA — plano de homologação controlada da 016

**Data:** 2026-09-28. **Versão 2.1**: v2 com os ajustes da revisão da v1, mais a correção de B9 pedida na revisão da v2 (§8). Plano aprovado tecnicamente como base para o desenho P1a (`2026-09-28-mfa-emergencia-homologacao-p1a-desenho.md`).
**Natureza:** **preparação**, para revisão. Nada foi executado para este plano, além de leituras e das inspeções da §6.
- Nenhuma migration aplicada em base persistente.
- E-mail real e frontend continuam pausados.
- A suíte completa não foi repetida.
- A publicação do commit na `main` **não** libera a aplicação da 016 em nenhuma base persistente.

## 1. Escopo e baseline

| Item | Valor |
|---|---|
| Commit | `6fc4dc0` (`main` = `origin/main`), sobre `03bc72c` (conjunto revisado da rodada 3) |
| Migration | `db/migrations/016_recuperacao_emergencial_mfa.sql`, **candidata**, SHA-256 `25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61` (bytes LF versionados) |
| Contrato | `docs/relatorios/2026-09-27-contrato-recuperacao-emergencial-mfa.md` (revisão 3, `ca4033b`), com as decisões da revisão 4 (`2026-09-28-mfa-emergencia-consolidacao-decisoes.md` v6, anexo v5, inventário de locks v3) |
| Evidência oficial | `ops/mfa-emergencia/saidas/2026-09-28T154847Z/` (versionada, sem o log bruto do Maven): 209 testes, 12/12 mutações, todas as etapas com saída 0 |
| Pacote revisado | `../relatorios/mfa-emergencia-implementacao-20260928T155926Z.zip` (SHA-256 `741a2d3f…8c7d19d`) |

**Objetivo da homologação:** comprovar a 016 e os caminhos da revisão 4 na **infraestrutura da homologação interna** (`ops/homologacao`), e não só nos containers de teste:
- implantação pela identidade dedicada com elevação controlada;
- TLS e `pg_hba` reais do ambiente;
- serviços em rede isolada, Platform atrás do gateway;
- operação pela CLI com certificado.

Separadamente, este plano define o que só se comprova com a infraestrutura real (CA, IdP, entrada, notificação).

**Fora de escopo:**
- produção;
- a `hml03` (existente, preservada);
- a base principal (`nekkora-postgres`);
- e-mail real (M365, `db0164c`);
- frontend e página de aviso publicada;
- mudança de política do contrato.

## 2. Matriz de validação

**Legenda:**
- **A** — já comprovado (evidência oficial 154847Z; não repetir);
- **B** — validável agora em ambiente **descartável** da homologação;
- **C** — depende de infraestrutura real ou de decisão pendente.

### 2.1 A — já comprovado (reuso da evidência; a homologação não repete)

| # | Cenário | Evidência existente |
|---|---|---|
| A1 | Instalação limpa 001–016; catálogo (37 funções `ops_*`, 21 tabelas em `ops`, 9 políticas); invariantes 012/013/014/016 | `suite_banco_resumo.txt`, `A01_diff_resumo.txt`, `A03_listagem_ops.txt` |
| A2 | Atualização populada 015 → 016: dados idênticos, histórico sem motivo preservado, restauração lógica | `B06_diff_dados.txt`, `B08_diff_catalogo_limpa.txt` |
| A3 | Fluxo completo: CLI com certificado → avisos → confirmação por POST → espera → aprovação → execução → novo MFA | `EmergencyRecoveryFlowIT` |
| A4 | Autoria por `session_user` + `sslinfo`; certificado por ato; guarda `READ COMMITTED` | `IdentidadeIT`, `CertificadosIT`, `IsolamentoIT` |
| A5 | Ordem de locks I-1 a I-5, com contraprovas `40P01` | `InventarioLocksOpsIT`, `InventarioLocksPlatformIT` |
| A6 | Itens 1–10 da revisão 4 (prazos em horas, impedimentos, recusas, origem/cota/rotação, declaração, tokens, CLI, tentativas) | `Revisao4*IT`, `AvisosIT`, `OpsCliContinuidadeIT` |
| A7 | Protocolo `ENTREGANDO` e perda da confirmação do commit | `EntregaReivindicacaoIT` |
| A8 | 12 contraprovas de mutação; varredura sem segredo, com controle positivo | `mutacoes.txt`, `varredura*.txt` |

Limites já declarados continuam valendo (relatório §5f.4 e §6): CA/IdP simulados, fixtures temporais só em base de teste, sem estresse longo.

### 2.2 B — validável agora, em ambiente descartável da homologação

Pré-requisito comum: as adaptações da §5 (P1a–P1c), revisadas, e o ambiente descartável da §4.

**Tempo e fixtures no ambiente de homologação** (vale para B8 e B9):
- **Contatos:** só são elegíveis com 720 h completas desde o registro do canal (item 1). Num ambiente novo, sem fixture temporal, nenhum contato é elegível antes de 30 dias.
- **Solicitação:** a aprovação final exige a confirmação + 24 h e o aceite dos demais + 24 h.
- **Consequência:** o fluxo completo **não** termina no mesmo dia sem fixture. Este plano separa:
  - o que roda em tempo real imediato (B8a);
  - a conclusão com **fixture declarada** (B8b, só no ambiente descartável);
  - a comprovação em tempo real, sem fixture de solicitação (B9, mini missão posterior).

| # | Cenário | Pré-requisito | Procedimento | Resultado esperado | Evidência exigida |
|---|---|---|---|---|---|
| B1 | Medição do que a 016 exige da identidade de implantação | Runner com 001–016 (P1b) | `mapa_privilegios.sh` com a 016, com e sem papéis pré-criados | Lista objetiva: `CREATE ROLE` (papéis `nekkora_ops_*`), `CREATE EXTENSION sslinfo` (não confiável), e o que mais aparecer | Saída do mapa, anexada à decisão de elevação (D-ELEV) |
| B2 | Implantação 001–016 pela identidade dedicada | D-ELEV (homologação) e `elevacao.txt` com `016 25bef818…` **acrescentada** às linhas de 011–013 | `homologacao.sh criar ensaio-016a` (001–016 pelo Flyway) | **Quatro** janelas de elevação, uma por versão: as de 011, 012 e 013, já autorizadas e mantidas com os mesmos sha256, e a **nova** da 016, só com o sha256 autorizado. Nenhuma elevação nas demais versões. Revogação e conferência `0\|0\|0` depois de **cada** janela e ao fim. `estado` = LIBERADO com 16 versões e `flyway validate` | `implantacao.txt`, `janelas_de_elevacao.txt` (4 janelas, 011/012/013/016), `flyway_info.txt`, `liberacao.txt` |
| B3 | Recusas da elevação | Idem | Lista com sha256 errado; sem `HOMOLOG_AUTORIZA_ELEVACAO`; versão fora da lista | Recusa **antes** de conceder, nada aplicado | Saídas das recusas |
| B4 | Interrupção na janela da 016 | Idem; id `ensaio-*` | `ensaio-interrupcao` com SIGKILL durante a 016 | BLOQUEADO, `recuperar` → migrador sem elevação, `implantar` conclui a 016, LIBERADO | Evidências de interrupção e recuperação |
| B5 | Catálogo e invariantes no ambiente | B2 | Bloco de invariantes da 016 (§19) e listagem de `ops` no ambiente | Igual à A1 (37/21/9); nenhuma diferença de catálogo em `public` além da matriz | Listagem e diff contra a A1 |
| B6 | Autenticação por certificado dos operadores | `pg_hba`/`pg_ident` de homologação com `cert map=ops` (P1b); CA operacional **descartável** do ambiente | Papel `op_*` pelo DBA (fora da migration); conexão com certificado válido, senha, certificado de CA estranha, CN ≠ papel | Só o certificado válido da CA operacional autentica; o resto é recusado pelo `pg_hba` ou pela 016 | Log de conexões do PostgreSQL (sem segredo) e saídas da CLI |
| B7 | Identidades de serviço novas | P1 | `nekkora_ops_ca` e `nekkora_ops_intake` com SCRAM, cada uma só do próprio IP | Cada uma executa só as próprias funções; nenhuma lê tabelas | Matriz de EXECUTE conferida no ambiente |
| B8a | Fluxo operacional em tempo real, até a espera, na rede do ambiente | B2, B6, B7; transporte de homologação (§2.5); job de CLI na rede interna (IP próprio); contatos elegíveis pela fixture **declarada** de contatos (§2.6) | Abertura por retorno e por entrada simulada; verificação com declaração; pré-aprovação; avisos pela Platform (despachante ligado só no ambiente) com o transporte de homologação respondendo `ACEITO_PELO_TRANSPORTE`; confirmação do titular pela rota do gateway, com o token lido da caixa restrita; aprovação imediata | Mesmos desfechos da A3 até aqui, atrás do gateway, com TLS e `pg_hba` reais: `PRE_APROVADA` confirmada; aprovação imediata recusada com `ESPERA_NAO_CUMPRIDA`, sem mudar o estado. A recusa `AVISO_DEMAIS_PENDENTE` exige a espera do titular cumprida e fica para B8b/B9. Nenhuma fixture de solicitação | Saídas da CLI, log local sanitizado, respostas da rota (status), estado final, índice da caixa sem tokens |
| B8b | Conclusão do fluxo (aprovação → execução → novo MFA) | B8a | Fixture temporal **declarada** de solicitação no ambiente descartável: o mesmo deslocamento uniforme dos testes (`recuarSolicitacao`), pelo `admin_cluster`, a partir do job de ferramentas; depois, aprovação, execução pela CLI e novo cadastro de MFA pela API | `APROVADA` → `EXECUTADA`; fator, códigos, desafios e sessões do titular resetados; senha, papéis e vínculos intactos; novo MFA pelo gateway | Idem B8a, com o registro da fixture (comando, instante e solicitação afetada) |
| B9 | Prazos em **tempo real**, sem fixture de solicitação — **mini missão posterior, explicitamente pendente** | **Nova preparação** (P5 descarta `ensaio-016a`): ambiente descartável próprio, com a mesma implantação (B2) e o transporte de homologação, mantido pelo período; **solicitações criadas nele que nunca tenham recebido fixture temporal**. **Contatos:** (i) sem fixture nenhuma, cadastrados ≥ 720 h antes do início (ambiente com 30 dias de antecedência); ou (ii) só a fixture declarada de contatos (§2.6), aplicada antes de qualquer solicitação. A escolha é da mini missão | Expirações e esperas comprovadas, cada uma com a duração real: **B9a** não verificada expira em **24 h** da abertura; **B9b** aprovação só com confirmação + 24 h **e** aceite dos demais + 24 h; **B9c** janela de execução de **60 min** depois da aprovação (expira sem executar); **B9d** prazo total de **72 h** (`T_total`) de uma solicitação confirmada e não aprovada. Duração mínima: **≈ 73 h**, com B9a–B9c em paralelo dentro das primeiras ≈ 26 h | Cada desfecho no instante previsto (`EXPIRADA` com `PRAZO_ENCERRADO`, ou aprovação aceita); nenhuma fixture de solicitação | Instantes do banco por ato (sem conteúdo sensível) e registro de que não houve fixture de solicitação |
| B10 | Despachante e varredura no ambiente | `nekkora.emergency-notices.enabled=true` e o transporte de homologação (§2.5) **só** no ambiente descartável | Emissão periódica; desfechos simulados pela caixa de controle (aceito, enfileirado, recusado, incerto); tentativas paradas; varredura acionada manualmente | Desfechos registrados pelo caminho normal (`recordTransportResult` → `ops_notice_delivery_result`), sem token nem destinatário no log; recuperação correta. **A varredura não tem agendamento** (limite conhecido) | Log da Platform sanitizado, índice da caixa e varredura com controle positivo |
| B11 | Backup e restauração lógica com `ops` | B2 | `pg_dump` por `backup_nekkora` (`pg_read_all_data`); restauração num segundo cluster descartável com papéis pré-criados | Dump inclui `ops`. A restauração exige `sslinfo` (superusuário) e os papéis de cluster; contagens iguais | Procedimento medido e contagens por tabela |
| B12 | Preservação | Todo o ensaio | Fotografia antes e depois (como `validar.sh`, etapa 5) | `hml03`, `nekkora-postgres` e demais recursos idênticos | `containers_antes/depois`, redes e volumes |

### 2.3 C — dependente de infraestrutura real ou de decisão

| # | Cenário | Depende de | Resultado esperado quando possível | Evidência exigida |
|---|---|---|---|---|
| C1 | CA operacional real (serviço, guarda da chave, validade ≤ 15 min, revogação) | D-CA | Emissão só após IdP com `acr`/`amr` exigidos; revogação refletida no ato seguinte | Registro da CA e testes de recusa (A4) repetidos contra ela |
| C2 | IdP corporativo | D-IDP | `acr` `phr`/`phrh` + `amr` `mfa` só quando a política exigiu MFA resistente; `auth_time` confiável | Tokens de teste do IdP validados pelo verificador da CA; negativos (`hwk` isolado) recusados |
| C3 | Serviço de entrada (desafio de ida e volta, HMAC em cofre, rotação) | D-ENT | Pseudônimos só no banco; cota por origem; rotação de 25 h | Registro das entradas e ensaio de rotação (A6 contra o serviço real) |
| C4 | Notificação real (e-mail) | **Pausado** (D3, `db0164c`) | Entrega com `attempt_id`, links, resultado do transporte | Fora deste plano até decisão explícita de retomada |
| C5 | Página de aviso publicada | **Pausado** (frontend) | CSP sem `unsafe-inline`, POST com CSRF/Origin | Idem |
| C6 | Relógio do servidor | D-REL | Sincronização com correção gradual, sem saltos para trás, monitorada | Configuração e monitoração do host de produção |
| C7 | Rede de administração e `pg_hba` de produção (sem pooler) | Implantação | Operadores só pela rede de administração, com certificado | Revisão da configuração de produção |
| C8 | Retenção (inclusive pseudônimos de origem) | D9 (jurídico e encarregado) | Prazos e expurgo definidos e implementados | Decisão escrita e mudança revisada |
| C9 | Agendamento do despachante e da varredura em produção | C4 e decisão operacional | Agendados, monitorados, com alerta | Configuração revisada |

**Nota sobre expiração:** a 016 não tem tarefa que expira solicitações em segundo plano. A expiração é aplicada pelo **primeiro ato** (ou consulta com a trava) depois do instante. Em B9, cada expiração é comprovada por um ato feito logo depois do instante previsto e por outro feito logo antes, que precisa ser aceito ou recusado por outro motivo.

### 2.5 Transporte de homologação (desenho; implementação em P1b)

**Por que:** `LocalMailSender` só registra a intenção. Ele devolve `ENFILEIRADO`, não expõe tokens e nunca satisfaz a condição (2) da aprovação (aceite dos demais). Com ele, B8 não termina.

**Requisitos:**
1. **Sem envio externo:** nenhuma conexão de rede de saída. O container da Platform continua só na rede `interna`, sem saída, e o transporte não abre socket.
2. **Ativação explícita e restrita:**
   - bean só com `nekkora.mail.transport=homologacao` **e** `nekkora.environment` com o id do ambiente, no formato `ensaio-*`;
   - sem as duas propriedades, vale o `LocalMailSender` padrão;
   - com o transporte ativo, a Platform registra um aviso na partida.
   - Teste de configuração: o bean **não** existe no perfil padrão, e existe só com as duas propriedades.
3. **Captura restrita dos tokens:**
   - cada mensagem vai para a **caixa**, um diretório no diretório de segredos do ambiente (`$HOMOLOG_SEGREDOS/<id>/caixa/`), montado só no container da Platform (escrita) e lido só pelo operador da homologação no host;
   - um arquivo por tentativa (`<attempt_id>.json`: tipo, organização, destinatário, links e tokens), com permissão só do dono;
   - a caixa fica **fora** do Git e **fora** das evidências, e é apagada no descarte, junto com os segredos;
   - o **índice** da caixa, que pode ir à evidência, tem só `attempt_id`, tipo, desfecho simulado e hash do destinatário. Nunca token, link ou endereço.
4. **Desfechos pelo caminho normal:**
   - o transporte devolve um `MailSender.Outcome`. O despachante real registra o desfecho por `recordTransportResult` → `ops_notice_delivery_result`, como em produção;
   - padrão: `ACEITO_PELO_TRANSPORTE`;
   - um arquivo de controle na caixa (`controle.conf`, só do operador) permite programar, por destinatário ou pela próxima tentativa, `ENFILEIRADO`, `RECUSADO_DEFINITIVO`, `INCERTO` ou exceção. Isso cobre B10 e a volta da vigente.
   - Resultado **assíncrono** posterior (aceite depois de `ENFILEIRADA`) não tem caminho de entrada na aplicação hoje. Fica fora de B8/B10; a varredura leva a tentativa a `DESCONHECIDO` depois de 24 h.
5. **Outras mensagens** (redefinição de senha, verificação de e-mail) passam pela mesma caixa, com as mesmas regras.
6. **Varredura da evidência:** `varredura.py` com controle positivo sobre a evidência do ambiente, para provar que nenhum token da caixa vazou para logs ou evidência.

**Testes do transporte (P1c):**
- ativação só com as duas propriedades;
- arquivo por tentativa com permissão restrita;
- índice sem segredo;
- desfechos programados registrados pelo caminho normal;
- ausência de conexão de saída (container sem rede de saída).

### 2.6 Fixture declarada de contatos (B8, e B9 opção ii)

- **O quê:** o deslocamento de `created_at` e `channel_set_at` dos contatos **sintéticos** do ambiente para 721 h antes, o mesmo de `OpsBanco.envelhecerContato`. É a única forma de ter contatos elegíveis sem esperar 30 dias.
- **Como:** pelo `admin_cluster`, a partir do job de ferramentas, em transação com `session_replication_role = replica`, **antes** de qualquer solicitação. Registro do comando, dos contatos afetados e do instante na evidência.
- **Limite declarado:** comprova o fluxo, não a carência de 720 h. A carência já está comprovada em A6 (`Revisao4PrazosEImpedimentosIT`, limites exatos e fluxo 719 h/720 h).
- **Nunca** em base persistente. A fixture de solicitação de B8b segue as mesmas regras.

## 3. Dependências e critérios de encerramento

| Pendência | Situação hoje | Critério objetivo para encerrar |
|---|---|---|
| **D-ELEV** — elevação da 016 | A 016 exige superusuário (`CREATE ROLE`, `sslinfo`). `implantacao/elevacao.txt` autoriza só 011–013 (decisão de 2026-09-26, só para homologação isolada) | (1) Medição B1 anexada. (2) Decisão escrita do responsável autorizando `016 25bef818…` **só** na homologação. (3) Linha em `elevacao.txt` revisada e commitada. (4) B2–B4 com saída 0. **Produção:** decisão separada |
| **D-CA** — CA operacional | Biblioteca testada em processo (`EmissorCertificados`); sem serviço nem guarda de chave | Serviço implantado; chave em HSM ou cofre; validade ≤ 15 min e `auth_time` ≤ 5 min aplicados; revogação e registro testados contra a 016 (C1) |
| **D-IDP** — IdP | Simulado (`IdpSimulado`); verificador exige `acr` `phr`/`phrh` + `amr` `mfa` | IdP configurado conforme o gate 5 do relatório; casos positivos e negativos (C2) aprovados |
| **D-ENT** — serviço de entrada | Proposto; simulado com pseudônimos aleatórios | Serviço com desafio de ida e volta, chave HMAC em cofre e procedimento de rotação; identidade `nekkora_ops_intake` só com as duas funções; C3 aprovado |
| **D3** — notificações | `LocalMailSender`; e-mail real **pausado** | Decisão explícita de retomada; depois, entrega real validada (gate 1). **Não** é pré-requisito das etapas B |
| **Agendamento** | Despachante desligado por padrão; varredura sem agendamento | Decisão operacional; agendamento com monitoração e alerta (C9); B10 aprovado em homologação |
| **D-REL** — relógio | Gate 6 | Host de produção com sincronização gradual e alarme de salto; registro da configuração |
| **D9** — retenção | Aberta | Decisão do jurídico e do encarregado (inclui pseudônimos de origem); mudança revisada |

## 4. Ambiente e recuperação

- **Isolamento:**
  - ambiente novo da homologação com id `ensaio-016a` (prefixo `ensaio-`, exigido pelo `ensaio-interrupcao`);
  - rede `interna` sem saída e sem porta publicada; só o gateway na `borda`, ligado a interface local.
  - **Conflito atual:** o runner usa sub-redes **fixas** (`10.252.10.0/24` e `10.252.11.0/24`), ainda ocupadas pelas redes da `hml03` (containers parados; redes e volume `nekkora-homologacao-hml03_pgdata` existem). O `criar` recusaria. **Não** se propõe destruir a `hml03`. A adaptação P1 (desenho em P1a, implementação em P1b) parametriza as sub-redes (ex.: `10.252.20.0/24` e `10.252.21.0/24`), mantendo a recusa de sub-rede ocupada.
- **Dados:** só sintéticos, gerados por execução (`sql/fixture.sql` e fixtures da 016 pela CLI). Nada copiado da base principal nem da `hml03`.
- **Segredos:** diretório próprio fora do Git (`$HOME/.nekkora/homologacao/ensaio-016a`). A CA operacional e a de servidor são **descartáveis** do ambiente, com chaves só nesse diretório.
- **Identificação:** projeto `nekkora-homologacao-ensaio-016a`, rótulos do runner em containers, redes e volumes; inventário antes e depois (B12).
- **Interrupção:** o runner para na primeira falha, sem `repair`, `baseline` nem `clean`. Falha ou queda na janela de elevação deixa o ambiente BLOQUEADO até `recuperar` (procedimento existente, `IMPLANTACAO.md` §5).
- **Descarte:** `HOMOLOG_CONFIRMA_DESTRUIR=ensaio-016a homologacao.sh destruir ensaio-016a`. O diretório de segredos é apagado manualmente, depois de confirmar o fim do uso. Nenhum volume nomeado fora do projeto.
- **Evidências:** `ops/homologacao/evidencias/ensaio-016a/`, sanitizadas, varridas (`varredura.py`, com controle positivo) e preservadas antes do descarte. Pacote de revisão no formato de `empacotar.sh`.

## 5. Sequência proposta e autorizações

| Etapa | O que | Autorização necessária |
|---|---|---|
| P0 | Revisão deste plano (v2) | Revisor |
| P1a | **Desenho** das adaptações, em documento, sem código: runner 001–016 (lista de versões e cópia imutável); `lib/estado.sh` esperando 16 versões; sub-redes parametrizadas; `pg_hba`/`pg_ident` com `cert map=ops` para `op_*` e SCRAM por IP para `nekkora_ops_ca`/`nekkora_ops_intake`; `ssl_ca_file` da CA operacional; job de CLI na rede interna; propriedades do despachante e do transporte por ambiente; transporte de homologação (§2.5); registro das fixtures declaradas (§2.6) | Revisor |
| P1b | **Implementação e validação estática**, sem criar recursos: código e configuração do P1a; `bash -n` dos scripts; compilação (`mvn -o compile`/`test-compile`, sem executar testes); revisão do diff | Revisão do diff |
| P1c | **Testes que criam recursos**, em containers descartáveis de teste (Testcontainers), sem ambiente de homologação: testes do transporte (§2.5), da configuração de `pg_hba`/`pg_ident` e do runner em modo de validação; regressão pertinente | Autorização de execução; commit do P1 depois da revisão |
| P2 | Medição B1 | Execução em ambiente descartável |
| P3 | Decisão de elevação da 016 para a homologação (D-ELEV), com a **nova** linha em `elevacao.txt` ao lado das de 011–013 | **Responsável**, por escrito (como em 2026-09-26) |
| P4 | Criação de `ensaio-016a` e B2–B8b, B10–B12 | Execução autorizada; ambiente descartável; fixtures declaradas só nele |
| P5 | Pacote e revisão das evidências; descarte do ambiente | Revisor; descarte com confirmação explícita do id |
| P6 | **B9 — mini missão posterior, pendente.** Tempo real (≈ 73 h) e contatos pela opção (i) ou (ii) | Decisão própria; não bloqueia as mini missões da API (inventário das rotas) |
| P7 | C1–C3, C6–C9 conforme as decisões D-* | Decisões próprias; fora deste ciclo |
| — | Produção | Gates do relatório (§7) e decisões separadas. **Nada deste plano autoriza** |

## 6. Limpeza pendente e arquivos de outras missões

**`nk016-smoke`: identificação conferida em 2026-09-28** (proposta de remoção, **não** executada).

| Campo | Valor |
|---|---|
| Id | `30d99456af2db140b4a27190be9a8eb587e93e6431f31cc1df75d0f901297e93` |
| Imagem | `postgres:16-alpine` |
| Criação | 2026-09-27T23:47:17Z (ensaio manual da rodada 1, instalação limpa inicial da 016) |
| Estado | `exited` |
| Rede | `bridge` (padrão), sem porta publicada |
| Montagens | bind do `scratchpad/mig016` de uma sessão anterior → `/docker-entrypoint-initdb.d`; volume **anônimo** `24f11db19f25…` → `PGDATA` |
| Rótulos | nenhum. O volume tem só `com.docker.volume.anonymous`, é usado **só** por este container e foi criado no mesmo instante |

- **Diferença do registro:** o relatório (§8) listava só `docker rm -f nk016-smoke`. Como há um volume anônimo, a remoção completa proposta é `docker rm -v nk016-smoke`, que remove o container e o volume anônimo dele.
- Antes da remoção, conferir de novo o id e que `docker ps -a --filter volume=24f11db19f25dd55372af21c6a302c316062b123803a0a84bbf00b2bb4b9c533` lista só `nk016-smoke`.
- Não tem dados reais: só a instalação sintética da 016 da rodada 1.

**Arquivos não rastreados de outras missões, preservados** (sem commit, sem remoção):
- `api/e2e/email/`;
- `db/tests/portal-8-2/`;
- saídas em `db/tests/mfa-014`, `db/tests/portal-013`, `db/tests/seguranca-012` e `db/tests/mfa-emergencia-016`.

**Execuções desta missão, preservadas e não versionadas:**
- execuções não oficiais e a inválida em `ops/mfa-emergencia/saidas/`;
- `125940Z` (anterior às correções da revisão);
- os `maven.txt` brutos.

**Recursos da `hml03`** (containers parados, redes e volume `nekkora-homologacao-hml03_pgdata`): **intocados**. Este plano não propõe alterá-los.

## 7. Decisões pedidas nesta revisão (v2)

As recomendações da revisão da v1 estão incorporadas; ficam para confirmação:
1. **Matriz e sequência:** aceitar a estrutura com os ajustes da v2 (B2, B8a/B8b, B9, P1a–P1c, §2.5, §2.6).
2. **P1:** revisar primeiro o **desenho** (P1a), incluindo o transporte de homologação. Depois, implementar o escopo delimitado (P1b), com validação estática, e só então os testes que criam recursos (P1c).
3. **Fixtures declaradas no ambiente descartável:** aceitar a de contatos (§2.6) para B8a e a de solicitação para B8b, ou pedir outra forma de comprovar B8 (por exemplo, só B8a, deixando a conclusão para B9).
4. **`nk016-smoke`:** a remoção (`docker rm -v`, depois de reconferir o id completo e a exclusividade do volume) **aguarda autorização de descarte** do responsável; não foi executada.
5. **B9:** mini missão posterior, **explicitamente pendente**, que não bloqueia o inventário das rotas da API.

## 8. Histórico

- **v1** (2026-09-28): estrutura A/B/C, dependências, ambiente, sequência, limpeza.
- **v2** (2026-09-28), ajustes da revisão da v1:
  - **B8:** o `LocalMailSender` devolve `ENFILEIRADO`, não expõe tokens e não satisfaz a condição de aceite. Por isso entra o transporte de homologação (§2.5), e B8 foi dividido em B8a (tempo real até a espera) e B8b (conclusão com fixture declarada).
  - **Tempo e contatos:** os contatos só são elegíveis com 720 h. Por isso entra a fixture declarada de contatos (§2.6).
  - **B9:** as expirações foram especificadas (24 h da não verificada, 24 h + 24 h da aprovação, 60 min da janela de execução, 72 h do prazo total; ≈ 73 h no mínimo), com duas opções para os contatos, e B9 virou mini missão posterior.
  - **B2:** as janelas de 011–013 são mantidas, e a da 016 é a nova autorização (quatro janelas no total).
  - **P1:** separado em desenho (P1a), implementação com validação estática (P1b) e testes que criam recursos (P1c).
- **v2.1** (2026-09-28), revisão da v2:
  - plano aprovado tecnicamente como base para P1a;
  - **B9:** como P5 descarta o ambiente, B9 exige nova preparação e solicitações que nunca tenham recebido fixture temporal. Corrigida a referência a "no mesmo ambiente";
  - as observações da revisão para P1a (linhas e campos das fixtures, `SET LOCAL`, verificações de isolamento; aceite sempre identificado como simulado; mensagens sem `attempt_id` sem sobrescrita) estão no documento de desenho P1a.
