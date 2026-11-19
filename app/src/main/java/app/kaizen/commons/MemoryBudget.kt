package app.kaizen.commons

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import app.kaizen.App
import coil3.SingletonImageLoader

/**
 * **El único punto de la gestión de memoria de la app.** Decide a qué grupo pertenece el equipo ([Tier]: justo de RAM o normal) y de ahí salen todos los límites y las reacciones, para que ninguna
 * pantalla ni ajuste invente su propio criterio:
 *  - **Límites por equipo:** caché de imágenes ([imageCachePercent], [IMAGE_CACHE_BACKGROUND_PERCENT]), tope del búfer de vídeo ([playerBufferBytes]) y nivel mínimo de calidad de portadas
 *    ([isTight], que lee `ImageQuality`).
 *  - **Reacción a los avisos del sistema** ([onTrim], [actionFor]): al ocultarse la interfaz se vacía la caché de imágenes (se rehace desde el disco al volver), como recomienda Android. El resto
 *    (vaciar con nivel 40 o más, recortar a la mitad con el sistema justo) ya lo hace Coil por su cuenta y no se repite.
 * Lo que sigue fuera, a propósito: lo que elige el usuario ([KaizenSettings.imageMemoryPercent] manda sobre el valor de fábrica), la clasificación de los avisos para el registro
 * (`AppErrors.isTrimPressure`) y «Vaciar caché» de Configuración (`CacheCleaner`), que son otra cosa.
 */
object MemoryBudget {
    enum class Tier { TIGHT, NORMAL }

    /** Con menos RAM que esto el equipo va justo: la TV de 32 bits tiene 2 GB; el tvbox, 3. */
    internal const val TIGHT_RAM_BYTES = 2_500_000_000L

    /** El grupo de un equipo con [totalRamBytes] de RAM. */
    internal fun tierOf(totalRamBytes: Long): Tier = if (totalRamBytes < TIGHT_RAM_BYTES) Tier.TIGHT else Tier.NORMAL

    /** La RAM total del equipo, o null si no se puede leer (por ejemplo, antes de que la app tenga contexto). */
    private fun totalRamBytes(): Long? = runCatching {
        val info = ActivityManager.MemoryInfo()
        (App.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        info.totalMem
    }.getOrNull()

    @Volatile private var readTier: Tier? = null

    /**
     * El grupo de este equipo. La RAM total no cambia mientras corre la app, así que una lectura buena se guarda; una lectura fallida **no** se guarda (se supone un equipo normal esta vez y se
     * vuelve a intentar): si no, un fallo al arrancar dejaría la protección de memoria apagada para siempre.
     */
    val tier: Tier get() = readTier ?: totalRamBytes()?.let { tierOf(it).also { found -> readTier = found } } ?: Tier.NORMAL

    /** Si este equipo va justo de RAM. */
    val isTight: Boolean get() = tier == Tier.TIGHT

    // ---------------------------------------------------------------------------------------------- límites por equipo

    /** Parte de la memoria de la app para la caché de imágenes de fábrica: 10 % en un equipo justo (ahorra unos 19 MB de los 192 que da Android) y 20 % en el resto. Es una opción del ajuste. */
    fun imageCachePercent(tier: Tier = this.tier): Int = if (tier == Tier.TIGHT) 10 else 20

    /** Con la app en segundo plano la caché de imágenes se recorta a esta parte de su tamaño (Coil 3.3+; lo que sigue en uso no se toca). */
    const val IMAGE_CACHE_BACKGROUND_PERCENT = 0.25

    /** Sin tope propio (ExoPlayer calcula el suyo por las pistas, que puede llegar a unos 125 MB). */
    const val NO_CAP = -1

    /** Tope del búfer de vídeo en un equipo justo: el búfer de ExoPlayer vive en el heap de Java (192 MB en la TV) y sin tope un vídeo de bitrate alto puede agotarlo. */
    internal const val TIGHT_PLAYER_BUFFER_BYTES = 48 * 1024 * 1024

    /** Bytes máximos de búfer de vídeo, o [NO_CAP] si el equipo es holgado. */
    fun playerBufferBytes(tier: Tier = this.tier): Int = if (tier == Tier.TIGHT) TIGHT_PLAYER_BUFFER_BYTES else NO_CAP

    // ---------------------------------------------------------------------------------------------- reacción a los avisos

    enum class Action { NONE, CLEAR }

    /**
     * La acción para un nivel de aviso de memoria. **Coil 3.6.3 ya reacciona por su cuenta** (comprobado en su bytecode, `AndroidSystemCallbacks`): con nivel 40 o más vacía la caché de
     * imágenes y entre 10 y 19 la recorta a la mitad; con 20 (interfaz oculta) solo se prepara para el recorte en segundo plano ([IMAGE_CACHE_BACKGROUND_PERCENT]). Aquí se añade lo único
     * que Android recomienda y Coil no hace: con la interfaz oculta (20 a 39) vaciarla del todo, porque las portadas se releen del disco al volver. No se repite lo que Coil ya hace.
     */
    internal fun actionFor(level: Int): Action =
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) Action.CLEAR else Action.NONE

    /** Aplica [actionFor] a la caché de imágenes de memoria de Coil. */
    fun onTrim(context: Context, level: Int) {
        if (actionFor(level) == Action.NONE) return
        SingletonImageLoader.get(context).memoryCache?.clear()
    }
}
