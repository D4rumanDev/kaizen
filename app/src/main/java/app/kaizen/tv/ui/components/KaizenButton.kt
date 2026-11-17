@file:OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui.components

import app.kaizen.tv.ui.theme.*
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import app.kaizen.tv.ui.*
import app.kaizen.tv.ui.theme.*

/** Botón de los diálogos y barras: translúcido, y blanco con el foco. */
@Composable
internal fun KaizenButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = SurfaceMuted,
    fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
    /** Deshabilitado: en grises, como una fuente deshabilitada, y sin foco. */
    enabled: Boolean = true
) {
    androidx.tv.material3.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        // El mismo redondeo que el borde del foco (y que el resto de la interfaz): antes el botón y el selector tenían esquinas distintas.
        shape = androidx.tv.material3.ButtonDefaults.shape(ShapePill),
        colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = container, contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
            disabledContainerColor = container.copy(alpha = container.alpha / 2), disabledContentColor = Color.Gray),
        border = androidx.tv.material3.ButtonDefaults.border(
            focusedBorder = androidx.tv.material3.Border(BorderStroke(2.dp, Color.White), shape = ShapePill))
    ) { Text(text, fontSize = fontSize, style = CenteredTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
