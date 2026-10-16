-- 0005_catalog_device.sql — the two tables the client reads directly, the
-- supporter-programme answers this backend does not have, and the device link.

-- ------------------------------------------------- addons and plugins ------
-- These two are the only datasets the desktop reads with a plain filtered
-- select (`.from("addons")`) instead of an RPC. A select returns every column,
-- so the view projects exactly the columns the client decodes — a stray
-- `user_id` would be an unknown key in `AddonRow`, which is a deserialization
-- default away from an empty addon list.
create or replace view public.addons
with (security_invoker = on)
as
    select profile_id, url, name, enabled, sort_order
    from public.addon_sources;

create or replace view public.plugins
with (security_invoker = on)
as
    select profile_id, url, name, enabled, sort_order
    from public.plugin_sources;

grant select on public.addons to authenticated;
grant select on public.plugins to authenticated;

-- --------------------------------------- the supporter programme -----------
-- This catalogue has no paid tiers, so every one of these answers "nothing".
-- They exist because the desktop's own UI calls them on boot and treats a
-- missing function as an error: an empty answer is how the app is told that
-- there is no membership, no avatar art and no profile backgrounds to offer —
-- which is the truth here, and the same shape the callers already handle for a
-- reader who is not a supporter.
create or replace function public.get_my_member_access()
returns table (
    tier text,
    entitlements jsonb
)
language sql
stable
set search_path = public
as $$
    select null::text as tier, '[]'::jsonb as entitlements where false;
$$;

create or replace function public.get_my_membership_overview()
returns table (
    status text,
    tier text,
    verified_at text,
    supporter_since text,
    provider_connected boolean,
    has_subscription boolean,
    subscription_access_active boolean,
    subscription_status text,
    provider text,
    membership_level text,
    current_period_end text,
    cancels_at_period_end boolean,
    monthly_amount_cents integer,
    currency_code text,
    has_active_grant boolean,
    grant_is_lifetime boolean,
    grant_expires_at text,
    grant_kind text,
    grant_tier text,
    grant_source text,
    has_lifetime_grant boolean,
    lifetime_grant_tier text
)
language sql
stable
set search_path = public
as $$
    select
        null::text, null::text, null::text, null::text, null::boolean,
        null::boolean, null::boolean, null::text, null::text, null::text,
        null::text, null::boolean, null::integer, null::text, null::boolean,
        null::boolean, null::text, null::text, null::text, null::text,
        null::boolean, null::text
    where false;
$$;

create or replace function public.get_avatar_catalog()
returns table (
    id text,
    display_name text,
    storage_path text,
    category text,
    sort_order integer,
    is_active boolean,
    bg_color text
)
language sql
stable
set search_path = public
as $$
    select
        null::text, null::text, null::text, null::text, null::integer,
        null::boolean, null::text
    where false;
$$;

create or replace function public.get_member_profile_avatar_catalog()
returns table (
    id text,
    display_name text,
    storage_path text,
    category text,
    sort_order integer,
    bg_color text,
    asset_version integer
)
language sql
stable
set search_path = public
as $$
    select
        null::text, null::text, null::text, null::text, null::integer,
        null::text, null::integer
    where false;
$$;

create or replace function public.get_member_profile_background_catalog()
returns table (
    id text,
    display_name text,
    storage_path text,
    portrait_storage_path text,
    asset_version integer
)
language sql
stable
set search_path = public
as $$
    select null::text, null::text, null::text, null::text, null::integer
    where false;
$$;

grant execute on function public.get_my_member_access() to authenticated;
grant execute on function public.get_my_membership_overview() to authenticated;
grant execute on function public.get_avatar_catalog() to authenticated, anon;
grant execute on function public.get_member_profile_avatar_catalog() to authenticated;
grant execute on function public.get_member_profile_background_catalog() to authenticated;

