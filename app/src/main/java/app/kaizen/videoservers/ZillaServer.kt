package app.kaizen.videoservers

import app.kaizen.source.SourceHttp
import app.kaizen.commons.HostGate
import android.content.Context
import app.kaizen.videoservers.VideoServer.Names.ZILLA

class ZillaServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("zilla-networks")

    override val name: String
        get() = ZILLA

    override suspend fun videoServer(): VideoServer? {
        val url = baseLink.replace("/play/", "/m3u8/")
        val response = HostGate.call(url) { SourceHttp.execute(url) { ignoreHttpErrors() } }
        if (response.statusCode != 200 || !response.body().startsWith("#EXTM3U")) {
            throw IllegalStateException()
        }
        return VideoServer(ZILLA,
                mutableListOf(
                        Option(name, null, url, Headers {
                            add("Sec-Fetch-Site" to "same-origin")
                        }),
                ))

    }
}
