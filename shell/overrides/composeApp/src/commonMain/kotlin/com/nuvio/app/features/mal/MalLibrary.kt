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
import kotlinx.coroutines.CancellationException
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

    /** What the panel says when a library add could not be checked with MAL. */
    private const val PLAN_UNREADABLE_MESSAGE =
        "MyAnimeList could not be asked about this title, so its status was left alone."

    /**
     * How many stale rows one read is allowed to put right. The rest wait for
     * the next read: a list that has been wrong for months is not a reason to
     * fire a hundred writes at MAL in one breath.
     */
    private const val MAX_SETTLEMENTS_PER_READ = 10

    /** Filed when a repair was refused without saying why. */
    private const val STALE_ROW_UNREADABLE_MESSAGE =
        "MyAnimeList refused to mark this finished title completed."

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
        try {
            // Through the tracker's own authorised call, so a token MAL refuses
            // is renewed and the read is made again rather than becoming an
            // error on the shelf. The one write a read is allowed to make rides
            // the same authorisation — see [settleFinishedRows] — and is the
            // reason the read and the repair are one call rather than two.
            val entries = MalTracker.authorized(profileId) { token ->
                settleFinishedRows(profileId, token, MalApi.fetchAnimeList(token))
            }
            if (entries == null) {
                _state.value = State()
                return
            }
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

    /**
     * The rows a read finds finished but still sitting on Watching, put right
     * while the read that found them is still in hand.
     *
     * MAL holds a status and an episode count in separate fields and derives
     * neither from the other, so a title watched to its end *before* this app
     * knew to say so was left on the reader's list as "Watching 12/12" — the
     * count at the total, the status still watching — and nothing here ever
     * went back for it, because the completion rule only decides what a *new*
     * write carries. A read is the one moment the app holds both the row and
     * the title's total, so it is the moment to finish such a row: one write of
     * `completed`, and no count restated — the count is already at the total,
     * and MAL writes only the fields it is handed.
     *
     * Three things keep this from being a write the reader never asked for:
     *
     *  * only a *finished* row is touched ([MalListEntry.isFinishedButWatching]
     *    — Watching, a known total, and a count at or past it), so nothing in
     *    progress moves, and a title MAL knows no total for is left alone;
     *  * a row being re-watched is skipped: `is_rewatching` is MAL saying the
     *    reader put it back on Watching themselves;
     *  * at most [MAX_SETTLEMENTS_PER_READ] rows are settled per read, so an
     *    account that spent a year under the old rule heals over a few reads
     *    instead of in one burst MAL would answer with refusals.
     *
     * A write that fails is filed like every other attempt — the panel is where
     * "the row is wrong, and this is why" belongs — and is never rethrown: the
     * list in hand is good, and a shelf that hid the reader's own list because
     * one repair bounced would be worse than the stale row it was sent for. The
     * journal row keeps the episode count, so "Send now" re-sends exactly the
     * write that failed.
     *
     * The write is also the answer: a row MAL accepted shows as completed here
     * without a second read of it.
     */
    private suspend fun settleFinishedRows(
        profileId: Int,
        token: String,
        entries: List<MalListEntry>,
    ): List<MalListEntry> {
        val stale = entries
            .filter(MalListEntry::isFinishedButWatching)
            .take(MAX_SETTLEMENTS_PER_READ)
        if (stale.isEmpty()) return entries

        val settled = mutableSetOf<Long>()
        for (entry in stale) {
            val written = try {
                MalApi.updateListStatus(
                    accessToken = token,
                    malId = entry.malId,
                    status = MalListStatus.COMPLETED,
                    watchedEpisodes = null,
                )
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                record(
                    profileId,
                    MalWriteAttempt(
                        entry.title,
                        entry.malId,
                        entry.watchedEpisodes,
                        epochMs(),
                        false,
                        error.message ?: STALE_ROW_UNREADABLE_MESSAGE,
                    ),
                )
                log.w { "MyAnimeList: ${entry.malId} could not be finished: ${error.message}" }
                false
            }
            if (!written) continue
            log.i {
                "MyAnimeList: ${entry.malId} finished on the list " +
                    "(it sat at ${entry.watchedEpisodes}/${entry.episodesTotal})"
            }
            record(
                profileId,
                MalWriteAttempt(entry.title, entry.malId, entry.watchedEpisodes, epochMs(), true, null),
            )
            settled += entry.malId
        }
        return entries.map { entry ->
            if (entry.malId in settled) entry.copy(status = MalListStatus.COMPLETED) else entry
        }
    }

    fun requestRefresh() {
        MalLibraryProvider.refreshInBackground()
    }

    // ------------------------------------------------------------- the plan

    /**
     * A title that just entered the reader's library, written to MyAnimeList as
     * a plan.
     *
     * The app's library is a watchlist — titles the reader means to get to —
     * and MAL has a status for exactly that, which is why saving something
     * there should not leave the account unaware it exists. Three rules keep
     * the write from ever *taking* something away:
     *
     *   * a title MAL's list already holds keeps the status it has. Adding a
     *     title to a library is not a reason to move a row the reader had
     *     finished, was re-watching, or had on hold — so the title is read
     *     first and a write only happens when there was nothing to overwrite;
     *   * a title MAL cannot be asked about (the read failed) is left alone
     *     rather than guessed at, and the failure is filed, because "nothing
     *     happened and here is why" is what the activity panel is for;
     *   * a title this provider does not track — a film, a series, an adult
     *     video — writes nothing and files nothing. The reader's library holds
     *     all of those, and none of them is an anime with a MAL row.
     *
     * With no account linked nothing is written and nothing is filed: the
     * library is still the reader's own, and the settings card already says
     * the account is not linked.
     */
    suspend fun planAddedItem(item: LibraryItem) {
        if (!MalTracker.isAuthenticated.value) return
        val malId = MalTracker.resolveMalIdForContent(
            contentId = item.trackingProviderItemId?.takeIf(String::isNotBlank) ?: item.id,
        ) ?: return
        // The copy of the list already in hand is the cheap half of the answer:
        // a title it carries is on the reader's list, so their status stands
        // and no read is needed to know it.
        if (_state.value.entries.any { entry -> entry.malId == malId }) return

        val profileId = MalTracker.uiState.value.profileId
        val title = item.name.takeIf(String::isNotBlank) ?: item.id
        // `true` when the list holds it, `false` when it does not, and null
        // when there was no usable token to ask with — three answers that a
        // nullable status could not tell apart.
        val onList = try {
            MalTracker.authorized(profileId) { token ->
                MalApi.fetchTitleStatus(token, malId).status != null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "MyAnimeList could not be asked about $malId: ${error.message}" }
            record(profileId, MalWriteAttempt(title, malId, null, epochMs(), false, PLAN_UNREADABLE_MESSAGE))
            return
        }
        if (onList == null) return
        if (onList) {
            log.i { "MyAnimeList already holds $malId; the library add wrote nothing" }
            return
        }

        try {
            val written = MalTracker.authorized(profileId) { token ->
                MalApi.updateListStatus(
                    accessToken = token,
                    malId = malId,
                    status = MalListStatus.PLAN_TO_WATCH,
                    // No episode count: a plan carries status and nothing else,
                    // and MAL writes only the fields it is handed.
                    watchedEpisodes = null,
                )
                true
            }
            if (written == null) {
                record(
                    profileId,
                    MalWriteAttempt(title, malId, null, epochMs(), false, MalTracker.NOT_LINKED_MESSAGE),
                )
                return
            }
            log.i { "MyAnimeList: $malId planned (added to the library)" }
            record(profileId, MalWriteAttempt(title, malId, null, epochMs(), true, null))
            // The row the reader is about to look for is on the list, so read
            // it back rather than leaving the shelf showing the state before
            // this save.
            refresh(TrackingRefreshIntent.INVALIDATED, force = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            record(profileId, MalWriteAttempt(title, malId, null, epochMs(), false, error.message))
            log.w { "MyAnimeList plan write failed: ${error.message}" }
        }
    }

    /** One filed attempt, into the journal the settings panel reads. */
    private fun record(profileId: Int, attempt: MalWriteAttempt) {
        runCatching { MalActivity.record(profileId, attempt) }
            .onFailure { log.w { "MyAnimeList activity could not be filed: ${it.message}" } }
    }

    /**
     * Forget the copy: the session behind it cannot be renewed, so a shelf that
     * kept painting from it would show a list nobody can act on.
     */
    fun forget() {
        _state.value = State()
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
        val status = desiredStatus(desired)

        val written = MalTracker.authorized(profileId) { token ->
            if (status == null) {
                MalApi.deleteListStatus(token, entry.malId)
            } else {
                MalApi.updateListStatus(
                    accessToken = token,
                    malId = entry.malId,
                    status = status,
                    watchedEpisodes = entry.watchedEpisodes.takeIf { it > 0 },
                )
            }
        }
        if (written == null) return null
        log.i {
            if (status == null) "MyAnimeList: ${entry.title} removed from the list"
            else "MyAnimeList: ${entry.title} -> $status"
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

    /**
     * What an unchecked "add to library" means on MyAnimeList.
     *
     * A library is a plan first: a title this account's list does not hold is
     * being *added*, and the status that says so is Plan to watch. A title does
     * not become Watching because it was saved — it becomes Watching because an
     * episode was played, which is the tracker's own write to make, and writing
     * `watching` here is how a saved-but-unstarted title ended up looking like
     * a show in progress. A title already on the list keeps the status it has:
     * this function is reached with the reader's own row, and restating it is
     * the one way a save could move something they had finished.
     */
    override fun toggledDefaultMembership(currentMembership: Map<String, Boolean>): Map<String, Boolean> {
        val current = MalLibrary.desiredStatus(currentMembership)
        val next = current ?: MalListStatus.PLAN_TO_WATCH
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
