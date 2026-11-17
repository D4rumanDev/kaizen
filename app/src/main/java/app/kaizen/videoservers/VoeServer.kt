package app.kaizen.videoservers

import app.kaizen.source.SourceHttp
import android.content.Context
import app.kaizen.commons.HostGate
import org.json.JSONArray
import org.json.JSONObject

/**
 * VOE (`voe.sx/e/<id>`). La página redirige por JavaScript a un dominio que cambia a menudo y allí
 * guarda las fuentes ofuscadas en `<script type="application/json">`. El HLS que sale no pide
 * Referer (comprobado con curl, 2026-10-11).
 */
class VoeServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("voe.sx/")

    override val name: String
        get() = VideoServer.Names.VOE

    override suspend fun videoServer(): VideoServer? {
        val first = fetch(baseLink)
        val html = JS_REDIRECT.find(first)?.groupValues?.get(1)?.let(::fetch) ?: first
        val url = streamUrl(html) ?: return null
        return VideoServer(name, Option(name, null, url, Headers { add("User-Agent" to BROWSER_USER_AGENT) }))
    }

    private fun fetch(url: String): String = HostGate.call(url) {
        SourceHttp.execute(url).body()
    }

    companion object {
        private val JS_REDIRECT = Regex("""window\.location\.href\s*=\s*'(https://[^']+)'""")
        private val JSON_SCRIPT = Regex("""<script type="application/json">\s*(.*?)\s*</script>""", RegexOption.DOT_MATCHES_ALL)
        private val JUNK = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")

        /** HLS (`source`) de la página final de VOE, o null si no trae los datos esperados. */
        fun streamUrl(html: String): String? = runCatching {
            val raw = JSONArray(JSON_SCRIPT.find(html)!!.groupValues[1]).getString(0)
            JSONObject(decode(raw)).optString("source").takeIf { it.startsWith("http") }
        }.getOrNull()

        /** ROT13 → sin separadores basura → base64 → cada carácter −3 → al revés → base64. */
        fun decode(raw: String): String {
            val cleaned = JUNK.fold(rot13(raw)) { text, junk -> text.replace(junk, "") }
            val shifted = String(base64(cleaned), Charsets.ISO_8859_1).map { it - 3 }.joinToString("").reversed()
            return String(base64(shifted), Charsets.UTF_8)
        }

        private fun rot13(text: String): String = text.map { c ->
            when (c) {
                in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
                in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
                else -> c
            }
        }.joinToString("")

        private fun base64(text: String): ByteArray =
            java.util.Base64.getDecoder().decode(text.padEnd((text.length + 3) / 4 * 4, '='))
    }
}
