package app.kaizen.source

import app.kaizen.source.spec.FichaField
import app.kaizen.source.spec.FichaPart
import app.kaizen.source.spec.FichaSpec
import app.kaizen.source.spec.ItemsField
import app.kaizen.source.spec.ListField
import app.kaizen.source.spec.Node
import app.kaizen.source.spec.ScalarField
import org.json.JSONArray
import org.json.JSONObject

/**
 * **El esquema único** del registro que se guarda de cada ficha de cada web (`WebFicha.json`). Es la única definición: de esta tabla salen las claves
 * que puede declarar el bloque `ficha` de una fuente (y se rechaza lo que no esté), el JSON vacío con todas las claves, cómo se lee cada valor, las
 * enumeraciones válidas y las columnas indexadas. Añadir un campo es añadir una línea aquí; una fuente nueva solo puede rellenar estos campos y así
 * todas entregan lo mismo y de la misma manera.
 *
 * La estructura es la de `Media` de AniList (`title`, `idMal`, `startDate`, `studios`…), con lo que una web puede dar. **Todas las claves están siempre**:
 * lo que la web no da va como `null` (valor suelto), `[]` (lista) o con sus partes a `null` (fechas, títulos…). Lo que no es de AniList (de qué web viene,
 * la nota de la web, calidad, edad, audio) va en `web`. Ver `docs/wiki/webficha-esquema.md`.
 */
internal object WebFichaSchema {
    enum class Kind { TEXT, URL, LONG, INT, DECIMAL, BOOL, DATE, ENUM, LIST, ITEMS, BY_LANGUAGE }

    /**
     * Un campo. [key] es el nombre que usa el bloque `ficha` de la fuente; [path] dónde va en el JSON; [values] los valores válidos de una enumeración;
     * [items] los campos de cada elemento de una lista de objetos; [system] los que rellena la app y no una fuente.
     */
    class Field(
        val key: String,
        val path: List<String>,
        val kind: Kind,
        val values: Set<String> = emptySet(),
        val required: Boolean = false,
        val items: List<Field> = emptyList(),
        val system: Boolean = false
    )

    // Valores de AniList (comprobados por introspección en graphql.anilist.co el 2026-11-04).
    val FORMATS = setOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC")
    val STATUSES = setOf("FINISHED", "RELEASING", "NOT_YET_RELEASED", "CANCELLED", "HIATUS")
    val SEASONS = setOf("WINTER", "SPRING", "SUMMER", "FALL")
    val SOURCES = setOf(
        "ORIGINAL", "MANGA", "LIGHT_NOVEL", "VISUAL_NOVEL", "VIDEO_GAME", "OTHER", "NOVEL", "DOUJINSHI", "ANIME", "WEB_NOVEL", "LIVE_ACTION", "GAME",
        "COMIC", "MULTIMEDIA_PROJECT", "PICTURE_BOOK"
    )
    val ROLES = setOf("MAIN", "SUPPORTING", "BACKGROUND")

    /** País de origen (código ISO 3166-1, como `countryOfOrigin` de AniList): los que dan las webs de anime. */
    val COUNTRIES = setOf("JP", "KR", "CN", "TW", "HK", "US", "GB", "FR", "DE", "ES", "IT", "CA", "AU", "RU", "BR", "MX", "TH", "IN", "ID", "PH", "VN")

    /** Idiomas en que una web puede dar la sinopsis: el registro lleva un texto por idioma. */
    val LANGUAGES = listOf("es", "en")

    private fun f(key: String, vararg path: String, kind: Kind = Kind.TEXT, values: Set<String> = emptySet(), required: Boolean = false, system: Boolean = false) =
        Field(key, path.toList(), kind, values, required, system = system)

