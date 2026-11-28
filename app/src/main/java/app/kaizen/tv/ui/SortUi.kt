@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package app.kaizen.tv.ui

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import app.kaizen.commons.KaizenSettings
import app.kaizen.domain.SeriesStatus
import app.kaizen.domain.SeriesStatuses
import app.kaizen.domain.StatusEntry
import app.kaizen.pojos.av1.AiringSeries
import app.kaizen.pojos.av1.FavoriteAV1
import app.kaizen.pojos.av1.Record
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.Recommended
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.source.AnimeAv1
import app.kaizen.source.Catalog
import app.kaizen.source.normalizeTitle
import app.kaizen.source.seriesIdOf
import app.kaizen.tv.anime.ContinueItem

/** Por qué se ordena una lista de tarjetas. [ORIGINAL] deja el orden de la sección. */
internal enum class ListSort(val label: String) { ORIGINAL("Original"), NAME("A-Z"), SCORE("Nota"), STATUS("Estado"), YEAR("Año") }

/** Lo que de una tarjeta sirve para ordenar (null = la tarjeta no lo trae). */
private class Facts(val name: String, val score: Double?, val year: Int?, val source: String?, val slug: String?, val aid: Int = 0) {
    val ficha: String? get() = if (source != null && slug != null) "$source:$slug" else null
}

private fun factsOf(item: Any): Facts? = when (item) {
    is ContinueItem -> factsOf(item.record)
    is Record -> Facts(item.name, null, null, item.source, item.slug, item.aid)
    is RecentEpisode -> Facts(item.name, null, null, item.source, item.slug, item.aid)
    is FavoriteAV1 -> Facts(item.name, null, null, item.source, item.slug, item.aid)
    is Recommended -> Facts(item.name, item.score, null, AnimeAv1.ID, item.slug, item.aid)
    is SeriesSummary -> Facts(item.name, item.score, item.year, item.source, item.slug, item.aid)
    is StatusEntry -> Facts(item.name, null, null, item.source, item.slug, item.aid)
    is AiringSeries -> Facts(item.name, null, null, item.source, item.slug, item.aid)
    else -> null
}

// «Viendo» primero, «Ninguna» al final.
private fun statusRank(status: SeriesStatus) = when (status) {
    SeriesStatus.WATCHING -> 0
    SeriesStatus.PLAN -> 1
    SeriesStatus.COMPLETED -> 2
    SeriesStatus.DROPPED -> 3
    SeriesStatus.NONE -> 4
}

/**
 * [items] ordenadas por [sort], del revés si [descending]. Las que no traen el dato (p. ej. sin nota) van siempre al final, en su orden
 * de origen, y lo que no es una tarjeta de serie (avisos, tarjetas de reintento) no se mueve del principio.
 */
internal fun <T : Any> sortedItems(
    items: List<T>, sort: ListSort, descending: Boolean, statuses: Map<String, StatusEntry>,
    /** Nota y año de las series que su tarjeta no trae, por «fuente:slug» (del catálogo). */
    extras: Map<String, Pair<Double?, Int?>> = emptyMap(),
    /** Solo las series con este estado (índice en [SeriesStatus.entries]); -1 = todas. Lo que no es una serie se queda. */
    filter: Int = -1,
    /** Con [favoritesOnly], solo las series favoritas según estas claves (de [favoriteKeys]). */
    favorites: Set<String> = emptySet(),
    favoritesOnly: Boolean = false
): List<T> {
    val wanted = SeriesStatus.entries.getOrNull(filter)
    val byStatus = if (wanted == null) items else items.filter { item ->
        factsOf(item)?.let { SeriesStatuses.statusIn(statuses, it.name, it.source, it.slug) == wanted } ?: true
    }
    val shown = if (!favoritesOnly) byStatus else byStatus.filter { item ->
        factsOf(item)?.let {
            val seriesId = if (it.source != null && it.slug != null) seriesIdOf(it.source, it.slug) else 0L
            matchesFavorite(favorites, it.name, it.aid, seriesId, it.slug)
        } ?: true
    }
    if (sort == ListSort.ORIGINAL) return shown
    val (series, others) = shown.partition { factsOf(it) != null }
    val keyed = series.mapNotNull { item ->
        val facts = factsOf(item) ?: return@mapNotNull null
        val key: Comparable<*>? = when (sort) {
            ListSort.NAME -> normalizeTitle(facts.name)
            ListSort.SCORE -> facts.score ?: facts.ficha?.let { extras[it]?.first }
            ListSort.YEAR -> facts.year ?: facts.ficha?.let { extras[it]?.second }
            ListSort.STATUS -> statusRank(SeriesStatuses.statusIn(statuses, facts.name, facts.source, facts.slug))
            ListSort.ORIGINAL -> null
        }
        item to key
    }
    val (withKey, withoutKey) = keyed.partition { it.second != null }
    @Suppress("UNCHECKED_CAST")
    val ordered = withKey.sortedWith { a, b -> (a.second as Comparable<Any>).compareTo(b.second as Any) }.let { if (descending) it.reversed() else it }
    return others + ordered.map { it.first } + withoutKey.map { it.first }
}

