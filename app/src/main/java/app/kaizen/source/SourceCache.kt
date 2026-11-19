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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Capa única de caché de datos de todas las fuentes. Cada fuente obtiene sus datos como puede (API, HTML o JS),
 * pero el guardado, los plazos y la purga están aquí. La ficha completa se guarda 60 min (clave «fiche|versión|url», ver
 * [cachedFiche]); de ella además se guardan por separado la nota y el número de episodios ([noteFiche]).
 */
object SourceCache {
    // Plazos por tipo de dato (un solo sitio).
    private val PAGE_TTL: Duration get() = Timings.pages()           // listados, géneros, búsquedas
    private val SCHEDULE_TTL: Duration get() = Timings.schedule()    // horario (estrenos y cambios de día)
    /** Recientes: lo guardado caduca a la mitad del intervalo de renovación (como mucho 30 min), para que cada pasada pida datos nuevos. */
    private fun recentsTtl(): Duration = runCatching { KaizenSettings.recentsEveryMinutes.coerceIn(15, 180) / 2 }.getOrDefault(30).coerceAtMost(30).minutes
    private val EPISODES_TTL: Duration get() = Timings.episodes()    // episodios publicados: una serie en emisión suma uno cada semana

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
        ApiCache.obj<List<StoredRecent>>("recents|${source.id}", if (force) Duration.ZERO else recentsTtl(), recentsType) {
            source.recents().takeIf { it.isNotEmpty() }
                ?.map { StoredRecent(it.key, it.aid, it.eid, it.name, it.number, it.slug, it.source, it.released) }
        }.orEmpty().map { RecentEpisode(it.key, it.aid, it.eid, it.name, it.number, it.slug, it.source, it.released) }

    /** Horario de la fuente por días. */
    suspend fun schedule(source: AnimeSource): Map<Int, List<AiringSeries>> =
        ApiCache.obj("schedule|${source.id}", SCHEDULE_TTL, scheduleType) { source.schedule().takeIf { it.isNotEmpty() } }.orEmpty()

    /**
     * Episodios publicados de la ficha [slug] de [source]. Al abrir la ficha se guardan ([noteFiche]) y quien los pida luego no vuelve a pedir la página.
     */
    suspend fun episodes(source: AnimeSource, slug: String): Int? =
        ApiCache.obj("episodes|${source.id}|$slug", EPISODES_TTL, Int::class.javaObjectType) {
            source.anime(source.animeUrl(slug))?.chapters?.size?.takeIf { it > 0 }
        }

    /**
     * La ficha de [url] guardada hace menos de [FICHE_TTL], con su edad en ms; null si no hay. La clave lleva la versión de la app: una
     * copia hecha por otra versión podría no leerse bien (R8 renombra los campos que no llevan @SerializedName).
     */
    suspend fun cachedFiche(url: String): CachedFiche? {
        val (series, age) = ApiCache.cached<Series>("fiche|${BuildConfig.VERSION_CODE}|$url", Series::class.java)?.takeIf { it.second in 0L until (FichaPolicy.SETTLED_TTL * 3 / 2).inWholeMilliseconds } ?: return null
        // Una serie finalizada y completa se sirve de la copia durante semanas y no se renueva; el resto, como siempre.
        val on = KaizenSettings.anilistFicha
        val anilist = AnilistFicha.of(series.source, series.slug)
        return if (age < app.kaizen.commons.Timings.spread(FichaPolicy.ttl(series, anilist, on), url).inWholeMilliseconds) CachedFiche(series, age, FichaPolicy.refreshAfter(series, anilist, on)) else null
    }

    /** Una ficha guardada, su edad en ms y la edad a partir de la cual conviene renovarla en segundo plano ([FichaPolicy.refreshAfter]). */
    class CachedFiche(val series: Series, val age: Long, val refreshAfter: Duration)

    /** La ficha ya completada con las demás fuentes (relaciones unidas), guardada solo para series finalizadas y completas. */
    suspend fun completeFiche(url: String): Series? =
        ApiCache.cached<Series>("fiche|${BuildConfig.VERSION_CODE}|completa|$url", Series::class.java)?.takeIf { it.second in 0L until FichaPolicy.COMPLETE_TTL.inWholeMilliseconds }?.first

    suspend fun saveCompleteFiche(url: String, series: Series) {
        runCatchingCoop { ApiCache.put("fiche|${BuildConfig.VERSION_CODE}|completa|$url", series) }.onFailure(::logError)
    }

    /** Guarda la ficha de [url] recién descargada. Guardar de más nunca debe impedir abrirla. */
    suspend fun saveFiche(url: String, series: Series) {
        runCatchingCoop { ApiCache.put("fiche|${BuildConfig.VERSION_CODE}|$url", series) }.onFailure(::logError)
    }

    /** Guarda los episodios de una ficha ya descargada para que no se pidan otra vez por separado. */
    suspend fun noteFiche(series: Series) {
        // Guardar de más nunca debe impedir abrir la ficha.
        runCatchingCoop {
            series.chapters.size.takeIf { it > 0 }?.let { ApiCache.put("episodes|${series.source}|${series.slug}", it) }
        }.onFailure(::logError)
    }
}
