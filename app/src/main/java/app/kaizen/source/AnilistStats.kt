package app.kaizen.source

/**
 * Las cifras del emparejado con AniList que se enseñan en Configuración › Catálogo, calculadas aparte (sin base de datos) para poder probarlas. Distingue lo que ya
 * está hecho de lo que solo se podría hacer: una unión es **hecha** cuando el libro de series junta varias fichas en una serie, y está **por hacer** cuando el mismo
 * anime de AniList está en series distintas (se hará al reagrupar el catálogo). Que haya enlaces con AniList no significa que haya uniones.
 */
internal class AnilistStats(
    /** Fichas que conoce el libro de series (cada una es de una fuente). */
    val fichas: Int,
    /** De ellas, las que ya tienen una fila de enlace (sea cual sea el resultado). */
    val revisadas: Int,
    /** Series del libro con más de una ficha, y cuántas fichas reúnen: las uniones hechas. */
    val seriesUnidas: Int,
    val fichasUnidas: Int,
    /** Animes de AniList que están en más de una serie: se unirían al reagrupar. */
    val unionesPorHacer: Int,
    /** Series ya unidas cuyas fichas apuntan a animes distintos: hay que corregirlas. */
    val unidasConAnimesDistintos: Int,
    /** El catálogo no tiene series: sin él no hay nada que reagrupar. */
    val catalogoVacio: Boolean
) {
    /** Las uniones, una cifra por trozo (el resumen se separa por « · » para pintar una línea cada una). */
    fun conflictsText(): String = buildList {
        add("Uniones hechas: $seriesUnidas series con varias fichas ($fichasUnidas fichas)")
        add("Uniones por hacer (el mismo anime en series distintas; se hacen al reagrupar el catálogo): $unionesPorHacer")
        add("Series unidas con animes distintos (a corregir): $unidasConAnimesDistintos")
        if (catalogoVacio) add("El catálogo está vacío: recórrelo en Catálogo › Fuentes para que se hagan las uniones")
    }.joinToString(" · ")

    companion object {
        /** [dudosos]: fichas cuyo enlace no se parece a su anime; no unen series (como en [CatalogBuilder]), así que no cuentan como unión por hacer. */
        fun of(links: Collection<AnimeFuente>, series: Map<Long, List<Pair<String, String>>>, catalogoVacio: Boolean, dudosos: Set<Pair<String, String>> = emptySet()): AnilistStats {
            val rows = links.associateBy { it.fuente to it.slug }
            val animePerSeries = series.mapValues { (_, fichas) -> AnilistFicha.appliedIds(fichas) { f, s -> rows[f to s] } }.filterValues { it.isNotEmpty() }
            val joinable = series.mapValues { (_, fichas) -> AnilistFicha.appliedIds(fichas.filter { it !in dudosos }) { f, s -> rows[f to s] } }.filterValues { it.isNotEmpty() }
            val unidas = series.values.filter { it.size > 1 }
            val porHacer = joinable.entries.flatMap { (id, anime) -> anime.map { it to id } }.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
            return AnilistStats(
                fichas = series.values.sumOf { it.size },
                revisadas = series.values.sumOf { fichas -> fichas.count { it in rows } },
                seriesUnidas = unidas.size,
                fichasUnidas = unidas.sumOf { it.size },
                unionesPorHacer = porHacer.size,
                unidasConAnimesDistintos = animePerSeries.count { (id, anime) -> anime.size > 1 && (series[id]?.size ?: 0) > 1 },
                catalogoVacio = catalogoVacio
            )
        }
    }
}
