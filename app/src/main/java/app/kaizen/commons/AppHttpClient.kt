package app.kaizen.commons

import app.kaizen.custom.TlsOnlySocketFactory
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Kaizen: cliente HTTP compartido de la app (antes NoSSLOkHttpClient, que creaba un cliente
 * nuevo y sin validación TLS en cada llamada). Valida según [TlsPolicy].
 */
object AppHttpClient {
    private val appCache by lazy { Cache(File(safeContext.cacheDir, "okhttpcache"), 10 * 1024 * 1024) }

    private val baseLazy = lazy {
        val socketFactory = TlsPolicy.sslContext.socketFactory
        OkHttpClient.Builder()
            .connectTimeout(KaizenSettings.timeoutTime, TimeUnit.SECONDS)
            .readTimeout(KaizenSettings.timeoutTime, TimeUnit.SECONDS)
            .sslSocketFactory(if (isMIUI) TlsOnlySocketFactory(socketFactory) else socketFactory, TlsPolicy.trustManager)
            .hostnameVerifier(TlsPolicy.hostnameVerifier)
            // Una petición sin User-Agent propio sale con el de la app (ver UserAgent), no con el «okhttp/x.y» por defecto.
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(if (request.header("User-Agent") != null) request else request.newBuilder().header("User-Agent", UserAgent.current()).build())
            }
            .build()
    }
    private val base: OkHttpClient by baseLazy

    /** Cliente con caché en disco, para páginas y API. */
    private val cached: OkHttpClient by lazy { base.newBuilder().cache(appCache).build() }

    fun get(): OkHttpClient = cached

    /** Sin caché: para vídeo (el proxy) y descargas grandes. */
    fun streaming(): OkHttpClient = base

    /** Cancela las peticiones en curso y cierra las conexiones abiertas (comparten dispatcher y pool). */
    fun shutdown() {
        if (!baseLazy.isInitialized()) return
        base.dispatcher.cancelAll()
        // Cerrar un socket TLS escribe en la red: en el hilo principal (la salida con doble Atrás) lanza
        // NetworkOnMainThreadException con el proveedor TLS del sistema.
        Thread({ base.connectionPool.evictAll() }, "kaizen-http-shutdown").start()
    }
}
