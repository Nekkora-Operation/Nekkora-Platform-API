# Missão PLATFORM-API-V1-003: base, consolidação e homologação na nova máquina

**Data:** 2026-10-23. **Máquina:** nova (Windows 11, Docker 29.7.2, Java 21.0.12 da Microsoft, Git 2.55 com `core.autocrlf=true` no sistema). **Clone:** `C:\Users\nicol\nekkora\Nekkora-Platform-API`, novo, com `core.autocrlf=false` local (§4.6).

Este relatório cobre os itens 1 a 4 e 7 da missão. A ativação das organizações (item 5) tem relatório próprio (`2026-10-23-api-v1-003-ativacao-organizacoes.md`). O inventário da V1 e a preparação do e-mail real (itens 6 e 8) estão em `2026-10-23-api-v1-003-inventario-v1.md` e `2026-10-23-api-v1-003-email-real-preparacao.md`.

## 1. Fotografia do estado inicial

**`main` = `bf977d7`** (merge do #15, 2026-10-23T19:02:44Z). CI da `main` nesse commit: run 40008270448, sucesso. Regressão completa nesta máquina, no mesmo commit (`regressao_maven.sh`, fontes estáveis): **570 testes, 0 falhas, 0 erros, 0 ignorados**.

| PR | Branch → base | Estado | Head | Merge |
|---|---|---|---|---|
| #9 | `docs/identidade-002-base` → `main` | mesclado 22/10 | `b09a8a3` | `9074a55` |
| #10 | `feat/identidade-002-troca-mfa` → `main` | mesclado 22/10 | `676c2ed` | `cebed22` |
| #11 | `feat/identidade-002-credenciais` → `troca-mfa` | mesclado 22/10 | `4504415` | `dfbc762` (na branch) |
| #12 | `feat/identidade-002-recuperacao-senha` → `credenciais` | mesclado 22/10 | `ae85ffb` | `0ca633b` (na branch) |
| #13 | `feat/identidade-002-email` → `recuperacao-senha` | mesclado 23/10 | `a39f684` | `5ecb0f8` (na branch) |
| #14 | `test/identidade-002-organizacoes` → `main` | mesclado 23/10 | `d1207eb` | `cdc383b` |
| #15 | `feat/identidade-002-recuperacao-senha` → `main` | mesclado 23/10 | `5ecb0f8` | `bf977d7` |

O estado observado no enunciado estava defasado: depois dele, #13 e #14 foram mesclados e o #15 levou a pilha `credenciais → recuperação → e-mail` para a `main`.

**Conferência por conteúdo e ancestralidade, não por título:**
- `676c2ed`, `4504415`, `ae85ffb`, `039f46f`, `9be9c38`, `a39f684`, `fc67c2f`, `d1207eb` e `b09a8a3` são ancestrais de `main`.
- Os únicos commits das branches remotas fora da `main` são os merges `dfbc762` (#11) e `0ca633b` (#12) e o `080ce06` (#6). `git diff 4504415 dfbc762` e `git diff ae85ffb 0ca633b` são vazios: esses merges não trazem conteúdo além dos heads já incorporados.
- A árvore de `bf977d7` é idêntica à de um merge refeito localmente de `cdc383b` com `5ecb0f8` (`e58bc924…`): o #15 não teve resolução manual de conflito.

## 2. Mapa e ordem das entregas desta missão

**Já está na `main`:** todo o conteúdo aprovado de #9 a #15 (base da missão 002, troca de MFA, credenciais, recuperação de senha por e-mail, transporte Graph com fila persistente cifrada e os testes de organizações).

**Precisa ser incorporado:** nada de #11, #12 e #13. O item 2 (consolidação) já foi feito pelo #15, e as correções aprovadas estão presentes (§3).

**Ordem segura das PRs desta missão:**

| Ordem | PR | Base | Por quê |
|---|---|---|---|
| 1 | `fix/021-acesso-papeis-predefinidos` (revisão 2 da 021) | `main` | defeito encontrado na implantação do ensaio; a homologação depende da correção |
| 2 | `ops/api-v1-003-homologacao` (este relatório) | PR 1 | elevação 016–020 vinculada, pré-voos, caminho 021 do runner, semeaduras, jornadas |
| 3 | `feat/organizacoes-ativacao` (022) | PR 2 | a 022 muda a lista padrão de elevação do modelo de aplicação e o H02 do runner, que a PR 2 também toca |
| 4 | `docs/api-v1-003-inventario` | `main` | só documentos; independente |

Nada é mesclado por esta missão; nenhum force-push nem exclusão de branch.

## 3. Consolidação de credenciais, recuperação e e-mail (item 2)

Correções aprovadas do #13, conferidas na `main` pelo teste que as demonstra. Todos estão entre os 570 que passaram nesta máquina.

| Correção | Onde está | Teste |
|---|---|---|
| intenção de e-mail na mesma transação do ato | `MailIntents`/`OutboxMailIntents` | `MailOutboxIT.intencaoGravadaComOAtoSobreviveAoReinicioSemRepetirOAto` |
| rollback da intenção quando a operação é recusada | idem | `MailOutboxIT.semIntencaoGravadaOAtoNaoConfirma`; `IdentityIntentFinalChecksIT.trocaDeSenhaComStepUpVencidoDuranteAGravacaoDaIntencaoNaoTroca` |
| verificações temporais depois das esperas relevantes | serviços de identidade (revisão 2) | `IdentityIntentFinalChecksIT.trocaDeFatorComSessaoVencidaDuranteAGravacaoDaIntencaoDesfazTudo` |
| reivindicação de um trabalho por vez | `MailDispatcher` | `MailOutboxIT.doisDespachantesComTransporteLentoNuncaEnviamSemPosse` |
| posse e prazo conferidos depois do lock | `MailDispatcher` (dois comandos) | `MailOutboxIT.reconferenciaDepoisDaEsperaPeloLockNaoEnviaVencidos`, `trabalhoNaoComecaDepoisDePerderAPosse` |
| resultado incerto explícito, sem reenvio | `MailDispatcher` | `MailOutboxIT.recusaEIncertezaSaoTerminaisENaoSaoRepetidas`, `tentativasEsgotadasViramIncerto`, `posseVencidaDepoisDeQuedaViraIncertoSemReenvio` |
| deduplicação enquanto a chave persistida existir | `mail_outbox.event_key` UNIQUE | `MailOutboxIT.mesmaChaveDeEventoGeraUmTrabalho` |
| retenção de metadados terminais por 30 dias | trigger da 021 + limpeza | `MailOutboxIT.retencaoApagaSoTerminaisAntigos` |
| payload apagado ao encerrar | CHECK da 021 (estado terminal ⇔ payload nulo) | `MailOutboxIT.bancoProtegeOrigemTerminalEApagamento` |
| avisos emergenciais no despacho persistente | `EmergencyNotices.recordTransportResult` | `MailOutboxIT.avisoEmergencialRespeitaORetryAfterEDevolveODesfecho` |

O título do #15 ("Feat/identidade 002 recuperacao senha") foi atualizado para refletir o conteúdo final (credenciais, recuperação por e-mail e transporte real), com a descrição apontando para os relatórios de #11 a #13.

## 4. Ambiente `ensaio-api-v1-002` (item 3)

### 4.1 Validação do identificador

| Regra | Onde | `ensaio-api-v1-002` |
|---|---|---|
| `^[a-z0-9-]{3,40}$` | `homologacao.sh criar` | 17 caracteres: aceito |
| prefixo `ensaio-` (descartável) | 016–021, transporte de homologação, lista alternativa, ganchos | atende |
| id na lista de elevação `[a-z0-9-]{3,40}` | `lib/lista_elevacao.sh` | aceito |
| `^ensaio-[a-z0-9-]{1,34}$` | `CaixaHomologacaoMailSender.AMBIENTE` (Platform) | aceito |
| nome do projeto Compose | `nekkora-homologacao-<id>` | aceito |

Nenhuma incompatibilidade, nenhum ajuste necessário.

### 4.2 Base identificada

- **Imagens** (Platform, Portal, job de operação): `HOMOLOG_COMMIT=a39f684`, o último commit que toca `FONTES_IMAGENS`. É o código da API aprovado (head do #13), idêntico ao da `main`; a guarda recusou qualquer alteração não commitada nessas entradas.
- **Ferramentas e lista de elevação:** o candidato desta PR. A criação final rodou no commit local `d1375a3` (`fontes_imagens.txt`, `HEAD=`). Em relação ao head publicado, `ops/homologacao` difere só em `parar`/`subir` no `homologacao.sh`, no `testes/janelas_016_020.sh` (usado depois, no laboratório) e no README; a lista de elevação, os pré-voos, o runner e as semeaduras são os mesmos. As linhas 016–020 são a **proposta** desta missão (§5); a 021 é a revisão 2 da PR 1. Não foi aplicada nenhuma mudança fora delas, e esta dependência está declarada aqui e no PR.

### 4.3 Topologia e dados

- Sub-redes próprias `10.252.98.0/24` (interna, `internal: true`) e `10.252.99.0/24` (borda); porta `127.0.0.1:55761`. O `criar` conferiu ausência de sobreposição com todas as redes Docker existentes.
- Volumes, redes e containers rotulados `nekkora.homologacao=ensaio-api-v1-002`. Os ambientes antigos (`hml03`, `ensaio-016a/b/c`, `ensaio-consol-016`, `ensaio-frontend`) e seus segredos não foram tocados.
- Segredos novos, gerados no `criar`, fora do Git (`~/.nekkora/homologacao/ensaio-api-v1-002`): senhas por identidade, chaves de MFA/HMAC, ACL do Redis, CA do ambiente e CA operacional descartável. Nada copiado de outro ambiente.
- Dados sintéticos (semeaduras declaradas e jornadas), e-mail **simulado** na caixa do ensaio (`HOMOLOG_TRANSPORTE=homologacao`, obrigatório com `HOMOLOG_ATE=021`).
- `compose.021.yml` liga só neste ambiente as chaves de identidade (cadastro, entrada geral, criação de organização, vinculação, recuperação de senha). O transporte real não é configurável por ela.

### 4.4 Tentativas preservadas

| Tentativa | O que aconteceu | Preservado em |
|---|---|---|
| 1 | 016–020 aplicadas com janelas e pré-voos; **a 021 falhou**: `021: papel com acesso a mail_outbox alem da Platform: backup_nekkora`. Migração desfeita por inteiro (sem `success=false`, sem `mail_outbox`), migrador 0\|0\|0, ambiente BLOQUEADO. Defeito real, corrigido na PR 1 (§6) | `saidas/tentativa1-…` e `~/.nekkora/homologacao/tentativa1-…` (renomeados, não apagados); recursos Docker removidos pelo `destruir` |
| 2 | Recriada com a revisão 2 da 021: LIBERADO. A preparação da 016 falhou na semeadura (§7); a preparação não é retomável | `saidas/tentativa2-…` e segredos renomeados |
| final | Recriada do zero com tudo corrigido | `ops/homologacao/evidencias/ensaio-api-v1-002/` (sanitizado) |

O id é exigido pela missão e pela linha de elevação, e o runner recusa reutilizar um id com evidência ou segredos. Por isso as tentativas anteriores foram renomeadas, nunca apagadas.

### 4.5 Resultado da criação final

| Passo | Resultado |
|---|---|
| `criar` (`HOMOLOG_ATE=021`) | 001–015 aplicadas, janelas 011–013 retiradas em 0\|0\|0; **parada antes da 016** conferida (o único bloqueio é "faltam: 016 017 018 019 020 021") |
| `implantar` | pré-voo da 016, 017, 018, 019 e 020: código 0 e `PREFLIGHT_OK`; cada versão na sua janela de SUPERUSER, **retirada conferida em 0\|0\|0** depois de cada uma; 021 sem janela; `flyway validate` OK; **LIBERADO** |
| `concluir-criacao` | senhas dos serviços por verificador SCRAM, volumes conferidos (`nekkora 700`), Platform, Portal e gateway no ar |
| `estado` | migrador recuperado, versões 001–021 com sucesso, conjunto íntegro, LIBERADO |

Janelas registradas: `evidencias/ensaio-api-v1-002/criacao/janelas_de_elevacao.txt` (011–013) e `implantacao/janelas_de_elevacao.txt` (016–020).

### 4.6 Instruções reproduzíveis

```sh
git clone -c core.longpaths=true -c core.autocrlf=false git@github.com:Nekkora-Operation/Nekkora-Platform-API.git
HOMOLOG_ATE=021 HOMOLOG_TRANSPORTE=homologacao HOMOLOG_REDE_INTERNA=10.252.98 HOMOLOG_REDE_BORDA=10.252.99 \
  HOMOLOG_PORTA=55761 ops/homologacao/homologacao.sh criar ensaio-api-v1-002
HOMOLOG_AUTORIZA_ELEVACAO=1 ops/homologacao/homologacao.sh implantar ensaio-api-v1-002
ops/homologacao/homologacao.sh concluir-criacao ensaio-api-v1-002
ops/homologacao/homologacao.sh preparar-016 ensaio-api-v1-002     # operadores, contatos e MFA da 016 (para J9)
ops/homologacao/homologacao.sh jornadas-api ensaio-api-v1-002     # J1-J8, J10 + varredura de segredos
ops/homologacao/homologacao.sh testar-016 ensaio-api-v1-002       # J9 (avisos emergenciais)
ops/homologacao/homologacao.sh parar ensaio-api-v1-002            # para sem remover nada
ops/homologacao/homologacao.sh subir ensaio-api-v1-002            # sobe de novo (servicos so com LIBERADO)
HOMOLOG_CONFIRMA_DESTRUIR=ensaio-api-v1-002 ops/homologacao/homologacao.sh destruir ensaio-api-v1-002
```

- **Reconstruir:** `destruir` e `criar` de novo. O runner recusa id em uso: mova `ops/homologacao/saidas/ensaio-api-v1-002` e `~/.nekkora/homologacao/ensaio-api-v1-002` para outro nome antes (não há exclusão automática de evidência ou segredo).
- **`core.autocrlf=false`:** nesta máquina o Git do sistema tem `core.autocrlf=true`. Com ele, `.txt`, `.patch` e outros arquivos sem regra em `.gitattributes` saem em CRLF, e cinco conferências estáticas do `validar-config` falharam por fim de linha, não por conteúdo (a lista de elevação compara bytes LF; o patch da proposta P3 não aplica em CRLF). Com o clone em `autocrlf=false`, a `main` fica com uma falha, ambiental (§9).

## 5. Elevação 016–020 e pré-voos (item 4)

### 5.1 Necessidade medida

`ops/homologacao/mapa_privilegios.sh` (cluster descartável, sem rede), com e sem os papéis pré-criados, em 2026-10-23, sobre 001–021:

| Versão | Sem elevação | Primeiro erro (sem / com papéis pré-criados) |
|---|---|---|
| 016 | não | `permission denied to create role` / `permission denied to alter role` |
| 017 | não | `permission denied to create role` |
| 018 | não | `permission denied to set parameter "nekkora.fn_organization_id"` |
| 019 | não | `must be able to SET ROLE "nekkora_identity_owner"` |
| 020 | não | `must be able to SET ROLE "nekkora_identity_owner"` |
| 021 | **sim** | — |

Evidência: `evidencias/ensaio-api-v1-002/medicoes/mapa_privilegios*.txt`.

### 5.2 Proposta (mecanismo por hash, vínculo ao ambiente)

`ops/homologacao/implantacao/elevacao.txt` ganha, ao lado de 011–013 (inalteradas), na forma nova da gramática:

```
016 f37d984128e8241474319a39c00fa54baed90519169663757c7f799e61b63732 tipo=superuser ambiente=ensaio-api-v1-002
017 90b8ddbcceac1319e9d4f71932e4af287ab1cdef67feacb6d71cfdc77db1c160 tipo=superuser ambiente=ensaio-api-v1-002
018 1bcfbacbe63d930e75d126c2d92ece5a4c85422ecb68c2e475d0c6e3f67c3418 tipo=superuser ambiente=ensaio-api-v1-002
019 f0154b468931636cd355ecf5d3eae9871743d8d1b4f6431c224be4bd3b776744 tipo=superuser ambiente=ensaio-api-v1-002
020 7266db33ae1bce291b0acf47d22b3ef5c6700116d774ce3d40baa2a14bd086b8 tipo=superuser ambiente=ensaio-api-v1-002
```

Os sha256 são os dos bytes LF versionados (conferidos pelo `validar-config` contra `db/migrations`). A 021 não tem linha.

**Controles reforçados, nenhum enfraquecido:**
- `VINCULO_OBRIGATORIO` passa de `016` para `016 017 018 019 020`, numa única definição em `lib/lista_elevacao.sh` (antes cada script repetia `" 016 "`). Para essas versões, a linha tem de ser `superuser` com `ambiente=<id exato>`, e o pré-voo vem **só** de `implantacao/preflight/<versão>.sql`, nunca da lista alternativa.
- Novo no runner: versão de vínculo obrigatório **sem linha** é recusada antes de chamar o Flyway, sem efeito (antes ela seria executada sem privilégio e falharia dentro do Flyway).
- O `criar` com `HOMOLOG_ATE=021` recusa, antes de criar qualquer recurso, se faltar qualquer uma das cinco linhas vinculadas ao id exato.
- `validar-config` registra a nova lista como conteúdo aprovado da proposta (sha256 `374c6620…`), mantendo as recusas de adulteração, CRLF, linha sem vínculo e vínculo a outro id. As bases das adulterações passam a ser a lista 011–013 fixada em `bf977d7`, e não `HEAD`.

### 5.3 Pré-voos 017–020

Mesmo formato do da 016 (somente leitura, uma linha `item|OK|FALHA|detalhe` por conferência, `PREFLIGHT_OK` só se todas passarem). Comuns: PostgreSQL 16; histórico exatamente 001–anterior com sucesso, sem a versão nem posteriores, sem falha; migrador sem atributo nem ACL de parâmetro; migrador dono do banco.

| Versão | Específicas |
|---|---|
| 017 | `nekkora_identity_owner` ausente ou compatível e sem participação; tabelas de identidade ausentes; `user_sessions.identity_session_id` ausente; schema `ops` presente |
| 018 | os quatro corpos que a 018 substitui com os **mesmos** SHA-256 fixados na §1 da 018 (a divergência recusa antes de abrir a janela, não dentro dela); `SELECT (created_at)` ainda não concedido; donos sem participação |
| 019 | marca da 018; `identity_recovery_codes_regenerate` ausente; CHECK do evento igual ao da 017; dono sem participação |
| 020 | marca da 019; `identity_password_reset` ausente; CHECKs do propósito (017) e do evento (019); dono sem participação |

### 5.4 Provas

**Matriz contra base real** (`testes/test_preflight_016_020.sh`, modelo `aplicar_como_migrador.sh`, histórico do Flyway simulado só nas colunas lidas, declarado no script): em cada estágio de 001–015 a 001–020, **só** o pré-voo da versão seguinte termina em `PREFLIGHT_OK`; os demais recusam; em 001–020, todos recusam. Controles: C1 (SUPERUSER residual do migrador) recusa no item do migrador; C2 (corpo de `ops_operator_disable` alterado por fora) recusa em `quatro_corpos` e volta a passar depois de restaurado byte a byte. O catálogo é o mesmo antes e depois de cada rodada.

**Janelas em sucesso e em falha, pelo runner real** (`testes/janelas_016_020.sh`, laboratório `ensaio-api-v1-002-janelas` com lista alternativa vinculada só a ele e os pré-voos oficiais):

| Versão | Falha dentro da janela | Recusa pelo pré-voo | Sucesso |
|---|---|---|---|
| 016 | `cannot execute CREATE ROLE in a read-only transaction`; código 2; janela `inicio`/`fim … revogado … [falha]`; 0\|0\|0; fora do histórico; sem `success=false` | — | janela concluída; `016:true`; 0\|0\|0 |
| 017 | `CREATE ROLE`, idem | — | idem |
| 018 | `CREATE TABLE AS`, idem | — | idem |
| 019 | `ALTER TABLE`, idem | função criada por fora: `identity_recovery_codes_regenerate_ausente|FALHA`; código 2; **nenhuma** linha de janela da 019 | idem |
| 020 | `ALTER TABLE`, idem | — | idem |
| 021 | — | — | sem janela; LIBERADO |

A falha foi provocada sem tocar no arquivo nem no runner: `ALTER ROLE migrador_nekkora IN DATABASE nekkora SET default_transaction_read_only = on`, que só afeta as conexões do migrador. O pré-voo e a revogação rodam como `admin_cluster`. A configuração foi removida depois de cada falha. Resultado: 0 linhas fora do esperado (`evidencias/ensaio-api-v1-002/janelas-016-020/resultado.txt`).

### 5.5 Origem da diferença da 016

| | Revisão 4 | Revisão 5 (atual) |
|---|---|---|
| sha256 (LF) | `25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61` | `f37d984128e8241474319a39c00fa54baed90519169663757c7f799e61b63732` |
| commit | `03bc72c` (2026-09-28, "revisao 4 e inventario de locks") | `4908544` (2026-10-02, "revisao 5, bootstrap do migrador") |
| onde foi aplicada | só no `ensaio-016a` e nas bases efêmeras das suítes | `ensaio-016b`, `ensaio-consol-016`, `ensaio-frontend`, suítes e CI |

**O que mudou (conteúdo, `git diff 03bc72c 4908544`, 118 linhas):**
1. Nota de cabeçalho "Revisão 5 (proposta P4-R1)": modelo suportado = migrador declarado `migrador_nekkora`, dono do banco.
2. §19 reescrita: a conferência "funções internas: nenhum papel além do dono" foi substituída pela conferência de identidade de implantação, donos por categoria, ACL explícita exata e EXECUTE efetivo, válida também depois da retirada da elevação.

Nenhuma função, tabela ou privilégio fora da §19 mudou.

**Evidências de aprovação:**
- `2026-10-01-mfa-emergencia-p4r1-b5-b12.md` §2.5: recomendação de nova revisão (5), não uma 017; 25bef818 registrado como revisão 4.
- `2026-10-01-mfa-emergencia-p4r1-revisao2.md`: proposta com `f37d9841…3732` em cópia fora do repositório.
- `2026-10-02-mfa-emergencia-consol1-preparacao.md` (linha da 016 rev5 vinculada a `ensaio-consol-016`) e `…-consol1-final.md` (016 `f37d9841…`).
- Homologação da revisão 5: `r2b0`, `r2b1` e `r2b-proposta` (`ensaio-016b`).

A mensagem do commit `4908544` ("revisão 5 aprovada") está **amparada** pelas evidências acima. A lista operacional nunca teve linha da 016: o valor antigo associado à revisão 4 viveu só nas listas de laboratório e no anexo de P3 (`elevacao-016-proposta.patch`, `016 25bef818… ensaio-016a`).

## 6. Defeito da 021 (PR 1)

Reproduzido no ambiente (tentativa 1) e na suíte (`db/tests/correio-021`, caso C0). A invariante 1 da §5 usava `has_table_privilege`, que conta o SELECT herdado de `pg_read_all_data`, e a topologia de implantação tem `backup_nekkora` nesse papel por desenho (backup lógico, IMPLANTACAO.md §1). A revisão 2 confere as concessões (ACL da tabela e das colunas, inclusive PUBLIC) e a participação no dono e na Platform. Contraprovas (C2) mostram que ela continua recusando todo acesso real além da Platform. A 021 nunca foi aplicada em base persistente; sha256 da revisão 2: `bf85b95b…`. Detalhes no PR 1.

## 7. Semeaduras no contexto exigido

Desde a 017, `user_credentials`, `user_mfa_factors`, `user_mfa_recovery_codes`, `auth_challenges`, `password_reset_tokens` e `email_verification_tokens` passam pela trigger `refuse_local_entry_for_linked_account`, que confere `nekkora.organization_id` (`identity_check_context`). Quatro semeaduras gravavam credencial sem esse contexto:

| Semeadura | Uso | Correção |
|---|---|---|
| `sql/fixture_016.sql` | `preparar-016` | `set_config('nekkora.organization_id', :'org', true)` na transação |
| `sql/fixture.sql` | H05 (`testar`) | contexto da organização A e depois da B, por credencial |
| `sql/semear_frontend.sql` | ensaio-frontend | contexto da organização da conta antes da credencial |
| `sql/semear_f6a.sql` | F6a | idem |

O caminho é o da aplicação: contexto local à transação. Nenhuma proteção foi desligada. Provas: a falha original (`contexto organizacional ausente ou divergente`, tentativa 2); a fixture corrigida passa em transação desfeita contra o ambiente; e `preparar-016` passa no ambiente final (organização, operadores, contatos e MFA dos dois ORG_ADMIN pelo gateway). As semeaduras do frontend e da F6a não foram executadas nesta missão: exigem a aplicação web, fora do escopo. Os testes de banco delas continuam em 001–016.

Nova semeadura declarada `sql/semear_api_v1.sql`: organização legada com duas contas antigas, para vinculação e redução de acesso. A API v1 não tem rota de criação ou convite de conta de organização. Usa o mesmo contexto e `provision_first_org_admin`, sem fator semeado (o primeiro fator é cadastrado pela API).

## 8. Jornadas integradas pela API (item 7)

`homologacao.sh jornadas-api` (`testes/jornadas_api.py`):
- fala só com o gateway, com TLS conferido pela CA e pelo nome da origem;
- usa cookies `__Host-` como um navegador e o CSRF de cada contexto;
- lê os tokens de e-mail da caixa simulada, sem gravá-los na evidência.

Cada caso é marcado como **API** (ação e resultado pela API), **CAIXA** (mensagem simulada), **BANCO** (só leitura) ou **PREP** (estado preparado no banco: o que a API ainda não cria ou só a operação altera).

**Resultado no ambiente final: 96 casos, 0 falhas.** Varredura: 238 valores vistos (senhas, tokens, cookies, CSRF, segredos TOTP, códigos de recuperação, e-mails), **0 achados** em 222 linhas de log da Platform, do Portal e do gateway e na evidência; controle positivo OK (id de organização presente no log do gateway).

| Jornada | Resultado exigido | Demonstrado | Tipo |
|---|---|---|---|
| J1 Cadastro e confirmação | identidade ativada pela titular, sem sessão automática | pedido 202; confirmação pela caixa; 204 sem cookie de sessão; `/me/session` 401; reuso do token 400; identidade ACTIVE e verificada | API, CAIXA, BANCO |
| J2 Login e MFA | sessão com as provas corretas | senha errada 401; `mfa_enrollment_required`; fator errado 401; certo emite sessão com 10 códigos; logout; nova entrada com TOTP; passo repetido recusado | API |
| J3 Recuperação de MFA | sessão restrita liberada só após troca válida | código → sessão restrita, demais revogadas; restrita sem organizações nem step-up; fator antigo recusado; novo levanta a restrição; códigos antigos invalidados; avisos | API, CAIXA |
| J4 Credenciais globais | troca de senha e regeneração com autorização e efeitos | sem step-up 403; senha atual errada 403; troca revoga a outra sessão e mantém a atual; senha antiga 401; regeneração invalida o conjunto anterior; MFA intacto; avisos | API, CAIXA, BANCO |
| J5 Recuperação por e-mail | senha alterada sem remover MFA | 202 uniforme (desconhecido e titular); token pela caixa; 204 sem sessão; token de uso único; todas as sessões revogadas; nova entrada pede o **fator** | API, CAIXA |
| J6 Organizações | criação, listagem, troca e capacidade inicial | lista vazia + `canCreateOrganization`; sem step-up 403; criação 201; ORG_ADMIN na lista; troca → sessão; `/o/{org}/me`; organização alheia `sem_acesso`; limites 1–3 FINITE 0 e 4–5 UNLIMITED; conta sem credencial local | API, BANCO |
| J7 Vinculação | provas dos dois lados; entrada local encerrada | organização legada; primeiro acesso local pela API; identidade com o mesmo e-mail; prova errada 401; etapa 2 sem step-up 403 (o cookie do desafio expira em qualquer desfecho; a etapa 1 é refeita); vinculada; sessão local antiga revogada; login local 401; troca pela sessão vinculada; aviso | PREP, API, CAIXA |
| J8 Redução de acesso | suspensão, retirada de vínculo e revogação impedem acesso | **suspensão e reativação pela API** (sessão do membro cai; login 401; volta a entrar); retirada do vínculo (PREP) derruba a sessão; logout da identidade derruba a sessão derivada; organização suspensa (PREP) cai, sai da lista, troca `sem_acesso`, identidade continua | API, PREP |
| J9 Avisos emergenciais | estado da tentativa e transporte coerentes | `testar-016`: B5, B6, B7, B8a e B12 OK; **B10 PARCIAL** (RECUSADO, ENFILEIRADO, INCERTO, EXCEÇÃO e ACEITO simulados com as categorias esperadas; varredura de travadas sem gatilho no ambiente); **B11 PARCIAL** (restauração em segundo cluster pendente, P4); B8b não executado (opcional) | API, CAIXA |
| J10 Falhas | recusas, rollback e incertos sem repetição | senha no pedido 400 sem efeito; sem CSRF 403; token desconhecido 400; **mesmo token de recuperação em duas confirmações paralelas: exatamente uma troca (204) e uma recusa (400), um único evento 17**; vale a senha da vencedora | API, BANCO |

**Testes que preparam estado no banco × jornadas integrais:**
- São integrais pela API: J1 a J6, a vinculação em J7, a suspensão e reativação em J8 e J10.
- São estados preparados (PREP): a organização legada e suas contas, a retirada do vínculo e a suspensão da organização. A API v1 não tem essas rotas.
- Os testes Java existentes (`IdentityOrganizationJourneysIT` e outros) seguem a mesma divisão, já declarada no relatório do #14.

**Não executado:** o `testar` H01–H09 do desenho 001–015. O H03, por exemplo, compara com a instalação de referência 001–015. A cobertura equivalente para 016–021 aqui são as invariantes das suítes, a liberação e a varredura das jornadas.

## 9. Testes e limitações

- **Validação estática** (`validar-config`) no candidato: todas as conferências novas passam. Resta **1 falha, preexistente e ambiental**: `testes/test_elevacao_historica.sh` reconstrói bytes de ambientes da máquina antiga a partir de diretórios de laboratório (`lab-p4-20260929T192708Z`, `lab-r2b1c-20261001T233433Z`) que não existem nesta máquina. A mesma falha ocorre na `main` com LF.
- **Regressão Maven completa:** a PR 2 não muda código Java. Vale a regressão do candidato da PR 1 (mesmo conteúdo Java e de banco), registrada no PR.
- **E-mail real:** fora desta PR. O ambiente usa só o transporte simulado (relatório de preparação).
- **Web:** fora da missão. Os caminhos de página continuam como contrato (`/cadastro/confirmar`, `/recuperar-senha`).
