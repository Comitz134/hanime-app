package com.nuvio.app.features.mal

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What this app has told MyAnimeList, and what came of it.
 *
 * The writes that keep MAL's episode counts in step are fire-and-forget by
 * design: an episode that will not record is not a reason to stop playing, so
 * the failure was logged and forgotten. That is exactly how a dead session
 * stayed invisible — the sheet played, the count did not move, and nothing on
 * screen said so. This is the missing half of that promise: one row per title
 * this app has tried to write, kept on the device, so the panel below the
 * connection card can say "this app reached episode 5, MyAnimeList still says
 * 2" instead of leaving the two to be compared by memory.
 *
 * A row is written for *every* attempt, successful or not, because the
 * failures are the point. The three that used to disappear entirely:
 *
 *  - the title has no MyAnimeList entry this app could find (the id lookup
 *    answered nothing), so nothing was ever sent;
 *  - there is no linked account, which is a refusal to write, not an error;
 *  - the write left and MAL refused it — a token it will not take, a row it
 *    will not accept.
 *
 * Deliberately small: one row per title, latest attempt wins, sixty titles.
 * This is a shortlist of what is wrong, not a log.
 */
internal object MalActivity {

    private val log = Logger.withTag("MalActivity")

    private val json = Json { ignoreUnknownKeys = true }

    /** How many titles the journal keeps. Latest activity wins the space. */
    internal const val MAX_TITLES = 60

    /**
     * Where the journal lives. The platform actual in production; a test
     * replaces it with an in-memory store, the same seam the tokens use.
     */
    internal var store: MalJournalPersistence = PlatformMalJournalPersistence

    private val _attempts = MutableStateFlow<List<MalWriteAttempt>>(emptyList())

    /** Newest first: what this app last tried to tell MyAnimeList. */
    val attempts: StateFlow<List<MalWriteAttempt>> = _attempts.asStateFlow()

    /** Which profile's journal is in memory; null until one is read. */
    private var loadedProfileId: Int? = null

    /** Reads the journal for [profileId] once, and leaves it alone after that. */
    fun load(profileId: Int) {
        if (loadedProfileId == profileId) return
        loadedProfileId = profileId
        val raw = runCatching { store.read(profileId) }.getOrNull()
        val rows = raw?.let { text ->
            runCatching { json.decodeFromString<List<MalWriteAttempt>>(text) }.getOrNull()
        }
        _attempts.value = rows.orEmpty()
    }

    /**
     * Files one attempt. A title that was tried again replaces its older row
     * rather than growing a second one — the question the panel answers is
     * "where does this title stand", and two rows about one title cannot
     * answer it.
     */
    fun record(profileId: Int, attempt: MalWriteAttempt) {
        load(profileId)
        val key = attempt.key
        val rest = _attempts.value.filterNot { it.key == key }
        val next = (listOf(attempt) + rest).take(MAX_TITLES)
        _attempts.value = next
        persist(profileId, next)
    }

    /** The whole journal goes when the account it is about does. */
    fun clear(profileId: Int) {
        loadedProfileId = null
        _attempts.value = emptyList()
        runCatching { store.write(profileId, null) }
    }

    fun forget() {
        loadedProfileId = null
        _attempts.value = emptyList()
        runCatching { store.clear() }
    }

    private fun persist(profileId: Int, rows: List<MalWriteAttempt>) {
        runCatching { store.write(profileId, json.encodeToString(rows)) }
            .onFailure { log.w { "MyAnimeList activity could not be stored: ${it.message}" } }
    }
}

/**
 * One attempt to tell MyAnimeList about a title, as the panel remembers it.
 *
 * `malId` is null when the attempt never got that far — an id this app could
 * not resolve is a title that was watched and never recorded, which is the
 * failure worth seeing. `episode` is what the player had reached, and `detail`
 * is why an attempt failed, in the words the call already produced.
 */
