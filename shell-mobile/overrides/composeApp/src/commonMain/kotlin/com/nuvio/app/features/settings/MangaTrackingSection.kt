package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.manga.MalImportResult
import com.nuvio.app.features.manga.MangaMal
import kotlinx.coroutines.launch

/**
 * MyAnimeList, in Settings → Tracking — as a card of the same make as Trakt,
 * Simkl and MDBList.
 *
 * The link lives here as well as on a title page, because "bring my list over"
 * is a question about the account and not about the series in front of you: a
 * reader with three hundred rows on MyAnimeList should not have to open one of
 * them to find the button that fetches the rest.
 *
 * The card owns exactly what the other tracker cards own — one gradient, one
 * expander, one connect/await/disconnect cycle — and delegates the account to
 * [`MangaMal`], which is also what the title page and the reader write through.
 * There is one session and one set of rules, not two.
 */
@Composable
internal fun MalProviderCard(modifier: Modifier = Modifier) {
    val auth = MangaMal.authState
    val scope = rememberCoroutineScope()

    // The authorize URL is kept so "open the consent page again" reopens the
    // same pending state instead of minting a second one the landing page
    // would then have to choose between.
    var pendingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var result by remember { mutableStateOf<MalImportResult?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { MangaMal.ensureLoaded() }

    // The browser's half of the sign-in, polled while the person is away.
    LaunchedEffect(auth.pendingState) {
        if (auth.pendingState != null) MangaMal.awaitSignIn()
    }

    val mode = when {
        auth.pendingState != null -> TrackingConnectionCardMode.AWAITING_APPROVAL
        auth.connected -> TrackingConnectionCardMode.CONNECTED
        else -> TrackingConnectionCardMode.DISCONNECTED
    }

    TrackingProviderCard(
        brand = TrackingBrand.MAL,
        mode = mode,
        credentialsConfigured = true,
        isLoading = auth.busy,
        connectedLabel = if (auth.username.isBlank()) {
            "Connected"
        } else {
            "Connected as ${auth.username}"
        },
        connectedDescription = "Chapters you open are written to your MyAnimeList — never " +
            "backwards, and the last chapter marks the title complete. Importing puts the " +
            "list you already have on this shelf, with how far you are in each.",
        signInDescription = "Link MyAnimeList to write your manga progress to it, and to " +
            "bring the list you already have onto the Library's manga shelf.",
        finishSignInLabel = "Finish signing in",
        approvalDescription = "Your browser is on MyAnimeList's consent page. Approve access " +
            "and come back — this card finishes the link on its own.",
        connectLabel = "Connect MyAnimeList",
        openLoginLabel = "Open the consent page",
        disconnectLabel = "Disconnect",
        missingCredentialsMessage = "",
        websiteLabel = "Visit MyAnimeList",
        websiteUrl = "https://myanimelist.net",
        syncLabel = "Import my reading list",
        isSyncing = busy,
        statusMessage = null,
        errorMessage = localError ?: auth.error,
        connectedContent = {
            val current = progress
            if (busy && current != null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (current.second > 0) {
                        LinearProgressIndicator(
                            progress = { current.first.toFloat() / current.second.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Checked ${current.first} of ${current.second} titles…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            }
            result?.let { outcome ->
                Text(
                    text = importSummary(outcome),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (outcome.failed != null) {
                        Color(0xFFFFDAD6)
                    } else {
                        Color.White.copy(alpha = 0.78f)
                    },
                )
            }
        },
        onConnectRequested = {
            localError = null
            result = null
            MangaMal.beginSignIn().also { url -> pendingUrl = url }
        },
        onResumeAuthorization = {
            val kept = pendingUrl
            if (kept.isNullOrBlank()) {
                MangaMal.beginSignIn().also { url -> pendingUrl = url }
            } else {
                kept
            }
        },
        onCancelAuthorization = {
            pendingUrl = null
            MangaMal.cancelSignIn()
        },
        onSyncRequested = {
            localError = null
            result = null
            progress = 0 to 0
            busy = true
            scope.launch {
                val outcome = MangaMal.importLibrary { done, total, _ ->
                    progress = done to total
                }
                result = outcome
                if (outcome.failed != null) localError = outcome.failed
                busy = false
                progress = null
            }
        },
        onDisconnect = {
            pendingUrl = null
            result = null
            progress = null
            scope.launch { MangaMal.disconnect() }
        },
        modifier = modifier,
    )

    // The error is also shown in the card's body, so don't let a stale one
    // survive the state that produced it.
    LaunchedEffect(auth.connected, auth.pendingState) {
        if (auth.connected || auth.pendingState != null) localError = null
    }
}

/** One line about what the last import did. */
private fun importSummary(result: MalImportResult): String {
    result.failed?.let { return it }
    val parts = buildList {
        add("${result.imported} title${if (result.imported == 1) "" else "s"} added to the library")
        if (result.skipped > 0) add("${result.skipped} already on it")
        if (result.unresolved > 0) add("${result.unresolved} not matched to the catalogue")
    }
    return parts.joinToString(" · ")
}
