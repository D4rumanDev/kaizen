@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui.components

import app.kaizen.tv.ui.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import app.kaizen.tv.ui.*
import app.kaizen.tv.ui.theme.*

/** La barra de lo visto: ancho completo = duración completa. */
@Composable
internal fun CardProgressBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).background(ProgressTrack)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
    }
}
