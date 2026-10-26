package com.nuvio.app.features.manga

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire shape and the two sentences the shared list draws.
 *
 * The feed is a PostgREST view, so its column names are snake_case and a typo
 * in a `@SerialName` would not fail to compile - it would show a list of blank
 * titles on the phone. These tests hold the mapping and the count wording,
 * which is where "1 readers" or "You and 0 others" used to be easy to ship.
 */
class MangaSuggestionsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a feed row decodes the view's columns`() {
        val row = json.decodeFromString<MangaSuggestion>(
            """
            {
              "manga_id": "mangafire:berserk",
              "source": "mangafire",
              "title": "Berserk",
              "poster": "https://example.test/berserk.jpg",
              "source_name": "Mangafire",
              "language": "en",
              "readers": 3,
              "mine": true,
              "recommended_at": "2026-10-26T12:00:00+00:00"
            }
            """.trimIndent(),
        )

        assertEquals("mangafire:berserk", row.id)
        assertEquals("Berserk", row.title)
        assertEquals("Mangafire", row.sourceLabel)
        assertEquals(3L, row.readers)
        assertTrue(row.mine)
    }

    @Test
    fun `a missing source name falls back to the source id`() {
        val row = MangaSuggestion(id = "x", source = "comick", sourceName = "")
        assertEquals("comick", row.sourceLabel)
    }

    @Test
    fun `one reader is singular and none invites the first`() {
        assertEquals("Be the first", readerCountLabel(0))
        assertEquals("1 reader", readerCountLabel(1))
        assertEquals("12 readers", readerCountLabel(12))
    }

    @Test
    fun `the title page does not count this reader twice`() {
        assertEquals("You recommend it", recommendationLine(mine = true, readers = 1))
        assertEquals("You and 2 others recommend it", recommendationLine(mine = true, readers = 3))
        assertEquals("You and 1 other recommend it", recommendationLine(mine = true, readers = 2))
        assertEquals("Be the first to recommend it", recommendationLine(mine = false, readers = 0))
        assertEquals("1 reader recommends it", recommendationLine(mine = false, readers = 1))
        assertEquals("4 readers recommend it", recommendationLine(mine = false, readers = 4))
    }

    @Test
    fun `a suggestion card says whose it is before how many`() {
        assertFalse(MangaSuggestion(id = "a", readers = 2, mine = false).mine)
        assertEquals("2 readers", suggestionDetail(MangaSuggestion(id = "a", readers = 2)))
        assertEquals("You recommend it", suggestionDetail(MangaSuggestion(id = "a", readers = 2, mine = true)))
    }
}
