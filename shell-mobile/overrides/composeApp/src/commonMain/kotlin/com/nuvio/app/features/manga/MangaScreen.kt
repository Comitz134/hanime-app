package com.nuvio.app.features.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.home.components.posterGridColumnCountForWidth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The manga tab.
 *
 * Three screens, held in one composable because they are one journey: a shelf
 * of titles, the chapters of one title, the pages of one chapter. A tab cannot
 * push routes onto the app's own navigation stack — it is one destination — so
 * the stack below the shelf lives here, and the system's back button walks
 * down it before the app's own does.
 *
 * Everything is drawn from the app's own pieces (NuvioScreen, NuvioScreenHeader,
 * NuvioInputField, NuvioDropdownChip, PosterGridRow) rather than from scratch.
 * That is not housekeeping: a bare Box in a tab inherits neither the screen's
 * background nor its content colour, and the first version of this screen
 * inherited black text on a black page — titles and chapter numbers that were
 * on screen and unreadable. The shelf looking like the Search tab is the same
 * fact seen from the other side.
 *
 * The reader is a strip: pages stacked in reading order, one column, no seams.
 * It is not trying to be a page-flipping reader. What a phone can do that a
 * shelf of links cannot is put the reader back where they were, so every page
 * turned is written down (see `MangaApi.savePosition`) and reopening a title
 * lands in the chapter and on the page left off.
 */

private sealed interface MangaRoute {
    data object Browse : MangaRoute
    data class Title(
        val id: String,
        val title: String,
        val poster: String,
        val resume: MangaPosition? = null,
    ) : MangaRoute
    data class Reader(
        val id: String,
        val title: String,
        val poster: String,
        val chapters: List<MangaChapter>,
        val index: Int,
        val startPage: Int = 0,
    ) : MangaRoute
}

