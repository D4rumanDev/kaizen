package app.kaizen.tv.ui

import app.kaizen.tv.ui.theme.*

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * El «sitio» que incrusta el vídeo. YouTube rechaza (error 153) los reproductores sin Referer ni origen, y con la propia web de YouTube como origen tampoco acepta
 * algunos vídeos: se identifica con una dirección https a partir del id de la app, como pide su documentación.
 */
private const val EMBED_ORIGIN = "https://app.kaizen"

/** El reproductor incrustado de un tráiler: la dirección que se carga en el iframe y los sitios a los que se le deja navegar. */
private class Embed(val html: String, val origin: String, val hosts: List<String>, val reportsErrors: Boolean = false)

/** YouTube o Dailymotion (los dos sitios que da [app.kaizen.source.AnilistTrailer]); null si la dirección no es de uno de ellos. */
private fun embedOf(url: String): Embed? {
    val uri = android.net.Uri.parse(url)
    val host = uri.host.orEmpty()
    return when {
        host == "youtube.com" || host.endsWith(".youtube.com") -> uri.getQueryParameter("v")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,32}")) }?.let {
            // La API del reproductor de YouTube avisa cuando el dueño no deja incrustar el vídeo (errores 101, 150 y 152): entonces se abre fuera de la app.
            Embed(
                "<html><head><style>iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style></head><body style=\"margin:0;background:#000\"><div id=\"p\"></div>" +
                    "<script src=\"https://www.youtube.com/iframe_api\"></script><script>function onYouTubeIframeAPIReady(){new YT.Player('p',{width:'100%',height:'100%',videoId:'$it'," +
                    "playerVars:{autoplay:1,rel:0,modestbranding:1,playsinline:1,origin:'$EMBED_ORIGIN'},events:{onReady:function(e){e.target.playVideo()},onError:function(e){KaizenTrailer.failed(e.data)}}})}</script></body></html>",
                EMBED_ORIGIN + "/", listOf("youtube.com", "youtube-nocookie.com", "google.com", "gstatic.com", "ytimg.com", "googlevideo.com"), reportsErrors = true)
        }
        host == "dailymotion.com" || host.endsWith(".dailymotion.com") -> uri.lastPathSegment?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,32}")) }?.let {
            Embed(
                "<html><body style=\"margin:0;background:#000\"><iframe width=\"100%\" height=\"100%\" src=\"https://www.dailymotion.com/embed/video/$it?autoplay=1\" frameborder=\"0\" " +
                    "allow=\"autoplay; fullscreen\" allowfullscreen style=\"position:fixed;inset:0;border:0\"></iframe></body></html>",
                "https://www.dailymotion.com/", listOf("dailymotion.com", "dmcdn.net"))
        }
        else -> null
    }
}

/**
 * El tráiler en una ventana de la app, como la de la sinopsis: un iframe del reproductor de YouTube o Dailymotion en un WebView, sin salir de Kaizen. Atrás la cierra.
 * Si el WebView no puede cargarlo (sin red, sitio caído) o la dirección no es de esos sitios, avisa con [onFail] para que se abra fuera de la app, como antes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TrailerDialog(url: String, onDismiss: () -> Unit, onFail: () -> Unit) {
    val embed = embedOf(url)
    if (embed == null) {
        androidx.compose.runtime.LaunchedEffect(url) { onFail() }
        return
    }
    // El error puede llegar por dos caminos (el reproductor y el WebView): solo se abre fuera una vez.
    val failed = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val failOnce = { if (failed.compareAndSet(false, true)) onFail() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(0.78f).aspectRatio(16f / 9f).clip(ShapeXl).background(androidx.compose.ui.graphics.Color.Black),
                onRelease = { it.stopLoading(); it.removeJavascriptInterface("KaizenTrailer"); it.loadUrl("about:blank"); it.destroy() },
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(AndroidColor.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // El reproductor es una página https y nada más: sin acceso a ficheros ni a contenido local, y sin contenido mixto.
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        // El vídeo arranca solo (es lo que se pidió con el botón): sin esto el navegador espera un toque que el mando no da.
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            // Solo se navega dentro del reproductor: un enlace del vídeo a otra web no se abre aquí.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val host = request.url.host.orEmpty()
                                return request.url.scheme != "https" || embed.hosts.none { host == it || host.endsWith(".$it") }
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) failOnce()
                            }
                        }
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                        // YouTube avisa por aquí de que el vídeo no se puede ver incrustado.
                        // Solo el reproductor de YouTube llama al puente; con Dailymotion no se registra.
                        if (embed.reportsErrors) addJavascriptInterface(object {
                            @android.webkit.JavascriptInterface
                            fun failed(code: Int) {
                                android.util.Log.w("KaizenTrailer", "YouTube no deja incrustar el vídeo (error $code)")
                                post { failOnce() }
                            }
                        }, "KaizenTrailer")
                        loadDataWithBaseURL(embed.origin, embed.html, "text/html", "utf-8", null)
                    }
                }
            )
        }
    }
}
