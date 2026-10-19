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
 * A title has no MAL id in mangafire's answer, so the first write resolves one
 * by searching MAL for the title — and remembers it. A miss stays a miss: the
 * app will not attach a title to the wrong entry just to have something to
 * write to.
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

/** One title, as MAL has it: the account's row plus the series' length. */
internal data class MangaMalEntry(
    val malId: Long = 0L,
    val title: String = "",
    val status: String? = null,
    val read: Int = 0,
    val total: Int = 0,
)

/** What one import run did, for the line the Tracking tab draws. */
internal data class MalImportResult(
    val imported: Int = 0,
    val skipped: Int = 0,
    val unresolved: Int = 0,
    val failed: String? = null,
)

private data class CachedEntry(val entry: MangaMalEntry?, val at: Long)

internal object MangaMal {

    private val http = createApiHttpClient()
    private val writeLock = Mutex()
    private val tokenLock = Mutex()
    private val entries = mutableMapOf<String, CachedEntry>()

    private var session: MalSession? = null
    private var pendingVerifier: String? = null

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
    suspend fun entry(title: String, force: Boolean = false): MangaMalEntry? {
        ensureLoaded()
        if (session == null) return null
        val key = normalize(title)
        if (key.isEmpty()) return null
        val cached = entries[key]
        if (!force && cached != null && now() - cached.at < LIST_STALE_MS) return cached.entry
        return writeLock.withLock {
            val fresh = cached
            if (!force && fresh != null && now() - fresh.at < LIST_STALE_MS) return@withLock fresh.entry
            val result = runCatching { fetchEntryForTitle(title) }.getOrNull()
            entries[key] = CachedEntry(result, now())
            result
        }
    }

    /**
     * Opening a chapter tells MAL. The number only moves forward, the status
     * is kept unless it was a plan, and the last chapter completes the title —
     * the same rules the web client writes by, so the two agree.
     *
     * A failure is swallowed: reading must never wait on a tracker.
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
            if (onShelf.contains(card.id)) {
                skipped++
            } else {
                runCatching { MangaApi.addToLibrary(card, at = importStamp(index)) }
                    .onSuccess { imported++ }
            }

            // The shelf is *which* titles; the position is *how far*. Only the
            // rows that have been read have one, and only forward motion is
            // written: an import must not walk a reader backwards.
            val read = status.numChaptersRead ?: 0
            if (read > 0) {
                val already = existing[normalize(title)]
                if (already == null || (already.number.toFloatOrNull() ?: 0f) < read) {
                    runCatching { writePosition(card, read) }
                }
            }
            onProgress(examined, minOf(rows.size, limit), imported)
        }

        return MalImportResult(
            imported = imported,
            skipped = skipped,
            unresolved = unresolved,
        )
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
    private suspend fun writePosition(card: MangaCard, read: Int): Boolean {
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
            throw IllegalStateException("MyAnimeList answered ${response.status.value}.")
        }
        return body
    }

    private suspend fun resolveId(title: String): Long? {
        val current = session ?: return null
        val key = normalize(title)
        current.ids[key]?.let { return it }
        val found = search(title) ?: return null
        val updated = current.copy(ids = current.ids + (key to found))
        session = updated
        persist(updated)
        return found
    }

    /** The best of MAL's answers to a title search, or null rather than a wrong row. */
    private suspend fun search(title: String): Long? {
        val token = token() ?: return null
        val body = apiGet(
            token,
            "manga?q=${encode(title)}&limit=6&fields=title,alternative_titles,num_chapters",
        )
        val rows = runCatching { malJson.decodeFromString<MalSearchResponse>(body) }.getOrNull()
            ?.data
            .orEmpty()
        if (rows.isEmpty()) return null
        val want = normalize(title)
        if (want.isEmpty()) return null
        var best: MalSearchNode? = null
        var bestScore = 0
        for (row in rows) {
            val node = row.node ?: continue
            val id = node.id ?: continue
            val names = buildList {
                node.title?.let(::add)
                node.alternativeTitles?.en?.let(::add)
                node.alternativeTitles?.ja?.let(::add)
                node.alternativeTitles?.synonyms?.forEach(::add)
            }
            for (name in names) {
                val score = titleScore(want, normalize(name))
                if (score > bestScore) {
                    bestScore = score
                    best = node.copy(id = id)
                }
            }
        }
        return if (bestScore > 0) best?.id else null
    }

    /** Exact beats prefix beats containment; a short shared prefix is no match. */
    private fun titleScore(want: String, candidate: String): Int {
        if (want.isEmpty() || candidate.isEmpty()) return 0
        if (want == candidate) return 100
        val short = if (want.length <= candidate.length) want else candidate
        val long = if (short === want) candidate else want
        if (short.length >= 6 && long.startsWith(short)) return 50 + short.length
        if (short.length >= 8 && long.contains(short)) return 20 + short.length
        return 0
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
)

@Serializable
private data class MalTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

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
)

@Serializable
private data class MalAlternativeTitles(
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

@Serializable
internal data class MalListStatus(
    val status: String? = null,
    @SerialName("num_chapters_read") val numChaptersRead: Int? = null,
)
