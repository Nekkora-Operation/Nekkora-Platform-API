# P3-R015 — Regressão do caminho 015 e H02 integrado

Data: 2026-09-29. Referência: `homologacao-016-p3itd-20260929T144257Z.zip` (SHA-256
`1b1a366b0f0cc6baecbe87c8143d93bf75d1007967d0414c1ea0ab1735940642`, manifesto 85/85).

Evidência: `ops/homologacao/saidas/p3r015-20260929T160508Z/`.

**Nenhuma janela da 016 foi aberta; a 016 não foi aplicada; `ensaio-016a` não existe.** A linha operacional da 016
não foi ativada, e nada foi commitado. Os recursos protegidos foram preservados (§6). A conclusão depende da revisão:
a P3-IT **não** é declarada encerrada aqui.

## 1. Resultado por etapa

| Etapa | Classificação | Tipo | Evidência |
|---|---|---|---|
| R0 — preparação | **COMPROVADO** | real | `R0/` (baseline, inventário, portas excluídas, desenho **antes** das edições) |
| R1 — configuração | **COMPROVADO** | estático + recusas reais | `R1/validar_config.txt` (107 OK / 0 FALHA), `R1/recusas_reais_sobreposicao.txt` |
| R2 — criação 015 em sub-rede de laboratório | **COMPROVADO** | **execução real** | `R2_criar_015/` |
| R3 — I8 (teste original, subcasos 1–6) | **COMPROVADO** | **execução real** | `R3_I8/` |
| R4 — H09 via `testar`, incluindo (d) | **COMPROVADO** | **execução real** | `R4_H09/` |
| R5 — H02 normal via `testar` | **COMPROVADO** | **execução real** | `R5_H02_normal/` |
| R6 — H02 com interrupção, recuperação e reexecução + contraprova integrada | **COMPROVADO** na tentativa 2. A tentativa 1 falhou num subcaso por **defeito da asserção** (§4) | **execução real** | `R6_h02_integrado/`; tentativa 1 em `R6_tentativa1_*` |
| R7 — encerramento | **COMPROVADO** | real + teste isolado | `R7/`, `R7_declaracao_isolada/`, `Z_destinacao/` |

Os quatro bloqueios pendentes da P3-IT têm execução real nesta missão: o caminho 015, a I8, a H09(d) e o H02
integrado.

## 2. Desenho implementado e arquivos alterados

Desenho registrado antes das edições: `R0/desenho.txt`.

| Arquivo | Alteração | Justificativa |
|---|---|---|
| `homologacao.sh` (`criar`) | `HOMOLOG_ATE=015` com sub-redes fora do padrão **só** em `ensaio-*` e com **as duas** sub-redes próprias. Gera `pg_hba.conf`/`nginx.conf` do caminho 015 com o prefixo do ambiente em `saidas/<id>/config`. Registra `HOMOLOG_REDE_015_ENSAIO`, `HOMOLOG_CONFIG` e `HOMOLOG_COMPOSE_EXTRA=compose.015-rede.yml` no `ambiente.env`. Passa o prefixo a `segredos.py` também no 015 | O 015 dependia de sub-redes ocupadas pela `hml03`. Com o padrão, o resultado é idêntico ao anterior; fora de `ensaio-*`, recusa |
| `compose.015-rede.yml` (novo) | Monta só `pg_hba.conf` e `nginx.conf` gerados | Os estáticos (usados pela `hml03`) não mudam |
| `lib/redes.py` | `trocar-prefixo`: troca o prefixo /24 de todos os endereços de um arquivo estático; recusa sem troca, prefixos iguais ou inválidos, ou se sobrar o antigo | Mesmas regras do 015, só o prefixo trocado; nada da 016 |
| `lib/estado.sh` | `sintetica_declarada` (declaração explícita de ensaio de **uma** versão sintética 9xx) e uso em `ambiente_apto` | Liberação com a sintética declarada. Declaração inválida bloqueia |
| `testes/executar.sh` (`h02`) | Com declaração válida: sintética nas janelas concluídas esperadas; interrompidas = número declarado, todas com recuperação própria; declaração anterior à primeira janela da sintética; histórico e reexecução com o conjunto de laboratório. `h02_janelas` **inalterada** (algoritmo aprovado) | H02 integrado com versão sintética, sem enfraquecer a comparação das migrations reais |
| `testes/p3it_runner.sh` | Caso `R015H` (sequência obrigatória de R6); captura completa da recusa do `implantar` (correção da tentativa 1) | Suporte de ensaio, `ensaio-*` |
| `testes/instalacao_incompleta.sh` | Repassa explicitamente `HOMOLOG_REDE_INTERNA/BORDA` e `HOMOLOG_PORTA` ao `criar` e registra os valores efetivos (confere com o `ambiente.env`). Subcasos inalterados | I8 no laboratório com a `hml03` preservada |
| `testes/validar_config.sh` | 015 em sub-rede de ensaio (geração, regras idênticas salvo o prefixo, nada da 016, render do overlay, montagens) e recusas do `criar` (fora de `ensaio-*`, só a interna, sobreposição, prefixo inválido) | R1 |
| `README.md` | Linha da I8 corrigida: o teste **não** destrói o ambiente (dizia que destruía) e usa parâmetros de laboratório | Documentação |

