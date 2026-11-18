package app.kaizen.source

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import app.kaizen.App
import app.kaizen.commons.DebugLog
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import app.kaizen.commons.postDelayed
import app.kaizen.commons.await

/*
 * Basado en lib/cloudflare-interceptor de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito: el resultado se guarda en
 * [SourceChallenge] (cookies y User-Agent por web), que [SourceHttp] ya añade a las peticiones siguientes, y no se hacen clics a ciegas en la página.
 */

/**
 * Cuando una web responde con el reto de Cloudflare («Just a moment…»), lo deja resolver a un WebView oculto (el mismo motor que un navegador) y repite la petición con
 * las cookies que resulten. Si el reto no se supera en [WAIT] (p. ej. uno interactivo), devuelve la respuesta original y sigue valiendo la verificación manual
 * de [SourceChallenge]. Tras un fallo no se vuelve a intentar con esa web en [RETRY_AFTER]: así ninguna petición espera medio minuto en vano.
 */
internal object CloudflareInterceptor : Interceptor {
    private val WAIT = 25.seconds
    private val RETRY_AFTER = 10.minutes
    private val POLL = 500.milliseconds

    private val locks = ConcurrentHashMap<String, Any>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val solvedAt = ConcurrentHashMap<String, Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!isCloudflareChallenge(response.code, response.header("cf-mitigated"))) return response
        val host = request.url.host
        // En el hilo principal no se puede esperar (y OkHttp no debería estar ahí): se deja pasar.
        if (Looper.myLooper() == Looper.getMainLooper()) return response
        val now = SystemClock.elapsedRealtime()
        if (failedAt[host]?.let { now - it < RETRY_AFTER.inWholeMilliseconds } == true) return response
        // Un cerrojo por web: resolver una no retiene a las demás.
        synchronized(locks.getOrPut(host) { Any() }) {
            // Otra petición a la misma web acaba de resolverlo: basta repetir la nuestra con sus cookies.
            val justSolved = solvedAt[host]?.let { SystemClock.elapsedRealtime() - it < 60_000L } == true
            if (!justSolved && !solve(request.url.toString(), host)) {
                failedAt[host] = SystemClock.elapsedRealtime()
                return response
            }
        }
        response.close()
        val retry = chain.proceed(request)
        if (isCloudflareChallenge(retry.code, retry.header("cf-mitigated"))) {
            failedAt[host] = SystemClock.elapsedRealtime()
            solvedAt.remove(host)
        }
        return retry
    }

    /** Respuesta de reto de Cloudflare: 403 o 503 con `cf-mitigated: challenge`. Un 503 sin esa cabecera es una caída del origen, no un reto. */
    internal fun isCloudflareChallenge(code: Int, mitigated: String?): Boolean =
        (code == 403 || code == 503) && mitigated.equals("challenge", ignoreCase = true)

    /** Carga [url] en un WebView oculto hasta que aparece la cookie `cf_clearance`; la guarda en [SourceChallenge]. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun solve(url: String, host: String): Boolean {
        val handler = Handler(Looper.getMainLooper())
        val agent = runCatching { WebSettings.getDefaultUserAgent(App.context) }.getOrNull() ?: return false
        val done = CountDownLatch(1)
        var webView: WebView? = null
        val cookie = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val poll = object : Runnable {
            override fun run() {
                val current = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                if (current != null && current.contains("cf_clearance=")) { cookie.set(current); done.countDown() } else handler.postDelayed(this, POLL)
            }
        }
        handler.post {
            runCatching {
                // Una cf_clearance caducada de una resolución anterior seguiría en el WebView y pasaría por la nueva: se quita antes de cargar.
                CookieManager.getInstance().setCookie(url, "cf_clearance=; Max-Age=0")
                webView = WebView(App.context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = agent
                    loadUrl(url)
                }
                handler.postDelayed(poll, POLL)
            }.onFailure { done.countDown() }
        }
        runCatching { done.await(WAIT) }
        handler.post { handler.removeCallbacks(poll); webView?.stopLoading(); webView?.destroy(); webView = null }
        val clearance = cookie.get()
        if (clearance == null) {
            DebugLog.info("Verificación", "$host: el reto de Cloudflare no se superó solo en ${WAIT.inWholeSeconds}s")
            return false
        }
        val saved = SourceChallenge.save(host, clearance, agent)
        if (saved) solvedAt[host] = SystemClock.elapsedRealtime()
        return saved
    }
}