    /** Los campos, en el orden en que salen en el JSON. */
    val FIELDS: List<Field> = listOf(
        f("idMal", "idMal", kind = Kind.LONG),
        f("romaji", "title", "romaji", required = true),
        f("english", "title", "english"),
        f("native", "title", "native"),
        f("synonyms", "synonyms", kind = Kind.LIST),
        f("format", "format", kind = Kind.ENUM, values = FORMATS),
        f("status", "status", kind = Kind.ENUM, values = STATUSES),
        f("episodes", "episodes", kind = Kind.INT),
        f("duration", "duration", kind = Kind.INT),
        f("season", "season", kind = Kind.ENUM, values = SEASONS),
        f("seasonYear", "seasonYear", kind = Kind.INT),
        f("startDate", "startDate", kind = Kind.DATE),
        f("endDate", "endDate", kind = Kind.DATE),
        f("countryOfOrigin", "countryOfOrigin", kind = Kind.ENUM, values = COUNTRIES),
        f("sourceMaterial", "source", kind = Kind.ENUM, values = SOURCES),
        f("genres", "genres", kind = Kind.LIST, system = true),
        f("studios", "studios", kind = Kind.LIST),
        f("producers", "producers", kind = Kind.LIST),
        Field(
            "characters", listOf("characters"), Kind.ITEMS, items = listOf(
                f("name", "name", required = true), f("native", "native"), f("role", "role", kind = Kind.ENUM, values = ROLES), f("voiceActors", "voiceActors", kind = Kind.LIST)
            )
        ),
        Field("staff", listOf("staff"), Kind.ITEMS, items = listOf(f("name", "name", required = true), f("role", "role"))),
        f("description", "description", kind = Kind.BY_LANGUAGE),
        f("isAdult", "isAdult", kind = Kind.BOOL),
        f("cover", "coverImage", "extraLarge", kind = Kind.URL),
        f("banner", "bannerImage", kind = Kind.URL),
        f("trailer", "trailer", "id"),
        f("trailerSite", "trailer", "site", system = true),
        f("fuente", "web", "fuente", system = true),
        f("slug", "web", "slug", system = true),
        f("url", "web", "url", system = true),
        f("score", "web", "score", kind = Kind.DECIMAL),
        f("votes", "web", "votes", kind = Kind.INT),
        f("quality", "web", "quality"),
        f("ageRating", "web", "ageRating"),
        f("audio", "web", "audio", kind = Kind.LIST)
    )

    /** Claves que no son un campo sino un dato de la propia fuente para leer otros: el idioma de la sinopsis y el máximo de la nota de la web. */
    val LITERALS = setOf("descriptionLanguage", "scoreMax")

    /** Las claves que puede llevar el bloque `ficha` de una fuente. */
    val KEYS: Set<String> = FIELDS.filter { !it.system }.map { it.key }.toSet() + LITERALS

    /** La nota de la web se guarda siempre sobre 5, como las estrellas de la app, sea cual sea su escala. */
    const val SCORE_SCALE = 5.0

    fun field(key: String): Field = FIELDS.first { it.key == key }

    // ---- el JSON vacío

    private fun defaultOf(field: Field): Any = when (field.kind) {
        Kind.LIST, Kind.ITEMS -> JSONArray()
        Kind.DATE -> JSONObject().put("year", JSONObject.NULL).put("month", JSONObject.NULL).put("day", JSONObject.NULL)
        Kind.BY_LANGUAGE -> JSONObject().also { o -> LANGUAGES.forEach { o.put(it, JSONObject.NULL) } }
        else -> JSONObject.NULL
    }

    private fun put(root: JSONObject, path: List<String>, value: Any) {
        var target = root
        path.dropLast(1).forEach { key -> target = target.optJSONObject(key) ?: JSONObject().also { target.put(key, it) } }
        target.put(path.last(), value)
    }

    /** El registro con todas las claves y sin ningún dato: de aquí parte cada ficha. */
    fun empty(): JSONObject = JSONObject().also { root -> FIELDS.forEach { put(root, it.path, defaultOf(it)) } }

    // ---- lectura de una ficha según el bloque `ficha` de la fuente

