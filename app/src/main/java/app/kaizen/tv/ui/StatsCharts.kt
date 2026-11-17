package app.kaizen.tv.ui

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Colores de las series de un gráfico (anillo, leyendas): distinguibles sobre el fondo oscuro. */
internal val ChartPalette = listOf(
    Color(0xFF2675D8), Color(0xFF4FC3F7), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFFBA68C8), Color(0xFFE57373), Color(0xFF90A4AE)
)

/**
 * Radar (telaraña): un eje por entrada, con su nombre alrededor; el polígono llena cada eje según su valor (0 a 1). Sirve para ver de un vistazo la forma de tus gustos
 * (qué géneros pesan más). Con menos de tres ejes no se dibuja: no hace forma.
 */
@Composable
internal fun RadarChart(axes: List<Pair<String, Float>>, modifier: Modifier = Modifier, color: Color = Accent) {
    if (axes.size < 3) return
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = Text14, textAlign = TextAlign.Center)
    Canvas(modifier.fillMaxWidth().height(RADAR_HEIGHT)) {
        val n = axes.size
        val centre = Offset(size.width / 2, size.height / 2)
        val radius = minOf(size.width / 2 - 110.dp.toPx(), size.height / 2 - 34.dp.toPx()).coerceAtLeast(0f)
        fun point(index: Int, fraction: Float): Offset {
            val angle = -PI / 2 + 2 * PI * index / n
            return Offset(centre.x + radius * fraction * cos(angle).toFloat(), centre.y + radius * fraction * sin(angle).toFloat())
        }
        // Anillos y ejes.
        for (ring in 1..RADAR_RINGS) {
            val path = Path().apply {
                for (i in 0 until n) point(i, ring / RADAR_RINGS.toFloat()).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, White20, style = Stroke(width = 1.dp.toPx()))
        }
        for (i in 0 until n) drawLine(White20, centre, point(i, 1f), strokeWidth = 1.dp.toPx())
        // El polígono de valores.
        val values = Path().apply {
            axes.forEachIndexed { i, (_, value) -> point(i, value.coerceIn(0.04f, 1f)).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            close()
        }
        drawPath(values, color.copy(alpha = 0.35f))
        drawPath(values, color, style = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round))
        axes.forEachIndexed { i, (_, value) -> drawCircle(color, 4.dp.toPx(), point(i, value.coerceIn(0.04f, 1f))) }
        // Los nombres, fuera del último anillo.
        axes.forEachIndexed { i, (label, _) ->
            val layout = measurer.measure(label, labelStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = androidx.compose.ui.unit.Constraints(maxWidth = 150.dp.roundToPx()))
            val anchor = point(i, 1f)
            val angle = -PI / 2 + 2 * PI * i / n
            val gap = 14.dp.toPx()
            // Se aparta del centro en la dirección del eje; el texto se centra a los lados y se pega arriba o abajo.
            val x = anchor.x + cos(angle).toFloat() * gap - layout.size.width / 2f - cos(angle).toFloat() * layout.size.width / 2f
            val y = anchor.y + sin(angle).toFloat() * gap - layout.size.height / 2f + sin(angle).toFloat() * layout.size.height / 2f
            drawText(layout, topLeft = Offset(x, y))
        }
    }
}

/**
 * Columnas verticales: una por entrada (etiqueta, valor), con la altura proporcional a su valor respecto al mayor. [valueLabel] es el texto sobre cada columna;
 * [highlight] marca una (la peor, la actual…) con otro color.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun ColumnChart(
    items: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    color: Color = Accent,
    highlight: Int? = null,
    highlightColor: Color = FailureRed,
    chartHeight: Dp = 130.dp,
    valueLabel: (Float) -> String = { it.toInt().toString() }
) {
    val max = items.maxOfOrNull { it.second }?.takeIf { it > 0f } ?: 1f
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        items.forEachIndexed { index, (label, value) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (value > 0f) valueLabel(value) else "", color = White80, fontSize = Text12, maxLines = 1)
                Box(Modifier.height(chartHeight), contentAlignment = Alignment.BottomCenter) {
                    // Sin valor no se pinta columna (antes quedaba una raya de 1 dp que parecía un dato).
                    if (value > 0f) Box(
                        Modifier.fillMaxWidth(0.62f).height((chartHeight * (value / max)).coerceAtLeast(3.dp))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(if (index == highlight) highlightColor else color)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(label, color = White70, fontSize = Text12, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Área: la evolución de un valor a lo largo del tiempo (un punto por entrada), con la línea y un degradado debajo. [first] y [last] son las etiquetas de los extremos
 * del eje (p. ej. las fechas); [max] fija el techo (si no, el mayor valor).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AreaChart(values: List<Float>, first: String, last: String, modifier: Modifier = Modifier, color: Color = FailureRed, max: Float? = null) {
    if (values.size < 2) return
    val top = (max ?: values.max()).takeIf { it > 0f } ?: 1f
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val step = size.width / (values.size - 1)
            fun point(i: Int) = Offset(i * step, size.height - (values[i] / top).coerceIn(0f, 1f) * (size.height - 6.dp.toPx()) - 2.dp.toPx())
            for (line in 1..3) drawLine(White10, Offset(0f, size.height * line / 4), Offset(size.width, size.height * line / 4), strokeWidth = 1.dp.toPx())
            val line = Path().apply { values.indices.forEach { i -> point(i).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
            val fill = Path().apply {
                addPath(line)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent)))
            drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val worst = values.indices.maxByOrNull { values[it] }
            if (worst != null && values[worst] > 0f) drawCircle(color, 4.dp.toPx(), point(worst))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(first, color = White70, fontSize = Text12, modifier = Modifier.weight(1f))
            Text(last, color = White70, fontSize = Text12)
        }
    }
}

/** Anillo: reparte un total entre segmentos (etiqueta, valor), con la leyenda al lado («etiqueta · porcentaje»). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DonutChart(segments: List<Triple<String, Float, Color>>, modifier: Modifier = Modifier, ringSize: Dp = DONUT_SIZE) {
    val shown = segments.filter { it.second > 0f }
    val total = shown.sumOf { it.second.toDouble() }.toFloat().takeIf { it > 0f } ?: return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(ringSize)) {
            val stroke = ringSize.toPx() * 0.17f
            val inset = stroke / 2
            var start = -90f
            shown.forEach { (_, value, color) ->
                val sweep = 360f * value / total
                // Un pequeño hueco entre segmentos para que se distingan.
                drawArc(color, start, (sweep - if (shown.size > 1) 2f else 0f).coerceAtLeast(0.5f), false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                start += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { (label, value, color) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Text("$label · ${(value / total * 100).let { if (it < 1f) "<1" else it.toInt().toString() }} %", color = Color.White, fontSize = Text15, maxLines = 1)
                }
            }
        }
    }
}

private val RADAR_HEIGHT = 300.dp
private const val RADAR_RINGS = 4
private val DONUT_SIZE = 130.dp
