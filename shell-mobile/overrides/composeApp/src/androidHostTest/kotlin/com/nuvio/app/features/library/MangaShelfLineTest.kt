package com.nuvio.app.features.library

import com.nuvio.app.features.manga.MangaLibraryItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one line under a shelf poster.
 *
 * It is the only place the Library can say how long a manga is, and the number
 * has to be the app's own count — the deduped one — because the title page shows
 * the same number and two screens disagreeing about "33" is worse than neither
 * saying it.
 *
 * The cases that matter are the two ends of knowledge: a row that knows both its
 * length and its place, and a row that knows neither yet because its chapter list
 * has never been read. A row must not invent a denominator it does not have.
 */
class MangaShelfLineTest {

    private fun item(number: String = "", chapters: Int = 0) = MangaLibraryItem(
        id = "hid",
        title = "A Title",
        number = number,
        chapters = chapters,
    )

    @Test
    fun `a title being read says where it is and how long it is`() {
        assertEquals("Ch. 12 of 33", mangaShelfLine(item(number = "12", chapters = 33)))
    }

    @Test
    fun `a title nobody has opened says how long it is`() {
        assertEquals("33 chapters", mangaShelfLine(item(chapters = 33)))
    }

    @Test
    fun `a one-chapter title is one chapter, not one chapters`() {
        assertEquals("1 chapter", mangaShelfLine(item(chapters = 1)))
    }

    @Test
    fun `a row that does not know its length says only where it is`() {
        assertEquals("Ch. 12", mangaShelfLine(item(number = "12")))
        assertEquals("Ch. 12.5", mangaShelfLine(item(number = " 12.5 ", chapters = 0)))
    }

    @Test
    fun `a row that knows nothing says not started`() {
        assertEquals("Not started", mangaShelfLine(item()))
    }
}
