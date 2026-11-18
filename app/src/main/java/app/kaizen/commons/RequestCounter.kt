package app.kaizen.commons

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * Cuántas peticiones HTTP hace la app a cada web por día, **solo para conocimiento interno** (¿saturamos el servidor de una fuente?). No es una
 * estadística de uso ni se enseña en la interfaz: se consulta por ADB (`node tools/tv.js calls`). Una petición es una salida real a la red
 * (no cuenta lo que se sirve de la caché), y se separa el trabajo en segundo plano (recorrido del catálogo, relleno de fichas, emparejado con AniList)
 * del uso normal ([Background]) para poder ver el uso sin el catálogo. Cuenta página, API, extractores, portadas y los segmentos del vídeo; no cuenta lo que
 * cargan los WebView (solo la dirección principal, y no se anota), ni AniList, Animeoteca o AniSkip (servicios, no fuentes).
 */
object RequestCounter {
    /** Marca el trabajo que ocurre dentro como de segundo plano: `withContext(RequestCounter.Background) { … }`. */
    object Background : AbstractCoroutineContextElement(Key), ThreadContextElement<Boolean> {
        object Key : CoroutineContext.Key<Background>

        override fun updateThreadContext(context: CoroutineContext): Boolean = flag.get().also { flag.set(true) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Boolean) { flag.set(oldState) }
    }

    private val flag = object : ThreadLocal<Boolean>() { override fun initialValue() = false }

    // Las direcciones completas solo se guardan de las webs de las fuentes: un CDN o un servidor de vídeo puede llevar un token en la ruta.
    private val ledger = CallLedger(pathHosts = ::isSourceHost)

    // Los servidores de las fuentes, calculados una vez (la lista solo cambia al reiniciar la app).
    @Volatile private var sourceHosts: Set<String>? = null

    private fun isSourceHost(host: String): Boolean {
        val set = sourceHosts ?: runCatching { app.kaizen.source.AnimeSources.all.map { it.host.removePrefix("www.") }.toSet() }.getOrNull()?.also { sourceHosts = it } ?: return false
        return host in set || set.any { host.endsWith(".$it") }
    }

    /** Si el código que se ejecuta ahora está marcado como de segundo plano ([Background]); para las pruebas. */
    internal fun inBackground(): Boolean = flag.get()
    private const val FILE = "kaizen_request_counts"
    private val FLUSH = 60.seconds
    private val loaded = java.util.concurrent.atomic.AtomicBoolean(false)
    private val flushScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun day(): String = java.time.LocalDate.now().toString()

    private fun load() {
        if (!loaded.compareAndSet(false, true)) return
        runCatching {
            ledger.load(app.kaizen.commons.KaizenStore.prefs(FILE).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap())
        }
    }

    /** Anota una petición a [url]. Barato y sin bloquear: un contador en memoria y, a los segundos, un volcado a disco. */
    fun note(url: String) {
        load()
        ledger.note(day(), url, flag.get())
        if (!flushScheduled.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            delay(FLUSH)
            flushScheduled.set(false)
            runCatching {
                val edit = app.kaizen.commons.KaizenStore.prefs(FILE).edit().clear()
                ledger.snapshot().forEach { (k, v) -> edit.putLong(k, v) }
                edit.commit()
            }
        }
    }

    fun today(): String = day()

    /** El informe de un día (hoy si [day] es null). */
    fun report(day: String?): CallLedger.Report { load(); return ledger.report(day ?: today()) }
}

/**
 * La cuenta, sin Android para probarla: por día, cuántas peticiones a cada servidor (separadas en uso y segundo plano) y cuántas veces se pidió la misma
 * dirección (sin parámetros). Acotada: unos 300 servidores y 400 direcciones por día, y 14 días.
 */
class CallLedger(private val maxHosts: Int = 300, private val maxUrls: Int = 400, private val keepDays: Int = 14, private val pathHosts: (String) -> Boolean = { true }) {
    private val counts = ConcurrentHashMap<String, Long>()
    private val seenHosts = ConcurrentHashMap.newKeySet<String>()
    private val seenUrls = ConcurrentHashMap.newKeySet<String>()
    private val hostsToday = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val urlsToday = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private companion object {
        val HOST = Regex("""^https?://(?:[^/@?#]*@)?([^/:?#]+)""", RegexOption.IGNORE_CASE)
        val SCHEME_USERINFO = Regex("""^https?://(?:[^/@?#]*@)?""", RegexOption.IGNORE_CASE)
    }

