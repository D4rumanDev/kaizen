@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kaizen.tv.ui.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.source.Catalog
import app.kaizen.source.SourceCatalog
import app.kaizen.source.UserTitles
import app.kaizen.tv.ui.CardMenu
import app.kaizen.tv.ui.theme.Background
import app.kaizen.tv.ui.theme.Text20

/** Las pantallas completas de la Biblioteca: las que necesitan toda la altura (decidir una duda, recorrer listas largas). Lo demás está en el panel lateral ([LibrarySettingsItems]). */
internal enum class LibraryScreen { REVIEW, JOINED, SOURCES }

/**
 * Configuración › Biblioteca › una pantalla completa, sin nada encima (antes llevaba pestañas y cifras y la tarjeta de decisión quedaba recortada). Atrás vuelve al panel:
 *  - **REVIEW:** [PendingTab], las dudas de AniList (aceptar un enlace es lo que une las fichas).
 *  - **JOINED:** [JoinedTab], las series unidas, para separar. Los cambios de uniones se aplican al salir.
 *  - **SOURCES:** [SourcesTab], qué tiene el catálogo de cada fuente y recorrerla.
 */
@Composable
internal fun LibraryDialog(screen: LibraryScreen, onDismiss: () -> Unit) {
    var browsing by remember { mutableStateOf<SourceCatalog?>(null) }
    var selected by remember { mutableStateOf<Catalog.Joined?>(null) }
    var changed by remember { mutableStateOf(false) }
    // Las fichas separadas desaparecen de las columnas al momento, aunque el catálogo no se reagrupe hasta salir.
    var separated by remember { mutableStateOf(emptySet<Ficha>()) }
    // El aviso de «Cambios guardados» (que reagrupa el catálogo) sale una sola vez, al salir.
    DisposableEffect(Unit) { onDispose { if (changed) CardMenu.titlesChanged("Cambios guardados") } }

    fun separate(ficha: Ficha, group: List<Ficha>) {
        // La ficha que se queda de referencia: otra de su grupo que no se haya separado ya.
        val reference = group.firstOrNull { it != ficha && it !in separated } ?: return
        UserTitles.separate(reference.first, reference.second, ficha.first, ficha.second)
        separated = separated + ficha
        changed = true
    }

    Dialog(
        onDismissRequest = { if (selected != null) selected = null else if (browsing != null) browsing = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 48.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Las dudas y la lista de series de una fuente llevan su propia cabecera: no se pone otra encima.
            val title = when (screen) {
                LibraryScreen.JOINED -> if (selected == null) "Series unidas" else null
                LibraryScreen.SOURCES -> if (browsing == null) "Fuentes" else null
                LibraryScreen.REVIEW -> null
            }
            title?.let { Text(it, color = Color.White, fontSize = Text20, fontWeight = FontWeight.Bold) }
            Box(Modifier.weight(1f)) {
                when (screen) {
                    LibraryScreen.REVIEW -> PendingTab(onDismiss)
                    LibraryScreen.JOINED -> JoinedTab(selected, { selected = it }, separated, ::separate) { changed = true }
                    LibraryScreen.SOURCES -> SourcesTab(browsing) { browsing = it }
                }
            }
        }
    }
}
