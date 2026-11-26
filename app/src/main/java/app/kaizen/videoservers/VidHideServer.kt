package app.kaizen.videoservers

import android.content.Context
import androidx.core.net.toUri
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.VIDHIDE

/*
 * Basado en los extractores de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito sobre SourceHttp/HostGate de la app.
 */

/** VidHide y sus espejos (EarnVids, FileLions): el mismo reproductor empaquetado que StreamWish, con la lista HLS dentro. */
class VidHideServer(context: Context, baseLink: String) : Server(context, baseLink, VIDHIDE, *HOSTS.toTypedArray()) {

    override suspend fun videoServer(): VideoServer? {
        val link = PatternUtil.extractLink(baseLink)
        val unpack = Unpacker.unpack(link)
        val found = Regex(""""((?:https?:)?/[^"]*?m3u8[^"]*?)"""").find(unpack.unpacked)?.groupValues?.get(1) ?: return null
        val host = (unpack.url ?: link).toUri().let { it.scheme + "://" + it.host }
        val master = when {
            found.startsWith("http") -> found
            found.startsWith("//") -> "https:$found"
            else -> host + found
        }
        return VideoServer(name, Option(name, "HLS", master, Headers { add(0, "Referer" to "$host/") }))
    }

    private companion object {
        val HOSTS = listOf("vidhide", "filelions", "earnvids")
    }
}
