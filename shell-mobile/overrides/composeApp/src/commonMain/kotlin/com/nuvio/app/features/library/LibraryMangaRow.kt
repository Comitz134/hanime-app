package com.nuvio.app.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.manga.MangaApi
import com.nuvio.app.features.manga.MangaLibraryItem
import kotlinx.coroutines.CancellationException

/** How the manga shelf is ordered; the series shelf's sorts, in manga terms. */
internal enum class MangaShelfSort(val label: String) {
    RECENT("Recently added"),
    TITLE("Title A–Z"),
    PROGRESS("Progress"),
}

/** What a reader means by "show me the ones I am reading". */
internal enum class MangaShelfFilter(val label: String) {
    ALL("All"),
    READING("Reading"),
    NOT_STARTED("Not started"),
}

/**
 * The manga shelf's state, held above the list rather than inside it.
 *
 * It used to live in the composable that drew the row, which was enough while
 * the shelf was one horizontal row inside the Series view. It is now a view of
 * its own (the Library's Manga pill), so it is drawn in both layouts — and a
 * grid of four hundred titles has to be built row by row *by the list*, not all
 * at once inside a single item, or every poster loads as the tab opens. Hoisting
 * the state is what lets the list chunk it.
 *
 * The sort and filter are saved with the screen's saved state, so they survive
 * rotation and process death exactly as the series shelf's do.
 */
@Stable
internal class MangaShelf {
    val items = mutableStateListOf<MangaLibraryItem>()
    var sortName by mutableStateOf(MangaShelfSort.RECENT.name)
    var filterName by mutableStateOf(MangaShelfFilter.ALL.name)

    /** False until the shelf has been read once, so "no manga" is not shown early. */
    var loaded by mutableStateOf(false)

    val sort: MangaShelfSort
        get() = MangaShelfSort.entries.firstOrNull { it.name == sortName } ?: MangaShelfSort.RECENT

    val filter: MangaShelfFilter
        get() = MangaShelfFilter.entries.firstOrNull { it.name == filterName } ?: MangaShelfFilter.ALL

    /** The shelf as the chips describe it. */
    val visible: List<MangaLibraryItem>
        get() {
            val filtered = items.filter { item ->
                when (filter) {
                    MangaShelfFilter.ALL -> true
                    MangaShelfFilter.READING -> item.number.isNotBlank()
                    MangaShelfFilter.NOT_STARTED -> item.number.isBlank()
                }
            }
            return when (sort) {
                // The order the shelf is stored in: newest first. For a shelf
                // imported from MyAnimeList that is the order the list was last
                // read in, because the import stamps each row from its place in
                // the list (see `MangaMal.importLibrary`).
                MangaShelfSort.RECENT -> filtered.sortedByDescending { it.at }
                MangaShelfSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
                MangaShelfSort.PROGRESS -> filtered.sortedWith(
                    compareByDescending<MangaLibraryItem> { chapterNumber(it) }
                        .thenBy { it.title.lowercase() },
                )
            }
        }

