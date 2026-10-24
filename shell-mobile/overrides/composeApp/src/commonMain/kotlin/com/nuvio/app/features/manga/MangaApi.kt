package com.nuvio.app.features.manga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The manga tab's data, spoken to the app's own server.
 *
 * Nothing here reaches mangafire.to. Their API signs every listing call and
 * their image CDN refuses a request without their referer, and neither is
 * something a screen should know about — so the server in this APK
 * (`SekaiServer`, `/manga/…`) fetches, signs and shapes, and this is the thin
 * client on the other side of loopback.
 *
 * The rows are flattened on purpose: a card needs a title, a poster and an id,
 * and a reader needs a page list, so the shapes here are the ones the screens
 * draw rather than copies of an upstream document.
 */

/** Where the in-process server listens — the same port the addon row names. */
internal const val MANGA_SERVER = "http://127.0.0.1:8787"

private val mangaJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
internal data class MangaCard(
    val id: String = "",
    val title: String = "",
    val poster: String = "",
    val type: String = "",
    val year: String = "",
    val status: String = "",
    val description: String = "",
    val rating: String = "",
    val latestChapter: String = "",
    val genres: List<String> = emptyList(),
    /**
     * The catalogue this row came from, when a search asked more than one.
     * Empty for a row that was stored before sources existed; the screen only
     * draws it when it has something to say.
     */
    val source: String = "",
    val sourceName: String = "",
    /** The catalogue's language code (`en`, `all`, `pt-BR`), for its heading. */
    val sourceLang: String = "",
    /**
     * The catalogue's own MyAnimeList id for this title, when it carries one.
     *
     * mangafire knows which MAL entry a series is, so this is an answer rather
     * than a guess — the one thing a title search can lose. It is empty for a
     * title the catalogue has no id for, which is when a search (or the
     * reader's own pick) is what finds the entry.
     */
    val malId: String = "",
)

@Serializable
internal data class MangaChapter(
    val id: String = "",
    val number: String = "",
    val name: String = "",
    /** The date the chapter was posted, as the catalogue spells it for display. */
    val at: String = "",
    /** The same moment in epoch seconds; 0 when upstream did not say. */
    val created: Long = 0L,
)

/**
 * How many of [chapters] the reader has read, from the one position the device
 * keeps and the order the catalogue sends them in.
 *
 * The catalogue lists newest first, and a reader reads forward from the old
 * end, so the chapter left open is the boundary: it and every chapter older
 * than it are read, everything above it is not. That is an inference, not a
 * record — mangafire has no per-chapter read flag and this app stores one
 * position per title — and it is the inference the shelf has always made
 * (*Continue Ch. 167* means 167 chapters). A position on a chapter the list no
 * longer has counts as nothing rather than as everything, which is the safe way
 * round for a boundary that colours a list.
 */
internal fun readChapterCount(chapters: List<MangaChapter>, resumeChapter: String?): Int {
    if (resumeChapter.isNullOrBlank() || chapters.isEmpty()) return 0
    val index = chapters.indexOfFirst { it.id == resumeChapter }
    if (index < 0) return 0
    return chapters.size - index
}

/**
 * The chapters a title page should call read, by chapter number.
 *
 * Two things claim to know this, and neither is the whole answer on its own:
 *
 * - **This device's record** ([recorded]) holds the numbers the reader actually
 *   put on screen. It is exact, and it is the reason a reader who jumped to
 *   chapter 40 is not told they have read sixty chapters. It is empty for a
 *   title read before the record existed, and for a title an import created.
 * - **The account's number** ([accountRead]) is a *position*, not a set: a
 *   tracker says "chapter forty" and means "read up to forty", because that is
 *   what its one number has always meant. It is not evidence about which
 *   chapters were opened, and it must not be merged with the record — a reader
 *   who opened chapter 7 and whose account says 7 has read one chapter, and a
 *   union would claim seven.
 *
 * So the record, once it exists, is the answer, and the account is not
 * consulted; the two numbers are answering different questions. The account is
 * the fallback for a title this device has no record of — an import, or a title
 * read on an older build — where its boundary at the old end of the list is the
 * only witness there is.
 *
 * With no record at all, the old boundary rule from the saved position (see
 * [readChapterCount]) is used exactly as before, and the account's boundary
 * replaces it only when the account claims more, so a title read on another
 * device still shows that much and a title read on an older build does not
 * suddenly claim nothing.
 *
 * Numbers, not ids: the duplicate rule keeps one row per number, a tracker
 * speaks numbers, and a mark for a chapter the catalogue no longer lists simply
 * matches nothing. A chapter with no number cannot be told apart from any other
 * nameless extra, so it is never counted.
 */
