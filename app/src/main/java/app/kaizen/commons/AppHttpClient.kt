package app.kaizen.commons

import app.kaizen.custom.TlsOnlySocketFactory
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import kotlin.time.Duration.Companion.seconds

/**
 * Kaizen: cliente HTTP compartido de la app (antes NoSSLOkHttpClient, que creaba un cliente
 * nuevo y sin validación TLS en cada llamada). Valida según [TlsPolicy].
 */
object AppHttpClient {
    private val appCache by lazy { Cache(File(safeContext.cacheDir, "okhttpcache"), 10 * 1024 * 1024) }

    /** Una petición sin User-Agent propio sale con el de la app (ver UserAgent), no con el «okhttp/x.y» por defecto. */
    private val userAgentInterceptor = Interceptor { chain ->
        val request = chain.request()
        chain.proceed(if (request.header("User-Agent") != null) request else request.newBuilder().header("User-Agent", UserAgent.current()).build())
    }

    private val baseLazy = lazy {
        val socketFactory = TlsPolicy.sslContext.socketFactory
        OkHttpClient.Builder()
            .connectTimeout(KaizenSettings.timeoutTime.seconds)
            .readTimeout(KaizenSettings.timeoutTime.seconds)
            .sslSocketFactory(if (isMIUI) TlsOnlySocketFactory(socketFactory) else socketFactory, TlsPolicy.trustManager)
            .hostnameVerifier(TlsPolicy.hostnameVerifier)
            .addInterceptor(userAgentInterceptor)
            .build()
    }
    private val base: OkHttpClient by baseLazy

    private val strictLazy = lazy { strictClient(base, KaizenSettings.timeoutTime, isMIUI) }

    /**
     * Sin pasar por [TlsPolicy]: validación del sistema siempre, aunque el usuario ponga «permisiva» para arreglar un servidor de vídeo. Para los
     * servicios de terceros (AniList, AniSkip), cuyas respuestas se guardan: un certificado falso no debe poder falsear enlaces ni fichas.
     */
    fun strict(): OkHttpClient = strictLazy.value

    /**
     * Cliente de validación TLS del sistema. Comparte dispatcher y pool con [shared] para que [shutdown] también lo corte; no comparte la fábrica de
     * sockets, así que una conexión abierta con la política permisiva no se reutiliza aquí.
     */
    internal fun strictClient(shared: OkHttpClient, seconds: Long, miui: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .dispatcher(shared.dispatcher)
            .connectionPool(shared.connectionPool)
            .connectTimeout(seconds.seconds)
            .readTimeout(seconds.seconds)
            .addInterceptor(userAgentInterceptor)
        if (miui) {
            val trust = TlsPolicy.systemTrustManager
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            builder.sslSocketFactory(TlsOnlySocketFactory(context.socketFactory), trust)
        }
        return builder.build()
    }

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
