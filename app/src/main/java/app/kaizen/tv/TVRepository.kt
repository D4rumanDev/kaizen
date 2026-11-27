package app.kaizen.tv

import androidx.paging.PagingData
import app.kaizen.pojos.av1.Series
import app.kaizen.pojos.av1.AiringSeries
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.source.AnimeSources
import app.kaizen.source.BestTop
import app.kaizen.source.Catalog
import app.kaizen.source.SearchEngine
import app.kaizen.source.SearchQuery
import app.kaizen.source.SortBy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/** Fachada de la UI de TV sobre las fuentes activas: todas las listas pasan por [SearchEngine]. */
object TVRepository {

    /**
     * «Mejores» ([kind]: en emisión o de siempre): el top público de AniList resuelto a una serie por anime ([BestTop]). Guardado una semana por lista; si AniList no responde o tarda
     * más de [LoadLimits.DATA] se enseña lo guardado aunque sea viejo, y null si no hay nada que enseñar (la sección ofrece reintentar).
     */
    internal suspend fun best(kind: BestTop.Kind): List<SeriesSummary>? {
        val sources = AnimeSources.enabled.map { it.id }
        val key = "best-" + kind.name.lowercase()
        val stored = BestCache.load(sources, key)
        if (stored != null && BestCache.isFresh(stored.at, System.currentTimeMillis())) return stored.items
        val fresh = try {
            withTimeoutOrNull(LoadLimits.DATA) { BestTop.fromAnilist(kind, sources) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: app.kaizen.source.AnilistUnavailable) {
            null
        }
        if (fresh == null || fresh.cards.isEmpty()) return stored?.items
        // Un ranking cortado se enseña, pero lo bueno guardado no se pisa una semana con él.
        if (!fresh.complete) return stored?.items ?: fresh.cards
        BestCache.save(sources, fresh.cards, key)
        return fresh.cards
    }

    /** Lista paginada de cualquier consulta (directorio, «Mejores en general»). */
    fun paged(query: SearchQuery): Flow<PagingData<SeriesSummary>> = SearchEngine.paged(query)

    /** Búsqueda por texto y/o género, paginada. */
    fun searchQuery(text: String, genre: String? = null, sort: SortBy = SortBy.TITLE): Flow<PagingData<SeriesSummary>> =
        paged(SearchQuery(text = text, genre = genre, sort = sort))

    /**
     * Ficha al abrirla, con capítulos frescos: la web da la ficha y los capítulos en la misma petición,
     * así que no se guarda en caché. Las notas usan la caché común (SourceCache).
     */
    suspend fun getAnime(url: String): Series? = withTimeoutOrNull(LoadLimits.OPEN_SERIES) { Series.open(url) }

    suspend fun calendarMap(): Map<Int, List<AiringSeries>> = AnimeSources.scheduleAll()
}
