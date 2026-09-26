-- MUTACAO m4 (teste do runner; NUNCA numa base real) - papel do portal SEM log_error_verbosity = terse.
-- O CONTEXT com os valores de bind volta ao log do servidor quando o proprio papel liga
-- log_parameter_max_length_on_error. A busca do marcador no log (t09c) PRECISA acusar o vazamento.
ALTER ROLE nekkora_portal RESET log_error_verbosity;
SELECT 'mutacao m4 aplicada' AS resultado, setconfig FROM pg_db_role_setting WHERE setrole = 'nekkora_portal'::regrole;
