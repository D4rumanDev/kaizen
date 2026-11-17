package app.kaizen.home

import app.kaizen.source.seriesIdentity
import app.kaizen.commons.Network
import app.kaizen.commons.runCatchingCoop
import app.kaizen.database.CacheDB
import app.kaizen.domain.StoredRelation
import app.kaizen.domain.StoredTaste
import app.kaizen.domain.TasteItem
import app.kaizen.domain.TasteProfile
import app.kaizen.pojos.av1.FavoriteAV1
import app.kaizen.pojos.av1.RELATION_PREQUEL
import app.kaizen.pojos.av1.RELATION_SEQUEL
import app.kaizen.pojos.av1.Record
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.domain.NotRecommended
import app.kaizen.source.AnimeSources
import app.kaizen.source.ApiCache
import app.kaizen.source.SeriesLedger
import app.kaizen.source.seriesIdOf
import app.kaizen.source.normalizeTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * El gusto del usuario: géneros de sus favoritos y de lo que más ha visto, y las precuelas y secuelas de esas series.
 * Los datos de cada serie se piden a su ficha una vez y se guardan 30 días; el perfil se rehace solo si cambian los
 * favoritos o el historial.
 */
object TasteRepository {
    /** Series que cuentan: primero las de más peso. Acota las fichas que hay que pedir la primera vez. */
    private const val MAX_SERIES = 30
    private const val FAVORITE_WEIGHT = 3.0
    private const val CHAPTERS_PER_EXTRA_WEIGHT = 12.0
    private const val MAX_EXTRA_WEIGHT = 2.0
    private const val TTL_MS = 30 * ApiCache.DAY

    /** Una serie cuya ficha falló no se vuelve a pedir en un día (si no, se reintentaría tras cada capítulo visto). */
    private const val RETRY_AFTER_FAILURE_MS = ApiCache.DAY
    private const val MAX_PARALLEL_FETCHES = 4

    /**
     * Perfil, lo que el usuario ya tiene (títulos normalizados, para no recomendárselo) y las precuelas y secuelas de
     * sus series que aún no tiene ni ha visto.
     */
    class Snapshot(val signature: String, val profile: TasteProfile, val known: Set<String>, val sequels: List<SeriesSummary>)

    private class Candidate(val source: String, val slug: String, var weight: Double, val lastDate: Long)

    private val prefs get() = app.kaizen.commons.KaizenStore.prefs("kaizen_taste")
    private val lock = Mutex()
    private var cached: Snapshot? = null

    suspend fun snapshot(): Snapshot = lock.withLock {
        withContext(Dispatchers.IO) {
            val database = CacheDB.INSTANCE
            val favorites = database.favoriteAV1DAO().allRaw
            val history = database.recordAV1DAO().all.toList()
            // El libro de ids entra en la firma: al unir o separar series cambian los grupos y hay que rehacer el perfil.
            val signature = "${favorites.size}:${history.size}:${history.maxOfOrNull { it.date } ?: 0L}:${SeriesLedger.version.value}"
            cached?.takeIf { it.signature == signature }?.let { return@withContext it }
            val wanted = candidates(favorites, history)
            val details = detailsOfAll(wanted)
            val known = (favorites.map { it.name } + history.map { it.name }).map(::normalizeTitle).toSet() +
                (favorites.map { it.source to it.slug } + history.map { it.source to it.slug }).mapNotNull { (source, slug) -> seriesIdOf(source, slug).takeIf { it != 0L }?.let { "#$it" } }
            val profile = TasteProfile.build(details.map { (candidate, stored) -> TasteItem(stored.genres, candidate.weight) })
            // Si alguna ficha falló (sin red, fuente caída) el perfil queda a medias: no se guarda y se rehace sin ella; esa serie no se vuelve a pedir hasta pasado el plazo de reintento (1 día).
            Snapshot(signature, profile, known, sequelsOf(details.flatMap { it.second.relations }, known))
                .also { if (details.size == wanted.size) cached = it }
        }
    }

