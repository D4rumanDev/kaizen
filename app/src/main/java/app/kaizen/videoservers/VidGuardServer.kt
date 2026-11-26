package app.kaizen.videoservers

import android.content.Context
import android.util.Base64
import app.kaizen.commons.HostGate
import app.kaizen.commons.JsEngine
import app.kaizen.commons.PatternUtil
import app.kaizen.source.SourceHttp
import app.kaizen.videoservers.VideoServer.Names.VIDGUARD
import org.json.JSONObject
import org.jsoup.Jsoup

/*
 * Basado en los extractores de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito sobre SourceHttp/HostGate de la app.
 */

/** VidGuard: la página trae un script ofuscado cuyo resultado (`svg.stream`) lleva la firma de la lista HLS desordenada. */
class VidGuardServer(context: Context, baseLink: String) : Server(context, baseLink, VIDGUARD, *HOSTS.toTypedArray()) {

    override suspend fun videoServer(): VideoServer? {
        val link = PatternUtil.extractLink(baseLink)
        val html = HostGate.call(link) { SourceHttp.execute(link) }.body()
        val script = Jsoup.parse(html).select("script").map { it.data() }.firstOrNull { it.contains("eval") } ?: return null
        val result = JsEngine.evaluate("var window = this; $script;\nJSON.stringify(svg);")?.takeIf { it.isNotBlank() } ?: return null
        val stream = JSONObject(result).optString("stream").takeIf { it.contains("sig=") } ?: return null
        return VideoServer(name, Option(name, "HLS", sigDecode(stream), Headers { add(0, "Referer" to link) }))
    }

    internal companion object {
        val HOSTS = listOf("vidguard", "vgfplay", "vembed", "listeamed", "bembed", "moflix-stream")

        /** Deshace el desorden de la firma: hex con XOR 2, base64, sin los 5 últimos, al revés, pares intercambiados y sin otros 5. */
        fun sigDecode(url: String): String {
            val sig = url.substringAfter("sig=").substringBefore("&")
            val decoded = sig.chunked(2).joinToString("") { (it.toInt(16) xor 2).toChar().toString() }
            val padded = decoded + when (decoded.length % 4) { 2 -> "=="; 3 -> "="; else -> "" }
            val chars = String(Base64.decode(padded.toByteArray(Charsets.UTF_8), Base64.DEFAULT)).dropLast(5).reversed().toCharArray()
            for (i in chars.indices step 2) if (i + 1 < chars.size) { val t = chars[i]; chars[i] = chars[i + 1]; chars[i + 1] = t }
            return url.replace(sig, String(chars).dropLast(5))
        }
    }
}
