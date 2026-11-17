@file:OptIn(ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui

import app.kaizen.tv.ui.theme.*

import app.kaizen.commons.logError
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.kaizen.R
import app.kaizen.bypass.BypassStatus
import app.kaizen.bypass.launchBypass
import app.kaizen.commons.findActivity
import app.kaizen.commons.HostGate
import app.kaizen.tv.settings.setSourceActive
import app.kaizen.commons.KaizenSettings
import app.kaizen.source.AnimeSource
import app.kaizen.source.AnimeSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Estado de las fuentes marcadas: todas responden, alguna en pausa o todas en pausa. */
enum class SourcesHealth(val color: Color, val label: String) {
    OK(StatusOk, "Todas responden"),
    PARTIAL(StatusWarn, "Alguna con fallos o en pausa"),
    DOWN(StatusDown, "Todas en pausa"),
    /** Aún sin medir (solo en la tarjeta de una fuente): círculo vacío, ni bueno ni malo. */
    CHECKING(Color.Gray, "Midiendo");

    companion object {
        fun current(): SourcesHealth {
            val selected = AnimeSources.selected
            val down = selected.count { HostGate.isDown(it.host) }
            val failing = selected.count { !HostGate.isDown(it.host) && HostGate.recentFailure(it.host) != null }
            return when {
                down == 0 && failing == 0 -> OK
                down == selected.size -> DOWN
                else -> PARTIAL
            }
        }
    }
}

/** Piloto de estado: color y forma (círculo, triángulo, cuadrado) de [health], para no depender solo del color. */
@Composable
internal fun Led(health: SourcesHealth, size: androidx.compose.ui.unit.Dp = 24.dp, description: String? = null) {
    Canvas(Modifier.size(size).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        val radius = this.size.minDimension / 2.5f
        val middle = center
        when (health) {
            SourcesHealth.OK -> drawCircle(health.color, radius = radius)
            SourcesHealth.PARTIAL -> drawPath(Path().apply {
                moveTo(middle.x, middle.y - radius)
                lineTo(middle.x + radius, middle.y + radius)
                lineTo(middle.x - radius, middle.y + radius)
                close()
            }, health.color)
            SourcesHealth.CHECKING -> drawCircle(health.color, radius = radius, style = androidx.compose.ui.graphics.drawscope.Stroke(width = radius / 3f))
            SourcesHealth.DOWN -> drawRect(health.color, topLeft = Offset(middle.x - radius, middle.y - radius), size = Size(radius * 2, radius * 2))
        }
    }
}

/**
 * Logo de [source]: el icono de su propia web (apple-touch-icon o favicon), con el piloto de estado en una esquina.
 * Si no hay icono o no carga, la inicial de su nombre.
 */
@Composable
internal fun SourceLogo(source: AnimeSource, health: SourcesHealth, size: Dp = 56.dp, disabled: Boolean = false) {
    // Los iconos que declara la propia portada (se piden una vez y se guardan); mientras no hay ninguno, las rutas de siempre.
    var declared by remember(source.host) { mutableStateOf(app.kaizen.source.SourceIcons.saved(source.host)) }
    LaunchedEffect(source.host) { declared = app.kaizen.source.SourceIcons.refresh(source.host) }
    val urls = remember(source.host, declared) { declared.ifEmpty { listOf("https://${source.host}/apple-touch-icon.png", "https://${source.host}/favicon.ico") } }
    LogoBadge(urls, health, size, disabled = disabled) {
        Text(source.name.take(1).uppercase(), color = if (disabled) Color.Gray else Color.White, fontSize = (size.value / 2).sp, fontWeight = FontWeight.Bold)
    }
}

/** Logo de Cloudflare para la tarjeta del bypass: el de su web y, si no carga, una nube naranja dibujada. */
@Composable
internal fun CloudflareLogo(health: SourcesHealth, size: Dp = 56.dp) {
    val urls = remember { listOf("https://www.cloudflare.com/apple-touch-icon.png", "https://www.cloudflare.com/favicon.ico") }
    LogoBadge(urls, health, size) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val orange = CloudflareOrange
            val w = this.size.width
            val h = this.size.height
            drawCircle(orange, radius = h * 0.26f, center = Offset(w * 0.32f, h * 0.58f))
            drawCircle(orange, radius = h * 0.34f, center = Offset(w * 0.58f, h * 0.44f))
            drawRoundRect(orange, topLeft = Offset(w * 0.04f, h * 0.52f), size = Size(w * 0.92f, h * 0.3f), cornerRadius = CornerRadius(h * 0.15f))
        }
    }
}

