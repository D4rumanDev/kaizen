package app.kaizen.videoservers

import kotlinx.coroutines.withContext

import android.content.Context
import app.kaizen.commons.HostGate
import app.kaizen.commons.PatternUtil
import app.kaizen.videoservers.VideoServer.Names.YOURUPLOAD
import kotlinx.coroutines.Dispatchers
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request

class YUServer(context: Context, baseLink: String) : Server(context, baseLink) {

    override val isValid: Boolean
        get() = baseLink.contains("yourupload.com")

    override val name: String
        get() = YOURUPLOAD

    override suspend fun videoServer(): VideoServer? {
        val yuLink = PatternUtil.extractLink(baseLink)
        val videoLink = PatternUtil.getYUvideoLink(withContext(Dispatchers.Main) { Unpacker.getHtml(context, yuLink, 8000).html })
        val client = OkHttpClient().newBuilder()
                .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .allEnabledTlsVersions()
                        .allEnabledCipherSuites()
                        .build()))
                .followRedirects(false).build()
        val request = Request.Builder()
                .url(videoLink)
                .addHeader("Referer", yuLink)
                .build()
        // Con el turno y el corte de HostGate; el 302 con la dirección real es la respuesta esperada, no un fallo.
        val refVideoLink = HostGate.call(videoLink) {
            app.kaizen.commons.RequestCounter.note(request.url.toString())
            client.newCall(request).execute().use { response ->
                HostGate.failureFor(videoLink, response.code)?.let { throw it }
                response.header("Location")
            }
        }
        val headers = Headers()
        headers.addHeader("Range", "bytes=0-")
        headers.addHeader("Referer", "https://www.yourupload.com/")
        return VideoServer(YOURUPLOAD, Option(name, null, refVideoLink, headers))

    }
}