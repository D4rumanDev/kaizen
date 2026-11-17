package app.kaizen.tv.details

import android.content.Context
import android.graphics.BitmapFactory
import app.kaizen.pojos.av1.Series
import app.kaizen.commons.Network
import app.kaizen.source.BestCovers
import app.kaizen.source.Catalog
import app.kaizen.source.userCover
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Ancho en dp de una tarjeta de portada: la imagen elegida debe cubrirlo en píxeles reales de la pantalla. */
internal const val COVER_TARGET_DP = 200

private const val PROBE_TIMEOUT_MS = 6_000L

// Las medidas bajan la imagen entera: a lo sumo dos a la vez para no quitar red a la ficha.
private val probeGate = Semaphore(2)

/**
 * La portada que mejor se ajusta a la pantalla para las tarjetas de [anime]: la elegida por el usuario, si la hay (no se guarda);
 * si no, entre la de su fuente y las de las demás fuentes que tienen la serie, la más pequeña que cubre una tarjeta en píxeles reales
 * o, si ninguna llega, la más ancha. Nunca una peor que la de su propia fuente: devuelve null si ya vale o no hay mejor. El resultado
 * se guarda en [BestCovers] y las tarjetas lo usan en adelante; una serie ya medida no se vuelve a medir.
 */
internal suspend fun sharpestCover(context: Context, anime: Series): String? = withContext(Dispatchers.IO) {
    // Todo en segundo plano: el catálogo y las reglas del usuario leen la base de datos, que no admite el hilo principal.
    userCover(anime.slug, anime.source)?.let { return@withContext it }
    BestCovers.get(anime.slug)?.let { return@withContext it.takeIf { url -> url != anime.imageUrl } }
    // Con calidad «equilibrada» o «ligera» (o la automática en un equipo justo o con red medida) no se bajan portadas de otras fuentes para compararlas.
    val quality = app.kaizen.commons.ImageQuality.level(context)
    if (!quality.comparesCovers) return@withContext null
    // Sin el catálogo recorrido las portadas de otras fuentes serían apuestas por slug: no se compara ni se guarda nada.
    if (!Catalog.isUsable()) return@withContext null
    val target = COVER_TARGET_DP * context.resources.displayMetrics.density * quality.widthScale
    run {
        val own = widthOf(context, anime.imageUrl)
        if (own >= target) {
            BestCovers.put(anime.slug, anime.imageUrl)
            return@run null
        }
        val others = galleryCovers(anime).map { it.url }.filter { it != anime.imageUrl }
        val wider = coroutineScope { others.map { url -> async { url to probeGate.withPermit { widthOf(context, url) } } }.awaitAll() }.filter { it.second > own }
        val best = (wider.filter { it.second >= target }.minByOrNull { it.second } ?: wider.maxByOrNull { it.second })?.first
        // Sin red (o si nada cargó) no se guarda «la propia»: se volverá a medir.
        if (best != null) BestCovers.put(anime.slug, best) else if (own > 0 && Network.isConnected) BestCovers.put(anime.slug, anime.imageUrl)
        best
    }
}

/**
 * Quita [url] de las cachés de imágenes (disco y memoria) para que se vuelva a bajar de su web la próxima vez que se pinte. Las portadas guardadas no se renuevan solas
 * ([app.kaizen.commons.CacheFirstStrategy]: por defecto «nunca, solo si falta»); esto es el botón «renovar» de la galería de la ficha.
 */
internal suspend fun renewCover(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
    // Sin red no se quita la copia: sería quedarse sin portada hasta que haya conexión.
    if (!Network.isConnected) return@withContext false
    val loader = SingletonImageLoader.get(context)
    runCatching { loader.diskCache?.remove(url) }
    runCatching { loader.memoryCache?.clear() }
    app.kaizen.commons.ImageMisses.forget(url)
    true
}

/** El ancho de la imagen guardada en la caché de disco de Coil, leído de su cabecera; null si no está o no se puede leer. */
private fun boundsWidth(disk: coil3.disk.DiskCache?, url: String): Int? = runCatching {
    disk?.openSnapshot(url)?.use { snapshot ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(snapshot.data.toString(), options)
        options.outWidth.takeIf { it > 0 }
    }
}.getOrNull()

/** Ancho real de la imagen de [url] (0 si no carga en 6 s o no se pudo medir). Se baja a la caché de disco, que luego reutiliza la tarjeta. */
private suspend fun widthOf(context: Context, url: String): Int = try {
    // Se baja con un tamaño mínimo (decodificación casi gratis) y el ancho real se lee de la caché de disco sin decodificar la imagen.
    val loader = SingletonImageLoader.get(context)
    val request = ImageRequest.Builder(context).data(url).size(Size(1, 1)).memoryCachePolicy(CachePolicy.DISABLED).build()
    val loaded = withTimeoutOrNull(PROBE_TIMEOUT_MS) { loader.execute(request) } is SuccessResult
    // Sin la cabecera en la caché no hay ancho fiable (la imagen decodificada es de 1 px): cuenta como no medida.
    if (loaded) boundsWidth(loader.diskCache, url) ?: 0 else 0
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    0
}
