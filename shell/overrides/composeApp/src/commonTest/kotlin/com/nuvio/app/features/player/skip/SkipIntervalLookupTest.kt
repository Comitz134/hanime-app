package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipIntervalLookupTest {
    @Test
    fun resolvesImdbEpisodeFromPlayerMetadata() {
        assertEquals(
            SkipIntervalLookup.Imdb("tt1234567", season = 2, episode = 4),
            resolveSkipIntervalLookup("tt1234567:2:4", season = 2, episode = 4),
        )
    }

    @Test
    fun resolvesMalEpisodeEmbeddedInVideoId() {
        assertEquals(
            SkipIntervalLookup.Mal("62322", episode = 4),
            resolveSkipIntervalLookup("mal:62322:4", season = 1, episode = null),
        )
    }

    @Test
    fun usesActiveEpisodeForSeasonQualifiedMalId() {
        assertEquals(
            SkipIntervalLookup.Mal("63375", episode = 5),
            resolveSkipIntervalLookup("mal:63375:1:5", season = 1, episode = 5),
        )
    }

    @Test
    fun resolvesKitsuEpisodeEmbeddedInVideoId() {
        assertEquals(
            SkipIntervalLookup.Kitsu("12345", episode = 8),
            resolveSkipIntervalLookup("kitsu:12345:8", season = null, episode = null),
        )
    }

    @Test
    fun rejectsUnsupportedOrIncompleteIds() {
        assertNull(resolveSkipIntervalLookup("tmdb:123", season = 1, episode = 2))
        assertNull(resolveSkipIntervalLookup("mal:62322", season = 1, episode = null))
    }

    @Test
    fun resolvesThisProjectsAnimeIdByItsAniListId() {
        assertEquals(
            SkipIntervalLookup.AniList("21", episode = 5),
            resolveSkipIntervalLookup("ha:an:21:1:5", season = 1, episode = 5),
        )
        // The catalogue writes the tail on every episode; a title id without
        // one still resolves when the player knows which episode is playing.
        assertEquals(
            SkipIntervalLookup.AniList("21", episode = 5),
            resolveSkipIntervalLookup("ha:an:21", season = null, episode = 5),
        )
    }

    @Test
    fun resolvesThisProjectsMalSpellingAsAMalId() {
        assertEquals(
            SkipIntervalLookup.Mal("62322", episode = 4),
            resolveSkipIntervalLookup("ha:ml:62322:1:4", season = 1, episode = 4),
        )
    }

    @Test
    fun aTitleWithoutAnEpisodeIsNotAnEpisode() {
        // `ha:an:21` is a title: its "21" is the AniList id, never an episode.
        assertNull(resolveSkipIntervalLookup("ha:an:21", season = 1, episode = null))
        // The other two areas this catalogue lists carry no id a skip provider
        // is keyed by — a film's slug holds a TMDB id, an 18+ video's its slug.
        assertNull(resolveSkipIntervalLookup("ha:fm:some-film-1234", season = null, episode = null))
        assertNull(resolveSkipIntervalLookup("ha:ad:some-video", season = null, episode = null))
    }
}
