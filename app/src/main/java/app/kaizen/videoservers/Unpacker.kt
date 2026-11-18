package app.kaizen.videoservers

import app.kaizen.source.SourceHttp
import app.kaizen.commons.HostGate
import android.content.Context
import android.webkit.WebSettings
import com.venom.greendark.decoder.WebJS
import app.kaizen.commons.JsEngine
import app.kaizen.commons.noCrash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

object Unpacker {
    private val packedRegex2 =
        "eval\\((function\\(p,a,c,k,e,?[dr]?\\).*.split\\('\\|'\\).*)\\)".toRegex()

    private fun runPacked(packedCode: String): String =
        JsEngine.evaluate("function prnt() {var txt = $packedCode; return txt;}prnt();").orEmpty()

    /** El HTML con su `eval(function(p,a,c,k,e,d)…)` ejecutado; sin código empaquetado, el HTML tal cual. */
    fun unpackHtml(html: String): String {
        val packedCode = packedRegex2.find(html)?.destructured?.component1() ?: return html
        return runPacked(packedCode)
    }

    fun unpack(link: String): UnpackResult {
        val result = HostGate.call(link) { SourceHttp.execute(link) }
        return UnpackResult(result.url, unpackHtml(result.body()))
    }

    suspend fun unpackWeb(context: Context, link: String): UnpackResult {
        val html = getHtml(context, link)
        return UnpackResult(html.url, unpackHtml(html.html))
    }

    suspend fun getHtml(context: Context, link: String, delay: Duration = 5.seconds): HTMLResult {
        return withContext(Dispatchers.Main) {
            val evaluator = WebJS(context)
            suspendCoroutine { continuation ->
                evaluator.evalOnFinish(
                    link,
                    "(\"<html>\"+document.getElementsByTagName(\"html\")[0].innerHTML+\"<\\/html>\")",
                    delay
                ) { url, html ->
                    // La página puede llamar al puente JS más de una vez: el segundo resume no debe cerrar la app.
                    noCrash { continuation.resume(HTMLResult(url, html)) }
                }
            }
        }
    }

    suspend fun listenResources(context: Context, link: String, onRequest: (String?) -> Boolean, userAgent: String = WebSettings.getDefaultUserAgent(context), timeout: Duration = 10.seconds, executeOnFinish: String? = null): String? {
        return withContext(Dispatchers.Main) {
            val evaluator = WebJS(context)
            suspendCoroutine { continuation ->
                evaluator.listenResources(
                    link,
                    onRequest,
                    userAgent,
                    timeout,
                    executeOnFinish
                ) { url, headers ->
                    noCrash {
                        continuation.resume(url)
                    }
                }
            }
        }
    }

    data class HTMLResult(
        val url: String?,
        val html: String
    )

    data class UnpackResult(
        val url: String?,
        val unpacked: String
    )

}