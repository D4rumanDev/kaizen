package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.getGated
import app.kaizen.commons.jsoupCookies
import app.kaizen.videoservers.VideoServer.Names.FENIX
import org.json.JSONObject

class FenixServer(context: Context, baseLink: String) : Server(context, baseLink, FENIX, "s=fenix") {

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val link = JSONObject(jsoupCookies(downLink.replace("embed", "check")).getGated().body().text()).getString("file")
        return VideoServer(FENIX, Option(name, null, link))
    }
}
