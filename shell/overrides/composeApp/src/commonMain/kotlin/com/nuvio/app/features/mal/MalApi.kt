package com.nuvio.app.features.mal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.nuvio.app.features.addons.httpRequestRaw

/**
 * MyAnimeList, spoken through this project's own server.
 *
 * MyAnimeList answers no browser origin and the desktop app is not a browser
 * anyway, so every call crosses the local server (`src/mal.mjs`), which adds
 * the registration's secret to the token exchange and forwards the rest with
 * this client's own Bearer token. The same relay already serves the web
 * client and the phone — one contract, three clients.
 *
 * The client id is public by design (it rides in every authorize URL, and the
 * mobile app's BuildConfig carries the same one); the secret never leaves the
 * server's environment.
 */
internal object MalApi {

    /** Where this project's server answers; the same port the window starts. */
    const val RELAY_BASE = "http://127.0.0.1:8787"

    /**
     * Marks a state this app minted, so the redirect origin it shares with the
     * web client can tell a desktop login from the browser's own. The server's
     * mirror is DESKTOP_STATE_PREFIX in src/mal.mjs; the two must agree.
     */
    const val DESKTOP_STATE_PREFIX = "sekai-"

    /** This project's MyAnimeList registration (public identifier). */
    const val CLIENT_ID = "63e08e44f57ef1b4644322939ebd9fa6"

    /**
     * Where MAL sends the browser back. This exact origin is a registered
     * redirect URI on the registration — it is this server's own root, the
     * one address both halves of the login share (the app polls the server
     * for the code the browser dropped there).
     */
    const val REDIRECT_URI = "http://localhost:8787/"

    private const val AUTHORIZE = "https://myanimelist.net/v1/oauth2/authorize"
    private const val TIMEOUT_MS = 15_000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The seam the tests replace, the way the phone's MAL client has one:
     * every MAL-shaped request leaves through here, so a test can record what
     * would have been written without a server or a network in the way.
     */
    internal var transport: suspend (method: String, url: String, headers: Map<String, String>, body: String) ->
        com.nuvio.app.features.addons.RawHttpResponse = { method, url, headers, body ->
        httpRequestRaw(method = method, url = url, headers = headers, body = body)
    }

    class MalApiException(val status: Int, message: String) : Exception(message)