@Composable
internal fun MangaScreen(modifier: Modifier = Modifier) {
    var route by remember { mutableStateOf<MangaRoute>(MangaRoute.Browse) }
    val scope = rememberCoroutineScope()

    // The shelf outlives its own screen. Walking into a title unmounts the
    // browse composable, and with it every `remember` inside it — a reader who
    // searched, filtered, scrolled and came back would find the tab reset to
    // trending. So the feed, the shelf row and the grid's scroll position are
    // held here, above the route switch.
    val feed = remember { MangaFeed() }
    val positions = remember { mutableStateListOf<MangaPosition>() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        feed.options = runCatching { MangaApi.filters() }.getOrDefault(MangaFilters())
    }

    // Typing, or any filter, is a new catalogue — always from its first page.
    // The debounce is the Search tab's, so a word typed at speed is one query.
    LaunchedEffect(feed.query, feed.type, feed.status, feed.genre) {
        if (feed.query.isNotBlank()) delay(350)
        if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
        feed.load(1)
    }

    // Read a chapter, come back, and the shelf should show the chapter you just
    // read — the reader writes the position, so the shelf is re-read each time
    // it is shown.
    LaunchedEffect(route is MangaRoute.Browse) {
        if (route !is MangaRoute.Browse) return@LaunchedEffect
        positions.clear()
        positions.addAll(runCatching { MangaApi.positions() }.getOrDefault(emptyList()))
    }

    // A position handed over by another tab — the Library's manga row — is
    // walked into once: the request is consumed here so that coming back to
    // this tab later lands on the shelf rather than re-opening a chapter that
    // was already read.
    val handedOver = MangaNav.request
    LaunchedEffect(handedOver) {
        val request = MangaNav.take() ?: return@LaunchedEffect
        route = MangaRoute.Title(
            id = request.id,
            title = request.title,
            poster = request.poster,
            resume = request.resume,
        )
    }

    // The shelf of titles the reader added, as ids: the title page shows
    // whether the title being looked at is on it, and the button under that
    // writes both the shelf and — when MyAnimeList is linked — the list.
    // Keyed on the revision the API bumps, so an id is never stale.
    var libraryIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(MangaApi.libraryRevision) {
        libraryIds = runCatching { MangaApi.library().map { it.id }.toSet() }
            .getOrDefault(emptySet())
    }

    PlatformBackHandler(enabled = route != MangaRoute.Browse) {
        route = when (val current = route) {
            is MangaRoute.Reader -> MangaRoute.Title(current.id, current.title, current.poster)
            else -> MangaRoute.Browse
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val current = route) {
            MangaRoute.Browse -> MangaBrowseScreen(
                feed = feed,
                positions = positions,
                listState = listState,
                onOpen = { card ->
                    route = MangaRoute.Title(
                        id = card.id,
                        title = card.title,
                        poster = card.poster,
                        // The shelf already holds every position; a title opened
                        // from it should not ask the server again for one row.
                        resume = positions.firstOrNull { it.id == card.id },
                    )
                },
                onResume = { position ->
                    route = MangaRoute.Title(
                        id = position.id,
                        title = position.title,
                        poster = position.poster,
                        resume = position,
                    )
                },
            )

            is MangaRoute.Title -> MangaTitleScreen(
                id = current.id,
                title = current.title,
                poster = current.poster,
                resume = current.resume,
                inLibrary = libraryIds.contains(current.id),
                onToggleLibrary = {
                    val wanted = !libraryIds.contains(current.id)
                    scope.launch {
                        if (wanted) {
                            MangaApi.addToLibrary(
                                MangaCard(
                                    id = current.id,
                                    title = current.title,
                                    poster = current.poster,
                                ),
                            )
                            // A title added while the account is linked is a
                            // title the account should know about too.
                            MangaMal.addToPlan(current.title.ifBlank { current.id })
                        } else {
                            MangaApi.removeFromLibrary(current.id)
                        }
                    }
                },
                onBack = { route = MangaRoute.Browse },
                onRead = { chapters, index, page ->
                    route = MangaRoute.Reader(
                        id = current.id,
                        title = current.title,
                        poster = current.poster,
                        chapters = chapters,
                        index = index,
                        startPage = page,
                    )
                },
            )

            is MangaRoute.Reader -> MangaReaderScreen(
                route = current,
                onBack = {
                    route = MangaRoute.Title(current.id, current.title, current.poster)
                },
                onChapter = { index, page ->
                    route = current.copy(index = index, startPage = page)
                },
            )
        }
    }
}

// ------------------------------------------------------------------- shelves

/**
 * The shelf's state, held in one object rather than in a handful of `by
 * remember` locals.
 *
 * The reason is that a load owns numbers it keeps between requests — the page
 * it is on, whether there is a next one — and a catalogue is pulled page after
 * page in one call. An object that owns those fields reads its own; a local
 * captured by a `LaunchedEffect` would keep the copy it was born with.
 */
private class MangaFeed {
    val cards = mutableStateListOf<MangaCard>()

    var query by mutableStateOf("")
    var type by mutableStateOf("")
    var status by mutableStateOf("")
    var genre by mutableStateOf("")

    var options by mutableStateOf(MangaFilters())
    var page by mutableIntStateOf(1)
    var hasNext by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** Nothing typed and nothing filtered: their homepage shelf. */
    val browsing: Boolean
        get() = query.isBlank() && type.isEmpty() && status.isEmpty() && genre.isEmpty()

    val filtered: Boolean
        get() = type.isNotEmpty() || status.isNotEmpty() || genre.isNotEmpty()

    fun clearFilters() {
        type = ""
        status = ""
        genre = ""
    }

