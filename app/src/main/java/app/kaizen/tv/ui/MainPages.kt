@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui

import app.kaizen.tv.ui.rememberLoadable
import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import app.kaizen.tv.search.SearchPage
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import app.kaizen.commons.KaizenSettings
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.background
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.commons.KaizenProgress
import app.kaizen.pojos.av1.FavoriteAV1
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.Recommended
import app.kaizen.pojos.av1.Record
import app.kaizen.domain.SeriesStatuses
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.source.SearchQuery
import app.kaizen.source.SortBy
import app.kaizen.retrofit.Repository
import app.kaizen.tv.TVRepository
import app.kaizen.tv.anime.ContinueItem
import app.kaizen.tv.settings.SettingsPage
import app.kaizen.commons.appString

/** Qué hacer con una tarjeta: pulsarla ([open]) o abrir su menú de pulsación larga ([menu]). */
internal class CardActions(val open: (Any) -> Unit, val menu: (Any) -> Unit)

/**
 * Kaizen: cada cabecera del menú lateral carga su propia página: las secciones de tarjetas son rejillas y Directorio,
 * Emisión, Fuentes y Herramientas, paneles propios. Los datos de las secciones vienen de [MainViewModel].
 */
@Composable
internal fun MainPage(id: Int, model: MainViewModel, actions: CardActions) {
    when (id) {
        MainSections.SEARCH -> SearchPage(actions)
        MainSections.BEST, MainSections.BESTGLOBAL -> BestPage(model, actions)
        MainSections.EMISSION -> CompositionLocalProvider(LocalCardWidth provides rowCardWidth()) { EmissionPage(actions) }
        MainSections.LAST_SEEN -> HistoryPage(model, actions)
        MainSections.STATUS -> MyListPage(actions)
        MainSections.TOOLS -> SettingsPage()
        MainSections.SOURCES -> SourcesPage()
        MainSections.HOME -> HomePage(model, actions)
        else -> SectionGridPage(id, model, actions)
    }
}

/** Columnas y ancho (dp) de carátula para un ancho disponible: lo comparten las rejillas y las filas con la misma medida (Emisión). */
internal fun gridColumns(settled: androidx.compose.ui.unit.Dp): Pair<Int, Float> {
    val available = (settled - ContentPaddingHorizontal * 2).value
    val gap = cardSpacing.value
    val target = ((available - gap * (PosterTargetColumns - 1)) / PosterTargetColumns).coerceAtLeast(PosterNarrowestWidth.value) * KaizenSettings.cardScale
    return fitColumns(available, target, gap)
}

/** Ancho de carátula de las filas sueltas (Emisión), igual que el de las rejillas con el menú cerrado. */
@Composable
private fun rowCardWidth(): androidx.compose.ui.unit.Dp {
    val screen = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    return gridColumns(screen - ClosedMenuWidth).second.dp
}

/** Rejilla con las tarjetas pegadas a la izquierda: caben tantas como permita el ancho de la pantalla. */
@Composable
internal fun CardGrid(
    modifier: Modifier = Modifier,
    userScrollEnabled: Boolean = true,
    /** Calcula las columnas con el ancho que tiene de verdad (diálogos con algo al lado), no con el de la pantalla sin menú. */
    fitToParent: Boolean = false,
    /** Para quien necesita desplazar la rejilla (p. ej. mantener a la vista la tarjeta que se mueve). */
    state: androidx.compose.foundation.lazy.grid.LazyGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState(),
    content: LazyGridScope.() -> Unit
) {
    val padding = PaddingValues(start = ContentPaddingHorizontal, end = ContentPaddingHorizontal, top = 8.dp, bottom = ContentPaddingVertical + 4.dp)
    // Las tarjetas se estiran un poco para que la fila llene el ancho en lugar de dejar un hueco a la derecha. Se calculan una
    // sola vez para el ancho con el menú cerrado: al abrirlo la rejilla no se vuelve a medir ni cambian las tarjetas (el
    // menú pasa por encima y tapa las últimas columnas), así que no hay animación de tamaño ni columnas que salten.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val screen = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
        val settled = if (fitToParent) maxWidth else maxOf(maxWidth, screen - ClosedMenuWidth)
        // Seis columnas por defecto en cualquier pantalla: el ancho de la tarjeta sale del ancho disponible ya adaptado a la densidad
        // (ver UiScale); «Tamaño de las tarjetas» lo escala y [fitColumns] ajusta las columnas que caben.
        val (columns, width) = gridColumns(settled)
        CompositionLocalProvider(LocalCardWidth provides width.dp) {
            LazyVerticalGrid(
                state = state,
                columns = GridCells.Fixed(columns),
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(settled).fillMaxHeight(),
                userScrollEnabled = userScrollEnabled,
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(cardSpacing),
                verticalArrangement = Arrangement.spacedBy(cardRowSpacing),
                content = content
            )
        }
    }
}

