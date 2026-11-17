package app.kaizen.videoservers

import android.content.Context
import android.util.Base64
import androidx.core.net.toUri
import app.kaizen.commons.HostGate
import app.kaizen.commons.PatternUtil
import app.kaizen.source.SourceHttp
import app.kaizen.videoservers.VideoServer.Names.FILEMOON
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Basado en los extractores de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito sobre SourceHttp/HostGate de la app.
 */

/** Filemoon: su API da la lista HLS en claro o cifrada con AES-GCM (la clave llega en partes dentro de la misma respuesta). */
class FilemoonServer internal constructor(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = HOSTS.any { baseLink.contains(it) }

    override val name: String
        get() = FILEMOON

    override suspend fun videoServer(): VideoServer? {
        val link = PatternUtil.extractLink(baseLink)
        val host = link.toUri().host ?: return null
        val mediaId = link.toUri().pathSegments.lastOrNull { it.isNotEmpty() } ?: return null

        val details = get("https://$host/api/videos/$mediaId/embed/details")
        val embedUrl = JSONObject(details).optString("embed_frame_url").takeIf { it.isNotBlank() } ?: return null
        val embedHost = embedUrl.toUri().host ?: return null

        val playback = get("https://$embedHost/api/videos/$mediaId/embed/playback") {
            header("Referer", embedUrl)
            header("X-Embed-Origin", host)
            header("X-Embed-Parent", link)
            header("X-Embed-Referer", link)
            header("Accept", "*/*")
            header("User-Agent", BROWSER_USER_AGENT)
        }
        val root = JSONObject(playback)
        val sources = root.optJSONArray("sources")
            ?: root.optJSONObject("playback")?.let { JSONObject(decrypt(it)).optJSONArray("sources") }
            ?: return null

        val options = (0 until sources.length()).mapNotNull { i ->
            val source = sources.getJSONObject(i)
            val url = source.optString("url").ifBlank { source.optString("file") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val headers = Headers { add(0, "Referer" to "https://$host/"); add(1, "User-Agent" to BROWSER_USER_AGENT) }
            Option(name, source.optString("label").ifBlank { "HLS${i + 1}" }, url, headers)
        }.toMutableList()
        return options.takeIf { it.isNotEmpty() }?.let { VideoServer(name, it) }
    }

    private fun get(url: String, configure: SourceHttp.Options.() -> Unit = {}): String =
        HostGate.call(url) { SourceHttp.execute(url, configure = configure) }.body()

    private fun decrypt(playback: JSONObject): String {
        val parts = playback.getJSONArray("key_parts")
        val key = (0 until parts.length()).fold(ByteArray(0)) { acc, i -> acc + base64Url(parts.getString(i)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, base64Url(playback.getString("iv"))))
        return String(cipher.doFinal(base64Url(playback.getString("payload"))), Charsets.UTF_8)
    }

    private fun base64Url(input: String): ByteArray = Base64.decode(input, Base64.URL_SAFE)

    companion object {
        /** «byse» es la web nueva de Filemoon (bysekoze.com en los enlaces, byselapuix.com como marco): misma API de `/api/videos/{id}/embed`. */
        val HOSTS = listOf("filemoon", "byse")
    }
}
