package com.nuvio.app.features.manga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Manga chapters kept on the phone, for reading without a connection.
 *
 * The store and the fetching live in the app's own server (`MangaOffline`):
 * their image CDN refuses a request without their referer, which an image
 * loader cannot be told to send, so the bytes have to be collected on the Java
 * side and written to the phone's own files. This is the thin client on the
 * other side of that, plus the one piece of state a screen cannot derive: how
 * far a chapter that is still arriving has got.
 *
 * A download is started, not awaited: the server has a single queue and walks
 * it in order, so leaving the title — or the app's manga tab — does not stop
 * it, and the next chapter starts by itself. [watch] follows the queue until
 * nothing is left, which is why the button that says "12%" eventually says
 * "saved" without the screen having to ask again.
 */

/** One chapter, downloaded or on its way. */
@Serializable
internal data class MangaSavedChapter(
    val hid: String = "",
    val chapter: String = "",
    val number: String = "",
    val name: String = "",
    val at: String = "",
    val title: String = "",
    val poster: String = "",
    /** `downloaded`, `queued`, `downloading` or `failed`. */
    val state: String = "downloaded",
    val pages: Int = 0,
    val done: Int = 0,
    val bytes: Long = 0L,
    /** Why a failed chapter failed, when the server said. */
    val message: String = "",
)

/** One title with chapters on this phone. */
@Serializable
internal data class MangaSavedTitle(
    val id: String = "",
    val title: String = "",
    val poster: String = "",
    val chapters: Int = 0,
    val bytes: Long = 0L,
    val at: Long = 0L,
)

/**
 * The whole store, as the Downloads screen and the chapter rows draw it.
 *
 * [active] is how many chapters are queued or downloading, and is the only
 * reason a screen polls: everything else changes only when the reader does.
 */
@Serializable
internal data class MangaSavedIndex(
    val chapters: List<MangaSavedChapter> = emptyList(),
    val titles: List<MangaSavedTitle> = emptyList(),
    val bytes: Long = 0L,
    val active: Int = 0,
) {
    /** The state of one chapter: `none` for one nobody has saved. */
    fun stateOf(chapterId: String): String =
        chapters.firstOrNull { it.chapter == chapterId }?.state ?: NONE

    /** How far one chapter has got, 0f when it has not started. */
    fun progressOf(chapterId: String): Float {
        val row = chapters.firstOrNull { it.chapter == chapterId } ?: return 0f
        if (row.state == MangaSavedIndex.DOWNLOADED) return 1f
        if (row.pages <= 0) return 0f
        return (row.done.toFloat() / row.pages).coerceIn(0f, 1f)
    }

    /** The chapters of one title that are here and whole. */
    fun savedOf(hid: String): List<MangaSavedChapter> =
        chapters.filter { it.hid == hid && it.state == MangaSavedIndex.DOWNLOADED }

    fun titleOf(hid: String): MangaSavedTitle? = titles.firstOrNull { it.id == hid }

    companion object {
        const val NONE = "none"
        const val DOWNLOADED = "downloaded"
        const val QUEUED = "queued"
        const val DOWNLOADING = "downloading"
        const val FAILED = "failed"
    }
}

internal object MangaDownloads {

    var index by mutableStateOf(MangaSavedIndex())
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /**
     * Polling runs outside any screen's composition: a chapter takes minutes to
     * arrive, and a reader who goes back to the shelf — or to another tab —
     * should still find it finished. The server is the one doing the work, so
     * all this scope owns is the loop that asks how it is going.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var poll: Job? = null

    suspend fun refresh() {
        try {
            index = MangaApi.savedChapters()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "The download list did not come back."
        }
    }

    /**
     * Keep these chapters on the phone.
     *
     * One loopback request per chapter, each of which queues it and answers at
     * once — the queue itself is the server's, so this call is a list of
     * intentions rather than the download. Chapters already here are skipped,
     * which is what makes "download all" safe to press twice.
     */
    suspend fun download(
        hid: String,
        title: String,
        poster: String,
        chapters: List<MangaChapter>,
    ) {
        if (hid.isBlank() || chapters.isEmpty()) return
        var failure: String? = null
        for (chapter in chapters) {
            if (chapter.id.isBlank()) continue
            if (index.stateOf(chapter.id) == MangaSavedIndex.DOWNLOADED) continue
            try {
                MangaApi.saveChapter(hid, title, poster, chapter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message ?: "A chapter could not be queued."
            }
        }
        refresh()
        if (failure != null) error = failure else watch()
    }

    suspend fun remove(hid: String, chapterId: String) {
        if (hid.isBlank() || chapterId.isBlank()) return
        try {
            MangaApi.removeDownload(hid, chapterId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "The chapter could not be removed."
        }
        refresh()
    }

    suspend fun removeTitle(hid: String) {
        if (hid.isBlank()) return
        try {
            MangaApi.removeDownloads(hid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "The title could not be removed."
        }
        refresh()
    }

    /** Follow the queue until nothing is on its way. Safe to call repeatedly. */
    fun watch() {
        if (poll?.isActive == true) return
        poll = scope.launch {
            while (isActive) {
                try {
                    index = MangaApi.savedChapters()
                    error = null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The next tick asks again; a failed poll is not a failed
                    // download, and the server keeps working either way.
                }
                if (index.active <= 0) break
                delay(POLL_MS)
            }
        }
    }

    fun stateOf(chapterId: String): String = index.stateOf(chapterId)

    fun progressOf(chapterId: String): Float = index.progressOf(chapterId)

    fun savedChapters(hid: String): List<MangaSavedChapter> = index.savedOf(hid)

    /** The chapters of one title that are here, in the reader's order. */
    fun chaptersOf(hid: String): List<MangaChapter> = savedAsChapters(index.savedOf(hid))

    fun titleOf(hid: String): MangaSavedTitle? = index.titleOf(hid)

    /** How long one poll waits between answers, while anything is arriving. */
    private const val POLL_MS = 1_500L
}

/**
 * Saved chapters as a chapter list the reader can walk.
 *
 * Newest first, because that is the order the catalogue sends and the reader
 * reads *up* it — the chapter after this one is the row above. Sorted by
 * number rather than by when it was downloaded: downloading chapter 3 after
 * chapter 12 must not make the reader treat 3 as the next one.
 */
internal fun savedAsChapters(saved: List<MangaSavedChapter>): List<MangaChapter> =
    saved
        .filter { it.state == MangaSavedIndex.DOWNLOADED && it.chapter.isNotBlank() }
        .sortedWith(
            compareByDescending<MangaSavedChapter> { chapterNumber(it.number) }
                .thenByDescending { it.number },
        )
        .map { MangaChapter(id = it.chapter, number = it.number, name = it.name, at = it.at) }

private fun chapterNumber(number: String): Double =
    number.trim().toDoubleOrNull() ?: -1.0

/** "1 chapter", "3 chapters" — a count that reads as a sentence. */
internal fun savedCount(count: Int, one: String, many: String): String =
    "$count " + if (count == 1) one else many

/**
 * A byte count as a person reads it: KB, MB, or GB with one decimal.
 *
 * Integer arithmetic, because this is common code and `String.format` is not.
 * A chapter is a few megabytes, so the common answer is "6 MB"; the decimals are
 * for a shelf of them.
 */
internal fun savedSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = 1024L
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes < mb -> "${(bytes + kb - 1) / kb} KB"
        bytes < gb -> "${(bytes + mb - 1) / mb} MB"
        else -> {
            val tenths = (bytes * 10 + gb / 2) / gb
            "${tenths / 10}.${tenths % 10} GB"
        }
    }
}
