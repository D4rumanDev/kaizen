@file:OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)

package app.kaizen.tv.ui

import app.kaizen.tv.ui.components.*

import app.kaizen.tv.ui.theme.*

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import app.kaizen.source.normalizeTitle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.kaizen.R
import app.kaizen.commons.KaizenSettings
import androidx.compose.ui.layout.ContentScale
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.painter.ColorPainter
import app.kaizen.source.SeriesLedger
import android.provider.Settings
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import app.kaizen.commons.appString
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/** Estados de lo que se puede activar (secciones, botones): mismo criterio en la lista de organizar y en las vistas previas. */
/** Color de los iconos de las tarjetas de [IconTextCard] (blanco, salvo en las que cambian de estado). */
internal val LocalIconTint = androidx.compose.runtime.staticCompositionLocalOf { Color.White }

/** Tamaño de las tarjetas de serie y capítulo; «Tamaño de la interfaz» (UiScale) las escala junto con el resto. */
internal val cardWidth: Dp get() = CardBaseWidth * KaizenSettings.cardScale

/** Ancho de las tarjetas en la rejilla donde están (se estira para llenar la fila; ver [fitColumns]); fuera de ella, [cardWidth]. */
internal val LocalCardWidth = androidx.compose.runtime.compositionLocalOf { cardWidth }

/** Columnas y ancho de tarjeta que llenan [availableDp] con tarjetas lo más cercanas posible a [cardDp] y [gapDp] entre ellas. */
internal fun fitColumns(availableDp: Float, cardDp: Float, gapDp: Float): Pair<Int, Float> {
    if (!availableDp.isFinite() || availableDp <= cardDp || cardDp <= 0f) return 1 to cardDp
    fun widthFor(columns: Int) = (availableDp - gapDp * (columns - 1)) / columns
    val fits = ((availableDp + gapDp) / (cardDp + gapDp)).toInt().coerceAtLeast(1)
    val best = listOf(fits, fits + 1).filter { widthFor(it) > 0f }.minByOrNull { kotlin.math.abs(widthFor(it) / cardDp - 1f) } ?: fits
    return best to widthFor(best)
}
internal val cardSpacing: Dp get() = PosterHorizontalSpacing * KaizenSettings.cardSpacing
/** Separación vertical entre filas de tarjetas. */
internal val cardRowSpacing: Dp get() = PosterVerticalSpacing * KaizenSettings.cardSpacing

