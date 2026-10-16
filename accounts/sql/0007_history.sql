-- 0007_history.sql — what was opened, and when. The fourth dataset, and the
-- one the phone asked for: a reader's history was the last thing still tied to
-- one device.
--
-- It is deliberately *not* the library and not watch progress. The library is
-- what a reader chose to keep; progress is where a title was left, for the
-- continue-watching rails; a watched mark is the claim that something has been
-- seen. History is none of those: it is the list of titles that were opened, in
-- the order they were opened, including the ones abandoned in the first minute
-- — and the phone has drawn exactly that list since before there was an account
-- (`htv:library:v1`'s `history`, capped at 240 entries).
--
-- So it gets its own table and its own four functions, with the same shape as
-- the library's (`0002`): a snapshot pull with paging, a delta log for a client
-- that already has a snapshot, a push by key, a delete by key — plus one thing
-- the library does not need, `sync_clear_history`, because this list has a
-- Clear button and "the reader emptied it" is not the same statement as "these
-- ten keys are gone" (a row this client never pulled would otherwise survive
-- the clear and reappear on the next pull).
--
-- The tables, indexes, policies and grants live in this file rather than in
-- 0001 because 0001 is already applied: a dataset added later arrives whole, so
-- running this one file is the whole migration. A fresh project runs 0001..0007
-- in order and lands in the same place.
--
-- Note for the desktop: nothing in Nuvio's own client reads this — it has
-- continue-watching and watched marks, and no history screen (its only
-- "history" is the search box's, which is a different thing). The window is
-- therefore unaffected by this file: it neither writes rows here nor reads
-- them. The list is the phone's, and now the account holds it, so a reinstall
-- or a second phone gets it back.

-- ------------------------------------------------------------------ table ---
create table if not exists public.history_items (
    user_id uuid not null references auth.users (id) on delete cascade,
    profile_id integer not null,
    content_id text not null,
    content_type text not null default '',
    name text not null default '',
    poster text,
    brand text,
    opened_at bigint not null default 0,
    updated_at timestamptz not null default now(),
    primary key (user_id, profile_id, content_id, content_type)
);

-- The delta log, like the library's: a client that already has a snapshot asks
-- for what happened after the cursor it last saw. Deletions are events too,
-- which is what lets a title cleared on one phone leave another's list.
create table if not exists public.history_events (
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

create index if not exists history_items_profile_opened_idx
    on public.history_items (user_id, profile_id, opened_at desc, content_id);
create index if not exists history_events_profile_event_idx
    on public.history_events (user_id, profile_id, event_id);

-- -------------------------------------------------------------------- RLS ---
-- Same policy as every other table in 0001: your own rows, for every verb, and
-- the functions below run as the caller (`security invoker`), so this is also
-- the write path.
alter table public.history_items enable row level security;
drop policy if exists history_items_owner on public.history_items;
create policy history_items_owner on public.history_items
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));
grant select, insert, update, delete on public.history_items to authenticated;

alter table public.history_events enable row level security;
drop policy if exists history_events_owner on public.history_events;
create policy history_events_owner on public.history_events
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));
grant select, insert on public.history_events to authenticated;
-- Append-only, like the other logs: a client writes its own and reads it back.
revoke update, delete on public.history_events from authenticated;

grant usage, select on all sequences in schema public to authenticated;

-- ------------------------------------------------------------- pull -------
create or replace function public.sync_pull_history(
    p_profile_id integer,
    p_limit integer default 500,
    p_offset integer default 0
)
returns table (
    content_id text,
    content_type text,
    name text,
    poster text,
    brand text,
    opened_at bigint
)
language sql
stable
set search_path = public
as $$
    select
        row.content_id,
        row.content_type,
        row.name,
        row.poster,
        row.brand,
        row.opened_at
    from public.history_items as row
    where row.user_id = auth.uid()
      and row.profile_id = p_profile_id
    order by row.opened_at desc, row.content_id asc
    limit greatest(coalesce(p_limit, 500), 0)
    offset greatest(coalesce(p_offset, 0), 0);
$$;

create or replace function public.sync_get_history_delta_cursor(
    p_profile_id integer
)
returns bigint
language sql
stable
set search_path = public
as $$
    select coalesce(max(event.event_id), 0)::bigint
    from public.history_events as event
    where event.user_id = auth.uid()
      and event.profile_id = p_profile_id;
$$;

