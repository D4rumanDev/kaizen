package app.kaizen.commons

import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import app.kaizen.App
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response as UpstreamResponse
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * Kaizen: proxy HTTP local para reproductores externos.
 *
 * Muchos servidores exigen cabeceras (Referer, User-Agent, cookies) que los reproductores
 * externos no envían. El proxy escucha solo en 127.0.0.1, añade esas cabeceras y, en las
 * listas HLS, reescribe cada URL para que los segmentos también pasen por él.
 *
 * Cada sesión solo puede pedir los hosts de su vídeo (el original y los que aparezcan en sus
 * listas o redirecciones), y caduca tras [SESSION_TTL_MS] sin uso.
 */
object KaizenProxy {
    private const val FIRST_PORT = 6992
    private const val LAST_PORT = 7010
    private const val MAX_SESSIONS = 16
    const val SESSION_TTL_MS = 6 * 60 * 60 * 1000L
    private val PLAYLIST_MARKER = "#EXTM3U".toByteArray()

    private class Session(val headers: Map<String, String>, initialHost: String) {
        val allowedHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { add(initialHost) }

        @Volatile
        var lastUsed = System.currentTimeMillis()
    }

    /** El cliente de la app más HostGate: se sabe si falla un servidor de vídeo y, en pausa, no se le pide nada. */
    private val client by lazy { AppHttpClient.streaming().newBuilder().addInterceptor(HostGateInterceptor).build() }

    private val sessions = LinkedHashMap<String, Session>(16, 0.75f, true)
    private var server: ProxyServer? = null