/** Imagen con Coil: prueba cada URL por orden hasta que una carga (así una serie sin carátula en su fuente usa la de otra). */
@Composable
internal fun Cover(urls: List<String>, modifier: Modifier = Modifier, fit: Boolean = false, showLabel: Boolean = true, under: List<String> = emptyList(), reuseCached: Boolean = false) {
    val candidates = remember(urls) { urls.filter { it.isNotBlank() }.distinct() }
    var attempt by remember(candidates) { mutableIntStateOf(0) }
    // Ninguna URL cargó (o no hay ninguna): se avisa en vez de dejar un hueco gris sin explicación.
    var failed by remember(candidates) { mutableStateOf(candidates.isEmpty()) }
    val context = LocalContext.current
    val fade = Motion.ms(200)
    // Aparece con un fundido (solo si no estaba ya en la caché de memoria); la duración sigue a Ajustes › Interfaz › Animaciones.
    val request = remember(candidates, attempt, fade, reuseCached) {
        ImageRequest.Builder(context).data(candidates.getOrNull(attempt)).crossfade(fade).apply {
            // [reuseCached]: mientras llega la imagen a este tamaño se ve la ya cargada en otro (la de la tarjeta de la que se viene).
            if (reuseCached) SingletonImageLoader.get(context).memoryCache?.keys.orEmpty().let { keys -> candidates.firstNotNullOfOrNull { url -> keys.firstOrNull { it.key == url } } }?.let { placeholderMemoryCacheKey(it) }
        }.build()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        // [under]: imagen que se ve por defecto; la principal la sustituye con un fundido cuando carga (y si no carga, se queda esta).
        if (under.isNotEmpty()) Cover(under, Modifier.matchParentSize(), fit, showLabel = showLabel)
        AsyncImage(
            model = request,
            contentDescription = null,
            // El fondo mientras llega evita que la portada «salte» sobre un hueco vacío.
            placeholder = if (under.isEmpty()) ColorPainter(SurfaceMuted) else null,
            // Recortada para llenar la tarjeta, o entera (fit) para verla completa.
            contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            onError = { if (attempt < candidates.lastIndex) attempt++ else failed = true },
            onSuccess = { failed = false },
            modifier = Modifier.matchParentSize()
        )
        if (failed && showLabel && under.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(
                    painterResource(R.drawable.header_ic_emision),
                    contentDescription = null,
                    tint = White40,
                    modifier = Modifier.size(28.dp)
                )
                Text(appString(R.string.ui_sin_imagen), color = White50, fontSize = Text12, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Hueco con la forma de una tarjeta mientras llegan los datos: la de [MediaCard] (mismo tamaño y margen, portada con su borde y redondeo, y debajo solo la línea del título,
 * sin fondo de tarjeta), sin foco y con un latido suave.
 */
@Composable
private fun SkeletonCard(pulse: State<Float>) {
    val width = LocalCardWidth.current
    Column(Modifier.size(width, posterHeight(width)).graphicsLayer { alpha = pulse.value }.padding(CardInset)) {
        Box(Modifier.weight(1f).fillMaxWidth().clip(CardImageShape).background(CardColor).border(1.dp, Border, CardImageShape))
        Box(Modifier.fillMaxWidth().height(CardTextAreaHeight).padding(horizontal = CardTextInset, vertical = 8.dp), contentAlignment = Alignment.TopStart) {
            Box(Modifier.fillMaxWidth(0.8f).height(14.dp).clip(ShapeSm).background(CardColor))
        }
    }
}

/** El hueco de la cabecera de una sección (título y botón), para que la rejilla de huecos empiece a la misma altura que la de tarjetas. */
@Composable
private fun SkeletonHeader(pulse: State<Float>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).graphicsLayer { alpha = pulse.value }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 170.dp, height = 28.dp).clip(ShapeSm).background(CardColor))
        Spacer(Modifier.width(16.dp))
        Box(Modifier.size(width = 120.dp, height = 40.dp).clip(ShapeMd).background(CardColor))
    }
}

private const val SKELETON_PULSE_MS = 700
private const val SKELETON_COUNT = 24

/** Rejilla de huecos para las secciones que aún cargan: se llena la pantalla y no recibe el foco. */
@Composable
internal fun LoadingGrid(header: Boolean = false) {
    // Con las animaciones del sistema desactivadas los huecos quedan quietos.
    val context = LocalContext.current
    val animate = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    // Una sola animación para todos los huecos.
    val pulse = if (animate) {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0.45f, targetValue = 1f, label = "skeletonAlpha",
            animationSpec = infiniteRepeatable(tween(SKELETON_PULSE_MS), RepeatMode.Reverse)
        )
    } else {
        rememberUpdatedState(0.7f)
    }
    CardGrid(userScrollEnabled = false) {
        if (header) fullWidth { SkeletonHeader(pulse) }
        items(SKELETON_COUNT) { SkeletonCard(pulse) }
    }
}


/** Margen entre el borde de una tarjeta y su contenido; el texto añade [CardTextInset] a los lados. Igual en todas las tarjetas. */

/** Por debajo de este ancho la portada es tan baja que el panel de datos solo deja sitio para tres líneas de sinopsis. */

/** Esquinas de la imagen dentro de una tarjeta (algo menos redondas que la tarjeta, que las rodea con [CardInset]). */












/** Alto de una tarjeta de icono y texto de ancho [width]: proporcional, pero nunca menos de [IconCardMinHeight] para que no se corte el texto. */
internal fun iconCardHeight(width: Dp, tall: Boolean): Dp = if (tall) posterHeight(width) else (width * 130 / 200).coerceAtLeast(IconCardMinHeight)

