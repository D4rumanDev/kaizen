package app.kaizen.tv.settings

import app.kaizen.tv.ui.theme.*

import app.kaizen.tv.ui.IconTextItem
import kotlin.reflect.KMutableProperty0
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.kaizen.commons.KaizenSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.rememberUpdatedState
import app.kaizen.tv.ui.Motion
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.tv.ui.KaizenToast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.Icon
import app.kaizen.R

/** Kaizen: modelo de ajustes (categorías con opciones), como los diálogos de SmartTube. */
sealed class SettingItem(val title: String)

class CheckItem(title: String, val summary: String? = null, val get: () -> Boolean, val set: (Boolean) -> Unit) : SettingItem(title) {
    constructor(title: String, summary: String?, property: KMutableProperty0<Boolean>) : this(title, summary, property::get, property::set)
}

class ChoiceItem(title: String, val options: List<String>, val get: () -> Int, val set: (Int) -> Unit) : SettingItem(title)

/** Elección entre [values] de un ajuste, mostrados con [label]. */
fun <T> choiceItem(title: String, values: List<T>, property: KMutableProperty0<T>, label: (T) -> String) =
    ChoiceItem(title, values.map(label), { values.indexOf(property.get()) }, { property.set(values[it]) })

/** Varias opciones a la vez (casillas): [get] y [set] trabajan con índices de [options]. Con [minSelected] > 0 no se puede quedar por debajo de ese mínimo: se avisa con [minMessage]. */
class MultiChoiceItem(
    title: String,
    val options: List<String>,
    val get: () -> Set<Int>,
    val set: (Set<Int>) -> Unit,
    val minSelected: Int = 0,
    val minMessage: String = ""
) : SettingItem(title)

/** Lista que se ordena: OK agarra un elemento, arriba/abajo lo mueven y OK lo suelta; el foco lo acompaña. */
class ReorderItem(title: String, val get: () -> List<String>, val set: (List<String>) -> Unit) : SettingItem(title)

class ActionItem(title: String, val summary: () -> String, val run: (Context) -> Unit) : SettingItem(title)

/**
 * Acción que borra o cambia cosas: al pulsarla se abre una confirmación con el [warning] y dos opciones, con el foco en
 * «Cancelar». [run] solo se ejecuta si se elige [confirmLabel].
 */
class ConfirmItem(title: String, val summary: String, val warning: String, val confirmLabel: String, val run: (Context) -> Unit) : SettingItem(title)

/** Un submenú: abre otra lista de opciones en el mismo panel (Atrás vuelve a la anterior). Sirve para agrupar muchas opciones parecidas bajo una sola fila. */
class SubMenuItem(title: String, val summary: () -> String, val category: SettingsCategory) : SettingItem(title)

class SettingsCategory(override val title: String, @DrawableRes override val icon: Int, val items: () -> List<SettingItem>) : IconTextItem