**Dependências de endereço rastreadas:**
- `compose.homolog.yml`: sub-redes e IPs .5/.10/.11/.20/.21/.30/.31/.32, já parametrizados.
- `pg_hba.conf` e `nginx.conf` estáticos: gerados.
- `segredos.py`: proxy confiável `<prefixo>.5`.
- `executar.sh`: usa o `ambiente.env`.
- Certificados: SAN por **nome** (postgres, redis, host público), sem IP.

## 3. Resultados (execução real, salvo indicação)

**R1.**
- `validar-config`: 107 OK / 0 FALHA. Casos novos:
  - geração com 6 e 2 endereços trocados, sem prefixo antigo restante;
  - regras idênticas aos estáticos salvo o prefixo;
  - nenhuma regra da 016;
  - o overlay renderiza e monta **só** os gerados;
  - sem `ssl_ca_file`, `ident_file`, operação ou transporte;
  - recusas antes de recursos: fora de `ensaio-*`, só a interna fora do padrão, sub-redes iguais, prefixo inválido.
- Recusas **reais** de sobreposição, nada criado:
  - contra `10.252.11.0/24` (rede da `hml03`, containers **parados**);
  - contra `172.18.0.0/16` (prefixo de tamanho diferente).

**R2** (`ensaio-p3r015-a`, 10.252.70/71, porta 56711; a porta estava fora das faixas excluídas do Windows):
- `criar` real com código 0: 001–015 no Flyway, janelas 011/012/013 abertas e fechadas `0|0|0`,
  `pos_implantacao` executada, LIBERADO;
- os containers montam os `pg_hba`/`nginx` **gerados**; IPs 10.252.70.x; `NEKKORA_PROXIES_CONFIAVEIS=10.252.70.5/32`;
- sem 016 (sem arquivo na cópia, sem `ops`, sem papéis `nekkora_ops_*`, sem linha 016 no histórico);
- TLS verificado (`ssl_verify_result=0`); `GET /api/v1/csrf` 200, Portal 401.

**R3** (`ensaio-p3r015-i8`, 10.252.72/73, porta 56712): o `instalacao_incompleta.sh` **original**, 6/6 subcasos:
1. criação interrompida (`kill -9` do criar e do runner, `docker kill` do Flyway) com a 011 aberta;
2. BLOQUEADO;
3. `recuperar` rc 0 e `0|0|0`, mas CONTINUA BLOQUEADO (faltam 011–015);
4. `estado` BLOQUEADO só pela instalação; `testar` recusado;
5. postgres parado → BLOQUEADO por consulta que falhou;
6. `implantar` aplica as 5 pendentes com revogação conferida → LIBERADO (001–015, validate).

