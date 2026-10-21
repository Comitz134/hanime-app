package com.nuvio.app.features.anilist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.manga.MANGA_SERVER
import com.nuvio.app.features.manga.MalImportResult
import com.nuvio.app.features.manga.MangaApi
import com.nuvio.app.features.manga.MangaMal
import com.nuvio.app.features.manga.MangaCard
import com.nuvio.app.features.manga.MangaPosition
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceLocal
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * AniList, for both halves of the tracker.
 *
 * The account is linked once, in the system browser, and the link is the same
 * shape as MyAnimeList's: the consent page redirects to this server's own root
 * on loopback, the app polls for the code the browser dropped there, and the
 * exchange — the one call that wants the registration's secret — happens in
 * `Anilist.java` on the way through. What comes back is a token that lasts a
 * year; AniList has no refresh token for the code flow, so an expiry is a
 * re-link rather than a silent renewal.
 *
 * Everything else is one GraphQL endpoint. The app does not talk to it
 * directly: the call goes through the same in-process server the rest of this
 * fork uses (`/api/anilist/graphql`), which is where the bearer header is
 * added and where a phone's flaky DNS is somebody else's problem.
 *
 * What the account is used for:
 *
 *   * the anime list is imported into the **Library** — each row is a series
 *     the app can play, keyed by AniList's own id (`ha:an:<id>`), which is the
 *     id the bridge addon calls the same title — and the last episode watched
 *     becomes a progress row, with the next one resumable in Continue
 *     Watching;
 *   * the manga list is imported onto the fork's manga shelf, matched to the
 *     catalogue by name, with the chapter read becoming a reading position;
 *   * watching an episode and opening a chapter are written back, never
 *     backwards.
 */
internal const val ANILIST_CLIENT_ID = "54045"

/** The redirect registered on the SEKAI application, and this server's root. */
internal const val ANILIST_REDIRECT_URI = "http://localhost:8787/"

private const val ANILIST_AUTHORIZE = "https://anilist.co/api/v2/oauth/authorize"

/** Distinct from MAL's, so the landing page can tell which provider answered. */
private const val ANILIST_STATE_PREFIX = "anilist-"

private const val SIGN_IN_TIMEOUT_MS = 5 * 60_000L

/** A token with no expiry is treated as valid for this long. */
private const val DEFAULT_TOKEN_MS = 365L * 24 * 60 * 60 * 1000

/** How long a title's AniList row is trusted before it is read again. */
private const val ENTRY_STALE_MS = 60_000L

/** The one episode length assumed when AniList knows only a count. */
private const val EPISODE_MS = 24 * 60_000L

/** Rows whose title→media id resolution is remembered between runs. */
private const val RESOLUTIONS_KEPT = 1_500

/** Search rows considered for a title, and unmatched names shown in a summary. */
private const val SEARCH_ROWS = 6
private const val UNMATCHED_NAMED = 8

/** Rows one reading-list import walks before it stops. */
private const val IMPORT_LIMIT = 120

/** Edition words the catalogue adds to a title, ignored when matching a row. */
private val CATALOGUE_NOISE = listOf("official", "colored", "coloured", "full color", "webtoon")

private val anilistJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

/** Where the linked account lives; the Android actual is SharedPreferences. */
internal expect object AniListStorage {
    fun load(): String?
    fun save(payload: String)
    fun clear()
}

