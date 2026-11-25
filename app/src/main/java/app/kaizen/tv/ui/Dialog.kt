package app.kaizen.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.kaizen.tv.ScreenDimmer

/**
 * El `Dialog` de Compose de la app: igual que el original, pero sus gestos cuentan para el protector de pantalla ([ScreenDimmer]). Un diálogo es una ventana aparte y sus
 * teclas no pasan por la actividad; sin esto, usar un menú o los ajustes tanto rato como el tiempo del protector lo oscurecería a media pulsación.
 */
@Composable
internal fun Dialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let(ScreenDimmer::watch)
            onDispose { }
        }
        content()
    }
}
