package app.kaizen.source.spec

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

/** Fichero de fuente inválido o de un motor más nuevo que el de la app. */
class SpecException(message: String) : Exception(message)

/**
 * Cómo sacar un dato: el mismo lenguaje para páginas HTML y para JSON.
 * En un elemento HTML: [sel] (selector CSS, o el propio elemento), [attr] (atributo; si falta, el texto) y [regex] (el primer
 * grupo, o todo el texto si no tiene grupos). Con [all], se prueban todos los elementos que casan y vale el primero que da
 * un resultado. En un objeto JSON: [sel] es la ruta con puntos («media.title»; [attr] la continúa) y [regex] se aplica al valor.
 */
class Pick(val sel: String?, val attr: String?, val regex: Regex?, val all: Boolean) {
    fun apply(node: Node): String? {
        val targets = when {
            sel == null -> listOf(node)
            all -> node.select(sel)
            else -> listOfNotNull(node.first(sel))
        }
        return targets.firstNotNullOfOrNull { read(it) }
    }

    fun apply(element: Element): String? = apply(HtmlNode(element))

    /** Un resultado por cada nodo que casa (para agregados como el máximo de los números de página). */
    fun applyAll(node: Node): List<String> {
        val targets = if (sel == null) listOf(node) else node.select(sel)
        return targets.mapNotNull { read(it) }
    }

    fun applyAll(element: Element): List<String> = applyAll(HtmlNode(element))

    private fun read(target: Node): String? = finish(if (attr != null) target.attr(attr) else target.text())

    private fun finish(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val value = if (regex == null) raw else regex.find(raw)?.let { if (it.groups.size > 1) it.groupValues[1] else it.value }
        return value?.takeIf { it.isNotBlank() }
    }
}

/** Alternativas en orden: vale la primera que da un resultado. */
class Picks(val items: List<Pick>) {
    fun first(node: Node): String? = items.firstNotNullOfOrNull { it.apply(node) }

    fun first(element: Element): String? = first(HtmlNode(element))

    /** Todos los valores del primer selector que da alguno (cada elemento que casa): para las listas de la ficha. */
    fun all(node: Node): List<String> = items.firstNotNullOfOrNull { it.applyAll(node).takeIf { found -> found.isNotEmpty() } }.orEmpty()

    /** El valor como número entero (acepta «12», «12.0»); null si no es un número. */
    fun int(node: Node): Int? = first(node)?.toDoubleOrNull()?.toInt()
}

/**
 * Fichas de un listado. El [slug] sale de [slug] o del enlace ([link]); [id] y [category] son los del sitio si los da (si no, id
 * interno y categoría 1).
 */
class CardsSpec(
    val item: String,
    val slug: Picks?,
    val link: Picks?,
    val id: Picks?,
    val category: Picks?,
    val name: Picks,
    val cover: Picks?,
    val year: Picks?,
    val airing: String?
)

/** Elementos de una lista de la ficha en JSON: [item] (ruta a la lista) y de cada uno los datos que se piden. */
class ChapterItemsSpec(val item: String, val eid: Picks, val number: Picks)

class GenreItemsSpec(val item: String, val name: Picks, val slug: Picks)

class RelationItemsSpec(val item: String, val aid: Picks, val name: Picks, val type: Picks, val slug: Picks)

/** Plantillas de imagen con {aid}, {slug} y {number} para los sitios que las calculan a partir del id. */
class ImagesSpec(val cover: String, val thumbnail: String)

/** Horario como lista de series con su fecha de último episodio: [day] es una fecha ISO y el día de la semana es el de emisión. */
class ScheduleListSpec(
    val item: String,
    val slug: Picks,
    val aid: Picks,
    val name: Picks,
    val category: Picks,
    val day: Picks
)

/** Cómo se pide una API: [method], [form] (valores con {token}), [headers], de dónde sale el token de la ficha, y si se envían las cookies y el referrer de la ficha. */
class RequestSpec(
    val method: String,
    val form: Map<String, String>,
    val headers: Map<String, String>,
    val tokenSel: String?,
    val tokenAttr: String,
    val cookies: Boolean,
    val referrer: Boolean
)

/** Respuesta paginada: [perPage] episodios por bloque; en la respuesta, [total] y [lastPage] (para leer solo el primero y el último). */
class PagedSpec(val perPage: Double, val total: String, val lastPage: String, val maxPages: Int)

/**
 * Números de capítulo que da una API JSON. La URL sale de un atributo de la ficha ([from]) o de la plantilla [url] con el id
 * que la ficha trae ([idRegex]) y {page}; [request] dice cómo pedirla (GET por defecto; POST con token y cookies si la web los
 * exige), [paged] si viene en bloques. La respuesta es un objeto con [array] (cada elemento con [field] y, si hay [date],
 * su fecha en [dateFormat]/[tz]).
 */
class AjaxChaptersSpec(
    val from: Picks?,
    val url: String?,
    val idRegex: Regex?,
    val request: RequestSpec?,
    val paged: PagedSpec?,
    val array: String,
    val field: String,
    val date: String?,
    val dateFormat: String,
    val tz: String
)

/** Un valor del formulario de una petición: un texto fijo o lo que se lee de la página ([pick]). */
class FormValue(val literal: String?, val pick: Picks?)

