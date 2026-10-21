package com.nuvio.app.features.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which chapters a title page calls read.
 *
 * The old rule was a boundary: the chapter left open, and everything older than
 * it. That is right for someone reading forward and wrong for someone who
 * jumps, which is the case this record exists for — open chapter 40 of a
 * hundred and the boundary calls sixty chapters read, thirty-nine of which were
 * never opened. So the case that matters most here is the one where a record
 * exists and the position disagrees with it: the record wins, and it wins with
 * exactly the chapters in it.
 *
 * The fallback is held just as tightly, because it is what an existing library
 * runs on: a title with no record at all (read on an older build, or created by
 * an import) must keep the count it had yesterday, not drop to nothing.
 */
class MangaReadRecordTest {

    private fun chapters(count: Int) = (count downTo 1).map { n ->
        MangaChapter(id = "c$n", number = n.toString())
    }

    @Test
    fun `a jump counts the chapters opened and not the ones skipped`() {
        // Position on chapter 12, but the reader opened 40 and 91 as well. The
        // boundary would say 89 read (12 and everything below it); the record
        // says three, which is what actually happened.
        val read = readChapterNumbers(
            chapters = chapters(100),
            recorded = setOf("12", "40", "91"),
            accountRead = 0,
            resumeChapter = "c12",
        )
        assertEquals(setOf("12", "40", "91"), read)
    }

    @Test
    fun `a record exists even for one chapter and the boundary is not added to it`() {
        val read = readChapterNumbers(
            chapters = chapters(100),
            recorded = setOf("91"),
            accountRead = 0,
            resumeChapter = "c12",
        )
        assertEquals(1, read.size)
        assertTrue("91" in read)
        // The chapter below the record's own must not be coloured on its way
        // past: that is exactly the boundary this replaces.
        assertFalse("90" in read)
    }

    @Test
    fun `no record at all keeps the boundary the old build drew`() {
        val read = readChapterNumbers(
            chapters = chapters(33),
            recorded = emptySet(),
            accountRead = 0,
            resumeChapter = "c20",
        )
        assertEquals(20, read.size)
        assertTrue("20" in read)
        assertTrue("1" in read)
        assertFalse("21" in read)
    }

    @Test
    fun `a title nobody has opened has nothing read`() {
        assertEquals(
            emptySet<String>(),
            readChapterNumbers(chapters(33), emptySet(), 0, null),
        )
        assertEquals(
            emptySet<String>(),
            readChapterNumbers(chapters(33), emptySet(), 0, "c-from-an-old-import"),
        )
        assertEquals(emptySet<String>(), readChapterNumbers(emptyList(), emptySet(), 0, "c1"))
    }

    @Test
    fun `an account that claims no more than the record does not add to it`() {
        // The reader marked chapter 7; the account says "one chapter read"
        // because that is what it was told. Those are the same chapter, and a
        // union would call it two — chapter 1 and chapter 7 — which is the
        // double count this comparison exists to prevent.
        val read = readChapterNumbers(
            chapters = chapters(17),
            recorded = setOf("7"),
            accountRead = 1,
            resumeChapter = "c7",
        )
        assertEquals(setOf("7"), read)
    }

    @Test
    fun `a record is not overruled by an account claiming more`() {
        // The account says "chapter 40", which is a position, not forty chapters
        // opened. The record says the reader opened 91, and one chapter was
        // read. Letting the account draw chapters 1 to 40 would be the boundary
        // this record exists to stop.
        val read = readChapterNumbers(
            chapters = chapters(100),
            recorded = setOf("91"),
            accountRead = 40,
            resumeChapter = "c91",
        )
        assertEquals(setOf("91"), read)
    }

    @Test
    fun `with no record the account can still claim more than the position`() {
        // Nothing recorded (an import, or a title read on an older build), the
        // position on chapter 12, and an account at chapter 40: the account is
        // the only thing that has seen the other twenty-eight chapters.
        val read = readChapterNumbers(
            chapters = chapters(100),
            recorded = emptySet(),
            accountRead = 40,
            resumeChapter = "c12",
        )
        assertEquals(40, read.size)
        assertTrue("1" in read)
        assertTrue("40" in read)
        assertFalse("41" in read)
    }

    @Test
    fun `an account that claims more than the catalogue has is capped`() {
        val read = readChapterNumbers(
            chapters = chapters(33),
            recorded = emptySet(),
            accountRead = 500,
            resumeChapter = null,
        )
        assertEquals(33, read.size)
    }

    @Test
    fun `a mark for a chapter the catalogue no longer lists matches nothing`() {
        val read = readChapterNumbers(
            chapters = chapters(33),
            recorded = setOf("500", "3"),
            accountRead = 0,
            resumeChapter = null,
        )
        assertEquals(setOf("3"), read)
    }

    @Test
    fun `two uploads under one number are one chapter read`() {
        // The duplicate rule keeps one row per number, but a list that slipped
        // through still must not count the same number twice.
        val list = listOf(
            MangaChapter(id = "a", number = "12"),
            MangaChapter(id = "b", number = "12"),
            MangaChapter(id = "c", number = "11"),
        )
        val read = readChapterNumbers(list, setOf("12", "11"), 0, null)
        assertEquals(setOf("12", "11"), read)
    }

    @Test
    fun `a chapter with no number is never counted`() {
        val list = listOf(
            MangaChapter(id = "a", number = "2"),
            MangaChapter(id = "extra", number = ""),
        )
        // Not even when something offers the empty mark, which is what a
        // nameless extra would write if the record stored raw strings.
        val read = readChapterNumbers(list, setOf("2", ""), 0, null)
        assertEquals(setOf("2"), read)
    }
}