/** Tarjeta de icono y texto (categorías de ajustes, fuentes, reintentar), como la rejilla de ajustes de SmartTube. */
@Composable
internal fun IconTextCard(
    title: String,
    onClick: () -> Unit,
    tall: Boolean = false,
    background: Color = CardColor,
    contentColor: Color = Color.White,
    /** Una línea de estado bajo el título (el valor actual, un recuento): quien mira la rejilla ve cómo está sin entrar. 14 sp, como el resto de textos secundarios. */
    summary: String? = null,
    /** Para quien necesita pedir el foco de la tarjeta (volver a ella al cerrar un panel). */
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    // Del ancho del resto de tarjetas (se adapta a la pantalla, ver CardGrid); más baja que una portada, o tan alta como ella con [tall].
    val width = LocalCardWidth.current
    // Con resumen hace falta algo más de alto para que quepan icono, título y dos líneas de estado.
    val height = iconCardHeight(width, tall).let { if (summary != null && !tall) maxOf(it, IconCardMinHeight + 28.dp) else it }
    FocusCard(Modifier.size(width, height).then(modifier), onClick, background = background) {
        Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            androidx.compose.runtime.CompositionLocalProvider(LocalIconTint provides contentColor) { icon() }
            Text(title, color = contentColor, fontSize = Text16, textAlign = TextAlign.Center, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            if (!summary.isNullOrBlank()) Text(summary, color = White70, fontSize = Text14, textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
internal fun TintedIcon(resource: Int) {
    Icon(painterResource(resource), contentDescription = null, tint = LocalIconTint.current, modifier = Modifier.size(48.dp))
}

/**
 * Texto centrado («Cargando…», «Sin resultados») sobre una pantalla que aún no tiene nada que mostrar. Con [illustration] lleva encima el gato de la app
 * en gris y algo transparente (un estado vacío, no una carga: así no compite con el contenido cuando lo haya).
 */
@Composable
internal fun CenterMessage(text: String, modifier: Modifier = Modifier, illustration: Boolean = false) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (illustration) {
                Image(
                    painterResource(R.drawable.empty_cat_kaizen), contentDescription = null, modifier = Modifier.size(EmptyIllustration),
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }), alpha = EMPTY_ILLUSTRATION_ALPHA
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(text, color = Color.White, fontSize = Text17, fontWeight = FontWeight.Normal)
        }
    }
}

private val EmptyIllustration = 120.dp
private const val EMPTY_ILLUSTRATION_ALPHA = 0.55f


/** Texto de botones: sin el relleno de fuente que lo deja más alto que el centro del botón. */
internal val CenteredTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)
)

/** Se llama cuando una tarjeta recibe el foco: a partir de ahí el panel lateral oculto puede abrirse (antes, el foco inicial no debe caer en él). */
internal val LocalOnCardFocus = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

internal val LocalStatuses = androidx.compose.runtime.compositionLocalOf<Map<String, app.kaizen.domain.StatusEntry>> { emptyMap() }

/** Estado de la serie [name] según el usuario, para marcar su tarjeta. */
@Composable
internal fun statusOfSeries(name: String, source: String? = null, slug: String? = null): app.kaizen.domain.SeriesStatus {
    val statuses = LocalStatuses.current
    // El libro de ids de serie carga después de arrancar: al cargar se vuelve a resolver (ver SeriesLedger.version).
    val ledger by SeriesLedger.version.collectAsState()
    return remember(name, source, slug, statuses, ledger) { app.kaizen.domain.SeriesStatuses.statusIn(statuses, name, source, slug) }
}

internal val LocalFavorites = androidx.compose.runtime.compositionLocalOf<Set<String>> { emptySet() }

/**
 * Da a las tarjetas de [content] los favoritos y los estados para que se marquen con el corazón y la etiqueta de estado. Las pantallas
 * que no cuelgan de [LandingScreen] (resultados de un género, ficha) lo necesitan: sin esto sus tarjetas salen sin marcas.
 */
@Composable
internal fun ProvideSeriesMarks(content: @Composable () -> Unit) {
    val favorites by remember {
        favoriteKeysFlow()
            .distinctUntilChanged()
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
    }.collectAsStateWithLifecycle(emptySet<String>())
    val statuses by app.kaizen.domain.SeriesStatuses.all.collectAsStateWithLifecycle()
    androidx.compose.runtime.CompositionLocalProvider(LocalFavorites provides favorites, LocalStatuses provides statuses, content = content)
}

/** Claves de los favoritos: el título normalizado, el `aid` y el id de serie (`#id`), como la ficha, que casa por `aid` o id de serie. */
internal fun favoriteKeys(list: List<app.kaizen.pojos.av1.FavoriteAV1>): Set<String> = HashSet<String>().also { keys ->
    list.forEach {
        keys += normalizeTitle(it.name)
        if (it.aid != 0) { keys += "@${it.aid}"; keys += "@${it.aid}|${it.slug}" }
        if (it.seriesId != 0L) keys += "#${it.seriesId}"
    }
}