/**
 * Una petición POST que la propia página hace con JavaScript a una ruta de su sitio ([path], con `/` delante, sin dominio): el
 * formulario lleva textos fijos y datos de la página ya leída. Se envía al sitio al que llevó la página (si la web redirige a otro
 * subdominio, a ese), porque un POST no sobrevive a una redirección.
 */
class FormRequestSpec(val path: String, val form: Map<String, FormValue>)

/** Capítulos que da una respuesta HTML de [request]: cada [number] (grupo 1) que aparece en ella; vale 1..el mayor (la web da los últimos). */
class HtmlChaptersSpec(val request: FormRequestSpec, val number: Regex)

/**
 * Capítulos de la ficha: por tarjetas HTML ([item], [url], [number]), por una API ([ajax]) o por un total ([count], capítulos
 * 1..N); en los dos últimos la URL de cada capítulo sale de `urls.chapter`.
 */
class ChaptersSpec(
    val items: ChapterItemsSpec?,
    val count: Picks?,
    val group: String?,
    val groupOrder: Picks?,
    val item: String?,
    val url: Picks?,
    val number: Picks?,
    val urlPrefix: String,
    val ajax: AjaxChaptersSpec?,
    val html: HtmlChaptersSpec? = null
)

/** Géneros como enlaces de la ficha: los [item] dentro del [scope] (selector del contenedor, o toda la página) y el slug del enlace. */
class GenreLinksSpec(val scope: String?, val item: String, val slug: Regex, val nameStrip: Regex?)

/** Saga de la ficha (precuelas/secuelas): los [item] de [scope] van de más antigua a más reciente y [current] marca la ficha actual. */
class RelationsSpec(
    val scope: String,
    val item: String?,
    val link: Picks,
    val name: Picks,
    val current: String?,
    val groupItem: String?,
    val groupTypes: Map<String, Int>,
    val nameStrip: Regex?
)

/** Lista de series como JSON dentro de la página (`var animes = {...}`): desde [marker], el objeto con [array] y [total]. */
class JsonListSpec(val marker: String, val array: String, val slug: String, val name: String, val cover: String?, val total: String?)

class GenresSpec(val idFrom: Picks, val url: String, val json: String, val regex: Regex)

/**
 * Lo de la ficha de una serie que **no** es dato de la ficha: si la página es una ficha ([require]), su id, el día de la semana, cómo se leen los
 * géneros (con su slug), las relaciones y los capítulos. Los datos de la ficha (título, sinopsis, estado, año, estudio, nota, portada…) se declaran
 * una sola vez, en el bloque `ficha` ([FichaSpec]), y de ahí sale también la serie que usa la app.
 */
class AnimeSpec(
    val require: String?,
    val id: Picks?,
    val day: Picks?,
    val genreItems: GenreItemsSpec?,
    val relationItems: RelationItemsSpec?,
    val chapters: ChaptersSpec,
    val genres: GenresSpec?,
    val genreLinks: GenreLinksSpec?,
    val relations: RelationsSpec?
)

/** Una forma de leer un valor de la ficha: el selector ([picks]), con qué se parte un texto que trae varios ([split]) y la tabla del código de la web al valor de AniList ([map]). */
class FichaPart(val picks: Picks, val split: Regex?, val map: Map<String, String>)

/** Cómo declara una fuente un campo del esquema (ver [app.kaizen.source.WebFichaSchema]): un valor, una lista de valores o una lista de objetos. */
sealed interface FichaField

class ScalarField(val part: FichaPart) : FichaField

/** Una lista: cada parte aporta sus valores y se juntan. */
class ListField(val parts: List<FichaPart>) : FichaField

/** Una lista de objetos (personajes, equipo): un elemento por cada [item] de la página y, de cada uno, sus campos. */
class ItemsField(val item: String, val fields: Map<String, FichaField>) : FichaField

/**
 * Lo que una fuente rellena del registro único que se guarda de cada ficha ([app.kaizen.source.WebFichaSchema]). Solo puede declarar claves del esquema,
 * el título (`romaji`) es obligatorio y el bloque lo es para toda fuente: así todas entregan lo mismo y de la misma manera. [descriptionLanguage] es el idioma
 * de la sinopsis que da la web (`es` o `en`) y [scoreMax] el máximo de su nota (5 o 10): se normaliza siempre sobre 5.
 */
class FichaSpec(val fields: Map<String, FichaField>, val descriptionLanguage: String?, val scoreMax: Int?)

class DatesSpec(val url: String, val linkField: String, val dateField: String, val format: String, val timeZone: String)

class RecentsSpec(
    val url: String,
    val item: String,
    val link: Picks?,
    val slug: Picks?,
    val aid: Picks?,
    val eid: Picks?,
    val number: Picks,
    val name: Picks,
    val nameStrip: Regex?,
    val cover: Picks?,
    val date: Picks?,
    val relativeDate: Picks?,
    val published: Picks?,
    val publishedFormat: String,
    val publishedTz: String,
    val limit: Int,
    val dates: DatesSpec?
)

/** Servidores en un JSON embebido en la página: el primer array que contiene [marker], con [players] por grupo. */
class EmbeddedServersSpec(val marker: String, val players: String, val name: String, val url: String)

/** Servidores buscados con una expresión en el texto de la página: cada coincidencia (grupo 1 = URL) o, con [array], un array JSON con base64 en [urlField]. */
class TextServerSpec(val regex: Regex, val array: Boolean, val name: String, val nameField: String?, val urlField: String?)

