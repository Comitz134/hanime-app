// accounts/probe.mjs — exercises the account backend the way the clients do.
//
// Not a unit test: it talks to the *live* project over the same public API the
// desktop and the phone use (GoTrue for sign-in, PostgREST for every `sync_*`
// RPC), so what it proves is what a client gets — including the parts a unit
// test cannot see: that the functions exist under the names the Kotlin calls,
// that their result column names are the ones the clients deserialize, that RLS
// isolates two accounts, and that a repeated push changes nothing.
//
//   node accounts/probe.mjs
//
// Two throwaway accounts are created (or reused) under @example.com. A project
// with email confirmation on cannot sign them in until the addresses are
// confirmed; the script says so and prints the one statement to run — see
// accounts/README.md.
//
// The keys below are the project's *publishable* keys: public by design, the
// same ones baked into the desktop build and the web client bundle. They are
// not secrets, and there is no service-role key anywhere in this file.

// The phone's own payload builders, imported rather than copied: this probe is
// supposed to fail when the client's idea of a row changes, and a copy of that
// idea would only ever agree with itself. The module is pure — no storage, no
// network, no DOM — so it runs under node as it is.
import { historyPayload, libraryPayload, progressPayload } from '../server/client/src/account-model.js';

const SUPABASE_URL = (process.env.SEKAI_SUPABASE_URL ?? 'https://hijiurhppikjgammgdmg.supabase.co').replace(/\/+$/, '');
const PUBLISHABLE_KEY = process.env.SEKAI_SUPABASE_KEY ?? 'sb_publishable_nr-W-nQRbUEg7pJ3ipMUxA_fwqw6t5e';

// Not @example.com: GoTrue rejects that domain outright (`email_address_invalid`),
// and it is not a throwaway-service domain either, because those are refused by
// some projects. A plausible domain that nobody owns is enough — nothing is
// delivered to it, since these accounts are confirmed directly.
const ACCOUNT_A = { email: 'sekai.probe.a@probe-sekai.com', password: 'probe-password-a' };
const ACCOUNT_B = { email: 'sekai.probe.b@probe-sekai.com', password: 'probe-password-b' };

let passed = 0;
const failures = [];

function check(label, condition, detail) {
  if (condition) {
    passed += 1;
    console.log(`  ok   ${label}`);
  } else {
    failures.push(label);
    console.log(`  FAIL ${label}${detail === undefined ? '' : ` — ${JSON.stringify(detail)}`}`);
  }
}

