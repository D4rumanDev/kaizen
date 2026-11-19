package app.kaizen.tv.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.tv.material3.Text
import app.kaizen.commons.KaizenSettings
import app.kaizen.tv.ui.theme.Text14

/**
 * Listas largas por tandas que **crecen solas al hacer scroll**: se muestran las primeras [ListPages.size] tarjetas (el tamaño de «Recientes», [KaizenSettings.recentsCount]) y, al acercarse al final,
 * se añade otra tanda, sin botones ni saltos. La rejilla ya solo compone las tarjetas visibles; lo que costaba en una tele lenta (el historial llega a 6.000) era ordenar y filtrar la lista entera y
 * mantener miles de claves, y eso se hace una vez por cambio. Lo que se parte es la lista ya ordenada y filtrada, así que el orden vale para toda ella.
 */
internal object ListPages {
    /** Menos que esto no tiene sentido: con 20 tarjetas por tanda se estaría pidiendo más a cada momento. */
    const val MIN_SIZE = 20

    /** Cuántas tarjetas antes del final (de lo que hay en la rejilla) empieza a pedirse otra tanda: unas dos filas de un mando que baja de fila en fila. */
    const val GROW_MARGIN = 15

    fun size(): Int = KaizenSettings.recentsCount.coerceAtLeast(MIN_SIZE)

    /** Cuántas tarjetas se muestran tras pedir otra tanda: [size] más, sin pasar del [total]. */
    fun grown(shown: Int, size: Int, total: Int): Int = minOf(total, shown + size)

    /**
     * Si hay que añadir otra tanda: quedan más elementos ([canGrow]) y la última tarjeta visible ([lastVisible], índice en la rejilla) está entre las últimas [margin] de lo que hay
     * ([gridItems], con cabeceras y pie incluidos: así no depende de cuántas filas haya antes de la lista).
     */
    fun needsMore(lastVisible: Int, gridItems: Int, canGrow: Boolean, margin: Int = GROW_MARGIN): Boolean =
        canGrow && gridItems > 0 && lastVisible >= gridItems - margin
}

/**
 * Clave por valor del orden y los filtros elegidos: [ListSortState] cambia sus campos sin cambiar de instancia, así que la instancia no sirve para saber que cambió algo.
 * Con ella, [rememberListWindow] vuelve al principio (y a la primera tanda) al cambiar el orden o un filtro.
 */
internal fun sortResetKey(sort: ListSortState): List<Any> = listOf(sort.sort, sort.descending, sort.filter, sort.favoritesOnly, sort.seen)

/** Lo que se ve de una lista larga: las primeras [items] de [total], y el desplazamiento de la rejilla que las muestra. */
internal class ListWindow<T>(val items: List<T>, val total: Int, val gridState: LazyGridState) {
    /** Si hay más elementos que los mostrados (para el pie «Mostrando n de total»). */
    val hasMore: Boolean get() = items.size < total
}

/**
 * La ventana de [all]: empieza con una tanda y crece otra cada vez que la rejilla ([ListWindow.gridState]) llega cerca del final. [resetKey] (el orden y los filtros, o la consulta) la devuelve
 * a una tanda y al principio. Si la lista se encoge, la ventana se ajusta a lo que hay.
 */
@Composable
internal fun <T> rememberListWindow(all: List<T>, resetKey: Any?): ListWindow<T> {
    val size = ListPages.size()
    var shown by remember(resetKey, size) { mutableIntStateOf(size) }
    val state = rememberLazyGridState()
    LaunchedEffect(resetKey, size) { state.scrollToItem(0) }
    val count = minOf(shown, all.size)
    val canGrow = count < all.size
    LaunchedEffect(state, count, canGrow, size) {
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to state.layoutInfo.totalItemsCount }
            // `total > count`: tras crecer, la primera lectura puede ser la de antes de medir la rejilla con las tarjetas nuevas (menos elementos que [count]); con ella se pediría otra tanda de más.
            .collect { (last, total) -> if (total > count && ListPages.needsMore(last, total, canGrow)) shown = ListPages.grown(count, size, all.size) }
    }
    val items = remember(all, count) { all.subList(0, count) }
    return ListWindow(items, all.size, state)
}

/**
 * «Mostrando 120 de 6.465» al final de una lista que aún tiene más por enseñar (se añaden solas al seguir bajando). Con [totalKnown] en false (una lista de Paging que sigue cargando: el total
 * es solo lo cargado hasta ahora) dice «Mostrando 120…» sin total, para no dar una cifra falsa.
 */
@Composable
internal fun WindowFooter(window: ListWindow<*>, loadingMore: Boolean = false, totalKnown: Boolean = true, modifier: Modifier = Modifier) {
    if (!window.hasMore && !loadingMore) return
    val text = when {
        !window.hasMore -> "Cargando más…"
        totalKnown -> "Mostrando ${window.items.size} de ${window.total}"
        else -> "Mostrando ${window.items.size}…"
    }
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = Text14, modifier = modifier.padding(vertical = 12.dp))
}
