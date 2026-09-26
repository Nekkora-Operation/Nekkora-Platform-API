-- MUTACAO m1 (teste do runner; NUNCA numa base real): remove a trigger que impede reabilitar/alterar sessao.
-- O t02 PRECISA falhar.
DROP TRIGGER trg_user_sessions_prevent_reactivation ON user_sessions;
