package app.kaizen.tv.settings

import app.kaizen.R
import app.kaizen.commons.appString
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.tv.ui.TitleField
import app.kaizen.tv.ui.rememberAutoFocus
import app.kaizen.tv.ui.theme.*

/**
 * Un ajuste de texto libre (una URL, una clave, un usuario): al pulsarlo se abre [TextEditDialog] con el teclado de la tele. [validate] dice por qué no vale (null si vale) y solo
 * lo que vale se guarda con [set]; un texto vacío es válido si [validate] lo acepta (suele querer decir «el de siempre»). [secret]: el valor no se enseña en el resumen.
 */
class TextItem(
    title: String,
    val summary: () -> String,
    val get: () -> String,
    val set: (String) -> Unit,
    val validate: (String) -> String? = { null },
    val hint: String = "",
    val help: String = "",
    /** Lo que se teclea se enseña con puntos (una clave). */
    val masked: Boolean = false
) : SettingItem(title)

/** El cuadro para escribir el texto de un [TextItem]; Guardar comprueba antes de aceptar. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TextEditDialog(item: TextItem, onClose: () -> Unit) {
    var text by remember(item.title) { mutableStateOf(item.get()) }
    var error by remember(item.title) { mutableStateOf<String?>(null) }
    val focus = rememberAutoFocus()
    // Una clave se puede ver mientras se teclea (con el mando es fácil equivocarse).
    var reveal by remember(item.title) { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.7f).clip(ShapeLg).background(PanelColor).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item.title, color = androidx.compose.ui.graphics.Color.White, fontSize = Text20)
            if (item.help.isNotEmpty()) Text(item.help, color = White70, fontSize = Text14)
            TitleField(text, { text = it; error = null }, focus, item.hint, item.masked && !reveal)
            error?.let { Text(it, color = FailureRed, fontSize = Text14) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val problem = item.validate(text.trim())
                    if (problem == null) { item.set(text.trim()); onClose() } else error = problem
                }) { Text(appString(R.string.ui_guardar_texto)) }
                if (item.masked) Button(onClick = { reveal = !reveal }) { Text(if (reveal) "Ocultar" else "Mostrar") }
                Button(onClick = onClose) { Text(appString(R.string.ui_cancelar_texto)) }
            }
        }
    }
}
