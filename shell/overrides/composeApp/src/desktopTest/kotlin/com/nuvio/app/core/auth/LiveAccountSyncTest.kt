package com.nuvio.app.core.auth

import com.nuvio.app.core.network.SupabaseConfig
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.sync.LibrarySyncKey
import com.nuvio.app.features.library.sync.SupabaseLibrarySyncAdapter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop's own account path, end to end, against the live backend.
 *
 * `accounts/probe.mjs` proves the contract with hand-written HTTP; this proves
 * that *this application's* code satisfies it — that `AuthRepository` signs in,
 * and that the sync adapter the library actually uses pushes a title into the
 * account and reads it back. A URL baked into a build is not evidence that the
 * window can sign in; this is.
 *
 * Opt-in, because it needs the network and a real account:
 *
 * ```bash
 * cd shell/.upstream && ./gradlew :composeApp:desktopTest \
 *   -PsekaiLiveEmail=someone@example.com -PsekaiLivePassword=… \
 *   --tests '*LiveAccountSyncTest*'
 * ```
 *
 * The values travel as Gradle project properties rather than environment
 * variables on purpose: a reused Gradle daemon keeps the environment it was
 * started with, so an exported variable can silently not reach the test.
 *
 * The test JVM writes its app data into the build directory (see
 * `tasks.withType<Test>` in build.gradle.kts), so a live run cannot disturb the
 * installed app's library, and it never signs out — sign-out wipes local
 * account data, which is not a thing a test may do to somebody's machine.
 */
class LiveAccountSyncTest {

    @Test
    fun desktopSignsInAndSyncsALibraryItemToTheAccount() {
        val email = System.getProperty("sekaiLiveEmail").orEmpty()
        val password = System.getProperty("sekaiLivePassword").orEmpty()
        if (email.isBlank() || password.isBlank()) {
            println("LiveAccountSyncTest: skipped — pass -PsekaiLiveEmail=… -PsekaiLivePassword=… to run it")
            return
        }

        assertTrue(
            SupabaseConfig.URL.isNotBlank() && SupabaseConfig.ANON_KEY.isNotBlank(),
            "this build has no account backend: SupabaseConfig.URL='${SupabaseConfig.URL}'",
        )

        runBlocking {
            AuthRepository.initialize()
            val signIn = AuthRepository.signInWithEmail(email, password)
            assertTrue(
                signIn.isSuccess,
                "sign-in failed: ${AuthRepository.error.value ?: signIn.exceptionOrNull()?.message}",
            )

            // Not `state.value`: signing in stores the session and the state
            // flow reports it from its own collector, so reading the value the
            // instant the call returns races that collector. The window never
            // notices — it renders whatever the flow says — but a test that
            // reads once would call a working sign-in broken.
            val state = withTimeout(20_000) {
                AuthRepository.state.first { it is AuthState.Authenticated }
            } as AuthState.Authenticated
            assertTrue(!state.isAnonymous, "signing in with an address must not leave an anonymous session")
            assertTrue(state.email.equals(email, ignoreCase = true), "signed in as ${state.email}")

            // A key of its own per run, so the check is repeatable and a leftover
            // from a previous run can never be mistaken for this one's write.
            val contentId = "ha:an:live-${System.currentTimeMillis()}"

            try {
                SupabaseLibrarySyncAdapter.pushItems(
                    profileId = 1,
                    items = listOf(libraryItem(contentId)),
                )

                val pulled = SupabaseLibrarySyncAdapter.pullSnapshot(profileId = 1, pageSize = 500)
                val stored = pulled.firstOrNull { it.id == contentId }
                assertTrue(stored != null, "the pushed title is not in the account's library")
                assertEquals("LiveAccountSyncTest", stored.name)
                assertEquals("series", stored.type)

                // The delta log is what makes the same write visible on the
                // phone without a full re-pull; a cursor that never moves would
                // leave the two clients permanently out of step.
                val cursor = SupabaseLibrarySyncAdapter.getDeltaCursor(profileId = 1)
                assertTrue(cursor > 0, "the delta cursor did not move: $cursor")
            } finally {
                // The key type directly rather than `LibraryItem.toLibrarySyncKey()`:
                // that helper is `internal` to the app's own compilation, so a test
                // source set cannot see it.
                SupabaseLibrarySyncAdapter.deleteItems(
                    profileId = 1,
                    keys = listOf(LibrarySyncKey(contentId = contentId, contentType = "series")),
                )
            }

            val afterDelete = SupabaseLibrarySyncAdapter.pullSnapshot(profileId = 1, pageSize = 500)
            assertTrue(afterDelete.none { it.id == contentId }, "the deleted title is still in the library")
        }
    }

    private fun libraryItem(contentId: String) = LibraryItem(
        id = contentId,
        type = "series",
        name = "LiveAccountSyncTest",
        description = "written by the desktop's own sync adapter",
        genres = listOf("Test"),
        addonBaseUrl = "http://127.0.0.1:8787",
        savedAtEpochMs = System.currentTimeMillis(),
    )
}
