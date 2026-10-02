# P3 — Decisão D-ELEV: elevação temporária da 016 na homologação isolada

**ESTADO: PROPOSTA — AGUARDANDO AUTORIZAÇÃO DA RESPONSÁVEL.** Revisão 3 (2026-09-29). B+C **não** aprovados para aplicação.

Esta proposta não autoriza elevação, criação de ambiente, aplicação de patch, ensaio (P4) nem commit/push. Os campos da
§12 ficam em branco até a manifestação expressa da responsável. P4 não começa antes disso.

Preparação documental e estática: nenhum container, banco ou implantação foi usado.

| Revisão | Pacote | Mudança |
|---|---|---|
| 1 | `homologacao-016-p3-20260929T111126Z.zip` (SHA-256 `38be8f33…2038`) | Proposta inicial; patches A e B |
| 2 | `homologacao-016-p3-20260929T112828Z.zip` (SHA-256 `f736b98a…4555`) | Atende à revisão técnica: (1) **parada verificável** antes da janela da 016 e **pré-voo automático** no runner (patch C, §4); (2) **B2 e B4 no mesmo ambiente**: interromper a primeira tentativa da 016, recuperar e concluir, com a conclusão como evidência de B2 (§5); limite de janelas ajustado (§6); recomendação técnica B + C e retirada da linha ao fim de P4 (§9) |
| 3 | este | Atende à revisão da revisão 2: (1) `ensaio_pausa` de C quebrava com `set -u` e a variável ausente, afetando também a implantação normal. Agora confere `${HOMOLOG_ENSAIO_PAUSA:-}` antes de expandir. (2) O pré-voo passa a exigir **código 0 da consulta E** `PREFLIGHT_OK`. (3) **B4 marcado como PARCIAL**: a interrupção durante o SQL da 016 e a reconciliação de resultado desconhecido ficam pendentes para cenário separado. (4) Contagem corrigida: ao fim da J2, **quatro janelas concluídas e uma interrompida**. Verificações estáticas afetadas refeitas (§10) |

## 1. Referências

| Item | Valor |
|---|---|
| Medição B1 (P2, **aprovada como medição**) | `homologacao-016-p2-20260929T042812Z.zip`, SHA-256 `4452fc5545e0adc4c57d227305511ff22fe020003d4cf70956c5a4d9461f02cc`, manifesto 301/301 |
| P1c (aprovado) | `homologacao-016-p1c-20260929T034607Z.zip`, SHA-256 `302104b7449d1d49fe962abd5a2f98bb5a656ac461eed2cf90c589cc1348e04b` |
| P3 revisão 1 (revisada) | `homologacao-016-p3-20260929T111126Z.zip`, SHA-256 `38be8f33ed6d1ec729da1f4f8e5e7d8ccfce226a25e0afe2ee3b525fb9882038`, manifesto 80/80 |
| P3 revisão 2 (revisada) | `homologacao-016-p3-20260929T112828Z.zip`, SHA-256 `f736b98aa60dd8f7e5bd48cc0091c3fe6032a7c44d85f21fb2d44f8ac21e4555`, manifesto 85/85 |
| Desenho / plano | P1a desenho v2.3; plano de homologação da 016 v2.1 (B2–B4, D-ELEV) |
| Baseline Git | HEAD `dd74a8f6e9a7b487e5698c11ca026e2c4666177e`, branch `feat/mfa-emergencia`; P1b/P1c/P2/P3 sem commit |
| Migration | `db/migrations/016_recuperacao_emergencial_mfa.sql`, SHA-256 `25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61` (bytes LF; inalterada) |
| Lista operacional | `ops/homologacao/implantacao/elevacao.txt`, **inalterada** (cópia de trabalho `eb98911a…0e6d`, CRLF por `core.autocrlf`; blob Git `59f3177`, LF) |

