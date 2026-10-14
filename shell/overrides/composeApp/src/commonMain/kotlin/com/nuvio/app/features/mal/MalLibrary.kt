package com.nuvio.app.features.mal

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.tracking.TrackingLibraryProvider
import com.nuvio.app.features.tracking.TrackingLibrarySnapshot
import com.nuvio.app.features.tracking.TrackingLibraryTab
import com.nuvio.app.features.tracking.TrackingLibraryTabKind
import com.nuvio.app.features.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.app.features.tracking.TrackingMembershipRemovalImpact
import com.nuvio.app.features.tracking.TrackingMembershipResolution
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingProviderId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * The person's MyAnimeList, projected into the app's library.
 *
 * A MAL list is five statuses, not five lists: a title is *in* exactly one of
 * them, which is why the tabs are the statuses and moving a title between
 * tabs is a status write rather than a copy. Rows carry `mal:<id>` as their
 * content id — the id space this project's addon answers for anime (it turns
 * one into the AniList record behind it), so a row opens and plays like any
 * other title.
 */
internal object MalLibrary {

    private val log = Logger.withTag("MalLibrary")

    data class State(
        val entries: List<MalListEntry> = emptyList(),
        val hasLoaded: Boolean = false,
        val isLoading: Boolean = false,
        val error: String? = null,
        val checkedAtEpochMs: Long? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun entryFor(contentId: String): MalListEntry? {
        val id = contentId.removePrefix("mal:").substringBefore(':').toLongOrNull() ?: return null
        return _state.value.entries.firstOrNull { it.malId == id }
    }

    /**
     * One refresh per intent: an automatic one rides the cache, a user's
     * "sync now" does not. Concurrent calls collapse into the one in flight.
     */
    suspend fun refresh(intent: TrackingRefreshIntent, force: Boolean = false) {
        val current = _state.value
        if (current.isLoading) return
        if (!force && intent == TrackingRefreshIntent.AUTOMATIC && current.hasLoaded) return
        _state.value = current.copy(isLoading = true, error = null)

        val profileId = MalTracker.uiState.value.profileId
        val token = MalTracker.accessToken(profileId)
        if (token == null) {
            _state.value = State()
            return
        }
        try {
            val entries = MalApi.fetchAnimeList(token)
            _state.value = State(
                entries = entries,
                hasLoaded = true,
                isLoading = false,
                checkedAtEpochMs = epochMs(),
            )
        } catch (error: Throwable) {
            log.w { "MyAnimeList list refresh failed: ${error.message}" }
            _state.value = _state.value.copy(
                isLoading = false,
                hasLoaded = _state.value.hasLoaded,
                error = error.message ?: "MyAnimeList list unavailable",
            )
        }
    }

    fun requestRefresh() {
        MalLibraryProvider.refreshInBackground()
    }

    // ------------------------------------------------------------ projection

    val tabs: List<TrackingLibraryTab>
        get() = MalListStatus.ALL.map { status ->
            TrackingLibraryTab(
                key = MalListStatus.tabKey(status),
                title = when (status) {
                    MalListStatus.WATCHING -> "Watching"
                    MalListStatus.COMPLETED -> "Completed"
                    MalListStatus.ON_HOLD -> "On hold"
                    MalListStatus.DROPPED -> "Dropped"
                    else -> "Plan to watch"
                },
                providerId = TrackingProviderId.MAL,
                kind = TrackingLibraryTabKind.STATUS,
                supportedContentTypes = setOf("series", "show", "tv", "anime", "movie"),
            )
        }

    fun snapshot(): TrackingLibrarySnapshot {
        val state = _state.value
        val entries = state.entries.map(::libraryItem)
        return TrackingLibrarySnapshot(
            items = entries,
            sections = MalListStatus.ALL.mapNotNull { status ->
                val key = MalListStatus.tabKey(status)
                entries.filter { key in it.listKeys }
                    .takeIf { it.isNotEmpty() }
                    ?.let { section -> LibrarySection(key, sectionTitle(status), section) }
            },
            tabs = tabs,
            hasLoaded = state.hasLoaded,
            isLoading = state.isLoading,
            errorMessage = state.error,
        )
    }

    private fun sectionTitle(status: String): String = tabs.firstOrNull {
        it.key == MalListStatus.tabKey(status)
    }?.title ?: status

    /**
     * One MAL row as the app's library item. The id is `mal:<id>` — the addon
     * answers it (turning it into the AniList record) — and the poster is
     * MAL's own, which needs no addon at all.
     */
    private fun libraryItem(entry: MalListEntry) = LibraryItem(
        id = entry.contentId,
        type = "series",
        name = entry.title,
        poster = entry.poster,
        releaseInfo = null,
        listKeys = setOf(MalListStatus.tabKey(entry.status)),
        mediaCategory = "anime",
        trackingProviderId = TrackingProviderId.MAL.storageId,
        trackingProviderItemId = entry.contentId,
        trackingSourceUrl = "https://myanimelist.net/anime/${entry.malId}",
        savedAtEpochMs = entry.updatedAtEpochMs.takeIf { it > 0 } ?: epochMs(),
    )

    // ------------------------------------------------------------- membership

    val membershipKeys: List<String> get() = tabs.map(TrackingLibraryTab::key)

    fun membershipFor(item: LibraryItem): Map<String, Boolean> =
        membershipKeys.associateWith { key -> key in item.listKeys }

    /**
     * The single status a set of toggles asks for, or null when the row is to
     * leave the list entirely. MAL keeps a title in exactly one status, so
     * several checked at once cannot be honoured — the last one wins, which is
     * what a picker that enforces one choice does anyway.
     */
    fun desiredStatus(desired: Map<String, Boolean>): String? =
        MalListStatus.ALL.lastOrNull { status -> desired[MalListStatus.tabKey(status)] == true }

    /** Taking a row off the list deletes its score and progress with it. */
    fun removalConfirmation(): TrackingMembershipRemovalConfirmation =
        TrackingMembershipRemovalConfirmation(
            providerId = TrackingProviderId.MAL,
            impacts = setOf(
                TrackingMembershipRemovalImpact.WATCHED_HISTORY,
                TrackingMembershipRemovalImpact.RATING,
            ),
        )

    /**
     * The write behind a toggle: a status change, or a removal when no status
     * is left checked. The row keeps its episode count — moving a title to
     * "on hold" must not unwatch it.
     */
    suspend fun applyMembership(
        item: LibraryItem,
        desired: Map<String, Boolean>,
    ): TrackingMembershipResolution? {
        val entry = entryFor(item.id) ?: return null
        val profileId = MalTracker.uiState.value.profileId
        val token = MalTracker.accessToken(profileId) ?: return null
        val status = desiredStatus(desired)

        if (status == null) {
            MalApi.deleteListStatus(token, entry.malId)
            log.i { "MyAnimeList: ${entry.title} removed from the list" }
        } else {
            MalApi.updateListStatus(
                accessToken = token,
                malId = entry.malId,
                status = status,
                watchedEpisodes = entry.watchedEpisodes.takeIf { it > 0 },
            )
            log.i { "MyAnimeList: ${entry.title} -> $status" }
        }
        refresh(TrackingRefreshIntent.INVALIDATED, force = true)
        val requested = desired.entries.firstOrNull { it.value }?.key ?: membershipKeys.first()
        val resolved = MalListStatus.tabKey(status ?: MalListStatus.DROPPED)
        return TrackingMembershipResolution(
            providerId = TrackingProviderId.MAL,
            requestedListKey = requested,
            resolvedListKey = resolved,
        )
    }
}

/**
 * The registry's view of [MalLibrary]: read the list, show it, and let a row
 * move between MAL's statuses.
 */
internal object MalLibraryProvider : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.MAL
    override val changes: Flow<Unit> = MalLibrary.state.map { }
    override val connectionRefreshIntent = TrackingRefreshIntent.AUTOMATIC

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refreshInBackground() {
        scope.launch { MalLibrary.refresh(TrackingRefreshIntent.AUTOMATIC) }
    }

