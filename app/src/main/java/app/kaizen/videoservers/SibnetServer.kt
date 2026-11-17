package app.kaizen.videoservers

import android.content.Context
import androidx.core.net.toUri
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.SIBNET

/*
 * Basado en los extractores de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito sobre SourceHttp/HostGate de la app.
 */
class SibnetServer internal constructor(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("sibnet.ru")

    override val name: String
        get() = SIBNET

    override suspend fun videoServer(): VideoServer? {
        val link = PatternUtil.extractLink(baseLink)
        val script = Unpacker.unpack(link).unpacked
        if (!script.contains("player.src")) return null
        val slug = script.substringAfter("player.src").substringAfter("src:").substringAfter("\"").substringBefore("\"")
        if (slug.isBlank()) return null
        val video = if (slug.startsWith("http")) slug else "https://${link.toUri().host}$slug"
        return VideoServer(name, Option(name, null, video, Headers { add(0, "Referer" to link) }))
    }
}