    /** Precuelas y secuelas sin repetir, de fuentes activas y que el usuario no tiene en favoritos ni ha visto. */
    private fun sequelsOf(relations: List<StoredRelation>, known: Set<String>): List<SeriesSummary> {
        val enabled = AnimeSources.enabled.map { it.id }.toSet()
        return relations.asSequence()
            .filter { (it.type == RELATION_PREQUEL || it.type == RELATION_SEQUEL) && it.source in enabled && !NotRecommended.isExcluded(known, it.name, it.source, it.slug) }
            .distinctBy { seriesIdentity(it.name, it.source, it.slug) }
            .map { SeriesSummary(it.aid, it.name, it.slug, 1, it.source) }
            .toList()
    }

    /**
     * Favoritas (peso fijo) y series vistas (más peso cuantos más capítulos), sin repetir, las [MAX_SERIES] más pesadas. Una serie es
     * el grupo de sus fichas ([seriesIdentity]): la misma serie en dos fuentes, aunque se llame distinto, cuenta una vez.
     */
    private fun candidates(favorites: List<FavoriteAV1>, history: List<Record>): List<Candidate> {
        val byTitle = LinkedHashMap<String, Candidate>()
        for (favorite in favorites) {
            byTitle[seriesIdentity(favorite.name, favorite.source, favorite.slug)] = Candidate(favorite.source, favorite.slug, FAVORITE_WEIGHT, 0L)
        }
        for ((key, chapters) in history.groupBy { seriesIdentity(it.name, it.source, it.slug) }) {
            val latest = chapters.maxBy { it.date }
            val weight = 1.0 + (chapters.size / CHAPTERS_PER_EXTRA_WEIGHT).coerceAtMost(MAX_EXTRA_WEIGHT)
            val existing = byTitle[key]
            if (existing != null) existing.weight += weight
            else byTitle[key] = Candidate(latest.source, latest.slug, weight, latest.date)
        }
        return byTitle.values.sortedWith(compareByDescending<Candidate> { it.weight }.thenByDescending { it.lastDate }).take(MAX_SERIES)
    }

    /** Los datos de todas las series, pidiendo las fichas que faltan de [MAX_PARALLEL_FETCHES] en [MAX_PARALLEL_FETCHES]. */
    private suspend fun detailsOfAll(candidates: List<Candidate>): List<Pair<Candidate, StoredTaste>> {
        val gate = Semaphore(MAX_PARALLEL_FETCHES)
        return coroutineScope {
            candidates.map { candidate -> async { gate.withPermit { detailsOf(candidate) }?.let { candidate to it } } }.awaitAll().filterNotNull()
        }
    }

    /** Géneros y relaciones de la serie: de lo guardado si es reciente; si no, de su ficha. null si no se pudieron conocer. */
    private suspend fun detailsOf(candidate: Candidate): StoredTaste? {
        val key = "${candidate.source}:${candidate.slug}"
        stored(key)?.let { return it }
        if (!Network.isConnected || failedRecently(key)) return null
        // Una fuente desactivada no se consulta para calcular los gustos.
        if (!app.kaizen.source.Catalog.isActive(candidate.source)) return null
        val source = AnimeSources.of(candidate.source)
        // Solo la fuente de la serie: si falla no se prueban las demás (cada alternativa es otra petición por serie, y son hasta 30).
        val series = runCatchingCoop { AnimeSources.openAnime(source.animeUrl(candidate.slug), alternatives = false) }.getOrNull()
        val genres = series?.genres?.map { it.slug }?.takeIf { it.isNotEmpty() }
        if (series == null || genres == null) {
            prefs.edit().putLong(failureKey(key), System.currentTimeMillis()).apply()
            return null
        }
        val relations = series.relations.map { StoredRelation(it.relation, it.aid, AnimeSources.of(it.source).id, it.slug, it.name) }
        return StoredTaste(genres, relations).also { prefs.edit().putString(key, it.encode(System.currentTimeMillis())).apply() }
    }

    private fun failureKey(key: String) = "fail|$key"

    private fun failedRecently(key: String): Boolean =
        System.currentTimeMillis() - prefs.getLong(failureKey(key), 0L) < RETRY_AFTER_FAILURE_MS

    /** Olvida lo aprendido de las fichas (lo usa «Vaciar la caché»); el perfil se rehace en la próxima consulta. */
    fun clear() {
        prefs.edit().clear().apply()
        cached = null
    }

    private fun stored(key: String): StoredTaste? {
        val (stamp, taste) = prefs.getString(key, null)?.let(StoredTaste::decode) ?: return null
        return taste.takeIf { System.currentTimeMillis() - stamp <= TTL_MS }
    }
}
