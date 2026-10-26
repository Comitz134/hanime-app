-- 0008_manga_suggestions.sql - the manga readers recommend to everyone.
--
-- The Manga shelf in 0002 is private: one reader's titles, one reader's
-- positions. This is the opposite kind of list. A reader who wants to put a
-- title in front of everybody writes one row here, and every client reads the
-- same feed. It is the app's only cross-account list, so the rules are stated
-- in one place:
--
--   * everyone, signed in or not, may read the whole feed;
--   * only a signed-in reader may write, and only rows of their own;
--   * a reader may recommend one title once per source, and may take that
--     recommendation back;
--   * the feed never exposes a user id. It answers with counts and a single
--     "mine" flag for the caller.
--
-- The table is the truth. `manga_suggestion_feed` is the read shape the app
-- queries: one row per title and source, the number of readers behind it, and
-- whether the caller is one of them. Grouping in the view (rather than in each
-- client) is what makes two recommendations of the same title one entry, and it
-- is also what keeps other readers' ids out of the answer.
--
-- Run after 0007 on an existing project. The file is re-runnable.

-- ------------------------------------------------------------------ table ---
create table if not exists public.manga_suggestions (
    user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
    manga_id text not null check (manga_id <> ''),
    source text not null default '',
    title text not null default '',
    poster text not null default '',
    source_name text not null default '',
    language text not null default '',
    created_at timestamptz not null default now(),
    primary key (user_id, manga_id, source)
);

create index if not exists manga_suggestions_recent_idx
    on public.manga_suggestions (created_at desc);
create index if not exists manga_suggestions_title_idx
    on public.manga_suggestions (manga_id, source);

-- -------------------------------------------------------------------- RLS ---
alter table public.manga_suggestions enable row level security;

-- Reading is the whole point of the list: a signed-out client sees the same
-- feed as a signed-in one.
drop policy if exists manga_suggestions_everyone_reads on public.manga_suggestions;
create policy manga_suggestions_everyone_reads on public.manga_suggestions
    for select to anon, authenticated
    using (true);

-- Writing is own rows only. `auth.uid()` is also the column default, so a
-- client that omits `user_id` still lands on the right reader and a client
-- that sends somebody else's id is refused rather than trusted.
drop policy if exists manga_suggestions_own_write on public.manga_suggestions;
create policy manga_suggestions_own_write on public.manga_suggestions
    for all to authenticated
    using (user_id = (select auth.uid()))
    with check (user_id = (select auth.uid()));

-- Supabase's default privileges give every new public table to anon as well.
-- RLS already refuses an anonymous write (there is no policy for it), but the
-- grant is still a grant: take it back rather than leaving a privilege that
-- depends on a policy staying right.
revoke all on public.manga_suggestions from anon;
grant select on public.manga_suggestions to anon;
grant select, insert, update, delete on public.manga_suggestions to authenticated;

-- ------------------------------------------------------------------- feed ---
-- `security_invoker = on` runs the query as the caller, so the table's policy
-- above is still what decides which rows exist. An anonymous caller reads all
-- rows and `mine` is false; a signed-in one gets the same rows with their own
-- recommendations marked.
create or replace view public.manga_suggestion_feed
with (security_invoker = on)
as
    select
        s.manga_id,
        s.source,
        (array_agg(s.title order by s.created_at desc))[1] as title,
        (array_agg(s.poster order by s.created_at desc))[1] as poster,
        (array_agg(s.source_name order by s.created_at desc))[1] as source_name,
        (array_agg(s.language order by s.created_at desc))[1] as language,
        count(*) as readers,
        coalesce(bool_or(s.user_id = (select auth.uid())), false) as mine,
        max(s.created_at) as recommended_at
    from public.manga_suggestions s
    group by s.manga_id, s.source;

grant select on public.manga_suggestion_feed to anon, authenticated;

-- PostgREST caches the schema; this makes the new view visible without waiting
-- for the next automatic reload.
notify pgrst, 'reload schema';
