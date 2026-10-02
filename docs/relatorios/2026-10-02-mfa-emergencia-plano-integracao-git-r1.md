# Integração Git da frente MFA 014/016: plano revisado (r1), depois da atualização das referências

**ESTADO: PLANO PARA DECISÃO.**
- Houve só a escrita administrativa do `git fetch origin` no `.git` local (refs remotas e `FETCH_HEAD`). O fetch **não** alterou o HEAD, o índice nem os arquivos de trabalho.
- Sem stage, commit, merge, reset ou push.
- A execução dos commits, o push e o PR são decisões posteriores sobre este plano concreto.

**Base:** aprovação técnica da MFA-CONSOL-1 (2026-10-02), com a indicação de seguir pela atualização das referências e a revisão do plano C1–C4. O plano preliminar está em `2026-10-02-mfa-emergencia-consol1-preparacao.md` §5.

**Evidências:** `ops/homologacao/saidas/consol1-20261002T143413Z/G_git/`.

## 1. Estado remoto depois do `fetch` (2026-10-02T16:16Z)

`git fetch origin` terminou com código 0, sem `--prune` e sem merge. Escreveu só refs remotas e `FETCH_HEAD`; não alterou HEAD, índice nem arquivos de trabalho.
- Antes e depois: `fetch_antes.txt`, `fetch_depois.txt`, `fetch_saida.txt`.
- O fetch anterior era de 2026-09-28T20:54:41Z.

| Ref | Valor depois do fetch | Mudou? |
|---|---|---|
| `origin/main` | `dd74a8f6e9a7b487e5698c11ca026e2c4666177e` (2026-09-28) | não |
| `origin/HEAD` | → `origin/main` | não |
| `origin/feat/api-v1` | `d608a96eea7e5eef5cc6ea6deee8d0c05b79e299` (2026-09-26) | não |
| ramo remoto da frente MFA | não existe | — |

**Consequências:**
- O HEAD local (`feat/mfa-emergencia`, `dd74a8f`) **é** o `origin/main` atual: 0 commits de diferença nos dois sentidos. Não há rebase nem conflito a resolver hoje.
- **A 016 publicada é a rev4** (`25bef818…`). A rev5 aprovada (`f37d9841…`) só existe na árvore de trabalho.
- O original continua com stage 0 e o mesmo `git status` (129 entradas sem `-uall`).

## 2. Lista de integração revisada: 125 arquivos (`G_git/plano_commits_r1.tsv`, mais este documento)

**Composição:**
- os 121 do plano preliminar;
- os 3 relatórios desta missão: `consol1-preparacao`, `consol1-e1-stop-e2-portao9c` e `consol1-final`;
- **este plano** (`2026-10-02-mfa-emergencia-plano-integracao-git-r1.md`), no C4, com o blob conferido no ato do commit, porque o arquivo ainda pode receber adendo.

**Conferência contra o `git status` atual:** nenhuma entrada relevante fora da lista e nenhuma da lista fora do status. As exclusões são as mesmas: `api/e2e/email/node_modules/`, saídas de `db/tests/*/saidas` e `ops/mfa-emergencia/saidas`, `db/tests/portal-8-2/` (outra missão) e `__pycache__`.

**Por arquivo:** sha256 dos bytes, sha256 LF e **blob Git** (`hash-object --path`, sem `-w`).

**Vínculo com o que foi validado:**
- **99 de 125** fazem parte do snapshot executado no `ensaio-consol-016` (manifesto r2) e têm **o mesmo blob** que foi validado no ensaio: 99/99 IGUAL.
- Isso inclui o `test_elevacao_historica.sh` corrigido no portão 9c (`364d7b69…`, blob `65eaf683…`).
- Os **26** restantes são só documentos: 22 relatórios posteriores ao S1″, já com os adendos, os 3 desta missão e este plano.

| Commit | Arquivos | No snapshot validado |
|---|---|---|
| C1 | 7 | 7 |
| C2 | 8 | 8 |
| C3 | 71 | 71 |
| C4 | 39 | 13 (os documentos que o S1″ já tinha); 26 são novos documentos |

## 3. Commits propostos (ordem C1 → C2 → C3 → C4, no ramo `feat/mfa-emergencia`, a partir de `dd74a8f`)

