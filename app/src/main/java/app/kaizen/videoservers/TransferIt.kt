package app.kaizen.videoservers

import android.content.Context
import android.webkit.WebSettings
import app.kaizen.videoservers.VideoServer.Names.TRANSFERIT
import kotlin.time.Duration.Companion.seconds

class TransferIt(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("transfer.it")

    override val name: String
        get() = TRANSFERIT

    override suspend fun videoServer(): VideoServer? {
        var ua = WebSettings.getDefaultUserAgent(context)
        val url = run {
            ua = app.kaizen.commons.UserAgent.current()
            Unpacker.listenResources(
                context,
                baseLink,
                userAgent = ua,
                onRequest = { it != null && it.contains("api.mega.co.nz") && it.contains("mp4") },
                timeout = 15.seconds,
                executeOnFinish = "javascript:setInterval(function(){var el=document.getElementsByClassName('js-download')[0];if(el)el.click();},5000);"
            )
        }
        return VideoServer(TRANSFERIT,
                mutableListOf(
                        Option(name, null, url, Headers {
                            add("User-Agent" to ua)
                            add("Referer" to "https://transfer.it/")
                        })
                ), true)

    }
}