/** What the Tracking card and any title row draw. */
internal data class AniListAuthState(
    val connected: Boolean = false,
    val username: String = "",
    val pendingState: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** One row of the account's list, as this app needs it. */
internal data class AniListRow(
    val mediaId: Int = 0,
    val title: String = "",
    val english: String = "",
    val synonyms: List<String> = emptyList(),
    val read: Int = 0,
    val total: Int = 0,
    val status: String = "",
    val updatedAt: Long = 0L,
    val cover: String = "",
    val banner: String = "",
    val year: Int = 0,
)

/** What one anime import run did, for the card's one-line summary. */
internal data class AniListAnimeResult(
    val imported: Int = 0,
    val skipped: Int = 0,
    val seeded: Int = 0,
    val failed: String? = null,
)

internal object AniList {

    private val http = createApiHttpClient()
    private val writeLock = Mutex()

    private var session: AniListSession? = null
    private var loaded = false

    internal var authState by mutableStateOf(AniListAuthState())
        private set

    /** The username the card shows once the viewer is known. */
    fun ensureLoaded() {
        if (!loaded) {
            loaded = true
            val payload = runCatching { AniListStorage.load() }.getOrNull()
            val parsed = payload?.let { text ->
                runCatching { anilistJson.decodeFromString<AniListSession>(text) }.getOrNull()
            }
            if (parsed != null && parsed.token.isNotBlank() && parsed.expiresAt > now()) {
                session = parsed
                authState = AniListAuthState(connected = true, username = parsed.username)
            }
            return
        }
        val current = session
        if (current != null && !authState.connected && current.expiresAt > now()) {
            authState = AniListAuthState(connected = true, username = current.username)
        }
    }

    // ------------------------------------------------------------- sign-in

    private fun randomState(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val out = StringBuilder(32)
        repeat(32) { out.append(alphabet[kotlin.random.Random.nextInt(alphabet.length)]) }
        return out.toString()
    }

    private fun authorizeUrl(state: String): String =
        "$ANILIST_AUTHORIZE?client_id=$ANILIST_CLIENT_ID" +
            "&response_type=code" +
            "&redirect_uri=${encode(ANILIST_REDIRECT_URI)}" +
            "&state=${encode(state)}"

    /**
     * Start a login: mint the state, publish it so the card can poll, and hand
     * back the URL the browser should open. Nothing is stored until AniList has
     * answered with a code.
     */
    fun beginSignIn(): String {
        ensureLoaded()
        val state = ANILIST_STATE_PREFIX + randomState()
        authState = authState.copy(pendingState = state, error = null, busy = false)
        return authorizeUrl(state)
    }

    fun cancelSignIn() {
        authState = authState.copy(pendingState = null, busy = false)
    }

    fun failSignIn(message: String) {
        authState = authState.copy(pendingState = null, busy = false, error = message)
    }

    /**
     * Wait for the browser's half of the login — the person may sit on AniList's
     * consent page for a while, so this polls rather than blocking.
     */
    suspend fun awaitSignIn() {
        val state = authState.pendingState ?: return
        val deadline = now() + SIGN_IN_TIMEOUT_MS
        while (now() < deadline) {
            val code = runCatching { pollCode(state) }.getOrNull()
            if (code != null) {
                authState = authState.copy(busy = true)
                runCatching { exchangeCode(code) }
                    .onSuccess {
                        authState = AniListAuthState(
                            connected = true,
                            username = session?.username.orEmpty(),
                        )
                    }
                    .onFailure { failSignIn(it.message ?: "AniList did not accept the sign-in.") }
                return
            }
            delay(1_500)
        }
        failSignIn("The sign-in timed out. Try again.")
    }

    suspend fun disconnect() {
        session = null
        runCatching { AniListStorage.clear() }
        authState = AniListAuthState()
    }

    private suspend fun pollCode(state: String): String? {
        val response = http.get("$MANGA_SERVER/anilist/callback") {
            parameter("state", state)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        if (response.status.value !in 200..299) return null
        val body = response.bodyAsText()
        return runCatching { anilistJson.decodeFromString<CodeResponse>(body).code }.getOrNull()
    }

    private suspend fun exchangeCode(code: String) {
        val response = http.post(
            "$MANGA_SERVER/api/anilist/token?code=${encode(code)}" +
                "&redirect_uri=${encode(ANILIST_REDIRECT_URI)}",
        ) {
            header("accept", "application/json")
        }
        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw IllegalStateException(
                runCatching { anilistJson.decodeFromString<ErrorResponse>(body).message }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "AniList refused the sign-in (${response.status.value}).",
            )
        }
        val parsed = runCatching { anilistJson.decodeFromString<TokenResponse>(body) }.getOrNull()
            ?: throw IllegalStateException("AniList's answer was unreadable.")
        val token = parsed.accessToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("AniList sent no access token.")
        val expires = parsed.expiresIn ?: (DEFAULT_TOKEN_MS / 1000)
        val current = session ?: AniListSession()
        val updated = current.copy(
            token = token,
            expiresAt = now() + expires.coerceAtLeast(60L) * 1000L,
        )
        session = updated
        persist(updated)
        // Who the account is, so the card can say so. A miss here is not a
        // failed link: the token is what was asked for.
        val viewer = runCatching { viewer() }.getOrNull()
        if (viewer != null) {
            val named = updated.copy(viewerId = viewer.first, username = viewer.second)
            session = named
            persist(named)
            authState = AniListAuthState(connected = true, username = viewer.second)
        }
    }

    private fun persist(value: AniListSession) {
        runCatching { AniListStorage.save(anilistJson.encodeToString(AniListSession.serializer(), value)) }
    }

    private suspend fun viewer(): Pair<Int, String>? {
        val data = graphql(
            "query { Viewer { id name } }",
        ).getOrNull()?.get("Viewer")?.jsonObject ?: return null
        val id = data["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null
        val name = data["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return id to name
    }

    // -------------------------------------------------------------- graphql

    /**
     * One query or mutation, with the account's token.
     *
     * AniList answers a refused document with `errors` and a 200 or a 400;
     * both are turned into an exception carrying AniList's own message, because
     * "it did not work" is not something a reader can act on.
     */
    private suspend fun graphql(query: String, variables: JsonObject? = null): Result<JsonObject> {
        ensureLoaded()
        val token = session?.token?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("Connect AniList first."))
        if ((session?.expiresAt ?: 0L) <= now()) {
            return Result.failure(
                IllegalStateException("The AniList link has expired. Connect it again."),
            )
        }
        val payload = buildJsonObject {
            put("query", query)
            if (variables != null) put("variables", variables)
        }.toString()

        val response = runCatching {
            http.post("$MANGA_SERVER/api/anilist/graphql") {
                header("authorization", "Bearer $token")
                header("accept", "application/json")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }.getOrElse { failure ->
            return Result.failure(
                IllegalStateException(failure.message ?: "AniList did not answer."),
            )
        }
        val body = runCatching { response.bodyAsText() }.getOrElse { return Result.failure(it) }
        if (response.status.value == 401) {
            return Result.failure(IllegalStateException("The AniList link has expired. Connect it again."))
        }
        val parsed = runCatching { anilistJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return Result.failure(IllegalStateException("AniList's answer was unreadable."))
        val errors = parsed["errors"]?.jsonArray
        if (errors != null && errors.isNotEmpty()) {
            val message = errors.firstOrNull()?.jsonObject?.get("message")
                ?.jsonPrimitive?.contentOrNull
            return Result.failure(IllegalStateException(message ?: "AniList refused that request."))
        }
        val data = parsed["data"]?.jsonObject
            ?: return Result.failure(IllegalStateException("AniList sent no data."))
        return Result.success(data)
    }

    // --------------------------------------------------------------- lists

    private val listQuery = """
        query LiveList(${'$'}userId: Int, ${'$'}type: MediaType) {
          MediaListCollection(userId: ${'$'}userId, type: ${'$'}type, sort: [UPDATED_TIME_DESC]) {
            lists {
              entries {
                mediaId
                status
                progress
                updatedAt
                media {
                  id
                  episodes
                  chapters
                  seasonYear
                  title { romaji english native }
                  synonyms
                  coverImage { extraLarge large }
                  bannerImage
                }
              }
            }
          }
        }
    """.trimIndent()

    /**
     * The account's whole list of one type.
     *
     * AniList answers a collection in one document — no paging to walk and no
     * offset to lose — and the entries arrive grouped by status. Flattening the
     * groups and sorting by `updatedAt` is what makes "the order I last read or
     * watched" true across them, which is the order the shelf and the Library
     * are stamped with.
     */
    suspend fun list(type: String): List<AniListRow> {
        ensureLoaded()
        val userId = session?.viewerId?.takeIf { it > 0 } ?: run {
            val viewer = viewer() ?: throw IllegalStateException("AniList did not say who is linked.")
            val updated = (session ?: AniListSession()).copy(viewerId = viewer.first, username = viewer.second)
            session = updated
            persist(updated)
            viewer.first
        }
        val variables = buildJsonObject {
            put("userId", userId)
            put("type", type)
        }
        val data = graphql(listQuery, variables).getOrThrow()
        val lists = data["MediaListCollection"]?.jsonObject?.get("lists")?.jsonArray
            ?: return emptyList()
        val rows = ArrayList<AniListRow>()
        for (group in lists) {
            val entries = group.jsonObject["entries"]?.jsonArray ?: continue
            for (entry in entries) {
                val row = entry.jsonObject
                val media = row["media"]?.jsonObject ?: continue
                val id = row["mediaId"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: media["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: continue
                val title = media["title"]?.jsonObject
                val romaji = title?.get("romaji")?.jsonPrimitive?.contentOrNull.orEmpty()
                val english = title?.get("english")?.jsonPrimitive?.contentOrNull.orEmpty()
                val native = title?.get("native")?.jsonPrimitive?.contentOrNull.orEmpty()
                val synonyms = media["synonyms"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                rows += AniListRow(
                    mediaId = id,
                    title = romaji.ifBlank { english }.ifBlank { native },
                    english = english,
                    synonyms = synonyms,
                    read = row["progress"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    total = (media[if (type == "ANIME") "episodes" else "chapters"]
                        ?.jsonPrimitive?.contentOrNull)?.toIntOrNull() ?: 0,
                    status = row["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    updatedAt = row["updatedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                        ?.times(1000L) ?: 0L,
                    cover = media["coverImage"]?.jsonObject?.get("extraLarge")
                        ?.jsonPrimitive?.contentOrNull
                        ?: media["coverImage"]?.jsonObject?.get("large")
                            ?.jsonPrimitive?.contentOrNull.orEmpty(),
                    banner = media["bannerImage"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    year = media["seasonYear"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                )
            }
        }
        return rows.sortedByDescending { it.updatedAt }
    }

    // ------------------------------------------------------------- imports

    /**
     * The anime list, brought onto this device.
     *
     * AniList's ids are the app's ids — `ha:an:<id>` is the bridge addon's own
     * name for the same title — so this import resolves nothing and cannot
     * mismatch: a row is the series the catalogue plays. Each title goes onto
     * the Library, and the last episode AniList says was watched becomes a
     * completed row with the next episode resumable, which is what puts the
     * series in Continue Watching.
     *
     * A title this device already has progress for is left alone: an import
     * seeds a fresh install, it does not race the reader.
     */
    suspend fun importAnime(
        onProgress: (done: Int, total: Int, imported: Int) -> Unit = { _, _, _ -> },
    ): AniListAnimeResult {
        ensureLoaded()
        if (session == null) return AniListAnimeResult(failed = "Connect AniList first.")

        val rows = try {
            list("ANIME")
        } catch (e: Exception) {
            return AniListAnimeResult(failed = e.message ?: "AniList did not answer.")
        }

        var imported = 0
        var skipped = 0
        var seeded = 0
        val knownLibraryIds = LibraryRepository.uiState.value.items.mapTo(HashSet()) { it.id }
        val knownProgressIds = WatchProgressRepository.uiState.value.entries
            .mapTo(HashSet()) { it.parentMetaId }
        val added = HashSet<String>()
        val seededIds = HashSet<String>()
        val progress = ArrayList<WatchProgressEntry>()
        val base = now()

        for ((index, row) in rows.withIndex()) {
            if (row.mediaId <= 0 || row.title.isBlank()) continue
            val metaId = "ha:an:${row.mediaId}"
            val stamp = row.updatedAt.takeIf { it > 0L } ?: (base - index.toLong())

            if (knownLibraryIds.contains(metaId) || !added.add(metaId)) {
                skipped++
            } else {
                runCatching {
                    LibraryRepository.toggleLocalSaved(
                        LibraryItem(
                            id = metaId,
                            type = "series",
                            name = row.title,
                            poster = row.cover.takeIf { it.isNotBlank() },
                            banner = row.banner.takeIf { it.isNotBlank() },
                            releaseInfo = row.year.takeIf { it > 0 }?.toString(),
                            savedAtEpochMs = stamp,
                        ),
                    )
                }.onSuccess { imported++ }
            }

            val watched = row.read
            if (watched > 0 && !knownProgressIds.contains(metaId) && seededIds.add(metaId)) {
                val finished = row.status == "COMPLETED" || (row.total > 0 && watched >= row.total)
                progress += animeProgressRow(row, metaId, watched, completed = true, at = stamp)
                if (!finished) {
                    progress += animeProgressRow(row, metaId, watched + 1, completed = false, at = stamp + 1)
                }
                seeded++
            }
            onProgress(index + 1, rows.size, imported)
        }

        if (progress.isNotEmpty()) {
            runCatching { WatchProgressRepository.applyExternalProgress(progress) }
        }
        return AniListAnimeResult(imported = imported, skipped = skipped, seeded = seeded)
    }

    private fun animeProgressRow(
        row: AniListRow,
        metaId: String,
        episode: Int,
        completed: Boolean,
        at: Long,
    ): WatchProgressEntry = WatchProgressEntry(
        contentType = "series",
        parentMetaId = metaId,
        parentMetaType = "series",
        videoId = "$metaId:1:$episode",
        title = row.title,
        poster = row.cover.takeIf { it.isNotBlank() },
        background = row.banner.takeIf { it.isNotBlank() },
        seasonNumber = 1,
        episodeNumber = episode,
        lastPositionMs = if (completed) EPISODE_MS else 1L,
        durationMs = EPISODE_MS,
        lastUpdatedEpochMs = at,
        isCompleted = completed,
        progressPercent = if (completed) 100f else null,
        source = WatchProgressSourceLocal,
    )

    /**
     * The reading list, onto the fork's manga shelf.
     *
     * AniList's manga ids are not mangafire's, so a row has to be matched by
     * name — the same conservative rule the MyAnimeList import follows: a
     * title is taken when the catalogue names it the same after normalisation,
     * or when the only difference is an edition word ("Official", "Colored").
     * A wrong match would put someone else's chapters on this list, which is
     * worse than a title that stays off it.
     *
     * The shelf gets the title, stamped with AniList's own `updatedAt`, so it
     * is ordered by when each row was last read rather than by the order this
     * run happened to walk the list. The position is written only forward: an
     * import must never walk a reader backwards.
     */
    suspend fun importManga(
        limit: Int = IMPORT_LIMIT,
        onProgress: (done: Int, total: Int, imported: Int) -> Unit = { _, _, _ -> },
    ): MalImportResult {
        ensureLoaded()
        if (session == null) return MalImportResult(failed = "Connect AniList first.")

        val rows = try {
            list("MANGA")
        } catch (e: Exception) {
            return MalImportResult(failed = e.message ?: "AniList did not answer.")
        }
        if (rows.isEmpty()) return MalImportResult()

        var imported = 0
        var skipped = 0
        var unresolved = 0
        var examined = 0

        // The shelf and the positions as they stand, so a second run is cheap
        // and cannot duplicate what the first one wrote.
        val shelf = runCatching { MangaApi.library() }.getOrDefault(emptyList())
        val onShelf = shelf.mapTo(HashSet()) { it.id }
        val existing = runCatching { MangaApi.positions() }.getOrDefault(emptyList())
            .associateBy { normalize(it.title) }
        val base = now()

        for ((index, row) in rows.withIndex()) {
            if (examined >= limit) break
            examined++
            val card = matchCard(row)
            if (card == null) {
                unresolved++
                onProgress(examined, minOf(rows.size, limit), imported)
                continue
            }
            if (onShelf.contains(card.id)) {
                skipped++
            } else {
                val stamp = row.updatedAt.takeIf { it > 0L } ?: (base - index.toLong())
                runCatching { MangaApi.addToLibrary(card, at = stamp) }
                    .onSuccess { imported++ }
            }
            if (row.read > 0) {
                val already = existing[normalize(card.title)]
                if (already == null || (already.number.toFloatOrNull() ?: 0f) < row.read) {
                    runCatching { writePosition(card, row.read) }
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

    /** The catalogue's answer to one AniList row, or null when none is close. */
    private suspend fun matchCard(row: AniListRow): MangaCard? {
        val names = buildList {
            add(row.title)
            if (row.english.isNotBlank() && row.english != row.title) add(row.english)
            addAll(row.synonyms)
        }.map(::normalize).filter { it.length >= 3 }.toSet()
        if (names.isEmpty()) return null
        val query = row.title.ifBlank { row.english }
        if (query.isBlank()) return null
        val cards = runCatching { MangaApi.search(query = query).items }
            .getOrDefault(emptyList())
        if (cards.isEmpty()) return null

        // Exact after normalisation wins outright; the edition words the
        // catalogue appends are the only difference allowed past that.
        for (card in cards) {
            if (names.contains(normalize(card.title))) return card
        }
        for (card in cards) {
            if (names.contains(normalizeCatalogue(card.title))) return card
        }
        return null
    }

    // ------------------------------------------------------------ two-way

    /**
     * What the linked accounts say about one title, pulled onto this device.
     *
     * The project's rule is that a tracker is told what was read here and never
     * the other way round *during* a read — but a title opened after months
     * away is a different question: the account may know a chapter this device
     * has never seen, because it was read on the website, on another phone, or
     * by an import that only half finished. So opening a title asks, and moves
     * the reading position forward when the answer is ahead of the shelf.
     *
     * The order is MAL first, AniList second, and it matters: where both
     * accounts are linked, MAL is the number this device follows, and AniList
     * is only asked when MAL does not know the title at all. Nothing is ever
     * moved backwards, and a title the account has read is put on the shelf if
     * it is missing — a row the account knows about is a row this shelf should
     * hold.
     *
     * Returns the position it wrote, or null when there was nothing to do.
     */
    suspend fun syncProgress(card: MangaCard, malId: Long = 0L): MangaPosition? {
        if (card.id.isBlank() || card.title.isBlank()) return null
        ensureLoaded()

        // By id when the shelf knows it, by name only when it does not: a title
        // whose catalogue name differs from the account's is exactly the one a
        // name search loses, and it is the row a reader complains about.
        val malRead = runCatching {
            if (malId > 0L) MangaMal.entry(malId)?.read ?: 0 else MangaMal.entry(card.title)?.read ?: 0
        }.getOrDefault(0)
        val anilistRead = if (malRead > 0) {
            0
        } else {
            runCatching { remoteRead(card.title) }.getOrDefault(0)
        }
        val remote = maxOf(malRead, anilistRead)
        if (remote <= 0) return null

        val local = runCatching { MangaApi.positions() }.getOrDefault(emptyList())
            .firstOrNull { normalize(it.title) == normalize(card.title) }
        val read = local?.number?.toFloatOrNull() ?: 0f
        if (remote <= read) return null

        val chapters = runCatching { MangaApi.chapters(card.id) }.getOrDefault(emptyList())
        if (chapters.isEmpty()) return null
        val wanted = chapters
            .mapNotNull { chapter ->
                val number = chapter.number.trim().toFloatOrNull()
                if (number == null) null else chapter to number
            }
            .filter { (_, number) -> number <= remote }
            .maxByOrNull { (_, number) -> number }
            ?.first
            ?: return null

        val position = MangaPosition(
            id = card.id,
            title = card.title,
            poster = card.poster,
            chapter = wanted.id,
            number = wanted.number,
            page = 0,
            pages = 0,
        )
        // Quiet: the account knows the chapter, not when it was read. Stamping
        // the shelf now would order a refreshed shelf by the refresh.
        runCatching { MangaApi.savePosition(position, readAt = 0L) }
            .getOrElse { return null }

        val onShelf = runCatching { MangaApi.library().map { it.id } }
            .getOrDefault(emptyList())
            .contains(card.id)
        if (!onShelf) runCatching { MangaApi.addToLibrary(card) }
        return position
    }

    /** How far the account has read a title, or 0 when it does not know it. */
    suspend fun remoteRead(title: String): Int {
        ensureLoaded()
        if (session == null) return 0
        val mediaId = resolveMediaId(title, "MANGA") ?: return 0
        return entry(mediaId)?.read ?: 0
    }

    /** Where the account left a title, written as this app's own position. */
    private suspend fun writePosition(card: MangaCard, read: Int): Boolean {
        val chapters = runCatching { MangaApi.chapters(card.id) }.getOrDefault(emptyList())
        if (chapters.isEmpty()) return false
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

    // -------------------------------------------------------------- writes

    /**
     * Watching an episode tells AniList. The number only moves forward and the
     * status is kept unless the row was still a plan, which is the same rule
     * MyAnimeList is written by — so one account does not contradict the other.
     *
     * A failure is swallowed: playback must never wait on a tracker.
     */
    suspend fun episodeWatched(mediaId: Int, episode: Int, total: Int) {
        if (mediaId <= 0 || episode <= 0) return
        ensureLoaded()
        if (session == null) return
        writeLock.withLock {
            runCatching {
                val current = entry(mediaId) ?: return@runCatching
                if (current.read >= episode) return@runCatching
                val status = when {
                    total > 0 && episode >= total -> "COMPLETED"
                    current.status == "PLANNING" || current.status.isBlank() -> "CURRENT"
                    current.status in setOf("COMPLETED", "DROPPED", "PAUSED") -> current.status
                    else -> "CURRENT"
                }
                save(mediaId, episode, status)
                entries[mediaId] = CachedEntry(
                    AniListEntry(mediaId = mediaId, read = episode, status = status),
                    now(),
                )
            }
        }
    }

    /** Opening a chapter tells AniList, resolved from the catalogue's name. */
    suspend fun chapterRead(title: String, chapterNumber: String) {
        ensureLoaded()
        if (session == null) return
        val number = chapterNumber.trim().substringBefore('.').toIntOrNull() ?: return
        if (number < 1) return
        writeLock.withLock {
            runCatching {
                val mediaId = resolveMediaId(title, "MANGA") ?: return@runCatching
                val current = entry(mediaId) ?: return@runCatching
                if (current.read >= number) return@runCatching
                val status = if (current.status == "PLANNING" || current.status.isBlank()) "CURRENT" else current.status
                save(mediaId, number, status)
                entries[mediaId] = CachedEntry(
                    AniListEntry(mediaId = mediaId, read = number, status = status),
                    now(),
                )
            }
        }
    }

    /** Saying "I want to read this" on a title page, when AniList is linked. */
    suspend fun addToPlan(title: String) {
        ensureLoaded()
        if (session == null) return
        writeLock.withLock {
            runCatching {
                val mediaId = resolveMediaId(title, "MANGA") ?: return@runCatching
                if (entry(mediaId) != null) return@runCatching
                save(mediaId, 0, "PLANNING")
            }
        }
    }

    private val entryQuery = """
        query Entry(${'$'}mediaId: Int) {
          Media(id: ${'$'}mediaId) {
            mediaListEntry { progress status }
          }
        }
    """.trimIndent()

    private suspend fun entry(mediaId: Int): AniListEntry? {
        val data = graphql(
            entryQuery,
            buildJsonObject { put("mediaId", mediaId) },
        ).getOrNull() ?: return null
        val entry = data["Media"]?.jsonObject?.get("mediaListEntry")?.jsonObject ?: return null
        val read = entry["progress"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val status = entry["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return AniListEntry(mediaId = mediaId, read = read, status = status)
    }

    private val saveMutation = """
        mutation Save(${'$'}mediaId: Int, ${'$'}progress: Int, ${'$'}status: MediaListStatus) {
          SaveMediaListEntry(mediaId: ${'$'}mediaId, progress: ${'$'}progress, status: ${'$'}status) {
            id
            progress
            status
          }
        }
    """.trimIndent()

    private suspend fun save(mediaId: Int, progress: Int, status: String) {
        graphql(
            saveMutation,
            buildJsonObject {
                put("mediaId", mediaId)
                put("progress", progress)
                put("status", status)
            },
        ).getOrThrow()
    }

    // --------------------------------------------------------- resolution

    private val searchQuery = """
        query Search(${'$'}search: String, ${'$'}type: MediaType) {
          Page(perPage: 6) {
            media(search: ${'$'}search, type: ${'$'}type) {
              id
              title { romaji english }
              synonyms
            }
          }
        }
    """.trimIndent()

    /**
     * The AniList id of a catalogue title, by name.
     *
     * The catalogue has no AniList id of its own, so this is a search and a
     * name comparison — exact after normalisation, or the row is not written at
     * all. A wrong match would move someone else's entry, which is worse than a
     * chapter that is not tracked.
     */
    private suspend fun resolveMediaId(title: String, type: String): Int? {
        val key = normalize(title)
        if (key.isEmpty()) return null
        session?.mediaIds?.get(key)?.let { return it }
        val data = graphql(
            searchQuery,
            buildJsonObject {
                put("search", title)
                put("type", type)
            },
        ).getOrNull() ?: return null
        val media = data["Page"]?.jsonObject?.get("media")?.jsonArray ?: return null
        var best: Int? = null
        for (item in media) {
            val node = item.jsonObject
            val id = node["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
            val names = buildList {
                node["title"]?.jsonObject?.get("romaji")?.jsonPrimitive?.contentOrNull?.let(::add)
                node["title"]?.jsonObject?.get("english")?.jsonPrimitive?.contentOrNull?.let(::add)
                node["synonyms"]?.jsonArray?.mapNotNullTo(this) { it.jsonPrimitive.contentOrNull }
            }
            if (names.any { normalize(it) == key }) {
                best = id
                break
            }
        }
        val resolved = best ?: return null
        val current = session ?: return resolved
        val trimmed = current.mediaIds.entries.toList().takeLast(RESOLUTIONS_KEPT)
            .associate { it.key to it.value }
        val updated = current.copy(mediaIds = trimmed + (key to resolved))
        session = updated
        persist(updated)
        return resolved
    }

    /** The account's row for one of its titles, read at most once a minute. */
    private val entries = mutableMapOf<Int, CachedEntry>()

    private data class CachedEntry(val entry: AniListEntry, val at: Long)

    private data class AniListEntry(val mediaId: Int, val read: Int, val status: String)

    /** Cached lookups are dropped when the account changes. */
    fun forget() {
        entries.clear()
    }

    /**
     * A scrobble from the player, as an AniList write.
     *
     * The app's own anime ids carry AniList's id in their tail (`ha:an:1234`),
     * so the row is known without a lookup. The event's episode number is what
     * AniList counts.
     */
    suspend fun episodeFromScrobble(contentId: String?, episode: Int?, total: Int?) {
        if (contentId == null || episode == null) return
        val id = anilistIdFrom(contentId) ?: return
        val count = if (episode > 0) episode else return
        episodeWatched(id, count, total ?: 0)
    }

    private fun anilistIdFrom(contentId: String): Int? {
        val marker = contentId.indexOf("an:")
        if (marker < 0) return null
        return contentId.substring(marker + 3)
            .substringBefore(':')
            .trim()
            .toIntOrNull()
            ?.takeIf { it > 0 }
    }

    private fun encode(value: String): String = value.encodeURLParameter()

    /** Letters and digits only, lowercased: the form two names are compared in. */
    private fun normalize(value: String): String = buildString {
        for (char in value.lowercase()) {
            if (char.isLetterOrDigit()) append(char)
        }
    }

    /** Words the catalogue appends to a title that are not part of its name. */
    private fun normalizeCatalogue(value: String): String {
        var stripped = value
        for (word in CATALOGUE_NOISE) stripped = stripped.replace(word, "", ignoreCase = true)
        return normalize(stripped)
    }

    private fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

// --------------------------------------------------------------- the wire

@Serializable
internal data class AniListSession(
    val token: String = "",
    val expiresAt: Long = 0L,
    val viewerId: Int = 0,
    val username: String = "",
    @SerialName("media_ids") val mediaIds: Map<String, Int> = emptyMap(),
)

@Serializable
private data class CodeResponse(val code: String = "")

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

@Serializable
private data class ErrorResponse(
    val message: String? = null,
    val error: String? = null,
)

private fun JsonElement?.orEmpty(): String = this?.jsonPrimitive?.contentOrNull.orEmpty()
