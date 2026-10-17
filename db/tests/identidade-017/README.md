# Suíte de banco da migration 017 (identidade, cadastro web e entrada geral)

Valida a `db/migrations/017_identidade_cadastro.sql`, que é **candidata**. Desenho: `docs/relatorios/2026-10-11-cadastro-entrada-geral-desenho.md`, §10.1.

Roda em três clusters PostgreSQL 16 descartáveis e isolados:
- containers próprios, com `--network none`, sem porta publicada e sem volume nomeado;
- nunca toca a stack principal, o hml03 ou o ambiente preservado.

```sh
db/tests/identidade-017/run.sh
TESTE_PROJETO=nekkora-id017b db/tests/identidade-017/run.sh
```

Requisitos: Docker, bash, Python 3. No sucesso, os containers são removidos. Na falha, ficam no ar e o comando de remoção é impresso. Duração típica: 3 a 4 minutos.

Os arquivos entram no container por `docker cp`, não por bind mount. No Docker Desktop para macOS, o entrypoint tratou o script montado como executável e falhou com `bad interpreter`. A cópia preserva o modo 644, e o script é carregado.

## Modelo de aplicação

É o mesmo da 016: `migrador_nekkora` é dono do banco, com SUPERUSER só nas versões 011, 012, 013, 016 e **017**, e BYPASSRLS só na 014. Cada janela é retirada e conferida em `0|0|0`. As invariantes rodam **depois** da retirada.

## A. Instalação limpa 001–015 + 017

1. **Catálogo** comparado com 001–015 no mesmo cluster.
   - Só são aceitos acréscimos ligados à identidade.
   - As **únicas** remoções aceitas são as versões antigas de `enforce_user_session_mfa_on_insert()` e `prevent_user_session_reactivation()`, substituídas pela 017.
   - São exigidas 10 tabelas novas e 11 políticas `identity_function_context`.
2. **Invariantes** da 012 (§15), da 013 (§8), da 014 (§8) e da 017 (§12).
   - A 014 tem uma adaptação única e conferida: a contagem de "exatamente uma política" nas tabelas de MFA ignora `identity_function_context` e `ops_function_context`.
3. **Comportamento com os papéis reais.** Os scripts rodam como `nekkora_platform`, por SQL direto, sem `AuthService` e sem `AccountRepository.lock`.

| Grupo | Arquivo | Cobre |
|---|---|---|
| fixture | `f01`–`f04` | bootstrap; três organizações pelo `nekkora_provisioning`; contas e identidades; vínculos pela `identity_link_account`. Desde a R3 a sessão de identidade **só nasce por função** (`identity_login_complete` ou `identity_mfa_confirm_first`); a `f03`, de superusuário declarado, cria só o que nenhuma função produz de propósito (sessão expirada, sessão de identidade sem fator, recuperação retroativa) |
| A | `t_a_sessao_org.sql` | nascimento da sessão de organização, dois ramos (§4.4) |
| B | `t_b_fechamento.sql` | as sete escritas da entrada local, em conta vinculada e não vinculada (§4.5, camada 2) |
| C | `run.sh` + `t_conc.sql` | concorrência com a vinculação; observador confirma a espera de lock |
| C′ | `t_c2_contorno.sql` | o papel da aplicação não contorna: `identity_users`, `DISABLE TRIGGER`, `session_replication_role`, `SET ROLE` |
| D | `t_d_recente.sql` | autenticação recente, T0 e T1; marcas no futuro recusadas |
| E | `e_check.sql`, `e.sh` | E-1..E-3 e "entrada local viva" **depois de cada cenário**, numa sessão de superusuário separada |
| G | `t_g_restrita.sql` | sessão restrita à recuperação, recusada no banco |
| H | `t_h_fator.sql`, `t_h_dono.sql` | fator irreversível, imutável inclusive para o dono e para o superusuário |
| I | `t_i_criacao.sql`, `t_i_verifica.sql` | criação de organização não anexa conta existente (`xmin` forçado na mesma transação) |
| J | `t_j_contexto.sql` | auxiliares falham fechado fora do contexto |
| K | `run.sh` + `t_conc.sql` | concorrência pela mesma identidade |
| L | `t_l_*.sql` | cada função sem a sua prova; instantâneo md5 de `public` idêntico antes e depois |
| M | `m_privilegios.sql`, `m_esperado.txt` | matriz **completa** de privilégios efetivos, revisada linha a linha |
| N (R1) | `t_n_imutabilidade.sql` | exceção terminal estrita; tokens e `user_credentials.user_id` imutáveis, em conta não vinculada também; controles positivos no formato do Java atual; vinculação com estado vivo |
| R (R1) | `run.sh` + `t_conc.sql` | desafio válido na primeira leitura e invalidado ou expirado enquanto a função espera o lock: nenhum fator, código ou confirmação |
| V (R2) | `run.sh` + `t_conc.sql` | a espera vem **depois** da primeira conferência (lock do fator, do pendente, do fator novo, de uma linha do fechamento) e a prova vence durante ela: nada muda |
| P (R2) | `t_p_isolamento.sql` | RLS por identidade em leitura e escrita, nas tabelas filhas; sem contexto, outra identidade, reuso da conexão; `identity_credentials` sem `SELECT`; funções de consulta só por chave exata |
| S (R3) | `t_s_senha_login.sql`, `t_s_superusuario.sql`, `run.sh` (V-5, V-6) | conclusão do login: finalidade, credencial vigente sempre, re-hash, prova de senha obsoleta, uso do fator ou do código, sessão no mesmo comando, rollback integral; troca de senha que invalida também os desafios pendentes; espera nas funções novas |
| Q (R4) | `t_q_prep_prov.sql`, `t_q_prep.sql`, `t_q_emissao.sql`, `run.sh` (Q-C1 a Q-C8) | emissão da sessão de organização da conta vinculada: elegibilidade da conta, da organização (O4, dedicada) e do vínculo interno conferida no `INSERT` depois de mudanças posteriores à resolução inicial (Q-1 a Q-4, cada uma com controle positivo depois da reativação); desativação confirmada durante a espera da emissão (Q-C1 a Q-C3, recusa) e emissão confirmada antes da desativação (Q-C4 a Q-C6, a desativação espera e encontra a sessão); ramo 1 sem condição nova (Q-5) e sem lock novo (Q-C7), com o controle de que o ramo 2 espera (Q-C8). R4b: emissão vinculada recusada fora de READ COMMITTED (Q-RR1, o snapshot de REPEATABLE READ fixado antes da desativação do vínculo; Q-RR2 SERIALIZABLE; Q-RR3 REPEATABLE READ sem mudança), o mesmo cenário recusado pela reconferência em READ COMMITTED (Q-RC1), controle positivo em READ COMMITTED (Q-RC2) e ramo 1 aceito em REPEATABLE READ e SERIALIZABLE (Q-R1) |
| T (R5) | `t_t_r5.sql`, `run.sh` (T-E) | `identity_org_creation_allowed`, **só indicação** (`canCreateOrganization`): controle positivo; token nulo, fora do formato ou desconhecido; sessão **expirada**, **revogada** e **restrita** (mesmo com step-up); identidade suspensa; teto de 10 organizações criadas, acompanhado a cada criação, e a criação recusando por conta própria a 11ª; verdadeiro na consulta não dispensa o step-up da criação; responde em transação READ ONLY, é `STABLE` e `SECURITY DEFINER`. Eventos 13 e 14 aceitos; 0 e 15 recusados pelo CHECK |
| mutações | `run.sh`, `sql/mutacoes/` | **precisam** reprovar: M1 (trigger sem `FOR UPDATE`), M2 (auxiliar sem a recusa da sessão restrita), M3 (sem reconferência do desafio depois do lock e sem validade no consumo), M4 (sem validade no consumo), M5 (vinculação com o corpo da R1), M6 (login sem a conferência da credencial vigente), M7 (troca de senha sem invalidar os desafios), M8 (trigger de nascimento da sessão sem o lock da organização, R4), M9 (auxiliar sem a reconferência da elegibilidade, R4), M10 (trigger sem a exigência de READ COMMITTED, R4b), M11 (consulta de criação sem a recusa da sessão restrita, R5), M12 (consulta de criação sem o teto, R5). Depois são restauradas e o catálogo volta idêntico |