    /**
     * MAL supports only the `plain` PKCE challenge, so the challenge *is* the
     * verifier (43–128 unreserved characters, per RFC 7636 and MAL's own
     * error text).
     */
    fun randomVerifier(length: Int = 64): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val out = StringBuilder(length)
        val random = kotlin.random.Random.Default
        repeat(length) { out.append(alphabet[random.nextInt(alphabet.length)]) }
        return out.toString()
    }

    /** The URL the card opens in the system browser. */
    fun authorizeUrl(state: String, verifier: String): String =
        "$AUTHORIZE?response_type=code" +
            "&client_id=$CLIENT_ID" +
            "&redirect_uri=${encodeQuery(REDIRECT_URI)}" +
            "&code_challenge=$verifier" +
            "&code_challenge_method=plain" +
            "&state=${encodeQuery(state)}"

    private fun encodeQuery(value: String): String =
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

    /**
     * The code the browser dropped at the server's door, collected by the
     * state this app minted. `null` means "not there yet" — the person may
     * still be reading MAL's consent page — which is why a 404 from the relay
     * is an answer rather than an error.
     */
    suspend fun pendingCode(state: String): String? {
        val res = transport(
            "GET",
            "$RELAY_BASE/api/mal/desktop-callback?state=${encodeQuery(state)}",
            emptyMap(),
            "",
        )
        if (res.status == 404) return null
        if (res.status != 200) throw MalApiException(res.status, "desktop-callback ${res.status}")
        return runCatching { json.decodeFromString<MalDesktopCode>(res.body).code }.getOrNull()
    }

    /** The authorization-code exchange; the server adds the client secret. */
    suspend fun exchangeCode(code: String, verifier: String): MalTokens {
        val body = tokenRequest(
            "grant_type=authorization_code" +
                "&code=${encodeQuery(code)}" +
                "&code_verifier=${encodeQuery(verifier)}" +
                "&redirect_uri=${encodeQuery(REDIRECT_URI)}",
        )
        return parseTokens(body)
    }

    /**
     * A refresh. MAL revokes the old refresh token the moment a new one is
     * issued, so two parallel refreshes race each other into a dead session —
     * the caller serialises them (see MalTracker's refresh lock).
     */
    suspend fun refresh(refreshToken: String): MalTokens =
        parseTokens(tokenRequest("grant_type=refresh_token&refresh_token=${encodeQuery(refreshToken)}"))

    private suspend fun tokenRequest(query: String): String {
        val res = transport(
            "POST",
            "$RELAY_BASE/api/mal/token?client_id=$CLIENT_ID&$query",
            mapOf("accept" to "application/json"),
            "",
        )
        if (res.status !in 200..299) {
            throw MalApiException(res.status, "token ${res.status}: ${res.body.take(200)}")
        }
        return res.body
    }

    private fun parseTokens(body: String): MalTokens {
        val parsed = runCatching { json.decodeFromString<MalTokenResponse>(body) }.getOrNull()
            ?: throw MalApiException(500, "token response unreadable")
        val access = parsed.accessToken?.takeIf(String::isNotBlank)
            ?: throw MalApiException(500, "token response has no access token")
        val refresh = parsed.refreshToken?.takeIf(String::isNotBlank)
        // MAL's docs disagree about the lifetime (an hour in prose, ~28 days
        // in the example); the value the endpoint actually sent is the truth,
        // and a missing one falls back to the shorter of the two readings.
        val expiresInSeconds = parsed.expiresIn ?: 3600L
        return MalTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMs = epochMs() + expiresInSeconds.coerceAtLeast(60L) * 1000L,
        )
    }

    /** Who the token belongs to — the name the settings card shows. */
    suspend fun fetchUsername(accessToken: String): String? {
        val res = apiGet(accessToken, "users/@me?fields=name")
        return runCatching { json.decodeFromString<MalMeResponse>(res).name }.getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    /**
     * The whole list, every page of it. One entry carries everything the
     * library projection needs — title, poster, episode counts and the
     * status — so a second call per title is never needed.
     */
    suspend fun fetchAnimeList(accessToken: String): List<MalListEntry> {
        val fields = "title,main_picture,num_episodes," +
            "list_status{status,score,num_episodes_watched,is_rewatching,updated_at}"
        val out = mutableListOf<MalListEntry>()
        var offset = 0
        while (offset < 10_000) {
            val res = apiGet(
                accessToken,
                "users/@me/animelist?limit=1000&offset=$offset&nsfw=false&fields=${encodeQuery(fields)}",
            )
            val page = runCatching { json.decodeFromString<MalAnimeListResponse>(res) }.getOrNull()
                ?: break
            out += page.data.orEmpty().mapNotNull(::entryOf)
            if (page.data.isNullOrEmpty() || page.paging?.next == null) break
            offset += 1000
        }
        return out
    }

    /**
     * One title's own numbers: how many episodes it has, and where this
     * account's list holds it.
     *
     * MAL does not move a title to Completed because a count reached its
     * total — the status is a field somebody has to write — so the total is
     * the only honest answer to "was that the last one?", and the list status
     * is what tells a write whether the row is already where the reader left
     * it. Both readings come back from one call, made before the write.
     *
     * Read through the media route, never `/my_list_status`: that path takes
     * writes only (a GET on it is `405 method_not_allowed`), which is the same
     * fact the web client's own read of a title is built around. A title this
     * account's list does not hold simply arrives without `my_list_status`,
     * and a series with no known total answers `num_episodes: 0` — which is
     * why zero reads as "no total" rather than as a very short show.
     */
    suspend fun fetchTitleStatus(accessToken: String, malId: Long): MalTitleStatus {
        val fields = "num_episodes,my_list_status{status,num_episodes_watched}"
        val body = apiGet(accessToken, "anime/$malId?fields=${encodeQuery(fields)}")
        val parsed = runCatching { json.decodeFromString<MalTitleResponse>(body) }.getOrNull()
            ?: throw MalApiException(500, "title response unreadable")
        return MalTitleStatus(
            episodesTotal = parsed.numEpisodes ?: 0,
            status = parsed.listStatus?.status,
        )
    }

    /**
     * Write one row of the list: which status it is in and how many episodes
     * have been watched. MAL writes only the fields it is given, and it does
     * **not** complete a title by itself when the count reaches the total —
     * that status travels like any other field, or not at all (see the
     * tracker's own `statusForWrite`).
     */
    suspend fun updateListStatus(
        accessToken: String,
        malId: Long,
        status: String?,
        watchedEpisodes: Int?,
    ) {
        val query = buildString {
            status?.let { append("status=${encodeQuery(it)}") }
            if (watchedEpisodes != null) {
                if (isNotEmpty()) append('&')
                append("num_watched_episodes=$watchedEpisodes")
            }
        }
        if (query.isEmpty()) return
        val res = transport(
            "PUT",
            "$RELAY_BASE/api/mal/v2/anime/$malId/my_list_status?$query",
            mapOf("authorization" to "Bearer $accessToken", "accept" to "application/json"),
            "",
        )
        if (res.status !in 200..299) {
            throw MalApiException(res.status, "list status ${res.status}: ${res.body.take(200)}")
        }
    }

    /** Take a title off the list entirely (its score and progress with it). */
    suspend fun deleteListStatus(accessToken: String, malId: Long) {
        val res = transport(
            "DELETE",
            "$RELAY_BASE/api/mal/v2/anime/$malId/my_list_status",
            mapOf("authorization" to "Bearer $accessToken"),
            "",
        )
        if (res.status !in 200..299 && res.status != 404) {
            throw MalApiException(res.status, "list removal ${res.status}: ${res.body.take(200)}")
        }
    }

    private suspend fun apiGet(accessToken: String, rest: String): String {
        val res = transport(
            "GET",
            "$RELAY_BASE/api/mal/v2/$rest",
            mapOf("authorization" to "Bearer $accessToken", "accept" to "application/json"),
            "",
        )
        if (res.status !in 200..299) {
            throw MalApiException(res.status, "mal api ${res.status}: ${res.body.take(200)}")
        }
        return res.body
    }

    private fun entryOf(node: MalAnimeListNode): MalListEntry? {
        val anime = node.node ?: return null
        val id = anime.id ?: return null
        val status = node.listStatus?.status ?: return null
        return MalListEntry(
            malId = id,
            title = anime.title?.takeIf(String::isNotBlank) ?: "Anime $id",
            poster = anime.mainPicture?.large ?: anime.mainPicture?.medium,
            episodesTotal = anime.numEpisodes ?: 0,
            watchedEpisodes = node.listStatus.numEpisodesWatched ?: 0,
            status = status,
            score = node.listStatus.score ?: 0,
            isRewatching = node.listStatus.isRewatching == true,
            updatedAtEpochMs = node.listStatus.updatedAt?.let(::epochSecondsOrNull) ?: 0L,
        )
    }

    /** MAL stamps list rows in epoch seconds; the app speaks milliseconds. */
    private fun epochSecondsOrNull(value: String?): Long? =
        value?.toLongOrNull()?.let { seconds -> seconds * 1000L }
}

