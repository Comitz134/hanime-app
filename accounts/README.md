# The account backend

One Supabase project is the account for both clients: the desktop window
(Nuvio's own Compose UI, this fork's build of it) and the phone's web client.
A reader signs in once and gets one library, one set of profiles and one set of
watch positions, whichever screen they are holding.

## The project

```
https://hijiurhppikjgammgdmg.supabase.co
sb_publishable_nr-W-nQRbUEg7pJ3ipMUxA_fwqw6t5e
```

Both are **publishable**: they ship inside every client and carry no privileges
of their own. The desktop build bakes the same pair (`SupabaseDefaultUrl` /
`SupabaseDefaultAnonKey` in
[`../shell/overrides/composeApp/build.gradle.kts`](../shell/overrides/composeApp/build.gradle.kts)),
`client/build.mjs` bakes it into the web client's bundle, and this directory's
probe reads it. Row-level security — not key secrecy — is what keeps one
reader's library out of another's. The **service-role key is never in a
client**: it would bypass every policy, and nothing here needs it.

Every table is owner-scoped by `auth.uid()` from RLS policies, and the functions
over them run as the caller (`security invoker`). There is no privileged bypass
to get wrong: a policy that says "your own rows" is the only thing that lets a
sync write land.

## What is here

| file | what it is |
| --- | --- |
| [`sql/0001_schema.sql`](sql/0001_schema.sql) | the tables the account lives in, and the RLS policies over them |
| [`sql/0002_library.sql`](sql/0002_library.sql) | the library contract: pull, delta, push, delete |
| [`sql/0003_watching.sql`](sql/0003_watching.sql) | watch positions ("continue watching") and watched marks, each with their own delta log |
| [`sql/0004_account.sql`](sql/0004_account.sql) | profiles and pins, the per-profile settings blob and home catalogue, collections, provider credentials, addons and plugins |
| [`sql/0005_catalog_device.sql`](sql/0005_catalog_device.sql) | the two views the client reads directly (`addons`, `plugins`), the supporter-programme answers this backend does not have, and the device link |
| [`sql/0006_hardening.sql`](sql/0006_hardening.sql) | exactly who may call what (PostgreSQL grants `EXECUTE` to `PUBLIC` by default) |
| [`sql/0007_history.sql`](sql/0007_history.sql) | what was opened, and when — the phone's history, which used to be tied to one device. Adds its own tables, policies and functions, so this one file *is* the migration |
| [`sql/0008_manga_suggestions.sql`](sql/0008_manga_suggestions.sql) | the mobile Manga tab's global list: a title any reader recommends to everyone. Public to read, own rows to write, grouped by a feed view that hides user ids |
| [`probe.mjs`](probe.mjs) | the live check: drives the whole contract over the public API, the way the clients do |

### Applying the migrations

In order, once each, against the project's own database — the SQL editor in the
dashboard is enough (paste a file, run it), or `psql`:

```bash
psql "$DATABASE_URL" -f accounts/sql/0001_schema.sql   # then 0002 ... 0008
```

They are written to be re-runnable (`create table if not exists`,
`create or replace function`), so re-applying one after an edit is safe, and a
fresh project is brought up by running all eight in order and nothing else.

A client can be newer than the project it talks to — the phone in your pocket
is not redeployed when someone pastes a file — so the client is built to notice:
`0007_history.sql` is the one dataset added after the first clients shipped, and
a phone that pulls history from a project without it says so (in the sync
result, on its Settings card) instead of failing the sync or retrying for ever.
Everything else goes on syncing.

## Probe accounts

`probe.mjs` signs in as two throwaway accounts (`sekai.probe.a@…`,
`sekai.probe.b@…`) rather than creating them on every run: sign-up would have to
clear this project's confirmation step every time, and the mailer allows only a
couple of messages an hour, so it would hit a rate limit long before it proved
anything. **This project already has both, seeded and confirmed** — a normal run
needs nothing.

Reproducing them in another project: this one has `mailer_autoconfirm = false`
(`GET /auth/v1/settings` says so), which means an address has to be confirmed
before it can sign in — so create the two readers with **Add user → Create new
user** in the dashboard's Authentication tab, ticking *Auto Confirm User*, or
sign them up through the API and confirm the address yourself:

```sql
update auth.users set email_confirmed_at = now()
where email in ('sekai.probe.a@probe-sekai.com', 'sekai.probe.b@probe-sekai.com');
```

The passwords are the ones in `probe.mjs` (`probe-password-a` / `-b`). A probe
run against a project without them says exactly this rather than failing
obscurely.

