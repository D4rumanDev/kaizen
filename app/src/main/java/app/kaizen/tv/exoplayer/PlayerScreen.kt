@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)

package app.kaizen.tv.exoplayer

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import app.kaizen.tv.ui.Clock
import app.kaizen.tv.ui.KaizenDialog
import app.kaizen.tv.ui.rememberAutoFocus
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.withFrameNanos
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import app.kaizen.tv.ui.Motion
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import app.kaizen.source.AnimeSources
import app.kaizen.source.coverCandidates
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.rememberLazyListState
import app.kaizen.tv.ui.Cover
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.commons.KaizenProgress
import app.kaizen.commons.KaizenSettings
import app.kaizen.tv.settings.SettingsPanel
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import app.kaizen.commons.appString
import kotlin.time.Duration.Companion.milliseconds

/**
 * Kaizen: pantalla del reproductor. El vídeo va en un [PlayerView] sin controles propios y encima, en Compose:
 * los controles (barra de tiempo, transporte y botones opcionales), los avisos, la ficha lateral, el siguiente capítulo
 * y los ajustes de vídeo. Todo el estado viene de [PlaybackController].
 */
@Composable
fun PlayerScreen(controller: PlaybackController) {
    // La raíz recoge las teclas aunque no haya nada enfocado (controles ocultos): es la única entrada de teclas.
    val root = remember { FocusRequester() }
    var rootHasFocus by remember { mutableStateOf(false) }
    // La raíz misma (y no uno de sus botones) tiene el foco.
    var rootIsFocused by remember { mutableStateOf(false) }
    // Al cerrarse los controles o un panel, el foco desaparece con ellos: vuelve a la raíz.
    // (La clave es overlayOpen y no overlay: la cuenta atrás de «Siguiente capítulo» cambia overlay cada segundo y no debe quitarle el foco.)
    LaunchedEffect(controller.controlsVisible, controller.overlayOpen) {
        if (!rootHasFocus || (!controller.controlsVisible && !controller.overlayOpen)) root.requestFocus()
    }
    Box(Modifier.fillMaxSize().background(Color.Black)
        .focusRequester(root)
        .onFocusChanged { rootHasFocus = it.hasFocus; rootIsFocused = it.isFocused }
        .onPreviewKeyEvent { event ->
            // Con los controles a la vista pero sin ningún botón enfocado, OK (pausa/reanuda) o arriba/abajo llevan el foco a «reproducir».
            val recover = rootIsFocused && controller.controlsVisible && !controller.overlayOpen && event.type == KeyEventType.KeyDown &&
                (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.DirectionUp || event.key == Key.DirectionDown) &&
                event.nativeKeyEvent.repeatCount == 0
            if (recover) { controller.recoverFocus(ok = event.key == Key.DirectionCenter || event.key == Key.Enter); true }
            else controller.handleKey(event.nativeKeyEvent)
        }
        .focusable()
    ) {
        VideoSurface(controller)
        controller.hint?.let { Hint(it, Alignment.BottomCenter, bottom = 120) }
        controller.openingPrompt?.let { Hint(it, Alignment.BottomEnd, bottom = 48) }
        controller.debugText?.let { DebugPanel(it) }
        // Los controles entran y salen solo con un fundido (velocidad de Ajustes › Interfaz › Animaciones): con deslizamiento, el degradado
        // de arriba se veía bajar y subir como una banda oscura al cargarse y descargarse.
        AnimatedVisibility(visible = controller.controlsVisible && !controller.overlayOpen, modifier = Modifier.fillMaxSize(),
            enter = fadeIn(Motion.spec(200)),
            exit = fadeOut(Motion.spec(200))) {
            Box(Modifier.fillMaxSize()) { Controls(controller) }
        }
        when (val overlay = controller.overlay) {
            PlayerOverlay.None -> Unit
            PlayerOverlay.Tracks -> {
                val category = remember { controller.tracksCategory() }
                SettingsPanel(category, refresh = controller.tracksVersion) { controller.closeTracks() }
            }
            is PlayerOverlay.NextUp -> NextUp(controller, overlay.seconds)
            is PlayerOverlay.Resume -> ResumeDialog(controller, overlay.positionMs)
        }
    }
    // Atrás cierra primero el aviso de siguiente capítulo, la ficha lateral o los controles.
    BackHandler(enabled = controller.overlay is PlayerOverlay.NextUp || controller.controlsVisible) {
        controller.onBack()
    }
}

