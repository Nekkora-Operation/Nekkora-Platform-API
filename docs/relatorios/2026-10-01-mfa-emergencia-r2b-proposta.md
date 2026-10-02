# R2b: ensaio integrado `ensaio-016b`, com 014 em janela BYPASSRLS e semeadura pré-014 (proposta formal)

**ESTADO: PROPOSTA, PARA AUTORIZAÇÃO.** Nada desta proposta foi executado.
- O P4 continua não aprovado até a validação integrada.
- Ficam fora: 5b (`hml03` parado), P5, ativação operacional da 014 e commit/push.

**Data:** 2026-10-01.

**Base:**
- revisão da 5a e do seu adendo;
- recomendações da revisão: semeadura pré-014 incluída, `ensaio-016b` preservado até a revisão;
- pacotes preservados, do P4 ao `frenteb5a` (§9).

## 1. Objetivo e critério de aceite

Comprovar, pelo executor real e num ambiente novo, o fluxo completo com o modelo atual:
- 011–013 em janela SUPERUSER;
- **014 em uma janela BYPASSRLS** vinculada ao `ensaio-016b`, revogando as sessões semeadas esperadas;
- 015;
- **016 (revisão 5, `f37d9841…3732`)** vinculada ao `ensaio-016b`: J1 interrompida e recuperada, J2 concluída.

Os critérios são os do §5.

**Aceite: todos os itens do §5 com OK.**
- B4, B10 e B11 com os limites atuais declarados.
- Um resultado diferente é registrado e encerra o ensaio no ponto em que ocorreu (§6).

## 2. Fases e portão

| Fase | Conteúdo | Cria recursos? | Saída |
|---|---|---|---|
| **R2b-0: ferramenta** | Alterações T1–T3 (§3), validadas em base descartável e num ensaio curto pelo executor real | Sim, só descartáveis desta fase, removidos por ID | Evidências, diffs, `validar-config` |
| **Portão** | **Recomendação:** entregar a R2b-0 para revisão e só então montar o clone | Não | Decisão D1 (§8) |
| **R2b-1: ensaio** | Clone, `ensaio-016b`, semeadura, 014, J1/J2 da 016, verificações e encerramento | Sim: o `ensaio-016b` e o clone ficam preservados | Relatório e pacote |

Motivo do portão: o snapshot deve vir só de versões aprovadas, e T1–T3 ainda não foram revisadas.

## 3. Alterações de ferramenta (R2b-0), necessárias antes do ensaio

Achados que tornam essas alterações necessárias, conferidos no código atual:
- **(a) 014 vinculada exige pré-voo oficial.** A gramática (`lib/lista_elevacao.sh`) já aceita `014 <sha> tipo=bypassrls ambiente=<id>`. Mas no `implantar.sh` toda linha com alvo exige `implantacao/preflight/<versao>.sql`, e hoje só existe o `016.sql`. O pré-voo alternativo vale só para versões sintéticas 9xx. Vincular a 014 sem T1 faria o executor **recusar** a elevação.
- **(b) Parada antes da 014.** O `criar` fixa `HOMOLOG_IMPLANTAR_ATE=015`; a única parada verificável é antes da 016. O gancho de ensaio `HOMOLOG_ENSAIO_PAUSA=antes-de-elevar@014:<arquivo>` já existe, é recusado fora de `ensaio-*` e funcionou dentro do `criar` na A6b.
  - Ponto exato: depois do pré-voo e com a trava segura, **antes de conceder**. A 013 está aplicada e registrada, a 014 está pendente e o migrador está em `0|0|0`.
  - A semeadura entra nessa pausa. Liberada a pausa, o **mesmo executor** segue: janela da 014, 015 e a parada verificável antes da 016, já existente.
  - Nada edita o histórico Flyway e nada reaplica migrations.
  - Por isso **não é preciso alterar o `criar` nem o `implantar.sh`.**

