package app.kaizen.tv.ui

import app.kaizen.tv.ui.components.*

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.ExperimentalTvMaterial3Api
import app.kaizen.commons.KaizenSettings
import kotlinx.coroutines.delay
import app.kaizen.R
import app.kaizen.commons.appString
import kotlin.time.Duration.Companion.seconds

/**
 * Tras cambiar el tamaño de la interfaz, pregunta si se mantiene. Sin respuesta en [KaizenSettings.UI_SCALE_CONFIRM],
 * con Atrás o con «Volver», se restaura el tamaño anterior (también si la app se cierra antes: ver UiScale.wrap).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun UiScaleConfirm(activity: ComponentActivity) {
    var pending by remember { mutableStateOf(KaizenSettings.uiScaleConfirmBy > System.currentTimeMillis()) }
    if (!pending) return

    fun secondsLeft() = ((KaizenSettings.uiScaleConfirmBy - System.currentTimeMillis() + 999) / 1000).toInt().coerceAtLeast(0)
    var seconds by remember { mutableIntStateOf(secondsLeft()) }

    fun revert() {
        pending = false
        KaizenSettings.revertUiScale()
        activity.recreate()
    }

    LaunchedEffect(Unit) {
        while (seconds > 0) {
            delay(1.seconds)
            seconds = secondsLeft()
        }
        revert()
    }

    KaizenDialog(
        onDismissRequest = ::revert,
        title = appString(R.string.ui_mantener_este_tamano_de_la_interfaz),
        subtitle = "Si no respondes, vuelve al anterior en $seconds s.",
        width = DialogWidthWide
    ) {
        val revertFocus = rememberAutoFocus()
        // El foco empieza en «Volver»: pulsar OK sin ver la pantalla deshace el cambio.
        KaizenButton(appString(R.string.ui_volver_al_anterior), onClick = ::revert, modifier = Modifier.fillMaxWidth().focusRequester(revertFocus))
        KaizenButton(appString(R.string.ui_mantener_este_tamano), onClick = {
            pending = false
            KaizenSettings.confirmUiScale()
        }, modifier = Modifier.fillMaxWidth())
    }
}