| # | Mensagem proposta | Arquivos | Depende de | Validação proposta no ato |
|---|---|---|---|---|
| C1 | `feat(mfa): migration 016 revisao 5, bootstrap do migrador e suporte de teste compartilhado` | 016 rev5, `db/bootstrap/aplicar_como_migrador.sh`, `docker-compose.modelo.yml`, `db/tests/mfa-emergencia-016/run.sh`, `RealDatabase.java`, `OpsBanco.java`, `matriz-016.md` (contrato da rev5) | — | compilação dos módulos (`mvnw -q -DskipTests package`) num clone do commit |
| C2 | `feat(platform): transporte de homologacao da caixa e configuracao do envio` | `PlatformConfig`, `EmergencyNotices`, `MailSender`, `application.yml`, `CaixaHomologacaoMailSender` (com a aquisição da trava da WT1); testes sem dependência do runner: `AquisicaoTravaTest`, `CaixaHomologacaoConfigIT`, `CaixaLinuxSonda` | C1 | compilação e testes do módulo platform que não usam o runner |
| C3 | `feat(homologacao): runner, preflights, ferramentas e ITs da homologacao da 016` | `ops/homologacao/**` (71 arquivos, incluindo `validar_config.sh` e o teste 9c), `api/e2e/Dockerfile.operacao`, ITs do `nekkora-ops` (p1c, docker-shim) e da platform (`CaixaHomologacaoMailSenderIT`, `ClienteH016IT`), `docs/relatorios/2026-09-29-p3it-anexos/elevacao-016-proposta.patch` (dependência do `validar-config`) | C1, C2 | **`validar-config` 184/0 por nome**; Maven: `fontes_maven.py verificar` da regressão do clone (`…/E2_ensaio/05_maven/clone_verify1`, fontes `5c522396…`) contra um clone do commit C3 com `autocrlf=false`. Se der PERMITIDO, reaproveita os 266/0/0; senão, `regressao_maven.sh` nesse clone |
| C4 | `docs(mfa): relatorios da homologacao da 016 (P1-P4, R2b, H03, H02, Maven, transporte, consolidacao)` | 39 documentos | — | revisão; `validar-config` não depende deles (o anexo está no C3) |

**Por que a árvore do C3 equivale à validada:**
- ao fim do C3, o código e as ferramentas são exatamente os 99 blobs do S1 r2, menos 13 documentos que só entram no C4;
- o conjunto de entradas do Maven não inclui `docs/`;
- por isso o `verificar` em um clone do C3 com `autocrlf=false` deve dar IDENTICA. A previsão será conferida, não presumida.

**Procedimento de cada commit no original** (quando autorizado):
1. `git add --pathspec-from-file=<lista do commit>`, nunca `-A` nem `.`;
2. conferir `git diff --cached --name-only` = a lista;
3. conferir cada blob em stage (`git ls-files -s`) = o `blob_git` do plano. Com `core.autocrlf=true` no original, os 10 arquivos CRLF entram normalizados para LF, e o blob esperado já é o normalizado;
4. `git commit`, com as linhas de atribuição de coautoria exigidas para commits feitos pelo executor;
5. registrar o hash de cada commit **depois** de criado.

**Ativação separada:** `ops/homologacao/implantacao/elevacao.txt` **não** está em nenhum commit e continua 011–013. As listas de lab (`elevacao_lab_*`) existem só nos clones de laboratório e **não** entram.

## 4. Riscos e tratamento

| Risco | Tratamento |
|---|---|
| `api/e2e/email/node_modules/` (2.240 arquivos) não está no `.gitignore` | adicionar só pela lista; conferir o stage. O ajuste do `.gitignore` pertence à frente de e-mail E2E e fica fora daqui |
| Conversão CRLF no original (`autocrlf=true`) | conferência de blob por arquivo (passo 3) |
| Commits intermediários sem a regressão completa | C1 e C2 com compilação e testes isolados; a regressão completa vale na árvore do C3, a que foi exercitada no ensaio |
| Divergência entre a publicação e o momento do `fetch` | novo `fetch` imediatamente antes de qualquer push, conferindo que `origin/main` continua `dd74a8f` |
| Relatórios citam evidências fora do Git (`ops/homologacao/saidas/`, `../relatorios/*.zip`) | é intencional (segredos e volume). Os pacotes ficam referenciados por SHA-256; o armazenamento das evidências é uma decisão à parte |
| Linha "reutilizada" do `executar.sh` com texto antigo ("58 testes… validacao-api-v1") e o auxiliar da imagem com controle não confiável | dívidas registradas no adendo do relatório final; não bloqueiam a integração. A correção do texto do `executar.sh` mudaria um arquivo validado e, por isso, fica para uma missão própria |

## 5. Decisões pedidas

| Código | Decisão | Recomendação |
|---|---|---|
| G1 | Executar os commits C1–C4 no ramo local `feat/mfa-emergencia` do original, pelo procedimento do §3, com as validações por commit | autorizar quando o plano for aceito |
| G2 | Push do ramo e abertura de PR para `main` (com novo `fetch` antes) | **decisão separada**, depois de revisar os 4 commits |
| G3 | Merge em `main` / publicação | **decisão separada**, depois da revisão do PR |

**Continuam fora:**
- a ativação operacional da 014/016 (lista operacional);
- 5b e P5;
- B4 completo, B9, a varredura do B10 e a restauração do B11;
- a rotação da política histórica;
- produção.

**Próximo trabalho funcional**, depois da integração: inventário das rotas da v1.