    /**
     * One catalogue, whole.
     *
     * The catalogue is paged upstream, and it used to be pulled one page at a
     * time as the grid scrolled — so a search that matched two hundred titles
     * showed twenty-four until the reader found the bottom, and a filter could
     * look empty because its first page happened to be thin. Now every page is
     * fetched in this one call, each appended the moment it lands, so the grid
     * fills in as the pages arrive instead of waiting for a thumb.
     *
     * It stops when upstream names no next page, or after [AUTO_PAGES] — a
     * guard against a catalogue that would page forever, not a limit anyone is
     * expected to reach.
     */
    suspend fun load(next: Int) {
        loading = true
        error = null
        try {
            if (browsing) {
                cards.clear()
                cards.addAll(MangaApi.trending(30))
                page = 1
                hasNext = false
            } else {
                cards.clear()
                var at = next
                var pulled = 0
                do {
                    val result = MangaApi.search(
                        query = query.trim(),
                        page = at,
                        type = type,
                        status = status,
                        genre = genre,
                    )
                    cards.addAll(result.items.filter { row -> cards.none { it.id == row.id } })
                    page = result.page
                    hasNext = result.hasNext
                    // Upstream's own idea of the page just read, so a redirect
                    // or a clamped page cannot make the walk stand still.
                    at = result.page + 1
                    pulled++
                } while (hasNext && pulled < AUTO_PAGES)
            }
        } catch (e: CancellationException) {
            // The next keystroke, or walking into a title, ends this load by
            // cancelling it. Compose cancels a composition's coroutines with a
            // CancellationException that says "the coroutine scope left the
            // composition", and a `catch (Exception)` reads that as a failure —
            // which is how a search that was merely replaced showed a cause
            // that was not one. A cancellation is not an error: rethrow it.
            throw e
        } catch (e: Exception) {
            error = e.message ?: "The catalogue did not answer."
        } finally {
            loading = false
        }
    }

}

/**
 * How many catalogue pages one search may pull before it stops. Twenty-four
 * cards a page, so several hundred titles — enough that "load all pages" means
 * what it says for any real search, and a bound in case upstream never runs
 * out.
 */
private const val AUTO_PAGES = 20