/** Cuadro con la imagen de la primera de [urls] que cargue, o [fallback] si ninguna, y el piloto de [health] en una esquina. */
@Composable
/** Con [disabled] el logo va en grises y en la esquina, en vez del piloto, una cruz. */
private fun LogoBadge(urls: List<String>, health: SourcesHealth, size: Dp, disabled: Boolean = false, fallback: @Composable () -> Unit) {
    var attempt by remember(urls) { mutableIntStateOf(0) }
    var failed by remember(urls) { mutableStateOf(false) }
    Box(Modifier.size(size)) {
        Box(Modifier.matchParentSize().clip(ShapeLg).background(SurfaceMuted), contentAlignment = Alignment.Center) {
            if (failed) fallback()
            else AsyncImage(
                model = urls[attempt],
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = if (disabled) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                alpha = if (disabled) 0.5f else 1f,
                onError = { if (attempt < urls.lastIndex) attempt++ else failed = true },
                modifier = Modifier.matchParentSize().padding(4.dp)
            )
        }
        if (disabled) Box(Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("✖", color = Color.LightGray, fontSize = 12.sp)
        }
        else Box(Modifier.align(Alignment.BottomEnd)) { Led(health, 22.dp) }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * «Fuentes» del menú lateral: estado de cada fuente marcada. Una fuente en pausa (no responde) no se usa, como si
 * no estuviera marcada, hasta que acaba la pausa (2, 5, 10 min y luego 1 h si sigue sin responder) o se comprueba
 * aquí y responde.
 */
@Composable
internal fun SourcesPage() {
    // Se recompone al cambiar las fuentes marcadas o las pausas.
    val changes by remember { KaizenSettings.sourceIdsFlow.combine(HostGate.down) { ids, down -> ids to down }.combine(HostGate.failureVersion) { pair, failures -> pair to failures } }
        .collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    /** Última comprobación manual por fuente. */
    val lastCheck = remember { mutableStateMapOf<String, HostGate.CheckResult>() }
    var checking by remember { mutableStateOf(false) }
    val bypass by BypassStatus.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Al abrir se mide cada fuente sin tocar pausas (probe): así se ven los tiempos sin pulsar nada.
    // Fuentes cuya IP está bloqueada ahora por un partido de LaLiga (hayahora.futbol); vacío si no hay bloqueo o no se pudo saber.
    var laLigaHosts by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { laLigaHosts = runCatching { app.kaizen.commons.LaLigaBlock.blockedHosts(AnimeSources.selected.map { it.host }) }.getOrDefault(emptySet()) }
    // Mientras dura esa primera medición las tarjetas no ponen texto, solo el círculo vacío (antes ponían «Activa» y a los segundos cambiaban).
    var probing by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try {
            val results = withContext(Dispatchers.IO) {
                coroutineScope { AnimeSources.selected.map { source -> async { source to HostGate.probe("https://${source.host}/") } }.awaitAll() }
            }
            results.forEach { (source, result) -> if (lastCheck[source.id] == null) lastCheck[source.id] = result }
        } finally {
            probing = false
        }
    }

    /** Comprueba ahora las fuentes (aunque estén en pausa): las que responden vuelven a usarse. */
    fun check(sources: List<AnimeSource>) {
        if (checking) return
        checking = true
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    coroutineScope { sources.map { source -> async { source to HostGate.check("https://${source.host}/") } }.awaitAll() }
                }
                results.forEach { (source, result) -> lastCheck[source.id] = result }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError(e)
            } finally {
                checking = false
            }
        }
    }

    CardGrid {
        // Leer el valor aquí suscribe la rejilla: se rehace al cambiar las fuentes marcadas o las pausas.
        changes
        val selected = AnimeSources.selected
        // Todas las fuentes: pulsar una la activa o la desactiva (como en los botones del reproductor); siempre queda al menos una.
        val all = AnimeSources.all
        items(all.size, key = { all[it].id }, contentType = { "source" }) { index ->
            val source = all[index]
            val active = source in selected
            val pausedUntil = HostGate.downUntil(source.host)
            val result = lastCheck[source.id]
            val failure = HostGate.recentFailure(source.host)
            val state = sourceState(active, pausedUntil, result, failure, probing, { timeFormat.format(Date(it)) }, source.host in laLigaHosts)
            IconTextCard(if (state.text.isEmpty()) source.name else "${source.name}\n${state.text}", { setSourceActive(source.id, !active) },
                background = if (active) ActiveCard else InactiveCard, contentColor = if (active) Color.White else InactiveContent) {
                SourceLogo(source, size = 40.dp, disabled = !active, health = state.health)
            }
        }
        item {
            val (health, text) = bypassSummary(bypass)
            IconTextCard("Bypass Cloudflare\n$text", { context.findActivity()?.launchBypass(app.kaizen.tv.ui.TVMain.BYPASS_REQUEST, force = true) }) {
                CloudflareLogo(health, size = 40.dp)
            }
        }
        item {
            // Cuántas de las fuentes activas responden bien, con lo ya medido.
            val summary = sourcesSummary(selected.map { source ->
                sourceState(true, HostGate.downUntil(source.host), lastCheck[source.id], HostGate.recentFailure(source.host), probing, { timeFormat.format(Date(it)) }, source.host in laLigaHosts)
            })
            IconTextCard((if (checking) "Comprobando…" else "Comprobar todas") + if (summary.isNotEmpty()) "\n$summary" else "", { check(selected) }) { TintedIcon(R.drawable.ic_sync) }
        }
    }
}

/** Color y texto del estado del bypass de Cloudflare para su tarjeta de «Fuentes». */
internal fun bypassSummary(b: BypassStatus.Snapshot, now: Long = System.currentTimeMillis()): Pair<SourcesHealth, String> {
    val at = if (b.at > 0) " · ${timeFormat.format(Date(b.at))}" else ""
    val took = if (b.millis > 0) " · ${b.millis} ms" else ""
    if (b.pausedUntil > now) return SourcesHealth.DOWN to "En pausa hasta ${timeFormat.format(Date(b.pausedUntil))}"
    return when (b.state) {
        BypassStatus.State.UNKNOWN -> SourcesHealth.OK to "Sin lanzar · pulsa para probar"
        BypassStatus.State.RUNNING -> SourcesHealth.PARTIAL to "En marcha…"
        BypassStatus.State.OK -> SourcesHealth.OK to "Activo$at$took"
        BypassStatus.State.NOT_NEEDED -> SourcesHealth.OK to "No hace falta$at$took"
        BypassStatus.State.FAILED -> SourcesHealth.DOWN to "Falló$at$took"
        BypassStatus.State.UNREACHABLE -> SourcesHealth.DOWN to "Cloudflare no responde$at"
    }
}
