package com.nuvio.app.features.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the search screen's per-source sections rest on.
 *
 * A mixed search comes back as one interleaved list. Splitting it back into
 * catalogues is only worth doing if the split is stable: the same query must
 * put the same source in the same place, a source must keep its own results in
 * the order upstream ranked them, and the heading must name the catalogue
 * rather than an id. Each of those was decided by eye on a phone once; this
 * holds them where a mistake costs a second instead of a scroll.
 */
class MangaSearchGroupTest {

    private fun card(id: String, source: String, name: String = "", lang: String = "") =
        MangaCard(id = id, title = id, source = source, sourceName = name, sourceLang = lang)

    @Test
    fun `sources appear in the order they first answered, not by name`() {
        val groups = mangaSearchGroups(
            listOf(
                card("1", "mangadex", "MangaDex", "en"),
                card("2", "mangafire", "MangaFire", "en"),
                card("3", "mangadex", "MangaDex", "en"),
                card("4", "comix", "Comix", "en"),
            ),
        )

        assertEquals(listOf("MangaDex", "MangaFire", "Comix"), groups.map { it.name })
        assertEquals(listOf(2, 1, 1), groups.map { it.cards.size })
    }

    @Test
    fun `each source keeps its own results in the order it ranked them`() {
        val groups = mangaSearchGroups(
            listOf(
                card("a1", "a", "A"),
                card("b1", "b", "B"),
                card("a2", "a", "A"),
                card("b2", "b", "B"),
                card("a3", "a", "A"),
            ),
        )

        assertEquals(listOf("a1", "a2", "a3"), groups.first { it.name == "A" }.cards.map { it.id })
        assertEquals(listOf("b1", "b2"), groups.first { it.name == "B" }.cards.map { it.id })
    }

    @Test
    fun `one catalogue switched on twice is one heading`() {
        // The script and the extension both call themselves MangaDex, in
        // English. Two sections reading the same words above the same posters
        // are not two pieces of news.
        val groups = mangaSearchGroups(
            listOf(
                card("s1", "mangadex", "MangaDex", "en"),
                card("e1", "e2499", "MangaDex", "en"),
                card("s2", "mangadex", "MangaDex", "en"),
            ),
        )

        assertEquals(1, groups.size)
        assertEquals(listOf("s1", "e1", "s2"), groups.single().cards.map { it.id })
    }

    @Test
    fun `the same name in two languages stays two sections`() {
        val groups = mangaSearchGroups(
            listOf(
                card("1", "a", "MangaDex", "en"),
                card("2", "b", "MangaDex", "fr"),
            ),
        )

        assertEquals(listOf("en", "fr"), groups.map { it.lang })
    }

    @Test
    fun `a source that never named itself is headed by its id`() {
        val groups = mangaSearchGroups(listOf(card("1", "some-repo.source", name = "")))

        assertEquals("some-repo.source", groups.single().name)
    }

    @Test
    fun `rows with no source are left out rather than pooled under a blank heading`() {
        // A list stored before sources existed, next to a tagged one. The
        // caller falls back to its plain grid when this leaves fewer than two
        // groups, so dropping is what keeps a heading honest.
        val groups = mangaSearchGroups(
            listOf(
                card("old", source = ""),
                card("1", "mangadex", "MangaDex"),
            ),
        )

        assertEquals(listOf("1"), groups.flatMap { group -> group.cards.map { it.id } })
        assertEquals(1, groups.size)
    }

    @Test
    fun `language codes are named the way a heading says them`() {
        assertEquals("English", mangaSourceLanguageName("en"))
        assertEquals("English", mangaSourceLanguageName("EN"))
        assertEquals("Portuguese (Brazil)", mangaSourceLanguageName("pt-BR"))
        assertEquals("All languages", mangaSourceLanguageName("all"))
    }

    @Test
    fun `a language nothing names is shown, not hidden`() {
        assertEquals("XX", mangaSourceLanguageName("xx"))
        assertTrue(mangaSourceLanguageName("").isEmpty())
    }
}
