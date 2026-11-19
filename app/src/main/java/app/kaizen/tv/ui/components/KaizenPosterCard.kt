@file:OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui.components

import app.kaizen.commons.KaizenProgress
import app.kaizen.source.seriesIdOf
import app.kaizen.tv.ui.theme.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import app.kaizen.commons.KaizenSettings
import app.kaizen.commons.runCatchingCoop
import app.kaizen.pojos.av1.RecentEpisode
import app.kaizen.pojos.av1.Recommended
import app.kaizen.pojos.av1.Record
import app.kaizen.source.AnilistCard
import app.kaizen.source.AnilistCards
import app.kaizen.source.AnimeAv1
import app.kaizen.source.AnimeSources
import app.kaizen.source.Catalog
import app.kaizen.source.SeriesLedger
import app.kaizen.source.coverCandidates
import app.kaizen.source.userCover
import app.kaizen.tv.cards.SCORE_LOADING
import app.kaizen.tv.cards.SCORE_NONE
import app.kaizen.tv.cards.formatScore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import app.kaizen.tv.ui.*
import app.kaizen.tv.ui.theme.*

/**
 * Contenedor enfocable con mando: fondo más claro y borde blanco al recibir el foco (y un poco más grande si «Animación
 * al enfocar» está activa). La pulsación larga abre el menú de la tarjeta.
 */
