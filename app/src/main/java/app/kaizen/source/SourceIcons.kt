package app.kaizen.source

import app.kaizen.commons.HostGate
import app.kaizen.commons.KaizenStore
import app.kaizen.commons.logError
import app.kaizen.commons.runCatchingCoop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * El logo de cada fuente, leído de su propia portada (los `<link rel="icon">` y `apple-touch-icon` que ella declara) en lugar de adivinar `/apple-touch-icon.png` y
 * `/favicon.ico`: evita las peticiones que dan 404 y los `.ico` que Android no lee. Se pide una vez por fuente (y se repite cada [REFRESH]) y se guarda en
 * `kaizen_source_icons` (caché: se puede vaciar sin perder nada); mientras no hay nada guardado «Fuentes» usa las rutas de siempre.
 */
internal object SourceIcons {
    private const val FILE = "kaizen_source_icons"
    private val REFRESH = 30.days
    private const val MAX_ICONS = 3
    private const val MAX_BYTES = 600_000

    /** Cuándo falló la última vez cada fuente (en memoria): una caída o un 403 no repite la petición cada vez que se abre «Fuentes». */
    private val failedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val RETRY = 6.hours
    private val IP_LITERAL = Regex("""^(\d{1,3}(\.\d{1,3}){3}|\[.*\])$""")

    private val prefs get() = KaizenStore.prefs(FILE)

    /**
     * Los iconos que declara [html] (la portada en [pageUrl]), el mejor primero: primero los `apple-touch-icon` y luego los demás, cada grupo de mayor a menor tamaño declarado.
     * Solo https y solo formatos que la app sabe pintar: se descartan `.ico` y `.svg`.
     */
    fun pick(html: String, pageUrl: String): List<String> {
        data class Icon(val url: String, val touch: Boolean, val area: Int)
        val icons = Jsoup.parse(html, pageUrl).select("link[rel][href]").mapNotNull { link ->
            val rel = link.attr("rel").lowercase().split(Regex("\\s+"))
            if ("icon" !in rel && "apple-touch-icon" !in rel && "apple-touch-icon-precomposed" !in rel) return@mapNotNull null
            val url = link.absUrl("href").substringBefore('#')
            val path = url.substringBefore('?').lowercase()
            // Nada de direcciones de red local ni IP sueltas: una fuente no debe hacer que la app pida algo a la LAN.
            val authority = url.removePrefix("https://").substringBefore('/')
            val iconHost = (if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')).lowercase()
            if (!url.startsWith("https://") || iconHost == "localhost" || iconHost.endsWith(".local") || IP_LITERAL.matches(iconHost) || path.endsWith(".ico") || path.endsWith(".svg") || link.attr("type").contains("svg") || link.attr("type").contains("icon")) return@mapNotNull null
            val side = Regex("(\\d+)x(\\d+)").find(link.attr("sizes"))?.let { it.groupValues[1].toInt() * it.groupValues[2].toInt() } ?: 0
            Icon(url, touch = rel.any { it.startsWith("apple-touch-icon") }, area = side)
        }
        return icons.sortedWith(compareByDescending<Icon> { it.touch }.thenByDescending { it.area }).map { it.url }.distinct().take(MAX_ICONS)
    }

    /** Los iconos guardados de [host] (puede estar vacío: aún no se pidió o la portada no declara ninguno). */
    fun saved(host: String): List<String> = prefs.getString(host, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    /** Pide la portada de [host] si hace más de [REFRESH] que no se miró (o nunca) y guarda lo que declara; devuelve los iconos guardados. Se anota también cuando no hay ninguno, para no repetir la petición. */
    suspend fun refresh(host: String): List<String> = withContext(Dispatchers.IO) {
        val at = prefs.getLong("$host@at", 0L)
        if (at != 0L && System.currentTimeMillis() - at < REFRESH.inWholeMilliseconds) return@withContext saved(host)
        // Una fuente en pausa o que falló hace poco no se vuelve a pedir ahora.
        if (HostGate.isDown(host) || System.currentTimeMillis() - (failedAt[host] ?: 0L) < RETRY.inWholeMilliseconds) return@withContext saved(host)
        val found = runCatchingCoop {
            val url = "https://$host/"
            val reply = HostGate.call(url) { SourceHttp.execute(url) { maxBodySize(MAX_BYTES); ignoreHttpErrors() } }
            val body = reply.body()
            // Solo vale una portada de verdad (con título o enlaces): una página intermedia servida con 200 no se guarda como «sin iconos» durante 30 días.
            if (reply.statusCode in 200..299 && Regex("<title|<link", RegexOption.IGNORE_CASE).containsMatchIn(body)) pick(body, reply.url) else null
        }.onFailure(::logError).getOrNull()
        // Una respuesta que no se pudo leer (sin red, 403 de Cloudflare) no se anota en disco: se reintenta pasadas unas horas.
        if (found != null) prefs.edit().putString(host, found.joinToString("\n")).putLong("$host@at", System.currentTimeMillis()).apply()
        else failedAt[host] = System.currentTimeMillis()
        saved(host)
    }
}
