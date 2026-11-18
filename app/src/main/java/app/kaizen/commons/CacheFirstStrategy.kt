@file:OptIn(ExperimentalCoilApi::class)

package app.kaizen.commons

import coil3.annotation.ExperimentalCoilApi
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options

/**
 * Imágenes (portadas y fotogramas de capítulo): si ya están en el disco y son recientes se usan sin preguntar a la web.
 * La estrategia por defecto de Coil obedece las cabeceras de caché de cada servidor: con un `no-cache` o un `max-age`
 * corto pide la imagen otra vez cada vez que se muestra, y si la web no contesta (caída de Cloudflare, fuente en pausa)
 * la imagen no sale aunque esté guardada. Pasados [MAX_AGE_MS] vuelve al comportamiento por defecto, para que una portada
 * cambiada acabe actualizándose.
 */
object CacheFirstStrategy : CacheStrategy {
    const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

    /** Días de uso sin preguntar a la web; el ajuste «Caducidad de las portadas» lo cambia (ver [Tuning]). */
    @Volatile internal var maxAgeMs = MAX_AGE_MS

    /** Reloj (las pruebas lo adelantan). */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** Un 200 con una página HTML (desafío o «no disponible») en la URL de una imagen no es una imagen: no se guarda ni se reutiliza. */
    private fun isImage(response: NetworkResponse): Boolean {
        val type = response.headers["Content-Type"] ?: return true
        return type.startsWith("image/", ignoreCase = true)
    }

    /** La imagen guardada se reutiliza sin preguntar a la web. */
    internal fun isReusable(cached: NetworkResponse, seed: Any = cached.responseMillis): Boolean =
        cached.code in 200..299 && isImage(cached) && (offline() || clock() - cached.responseMillis < Timings.spread(maxAgeMs, seed))

    /** Sin conexión una imagen guardada vale aunque haya caducado: pedirla otra vez no puede salir bien y la portada se quedaría vacía. Las pruebas lo cambian. */
    @Volatile internal var offline: () -> Boolean = { NetworkState.isOffline }

    internal fun isStorable(response: NetworkResponse): Boolean = response.code in 200..299 && isImage(response)

    override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options): CacheStrategy.ReadResult =
        if (isReusable(cacheResponse, networkRequest.url)) CacheStrategy.ReadResult(cacheResponse)
        else CacheStrategy.DEFAULT.read(cacheResponse, networkRequest, options)

    // Se guarda toda respuesta correcta aunque el servidor diga `no-store`: sin eso no habría nada que reutilizar.
    override suspend fun write(
        cacheResponse: NetworkResponse?, networkRequest: NetworkRequest, networkResponse: NetworkResponse, options: Options
    ): CacheStrategy.WriteResult =
        if (isStorable(networkResponse)) CacheStrategy.WriteResult(networkResponse)
        else CacheStrategy.DEFAULT.write(cacheResponse, networkRequest, networkResponse, options)
}
