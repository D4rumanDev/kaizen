@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kaizen.tv.ui.valueOrNull
import app.kaizen.tv.ui.rememberLoadable
import app.kaizen.tv.details.*
import app.kaizen.tv.ui.*
import app.kaizen.tv.ui.components.*
import app.kaizen.tv.ui.theme.*

import app.kaizen.tv.ui.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.launch
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import app.kaizen.tv.ui.CenteredTextStyle
import app.kaizen.tv.ui.uniqueKeys
import app.kaizen.tv.ui.NO_EPISODES
import app.kaizen.tv.ui.episodesLabel
import app.kaizen.tv.ui.rememberAutoFocus
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.pojos.av1.Chapter
import app.kaizen.pojos.av1.Relation
import app.kaizen.pojos.av1.Series
import app.kaizen.pojos.av1.SeriesSummary
import app.kaizen.source.coverCandidates
import app.kaizen.source.Catalog
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.AnilistTrailer
import app.kaizen.source.FichaData
import app.kaizen.source.AnilistLabels
import app.kaizen.source.AnimeSources
import app.kaizen.source.UserTitles
import app.kaizen.source.BestCovers
import app.kaizen.source.userCover
import app.kaizen.tv.ui.SeriesPickerDialog
import app.kaizen.tv.ui.KaizenToast
import app.kaizen.tv.TVServersFactory.ServerChoice
import app.kaizen.tv.ui.CardMenu
import app.kaizen.tv.ui.StatusDialog
import app.kaizen.domain.SeriesStatuses
import app.kaizen.tv.ui.Cover
import app.kaizen.tv.ui.LocalCardWidth
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import app.kaizen.domain.NotRecommended
import app.kaizen.commons.appString
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

private val GutterStart = ContentPaddingHorizontal

/** Cabecera baja (como Netflix o Prime): con ella las pestañas y los episodios quedan a la vista sin bajar. */
private val HERO_HEIGHT = DetailHeaderMinHeight
private val POSTER_WIDTH = DetailPosterWidth
private val REGROUP_WAIT = 9.seconds
/** Lo que tarda en desaparecer una ficha separada: el foco ya está en otra tarjeta. */
private val SEPARATE_HIDE = 500.milliseconds

/** Lo que se espera a que el menú se cierre y devuelva el foco antes de moverlo a otra tarjeta. */
private val REFOCUS_AFTER_DIALOG = 150.milliseconds

/** Acciones de la ficha que resuelve quien la aloja (reproducir, abrir otra ficha) o el modelo (favorito, vistos). */
class FichaActions(
    val onPlay: (Chapter?, ServerChoice) -> Unit,
    val onOpen: (String) -> Unit,
    val onFavorite: () -> Unit,
    val onSeen: (Boolean) -> Unit,
    val onStatus: (app.kaizen.domain.SeriesStatus) -> Unit
)

/**
 * Kaizen: ficha del anime en Compose. Cabecera (portada de fondo, tarjeta, chips, sinopsis y botones), episodios
 * (abierta en el que toca ver), «Precuelas y secuelas», «Relacionados», «Más como esto» y «Series unidas».
 */
