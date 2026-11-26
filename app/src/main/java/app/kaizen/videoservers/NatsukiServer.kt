package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.getGated
import app.kaizen.commons.jsoupCookies
import app.kaizen.videoservers.VideoServer.Names.NATSUKI
import org.json.JSONObject

class NatsukiServer(context: Context, baseLink: String) : Server(context, baseLink, NATSUKI, "s=natsuki") {

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val link = JSONObject(jsoupCookies(downLink.replace("embed", "check")).getGated().body().text()).getString("file")
        return VideoServer(NATSUKI, Option(name, null, link))

    }
}
