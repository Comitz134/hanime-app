package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.SurfaceEdge
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.manga.MangaRepoDetail
import com.nuvio.app.features.manga.MangaRepoExtension
import com.nuvio.app.features.manga.MangaSourceInfo
import com.nuvio.app.features.manga.MangaSourceRepo
import com.nuvio.app.features.manga.MangaSourcesApi
import kotlinx.coroutines.launch

/**
 * Settings -> Content & discovery -> Manga sources.
 *
 * The rows are the app's own settings furniture, deliberately: a repository is
 * a thing you install and a source is a thing you turn on, which is exactly
 * what the Addons, Media servers and Plugins rows above are.
 *
 * A repository is opened rather than expanded: a Mihon repository lists
 * hundreds of extensions, so it is a dialog with a filter and a lazy list, and
 * the settings page only ever holds the counts. Inside, a Mihon repository
 * shows its extensions - each with the catalogues it provides, switched
 * together or one at a time - and a SEKAI repository shows its scripts.
 */
@Composable
internal fun MangaSourcesSettings(isTablet: Boolean) {
    val state = MangaSourcesApi.state
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    var opening by remember { mutableStateOf<MangaSourceRepo?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { MangaSourcesApi.ensureLoaded() }

    SettingsSection(
        title = "MANGA SOURCES",
        isTablet = isTablet,
        actions = {
            IconButton(
                onClick = {
                    notice = null
                    adding = true
                },
                enabled = state.busy == null,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Add a source repository",
                    tint = tokens.colors.accent,
                )
            }
        },
    ) {
        SettingsGroup(isTablet = isTablet) {
            when {
                state.repos.isEmpty() && state.loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NuvioLoadingIndicator(modifier = Modifier.size(22.dp))
                }

                state.repos.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "No source repositories",
                        style = MaterialTheme.typography.bodyLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Add one with the plus above. A Mihon repository (index.pb) brings its extensions and sources; a SEKAI repository is an index.json of JavaScript sources.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }

                else -> state.repos.forEachIndexed { index, repo ->
                    if (index > 0) SettingsGroupDivider(isTablet)
                    SettingsNavigationRow(
                        title = repo.name.ifBlank { repo.url },
                        description = repoLine(repo),
                        isTablet = isTablet,
                        trailingContent = {
                            if (state.busy == repo.url) {
                                NuvioLoadingIndicator(modifier = Modifier.size(18.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowRight,
                                    contentDescription = "Open ${repo.name}",
                                    tint = tokens.colors.textMuted,
                                )
                            }
                        },
                        onClick = {
                            notice = null
                            opening = repo
                        },
                    )
                }
            }
        }

        val message = notice ?: state.error
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }

        Text(
            text = "Search asks every source you leave on. Mangafire is built in; the rest come from the repositories above, as scripts or as Mihon extensions.",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )

        opening?.let { repo ->
            RepoPickerDialog(
                repo = repo,
                isTablet = isTablet,
                onDismiss = { opening = null },
                onRemoved = {
                    opening = null
                    scope.launch { MangaSourcesApi.load() }
                },
                onNotice = { notice = it },
            )
        }
    }

    if (adding) {
        AddRepositoryDialog(
            isTablet = isTablet,
            onDismiss = { adding = false },
            onAdd = { url ->
                val refused = MangaSourcesApi.add(url)
                if (refused == null) adding = false
                refused
            },
        )
    }
}

private fun repoLine(repo: MangaSourceRepo): String {
    val parts = mutableListOf<String>()
    if (repo.isMihon) {
        if (repo.extensions > 0) {
            parts += "${repo.extensions} ${if (repo.extensions == 1) "extension" else "extensions"}"
        }
        parts += "${repo.installed} of ${repo.count} on"
    } else {
        val count = repo.count
        parts += "$count ${if (count == 1) "source" else "sources"}"
        if (repo.installed != count) parts += "${repo.installed} on"
    }
    if (repo.builtin) parts += "Built in"
    if (repo.error.isNotBlank()) parts += repo.error
    return parts.joinToString(" · ")
}