**Fundamento (P2, medido em PostgreSQL 16.15 via psql):** com os bytes originais, a identidade normal é recusada (M1).
Pré-criar os papéis não basta (M2). Nada abaixo de SUPERUSER passa das linhas 61–65, 73 e 3049–3064 sem violar o
invariante da seção 19. Com SUPERUSER temporário, a 016 conclui: catálogo 37/21/9 e invariantes OK (M4). A retirada
volta a `0|0|0` (M5). P2 **não** comprovou o caminho pelo Flyway.

**Notação `0|0|0`** (`estado_migrador`, `lib/estado.sh`): são três contagens, e não três atributos. (1) soma de
`rolsuper + rolcreaterole + rolcreatedb + rolbypassrls + rolreplication` do migrador; (2) número de participações em
`pg_auth_members` em que o migrador é membro ou papel concedido; (3) número de sessões do migrador em
`pg_stat_activity`.

## 2. Decisão proposta

> Autorizo janelas temporárias de `SUPERUSER` para a identidade `migrador_nekkora` **do cluster do ambiente
> `ensaio-016a`** (projeto `nekkora-homologacao-ensaio-016a`, novo e descartável), restritas à migration **016**
> (`db/migrations/016_recuperacao_emergencial_mfa.sql`, SHA-256
> `25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61`), aplicadas pelo runner de homologação
> (`homologacao.sh implantar` → `implantacao/implantar.sh`, Flyway), **no máximo duas**, na ordem da §5 (a primeira
> interrompida de propósito, antes do Flyway, na parte de B4 coberta aqui; a segunda conclui a 016 e é a evidência de
> B2). A linha da 016 é acrescentada a
> `implantacao/elevacao.txt`, vinculada ao id `ensaio-016a`, **preservando byte a byte** 011, 012 e 013, junto com as
> alterações do runner da §4 (patches B e C), depois de aplicadas e testadas conforme a §11. A autorização **não** se
> estende a produção, `hml03`, `nekkora-postgres`, `nk016-smoke`, nem a qualquer outro cluster, projeto ou id,
> inclusive outros `ensaio-*`.

| Campo | Condição |
|---|---|
| Ambiente | Exclusivamente `ensaio-016a`, projeto `nekkora-homologacao-ensaio-016a`, criado novo por `homologacao.sh criar ensaio-016a` com `HOMOLOG_ATE=016` |
| Identidade | `migrador_nekkora` do cluster desse ambiente |
| Migration | Versão `016`, arquivo e SHA-256 acima |
| Privilégio | `SUPERUSER` temporário, só durante cada janela da 016 (pré-voo OK → elevar → `flyway migrate -target=016` → retirar e conferir) |
| Autorizações existentes | 011 `38a50984…87ef9`, 012 `d3929cc7…321f4`, 013 `c6192bff…4c14d9`: preservadas integralmente (T2) |
| Janelas no ensaio | 011, 012 e 013 no `criar` (que **para** depois da 015). 016: **no máximo 2**. **J1** interrompida (B4 parcial) e **J2** de conclusão (B2), cada uma com retirada e `0\|0\|0`. Total ao fim da J2: **quatro janelas concluídas** (011, 012, 013 e 016/J2) **e uma interrompida** (016/J1) |
| Janela além de J2 | **Não abrangida.** Qualquer outra (J2 falhou, reconciliação não "não aplicada", interrupção fora do previsto) exige nova manifestação |
| Fora do alvo | Produção, `hml03`, `nekkora-postgres`, `nk016-smoke`, qualquer outro cluster/projeto/id |
| Validação operacional | B2–B4 do plano, em P4, pelo runner/Flyway |
| Vigência | Da autorização até o encerramento de P4. Ao fim, a linha da 016 é retirada de `elevacao.txt` por alteração revisada (recomendação técnica, D-2) |

## 3. O que o código garante hoje e com os patches propostos

Revisão estática de `homologacao.sh` (versão aprovada em P1c), `implantacao/implantar.sh` e `lib/estado.sh` (iguais a
HEAD).

