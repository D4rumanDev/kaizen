package app.kaizen.source

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import app.kaizen.pojos.av1.SeriesSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import app.kaizen.commons.runCatchingCoop

/**
 * Motor de consultas: único punto por el que las pantallas piden listas a las fuentes.
 * Planifica (qué fuentes pueden responder), pide cada página a la caché común, aplica en local los filtros que la
 * fuente no resolvió y une las series iguales cuando hay varias fuentes activas. Las fuentes solo saben obtener datos.
 */
object SearchEngine {

    /**
     * Fuentes activas que pueden responder a [query]. Género y «en emisión» no se pueden reconstruir en local
     * (las tarjetas no traen esos datos), así que una fuente que no los resuelve queda fuera de la consulta.
     */
    fun sourcesFor(query: SearchQuery): List<AnimeSource> {
        val required = query.active.filter { it == Filter.GENRE || it == Filter.AIRING }
        val capable = AnimeSources.enabled.filter { it.pushes(query).containsAll(required) }
        // Texto: solo responden las fuentes con buscador propio; filtrar en local las páginas de las demás
        // pide muchas páginas para pocos resultados. Si ninguna activa lo tiene, se usan las demás (aproximado).
        val searching = if (Filter.TEXT in query.active) capable.filter { Filter.TEXT in it.pushes(query) } else capable
        return searching.ifEmpty { capable }
    }

    /** Aplica en local los filtros que [source] no resolvió: texto, nota mínima y años. */
    fun applyLocal(query: SearchQuery, source: AnimeSource, items: List<SeriesSummary>): List<SeriesSummary> {
        val pushed = source.pushes(query)
        return items.filter { item ->
            val year = item.year
            (Filter.TEXT in pushed || query.text.isBlank() || matchesText(item.name, query.text)) &&
                (Filter.SCORE in pushed || query.minScore == null || (item.score ?: -1.0) >= query.minScore) &&
                (Filter.YEAR in pushed || query.years == null || (year != null && year in query.years))
        }
    }

    /** Texto en el nombre sin distinguir mayúsculas, símbolos ni letras cirílicas que imitan a las latinas. */
    private fun matchesText(name: String, text: String): Boolean {
        return if (foldTitle(text).isBlank()) name.contains(text.trim(), ignoreCase = true) else nameMatches(name, text)
    }

    /**
     * Intercala las listas de las fuentes. Con varias fuentes, las series iguales pasan a una sola ficha;
     * con una sola no hay nada que unir y sus datos salen tal cual.
     */
    fun combine(perSource: List<List<SeriesSummary>>): List<SeriesSummary> {
        val mixed = interleave(perSource)
        return if (perSource.size > 1) mergeSeries(mixed) else mixed
    }

    /**
     * Resultados paginados. Si el catálogo interno puede responder (cubre las fuentes activas y no pide género
     * ni «en emisión»), salen de la base de datos; si no, de las fuentes: una página por fuente en cada carga.
     */
    fun paged(query: SearchQuery): Flow<PagingData<SeriesSummary>> = flow {
        emitAll(if (Catalog.canServe(query)) Catalog.paged(query) else pagedFromSources(query))
    }

    private fun pagedFromSources(query: SearchQuery): Flow<PagingData<SeriesSummary>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
            MultiSourcePagingSource(sourcesFor(query), query)
        }.flow

    /**
     * Como [first] pero con hasta [pages] páginas de cada fuente (siguiendo su cursor): más candidatas, para que lo sorteado entre ellas (recomendaciones) no sea siempre lo mismo.
     * Cada página pasa por [SourceCache], así que las ya vistas no vuelven a la red.
     */
    suspend fun firstPages(query: SearchQuery, pages: Int): List<SeriesSummary> = withContext(Dispatchers.IO) {
        val lists = coroutineScope {
            sourcesFor(query).map { source ->
                async {
                    runCatchingCoop {
                        val items = ArrayList<SeriesSummary>()
                        var cursor: String? = null
                        for (n in 1..pages.coerceAtLeast(1)) {
                            val page = SourceCache.page(source, query, cursor)
                            items += applyLocal(query, source, page.items)
                            cursor = page.next ?: break
                        }
                        items
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }
        val combined = combine(lists)
        if (query.sort == SortBy.RATING) rankByRating(combined) else combined
    }

    /** Primera página de cada fuente, ya unida y ordenada (filas de inicio como «Mejores en emisión»). */
    suspend fun first(query: SearchQuery): List<SeriesSummary> = firstChecked(query).first

    /** Como [first], y si todas las fuentes respondieron (false: alguna falló y la lista es parcial; no debe guardarse como buena). */
    suspend fun firstChecked(query: SearchQuery): Pair<List<SeriesSummary>, Boolean> = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            sourcesFor(query).map { source ->
                async {
                    runCatchingCoop { applyLocal(query, source, SourceCache.page(source, query, null).items) }
                }
            }.awaitAll()
        }
        val combined = combine(results.map { it.getOrDefault(emptyList()) })
        (if (query.sort == SortBy.RATING) rankByRating(combined) else combined) to results.all { it.isSuccess }
    }

    /**
     * Ordena por nota de más a menos. La nota es la de AniList que guarda el catálogo ([Catalog.ratingOf]); las series sin nota quedan al final.
     * Sin esto, con varias fuentes la lista saldría intercalada por el orden de cada una, no por nota.
     */
    suspend fun rankByRating(items: List<SeriesSummary>): List<SeriesSummary> {
        val scored = coroutineScope {
            items.map { item ->
                async {
                    if (item.score != null) item
                    else Catalog.ratingOf(item.source, item.slug)?.let { item.copy(score = it) } ?: item
                }
            }.awaitAll()
        }
        return scored.sortedByDescending { it.score ?: -1.0 }
    }
}
