-- 0003_watching.sql — where each title was left, and what has been watched.
--
-- Two datasets, one shape each: `watch_progress` is "continue watching" (a
-- position in a video, keyed by `progress_key`), `watched_items` is the mark
-- that an episode or a film has been seen. Both keep their own delta log, for
-- the same reason the library does — a client that has a snapshot only wants
-- what happened since, which is what makes the phone's second episode show up
-- as watched in the window without a full re-pull.

-- ---------------------------------------------- watch progress: pull ------
create or replace function public.sync_pull_watch_progress(
    p_profile_id integer,
    p_since_last_watched bigint default null,
    p_limit integer default null
)
returns table (
    progress_key text,
    content_id text,
    content_type text,
    video_id text,
    season integer,
    episode integer,
    "position" bigint,
    duration bigint,
    last_watched bigint
)
language sql
stable
set search_path = public
as $$
    select
        progress.progress_key,
        progress.content_id,
        progress.content_type,
        progress.video_id,
        progress.season,
        progress.episode,
        progress."position",
        progress.duration,
        progress.last_watched
    from public.watch_progress as progress
    where progress.user_id = auth.uid()
      and progress.profile_id = p_profile_id
      and (
          p_since_last_watched is null
          or progress.last_watched > p_since_last_watched
      )
    order by progress.last_watched desc, progress.progress_key asc
    limit case when p_limit is null then null else greatest(p_limit, 0) end;
$$;

create or replace function public.sync_get_watch_progress_delta_cursor(
    p_profile_id integer
)
returns bigint
language sql
stable
set search_path = public
as $$
    select coalesce(max(event.event_id), 0)::bigint
    from public.watch_progress_events as event
    where event.user_id = auth.uid()
      and event.profile_id = p_profile_id;
$$;

create or replace function public.sync_pull_watch_progress_delta(
    p_profile_id integer,
    p_since_event_id bigint,
    p_limit integer default 500
)
returns table (
    event_id bigint,
    operation text,
    progress_key text,
    content_id text,
    content_type text,
    video_id text,
    season integer,
    episode integer,
    "position" bigint,
    duration bigint,
    last_watched bigint
)
language sql
stable
set search_path = public
as $$
    select
        event.event_id,
        event.operation,
        event.progress_key,
        coalesce(event.payload ->> 'content_id', '') as content_id,
        coalesce(event.payload ->> 'content_type', '') as content_type,
        coalesce(event.payload ->> 'video_id', '') as video_id,
        (event.payload ->> 'season')::integer as season,
        (event.payload ->> 'episode')::integer as episode,
        coalesce((event.payload ->> 'position')::bigint, 0) as position,
        coalesce((event.payload ->> 'duration')::bigint, 0) as duration,
        coalesce((event.payload ->> 'last_watched')::bigint, 0) as last_watched
    from public.watch_progress_events as event
    where event.user_id = auth.uid()
      and event.profile_id = p_profile_id
      and event.event_id > coalesce(p_since_event_id, 0)
    order by event.event_id asc
    limit greatest(coalesce(p_limit, 500), 0);
$$;