internal fun readChapterNumbers(
    chapters: List<MangaChapter>,
    recorded: Set<String>,
    accountRead: Int,
    resumeChapter: String?,
): Set<String> {
    if (chapters.isEmpty()) return emptySet()
    val numbers = chapters.map { it.number.trim() }
    val account = accountRead.coerceIn(0, chapters.size)

    // What this device recorded, as rows of this list.
    val marks = LinkedHashSet<String>()
    if (recorded.isNotEmpty()) {
        for (number in numbers) {
            // A blank is not a chapter: every nameless extra would answer to
            // the same empty mark and claim a row nobody opened.
            if (number.isNotEmpty() && number in recorded) marks += number
        }
        return marks
    }

    // No record: the position's boundary stands in for it.
    val at = chapters.indexOfFirst { it.id == resumeChapter }
    if (at >= 0) {
        for (index in at until chapters.size) {
            if (numbers[index].isNotEmpty()) marks += numbers[index]
        }
    }

    if (account <= marks.size) return marks

    // The account claims more than this device has marked. Its oldest-N
    // boundary is what those chapters can be, and the bar and the ink agree on
    // it rather than one of them claiming a chapter the other does not.
    val boundary = LinkedHashSet<String>(account)
    for (index in chapters.size - account until chapters.size) {
        if (numbers[index].isNotEmpty()) boundary += numbers[index]
    }
    return boundary
}

/**
 * One row per chapter number, in the order the catalogue sent them.
 *
 * A mangafire title carries the same chapter twice more often than it should —
 * the same upload listed again, or a second scanlation under the same number —
 * and the list, the count and the reader all took that at face value: 65 rows
 * for 33 chapters, *Chapter 32* twice in a row, and a strip that carried on into
 * a chapter the reader had just read. The duplicate is dropped here rather than
 * in the screen so that everything that reads a chapter list reads the same
 * one: the title page, the progress fraction, the two-way sync that maps a
 * tracker's chapter number onto a chapter, and the reader's own seam.
 *
 * The first row wins, because the catalogue is newest first and the newest
 * upload of a number is the one a reader means. Two exceptions: a chapter with
 * no number at all is kept (there is nothing to compare it by, and it is
 * usually an extra), and if the kept row has no name while a later duplicate
 * does, the name is taken from the duplicate — the row that is shown should be
 * the one that knows the most.
 *
 * This is the whole rule, and it deliberately errs towards showing a chapter:
 * a hidden chapter cannot be read at all, where a duplicate can be scrolled
 * past.
 */
internal fun uniqueChapters(chapters: List<MangaChapter>): List<MangaChapter> {
    if (chapters.size < 2) return chapters
    val kept = ArrayList<MangaChapter>(chapters.size)
    val slots = HashMap<String, Int>()
    for (chapter in chapters) {
        val number = chapter.number.trim()
        if (number.isEmpty()) {
            kept += chapter
            continue
        }
        val slot = slots[number]
        if (slot == null) {
            slots[number] = kept.size
            kept += chapter
        } else if (kept[slot].name.isBlank() && chapter.name.isNotBlank()) {
            kept[slot] = kept[slot].copy(name = chapter.name)
        }
    }
    // The common answer is "there were no duplicates": handing back the list
    // that came in keeps every caller's identity check meaningful and allocates
    // nothing on a title with 400 chapters and no repeats.
    return if (kept.size == chapters.size) chapters else kept
}

@Serializable
internal data class MangaBrowse(
    val items: List<MangaCard> = emptyList(),
    val page: Int = 1,
    val hasNext: Boolean = false,
)

/**
 * One catalogue's slice of a keyword search: what the search screen draws as a
 * heading, a language and a row of posters.
 */
internal data class MangaSearchGroup(
    /** How a section is told apart from its neighbours, and what a tap holds. */
    val key: String,
    val name: String,
    val lang: String,
    val cards: List<MangaCard>,
)

