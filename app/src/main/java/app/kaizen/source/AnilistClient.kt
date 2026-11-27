package app.kaizen.source

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Lo que el emparejado y la cola necesitan de AniList. Las pruebas lo sustituyen. */
internal interface AnilistApi {
    /** Una lista de candidatos por cada título pedido (vacía si no hay resultado). */
    suspend fun search(titles: List<String>): List<List<AnilistMatcher.Candidate>>

    /** La ficha de cada anime pedido que AniList conozca. */
    suspend fun fichas(ids: List<Long>): List<AnilistFichaRaw>

    /** El anime de AniList que corresponde a cada id de MyAnimeList pedido (`Media(idMal)`); los que AniList no conoce no aparecen. */
    suspend fun byMal(ids: List<Long>): Map<Long, AnilistMatcher.Candidate> = emptyMap()

    /**
     * Los [count] animes mejor puntuados del ranking público (TV, película, OVA y ONA, sin adultos) con popularidad mayor de [minPopularity], de mayor a menor nota; solo los que
     * se están emitiendo si [airing].
     */
    suspend fun top(count: Int, minPopularity: Int, airing: Boolean = false): AnilistTops = AnilistTops(emptyList(), true)
}

/** El ranking pedido y si llegó entero ([complete]: se alcanzó lo pedido o AniList no tiene más; false si una página no dio datos y la lista quedó corta). */
internal class AnilistTops(val items: List<AnilistTop>, val complete: Boolean)

/** Un anime del ranking público de AniList: [score] de 0 a 100. */
internal class AnilistTop(val id: Long, val title: String, val score: Int?, val year: Int?)

/** La ficha tal como llegó ([json]) y lo que se deriva de ella para la tabla. */
internal class AnilistFichaRaw(
    val id: Long,
    val malId: Long?,
    val json: String,
    val romaji: String?,
    val english: String?,
    val native: String?,
    val year: Int?,
    val format: String?,
    val score: Int?,
    val status: String?
)

/**
 * Habla con la API GraphQL pública de AniList. [post] envía el cuerpo JSON y devuelve la respuesta (con el ritmo, los reintentos y
 * el User-Agent de [AnilistHttp]); aquí solo se construyen las consultas y se lee la respuesta, así que se prueba sin red.
 */
internal class AnilistClient(private val post: suspend (String) -> String) : AnilistApi {
    override suspend fun search(titles: List<String>): List<List<AnilistMatcher.Candidate>> = search(titles, unfiltered = false)

    /** [unfiltered]: sin el filtro de formato de [AnilistMatcher.searchPlan] (el reintento cuando con él no sale nada). */
    private suspend fun search(titles: List<String>, unfiltered: Boolean): List<List<AnilistMatcher.Candidate>> {
        if (titles.isEmpty()) return emptyList()
        val vars = titles.indices.joinToString(", ") { "\$t$it: String" }
        // Una película, un OVA o un especial se busca sin la palabra de formato y solo entre los formatos que le tocan ([AnilistMatcher.searchPlan]).
        val plans = titles.map { if (unfiltered) null else AnilistMatcher.searchPlan(it) }
        val body = titles.indices.joinToString("\n") { i ->
            val filter = plans[i]?.let { ", format_in: [${it.second.joinToString(", ")}]" }.orEmpty()
            "s$i: Page(perPage: $PER_SEARCH) { media(search: \$t$i, type: ANIME$filter, sort: SEARCH_MATCH) { $SEARCH_FIELDS } }"
        }
        val variables = JsonObject().also { v -> titles.forEachIndexed { i, t -> v.addProperty("t$i", plans[i]?.first ?: t) } }
        val data = request("query ($vars) { $body }", variables)
        // AniList rechaza con un 400 todo el lote si un solo texto no le gusta: se pide de uno en uno para saber cuál.
        if (data == null && titles.size > 1) return titles.flatMap { search(listOf(it), unfiltered) }
        val found = titles.indices.map { i -> data?.get("s$i").asObjectOrNull()?.get("media")?.takeIf { it.isJsonArray }?.asJsonArray.candidates() }.toMutableList()
        // Una serie cuyo nombre lleva la palabra («Special A») no está entre los formatos filtrados: si con el filtro no sale nada que se parezca al título, se repite el título entero sin él
        // y se suman sus resultados.
        val weak = titles.indices.filter { i -> plans[i] != null && found[i].none { AnilistMatcher.score(AnilistMatcher.normalize(titles[i]), it) >= AnilistMatcher.PROBABLE_FROM } }
        if (data != null && weak.isNotEmpty()) search(weak.map { titles[it] }, unfiltered = true).forEachIndexed { k, list -> found[weak[k]] = (found[weak[k]] + list).distinctBy { it.id } }
        return found
    }

