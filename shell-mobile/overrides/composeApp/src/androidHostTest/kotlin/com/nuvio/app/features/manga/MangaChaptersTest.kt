package com.nuvio.app.features.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The duplicate rule, held on a JVM.
 *
 * mangafire lists the same chapter twice when an upload is repeated or a second
 * scanlation carries the same number, and every surface took that at face value:
 * the title said 65 chapters where the series has 33, *Chapter 32* appeared twice
 * in a row, the progress bar divided by 65, and the reader's strip carried on
 * into the chapter that had just been read.
 *
 * The cases below are the ones a phone showed: an ordinary duplicate, the small
 * title that has *one* chapter listed twice (which is the case that breaks a
 * dedupe written against "keep the newest of many"), a row with no number at
 * all, and a duplicate that knows the chapter's name where the kept row does
 * not. The last test is the progress fraction, because the count is the visible
 * half of this bug.
 */
class MangaChaptersTest {

    private fun chapter(number: String, id: String = "c$number", name: String = "") =
        MangaChapter(id = id, number = number, name = name)

    @Test
    fun `a repeated chapter number is one row, and the first row wins`() {
        val chapters = listOf(
            chapter("33", id = "a33"),
            chapter("32", id = "b32"),
            chapter("32", id = "c32"),
            chapter("31", id = "d31"),
        )

        val unique = uniqueChapters(chapters)

        assertEquals(listOf("33", "32", "31"), unique.map { it.number })
        assertEquals(listOf("a33", "b32", "d31"), unique.map { it.id })
    }

    @Test
    fun `a title with one chapter listed twice is a title with one chapter`() {
        val chapters = listOf(chapter("1", id = "first"), chapter("1", id = "again"))

        val unique = uniqueChapters(chapters)

        assertEquals(1, unique.size)
        assertEquals("first", unique.single().id)
    }

    @Test
    fun `a list with nothing repeated is returned untouched`() {
        val chapters = listOf(chapter("3"), chapter("2"), chapter("1"))

        assertSame(chapters, uniqueChapters(chapters))
    }

    @Test
    fun `chapters with no number are kept, duplicates or not`() {
        // Nothing to compare them by, and they are usually extras — hiding one
        // would be hiding a chapter the reader can see on the site.
        val chapters = listOf(chapter(""), chapter(""), chapter("1"))

        val unique = uniqueChapters(chapters)

        assertEquals(3, unique.size)
        assertEquals(listOf("", "", "1"), unique.map { it.number })
    }

    @Test
    fun `a name is borrowed from the duplicate the kept row replaced`() {
        val chapters = listOf(
            chapter("12", id = "plain", name = ""),
            chapter("12", id = "named", name = "The Duel"),
        )

        val unique = uniqueChapters(chapters)

        assertEquals(1, unique.size)
        assertEquals("plain", unique.single().id)
        assertEquals("The Duel", unique.single().name)
    }

    @Test
    fun `the progress fraction counts chapters, not rows`() {
        // 33 chapters listed with every number twice is a full shelf of 33, and a
        // position on chapter 1 is one of them read — not one of 66.
        val chapters = (33 downTo 1).flatMap { n ->
            listOf(chapter(n.toString(), id = "n$n"), chapter(n.toString(), id = "d$n"))
        }

        val unique = uniqueChapters(chapters)

        assertEquals(33, unique.size)
        assertEquals(1, readChapterCount(unique, "n1"))
        assertEquals(33, readChapterCount(unique, "n33"))
    }
}
