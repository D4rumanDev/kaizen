package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.STREAMHUB

/*
 * Basado en los extractores de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito sobre SourceHttp/HostGate de la app.
 */
class StreamHubServer internal constructor(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("streamhub")

    override val name: String
        get() = STREAMHUB

    override suspend fun videoServer(): VideoServer? {
        val unpacked = Unpacker.unpack(PatternUtil.extractLink(baseLink)).unpacked
        val id = Regex("""urlset\|(.*?)\|""").find(unpacked)?.groupValues?.get(1) ?: return null
        val sub = Regex("""width\|(.*?)\|""").find(unpacked)?.groupValues?.get(1) ?: return null
        return VideoServer(name, Option(name, "HLS", "https://$sub.streamhub.ink/hls/,$id,.urlset/master.m3u8", null))
    }
}
