# R2b-1: ensaio integrado `ensaio-016b`, encerrado por STOP no passo 9 (`concluir-criacao`)

**ESTADO: STOP, PARA REVISÃO. A R2b-1 não está aprovada.**
- O ensaio parou no primeiro resultado diferente do esperado, conforme a regra da missão.
- Nada foi repetido, corrigido ou recriado.
- **O P4 continua não aprovado.**

**Data:** 2026-10-01, das 22:51Z às 23:05Z.

**Base:** ordem da R2b-1 com os ajustes do portão R2b-0b: S1′ com 20 caminhos, `validar-config` 147/0, e `testar` com o mesmo `HOMOLOG_ELEVACAO_ARQ`.

**Evidências:** `ops/homologacao/saidas/r2b1-20261001T225114Z/`, fora do Git, com subdiretórios por passo.

## 1. Resultado por passo

| Passo | Resultado | Evidência |
|---|---|---|
| 1. S1′ | **OK.** `d056145e4c1c8f3fe98fe62f491c8d2cc45292e1` sobre S1 `327c4d4`. Diff com **exatamente os 20 caminhos** da lista fechada; 20/20 sha256 iguais aos aprovados no commit e na cópia de trabalho; `git status` limpo; 0 remotos. `core.autocrlf=false` configurado **antes** do checkout: 0 arquivos `i/lf` com `w/crlf`, e o anexo do O1 com os bytes do blob (`653eca1a…`). `validar-config` no S1′: **147/0** | `01_S1/` |
| 2. S2′ | **OK.** `36eabb31d0ab4a804768cbbeedf5c0e06c62c591`. Diff S1′→S2′ = só `ops/homologacao/implantacao/elevacao_lab_ensaio-016b.txt` (sha `b3323116…7759`), com 011–013 legadas, `014 5e5726b9…d8fc tipo=bypassrls ambiente=ensaio-016b` e `016 f37d9841…3732 tipo=superuser ambiente=ensaio-016b`. O `elevacao.txt` operacional não foi tocado (`7fd92fae…`) | `02_S2/` |
| Pré-condições | **OK.** Porta 56721 sem uso (`netstat`); sub-redes 10.252.82/83 livres; nenhum recurso `ensaio-016b` prévio; segredos inexistentes; `hml03` parado; B12 inicial coletado. *A verificação de porta não cobria as faixas reservadas do Windows (§3).* | `00_base/` |
| 3. `criar` até a pausa da 014 | **OK.** T1 `PREFLIGHT_OK` 7/7 e pausa oficial (22:56:12Z). Fotografia: Flyway 001–013; 014, 015 e 016 pendentes; migrador `0|0|0` com 5 atributos `false`; 0 sessões; nenhuma janela da 014; trava do executor ativa (pid 716 vivo) | `03_criar/` |
| 4. Semeadura | **OK.** `SEMENTE_APLICADA`, V=6, E=6, R=6, 18 no total. T3 `antes` OK; manifesto de 21 linhas preservado (`d9ee4307…3f1a`); só contagens e digests | `04_semente/` |
| 5. Liberação da 014 | **OK.** O **mesmo** executor abriu uma janela BYPASSRLS da 014 (22:56:57.616Z → 22:57:02.609Z), aplicou, revogou (`0|0|0`), aplicou a 015 e fez a parada antes da 016 (`faltam: 016`, BLOQUEADO). **T3 `depois` 20/20 OK:** V+E com 12/12 revogadas num único instante dentro da janela; R 6/6 intactas; digests das colunas preservadas idênticos; colunas novas nulas; 0 sessões fora da semente; 18 no total; 0 não revogadas sem marcador | `05_014/` |
| 6. Recusas B3 da 016 | **OK, 4 de 4.** A (sem `HOMOLOG_AUTORIZA_ELEVACAO`) código 2; B (sha errado) 2; C (`ensaio-outro`) 2; D (sem vínculo) 3, na validação. Em todas, antes = depois: 0 janelas da 016, histórico 001–015, `0|0|0`, `ops` ausente | `06_B3/` |
| 7. J1 | **OK.** Catálogo antes da J1: `e04b759e… 807`. Pausa `antes-do-flyway@016` com a janela aberta (`1|0|0`, só SUPERUSER) e sem container Flyway. `kill -9` só no pid 1974 (`implantar.sh` dono da trava, filho de `homologacao.sh implantar`); o pai terminou com 137; sem processos órfãos. BLOQUEADO; `testar`, `testar-016`, `preparar-016` e `concluir-criacao` recusados (código 1), com o catálogo inalterado | `07_J1/` |
| Recuperação e reconciliação | **OK.** `recuperar` com código 0 e `0|0|0`. Reconciliação somente leitura: `preflight/016.sql` em `READ ONLY` com código 0 e `PREFLIGHT_OK` (7/7); 016 fora do histórico; `ops`, `sslinfo` e `ops_request_id` ausentes; **catálogo idêntico ao de antes da J1** | `07_J1/` |
| 8. J2 | **OK.** Código 0, **LIBERADO**, Flyway 001–016. **§19 completa depois da retirada**, por `admin_cluster` em `READ ONLY`: código 0, `SECAO19_OK`. Janelas da 016: **exatamente 2** (J1 sem fim; J2 concluída 23:00:20.656Z → 23:00:26.382Z); nenhuma J3. Migrador: 5 atributos `false`, 0 participações, 0 ACL de parâmetro, 0 sessões | `08_J2/` |
| **9. `concluir-criacao`** | **STOP: código 1, "ERRO: subida dos serviços"** (§2) | `09_testar/` |
| 9. `testar` / H02 | **Não executado** (STOP) | — |
| 10. `preparar-016` / `testar-016` | **Não executado** (STOP) | — |
| 11. `validar-config` final | **Não executado** depois do STOP. O registro é o 147/0 no S1′ | `01_S1/` |
| 12. Maven | Não avaliado (STOP antes do passo 12) | — |
| 14. S3′ e encerramento | **Não executado**: o S3′ é o passo 14. A lista de lab do S2′ continua no clone com as linhas 014 e 016 | `14_encerramento/` |
| B12 início × STOP | **OK:** idêntico por ID fora do ambiente. O ambiente tem 5 containers, 2 redes, 4 volumes (3 nomeados e 1 anônimo do Redis) e 3 imagens `d056145` | `14_encerramento/` |

