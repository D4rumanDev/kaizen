package app.kaizen.videoservers

import app.kaizen.source.SourceHttp
import android.content.Context
import app.kaizen.commons.HostGate
import app.kaizen.commons.decodeBase64Text

/**
 * Reproductores propios de jkanime («Desu» = `jkplayer/um`, «Magi» = `jkplayer/umv`). La página del
 * reproductor trae el HLS en claro (`url: '…m3u8'`, `atob('…')` o `<source src='…m3u8'>`); el
 * .m3u8 no pide Referer ni cookies, pero sí un User-Agent de navegador: con el de ExoPlayer el CDN da 403
 * (comprobado con curl, 2026-10-12).
 */
class JkPlayerServer(context: Context, baseLink: String) : Server(context, baseLink, nameOf(baseLink), "jkanime.net/jkplayer/") {

    override suspend fun videoServer(): VideoServer? {
        val html = HostGate.call(baseLink) {
            SourceHttp.execute(baseLink) { referrer("https://jkanime.net/") }.body()
        }
        val url = streamUrl(html) ?: return null
        return VideoServer(name, Option(name, null, url, Headers { add("User-Agent" to BROWSER_USER_AGENT) }))
    }

    companion object {
        private val PLAIN_M3U8 = Regex("""https://[^'"\s]+\.m3u8[^'"\s]*""")
        private val ATOB = Regex("""atob\(\s*'([A-Za-z0-9+/=]+)'\s*\)""")

        /** Nombre que muestra jkanime para cada reproductor. */
        fun nameOf(link: String): String = when (link.substringAfter("/jkplayer/").substringBefore("?")) {
            "um" -> "Desu"
            "umv" -> "Magi"
            else -> "JKPlayer"
        }

        /** Primer HLS de la página: en claro o codificado con `atob`. */
        fun streamUrl(html: String): String? =
            PLAIN_M3U8.find(html)?.value
                ?: ATOB.findAll(html).mapNotNull { decodeBase64Text(it.groupValues[1]) }.firstOrNull { ".m3u8" in it }
    }
}
