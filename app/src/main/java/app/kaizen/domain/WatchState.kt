package app.kaizen.domain

import app.kaizen.commons.KaizenProgress
import app.kaizen.commons.roundedString
import app.kaizen.pojos.av1.Chapter

/** Qué ver ahora de una serie y los textos de los botones de su ficha, según el historial y el progreso guardado. */
data class WatchState(
    val target: Chapter?,
    val playLabel: String,
    /** Versión corta de [playLabel] para botones con poco sitio: «Ver Ep. 3», «Seguir Ep. 3». */
    val actionLabel: String,
    val seenLabel: String,
    val allSeen: Boolean,
    val isFavorite: Boolean,
    /** Ids de los capítulos vistos al calcularlo (la ficha los usa para el progreso y los filtros). */
    val seenEids: Set<Int> = emptySet()
) {
    companion object {
        /**
         * Calcula el estado sin tocar la base de datos. Prioridad: el capítulo empezado de número más alto, el primero si
         * no se ha visto ninguno, el siguiente al último visto y, con todos vistos, el primero otra vez.
         */
        fun compute(
            episodes: List<Chapter>,
            seenEids: Set<Int>,
            inProgress: (Chapter) -> KaizenProgress.Progress?,
            isFavorite: Boolean
        ): WatchState {
            val resuming = episodes.filter { inProgress(it) != null }.maxByOrNull { it.number }
            val resumingProgress = resuming?.let(inProgress)
            val lastSeen = episodes.lastOrNull { it.eid in seenEids }
            val first = episodes.firstOrNull()
            val next = lastSeen?.let { episodes.getOrNull(episodes.indexOf(it) + 1) }
            val (target, label) = when {
                first == null -> null to "Sin episodios"
                // «Seguir» solo si se va a reanudar (desde 30 s); un capítulo dejado al segundo se vuelve a ver desde el principio.
                resuming != null && resumingProgress?.isResumable == true ->
                    resuming to "Seguir ${resuming.episodeName} · ${KaizenProgress.formatTime(resumingProgress.positionMs)}"
                resuming != null -> resuming to "Ver ${resuming.episodeName}"
                lastSeen == null -> first to "Ver ${first.episodeName}"
                next != null -> next to "Siguiente: ${next.episodeName}"
                else -> first to "Ver ${first.episodeName}"
            }
            val resumes = resuming != null && resumingProgress?.isResumable == true
            val actionLabel = if (target == null) label else "${if (resumes) "Seguir" else "Ver"} Ep. ${target.number.roundedString()}"
            val seenCount = episodes.count { it.eid in seenEids }
            val allSeen = episodes.isNotEmpty() && seenCount == episodes.size
            val seenLabel = if (allSeen) "✓  Desmarcar todos ($seenCount/${episodes.size})" else "✓  Marcar todos ($seenCount/${episodes.size})"
            return WatchState(target, label, actionLabel, seenLabel, allSeen, isFavorite, seenEids)
        }
    }
}