Then:

```bash
node accounts/probe.mjs
```

It prints one line per check and a count. Point it at another project with
`SEKAI_SUPABASE_URL` and `SEKAI_SUPABASE_KEY` — the same two names the web
client's build reads (and the same project the desktop's build bakes in).

One leg is not part of a normal run: **deleting an account**. It needs an
account that may be destroyed, so it is opt-in:

```bash
SEKAI_PROBE_DELETE_EMAIL=… SEKAI_PROBE_DELETE_PASSWORD=… node accounts/probe.mjs
```

## What each client does with it

**The desktop window** uses the account as Nuvio always did: the sign-in screen,
the profile picker, the library, watch progress, watched marks, settings, addons
and collections all speak to this project through these functions. That is why
bringing the account back needed almost no Kotlin — only a build that names the
project. `NUVIO_SUPABASE_URL` / `NUVIO_SUPABASE_ANON_KEY` (in
`local.properties` or the environment) point it somewhere else.

**The phone's web client** signs in from its own Settings card ("SEKAI
account"). It keeps in step the things the phone and the window share:

*   **the library** — a favorite filed here is a library row there, and a title
    removed in the window is removed here on the next sync;
*   **watch positions** — where a title was left, both ways;
*   **history** — the titles that were opened, which the account keeps so a
    reinstall or a second phone gets them back (see
    [`sql/0007_history.sql`](sql/0007_history.sql)). The desktop window has no
    history screen of its own, so it neither reads nor writes this list; and
    because it is the account's list rather than this device's, the Library
    view's Clear button empties it for the account.

Every write goes through one queue (`client/src/account-queue.js`): writes about
the same title collapse into the newest, a burst becomes one call per dataset,
and a call that fails is retried on a backoff rather than lost — which is what
keeps a two-hour watch to a handful of requests and lands a session that
happened offline.

It deliberately does not sync its manga reading or its films-area library (both
keep their own stores with their own ids — see below), or episode marks (the 18+
shelf plays whole videos, and the anime and manga the phone tracks are tracked on
MyAnimeList).

The phone's own rows live in `htv:library:v1`, the same device storage the
Library view draws from, so nothing about the account is a second copy to keep
in step: `account-sync.js` pulls, merges, and pushes, and the view repaints.

## The wire contract

Two clients that disagree about an id, a unit or a key are two clients whose
libraries silently diverge, so all three are fixed here:

*   **ids.** A title is `<area>:<key>`: `ha:an:<anilist id>` anime,
    `ha:fm:<slug>` / `ha:ft:<slug>` films and series, `ha:ad:<hanime slug>` the
    18+ shelf (built in [`../server/src/addon.mjs`](../server/src/addon.mjs),
    which is also what the desktop's catalogue resolves). An episode of a series
    is `<id>:<season>:<episode>`. The phone speaks for `ha:ad:` and passes every
    other area over rather than guessing a translation.

*   **units.** `position`, `duration` and `last_watched` on a progress row are
    **milliseconds** and epoch milliseconds — the desktop sends
    `lastPositionMs`, `durationMs`, `lastUpdatedEpochMs`. The phone stores
    seconds (what the `<video>` element says) and converts at the two edges.

*   **keys.** A progress row is keyed by `progress_key`: the bare `content_id`
    for a film, `<content_id>_s<season>e<episode>` for an episode (the desktop's
    own `buildWatchProgressKey`). The library is keyed by
    `(user_id, profile_id, content_id, content_type)`, and the 18+ shelf's
    content type is `movie` on the wire — its catalogue is typed `hentai`, but
    `movie` is what the window's library and player read. History is keyed the
    same way and its timestamps are epoch milliseconds like the rest
    (`opened_at`), with the later opening winning on a conflict so two clients'
    clocks cannot move a title down the list.

*   **profiles.** Rows carry `profile_id`, the profile index 1..N the desktop's
    picker shows. The phone has no picker and writes to profile 1.

*   **writes are tagged.** Every push carries `p_origin_client_id`, a stable id
    minted once per install, so a client can tell its own events from another
    client's when it reads a delta log. The phone's is `sekai-phone-…` in
    `htv:account-client:v1`; the desktop's comes from its own settings.

A push of profiles, addons, plugins or provider credentials is
**authoritative** — everything not named is gone, which is what makes a deletion
on one client a deletion on the other. The library, watch progress and watched
marks are not: they push and delete by key, and keep a delta log so a client
that already has a snapshot only asks for what happened since.
