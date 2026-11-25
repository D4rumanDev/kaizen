package app.kaizen.tv.details

import app.kaizen.tv.ui.valueOrNull
import app.kaizen.tv.ui.rememberLoadable
import app.kaizen.tv.ui.theme.*

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.kaizen.tv.ui.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import app.kaizen.commons.runCatchingCoop
import app.kaizen.pojos.av1.Series
import app.kaizen.source.AnimeSources
import app.kaizen.source.Catalog
import app.kaizen.tv.ui.rememberAutoFocus
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.kaizen.R
import app.kaizen.commons.appString
import kotlin.time.Duration.Companion.seconds

/** Una portada de la serie y la fuente de la que viene. */
internal class GalleryCover(val label: String, val url: String)

/** Portadas de la serie en cada fuente activa que la tiene (la de su propia fuente primero), sin repetir. */
internal suspend fun galleryCovers(series: Series): List<GalleryCover> = withContext(Dispatchers.IO) {
    val found = LinkedHashMap<String, String>()
    found[series.imageUrl] = AnimeSources.of(series.source).badge
    for (source in AnimeSources.enabled) {
        val slug = runCatchingCoop { Catalog.slugIn(source, series.source, series.slug) }.getOrNull() ?: continue
        // Una web que calcula la portada con el id (animeav1) solo vale para sus propias series: el id de otra fuente es de otra cosa.
        if (source.id != series.source && source.coverUrl(0, slug) != source.coverUrl(1, slug)) continue
        found.putIfAbsent(source.coverUrl(series.aid, slug), source.badge)
    }
    // Solo https: la galería no carga nada local aunque un fichero o una web lo pidiera.
    found.filterKeys { it.startsWith("https://") }.map { (url, label) -> GalleryCover(label, url) }
}

/** Si la imagen se puede cargar (una URL que no existe en esa fuente no entra en la galería). */
private suspend fun loads(context: Context, url: String): Boolean = runCatchingCoop {
    // Tamaño mínimo (no se decodifica la imagen entera) y un tope de tiempo por fuente: una web lenta no retrasa a las demás.
    withTimeoutOrNull(LOAD_TIMEOUT) {
        SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).size(Size(PREFLIGHT_PX, PREFLIGHT_PX)).build()) is SuccessResult
    } == true
}.getOrDefault(false)

private val LOAD_TIMEOUT = 6.seconds
private const val PREFLIGHT_PX = 64

/**
 * Galería de portadas a pantalla completa: izquierda y derecha pasan de una fuente a otra, Atrás cierra. Se comprueban todas
 * al abrir y solo se enseñan las que cargan.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PosterGallery(candidates: List<GalleryCover>?, onChoose: (GalleryCover) -> Unit, onDismiss: () -> Unit, onRefresh: (GalleryCover) -> Unit = {}) {
    val context = LocalContext.current
    val covers = rememberLoadable(candidates) {
        candidates?.let { found -> coroutineScope { found.map { async(Dispatchers.IO) { it.takeIf { cover -> loads(context, cover.url) } } }.awaitAll().filterNotNull() } }
    }.valueOrNull
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focus = rememberAutoFocus()
        var index by remember { mutableIntStateOf(0) }
        val list = covers
        Box(
            Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().onPreviewKeyEvent { event ->
                val size = list?.size ?: 0
                if (event.type != KeyEventType.KeyDown || size == 0) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { index = (index - 1 + size) % size; true }
                    Key.DirectionRight -> { index = (index + 1) % size; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { list?.getOrNull(index.coerceIn(0, size - 1))?.let(onChoose); true }
                    // Arriba: vuelve a bajar esta portada de su web (las guardadas no se renuevan solas: casi nunca cambian).
                    Key.DirectionUp -> { list?.getOrNull(index.coerceIn(0, size - 1))?.let(onRefresh); true }
                    else -> false
                }
            }
        ) {
            when {
                list == null -> Text(appString(R.string.ui_buscando_portadas), color = Color.White, fontSize = Text18, modifier = Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(appString(R.string.ui_sin_portadas), color = Color.White, fontSize = Text18, modifier = Modifier.align(Alignment.Center))
                else -> {
                    val current = list[index.coerceIn(0, list.lastIndex)]
                    AsyncImage(model = current.url, contentDescription = current.label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(24.dp))
                    val hint = (if (list.size > 1) "  ·  ◀ ▶ cambiar" else "") + "  ·  OK usar esta portada  ·  ▲ renovar  ·  Atrás cerrar"
                    Text("${current.label} · ${index.coerceIn(0, list.lastIndex) + 1}/${list.size}$hint", color = White80, fontSize = Text14,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                }
            }
        }
    }
}
