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

@Serializable
private data class CardsResponse(val items: List<MangaCard> = emptyList())

@Serializable
private data class TitleResponse(val title: MangaCard = MangaCard())

@Serializable
private data class ChaptersResponse(val chapters: List<MangaChapter> = emptyList())

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

    suspend fun chapters(id: String): List<MangaChapter> {
        val json = body("/manga/chapters.json") {
            parameter("hid", id)
        }
        val list = uniqueChapters(mangaJson.decodeFromString<ChaptersResponse>(json).chapters)
        // Whoever read this list now knows how long the title is, and the shelf
        // does not: it draws a hundred rows and cannot fetch a chapter list for
        // each. Telling the row costs one loopback request and no upstream call,
        // and failing to tell it must never fail the read — a chapter list that
        // arrived is a chapter list, count or no count.
        try {
            recordChapterCount(id, list.size)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The shelf will catch up the next time this title is opened.
        }
        return list
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
