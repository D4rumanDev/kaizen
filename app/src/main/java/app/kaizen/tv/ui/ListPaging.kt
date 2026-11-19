package app.kaizen.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import app.kaizen.commons.KaizenSettings
import app.kaizen.tv.ui.components.KaizenButton
import app.kaizen.tv.ui.theme.*
import androidx.tv.material3.Text

/**
 * Partir una lista larga en páginas del tamaño de «Recientes» ([KaizenSettings.recentsCount]): una rejilla con miles de tarjetas (el historial llega a 6.000) costaba
 * memoria y tiempo de composición, sobre todo en una tele lenta. Lo que se parte es la lista ya ordenada y filtrada, así que el orden vale para todas las páginas.
 */
internal object ListPages {
    /** Menos que esto no tiene sentido: con 20 tarjetas por página el menú de páginas estorbaría más que ayudaría. */
    const val MIN_SIZE = 20

    fun size(): Int = KaizenSettings.recentsCount.coerceAtLeast(MIN_SIZE)

    /** Cuántas páginas hacen falta para [total] elementos (una, aunque no haya ninguno). */
    fun count(total: Int, size: Int): Int = if (total <= 0) 1 else (total + size - 1) / size

    /** Los elementos de la página [page] (empieza en 0; fuera de rango se lleva a la última o a la primera). */
    fun <T> slice(items: List<T>, page: Int, size: Int): List<T> {
        val at = page.coerceIn(0, count(items.size, size) - 1)
        return items.subList(at * size, minOf(items.size, (at + 1) * size))
    }
}

/**
 * Clave por valor del orden y los filtros elegidos: [ListSortState] cambia sus campos sin cambiar de instancia, así que la instancia no sirve para saber que cambió algo.
 * Con ella, [rememberListPage] vuelve a la primera página al cambiar el orden o un filtro.
 */
internal fun sortResetKey(sort: ListSortState): List<Any> = listOf(sort.sort, sort.descending, sort.filter, sort.favoritesOnly, sort.seen)

/** La página en pantalla de una lista y lo que hace falta para cambiarla: la página, el desplazamiento de la rejilla y si hay que devolver el foco a la primera tarjeta. */
internal class ListPage<T>(
    val items: List<T>,
    val page: Int,
    val pages: Int,
    val gridState: LazyGridState,
    private val setPage: (Int) -> Unit,
    private val setRefocus: (Boolean) -> Unit,
    val refocus: Boolean
) {
    fun go(target: Int) {
        setRefocus(true)
        setPage(target.coerceIn(0, pages - 1))
    }

    fun consumedRefocus() = setRefocus(false)
}

/**
 * Parte [all] en páginas. [resetKey] (el orden y los filtros) devuelve a la primera página al cambiar. Al cambiar de página la rejilla vuelve arriba; quien cambió de
 * página desde la barra de abajo recupera el foco en la primera tarjeta (si no, al desaparecer el botón caería en el menú lateral).
 */
@Composable
internal fun <T> rememberListPage(all: List<T>, resetKey: Any?): ListPage<T> {
    val size = ListPages.size()
    var requested by remember(resetKey) { mutableIntStateOf(0) }
    var refocus by remember { mutableStateOf(false) }
    val pages = ListPages.count(all.size, size)
    val page = requested.coerceIn(0, pages - 1)
    val state = rememberLazyGridState()
    // Si la lista se encoge y la página pedida ya no existe, se queda en la última: así, si luego crece, no salta a la página antigua.
    LaunchedEffect(page) { if (requested != page) requested = page }
    LaunchedEffect(page, resetKey, size) { state.scrollToItem(0) }
    val items = remember(all, page, size) { ListPages.slice(all, page, size) }
    return ListPage(items, page, pages, state, { requested = it }, { refocus = it }, refocus)
}

/**
 * Tras cambiar de página con la barra de abajo, el foco pasa a la primera tarjeta de la página nueva. Reintenta unos fotogramas: la tarjeta puede no estar compuesta todavía.
 */
@Composable
internal fun <T> RefocusFirstCard(paged: ListPage<T>, focus: GridFocus, section: Int, firstKey: String?) {
    LaunchedEffect(paged.page, paged.refocus, firstKey) {
        if (!paged.refocus) return@LaunchedEffect
        // Sin tarjetas (la lista se vació justo al cambiar de página) no hay a quién dar el foco: se descarta para que no se lo quede la primera que aparezca después.
        if (firstKey == null) return@LaunchedEffect paged.consumedRefocus()
        repeat(10) {
            if (runCatching { focus.requester(section, firstKey).requestFocus() }.getOrDefault(false)) {
                paged.consumedRefocus()
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
        paged.consumedRefocus()
    }
}

/** «‹ Anterior · Página 2 de 5 · Siguiente ›». No sale con una sola página. Con [knownTotal] en false (la lista sigue cargando) dice solo «Página 2». */
@Composable
internal fun PageBar(paged: ListPage<*>, modifier: Modifier = Modifier, knownTotal: Boolean = true) {
    if (paged.pages <= 1) return
    Row(modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        KaizenButton("‹ Anterior", { paged.go(paged.page - 1) }, fontSize = Text14, enabled = paged.page > 0)
        Text(if (knownTotal) "Página ${paged.page + 1} de ${paged.pages}" else "Página ${paged.page + 1}", color = Color.White, fontSize = Text14)
        KaizenButton("Siguiente ›", { paged.go(paged.page + 1) }, fontSize = Text14, enabled = paged.page < paged.pages - 1)
    }
}
