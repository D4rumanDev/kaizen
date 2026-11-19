package app.kaizen.source

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Catálogo interno, lado de lectura: lista de todas las series de las fuentes activas (solo nombres y datos básicos)
 * y en qué fuente está cada una. Con él, el directorio y la búsqueda salen de la base de datos: ordenados, unidos
 * entre fuentes y sin pedir una página a cada servidor por cada pantalla. Lo genera [CatalogBuilder].
 */
object Catalog {
    private val dao get() = catalogDao

    /** Si el catálogo puede responder: alguna fuente activa está recorrida y la consulta no pide género ni «en emisión». */
    suspend fun canServe(query: SearchQuery): Boolean = withContext(Dispatchers.IO) {
        query.genre == null && !query.airing && isUsable()
    }

    /** Si el catálogo se ha recorrido y tiene series: sin él no se puede buscar series ni proponer uniones (se recorre en Configuración › Catálogo). */
    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) { isUsable() }

    /** Resultados de la consulta desde la base de datos, paginados. */
    fun paged(query: SearchQuery): Flow<PagingData<SeriesSummary>> {
        val sources = AnimeSources.enabled.map { it.id }
        // Con texto, la misma búsqueda tolerante que el buscador de «Unir» (sin acentos, símbolos ni vocales largas): «Hyoken» = «Hyouken».
        if (query.text.isNotBlank()) return kotlinx.coroutines.flow.flow { emit(PagingData.from(runCatchingCoop { tolerantCards(query, sources) }.onFailure(::logError).getOrDefault(emptyList()))) }
        return Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
            dao.entries(
                sources, query.text.trim(), query.minScore,
                query.years?.first, query.years?.last,
                if (query.sort == SortBy.RATING) 1 else 0
            )
        }.flow.map { data -> data.map { entry -> toCard(entry, sources) } }
    }

    /** Series que casan con el texto de [query] (por nombre plegado o por fragmento de slug), con sus filtros de nota y año, como tarjetas. */
    private suspend fun tolerantCards(query: SearchQuery, sources: List<String>): List<SeriesSummary> = withContext(Dispatchers.IO) {
        // Sin tope antes de filtrar (las de fuentes desactivadas o fuera de nota/año no deben quitar sitio a las demás): el tope va al final.
        val byName = EntrySearch.byFoldedName(foldedEntries(), query.text, limit = Int.MAX_VALUE)
        val bySlug = EntrySearch.slugFragment(query.text).takeIf { it.length >= 3 }?.let(dao::searchBySlug).orEmpty()
        val matches = EntrySearch.merge(byName, bySlug, limit = Int.MAX_VALUE).asSequence().filter { entry ->
            (query.minScore == null || (entry.score ?: 0.0) >= query.minScore) &&
                (query.years == null || entry.year?.let { it in query.years } == true) &&
                dao.links(entry.key).any { it.sourceId in sources }
        }
        (if (query.sort == SortBy.RATING) matches.sortedByDescending { it.score ?: 0.0 } else matches)
            .take(SEARCH_LIMIT).toList().map { toCard(it, sources) }
    }

    /**
     * Una serie para elegirla en un buscador: clave (el id de la serie como texto), nombre y portadas posibles (la propia del usuario primero). Si es una ficha
     * concreta de una serie, [sourceId] y [slug] dicen cuál.
     */
    class Pick(
        val key: String, val name: String, val covers: List<String>,
        val sourceId: String? = null, val slug: String? = null,
        /** Año de estreno de la serie (el más antiguo de sus fichas) si alguna fuente lo da. */
        val year: Int? = null,
        /** Nombres de las fuentes donde está la serie. */
        val sources: List<String> = emptyList()
    )

    /**
     * Hasta 20 series del catálogo que casan con [text] por nombre (todas las palabras escritas, sin distinguir mayúsculas) o por slug (vacío si el
     * texto está en blanco).
     */
    suspend fun searchEntries(text: String): List<Pick> = withContext(Dispatchers.IO) {
        val byName = EntrySearch.byFoldedName(foldedEntries(), text)
        val bySlug = EntrySearch.slugFragment(text).takeIf { it.length >= 3 }?.let(dao::searchBySlug).orEmpty()
        EntrySearch.merge(byName, bySlug).map { pickOf(it, dao.links(it.key)) }
    }

    // Los nombres del catálogo ya plegados para buscar; se rehacen si cambia el número de series.
    private var foldedCache: Pair<Int, List<Pair<CatalogEntry, String>>>? = null

    private fun foldedEntries(): List<Pair<CatalogEntry, String>> {
        val count = dao.count()
        foldedCache?.takeIf { it.first == count }?.let { return it.second }
        return dao.allEntries().map { it to foldTitleUncached(it.name) }.also { foldedCache = count to it }
    }

    // Episodios publicados por ficha ya consultados: el selector y la fila de series unidas los piden una y otra vez.
    private val episodeCache = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Pair<Int, Long>>()
    private val EPISODE_CACHE = 10.minutes
    private val episodeGate = kotlinx.coroutines.sync.Semaphore(3)
    // Fichas que no respondieron: no se reintentan al momento (cada fila visible volvería a esperar el tiempo máximo).
    private val episodeFailures = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Long>()
    private val EPISODE_RETRY = 1.minutes

    /** Si [sourceId] está marcada en Configuración: una web desactivada no se consulta para nada que no sea abrir a mano una ficha suya. */
    fun isActive(sourceId: String): Boolean = AnimeSources.selected.any { it.id == sourceId }

    /** Cuántos episodios tiene publicados la ficha [slug] de [sourceId] (lo que lista su fuente); null si no responde a tiempo o si la fuente está desactivada (no se le pide nada). */
    suspend fun episodeCount(sourceId: String, slug: String): Int? {
        if (!isActive(sourceId)) return null
        episodeCache[sourceId to slug]?.let { (count, at) -> if (System.currentTimeMillis() - at < EPISODE_CACHE.inWholeMilliseconds) return count }
        episodeFailures[sourceId to slug]?.let { if (System.currentTimeMillis() - it < EPISODE_RETRY.inWholeMilliseconds) return null }
        val source = AnimeSources.of(sourceId)
        val count = episodeGate.withPermit {
            withTimeoutOrNull(EPISODES_TIMEOUT) {
                runCatchingCoop { SourceCache.episodes(source, slug) }.getOrNull()
            }
        }
        if (count == null) episodeFailures[sourceId to slug] = System.currentTimeMillis() else episodeFailures.remove(sourceId to slug)
        return count?.also { episodeCache[sourceId to slug] = it to System.currentTimeMillis() }
    }

    /** Lo que la ficha de una fuente dice de la serie, para compararlo con AniList: episodios, si sigue en emisión, géneros (slugs) y estudio. */
    class SourceFacts(val episodes: Int?, val airing: Boolean, val genres: List<String>, val studio: String?, val year: Int?, val formato: String? = null, val idMal: Long? = null, val names: List<String> = emptyList(), val duration: Int? = null)

    private val factsCache = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Pair<SourceFacts, Long>>()

    /** Los datos de la ficha [slug] de [sourceId] (una petición, con el mismo ritmo y límite que [episodeCount]); null si no responde a tiempo. */
    suspend fun facts(sourceId: String, slug: String): SourceFacts? {
        if (!isActive(sourceId)) return null
        factsCache[sourceId to slug]?.let { (facts, at) -> if (System.currentTimeMillis() - at < EPISODE_CACHE.inWholeMilliseconds) return facts }
        episodeFailures[sourceId to slug]?.let { if (System.currentTimeMillis() - it < EPISODE_RETRY.inWholeMilliseconds) return null }
        val source = AnimeSources.of(sourceId)
        val series = episodeGate.withPermit {
            withTimeoutOrNull(EPISODES_TIMEOUT) {
                runCatchingCoop { source.anime(source.animeUrl(slug))?.also { SourceCache.noteFiche(it) } }.getOrNull()
            }
        }
        if (series == null) { episodeFailures[sourceId to slug] = System.currentTimeMillis(); return null }
        episodeFailures.remove(sourceId to slug)
        // Lo que dice la web de la ficha que acaba de abrirse (formato, idMal…): del mismo registro que se guardó al abrirla.
        val web = runCatching { FichaData.get(app.kaizen.database.CatalogDB.INSTANCE.webFichaDao(), sourceId, slug) }.getOrNull()
        return SourceFacts(series.chapters.size.takeIf { it > 0 }, series.state == 1, series.genres.map { it.slug }, series.studio, series.year, web?.formato, web?.idMal, web?.let { w -> w.nombres.filter { it != w.romaji } }.orEmpty(), web?.duracion)
            .also { factsCache[sourceId to slug] = it to System.currentTimeMillis() }
    }

    /**
     * Las fichas de [pick]: (id de fuente, nombre de fuente, slug). Una sola si el resultado ya es una ficha concreta; si no, la de cada
     * fuente activa donde está la serie (con «x» y «x-latino» gana la base).
     */
    suspend fun fichasOf(pick: Pick): List<Triple<String, String, String>> {
        if (pick.sourceId != null && pick.slug != null) return listOf(Triple(pick.sourceId, AnimeSources.of(pick.sourceId).name, pick.slug))
        val enabled = AnimeSources.enabled.map { it.id }
        val links = withContext(Dispatchers.IO) { dao.links(pick.key) }
        return links.filter { it.sourceId in enabled }.groupBy { it.sourceId }
            .map { (_, sameSource) -> sameSource.minWith(compareBy<CatalogLink>({ it.slug.endsWith("-latino") }, { it.slug })) }
            .sortedBy { enabled.indexOf(it.sourceId) }
            .map { Triple(it.sourceId, AnimeSources.of(it.sourceId).name, it.slug) }
    }

    /** La serie del catálogo a la que pertenece la ficha ([sourceId], [slug]), con su año y sus fuentes; null si el catálogo no la tiene. */
    suspend fun pickOfFicha(sourceId: String, slug: String): Pick? = withContext(Dispatchers.IO) {
        if (!isUsable()) return@withContext null
        val key = dao.keyOf(sourceId, slug) ?: return@withContext null
        dao.entriesOf(listOf(key)).firstOrNull()?.let { pickOf(it, dao.links(key)) }
    }

    private val EPISODES_TIMEOUT = 8.seconds

    /** Una ficha de una serie unida, para mostrarla en la ficha de otra: de qué fuente es, su slug y la serie a la que pertenece. */
    class JoinedFicha(
        val sourceId: String, val sourceName: String, val slug: String, val aid: Int, val name: String, val covers: List<String>,
        val year: Int? = null
    )

    /** Las otras fichas (de otras fuentes, o de la misma con otro slug) de la serie de la ficha [slug] de [sourceId]. Vacío sin catálogo. */
    suspend fun joinedFichas(sourceId: String, slug: String): List<JoinedFicha> = withContext(Dispatchers.IO) {
        val key = if (isUsable()) dao.keyOf(sourceId, slug) else null
        val entry = key?.let { dao.entriesOf(listOf(it)).firstOrNull() }
        val name = entry?.name ?: titleFromSlug(slug)
        val fromCatalog = key?.let { k ->
            dao.links(k).filterNot { it.sourceId == sourceId && it.slug == slug }.map { link ->
                val source = AnimeSources.of(link.sourceId)
                val aid = link.aid.takeIf { it > 0 } ?: AnimeIds.forSlug(link.slug, link.sourceId)
                JoinedFicha(link.sourceId, source.name, link.slug, aid, name, listOf(source.coverUrl(aid, link.slug)), link.year ?: entry?.year)
            }
        }.orEmpty()
        // Las uniones del usuario valen aunque el catálogo aún no tenga alguna de las fichas (un estreno, una fuente que no respondió).
        val known = fromCatalog.map { it.sourceId to it.slug }.toSet()
        val fromRules = ruleSiblings(sourceId, slug).filterNot { it in known }.map { (siblingSource, siblingSlug) ->
            val source = AnimeSources.of(siblingSource)
            val aid = AnimeIds.forSlug(siblingSlug, siblingSource)
            JoinedFicha(siblingSource, source.name, siblingSlug, aid, name, listOf(source.coverUrl(aid, siblingSlug)))
        }
        (fromCatalog + fromRules).sortedWith(compareBy({ it.sourceName }, { it.slug }))
    }

    /** Las otras fichas que, por las reglas del usuario y los ids de serie, son la misma serie que ([sourceId], [slug]); no necesita el catálogo. */
    private fun ruleSiblings(sourceId: String, slug: String): List<Pair<String, String>> =
        siblingFichas(UserTitles.rules, SeriesLedger::fichasOfKeys, SeriesLedger::peekKey, sourceId, slug)

    /**
     * Fichas que las fuentes activas devuelven al buscar [text] directamente, sin pasar por el catálogo: sirve para unir una serie
     * que el catálogo aún no tiene (un estreno) o cuyo nombre no contiene el texto. Si el catálogo conoce la ficha, la [Pick] lleva
     * su clave; si no, la clave va vacía y se identifica por [Pick.sourceId] y [Pick.slug]. Una fuente que tarda más de
     * [LIVE_TIMEOUT] o falla no aporta.
     */
    suspend fun searchLive(text: String): List<Pick> = coroutineScope {
        val query = SearchQuery(text = text.trim())
        if (query.text.length < 2) return@coroutineScope emptyList()
        // Las webs buscan mal con varias palabras o con símbolos («Magical Explorer» no encuentra «Magical★Explorer»): con varias
        // se les pide la más larga y aquí se queda lo que contiene todas.
        val several = searchWords(query.text).size > 1
        val fetch = if (several) SearchQuery(text = longestWord(query.text).ifEmpty { query.text }) else query
        AnimeSources.enabled.map { source ->
            async {
                withTimeoutOrNull(LIVE_TIMEOUT) {
                    runCatchingCoop { SearchEngine.applyLocal(fetch, source, source.search(fetch, null).items) }.getOrNull()
                }.orEmpty().filter { !several || nameMatches(it.name, query.text) }.map { item -> source to item }
            }
        }.awaitAll().flatten().distinctBy { (source, item) -> source.id to item.slug }.take(LIVE_MAX).map { (source, item) ->
            val key = withContext(Dispatchers.IO) { dao.keyOf(source.id, item.slug) }.orEmpty()
            val cover = source.coverUrl(item.aid, item.slug)
            Pick(key, item.name, listOf(cover), source.id, item.slug, item.year, listOf(source.name))
        }
    }

    private val LIVE_TIMEOUT = 8.seconds
    private const val LIVE_MAX = 30
    private const val SEARCH_LIMIT = 100

    /** Una propuesta de unión lista para mostrar: las dos series y las fichas (fuente · slug) de cada una. */
    class Proposal(val cleanSlug: String, val first: Pick, val second: Pick, val firstFichas: List<Pair<String, String>>, val secondFichas: List<Pair<String, String>>, val inferred: Boolean = false,
        /** Las fichas (de las dos series) cuyo slug limpio es [cleanSlug]: las que motivan la propuesta. */
        val matching: Set<Pair<String, String>> = emptySet())

    /** Series que el catálogo mantiene separadas aunque comparten el slug sin sufijos ([MergeProposals]). Vacío sin catálogo. */
    suspend fun mergeProposals(): List<Proposal> = withContext(Dispatchers.IO) {
        if (!isUsable()) return@withContext emptyList()
        val links = dao.allLinks()
        val byKey = links.groupBy { it.key }
        val learned = MergeProposals.learnedSuffixes(links)
        MergeProposals.find(dao.allEntries(), links).map { found ->
            val first = byKey[found.first.key].orEmpty()
            val second = byKey[found.second.key].orEmpty()
            Proposal(found.cleanSlug, pickOf(found.first, first), pickOf(found.second, second), fichasOf(first), fichasOf(second), found.inferred,
                (first + second).filter { MergeProposals.cleanSlugWith(it.slug, learned) == found.cleanSlug }.map { it.sourceId to it.slug }.toSet())
        }
    }

    /** Una serie que el catálogo ha juntado a partir de varias fichas (fuente, slug), con su nombre y portadas. */
    class Joined(val pick: Pick, val fichas: List<Pair<String, String>>, val labels: List<String>)

    /** Las series que tienen más de una ficha: lo que se juntó solo (por slug o título) o por el usuario. Vacío sin catálogo. */
    suspend fun joinedSeries(): List<Joined> = withContext(Dispatchers.IO) {
        if (!isUsable()) return@withContext emptyList()
        val entries = dao.allEntries().associateBy { it.key }
        dao.allLinks().groupBy { it.key }.mapNotNull { (key, links) ->
            val fichas = links.map { it.sourceId to it.slug }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
            val entry = entries[key]
            if (fichas.size < 2 || entry == null) null
            else Joined(pickOf(entry, links), fichas, fichas.map { (source, slug) -> fichaLabel(source, slug) })
        }.sortedBy { it.pick.name.lowercase() }
    }

    private fun fichasOf(links: List<CatalogLink>) = links.map { it.sourceId to it.slug }.distinct().sortedWith(compareBy({ it.first }, { it.second }))

    private fun fichaLabel(sourceId: String, slug: String) = "${AnimeSources.of(sourceId).name} · $slug"

    /** La serie para elegir: sus portadas salen de [links], las fichas que ya se leyeron de la base de datos. */
    private fun pickOf(entry: CatalogEntry, links: List<CatalogLink>): Pick {
        val covers = links.map { link ->
            AnimeSources.of(link.sourceId).coverUrl(link.aid.takeIf { it > 0 } ?: AnimeIds.forSlug(link.slug, link.sourceId), link.slug)
        }
        return Pick(entry.key, entry.name, listOfNotNull(UserTitles.coverOfKey(entry.key)) + covers, year = entry.year,
            sources = links.map { AnimeSources.of(it.sourceId).name }.distinct())
    }

    /** Tarjeta de una serie: se abre en la primera fuente activa que la tiene. */
    private suspend fun toCard(entry: CatalogEntry, sources: List<String>): SeriesSummary = withContext(Dispatchers.IO) {
        val links = dao.links(entry.key)
        val link = links.filter { it.sourceId in sources }
            // Con «x» y «x-latino» de la misma fuente gana la base, siempre la misma (el orden de la consulta no está fijado).
            .minWithOrNull(compareBy<CatalogLink>({ sources.indexOf(it.sourceId) }, { it.slug.endsWith("-latino") }, { it.slug })) ?: links.first()
        SeriesSummary(
            aid = link.aid.takeIf { it > 0 } ?: AnimeIds.forSlug(link.slug, link.sourceId),
            name = entry.name,
            slug = link.slug,
            category = 1,
            source = link.sourceId,
            score = entry.score,
            votes = entry.votes,
            year = entry.year
        )
    }

    /** Si alguna fuente activa está recorrida y hay datos (se puede confiar en los enlaces de esas fuentes). */
    internal fun isUsable(): Boolean = AnimeSources.enabled.any { CatalogStates[it.id].checkedAt > 0 } && dao.count() > 0

    /**
     * Otras fuentes activas que tienen la misma serie que la ficha [url] de [from], con su slug: alternativa
     * cuando [from] no responde. Vacío si el catálogo no la conoce.
     */
    suspend fun alternatives(from: AnimeSource, url: String): List<Pair<AnimeSource, String>> = withContext(Dispatchers.IO) {
        if (!isUsable()) return@withContext emptyList()
        val last = slugOfUrl(url)
        val key = dao.keyOf(from.id, last) ?: dao.keyOf(from.id, last.removeSuffix("-anime")) ?: return@withContext emptyList()
        AnimeSources.enabled.filter { it.id != from.id }.mapNotNull { source -> dao.slugOf(key, source.id)?.let { source to it } }
    }

    /**
     * Sin catálogo no se sabe cómo llama otra fuente a la serie: se prueba el slug sin el sufijo de plataforma («x-anime», «x-tv»),
     * que es como la suelen llamar. Es una apuesta para pedir una URL, no una identidad: la identidad la dan los ids de serie.
     */
    private fun guessSlug(slug: String) = cleanSlug(slug)

    /**
     * Slug de la serie [fromSlug] (de la fuente [fromSource]) en la fuente [target], según el catálogo.
     * Devuelve null si [target] no la tiene, para no pedirle algo que no existe. Sin catálogo se supone el
     * mismo slug.
     */
    suspend fun slugIn(target: AnimeSource, fromSource: String, fromSlug: String): String? = withContext(Dispatchers.IO) {
        if (!isUsable()) return@withContext guessSlug(fromSlug)
        // Serie aún no recorrida (estrenos del horario): se prueba su slug sin sufijo, como sin catálogo.
        val key = dao.keyOf(fromSource, fromSlug) ?: return@withContext guessSlug(fromSlug)
        dao.slugOf(key, target.id)
    }

    /** Igual que [slugIn] cuando no se sabe de qué fuente viene el slug (recomendados). */
    suspend fun slugAny(target: AnimeSource, fromSlug: String): String? = withContext(Dispatchers.IO) {
        if (!isUsable()) return@withContext guessSlug(fromSlug)
        val key = dao.keyOfSlug(fromSlug) ?: return@withContext guessSlug(fromSlug)
        dao.slugOf(key, target.id)
    }

    /**
     * Nota en estrellas que el catálogo guarda de la serie de la ficha [source]/[slug]: la de AniList (ver [AnilistScores]), o null si no tiene
     * o la serie no está.
     */
    suspend fun ratingOf(source: String, slug: String): Double? = withContext(Dispatchers.IO) {
        if (!isUsable()) return@withContext null
        dao.keyOf(source, slug)?.let(::ratingOfKey)
    }

    /** Nota (estrellas) y año de estreno de la serie de la ficha según el catálogo; null en lo que no tiene. Para ordenar listas. */
    suspend fun scoreAndYear(source: String, slug: String): Pair<Double?, Int?> = withContext(Dispatchers.IO) {
        if (!isUsable()) return@withContext null to null
        val key = dao.keyOf(source, slug) ?: return@withContext null to null
        ratingOfKey(key) to dao.entriesOf(listOf(key)).firstOrNull()?.year
    }

    private fun ratingOfKey(key: String): Double? =
        dao.entriesOf(listOf(key)).firstOrNull()?.score?.takeIf { it > 0 }
}
