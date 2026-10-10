package app.hanime.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalMovies
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Explicit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ theme */

private val Ink = Color(0xFF0B0B0E)
private val Panel = Color(0xFF14141A)
private val Line = Color(0xFF23232C)
private val Amber = Color(0xFFF0B45C)
private val Dim = Color(0xFF9A9AA8)

@Composable
fun HanimeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Amber,
            onPrimary = Color(0xFF201400),
            background = Ink,
            onBackground = Color(0xFFEDEDF2),
            surface = Panel,
            onSurface = Color(0xFFEDEDF2),
            surfaceVariant = Panel,
            onSurfaceVariant = Dim,
            outline = Line,
        ),
        content = content,
    )
}

/* ------------------------------------------------------------------- model */

enum class Area(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    ANIME("Anime", Icons.Filled.SmartDisplay),
    FILMS("Films", Icons.Filled.LocalMovies),
    ADULT("18+", Icons.Outlined.Explicit),
    LIBRARY("Library", Icons.Outlined.VideoLibrary),
}

class AppState {
    var area by mutableStateOf(Area.HOME)
    var query by mutableStateOf("")
    var items by mutableStateOf<List<Title>>(emptyList())
    var loading by mutableStateOf(false)
    var note by mutableStateOf<String?>(null)
    var online by mutableStateOf(false)
    var detail by mutableStateOf<Detail?>(null)
    var episodes by mutableStateOf<List<Episode>>(emptyList())
    var season by mutableStateOf(1)
    var playing by mutableStateOf<Int?>(null)

    // The three rails the home page is made of, filled from three routes.
    var animeRail by mutableStateOf<List<Title>>(emptyList())
    var filmsRail by mutableStateOf<List<Title>>(emptyList())
    var adultRail by mutableStateOf<List<Title>>(emptyList())
}

/* ------------------------------------------------------------------ pieces */

@Composable
private fun PosterCard(title: Title, onClick: () -> Unit, width: Int = 168) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = Modifier
            .width(width.dp)
            .hoverable(interaction)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(Panel),
        ) {
            AsyncImage(
                model = title.poster,
                contentDescription = title.title,
                modifier = Modifier.fillMaxSize(),
            )
            if (hovered) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Amber, modifier = Modifier.size(40.dp))
                }
            }
            title.score?.let { score ->
                Surface(
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(6.dp).align(Alignment.TopEnd),
                ) {
                    Text(
                        "★ %.1f".format(score),
                        fontSize = 11.sp,
                        color = Amber,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            listOfNotNull(title.subtitle, title.year, title.kind.label()).joinToString(" · "),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.sp,
            color = Dim,
        )
    }
}

private fun Kind.label() = when (this) {
    Kind.ANIME -> "Anime"
    Kind.SERIES -> "Series"
    Kind.FILM -> "Film"
    Kind.VIDEO -> "Video"
}

@Composable
private fun Rail(heading: String, count: Int, items: List<Title>, onOpen: (Title) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(heading, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("$count", fontSize = 12.sp, color = Dim)
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items) { item -> PosterCard(item, onClick = { onOpen(item) }) }
        }
    }
}