| Garantia | Hoje | Com B | Com B + C |
|---|---|---|---|
| Elevação só para versão listada com o sha256 exato (`implantar.sh:133-137`) | Sim | Sim | Sim |
| 016 só no id `ensaio-016a` | Não (só `ensaio-*`, `homologacao.sh:88`) | **Sim** (terceira coluna; recusa antes de conceder) | Sim |
| Parada verificável depois de 001–015, antes da janela da 016 | Não (`criar` aplica 001–016 de uma vez) | Não | **Sim** (`criar` para em 015 e confere `faltam: 016`) |
| Pré-condições da 016 conferidas **no instante** anterior à elevação, sob a trava | Não | Não | **Sim** (`preflight/016.sql`; sem código 0 **e** `PREFLIGHT_OK` → recusa antes de conceder) |
| Retomada não reaplica 016 efetivada sem histórico (F8) | Não | Não | **Sim** (o pré-voo exige catálogo e histórico de "não aplicada") |
| Criação concluída depois da 016 (senhas, volumes, liberação, serviços) | Não se a 016 falhar no `criar` | Não | **Sim** (`concluir-criacao`) |
| Pausa de ensaio numa versão específica (B4) | Não (a pausa pega a 1ª versão elevada, a 011) | Não | **Sim** (`antes-do-flyway@016`) |
| `HOMOLOG_AUTORIZA_ELEVACAO` no `criar` | Definido pelo próprio `criar` | Idem | Idem. A janela da 016 só ocorre em `implantar`, onde o operador dá a variável |
| `HOMOLOG_ELEVACAO_ARQ` restrito a ensaios (F3) | Não | Não | Não. Controle operacional: `implantacao.txt` registra a lista usada |
| Janela = uma versão; retirada e `0\|0\|0` a cada janela e ao fim; exclusão por trava; sem repair/baseline/clean | Sim | Sim | Sim |

## 4. Parada verificável e pré-voo automático (atende ao ajuste 1)

**Parada verificável (patch C, `homologacao.sh criar`).** Com `HOMOLOG_ATE=016`, o `criar` executa a implantação com
`HOMOLOG_IMPLANTAR_ATE=015`: aplica 001–015 (janelas 011–013), **não** toca a 016 e termina. O `flyway validate` final
ignora só a pendente (`-ignoreMigrationPatterns='*:pending'`). Em seguida:
- grava `saidas/ensaio-016a/parada_antes_016.txt` (`confere_liberacao`);
- **exige** `migrador recuperado` (sem trava local, trava no banco livre, `0|0|0`) e `faltam: 016;` (único motivo de
  bloqueio). Senão, falha;
- sai com código 0 **sem** senhas dos serviços, volumes e serviços, e imprime os próximos passos.

Estado verificável na parada: `homologacao.sh estado ensaio-016a` = BLOQUEADO, só por `faltam: 016`.

**Pré-voo automático (patch C, `implantar.sh`).** Para versão cuja linha na lista tem **terceira coluna** (a 016 com
B), o runner, **já com a trava**, depois do sha256 e do `0|0|0` e **antes** de conceder:
- exige `implantacao/preflight/016.sql`; sem ele, recusa;
- executa-o (somente leitura, como `admin_cluster`) e grava `preflight_016.txt` na evidência da execução;
- só eleva com as **duas** condições: a consulta termina com **código 0** e a **última linha** é `PREFLIGHT_OK`.
  Código diferente de zero recusa mesmo que `PREFLIGHT_OK` tenha sido impresso (falha fechada). O código e a última
  linha constam da mensagem de recusa.

Conferências do `preflight/016.sql`, cada uma impressa como `item|OK|…` ou `item|FALHA|…`:

| # | Conferência |
|---|---|
| 1 | versão maior do servidor = 16 (`server_version_num` 160000–169999; versão exata impressa) |
| 2 | `sslinfo` disponível, `trusted=false`, `superuser=true`, **não** instalada |
| 3 | schema `ops` inexistente |
| 4 | `mfa_events.ops_request_id` inexistente |
| 5 | histórico do Flyway: 001–015 com sucesso, **nenhuma** linha da 016, **nenhuma** `success=false` |
| 6 | migrador sem nenhum dos 5 atributos e sem ACL de parâmetro (`pg_parameter_acl`) |
| 7 | papéis `nekkora_ops_*` ausentes, ou com os atributos da 016 e sem nenhuma participação (P2/M3g) |

