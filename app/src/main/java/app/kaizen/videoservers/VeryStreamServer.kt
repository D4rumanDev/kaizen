package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.getGated
import app.kaizen.commons.jsoupCookies
import app.kaizen.videoservers.VideoServer.Names.VERYSTREAM

class VeryStreamServer internal constructor(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("verystream.com")

    override val name: String
        get() = VERYSTREAM

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val link = "https://verystream.com/gettoken/${jsoupCookies(downLink).getGated().select("videolink").text()}?mime=true"
        return VideoServer(name, Option(name, null, link))

    }
}
