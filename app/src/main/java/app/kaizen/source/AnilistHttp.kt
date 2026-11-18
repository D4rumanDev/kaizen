package app.kaizen.source

import app.kaizen.commons.AppHttpClient
import app.kaizen.commons.FallbackDns
import app.kaizen.commons.HostGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Fallo de red o de AniList que no es «sin resultado»: la cola para y vuelve a intentarlo más tarde. */
internal class AnilistUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * La única puerta de salida hacia AniList: un hilo de peticiones con ritmo propio (por debajo de su límite), el User-Agent de la app y
 * reintento cuando AniList pide esperar (429). Solo un 400 (AniList no admite ese texto) cuenta como respuesta vacía; una caída (5xx),
 * un bloqueo (403) o un 429 que no cede lanzan [AnilistUnavailable] para que la cola pare y no marque nada como «sin resultado».
 */
internal object AnilistHttp {
    private const val ENDPOINT = "https://graphql.anilist.co"
    /** El User-Agent de la app (ver [app.kaizen.commons.UserAgent]): el del navegador de la TV o uno aleatorio, nunca una marca propia. */
    internal val USER_AGENT: String get() = app.kaizen.commons.UserAgent.current()

    /** Entre dos peticiones: 30 por minuto (AniList anuncia 90, pero a veces lo baja a ~30). */
    internal var spacing = 2.seconds
    private const val MAX_RETRIES = 3
    private val MAX_WAIT = 90.seconds
    internal var retryWait = 5.seconds

    private val gate = Mutex()
    private var last = 0L

    @Volatile internal var clientOverride: OkHttpClient? = null
    private val defaultClient: OkHttpClient by lazy {
        AppHttpClient.streaming().newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).dns(FallbackDns).build()
    }
    internal val client: OkHttpClient get() = clientOverride ?: defaultClient

    internal var endpoint = ENDPOINT

    /** Envía [body] (JSON GraphQL) y devuelve el cuerpo de la respuesta, o `{}` si AniList responde 400 a esa consulta. */
    suspend fun post(body: String): String = gate.withLock {
        repeat(MAX_RETRIES + 1) { attempt ->
            val wait = last + spacing.inWholeMilliseconds - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            // Si HostGate tiene a AniList en pausa (bloqueo o caída seguidos) no se insiste: la cola para y vuelve más tarde.
            try { HostGate.allow(endpoint) } catch (e: HostGate.HostDownException) { throw AnilistUnavailable("AniList en pausa", e) }
            val request = Request.Builder().url(endpoint).header("User-Agent", USER_AGENT).header("Accept", "application/json")
                .post(body.toRequestBody("application/json".toMediaType())).build()
            try {
                val (code, text, retryAfter) = withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { Triple(it.code, it.body.string(), it.header("Retry-After")?.toLongOrNull()) }
                }
                last = System.currentTimeMillis()
                HostGate.report(endpoint, code)
                when {
                    code == 200 -> return text
                    code == 429 && attempt < MAX_RETRIES -> delay((retryAfter ?: 30L).seconds.coerceAtMost(MAX_WAIT) + 1.seconds)
                    code == 400 -> return "{}"
                    (code == 403 || code == 408 || code == 425 || code >= 500) && attempt < MAX_RETRIES -> delay(retryWait)
                    else -> throw AnilistUnavailable("AniList respondió $code")
                }
            } catch (e: IOException) {
                last = System.currentTimeMillis()
                if (e is AnilistUnavailable) throw e
                HostGate.reportFailure(endpoint, e)
                if (attempt == MAX_RETRIES) throw AnilistUnavailable("Sin conexión con AniList", e)
                delay(retryWait)
            }
        }
        throw AnilistUnavailable("AniList no respondió")
    }
}