create or replace function public.sync_pull_history_delta(
    p_profile_id integer,
    p_since_event_id bigint,
    p_limit integer default 500
)
returns table (
    event_id bigint,
    operation text,
    content_id text,
    content_type text,
    name text,
    poster text,
    brand text,
    opened_at bigint
)
language sql
stable
set search_path = public
as $$
    select
        event.event_id,
        event.operation,
        event.content_id,
        event.content_type,
        coalesce(event.payload ->> 'name', '') as name,
        event.payload ->> 'poster' as poster,
        event.payload ->> 'brand' as brand,
        coalesce((event.payload ->> 'opened_at')::bigint, 0) as opened_at
    from public.history_events as event
    where event.user_id = auth.uid()
      and event.profile_id = p_profile_id
      and event.event_id > coalesce(p_since_event_id, 0)
    order by event.event_id asc
    limit greatest(coalesce(p_limit, 500), 0);
$$;

-- ------------------------------------------------------------- push -------
-- The later opening is the one that counts, whichever client it came from:
-- two phones with clocks a minute apart must not be able to move a title
-- *down* the list by opening it again.
create or replace function public.sync_push_history(
    p_profile_id integer,
    p_entries jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
declare
    entry jsonb;
begin
    for entry in select * from jsonb_array_elements(coalesce(p_entries, '[]'::jsonb))
    loop
        continue when coalesce(entry ->> 'content_id', '') = '';

        insert into public.history_items (
            user_id, profile_id, content_id, content_type, name, poster, brand,
            opened_at, updated_at
        )
        values (
            auth.uid(),
            p_profile_id,
            entry ->> 'content_id',
            coalesce(entry ->> 'content_type', ''),
            coalesce(entry ->> 'name', ''),
            entry ->> 'poster',
            entry ->> 'brand',
            coalesce((entry ->> 'opened_at')::bigint, 0),
            now()
        )
        on conflict (user_id, profile_id, content_id, content_type) do update
            set name = excluded.name,
                poster = excluded.poster,
                brand = excluded.brand,
                opened_at = greatest(excluded.opened_at, public.history_items.opened_at),
                updated_at = now();

        insert into public.history_events (
            user_id, profile_id, operation, content_id, content_type,
            payload, origin_client_id
        )
        values (
            auth.uid(), p_profile_id, 'upsert',
            entry ->> 'content_id', coalesce(entry ->> 'content_type', ''),
            entry, p_origin_client_id
        );
    end loop;
end;
$$;

create or replace function public.sync_delete_history(
    p_profile_id integer,
    p_keys jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
declare
    entry jsonb;
begin
    for entry in select * from jsonb_array_elements(coalesce(p_keys, '[]'::jsonb))
    loop
        continue when coalesce(entry ->> 'content_id', '') = '';

        delete from public.history_items
        where user_id = auth.uid()
          and profile_id = p_profile_id
          and content_id = entry ->> 'content_id'
          and content_type = coalesce(entry ->> 'content_type', '');

        insert into public.history_events (
            user_id, profile_id, operation, content_id, content_type,
            payload, origin_client_id
        )
        values (
            auth.uid(), p_profile_id, 'delete',
            entry ->> 'content_id', coalesce(entry ->> 'content_type', ''),
            null, p_origin_client_id
        );
    end loop;
end;
$$;

-- Emptying the list is its own act: everything this profile has, gone, with one
-- delete event per row so a second phone following the log drops them too.
create or replace function public.sync_clear_history(
    p_profile_id integer,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
begin
    with gone as (
        delete from public.history_items
        where user_id = auth.uid()
          and profile_id = p_profile_id
        returning content_id, content_type
    )
    insert into public.history_events (
        user_id, profile_id, operation, content_id, content_type,
        payload, origin_client_id
    )
    select auth.uid(), p_profile_id, 'delete', gone.content_id, gone.content_type,
           null, p_origin_client_id
    from gone;
end;
$$;

-- ------------------------------------------------------------- grants -----
-- Reachable by a signed-in reader only, and through these grants rather than
-- PostgreSQL's PUBLIC default (the reason 0006 exists at all).
revoke execute on function public.sync_pull_history(integer, integer, integer) from public;
revoke execute on function public.sync_get_history_delta_cursor(integer) from public;
revoke execute on function public.sync_pull_history_delta(integer, bigint, integer) from public;
revoke execute on function public.sync_push_history(integer, jsonb, text) from public;
revoke execute on function public.sync_delete_history(integer, jsonb, text) from public;
revoke execute on function public.sync_clear_history(integer, text) from public;

grant execute on function public.sync_pull_history(integer, integer, integer) to authenticated;
grant execute on function public.sync_get_history_delta_cursor(integer) to authenticated;
grant execute on function public.sync_pull_history_delta(integer, bigint, integer) to authenticated;
grant execute on function public.sync_push_history(integer, jsonb, text) to authenticated;
grant execute on function public.sync_delete_history(integer, jsonb, text) to authenticated;
grant execute on function public.sync_clear_history(integer, text) to authenticated;