async function request(path, { method = 'GET', token, body, headers = {} } = {}) {
  const response = await fetch(`${SUPABASE_URL}${path}`, {
    method,
    headers: {
      apikey: PUBLISHABLE_KEY,
      accept: 'application/json',
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const text = await response.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    json = text;
  }
  return { status: response.status, json, text };
}

async function rpc(name, params, token) {
  const result = await request(`/rest/v1/rpc/${name}`, {
    method: 'POST',
    token,
    body: params ?? {},
  });
  if (result.status >= 300) {
    throw new Error(`rpc ${name} → ${result.status}: ${result.text.slice(0, 300)}`);
  }
  return result.json;
}

/**
 * Signs a throwaway account in. Sign-in comes first, not sign-up: the project's
 * built-in mailer allows only a couple of messages an hour, so creating the
 * accounts on every run would fail on rate limit long before it failed on
 * anything interesting. The SQL that seeds them lives in accounts/README.md.
 */
async function session(email, password) {
  const signIn = await request('/auth/v1/token?grant_type=password', {
    method: 'POST',
    body: { email, password },
  });
  if (signIn.json?.access_token) return signIn.json.access_token;

  const signUp = await request('/auth/v1/signup', {
    method: 'POST',
    body: { email, password },
  });
  if (signUp.json?.access_token) return signUp.json.access_token;

  const reason = signIn.json?.error_description ?? signIn.json?.msg ?? signIn.text?.slice(0, 200);
  throw new Error(
    `cannot sign in as ${email}: ${reason}\n` +
      '  seed the probe accounts first (accounts/README.md, "Probe accounts"), or\n' +
      '  confirm a fresh signup with:\n' +
      `  update auth.users set email_confirmed_at = now() where email = '${email}';`,
  );
}

async function main() {
  console.log(`probe → ${SUPABASE_URL}`);

  const settings = await request('/auth/v1/settings');
  console.log(`auth settings: mailer_autoconfirm=${settings.json?.mailer_autoconfirm}`);

  const tokenA = await session(ACCOUNT_A.email, ACCOUNT_A.password);
  const tokenB = await session(ACCOUNT_B.email, ACCOUNT_B.password);
  console.log('both accounts signed in\n');

  // ---------------------------------------------------------------- profiles
  console.log('profiles');
  const profiles = await rpc('sync_pull_profiles', {}, tokenA);
  check('a fresh account is seeded with profile 1', Array.isArray(profiles) && profiles.length === 1 && profiles[0].profile_index === 1, profiles);
  check(
    'the profile carries the keys the desktop deserializes',
    profiles[0] && ['id', 'user_id', 'profile_index', 'name', 'avatar_color_hex', 'uses_primary_addons', 'uses_primary_plugins', 'pin_enabled', 'pin_locked_until', 'created_at', 'updated_at'].every((key) => key in profiles[0]),
    profiles[0],
  );

  await rpc('sync_push_profiles', {
    p_client_max_profiles: 4,
    p_profiles: [{ profile_index: 1, name: 'Probe One', avatar_color_hex: '#101010', uses_primary_addons: false, uses_primary_plugins: false }],
    p_origin_client_id: 'probe-client',
  }, tokenA);
  const renamed = await rpc('sync_pull_profiles', {}, tokenA);
  check('an authoritative push renames the profile', renamed[0]?.name === 'Probe One', renamed);
  check('a push naming every profile deletes nothing', renamed.length === 1, renamed);

  // ----------------------------------------------------------------- library
  console.log('\nlibrary');
  const item = {
    content_id: 'ha:an:21',
    content_type: 'series',
    name: 'ONE PIECE',
    poster: 'https://example.com/poster.jpg',
    poster_shape: 'POSTER',
    background: null,
    logo: null,
    description: 'probe',
    release_info: '1999',
    imdb_rating: 8.7,
    genres: ['Action'],
    addon_base_url: 'http://127.0.0.1:8787',
    added_at: 1760000000000,
  };
  const before = await rpc('sync_get_library_delta_cursor', { p_profile_id: 1 }, tokenA);
  await rpc('sync_push_library_items', { p_profile_id: 1, p_items: [item], p_origin_client_id: 'probe-client' }, tokenA);
  await rpc('sync_push_library_items', { p_profile_id: 1, p_items: [item], p_origin_client_id: 'probe-client' }, tokenA);
  const snapshot = await rpc('sync_pull_library', { p_profile_id: 1, p_limit: 500, p_offset: 0 }, tokenA);
  check('a pushed title is in the snapshot', snapshot.length === 1 && snapshot[0].content_id === 'ha:an:21', snapshot);
  check('pushing it twice keeps one row', snapshot.length === 1, snapshot.length);
  check('the snapshot has exactly the keys the desktop decodes', JSON.stringify(Object.keys(snapshot[0]).sort()) === JSON.stringify(['added_at', 'addon_base_url', 'background', 'content_id', 'content_type', 'description', 'genres', 'imdb_rating', 'logo', 'name', 'poster', 'poster_shape', 'release_info'].sort()), Object.keys(snapshot[0]));
  check('the cursor is a bare number', typeof before === 'number', before);

  const delta = await rpc('sync_pull_library_delta', { p_profile_id: 1, p_since_event_id: before, p_limit: 500 }, tokenA);
  check('the delta reports the push once per call', delta.length === 2 && delta.every((event) => event.operation === 'upsert'), delta.length);
  check('a delta event carries the item', delta[0]?.content_id === 'ha:an:21' && delta[0]?.name === 'ONE PIECE', delta[0]);

  await rpc('sync_delete_library_items', { p_profile_id: 1, p_keys: [{ content_id: 'ha:an:21', content_type: 'series' }], p_origin_client_id: 'probe-client' }, tokenA);
  const afterDelete = await rpc('sync_pull_library', { p_profile_id: 1, p_limit: 500, p_offset: 0 }, tokenA);
  check('a delete removes the row', afterDelete.length === 0, afterDelete);

  // ---------------------------------------------------------- watch progress
  console.log('\nwatch progress');
  await rpc('sync_push_watch_progress', {
    p_profile_id: 1,
    p_entries: [{ progress_key: 'ha:an:21:1:5', content_id: 'ha:an:21', content_type: 'series', video_id: 'ha:an:21:1:5', season: 1, episode: 5, position: 42000, duration: 1440000, last_watched: 1760000001000 }],
    p_origin_client_id: 'probe-client',
  }, tokenA);
  await rpc('sync_push_watch_progress', {
    p_profile_id: 1,
    p_entries: [{ progress_key: 'ha:an:21:1:5', content_id: 'ha:an:21', content_type: 'series', video_id: 'ha:an:21:1:5', season: 1, episode: 5, position: 99000, duration: 1440000, last_watched: 1760000002000 }],
    p_origin_client_id: 'probe-client',
  }, tokenA);
  const progress = await rpc('sync_pull_watch_progress', { p_profile_id: 1 }, tokenA);
  check('one progress key, latest position', progress.length === 1 && progress[0].position === 99000, progress);
  check('progress columns decode as named', progress[0]?.progress_key === 'ha:an:21:1:5' && progress[0]?.season === 1 && progress[0]?.episode === 5, progress[0]);
  const progressLimit = await rpc('sync_pull_watch_progress', { p_profile_id: 1, p_limit: 1 }, tokenA);
  check('the optional p_limit is accepted', progressLimit.length === 1, progressLimit.length);

  // ------------------------------------------------- the phone's own rows
  //
  // The phone is the other client of this same account, and it does not
  // hand-write what it sends: `client/src/account-model.js` builds the payload
  // from its own records. Pushing *those* payloads here is what makes this run
  // a check on the client rather than on a hand-written idea of it — a renamed
  // field or a position left in seconds fails here instead of filing a row the
  // desktop window cannot read.
  console.log('\nthe phone\'s own rows');
  const phoneSlug = `probe-phone-${Date.now()}`;
  const phoneItem = libraryPayload({ slug: phoneSlug, name: 'Probe Phone', cover: 'https://example.com/phone.jpg', at: 1760000005000 });
  await rpc('sync_push_library_items', { p_profile_id: 1, p_items: [phoneItem], p_origin_client_id: 'sekai-phone-probe' }, tokenA);
  const phoneLibrary = (await rpc('sync_pull_library', { p_profile_id: 1, p_limit: 500, p_offset: 0 }, tokenA))
    .find((row) => row.content_id === phoneItem.content_id);
  check("the phone's favorite is the id the desktop's catalogue builds", phoneItem.content_id === `ha:ad:${phoneSlug}`, phoneItem.content_id);
  check('and it arrives as the type the window draws', phoneLibrary?.content_type === 'movie', phoneLibrary?.content_type);
  check('with the name and cover this phone holds', phoneLibrary?.name === 'Probe Phone' && phoneLibrary?.poster === 'https://example.com/phone.jpg', phoneLibrary);

  const phoneProgress = progressPayload(phoneSlug, { t: 90, d: 300, at: 1760000006000 });
  await rpc('sync_push_watch_progress', { p_profile_id: 1, p_entries: [phoneProgress], p_origin_client_id: 'sekai-phone-probe' }, tokenA);
  const phonePulled = (await rpc('sync_pull_watch_progress', { p_profile_id: 1 }, tokenA))
    .find((row) => row.progress_key === `ha:ad:${phoneSlug}`);
  // Seconds on the phone, milliseconds on the wire: 90s of a 300s video.
  check("the phone's position crosses in milliseconds", phonePulled?.position === 90000 && phonePulled?.duration === 300000, phonePulled);
  check('the key is the bare content id, as the window writes it', phonePulled?.progress_key === phonePulled?.content_id, phonePulled);
  check('the moment watched travels with it', phonePulled?.last_watched === 1760000006000, phonePulled?.last_watched);

  // And the phone takes its own rows back out — a title finished or cleared on
  // the device is a deletion on the account, or the window keeps showing it.
  await rpc('sync_delete_library_items', { p_profile_id: 1, p_keys: [{ content_id: phoneItem.content_id, content_type: 'movie' }], p_origin_client_id: 'sekai-phone-probe' }, tokenA);
  await rpc('sync_delete_watch_progress', { p_profile_id: 1, p_keys: [phoneProgress.progress_key], p_origin_client_id: 'sekai-phone-probe' }, tokenA);
  const phoneGone = await rpc('sync_pull_library', { p_profile_id: 1, p_limit: 500, p_offset: 0 }, tokenA);
  check(
    'the phone can take its own rows back out',
    !phoneGone.some((row) => row.content_id === phoneItem.content_id) &&
      !(await rpc('sync_pull_watch_progress', { p_profile_id: 1 }, tokenA)).some((row) => row.progress_key === phoneProgress.progress_key),
    phoneGone,
  );

  // --------------------------------------------------------------- history
  //
  // The fourth dataset (`0007_history.sql`), and the phone's own: the list of
  // titles that were opened. It arrived after the project did, so a run against
  // a project that has not applied the migration reports it and carries on —
  // the other three datasets are still checked, which is exactly the state the
  // phone is in when it is newer than the backend.
  console.log('\nhistory');
  const historyReady = await (async () => {
    try {
      await rpc('sync_pull_history', { p_profile_id: 1, p_limit: 1, p_offset: 0 }, tokenA);
      return true;
    } catch (e) {
      console.log(`  skip history — 0007_history.sql is not applied (${String(e.message).slice(0, 60)})`);
      return false;
    }
  })();

  if (historyReady) {
    const opening = historyPayload({ slug: 'probe-phone-open', name: 'Probe Opened', cover: 'https://example.com/opened.jpg', brand: 'Probe Studio', at: 1760000007000 });
    await rpc('sync_push_history', { p_profile_id: 1, p_entries: [opening], p_origin_client_id: 'sekai-phone-probe' }, tokenA);
    const history = await rpc('sync_pull_history', { p_profile_id: 1 }, tokenA);
    check('an opening round-trips under the shared id space', history.length === 1 && history[0].content_id === `ha:ad:probe-phone-open`, history);
    check('it keeps the brand, which a library row has nowhere to put', history[0]?.brand === 'Probe Studio', history[0]);

    // Opening it again is the same row, and the later opening is the one kept —
    // two clients' clocks must not be able to move a title down the list.
    await rpc('sync_push_history', { p_profile_id: 1, p_entries: [{ ...opening, opened_at: 1760000009000 }], p_origin_client_id: 'sekai-phone-probe' }, tokenA);
    await rpc('sync_push_history', { p_profile_id: 1, p_entries: [{ ...opening, opened_at: 1760000008000 }], p_origin_client_id: 'probe-client' }, tokenA);
    const again = await rpc('sync_pull_history', { p_profile_id: 1 }, tokenA);
    check('the same title twice is one row, and the later opening wins', again.length === 1 && again[0].opened_at === 1760000009000, again);

    const historyBefore = await rpc('sync_get_history_delta_cursor', { p_profile_id: 1 }, tokenA);
    check('the history cursor is a bare number', typeof historyBefore === 'number', historyBefore);
    const historyDelta = await rpc('sync_pull_history_delta', { p_profile_id: 1, p_since_event_id: 0, p_limit: 500 }, tokenA);
    check('the delta log has the pushes in it', historyDelta.length === 3 && historyDelta.every((event) => event.operation === 'upsert'), historyDelta.length);

    // Emptying the list is its own act: every row for the profile, with a delete
    // event each, so a second phone following the log drops them too.
    await rpc('sync_clear_history', { p_profile_id: 1, p_origin_client_id: 'sekai-phone-probe' }, tokenA);
    const cleared = await rpc('sync_pull_history', { p_profile_id: 1 }, tokenA);
    check('clearing the list clears it', cleared.length === 0, cleared);
    const afterClear = await rpc('sync_pull_history_delta', { p_profile_id: 1, p_since_event_id: 0, p_limit: 500 }, tokenA);
    check('and says so in the log', afterClear.some((event) => event.operation === 'delete'), afterClear.length);

    // A reader who never signed in, or another account entirely: history is as
    // owner-scoped as everything else.
    check('another account sees no history of its own', (await rpc('sync_pull_history', { p_profile_id: 1 }, tokenB)).length === 0);
  }

  // --------------------------------------------------------------- watched
  console.log('\nwatched marks');
  const film = { content_id: 'ha:fm:f1-911430', content_type: 'movie', title: 'Probe Film', season: null, episode: null, watched_at: 1760000003000 };
  await rpc('sync_push_watched_items', { p_profile_id: 1, p_items: [film], p_origin_client_id: 'probe-client' }, tokenA);
  await rpc('sync_push_watched_items', { p_profile_id: 1, p_items: [film], p_origin_client_id: 'probe-client' }, tokenA);
  const watched = await rpc('sync_pull_watched_items', { p_profile_id: 1, p_page: 1, p_page_size: 500 }, tokenA);
  check('a film with no season is one row, not two', watched.length === 1, watched);
  check('no season comes back as null, not -1', watched[0]?.season === null && watched[0]?.episode === null, watched[0]);

  const episode = { content_id: 'ha:an:21', content_type: 'series', title: 'ONE PIECE', season: 1, episode: 5, watched_at: 1760000004000 };
  await rpc('sync_push_watched_items', { p_profile_id: 1, p_items: [episode], p_origin_client_id: 'probe-client' }, tokenA);
  const watchedTwo = await rpc('sync_pull_watched_items', { p_profile_id: 1, p_page: 1, p_page_size: 1 }, tokenA);
  check('paging returns one page at a time', watchedTwo.length === 1, watchedTwo.length);
  await rpc('sync_delete_watched_items', { p_profile_id: 1, p_keys: [{ content_id: 'ha:an:21', season: 1, episode: 5 }], p_origin_client_id: 'probe-client' }, tokenA);
  const watchedThree = await rpc('sync_pull_watched_items', { p_profile_id: 1, p_page: 1, p_page_size: 500 }, tokenA);
  check('the delete key matches season and episode', watchedThree.length === 1 && watchedThree[0].content_id === 'ha:fm:f1-911430', watchedThree);

  // ------------------------------------------------- settings, addons, etc.
  console.log('\nblobs, addons and plugins');
  await rpc('sync_push_profile_settings_blob', { p_profile_id: 1, p_platform: 'desktop', p_settings_json: { theme: 'dark', probe: true } }, tokenA);
  const blob = await rpc('sync_pull_profile_settings_blob', { p_profile_id: 1, p_platform: 'desktop' }, tokenA);
  check('the settings blob round-trips', blob[0]?.settings_json?.probe === true, blob);
  check('another platform is a different row', (await rpc('sync_pull_profile_settings_blob', { p_profile_id: 1, p_platform: 'mobile' }, tokenA)).length === 0);

  await rpc('sync_push_home_catalog_settings', { p_profile_id: 1, p_platform: 'shared', p_settings_json: { items: [] }, p_origin_client_id: 'probe-client' }, tokenA);
  check('home catalog settings round-trip', (await rpc('sync_pull_home_catalog_settings', { p_profile_id: 1, p_platform: 'shared' }, tokenA)).length === 1);

  await rpc('sync_push_collections', { p_profile_id: 1, p_collections_json: [{ name: 'probe' }], p_origin_client_id: 'probe-client' }, tokenA);
  const collections = await rpc('sync_pull_collections', { p_profile_id: 1 }, tokenA);
  check('collections round-trip', collections[0]?.collections_json?.[0]?.name === 'probe', collections);

  await rpc('sync_push_provider_credentials', { p_profile_id: 1, p_credentials: [{ provider: 'tmdb', credential_json: { api_key: 'not-a-real-key' } }], p_origin_client_id: 'probe-client' }, tokenA);
  const credentials = await rpc('sync_pull_provider_credentials', { p_profile_id: 1 }, tokenA);
  check('credentials round-trip', credentials.length === 1 && credentials[0].provider === 'tmdb', credentials);
  await rpc('sync_push_provider_credentials', { p_profile_id: 1, p_credentials: [], p_origin_client_id: 'probe-client' }, tokenA);
  check('removing a credential deletes it', (await rpc('sync_pull_provider_credentials', { p_profile_id: 1 }, tokenA)).length === 0);

  await rpc('sync_push_addons', {
    p_profile_id: 1,
    p_addons: [{ url: 'http://127.0.0.1:8787/addon/manifest.json', name: 'SEKAI', enabled: true, sort_order: 0 }],
    p_origin_client_id: 'probe-client',
  }, tokenA);
  await rpc('sync_push_plugins', { p_profile_id: 1, p_plugins: [{ url: 'https://example.com/plugin.js', name: 'probe', enabled: true, sort_order: 0 }] }, tokenA);

  // The desktop reads these two with a plain filtered select, so the shape of
  // the response is part of the contract.
  const addonRows = await request('/rest/v1/addons?select=*&profile_id=eq.1&order=sort_order.asc', { token: tokenA });
  check('the addons view answers a filtered select', addonRows.status === 200 && Array.isArray(addonRows.json) && addonRows.json.length === 1, addonRows.json);
  check(
    'the addons row has exactly the keys the desktop decodes',
    JSON.stringify(Object.keys(addonRows.json[0]).sort()) === JSON.stringify(['enabled', 'name', 'profile_id', 'sort_order', 'url']),
    Object.keys(addonRows.json[0]),
  );
  const pluginRows = await request('/rest/v1/plugins?select=*&profile_id=eq.1&order=sort_order.asc', { token: tokenA });
  check('the plugins view answers a filtered select', pluginRows.status === 200 && pluginRows.json.length === 1, pluginRows.json);
  check(
    'the plugins row has exactly the keys the desktop decodes',
    JSON.stringify(Object.keys(pluginRows.json[0]).sort()) === JSON.stringify(['enabled', 'name', 'profile_id', 'sort_order', 'url']),
    Object.keys(pluginRows.json[0]),
  );

  // ------------------------------------------------------------------ pins
  console.log('\npins and locks');
  await rpc('set_profile_pin', { p_profile_id: 1, p_pin: '4321' }, tokenA);
  const wrong = await rpc('verify_profile_pin', { p_profile_id: 1, p_pin: '0000' }, tokenA);
  const right = await rpc('verify_profile_pin', { p_profile_id: 1, p_pin: '4321' }, tokenA);
  check('a wrong PIN is refused', wrong.unlocked === false, wrong);
  check('the right PIN unlocks', right.unlocked === true, right);
  const locks = await rpc('sync_pull_profile_locks', {}, tokenA);
  check('the lock list reports the pin', locks[0]?.pin_enabled === true, locks);
  await rpc('clear_profile_pin', { p_profile_id: 1, p_current_pin: '4321' }, tokenA);
  check('the pin can be cleared', (await rpc('sync_pull_profile_locks', {}, tokenA))[0]?.pin_enabled === false);

  // ------------------------------------------------------- isolation (RLS)
  console.log('\ntwo accounts cannot see each other');
  await rpc('sync_push_library_items', { p_profile_id: 1, p_items: [item], p_origin_client_id: 'probe-client' }, tokenA);
  const bLibrary = await rpc('sync_pull_library', { p_profile_id: 1, p_limit: 500, p_offset: 0 }, tokenB);
  check("account B's library is empty", bLibrary.length === 0, bLibrary);
  const bProfiles = await rpc('sync_pull_profiles', {}, tokenB);
  check("account B has its own profile", bProfiles.length === 1 && bProfiles[0].name !== 'Probe One', bProfiles);
  const bDirect = await request('/rest/v1/library_items?select=content_id&profile_id=eq.1', { token: tokenB });
  check('a direct table select does not leak A rows', bDirect.json.length === 0, bDirect.json);
  const bWatchedDirect = await request('/rest/v1/watched_items?select=*', { token: tokenB });
  check('B reads none of A watched marks', bWatchedDirect.json.length === 0, bWatchedDirect.json);
  const anonRead = await request('/rest/v1/library_items?select=content_id', {});
  check('signed out, the tables answer nothing', Array.isArray(anonRead.json) && anonRead.json.length === 0, anonRead.json);

  // -------------------------------------------- supporter answers and link --
  console.log('\nsupporter programme and the device link');
  check('no membership is reported', (await rpc('get_my_member_access', {}, tokenA)).length === 0);
  check('no membership overview is reported', (await rpc('get_my_membership_overview', {}, tokenA)).length === 0);
  check('no avatar catalogue is offered', (await rpc('get_avatar_catalog', {}, tokenA)).length === 0);

  const started = await rpc('start_device_login_session', {
    p_device_nonce: 'probe-nonce',
    p_redirect_base_url: `${SUPABASE_URL}/link`,
    p_device_name: 'probe',
    p_device_type: 'desktop',
  });
  check('a device code is issued to a signed-out caller', started.length === 1 && /^[A-Z0-9]{6}$/.test(started[0].user_code), started);
  check('polling before approval says pending', (await rpc('poll_tv_login_session', { p_code: started[0].device_code, p_device_nonce: 'probe-nonce' }))[0]?.status === 'pending');
  await rpc('approve_device_login_session', { p_user_code: started[0].user_code.replace(/(.{3})/, '$1-') }, tokenA);
  check('after approval the window is told', (await rpc('poll_tv_login_session', { p_code: started[0].device_code, p_device_nonce: 'probe-nonce' }))[0]?.status === 'approved');
  check("another window's nonce does not poll the same code", (await rpc('poll_tv_login_session', { p_code: started[0].device_code, p_device_nonce: 'someone-else' }))[0]?.status === 'denied');

  // ------------------------------------------- the device link, end to end --
  // The whole flow a real window walks: ask for a code, have a signed-in client
  // approve it, poll until "approved", then cash the code in for a session.
  console.log('\nthe device link, end to end');
  const windowNonce = `probe-window-${Date.now()}`;
  const linkStart = await rpc('start_device_login_session', {
    p_device_nonce: windowNonce,
    p_redirect_base_url: `${SUPABASE_URL}/link`,
    p_device_name: 'probe window',
    p_device_type: 'desktop',
  });
  await rpc('approve_device_login_session', { p_user_code: linkStart[0].user_code.replace(/(.{3})/, '$1-') }, tokenA);
  const exchange = await request('/functions/v1/tv-logins-exchange', {
    method: 'POST',
    body: { code: linkStart[0].device_code, device_nonce: windowNonce },
  });
  check('the exchange hands the window a session', exchange.status === 200 && typeof exchange.json?.access_token === 'string', exchange.json);
  check(
    'that session is the approving account',
    exchange.json?.access_token
      ? (await rpc('sync_pull_profiles', {}, exchange.json.access_token))[0]?.name === 'Probe One'
      : false,
  );
  const replay = await request('/functions/v1/tv-logins-exchange', {
    method: 'POST',
    body: { code: linkStart[0].device_code, device_nonce: windowNonce },
  });
  check('the code is single use', replay.status >= 400, replay.status);

  // -------------------------------------------------------------- devices
  await rpc('register_current_device', { p_installation_id: 'probe-install', p_client_name: 'probe', p_client_version: '1.0', p_platform: 'desktop', p_device_name: 'probe machine' }, tokenA);
  const devices = await request('/rest/v1/devices?select=installation_id,platform', { token: tokenA });
  check('the device is registered on the account', devices.json.length === 1 && devices.json[0].platform === 'desktop', devices.json);
  const bDevices = await request('/rest/v1/devices?select=installation_id', { token: tokenB });
  check('B sees no devices of its own account', bDevices.json.length === 0, bDevices.json);

  // ------------------------------------------------------- delete account --
  // Opt-in and destructive: it removes the account it is pointed at, so it is
  // not part of a normal run. Seed a throwaway account (accounts/README.md) and
  //
  //   SEKAI_PROBE_DELETE_EMAIL=… SEKAI_PROBE_DELETE_PASSWORD=… node accounts/probe.mjs
  if (process.env.SEKAI_PROBE_DELETE_EMAIL && process.env.SEKAI_PROBE_DELETE_PASSWORD) {
    console.log('\ndeleting an account');
    const doomedEmail = process.env.SEKAI_PROBE_DELETE_EMAIL;
    const doomedToken = await session(doomedEmail, process.env.SEKAI_PROBE_DELETE_PASSWORD);
    await rpc('sync_push_library_items', { p_profile_id: 1, p_items: [item], p_origin_client_id: 'probe-client' }, doomedToken);
    const deletion = await request('/functions/v1/delete-account', { method: 'POST', token: doomedToken, body: {} });
    check('the delete-account function accepts the request', deletion.status === 200, deletion);

    // The token itself is not revoked — it is a signed JWT, and PostgREST has no
    // way to know the user behind it is gone. What *is* gone is every row, so
    // the same token now reads nothing, and a write it attempts is refused by
    // the foreign key. That is the honest statement of what deletion means here.
    const doomedLibrary = await rpc('sync_pull_library', { p_profile_id: 1, p_limit: 500, p_offset: 0 }, doomedToken);
    check('the deleted account has no data left', doomedLibrary.length === 0, doomedLibrary);
    const doomedProfiles = await rpc('sync_pull_profiles', {}, doomedToken);
    check('not even its profile survives', doomedProfiles.length === 0, doomedProfiles);
    const doomedWrite = await request('/rest/v1/rpc/sync_push_library_items', {
      method: 'POST',
      token: doomedToken,
      body: { p_profile_id: 1, p_items: [item], p_origin_client_id: 'probe-client' },
    });
    check('a stale session cannot write against a deleted account', doomedWrite.status >= 400, doomedWrite.status);

    const signInAgain = await request('/auth/v1/token?grant_type=password', {
      method: 'POST',
      body: { email: doomedEmail, password: process.env.SEKAI_PROBE_DELETE_PASSWORD },
    });
    check('the account is gone from auth', signInAgain.status >= 400, signInAgain.json);
  }

  console.log(`\n${passed} checks passed, ${failures.length} failed`);
  if (failures.length > 0) {
    console.log('failed:');
    for (const failure of failures) console.log(`  - ${failure}`);
    process.exitCode = 1;
  }
}

main().catch((error) => {
  console.error(`\nprobe stopped: ${error.message}`);
  process.exitCode = 1;
});
