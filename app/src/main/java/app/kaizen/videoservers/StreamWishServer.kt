package app.kaizen.videoservers

import android.content.Context
import androidx.core.net.toUri
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.executeGated
import app.kaizen.commons.jsoupCookies
import app.kaizen.videoservers.VideoServer.Names.STREAMWISH

class StreamWishServer(context: Context, baseLink: String) : Server(context, baseLink, STREAMWISH, "streamwish.to", "sfastwish.com") {

    override val canStream: Boolean
        get() = true

    override suspend fun videoServer(): VideoServer? {
        val downLink = "https://sfastwish.com/e/${PatternUtil.extractLink(baseLink).substringAfterLast("/")}"
        for (i in 0..3) {
            val unpack = if (i >= 2) {
                run { Unpacker.unpackWeb(context, downLink) }
            } else {
                run { Unpacker.unpack(downLink) }
            }
            val host = unpack.url!!.toUri().let { it.scheme + "://" + it.host }
            val options = "hls\\d\": ?\"([^\"]*)".toRegex().findAll(unpack.unpacked).toList().reversed().mapIndexed { index, it ->
                val (link) = it.destructured
                Option(name, "HLS${index + 1}", if (link.startsWith("http")) link else host + link)
            }.toMutableList()
            val selected = options.firstOrNull { option ->
                jsoupCookies(option.url)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .executeGated().let { it.statusCode() in (200..299) && it.body().startsWith("#") }
            }
            if (selected == null) continue
            return VideoServer(name, selected)
        }
        return null
    }
}
