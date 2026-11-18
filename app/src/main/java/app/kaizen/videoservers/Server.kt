package app.kaizen.videoservers

import app.kaizen.BuildConfig
import app.kaizen.commons.FallbackDns
import app.kaizen.domain.ConnectionStats
import app.kaizen.commons.HostGate
import app.kaizen.tv.ui.KaizenToast
import app.kaizen.commons.logError
import app.kaizen.commons.noCrash
import app.kaizen.commons.runCatchingCoop
import app.kaizen.source.SourceHttp
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

abstract class Server(val context: Context, val baseLink: String) : Comparable<Server> {
    @Volatile private var server: VideoServer? = null

    abstract val isValid: Boolean

    abstract val name: String

    /** Extrae el vídeo del servidor; puede pedir páginas o ejecutar JavaScript (se llama en un hilo de IO). */
    abstract suspend fun videoServer(): VideoServer?

    open val canStream: Boolean = true

    /**
     * Único punto que captura los fallos de [videoServer] (las subclases dejan subir las excepciones).
     * Un timeout propio (`withTimeout`) es un fallo del servidor; la cancelación del llamante se relanza.
     */
    suspend fun verified(): VideoServer? {
        if (server == null) {
            networkFailure = null
            verifyFailure = null
            attempted = false
            // El registro dice qué servidor falló (antes solo salía la excepción, sin saber de quién era).
            val extracted = runCatchingCoop { extract() }.onFailure { logError(RuntimeException("Extractor de $name (${runCatching { java.net.URI(baseLink).host }.getOrNull() ?: "?"}): ${it::class.java.simpleName}: ${it.message?.replace(URL_IN_TEXT, "<url>")}", it), app.kaizen.commons.AppErrors.Area.SOURCE, name) }
            val failure = extracted.exceptionOrNull()
            // Un nombre que la red no resuelve (al sacar la página del servidor, no solo al comprobar el enlace) no es un fallo del servidor: o ya
            // no existe o lo filtra el DNS (AdGuard). Se comprueba con Quad9 y se avisa; no cuenta para su pausa.
            if (failure is java.net.UnknownHostException) hostOf(failure)?.let(::warnIfNetworkDnsFails)
            server = verify(extracted.getOrNull())
            attempted = true
            reportHealth(failure.takeUnless { it is java.net.UnknownHostException } as? java.io.IOException)
            // Una vez por extracción real (una repetición que devuelve el resultado ya guardado no es otro intento). Si falló, con su causa.
            val cause = if (server != null) null else app.kaizen.commons.AppErrors.connectionCause(failure ?: networkFailure ?: verifyFailure) ?: ConnectionStats.Cause.NO_VIDEO
            onAttempt?.invoke(server != null, cause)
        }
        return server
    }

    /** Quien lleva las estadísticas: recibe si cada extracción real funcionó. */
    @Volatile var onAttempt: ((Boolean, ConnectionStats.Cause?) -> Unit)? = null

    /** El último fallo al comprobar los enlaces de la extracción en curso (también los de nombre sin resolver), para decir la causa. */
    private var verifyFailure: Exception? = null

    /** La última extracción llegó a terminar (si se cortó por tiempo desde fuera, no: quien la cortó lo anota). */
    @Volatile var attempted = false
        private set

    /** Último fallo de red de las opciones al verificarlas (DNS, conexión, tiempo), para el corte del servidor. */
    private var networkFailure: java.io.IOException? = null

    /**
     * Anota en [HostGate] la salud de este servidor como familia (ver [HostGate.serverKey]): un vídeo entregado lo devuelve a la
     * lista; un fallo de red al sacarlo o comprobarlo cuenta para las pausas de 2, 5 y 10 min y 1 h. Un vídeo que ya no existe
     * (sin fallo de red) no es culpa del servidor y no cuenta.
     */
    private fun reportHealth(extractFailure: java.io.IOException?) {
        when {
            server != null -> HostGate.reportServerUp(name)
            else -> (extractFailure ?: networkFailure)?.let { HostGate.reportServerFailure(name, it) }
        }
    }

    private suspend fun extract(): VideoServer? = try {
        videoServer()
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        logError(e)
        // Un tiempo agotado propio es un fallo del servidor, aunque no sea un IOException.
        networkFailure = java.net.SocketTimeoutException(e.message)
        null
    }