| Item | Alteração | Validação na R2b-0 |
|---|---|---|
| **T1** `implantacao/preflight/014.sql` (novo) | Somente leitura; termina em `PREFLIGHT_OK`. Confere: histórico 001–013 com `success=true` e nenhuma 014; ausência de `mfa_verified_at`, `user_mfa_factors` e das triggers da 014; `user_sessions` com RLS habilitada e forçada; dono do banco = `migrador_nekkora` | Positivo na base 013. Negativos com recusa antes de conceder: 014 já aplicada, histórico com falha, coluna presente. `validar-config` com verificações novas |
| **T2** Semeadura declarada: `sql/fixture_014_pre.sql` + subcomando `homologacao.sh semear-pre014 <id>` | Só `ensaio-*`. Pré-condições, todas exigidas: histórico com máxima 013 e sem 014; pausa `antes-de-elevar@014` ativa (`.chegou` presente); migrador em `0|0|0`; nenhuma semente anterior. Uma transação via `admin_cluster`: organização e usuários **sintéticos**, mais as sessões do §4. Grava o manifesto da semente (contagens e digests, sem token nem identificador) | Em base descartável no modelo (`aplicar_como_migrador.sh` com `NEKKORA_ATE=013`): semear, aplicar a 014 **com** BYPASSRLS → conjunto esperado revogado. **Contraprova sem BYPASSRLS** (`NEKKORA_BYPASS=`) → a conferência T3 acusa sessões não revogadas sem marcador. Recusas: fora de `ensaio-*`, 014 já aplicada, semente repetida |
| **T3** Conferência `testes/semente_014.sh` (`antes` / `depois`) | Somente leitura (`READ ONLY` + limites de duração), por `admin_cluster`, que vê todas as linhas. Compara o manifesto com o estado depois da 014 (§4). Sai com 0 OK, 1 FALHA ou 3 INCONCLUSIVO | Mutações controladas na base descartável: revogada de controle alterada, coluna preservada alterada e sessão a mais devem dar FALHA |
| **Ensaio curto da ferramenta** (executor real) | Ambiente descartável `ensaio-r2b0`, com lista de lab e 014 vinculada a ele | Fluxo `criar` → pausa → `semear-pre014` → continuação → `PARADA antes da 016` conforme. **Recusas da 014 antes de conceder:** sha256 errado e outro ambiente, sem janela aberta. Removido por ID ao fim da R2b-0 |

**Por que as recusas da 014 ficam na R2b-0:** no `ensaio-016b`, a 014 roda dentro do `criar`. Uma recusa ali seria uma falha parcial de criação, e a regra é registrar e parar (§6). As recusas da **016** continuam no `ensaio-016b`, como no P4.

**Verificações novas no `validar-config`:**
- T1 presente e somente leitura;
- `semear-pre014` recusado fora de `ensaio-*`;
- ganchos de pausa ainda recusados fora de `ensaio-*`;
- `elevacao.txt` operacional sem linha da 014 nem da 016.

## 4. Semeadura e conferência da 014

**Fixture declarada, exclusivamente sintética**, aplicada com a 013 registrada e antes da 014.
- Organização e usuários com nomes e e-mails sintéticos (`r2b-semente-*`, domínio reservado `.invalid`).
- `session_token_hash` sintético; nunca exportado.
- `created_at` no passado (agora − 2 h).

| Grupo | Qtde | Estado inicial | Alteração permitida pela 014 |
|---|---|---|---|
| V: não revogadas, válidas | 6 | `revoked_at` nulo, `expires_at` = agora + 1 dia | Só `revoked_at` ← o mesmo instante para todas (`greatest(now(), created_at)` = `now()` da transação da 014) |
| E: não revogadas, expiradas | 6 | `revoked_at` nulo, `expires_at` = `created_at` + 1 h (já passou) | Idem: a 014 usa só `revoked_at IS NULL`, não a validade |
| R: já revogadas (controle) | 6 | `revoked_at` = `created_at` + 10 min | **Nenhuma** |

**Conferência T3, depois da retirada da janela da 014:**
- V e E: 12 de 12 com `revoked_at` igual a um único instante, dentro da janela registrada da 014 (entre `inicio` e `fim` em `janelas_de_elevacao.txt`).
- R: 6 de 6 com `revoked_at` inalterado.
- As 18 sessões com digest idêntico das colunas preservadas: `session_id`, `organization_id`, `user_id`, `session_token_hash`, `created_at`, `expires_at`, `last_seen_at`.
- Colunas novas (`mfa_verified_at`, `mfa_step_up_at`, `csrf_token`) nulas nas 18.
- Nenhuma outra sessão existe; digest da organização e dos usuários semeados inalterado.
- 0 sessões não revogadas sem marcador.
- Janela da 014 retirada para `0|0|0`.

Só contagens e digests saem para as evidências.

## 5. Sequência do ensaio (R2b-1)