    override suspend fun fichas(ids: List<Long>): List<AnilistFichaRaw> {
        if (ids.isEmpty()) return emptyList()
        val variables = JsonObject().also { v -> v.add("ids", JsonArray().also { a -> ids.forEach(a::add) }) }
        val data = request("query (\$ids: [Int]) { Page(perPage: ${ids.size}) { media(id_in: \$ids, type: ANIME) { $FICHA_FIELDS } } }", variables)
        return data?.get("Page").asObjectOrNull()?.get("media")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty().mapNotNull { it.asObjectOrNull()?.let(::raw) }
    }

    override suspend fun byMal(ids: List<Long>): Map<Long, AnilistMatcher.Candidate> {
        if (ids.isEmpty()) return emptyMap()
        val vars = ids.indices.joinToString(", ") { "\$m$it: Int" }
        // Page y no Media: un id que AniList no conoce da una lista vacía en lugar de un error 404 que estropearía el lote entero.
        val body = ids.indices.joinToString("\n") { "m$it: Page(perPage: 1) { media(idMal: \$m$it, type: ANIME) { $SEARCH_FIELDS } }" }
        val variables = JsonObject().also { v -> ids.forEachIndexed { i, id -> v.addProperty("m$i", id) } }
        val data = request("query ($vars) { $body }", variables)
        if (data == null && ids.size > 1) return ids.fold(emptyMap()) { found, id -> found + byMal(listOf(id)) }
        return ids.indices.mapNotNull { i ->
            data?.get("m$i").asObjectOrNull()?.get("media")?.takeIf { it.isJsonArray }?.asJsonArray.candidates().firstOrNull()?.let { ids[i] to it }
        }.toMap()
    }

    override suspend fun top(count: Int, minPopularity: Int, airing: Boolean): AnilistTops {
        val out = ArrayList<AnilistTop>()
        val status = if (airing) ", status: RELEASING" else ""
        var page = 1
        while (out.size < count) {
            val variables = JsonObject().also { it.addProperty("page", page); it.addProperty("pop", minPopularity) }
            val query = "query (\$page: Int, \$pop: Int) { Page(page: \$page, perPage: $TOP_PAGE) { media(type: ANIME, isAdult: false, sort: SCORE_DESC, popularity_greater: \$pop, format_in: [TV, MOVIE, OVA, ONA]$status) { id title { romaji } averageScore seasonYear } } }"
            // Sin datos (AniList rechazó la consulta) la lista queda corta: quien la pide decide si vale; una caída lanza AnilistUnavailable.
            val media = request(query, variables)?.get("Page").asObjectOrNull()?.get("media")?.takeIf { it.isJsonArray }?.asJsonArray ?: return AnilistTops(out.distinctBy { it.id }.take(count), complete = false)
            if (media.size() == 0) break
            media.mapNotNullTo(out) { element ->
                val m = element.asObjectOrNull() ?: return@mapNotNullTo null
                val id = m.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@mapNotNullTo null
                AnilistTop(id, m.get("title").asObjectOrNull()?.string("romaji").orEmpty(), m.get("averageScore")?.takeIf { it.isJsonPrimitive }?.asInt, m.get("seasonYear")?.takeIf { it.isJsonPrimitive }?.asInt)
            }
            page++
        }
        return AnilistTops(out.distinctBy { it.id }.take(count), complete = true)
    }