@Composable
internal fun FocusCard(
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    background: Color = CardColor,
    onFocusChange: ((Boolean) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val onCardFocus = LocalOnCardFocus.current
    // El ajuste se lee en cada cambio de foco, no una sola vez al crear la tarjeta: con la ventana de Configuración abierta las tarjetas de detrás siguen creadas y conservaban el zoom aunque se apagara.
    val scale by animateFloatAsState(if (focused && KaizenSettings.focusZoom) 1.06f else 1f, animationSpec = Motion.spec(150), label = "focusScale")
    Column(
        modifier
            // La escala se lee al dibujar: la animación no recompone la tarjeta entera en cada fotograma.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused; onFocusChange?.invoke(it.isFocused); if (it.isFocused) onCardFocus() }
            .shadow(if (focused) 8.dp else 0.dp, CardShape, clip = false)
            .clip(CardShape)
            .background(if (focused) CardColorFocused else background)
            .then(if (focused) Modifier.border(BorderStroke(3.dp, AccentLight), CardShape) else Modifier)
            // El contenido queda dentro del borde del foco: ni la imagen ni el texto quedan bajo la línea azul.
            .padding(CardInset)
            // La tecla Menú del mando abre el menú de la tarjeta sin tener que mantener OK pulsado.
            .onKeyEvent { event ->
                if (onLongClick != null && event.key == Key.Menu) {
                    if (event.type == KeyEventType.KeyUp) onLongClick()
                    true
                } else {
                    false
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        content = content
    )
}

/** Lo que AniList da de la serie para su tarjeta (nota, portada, romaji y panel), de la base local; null si no está enlazada o el ajuste está apagado. Un solo sitio para todas las lecturas. */
@Composable
private fun rememberAnilistCard(source: String?, slug: String?): AnilistCard? {
    if (source == null || slug == null) return null
    val card by produceState<AnilistCard?>(AnilistCards.peek(source, slug), source, slug) { value = AnilistCards.of(source, slug) }
    return card
}

/** ¿Se desenfoca la portada de esta serie? Con el modo parental encendido: si AniList la da por sensible o está marcada a mano. */
@Composable
internal fun rememberSensitive(source: String?, slug: String?): Boolean {
    if (!KaizenSettings.sensitiveBlur) return false
    return slug != null && ("$source|$slug" in KaizenSettings.sensitiveMarked || rememberAnilistCard(source, slug)?.sensitive == true)
}

/** El título de la tarjeta: el romaji de AniList si la serie está enlazada (y el ajuste encendido). */
private fun romajiOr(card: AnilistCard?, name: String): String = card?.romaji?.takeIf { it.isNotBlank() } ?: name

/** La nota de la serie: la de AniList («…» mientras se pide, «—» si la serie no está enlazada o no tiene nota). Las notas de las webs se descartan. */
@Composable
private fun scoreLine(slug: String, source: String?): State<String> {
    val seen = if (source != null) AnilistCards.peek(source, slug) else null
    val initial = when {
        source == null -> SCORE_NONE
        seen != null -> seen.score?.let { formatScore(it / 20.0) } ?: SCORE_NONE
        else -> SCORE_LOADING
    }
    return produceState(initial, slug, source) {
        value = initial
        if (source == null) return@produceState
        val stars = AnilistCards.of(source, slug)?.score?.let { it / 20.0 }
        value = if (stars != null) formatScore(stars) else SCORE_NONE
    }
}

/**
 * Las portadas de una tarjeta: la de AniList (mejor calidad) va tras la que eligió el usuario y antes de las de las fuentes, si la serie
 * está enlazada y el ajuste «Datos de AniList en la ficha» está encendido. Es una lectura local; sin ella, las de siempre.
 */
@Composable
internal fun rememberCovers(source: String?, slug: String, base: List<String>): List<String> {
    val anilist = rememberAnilistCard(source, slug)
    return remember(base, anilist) {
        val cover = anilist?.cover
        if (cover == null) base else (listOfNotNull(userCover(slug, source), cover) + base).distinct()
    }
}

/** Tarjeta de serie: portada, nombre y nota (la que trae o, si no, la que se pide al pintarla); con [detail] va esa línea en lugar de la nota (sin pedirla a ninguna web). */
@Composable
internal fun SeriesCard(
    aid: Int,
    slug: String,
    image: String,
    name: String,
    score: Double?,
    source: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    markFavorite: Boolean = true,
    detail: String? = null,
    /** Fuerza el corazón: en «Favoritos» todas lo son (esa lista no consulta el estado de cada una). */
    isFavorite: Boolean? = null
) {
    val line = detail ?: scoreLine(slug, source).value
    val covers = rememberCovers(source, slug, remember(aid, slug, image) { coverCandidates(aid, slug, image) })
    val favorite = isFavorite ?: (markFavorite && isFavoriteSeries(name, aid, source, slug))
    MediaCard(covers, name, line, onClick, onLongClick, detailColor = if (detail != null) White60 else ScoreColor, favorite = favorite, source = source, slug = slug)
}

/** Tarjeta de las sugerencias: la nota y la fuente donde está la serie se resuelven al pintarla, una vez por serie. */
@Composable
internal fun RecommendedCard(data: Recommended, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val state by produceState(remember(data) { recommendedState(data) }, data) {
        if (data.scoreRequested) return@produceState
        // Aunque la tarjeta salga de pantalla, la consulta termina y deja el resultado en [data].
        withContext(NonCancellable) {
            // Las sugerencias son slugs de animeav1: en la fuente activa la serie puede llamarse distinto.
            val found = data.resolved ?: AnimeSources.enabled.firstNotNullOfOrNull { source ->
                Catalog.slugAny(source, data.slug)?.let { source to it }
            }
            data.resolved = found
            // La nota es la de AniList de la serie donde se encontró; sin enlace o sin ficha no hay nota.
            data.score = found?.let { (source, slug) -> runCatchingCoop { AnilistCards.of(source.id, slug) }.getOrNull()?.score?.let { it / 20.0 } }
            // Solo al terminar: una tarjeta que vuelve a pantalla durante la consulta la repite en vez de quedarse en «—».
            data.scoreRequested = true
        }
        value = recommendedState(data)
    }
    // La ficha donde se encontró (o la sugerida): así el estado también se reconoce por ficha e id de serie.
    val ficha = data.resolved?.let { (source, slug) -> source.id to slug } ?: (AnimeAv1.ID to data.slug)
    MediaCard(state.first, data.name, state.second, onClick, onLongClick, detailColor = ScoreColor, favorite = isFavoriteSeries(data.name, data.aid, ficha.first, ficha.second), source = ficha.first, slug = ficha.second)
}

private fun recommendedState(data: Recommended): Pair<List<String>, String> {
    val found = data.resolved
    val covers = if (found != null && (found.second != data.slug || found.first.id != AnimeAv1.ID)) {
        listOf(found.first.coverUrl(data.aid, found.second), data.imageUrl)
    } else coverCandidates(data.aid, data.slug, data.imageUrl)
    val line = if (data.scoreRequested) formatScore(data.score).ifEmpty { SCORE_NONE } else SCORE_LOADING
    return covers to line
}

/**
 * La tarjeta de las series y los capítulos: portada y nombre (hasta dos líneas). Sobre la imagen, para no saturarla: el estado con su nombre (arriba a la izquierda) y el corazón de
 * [favorite] (abajo a la derecha); debajo, el título y el dato principal [line] (el capítulo o la nota, en [detailColor]). Con [seen] un check verde junto al capítulo, debajo de la imagen (la portada no cambia); con [progress], la
 * barra de lo visto (ancho completo = duración completa). Todas las tarjetas de serie, capítulo y sugerencia pasan por aquí.
 */
@Composable
internal fun MediaCard(
    covers: List<String>,
    title: String,
    line: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    detailColor: Color = AccentLight,
    seen: Boolean = false,
    progress: Float? = null,
    favorite: Boolean = false,
    resuming: Boolean = false,
    source: String? = null,
    slug: String? = null,
    /** Una serie que empieza: la etiqueta NUEVA arriba a la izquierda (solo el capítulo 1 en la lista de recientes). */
    isNew: Boolean = false,
    /** El número del capítulo que enseña la tarjeta: si es el último que AniList da para la serie, lleva la etiqueta FINAL. */
    chapterNumber: Double? = null
) {
    val width = LocalCardWidth.current
    val status = statusOfSeries(title, source, slug)
    val anilist = rememberAnilistCard(source, slug)
    val shown = romajiOr(anilist, title)
    var focused by remember { mutableStateOf(false) }
    val total = anilist?.info?.episodes
    val isFinal = !isNew && chapterNumber != null && total != null && total > 1 && chapterNumber == total.toDouble()
    FocusCard(Modifier.size(width, posterHeight(width)), onClick, onLongClick, background = Color.Transparent, onFocusChange = { focused = it }) {
        // Con la barra de lo visto pegada debajo, la imagen solo redondea arriba para que no haya muescas.
        val imageShape = if (progress != null) RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp) else CardImageShape
        // Como en el diseño: el estado arriba a la izquierda, el corazón arriba a la derecha, el capítulo abajo a la izquierda y el check de visto abajo a la derecha.
        Box(Modifier.weight(1f).fillMaxWidth().clip(imageShape).border(1.dp, Border, imageShape)) {
            Cover(covers, Modifier.fillMaxSize(), blur = rememberSensitive(source, slug))
            if (isNew || isFinal || status != app.kaizen.domain.SeriesStatus.NONE) {
                Column(Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 6.dp, end = 34.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
                    if (isNew) NewBadge() else if (isFinal) NewBadge("FINAL")
                    if (status != app.kaizen.domain.SeriesStatus.NONE) StatusBadge(status)
                }
            }
            FavoriteMark(Modifier.align(Alignment.TopEnd), favorite = favorite)
            val detail = line.takeIf { it.isNotBlank() && it != SCORE_LOADING && it != SCORE_NONE }
            if (detail != null) ChapterPill(shortChapter(detail), Color.White, Modifier.align(Alignment.BottomStart), reserveEnd = if (seen || resuming) 30.dp else 6.dp)
            // Acabado: check verde. Empezado y sin acabar: triángulo azul y, debajo, la barra de lo visto.
            if (resuming) ResumeBadge(Modifier.align(Alignment.BottomEnd)) else if (seen) SeenBadge(Modifier.align(Alignment.BottomEnd))
            // Con el foco quieto, los datos de AniList sobre la portada (solo si la serie está enlazada; ver [FocusInfoPanel]).
            val info = anilist?.info
            if (focused && info != null && KaizenSettings.focusPanel) {
                FocusInfoPanel(info, anilist?.score, Modifier.fillMaxSize(), synopsisLines = if (width < NarrowCardWidth) 3 else 6, status = status)
            }
        }
        if (progress != null) CardProgressBar(progress)
        // La nota que aún se pide («★ …») o que no existe («★ —») no lleva pastilla: aparece al llegar. Debajo de la imagen solo va el título.
        CardTexts(shown)
    }
}

/** "Episodio 5" y "Ep. 5" pasan a "E5"; lo demás queda igual. */
internal fun shortChapter(text: String): String = Regex("^(?:Episodio|Ep\\.?|Capítulo|Cap\\.?)\\s*(\\d+(?:[.,]\\d+)?)\\s*$", RegexOption.IGNORE_CASE).find(text.trim())?.let { "E${it.groupValues[1]}" } ?: text

@Composable
internal fun RecordCard(record: Record, onClick: () -> Unit, onLongClick: (() -> Unit)?, line: String = record.chapter(), progress: Float? = null) {
    val covers = rememberCovers(record.source, record.slug, remember(record.aid, record.slug) { coverCandidates(record.aid, record.slug, record.imageUrl()) })
    MediaCard(covers, record.name, line, onClick, onLongClick, progress = progress, favorite = isFavoriteSeries(record.name, record.aid, record.source, record.slug), source = record.source, slug = record.slug)
}

/** «Seguir viendo», compacta: portada pequeña a la izquierda y, a la derecha, título, capítulo con el porcentaje visto y la barra de progreso. */
@Composable
internal fun ContinueCard(record: Record, onClick: () -> Unit, onLongClick: (() -> Unit)?, line: String, progress: Float?) {
    val covers = rememberCovers(record.source, record.slug, remember(record.aid, record.slug) { coverCandidates(record.aid, record.slug, record.imageUrl()) })
    WideCard(covers, record.name, onClick, Modifier.fillMaxWidth(), onLongClick, details = listOf(line to AccentLight), titleLines = 1, coverWidth = 52.dp, height = 68.dp, progress = progress)
}

/** Capítulo reciente: el ojo sigue a Room, así que se actualiza al marcarlo o desmarcarlo desde cualquier pantalla. */
@Composable
internal fun RecentCard(data: RecentEpisode, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    // El id de serie sale del registro en memoria, que se carga al arrancar: al cambiar se vuelve a consultar el visto.
    val ledger by SeriesLedger.version.collectAsState()
    val seen by remember(data, ledger) { data.seenFlow() }.collectAsState(initial = false)
    // Empezado y sin acabar: la barra de lo visto y el triángulo azul, no el check verde. Se relee cuando cambia el progreso guardado (al salir del reproductor).
    val changes by KaizenProgress.changes.collectAsState()
    val started = remember(data, ledger, changes) { KaizenProgress.startedOf(KaizenProgress.Key(seriesIdOf(data.source, data.slug), data.number), data.eid) }
    val watch = cardWatchOf(seen, started)
    val covers = rememberCovers(data.source, data.slug, remember(data.aid, data.slug) { coverCandidates(data.aid, data.slug, data.animeImageUrl) })
    MediaCard(covers, data.name, data.chapter, onClick, onLongClick, seen = watch == CardWatch.SEEN, progress = started?.fraction?.toFloat(), resuming = watch == CardWatch.RESUMING, favorite = isFavoriteSeries(data.name, data.aid, data.source, data.slug), source = data.source, slug = data.slug, isNew = data.number == 1.0, chapterNumber = data.number)
}
