# P4-R1, revisão 2: correções de B12 e âncora explícita de B5

**ESTADO: PARA REVISÃO.** O P4 continua não aprovado. Esta revisão não autoriza descarte (P5) nem janela de elevação.

**Data:** 2026-10-01, das 14:58Z às 15:10Z.

**Base:**
- relatório P4-R1 (`2026-10-01-mfa-emergencia-p4r1-b5-b12.md`) e pacote `homologacao-016-p4r1-20261001T144809Z.zip` (`3b893b72…a5ab`), ambos preservados e não editados;
- apontamentos da revisão de 2026-10-01.

**Evidências:** `ops/homologacao/saidas/p4r1r2-20261001T145835Z/`, fora do Git.

**Itens do P4-R1 substituídos por esta revisão:**
- §2.4: parte (a) da alternativa A e Q1;
- §2.6: plano de testes;
- §3.3: o coletor proposto;
- §6: o plano da P4-R2.

O restante continua válido. Em particular, a classificação do B12 do P4 continua a mesma: a medição original do `h016` é **parcial**, e a preservação é **comprovada** pelo inventário histórico independente P1b × P10. Essa conclusão não depende do coletor novo.

## 1. B12: inspeção incompleta aceita (apontamento 1)

**Reprodução.** Escrevi os testes novos antes de corrigir e rodei contra o coletor da rev. 1 (`B12/testes_antes_da_correcao.txt`). Resultado: **21 falhas + 1 erro em 29**.
- `[{}]` com código 0 deu OK (T13).
- Objeto com outro `Id` (container, rede, imagem) ou outro `Name` (volume) deu OK (T14).
- Também passavam tipos inválidos e campos obrigatórios ausentes (T15): `State` como texto, sem `Name`, sem `StartedAt`, `Labels` como lista, `RepoTags` como texto, sem `CreatedAt`, `Mounts` como objeto.

**Correção (`validar()`):** estrutura, tipos e campos obrigatórios conferidos por classe.

| Classe | Identidade (tem de ser **igual ao ID pedido**) | Obrigatórios |
|---|---|---|
| containers | `Id` | `Name`, `Image`, `RestartCount` inteiro, `State.Status`, `State.StartedAt`, `Config` (mapa), `Mounts` (lista; `Type`, `Destination`, e `Name` se `Type=volume`) |
| redes | `Id` | `Name`, `IPAM` (mapa; `Config` nulo ou lista) |
| volumes | `Name` | `CreatedAt`, `Driver` |
| imagens | `Id` | `RepoTags` (lista de texto ou nulo); `Config`, se presente, é mapa |

- Rótulos **ausentes ou nulos continuam legítimos** (T15b). Rótulos em outra forma que não mapa de texto → leitura inválida.
- Identidade ausente ou divergente, ou estrutura inválida → `FALHA_LEITURA` → **INCONCLUSIVO**.
- O bruto preserva ausência e tipo originais: a redução não "conserta" valores. Continua sem `Env`/`Cmd`.

## 2. B12: volume preexistente desprotegido (apontamento 2)

**Reprodução.** O caso apontado foi um volume protegido no início, que um container do ensaio passa a montar com um atributo alterado. Na rev. 1 dava B12 OK (T16, falhava). Também falhavam:
- o mesmo caso sem atributo alterado (T16b);
- anônimo compartilhado com container protegido (T17);
- volume nomeado sem rótulo montado só pelo ensaio (T18);
- anônimo do ensaio passando a ser montado por protegido (T19);
- container protegido passando a montar um volume (T20);
- volume sem a marca de anônimo (T21).

**Correção:**

1. **Classificação por fotografia, com prova explícita.** Um volume é do ambiente quando:
   - tem o rótulo `nekkora.homologacao=<id>`; **ou**
   - é **anônimo** (rótulo `com.docker.volume.anonymous`) **e** é montado por pelo menos 1 container do ambiente **e** por **nenhum** container protegido.

   Montagem pelo ensaio, sozinha, não comprova propriedade. Volume nomeado sem rótulo continua protegido.
2. **Proteção monotônica.** Um objeto só é do ambiente se o for em **toda** fotografia em que foi classificado. Objeto presente nas duas fotografias com classificação diferente → **FALHA** ("classificação mudou"; T16c, T19).
3. **Atributos protegidos ampliados:**
   - montagens de cada container (T20);
   - `MontadoPor` de cada volume, isto é, a lista de quem o monta. Um protegido que passe a ser montado pelo ensaio aparece como alterado (T16b).
4. Volume anônimo do ensaio criado durante o teste e só usado por ele continua aceito (T22).