1. **Snapshot (lista fechada).** Clone novo em `../lab-p4r2b-<instante>/nekkora-platform`, sem remoto.
   - **S1′:** árvore do S1 aprovado no P4 (`327c4d4`, clone `lab-p4-20260929T192708Z`) mais **exatamente** os 15 arquivos da tabela abaixo, com o sha256 LF conferido, mais T1–T3 como aprovados no portão.
   - Nada mais da árvore de trabalho: ficam de fora `db/tests/portal-8-2/`, relatórios, `saidas/`, `node_modules`, `__pycache__` e a variante CRLF de evidência.
   - **S2′:** só a lista de elevação de lab: 011–013 como estão; `014 5e5726b9a8f58d319b6b17dcbf7801c21e258999b003d25a023680f0d271d8fc tipo=bypassrls ambiente=ensaio-016b`; `016 f37d984128e8241474319a39c00fa54baed90519169663757c7f799e61b63732 tipo=superuser ambiente=ensaio-016b`.
   - Conferência de `diff` S1→S1′ = a lista; S1′→S2′ = só `elevacao.txt`.
2. **`criar ensaio-016b`** com `HOMOLOG_ATE=016`, `HOMOLOG_ENSAIO_PAUSA=antes-de-elevar@014:<arquivo>`, redes /24 e porta livres conferidas na hora.
   - O processo pausa depois do pré-voo T1.
   - Fotografia somente leitura: histórico 001–013, `0|0|0`, `user_sessions` vazia.
3. **`semear-pre014`**, e então T3 `antes`.
4. **Liberar a pausa.** O mesmo executor concede a janela BYPASSRLS da 014, aplica, revoga e confere `0|0|0`, aplica a 015 e para antes da 016 (`parada_antes_016.txt` conforme).
   - Depois, T3 `depois` e invariantes da 014 com `admin_cluster`.
5. **Recusas da 016 (B3), antes de qualquer concessão**, como no P4: (a) sem `HOMOLOG_AUTORIZA_ELEVACAO`, (b) sha256 errado, (c) outro ambiente, (d) linha da 016 sem vínculo (código 3, validação).
   - Esperado em todas: nenhuma janela, nenhum pré-voo concedido, histórico inalterado, `0|0|0`.
6. **J1 da 016:** `implantar` com pausa `antes-do-flyway@016`, e `kill -9` só no dono da trava.
   - Esperado: BLOQUEADO; `testar`, `testar-016`, `preparar-016` e `concluir-criacao` recusados.
   - `recuperar` → `0|0|0`.
   - Reconciliação somente leitura: 016 não registrada e catálogo idêntico ao de antes da J1.
7. **J2 da 016:** `implantar` → LIBERADO 001–016.
   - **§19 completa depois da retirada**, por `admin_cluster` em `READ ONLY`.
   - **No máximo duas janelas da 016**, sem J3 e sem recriação automática.
8. **`concluir-criacao`**, e então **`testar`**.
   - O **H02 completo** precisa reconhecer **5 janelas concluídas** (011, 012, 013 SUPERUSER; 014 BYPASSRLS; 016/J2 SUPERUSER) e **exatamente 1 interrompida** (016/J1), com recuperação própria.
9. **`preparar-016`** e **`testar-016`**:
   - B5 (§19 depois da retirada), B6, B7, **B8a e B8b com as fixtures já declaradas**, B12 completo (início × fim);
   - **B4 parcial**, **B10 e B11** com os limites atuais;
   - **B9 separado** (fora deste ensaio);
   - `SEGREDOS` OK.
10. **`validar-config`** no clone.
11. **Encerramento:**
    - **S3′** retira a lista de lab (sem 014/016), como o S3 do P4.
    - Conferir privilégios: 5 atributos `false`, 0 participações, 0 sessões do migrador.
    - Preservar o `ensaio-016b`, o clone, as imagens e as evidências. **Descarte só em decisão posterior.**

**Os 15 arquivos aprovados (sha256 LF).** Todos modificados pela última vez antes da entrega da C2 (20:04:57Z), portanto iguais às versões entregues.

