package li.mof.kamigura.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import li.mof.kamigura.ChapterDto
import li.mof.kamigura.CollectionDto
import li.mof.kamigura.GenreTagDto
import li.mof.kamigura.KamiguraLog
import li.mof.kamigura.KavitaApi
import li.mof.kamigura.KavitaClient
import li.mof.kamigura.KavitaSession
import li.mof.kamigura.KavitaSessionStore
import li.mof.kamigura.ReadingListDto
import li.mof.kamigura.SearchHistoryStore
import li.mof.kamigura.SearchResultDto
import li.mof.kamigura.SearchResultGroupDto
import li.mof.kamigura.SeriesByIdsDto
import li.mof.kamigura.SeriesDto
import li.mof.kamigura.SeriesFilterStatementDto
import li.mof.kamigura.SeriesFilterV2Dto
import li.mof.kamigura.TagDto
import li.mof.kamigura.ui.DarkLoadingState
import li.mof.kamigura.ui.DarkMessageState
import li.mof.kamigura.ui.browse.BrowsePageScaffold
import li.mof.kamigura.ui.browse.PosterGrid
import li.mof.kamigura.ui.browse.SeriesPosterCard
import li.mof.kamigura.ui.theme.KamiguraBackground
import li.mof.kamigura.ui.theme.KamiguraSurface

enum class SearchSeriesTarget(val routeValue: String, val titlePrefix: String) {
    Person("person", "by"),
    Genre("genre", "Genre"),
    Tag("tag", "Tag"),
    Collection("collection", "Collection"),
    ReadingList("reading-list", "Reading List");

    companion object {
        fun fromRouteValue(value: String?): SearchSeriesTarget? {
            return entries.firstOrNull { it.routeValue == value }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeSearchScreen(
    api: KavitaApi?,
    session: KavitaSession,
    historyStore: SearchHistoryStore,
    initialQuery: String = "",
    onSelectSeries: (SeriesDto) -> Unit,
    onOpenFilteredSeries: (SearchSeriesTarget, Int, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val recentQueries by historyStore.recentQueries.collectAsState(initial = emptyList())

    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var result by remember { mutableStateOf(SearchResultGroupDto()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) query = initialQuery
    }

    LaunchedEffect(api, query) {
        val trimmed = query.trim()
        if (trimmed.isBlank() || api == null) {
            searching = false
            result = SearchResultGroupDto()
            return@LaunchedEffect
        }
        delay(SearchDebounceMs)
        searching = true
        runCatching { api.search(trimmed, includeChapterAndFiles = true) }
            .onSuccess {
                result = it
                if (!it.isEmpty()) historyStore.record(trimmed)
            }
            .onFailure {
                KamiguraLog.w("Could not search Kavita.", it)
                result = SearchResultGroupDto()
            }
        searching = false
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(KamiguraBackground),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = {
                            query = it
                            scope.launch { historyStore.record(it) }
                        },
                        expanded = false,
                        onExpandedChange = {},
                        placeholder = { Text("Search") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotBlank()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                }
                            }
                        }
                    )
                },
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier.fillMaxWidth(),
            ) {}
        }

        if (query.isBlank()) {
            item {
                RecentSearches(
                    queries = recentQueries,
                    onPick = { query = it }
                )
            }
            return@LazyColumn
        }

        if (searching) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        val series = result.series.map { it.toSeriesDto() }
        if (series.isNotEmpty()) {
            item {
                SearchSeriesSection(
                    title = "Series",
                    series = series,
                    session = session,
                    onSelectSeries = onSelectSeries
                )
            }
        }
        if (result.persons.isNotEmpty()) {
            item {
                SearchChipSection(
                    title = "Persons",
                    chips = result.persons.mapNotNull { person ->
                        val id = person.id ?: return@mapNotNull null
                        SearchChipItem(id, person.name.orEmpty())
                    },
                    onClick = { onOpenFilteredSeries(SearchSeriesTarget.Person, it.id, it.label) }
                )
            }
        }
        if (result.genres.isNotEmpty()) {
            item {
                SearchChipSection(
                    title = "Genres",
                    chips = result.genres.mapNotNull { it.toChipItem() },
                    onClick = { onOpenFilteredSeries(SearchSeriesTarget.Genre, it.id, it.label) }
                )
            }
        }
        if (result.tags.isNotEmpty()) {
            item {
                SearchChipSection(
                    title = "Tags",
                    chips = result.tags.mapNotNull { it.toChipItem() },
                    onClick = { onOpenFilteredSeries(SearchSeriesTarget.Tag, it.id, it.label) }
                )
            }
        }
        if (result.collections.isNotEmpty()) {
            item {
                SearchChipSection(
                    title = "Collections",
                    chips = result.collections.map { SearchChipItem(it.id, it.title) },
                    onClick = { onOpenFilteredSeries(SearchSeriesTarget.Collection, it.id, it.label) }
                )
            }
        }
        if (result.readingLists.isNotEmpty()) {
            item {
                SearchChipSection(
                    title = "Reading Lists",
                    chips = result.readingLists.map { SearchChipItem(it.id, it.title ?: "Reading List ${it.id}") },
                    onClick = { onOpenFilteredSeries(SearchSeriesTarget.ReadingList, it.id, it.label) }
                )
            }
        }
        if (result.chapters.isNotEmpty()) {
            item {
                SearchChapterSection(
                    chapters = result.chapters,
                    onClick = { chapter ->
                        val currentApi = api ?: return@SearchChapterSection
                        scope.launch {
                            runCatching { currentApi.seriesForChapter(chapter.id) }
                                .onSuccess { onSelectSeries(it) }
                                .onFailure {
                                    KamiguraLog.w("Could not resolve series for chapter ${chapter.id}.", it)
                                }
                        }
                    }
                )
            }
        }

        if (!searching && result.isEmpty()) {
            item {
                DarkMessageState(
                    title = "No results",
                    body = "Try another title, creator, genre, or tag."
                )
            }
        }
    }
}

