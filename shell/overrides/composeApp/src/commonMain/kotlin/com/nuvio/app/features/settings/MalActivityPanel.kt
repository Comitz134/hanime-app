package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.mal.MalActivity
import com.nuvio.app.features.mal.MalLibrary
import com.nuvio.app.features.mal.MalMembership
import com.nuvio.app.features.mal.MalMovement
import com.nuvio.app.features.mal.MalTrackedTitle
import com.nuvio.app.features.mal.MalTracker
import com.nuvio.app.features.mal.agoInWords
import com.nuvio.app.features.mal.malTrackedTitles
import com.nuvio.app.features.mal.statusLabel
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.coroutines.launch

/**
 * The panel as a settings section: its label, or nothing at all.
 *
 * A disconnection is answered by the connection card above, so an accountless
 * panel with no history would be a second, empty voice saying the same thing.
 * A journal with rows in it is the case that has to be shown either way: those
 * rows are the record of what was tried while the link worked.
 */
@Composable
internal fun MalActivitySection(isTablet: Boolean) {
    val linked by remember {
        MalTracker.ensureLoaded()
        MalTracker.isAuthenticated
    }.collectAsStateWithLifecycle()
    val attempts by remember { MalActivity.attempts }.collectAsStateWithLifecycle()
    if (!linked && attempts.isEmpty()) return

    SettingsSection(
        title = "MyAnimeList activity",
        isTablet = isTablet,
    ) {
        MalActivityPanel(isTablet = isTablet)
    }
}

/**
 * What this app has told MyAnimeList, one row per title.
 *
 * The connection card answers "is it linked"; this answers "is it working".
 * Each row carries three facts that only mean something next to each other —
 * whether MAL's list holds the title at all, what MAL says it is at, and when
 * its row last moved — and every disagreement between them is printed in the
 * error colour underneath. That is the whole design: a write that did not take
 * should be a sentence on a screen, not an absence.
 *
 * The rows come from the device's own journal of attempts (MalActivity), so a
 * title played last night is still on this list this morning, with MAL's own
 * answer read back beside it. Above them sits the list's freshness, because a
 * panel that trusts a stale copy is how a wrong answer gets told confidently.
 */
@Composable
internal fun MalActivityPanel(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val trackerState by remember {
        MalTracker.ensureLoaded()
        MalTracker.uiState
    }.collectAsStateWithLifecycle()
    val listState by remember { MalLibrary.state }.collectAsStateWithLifecycle()
    val attempts by remember { MalActivity.attempts }.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val rows = remember(attempts, listState.entries, listState.hasLoaded) {
        malTrackedTitles(
            attempts = attempts,
            entries = listState.entries,
            listLoaded = listState.hasLoaded,
        )
    }

    SettingsGroup(isTablet = isTablet, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (isTablet) 20.dp else 16.dp,
                    vertical = if (isTablet) 14.dp else 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = panelSummary(
                    linked = trackerState.isAuthenticated,
                    rows = rows,
                    entryCount = listState.entries.size,
                    checkedAtEpochMs = listState.checkedAtEpochMs,
                    isLoading = listState.isLoading,
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            TextButton(
                onClick = {
                    scope.launch { MalLibrary.refresh(TrackingRefreshIntent.USER_INITIATED, force = true) }
                },
                enabled = !listState.isLoading && trackerState.isAuthenticated,
            ) {
                Text("Check now")
            }
        }

        listState.error?.takeIf(String::isNotBlank)?.let { message ->
            SettingsGroupDivider(isTablet = isTablet)
            PanelMessage(
                isTablet = isTablet,
                text = "MyAnimeList's list could not be read: $message",
                isError = true,
            )
        }

        if (rows.isEmpty()) {
            SettingsGroupDivider(isTablet = isTablet)
            PanelMessage(
                isTablet = isTablet,
                text = if (trackerState.isAuthenticated) {
                    "Nothing has been written from this app yet. Finish an episode and it " +
                        "appears here with what MyAnimeList made of it."
                } else {
                    "No MyAnimeList account is linked, so nothing you watch here is recorded."
                },
                isError = !trackerState.isAuthenticated,
            )
        }

        rows.forEach { row ->
            SettingsGroupDivider(isTablet = isTablet)
            ActivityRow(
                isTablet = isTablet,
                row = row,
                // The suspend call itself: the row owns its own scope, so the
                // press reports what came back rather than the job it started.
                onRetry = { MalTracker.retry(row.lastAttempt) },
            )
        }
    }
}

