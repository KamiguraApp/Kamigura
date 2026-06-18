package li.mof.kamigura.library

import android.net.Uri

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import li.mof.kamigura.GroupedSeriesDto
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.LibraryDto
import li.mof.kamigura.SeriesDto
import li.mof.kamigura.normalizeKavitaBaseUrl
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.seriesCoverUrl
import li.mof.kamigura.ui.seriesInitial

private enum class HomeDestination(
    val label: String,
    val icon: ImageVector
) {
    Home("Home", Icons.Filled.Home),
    Libraries("Libraries", Icons.Filled.CollectionsBookmark),
    WantToRead("Want", Icons.Filled.BookmarkBorder),
    Browse("Browse", Icons.Filled.Explore),
    Search("Search", Icons.Filled.Search)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    sessionStore: KavitaSessionStore,
    sessionRevision: Int,
    onOpenSettings: () -> Unit,
    onSelectLibrary: (LibraryDto) -> Unit,
    onSelectSeries: (SeriesDto) -> Unit
) {
    val ctx = LocalContext.current

    var libs by remember { mutableStateOf<List<LibraryDto>>(emptyList()) }
    var onDeck by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var recentlyUpdated by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var newlyAdded by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var wantToRead by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var wantToReadError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var serverName by remember { mutableStateOf("No server selected") }
    var session by remember { mutableStateOf(KavitaSession()) }

    LaunchedEffect(sessionRevision) {
        libs = emptyList()
        onDeck = emptyList()
        recentlyUpdated = emptyList()
        newlyAdded = emptyList()
        wantToRead = emptyList()
        wantToReadError = null
        error = null
        loading = true
        try {
            serverName = sessionStore.activeProfile()?.name ?: "No server selected"
            session = sessionStore.load()
            val client = KavitaClient(ctx, sessionStore)
            val (api, _) = client.buildApi()
            libs = api.userLibraries().sortedBy { it.id }
            onDeck = api.onDeck(pageSize = 12)
            recentlyUpdated = api.recentlyUpdatedSeries(pageSize = 16)
                .map { it.toSeriesDto() }
                .distinctBy { it.id }
            newlyAdded = api.recentlyAdded(pageSize = 18)
            runCatching { api.wantToRead(pageSize = 200) }
                .onSuccess { wantToRead = it }
                .onFailure { wantToReadError = it.message ?: it.toString() }
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    HomeShell(
        libraries = libs,
        serverName = serverName,
        loading = loading,
        error = error,
        session = session,
        onDeck = onDeck,
        recentlyUpdated = recentlyUpdated,
        newlyAdded = newlyAdded,
        wantToRead = wantToRead,
        wantToReadError = wantToReadError,
        onOpenSettings = onOpenSettings,
        onSelectLibrary = onSelectLibrary,
        onSelectSeries = onSelectSeries
    )
}

@Composable
private fun HomeShell(
    libraries: List<LibraryDto>,
    serverName: String,
    loading: Boolean,
    error: String?,
    session: KavitaSession,
    onDeck: List<SeriesDto>,
    recentlyUpdated: List<SeriesDto>,
    newlyAdded: List<SeriesDto>,
    wantToRead: List<SeriesDto>,
    wantToReadError: String?,
    onOpenSettings: () -> Unit,
    onSelectLibrary: (LibraryDto) -> Unit,
    onSelectSeries: (SeriesDto) -> Unit
) {
    var destination by rememberSaveable(
        stateSaver = Saver(
            save = { it.ordinal },
            restore = { HomeDestination.entries[it] }
        )
    ) { mutableStateOf(HomeDestination.Home) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(Color(0xFF202222))
    ) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Column(Modifier.fillMaxSize()) {
                HomeTopBar(serverName = serverName, onOpenSettings = onOpenSettings)
                Row(Modifier.fillMaxSize()) {
                    HomeNavigationRail(
                        selected = destination,
                        onSelect = { destination = it }
                    )
                    HomeContent(
                        destination = destination,
                        libraries = libraries,
                        loading = loading,
                        error = error,
                        session = session,
                        onDeck = onDeck,
                        recentlyUpdated = recentlyUpdated,
                        newlyAdded = newlyAdded,
                        wantToRead = wantToRead,
                        wantToReadError = wantToReadError,
                        onSelectLibrary = onSelectLibrary,
                        onSelectSeries = onSelectSeries,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                HomeTopBar(serverName = serverName, onOpenSettings = onOpenSettings)
                HomeContent(
                    destination = destination,
                    libraries = libraries,
                    loading = loading,
                    error = error,
                    session = session,
                    onDeck = onDeck,
                    recentlyUpdated = recentlyUpdated,
                    newlyAdded = newlyAdded,
                    wantToRead = wantToRead,
                    wantToReadError = wantToReadError,
                    onSelectLibrary = onSelectLibrary,
                    onSelectSeries = onSelectSeries,
                    modifier = Modifier.weight(1f)
                )
                HomeBottomNavigation(
                    selected = destination,
                    onSelect = { destination = it }
                )
            }
        }
    }
}

@Composable
private fun HomeTopBar(serverName: String, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF171818))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Kamigura", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(serverName, color = Color(0xFFB9BDBD), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun HomeNavigationRail(
    selected: HomeDestination,
    onSelect: (HomeDestination) -> Unit
) {
    NavigationRail(
        modifier = Modifier
            .fillMaxHeight()
            .background(Color(0xFF181A1A)),
        containerColor = Color(0xFF181A1A),
        contentColor = Color.White
    ) {
        Spacer(Modifier.height(12.dp))
        HomeDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = { NavDestinationIcon(destination, selected == destination) },
                label = { Text(destination.label) }
            )
        }
    }
}

