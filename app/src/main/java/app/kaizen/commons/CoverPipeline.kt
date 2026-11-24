package app.kaizen.commons

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import app.kaizen.App
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.NetworkResponse
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

/**
 * **El único camino de las portadas**: bajar → reducir → convertir a WebP → guardar, todo en un solo trabajo por dirección. Si varias tarjetas piden la
 * misma portada a la vez comparten ese trabajo (una descarga, una conversión, una escritura), y el trabajo sigue aunque la tarjeta que lo pidió salga de
 * la pantalla. Solo se hacen [PARALLEL] a la vez para no ahogar el procesador de la TV.
 *
 * Lo guardado es una sola copia por portada, en WebP de [WIDTH_PX] px de ancho y calidad [QUALITY], en `cache/covers/hot` o `cache/covers/warm` según su
 * [CoverTier]; las de [CoverTier.NONE] no se guardan. Cada carpeta tiene su tope de espacio y desaloja primero lo menos usado.
 */
object CoverPipeline {
    const val WIDTH_PX = 400
    const val QUALITY = 80
    private const val PARALLEL = 2
    private const val MAX_DOWNLOAD = 12L * 1024 * 1024
    private const val HOT_MAX_BYTES = 100L * 1024 * 1024
    private const val WARM_MAX_BYTES = 50L * 1024 * 1024
    private const val TRIM_EVERY = 25
    private val TOUCH_EVERY = 10.minutes

    /** Cómo acaba un trabajo: la portada guardada o el motivo del fallo. */
    sealed interface Outcome {
        class Ready(val file: File) : Outcome
        class Failed(val error: Throwable) : Outcome
    }

    /** No es un fallo: esta portada no se guarda por diseño ([CoverTier.NONE]). */
    object NotStored : IOException("portada sin caché de disco")

