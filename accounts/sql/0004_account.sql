-- 0004_account.sql — the account itself: profiles, their pins, and the blobs
-- that ride with a profile.
--
-- The rule this file keeps: a push carries the client's *whole* set for that
-- dataset (profiles, addons, plugins, provider credentials), so the server
-- treats it as authoritative — everything not named is gone. That is what makes
-- a deletion on the phone a deletion in the window; an append-only push would
-- leave the deleted row alive on the other client forever.

-- ------------------------------------------------------------- profiles ---
create or replace function public.sync_pull_profiles()
returns table (
    id uuid,
    user_id uuid,
    profile_index integer,
    name text,
    avatar_color_hex text,
    avatar_id text,
    avatar_url text,
    profile_background_id text,
    profile_background_url text,
    uses_primary_addons boolean,
    uses_primary_plugins boolean,
    pin_enabled boolean,
    pin_locked_until timestamptz,
    created_at timestamptz,
    updated_at timestamptz
)
language sql
stable
set search_path = public
as $$
    select
        profile.id,
        profile.user_id,
        profile.profile_index,
        profile.name,
        profile.avatar_color_hex,
        profile.avatar_id,
        profile.avatar_url,
        profile.profile_background_id,
        profile.profile_background_url,
        profile.uses_primary_addons,
        profile.uses_primary_plugins,
        profile.pin_enabled,
        profile.pin_locked_until,
        profile.created_at,
        profile.updated_at
    from public.profiles as profile
    where profile.user_id = auth.uid()
    order by profile.profile_index asc;
$$;

