package app.kaizen.videoservers

import app.kaizen.source.SourceHttp
import app.kaizen.commons.HostGate
import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.getGated
import app.kaizen.commons.jsoupCookies
import app.kaizen.videoservers.VideoServer.Names.FIRE

class FireServer internal constructor(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("efire.php")

    override val name: String
        get() = FIRE

    override suspend fun videoServer(): VideoServer? {
        val frame = PatternUtil.extractLink(baseLink)
        val mediaFunc = jsoupCookies(frame).getGated().select("script").last()!!.outerHtml()
        val mediaLink = PatternUtil.extractMediaLink(mediaFunc)
        val download = HostGate.call(mediaLink) { SourceHttp.execute(mediaLink).parse() }.select("a[href~=http://download.*]").first()!!.attr("href")
        return VideoServer(FIRE, Option(name, null, download))

    }
}