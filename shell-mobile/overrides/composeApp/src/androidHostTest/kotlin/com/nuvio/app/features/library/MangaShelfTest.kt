package com.nuvio.app.features.library

import com.nuvio.app.features.manga.MangaLibraryItem
import com.nuvio.app.features.manga.parseMalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two rules the manga shelf's order rests on, held on a JVM.
 *
 * The bug this exists for: "Last read" is the sort a shelf is wanted in, and it
 * is only true if two things are true — a title nobody has read sorts *after*
 * everything read, not before it on a zero stamp, and a tie falls back to a
 * total order instead of to whatever the server happened to send. Both were
 * decided by eye on a phone once; a phone is a slow and imprecise way to find
 * out that `sortedByDescending` put every unread title first.
 *
 * The parse is here for the same reason: MyAnimeList's `updated_at` is the only
 * last-read stamp an import can bring, and reading it wrongly — as 1970, or in
 * the wrong timezone — would silently reorder the whole shelf rather than fail.
 */
class MangaShelfTest {

    private fun item(
        id: String,
        title: String = id,
        at: Long = 0L,
        read: Long = 0L,
        number: String = "",
    ) = MangaLibraryItem(id = id, title = title, at = at, read = read, number = number)

    @Test
    fun `last read puts the most recently read first and the never-read last`() {
        val items = listOf(
            item("unread", at = 900L),
            item("read-long-ago", read = 100L),
            item("read-yesterday", read = 500L),
            item("read-now", read = 1_000L),
        )

        val order = sortShelf(items, MangaShelfSort.LAST_READ).map { it.id }

        assertEquals(listOf("read-now", "read-yesterday", "read-long-ago", "unread"), order)
    }

    @Test
    fun `titles that were never read keep the order they were added in`() {
        val items = listOf(
            item("oldest", at = 100L),
            item("newest", at = 300L),
            item("middle", at = 200L),
        )

        val order = sortShelf(items, MangaShelfSort.LAST_READ).map { it.id }

        assertEquals(listOf("newest", "middle", "oldest"), order)
    }

    @Test
    fun `a title read at the same moment as another falls back to its title`() {
        val items = listOf(
            item("b", title = "Berserk", read = 500L),
            item("a", title = "Akira", read = 500L),
        )

        val order = sortShelf(items, MangaShelfSort.LAST_READ).map { it.title }

        assertEquals(listOf("Akira", "Berserk"), order)
    }

    @Test
    fun `recently added still means added, not read`() {
        val items = listOf(
            item("read-today-added-early", at = 100L, read = 9_000L),
            item("added-late", at = 500L),
        )

        val order = sortShelf(items, MangaShelfSort.RECENT).map { it.id }

        assertEquals(listOf("added-late", "read-today-added-early"), order)
    }

    @Test
    fun `myanimelist timestamps parse to the epoch millisecond they name`() {
        assertEquals(1_714_979_289_000L, parseMalTime("2024-05-06T07:08:09+00:00"))
        // The same instant at +02:00 — the offset moves the clock, not the moment.
        assertEquals(1_714_972_089_000L, parseMalTime("2024-05-06T07:08:09+02:00"))
        assertEquals(1_704_067_200_000L, parseMalTime("2024-01-01T00:00:00+00:00"))
        assertEquals(1_704_067_200_000L, parseMalTime("2024-01-01T00:00:00Z"))
    }

    @Test
    fun `anything that is not a myanimelist timestamp is no stamp at all`() {
        // 0, not 1970: a row with a bogus stamp would outrank every real one.
        assertEquals(0L, parseMalTime(null))
        assertEquals(0L, parseMalTime(""))
        assertEquals(0L, parseMalTime("yesterday"))
        assertEquals(0L, parseMalTime("2024-13-45T00:00:00+00:00"))
        assertEquals(0L, parseMalTime("2024-05-06"))
    }
}