class ServersSpec(
    val text: List<TextServerSpec>?,
    val embedded: EmbeddedServersSpec?,
    val item: String?,
    val url: Picks?,
    val base64: Boolean,
    /** La URL del servidor va escrita en hexadecimal (en lugar de base64). */
    val hex: Boolean = false,
    /** Lo codificado en base64 es un `<iframe src="…">` y la URL es su `src` (animeflv.ar), no la URL sola. */
    val iframe: Boolean = false,
    val tagFrom: Picks?,
    val tagMap: List<Pair<String, String>>,
    val tagDefault: String,
    val order: List<String>,
    /** Los servidores no vienen en la página del capítulo: se piden con esta petición y [item]/[url] se leen de su respuesta HTML. */
    val request: FormRequestSpec? = null
)

/** Total del catálogo: de una cabecera de [url] o, sin ella, de la última página ([lastUrl]) contando [perPage] fichas por página. */
class TotalSpec(val url: String, val header: String?, val json: String?, val listVar: Boolean, val lastUrl: String?, val perPage: Int)

/** Horario en una sola página con una columna por día: [section] y el nombre del día en [day] (lunes = 1 según [dayNames]). */
class ColumnsSpec(
    val section: String,
    val day: Picks?,
    val dayNames: List<String>,
    val item: String,
    val link: Picks,
    val name: Picks,
    val cover: Picks?
)

/** Horario: una página por día ([url] con {day}) o una sola con [columns]. */
class ScheduleSpec(val url: String, val days: Int, val columns: ColumnsSpec?, val list: ScheduleListSpec?, val scoresUrl: String?)

/** Campos de cada serie en la respuesta JSON de la API del directorio. */
class ApiFields(val slug: String, val name: String, val cover: String?, val score: String?, val votes: String?, val year: String?)

/**
 * API JSON del directorio: [url] más los parámetros que resuelve el servidor (nombres en [params]: page, limit, sort, text,
 * genre, minScore, year; el estado «en emisión» en [airing]). La nota llega sobre 10 y se divide por [scoreScale].
 */
class ApiSpec(
    val url: String,
    val limit: Int,
    val params: Map<String, String>,
    val sortValues: Map<String, String>,
    val airing: Pair<String, String>?,
    val scoreScale: Double,
    val list: String,
    val totalPages: String,
    val fields: ApiFields
)

/** Cómo trata la fuente el texto de búsqueda: solo (sin más filtros), solo aunque haya un año, siempre junto a los demás filtros, o no lo admite. */
enum class TextMode { ALONE, ALONE_WITH_YEAR, ALWAYS, NONE }

/** Lo que la propia fuente resuelve al pedir una página: [text] y los [filters] (GENRE, AIRING, YEAR, SCORE, SORT_RATING). */
class SearchCaps(val text: TextMode, val filters: Set<app.kaizen.source.Filter>)

/** Plantillas de URL de la fuente; los marcadores son {base}, {slug}, {number}, {page}, {genre}, {query}, {id} y {day}. */
class UrlsSpec(
    val anime: String,
    val chapter: String?,
    val listFirst: String,
    val listNext: String,
    val genreFirst: String?,
    val genreNext: String?,
    val searchFirst: String?,
    val searchNext: String?,
    val airing: String?,
    val yearParam: String?,
    val genreParam: String?,
    val airingParam: String?,
    val sortTitleParam: String?,
    val sortRatingParam: String?
)

/**
 * Descripción declarativa de una fuente por scraping (ver [app.kaizen.source.ConfigurableSource]). Un fichero JSON por
 * fuente; el campo `schema` es la versión del formato: la app solo acepta los que conoce ([SUPPORTED_SCHEMA]).
 */
