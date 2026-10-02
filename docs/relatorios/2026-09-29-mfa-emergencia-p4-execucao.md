# P4 — execução da D-ELEV e ensaio integrado da 016 em `ensaio-016a`

**ESTADO: ENCERRADO PELA §4.9 (falha em B5), PARA REVISÃO.** Data: 2026-09-29.
- Autorização: A1–A8 da proposta `2026-09-29-mfa-emergencia-d-elev-p4-missao-proposta.md`, §10 (2026-09-29T19:20:48Z).
- Instruções de execução e de retomada nesta sessão.
- Nenhuma operação fora desses termos: sem J3, sem recriação, sem repair, sem push, sem nada no repositório original.
- **Ambiente e clone preservados até a revisão.**

## 1. Resultado em uma tabela

| Passo | Resultado |
|---|---|
| P0 classificação (§3.4, 1) | OK: 8 pacotes aprovados conferidos por SHA-256. 4.029 entradas do `git status` classificadas: L = 71 arquivos (69 idênticos ao fornecedor G1r, 2 relatórios posteriores com hash) e 3.958 exclusões com motivo. 0 não classificadas, 0 divergências |
| Clone e S1/S2 (§3.4, 2–6) | Clone local sem remoto. S1 `327c4d4`: 71/71 arquivos conferidos (bytes, blob e LF), `diff dd74a8f..S1` = L. S2 `296808b`: só o `elevacao.txt`, LF `47bb9596…`, 011–013 idênticas |
| Pré-voo §4.0 | OK. Divergência do critério "108/0" decidida pela responsável (ver §2) |
| 1. `criar` com parada | OK: 001–015; janelas 011/012/013 concluídas com `0|0|0`; `parada_antes_016.txt` conforme; `HOMOLOG_COMMIT` = `327c4d4` (S1) |
| 2. Recusas B3 | OK: (a) 2, (b) 2, (c) 2, (d) 3. Em nenhuma execução houve janela ou pré-voo da 016; histórico inalterado; `0|0|0` |
| 3. J1 | OK: pré-voo 0 + `PREFLIGHT_OK`; janela aberta (`1|0|0`); `kill -9` só no PID 13883, dono da trava e filho do runner deste ensaio; sem container Flyway; nenhum processo órfão |
| 4. Bloqueio, recuperação e reconciliação | OK: BLOQUEADO; `testar`, `testar-016`, `preparar-016` e `concluir-criacao` recusados. `recuperar` terminou com código 0 e `0|0|0`. Reconciliação READ ONLY: `preflight/016.sql` com código 0 e `PREFLIGHT_OK` (7/7); histórico sem 016; sem `ops`, `sslinfo` e `ops_request_id`; catálogo idêntico (`e04b759e…`, 807). **016 NÃO APLICADA** |
| 5. J2 | OK: 016 aplicada, `fim versao=016 (0|0|0)`, `flyway validate`, **LIBERADO** 001–016. Retirada: 5 atributos `false`, 0 participações, 0 ACL de parâmetro, 0 sessões |
| 6. `concluir-criacao` | OK: SCRAM em `nekkora_ops_ca`/`ops_intake` e nos demais; volumes `operadores` e `caixa` com `nekkora 700`; serviços com TLS e HTTP 200/401 |
| 7. H02 | OK: 4 janelas concluídas (011, 012, 013, 016/J2) e **exatamente 1** interrompida (016/J1), com recuperação própria; reexecução sem elevação |
| 7. `preparar-016` | OK (SEGREDOS OK, 36 valores) |
| 7. `testar-016` (com B8b) | **Código 1.** B6, B7, B8a, B8b e B12 OK; B10 e B11 PARCIAL (limites declarados); **B5 FALHA** (§3); SEGREDOS OK (59 valores) |
| 7. `validar-config` final | 109/0 no S3 (lista operacional) |
| Encerramento §4.9 | Privilégios conferidos sem resíduo; S3 `1c2f26b` (árvore = S1, lista `7fd92fae…`); listas temporárias de B3 removidas com hash registrado; estado preservado; B12 fora do ambiente idêntico |

Janelas da 016 usadas: **2 de 2** (J1 interrompida, J2 concluída). Nenhuma outra janela.

## 2. Divergência do pré-voo (decidida)

O critério escrito "validar-config 108/0" não se cumpriu no S2: foram 106 OK e 3 FALHA. As três verificações exigem, por
construção, a lista operacional no HEAD. A responsável decidiu **"Aceitar e prosseguir"**, com o critério registrado assim:
109/0 no S1 e a lista do S2 reconhecida como `proposta-016`.