    /** Cierra el servidor y olvida las sesiones (al salir de la app). */
    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        sessions.clear()
    }

    /** Momento del último uso de cualquier sesión (para que el servicio sepa cuándo parar). */
    @Volatile
    var lastActivity = 0L
        private set

    /** URL local que sirve [target] con las [headers] indicadas. Lanza IOException si no arranca. */
    @Synchronized
    fun urlFor(target: String, headers: Map<String, String>): String {
        val host = target.toHttpUrlOrNull()?.host ?: throw IOException("URL de vídeo no válida")
        val running = server ?: startServer().also { server = it }
        evictExpired()
        val id = UUID.randomUUID().toString()
        sessions[id] = Session(headers, host)
        lastActivity = System.currentTimeMillis()
        KaizenProxyService.start(App.context)
        return running.localUrl(id, target)
    }

    @Synchronized
    private fun session(id: String): Session? = sessions[id]?.also {
        it.lastUsed = System.currentTimeMillis()
        lastActivity = it.lastUsed
    }

    @Synchronized
    private fun evictExpired() {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { now - it.value.lastUsed > SESSION_TTL_MS }
        while (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.keys.first())
    }

    private fun startServer(): ProxyServer {
        for (port in FIRST_PORT..LAST_PORT) {
            try {
                return ProxyServer(port).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            } catch (_: IOException) {
                // Puerto ocupado: se prueba el siguiente.
            }
        }
        throw IOException("No hay puerto libre para el proxy entre $FIRST_PORT y $LAST_PORT")
    }

    private class ProxyServer(private val port: Int) : NanoHTTPD("127.0.0.1", port) {

        /**
         * La URL de destino va en la ruta (Base64) y termina en un nombre con extensión:
         * el demuxer HLS de FFmpeg (NOVA, VLC…) rechaza segmentos cuya extensión no cuadra
         * con el formato detectado.
         */
        fun localUrl(id: String, target: String, extension: String = guessExtension(target)): String {
            val encoded = Base64.encodeToString(target.toByteArray(), BASE64_FLAGS)
            return "http://127.0.0.1:$port/s/$id/$encoded/media.$extension"
        }

        override fun useGzipWhenAccepted(r: Response): Boolean = false

        override fun serve(session: IHTTPSession): Response {
            val parts = session.uri.removePrefix("/s/").split('/')
            val id = parts.getOrNull(0) ?: return text(Response.Status.BAD_REQUEST, "Petición no válida")
            val proxySession = session(id) ?: return text(Response.Status.NOT_FOUND, "Sesión desconocida")
            val target = parts.getOrNull(1)?.let { runCatching { String(Base64.decode(it, BASE64_FLAGS)) }.getOrNull() }
                ?: return text(Response.Status.BAD_REQUEST, "Falta la URL")
            val host = target.toHttpUrlOrNull()?.host ?: return text(Response.Status.BAD_REQUEST, "URL no válida")
            if (host !in proxySession.allowedHosts) return text(Response.Status.FORBIDDEN, "Host no permitido")
            return try {
                forward(id, proxySession, target, session.headers["range"])
            } catch (e: HostGate.HostDownException) {
                // HostGate tiene a ese servidor en pausa: el reproductor externo recibe un 503 y no se le pide nada.
                text(Response.Status.SERVICE_UNAVAILABLE, "Servidor en pausa")
            } catch (e: Exception) {
                logError(e)
                text(Response.Status.INTERNAL_ERROR, "Error del proxy")
            }
        }

        private fun text(status: Response.Status, message: String) = newFixedLengthResponse(status, MIME_PLAINTEXT, message)

        private fun request(target: String, headers: Map<String, String>, range: String?) =
            Request.Builder().url(target).apply {
                headers.forEach { (k, v) -> header(k, v) }
                if (range != null) header("Range", range)
            }.build()

        private fun forward(id: String, session: Session, target: String, range: String?): Response {
            val upstream = client.newCall(request(target, session.headers, range)).execute()
            // Las redirecciones pueden llevar a otro host (CDN): ese host queda permitido para la sesión.
            val finalUrl = upstream.request.url
            session.allowedHosts.add(finalUrl.host)
            val contentType = upstream.header("Content-Type") ?: "application/octet-stream"
            if (upstream.isSuccessful && isPlaylist(target, contentType, upstream)) {
                val text = upstream.body.string()
                upstream.close()
                return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl",
                    rewritePlaylist(id, session, finalUrl.toString(), text))
            }
            return passThrough(upstream, contentType)
        }

        private fun passThrough(upstream: UpstreamResponse, contentType: String): Response {
            val body = upstream.body
            val status = Response.Status.lookup(upstream.code) ?: Response.Status.OK
            val length = body.contentLength()
            val response = if (length >= 0)
                newFixedLengthResponse(status, contentType, body.byteStream(), length)
            else
                newChunkedResponse(status, contentType, body.byteStream())
            upstream.header("Content-Range")?.let { response.addHeader("Content-Range", it) }
            response.addHeader("Accept-Ranges", upstream.header("Accept-Ranges") ?: "bytes")
            return response
        }

        /** Por tipo, por extensión o (servidores que la sirven como text/plain) por la cabecera #EXTM3U. */
        private fun isPlaylist(url: String, contentType: String, upstream: UpstreamResponse): Boolean {
            if (contentType.contains("mpegurl", ignoreCase = true)) return true
            val path = url.substringBefore('?')
            if (path.endsWith(".m3u8", ignoreCase = true)) return true
            if (contentType.startsWith("video/") || contentType.startsWith("audio/")) return false
            val head = upstream.peekBody(PLAYLIST_MARKER.size.toLong() + 3).bytes()
            val start = if (head.size >= 3 && head[0] == 0xEF.toByte()) 3 else 0
            return head.size - start >= PLAYLIST_MARKER.size &&
                head.copyOfRange(start, start + PLAYLIST_MARKER.size).contentEquals(PLAYLIST_MARKER)
        }

        /** Reescribe las URIs de una lista HLS (líneas y atributos URI="...") hacia el proxy. */
        private fun rewritePlaylist(id: String, session: Session, base: String, text: String): String {
            val baseUrl = base.toHttpUrlOrNull() ?: return text
            val lines = text.lines()

            fun resolve(ref: String): String? = baseUrl.resolve(ref.trim())?.also { session.allowedHosts.add(it.host) }?.toString()

            val segmentExtension by lazy { probeSegmentExtension(session, lines, ::resolve) }
            var nextIsPlaylist = false
            return lines.joinToString(separator = "\n") { line ->
                when {
                    line.isBlank() -> line
                    line.startsWith("#") -> {
                        nextIsPlaylist = line.startsWith("#EXT-X-STREAM-INF")
                        val attrExtension = when {
                            line.startsWith("#EXT-X-MAP") -> "mp4"
                            line.startsWith("#EXT-X-KEY") -> "key"
                            else -> "m3u8"
                        }
                        URI_ATTRIBUTE.replace(line) { m ->
                            val resolved = resolve(m.groupValues[1]) ?: return@replace m.value
                            "URI=\"${localUrl(id, resolved, attrExtension)}\""
                        }
                    }
                    else -> {
                        val resolved = resolve(line) ?: return@joinToString line
                        val extension = if (nextIsPlaylist) "m3u8" else knownExtension(resolved) ?: segmentExtension
                        nextIsPlaylist = false
                        localUrl(id, resolved, extension)
                    }
                }
            }
        }

        /** Mira los primeros bytes del primer segmento para saber si es MPEG-TS o MP4. */
        private fun probeSegmentExtension(session: Session, lines: List<String>, resolve: (String) -> String?): String {
            val first = lines.firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.let(resolve) ?: return "ts"
            return try {
                client.newCall(request(first, session.headers, "bytes=0-15")).execute().use { response ->
                    val bytes = response.body.byteStream().readUpTo(16)
                    val box = if (bytes.size >= 8) String(bytes, 4, 4, Charsets.ISO_8859_1) else ""
                    when {
                        bytes.isNotEmpty() && bytes[0] == 0x47.toByte() -> "ts"
                        box in setOf("ftyp", "styp", "moof", "sidx") -> "mp4"
                        else -> "ts"
                    }
                }
            } catch (e: Exception) {
                logError(e)
                "ts"
            }
        }
    }

    private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    private val URI_ATTRIBUTE = Regex("URI=\"([^\"]+)\"")
    private val KNOWN_EXTENSIONS = setOf("m3u8", "ts", "mp4", "m4s", "m4v", "m4a", "aac", "mkv", "webm", "key", "vtt")

    private fun guessExtension(url: String): String =
        knownExtension(url) ?: if ("cf-master" in url || "/m3u8/" in url) "m3u8" else "mp4"

    private fun knownExtension(url: String): String? {
        val ext = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext.takeIf { it in KNOWN_EXTENSIONS }
    }

    private fun InputStream.readUpTo(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(buffer, read, n - read)
            if (r < 0) break
            read += r
        }
        return buffer.copyOf(read)
    }
}
