package app.kaizen.videoservers

import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolución de servidores por salud: aparta de la cabeza de una lista los candidatos que no funcionan hasta dar con
 * uno que sí. Independiente de la pantalla; quien la usa decide cómo avisar.
 */
object ServerResolver {
    /**
     * Comprueba el primero de [candidates] y, si no funciona o tarda más de [timeoutMs], lo quita y pasa al siguiente.
     * Un candidato que no se puede comprobar ([canCheck] falso, p. ej. los que abren el navegador) se da por bueno. [onTrying] avisa de
     * cada candidato justo antes de comprobarlo.
     * Devuelve false si se agotó la lista.
     */
    suspend fun <T> dropBrokenLeading(
        candidates: MutableList<T>,
        timeoutMs: Long,
        canCheck: (T) -> Boolean = { true },
        onDropped: (T) -> Unit = {},
        onTrying: (T) -> Unit = {},
        works: suspend (T) -> Boolean
    ): Boolean {
        while (candidates.isNotEmpty()) {
            val first = candidates.first()
            if (!canCheck(first)) return true
            onTrying(first)
            if (withTimeoutOrNull(timeoutMs) { works(first) } == true) return true
            candidates.removeAt(0)
            onDropped(first)
        }
        return false
    }

    /** [dropBrokenLeading] para servidores: funciona si el extractor da al menos una opción de vídeo verificada. */
    suspend fun dropBrokenLeading(servers: MutableList<Server>, timeoutMs: Long, onTrying: (Server) -> Unit = {}, onDropped: (Server) -> Unit = {}): Boolean =
        dropBrokenLeading(servers, timeoutMs, canCheck = { it !is WebServer }, onDropped = onDropped, onTrying = onTrying) {
            it.verified()?.options?.isNotEmpty() == true
        }
}