Os parâmetros efetivos conferem com os pedidos.

**R4** (H09 via `testar`, `HOMOLOG_TESTES=h09`, código 0; o H09 verifica cada desfecho, não só o término do
comando):
- **(a)** id em uso recusado, recursos iguais;
- **(b)** checksum divergente da 015 detectado (`checksum mismatch`);
- **(c)** falha sem elevação para com rc 2, sem nova tentativa;
- **(d)** falha **dentro** da janela real da 900: `inicio versao=900` e `fim … revogado … (0|0|0) [falha]`, sem
  retry.

Depois: histórico idêntico (versões, sucesso, checksums), sem linha 900, tabela de ensaio ausente, runner sem
repair/baseline/clean. Detalhe não secreto em `R4_H09/detalhe_teste/`.

**R5** (H02 via `testar`, antes de qualquer sintética): histórico = cópia 001–015; janelas concluídas 011 012 013; 0
interrompidas; reexecução sem elevação; migrador `0|0|0`; código 0.

**R6** (`ensaio-p3r015-h2`, 10.252.78/79, porta 56714; ambiente dedicado, separado de I8/H09):
1. **Declaração antes da execução.** `ensaio_sintetica.txt` às 16:22:21Z, antes da primeira janela 950 (16:22:25.834Z).
   Sintética `950_p3r015_sintetica.sql` (`CREATE TABLE p3r015_950; INSERT 950`), fora de `db/migrations`, SHA-256
   `39c9486a…29a2`, id exato, 1 interrompida esperada. Conjunto `migracoes_ensaio` = cópia + sintética, com hashes
   registrados. Lista `950 <sha256> ensaio-p3r015-h2`. Pré-voo sintético: tabela ausente e 950 fora do histórico.
2. **Pausa.** Janela 950 aberta pelo runner (`1|0|0`) com pausa `antes-do-flyway@950`. `kill -9` **só** no PID do
   runner deste ensaio, conferido como dono da trava. Janela residual.
