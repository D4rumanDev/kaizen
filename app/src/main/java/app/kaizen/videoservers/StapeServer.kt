package app.kaizen.videoservers

import app.kaizen.source.SourceHttp
import app.kaizen.commons.HostGate
import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.STAPE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class StapeServer(context: Context, baseLink: String) : Server(context, baseLink, STAPE, "streamtape.com") {

    override suspend fun videoServer(): VideoServer? {
        val downLink = PatternUtil.extractLink(baseLink)
        val link = run {
            val html = withContext(Dispatchers.Main) { Unpacker.getHtml(context, downLink).html }
            val doc = Jsoup.parse(html, "https://streamtape.com")
            doc.select("video[id]").attr("abs:src")
        }
        val videoLink =
            HostGate.call(link) { SourceHttp.execute(link) { maxBodySize(1) }.url }
        check(!videoLink.contains("streamtape_do_not_delete.mp4"))
        return VideoServer(STAPE, Option(name, null, videoLink))

    }
}