As conferências 3–5 são o critério de **"016 não aplicada"** da reconciliação (§6). Assim, **toda** janela da 016,
inclusive a retomada, só abre se o estado for comprovadamente "não aplicada".

**Continuam manuais** (registradas em P4 antes do `criar`): alvo inequívoco e inventário (B12); SHA-256 do
`elevacao.txt` revisado; `HOMOLOG_ELEVACAO_ARQ` **não** definido; imagem e id do PostgreSQL. O `criar` já recusa id
em uso, recursos rotulados existentes e sobreposição de sub-rede.

## 5. Ordem de B2, B3 e B4 no mesmo ambiente (atende ao ajuste 2)

| Passo | Comando / ação | Resultado exigido | Evidência |
|---|---|---|---|
| 0 | Pré-requisitos manuais da §4; inventário antes | — | `inventario_antes.txt`, `principal_antes.txt` |
| 1 | `HOMOLOG_ATE=016 homologacao.sh criar ensaio-016a` | 001–015; janelas 011–013 concluídas com `0\|0\|0`; **parada** | `saidas/ensaio-016a/{implantacao.txt,janelas_de_elevacao.txt,parada_antes_016.txt}` |
| 2 (B3) | Recusas **na parada**, sem abrir janela: (a) `homologacao.sh implantar ensaio-016a` **sem** `HOMOLOG_AUTORIZA_ELEVACAO` → código 2; (b) lista temporária com sha256 errado para a 016 (`HOMOLOG_ELEVACAO_ARQ`, só neste passo) → código 2; (c) lista temporária com a 016 vinculada a **outro** id → código 2 | Recusa **antes** de conceder; `janelas_de_elevacao.txt` da execução vazio; histórico inalterado; `0\|0\|0` | `implantacao-*/implantacao.txt` de cada recusa |
| 3 (B4 **parcial**, J1) | Em segundo plano: `HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_ENSAIO_PAUSA="antes-do-flyway@016:<arq>" homologacao.sh implantar ensaio-016a`. Com `<arq>.chegou`: registrar `estado_migrador` (esperado `1\|0\|0`); **`kill -9`** no PID dono da trava local (como I4) e `docker kill` de container Flyway do projeto, se houver | pré-voo com código 0 e `PREFLIGHT_OK` (`preflight_016.txt`); `inicio versao=016` sem `fim` | `implantacao-*/{preflight_016.txt,janelas_de_elevacao.txt}` |
| 4 | `homologacao.sh estado ensaio-016a`; `testar-016` (deve recusar) | BLOQUEADO (trava local, elevação residual) | saídas |
| 5 | `homologacao.sh recuperar ensaio-016a` | migrador recuperado `0\|0\|0`; CONTINUA BLOQUEADO (`faltam: 016`) | `recuperacao-*.txt` |
| 6 | Reconciliação (§6.3), somente leitura, incluindo a execução manual de `preflight/016.sql` como `admin_cluster` | Estado **"não aplicada"** (esperado: a J1 foi interrompida antes do Flyway) | `reconciliacao_B4.txt` |
| 7 (B2, J2) | `HOMOLOG_AUTORIZA_ELEVACAO=1 homologacao.sh implantar ensaio-016a` | pré-voo com código 0 e `PREFLIGHT_OK`; 016 aplicada; `fim versao=016 … (0\|0\|0)`; `flyway validate`; código 0 e **LIBERADO** com 16 versões | `implantacao-*/{implantacao.txt,janelas_de_elevacao.txt,flyway_info.txt}` |
| 8 | Conferências de retirada da §7 e catálogo (37/21/9, `sslinfo` em `ops`) | — | `retirada_016.txt` |
| 9 | `homologacao.sh concluir-criacao ensaio-016a` | senhas, volumes, liberação e serviços | `saidas/ensaio-016a/*` |
| 10 | B5 em diante (`preparar-016`, `testar-016`), conforme P4 | — | — |

