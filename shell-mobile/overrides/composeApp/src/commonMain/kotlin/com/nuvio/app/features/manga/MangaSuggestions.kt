package com.nuvio.app.features.manga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One title other readers have put in front of everybody.
 *
 * The feed is grouped by the account, not by the client: the same title
 * recommended by four readers is one row with `readers = 4`, and `mine` says
 * whether the reader holding this phone is one of the four. A user id never
 * reaches the client, which is why the row can be shared with everybody.
 */
@Serializable
internal data class MangaSuggestion(
    @SerialName("manga_id") val id: String = "",
    val source: String = "",
    val title: String = "",
    val poster: String = "",
    @SerialName("source_name") val sourceName: String = "",
    val language: String = "",
    val readers: Long = 0L,
    val mine: Boolean = false,
    @SerialName("recommended_at") val recommendedAt: String = "",
) {
    /** The catalogue, as a line under the title; blank when nobody said. */
    val sourceLabel: String
        get() = sourceName.ifBlank { source }
}

/**
 * The row this device writes. `user_id` is deliberately absent: the column's
 * default is `auth.uid()` and the table's policy checks the same value, so the
 * account cannot be spoofed from the client even if this file were changed.
 */
@Serializable
private data class MangaSuggestionRow(
    @SerialName("manga_id") val mangaId: String = "",
    val source: String = "",
    val title: String = "",
    val poster: String = "",
    @SerialName("source_name") val sourceName: String = "",
    val language: String = "",
)

/** How long a loaded feed is trusted before it is read again. */
private const val FEED_TTL_MS = 45_000L

/**
 * The global suggestion list: manga any reader wants to recommend to everyone.
 *
 * Unlike the shelf (which is this phone's private copy in `MangaApi.library`),
 * this list lives in the account's Supabase project and has one shared truth.
 * Reading needs no account, so a signed-out reader still sees what the
 * community recommends. Writing does: a recommendation has to belong to
 * somebody, both so the list can say how many readers are behind a title and so
 * the reader can take their own back.
 *
 * The feed comes from the `manga_suggestion_feed` view rather than from the
 * table: the view groups rows by title and source, counts the readers and
 * returns `mine` for the caller. Grouping on the server is what keeps two
 * readers' recommendations from drawing the same title twice, and it is also
 * what keeps their user ids off the wire.
 */
internal object MangaSuggestions {

    private val log = Logger.withTag("MangaSuggestions")
    private val mutex = Mutex()

    /** The feed as last read, in the order the server ranked it. */
    var items: List<MangaSuggestion> by mutableStateOf(emptyList())
        private set

    var loading: Boolean by mutableStateOf(false)
        private set

    /** Why the last read failed, or null. A failed read keeps the old list. */
    var error: String? by mutableStateOf(null)
        private set

    private var loadedAt: Long = 0L

    /** True when a real account is signed in; anonymous profiles cannot write. */
    val canRecommend: Boolean
        get() {
            val state = AuthRepository.state.value
            return state is AuthState.Authenticated && !state.isAnonymous
        }

    /** Whether the suggestion for [id] and [source] belongs to this reader. */
    fun isMine(id: String, source: String): Boolean {
        val row = items.firstOrNull { it.id == id && it.source == source }
            ?: items.firstOrNull { it.id == id }
        return row?.mine == true
    }

    /** How many readers recommend this title on this source / any source. */
    fun readersOf(id: String, source: String): Long {
        val row = items.firstOrNull { it.id == id && it.source == source }
            ?: items.firstOrNull { it.id == id }
        return row?.readers ?: 0L
    }

