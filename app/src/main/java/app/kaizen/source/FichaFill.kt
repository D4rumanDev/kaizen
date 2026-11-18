package app.kaizen.source

import app.kaizen.database.CatalogDB
import app.kaizen.database.UserDB
import app.kaizen.commons.HostGate
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.Network
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Rellena `WebFicha` de las fichas que aún están **por revisar** con AniList (enlace `pendiente`) y de las que todavía no se ha abierto ninguna copia: es lo que dice cada
 * web de la serie (año, formato, episodios, `idMal`…), la prueba con la que luego se empareja. No toca nada más que `WebFicha`; abrirlas es lo mismo que abrir la ficha en
 * la app ([AnimeSource.anime]), con las mismas salidas HTTP (límite de ritmo y pausas por bloqueo de `HostGate`).
 *
 * Se hace por tramos con tope: [plan] elige a lo sumo [limit] fichas repartidas **por turnos entre las fuentes** (para que una fuente no se lleve todo el tope) y [run] las
 * abre una a una con una pausa entre peticiones y deja una fuente si falla [MAX_CONSECUTIVE_FAILURES] veces seguidas (un bloqueo no se insiste).
 */
internal object FichaFill {
    const val MAX_CONSECUTIVE_FAILURES = 3
    val PAUSE = 1500.milliseconds
    const val MAX_LIMIT = 200

    /** En segundo plano: una tanda como mucho cada [MIN_INTERVAL], de [BATCH] fichas y [DAILY_CAP] al día. */
    const val BATCH = 20
    val MIN_INTERVAL = 10.minutes
    const val DAILY_CAP = 300

    /** Cuántas fichas abrir ahora en segundo plano: 0 si la última tanda fue hace menos de [MIN_INTERVAL] o se llegó al tope del día. [day] y [doneToday] son del último día contado. */
    fun allowance(now: Long, lastAt: Long, day: Long, doneToday: Int, batch: Int = BATCH, dailyCap: Int = DAILY_CAP): Int {
        if (now - lastAt < MIN_INTERVAL.inWholeMilliseconds) return 0
        val used = if (day == now / 1.days.inWholeMilliseconds) doneToday else 0
        return (dailyCap - used).coerceIn(0, batch.coerceAtLeast(0))
    }

    /** Una ficha que falló no se reintenta hasta pasado este tiempo (una página muerta no debe gastar el tope de cada tanda). */
    val RETRY_AFTER = 7.days
    private const val MAX_FAILURES_KEPT = 2_000

    /** El fallo no es de la ficha sino del momento (sin red, servidor en pausa): no se anota como fallo ni cuenta para dejar la fuente; la fuente se deja para el resto de la tanda. */
    class Transient : RuntimeException()

    /** Solo una tanda a la vez, venga de la acción ADB o del trabajo en segundo plano: el ritmo y el tope valen para las dos. */
    private val running = AtomicBoolean(false)
    fun tryLock(): Boolean = running.compareAndSet(false, true)
    fun unlock() { running.set(false) }