/** [favoriteKeys] de los favoritos guardados; se recalcula al cambiar y al cargarse el libro de ids (de él sale el id de serie). */
internal fun favoriteKeysFlow(): kotlinx.coroutines.flow.Flow<Set<String>> =
    kotlinx.coroutines.flow.combine(app.kaizen.domain.Library.favorites, app.kaizen.source.SeriesLedger.version) { list, _ -> favoriteKeys(list) }

/**
 * Si una serie es favorita según [keys] (de [favoriteKeys]): por título normalizado, por `aid` o por id de serie, como el botón de la ficha.
 * Con el [slug] de la tarjeta, el `aid` solo casa con el favorito que tiene ese mismo slug: los ids internos de las series sin id propio (desde 1.000.000) pueden
 * coincidir entre series distintas (un favorito de otra serie encendía el corazón de «Tokyo Revengers», y al pulsar se añadía otro en vez de quitarlo).
 */
internal fun matchesFavorite(keys: Set<String>, name: String, aid: Int, seriesId: Long, slug: String? = null): Boolean =
    normalizeTitle(name) in keys ||
        (aid != 0 && (if (slug != null) "@$aid|$slug" else "@$aid") in keys) ||
        (seriesId != 0L && "#$seriesId" in keys)

/** Si la serie es favorita (ver [matchesFavorite]); se recompone al cargar el libro de ids de serie. */
@Composable
internal fun isFavoriteSeries(name: String, aid: Int = 0, source: String? = null, slug: String? = null): Boolean {
    val favorites = LocalFavorites.current
    // El libro de ids de serie carga después de arrancar: al cargar se vuelve a resolver (ver SeriesLedger.version).
    val ledger by SeriesLedger.version.collectAsState()
    return remember(name, aid, source, slug, favorites, ledger) {
        val seriesId = if (source != null && slug != null) app.kaizen.source.seriesIdOf(source, slug) else 0L
        matchesFavorite(favorites, name, aid, seriesId, slug)
    }
}

/**
 * Para los menús que se abren con una pulsación larga de OK: mientras el OK siga pulsado desde antes de abrirse, sus repeticiones y
 * su suelta no llegan a la opción enfocada (si no, mantener un poco más activaba la primera). Una pulsación nueva (un «abajo» de OK sin
 * repetición) vuelve a funcionar con normalidad; las demás teclas pasan siempre.
 */
@Composable
internal fun Modifier.ignoreHeldSelect(): Modifier {
    var freshPress by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { event ->
        val select = event.key == androidx.compose.ui.input.key.Key.DirectionCenter ||
            event.key == androidx.compose.ui.input.key.Key.Enter || event.key == androidx.compose.ui.input.key.Key.NumPadEnter
        when {
            !select -> false
            event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown -> {
                if (!freshPress && event.nativeKeyEvent.repeatCount > 0) true else { freshPress = true; false }
            }
            else -> !freshPress
        }
    }
}


/** FocusRequester que pide el foco al entrar en pantalla (el intento falla en silencio si el nodo aún no está listo). */
@Composable
internal fun rememberAutoFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return requester
}

/** Hora (y, con [showDate], fecha) que se actualiza sola cada medio minuto; respeta Ajustes › 24 h. */
@Composable
internal fun Clock(showDate: Boolean, fontSize: TextUnit) {
    val pattern = if (KaizenSettings.clock24h) "HH:mm" else "h:mm a"
    val timeFormat = remember(pattern) { SimpleDateFormat(pattern, Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val now by produceState(Date()) {
        while (true) {
            value = Date()
            delay(CLOCK_TICK_MS)
        }
    }
    val text = if (showDate) "${timeFormat.format(now)}  ·  ${dateFormat.format(now)}" else timeFormat.format(now)
    Text(text, color = Color.White, fontSize = fontSize)
}

private const val CLOCK_TICK_MS = 30_000L

/** Claves únicas para una lista de Compose: si dos elementos repiten clave, la repetición lleva un sufijo (repetir una clave bloquea la app). */
internal fun uniqueKeys(raw: List<String>): List<String> {
    val used = HashSet<String>()
    return raw.map { key ->
        var candidate = key
        var attempt = 1
        while (!used.add(candidate)) candidate = "$key#${++attempt}"
        candidate
    }
}
