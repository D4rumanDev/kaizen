package app.kaizen.tv.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaizen.pojos.av1.Record
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlin.time.Duration.Companion.seconds

/** Qué capítulos de «Recientes» se muestran: todos, solo los vistos o solo los no vistos. */
internal enum class SeenFilter(val label: String) { ALL("Todos"), SEEN("Vistos"), UNSEEN("No vistos") }

/**
 * Lo visto, en memoria, con el mismo criterio que `RecordDao.chapterIsSeen` (el ojo de la tarjeta): el mismo capítulo (eid ≠ 0) o el
 * mismo número de la misma serie (mismo id de serie ≠ 0). El aid no cuenta: es de la fuente y se repite entre series distintas (5 de 394 en la copia del 27-nov). «Visto» es lo acabado, como el check verde: un capítulo empezado y sin acabar
 * ([resuming] dice si lo está, por id de serie, número y eid) lleva el triángulo azul y cuenta como no visto, igual que uno sin marca.
 */
internal class SeenIndex(records: List<Record>, private val resuming: (seriesId: Long, number: Double, eid: Int) -> Boolean = { _, _, _ -> false }) {
    private val eids = records.mapTo(HashSet()) { it.eid }
    private val bySeries = records.mapNotNull { r -> r.seriesId.takeIf { it != 0L }?.let { it to r.number } }.toHashSet()

    fun isSeen(eid: Int, aid: Int, seriesId: Long, number: Double): Boolean =
        ((eid != 0 && eid in eids) || (seriesId != 0L && (seriesId to number) in bySeries)) && !resuming(seriesId, number, eid)
}

/**
 * Los capítulos vistos (id de serie y número), en memoria y compartidos por todas las tarjetas de «Recientes». Es el criterio de `RecordDao.chapterIsSeen`
 * (el registro con ese número cuya serie es la de la ficha), pero con **una** suscripción a Room para toda la pantalla: antes cada tarjeta abría la suya y cada
 * cambio en lo visto relanzaba una consulta por tarjeta. null mientras se lee la primera vez. Se mantiene mientras alguien lo mira (y unos segundos después).
 */
internal object SeenChapters {
    // Antes de [all]: lo usa al crearse.
    private val KEEP = 5.seconds

    val all: kotlinx.coroutines.flow.StateFlow<Set<Pair<Long, Double>>?> by lazy {
        app.kaizen.domain.Library.history
            .map { records -> seenOf(records) }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(app.kaizen.commons.AppScope, SharingStarted.WhileSubscribed(KEEP), null)
    }

    /** Lo visto de [records] por serie y número (los registros sin serie no cuentan: la consulta de Room tampoco los encuentra). */
    fun seenOf(records: List<Record>): Set<Pair<Long, Double>> = records.mapNotNullTo(HashSet()) { r -> r.seriesId.takeIf { it != 0L }?.let { it to r.number } }
}

/** ¿Está visto el capítulo [number] de la serie de la ficha ([source], [slug])? Lo leen las tarjetas de capítulo de «Recientes», de la ficha y del reproductor, todas de [SeenChapters]. */
@androidx.compose.runtime.Composable
internal fun rememberSeen(source: String, slug: String, number: Double): Boolean {
    // El id de serie sale del registro en memoria, que se carga al arrancar: al cambiar se vuelve a mirar.
    val ledger = app.kaizen.source.SeriesLedger.version.collectAsStateWithLifecycle().value
    val seen = SeenChapters.all.collectAsStateWithLifecycle().value
    return remember(source, slug, number, ledger, seen) { seen?.contains(app.kaizen.source.seriesIdOf(source, slug) to number) == true }
}

/** Cómo se marca un capítulo en todas las tarjetas (Recientes, ficha, reproductor): sin marca, visto (acabado) o empezado y sin acabar (se puede seguir). */
internal enum class CardWatch { NONE, SEEN, RESUMING }

/** Lo que dicen las tarjetas de un capítulo: la marca ([state]) y, si está empezado, su progreso ([started], para la barra). */
internal class ChapterWatch(val state: CardWatch, val started: app.kaizen.commons.KaizenProgress.Progress?)