@Composable
private fun HomeBottomNavigation(
    selected: HomeDestination,
    onSelect: (HomeDestination) -> Unit
) {
    NavigationBar(
        containerColor = Color(0xFF181A1A),
        contentColor = Color.White
    ) {
        HomeDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
                icon = { NavDestinationIcon(destination, selected == destination) },
                label = { Text(destination.label) }
            )
        }
    }
}

@Composable
private fun NavDestinationIcon(destination: HomeDestination, selected: Boolean) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            scale.snapTo(0.8f)
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }
    Icon(
        imageVector = destination.icon,
        contentDescription = destination.label,
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    )
}

@Composable
private fun LibraryHub(
    libraries: List<LibraryDto>,
    session: KavitaSession,
    onSelectLibrary: (LibraryDto) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF202222))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Libraries", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (libraries.isEmpty()) {
            Text("No libraries", color = Color(0xFF9FA5A5), style = MaterialTheme.typography.bodyMedium)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                libraries.forEach { library ->
                    Surface(
                        color = Color(0xFF2C3030),
                        contentColor = Color.White,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectLibrary(library) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            LibraryIcon(library, session)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    library.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    library.subtitleText(),
                                    color = Color(0xFFB9BDBD),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryIcon(library: LibraryDto, session: KavitaSession) {
    val hasRemoteCover = session.baseUrl.isNotBlank() &&
        session.apiKey.isNotBlank() &&
        library.coverImage?.isNotBlank() == true
    var coverLoaded by remember(session.baseUrl, session.apiKey, library.id, library.coverImage) {
        mutableStateOf(false)
    }

    Surface(
        modifier = Modifier.size(48.dp),
        color = Color(0xFF273A32),
        contentColor = Color(0xFFD3EEE3),
        shape = MaterialTheme.shapes.small
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!coverLoaded) {
                Text(
                    library.iconText(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (hasRemoteCover) {
                AsyncImage(
                    model = libraryCoverUrl(session, library.id),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    contentScale = ContentScale.Fit,
                    onSuccess = { coverLoaded = true },
                    onError = { coverLoaded = false }
                )
            }
        }
    }
}

private fun libraryCoverUrl(session: KavitaSession, libraryId: Int): String {
    val root = normalizeKavitaBaseUrl(session.baseUrl)
    val apiKey = session.apiKey.takeIf { it.isNotBlank() }?.let { "&apiKey=${Uri.encode(it)}" }.orEmpty()
    return "$root/api/Image/library-cover?libraryId=$libraryId$apiKey"
}

private fun LibraryDto.iconText(): String {
    return name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "L"
}

private fun LibraryDto.subtitleText(): String {
    val folderName = folders.firstOrNull()
        ?.replace('\\', '/')
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
    return folderName ?: "Kavita library"
}

@Composable
private fun HomePlaceholder(destination: HomeDestination, modifier: Modifier = Modifier) {
    DarkMessageState(
        title = destination.label,
        body = "This section is not implemented yet."
    )
}

@Composable
private fun HomeContent(
    destination: HomeDestination,
    libraries: List<LibraryDto>,
    loading: Boolean,
    error: String?,
    session: KavitaSession,
    onDeck: List<SeriesDto>,
    recentlyUpdated: List<SeriesDto>,
    newlyAdded: List<SeriesDto>,
    wantToRead: List<SeriesDto>,
    wantToReadError: String?,
    onSelectLibrary: (LibraryDto) -> Unit,
    onSelectSeries: (SeriesDto) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        if (loading) {
            DarkLoadingState()
            return@Column
        }
        if (error != null) {
            DarkMessageState(title = "Could not load home", body = error)
            return@Column
        }

        when (destination) {
            HomeDestination.Home -> {
                if (onDeck.isEmpty() && recentlyUpdated.isEmpty() && newlyAdded.isEmpty()) {
                    DarkMessageState(
                        title = "No series",
                        body = "This server did not return visible home shelves."
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        item {
                            HomeShelf("On Deck", onDeck, session, onSelectSeries)
                        }
                        item {
                            HomeShelf("Recently Updated Series", recentlyUpdated, session, onSelectSeries)
                        }
                        item {
                            HomeShelf("Newly Added Series", newlyAdded, session, onSelectSeries)
                        }
                    }
                }
            }
            HomeDestination.Libraries -> {
                LibraryHub(
                    libraries = libraries,
                    session = session,
                    onSelectLibrary = onSelectLibrary,
                    modifier = Modifier.fillMaxSize()
                )
            }
            HomeDestination.WantToRead -> {
                if (wantToReadError != null) {
                    DarkMessageState(
                        title = "Could not load Want to Read",
                        body = wantToReadError
                    )
                } else {
                    WantToReadGrid(
                        series = wantToRead,
                        session = session,
                        onSelectSeries = onSelectSeries,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            HomeDestination.Browse,
            HomeDestination.Search -> {
                HomePlaceholder(destination, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun WantToReadGrid(
    series: List<SeriesDto>,
    session: KavitaSession,
    onSelectSeries: (SeriesDto) -> Unit,
    modifier: Modifier = Modifier
) {
    if (series.isEmpty()) {
        DarkMessageState(
            title = "Want to Read",
            body = "No series added yet."
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(series, key = { it.id }) { item ->
            SeriesPosterCard(
                series = item,
                session = session,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelectSeries(item) }
            )
        }
    }
}

@Composable
private fun HomeShelf(
    title: String,
    series: List<SeriesDto>,
    session: KavitaSession,
    onSelectSeries: (SeriesDto) -> Unit
) {
    Column {
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (series.isEmpty()) {
            Text("Nothing here yet", color = Color(0xFF9FA5A5), style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                series.forEach { item ->
                    SeriesPosterCard(
                        series = item,
                        session = session,
                        modifier = Modifier.width(160.dp),
                        onClick = { onSelectSeries(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesPosterCard(
    series: SeriesDto,
    session: KavitaSession,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF303333))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.68f)
                    .background(Color(0xFF111111)),
                contentAlignment = Alignment.Center
            ) {
                if (session.baseUrl.isNotBlank() && session.apiKey.isNotBlank()) {
                    AsyncImage(
                        model = seriesCoverUrl(session, series.id),
                        contentDescription = series.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(seriesInitial(series.name), color = Color(0xFFB9BDBD), style = MaterialTheme.typography.headlineMedium)
                }
            }
            Text(
                text = series.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
    }
}

private fun GroupedSeriesDto.toSeriesDto(): SeriesDto {
    return SeriesDto(
        id = seriesId,
        name = seriesName ?: "Series $seriesId",
        libraryId = libraryId
    )
}