private fun sourceLine(source: MangaSourceInfo): String {
    val parts = mutableListOf<String>()
    if (source.lang.isNotBlank()) parts += source.lang.uppercase()
    if (source.native) parts += "Built in" else if (source.version.isNotBlank()) parts += "v${source.version}"
    return parts.joinToString(" · ")
}

private fun extensionLine(extension: MangaRepoExtension, on: Int): String {
    val parts = mutableListOf<String>()
    if (extension.version.isNotBlank()) parts += "v${extension.version}"
    parts += "${extension.sources.size} ${if (extension.sources.size == 1) "source" else "sources"}"
    // A MangaDex package can carry sixty languages; four and a count is a
    // description, sixty is a paragraph.
    val langs = extension.sources.map { it.lang.uppercase() }.filter { it.isNotBlank() }.distinct()
    if (langs.isNotEmpty()) {
        val shown = langs.take(4).joinToString(", ")
        parts += if (langs.size > 4) "$shown +${langs.size - 4}" else shown
    }
    if (on > 0) parts += "$on on"
    if (extension.contentWarning > 0) parts += "18+"
    return parts.joinToString(" · ")
}

private fun matches(extension: MangaRepoExtension, query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    if (extension.name.lowercase().contains(needle)) return true
    if (extension.pkg.lowercase().contains(needle)) return true
    return extension.sources.any {
        it.name.lowercase().contains(needle) || it.lang.lowercase().contains(needle)
    }
}

