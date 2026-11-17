package app.kaizen.videoservers

import app.kaizen.source.SourceHttp
import app.kaizen.commons.HostGate
import app.kaizen.commons.logError
import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.RV

class RVServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("rapidvideo") || baseLink.contains("&server=rv")

    override val name: String
        get() = RV

    override suspend fun videoServer(): VideoServer? {
        var downLink = PatternUtil.extractLink(baseLink).replace("&q=720p|&q=480p|&q=360p".toRegex(), "")
        if (downLink.contains("&server=rv"))
            downLink = PatternUtil.getRapidLink(downLink)
        val needPost = HostGate.call(downLink) { SourceHttp.execute(downLink).parse().html() }.contains("Please click on this button to open this video")
        val videoServer = VideoServer(RV)
        // Una calidad rota no invalida las demás.
        for (quality in listOf("720p", "480p", "360p")) {
            runCatching {
                videoServer.addOption(Option(name, quality, PatternUtil.getRapidVideoLink(getHtml("$downLink&q=$quality", needPost))))
            }.onFailure(::logError)
        }
        return if (videoServer.options.size > 0) videoServer else null
    }

    private fun getHtml(link: String, needPost: Boolean): String {
        return try {
            if (needPost)
                HostGate.call(link) { SourceHttp.execute("$link#") { method("POST"); data("block", "1") }.parse().html() }
            else
                HostGate.call(link) { SourceHttp.execute(link).parse().html() }
        } catch (e: Exception) {
            logError(e)
            ""
        }

    }
}