**B2 no novo desenho:** ao fim da J2, **quatro janelas concluídas**, 011, 012 e 013 no `criar` e 016 na J2, cada uma
revogada e conferida, **e uma interrompida**, a J1 da 016. Estão em arquivos separados (`criar` e cada `implantar`). O critério "4
janelas" do plano é lido sobre o conjunto. Nenhum histórico é apagado e nenhuma migration concluída é reaplicada: a
J2 só abre com "não aplicada" comprovada pelo pré-voo.

**Cobertura de B4: PARCIAL.** O ponto de interrupção é `antes-do-flyway@016`, depois de conceder e antes de a 016
executar. A sequência comprova:
- a recuperação de uma **janela elevada interrompida antes da migration**: BLOQUEADO, `recuperar`, `0|0|0`,
  reconciliação "não aplicada" e conclusão pelo caminho autorizado.

**Não** comprova:
- a interrupção **durante a execução do SQL** da 016;
- a reconciliação de uma aplicação com **resultado desconhecido** (perda da resposta do `COMMIT`; 016 efetivada sem
  histórico; estado divergente).

Esses casos ficam **PENDENTES** para um **cenário separado**, com ambiente e decisão próprios, porque podem deixar o
ambiente em estado divergente e inviabilizariam concluir B2 no mesmo ambiente. O procedimento de recuperação deles
está na §6, mas **não** será exercitado por esta sequência.

**B3 "versão fora da lista":** **não** executar com a 016 em `ensaio-016a`. Sem elevação, a 016 seria executada e
falharia, e com `executeInTransaction=false` pode deixar `success=false` no histórico (IMPLANTACAO.md §4–5),
bloqueando o ambiente. Esse caso já foi coberto por I1/`variante-crlf` com migration sintética. Em P4, só com versão
sintética em ambiente próprio.

## 6. Falha, interrupção e resultado desconhecido

**Premissa.** A 016 é uma transação. Os rollbacks observados em P2 vieram de erros devolvidos pelo servidor. Uma
desconexão, ou a perda da resposta do `COMMIT`, deixa o resultado **desconhecido**. Com `executeInTransaction=false`,
o Flyway grava o histórico depois do script, então a 016 pode estar efetivada sem linha no histórico. Isto é
**inferência** (IMPLANTACAO.md §4), **não medida** para a 016. Nenhuma falta de resposta é lida como sucesso ou
rollback.

1. **Bloqueio.** Ambiente BLOQUEADO para continuidade normal. Nada é reaplicado nesta etapa.
2. **Retirada comprovada.** `homologacao.sh recuperar ensaio-016a` (revoga, encerra sessões, confere `0|0|0`, pela
   mesma trava). Se o banco estiver indisponível, registrar **"retirada ainda não comprovada"** e manter o bloqueio.
   Não recriar banco nem ambiente.
3. **Preservação e reconciliação** (somente leitura). Preservar logs e evidências. Registrar o histórico do Flyway
   (linhas da 016, checksum, `success`), o catálogo (`ops`, `sslinfo`, `mfa_events.ops_request_id`, papéis
   `nekkora_ops_*`, contagens 37/21/9), a saída de `preflight/016.sql` e o estado do runner (travas, `estado_migrador`,
   `homologacao.sh estado`). Os invariantes da seção 19, em `BEGIN … ROLLBACK`, só se o catálogo indicar 016 aplicada.

   | Estado | Critério | Consequência |
   |---|---|---|
   | **Não aplicada** | `preflight/016.sql` com código 0 e `PREFLIGHT_OK` (conferências 3–5 em especial) | Retomada permitida (item 4), dentro do limite de janelas |
   | **Aplicada** | histórico com 016 `success=true` e checksum aprovado; catálogo 37/21/9; invariantes OK | Não reaplicar. Seguir para `estado` = LIBERADO e `concluir-criacao` |
   | **Divergente / indeterminado** | qualquer outra combinação | **Parar.** Sem retomada, `repair`, edição de histórico ou exclusão de objetos. Ambiente preservado. Só com nova decisão |

