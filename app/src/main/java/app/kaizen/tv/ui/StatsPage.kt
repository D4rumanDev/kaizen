@file:OptIn(ExperimentalLayoutApi::class)

package app.kaizen.tv.ui

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.commons.KaizenProgress
import app.kaizen.commons.runCatchingCoop
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaizen.domain.ConnectionStats
import app.kaizen.domain.Library
import app.kaizen.domain.SeriesStatus
import app.kaizen.domain.SeriesStatuses
import app.kaizen.domain.TasteProfile
import app.kaizen.domain.UserStats
import app.kaizen.domain.WatchInsights
import app.kaizen.domain.WatchedSeries
import app.kaizen.home.TasteRepository
import app.kaizen.search.SearchGenres
import app.kaizen.source.AnilistFicha
import app.kaizen.source.seriesIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

private val TileColor = SurfaceCard
private val BarTrack = White20

/**
 * «Estadísticas» (Configuración), en dos pestañas: **Tu actividad** (lo que ves: tus estilos, estudios, épocas, formatos y ritmo) y **Conexión** (cómo van las fuentes
 * y los servidores: fallos por día y por día de la semana, y cuáles fallan más). Los estilos tardan la primera vez: se piden a las fichas de tus series.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun StatsPage() {
    var tab by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    // Se calcula una vez y no al cambiar de pestaña (que se cambia al enfocar): recorre todo el historial y consulta una ficha por serie.
    val stats = rememberLoadable { loadStats() }
    val insights = rememberLoadable { runCatchingCoop { loadInsights() }.getOrNull() ?: WatchInsights.compute(emptyList()) }.valueOrNull
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = ContentPaddingHorizontal, end = ContentPaddingHorizontal, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Tu actividad", "Conexión").forEachIndexed { index, title -> StatsTab(title, selected = tab == index, if (index == 0) Modifier.focusRequester(first) else Modifier) { tab = index } }
        }
        if (tab == 0) ActivityTab(stats, insights) else ConnectionTab()
    }
}

/** Una pestaña: al enfocarla (o pulsarla) se muestra; la elegida va en el color de acento. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsTab(title: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        title, color = Color.White, fontSize = Text16, fontWeight = FontWeight.Medium,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onSelect() }
            .clip(ShapePill)
            .background(if (selected) Accent else White10)
            .then(if (focused) Modifier.border(BorderStroke(2.dp, White60), ShapePill) else Modifier)
            .clickable { onSelect() }
            .padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

// ------------------------------------------------------------------------------------------------ Tu actividad

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActivityTab(stats: Loadable<UserStats>, insights: WatchInsights?) {
    // null mientras se calcula; si falla, perfil vacío.
    val taste = rememberLoadable { runCatchingCoop { TasteRepository.snapshot().profile }.getOrNull() ?: TasteProfile.EMPTY }.valueOrNull
    val current = when (stats) {
        Loadable.Loading -> return StatsLoading()
        is Loadable.Failed -> return StatsFailed(stats.error)
        is Loadable.Ready -> stats.value
    }
    // Cuántas series hay en cada estado (las unidas de varias fuentes cuentan una vez); «Ninguna» no se cuenta.
    val statusMap by SeriesStatuses.all.collectAsStateWithLifecycle()
    val ledger by app.kaizen.source.SeriesLedger.version.collectAsStateWithLifecycle()
    val statuses = remember(statusMap, ledger) {
        SeriesStatuses.distinctSeries(statusMap.values).groupingBy { SeriesStatus.of(it.status) }.eachCount()
            .filterKeys { it != SeriesStatus.NONE }.toList().sortedByDescending { it.second }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ContentPaddingHorizontal, end = ContentPaddingHorizontal, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Block("Resumen") { Summary(current, insights) } }
        item {
            Block("Tus estilos") {
                val profile = taste
                when {
                    profile == null -> Note("Calculando tus estilos a partir de tus favoritos y lo que has visto…")
                    profile.isEmpty -> Note("Aún no hay datos: marca favoritos o mira algún capítulo.")
                    else -> {
                        val top = profile.top(RADAR_GENRES)
                        val max = top.maxOf { it.series }.coerceAtLeast(1)
                        // El radar a la izquierda y, a la derecha, los mismos géneros con su número de series (el radar solo enseña la forma).
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (top.size >= 3) RadarChart(top.map { SearchGenres.nameOf(it.slug) to it.series / max.toFloat() }, Modifier.weight(1.1f))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                top.forEach { Bar(SearchGenres.nameOf(it.slug), it.series / max.toFloat(), "${it.series}", valueWidth = 40.dp, labelWidth = 130.dp) }
                                Note("Series de cada género entre tus favoritas y las que más ves.")
                            }
                        }
                    }
                }
            }
        }
        item {
            Block("Estudios que más ves") {
                val data = insights
                when {
                    data == null -> Note("Calculando…")
                    data.studios.isEmpty() -> Note("Aún no se conoce el estudio de tus series: sale de su ficha de AniList, que se guarda al emparejarla.")
                    else -> {
                        val max = data.studios.maxOf { it.chapters }.coerceAtLeast(1)
                        data.studios.forEach { Bar(it.name, it.chapters / max.toFloat(), "${it.chapters} cap. · ${it.series} ${if (it.series == 1) "serie" else "series"}", valueWidth = 170.dp) }
                        Note("Conocido en ${data.withStudio} de ${data.total} series vistas.")
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Block("Formato", Modifier.weight(1f)) {
                    val formats = insights?.formats.orEmpty().filter { it.first != "Sin datos" }
                    if (formats.isNotEmpty()) DonutChart(formats.mapIndexed { i, (name, chapters) -> Triple(name, chapters.toFloat(), ChartPalette[i % ChartPalette.size]) }, ringSize = BIG_DONUT)
                    else Note(if (insights == null) "Calculando…" else "Aún no se conoce el formato de tus series.")
                }
                Block("Estado de tus series", Modifier.weight(1f)) {
                    if (statuses.isNotEmpty()) DonutChart(statuses.map { (status, count) -> Triple(status.label, count.toFloat(), Color(status.color)) }, ringSize = BIG_DONUT)
                    else Note("Aún no has puesto estado a ninguna serie.")
                }
            }
        }
    }
}

/** Cuatro cifras: lo esencial; el resto (últimos 7 y 30 días, fecha del primer capítulo…) no aporta. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Summary(stats: UserStats, insights: WatchInsights?) {
    // Las cinco en una fila, con el mismo ancho y la misma altura (antes, a 190 dp fijos la última caía a una segunda fila).
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile(stats.chapters.toString(), "capítulos vistos", Modifier.weight(1f))
        Tile(stats.series.toString(), "series vistas", Modifier.weight(1f))
        // Con la duración real de cada serie cuando se conoce (su ficha de AniList); si no, 24 min por capítulo.
        Tile("≈ ${insights?.hours ?: stats.estimatedHours} h", "viendo", Modifier.weight(1f))
        Tile(stats.currentStreak.toString(), if (stats.currentStreak == 1) "día seguido" else "días seguidos", Modifier.weight(1f))
        Tile(stats.bestStreak.toString(), "mejor racha (días)", Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------------------------------------ Conexión

private class ConnectionReport(val rows: List<ConnectionStats.Row>, val errors: List<ConnectionStats.ErrorRow>, val today: Long)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConnectionTab() {
    val report = rememberLoadable { ConnectionReport(ConnectionStats.rows(), ConnectionStats.errorRows(), ConnectionStats.dayOf(System.currentTimeMillis())) }
    val data = when (report) {
        Loadable.Loading -> return StatsLoading()
        is Loadable.Failed -> return StatsFailed(report.error)
        is Loadable.Ready -> report.value
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ContentPaddingHorizontal, end = ContentPaddingHorizontal, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Block("Cómo se cuenta") {
                Note("Cada vez que la app pide algo a una fuente, o abre un servidor de vídeo, se anota si funcionó o falló, con su día. Los porcentajes son fallos sobre el total de intentos, " +
                    "y los que tienen muy pocos intentos van al final. Se guardan ${ConnectionStats.keepDays} días. Un servidor que falla se cuenta contra el servidor (todas las fuentes juntas) y, " +
                    "aparte, contra la fuente que lo ofreció (en «Enlaces de cada fuente»); no contra la web de la fuente, que se mide por sus propias peticiones.")
            }
        }
        connectionSection(data, ConnectionStats.Kind.SOURCE, "Fuentes", "las webs de capítulos y fichas")
        connectionSection(data, ConnectionStats.Kind.SERVER, "Servidores", "los alojadores del vídeo")
        connectionSection(data, ConnectionStats.Kind.LINK, "Enlaces de cada fuente", "los servidores que ofrece cada fuente")
    }
}

/** Una sección de la pestaña Conexión: la evolución de los últimos días (Configuración › Servidores y red), los fallos por día de la semana y quién falla más. */
@OptIn(ExperimentalTvMaterial3Api::class)
private fun androidx.compose.foundation.lazy.LazyListScope.connectionSection(report: ConnectionReport, kind: ConnectionStats.Kind, title: String, what: String) {
    val chartDays = app.kaizen.commons.Tuning.chartDays()
    val rows = report.rows.filter { it.kind == kind }
    item {
        Block("$title · últimos $chartDays días") {
            if (rows.isEmpty()) {
                Note("Aún no hay intentos registrados de $what: se cuentan desde esta versión.")
                return@Block
            }
            val days = ConnectionStats.perDay(report.rows, kind, report.today, chartDays)
            val attempts = days.sumOf { it.total }
            val fails = days.sumOf { it.fail }
            Text(
                "$fails ${if (fails == 1) "fallo" else "fallos"} de $attempts intentos" + if (attempts > 0) " (${(fails * 100f / attempts).roundToInt()} %)" else "",
                color = Color.White, fontSize = Text16, fontWeight = FontWeight.Medium
            )
            AreaChart(days.map { it.fail.toFloat() }, dayLabel(days.first().day), "hoy")
            val worst = days.filter { it.fail > 0 }.maxByOrNull { it.fail }
            if (worst != null) Note("El día con más fallos: ${dayLabel(worst.day)} (${worst.fail} de ${worst.total} intentos).")
        }
    }
    item {
        Block("$title · tipos de error") {
            val causes = ConnectionStats.causes(report.errors, kind)
            val total = causes.sumOf { it.second }
            if (causes.isEmpty()) {
                Note("Aún no hay fallos con su tipo registrado: saldrán aquí en cuanto haya alguno.")
            }
            causes.forEach { (cause, count) ->
                Bar("${cause.tag} ${cause.label}", count / total.toFloat(), "$count · ${(count * 100f / total).roundToInt()} %", FailureRed, valueWidth = 110.dp, labelWidth = 250.dp)
            }
        }
    }
    if (rows.isEmpty()) return
    item {
        Block("$title · fallos por día de la semana") {
            val weekdays = ConnectionStats.perWeekday(rows, kind)
            // Al entero que se enseña: un 0,3 % se leería «0 %» con su columna pintada, y ahora no la tiene.
            val rates = weekdays.map { if (it.total > 0) (it.failRate * 100f).roundToInt().toFloat() else 0f }
            val worst = weekdays.indices.filter { weekdays[it].total >= ConnectionStats.MIN_WEEKDAY_SAMPLE && weekdays[it].fail > 0 && rates[it] > 0f }.maxByOrNull { rates[it] }
            ColumnChart(weekdays.mapIndexed { i, day -> day.name.take(3) to rates[i] }, highlight = worst, valueLabel = { "${it.roundToInt()} %" })
            Note(
                if (worst != null) "Se falla más los ${weekdays[worst].name}: ${rates[worst].roundToInt()} % de ${weekdays[worst].total} intentos. Porcentaje de intentos que fallaron cada día de la semana."
                else "Porcentaje de intentos que fallaron cada día de la semana. Aún no hay base para señalar un día."
            )
        }
    }
    item {
        Block("$title · los que más fallan") {
            val ranked = ConnectionStats.ranked(rows, kind).take(RANKED_SHOWN)
            ranked.forEach { total ->
                val color = if (total.total < ConnectionStats.MIN_SAMPLE) FailureRed.copy(alpha = 0.4f) else FailureRed
                val worstDay = total.worstWeekday?.let { " · ${ConnectionStats.WEEKDAYS[it].take(3)}" }.orEmpty()
                Bar(total.name, total.failRate, "${(total.failRate * 100).roundToInt()} % (${total.fail}/${total.total})$worstDay", color, valueWidth = 190.dp)
                ConnectionStats.mainCause(report.errors, kind, total.name)?.let { Text("Causa más frecuente: ${it.label}", color = White70, fontSize = Text13, modifier = Modifier.padding(start = 202.dp)) }
            }
            Note("Barra completa = todos los intentos fallaron. Entre paréntesis: fallos y total de intentos; a la derecha, su peor día de la semana.")
        }
    }
}

