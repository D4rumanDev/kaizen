package app.kaizen.source

import app.kaizen.commons.DebugLog
import app.kaizen.commons.JsExtractor
import app.kaizen.commons.Network
import app.kaizen.commons.decodeEmbedIframeUrl
import app.kaizen.commons.decodeEmbedUrl
import app.kaizen.commons.runCatchingCoop
import app.kaizen.commons.logError
import app.kaizen.database.CacheDB
import app.kaizen.database.CatalogDB
import app.kaizen.pojos.av1.SeriesKind
import app.kaizen.pojos.av1.AiringSeries
import app.kaizen.pojos.av1.ChapterEntry
import app.kaizen.pojos.av1.Genre
import app.kaizen.pojos.av1.RELATION_PREQUEL
import app.kaizen.pojos.av1.RELATION_SEQUEL
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.Relation
import app.kaizen.pojos.av1.Series
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.search.SearchGenres
import app.kaizen.source.SourceHttp.document
import app.kaizen.source.spec.HtmlNode
import app.kaizen.source.spec.JsonNode
import app.kaizen.source.spec.Node
import app.kaizen.source.spec.SourceSpec
import app.kaizen.source.spec.TextMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.TimeZone
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/**
 * Fuente por scraping descrita por un fichero ([SourceSpec]): el motor es de la app y los selectores, las plantillas de URL
 * y las expresiones regulares vienen del JSON, que puede actualizarse sin recompilar (ver [app.kaizen.source.spec.SpecSources]).
 *
 * Un solo motor para todas las fuentes; el fichero decide con opciones cómo se pide y se lee cada cosa: `format` (páginas HTML
 * con selectores CSS, o `jsData`: JSON dentro del JavaScript de la página, que se evalúa con [JsExtractor] y se lee con rutas
 * de campo), API de episodios con método, token, cookies y paginación (`chapters.ajax`), API o lista JSON del directorio, etc.
 * HTML y JSON se leen con el mismo lenguaje de campos ([app.kaizen.source.spec.Pick] sobre un [Node]).
 * Los extractores de cada servidor de vídeo también son código de la app: el fichero solo dice dónde está la URL del embed.
 *
 * Si la web no permite calcular la URL de un capítulo a partir del número (`urls.chapter` ausente), se guarda la real al
 * leer la ficha o la portada ([chapterLinks]) y, si falta, se lee la ficha una vez para aprenderla.
 */
class ConfigurableSource(private val spec: SourceSpec) : AnimeSource {
    override val id = spec.id
    override val name = SourceNames.display(spec.id, spec.name)
    override val badge = spec.badge
    override val host = spec.host

    // Su ficha pide la nota solo al abrirla cuando la página es pesada (animeav1).

    override val needsBrowserUa: Boolean get() = spec.browserUa

    private val base = spec.base

    // Las portadas «débiles» (p. ej. el banner de la portada de la web) solo valen mientras no haya otra guardada.
    // [coverReject]: imágenes de relleno de la web (su logo cuando la serie no tiene portada); no valen y se usa la de otra fuente.
    private val covers = CoverStore("kaizen_${spec.id}_covers", fallback = ::fallbackCover) { slug, url, hasStored ->
        spec.coverReject?.containsMatchIn(url) != true &&
            (spec.coverPrefixes.any { url.startsWith(it) } ||
                spec.coverRegex?.containsMatchIn(url) == true ||
                (!hasStored && spec.coverWeakPrefixes.any { url.startsWith(it) }))
    }

    private fun fallbackCover(slug: String) = spec.coverFallback.replace("{slug}", slug)

    // Las webs que calculan las imágenes a partir del id (`images`) no necesitan recordar la portada real.
    override fun coverUrl(aid: Int, slug: String): String =
        spec.images?.let { fill(it.cover, "aid" to "$aid", "slug" to slug) } ?: covers.url(slug)

    override fun thumbnailUrl(aid: Int, number: String): String =
        spec.images?.let { fill(it.thumbnail, "aid" to "$aid", "number" to number) } ?: ""

    /** URL real de cada capítulo («slug#número» → URL). Reutiliza el almacén de portadas, que es un mapa persistente de textos. */
    private val chapterLinks = CoverStore("kaizen_${spec.id}_chapters", fallback = { "" }) { _, url, _ -> url.startsWith("$base/") }

    // Series cuya ficha ya se pidió para completar enlaces: una sola petición por serie, no una por capítulo que falta.
    private val unresolved = ConcurrentHashMap.newKeySet<String>()

    /** Episodios por una API de la web (con POST, token y cookies si el fichero lo pide); null si la ficha trae las tarjetas. */
    private val ajax = spec.anime.chapters.ajax?.let { AjaxEpisodes(spec.id, base, it) }

    /** Algunas fuentes guardaron los ids con el número decimal («slug#1.0»): se mantiene para no perder favoritos e historial. */
    override fun episodeEid(slug: String, number: Double): Int =
        if (spec.eidDouble) stableId("$slug#$number") else super.episodeEid(slug, number)

    override fun animeUrl(slug: String) = fill(spec.urls.anime, "slug" to slug)

    override fun chapterUrl(slug: String, number: String): String {
        spec.urls.chapter?.let { return fill(it, "slug" to slug, "number" to number) }
        chapterLinks.stored(linkKey(slug, number))?.let { return it }
        if (slug !in unresolved) {
            // Solo se da por intentada si la web respondió: un fallo de red se reintenta en la siguiente llamada.
            document(animeUrl(slug))?.let {
                unresolved.add(slug)
                learnChapters(slug, parseChapters(HtmlNode(it)))
            }
        }
        return chapterLinks.stored(linkKey(slug, number)) ?: animeUrl(slug)
    }

    private fun fill(template: String, vararg values: Pair<String, String>): String = fillTemplate(base, template, *values)

    /** Enlaces relativos de la web (`/anime/x`) como absolutos. */
    private fun absolute(url: String) = when {
        url.startsWith("./") -> "$base/" + url.removePrefix("./")
        url.startsWith("/") -> base + url
        else -> url
    }

    /** Si [url] es de esta web: https en su dominio o en un subdominio suyo (la web puede mandar `dominio.net` a `www.dominio.net`). */
    private fun onSite(url: String): Boolean {
        if (url.startsWith("$base/")) return true
        if (!url.startsWith("https://")) return false
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return host == spec.host || host.endsWith(".${spec.host}")
    }

    private fun linkKey(slug: String, number: String) = "$slug#$number"

    private fun numberText(number: Double) = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

    private fun learnChapters(slug: String, chapters: List<Chapter>) {
        if (spec.urls.chapter != null) return
        chapterLinks.rememberAll(chapters.map { linkKey(slug, numberText(it.number)) to it.url })
    }

