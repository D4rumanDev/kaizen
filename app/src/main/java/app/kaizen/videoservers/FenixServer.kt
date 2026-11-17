package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.getGated
import app.kaizen.commons.jsoupCookies
import app.kaizen.videoservers.VideoServer.Names.FENIX
import org.json.JSONObject

class FenixServer internal constructor(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("s=fenix")

    override val name: String
        get() = FENIX

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val link = JSONObject(jsoupCookies(downLink.replace("embed", "check")).getGated().body().text()).getString("file")
        return VideoServer(FENIX, Option(name, null, link))
    }
}
