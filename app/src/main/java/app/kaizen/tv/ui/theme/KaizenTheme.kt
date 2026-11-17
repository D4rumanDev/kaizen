package app.kaizen.tv.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.kaizen.tv.ui.KaizenToastHost

/** Tema de la app: esquema oscuro con el [Accent] y el host de avisos flotantes sobre cualquier pantalla. */
@Composable
fun KaizenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
        Box(Modifier.fillMaxSize()) {
            content()
            KaizenToastHost(Modifier.align(Alignment.BottomCenter).padding(bottom = ToastBottomPadding))
        }
    }
}
