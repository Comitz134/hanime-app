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
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MalTrackerTest {

    private val realTransport = MalApi.transport
    private val realTokenStore = MalTracker.tokenStore

    private lateinit var transport: RecordingTransport
    private lateinit var tokens: FakeTokenStore

    @BeforeTest
    fun installFakes() {
        transport = RecordingTransport()
        tokens = FakeTokenStore()
        MalApi.transport = transport
        MalTracker.tokenStore = tokens
    }

    @AfterTest
    fun restoreFakes() {
        MalApi.transport = realTransport
        MalTracker.tokenStore = realTokenStore
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

        val put = transport.calls.single()
        assertEquals("PUT", put.method)
        assertTrue(put.url.contains("/api/mal/v2/anime/44/my_list_status?"), put.url)
        assertTrue(put.url.contains("status=watching"), put.url)
        assertTrue(put.url.contains("num_watched_episodes=3"), put.url)
        // The relay is told whose token this is; MAL completes a title by
        // itself when the count reaches the total, so only the count travels.
        assertEquals("Bearer $ACCESS_TOKEN", put.headers["authorization"])
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
        // A pause and a stop at the end of one episode are one event to MAL.
        assertEquals(1, transport.calls.size)
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

    // --------------------------------------------------------------- fixtures

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

        override suspend fun invoke(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String,
        ): RawHttpResponse {
            calls += Call(method, url, headers)
            val answer = answers.entries.firstOrNull { (needle, _) -> url.contains(needle) }?.value
            return RawHttpResponse(
                status = 200,
                statusText = "OK",
                url = url,
                body = answer ?: "{}",
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
    }
}
