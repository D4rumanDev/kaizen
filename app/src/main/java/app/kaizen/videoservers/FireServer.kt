package app.kaizen.videoservers

import app.kaizen.source.SourceHttp
import app.kaizen.commons.HostGate
import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.commons.getGated
import app.kaizen.commons.jsoupCookies
import app.kaizen.videoservers.VideoServer.Names.FIRE

class FireServer(context: Context, baseLink: String) : Server(context, baseLink, FIRE, "efire.php") {

    override suspend fun videoServer(): VideoServer? {
        val frame = PatternUtil.extractLink(baseLink)
        // Sin coincidencia se dice qué faltaba (un NPE no lo diría): `verified()` lo captura y lo anota.
        val mediaFunc = jsoupCookies(frame).getGated().select("script").last()?.outerHtml()
            ?: error("FireServer: la página no trae ningún script (¿cambió o devolvió una verificación?)")
        val mediaLink = PatternUtil.extractMediaLink(mediaFunc)
        val download = HostGate.call(mediaLink) { SourceHttp.execute(mediaLink).parse() }.select("a[href~=http://download.*]").first()?.attr("href")
            ?: error("FireServer: sin enlace de descarga en la página de Mediafire (¿cambió?)")
        return VideoServer(FIRE, Option(name, null, download))

    }
}