    override suspend fun recents(): List<RecentEpisode> = withContext(Dispatchers.IO) {
        val recents = spec.recents ?: return@withContext emptyList()
        val page = fetchNode(fill(recents.url)) ?: throw noResponse(fill(recents.url))
        val episodes = parseRecents(page).take(recents.limit)
        episodes.forEach { learnChapters(it.slug, listOf(Chapter(it.number, it.url))) }
        covers.rememberAll(episodes.map { it.slug to it.cover })
        // La portada solo da el día; la hora exacta sale de la lista de episodios de cada serie (si la web la da).
        val exact = ajax?.exactDates(episodes.map { it.slug to it.number }, ::openAjaxPage).orEmpty()
        val dates = recentDates()
        episodes.mapIndexed { index, episode ->
            RecentEpisode(
                key = index,
                aid = episode.aid ?: AnimeIds.forSlug(episode.slug, id),
                eid = episode.eid ?: episodeEid(episode.slug, episode.number),
                name = episode.name,
                number = episode.number,
                slug = episode.slug,
                source = id,
                released = dates[episode.url] ?: exact[episode.slug to episode.number] ?: episode.released
            )
        }
    }

    /** URL de capítulo → fecha de publicación (ms) según la API que indique el fichero; vacío si no hay o falla. */
    private fun recentDates(): Map<String, Long> = runCatchingCoop {
        val dates = spec.recents?.dates ?: return@runCatchingCoop emptyMap()
        val body = SourceHttp.text(fill(dates.url)) ?: return@runCatchingCoop emptyMap()
        parseRecentDates(JSONArray(body))
    }.getOrDefault(emptyMap())

    // Texto solo por la búsqueda; género y año a su listado; «en emisión» a su listado o filtrando con el estado de cada ficha.
    override fun pushes(query: SearchQuery): Set<Filter> {
        val caps = spec.search
        val years = query.years
        val oneYear = years != null && years.first == years.last
        // El texto va solo a la búsqueda de la web, que no admite más filtros (o solo el año, según el fichero).
        val textOnly = (caps.text == TextMode.ALONE || caps.text == TextMode.ALONE_WITH_YEAR) && query.text.isNotBlank() &&
            query.genre == null && !query.airing && (years == null || caps.text == TextMode.ALONE_WITH_YEAR)
        if (textOnly) return setOf(Filter.TEXT)
        return buildSet {
            if (caps.text == TextMode.ALWAYS) add(Filter.TEXT)
            addAll(caps.filters.filter { it != Filter.YEAR })
            if (Filter.YEAR in caps.filters && oneYear) add(Filter.YEAR)
        }
    }

    /** El año del filtro si la web lo admite y es uno solo. */
    private fun singleYear(query: SearchQuery): Int? =
        query.years?.takeIf { spec.urls.yearParam != null && it.first == it.last }?.first

    override val catalogYears: IntRange?
        get() = spec.yearSlicesFrom?.takeIf { spec.urls.yearParam != null }?.let { it..(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1) }

    override suspend fun catalogPage(status: String, cursor: String?): CatalogPage =
        search(SearchQuery(airing = status.contains("emision")), cursor)

    override suspend fun genrePage(genreSlug: String, cursor: String?): CatalogPage = search(SearchQuery(genre = genreSlug), cursor)

    override suspend fun search(query: SearchQuery, cursor: String?): CatalogPage = withContext(Dispatchers.IO) {
        apiPage(query, cursor) ?: htmlSearch(query, cursor)
    }

    /** La página por la API de la web; null si no hay API, si pide un género que solo conoce el HTML o si exige verificación y se recorre el directorio HTML. */
    private fun apiPage(query: SearchQuery, cursor: String?): CatalogPage? {
        val api = spec.api ?: return null
        // La API pide el nombre del género, no el slug: sin nombre conocido se recorre su página HTML.
        val htmlGenre = query.genre != null && spec.urls.genreFirst != null && apiGenreName(query.genre) == null
        if (htmlGenre) return null
        return try {
            apiSearch(api, query, cursor)
        } catch (e: org.jsoup.HttpStatusException) {
            // La API puede pedir una verificación de seguridad (403, ocurre con animegratis) mientras el directorio HTML sigue abierto: sin
            // texto que buscar se recorre ese. El cursor de la API (solo la página) lo entiende igual el del sitio.
            if (e.statusCode != 403 || query.text.isNotBlank()) throw e
            DebugLog.info("Catálogo", "${spec.name}: la API pide verificación (403); se recorre el directorio HTML")
            null
        }
    }

    private suspend fun htmlSearch(query: SearchQuery, cursor: String?): CatalogPage {
        val (page, offset) = parseSiteCursor(cursor)
        val byText = isTextSearch(query)
        // Una búsqueda sin segunda plantilla devuelve todo en una sola página.
        if (byText && spec.urls.searchNext == null && page > 1) return CatalogPage(emptyList(), null)
        // «En emisión» con género o año, si la web tiene un listado de emisión aparte: se cruza con él.
        val live = if (query.airing && !byText && spec.urls.airing != null && (query.genre != null || query.years != null)) airingSlugs() else null
        val airingOnly = query.airing && spec.airingFilter && !byText
        var current = page
        var skip = offset
        // El filtro deja páginas vacías: se sigue con la siguiente (con tope).
        repeat(if (airingOnly || live != null) SKIP_PAGES else 1) {
            val listing = pageListing(query, current, byText) ?: return CatalogPage(emptyList(), null)
            val visible = listing.cards.filter { (!airingOnly || it.airing) && (live == null || it.slug in live) }.map { it.toSummary() }
            val last = listing.cards.isEmpty() || current >= listing.lastPage
            if (visible.isNotEmpty() || last) return closeSlice(sitePageSlice(visible, current, skip), current, last)
            current += 1
            skip = 0
        }
        return CatalogPage(emptyList(), "$current:0")
    }

    /** El listado de la página [current]; null si es el final (la web de datos en JavaScript no distingue «sin más páginas» de un fallo: pasada la primera, lo es). */
    private suspend fun pageListing(query: SearchQuery, current: Int, byText: Boolean): Listing? = try {
        listing(listUrl(query, current), json = !byText && spec.jsonList != null)
    } catch (e: IOException) {
        if (spec.format == "jsData" && current > 1) null else throw e
    }

    /** Sin más páginas del sitio, no hay cursor aunque el tramo esté lleno. */
    private fun closeSlice(slice: CatalogPage, current: Int, last: Boolean): CatalogPage =
        if (last && slice.next?.startsWith("${current + 1}:") == true) CatalogPage(slice.items, null) else slice

    private fun isTextSearch(query: SearchQuery) = query.text.isNotBlank() && spec.urls.searchFirst != null && Filter.TEXT in pushes(query)

    private fun listUrl(query: SearchQuery, page: Int): String {
        val urls = spec.urls
        val first = page <= 1
        val vars = arrayOf("page" to "$page")
        val searchFirst = urls.searchFirst
        val airing = urls.airing
        return when {
            searchFirst != null && isTextSearch(query) -> fill(if (first) searchFirst else urls.searchNext ?: searchFirst, *vars, "query" to enc(query.text.trim()))
            airing != null && query.airing && query.genre == null && query.years == null -> fill(airing, *vars)
            else -> browseUrl(query, first, vars)
        }
    }

