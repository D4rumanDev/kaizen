package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.getGated
import app.kaizen.commons.jsoupCookies
import app.kaizen.videoservers.VideoServer.Names.NATSUKI
import org.json.JSONObject

class NatsukiServer internal constructor(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("s=natsuki")

    override val name: String
        get() = NATSUKI

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val link = JSONObject(jsoupCookies(downLink.replace("embed", "check")).getGated().body().text()).getString("file")
        return VideoServer(NATSUKI, Option(name, null, link))

    }
}
