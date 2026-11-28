package app.kaizen.tv.ui

/** Secciones del menú lateral de la pantalla principal: ids (también los guarda «Secciones del menú») y títulos. */
object MainSections {
    const val RECENTS = 0
    const val LAST_SEEN = 1
    const val FAVORITES = 2
    const val STAFF = 3
    const val BEST = 4
    const val BESTGLOBAL = 5
    const val SEARCH = 12
    const val TOOLS = 7
    const val CONTINUE = 8
    const val EMISSION = 9
    const val HOME = 10
    const val SOURCES = 11
    const val STATUS = 13
    // 6 (antes «Directorio», y «Secciones» en V1) queda retirado: lo que se guardó con ese id no debe ocultar «Buscar».
    /** Id de «Secciones» antes del 2026-10-07 (ver KaizenSettings.migrateHiddenRows). */
    const val SECTIONS_V1 = 6

    /** Cabeceras del menú lateral en orden (id, título); todas ocultables en «Herramientas». */
    val CONFIGURABLE_ROWS = listOf(
        HOME to "Inicio",
        FAVORITES to "Favoritos",
        STATUS to "Mi lista",
        LAST_SEEN to "Vistos",
        STAFF to "Recomendados",
        // «Mejores» reúne las dos listas (en emisión y de siempre) con un conmutador arriba; BESTGLOBAL ya no tiene entrada propia en el menú.
        BEST to "Mejores",
        SEARCH to "Buscar",
        EMISSION to "Emisión",
        SOURCES to "Fuentes"
    )

    /** [rows] en el orden guardado (ids separados por comas); las que no están guardadas (nuevas) van al final en su orden de fábrica. */
    internal fun applyOrder(rows: List<Pair<Int, String>>, saved: String): List<Pair<Int, String>> {
        val first = saved.split(',').mapNotNull { id -> id.toIntOrNull()?.let { wanted -> rows.firstOrNull { it.first == wanted } } }.distinct()
        return first + rows.filter { it !in first }
    }

    /** Todas las secciones en el orden elegido en Ajustes. */
    fun ordered(): List<Pair<Int, String>> = applyOrder(CONFIGURABLE_ROWS, app.kaizen.commons.KaizenSettings.menuOrder)

}