4. **Retomada** só com "não aplicada" e retirada comprovadas, por `HOMOLOG_AUTORIZA_ELEVACAO=1 homologacao.sh
   implantar ensaio-016a`. Com o patch C, o próprio runner reexecuta o pré-voo sob a trava e recusa se o estado não
   for "não aplicada". A retomada é a **J2** da §5. Não há J3 sem nova manifestação.
5. **B4.** Os passos 3–7 da §5 cobrem só a interrupção antes do Flyway (B4 **parcial**). A interrupção durante o SQL
   e o resultado desconhecido ficam pendentes para cenário separado (§5). Em qualquer caso, descartar e recriar o
   ambiente não substitui a prova de recuperação.
6. **Descarte** (`homologacao.sh destruir ensaio-016a`) é etapa própria, autorizada, depois da preservação das
   evidências. Nunca é caminho de recuperação.

## 7. Retirada (a cada janela e ao fim)

- **Pelo runner** (código existente): `ALTER ROLE migrador_nekkora NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS
  NOREPLICATION`, encerramento das sessões do migrador e `0|0|0`, pela sessão da trava. Falha → código 4, trava
  mantida, BLOQUEADO.
- **Conferências adicionais em P4** (somente leitura, depois da J2 e ao fim): os 5 atributos `false`; nenhuma
  participação envolvendo o migrador ou os `nekkora_ops_*` além das permitidas pelo invariante; nenhuma ACL de
  parâmetro citando o migrador.
- **Não remover** permissões normais da baseline (posse do banco `nekkora` e dos objetos do migrador, permissões do
  bootstrap).

## 8. Produção

Fora desta decisão. P2 e P4 não a autorizam.

## 9. Alterações propostas (NÃO aplicadas)

Arquivos em `docs/relatorios/2026-09-29-p3-anexos/`. Nada aplicado ao repositório (T6).

| Patch | Base | Conteúdo |
|---|---|---|
| **A** `p3-A-elevacao-016.patch` | `elevacao.txt` de HEAD | só `+016 25bef818…be61`. **Insuficiente** para o procedimento da §5 (sem vínculo, sem parada, sem pré-voo) |
| **B** `p3-B-vinculo-ambiente.patch` | cópia de trabalho aprovada em P1c | linha `016 25bef818…be61 ensaio-016a` + comentário; `implantar.sh` recusa, antes de conceder, versão vinculada a outro id; `homologacao.sh:90` aceita só sem terceira coluna ou com o id do `criar` |
| **C** `p3-C-parada-preflight.patch` | B aplicado | `implantar.sh`: `HOMOLOG_IMPLANTAR_ATE`; pré-voo obrigatório para versão vinculada; pausa `<ponto>@<versao>` (forma antiga preservada); `validate` ignorando só as pendentes na parada. `homologacao.sh`: `criar` para em 015 com `HOMOLOG_ATE=016`; `pos_implantacao` extraída sem mudança de conteúdo; ação `concluir-criacao`. Novo `implantacao/preflight/016.sql` (somente leitura) |

SHA-256 dos anexos em `LEIA-patches-propostos.txt` do pacote. As linhas 011–013 ficam idênticas byte a byte em A e B
(C não toca `elevacao.txt`).

**Recomendação técnica** (da revisão, ainda não autorização): **B + C**, e retirar a linha da 016 ao encerrar P4.

**Decisões que faltam à responsável:**
- **D-1:** autorizar a decisão da §2 com B + C (ou indicar alternativa).
- **D-2:** retirar a linha da 016 ao fim de P4 (recomendado). Com B, a linha restante só valeria para `ensaio-016a`,
  mas a vigência terá terminado.
