package app.kaizen.tv.exoplayer

import androidx.media3.common.Player
import kotlin.time.Duration.Companion.seconds

/**
 * Máquina de estados de la reproducción para el panel de depuración: en qué fase está el vídeo y por qué se detiene. Sustituye a las banderas
 * sueltas («ya estuvo listo», «está saltando»…) que decían lo mismo de forma implícita. Es pura: no toca Android, así que se prueba sola.
 *
 * ```
 * START ──READY──▶ READY ──BUFFERING──▶ REBUFFER ──READY──▶ READY
 *   │                 │ Seek                                    │
 *   │                 ▼                                         ▼
 *   └──Seek──▶ (START) SEEKING ──READY──▶ READY          ENDED ──Seek/BUFFERING──▶ SEEKING
 * ```
 */
enum class PlaybackPhase(val label: String) {
    /** Carga inicial: aún no se ha podido reproducir. */
    START("carga inicial"),

    /** Reproduciendo (o en pausa) con datos suficientes. */
    READY("lista"),

    /** Se quedó sin datos a mitad: la red no llega. Es lo que cuenta como «recarga». */
    REBUFFER("recargando"),

    /** Cargando tras un salto pedido (del usuario o de omitir intro/cierre): no es un fallo. */
    SEEKING("tras un salto"),

    /** El vídeo terminó. */
    ENDED("terminada");

    /** La fase tras un cambio de estado del reproductor ([Player.STATE_IDLE], BUFFERING, READY, ENDED). */
    fun onPlayerState(state: Int): PlaybackPhase = when (state) {
        Player.STATE_READY -> READY
        Player.STATE_ENDED -> ENDED
        Player.STATE_BUFFERING -> when (this) {
            READY -> REBUFFER
            ENDED -> SEEKING
            else -> this
        }
        else -> this
    }

    /** La fase tras un salto de posición. Antes de la primera carga no cambia nada: sigue siendo carga inicial. */
    fun onSeek(): PlaybackPhase = if (this == START) START else SEEKING

    /**
     * Un salto dentro de lo ya descargado no provoca ningún cambio de estado y no se vería terminar: pasados [SEEK_WINDOW] sin cargar, el salto
     * se da por resuelto y una carga posterior ya es una recarga de verdad.
     */
    fun expireSeek(sinceSeekMs: Long): PlaybackPhase = if (this == SEEKING && sinceSeekMs > SEEK_WINDOW.inWholeMilliseconds) READY else this

    /** Por qué está cargando en esta fase, en claro. */
    fun bufferingReason(bandwidth: String): String = when (this) {
        START -> "carga inicial"
        SEEKING -> "tras un salto"
        else -> "el búfer se vació: la red no llega a tiempo (≈ $bandwidth)"
    }

    companion object {
        val SEEK_WINDOW = 3.seconds
    }
}