@Composable
private fun PosterGrid(items: List<Title>, onOpen: (Title) -> Unit, footNote: String?) {
    val grid = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(168.dp),
            contentPadding = PaddingValues(start = 28.dp, end = 40.dp, top = 18.dp, bottom = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize().padding(end = 6.dp),
        ) {
            items(items) { item -> PosterCard(item, onClick = { onOpen(item) }, width = 168) }
            footNote?.let {
                item {
                    Text(it, color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(grid),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 6.dp),
        )
    }
}

/* ------------------------------------------------------------------- shell */

@Composable
fun AppShell(state: AppState) {
    val scope = rememberCoroutineScope()

    suspend fun open(ref: String) {
        state.loading = true
        state.note = null          // a browse note does not belong on a title's page
        state.detail = Api.detail(ref)
        state.season = state.detail?.seasons?.firstOrNull() ?: 1
        state.episodes = if (state.detail?.kind == Kind.SERIES) {
            Api.episodes(ref, state.season)
        } else {
            emptyList()
        }
        state.loading = false
    }

    // The home page's three rails, fetched once: an anime shelf, a films shelf
    // and the 18+ catalogue's own "newest".
    LaunchedEffect(Unit) {
        state.online = Api.alive()
        if (!state.online) return@LaunchedEffect
        val anime = Api.animeSearch(page = 1).first
        state.animeRail = anime
        state.filmsRail = Api.filmsSearch(page = 1).first
        state.adultRail = Api.videos(perPage = 18)
    }

    // One search, aimed at whichever area is on screen.
    LaunchedEffect(state.area, state.query) {
        val q = state.query.trim()
        if (!state.online) return@LaunchedEffect
        state.loading = true
        state.note = null
        when (state.area) {
            Area.HOME -> Unit
            // An empty query is not a dead end: it browses. The server answers
            // a query-less search with its own listing page — the season's top
            // for anime, the site's `/movies` shelf for films — so entering an
            // area shows something before a letter is typed. (Returning early
            // here used to leave the spinner turning over an empty page.)
            Area.ANIME -> {
                val (items, more) = Api.animeSearch(q = q.ifEmpty { null })
                state.items = items
                if (items.isEmpty() && q.isNotEmpty()) {
                    state.note = "Nothing matched \"$q\"."
                } else if (more) {
                    state.note = "showing the first ${items.size} of more"
                }
            }
            Area.FILMS -> {
                val (items, more) = Api.filmsSearch(q = q.ifEmpty { null })
                state.items = items
                if (items.isEmpty() && q.isNotEmpty()) {
                    state.note = "Nothing matched \"$q\"."
                } else if (more) {
                    state.note = "showing the first ${items.size} of more"
                }
            }
            Area.ADULT -> {
                val items = Api.videos(perPage = 30, orderBy = "views", q = q.ifEmpty { null })
                state.items = items
                if (items.isEmpty()) state.note = "Nothing matched \"$q\"."
            }
            Area.LIBRARY -> state.items = emptyList()
        }
        state.loading = false
    }

    Row(Modifier.fillMaxSize().background(Ink)) {
        Sidebar(state)
        Column(Modifier.fillMaxSize()) {
            TopBar(state)
            Box(Modifier.fillMaxSize()) {
                val detail = state.detail
                when {
                    detail != null -> DetailPage(state, detail, scope, onBack = { state.detail = null })
                    state.area == Area.HOME -> HomePage(state) { title ->
                        scope.launch { open(title.ref) }
                    }
                    state.area == Area.LIBRARY -> EmptyLibrary()
                    else -> PosterGrid(state.items, onOpen = { title ->
                        scope.launch { open(title.ref) }
                    }, footNote = state.note)
                }
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(22.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Sidebar(state: AppState) {
    Column(
        Modifier
            .width(212.dp)
            .fillMaxHeight()
            .background(Panel)
            .padding(vertical = 18.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(4.dp)).background(Amber))
            Spacer(Modifier.width(9.dp))
            Text("HANIME", fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
        }
        Spacer(Modifier.height(26.dp))
        Area.entries.forEach { area ->
            val active = state.area == area
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) Color(0x1FF0B45C) else Color.Transparent)
                    .clickable {
                        state.area = area
                        state.detail = null
                        state.query = ""
                        state.items = emptyList()
                        state.note = null
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    area.icon,
                    contentDescription = area.label,
                    modifier = Modifier.size(18.dp),
                    tint = if (active) Amber else Dim,
                )
                Spacer(Modifier.width(11.dp))
                Text(
                    area.label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) Color(0xFFEDEDF2) else Dim,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(7.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (state.online) Color(0xFF5CD08A) else Color(0xFFD06A5C)),
            )
            Spacer(Modifier.width(9.dp))
            Column {
                Text(if (state.online) "server up" else "server down", fontSize = 11.sp, color = Dim)
                Text(Api.base.removePrefix("http://"), fontSize = 10.sp, color = Color(0xFF5A5A66))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Settings, null, modifier = Modifier.size(15.dp), tint = Color(0xFF5A5A66))
            Spacer(Modifier.width(8.dp))
            Text("Settings", fontSize = 11.sp, color = Color(0xFF5A5A66))
        }
    }
}

@Composable
private fun TopBar(state: AppState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (state.detail != null) "" else state.area.label,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        TextField(
            value = state.query,
            onValueChange = { state.query = it },
            singleLine = true,
            placeholder = {
                Text(
                    when (state.area) {
                        Area.FILMS -> "Search films and series…"
                        Area.ANIME -> "Search anime…"
                        Area.ADULT -> "Search the catalogue…"
                        else -> "Search an area…"
                    },
                    fontSize = 13.sp,
                )
            },
            leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(17.dp), tint = Dim) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Panel,
                unfocusedContainerColor = Panel,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.widthIn(min = 300.dp, max = 420.dp),
        )
    }
}

