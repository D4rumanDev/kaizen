@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.settings

import app.kaizen.commons.appString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.kaizen.tv.ui.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.commons.KaizenSettings
import app.kaizen.tv.exoplayer.PlayerButton
import kotlinx.coroutines.flow.scan
import app.kaizen.tv.ui.LocalIconTint
import app.kaizen.tv.ui.CardGrid
import app.kaizen.tv.ui.IconTextCard
import app.kaizen.tv.ui.MainSections
import app.kaizen.tv.ui.TintedIcon
import app.kaizen.tv.ui.components.iconFor
import app.kaizen.tv.ui.KaizenToast

/** Un elemento de [ArrangeDialog]: su clave (estable), su nombre, si se puede ocultar y su icono. */
internal class ArrangeEntry(val key: String, val label: String, val hideable: Boolean = true, val icon: @Composable () -> Unit)

/**
 * Pantalla de tarjetas (como Fuentes) para mostrar, ocultar y organizar una lista: botones del reproductor, secciones del menú.
 * Pulsar una tarjeta la muestra u oculta. «Organizar» cambia el modo: pulsar una tarjeta la agarra, izquierda y derecha la mueven y OK
 * la suelta. [entries] llega en el orden guardado; cada cambio se guarda al momento con [setOrder] y [setHidden].
 *
 * [preview] dibuja cómo quedará el resultado con las mismas piezas que la pantalla real: recibe las entradas visibles, en su orden, y las
 * ocultas. Va encima de las tarjetas, o a la derecha con [previewAtEnd].
 */
@Composable
internal fun ArrangeDialog(
    title: String,
    entries: List<ArrangeEntry>,
    isHidden: (String) -> Boolean,
    setHidden: (String, Boolean) -> Unit,
    setOrder: (List<String>) -> Unit,
    hint: String = "Los que no caben en la pantalla no se ven.",
    previewAtEnd: Boolean = false,
    preview: (@Composable (shown: List<ArrangeEntry>, hidden: List<ArrangeEntry>, marked: String?) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var order by remember { mutableStateOf(entries.map { it.key }) }
    // KaizenSettings avisa de cada cambio con la clave; se cuentan los avisos (no se compara la clave: dos cambios seguidos de la misma no recompondrían).
    val settings by remember { KaizenSettings.changes.scan(0) { n, _ -> n + 1 } }.collectAsStateWithLifecycle(0)
    var organizing by remember { mutableStateOf(false) }
    var grabbed by remember { mutableStateOf<String?>(null) }
    val byKey = remember(entries) { entries.associateBy { it.key } }
    val gridState = rememberLazyGridState()
    // El foco sigue a la tarjeta agarrada, pero la rejilla no siempre la desplaza hasta verla: si queda fuera, se lleva a la vista.
    LaunchedEffect(order, grabbed) {
        val item = (order.indexOf(grabbed ?: return@LaunchedEffect) + 1).takeIf { it > 0 } ?: return@LaunchedEffect
        val info = gridState.layoutInfo
        val seen = info.visibleItemsInfo.firstOrNull { it.index == item }
        if (seen == null || seen.offset.y < info.viewportStartOffset || seen.offset.y + seen.size.height > info.viewportEndOffset) gridState.animateScrollToItem(item)
    }

    fun move(step: Int) {
        val key = grabbed ?: return
        val from = order.indexOf(key)
        val to = from + step
        if (from < 0 || to !in order.indices) return
        val moved = order.toMutableList()
        moved[from] = moved[to].also { moved[to] = moved[from] }
        order = moved
        setOrder(moved)
    }

    Dialog(onDismissRequest = { if (grabbed != null) grabbed = null else onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Background).padding(top = 32.dp)) {
            Text(title, color = Color.White, fontSize = Text20, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 48.dp))
            Text(
                if (organizing) "Pulsa uno para agarrarlo; las flechas lo mueven y OK lo suelta." else "Pulsa uno para mostrarlo u ocultarlo. $hint",
                color = White70, fontSize = Text14, modifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp)
            )
            settings // se relee al cambiar un ajuste
            val shown = order.mapNotNull { byKey[it] }.filter { !it.hideable || !isHidden(it.key) }
            val hidden = order.mapNotNull { byKey[it] }.filter { it.hideable && isHidden(it.key) }
            if (preview != null && !previewAtEnd) preview(shown, hidden, grabbed)
            Row(Modifier.weight(1f)) {
                // Con uno agarrado, las flechas lo mueven (el foco lo sigue porque la tarjeta conserva su clave); arriba y abajo saltan una fila.
                Box(Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (grabbed == null) return@onPreviewKeyEvent false
                    // Columnas = tarjetas visibles en la primera fila visible; si caben todas en una, el salto vertical no mueve nada.
                    val visible = gridState.layoutInfo.visibleItemsInfo
                    val columns = visible.count { it.offset.y == visible.firstOrNull()?.offset?.y }.coerceAtLeast(1)
                    val step = when (event.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; Key.DirectionUp -> -columns; Key.DirectionDown -> columns; else -> 0 }
                    if (step != 0 && event.type == KeyEventType.KeyDown) move(step)
                    step != 0
                }) {
                    CardGrid(fitToParent = true, state = gridState) {
                        item {
                            IconTextCard(if (organizing) "Terminar de organizar" else "Organizar", { organizing = !organizing; grabbed = null }) {
                                TintedIcon(R.drawable.settings_ic_sections)
                            }
                        }
                        items(order.size, key = { order[it] }) { index ->
                            settings // se relee al cambiar un ajuste
                            val key = order[index]
                            val entry = byKey[key] ?: return@items
                            val visible = !entry.hideable || !isHidden(key)
                            val isGrabbed = organizing && grabbed == key
                            val state = when {
                                isGrabbed -> "◀ ▶ ▲ ▼ Moviendo · OK suelta"
                                organizing -> "Posición ${index + 1} · OK agarra"
                                !entry.hideable -> "Siempre visible"
                                visible -> "✔ Visible"
                                else -> "✖ Oculto"
                            }
                            // Agarrado = ámbar; activo = gris; desactivado = rojo con el icono rojo oscuro.
                            IconTextCard("${entry.label}\n$state", {
                                when {
                                    organizing -> grabbed = if (grabbed == key) null else key
                                    !entry.hideable -> KaizenToast.toast("${entry.label} siempre está a la vista")
                                    else -> setHidden(key, visible)
                                }
                            }, background = if (isGrabbed) GrabbedCard else if (visible) ActiveCard else InactiveCard,
                                contentColor = if (isGrabbed) GrabbedContent else if (visible) Color.White else InactiveContent) {
                                entry.icon()
                            }
                        }
                    }
                }
                // Con muchas secciones el panel puede ser más alto que la pantalla: se desplaza (sin foco) en vez de cortarse.
                if (preview != null && previewAtEnd) Box(Modifier.padding(end = 24.dp, top = 8.dp, bottom = 24.dp).verticalScroll(rememberScrollState())) { preview(shown, hidden, grabbed) }
            }
        }
    }
}

