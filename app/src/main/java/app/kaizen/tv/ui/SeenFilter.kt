package app.kaizen.tv.ui

import app.kaizen.pojos.av1.Record

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
