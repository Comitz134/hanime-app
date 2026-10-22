package com.nuvio.app.features.manga

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The list the Downloads screen hands the reader, and the sizes it prints.
 *
 * The reader reads *up* a chapter list — the row above the current chapter is
 * the next one — so the saved chapters have to arrive newest first, and "newest"
 * is a number, not a string: a plain sort puts chapter 10 behind chapter 9 and
 * makes the reader's next chapter one it has already read. A chapter that is
 * still arriving is not a chapter the reader can open, so it must not appear in
 * that list at all.
 */
class MangaDownloadsTest {

    private fun saved(
        number: String,
        id: String = "c$number",
        state: String = MangaSavedIndex.DOWNLOADED,
    ) = MangaSavedChapter(hid = "h1", chapter = id, number = number, state = state)

    @Test
    fun `saved chapters arrive newest first`() {
        val list = savedAsChapters(listOf(saved("9"), saved("10"), saved("2")))
        assertEquals(listOf("10", "9", "2"), list.map { it.number })
    }

    @Test
    fun `a chapter still arriving is not a chapter to open`() {
        val list = savedAsChapters(
            listOf(
                saved("1"),
                saved("2", state = MangaSavedIndex.QUEUED),
                saved("3", state = MangaSavedIndex.FAILED),
            ),
        )
        assertEquals(listOf("1"), list.map { it.number })
    }

    @Test
    fun `a chapter without an id is dropped`() {
        val list = savedAsChapters(listOf(saved("1", id = ""), saved("2", id = "c2")))
        assertEquals(listOf("c2"), list.map { it.id })
    }

    @Test
    fun `a decimal chapter sorts between its neighbours`() {
        val list = savedAsChapters(listOf(saved("12.5"), saved("13"), saved("12")))
        assertEquals(listOf("13", "12.5", "12"), list.map { it.number })
    }

    @Test
    fun `an extra with no number sorts below the numbered chapters`() {
        val list = savedAsChapters(listOf(saved("Omake"), saved("1")))
        assertEquals(listOf("1", "Omake"), list.map { it.number })
    }

    @Test
    fun `sizes read as storage sizes`() {
        assertEquals("0 KB", savedSize(0L))
        assertEquals("1 KB", savedSize(500L))
        assertEquals("1 KB", savedSize(1024L))
        assertEquals("6 MB", savedSize(6L * 1024 * 1024))
        assertEquals("1.5 GB", savedSize((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `the index answers one chapter's progress`() {
        val index = MangaSavedIndex(
            chapters = listOf(
                saved("1"),
                MangaSavedChapter(
                    hid = "h1",
                    chapter = "c2",
                    state = MangaSavedIndex.DOWNLOADING,
                    pages = 10,
                    done = 4,
                ),
            ),
        )
        assertEquals(MangaSavedIndex.DOWNLOADED, index.stateOf("c1"))
        assertEquals(MangaSavedIndex.DOWNLOADING, index.stateOf("c2"))
        assertEquals(MangaSavedIndex.NONE, index.stateOf("c3"))
        assertEquals(1.0, index.progressOf("c1").toDouble(), 0.0001)
        assertEquals(0.4, index.progressOf("c2").toDouble(), 0.0001)
        assertEquals(0.0, index.progressOf("c3").toDouble(), 0.0001)
        assertEquals(1, index.savedOf("h1").size)
    }
}
