package app.hanime.shell;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The subtitle tracks a title offers — the protocol's `subtitles` resource,
 * the JVM twin of the desktop server's `subtitles()` in `server/src/addon.mjs`.
 *
 * Where each area's tracks come from is decided by what its player actually
 * has, which is the same finding the desktop made:
 *
 *   * **Anime** — the embed page names its own files, and the provider's page
 *     is the same evidence on both clients. `FlixCloud.subtitles` reads them
 *     off that page, so an anime episode carries whatever LunarX's player
 *     carries, in the languages it carries them in.
 *   * **Films and series** — the doors have none: their players resolve to a
 *     plain HLS master with no `#EXT-X-MEDIA` renditions and never fetch a
 *     `.srt` of their own (checked live, and again on this build). So the
 *     tracks come from OpenSubtitles by IMDb id ({@link OpenSubs}), exactly as
 *     the desktop's films area does.
 *   * **The 18+ shelf** — burn-in only, and the desktop's answer for it is an
 *     empty list. An empty list is a real answer here too: "nothing to offer",
 *     not an error.
 *
 * A track travels as a relay link rather than the CDN URL it really is: the
 * link carries the provider's referer where one is needed, so the player
 * downloads the file through this server without knowing whose CDN it is and
 * without a header of its own.
 */
final class Subtitles {

    private static final String TAG = "ShellSubs";

    private Subtitles() {
    }

    /** The tracks for one title id, as the addon's `subtitles` array. */
    static JSONArray tracks(SekaiServer.Id ref, Context context) {
        JSONArray out = new JSONArray();
        try {
            if (ref.area.equals("fm") || ref.area.equals("ft")) {
                for (JSONObject track : filmTracks(ref)) out.put(track);
            } else if (ref.area.equals("an")) {
                for (JSONObject track : animeTracks(ref, context)) out.put(track);
            }
        } catch (Exception e) {
            // A subtitle list is never a failure: the player asks before it has
            // anything, and an empty list is what "none" looks like.
            Log.i(TAG, "subtitles for " + ref.area + ":" + ref.key + " -> " + e);
        }
        return out;
    }

    // ------------------------------------------------------------ films & series

    /** OpenSubtitles' tracks for a film or one episode of a series. */
    private static List<JSONObject> filmTracks(SekaiServer.Id ref) {
        List<JSONObject> out = new ArrayList<>();
        Integer tmdbId = Fmovies.tmdbIdOf(ref.key);
        if (tmdbId == null) return out;
        boolean series = ref.area.equals("ft");

        String imdb = Fmovies.imdbId(series ? "tv" : "movie", tmdbId);
        if (imdb == null) {
            Log.i(TAG, "no IMDb id for " + ref.key + " — no OpenSubtitles tracks");
            return out;
        }

        Integer season = series ? (ref.season == null ? 1 : ref.season) : null;
        Integer episode = series ? (ref.episode == null ? 1 : ref.episode) : null;
        List<OpenSubs.Track> tracks = OpenSubs.tracks(imdb, season, episode);
        for (OpenSubs.Track track : tracks) {
            try {
                out.add(trackJson(track.id, track.url, track.lang, track.label, track.format, null, null));
            } catch (Exception e) {
                Log.i(TAG, "unusable track " + track.id + ": " + e);
            }
        }
        return out;
    }

    // -------------------------------------------------------------------- anime

    /**
     * The embed's own tracks, from the first player that has any.
     *
     * LunarX hands out several embeds for an episode and only some of them are
     * the provider whose page carries subtitles; the first source with tracks
     * wins, because a second page load would be a second wait for a list that
     * the first one already answered.
     */
    private static List<JSONObject> animeTracks(SekaiServer.Id ref, Context context) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        if (context == null) return out;

        int ep = Math.max(1, ref.episode == null ? 1 : ref.episode);
        java.util.Map<String, String> query = new java.util.HashMap<>();
        query.put("ep", String.valueOf(ep));
        Anime.Result player =
                Anime.handle("/api/anime/" + SekaiServer.seg(ref.key) + "/player", query);
        if (player.status != 200) return out;

        JSONArray sources = new JSONObject(player.body).optJSONArray("sources");
        if (sources == null) return out;

        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            String url = source == null ? "" : source.optString("url", "");
            if (url.isEmpty() || !FlixCloud.isEmbed(url)) continue;

            List<FlixCloud.Subtitle> tracks = FlixCloud.subtitles(url);
            if (tracks.isEmpty()) continue;
            Log.i(TAG, "anime subtitles from " + url + ": " + tracks.size() + " tracks");

            String referer = FlixCloud.REFERER;
            String origin = Doors.originOf(url);
            Set<String> seen = new LinkedHashSet<>();
            for (int t = 0; t < tracks.size(); t++) {
                FlixCloud.Subtitle track = tracks.get(t);
                if (!seen.add(track.url)) continue;
                out.add(trackJson("flixcloud-" + t, track.url, track.lang,
                        track.label, track.format, referer, origin));
            }
            break;
        }
        return out;
    }

    // ------------------------------------------------------------------ shaping

    /**
     * One track in the addon's shape. `referer` and `origin` are the profile the
     * relay reads the file with — null for a CDN that answers a plain reader,
     * which is OpenSubtitles' own.
     */
    private static JSONObject trackJson(String id, String url, String lang, String label,
                                        String format, String referer, String origin)
            throws Exception {
        JSONObject track = new JSONObject()
                .put("id", id)
                .put("url", HlsRelay.linkVia(url, referer, origin, null))
                .put("lang", lang == null || lang.isEmpty() ? "unknown" : lang);
        if (label != null && !label.isEmpty()) track.put("label", label);
        if (format != null && !format.isEmpty()) track.put("format", format);
        return track;
    }
}