/** «12 nov» para el día [day] (días desde 1970 en la zona del usuario: se formatea en UTC para no volver a desplazarlo). */
private fun dayLabel(day: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(day * 86_400_000L))

// ------------------------------------------------------------------------------------------------ Piezas

/** Mientras se calculan: huecos con la forma de las losetas y un texto. */
/** La lectura de las estadísticas falló: se dice y se anota (antes la excepción tiraba la pantalla). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsFailed(error: Throwable) {
    LaunchedEffect(error) { app.kaizen.commons.logError(error) }
    Column(Modifier.fillMaxSize().padding(start = ContentPaddingHorizontal, end = ContentPaddingHorizontal, top = 8.dp)) {
        Text("No se pudieron calcular las estadísticas (mira el registro de depuración).", color = White80, fontSize = Text15)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsLoading() {
    Column(Modifier.fillMaxSize().padding(start = ContentPaddingHorizontal, end = ContentPaddingHorizontal, top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Calculando tus estadísticas…", color = White80, fontSize = Text15)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(STATS_PLACEHOLDERS) { Box(Modifier.width(190.dp).height(72.dp).clip(ShapeMd).background(TileColor.copy(alpha = 0.5f))) }
        }
    }
}

private const val STATS_PLACEHOLDERS = 4

private fun loadStats(): UserStats {
    return UserStats.compute(
        history = Library.historyNow(),
        // Una serie unida de varias fuentes cuenta una vez, como en la lista de Favoritos.
        favorites = Library.favoritesNow().distinctBy { app.kaizen.source.seriesKeyOf(it.source, it.slug) }.size,
        inProgress = KaizenProgress.inProgressCount(),
        now = System.currentTimeMillis()
    )
}

/** Estudio, año, formato y duración de cada serie vista, de su ficha de AniList guardada (solo lectura local, sin red). */
private suspend fun loadInsights(): WatchInsights = withContext(Dispatchers.IO) {
    val series = Library.historyNow().groupBy { seriesIdentity(it.name, it.source, it.slug) }.map { (_, chapters) ->
        val latest = chapters.maxBy { it.date }
        val ficha = AnilistFicha.of(latest.source, latest.slug)
        WatchedSeries(chapters.size, ficha?.estudio, ficha?.anio, ficha?.formato, ficha?.duracion)
    }
    WatchInsights.compute(series)
}

