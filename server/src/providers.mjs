// The register of embed providers this server can open by itself.
//
// LunarX is a name list: it answers with a `server` label and a `player_url`,
// and everything else about the provider is behind that URL. Historically that
// URL was always flixcloud; the register exists so that a second host is a
// single entry here rather than a second special case in the route, the addon
// and this module — which is how the last one spread.
//
// A provider entry is three things: how to recognise its embeds, what referer
// its CDN expects, and how to turn one embed into a media URL. Everything a
// caller needs is `isResolvableEmbed` (a yes/no for the stream picker, which
// must not make network calls to answer) and `resolveEmbed` (the work, done
// only when a player actually asks for the URL).
//
// A host that is *not* registered is not a failure: its embed travels to the
// client as `externalUrl`, which is where a page this server cannot open
// belongs. The first live survey (2,238 episodes across both the mainstream and
// the 18+ catalogue) found only flixcloud, so the register has one entry today;
// the survey is recorded in _mf/providers.json and _mf/providers2.json.

import { isFlixcloudEmbed, resolveStream, subtitleTracks, FLIX_REFERER } from './flixcloud.mjs';

const PROVIDERS = [
  {
    id: 'flixcloud',
    // The embed page, not merely the host: a flixcloud URL that is not an /e/
    // page is something this server has no reader for, so it must not count as
    // resolvable just because the domain matches.
    matches: isFlixcloudEmbed,
    referer: FLIX_REFERER,
    resolve: resolveStream,
    subtitles: subtitleTracks,
  },
];

/** The provider that claims this URL, or null when none does. */
export function providerFor(url) {
  return PROVIDERS.find((provider) => provider.matches(url)) ?? null;
}

/**
 * Whether an embed can be opened here — deliberately synchronous and offline:
 * the stream picker asks this for every source on every request, and a probe
 * that fetched the page would turn listing an episode into a network fan-out.
 */
export function isResolvableEmbed(url) {
  return providerFor(url) !== null;
}

/** Ids of the registered providers, for diagnostics and tests. */
export function providerIds() {
  return PROVIDERS.map((provider) => provider.id);
}

/**
 * An embed URL in, a media URL out: `{ ok, url, key, referer, provider }` or
 * `{ ok: false, code }`. The referer comes from the provider entry rather than
 * from its resolver, so a caller cannot forget it — every playlist and segment
 * below the master has to be read with the same one.
 */
export async function resolveEmbed(embedUrl) {
  const provider = providerFor(embedUrl);
  if (!provider) return { ok: false, code: 'unknown_provider' };

  const found = await provider.resolve(embedUrl);
  if (!found.ok) return { ...found, provider: provider.id };
  return { ...found, provider: provider.id, referer: provider.referer };
}

/**
 * The subtitle tracks an embed offers, in the same shape of answer as
 * `resolveEmbed`: `{ ok, tracks, provider }`, or `{ ok: false, code }` when
 * the embed cannot be read. A provider with no subtitles of its own is a miss,
 * not an error — the caller is listing what is available, and often that is
 * nothing.
 */
export async function resolveSubtitles(embedUrl) {
  const provider = providerFor(embedUrl);
  if (!provider) return { ok: false, code: 'unknown_provider' };
  if (!provider.subtitles) return { ok: false, code: 'no_subtitles', provider: provider.id };

  const found = await provider.subtitles(embedUrl);
  // The referer travels with the answer for the same reason it does on a
  // resolve: whoever relays these files has to read them as the provider, and
  // only this module knows which provider that is.
  return { ...found, provider: provider.id, referer: provider.referer };
}