@Composable
private fun VideoSurface(controller: PlaybackController) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                isFocusable = false
                setShutterBackgroundColor(AndroidColor.BLACK)
                setKeepContentOnPlayerReset(true)
            }
        },
        update = { view ->
            if (view.player !== controller.player) view.player = controller.player
            // Por defecto «Ajustar» (el vídeo entero, sin recortar ni estirar); el botón de escala lo cambia.
            view.resizeMode = controller.videoScale.resizeMode
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun BoxScope.Hint(text: String, alignment: Alignment, bottom: Int) {
    Text(
        text,
        color = Color.White,
        fontSize = Text18,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .align(alignment)
            .padding(end = 48.dp, bottom = bottom.dp, start = 48.dp)
            .clip(ShapePill)
            .background(HintBackground)
            .border(BorderStroke(1.dp, White20), ShapePill)
            .padding(horizontal = 24.dp, vertical = 10.dp)
    )
}

@Composable
private fun BoxScope.DebugPanel(text: String) {
    Text(text, color = Color.White, fontSize = Text12, fontFamily = FontFamily.Monospace,
        modifier = Modifier.align(Alignment.TopStart).padding(24.dp).widthIn(max = 760.dp).background(Black67).padding(12.dp))
}

// ------------------------------------------------------------------------------------------------ Controles

/**
 * Pide el foco reintentando unos fotogramas: los botones están dentro de un BoxWithConstraints, que los compone en el
 * layout, así que el nodo puede no existir todavía cuando arranca el efecto.
 */
private suspend fun FocusRequester.requestFocusWhenReady() {
    repeat(5) {
        if (runCatching { requestFocus() }.getOrDefault(false)) return
        withFrameNanos { }
    }
}

@Composable
private fun BoxScope.Controls(controller: PlaybackController) {
    val playFocus = rememberAutoFocus()
    DisposableEffect(Unit) {
        controller.focusPlay = { runCatching { playFocus.requestFocus() } }
        onDispose { controller.focusPlay = null }
    }
    // Los ajustes se leen una vez por pantalla de controles, no una por botón y recomposición.
    val buttons = remember { PlayerButton.ordered().filter { it.isVisible && (it != PlayerButton.SKIP_OPENING || KaizenSettings.openingSeconds > 0) } }
    // Degradados arriba y abajo para que el texto se lea sobre cualquier imagen.
    // Arriba solo hace falta para el reloj: sin reloj no hay degradado, y con él es corto y suave.
    if (KaizenSettings.playerClock) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(96.dp)
        .background(Brush.verticalGradient(listOf(Scrim.copy(alpha = 0.45f), Color.Transparent))))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(animateDpAsState(if (controller.chaptersOpen) 540.dp else 360.dp, Motion.spec(300)).value)
        .background(Brush.verticalGradient(listOf(Color.Transparent, Black90))))
    // Cerrar la fila (Atrás) devuelve el foco a «reproducir»; al ocultarse los controles la fila se cierra con ellos.
    var chaptersWereOpen by remember { mutableStateOf(false) }
    // Si los controles reaparecen mientras salían, siguen compuestos y sin foco: vuelve a «reproducir».
    LaunchedEffect(controller.controlsVisible) { if (controller.controlsVisible) playFocus.requestFocusWhenReady() }
    LaunchedEffect(controller.chaptersOpen) {
        if (controller.chaptersOpen) chaptersWereOpen = true
        else if (chaptersWereOpen) { chaptersWereOpen = false; playFocus.requestFocusWhenReady() }
    }
    DisposableEffect(Unit) { onDispose { controller.closeChapters() } }

    // Arriba solo el reloj: el título, el capítulo y los códecs van sobre la barra de tiempo.
    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp), horizontalArrangement = Arrangement.End) {
        if (KaizenSettings.playerClock) Clock(showDate = false, fontSize = Text18)
    }

    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Título de la serie, y debajo el capítulo a la izquierda y los códecs a la derecha (sobre «Restante»).
        if (controller.seriesTitle.isNotBlank() || controller.chapterTitle.isNotBlank() || controller.subtitle.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // La fuente de la que viene el capítulo, sobre el título.
            val sourceName = remember(controller.video?.source) { controller.video?.source?.takeIf { it.isNotBlank() }?.let { AnimeSources.of(it).name } }
            if (sourceName != null) Text(sourceName, color = Accent, fontSize = PlayerSubtitleText, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (controller.seriesTitle.isNotBlank()) {
                Text(controller.seriesTitle, color = Color.White, fontSize = PlayerTitleText, fontWeight = FontWeight.Light,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (controller.chapterTitle.isNotBlank() || controller.subtitle.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(controller.chapterTitle, color = White80, fontSize = PlayerSubtitleText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (controller.subtitle.isNotBlank()) {
                        Text(controller.subtitle, color = White80, fontSize = PlayerSubtitleText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
        // Todos los botones en una sola fila y en el orden de Ajustes; los que no caben (interfaz grande) no se pintan.
        // La barra de tiempo va debajo de los botones: abajo desde ellos la enfoca, y abajo desde la barra abre la lista de capítulos.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Una sola línea: se pintan solo los botones que caben, y «Restante» no se mueve. Los que no caben no existen en la
            // pantalla, así que no reciben el foco; reproducir/pausa siempre está.
            BoxWithConstraints(Modifier.weight(1f)) {
                val shown = remember(buttons, maxWidth) { PlayerButton.fitting(buttons, maxWidth.value.toInt() - PlayerButton.FIT_SLACK_DP) }
                SingleLineRow(Modifier, horizontalArrangement = Arrangement.spacedBy(PlayerButton.SPACING_DP.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    shown.forEach { button ->
                        PlayerActionButton(button, controller, if (button == PlayerButton.PLAY) Modifier.focusRequester(playFocus) else Modifier) {}
                    }
                }
            }
            RemainingTime(controller)
        }
        // Un solo hijo de la columna (el hueco entre hijos no cambia): la fila se despliega y se pliega con animación.
        Column {
            SeekBar(controller, onUp = { runCatching { playFocus.requestFocus() } }, onDown = { if (!controller.chaptersOpen) controller.openChapters() })
            AnimatedVisibility(visible = controller.chaptersOpen, enter = expandVertically(Motion.spec(300)) + fadeIn(Motion.spec(300)), exit = shrinkVertically(Motion.spec(300)) + fadeOut(Motion.spec(300))) {
                Box(Modifier.padding(top = 12.dp)) { ChaptersRow(controller) }
            }
        }
    }
}

/** Etiqueta de un botón de la fila de controles (la que se ve bajo ella al enfocarlo). */
private fun buttonLabel(button: PlayerButton, controller: PlaybackController): String = when (button) {
    PlayerButton.PREVIOUS -> "Capítulo anterior"
    PlayerButton.REWIND -> "Atrás"
    PlayerButton.PLAY -> if (controller.isPlaying) "Pausa" else "Reproducir"
    PlayerButton.FORWARD -> "Adelante"
    PlayerButton.NEXT -> "Capítulo siguiente"
    PlayerButton.SKIP_OPENING -> "Saltar opening (+${KaizenSettings.openingSeconds} s)"
    PlayerButton.FAVORITE -> if (controller.isFavorite) "Quitar de favoritos" else "Añadir a favoritos"
    PlayerButton.SPEED -> "Velocidad"
    PlayerButton.SCALE -> "Escala del vídeo: ${controller.videoScale.label}"
    PlayerButton.TRACKS -> "Calidad, audio y subtítulos"
    PlayerButton.SERIES -> "Ficha del anime"
    PlayerButton.DEBUG -> "Depuración"
}

/** Un botón de la fila de controles: su icono, su etiqueta y lo que hace. */
@Composable
private fun PlayerActionButton(button: PlayerButton, controller: PlaybackController, modifier: Modifier, onFocused: () -> Unit) {
    val label = buttonLabel(button, controller)
    when (button) {
        PlayerButton.PREVIOUS -> RoundButton(androidx.media3.ui.R.drawable.exo_icon_previous, label, modifier, onFocused = onFocused) { controller.previousOrRestart() }
        PlayerButton.REWIND -> RoundButton(androidx.media3.ui.R.drawable.exo_icon_rewind, label, modifier, onFocused = onFocused) { controller.rewind() }
        PlayerButton.PLAY -> RoundButton(
            if (controller.isPlaying) androidx.media3.ui.R.drawable.exo_icon_pause else androidx.media3.ui.R.drawable.exo_icon_play,
            label, modifier, onFocused = onFocused
        ) { controller.togglePlay() }
        PlayerButton.FORWARD -> RoundButton(androidx.media3.ui.R.drawable.exo_icon_fastforward, label, modifier, onFocused = onFocused) { controller.fastForward() }
        PlayerButton.NEXT -> RoundButton(androidx.media3.ui.R.drawable.exo_icon_next, label, modifier, onFocused = onFocused) { controller.changeChapter(1) }
        PlayerButton.SKIP_OPENING -> RoundButton(R.drawable.ic_skip_kaizen, label, modifier, onFocused = onFocused) { controller.skipOpening() }
        PlayerButton.FAVORITE -> RoundButton(
            if (controller.isFavorite) R.drawable.ic_favorite_kaizen else R.drawable.ic_favorite_border_kaizen,
            label, modifier,
            tint = if (controller.isFavorite) Color.Unspecified else Color.White, onFocused = onFocused
        ) { controller.toggleFavorite() }
        PlayerButton.SPEED -> RoundButton(null, label, modifier, text = KaizenSettings.formatSpeed(controller.speed), onFocused = onFocused) { controller.cycleSpeed() }
        PlayerButton.SCALE -> RoundButton(R.drawable.ic_video_scale, label, modifier, onFocused = onFocused) { controller.cycleScale() }
        PlayerButton.TRACKS -> RoundButton(R.drawable.ic_quality_kaizen, label, modifier, onFocused = onFocused) { controller.openTracks() }
        PlayerButton.SERIES -> RoundButton(R.drawable.ic_queue_list_kaizen, label, modifier, cover = remember { controller.coverUrls }, onFocused = onFocused) { controller.openSeries() }
        PlayerButton.DEBUG -> RoundButton(R.drawable.ic_bug_kaizen, label, modifier, onFocused = onFocused) { controller.toggleDebug() }
    }
}

/** Botón redondo de icono (o de texto) que se enfoca con el mando. */
@Composable
private fun RoundButton(
    icon: Int?,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    text: String? = null,
    cover: List<String> = emptyList(),
    onFocused: () -> Unit = {},
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    // Enfocado: círculo translúcido y sin borde; el icono sigue blanco.
    val content = Color.White
    val fill by animateColorAsState(if (focused) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0f), Motion.spec(150), label = "buttonFill")
    Box(
        // Proporciones de SmartTube: círculo de 48 dp con el icono de 32 dp.
        modifier.size(width = (if (text != null) PlayerButton.TEXT_WIDTH_DP else PlayerButton.ROUND_DP).dp, height = PlayerButton.ROUND_DP.dp)
            .clip(CircleShape)
            // Sin fondo: solo el botón enfocado se rellena.
            .background(fill)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (cover.isNotEmpty()) {
            // Círculo de la portada con el tamaño de los iconos de los demás botones.
            Cover(cover, Modifier.size(PlayerIcon).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape), showLabel = false)
        } else if (icon != null) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(PlayerIcon),
                tint = if (tint == Color.Unspecified) Color.Unspecified else content)
        } else {
            Text(text.orEmpty(), color = content, fontSize = PlayerButtonText, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
    }
}

/** Lo que queda de vídeo, a la derecha de los botones. Lee la posición aquí para que no se recomponga toda la fila con cada tic. */
@Composable
private fun RemainingTime(controller: PlaybackController) {
    if (controller.durationMs <= 0) return
    Text("Restante: ${KaizenProgress.formatTime((controller.durationMs - controller.positionMs).coerceAtLeast(0))}",
        color = White80, fontSize = PlayerTimeText, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
}

/** Barra de tiempo: se enfoca y las flechas la recorren (1 % por pulsación). */
@Composable
private fun SeekBar(controller: PlaybackController, onUp: () -> Unit, onDown: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var barWidth by remember { mutableIntStateOf(0) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Cada texto lee su propio estado: la fila no se recompone con cada tic de posición.
        Text(KaizenProgress.formatTime(controller.positionMs), color = Color.White, fontSize = Text16)
        Box(
            Modifier.weight(1f).height(28.dp)
                .onSizeChanged { barWidth = it.width }
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { controller.seekPercent(-1); true }
                        Key.DirectionRight -> { controller.seekPercent(1); true }
                        // Arriba sube a «reproducir» y no a donde caiga la búsqueda de foco (el último botón); abajo abre los capítulos, y con la fila ya abierta baja a ella.
                        Key.DirectionUp -> { onUp(); true }
                        Key.DirectionDown -> if (controller.chaptersOpen) false else { onDown(); true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.CenterStart
        ) {
            val trackHeight = if (focused) 8.dp else 4.dp
            Box(Modifier.fillMaxWidth().height(trackHeight).clip(ShapeSm).background(White40))
            // Lo descargado, más claro que la pista y por debajo de lo reproducido.
            Box(Modifier.fillMaxWidth().height(trackHeight)
                .graphicsLayer {
                    scaleX = controller.fractionOf(controller.bufferedMs)
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .clip(ShapeSm).background(White60))
            // El relleno se escala al dibujar: la posición se lee en la capa gráfica, sin recomponer ni medir de nuevo.
            // La escala va antes que el fondo: si no, el fondo se dibuja sin escalar y la barra sale siempre llena.
            Box(Modifier.fillMaxWidth().height(trackHeight)
                .graphicsLayer {
                    scaleX = controller.fractionOf(controller.positionMs)
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .clip(ShapeSm).background(Accent))
            // Los tramos que AniSkip conoce (opening y cierre), en ámbar sobre la pista: se ve de un vistazo dónde empiezan y acaban.
            val ranges = controller.skipRanges
            if (ranges.isNotEmpty() && controller.durationMs > 0) Box(Modifier.fillMaxWidth().height(trackHeight).drawBehind {
                ranges.forEach { range ->
                    val from = size.width * (range.first.toFloat() / controller.durationMs).coerceIn(0f, 1f)
                    val to = size.width * (range.last.toFloat() / controller.durationMs).coerceIn(0f, 1f)
                    drawRect(Color(0xFFF3B95F), topLeft = androidx.compose.ui.geometry.Offset(from, 0f), size = androidx.compose.ui.geometry.Size((to - from).coerceAtLeast(2f), size.height))
                }
            })
            // Con la barra enfocada, un círculo marca dónde va la reproducción.
            if (focused) {
                Box(Modifier.size(SEEK_THUMB)
                    .graphicsLayer { translationX = controller.fractionOf(controller.positionMs) * barWidth - SEEK_THUMB.toPx() / 2 }
                    .background(Color.White, CircleShape))
            }
        }
        Text(KaizenProgress.formatTime(controller.durationMs), color = Color.White, fontSize = Text16)
    }
}

private val SEEK_THUMB = 20.dp

/** [ms] como fracción de la duración (0 mientras no se conoce). */
private fun PlaybackController.fractionOf(ms: Long): Float = if (durationMs > 0) (ms.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

// ------------------------------------------------------------------------------------------------ Paneles

/** Ancho de cada tarjeta de la fila de capítulos; también es el ancho que busca [PlaybackController] al elegir la portada. */
internal const val CHAPTER_CARD_DP = 200

/** Fila de capítulos con su captura, bajo los botones (como SmartTube): el actual enfocado; OK reproduce, arriba o Atrás la ocultan y devuelven el foco al reproductor. */
@Composable
private fun ChaptersRow(controller: PlaybackController) {
    val chapters = controller.chapters
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val current = remember { FocusRequester() }
    val currentIndex = chapters.indexOfFirst(controller::isCurrent).coerceAtLeast(0)
    if (chapters.isEmpty()) Text("Cargando…", color = White80, fontSize = Text17)
    // El foco se pide cuando la fila ya está desplazada al capítulo actual (si no, la tarjeta aún no existe).
    LaunchedEffect(chapters, controller.chaptersOpen) {
        if (chapters.isEmpty() || !controller.chaptersOpen) return@LaunchedEffect
        listState.scrollToItem(currentIndex)
        withFrameNanos { }
        runCatching { current.requestFocus() }.onFailure { delay(100.milliseconds); runCatching { current.requestFocus() } }
    }
    // Claves estables (fuente, serie y número; sin repetir): al cambiar la lista el foco y el desplazamiento se quedan en el capítulo, no en la posición.
    val chapterKeys = remember(chapters) { app.kaizen.tv.ui.uniqueKeys(chapters.map { "${it.source}:${it.slug}:${it.number}" }) }
    // Arriba oculta la fila y devuelve el foco al reproductor (el efecto de los controles lo lleva a «reproducir»).
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.onPreviewKeyEvent { event ->
        val up = event.key == Key.DirectionUp
        if (up && event.type == KeyEventType.KeyDown) controller.closeChapters()
        up
    }) {
        itemsIndexed(chapters, key = { index, _ -> chapterKeys[index] }, contentType = { _, _ -> "chapter" }) { index, chapter ->
            var focused by remember { mutableStateOf(false) }
            val playing = controller.isCurrent(chapter)
            // Si la fuente no da captura del capítulo (usa su portada, a veces diminuta), va antes la portada más nítida de la serie entre
            // todas las fuentes. Detrás, la captura y las portadas, con la ya guardada en la caché de imágenes de más peso la primera.
            val sharp = controller.sharpCover.takeIf { chapter.thumbnail == AnimeSources.of(chapter.source).coverUrl(chapter.aid, chapter.slug) }
            val covers by produceState(listOfNotNull(sharp, chapter.thumbnail), chapter, sharp) {
                val rest = bestCachedFirst(context, (listOf(chapter.thumbnail) + coverCandidates(chapter.aid, chapter.slug, "")).filter { it.isNotBlank() }.distinct())
                value = (listOfNotNull(sharp) + rest).distinct()
            }
            Column(
                Modifier.width(CHAPTER_CARD_DP.dp).clip(ShapeMd)
                    .then(if (index == currentIndex) Modifier.focusRequester(current) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .clickable(role = Role.Button) { controller.playChapter(chapter) }
                    .then(if (focused) Modifier.border(3.dp, Color.White, ShapeMd) else Modifier)
                    .padding(CardInset),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val seen = app.kaizen.tv.ui.rememberSeen(chapter.source, chapter.slug, chapter.number)
                Cover(covers, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(CardImageShape), showLabel = false, under = remember(chapter) { chapter.coverUnderThumbnail })
                Row(Modifier.padding(horizontal = CardTextInset, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (seen) SeenCheck()
                    Text(if (playing) "▶ ${chapter.episodeName}" else chapter.episodeName, color = if (focused || playing) Color.White else White80,
                        fontSize = Text15, fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Las [urls] con primero la que ya está en la caché de disco de Coil con más peso (la de mayor calidad); el resto, en su orden. */
private suspend fun bestCachedFirst(context: Context, urls: List<String>): List<String> = withContext(Dispatchers.IO) {
    val disk = SingletonImageLoader.get(context).diskCache ?: return@withContext urls
    val sizes = urls.associateWith { url ->
        runCatching { disk.openSnapshot(url)?.use { disk.fileSystem.metadata(it.data).size } }.getOrNull() ?: 0L
    }
    urls.sortedByDescending { sizes.getValue(it) }
}

/** «Siguiente capítulo» con cuenta atrás, «Ver ahora» y «Cancelar». */
@Composable
private fun BoxScope.NextUp(controller: PlaybackController, seconds: Int) {
    val play = rememberAutoFocus()
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = shown, modifier = Modifier.align(Alignment.BottomEnd),
        enter = fadeIn(Motion.spec(200)) + slideInVertically(Motion.spec(250)) { it / 4 }) {
    Column(
        Modifier.padding(48.dp).width(360.dp)
            .background(PanelColor, ShapeXl).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(appString(R.string.ui_siguiente_capitulo), color = Color.White, fontSize = Text18, fontWeight = FontWeight.Bold)
        Text("Empieza en $seconds s", color = White80, fontSize = Text16)
        KaizenButton(appString(R.string.ui_ver_ahora), onClick = controller::playNextNow, modifier = Modifier.fillMaxWidth().focusRequester(play))
        KaizenButton(appString(R.string.ui_cancelar), onClick = controller::cancelAutoNext, modifier = Modifier.fillMaxWidth())
    }
    }
}

/** Pregunta si reanudar donde se quedó o empezar desde el principio; cancelar reanuda. */
@Composable
private fun ResumeDialog(controller: PlaybackController, positionMs: Long) {
    val first = remember { FocusRequester() }
    val title = controller.video?.chapter ?: appString(R.string.ui_reanudar)
    KaizenDialog(onDismissRequest = { controller.resume(fromSaved = true) }, title = title) {
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        KaizenButton("Reanudar desde ${KaizenProgress.formatTime(positionMs)}", onClick = { controller.resume(fromSaved = true) },
            modifier = Modifier.fillMaxWidth().focusRequester(first))
        KaizenButton(appString(R.string.ui_desde_el_principio), onClick = { controller.resume(fromSaved = false) }, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Los botones del reproductor en una sola línea, recortando lo que no quepa (ya van ajustados con [PlayerButton.fitting]). `maxLines` y `overflow` de `FlowRow`
 * están marcados como obsoletos sin sustituto equivalente; el aviso se silencia solo aquí.
 */
@Suppress("DEPRECATION")
@Composable
private fun SingleLineRow(
    modifier: Modifier, horizontalArrangement: Arrangement.Horizontal, verticalArrangement: Arrangement.Vertical, content: @Composable FlowRowScope.() -> Unit
) = FlowRow(modifier, horizontalArrangement = horizontalArrangement, verticalArrangement = verticalArrangement, maxLines = 1, overflow = FlowRowOverflow.Clip, content = content)
