package app.kaizen.commons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/**
 * ¿Está el operador bloqueando ahora las IPs de una web por un partido de LaLiga? Los operadores bloquean por **IP** (no por DNS ni por nombre
 * de servidor) las direcciones de Cloudflare compartidas con webs pirata, así que cualquier fuente detrás de Cloudflare deja de responder mientras
 * dura el partido. `hayahora.futbol` publica, en `estado/blocked-any.txt`, una IP por línea con las que algún operador bloquea ahora mismo (el
 * fichero es de unos pocos bytes). Solo informa: la app no cambia nada por ello, pero la pantalla «Fuentes» dice «Bloqueo de LaLiga» en vez de «No responde».
 * Si el servicio no contesta o cambia de forma, no se avisa de nada.
 */
object LaLigaBlock {
    private const val URL = "https://hayahora.futbol/estado/blocked-any.txt"
    private val CACHE = 5.minutes
    private const val MAX_BYTES = 512 * 1024L

    @Volatile private var cached: Pair<Long, Set<String>>? = null

    /** Las IPs de un texto «una por línea»; lo que no parece una IP (cabeceras, HTML de error) se ignora. */
    internal fun parse(text: String): Set<String> =
        text.lineSequence().map { it.trim() }.filter { IP.matches(it) }.toSet()

    /** Las [hostIps] de una web que están en [blocked]. */
    internal fun blockedAmong(hostIps: Collection<String>, blocked: Set<String>): Set<String> = hostIps.filter { it in blocked }.toSet()

    /** El DNS de la red y, solo si no resuelve el nombre, el de relevo de la app (Quad9 de fábrica; ver [FallbackDns.Provider]). */
    private object RelayDns : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            runCatching { okhttp3.Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList()).ifEmpty { FallbackDns.relayLookup(hostname) }
                .ifEmpty { throw java.net.UnknownHostException(hostname) }
    }

    private val IP = Regex("""^(\d{1,3}(\.\d{1,3}){3}|[0-9a-fA-F:]{2,39})$""")

    /** Las IPs bloqueadas ahora, o null si no se pudo saber. Se guarda 5 minutos. */
    private suspend fun blockedIps(): Set<String>? = withContext(Dispatchers.IO) {
        // Con el aviso apagado no se consulta nada, salvo que el túnel esté en «solo con bloqueo»: ese modo necesita saberlo.
        if (!KaizenSettings.laLigaWarning && app.kaizen.commons.warp.WarpMode.of(KaizenSettings.warpMode) != app.kaizen.commons.warp.WarpMode.ON_BLOCK) return@withContext null
        cached?.takeIf { System.currentTimeMillis() - it.first < CACHE.inWholeMilliseconds }?.let { return@withContext it.second }
        runCatching {
            // Validación TLS estricta del sistema aunque el usuario haya puesto «permisiva» para las fuentes: es un servicio de un tercero.
            val client = okhttp3.OkHttpClient.Builder().dns(RelayDns).callTimeout(8, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(URL).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.source()?.let { source -> source.request(MAX_BYTES); source.buffer.readUtf8() }?.let(::parse)
            }
        }.getOrNull()?.also { cached = System.currentTimeMillis() to it }
    }

    /** De [hosts], los que hoy resuelven a alguna IP bloqueada. Vacío si no hay bloqueo o no se pudo saber. */
    suspend fun blockedHosts(hosts: Collection<String>): Set<String> {
        val blocked = blockedIps()?.takeIf { it.isNotEmpty() } ?: return emptySet()
        return withContext(Dispatchers.IO) {
            hosts.filter { host ->
                val ips = runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }.getOrDefault(emptyList())
                blockedAmong(ips, blocked).isNotEmpty()
            }.toSet()
        }
    }
}
