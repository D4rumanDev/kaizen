@file:OptIn(ExperimentalCoilApi::class)

package app.kaizen.tv

import android.content.Context
import android.net.Uri
import app.kaizen.commons.AppErrors
import app.kaizen.commons.KaizenStore
import app.kaizen.commons.NetworkState
import app.kaizen.commons.runCatchingCoop
import app.kaizen.domain.ConnectionStats.Cause
import coil3.annotation.ExperimentalCoilApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Duration.Companion.seconds

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
    private val FETCH_TIMEOUT = 8.seconds

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

    /** La imagen guardada de [url] (copia abierta al leer); null si no está o no se puede servir. [servable] es lo publicado (se lee una vez y se pasa si se piden varias). */
    internal fun cachedFile(context: Context, url: String, servable: Set<String> = published()): File? {
        if (!isServable(url, servable)) return null
        // El launcher puede pedir una imagen antes de que la app arranque (App.context aún sin asignar): entonces no hay copia WebP que mirar y se sigue por Coil, que espera a la app.
        runCatching { app.kaizen.commons.CoverPipeline.cached(url) }.getOrNull()?.let { return it }
        val cache = app.kaizen.App.awaitImageLoader(context).diskCache ?: return null
        return cache.openSnapshot(url)?.use { snapshot -> snapshot.data.toFile().takeIf { it.isFile && it.length() > 0 } }
    }

    /**
     * Lo que se le da al launcher para la imagen [url] de una fila. No descarga nada (la fila ya llamó a [publish]): si la imagen no está
     * guardada se anota E28 y se deja la dirección web (no hay otra cosa que dar).
     */
    fun forLauncher(context: Context, url: String): String = launcherUrl(context, url) { cachedFile(context, url) != null }

    private inline fun launcherUrl(context: Context, url: String, isAvailable: () -> Boolean): String {
        if (url.isBlank() || !url.startsWith("https://")) return url
        if (!isAvailable()) {
            runCatching { AppErrors.record(Cause.HOME_CHANNEL, AppErrors.Area.IMAGE, "Miniatura sin guardar", context = url.substringAfter("://").substringBefore('/')) }
            return url
        }
        return uriFor(context, url).toString()
    }

    /** Las de [urls] por https que no están ya guardadas según [isCached], sin repetir. */
    internal fun missing(urls: Collection<String>, isCached: (String) -> Boolean): List<String> =
        urls.asSequence().filter { it.startsWith("https://") }.distinct().filterNot(isCached).toList()

    /**
     * Publica qué imágenes de la fila [kind] se pueden servir (sustituye lo anterior de esa fila) y, con conexión, baja a la vez las que
     * faltan, cada una con su propio tiempo máximo ([FETCH_TIMEOUT]). Antes se bajaban una a una bloqueando el hilo: con 20 filas, hasta
     * minutos en una web lenta, con el cerrojo de la sincronización cogido. Un fallo de una imagen no corta las demás.
     */
    suspend fun publish(context: Context, kind: String, urls: Collection<String>): Published {
        register(kind, urls)
        // Lo publicado se lee una vez y cada imagen se mira en el disco una vez (antes, otra más al dárselas al launcher).
        val servable = published()
        val available = urls.filterTo(HashSet()) { cachedFile(context, it, servable) != null }
        // «Faltan» cuenta también sin conexión (no se pueden bajar, pero la fila no está al día mientras falten): así no se omite el rehacerla.
        val todo = missing(urls) { it in available }
        if (NetworkState.isOffline) return Published(todo.size, available)
        if (todo.isNotEmpty()) {
            coroutineScope {
                todo.map { url ->
                    // Las filas del launcher son lo que más se mira: caché superior, y por el mismo camino único que las tarjetas.
                    async { runCatchingCoop { withTimeoutOrNull(FETCH_TIMEOUT) { app.kaizen.commons.CoverPipeline.obtain(url, app.kaizen.commons.CoverTier.HOT) } } }
                }.awaitAll()
            }
            todo.filterTo(available) { cachedFile(context, it, servable) != null }
        }
        return Published(todo.size, available)
    }

    /** Lo que dejó [publish]: cuántas miniaturas faltaban (y se pidieron) y cuáles hay guardadas, para dárselas al launcher sin volver a mirar el disco. */
    class Published(val missing: Int, private val available: Set<String>) {
        fun forLauncher(context: Context, url: String): String = launcherUrl(context, url) { url in available }
    }
}
