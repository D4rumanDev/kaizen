package app.kaizen.tv.ui

import app.kaizen.commons.HostGate

/** Estado de una fuente tal como lo pinta su tarjeta de «Fuentes»: piloto y texto. */
internal data class SourceState(val health: SourcesHealth, val text: String)

/** Por encima de esto una fuente que responde se marca como lenta (con el piloto de aviso). */
internal const val SLOW_SOURCE_MS = 1_500L

/**
 * Qué dice la tarjeta de una fuente. El piloto y el texto salen del mismo dato (antes el piloto solo miraba las pausas y los fallos,
 * y hasta que terminaba la primera medición ponía «Activa», un texto que a los segundos desaparecía). Mientras se mide, [measuring],
 * no pone texto y deja el piloto neutro; una fuente lenta o con un error HTTP lleva el piloto de aviso aunque responda.
 * [clock] da formato a una hora.
 */
internal fun sourceState(
    active: Boolean,
    pausedUntil: Long,
    result: HostGate.CheckResult?,
    failure: Pair<Long, String>?,
    measuring: Boolean,
    clock: (Long) -> String,
    laLiga: Boolean = false
): SourceState = when {
    !active -> SourceState(SourcesHealth.OK, "✖ Desactivada")
    // El operador bloquea la IP de la fuente por un partido (hayahora.futbol): se dice, en vez de «no responde» o «en pausa».
    laLiga -> SourceState(SourcesHealth.DOWN, "Bloqueo de LaLiga (E23) · se pasa al acabar el partido")
    pausedUntil > 0 -> SourceState(SourcesHealth.DOWN, "En pausa hasta ${clock(pausedUntil)}")
    result?.ok == true && (result.code ?: 0) >= 400 -> SourceState(SourcesHealth.PARTIAL, "Responde con error HTTP ${result.code} · ${result.millis} ms")
    result?.ok == true && result.millis > SLOW_SOURCE_MS -> SourceState(SourcesHealth.PARTIAL, "Lenta · ${result.millis} ms")
    result?.ok == true -> SourceState(SourcesHealth.OK, "Responde · ${result.millis} ms")
    result != null -> SourceState(SourcesHealth.DOWN, "No responde${result.error?.let { " ($it)" } ?: ""}")
    // Fallo reciente sin pausa: no responde del todo bien, aunque aún se use.
    failure != null -> SourceState(SourcesHealth.PARTIAL, "⚠ Fallo a las ${clock(failure.first)}: ${failure.second.take(80)}")
    // Midiendo: sin texto (el círculo vacío basta; un «Midiendo…» que desaparece a los segundos solo distrae).
    measuring -> SourceState(SourcesHealth.CHECKING, "")
    else -> SourceState(SourcesHealth.CHECKING, "Sin medir · pulsa «Comprobar todas»")
}

/** Resumen de todas las fuentes activas para la tarjeta «Comprobar todas»: cuántas responden bien de las que se han medido. */
internal fun sourcesSummary(states: List<SourceState>): String {
    val measured = states.filter { it.health != SourcesHealth.CHECKING }
    if (states.isEmpty()) return ""
    if (measured.isEmpty()) return ""
    val good = measured.count { it.health == SourcesHealth.OK }
    return "$good de ${states.size} responden bien"
}