@Composable
private fun MangaBrowseScreen(
    feed: MangaFeed,
    positions: List<MangaPosition>,
    listState: LazyListState,
    onOpen: (MangaCard) -> Unit,
    onResume: (MangaPosition) -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = remember(maxWidth) { posterGridColumnCountForWidth(maxWidth) }
        // Derived, not rebuilt: a keystroke in the search field recomposes this
        // screen, and mapping and chunking a few hundred cards on every one of
        // those recompositions is work whose answer did not change. `derivedStateOf`
        // keeps the last answer until the card list itself changes.
        val rows by remember(columns) {
            derivedStateOf {
                feed.cards
                    .map { it.asPreview() }
                    .chunked(columns)
            }
        }

        NuvioScreen(
            horizontalPadding = 0.dp,
            listState = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            stickyHeader {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(tokens.colors.background)
                            .nuvioConsumePointerEvents(),
                    )
                    Column(modifier = Modifier.fillMaxWidth()) {
                        NuvioScreenHeader(
                            title = "Manga",
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            NuvioInputField(
                                value = feed.query,
                                onValueChange = { feed.query = it },
                                placeholder = "Search manga",
                                trailingContent = if (feed.query.isNotBlank()) {
                                    {
                                        IconButton(onClick = { feed.query = "" }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = "Clear search",
                                                tint = tokens.colors.textMuted,
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        MangaFilterRow(feed = feed)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            if (feed.browsing && positions.isNotEmpty()) {
                item(key = "continue") {
                    NuvioShelfSection(
                        title = "Continue reading",
                        entries = positions.toList(),
                        modifier = Modifier.fillMaxWidth(),
                        headerHorizontalPadding = 16.dp,
                        rowContentPadding = PaddingValues(horizontal = 16.dp),
                        itemSpacing = 12.dp,
                        key = { position -> position.id },
                    ) { position ->
                        NuvioPosterCard(
                            title = position.title,
                            imageUrl = position.poster.ifBlank { null },
                            detailLine = "Ch. ${position.number}",
                            onClick = { onResume(position) },
                        )
                    }
                }
            }

            item(key = "heading") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = when {
                            feed.query.isNotBlank() -> "Results"
                            feed.filtered -> "Browse"
                            else -> "Trending"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (feed.error != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = feed.error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (!feed.browsing && !feed.loading) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${feed.cards.size} titles",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                        )
                    }
                }
            }

            itemsIndexed(rows, key = { index, _ -> "row-$index" }) { _, row ->
                PosterGridRow(
                    items = row,
                    columns = columns,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onPosterClick = { preview ->
                        feed.cards.firstOrNull { it.id == preview.id }?.let(onOpen)
                    },
                )
            }

            if (feed.loading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioLoadingIndicator()
                    }
                }
            }

            if (!feed.loading && feed.cards.isEmpty() && feed.error == null) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp, horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (feed.browsing) {
                                "Nothing came back from the catalogue."
                            } else {
                                "Nothing matched — try another title, or clear the filters."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Type, status and genre, as the app's own dropdown chips.
 *
 * The options are upstream's, fetched on first landing: their genre list is
 * dozens of enums, and a list written here would be a list that drifts. Any
 * change is a new catalogue — the same call the web client makes.
 */
@Composable
private fun MangaFilterRow(feed: MangaFeed) {
    val allTypes = remember(feed.options.types) {
        listOf(NuvioDropdownOption("", "All types")) +
            feed.options.types.map { NuvioDropdownOption(it.value, it.label) }
    }
    val allStatuses = remember(feed.options.statuses) {
        listOf(NuvioDropdownOption("", "Any status")) +
            feed.options.statuses.map { NuvioDropdownOption(it.value, it.label) }
    }
    val allGenres = remember(feed.options.genres) {
        listOf(NuvioDropdownOption("", "All genres")) +
            feed.options.genres.map { NuvioDropdownOption(it.value, it.label) }
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        item {
            NuvioDropdownChip(
                title = "Type",
                label = feed.options.types.firstOrNull { it.value == feed.type }?.label ?: "Type",
                selectedKey = feed.type,
                options = allTypes,
                enabled = feed.options.types.isNotEmpty(),
                onSelected = { feed.type = it.key },
            )
        }
        item {
            NuvioDropdownChip(
                title = "Status",
                label = feed.options.statuses.firstOrNull { it.value == feed.status }?.label
                    ?: "Status",
                selectedKey = feed.status,
                options = allStatuses,
                enabled = feed.options.statuses.isNotEmpty(),
                onSelected = { feed.status = it.key },
            )
        }
        item {
            NuvioDropdownChip(
                title = "Genre",
                label = feed.options.genres.firstOrNull { it.value == feed.genre }?.label
                    ?: "Genre",
                selectedKey = feed.genre,
                options = allGenres,
                enabled = feed.options.genres.isNotEmpty(),
                onSelected = { feed.genre = it.key },
            )
        }
        if (feed.filtered) {
            item {
                Chip(
                    label = "Clear",
                    selected = false,
                    onClick = { feed.clearFilters() },
                )
            }
        }
    }
}

/** A card as the app's poster tiles expect one. */
private fun MangaCard.asPreview(): MetaPreview = MetaPreview(
    id = id,
    type = "manga",
    name = title,
    poster = poster.ifBlank { null },
    releaseInfo = latestChapter.takeIf { it.isNotBlank() }?.let { "Ch. $it" },
)

// --------------------------------------------------------------------- title

@Composable
private fun MangaTitleScreen(
    id: String,
    title: String,
    poster: String,
    resume: MangaPosition?,
    inLibrary: Boolean,
    onToggleLibrary: () -> Unit,
    onBack: () -> Unit,
    onRead: (chapters: List<MangaChapter>, index: Int, startPage: Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var card by remember(id) { mutableStateOf(MangaCard(id = id, title = title, poster = poster)) }
    val chapters = remember(id) { mutableStateListOf<MangaChapter>() }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    // A shelf can hand a position over, and so can the title itself: the reader
    // who arrives here from a search has no card to carry it.
    var saved by remember(id) { mutableStateOf(resume) }

    LaunchedEffect(id) {
        loading = true
        error = null
        // The title page used to ask for the account's whole position list and
        // then the title, then the chapters: three round trips in a row on the
        // screen that gates everything else. The position arrives from the
        // shelf now, and the two catalogue calls run together.
        coroutineScope {
            val detail = async { runCatching { MangaApi.title(id) }.getOrNull() }
            val list = async { runCatching { MangaApi.chapters(id) } }

            detail.await()?.let { fetched -> card = fetched }
            try {
                // The title's own page is the one call that can fail without
                // the chapter list being wrong too, so the list is drawn even
                // when the synopsis is not there to go with it.
                chapters.clear()
                chapters.addAll(list.await().getOrThrow())
            } catch (e: CancellationException) {
                // Leaving the title cancels this load; that is not a failure.
                throw e
            } catch (e: Exception) {
                error = e.message ?: "The chapter list did not come back."
            } finally {
                loading = false
            }
        }
    }

    // Where "continue" lands: the chapter that was open, or the newest one.
    val resumeIndex = remember(saved, chapters.size) {
        if (saved == null) -1 else chapters.indexOfFirst { it.id == saved?.chapter }
    }

    NuvioScreen(
        horizontalPadding = 0.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            NuvioScreenHeader(
                title = "",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        item(key = "summary") {
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                Poster(
                    url = card.poster.ifEmpty { poster },
                    modifier = Modifier.width(128.dp).aspectRatio(0.68f),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = card.title.ifBlank { title },
                        style = MaterialTheme.typography.headlineSmall,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val facts = listOf(card.type, card.year, card.status)
                        .filter { it.isNotEmpty() }
                    if (facts.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = facts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                        )
                    }
                    if (chapters.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${chapters.size} chapters",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    if (chapters.isNotEmpty()) {
                        val index = if (resumeIndex >= 0) resumeIndex else 0
                        val page = if (resumeIndex >= 0) saved?.page ?: 0 else 0
                        Button(
                            onClick = { onRead(chapters.toList(), index, page) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (resumeIndex >= 0) {
                                    "Continue Ch. ${chapters[resumeIndex].number}"
                                } else {
                                    "Start reading"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // A title left at chapter 167 still offers its first
                        // chapter: continuing is the common case, not the only one.
                        if (index > 0) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { onRead(chapters.toList(), chapters.lastIndex, 0) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Ch. ${chapters.last().number}")
                            }
                        }
                    }

                    // The one place a title is taken onto the Library tab's
                    // manga shelf. It is drawn even when the chapter list has
                    // not come back yet: wanting a title is not conditional on
                    // its chapters loading.
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onToggleLibrary,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (inLibrary) "In your library — remove" else "Add to library")
                    }
                }
            }
        }

        if (card.description.isNotEmpty()) {
            item(key = "synopsis") {
                Text(
                    text = card.description,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
            }
        }

        if (card.genres.isNotEmpty()) {
            item(key = "genres") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    items(card.genres) { genre ->
                        Chip(label = genre, selected = false, onClick = {})
                    }
                }
            }
        }

        item(key = "mal") {
            MangaMalSection(title = card.title.ifBlank { title })
        }

        item(key = "chapters-heading") {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                HorizontalDivider(color = tokens.colors.borderDefault)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Chapters",
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (loading) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NuvioLoadingIndicator()
                }
            }
        }

        itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { index, chapter ->
            val isResume = chapter.id == saved?.chapter
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRead(chapters.toList(), index, if (isResume) saved?.page ?: 0 else 0) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Chapter ${chapter.number}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isResume) tokens.colors.accent else tokens.colors.textPrimary,
                )
                Spacer(Modifier.weight(1f))
                if (isResume) {
                    Text(
                        text = "page ${(saved?.page ?: 0) + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                    )
                } else if (chapter.at.isNotEmpty()) {
                    Text(
                        text = chapter.at,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }

        item(key = "tail") {
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Poster(url: String, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(tokens.colors.surfaceCard),
    ) {
        if (url.isNotEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * The title page's MyAnimeList row.
 *
 * It is the whole visible surface of the tracker, and it is on the title page
 * rather than in Settings on purpose: the question "where is this on my list?"
 * is asked here, and a link that lives three taps away is not an answer. The
 * connection is the system browser's; the row waits for it and then shows the
 * list row MAL has — or says that it has none, rather than inventing one.
 */
@Composable
private fun MangaMalSection(title: String) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val auth = MangaMal.authState
    var entry by remember(title) { mutableStateOf<MangaMalEntry?>(null) }
    var checking by remember(title) { mutableStateOf(false) }
    var localError by remember(title) { mutableStateOf<String?>(null) }

    LaunchedEffect(title, auth.connected) {
        if (!auth.connected) {
            entry = null
            return@LaunchedEffect
        }
        checking = true
        entry = runCatching { MangaMal.entry(title) }.getOrNull()
        checking = false
    }

    LaunchedEffect(auth.pendingState) {
        if (auth.pendingState != null) MangaMal.awaitSignIn()
    }

    val message = auth.error ?: localError

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        HorizontalDivider(color = tokens.colors.borderDefault)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "MyAnimeList",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            if (auth.connected && auth.username.isNotBlank()) {
                Text(
                    text = auth.username,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        when {
            auth.pendingState != null -> {
                Text(
                    text = if (auth.busy) "Finishing the sign-in…" else "Finish signing in in your browser, then come back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                )
                TextButton(onClick = { MangaMal.cancelSignIn() }) { Text("Cancel") }
            }

            auth.connected -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (checking) "Checking your list…" else malStatusLine(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            localError = null
                            scope.launch { MangaMal.disconnect() }
                        },
                    ) {
                        Text("Disconnect", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            else -> {
                Text(
                    text = "Chapters you read are written to your list automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        localError = null
                        val url = MangaMal.beginSignIn()
                        runCatching { uriHandler.openUri(url) }
                            .onFailure { MangaMal.failSignIn("Could not open a browser for the sign-in.") }
                    },
                ) {
                    Text("Connect MyAnimeList")
                }
            }
        }

        if (message != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

private fun malStatusLine(entry: MangaMalEntry?): String = when {
    entry == null ->
        "Not matched to a MyAnimeList entry yet — a chapter read will look one up."
    entry.status == null -> buildString {
        append("Not on your list")
        if (entry.total > 0) append(" · ${entry.total} chapters")
    }
    else -> buildString {
        append(
            when (entry.status) {
                "reading" -> "Reading"
                "completed" -> "Completed"
                "on_hold" -> "On hold"
                "dropped" -> "Dropped"
                "plan_to_read" -> "Plan to read"
                else -> entry.status.replace('_', ' ')
            },
        )
        if (entry.total > 0) append(" · ${entry.read} / ${entry.total} chapters")
        else append(" · ${entry.read} chapters read")
    }
}

// -------------------------------------------------------------------- reader

/**
 * One page of the strip, with the chapter it belongs to.
 *
 * A reader that holds one chapter at a time can get away with holding pages,
 * because every page belongs to the chapter on screen. This one runs on into
 * the next chapter, so each page has to carry its own chapter: that is what the
 * position write, the tracker and the chapter label all read, and at the seam
 * they are no longer the chapter the strip was anchored on.
 */
private data class ReaderPage(
    val page: MangaPage,
    val chapterIndex: Int,
    val chapterId: String,
    val number: String,
    val pageIndex: Int,
    val chapterPages: Int,
)

/** Pages before the end of the strip where the continuing chapter is fetched. */
private const val SEAM_LEAD_PAGES = 3

@Composable
private fun MangaReaderScreen(
    route: MangaRoute.Reader,
    onBack: () -> Unit,
    onChapter: (index: Int, startPage: Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // The strip: this chapter's pages, then the next chapter's, appended as the
    // reader approaches the end of what is already there. It is deliberately
    // *not* re-seated when the seam is crossed — that is the whole point — so
    // the route's index is only where the strip starts.
    val strip = remember { mutableStateListOf<ReaderPage>() }
    val loaded = remember { mutableStateListOf<Int>() }
    val listState = rememberLazyListState()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var appending by remember { mutableStateOf(false) }

    val total = route.chapters.size
    // Reading runs *up* the chapter numbers, and the list is newest first, so
    // the chapter after this one is the row above it.
    val previousIndex = route.index + 1

    // The chapter under the reader's eye: the one the top page belongs to. It
    // is read from the strip rather than remembered, because a chapter that was
    // scrolled into must not be a different chapter from the one on screen.
    val visible = strip.getOrNull(listState.firstVisibleItemIndex)
    val readingIndex = visible?.chapterIndex ?: route.index
    val nextIndex = readingIndex - 1

    // A chapter is a fresh strip, anchored at its first page — or at the page
    // the shelf left off on.
    LaunchedEffect(route.index, route.chapters) {
        val chapter = route.chapters.getOrNull(route.index) ?: return@LaunchedEffect
        strip.clear()
        loaded.clear()
        loading = true
        error = null
        try {
            val pages = MangaApi.pages(chapter.id)
            strip.addAll(
                pages.mapIndexed { index, page ->
                    ReaderPage(page, route.index, chapter.id, chapter.number, index, pages.size)
                },
            )
            loaded.add(route.index)
        } catch (e: CancellationException) {
            // Backing out of a chapter cancels its load; that is not a failure.
            throw e
        } catch (e: Exception) {
            error = e.message ?: "The pages did not come back."
        } finally {
            loading = false
        }
        listState.scrollToItem(route.startPage.coerceIn(0, maxOf(0, strip.lastIndex)))

        // The next chapter's pages are fetched now, so the seam is a scroll
        // rather than a wait when it comes.
        route.chapters.getOrNull(route.index - 1)?.let { next -> MangaApi.prefetch(next.id) }
    }

    // The seam: within a few pages of the end of the strip, the chapter that
    // continues it is fetched and appended to the same list. Appended, not
    // pushed — the reader never sees a boundary, because there is not one: as
    // far as this list is concerned the next chapter is more of the same strip.
    LaunchedEffect(route.chapters) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (appending || strip.isEmpty()) return@collect
                val seam = loaded.lastOrNull() ?: return@collect
                val next = seam - 1
                if (next < 0) return@collect
                if (lastVisible < strip.lastIndex - SEAM_LEAD_PAGES) return@collect
                val chapter = route.chapters.getOrNull(next) ?: return@collect
                appending = true
                try {
                    val pages = MangaApi.pages(chapter.id)
                    strip.addAll(
                        pages.mapIndexed { index, page ->
                            ReaderPage(page, next, chapter.id, chapter.number, index, pages.size)
                        },
                    )
                    loaded.add(next)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A chapter that did not come back is still reachable by the
                    // button at the end of the strip; it is not an error over
                    // the pages already on screen.
                } finally {
                    appending = false
                }
            }
    }

    // Every page is asked for as soon as a chapter's list is known, not when
    // the reader scrolls to it: a strip that fills in behind the finger reads
    // as a strip that is not there, and on a slow connection it is the whole
    // difference between reading and waiting. The queue is Coil's own, so these
    // share its pool with the pages on screen rather than opening a second
    // client. Done once per chapter, keyed so the effect re-running on a
    // recomposition cannot enqueue the same forty requests twice.
    val platformContext = LocalPlatformContext.current
    var prefetchedChapter by remember { mutableStateOf<String?>(null) }
    val stripTail = strip.lastOrNull()
    LaunchedEffect(stripTail?.chapterId, strip.size) {
        val id = stripTail?.chapterId ?: return@LaunchedEffect
        if (prefetchedChapter == id) return@LaunchedEffect
        prefetchedChapter = id
        val loader = coil3.SingletonImageLoader.get(platformContext)
        for (page in strip.filter { it.chapterId == id }) {
            if (page.page.u.isBlank()) continue
            loader.enqueue(
                coil3.request.ImageRequest.Builder(platformContext)
                    .data(page.page.u)
                    .build(),
            )
        }
    }

    // Opening a chapter is what the tracker is told about, and with a strip that
    // runs on, the chapter to tell it about is the one *under the reader* rather
    // than the one the strip started on: a chapter scrolled into is a chapter
    // being read. Its own effect, so a slow MyAnimeList call never delays a page.
    LaunchedEffect(route.title, route.chapters) {
        snapshotFlow { strip.getOrNull(listState.firstVisibleItemIndex)?.chapterIndex }
            .distinctUntilChanged()
            .collect { index ->
                val chapter = index?.let(route.chapters::getOrNull) ?: return@collect
                MangaMal.chapterRead(route.title, chapter.number)
            }
    }

    // Every place the reader stops is a position written down — against the
    // chapter that page is in, which at the seam is not the chapter the strip
    // was anchored on. The old shape delayed *inside* the collector, so a fling
    // through forty pages queued forty writes half a second apart and the last
    // one to land was whatever the queue reached — not where the reader
    // actually stopped. Debouncing upstream collapses the fling into one write,
    // at the place it ended.
    LaunchedEffect(route.id, route.chapters) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(600)
            .distinctUntilChanged()
            .collect { index ->
                val at = strip.getOrNull(index) ?: return@collect
                MangaApi.savePosition(
                    MangaPosition(
                        id = route.id,
                        title = route.title,
                        poster = route.poster,
                        chapter = at.chapterId,
                        number = at.number,
                        page = at.pageIndex,
                        pages = at.chapterPages,
                    ),
                )
            }
    }

    Box(modifier = Modifier.fillMaxSize().background(tokens.colors.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(
                items = strip,
                // A page's URL alone is not a key: two chapters can name the
                // same file, and a duplicate key is a crash rather than a
                // redraw. The chapter and the page inside it are unique.
                key = { _, page -> "${page.chapterId}:${page.pageIndex}" },
                contentType = { _, _ -> "page" },
            ) { _, page ->
                AsyncImage(
                    model = page.page.u,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }

            item {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    if (loading) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            NuvioLoadingIndicator()
                        }
                    }
                    if (error != null) {
                        Text(
                            text = error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // The strip already runs on into the next chapter, so the
                    // button for it is only for a reader who wants it *now* —
                    // and when the chapter below is already loaded, saying so
                    // is more use than a button that scrolls them into it.
                    if (nextIndex >= 0 && loaded.contains(nextIndex)) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Ch. ${route.chapters[nextIndex].number} continues below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (previousIndex < total) {
                            OutlinedButton(onClick = { onChapter(previousIndex, 0) }) {
                                Text("← Ch. ${route.chapters[previousIndex].number}")
                            }
                        }
                        val jump = nextIndex.takeIf { it >= 0 && !loaded.contains(it) }
                        if (jump != null) {
                            Button(onClick = { onChapter(jump, 0) }) {
                                Text("Ch. ${route.chapters[jump].number} →")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onBack) { Text("Back to the title") }
                }
            }
        }

        // The chrome floats over the pages: a strip has no room for a bar, and
        // a chapter number is all the reader needs to know where they are.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                // The tab is drawn edge to edge, so the strip has to keep itself
                // out of the status bar rather than being placed below it.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 16.dp),
            )
            Text(
                text = "Ch. ${route.chapters.getOrNull(readingIndex)?.number ?: "—"} / $total",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = route.title,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(160.dp),
            )
        }
    }
}
