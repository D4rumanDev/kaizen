package app.kaizen.source

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.kaizen.pojos.av1.SeriesSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resultados de una consulta en varias fuentes, paginados. La clave guarda el cursor de cada fuente
 * («id=cursor;id=cursor»): vacío = empezar, [DONE] = agotada. Cada carga pide una página a cada fuente activa
 * (siempre por la caché común), aplica los filtros locales y las intercala, así las fuentes avanzan a la vez.
 */
class MultiSourcePagingSource(
    private val sources: List<AnimeSource>,
    private val query: SearchQuery
) : PagingSource<String, SeriesSummary>() {

    override fun getRefreshKey(state: PagingState<String, SeriesSummary>): String? = null

    // Series ya emitidas en esta lista (una instancia dura lo que la lista). Solo con varias fuentes.
    private val shownSeries = SeriesDedupe()

    override suspend fun load(params: LoadParams<String>): LoadResult<String, SeriesSummary> = try {
        val cursors = decode(params.key)
        val active = sources.filter { cursors[it.id] != DONE }
        val fetched = withContext(Dispatchers.IO) {
            coroutineScope {
                active.map { source ->
                    async { source.id to runCatchingCoop { SourceCache.page(source, query, cursors[source.id].orEmpty().ifEmpty { null }) }.onFailure(::logError).getOrNull() }
                }.awaitAll()
            }
        }.toMap()
        // Todas las fuentes pedidas fallaron: es un error (con «reintentar»), no una lista vacía.
        if (active.isNotEmpty() && active.all { fetched[it.id] == null }) {
            throw java.io.IOException("Ninguna fuente respondió")
        }
        val next = sources.associate { source ->
            source.id to when {
                cursors[source.id] == DONE -> DONE
                // Fallo de red: se conserva el cursor para reintentar la misma página; agotada = DONE.
                else -> fetched[source.id]?.let { it.next ?: DONE } ?: cursors[source.id].orEmpty()
            }
        }
        val perSource = active.map { source -> SearchEngine.applyLocal(query, source, fetched[source.id]?.items.orEmpty()) }
        val mixed = SearchEngine.combine(perSource)
        // Varias fuentes ordenadas por nota: cada página se ordena por nota, no se intercala.
        val combined = if (query.sort == SortBy.RATING && sources.size > 1) SearchEngine.rankByRating(mixed) else mixed
        // Con varias fuentes, una serie ya mostrada en una página anterior de esta lista no vuelve a salir.
        val items = if (sources.size > 1) {
            combined.filter { item -> synchronized(shownSeries) { shownSeries.isNew(item.slug, item.name, item.source, year = item.year) } }
        } else combined
        LoadResult.Page(items, null, if (next.values.any { it != DONE }) encode(next) else null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logError(e)
        LoadResult.Error(e)
    }

    private fun decode(key: String?): Map<String, String> =
        key?.split(";")?.mapNotNull { part -> part.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap().orEmpty()

    private fun encode(cursors: Map<String, String>): String = cursors.entries.joinToString(";") { "${it.key}=${it.value}" }

    private companion object {
        const val DONE = "~"
    }
}
