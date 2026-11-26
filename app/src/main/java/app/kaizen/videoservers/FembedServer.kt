package app.kaizen.videoservers

import app.kaizen.source.SourceHttp
import app.kaizen.commons.HostGate
import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.iterator
import app.kaizen.videoservers.VideoServer.Names.FEMBED
import org.json.JSONObject

class FembedServer(context: Context, baseLink: String) : Server(context, baseLink, FEMBED, "fembed", "embedsito.com") {

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val fLink = if (downLink.contains("value="))
            "https://embedsito.com/v/${downLink.substring(downLink.lastIndexOf("=") + 1)}"
        else
            downLink
        val apiUrl = fLink.replace("/v/", "/api/source/")
        val json = JSONObject(HostGate.call(apiUrl) { SourceHttp.execute(apiUrl) { method("POST") }.body() })
        check(json.getBoolean("success")) { "Request was not succeeded" }
        val array = json.getJSONArray("data")
        val options = mutableListOf<Option>()
        for (item in array) {
            options.add(Option(name, item.getString("label"), item.getString("file")))
        }
        return VideoServer(name, options, true)

    }
}
