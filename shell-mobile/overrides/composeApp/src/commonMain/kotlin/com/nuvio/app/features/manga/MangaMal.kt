package com.nuvio.app.features.manga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.core.network.createApiHttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MyAnimeList, for the manga tab.
 *
 * The account is linked once, in the system browser. MyAnimeList answers a
 * native client fine, but the token exchange needs the registration's secret
 * and MAL's redirect URI is this app's own loopback origin, so the exchange
 * crosses the in-process server (`SekaiServer` → `Mal.java`) exactly as the
 * desktop window's crosses its Node server. The client never sees the secret.
 *
 * What the tab does with the account is deliberately small:
 *
 *   * the title page shows where the title sits on the list;
 *   * opening a chapter writes chapters read, never backwards, and marks the
 *     title completed when the last chapter is reached.
 *
 * Which MAL entry a title is, is answered in the order the desktop client
 * answered it, because that order is what made tracking feel automatic there:
 *
 *   1. what the reader picked by hand — the one answer nobody has to check, and
 *      therefore the one that outranks the rest;
 *   2. the id the catalogue itself carries (`MangaCard.malId`);
 *   3. the id an import wrote on the shelf row;
 *   4. a title search, whose best hit is remembered.
 *
 * The first three are answers; only the fourth is a guess. When the guess comes
 * back empty-handed the title page says so and offers the picker rather than
 * attaching the title to a row that is not it.
 */

internal const val MAL_CLIENT_ID = "63e08e44f57ef1b4644322939ebd9fa6"

/** The redirect URI registered on the project's MAL application. */
internal const val MAL_REDIRECT_URI = "http://localhost:8787/"

/** Marks a state this app minted, so the server can tell it from the web client's. */
private const val MAL_STATE_PREFIX = "sekai-"

private const val MAL_AUTHORIZE = "https://myanimelist.net/v1/oauth2/authorize"
private const val LIST_STALE_MS = 60_000L

/** Titles one import run may look up; a second run picks up where it left off. */
private const val IMPORT_LIMIT = 120

/** How stale the shelf's missing ids may get before they are looked up again. */
private const val SHELF_MATCH_MS = 12 * 60 * 60 * 1000L

/** Pages of the account's list to walk: 20 × 100 rows is far past any real list. */
private const val LIST_PAGES = 20
private const val SIGN_IN_TIMEOUT_MS = 5 * 60_000L

private val malJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

/** Where the linked account lives; the Android actual is SharedPreferences. */
internal expect object MangaMalStorage {
    fun load(): String?
    fun save(payload: String)
    fun clear()
}

