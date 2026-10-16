package com.nuvio.app.features.player.skip

internal sealed interface SkipIntervalLookup {
    data class Imdb(val imdbId: String, val season: Int, val episode: Int) : SkipIntervalLookup
    data class Mal(val malId: String, val episode: Int) : SkipIntervalLookup
    data class Kitsu(val kitsuId: String, val episode: Int) : SkipIntervalLookup

    /**
     * This project's own anime id — `ha:an:<anilist id>`, the shape the
     * catalogue in `server/` gives every title it lists (`ha:an:21:1:5` for
     * its first episode, in the same tail `ha:ft:` uses).
     *
     * It is its own case because it is its own id space: AniList's numeric id,
     * not MyAnimeList's. AniList carries MAL's id on every record and this
     * project's own server already answers that hop (`GET /api/anime/<id>` →
     * `details.malId`, the one the scrobbles and the plan writes use), so the
     * skip providers — which are keyed by MAL id — are one lookup away.
     */
    data class AniList(val anilistId: String, val episode: Int) : SkipIntervalLookup
}

internal fun resolveSkipIntervalLookup(
    videoId: String?,
    season: Int?,
    episode: Int?,
): SkipIntervalLookup? {
    val normalizedId = videoId?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val parts = normalizedId.split(':')

    return when {
        normalizedId.startsWith("mal:", ignoreCase = true) -> {
            val malId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
            val embeddedEpisode = parts.takeIf { it.size >= 3 }?.lastOrNull()?.toIntOrNull()
            val resolvedEpisode = episode ?: embeddedEpisode ?: return null
            SkipIntervalLookup.Mal(malId = malId, episode = resolvedEpisode)
        }

        normalizedId.startsWith("kitsu:", ignoreCase = true) -> {
            val kitsuId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
            val embeddedEpisode = parts.takeIf { it.size >= 3 }?.lastOrNull()?.toIntOrNull()
            val resolvedEpisode = episode ?: embeddedEpisode ?: return null
            SkipIntervalLookup.Kitsu(kitsuId = kitsuId, episode = resolvedEpisode)
        }

        parts.firstOrNull()?.startsWith("tt", ignoreCase = true) == true -> {
            val resolvedSeason = season ?: parts.getOrNull(1)?.toIntOrNull() ?: return null
            val resolvedEpisode = episode ?: parts.getOrNull(2)?.toIntOrNull() ?: return null
            SkipIntervalLookup.Imdb(
                imdbId = parts.first(),
                season = resolvedSeason,
                episode = resolvedEpisode,
            )
        }

        // This project's own ids, read the way the addon writes them: `ha:an:21:1:5`
        // is AniList 21's episode five, `ha:ml:21:1:5` the same episode of the same
        // title entered through its MyAnimeList id. The tail is only a tail when
        // both of its numbers are there — `ha:an:21` is the title and nothing else,
        // so its "21" is never mistaken for an episode.
        isProjectAnimeId(parts) -> {
            val key = parts[2].takeIf(String::isNotBlank) ?: return null
            val embeddedEpisode = parts.takeIf { it.size >= 5 }?.lastOrNull()?.toIntOrNull()
            val resolvedEpisode = episode ?: embeddedEpisode ?: return null
            SkipIntervalLookup.AniList(anilistId = key, episode = resolvedEpisode)
        }

        isProjectMalId(parts) -> {
            val malId = parts[2].takeIf(String::isNotBlank) ?: return null
            val embeddedEpisode = parts.takeIf { it.size >= 5 }?.lastOrNull()?.toIntOrNull()
            val resolvedEpisode = episode ?: embeddedEpisode ?: return null
            SkipIntervalLookup.Mal(malId = malId, episode = resolvedEpisode)
        }

        // The rest of this project's ids carry no id any skip provider is keyed
        // by: a film's slug holds a TMDB id (`ha:fm:<slug>-<tmdb id>`) and an
        // adult video's its slug. They are answered by the meta's own IMDb id
        // where there is one, which the player passes in for a movie.
        else -> null
    }
}

private fun isProjectAnimeId(parts: List<String>): Boolean =
    parts.size >= 3 && parts[0].equals("ha", ignoreCase = true) &&
        parts[1].equals("an", ignoreCase = true)

private fun isProjectMalId(parts: List<String>): Boolean =
    parts.size >= 3 && parts[0].equals("ha", ignoreCase = true) &&
        parts[1].equals("ml", ignoreCase = true)
