package app.kaizen.source

import app.kaizen.commons.AppHttpClient
import app.kaizen.commons.FallbackDns
import app.kaizen.commons.HostGate
import app.kaizen.commons.runCatchingCoop
import app.kaizen.videoservers.Server
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Cliente HTTP común de las fuentes que se leen por scraping: User-Agent de navegador, tiempos acotados y turno por host
 * ([HostGate]). Las fuentes no deben construir sus propias peticiones. Resuelve los nombres con el DNS de la red y, si no se
 * consigue conectar, reintenta una vez con las IP que da 9.9.9.9 ([FallbackDns]).
 */
internal object SourceHttp {
    /** Las webs sanas responden en menos de un segundo: pasados 12 s la pantalla ya no debe esperar a un servidor caído. */
    private val READ_TIMEOUT = HostGate.REQUEST_TIMEOUT

    /** Páginas del catálogo y de la API: pesan más y se piden en segundo plano. */
    private val HEAVY_READ_TIMEOUT = 30.seconds

    /** Conectar es rápido o no se consigue: el relevo de DNS no puede esperar medio minuto. */
    private val CONNECT_TIMEOUT = 6.seconds

    /** Tope del cuerpo si la fuente no pide otro (Jsoup cortaba en 2 MB): un servidor roto no puede llenar la memoria de la tele. */
    private const val MAX_BODY_BYTES = 8 * 1024 * 1024

    /** Para las pruebas: cliente base sin Android (por defecto el de la app). */
    @Volatile internal var clientOverride: OkHttpClient? = null
        set(value) { field = value; light = null; heavy = null }

    @Volatile private var light: OkHttpClient? = null
    @Volatile private var heavy: OkHttpClient? = null

    private fun lightClient(): OkHttpClient = light ?: client(READ_TIMEOUT).also { light = it }
    private fun heavyClient(): OkHttpClient = heavy ?: client(HEAVY_READ_TIMEOUT).also { heavy = it }

    private fun client(read: Duration): OkHttpClient = (clientOverride ?: AppHttpClient.streaming()).newBuilder()
        .connectTimeout(CONNECT_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .readTimeout(read.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .dns(FallbackDns)
        // El reto de Cloudflare lo intenta pasar un WebView oculto (sin Android, en las pruebas, no hay cliente real que lo necesite).
        .apply { if (clientOverride == null) addInterceptor(CloudflareInterceptor) }
        // Lo guardado de una verificación hecha por el usuario va solo a la web donde se hizo, también en las redirecciones.
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val cookie = SourceChallenge.cookieFor(host)
            val agent = SourceChallenge.userAgentFor(host)
            chain.proceed(if (cookie == null && agent == null) request else request.newBuilder().apply {
                if (cookie != null) header("Cookie", listOfNotNull(request.header("Cookie"), cookie).joinToString("; "))
                if (agent != null) header("User-Agent", agent)
            }.build())
        }
        .build()

    /** Respuesta ya descargada: el cuerpo se lee una sola vez y se puede pedir como texto, bytes o documento. */
    class Reply(val statusCode: Int, val url: String, private val bytes: ByteArray, private val charset: java.nio.charset.Charset?,
                private val headers: Map<String, String>, private val cookies: Map<String, String>) {
        /** Como texto, con el charset de la cabecera o UTF-8 (lo mismo que hacía jsoup en `body()`). */
        fun body(): String = String(bytes, charset ?: Charsets.UTF_8)
        fun bodyAsBytes(): ByteArray = bytes

        /** Cabecera sin distinguir mayúsculas; si se repite, los valores van unidos por «, ». */
        fun header(name: String): String? = headers[name.lowercase()]
        fun cookies(): Map<String, String> = cookies

        /** Documento HTML: sin charset en la cabecera, jsoup lo toma de la marca de orden de bytes o de `<meta charset>`. */
        fun parse(): Document = Jsoup.parse(java.io.ByteArrayInputStream(bytes), charset?.name(), url)
    }

    /** Lo que una fuente puede pedir de una petición además de la URL. */
    class Options {
        internal val headers = linkedMapOf<String, String>()
        internal val cookies = linkedMapOf<String, String>()
        internal val form = linkedMapOf<String, String>()
        internal var post = false
        internal var maxBytes = MAX_BODY_BYTES
        internal var ignoreHttpErrors = false
        internal var relay = true

        fun header(name: String, value: String) { headers[name] = value }
        fun referrer(value: String) { headers["Referer"] = value }
        fun cookies(values: Map<String, String>) { cookies.putAll(values) }
        fun data(name: String, value: String) { form[name] = value }
        fun method(name: String) { post = name.equals("POST", ignoreCase = true) }
        fun maxBodySize(bytes: Int) { maxBytes = bytes }
        fun ignoreHttpErrors() { ignoreHttpErrors = true }

        /** Sin el DNS de relevo: lo que el DNS de la red (p. ej. AdGuard) no resuelve o bloquea se respeta y la petición falla. */
        fun noRelay() { relay = false }
    }

    /**
     * Pide [url]. Un código HTTP de error lanza [HttpStatusException] (salvo con `ignoreHttpErrors`); un fallo de conexión se
     * reintenta una vez con el DNS de relevo. [timeout] sustituye el tiempo de lectura de la petición.
     */
    fun execute(url: String, ajax: Boolean = false, heavy: Boolean = false, timeout: Duration? = null, configure: Options.() -> Unit = {}): Reply {
        val options = Options().apply(configure)
        val base = when {
            timeout != null -> client(maxOf(1.seconds, timeout))
            heavy -> heavyClient()
            else -> lightClient()
        }
        // Una URL o una cabecera que OkHttp no admite es un fallo de la petición (como antes con jsoup), no un error de programa.
        val request = try {
            build(url, ajax, options)
        } catch (e: IllegalArgumentException) {
            throw java.net.MalformedURLException(e.message)
        }
        val host = request.url.host
        val response = try {
            app.kaizen.commons.RequestCounter.note(url)
            base.newCall(request).execute()
        } catch (e: IOException) {
            // Sin respuesta: el DNS de la red pudo dar una IP que no se alcanza. Con las de 9.9.9.9 se reintenta una vez.
            if (!options.relay || !isConnectFailure(e)) throw e
            // Si ya iba por 9.9.9.9 y tampoco conecta, no se insiste: se vuelve al DNS de la red.
            if (FallbackDns.isPreferred(host)) { FallbackDns.forget(host); throw e }
            if (!FallbackDns.prefer(host)) throw e
            try {
                app.kaizen.commons.RequestCounter.note(url)
                base.newCall(request).execute()
            } catch (retry: IOException) {
                retry.addSuppressed(e)
                throw retry
            }
        }
        response.use {
            val code = it.code
            if (code >= 400 && !options.ignoreHttpErrors) {
                // Un 403 que no es una verificación (la de la web, con `"challenge":true`, ni la de Cloudflare) es un bloqueo: HostGate
                // lo cuenta como fallo del servidor.
                if (code == 403 && !noteChallenge(code, host, runCatching { it.peekBody(512).string() }.getOrDefault(""))) {
                    HostGate.failureFor(url, code, it.header("cf-mitigated"))?.let { failure -> throw failure }
                }
                throw HttpStatusException("HTTP error fetching URL", code, url)
            }
            val body = it.body
            val bytes = body.source().use { source -> source.request(options.maxBytes.toLong()); source.buffer.readByteArray(minOf(source.buffer.size, options.maxBytes.toLong())) }
            if (code == 403) noteChallenge(code, host, String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8))
            val charset = body.contentType()?.charset()
            val headers = it.headers.names().associate { name -> name.lowercase() to it.headers(name).joinToString(", ") }
            val cookies = Cookie.parseAll(it.request.url, it.headers).associate { cookie -> cookie.name to cookie.value }
            return Reply(code, it.request.url.toString(), bytes, charset, headers, cookies)
        }
    }

