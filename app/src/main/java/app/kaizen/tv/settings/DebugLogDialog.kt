@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.settings

import app.kaizen.R
import app.kaizen.commons.appString
import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import app.kaizen.tv.ui.components.Legend
import app.kaizen.tv.ui.components.LogLegend
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.commons.AppErrors
import app.kaizen.commons.DebugLog
import app.kaizen.commons.KaizenSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

/** Cada cuánto se mira si el registro ha cambiado mientras el visor está abierto. */
private val REFRESH = 2.seconds

private val TimeWidth = 80.dp
private val LevelWidth = 90.dp
private val AreaWidth = 190.dp
private val RowDivider = White20
private val FooterHeight = 190.dp
private val StripedRow = Color(0x14FFFFFF)

/**
 * Configuración › Depuración › Ver el registro: lo escrito en [DebugLog] en forma de tabla (hora, gravedad, parte de la app y qué
 * pasó), con la línea más reciente abajo (el mando sube y baja). Se actualiza solo mientras está abierto: si ya estabas al final
 * sigue las nuevas anotaciones; si subiste a mirar algo, no te mueve.
 */
@Composable
internal fun DebugLogDialog(onDismiss: () -> Unit) {
    var lines by remember { mutableStateOf<List<LogLine>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(AppErrors.sessionLine()) }
    var health by remember { mutableStateOf<DebugLog.Health?>(null) }
    var healthText by remember { mutableStateOf("") }
    val last = remember { FocusRequester() }
    val state = rememberLazyListState()
    // La fila enfocada: su texto completo se lee en el panel del pie (en la tabla cada anotación ocupa una línea).
    var selected by remember { mutableStateOf<LogLine?>(null) }

    LaunchedEffect(Unit) {
        var stamp: Triple<Long, Long, Int>? = null
        while (true) {
            // Solo se vuelve a leer y analizar si el fichero cambió (hora de modificación y tamaño) o si hay líneas esperando en memoria porque el disco falla.
            val current = withContext(Dispatchers.IO) {
                val waiting = DebugLog.health().waiting
                runCatching { DebugLog.file.let { Triple(it.lastModified(), it.length(), waiting) } }.getOrElse { Triple(0L, 0L, waiting) }
            }
            if (!loaded || current != stamp) {
                stamp = current
                val fresh = withContext(Dispatchers.IO) { entryLines(DebugLog.parse(DebugLog.read())).filter { it.kind != LogLine.Kind.DETAIL } }
                val following = !loaded || state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= lines.lastIndex } ?: true
                val firstLoad = !loaded
                lines = fresh
                loaded = true
                if (following && fresh.isNotEmpty()) runCatching { state.scrollToItem(fresh.lastIndex) }
                if (firstLoad && fresh.isNotEmpty()) {
                    delay(100.milliseconds)
                    runCatching { last.requestFocus() }
                }
            }
            session = AppErrors.sessionLine()
            withContext(Dispatchers.IO) { DebugLog.health() to DebugLog.healthLine() }.let { (h, text) -> health = h; healthText = text }
            delay(REFRESH)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 24.dp, vertical = 24.dp)) {
            Text("Registro · nivel: ${DebugLog.LEVELS[KaizenSettings.debugLevel.coerceIn(0, DebugLog.LEVELS.lastIndex)]} · ${lines.count { it.kind == LogLine.Kind.ENTRY }} anotaciones",
                color = Color.White, fontSize = Text17)
            Text(session, color = if (session.endsWith("sin errores")) White70 else ErrorTint, fontSize = Text14, modifier = Modifier.padding(top = 4.dp))
            if (healthText.isNotEmpty()) Text(healthText, color = if (health?.diskProblem != null) ErrorTint else White70, fontSize = Text14,
                fontWeight = if (health?.diskProblem != null) FontWeight.Bold else null, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(LogLegend)
                Text(appString(R.string.ui_registro_se_actualiza_solo), color = White70, fontSize = Text14, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (!loaded) {
                Text(appString(R.string.ui_leyendo_el_registro), color = White70, fontSize = Text14, modifier = Modifier.padding(top = 16.dp))
            } else if (lines.isEmpty()) {
                Text(appString(R.string.ui_registro_vacio_sube_nivel), color = White70, fontSize = Text14,
                    modifier = Modifier.padding(top = 16.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).padding(vertical = 4.dp)) {
                    HeaderCell("Hora", Modifier.width(TimeWidth))
                    HeaderCell("Gravedad", Modifier.width(LevelWidth))
                    HeaderCell("Parte de la app", Modifier.width(AreaWidth))
                    HeaderCell("Qué pasó", Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White))
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(lines, contentType = { _, line -> line.kind }) { index, line ->
                        var focused by remember { mutableStateOf(false) }
                        // Cada línea toma el foco para que el mando pueda desplazar la lista.
                        Column(
                            Modifier.fillMaxWidth()
                                .then(if (index == lines.lastIndex) Modifier.focusRequester(last) else Modifier)
                                .onFocusChanged { focused = it.isFocused; if (it.isFocused) selected = line }
                                .focusable()
                                .background(if (focused) Color(0xFF3A3A3A) else if (index % 2 == 0) StripedRow else Color.Transparent)
                                .then(if (focused) Modifier.border(2.dp, Color.White) else Modifier)
                        ) {
                            LogRow(line)
                            Box(Modifier.fillMaxWidth().height(1.dp).background(RowDivider))
                        }
                    }
                }
                // Panel del pie: el texto completo de la fila enfocada.
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White))
                Text(selected?.full?.takeIf { it.isNotEmpty() } ?: "Mueve el foco sobre una anotación para leerla entera aquí.",
                    // Un solo color fijo: antes cambiaba (rojo en los errores, gris en el resto) al mover el foco y molestaba.
                    color = Color.White, fontSize = Text14, maxLines = 9, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().height(FooterHeight).padding(horizontal = 6.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun HeaderCell(title: String, modifier: Modifier) {
    Text(title, color = Color.White, fontSize = Text14, fontWeight = FontWeight.Bold, modifier = modifier.padding(horizontal = 6.dp))
}

/** Una fila de la tabla según su tipo: cabecera de día, anotación en cuatro columnas o rastro técnico bajo la columna del mensaje. */
@Composable
private fun LogRow(line: LogLine) {
    when (line.kind) {
        LogLine.Kind.DAY -> Text(line.message, color = Color.White, fontSize = Text16, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp))
        LogLine.Kind.ENTRY -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            val tint = line.tint ?: Color.White
            Cell(line.time, tint, Modifier.width(TimeWidth))
            Cell(line.level, tint, Modifier.width(LevelWidth), bold = line.tint == ErrorTint)
            Cell(line.area, tint, Modifier.width(AreaWidth), maxLines = 1)
            Cell(line.message, tint, Modifier.weight(1f), maxLines = 1)
        }
        LogLine.Kind.DETAIL -> Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
            Box(Modifier.width(TimeWidth + LevelWidth + AreaWidth))
            Cell(line.message, line.tint ?: DetailTint, Modifier.weight(1f), maxLines = 2)
        }
    }
}