@Serializable
internal data class MalWriteAttempt(
    val title: String = "",
    val malId: Long? = null,
    val episode: Int? = null,
    val attemptedAtEpochMs: Long = 0L,
    val succeeded: Boolean = false,
    val detail: String? = null,
) {
    /** What two attempts about the same title share. */
    val key: String
        get() = malId?.let { "mal:$it" } ?: "title:${title.trim().lowercase()}"

    /** Only a title MAL knows can be written again. */
    val isRetryable: Boolean get() = !succeeded && malId != null && (episode ?: 0) > 0
}

/** Whether MyAnimeList's list holds the title, as far as this device knows. */
internal enum class MalMembership { ON_LIST, NOT_ON_LIST, UNKNOWN }

/** Who made the list row move last, as far as the two stamps can tell. */
internal enum class MalMovement { THIS_APP, SOMEWHERE_ELSE }

/**
 * One row of the panel: a title this app tracks, crossed with what
 * MyAnimeList holds for it.
 *
 * `watchedEpisodes` and `status` are MAL's own answer — the truth, read back
 * over the network — while `lastAttempt` is this app's own last word. Every
 * disagreement between the two is a `trouble` line, which is what makes a
 * write that did not take visible instead of invisible.
 */
internal data class MalTrackedTitle(
    val title: String,
    val malId: Long?,
    val membership: MalMembership,
    val status: String?,
    val watchedEpisodes: Int,
    val episodesTotal: Int,
    val lastMovedAtEpochMs: Long,
    val lastMovedBy: MalMovement?,
    val lastAttempt: MalWriteAttempt,
    val troubles: List<String> = emptyList(),
) {
    /** What MAL says it is at: its own episode count, once it holds the title. */
    val standing: String
        get() = when (membership) {
            MalMembership.UNKNOWN -> "MyAnimeList's list has not been read yet"
            MalMembership.NOT_ON_LIST -> "MyAnimeList has no row for it"
            MalMembership.ON_LIST -> when {
                episodesTotal > 0 -> "$watchedEpisodes / $episodesTotal episodes"
                watchedEpisodes > 0 -> "$watchedEpisodes episodes"
                else -> "No progress recorded yet"
            }
        }
}

/**
 * The panel's rows: one per title this app has tried to write, newest first.
 *
 * [listLoaded] separates "MyAnimeList does not hold this title" from "the list
 * has not been read yet" — claiming a title is missing from a list nobody has
 * fetched would be the same kind of confident lie this panel exists to stop.
 */
internal fun malTrackedTitles(
    attempts: List<MalWriteAttempt>,
    entries: List<MalListEntry>,
    listLoaded: Boolean,
): List<MalTrackedTitle> = attempts
    .sortedByDescending { it.attemptedAtEpochMs }
    .map { attempt -> trackedTitle(attempt, attempts, entries, listLoaded) }

