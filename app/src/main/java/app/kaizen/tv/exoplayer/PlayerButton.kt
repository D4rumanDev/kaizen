package app.kaizen.tv.exoplayer

import androidx.media3.ui.AspectRatioFrameLayout
import app.kaizen.commons.KaizenSettings

/**
 * Botones de la fila de controles del reproductor. Todos van en una sola fila y su orden se elige en Ajustes. El de
 * reproducir/pausa no se puede ocultar; el resto sí (se guardan los ocultos, así que los nuevos salen por defecto).
 */
enum class PlayerButton(val label: String, val hideable: Boolean = true) {
    PREVIOUS("Capítulo anterior"),
    REWIND("Atrás"),
    PLAY("Reproducir / pausa", hideable = false),
    FORWARD("Adelante"),
    NEXT("Capítulo siguiente"),
    SKIP_OPENING("Saltar opening"),
    FAVORITE("Favorito"),
    SPEED("Velocidad"),
    TRACKS("Calidad, audio y subtítulos"),
    SERIES("Ficha del anime"),
    DEBUG("Depuración"),
    SCALE("Escala del vídeo");

    /** Texto que lleva el círculo cuando no tiene icono (velocidad). */
    val previewText: String? get() = when (this) {
        SPEED -> "1x"
        else -> null
    }

    val isVisible: Boolean get() = !hideable || name !in KaizenSettings.hiddenPlayerButtons

    companion object {
        /** Ancho (dp) del círculo de un botón (48 dp, como SmartTube): el de velocidad lleva texto («1x»). */
        fun widthDp(button: PlayerButton): Int = if (button == SPEED) TEXT_WIDTH_DP else ROUND_DP
        const val ROUND_DP = 48
        const val TEXT_WIDTH_DP = 64
        const val SPACING_DP = 12
        /** Margen al calcular cuántos caben: cada tamaño se redondea a píxeles por separado y, con densidades fraccionarias, la suma puede pasarse un par de px. */
        const val FIT_SLACK_DP = 2

        /**
         * Los botones de [buttons] que caben en [availableDp] de ancho, en su orden: los últimos que no caben se quitan
         * (no se pintan, así que tampoco se pueden enfocar). El de reproducir/pausa nunca se quita: si no entrase por
         * su puesto, ocupa el del último que cabe.
         */
        fun fitting(buttons: List<PlayerButton>, availableDp: Int): List<PlayerButton> {
            val kept = mutableListOf<PlayerButton>()
            var used = 0
            for (button in buttons) {
                val next = used + widthDp(button) + if (kept.isEmpty()) 0 else SPACING_DP
                if (next > availableDp) break
                kept += button
                used = next
            }
            if (PLAY in buttons && PLAY !in kept) {
                if (kept.isNotEmpty()) kept.removeAt(kept.lastIndex)
                kept += PLAY
            }
            return buttons.filter { it in kept }
        }

        /** Todos los botones en el orden elegido; los que no están guardados (nuevos) van en su sitio por defecto, al final. */
        fun ordered(): List<PlayerButton> {
            val saved = KaizenSettings.playerButtonOrder.split(',').mapNotNull { name -> entries.firstOrNull { it.name == name } }.distinct()
            return saved + entries.filter { it !in saved }
        }

        fun setOrder(order: List<PlayerButton>) {
            KaizenSettings.playerButtonOrder = order.joinToString(",") { it.name }
        }
    }
}

/**
 * Cómo se encaja el vídeo en la pantalla (botón «Escala del vídeo», como el de SmartTube: cada pulsación pasa al siguiente modo y avisa de su nombre);
 * [resizeMode] es el de `AspectRatioFrameLayout`.
 */
enum class VideoScale(val resizeMode: Int, val label: String) {
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT, "Ajustar (se ve entero)"),
    WIDTH(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, "Ajustar al ancho (recorta arriba y abajo si sobra)"),
    HEIGHT(AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT, "Ajustar al alto (recorta a los lados si sobra)"),
    ZOOM(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Rellenar (recorta los bordes)"),
    FILL(AspectRatioFrameLayout.RESIZE_MODE_FILL, "Estirar (deforma la imagen)");

    fun next(): VideoScale = entries[(ordinal + 1) % entries.size]
}