A contagem real é 109 = os 107 casos do P3-R015 + `bash -n guarda_fontes_imagens.sh` (a seção 1 roda `bash -n` em todo
`testes/*.sh`) + a seção 10. A previsão contou só a seção 10. Registro completo em
`clone/p4/P1_prevoo/criterio_validar_config.txt`.

## 3. Achado B5 — invariante da 016 depende da janela

`testar-016` reexecuta os invariantes da §19 da 016. Falhou o invariante "funções internas de `ops`: nenhum papel além do
dono e de superusuários" (`016_recuperacao_emergencial_mfa.sql`, 3163–3167), com
`EXECUTE indevido em funcao interna de ops: … :migrador_nekkora` em 21 funções.

Análise, somente leitura (`clone/p4/P9_h016/analise_B5.txt`):
- das 49 funções de `ops`, 28 pertencem a `nekkora_ops_mfa_owner`;
- as outras **21 pertencem ao `migrador_nekkora`**: as 10 do `sslinfo` (extensão criada pelo migrador elevado, no esquema
  `ops`) e as 11 funções de gatilho `prevent_*`;
- o esquema `ops`, a extensão e as 21 tabelas também pertencem ao migrador;
- a ACL dessas funções contém **só o dono** (`PUBLIC` revogado); no `sslinfo`, há também `EXECUTE` explícito para
  `nekkora_ops_mfa_owner`;
- **nenhum papel de serviço ou de operador tem `EXECUTE`.**

O invariante só exclui `nekkora_ops_mfa_owner` e superusuários. Durante a janela, o migrador é superusuário e o invariante
passa. Depois da revogação, falha pelo próprio dono.

**Classificação proposta para a revisão:** o invariante não descreve o estado final pretendido. Não encontrei vazamento de
privilégio. As duas saídas possíveis são decisão de contrato, fora do P4:
- ajustar o invariante (excluir o dono real); ou
- transferir a propriedade dessas funções.

Nada foi corrigido.

## 4. Observações

- **O1:** `validar_config.sh` falha num checkout com `core.autocrlf=true`: o `.patch` fica em CRLF e o `git apply` o recusa.
- **O2:** `h016.sh` (B12) imprimiu um erro de template do `docker inspect` (`map has no entry for key "Labels"`), provavelmente
  de um container `--rm` removido no meio da inspeção. O B12 conferiu os inventários idênticos.
- **O3:** ao iniciar o Docker Desktop, a pedido da responsável, subiram sozinhos três Postgres com `unless-stopped`:
  o principal, `portal-dev` e `portal-013`. O inventário "antes" já os contém.
- A consulta direta ao Postgres do ensaio pelo socket foi recusada pelo `pg_hba`, como previsto. Não foi contornada; as
  leituras usaram o `admin_cluster` pelo caminho do runner (`clone/p4/leitura.sh`, em transação READ ONLY).

## 5. Estado preservado

- **Ambiente:** `ensaio-016a` LIBERADO, com 5 containers rodando, volumes `pgdata`, `operadores`, `caixa` e o anônimo
  `4d6c8c64…` (`/data` do Redis, para o F17) e redes `10.252.80/81`. Porta `127.0.0.1:56720`.
- **Imagens:** `nekkora-homologacao-{platform,portal,operacao}:327c4d4`
  (`873d65c3…`, `f6625f51…`, `62e1df27…`). Jars e classes com SHA-256 em `artefatos_e_imagens.txt`.
- **Clone:** `C:/Users/nicol/nekkora/lab-p4-20260929T192708Z/nekkora-platform`, commits S1 `327c4d4`, S2 `296808b` e
  S3 `1c2f26b`, sem remoto. Push proibido. **Não descartar antes do P5**: ele é o repositório do ambiente.
- **Repositório original:** HEAD `dd74a8f`, nada em stage, `elevacao.txt` `eb98911a…` (inalterado).
- **Segredos:** `~/.nekkora/homologacao/ensaio-016a`, fora do pacote.

## 6. Evidências

- `ops/homologacao/saidas/p4-20260929T192708Z/` (fora do Git):
  - `P0/`: classificação, manifesto de L, exclusões, clone, S1, S2;
  - `clone/p4/`: pré-voo a encerramento;
  - `clone/ensaio-016a/`: saídas do runner.
- Pacote de revisão: `../relatorios/homologacao-016-p4-<instante>.zip`, com manifesto conferido em extração nova e varredura
  com controle positivo.

## 7. Pendências para decisão

1. Classificação do achado B5 e o caminho de correção (contrato da 016).
2. P5: descarte de `ensaio-016a` (F17) e, por último, do clone. Só com nova decisão.
3. Continuam pendentes para missões próprias: B4 completo, B9, restauração do B11 e o gatilho da varredura do B10.
