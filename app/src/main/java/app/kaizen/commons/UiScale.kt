package app.kaizen.commons

import android.content.Context
import android.content.res.Configuration
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Kaizen: tamaño de toda la interfaz (Ajustes › Interfaz › Tamaño de la interfaz, ×1 por defecto). Escala la
 * densidad de la pantalla de cada actividad: todos los dp y sp (textos, iconos, tarjetas, controles del reproductor)
 * crecen o se reducen a la vez. Los cambios se aplican al recrear la actividad. Además, con «Ajustar a la pantalla» la
 * interfaz se diseña para una pantalla de [REFERENCE_DP] dp de ancho (la de las teles Android TV de 1080p y 4K) y se
 * adapta a las que informan otra densidad: el mismo aspecto en cualquier resolución.
 */
object UiScale {
    /** Ancho en dp para el que está pensada la interfaz (1920 px a 320 ppp, lo habitual en Android TV). */
    const val REFERENCE_DP = 960f
    private const val MIN_AUTO = 0.67f
    private const val MAX_AUTO = 2f

    /**
     * Multiplicador de densidad que deja el lado largo de la pantalla en [REFERENCE_DP] dp. Si ya está cerca (menos de un 5 %
     * de diferencia) no se toca: las teles de 1080p a 320 ppp, como el tvbox y la TV, se quedan igual.
     */
    internal fun autoFactor(longSidePx: Int, densityDpi: Int): Float {
        if (longSidePx <= 0 || densityDpi <= 0) return 1f
        val widthDp = longSidePx * 160f / densityDpi
        val factor = (widthDp / REFERENCE_DP).coerceIn(MIN_AUTO, MAX_AUTO)
        return if (kotlin.math.abs(factor - 1f) < 0.05f) 1f else factor
    }

    /** Contexto de [base] con la densidad multiplicada por el tamaño elegido; [base] tal cual si es ×1. */
    fun wrap(base: Context): Context {
        KaizenSettings.revertExpiredUiScale()
        val metrics = base.resources.displayMetrics
        val auto = if (KaizenSettings.autoFit) autoFactor(max(metrics.widthPixels, metrics.heightPixels), base.resources.configuration.densityDpi) else 1f
        val factor = KaizenSettings.effectiveUiScale * auto
        if (factor == 1f) return base
        val configuration = Configuration(base.resources.configuration)
        configuration.densityDpi = (configuration.densityDpi * factor).roundToInt()
        return base.createConfigurationContext(configuration)
    }
}
