package app.kaizen.tv.ui

import android.os.SystemClock
import androidx.compose.ui.focus.FocusRequester

/**
 * Foco de una rejilla de tarjetas: recuerda cuál tenía el foco para devolvérselo a la que la sustituye cuando desaparece
 * («Quitar de Seguir viendo», de favoritos…). Sin esto el foco cae en el menú lateral y, pasado un instante, cambia la sección.
 * Las secciones se numeran por posición (Inicio tiene dos: Seguir viendo y Recientes) y las tarjetas por su clave.
 */
internal class GridFocus {
    private val requesters = HashMap<String, FocusRequester>()

    /** Sección y clave de la tarjeta que tiene el foco (null si ninguna). */
    var focused: Pair<Int, String>? = null

    /** Claves de cada sección en la última composición, para saber dónde estaba la tarjeta que ha desaparecido. */
    var previous: List<List<String>> = emptyList()

    fun requester(section: Int, key: String): FocusRequester = requesters.getOrPut("$section/$key") { FocusRequester() }

    /** Olvida los requesters de tarjetas que ya no están. */
    fun retain(current: List<List<String>>) {
        val live = current.withIndex().flatMap { (section, keys) -> keys.map { "$section/$it" } }.toSet()
        requesters.keys.retainAll(live)
    }
}

/**
 * Marca que el usuario acaba de ejecutar una acción del menú de una tarjeta (quitar de Seguir viendo, de favoritos…). Solo entonces una
 * tarjeta que desaparece le pasa el foco a su vecina: así una recarga cualquiera no le roba el foco a quien ha ido al menú lateral, y no
 * hace falta fiarse de que el aviso de foco se dispare antes o después de que la tarjeta se retire.
 */
internal object CardMenuSignal {
    private const val WINDOW_MS = 3_000L
    @Volatile private var at = 0L

    fun arm() { at = SystemClock.elapsedRealtime() }

    fun recent(): Boolean = at != 0L && SystemClock.elapsedRealtime() - at < WINDOW_MS
}

/**
 * A qué tarjeta pasa el foco cuando la enfocada ([focused]: sección y clave) ya no está en [current]: la que ocupa su puesto en la
 * misma sección (o la última si era la última); si la sección se ha quedado vacía, la primera de la siguiente con tarjetas (o, si no
 * hay, la última de la anterior). Null si la tarjeta sigue ahí o no se sabe dónde estaba.
 */
internal fun refocusTarget(previous: List<List<String>>, current: List<List<String>>, focused: Pair<Int, String>): Pair<Int, String>? {
    val (section, key) = focused
    val remaining = current.getOrNull(section).orEmpty()
    if (key in remaining) return null
    val index = previous.getOrNull(section)?.indexOf(key)?.takeIf { it >= 0 } ?: return null
    if (remaining.isNotEmpty()) return section to remaining[index.coerceAtMost(remaining.lastIndex)]
    val next = (section + 1 until current.size).firstOrNull { current[it].isNotEmpty() }
    if (next != null) return next to current[next].first()
    val before = (section - 1 downTo 0).firstOrNull { current[it].isNotEmpty() } ?: return null
    return before to current[before].last()
}
