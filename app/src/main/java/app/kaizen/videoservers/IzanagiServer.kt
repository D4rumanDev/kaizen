package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.getGated
import app.kaizen.commons.jsoupCookies
import app.kaizen.videoservers.VideoServer.Names.IZANAGI
import org.json.JSONObject

class IzanagiServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("s=izanagi")

    override val name: String
        get() = IZANAGI

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val link = JSONObject(jsoupCookies(downLink.replace("embed", "check")).getGated().body().text()).getString("file").replace("\\", "")
        return VideoServer(IZANAGI,
                mutableListOf(
                        Option(name, null, link),
                        Option(name, null, link.replace("/".toRegex(), "//").replace(":////", "://"))
                ))

    }
}
