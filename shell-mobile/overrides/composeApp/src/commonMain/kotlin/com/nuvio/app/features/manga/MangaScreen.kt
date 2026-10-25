package com.nuvio.app.features.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop169
import androidx.compose.material.icons.rounded.CropPortrait
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subject
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioConsumePointerEvents
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.home.components.posterGridColumnCountForWidth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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
        /**
         * Whether the Library handed this title over.
         *
         * A title opened from the Library's manga shelf is the Library's
         * visit, not the manga tab's: closing it returns to the shelf that was
         * open rather than dropping the reader in a tab they never chose. A
         * title opened here stays here.
         */
        val fromLibrary: Boolean = false,
    ) : MangaRoute
    data class Reader(
        val id: String,
        val title: String,
        val poster: String,
        val chapters: List<MangaChapter>,
        val index: Int,
        val startPage: Int = 0,
        /** Carried through from the title page, so "back" still knows. */
        val fromLibrary: Boolean = false,
    ) : MangaRoute

    /**
     * The title on the site it came from, in this app's own window.
     *
     * The old client's WebView tile did exactly this: it did not hand the reader
     * to a browser and hope they came back, it loaded mangafire's own title page
     * over the app. The id is the page's address there — their API and their
     * pages name a title by the same string — so no lookup stands between the
     * tile and the page.
     */
    data class Source(
        val id: String,
        val title: String,
        val poster: String,
        val fromLibrary: Boolean = false,
    ) : MangaRoute

    /**
     * The chapters kept on this phone.
     *
     * A destination of its own, not a filter on the catalogue: with no
     * connection the catalogue is exactly what this screen cannot draw, and the
     * saved chapters are exactly what it can. Everything here comes from the
     * app's own files through loopback, so the screen opens in airplane mode.
     */
    data object Downloads : MangaRoute
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
        // A shelf imported before the app stored an id per row has none to give,
        // so the ids are derived once here — one list read, matched locally —
        // rather than leaving every title to a name search that can lose it.
        runCatching { MangaMal.matchShelf() }
        // What is kept on this phone, once the tab is drawn: the chapter rows
        // and the Downloads screen both read this, and the queue may still be
        // running from a title opened before the tab was last left.
        MangaDownloads.refresh()
        MangaDownloads.watch()
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
            fromLibrary = true,
        )
    }

    // The shelf of titles the reader added, as ids: the title page shows
    // whether the title being looked at is on it, and the button under that
    // writes both the shelf and — when MyAnimeList is linked — the list.
    // Keyed on the revision the API bumps, so an id is never stale.
    // Opening a title asks the linked accounts where this one stands — MAL
    // first, AniList second — and moves the shelf forward when they know a
    // chapter this device does not. The tracker is the source of truth for "how
    // far am I", which is what makes a shelf imported elsewhere agree with it
    // even after the reader stopped using one of the two.
    LaunchedEffect((route as? MangaRoute.Title)?.id) {
        val title = route as? MangaRoute.Title ?: return@LaunchedEffect
        val synced = com.nuvio.app.features.anilist.AniList.syncProgress(
            MangaCard(id = title.id, title = title.title, poster = title.poster),
            // The shelf's own match, by id: the account is asked about the row it
            // already knows instead of searching for the title again.
            malId = MangaMal.shelfId(title.id),
        ) ?: return@LaunchedEffect
        positions.removeAll { it.id == title.id }
        positions.add(synced)
        // The page on screen should say the same thing the shelf now does.
        route = title.copy(resume = synced)
    }

    var libraryIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(MangaApi.libraryRevision) {
        val shelf = runCatching { MangaApi.library() }.getOrDefault(emptyList())
        libraryIds = shelf.map { it.id }.toSet()
        // The ids the shelf already holds are the ones the title page should ask
        // by. Handing them over here, before any title is opened, is what means
        // nobody has to re-open 121 titles to have them recognised.
        MangaMal.learnShelf(shelf)
    }

    // One place that knows what adding a title to the shelf means, because more
    // than one screen now says it: the title page's button and the reader's
    // rail. A title added while an account is linked is a title the account
    // should know about too — either account.
    fun toggleLibrary(id: String, title: String, poster: String) {
        val wanted = !libraryIds.contains(id)
        scope.launch {
            if (wanted) {
                // An entry the reader picked rides along: a shelf row that knows
                // its MAL id never has to be matched by name again.
                MangaApi.addToLibrary(
                    MangaCard(id = id, title = title, poster = poster),
                    mal = MangaMal.pickedId(title).takeIf { it > 0L },
                )
                MangaMal.addToPlan(title.ifBlank { id })
                com.nuvio.app.features.anilist.AniList.addToPlan(title.ifBlank { id })
            } else {
                MangaApi.removeFromLibrary(id)
            }
        }
    }

    // Closing a title that the Library opened is a return to the Library: the
    // tab is asked to show it again, and the manga tab is left on its shelf for
    // the next time it is chosen.
    fun closeTitle(title: MangaRoute.Title) {
        if (title.fromLibrary) MangaNav.requestReturnToLibrary()
        route = MangaRoute.Browse
    }

    // A source's own results are one step in from the mixed list, so back is
    // that step - not leaving the app from a view the reader just opened.
    PlatformBackHandler(enabled = route != MangaRoute.Browse || feed.focusSource != null) {
        when (val current = route) {
            is MangaRoute.Reader ->
                route = MangaRoute.Title(
                    current.id,
                    current.title,
                    current.poster,
                    fromLibrary = current.fromLibrary,
                )
            is MangaRoute.Source -> route = MangaRoute.Title(
                current.id,
                current.title,
                current.poster,
                fromLibrary = current.fromLibrary,
            )
            is MangaRoute.Title -> closeTitle(current)
            MangaRoute.Downloads -> route = MangaRoute.Browse
            else -> if (feed.focusSource != null) {
                feed.focusSource = null
                scope.launch { listState.scrollToItem(0) }
            } else {
                route = MangaRoute.Browse
            }
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
                onDownloads = { route = MangaRoute.Downloads },
            )

            MangaRoute.Downloads -> MangaDownloadsScreen(
                onBack = { route = MangaRoute.Browse },
                onRead = { hid, title, poster, chapters, index ->
                    route = MangaRoute.Reader(
                        id = hid,
                        title = title,
                        poster = poster,
                        chapters = chapters,
                        index = index,
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
                    toggleLibrary(current.id, current.title, current.poster)
                },
                onBack = { closeTitle(current) },
                // Moving the title to another catalogue is a new id, so the
                // page is replaced rather than re-rendered: the route names the
                // id, and every loader below it is keyed on that.
                onMigrated = { newId, newTitle, newPoster, position ->
                    route = MangaRoute.Title(
                        id = newId,
                        title = newTitle,
                        poster = newPoster,
                        resume = position,
                        fromLibrary = current.fromLibrary,
                    )
                },
                onRead = { chapters, index, page ->
                    route = MangaRoute.Reader(
                        id = current.id,
                        title = current.title,
                        poster = current.poster,
                        chapters = chapters,
                        index = index,
                        startPage = page,
                        fromLibrary = current.fromLibrary,
                    )
                },
            )

            is MangaRoute.Source -> MangaSourceScreen(
                url = mangafireUrl(current.id),
                title = current.title,
                onBack = {
                    route = MangaRoute.Title(
                        current.id,
                        current.title,
                        current.poster,
                        fromLibrary = current.fromLibrary,
                    )
                },
            )

            is MangaRoute.Reader -> MangaReaderScreen(
                route = current,
                inLibrary = libraryIds.contains(current.id),
                onBack = {
                    route = MangaRoute.Title(
                        current.id,
                        current.title,
                        current.poster,
                        fromLibrary = current.fromLibrary,
                    )
                },
                onHome = { route = MangaRoute.Browse },
                onToggleLibrary = {
                    toggleLibrary(current.id, current.title, current.poster)
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

    /**
     * The one catalogue whose results are being read, when the reader tapped a
     * source heading's arrow. Null is the mixed list: every source at once.
     */
    var focusSource by mutableStateOf<String?>(null)

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
        // A new catalogue is a new search: whatever source was being read
        // alone was a view of the old answer, not a place to stay.
        if (next == 1) focusSource = null
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
    onDownloads: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()

    // Opening or closing one source's results swaps the whole list under the
    // scroll position, so both land at the top: an offset earned reading the
    // third section would otherwise drop the reader into the middle of a grid
    // whose heading - the way back - is somewhere above them.
    fun showSource(key: String?) {
        feed.focusSource = key
        scope.launch { listState.scrollToItem(0) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = remember(maxWidth) { posterGridColumnCountForWidth(maxWidth) }
        // A keyword search asks every source the reader left on, and the answer
        // is one interleaved list. Split back into catalogues it reads the way
        // a search should: one heading per source, its own row of posters, and
        // its arrow leading to all of them. Two sources is the threshold - a
        // search answered by one catalogue is that catalogue's grid, and a
        // heading over a single section says nothing the search field does not.
        // Derived, not rebuilt: a keystroke recomposes this screen, and grouping
        // a few hundred cards on every one of those recompositions is work whose
        // answer did not change.
        val groups by remember {
            derivedStateOf { mangaSearchGroups(feed.cards) }
        }
        val sectioned = groups.size > 1
        val focused = feed.focusSource?.let { key -> groups.firstOrNull { it.key == key } }

        // Derived, not rebuilt: mapping and chunking a few hundred cards on every
        // recomposition is work whose answer did not change. `derivedStateOf`
        // keeps the last answer until the card list, the chosen source or the
        // column count itself changes.
        //
        // Read through the `groups` delegate, not through `focused`: a local
        // computed in composition is the copy this lambda was born with, so a
        // tap on a heading would leave the grid showing every source's cards
        // under one source's name. `groups` is state, and reading it here is
        // what makes the grid follow the choice.
        val rows by remember(columns) {
            derivedStateOf {
                val focusedCards = groups.firstOrNull { it.key == feed.focusSource }?.cards
                (focusedCards ?: feed.cards)
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
                            // The way into the saved chapters is on the shelf's
                            // own header, because it is the one destination that
                            // works when the catalogue does not: a reader with no
                            // connection still gets a screen with a door on it.
                            actions = {
                                // Read here rather than at the top of the screen:
                                // a download finishing should redraw this button,
                                // not the whole catalogue grid with it.
                                val saved = MangaDownloads.index
                                TextButton(onClick = onDownloads) {
                                    Icon(
                                        imageVector = Icons.Rounded.DownloadForOffline,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (saved.titles.isEmpty()) {
                                            tokens.colors.textMuted
                                        } else {
                                            tokens.colors.accent
                                        },
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (saved.titles.isEmpty()) "Offline"
                                        else "Offline · ${saved.titles.sumOf { it.chapters }}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = tokens.colors.textSecondary,
                                    )
                                }
                            },
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
                if (focused != null) {
                    // One source's results: the heading is the way back to the
                    // mixed list, and it says how many titles are behind it.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { showSource(null) }) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = "All sources",
                                tint = tokens.colors.textPrimary,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                        ) {
                            Text(
                                text = focused.name,
                                style = MaterialTheme.typography.titleLarge,
                                color = tokens.colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = listOfNotNull(
                                    mangaSourceLanguageName(focused.lang).ifBlank { null },
                                    "${focused.cards.size} titles",
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textMuted,
                            )
                        }
                    }
                } else {
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
            }

            if (sectioned && focused == null) {
                items(groups, key = { group -> "source-${group.key}" }) { group ->
                    MangaSourceShelf(
                        group = group,
                        onOpenSource = { showSource(group.key) },
                        onOpenCard = onOpen,
                    )
                }
            } else {
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
 * One catalogue's section of a mixed search: the source's name, its language,
 * a row of its results, and an arrow to all of them.
 *
 * The heading is the arrow's target as well as its label - the name and the
 * language are what a reader aims at, and a 32dp chevron is a small thing to
 * hit. The row scrolls sideways so four sources do not turn one search into a
 * screen of endless vertical grid; the arrow is how a reader goes deeper in one
 * catalogue without losing the other answers.
 */
@Composable
private fun MangaSourceShelf(
    group: MangaSearchGroup,
    onOpenSource: () -> Unit,
    onOpenCard: (MangaCard) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val language = mangaSourceLanguageName(group.lang)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSource)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (language.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = language,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(tokens.colors.surface, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "All ${group.cards.size} results from ${group.name}",
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(group.cards, key = { card -> card.id }) { card ->
                NuvioPosterCard(
                    title = card.title,
                    imageUrl = card.poster.ifBlank { null },
                    onClick = { onOpenCard(card) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
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
    onMigrated: (id: String, title: String, poster: String, resume: MangaPosition?) -> Unit,
    onRead: (chapters: List<MangaChapter>, index: Int, startPage: Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    var card by remember(id) { mutableStateOf(MangaCard(id = id, title = title, poster = poster)) }
    val chapters = remember(id) { mutableStateListOf<MangaChapter>() }
    var loading by remember(id) { mutableStateOf(true) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    // A shelf can hand a position over, and so can the title itself: the reader
    // who arrives here from a search has no card to carry it.
    var saved by remember(id) { mutableStateOf(resume) }

    // Where this title sits on MyAnimeList. Held here rather than inside the
    // tracking row because two things read it now — the row under the actions
    // and the picker the tracking tile opens — and two copies would be two
    // answers on one screen.
    val auth = MangaMal.authState
    var lookup by remember(id) { mutableStateOf<MangaMalLookup?>(null) }
    var checking by remember(id) { mutableStateOf(false) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    var tracking by remember(id) { mutableStateOf(false) }

    // The chapters this device has actually put on screen, by number. Empty is
    // an answer (a title read before the record existed, or brought in by an
    // import), and that answer falls back to the boundary rule below.
    var recorded by remember(id) { mutableStateOf<Set<String>>(emptySet()) }

    // Whether the list on screen is the whole catalogue or only the chapters
    // saved on this phone — the server says which, because with no connection
    // the second is all it can answer, and a short list with no explanation
    // reads as a series the catalogue cut down.
    var offlineList by remember(id) { mutableStateOf(false) }
    var downloading by remember(id) { mutableStateOf(false) }
    var migrating by remember(id) { mutableStateOf(false) }

    // Keyed on the record's revision as well as the title: coming back from the
    // reader re-reads it, which is what puts the chapter just finished into the
    // fraction and under a *read* mark without either screen telling the other.
    LaunchedEffect(id, MangaApi.readRevision) {
        recorded = runCatching { MangaApi.readChapters(id) }.getOrDefault(recorded)
    }

    LaunchedEffect(id) {
        loading = true
        error = null
        // What is saved for this title, so the buttons are right before the
        // catalogue answers — and so the page can draw at all with no network.
        MangaDownloads.refresh()
        MangaDownloads.watch()
        // The title page used to ask for the account's whole position list and
        // then the title, then the chapters: three round trips in a row on the
        // screen that gates everything else. The position arrives from the
        // shelf now, and the two catalogue calls run together.
        coroutineScope {
            val detail = async { runCatching { MangaApi.title(id) }.getOrNull() }
            val list = async { runCatching { MangaApi.chapterList(id) } }

            detail.await()?.let { fetched -> card = fetched }
            try {
                // The title's own page is the one call that can fail without
                // the chapter list being wrong too, so the list is drawn even
                // when the synopsis is not there to go with it.
                val answer = list.await().getOrThrow()
                chapters.clear()
                chapters.addAll(answer.chapters)
                offlineList = answer.offline
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
    //
    // By id first, then by number, because a position can name a row that the
    // duplicate rule dropped — the stored id was one of two rows that both say
    // "Ch. 32", and the one that is left says it too. Without the second look a
    // reader's position would vanish from the screen that offers to resume it.
    val resumeIndex = remember(saved, chapters.size) {
        val pending = saved
        if (pending == null) {
            -1
        } else {
            val byId = chapters.indexOfFirst { it.id == pending.chapter }
            if (byId >= 0) byId else {
                val number = pending.number.trim()
                if (number.isEmpty()) -1
                else chapters.indexOfFirst { it.number.trim() == number }
            }
        }
    }

    // What the rows compare against: the id of the row actually on screen, so a
    // position found by number still marks its chapter the same way.
    val resumeChapterId = chapters.getOrNull(resumeIndex)?.id

    // The next chapter, read off the chapters' own posting dates. Recomputed
    // when the list arrives and when the title's status does; nothing else can
    // change the answer, so nothing else re-runs it.
    val eta = remember(chapters.size, card.status) {
        MangaEta.estimate(chapters.toList(), card.status)
    }

    // How much of the list is read: the chapters this device recorded, the
    // chapters the account claims, and — only when there is no record at all —
    // the old boundary rule from the position. One function, because the bar,
    // the fraction and the ink on each row have to be the same answer, and a
    // rule that only a phone can check is a rule checked by looking at a phone.
    // `MangaReadRecordTest` holds it on the JVM.
    val accountRead = (lookup as? MangaMalLookup.Found)?.entry?.read ?: 0
    val readNumbers = remember(chapters.size, resumeChapterId, lookup, recorded, accountRead) {
        readChapterNumbers(chapters.toList(), recorded, accountRead, resumeChapterId)
    }
    val readCount = readNumbers.size

    // A title read before this record existed has a position and no marks. The
    // chapters the old boundary rule claims are offered once, so the count does
    // not drop from "12 of 33" to "1 of 33" the first time the reader opens a
    // chapter on this build. The server refuses the write if a record exists,
    // so this can never overwrite the reader's own history.
    LaunchedEffect(id, chapters.size, resumeChapterId, recorded) {
        if (recorded.isNotEmpty() || chapters.isEmpty()) return@LaunchedEffect
        val at = chapters.indexOfFirst { it.id == resumeChapterId }
        if (at < 0) return@LaunchedEffect
        val numbers = chapters.drop(at).map { it.number.trim() }.filter { it.isNotEmpty() }
        if (numbers.isEmpty()) return@LaunchedEffect
        recorded = numbers.toSet()
        runCatching { MangaApi.seedReadMarks(id, numbers) }
    }

    // Asked with every id worth trying, in the order that outranks a guess: the
    // reader's pick, the shelf row's id, the catalogue's own `malId`, and only
    // then a search. A detail that has not arrived yet asks with what it has and
    // asks again when `card.malId` lands.
    LaunchedEffect(card.title, card.malId, auth.connected, attempt) {
        if (!auth.connected) {
            lookup = null
            return@LaunchedEffect
        }
        val name = card.title.ifBlank { title }
        checking = true
        lookup = runCatching {
            MangaMal.lookup(
                name,
                mangaId = id,
                malId = card.malId.toLongOrNull() ?: 0L,
                force = attempt > 0,
            )
        }.getOrElse { MangaMalLookup.Failed(it.message ?: "MyAnimeList did not answer.") }
        checking = false
    }

    // The browser's half of a sign-in is waited for here, so the row that says
    // "finishing the sign-in" is the one that is on screen.
    LaunchedEffect(auth.pendingState) {
        if (auth.pendingState != null) MangaMal.awaitSignIn()
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
                        // The list is newest first, so the *first* chapter is
                        // the last row — and a title nobody has opened starts
                        // there, not at the newest chapter, which would mark a
                        // whole unread series as read on the first tap.
                        val index = if (resumeIndex >= 0) resumeIndex else chapters.lastIndex
                        val page = if (resumeIndex >= 0) saved?.page ?: 0 else 0
                        // A catalogue can carry chapters without numbers - a
                        // oneshot, a special, or an extension that reads them
                        // out of a label. "Start Ch. " is not a sentence, so a
                        // blank number falls back to the act rather than the
                        // number.
                        val verb = if (resumeIndex >= 0) "Continue" else "Start"
                        val number = chapters[index].number.trim()
                        Button(
                            onClick = { onRead(chapters.toList(), index, page) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = if (number.isEmpty()) "$verb reading" else "$verb Ch. $number",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // A title left at chapter 167 still offers its first
                        // chapter: continuing is the common case, not the only
                        // one. Not when the resume *is* already the first
                        // chapter — two buttons onto one chapter is a button
                        // that does nothing. By name rather than by row: the
                        // catalogue carries duplicate numbers (a re-upload, a
                        // second scanlation), and "Continue Ch. 1" over a
                        // "Ch. 1" button reads as a bug whether or not the
                        // rows are different chapters.
                        if (chapters.last().number.isNotBlank() &&
                            chapters.last().number != chapters[index].number
                        ) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { onRead(chapters.toList(), chapters.lastIndex, 0) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Ch. ${chapters.last().number}")
                            }
                        }
                    }
                }
            }
        }

        // The four things that can be done with a title before reading it, in
        // one row where the old client kept them: keep it, see when the next
        // chapter is due, say which MyAnimeList entry it is, or walk to the
        // site it came from. Drawn even when the chapter list has not come
        // back: wanting a title is not conditional on its chapters loading.
        item(key = "actions") {
            MangaActionRow(
                inLibrary = inLibrary,
                eta = eta,
                onLibrary = onToggleLibrary,
                onTracking = { tracking = true },
                onMigrate = { migrating = true },
            )
            Spacer(Modifier.height(14.dp))
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
            MangaMalLine(
                auth = auth,
                lookup = lookup,
                checking = checking,
                onOpen = { tracking = true },
                onRetry = { attempt++ },
            )
            Spacer(Modifier.height(12.dp))
        }

        item(key = "chapters-heading") {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                HorizontalDivider(color = tokens.colors.borderDefault)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Chapters",
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (readCount > 0) {
                        Text(
                            text = "$readCount of ${chapters.size} read",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textMuted,
                        )
                    }
                }
                if (readCount > 0 && chapters.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    // The bar is drawn rather than asked for: three pixels of
                    // accent under a heading is a track and a fill, and a
                    // component with its own insets would be a taller thing
                    // than the heading it belongs to.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(tokens.colors.surfaceCard),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(readCount.toFloat() / chapters.size)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(tokens.colors.accent),
                        )
                    }
                }
                if (chapters.isNotEmpty()) {
                    // Read inside the heading, so a poll redraws the summary and
                    // the rows whose buttons changed rather than the whole list.
                    val savedHere = MangaDownloads.index.savedOf(id)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Downloading is per chapter on the rows, and in bulk
                        // here: a reader who wants the next few taps six rows,
                        // and one who wants the series should not have to.
                        TextButton(
                            onClick = { downloading = true },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DownloadForOffline,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Download chapters",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (savedHere.isNotEmpty()) {
                            Text(
                                text = "${savedHere.size} saved · " +
                                    savedSize(savedHere.sumOf { it.bytes }),
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.textMuted,
                            )
                        }
                    }
                }
                if (offlineList) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "No connection — showing the chapters saved on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
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
            val isResume = resumeChapterId != null && chapter.id == resumeChapterId
            // Read is a record now, not a boundary: each row asks whether this
            // chapter itself was opened. A reader who jumped to chapter 40 and
            // left the first thirty-nine alone sees forty marked, not sixty —
            // the bar and the ink are still the same fact at two sizes.
            val isRead = chapter.number.trim().let { number ->
                number.isNotEmpty() && number in readNumbers
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRead(chapters.toList(), index, if (isResume) saved?.page ?: 0 else 0) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(24.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isResume -> tokens.colors.accent
                                isRead -> tokens.colors.textMuted.copy(alpha = 0.45f)
                                else -> Color.Transparent
                            },
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Chapter ${chapter.number}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = when {
                        isResume -> tokens.colors.accent
                        isRead -> tokens.colors.textMuted
                        else -> tokens.colors.textPrimary
                    },
                )
                Spacer(Modifier.weight(1f))
                if (isResume) {
                    Text(
                        text = "page ${(saved?.page ?: 0) + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                    )
                } else if (isRead) {
                    Text(
                        text = "read",
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
                // The row is also the chapter's download button: saving one
                // chapter is the common case, and making it a trip to a sheet
                // would be a trip for every chapter.
                ChapterDownloadAction(
                    state = MangaDownloads.index.stateOf(chapter.id),
                    progress = MangaDownloads.index.progressOf(chapter.id),
                    onSave = {
                        scope.launch {
                            MangaDownloads.download(
                                id,
                                card.title.ifBlank { title },
                                card.poster.ifBlank { poster },
                                listOf(chapter),
                            )
                        }
                    },
                    onRemove = { scope.launch { MangaDownloads.remove(id, chapter.id) } },
                )
            }
        }

        item(key = "tail") {
            Spacer(Modifier.height(24.dp))
        }
    }

    if (tracking) {
        MangaTrackSheet(
            title = card.title.ifBlank { title },
            lookup = lookup,
            checking = checking,
            onPicked = { hit ->
                val name = card.title.ifBlank { title }
                if (MangaMal.rememberPick(name, hit.id, hit.title)) {
                    // A shelf row that knows its id never has to be matched by
                    // name again — a refresh, a sync and a chapter read all ask
                    // by that id from here on. A title that is not on the shelf
                    // is not put on it by picking: tracking is not keeping.
                    if (inLibrary) {
                        scope.launch {
                            MangaApi.addToLibrary(
                                MangaCard(
                                    id = id,
                                    title = name,
                                    poster = card.poster.ifBlank { poster },
                                ),
                                mal = hit.id,
                            )
                        }
                    }
                    attempt++
                }
                tracking = false
            },
            onRetry = { attempt++ },
            onDismiss = { tracking = false },
        )
    }

    if (migrating) {
        MangaMigrateSheet(
            id = id,
            title = card.title.ifBlank { title },
            poster = card.poster.ifBlank { poster },
            position = saved,
            inLibrary = inLibrary,
            malId = MangaMal.shelfId(id),
            onMigrated = { newId, newTitle, newPoster, moved ->
                migrating = false
                onMigrated(newId, newTitle, newPoster, moved)
            },
            onDismiss = { migrating = false },
        )
    }

    if (downloading) {
        MangaDownloadSheet(
            title = card.title.ifBlank { title },
            chapters = chapters.toList(),
            saved = MangaDownloads.index.savedOf(id),
            onDownload = { picked ->
                val name = card.title.ifBlank { title }
                val art = card.poster.ifBlank { poster }
                scope.launch { MangaDownloads.download(id, name, art, picked) }
            },
            onRemoveAll = { scope.launch { MangaDownloads.removeTitle(id) } },
            onDismiss = { downloading = false },
        )
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
 * row is short on purpose — it says which entry this title is and where the
 * reader stands on it, and the tracking tile above it is where the entry is
 * chosen. Two places that could disagree about the same fact would be one too
 * many, so the choice lives in exactly one of them.
 */
@Composable
private fun MangaMalLine(
    auth: MangaMalAuthState,
    lookup: MangaMalLookup?,
    checking: Boolean,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    auth.pendingState != null ->
                        if (auth.busy) "Finishing the sign-in…"
                        else "Finish signing in in your browser, then come back."
                    !auth.connected ->
                        "Not connected — the tracking tile above is where that is fixed."
                    checking -> "Checking your list…"
                    else -> malStatusLine(lookup)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (lookup is MangaMalLookup.Failed) tokens.colors.textMuted
                else tokens.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            // A question that could not be asked is worth asking again; a title
            // the account does not know is not.
            if (lookup is MangaMalLookup.Failed) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
            if (auth.connected || auth.pendingState != null) {
                TextButton(onClick = onOpen) {
                    Text("Change", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (auth.error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = auth.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The title page's four actions, in one row under the header.
 *
 * Keep the title, read when the next chapter is due, say which MyAnimeList
 * entry it is, or move it to another catalogue. The estimate is a reading
 * rather than a control - there is nothing to press - so it is a tile with no
 * handler and its own spoken label. The other three are buttons, and each one
 * does the thing its name says.
 *
 * The fourth tile used to be a WebView onto mangafire, which was one site's
 * idea of "see this elsewhere" and no use at all for a title that came from an
 * extension. It is a migration instead: the same title on a source the reader
 * already has on, with the chapter they are on carried across.
 */
@Composable
private fun MangaActionRow(
    inLibrary: Boolean,
    eta: MangaEta?,
    onLibrary: () -> Unit,
    onTracking: () -> Unit,
    onMigrate: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MangaActionTile(
            icon = if (inLibrary) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            label = if (inLibrary) "In library" else "Library",
            description = if (inLibrary) {
                "Remove this title from your library"
            } else {
                "Add this title to your library"
            },
            tint = if (inLibrary) tokens.colors.accent else tokens.colors.textPrimary,
            onClick = onLibrary,
            modifier = Modifier.weight(1f),
        )
        MangaActionTile(
            icon = Icons.Rounded.Schedule,
            label = MangaEta.chip(eta),
            description = "Next chapter — " + MangaEta.describe(eta),
            tint = if (eta == null) tokens.colors.textMuted else tokens.colors.textPrimary,
            onClick = null,
            modifier = Modifier.weight(1f),
        )
        MangaActionTile(
            icon = Icons.Rounded.Sync,
            label = "Tracking",
            description = "Pick the MyAnimeList entry this title is",
            tint = tokens.colors.textPrimary,
            onClick = onTracking,
            modifier = Modifier.weight(1f),
        )
        MangaActionTile(
            icon = Icons.Rounded.SwapHoriz,
            label = "Migrate",
            description = "Move this title to another source, keeping your place",
            tint = tokens.colors.textPrimary,
            onClick = onMigrate,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One tile of the action row: an icon over a short word. */
@Composable
private fun MangaActionTile(
    icon: ImageVector,
    label: String,
    description: String,
    tint: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.md)
    Column(
        modifier = modifier
            .clip(shape)
            .background(tokens.colors.surfaceCard)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Moving one title to another catalogue.
 *
 * A migration is not a second copy: it is the same series read from a source
 * that still works, which is the one thing a phone reader needs when a site
 * dies. So the shell - the title, the chapter, the place on the shelf - stays,
 * and only the id underneath it changes.
 *
 * What travels and what does not:
 *
 * - The chapter position travels by *number*, because a chapter id belongs to
 *   the catalogue that issued it. The new source's chapter list is read first
 *   and the matching number becomes the new position, at the same page.
 * - The read record travels the same way, as chapter numbers, and is seeded
 *   into the new title's empty record.
 * - A title on the shelf moves its row: the new id is added with the old row's
 *   MyAnimeList id, then the old row is removed.
 * - Saved chapters do not travel. They are files under the old id, and the new
 *   source's pages are different files; the old title's Downloads entry stays
 *   until the reader removes it there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaMigrateSheet(
    id: String,
    title: String,
    poster: String,
    position: MangaPosition?,
    inLibrary: Boolean,
    malId: Long,
    onMigrated: (id: String, title: String, poster: String, resume: MangaPosition?) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentSource = mangaSourceId(id)
    var query by rememberSaveable { mutableStateOf(title) }
    var cards by remember { mutableStateOf<List<MangaCard>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    // The same debounce the search tab uses: a word typed at speed is one
    // request, and an empty field is no request at all.
    LaunchedEffect(query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            cards = emptyList()
            failure = null
            searching = false
            return@LaunchedEffect
        }
        delay(400)
        searching = true
        failure = null
        try {
            val answer = MangaApi.search(query = needle)
            cards = answer.items.filter { card ->
                card.source.isNotBlank() && card.source != currentSource && card.id != id
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cards = emptyList()
            failure = e.message ?: "The other sources did not answer."
        } finally {
            searching = false
        }
    }

    suspend fun move(card: MangaCard) {
        busy = card.id
        try {
            var moved: MangaPosition? = null
            val number = position?.number?.trim().orEmpty()
            if (number.isNotEmpty()) {
                val chapters = runCatching {
                    MangaApi.chapterList(card.id).chapters
                }.getOrDefault(emptyList())
                val match = chapters.firstOrNull { it.number.trim() == number }
                if (match != null) {
                    moved = MangaPosition(
                        id = card.id,
                        title = card.title,
                        poster = card.poster,
                        chapter = match.id,
                        number = match.number,
                        page = position?.page ?: 0,
                        pages = position?.pages ?: 0,
                    )
                    runCatching { MangaApi.savePosition(moved) }
                }
            }
            // Numbers, not ids: the record is keyed by title, so the new id
            // starts empty and this is what fills it with what was read.
            runCatching {
                val marks = MangaApi.readChapters(id)
                if (marks.isNotEmpty()) MangaApi.seedReadMarks(card.id, marks.toList())
            }
            if (inLibrary) {
                runCatching {
                    MangaApi.addToLibrary(card, mal = malId.takeIf { it > 0L })
                    MangaApi.removeFromLibrary(id)
                }
            }
            onMigrated(card.id, card.title, card.poster, moved)
        } finally {
            busy = null
        }
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            if (busy == null) {
                scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
            }
        },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Migrate",
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Pick this title on another source you already have on. " +
                        "The chapter you are on moves with it, and the shelf row follows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                Spacer(Modifier.height(10.dp))
                NuvioInputField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search another source",
                )
                Spacer(Modifier.height(6.dp))
            }

            if (busy != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NuvioLoadingIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Moving the title and your place…",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }

            failure?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            val groups = remember(cards) { mangaSearchGroups(cards) }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (searching && cards.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            NuvioLoadingIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                }
                groups.forEach { group ->
                    item(key = "head:${group.key}") {
                        Text(
                            text = group.name +
                                if (group.lang.isBlank()) "" else " · ${mangaSourceLanguageName(group.lang)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textMuted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    items(group.cards, key = { it.id }) { card ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = busy == null) {
                                    scope.launch { move(card) }
                                }
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = card.poster,
                                contentDescription = null,
                                modifier = Modifier
                                    .width(38.dp)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = card.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = tokens.colors.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = card.sourceName.ifBlank { card.source },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.colors.textMuted,
                                )
                            }
                        }
                    }
                }
                if (!searching && cards.isEmpty() && failure == null && query.isNotBlank()) {
                    item {
                        Text(
                            text = "Nothing on your other sources matches that.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                        )
                    }
                }
                item(key = "pad") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

/**
 * The picker the tracking tile opens: search MyAnimeList, then say which entry
 * this title is.
 *
 * It is the old client's tracking slot search, in a sheet. The field opens
 * filled with the name this app uses, because a search starting from nothing
 * would ask the reader to type a title the app already knows; it searches as the
 * typing pauses, because a request per letter is a request per letter; and every
 * hit says what separates two same-named entries — the kind, the year, the
 * length — because that is the part only the reader can decide.
 *
 * A pick is written down where it outranks every automatic match, so the
 * catalogue's guess and a search's best hit can never move it afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaTrackSheet(
    title: String,
    lookup: MangaMalLookup?,
    checking: Boolean,
    onPicked: (MangaMalHit) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val auth = MangaMal.authState

    var query by remember(title) { mutableStateOf(title) }
    var hits by remember(title) { mutableStateOf<List<MangaMalHit>>(emptyList()) }
    var searching by remember(title) { mutableStateOf(false) }
    var failure by remember(title) { mutableStateOf<String?>(null) }

    // Typing is a new search, and cancelling the previous one is the debounce:
    // the answer for "Bew" can never land after the answer for "Beware the
    // Villainess!" and paint a list that does not match the field.
    LaunchedEffect(query, auth.connected) {
        if (!auth.connected) return@LaunchedEffect
        val text = query.trim()
        failure = null
        if (text.length < 2) {
            hits = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(320)
        searching = true
        hits = runCatching { MangaMal.searchHits(text) }
            .getOrElse {
                failure = it.message ?: "MyAnimeList did not answer."
                emptyList()
            }
        searching = false
    }

    LaunchedEffect(auth.pendingState) {
        if (auth.pendingState != null) MangaMal.awaitSignIn()
    }

    NuvioModalBottomSheet(
        onDismissRequest = {
            scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
        },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Tracking",
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "This app calls it \"$title\". MyAnimeList may call it " +
                        "something else — search, then pick the entry you mean. " +
                        "A pick outranks every automatic match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            Spacer(Modifier.height(12.dp))

            when {
                auth.pendingState != null -> {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            text = if (auth.busy) "Finishing the sign-in…"
                            else "Finish signing in in your browser, then come back.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textSecondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { MangaMal.cancelSignIn() }) { Text("Cancel") }
                    }
                }

                !auth.connected -> {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            text = "Connect MyAnimeList and chapters you read are written " +
                                "to your list automatically — and the entry you pick here " +
                                "is the one they are written to.",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textSecondary,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                val url = MangaMal.beginSignIn()
                                runCatching { uriHandler.openUri(url) }.onFailure {
                                    MangaMal.failSignIn("Could not open a browser for the sign-in.")
                                }
                            },
                        ) {
                            Text("Connect MyAnimeList")
                        }
                    }
                }

                else -> {
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (checking) "Checking your list…" else malStatusLine(lookup),
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.colors.textSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            if (lookup is MangaMalLookup.Failed) {
                                TextButton(onClick = onRetry) { Text("Retry") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        NuvioInputField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = "Search MyAnimeList",
                            trailingContent = if (query.isNotBlank()) {
                                {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Clear the search",
                                            tint = tokens.colors.textMuted,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    if (searching) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            NuvioLoadingIndicator()
                        }
                    }

                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(hits, key = { hit -> hit.id }) { hit ->
                            val bound = (lookup as? MangaMalLookup.Found)?.entry?.malId == hit.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPicked(hit) }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // The cover is the fastest way to tell two entries
                                // with the same name apart — the row already says
                                // the year, but a reader knows the art.
                                MangaHitCover(url = hit.picture)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = hit.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (bound) tokens.colors.accent
                                        else tokens.colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (hit.meta.isNotEmpty()) {
                                        Text(
                                            text = hit.meta,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = tokens.colors.textMuted,
                                        )
                                    }
                                }
                                if (bound) {
                                    Text(
                                        text = "Tracking",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tokens.colors.accent,
                                    )
                                }
                            }
                        }
                        item(key = "search-note") {
                            val note = when {
                                query.trim().length < 2 && !searching ->
                                    "Keep typing — MyAnimeList is searched from two letters."
                                failure != null -> failure.orEmpty()
                                hits.isEmpty() && !searching ->
                                    "Nothing on MyAnimeList for that search. Try the name " +
                                        "you know it by there, or a word from it."
                                else -> ""
                            }
                            if (note.isNotEmpty()) {
                                Text(
                                    text = note,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.colors.textMuted,
                                )
                            }
                        }
                        item(key = "sheet-tail") { Spacer(Modifier.height(12.dp)) }
                    }

                    if (auth.error != null) {
                        Text(
                            text = auth.error.orEmpty(),
                            modifier = Modifier.padding(horizontal = 20.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { scope.launch { MangaMal.disconnect() } }) {
                            Text("Disconnect", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
            }
        }
    }
}

/**
 * The cover beside a MyAnimeList search hit.
 *
 * A thumbnail rather than a poster: the row is one line of text tall and the
 * picture is there to be recognised, not read. An entry without art keeps the
 * space and a book mark, so a list of hits does not go ragged.
 */
@Composable
private fun MangaHitCover(url: String) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = Modifier
            .height(56.dp)
            .aspectRatio(0.7f)
            .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
            .background(tokens.colors.surfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MenuBook,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun malStatusLine(result: MangaMalLookup?): String = when (result) {
    is MangaMalLookup.Failed -> "Couldn't reach MyAnimeList — ${result.message} A retry asks again."
    null, MangaMalLookup.Missing ->
        "Not matched to a MyAnimeList entry yet — tap Tracking to pick it."
    is MangaMalLookup.Found -> malEntryLine(result.entry)
}

private fun malEntryLine(entry: MangaMalEntry): String = when {
    entry.status == null -> buildString {
        append("Not on your list")
        if (entry.total > 0) append(" · ${entry.total} chapters")
        if (entry.title.isNotBlank()) append(" · ${entry.title}")
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
        if (entry.title.isNotBlank()) append(" · ${entry.title}")
    }
}

/**
 * The title, on the site it came from.
 *
 * The reader is the app's own WebView, not a browser handoff: the old client's
 * tile loaded mangafire in the app's window, and a reader checking an extra
 * chapter or a scanlation note should not be leaving the app to do it.
 */
@Composable
private fun MangaSourceScreen(url: String, title: String, onBack: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = Modifier.fillMaxSize().background(tokens.colors.background)) {
        NuvioScreenHeader(
            title = title,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        MangaWebView(
            url = url,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** Where a title lives on the site it came from: their pages and API share an id. */
private fun mangafireUrl(id: String): String = "https://mangafire.to/title/$id"

/**
 * The app's own window onto the web.
 *
 * An `expect` because a WebView is the one thing a multiplatform screen cannot
 * draw for itself: the Android half owns the instance, its settings and its
 * lifetime, and this side only says where to go.
 */
@Composable
internal expect fun MangaWebView(url: String, modifier: Modifier = Modifier)

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

/** Which reader sheet is open; the chrome opens one at a time. */
private enum class ReaderSheet { CHAPTERS, DISPLAY, HELP }

/** How the reader draws a chapter: one page a screen, two, or a strip. */
internal enum class ReaderLayout { SINGLE, DOUBLE, STRIP }

/** Where the reading position is shown, along the bottom of the page. */
internal enum class ReaderProgress { LINE, SEGMENTS, SHORT, BAR, NONE }

/** Which gesture brings the chrome back. */
internal enum class ReaderTap { SINGLE, DOUBLE }

/**
 * The reader's own panel, kept for the app run rather than for one chapter.
 *
 * The values live in one object rather than in `rememberSaveable` locals for a
 * reason a reader would notice: leaving a chapter for the title page and coming
 * back must not put the margin, the layout or the dim back where they were.
 * They are also kept on the phone - one loopback object, read once per run and
 * written when the panel closes - so a restart does not reset them either. They
 * are not carried to another device: this is a reading aid, not a preference
 * the shelf has to sync.
 */
internal object MangaReaderPrefs {
    var layout by mutableStateOf(ReaderLayout.STRIP)
    var margin by mutableIntStateOf(0)
    var progress by mutableStateOf(ReaderProgress.LINE)
    var tap by mutableStateOf(ReaderTap.SINGLE)
    var fitWidth by mutableStateOf(true)
    var fitHeight by mutableStateOf(false)
    var stretch by mutableStateOf(false)
    var maxWidth by mutableStateOf(false)
    var maxHeight by mutableStateOf(false)
    var grayscale by mutableStateOf(false)
    var dim by mutableStateOf(0f)
    var continuous by mutableStateOf(true)

    /** Whether this run has already asked the phone what it stored. */
    private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var saveJob: Job? = null

    /**
     * What the phone stored, read once per run.
     *
     * Best effort: a panel that cannot be read leaves the defaults, because a
     * chapter that will not open over a preference is worse than a margin the
     * reader has to set again.
     */
    suspend fun load() {
        if (loaded) return
        loaded = true
        val raw = runCatching { MangaApi.readerPrefs() }.getOrNull() ?: return
        val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        layout = json.enumValue("layout", ReaderLayout.STRIP)
        margin = (json["margin"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 48) ?: 0
        progress = json.enumValue("progress", ReaderProgress.LINE)
        tap = json.enumValue("tap", ReaderTap.SINGLE)
        fitWidth = json["fitWidth"].asBoolean() ?: true
        fitHeight = json["fitHeight"].asBoolean() ?: false
        stretch = json["stretch"].asBoolean() ?: false
        maxWidth = json["maxWidth"].asBoolean() ?: false
        maxHeight = json["maxHeight"].asBoolean() ?: false
        grayscale = json["grayscale"].asBoolean() ?: false
        dim = ((json["dim"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 70) / 100f
        continuous = json["continuous"].asBoolean() ?: true
    }

    /**
     * Write the panel back.
     *
     * Debounced and off the main thread: the dim slider is dragged, not set, and
     * one write per frame would be a preference file answered sixty times a
     * second. The sheet saves on its way out, which is the moment the answer is
     * settled.
     */
    fun save() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            val panel = buildJsonObject {
                put("layout", layout.name)
                put("margin", margin)
                put("progress", progress.name)
                put("tap", tap.name)
                put("fitWidth", fitWidth)
                put("fitHeight", fitHeight)
                put("stretch", stretch)
                put("maxWidth", maxWidth)
                put("maxHeight", maxHeight)
                put("grayscale", grayscale)
                put("dim", (dim * 100).toInt())
                put("continuous", continuous)
            }
            runCatching { MangaApi.saveReaderPrefs(panel.toString()) }
        }
    }
}

private inline fun <reified T : Enum<T>> JsonObject.enumValue(key: String, fallback: T): T {
    val name = (this[key] as? JsonPrimitive)?.contentOrNull ?: return fallback
    return runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}

private fun JsonElement?.asBoolean(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull

/**
 * Whether the reader is showing its own chrome, for the rest of the app.
 *
 * The tab bar lives above this tab, in `MainTabsDestination`, so a reader that
 * hid only its own pills would still be sitting on a bar. This is the one fact
 * that screen needs: the chrome is away, so the bar goes with it. Reset when
 * the reader leaves the composition, which is why a backgrounded reader never
 * leaves the tab bar hidden.
 */
internal object MangaReaderUi {
    var immersive by mutableStateOf(false)
        internal set
}

@Composable
private fun MangaReaderScreen(
    route: MangaRoute.Reader,
    inLibrary: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onToggleLibrary: () -> Unit,
    onChapter: (index: Int, startPage: Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    // The panel's answers are on the phone, not in this composition: the first
    // chapter of the run asks what was stored, once.
    LaunchedEffect(Unit) { MangaReaderPrefs.load() }
    // The chrome's own state: one sheet at a time.
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    // The panel's answers, read here so a page redraws when one of them changes.
    val layout = MangaReaderPrefs.layout
    val margin = MangaReaderPrefs.margin
    val progressStyle = MangaReaderPrefs.progress
    val tapMode = MangaReaderPrefs.tap
    val grayscale = MangaReaderPrefs.grayscale
    val dim = MangaReaderPrefs.dim
    val paged = layout != ReaderLayout.STRIP
    val spread = layout == ReaderLayout.DOUBLE
    // Pages per list item, which is what the position and the chapter label
    // have to divide by once the reader is not reading a strip.
    val pagesPerItem = if (spread) 2 else 1
    val pageFilter = remember(grayscale) {
        if (grayscale) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
        } else {
            null
        }
    }

    // The chrome starts hidden: pages are the point, and a chapter number is not
    // worth a permanent band across a comic. A tap in the middle of the page
    // brings it back, and another — or four idle seconds — puts it away again.
    var chromeVisible by rememberSaveable { mutableStateOf(false) }
    val showChrome = chromeVisible || sheet != null
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
    // Read on the strip either way; the display list only groups its pages for
    // the paged layouts. The seam, the saved position and the chapter label
    // still speak pages, so the grouping is a view of the strip rather than a
    // second model that would have to be kept in step with it.
    val displayPages = remember(strip.size, spread) {
        if (spread) strip.chunked(2) else strip.map { listOf(it) }
    }
    // Changing the layout re-groups the same pages, and an index means a
    // different page afterwards: re-aim the list at the page that was under the
    // reader instead of leaving them twenty pages further on.
    var pagesPerItemBefore by remember { mutableIntStateOf(pagesPerItem) }
    LaunchedEffect(pagesPerItem) {
        if (pagesPerItemBefore != pagesPerItem) {
            val page = listState.firstVisibleItemIndex * pagesPerItemBefore
            pagesPerItemBefore = pagesPerItem
            listState.scrollToItem(page / pagesPerItem)
        }
    }
    // Reading runs *up* the chapter numbers, and the list is newest first, so
    // the chapter after this one is the row above it.
    val previousIndex = route.index + 1

    // The chapter under the reader's eye: the one the top image belongs to. It
    // is read from the list rather than remembered, because a chapter that was
    // scrolled into must not be a different chapter from the one on screen.
    val visible = displayPages.getOrNull(listState.firstVisibleItemIndex)?.firstOrNull()
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
        listState.scrollToItem(
            (route.startPage / pagesPerItem)
                .coerceIn(0, maxOf(0, strip.lastIndex / pagesPerItem)),
        )

        // The next chapter's pages are fetched now, so the seam is a scroll
        // rather than a wait when it comes.
        route.chapters.getOrNull(route.index - 1)?.let { next -> MangaApi.prefetch(next.id) }
    }

    // The seam: within a few pages of the end of the strip, the chapter that
    // continues it is fetched and appended to the same list. Appended, not
    // pushed — the reader never sees a boundary, because there is not one: as
    // far as this list is concerned the next chapter is more of the same strip.
    LaunchedEffect(route.chapters, spread) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (!MangaReaderPrefs.continuous) return@collect
                if (appending || strip.isEmpty()) return@collect
                val seam = loaded.lastOrNull() ?: return@collect
                val next = seam - 1
                if (next < 0) return@collect
                // Measured in items drawn, which in Double is two pages each.
                val lastItem = (strip.size + pagesPerItem - 1) / pagesPerItem - 1
                if (lastVisible < lastItem - SEAM_LEAD_PAGES) return@collect
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
    LaunchedEffect(route.title, route.chapters, spread) {
        snapshotFlow { strip.getOrNull(listState.firstVisibleItemIndex * pagesPerItem)?.chapterIndex }
            .distinctUntilChanged()
            .collect { index ->
                val chapter = index?.let(route.chapters::getOrNull) ?: return@collect
                // The device's own record: a chapter under the reader is a
                // chapter read, and this is the write that makes the count
                // exact rather than a boundary guessed from the last position.
                // Best effort — the next chapter marks itself anyway.
                runCatching { MangaApi.markRead(route.id, chapter.number) }
                // The trackers keep their own meaning, which is a *position*:
                // "I am at chapter N", not "N distinct chapters read". Writing
                // the record's count here would tell an account the reader sat
                // on chapter 40 that they have read one chapter.
                MangaMal.chapterRead(route.title, chapter.number)
                // The same chapter, to the other tracker when one is linked.
                // A read is a read: whichever account is connected should hear
                // about it, and neither write may delay the other or a page.
                com.nuvio.app.features.anilist.AniList.chapterRead(route.title, chapter.number)
            }
    }

    // Every place the reader stops is a position written down — against the
    // chapter that page is in, which at the seam is not the chapter the strip
    // was anchored on. The old shape delayed *inside* the collector, so a fling
    // through forty pages queued forty writes half a second apart and the last
    // one to land was whatever the queue reached — not where the reader
    // actually stopped. Debouncing upstream collapses the fling into one write,
    // at the place it ended.
    LaunchedEffect(route.id, route.chapters, spread) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(600)
            .distinctUntilChanged()
            .collect { index ->
                val at = strip.getOrNull(index * pagesPerItem) ?: return@collect
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

    val maxWidthCap = if (MangaReaderPrefs.maxWidth) Modifier.widthIn(max = 760.dp) else Modifier
    val maxHeightCap = if (MangaReaderPrefs.maxHeight) Modifier.heightIn(max = 1100.dp) else Modifier
    // A page that is a screen should stay a screen: without the snap, Single
    // and Double are a strip with taller images, which is not what the panel
    // says they are.
    val snapBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val stripBehavior = ScrollableDefaults.flingBehavior()

    Box(modifier = Modifier.fillMaxSize().background(tokens.colors.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // A tap is the only gesture the pages have to share: scrolling
                // is the reader's, so this listens for taps alone and leaves
                // drags to the list. The panel chooses which tap counts.
                .pointerInput(tapMode) {
                    detectTapGestures(
                        onTap = {
                            if (MangaReaderPrefs.tap == ReaderTap.SINGLE) {
                                chromeVisible = !chromeVisible
                            }
                        },
                        onDoubleTap = if (MangaReaderPrefs.tap == ReaderTap.DOUBLE) {
                            { _ -> chromeVisible = !chromeVisible }
                        } else {
                            null
                        },
                    )
                },
            contentPadding = PaddingValues(bottom = 24.dp),
            flingBehavior = if (paged) snapBehavior else stripBehavior,
        ) {
            itemsIndexed(
                items = displayPages,
                // A page's URL alone is not a key: two chapters can name the
                // same file, and a duplicate key is a crash rather than a
                // redraw. The chapter and the page inside it are unique.
                key = { _, pages -> "${pages.first().chapterId}:${pages.first().pageIndex}" },
                contentType = { _, _ -> "page" },
            ) { _, pages ->
                if (paged) {
                    Row(
                        modifier = Modifier
                            .then(maxWidthCap)
                            .fillMaxWidth()
                            .fillParentMaxHeight()
                            .padding(horizontal = margin.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        pages.forEach { page ->
                            AsyncImage(
                                model = page.page.u,
                                contentDescription = null,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(horizontal = 2.dp),
                                contentScale = if (MangaReaderPrefs.stretch) {
                                    ContentScale.FillWidth
                                } else {
                                    ContentScale.Fit
                                },
                                colorFilter = pageFilter,
                            )
                        }
                        // A last, odd page in Double sits where a left-hand
                        // page would, not stretched across both halves.
                        if (pages.size == 1 && spread) Spacer(Modifier.weight(1f))
                    }
                } else {
                    val page = pages.first()
                    Box(
                        modifier = Modifier
                            .then(maxWidthCap)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = page.page.u,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = margin.dp)
                                .then(
                                    if (MangaReaderPrefs.fitHeight) {
                                        Modifier.fillParentMaxHeight()
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(maxHeightCap),
                            contentScale = if (MangaReaderPrefs.fitHeight) {
                                ContentScale.Fit
                            } else {
                                ContentScale.FillWidth
                            },
                            colorFilter = pageFilter,
                        )
                    }
                }
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

        // Night reading: the pages are turned down, the chrome is not. Drawn
        // between the strip and the controls, and it takes no touches.
        if (dim > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dim)),
            )
        }

        // How far into what is loaded the reader is. Drawn over the dimmed
        // pages so it stays readable at night, and outside the chrome so it is
        // there whether the pills are or not - which is the point of choosing
        // between five ways of saying it.
        if (progressStyle != ReaderProgress.NONE && displayPages.isNotEmpty()) {
            val fraction by remember(displayPages.size) {
                derivedStateOf {
                    ((listState.firstVisibleItemIndex + 1).toFloat() / displayPages.size)
                        .coerceIn(0f, 1f)
                }
            }
            ReaderProgressBar(
                style = progressStyle,
                fraction = fraction,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp),
            )
        }

        // The chrome floats over the pages in the app's own floating-navbar
        // shape — one dark glass pill per group of controls, with the
        // hairline edge the tab bar has — rather than in a bar that would push
        // the strip down. The chapter pill says where the reader is and steps
        // between chapters; the rail at the right is everything else, one tap
        // away and out of the page's way.
        AnimatedVisibility(
            visible = showChrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopChrome(
                chapter = route.chapters.getOrNull(readingIndex)?.number ?: "—",
                total = total,
                canGoOlder = readingIndex + 1 < total,
                canGoNewer = readingIndex - 1 >= 0,
                onBack = onBack,
                onOlder = { onChapter(readingIndex + 1, 0) },
                onNewer = { onChapter(readingIndex - 1, 0) },
            )
        }

        AnimatedVisibility(
            visible = showChrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            ReaderRail(
                inLibrary = inLibrary,
                modifier = Modifier.padding(end = 10.dp),
                onHome = onHome,
                onTitle = onBack,
                onChapters = { sheet = ReaderSheet.CHAPTERS },
                onDisplay = { sheet = ReaderSheet.DISPLAY },
                onHelp = { sheet = ReaderSheet.HELP },
                onLibrary = onToggleLibrary,
            )
        }

        // Shown chrome puts itself away once the reader has stopped touching
        // it; a sheet is chrome the reader is using, so it holds.
        LaunchedEffect(showChrome, sheet) {
            if (!showChrome || sheet != null) return@LaunchedEffect
            delay(4_000)
            chromeVisible = false
        }

        // While the chrome is hidden the app's tab bar hides with it — the same
        // request the app's own full-screen routes make — and it comes back
        // with the chrome.
        DisposableEffect(showChrome) {
            MangaReaderUi.immersive = !showChrome
            onDispose { MangaReaderUi.immersive = false }
        }

        when (sheet) {
            ReaderSheet.CHAPTERS -> ReaderChapterSheet(
                chapters = route.chapters,
                current = readingIndex,
                onPick = { index ->
                    sheet = null
                    onChapter(index, 0)
                },
                onDismiss = { sheet = null },
            )

            ReaderSheet.DISPLAY -> ReaderDisplaySheet(onDismiss = { sheet = null })

            ReaderSheet.HELP -> ReaderHelpSheet(onDismiss = { sheet = null })

            null -> Unit
        }
    }
}

// ------------------------------------------------------------------- chrome

/**
 * The reader's top row: a back pill, and the chapter pill the app's navbar
 * would have drawn — the same glass, the same pill shape, the same hairline.
 */
@Composable
private fun ReaderTopChrome(
    chapter: String,
    total: Int,
    canGoOlder: Boolean,
    canGoNewer: Boolean,
    onBack: () -> Unit,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The tab is drawn edge to edge, so the chrome has to keep itself
            // out of the status bar rather than being placed below it.
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderPill {
            Row(
                modifier = Modifier.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderPillButton(
                    icon = Icons.Rounded.ArrowBack,
                    description = "Back to the title",
                    onClick = onBack,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        ReaderPill {
            Row(
                modifier = Modifier.padding(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ReaderPillButton(
                    icon = Icons.Rounded.ChevronLeft,
                    description = "Previous chapter",
                    onClick = onOlder,
                    enabled = canGoOlder,
                    circled = true,
                )
                Text(
                    text = "Ch. $chapter / $total",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
                ReaderPillButton(
                    icon = Icons.Rounded.ChevronRight,
                    description = "Next chapter",
                    onClick = onNewer,
                    enabled = canGoNewer,
                    circled = true,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // Keeps the chapter pill centred on the page, with the back pill's
        // width on the other side.
        Spacer(Modifier.size(46.dp))
    }
}

/**
 * The reader's actions, stacked in one pill down the right edge.
 *
 * Six icons, each a real destination rather than a placeholder: leave the
 * reader, the title's page, its chapters, how the pages are drawn, what the
 * reader does, and whether this title is kept.
 */
@Composable
private fun ReaderRail(
    inLibrary: Boolean,
    onHome: () -> Unit,
    onTitle: () -> Unit,
    onChapters: () -> Unit,
    onDisplay: () -> Unit,
    onHelp: () -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.nuvio.colors.accent
    ReaderPill(modifier) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ReaderPillButton(
                icon = Icons.Rounded.Home,
                description = "Back to the shelf",
                onClick = onHome,
            )
            ReaderPillButton(
                icon = Icons.Rounded.MenuBook,
                description = "This title's page",
                onClick = onTitle,
            )
            ReaderPillButton(
                icon = Icons.Rounded.Subject,
                description = "Chapters",
                onClick = onChapters,
            )
            ReaderPillButton(
                icon = Icons.Rounded.Settings,
                description = "How the pages are drawn",
                onClick = onDisplay,
            )
            ReaderPillButton(
                icon = Icons.Rounded.HelpOutline,
                description = "How the reader works",
                onClick = onHelp,
            )
            ReaderPillButton(
                icon = Icons.Rounded.Flag,
                description = if (inLibrary) {
                    "Remove from the manga shelf"
                } else {
                    "Add to the manga shelf"
                },
                onClick = onLibrary,
                tint = if (inLibrary) accent else Color.White.copy(alpha = 0.82f),
            )
        }
    }
}

/**
 * The floating chrome's surface: the tab bar's own make, clipped to a pill.
 *
 * The same dark fill the navbar falls back to, and the same hairline edge —
 * drawn here rather than through the bar's own surface because that one lives
 * in Android's source set with the blur it needs, and this is a common screen.
 * The strip underneath is a column of full-bleed images, so a blur pass over it
 * would buy nothing the fill does not.
 */
@Composable
private fun ReaderPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(ReaderChromeFill)
            .border(
                width = 0.75.dp,
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.27f), Color.White.copy(alpha = 0.02f)),
                ),
                shape = shape,
            ),
    ) {
        content()
    }
}

/** The tab bar's own fill: the glass surface's colour, at its solid alpha. */
private val ReaderChromeFill = Color(0xFF1C1C1E).copy(alpha = 0.82f)

@Composable
private fun ReaderPillButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.White.copy(alpha = 0.82f),
    circled: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .then(
                if (circled) {
                    Modifier.background(Color.White.copy(alpha = 0.07f))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint.copy(alpha = if (enabled) tint.alpha else 0.28f),
            modifier = Modifier.size(20.dp),
        )
    }
}

// ------------------------------------------------------------------- sheets

/** Every chapter, in the order the title lists them, with the current one lit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderChapterSheet(
    chapters: List<MangaChapter>,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = current.coerceIn(0, maxOf(0, chapters.lastIndex)),
    )

    NuvioModalBottomSheet(
        onDismissRequest = {
            scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
        },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
            Text(
                text = "Chapters",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
            NuvioBottomSheetDivider()
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                itemsIndexed(
                    items = chapters,
                    key = { _, chapter -> chapter.id },
                ) { index, chapter ->
                    NuvioBottomSheetActionRow(
                        title = buildString {
                            append("Ch. ")
                            append(chapter.number)
                            if (chapter.name.isNotBlank()) {
                                append("  ")
                                append(chapter.name)
                            }
                        },
                        selected = index == current,
                        onClick = {
                            scope.launch {
                                dismissNuvioBottomSheet(sheetState = sheetState) { onPick(index) }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * The reader's settings, in the two tabs the site's own reader uses.
 *
 * Layout is how a page is drawn and how the reader moves through it; Image is
 * how much of the page is shown and how it is coloured. Everything writes to
 * [MangaReaderPrefs], which is state the pages already watch - so a change is
 * a redraw, not a callback this sheet has to thread back to the reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderDisplaySheet(onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    val dismiss: () -> Unit = {
        // Closing the panel is when its answers are settled; the reader writes
        // them down here rather than on every drag of the dim slider.
        MangaReaderPrefs.save()
        scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
    }

    NuvioModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(label = "Layout", selected = tab == 0, onClick = { tab = 0 })
                Spacer(Modifier.width(8.dp))
                Chip(label = "Image", selected = tab == 1, onClick = { tab = 1 })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = dismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close reader settings",
                        tint = tokens.colors.textMuted,
                    )
                }
            }
            HorizontalDivider(color = tokens.colors.borderDefault)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (tab == 0) ReaderLayoutSettings() else ReaderImageSettings()
            }
        }
    }
}

/** The left-hand tab: how a page is laid out, and how it is turned. */
@Composable
private fun ReaderLayoutSettings() {
    val tokens = MaterialTheme.nuvio

    ReaderSectionLabel("LAYOUT")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderLayout.entries.forEach { option ->
            ReaderLayoutTile(
                option = option,
                selected = MangaReaderPrefs.layout == option,
                modifier = Modifier.weight(1f),
                onClick = { MangaReaderPrefs.layout = option },
            )
        }
    }

    ReaderSectionLabel("MARGIN")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            color = tokens.colors.surfaceCard,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        MangaReaderPrefs.margin = (MangaReaderPrefs.margin - 4).coerceAtLeast(0)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Remove,
                        contentDescription = "Less margin",
                        tint = tokens.colors.textPrimary,
                    )
                }
                Text(
                    text = "${MangaReaderPrefs.margin}",
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        MangaReaderPrefs.margin = (MangaReaderPrefs.margin + 4).coerceAtMost(48)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "More margin",
                        tint = tokens.colors.textPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = { MangaReaderPrefs.margin = 0 }) {
            Text("Reset", style = MaterialTheme.typography.labelLarge)
        }
    }

    ReaderSectionLabel("PROGRESS BAR")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderProgress.entries.forEach { style ->
            ReaderProgressChoice(
                style = style,
                selected = MangaReaderPrefs.progress == style,
                modifier = Modifier.weight(1f),
                onClick = { MangaReaderPrefs.progress = style },
            )
        }
    }

    ReaderSectionLabel("SHOW CONTROLS")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderTap.entries.forEach { gesture ->
            ReaderChoiceTile(
                label = if (gesture == ReaderTap.SINGLE) "Single tap" else "Double tap",
                selected = MangaReaderPrefs.tap == gesture,
                modifier = Modifier.weight(1f),
                onClick = { MangaReaderPrefs.tap = gesture },
            )
        }
    }
}

/** The right-hand tab: how much of the page is drawn, and how it looks. */
@Composable
private fun ReaderImageSettings() {
    val tokens = MaterialTheme.nuvio

    ReaderSectionLabel("IMAGE SIZING")
    ReaderCheckRow(
        label = "Fit width",
        checked = MangaReaderPrefs.fitWidth,
        onChange = { on ->
            MangaReaderPrefs.fitWidth = on
            if (on) MangaReaderPrefs.fitHeight = false
        },
    )
    ReaderCheckRow(
        label = "Fit height",
        checked = MangaReaderPrefs.fitHeight,
        onChange = { on ->
            MangaReaderPrefs.fitHeight = on
            if (on) MangaReaderPrefs.fitWidth = false
        },
    )
    ReaderCheckRow(
        label = "Stretch small images",
        checked = MangaReaderPrefs.stretch,
        onChange = { MangaReaderPrefs.stretch = it },
    )
    ReaderCheckRow(
        label = "Limit max width",
        checked = MangaReaderPrefs.maxWidth,
        onChange = { MangaReaderPrefs.maxWidth = it },
    )
    ReaderCheckRow(
        label = "Limit max height",
        checked = MangaReaderPrefs.maxHeight,
        onChange = { MangaReaderPrefs.maxHeight = it },
    )

    ReaderSectionLabel("IMAGE COLORING")
    ReaderCheckRow(
        label = "Grayscale",
        checked = MangaReaderPrefs.grayscale,
        onChange = { MangaReaderPrefs.grayscale = it },
    )

    ReaderSectionLabel("DIM SCREEN")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Dim",
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${(MangaReaderPrefs.dim * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textMuted,
        )
    }
    Slider(
        value = MangaReaderPrefs.dim,
        onValueChange = { MangaReaderPrefs.dim = it },
        valueRange = 0f..0.7f,
    )

    ReaderSectionLabel("READING")
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Run on into the next chapter",
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = "Scrolling past the last page loads what comes after it.",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
        Switch(
            checked = MangaReaderPrefs.continuous,
            onCheckedChange = { MangaReaderPrefs.continuous = it },
        )
    }
}

/** A section heading in the panel: small, spaced, and not a button. */
@Composable
private fun ReaderSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.nuvio.colors.textMuted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** One of Single / Double / Long strip. */
@Composable
private fun ReaderLayoutTile(
    option: ReaderLayout,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val (icon, label) = when (option) {
        ReaderLayout.SINGLE -> Icons.Rounded.CropPortrait to "Single"
        ReaderLayout.DOUBLE -> Icons.Rounded.GridView to "Double"
        ReaderLayout.STRIP -> Icons.Rounded.ViewAgenda to "Long strip"
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) tokens.colors.accent.copy(alpha = 0.16f) else tokens.colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) tokens.colors.accent else tokens.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One of the five ways the position along the chapter can be drawn. */
@Composable
private fun ReaderProgressChoice(
    style: ReaderProgress,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val icon = when (style) {
        ReaderProgress.LINE -> Icons.Rounded.Remove
        ReaderProgress.SEGMENTS -> Icons.Rounded.MoreHoriz
        ReaderProgress.SHORT -> Icons.Rounded.LinearScale
        ReaderProgress.BAR -> Icons.Rounded.Crop169
        ReaderProgress.NONE -> Icons.Rounded.Close
    }
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) tokens.colors.accent.copy(alpha = 0.16f) else tokens.colors.surfaceCard)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = when (style) {
                ReaderProgress.LINE -> "A line along the bottom"
                ReaderProgress.SEGMENTS -> "Segmented line"
                ReaderProgress.SHORT -> "Short line"
                ReaderProgress.BAR -> "A bar"
                ReaderProgress.NONE -> "No progress bar"
            },
            tint = if (selected) tokens.colors.accent else tokens.colors.textMuted,
            modifier = Modifier.size(19.dp),
        )
    }
}

/** A word-shaped choice: Single tap / Double tap. */
@Composable
private fun ReaderChoiceTile(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) tokens.colors.accent.copy(alpha = 0.16f) else tokens.colors.surfaceCard)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) tokens.colors.accent else tokens.colors.textPrimary,
        )
    }
}

/** One checkbox row, on the panel's own card. */
@Composable
private fun ReaderCheckRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tokens.colors.surfaceCard)
            .clickable { onChange(!checked) }
            .padding(horizontal = 6.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tokens.colors.textPrimary,
        )
    }
}

/** The position along what is loaded, in the style the panel chose. */
@Composable
private fun ReaderProgressBar(
    style: ReaderProgress,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val accent = tokens.colors.accent
    val track = tokens.colors.surfaceCard
    when (style) {
        ReaderProgress.LINE -> Box(
            modifier.fillMaxWidth().height(2.dp).background(track),
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent))
        }

        ReaderProgress.SEGMENTS -> Row(
            modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val segments = 12
            repeat(segments) { index ->
                val filled = (index + 1).toFloat() / segments <= fraction
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(if (filled) accent else track),
                )
            }
        }

        ReaderProgress.SHORT -> Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(150.dp).height(3.dp).clip(CircleShape).background(track)) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }

        ReaderProgress.BAR -> Box(
            modifier.fillMaxWidth().height(6.dp).background(track),
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent))
        }

        ReaderProgress.NONE -> Unit
    }
}

