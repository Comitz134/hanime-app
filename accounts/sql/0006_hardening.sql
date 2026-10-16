-- 0006_hardening.sql — who may call what.
--
-- PostgreSQL grants EXECUTE to PUBLIC on every new function, so "I granted it
-- to anon" is not the same statement as "only anon may call it" — and the
-- security advisor says exactly that. This closes it: the four functions below
-- are the ones whose privilege matters.
--
--   * `seed_new_account_profile` is a trigger, not an endpoint. Nothing should
--     reach it over `/rest/v1/rpc/`; the trigger fires as the table owner.
--   * the three device-link functions are *meant* to be reachable — the window
--     that shows the code has no session yet, which is the whole point — but
--     through the grants given in 0005, not through the PUBLIC default.

revoke execute on function public.seed_new_account_profile() from public;
revoke execute on function public.approve_device_login_session(text) from public;
revoke execute on function public.poll_tv_login_session(uuid, text) from public;
revoke execute on function public.start_device_login_session(text, text, text, text) from public;