- **D-3:** aplicar B + C, com os ajustes F4/F5 e os testes do caminho real do runner (§11), e a integração Git, em
  etapas próprias.

## 10. Revisão estática (comandos, resultados, limites)

- `docs/relatorios/2026-09-29-p3-anexos/revisao_estatica_p3.sh ops/homologacao/saidas/p3r3-20260929T120400Z` →
  **84 OK, 0 FALHA**. Verifica:
  - T0–T6 da revisão 1 (59): aplicabilidade de A e B; 011–013 byte a byte; hash; expressões do runner em LF e CRLF;
    vínculo e negativos; sintaxe; operacionais inalterados;
  - T7 (25), patch C (refeitos nesta revisão):
    - aplica sobre B; `bash -n`; `preflight/016.sql` sem instrução de escrita;
    - **pausa:** a função `ensaio_pausa` **real** de C, executada com `bash -u` (como o runner). Variável **ausente**
      e **vazia** → segue sem erro; forma antiga `antes-de-elevar` e `antes-do-flyway` (I9b/I9c) → pausa;
      `antes-do-flyway@016` → pausa na 016 e não na 013; outro ponto → não dispara. **Contraprova:** a função da
      revisão 2 reproduz `HOMOLOG_ENSAIO_PAUSA: unbound variable`;
    - **pré-voo:** o **bloco real** de `implantar.sh` (C), extraído e executado com `bash -u`, com `admin_sql`
      simulado:
      - código 0 + `PREFLIGHT_OK` → eleva;
      - **`PREFLIGHT_OK` com código 1 → RECUSA** e **`PREFLIGHT_OK` com código 3 → RECUSA** (contraprovas pedidas);
      - `PREFLIGHT_FALHOU` → recusa; erro de consulta → recusa; saída vazia → recusa;
      - CRLF com código 0 → eleva; `preflight/016.sql` ausente → recusa;
    - aritmética da parada em 015; chamada do `criar` com parada; `validate` com pendentes (só texto); `concluir-criacao`.
- **Verificação de mutação** (`mutacao_rev2.txt`): os mesmos testes, aplicados ao código da **revisão 2** reconstruído
  em cópia temporária, detectam os dois defeitos. O pré-voo eleva com `PREFLIGHT_OK` e código 1; a pausa aborta com
  `unbound variable`. Na revisão 2 os testes T7 não os detectavam: a pausa só era testada com a variável definida, e o
  pré-voo por uma réplica da expressão, sem o código de saída.
- **Correções anteriores do instrumento** (revisão 2, `p3r2-…/nota_execucao_anterior.txt`): `\b` do detector de
  escrita corrompido na edição. Corrigido e provado com INSERT plantado.
- **Limites:**
  - nada foi executado contra runner, Flyway ou banco;
  - `preflight/016.sql` **não** foi executado num PostgreSQL;
  - `-ignoreMigrationPatterns='*:pending'` **não** foi testado no Flyway 11;
  - `pos_implantacao` foi conferida só por sintaxe;
  - `admin_sql` real (via `compose exec`) não foi exercitado.

  As 84 verificações validam expressões, sintaxe e funções/blocos isolados, **não** o caminho real (§11, item 4).

**Achados** (F1–F8 da revisão 1; F9–F11 da revisão 2; F12–F14 da revisão 3):

