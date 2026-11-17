package app.kaizen.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Una sola ejecución a la vez por clave: si varias pantallas piden lo mismo a la vez (el arranque), solo la primera lo hace y las
 * demás esperan su resultado, o su fallo. Cuando termina, la siguiente petición vuelve a ejecutar el bloque.
 */
internal class SingleFlight<T> {
    private val running = ConcurrentHashMap<String, CompletableDeferred<T>>()

    suspend fun run(key: String, block: suspend () -> T): T {
        while (true) {
            val mine = CompletableDeferred<T>()
            val other = running.putIfAbsent(key, mine)
            if (other == null) {
                try {
                    // La clave se libera antes de avisar: quien espera y reintenta no vuelve a encontrar este mismo aviso.
                    return block().also { running.remove(key, mine); mine.complete(it) }
                } catch (e: Throwable) {
                    running.remove(key, mine)
                    mine.completeExceptionally(e)
                    throw e
                } finally {
                    running.remove(key, mine)
                }
            }
            try {
                return other.await()
            } catch (e: CancellationException) {
                // Si me cancelaron a mí, salgo; si se canceló quien ejecutaba, lo intento yo.
                currentCoroutineContext().ensureActive()
            }
        }
    }
}
