package app.kaizen.tv.exoplayer

import app.kaizen.domain.ConnectionStats.Cause
import app.kaizen.domain.Incident
import app.kaizen.domain.IncidentSummary
import app.kaizen.domain.summarize
import androidx.media3.common.Player

/** Lo que le puede pasar a una reproducción. [at] es la hora del suceso en ms (un reloj monótono: lo pone quien llama, así la máquina no depende de Android). */
sealed interface SessionEvent {
    val at: Long

    /** Cambio de estado del reproductor ([Player.STATE_BUFFERING], READY…). */
    data class PlayerState(val state: Int, override val at: Long) : SessionEvent

    /** Salto de posición (del usuario o de omitir intro/cierre). */
    data class Seek(override val at: Long) : SessionEvent

    /** Se pintó el primer fotograma. */
    data class FirstFrame(override val at: Long) : SessionEvent

    /** El decodificador perdió [frames] fotogramas. */
    data class Dropped(val frames: Int, override val at: Long) : SessionEvent

    /** La salida de audio se quedó sin datos. */
    data class Underrun(override val at: Long) : SessionEvent

    /** Algo falló: la [cause] (del catálogo único de errores) y el detalle técnico. */
    data class Failure(val cause: Cause, val detail: String, override val at: Long) : SessionEvent
}

/**
 * **La máquina de estados de una reproducción**: un estado inmutable que avanza con [on], un evento cada vez, sin banderas sueltas ni contadores por causa.
 * La fase la lleva [PlaybackPhase]; de las transiciones salen el arranque, las recargas (cuántas y cuánto esperaron), los saltos de imagen y los cortes
 * de audio. Los errores se anotan como [incidents] (una lista de lo ocurrido) y los totales por causa se **derivan** al pedirlos ([incidentSummary]).
 * Pura: se prueba sin Android.
 */
data class PlaybackSession(
    val phase: PlaybackPhase = PlaybackPhase.START,
    /** Fase anterior a la última transición de estado del reproductor: quien registra sucesos compara con ella. */
    val previous: PlaybackPhase = PlaybackPhase.START,
    /** Cuándo se entró en la fase actual. */
    val phaseSince: Long = 0,
    /** Cuándo empezó la primera carga (-1: aún no). */
    val loadStart: Long = -1,
    /** Ms desde que empezó a cargar hasta el primer fotograma (-1: aún no). */
    val startupMs: Long = -1,
    val rebuffers: Int = 0,
    /** Espera acumulada de las recargas ya terminadas. */
    val rebufferMs: Long = 0,
    /** Espera de la última recarga que terminó. */
    val lastRebufferMs: Long = 0,
    val droppedFrames: Int = 0,
    val underruns: Int = 0,
    val incidents: List<Incident> = emptyList()
) {
    fun on(event: SessionEvent): PlaybackSession = when (event) {
        is SessionEvent.PlayerState -> onPlayerState(event)
        is SessionEvent.Seek -> {
            val next = phase.onSeek()
            // Un salto en mitad de una recarga la termina: su espera se suma, no se pierde.
            val waited = if (phase == PlaybackPhase.REBUFFER && next != PlaybackPhase.REBUFFER) event.at - phaseSince else 0L
            copy(
                phase = next, previous = if (waited > 0) phase else previous, phaseSince = if (next == PlaybackPhase.SEEKING) event.at else phaseSince,
                rebufferMs = rebufferMs + waited, lastRebufferMs = if (waited > 0) waited else lastRebufferMs
            )
        }
        is SessionEvent.FirstFrame -> if (startupMs < 0 && loadStart >= 0) copy(startupMs = event.at - loadStart) else this
        is SessionEvent.Dropped -> copy(droppedFrames = droppedFrames + event.frames)
        is SessionEvent.Underrun -> copy(underruns = underruns + 1)
        is SessionEvent.Failure -> copy(incidents = (incidents + Incident(event.cause, event.detail, event.at)).takeLast(MAX_INCIDENTS))
    }

    private fun onPlayerState(event: SessionEvent.PlayerState): PlaybackSession {
        val before = phase.expireSeek(event.at - phaseSince)
        val next = before.onPlayerState(event.state)
        var s = copy(phase = next, previous = before, phaseSince = if (next != before) event.at else phaseSince)
        if (event.state == Player.STATE_BUFFERING && loadStart < 0) s = s.copy(loadStart = event.at)
        if (before != PlaybackPhase.REBUFFER && next == PlaybackPhase.REBUFFER) s = s.copy(rebuffers = rebuffers + 1)
        if (before == PlaybackPhase.REBUFFER && next != PlaybackPhase.REBUFFER) {
            val waited = event.at - phaseSince
            s = s.copy(rebufferMs = rebufferMs + waited, lastRebufferMs = waited)
        }
        return s
    }

    /** La espera total de las recargas a la hora [now], contando la que está en curso. */
    fun rebufferMsAt(now: Long): Long = rebufferMs + if (phase == PlaybackPhase.REBUFFER) now - phaseSince else 0L

    /** ¿Acaba de empezar una recarga con el último evento de estado? */
    val rebufferStarted: Boolean get() = previous != PlaybackPhase.REBUFFER && phase == PlaybackPhase.REBUFFER

    /** ¿Acaba de terminar una recarga con el último evento de estado? */
    val rebufferEnded: Boolean get() = previous == PlaybackPhase.REBUFFER && phase != PlaybackPhase.REBUFFER

    /** Los fallos agrupados por causa, en orden de aparición, con cuántos hubo y el último detalle. */
    fun incidentSummary(): List<IncidentSummary> = incidents.summarize()

    companion object {
        /** Tope de incidentes guardados: una reproducción rota en bucle no debe crecer sin límite. */
        const val MAX_INCIDENTS = 200
    }
}