-- ---------------------------------------------------- watch progress: push --
create or replace function public.sync_push_watch_progress(
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
    entry_key text;
begin
    for entry in select * from jsonb_array_elements(coalesce(p_entries, '[]'::jsonb))
    loop
        entry_key := coalesce(nullif(entry ->> 'progress_key', ''), entry ->> 'content_id');
        continue when entry_key is null;

        insert into public.watch_progress (
            user_id, profile_id, progress_key, content_id, content_type,
            video_id, season, episode, "position", duration, last_watched, updated_at
        )
        values (
            auth.uid(),
            p_profile_id,
            entry_key,
            coalesce(entry ->> 'content_id', ''),
            coalesce(entry ->> 'content_type', ''),
            coalesce(entry ->> 'video_id', ''),
            (entry ->> 'season')::integer,
            (entry ->> 'episode')::integer,
            coalesce((entry ->> 'position')::bigint, 0),
            coalesce((entry ->> 'duration')::bigint, 0),
            coalesce((entry ->> 'last_watched')::bigint, 0),
            now()
        )
        on conflict (user_id, profile_id, progress_key) do update
            set content_id = excluded.content_id,
                content_type = excluded.content_type,
                video_id = excluded.video_id,
                season = excluded.season,
                episode = excluded.episode,
                "position" = excluded."position",
                duration = excluded.duration,
                last_watched = excluded.last_watched,
                updated_at = now();

        insert into public.watch_progress_events (
            user_id, profile_id, operation, progress_key, payload, origin_client_id
        )
        values (
            auth.uid(), p_profile_id, 'upsert', entry_key, entry, p_origin_client_id
        );
    end loop;
end;
$$;

-- The delete payload is a bare array of progress keys, not of objects.
create or replace function public.sync_delete_watch_progress(
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
    entry_key text;
begin
    for entry_key in select * from jsonb_array_elements_text(coalesce(p_keys, '[]'::jsonb))
    loop
        delete from public.watch_progress
        where user_id = auth.uid()
          and profile_id = p_profile_id
          and progress_key = entry_key;

        insert into public.watch_progress_events (
            user_id, profile_id, operation, progress_key, payload, origin_client_id
        )
        values (
            auth.uid(), p_profile_id, 'delete', entry_key, null, p_origin_client_id
        );
    end loop;
end;
$$;

-- --------------------------------------------------------- watched: pull ---
create or replace function public.sync_pull_watched_items(
    p_profile_id integer,
    p_page integer default 1,
    p_page_size integer default 500
)
returns table (
    content_id text,
    content_type text,
    title text,
    season integer,
    episode integer,
    watched_at bigint
)
language sql
stable
set search_path = public
as $$
    select
        item.content_id,
        item.content_type,
        item.title,
        nullif(item.season, -1) as season,
        nullif(item.episode, -1) as episode,
        item.watched_at
    from public.watched_items as item
    where item.user_id = auth.uid()
      and item.profile_id = p_profile_id
    order by item.watched_at desc, item.content_id asc, item.season asc, item.episode asc
    limit greatest(coalesce(p_page_size, 500), 0)
    offset greatest(coalesce(p_page, 1) - 1, 0) * greatest(coalesce(p_page_size, 500), 0);
$$;

create or replace function public.sync_get_watched_items_delta_cursor(
    p_profile_id integer
)
returns bigint
language sql
stable
set search_path = public
as $$
    select coalesce(max(event.event_id), 0)::bigint
    from public.watched_events as event
    where event.user_id = auth.uid()
      and event.profile_id = p_profile_id;
$$;

create or replace function public.sync_pull_watched_items_delta(
    p_profile_id integer,
    p_since_event_id bigint,
    p_limit integer default 500
)
returns table (
    event_id bigint,
    operation text,
    content_id text,
    content_type text,
    title text,
    season integer,
    episode integer,
    watched_at bigint
)
language sql
stable
set search_path = public
as $$
    select
        event.event_id,
        event.operation,
        event.content_id,
        coalesce(event.payload ->> 'content_type', '') as content_type,
        coalesce(event.payload ->> 'title', '') as title,
        (event.payload ->> 'season')::integer as season,
        (event.payload ->> 'episode')::integer as episode,
        coalesce((event.payload ->> 'watched_at')::bigint, 0) as watched_at
    from public.watched_events as event
    where event.user_id = auth.uid()
      and event.profile_id = p_profile_id
      and event.event_id > coalesce(p_since_event_id, 0)
    order by event.event_id asc
    limit greatest(coalesce(p_limit, 500), 0);
$$;

-- ------------------------------------------------------- watched: push -----
create or replace function public.sync_push_watched_items(
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
    item_season integer;
    item_episode integer;
begin
    for item in select * from jsonb_array_elements(coalesce(p_items, '[]'::jsonb))
    loop
        item_season := coalesce((item ->> 'season')::integer, -1);
        item_episode := coalesce((item ->> 'episode')::integer, -1);

        insert into public.watched_items (
            user_id, profile_id, content_id, content_type, title,
            season, episode, watched_at, updated_at
        )
        values (
            auth.uid(),
            p_profile_id,
            item ->> 'content_id',
            coalesce(item ->> 'content_type', ''),
            coalesce(item ->> 'title', ''),
            item_season,
            item_episode,
            coalesce((item ->> 'watched_at')::bigint, 0),
            now()
        )
        on conflict (user_id, profile_id, content_id, season, episode) do update
            set content_type = excluded.content_type,
                title = excluded.title,
                watched_at = excluded.watched_at,
                updated_at = now();

        insert into public.watched_events (
            user_id, profile_id, operation, content_id, content_type,
            payload, origin_client_id
        )
        values (
            auth.uid(), p_profile_id, 'upsert',
            item ->> 'content_id', coalesce(item ->> 'content_type', ''),
            item, p_origin_client_id
        );
    end loop;
end;
$$;

create or replace function public.sync_delete_watched_items(
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
    item_season integer;
    item_episode integer;
begin
    for item in select * from jsonb_array_elements(coalesce(p_keys, '[]'::jsonb))
    loop
        item_season := coalesce((item ->> 'season')::integer, -1);
        item_episode := coalesce((item ->> 'episode')::integer, -1);

        delete from public.watched_items
        where user_id = auth.uid()
          and profile_id = p_profile_id
          and content_id = item ->> 'content_id'
          and season = item_season
          and episode = item_episode;

        insert into public.watched_events (
            user_id, profile_id, operation, content_id, content_type,
            payload, origin_client_id
        )
        values (
            auth.uid(), p_profile_id, 'delete',
            item ->> 'content_id', '',
            null, p_origin_client_id
        );
    end loop;
end;
$$;

grant execute on function public.sync_pull_watch_progress(integer, bigint, integer) to authenticated;
grant execute on function public.sync_get_watch_progress_delta_cursor(integer) to authenticated;
grant execute on function public.sync_pull_watch_progress_delta(integer, bigint, integer) to authenticated;
grant execute on function public.sync_push_watch_progress(integer, jsonb, text) to authenticated;
grant execute on function public.sync_delete_watch_progress(integer, jsonb, text) to authenticated;
grant execute on function public.sync_pull_watched_items(integer, integer, integer) to authenticated;
grant execute on function public.sync_get_watched_items_delta_cursor(integer) to authenticated;
grant execute on function public.sync_pull_watched_items_delta(integer, bigint, integer) to authenticated;
grant execute on function public.sync_push_watched_items(integer, jsonb, text) to authenticated;
grant execute on function public.sync_delete_watched_items(integer, jsonb, text) to authenticated;