/** Configuración › Reproductor › Botones. */
@Composable
internal fun PlayerButtonsDialog(onDismiss: () -> Unit) {
    val entries = remember {
        PlayerButton.ordered().map { button -> ArrangeEntry(button.name, button.label, button.hideable) { TintedPlayerIcon(button) } }
    }
    ArrangeDialog(
        title = "Botones del reproductor",
        entries = entries,
        isHidden = { it in KaizenSettings.hiddenPlayerButtons },
        setHidden = { key, hide -> KaizenSettings.hiddenPlayerButtons = KaizenSettings.hiddenPlayerButtons.let { if (hide) it + key else it - key } },
        setOrder = { keys -> PlayerButton.setOrder(keys.mapNotNull { key -> PlayerButton.entries.firstOrNull { it.name == key } }) },
        preview = { shown, hidden, marked -> PlayerPreview(shown.map { PlayerButton.valueOf(it.key) }, marked?.let(PlayerButton::valueOf)) },
        onDismiss = onDismiss
    )
}

/** Configuración › Pantalla › Secciones del menú. */
@Composable
internal fun MenuSectionsDialog(onDismiss: () -> Unit) {
    val entries = remember {
        MainSections.ordered().map { (id, name) -> ArrangeEntry(id.toString(), name) { TintedIcon(sectionIcon(id)) } }
    }
    ArrangeDialog(
        title = "Secciones del menú",
        entries = entries,
        isHidden = { it.toInt() in KaizenSettings.hiddenRows },
        setHidden = { key, hide -> KaizenSettings.hiddenRows = KaizenSettings.hiddenRows.let { if (hide) it + key.toInt() else it - key.toInt() } },
        setOrder = { keys -> KaizenSettings.menuOrder = keys.joinToString(",") },
        hint = "Los cambios se ven al volver al menú.",
        previewAtEnd = true,
        preview = { shown, hidden, marked -> MenuPreview(shown.map { it.key.toInt() to it.label }, marked?.toInt()) },
        onDismiss = onDismiss
    )
}

private fun sectionIcon(id: Int): Int = if (id == MainSections.SOURCES) R.drawable.settings_ic_server else iconFor(id)

/**
 * Los controles del reproductor tal como quedarán: mismos círculos (48 dp) e iconos (32 dp), mismo margen y «Restante» a la derecha, y el
 * mismo cálculo de cuántos caben ([PlayerButton.fitting]). Los que no caben salen debajo apagados, y las ocultas se nombran.
 */