@Composable
fun SeriesDetailScreen(state: FichaViewModel.State.Ready, actions: FichaActions) {
    // La portada es lo primero que se selecciona (pulsarla la muestra completa); con la flecha derecha el foco pasa al botón de ver.
    val poster = rememberAutoFocus()
    val play = remember { FocusRequester() }
    // Pulsación larga en un episodio, una relación o una sugerencia: el mismo menú que en el resto de pantallas.
    var menuItem by remember { mutableStateOf<Any?>(null) }
    val openMenu = { item: Any -> menuItem = item }
    // Las otras fichas de esta misma serie (otras fuentes): salen al final, como las precuelas y secuelas.
    val titleChanges by UserTitles.changes.collectAsStateWithLifecycle()
    val firstChanges = remember { UserTitles.changes.value }
    val loadedJoined by produceState(emptyList<Catalog.JoinedFicha>(), state.series.slug, state.series.source, titleChanges) {
        suspend fun load() = runCatchingCoop { Catalog.joinedFichas(state.series.source, state.series.slug) }.onFailure(::logError).getOrDefault(emptyList())
        value = load()
        // Unir o separar reagrupa el catálogo en segundo plano (unos segundos): al terminar se relee con sus datos.
        if (titleChanges != firstChanges) {
            delay(REGROUP_WAIT)
            value = load()
        }
    }
    // Las que se acaban de separar desaparecen de la fila al momento, sin esperar a que el catálogo se reagrupe.
    var separated by remember { mutableStateOf(emptySet<Pair<String, String>>()) }
    val joined = loadedJoined.filterNot { (it.sourceId to it.slug) in separated }
    var chosen by remember { mutableStateOf<Catalog.JoinedFicha?>(null) }
    // Al separar una ficha unida el foco pasa antes a otra tarjeta de «Series unidas» y solo después desaparece la separada (si el foco se
    // quedara en la que se quita, saltaría al menú lateral); si no queda ninguna, va al botón de ver.
    val scope = rememberCoroutineScope()
    var refocusKey by remember { mutableStateOf<String?>(null) }
    var separations by remember { mutableStateOf(0) }
    LaunchedEffect(separations) {
        if (separations == 0 || joined.isNotEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { play.requestFocus() }
    }

    // Mover el foco dentro de la cabecera (portada, sinopsis, botones) no debe desplazar la lista: ver [LeastScroll].
    // Pero si el foco vuelve a la cabecera o a las pestañas (subiendo desde los episodios), la lista vuelve arriba: con el desplazamiento mínimo la cabecera podía quedar cortada.
    val listState = rememberLazyListState()
    val backToTop = Modifier.onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) } }
    val edgeMargin = with(androidx.compose.ui.platform.LocalDensity.current) { SCROLL_EDGE_MARGIN.toPx() }
    CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(edgeMargin) { smoothScroll(edgeMargin) }) {
        LazyColumn(Modifier.fillMaxSize().background(Background), state = listState, contentPadding = PaddingValues(bottom = DetailBottomPadding)) {
            // Con clave: las filas que llegan después (la ficha sale antes de completarse) no desplazan el estado ni el foco de las demás.
            item(key = "hero") { Box(backToTop) { Hero(state, actions, poster, play) } }
            // Sin pestañas: sinopsis y datos en paneles bajo la cabecera, y después todo en una sola lista.
            item(key = "panels") { InfoPanels(state) }
            if (state.episodes.isNotEmpty()) item(key = "episodes") { EpisodesRow(state, actions, openMenu) }
            if (state.story.isNotEmpty()) item(key = "story") { RelationsRow("Precuelas y secuelas", state.story, actions, openMenu) }
            if (state.others.isNotEmpty()) item(key = "others") { RelationsRow(appString(R.string.ui_obras_relacionadas), state.others, actions, openMenu) }
            if (state.similar.isNotEmpty()) item(key = "similar") { SimilarRow(state.similar, actions, openMenu) }
            if (joined.isNotEmpty() || state.anilist != null) item(key = "joined") {
                JoinedRow(joined, state.anilist, {}, actions, refocusKey, onRefocused = { refocusKey = null }) { chosen = it }
            }
        }
    }
    menuItem?.let { CardMenu.Menu(it) { menuItem = null } }
    chosen?.let { ficha ->
        JoinedFichaMenu(ficha,
            onOpen = { actions.onOpen(AnimeSources.of(ficha.sourceId).animeUrl(ficha.slug)) },
            onSeparate = {
                UserTitles.separate(state.series.source, state.series.slug, ficha.sourceId, ficha.slug)
                CardMenu.titlesChanged("Separada")
                // La vecina de al lado (la siguiente o, si era la última, la anterior): es la que seguro está a la vista.
                val at = joined.indexOfFirst { it.sourceId == ficha.sourceId && it.slug == ficha.slug }
                refocusKey = (joined.getOrNull(at + 1) ?: joined.getOrNull(at - 1))?.let { "${it.sourceId}:${it.slug}" }
                scope.launch {
                    delay(SEPARATE_HIDE)
                    refocusKey = null
                    separated = separated + (ficha.sourceId to ficha.slug)
                    separations += 1
                }
            },
            onDismiss = { chosen = null })
    }
}

/** Hueco que deja el desplazamiento mínimo entre el elemento enfocado y el borde de la lista. */
private val SCROLL_EDGE_MARGIN = 24.dp
/** Aire arriba y abajo de las filas: el foco ampliado y la sombra de las tarjetas no se recortan. */
private val ROW_VERTICAL_PADDING = 8.dp