    /** Si la respuesta es la petición de verificación de la web, se anota para ofrecérsela al usuario; devuelve si lo era. */
    private fun noteChallenge(code: Int, host: String, head: String): Boolean {
        val challenge = SourceChallenge.isChallenge(code, head)
        if (challenge) SourceChallenge.note(host)
        return challenge
    }

    private fun build(url: String, ajax: Boolean, options: Options): Request {
        var target = url.toHttpUrl()
        val builder = Request.Builder().header("User-Agent", Server.BROWSER_USER_AGENT)
        if (ajax) builder.header("X-Requested-With", "XMLHttpRequest")
        options.headers.forEach { (name, value) -> builder.header(name, value) }
        if (options.cookies.isNotEmpty()) builder.header("Cookie", options.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        if (options.post) {
            val form = FormBody.Builder().apply { options.form.forEach { (name, value) -> add(name, value) } }.build()
            builder.post(form)
        } else {
            if (options.form.isNotEmpty()) target = target.newBuilder().apply { options.form.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
            builder.get()
        }
        return builder.url(target).build()
    }

    /** El fallo es de llegar al servidor (no de lo que responde): lo que tiene sentido probar con otras IP. */
    internal fun isConnectFailure(e: IOException): Boolean =
        e is ConnectException || e is NoRouteToHostException || e is UnknownHostException ||
            (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)

    /** Respuesta de [url], o null si falla (red o código HTTP de error). La cancelación se relanza. */
    fun get(url: String, ajax: Boolean = false): Reply? = runCatchingCoop {
        HostGate.call(url) { execute(url, ajax) }
    }.onSuccess { lastFailures.remove(hostKey(url)) }.onFailure { lastFailures[hostKey(url)] = it }.getOrNull()

    // Por qué falló la última petición de cada web: [get] devuelve null y quien lo convierte en error ([failureOf]) no perdería la causa (404, bloqueo, tiempo…).
    private val lastFailures = java.util.concurrent.ConcurrentHashMap<String, Throwable>()
    private fun hostKey(url: String) = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url

    /** El último fallo de la web de [url] (null si la última petición fue bien o no se sabe): para que «no respondió» lleve su causa y se clasifique (E02, E06…) en vez de ser un E99. */
    fun failureOf(url: String): Throwable? = lastFailures[hostKey(url)]

    /** Página HTML de [url], o null si falla. */
    fun document(url: String): Document? = runCatchingCoop { get(url)?.parse() }.getOrNull()

    /** Cuerpo de [url] como texto, o null si falla. */
    fun text(url: String): String? = get(url)?.body()

    /** Cuerpo de [url]; un fallo se propaga (el paginador reintenta la página). */
    fun body(url: String): String = HostGate.call(url) { execute(url, heavy = true).body() }

    /** Página HTML de [url]; un fallo se propaga, incluido el [HttpStatusException] de un 404. */
    fun page(url: String): Document = HostGate.call(url) { execute(url).parse() }
}
