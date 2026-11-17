package app.kaizen.tv

import androidx.paging.PagingData
import app.kaizen.pojos.av1.Series
import app.kaizen.pojos.av1.AiringSeries
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.source.AnimeSources
import app.kaizen.source.SearchEngine
import app.kaizen.source.SearchQuery
import app.kaizen.source.SortBy
import kotlinx.coroutines.flow.Flow

/** Fachada de la UI de TV sobre las fuentes activas: todas las listas pasan por [SearchEngine]. */
object TVRepository {

    /** «Mejores en emisión»: primera página, en emisión y por puntuación, de las fuentes activas. */
    suspend fun bestAiring(): List<SeriesSummary> =
        SearchEngine.first(SearchQuery(airing = true, sort = SortBy.RATING))

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