## B. Atualização de base populada 015 → 017

1. Massa da `atualizacao-populada/p01` e da `mfa-emergencia-016/p02`.
2. Instantâneo antes e depois da 017: **idêntico**. Nenhuma sessão ativa revogada e a coluna nova vazia.
3. Catálogo igual ao da instalação limpa, e invariantes depois da retirada.
4. Uma conta atual com fator abre sessão pelo ramo 1; uma conta atual sem fator continua recusada.
5. Restauração lógica com `pg_dump -Fc` e `pg_restore --exit-on-error`, com contagens idênticas.

## C. Compatibilidade com a 016

001–017 instaladas juntas, com as invariantes da 012, 013, 014, 016 e 017. Controles positivos (R1): a fixture vincula contas com a 016 presente, e o bloco de efeitos do reset operacional, extraído do arquivo da 016, é executado com o papel `nekkora_ops_mfa_owner` e aceito. A ordem de aplicação em base persistente continua uma decisão em aberto.

## D. Atualização de base populada **com a 016** → 017 (R3, `parte_d.sh`)

1. Base 015 → 016 com a massa das suítes anteriores.
2. **Massa da 016 pelos caminhos reais.** Operadores com certificado de cliente TLS de 15 minutos, registrado pelo papel `nekkora_ops_ca` (IdP simulado, mesmo `pg_hba` com `cert map=ops` dos testes Java), cadastrados e habilitados pela função; contatos; abertura por retorno; verificação; pré-aprovação; fluxo de banco dos avisos sob `nekkora_platform` (emissão, reivindicação, resultado de entrega **simulado** por `ops_notice_delivery_result`; sem remetente Java, transporte ou e-mail); confirmação do titular; aprovação final. As duas fixtures temporais **declaradas** são as mesmas da 016 (`OpsBanco` e homologação): contato envelhecido 31 dias e solicitação recuada 24 h + 1 min. Estados antes da 017: APROVADA e VERIFICADA.
3. Instantâneo de `public` **e** `ops`, aplicação da 017 e instantâneo de novo: idêntico. Nenhuma sessão revogada.
4. **Caminhos da 016 depois da 017:** registro de certificado, execução de S1 (os efeitos do reset passam pelas triggers da 017), consulta, pré-aprovação de S2 e o mesmo fluxo de banco dos avisos, com entrega simulada. O titular resetado volta pela porta antiga, com fator novo e sessão.
5. Invariantes da 012, 013, 014, 016 e 017; catálogo de `public` e de `ops` igual a uma base limpa 001–017 no mesmo cluster; restauração lógica.

`TESTE_SO_D=1` roda só esta parte (desenvolvimento).

## Fora desta suíte

Java (`IdentityRepository`, `IdentityAuthService`, `IdentityFilter`, rotas e testes HTTP), conforme a §11 do desenho.
