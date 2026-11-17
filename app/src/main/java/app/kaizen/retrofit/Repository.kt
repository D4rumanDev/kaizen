package app.kaizen.retrofit

import app.kaizen.commons.Network
import app.kaizen.database.CacheDB
import app.kaizen.source.AnimeSources
import kotlinx.coroutines.launch
import app.kaizen.commons.AppScope
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** Estado de la carga de recientes de las fuentes activas para la pantalla principal de TV (ver AnimeSources). */
object Repository {
    /** `false` si la última recarga de recientes falló (sin red o todas las fuentes). Mientras recarga, `true`. */
    val recentsLoaded = MutableStateFlow(true)
    private val reloading = AtomicBoolean(false)
    private val forcePending = AtomicBoolean(false)

    /** `true` mientras una recarga de recientes está en curso (la pantalla principal muestra «Cargando…»). */
    val recentsLoading = MutableStateFlow(false)

    /** Recarga los recientes. Con [force] (el botón «Actualizar») se piden a las fuentes aunque la copia sea reciente. */
    fun reloadRecents(force: Boolean = false) {
        if (!Network.isConnected) {
            recentsLoaded.value = false
            return
        }
        // Una recarga a la vez (la tarjeta de reintentar se puede pulsar varias veces).
        // Si pide «Actualizar» mientras otra recarga está en curso, se repite al terminar: esa pudo leer una copia reciente.
        if (!reloading.compareAndSet(false, true)) {
            if (force) forcePending.set(true)
            return
        }
        recentsLoaded.value = true
        recentsLoading.value = true
        AppScope.launch {
            try {
                val result = runCatchingCoop { AnimeSources.recentsAll(force) }.onFailure(::logError)
                result.getOrNull()?.takeIf { it.isNotEmpty() }?.let { CacheDB.INSTANCE.recentEpisodeDAO().setCache(it) }
                recentsLoaded.value = result.isSuccess
            } finally {
                recentsLoading.value = false
                reloading.set(false)
                if (forcePending.getAndSet(false)) reloadRecents(force = true)
            }
        }
    }
}