/**
 * A mixed search, split by the catalogue each card came from.
 *
 * The order is the order the sources first appear in the answer, which is the
 * order the server interleaved them - that is, the order the reader switched
 * the sources on. Sorting by name instead would regroup the screen on every
 * search and make a source the reader uses daily sit wherever the alphabet
 * puts it.
 *
 * A section is a *name and a language*, not a source id, because one catalogue
 * can be switched on twice: this phone has both the MangaDex script and the
 * MangaDex extension turned on, and keying by id drew two headings that read
 * "MangaDex / English" one above the other. A reader looking for a title does
 * not care which of the two answered - and the arrow opening both is more
 * useful than two arrows over identical words. Different languages stay apart,
 * since a heading that says "English" over a French result would be a lie.
 *
 * Rows with no source are left out rather than lumped together: the only way
 * to have one is a list stored before sources existed, and a heading saying
 * nothing is worse than the plain grid the caller falls back to when fewer
 * than two groups come back.
 */
internal fun mangaSearchGroups(cards: List<MangaCard>): List<MangaSearchGroup> {
    val groups = LinkedHashMap<String, MutableList<MangaCard>>()
    for (card in cards) {
        if (card.source.isBlank()) continue
        val name = card.sourceName.ifBlank { card.source }
        val key = "${name.lowercase()}\u0000${card.sourceLang.lowercase()}"
        groups.getOrPut(key) { mutableListOf() }.add(card)
    }
    return groups.map { (key, rows) ->
        MangaSearchGroup(
            key = key,
            name = rows.first().sourceName.ifBlank { rows.first().source },
            lang = rows.first().sourceLang,
            cards = rows,
        )
    }
}

/**
 * What a source's language code is called on its search heading.
 *
 * The codes come from two places with different habits: extensions name an ISO
 * 639-1 language (`en`, `pt-BR`), and an extension covering more than one says
 * `all`. A code with no name here is shown upper-cased rather than dropped -
 * `PL` still tells a reader something, and a blank line would make a heading
 * look like it had no language at all.
 *
 * Empty stays empty: a source from before languages were recorded has nothing
 * to say, and the heading omits the line instead of guessing English.
 */
internal fun mangaSourceLanguageName(code: String): String = when (code.trim().lowercase()) {
    "" -> ""
    "all", "multi" -> "All languages"
    "ar" -> "Arabic"
    "bg" -> "Bulgarian"
    "ca" -> "Catalan"
    "cs" -> "Czech"
    "da" -> "Danish"
    "de" -> "German"
    "el" -> "Greek"
    "en" -> "English"
    "es" -> "Spanish"
    "fa" -> "Persian"
    "fi" -> "Finnish"
    "fr" -> "French"
    "he" -> "Hebrew"
    "hi" -> "Hindi"
    "hr" -> "Croatian"
    "hu" -> "Hungarian"
    "id" -> "Indonesian"
    "it" -> "Italian"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "ms" -> "Malay"
    "nl" -> "Dutch"
    "no" -> "Norwegian"
    "pl" -> "Polish"
    "pt" -> "Portuguese"
    "pt-br" -> "Portuguese (Brazil)"
    "ro" -> "Romanian"
    "ru" -> "Russian"
    "sk" -> "Slovak"
    "sr" -> "Serbian"
    "sv" -> "Swedish"
    "th" -> "Thai"
    "tl", "fil" -> "Filipino"
    "tr" -> "Turkish"
    "uk" -> "Ukrainian"
    "vi" -> "Vietnamese"
    "zh" -> "Chinese"
    "zh-hans" -> "Chinese (Simplified)"
    "zh-hant" -> "Chinese (Traditional)"
    else -> code.trim().uppercase()
}

@Serializable
private data class CardsResponse(val items: List<MangaCard> = emptyList())

@Serializable
private data class TitleResponse(val title: MangaCard = MangaCard())

@Serializable
private data class ChaptersResponse(
    val chapters: List<MangaChapter> = emptyList(),
    /**
     * The server could not ask the catalogue and answered with the chapters
     * saved on this phone instead. Absent (false) for a normal answer.
     */
    val offline: Boolean = false,
)

