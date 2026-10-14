// opensubs.mjs — the OpenSubtitles v3 Stremio addon, as a subtitle source for
// the films & series area.
//
// The film doors hand out a player, not a subtitle list: probed live on
// 2026-10-10, vidcore, vidapi and the embos chain all resolve to a plain HLS
// master with no `#EXT-X-MEDIA` renditions, and none of their players ever
// fetches a `.srt`/`.vtt`/`.ass` of its own — so nothing in the door can be
// relayed. What the embos player *does* read is vidsrc.sh's own list
// (`data.vidsrc.sh/api.php`), and that list is empty for nearly every title
// (1 of 13 sampled).
//
// So the tracks come from a Stremio addon instead, which is the same shape of
// answer the anime area already gives and needs no account of our own:
//
//   GET /subtitles/movie/tt0133093.json        dozens of tracks
//   GET /subtitles/series/tt0903747:1:1.json   the episode's tracks
//
// It is keyed by **IMDb id**, which is the one field a film's slug does not
// carry — `fmovies.mjs` maps the slug's TMDB id to one before asking here.
// Every answer is cached for hours: a subtitle list moves with the release, not
// with the request.

const ADDON_BASE = 'https://opensubtitles-v3.strem.io';
const TIMEOUT_MS = 15_000;
const TTL_MS = 6 * 3600_000;
/** A player cannot show more than this many tracks usefully, and the list runs to dozens. */
const MAX_TRACKS = 40;

const cache = new Map();

/** Only the tests call this, and only so one case cannot seed the next. */
export function clearCache() {
  cache.clear();
}

function memo(key, produce) {
  const hit = cache.get(key);
  if (hit && hit.expires > Date.now()) return hit.value;
  const value = (async () => {
    try {
      return await produce();
    } catch (e) {
      cache.delete(key);   // a rejection is not an answer
      throw e;
    }
  })();
  cache.set(key, { value, expires: Date.now() + TTL_MS });
  return value;
}

/**
 * The addon speaks ISO 639-2 ('eng', 'ger', 'por'); a client matches a
 * preference against ISO 639-1 ('en', 'de', 'pt'), which is also what the
 * anime area's tracks carry. Both spellings of the same language appear in the
 * wild (ger/deu, fre/fra), so both are listed. Anything unmapped passes
 * through as it arrived: a code the client cannot read is better than a wrong
 * one, and the client treats an unknown code as "no match" either way.
 */
const ISO_639_1 = {
  eng: 'en', ger: 'de', deu: 'de', fre: 'fr', fra: 'fr', spa: 'es', por: 'pt',
  ita: 'it', rus: 'ru', ara: 'ar', jpn: 'ja', kor: 'ko', chi: 'zh', zho: 'zh',
  dut: 'nl', nld: 'nl', pol: 'pl', swe: 'sv', nor: 'no', dan: 'da', fin: 'fi',
  tur: 'tr', hin: 'hi', ben: 'bn', tam: 'ta', tel: 'te', mal: 'ml', urd: 'ur',
  per: 'fa', fas: 'fa', ind: 'id', vie: 'vi', tha: 'th', ukr: 'uk', heb: 'he',
  ell: 'el', gre: 'el', ces: 'cs', cze: 'cs', slk: 'sk', slo: 'sk', slv: 'sl',
  ron: 'ro', rum: 'ro', hun: 'hu', bul: 'bg', hrv: 'hr', srp: 'sr', bos: 'bs',
  mkd: 'mk', mac: 'mk', alb: 'sq', sqi: 'sq', lit: 'lt', lav: 'lv', est: 'et',
  cat: 'ca', glg: 'gl', eus: 'eu', baq: 'eu', isl: 'is', ice: 'is', gle: 'ga',
  cym: 'cy', wel: 'cy', afr: 'af', swa: 'sw', amh: 'am', nep: 'ne', sin: 'si',
  khm: 'km', lao: 'lo', mya: 'my', fil: 'tl', tgl: 'tl', may: 'ms', msa: 'ms',
};

/** 'eng' -> 'en'; an unknown code is returned trimmed as it arrived. */
export function languageCode(lang) {
  const raw = String(lang ?? '').trim().toLowerCase();
  if (!raw) return 'unknown';
  return ISO_639_1[raw] ?? raw;
}

/**
 * What tells two English tracks apart: the release the subtitle was timed
 * against, else the file's own name. Kept short — it travels as the `label`
 * beside the language, not as the language itself.
 */
function detailOf(entry) {
  const name = entry?.movieReleaseName || entry?.subtitleFileName || '';
  return String(name).replace(/\.(srt|ass|ssa|vtt)$/i, '').replace(/[\s_]+/g, ' ').trim().slice(0, 80) || undefined;
}

function getJson(path) {
  return fetch(ADDON_BASE + path, {
    headers: { accept: 'application/json', 'user-agent': 'SEKAI/1.0 (+self-hosted)' },
    signal: AbortSignal.timeout(TIMEOUT_MS),
  }).then(async (res) => {
    if (!res.ok) throw new Error(`opensubs: status ${res.status}`);
    return res.json();
  });
}

/**
 * The tracks an IMDb id has, in our own shape: `{ id, url, lang, label,
 * format }`. `url` is the addon's own download link under subs5.strem.io, which
 * answers a plain `.srt` (verified: `application/x-subrip`, ~100 KB for a
 * feature) and needs no referer — so the relay reads it as itself.
 *
 * A series needs the episode in the id (`tt0903747:1:1`) and a film must not
 * carry one; passing the wrong shape is an empty list, which is why `season`
 * and `episode` are read together.
 */
export async function openSubtitlesTracks({ imdbId, season, episode }) {
  const id = String(imdbId ?? '').trim();
  if (!id) return [];
  const isEpisode = Number.isFinite(season) && Number.isFinite(episode) && season > 0 && episode > 0;
  const type = isEpisode ? 'series' : 'movie';
  const key = isEpisode ? `${id}:${season}:${episode}` : id;

  // The episode separator stays a colon: that is the id shape the addon's own
  // player asks with (`…/series/tt0903747:1:1.json`), and percent-encoding it
  // is a different URL to a route that reads it as one.
  const path = `/subtitles/${type}/${encodeURIComponent(key).replace(/%3A/gi, ':')}.json`;
  const body = await memo(`subs:${type}:${key}`, () => getJson(path));
  const list = Array.isArray(body?.subtitles) ? body.subtitles : [];

  const seen = new Set();
  const out = [];
  for (const entry of list) {
    const url = typeof entry?.url === 'string' ? entry.url : '';
    if (!url.startsWith('https://') || seen.has(url)) continue;
    seen.add(url);
    out.push({
      id: String(entry.id ?? out.length),
      url,
      lang: languageCode(entry.lang),
      label: detailOf(entry),
      format: /\.vtt$/i.test(url) ? 'vtt' : 'srt',
    });
    if (out.length >= MAX_TRACKS) break;
  }
  return out;
}