**Resultados:**
- **Testes locais:** 30/30 OK (`B12/testes_locais.txt`, código 0). São os 17 da rev. 1 mais 13 novos; um fixture da rev. 1 (T06, volume novo) ganhou o `Driver`, que agora é obrigatório.
- **Controles de mutação** (`B12/controle_mutacao.txt`): cada regra desligada isoladamente reprova.

| Regra desligada | Testes que reprovam |
|---|---|
| código de saída | T03b, T05, T05b |
| identidade | T14 |
| estrutura | T15 |
| proteção monotônica | T16c |
| `MontadoPor` | T16b |
| anônimo compartilhado | T17 |

- **Coleta real só de leitura** (`B12/coleta_real/`): 124 objetos lidos, todos aprovados pela validação estrita, 0 problemas. Ambiente: 5 containers, 2 redes, 4 volumes e 3 imagens. O anônimo `4d6c8c64…` é montado só por `872da8909dbb` (Redis do ensaio). Comparação: código 0.
- **Patches** (`git apply --check` OK no original; nada aplicado):
  - `B12/proposta/inventario_b12_novos.patch`;
  - `B12/proposta/h016_b12.patch`, idêntico ao da rev. 1.

## 3. B5: âncora explícita (apontamento 3)

**Problema da rev. 1.** A âncora era `datdba`, validada só por exclusão de listas. Um dono de banco fora das listas era aceito: `admin_cluster`, `dono_inesperado`, `pg_monitor`. A identidade esperada vinha do próprio catálogo validado.

**Correção (proposta `B5/proposta_A/`, patch `016_proposta_A.patch`):**

- **Identidade de implantação declarada:** `v_identidade_impl CONSTANT TEXT := 'migrador_nekkora'`, no bloco. Ela não é derivada do catálogo.
- **(a1)** A identidade declarada tem de existir e não pode ser papel operacional, de serviço, dono de funções, `backup_nekkora` nem `op_*`.
- **(a2)** **Exigência do contrato operacional** (`IMPLANTACAO.md:12`: o migrador é dono do banco): `datdba` **tem de ser** a identidade declarada. Qualquer outro papel reprova, inclusive superusuário ou papel fora de todas as listas.
- **(b) e (c)** comparam os donos com a identidade declarada, não com `datdba`.
- **(b)** virou predicado sobre uma fonte (`__FONTE_B__`), como o (c), para ter contraprovas sintéticas.
- **Cabeçalho da 016** (no patch) declara o **modelo suportado**:
  - a migration é aplicada pela identidade `migrador_nekkora`, dona do banco, elevada só na janela;
  - os objetos não reatribuídos ficam com ela;
  - outra topologia não é suportada por esta revisão e reprova a §19;
  - a topologia de produção continua pendente.

**Resultados** (READ ONLY no `ensaio-016a`):

| Execução | Resultado |
|---|---|
| §19 completa com a proposta, no estado real pós-retirada (`sql/07_*`) | código 0 |
| Sintéticos (`sql/08_*`) | **40/40**: 21 de (c), 9 de (b), 10 de identidade |
| Controles: (a2) desligado / (b) só local / (c) sem ACL (`sql/08b_controle_*`) | reprovam 5 / 6 / 8 (código 3) |

Casos sintéticos novos de dono inesperado fora dos prefixos e listas:
- **(c):** C18 (gatilho), C19 (`sslinfo` com dono superusuário), C20 (interna);
- **(b):** B01 (schema), B02 (schema com dono superusuário), B07 (índice);
- **identidade:** dono do banco `admin_cluster`, `dono_inesperado`, `pg_monitor`, `nekkora_platform` ou nulo; identidade declarada inválida (`nekkora_platform`, `nekkora_ops_mfa_owner`, `op_hml_ver`) ou inexistente (`dono_inesperado`).

**Hashes:**
- 016 revisão 4: `25bef818…` (inalterada no original, no clone e na cópia do ambiente);
- 016 com a proposta: `f37d9841…3732`, só numa cópia fora do repositório;
- predicados: (a) `4d066991…`, (b) `5e0d938b…`, (c) `f596a6e9…`.

**Consequência a decidir.** As suítes atuais aplicam as migrations como o superusuário dono do banco:
- `db/tests/mfa-emergencia-016`: `nekkora`;
- `OpsBanco`: `nekkora` ou `admin_cluster`.

Com a revisão 5, (a2) **reprovará essas bases por desenho**, porque não seguem o modelo suportado. A P4-R2 precisa fazê-las reproduzir o modelo:
- criar `migrador_nekkora` como dono do banco;
- aplicar 001–016 como ele, com SUPERUSER só durante as versões que exigem elevação, como na homologação.

