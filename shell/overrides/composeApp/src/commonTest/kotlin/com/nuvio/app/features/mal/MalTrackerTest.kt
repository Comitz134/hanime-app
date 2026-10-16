// MyAnimeList, the parts that decide what gets written and to which title
// (features/mal).
//
// The login's browser leg crosses a real browser and this project's server,
// so what is pinned here is everything around it: the authorize URL MAL's
// consent page is built from, the id arithmetic that turns this app's anime
// ids into MAL's, and — through the transport seam every MAL-shaped request
// leaves through — exactly which writes a watched episode produces.
//
// Each guard test is written so that removing the guard fails the test: the
// transport records what would have been sent, and an empty recording is the
// assertion.

package com.nuvio.app.features.mal

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MalTrackerTest {

    private val realTransport = MalApi.transport
    private val realTokenStore = MalTracker.tokenStore
    private val realJournalStore = MalActivity.store

    private lateinit var transport: RecordingTransport
    private lateinit var tokens: FakeTokenStore
    private lateinit var journal: FakeJournalStore

    @BeforeTest
    fun installFakes() {
        transport = RecordingTransport()
        tokens = FakeTokenStore()
        journal = FakeJournalStore()
        MalApi.transport = transport
        MalTracker.tokenStore = tokens
        // The activity journal writes where the panel reads: a fake here keeps
        // a test run out of the app's real data directory, and lets the filing
        // itself be asserted.
        MalActivity.store = journal
        MalActivity.forget()
    }

    @AfterTest
    fun restoreFakes() {
        MalApi.transport = realTransport
        MalTracker.tokenStore = realTokenStore
        MalActivity.forget()
        MalActivity.store = realJournalStore
    }

    // -------------------------------------------------------- the login leg

    @Test
    fun `the authorize url carries everything MAL's consent page needs`() {
        val verifier = MalApi.randomVerifier(64)
        val url = MalApi.authorizeUrl(state = "state-123", verifier = verifier)

        assertTrue(url.startsWith("https://myanimelist.net/v1/oauth2/authorize?"), url)
        assertTrue(url.contains("response_type=code"), url)
        assertTrue(url.contains("client_id=${MalApi.CLIENT_ID}"), url)
        // The redirect is this project's own server — the registered origin
        // both halves of the login share.
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8787%2F"), url)
        // MAL supports only the plain challenge, so the challenge is the
        // verifier itself, and the state travels for the CSRF half.
        assertTrue(url.contains("code_challenge_method=plain"), url)
        assertTrue(url.contains("code_challenge=$verifier"), url)
        assertTrue(url.contains("state=state-123"), url)
    }

    @Test
    fun `a verifier is long enough and stays inside the unreserved alphabet`() {
        val verifier = MalApi.randomVerifier(64)
        assertEquals(64, verifier.length)
        // RFC 7636: 43–128 characters of the unreserved set — MAL's own
        // error text names the same bounds.
        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" }, verifier)
        // Two verifiers are not the same verifier.
        assertTrue(MalApi.randomVerifier(64) != MalApi.randomVerifier(64))
    }

    // ----------------------------------------------------------- id arithmetic

    @Test
    fun `an anime id becomes a mal id from whatever shape it arrived in`() {
        // The identity the scrobble event carries for this project's anime.
        assertEquals(21L, MalTracker.anilistIdFrom("ha:an:21"))
        // The same id as an episode's playback id.
        assertEquals(21L, MalTracker.anilistIdFrom("ha:an:21:1:5"))
        // Stripped variants a client may hand over.
        assertEquals(21L, MalTracker.anilistIdFrom("an:21"))
        assertEquals(21L, MalTracker.anilistIdFrom("21"))
        // A film id is not an anime id, and nonsense is nothing.
        assertNull(MalTracker.anilistIdFrom("ha:fm:the-matrix-603"))
        assertNull(MalTracker.anilistIdFrom(""))
        assertNull(MalTracker.anilistIdFrom("ha:an:"))
    }

    @Test
    fun `an id that is already mal's is used as it is`() = runBlocking {
        seedAccount(profileId = PROFILE_A)
        val media = buildTrackingMediaReference(contentType = "anime", parentMetaId = "mal:42")
        // No lookup is asked for: the event already carried MAL's own id.
        assertEquals(42L, MalTracker.resolveMalId(media))
        assertTrue(transport.calls.none { it.url.contains("/api/anime/") })
    }

    // ------------------------------------------------------- the write guard

    @Test
    fun `a playback start never writes anything`() = runBlocking {
        seedAccount(profileId = PROFILE_B)
        val media = episodeMedia(malId = 41, episode = 3)
        MalTracker.scrobble(
            profileId = PROFILE_B,
            action = TrackingScrobbleAction.START,
            event = TrackingScrobbleEvent(media = media, progressPercent = 95.0),
        )
        // Removing the START guard fails this: a write would be recorded
        // here, and a show opened and closed is not a show watched.
        assertTrue(transport.calls.isEmpty(), "START recorded ${transport.calls.size} calls")
    }

    @Test
    fun `a stop before the watched threshold writes nothing`() = runBlocking {
        seedAccount(profileId = PROFILE_C)
        val media = episodeMedia(malId = 42, episode = 3)
        MalTracker.scrobble(
            profileId = PROFILE_C,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = media, progressPercent = 25.0),
        )
        assertTrue(transport.calls.isEmpty(), "a 25% stop recorded ${transport.calls.size} calls")
    }

    @Test
    fun `an episode without a number is not a watched episode`() = runBlocking {
        seedAccount(profileId = PROFILE_D)
        val media = buildTrackingMediaReference(contentType = "anime", parentMetaId = "mal:43")
        MalTracker.scrobble(
            profileId = PROFILE_D,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = media, progressPercent = 95.0),
        )
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun `an unlinked account writes nothing`() = runBlocking {
        // No tokens seeded: the write is skipped rather than attempted with
        // no credential.
        val media = episodeMedia(malId = 45, episode = 2)
        MalTracker.scrobble(
            profileId = PROFILE_E,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = media, progressPercent = 95.0),
        )
        assertTrue(transport.calls.isEmpty())
        // Skipped, but not silently: an episode played with no account linked
        // is a fact the activity panel is allowed to repeat.
        val filed = MalActivity.attempts.value.single()
        assertEquals(MalTracker.NOT_LINKED_MESSAGE, filed.detail)
        assertFalse(filed.succeeded)
    }

    // --------------------------------------------------------- the write itself

    @Test
    fun `a finished episode is written to mal with this account's own token`() = runBlocking {
        seedAccount(profileId = PROFILE_F)
        val media = episodeMedia(malId = 44, episode = 3)
        MalTracker.scrobble(
            profileId = PROFILE_F,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = media, progressPercent = 92.0),
        )

        // The title is read before it is written: its own episode total is what
        // tells the last episode from the third, and MAL does not finish a
        // title by itself when a count reaches the total (see the completion
        // tests below).
        val read = transport.calls.first { it.method == "GET" }
        assertTrue(read.url.contains("/api/mal/v2/anime/44?fields="), read.url)
        assertTrue(read.url.contains("num_episodes"), read.url)
        assertEquals("Bearer $ACCESS_TOKEN", read.headers["authorization"])

        val put = transport.calls.single { it.method == "PUT" }
        assertTrue(put.url.contains("/api/mal/v2/anime/44/my_list_status?"), put.url)
        assertTrue(put.url.contains("num_watched_episodes=3"), put.url)
        assertEquals("Bearer $ACCESS_TOKEN", put.headers["authorization"])
        // Third episode of a title MAL was never asked about: nothing here says
        // it is the last one, so it stays a watched episode of a series being
        // watched.
        assertTrue(put.url.contains("status=watching"), put.url)
        // And the read is what the write is decided on, so it comes first.
        assertTrue(
            transport.calls.indexOf(read) < transport.calls.indexOf(put),
            "the title was read after the write: ${transport.calls.map { it.method + ' ' + it.url }}",
        )
    }

    @Test
    fun `the same episode is not written twice in a row`() = runBlocking {
        seedAccount(profileId = PROFILE_G)
        val media = episodeMedia(malId = 46, episode = 4)
        repeat(2) {
            MalTracker.scrobble(
                profileId = PROFILE_G,
                action = TrackingScrobbleAction.STOP,
                event = TrackingScrobbleEvent(media = media, progressPercent = 95.0),
            )
        }
        // A pause and a stop at the end of one episode are one event to MAL —
        // and the second one is not even read for, because the guard stands
        // before the read as it stood before the write.
        assertEquals(1, transport.calls.count { it.method == "PUT" })
        assertEquals(1, transport.calls.count { it.method == "GET" })
    }

    // ----------------------------------------------------------- finishing a title

    /**
     * The last episode finishes the title, on MAL's list as much as here.
     *
     * MAL holds a status and a count in separate fields and derives neither
     * from the other, so a show watched to its end used to sit on the reader's
     * list as **Watching 12/12** — the count at the total, the status still
     * watching — until somebody opened MAL and changed it by hand. The total
     * MAL answers is what makes the twelfth episode tellable from the eleventh,
     * and it travels with the write.
     */
    @Test
    fun `the last episode is written as completed`() = runBlocking {
        seedAccount(profileId = PROFILE_O)
        transport.answers["num_episodes"] =
            """{"num_episodes":12,"my_list_status":{"status":"watching","num_episodes_watched":11}}"""

        MalTracker.scrobble(
            profileId = PROFILE_O,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(malId = 60, episode = 12), progressPercent = 96.0),
        )

        val put = transport.calls.single { it.method == "PUT" }
        assertTrue(put.url.contains("status=completed"), put.url)
        assertTrue(put.url.contains("num_watched_episodes=12"), put.url)
    }

    @Test
    fun `the episode before the last is still just a watched episode`() = runBlocking {
        seedAccount(profileId = PROFILE_P)
        transport.answers["num_episodes"] =
            """{"num_episodes":12,"my_list_status":{"status":"watching","num_episodes_watched":10}}"""

        MalTracker.scrobble(
            profileId = PROFILE_P,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(malId = 61, episode = 11), progressPercent = 96.0),
        )

        val put = transport.calls.single { it.method == "PUT" }
        assertTrue(put.url.contains("status=watching"), put.url)
        assertTrue(put.url.contains("num_watched_episodes=11"), put.url)
    }

    @Test
    fun `a title with no total is never the last episode`() = runBlocking {
        seedAccount(profileId = PROFILE_Q)
        // An ongoing series answers `num_episodes: 0` — verified against the
        // live API, which describes One Piece exactly that way — and an episode
        // cannot be the last of a total nobody has.
        transport.answers["num_episodes"] = """{"num_episodes":0}"""

        MalTracker.scrobble(
            profileId = PROFILE_Q,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(malId = 62, episode = 1102), progressPercent = 96.0),
        )

        val put = transport.calls.single { it.method == "PUT" }
        assertTrue(put.url.contains("status=watching"), put.url)
    }

    @Test
    fun `a title already finished is not moved back to watching`() = runBlocking {
        seedAccount(profileId = PROFILE_R)
        transport.answers["num_episodes"] =
            """{"num_episodes":12,"my_list_status":{"status":"completed","num_episodes_watched":12}}"""

        // Episode 3 again, months later: re-watching is not unwatching, and the
        // status the reader finished the show with is kept.
        MalTracker.scrobble(
            profileId = PROFILE_R,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(malId = 63, episode = 3), progressPercent = 96.0),
        )

        val put = transport.calls.single { it.method == "PUT" }
        assertTrue(put.url.contains("status=completed"), put.url)
        assertTrue(put.url.contains("num_watched_episodes=3"), put.url)
    }

    @Test
    fun `a title only planned enters the list as watching`() = runBlocking {
        seedAccount(profileId = PROFILE_S)
        transport.answers["num_episodes"] =
            """{"num_episodes":12,"my_list_status":{"status":"plan_to_watch","num_episodes_watched":0}}"""

        MalTracker.scrobble(
            profileId = PROFILE_S,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(malId = 64, episode = 1), progressPercent = 96.0),
        )

        val put = transport.calls.single { it.method == "PUT" }
        assertTrue(put.url.contains("status=watching"), put.url)
    }

    @Test
    fun `a title mal will not describe is still written`() = runBlocking {
        seedAccount(profileId = PROFILE_T)
        // The read fails — a blip, or a title MAL has no answer for — and the
        // write is not lost with it: the episode goes as a watched episode,
        // which is what this provider wrote before a total was ever asked for.
        transport.failures["/anime/65?fields="] = mutableListOf(500)

        MalTracker.scrobble(
            profileId = PROFILE_T,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(malId = 65, episode = 2), progressPercent = 96.0),
        )

        val put = transport.calls.single { it.method == "PUT" }
        assertTrue(put.url.contains("status=watching"), put.url)
        assertTrue(put.url.contains("num_watched_episodes=2"), put.url)
    }

    @Test
    fun `an anime id is looked up once and remembered`() = runBlocking {
        seedAccount(profileId = PROFILE_H)
        // This project's anime arrive as `ha:an:<anilist id>`; this project's
        // own server answers MAL's id for one, and the answer is remembered.
        transport.answers["/api/anime/21"] = """{"details":{"id":21,"malId":777}}"""

        MalTracker.scrobble(
            profileId = PROFILE_H,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(
                media = episodeMedia(anilistId = 21, episode = 5),
                progressPercent = 95.0,
            ),
        )
        MalTracker.scrobble(
            profileId = PROFILE_H,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(
                media = episodeMedia(anilistId = 21, episode = 6),
                progressPercent = 95.0,
            ),
        )

        val puts = transport.calls.filter { it.method == "PUT" }
        assertEquals(2, puts.size)
        assertTrue(puts.all { it.url.contains("/anime/777/my_list_status?") }, puts.toString())
        assertTrue(puts[0].url.contains("num_watched_episodes=5"), puts[0].url)
        assertTrue(puts[1].url.contains("num_watched_episodes=6"), puts[1].url)
        // One lookup for two writes: the mapping is cached, not re-asked.
        assertEquals(1, transport.calls.count { it.url.contains("/api/anime/21") })
    }

    // ---------------------------------------------- the rows the old rule left
    //
    // MAL derives no status from a count, so before this app wrote `completed`
    // with the last episode, a title watched to its end stayed on the reader's
    // list as "Watching 12/12": the count at the total, the status where it
    // was. Those rows are already on the account, where no rule about *new*
    // writes can reach them, so a read settles them — it is the one moment the
    // app holds both the row and the title's total at once.
    //
    // Each guard test is written so that removing the guard fails it: the
    // transport records what would have been sent, and an empty recording is
    // the assertion.

    @Test
    fun `a finished row still on watching is completed when the list is read`() = runBlocking {
        seedActiveAccount()
        transport.answers["/users/@me/animelist"] = listRead(ListRow(malId = 501))

        // The ordinary read, the one opening the shelf makes: nobody asked for
        // this write, and nobody should have to.
        MalLibrary.refresh(TrackingRefreshIntent.AUTOMATIC)

        val put = transport.calls.single { it.method == "PUT" }
        assertTrue(put.url.contains("/api/mal/v2/anime/501/my_list_status?"), put.url)
        assertTrue(put.url.contains("status=completed"), put.url)
        // Status and nothing else: the count MAL holds is already the total,
        // and MAL writes only the fields it is handed.
        assertFalse(put.url.contains("num_watched_episodes"), put.url)
        // The write is the answer — the shelf shows what MAL now holds without
        // a second read of it.
        assertEquals(MalListStatus.COMPLETED, MalLibrary.state.value.entries.single().status)
        assertNull(MalLibrary.state.value.error)
        // And the panel can say what moved and why, like any other write.
        val filed = MalActivity.attempts.value.single()
        assertEquals(501L, filed.malId)
        assertEquals(12, filed.episode)
        assertTrue(filed.succeeded)
        // Nothing for "Send now" to send: the row is where the app meant it.
        assertFalse(filed.isRetryable)
    }

    @Test
    fun `a title still being watched is left exactly where it is`() = runBlocking {
        seedActiveAccount()
        transport.answers["/users/@me/animelist"] = listRead(ListRow(malId = 502, watched = 11))

        MalLibrary.refresh(TrackingRefreshIntent.AUTOMATIC)

        assertTrue(transport.calls.none { it.method == "PUT" }, transport.calls.toString())
        assertEquals(MalListStatus.WATCHING, MalLibrary.state.value.entries.single().status)
        // Nothing is filed either: nothing was wrong, so nothing is owed.
        assertTrue(MalActivity.attempts.value.isEmpty())
    }

    @Test
    fun `a title the reader is re-watching is their own standing order`() = runBlocking {
        seedActiveAccount()
        // MAL's own flag for "I put this back on Watching", and it outranks
        // anything this app could infer from the numbers.
        transport.answers["/users/@me/animelist"] = listRead(ListRow(malId = 503, rewatching = true))

        MalLibrary.refresh(TrackingRefreshIntent.AUTOMATIC)

        assertTrue(transport.calls.none { it.method == "PUT" }, transport.calls.toString())
        assertEquals(MalListStatus.WATCHING, MalLibrary.state.value.entries.single().status)
    }

    @Test
    fun `a title with no known total is never finished for the reader`() = runBlocking {
        seedActiveAccount()
        // An ongoing series: MAL answers `0`, so a count of 12 is twelve of
        // however many there will turn out to be.
        transport.answers["/users/@me/animelist"] = listRead(ListRow(malId = 504, total = 0))

        MalLibrary.refresh(TrackingRefreshIntent.AUTOMATIC)

        assertTrue(transport.calls.none { it.method == "PUT" }, transport.calls.toString())
    }

    @Test
    fun `a row already completed is not written to again`() = runBlocking {
        seedActiveAccount()
        transport.answers["/users/@me/animelist"] =
            listRead(ListRow(malId = 505, status = MalListStatus.COMPLETED))

        MalLibrary.refresh(TrackingRefreshIntent.AUTOMATIC)

        // A repair that fired on every read is a write nobody asked for,
        // repeated forever; the rule is about rows MAL still has wrong.
        assertTrue(transport.calls.none { it.method == "PUT" }, transport.calls.toString())
    }

    @Test
    fun `a refused repair does not take the list down with it`() = runBlocking {
        seedActiveAccount()
        transport.answers["/users/@me/animelist"] = listRead(ListRow(malId = 506))
        transport.failures["/my_list_status?"] = mutableListOf(500)

        MalLibrary.refresh(TrackingRefreshIntent.AUTOMATIC)

        // The list in hand is good, so it is published: one bounced repair is
        // not a reason to hide the reader's own shelf behind an error.
        assertEquals(MalListStatus.WATCHING, MalLibrary.state.value.entries.single().status)
        assertNull(MalLibrary.state.value.error)
        // Filed, with the count it meant to write, so "Send now" re-sends it.
        val filed = MalActivity.attempts.value.single()
        assertEquals(506L, filed.malId)
        assertEquals(12, filed.episode)
        assertFalse(filed.succeeded)
        assertTrue(filed.isRetryable)
    }

    @Test
    fun `one read settles a bounded number of stale rows`() = runBlocking {
        seedActiveAccount()
        // Twelve rows left wrong at once: one read takes ten and leaves two
        // for the next, because a burst of writes is what MAL answers with
        // refusals — and a refusal here would be filed against a row that was
        // never wrong.
        transport.answers["/users/@me/animelist"] =
            listRead(*(600L..611L).map { ListRow(malId = it) }.toTypedArray())

        MalLibrary.refresh(TrackingRefreshIntent.AUTOMATIC)

        assertEquals(10, transport.calls.count { it.method == "PUT" })
        val entries = MalLibrary.state.value.entries
        assertEquals(12, entries.size)
        assertEquals(10, entries.count { it.status == MalListStatus.COMPLETED })
        // The two it did not reach keep the honest answer of this read.
        assertEquals(2, entries.count { it.status == MalListStatus.WATCHING })
    }

    // ------------------------------------------------- MAL refusing the token

    /**
     * The token this device holds can be one MyAnimeList has already thrown
     * away, whatever its own `expires_in` promised — the desktop app's first
     * month of tracking was exactly that, and it failed silently: the write was
     * refused, the warning went to a log nobody reads, and the list never moved
     * again. A 401 is therefore renewed and the call repeated, the way the web
     * client and the phone already do it.
     */
    @Test
    fun `a token mal refuses early is renewed and the write is retried`() = runBlocking {
        // Seeded with an hour of life left: nothing here refreshes on its own,
        // so the retry below can only come from the 401.
        seedAccount(profileId = PROFILE_I)
        transport.failures["/anime/47/my_list_status"] = mutableListOf(401)
        transport.answers["/api/mal/token"] =
            """{"access_token":"tok-renewed","refresh_token":"refresh-2","expires_in":3600}"""

        MalTracker.scrobble(
            profileId = PROFILE_I,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(malId = 47, episode = 2), progressPercent = 95.0),
        )

        val puts = transport.calls.filter { it.method == "PUT" }
        assertEquals(2, puts.size, "a refused write was not repeated")
        assertEquals("Bearer $ACCESS_TOKEN", puts[0].headers["authorization"])
        // The repeat carries the renewed token, not the one that was refused.
        assertEquals("Bearer tok-renewed", puts[1].headers["authorization"])
        assertEquals(1, transport.calls.count { it.url.contains("/api/mal/token") })
        assertTrue(MalTracker.uiState.value.errorMessage == null, "a recovered write reported an error")
    }

    @Test
    fun `a renewal mal refuses too drops the session and says so`() = runBlocking {
        seedAccount(profileId = PROFILE_J)
        transport.failures["/anime/48/my_list_status"] = mutableListOf(401)
        // MAL will not renew either: the link itself is gone.
        transport.failures["/api/mal/token"] = mutableListOf(401)

        MalTracker.scrobble(
            profileId = PROFILE_J,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(malId = 48, episode = 1), progressPercent = 95.0),
        )

        // Nothing pretends the account is still linked, and the reason is on
        // the card rather than in a log line.
        assertNull(tokens.values[PROFILE_J], "a dead session was left in storage")
        assertEquals(MalTracker.SESSION_EXPIRED_MESSAGE, MalTracker.uiState.value.errorMessage)
    }

    // ------------------------------------------------------- the activity panel
    //
    // Everything the panel can show comes from what the tracker files here, so
    // each way a watched episode can end is pinned: sent, refused by MAL, never
    // addressable, and not attempted at all because no account is linked. The
    // last two were silent returns, which is how tracking stopped being visible.

    @Test
    fun `a sent episode is filed as sent, with what it reached`() = runBlocking {
        seedAccount(profileId = PROFILE_K)
        MalTracker.scrobble(
            profileId = PROFILE_K,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(malId = 51, episode = 4), progressPercent = 95.0),
        )

        val filed = MalActivity.attempts.value.single()
        assertEquals(51L, filed.malId)
        assertEquals(4, filed.episode)
        assertTrue(filed.succeeded)
        assertNull(filed.detail)
    }

    @Test
    fun `a title mal cannot place is filed rather than forgotten`() = runBlocking {
        seedAccount(profileId = PROFILE_L)
        // The lookup this project's own server answers carries no MAL id, so
        // there was never an entry to write to.
        transport.answers["/api/anime/"] = "{}"

        MalTracker.scrobble(
            profileId = PROFILE_L,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(anilistId = 777, episode = 1), progressPercent = 95.0),
        )

        val filed = MalActivity.attempts.value.single()
        assertNull(filed.malId)
        assertEquals(1, filed.episode)
        assertEquals(MalTracker.NO_MATCH_MESSAGE, filed.detail)
        assertTrue(transport.calls.none { it.method == "PUT" })
        // Nothing to address, so nothing the panel could offer to send again.
        assertFalse(filed.isRetryable)
    }

    @Test
    fun `a write mal refuses is filed in mal's own words`() = runBlocking {
        seedAccount(profileId = PROFILE_M)
        transport.failures["/anime/55/my_list_status"] = mutableListOf(401)
        // MAL will not renew either: the link itself is gone, which is the case
        // the panel has to be able to explain afterwards.
        transport.failures["/api/mal/token"] = mutableListOf(401)

        MalTracker.scrobble(
            profileId = PROFILE_M,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(malId = 55, episode = 2), progressPercent = 95.0),
        )

        val filed = MalActivity.attempts.value.single()
        assertEquals(55L, filed.malId)
        assertFalse(filed.succeeded)
        assertTrue(filed.detail.orEmpty().contains("401"), filed.detail)
        assertTrue(filed.isRetryable, "a title MAL knows should be sendable again")
    }

    @Test
    fun `the panel can send a refused episode again once the link is back`() = runBlocking {
        // The re-send writes for the profile the app is on — the same one the
        // panel is showing — so this is the profile its tokens go to.
        val profileId = ProfileRepository.activeProfileId
        seedAccount(profileId)
        transport.failures["/anime/56/my_list_status"] = mutableListOf(401)
        transport.failures["/api/mal/token"] = mutableListOf(401)
        MalTracker.scrobble(
            profileId = profileId,
            action = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(media = episodeMedia(malId = 56, episode = 3), progressPercent = 95.0),
        )
        val refused = MalActivity.attempts.value.single()
        assertFalse(refused.succeeded)

        // The reader links again — the tokens go straight into the store, so
        // the journal the panel is reading survives the re-link.
        tokens.write(
            profileId,
            """{"accessToken":"$ACCESS_TOKEN","refreshToken":"refresh-1","expiresAtEpochMs":${epochMs() + 3_600_000L}}""",
        )

        assertTrue(MalTracker.retry(refused))

        val puts = transport.calls.filter { it.method == "PUT" }
        assertEquals(2, puts.size, "the refused write was not repeated")
        assertTrue(puts.last().url.contains("num_watched_episodes=3"), puts.last().url)
        val filed = MalActivity.attempts.value.single()
        assertTrue(filed.succeeded)
        assertNull(filed.detail)
    }

    @Test
    fun `a title with nothing to address is not sent again`() = runBlocking {
        seedAccount(profileId = PROFILE_N)
        val nothingToSend = MalWriteAttempt(
            title = "Some Show",
            malId = null,
            episode = 4,
            attemptedAtEpochMs = epochMs(),
            succeeded = false,
            detail = MalTracker.NO_MATCH_MESSAGE,
        )
        assertFalse(MalTracker.retry(nothingToSend))
        assertTrue(transport.calls.isEmpty())
    }

    // ------------------------------------------------------- reading the list

    /**
     * A row read back, in the shape the live API actually sends it. MAL names
     * progress twice — `num_episodes_watched` when it answers, and
     * `num_watched_episodes` when it is asked to write — and this client read
     * the write spelling on a read. MAL answers an unknown field with 200 and no
     * key, so every shelf row silently said "0 / 12 ep" however much had been
     * watched, and the count was then written back as "nothing watched".
     *
     * The body below is verbatim from `/v2/users/@me/animelist` on the reader's
     * own account (trimmed to the fields this client asks for).
     */
    @Test
    fun `a list row is read with the field mal actually sends`() = runBlocking {
        transport.answers["/users/@me/animelist"] = """
            {"data":[{"node":{"id":11617,"title":"High School DxD","num_episodes":12,
            "main_picture":{"medium":"https://cdn.myanimelist.net/i/1.jpg"}},
            "list_status":{"status":"watching","score":0,"num_episodes_watched":7,
            "is_rewatching":false,"updated_at":"2026-10-11T02:02:10+00:00"}}],
            "paging":{}}
        """.trimIndent()

        val entries = MalApi.fetchAnimeList("tok-test-1")

        val row = entries.single()
        assertEquals(11617L, row.malId)
        assertEquals("High School DxD", row.title)
        assertEquals(MalListStatus.WATCHING, row.status)
        assertEquals(12, row.episodesTotal)
        // The count MAL sent, not the zero a wrong field name produces.
        assertEquals(7, row.watchedEpisodes, "the read used the write spelling of progress")
        assertFalse(row.isRewatching)
        // The read is asked for MAL's re-watch flag as well. Dropping it from
        // the request would not fail anything else here — the wire would simply
        // answer without it — and the guard that keeps a finished row a reader
        // is deliberately re-watching out of the read's own repairs
        // (MalLibrary.settleFinishedRows) would quietly stop working.
        val read = transport.calls.single { it.method == "GET" }
        assertTrue(read.url.contains("is_rewatching"), read.url)
        assertTrue(read.url.contains("num_episodes_watched"), read.url)
    }

    // --------------------------------------------------------- the list shape

    @Test
    fun `a row carries the id space the addon answers`() {
        val entry = MalListEntry(malId = 21, title = "Test", status = MalListStatus.WATCHING)
        assertEquals("mal:21", entry.contentId)
        assertEquals("mal:watching", MalListStatus.tabKey(MalListStatus.WATCHING))
    }

    @Test
    fun `membership asks for the one status mal keeps a title in`() {
        assertEquals(
            MalListStatus.WATCHING,
            MalLibrary.desiredStatus(mapOf("mal:watching" to true, "mal:completed" to false)),
        )
        // Nothing checked means the row leaves the list, which is a removal
        // rather than a status — the caller treats null as exactly that.
        assertNull(MalLibrary.desiredStatus(mapOf("mal:watching" to false)))
        assertNull(MalLibrary.desiredStatus(emptyMap()))
    }

    @Test
    fun `the tracker knows whether an account is linked before it is asked`() {
        // The registry path the settings card reads: an unlinked profile is
        // simply not authenticated, never an error.
        MalTracker.ensureLoaded()
        assertNotNull(MalTracker.descriptor)
        assertEquals("MyAnimeList", MalTracker.descriptor.displayName)
    }

    // --------------------------------------------------------- the library's
    // A title saved to the reader's library is a plan, and MyAnimeList is
    // where this app keeps a plan: every add is written as Plan to watch. What
    // is pinned here is how careful that write has to be — it must never move
    // a row the reader already has, must stay quiet about the titles this
    // provider does not track (films, series, adult videos), and must leave a
    // record when it could not do its job.

    @Test
    fun `a library add lands on mal's plan to watch`() = runBlocking {
        seedActiveAccount()
        // The catalogue id is AniList's; MAL's id comes from this project's own
        // server, the same hop a scrobble makes.
        // An AniList id no other test in this class uses: the id lookup is
        // cached for the life of the process, so a shared one would make the
        // arithmetic test below read a mapping this test taught it.
        transport.answers["/api/anime/8777"] = """{"details":{"malId":9001}}"""
        // Not on the list: MAL answers the title without a my_list_status.
        transport.answers["/anime/9001?"] = """{"num_episodes":12}"""

        MalLibrary.planAddedItem(libraryItem(id = "ha:an:" + 8777, name = "Attack on Titan"))

        val puts = transport.calls.filter { it.method == "PUT" }
        assertEquals(1, puts.size, "the library add wrote nothing")
        assertTrue(puts.single().url.contains("/anime/9001/my_list_status?"), puts.single().url)
        assertTrue(puts.single().url.contains("status=plan_to_watch"), puts.single().url)
        // Status and nothing else: an episode count here would claim episodes
        // nobody has watched.
        assertFalse(puts.single().url.contains("num_watched_episodes"), puts.single().url)

        val filed = MalActivity.attempts.value.single()
        assertEquals(9001L, filed.malId)
        assertNull(filed.episode)
        assertTrue(filed.succeeded)
        assertNull(filed.detail)
        // A plan is not an episode: there is nothing for "Send now" to send.
        assertFalse(filed.isRetryable)
    }

    @Test
    fun `a title mal already holds keeps the status it has`() = runBlocking {
        seedActiveAccount()
        transport.answers["/api/anime/8778"] = """{"details":{"malId":9002}}"""
        // Finished, on the reader's own list. Saving it to a library again is
        // not a reason to move it back to Plan to watch.
        transport.answers["/anime/9002?"] =
            """{"num_episodes":12,"my_list_status":{"status":"completed","num_episodes_watched":12}}"""

        MalLibrary.planAddedItem(libraryItem(id = "ha:an:8778", name = "Finished Show"))

        assertTrue(transport.calls.none { it.method == "PUT" }, transport.calls.toString())
        // And nothing is filed either: nothing was wrong, and nothing was owed.
        assertTrue(MalActivity.attempts.value.isEmpty())
    }

    @Test
    fun `a title that is not an anime is never asked about`() = runBlocking {
        seedActiveAccount()

        // A series from the films area, and a video from the 18+ shelf: the
        // reader's library holds both, and neither has a MyAnimeList row.
        MalLibrary.planAddedItem(
            libraryItem(id = "ha:ft:breaking-bad-1396", name = "Breaking Bad", type = "series"),
        )
        MalLibrary.planAddedItem(
            libraryItem(id = "ha:ad:some-slug", name = "Some Adult Video", type = "movie"),
        )

        assertTrue(transport.calls.isEmpty(), transport.calls.toString())
        assertTrue(MalActivity.attempts.value.isEmpty())
    }

    @Test
    fun `a plan that mal could not be asked about is filed, not guessed at`() = runBlocking {
        seedActiveAccount()
        transport.answers["/api/anime/8779"] = """{"details":{"malId":9003}}"""
        // The read of the title itself fails: whether the reader already has a
        // row is unknown, and writing over an unknown row could move a title
        // they had finished — so nothing is written, and the panel is told.
        transport.failures["/anime/9003?"] = mutableListOf(500)

        MalLibrary.planAddedItem(libraryItem(id = "ha:an:8779", name = "Unreadable"))

        assertTrue(transport.calls.none { it.method == "PUT" }, transport.calls.toString())
        val filed = MalActivity.attempts.value.single()
        assertEquals(9003L, filed.malId)
        assertFalse(filed.succeeded)
        // In words the panel can print, like the other "nothing was sent"
        // reasons: this is a refusal to guess, not MAL's own error text.
        assertTrue(filed.detail.orEmpty().contains("could not be asked"), filed.detail)
    }

    @Test
    fun `a library add with no account linked writes nothing`() = runBlocking {
        // No tokens for the account the app is on, and the app knows it.
        MalTracker.removeStoredProfile(ProfileRepository.activeProfileId)
        MalTracker.ensureLoaded()
        MalLibrary.forget()
        assertFalse(MalTracker.isAuthenticated.value)

        MalLibrary.planAddedItem(libraryItem(id = "ha:an:8780", name = "Unlinked"))

        assertTrue(transport.calls.isEmpty(), transport.calls.toString())
        // Quiet on purpose: the library is still the reader's own, and the
        // settings card already says the account is not linked.
        assertTrue(MalActivity.attempts.value.isEmpty())
    }

    @Test
    fun `an add from mal's own shelf is a plan, not a show in progress`() {
        // Nothing checked: the title is being added to the library.
        val added = MalLibraryProvider.toggledDefaultMembership(
            mapOf("mal:watching" to false, "mal:completed" to false),
        )
        assertTrue(added["mal:plan_to_watch"] == true, added.toString())
        assertFalse(added["mal:watching"] == true, added.toString())

        // A status the reader already has is restated, not replaced.
        val kept = MalLibraryProvider.toggledDefaultMembership(mapOf("mal:completed" to true))
        assertTrue(kept["mal:completed"] == true, kept.toString())
        assertFalse(kept["mal:watching"] == true, kept.toString())
    }

    @Test
    fun `the library announces a title it gained`() {
        val seen = mutableListOf<String>()
        TrackingProviderRegistry.registerLibraryAddObserver { item -> seen += item.id }

        TrackingProviderRegistry.notifyLibraryItemAdded(libraryItem(id = "ha:an:8781", name = "Saved"))

        assertEquals(listOf("ha:an:8781"), seen)
    }

    // --------------------------------------------------------------- fixtures

    private fun libraryItem(id: String, name: String, type: String = "series"): LibraryItem =
        LibraryItem(id = id, type = type, name = name, savedAtEpochMs = epochMs())

    /**
     * The account the app is on, linked, with no list of its own in memory —
     * the floor the plan tests stand on, since both guards in
     * `planAddedItem` (linked, list not read) would otherwise be decided by
     * whatever test ran before this one.
     */
    private fun seedActiveAccount() {
        seedAccount(ProfileRepository.activeProfileId)
        MalTracker.ensureLoaded()
        MalLibrary.forget()
    }

    private fun episodeMedia(malId: Long? = null, anilistId: Long? = null, episode: Int) =
        buildTrackingMediaReference(
            contentType = "anime",
            parentMetaId = malId?.let { "mal:$it" } ?: "ha:an:$anilistId",
            episodeNumber = episode,
        )

    /** A linked account, whose token is far from expiry. */
    private fun seedAccount(profileId: Int) {
        MalTracker.removeStoredProfile(profileId)
        tokens.write(
            profileId,
            """{"accessToken":"$ACCESS_TOKEN","refreshToken":"refresh-1","expiresAtEpochMs":${epochMs() + 3_600_000L}}""",
        )
    }

    /**
     * One list read, in MAL's own shape: the title's total beside the account's
     * own `list_status`, carrying MAL's *reading* spelling of the count — the
     * one a read actually answers — and the re-watch flag, which is what tells
     * a stale row from a deliberate one.
     */
    private data class ListRow(
        val malId: Long,
        val total: Int = 12,
        val watched: Int = 12,
        val status: String = MalListStatus.WATCHING,
        val rewatching: Boolean = false,
    )

    private fun listRead(vararg rows: ListRow): String {
        val data = rows.joinToString(",") { row ->
            """{"node":{"id":${row.malId},"title":"Show ${row.malId}","num_episodes":${row.total},"main_picture":{"medium":"https://cdn.myanimelist.net/i/1.jpg"}},"list_status":{"status":"${row.status}","score":0,"num_episodes_watched":${row.watched},"is_rewatching":${row.rewatching},"updated_at":"2026-10-11T02:02:10+00:00"}}"""
        }
        return """{"data":[$data],"paging":{}}"""
    }

    private class FakeJournalStore : MalJournalPersistence {
        val values = mutableMapOf<Int, String>()
        override fun read(profileId: Int): String? = values[profileId]
        override fun write(profileId: Int, value: String?) {
            if (value == null) values.remove(profileId) else values[profileId] = value
        }
        override fun clear() = values.clear()
    }

    private class FakeTokenStore : MalAuthPersistence {
        val values = mutableMapOf<Int, String>()
        override fun read(profileId: Int): String? = values[profileId]
        override fun write(profileId: Int, value: String?) {
            if (value == null) values.remove(profileId) else values[profileId] = value
        }
        override fun clear() = values.clear()
    }

    /**
     * The transport every MAL-shaped request leaves through, standing in for
     * this project's server: it records what would have been sent, and
     * answers the one lookup the id arithmetic makes.
     */
    private class RecordingTransport :
        suspend (String, String, Map<String, String>, String) -> RawHttpResponse {

        data class Call(val method: String, val url: String, val headers: Map<String, String>)

        val calls = mutableListOf<Call>()
        val answers = mutableMapOf<String, String>()

        /**
         * URL substring -> statuses to answer with, consumed in order, so a
         * route can refuse once and answer the next time. A route with nothing
         * queued answers 200.
         */
        val failures = mutableMapOf<String, MutableList<Int>>()

        override suspend fun invoke(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String,
        ): RawHttpResponse {
            calls += Call(method, url, headers)
            val queued = failures.entries.firstOrNull { url.contains(it.key) }?.value
            val status = if (!queued.isNullOrEmpty()) queued.removeAt(0) else 200
            val answer = answers.entries.firstOrNull { (needle, _) -> url.contains(needle) }?.value
            return RawHttpResponse(
                status = status,
                statusText = if (status == 200) "OK" else "error",
                url = url,
                body = if (status == 200) (answer ?: "{}") else "{\"error\":\"invalid_token\"}",
                headers = emptyMap(),
            )
        }
    }

    companion object {
        private const val ACCESS_TOKEN = "tok-test-1"
        private const val PROFILE_A = 9301
        private const val PROFILE_B = 9302
        private const val PROFILE_C = 9303
        private const val PROFILE_D = 9304
        private const val PROFILE_E = 9305
        private const val PROFILE_F = 9306
        private const val PROFILE_G = 9307
        private const val PROFILE_H = 9308
        private const val PROFILE_I = 9309
        private const val PROFILE_J = 9310
        private const val PROFILE_K = 9311
        private const val PROFILE_L = 9312
        private const val PROFILE_M = 9313
        private const val PROFILE_N = 9314
        private const val PROFILE_O = 9315
        private const val PROFILE_P = 9316
        private const val PROFILE_Q = 9317
        private const val PROFILE_R = 9318
        private const val PROFILE_S = 9319
        private const val PROFILE_T = 9320
    }
}
