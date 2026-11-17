package app.kaizen.commons

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager

/**
 * Calidad de las portadas: **automática** (según el equipo y la red) o fija. Decide cuánto hay que bajar y guardar:
 *  - [HIGH]: la mejor portada de entre las de las fuentes de la serie (mide varias, lo que baja imágenes enteras), píxeles completos.
 *  - [BALANCED]: la de la propia fuente, ancho suficiente al 75 %; no compara con otras fuentes (red medida o equipo justo).
 *  - [LIGHT]: la de la propia fuente, al 50 % y con 16 bits de color (menos datos y memoria: equipos con poca RAM).
 * La automática elige [LIGHT] con poca memoria, [BALANCED] con red de datos medida y [HIGH] en el resto.
 */
object ImageQuality {
    enum class Level(val widthScale: Float, val comparesCovers: Boolean, val config: Bitmap.Config) {
        HIGH(1f, true, Bitmap.Config.ARGB_8888),
        BALANCED(0.75f, false, Bitmap.Config.ARGB_8888),
        LIGHT(0.5f, false, Bitmap.Config.RGB_565)
    }

    /** El nivel de ahora (la automática lo calcula cada vez: la red puede cambiar). */
    fun level(context: Context): Level = when (runCatching { KaizenSettings.coverQuality }.getOrDefault(0)) {
        1 -> Level.HIGH
        2 -> Level.BALANCED
        3 -> Level.LIGHT
        else -> automatic(context)
    }

    internal fun automatic(context: Context): Level = pick(lowMemory(context), metered(context))

    /** La decisión de la automática: poca memoria manda, después la red medida. */
    internal fun pick(lowMemory: Boolean, metered: Boolean): Level = when {
        lowMemory -> Level.LIGHT
        metered -> Level.BALANCED
        else -> Level.HIGH
    }

    private fun lowMemory(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java)
        manager.isLowRamDevice || manager.memoryClass < LOW_MEMORY_CLASS_MB
    }.getOrDefault(false)

    private fun metered(context: Context): Boolean = runCatching { context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(false)

    /** Por debajo de estos MB de montón por aplicación se considera un equipo justo. */
    private const val LOW_MEMORY_CLASS_MB = 128
}
