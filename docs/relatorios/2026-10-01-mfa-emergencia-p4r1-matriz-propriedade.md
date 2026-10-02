# Matriz de propriedade e permissões dos objetos da 016 — P4-R1 (B5.2)

**Fonte:** catálogo de `ensaio-016a` lido em 2026-10-01 entre 14:23Z e 14:26Z, pelo `admin_cluster` no caminho do runner, em `BEGIN TRANSACTION READ ONLY … ROLLBACK` (`transaction_read_only = on` registrado em cada saída). Consultas `sql/01_matriz_funcoes.sql` e `sql/02_matriz_tabelas.sql`, saídas `B5/01_*.txt` e `B5/02_*.txt`, código 0. Esta matriz é **gerada** dessas saídas por `B5/gerar_matriz.py`.

**Abreviações nas ACLs:** `M` = `migrador_nekkora`, `O` = `nekkora_ops_mfa_owner`; `X` = EXECUTE; `a/r/w/d/D/x/t` = INSERT/SELECT/UPDATE/DELETE/TRUNCATE/REFERENCES/TRIGGER. **Efetivo** = papéis não superusuários com o privilégio por `has_*_privilege` (inclui herança), sem os papéis predefinidos `pg_*`.

**Origens:** `M1` = matriz-016 §1 ("Dono das funções operacionais (public.ops_* e internas ops.*)"); `M2s` = matriz-016 §2 ("O schema ops pertence ao migrador"); `M2e` = matriz-016 §2 ("sslinfo fica em ops, com EXECUTE só para o dono operacional"); `M2t` = matriz-016 §2 e §7 (tabelas "pertencem ao migrador"; 21 no total); `M3` = matriz-016 §3 (padrão das públicas: SECURITY DEFINER, dono operacional, search_path); `C18` = 016 §18 (ALTER OWNER só fora de ssl%/prevent_%; REVOKE PUBLIC em todas); `C2` = 016 §2 (CREATE EXTENSION sslinfo SCHEMA ops pelo migrador elevado; GRANT ao dono operacional); `C12` = 016 §12 (internas: SECURITY INVOKER); `I12` = IMPLANTACAO.md:12 (migrador mantém a propriedade do que as migrations não reatribuem); `I13` = IMPLANTACAO.md:13 e bootstrap.sql:20 (backup_nekkora: BYPASSRLS + pg_read_all_data); `PG` = regra do PostgreSQL (índice e tipo de linha têm o dono da tabela).

**Avaliação:** *conforme* (dono e permissões batem com a origem citada); *divergente*; *não decidido* (a origem é ambígua ou omissa; o estado observado NÃO é tomado como o desejado).

## 1. Schema, banco e extensão

| Objeto | Categoria | Dono observado | Dono esperado (origem) | Extensão | Permissões (ACL; efetivo) | Avaliação |
|---|---|---|---|---|---|---|
| `ops` | schema | migrador_nekkora | migrador (`M2s`, `I12`) | — | `{M=UC/M,O=U/M}`; USAGE efetivo: backup_nekkora,migrador_nekkora,nekkora_ops_mfa_owner; CREATE efetivo: migrador_nekkora | conforme (USAGE de `backup_nekkora` vem de `pg_read_all_data`, `I13`) |
| `pgcrypto` 1.3 | extensão de 001–015 (confiável) | migrador_nekkora | fora do escopo da 016 | — | 36 membros, donos `admin_cluster` (extensão confiável criada por não superusuário) | fora do escopo |
| `sslinfo` 1.2 | extensão (não confiável: confiavel=false) | migrador_nekkora | não explicitado no contrato; código: migrador (`C2`, `C18`) | — | 10 membros em `ops` | não decidido (conforme ao código) |
| `nekkora` | banco | migrador_nekkora | migrador (`I12`) | — | — | conforme |

## 2. Tabelas, índices e sequências de `ops`