    private val DATE = Regex("""(\d{4})(?:-(\d{2})-(\d{2}))?""")

    /** «3 de octubre de 2017», «03 de Octubre de 2017», «Viernes, 16 de Junio de 2023»: el día de la semana, si lo hay, se ignora. */
    private val SPANISH_DATE = Regex("""(\d{1,2}) de ([A-Za-zñÑ]+) de (\d{4})""")
    private val MONTHS_ES = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    /** El registro de la ficha leída en [node] según [spec]; null si la web no dio el título (no es una ficha). [genres], [fuente], [slug] y [url] los aporta quien ya los tiene. */
    fun build(spec: FichaSpec, node: Node, genres: List<String>, fuente: String, slug: String, url: String): JSONObject? {
        val media = empty()
        for (field in FIELDS) {
            if (field.system) continue
            val declared = spec.fields[field.key] ?: continue
            val value = read(field, declared, node) ?: continue
            if (field.kind == Kind.BY_LANGUAGE) put(media, field.path + (spec.descriptionLanguage ?: continue), value) else put(media, field.path, value)
        }
        if (media.optJSONObject("title")?.text("romaji") == null) return null
        // Los otros nombres no repiten los tres principales.
        val own = listOfNotNull(media.optJSONObject("title")?.text("romaji"), media.optJSONObject("title")?.text("english"), media.optJSONObject("title")?.text("native"))
        media.put("synonyms", JSONArray(media.strings("synonyms").filter { it !in own }.distinct()))
        media.put("genres", JSONArray(genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct()))
        // La nota de la web, sobre 5 como las estrellas de la app.
        media.optJSONObject("web")?.let { web ->
            val raw = web.opt("score") as? Number
            val max = spec.scoreMax
            if (raw != null && max != null && max > 0) web.put("score", outOfFive(raw.toDouble(), max)) else web.put("score", JSONObject.NULL)
            web.put("fuente", fuente).put("slug", slug).put("url", url)
        }
        if (media.optJSONObject("trailer")?.text("id") != null) media.getJSONObject("trailer").put("site", "youtube")
        return media
    }

    /** [raw] (sobre [max]) pasado a una escala de 5 y redondeado a dos decimales con el redondeo de siempre (5,87 sobre 10 = 2,94, no 2,93 por coma flotante). */
    private fun outOfFive(raw: Double, max: Int): Double =
        java.math.BigDecimal(raw.toString()).multiply(java.math.BigDecimal(SCORE_SCALE.toInt())).divide(java.math.BigDecimal(max), 2, java.math.RoundingMode.HALF_UP).toDouble()

    /** Solo la nota de la web (sobre 5) y sus votos, sin leer el resto: para la nota ligera de una página pesada. */
    fun rating(spec: FichaSpec, node: Node): Pair<Float, Int>? {
        val score = spec.fields["score"]?.let { read(field("score"), it, node) } as? Double ?: return null
        val votes = (spec.fields["votes"]?.let { read(field("votes"), it, node) } as? Int) ?: return null
        val max = spec.scoreMax?.takeIf { it > 0 } ?: return null
        return outOfFive(score, max).toFloat() to votes
    }

    private fun read(field: Field, declared: FichaField, node: Node): Any? = when (field.kind) {
        Kind.LIST -> (declared as ListField).parts.flatMap { values(it, node) }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeIf { it.isNotEmpty() }?.let(::JSONArray)
        Kind.ITEMS -> items(field, declared as ItemsField, node).takeIf { it.length() > 0 }
        else -> scalar(field, (declared as ScalarField).part, node)
    }

    /** Todos los valores de una parte (cada elemento que casa), partidos por su separador si lo tiene. */
    private fun values(part: FichaPart, node: Node): List<String> =
        part.picks.all(node).flatMap { text -> part.split?.let { text.split(it) } ?: listOf(text) }

