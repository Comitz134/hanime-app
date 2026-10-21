package com.nuvio.app.features.manga

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the read boundary falls in a chapter list.
 *
 * The list is what a reader looks at to know how far along they are, so the
 * arithmetic behind it has to be right in both directions: colouring the wrong
 * half of a 33-chapter list says "you are at the start" to someone on chapter
 * 30, and it says it in the same ink as the truth. The catalogue sends newest
 * first and a reader reads forward from the old end, so the chapter that was
 * left open is the boundary — this holds that, and holds the two cases where
 * there is no boundary to draw: nothing read yet, and a position naming a
 * chapter the list no longer has.
 */
class MangaReadTest {

    private fun chapters(count: Int) = (count downTo 1).map { n ->
        MangaChapter(id = "c$n", number = n.toString())
    }

    @Test
    fun `a position on the newest chapter means the whole list is read`() {
        assertEquals(33, readChapterCount(chapters(33), "c33"))
    }

    @Test
    fun `a position in the middle reads that chapter and everything below it`() {
        // Newest first — c33, c32, … c1 — so c20 sits thirteen rows down and the
        // twenty chapters beneath it, itself included, are read.
        assertEquals(20, readChapterCount(chapters(33), "c20"))
    }

    @Test
    fun `the first chapter read is one chapter read`() {
        assertEquals(1, readChapterCount(chapters(33), "c1"))
    }

    @Test
    fun `a title nobody has opened has read nothing`() {
        assertEquals(0, readChapterCount(chapters(33), null))
        assertEquals(0, readChapterCount(chapters(33), ""))
    }

    @Test
    fun `a position the list does not carry is no read at all`() {
        // The catalogue re-issued the chapter ids; the stale position must not
        // colour a list it cannot be placed in.
        assertEquals(0, readChapterCount(chapters(33), "c-from-an-old-import"))
        assertEquals(0, readChapterCount(emptyList(), "c1"))
    }
}