private fun trackedTitle(
    attempt: MalWriteAttempt,
    all: List<MalWriteAttempt>,
    entries: List<MalListEntry>,
    listLoaded: Boolean,
): MalTrackedTitle {
    val entry = attempt.malId?.let { id -> entries.firstOrNull { it.malId == id } }
    val membership = when {
        entry != null -> MalMembership.ON_LIST
        listLoaded -> MalMembership.NOT_ON_LIST
        else -> MalMembership.UNKNOWN
    }
    // This title's own successes, not just the newest row: a write that took
    // last week is still what moved the row MAL holds.
    val sent = all.filter { it.key == attempt.key && it.succeeded }
    val ourLastWrite = sent.maxOfOrNull { it.attemptedAtEpochMs } ?: 0L
    val malUpdated = entry?.updatedAtEpochMs ?: 0L
    val movedAt = maxOf(malUpdated, ourLastWrite)
    val movedBy = when {
        movedAt == 0L -> null
        // MAL stamps `updated_at` when the row changes, so a stamp landing on
        // this app's own write is this app's move; anything else was made
        // somewhere else — the web client, the phone, MAL's own app.
        ourLastWrite > 0L && malUpdated - ourLastWrite <= MOVED_BY_GRACE_MS -> MalMovement.THIS_APP
        else -> MalMovement.SOMEWHERE_ELSE
    }
    val knownEpisode = sent.mapNotNull { it.episode }.maxOrNull()
        ?: attempt.episode?.takeIf { attempt.succeeded }

    // Why the last attempt failed, or — when it did not — the two ways a list
    // can still disagree with a write that was accepted: the row is not there,
    // or it is there and its count is behind what this app sent. Asking MAL a
    // question it says yes to while its answer says something else is precisely
    // the silent failure this panel exists to print.
    val troubles = if (!attempt.succeeded) {
        listOf(attempt.detail ?: "The write did not reach MyAnimeList.")
    } else {
        buildList {
            if (membership == MalMembership.NOT_ON_LIST) {
                add("This app wrote to it, but MyAnimeList's list does not hold it.")
            }
            if (knownEpisode != null && entry != null && entry.watchedEpisodes < knownEpisode) {
                add(
                    "MyAnimeList still says ${entry.watchedEpisodes} " +
                        "episode${if (entry.watchedEpisodes == 1) "" else "s"}; " +
                        "this app reached $knownEpisode.",
                )
            }
        }
    }

    return MalTrackedTitle(
        title = entry?.title?.takeIf(String::isNotBlank) ?: attempt.title,
        malId = attempt.malId,
        membership = membership,
        status = entry?.status,
        watchedEpisodes = entry?.watchedEpisodes ?: 0,
        episodesTotal = entry?.episodesTotal ?: 0,
        lastMovedAtEpochMs = movedAt,
        lastMovedBy = movedBy,
        lastAttempt = attempt,
        troubles = troubles,
    )
}

/** MAL's own spellings, in the words this fork's MAL surface uses for them. */
internal fun statusLabel(status: String): String = when (status) {
    MalListStatus.WATCHING -> "Watching"
    MalListStatus.COMPLETED -> "Completed"
    MalListStatus.ON_HOLD -> "On hold"
    MalListStatus.DROPPED -> "Dropped"
    MalListStatus.PLAN_TO_WATCH -> "Plan to watch"
    else -> status
}

/**
 * The one line the connection card shows when the panel has something to say,
 * or null when every tracked title is where it should be. Null is the common
 * answer, and the point: a card that only ever speaks up when something is
 * wrong is a card worth reading.
 */
internal fun malTroubleSummary(rows: List<MalTrackedTitle>): String? {
    val troubled = rows.count { it.troubles.isNotEmpty() }
    if (troubled == 0) return null
    val plural = if (troubled == 1) "title has" else "titles have"
    return "$troubled $plural not reached MyAnimeList — see MyAnimeList activity."
}

/** How close MAL's stamp has to be to ours to count as our own move. */
private const val MOVED_BY_GRACE_MS = 2 * 60_000L

/**
 * How long ago, in words: the panel's whole subject is *when* something last
 * moved, and an epoch millisecond is not an answer a person reads.
 */
internal fun agoInWords(epochMs: Long, nowEpochMs: Long = epochMs()): String {
    val delta = nowEpochMs - epochMs
    if (epochMs <= 0L || delta < 0L) return "just now"
    val minutes = delta / 60_000L
    if (minutes < 1L) return "just now"
    if (minutes == 1L) return "1 minute ago"
    // An hour is the moment minutes stop being the unit: "60 minutes ago" is
    // the same fact told worse.
    if (minutes < 60L) return "$minutes minutes ago"
    val hours = delta / 3_600_000L
    if (hours == 1L) return "1 hour ago"
    // A day is where hours stop being the unit: "24 hours ago" is the same
    // fact told roundaboutly.
    if (hours < 24L) return "$hours hours ago"
    val days = delta / 86_400_000L
    if (days == 1L) return "1 day ago"
    if (days <= 45L) return "$days days ago"
    return "${days / 7} weeks ago"
}
