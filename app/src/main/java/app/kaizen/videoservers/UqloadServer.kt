package app.kaizen.videoservers

import android.content.Context
import androidx.core.net.toUri
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.UQLOAD

/*
 * Basado en los extractores de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito sobre SourceHttp/HostGate de la app.
 */
class UqloadServer internal constructor(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("uqload")

    override val name: String
        get() = UQLOAD

    override suspend fun videoServer(): VideoServer? {
        val link = PatternUtil.extractLink(baseLink).substringBefore('?')
        val unpack = Unpacker.unpack(link)
        val video = Regex("""sources:\s*\["(http[^"]+)"""").find(unpack.unpacked)?.groupValues?.get(1) ?: return null
        val host = link.toUri().let { it.scheme + "://" + it.host }
        return VideoServer(name, Option(name, null, video, Headers { add(0, "Referer" to "$host/") }))
    }
}
