package app.kaizen.source

import kotlin.math.abs

/**
 * Los datos de una ficha de una fuente que valen como evidencia al decidir si es el mismo anime que otro, con un único criterio para el
 * emparejado con AniList, la comprobación de enlaces y la unión de fichas de distintas fuentes: dos fichas del mismo anime no pueden
 * tener años de estreno distintos (con un año de margen: el estreno cae a final de año o una fuente cuenta la emisión de otro modo).
 * Un año que falta no juzga nada.
 */
internal object LinkEvidence {
    const val YEAR_MARGIN = 1

    /** Si dos años pueden ser del mismo anime; sin alguno de los dos, sí. */
    fun yearsClose(a: Int?, b: Int?, margin: Int = YEAR_MARGIN): Boolean = a == null || b == null || abs(a - b) <= margin

    /** Si todos los años conocidos de [years] caben juntos en el margen (los desconocidos no cuentan). */
    fun compatible(years: Collection<Int?>): Boolean {
        val known = years.filterNotNull()
        return known.isEmpty() || known.max() - known.min() <= YEAR_MARGIN
    }

    /** El año con el que se compara una serie con AniList: el más antiguo de sus fichas (el de su estreno), si alguna lo da. */
    fun seriesYear(years: Collection<Int?>): Int? = years.filterNotNull().minOrNull()
}
