package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.source.SourceHttp
import app.kaizen.videoservers.VideoServer.Names.GOCDN
import org.json.JSONObject

class GoCDNServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("gocdn")

    override val name: String
        get() = GOCDN

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val json = JSONObject(SourceHttp.body("https://streamium.xyz/gocdn.php?v=${downLink.substringAfterLast("#")}"))
        return VideoServer(GOCDN, Option(GOCDN, null, json.getString("file")))

    }
}