| Objeto | Categoria | Dono observado | Dono esperado (origem) | ACL explícita | Efetivo (fora o dono) | RLS | Gatilhos | Avaliação |
|---|---|---|---|---|---|---|---|---|
| `ops.ops_account_link_proofs` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_contact_link_proofs` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_intake_entries` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_intake_key_state` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=rw/M}` | backup_nekkora:r,O:rw | false/false | 3 | conforme |
| `ops.ops_mfa_approval_attempts` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=ar/M}` | backup_nekkora:r,O:ra | false/false | 3 | conforme |
| `ops.ops_mfa_callbacks` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_mfa_notice_results` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=ar/M}` | backup_nekkora:r,O:ra | false/false | 3 | conforme |
| `ops.ops_mfa_notice_tokens` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_mfa_notices` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_mfa_recovery_events` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=ar/M}` | backup_nekkora:r,O:ra | false/false | 3 | conforme |
| `ops.ops_mfa_recovery_requests` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_mfa_request_admins` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=ar/M}` | backup_nekkora:r,O:ra | false/false | 3 | conforme |
| `ops.ops_operator_certificates` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_operator_contact_links` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_operator_permissions` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_operator_platform_accounts` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_operators` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_origin_buckets` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| `ops.ops_registry_events` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=ar/M}` | backup_nekkora:r,O:ra | false/false | 3 | conforme |
| `ops.org_authorized_contact_events` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=ar/M}` | backup_nekkora:r,O:ra | false/false | 3 | conforme |
| `ops.org_authorized_contacts` | tabela | migrador_nekkora | migrador (`M2t`) | `{M=arwdDxt/M,O=arw/M}` | backup_nekkora:r,O:raw | false/false | 3 | conforme |
| 51 índices de `ops` | índice | migrador_nekkora | dono da tabela (`PG`) | — | — | — | — | conforme (51) |
| sequências de `ops` | sequência | — | — | — | — | — | — | nenhuma existe (0) |

Tabelas: o dono operacional `O` tem `arw` (ou `ar` nas de auditoria/fotografia e `rw` em `ops_intake_key_state`) e nunca `d/D/x/t` (conferido pelo V06 da §19). `backup_nekkora:r` vem de `pg_read_all_data` (`I13`) e é a leitura do backup lógico do B11; o V05 da §19 não o inclui na lista de serviços — ver observação O-B5-3 no relatório.

## 3. Funções públicas `public.ops_*` — 37

| Assinatura | Dono observado | Dono esperado (origem) | ACL explícita | EXECUTE efetivo | PUBLIC | Segurança / config | Gatilhos | Avaliação |
|---|---|---|---|---|---|---|---|---|
| `ops_account_proof_issue(uuid,uuid,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_platform=X/O}` | O,nekkora_platform | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_bootstrap_operator(text,text,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O}` | O | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops_ca_resolve_operator(text,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_ca=X/O}` | nekkora_ops_ca,O | false | secdef=true; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops_contact_add(uuid,text,smallint,text,uuid,text,boolean)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_contact_change_channel(uuid,smallint,text,boolean,uuid,boolean)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; sql | 0 | conforme |
| `ops_contact_link_code_issue(uuid,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_contact_remove(uuid,boolean,uuid,boolean)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; sql | 0 | conforme |
| `ops_intake_key_activate(integer)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_intake=X/O}` | nekkora_ops_intake,O | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops_intake_register(smallint,smallint,integer,text,integer,text,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_intake=X/O}` | nekkora_ops_intake,O | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops_link_contact(text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops_link_platform_account(text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops_link_remove(text,uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops_mfa_callback_register(uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_mfa_cancel(uuid,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_mfa_execute(uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_mfa_final_approve(uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_mfa_open(uuid,uuid,uuid,uuid,uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_mfa_preapprove(uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_mfa_record_failure(uuid,text,text,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_mfa_resend_notice(uuid,uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_mfa_status(uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=;lock_timeout=10s; plpgsql | 0 | conforme |
| `ops_mfa_verify(uuid,boolean,uuid,boolean,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_notice_attempt_state(uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_platform=X/O}` | O,nekkora_platform | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_notice_claim(uuid,uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_platform=X/O}` | O,nekkora_platform | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_notice_confirm(uuid,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_platform=X/O}` | O,nekkora_platform | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; sql | 0 | conforme |
| `ops_notice_contest(uuid,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_platform=X/O}` | O,nekkora_platform | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; sql | 0 | conforme |
| `ops_notice_delivery_result(uuid,smallint,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_platform=X/O}` | O,nekkora_platform | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_notice_issue(uuid,text,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_platform=X/O}` | O,nekkora_platform | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_notice_pending(integer)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_platform=X/O}` | O,nekkora_platform | false | secdef=true; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops_notice_recover(uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_platform=X/O}` | O,nekkora_platform | false | secdef=true; sp=pg_catalog, pg_temp;nekkora.fn_organization_id=; plpgsql | 0 | conforme |
| `ops_notice_stalled(integer)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_platform=X/O}` | O,nekkora_platform | false | secdef=true; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops_operator_create(text,text,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops_operator_disable(uuid)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops_operator_grant(uuid,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops_operator_revoke(uuid,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_mfa=X/O}` | nekkora_ops_mfa,O,op_hml_apr,op_hml_cad,op_hml_exe,op_hml_ver | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops_register_certificate(text,text,text,text,numeric,timestamp with time zone,timestamp with time zone,timestamp with time zone,text)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_ca=X/O}` | nekkora_ops_ca,O | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops_revoke_certificate(text,numeric)` | nekkora_ops_mfa_owner | `O` (M1, M3, C18) | `{O=X/O,nekkora_ops_ca=X/O}` | nekkora_ops_ca,O | false | secdef=true; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |

## 4. Funções internas de `ops` (não gatilho, fora de extensão) — 28

| Assinatura | Dono observado | Dono esperado (origem) | ACL explícita | EXECUTE efetivo | PUBLIC | Segurança / config | Gatilhos | Avaliação |
|---|---|---|---|---|---|---|---|---|
| `ops.alvo_inelegivel(uuid,uuid)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.ato_codigo(text)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.autorizar(uuid,smallint,timestamp with time zone)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops.condicao_avisos_demais(uuid,timestamp with time zone)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.contas_em_mudanca(uuid,uuid,uuid[])` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.contato_alterar(uuid,boolean,smallint,text,boolean,uuid,boolean)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops.contato_inelegivel(uuid,uuid,timestamp with time zone)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.contatos_elegiveis(uuid,timestamp with time zone)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.criar_avisos(uuid,uuid,uuid,smallint,uuid[])` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops.declaracao_invalida(uuid,uuid,uuid)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.demais_admins(uuid,uuid)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.encerrar(uuid,smallint,text,uuid,timestamp with time zone)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops.estado_nome(smallint)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.exigir_read_committed()` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops.fotografar_admins(uuid,uuid,uuid,uuid[],timestamp with time zone)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.limite_executado(uuid,uuid,timestamp with time zone)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.nova_tentativa(uuid,uuid,uuid,uuid,smallint)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops.operador_da_sessao()` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.papel_operador_invalido(text)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.participante_invalido(uuid,smallint,uuid,timestamp with time zone)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.registrar(uuid,uuid,uuid,text,text,text,uuid,text,numeric,uuid,smallint)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.registrar_cadastro(text,text,text,smallint,uuid,uuid,smallint,uuid,uuid,uuid,text,numeric)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.tem_vinculo(uuid,uuid)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.tentativa_vigente(uuid,uuid,smallint)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |
| `ops.travar_contas(uuid,uuid,uuid[])` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops.travar_operadores(uuid[],uuid[])` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops.usar_token(uuid,text,smallint)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 0 | conforme |
| `ops.vencida(smallint,timestamp with time zone,timestamp with time zone,timestamp with time zone,timestamp with time zone)` | nekkora_ops_mfa_owner | `O` (M1, C18, C12) | `{O=X/O}` | O | false | secdef=false; sp=pg_catalog, pg_temp; sql | 0 | conforme |

## 5. Funções de gatilho `ops.prevent_*` — 11

| Assinatura | Dono observado | Dono esperado (origem) | ACL explícita | EXECUTE efetivo | PUBLIC | Segurança / config | Gatilhos | Avaliação |
|---|---|---|---|---|---|---|---|---|
| `ops.prevent_certificate_change()` | migrador_nekkora | pendente de decisão: `M1` diz "internas ops.*" → `O`; `C18` exclui `prevent_%` → fica `M` | `{M=X/M}` | M | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 1 | não decidido (ACL só do dono; nenhum serviço, operador ou PUBLIC) |
| `ops.prevent_contact_change()` | migrador_nekkora | pendente de decisão: `M1` diz "internas ops.*" → `O`; `C18` exclui `prevent_%` → fica `M` | `{M=X/M}` | M | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 1 | não decidido (ACL só do dono; nenhum serviço, operador ou PUBLIC) |
| `ops.prevent_entry_change()` | migrador_nekkora | pendente de decisão: `M1` diz "internas ops.*" → `O`; `C18` exclui `prevent_%` → fica `M` | `{M=X/M}` | M | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 2 | não decidido (ACL só do dono; nenhum serviço, operador ou PUBLIC) |
| `ops.prevent_key_state_change()` | migrador_nekkora | pendente de decisão: `M1` diz "internas ops.*" → `O`; `C18` exclui `prevent_%` → fica `M` | `{M=X/M}` | M | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 1 | não decidido (ACL só do dono; nenhum serviço, operador ou PUBLIC) |
| `ops.prevent_link_change()` | migrador_nekkora | pendente de decisão: `M1` diz "internas ops.*" → `O`; `C18` exclui `prevent_%` → fica `M` | `{M=X/M}` | M | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 2 | não decidido (ACL só do dono; nenhum serviço, operador ou PUBLIC) |
| `ops.prevent_notice_change()` | migrador_nekkora | pendente de decisão: `M1` diz "internas ops.*" → `O`; `C18` exclui `prevent_%` → fica `M` | `{M=X/M}` | M | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 1 | não decidido (ACL só do dono; nenhum serviço, operador ou PUBLIC) |
| `ops.prevent_notice_token_change()` | migrador_nekkora | pendente de decisão: `M1` diz "internas ops.*" → `O`; `C18` exclui `prevent_%` → fica `M` | `{M=X/M}` | M | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 1 | não decidido (ACL só do dono; nenhum serviço, operador ou PUBLIC) |
| `ops.prevent_operator_change()` | migrador_nekkora | pendente de decisão: `M1` diz "internas ops.*" → `O`; `C18` exclui `prevent_%` → fica `M` | `{M=X/M}` | M | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 1 | não decidido (ACL só do dono; nenhum serviço, operador ou PUBLIC) |
| `ops.prevent_permission_change()` | migrador_nekkora | pendente de decisão: `M1` diz "internas ops.*" → `O`; `C18` exclui `prevent_%` → fica `M` | `{M=X/M}` | M | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 1 | não decidido (ACL só do dono; nenhum serviço, operador ou PUBLIC) |
| `ops.prevent_proof_change()` | migrador_nekkora | pendente de decisão: `M1` diz "internas ops.*" → `O`; `C18` exclui `prevent_%` → fica `M` | `{M=X/M}` | M | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 2 | não decidido (ACL só do dono; nenhum serviço, operador ou PUBLIC) |
| `ops.prevent_request_change()` | migrador_nekkora | pendente de decisão: `M1` diz "internas ops.*" → `O`; `C18` exclui `prevent_%` → fica `M` | `{M=X/M}` | M | false | secdef=false; sp=pg_catalog, pg_temp; plpgsql | 1 | não decidido (ACL só do dono; nenhum serviço, operador ou PUBLIC) |

## 6. Funções integrantes de `sslinfo` (em `ops`) — 10

| Assinatura | Dono observado | Dono esperado (origem) | ACL explícita | EXECUTE efetivo | PUBLIC | Segurança / config | Gatilhos | Avaliação |
|---|---|---|---|---|---|---|---|---|
| `ops.ssl_cipher()` | migrador_nekkora | pendente de decisão: contrato omisso sobre o dono; código (`C2`, `C18`) → `M` | `{M=X/M,O=X/M}` | M,O | false | secdef=false; -; c | 0 | não decidido (ACL = dono + `O`, como `C2`; o "só para o dono operacional" de `M2e` não cobre o EXECUTE implícito do dono) |
| `ops.ssl_client_cert_present()` | migrador_nekkora | pendente de decisão: contrato omisso sobre o dono; código (`C2`, `C18`) → `M` | `{M=X/M,O=X/M}` | M,O | false | secdef=false; -; c | 0 | não decidido (ACL = dono + `O`, como `C2`; o "só para o dono operacional" de `M2e` não cobre o EXECUTE implícito do dono) |
| `ops.ssl_client_dn()` | migrador_nekkora | pendente de decisão: contrato omisso sobre o dono; código (`C2`, `C18`) → `M` | `{M=X/M,O=X/M}` | M,O | false | secdef=false; -; c | 0 | não decidido (ACL = dono + `O`, como `C2`; o "só para o dono operacional" de `M2e` não cobre o EXECUTE implícito do dono) |
| `ops.ssl_client_dn_field(text)` | migrador_nekkora | pendente de decisão: contrato omisso sobre o dono; código (`C2`, `C18`) → `M` | `{M=X/M,O=X/M}` | M,O | false | secdef=false; -; c | 0 | não decidido (ACL = dono + `O`, como `C2`; o "só para o dono operacional" de `M2e` não cobre o EXECUTE implícito do dono) |
| `ops.ssl_client_serial()` | migrador_nekkora | pendente de decisão: contrato omisso sobre o dono; código (`C2`, `C18`) → `M` | `{M=X/M,O=X/M}` | M,O | false | secdef=false; -; c | 0 | não decidido (ACL = dono + `O`, como `C2`; o "só para o dono operacional" de `M2e` não cobre o EXECUTE implícito do dono) |
| `ops.ssl_extension_info()` | migrador_nekkora | pendente de decisão: contrato omisso sobre o dono; código (`C2`, `C18`) → `M` | `{M=X/M,O=X/M}` | M,O | false | secdef=false; -; c | 0 | não decidido (ACL = dono + `O`, como `C2`; o "só para o dono operacional" de `M2e` não cobre o EXECUTE implícito do dono) |
| `ops.ssl_is_used()` | migrador_nekkora | pendente de decisão: contrato omisso sobre o dono; código (`C2`, `C18`) → `M` | `{M=X/M,O=X/M}` | M,O | false | secdef=false; -; c | 0 | não decidido (ACL = dono + `O`, como `C2`; o "só para o dono operacional" de `M2e` não cobre o EXECUTE implícito do dono) |
| `ops.ssl_issuer_dn()` | migrador_nekkora | pendente de decisão: contrato omisso sobre o dono; código (`C2`, `C18`) → `M` | `{M=X/M,O=X/M}` | M,O | false | secdef=false; -; c | 0 | não decidido (ACL = dono + `O`, como `C2`; o "só para o dono operacional" de `M2e` não cobre o EXECUTE implícito do dono) |
| `ops.ssl_issuer_field(text)` | migrador_nekkora | pendente de decisão: contrato omisso sobre o dono; código (`C2`, `C18`) → `M` | `{M=X/M,O=X/M}` | M,O | false | secdef=false; -; c | 0 | não decidido (ACL = dono + `O`, como `C2`; o "só para o dono operacional" de `M2e` não cobre o EXECUTE implícito do dono) |
| `ops.ssl_version()` | migrador_nekkora | pendente de decisão: contrato omisso sobre o dono; código (`C2`, `C18`) → `M` | `{M=X/M,O=X/M}` | M,O | false | secdef=false; -; c | 0 | não decidido (ACL = dono + `O`, como `C2`; o "só para o dono operacional" de `M2e` não cobre o EXECUTE implícito do dono) |

## 7. Funções de gatilho de 001–015 usadas pelas tabelas de `ops` — 3

| Assinatura | Dono observado | Dono esperado (origem) | ACL explícita | EXECUTE efetivo | PUBLIC | Segurança / config | Gatilhos | Avaliação |
|---|---|---|---|---|---|---|---|---|
| `prevent_delete()` | migrador_nekkora | `M` (001–015) | `NULL` | todos os papéis (via PUBLIC) | true | secdef=false; sp=pg_catalog, public, pg_temp; plpgsql | 50 | fora do escopo da 016 (pré-existente; ACL nula = PUBLIC; EXECUTE de gatilho não é conferido no disparo) |
| `prevent_truncate()` | migrador_nekkora | `M` (001–015) | `NULL` | todos os papéis (via PUBLIC) | true | secdef=false; sp=pg_catalog, public, pg_temp; plpgsql | 50 | fora do escopo da 016 (pré-existente; ACL nula = PUBLIC; EXECUTE de gatilho não é conferido no disparo) |
| `prevent_update()` | migrador_nekkora | `M` (001–015) | `NULL` | todos os papéis (via PUBLIC) | true | secdef=false; sp=pg_catalog, public, pg_temp; plpgsql | 17 | fora do escopo da 016 (pré-existente; ACL nula = PUBLIC; EXECUTE de gatilho não é conferido no disparo) |

## 8. Demais funções listadas pela consulta — 4

Funções internas do PostgreSQL usadas por gatilhos de FK (`RI_FKey_*`, dono `admin_cluster`, `pg_catalog`): entraram porque a consulta inclui toda função referenciada por gatilho de tabela de `ops`. Fora do escopo.

## 9. Demais objetos pertencentes ao migrador (banco inteiro)

| Classe | Esquema | Quantidade |
|---|---|---|
| banco | nekkora | 1 |
| extensao | pgcrypto | 1 |
| extensao | sslinfo | 1 |
| funcao | ops | 21 |
| funcao | public | 67 |
| relacao_c | public | 1 |
| relacao_i | ops | 51 |
| relacao_i | pg_toast | 46 |
| relacao_i | public | 137 |
| relacao_r | ops | 21 |
| relacao_r | public | 46 |
| relacao_t | pg_toast | 46 |
| schema | ops | 1 |
| tipo_c | ops | 21 |
| tipo_c | public | 47 |

`relacao_c`/`tipo_c` são os tipos de linha das tabelas; `relacao_t`/`relacao_i` em `pg_toast` são as TOAST das tabelas de `public`. Os 46 + 67 de `public` batem com `IMPLANTACAO.md:12` (escrito antes da 016); a 016 acrescentou schema `ops`, `sslinfo`, 21 tabelas, 51 índices, 21 tipos de linha e 21 funções.

Privilégio padrão do migrador para funções: `migrador_nekkora|(banco)|f|{migrador_nekkora=X/migrador_nekkora}` (EXECUTE só do dono em funções novas).

## 10. Resumo

| Grupo | Dono observado | Quantidade |
|---|---|---|
| 1_publica_ops | nekkora_ops_mfa_owner | 37 |
| 2_interna_ops | nekkora_ops_mfa_owner | 28 |
| 3_prevent_ops | migrador_nekkora | 11 |
| 3b_prevent_public_preexistente | migrador_nekkora | 3 |
| 4_extensao_sslinfo | migrador_nekkora | 10 |
| 9_outra | admin_cluster | 4 |
