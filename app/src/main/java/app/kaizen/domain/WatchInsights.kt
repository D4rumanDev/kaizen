package app.kaizen.domain

/**
 * Una serie vista con lo que se sabe de ella (de su ficha de AniList guardada; null en lo que no se conoce): cuántos capítulos has visto y sus datos.
 * Es lo único que necesita [WatchInsights.compute], sin Android ni JSON.
 */
data class WatchedSeries(
    val chapters: Int,
    val studio: String? = null,
    val year: Int? = null,
    /** Formato de AniList en mayúsculas (TV, MOVIE, OVA, ONA, SPECIAL, TV_SHORT, MUSIC). */
    val format: String? = null,
    /** Minutos por capítulo según la ficha. */
    val minutes: Int? = null
)

/** Un estudio con los capítulos y las series que has visto de él. */
data class StudioWatch(val name: String, val chapters: Int, val series: Int)

/** Lo que se calcula de las series vistas para «Estadísticas»: estudios, época de estreno, formato y horas. */
data class WatchInsights(
    val studios: List<StudioWatch>,
    /** (década, capítulos): 1990 para los años 90… de la más antigua a la más reciente. */
    val decades: List<Pair<Int, Int>>,
    /** (formato en español, capítulos), de más a menos. */
    val formats: List<Pair<String, Int>>,
    /** Horas viendo: con la duración real de cada serie cuando se conoce, y [DEFAULT_MINUTES] por capítulo cuando no. */
    val hours: Int,
    /** Series cuyo estudio se conoce y series totales: dice cuánto fiarse de la lista de estudios. */
    val withStudio: Int,
    val total: Int
) {
    companion object {
        const val DEFAULT_MINUTES = 24
        const val STUDIOS_SHOWN = 8

        fun compute(series: List<WatchedSeries>): WatchInsights {
            val studios = series.filter { !it.studio.isNullOrBlank() }.groupBy { it.studio!!.trim() }
                .map { (name, group) -> StudioWatch(name, group.sumOf { it.chapters }, group.size) }
                .sortedWith(compareByDescending<StudioWatch> { it.chapters }.thenByDescending { it.series }.thenBy { it.name.lowercase() })
                .take(STUDIOS_SHOWN)
            val decades = series.filter { it.year != null && it.year in 1950..2100 }
                .groupBy { it.year!! / 10 * 10 }.mapValues { (_, group) -> group.sumOf { it.chapters } }
                .toList().sortedBy { it.first }
            val formats = series.groupBy { formatName(it.format) }
                .mapValues { (_, group) -> group.sumOf { it.chapters } }
                .toList().sortedByDescending { it.second }
            val minutes = series.sumOf { it.chapters.toLong() * (it.minutes?.takeIf { m -> m in 1..300 } ?: DEFAULT_MINUTES) }
            return WatchInsights(studios, decades, formats, (minutes / 60).toInt(), series.count { !it.studio.isNullOrBlank() }, series.size)
        }

        /** El formato de AniList en español; lo que no se conoce, «Sin datos». */
        fun formatName(code: String?): String = when (code) {
            "TV" -> "Serie"
            "TV_SHORT" -> "Serie corta"
            "MOVIE" -> "Película"
            "OVA" -> "OVA"
            "ONA" -> "ONA"
            "SPECIAL" -> "Especial"
            "MUSIC" -> "Música"
            null, "" -> "Sin datos"
            else -> code
        }
    }
}