    /** Los fallos guardados: `fuente|slug|ms` por línea. */
    fun parseFailures(text: String?): Map<Pair<String, String>, Long> = text.orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('|'); if (p.size == 3) p[2].toLongOrNull()?.let { (p[0] to p[1]) to it } else null
    }.toMap()

    /** Los fallos de hace menos de [RETRY_AFTER] más los [fresh] de ahora, como mucho [MAX_FAILURES_KEPT] (los más recientes). */
    fun encodeFailures(old: Map<Pair<String, String>, Long>, fresh: Collection<Pair<String, String>>, now: Long): String =
        (old.filter { now - it.value < RETRY_AFTER.inWholeMilliseconds } + fresh.associateWith { now }).entries.sortedByDescending { it.value }.take(MAX_FAILURES_KEPT)
            .joinToString("\n") { "${it.key.first}|${it.key.second}|${it.value}" }

    /** El número de día de [now] (el que se guarda junto a lo hecho en el día). */
    fun dayOf(now: Long): Long = now / 1.days.inWholeMilliseconds

    /** Las fichas a abrir: sin enlace con AniList (por revisar con candidato o sin resultado), sin `WebFicha` ([has]) y, si se pide, solo de [source]; a turnos entre fuentes, como mucho [limit]. */
    fun plan(links: Collection<AnimeFuente>, has: (String, String) -> Boolean, limit: Int, source: String? = null, skip: (String, String) -> Boolean = { _, _ -> false }): List<Pair<String, String>> {
        val perSource = links.asSequence()
            .filter { ((it.estado == AnimeFuente.PENDIENTE && it.anilistId != null) || it.estado == AnimeFuente.SIN_RESULTADO) && it.origen == AnimeFuente.AUTO && (source == null || it.fuente == source) && !has(it.fuente, it.slug) && !skip(it.fuente, it.slug) }
            .sortedWith(compareBy({ it.fuente }, { it.slug }))
            .groupBy { it.fuente }.values.map { it.map { l -> l.fuente to l.slug }.iterator() }
        val out = ArrayList<Pair<String, String>>()
        val cap = limit.coerceIn(0, MAX_LIMIT)
        while (out.size < cap && perSource.any { it.hasNext() }) perSource.forEach { if (out.size < cap && it.hasNext()) out += it.next() }
        return out
    }

    /** El plan con las fichas de verdad (enlaces de user-db y copias de catalog-db). */
    fun planNow(limit: Int, source: String? = null): List<Pair<String, String>> {
        val web = CatalogDB.INSTANCE.webFichaDao()
        val failed = parseFailures(KaizenSettings.fillFailed); val now = System.currentTimeMillis()
        // Solo las webs activas: una desactivada no se consulta (ni se pausa por no responder) por rellenar sus fichas.
        val active = AnimeSources.selected.map { it.id }.toSet()
        return plan(UserDB.INSTANCE.animeFuenteDao().all().filter { it.fuente in active }, { f, s -> web.get(f, s) != null }, limit, source) { f, s -> failed[f to s]?.let { now - it < RETRY_AFTER.inWholeMilliseconds } == true }
    }

    /** Abre [todo] con las fuentes de verdad: una ficha cuenta solo si de verdad dejó su `WebFicha` (una copia local de la fuente no basta). */
    suspend fun runNow(todo: List<Pair<String, String>>, canRun: () -> Boolean = { true }): Report {
        val web = CatalogDB.INSTANCE.webFichaDao()
        val report = withContext(app.kaizen.commons.RequestCounter.Background) { run(todo, { fuente, slug ->
            val source = AnimeSources.of(fuente)
            // Una web desactivada no se abre: se deja para el resto de la tanda, sin castigar a la ficha.
            if (source.id != fuente) false else if (AnimeSources.selected.none { it.id == fuente }) throw Transient() else {
                val url = source.animeUrl(slug)
                when {
                    source.anime(url) != null && web.get(fuente, slug) != null -> true
                    // Sin red o con el servidor en pausa, el fallo es del momento: no se castiga a la ficha una semana.
                    !Network.isConnected || HostGate.downUntil(runCatching { java.net.URI(url).host }.getOrNull().orEmpty()) > 0L -> throw Transient()
                    else -> false
                }
            }
        }, canRun) }
        // Lo que falló no se reintenta en la próxima tanda: se anota (una semana de espera).
        if (report.failedPairs.isNotEmpty()) KaizenSettings.fillFailed = encodeFailures(parseFailures(KaizenSettings.fillFailed), report.failedPairs, System.currentTimeMillis())
        return report
    }

    /** Lo que dio una pasada: por fuente, cuántas fichas se abrieron bien, cuántas fallaron y si se dejó de insistir. */
    class Report(
        val ok: Map<String, Int>, val failed: Map<String, Int>, val stopped: Set<String>, val failedPairs: List<Pair<String, String>> = emptyList(),
        /** Las que no se pudieron abrir por el momento (sin red, servidor en pausa): no cuentan como fallo. */
        val transient: Map<String, Int> = emptyMap()
    ) {
        val total get() = ok.values.sum()
        /** Cuántas fichas se llegaron a intentar (abiertas, fallidas o aplazadas). */
        val tried get() = ok.values.sum() + failed.values.sum() + transient.values.sum()
        override fun toString() = (ok.keys + failed.keys + transient.keys).sorted().joinToString(", ") { s ->
            "$s ok=${ok[s] ?: 0} fallo=${failed[s] ?: 0}" + (transient[s]?.let { " aplazadas=$it" } ?: "") + if (s in stopped) " (dejada)" else ""
        }.ifEmpty { "nada que abrir" }
    }

    /** Abre las fichas de [todo] con [open] (true si se guardó), con [pause] entre peticiones; [canRun] permite parar. */
    suspend fun run(todo: List<Pair<String, String>>, open: suspend (String, String) -> Boolean, canRun: () -> Boolean = { true }, pause: suspend (Duration) -> Unit = { delay(it) }): Report {
        val transient = HashMap<String, Int>()
        val ok = HashMap<String, Int>(); val failed = HashMap<String, Int>(); val streak = HashMap<String, Int>(); val stopped = HashSet<String>(); val failedPairs = ArrayList<Pair<String, String>>()
        for ((source, slug) in todo) {
            if (!canRun()) break
            if (source in stopped) continue
            val saved = try { open(source, slug) } catch (e: Transient) { transient.merge(source, 1, Int::plus); stopped += source; continue } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
            if (saved) { ok.merge(source, 1, Int::plus); streak[source] = 0 }
            else {
                failed.merge(source, 1, Int::plus); failedPairs += source to slug
                if (streak.merge(source, 1, Int::plus)!! >= MAX_CONSECUTIVE_FAILURES) stopped += source
            }
            pause(PAUSE)
        }
        return Report(ok, failed, stopped, failedPairs, transient)
    }
}