@Composable
private fun Hero(state: FichaViewModel.State.Ready, actions: FichaActions, poster: FocusRequester, play: FocusRequester) {
    val series = state.series
    val shownName = remember(series.name, state.anilist) { state.anilist?.romaji?.takeIf { it.isNotBlank() } ?: series.name }
    Box(Modifier.fillMaxWidth().height(HERO_HEIGHT)) {
        // Fondo: la portada ampliada (se ve difusa) bajo un degradado que la funde con el negro.
        // Con anime de AniList, su banner o su portada (mejor calidad); la de la fuente solo si no cargan.
        Cover(listOfNotNull(state.anilist?.banner, state.anilist?.portada, series.imageUrl), Modifier.fillMaxSize().alpha(0.55f), showLabel = false)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Black60, Background))))
        Row(Modifier.fillMaxSize().padding(start = GutterStart, end = GutterStart, top = 18.dp, bottom = 10.dp)) {
            Poster(series, state.anilist?.portada, poster, play)
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f).fillMaxSize()) {
                Text(series.typeText, color = White70, fontSize = Text13, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Interlineado propio: con el de la fuente, un título de dos líneas se solapa consigo mismo.
                Text(shownName, color = Color.White, fontSize = DetailTitleText, lineHeight = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                state.anilist?.native?.takeIf { it.isNotBlank() && it != shownName }?.let {
                    Text(it, color = TextSecondary, fontSize = Text14, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MetaLine(series, state.episodes.size, state.anilist?.episodios, state.anilist != null, poster, airing = state.anilist?.estado?.let { it == "RELEASING" } ?: (series.state == 1))
                if (series.genres.isNotEmpty()) {
                    Text(series.genres.joinToString("  ·  ") { it.name }, color = AccentLight, fontSize = Text14,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                Buttons(state, actions, play)
            }
        }
    }
}

/** Portada de la ficha: enfocable, y pulsarla abre la galería de portadas de la serie en cada fuente. */
@Composable
private fun Poster(series: Series, anilistCover: String?, requester: FocusRequester, play: FocusRequester) {
    var viewing by remember { mutableStateOf(false) }
    // La portada de mayor resolución entre las fuentes de la serie (se mide una vez y se recuerda: también la usan las tarjetas).
    val context = LocalContext.current
    // La portada no depende de la nota ni de las relaciones: no se reinicia cuando la ficha se completa.
    var best by remember(series.slug, series.source) { mutableStateOf(BestCovers.get(series.slug)) }
    // Con la portada de AniList no se miden las de las fuentes (son varias peticiones a sus webs).
    LaunchedEffect(series.slug, series.source, anilistCover) { if (anilistCover == null) best = sharpestCover(context, series) ?: best }
    FocusCard(Modifier.width(POSTER_WIDTH).fillMaxHeight().focusRequester(requester).focusProperties { right = play }.semantics { contentDescription = "Portada de ${series.name}: ver portadas" }, onClick = { viewing = true }, background = White10) {
        Cover(listOfNotNull(userCover(series.slug, series.source), anilistCover, best, series.imageUrl).distinct(), Modifier.weight(1f).fillMaxWidth().clip(CardImageShape), reuseCached = true, blur = app.kaizen.tv.ui.components.rememberSensitive(series.source, series.slug))
    }
    if (viewing) {
        val covers = rememberLoadable(series) { listOfNotNull(anilistCover?.let { GalleryCover("AniList", it) }) + galleryCovers(series) }.valueOrNull
        val scope = rememberCoroutineScope()
        PosterGallery(covers, onChoose = { cover ->
            UserTitles.editFor(series.source, series.slug) { rules, key -> rules.withCover(key, cover.url) }
            KaizenToast.show("Portada elegida (${cover.label})")
            viewing = false
        }, onDismiss = { viewing = false }, onRefresh = { cover ->
            scope.launch { KaizenToast.show(if (renewCover(context, cover.url)) "Portada renovada (${cover.label}): se baja de nuevo al volver" else "Sin conexión: no se renueva la portada") }
        })
    }
}

/**
 * Los datos de la serie en una sola línea de texto (como Netflix o Prime): nota, y después año, episodios y estado separados por puntos.
 * Tu estado de seguimiento ya está en su botón y el tipo sobre el título: no se repiten.
 */
@Composable
private fun MetaLine(series: Series, episodes: Int, total: Int?, fromAnilist: Boolean, refocus: FocusRequester, airing: Boolean) {
    // El año lo guarda el catálogo (la ficha de la fuente no lo trae).
    val year = rememberLoadable(series.source to series.slug) { Catalog.pickOfFicha(series.source, series.slug)?.year }.valueOrNull
    val facts = listOfNotNull(
        // De qué fuente viene la información de la ficha; con los datos de AniList ya no importa (las fuentes están en la pestaña «Fuentes»).
        AnimeSources.of(series.source).name.takeIf { !fromAnilist },
        year?.toString(),
        // Disponibles y totales por separado: en una serie en emisión no coinciden.
        (if (total != null && total > episodes) "$episodes de $total episodios" else episodesLabel(episodes)).takeIf { episodes > 0 },
        appString(R.string.ui_en_emision).takeIf { airing }
    ).joinToString("  ·  ")
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (series.rateStars > 0f) {
            // Nota en porcentaje (la de estrellas /5 por 20), verde, ámbar o roja según su valor.
            val percent = (series.rateStars * 20).roundToInt()
            val (background, text) = when {
                percent >= 80 -> ScoreGood to Color.White
                percent >= 60 -> ScoreFair to Color.Black
                else -> ScoreBad to Color.White
            }
            Chip("$percent%", background, text, FontWeight.Bold)
        }
        if (facts.isNotEmpty()) Text(facts, color = White70, fontSize = Text15, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        NotRecommendedChip(series.name, series.source, series.slug, refocus)
    }
}

/** «No recomendada» si el usuario la excluyó de las sugerencias; pulsarla (OK) vuelve a permitirla. */
@Composable
private fun NotRecommendedChip(name: String, source: String, slug: String, refocus: FocusRequester) {
    var blocked by remember(name, source, slug) { mutableStateOf(!NotRecommended.allows(name, source, slug)) }
    if (!blocked) return
    var focused by remember { mutableStateOf(false) }
    Text(appString(R.string.ui_no_recomendada_ok_permitir), color = if (focused) Color.Black else Color.White,
        fontSize = Text14, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }.clip(ShapePill)
            .background(if (focused) Color.White else White20)
            .clickable(role = Role.Button) { NotRecommended.set(name, excluded = false, source = source, slug = slug); runCatching { refocus.requestFocus() }; blocked = false }
            .padding(horizontal = 12.dp, vertical = 4.dp))
}

@Composable
private fun Chip(text: String, background: Color = White20, content: Color = Color.White, weight: FontWeight = FontWeight.Normal) {
    Text(text, color = content, fontSize = Text14, fontWeight = weight, maxLines = 1,
        modifier = Modifier.clip(ShapePill).background(background).padding(horizontal = 12.dp, vertical = 4.dp))
}

/** Sinopsis y datos de interés, en paneles bajo la cabecera (como la maqueta). */
@Composable
private fun InfoPanels(state: FichaViewModel.State.Ready) {
    val series = state.series
    val a = state.anilist
    val facts = if (a != null) listOfNotNull(
        a.estudio?.let { "Estudio" to it },
        AnilistLabels.format(a.formato)?.let { "Formato" to it },
        a.episodios?.let { "Episodios" to it.toString() },
        a.duracion?.let { "Duración" to "$it min" },
        a.anio?.let { "Estreno" to it.toString() }
    ) else listOfNotNull(
        "Fuente" to AnimeSources.of(series.source).name,
        series.typeText.takeIf { it.isNotBlank() }?.let { "Tipo" to it },
        state.episodes.size.takeIf { it > 0 }?.let { "Episodios" to it.toString() }
    )
    Row(Modifier.fillMaxWidth().padding(start = GutterStart, end = GutterStart, top = 12.dp).height(140.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        InfoPanel(appString(R.string.ui_sinopsis), Modifier.weight(2.4f)) {
            Synopsis(series.description.ifBlank { "Sin sinopsis" }, Modifier.height(92.dp).offset(x = -SYNOPSIS_PADDING))
        }
        // Una sola columna estrecha: lo que sobra es para la sinopsis.
        InfoPanel(appString(R.string.ui_datos_de_interes), Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                facts.forEach { (label, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, color = TextSecondary, fontSize = Text12, maxLines = 1)
                        Text(value, color = Color.White, fontSize = Text12, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        // Tu progreso: lo visto de lo disponible.
        val total = state.episodes.size
        val seen = state.episodes.count { it.eid in state.watch.seenEids }
        InfoPanel(appString(R.string.ui_tu_progreso), Modifier.weight(0.7f)) {
            val fraction = if (total > 0) seen.toFloat() / total else 0f
            Text("${(fraction * 100).roundToInt()} %", color = AccentLight, fontSize = Text20, fontWeight = FontWeight.Bold)
            CardProgressBar(fraction, Modifier.fillMaxWidth().clip(ShapeSm))
            Text(appString(R.string.ui_progreso_resumen, seen, total), color = TextSecondary, fontSize = Text12, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun InfoPanel(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxHeight().clip(ShapeMd).background(SurfaceCard).border(1.dp, Border, ShapeMd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = Color.White, fontSize = Text14, fontWeight = FontWeight.Bold)
        // El contenido se centra en el alto que deja el título: los datos y el progreso no quedan pegados arriba con un hueco debajo.
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) { content() }
    }
}

/**
 * Sinopsis: enfocable, y pulsarla la expande o contrae. Contraída ocupa todo el alto que le deja la ficha (no un número
 * fijo de líneas), para no cortar el texto habiendo sitio.
 */
@Composable
private fun Synopsis(text: String, modifier: Modifier) {
    var showing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier) {
        val lineHeight = 20.sp
        val fitting = with(LocalDensity.current) { ((maxHeight - SYNOPSIS_PADDING * 2).toPx() / lineHeight.toPx()).toInt() }
        Text(text, color = TextSecondary, fontSize = DescriptionText, lineHeight = lineHeight,
            maxLines = fitting.coerceAtLeast(1), overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .onFocusChanged { focused = it.isFocused }
                .then(if (focused) Modifier.border(BorderStroke(2.dp, AccentLight), ShapeMd) else Modifier)
                .clip(ShapeMd)
                .clickable(role = Role.Button) { showing = true }
                .padding(SYNOPSIS_PADDING))
    }
    if (showing) SynopsisDialog(text) { showing = false }
}

/** La sinopsis entera en una ventana: arriba y abajo con el mando la desplazan; Atrás la cierra. */
@Composable
private fun SynopsisDialog(text: String, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val step = with(LocalDensity.current) { 120.dp.toPx() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Scrim).padding(horizontal = 96.dp, vertical = 48.dp)) {
            Box(Modifier.fillMaxSize().background(PanelColor, ShapeXl)) {
                Text(text, color = Color.White, fontSize = Text18, lineHeight = Line26,
                    modifier = Modifier.fillMaxSize()
                        .focusRequester(focus)
                        .onKeyEvent { event ->
                            val direction = when (event.key) { Key.DirectionDown -> 1f; Key.DirectionUp -> -1f; else -> 0f }
                            if (direction != 0f && event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(direction * step) }
                            direction != 0f
                        }
                        .focusable()
                        .verticalScroll(scroll)
                        .padding(32.dp))
            }
        }
    }
}

@Composable
private fun Buttons(state: FichaViewModel.State.Ready, actions: FichaActions, play: FocusRequester) {
    val watch = state.watch
    var merging by remember { mutableStateOf(false) }
    var choosingStatus by remember { mutableStateOf(false) }
    var confirmUnseen by remember { mutableStateOf(false) }
    var trailerOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val statuses by SeriesStatuses.all.collectAsStateWithLifecycle()
    val status = SeriesStatuses.statusIn(statuses, state.series.name, state.series.source, state.series.slug)
    // El tráiler solo sale si la serie está enlazada con AniList y este lo tiene.
    val anilistId = state.anilist?.media?.optLong("id", 0L) ?: 0L
    val trailer = rememberLoadable(anilistId) { if (anilistId > 0) runCatchingCoop { AnilistTrailer.of(anilistId) }.onFailure(::logError).getOrNull() else null }.valueOrNull
    val context = LocalContext.current
    // Una sola fila compacta: solo iconos (el texto va como descripción para los lectores de pantalla) y, a la derecha, marcar todos los capítulos y unir.
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Solo el icono: «Seguir Episodio N · mm:ss» no cabía y se veía recortado («Ve…»). El texto queda como descripción para los lectores de pantalla.
        HeroButton("", R.drawable.ic_play_arrow_kaizen, Modifier.focusRequester(play).semantics { contentDescription = watch.playLabel }, primary = true) {
            actions.onPlay(watch.target, ServerChoice.REMEMBERED)
        }
        HeroButton(
            "",
            if (watch.isFavorite) R.drawable.ic_favorite_kaizen else R.drawable.ic_favorite_border_kaizen,
            Modifier.semantics { contentDescription = if (watch.isFavorite) "En favoritos" else "Favorito" },
            // El corazón de «en favoritos» ya va en rojo; sin favorito, blanco como el resto de iconos.
            tint = if (watch.isFavorite) Color.Unspecified else Color.White,
            onClick = actions.onFavorite
        )
        // El tráiler, junto al favorito: solo el icono del reproductor.
        trailer?.let { HeroButton("", R.drawable.ic_trailer_kaizen, Modifier.semantics { contentDescription = "Tráiler" }) { trailerOpen = true } }
        // El estado de seguimiento va como texto (sin icono) y con el color de su etiqueta.
        val hasStatus = status != app.kaizen.domain.SeriesStatus.NONE
        HeroButton(if (hasStatus) status.label else "Estado", 0, Modifier.semantics { contentDescription = if (hasStatus) "Estado: ${status.label}" else "Elegir estado" }, container = status.takeIf { hasStatus }?.let { Color(it.color) }) { choosingStatus = true }
        // Marcar todos y unir, en el menú de tres puntos.
        HeroButton("", R.drawable.ic_more_vert_kaizen, Modifier.semantics { contentDescription = "Más acciones" }) { moreOpen = true }
    }
    if (moreOpen) {
        val first = rememberAutoFocus()
        KaizenDialog(onDismissRequest = { moreOpen = false }, title = state.series.name) {
            KaizenButton(watch.seenLabel.removePrefix("✓  "), onClick = { moreOpen = false; if (watch.allSeen) confirmUnseen = true else actions.onSeen(true) }, modifier = Modifier.fillMaxWidth().focusRequester(first))
            KaizenButton(appString(R.string.ui_unir), onClick = { moreOpen = false; merging = true }, modifier = Modifier.fillMaxWidth())
        }
    }
    // Desmarcar todos borra el historial de vistos de la serie: se pide confirmación (marcar todos se deshace con el mismo botón).
    if (confirmUnseen) {
        val cancel = rememberAutoFocus()
        KaizenDialog(onDismissRequest = { confirmUnseen = false }, title = appString(R.string.ui_desmarcar_todos_titulo), subtitle = appString(R.string.ui_desmarcar_todos_aviso)) {
            KaizenButton(appString(R.string.ui_cancelar), onClick = { confirmUnseen = false }, modifier = Modifier.fillMaxWidth().focusRequester(cancel))
            KaizenButton(appString(R.string.ui_desmarcar_todos), onClick = { confirmUnseen = false; actions.onSeen(false) }, modifier = Modifier.fillMaxWidth())
        }
    }
    // El tráiler se ve en una ventana de la app; si el reproductor incrustado no carga, se abre fuera como antes.
    if (trailerOpen) trailer?.let { url ->
        TrailerDialog(url, onDismiss = { trailerOpen = false }, onFail = { trailerOpen = false; openTrailer(context, url) })
    }
    if (choosingStatus) {
        val series = state.series
        StatusDialog(series.name, status, onPick = { actions.onStatus(it) }, onDismiss = { choosingStatus = false })
    }
    if (merging) {
        val series = state.series
        SeriesPickerDialog(appString(R.string.ui_unir_con_la_serie), series.name, listOf(series.imageUrl), originFicha = series.source to series.slug,
            onPick = { pick -> CardMenu.mergeWith(series.source, series.slug, pick); merging = false },
            onDismiss = { merging = false })
    }
}

/** Aplicaciones de YouTube para TV, en orden de preferencia: así el tráiler se abre en la que haya sin preguntar «Abrir con». */
private val TRAILER_APPS = listOf("org.smarttube.beta", "com.liskovsoft.smarttubetv", "com.liskovsoft.smarttubetv.beta", "com.google.android.youtube.tv", "com.google.android.youtube")

/** Abre el tráiler fuera de la app: en una app de YouTube de la TV si hay (sin selector) y, si no, en la que el sistema ofrezca; sin ninguna, lo dice. */
private fun openTrailer(context: android.content.Context, url: String) {
    val view = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    for (app in TRAILER_APPS) {
        try { context.startActivity(android.content.Intent(view).setPackage(app)); return } catch (_: android.content.ActivityNotFoundException) { }
    }
    try {
        context.startActivity(view)
    } catch (_: android.content.ActivityNotFoundException) {
        KaizenToast.show("No hay ninguna aplicación para abrir el tráiler")
    }
}

@Composable
private fun HeroButton(
    text: String,
    icon: Int,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    tint: Color = Color.White,
    /** Texto junto al icono (solo el tráiler lo lleva); sin él, el botón es solo el icono. */
    label: String = "",
    /** Color propio del botón (el del estado de la serie); sin él, el azul oscuro de siempre. */
    container: Color? = null,
    onClick: () -> Unit
) {
    val colors = if (primary) {
        ButtonDefaults.colors(containerColor = Accent, contentColor = Color.White,
            focusedContainerColor = AccentLight, focusedContentColor = Color.White)
    } else {
        ButtonDefaults.colors(containerColor = container ?: SurfaceMuted, contentColor = Color.White,
            // Con el color del estado, el foco lo aclara (antes solo se distinguía por el borde).
            focusedContainerColor = container?.let { androidx.compose.ui.graphics.lerp(it, Color.White, 0.25f) } ?: ActiveCard, focusedContentColor = Color.White)
    }
    Button(
        onClick = onClick,
        // La altura sale del relleno: con una altura fija el contenido de tv-material queda arriba, no centrado.
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        shape = ButtonDefaults.shape(RoundedCornerShape(7.dp)),
        colors = colors,
        border = ButtonDefaults.border(focusedBorder = androidx.tv.material3.Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(7.dp)))
    ) {
        if (icon != 0) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            if (label.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        val shown = text.ifEmpty { label }
        if (shown.isNotEmpty()) Text(shown, fontSize = Text13, fontWeight = FontWeight.Medium,
            style = CenteredTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = DetailSectionSpacing)) {
        Text(title, color = Color.White, fontSize = SectionTitleText, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = GutterStart, bottom = 8.dp))
        content()
    }
}

@Composable
private fun EpisodesRow(state: FichaViewModel.State.Ready, actions: FichaActions, openMenu: (Any) -> Unit) {
    // 0 = todos, 1 = vistos, 2 = pendientes (con lo visto al abrir o refrescar la ficha).
    var filter by remember { mutableStateOf(0) }
    val shown = remember(state.episodes, state.watch.seenEids, filter) {
        when (filter) {
            1 -> state.episodes.filter { it.eid in state.watch.seenEids }
            2 -> state.episodes.filter { it.eid !in state.watch.seenEids }
            else -> state.episodes
        }
    }
    Section(appString(R.string.ui_episodios)) {
        Row(Modifier.padding(start = GutterStart, end = GutterStart, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(R.string.ui_filtro_todos, R.string.ui_filtro_vistos, R.string.ui_filtro_pendientes).forEachIndexed { index, label ->
                KaizenButton(appString(label), onClick = { filter = index }, container = if (filter == index) Accent else SurfaceMuted, fontSize = Text13)
            }
            Spacer(Modifier.weight(1f))
            Text("${shown.size} episodios", color = TextSecondary, fontSize = Text13)
        }
        androidx.compose.runtime.key(filter) {
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (filter == 0) state.startIndex else 0)
            LazyRow(state = listState, contentPadding = PaddingValues(horizontal = GutterStart, vertical = ROW_VERTICAL_PADDING), horizontalArrangement = Arrangement.spacedBy(PosterHorizontalSpacing)) {
                items(shown, key = { it.eid }) { chapter ->
                    EpisodeCard(chapter, state.version, onLongClick = { openMenu(chapter.asRecord()) }) { actions.onPlay(chapter, ServerChoice.SETTING) }
                }
            }
        }
    }
}

@Composable
private fun RelationsRow(title: String, relations: List<Relation>, actions: FichaActions, openMenu: (Any) -> Unit) {
    Section(title) {
        LazyRow(contentPadding = PaddingValues(horizontal = GutterStart, vertical = ROW_VERTICAL_PADDING), horizontalArrangement = Arrangement.spacedBy(PosterHorizontalSpacing)) {
            val keys = uniqueKeys(relations.map { "${it.source}:${it.slug}:${it.relation}" })
            items(relations.size, key = { keys[it] }) { index ->
                val relation = relations[index]
                WideCard(coverCandidates(relation.aid, relation.slug, relation.imageUrl), relation.name, { actions.onOpen(relation.animeUrl) }, Modifier.width(300.dp),
                    { openMenu(relation.asSummary()) }, label = relationLabel(relation.relation), titleLines = 3)
            }
        }
    }
}

/** Menú de pulsación larga de una ficha unida: abrirla o separarla de esta serie (no se vuelve a juntar sola). */
@Composable
private fun JoinedFichaMenu(ficha: Catalog.JoinedFicha, onOpen: () -> Unit, onSeparate: () -> Unit, onDismiss: () -> Unit) {
    val first = rememberAutoFocus()
    KaizenDialog(onDismissRequest = onDismiss, title = "${ficha.sourceName} · ${ficha.slug}") {
        KaizenButton(appString(R.string.ui_separar_esta_serie), onClick = { onSeparate(); onDismiss() }, modifier = Modifier.fillMaxWidth().focusRequester(first))
        KaizenButton(appString(R.string.ui_abrir_ficha), onClick = { onOpen(); onDismiss() }, modifier = Modifier.fillMaxWidth())
        KaizenButton(appString(R.string.ui_cancelar), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}

/** «Series unidas»: las otras fichas de esta serie, cada una con su fuente; abrirla la carga en su propia fuente. */
@Composable
private fun JoinedRow(
    fichas: List<Catalog.JoinedFicha>, anilist: FichaData?, onAnilist: () -> Unit, actions: FichaActions,
    refocusKey: String?, onRefocused: () -> Unit, onLongClick: (Catalog.JoinedFicha) -> Unit
) {
    Section(appString(R.string.ui_series_unidas)) {
        LazyRow(contentPadding = PaddingValues(horizontal = GutterStart, vertical = ROW_VERTICAL_PADDING), horizontalArrangement = Arrangement.spacedBy(PosterHorizontalSpacing)) {
            // AniList es una ficha más de la serie (la que la enlaza con su anime); pulsarla lleva a la pestaña «Datos».
            if (anilist != null) item(key = "anilist") {
                val detail = listOfNotNull(anilist.anio?.toString(), anilist.episodios?.let { episodesLabel(it) }).joinToString(" · ")
                WideCard(listOfNotNull(anilist.portada), anilist.romaji ?: anilist.english ?: anilist.native ?: "AniList #${anilist.anilistId}", onAnilist, Modifier.width(300.dp),
                    label = "AniList", details = listOfNotNull(detail.takeIf { it.isNotEmpty() }?.let { it to Highlight }, "#${anilist.anilistId}" to White60))
            }
            val keys = uniqueKeys(fichas.map { "${it.sourceId}:${it.slug}" })
            items(fichas.size, key = { keys[it] }) { index ->
                val ficha = fichas[index]
                val url = AnimeSources.of(ficha.sourceId).animeUrl(ficha.slug)
                // Los episodios los da la propia fuente: se piden al verse la tarjeta.
                val episodes = rememberLoadable(ficha.sourceId to ficha.slug) { Catalog.episodeCount(ficha.sourceId, ficha.slug) ?: NO_EPISODES }.valueOrNull
                val requester = remember { FocusRequester() }
                if (refocusKey == "${ficha.sourceId}:${ficha.slug}") LaunchedEffect(refocusKey) {
                    delay(REFOCUS_AFTER_DIALOG)
                    withFrameNanos { }
                    runCatching { requester.requestFocus() }
                    // Una sola vez por separación: si no, al volver la tarjeta a componerse (desplazamientos) robaría el foco.
                    onRefocused()
                }
                val detail = listOfNotNull(ficha.year?.toString(), episodes?.let { episodesLabel(it) }).joinToString(" · ")
                WideCard(ficha.covers, ficha.name, { actions.onOpen(url) }, Modifier.width(300.dp).focusRequester(requester), { onLongClick(ficha) },
                    label = ficha.sourceName, details = listOfNotNull(detail.takeIf { it.isNotEmpty() }?.let { it to Highlight }))
            }
        }
    }
}

@Composable
private fun SimilarRow(similar: List<SeriesSummary>, actions: FichaActions, openMenu: (Any) -> Unit) {
    Section(appString(R.string.ui_series_recomendadas)) {
        LazyRow(contentPadding = PaddingValues(horizontal = GutterStart, vertical = ROW_VERTICAL_PADDING), horizontalArrangement = Arrangement.spacedBy(PosterHorizontalSpacing)) {
            val keys = uniqueKeys(similar.map { "${it.source}:${it.slug}" })
            items(similar.size, key = { keys[it] }) { index ->
                val summary = similar[index]
                // La misma tarjeta de serie que las listas (estado, favorito, romaji y panel de datos); la línea de detalle es el tipo, sin pedir la nota a ninguna web.
                CompositionLocalProvider(LocalCardWidth provides RelatedCardWidth) {
                    SeriesCard(summary.aid, summary.slug, summary.imageUrl, summary.name, null, summary.source, { actions.onOpen(summary.animeUrl) }, { openMenu(summary) }, detail = summary.type)
                }
            }
        }
    }
}

private fun relationLabel(id: Int): String = when (id) {
    8 -> "Historia Principal"
    1 -> "Precuela"
    2 -> "Secuela"
    4 -> "Versión Alternativa"
    3 -> "Ambientación Alternativa"
    7 -> "Historia Completa"
    5 -> "Historia Paralela"
    6 -> "Resumen"
    else -> "Otro"
}

private val SYNOPSIS_PADDING = 6.dp