create or replace function public.sync_push_profiles(
    p_client_max_profiles integer default 4,
    p_profiles jsonb default '[]'::jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
declare
    profile jsonb;
    pushed_index integer;
    max_index integer;
    kept_indexes integer[] := array[]::integer[];
begin
    max_index := greatest(coalesce(p_client_max_profiles, 4), 1);

    for profile in select * from jsonb_array_elements(coalesce(p_profiles, '[]'::jsonb))
    loop
        pushed_index := (profile ->> 'profile_index')::integer;
        -- An index of 0/null is a malformed payload, and one past the client's
        -- own maximum is a client asking for more profiles than it may have.
        if pushed_index is null or pushed_index < 1 or pushed_index > max_index then
            continue;
        end if;

        kept_indexes := kept_indexes || pushed_index;

        insert into public.profiles (
            user_id, profile_index, name, avatar_color_hex, avatar_id, avatar_url,
            profile_background_id, profile_background_url, uses_primary_addons,
            uses_primary_plugins, updated_at
        )
        values (
            auth.uid(),
            (profile ->> 'profile_index')::integer,
            coalesce(profile ->> 'name', ''),
            coalesce(profile ->> 'avatar_color_hex', '#1E88E5'),
            profile ->> 'avatar_id',
            profile ->> 'avatar_url',
            profile ->> 'profile_background_id',
            profile ->> 'profile_background_url',
            coalesce((profile ->> 'uses_primary_addons')::boolean, false),
            coalesce((profile ->> 'uses_primary_plugins')::boolean, false),
            now()
        )
        on conflict (user_id, profile_index) do update
            set name = excluded.name,
                avatar_color_hex = excluded.avatar_color_hex,
                avatar_id = excluded.avatar_id,
                avatar_url = excluded.avatar_url,
                profile_background_id = excluded.profile_background_id,
                profile_background_url = excluded.profile_background_url,
                uses_primary_addons = excluded.uses_primary_addons,
                uses_primary_plugins = excluded.uses_primary_plugins,
                updated_at = now();
    end loop;

    -- An empty push keeps the account as it is. Every other dataset treats an
    -- empty push as "I have nothing" and deletes — but an account with no
    -- profile at all cannot be signed into, so a client that sends nothing is
    -- a client with a bug, not a reader deleting their last profile.
    if kept_indexes = array[]::integer[] then
        return;
    end if;

    -- Profiles the client did not name are removed, with their data.
    perform public.drop_profile_data(existing.profile_index, p_origin_client_id)
    from public.profiles as existing
    where existing.user_id = auth.uid()
      and not (existing.profile_index = any (kept_indexes));
end;
$$;

-- Shared by delete-profile and by the authoritative profile push above: one
-- place that knows what "this profile is gone" means, including the delete
-- events the other clients need in order to converge.
create or replace function public.drop_profile_data(
    p_profile_id integer,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
    perform public.record_profile_deletions(p_profile_id, p_origin_client_id);

    delete from public.library_items
    where user_id = auth.uid() and profile_id = p_profile_id;
    delete from public.watch_progress
    where user_id = auth.uid() and profile_id = p_profile_id;
    delete from public.watched_items
    where user_id = auth.uid() and profile_id = p_profile_id;
    delete from public.profile_settings
    where user_id = auth.uid() and profile_id = p_profile_id;
    delete from public.home_catalog_settings
    where user_id = auth.uid() and profile_id = p_profile_id;
    delete from public.collections
    where user_id = auth.uid() and profile_id = p_profile_id;
    delete from public.provider_credentials
    where user_id = auth.uid() and profile_id = p_profile_id;
    delete from public.addon_sources
    where user_id = auth.uid() and profile_id = p_profile_id;
    delete from public.plugin_sources
    where user_id = auth.uid() and profile_id = p_profile_id;
    delete from public.profiles
    where user_id = auth.uid() and profile_index = p_profile_id;
end;
$$;

create or replace function public.record_profile_deletions(
    p_profile_id integer,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
    insert into public.library_events (
        user_id, profile_id, operation, content_id, content_type, payload, origin_client_id
    )
    select auth.uid(), p_profile_id, 'delete', item.content_id, item.content_type,
           null, p_origin_client_id
    from public.library_items as item
    where item.user_id = auth.uid() and item.profile_id = p_profile_id;

    insert into public.watch_progress_events (
        user_id, profile_id, operation, progress_key, payload, origin_client_id
    )
    select auth.uid(), p_profile_id, 'delete', progress.progress_key, null, p_origin_client_id
    from public.watch_progress as progress
    where progress.user_id = auth.uid() and progress.profile_id = p_profile_id;

    insert into public.watched_events (
        user_id, profile_id, operation, content_id, content_type, payload, origin_client_id
    )
    select auth.uid(), p_profile_id, 'delete', item.content_id, item.content_type,
           null, p_origin_client_id
    from public.watched_items as item
    where item.user_id = auth.uid() and item.profile_id = p_profile_id;
end;
$$;

create or replace function public.sync_delete_profile_data(
    p_profile_id integer,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
    perform public.drop_profile_data(p_profile_id, p_origin_client_id);
end;
$$;

-- ----------------------------------------------------------- profile pins --
-- `unlocked` comes back as a single object, not an array: the client reads this
-- one with `decodeSingle`, so the function returns a composite rather than a
-- set.
drop type if exists public.pin_verify_result;
create type public.pin_verify_result as (
    unlocked boolean,
    retry_after_seconds integer,
    message text
);

create or replace function public.verify_profile_pin(
    p_profile_id integer,
    p_pin text
)
returns public.pin_verify_result
language plpgsql
security invoker
set search_path = public, extensions
as $$
declare
    profile public.profiles;
    failures integer;
    result public.pin_verify_result;
begin
    select * into profile
    from public.profiles
    where user_id = auth.uid() and profile_index = p_profile_id;

    if profile.id is null then
        return (false, 0, 'No such profile')::public.pin_verify_result;
    end if;
    if not profile.pin_enabled or profile.pin_hash is null then
        return (true, 0, null)::public.pin_verify_result;
    end if;
    if profile.pin_locked_until is not null and profile.pin_locked_until > now() then
        return (
            false,
            ceil(extract(epoch from (profile.pin_locked_until - now())))::integer,
            'Too many attempts'
        )::public.pin_verify_result;
    end if;

    if crypt(coalesce(p_pin, ''), profile.pin_hash) = profile.pin_hash then
        update public.profiles
        set pin_failed_attempts = 0, pin_locked_until = null, updated_at = now()
        where user_id = auth.uid() and profile_index = p_profile_id;
        return (true, 0, null)::public.pin_verify_result;
    end if;

    failures := profile.pin_failed_attempts + 1;
    result := (false, 0, 'Incorrect PIN')::public.pin_verify_result;
    -- Five wrong tries buys a five-minute wait; the count is kept so the next
    -- wrong try after the window continues where it left off.
    if failures >= 5 then
        result.retry_after_seconds := 300;
        result.message := 'Too many attempts';
        update public.profiles
        set pin_failed_attempts = failures,
            pin_locked_until = now() + interval '5 minutes',
            updated_at = now()
        where user_id = auth.uid() and profile_index = p_profile_id;
    else
        update public.profiles
        set pin_failed_attempts = failures, updated_at = now()
        where user_id = auth.uid() and profile_index = p_profile_id;
    end if;

    return result;
end;
$$;

create or replace function public.set_profile_pin(
    p_profile_id integer,
    p_pin text,
    p_current_pin text default null
)
returns void
language plpgsql
security invoker
set search_path = public, extensions
as $$
declare
    profile public.profiles;
begin
    select * into profile
    from public.profiles
    where user_id = auth.uid() and profile_index = p_profile_id;

    if profile.id is null then
        raise exception 'No such profile';
    end if;
    if length(coalesce(p_pin, '')) < 4 then
        raise exception 'A PIN needs at least four digits';
    end if;
    if profile.pin_hash is not null
        and crypt(coalesce(p_current_pin, ''), profile.pin_hash) <> profile.pin_hash then
        raise exception 'Current PIN is incorrect';
    end if;

    update public.profiles
    set pin_hash = crypt(p_pin, gen_salt('bf')),
        pin_enabled = true,
        pin_failed_attempts = 0,
        pin_locked_until = null,
        updated_at = now()
    where user_id = auth.uid() and profile_index = p_profile_id;
end;
$$;

create or replace function public.clear_profile_pin(
    p_profile_id integer,
    p_current_pin text default null
)
returns void
language plpgsql
security invoker
set search_path = public, extensions
as $$
declare
    profile public.profiles;
begin
    select * into profile
    from public.profiles
    where user_id = auth.uid() and profile_index = p_profile_id;

    if profile.id is null then
        raise exception 'No such profile';
    end if;
    if profile.pin_hash is not null
        and crypt(coalesce(p_current_pin, ''), profile.pin_hash) <> profile.pin_hash then
        raise exception 'Current PIN is incorrect';
    end if;

    update public.profiles
    set pin_hash = null,
        pin_enabled = false,
        pin_failed_attempts = 0,
        pin_locked_until = null,
        updated_at = now()
    where user_id = auth.uid() and profile_index = p_profile_id;
end;
$$;

-- The forgotten-PIN door. The account password is not re-verified: this
-- database has no access to GoTrue's password check, and it does not need one —
-- every profile in an account belongs to the same reader, and the same session
-- could delete the profile outright. It is a convenience for the locked-out
-- reader, not a privilege boundary, and it is documented as such.
create or replace function public.clear_profile_pin_with_account_password(
    p_account_password text,
    p_profile_id integer
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Not signed in';
    end if;
    if length(coalesce(p_account_password, '')) < 6 then
        raise exception 'Account password is required';
    end if;

    update public.profiles
    set pin_hash = null,
        pin_enabled = false,
        pin_failed_attempts = 0,
        pin_locked_until = null,
        updated_at = now()
    where user_id = auth.uid() and profile_index = p_profile_id;
end;
$$;

create or replace function public.sync_pull_profile_locks()
returns table (
    profile_index integer,
    pin_enabled boolean,
    pin_locked_until timestamptz
)
language sql
stable
set search_path = public
as $$
    select profile.profile_index, profile.pin_enabled, profile.pin_locked_until
    from public.profiles as profile
    where profile.user_id = auth.uid()
    order by profile.profile_index asc;
$$;

-- ------------------------------------------------- settings, collections ---
create or replace function public.sync_pull_profile_settings_blob(
    p_profile_id integer,
    p_platform text
)
returns table (
    profile_id integer,
    settings_json jsonb,
    updated_at timestamptz
)
language sql
stable
set search_path = public
as $$
    select blob.profile_id, blob.settings_json, blob.updated_at
    from public.profile_settings as blob
    where blob.user_id = auth.uid()
      and blob.profile_id = p_profile_id
      and blob.platform = p_platform;
$$;

create or replace function public.sync_push_profile_settings_blob(
    p_profile_id integer,
    p_platform text,
    p_settings_json jsonb default '{}'::jsonb
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
    insert into public.profile_settings (user_id, profile_id, platform, settings_json, updated_at)
    values (auth.uid(), p_profile_id, p_platform, coalesce(p_settings_json, '{}'::jsonb), now())
    on conflict (user_id, profile_id, platform) do update
        set settings_json = excluded.settings_json, updated_at = now();
end;
$$;

create or replace function public.sync_pull_home_catalog_settings(
    p_profile_id integer,
    p_platform text
)
returns table (
    profile_id integer,
    settings_json jsonb
)
language sql
stable
set search_path = public
as $$
    select blob.profile_id, blob.settings_json
    from public.home_catalog_settings as blob
    where blob.user_id = auth.uid()
      and blob.profile_id = p_profile_id
      and blob.platform = p_platform;
$$;

create or replace function public.sync_push_home_catalog_settings(
    p_profile_id integer,
    p_platform text,
    p_settings_json jsonb default '{}'::jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
    insert into public.home_catalog_settings (user_id, profile_id, platform, settings_json, updated_at)
    values (auth.uid(), p_profile_id, p_platform, coalesce(p_settings_json, '{}'::jsonb), now())
    on conflict (user_id, profile_id, platform) do update
        set settings_json = excluded.settings_json, updated_at = now();
end;
$$;

create or replace function public.sync_pull_collections(
    p_profile_id integer
)
returns table (
    profile_id integer,
    collections_json jsonb,
    updated_at timestamptz
)
language sql
stable
set search_path = public
as $$
    select blob.profile_id, blob.collections_json, blob.updated_at
    from public.collections as blob
    where blob.user_id = auth.uid()
      and blob.profile_id = p_profile_id;
$$;

create or replace function public.sync_push_collections(
    p_profile_id integer,
    p_collections_json jsonb default '[]'::jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
    insert into public.collections (user_id, profile_id, collections_json, updated_at)
    values (auth.uid(), p_profile_id, coalesce(p_collections_json, '[]'::jsonb), now())
    on conflict (user_id, profile_id) do update
        set collections_json = excluded.collections_json, updated_at = now();
end;
$$;

-- --------------------------------------------------- provider credentials --
create or replace function public.sync_pull_provider_credentials(
    p_profile_id integer
)
returns table (
    provider text,
    credential_json jsonb,
    updated_at timestamptz
)
language sql
stable
set search_path = public
as $$
    select credential.provider, credential.credential_json, credential.updated_at
    from public.provider_credentials as credential
    where credential.user_id = auth.uid()
      and credential.profile_id = p_profile_id
    order by credential.provider asc;
$$;

create or replace function public.sync_push_provider_credentials(
    p_profile_id integer,
    p_credentials jsonb default '[]'::jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
declare
    credential jsonb;
    kept_providers text[] := array[]::text[];
begin
    for credential in select * from jsonb_array_elements(coalesce(p_credentials, '[]'::jsonb))
    loop
        continue when coalesce(credential ->> 'provider', '') = '';

        kept_providers := kept_providers || (credential ->> 'provider');

        insert into public.provider_credentials (
            user_id, profile_id, provider, credential_json, updated_at
        )
        values (
            auth.uid(),
            p_profile_id,
            credential ->> 'provider',
            coalesce(credential -> 'credential_json', '{}'::jsonb),
            now()
        )
        on conflict (user_id, profile_id, provider) do update
            set credential_json = excluded.credential_json, updated_at = now();
    end loop;

    delete from public.provider_credentials
    where user_id = auth.uid()
      and profile_id = p_profile_id
      and not (provider = any (kept_providers));
end;
$$;

-- ------------------------------------------------------- addons, plugins ---
create or replace function public.sync_push_addons(
    p_profile_id integer,
    p_addons jsonb default '[]'::jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
declare
    addon jsonb;
    kept_urls text[] := array[]::text[];
begin
    for addon in select * from jsonb_array_elements(coalesce(p_addons, '[]'::jsonb))
    loop
        continue when coalesce(addon ->> 'url', '') = '';

        kept_urls := kept_urls || (addon ->> 'url');

        insert into public.addon_sources (user_id, profile_id, url, name, enabled, sort_order, updated_at)
        values (
            auth.uid(),
            p_profile_id,
            addon ->> 'url',
            nullif(addon ->> 'name', ''),
            coalesce((addon ->> 'enabled')::boolean, true),
            coalesce((addon ->> 'sort_order')::integer, 0),
            now()
        )
        on conflict (user_id, profile_id, url) do update
            set name = excluded.name,
                enabled = excluded.enabled,
                sort_order = excluded.sort_order,
                updated_at = now();
    end loop;

    delete from public.addon_sources
    where user_id = auth.uid()
      and profile_id = p_profile_id
      and not (url = any (kept_urls));
end;
$$;

create or replace function public.sync_push_plugins(
    p_profile_id integer,
    p_plugins jsonb default '[]'::jsonb
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
declare
    plugin jsonb;
    kept_urls text[] := array[]::text[];
begin
    for plugin in select * from jsonb_array_elements(coalesce(p_plugins, '[]'::jsonb))
    loop
        continue when coalesce(plugin ->> 'url', '') = '';

        kept_urls := kept_urls || (plugin ->> 'url');

        insert into public.plugin_sources (user_id, profile_id, url, name, enabled, sort_order, updated_at)
        values (
            auth.uid(),
            p_profile_id,
            plugin ->> 'url',
            nullif(plugin ->> 'name', ''),
            coalesce((plugin ->> 'enabled')::boolean, true),
            coalesce((plugin ->> 'sort_order')::integer, 0),
            now()
        )
        on conflict (user_id, profile_id, url) do update
            set name = excluded.name,
                enabled = excluded.enabled,
                sort_order = excluded.sort_order,
                updated_at = now();
    end loop;

    delete from public.plugin_sources
    where user_id = auth.uid()
      and profile_id = p_profile_id
      and not (url = any (kept_urls));
end;
$$;

grant execute on function public.sync_pull_profiles() to authenticated;
grant execute on function public.sync_push_profiles(integer, jsonb, text) to authenticated;
grant execute on function public.sync_delete_profile_data(integer, text) to authenticated;
grant execute on function public.verify_profile_pin(integer, text) to authenticated;
grant execute on function public.set_profile_pin(integer, text, text) to authenticated;
grant execute on function public.clear_profile_pin(integer, text) to authenticated;
grant execute on function public.clear_profile_pin_with_account_password(text, integer) to authenticated;
grant execute on function public.sync_pull_profile_locks() to authenticated;
grant execute on function public.sync_pull_profile_settings_blob(integer, text) to authenticated;
grant execute on function public.sync_push_profile_settings_blob(integer, text, jsonb) to authenticated;
grant execute on function public.sync_pull_home_catalog_settings(integer, text) to authenticated;
grant execute on function public.sync_push_home_catalog_settings(integer, text, jsonb, text) to authenticated;
grant execute on function public.sync_pull_collections(integer) to authenticated;
grant execute on function public.sync_push_collections(integer, jsonb, text) to authenticated;
grant execute on function public.sync_pull_provider_credentials(integer) to authenticated;
grant execute on function public.sync_push_provider_credentials(integer, jsonb, text) to authenticated;
grant execute on function public.sync_push_addons(integer, jsonb, text) to authenticated;
grant execute on function public.sync_push_plugins(integer, jsonb) to authenticated;
grant execute on function public.drop_profile_data(integer, text) to authenticated;
grant execute on function public.record_profile_deletions(integer, text) to authenticated;
