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

    /** Solo la copia WebP de [url] (un fichero: no toca la caché de imágenes, que con el arranque en frío tarda mucho en abrirse); null si no está o no se puede servir. */
    private fun cachedWebp(url: String, servable: Set<String>, stats: Lookup?): File? {
        if (!isServable(url, servable)) return null
        val start = System.nanoTime()
        val webp = runCatching { app.kaizen.commons.CoverPipeline.cached(url) }.getOrNull()
        stats?.webpNs = stats.webpNs + (System.nanoTime() - start)
        if (webp != null) stats?.viaWebp++
        return webp
    }

    /** La imagen guardada de [url] (copia abierta al leer); null si no está o no se puede servir. [servable] es lo publicado (se lee una vez y se pasa si se piden varias). */
    internal fun cachedFile(context: Context, url: String, servable: Set<String> = published(), stats: Lookup? = null): File? =
        cachedWebp(url, servable, stats) ?: coilFile(context, url, servable, stats)

    /** La copia en la caché de imágenes de Coil. El launcher puede pedirla antes de que la app arranque (App.context aún sin asignar): Coil espera a la app. */
    private fun coilFile(context: Context, url: String, servable: Set<String>, stats: Lookup?): File? {
        if (!isServable(url, servable)) return null
        val loaderStart = System.nanoTime()
        val cache = app.kaizen.App.awaitImageLoader(context).diskCache
        val snapshotStart = System.nanoTime()
        stats?.loaderNs = stats.loaderNs + (snapshotStart - loaderStart)
        val file = cache?.openSnapshot(url)?.use { snapshot -> snapshot.data.toFile().takeIf { it.isFile && it.length() > 0 } }
        stats?.snapshotNs = stats.snapshotNs + (System.nanoTime() - snapshotStart)
        if (file != null) stats?.viaCoil++ else stats?.notFound++
        return file
    }

    /** Cuánto cuesta mirar las miniaturas y por qué camino se resolvió cada una (solo para medir: «Medición» del registro). */
    internal class Lookup {
        var viaWebp = 0
        var viaCoil = 0
        var notFound = 0
        var webpNs = 0L
        var loaderNs = 0L
        var snapshotNs = 0L
        private fun ms(ns: Long) = ns / 1_000_000
        fun describe() = "WebP $viaWebp en ${ms(webpNs)} ms, caché de imágenes $viaCoil (cargador ${ms(loaderNs)} ms, lectura ${ms(snapshotNs)} ms), sin guardar $notFound"
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
     * Publica qué imágenes de la fila [kind] se pueden servir (sustituye lo anterior de esa fila) y mira cuáles hay, sin bajar nada: primero la copia WebP (un
     * fichero) y, para lo que solo esté en la caché de imágenes de Coil, la pasa a WebP una vez ([app.kaizen.commons.CoverPipeline.adopt]); así la siguiente vez
     * es también un fichero (con la caché de Coil fría, abrirla costó 9 s por miniatura en la BRAVIA). Lo que no esté en ningún sitio queda en [Published.toFetch]
     * para [fetchMissing], que se hace después de escribir la fila.
     */
    suspend fun publish(context: Context, kind: String, urls: Collection<String>): Published {
        register(kind, urls)
        val servable = published()
        val stats = Lookup()
        val available = urls.filterTo(HashSet()) { cachedWebp(it, servable, stats) != null }
        for (url in missing(urls) { it in available }) {
            val file = coilFile(context, url, servable, stats) ?: continue
            app.kaizen.commons.CoverPipeline.adopt(url, file)
            available += url
        }
        return Published(missing(urls) { it in available }, available, stats.describe())
    }

    /**
     * Baja a la vez las miniaturas de [published] que faltan, cada una con su propio tiempo máximo ([FETCH_TIMEOUT]); un fallo no corta las demás. Sin conexión no hace
     * nada. Devuelve [published] mismo si no llegó ninguna. Antes se bajaban una a una bloqueando el hilo: con 20 filas, hasta minutos en una web lenta.
     */
    suspend fun fetchMissing(published: Published): Published {
        if (published.toFetch.isEmpty() || NetworkState.isOffline) return published
        coroutineScope {
            published.toFetch.map { url ->
                // Las filas del launcher son lo que más se mira: caché superior, y por el mismo camino único que las tarjetas.
                async { runCatchingCoop { withTimeoutOrNull(FETCH_TIMEOUT) { app.kaizen.commons.CoverPipeline.obtain(url, app.kaizen.commons.CoverTier.HOT) } } }
            }.awaitAll()
        }
        val arrived = published.toFetch.filter { runCatching { app.kaizen.commons.CoverPipeline.cached(it) }.getOrNull() != null }
        if (arrived.isEmpty()) return published
        return Published(published.toFetch - arrived.toSet(), published.available + arrived, published.detail)
    }

    /** Lo que dejó [publish]: las miniaturas que aún faltan y cuáles hay guardadas, para dárselas al launcher sin volver a mirar el disco. */
    class Published(val toFetch: List<String>, internal val available: Set<String>, val detail: String = "") {
        fun forLauncher(context: Context, url: String): String = launcherUrl(context, url) { url in available }
    }
}