/** What the title page draws in its MyAnimeList row. */
internal data class MangaMalAuthState(
    val connected: Boolean = false,
    val username: String = "",
    val pendingState: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * One row of a MyAnimeList search: what tells two same-named entries apart.
 *
 * The reader is the one who decides which of these is the series they are
 * looking at, so the picker hands them the kind, the year and the length — the
 * three facts that separate "Berserk" the 1989 manga from a light novel with
 * the same name.
 */
internal data class MangaMalHit(
    val id: Long = 0L,
    val title: String = "",
    val type: String = "",
    val year: String = "",
    val length: Int = 0,
    /** The cover MAL has for it; empty when the entry has none. */
    val picture: String = "",
) {
    /** The one line under the title: `manga · 1989 · 374 chapters`. */
    val meta: String
        get() = listOf(type, year, if (length > 0) "$length chapters" else "")
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/** One title, as MAL has it: the account's row plus the series' length. */
internal data class MangaMalEntry(
    val malId: Long = 0L,
    val title: String = "",
    val status: String? = null,
    val read: Int = 0,
    val total: Int = 0,
)

/**
 * The three answers to "where is this on my list?".
 *
 * They are not the same answer, and the title page draws them differently: the
 * account can know the title ([Found]), not know it ([Missing]), or not have
 * been asked successfully ([Failed]). Reporting the third as the second was a
 * bug a reader could see — open a dozen titles quickly, get throttled, and every
 * one of them says it is not on your list.
 */
internal sealed interface MangaMalLookup {
    data class Found(val entry: MangaMalEntry) : MangaMalLookup
    data object Missing : MangaMalLookup
    data class Failed(val message: String) : MangaMalLookup
}

/** What one import run did, for the line the Tracking tab draws. */
internal data class MalImportResult(
    val imported: Int = 0,
    val skipped: Int = 0,
    val unresolved: Int = 0,
    val failed: String? = null,
)

/** What [MangaMal.refreshLibraryProgress] found: how many rows moved forward. */
internal data class MangaProgressResult(
    val checked: Int = 0,
    val moved: Int = 0,
    val failed: String? = null,
)

private data class CachedEntry(val entry: MangaMalEntry?, val at: Long)

internal object MangaMal {

    private val http = createApiHttpClient()
    private val writeLock = Mutex()
    private val tokenLock = Mutex()
    private val entries = mutableMapOf<String, CachedEntry>()

    /**
     * The account's own id for a title, by normalized title, as the shelf
     * already knows it.
     *
     * This is the fix for a shelf that says "Not matched to a MyAnimeList entry"
     * about titles that *arrived* from MyAnimeList: the import writes the id into
     * each shelf row (`MangaLibraryItem.mal`), but the title page used to ask by
     * name, and a catalogue's title and the account's title differ by a word
     * often enough that the answer came back empty. The ids were on disk all
     * along — nothing here is another import, and no title has to be opened
     * again: the first screen that reads the shelf hands them over.
     */
    private val shelfIds = mutableMapOf<String, Long>()

    /** The same matches keyed by the catalogue's own id, which cannot drift. */
    private val shelfIdsById = mutableMapOf<String, Long>()

    /**
     * Read the shelf's own matches into the tracker.
     *
     * Called by whoever has just read the shelf — the manga tab on landing, an
     * import, a refresh — so the lookup below never has to be the first thing to
     * notice. Cheap: a map put per row, no network, nothing persisted here (the
     * id is already in the row; `resolveId` persists it the first time it is
     * actually used).
     */
    fun learnShelf(items: List<MangaLibraryItem>) {
        for (item in items) {
            if (item.mal <= 0L) continue
            val key = normalize(item.title)
            if (key.isNotEmpty()) shelfIds[key] = item.mal
            if (item.id.isNotBlank()) shelfIdsById[item.id] = item.mal
        }
    }

    /** The account's id the shelf already holds for this title, or 0. */
    fun shelfId(mangaId: String): Long =
        if (mangaId.isBlank()) 0L else shelfIdsById[mangaId] ?: 0L

    /**
     * The entry the reader picked for this title, or 0.
     *
     * A pick is the reader saying "this one", which is why it is stored on its
     * own: it is the answer every later question about this title starts from,
     * and no search is allowed to overwrite it.
     */
    fun pickedId(title: String): Long {
        val key = normalize(title)
        if (key.isEmpty()) return 0L
        ensureLoaded()
        return session?.picks?.get(key) ?: 0L
    }

    /** MAL's own name for the picked entry — what the row says it is tracking. */
    fun pickedName(title: String): String {
        val key = normalize(title)
        if (key.isEmpty()) return ""
        ensureLoaded()
        return session?.pickNames?.get(key).orEmpty()
    }

    /**
     * Bind this title to a MyAnimeList entry by hand.
     *
     * This is the old client's "pick the right entry", and it exists because a
     * title search is a guess: the catalogue calls a series one thing and the
     * account calls it another, and the reader is the only one who knows they
     * are the same book. The pick outranks every later guess — including the
     * catalogue's own id — so it is written where it survives a restart.
     *
     * Returns whether anything changed, so a caller can say so.
     */
    fun rememberPick(title: String, id: Long, name: String = ""): Boolean {
        ensureLoaded()
        val current = session ?: return false
        val key = normalize(title)
        if (key.isEmpty() || id <= 0L) return false
        val updated = current.copy(
            picks = current.picks + (key to id),
            pickNames = if (name.isBlank()) current.pickNames else current.pickNames + (key to name),
            // The id cache follows the pick, so anything that asks without the
            // title (a chapter read, a sync) gets the same answer.
            ids = current.ids + (key to id),
        )
        session = updated
        persist(updated)
        // A miss was cached under this title before the reader chose; the choice
        // is newer than the miss, and the next open must not read it.
        entries.remove(key)
        return true
    }

    /** The id for this title that somebody already resolved, or null. */
    private fun rememberedId(title: String): Long? {
        val key = normalize(title)
        if (key.isEmpty()) return null
        session?.picks?.get(key)?.let { return it }
        session?.ids?.get(key)?.let { return it }
        return shelfIds[key]
    }

    private var session: MalSession? = null
    private var pendingVerifier: String? = null

    /** One shelf repair at a time: the screen that asks for it may redraw. */
    private var matchingShelf = false

    /** Compose-observable: the title row and the browse header read this. */
    internal var authState by mutableStateOf(MangaMalAuthState())
        private set

    /** Read once from disk; every entry point calls it, so nobody has to order them. */
    fun ensureLoaded() {
        if (!loaded) {
            loaded = true
            val payload = runCatching { MangaMalStorage.load() }.getOrNull()
            val parsed = payload?.let { text ->
                runCatching { malJson.decodeFromString<MalSession>(text) }.getOrNull()
            }
            if (parsed != null && parsed.accessToken.isNotBlank()) {
                session = parsed
                authState = MangaMalAuthState(connected = true, username = parsed.username)
            }
            return
        }
        // A sign-in whose polling coroutine was cancelled between saving the
        // token and publishing it leaves the session restored but the UI still
        // saying "disconnected". The session is the truth; say so.
        val current = session
        if (current != null && !authState.connected) {
            authState = MangaMalAuthState(connected = true, username = current.username)
            pendingVerifier = null
        }
    }

    private var loaded = false

    /** MAL's supported PKCE method is `plain`, so challenge and verifier are one. */
    private fun randomVerifier(length: Int = 64): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val out = StringBuilder(length)
        repeat(length) { out.append(alphabet[kotlin.random.Random.nextInt(alphabet.length)]) }
        return out.toString()
    }

    private fun authorizeUrl(state: String, verifier: String): String =
        "$MAL_AUTHORIZE?response_type=code" +
            "&client_id=$MAL_CLIENT_ID" +
            "&redirect_uri=${encode(MAL_REDIRECT_URI)}" +
            "&code_challenge=$verifier" +
            "&code_challenge_method=plain" +
            "&state=${encode(state)}"

    /**
     * Start a login: mint the state and verifier, publish them so the UI can
     * poll, and hand back the URL the browser should open. Nothing is written
     * down until MAL has answered with a code.
     */
    fun beginSignIn(): String {
        ensureLoaded()
        val verifier = randomVerifier()
        val state = MAL_STATE_PREFIX + randomVerifier(32)
        pendingVerifier = verifier
        authState = authState.copy(
            pendingState = state,
            error = null,
            busy = false,
        )
        return authorizeUrl(state, verifier)
    }

    fun cancelSignIn() {
        pendingVerifier = null
        authState = authState.copy(pendingState = null, busy = false)
    }

    fun failSignIn(message: String) {
        pendingVerifier = null
        authState = authState.copy(pendingState = null, busy = false, error = message)
    }

    /**
     * Wait for the browser's half of the login. The person may sit on MAL's
     * consent page for a while, so this polls the in-app server rather than
     * blocking on anything.
     */
    suspend fun awaitSignIn() {
        val state = authState.pendingState ?: return
        val verifier = pendingVerifier ?: return
        val deadline = now() + SIGN_IN_TIMEOUT_MS
        while (now() < deadline) {
            val code = runCatching { pollCode(state) }.getOrNull()
            if (code != null) {
                authState = authState.copy(busy = true)
                runCatching { exchangeCode(code, verifier) }
                    .onSuccess {
                        pendingVerifier = null
                        entries.clear()
                        authState = MangaMalAuthState(connected = true, username = session?.username.orEmpty())
                    }
                    .onFailure { failSignIn(it.message ?: "MyAnimeList did not accept the sign-in.") }
                return
            }
            kotlinx.coroutines.delay(1_500)
        }
        failSignIn("The sign-in timed out. Try again.")
    }

    suspend fun disconnect() {
        session = null
        entries.clear()
        runCatching { MangaMalStorage.clear() }
        authState = MangaMalAuthState()
    }

    /**
     * Where this title sits on the account's list, or null when MAL has no
     * entry for it. Cached for a minute: opening a title, reading, and coming
     * back should not spend three calls.
     */
    /**
     * One row by the account's own id — no name search, no guessing.
     *
     * This is what a shelf imported once already knows (see `MangaMal`'s import
     * and `MangaLibraryItem.mal`), and asking by id is the difference between a
     * refresh that moves a shelf forward and one that quietly finds nothing
     * because the catalogue's title and the account's title differ by a word.
     */
    suspend fun entry(malId: Long): MangaMalEntry? {
        ensureLoaded()
        if (session == null || malId <= 0) return null
        val key = "id:$malId"
        val cached = entries[key]
        if (cached != null && now() - cached.at < LIST_STALE_MS) return cached.entry
        return writeLock.withLock {
            val fresh = entries[key]
            if (fresh != null && now() - fresh.at < LIST_STALE_MS) return@withLock fresh.entry
            val result = try {
                fetchEntry(malId)
            } catch (e: MalHttpException) {
                // "No such entry" is an answer and is cached; a throttle is not.
                if (e.status == 404) null else return@withLock null
            } catch (e: Exception) {
                return@withLock null
            }
            entries[key] = CachedEntry(result, now())
            result
        }
    }

    suspend fun entry(title: String, force: Boolean = false): MangaMalEntry? {
        ensureLoaded()
        if (session == null) return null
        // An id the shelf already knows beats asking by name — including a miss
        // that was cached before the shelf was read. This is what keeps a title
        // that came from MyAnimeList from being called "not matched".
        rememberedId(title)?.let { id -> return entry(id) }
        val key = normalize(title)
        if (key.isEmpty()) return null
        val cached = entries[key]
        if (!force && cached != null && now() - cached.at < LIST_STALE_MS) return cached.entry
        return writeLock.withLock {
            val fresh = cached
            if (!force && fresh != null && now() - fresh.at < LIST_STALE_MS) return@withLock fresh.entry
            val result = try {
                fetchEntryForTitle(title)
            } catch (e: Exception) {
                // Not cached: a throttle is not a fact about this title.
                return@withLock null
            }
            entries[key] = CachedEntry(result, now())
            result
        }
    }

    /**
     * The same question as [entry], but saying *why* there is no row.
     *
     * The id the shelf holds is asked first — by catalogue id, so a title whose
     * name changed upstream is still found — and only then by name. A failed
     * ask is not cached as a miss: "MyAnimeList did not answer" and "MyAnimeList
     * does not know this title" are different facts, and the next open should
     * be able to learn the first one was temporary.
     */
    suspend fun lookup(
        title: String,
        mangaId: String = "",
        malId: Long = 0L,
        force: Boolean = false,
    ): MangaMalLookup {
        ensureLoaded()
        if (session == null) return MangaMalLookup.Failed("MyAnimeList is not connected.")
        val key = normalize(title)
        // The answers first, each stronger than the one after it: the reader's
        // pick, the shelf row's own id, the catalogue's id, then anything the
        // app worked out earlier.
        val known = pickedId(title).takeIf { it > 0L }
            ?: shelfId(mangaId).takeIf { it > 0L }
            ?: malId.takeIf { it > 0L }
            ?: rememberedId(title)
        if (known != null && known > 0L) {
            val cacheKey = "id:$known"
            val cached = entries[cacheKey]
            if (!force && cached != null && now() - cached.at < LIST_STALE_MS) {
                return cached.entry?.let(MangaMalLookup::Found) ?: MangaMalLookup.Missing
            }
            return try {
                val found = fetchEntry(known)
                entries[cacheKey] = CachedEntry(found, now())
                // An id that answered is worth keeping under this title: a
                // chapter read resolves by name, and the next open should not
                // have to ask the same question of the network.
                if (found != null) rememberId(title, known)
                found?.let(MangaMalLookup::Found) ?: MangaMalLookup.Missing
            } catch (e: MalHttpException) {
                // 404 is MAL saying it has no such entry, not a failure to ask.
                if (e.status == 404) MangaMalLookup.Missing
                else MangaMalLookup.Failed(e.message ?: "MyAnimeList did not answer.")
            } catch (e: Exception) {
                MangaMalLookup.Failed(e.message ?: "MyAnimeList did not answer.")
            }
        }
        val cached = entries[key]
        if (!force && cached != null && now() - cached.at < LIST_STALE_MS) {
            return cached.entry?.let(MangaMalLookup::Found) ?: MangaMalLookup.Missing
        }
        return writeLock.withLock {
            val fresh = entries[key]
            if (!force && fresh != null && now() - fresh.at < LIST_STALE_MS) {
                return@withLock fresh.entry?.let(MangaMalLookup::Found) ?: MangaMalLookup.Missing
            }
            try {
                // The search answers with an id; the row is a second question.
                val found = search(title)?.let { fetchEntry(it) }
                entries[key] = CachedEntry(found, now())
                found?.let(MangaMalLookup::Found) ?: MangaMalLookup.Missing
            } catch (e: Exception) {
                MangaMalLookup.Failed(e.message ?: "MyAnimeList did not answer.")
            }
        }
    }

    /**
     * Opening a chapter tells MAL "I am at chapter [chapterNumber]". The number
     * only moves forward, the status is kept unless it was a plan, and the last
     * chapter completes the title — the same rules the web client writes by, so
     * the two agree.
     *
     * A failure is swallowed: reading must never wait on a tracker.
     *
     * The account's number is a *position*, not a count of distinct chapters: a
     * reader who opens chapter 40 has read up to 40 as far as MyAnimeList is
     * concerned, and that is what everyone else's client writes there. So the
     * chapter number is what is written, even when the device's own record knows
     * only a few chapters were actually opened — the exact count is what this
     * app's own title page shows, from its own read record. Sending the count
     * here instead would make the account say "1 chapter" to someone sitting on
     * chapter 40.
     */
    suspend fun chapterRead(title: String, chapterNumber: String) {
        ensureLoaded()
        if (session == null) return
        val number = chapterNumber.trim().substringBefore('.').toIntOrNull() ?: return
        if (number < 1) return
        writeLock.withLock {
            runCatching {
                val resolved = resolveId(title)
                if (resolved != null) {
                    val current = fetchEntry(resolved)
                    if (current != null && current.read < number) {
                        val status = when {
                            current.total > 0 && number >= current.total -> "completed"
                            current.status != null && !current.status.startsWith("plan_to_") -> current.status
                            else -> "reading"
                        }
                        putStatus(resolved, status, number)
                        entries[normalize(title)] = CachedEntry(
                            current.copy(status = status, read = number),
                            now(),
                        )
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------------- import

    /**
     * The account's whole manga list, brought onto this phone's shelf.
     *
     * A list kept elsewhere is only half a sync: the reader writes progress
     * *to* MyAnimeList the moment a chapter opens, but the rows read on another
     * device, in another app, before this one was installed are rows this app
     * has never seen. This is the other half, in two parts: every row on the
     * account's list is added to the Library's manga shelf (what the reader
     * keeps), and every row that has been read also becomes a position (how far
     * in they are), which is what the shelf's "Ch. 12" line is drawn from.
     *
     * The matching is deliberately conservative, because a wrong match would put
     * a row for *another* title on the shelf: a MAL entry is only imported when
     * a catalogue search answers with a title that scores against it (the same
     * scoring the tracker's own lookups use), and the chapter is only written
     * when the catalogue numbers that chapter itself. What cannot be matched is
     * counted and left alone rather than guessed at.
     *
     * Only forward motion, and only addition: a title already on the shelf keeps
     * the moment it was added, and an entry whose MAL progress is at or behind
     * the position already written is left as it is — so running the import
     * twice cannot walk a reader backwards.
     *
     * The order is the account's own: the list is read last-read first, and each
     * row is stamped a millisecond older than the one before it, so the shelf's
     * "Recently added" — which is ordered by that stamp — lists an imported
     * shelf in the order it was last read. Stamping them all "now" was what made
     * an import look shuffled: the server sorts newest first, so every row added
     * in one run landed in whatever order the stamps collided in, which for a
     * batch added within the same few milliseconds was the reverse of MAL's.
     */
    suspend fun importLibrary(
        limit: Int = IMPORT_LIMIT,
        onProgress: (done: Int, total: Int, imported: Int) -> Unit = { _, _, _ -> },
    ): MalImportResult {
        ensureLoaded()
        if (session == null) return MalImportResult(failed = "Connect MyAnimeList first.")

        val rows = try {
            listEntries()
        } catch (e: Exception) {
            return MalImportResult(failed = e.message ?: "MyAnimeList did not answer.")
        }

        var imported = 0
        var skipped = 0
        var unresolved = 0
        var examined = 0

        // The shelf and the positions as they stand, so a second run is cheap:
        // one read each, and a map from title to what is already known.
        val shelf = runCatching { MangaApi.library() }.getOrDefault(emptyList())
        val onShelf = shelf.map { it.id }.toSet()
        val existing = runCatching { MangaApi.positions() }.getOrDefault(emptyList())
            .associateBy { normalize(it.title) }

        for ((index, row) in rows.withIndex()) {
            if (examined >= limit) break
            examined++
            val (node, status) = row
            val title = node.title?.takeIf { it.isNotBlank() } ?: continue

            // Every row on the account's list is a title the reader keeps — the
            // shelf is what MyAnimeList says is on it, which is the point of an
            // import: a reader with three hundred rows should not have to add
            // them one at a time.
            val card = matchCard(title)
            if (card == null) {
                unresolved++
                onProgress(examined, minOf(rows.size, limit), imported)
                continue
            }
            // What the account says about when this row was last read. On the
            // shelf it is the only thing a re-import has to *update*: the moment
            // of adding is kept (see `Manga.addToLibrary`), but the moment of
            // last read is what the shelf's "Last read" order is drawn from, and
            // a title read in the browser or another app never writes a position
            // here.
            val lastRead = parseMalTime(status.updatedAt)
            // The id is what makes the *next* question cheap: "how far am I on
            // this title" stops being a name search and becomes a lookup.
            val malId = node.id
            // The row's own id, handed to the title lookup under the name the
            // app asks with. Without this the shelf carried the id and the title
            // page still searched by name — which is how a title that *came from*
            // MAL was reported as not matching it.
            if (malId != null) teach(card.title, malId)
            if (onShelf.contains(card.id)) {
                skipped++
                if (lastRead > 0 || malId != null) {
                    runCatching {
                        MangaApi.addToLibrary(
                            card,
                            at = importStamp(index),
                            read = lastRead,
                            mal = malId,
                        )
                    }
                }
            } else {
                runCatching {
                    MangaApi.addToLibrary(
                        card,
                        at = importStamp(index),
                        read = lastRead,
                        mal = malId,
                    )
                }.onSuccess { imported++ }
            }

            // The shelf is *which* titles; the position is *how far*. Only the
            // rows that have been read have one, and only forward motion is
            // written: an import must not walk a reader backwards.
            val read = status.numChaptersRead ?: 0
            if (read > 0) {
                val already = existing[normalize(title)]
                if (already == null || (already.number.toFloatOrNull() ?: 0f) < read) {
                    runCatching { writePosition(card, read, readAt = lastRead) }
                }
            }
            onProgress(examined, minOf(rows.size, limit), imported)
        }
        // Written once rather than per row: the ids learned above, and the fact
        // that the shelf has just been matched — so the background repair below
        // does not repeat a walk the import has just done.
        session?.let { current ->
            session = current.copy(shelfMatchedAt = now())
            persist(session ?: current)
        }

        return MalImportResult(
            imported = imported,
            skipped = skipped,
            unresolved = unresolved,
        )
    }

    /**
     * Give the shelf the ids it is missing, from the account's own list.
     *
     * The import matches MAL rows to catalogue cards by searching for each title,
     * which is slow and can lose a row. This is the cheap half, and it exists for
     * one reason: a shelf imported *before* the app started storing the account's
     * id per row has no ids at all, and without them every title has to be found
     * by name — the titles that name-search loses are exactly the ones that said
     * "not matched", and asking a reader to open every title again to fix that
     * would be the app's problem, not theirs.
     *
     * One list call, then everything is local: each MAL row is matched to the
     * shelf row whose title scores best against it. Only rows still without an
     * id are written, and a write keeps the moment added and the last-read stamp
     * (`Manga.addToLibrary` merges the id in). Bounded by a stamp, so a shelf with
     * titles genuinely absent from the account is not re-walked on every launch.
     */
    suspend fun matchShelf(): Int {
        ensureLoaded()
        val current = session ?: return 0
        if (now() - current.shelfMatchedAt < SHELF_MATCH_MS) return 0
        if (matchingShelf) return 0
        matchingShelf = true
        try {
            val shelf = runCatching { MangaApi.library() }.getOrDefault(emptyList())
            if (shelf.isEmpty()) return 0
            learnShelf(shelf)
            if (shelf.none { it.mal <= 0L }) return 0
            // A failed list read changes nothing and is retried next time: the
            // stamp is only written once this has actually run.
            val rows = runCatching { listEntries() }.getOrNull() ?: return 0
            var matched = 0
            for ((node, _) in rows) {
                val id = node.id ?: continue
                val title = node.title?.takeIf { it.isNotBlank() } ?: continue
                // Every name the account has for the row — its own, the English
                // one, the original, and the synonyms — because the catalogue's
                // name for a series is often one of those rather than the first.
                val names = buildList {
                    add(title)
                    node.alternativeTitles?.en?.let(::add)
                    node.alternativeTitles?.ja?.let(::add)
                    node.alternativeTitles?.synonyms?.forEach(::add)
                }.map(::normalize).filter { it.isNotEmpty() }
                if (names.isEmpty()) continue
                var hit: MangaLibraryItem? = null
                var bestScore = 0
                for (item in shelf) {
                    if (item.mal > 0L) continue
                    val shelfName = normalize(item.title)
                    val score = names.maxOf { titleScore(it, shelfName) }
                    if (score > bestScore) {
                        bestScore = score
                        hit = item
                    }
                }
                val card = hit ?: continue
                // Taught before the write, so the title page and a chapter read
                // already answer by id while the shelf row is being rewritten.
                teach(card.title, id)
                runCatching {
                    MangaApi.addToLibrary(
                        MangaCard(id = card.id, title = card.title, poster = card.poster),
                        mal = id,
                    )
                }.onSuccess { matched++ }
            }
            return matched
        } finally {
            matchingShelf = false
            session?.let { updated ->
                session = updated.copy(shelfMatchedAt = now())
                persist(session ?: updated)
            }
        }
    }

    /**
     * Every title on the shelf, asked where it stands.
     *
     * Opening a title already asks its account and moves the shelf forward when
     * the account knows a chapter this device does not (see
     * `AniList.syncProgress`) — which is why a shelf read elsewhere shows "Not
     * started" until each title is opened once. That is one title at a time for
     * a question with the same answer for all of them, so this is the same walk
     * over the whole shelf: MAL first, AniList only where MAL does not know the
     * title, nothing ever moves backwards, and a chapter the account has read
     * becomes a position here.
     *
     * Deliberately the same function the title page calls: two implementations
     * of "how far am I" would disagree the first time one of them was fixed.
     */
    suspend fun refreshLibraryProgress(
        onProgress: (done: Int, total: Int, moved: Int) -> Unit = { _, _, _ -> },
    ): MangaProgressResult {
        ensureLoaded()
        if (session == null) return MangaProgressResult(failed = "Connect MyAnimeList first.")

        val shelf = runCatching { MangaApi.library() }.getOrElse { rows ->
            return MangaProgressResult(failed = rows.message ?: "The shelf could not be read.")
        }
        // The ids the shelf already holds are the ones a refresh should ask by;
        // handing them over here means it does, even when the manga tab was not
        // the screen that opened Settings.
        learnShelf(shelf)

        var moved = 0
        var checked = 0
        for (row in shelf) {
            checked++
            val card = MangaCard(id = row.id, title = row.title, poster = row.poster)
            val synced = runCatching {
                com.nuvio.app.features.anilist.AniList.syncProgress(card, malId = row.mal)
            }.getOrNull()
            if (synced != null) moved++
            onProgress(checked, shelf.size, moved)
        }
        // No revision bump of its own: every write above goes through
        // `savePosition` (or `addToLibrary`, for a title the account knew and
        // the shelf did not), and both already bump it.
        return MangaProgressResult(checked = checked, moved = moved)
    }

    /**
     * The moment an imported row counts as added: now, less its place in the
     * list.
     *
     * One millisecond per row is enough to keep the order, and it keeps the
     * imported shelf at the top of "Recently added" — these titles *were* just
     * added to it — without inventing timestamps that pretend to be MAL's own.
     */
    private fun importStamp(index: Int): Long = now() - index.toLong()

    /**
     * Where the account left a title, written as this app's own position, or
     * `false` when the catalogue does not number that chapter.
     */
    private suspend fun writePosition(card: MangaCard, read: Int, readAt: Long = 0L): Boolean {
        val chapters = runCatching { MangaApi.chapters(card.id) }.getOrDefault(emptyList())
        if (chapters.isEmpty()) return false

        // The chapter that was read, by its own number: `Ch. 41.5` counts as
        // 41, and a list that numbers chapters oddly is matched to the last
        // chapter at or below the number read rather than to the wrong one.
        val wanted = chapters
            .mapNotNull { chapter ->
                val number = chapter.number.trim().toFloatOrNull()
                if (number == null) null else chapter to number
            }
            .filter { (_, number) -> number <= read }
            .maxByOrNull { (_, number) -> number }
            ?.first
            ?: return false

        MangaApi.savePosition(
            MangaPosition(
                id = card.id,
                title = card.title,
                poster = card.poster,
                chapter = wanted.id,
                number = wanted.number,
                page = 0,
                pages = 0,
            ),
            // The account's own moment, so that the shelf's "Last read" is the
            // account's order and not the order this import happened to run in.
            readAt = readAt,
        )
        return true
    }

    /**
     * A title added to the shelf while the account is linked, said to the
     * account too — otherwise the two lists drift the moment the reader stops
     * using one of them. A row that already exists is left alone: "plan to
     * read" must never overwrite progress.
     */
    suspend fun addToPlan(title: String) {
        ensureLoaded()
        if (session == null) return
        writeLock.withLock {
            runCatching {
                val id = resolveId(title) ?: return@runCatching
                if (fetchEntry(id) != null) return@runCatching
                putStatus(id, "plan_to_read", 0)
                entries[normalize(title)] = CachedEntry(
                    MangaMalEntry(malId = id, title = title, status = "plan_to_read"),
                    now(),
                )
            }
        }
    }

    /** The catalogue's best answer to a MAL title, or null when none is close. */
    private suspend fun matchCard(title: String): MangaCard? {
        val want = normalize(title)
        if (want.isEmpty()) return null
        val cards = runCatching { MangaApi.search(query = title, page = 1).items }
            .getOrDefault(emptyList())
        var best: MangaCard? = null
        var bestScore = 0
        for (card in cards) {
            val score = titleScore(want, normalize(card.title))
            if (score > bestScore) {
                bestScore = score
                best = card
            }
        }
        return if (bestScore > 0) best else null
    }

    /**
     * The account's manga list, page by page.
     *
     * MAL pages by offset and says whether there is another page; the walk is
     * bounded so a very long list cannot turn one tap into forty calls.
     */
    private suspend fun listEntries(): List<Pair<MalListEntry, MalListStatus>> {
        val token = token() ?: throw IllegalStateException("Connect MyAnimeList first.")
        val out = ArrayList<Pair<MalListEntry, MalListStatus>>()
        var offset = 0
        repeat(LIST_PAGES) {
            val body = apiGet(
                token,
                // Sorted by the account's own order: `list_updated_at` is when
                // the reader last touched a title, descending, which is what
                // "the order of last read of MAL" means.
                "users/@me/mangalist?fields=list_status,num_chapters,main_picture,alternative_titles" +
                    "&sort=list_updated_at&limit=100&offset=$offset",
            )
            val parsed = malJson.decodeFromString<MalListResponse>(body)
            val page = parsed.data.orEmpty()
            for (row in page) {
                val node = row.node ?: continue
                out.add(node to (row.status ?: MalListStatus()))
            }
            if (page.isEmpty() || parsed.paging?.next.isNullOrBlank()) return out
            offset += page.size
        }
        return out
    }

    // ------------------------------------------------------------- the wire

    private suspend fun pollCode(state: String): String? {
        val response = http.get("$MANGA_SERVER/manga/mal/callback") {
            parameter("state", state)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status.value !in 200..299) return null
        val body = response.bodyAsText()
        return runCatching { malJson.decodeFromString<MalCallback>(body).code }.getOrNull()
    }

    private suspend fun exchangeCode(code: String, verifier: String) {
        val body = tokenRequest(
            "grant_type=authorization_code" +
                "&code=${encode(code)}" +
                "&code_verifier=${encode(verifier)}" +
                "&redirect_uri=${encode(MAL_REDIRECT_URI)}",
        )
        applyTokens(body)
        fetchUsername()?.let { name ->
            session?.let { current ->
                val updated = current.copy(username = name)
                session = updated
                persist(updated)
            }
        }
    }

    private suspend fun tokenRequest(query: String): String {
        val response = http.post(
            "$MANGA_SERVER/api/mal/token?client_id=$MAL_CLIENT_ID&$query",
        ) {
            header("accept", "application/json")
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            val message = runCatching {
                malJson.decodeFromString<MalError>(body).message
            }.getOrNull()
            throw IllegalStateException(
                message?.takeIf { it.isNotBlank() }
                    ?: if (response.status.value >= 500) {
                        // MAL's edge failing is not MAL's verdict on the code;
                        // the relay already asked twice. Say what to do.
                        "MyAnimeList's sign-in service had a gateway error. Try Connect again in a moment."
                    } else {
                        "MyAnimeList refused the sign-in (${response.status.value})."
                    },
            )
        }
        return body
    }

    private suspend fun applyTokens(body: String) {
        val parsed = runCatching { malJson.decodeFromString<MalTokenResponse>(body) }.getOrNull()
            ?: throw IllegalStateException("MyAnimeList's answer was unreadable.")
        val access = parsed.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("MyAnimeList sent no access token.")
        val expires = parsed.expiresIn ?: 3600L
        val current = session ?: MalSession()
        val updated = current.copy(
            accessToken = access,
            refreshToken = parsed.refreshToken?.takeIf { it.isNotBlank() } ?: current.refreshToken,
            expiresAt = now() + expires.coerceAtLeast(60L) * 1000L,
        )
        session = updated
        persist(updated)
    }

    private fun persist(value: MalSession) {
        runCatching { MangaMalStorage.save(malJson.encodeToString(MalSession.serializer(), value)) }
    }

    /** A live access token, refreshed under the lock when it is about to expire. */
    private suspend fun token(): String? {
        val current = session ?: return null
        if (current.expiresAt > now() + 90_000L) return current.accessToken
        return tokenLock.withLock {
            val again = session ?: return@withLock null
            if (again.expiresAt > now() + 90_000L) return@withLock again.accessToken
            val refresh = again.refreshToken
            if (refresh.isNullOrBlank()) return@withLock again.accessToken
            val body = tokenRequest(
                "grant_type=refresh_token&refresh_token=${encode(refresh)}",
            )
            applyTokens(body)
            session?.accessToken
        }
    }

    private suspend fun fetchUsername(): String? {
        val token = token() ?: return null
        val body = apiGet(token, "users/@me?fields=name")
        return runCatching { malJson.decodeFromString<MalMe>(body).name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun apiGet(token: String, rest: String): String {
        val response = http.get("$MANGA_SERVER/api/mal/v2/$rest") {
            header("authorization", "Bearer $token")
            header("accept", "application/json")
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw malHttpFailure(response.status.value, body)
        }
        return body
    }

    /**
     * MAL's refusal, as an exception that keeps its status code.
     *
     * The code matters: a 404 on a title is MAL saying it does not know that
     * entry, which is an answer, where a 401 or a 500 is the question failing.
     * Reporting the first as the second is how a signed-in reader was told their
     * account was unreachable for every title.
     */
    private fun malHttpFailure(status: Int, body: String): MalHttpException {
        val error = runCatching { malJson.decodeFromString<MalError>(body) }.getOrNull()
        val detail = error?.message?.takeIf { it.isNotBlank() }
            ?: error?.error?.takeIf { it.isNotBlank() }?.replace('_', ' ')
        return MalHttpException(
            status,
            detail?.let { "MyAnimeList: $it" } ?: "MyAnimeList answered $status.",
        )
    }

    /**
     * Remember, in memory, that this title is this MAL id.
     *
     * Keyed by the *catalogue's* title because that is the name every caller
     * passes (`chapterRead`, the title page, the shelf) — the account's own
     * spelling was already matched to the card by the import, and keeping both
     * would be two keys for one row.
     */
    private fun teach(title: String, id: Long): Boolean {
        val current = session ?: return false
        val key = normalize(title)
        if (key.isEmpty() || id <= 0L) return false
        if (current.ids[key] == id) return false
        session = current.copy(ids = current.ids + (key to id))
        return true
    }

    /** [teach], written down — for an id that came from an answer, not a guess. */
    private fun rememberId(title: String, id: Long): Boolean {
        if (!teach(title, id)) return false
        session?.let(::persist)
        return true
    }

    private suspend fun resolveId(title: String): Long? {
        val current = session ?: return null
        val key = normalize(title)
        current.ids[key]?.let { return it }
        // The shelf's own match, written by the import — a resolved id, not a
        // guess, so it is tried before a search that might lose the title.
        shelfIds[key]?.let { id ->
            val updated = current.copy(ids = current.ids + (key to id))
            session = updated
            persist(updated)
            return id
        }
        val found = search(title) ?: return null
        val updated = current.copy(ids = current.ids + (key to found))
        session = updated
        persist(updated)
        return found
    }

    /**
     * The best of MAL's answers to a title search.
     *
     * The desktop client's rule, and the one that made tracking feel automatic:
     * the same name, else a name one of them extends, else MAL's own first hit.
     * Refusing the first hit was this app's answer for a while, and it turned
     * every title whose catalogue name and list name differ into "not matched" —
     * a shelf imported from MyAnimeList could show a hundred of them at once. A
     * wrong guess is now correctable in one tap (a pick outranks it forever
     * after); a blanket refusal was not correctable at all.
     */
    private suspend fun search(title: String): Long? =
        searchHits(title, limit = 6).firstOrNull()?.id

    /**
     * MAL's own search, as hits a reader can be shown.
     *
     * Every hit carries what tells two same-named entries apart — the kind, the
     * year it started, and how long it is — because the choice is the reader's,
     * and none of those can be guessed for them. The list is ordered by the same
     * confidence the automatic match uses, so the top row is the row the app
     * would have taken on its own.
     */
    suspend fun searchHits(query: String, limit: Int = 10): List<MangaMalHit> {
        ensureLoaded()
        val text = query.trim()
        if (text.isEmpty() || session == null) return emptyList()
        val token = token() ?: return emptyList()
        val body = apiGet(
            token,
            "manga?q=${encode(text)}&limit=$limit" +
                "&fields=title,alternative_titles,media_type,start_date,num_chapters," +
                "main_picture",
        )
        val rows = runCatching { malJson.decodeFromString<MalSearchResponse>(body) }.getOrNull()
            ?.data
            .orEmpty()
        if (rows.isEmpty()) return emptyList()
        val want = normalize(text)
        val scored = rows.mapNotNull { row ->
            val node = row.node ?: return@mapNotNull null
            val id = node.id ?: return@mapNotNull null
            if (id <= 0L) return@mapNotNull null
            val names = buildList {
                node.title?.let(::add)
                node.alternativeTitles?.en?.let(::add)
                node.alternativeTitles?.ja?.let(::add)
                node.alternativeTitles?.synonyms?.forEach(::add)
            }.map(::normalize).filter { it.isNotEmpty() }
            val score = if (want.isEmpty() || names.isEmpty()) 0
            else names.maxOf { titleScore(want, it) }
            MangaMalHit(
                id = id,
                title = node.title.orEmpty(),
                type = node.mediaType.orEmpty().replace('_', ' '),
                year = node.startDate.orEmpty().take(4),
                length = node.numChapters ?: 0,
                // MAL sends two sizes and sometimes only the small one, so the
                // large is preferred and the medium stands in when it is all
                // there is — a 44dp thumbnail does not need the original.
                picture = node.mainPicture?.large?.takeIf { it.isNotBlank() }
                    ?: node.mainPicture?.medium.orEmpty(),
            ) to score
        }
        // A stable sort: confidence first, MAL's own ranking within a tie.
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Exact beats prefix beats containment beats "the same words in another
     * order"; a short shared prefix is no match.
     *
     * The word test is what pairs `Regression of the Third Prince` with `The
     * Third Prince of the Fallen Kingdom has Regressed` — four of five words the
     * same, and no substring in common, which is the shape of a Korean or
     * Chinese series' English title between two sites. It needs most of the
     * shorter name's words to be present, so "The Duke's Daughter" and "The
     * Duke's Son" stay apart.
     */
    private fun titleScore(want: String, candidate: String): Int {
        if (want.isEmpty() || candidate.isEmpty()) return 0
        if (want == candidate) return 100
        val short = if (want.length <= candidate.length) want else candidate
        val long = if (short === want) candidate else want
        if (short.length >= 6 && long.startsWith(short)) return 50 + short.length
        if (short.length >= 8 && long.contains(short)) return 20 + short.length
        val shared = sharedWords(want, candidate)
        if (shared >= 3) return 10 + shared
        return 0
    }

    /** How many words the two names have in common, when that is most of both. */
    private fun sharedWords(want: String, candidate: String): Int {
        val a = words(want)
        val b = words(candidate)
        if (a.size < 3 || b.size < 3) return 0
        val common = a.count { it in b }
        val small = minOf(a.size, b.size)
        // Four of five is a match; three of eight is a coincidence.
        return if (common.toDouble() / small >= 0.75) common else 0
    }

    private fun words(value: String): Set<String> {
        val out = mutableSetOf<String>()
        val word = StringBuilder()
        for (char in value.lowercase()) {
            if (char.isLetterOrDigit()) word.append(char)
            else if (word.isNotEmpty()) {
                out.add(word.toString())
                word.setLength(0)
            }
        }
        if (word.isNotEmpty()) out.add(word.toString())
        return out
    }

    private suspend fun fetchEntryForTitle(title: String): MangaMalEntry? {
        val id = resolveId(title) ?: return null
        return fetchEntry(id)
    }

    /** One title's row, or null when MAL does not know the id or has no row. */
    private suspend fun fetchEntry(id: Long): MangaMalEntry? {
        val token = token() ?: return null
        val body = runCatching {
            apiGet(
                token,
                "manga/$id?fields=title,num_chapters,my_list_status{status,num_chapters_read}",
            )
        }.getOrNull() ?: return null
        val parsed = runCatching { malJson.decodeFromString<MalEntryResponse>(body) }.getOrNull()
            ?: return null
        return MangaMalEntry(
            malId = id,
            title = parsed.title.orEmpty(),
            status = parsed.status?.status,
            read = parsed.status?.numChaptersRead ?: 0,
            total = parsed.numChapters ?: 0,
        )
    }

    private suspend fun putStatus(id: Long, status: String, read: Int) {
        val token = token() ?: return
        val response = http.put("$MANGA_SERVER/api/mal/v2/manga/$id/my_list_status") {
            header("authorization", "Bearer $token")
            header("accept", "application/json")
            parameter("status", status)
            parameter("num_chapters_read", read)
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException("MyAnimeList answered ${response.status.value}.")
        }
    }

    private fun normalize(value: String): String =
        buildString {
            for (char in value.lowercase()) {
                if (char.isLetterOrDigit()) append(char)
            }
        }

    private fun encode(value: String): String =
        buildString {
            for (char in value) {
                if (char.isLetterOrDigit() || char in "-._~") append(char)
                else {
                    for (byte in char.toString().encodeToByteArray()) {
                        append('%')
                        append("0123456789ABCDEF"[byte.toInt() shr 4 and 0xF])
                        append("0123456789ABCDEF"[byte.toInt() and 0xF])
                    }
                }
            }
        }

    private fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

@Serializable
private data class MalSession(
    val accessToken: String = "",
    val refreshToken: String? = null,
    val expiresAt: Long = 0L,
    val username: String = "",
    val ids: Map<String, Long> = emptyMap(),
    /**
     * The entries the reader picked by hand, by normalized catalogue title.
     *
     * Kept apart from [ids] because the difference matters: an id here was
     * chosen, and a choice must never be replaced by a later search's guess.
     * [ids] is the cache of everything else the app worked out for itself.
     */
    val picks: Map<String, Long> = emptyMap(),
    /** MAL's own name for each picked entry, so the row can say which one it is. */
    val pickNames: Map<String, String> = emptyMap(),
    /** When the shelf's missing ids were last derived from the account's list. */
    val shelfMatchedAt: Long = 0L,
)

@Serializable
private data class MalTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

/** MAL's answer, kept with the code so a 404 can mean "no such entry". */
private class MalHttpException(val status: Int, message: String) : Exception(message)

@Serializable
private data class MalError(val error: String? = null, val message: String? = null)

@Serializable
private data class MalCallback(val code: String? = null, val state: String? = null)

@Serializable
private data class MalMe(val name: String? = null)

@Serializable
private data class MalSearchResponse(val data: List<MalSearchEdge>? = null)

@Serializable
internal data class MalListResponse(
    val data: List<MalListEdge>? = null,
    val paging: MalPaging? = null,
)

@Serializable
internal data class MalListEdge(
    val node: MalListEntry? = null,
    @SerialName("list_status") val status: MalListStatus? = null,
)

@Serializable
internal data class MalListEntry(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("num_chapters") val numChapters: Int? = null,
    @SerialName("main_picture") val mainPicture: MalPicture? = null,
    @SerialName("alternative_titles") val alternativeTitles: MalAlternativeTitles? = null,
)

@Serializable
internal data class MalPicture(val medium: String? = null, val large: String? = null)

@Serializable
internal data class MalPaging(val next: String? = null)

@Serializable
private data class MalSearchEdge(val node: MalSearchNode? = null)

@Serializable
private data class MalSearchNode(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("alternative_titles") val alternativeTitles: MalAlternativeTitles? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("num_chapters") val numChapters: Int? = null,
    @SerialName("main_picture") val mainPicture: MalPicture? = null,
)

@Serializable
internal data class MalAlternativeTitles(
    val synonyms: List<String>? = null,
    val en: String? = null,
    val ja: String? = null,
)

@Serializable
private data class MalEntryResponse(
    val title: String? = null,
    @SerialName("num_chapters") val numChapters: Int? = null,
    @SerialName("my_list_status") val status: MalListStatus? = null,
)

/**
 * `2024-05-06T07:08:09+00:00` — the one date format MyAnimeList writes — as epoch
 * milliseconds.
 *
 * No date library: this module has none, the format is fixed, and the parser is
 * small enough to hold in the head. Anything that does not look like that format
 * is 0, which the shelf reads as "no last-read stamp" rather than as 1970 — a
 * row with a wrong stamp would outrank every real one.
 */
internal fun parseMalTime(value: String?): Long {
    if (value.isNullOrBlank()) return 0
    val text = value.trim()
    if (text.length < 19) return 0
    fun at(from: Int, to: Int): Int? = text.substring(from, to).toIntOrNull()
    val year = at(0, 4) ?: return 0
    val month = at(5, 7) ?: return 0
    val day = at(8, 10) ?: return 0
    val hour = at(11, 13) ?: return 0
    val minute = at(14, 16) ?: return 0
    val second = at(17, 19) ?: return 0
    if (month !in 1..12 || day !in 1..31) return 0

    // Days from 1970-01-01, by the days-from-civil algorithm: no timezone table,
    // no leap-year list, and it agrees with every calendar back to year 0.
    val y = if (month <= 2) year - 1 else year
    val era = if (y >= 0) y / 400 else (y - 399) / 400
    val yearOfEra = y - era * 400
    val dayOfYear = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    val days = era.toLong() * 146_097L + dayOfEra - 719_468L

    val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second
    return (seconds - offsetSeconds(text)) * 1_000L
}

/** The `+HH:MM` / `Z` tail of [parseMalTime]'s format, in seconds east of UTC. */
private fun offsetSeconds(text: String): Long {
    if (text.length <= 19) return 0
    val rest = text.substring(19)
    if (rest.isEmpty() || rest[0] == 'Z') return 0
    val sign = if (rest[0] == '-') -1L else 1L
    val hours = rest.drop(1).take(2).toLongOrNull() ?: return 0
    val minutes = rest.drop(4).take(2).toLongOrNull() ?: 0
    return sign * (hours * 3_600L + minutes * 60L)
}

@Serializable
internal data class MalListStatus(
    val status: String? = null,
    @SerialName("num_chapters_read") val numChaptersRead: Int? = null,
    /** When the account last touched this row — i.e. last read it. */
    @SerialName("updated_at") val updatedAt: String? = null,
)
