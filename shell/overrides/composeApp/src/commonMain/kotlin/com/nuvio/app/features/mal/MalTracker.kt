package com.nuvio.app.features.mal

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
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
                publishAuth()
            }
        }
    }

    fun register() {
        if (TrackingProviderRegistry.authProvider(providerId) === this) return
        TrackingProviderRegistry.register(this)
        TrackingProviderRegistry.registerScrobbler(this)
        TrackingProviderRegistry.registerLibraryProvider(MalLibraryProvider)
    }

    override fun ensureLoaded() {
        if (activeProfile.value != ProfileRepository.activeProfileId) onProfileChanged()
        synchronized(this) { loadTokens(activeProfile.value) }
        publishAuth()
    }

    override fun onProfileChanged() {
        pollJob?.cancel()
        pendingAuth = null
        activeProfile.value = ProfileRepository.activeProfileId
        synchronized(this) { loadTokens(activeProfile.value) }
        publishAuth()
    }

    override fun clearLocalState() {
        pollJob?.cancel()
        pendingAuth = null
        synchronized(this) {
            tokensByProfile.clear()
            tokenStore.clear()
        }
        publishAuth()
    }

    override fun removeStoredProfile(profileId: Int) {
        synchronized(this) {
            tokensByProfile.remove(profileId)
            tokenStore.write(profileId, null)
        }
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
     * writes, and writing the same episode twice in a row is not repeated.
     */
    override suspend fun scrobble(
        profileId: Int,
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent,
    ) {
        if (action == TrackingScrobbleAction.START) return
        if (event.progressPercent < WATCHED_PERCENT_THRESHOLD) return
        val episode = event.media.episode?.number?.takeIf { it > 0 } ?: return
        val malId = resolveMalId(event.media) ?: return

        val now = epochMs()
        synchronized(recentWrites) {
            val last = recentWrites[malId to episode]
            if (last != null && now - last < WRITE_DEDUPE_MS) return
            recentWrites[malId to episode] = now
            while (recentWrites.size > 64) recentWrites.remove(recentWrites.keys.first())
        }

        val token = accessToken(profileId) ?: return
        try {
            MalApi.updateListStatus(
                accessToken = token,
                malId = malId,
                status = MalListStatus.WATCHING,
                watchedEpisodes = episode,
            )
            log.i { "MyAnimeList: episode $episode of $malId marked watched" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // A failed write is not fatal to playback; it is logged and the
            // next episode's write carries the count forward anyway.
            synchronized(recentWrites) { recentWrites.remove(malId to episode) }
            log.w { "MyAnimeList write failed: ${error.message}" }
        }
    }

    /** A MAL id from whatever identity the event carried. */
    internal suspend fun resolveMalId(media: com.nuvio.app.features.tracking.TrackingMediaReference): Long? {
        media.ids.mal?.let { return it }
        val contentId = media.catalog?.contentId.orEmpty()
        val videoId = media.catalog?.videoId.orEmpty()
        if (contentId.startsWith("mal:", ignoreCase = true)) {
            contentId.substringAfter(':').substringBefore(':').toLongOrNull()?.let { return it }
        }
        if (videoId.startsWith("mal:", ignoreCase = true)) {
            videoId.substringAfter(':').substringBefore(':').toLongOrNull()?.let { return it }
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
