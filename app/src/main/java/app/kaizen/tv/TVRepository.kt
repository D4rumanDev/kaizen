package app.kaizen.tv

import androidx.paging.PagingData
import app.kaizen.pojos.av1.Series
import app.kaizen.pojos.av1.AiringSeries
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.source.AnimeSources
import app.kaizen.source.Catalog
import app.kaizen.source.SearchEngine
import app.kaizen.source.SearchQuery
import app.kaizen.source.SortBy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/** Fachada de la UI de TV sobre las fuentes activas: todas las listas pasan por [SearchEngine]. */
object TVRepository {

    /**
     * «Mejores en emisión»: primera página, en emisión y por puntuación, de las fuentes activas. Sale de lo guardado si tiene menos de [BestAiringCache.MAX_AGE]
     * (salvo [force], el botón «Actualizar»); si no hay red o las webs no dan nada, se enseña lo guardado aunque sea viejo.
     */
    suspend fun bestAiring(force: Boolean = false): List<SeriesSummary> {
        val sources = AnimeSources.enabled.map { it.id }
        val stored = BestAiringCache.load(sources)
        if (!force && stored != null && BestAiringCache.isFresh(stored.at, System.currentTimeMillis())) return stored.items
        // Tope de tiempo: una web colgada no debe dejar la sección en «cargando»; pasado el tope cuenta como lista incompleta (sale lo guardado, si lo hay).
        val (fresh, complete) = withTimeoutOrNull(LoadLimits.DATA) { SearchEngine.firstChecked(SearchQuery(airing = true, sort = SortBy.RATING)) } ?: (emptyList<SeriesSummary>() to false)
        // Una lista parcial (una web falló) o sin las notas de AniList todavía (catálogo sin listo) no se guarda una semana: se enseña, pero lo bueno guardado no se pisa.
        if (fresh.isEmpty() || !complete || !Catalog.isReady()) return stored?.items ?: fresh
        BestAiringCache.save(sources, fresh)
        return fresh
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
    suspend fun getAnime(url: String): Series? = Series.open(url)

    suspend fun calendarMap(): Map<Int, List<AiringSeries>> = AnimeSources.scheduleAll()
}