/**
 * El estado de un capítulo para pintarlo, igual en todas las tarjetas: [rememberSeen] y el progreso guardado, que se relee cuando cambia (al salir del reproductor) o cuando el
 * libro de series conoce la serie. Un capítulo se guarda como visto al empezar, así que lo empezado es [CardWatch.RESUMING] y no lleva el check.
 */
@androidx.compose.runtime.Composable
internal fun rememberChapterWatch(source: String, slug: String, number: Double, eid: Int): ChapterWatch {
    val ledger = app.kaizen.source.SeriesLedger.version.collectAsStateWithLifecycle().value
    val changes = app.kaizen.commons.KaizenProgress.changes.collectAsStateWithLifecycle().value
    val seen = rememberSeen(source, slug, number)
    return remember(source, slug, number, eid, ledger, changes, seen) {
        val started = app.kaizen.commons.KaizenProgress.startedOf(app.kaizen.commons.KaizenProgress.Key(app.kaizen.source.seriesIdOf(source, slug), number), eid)
        val watch = ChapterWatch(cardWatchOf(seen, started), started)
        StaleCardCheck.watch(source, slug, number, watch.state)
        watch
    }
}

/** ¿Una tarjeta está desfasada? Pinta «sin marca» ([state] NONE) con todo cargado ([ready]) y, sin embargo, la base tiene ese capítulo visto ([seenInDb]). */
internal fun isStaleCard(state: CardWatch, ready: Boolean, seenInDb: Boolean): Boolean = ready && state == CardWatch.NONE && seenInDb

/**
 * TEMPORAL (27-nov-2026): detector de tarjetas desfasadas para cualquier serie. Si una tarjeta sale «sin marca», pregunta en segundo plano a la base (la misma consulta que
 * `chapterIsSeen`) y, si dice que está visto, lo anota con los registros de esa serie y su ficha en el libro, para entender por qué. Como mucho una vez por minuto y capítulo,
 * y solo con los vistos y el libro de series ya cargados. Quitar cuando pase una semana sin recaídas.
 */
private object StaleCardCheck {
    private val EVERY = 60.seconds
    private const val MAX_KEYS = 500
    private val last = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun watch(source: String, slug: String, number: Double, state: CardWatch) {
        if (state != CardWatch.NONE) return
        val seenSet = SeenChapters.all.value
        val ready = seenSet != null && app.kaizen.source.SeriesLedger.version.value > 0
        if (!ready) return
        val key = "$source|$slug|$number"
        val now = System.currentTimeMillis()
        if (last.size > MAX_KEYS) last.clear()
        val before = last[key]
        if (before != null && now - before < EVERY.inWholeMilliseconds) return
        last[key] = now
        kotlinx.coroutines.CoroutineScope(app.kaizen.commons.AppScope.coroutineContext + kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                if (!isStaleCard(state, ready, app.kaizen.domain.Library.chapterSeen(source, slug, number))) return@runCatching
                val id = app.kaizen.source.seriesIdOf(source, slug)
                val mine = app.kaizen.domain.Library.historyNow().filter { it.slug == slug }.joinToString { "cap=${it.number} eid=${it.eid} serie=${it.seriesId}" }
                app.kaizen.commons.DebugLog.info("Diagnóstico", "Tarjeta desfasada: $source/$slug cap=$number sin marca, pero la base lo tiene visto. Id de serie en memoria=$id; el conjunto de vistos lo contiene=${seenSet?.contains(id to number)}; versión del libro=${app.kaizen.source.SeriesLedger.version.value}; registros de la ficha: $mine")
            }
        }
    }
}

/**
 * Un capítulo se guarda como visto en cuanto empieza a reproducirse, así que «visto» solo no dice si se acabó: con progreso a medias ([progress] de
 * [app.kaizen.commons.KaizenProgress.startedOf]) es «seguir viendo» y no lleva el check verde; el check queda para los acabados.
 */
internal fun cardWatchOf(seen: Boolean, progress: app.kaizen.commons.KaizenProgress.Progress?): CardWatch = when {
    progress != null -> CardWatch.RESUMING
    seen -> CardWatch.SEEN
    else -> CardWatch.NONE
}
