package app.kaizen.videoservers

import android.content.Context
import app.kaizen.videoservers.VideoServer.Names.PDRAIN

class PixelServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("pixeldrain")

    override val name: String
        get() = PDRAIN

    override suspend fun videoServer(): VideoServer? {
        val url = baseLink.replace("/u/", "/api/file/").let {
            if (it.endsWith("?embed")) {
                it.replace("?embed", "?download=")
            } else {
                "$it?download="
            }
        }
        return VideoServer(PDRAIN,
                mutableListOf(
                        Option(name, null, url),
                ))

    }
}
