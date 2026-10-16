// The MyAnimeList activity panel's own arithmetic (features/mal).
//
// The panel is the answer to "is tracking actually working", so what is pinned
// here is every way the two sides can disagree: a write that never left, a row
// MyAnimeList does not hold, a count MyAnimeList has not caught up with. Each
// test is written so that losing its guard loses the test — a `troubles` that
// stops being computed is a panel that goes back to saying nothing, which is
// the failure this whole surface exists to end.

package com.nuvio.app.features.mal

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MalActivityTest {

    private val realStore = MalActivity.store
    private lateinit var journal: FakeJournalStore

    @BeforeTest
    fun installFakes() {
        journal = FakeJournalStore()
        MalActivity.store = journal
        MalActivity.forget()
    }

    @AfterTest
    fun restoreFakes() {
        MalActivity.forget()
        MalActivity.store = realStore
    }

    // ------------------------------------------------------------ the journal

    @Test
    fun `one title keeps one row, and the newest attempt is the one kept`() {
        MalActivity.record(PROFILE, attempt(malId = 21, episode = 3, succeeded = false, detail = "refused"))
        MalActivity.record(PROFILE, attempt(malId = 21, episode = 4, succeeded = true))

        val rows = MalActivity.attempts.value
        assertEquals(1, rows.size)
        assertEquals(4, rows.single().episode)
        assertTrue(rows.single().succeeded)
    }

    @Test
    fun `a title with no mal id is remembered by name, next to the ones that have ids`() {
        MalActivity.record(PROFILE, attempt(malId = 21, episode = 3, succeeded = true))
        MalActivity.record(
            PROFILE,
            attempt(malId = null, episode = 1, succeeded = false, title = "No Entry Here"),
        )
        assertEquals(2, MalActivity.attempts.value.size)
    }

    @Test
    fun `the journal survives the window closing`() {
        MalActivity.record(PROFILE, attempt(malId = 21, episode = 3, succeeded = true))
        // A restart is an empty memory and the same store: the written row is
        // put back where the store had it and read again from scratch.
        val onDisk = journal.values.getValue(PROFILE)
        MalActivity.forget()
        assertEquals(0, MalActivity.attempts.value.size)
        journal.values[PROFILE] = onDisk

        MalActivity.load(PROFILE)
        val restored = MalActivity.attempts.value.single()
        assertEquals(21L, restored.malId)
        assertEquals(3, restored.episode)
        assertTrue(restored.succeeded)
    }

    @Test
    fun `the journal is a shortlist, not a log`() {
        repeat(MalActivity.MAX_TITLES + 5) { index ->
            MalActivity.record(PROFILE, attempt(malId = index.toLong(), episode = 1, succeeded = true))
        }
        assertEquals(MalActivity.MAX_TITLES, MalActivity.attempts.value.size)
        // The newest is kept and the oldest is the one that made room.
        assertEquals((MalActivity.MAX_TITLES + 4).toLong(), MalActivity.attempts.value.first().malId)
        assertTrue(MalActivity.attempts.value.none { it.malId == 0L })
    }

    @Test
    fun `a deleted profile takes its journal with it`() {
        MalActivity.record(PROFILE, attempt(malId = 21, episode = 3, succeeded = true))
        MalActivity.clear(PROFILE)
        assertTrue(MalActivity.attempts.value.isEmpty())
        assertNull(journal.values[PROFILE])
    }

    // --------------------------------------------------------------- the rows

    @Test
    fun `a write this app made is what moved the row it landed on`() {
        val wroteAt = 1_700_000_000_000L
        val rows = malTrackedTitles(
            attempts = listOf(attempt(malId = 21, episode = 3, succeeded = true, at = wroteAt)),
            entries = listOf(entry(malId = 21, watched = 3, total = 12, updatedAt = wroteAt + 4_000L)),
            listLoaded = true,
        )

        val row = rows.single()
        assertEquals(MalMembership.ON_LIST, row.membership)
        assertEquals("3 / 12 episodes", row.standing)
        assertEquals(MalMovement.THIS_APP, row.lastMovedBy)
        assertEquals(wroteAt + 4_000L, row.lastMovedAtEpochMs)
        assertTrue(row.troubles.isEmpty(), row.troubles.toString())
    }

    @Test
    fun `a row moving hours after this app's write was moved somewhere else`() {
        val wroteAt = 1_700_000_000_000L
        val row = malTrackedTitles(
            attempts = listOf(attempt(malId = 21, episode = 3, succeeded = true, at = wroteAt)),
            entries = listOf(entry(malId = 21, watched = 9, total = 12, updatedAt = wroteAt + 7_200_000L)),
            listLoaded = true,
        ).single()

        assertEquals(MalMovement.SOMEWHERE_ELSE, row.lastMovedBy)
        assertEquals(wroteAt + 7_200_000L, row.lastMovedAtEpochMs)
        // Somebody else moved it forward — from nine episodes, not from three —
        // so there is nothing here for this app to complain about.
        assertTrue(row.troubles.isEmpty(), row.troubles.toString())
    }

    @Test
    fun `a title the list does not hold is said out loud`() {
        val row = malTrackedTitles(
            attempts = listOf(attempt(malId = 21, episode = 3, succeeded = true)),
            entries = listOf(entry(malId = 99, watched = 0, total = 0, updatedAt = 0L)),
            listLoaded = true,
        ).single()

        assertEquals(MalMembership.NOT_ON_LIST, row.membership)
        assertEquals("MyAnimeList has no row for it", row.standing)
        assertContains(row.troubles, "This app wrote to it, but MyAnimeList's list does not hold it.")
    }

    @Test
    fun `a list nobody has read yet is not a list that lacks the title`() {
        val row = malTrackedTitles(
            attempts = listOf(attempt(malId = 21, episode = 3, succeeded = true)),
            entries = emptyList(),
            listLoaded = false,
        ).single()

        assertEquals(MalMembership.UNKNOWN, row.membership)
        assertEquals("MyAnimeList's list has not been read yet", row.standing)
        assertTrue(row.troubles.isEmpty(), row.troubles.toString())
    }

    @Test
    fun `a count myanimelist has not caught up with is a trouble`() {
        val row = malTrackedTitles(
            attempts = listOf(attempt(malId = 21, episode = 5, succeeded = true)),
            // The write was accepted and the row still says two: the id it was
            // written to is not the row this device is looking at.
            entries = listOf(entry(malId = 21, watched = 2, total = 12, updatedAt = 0L)),
            listLoaded = true,
        ).single()

        assertContains(row.troubles, "MyAnimeList still says 2 episodes; this app reached 5.")
    }

    @Test
    fun `a refused write is the trouble, in the words the refuser used`() {
        val row = malTrackedTitles(
            attempts = listOf(
                attempt(malId = 21, episode = 5, succeeded = false, detail = "list status 401: invalid_token"),
            ),
            entries = listOf(entry(malId = 21, watched = 2, total = 12, updatedAt = 0L)),
            listLoaded = true,
        ).single()

        assertEquals(listOf("list status 401: invalid_token"), row.troubles)
        // The row is still told what MAL has, beside what went wrong.
        assertEquals("2 / 12 episodes", row.standing)
        assertTrue(row.lastAttempt.isRetryable)
    }

    @Test
    fun `an attempt that never found its entry has nothing to send again`() {
        val row = malTrackedTitles(
            attempts = listOf(
                MalWriteAttempt(
                    title = "Some Show",
                    malId = null,
                    episode = 4,
                    attemptedAtEpochMs = 1_700_000_000_000L,
                    succeeded = false,
                    detail = MalTracker.NO_MATCH_MESSAGE,
                ),
            ),
            entries = emptyList(),
            listLoaded = true,
        ).single()

        assertEquals("Some Show", row.title)
        assertEquals(listOf(MalTracker.NO_MATCH_MESSAGE), row.troubles)
        assertTrue(!row.lastAttempt.isRetryable)
    }

    @Test
    fun `what a title is at comes from the list, not from what this app sent`() {
        val rows = malTrackedTitles(
            attempts = listOf(attempt(malId = 21, episode = 5, succeeded = true)),
            entries = listOf(entry(malId = 21, title = "Frieren", watched = 5, total = 28, updatedAt = 0L)),
            listLoaded = true,
        )
        assertEquals("Frieren", rows.single().title)
        assertEquals("5 / 28 episodes", rows.single().standing)
    }

    // ------------------------------------------------------------- the summary

    @Test
    fun `the card stays quiet while every tracked title is where it should be`() {
        val rows = malTrackedTitles(
            attempts = listOf(attempt(malId = 21, episode = 3, succeeded = true)),
            entries = listOf(entry(malId = 21, watched = 3, total = 12, updatedAt = 0L)),
            listLoaded = true,
        )
        assertNull(malTroubleSummary(rows))
        assertNull(malTroubleSummary(emptyList()))
    }

    @Test
    fun `the card counts the titles that did not land`() {
        val rows = malTrackedTitles(
            attempts = listOf(
                attempt(malId = 21, episode = 3, succeeded = true),
                attempt(malId = 22, episode = 1, succeeded = false, detail = "refused"),
            ),
            entries = listOf(
                entry(malId = 21, watched = 3, total = 12, updatedAt = 0L),
                entry(malId = 22, watched = 0, total = 12, updatedAt = 0L),
            ),
            listLoaded = true,
        )
        assertEquals("1 title has not reached MyAnimeList — see MyAnimeList activity.", malTroubleSummary(rows))
    }

    // ------------------------------------------------------------- the words

    @Test
    fun `a moment is told in words a person reads`() {
        val now = 1_700_000_000_000L
        assertEquals("just now", agoInWords(now, now))
        assertEquals("just now", agoInWords(now - 20_000L, now))
        assertEquals("1 minute ago", agoInWords(now - 60_000L, now))
        assertEquals("5 minutes ago", agoInWords(now - 5 * 60_000L, now))
        // Minutes carry the count right up to the hour, and the hour takes
        // over from them rather than "60 minutes" being said.
        assertEquals("59 minutes ago", agoInWords(now - 3_599_000L, now))
        assertEquals("1 hour ago", agoInWords(now - 3_600_000L, now))
        assertEquals("1 hour ago", agoInWords(now - 100 * 60_000L, now))
        assertEquals("3 hours ago", agoInWords(now - 3 * 3_600_000L, now))
        assertEquals("1 day ago", agoInWords(now - 86_400_000L, now))
        assertEquals("6 days ago", agoInWords(now - 6 * 86_400_000L, now))
        assertEquals("40 days ago", agoInWords(now - 40 * 86_400_000L, now))
        // Past six weeks a day count stops being readable, and the week is the
        // unit a person actually counts in.
        assertEquals("10 weeks ago", agoInWords(now - 70 * 86_400_000L, now))
        // Nothing known is not a moment in the past, and a stamp from a clock
        // that ran ahead is not a negative age.
        assertEquals("just now", agoInWords(0L, now))
        assertEquals("just now", agoInWords(now + 5_000L, now))
    }

    @Test
    fun `mal's own statuses are said the way this fork says them`() {
        assertEquals("Watching", statusLabel(MalListStatus.WATCHING))
        assertEquals("Completed", statusLabel(MalListStatus.COMPLETED))
        assertEquals("On hold", statusLabel(MalListStatus.ON_HOLD))
        assertEquals("Dropped", statusLabel(MalListStatus.DROPPED))
        assertEquals("Plan to watch", statusLabel(MalListStatus.PLAN_TO_WATCH))
    }

    // --------------------------------------------------------------- fixtures

    private fun attempt(
        malId: Long?,
        episode: Int,
        succeeded: Boolean,
        at: Long = 1_700_000_000_000L,
        detail: String? = null,
        title: String = "Title $malId",
    ) = MalWriteAttempt(
        title = title,
        malId = malId,
        episode = episode,
        attemptedAtEpochMs = at,
        succeeded = succeeded,
        detail = detail,
    )

    private fun entry(
        malId: Long,
        watched: Int,
        total: Int,
        updatedAt: Long,
        title: String = "Title $malId",
    ) = MalListEntry(
        malId = malId,
        title = title,
        episodesTotal = total,
        watchedEpisodes = watched,
        status = MalListStatus.WATCHING,
        updatedAtEpochMs = updatedAt,
    )

    private class FakeJournalStore : MalJournalPersistence {
        val values = mutableMapOf<Int, String>()
        override fun read(profileId: Int): String? = values[profileId]
        override fun write(profileId: Int, value: String?) {
            if (value == null) values.remove(profileId) else values[profileId] = value
        }
        override fun clear() = values.clear()
    }

    private companion object {
        const val PROFILE = 9401
    }
}
