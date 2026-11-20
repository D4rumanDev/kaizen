package app.kaizen.tv.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaizen.R
import app.kaizen.commons.UpdateChecker
import app.kaizen.commons.UpdateFlow
import app.kaizen.commons.UpdateInstall
import app.kaizen.commons.appString
import app.kaizen.tv.ui.components.KaizenButton
import app.kaizen.tv.ui.theme.Accent
import app.kaizen.tv.ui.theme.Text14

/** La etiqueta de Inicio, junto a la hora: se enfoca con el mando y abre el diálogo de actualizar. */
@Composable
internal fun UpdateBadge(release: UpdateChecker.Release, onClick: () -> Unit) {
    KaizenButton("${appString(R.string.ui_update_badge)} ${release.version}", onClick = onClick, container = Accent, fontSize = Text14)
}

/**
 * Descargar e instalar la versión nueva: un solo diálogo que sigue el estado de [UpdateFlow]. Con una descarga en marcha, «Atrás» la cancela. Nada se instala sin que el
 * usuario confirme en la pantalla del sistema.
 */
@Composable
internal fun UpdateDialog(release: UpdateChecker.Release, onDismiss: () -> Unit) {
    val ui by UpdateFlow.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val busy = ui is UpdateFlow.Ui.Preparing || ui is UpdateFlow.Ui.Downloading || ui is UpdateFlow.Ui.Verifying
    val close = { UpdateFlow.reset(); onDismiss() }
    KaizenDialog(
        onDismissRequest = { if (busy) UpdateFlow.cancel() else close() },
        title = "Kaizen ${release.version}", width = DialogWidthWide,
        properties = DialogProperties(dismissOnClickOutside = false)
    ) {
        when (val state = ui) {
            UpdateFlow.Ui.Idle -> {
                val first = rememberAutoFocus()
                Message(appString(R.string.ui_update_body))
                KaizenButton(appString(R.string.ui_update_install), onClick = { UpdateFlow.start(context) }, modifier = Modifier.fillMaxWidth().focusRequester(first))
                KaizenButton(appString(R.string.ui_update_later), onClick = close, modifier = Modifier.fillMaxWidth())
                KaizenButton(appString(R.string.ui_update_skip), onClick = { UpdateChecker.skip(release.tag); close() }, modifier = Modifier.fillMaxWidth())
            }
            UpdateFlow.Ui.Preparing -> Progress(appString(R.string.ui_update_preparing))
            is UpdateFlow.Ui.Downloading -> {
                val percent = if (state.total > 0) (state.done * 100 / state.total).toInt() else 0
                Progress("${appString(R.string.ui_update_downloading)} ${state.done / MB} / ${state.total / MB} MB ($percent %)")
            }
            UpdateFlow.Ui.Verifying -> Progress(appString(R.string.ui_update_verifying))
            is UpdateFlow.Ui.NeedsPermission -> {
                val first = rememberAutoFocus()
                Message(appString(if (state.canOpenSettings) R.string.ui_update_permission else R.string.ui_update_no_settings))
                if (state.canOpenSettings) {
                    KaizenButton(appString(R.string.ui_update_open_settings), onClick = { if (!UpdateInstall.openPermissionSettings(context)) UpdateFlow.noSettings() },
                        modifier = Modifier.fillMaxWidth().focusRequester(first))
                    KaizenButton(appString(R.string.ui_update_retry), onClick = { UpdateFlow.start(context) }, modifier = Modifier.fillMaxWidth())
                    KaizenButton(appString(R.string.ui_update_close), onClick = close, modifier = Modifier.fillMaxWidth())
                } else {
                    KaizenButton(appString(R.string.ui_update_close), onClick = close, modifier = Modifier.fillMaxWidth().focusRequester(first))
                }
            }
            UpdateFlow.Ui.Installing -> {
                val first = rememberAutoFocus()
                Message(appString(R.string.ui_update_confirm))
                KaizenButton(appString(R.string.ui_update_close), onClick = close, modifier = Modifier.fillMaxWidth().focusRequester(first))
            }
            is UpdateFlow.Ui.Failed -> {
                val first = rememberAutoFocus()
                Message(state.message)
                KaizenButton(appString(R.string.ui_update_retry), onClick = { UpdateFlow.start(context) }, modifier = Modifier.fillMaxWidth().focusRequester(first))
                KaizenButton(appString(R.string.ui_update_close), onClick = close, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private const val MB = 1024L * 1024

@Composable
private fun ColumnScope.Message(text: String) {
    Text(text, color = Color.White, fontSize = Text14, modifier = Modifier.padding(bottom = 8.dp))
}

/** Un paso en marcha: el texto y «Cancelar», con el foco en el botón (Atrás también cancela). */
@Composable
private fun ColumnScope.Progress(text: String) {
    val first = rememberAutoFocus()
    Message(text)
    KaizenButton(appString(R.string.ui_cancelar), onClick = { UpdateFlow.cancel() }, modifier = Modifier.fillMaxWidth().focusRequester(first))
}
