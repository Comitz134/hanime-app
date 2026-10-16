package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.mal.MalActivity
import com.nuvio.app.features.mal.MalLibrary
import com.nuvio.app.features.mal.MalTracker
import com.nuvio.app.features.mal.malTrackedTitles
import com.nuvio.app.features.mal.malTroubleSummary
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.coroutines.launch

/**
 * The MyAnimeList connection card, beside the other trackers.
 *
 * It borrows the app's shared connection card, so linking looks and behaves
 * exactly like Trakt, Simkl and MDBList: Connect opens the system browser at
 * MAL's consent page, the card waits while the app collects the code from the
 * local server, and Disconnect only stops this app — MAL's list is never
 * touched by it.
 */
@Composable
internal fun MalProviderCard(modifier: Modifier = Modifier) {
    val state by remember {
        MalTracker.ensureLoaded()
        MalTracker.uiState
    }.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState by remember { MalLibrary.state }.collectAsStateWithLifecycle()
    val attempts by remember { MalActivity.attempts }.collectAsStateWithLifecycle()
    // The card's own last word on whether tracking is actually working: the
    // session's error if there is one, and otherwise the panel's summary — null
    // whenever every tracked title landed. A card that stays quiet while the
    // list is out of step is how this failed invisibly the first time.
    val activityTrouble = remember(attempts, listState.entries, listState.hasLoaded) {
        malTroubleSummary(
            malTrackedTitles(
                attempts = attempts,
                entries = listState.entries,
                listLoaded = listState.hasLoaded,
            ),
        )
    }

    TrackingProviderCard(
        brand = TrackingBrand.MAL,
        mode = when {
            state.isAuthenticated -> TrackingConnectionCardMode.CONNECTED
            state.isAwaitingApproval -> TrackingConnectionCardMode.AWAITING_APPROVAL
            else -> TrackingConnectionCardMode.DISCONNECTED
        },
        // The registration's public id is part of this build; there is no
        // configuration step that could be missing.
        credentialsConfigured = true,
        isLoading = state.isBusy && !state.isAwaitingApproval,
        connectedLabel = "Connected as ${state.username ?: "MyAnimeList"}",
        connectedDescription = "Your list shows in the Library, and episodes you watch here update MyAnimeList.",
        signInDescription = "Link your MyAnimeList account to see your list here and keep its episode counts in step.",
        finishSignInLabel = "Open the login again",
        approvalDescription = "Approve the request in your browser — the app collects the code by itself.",
        connectLabel = "Connect",
        openLoginLabel = "Open MyAnimeList",
        disconnectLabel = "Disconnect",
        missingCredentialsMessage = "MyAnimeList is not configured in this build.",
        approvalUrl = state.authorizationUrl.takeIf { state.isAwaitingApproval },
        syncLabel = "Sync now",
        errorMessage = state.errorMessage ?: activityTrouble,
        websiteLabel = "myanimelist.net",
        websiteUrl = "https://myanimelist.net",
        onConnectRequested = { MalTracker.startAuthorization() },
        onResumeAuthorization = { MalTracker.pendingAuthorizationUrl() },
        onCancelAuthorization = { MalTracker.cancelAuthorization() },
        onSyncRequested = {
            scope.launch {
                MalLibrary.refresh(TrackingRefreshIntent.USER_INITIATED, force = true)
            }
        },
        onDisconnect = { MalTracker.disconnect() },
        modifier = modifier,
    )
}
