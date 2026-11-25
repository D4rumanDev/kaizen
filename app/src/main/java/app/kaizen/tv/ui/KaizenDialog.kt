package app.kaizen.tv.ui

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text

/** Ancho estándar para diálogos modales en Kaizen (440 dp). */
internal val DialogWidthDefault: Dp = 440.dp

/** Ancho más amplio para confirmaciones o diálogos con texto descriptivo (480 dp). */
internal val DialogWidthWide: Dp = 480.dp

/**
 * Diálogo modal unificado de Kaizen para Android TV:
 * - Contenedor con [PanelColor] y bordes redondeados [ShapeXl].
 * - Dimensiones consistentes ([DialogWidthDefault] o [DialogWidthWide]).
 * - Protección contra repetición de pulsación larga ([ignoreHeldSelect]) para no activar la primera opción por accidente.
 * - Título y subtítulo estandarizados con tipografías y contrastes de la app.
 */
@Composable
internal fun KaizenDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    width: Dp = DialogWidthDefault,
    scrollable: Boolean = false,
    titleMaxLines: Int = 2,
    properties: DialogProperties = DialogProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        val scrollModifier = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            modifier = modifier
                .width(width)
                .ignoreHeldSelect()
                .background(PanelColor, ShapeXl)
                .padding(24.dp)
                .then(scrollModifier),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = Text18,
                    fontWeight = FontWeight.Bold,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = if (subtitle.isNullOrBlank()) 4.dp else 2.dp)
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = White70,
                    fontSize = Text14,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            content()
        }
    }
}
