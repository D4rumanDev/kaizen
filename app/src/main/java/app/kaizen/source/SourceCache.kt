package app.kaizen.source

import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import app.kaizen.pojos.av1.AiringSeries
import app.kaizen.BuildConfig
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.Timings
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.Series
import java.lang.reflect.Type

/** Nota guardada. [votes] = 0 si la fuente no tiene valoraciones: así no se vuelve a pedir. */
@androidx.annotation.Keep
data class RatingValue(
    @SerializedName("stars") val stars: Float,
    @SerializedName("votes") val votes: Int
)

/**
 * Capa única de caché de datos de todas las fuentes. Cada fuente obtiene sus datos como puede (API, HTML o JS),
 * pero el guardado, los plazos y la purga están aquí. La ficha completa se guarda 60 min (clave «fiche|versión|url», ver
 * [cachedFiche]); de ella además se guardan por separado la nota y el número de episodios ([noteFiche]).
 */
object SourceCache {
    private const val MINUTE = 60_000L
    private const val DAY = ApiCache.DAY

    // Plazos por tipo de dato (un solo sitio).
    private val PAGE_TTL: Long get() = Timings.pagesMs()           // listados, géneros, búsquedas
    private val SCHEDULE_TTL: Long get() = Timings.scheduleMs()    // horario (estrenos y cambios de día)
    /** Recientes: lo guardado caduca a la mitad del intervalo de renovación (como mucho 30 min), para que cada pasada pida datos nuevos. */
    private fun recentsTtl(): Long = runCatching { KaizenSettings.recentsEveryMinutes.coerceIn(15, 180) / 2 }.getOrDefault(30).coerceAtMost(30) * MINUTE
    private val RATING_TTL: Long get() = Timings.ratingMs()        // notas
    private val EPISODES_TTL: Long get() = Timings.episodesMs()    // episodios publicados: una serie en emisión suma uno cada semana
    internal const val MAX_VOTES = 10_000_000      // más votos que esto en una ficha no es creíble

    // RecentEpisode lleva objetos que apuntan a sí mismo (state): se guarda una copia plana de sus datos.
    // @Keep y nombres fijos: el JSON guardado debe sobrevivir a R8 y a las actualizaciones.
    @androidx.annotation.Keep
    private data class StoredRecent(
        @SerializedName("key") val key: Int,
        @SerializedName("aid") val aid: Int,
        @SerializedName("eid") val eid: Int,
        @SerializedName("name") val name: String,
        @SerializedName("number") val number: Double,
        @SerializedName("slug") val slug: String,
        @SerializedName("source") val source: String,
        @SerializedName("released") val released: Long
    )

    private val recentsType: Type = object : TypeToken<List<StoredRecent>>() {}.type
    private val scheduleType: Type = object : TypeToken<Map<Int, List<AiringSeries>>>() {}.type

    /** Página de una consulta en una fuente. La clave incluye la consulta y el cursor. */
    suspend fun page(source: AnimeSource, query: SearchQuery, cursor: String?): CatalogPage =
        ApiCache.obj("page2|${source.id}|${query.cacheKey}|${cursor.orEmpty()}", PAGE_TTL, CatalogPage::class.java) {
            // Una página vacía no se guarda: suele ser un fallo de red, no que la lista esté vacía.
            source.search(query, cursor).takeIf { it.items.isNotEmpty() }
        } ?: CatalogPage(emptyList(), null)

    /** Recientes de la fuente; con [force] (el botón «Actualizar») se piden aunque la copia sea reciente. */
    suspend fun recents(source: AnimeSource, force: Boolean = false): List<RecentEpisode> =
        ApiCache.obj<List<StoredRecent>>("recents|${source.id}", if (force) 0L else recentsTtl(), recentsType) {
            source.recents().takeIf { it.isNotEmpty() }
                ?.map { StoredRecent(it.key, it.aid, it.eid, it.name, it.number, it.slug, it.source, it.released) }
        }.orEmpty().map { RecentEpisode(it.key, it.aid, it.eid, it.name, it.number, it.slug, it.source, it.released) }

    /** Horario de la fuente por días. */
    suspend fun schedule(source: AnimeSource): Map<Int, List<AiringSeries>> =
        ApiCache.obj("schedule|${source.id}", SCHEDULE_TTL, scheduleType) { source.schedule().takeIf { it.isNotEmpty() } }.orEmpty()