/**
 * Panel lateral derecho con las opciones de una categoría. Las opciones de elección abren
 * su lista (radio) en el mismo panel; Atrás vuelve a la categoría y luego cierra.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsPanel(root: SettingsCategory, refresh: Int = 0, onDismiss: () -> Unit) {
    // Pila de submenús: el panel muestra el último; Atrás saca uno antes de cerrar.
    // Cada nivel guarda el título de la fila desde la que se abrió, para devolverle el foco al volver.
    var stack by remember { mutableStateOf(listOf<Pair<SettingsCategory, String?>>(root to null)) }
    val category = stack.last().first
    val context = androidx.compose.ui.platform.LocalContext.current
    var openChoice by remember { mutableStateOf<SettingItem?>(null) }
    // Los ajustes de KaizenSettings avisan solos de cada cambio; este contador solo cubre lo que no es un ajuste (acciones que cambian un resumen, la validación TLS).
    val settings by KaizenSettings.changes.collectAsState("")
    var version by remember { mutableIntStateOf(0) }
    var focusTitle by remember { mutableStateOf<String?>(null) }
    // Elemento reordenado que conserva el foco; vive aquí porque las filas se reconstruyen en cada recomposición.
    var movedName by remember(openChoice) { mutableStateOf<String?>(null) }
    var grabbed by remember(openChoice) { mutableStateOf(false) }

    fun closeChoice() {
        focusTitle = openChoice?.title
        openChoice = null
    }

    // El panel entra deslizándose desde la derecha y al cerrar sale igual: el diálogo se quita cuando la animación de salida termina.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val onDismissLatest by rememberUpdatedState(onDismiss)
    LaunchedEffect(shown.currentState, shown.targetState) { if (!shown.currentState && !shown.targetState) onDismissLatest() }
    fun dismissAnimated() {
        shown.targetState = false
    }

    // Un ajuste de texto abre su cuadro para escribir encima del panel; al cerrarlo vuelve a la categoría.
    (openChoice as? TextItem)?.let { TextEditDialog(it) { closeChoice() } }
    Dialog(
        onDismissRequest = { if (openChoice != null) closeChoice() else if (stack.size > 1) { focusTitle = stack.last().second; stack = stack.dropLast(1) } else dismissAnimated() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AnimatedVisibility(visibleState = shown, modifier = Modifier.fillMaxSize(),
            enter = fadeIn(Motion.spec(PANEL_ANIMATION_MS)), exit = fadeOut(Motion.spec(PANEL_ANIMATION_MS))) {
        Box(Modifier.fillMaxSize().background(Black50)) {
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(PANEL_WIDTH_RATIO)
                    .animateEnterExit(enter = slideInHorizontally(Motion.spec(PANEL_ANIMATION_MS)) { it },
                        exit = slideOutHorizontally(Motion.spec(PANEL_ANIMATION_MS)) { it })
                    .background(PanelColor).padding(start = 24.dp, top = 40.dp, end = 24.dp, bottom = 24.dp)
            ) {
                // Miga de pan: dónde estás («Reproductor › Controles › Salto»), para no perderse al entrar en submenús y listas.
                val crumb = (stack.map { it.first.title } + listOfNotNull(openChoice?.title)).joinToString(" › ")
                Text(crumb, color = Color.White, fontSize = Text20, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, bottom = 16.dp))
                // Leer estos dos valores suscribe el panel: se relee al cambiar un ajuste y cuando quien lo abre avisa de que las opciones han cambiado (pistas del vídeo).
                @Suppress("UNUSED_EXPRESSION") settings
                @Suppress("UNUSED_EXPRESSION") version
                @Suppress("UNUSED_EXPRESSION") refresh
                val rows = rows(context, category, openChoice, focusTitle, movedName, grabbed, onMoved = { movedName = it }, onGrab = { grabbed = it }, onChanged = { version++ }, onPush = { sub, from -> stack = stack + (sub to from); focusTitle = null }, onChosen = { title -> focusTitle = title; openChoice = null }, onOpen = { openChoice = it })
                val requesters = remember(openChoice, category, rows.size) { List(rows.size) { FocusRequester() } }
                // Foco en la primera opción, o en la que se acaba de cambiar.
                // En una lista ordenable el foco sigue al elemento movido: se pide de nuevo tras cada cambio.
                LaunchedEffect(requesters, if (openChoice is ReorderItem) version else 0) {
                    val index = rows.indexOfFirst { it.focus }.coerceAtLeast(0)
                    runCatching { requesters.getOrNull(index)?.requestFocus() }
                }
                Column(Modifier.verticalScroll(rememberScrollState()).onPreviewKeyEvent { event ->
                    val open = openChoice
                    val step = when (event.key) { Key.DirectionUp -> -1; Key.DirectionDown -> 1; else -> 0 }
                    if (!grabbed || open !is ReorderItem || step == 0) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) {
                        val order = open.get().toMutableList()
                        val from = order.indexOf(movedName)
                        val to = from + step
                        if (from >= 0 && to in order.indices) {
                            order[from] = order[to].also { order[to] = order[from] }
                            open.set(order)
                            version++
                        }
                    }
                    true
                }) {
                    rows.forEachIndexed { index, row -> SettingRow(row, Modifier.focusRequester(requesters[index])) }
                }
            }
        }
        }
    }
}

private const val PANEL_ANIMATION_MS = 250

/**
 * La opción abierta se vuelve a pedir a la categoría: así sus opciones (pistas de audio o subtítulos que aparecen
 * mientras el panel está abierto) están al día sin cerrar la lista. Si ya no existe, se conserva la anterior.
 */
private fun freshItem(category: SettingsCategory, stale: SettingItem): SettingItem =
    category.items().firstOrNull { it.title == stale.title && it.javaClass == stale.javaClass } ?: stale

private class PanelRow(val title: String, val summary: String?, val value: String, val focus: Boolean, val onClick: (() -> Unit)?)