Isso coincide com o T1 (estado pós-retirada), que as suítes nunca exercitaram. Se a decisão for manter as suítes como estão, o modelo declarado tem de mudar. Não há atalho que derive a identidade do catálogo.

## 4. Plano de testes atualizado (substitui o §2.6 do P4-R1)

Contraprovas que alteram dono, ACL ou papéis: **só** em base descartável de laboratório (suíte) ou no novo ensaio. Nunca no `ensaio-016a`.

| # | Caso | Esperado |
|---|---|---|
| T1 | Base no modelo suportado (`migrador_nekkora` dono do banco, 016 rev 5 aplicada elevada, depois `NOSUPERUSER`): §19 inteira de novo | código 0 até o fim |
| T2 | Dono incorreto: `prevent_*` → dono operacional; interna → migrador; schema/tabela/índice → outro; `sslinfo` membro → superusuário | reprova em (b) ou (c) |
| T2b | **Dono do banco inesperado fora das listas:** `CREATE ROLE dono_inesperado; ALTER DATABASE … OWNER TO dono_inesperado`; também `admin_cluster` | reprova em (a2) |
| T2c | Identidade declarada ausente do cluster (base sem `migrador_nekkora`) | reprova em (a1) |
| T3 | EXECUTE indevido: `GRANT` a serviço, `op_*`, `nekkora_ops_mfa`, PUBLIC; `GRANT migrador_nekkora TO op_x`; `WITH GRANT OPTION` | reprova em (c) ou (d) |
| T4 | Permissão legítima retirada (`REVOKE` da `sslinfo` do dono operacional) | reprova (ACL faltante) |
| T5 | `sslinfo` e gatilhos funcionando (B6, B8a, imutabilidade) | inalterados |
| T6 | `testar-016` no novo ensaio | B5 com `invariantes_016=OK` |
| T7 | B6–B11 no novo ensaio | iguais ao P4 |
| T8 | Sintéticos `08` (40) e controles `08b_*` contra o modelo implementado (hashes (a)/(b)/(c)) | 40/40; controles reprovam |
| T9 | B12 local: 30 testes e 6 mutações | 30/30; cada mutação reprova |
| T10 | **B12 em laboratório Docker descartável**, com recursos criados para o teste e nunca recursos protegidos reais: volume protegido compartilhado com container do ensaio; volume anônimo compartilhado; container protegido que ganha montagem; mudança de classificação entre fotografias; imagem sem rótulo re-taggeada; container `--rm` que some durante a coleta | FALHA ou OK conforme os testes locais |

## 5. P4-R2: o que muda no plano

**0. Decisões**

| Decisão | Opção recomendada |
|---|---|
| D-B5-1 | donos da alternativa A, com as exceções `prevent_*` e `sslinfo` documentadas na matriz §1/§2 |
| **D-B5-2** | identidade declarada `migrador_nekkora`, `datdba` conferido contra ela, modelo suportado no cabeçalho; produção pendente |
| D-B5-3 | revisão 5 para novo ensaio |
| D-B12 | coletor rev. 2 |
| — | adaptar as suítes ao modelo suportado (§3) |

**1. Repositório**
- Aplicar os patches.
- Emendar a matriz-016 e `IMPLANTACAO.md:12`.
- Suítes no modelo suportado (T1, T2–T4, T2b, T2c).
- T8, T9 e T10 (este com laboratório Docker próprio).
- `validar-config`: 111/0 esperado.

**2. Laboratório, com nova autorização**
- Novo ensaio (`ensaio-016b`) com a linha da revisão 5.
- T5–T7.

O `ensaio-016a` e seu histórico Flyway **não** recebem a revisão 5.

## 6. Estado preservado nesta revisão

**Feito, só leitura:**
- consultas READ ONLY (`sql/07`, `08`, `08b_*`, `transaction_read_only=on`);
- inspeções Docker (`ls`/`inspect`) da coleta real.

**Não feito:** alteração de dono, ACL, papel, dados, migration, histórico ou recurso Docker. Nada de commit, stage ou push.

**Ambiente:** como deixado pelo P4-R1. O Postgres do `ensaio-016a` está rodando (`docker start` autorizado no P4-R1); os outros 4 containers continuam `exited`.

**Verificações:**
- 016 original: `25bef818…`;
- pacote P4: `41dfe24b…`, e pacote P4-R1: `3b893b72…`, ambos inalterados.

**O que ainda depende do ambiente:** só os reexames READ ONLY das propostas (`07`, `08`). As conclusões já estão nas evidências.
