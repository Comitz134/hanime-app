package com.nuvio.app.features.mal

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingProfileStore
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.TrackingScrobbler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * MyAnimeList as a tracking provider: the account, the list, and the writes
 * that keep MAL's episode counts in step with what is played here.
 *
 * The login is a browser leg this window cannot watch: it opens the system
 * browser at MAL's authorize URL, MAL redirects to the one origin both halves
 * share (this project's own server, a registered redirect URI), and the
 * server holds the code until this app collects it by state. Everything after
 * that — the exchange, the refresh, the list — travels through the same
 * server relay the web client and the phone already use (src/mal.mjs), so the
 * registration's secret stays in the server's environment and the tokens stay
 * here, in this app's own storage.
 */
object MalTracker : TrackingAuthProvider, TrackingProfileStore, TrackingScrobbler {

    private val log = Logger.withTag("MalTracker")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private val activeProfile = MutableStateFlow(ProfileRepository.activeProfileId)

    /**
     * Where tokens live. The platform actual in production; a test replaces
     * it with an in-memory store, which is what lets a scrobble be tested
     * end to end without touching the app's real preferences.
     */
    internal var tokenStore: MalAuthPersistence = PlatformMalAuthPersistence

    /** A refresh is serialised: MAL revokes the old token on a new one. */
    private val refreshLock = Mutex()
    private var pollJob: Job? = null
    private var pendingAuth: MalPendingAuth? = null

    private val tokensByProfile = mutableMapOf<Int, MalTokens>()

    /** Episode writes MAL already saw, so a pause and a stop do not double-write. */
    private val recentWrites = mutableMapOf<Pair<Long, Int>, Long>()

    /** anilist id -> mal id, learned from this project's own server. */
    private val malIdByAnilistId = mutableMapOf<Long, Long>()

    private val _uiState = MutableStateFlow(MalAuthUiState())
    internal val uiState: StateFlow<MalAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.MAL,
        displayName = "MyAnimeList",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.SCROBBLE,
        ),
    )

    override val providerId: TrackingProviderId get() = TrackingProviderId.MAL

    init {
        scope.launch {
            activeProfile.collectLatest { profileId ->
                synchronized(this@MalTracker) { loadTokens(profileId) }
                MalActivity.load(profileId)
                publishAuth()
            }
        }
    }

    fun register() {
        if (TrackingProviderRegistry.authProvider(providerId) === this) return
        TrackingProviderRegistry.register(this)
        TrackingProviderRegistry.registerScrobbler(this)
        TrackingProviderRegistry.registerLibraryProvider(MalLibraryProvider)
        // A title the reader puts in their library is a plan, and this account
        // is where a plan belongs: every save is announced here once, and the
        // write itself lives in MalLibrary so the two providers of "what this
        // app told MAL" stay in one file.
        TrackingProviderRegistry.registerLibraryAddObserver { item ->
            scope.launch { MalLibrary.planAddedItem(item) }
        }
    }

    override fun ensureLoaded() {
        if (activeProfile.value != ProfileRepository.activeProfileId) onProfileChanged()
        synchronized(this) { loadTokens(activeProfile.value) }
        MalActivity.load(activeProfile.value)
        publishAuth()
    }

    override fun onProfileChanged() {
        pollJob?.cancel()
        pendingAuth = null
        activeProfile.value = ProfileRepository.activeProfileId
        synchronized(this) { loadTokens(activeProfile.value) }
        MalActivity.load(activeProfile.value)
        publishAuth()
    }

    override fun clearLocalState() {
        pollJob?.cancel()
        pendingAuth = null
        synchronized(this) {
            tokensByProfile.clear()
            tokenStore.clear()
        }
        MalActivity.forget()
        publishAuth()
    }

    override fun removeStoredProfile(profileId: Int) {
        synchronized(this) {
            tokensByProfile.remove(profileId)
            tokenStore.write(profileId, null)
        }
        // A deleted profile's activity goes with its tokens: the panel is about
        // an account, and this one no longer exists.
        MalActivity.clear(profileId)
        publishAuth()
    }

    // ---------------------------------------------------------------- login

    /**
     * Begin the login: mint the verifier and state, start collecting the code
     * the browser will drop at the server's door, and hand back the URL the
     * card opens. The card opens it in the system browser, which is why this
     * function never touches a browser itself.
     */
    fun startAuthorization(): String? {
        val profileId = ProfileRepository.activeProfileId
        val auth = MalPendingAuth(
            profileId = profileId,
            // The prefix is this app's signature on the state: the redirect
            // origin is shared with the web client, and an unmarked state would
            // land the browser on the web client's page, which would then
            // report a mismatch for a login that was never its own.
            state = MalApi.DESKTOP_STATE_PREFIX + MalApi.randomVerifier(48),
            verifier = MalApi.randomVerifier(64),
            startedAtEpochMs = epochMs(),
        )
        pendingAuth = auth
        publishAuth(busy = true)
        pollJob?.cancel()
        pollJob = scope.launch { collectCode(auth) }
        return MalApi.authorizeUrl(state = auth.state, verifier = auth.verifier)
    }

    /** The URL of a login still in flight, so the card can reopen it. */
    fun pendingAuthorizationUrl(): String? {
        val auth = pendingAuth ?: return null
        return MalApi.authorizeUrl(state = auth.state, verifier = auth.verifier)
    }

    fun cancelAuthorization() {
        pollJob?.cancel()
        pendingAuth = null
        publishAuth()
    }

    /** Ten minutes is generous: MAL's consent page is one screen and a login. */
    private const val AUTH_WINDOW_MS = 10 * 60_000L

    private suspend fun collectCode(auth: MalPendingAuth) {
        try {
            while (pendingAuth === auth && epochMs() - auth.startedAtEpochMs < AUTH_WINDOW_MS) {
                delay(2_000)
                val code = MalApi.pendingCode(auth.state) ?: continue
                val tokens = MalApi.exchangeCode(code, auth.verifier)
                synchronized(this) { storeTokens(auth.profileId, tokens) }
                val username = runCatching {
                    MalApi.fetchUsername(tokens.accessToken)
                }.getOrNull()
                pendingAuth = null
                publishAuth(username = username)
                log.i { "MyAnimeList linked" }
                MalLibrary.requestRefresh()
                return
            }
            if (pendingAuth === auth) {
                pendingAuth = null
                publishAuth(errorMessage = "The login was not finished in time.")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            pendingAuth = null
            log.w { "MyAnimeList login failed: ${error.message}" }
            publishAuth(errorMessage = error.message ?: "MyAnimeList sign-in failed.")
        }
    }

    fun disconnect() {
        cancelAuthorization()
        synchronized(this) {
            tokensByProfile.remove(activeProfile.value)
            tokenStore.write(activeProfile.value, null)
        }
        publishAuth()
    }

    // --------------------------------------------------------------- tokens

    /** A usable access token for [profileId], refreshed when it is stale. */
    suspend fun accessToken(profileId: Int): String? {
        val current = synchronized(this) {
            loadTokens(profileId)
            tokensByProfile[profileId]
        } ?: return null
        if (current.expiresAtEpochMs - epochMs() > 60_000L) return current.accessToken
        val refreshToken = current.refreshToken ?: return null
        return refreshLock.withLock {
            val again = synchronized(this) { tokensByProfile[profileId] } ?: return@withLock null
            if (again.expiresAtEpochMs - epochMs() > 60_000L) return@withLock again.accessToken
            try {
                val fresh = MalApi.refresh(refreshToken)
                // MAL may not repeat the refresh token; keep the old one when
                // it did not, so the next expiry is still recoverable.
                val stored = fresh.copy(refreshToken = fresh.refreshToken ?: refreshToken)
                synchronized(this) { storeTokens(profileId, stored) }
                publishAuth()
                stored.accessToken
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "MyAnimeList refresh failed: ${error.message}" }
                null
            }
        }
    }

    /**
     * A MAL call made with this profile's token, renewed **once** when
     * MyAnimeList refuses that token.
     *
     * This is the piece every other client of this registration already has and
     * this one was missing: the web client and the phone renew on a 401 and
     * repeat the call, which is what turns "the token went stale" into a
     * non-event. Here a 401 used to be thrown and swallowed — the scrobble
     * logged a warning, the library showed an error — so a token MAL had
     * invalidated early (its `expires_in` promises weeks; that promise is not a
     * guarantee) silently turned tracking off for good.
     *
     * A renewal that is refused means the session cannot be saved: the device
     * is no longer linked, the tokens are dropped so the settings card says so,
     * and the failure is reported rather than hidden.
     */
    internal suspend fun <T> authorized(profileId: Int, call: suspend (String) -> T): T? {
        val first = accessToken(profileId) ?: return null
        return try {
            call(first)
        } catch (error: MalApi.MalApiException) {
            if (error.status != UNAUTHORIZED) throw error
            val renewed = renew(profileId)
            if (renewed == null) {
                expireSession(profileId)
                throw error
            }
            try {
                call(renewed)
            } catch (again: MalApi.MalApiException) {
                if (again.status == UNAUTHORIZED) expireSession(profileId)
                throw again
            }
        }
    }

    /**
     * A refresh asked for *now* rather than when the store says the token is
     * stale — the case MAL invalidating a token early creates. Serialised on the
     * same lock: MAL revokes the old refresh token the moment a new one is
     * issued, so two renewals at once would race each other into a dead session.
     */
    private suspend fun renew(profileId: Int): String? = refreshLock.withLock {
        val current = synchronized(this) {
            loadTokens(profileId)
            tokensByProfile[profileId]
        }
        val refreshToken = current?.refreshToken ?: return@withLock null
        try {
            val fresh = MalApi.refresh(refreshToken)
            // MAL may not repeat the refresh token; keeping the old one is what
            // makes the next expiry recoverable.
            val stored = fresh.copy(refreshToken = fresh.refreshToken ?: refreshToken)
            synchronized(this) { storeTokens(profileId, stored) }
            publishAuth()
            log.i { "MyAnimeList: token renewed" }
            stored.accessToken
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "MyAnimeList renewal failed: ${error.message}" }
            null
        }
    }

    /**
     * MAL will not take this session again: forget it here too, so the card
     * offers a new login instead of claiming a link that no longer works.
     */
    private fun expireSession(profileId: Int) {
        synchronized(this) {
            tokensByProfile.remove(profileId)
            runCatching { tokenStore.write(profileId, null) }
        }
        MalLibrary.forget()
        publishAuth(errorMessage = SESSION_EXPIRED_MESSAGE)
    }

    private fun loadTokens(profileId: Int) {
        if (tokensByProfile.containsKey(profileId)) return
        val raw = runCatching { tokenStore.read(profileId) }.getOrNull() ?: return
        runCatching { json.decodeFromString<MalTokens>(raw) }
            .onSuccess { tokensByProfile[profileId] = it }
    }

    private fun storeTokens(profileId: Int, tokens: MalTokens) {
        tokensByProfile[profileId] = tokens
        runCatching { tokenStore.write(profileId, json.encodeToString(MalTokens.serializer(), tokens)) }
    }

    private fun publishAuth(busy: Boolean = false, username: String? = null, errorMessage: String? = null) {
        val profileId = activeProfile.value
        val linked = tokensByProfile[profileId] != null
        _isAuthenticated.value = linked
        _uiState.value = MalAuthUiState(
            profileId = profileId,
            isAuthenticated = linked,
            username = username ?: _uiState.value.username?.takeIf { linked },
            isBusy = busy || (pendingAuth != null),
            isAwaitingApproval = pendingAuth != null,
            authorizationUrl = pendingAuthorizationUrl(),
            errorMessage = errorMessage,
        )
    }

    // -------------------------------------------------------------- scrobble

    /**
     * An episode watched here becomes an episode watched there.
     *
     * The event carries whatever identity the player had, which for this
     * project's anime is `ha:an:<anilist id>` — a bare number to MAL. AniList
     * carries MAL's own id on the record and this project's server already
     * answers it (`/api/anime/:id`), so the one hop is a cached lookup rather
     * than a search by title.
     *
     * START is deliberately a no-op: a title entering MAL's Watching list the
     * moment its first frame appears would mark shows watched that were never
     * finished. A pause or a stop at the app's own watched threshold is what
     * writes, writing the same episode twice in a row is not repeated, and
     * what that write carries for the last episode is `completed` rather than
     * `watching` — see [statusForWrite].
     */
    override suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ) {
        if (action == TrackingScrobbleAction.START) return
        if (event.progressPercent < WATCHED_PERCENT_THRESHOLD) return
        val title = titleOf(event.media)
        val episode = event.media.episode?.number?.takeIf { it > 0 }
        if (episode == null) {
            // Watched past the threshold with no episode number to write: a
            // write that cannot be made, which is exactly the kind of silence
            // the activity panel exists to break.
            record(profileId, MalWriteAttempt(title, null, null, epochMs(), false, NO_EPISODE_MESSAGE))
            return
        }
        val malId = resolveMalId(event.media)
        if (malId == null) {
            record(profileId, MalWriteAttempt(title, null, episode, epochMs(), false, NO_MATCH_MESSAGE))
            return
        }

        val now = epochMs()
        synchronized(recentWrites) {
            val last = recentWrites[malId to episode]
            if (last != null && now - last < WRITE_DEDUPE_MS) return
            recentWrites[malId to episode] = now
            while (recentWrites.size > 64) recentWrites.remove(recentWrites.keys.first())
        }

        try {
            val written = authorized(profileId) { token ->
                MalApi.updateListStatus(
                    accessToken = token,
                    malId = malId,
                    status = statusForWrite(token, malId, episode),
                    watchedEpisodes = episode,
                )
            }
            if (written == null) {
                // Not a failure of the write — a refusal to make one. Recorded
                // all the same: a title played with no account linked is
                // tracking that is off, and nothing else on screen says so.
                record(profileId, MalWriteAttempt(title, malId, episode, epochMs(), false, NOT_LINKED_MESSAGE))
                return
            }
            record(profileId, MalWriteAttempt(title, malId, episode, epochMs(), true, null))
            log.i { "MyAnimeList: episode $episode of $malId marked watched" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A failed write is not fatal to playback; it is logged, filed for
            // the panel, and the next episode's write carries the count
            // forward anyway.
            synchronized(recentWrites) { recentWrites.remove(malId to episode) }
            record(profileId, MalWriteAttempt(title, malId, episode, epochMs(), false, error.message))
            log.w { "MyAnimeList write failed: ${error.message}" }
        }
    }

    /**
     * The status the episode about to be written should carry.
     *
     * MAL's five statuses are not derived from progress: writing
     * `num_watched_episodes` up to the show's total leaves the title on
     * Watching, which is how a finished series ends up sitting on the reader's
     * list as "Watching 12/12" until somebody says otherwise by hand. Two rules
     * put that right, and they are the web client's own rules — one account
     * should read the same whichever client watched the episode:
     *
     *   * the last episode finishes the title: the total MAL holds is the only
     *     honest answer to "was that the last one?", which is why the title is
     *     read before it is written;
     *   * a status the reader already chose is kept, except a plan — watching
     *     episode 3 of a completed show again is not unwatching it, and writing
     *     `watching` there would move a row the reader had finished. A title
     *     only planned, or new to the list, becomes watching: that is the one
     *     case where opening an episode plainly says more than the list does.
     */
    private suspend fun statusForWrite(token: String, malId: Long, episode: Int): String {
        val title = titleStatus(token, malId)
        val total = title?.episodesTotal ?: 0
        if (total > 0 && episode >= total) return MalListStatus.COMPLETED
        val current = title?.status
        if (current != null && !current.startsWith("plan_to_")) return current
        return MalListStatus.WATCHING
    }

    /**
     * One title as MAL describes it, or null when MAL will not describe it.
     *
     * A title MAL cannot describe is not a reason to skip the write: the episode
     * still goes, as `watching`, which is exactly what this provider wrote
     * before the total was ever read. The one failure that must not be swallowed
     * here is a token MAL refuses — the renewal inside [authorized] is what
     * recovers the session, and a read that hid the 401 would have the write
     * refused with the same dead token a moment later.
     */
    private suspend fun titleStatus(token: String, malId: Long): MalTitleStatus? =
        try {
            MalApi.fetchTitleStatus(token, malId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: MalApi.MalApiException) {
            if (error.status == UNAUTHORIZED) throw error
            null
        } catch (error: Throwable) {
            null
        }

    /**
     * One filed attempt, into the journal the activity panel reads.
     *
     * Every path out of [scrobble] goes through here, including the two that
     * used to be silent returns — that is the whole point of the journal: the
     * row that says "nothing was sent, and here is why" is the one nobody
     * could see before.
     */
    private fun record(profileId: Int, attempt: MalWriteAttempt) {
        runCatching { MalActivity.record(profileId, attempt) }
            .onFailure { log.w { "MyAnimeList activity could not be filed: ${it.message}" } }
    }

    /**
     * Sends one filed attempt again, deliberately.
     *
     * The panel's "Send now": the same write a finished episode makes, asked
     * for by hand, for the case the episode wrote before the session went bad.
     * The status is decided again rather than copied from the attempt, so a
     * re-send of a title's last episode lands as completed like the first
     * attempt meant to. Only a title MAL has an id for can be sent — an attempt
     * that never found its entry has nothing to address.
     */
    internal suspend fun retry(attempt: MalWriteAttempt): Boolean {
        val malId = attempt.malId ?: return false
        val episode = attempt.episode?.takeIf { it > 0 } ?: return false
        val profileId = activeProfile.value
        return try {
            val written = authorized(profileId) { token ->
                MalApi.updateListStatus(
                    accessToken = token,
                    malId = malId,
                    status = statusForWrite(token, malId, episode),
                    watchedEpisodes = episode,
                )
            }
            if (written == null) {
                record(profileId, attempt.copy(attemptedAtEpochMs = epochMs(), detail = NOT_LINKED_MESSAGE))
                return false
            }
            // The episode is written again, so the dedupe window must not stand
            // in the way of the next genuine write for it.
            synchronized(recentWrites) { recentWrites.remove(malId to episode) }
            record(profileId, MalWriteAttempt(attempt.title, malId, episode, epochMs(), true, null))
            log.i { "MyAnimeList: episode $episode of $malId sent again" }
            // The list is *not* re-read from here: the caller — the panel's own
            // press — is the one that wants the fresh answer, and a write that
            // fires a background read is a write whose effects are not its own
            // to wait for.
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            record(profileId, attempt.copy(attemptedAtEpochMs = epochMs(), detail = error.message))
            log.w { "MyAnimeList re-send failed: ${error.message}" }
            false
        }
    }

    /** What the panel calls a title: the name the player had, or the id it had. */
    private fun titleOf(media: TrackingMediaReference): String {
        media.title?.takeIf { it.isNotBlank() }?.let { return it }
        media.catalog?.contentId?.takeIf { it.isNotBlank() }?.let { return it }
        return media.stableKey
    }

    /** A MAL id from whatever identity the event carried. */
    internal suspend fun resolveMalId(media: com.nuvio.app.features.tracking.TrackingMediaReference): Long? {
        media.ids.mal?.let { return it }
        return resolveMalIdForContent(
            contentId = media.catalog?.contentId.orEmpty(),
            videoId = media.catalog?.videoId.orEmpty(),
        )
    }

    /**
     * A MAL id for a title this app knows only by its own content id — the
     * shape a library row carries (`ha:an:21` from the catalogue, `mal:21`
     * when the row came from MAL's own shelf).
     *
     * The same arithmetic a scrobble makes, because it is the same question:
     * AniList carries MAL's id on every anime record and this project's server
     * already answers it, so one cached lookup turns one into the other. A
     * title that is plainly not anime — a film, a series, an adult video —
     * answers null without asking anybody.
     */
    internal suspend fun resolveMalIdForContent(contentId: String, videoId: String = ""): Long? {
        listOf(contentId, videoId).forEach { value ->
            if (value.startsWith("mal:", ignoreCase = true)) {
                value.substringAfter(':').substringBefore(':').toLongOrNull()?.let { return it }
            }
        }
        val anilistId = anilistIdFrom(contentId) ?: anilistIdFrom(videoId) ?: return null
        synchronized(malIdByAnilistId) { malIdByAnilistId[anilistId] }?.let { return it }
        val lookedUp = runCatching { fetchMalIdForAnilist(anilistId) }.getOrNull() ?: return null
        synchronized(malIdByAnilistId) { malIdByAnilistId[anilistId] = lookedUp }
        return lookedUp
    }

    /**
     * `ha:an:21`, `ha:an:21:1:5`, `an:21` or a bare `21` — the shapes a
     * content id can arrive in for this project's anime area.
     */
    internal fun anilistIdFrom(contentId: String): Long? {
        val value = contentId.trim()
        if (value.isEmpty()) return null
        val parts = value.split(':')
        return when {
            parts.size >= 3 && parts[0].equals("ha", true) && parts[1].equals("an", true) ->
                parts[2].toLongOrNull()
            parts.size == 2 && parts[0].equals("an", true) -> parts[1].toLongOrNull()
            parts.size == 1 -> value.toLongOrNull()
            else -> null
        }
    }

    private suspend fun fetchMalIdForAnilist(anilistId: Long): Long? {
        // Through MalApi's transport like every other request this provider
        // makes: the seam is what lets a test record the lookup, and what
        // keeps the relay the single door to the network.
        val res = MalApi.transport(
            "GET",
            "${MalApi.RELAY_BASE}/api/anime/$anilistId",
            emptyMap(),
            "",
        )
        if (res.status != 200) return null
        val details = runCatching {
            (json.parseToJsonElement(res.body) as? JsonObject)
                ?.get("details")
                ?.let { it as? JsonObject }
                ?.get("malId")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.toLongOrNull()
        }.getOrNull()
        return details
    }

    internal const val WATCHED_PERCENT_THRESHOLD = 70.0
    private const val WRITE_DEDUPE_MS = 30_000L

    /** MAL's own refusal for a token it will not take, on any route. */
    private const val UNAUTHORIZED = 401

    /** What the settings card says when a session cannot be renewed. */
    internal const val SESSION_EXPIRED_MESSAGE = "The MyAnimeList session expired — link again."

    /** Why a watched episode produced no write: there is no account to write to. */
    internal const val NOT_LINKED_MESSAGE =
        "No MyAnimeList account is linked, so nothing was sent."

    /** Why a watched episode produced no write: MAL does not carry the title. */
    internal const val NO_MATCH_MESSAGE =
        "No MyAnimeList entry was found for this title, so nothing was sent."

    /** Why a watched episode produced no write: the player never named it. */
    internal const val NO_EPISODE_MESSAGE =
        "The player did not say which episode this was, so nothing was sent."
}

internal data class MalPendingAuth(
    val profileId: Int,
    val state: String,
    val verifier: String,
    val startedAtEpochMs: Long,
)

internal data class MalAuthUiState(
    val profileId: Int = 0,
    val isAuthenticated: Boolean = false,
    val username: String? = null,
    val isBusy: Boolean = false,
    val isAwaitingApproval: Boolean = false,
    val authorizationUrl: String? = null,
    val errorMessage: String? = null,
)