/** Filas del panel: las opciones de la categoría, o las de la lista abierta. */
private fun rows(
    context: android.content.Context,
    category: SettingsCategory,
    openItem: SettingItem?,
    focusTitle: String?,
    movedName: String?,
    grabbed: Boolean,
    onMoved: (String?) -> Unit,
    onGrab: (Boolean) -> Unit,
    onChanged: () -> Unit,
    onPush: (SettingsCategory, String) -> Unit,
    onChosen: (String) -> Unit,
    onOpen: (SettingItem) -> Unit
): List<PanelRow> = when (val open = openItem?.let { stale -> freshItem(category, stale) }) {
    is ChoiceItem -> {
        val selected = open.get()
        open.options.mapIndexed { index, option ->
            PanelRow(option, null, if (index == selected) RADIO_ON else RADIO_OFF, index == selected) {
                open.set(index)
                onChosen(open.title)
            }
        }
    }
    is ReorderItem -> open.get().let { names ->
        names.mapIndexed { index, name ->
            val held = grabbed && name == movedName
            PanelRow(name, if (held) "Arriba/abajo mueven · OK suelta" else null, if (held) "↑↓ ${index + 1}" else "${index + 1}",
                if (movedName != null) name == movedName else index == 0) {
                onMoved(name)
                onGrab(!held)
                onChanged()
            }
        }
    }
    is TextItem -> emptyList()
    is ConfirmItem -> listOf(
        // El aviso es solo texto: sin clic no recibe el foco del mando.
        PanelRow(open.warning, null, "", false, null),
        PanelRow("Cancelar", null, "", true) { onChosen(open.title) },
        PanelRow(open.confirmLabel, null, "", false) {
            open.run(context)
            onChosen(open.title)
            onChanged()
        }
    )
    is MultiChoiceItem -> open.options.mapIndexed { index, option ->
        PanelRow(option, null, if (index in open.get()) CHECKED else UNCHECKED, index == 0) {
            val selected = open.get().toMutableSet()
            if (!selected.add(index)) selected.remove(index)
            if (selected.size < open.minSelected) {
                KaizenToast.toast(open.minMessage)
            } else {
                open.set(selected)
                onChanged()
            }
        }
    }
    else -> category.items().mapIndexed { index, item ->
        val focus = if (focusTitle != null) item.title == focusTitle else index == 0
        when (item) {
            is CheckItem -> PanelRow(item.title, item.summary, if (item.get()) CHECKED else UNCHECKED, focus) {
                item.set(!item.get())
                onChanged()
            }
            is ChoiceItem -> PanelRow(item.title, item.options.getOrNull(item.get()), MORE, focus) { onOpen(item) }
            is MultiChoiceItem -> PanelRow(item.title, item.get().sorted().joinToString { item.options[it] }.ifEmpty { "Ninguna" }, MORE, focus) { onOpen(item) }
            is ReorderItem -> PanelRow(item.title, "OK agarra un botón, arriba/abajo lo mueven", MORE, focus) {
                onMoved(null)
                onOpen(item)
            }
            is ActionItem -> PanelRow(item.title, item.summary(), "", focus) {
                item.run(context)
                onChanged()
            }
            is ConfirmItem -> PanelRow(item.title, item.summary, "", focus) { onOpen(item) }
            is TextItem -> PanelRow(item.title, item.summary(), MORE, focus) { onOpen(item) }
            is SubMenuItem -> PanelRow(item.title, item.summary(), MORE, focus) { onPush(item.category, item.title) }
        }
    }
}

@Composable
private fun SettingRow(row: PanelRow, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    // El glifo del valor es solo dibujo: el rol y el estado van en la semántica.
    val role = when (row.value) {
        CHECKED, UNCHECKED -> Role.Checkbox
        RADIO_ON, RADIO_OFF -> Role.RadioButton
        else -> Role.Button
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(ShapeMd)
            .background(if (focused) White20 else Color.Transparent)
            .then(if (focused) Modifier.border(BorderStroke(2.dp, Color.White), ShapeMd) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .then(row.onClick?.let { Modifier.clickable(role = role, onClick = it) } ?: Modifier)
            .semantics {
                when (row.value) {
                    CHECKED -> toggleableState = ToggleableState.On
                    UNCHECKED -> toggleableState = ToggleableState.Off
                    RADIO_ON -> selected = true
                    RADIO_OFF -> selected = false
                    else -> Unit
                }
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title, color = Color.White, fontSize = Text17)
            if (!row.summary.isNullOrEmpty()) Text(row.summary, color = White70, fontSize = Text14, modifier = Modifier.padding(top = 2.dp))
        }
        val mark = markIcon(row.value)
        if (mark != null) {
            // El rol y el estado ya van en la semántica de la fila: el icono es solo dibujo.
            Icon(painterResource(mark), contentDescription = null, tint = Color.White, modifier = Modifier.padding(start = 12.dp).size(IconLg))
        } else {
            Text(row.value, color = Color.White, fontSize = Text17, modifier = Modifier.clearAndSetSemantics { }.padding(start = 12.dp))
        }
    }
}

/** El icono de los valores que son una marca (casilla, opción elegida, «hay más»); null si el valor es un texto, como un número. */
private fun markIcon(value: String): Int? = when (value) {
    CHECKED -> R.drawable.ic_check_box_kaizen
    UNCHECKED -> R.drawable.ic_check_box_outline_kaizen
    RADIO_ON -> R.drawable.ic_radio_on_kaizen
    RADIO_OFF -> R.drawable.ic_radio_off_kaizen
    MORE -> R.drawable.ic_chevron_right_kaizen
    else -> null
}

private const val PANEL_WIDTH_RATIO = 0.36f
// Marcas de PanelRow.value: se dibujan con icono (markIcon), no como texto.
private const val CHECKED = "mark:checked"
private const val UNCHECKED = "mark:unchecked"
private const val RADIO_ON = "mark:radio-on"
private const val RADIO_OFF = "mark:radio-off"
private const val MORE = "mark:more"
