-- 0001_schema.sql — the tables the account lives in.
--
-- One Supabase project is the account backend for both clients: the desktop
-- window (Nuvio's own Compose UI, pointed at this project instead of Nuvio's
-- dead one) and the phone's web client. Everything a reader saves is keyed by
-- `auth.uid()` — the account — and inside it by `profile_id`, the profile
-- index (1..N) the desktop calls `profile_index`.
--
-- Why these tables look the way they do: Nuvio's desktop client already speaks
-- a fixed contract (the `sync_*` RPCs, in 0002–0004). This file defines the
-- storage behind that contract, so the desktop needs no Kotlin change at all —
-- only a build that names this project.
--
-- Every table is owner-scoped by RLS. The RPCs above them run as the *caller*
-- (`security invoker`), so a policy that says "your own rows" is the only thing
-- that lets a sync write land: there is no privileged bypass to get wrong.

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------- profiles --
-- One row per profile inside an account. `profile_index` is what every sync
-- payload calls `profile_id`.
create table if not exists public.profiles (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_index integer not null check (profile_index between 1 and 10),
    name text not null default '',
    avatar_color_hex text not null default '#1E88E5',
    avatar_id text,
    avatar_url text,
    profile_background_id text,
    profile_background_url text,
    uses_primary_addons boolean not null default false,
    uses_primary_plugins boolean not null default false,
    pin_enabled boolean not null default false,
    pin_locked_until timestamptz,
    pin_hash text,
    pin_failed_attempts integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, profile_index)
);

-- ----------------------------------------------------------------- library --
create table if not exists public.library_items (
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    content_id text not null,
    content_type text not null,
    name text not null default '',
    poster text,
    poster_shape text not null default 'POSTER',
    background text,
    logo text,
    description text,
    release_info text,
    imdb_rating real,
    genres jsonb not null default '[]'::jsonb,
    addon_base_url text,
    added_at bigint not null default 0,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, content_id, content_type)
);

-- The delta log. A client that already has a snapshot asks for everything that
-- happened after the cursor it last saw (`sync_pull_library_delta`), which is
-- how a title saved on the phone appears in the window without a full re-pull.
create table if not exists public.library_events (
    event_id bigserial primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    operation text not null check (operation in ('upsert', 'delete')),
    content_id text not null,
    content_type text not null,
    payload jsonb,
    origin_client_id text,
    created_at timestamptz not null default now()
);

-- ----------------------------------------------------------- watch progress --
create table if not exists public.watch_progress (
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    progress_key text not null,
    content_id text not null default '',
    content_type text not null default '',
    video_id text not null default '',
    season integer,
    episode integer,
    position bigint not null default 0,
    duration bigint not null default 0,
    last_watched bigint not null default 0,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, progress_key)
);

create table if not exists public.watch_progress_events (
    event_id bigserial primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    operation text not null check (operation in ('upsert', 'delete')),
    progress_key text not null,
    payload jsonb,
    origin_client_id text,
    created_at timestamptz not null default now()
);

-- ---------------------------------------------------------- watched marks --
-- A film has no season/episode, so the client sends both as null — and two
-- nulls in a unique key are normally *different* rows, which would pile up one
-- mark per save and show the title twice. So "no season" is stored as -1 and
-- mapped back to null on the way out: the uniqueness is then an ordinary one,
-- and `on conflict` needs no null semantics from anyone.
create table if not exists public.watched_items (
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    content_id text not null,
    content_type text not null default '',
    title text not null default '',
    season integer not null default -1,
    episode integer not null default -1,
    watched_at bigint not null default 0,
    updated_at timestamptz not null default now(),
    unique (user_id, profile_id, content_id, season, episode)
);

create table if not exists public.watched_events (
    event_id bigserial primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    operation text not null check (operation in ('upsert', 'delete')),
    content_id text not null,
    content_type text not null default '',
    payload jsonb,
    origin_client_id text,
    created_at timestamptz not null default now()
);

-- ---------------------------------------------- settings and other blobs ----
create table if not exists public.profile_settings (
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    platform text not null,
    settings_json jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, platform)
);

create table if not exists public.home_catalog_settings (
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    platform text not null,
    settings_json jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, platform)
);

create table if not exists public.collections (
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    collections_json jsonb not null default '[]'::jsonb,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id)
);

create table if not exists public.provider_credentials (
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    provider text not null,
    credential_json jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, provider)
);