/** El orden elegido en una lista (se conserva al girar la pantalla). */
internal class ListSortState(sort: ListSort, descending: Boolean, filter: Int = -1, favoritesOnly: Boolean = false, seen: SeenFilter = SeenFilter.ALL) {
    var sort by mutableStateOf(sort)
    var descending by mutableStateOf(descending)
    /** Estado que se muestra (índice en [SeriesStatus.entries]; -1 = todas). */
    var filter by mutableStateOf(filter)
    /** Solo las series favoritas (se combina con [filter]). */
    var favoritesOnly by mutableStateOf(favoritesOnly)
    /** Capítulos que se muestran en «Recientes»: todos, vistos o no vistos. */
    var seen by mutableStateOf(seen)

    // Pulsar el criterio elegido lo invierte; uno nuevo empieza en ascendente.
    fun choose(option: ListSort) { if (option == sort) descending = !descending else { sort = option; descending = false } }
}

/**
 * [items] ordenadas según [state]. Para «Nota» y «Año» las series cuya tarjeta no trae el dato lo toman del catálogo (se pide una vez
 * por serie y se recuerda); hasta que llega, salen al final.
 */
@Composable
internal fun <T : Any> rememberSorted(items: List<T>, state: ListSortState, statuses: Map<String, StatusEntry>): List<T> {
    val extras = remember { java.util.concurrent.ConcurrentHashMap<String, Pair<Double?, Int?>>() }
    var loaded by remember { mutableStateOf(0) }
    val needsCatalog = state.sort == ListSort.SCORE || state.sort == ListSort.YEAR
    LaunchedEffect(items, needsCatalog) {
        if (!needsCatalog) return@LaunchedEffect
        // (clave de la ficha, fuente, slug) de las que aún no se conocen; sin alguno de los tres no hay nada que pedir.
        val missing = items.mapNotNull { factsOf(it) }.mapNotNull { facts ->
            val ficha = facts.ficha ?: return@mapNotNull null
            val source = facts.source ?: return@mapNotNull null
            val slug = facts.slug ?: return@mapNotNull null
            Triple(ficha, source, slug).takeUnless { extras.containsKey(ficha) }
        }
        if (missing.isEmpty()) return@LaunchedEffect
        try {
            missing.forEachIndexed { index, (ficha, source, slug) ->
                val found = Catalog.scoreAndYear(source, slug)
                // Sin catálogo (aún) no se recuerda el vacío: se reintenta cuando lo haya.
                if (found.first != null || found.second != null) extras[ficha] = found
                if (index % 25 == 24) loaded++
            }
        } finally { loaded++ }
    }
    val favorites = LocalFavorites.current
    return remember(items, state.sort, state.descending, state.filter, state.favoritesOnly, favorites, statuses, loaded) {
        sortedItems(items, state.sort, state.descending, statuses, extras, state.filter, favorites, state.favoritesOnly)
    }
}

/** El orden y los filtros como texto («orden,descendente,estado,favoritas,capítulos») para guardarlos entre sesiones. */
internal fun ListSortState.encode(): String = listOf(sort.ordinal, descending, filter, favoritesOnly, seen.ordinal).joinToString(",")

/** Lo contrario de [encode]; null si el texto está vacío o no se entiende (entonces vale el orden de fábrica). */
internal fun decodeListSort(text: String): ListSortState? {
    val p = text.split(',')
    if (p.size != 5) return null
    return runCatching { ListSortState(ListSort.entries[p[0].toInt()], p[1].toBooleanStrict(), p[2].toInt(), p[3].toBooleanStrict(), SeenFilter.entries[p[4].toInt()]) }.getOrNull()
}

/** Como [rememberListSort], pero la elección de la [section] sobrevive a cerrar la app: se lee al empezar y se guarda al cambiar. */
@Composable
internal fun rememberSavedListSort(section: Int): ListSortState {
    val state = rememberListSort(KaizenSettings.sortChoice(section))
    LaunchedEffect(state, section) {
        androidx.compose.runtime.snapshotFlow { state.encode() }.collect { if (it != KaizenSettings.sortChoice(section)) KaizenSettings.setSortChoice(section, it) }
    }
    return state
}

