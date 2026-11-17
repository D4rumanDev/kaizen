package app.kaizen.videoservers

import android.content.Context
import androidx.core.net.toUri
import app.kaizen.commons.HostGate
import app.kaizen.commons.PatternUtil
import app.kaizen.source.SourceHttp
import app.kaizen.videoservers.VideoServer.Names.DOOD

/*
 * Basado en los extractores de Aniyomi (Kohi-den/extensions-source, licencia Apache 2.0); ver THIRD_PARTY.md. Reescrito sobre SourceHttp/HostGate de la app.
 */
class DoodServer internal constructor(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = HOSTS.any { baseLink.contains(it) }

    override val name: String
        get() = DOOD

    override suspend fun videoServer(): VideoServer? {
        // Las webs dan a veces la página de descarga (/d/): el reproductor, con el enlace al vídeo (pass_md5), es /e/ (comprobado con curl, 2026-11-06).
        val link = PatternUtil.extractLink(baseLink).replace("/d/", "/e/")
        val page = HostGate.call(link) { SourceHttp.execute(link) }
        val finalUrl = page.url
        val host = finalUrl.toUri().let { it.scheme + "://" + it.host }
        val pass = Regex("/pass_md5/[^']*").find(page.body())?.value ?: return null
        val md5 = host + pass
        val token = md5.substringAfterLast("/")
        val start = HostGate.call(md5) { SourceHttp.execute(md5) { referrer(finalUrl) } }.body()
        val alphabet = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        val random = (1..10).map { alphabet.random() }.joinToString("")
        val video = "$start$random?token=$token&expiry=${System.currentTimeMillis()}"
        return VideoServer(name, Option(name, null, video, Headers { add(0, "Referer" to "$host/") }))
    }

    private companion object {
        val HOSTS = listOf("dood", "d0o0d", "ds2play", "do7go", "dooood")
    }
}
