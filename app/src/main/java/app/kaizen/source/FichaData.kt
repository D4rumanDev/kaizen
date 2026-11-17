package app.kaizen.source

import org.json.JSONArray
import org.json.JSONObject

/**
 * Los datos de una serie tal como los dice **una fuente**: el registro JSON con la estructura de `Media` de AniList ([WebFichaSchema]) más de dónde sale
 * ([fuente], [slug]). Es el **único lector** de ese JSON: la serie de la app, las columnas indexadas de [WebFicha], las pistas del emparejado con AniList
 * ([AnilistMatcher], [AnilistSync]) y la consulta leen sus datos de aquí, no del JSON a mano.
 *
 * Vale también para una ficha de AniList (`AnimeFicha`, [ofAnilist]): comparte los campos de identificación (`idMal`, `title`, `synonyms`, `format`, `status`,
 * `episodes`, `season`, `startDate`…), así que el emparejador puede comparar dos [FichaData] campo a campo, sabiendo de qué fuente viene cada una.
 * Los datos que solo da la web (`studios` como lista de nombres, `web.score`…) quedan vacíos en una ficha de AniList: otra forma de guardarlos, no un dato que falte.
 *
 * Un dato que la fuente no da es null o una lista vacía, nunca un texto «null».
 */
class FichaData(
    /** El registro: se lee con los getters de esta clase; no se modifica. */
    val media: JSONObject,
    val fuente: String,
    val slug: String,
    /** Cuándo se guardó (milisegundos), para saber si es más nuevo que una decisión ya tomada; 0 si no se sabe. */
    val at: Long = 0L,
    /** El texto en español de Animeoteca de una ficha de AniList, si ya se miró y tiene algo. */
    val es: AnimeotecaText? = null
) {
    // ---- identificación

    /** El id de MyAnimeList (el mismo que `idMal` de AniList): enlaza dos fichas sin adivinar por el nombre. */
    val idMal: Long? get() = media.long("idMal")
    val url: String? get() = media.optJSONObject("web")?.text("url")

    // ---- nombres

    private val title: JSONObject? get() = media.optJSONObject("title")
    val romaji: String? get() = title?.text("romaji")
    val english: String? get() = title?.text("english")
    val native: String? get() = title?.text("native")
    val sinonimos: List<String> get() = media.strings("synonyms")

    /** Todos los nombres de la serie, sin repetir: romaji, inglés, nativo y sinónimos. */
    val nombres: List<String> get() = (listOfNotNull(romaji, english, native) + sinonimos).distinct()

    /** Los nombres con los que buscar además del título de la ficha: el inglés primero, luego el romaji y los sinónimos (sin el nativo: AniList no lo busca bien). */
    val alternativas: List<String> get() = (listOfNotNull(english, romaji) + sinonimos).distinct()

    /** Todos los nombres para buscar con un «contiene»: los de letras latinas normalizados y los que no (japonés) tal cual, entre barras: `|nombre|otro|`. */
    val nombresBuscables: String
        get() = "|" + nombres.flatMap { n ->
            val norm = AnilistMatcher.normalize(n)
            listOfNotNull(norm.takeIf { it.isNotBlank() }, n.takeIf { norm.isBlank() })
        }.distinct().joinToString("|") + "|"

    // ---- clasificación

    val formato: String? get() = media.text("format")
    val estado: String? get() = media.text("status")
    val episodios: Int? get() = media.int("episodes")
    val duracion: Int? get() = media.int("duration")
    val temporada: String? get() = media.text("season")
    val adulto: Boolean? get() = if (media.isNull("isAdult")) null else media.optBoolean("isAdult")
    val pais: String? get() = media.text("countryOfOrigin")

    // ---- fechas

    private val start: JSONObject? get() = media.optJSONObject("startDate")

    /** El año de la fecha de inicio. */
    val anioInicio: Int? get() = start?.int("year")

    /** El año de estreno: el de la fecha de inicio o, si falta, el de la temporada. */
    val anio: Int? get() = anioInicio ?: media.int("seasonYear")

    /** La fecha de inicio como número `AAAAMMDD` (0 en lo que falte) para ordenar y comparar; null si no hay ni el año. */
    val inicio: Int? get() = anioInicio?.let { y -> y * 10_000 + (start?.int("month") ?: 0) * 100 + (start?.int("day") ?: 0) }

    // ---- contenido

    val generos: List<String> get() = media.strings("genres")
    /** Los estudios: en una web, la lista de nombres; en AniList (`studios.edges`), los nombres con el principal primero. */
    val estudios: List<String> by lazy {
        val studios = media.opt("studios")
        if (studios is JSONObject) {
            val edges = studios.optJSONArray("edges").objects()
            (edges.filter { it.optBoolean("isMain") } + edges.filterNot { it.optBoolean("isMain") }).mapNotNull { it.optJSONObject("node")?.text("name") }.distinct()
        } else media.strings("studios")
    }

    /** El estudio principal (el primero de [estudios]). */
    val estudio: String? get() = estudios.firstOrNull()
    val productoras: List<String> get() = media.strings("producers")

    /** La sinopsis: en el primer idioma que la tiene ([WebFichaSchema.LANGUAGES]) si es un registro de web; el texto tal cual si es de AniList. */
    val descripcion: String?
        get() {
            val d = media.opt("description")
            return when {
                d is JSONObject -> WebFichaSchema.LANGUAGES.firstNotNullOfOrNull { d.text(it) }
                d is String -> d.trim().takeIf { it.isNotEmpty() }
                else -> null
            }
        }

    /** La sinopsis solo si está en español (la de una web que la da en ese idioma); la de AniList es inglesa y aquí no cuenta. */
    val descripcionEs: String? get() = (media.opt("description") as? JSONObject)?.text("es")

    /** La portada (la más grande que dé), solo si es https. */
    val portada: String? get() = media.optJSONObject("coverImage")?.let { it.text("extraLarge") ?: it.text("large") }?.takeIf { it.startsWith("https://") }

    /** El banner (imagen ancha), solo si es https. */
    val banner: String? get() = media.text("bannerImage")?.takeIf { it.startsWith("https://") }

    /**
     * Los personajes: en una web, `characters` (`name`, `role`, el primer `voiceActors`); en AniList, las aristas de `characters` (nombre completo o nativo, rol y la
     * primera voz japonesa). Sin nombre no hay personaje.
     */
    val personajes: List<Personaje> by lazy {
        val raw = media.opt("characters")
        if (raw is JSONObject) raw.optJSONArray("edges").objects().mapNotNull { e ->
            val name = e.optJSONObject("node")?.optJSONObject("name")?.let { it.text("full") ?: it.text("native") } ?: return@mapNotNull null
            Personaje(name, e.text("role"), e.optJSONArray("voiceActors").objects().firstNotNullOfOrNull { it.optJSONObject("name")?.text("full") })
        } else (raw as? JSONArray).objects().mapNotNull { c -> Personaje(c.text("name") ?: return@mapNotNull null, c.text("role"), c.strings("voiceActors").firstOrNull()) }
    }

    /** Otros animes de la franquicia (precuela, secuela, historia paralela…), sin mangas ni novelas; precuelas y secuelas primero. Solo las da AniList. */
    val relaciones: List<Relacion> by lazy {
        media.optJSONObject("relations")?.optJSONArray("edges").objects().mapNotNull { e ->
            val node = e.optJSONObject("node") ?: return@mapNotNull null
            val formato = node.text("format")
            if (formato !in WebFichaSchema.FORMATS) return@mapNotNull null
            Relacion(node.long("id") ?: return@mapNotNull null, e.text("relationType") ?: return@mapNotNull null, node.optJSONObject("title")?.text("romaji") ?: return@mapNotNull null, formato)
        }.sortedBy { RELATION_ORDER.indexOf(it.tipo).let { i -> if (i < 0) RELATION_ORDER.size else i } }
    }

    // ---- lo que solo da AniList

    /** El id del anime en AniList; null en una ficha de web. */
    val anilistId: Long? get() = media.long("id")

    /** La nota media de AniList, de 0 a 100 (la de la web va en [nota], sobre 5). */
    val puntuacion: Int? get() = media.int("averageScore")

    val proximoEpisodio: Int? get() = media.optJSONObject("nextAiringEpisode")?.int("episode")
    val proximaEmision: Long? get() = media.optJSONObject("nextAiringEpisode")?.long("airingAt")

    class Personaje(val nombre: String, val rol: String?, val voz: String?)

    /** [tipo] es el `relationType` de AniList (PREQUEL, SEQUEL, SIDE_STORY…) y [formato] el formato del anime relacionado. */
    class Relacion(val anilistId: Long, val tipo: String, val titulo: String, val formato: String?)

    // ---- lo que no es de la ficha de AniList

    private val web: JSONObject? get() = media.optJSONObject("web")

    /** La nota de la web sobre 5 (la de las estrellas de la app); null si no la da. */
    val nota: Double? get() = web?.takeIf { !it.isNull("score") }?.optDouble("score")?.takeIf { it.isFinite() }
    val votos: Int? get() = web?.int("votes")
    val calidad: String? get() = web?.text("quality")

    // ---- lo que sirve para emparejar

    /** Sirve de pista para emparejar con AniList: trae el id de MyAnimeList o algún otro nombre. */
    val aportaPistas: Boolean get() = idMal != null || alternativas.isNotEmpty()

    // ---- consulta de un campo concreto

    /**
     * Un solo dato de la ficha, sin cargar nada más: [campo] es el nombre de un getter de esta clase (`generos`, `anio`, `nota`, `alternativas`, `fuente`…, ver [CAMPOS])
     * o, si no lo es, una ruta del JSON con claves separadas por punto y el índice de las listas entre corchetes o como número (`genres[0]`, `web.score`, `startDate.year`,
     * `title.native`). Una ruta vacía da el JSON entero. Un texto vale tal cual; una lista o un objeto, como JSON; un dato que no hay, null (un nombre desconocido o mal escrito también da null: no se distingue de un dato ausente).
     */
    fun valor(campo: String): String? {
        val name = campo.trim()
        CAMPOS[name]?.let { return render(it(this)) }
        if (name.isEmpty()) return media.toString()
        var current: Any? = media
        for (step in STEP.findAll(name)) {
            val key = step.groupValues[1]
            val index = step.groupValues[2].ifEmpty { key.takeIf { k -> k.all(Char::isDigit) } }?.toIntOrNull()
            current = when {
                current is JSONArray && index != null -> current.opt(index)
                current is JSONObject && key.isNotEmpty() -> current.opt(key)
                else -> null
            } ?: return null
            if (current === JSONObject.NULL) return null
        }
        return current.toString()
    }

    /** Varios datos de una vez, cada uno por su nombre o ruta: `valores("fuente", "generos", "web.score")`. Mantiene el orden pedido. */
    fun valores(vararg campos: String): Map<String, String?> = campos.associateWith { valor(it) }

    private fun render(value: Any?): String? = when (value) {
        null -> null
        is List<*> -> JSONArray(value).toString()
        else -> value.toString()
    }

    companion object {
        private val STEP = Regex("""([^.\[\]]+)|\[(\d+)\]""")

        /** Los getters que se pueden pedir por su nombre en [valor]: una sola lista, la de las propiedades de esta clase. */
        val CAMPOS: Map<String, (FichaData) -> Any?> = mapOf(
            "fuente" to { it.fuente }, "slug" to { it.slug }, "url" to { it.url }, "idMal" to { it.idMal },
            "romaji" to { it.romaji }, "english" to { it.english }, "native" to { it.native },
            "sinonimos" to { it.sinonimos }, "nombres" to { it.nombres }, "alternativas" to { it.alternativas },
            "formato" to { it.formato }, "estado" to { it.estado }, "episodios" to { it.episodios }, "duracion" to { it.duracion },
            "temporada" to { it.temporada }, "adulto" to { it.adulto }, "pais" to { it.pais },
            "anio" to { it.anio }, "anioInicio" to { it.anioInicio }, "inicio" to { it.inicio },
            "generos" to { it.generos }, "estudios" to { it.estudios }, "productoras" to { it.productoras },
            "descripcion" to { it.descripcion }, "portada" to { it.portada },
            "nota" to { it.nota }, "votos" to { it.votos }, "calidad" to { it.calidad },
            "anilistId" to { it.anilistId }, "puntuacion" to { it.puntuacion }, "banner" to { it.banner },
            "personajes" to { it.personajes.map { p -> p.nombre } }, "relaciones" to { it.relaciones.map { r -> r.titulo } },
            "proximoEpisodio" to { it.proximoEpisodio }, "proximaEmision" to { it.proximaEmision }
        )

        /** La ficha guardada de [fuente] con ese [slug] (su identidad), o null si no se ha guardado: `FichaData.get(dao, "jkanime", "black-clover")?.valor("generos")`. */
        fun get(dao: WebFichaDao, fuente: String, slug: String): FichaData? = dao.get(fuente, slug)?.let { runCatching { of(it) }.getOrNull() }

        /** La ficha guardada de una web. */
        fun of(row: WebFicha): FichaData = FichaData(JSONObject(row.jsonText()), row.fuente, row.slug, row.descargada)

        private val RELATION_ORDER = listOf("PREQUEL", "SEQUEL", "PARENT", "SIDE_STORY", "SPIN_OFF", "ALTERNATIVE", "SUMMARY", "CHARACTER", "OTHER")

        /** La ficha de AniList guardada (con su texto en español, si lo tiene): su fuente es `anilist` y su slug el id; null si el JSON no se puede interpretar. */
        fun ofAnilist(row: AnimeFicha): FichaData? = ofAnilistJson(
            row.jsonText(), row.es?.let { runCatching { AnimeotecaText.fromJson(AnimeFicha.gunzip(it)) }.getOrNull() }?.takeUnless { it.isEmpty }, row.descargada
        )

        /** La ficha de AniList a partir de su JSON; null si no es un objeto con `id`. */
        fun ofAnilistJson(json: String, es: AnimeotecaText? = null, at: Long = 0L): FichaData? = runCatching {
            val media = JSONObject(json)
            media.long("id")?.let { FichaData(media, ANILIST, it.toString(), at, es) }
        }.getOrNull()

        const val ANILIST = "anilist"
    }
}

/** Los objetos de una lista JSON (se saltan los que no lo son); lista vacía si no hay lista. */
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
