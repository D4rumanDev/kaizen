package app.kaizen.videoservers

import android.content.Context
import androidx.core.net.toUri
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.SAVEFILE

/*
 * Basado en los extractores de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito sobre SourceHttp/HostGate de la app.
 */
class SavefileServer(context: Context, baseLink: String) : Server(context, baseLink, SAVEFILE, "savefiles") {

    override suspend fun videoServer(): VideoServer? {
        val link = PatternUtil.extractLink(baseLink)
        val unpacked = Unpacker.unpack(link).unpacked
        val master = Regex("""\{file:"([^"]+m3u8[^"]*)"\}""").find(unpacked)?.groupValues?.get(1) ?: return null
        val host = link.toUri().let { it.scheme + "://" + it.host }
        return VideoServer(name, Option(name, "HLS", master, Headers {
            add(0, "Referer" to "$host/")
            add(1, "Origin" to host)
        }))
    }
}
