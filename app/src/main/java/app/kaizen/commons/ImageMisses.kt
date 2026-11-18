package app.kaizen.commons

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration

/**
 * Imágenes que se sabe que no existen (404/410) o que no se pueden decodificar (un `favicon.ico`): se anota la dirección con la hora y no se
 * vuelve a pedir hasta pasado [ttl]. Sin esto, cada vez que se abría «Fuentes» se pedían otra vez los iconos que ya habían dado 404.
 * Es solo la lógica (sin Android) para probarla; [ImageMisses] la guarda en disco.
 */
class MissLedger(private val ttl: Duration, private val max: Int, private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = ConcurrentHashMap<String, Long>()

    fun isMissing(url: String): Boolean {
        val at = entries[url] ?: return false
        if (clock() - at < ttl.inWholeMilliseconds) return true
        entries.remove(url, at)
        return false
    }

    /** Anota [url]; devuelve si era nueva (para guardar solo cuando cambia algo). Si hay más de [max], se olvidan las más antiguas. */
    fun mark(url: String): Boolean {
        val isNew = !isMissing(url)
        entries[url] = clock()
        if (entries.size > max) entries.entries.sortedBy { it.value }.take(entries.size - max).forEach { entries.remove(it.key, it.value) }
        return isNew
    }

    fun clear() = entries.clear()
    fun remove(url: String) { entries.remove(url) }
    fun load(saved: Map<String, Long>) { entries.putAll(saved) }
    fun snapshot(): Map<String, Long> = entries.toMap()
    val size: Int get() = entries.size
}

/** Las imágenes no disponibles, guardadas entre arranques (fichero de preferencias propio). */
object ImageMisses {
    private const val FILE = "kaizen_image_misses"
    private val TTL = 7.days
    private const val MAX = 500
    private val FLUSH = 5.seconds
    private val flushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    private val ledger by lazy {
        MissLedger(TTL, MAX).also { ledger ->
            runCatching {
                app.kaizen.commons.KaizenStore.prefs(FILE).all
                    .mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap().let(ledger::load)
            }
        }
    }

    fun isMissing(url: String) = ledger.isMissing(url)

    /** Cuántas imágenes no disponibles hay anotadas. */
    fun count(): Int = ledger.size

    /** Olvida todas (se vuelven a pedir). */
    fun clear() {
        ledger.clear()
        AppScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.kaizen.commons.KaizenStore.prefs(FILE).edit().clear().commit() } }
    }

    /** Olvida una imagen concreta (se vuelve a pedir): la usa «renovar portada». */
    fun forget(url: String) {
        ledger.remove(url)
        AppScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.kaizen.commons.KaizenStore.prefs(FILE).edit().remove(url).commit() } }
    }

    fun mark(url: String) {
        if (!ledger.mark(url)) return
        // Un solo volcado a disco unos segundos después: con cientos de 404 a la vez no se escribe una vez por cada uno (un apply() por imagen ya causó un ANR en otro sitio).
        if (!flushScheduled.compareAndSet(false, true)) return
        AppScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(FLUSH)
            flushScheduled.set(false)
            runCatching {
                val edit = app.kaizen.commons.KaizenStore.prefs(FILE).edit().clear()
                ledger.snapshot().forEach { (k, v) -> edit.putLong(k, v) }
                edit.commit()
            }
        }
    }

    /** Los iconos que la pantalla Fuentes prueba uno tras otro: que falte uno es lo esperado, no un fallo que anotar. */
    fun isProbe(url: String): Boolean = url.endsWith("/apple-touch-icon.png") || url.endsWith("/favicon.ico")
}

/** Lo que devuelve la carga de una imagen ya sabida como no disponible, sin tocar la red. */
class MissingImageException(url: String) : java.io.IOException("Imagen no disponible: $url")

/** Corta las peticiones de imágenes que se sabe que no existen (ver [ImageMisses]). */
object MissingImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String
        if (url != null && ImageMisses.isMissing(url)) return ErrorResult(null, chain.request, MissingImageException(url))
        return chain.proceed()
    }
}
