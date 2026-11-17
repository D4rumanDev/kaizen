package app.kaizen.source

import app.kaizen.commons.RecentsArchive
import app.kaizen.commons.HostGate
import app.kaizen.commons.KaizenSettings
import app.kaizen.pojos.av1.Series
import app.kaizen.pojos.av1.AiringSeries
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.source.spec.SpecSources
import app.kaizen.commons.AppScope
import app.kaizen.commons.Network
import app.kaizen.commons.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.kaizen.commons.runCatchingCoop

/**
 * Kaizen: origen de datos de la app (catálogo, fichas, recientes y horario). Cada plataforma
 * (animeav1, jkanime...) implementa esta interfaz; la UI solo habla con ella.
 *
 * Los identificadores numéricos (aid, eid) son de animeav1 y se guardan en la base de datos.
 * Una segunda plataforma necesitará mapearlos (ver [AnimeSources]).
 */
interface AnimeSource {
    /** Identificador estable guardado en ajustes, p. ej. «animeav1». */
    val id: String

    /** Nombre visible, p. ej. «animeav1». */
    val name: String

    /** Dominio de la web, para reconocer sus URLs (p. ej. «animegratis.net»). */
    val host: String

    /** Texto corto del botón de la barra superior, p. ej. «AV1». */
    val badge: String

    /** Si la web y su CDN de portadas rechazan la firma de OkHttp: el cliente de imágenes les envía la de un navegador. */
    val needsBrowserUa: Boolean get() = false

    /** Capítulos recientes de la portada. */
    suspend fun recents(): List<RecentEpisode>

    /** Una página del catálogo; [cursor] null = primera página, y [CatalogPage.next] null = última. */
    suspend fun catalogPage(status: String, cursor: String?): CatalogPage

    /** Ficha completa con capítulos y relaciones, o null si no se puede obtener. */
    suspend fun anime(url: String): Series?

    /** Horario de emisión: día (1 = lunes ... 7 = domingo) → animes. */
    suspend fun schedule(): Map<Int, List<AiringSeries>>

    /** URL pública de la ficha. */
    fun animeUrl(slug: String): String

    /** URL pública de un capítulo; [number] ya formateado (sin decimales si es entero). */
    fun chapterUrl(slug: String, number: String): String

    /** Portada de un anime. */
    fun coverUrl(aid: Int, slug: String): String

    /** Nota de la ficha en estrellas (escala /5) y número de votos; null si la fuente no tiene valoraciones. */
    suspend fun rating(slug: String): Pair<Float, Int>? = null

    /** Si la nota se puede pedir sin bajar la ficha completa. Las tarjetas (recomendados) solo usan fuentes así. */
    val lightRating: Boolean get() = true

    /** Si sus listados traen la nota de cada serie. Si no, ordenar por nota exige que la fuente lo ordene ella (SORT_RATING). */
    val listsScores: Boolean get() = true

    /**
     * Número de series que anuncia el listado completo de la fuente (una sola petición), o null si no lo da.
     * Con él, el catálogo interno se salta el recorrido de cientos de páginas si el total no ha cambiado.
     */
    suspend fun catalogTotal(): Int? = null

    /** Años por los que recorrer además el catálogo, si la web recorta el listado completo (animeav1: 1000 series); null si no hace falta. */
    val catalogYears: IntRange? get() = null

    /**
     * Filtros de [query] que la propia fuente resuelve al pedir una página. El motor ([SearchEngine]) aplica en local
     * texto, nota y años cuando la fuente no los resuelve; género y «en emisión» los exige a la fuente.
     */
    fun pushes(query: SearchQuery): Set<Filter> = emptySet()

    /** Id de episodio que guarda la app; cada fuente lo calcula igual al listar, guardar y recordar. */
    fun episodeEid(slug: String, number: Double): Int {
        val key = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
        return stableId("$slug#$key")
    }

    /** Miniatura de un capítulo. */
    fun thumbnailUrl(aid: Int, number: String): String

    /** Servidores de un capítulo. Por defecto ninguno (animeav1 usa su propio camino). */
    suspend fun servers(episodeUrl: String): List<ServerLink> = emptyList()

    /** Una página de un género; [cursor] null = primera página. Por defecto la fuente no tiene géneros. */
    suspend fun genrePage(genreSlug: String, cursor: String?): CatalogPage = CatalogPage(emptyList(), null)

