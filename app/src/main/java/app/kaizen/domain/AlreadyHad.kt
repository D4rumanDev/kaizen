package app.kaizen.domain

import app.kaizen.commons.AppScope
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.database.CacheDB
import app.kaizen.source.Catalog
import app.kaizen.source.SeriesMatch
import app.kaizen.source.seriesKeyOf
import app.kaizen.source.normalizeTitle
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds

/**
 * Las series que no hace falta recomendar: se reconocen por el título normalizado, por el slug (sin «-latino», porque el mismo
 * anime con otro título en otra fuente tiene el mismo slug) o por su id de serie ([seriesKeyOf]).
 */
class Had(private val titles: Set<String>, private val slugs: Set<String>, private val series: Set<String> = emptySet()) {
    /** [source] da la ficha de la sugerencia: con ella se reconoce también la misma serie con otro título (fichas unidas). */
    fun has(name: String, slug: String, source: String? = null): Boolean =
        normalizeTitle(name) in titles || SeriesMatch.slugBase(slug) in slugs || (source != null && seriesKeyOf(source, slug) in series)

    companion object {
        val NONE = Had(emptySet(), emptySet())
    }
}

/**
 * Qué series no hace falta recomendar: las favoritas y las que ya se han visto enteras (todos los episodios que publica su
 * fuente). Lo usan «Más como esto» y las recomendaciones del staff; las personalizadas ya excluyen todo lo que está en favoritos
 * o en el historial.
 *
 * Saber qué series están enteras cuesta pedir sus episodios, así que se calcula en segundo plano: quien pregunta espera como mucho
 * [WAIT] y, si no llega, recibe las favoritas más lo último que se supo; la siguiente consulta ya lo tiene.
 */
object AlreadyHad {
    private val FRESH = 30.minutes

    /** Tras un cálculo incompleto (alguna fuente no respondió) se vuelve a intentar antes. */
    private val RETRY = 2.minutes
    private val WAIT = 2500.milliseconds

    /** Las series del historial que se comprueban, las más recientes. */
    private const val MAX_CHECKED = 40

    private val lock = Mutex()
    private var finishedTitles: Set<String> = emptySet()
    private var finishedSlugs: Set<String> = emptySet()
    private var finishedSignature = ""
    private var finishedAt = 0L
    private var finishedComplete = true
    private var refresh: Deferred<Unit>? = null

    suspend fun current(): Had {
        val (favorites, signature) = withContext(Dispatchers.IO) {
            val database = CacheDB.INSTANCE
            val favorites = database.favoriteAV1DAO().allRaw
            val history = database.recordAV1DAO().all
            // Cambia al añadir o quitar favoritos y al marcar o desmarcar capítulos (también si el total no cambia).
            favorites to "${favorites.map { it.aid }.hashCode()}:${history.size}:${history.sumOf { it.eid.toLong() }}"
        }
        val job = lock.withLock {
            val age = System.currentTimeMillis() - finishedAt
            val stale = signature != finishedSignature || age > (if (finishedComplete) FRESH.inWholeMilliseconds else RETRY.inWholeMilliseconds)
            if (stale && refresh?.isActive != true) refresh = AppScope.async {
                runCatchingCoop { recompute(signature) }.onFailure { error ->
                    // Un fallo cuenta como cálculo incompleto: se reintenta a los 2 min en vez de en cada consulta.
                    logError(error)
                    lock.withLock { finishedSignature = signature; finishedAt = System.currentTimeMillis(); finishedComplete = false }
                }.let { }
            }
            refresh
        }
        job?.let { withTimeoutOrNull(WAIT) { it.await() } }
        // Una serie con estado (viendo, completada, pendiente...) ya está en la lista del usuario: no se recomienda.
        val withStatus = SeriesStatuses.all.value
        return lock.withLock {
            Had(
                favorites.map { normalizeTitle(it.name) }.toSet() + finishedTitles + withStatus.keys,
                favorites.map { SeriesMatch.slugBase(it.slug) }.toSet() + finishedSlugs + withStatus.values.map { SeriesMatch.slugBase(it.slug) },
                (favorites.map { seriesKeyOf(it.source, it.slug) } + withStatus.values.map { seriesKeyOf(it.source, it.slug) }).toSet()
            )
        }
    }

    private suspend fun recompute(signature: String) {
        val history = withContext(Dispatchers.IO) { CacheDB.INSTANCE.recordAV1DAO().all.toList() }
        var complete = true
        val done = coroutineScope {
            history.groupBy { normalizeTitle(it.name) }.entries
                .sortedByDescending { (_, chapters) -> chapters.maxOf { it.date } }
                .take(MAX_CHECKED)
                .map { (title, chapters) ->
                    async {
                        val latest = chapters.maxBy { it.date }
                        // Una fuente desactivada no se consulta: su serie no cuenta como terminada, pero tampoco deja el cálculo «incompleto» (se reintentaría sin parar).
                        if (!Catalog.isActive(latest.source)) return@async null
                        val total = Catalog.episodeCount(latest.source, latest.slug)
                        if (total == null) complete = false
                        // Los números con decimales son especiales: no cuentan como un episodio más.
                        val finished = total != null && total > 0 && chapters.map { it.number }.distinct().count { it % 1.0 == 0.0 } >= total
                        if (finished) title to SeriesMatch.slugBase(latest.slug) else null
                    }
                }.awaitAll().filterNotNull()
        }
        lock.withLock {
            finishedTitles = done.map { it.first }.toSet()
            finishedSlugs = done.map { it.second }.toSet()
            finishedSignature = signature
            finishedAt = System.currentTimeMillis()
            finishedComplete = complete
        }
    }
}
