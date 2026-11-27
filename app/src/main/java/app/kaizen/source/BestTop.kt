package app.kaizen.source

import app.kaizen.commons.DebugLog
import app.kaizen.pojos.av1.SeriesSummary
import kotlinx.coroutines.flow.first

/**
 * «Mejores» («de siempre» y «en emisión») desde el ranking público de AniList: cada anime del top se resuelve a **una** serie del libro y sale una vez, en el orden de nota de AniList.
 * La elección es pura ([resolve]) para probarla sin red ni base de datos; [build] la alimenta con AniList y los enlaces aplicados.
 */
internal object BestTop {
    /**
     * Las dos listas: cuántos animes se piden y la popularidad mínima que se exige. «De siempre» es la medida del 27-nov-2026 sobre la copia de la TV; «en emisión» pide solo los que
     * AniList da como emitiéndose y baja la popularidad porque los estrenos recientes tienen menos votantes (suposición tomada, a revisar con lo que salga en la TV).
     */
    enum class Kind(val count: Int, val minPopularity: Int, val airing: Boolean) { ALWAYS(300, 20_000, false), AIRING(100, 10_000, true) }

    /** El anime [anime] resuelto a la ficha ([source], [slug]) de la serie [seriesId]. */
    class Pick(val anime: AnilistTop, val seriesId: Long, val source: String, val slug: String)

    /**
     * Por cada anime de [top], en orden: de las fichas de [linksOf] en fuentes activas ([enabled], en su orden) se agrupan por serie ([seriesOf], 0 = sin serie, no cuenta); gana la serie con
     * más fichas enlazadas a ese anime y, en empate, la de id más bajo. Una serie ya usada por un anime anterior no se repite, y un anime sin ficha utilizable no sale.
     * Dentro de la serie ganadora se abre la ficha de la primera fuente activa (la base antes que «-latino», y luego por slug).
     */
    fun resolve(top: List<AnilistTop>, linksOf: Map<Long, List<Pair<String, String>>>, seriesOf: (Pair<String, String>) -> Long, enabled: List<String>): List<Pick> {
        val used = HashSet<Long>()
        return top.mapNotNull { anime ->
            val usable = linksOf[anime.id].orEmpty().filter { it.first in enabled }.distinct().groupBy(seriesOf).filterKeys { it != 0L }
            val series = usable.entries.filter { it.key !in used }.sortedWith(compareByDescending<Map.Entry<Long, List<Pair<String, String>>>> { it.value.size }.thenBy { it.key }).firstOrNull() ?: return@mapNotNull null
            val ficha = series.value.minWith(compareBy<Pair<String, String>>({ enabled.indexOf(it.first) }, { it.second.endsWith("-latino") }, { it.second }))
            used += series.key
            Pick(anime, series.key, ficha.first, ficha.second)
        }
    }

    /** Las tarjetas y si el ranking llegó entero: uno cortado (una página sin datos) se enseña pero no se guarda una semana. */
    class Built(val cards: List<SeriesSummary>, val complete: Boolean)

    /** Pide el top a AniList, lo resuelve con los enlaces aplicados y devuelve las tarjetas; anota la medición. Lanza [AnilistUnavailable] si AniList no responde. */
    suspend fun build(kind: Kind, api: AnilistApi, links: AnimeFuenteDao, enabled: List<String>, seriesOf: (Pair<String, String>) -> Long = { (source, slug) -> seriesIdOf(source, slug) }, aidOf: suspend (source: String, slug: String) -> Int): Built {
        val tops = api.top(kind.count, kind.minPopularity, kind.airing)
        val top = tops.items
        val linksOf = links.all().filter { it.estado == AnimeFuente.APLICADO && it.anilistId != null }.groupBy({ it.anilistId!! }, { it.fuente to it.slug })
        val picks = resolve(top, linksOf, seriesOf, enabled)
        DebugLog.info("Medición", "Mejores ${kind.name.lowercase()}: ${top.size} animes recibidos de ${kind.count}, ${picks.size} resueltos, ${top.size - picks.size} sin serie con enlace aplicado en una fuente activa")
        val cards = picks.map { pick ->
            SeriesSummary(aidOf(pick.source, pick.slug), pick.anime.title, pick.slug, category = 1, source = pick.source, score = pick.anime.score?.let { it / 10.0 }, votes = 0, year = pick.anime.year)
        }
        return Built(cards, complete = tops.complete)
    }

    /** [build] con AniList de verdad, los enlaces del usuario y los aids del catálogo; espera a que el libro de series esté cargado (sin él ninguna ficha tiene serie). */
    suspend fun fromAnilist(kind: Kind, enabled: List<String>): Built {
        SeriesLedger.version.first { it > 0 }
        return build(kind, AnilistClient(AnilistHttp::post), app.kaizen.database.UserDB.INSTANCE.animeFuenteDao(), enabled, aidOf = Catalog::aidOf)
    }
}