    private fun index() {
        seenHosts.clear(); seenUrls.clear(); hostsToday.clear(); urlsToday.clear()
        counts.keys.forEach { k ->
            val p = k.split('|')
            if (p.size >= 3 && p[0] == "h" && seenHosts.add("${p[1]}|${p[2]}")) hostsToday.getOrPut(p[1]) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            if (p.size >= 3 && p[0] == "u" && seenUrls.add(k)) urlsToday.getOrPut(p[1]) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        }
    }

    class HostRow(val host: String, val foreground: Long, val background: Long) { val total get() = foreground + background }
    class UrlRow(val url: String, val count: Long)
    class Report(val day: String, val hosts: List<HostRow>, val urls: List<UrlRow>)

    /** El servidor de [url] sin «www.», o null si no es una dirección web. */
    internal fun hostOf(url: String): String? =
        HOST.find(url)?.groupValues?.get(1)?.lowercase()?.removePrefix("www.")?.takeUnless { it == "localhost" || it == "127.0.0.1" }

    /** La dirección sin parámetros ni ancla, cortada: lo que identifica «la misma página». */
    internal fun urlKey(url: String): String = url.substringBefore('#').substringBefore('?').replace(SCHEME_USERINFO, "").removePrefix("www.").take(120)

    fun note(day: String, url: String, background: Boolean) {
        val host = hostOf(url) ?: return
        val hostKey = "h|$day|$host|${if (background) "b" else "u"}"
        val hostsN = hostsToday.getOrPut(day) { java.util.concurrent.atomic.AtomicInteger() }
        if (seenHosts.contains("$day|$host") || hostsN.get() < maxHosts) {
            if (seenHosts.add("$day|$host")) hostsN.incrementAndGet()
            counts.merge(hostKey, 1L, Long::plus)
        }
        else counts.merge("h|$day|(otros)|${if (background) "b" else "u"}", 1L, Long::plus)
        if (!pathHosts(host)) return
        val urlKey = "u|$day|${urlKey(url)}"
        val urlsN = urlsToday.getOrPut(day) { java.util.concurrent.atomic.AtomicInteger() }
        if (seenUrls.contains(urlKey) || urlsN.get() < maxUrls) {
            if (seenUrls.add(urlKey)) urlsN.incrementAndGet()
            counts.merge(urlKey, 1L, Long::plus)
        }
        if (day != lastPurgeDay) { lastPurgeDay = day; purge(day) }
    }

    @Volatile private var lastPurgeDay = ""

    private fun purge(today: String) {
        val days = counts.keys.mapNotNull { it.split('|').getOrNull(1) }.distinct().sorted()
        if (days.size > keepDays) { days.dropLast(keepDays).forEach { old -> counts.keys.removeAll { it.split('|').getOrNull(1) == old } }; index() }
    }

    fun report(day: String): Report {
        val hosts = counts.keys.filter { it.startsWith("h|$day|") }.mapNotNull { it.split('|').getOrNull(2) }.distinct().map { host ->
            HostRow(host, counts["h|$day|$host|u"] ?: 0L, counts["h|$day|$host|b"] ?: 0L)
        }.sortedByDescending { it.total }
        val urls = counts.entries.filter { it.key.startsWith("u|$day|") }.map { UrlRow(it.key.removePrefix("u|$day|"), it.value) }.sortedByDescending { it.count }
        return Report(day, hosts, urls)
    }

    /** Suma lo guardado; las direcciones completas de servidores que ya no se permiten (un CDN anotado antes de limitarlas) se descartan al cargar. */
    fun load(saved: Map<String, Long>) {
        saved.forEach { (k, v) ->
            if (k.startsWith("u|") && !pathHosts(k.split('|', limit = 3).getOrElse(2) { "" }.substringBefore('/').substringBefore(':'))) return@forEach
            counts.merge(k, v, Long::plus)
        }
        index()
    }
    fun snapshot(): Map<String, Long> = counts.toMap()
}