/**
 * A chapter list, and whether it is the phone's own.
 *
 * The two travel together because the screen has to say which one it is
 * showing: an "offline" list is short by nature, and a reader who is not told
 * why would think the series ended.
 */
internal data class MangaChapterList(
    val chapters: List<MangaChapter>,
    val offline: Boolean = false,
)

@Serializable
internal data class MangaPage(val u: String = "")

@Serializable
private data class PagesResponse(val pages: List<MangaPage> = emptyList())

/**
 * Where a title was left. `chapter` is the chapter's own id (what the reader
 * opens) and `number` is what it is called — the two are different ids
 * upstream, and only one of them is worth showing.
 */
/**
 * One title on the reader's shelf.
 *
 * `chapter` and `number` are filled in from the reader's position when there is
 * one, which is what makes a shelf row say "Ch. 12" rather than nothing at all —
 * and what lets a tap land on the chapter that was left. A title added and never
 * opened is a card with no line under it.
 */
@Serializable
internal data class MangaLibraryItem(
    val id: String = "",
    val title: String = "",
    val poster: String = "",
    val chapter: String = "",
    val number: String = "",
    val page: Int = 0,
    /** When the title was put on the shelf; 0 for a row stored without one. */
    val at: Long = 0L,
    /** The account's id for this title, when an import matched it. */
    val mal: Long = 0L,
    /**
     * How many chapters the title has, as the app counted them once the list
     * was deduped; 0 until a chapter list has been read for this title.
     *
     * The shelf carries it so a row can say "Ch. 12 of 33" without fetching a
     * chapter list for every title in the Library. It is written by whichever
     * screen reads chapters (see [MangaApi.chapters]) and lives on the row, so
     * it survives the app being closed.
     */
    val chapters: Int = 0,
    /**
     * When the title was last read — the newest of the reader's own position
     * (written as a page turns) and MyAnimeList's `updated_at` brought over by
     * an import. 0 for a title nobody has read, which is what puts it at the
     * end of a last-read order rather than spreading it through it.
     */
    val read: Long = 0L,
) {
    /** Where to resume, or null when this title has never been opened. */
    val position: MangaPosition?
        get() = if (chapter.isBlank()) null else MangaPosition(
            id = id,
            title = title,
            poster = poster,
            chapter = chapter,
            number = number,
            page = page,
        )
}

@Serializable
internal data class MangaPosition(
    val id: String = "",
    val title: String = "",
    val poster: String = "",
    val chapter: String = "",
    val number: String = "0",
    val page: Int = 0,
    val pages: Int = 0,
    val at: Long = 0L,
)

/** One value of a dropdown: what upstream wants sent, and what to show. */
@Serializable
internal data class MangaFilterOption(
    val value: String = "",
    val label: String = "",
)

/**
 * The catalogue's own filter vocabulary, fetched rather than pasted: the
 * genres are upstream's enums and there are dozens of them, so a list that
 * disagreed with the server would be worse than none.
 *
 * The two spellings are upstream's: types and statuses name their id `value`
 * and their text `label`, genres name them `id` and `name`.
 */
@Serializable
private data class MangaFilterRaw(val value: String = "", val label: String = "")

@Serializable
private data class MangaGenreRaw(val id: Int = 0, val name: String = "")

@Serializable
internal data class MangaFilters(
    val types: List<MangaFilterOption> = emptyList(),
    val statuses: List<MangaFilterOption> = emptyList(),
    val genres: List<MangaFilterOption> = emptyList(),
)

@Serializable
private data class MangaFiltersResponse(
    val types: List<MangaFilterRaw> = emptyList(),
    val statuses: List<MangaFilterRaw> = emptyList(),
    val genres: List<MangaGenreRaw> = emptyList(),
)

@Serializable
private data class PositionsResponse(val items: List<MangaPosition> = emptyList())

@Serializable
private data class LibraryResponse(val items: List<MangaLibraryItem> = emptyList())

/** One title's read marks: the chapter numbers the reader has put on screen. */
@Serializable
private data class ReadResponse(val chapters: List<String> = emptyList())

internal object MangaApi {

    private val http = createApiHttpClient()

    /**
     * Bumped whenever the shelf of read titles changes — one position written,
     * or a list imported. The Library's manga row reads it as the key of its
     * own load, so a chapter finished on the manga tab is already on the
     * Library's shelf when the tab is drawn again, without either screen
     * knowing about the other.
     */
    internal var libraryRevision by mutableStateOf(0)
        private set