    /** Nota de una serie en una fuente (con votos 0 si no tiene). Errores de red se propagan. */
    suspend fun rating(source: AnimeSource, slug: String): RatingValue =
        ApiCache.obj("rating|${source.id}|$slug", RATING_TTL, RatingValue::class.java) {
            val rated = source.rating(slug)
            RatingValue(rated?.first ?: 0f, rated?.second ?: 0)
        } ?: RatingValue(0f, 0)

    /**
     * Episodios publicados de la ficha [slug] de [source]. La ficha trae a la vez la nota y los episodios: al pedirla se guardan
     * las dos cosas ([noteFiche]), y quien pida luego una de ellas no vuelve a pedir la página.
     */
    suspend fun episodes(source: AnimeSource, slug: String): Int? =
        ApiCache.obj("episodes|${source.id}|$slug", EPISODES_TTL, Int::class.javaObjectType) {
            source.anime(source.animeUrl(slug))?.also { noteRating(it) }?.chapters?.size?.takeIf { it > 0 }
        }

    /**
     * La ficha de [url] guardada hace menos de [FICHE_TTL], con su edad en ms; null si no hay. La clave lleva la versión de la app: una
     * copia hecha por otra versión podría no leerse bien (R8 renombra los campos que no llevan @SerializedName).
     */
    suspend fun cachedFiche(url: String): CachedFiche? {
        val (series, age) = ApiCache.cached<Series>("fiche|${BuildConfig.VERSION_CODE}|$url", Series::class.java)?.takeIf { it.second in 0L until FichaPolicy.SETTLED_TTL * 3 / 2 } ?: return null
        // Una serie finalizada y completa se sirve de la copia durante semanas y no se renueva; el resto, como siempre.
        val on = KaizenSettings.anilistFicha
        val anilist = AnilistFicha.of(series.source, series.slug)
        return if (age < app.kaizen.commons.Timings.spread(FichaPolicy.ttl(series, anilist, on), url)) CachedFiche(series, age, FichaPolicy.refreshAfter(series, anilist, on)) else null
    }

    /** Una ficha guardada, su edad en ms y la edad a partir de la cual conviene renovarla en segundo plano ([FichaPolicy.refreshAfter]). */
    class CachedFiche(val series: Series, val age: Long, val refreshAfter: Long)

    /** La ficha ya completada con las demás fuentes (relaciones unidas), guardada solo para series finalizadas y completas. */
    suspend fun completeFiche(url: String): Series? =
        ApiCache.cached<Series>("fiche|${BuildConfig.VERSION_CODE}|completa|$url", Series::class.java)?.takeIf { it.second in 0L until FichaPolicy.COMPLETE_TTL }?.first

    suspend fun saveCompleteFiche(url: String, series: Series) {
        runCatchingCoop { ApiCache.put("fiche|${BuildConfig.VERSION_CODE}|completa|$url", series) }.onFailure(::logError)
    }

    /** Guarda la ficha de [url] recién descargada. Guardar de más nunca debe impedir abrirla. */
    suspend fun saveFiche(url: String, series: Series) {
        runCatchingCoop { ApiCache.put("fiche|${BuildConfig.VERSION_CODE}|$url", series) }.onFailure(::logError)
    }

    /** Guarda los datos de una ficha ya descargada (nota y episodios) para que no se pidan otra vez por separado. */
    suspend fun noteFiche(series: Series) {
        // Guardar de más nunca debe impedir abrir la ficha.
        runCatchingCoop {
            noteRating(series)
            series.chapters.size.takeIf { it > 0 }?.let { ApiCache.put("episodes|${series.source}|${series.slug}", it) }
        }.onFailure(::logError)
    }

    // Sin votos no se guarda: no pisa una nota anterior ni marca «sin nota» una serie que solo aún no la trae la ficha. Los datos
    // vienen de una web y quedan 15 días: votos absurdos o una nota que no es un número tampoco se guardan. Un fallo al guardar no
    // debe estropear lo que pidió quien llama (los episodios).
    private suspend fun noteRating(series: Series) {
        runCatchingCoop {
            val stars = toStars(series.rateStars.toDouble())
            if (series.rateCount in 1..MAX_VOTES && stars.isFinite()) {
                ApiCache.put("rating|${series.source}|${series.slug}", RatingValue(stars.toFloat(), series.rateCount))
            }
        }.onFailure(::logError)
    }

    /** Nota con votos, o null si la fuente no tiene valoraciones (animeav1 da 0 hasta que una serie nueva reúne votos). */
    suspend fun ratedOrNull(source: AnimeSource, slug: String): Pair<Float, Int>? =
        rating(source, slug).takeIf { it.votes > 0 && it.stars > 0f }?.let { it.stars to it.votes }
}
