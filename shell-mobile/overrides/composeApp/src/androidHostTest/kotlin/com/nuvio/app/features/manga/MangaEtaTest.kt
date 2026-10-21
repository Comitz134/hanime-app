package com.nuvio.app.features.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The next-chapter tile's arithmetic, held on a JVM.
 *
 * The tile says a date, and a date is the one thing on that screen a reader will
 * act on — an estimate read off a broken average ("next chapter in 3 hours", for
 * a series that posts weekly) is worse than the N/A it replaced. So the three
 * refusals are tested as hard as the estimate: a finished title, a title with no
 * rhythm, and a "cadence" that is really a re-upload.
 *
 * The margins matter too. Two hours is the floor because a chapter posted twice
 * an hour apart is one chapter; a year is the ceiling because a scanlation that
 * paused is not a schedule. Both are asserted at the edge, not in the middle.
 */
class MangaEtaTest {

    private val day = 86_400L
    private val week = 7 * day

    private fun chapter(number: Int, created: Long) =
        MangaChapter(id = "c$number", number = number.toString(), created = created)

    /** [count] chapters, newest first, one [gap] apart, ending [ago] before now. */
    private fun rhythm(count: Int, gap: Long, now: Long, ago: Long = day): List<MangaChapter> {
        val newest = now / 1000L - ago
        return (0 until count).map { i -> chapter(i, newest - i * gap) }
    }

    @Test
    fun `a weekly title is estimated a week after its newest chapter`() {
        val now = 1_800_000_000_000L
        val eta = MangaEta.estimate(rhythm(6, week, now), "releasing", now)

        assertEquals((now / 1000L - day + week) * 1000L, eta?.at)
        assertEquals(week * 1000L, eta?.cadence)
        assertTrue("the estimate is in the future", eta!!.late < 0L)
    }

    @Test
    fun `a title that has stopped is never projected`() {
        val now = 1_800_000_000_000L
        val chapters = rhythm(6, week, now)

        assertNull(MangaEta.estimate(chapters, "completed", now))
        assertNull(MangaEta.estimate(chapters, "on_hiatus", now))
        assertNull(MangaEta.estimate(chapters, "dropped", now))
        // ...and the same list is estimated when the status says it is running.
        assertTrue(MangaEta.estimate(chapters, "releasing", now) != null)
    }

    @Test
    fun `two chapters are a fact, not a rhythm`() {
        val now = 1_800_000_000_000L
        assertNull(MangaEta.estimate(rhythm(2, week, now), "releasing", now))
        assertNull(MangaEta.estimate(rhythm(3, week, now), "releasing", now))
        assertTrue(MangaEta.estimate(rhythm(4, week, now), "releasing", now) != null)
    }

    @Test
    fun `a cadence that is really a re-upload is refused`() {
        val now = 1_800_000_000_000L
        // Every hour: two uploads of one chapter, not a schedule.
        assertNull(MangaEta.estimate(rhythm(6, 3600L, now), "releasing", now))
        // Just under the floor.
        assertNull(MangaEta.estimate(rhythm(6, 2 * 3600L - 1, now), "releasing", now))
        // At the floor it is a schedule — a fast one, but one.
        assertTrue(MangaEta.estimate(rhythm(6, 2 * 3600L, now), "releasing", now) != null)
    }

    @Test
    fun `undated chapters cannot carry an estimate`() {
        val now = 1_800_000_000_000L
        val undated = (0 until 8).map { i -> chapter(i, 0L) }
        assertNull(MangaEta.estimate(undated, "releasing", now))
    }

    @Test
    fun `the chip is the four characters a tile can hold`() {
        val now = 1_800_000_000_000L
        val eta = MangaEta.estimate(rhythm(6, week, now), "releasing", now)!!

        assertEquals("N/A", MangaEta.chip(null, now))
        assertEquals("≈3d", MangaEta.chip(eta, eta.at - 3 * day * 1000L))
        assertEquals("≈1h", MangaEta.chip(eta, eta.at - 30 * 60_000L))
        assertEquals("≈5h", MangaEta.chip(eta, eta.at - 5 * 3_600_000L))
        assertEquals("≈2mo", MangaEta.chip(eta, eta.at - 60 * day * 1000L))
        // Past the estimate it is no longer a date, it is "soon".
        assertEquals("Any day", MangaEta.chip(eta, eta.at + 1_000L))
    }

    @Test
    fun `the civil date is the utc day of the instant`() {
        assertEquals("2023-11-14", civilDate(1_700_000_000_000L))
        assertEquals("2024-02-29", civilDate(1_709_164_800_000L))
        assertEquals("1970-01-01", civilDate(1L))
        assertEquals("", civilDate(0L))
    }
}
