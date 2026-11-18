@file:OptIn(ExperimentalCoilApi::class)

package app.kaizen.tv

import android.content.Context
import android.net.Uri
import app.kaizen.commons.AppErrors
import app.kaizen.commons.KaizenStore
import app.kaizen.commons.NetworkState
import app.kaizen.domain.ConnectionStats.Cause
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.request.ImageRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Miniaturas de las filas de la pantalla de inicio. El launcher de la TV descarga por su cuenta la imagen de cada fila: si se le da una
 * dirección web, sin red (o con la web en pausa) pinta un «!». Aquí se le da una dirección `content://` que [ChannelArtProvider] sirve desde la
 * caché de disco de las imágenes de la app, así que se ve igual sin Internet. Solo se sirven las imágenes que la app ha publicado en una fila
 * ([register]): el proveedor es legible por otras aplicaciones y no debe dar el resto de la caché.
 */
object ChannelArt {
    private const val PREFS = "home_channel_art"
    private const val PARAM = "u"
    /** Cuánto se espera a bajar una miniatura que falta (con conexión) al publicar la fila. */
    private const val FETCH_TIMEOUT_MS = 8_000L

    fun authority(context: Context) = "${context.packageName}.channelart"

    /** La dirección que se le da al launcher para [url]. */
    internal fun uriFor(context: Context, url: String): Uri =
        Uri.Builder().scheme("content").authority(authority(context)).appendPath("art").appendQueryParameter(PARAM, url).build()

    /** La dirección web que pide [uri], o null si no es una de las nuestras. */
    internal fun urlOf(uri: Uri): String? = uri.getQueryParameter(PARAM)?.takeIf { uri.pathSegments.firstOrNull() == "art" }

    /** Solo se sirve lo publicado en una fila y por https: el resto de la caché no sale de la app. */
    internal fun isServable(url: String, published: Set<String>): Boolean = url.startsWith("https://") && url in published

    /** Qué hay publicado ahora por fila («recents», «continue»): se sustituye entero en cada sincronización. */
    @Synchronized fun register(kind: String, urls: Collection<String>) {
        runCatching { KaizenStore.prefs(PREFS).edit().putStringSet(kind, urls.toSet()).apply() }
    }

    private fun published(): Set<String> = runCatching { KaizenStore.prefs(PREFS).all.values.flatMap { (it as? Set<*>).orEmpty().filterIsInstance<String>() }.toSet() }.getOrDefault(emptySet())

    /** La imagen guardada de [url] (copia abierta al leer); null si no está o no se puede servir. */
    internal fun cachedFile(context: Context, url: String): File? {
        if (!isServable(url, published())) return null
        val cache = SingletonImageLoader.get(context).diskCache ?: return null
        return cache.openSnapshot(url)?.use { snapshot -> snapshot.data.toFile().takeIf { it.isFile && it.length() > 0 } }
    }

    /**
     * Lo que se le da al launcher para la imagen [url] de una fila. Con conexión, si la imagen no está guardada se baja ahora (una vez); sin
     * ella, se anota E28 y se deja la dirección web (no hay otra cosa que dar). Hilo de fondo.
     */
    fun forLauncher(context: Context, url: String): String {
        if (url.isBlank() || !url.startsWith("https://")) return url
        if (!NetworkState.isOffline && cachedFile(context, url) == null) fetch(context, url)
        val available = cachedFile(context, url) != null
        if (!available) {
            runCatching { AppErrors.record(Cause.HOME_CHANNEL, AppErrors.Area.IMAGE, "Miniatura sin guardar", context = url.substringAfter("://").substringBefore('/')) }
            return url
        }
        return uriFor(context, url).toString()
    }

    private fun fetch(context: Context, url: String) {
        runCatching {
            runBlocking {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) { SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).build()) }
            }
        }
    }
}
