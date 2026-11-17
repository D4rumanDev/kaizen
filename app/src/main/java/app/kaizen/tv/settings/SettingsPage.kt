package app.kaizen.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kaizen.R
import app.kaizen.commons.appString
import app.kaizen.tv.ui.CardGrid
import app.kaizen.tv.ui.IconTextCard
import app.kaizen.tv.ui.StatsPage
import app.kaizen.tv.ui.TintedIcon
import app.kaizen.tv.ui.theme.Background
import kotlinx.coroutines.delay

/** Una tarjeta de la rejilla de Configuración: abre un panel lateral, una pantalla propia o un diálogo. */
private class Entry(val title: String, @androidx.annotation.DrawableRes val icon: Int, val onClick: () -> Unit)

/**
 * Kaizen: «Configuración» con el diseño de los ajustes de SmartTube: rejilla de nueve categorías y, al pulsar una, panel lateral con sus opciones ([SettingsPanel]) o, en la
 * Biblioteca, una pantalla con pestañas. Al cerrar un panel o una pantalla, el foco vuelve a la tarjeta de la que se salió.
 *
 */
@Composable
internal fun SettingsPage() {
    val context = LocalContext.current
    var showButtons by remember { mutableStateOf(false) }
    var showSections by remember { mutableStateOf(false) }
    var showDebugLog by remember { mutableStateOf(false) }
    var showSmbPicker by remember { mutableStateOf(false) }
    val categories = remember { SettingsCategories(context, { showButtons = true }, { showSections = true }, { showDebugLog = true }, { showSmbPicker = true }).menu() }
    var open by remember { mutableStateOf<SettingsCategory?>(null) }
    var showStats by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf<LibraryScreen?>(null) }
    // Abrir una pantalla desde fuera (adb: `node tools/tv.js series`): TVMain deja el pedido y aquí se consume. «Series» abre la Biblioteca en «Por revisar».
    val request by OpenRequest.dialog.collectAsState()
    LaunchedEffect(request) { if (request == "series") { library = LibraryScreen.REVIEW; OpenRequest.dialog.value = null } }

    // Biblioteca: panel lateral con las cifras en sus filas; las pantallas que necesitan altura (dudas, series unidas, fuentes) se abren encima del panel.
    val stats = remember { LibraryStats() }
    val libraryCategory = remember { SettingsCategory("Biblioteca", R.drawable.header_ic_directory, LibrarySettingsItems(stats) { library = it }::items) }
    // Las cifras se leen al abrir el panel y al volver de una pantalla (decidir una duda las cambia).
    LaunchedEffect(open, library) { if (open === libraryCategory && library == null) stats.load() }

    // Orden: lo que más se toca (reproducir, pantalla), el contenido (Biblioteca, fuentes, datos), la copia y lo informativo.
    val entries = listOf(
        categories.player.asEntry { open = it },
        categories.screen.asEntry { open = it },
        libraryCategory.asEntry { open = it },
        categories.network.asEntry { open = it },
        categories.data.asEntry { open = it },
        categories.backup.asEntry { open = it },
        Entry(appString(R.string.ui_estadisticas), R.drawable.header_ic_stats) { showStats = true },
        categories.about.asEntry { open = it },
        categories.debug.asEntry { open = it }
    )

    // Foco: la tarjeta de la que se salió recupera el foco al cerrar el panel o la pantalla (si no, Compose lo deja en ninguna y el mando «se pierde»).
    val requesters = remember { List(entries.size) { FocusRequester() } }
    var lastOpened by remember { mutableIntStateOf(0) }
    var hadOverlay by remember { mutableStateOf(false) }
    val overlay = open != null || showStats || library != null || showDebugLog || showButtons || showSections || showSmbPicker
    LaunchedEffect(overlay) {
        if (overlay) hadOverlay = true
        else if (hadOverlay) {
            delay(80)
            runCatching { requesters[lastOpened].requestFocus() }
        }
    }

    CardGrid {
        items(entries.size) { index ->
            val entry = entries[index]
            IconTextCard(entry.title, { lastOpened = index; entry.onClick() }, modifier = Modifier.focusRequester(requesters[index])) { TintedIcon(entry.icon) }
        }
    }
    open?.let { SettingsPanel(it) { open = null } }
    library?.let { screen -> LibraryDialog(screen) { library = null } }
    if (showDebugLog) DebugLogDialog { showDebugLog = false }
    if (showSmbPicker) SmbPickerDialog { showSmbPicker = false }
    if (showButtons) PlayerButtonsDialog { showButtons = false }
    if (showSections) MenuSectionsDialog { showSections = false }
    if (showStats) {
        Dialog(onDismissRequest = { showStats = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Background).padding(top = 24.dp)) { StatsPage() }
        }
    }
}

private fun SettingsCategory.asEntry(open: (SettingsCategory) -> Unit) = Entry(title, icon) { open(this) }

/** Restablecer y vaciar la caché sin pasar por la rejilla de Configuración (Atrás mantenido en el menú). */
@Composable
fun RecoveryPanel(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val category = remember { SettingsCategory(appString(R.string.ui_restablecer), R.drawable.ic_sync, SettingsCategories(context)::resetItems) }
    SettingsPanel(category, onDismiss = onDismiss)
}

/** La pantalla de Configuración que pide abrir un intent de fuera ([TVMain]); null si ninguna. */
internal object OpenRequest { val dialog = kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