/**
 * One repository, opened.
 *
 * What it offers is fetched when it opens, not with the shelf: a Mihon
 * repository can hold 1,400 extensions. The list is lazy and filterable, and a
 * switch writes straight through to the server, which answers with the
 * repository as it is afterwards - so the screen redraws from the server's
 * answer rather than from what it hoped would happen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoPickerDialog(
    repo: MangaSourceRepo,
    isTablet: Boolean,
    onDismiss: () -> Unit,
    onRemoved: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<MangaRepoDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable(repo.url) { mutableStateOf("") }

    suspend fun reload() {
        loading = true
        val answer = MangaSourcesApi.open(repo.url)
        detail = answer.detail
        failure = answer.error
        loading = false
    }

    LaunchedEffect(repo.url) { reload() }

    BasicAlertDialog(onDismissRequest = { if (busy == null) onDismiss() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = tokens.components.dialogMaxWidth),
            shape = tokens.shapes.dialog,
            color = tokens.colors.surfaceDialog,
            border = BorderStroke(tokens.borders.thin, SurfaceEdge),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = repo.name.ifBlank { repo.url },
                            style = MaterialTheme.typography.titleLarge,
                            color = tokens.colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val current = detail
                        Text(
                            text = when {
                                current == null -> repo.url
                                current.extensions.isNotEmpty() ->
                                    "${current.total} sources in ${current.extensions.size} extensions · ${current.enabled} on"
                                else -> "${current.sources.size} sources · ${current.sources.count { it.enabled }} on"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                        )
                    }
                    TextButton(onClick = onDismiss, enabled = busy == null) {
                        Text("Close", style = MaterialTheme.typography.labelLarge)
                    }
                }

                val current = detail
                when {
                    loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioLoadingIndicator(modifier = Modifier.size(22.dp))
                    }

                    current == null -> Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = failure ?: "That repository could not be read.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        DialogButtons {
                            DialogButton(text = "Close", enabled = true, onClick = onDismiss)
                            DialogButton(
                                text = "Try again",
                                style = DialogButtonStyle.Primary,
                                enabled = true,
                                onClick = { scope.launch { reload() } },
                            )
                        }
                    }

                    current.extensions.isNotEmpty() -> {
                        Text(text = "Extensions", style = MaterialTheme.typography.labelMedium, color = tokens.colors.textMuted)
                        if (current.extensions.size > 8) {
                            NuvioInputField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = "Filter extensions",
                            )
                        }
                        val shown = current.extensions.filter { matches(it, query) }
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                            if (shown.isEmpty()) {
                                item {
                                    Text(
                                        text = "No extension matches that.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = tokens.colors.textMuted,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    )
                                }
                            }
                            items(shown, key = { it.pkg }) { extension ->
                                SettingsSwitchRow(
                                    title = extension.name,
                                    description = extensionLine(
                                        extension,
                                        extension.sources.count { it.enabled },
                                    ),
                                    checked = extension.enabled,
                                    enabled = busy == null,
                                    isTablet = isTablet,
                                    onCheckedChange = { on ->
                                        scope.launch {
                                            busy = extension.pkg
                                            val answer = MangaSourcesApi.toggleExtension(repo.url, extension.pkg, on)
                                            busy = null
                                            answer.detail?.let {
                                                detail = it
                                                failure = null
                                            }
                                            answer.error?.let { failure = it }
                                        }
                                    },
                                )
                                // The catalogues of an extension that is on: a
                                // site can be broken while its package is fine,
                                // so one of them can be switched off on its own.
                                if (extension.enabled) {
                                    extension.sources.forEach { source ->
                                        SettingsSwitchRow(
                                            title = source.name,
                                            description = source.lang.uppercase(),
                                            checked = source.enabled,
                                            enabled = busy == null,
                                            isTablet = isTablet,
                                            onCheckedChange = { on ->
                                                scope.launch {
                                                    busy = source.id
                                                    val refused = MangaSourcesApi.toggle(source.id, on)
                                                    busy = null
                                                    if (refused != null) onNotice(refused)
                                                    reload()
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        if (current.sources.isEmpty()) {
                            item {
                                Text(
                                    text = "This repository installed no sources.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.colors.textMuted,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                        items(current.sources, key = { it.id }) { source ->
                            SettingsSwitchRow(
                                title = source.name,
                                description = sourceLine(source),
                                checked = source.enabled,
                                enabled = busy == null,
                                isTablet = isTablet,
                                onCheckedChange = { on ->
                                    scope.launch {
                                        busy = source.id
                                        val refused = MangaSourcesApi.toggle(source.id, on)
                                        busy = null
                                        if (refused != null) onNotice(refused)
                                        reload()
                                    }
                                },
                            )
                        }
                    }
                }

                failure?.takeIf { detail != null }?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                DialogButtons {
                    TextButton(
                        onClick = {
                            scope.launch {
                                busy = repo.url
                                val refused = MangaSourcesApi.refreshRepo(repo.url)
                                busy = null
                                if (refused != null) onNotice(refused)
                                reload()
                            }
                        },
                        enabled = busy == null,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Refresh",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    if (!repo.builtin) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    busy = repo.url
                                    val refused = MangaSourcesApi.remove(repo.url)
                                    busy = null
                                    if (refused == null) onRemoved() else onNotice(refused)
                                }
                            },
                            enabled = busy == null,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = tokens.colors.danger,
                            )
                            Text(
                                text = "Remove repository",
                                style = MaterialTheme.typography.labelMedium,
                                color = tokens.colors.danger,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRepositoryDialog(
    isTablet: Boolean,
    onDismiss: () -> Unit,
    onAdd: suspend (String) -> String?,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    BasicAlertDialog(onDismissRequest = { if (!adding) onDismiss() }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = tokens.components.dialogMaxWidth),
            shape = tokens.shapes.dialog,
            color = tokens.colors.surfaceDialog,
            border = BorderStroke(tokens.borders.thin, SurfaceEdge),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Add a source repository",
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Paste the address of a repository index. A Mihon repository publishes index.pb and its extensions run here as they do in Mihon; a SEKAI repository is an index.json of JavaScript sources.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
                NuvioInputField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    placeholder = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
                )
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                DialogButtons {
                    DialogButton(
                        text = "Cancel",
                        enabled = !adding,
                        onClick = onDismiss,
                    )
                    DialogButton(
                        text = if (adding) "Adding" else "Add",
                        style = DialogButtonStyle.Primary,
                        loading = adding,
                        enabled = url.isNotBlank(),
                        onClick = {
                            adding = true
                            error = null
                            scope.launch {
                                error = onAdd(url.trim())
                                adding = false
                            }
                        },
                    )
                }
            }
        }
    }
}