/** What this reader does, said plainly — it is not the same as other readers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderHelpSheet(onDismiss: () -> Unit) {
    val tokens = MaterialTheme.nuvio
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    NuvioModalBottomSheet(
        onDismissRequest = {
            scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "How reading works",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                modifier = Modifier.padding(top = 10.dp),
            )
            listOf(
                "The chapters are one strip. Scrolling past the last page of one brings the " +
                    "next one in underneath it, and the chapter pill follows you across the seam.",
                "The display button opens the reading panel: a page a screen, a double-page " +
                    "spread, or the long strip; the margin, the progress bar and which tap " +
                    "brings the controls back; how the page is scaled and coloured.",
                "Where you stop is written down, so opening the title again lands in that " +
                    "chapter and on that page.",
                "On the pill, < is the chapter before this one and > is the one after it.",
                "The flag keeps this title on the manga shelf — and tells a linked " +
                    "MyAnimeList or AniList account that you mean to read it.",
            ).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ------------------------------------------------------------------ offline

/**
 * The download state of one chapter, as a button.
 *
 * Four answers to one question, each a tap away: a chapter nobody saved is a
 * download arrow, one on its way is the percentage, one saved is a tick that is
 * also how it is deleted, and one that failed is a retry.
 *
 * Removing is deliberately one tap and no dialog: what it deletes is a copy,
 * the chapter is still in the catalogue, and a confirmation over a list of
 * thirty rows is a dialog the reader has to dismiss thirty times.
 */
