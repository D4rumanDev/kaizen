package app.kaizen.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaizen.commons.runCatchingCoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Lo que enseña una pantalla que lee algo una vez: cargando, error o el dato. Un solo valor, sin `null` que signifique «cargando». */
internal sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Failed(val error: Throwable) : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

/** El dato si ya está; null mientras carga o si falló. */
internal val <T> Loadable<T>.valueOrNull: T? get() = (this as? Loadable.Ready<T>)?.value

/**
 * Lee [load] una vez, fuera del hilo principal, y publica el resultado como [StateFlow]. Una cancelación no es un fallo (se relanza); cualquier otra
 * excepción queda en [Loadable.Failed] en vez de tirar la pantalla. La lectura la arranca y la cancela quien llama a [start] (la composición).
 */
internal class LoadState<T>(private val load: suspend () -> T) {
    private val _state = MutableStateFlow<Loadable<T>>(Loadable.Loading)
    val state: StateFlow<Loadable<T>> = _state.asStateFlow()

    suspend fun start() {
        _state.value = runCatchingCoop { withContext(Dispatchers.IO) { load() } }.fold({ Loadable.Ready(it) }, { Loadable.Failed(it) })
    }
}

/** El [Loadable] de [load], que se lee una vez por [key] (como `produceState`): al cambiar la clave o salir de la pantalla la lectura anterior se cancela. */
@Composable
internal fun <T> rememberLoadable(key: Any? = Unit, load: suspend () -> T): Loadable<T> {
    val holder = remember(key) { LoadState(load) }
    LaunchedEffect(holder) { holder.start() }
    val state by holder.state.collectAsStateWithLifecycle()
    return state
}
