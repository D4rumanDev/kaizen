package app.kaizen.source

/** Orden de los resultados. */
enum class SortBy { TITLE, RATING }

/**
 * Filtros de una consulta. Cada fuente declara los que resuelve ella misma ([AnimeSource.pushes]);
 * el motor ([SearchEngine]) aplica en local los demás sobre lo que la fuente devuelve.
 */
enum class Filter { TEXT, GENRE, AIRING, YEAR, SCORE, SORT_RATING }

/**
 * Lo que pide el usuario, igual para todas las fuentes. La nota va en estrellas (/5).
 * Unir o separar fuentes no es un parámetro: lo decide la configuración (una fuente activa = sus datos solos;
 * varias = las series iguales se unen en una ficha).
 */
data class SearchQuery(
    val text: String = "",
    val genre: String? = null,
    val airing: Boolean = false,
    val minScore: Double? = null,
    val years: IntRange? = null,
    val sort: SortBy = SortBy.TITLE
) {
    /** Filtros activos en esta consulta. */
    val active: Set<Filter>
        get() = buildSet {
            if (text.isNotBlank()) add(Filter.TEXT)
            if (genre != null) add(Filter.GENRE)
            if (airing) add(Filter.AIRING)
            if (minScore != null) add(Filter.SCORE)
            if (years != null) add(Filter.YEAR)
            if (sort == SortBy.RATING) add(Filter.SORT_RATING)
        }

    /** Clave de caché: lo que cambia lo que devuelve una fuente. */
    val cacheKey: String
        get() = listOf(
            text.trim().lowercase(),
            genre.orEmpty(),
            airing,
            minScore ?: "",
            years?.let { "${it.first}-${it.last}" }.orEmpty(),
            sort
        ).joinToString("|")
}