    /**
     * Read the shared feed.
     *
     * [force] skips the short cache; a pull after this reader changed the list
     * has to see their own write, so it always forces.
     */
    suspend fun load(force: Boolean = false) {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (!force && loadedAt > 0L && now - loadedAt < FEED_TTL_MS) return
        mutex.withLock {
            // A second caller may have loaded it while this one waited for the
            // lock; reading again would be a duplicate round trip.
            val settled = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (!force && loadedAt > 0L && settled - loadedAt < FEED_TTL_MS) return
            loading = true
            error = null
            try {
                val rows = SupabaseProvider.client.postgrest
                    .from("manga_suggestion_feed")
                    .select {
                        order("readers", Order.DESCENDING)
                        order("recommended_at", Order.DESCENDING)
                        limit(120)
                    }
                    .decodeList<MangaSuggestion>()
                items = rows.filter { it.id.isNotBlank() }
                loadedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the last good list on screen. An offline rail with a
                // stale list is more useful than an empty one, and the screen
                // says the refresh did not answer.
                error = e.message ?: "The suggestions did not load."
                log.w(e) { "could not read the suggestion feed" }
            } finally {
                loading = false
            }
        }
    }

    /**
     * Put this title in front of everybody, or refresh this reader's row when it
     * is already there.
     *
     * The upsert is keyed on the same three columns as the table's primary key,
     * so a second tap updates the poster or title instead of drawing the same
     * series twice. The timestamp is left alone: "recommended since" should not
     * move because a poster changed.
     */
    suspend fun recommend(
        id: String,
        title: String,
        poster: String,
        source: String,
        sourceName: String,
        language: String,
    ): Result<Unit> {
        if (id.isBlank()) return Result.failure(IllegalStateException("This title has no id."))
        if (!canRecommend) return Result.failure(IllegalStateException(SIGN_IN_HINT))
        val row = MangaSuggestionRow(
            mangaId = id,
            source = source,
            title = title,
            poster = poster,
            sourceName = sourceName,
            language = language,
        )
        val result = try {
            SupabaseProvider.client.postgrest
                .from("manga_suggestions")
                .upsert(row) {
                    onConflict = "user_id,manga_id,source"
                }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "could not recommend $id" }
            Result.failure(e)
        }
        if (result.isSuccess) load(force = true)
        return result
    }

    /**
     * Take this reader's recommendation back.
     *
     * The delete names the title and source, not the user: the table's policy
     * is what limits the statement to this reader's own row, so a client cannot
     * remove somebody else's recommendation even by sending their row id.
     */
    suspend fun withdraw(id: String, source: String): Result<Unit> {
        if (!canRecommend) return Result.failure(IllegalStateException(SIGN_IN_HINT))
        val result = try {
            SupabaseProvider.client.postgrest
                .from("manga_suggestions")
                .delete {
                    filter {
                        eq("manga_id", id)
                        eq("source", source)
                    }
                }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "could not withdraw $id" }
            Result.failure(e)
        }
        if (result.isSuccess) load(force = true)
        return result
    }

    /** Shown under the action when a write needs an account. */
    const val SIGN_IN_HINT: String =
        "Sign in from Settings, Account, to recommend titles to everyone."
}

/** "1 reader" / "4 readers" / "Be the first", for a card's detail line. */
internal fun readerCountLabel(readers: Long): String = when (readers) {
    0L -> "Be the first"
    1L -> "1 reader"
    else -> "$readers readers"
}

/** The short line under a suggestion poster. */
internal fun suggestionDetail(suggestion: MangaSuggestion): String =
    if (suggestion.mine) "You recommend it" else readerCountLabel(suggestion.readers)

/**
 * What the title page says under its recommendation action.
 *
 * The count includes this reader when [mine] is true, so "You and 3 others"
 * doubles it if it also says "4 readers". One sentence, one total.
 */
internal fun recommendationLine(mine: Boolean, readers: Long): String = when {
    mine && readers <= 1L -> "You recommend it"
    mine && readers == 2L -> "You and 1 other recommend it"
    mine -> "You and ${readers - 1L} others recommend it"
    readers == 0L -> "Be the first to recommend it"
    readers == 1L -> "1 reader recommends it"
    else -> "$readers readers recommend it"
}
