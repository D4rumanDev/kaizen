package app.kaizen.videoservers

import android.content.Context
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.MIXDROP

/*
 * Basado en los extractores de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito sobre SourceHttp/HostGate de la app.
 */
class MixDropServer(context: Context, baseLink: String) : Server(context, baseLink, MIXDROP, *HOSTS.toTypedArray()) {

    override suspend fun videoServer(): VideoServer? {
        // /f/ es la página de descarga; el reproductor con MDCore es /e/ (comprobado con curl, 2026-11-06).
        val link = PatternUtil.extractLink(baseLink).replace("/f/", "/e/")
        val unpacked = Unpacker.unpack(link).unpacked
        val wurl = unpacked.substringAfter("Core.wurl=\"", "").substringBefore('"').takeIf { it.isNotBlank() } ?: return null
        val video = if (wurl.startsWith("//")) "https:$wurl" else wurl
        return VideoServer(name, Option(name, null, video, Headers {
            add(0, "Referer" to "https://mixdrop.co/")
            add(1, "User-Agent" to BROWSER_USER_AGENT)
        }))
    }

    private companion object {
        val HOSTS = listOf("mixdrop", "mxdrop", "mixdroop")
    }
}
