package app.kaizen.videoservers

import android.content.Context
import app.kaizen.videoservers.VideoServer.Names.UPNServer

class UPNServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("animeav1.uns.bio")

    override val name: String
        get() = UPNServer

    override suspend fun videoServer(): VideoServer? {
        val url = run {
            val regex = Regex(".*master.m3u8.*|https://\\d+\\.\\d+\\.\\d+\\.\\d+.*mp4.*")
            Unpacker.listenResources(
                context,
                "$baseLink&dl=1",
                onRequest = { it != null && it.matches(regex) },
                timeout = 15000,
                executeOnFinish = "javascript:setInterval(function(){var el=document.getElementsByClassName('downloader-button')[0];if(el)el.click();},100);"
            )
        }
        return VideoServer(UPNServer,
                mutableListOf(
                        Option(name, null, url, Headers {
                            add(0, "Referer" to "https://animeav1.uns.bio/")
                        }),
                ))

    }
}
