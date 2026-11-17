package app.kaizen.domain

import app.kaizen.domain.ConnectionStats.Cause

/** Un fallo ocurrido: lo que se guarda en vez de contadores por causa. */
data class Incident(val cause: Cause, val detail: String, val at: Long)

/** Un tipo de fallo con cuántas veces ha pasado y su último detalle, para enseñarlo agrupado. */
data class IncidentSummary(val cause: Cause, val count: Int, val lastDetail: String)

/** Los incidentes agrupados por causa, en orden de aparición, con cuántos hubo y el último detalle: los totales **se derivan** de la lista, no se guardan. */
fun List<Incident>.summarize(): List<IncidentSummary> = map { it.cause }.distinct().map { cause ->
    val ofCause = filter { it.cause == cause }
    IncidentSummary(cause, ofCause.size, ofCause.last().detail)
}