    protected suspend fun getFinishedHtml(link: String): String {
        return suspendCoroutine {
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                }
                addJavascriptInterface(object : ZippyServer.ZippyJSInterface() {
                    @JavascriptInterface
                    override fun printHtml(string: String) {
                        // La página puede llamar al puente JS más de una vez: el segundo resume no debe cerrar la app.
                        noCrash { it.resume(string) }
                    }
                }, "HtmlViewer")
                val handler = Handler(Looper.getMainLooper())
                var isExecuted = false
                val runnable = Runnable {
                    if (!isExecuted) {
                        isExecuted = true
                        loadUrl(
                            "javascript:window.HtmlViewer.printHtml" +
                                    "('<html>'+document.getElementsByTagName('html')[0].innerHTML+'</html>');"
                        )
                    }
                }
                handler.postDelayed(runnable, 5000)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        handler.removeCallbacks(runnable)
                        runnable.run()
                    }
                }
                loadUrl(link)
            }
        }
    }

    private fun verify(videoServer: VideoServer?): VideoServer? {
        if (videoServer == null)
            return null
        for (option in ArrayList(videoServer.options))
            try {
                check(option.url != null)
                if (videoServer.skipVerification) continue
                // Con el turno y el corte de HostGate: un bloqueo (403) o una caída (5xx) seguidos pausan ese servidor de vídeo.
                // Por SourceHttp (con turno, tiempos y 403/5xx como antes). Un nombre que el DNS de la red no resuelve (los CDN con subdominios
                // aleatorios, como el de Voe, si AdGuard los filtra) hacía fallar el enlace y pausaba todo el servidor: ya no cuenta como fallo suyo.
                val response = HostGate.call(option.url) {
                    SourceHttp.execute(option.url, timeout = VERIFY_TIMEOUT) {
                        ignoreHttpErrors()
                        maxBodySize(1)
                        // Los enlaces de vídeo respetan el DNS de la red (AdGuard): si bloquea o no resuelve el CDN, el enlace falla.
                        noRelay()
                        option.headers?.createHeaders()?.forEach { (name, value) -> header(name, value) }
                    }.also { r -> HostGate.failureFor(option.url, r.statusCode)?.let { throw it } }
                }
                if (response.statusCode !in 200..300) {
                    if (BuildConfig.DEBUG) Log.e("Remove Option", "Server: ${option.server} Url: ${option.url} Code: ${response.statusCode}")
                    videoServer.options.remove(option)
                }
            } catch (e: Exception) {
                logError(e)
                // Un nombre que no se resuelve no es un fallo del servidor de vídeo: o el enlace está muerto (ni la red ni Quad9 lo resuelven) o lo
                // filtra el DNS de la red (AdGuard); en ese caso se avisa al usuario de qué dominio permitir.
                verifyFailure = e
                if (e is java.net.UnknownHostException) option.url?.let { runCatching { java.net.URI(it).host }.getOrNull() }?.let(::warnIfNetworkDnsFails)
                else if (e is java.io.IOException) networkFailure = e
                videoServer.options.remove(option)
            }

        return if (videoServer.options.isEmpty()) null else videoServer
    }

    /** Si Quad9 resuelve el nombre que la red no resolvió, el culpable es el DNS configurado: se anota y se avisa una vez por dominio. */
    private fun warnIfNetworkDnsFails(host: String) {
        val domain = registrableDomain(host)
        // Ya avisado: no se vuelve a preguntar a Quad9 (cada consulta puede tardar y los subdominios aleatorios no se repiten).
        if (domain in warnedDomains) return
        if (FallbackDns.diagnose(host) != FallbackDns.Diagnosis.NETWORK_DNS_FAILS) return
        warnedDomains += domain
        Log.w("Kaizen", "El DNS de la red no resuelve $host y Quad9 sí: se filtra el dominio $domain (no es un fallo de $name)")
        KaizenToast.toastLong("Tu DNS no resuelve $domain (el servidor $name está bien). Permítelo en AdGuard: @@||$domain^")
    }

    /** El nombre que no se pudo resolver, del mensaje de la excepción («Unable to resolve host "x.com": …»). */
    private fun hostOf(e: java.net.UnknownHostException): String? = Regex("""host "([^"]+)"""").find(e.message.orEmpty())?.groupValues?.get(1)

    /** El dominio que se permitiría en el filtro: los dos últimos nombres, o tres con un sufijo compuesto como co.uk o com.br. */
    private fun registrableDomain(host: String): String {
        val labels = host.split('.')
        val compound = labels.size >= 3 && labels.last().length == 2 && labels[labels.size - 2].length <= 3
        return labels.takeLast(if (compound) 3 else 2).joinToString(".")
    }

    override fun compareTo(other: Server): Int {
        return name.compareTo(other.name)
    }

    companion object {
        const val TIMEOUT = 10000L
        private val VERIFY_TIMEOUT = 10.seconds

        /** Una dirección dentro de un mensaje de error: puede llevar tokens o parámetros, así que no va al registro. */
        private val URL_IN_TEXT = Regex("""https?://\S+""")
        private val warnedDomains: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * User-Agent para los HLS que lo exigen: el CDN de Desu y Magi (playmudos.com) responde 403 al de ExoPlayer
         * («KAIZEN/… AndroidXMedia3/…») y 200 a uno de navegador (comprobado con curl, 2026-10-12).
         */
        val BROWSER_USER_AGENT: String get() = app.kaizen.commons.UserAgent.current()

        private fun getServers(context: Context, base: String): List<Server> {
            return listOf(
                JkPlayerServer(context, base),
                VoeServer(context, base),
                FireServer(context, base),
                NatsukiServer(context, base),
                GoCDNServer(context, base),
                StapeServer(context, base),
                StreamWishServer(context, base),
                VidHideServer(context, base),
                FilemoonServer(context, base),
                UqloadServer(context, base),
                MixDropServer(context, base),
                SavefileServer(context, base),
                VidMolyServer(context, base),
                SendvidServer(context, base),
                SibnetServer(context, base),
                StreamHubServer(context, base),
                DoodServer(context, base),
                VidGuardServer(context, base),
                //SBServer(context, base),
                VeryStreamServer(context, base),
                FembedServer(context, base),
                FenixServer(context, base),
                HyperionServer(context, base),
                IzanagiServer(context, base),
                MangoServer(context, base),
                MegaServer(context, base),
                OkruServer(context, base),
                RVServer(context, base),
                ZippyServer(context, base),
                YUServer(context, base),
                MP4UploadServer(context, base),
                ZillaServer(context, base),
                PixelServer(context, base),
                UPNServer(context, base),
                FichierServer(context, base),
                TransferIt(context, base)
            )
        }

        fun check(context: Context, base: String): Server? {
            for (server in getServers(context, base)) {
                if (server.isValid)
                    return server
            }
            return null
        }

    }
}