internal fun epochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

// ------------------------------------------------------------------ models

@Serializable
internal data class MalTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long = 0L,
)

@Serializable
internal data class MalListEntry(
    val malId: Long,
    val title: String,
    val poster: String? = null,
    val episodesTotal: Int = 0,
    val watchedEpisodes: Int = 0,
    val status: String,
    val score: Int = 0,
    val isRewatching: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
) {
    /** `mal:21` — the id space this app's addon answers for anime titles. */
    val contentId: String get() = "mal:$malId"

    /**
     * A row that is finished but still says Watching.
     *
     * That is the shape a title takes when it was watched to its end *before*
     * this app learned to write `completed` on the last episode (see
     * MalTracker's own `statusForWrite`): MAL keeps a status and a count in
     * separate fields and derives neither from the other, so the count reached
     * the total while the status stayed where it was. Nothing in the app went
     * back for those rows, because the completion rule only decides what a
     * *new* write carries — which is what [MalLibrary] settles on a read.
     *
     * A total of zero is MAL saying it knows of none (an ongoing series
     * answers `0`, not null), so an episode cannot be the last of it; and a row
     * being re-watched is the reader's own standing order, not a stale one.
     */
    val isFinishedButWatching: Boolean
        get() = status == MalListStatus.WATCHING &&
            episodesTotal > 0 &&
            watchedEpisodes >= episodesTotal &&
            !isRewatching
}

