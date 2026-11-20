package app.kaizen.tv.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaizen.pojos.av1.Record
import androidx.compose.runtime.remember
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
 * mismo número de la misma serie (mismo aid o mismo id de serie ≠ 0). «Visto» es lo acabado, como el check verde: un capítulo empezado y sin acabar
 * ([resuming] dice si lo está, por id de serie, número y eid) lleva el triángulo azul y cuenta como no visto, igual que uno sin marca.
 */
internal class SeenIndex(records: List<Record>, private val resuming: (seriesId: Long, number: Double, eid: Int) -> Boolean = { _, _, _ -> false }) {
    private val eids = records.mapTo(HashSet()) { it.eid }
    private val byAid = records.mapTo(HashSet()) { it.aid to it.number }
    private val bySeries = records.mapNotNull { r -> r.seriesId.takeIf { it != 0L }?.let { it to r.number } }.toHashSet()

    fun isSeen(eid: Int, aid: Int, seriesId: Long, number: Double): Boolean =
        ((eid != 0 && eid in eids) || (aid to number) in byAid || (seriesId != 0L && (seriesId to number) in bySeries)) && !resuming(seriesId, number, eid)
}

/**
 * Los capítulos vistos (id de serie y número), en memoria y compartidos por todas las tarjetas de «Recientes». Es el criterio de `RecordDao.chapterIsSeenFlow`
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

/** Cómo se marca un capítulo en «Recientes»: sin marca, visto (acabado) o empezado y sin acabar (se puede seguir). */
internal enum class CardWatch { NONE, SEEN, RESUMING }

/**
 * Un capítulo se guarda como visto en cuanto empieza a reproducirse, así que «visto» solo no dice si se acabó: con progreso a medias ([progress] de
 * [app.kaizen.commons.KaizenProgress.startedOf]) es «seguir viendo» y no lleva el check verde; el check queda para los acabados.
 */
internal fun cardWatchOf(seen: Boolean, progress: app.kaizen.commons.KaizenProgress.Progress?): CardWatch = when {
    progress != null -> CardWatch.RESUMING
    seen -> CardWatch.SEEN
    else -> CardWatch.NONE
}