/** Ancho del menú lateral cerrado (el de NavigationDrawer de TV Material). */

internal fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

/** La tarjeta que corresponde a cada elemento de las secciones. */
@Composable
internal fun ItemCard(item: Any, actions: CardActions) {
    val open = { actions.open(item) }
    val menu = { actions.menu(item) }
    when (item) {
        is ContinueItem -> {
            val progress = item.progress
            // «E6 · 32 %»: el capítulo y lo visto (la barra va debajo).
            val line = shortChapter(item.record.chapter()) + (if (progress.durationMs > 0) " · ${(progress.fraction.coerceIn(0.0, 1.0) * 100).toInt()} %" else " · " + KaizenProgress.formatTime(progress.positionMs))
            ContinueCard(item.record, open, menu, line, progress.fraction.toFloat().coerceIn(0f, 1f).takeIf { progress.durationMs > 0 })
        }
        is Record -> RecordCard(item, open, menu)
        is RecentEpisode -> RecentCard(item, open, menu)
        // Los favoritos no guardan nota: se pide al pintar la tarjeta, como en el resto de tarjetas sin nota.
        is FavoriteAV1 -> SeriesCard(item.aid, item.slug, item.imageUrl(), item.name, null, item.source, open, menu, markFavorite = false, isFavorite = true)
        is Recommended -> RecommendedCard(item, open, menu)
        is SeriesSummary -> SeriesCard(item.aid, item.slug, item.imageUrl, item.name, item.score, item.source, open, menu)
        is RetryCard -> IconTextCard(item.title, open) { TintedIcon(item.icon) }
        is VerifyCard -> IconTextCard("Verificar fuente", open, tall = true, summary = "${item.host}\nPulsa para hacerlo") { TintedIcon(item.icon) }
    }
}

/** Identidad de un elemento de las secciones: con ella la rejilla conserva el foco cuando la lista cambia de orden. */
private fun stableKey(item: Any): String = when (item) {
    is ContinueItem -> "c:${item.record.source}:${item.record.eid}"
    is Record -> "r:${item.source}:${item.eid}"
    is RecentEpisode -> "n:${item.source}:${item.eid}"
    is FavoriteAV1 -> "f:${item.source}:${item.aid}"
    is Recommended -> "s:${item.slug}"
    is VerifyCard -> "v:${item.host}"
    is SeriesSummary -> "m:${item.source}:${item.slug}"
    else -> item.javaClass.name
}

private fun LazyGridScope.cardItems(items: List<Any>, keys: List<String>, actions: CardActions, focus: GridFocus, section: Int = 0, span: Int = 1) {
    items(items.size, key = { keys[it] }, span = { GridItemSpan(span) }) { index ->
        val key = keys[index]
        // El requester y el aviso de foco van en un contenedor: el foco entra por la tarjeta que contiene, y si la tarjeta desaparece
        // [GridFocus] sabe cuál era y a quién pasárselo.
        Box(Modifier.focusRequester(focus.requester(section, key)).onFocusChanged {
            // No se borra al perder el foco: cuando la tarjeta se quita, ese aviso puede llegar antes que el efecto que la busca.
            if (it.hasFocus) focus.focused = section to key
        }) { ItemCard(items[index], actions) }
    }
}

/**
 * Cuando la tarjeta enfocada desaparece de [keys] (quitada de Seguir viendo o de favoritos), le pasa el foco a la vecina antes de que
 * caiga en el menú lateral. Reintenta unos fotogramas: la tarjeta nueva puede no estar compuesta todavía.
 */
