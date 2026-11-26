package app.kaizen.commons.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import app.kaizen.commons.connectTimeout
import app.kaizen.commons.readTimeout
import app.kaizen.commons.writeTimeout

/**
 * [RemoteStore] sobre un servidor WebDAV (Nextcloud, Synology, un servidor propio…): lista con `PROPFIND`, lee con `GET` y escribe con `PUT`. [baseUrl] es una carpeta (`https`,
 * acabada en `/`); si no existe, se crea con `MKCOL`. La contraseña solo viaja en la cabecera `Authorization` de las peticiones a esa URL y no se anota en ningún registro.
 */
class WebDavStore(
    private val baseUrl: String,
    private val user: String,
    private val password: String,
    private val client: OkHttpClient = DEFAULT_CLIENT
) : RemoteStore {
    private fun request(name: String = ""): Request.Builder = Request.Builder().url(baseUrl + name).apply {
        if (user.isNotEmpty()) header("Authorization", Credentials.basic(user, password, Charsets.UTF_8))
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        val request = request().method("PROPFIND", "".toRequestBody()).header("Depth", "1").build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext emptyList()
            if (response.code != 207 && !response.isSuccessful) throw IOException("WebDAV: ${response.code} al listar")
            parseNames(String(response.body.boundedBytes(MAX_LIST_BYTES), Charsets.UTF_8))
        }
    }

    override suspend fun read(name: String): ByteArray? = withContext(Dispatchers.IO) {
        client.newCall(request(name).get().build()).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("WebDAV: ${response.code} al leer")
            response.body.boundedBytes(DeviceSync.MAX_BYTES.toLong())
        }
    }

    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        fun put() = client.newCall(request(name).put(bytes.toRequestBody(JSON)).build()).execute().use { it.code }
        var code = put()
        // 404/409: la carpeta no existe; se crea y se vuelve a intentar una vez.
        if (code == 404 || code == 409) {
            client.newCall(request().method("MKCOL", null).build()).execute().close()
            code = put()
        }
        if (code !in 200..299) throw IOException("WebDAV: $code al escribir")
    }

    override suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        client.newCall(request(name).delete().build()).execute().use { response ->
            if (response.code != 404 && !response.isSuccessful) throw IOException("WebDAV: ${response.code} al borrar")
        }
    }

    companion object {
        /** Una lista de carpeta: unos KB; esto corta a un servidor que mande mucho más. */
        private const val MAX_LIST_BYTES = 2L * 1024 * 1024

        /** Lee el cuerpo sin pasar de [max] bytes: avisa antes de cargar nada si la cabecera ya lo dice y no lee de más si miente. */
        internal fun ResponseBody.boundedBytes(max: Long): ByteArray {
            if (contentLength() > max) throw IOException("WebDAV: respuesta demasiado grande")
            val source = source()
            source.request(max + 1)
            if (source.buffer.size > max) throw IOException("WebDAV: respuesta demasiado grande")
            return source.readByteArray()
        }

        private val JSON = "application/json".toMediaType()
        private val HREF = Regex("<(?:[A-Za-z0-9]+:)?href>([^<]+)</(?:[A-Za-z0-9]+:)?href>", RegexOption.IGNORE_CASE)

        private val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(20.seconds).readTimeout(60.seconds).writeTimeout(60.seconds).followRedirects(false).build()
        }

        /** Los nombres de fichero de una respuesta `PROPFIND` (la última parte de cada `href`, sin escapes `%XX`). */
        internal fun parseNames(xml: String): List<String> = HREF.findAll(xml).map { java.net.URLDecoder.decode(it.groupValues[1].trimEnd('/').substringAfterLast('/'), "UTF-8") }.filter { it.isNotEmpty() }.toList()
    }
}