/** Bloque enfocable (así el mando puede bajar por la página) con su título. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Block(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(BorderStroke(2.dp, White60), RoundedCornerShape(6.dp)) else Modifier)
            .clip(RoundedCornerShape(6.dp))
            .focusable()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = Color.White, fontSize = Text18, fontWeight = FontWeight.Bold)
        content()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Note(text: String) = Text(text, color = White80, fontSize = Text15)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Tile(value: String, label: String, modifier: Modifier = Modifier) {
    // Todas con la misma altura (un texto largo en dos líneas hacía una más alta que las demás): el texto va en una sola línea.
    Column(modifier.height(TILE_HEIGHT).clip(ShapeMd).background(TileColor).padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
        Text(value, color = Color.White, fontSize = Text24, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = White70, fontSize = Text15, maxLines = 1)
    }
}

/** Una barra llena en [fraction] (0 a 1) con su texto a la derecha. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Bar(label: String, fraction: Float, valueText: String, color: Color = Accent, valueWidth: Dp = 90.dp, labelWidth: Dp = 190.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = Color.White, fontSize = Text15, maxLines = 1, modifier = Modifier.width(labelWidth))
        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(BarTrack)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(14.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
        Text(valueText, color = White80, fontSize = Text14, modifier = Modifier.width(valueWidth))
    }
}

private const val RADAR_GENRES = 6
private val TILE_HEIGHT = 80.dp
private val BIG_DONUT = 190.dp

private const val RANKED_SHOWN = 8
