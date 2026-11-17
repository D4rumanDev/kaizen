package app.kaizen.source

import app.kaizen.pojos.av1.AiringSeries
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.Series

/**
 * Identidad de animeav1, la fuente de la que vienen los ids numéricos que guarda la app (aid, eid). Su scraping está en
 * `assets/sources/animeav1.json` ([ConfigurableSource] con `format: jsData`); aquí solo quedan las constantes que usan otras partes de la app (ids por
 * defecto de las tablas, portadas de las recomendaciones del catálogo de animeav1).
 */
object AnimeAv1 {
    const val ID = "animeav1"
    private const val BASE = "https://animeav1.com"

    fun animeUrl(slug: String) = "$BASE/media/$slug"

    fun coverUrl(aid: Int) = "https://cdn.animeav1.com/covers/$aid.jpg"
}

/** Fuente vacía: último recurso si ninguna fuente por fichero cargó, para que la app arranque sin datos en vez de caerse. */
internal object EmptySource : AnimeSource {
    override val id = "ninguna"
    override val name = "sin fuentes"
    override val host = "invalid"
    override val badge = "--"
    override suspend fun recents(): List<RecentEpisode> = emptyList()
    override suspend fun catalogPage(status: String, cursor: String?) = CatalogPage(emptyList(), null)
    override suspend fun anime(url: String): Series? = null
    override suspend fun schedule(): Map<Int, List<AiringSeries>> = emptyMap()
    override fun animeUrl(slug: String) = AnimeAv1.animeUrl(slug)
    override fun chapterUrl(slug: String, number: String) = "${AnimeAv1.animeUrl(slug)}/$number"
    override fun coverUrl(aid: Int, slug: String) = AnimeAv1.coverUrl(aid)
    override fun thumbnailUrl(aid: Int, number: String) = ""
}