@Composable
internal fun rememberListSort(start: String = ""): ListSortState = rememberSaveable(saver = androidx.compose.runtime.saveable.Saver<ListSortState, List<Any>>(
    save = { listOf(it.sort.ordinal, it.descending, it.filter, it.favoritesOnly, it.seen.ordinal) },
    restore = {
        ListSortState(ListSort.entries[it[0] as Int], it[1] as Boolean, it[2] as Int,
            it.getOrNull(3) as? Boolean ?: false, SeenFilter.entries.getOrElse(it.getOrNull(4) as? Int ?: 0) { SeenFilter.ALL })
    }
)) { decodeListSort(start) ?: ListSortState(ListSort.ORIGINAL, false) }

/** El orden de la sección que se está viendo (lo crea la pantalla principal; null en las que no se ordenan). */
internal val LocalSectionSort = androidx.compose.runtime.compositionLocalOf<ListSortState?> { null }

/** El orden de la sección actual, o uno propio si la página se usa fuera de la pantalla principal. */
@Composable
internal fun currentSectionSort(): ListSortState = LocalSectionSort.current ?: rememberListSort()

/**
 * Panel de la derecha, oculto: solo se ve una tira fina en el borde. Al llegar a ella con la flecha derecha (desde la última columna
 * de tarjetas) se despliega por encima del contenido con el orden y el filtro por estado; izquierda o Atrás lo cierran.
 */
@Composable
internal fun SidePanel(state: ListSortState, open: Boolean, enabled: Boolean, onOpen: () -> Unit, onClose: () -> Unit, onStripFocus: (Boolean) -> Unit = {}, showSeen: Boolean = false) {
    if (!open) {
        // La tira del borde: ocupa todo el alto, para que cualquier fila llegue a ella con la flecha derecha.
        var focused by remember { mutableStateOf(false) }
        Box(
            Modifier.fillMaxHeight().width(10.dp)
                .background(if (focused) White40 else White10)
                .onFocusChanged { focused = it.isFocused; onStripFocus(it.isFocused); if (it.isFocused) onOpen() }
                // Hasta que una tarjeta tenga el foco, la tira no lo admite: el foco inicial no debe abrir el panel.
                .focusProperties { canFocus = enabled }
                .focusable()
        )
        return
    }
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    androidx.compose.foundation.layout.Column(
        Modifier.fillMaxHeight().width(210.dp)
            // El foco no sale del panel hacia arriba ni abajo (debajo hay tarjetas ocultas); solo se cierra con Izquierda o Atrás.
            .focusExit { dir -> if (dir == androidx.compose.ui.focus.FocusDirection.Up || dir == androidx.compose.ui.focus.FocusDirection.Down) FocusRequester.Cancel else FocusRequester.Default }
            .focusGroup().background(SurfaceDark).padding(horizontal = 12.dp, vertical = 16.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .onPreviewKeyEvent { event ->
                if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && event.key == androidx.compose.ui.input.key.Key.DirectionLeft) { onClose(); true } else false
            },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PanelTitle("Ordenar por")
        ListSort.entries.forEach { option ->
            val arrow = if (option == state.sort && option != ListSort.ORIGINAL) (if (state.descending) "  ▼" else "  ▲") else ""
            KaizenButton((if (option == state.sort) "✓  " else "") + option.label + arrow, onClick = { state.choose(option) },
                modifier = Modifier.fillMaxWidth().then(if (option == state.sort) Modifier.focusRequester(first) else Modifier), fontSize = Text14)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        PanelTitle("Mostrar")
        (listOf(-1) + SeriesStatus.entries.map { it.ordinal }).forEach { option ->
            val name = if (option < 0) "Todas" else if (option == SeriesStatus.NONE.ordinal) "Sin estado" else SeriesStatus.entries[option].label
            KaizenButton((if (option == state.filter) "✓  " else "") + name, onClick = { state.filter = option },
                modifier = Modifier.fillMaxWidth(), fontSize = Text14)
        }
        KaizenButton((if (state.favoritesOnly) "✓  " else "") + "Solo favoritas", onClick = { state.favoritesOnly = !state.favoritesOnly },
            modifier = Modifier.fillMaxWidth(), fontSize = Text14)
        // Solo «Recientes» tiene capítulos con ojo.
        if (showSeen) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
            PanelTitle("Capítulos")
            SeenFilter.entries.forEach { option ->
                KaizenButton((if (option == state.seen) "✓  " else "") + option.label, onClick = { state.seen = option },
                    modifier = Modifier.fillMaxWidth(), fontSize = Text14)
            }
        }
    }
}

@Composable
internal fun PanelTitle(text: String) {
    androidx.tv.material3.Text(text, color = androidx.compose.ui.graphics.Color.White, fontSize = Text16,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
}
