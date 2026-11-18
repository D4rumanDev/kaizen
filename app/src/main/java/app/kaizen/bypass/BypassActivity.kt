package app.kaizen.bypass

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import app.kaizen.commons.postDelayed

/** Carga la URL en un WebView y espera a que Cloudflare entregue la cookie `cf_clearance`. */
class BypassActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var webView: WebView
    private lateinit var url: String
    private lateinit var userAgent: String
    private var maxTries = 3
    private var tryCount = 0
    private var ticks = 0
    private var finished = false
    private var pageLoaded = false
    private var startedAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (finished) return
            val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
            if (COOKIE_CLEARANCE in cookies) {
                finishWith(cookies)
                return
            }
            // La web cargó y no es la página del desafío: Cloudflare no pide verificación, no hay nada que esperar.
            if (pageLoaded && !isChallengePage()) {
                finishWith(cookies)
                return
            }
            // Tope duro: sin él, una Cloudflare que no contesta deja el bypass colgado y sin aviso.
            if (SystemClock.elapsedRealtime() - startedAt > DEADLINE.inWholeMilliseconds) {
                finishWith(null)
                return
            }
            if (++ticks >= TICKS_PER_TRY) {
                ticks = 0
                if (++tryCount >= maxTries) {
                    finishWith(null)
                    return
                }
                pageLoaded = false
                webView.reload()
            }
            handler.postDelayed(this, POLL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        url = intent.getStringExtra(EXTRA_URL) ?: return finishWith(null)
        userAgent = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty()
        maxTries = intent.getIntExtra(EXTRA_MAX_TRIES, 3)
        startedAt = SystemClock.elapsedRealtime()
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            if (userAgent.isNotBlank()) settings.userAgentString = userAgent
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    pageLoaded = true
                }

                // Sin conexión con Cloudflare o error propio suyo (500, 502, 52x): no hay desafío que esperar. El 503 puede ser el desafío.
                // ERROR_UNKNOWN (p. ej. net::ERR_ABORTED por el reload o una redirección del desafío) no es una caída.
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && error.errorCode != WebViewClient.ERROR_UNKNOWN) finishUnreachable()
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 500 && errorResponse.statusCode != 503) finishUnreachable()
                }
            }
        }
        // El WebView debe estar en pantalla para que el desafío de JavaScript se ejecute.
        setContentView(FrameLayout(this).apply {
            addView(webView, ViewGroup.LayoutParams(1, 1))
        })
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        // Solo caduca el cf_clearance de este sitio (los de otras fuentes siguen valiendo). Es asíncrono: se carga
        // y se sondea al terminar, para no tomar el caducado.
        CookieManager.getInstance().setCookie(url, "$COOKIE_CLEARANCE=; Max-Age=0") {
            if (finished || isDestroyed) return@setCookie
            webView.loadUrl(url)
            handler.postDelayed(poll, POLL)
        }
    }

    private fun isChallengePage(): Boolean =
        CHALLENGE_TITLES.any { webView.title.orEmpty().contains(it, ignoreCase = true) }

    private fun finishUnreachable() {
        if (finished) return
        BypassStatus.finish(BypassStatus.State.UNREACHABLE)
        finishWith(null, recorded = true)
    }

    private fun finishWith(cookies: String?, recorded: Boolean = false) {
        if (finished) return
        finished = true
        handler.removeCallbacks(poll)
        if (!recorded) BypassStatus.finish(
            when {
                cookies == null -> BypassStatus.State.FAILED
                COOKIE_CLEARANCE in cookies -> BypassStatus.State.OK
                else -> BypassStatus.State.NOT_NEEDED
            }
        )
        if (cookies != null)
            setResult(RESULT_OK, Intent().putExtra(RESULT_COOKIES, cookies).putExtra(RESULT_USER_AGENT, userAgent))
        else
            setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        // Cerrada a mano (Atrás): no cuenta como fallo, pero el estado no se queda en «en marcha».
        if (!finished) BypassStatus.cancel()
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_USER_AGENT = "ua"
        const val EXTRA_MAX_TRIES = "max_tries"
        const val RESULT_COOKIES = "cookies"
        const val RESULT_USER_AGENT = "user_agent"
        private const val COOKIE_CLEARANCE = "cf_clearance"
        private val CHALLENGE_TITLES = listOf("just a moment", "un momento", "attention required")
        private val POLL = 500.milliseconds
        private const val TICKS_PER_TRY = 40
        private val DEADLINE = 45.seconds
    }
}