    /**
     * Una página de la consulta. Por defecto: género con [genrePage] y el resto con el listado de [catalogPage].
     * Las fuentes con buscador propio lo sobrescriben y declaran en [pushes] lo que resuelven.
     */
    suspend fun search(query: SearchQuery, cursor: String?): CatalogPage = when {
        query.genre != null -> genrePage(query.genre, cursor)
        else -> catalogPage("", cursor)
    }
}

/** Enlace a un servidor de vídeo de un capítulo. [label] es una etiqueta para el usuario (p. ej. el idioma) que la lista de servidores muestra; null si la fuente no etiqueta. */
data class ServerLink(val url: String, val label: String? = null)

/** Página de catálogo: fichas y cursor para la siguiente (null si no hay más). */
@androidx.annotation.Keep
data class CatalogPage(
    @com.google.gson.annotations.SerializedName("items") val items: List<SeriesSummary>,
    @com.google.gson.annotations.SerializedName("next") val next: String?
)

/** Tope de fichas por página en todas las listas (catálogo, género, búsqueda, novedades). Evita cargar páginas enteras en memoria. */
const val PAGE_SIZE = 25

/** Cursor «página:desplazamiento» → (página, desplazamiento). Sin cursor, primera página desde el principio. */
internal fun parseSiteCursor(cursor: String?): Pair<Int, Int> {
    val parts = cursor?.split(":").orEmpty()
    return (parts.getOrNull(0)?.toIntOrNull() ?: 1).coerceAtLeast(1) to (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceAtLeast(0)
}

/**
 * Tramo de [PAGE_SIZE] fichas de una página del sitio, desde [offset]. Si la página del sitio trae más
 * de [PAGE_SIZE], el cursor siguiente sigue dentro de ella; así no se pierde ninguna ficha.
 */
internal fun sitePageSlice(items: List<SeriesSummary>, page: Int, offset: Int): CatalogPage {
    val slice = items.drop(offset).take(PAGE_SIZE)
    // Página del sitio vacía = fin. Un desplazamiento fuera de rango pasa a la página siguiente (no corta la lista).
    val next = when {
        items.isEmpty() -> null
        offset + PAGE_SIZE < items.size -> "$page:${offset + PAGE_SIZE}"
        else -> "${page + 1}:0"
    }
    return CatalogPage(slice, next)
}

/** La portada que el usuario eligió para la serie de [slug] (titles.json), si hay una. Con la [source] se acierta aunque el slug se repita. */
fun userCover(slug: String, source: String? = null): String? = when (source) {
    null -> SeriesLedger.peekBySlug(slug)?.let { UserTitles.coverOfKey(it.toString()) }
    else -> UserTitles.coverOf(source, slug)
}

/**
 * Portadas candidatas de una serie: primero la de su fuente, luego la de las demás plataformas.
 * El id numérico (aid) puede no coincidir entre plataformas; si no existe, esa URL simplemente falla.
 */
fun coverCandidates(aid: Int, slug: String, preferred: String, sources: List<AnimeSource> = AnimeSources.enabled): List<String> {
    // Primero la elegida por el usuario y la mejor medida (BestCovers); después la preferida, que es la de la fuente del propio elemento (historial de una fuente desmarcada).
    // Una web que calcula la portada con el id (animeav1) solo vale con un id suyo: los ids de las demás fuentes (desde NEW_ID_BASE) son de otra cosa.
    val others = sources.filter { aid < AnimeIds.NEW_ID_BASE || it.coverUrl(0, slug) == it.coverUrl(1, slug) }.map { it.coverUrl(aid, slug) }
    return (listOfNotNull(userCover(slug), BestCovers.get(slug)) + listOf(preferred) + others).distinct()
}

/** Nombre legible a partir de un slug («bai-ri-cheng-wang» -> «Bai Ri Cheng Wang»): se usa cuando la web no da el nombre. */
fun titleFromSlug(slug: String): String =
    slug.split('-').filter { it.isNotEmpty() }.joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

/** Id numérico estable de un texto (slug, slug#número). Puede colisionar; ver AnimeIds. */
internal fun stableId(text: String): Int = text.hashCode() and 0x7fffffff

object AnimeSources {
    /** Todas las plataformas implementadas. */
    val all: List<AnimeSource> by lazy {
        // Las fuentes en código y, detrás, las descritas por fichero (assets/sources y la caché descargada, ver SpecSources).
        SpecSources.sources
    }

    /** Fuente por defecto (animegratis); si no cargó, la primera que haya. */
    private val default: AnimeSource get() = all.firstOrNull { it.id == "animegratis" } ?: all.firstOrNull() ?: EmptySource

    /** Fuente por id guardado en cada anime/episodio; animeav1 si el id no se conoce. */
    fun of(id: String?): AnimeSource = all.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == AnimeAv1.ID } ?: default

    /** Fuente según el dominio de una URL pública; animeav1 si no coincide ninguna. */
    fun forUrl(url: String): AnimeSource = all.firstOrNull { url.contains(it.host) } ?: of(null)

    /**
     * Ficha de [url]. Si su fuente no responde, la misma serie en las demás fuentes activas que la tengan
     * según el catálogo interno (salvo con [alternatives] falso: cada alternativa es otra petición).
     */
    suspend fun openAnime(url: String, alternatives: Boolean = true): Series? {
        // Una ficha vista hace poco se sirve de la copia sin pedir nada; si la copia ya tiene unos minutos, se renueva en segundo plano.
        // Salvo que «recientes» ya traiga un episodio posterior a los de la copia (se publicó después de guardarla): entonces se pide de nuevo.
        SourceCache.cachedFiche(url)?.takeUnless { cached ->
            RecentsArchive.hasNewerThan(cached.series.source, cached.series.slug, cached.series.chapters.maxOfOrNull { it.number } ?: 0.0)
        }?.let { cached ->
            // Una serie finalizada y completa no se renueva; una finalizada incompleta, como mucho una vez al día (ver FichaPolicy).
            if (cached.age >= cached.refreshAfter) refreshFiche(url)
            return cached.series
        }
        val primary = forUrl(url)
        runCatchingCoop { primary.anime(url) }.getOrNull()?.let { keepFiche(url, it); return it }
        if (!alternatives) return null
        for ((source, slug) in Catalog.alternatives(primary, url)) {
            runCatchingCoop { source.anime(source.animeUrl(slug)) }.getOrNull()?.let { keepFiche(url, it); return it }
        }
        return null
    }

    /**
     * Guarda la ficha descargada para [url] (la copia entera, y su nota y episodios para las tarjetas). Solo con red: sin ella la
     * ficha puede venir de la copia local de la fuente y no se debe sellar como recién descargada. (Con red y la web caída, una fuente
     * con `cacheFiche` también devuelve su copia local: se sirve como mucho [SourceCache] 60 min y se corrige en la siguiente renovación.)
     */
    private suspend fun keepFiche(url: String, series: Series) {
        if (!Network.isConnected) return
        SourceCache.saveFiche(url, series)
        SourceCache.noteFiche(series)
        // La ficha completa lo que el catálogo no trae (nota, votos, año), pero que falle no debe estropear la apertura.
        // Aparte: el catálogo puede estar escribiéndose y la ficha no debe esperar a su cerrojo.
        AppScope.launch { runCatchingCoop { CatalogBuilder.noteFicha(series) }.onFailure(::logError) }
    }

    private val refreshing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Vuelve a pedir la ficha de [url] sin que nadie espere, para que la siguiente apertura la tenga al día. Una renovación a la vez por URL. */
    private fun refreshFiche(url: String) {
        if (!Network.isConnected || !refreshing.add(url)) return
        // invokeOnCompletion también corre si el trabajo se cancela antes de empezar: la URL no queda marcada para siempre.
        AppScope.launch(Dispatchers.IO) {
            runCatchingCoop { forUrl(url).anime(url) }.getOrNull()?.let { keepFiche(url, it) }
        }.invokeOnCompletion { refreshing.remove(url) }
    }

    /** Fuentes marcadas en Configuración (casillas); siempre al menos una. */
    val selected: List<AnimeSource> get() = all.filter { it.id in KaizenSettings.sourceIds }.ifEmpty { listOf(default) }

    /**
     * Fuentes que se usan: las marcadas menos las que están en pausa por no responder (HostGate), como si no
     * estuvieran marcadas. Si están todas en pausa se devuelven igualmente (fallan al momento y la interfaz avisa).
     */
    val enabled: List<AnimeSource> get() = selected.let { picked -> picked.filterNot { HostGate.isDown(it.host) }.ifEmpty { picked } }

    /** Cambia al marcar o desmarcar fuentes y cuando una entra o sale de la pausa. */
    val enabledFlow: kotlinx.coroutines.flow.Flow<List<AnimeSource>> =
        kotlinx.coroutines.flow.combine(KaizenSettings.sourceIdsFlow, HostGate.down) { _, _ -> enabled }
            .distinctUntilChangedBy { list -> list.map { it.id } }

    /** Fuente principal de respaldo (ficha sin fuente conocida y acciones de una sola fuente). */
    val current: AnimeSource
        get() = default

    // Tiempo máximo por fuente: corto si la ficha ya tiene capítulos (solo faltan nota y relaciones).
    private const val OTHER_SOURCE_TIMEOUT_MS = 8_000L
    private const val OTHER_SOURCE_EXTRAS_TIMEOUT_MS = 3_000L

    /**
     * Ficha completa a partir de la de la fuente abierta y de la misma serie en las demás fuentes activas, pidiendo
     * cada ficha una sola vez y en paralelo (una fuente que falla solo deja de aportar):
     *  - capítulos: si la abierta no los tiene (serie terminada que su web no lista), los de la primera que sí;
     *  - nota: media de todas, ponderada por votos;
     *  - relaciones: las de todas, sin repetir serie (las de la ficha base primero).
     */
    suspend fun complete(anime: Series): Series {
        val timeout = if (anime.chapters.isEmpty()) OTHER_SOURCE_TIMEOUT_MS else OTHER_SOURCE_EXTRAS_TIMEOUT_MS
        val others = sameSeriesIn(enabled.filter { it.id != anime.source }, anime, timeout)
        val base = if (anime.chapters.isEmpty()) others.firstOrNull { it.chapters.isNotEmpty() } ?: anime else anime
        val all = listOf(anime) + others
        // Solo fichas con nota y votos: una fuente con votos pero sin nota no cuenta como 0 estrellas.
        val rating = weightedStars(all.filter { it.rateCount > 0 && it.rateStars > 0f }.map { toStars(it.rateStars.toDouble()) to it.rateCount })
        val relations = mergeRelations(base.slug, base.name, base.source, (listOf(base) + (all - base)).flatMap { it.relations }, AnimeAv1.ID)
        return base.copy(
            rateStars = rating?.first?.toFloat() ?: base.rateStars,
            rateCount = rating?.second ?: base.rateCount,
            relations = relations
        )
    }

    /** Ficha de la serie de [anime] en cada una de [sources] que la tiene según el catálogo; una fuente lenta no espera más de [timeoutMs]. */
    private suspend fun sameSeriesIn(sources: List<AnimeSource>, anime: Series, timeoutMs: Long): List<Series> = coroutineScope {
        sources.map { source ->
            async {
                withTimeoutOrNull(timeoutMs) {
                    runCatchingCoop {
                        Catalog.slugIn(source, anime.source, anime.slug)?.let { source.anime(source.animeUrl(it)) }
                    }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Nota para una tarjeta de [owner]/[slug]: la que ya guarda el catálogo (con o sin votos) o, si no, la media ponderada por votos de las fuentes activas que la tienen
     * (según el catálogo). Las fuentes que solo dan la nota con la ficha completa (animeav1) cuestan una petición
     * por serie: la puerta de [ratingGate] las limita a dos a la vez y la nota se guarda 15 días.
     */
    private val cardGate = kotlinx.coroutines.sync.Semaphore(3)

    suspend fun cardRating(owner: AnimeSource, slug: String): Double? = cardGate.withPermit {
        // Si el catálogo ya guarda la nota de la serie no se pide ninguna ficha.
        Catalog.ratingOf(owner.id, slug)?.let { return@withPermit it }
        val ratings = coroutineScope {
            enabled.map { source ->
                async {
                    val target = if (source.id == owner.id) slug else Catalog.slugIn(source, owner.id, slug)
                    target?.let { runCatchingCoop { SourceCache.ratedOrNull(source, it) }.getOrNull() }
                }
            }.awaitAll()
        }.filterNotNull()
        weightedStars(ratings.map { toStars(it.first.toDouble()) to it.second })?.first
    }

    /** Nota de una serie por slug: la del catálogo si la tiene (con o sin votos); si no, ponderada por votos entre las fuentes activas; null si no hay. */
    suspend fun ratingOf(slug: String): Double? {
        Catalog.ratingOfSlug(slug)?.let { return it }
        val ratings = coroutineScope {
            enabled.filter { it.lightRating }.map { source ->
                async {
                    runCatchingCoop { Catalog.slugAny(source, slug)?.let { SourceCache.ratedOrNull(source, it) } }.getOrNull()
                }
            }.awaitAll()
        }.filterNotNull()
        return weightedStars(ratings.map { toStars(it.first.toDouble()) to it.second })?.first
    }

    /**
     * Lo que dio cada fuente; las que fallan no aportan. Si fallan todas se lanza el primer error, para que quien
     * llama distinga «no se pudo cargar» de «no hay nada».
     */
    private fun <T> unlessAllFailed(results: List<Result<T>>): List<T> {
        results.firstOrNull()?.exceptionOrNull()?.takeIf { results.all { it.isFailure } }?.let { throw it }
        return results.mapNotNull { it.getOrNull() }
    }

    /** Recientes de todas las fuentes activas, intercaladas (cada fuente conserva su orden). */
    suspend fun recentsAll(force: Boolean = false): List<RecentEpisode> = withContext(Dispatchers.IO) {
        val lists = unlessAllFailed(coroutineScope { enabled.map { async { runCatchingCoop { SourceCache.recents(it, force) } } }.awaitAll() })
        // Cada fuente numera sus recientes desde 0: se reindexan para que la clave (PK) no se repita.
        interleave(lists).mapIndexed { index, recent -> recent.copy(key = index) }.also { recents ->
            // Las series que aparecen aquí y el catálogo aún no tiene entran ya, sin esperar a su siguiente recorrido.
            AppScope.launch { runCatchingCoop { CatalogBuilder.addFromRecents(recents) }.onFailure(::logError) }
        }
    }

    /** Horario de todas las fuentes activas: cada tarjeta lleva la fuente de la que viene. */
    suspend fun scheduleAll(): Map<Int, List<AiringSeries>> = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            enabled.map { source -> async { runCatchingCoop { source.id to SourceCache.schedule(source) } } }.awaitAll()
        }
        val maps = unlessAllFailed(results)
        val merged = mutableMapOf<Int, MutableList<AiringSeries>>()
        for ((sourceId, byDay) in maps) {
            for ((day, items) in byDay) {
                items.forEach { it.source = sourceId }
                merged.getOrPut(day) { mutableListOf() }.addAll(items)
            }
        }
        dedupeSchedule(merged) { Triple(it.slug, it.name, it.source) }
    }
}

/**
 * Una misma serie sale una vez en el horario aunque venga de varias fuentes o de varios días (cada fuente puede darle un día
 * distinto): se queda el día más temprano de la semana. Sigue [SeriesDedupe]: lo unido por el usuario cuenta como una sola serie,
 * y dos fichas de una misma fuente sin unir (una serie y su película) se conservan.
 */
internal fun <T> dedupeSchedule(
    days: Map<Int, List<T>>,
    dedupe: SeriesDedupe = SeriesDedupe(),
    /** Slug, nombre y fuente de una tarjeta del horario. */
    identity: (T) -> Triple<String, String, String>
): Map<Int, List<T>> =
    days.toSortedMap().mapValues { (_, items) -> items.filter { item -> identity(item).let { (slug, name, source) -> dedupe.isNew(slug, name, source) } } }

/**
 * Fecha de un episodio a partir de «8 oct» (la web no indica año): año actual, o el anterior si caería en el futuro.
 * 0 si el mes no se reconoce.
 */
internal fun siteDate(day: Int, monthAbbr: String, now: Long = System.currentTimeMillis()): Long {
    val months = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    val month = months.indexOf(monthAbbr.lowercase())
    if (month < 0) return 0L
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = now
    calendar.set(calendar.get(java.util.Calendar.YEAR), month, day, 12, 0, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    if (calendar.timeInMillis > now + 2L * 24 * 60 * 60 * 1000) calendar.add(java.util.Calendar.YEAR, -1)
    return calendar.timeInMillis
}

/**
 * Fecha relativa de jkanime: «Hoy», «Ayer», «hace N minutos/horas/días/semanas/meses». 0 si el texto no la tiene.
 * Minutos y horas son exactos; de un día en adelante se toma el mediodía de ese día.
 */
internal fun relativeDate(text: String, now: Long = System.currentTimeMillis()): Long {
    val lower = text.lowercase()
    fun amount(unit: String): Int? = Regex("""(\d+)\s+$unit""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
    // «Miércoles 07»: día de la semana y del mes (jkanime lo usa a partir de dos días).
    Regex("""(lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)\s+(\d{1,2})""").find(lower)?.groupValues?.get(2)?.toIntOrNull()?.let { day ->
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 12)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        if (day > calendar.get(java.util.Calendar.DAY_OF_MONTH)) calendar.add(java.util.Calendar.MONTH, -1) // es del mes anterior
        calendar.set(java.util.Calendar.DAY_OF_MONTH, day.coerceAtMost(calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)))
        return calendar.timeInMillis
    }
    amount("min[a-z]*")?.let { return now - it * 60_000L }
    amount("horas?")?.let { return now - it * 3_600_000L }
    val daysAgo = when {
        lower.contains("hoy") -> 0
        lower.contains("ayer") -> 1
        else -> amount("d[ií]as?") ?: amount("semanas?")?.let { it * 7 } ?: amount("mes(?:es)?")?.let { it * 30 } ?: return 0L
    }
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = now
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 12)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    calendar.add(java.util.Calendar.DAY_OF_MONTH, -daysAgo)
    return calendar.timeInMillis
}

/**
 * Slug sin sufijos de plataforma («-anime», «-tv»). Ya no identifica a ninguna serie (cada ficha se identifica por su slug
 * original y su id, ver [SeriesLedger]): sirve para leer las reglas de titles.json guardadas con el formato antiguo y para
 * que [MergeProposals] sugiera uniones, que decide el usuario.
 */
internal fun cleanSlug(slug: String): String = slug.removeSuffix("-anime").removeSuffix("-tv")

/** El slug de una ficha a partir de su URL: el último tramo de la ruta. */
internal fun slugOfUrl(url: String): String = url.trimEnd('/').substringAfterLast('/')

/** Nota en estrellas, máximo 5. Algunas fuentes publican sobre 10 (animeav1): se pasan a /5. */
internal fun toStars(raw: Double): Double = (if (raw > 5.0) raw / 2.0 else raw).coerceIn(0.0, 5.0)

/** Media de notas ponderada por votos, pares (estrellas, votos). Null si ninguna nota tiene votos. */
internal fun weightedStars(ratings: List<Pair<Double, Int>>): Pair<Double, Int>? {
    val votes = ratings.sumOf { it.second }
    if (votes == 0) return null
    return ratings.sumOf { it.first * it.second } / votes to votes
}

/** Nota ponderada de las fichas de una serie que tienen nota y votos. */
internal fun weightedStarsOf(group: List<SeriesSummary>): Pair<Double, Int>? =
    weightedStars(group.mapNotNull { item -> item.score?.takeIf { item.votes > 0 }?.let { it to item.votes } })

/**
 * Une las fichas de una misma serie (mismo slug). Si varias traen nota con votos, la nota es la media
 * ponderada por votos, igual que en la ficha. Mantiene el orden de la primera aparición.
 */
internal fun mergeSeries(items: List<SeriesSummary>): List<SeriesSummary> {
    // Misma serie: mismo slug, o el mismo título en otra fuente, y en los dos casos con años compatibles ([SeriesMatch]).
    val groups = ArrayList<MutableList<SeriesSummary>>()
    // Claves de serie ya calculadas (por ficha), para no recalcularlas en cada comparación.
    val keys = HashMap<SeriesSummary, String>()
    fun keyOfItem(item: SeriesSummary) = keys.getOrPut(item) { seriesKeyOf(item.source, item.slug) }
    for (item in items) {
        val title = normalizeTitle(item.name).takeIf { it.length >= 3 }
        // Las fichas unidas (por el catálogo o por el usuario) comparten id de serie aunque su título o su slug difieran.
        val key = keyOfItem(item)
        val group = groups.firstOrNull { members ->
            (key.startsWith("i:") && members.any { keyOfItem(it) == key }) ||
            members.any { other -> SeriesMatch.slugBase(other.slug) == SeriesMatch.slugBase(item.slug) && SeriesMatch.yearsFit(other.year, item.year) } ||
                (title != null && members.none { it.source == item.source } && members.any { other ->
                    SeriesMatch.sameSeries(normalizeTitle(other.name), other.year, other.source, title, item.year, item.source)
                })
        }
        if (group != null) group += item else groups += mutableListOf(item)
    }
    return groups.map { group ->
        val first = group.first()
        val weighted = weightedStarsOf(group)
        first.copy(
            score = weighted?.first ?: first.score,
            votes = weighted?.second ?: first.votes,
            year = group.mapNotNull { it.year }.minOrNull() // estreno: el más antiguo
        )
    }
}

/** Intercala varias listas: primero el elemento 0 de cada una, luego el 1, etc. */
internal fun <T> interleave(lists: List<List<T>>): List<T> {
    val out = mutableListOf<T>()
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (index in 0 until longest) {
        for (list in lists) list.getOrNull(index)?.let { out += it }
    }
    return out
}
