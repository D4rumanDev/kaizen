package app.kaizen.tv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Zonas del borde (en dp) donde empieza el gesto que abre cada panel; la de la izquierda cubre la franja del menú cerrado. */
private val LEFT_EDGE = 96.dp
private val RIGHT_EDGE = 72.dp
/** Recorrido mínimo del dedo para contar como deslizamiento. */
private val MIN_SWIPE = 64.dp

/**
 * Control táctil de los dos paneles laterales (en la tele se abren con el foco del mando; en una tableta no hay foco):
 * un deslizamiento hacia la derecha desde el borde izquierdo abre el menú ([onOpenMenu]), uno hacia la izquierda desde el borde derecho abre
 * el panel de orden ([onOpenPanel]); en cualquier otro punto, hacia la izquierda cierra el menú ([onCloseMenu]) y hacia la derecha cierra el
 * panel ([onClosePanel]). Solo observa los toques (pasada inicial, sin consumirlos): las tarjetas, el desplazamiento y los botones siguen igual.
 */
internal fun Modifier.edgeSwipes(onOpenMenu: () -> Unit, onOpenPanel: () -> Unit, onCloseMenu: () -> Unit, onClosePanel: () -> Unit): Modifier = composed {
    val density = LocalDensity.current
    val leftEdge = with(density) { LEFT_EDGE.toPx() }
    val rightEdge = with(density) { RIGHT_EDGE.toPx() }
    val minSwipe = with(density) { MIN_SWIPE.toPx() }
    val openMenu by rememberUpdatedState(onOpenMenu)
    val openPanel by rememberUpdatedState(onOpenPanel)
    val closeMenu by rememberUpdatedState(onCloseMenu)
    val closePanel by rememberUpdatedState(onClosePanel)
    pointerInput(leftEdge, rightEdge, minSwipe) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                // Un gesto más horizontal que vertical y con recorrido suficiente; el desplazamiento vertical de las listas no cuenta.
                if (abs(dx) < minSwipe || abs(dx) < 2 * abs(dy)) continue
                when {
                    dx > 0 && down.position.x <= leftEdge -> openMenu()
                    dx < 0 && down.position.x >= size.width - rightEdge -> openPanel()
                    dx > 0 -> closePanel()
                    else -> closeMenu()
                }
                break
            }
        }
    }
}
