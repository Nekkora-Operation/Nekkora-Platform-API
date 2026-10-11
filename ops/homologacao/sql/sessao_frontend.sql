-- Expiracao DECLARADA de sessao por prazo absoluto (ensaio-frontend, F1.5 P-A) - papel: admin_cluster (administrador
-- do cluster do ENSAIO, socket local; nao e janela de elevacao). Provoca expiracao SEM reduzir prazo global da aplicacao
-- e SEM tocar outras contas: SO as sessoes ATIVAS (revoked_at nulo) da conta exclusiva "expira@demo.nekkora.test" da
-- organizacao "Nekkora Demo". Recebe -v usuario (user_id), organizacao (organization_id) e tipo.
--
-- tipo = absoluta: expires_at = created_at + 1 ms (no passado). E so ENCURTAR o prazo: o trigger da 014
--   (prevent_user_session_reactivation) proibe estender expires_at e mantem created_at, o marcador de MFA e o CSRF
--   imutaveis - esta operacao nao toca nenhum deles; o CHECK da 002 (expires_at > created_at) continua valendo.
-- Inatividade NAO e simulada aqui: como created_at e imutavel e last_seen_at >= created_at, envelhecer last_seen_at
--   exigiria contornar a regra do banco. A inatividade e provada com o relogio real (jornadas --inatividade-real).
-- Imprime SESSOES_ENVELHECIDAS=<n>.
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('nekkora_sessao.usuario', :'usuario', true) IS NOT NULL AS u_ok,
       set_config('nekkora_sessao.org', :'organizacao', true) IS NOT NULL AS o_ok,
       set_config('nekkora_sessao.tipo', :'tipo', true) IS NOT NULL AS t_ok \gset
DO $$
DECLARE
    u uuid := current_setting('nekkora_sessao.usuario')::uuid;
    o uuid := current_setting('nekkora_sessao.org')::uuid;
    t text := current_setting('nekkora_sessao.tipo');
    n int;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users x JOIN organizations g USING (organization_id)
                    WHERE x.user_id = u AND x.organization_id = o AND x.email = 'expira@demo.nekkora.test' AND g.name = 'Nekkora Demo') THEN
        RAISE EXCEPTION 'expiracao recusada: so a conta exclusiva expira@demo.nekkora.test da organizacao Nekkora Demo';
    END IF;
    IF t <> 'absoluta' THEN
        RAISE EXCEPTION 'tipo invalido (so absoluta; inatividade e provada com o relogio real): %', t;
    END IF;
    UPDATE user_sessions SET expires_at = created_at + interval '1 millisecond'
     WHERE user_id = u AND organization_id = o AND revoked_at IS NULL AND expires_at > now();
    GET DIAGNOSTICS n = ROW_COUNT;
    PERFORM set_config('nekkora_sessao.n', n::text, true);
END $$;
SELECT 'SESSOES_ENVELHECIDAS=' || current_setting('nekkora_sessao.n');
COMMIT;
