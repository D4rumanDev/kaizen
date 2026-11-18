package app.kaizen.tv.ui

import app.kaizen.tv.ui.theme.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.kaizen.commons.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xdroid.toaster.Toaster
import kotlin.time.Duration.Companion.milliseconds

/**
 * Notificaciones flotantes HUD (Toast) tipo pastilla unificadas para Android TV:
 * - Se muestran centradas abajo con esquinas redondeadas [ShapePill], sin borde.
 * - Animación de entrada y salida suave ([Motion.spec]).
 * - Reemplaza los Toast grises nativos de Android por un diseño integrado en la app.
 */
object KaizenToast {
    /** [at] es la hora (reloj monótono) en que se mostró: un aviso más viejo que su duración es un resto, no algo que enseñar. */
    data class Entry(val id: Long, val text: String, val longDuration: Boolean, val at: Long = android.os.SystemClock.elapsedRealtime()) {
        val durationMs: Long get() = if (longDuration) 3_500L else 2_200L
        fun isStale(now: Long): Boolean = now - at > durationMs + STALE_MARGIN.inWholeMilliseconds
    }

    private val STALE_MARGIN = 500.milliseconds

    private val _current = MutableStateFlow<Entry?>(null)
    val current = _current.asStateFlow()

    private var dismissJob: Job? = null

    /** Si hay una pantalla Compose activa escuchando. Si no hay ninguna, recurre al Toast del sistema. */
    @Volatile var activeHosts: Int = 0

    private val nextId = java.util.concurrent.atomic.AtomicLong()

    /**
     * Se llama desde cualquier hilo. Todo el relevo (aviso nuevo, cancelar el temporizador anterior, programar el suyo) va bajo un cerrojo:
     * antes, dos avisos a la vez dejaban el temporizador del otro y el último aviso nunca se ocultaba. El id es un contador, no la hora
     * (dos avisos en el mismo milisegundo compartían id).
     */
    fun show(text: String, longDuration: Boolean = false) {
        if (text.isBlank()) return
        if (activeHosts <= 0) {
            if (longDuration) Toaster.toastLong(text) else Toaster.toast(text)
            return
        }
        synchronized(this) {
            val entry = Entry(nextId.incrementAndGet(), text, longDuration)
            _current.value = entry
            dismissJob?.cancel()
            dismissJob = AppScope.launch(Dispatchers.Main) {
                delay(entry.durationMs)
                synchronized(this@KaizenToast) {
                    if (_current.value?.id == entry.id) _current.value = null
                }
            }
        }
    }

    /**
     * Quita el aviso que haya. Al salir de la app ([AppExit]) se cancelan los temporizadores y el aviso «Pulsa Atrás otra vez para salir» se
     * quedaba guardado: el proceso sigue vivo y, al volver a abrir Kaizen, la pastilla reaparecía sola y no se iba.
     */
    fun clear() = synchronized(this) {
        dismissJob?.cancel()
        _current.value = null
    }

    /** Quita el aviso si es un resto viejo (la pantalla acaba de aparecer y ya había uno que nadie quitó). */
    fun dropStale() = synchronized(this) {
        if (_current.value?.isStale(android.os.SystemClock.elapsedRealtime()) == true) _current.value = null
    }

    fun toast(text: String) = show(text, longDuration = false)
    fun toastLong(text: String) = show(text, longDuration = true)
}

/**
 * Contenedor flotante que dibuja la pastilla animada sobre cualquier pantalla de Kaizen.
 */
@Composable
internal fun KaizenToastHost(modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        KaizenToast.dropStale()
        KaizenToast.activeHosts += 1
        onDispose {
            KaizenToast.activeHosts = (KaizenToast.activeHosts - 1).coerceAtLeast(0)
        }
    }
    val entry by KaizenToast.current.collectAsStateWithLifecycle()
    // Red de seguridad: si el temporizador del aviso se perdió (se cancelaron las tareas de la app), la propia pantalla lo quita a su hora.
    LaunchedEffect(entry?.id) {
        val item = entry ?: return@LaunchedEffect
        delay(item.durationMs + 300L)
        synchronized(KaizenToast) { if (KaizenToast.current.value?.id == item.id) KaizenToast.clear() }
    }
    AnimatedVisibility(
        visible = entry != null,
        modifier = modifier,
        enter = fadeIn(Motion.spec(180)) + slideInVertically(Motion.spec(220)) { it / 2 },
        exit = fadeOut(Motion.spec(180)) + slideOutVertically(Motion.spec(220)) { it / 2 }
    ) {
        entry?.let { item ->
            Box(
                Modifier
                    .widthIn(max = 680.dp)
                    .clip(ShapePill)
                    .background(ToastBackground)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = item.text,
                    color = Color.White,
                    fontSize = Text15,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
