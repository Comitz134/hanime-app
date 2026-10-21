package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What happened to the session the old shell left behind. */
fun interface LegacyImportCallback {
    fun onResult(outcome: String)
}

/**
 * The retired WebView shell's account, handed to the Supabase SDK.
 *
 * The shell signed in to the same project this app talks to, and kept the
 * session in the WebView's localStorage as `{access, refresh, expiresAt,
 * email, userId}` (server/client/src/account.js). The APK that replaced it has
 * the same package id, so both the WebView profile and that localStorage
 * survived the update — [app.hanime.shell.LegacyShellImport] reads the record
 * and lands here.
 *
 * `importAuthToken` is the SDK's own door: it stores the session through the
 * configured session manager (so the next launch starts signed in), refreshes
 * it, and starts the auto-refresh the app expects. Nothing is invented: if the
 * refresh token has been spent, the import fails and the reader signs in.
 *
 * It never overwrites a session that already exists. A reader who installed
 * 2.0.0 and signed in before the migration shipped has one, and it is newer
 * than anything the old shell knew.
 */
object LegacyShellAccount {
    const val IMPORTED = "imported"
    const val ALREADY_SIGNED_IN = "signed-in"
    const val FAILED = "failed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = Logger.withTag("LegacyShellAccount")

    @JvmStatic
    fun importSession(accessToken: String, refreshToken: String, callback: LegacyImportCallback) {
        if (accessToken.isBlank() || refreshToken.isBlank()) {
            callback.onResult(FAILED)
            return
        }
        scope.launch {
            val outcome = runCatching {
                val auth = SupabaseProvider.client.auth
                // The SDK loads its persisted session at client creation; reading
                // `currentSessionOrNull` before that finishes would see null and
                // mistake a signed-in reader for a signed-out one.
                withTimeoutOrNull(5_000L) { auth.awaitInitialization() }
                if (auth.currentSessionOrNull() != null) return@runCatching ALREADY_SIGNED_IN

                auth.importAuthToken(accessToken, refreshToken)
                withTimeoutOrNull(10_000L) { auth.awaitInitialization() }
                if (auth.currentSessionOrNull() != null) IMPORTED else FAILED
            }.getOrElse { error ->
                // The reason matters and the tokens do not: an expired refresh
                // token and an unreachable backend look identical from here,
                // and only one of them is worth another launch.
                log.w(error) { "could not import the old shell's session" }
                FAILED
            }
            callback.onResult(outcome)
        }
    }
}