## 2. O STOP

**Sintoma.** `concluir-criacao` criou as senhas SCRAM e os volumes e subiu platform e portal. Ao subir o gateway:

> `Error response from daemon: ports are not available: exposing port TCP 127.0.0.1:56721 -> 127.0.0.1:0: listen tcp4 127.0.0.1:56721: bind: An attempt was made to access a socket in a way forbidden by its access permissions.`

**Causa, confirmada só com leitura:**
- `netsh interface ipv4 show excludedportrange protocol=tcp` mostra o Windows com a faixa **56626–56725 reservada**. Ela contém 56721, e também 56720 (porta do `ensaio-016a` no P4) e 56722 (`ensaio-r2b0`).
- A reserva, portanto, surgiu depois dessas execuções. Reservas assim são dinâmicas, feitas pelo Hyper-V/WinNAT do Docker Desktop.
- A porta não estava em uso (`netstat` = 0), e foi isso que a pré-condição verificou.

**Classificação:**
- É falha do **ambiente hospedeiro**, não do código em teste.
- Mas a pré-condição "porta livre" da missão estava **incompleta**: não consultava as faixas reservadas.
- Registro como achado **F-R2b1-1**.

**Estado preservado no ponto do STOP:**
- Banco **LIBERADO**: 001–016, `flyway validate` OK, migrador `0|0|0` sem privilégio.
- 18 sessões, com 0 não revogadas sem marcador.
- `gateway-1` em `Created`; platform e portal em execução; Postgres e Redis saudáveis.
- Nada foi parado, removido, repetido nem alterado depois do erro.

**O que ficou provado e o que não:**
- **Provado:** a parte de banco do ensaio integrado (passos 1–8), com os critérios da ordem: 014 com semeadura e revogação corretas; J1 interrompida, recuperação limpa, J2 concluída; §19 OK depois da retirada; migrador sem privilégio residual.
- **Ficaram sem prova:** a subida completa dos serviços, o **H02 completo** (5 concluídas + 1 interrompida pelo `testar`), `testar-016` (B5–B8b, B10–B12), o `validar-config` final, o S3′ e o B12 final.

## 3. Decisão necessária para continuar

| Opção | O que é | Prós e contras |
|---|---|---|
| **A. Novo ensaio `ensaio-016c` (recomendo)** | S2″ = nova lista de lab vinculada a `ensaio-016c` (commit só no clone, a partir de S1′ inalterado). Nova pré-condição de porta que consulta também `excludedportrange`. Fluxo completo dos passos 3–14, com J1 e J2 próprias | Respeita "sem correção dentro do ensaio" e "sem recriação". O `ensaio-016b` fica preservado como evidência do STOP. Custo: refazer os passos 3–8, uns 6 minutos de executor real |
| B. Retomar o `ensaio-016b` | Trocar só a porta publicada (`HOMOLOG_PORTA` no `ambiente.env`) e repetir `concluir-criacao` | Aproveita J1/J2 já provadas. Mas é alteração de configuração dentro do ensaio, além de uma repetição de passo, ambas proibidas pela regra. Exigiria exceção explícita; e a repetição do `concluir-criacao` (senhas já gravadas) não foi validada |
| C. Liberar a faixa reservada no Windows | Mexer nas reservas do WinNAT/Hyper-V | Altera a configuração da máquina, fora do escopo. Não recomendo |