3. **Bloqueio.** BLOQUEADO (trava local, `1|0|0`); `testar` recusado; `implantar` recusado com código 3 ("implantação
   interrompida neste host").
4. **Recuperação.** `recuperar` real → `0|0|0`. Reconciliação: 950 **não aplicada** (fora do histórico, sem tabela).
5. **Retomada** pelo runner: pré-voo OK, janela concluída, 950 aplicada **uma vez** (checksum 2044851854), tabela com
   950, `0|0|0`, LIBERADO.
6. **H02 completo** via `testar`, código 0:
   - concluídas 011 012 013 950;
   - 1 interrompida, `RECUPERADA em 16:22:34Z`;
   - histórico = cópia + 950;
   - reexecução sem elevação.
7. **Contraprova integrada.** O **único** registro de recuperação desta execução (SHA-256 `584aa138…0b41`) foi movido
   para `R015H_8_area_fora_do_caminho/`.
   - H02 **REPROVADO** como resultado de teste: resumo `H02|FALHA`, código 1, e o motivo é só
     `INTERROMPIDA|950 … SEM RECUPERACAO PROPRIA`, com o migrador `0|0|0`.
   - Restaurado **byte a byte** (hash idêntico), H02 volta a OK.

   Nada no banco nem nos logs foi alterado. O harness distingue resultado de teste de falha técnica: sem novo
   diretório de testes, reporta `TECNICA`.

**R7.**
- `h02_janelas`: os 15 casos isolados de P3-ITd → 15/15, e a função é byte a byte a aprovada.
- Declaração (teste isolado da função real `sintetica_declarada`): 11/11. Válida → aceita; sem declaração →
  comportamento normal. **Recusada:**
  - fora de `ensaio-*`;
  - com outro id;
  - com a versão 016;
  - com SHA-256 divergente;
  - com arquivo extra no conjunto;
  - com migration real adulterada no conjunto;
  - com o conjunto errado;
  - com campo desconhecido;
  - sem `registrada_em`.
- `validar-config` final: 107/0.

## 4. Tentativas falhas preservadas

| Tentativa | Causa | Correção |
|---|---|---|
| R6 tentativa 1 (`ensaio-p3r015-h`) | Todos os subcasos OK, exceto `R015H_4`, que falhou por **defeito da asserção**: a saída do `implantar` foi truncada com `tail -3`, cortando a linha de recusa. O runner estava correto: `implantar` rc 3, e o registro real está em `implantacao-20260929T161819Z/implantacao.txt` do ambiente | Saída completa em arquivo próprio e asserção por rc=3 **e** mensagem. Repetição integral em ambiente novo (`ensaio-p3r015-h2`), aprovada |
| R7, primeira execução dos 15 casos | Caminho errado do script (evidência de P3-ITd); não rodou | Caminho correto `p3itd-20260929T144155Z/`. No resumo anterior eu havia citado `…144039Z`; o nome correto é **`p3itd-20260929T144155Z`** |

## 5. Janelas e `0|0|0`

`0|0|0` = soma dos 5 atributos privilegiados do migrador | participações envolvendo o migrador | sessões do migrador.

| Ambiente | Janelas |
|---|---|
| `ensaio-p3r015-a` | 011, 012, 013 (criar); 900 da H09(d), revogada |
| `ensaio-p3r015-i8` | 011 interrompida pela I8 e recuperada; 011–013 na conclusão |
| `ensaio-p3r015-h` (tentativa 1) | 011–013; 950 interrompida, recuperada e concluída |
| `ensaio-p3r015-h2` | 011–013; 950 interrompida, recuperada e concluída |

Estado final dos quatro ambientes antes do descarte: `0|0|0`. Nenhuma janela da 016.

## 6. Recursos, inventário e destinação

- Inventário antes: `R0/inventario_antes.txt`. Depois: `Z_destinacao/inventario_depois.txt`. **Containers
  (inclusive estado e `StartedAt`), redes/sub-redes, volumes e imagens idênticos.**
- Cache de build do Docker: 70 → 72 entradas (preservado, declarado). Cache local do Maven preservado.
- **Destinação** (`Z_destinacao/destinacao.txt`):
  - os 4 ambientes pelo `destruir` confirmado, removendo containers, volumes nomeados, redes e as imagens
    `:03bc72c` rotuladas;
  - os volumes anônimos `/data` do Redis, **registrados por id antes do `destruir`** e removidos só depois de
    conferido que nenhum container os usava;
  - segredos `~/.nekkora/homologacao/ensaio-p3r015-*`;
  - o clone de laboratório.

  Tentativas de `criar` recusadas (R1) não criaram nada.
- `hml03`, banco principal, bancos de desenvolvimento e `nk016-smoke` não foram tocados.
- **Clone de laboratório.** O `criar` exige fontes principais da API sem alteração não commitada, e essa guarda foi
  mantida. Por isso as imagens foram construídas de `03bc72c` (API de HEAD). A sobreposição (`R0/clone_laboratorio.txt`)
  inclui as ferramentas finais **idênticas** à árvore e exclui as fontes principais P1c da API e os testes de mail
  que dependem delas. Nenhuma regressão da API é atribuída a esta missão.

## 7. Git e bytes protegidos

- HEAD `dd74a8f`, branch `feat/mfa-emergencia`, nada em stage, antes e depois (`R0/baseline.txt`,
  `Z_destinacao/git_depois.txt`).
- Novos no status: `lib/estado.sh` e `testes/instalacao_incompleta.sh` modificados, `compose.015-rede.yml` novo. Os
  demais arquivos alterados já estavam modificados/não rastreados.
- `elevacao.txt` operacional `eb98911a…0e6d` (só 011–013) e todas as migrations (016 `25bef818…be61`) com **bytes
  idênticos** antes e depois.

## 8. Continuam separados

D-ELEV e ativação da linha 016; `ensaio-016a` e P4; B4 completo; B9, B10/B11; e-mail real; frontend; commit/push;
F15–F17 (procedimentos na §11 do relatório P3-IT).
