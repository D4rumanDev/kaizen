package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil

class WebServer (context: Context, baseLink: String, val serverName: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = true

    override val name: String
        get() = "$serverName (WEB)"

    override val canStream: Boolean
        get() = false

    override suspend fun videoServer(): VideoServer? {
        return VideoServer(serverName, Option(name, null, PatternUtil.extractLink(baseLink)))
    }
}