**Também para decidir:**
- **Destino do `ensaio-016b`:** preservar até a revisão (padrão desta missão) ou descartar por ID depois.
- **Achado de ferramenta.** A pré-condição de porta do `criar` (e as das missões) deveria consultar as faixas reservadas. Não alterei nada. Fica para decisão própria, ou só como verificação no roteiro da opção A.

## 4. Achados

1. **F-R2b1-1 (STOP):** a porta do gateway estava numa faixa TCP reservada pelo Windows (56626–56725). A pré-condição só cobria uso. Causa e estado no §2.
2. **Harness, sem efeito no resultado:**
   - o auxiliar de leitura não tinha a função `papeis` e a linha "papéis antes da J1" ficou vazia. O critério da J1 é o catálogo, registrado (`e04b759e… 807`);
   - na lista de processos depois da J1, a única linha é o próprio `awk` da consulta, que reutilizou o pid 1984.
3. **Transporte da caixa em Windows:** não exercitado (fora do escopo). Continua aberto.
4. **Expectativa do H02 pela lista em vigor:** achado conhecido e não bloqueante, registrado no portão R2b-0b; não exercitado por causa do STOP.

## 5. Inventário preservado

- **`ensaio-016b`** (rótulo `nekkora.homologacao=ensaio-016b`):
  - containers `gateway-1` (Created), `portal-1`, `platform-1`, `postgres-1`, `redis-1`;
  - volumes `_caixa`, `_operadores`, `_pgdata` e 1 anônimo do Redis;
  - redes `_borda` e `_interna`;
  - imagens `operacao`, `platform` e `portal` com a tag `d056145`;
  - segredos em `~/.nekkora/homologacao/ensaio-016b`, fora do pacote e do Git.
- **Clone:** `../lab-r2b1-20261001T225153Z/nekkora-platform`.
  - S1′ `d056145`, S2′ `36eabb3` (HEAD).
  - Sem remoto, `autocrlf=false`, `git status` limpo.
  - A lista de lab segue com 014/016, porque o S3′ não foi feito.
- **Outros preservados:** clones `lab-r2b0-…`, `lab-p4-…` e `lab-p4r2ac-…`; `ensaio-016a` (só o Postgres, intocado); `hml03` parado; bases de desenvolvimento.
- **Repositório original:** sem stage, commit ou push; `elevacao.txt` operacional intacto; 014 e 016 rev5 inalteradas. Na árvore de trabalho, só este relatório é novo nesta missão.

## Adendo MFA-CONSOL-1 (2026-10-02): registro da decisão da revisão

*Acrescentado na missão MFA-CONSOL-1 (E0.1). O corpo acima não foi alterado; o pacote citado continua sendo a evidência daquele momento. Origem das citações: mensagens da responsável na sessão Claude Code `d31e2723-029b-47ee-a98d-bf0f4f24ed47`, horário UTC. Só trechos de decisão.*

| Item | Registro |
|---|---|
| Entrega | R2b-1, `ensaio-016b` (STOP no passo 9) |
| Pacote | `homologacao-016-r2b1-20261001T230355Z.zip`, `6191948e54dd142530f950cc64d69c81bb473edd4d9f68ce4fc9ba4e5c01528b` (85/85) |
| Decisão | 2026-10-01T23:15:38Z: "R2b-1 / ensaio-016b STOP — não aprovado; Evidência dos passos 1–8 Aceita e preservada; Retomar ensaio-016b trocando porta Não autorizado; Alterar reservas do Windows Não autorizado; Novo ensaio-016c Aprovado, após gate da ferramenta; Preservar ensaio-016b Sim; Verificação de porta Deve virar ferramenta" |
| Pendência aberta | **encerramento administrativo pendente** da lista de lab do clone `lab-r2b1-20261001T225153Z` (HEAD `36eabb3`, linhas 014/016 vinculadas a `ensaio-016b`; o S3′ não foi feito por causa do STOP). STOP **não** significa container parado: 4 dos 5 containers estavam em execução no último registro. A ausência de remoto **não** impede a execução local do executor a partir do clone. Tratada na etapa E1 da MFA-CONSOL-1 |
