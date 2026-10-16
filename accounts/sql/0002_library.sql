-- 0002_library.sql — the library contract the desktop calls.
--
-- Everything here is `security invoker`: the function runs as the signed-in
-- reader, so the RLS policies from 0001 are what decide which rows it may see
-- or write. Each function also filters on `auth.uid()` explicitly, which is
-- belt and braces rather than the only guard.
--
-- Result columns are listed explicitly (`returns table (...)`) instead of
-- `returns setof public.library_items`, because the desktop decodes these
-- payloads into `LibrarySyncItem`: the exact column names are the contract, and
-- a stray `user_id` in the response would be an unknown key in a `@Serializable`
-- class. Writing them out keeps that contract visible in one place.

-- ---------------------------------------------------------------- pull ----
create or replace function public.sync_pull_library(
    p_profile_id integer,
    p_limit integer default 500,
    p_offset integer default 0
)
returns table (
    content_id text,
    content_type text,
    name text,
    poster text,
    poster_shape text,
    background text,
    logo text,
    description text,
    release_info text,
    imdb_rating real,
    genres jsonb,
    addon_base_url text,
    added_at bigint
)
language sql
stable
set search_path = public
as $$
    select
        item.content_id,
        item.content_type,
        item.name,
        item.poster,
        item.poster_shape,
        item.background,
        item.logo,
        item.description,
        item.release_info,
        item.imdb_rating,
        item.genres,
        item.addon_base_url,
        item.added_at
    from public.library_items as item
    where item.user_id = auth.uid()
      and item.profile_id = p_profile_id
    order by item.added_at desc, item.content_id asc
    limit greatest(coalesce(p_limit, 500), 0)
    offset greatest(coalesce(p_offset, 0), 0);
$$;

-- The cursor is a bare number, not a row: the client reads it with
-- `decodeAs<Long>()`.
create or replace function public.sync_get_library_delta_cursor(
    p_profile_id integer
)
returns bigint
language sql
stable
set search_path = public
as $$
    select coalesce(max(event.event_id), 0)::bigint
    from public.library_events as event
    where event.user_id = auth.uid()
      and event.profile_id = p_profile_id;
$$;

create or replace function public.sync_pull_library_delta(
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
    poster_shape text,
    background text,
    logo text,
    description text,
    release_info text,
    imdb_rating real,
    genres jsonb,
    addon_base_url text,
    added_at bigint
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
        coalesce(event.payload ->> 'poster_shape', 'POSTER') as poster_shape,
        event.payload ->> 'background' as background,
        event.payload ->> 'logo' as logo,
        event.payload ->> 'description' as description,
        event.payload ->> 'release_info' as release_info,
        (event.payload ->> 'imdb_rating')::real as imdb_rating,
        coalesce(event.payload -> 'genres', '[]'::jsonb) as genres,
        event.payload ->> 'addon_base_url' as addon_base_url,
        coalesce((event.payload ->> 'added_at')::bigint, 0) as added_at
    from public.library_events as event
    where event.user_id = auth.uid()
      and event.profile_id = p_profile_id
      and event.event_id > coalesce(p_since_event_id, 0)
    order by event.event_id asc
    limit greatest(coalesce(p_limit, 500), 0);
$$;

-- ---------------------------------------------------------------- push ----
-- A push settles the whole batch and records it in the same transaction, so the
-- delta another client picks up can never describe a row that did not land.
create or replace function public.sync_push_library_items(
    p_profile_id integer,
    p_items jsonb,
    p_origin_client_id text default null
)
returns void
language plpgsql
security invoker
set search_path = public
as $$
declare
    item jsonb;
begin
    for item in select * from jsonb_array_elements(coalesce(p_items, '[]'::jsonb))
    loop
        insert into public.library_items (
            user_id, profile_id, content_id, content_type, name, poster,
            poster_shape, background, logo, description, release_info,
            imdb_rating, genres, addon_base_url, added_at, updated_at
        )
        values (
            auth.uid(),
            p_profile_id,
            item ->> 'content_id',
            item ->> 'content_type',
            coalesce(item ->> 'name', ''),
            item ->> 'poster',
            coalesce(item ->> 'poster_shape', 'POSTER'),
            item ->> 'background',
            item ->> 'logo',
            item ->> 'description',
            item ->> 'release_info',
            (item ->> 'imdb_rating')::real,
            coalesce(item -> 'genres', '[]'::jsonb),
            item ->> 'addon_base_url',
            coalesce((item ->> 'added_at')::bigint, 0),
            now()
        )
        on conflict (user_id, profile_id, content_id, content_type) do update
            set name = excluded.name,
                poster = excluded.poster,
                poster_shape = excluded.poster_shape,
                background = excluded.background,
                logo = excluded.logo,
                description = excluded.description,
                release_info = excluded.release_info,
                imdb_rating = excluded.imdb_rating,
                genres = excluded.genres,
                addon_base_url = excluded.addon_base_url,
                added_at = excluded.added_at,
                updated_at = now();

        insert into public.library_events (
            user_id, profile_id, operation, content_id, content_type,
            payload, origin_client_id
        )
        values (
            auth.uid(), p_profile_id, 'upsert',
            item ->> 'content_id', item ->> 'content_type',
            item, p_origin_client_id
        );
    end loop;
end;
$$;

create or replace function public.sync_delete_library_items(
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
    item jsonb;
begin
    for item in select * from jsonb_array_elements(coalesce(p_keys, '[]'::jsonb))
    loop
        delete from public.library_items
        where user_id = auth.uid()
          and profile_id = p_profile_id
          and content_id = item ->> 'content_id'
          and content_type = item ->> 'content_type';

        insert into public.library_events (
            user_id, profile_id, operation, content_id, content_type,
            payload, origin_client_id
        )
        values (
            auth.uid(), p_profile_id, 'delete',
            item ->> 'content_id', item ->> 'content_type',
            null, p_origin_client_id
        );
    end loop;
end;
$$;

grant execute on function public.sync_pull_library(integer, integer, integer) to authenticated;
grant execute on function public.sync_get_library_delta_cursor(integer) to authenticated;
grant execute on function public.sync_pull_library_delta(integer, bigint, integer) to authenticated;
grant execute on function public.sync_push_library_items(integer, jsonb, text) to authenticated;
grant execute on function public.sync_delete_library_items(integer, jsonb, text) to authenticated;
