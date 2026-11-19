package app.kaizen.home

import app.kaizen.source.AnimeAv1
import app.kaizen.commons.runCatchingCoop
import app.kaizen.domain.Taste
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.source.AnimeSources
import app.kaizen.source.Catalog
import app.kaizen.source.SearchEngine
import app.kaizen.source.SearchQuery
import app.kaizen.source.SortBy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import app.kaizen.domain.AlreadyHad
import app.kaizen.domain.Had
import app.kaizen.domain.NotRecommended
import app.kaizen.domain.SeriesStatuses
import kotlin.time.Duration.Companion.seconds

/**
 * Recomendados a tu medida: primero las precuelas y secuelas de tus series que aún no has visto y después series de
 * géneros que ves (favoritos e historial) con nota de 3 a 5 estrellas, sin las que ya tienes ni las marcadas como «No recomendar». Los géneros y las series se sortean según su encaje, así que cada vez que abres la app sale una tanda
 * distinta. Sin gusto conocido (nada visto ni en favoritos) devuelve vacío y la sección usa la lista general.
 */
object Recommendations {
    /** Un género lento no retrasa toda la sección: pasado este tiempo se sigue sin él. */
    private val GENRE_TIMEOUT = 25.seconds

    /** Series que enseña la sección «Recomendados». */
    const val SECTION_SIZE = 15

    /** Páginas de cada fuente que se piden por género: con más candidatas el sorteo no se queda en las mismas de siempre. */
    private const val GENRE_PAGES = 3

    /** Con menos de estas, lo personalizado se completa con la lista general. */
    private const val MIN_PERSONAL = 10

    /**
     * La sección entera: lo personalizado y, si no llena (poca información o fuentes sin respuesta), la lista general del
     * staff hasta completarla. La lista general se pide a la vez, no después.
     */
    suspend fun forHome(): List<Any> = coroutineScope {
        val today = java.time.LocalDate.now().toEpochDay()
        savedToday(today)?.let { return@coroutineScope it }
        val staff = async { runCatchingCoop { staff() }.getOrDefault(emptyList()) }
        // Sin lo enseñado estos días: así la tanda de mañana no es la de hoy. Si con eso no llegan las suficientes, se vuelve a sortear sin evitar nada.
        val recent = DailyRecommendations.recentTitles()
        val fresh = personalized(SECTION_SIZE, recent)
        val personal = if (fresh.size >= MIN_PERSONAL || recent.isEmpty()) fresh else personalized(SECTION_SIZE)
        // Una tanda personal completa se guarda y se enseña igual todo el día; la que se completa con la lista general no (es un parche mientras no hay gusto conocido).
        if (personal.size >= MIN_PERSONAL) personal.also { DailyRecommendations.save(today, it) } else personal + staff.await().take(SECTION_SIZE - personal.size)
    }

    /**
     * La tanda de hoy ya guardada, sin lo que desde entonces ha dejado de valer: lo conocido (favoritos, historial), lo marcado «No recomendar», lo que tiene estado (también por el id de su serie,
     * que une fichas con otro título) y lo de una fuente que ya no está activa. Es el mismo conjunto de exclusión del sorteo ([exclusions]). Null si queda corta y toca rehacerla.
     */
    private suspend fun savedToday(day: Long): List<SeriesSummary>? {
        val saved = DailyRecommendations.savedFor(day) ?: return null
        val excluded = exclusions(TasteRepository.snapshot().known)
        val enabled = AnimeSources.enabled.map { it.id }.toSet()
        return saved.filter { it.source in enabled && !NotRecommended.isExcluded(excluded, it.name, it.source, it.slug) }.takeIf { it.size >= MIN_PERSONAL }
    }

    /** Lo que no debe salir en Recomendados: lo [known], lo de «No recomendar» y las series con estado (por título y por el id de su serie). */
    private fun exclusions(known: Set<String>): Set<String> {
        val statusSeries = SeriesStatuses.all.value.values.mapNotNull { entry -> app.kaizen.source.seriesIdOf(entry.source, entry.slug).takeIf { it != 0L }?.let { "#$it" } }
        return known + NotRecommended.titles + SeriesStatuses.all.value.keys + statusSeries
    }

    /** Sugerencias del staff, solo las series que tiene alguna fuente activa (son de animeav1); si no, saldrían sin portada. */
    private suspend fun staff(): List<Any> = coroutineScope {
        // Sin las favoritas ni las que ya se han visto enteras (se consulta a la vez que la lista).
        val hadAsync = async { runCatchingCoop { AlreadyHad.current() }.getOrDefault(Had.NONE) }
        val list = StaffRecommendations.createList(60)
        val had = hadAsync.await()
        list.filter { recommended ->
            NotRecommended.allows(recommended.name, AnimeAv1.ID, recommended.slug) && !had.has(recommended.name, recommended.slug, AnimeAv1.ID) && AnimeSources.enabled.firstNotNullOfOrNull { source ->
                Catalog.slugAny(source, recommended.slug)?.let { source to it }
            }?.also { recommended.resolved = it } != null
        }
    }

    suspend fun personalized(count: Int = SECTION_SIZE, avoid: Set<String> = emptySet()): List<SeriesSummary> {
        val snapshot = TasteRepository.snapshot()
        val random = Random.Default
        val top = Taste.pickGenres(snapshot.profile, Taste.GENRES_PER_BATCH, random)
        if (top.isEmpty()) return emptyList()
        val byGenre = coroutineScope {
            top.map { genre ->
                async {
                    // Sin minScore en la consulta: las series sin nota de AniList (sin enlace o con la ficha sin descargar) quedarían fuera.
                    // Con SortBy.RATING el catálogo las da por nota de AniList (las que no la tienen, al final) y Taste.rank descarta las de menos de 3.
                    val query = SearchQuery(genre = genre.slug, sort = SortBy.RATING)
                    genre.slug to (runCatchingCoop { withTimeoutOrNull(GENRE_TIMEOUT) { SearchEngine.firstPages(query, GENRE_PAGES) } }.getOrNull() ?: emptyList())
                }
            }.awaitAll().toMap()
        }
        // [avoid]: lo enseñado los días anteriores (títulos normalizados), tanto en las del género como en las secuelas.
        val recommended = Taste.rank(byGenre, snapshot.profile, exclusions(snapshot.known) + avoid, count, random)
        // Las secuelas también se descartan si ya tienen estado (vistas, pendientes, descartadas), como el resto de la sección (sin lo conocido: son series que el usuario ya tiene en parte).
        return Taste.withSequels(Taste.withoutExcluded(snapshot.sequels, exclusions(emptySet()) + avoid), recommended, count, random)
    }
}