-- --------------------------------------------------------------- addons ----
-- The base tables carry `user_id`; the client reads these two *directly* (a
-- filtered select, not an RPC), so `0005_catalog_device.sql` puts a
-- security-invoker view named `addons`/`plugins` in front of each, projecting
-- exactly the columns the client decodes. Extra columns in a select would be
-- one deserialization default away from breaking the phone's addon list.
create table if not exists public.addon_sources (
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    url text not null,
    name text,
    enabled boolean not null default true,
    sort_order integer not null default 0,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, url)
);

create table if not exists public.plugin_sources (
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    url text not null,
    name text,
    enabled boolean not null default true,
    sort_order integer not null default 0,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, url)
);

-- -------------------------------------------------------------- devices ----
-- What the desktop registers itself as, so the account can show which machines
-- hold a session and when each was last seen.
create table if not exists public.devices (
    user_id uuid not null references auth.users (id) on delete cascade,
    installation_id text not null,
    client_name text not null default '',
    client_version text not null default '',
    platform text not null default '',
    device_name text not null default '',
    last_seen_at timestamptz not null default now(),
    primary key (user_id, installation_id)
);

-- The TV/device link handshake: the window asks for a code, a signed-in device
-- approves it, the window polls until it is approved and then exchanges it.
create table if not exists public.device_login_sessions (
    device_code uuid primary key default gen_random_uuid(),
    user_code text not null unique,
    device_nonce text not null,
    device_name text not null default '',
    device_type text not null default '',
    verification_base_url text not null default '',
    status text not null default 'pending' check (status in ('pending', 'approved', 'denied')),
    user_id uuid references auth.users (id) on delete cascade,
    approved_at timestamptz,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default now() + interval '15 minutes'
);

-- ------------------------------------------------------------- indexes -----
create index if not exists library_items_profile_added_idx
    on public.library_items (user_id, profile_id, added_at desc, content_id);
create index if not exists library_events_profile_event_idx
    on public.library_events (user_id, profile_id, event_id);
create index if not exists watch_progress_profile_last_idx
    on public.watch_progress (user_id, profile_id, last_watched desc);
create index if not exists watch_progress_events_profile_event_idx
    on public.watch_progress_events (user_id, profile_id, event_id);
create index if not exists watched_items_profile_watched_idx
    on public.watched_items (user_id, profile_id, watched_at desc);
create index if not exists watched_events_profile_event_idx
    on public.watched_events (user_id, profile_id, event_id);
create index if not exists addon_sources_profile_order_idx
    on public.addon_sources (user_id, profile_id, sort_order);
create index if not exists plugin_sources_profile_order_idx
    on public.plugin_sources (user_id, profile_id, sort_order);
create index if not exists device_login_sessions_code_idx
    on public.device_login_sessions (user_code);

-- ------------------------------------------------------------------ RLS ----
-- Row-level security on, one policy per verb, "your own rows" always. The
-- sync RPCs run as the caller, so these policies are also the write path.
do $$
declare
    t text;
begin
    foreach t in array array[
        'profiles', 'library_items', 'library_events', 'watch_progress',
        'watch_progress_events', 'watched_items', 'watched_events',
        'profile_settings', 'home_catalog_settings', 'collections',
        'provider_credentials', 'addon_sources', 'plugin_sources', 'devices'
    ]
    loop
        execute format('alter table public.%I enable row level security', t);
        execute format('drop policy if exists %I on public.%I', t || '_owner', t);
        execute format(
            'create policy %I on public.%I for all to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))',
            t || '_owner', t
        );
        execute format('grant select, insert, update, delete on public.%I to authenticated', t);
    end loop;
end
$$;

-- Events are append-only: a client writes its own history and reads it back,
-- but nobody edits or deletes the log.
do $$
declare
    t text;
begin
    foreach t in array array['library_events', 'watch_progress_events', 'watched_events']
    loop
        execute format('revoke update, delete on public.%I from authenticated', t);
    end loop;
end
$$;

grant usage, select on all sequences in schema public to authenticated;

-- The two handshake tables are touched by `anon` as well: a window that has no
-- session yet must be able to ask for a code.
alter table public.device_login_sessions enable row level security;
revoke all on public.device_login_sessions from anon, authenticated;

-- ------------------------------------------- a new account gets a profile --
-- Nuvio's own backend hands a fresh signup "Profile 1"; without one the account
-- gate opens on an empty profile picker. Seeded here, so both clients see it.
create or replace function public.seed_new_account_profile()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.profiles (user_id, profile_index, name)
    values (new.id, 1, 'Profile 1')
    on conflict (user_id, profile_index) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.seed_new_account_profile();
