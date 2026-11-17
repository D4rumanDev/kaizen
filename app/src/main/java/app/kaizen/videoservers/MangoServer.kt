package app.kaizen.videoservers

import app.kaizen.source.SourceHttp
import app.kaizen.commons.HostGate
import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.MANGO
import java.util.regex.Pattern

class MangoServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("streamango.com") && !baseLink.contains("%")

    override val name: String
        get() = MANGO

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val html = HostGate.call(downLink) { SourceHttp.execute(downLink).parse().html() }
        val matcher = Pattern.compile("type:\"video/mp4\",src:d\\('([^']+)',(\\d+)\\)").matcher(html)
        matcher.find()
        val hash = matcher.group(1)!!
        val key = Integer.parseInt(matcher.group(2)!!)
        var file = KDecoder.decodeMango(hash, key)
        if (file.isNullOrBlank())
            return null
        if (file.startsWith("//"))
            file = file.replaceFirst("//".toRegex(), "https://")
        return VideoServer(MANGO, Option(name, null, file))

    }
}