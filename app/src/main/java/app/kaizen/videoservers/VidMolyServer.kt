package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.VIDMOLY

/*
 * Basado en los extractores de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito sobre SourceHttp/HostGate de la app.
 */
class VidMolyServer internal constructor(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("vidmoly")

    override val name: String
        get() = VIDMOLY

    override suspend fun videoServer(): VideoServer? {
        val link = PatternUtil.extractLink(baseLink)
        val html = Unpacker.unpack(link).unpacked
        val sources = Regex("""sources: (.*?\]),""").find(html)?.groupValues?.get(1) ?: return null
        val urls = Regex("""file:"(.*?)"""").findAll(sources).map { it.groupValues[1] }.toList()
        val options = urls.mapIndexed { i, url ->
            Option(name, "HLS${i + 1}", url, Headers { add(0, "Origin" to "https://vidmoly.to"); add(1, "Referer" to "https://vidmoly.to/") })
        }.toMutableList()
        return options.takeIf { it.isNotEmpty() }?.let { VideoServer(name, it) }
    }
}
