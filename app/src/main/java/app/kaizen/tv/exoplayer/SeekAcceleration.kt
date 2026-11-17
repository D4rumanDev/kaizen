package app.kaizen.tv.exoplayer

/**
 * Kaizen: salto de las flechas izquierda/derecha con los controles ocultos. Parte del salto de Ajustes
 * y, mientras se mantiene la tecla, sube [INCREMENT_SECONDS] cada [REPEATS_PER_STEP] repeticiones hasta [CAP_SECONDS].
 */
object SeekAcceleration {
    private const val REPEATS_PER_STEP = 3
    private const val INCREMENT_SECONDS = 10
    const val DEFAULT_CAP_SECONDS = 90

    /** [capSeconds] es el ajuste «Salto máximo al mantener la tecla»; el salto base nunca se recorta por debajo de sí mismo. */
    fun stepSeconds(baseSeconds: Int, repeatCount: Int, capSeconds: Int = DEFAULT_CAP_SECONDS): Int =
        minOf(maxOf(capSeconds, baseSeconds), baseSeconds + repeatCount / REPEATS_PER_STEP * INCREMENT_SECONDS)
}