@Composable
private fun PlayerPreview(buttons: List<PlayerButton>, marked: PlayerButton?) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val remainingWidth = with(density) { measurer.measure(appString(R.string.ui_restante_ejemplo), TextStyle(fontSize = PlayerTimeText)).size.width.toDp() }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).clip(ShapeXl)
            .background(Brush.verticalGradient(listOf(Color(0xFF202020), Color(0xFF080808)))).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(appString(R.string.ui_asi_se_vera_en_el_reproductor), color = White70, fontSize = Text14, modifier = Modifier.padding(start = 24.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Los mismos márgenes que los controles reales (48 dp a cada lado) menos los 24 dp de este recuadro, y «Restante» con su margen.
            val available = maxWidth - 24.dp * 2 - remainingWidth - 16.dp
            val fits = PlayerButton.fitting(buttons, available.value.toInt() - PlayerButton.FIT_SLACK_DP)
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(PlayerButton.SPACING_DP.dp)) {
                        fits.forEach { PreviewCircle(it, 1f, it == marked) }
                    }
                    Text(appString(R.string.ui_restante_ejemplo), color = White80, fontSize = PlayerTimeText, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
                }
                val cut = buttons.filter { it !in fits }
                if (cut.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PlayerButton.SPACING_DP.dp)) {
                        Text(appString(R.string.ui_no_caben), color = White50, fontSize = Text14, modifier = Modifier.padding(end = 4.dp))
                        cut.forEach { PreviewCircle(it, 0.5f, it == marked) }
                    }
                }
            }
        }
    }
}

/** Un botón del reproductor en pequeño, igual que en los controles: círculo de [PlayerButton.ROUND_DP] y su icono. */
@Composable
private fun PreviewCircle(button: PlayerButton, alpha: Float, marked: Boolean) {
    Box(
        Modifier.size(width = PlayerButton.widthDp(button).dp, height = PlayerButton.ROUND_DP.dp).alpha(alpha).clip(CircleShape)
            .then(if (marked) Modifier.background(GrabbedCard).border(2.dp, Color.White, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) { PlayerIconContent(button) }
}

/** El contenido de un botón: su icono de [PlayerIcon], o el texto «1x» del de velocidad. */
@Composable
private fun PlayerIconContent(button: PlayerButton) {
    val res = playerIconRes(button)
    if (res == null) Text(button.previewText.orEmpty(), color = Color.White, fontSize = PlayerButtonText, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    else Icon(painterResource(res), contentDescription = button.label, tint = Color.White, modifier = Modifier.size(PlayerIcon))
}

/** El mismo icono para las tarjetas de la rejilla, que son más grandes (48 dp). */
@Composable
private fun TintedPlayerIcon(button: PlayerButton) {
    val res = playerIconRes(button)
    if (res == null) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Text(button.previewText.orEmpty(), color = LocalIconTint.current, fontSize = Text20, fontWeight = FontWeight.Bold)
    } else TintedIcon(res)
}

private fun playerIconRes(button: PlayerButton): Int? = when (button) {
    PlayerButton.PREVIOUS -> androidx.media3.ui.R.drawable.exo_icon_previous
    PlayerButton.REWIND -> androidx.media3.ui.R.drawable.exo_icon_rewind
    PlayerButton.PLAY -> androidx.media3.ui.R.drawable.exo_icon_play
    PlayerButton.FORWARD -> androidx.media3.ui.R.drawable.exo_icon_fastforward
    PlayerButton.NEXT -> androidx.media3.ui.R.drawable.exo_icon_next
    PlayerButton.SKIP_OPENING -> R.drawable.ic_skip_kaizen
    PlayerButton.FAVORITE -> R.drawable.ic_favorite_border_kaizen
    PlayerButton.SPEED -> null
    PlayerButton.SCALE -> R.drawable.ic_video_scale
    PlayerButton.TRACKS -> R.drawable.ic_quality_kaizen
    PlayerButton.SERIES -> R.drawable.ic_queue_list_kaizen
    PlayerButton.DEBUG -> R.drawable.ic_bug_kaizen
}

/**
 * El menú lateral tal como quedará: las secciones visibles en su orden, con su icono («Buscar» y «Configuración» van en la barra de arriba, no aquí). La
 * primera sale marcada como la elegida; las ocultas se nombran debajo.
 */
@Composable
private fun MenuPreview(rows: List<Pair<Int, String>>, marked: Int?) {
    Column(Modifier.width(260.dp).clip(ShapeLg).background(SurfaceDark).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(appString(R.string.ui_asi_se_vera_en_el_menu), color = White70, fontSize = Text14, modifier = Modifier.padding(bottom = 6.dp))
        rows.filter { it.first != MainSections.SEARCH }.forEachIndexed { index, (id, label) ->
            Row(
                Modifier.fillMaxWidth().clip(ShapeLg).background(if (id == marked) GrabbedCard else if (index == 0) ActiveCard else Color.Transparent).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(painterResource(sectionIcon(id)), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(label, color = if (index == 0) Color.White else White80, fontSize = Text16, maxLines = 1)
            }
        }
    }
}