@Composable
private fun RefocusOnRemoval(focus: GridFocus, keys: List<List<String>>) {
    androidx.compose.runtime.LaunchedEffect(keys) {
        val target = focus.focused?.takeIf { CardMenuSignal.recent() }?.let { refocusTarget(focus.previous, keys, it) }
        focus.previous = keys
        focus.retain(keys)
        if (target != null) repeat(10) {
            if (runCatching { focus.requester(target.first, target.second).requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
        }
    }
}

@Composable
private fun rememberCardKeys(items: List<Any>): List<String> = remember(items) { uniqueKeys(items.map(::stableKey)) }

/** Sección de tarjetas (Favoritos, Recomendados...) en rejilla. */
@Composable
private fun SectionGridPage(id: Int, model: MainViewModel, actions: CardActions) {
    val flow = model.section(id) ?: return
    val items by flow.collectAsStateWithLifecycle()
    val visible = items.filter { it !== LoadingCard }
    if (visible.isEmpty() && items.isNotEmpty()) return LoadingGrid(header = true)
    // Orden elegido por el usuario (por defecto el de la sección); con los estados, para ordenar por «Estado».
    val sort = currentSectionSort()
    val statuses by SeriesStatuses.all.collectAsStateWithLifecycle()
    val shown = rememberSorted(visible, sort, statuses)
    val keys = rememberCardKeys(shown)
    val focus = remember { GridFocus() }
    RefocusOnRemoval(focus, listOf(keys))
    CardGrid {
        cardItems(shown, keys, actions, focus)
    }
}

/** «Últimos vistos»: «Seguir viendo» (tarjetas horizontales con su progreso) y, debajo, el historial. */
@Composable
private fun HistoryPage(model: MainViewModel, actions: CardActions) {
    val continueFlow = model.section(MainSections.CONTINUE) ?: return
    val historyFlow = model.section(MainSections.LAST_SEEN) ?: return
    val rawContinue by continueFlow.collectAsStateWithLifecycle()
    val rawHistory by historyFlow.collectAsStateWithLifecycle()
    val sort = currentSectionSort()
    val statuses by SeriesStatuses.all.collectAsStateWithLifecycle()
    val started = rawContinue.filter { it !== LoadingCard }
    val history = rememberSorted(rawHistory.filter { it !== LoadingCard }, sort, statuses)
    if (started.isEmpty() && history.isEmpty() && (rawContinue + rawHistory).any { it === LoadingCard }) return LoadingGrid(header = true)
    val keys = listOf(rememberCardKeys(started), rememberCardKeys(history))
    val focus = remember { GridFocus() }
    RefocusOnRemoval(focus, keys)
    CardGrid {
        if (started.isNotEmpty()) {
            fullWidth { SectionTitle(appString(R.string.ui_seguir_viendo)) }
            // Las tarjetas horizontales ocupan dos columnas: así caben el título y el capítulo.
            cardItems(started, keys[0], actions, focus, 0, span = 2)
        }
        if (history.isNotEmpty()) {
            fullWidth { SectionTitle(appString(R.string.ui_historial)) }
            cardItems(history, keys[1], actions, focus, 1)
        }
    }
}

/**
 * Página con varias secciones, como las rejillas (Inicio = «Seguir viendo» + «Recientes»). Las secciones vacías no se
 * muestran, y mientras alguna carga y no hay nada que enseñar salen los huecos de carga.
 */
@Composable
private fun HomePage(model: MainViewModel, actions: CardActions) {
    // Inicio solo lleva «Recientes»; «Seguir viendo» está unido al historial en «Últimos vistos».
    val sections = listOf(MainSections.RECENTS to "Recientes")
    val loaded = sections.map { (id, title) -> Triple(id, title, model.section(id)?.collectAsStateWithLifecycle()?.value.orEmpty()) }
    val rawVisible = loaded.map { (_, _, items) -> items.filter { it !== LoadingCard } }
    // La barra de orden solo afecta a «Recientes» («Seguir viendo» conserva su orden).
    val sort = currentSectionSort()
    val statuses by SeriesStatuses.all.collectAsStateWithLifecycle()
    val recentsAt = loaded.indexOfFirst { it.first == MainSections.RECENTS }
    // Lo visto, en memoria con el criterio del ojo de cada tarjeta, para mostrar solo los capítulos vistos o los no vistos.
    val seenIndex by remember {
        kotlinx.coroutines.flow.combine(app.kaizen.domain.Library.history, app.kaizen.source.SeriesLedger.version, KaizenProgress.changes) { records, _, _ ->
            SeenIndex(records) { series, number, eid -> KaizenProgress.startedOf(KaizenProgress.Key(series, number), eid) != null }
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
    }.collectAsStateWithLifecycle<SeenIndex?>(null)
    val ledger by app.kaizen.source.SeriesLedger.version.collectAsStateWithLifecycle()
    val recentsRaw = rawVisible[recentsAt]
    val recentsBySeen = remember(recentsRaw, sort.seen, seenIndex, ledger) {
        val index = seenIndex
        if (sort.seen == SeenFilter.ALL || index == null) recentsRaw else recentsRaw.filter { item ->
            item !is RecentEpisode || index.isSeen(item.eid, item.aid, app.kaizen.source.seriesIdOf(item.source, item.slug), item.number) == (sort.seen == SeenFilter.SEEN)
        }
    }
    val recents = rememberSorted(recentsBySeen, sort, statuses)
    val visible = rawVisible.mapIndexed { index, list -> if (index == recentsAt) recents else list }
    if (visible.all { it.isEmpty() } && loaded.any { (_, _, items) -> items.any { it === LoadingCard } }) return LoadingGrid(header = true)
    val keys = visible.map { rememberCardKeys(it) }
    val focus = remember { GridFocus() }
    RefocusOnRemoval(focus, keys)
    val refreshing by Repository.recentsLoading.collectAsStateWithLifecycle()
    // Un solo título: lo que se está viendo (con su barra de progreso) va primero y los capítulos recientes después, en la misma rejilla.
    CardGrid {
        fullWidth {
            SectionTitle("Recientes") {
                // Los recientes se guardan unos minutos: el botón, junto al título, los pide ahora a las fuentes.
                KaizenButton(if (refreshing) appString(R.string.ui_actualizando) else appString(R.string.ui_actualizar), onClick = { Repository.reloadRecents(force = true) }, fontSize = Text14)
            }
        }
        loaded.forEachIndexed { index, _ ->
            if (visible[index].isNotEmpty()) cardItems(visible[index], keys[index], actions, focus, index)
        }
    }
}

/** Rejilla paginada de series con el mensaje de estado: «Cargando…», «Sin resultados» o error con reintento al pulsar OK. */
@Composable
internal fun PagedGrid(
    items: LazyPagingItems<SeriesSummary>,
    actions: CardActions?,
    onOpen: (SeriesSummary) -> Unit,
    header: (LazyGridScope.() -> Unit)? = null
) {
    val refresh = items.loadState.refresh
    val empty = items.itemCount == 0
    // Orden elegido por el usuario: se aplica a lo ya cargado y, mientras se ordena, se siguen pidiendo páginas (hasta [SORT_MAX_ITEMS]).
    val sort = currentSectionSort()
    val statuses by SeriesStatuses.all.collectAsStateWithLifecycle()
    val sorting = sort.sort != ListSort.ORIGINAL || sort.filter >= 0 || sort.favoritesOnly
    val loaded = remember(items, items.itemCount, items.loadState.refresh) { (0 until items.itemCount).mapNotNull { items.peek(it) } }
    val sorted = rememberSorted(loaded, sort, statuses)
    val sortedKeys = remember(sorted) { uniqueKeys(sorted.map { "${it.source}:${it.slug}" }) }
    LaunchedEffect(items.itemCount, sorting) {
        if (sorting && items.itemCount in 1 until SORT_MAX_ITEMS) items[items.itemCount - 1]
    }
    Box(Modifier.fillMaxSize()) {
        CardGrid {
            header?.invoke(this)
                if (sorting) {
                items(sorted.size, key = { sortedKeys[it] }, contentType = { "series" }) { index ->
                    val series = sorted[index]
                    SeriesCard(series.aid, series.slug, series.imageUrl, series.name, series.score, series.source,
                        onClick = { onOpen(series) }, onLongClick = actions?.let { { it.menu(series) } })
                }
            } else items(items.itemCount, contentType = { "series" }) { index ->
                val series = items[index] ?: return@items
                SeriesCard(series.aid, series.slug, series.imageUrl, series.name, series.score, series.source,
                    onClick = { onOpen(series) }, onLongClick = actions?.let { { it.menu(series) } })
            }
        }
        when {
            refresh is LoadState.Error && empty -> {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(appString(R.string.ui_no_se_pudo_cargar), color = Color.White, fontSize = Text17)
                    KaizenButton(appString(R.string.ui_reintentar), onClick = { items.retry() })
                }
            }
            refresh is LoadState.NotLoading && empty -> CenterMessage(appString(R.string.ui_sin_resultados), illustration = true)
            refresh is LoadState.Loading && empty -> LoadingGrid()
        }
    }
}

/** Tope de series que se cargan para ordenar una lista paginada. */
private const val SORT_MAX_ITEMS = 600

/** «Mejores»: un conmutador «En emisión / De siempre» sobre la lista que toque. */
@Composable
private fun BestPage(model: MainViewModel, actions: CardActions) {
    var global by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(Modifier.padding(start = ContentPaddingHorizontal, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KaizenButton("En emisión", { global = false }, container = if (!global) White60 else White20, fontSize = Text14)
            KaizenButton("De siempre", { global = true }, container = if (global) White60 else White20, fontSize = Text14)
        }
        Box(Modifier.weight(1f)) { if (global) BestGlobalPage(actions) else SectionGridPage(MainSections.BEST, model, actions) }
    }
}

/** «Mejores de siempre»: el catálogo interno ordenado por puntuación. */
@Composable
private fun BestGlobalPage(actions: CardActions) {
    val items = remember { TVRepository.paged(SearchQuery(sort = SortBy.RATING)) }.collectAsLazyPagingItems()
    // El catálogo no se recorre desde aquí: se gestiona en Configuración › Biblioteca; esta sección solo lo lee.
    PagedGrid(items, actions, onOpen = { actions.open(it) })
}

/**
 * Para un bloque «título + fila»: al enfocar una tarjeta, lo que se trae a la vista empieza en el borde de arriba del bloque y no en la tarjeta. Sin esto, al subir con el
 * mando la lista se detiene en cuanto la tarjeta se ve y el título del día (que está encima) queda fuera de la pantalla.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.includeTitleInView(): Modifier = bringIntoViewResponder(object : androidx.compose.foundation.relocation.BringIntoViewResponder {
    override fun calculateRectForParent(localRect: androidx.compose.ui.geometry.Rect) = localRect.copy(top = 0f)
    override suspend fun bringChildIntoView(localRect: () -> androidx.compose.ui.geometry.Rect?) = Unit
})

private val ROW_PADDING = 6.dp
private val DAY_NAMES = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")

/** Horario de emisión: una fila por día, de lunes a domingo. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun EmissionPage(actions: CardActions) {
    var attempt by remember { mutableStateOf(0) }
    // Cargando, o fallan todas las fuentes: fila para reintentar (un horario vacío no es un error).
    val calendar = rememberLoadable(attempt) { TVRepository.calendarMap() }
    val days = when (calendar) {
        Loadable.Loading -> return LoadingGrid()
        is Loadable.Failed -> return CardGrid { fullWidth { SectionTitle(appString(R.string.ui_no_se_pudo_cargar_el_horario)) }
            item { IconTextCard(appString(R.string.ui_reintentar), { attempt++ }) { TintedIcon(R.drawable.ic_sync) } } }
        is Loadable.Ready -> calendar.value
    }
    val sort = currentSectionSort()
    val statuses by SeriesStatuses.all.collectAsStateWithLifecycle()
    // Al entrar desde el menú lateral el foco va a la primera tarjeta del primer día (lunes), no a la que quede a la altura del menú.
    val firstDay = (1..7).firstOrNull { days[it].orEmpty().isNotEmpty() }
    val first = remember { FocusRequester() }
    val today = remember { java.time.LocalDate.now().dayOfWeek.value }
    LazyColumn(Modifier.fillMaxSize().focusProperties { enter = { if (firstDay != null) first else FocusRequester.Default } }.focusGroup(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (day in 1..7) {
            val dayItems = days[day].orEmpty()
            if (dayItems.isEmpty()) continue
            item(key = day) {
                val items = rememberSorted(dayItems, sort, statuses)
                Column(Modifier.includeTitleInView()) {
                    androidx.compose.foundation.layout.Row(Modifier.padding(start = ContentPaddingHorizontal, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(DAY_NAMES[day - 1], color = Color.White, fontSize = SectionTitleText, fontWeight = FontWeight.Bold)
                        if (day == today) {
                            Text("Hoy", color = Color.White, fontSize = Text14, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 12.dp).background(Accent, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 2.dp))
                        }
                    }
                    val keys = remember(items) { uniqueKeys(items.map { "${it.source}:${it.aid}" }) }
                    LazyRow(contentPadding = PaddingValues(horizontal = ContentPaddingHorizontal, vertical = ROW_PADDING), horizontalArrangement = Arrangement.spacedBy(cardSpacing)) {
                        items(items.size, key = { keys[it] }) { index ->
                            val airing = items[index]
                            val summary = remember(airing) { airing.asMin() }
                            Box(if (day == firstDay && index == 0) Modifier.focusRequester(first) else Modifier) {
                                SeriesCard(summary.aid, summary.slug, summary.imageUrl, summary.name, summary.score, summary.source,
                                    onClick = { actions.open(airing) })
                            }
                        }
                    }
                }
            }
        }
    }
}