@Composable
private fun RecentSearches(
    queries: List<String>,
    onPick: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchSectionHeader(title = "Recent searches")
        if (queries.isEmpty()) {
            Text(
                text = "Your recent searches will appear here.",
                color = Color(0xFFB9BDBD),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                queries.forEach { query ->
                    AssistChip(
                        onClick = { onPick(query) },
                        label = { Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSeriesSection(
    title: String,
    series: List<SeriesDto>,
    session: KavitaSession,
    onSelectSeries: (SeriesDto) -> Unit
) {
    Column {
        SearchSectionHeader(title = title)
        Spacer(Modifier.height(10.dp))
        val carouselState = rememberCarouselState { series.size }
        val cardShape = MaterialTheme.shapes.small
        HorizontalMultiBrowseCarousel(
            state = carouselState,
            preferredItemWidth = SearchCarouselItemWidth,
            modifier = Modifier
                .fillMaxWidth()
                .height(SearchCarouselHeight),
            itemSpacing = SearchCarouselItemSpacing,
            minSmallItemWidth = 48.dp,
            maxSmallItemWidth = 72.dp,
            contentPadding = PaddingValues(horizontal = SearchCarouselHorizontalPadding)
        ) { index ->
            val item = series[index]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .maskClip(cardShape)
            ) {
                SeriesPosterCard(
                    series = item,
                    session = session,
                    shape = cardShape,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onSelectSeries(item) }
                )
            }
        }
    }
}

@Composable
private fun SearchChipSection(
    title: String,
    chips: List<SearchChipItem>,
    onClick: (SearchChipItem) -> Unit
) {
    if (chips.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchSectionHeader(title = title)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            chips.forEach { item ->
                AssistChip(
                    onClick = { onClick(item) },
                    label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchChapterSection(
    chapters: List<ChapterDto>,
    onClick: (ChapterDto) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SearchSectionHeader(title = "Chapters")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chapters.forEach { chapter ->
                Surface(
                    color = KamiguraSurface,
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClick(chapter) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = chapter.titleName?.takeIf { it.isNotBlank() } ?: "Chapter ${chapter.id}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = chapter.searchSubtitle(),
                                color = Color(0xFFB9BDBD),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Color(0xFFE6EAEA)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
internal fun SearchSeriesScreen(
    sessionStore: KavitaSessionStore,
    target: SearchSeriesTarget,
    targetId: Int,
    label: String,
    onBack: () -> Unit,
    onSelectSeries: (SeriesDto) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var series by remember { mutableStateOf<List<SeriesDto>>(emptyList()) }
    var session by remember { mutableStateOf(KavitaSession()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(target, targetId) {
        loading = true
        error = null
        try {
            session = sessionStore.load()
            val (api, _) = KavitaClient(ctx, sessionStore).buildApi()
            series = api.loadSearchSeries(target, targetId).sortedBy { it.name }
        } catch (t: Throwable) {
            KamiguraLog.w("Could not load filtered search series for ${target.routeValue}.", t)
            error = t.message ?: t.toString()
        } finally {
            loading = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .background(KamiguraBackground)
    ) {
        BrowsePageScaffold(title = "${target.titlePrefix} $label", onBack = onBack) {
            when {
                loading -> DarkLoadingState()
                error != null -> DarkMessageState("Could not load results", error ?: "Unknown error")
                series.isEmpty() -> DarkMessageState("No series", "No readable series matched this result.")
                else -> PosterGrid(items = series, key = { it.id }) { item ->
                    SeriesPosterCard(
                        series = item,
                        session = session,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSeries(item) }
                    )
                }
            }
        }
    }
}

private suspend fun KavitaApi.loadSearchSeries(target: SearchSeriesTarget, targetId: Int): List<SeriesDto> {
    return when (target) {
        SearchSeriesTarget.Person -> allSeriesV2(
            body = SeriesFilterV2Dto(
                statements = PersonFilterFields.map { field ->
                    SeriesFilterStatementDto(
                        comparison = FilterContains,
                        field = field,
                        value = targetId.toString()
                    )
                },
                combination = FilterOr
            ),
            pageNumber = 0,
            pageSize = SearchFilteredSeriesPageSize
        )
        SearchSeriesTarget.Genre -> allSeriesV2(searchFilter(SeriesFilterFieldGenres, targetId), 0, SearchFilteredSeriesPageSize)
        SearchSeriesTarget.Tag -> allSeriesV2(searchFilter(SeriesFilterFieldTags, targetId), 0, SearchFilteredSeriesPageSize)
        SearchSeriesTarget.Collection -> allSeriesV2(
            searchFilter(SeriesFilterFieldCollections, targetId),
            0,
            SearchFilteredSeriesPageSize
        )
        SearchSeriesTarget.ReadingList -> {
            val ids = readingListItems(targetId).map { it.seriesId }.distinct()
            if (ids.isEmpty()) emptyList() else seriesByIds(SeriesByIdsDto(ids))
        }
    }
}

private fun searchFilter(field: Int, id: Int): SeriesFilterV2Dto {
    return SeriesFilterV2Dto(
        statements = listOf(
            SeriesFilterStatementDto(
                comparison = FilterContains,
                field = field,
                value = id.toString()
            )
        )
    )
}

private fun SearchResultGroupDto.isEmpty(): Boolean {
    return series.isEmpty() &&
        collections.isEmpty() &&
        readingLists.isEmpty() &&
        persons.isEmpty() &&
        genres.isEmpty() &&
        tags.isEmpty() &&
        chapters.isEmpty()
}

private fun SearchResultDto.toSeriesDto(): SeriesDto {
    return SeriesDto(
        id = seriesId,
        name = name,
        originalName = originalName,
        localizedName = localizedName,
        libraryId = libraryId,
        libraryName = libraryName
    )
}

private fun GenreTagDto.toChipItem(): SearchChipItem? {
    val genreId = id ?: return null
    return SearchChipItem(genreId, title.orEmpty())
}

private fun TagDto.toChipItem(): SearchChipItem? {
    val tagId = id ?: return null
    return SearchChipItem(tagId, title.orEmpty())
}

private fun ChapterDto.searchSubtitle(): String {
    val numberText = number?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
        ?: title?.takeIf { it.isNotBlank() }
        ?: id.toString()
    val pageText = pages?.takeIf { it > 0 }?.let { "$it pages" }
    return listOfNotNull("Chapter $numberText", pageText).joinToString(" • ")
}

private data class SearchChipItem(
    val id: Int,
    val label: String
)

private const val SearchDebounceMs = 400L
private const val SearchFilteredSeriesPageSize = 300

private const val FilterOr = 0
private const val FilterContains = 5
private const val SeriesFilterFieldTags = 6
private const val SeriesFilterFieldCollections = 7
private const val SeriesFilterFieldTranslators = 8
private const val SeriesFilterFieldCharacters = 9
private const val SeriesFilterFieldPublisher = 10
private const val SeriesFilterFieldEditor = 11
private const val SeriesFilterFieldCoverArtist = 12
private const val SeriesFilterFieldLetterer = 13
private const val SeriesFilterFieldColorist = 14
private const val SeriesFilterFieldInker = 15
private const val SeriesFilterFieldPenciller = 16
private const val SeriesFilterFieldWriters = 17
private const val SeriesFilterFieldGenres = 18
private const val SeriesFilterFieldImprint = 29
private const val SeriesFilterFieldTeam = 30
private const val SeriesFilterFieldLocation = 31

private val PersonFilterFields = listOf(
    SeriesFilterFieldTranslators,
    SeriesFilterFieldCharacters,
    SeriesFilterFieldPublisher,
    SeriesFilterFieldEditor,
    SeriesFilterFieldCoverArtist,
    SeriesFilterFieldLetterer,
    SeriesFilterFieldColorist,
    SeriesFilterFieldInker,
    SeriesFilterFieldPenciller,
    SeriesFilterFieldWriters,
    SeriesFilterFieldImprint,
    SeriesFilterFieldTeam,
    SeriesFilterFieldLocation
)

private val SearchCarouselItemWidth = 164.dp
private val SearchCarouselHeight = 300.dp
private val SearchCarouselItemSpacing = 14.dp
private val SearchCarouselHorizontalPadding = 16.dp