class SourceSpec(
    val schema: Int,
    val version: Int,
    val id: String,
    val format: String,
    val images: ImagesSpec?,
    val lightRating: Boolean,
    val cacheFiche: Boolean,
    val name: String,
    val badge: String,
    val host: String,
    val base: String,
    val browserUa: Boolean,
    val coverPrefixes: List<String>,
    val coverFallback: String,
    val slugRegex: Regex,
    val slugReserved: Set<String>,
    val chapterLinks: Boolean,
    val coverWeakPrefixes: List<String>,
    val coverRegex: Regex?,
    val coverReject: Regex?,
    val jsonList: JsonListSpec?,
    val eidDouble: Boolean,
    val search: SearchCaps,
    val listsScores: Boolean,
    val api: ApiSpec?,
    val airingPages: Int,
    val urls: UrlsSpec,
    val cards: CardsSpec,
    val lastPage: Pick?,
    val airingFilter: Boolean,
    val total: TotalSpec?,
    /** Primer año desde el que se pide el catálogo año a año (la web recorta a 1000 series un listado largo); requiere `urls.yearParam`. */
    val yearSlicesFrom: Int? = null,
    val anime: AnimeSpec,
    val recents: RecentsSpec?,
    val schedule: ScheduleSpec?,
    /** Cómo guardar lo que da la web de cada ficha con la estructura de AniList; sin él, la fuente no guarda esa copia. */
    val ficha: FichaSpec,
    private val htmlServers: ServersSpec?
) {
    // Una fuente jsData no tiene servidores en la página: los saca por su propio camino (ver TVServersFactory).
    val servers: ServersSpec get() = checkNotNull(htmlServers) { "fuente sin sección servers" }

    companion object {
        const val SUPPORTED_SCHEMA = 1

        fun parse(text: String): SourceSpec = try {
            fromJson(JSONObject(text))
        } catch (e: SpecException) {
            throw e
        } catch (e: Exception) {
            throw SpecException("fichero de fuente no válido: ${e.message}")
        }

        private fun fromJson(j: JSONObject): SourceSpec {
            val schema = j.getInt("schema")
            if (schema != SUPPORTED_SCHEMA) throw SpecException("formato $schema no soportado (la app entiende $SUPPORTED_SCHEMA)")
            val id = j.getString("id").also { if (!Regex("[a-z0-9]{2,20}").matches(it)) throw SpecException("id no válido: $it") }
            val base = j.getString("base").trimEnd('/').also { if (!it.startsWith("https://")) throw SpecException("base debe ser https") }
            val host = j.getString("host")
            val baseHost = URL_HOST.find(base)?.groupValues?.get(1)
            if (baseHost == null || !(baseHost == host || baseHost.endsWith(".$host"))) throw SpecException("host no coincide con base")
            val slug = j.getJSONObject("slug")
            val urls = j.getJSONObject("urls")
            val format = j.optString("format", "html")
            if (format != "html" && format != "jsData") throw SpecException("formato de páginas desconocido: $format")
            val html = format == "html"
            // Con capítulos por API la URL de cada uno solo puede salir de la plantilla.
            if ((!html || j.getJSONObject("anime").getJSONObject("chapters").let { it.has("ajax") || it.has("count") || it.has("items") }) && !urls.has("chapter")) throw SpecException("falta urls.chapter")
            val spec = SourceSpec(
                schema = schema,
                version = j.getInt("version"),
                id = id,
                format = format,
                images = j.optJSONObject("images")?.let { ImagesSpec(imageTemplate(it, "cover", host), imageTemplate(it, "thumbnail", host)) },
                lightRating = j.optBoolean("lightRating", true),
                cacheFiche = j.optBoolean("cacheFiche", false),
                name = j.getString("name"),
                badge = j.getString("badge"),
                host = host,
                base = base,
                browserUa = j.optBoolean("browserUa", false),
                coverPrefixes = strings(j.optJSONArray("coverPrefixes")),
                coverFallback = j.optString("coverFallback", "$base/favicon.ico"),
                slugRegex = Regex(slug.getString("regex")),
                slugReserved = strings(slug.optJSONArray("reserved")).toSet(),
                chapterLinks = j.optBoolean("chapterLinks", false),
                coverWeakPrefixes = strings(j.optJSONArray("coverWeakPrefixes")),
                coverRegex = j.optString("coverRegex").takeIf { it.isNotBlank() }?.let { Regex(it) },
                coverReject = j.optString("coverReject").takeIf { it.isNotBlank() }?.let { Regex(it) },
                jsonList = j.optJSONObject("jsonList")?.let {
                    JsonListSpec(it.getString("marker"), it.getString("array"), it.getString("slug"), it.getString("name"),
                        it.optString("cover").takeIf { s -> s.isNotBlank() }, it.optString("total").takeIf { s -> s.isNotBlank() })
                },
                eidDouble = j.optBoolean("eidDouble", false),
                search = search(j.getJSONObject("search")),
                listsScores = j.optBoolean("listsScores", false),
                api = j.optJSONObject("api")?.let(::api),
                airingPages = j.optInt("airingPages", 15).coerceIn(1, 30),
                urls = UrlsSpec(
                    anime = template(urls.getString("anime")),
                    chapter = optTemplate(urls, "chapter"),
                    listFirst = template(urls.getString("listFirst")),
                    listNext = template(urls.getString("listNext")),
                    genreFirst = optTemplate(urls, "genreFirst"),
                    genreNext = optTemplate(urls, "genreNext"),
                    searchFirst = optTemplate(urls, "searchFirst"),
                    searchNext = optTemplate(urls, "searchNext"),
                    airing = optTemplate(urls, "airing"),
                    yearParam = fragment(urls, "yearParam"),
                    genreParam = fragment(urls, "genreParam"),
                    airingParam = fragment(urls, "airingParam"),
                    sortTitleParam = fragment(urls, "sortTitleParam"),
                    sortRatingParam = fragment(urls, "sortRatingParam")
                ),
                cards = j.getJSONObject("cards").let { c ->
                    if (!c.has("slug") && !c.has("link")) throw SpecException("cards: falta slug o link")
                    CardsSpec(c.getString("item"), c.opt("slug")?.let(::picks), c.opt("link")?.let(::picks), c.opt("id")?.let(::picks),
                        c.opt("category")?.let(::picks), picks(c.get("name")), c.opt("cover")?.let(::picks),
                        c.opt("year")?.let(::picks), c.optString("airing").takeIf { it.isNotBlank() })
                },
                lastPage = j.optJSONObject("lastPage")?.let(::pick),
                airingFilter = j.optBoolean("airingFilter", false),
                total = j.optJSONObject("catalogTotal")?.let { t ->
                    TotalSpec(template(t.getString("url")), t.optString("header").takeIf { it.isNotBlank() },
                        t.optString("json").takeIf { it.isNotBlank() }, t.optBoolean("listVar", false), optTemplate(t, "lastUrl"), t.optInt("perPage", 24).coerceIn(1, 500))
                },
                yearSlicesFrom = j.optInt("catalogByYearFrom", 0).takeIf { it in 1900..2100 },
                anime = anime(j.getJSONObject("anime")),
                recents = j.optJSONObject("recents")?.let(::recents),
                schedule = j.optJSONObject("schedule")?.let(::schedule),
                ficha = ficha(j.optJSONObject("ficha") ?: throw SpecException("falta el bloque ficha: toda fuente debe declararlo (ver docs/wiki/webficha-esquema.md)")),
                htmlServers = if (html) servers(j.getJSONObject("servers")) else null
            )
            validateSearch(spec)
            return spec
        }

        /** Un filtro o el texto solo se pueden declarar si la fuente tiene con qué resolverlos (URL, parámetro o API): si no, se anunciarían y no se aplicarían. */
        private fun validateSearch(spec: SourceSpec) {
            val caps = spec.search
            val urls = spec.urls
            val api = spec.api != null
            if (caps.text != TextMode.NONE && urls.searchFirst == null && !api) throw SpecException("search.text sin urls.searchFirst ni api")
            caps.filters.forEach { filter ->
                val ok = api || when (filter) {
                    app.kaizen.source.Filter.GENRE -> urls.genreFirst != null || urls.genreParam != null
                    app.kaizen.source.Filter.AIRING -> spec.airingFilter || urls.airing != null || urls.airingParam != null
                    app.kaizen.source.Filter.YEAR -> urls.yearParam != null
                    app.kaizen.source.Filter.SORT_RATING -> urls.sortRatingParam != null
                    else -> false
                }
                if (!ok) throw SpecException("filtro sin soporte en la fuente: $filter")
            }
        }

        private fun anime(a: JSONObject): AnimeSpec {
            val ch = a.getJSONObject("chapters")
            // Lo que ya no vale aquí (está en `ficha`) se rechaza: dejarlo sería una segunda definición del mismo dato.
            val moved = MOVED_TO_FICHA.filter { a.has(it) }
            if (moved.isNotEmpty()) throw SpecException("anime: ${moved.joinToString()} van en el bloque ficha (ver docs/wiki/webficha-esquema.md)")
            return AnimeSpec(
                require = a.optString("require").takeIf { it.isNotBlank() },
                id = a.opt("id")?.let(::picks),
                day = a.opt("day")?.let(::picks),
                genreItems = a.optJSONObject("genreItems")?.let { GenreItemsSpec(it.getString("item"), picks(it.get("name")), picks(it.get("slug"))) },
                relationItems = a.optJSONObject("relationItems")?.let {
                    RelationItemsSpec(it.getString("item"), picks(it.get("aid")), picks(it.get("name")), picks(it.get("type")), picks(it.get("slug")))
                },
                chapters = chapters(ch),
                genres = a.optJSONObject("genres")?.let {
                    GenresSpec(picks(it.get("idFrom")), template(it.getString("url")), it.getString("json"), Regex(it.getString("regex")))
                },
                genreLinks = a.optJSONObject("genreLinks")?.let {
                    GenreLinksSpec(it.optString("scope").takeIf { s -> s.isNotBlank() }, it.getString("item"), Regex(it.getString("slug")),
                        it.optString("nameStrip").takeIf { s -> s.isNotBlank() }?.let { s -> Regex(s) })
                },
                relations = a.optJSONObject("relations")?.let {
                    val types = it.optJSONObject("groupTypes")?.let { t -> t.keys().asSequence().associate { k -> k.lowercase() to t.getInt(k) } }.orEmpty()
                    RelationsSpec(it.getString("scope"), it.optString("item").takeIf { s -> s.isNotBlank() }, picks(it.get("link")),
                        picks(it.get("name")), it.optString("current").takeIf { s -> s.isNotBlank() },
                        it.optString("groupItem").takeIf { s -> s.isNotBlank() }, types,
                        it.optString("nameStrip").takeIf { s -> s.isNotBlank() }?.let { s -> Regex(s) })
                }
            )
        }

        /** El bloque `ficha` de una fuente, leído según el esquema único: solo claves suyas, título obligatorio, y valores de enumeración que existan en AniList. */
        private fun ficha(f: JSONObject): FichaSpec {
            val schema = app.kaizen.source.WebFichaSchema
            val unknown = f.keys().asSequence().filter { it !in schema.KEYS }.sorted().toList()
            if (unknown.isNotEmpty()) throw SpecException("ficha: claves que no son del esquema: ${unknown.joinToString()} (válidas: ${schema.KEYS.sorted().joinToString()})")
            schema.FIELDS.filter { it.required && !f.has(it.key) }.forEach { throw SpecException("ficha: falta ${it.key}, obligatorio en todas las fuentes") }
            val fields = schema.FIELDS.filter { !it.system && f.has(it.key) }.associate { it.key to fichaField(it, f.get(it.key)) }
            val language = f.optString("descriptionLanguage").takeIf { it.isNotBlank() }
            if (language != null && language !in schema.LANGUAGES) throw SpecException("ficha.descriptionLanguage no válido: $language (válidos: ${schema.LANGUAGES.joinToString()})")
            if ("description" in fields && language == null) throw SpecException("ficha: con description hace falta descriptionLanguage")
            val max = if (f.has("scoreMax")) f.getInt("scoreMax") else null
            if (max != null && max !in SCORE_MAXIMUMS) throw SpecException("ficha.scoreMax no válido: $max (válidos: ${SCORE_MAXIMUMS.joinToString()})")
            if ("score" in fields && max == null) throw SpecException("ficha: con score hace falta scoreMax (5 o 10)")
            return FichaSpec(fields, language, max)
        }

        private fun fichaField(def: app.kaizen.source.WebFichaSchema.Field, value: Any): FichaField = when (def.kind) {
            app.kaizen.source.WebFichaSchema.Kind.LIST -> ListField((value as? org.json.JSONArray)?.let { a -> List(a.length()) { fichaPart(def, a.get(it)) } } ?: listOf(fichaPart(def, value)))
            app.kaizen.source.WebFichaSchema.Kind.ITEMS -> {
                val o = value as? JSONObject ?: throw SpecException("ficha.${def.key}: debe ser un objeto con item y sus campos")
                val inner = o.keys().asSequence().filter { it != "item" }.toList()
                val bad = inner.filter { key -> def.items.none { it.key == key } }
                if (bad.isNotEmpty()) throw SpecException("ficha.${def.key}: campos que no son del esquema: ${bad.joinToString()}")
                if (def.items.any { it.required && !o.has(it.key) }) throw SpecException("ficha.${def.key}: falta ${def.items.first { it.required && !o.has(it.key) }.key}")
                ItemsField(o.getString("item"), def.items.filter { o.has(it.key) }.associate { it.key to fichaField(it, o.get(it.key)) })
            }
            else -> ScalarField(fichaPart(def, value))
        }

        /** Un selector con, si lo lleva, `split` (para un texto con varios valores) y `map` (código de la web → valor de AniList; todos sus valores tienen que existir). */
        private fun fichaPart(def: app.kaizen.source.WebFichaSchema.Field, value: Any): FichaPart {
            // Con alternativas (una lista de selectores) `split` y `map` pueden ir en cualquiera de ellas.
            val objects = (value as? org.json.JSONArray)?.let { a -> List(a.length()) { a.get(it) } }.orEmpty().filterIsInstance<JSONObject>() + listOfNotNull(value as? JSONObject)
            val split = objects.firstNotNullOfOrNull { it.optString("split").takeIf { s -> s.isNotBlank() } }?.let { regex ->
                try { Regex(regex) } catch (e: java.util.regex.PatternSyntaxException) { throw SpecException("ficha.${def.key}.split no válido: ${e.description}") }
            }
            val map = objects.flatMap { o -> o.optJSONObject("map")?.let { m -> m.keys().asSequence().map { k -> k to m.getString(k) }.toList() }.orEmpty() }.toMap()
            if (map.isNotEmpty()) {
                if (def.kind != app.kaizen.source.WebFichaSchema.Kind.ENUM) throw SpecException("ficha.${def.key}: solo las enumeraciones llevan map")
                map.values.filter { it !in def.values }.forEach { throw SpecException("ficha.${def.key}.map: «$it» no es un valor de AniList (${def.values.sorted().joinToString()})") }
            }
            return FichaPart(picks(value), split, map)
        }

        private fun search(o: JSONObject): SearchCaps {
            val text = when (o.getString("text")) {
                "alone" -> TextMode.ALONE
                "aloneWithYear" -> TextMode.ALONE_WITH_YEAR
                "always" -> TextMode.ALWAYS
                "none" -> TextMode.NONE
                else -> throw SpecException("search.text no válido: ${o.getString("text")}")
            }
            val filters = strings(o.optJSONArray("filters")).map { name ->
                app.kaizen.source.Filter.values().firstOrNull { it.name == name } ?: throw SpecException("filtro desconocido: $name")
            }.toSet()
            if (app.kaizen.source.Filter.TEXT in filters) throw SpecException("el texto va en search.text, no en filters")
            return SearchCaps(text, filters)
        }

        private fun chapters(ch: JSONObject): ChaptersSpec {
            val ajax = ch.optJSONObject("ajax")?.let(::ajax)
            val items = ch.optJSONObject("items")?.let { ChapterItemsSpec(it.getString("item"), picks(it.get("eid")), picks(it.get("number"))) }
            val html = ch.optJSONObject("html")?.let { HtmlChaptersSpec(formRequest(it.getJSONObject("request")), Regex(it.getString("number"))) }
            // Sin API, las tarjetas HTML son obligatorias.
            if (ajax == null && items == null && html == null && !ch.has("count")) listOf("item", "url", "number").forEach { if (!ch.has(it)) throw SpecException("capítulos: falta $it") }
            return ChaptersSpec(
                items = items,
                count = ch.opt("count")?.let(::picks),
                group = ch.optString("group").takeIf { it.isNotBlank() },
                groupOrder = ch.opt("groupOrder")?.let(::picks),
                item = ch.optString("item").takeIf { it.isNotBlank() },
                url = ch.opt("url")?.let(::picks),
                number = ch.opt("number")?.let(::picks),
                urlPrefix = ch.optString("urlPrefix"),
                ajax = ajax,
                html = html
            )
        }

        private fun formRequest(o: JSONObject): FormRequestSpec {
            val path = o.getString("path")
            // Solo una ruta del propio sitio: nunca un dominio.
            if (!path.startsWith("/") || path.startsWith("//")) throw SpecException("la ruta debe empezar por una sola /: $path")
            val form = o.getJSONObject("form")
            return FormRequestSpec(path, form.keys().asSequence().associateWith { key ->
                val value = form.get(key)
                if (value is String) FormValue(value, null) else FormValue(null, picks(value))
            })
        }

        private fun ajax(o: JSONObject): AjaxChaptersSpec {
            val from = o.opt("from")?.let(::picks)
            val url = optTemplate(o, "url")
            val idRegex = o.optString("idRegex").takeIf { it.isNotBlank() }?.let { Regex(it) }
            if (from == null && !(url != null && idRegex != null)) throw SpecException("ajax: falta from o url con idRegex")
            val request = o.optJSONObject("request")?.let { r ->
                val method = r.optString("method", "GET").uppercase()
                if (method != "GET" && method != "POST") throw SpecException("método no válido: $method")
                val token = r.optJSONObject("token")
                RequestSpec(method, stringMap(r.optJSONObject("form")), stringMap(r.optJSONObject("headers")),
                    token?.getString("sel"), token?.optString("attr", "content") ?: "content",
                    r.optBoolean("cookies", false), r.optBoolean("referrer", false))
            }
            val paged = o.optJSONObject("paged")?.let {
                PagedSpec(it.optDouble("perPage", 16.0).takeIf { n -> n >= 1 } ?: 16.0, it.getString("total"), it.getString("lastPage"),
                    it.optInt("maxPages", 200).coerceIn(1, 500))
            }
            return AjaxChaptersSpec(from, url, idRegex, request, paged, o.getString("array"), o.getString("field"),
                o.optString("date").takeIf { it.isNotBlank() }, o.optString("dateFormat", "yyyy-MM-dd HH:mm:ss"), o.optString("tz", "UTC"))
        }

        private fun stringMap(o: JSONObject?): Map<String, String> = o?.let { m -> m.keys().asSequence().associateWith { k -> m.getString(k) } }.orEmpty()

        private fun schedule(s: JSONObject) = ScheduleSpec(
            url = template(s.getString("url")),
            days = s.optInt("days", 7).coerceIn(1, 7),
            columns = s.optJSONObject("columns")?.let {
                ColumnsSpec(it.getString("section"), it.opt("day")?.let(::picks), strings(it.optJSONArray("dayNames")).map { n -> n.lowercase() },
                    it.getString("item"), picks(it.get("link")), picks(it.get("name")), it.opt("cover")?.let(::picks))
            },
            list = s.optJSONObject("list")?.let {
                ScheduleListSpec(it.getString("item"), picks(it.get("slug")), picks(it.get("aid")), picks(it.get("name")),
                    picks(it.get("category")), picks(it.get("day")))
            },
            scoresUrl = optTemplate(s, "scoresUrl")
        )

        private fun api(a: JSONObject): ApiSpec {
            val params = a.getJSONObject("params").let { p -> p.keys().asSequence().associateWith { k -> p.getString(k) } }
            listOf("page", "limit", "sort").forEach { if (it !in params) throw SpecException("api: falta el parámetro $it") }
            val sorts = a.getJSONObject("sortValues").let { p -> p.keys().asSequence().associateWith { k -> p.getString(k) } }
            val f = a.getJSONObject("fields")
            fun field(key: String) = f.optString(key).takeIf { it.isNotBlank() }
            return ApiSpec(
                url = template(a.getString("url")),
                limit = a.optInt("limit", 24).coerceIn(1, 100),
                params = params,
                sortValues = sorts,
                airing = a.optJSONObject("airing")?.let { it.getString("name") to it.getString("value") },
                scoreScale = a.optDouble("scoreScale", 1.0).takeIf { it > 0 } ?: 1.0,
                list = a.getString("list"),
                totalPages = a.getString("totalPages"),
                fields = ApiFields(f.getString("slug"), f.getString("name"), field("cover"), field("score"), field("votes"), field("year"))
            )
        }

        private fun recents(r: JSONObject): RecentsSpec {
            if (!r.has("slug") && !r.has("link")) throw SpecException("recents: falta slug o link")
            return recentsOf(r)
        }

        private fun recentsOf(r: JSONObject) = RecentsSpec(
            url = template(r.getString("url")),
            item = r.getString("item"),
            link = r.opt("link")?.let(::picks),
            slug = r.opt("slug")?.let(::picks),
            aid = r.opt("aid")?.let(::picks),
            eid = r.opt("eid")?.let(::picks),
            number = picks(r.get("number")),
            name = picks(r.get("name")),
            nameStrip = r.optString("nameStrip").takeIf { it.isNotBlank() }?.let { Regex(it) },
            cover = r.opt("cover")?.let(::picks),
            date = r.opt("date")?.let(::picks),
            relativeDate = r.opt("relativeDate")?.let(::picks),
            published = r.opt("published")?.let(::picks),
            publishedFormat = r.optString("publishedFormat", "yyyy-MM-dd HH:mm:ss"),
            publishedTz = r.optString("publishedTz", "UTC"),
            limit = r.optInt("limit", 30).coerceIn(1, 100),
            dates = r.optJSONObject("dates")?.let {
                DatesSpec(template(it.getString("url")), it.getString("linkField"), it.getString("dateField"),
                    it.optString("format", "yyyy-MM-dd'T'HH:mm:ss"), it.optString("tz", "UTC"))
            }
        )

        private fun servers(s: JSONObject): ServersSpec {
            val tag = s.optJSONObject("tag")
            val map = tag?.optJSONArray("map")?.let { arr ->
                List(arr.length()) { arr.getJSONObject(it).let { m -> m.getString("contains").lowercase() to m.getString("value") } }
            }.orEmpty()
            val text = s.optJSONArray("text")?.let { arr ->
                List(arr.length()) {
                    val o = arr.getJSONObject(it)
                    val array = o.has("regexArray")
                    TextServerSpec(Regex(o.getString(if (array) "regexArray" else "regex")), array, o.optString("name"),
                        o.optString("nameField").takeIf { f -> f.isNotBlank() }, o.optString("urlField").takeIf { f -> f.isNotBlank() })
                }
            }
            val embedded = s.optJSONObject("embedded")?.let {
                EmbeddedServersSpec(it.getString("marker"), it.getString("players"), it.getString("name"), it.getString("url"))
            }
            val request = s.optJSONObject("request")?.let(::formRequest)
            if (text == null && embedded == null && !(s.has("item") && s.has("url"))) throw SpecException("servidores: falta item/url")
            return ServersSpec(
                text = text,
                embedded = embedded,
                item = s.optString("item").takeIf { it.isNotBlank() },
                url = s.opt("url")?.let(::picks),
                base64 = s.optBoolean("base64", false),
                hex = s.optBoolean("hex", false),
                iframe = s.optBoolean("iframe", false),
                tagFrom = tag?.opt("from")?.let(::picks),
                tagMap = map,
                tagDefault = tag?.optString("default").orEmpty(),
                order = strings(s.optJSONArray("order")),
                request = request
            )
        }

        /** Los datos de la ficha que antes iban en `anime` y ahora solo se declaran en `ficha`: si una fuente los deja en `anime`, se rechaza. */
        internal val MOVED_TO_FICHA = listOf("name", "description", "airing", "cover", "score", "votes", "scoreScale", "year", "studio", "type", "state", "alwaysAiring")

        /** Sobre cuánto da una web su nota: se guarda siempre sobre 5. */
        private val SCORE_MAXIMUMS = setOf(5, 10)

        private val URL_HOST = Regex("""^https://([^/]+)""")
        // La llave de cierre va escapada: el motor de expresiones de Android (ICU) la rechaza sin escapar y el de la JVM de los tests no.
        private val MARKER = Regex("""\{([a-z]+)\}""")
        private val MARKERS = setOf("base", "slug", "number", "page", "genre", "query", "id", "day", "year", "sort", "status", "aid")

        /** Las plantillas solo pueden usar marcadores conocidos y nunca cambiar de host: empiezan por {base}/. */
        private fun template(value: String): String {
            if (!value.startsWith("{base}/")) throw SpecException("la plantilla debe empezar por {base}/: $value")
            checkMarkers(value)
            return value
        }

        private fun checkMarkers(value: String) =
            MARKER.findAll(value).forEach { if (it.groupValues[1] !in MARKERS) throw SpecException("marcador desconocido {${it.groupValues[1]}}") }

        /** Fragmento de consulta opcional ({genre}, {year}...); no es una URL, se añade a la del listado. */
        private fun fragment(o: JSONObject, key: String): String? = o.optString(key).takeIf { it.isNotBlank() }?.also { checkMarkers(it) }

        /** Plantilla de imagen: https y del propio dominio de la fuente o un subdominio suyo (la CDN), sin usuario@; solo marcadores conocidos. */
        private fun imageTemplate(o: JSONObject, key: String, host: String): String {
            val value = o.getString(key)
            if (!value.startsWith("https://")) throw SpecException("la imagen $key debe ser https")
            checkMarkers(value)
            // El host se lee con un analizador de URL (no del texto): sin usuario@ ni puertos raros.
            val uri = try {
                java.net.URI(value.replace(MARKER, "x"))
            } catch (e: java.net.URISyntaxException) {
                throw SpecException("la imagen $key no es una URL válida")
            }
            val imageHost = uri.host
            if (uri.userInfo != null || imageHost == null || !(imageHost == host || imageHost.endsWith(".$host")))
                throw SpecException("la imagen $key no es del dominio de la fuente")
            return value
        }

        private fun optTemplate(o: JSONObject, key: String): String? = o.optString(key).takeIf { it.isNotBlank() }?.let(::template)

        private fun strings(array: JSONArray?): List<String> = array?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()

        private fun pick(value: Any): Pick {
            // «media.title» es lo mismo que {"sel": "media.title"}.
            if (value is String) return Pick(value, null, null, false)
            return pick(value as? JSONObject ?: throw SpecException("selector no válido"))
        }

        private fun pick(o: JSONObject) = Pick(
            sel = o.optString("sel").takeIf { it.isNotBlank() },
            attr = o.optString("attr").takeIf { it.isNotBlank() },
            regex = o.optString("regex").takeIf { it.isNotBlank() }?.let { Regex(it) },
            all = o.optBoolean("all", false)
        )

        /** Un objeto, o una lista de objetos como alternativas. */
        internal fun picks(value: Any): Picks = when (value) {
            is JSONObject, is String -> Picks(listOf(pick(value)))
            is JSONArray -> Picks(List(value.length()) { pick(value.get(it)) })
            else -> throw SpecException("selector no válido")
        }
    }
}