@Composable
private fun ActivityRow(
    isTablet: Boolean,
    row: MalTrackedTitle,
    onRetry: suspend () -> Boolean,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var busy by remember(row) { mutableStateOf(false) }
    var outcome by remember(row) { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = if (isTablet) 14.dp else 12.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.standing,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            Text(
                text = lastMovedLabel(row),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
            row.troubles.forEach { trouble ->
                Text(
                    text = trouble,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
            outcome?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = membershipLabel(row),
                style = MaterialTheme.typography.labelMedium,
                color = when (row.membership) {
                    MalMembership.ON_LIST -> tokens.colors.accent
                    MalMembership.NOT_ON_LIST -> tokens.colors.danger
                    MalMembership.UNKNOWN -> tokens.colors.textMuted
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.lastAttempt.isRetryable) {
                TextButton(
                    onClick = {
                        busy = true
                        outcome = null
                        scope.launch {
                            val sent = onRetry()
                            // The row's own "what it is at" comes from MAL, so a
                            // re-send that worked re-reads the list here rather
                            // than leaving the new truth for the next visit.
                            if (sent) {
                                MalLibrary.refresh(TrackingRefreshIntent.INVALIDATED, force = true)
                            }
                            busy = false
                            outcome = if (sent) {
                                "Sent. MyAnimeList has it now."
                            } else {
                                "It was refused again — the line above says why."
                            }
                        }
                    },
                    enabled = !busy,
                ) {
                    Text(if (busy) "Sending…" else "Send now")
                }
            }
        }
    }
}

@Composable
private fun PanelMessage(
    isTablet: Boolean,
    text: String,
    isError: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isTablet) 20.dp else 16.dp,
                vertical = if (isTablet) 14.dp else 12.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) tokens.colors.danger else tokens.colors.textMuted,
        )
    }
}

/** MAL's status when the list holds the title, or why it does not. */
internal fun membershipLabel(row: MalTrackedTitle): String = when (row.membership) {
    MalMembership.ON_LIST -> statusLabel(row.status ?: "")
    MalMembership.NOT_ON_LIST -> "Not on your list"
    MalMembership.UNKNOWN -> "List not read"
}

/**
 * When the row last moved, and which side moved it. A move nobody can account
 * for is worth as much as the timestamp itself: it means another client — the
 * web client, the phone, MAL's own app — is in the same account.
 */
internal fun lastMovedLabel(row: MalTrackedTitle): String {
    if (row.lastMovedAtEpochMs <= 0L) return "Has not moved since this app started keeping track"
    val stamp = agoInWords(row.lastMovedAtEpochMs)
    return when (row.lastMovedBy) {
        MalMovement.THIS_APP -> "Last moved $stamp — by this app"
        MalMovement.SOMEWHERE_ELSE -> "Last moved $stamp — by MyAnimeList"
        null -> "Last moved $stamp"
    }
}

/**
 * The line above the rows: what the panel is looking at, and how fresh it is.
 * Silence is not an option here — "nothing has been written yet" and "the list
 * could not be read" are different problems, and the reader has to be able to
 * tell which one they have.
 */
internal fun panelSummary(
    linked: Boolean,
    rows: List<MalTrackedTitle>,
    entryCount: Int,
    checkedAtEpochMs: Long?,
    isLoading: Boolean,
): String {
    if (!linked) return "Not linked — nothing is being written to MyAnimeList."
    if (isLoading) return "Reading your list from MyAnimeList…"
    val standing = when {
        checkedAtEpochMs == null -> "MyAnimeList's list has not been read yet."
        else -> "MyAnimeList holds $entryCount title${if (entryCount == 1) "" else "s"}, " +
            "read ${agoInWords(checkedAtEpochMs)}."
    }
    if (rows.isEmpty()) return standing
    return "$standing ${rows.size} title${if (rows.size == 1) "" else "s"} written from this app."
}
