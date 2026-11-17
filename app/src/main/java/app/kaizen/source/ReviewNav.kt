package app.kaizen.source

/**
 * Por dónde va la revisión de series dudosas (Configuración › Series › AniList: dudosas), como un valor inmutable: cada paso devuelve el estado siguiente y la pantalla solo lo guarda. Antes
 * eran cuatro variables y tres «está decidiendo» sueltos repartidos entre dos composables; así las reglas (hasta dónde se ha llegado, qué se puede deshacer, que una pulsación doble no decida
 * dos veces) están en un sitio y se prueban sin interfaz.
 *
 * - [index]: la serie que se muestra. [frontier]: hasta dónde se ha llegado (al volver atrás con [back], decidir o saltar lleva de nuevo hasta ahí). [passed]: las series por las que se ha
 *   pasado, para «Anterior». [history]: lo decidido, para «Deshacer» (saltar no cambia nada y no entra).
 * - [busy]: hay una escritura en curso (decidir, resolver sola o deshacer). Mientras dura no se acepta otra: [claim] lo toma y avanzar o terminar de deshacer lo suelta.
 */
internal data class ReviewNav(
    val index: Int = 0,
    val frontier: Int = 0,
    val passed: List<Int> = emptyList(),
    val history: List<Decision> = emptyList(),
    val busy: Boolean = false
) {
    /** Una decisión ya tomada: dónde estaba, la serie y lo que había antes de cada ficha. */
    class Decision(val index: Int, val series: PendingSeries, val before: List<AnimeFuente?>)

    val canUndo: Boolean get() = history.isNotEmpty()

    /** Toma el candado de escritura; null si ya hay una en curso (la pulsación sobra). */
    fun claim(): ReviewNav? = if (busy) null else copy(busy = true)

    /** Pasa a la siguiente (o, si se había vuelto atrás, hasta donde se estaba) y suelta el candado. */
    private fun forward(): ReviewNav {
        val next = if (index < frontier) frontier else index + 1
        return copy(index = next, frontier = maxOf(frontier, next), busy = false)
    }

    /** Se decidió ([before] = lo que había antes, para deshacer) o se saltó ([before] null) la serie [series] que se mostraba. */
    fun done(series: PendingSeries, before: List<AnimeFuente?>?): ReviewNav =
        copy(history = if (before != null) history + Decision(index, series, before) else history, passed = passed + index).forward()

    /** La serie se resolvió sola (descartada o aceptada): se sigue, sin poder volver a ella con «Anterior». */
    fun resolved(): ReviewNav = forward()

    /** «Anterior»: vuelve a la última serie por la que se pasó; null si no hay o si hay una escritura en curso (la decisión iría a otra serie). */
    fun back(): ReviewNav? = if (busy) null else passed.lastOrNull()?.let { copy(index = it, passed = passed.dropLast(1)) }

    /** «Deshacer»: la decisión a revertir y el estado con el candado tomado; null si no hay nada que deshacer o hay otra escritura en curso. Al terminar de revertirla, [undone]. */
    fun undo(): Pair<ReviewNav, Decision>? {
        val last = history.lastOrNull() ?: return null
        val taken = claim() ?: return null
        return taken.copy(history = history.dropLast(1)) to last
    }

    /** La decisión [last] ya está revertida: se vuelve a su serie y se suelta el candado. */
    fun undone(last: Decision): ReviewNav = copy(passed = passed - last.index, frontier = minOf(frontier, last.index + 1), index = last.index, busy = false)
}