-- ------------------------------------------------------------- devices -----
create or replace function public.register_current_device(
    p_installation_id text,
    p_client_name text default '',
    p_client_version text default '',
    p_platform text default '',
    p_device_name text default ''
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
    if auth.uid() is null or coalesce(p_installation_id, '') = '' then
        return;
    end if;

    insert into public.devices (
        user_id, installation_id, client_name, client_version, platform,
        device_name, last_seen_at
    )
    values (
        auth.uid(), p_installation_id, coalesce(p_client_name, ''),
        coalesce(p_client_version, ''), coalesce(p_platform, ''),
        coalesce(p_device_name, ''), now()
    )
    on conflict (user_id, installation_id) do update
        set client_name = excluded.client_name,
            client_version = excluded.client_version,
            platform = excluded.platform,
            device_name = excluded.device_name,
            last_seen_at = now();
end;
$$;

grant execute on function public.register_current_device(text, text, text, text, text) to authenticated;

-- --------------------------------------------------------- device link -----
-- "Sign in on the phone, land in the window": the window asks for a code, a
-- signed-in client approves it, the window polls until it is approved and then
-- exchanges the code for its own session. The two RPCs the *unauthenticated*
-- window calls are `security definer`, because the handshake table is owned by
-- nobody yet; they touch nothing but that table, and the nonce is what stops
-- one window from polling another's code.
create or replace function public.start_device_login_session(
    p_device_nonce text,
    p_redirect_base_url text default '',
    p_device_name text default '',
    p_device_type text default ''
)
returns table (
    device_code uuid,
    user_code text,
    verification_uri_complete text,
    poll_interval_seconds integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
    alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    generated_code text := '';
    created public.device_login_sessions;
    base_url text;
begin
    loop
        generated_code := '';
        for digit in 1..6 loop
            generated_code := generated_code
                || substr(alphabet, 1 + floor(random() * length(alphabet))::integer, 1);
        end loop;
        exit when not exists (
            select 1 from public.device_login_sessions as existing
            where existing.user_code = generated_code
        );
    end loop;

    base_url := nullif(rtrim(coalesce(p_redirect_base_url, ''), '/'), '');

    insert into public.device_login_sessions (
        user_code, device_nonce, device_name, device_type, verification_base_url
    )
    values (
        generated_code,
        coalesce(p_device_nonce, ''),
        coalesce(p_device_name, ''),
        coalesce(p_device_type, ''),
        coalesce(base_url, '')
    )
    returning * into created;

    return query
        select
            created.device_code,
            created.user_code,
            case
                when base_url is null then created.user_code
                else base_url || '/' || created.user_code
            end,
            3;
end;
$$;

create or replace function public.poll_tv_login_session(
    p_code uuid,
    p_device_nonce text
)
returns table (status text)
language plpgsql
security definer
set search_path = public
as $$
declare
    session public.device_login_sessions;
begin
    select * into session
    from public.device_login_sessions as candidate
    where candidate.device_code = p_code
      and candidate.device_nonce = coalesce(p_device_nonce, '');

    if session.device_code is null then
        return query select 'denied'::text;
        return;
    end if;
    if session.expires_at < now() and session.status = 'pending' then
        return query select 'expired'::text;
        return;
    end if;

    return query select session.status;
end;
$$;

-- What a signed-in client (the phone) calls with the code the window shows.
create or replace function public.approve_device_login_session(
    p_user_code text
)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    normalized text;
    updated integer;
begin
    if auth.uid() is null then
        return false;
    end if;

    normalized := upper(regexp_replace(coalesce(p_user_code, ''), '[^A-Za-z0-9]', '', 'g'));

    update public.device_login_sessions
    set status = 'approved', user_id = auth.uid(), approved_at = now()
    where user_code = normalized
      and status = 'pending'
      and expires_at > now();

    get diagnostics updated = row_count;
    return updated > 0;
end;
$$;

grant execute on function public.start_device_login_session(text, text, text, text) to anon, authenticated;
grant execute on function public.poll_tv_login_session(uuid, text) to anon, authenticated;
grant execute on function public.approve_device_login_session(text) to authenticated;
