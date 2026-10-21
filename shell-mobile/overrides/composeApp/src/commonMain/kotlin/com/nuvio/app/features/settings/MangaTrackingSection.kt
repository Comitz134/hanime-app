package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.nuvio.app.features.anilist.AniList
import com.nuvio.app.features.anilist.AniListAnimeResult
import com.nuvio.app.features.manga.MalImportResult
import com.nuvio.app.features.manga.MangaMal
import com.nuvio.app.features.manga.MangaProgressResult
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
    // The shelf-wide counterpart of opening a title: same walk, every row.
    var refreshing by remember { mutableStateOf(false) }
    var refreshProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var refreshResult by remember { mutableStateOf<MangaProgressResult?>(null) }

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

            // Importing brings the list over; this asks each title already on it
            // how far the account got — the answer a shelf only shows once every
            // title has been opened at least once. Same walk, one tap.
            Spacer(Modifier.height(10.dp))
            val refreshingNow = refreshing
            if (refreshingNow) {
                val current = refreshProgress
                if (current != null && current.second > 0) {
                    LinearProgressIndicator(
                        progress = { current.first.toFloat() / current.second.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Checked ${current.first} of ${current.second} titles…",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
            } else {
                OutlinedButton(
                    onClick = {
                        localError = null
                        refreshResult = null
                        refreshProgress = 0 to 0
                        refreshing = true
                        scope.launch {
                            val outcome = MangaMal.refreshLibraryProgress { done, total, _ ->
                                refreshProgress = done to total
                            }
                            refreshResult = outcome
                            if (outcome.failed != null) localError = outcome.failed
                            refreshing = false
                            refreshProgress = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Refresh progress for every title", maxLines = 1)
                }
            }
            refreshResult?.let { outcome ->
                Text(
                    text = when {
                        outcome.failed != null -> outcome.failed
                        outcome.moved == 0 -> {
                            "${outcome.checked} titles checked — all up to date"
                        }
                        else -> {
                            "${outcome.checked} titles checked · ${outcome.moved} moved forward"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
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

/**
 * AniList, in Settings → Tracking — the same card, one provider over.
 *
 * The account is separate from MyAnimeList's on purpose: they are different
 * lists on different services, and linking one must not depend on the other.
 * What it brings over is both halves: the anime list into the Library (the app
 * plays those titles), and the reading list onto the manga shelf with the
 * chapters already read.
 */
@Composable
internal fun AniListProviderCard(modifier: Modifier = Modifier) {
    val auth = AniList.authState
    val scope = rememberCoroutineScope()

    var pendingUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<AniListImport?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var mangaResult by remember { mutableStateOf<MalImportResult?>(null) }
    var animeResult by remember { mutableStateOf<AniListAnimeResult?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { AniList.ensureLoaded() }

    // The browser's half of the sign-in, polled while the person is away.
    LaunchedEffect(auth.pendingState) {
        if (auth.pendingState != null) AniList.awaitSignIn()
    }

    val mode = when {
        auth.pendingState != null -> TrackingConnectionCardMode.AWAITING_APPROVAL
        auth.connected -> TrackingConnectionCardMode.CONNECTED
        else -> TrackingConnectionCardMode.DISCONNECTED
    }

    TrackingProviderCard(
        brand = TrackingBrand.ANILIST,
        mode = mode,
        credentialsConfigured = true,
        isLoading = auth.busy,
        connectedLabel = if (auth.username.isBlank()) {
            "Connected"
        } else {
            "Connected as ${auth.username}"
        },
        connectedDescription = "Episodes you watch and chapters you open are written to " +
            "AniList — never backwards. The anime list comes across into the Library and " +
            "Continue Watching; the reading list fills the manga shelf with the chapters " +
            "you have read.",
        signInDescription = "Link AniList to write your progress to it, and to bring the " +
            "anime and manga lists you already have onto this device.",
        finishSignInLabel = "Finish signing in",
        approvalDescription = "Your browser is on AniList's consent page. Approve access and " +
            "come back — this card finishes the link on its own.",
        connectLabel = "Connect AniList",
        openLoginLabel = "Open the consent page",
        disconnectLabel = "Disconnect",
        missingCredentialsMessage = "",
        websiteLabel = "Visit AniList",
        websiteUrl = "https://anilist.co",
        syncLabel = null,
        isSyncing = busy != null,
        statusMessage = null,
        errorMessage = localError ?: auth.error,
        connectedContent = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val current = progress
                if (busy != null) {
                    if (current != null && current.second > 0) {
                        LinearProgressIndicator(
                            progress = { current.first.toFloat() / current.second.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (current != null && current.second > 0) {
                            "Checked ${current.first} of ${current.second} titles…"
                        } else {
                            "Reading your list…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }

                TrackingBrandPrimaryButton(
                    label = "Import my anime list",
                    loading = busy == AniListImport.ANIME,
                    enabled = busy == null,
                    showSyncIcon = true,
                    onClick = {
                        localError = null
                        animeResult = null
                        progress = 0 to 0
                        busy = AniListImport.ANIME
                        scope.launch {
                            val outcome = AniList.importAnime { done, total, _ ->
                                progress = done to total
                            }
                            animeResult = outcome
                            if (outcome.failed != null) localError = outcome.failed
                            busy = null
                            progress = null
                        }
                    },
                )

                TrackingBrandPrimaryButton(
                    label = "Import my reading list",
                    loading = busy == AniListImport.MANGA,
                    enabled = busy == null,
                    showSyncIcon = true,
                    onClick = {
                        localError = null
                        mangaResult = null
                        progress = 0 to 0
                        busy = AniListImport.MANGA
                        scope.launch {
                            val outcome = AniList.importManga { done, total, _ ->
                                progress = done to total
                            }
                            mangaResult = outcome
                            if (outcome.failed != null) localError = outcome.failed
                            busy = null
                            progress = null
                        }
                    },
                )

                animeResult?.let { outcome ->
                    Text(
                        text = anilistAnimeSummary(outcome),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (outcome.failed != null) {
                            Color(0xFFFFDAD6)
                        } else {
                            Color.White.copy(alpha = 0.78f)
                        },
                    )
                }

                mangaResult?.let { outcome ->
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
            }
        },
        onConnectRequested = {
            localError = null
            mangaResult = null
            animeResult = null
            AniList.beginSignIn().also { url -> pendingUrl = url }
        },
        onResumeAuthorization = {
            val kept = pendingUrl
            if (kept.isNullOrBlank()) {
                AniList.beginSignIn().also { url -> pendingUrl = url }
            } else {
                kept
            }
        },
        onCancelAuthorization = {
            pendingUrl = null
            AniList.cancelSignIn()
        },
        onDisconnect = {
            pendingUrl = null
            mangaResult = null
            animeResult = null
            progress = null
            AniList.forget()
            scope.launch { AniList.disconnect() }
        },
        modifier = modifier,
    )

    // The error is also shown in the card's body, so don't let a stale one
    // survive the state that produced it.
    LaunchedEffect(auth.connected, auth.pendingState) {
        if (auth.connected || auth.pendingState != null) localError = null
    }
}

/** Which list a run is importing, so one button can spin without the other. */
private enum class AniListImport { MANGA, ANIME }

/** One line about what the last AniList anime import did. */
private fun anilistAnimeSummary(result: AniListAnimeResult): String {
    result.failed?.let { return it }
    val parts = buildList {
        add("${result.imported} anime added to the library")
        if (result.skipped > 0) add("${result.skipped} already on it")
        if (result.seeded > 0) add("${result.seeded} ready in Continue Watching")
    }
    return parts.joinToString(" · ")
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
