package app.kaizen.commons

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Ámbito para trabajo que debe seguir aunque se cierre la pantalla que lo pidió (catálogo, recientes, purgas).
 * A diferencia de GlobalScope tiene dueño y un fallo en una tarea no cancela a las demás.
 */
object AppScope : CoroutineScope {
    // Un fallo no capturado en segundo plano se registra en vez de cerrar la app.
    override val coroutineContext = SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> logError(e) }
}

/**
 * Como runCatching, pero deja pasar la cancelación: si la corrutina se cancela mientras espera una petición, no se
 * convierte en un fallo «normal» y el trabajo se detiene (con runCatching seguiría como si nada).
 */
inline fun <T> runCatchingCoop(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/**
 * Hilo único de prioridad de fondo para el trabajo pesado de CPU que no hace falta para pintar (reagrupar el catálogo y, en otro hilo igual, emparejar con AniList): son decenas de
 * segundos de CPU y memoria (el GC no paraba) y, a prioridad normal, «Últimos vistos» tardaba en salir tras abrir la app. Con prioridad de fondo solo usa lo que sobra.
 */
val BackgroundCpu: kotlinx.coroutines.CoroutineDispatcher = backgroundThread("kaizen-background")

/** Otro hilo de fondo aparte para el emparejado con AniList: así reagrupar tras unir o separar una serie no espera a que acabe una pasada. */
val BackgroundAnilist: kotlinx.coroutines.CoroutineDispatcher = backgroundThread("kaizen-anilist")

private fun backgroundThread(name: String) = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
    Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() }, name)
}.asCoroutineDispatcher()