    /** El listado general, con el género (si la web tiene página por género) y los filtros que recibe como parámetros añadidos a la URL. */
    private fun browseUrl(query: SearchQuery, first: Boolean, vars: Array<Pair<String, String>>): String {
        val urls = spec.urls
        val genre = query.genre
        val genreFirst = urls.genreFirst
        val byGenreUrl = genre != null && genreFirst != null
        val list = when {
            genre != null && genreFirst != null -> fill(if (first) genreFirst else urls.genreNext ?: genreFirst, *vars, "genre" to enc(genre))
            else -> fill(if (first) urls.listFirst else urls.listNext, *vars)
        }
        val extras = buildList {
            if (!byGenreUrl) genre?.let { g -> urls.genreParam?.let { add(fill(it, "genre" to enc(g))) } }
            if (query.airing && urls.airing == null) urls.airingParam?.let { add(it) }
            singleYear(query)?.let { year -> urls.yearParam?.let { add(fill(it, "year" to "$year")) } }
            (if (query.sort == SortBy.RATING) urls.sortRatingParam else urls.sortTitleParam)?.let { add(it) }
        }
        return if (extras.isEmpty()) list else list + (if ('?' in list) "&" else "?") + extras.joinToString("&")
    }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    private fun apiGenreName(slug: String): String? = SearchGenres.genres.firstOrNull { it.slug == slug }?.name

    /** Una página de la API del directorio: texto, género, estado, año, nota mínima y orden los resuelve el servidor. */
    private fun apiSearch(api: app.kaizen.source.spec.ApiSpec, query: SearchQuery, cursor: String?): CatalogPage {
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val names = api.params
        val sort = api.sortValues[if (query.sort == SortBy.RATING) "rating" else "title"].orEmpty()
        val params = buildList {
            add("${names.getValue("page")}=$page")
            add("${names.getValue("limit")}=${api.limit}")
            add("${names.getValue("sort")}=$sort")
            if (query.text.isNotBlank()) names["text"]?.let { add("$it=${enc(query.text.trim())}") }
            query.genre?.let { slug -> names["genre"]?.let { name -> apiGenreName(slug)?.let { add("$name=${enc(it)}") } } }
            if (query.airing) api.airing?.let { add("${it.first}=${enc(it.second)}") }
            query.minScore?.let { min -> names["minScore"]?.let { add("$it=${String.format(Locale.US, "%.1f", min * api.scoreScale)}") } }
            query.years?.takeIf { it.first == it.last }?.let { year -> names["year"]?.let { add("$it=${year.first}") } }
        }
        val (items, totalPages) = parseApiPage(JSONObject(SourceHttp.body(fill(api.url) + "?" + params.joinToString("&"))), page)
        return CatalogPage(items, if (page < totalPages && items.isNotEmpty()) (page + 1).toString() else null)
    }

    /** Series de la respuesta de la API y número de páginas; recuerda la portada de cada una. */
    internal fun parseApiPage(json: JSONObject, page: Int): Pair<List<SeriesSummary>, Int> {
        val api = spec.api ?: return emptyList<SeriesSummary>() to page
        val fields = api.fields
        val animes = json.optJSONArray(api.list) ?: return emptyList<SeriesSummary>() to page
        val items = List(animes.length()) { animes.getJSONObject(it) }.mapNotNull { anime ->
            val slug = anime.optString(fields.slug)
            if (!SLUG.matches(slug)) return@mapNotNull null
            fields.cover?.let { covers.remember(slug, anime.optString(it)) }
            SeriesSummary(
                aid = AnimeIds.forSlug(slug, id),
                name = anime.optString(fields.name).ifBlank { titleFromSlug(slug) },
                slug = slug,
                category = 1,
                source = id,
                // La nota que da la web se descarta: solo vale la de AniList (ver [AnilistScores]).
                score = null,
                votes = 0,
                year = fields.year?.let { anime.optString(it) }?.toDoubleOrNull()?.toInt()
            )
        }
        return items to json.optInt(api.totalPages, page)
    }

    /** Notas (estrellas, votos) de las series que lista [url] en la API; vacío si falla: es un dato extra. */
    private fun apiScores(url: String): Map<String, Pair<Double, Int>> = runCatchingCoop {
        parseApiPage(JSONObject(SourceHttp.body(url)), 1).first
            .mapNotNull { item -> item.score?.let { item.slug to (it to item.votes) } }.toMap()
    }.getOrDefault(emptyMap())

    private class Listing(val cards: List<Card>, val lastPage: Int)

