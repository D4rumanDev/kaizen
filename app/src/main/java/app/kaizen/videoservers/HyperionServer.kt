package app.kaizen.videoservers

import app.kaizen.commons.logError
import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.getGated
import app.kaizen.commons.jsoupCookies
import app.kaizen.videoservers.VideoServer.Names.HYPERION
import org.json.JSONObject

class HyperionServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("server=hyperion")

    override val name: String
        get() = HYPERION

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val options = JSONObject(jsoupCookies(downLink.replace("embed_hyperion", "check")).getGated().body().text())
        val array = options.getJSONArray("streams")
        val videoServer = VideoServer(HYPERION)
        // Una calidad rota no invalida las demás.
        for (i in 0 until array.length()) {
            runCatching {
                val stream = array.getJSONObject(i)
                val label = stream.getInt("label")
                if (label in QUALITIES) videoServer.addOption(Option(name, "${label}p", stream.getString("file")))
            }.onFailure(::logError)
        }
        runCatching { videoServer.addOption(Option(name, "Direct", options.getString("direct"))) }.onFailure(::logError)
        return videoServer
    }

    private companion object {
        val QUALITIES = setOf(360, 480, 720)
    }
}