@Composable
private fun Cell(text: String, tint: Color, modifier: Modifier, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    Text(text, color = tint, fontSize = Text14, fontWeight = if (bold) FontWeight.Bold else null, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, modifier = modifier.padding(horizontal = 6.dp))
}

/**
 * Una fila del visor: su tipo, su color (null = cabecera de día) y sus celdas. [text] la resume en una sola línea
 * («hora · gravedad · parte — mensaje»), que es lo que se comprueba en las pruebas.
 */
internal class LogLine(
    val kind: Kind,
    val tint: Color?,
    val time: String = "",
    val level: String = "",
    val area: String = "",
    val message: String = "",
    /** El texto completo de la anotación (mensaje entero, origen, solución y rastro) para el panel del pie; vacío en las cabeceras de día. */
    val full: String = ""
) {
    enum class Kind { DAY, ENTRY, DETAIL }

    val text: String
        get() = when (kind) {
            Kind.DAY -> message
            Kind.ENTRY -> "$time · $level · $area — $message"
            Kind.DETAIL -> "      $message"
        }
}

private val ErrorTint = FailureRed
private val DetailTint = Color(0xFF9E9E9E)

/** Las anotaciones en filas legibles: una cabecera por día, una fila por anotación y, bajo un error, la primera línea técnica. */
internal fun entryLines(entries: List<DebugLog.Entry>): List<LogLine> {
    val out = ArrayList<LogLine>()
    var day = ""
    for (entry in entries) {
        if (entry.date != day) { day = entry.date; out += LogLine(LogLine.Kind.DAY, null, message = day) }
        val full = fullText(entry)
        val level = when (entry.level) { DebugLog.ERROR -> "ERROR"; DebugLog.DETAIL -> "detalle"; else -> "info" }
        out += LogLine(LogLine.Kind.ENTRY, if (entry.level == DebugLog.ERROR) ErrorTint else White70,
            time = entry.time, level = level, area = entry.area, message = entry.message.lineSequence().first() + repeatedNote(entry), full = full)
        entry.details.firstOrNull()?.let { first ->
            val more = entry.details.size - 1
            out += LogLine(LogLine.Kind.DETAIL, DetailTint, message = first + if (more > 0) " (+$more líneas técnicas; se leen enteras en el panel de abajo)" else "", full = full)
        }
    }
    return out
}

/** Líneas técnicas que como mucho cabe leer en el panel del pie (un rastro de error puede tener cientos). */
private const val FULL_DETAIL_LINES = 40

/** Lo que dice la anotación entera: cuándo, de quién, el mensaje completo, el número del error con su origen y lo técnico. */
internal fun fullText(entry: DebugLog.Entry): String = buildString {
    append(entry.date).append(' ').append(entry.time).append(" · ").append(entry.area).append(NL)
    append(entry.message)
    val info = listOfNotNull(entry.code, entry.cause, entry.origin?.let { "origen: $it" }, entry.context?.takeIf { it.isNotBlank() }).joinToString(" · ")
    if (info.isNotEmpty()) append(NL).append(info)
    entry.details.take(FULL_DETAIL_LINES).forEach { append(NL).append(it) }
    if (entry.details.size > FULL_DETAIL_LINES) append(NL).append("… (+${entry.details.size - FULL_DETAIL_LINES} líneas más)")
    if (entry.repeated > 0) append(NL).append("Se repitió ${entry.repeated} ${if (entry.repeated == 1) "vez más" else "veces más"} seguidas en el último minuto y no se anotó para no llenar el registro.")
    DebugLog.areaHelp(entry.area)?.let { append(NL).append(NL).append("Qué es «").append(entry.area).append("»: ").append(it) }
}

/** « (×3)»: cuántas veces ocurrió esta anotación contando las que se callaron por repetidas; vacío si ocurrió una sola vez. */
private fun repeatedNote(entry: DebugLog.Entry): String = if (entry.repeated > 0) " (×${entry.repeated + 1})" else ""

private const val NL = "\n"
