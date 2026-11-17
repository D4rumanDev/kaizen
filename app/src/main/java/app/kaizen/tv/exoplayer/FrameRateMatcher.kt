package app.kaizen.tv.exoplayer

import android.app.Activity
import android.os.Build
import android.util.Log
import app.kaizen.BuildConfig
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Kaizen: ajuste automático de la frecuencia de pantalla (como el AFR de SmartTube).
 * Elige, con la misma resolución que el modo actual, la frecuencia que sea múltiplo exacto
 * de los fps del vídeo (24 → 23,976/24 Hz, 25 → 50 Hz, 30 → 60 Hz) y la pide a la ventana.
 * La preferencia es de la ventana: al cerrar el reproductor el sistema vuelve a su modo.
 */
object FrameRateMatcher {
    private const val TAG = "KaizenAFR"
    private const val MULTIPLE_TOLERANCE = 0.01f
    private const val ERROR_BUCKETS = 2000

    /** Devuelve el id del modo pedido si hay que cambiarlo (la pantalla parpadeará unos segundos). */
    fun apply(activity: Activity, fps: Float): Int? {
        if (fps <= 0f) return null
        val display = display(activity) ?: return null
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { multipleError(it.refreshRate, fps) < MULTIPLE_TOLERANCE }
            // Primero el múltiplo más exacto (23,976 Hz antes que 24 Hz para 23,976 fps) y,
            // a igualdad, la frecuencia más alta (50 Hz antes que 25 Hz).
            .sortedWith(compareBy<Display.Mode> { (multipleError(it.refreshRate, fps) * ERROR_BUCKETS).toInt() }
                .thenByDescending { it.refreshRate })
            .firstOrNull()
        if (BuildConfig.DEBUG) Log.d(TAG, "vídeo $fps fps; actual ${current.refreshRate} Hz; elegido ${best?.refreshRate}")
        if (best == null || best.modeId == current.modeId) return null
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = best.modeId }
        return best.modeId
    }

    /** Deja que el sistema elija el modo (al desactivar la frecuencia automática). */
    fun reset(activity: Activity) {
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
    }

    /** Modo activo de la pantalla de la actividad. */
    fun currentModeId(activity: Activity): Int? = display(activity)?.mode?.modeId

    /** Error relativo de [refreshRate] como múltiplo entero de [fps] (infinito si es menor). */
    private fun multipleError(refreshRate: Float, fps: Float): Float {
        val ratio = refreshRate / fps
        val rounded = ratio.roundToInt()
        return if (rounded < 1) Float.MAX_VALUE else abs(ratio - rounded) / rounded
    }

    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else activity.windowManager.defaultDisplay
}
