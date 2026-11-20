package app.kaizen.videoservers

import app.kaizen.source.SourceHttp
import app.kaizen.commons.HostGate
import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.iterator
import app.kaizen.videoservers.VideoServer.Names.OKRU
import org.json.JSONObject
import org.jsoup.parser.Parser

class OkruServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("ok.ru")

    override val name: String
        get() = OKRU

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val page = HostGate.call(downLink) { SourceHttp.execute(downLink).parse().html() }
        val source = Regex("data-options=\"(.*?)\"").find(page)?.groupValues?.get(1)
            ?: error("OkruServer: sin data-options en la página (¿cambió o devolvió una verificación?)")
        val jsonText = Parser.unescapeEntities(source, false)
        val videos = JSONObject(JSONObject(jsonText).getJSONObject("flashvars").getString("metadata")).getJSONArray("videos")
        val options = mutableListOf<Option>()
        for(video in videos){
            val url = video.getString("url")
            val name = when(video.getString("name")){
                "mobile" -> "144p"
                "lowest" -> "240p"
                "low" -> "360p"
                "sd" -> "480p"
                "hd" -> "720p"
                "full" -> "1080p"
                "quad" -> "2000p"
                "ultra" -> "4000p"
                else -> "Default"
            }
            options.add(Option(this.name, name, url, Headers { add("User-Agent" to BROWSER_USER_AGENT) }))//mapOf("User-agent" to userAgent)
        }
        check(options.isNotEmpty())
        return VideoServer(OKRU, options, true)
    }
}