| sha256 | arquivo |
|---|---|
| `b9fb8b9c…280d` | `api/nekkora-core/src/test/java/com/nekkora/core/testsupport/RealDatabase.java` |
| `395cbd44…c017` | `api/nekkora-ops/src/test/java/com/nekkora/ops/testsupport/OpsBanco.java` |
| `f37d9841…3732` | `db/migrations/016_recuperacao_emergencial_mfa.sql` |
| `09c0f16b…8163` | `db/tests/mfa-emergencia-016/run.sh` |
| `4a6c3ad6…e956` | `docs/relatorios/2026-09-27-mfa-emergencia-matriz-016.md` |
| `71a41ed4…4d28` | `ops/homologacao/IMPLANTACAO.md` |
| `a554a4a6…a47` | `ops/homologacao/homologacao.sh` (antes de T2) |
| `60f81c85…e1d` | `ops/homologacao/implantacao/implantar.sh` |
| `47954b73…9050` | `ops/homologacao/testes/h016.sh` |
| `6c7fe444…67da` | `ops/homologacao/testes/validar_config.sh` (antes das verificações de T1/T2) |
| `245f667a…70d1` | `db/bootstrap/aplicar_como_migrador.sh` |
| `1030842e…e19a` | `docker-compose.modelo.yml` |
| `367a6f51…1451` | `ops/homologacao/lib/lista_elevacao.sh` |
| `ef6b1a71…dcf1` | `ops/homologacao/testes/inventario_b12.py` |
| `25679221…d574` | `ops/homologacao/testes/test_inventario_b12.py` |

Os hashes completos ficam na evidência da montagem. Os arquivos que T2/T3 alterarem entram com o hash aprovado no portão.

## 6. Regras de falha e de achado

- **Falha parcial de criação:** registrar e parar, sem repetição automática. O ambiente fica como está, para a revisão.
- **016:** no máximo 2 janelas (J1 interrompida, J2 concluída). Nenhuma J3 e nenhuma recriação.
- **Falha do transporte em Windows:** preservar a **primeira** evidência, com stack trace e horário. Uma repetição bem-sucedida não apaga o achado, que continua aberto.
- **Maven:** reaproveitar a regressão aprovada (C2, 256/0) se os fontes Java do clone forem idênticos aos daquela execução, conferido por sha256. T1–T3 não tocam Java. Só repetir se houver mudança que a justifique, com o motivo registrado.
- **Resultado diferente do esperado num passo:** registrar e parar naquele ponto. Nenhuma correção dentro do ensaio.

## 7. Restrições mantidas

- **Repositório:** sem stage, commit ou push. Commits só no clone de lab, que não tem remoto.
- **`elevacao.txt` operacional intacto:** linhas da 014 e da 016 só na lista de lab.
- **Recursos preservados:** `ensaio-016a`, o clone dele, a cópia da 016 rev4 e o histórico Flyway, os clones `lab-p4r2ac`, o cache de build, as três bases de desenvolvimento e `hml03` (parado).
- **Limpeza:** remover só recursos criados nesta missão, por ID e inventário (R2b-0). Na R2b-1, nada é removido.
- **Segredos:** em `~/.nekkora/homologacao/ensaio-016b` (e `ensaio-r2b0`), nunca empacotados.
- **Pacotes:** manifesto conferido em extração nova; varredura de tokens com controle positivo; varredura de valores reais de segredos com controle.
- **Escopo:** ampliação só com motivo registrado e decisão apresentada.

## 8. Decisões pedidas

| ID | Decisão | Recomendação |
|---|---|---|
| **D1** | Portão entre R2b-0 e R2b-1 | **Revisar a R2b-0 antes de montar o snapshot.** Alternativa: seguir direto se todos os critérios da R2b-0 derem OK, com o risco de o snapshot conter ferramenta ainda não revisada |
| **D2** | Autorizar a R2b-0: T1–T3, base descartável e `ensaio-r2b0` com remoção por ID ao fim | Autorizar |
| **D3** | Autorizar a R2b-1 nos termos do §5–§7, com `ensaio-016b` e clone preservados | Autorizar depois do portão |
| **D4** | Recusas da 014 no `ensaio-r2b0` (R2b-0) e as da 016 no `ensaio-016b` | Como proposto (§3) |

## 9. Referências

- **Adendo da 5a, incluído nesta entrega:** `docs/relatorios/2026-10-01-mfa-emergencia-frente-b-5a.md`, sha256 `e5a217bedba0a397b81a4b8b940515cd25b244f0fe29bf1ccc84f810b8a72186`.
- **Pacotes preservados:**
  - P4 `homologacao-016-p4-20260929T231321Z.zip`;
  - `p4r1`, `p4r1r2`, `p4r2a`, `p4r2ac`, `p4r2ac2-20261001T200457Z`;
  - `frenteb5a-20261001T204222Z` (`efc4ccd9…1251`).
- **014:** `5e5726b9a8f58d319b6b17dcbf7801c21e258999b003d25a023680f0d271d8fc` (inalterada desde o P4).
- **016 rev5:** `f37d984128e8241474319a39c00fa54baed90519169663757c7f799e61b63732`.