    private fun items(field: Field, declared: ItemsField, node: Node): JSONArray {
        val out = JSONArray()
        val seen = HashSet<String>()
        for (element in node.select(declared.item)) {
            val entry = JSONObject()
            field.items.forEach { inner -> entry.put(inner.key, if (inner.kind == Kind.LIST) JSONArray() else JSONObject.NULL) }
            field.items.forEach { inner ->
                val value = declared.fields[inner.key]?.let { read(inner, it, element) } ?: return@forEach
                entry.put(inner.key, value)
            }
            val name = entry.text("name") ?: continue
            if (seen.add(name + "|" + entry.text("role"))) out.put(entry)
        }
        return out
    }

    /** El valor de [field]: el de la primera alternativa que da un valor interpretable (una que da texto pero no es una fecha o un código conocido no tapa a la siguiente). */
    private fun scalar(field: Field, part: FichaPart, node: Node): Any? = part.picks.items.firstNotNullOfOrNull { pick ->
        pick.apply(node)?.trim()?.takeIf { it.isNotEmpty() }?.let { convert(field, part, it) }
    }

    private fun convert(field: Field, part: FichaPart, text: String): Any? = when (field.kind) {
        Kind.TEXT, Kind.BY_LANGUAGE -> text
        Kind.URL -> text.takeIf { it.startsWith("https://") }
        Kind.LONG -> text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong()?.takeIf { it > 0 }
        Kind.INT -> text.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()?.takeIf { it > 0 }
        Kind.DECIMAL -> text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        Kind.BOOL -> when (text.lowercase()) { "true" -> true; "false" -> false; else -> null }
        Kind.DATE -> date(text)
        // Con tabla, el código de la web pasa por ella y uno que no está se deja a null: no se adivina. Sin tabla, el valor tiene que ser ya de AniList.
        Kind.ENUM -> if (part.map.isNotEmpty()) mapped(part.map, text) else text.uppercase().takeIf { it in field.values }
        else -> null
    }

    /** El valor de [map] para [text]: igual, o igual sin mayúsculas ni acentos («En emisión» = «en emision»); una web escribe el mismo estado de varias formas. */
    private fun mapped(map: Map<String, String>, text: String): String? =
        map[text] ?: AnilistMatcher.normalize(text).let { wanted -> map.entries.firstOrNull { AnilistMatcher.normalize(it.key) == wanted }?.value }

    /** «2026-07-08» o una fecha en español como `{year, month, day}` con lo que haya; null si no hay ni el año. */
    private fun date(text: String): JSONObject? {
        val result = JSONObject().put("year", JSONObject.NULL).put("month", JSONObject.NULL).put("day", JSONObject.NULL)
        SPANISH_DATE.find(text)?.let { es ->
            val month = MONTHS_ES.indexOf(es.groupValues[2].lowercase()) + 1
            val day = es.groupValues[1].toInt()
            result.put("year", es.groupValues[3].toInt())
            // Un mes que no es un mes (o un día imposible) no se adivina: queda solo el año.
            if (month > 0 && day in 1..31) result.put("month", month).put("day", day)
            return result
        }
        val match = DATE.find(text) ?: return null
        result.put("year", match.groupValues[1].toInt())
        match.groupValues[2].toIntOrNull()?.let { result.put("month", it) }
        match.groupValues[3].toIntOrNull()?.let { result.put("day", it) }
        return result
    }
}

// ---- lectura de un registro sin confundir `null` con el texto «null» (org.json devuelve «null» en optString de un valor nulo)

internal fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

internal fun JSONObject.int(key: String): Int? = if (isNull(key)) null else optInt(key).takeIf { it != 0 }

internal fun JSONObject.long(key: String): Long? = if (isNull(key)) null else optLong(key).takeIf { it > 0 }

internal fun JSONObject.strings(key: String): List<String> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { i -> if (a.isNull(i)) null else a.optString(i).trim().takeIf { s -> s.isNotEmpty() } } }.orEmpty()