    override fun ensureLoaded() = Unit
    override suspend fun refresh(intent: TrackingRefreshIntent) = MalLibrary.refresh(intent)
    override fun snapshot(): TrackingLibrarySnapshot = MalLibrary.snapshot()

    override fun contains(contentId: String, contentType: String?): Boolean =
        MalLibrary.entryFor(contentId) != null

    override fun find(contentId: String): LibraryItem? =
        MalLibrary.snapshot().items.firstOrNull { it.id == contentId || contentId.removePrefix("mal:") == it.id.removePrefix("mal:") }

    override suspend fun membership(item: LibraryItem): Map<String, Boolean> =
        MalLibrary.membershipFor(item)

    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> {
        val current = MalLibrary.desiredStatus(currentMembership)
        val next = if (current == MalListStatus.WATCHING) MalListStatus.COMPLETED else MalListStatus.WATCHING
        return MalLibrary.membershipKeys.associateWith { it == MalListStatus.tabKey(next) }
    }

    override fun membershipRemovalConfirmation(
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
    ): TrackingMembershipRemovalConfirmation? =
        MalLibrary.removalConfirmation().takeIf { MalLibrary.desiredStatus(desiredMembership) == null }

    override suspend fun applyMembership(
        profileId: Int,
        item: LibraryItem,
        desiredMembership: Map<String, Boolean>,
        destructiveRemovalConfirmed: Boolean,
    ): TrackingMembershipResolution? = MalLibrary.applyMembership(item, desiredMembership)
}
