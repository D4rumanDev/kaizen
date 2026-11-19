package app.kaizen.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.Text
import app.kaizen.tv.LoadLimits
import app.kaizen.commons.appString
import app.kaizen.R
import app.kaizen.tv.ui.components.KaizenButton
import app.kaizen.tv.ui.theme.Text14
import app.kaizen.tv.ui.theme.Text17
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaizen.commons.runCatchingCoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

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
internal class LoadState<T>(private val timeout: Duration? = null, private val load: suspend () -> T) {
    private val _state = MutableStateFlow<Loadable<T>>(Loadable.Loading)
    val state: StateFlow<Loadable<T>> = _state.asStateFlow()

    suspend fun start() {
        // `withTimeout` lanzaría una cancelación, que `runCatchingCoop` relanza (no es un fallo): la pantalla se quedaría cargando para siempre. Con `withTimeoutOrNull` el plazo es un fallo normal.
        val result = runCatchingCoop {
            withContext(Dispatchers.IO) {
                if (timeout == null) Loadable.Ready(load())
                else withTimeoutOrNull(timeout) { Loadable.Ready(load()) } ?: Loadable.Failed(java.util.concurrent.TimeoutException("la carga pasó de $timeout"))
            }
        }
        _state.value = result.getOrElse { Loadable.Failed(it) }
    }
}

/** El [Loadable] de [load], que se lee una vez por [key] (con [timeout], pasado ese tiempo queda en [Loadable.Failed] y cada pantalla ya sabe enseñar el fallo y reintentar) (como `produceState`): al cambiar la clave o salir de la pantalla la lectura anterior se cancela. */
@Composable
internal fun <T> rememberLoadable(key: Any? = Unit, timeout: Duration? = null, load: suspend () -> T): Loadable<T> {
    val holder = remember(key) { LoadState(timeout, load) }
    LaunchedEffect(holder) { holder.start() }
    val state by holder.state.collectAsStateWithLifecycle()
    return state
}

/**
 * True cuando [loading] lleva más de [LoadLimits.DATA] seguido (se reinicia al cambiar [key] o al terminar la carga). Es el aviso común de «tarda demasiado» de las cargas que no se
 * pueden cortar desde fuera, como la de una lista paginada.
 */
@Composable
internal fun rememberStalled(loading: Boolean, key: Any?): Boolean {
    var stalled by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, loading) {
        stalled = false
        if (loading) {
            delay(LoadLimits.DATA)
            stalled = true
        }
    }
    return stalled
}

/** El aviso común de una carga que falló o se atascó: el mensaje y «Reintentar». Lo usan todas las pantallas de datos de red, para no inventar uno distinto en cada una. */
@Composable
internal fun LoadProblem(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, color = Color.White, fontSize = Text17)
        KaizenButton(appString(R.string.ui_reintentar), onClick = onRetry, fontSize = Text14)
    }
}
