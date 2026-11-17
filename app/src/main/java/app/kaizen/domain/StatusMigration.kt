package app.kaizen.domain

import app.kaizen.commons.AppScope
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.database.CacheDB
import app.kaizen.source.AnimeSources
import app.kaizen.source.Catalog
import app.kaizen.source.SeriesMatch
import app.kaizen.source.normalizeTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import app.kaizen.tv.ui.KaizenToast
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Da estado una sola vez a las series que ya hay en el historial: las que están en emisión pasan a «Viendo» y las demás con todos
 * sus episodios vistos, a «Completada». Después, a las favoritas que siguen sin estado: en emisión o con algo visto, «Viendo» (con todo
 * visto, «Completada»), y sin nada visto, «Pendiente». Nunca toca una serie que ya tiene estado. Si no se puede decidir (el calendario de emisión
 * aún está vacío o alguna fuente no responde) no se marca como hecha y se reintenta en el siguiente arranque.
 */
object StatusMigration {
    /** Las series del historial que se examinan, las más recientes. */
    private const val MAX_CHECKED = 150

    /** Intentos como mucho: si alguna fuente no responde nunca, no se repite en cada arranque. */
    private const val MAX_ATTEMPTS = 3

    /** Espera tras arrancar: la migración pide muchas fichas y no debe competir con la carga de la pantalla principal. */
    private const val START_DELAY_MS = 30_000L