@Composable
private fun ChapterDownloadAction(
    state: String,
    progress: Float,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    when (state) {
        MangaSavedIndex.QUEUED, MangaSavedIndex.DOWNLOADING -> Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (state == MangaSavedIndex.QUEUED) "…"
                else "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.accent,
            )
        }

        MangaSavedIndex.FAILED -> IconButton(onClick = onSave, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "Try saving this chapter again",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }

        MangaSavedIndex.DOWNLOADED -> IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.DownloadDone,
                contentDescription = "Saved on this phone - tap to remove it",
                tint = tokens.colors.accent,
                modifier = Modifier.size(18.dp),
            )
        }

        else -> IconButton(onClick = onSave, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = "Save this chapter to read offline",
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Picking chapters to save.
 *
 * A list rather than a range picker: a chapter number is not a position you can
 * count on (extras, gaps, a second scanlation), so "the next ten" is not a thing
 * this screen can promise. What it can do is show every chapter the phone does
 * not have yet and let the reader tick the ones they want - with `All` for the
 * reader who wants the series, which is the same decision at a larger size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaDownloadSheet(
    title: String,
    chapters: List<MangaChapter>,
    saved: List<MangaSavedChapter>,
    onDownload: (List<MangaChapter>) -> Unit,
    onRemoveAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val savedIds = remember(saved) { saved.map { it.chapter }.toSet() }
    val available = remember(chapters, savedIds) {
        chapters.filter { it.id.isNotBlank() && it.id !in savedIds }
    }
    val picked = remember { mutableStateListOf<String>() }

    NuvioModalBottomSheet(
        onDismissRequest = {
            scope.launch { dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss) }
        },
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "Download",
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "The pages of the chapters you pick are kept on this phone, " +
                        "so they read with no connection. \"$title\" has " +
                        "${saved.size} saved already.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    picked.clear()
                    picked.addAll(available.map { it.id })
                }) {
                    Text("All", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { picked.clear() }) {
                    Text("None", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${picked.size} picked",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.colors.textMuted,
                )
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(available, key = { it.id }) { chapter ->
                    val on = chapter.id in picked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (on) picked.remove(chapter.id) else picked.add(chapter.id)
                            }
                            .padding(horizontal = 20.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Chapter ${chapter.number}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        if (chapter.name.isNotBlank()) {
                            Text(
                                text = chapter.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = tokens.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (available.isEmpty()) {
                    item(key = "all-saved") {
                        Text(
                            text = "Every chapter of this title is saved on this phone.",
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textMuted,
                        )
                    }
                }
                item(key = "sheet-tail") { Spacer(Modifier.height(12.dp)) }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        onDownload(available.filter { it.id in picked })
                        onDismiss()
                    },
                    enabled = picked.isNotEmpty(),
                ) {
                    Text(
                        if (picked.isEmpty()) {
                            "Pick chapters"
                        } else {
                            "Download ${picked.size}"
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (saved.isNotEmpty()) {
                    TextButton(onClick = {
                        onRemoveAll()
                        onDismiss()
                    }) {
                        Text("Remove saved", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * The chapters kept on this phone.
 *
 * This is the door that works with the radio off: the catalogue screen needs
 * mangafire, and this one needs only the app's own files. Each title is a
 * header (what it is, how much it costs in storage, how to remove it) over its
 * chapters, newest first, and a tap on one opens the reader at it with the rest
 * of the saved chapters behind it.
 */
@Composable
private fun MangaDownloadsScreen(
    onBack: () -> Unit,
    onRead: (
        hid: String,
        title: String,
        poster: String,
        chapters: List<MangaChapter>,
        index: Int,
    ) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val index = MangaDownloads.index
    val failure = MangaDownloads.error

    LaunchedEffect(Unit) {
        MangaDownloads.refresh()
        MangaDownloads.watch()
    }

    NuvioScreen(horizontalPadding = 0.dp, modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            NuvioScreenHeader(title = "Downloads", onBack = onBack)
        }

        item(key = "summary") {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = if (index.titles.isEmpty()) {
                        "Nothing saved yet"
                    } else {
                        savedCount(index.titles.size, "title", "titles") + " · " +
                            savedCount(
                                index.titles.sumOf { it.chapters },
                                "chapter",
                                "chapters",
                            ) + " · " + savedSize(index.bytes)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Saved pages live on this phone and read with no connection. " +
                        "Tap a chapter to open it; the bin removes its pages.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                if (index.active > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${index.active} chapters still downloading - they finish " +
                            "in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.accent,
                    )
                }
                if (failure != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = failure,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (index.titles.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Open a title and use Download chapters - or the arrow on " +
                            "any chapter row - to keep it here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textMuted,
                    )
                }
            }
        }

        items(index.titles, key = { it.id }) { title ->
            val saved = index.savedOf(title.id)
            val reader = savedAsChapters(saved)
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Poster(
                        url = title.poster,
                        modifier = Modifier.width(52.dp).aspectRatio(0.68f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title.title.ifBlank { "Untitled" },
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.colors.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = savedCount(title.chapters, "chapter", "chapters") +
                                " · " + savedSize(title.bytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textMuted,
                        )
                    }
                    IconButton(onClick = {
                        scope.launch { MangaDownloads.removeTitle(title.id) }
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Remove every saved chapter of this title",
                            tint = tokens.colors.textMuted,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                reader.forEachIndexed { at, chapter ->
                    val row = saved.firstOrNull { it.chapter == chapter.id }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                            .clickable {
                                onRead(title.id, title.title, title.poster, reader, at)
                            }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DownloadDone,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = tokens.colors.accent,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Chapter ${chapter.number}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = savedSize(row?.bytes ?: 0L),
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.colors.textMuted,
                        )
                        IconButton(
                            onClick = {
                                scope.launch { MangaDownloads.remove(title.id, chapter.id) }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Remove this chapter's pages",
                                tint = tokens.colors.textMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = tokens.colors.borderDefault)
            }
        }

        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}