    suspend fun reload() {
        try {
            val fresh = MangaApi.library()
            items.clear()
            items.addAll(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A shelf that cannot be read is an absent shelf, not an error over
            // the whole Library: the rest of the tab is still true.
            items.clear()
        }
        loaded = true
    }
}

private val MangaShelfSaver = Saver<MangaShelf, List<String>>(
    // An ArrayList rather than listOf: the saved value has to be something a
    // Bundle can hold, and what `listOf` returns is not promised to be.
    save = { shelf -> arrayListOf(shelf.sortName, shelf.filterName) },
    restore = { saved ->
        MangaShelf().apply {
            sortName = saved.getOrElse(0) { MangaShelfSort.RECENT.name }
            filterName = saved.getOrElse(1) { MangaShelfFilter.ALL.name }
        }
    },
)

/**
 * The shelf, remembered and re-read whenever it can have changed.
 *
 * `active` is the Manga pill: reading a few hundred rows for a tab nobody is
 * looking at would be work done to be thrown away, and the reader's shelf is
 * only ever shown there. `libraryRevision` moves when the reader adds a title,
 * removes one, or an import lands — which is exactly when re-reading is right.
 */
@Composable
internal fun rememberMangaShelf(active: Boolean): MangaShelf {
    val shelf = rememberSaveable(saver = MangaShelfSaver) { MangaShelf() }
    LaunchedEffect(active, MangaApi.libraryRevision) {
        if (active) shelf.reload()
    }
    return shelf
}

/**
 * The Manga view, as items in the Library's own list.
 *
 * Row mode is the horizontal shelf this fork has always drawn; grid mode is the
 * same posters as the Series view's grid — the layout button in the header now
 * switches both, which is what it looked like it should do all along.
 */
internal fun LazyListScope.mangaShelfContent(
    shelf: MangaShelf,
    layoutMode: LibraryLayoutMode,
    columns: Int,
    onOpen: ((MangaLibraryItem) -> Unit)?,
) {
    item(key = "library-manga-controls") {
        MangaShelfControls(shelf)
    }

    val visible = shelf.visible
    when {
        !shelf.loaded -> Unit

        visible.isEmpty() -> item(key = "library-manga-empty") {
            // The shelf has to exist — and say how a title gets onto it — or
            // nobody learns it is there.
            Text(
                text = if (shelf.items.isEmpty()) {
                    "No manga yet — open a title in the Manga tab and add it to your library."
                } else {
                    "No manga in \"${shelf.filter.label}\" yet."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.nuvio.colors.textMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        layoutMode == LibraryLayoutMode.HORIZONTAL -> item(key = "library-manga-row") {
            NuvioShelfSection(
                title = "",
                entries = visible,
                modifier = Modifier.fillMaxWidth(),
                headerHorizontalPadding = 16.dp,
                rowContentPadding = PaddingValues(horizontal = 16.dp),
                itemSpacing = 12.dp,
                key = { item -> "library-manga:${item.id}" },
            ) { item ->
                NuvioPosterCard(
                    title = item.title,
                    imageUrl = item.poster.ifBlank { null },
                    detailLine = if (item.number.isNotBlank()) "Ch. ${item.number}" else "Not started",
                    onClick = { onOpen?.invoke(item) },
                )
            }
        }

        else -> items(
            items = visible.chunked(columns.coerceAtLeast(1)),
            key = { row -> "library-manga-grid:${row.first().id}" },
        ) { row ->
            MangaGridRow(
                items = row,
                columns = columns,
                onOpen = onOpen,
            )
        }
    }
}

/** The count line and the two chips, drawn where the series' controls sit. */
@Composable
private fun MangaShelfControls(shelf: MangaShelf) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when {
                    !shelf.loaded -> "Loading your shelf…"
                    shelf.visible.size == shelf.items.size ->
                        "${shelf.items.size} title${if (shelf.items.size == 1) "" else "s"}"
                    else -> "${shelf.visible.size} of ${shelf.items.size}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = tokens.colors.textMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NuvioDropdownChip(
                title = "Sort",
                label = shelf.sort.label,
                selectedKey = shelf.sort.name,
                options = MangaShelfSort.entries.map { option ->
                    NuvioDropdownOption(key = option.name, label = option.label)
                },
                enabled = shelf.items.size > 1,
                onSelected = { option -> shelf.sortName = option.key },
            )
            NuvioDropdownChip(
                title = "Show",
                label = shelf.filter.label,
                selectedKey = shelf.filter.name,
                options = MangaShelfFilter.entries.map { option ->
                    NuvioDropdownOption(key = option.name, label = option.label)
                },
                enabled = shelf.items.isNotEmpty(),
                onSelected = { option -> shelf.filterName = option.key },
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun MangaGridRow(
    items: List<MangaLibraryItem>,
    columns: Int,
    onOpen: ((MangaLibraryItem) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        items.forEach { item ->
            MangaGridTile(
                item = item,
                modifier = Modifier.weight(1f),
                onClick = onOpen?.let { open -> { open(item) } },
            )
        }
        repeat(columns - items.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * One poster in the grid, drawn to the series grid's measurements: the same
 * portrait ratio, the same rounding, the same two-line title under it.
 */
@Composable
private fun MangaGridTile(
    item: MangaLibraryItem,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.md)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(shape)
                .background(tokens.colors.surfaceCard)
                .clickable(enabled = onClick != null) { onClick?.invoke() },
        ) {
            if (item.poster.isNotBlank()) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (item.number.isNotBlank()) "Ch. ${item.number}" else "Not started",
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The chapter a title is on, or -1 so unopened titles sort after every read one. */
private fun chapterNumber(item: MangaLibraryItem): Float =
    item.number.trim().substringBefore('.').toFloatOrNull() ?: -1f