    /**
     * Bumped whenever a chapter is written down as read. The title page keys
     * its own load of the marks on it, so coming back from the reader redraws
     * the list with the chapter just read already marked — the same trick the
     * shelf uses with [libraryRevision], for the same reason: neither screen
     * needs to know the other exists.
     */
    internal var readRevision by mutableStateOf(0)
        private set

    /**
     * Chapters whose pages have been read in this process, most recent last.
     * A chapter is returned to often — back from the next one, a re-open after
     * the app was left — and their page lists do not change. Bounded, because
     * a long series read in one sitting would otherwise pin every page URL it
     * has ever seen.
     */
    private val pagesLock = Mutex()
    private val pagesCache = mutableMapOf<String, List<MangaPage>>()
    private const val PAGES_KEPT = 8

    /**
     * One GET of this app's own server, with a refusal turned into an exception
     * rather than an empty list.
     *
     * The `/manga/…` routes answer trouble as JSON (`{"error":"upstream",
     * "message":"…"}`), and a decode with defaults would read that as "no
     * titles" — a search that silently found nothing instead of a search that
     * says why. So a non-2xx answer is read for its message and thrown, and the
     * screen's error line carries upstream's own words.
     */
    private suspend fun body(path: String, block: HttpRequestBuilder.() -> Unit = {}): String {
        val response = http.get("$MANGA_SERVER$path") { block() }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching {
                (mangaJson.parseToJsonElement(text) as? JsonObject)
                    ?.get("message")
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }
            }.getOrNull()
            throw IllegalStateException(
                message?.takeIf { it.isNotBlank() }
                    ?: "The catalogue answered ${response.status.value}.",
            )
        }
        return text
    }

    /** The homepage shelf: what is trending, one page of it. */
    suspend fun trending(limit: Int = 30): List<MangaCard> {
        val json = body("/manga/trending.json") {
            parameter("limit", limit)
        }
        return mangaJson.decodeFromString<CardsResponse>(json).items
    }

    /**
     * Search and browse in one call. An empty [query] is the catalogue ordered
     * by the latest chapter update; a keyword ranks by relevance upstream.
     */
    suspend fun search(
        query: String = "",
        page: Int = 1,
        type: String = "",
        status: String = "",
        genre: String = "",
    ): MangaBrowse {
        val json = body("/manga/search.json") {
            if (query.isNotBlank()) parameter("q", query.trim())
            parameter("page", page)
            parameter("type", type)
            parameter("status", status)
            parameter("genre", genre)
        }
        return mangaJson.decodeFromString<MangaBrowse>(json)
    }

    suspend fun title(id: String): MangaCard {
        val json = body("/manga/title.json") {
            parameter("hid", id)
        }
        return mangaJson.decodeFromString<TitleResponse>(json).title
    }

    suspend fun chapters(id: String): List<MangaChapter> = chapterList(id).chapters

    /**
     * The chapter list, and whether it came from this phone rather than the
     * catalogue — see [MangaChapterList].
     */
    suspend fun chapterList(id: String): MangaChapterList {
        val json = body("/manga/chapters.json") {
            parameter("hid", id)
        }
        val answer = mangaJson.decodeFromString<ChaptersResponse>(json)
        val list = uniqueChapters(answer.chapters)
        // Whoever read this list now knows how long the title is, and the shelf
        // does not: it draws a hundred rows and cannot fetch a chapter list for
        // each. Telling the row costs one loopback request and no upstream call,
        // and failing to tell it must never fail the read — a chapter list that
        // arrived is a chapter list, count or no count.
        //
        // Not for an offline answer: that list is only the chapters saved here,
        // and stamping its length on the shelf row would shrink a 200-chapter
        // title to "3 chapters" for as long as it stayed there.
        if (!answer.offline) {
            try {
                recordChapterCount(id, list.size)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The shelf will catch up the next time this title is opened.
            }
        }
        return MangaChapterList(list, answer.offline)
    }

    /**
     * Remember how long a title is, on its shelf row. A no-op for a title that
     * is not on the shelf, and for a count of nothing.
     */
    suspend fun recordChapterCount(id: String, chapters: Int) {
        if (id.isBlank() || chapters <= 0) return
        body("/manga/size.json") {
            parameter("hid", id)
            parameter("chapters", chapters)
        }
    }

    /** Type, status and genre options — one call, on first landing. */
    suspend fun filters(): MangaFilters {
        val json = body("/manga/filters.json")
        val raw = mangaJson.decodeFromString<MangaFiltersResponse>(json)
        return MangaFilters(
            types = raw.types.map { MangaFilterOption(it.value, it.label) },
            statuses = raw.statuses.map { MangaFilterOption(it.value, it.label) },
            genres = raw.genres.mapNotNull { genre ->
                if (genre.id == 0 || genre.name.isBlank()) null
                else MangaFilterOption(genre.id.toString(), genre.name)
            },
        )
    }

    /**
     * The chapter numbers this title's record holds.
     *
     * An empty set is an answer — "nothing recorded yet" — and the title page
     * falls back to the boundary rule for it; a failed call is the same answer,
     * because a record that cannot be read must not take the count away.
     */
    suspend fun readChapters(id: String): Set<String> {
        if (id.isBlank()) return emptySet()
        val json = body("/manga/read.json") {
            parameter("hid", id)
        }
        return mangaJson.decodeFromString<ReadResponse>(json).chapters
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Write down that one chapter has been read, as the reader opens it.
     *
     * Best effort by design: a mark that does not land costs a count on one
     * screen, and the reader is in the middle of a page. The next chapter marks
     * itself, and the count catches up.
     */
    suspend fun markRead(id: String, number: String) {
        if (id.isBlank() || number.trim().isEmpty()) return
        body("/manga/read.json") {
            parameter("hid", id)
            parameter("chapter", number.trim())
        }
        readRevision++
    }

    /**
     * The one-time migration from the boundary rule.
     *
     * A title read before this record existed has a position and no marks. The
     * chapters the old rule claimed are offered here once; the server refuses
     * the write when a record already exists, so a visit to a title can never
     * overwrite the reader's own history with a guess made from the position.
     */
    suspend fun seedReadMarks(id: String, numbers: List<String>) {
        val clean = numbers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (id.isBlank() || clean.isEmpty()) return
        body("/manga/read.json") {
            parameter("hid", id)
            parameter("chapters", clean.joinToString(","))
            parameter("seed", 1)
        }
        readRevision++
    }

    /** One chapter's pages, already pointing at the app's own image route. */
    suspend fun pages(chapterId: String): List<MangaPage> {
        pagesCache[chapterId]?.let { return it }
        return pagesLock.withLock {
            pagesCache[chapterId]?.let { return@withLock it }
            val json = body("/manga/pages.json") {
                parameter("chapter", chapterId)
            }
            val pages = mangaJson.decodeFromString<PagesResponse>(json).pages
            if (pagesCache.size >= PAGES_KEPT) {
                pagesCache.remove(pagesCache.keys.first())
            }
            pagesCache[chapterId] = pages
            pages
        }
    }

    /**
     * Warm the cache for a chapter that is about to be read. Best effort: a
     * failed prefetch is simply a chapter that loads the usual way.
     */
    suspend fun prefetch(chapterId: String) {
        if (chapterId.isBlank() || pagesCache.containsKey(chapterId)) return
        runCatching { pages(chapterId) }
    }

    // ---------------------------------------------------------------- offline

    /**
     * Everything kept on this phone, with what is still arriving.
     *
     * Answered from the app's own files — no connection is used — so this is
     * also what the Downloads screen draws when there is no network at all.
     */
    suspend fun savedChapters(): MangaSavedIndex {
        val json = body("/manga/downloads.json")
        val index = mangaJson.decodeFromString<MangaSavedIndex>(json)
        // A chapter saved while its page list is still in memory has a cached
        // answer that names the proxy, and the proxy needs a connection. Dropping
        // it here is what makes the chapter that was being read when it finished
        // downloading open from the phone on the next visit rather than the
        // process after next.
        pagesLock.withLock {
            for (row in index.chapters) {
                if (row.state == MangaSavedIndex.DOWNLOADED) pagesCache.remove(row.chapter)
            }
        }
        return index
    }

    /**
     * Ask the phone to keep one chapter. Answers at once with the state it is
     * in (`queued`, `downloading` or `downloaded`); the pages are fetched by the
     * server's own queue in the background.
     */
    suspend fun saveChapter(
        hid: String,
        title: String,
        poster: String,
        chapter: MangaChapter,
    ): String {
        if (hid.isBlank() || chapter.id.isBlank()) return MangaSavedIndex.NONE
        val json = body("/manga/download.json") {
            parameter("hid", hid)
            parameter("chapter", chapter.id)
            parameter("number", chapter.number)
            parameter("name", chapter.name)
            parameter("at", chapter.at)
            parameter("title", title)
            parameter("poster", poster)
        }
        return runCatching {
            (mangaJson.parseToJsonElement(json) as? JsonObject)
                ?.get("state")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: MangaSavedIndex.QUEUED
    }

    /** Forget one saved chapter, and its pages with it. */
    suspend fun removeDownload(hid: String, chapterId: String) {
        if (hid.isBlank() || chapterId.isBlank()) return
        body("/manga/download.json") {
            parameter("hid", hid)
            parameter("chapter", chapterId)
            parameter("remove", 1)
        }
    }

    /** Forget every saved chapter of one title. */
    suspend fun removeDownloads(hid: String) {
        if (hid.isBlank()) return
        body("/manga/download.json") {
            parameter("hid", hid)
            parameter("remove", 1)
        }
    }

    /** Every title that has been read, newest first. */
    suspend fun positions(): List<MangaPosition> {
        val json = body("/manga/pos.json")
        return mangaJson.decodeFromString<PositionsResponse>(json).items
    }

    /**
     * The reader's shelf: the titles they chose, newest added first, each with
     * its position where there is one. Kept by the app rather than by the
     * catalogue — mangafire has no notion of a library, and MyAnimeList's own
     * list is only imported *into* this one.
     */
    suspend fun library(): List<MangaLibraryItem> {
        val json = body("/manga/library.json")
        return mangaJson.decodeFromString<LibraryResponse>(json).items
    }

    /**
     * Put one title on the shelf. Adding a title already there is a no-op.
     *
     * `at` is when the title counts as added — normally now, which is what the
     * route stamps when no time is given. An import passes its own, because a
     * list brought over from MyAnimeList arrives with an order (the order it was
     * last read in) that "every row at the same millisecond" would throw away.
     * A title already on the shelf keeps the moment it was first added.
     */
    suspend fun addToLibrary(
        card: MangaCard,
        at: Long? = null,
        read: Long? = null,
        mal: Long? = null,
    ) {
        if (card.id.isBlank()) return
        body("/manga/library.json") {
            parameter("hid", card.id)
            parameter("title", card.title)
            parameter("poster", card.poster)
            if (at != null && at > 0L) parameter("at", at)
            if (read != null && read > 0L) parameter("read", read)
            // An import knows the account's id for this title; a title added by
            // hand does not. Kept so that asking "how far am I" later does not
            // depend on matching a name again — which is what left a shelf full
            // of "Not started" rows the import had already matched once.
            if (mal != null && mal > 0L) parameter("mal", mal)
        }
        libraryRevision++
    }

    /** Take one title off the shelf. Its reading position, if any, stays. */
    suspend fun removeFromLibrary(id: String) {
        if (id.isBlank()) return
        body("/manga/library.json") {
            parameter("hid", id)
            parameter("remove", 1)
        }
        libraryRevision++
    }

    /**
     * Record the reader's place. A write, done as a GET with its parameters —
     * the server reads a request's head and no body, and a page number is not
     * worth making it read one.
     */
    suspend fun savePosition(position: MangaPosition, readAt: Long? = null) {
        body("/manga/pos.json") {
            parameter("hid", position.id)
            parameter("chapter", position.chapter)
            parameter("title", position.title)
            parameter("poster", position.poster)
            parameter("number", position.number)
            parameter("page", position.page)
            parameter("pages", position.pages)
            // Only an import passes one: a page turn is "now", and the server
            // stamps that itself. A sync from a tracker passes 0 — it knows the
            // chapter, not when it was read, so the position moves and the
            // shelf's last-read stamp is left alone.
            when {
                readAt == null -> Unit
                readAt > 0L -> parameter("at", readAt)
                else -> parameter("quiet", 1)
            }
        }
        libraryRevision++
    }
}