/**
 * One title as MAL describes it: its episode total and the reader's own row on
 * it. Zero episodes means MAL knows no total — an ongoing series answers `0`,
 * not null — so anything deciding "was that the last one?" reads zero as
 * "no total to reach".
 */
internal data class MalTitleStatus(
    val episodesTotal: Int = 0,
    val status: String? = null,
)

/** MyAnimeList's own status spellings, which are also this app's tab keys. */
internal object MalListStatus {
    const val WATCHING = "watching"
    const val COMPLETED = "completed"
    const val ON_HOLD = "on_hold"
    const val DROPPED = "dropped"
    const val PLAN_TO_WATCH = "plan_to_watch"

    /** Tab key -> MAL's spelling; the tab key carries the provider. */
    val ALL = listOf(WATCHING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_WATCH)

    fun tabKey(status: String): String = "mal:$status"
}

@Serializable
private data class MalTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

@Serializable
internal data class MalDesktopCode(val code: String? = null, val state: String? = null)

@Serializable
private data class MalMeResponse(val name: String? = null)

@Serializable
private data class MalAnimeListResponse(
    val data: List<MalAnimeListNode>? = null,
    val paging: MalPaging? = null,
)

@Serializable
private data class MalPaging(val next: String? = null)

@Serializable
private data class MalAnimeListNode(
    val node: MalAnimeNode? = null,
    @SerialName("list_status") val listStatus: MalListStatusPayload? = null,
)

@Serializable
private data class MalAnimeNode(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("num_episodes") val numEpisodes: Int? = null,
    @SerialName("main_picture") val mainPicture: MalPicture? = null,
)

/** One title asked for by its own id: its total, and this account's row on it. */
@Serializable
private data class MalTitleResponse(
    @SerialName("num_episodes") val numEpisodes: Int? = null,
    @SerialName("my_list_status") val listStatus: MalListStatusPayload? = null,
)

@Serializable
internal data class MalPicture(val medium: String? = null, val large: String? = null)

@Serializable
private data class MalListStatusPayload(
    val status: String? = null,
    val score: Int? = null,
    // MAL's *reading* spelling. Writing uses `num_watched_episodes` (see
    // updateListStatus) and reading uses `num_episodes_watched` — the two
    // differ, and asking for the write spelling on a read is answered with
    // silence: read back from the live API, every row carried
    // `num_episodes_watched`, so this field was always null and every shelf row
    // drew "0 / 12 ep" no matter how much of the show had been watched.
    @SerialName("num_episodes_watched") val numEpisodesWatched: Int? = null,
    // MAL's own flag for "the reader put this back on Watching themselves".
    // Read so that a deliberate re-watch is never mistaken for a stale row.
    @SerialName("is_rewatching") val isRewatching: Boolean? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