    @Volatile internal var clientProvider: () -> OkHttpClient = { App.imageHttpClient }
    @Volatile internal var rootProvider: () -> File = { File(App.context.cacheDir, "covers") }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(PARALLEL))
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<Outcome>>()
    private val writes = AtomicInteger()

    private fun dir(tier: CoverTier) = File(rootProvider(), if (tier == CoverTier.HOT) "hot" else "warm")

    internal fun nameOf(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".webp"

    /** La portada ya guardada de [url] (en cualquiera de las dos carpetas); la marca como usada ahora. null si no está. */
    fun cached(url: String): File? {
        val name = nameOf(url)
        for (tier in listOf(CoverTier.HOT, CoverTier.WARM)) {
            val file = File(dir(tier), name)
            if (file.isFile && file.length() > 0) {
                // «Último uso» para desalojar lo menos usado; basta con tocarlo cada tanto (cada petición de tarjeta pasa por aquí).
                val now = System.currentTimeMillis()
                if (now - file.lastModified() > TOUCH_EVERY.inWholeMilliseconds) file.setLastModified(now)
                return file
            }
        }
        return null
    }

    /** Cuántos bytes ocupan las portadas guardadas (para el registro y Ajustes). */
    fun sizeBytes(): Long = listOf(CoverTier.HOT, CoverTier.WARM).sumOf { tier -> dir(tier).listFiles().orEmpty().sumOf { it.length() } }

    /** Borra la portada guardada de [url] (renovarla, o una serie que pasa a [CoverTier.NONE]). */
    fun forget(url: String) {
        val name = nameOf(url)
        listOf(CoverTier.HOT, CoverTier.WARM).forEach { File(dir(it), name).delete() }
    }

    /** Vacía todo lo guardado. */
    fun clear() { runCatching { rootProvider().deleteRecursively() } }

    /**
     * La portada de [url] para [tier]: la guardada si está; si no, el trabajo único de bajar, convertir y guardar (compartido con quien la pida a la vez).
     * Con [CoverTier.NONE] no guarda nada: devuelve [Outcome.Failed] con [NotStored] para que quien llama siga por el camino normal sin disco.
     */
    suspend fun obtain(url: String, tier: CoverTier): Outcome {
        // Todo el acceso a disco, fuera del hilo principal: Coil llama a los interceptores desde el de quien pinta, en cada petición (también con la imagen ya en memoria).
        if (tier == CoverTier.NONE) { withContext(Dispatchers.IO) { forget(url) }; return Outcome.Failed(NotStored) }
        val hit = withContext(Dispatchers.IO) {
            cached(url)?.let { file ->
                // Una serie que pasa a la caché superior sube con ella.
                if (tier == CoverTier.HOT && file.parentFile?.name == "warm") move(file, url) ?: file else file
            }
        }
        if (hit != null) return Outcome.Ready(hit)
        val fresh = CompletableDeferred<Outcome>()
        val running = inflight.putIfAbsent(url, fresh)
        if (running != null) return running.await()
        scope.launch {
            // Otro trabajo pudo terminar entre la mirada de arriba y ganar el turno: se vuelve a mirar antes de bajar nada.
            val outcome = runCatching { cached(url)?.let { Outcome.Ready(it) } ?: download(url, tier) }.getOrElse { Outcome.Failed(it) }
            inflight.remove(url)
            fresh.complete(outcome)
        }
        return fresh.await()
    }

    private fun move(file: File, url: String): File? {
        val target = File(dir(CoverTier.HOT), nameOf(url)).also { it.parentFile?.mkdirs() }
        return if (file.renameTo(target)) target else null
    }

    /** El trabajo entero, en un solo paso: descarga, reduce, convierte y escribe (con un fichero temporal para no dejar nada a medias). */
    private fun download(url: String, tier: CoverTier): Outcome {
        val bytes = fetch(url)
        val webp = encode(bytes)
        val target = File(dir(tier), nameOf(url)).also { it.parentFile?.mkdirs() }
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeBytes(webp)
            if (!tmp.renameTo(target)) throw IOException("no se pudo guardar la portada")
        } finally {
            // Disco lleno o rename fallido: no se deja el temporal.
            if (tmp.exists()) tmp.delete()
        }
        // Cada tantas escrituras se mira el tope de las dos carpetas (la otra también crece al subir series de nivel).
        if (writes.incrementAndGet() % TRIM_EVERY == 0) { trim(CoverTier.HOT); trim(CoverTier.WARM) }
        DebugLog.detail("Portadas", "Guardada (${tier.name.lowercase()}): ${bytes.size / 1024} KB → ${webp.size / 1024} KB en WebP")
        return Outcome.Ready(target)
    }

    private fun fetch(url: String): ByteArray {
        clientProvider().newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(NetworkResponse(code = response.code, headers = NetworkHeaders.EMPTY))
            val type = response.header("Content-Type")
            // Algunos CDN sirven imágenes como application/octet-stream: se fía del decodificador. Una página HTML sí se rechaza.
            if (type != null && !type.startsWith("image/", ignoreCase = true) && !type.startsWith("application/octet-stream", ignoreCase = true)) throw IOException("no es una imagen ($type)")
            val body = response.body ?: throw IOException("respuesta sin cuerpo")
            if (body.contentLength() > MAX_DOWNLOAD) throw IOException("imagen demasiado grande")
            // Sin longitud conocida (respuesta troceada) el tope también vale: no se lee más de MAX_DOWNLOAD.
            val source = body.source()
            if (source.request(MAX_DOWNLOAD + 1)) throw IOException("imagen demasiado grande")
            return source.readByteArray()
        }
    }

    /** Decodifica ya reducida (sin cargar el original entero) y la deja en [WIDTH_PX] px de ancho como mucho, en WebP. */
    internal fun encode(bytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IOException("imagen no decodificable")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= WIDTH_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("imagen no decodificable")
        var scaled = decoded
        try {
            if (decoded.width > WIDTH_PX) scaled = Bitmap.createScaledBitmap(decoded, WIDTH_PX, (decoded.height * WIDTH_PX.toLong() / decoded.width).toInt().coerceAtLeast(1), true)
            val out = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            if (!scaled.compress(format, QUALITY, out)) throw IOException("no se pudo convertir a WebP")
            return out.toByteArray()
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    /** Si la carpeta pasa de su tope, quita las menos usadas hasta quedarse al 80 %. */
    internal fun trim(tier: CoverTier, max: Long = if (tier == CoverTier.HOT) HOT_MAX_BYTES else WARM_MAX_BYTES) {
        val files = dir(tier).listFiles { f -> f.name.endsWith(".webp") }.orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        if (total <= max) return
        for (file in files) {
            if (total <= max * 8 / 10) break
            total -= file.length()
            file.delete()
        }
    }
}

/**
 * Enchufa [CoverPipeline] a Coil: las peticiones con [CoverTier.KEY] (portadas de serie) se sirven de la copia WebP; el resto no se toca.
 * Un 404 o similar sale tal cual (lo anota [ImageErrorListener]); si falla otra cosa (sin red, formato raro) se sigue por el camino normal de Coil.
 */
object CoverInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val tier = request.extras[CoverTier.KEY] ?: return chain.proceed()
        val url = request.data as? String ?: return chain.proceed()
        return when (val outcome = CoverPipeline.obtain(url, tier)) {
            is CoverPipeline.Outcome.Ready -> {
                // La clave de la memoria sigue siendo la dirección web: lo que busca el resto de la app (portada ya cargada en otra tarjeta) la encuentra.
                val local = request.newBuilder().data(android.net.Uri.fromFile(outcome.file).toString()).memoryCacheKey(request.memoryCacheKey ?: url).diskCachePolicy(CachePolicy.DISABLED).build()
                chain.withRequest(local).proceed()
            }
            is CoverPipeline.Outcome.Failed -> when {
                outcome.error is HttpException -> ErrorResult(null, request, outcome.error)
                // Sin caché de disco (serie antigua): se baja por Coil cada vez y solo vive en memoria.
                outcome.error === CoverPipeline.NotStored -> chain.withRequest(request.newBuilder().diskCachePolicy(CachePolicy.DISABLED).build()).proceed()
                else -> chain.proceed()
            }
        }
    }
}
