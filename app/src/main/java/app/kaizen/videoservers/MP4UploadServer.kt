package app.kaizen.videoservers

import android.content.Context
import app.kaizen.videoservers.VideoServer.Names.MP4UPLOAD

class MP4UploadServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("mp4upload")

    override val name: String
        get() = MP4UPLOAD

    override suspend fun videoServer(): VideoServer? {
        val url = run {
            val regex = Regex(".*video.mp4")
            Unpacker.listenResources(context, baseLink, onRequest = { it != null && it.matches(regex) })
        }
        return VideoServer(
            VideoServer.Names.UPNServer,
            mutableListOf(
                Option(name, null, url, Headers {
                    add(0, "Referer" to "https://www.mp4upload.com/")
                })
            ))

    }

}
