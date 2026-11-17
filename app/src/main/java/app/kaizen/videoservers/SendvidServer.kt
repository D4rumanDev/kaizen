package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.SENDVID
import org.jsoup.Jsoup

/*
 * Basado en los extractores de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito sobre SourceHttp/HostGate de la app.
 */
class SendvidServer internal constructor(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("sendvid.com")

    override val name: String
        get() = SENDVID

    override suspend fun videoServer(): VideoServer? {
        val link = PatternUtil.extractLink(baseLink)
        val unpack = Unpacker.unpack(link)
        val video = Jsoup.parse(unpack.unpacked, link).selectFirst("source#video_source")?.attr("abs:src")?.takeIf { it.isNotBlank() } ?: return null
        return VideoServer(name, Option(name, null, video, Headers { add(0, "Referer" to "https://sendvid.com/") }))
    }
}