    fun launch() {
        if (KaizenSettings.statusesMigrated) return
        AppScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(START_DELAY_MS)
            if (KaizenSettings.statusesMigrated || !running.compareAndSet(false, true)) return@launch
            try {
                // Solo cuenta como intento si hubo con qué trabajar (sin calendario, p. ej. sin red, no se gasta).
                val worked = runCatchingCoop { run() }.onFailure(::logError).getOrDefault(false)
                if (worked) KaizenSettings.statusesAttempts += 1
                if (KaizenSettings.statusesAttempts >= MAX_ATTEMPTS) KaizenSettings.statusesMigrated = true
            } finally { running.set(false) }
        }
    }

    /** Si la serie sale en el horario de emisión de alguna fuente. Sin horario (fallo de red o vacío) cuenta como en emisión: no se da por completada. */
    suspend fun isAiring(name: String, slug: String): Boolean {
        val airing = runCatchingCoop { AnimeSources.scheduleAll().values.flatten() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return true
        val title = normalizeTitle(name)
        return airing.any { normalizeTitle(it.name) == title || SeriesMatch.slugBase(it.slug) == SeriesMatch.slugBase(slug) }
    }

    private val running = AtomicBoolean(false)

    /** Qué hacer con una serie: darle un estado, dejarla como está o no poder decidir (una fuente no respondió: se reintentará). */
    internal sealed interface Verdict {
        data class Mark(val status: SeriesStatus) : Verdict
        data object Skip : Verdict
        data object Unknown : Verdict
    }

    /** Lo que se sabe de una serie para decidirla: su ficha y cuántos episodios enteros (sin decimales, que son especiales) se han visto. */
    internal class Subject(val name: String, val source: String, val slug: String, val aid: Int, val wholeSeen: Int, val hasHistory: Boolean)

    /** Con todos los episodios vistos (los decimales no cuentan como uno más, igual que en AlreadyHad). */
    internal fun finished(total: Int, wholeSeen: Int) = total > 0 && wholeSeen >= total

    /**
     * La decisión sobre una serie sin estado, en plano y sin efectos: en emisión, «Viendo»; las favoritas sin nada visto, «Pendiente»; el resto, según
     * los episodios de su fuente ([totalOf]). Una fuente desactivada no se consulta: la serie se deja como está sin dar el cálculo por incompleto.
     */
    internal suspend fun decide(subject: Subject, airing: Boolean, isFavorite: Boolean, active: (String) -> Boolean = Catalog::isActive, totalOf: suspend (Subject) -> Int?): Verdict {
        if (airing) return Verdict.Mark(SeriesStatus.WATCHING)
        if (isFavorite && !subject.hasHistory) return Verdict.Mark(SeriesStatus.PLAN)
        if (!active(subject.source)) return Verdict.Skip
        val total = totalOf(subject) ?: return Verdict.Unknown
        if (finished(total, subject.wholeSeen)) return Verdict.Mark(SeriesStatus.COMPLETED)
        // Una favorita con algo visto pero sin terminar se está viendo; una del historial sin terminar se deja como está.
        return if (isFavorite) Verdict.Mark(SeriesStatus.WATCHING) else Verdict.Skip
    }

    private class Tally {
        val complete = AtomicBoolean(true)
        val counts = java.util.concurrent.ConcurrentHashMap<SeriesStatus, AtomicInteger>()
        fun count(status: SeriesStatus) = counts.getOrPut(status) { AtomicInteger() }.incrementAndGet()
        fun of(status: SeriesStatus) = counts[status]?.get() ?: 0
    }

    private fun wholeEpisodes(numbers: List<Double>) = numbers.distinct().count { it % 1.0 == 0.0 }

    /** Aplica el veredicto de [subject]: da el estado (solo si sigue sin tenerlo) y lo cuenta, o marca el cálculo como incompleto. */
    private fun apply(subject: Subject, verdict: Verdict, tally: Tally) = when (verdict) {
        Verdict.Skip -> Unit
        Verdict.Unknown -> tally.complete.set(false)
        is Verdict.Mark -> if (SeriesStatuses.setIfNone(subject.name, verdict.status, subject.source, subject.slug, subject.aid)) tally.count(verdict.status) else Unit
    }

    /** Devuelve si tuvo con qué trabajar (había calendario de emisión). */
    private suspend fun run(): Boolean {
        val airing = runCatchingCoop { AnimeSources.scheduleAll().values.flatten() }.getOrNull().orEmpty()
        // Sin el calendario no se sabe qué está en emisión: una serie al día se daría por completada.
        if (airing.isEmpty()) return false
        val airingTitles = airing.map { normalizeTitle(it.name) }.toSet()
        val airingSlugs = airing.map { SeriesMatch.slugBase(it.slug) }.toSet()
        val isAiring = { name: String, slug: String -> normalizeTitle(name) in airingTitles || SeriesMatch.slugBase(slug) in airingSlugs }
        val history = withContext(Dispatchers.IO) { CacheDB.INSTANCE.recordAV1DAO().all.toList() }
        val byTitle = history.groupBy { normalizeTitle(it.name) }
        val tally = Tally()
        val gate = Semaphore(4)
        val totalOf: suspend (Subject) -> Int? = { subject -> gate.withPermit { Catalog.episodeCount(subject.source, subject.slug) } }

        // Las series del historial, las más recientes primero.
        val subjects = byTitle.values
            .sortedByDescending { chapters -> chapters.maxOf { it.date } }
            .take(MAX_CHECKED)
            .map { chapters -> chapters.maxBy { it.date }.let { latest -> Subject(latest.name, latest.source, latest.slug, latest.aid, wholeEpisodes(chapters.map { it.number }), true) } }
        // Las favoritas que siguen sin estado.
        val favorites = withContext(Dispatchers.IO) { CacheDB.INSTANCE.favoriteAV1DAO().allRaw }.map { favorite ->
            val chapters = byTitle[normalizeTitle(favorite.name)].orEmpty()
            Subject(favorite.name, favorite.source, favorite.slug, favorite.aid, wholeEpisodes(chapters.map { it.number }), chapters.isNotEmpty())
        }
        // Primero el historial y después las favoritas (una favorita con historial ya tendrá estado): no a la vez, para no pisarse.
        suspend fun migrate(group: List<Subject>, isFavorite: Boolean) = coroutineScope {
            group.map { subject ->
                async {
                    if (SeriesStatuses.of(subject.name, subject.source, subject.slug) != SeriesStatus.NONE) return@async
                    apply(subject, decide(subject, isAiring(subject.name, subject.slug), isFavorite, totalOf = totalOf), tally)
                }
            }.awaitAll()
        }
        migrate(subjects, isFavorite = false)
        migrate(favorites, isFavorite = true)
        if (tally.complete.get()) KaizenSettings.statusesMigrated = true
        val (watching, completed, pending) = Triple(tally.of(SeriesStatus.WATCHING), tally.of(SeriesStatus.COMPLETED), tally.of(SeriesStatus.PLAN))
        if (watching + completed + pending > 0) KaizenToast.toast("Estados: $completed completadas, $watching viendo, $pending pendientes")
        return true
    }
}
