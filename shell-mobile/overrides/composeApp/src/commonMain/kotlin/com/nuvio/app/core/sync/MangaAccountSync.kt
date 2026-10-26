package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.features.manga.MangaApi
import com.nuvio.app.features.manga.MangaCard
import com.nuvio.app.features.manga.MangaLibraryItem
import com.nuvio.app.features.manga.MangaMal
import com.nuvio.app.features.manga.MangaMalStorage
import com.nuvio.app.features.manga.MangaPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** How long the shelf is still being written before the account is told. */
private const val SHELF_SETTLE_MS = 1_200L

/**
 * The manga side of the account: the reader's shelf and the MyAnimeList
 * session, carried in the same per-profile settings blob as the app's other
 * settings.
 *
 * The shelf lives in this phone's preferences, served over loopback; it is not
 * part of Nuvio's series Library, so the account knew nothing about it and a
 * second device started with an empty Manga tab. The MyAnimeList tokens were
 * just as local, so a reader who connected it once was asked to connect it
 * again on every install.
 *
 * Two rules keep this honest:
 *
 * - **Nothing is exported before the shelf has been read once.** An empty
 *   payload would be a push that erased the account's copy, and at startup the
 *   cache is empty for the few milliseconds between "app opened" and "loopback
 *   answered". [libraryPayload] answers null until then and the blob leaves the
 *   field out.
 * - **Pull unions, push replaces.** A remote title this device does not have is
 *   added; a title this device has keeps its own position unless the account's
 *   row was read more recently. A removal made here reaches the account with the
 *   next push, which is what a reader who tidied their shelf means by it.
 */
internal object MangaAccountSync {

    private val log = Logger.withTag("MangaAccountSync")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val shelfSerializer = ListSerializer(MangaLibraryItem.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var library: String = ""

    @Volatile
    private var mal: String = ""

    private var job: Job? = null

    private val _version = MutableStateFlow(0)

    /**
     * Moves whenever what this object would export has changed, so the settings
     * sync's change watcher sees a shelf edit the way it sees a theme change.
     */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** False until the shelf has been read at least once. */
    val isLoaded: Boolean
        get() = loaded

    @OptIn(FlowPreview::class)
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            // The shelf is written one row per tap - adding a title, turning a
            // page, a bulk import - and each write is one push to the account
            // that nobody needs. Wait for the writing to stop.
            launch {
                MangaApi.libraryChanges
                    .debounce(SHELF_SETTLE_MS)
                    .collectLatest { refresh() }
            }
            refresh()
        }
    }

    /** Read the shelf and the session, and say if either changed. */
    suspend fun refresh() {
        mutex.withLock {
            val nextLibrary = runCatching {
                json.encodeToString(shelfSerializer, MangaApi.library())
            }.getOrNull()
            if (nextLibrary == null) {
                // A shelf that cannot be read is not an empty shelf. The
                // loopback server is not up yet in the first moments of a
                // launch, and marking the cache loaded here would let the next
                // push tell the account that this reader has no manga at all.
                // Leave [loaded] alone: the caller that needs the shelf can try
                // again (see [ensureLoaded]).
                log.w { "the shelf did not answer; leaving the last copy in place" }
                return@withLock
            }
            val nextMal = runCatching { MangaMalStorage.load() }.getOrNull().orEmpty()
            val changed = !loaded || nextLibrary != library || nextMal != mal
            library = nextLibrary
            mal = nextMal
            loaded = true
            if (changed) _version.value += 1
        }
    }

    /** Load the cache if this process has not done it yet. */
    suspend fun ensureLoaded() {
        if (!loaded) refresh()
    }

    /**
     * The shelf for the account blob; null until it has been read once.
     *
     * Null rather than an empty string on purpose: the blob's field is left out
     * of the push, so a device that has not finished starting up cannot tell the
     * account that its shelf is empty.
     */
    fun libraryPayload(): String? = if (loaded) library else null

    /** The MyAnimeList session payload for the account blob, or null. */
    fun malPayload(): String? = if (loaded) mal else null

    /** One shelf write landed outside [MangaApi]'s own change stream. */
    fun onMalChanged() {
        scope.launch {
            val payload = runCatching { MangaMalStorage.load() }.getOrNull().orEmpty()
            if (payload == mal) return@launch
            mal = payload
            _version.value += 1
        }
    }

    /**
     * Apply what the account holds.
     *
     * The session is adopted only when this device has none: a sign-in here is
     * newer than anything a blob written before it knows, and a stale token must
     * not replace a working one. The shelf is a union - titles this device is
     * missing are added, and a title read further away moves its position here.
     */
    suspend fun applyRemote(libraryJson: String, malJson: String) {
        try {
            if (malJson.isNotBlank()) {
                val local = runCatching { MangaMalStorage.load() }.getOrNull().orEmpty()
                if (local.isBlank()) {
                    MangaMal.adoptSession(malJson)
                    log.i { "adopted the account's MyAnimeList session" }
                }
            }
            if (libraryJson.isNotBlank()) {
                val rows = runCatching {
                    json.decodeFromString(shelfSerializer, libraryJson)
                }.getOrNull()
                if (rows == null) {
                    log.w { "the account's manga shelf did not decode" }
                } else {
                    merge(rows)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.e(error) { "could not apply the account's manga shelf" }
        }
        refresh()
    }

    private suspend fun merge(rows: List<MangaLibraryItem>) {
        val mine = runCatching { MangaApi.library() }.getOrDefault(emptyList())
        val byId = mine.associateBy { it.id }
        var added = 0
        var moved = 0
        for (row in rows) {
            if (row.id.isBlank()) continue
            val here = byId[row.id]
            if (here == null) {
                runCatching {
                    MangaApi.addToLibrary(
                        MangaCard(id = row.id, title = row.title, poster = row.poster),
                        at = row.at.takeIf { it > 0L },
                        read = row.read.takeIf { it > 0L },
                        mal = row.mal.takeIf { it > 0L },
                    )
                    // The length travels with the row so a restored shelf is not
                    // a wall of "Ch. 12" while the phone re-counts every title.
                    if (row.chapters > 0) MangaApi.recordChapterCount(row.id, row.chapters)
                    if (row.chapter.isNotBlank()) {
                        MangaApi.savePosition(
                            positionOf(row),
                            // A position that came from somewhere else is not a
                            // page turn here: move it without stamping "read
                            // now" on the shelf.
                            readAt = 0L,
                        )
                    }
                }.onSuccess { added++ }
                    .onFailure { error -> log.w(error) { "could not add ${row.id}" } }
            } else if (row.chapter.isNotBlank() && row.read > here.read) {
                runCatching { MangaApi.savePosition(positionOf(row), readAt = 0L) }
                    .onSuccess { moved++ }
                    .onFailure { error -> log.w(error) { "could not move ${row.id}" } }
            }
        }
        if (added > 0 || moved > 0) {
            log.i { "restored $added titles from the account, moved $moved positions" }
        }
    }

    private fun positionOf(row: MangaLibraryItem) = MangaPosition(
        id = row.id,
        title = row.title,
        poster = row.poster,
        chapter = row.chapter,
        number = row.number.ifBlank { "0" },
        page = row.page,
    )
}