| Id | Achado | Tratamento |
|---|---|---|
| F1 | Lista não vincula ao id | B |
| F2 | `criar` define `HOMOLOG_AUTORIZA_ELEVACAO=1` | Com C, a 016 não abre janela no `criar`; no `implantar` a variável é do operador |
| F3 | `HOMOLOG_ELEVACAO_ARQ` sem restrição | Operacional (só no passo 2 da §5); restrição no código em revisão própria |
| F4 | `validar_config.sh:83` exige `elevacao.txt` = HEAD | Ajustar ao aplicar B (conferir o sha256 do arquivo revisado) |
| F5 | H02 espera 3 janelas | Com C, o `criar` registra exatamente 011–013 (3 janelas) no seu `janelas_de_elevacao.txt`. A conferir no teste real |
| F6 | Sem procedimento para interromper a 016 real | §5 passo 3 (pausa `@016` + `kill -9`, como I4) |
| F7 | Lista CRLF na cópia de trabalho | Sem ação |
| F8 | Liberação não confere o catálogo; retomada poderia reaplicar | C: o pré-voo exige "não aplicada" antes de qualquer janela da 016 |
| F9 | `criar` interrompido na implantação não executa senhas, volumes e serviços, e nenhuma ação os retoma | C: parada + `concluir-criacao` |
| F10 | B3 "fora da lista" com a 016 envenenaria o histórico de `ensaio-016a` | §5: não executar com a 016 |
| F11 | `preflight/016.sql` e o `validate` com pendentes nunca executados | §11: testes reais antes de P4 |
| F12 | (C rev. 2) `ensaio_pausa` expandia `HOMOLOG_ENSAIO_PAUSA` antes de conferi-la: com `set -u` e a variável ausente, o runner abortava, inclusive na implantação normal | Corrigido na rev. 3 (T7 com variável ausente e vazia; contraprova da rev. 2) |
| F13 | (C rev. 2) Pré-voo aceitava `PREFLIGHT_OK` com código de saída diferente de zero | Corrigido na rev. 3: exige código 0 **e** `PREFLIGHT_OK` (contraprovas com códigos 1 e 3) |
| F14 | B4 original não coberto por inteiro: a sequência da §5 só interrompe antes do Flyway | B4 **PARCIAL**. Interrupção durante o SQL e resultado desconhecido pendentes para cenário separado (§11, item 8) |

## 11. Checklist de passagem para P4 e autorização

| # | Item | Situação |
|---|---|---|
| 1 | Decisão da §2 expressamente autorizada (D-1) | **PENDENTE** |
| 2 | Destino da linha depois de P4 (D-2) | **PENDENTE** |
| 3 | B + C aplicados em etapa própria, com F4/F5 | **PENDENTE** |
| 4 | **Testes do caminho real do runner** com B + C, antes de P4, em ambiente `ensaio-*` próprio e autorizado, **sem a 016**, usando versão **sintética** vinculada (ex.: `900 <sha> ensaio-teste` + `preflight/900.sql`), como em I1–I9. Cobrir: implantação normal **sem** `HOMOLOG_ENSAIO_PAUSA` (F12); pré-voo com erro real de consulta (F13); parada em `HOMOLOG_IMPLANTAR_ATE` + `validate` com pendente; `parada_antes_*.txt`; pré-voo OK, FALHOU e com erro; vínculo com outro id recusado; pausa `@versao` + `kill -9` + `recuperar` + retomada; `concluir-criacao`; regressão I1–I9 e H02. `preflight/016.sql` executado em somente leitura num cluster descartável 001–015 (esperado `PREFLIGHT_OK`) e num 001–016 (esperado `FALHA` nas conferências 2–5) | **PENDENTE** |
| 5 | Integração Git (P1b/P1c/P2/P3 e patches) tratada separadamente | **PENDENTE** |
| 6 | Mini missão P4 autorizada, com a ordem da §5 e o limite de janelas da §2 | **PENDENTE** |
| 7 | Relatório P2 corrigido documentalmente | **FEITO** (revisão 1) |
| 8 | **B4 complementar**, em cenário separado (ambiente `ensaio-*` e decisão próprios): interrupção **durante a execução do SQL** da 016 e reconciliação de resultado desconhecido (§6), incluindo o caso "efetivada sem histórico" | **PENDENTE** (fora da sequência de `ensaio-016a`) |

**Registro de autorização (preencher só com manifestação expressa da responsável):**

| Campo | Valor |
|---|---|
| Decisão | — (não fornecida) |
| Patches (B + C ou outro) | — (não fornecido) |
| Destino da linha depois de P4 | — (não fornecido) |
| Responsável | — (não fornecido) |
| Data/hora | — (não fornecida) |
| Referência da manifestação | — (não fornecida) |