/* -------------------------------------------------------------------- pages */

@Composable
private fun HomePage(state: AppState, onOpen: (Title) -> Unit) {
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
            item {
                val hero = state.filmsRail.firstOrNull() ?: state.animeRail.firstOrNull()
                Hero(hero, onOpen)
            }
            item { Rail("Anime, right now", state.animeRail.size, state.animeRail, onOpen) }
            item { Rail("Films and series", state.filmsRail.size, state.filmsRail, onOpen) }
            item { Rail("Newest in 18+", state.adultRail.size, state.adultRail, onOpen) }
            item { Spacer(Modifier.height(40.dp)) }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(list),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun Hero(hero: Title?, onOpen: (Title) -> Unit) {
    if (hero == null) return
    Box(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .padding(start = 28.dp, end = 28.dp, top = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Panel),
    ) {
        AsyncImage(
            model = hero.backdrop ?: hero.poster,
            contentDescription = hero.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color(0xF20B0B0E), Color(0x330B0B0E), Color(0x000B0B0E)),
                ),
            ),
        )
        Column(
            Modifier.align(Alignment.CenterStart).padding(30.dp).widthIn(max = 460.dp),
        ) {
            Text(
                hero.kind.label().uppercase(),
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = Amber,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(hero.title, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(hero.year, hero.subtitle).joinToString(" · "),
                fontSize = 13.sp,
                color = Dim,
            )
            Spacer(Modifier.height(18.dp))
            Surface(
                color = Amber,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.clickable { onOpen(hero) },
            ) {
                Row(
                    Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF201400), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open", fontWeight = FontWeight.SemiBold, color = Color(0xFF201400), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Library", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "The device's own records live in the app shell and the web client; this\n"
                + "window keeps none yet. Favourites and \"continue watching\" are the next step.",
            color = Dim,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun DetailPage(
    state: AppState,
    detail: Detail,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
) {
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(end = 8.dp)) {
            item { DetailHeader(state, detail, scope, onBack) }
            if (detail.seasons.size > 1) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 34.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(detail.seasons) { season ->
                            val active = season == state.season
                            Surface(
                                color = if (active) Amber else Panel,
                                shape = RoundedCornerShape(999.dp),
                                modifier = Modifier.clickable {
                                    state.season = season
                                    scope.launch { state.episodes = Api.episodes(detail.ref, season) }
                                },
                            ) {
                                Text(
                                    "S$season",
                                    fontSize = 12.sp,
                                    color = if (active) Color(0xFF201400) else Dim,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
            item { DetailBody(state, detail, scope) }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(list),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun DetailHeader(
    state: AppState,
    detail: Detail,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(320.dp)) {
        AsyncImage(
            model = detail.backdrop ?: detail.poster,
            contentDescription = detail.title,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0x40000000), Ink))),
        )
        Row(Modifier.align(Alignment.TopStart).padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
            // The pill is its own colour, not a scheme colour, so M3 has no
            // content colour to pick: without an explicit one the label would
            // be Unspecified and render black on black.
            Surface(
                color = Color(0xAA000000),
                contentColor = Color(0xFFEDEDF2),
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.clickable { onBack() },
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Back", fontSize = 12.sp)
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(28.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier.width(126.dp).aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(10.dp)).background(Panel),
            ) {
                AsyncImage(model = detail.poster, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.padding(bottom = 6.dp).widthIn(max = 620.dp)) {
                Text(
                    listOfNotNull(
                        detail.kind.label().uppercase(),
                        detail.year,
                        detail.score?.let { "★ %.1f".format(it) },
                    ).joinToString("  ·  "),
                    fontSize = 11.sp,
                    color = Amber,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(detail.title, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Amber,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier.clickable {
                            scope.launch {
                                state.playing = 0
                                val url = Api.streamUrl(detail.ref, state.episodes.firstOrNull()?.number ?: 1)
                                state.playing = null
                                if (url != null) openInBrowser(url) else state.note = "No source resolved."
                            }
                        },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.playing != null) {
                                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF201400), modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (detail.kind == Kind.SERIES) "Play first episode" else "Play",
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF201400),
                                fontSize = 14.sp,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(state.note ?: "", color = Dim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DetailBody(
    state: AppState,
    detail: Detail,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    Row(Modifier.fillMaxWidth().padding(start = 34.dp, end = 40.dp, bottom = 60.dp)) {
        Column(Modifier.weight(1f).widthIn(max = 760.dp)) {
            if (detail.genres.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    detail.genres.take(6).forEach { genre ->
                        Surface(color = Panel, shape = RoundedCornerShape(999.dp)) {
                            Text(genre, fontSize = 11.sp, color = Dim, modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp))
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            Text("Synopsis", fontSize = 13.sp, color = Amber, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text(
                detail.synopsis.ifBlank { "No synopsis came back for this title." },
                fontSize = 13.sp,
                color = Color(0xFFD6D6DE),
                lineHeight = 20.sp,
            )
            if (detail.cast.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    if (detail.kind == Kind.VIDEO) "Studio" else "Cast",
                    fontSize = 13.sp,
                    color = Amber,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(detail.cast.joinToString(", "), fontSize = 13.sp, color = Color(0xFFD6D6DE))
            }
        }
        if (detail.episodes.isNotEmpty() || state.episodes.isNotEmpty()) {
            Spacer(Modifier.width(40.dp))
            Column(Modifier.width(330.dp)) {
                Text(
                    "Episodes" + if (detail.seasons.size > 1) " · season ${state.season}" else "",
                    fontSize = 13.sp,
                    color = Amber,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                state.episodes.forEach { episode ->
                    val active = state.playing == episode.number
                    Surface(
                        color = if (active) Color(0x22F0B45C) else Panel,
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable {
                            scope.launch {
                                state.playing = episode.number
                                val url = Api.streamUrl(detail.ref, episode.number)
                                state.playing = null
                                if (url != null) openInBrowser(url) else state.note = "No source for episode ${episode.number}."
                            }
                        },
                    ) {
                        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("%02d".format(episode.number), fontSize = 12.sp, color = Amber, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(11.dp))
                            Text(episode.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(14.dp), tint = Dim)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hand the resolved source to the machine's own player.
 *
 * An HLS source plays as-is in a browser, and so does the films area's own
 * page — which is why this is one call rather than a second app: an in-window
 * player needs a media stack (and its own decoding) before it can beat what the
 * desktop already has.
 */
private fun openInBrowser(url: String) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    }
}