    /** El tráiler de un anime (sitio e id del vídeo, como los da AniList), o null si no tiene. No se guarda: se pide al abrir la ficha. */
    suspend fun trailer(id: Long): Pair<String?, String?>? {
        val variables = JsonObject().also { it.addProperty("id", id) }
        val media = request("query (${'$'}id: Int) { Media(id: ${'$'}id, type: ANIME) { trailer { id site } } }", variables)?.get("Media").asObjectOrNull() ?: return null
        val trailer = media.get("trailer").asObjectOrNull() ?: return null
        fun text(key: String) = trailer.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        return text("site") to text("id")
    }

    /** El objeto `data` de la respuesta; null si AniList no devolvió datos (una búsqueda que falla no estropea las demás). */
    private suspend fun request(query: String, variables: JsonObject): JsonObject? {
        val body = JsonObject().apply { addProperty("query", query); add("variables", variables) }
        val response = JsonParser.parseString(post(body.toString())).asObjectOrNull() ?: return null
        return response.get("data").asObjectOrNull()
    }

    private fun JsonArray?.candidates(): List<AnilistMatcher.Candidate> = orEmpty().mapNotNull { element ->
        val media = element.asObjectOrNull() ?: return@mapNotNull null
        val id = media.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return@mapNotNull null
        val title = media.get("title").asObjectOrNull()
        AnilistMatcher.Candidate(
            id, media.string("format"), title?.string("romaji"), title?.string("english"), title?.string("native"),
            media.get("synonyms")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty().mapNotNull { it.takeIf { s -> s.isJsonPrimitive }?.asString },
            media.get("seasonYear")?.takeIf { it.isJsonPrimitive }?.asInt ?: media.get("startDate").asObjectOrNull()?.get("year")?.takeIf { it.isJsonPrimitive }?.asInt,
            media.get("episodes")?.takeIf { it.isJsonPrimitive }?.asInt, media.get("idMal")?.takeIf { it.isJsonPrimitive }?.asLong
        )
    }

    private fun raw(media: JsonObject): AnilistFichaRaw? {
        val id = media.get("id")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
        val title = media.get("title").asObjectOrNull()
        return AnilistFichaRaw(
            id, media.get("idMal")?.takeIf { it.isJsonPrimitive }?.asLong, media.toString(),
            title?.string("romaji"), title?.string("english"), title?.string("native"),
            media.get("seasonYear")?.takeIf { it.isJsonPrimitive }?.asInt ?: media.get("startDate").asObjectOrNull()?.get("year")?.takeIf { it.isJsonPrimitive }?.asInt,
            media.string("format"), media.get("averageScore")?.takeIf { it.isJsonPrimitive }?.asInt, media.string("status")
        )
    }


    companion object {
        /** Versión de la consulta de ficha: si se amplía, se sube y se vuelven a pedir las fichas ya guardadas. */
        const val FICHA_VERSION = 1
        private const val PER_SEARCH = 8
        private const val TOP_PAGE = 50
        const val SEARCH_FIELDS = "id idMal format episodes title { romaji english native } synonyms seasonYear startDate { year }"
        const val FICHA_FIELDS = "id idMal title { romaji english native } synonyms format status season seasonYear episodes duration " +
            "startDate { year month day } endDate { year month day } genres description(asHtml: false) averageScore popularity isAdult countryOfOrigin " +
            "coverImage { extraLarge large color } bannerImage studios { edges { isMain node { name } } } " +
            "characters(perPage: 12, sort: [ROLE, FAVOURITES_DESC]) { edges { role node { name { full native } } voiceActors(language: JAPANESE) { name { full } } } } " +
            "relations { edges { relationType node { id title { romaji } format } } } nextAiringEpisode { episode airingAt }"
    }
}