    private val listings = object : LinkedHashMap<String, Pair<Long, Listing>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Listing>>) = size > LISTING_CACHE
    }

    /**
     * Fichas y última página del listado de [url] (en caché unos minutos: una página del sitio da varias de la app);
     * un fallo de red se propaga (si no, parecería el final del catálogo).
     */
    private suspend fun listing(url: String, json: Boolean = false): Listing {
        synchronized(listings) { listings[url] }?.takeIf { System.currentTimeMillis() - it.first < LISTING_TTL.inWholeMilliseconds }?.let { return it.second }
        val cards: List<Card>
        val lastPage: Int
        if (json) {
            cards = parseJsonList(SourceHttp.text(url) ?: throw noResponse(url))
            lastPage = Int.MAX_VALUE
        } else {
            val page = fetchNode(url) ?: throw noResponse(url)
            cards = parseCards(page)
            // Sin paginación declarada, el final es una página vacía.
            lastPage = if (spec.lastPage == null) Int.MAX_VALUE else parseLastPage(page)
        }
        covers.rememberAll(cards.map { it.slug to it.cover })
        return Listing(cards, lastPage).also { if (cards.isNotEmpty()) synchronized(listings) { listings[url] = System.currentTimeMillis() to it } }
    }

    /** La página como [Node]: HTML con Jsoup, o (`format: jsData`) el JSON que lleva dentro su JavaScript; null si falla. */
    /** «La web no respondió», con el motivo real (si se conoce) como causa: así el gestor de errores lo clasifica en vez de dejarlo en «Otro». */
    private fun noResponse(url: String): IOException =
        IOException("${spec.name} no respondió (${requestedPath(url)})", SourceHttp.failureOf(url))

    /**
     * Qué se pedía, para el registro: la ruta, sin servidor ni parámetros. Si la web busca con el texto en la ruta (`/buscar/{query}`), de una búsqueda solo se deja
     * lo que va antes del texto: lo que escribió el usuario no entra en el registro.
     */
    private fun requestedPath(url: String): String {
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty().ifEmpty { "/" }
        val marker = "@@Q@@"
        val searchPrefixes = listOfNotNull(spec.urls.searchFirst, spec.urls.searchNext)
            .filter { it.substringBefore('?').contains("{query}") }
            .mapNotNull { runCatching { java.net.URI(fill(it, "query" to marker, "page" to "1").substringBefore(marker)).path }.getOrNull() }
        val prefix = searchPrefixes.firstOrNull { it.isNotEmpty() && path.startsWith(it) } ?: return path
        return "$prefix…"
    }

    private suspend fun fetchNode(url: String): Node? =
        if (spec.format == "jsData") JsExtractor.processLink(url)?.let { JsonNode(it) } else document(url)?.let(::HtmlNode)

    @Volatile
    private var airingCache: Pair<Long, Set<String>>? = null

    /** Slugs de todas las series del listado de emisión (varias páginas); se guardan unos minutos. Un fallo no guarda un conjunto a medias. */
    private fun airingSlugs(): Set<String> {
        airingCache?.takeIf { System.currentTimeMillis() - it.first < LISTING_TTL.inWholeMilliseconds }?.let { return it.second }
        val template = spec.urls.airing ?: return emptySet()
        val slugs = LinkedHashSet<String>()
        for (page in 1..spec.airingPages) {
            val doc = document(fill(template, "page" to "$page")) ?: throw noResponse(fill(template, "page" to "$page"))
            val cards = parseCards(HtmlNode(doc))
            if (cards.isEmpty()) break
            cards.mapTo(slugs) { it.slug }
        }
        airingCache = System.currentTimeMillis() to slugs
        return slugs
    }

    /** Total: de una cabecera, o de la última página del directorio (las anteriores están llenas). */
    override suspend fun catalogTotal(): Int? = withContext(Dispatchers.IO) {
        val total = spec.total ?: return@withContext null
        if (total.header != null) return@withContext SourceHttp.get(fill(total.url))?.header(total.header)?.toIntOrNull()?.takeIf { it > 0 }
        if (total.listVar) return@withContext SourceHttp.text(fill(total.url))?.let(::jsonListObject)
            ?.optInt(spec.jsonList?.total ?: "total", -1)?.takeIf { it > 0 }
        if (total.json != null) return@withContext SourceHttp.text(fill(total.url))?.let { runCatchingCoop { JSONObject(it).optInt(total.json, -1) }.getOrNull() }?.takeIf { it > 0 }
        val lastUrl = total.lastUrl ?: return@withContext null
        val first = document(fill(total.url)) ?: return@withContext null
        val lastPage = parseLastPage(HtmlNode(first))
        val last = if (lastPage <= 1) first else document(fill(lastUrl, "page" to "$lastPage")) ?: return@withContext null
        val count = parseCards(HtmlNode(last)).size
        ((lastPage - 1) * total.perPage + count).takeIf { count > 0 }
    }

    /** Ficha con todos los capítulos (por grupo en orden; un número repetido en otro grupo se omite). */
    /** La página de una ficha ya leída ([Page]) o el resultado final sin leerla ([Done]: null, o la copia guardada). */
    private sealed interface FichePage {
        class Page(val node: Node, val context: AjaxEpisodes.Context? = null, val htmlPage: Pair<SourceHttp.Reply, Document>? = null) : FichePage
        class Done(val series: Series?) : FichePage
    }

    override suspend fun anime(url: String): Series? = withContext(Dispatchers.IO) {
        val slug = slugOf(url) ?: return@withContext null
        // Con `cacheFiche`, sin conexión o si la web falla, la ficha guardada de la última vez.
        val cached = if (spec.cacheFiche) CacheDB.INSTANCE.sourceFichaDAO().findBySlug(slug) else null
        if (spec.cacheFiche && !Network.isConnected) return@withContext cached
        val loaded = when {
            ajax != null -> ajaxPage(slug)
            spec.anime.chapters.html != null -> htmlChaptersPage(slug, cached)
            else -> plainPage(slug, cached)
        }
        val page = when (loaded) {
            is FichePage.Done -> return@withContext loaded.series
            is FichePage.Page -> loaded
        }
        val node = page.node
        // Una URL inexistente puede devolver la portada con un 200: sin el elemento obligatorio de la ficha no es una serie.
        if (spec.anime.require?.let { node.first(it) == null } == true) return@withContext null
        // Una sola lectura de la página: el registro único de la ficha y, derivada de él, la serie que usa la app. Sin título no es una ficha: se sale antes de pedir nada más.
        val (ficha, genres) = recordOf(slug, node) ?: return@withContext null
        val chapters = chaptersOf(slug, node, page.context, page.htmlPage)
        learnChapters(slug, chapters)
        val aid = spec.anime.id?.int(node) ?: AnimeIds.internalIdFor(CacheDB.INSTANCE.animeIdentityDao(), slug, id)
        withCover(ficha, slug, aid)
        val series = seriesOf(ficha, genres, slug, aid, node, chapters)
        FichaData(ficha, id, slug).portada?.let { covers.remember(slug, it) }
        if (spec.cacheFiche) CacheDB.INSTANCE.sourceFichaDAO().add(series)
        saveWebFicha(slug, ficha)
        series
    }

    /** Ficha con los capítulos por AJAX: con el id en la ficha (idRegex) sin él no hay serie; con la URL en un atributo solo falta la lista de capítulos. */
    private fun ajaxPage(slug: String): FichePage {
        val page = SourceHttp.get(animeUrl(slug)) ?: return FichePage.Done(null)
        // body() antes que parse(): jsoup consume el cuerpo al parsear y body() después falla.
        val body = page.body()
        val doc = Jsoup.parse(body, animeUrl(slug))
        val context = openAjax(slug, body, doc, page.cookies())
        if (context == null && spec.anime.chapters.ajax?.idRegex != null) return FichePage.Done(null)
        return FichePage.Page(HtmlNode(doc), context)
    }

    /** Ficha con los capítulos por petición POST: hace falta la dirección final de la página (la web puede redirigir de dominio). */
    private fun htmlChaptersPage(slug: String, cached: Series?): FichePage {
        val page = SourceHttp.get(animeUrl(slug)) ?: return FichePage.Done(cached)
        val doc = page.parse()
        return FichePage.Page(HtmlNode(doc), htmlPage = page to doc)
    }

    private suspend fun plainPage(slug: String, cached: Series?): FichePage {
        val page = fetchNode(animeUrl(slug)) ?: return FichePage.Done(cached)
        // En JSON la respuesta es una lista con la serie como primer elemento.
        val node = if (spec.format == "jsData") page.first(".") else page
        return node?.let { FichePage.Page(it) } ?: FichePage.Done(cached)
    }

    /**
     * Guarda el registro único de la ficha ([WebFichaSchema], en `catalog-db`). Es una copia aparte: un fallo al guardarla se anota y la ficha se abre igualmente.
     */
    private fun saveWebFicha(slug: String, ficha: JSONObject) {
        runCatchingCoop { CatalogDB.INSTANCE.webFichaDao().put(WebFicha.of(id, slug, ficha, System.currentTimeMillis())); AnilistCards.forget(id, slug) }.onFailure { logError(it) }
    }

    /** El registro de la ficha y sus géneros (con slug), leídos de la página con el bloque `ficha` de la fuente; null si la página no trae título. */
    private fun recordOf(slug: String, node: Node): Pair<JSONObject, List<Genre>>? {
        val genres = genresOf(node)
        return WebFichaSchema.build(spec.ficha, node, genres.map { it.name }, id, slug, animeUrl(slug))?.let { it to genres }
    }

    /** Las fuentes que calculan la portada con una plantilla (no está en la página) la dan aquí, para que el registro la lleve. */
    private fun withCover(ficha: JSONObject, slug: String, aid: Int) {
        if (ficha.getJSONObject("coverImage").isNull("extraLarge") && spec.images != null) ficha.getJSONObject("coverImage").put("extraLarge", coverUrl(aid, slug))
    }

    /** La serie que usa la app y el registro de la ficha, leídos de la página; null si la página no trae título. */
    internal fun readFicha(slug: String, aid: Int, node: Node, chapters: List<Chapter>): Pair<Series, JSONObject>? {
        val (ficha, genres) = recordOf(slug, node) ?: return null
        withCover(ficha, slug, aid)
        return seriesOf(ficha, genres, slug, aid, node, chapters) to ficha
    }

    /** Ficha a partir de lo leído de la página (sin red salvo los géneros de las fuentes que los piden aparte). */
    internal fun parseSeries(slug: String, aid: Int, node: Node, chapters: List<Chapter>): Series? = readFicha(slug, aid, node, chapters)?.first

    /** La serie de la app, derivada del registro único: no vuelve a leer la página para nada que ya esté en él. */
    private fun seriesOf(ficha: JSONObject, genres: List<Genre>, slug: String, aid: Int, node: Node, chapters: List<Chapter>): Series {
        val data = FichaData(ficha, id, slug)
        return Series(
            aid = aid,
            name = data.romaji ?: "",
            slug = slug,
            description = data.descripcion ?: "",
            // La nota y los votos de la web se descartan: solo vale la nota de AniList (ver [AnilistScores]).
            rateStars = 0f,
            rateCount = 0,
            type = kindOf(data.formato),
            state = if (data.estado == "RELEASING") 1 else 2,
            day = spec.anime.day?.first(node)?.let(::dayOfWeek),
            genres = genres,
            relations = parseRelations(node),
            chapters = chapters.map { ChapterEntry(it.eid ?: episodeEid(slug, it.number), it.number) },
            source = id,
            year = data.anio,
            studio = data.estudios.firstOrNull()
        )
    }

    /** El tipo de la app (`SeriesKind`) a partir del formato de AniList; sin formato, serie de TV como siempre. */
    private fun kindOf(format: String?): String = when (format) {
        "MOVIE" -> SeriesKind.MOVIE.slug
        "OVA" -> SeriesKind.OVA.slug
        "SPECIAL", "MUSIC" -> SeriesKind.SPECIAL.slug
        else -> SeriesKind.ANIME.slug
    }

    /** Día de la semana (1 = lunes) de una fecha ISO con o sin hora y con la zona como +HH o +HH:mm; null si no se entiende. */
    internal fun dayOfWeek(text: String): Int? =
        runCatching { OffsetDateTime.parse(text.replace(" ", "T"), ISO_WITH_OFFSET).dayOfWeek.value }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(10), DateTimeFormatter.ISO_LOCAL_DATE).dayOfWeek.value }.getOrNull()

    /** Capítulos de la ficha: de su lista JSON, de sus tarjetas o, si el fichero lo indica, de una API o de un total (un fallo deja la lista vacía y la ficha se abre igualmente). */
    private fun chaptersOf(slug: String, node: Node, context: AjaxEpisodes.Context?, htmlPage: Pair<SourceHttp.Reply, Document>? = null): List<Chapter> {
        if (ajax != null) return context?.let { ajax.numbers(it) }.orEmpty().map { Chapter(it, chapterUrl(slug, numberText(it))) }
        spec.anime.chapters.html?.let { html ->
            // El total sale de la respuesta; si falla o viene vacía se usa el total de la ficha (`count`), si lo hay.
            val last = htmlPage?.let { (page, doc) -> postForm(html.request, page, doc) }
                ?.let { body -> html.number.findAll(body).mapNotNull { it.groupValues.getOrNull(1)?.toDoubleOrNull() }.filter { it in 1.0..MAX_HTML_EPISODES }.maxOrNull() }?.toInt() ?: 0
            if (last > 0) return (1..last).map { Chapter(it.toDouble(), chapterUrl(slug, "$it")) }
        }
        spec.anime.chapters.items?.let { items -> return parseChapterItems(slug, node, items) }
        spec.anime.chapters.count?.let { count ->
            val total = count.first(node)?.toIntOrNull() ?: 0
            return (1..total).map { Chapter(it.toDouble(), chapterUrl(slug, "$it")) }
        }
        return parseChapters(node)
    }

    /** Capítulos de una lista JSON de la ficha: id del sitio y número; la URL sale de `urls.chapter`. */
    internal fun parseChapterItems(slug: String, node: Node, items: app.kaizen.source.spec.ChapterItemsSpec): List<Chapter> =
        node.select(items.item).mapNotNull { episode ->
            val eid = items.eid.int(episode) ?: return@mapNotNull null
            val number = items.number.first(episode)?.toDoubleOrNull() ?: return@mapNotNull null
            Chapter(number, chapterUrl(slug, numberText(number)), eid)
        }

    /** Lo que la API de episodios necesita de la ficha (id o URL, token, cookies); null si falta. */
    private fun openAjax(slug: String, body: String, doc: Document, cookies: Map<String, String>): AjaxEpisodes.Context? {
        val episodes = ajax ?: return null
        val pageUrl = spec.anime.chapters.ajax?.from
        return episodes.context(slug, body, doc, cookies, animeUrl(slug)) { pageUrl?.first(it) }
    }

    /** Ficha de [slug] ya descargada con su contexto de API, o null. */
    private fun openAjaxPage(slug: String): AjaxEpisodes.Context? {
        val url = animeUrl(slug)
        val page = SourceHttp.get(url) ?: return null
        val body = page.body()
        return openAjax(slug, body, Jsoup.parse(body, url), page.cookies())
    }

    /** Géneros de la ficha: de su lista JSON, de enlaces o, si la web no los muestra, de una API; sin ellos la ficha sale sin géneros. */
    private fun genresOf(node: Node): List<Genre> {
        spec.anime.genreItems?.let { items ->
            return node.select(items.item).mapNotNull { genre ->
                Genre(items.name.first(genre) ?: return@mapNotNull null, items.slug.first(genre)?.takeIf { validSlug(it) } ?: return@mapNotNull null)
            }
        }
        spec.anime.genreLinks?.let { return parseGenreLinks(node, it) }
        val genres = spec.anime.genres ?: return emptyList()
        val animeId = genres.idFrom.first(node)?.takeIf { value -> value.all { it.isDigit() } } ?: return emptyList()
        return runCatchingCoop {
            val body = SourceHttp.text(fill(genres.url, "id" to animeId)) ?: return@runCatchingCoop emptyList()
            parseGenreClasses(JSONObject(body).getJSONArray(genres.json))
        }.getOrDefault(emptyList())
    }

    override suspend fun schedule(): Map<Int, List<AiringSeries>> = withContext(Dispatchers.IO) {
        val schedule = spec.schedule ?: return@withContext emptyMap()
        if (schedule.list != null) {
            val page = fetchNode(fill(schedule.url)) ?: throw noResponse(fill(schedule.url))
            val grouped = parseScheduleList(page).sortedBy { it.name }.groupBy { it.day }
            return@withContext (1..7).associateWith { day ->
                grouped[day].orEmpty().map { AiringSeries(it.aid, it.name, it.slug, day, it.category).also { airing -> airing.source = id } }
            }
        }
        if (schedule.columns != null) {
            val doc = document(fill(schedule.url)) ?: throw noResponse(fill(schedule.url))
            return@withContext parseSchedule(HtmlNode(doc)).mapValues { (day, cards) ->
                cards.map { card ->
                    covers.remember(card.slug, card.cover)
                    AiringSeries(AnimeIds.forSlug(card.slug, id), card.name, card.slug, day, category = 1)
                }
            }
        }
        (1..schedule.days).associateWith { day ->
            val doc = document(fill(schedule.url, "day" to "$day"))
            doc?.let { parseCards(HtmlNode(it)) }.orEmpty().map { AiringSeries(AnimeIds.forSlug(it.slug, id), it.name, it.slug, day, category = 1) }
        }
    }

    override suspend fun servers(episodeUrl: String): List<ServerLink> = withContext(Dispatchers.IO) {
        // Las fuentes jsData sacan sus servidores por su propio camino (JsExtractor, ver TVServersFactory).
        if (spec.format == "jsData") return@withContext emptyList()
        spec.servers.text?.let { rules ->
            return@withContext SourceHttp.text(episodeUrl)?.let { parseTextServers(it, rules) }.orEmpty()
        }
        spec.servers.embedded?.let { embedded ->
            return@withContext SourceHttp.text(episodeUrl)?.let { parseEmbeddedServers(it, embedded) }.orEmpty()
        }
        spec.servers.request?.let { request ->
            // Los servidores no vienen en la página: se piden con la misma petición que hace su JavaScript.
            val page = SourceHttp.get(episodeUrl) ?: return@withContext emptyList()
            val response = postForm(request, page, page.parse()) ?: return@withContext emptyList()
            return@withContext parseServers(HtmlNode(Jsoup.parse(response)))
        }
        document(episodeUrl)?.let { parseServers(HtmlNode(it)) }.orEmpty()
    }

    /**
     * Hace la petición POST de [request] (formulario con textos fijos y datos de [doc], la página ya leída) al sitio al que llevó
     * [page]: un POST no sobrevive a una redirección, y la web puede mandar de `dominio.net` a un subdominio suyo. Solo se envía a
     * https y a un host del propio sitio. Devuelve el cuerpo de la respuesta; null si falta un dato o la petición falla.
     */
    private fun postForm(request: app.kaizen.source.spec.FormRequestSpec, page: SourceHttp.Reply, doc: Document): String? = runCatchingCoop {
        val origin = java.net.URI(page.url)
        val host = origin.host ?: return@runCatchingCoop null
        if (origin.scheme != "https" || (host != spec.host && !host.endsWith(".${spec.host}"))) return@runCatchingCoop null
        val form = request.form.mapValues { (_, value) -> value.literal ?: value.pick?.first(HtmlNode(doc)) ?: return@runCatchingCoop null }
        val url = "https://${origin.authority}${request.path}"
        app.kaizen.commons.HostGate.call(url) {
            SourceHttp.execute(url, ajax = true) {
                method("POST")
                referrer(page.url)
                cookies(page.cookies())
                form.forEach { (name, value) -> data(name, value) }
            }.body()
        }
    }.getOrNull()

    // Análisis puro del HTML (sin red), separado para poder probarlo.

    internal class Card(
        val slug: String,
        val name: String,
        val cover: String?,
        val year: Int?,
        val airing: Boolean,
        private val sourceId: String,
        val aid: Int? = null,
        val category: Int = 1
    ) {
        fun toSummary() = SeriesSummary(aid ?: AnimeIds.forSlug(slug, sourceId), name, slug, category = category, source = sourceId, year = year)
    }

    internal class Chapter(val number: Double, val url: String, val eid: Int? = null)

    internal class Episode(
        val slug: String,
        val name: String,
        val number: Double,
        val url: String,
        val cover: String? = null,
        val released: Long = 0L,
        val aid: Int? = null,
        val eid: Int? = null
    )

    internal class ScheduleRow(val aid: Int, val name: String, val slug: String, val day: Int, val category: Int)

    /** Objeto JSON que sigue al marcador de la lista embebida en la página (`var animes = {...}`). */
    private fun jsonListObject(body: String): JSONObject? {
        val marker = spec.jsonList?.marker ?: return null
        val start = body.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        return runCatchingCoop { JSONTokener(body.substring(start)).nextValue() as JSONObject }.getOrNull()
    }

    /** Fichas de la lista JSON embebida en una página; vacío si no está. */
    internal fun parseJsonList(body: String): List<Card> {
        val list = spec.jsonList ?: return emptyList()
        val data = jsonListObject(body)?.optJSONArray(list.array) ?: return emptyList()
        return List(data.length()) { data.optJSONObject(it) }.mapNotNull { item ->
            val slug = item?.optString(list.slug)?.takeIf { SLUG.matches(it) && it !in spec.slugReserved } ?: return@mapNotNull null
            Card(slug, item.optString(list.name).ifBlank { titleFromSlug(slug) }, list.cover?.let { item.optString(it) }, null, false, id)
        }.distinctBy { it.slug }
    }

    /** Servidores buscados en el texto de la página del capítulo, en el orden de las reglas del fichero. */
    internal fun parseTextServers(html: String, rules: List<app.kaizen.source.spec.TextServerSpec>): List<ServerLink> = buildList {
        for (rule in rules) {
            if (!rule.array) {
                rule.regex.findAll(html).forEach { match -> match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { add(ServerLink(it)) } }
                continue
            }
            val raw = rule.regex.find(html)?.groupValues?.getOrNull(1) ?: continue
            val array = runCatchingCoop { JSONArray(raw) }.getOrNull() ?: continue
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = decodeEmbedUrl(item.optString(rule.urlField.orEmpty())) ?: continue
                add(ServerLink(url))
            }
        }
    }

    /** Fichas de un listado (directorio, género, búsqueda, horario). */
    internal fun parseCards(page: Node): List<Card> {
        val cards = spec.cards
        return page.select(cards.item).mapNotNull { card ->
            val slug = (cards.slug?.first(card) ?: cards.link?.first(card)?.let { slugOf(it) })?.takeIf { validSlug(it) } ?: return@mapNotNull null
            val siteId = cards.id?.int(card)
            if (cards.id != null && siteId == null) return@mapNotNull null
            val name = cards.name.first(card).orEmpty().ifBlank { titleFromSlug(slug) }
            Card(slug, name, cards.cover?.first(card), cards.year?.first(card)?.toIntOrNull(),
                cards.airing != null && card.first(cards.airing) != null, id,
                aid = siteId, category = cards.category?.int(card) ?: 1)
        }.distinctBy { it.slug }
    }

    internal fun parseCards(doc: Document): List<Card> = parseCards(HtmlNode(doc))

    /** Un slug válido: solo minúsculas, cifras y guiones, y no una ruta reservada de la web. */
    private fun validSlug(slug: String) = SLUG.matches(slug) && slug !in spec.slugReserved

    /** Última página del listado según los enlaces de paginación (1 si no hay). */
    internal fun parseLastPage(page: Node): Int =
        spec.lastPage?.applyAll(page)?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 1

    internal fun parseLastPage(doc: Document): Int = parseLastPage(HtmlNode(doc))

    /** Capítulos de la ficha por grupo ascendente (el orden de [app.kaizen.source.spec.ChaptersSpec.groupOrder]); un número ya visto se omite. */
    internal fun parseChapters(page: Node): List<Chapter> {
        val chapters = spec.anime.chapters
        val item = chapters.item ?: return emptyList()
        val urlPick = chapters.url ?: return emptyList()
        val numberPick = chapters.number ?: return emptyList()
        val seen = HashSet<Double>()
        val groups = (chapters.group?.let { page.select(it) } ?: emptyList()).ifEmpty { listOf(page) }
        val ordered = groups.sortedBy { group -> chapters.groupOrder?.first(group)?.toIntOrNull() ?: 0 }
        val prefix = chapters.urlPrefix.replace("{base}", base)
        return ordered.flatMap { group ->
            group.select(item).mapNotNull { card ->
                val url = urlPick.first(card)?.takeIf { it.startsWith(prefix) } ?: return@mapNotNull null
                val number = numberPick.first(card)?.toDoubleOrNull() ?: return@mapNotNull null
                if (seen.add(number)) Chapter(number, url) else null
            }
        }
    }

    internal fun parseChapters(doc: Document): List<Chapter> = parseChapters(HtmlNode(doc))

    /** Géneros como enlaces de la ficha (dentro del contenedor del fichero). */
    internal fun parseGenreLinks(page: Node, links: app.kaizen.source.spec.GenreLinksSpec): List<Genre> {
        val scope = links.scope?.let { page.first(it) } ?: page
        return scope.select(links.item).mapNotNull { link ->
            val slug = links.slug.find(link.attr("href"))?.groupValues?.getOrNull(1) ?: return@mapNotNull null
            Genre(links.nameStrip?.let { link.text().replace(it, "") }.let { it ?: link.text() }.trim(), slug)
        }.distinctBy { it.slug }
    }

    internal fun parseGenreLinks(doc: Document, links: app.kaizen.source.spec.GenreLinksSpec): List<Genre> = parseGenreLinks(HtmlNode(doc), links)

    /** Relaciones de la ficha: de su lista JSON (ids del sitio) o de la saga en HTML (precuelas y secuelas). */
    private fun parseRelations(page: Node): List<Relation> {
        spec.anime.relationItems?.let { items ->
            return page.select(items.item).mapNotNull { relation ->
                Relation(
                    aid = items.aid.int(relation) ?: return@mapNotNull null,
                    name = items.name.first(relation) ?: return@mapNotNull null,
                    relation = items.type.int(relation) ?: return@mapNotNull null,
                    slug = items.slug.first(relation)?.takeIf { validSlug(it) } ?: return@mapNotNull null,
                    source = id
                )
            }
        }
        return parseSaga(page).map { (slug, name, type) -> Relation(AnimeIds.forSlug(slug, id), name, type, slug, id) }
    }

    /** Saga de la ficha como (slug, nombre, tipo): lo anterior a la ficha actual son precuelas y lo posterior secuelas. */
    internal fun parseSaga(page: Node): List<Triple<String, String, Int>> {
        val relations = spec.anime.relations ?: return emptyList()
        val scope = page.first(relations.scope) ?: return emptyList()
        if (relations.groupItem != null) return parseGroupedSaga(scope, relations)
        var afterCurrent = false
        return (relations.item?.let { scope.select(it) } ?: scope.children()).mapNotNull { entry ->
            if (relations.current != null && entry.matches(relations.current)) {
                afterCurrent = true
                return@mapNotNull null
            }
            val slug = slugOf(relations.link.first(entry).orEmpty()) ?: return@mapNotNull null
            val name = relations.name.first(entry).orEmpty().ifBlank { titleFromSlug(slug) }
            Triple(slug, name, if (afterCurrent) RELATION_SEQUEL else RELATION_PREQUEL)
        }
    }

    internal fun parseSaga(doc: Document): List<Triple<String, String, Int>> = parseSaga(HtmlNode(doc))

    /** Relaciones con títulos de grupo (Precuela, Secuela...): cada enlace toma el tipo del último título anterior. */
    private fun parseGroupedSaga(scope: Node, relations: app.kaizen.source.spec.RelationsSpec): List<Triple<String, String, Int>> {
        var type = 0
        return scope.children().mapNotNull { child ->
            if (child.matches(relations.groupItem!!)) {
                type = relations.groupTypes[child.text().trim().lowercase()] ?: 0
                return@mapNotNull null
            }
            if (relations.item != null && !child.matches(relations.item)) return@mapNotNull null
            val slug = slugOf(relations.link.first(child).orEmpty()) ?: return@mapNotNull null
            val raw = relations.name.first(child).orEmpty()
            val name = (relations.nameStrip?.let { raw.replace(it, "") } ?: raw).trim().ifBlank { titleFromSlug(slug) }
            Triple(slug, name, type)
        }.distinctBy { it.first }
    }

    /** Servidores de un JSON embebido: `[{..., players:[{name,url}]}]`; las URL que empiezan por // se completan con https:. */
    internal fun parseEmbeddedServers(html: String, embedded: app.kaizen.source.spec.EmbeddedServersSpec): List<ServerLink> {
        val marker = html.indexOf(embedded.marker)
        if (marker < 0) return emptyList()
        val start = html.lastIndexOf('[', marker)
        if (start < 0) return emptyList()
        var depth = 0
        var end = -1
        for (i in start until html.length) {
            when (html[i]) {
                '[' -> depth++
                ']' -> if (--depth == 0) { end = i; break }
            }
        }
        if (end < 0) return emptyList()
        val groups = runCatchingCoop { JSONArray(html.substring(start, end + 1).replace("\\u0026", "&")) }.getOrNull() ?: return emptyList()
        return buildList {
            for (g in 0 until groups.length()) {
                val players = groups.optJSONObject(g)?.optJSONArray(embedded.players) ?: continue
                for (p in 0 until players.length()) {
                    val player = players.optJSONObject(p) ?: continue
                    val url = player.optString(embedded.url).let { if (it.startsWith("//")) "https:$it" else it }
                    if (url.isNotBlank()) add(ServerLink(url))
                }
            }
        }
    }

    /** Horario en una página con una sección por día. */
    internal fun parseSchedule(page: Node): Map<Int, List<Card>> {
        val columns = spec.schedule?.columns ?: return emptyMap()
        return page.select(columns.section).withIndex().mapNotNull { (index, section) ->
            // Sin nombres de día, la posición de la sección es el día (lunes = 1).
            val day = if (columns.day == null) index + 1 else columns.dayNames.indexOf(columns.day.first(section)?.lowercase()) + 1
            if (day == 0 || day > 7) return@mapNotNull null
            // Una serie puede salir en varios enlaces (portada y texto): se junta por slug y se queda el que trae nombre.
            val bySlug = LinkedHashMap<String, Card>()
            section.select(columns.item).forEach { card ->
                val slug = slugOf(columns.link.first(card).orEmpty()) ?: return@forEach
                val found = Card(slug, columns.name.first(card).orEmpty(), columns.cover?.first(card), null, false, id)
                val old = bySlug[slug]
                if (old == null || (old.name.isBlank() && found.name.isNotBlank())) bySlug[slug] = found
            }
            day to bySlug.values.map { Card(it.slug, it.name.ifBlank { titleFromSlug(it.slug) }, it.cover, null, false, id) }
        }.toMap()
    }

    internal fun parseSchedule(doc: Document): Map<Int, List<Card>> = parseSchedule(HtmlNode(doc))

    /** Horario como lista de series con la fecha de su último episodio: el día de la semana de esa fecha (-1 si no la hay). */
    internal fun parseScheduleList(page: Node): List<ScheduleRow> {
        val list = spec.schedule?.list ?: return emptyList()
        return page.select(list.item).mapNotNull { row ->
            ScheduleRow(
                aid = list.aid.int(row) ?: return@mapNotNull null,
                name = list.name.first(row) ?: return@mapNotNull null,
                slug = list.slug.first(row)?.takeIf { validSlug(it) } ?: return@mapNotNull null,
                day = list.day.first(row)?.let(::dayOfWeek) ?: -1,
                category = list.category.int(row) ?: return@mapNotNull null
            )
        }
    }

    /** Capítulos de la portada: URL, número y nombre según el fichero. */
    internal fun parseRecents(page: Node): List<Episode> {
        val recents = spec.recents ?: return emptyList()
        return page.select(recents.item).mapNotNull { card ->
            val number = recents.number.first(card)?.toDoubleOrNull() ?: return@mapNotNull null
            val link = recents.link?.first(card)?.let(::absolute)?.takeIf(::onSite)
            val slug = (recents.slug?.first(card)?.takeIf { validSlug(it) } ?: link?.let { slugOf(it) }) ?: return@mapNotNull null
            // Sin enlace en la tarjeta, la URL del capítulo sale de la plantilla.
            val url = link ?: fill(spec.urls.chapter ?: return@mapNotNull null, "slug" to slug, "number" to numberText(number))
            val raw = recents.name.first(card).orEmpty()
            val name = (recents.nameStrip?.let { raw.replace(it, "") } ?: raw).trim().ifBlank { titleFromSlug(slug) }
            Episode(slug, name, number, url, recents.cover?.first(card), releasedOf(card),
                aid = recents.aid?.int(card), eid = recents.eid?.int(card))
        }.distinctBy { it.url }
    }

    internal fun parseRecents(doc: Document): List<Episode> = parseRecents(HtmlNode(doc))

    /** Fecha de publicación (ms) de una tarjeta de la portada: la fecha completa del fichero, «12 oct» o «hace 2 horas»; 0 si no hay. */
    private fun releasedOf(card: Node): Long {
        val recents = spec.recents ?: return 0L
        recents.published?.first(card)?.let { text ->
            val time = runCatching {
                SimpleDateFormat(recents.publishedFormat, Locale.US).apply { timeZone = TimeZone.getTimeZone(recents.publishedTz) }.parse(text)?.time
            }.getOrNull()
            if (time != null) return time
        }
        recents.date?.first(card)?.let { DATE.find(it) }?.let { return siteDate(it.groupValues[1].toInt(), it.groupValues[2]) }
        return recents.relativeDate?.first(card)?.let { relativeDate(it) } ?: 0L
    }

    /** `[{link, fecha}]` → URL → milisegundos, con el formato y la zona del fichero. */
    internal fun parseRecentDates(json: JSONArray): Map<String, Long> {
        val dates = spec.recents?.dates ?: return emptyMap()
        val format = java.text.SimpleDateFormat(dates.format, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone(dates.timeZone) }
        return buildMap {
            for (i in 0 until json.length()) {
                val item = json.optJSONObject(i) ?: continue
                val time = runCatchingCoop { format.parse(item.optString(dates.dateField))?.time }.getOrNull() ?: continue
                item.optString(dates.linkField).takeIf { it.isNotBlank() }?.let { put(it, time) }
            }
        }
    }

    /** Géneros de una lista de clases/textos con la expresión del fichero (grupo 1 = slug). */
    internal fun parseGenreClasses(classes: JSONArray): List<Genre> {
        val regex = spec.anime.genres?.regex ?: return emptyList()
        return List(classes.length()) { classes.optString(it) }
            .mapNotNull { regex.matchEntire(it)?.groupValues?.get(1) }
            .distinct().map { Genre(SearchGenres.nameOf(it), it) }
    }

    /** Servidores del capítulo: URL del embed y, si el fichero define etiquetas (idioma...), la suya; ordenados según el fichero; siempre https. */
    internal fun parseServers(page: Node): List<ServerLink> {
        val servers = spec.servers
        val order = servers.order
        val itemSelector = servers.item ?: return emptyList()
        val urlPick = servers.url ?: return emptyList()
        return page.select(itemSelector).mapNotNull { item ->
            val raw = urlPick.first(item) ?: return@mapNotNull null
            val url = (if (servers.hex) decodeHexUrl(raw) else if (servers.base64) (if (servers.iframe) decodeEmbedIframeUrl(raw) else decodeEmbedUrl(raw)) else raw.takeIf { it.startsWith("http") })
                ?.replaceFirst("http://", "https://") ?: return@mapNotNull null
            ServerLink(url, if (servers.tagFrom != null) tagOf(item) else null)
        }.distinctBy { it.url }.sortedBy { order.indexOf(it.label.orEmpty()).let { index -> if (index < 0) order.size else index } }
    }

    internal fun parseServers(doc: Document): List<ServerLink> = parseServers(HtmlNode(doc))

    /** URL escrita en hexadecimal («68747470…»); null si no es hexadecimal o no da una URL http(s). */
    private fun decodeHexUrl(value: String): String? {
        if (value.length % 2 != 0 || !value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return runCatching { String(ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, Charsets.UTF_8) }
            .getOrNull()?.takeIf { (it.startsWith("http://") || it.startsWith("https://")) && it.none { c -> c.isWhitespace() || c.isISOControl() } }
    }

    private fun tagOf(item: Node): String {
        val servers = spec.servers
        val text = servers.tagFrom?.first(item)?.lowercase() ?: return servers.tagDefault
        return servers.tagMap.firstOrNull { (contains, _) -> contains in text }?.second ?: servers.tagDefault
    }

    /** Slug de la serie: el grupo 1 de la expresión del fichero sobre la URL; descarta los reservados. */
    internal fun slugOf(url: String): String? =
        spec.slugRegex.find(url)?.groups?.drop(1)?.firstNotNullOfOrNull { it?.value }?.takeIf { SLUG.matches(it) && it !in spec.slugReserved }

    private companion object {
        /** Tope de episodios que se aceptan de una respuesta HTML: un número absurdo (un identificador en una URL) no debe crear una lista enorme. */
        const val MAX_HTML_EPISODES = 5000.0

        const val SKIP_PAGES = 6
        const val LISTING_CACHE = 6
        val LISTING_TTL = 10.minutes
        val SLUG = Regex("[a-z0-9-]+")
        val DATE = Regex("""(\d{1,2}) ([a-z]{3})""")

        /** «2026-08-25T01:09:15.2+00» y similares: fecha-hora ISO con la zona como +HH o +HH:mm (formateador inmutable, compartido). */
        val ISO_WITH_OFFSET: DateTimeFormatter =
            DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE_TIME).appendOffset("+HH:mm", "Z").toFormatter()
    }
}
