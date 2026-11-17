package app.kaizen.tv.exoplayer

/** Lo que hay encima del vídeo y se lleva las teclas. Solo puede haber una cosa a la vez. */
sealed interface PlayerOverlay {
    data object None : PlayerOverlay

    /** Panel de calidad, audio y subtítulos. */
    data object Tracks : PlayerOverlay

    /** Aviso del siguiente capítulo con su cuenta atrás. */
    data class NextUp(val seconds: Int) : PlayerOverlay

    /** Pregunta si reanudar en [positionMs] o empezar de nuevo. */
    data class Resume(val positionMs: Long) : PlayerOverlay
}
