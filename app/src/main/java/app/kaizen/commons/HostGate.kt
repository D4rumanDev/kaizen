package app.kaizen.commons

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jsoup.HttpStatusException
import app.kaizen.tv.ui.KaizenToast
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration

/**
 * Peticiones a las webs de las fuentes: una a la vez por servidor (las demás esperan su turno, en orden) y
 * cortocircuito cuando un servidor no responde: tras [FAILURES_TO_DOWN] fallos de red seguidos (en [FAILURE_WINDOW]) se deja de
 * consultar y se avisa con un mensaje. Para no parecer un ataque ni arriesgar que bloqueen la IP, cada pausa
 * seguida es más larga ([PAUSES]: 2 min, 5 min, 10 min y luego 1 h) y, tras una pausa, basta un fallo para
 * volver a pausar. Todo vuelve a empezar cuando el servidor responde o el usuario lo comprueba y responde.
 * Las llamadas son bloqueantes: van en hilos de IO.
 */
object HostGate {
    private const val FAILURES_TO_DOWN = 2
    /** Los fallos solo cuentan seguidos y dentro de esta ventana: uno suelto de hace rato no suma. */
    private val FAILURE_WINDOW = 3.minutes

    /** Cuánto tiempo se muestra un fallo en «Fuentes» si el servidor no vuelve a responder ni a fallar. */
    private val FAILURE_SHOWN = 1.hours
    private const val SERVER_PREFIX = "servidor:"

    /**
     * Las pausas seguidas caducan: si pasó tanto tiempo desde que acabó la última pausa sin que el servidor volviera a fallar,
     * se empieza de nuevo por 2 min. Sin esto, los strikes solo se borraban con un éxito y un servidor que nunca llegaba a
     * comprobarse bien (o cuyas pruebas fallaban siempre por otra causa) se quedaba para siempre en pausas de 1 hora.
     */
    internal val STRIKE_DECAY = 6.hours
    /** Duración de la 1.ª, 2.ª, 3.ª y siguientes pausas seguidas. */
    internal val PAUSES = listOf(2.minutes, 5.minutes, 10.minutes, 60.minutes)
    /** Espera máxima por el turno: si el servidor sigue ocupado, la petición se abandona sin contar como fallo. */
    private val MAX_WAIT = 60.seconds
    private val CHECK_TIMEOUT = 10.seconds

    /** Timeout de las peticiones que van con el turno tomado: acota cuánto puede esperar la cola de un mismo servidor. */
    val REQUEST_TIMEOUT = 12.seconds

    private class Host {
        val lock = ReentrantLock(true)
        @Volatile var failures = 0
        @Volatile var lastFailure = 0L
        @Volatile var downUntil = 0L
        /** Pausas seguidas sin que el servidor haya vuelto a responder. */
        @Volatile var strikes = 0
        /** Último fallo sin que después haya respondido (aunque no llegara a pausarlo) y cuándo ocurrió. */
        @Volatile var lastError: String? = null
        @Volatile var lastErrorAt = 0L
    }

    private val hosts = ConcurrentHashMap<String, Host>()

    /** Pausas guardadas: sobreviven a cerrar la app, para no volver a insistir al abrirla. */
    interface Storage {
        fun load(): Map<String, Pair<Long, Int>>
        fun save(host: String, downUntil: Long, strikes: Int)
    }

    private object PrefsStorage : Storage {
        private val prefs get() = app.kaizen.commons.KaizenStore.prefs("kaizen_hostgate")

        override fun load(): Map<String, Pair<Long, Int>> = runCatching {
            prefs.all.mapNotNull { (host, value) ->
                val parts = (value as? String)?.split(";") ?: return@mapNotNull null
                host to (parts[0].toLong() to parts[1].toInt())
            }.toMap()
        }.getOrDefault(emptyMap())

        override fun save(host: String, downUntil: Long, strikes: Int) {
            runCatching {
                prefs.edit().apply {
                    if (strikes == 0) remove(host) else putString(host, "$downUntil;$strikes")
                }.apply()
            }
        }
    }

    internal var storage: Storage? = PrefsStorage

    init {
        val now = System.currentTimeMillis()
        storage?.load()?.forEach { (name, saved) ->
            // Una pausa guardada que acabó hace mucho no se arrastra a esta sesión.
            if (saved.second > 0 && saved.first > 0 && now - saved.first > STRIKE_DECAY.inWholeMilliseconds) storage?.save(name, 0L, 0)
            else hosts[name] = Host().apply { downUntil = saved.first; strikes = saved.second }
        }
    }

    /** Servidores en pausa ahora mismo (host sin «www.»): la interfaz los trata como fuentes no seleccionadas. */
    private val _down = MutableStateFlow<Set<String>>(emptySet())
    val down: StateFlow<Set<String>> get() = _down

    init {
        // Pausas guardadas de la sesión anterior: se publican y se programa su final.
        publish()
        val now = System.currentTimeMillis()
        hosts.values.map { it.downUntil }.filter { it > now }.forEach { until ->
            AppScope.launch {
                delay(until - now + 50)
                publish()
            }
        }
    }

    /** Aviso visible cuando un servidor pasa a cortocircuito. */
    internal var onDown: (String) -> Unit = { KaizenToast.toastLong(it) }

    /** El servidor está en cortocircuito: no se le ha pedido nada. */
    open class HostDownException(host: String, message: String = "$host no responde") : IOException(message)

    /**
     * Sin conexión ([NetworkState]): no se le ha pedido nada a [host]. Es un [HostDownException] para que todo lo que ya trata una web
     * en pausa (usar lo guardado, saltar a otra fuente) trate igual la falta de red, pero no cuenta como fallo ni abre una pausa.
     */
    class OfflineException(host: String) : HostDownException(host, "Sin conexión")

    /** Demasiadas peticiones esperando al mismo servidor. */
    class HostBusyException(host: String) : IOException("$host ocupado")

    /**
     * 403 que no es la verificación de la web (esa la resuelve el usuario): la web bloquea las peticiones. Sigue siendo un
     * [HttpStatusException] 403 para quien lo captura, pero aquí cuenta como fallo del servidor.
     */
    class ForbiddenException(url: String) : HttpStatusException("HTTP error fetching URL", 403, url)

    /** Comprobación de red para no culpar al servidor de un corte propio. */
    internal var isNetworkUp: () -> Boolean = { Network.isConnected && !NetworkState.isOffline }

    /** Reloj de la ventana de fallos (las pruebas lo adelantan). */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** Resultado de [check]: si responde, código HTTP y tiempo, o el error. */
    data class CheckResult(val ok: Boolean, val code: Int?, val millis: Long, val error: String?)

    /** Sube cada vez que se anota o se borra un fallo: quien lo muestra se recompone. */
    private val _failureVersion = MutableStateFlow(0)
    val failureVersion: StateFlow<Int> get() = _failureVersion

    /** El último fallo de [host] y cuándo fue, si no ha vuelto a responder y es de hace menos de una hora. */
    fun recentFailure(host: String): Pair<Long, String>? {
        val state = hosts[host.removePrefix("www.")] ?: return null
        val error = state.lastError ?: return null
        return (state.lastErrorAt to error).takeIf { clock() - it.first < FAILURE_SHOWN.inWholeMilliseconds }
    }

    /** Sin conexión las pausas no cuentan (se ignoran, no se borran): la causa es la red, no la web, y «Fuentes» lo dice con su propio estado. */
    fun isDown(host: String): Boolean = !NetworkState.isOffline && (hosts[host.removePrefix("www.")]?.downUntil ?: 0L) > System.currentTimeMillis()

    /** Hora (ms) hasta la que [host] está en pausa, o 0. */
    fun downUntil(host: String): Long =
        if (NetworkState.isOffline) 0L else hosts[host.removePrefix("www.")]?.downUntil?.takeIf { it > System.currentTimeMillis() } ?: 0L

    fun <T> call(url: String, block: () -> T): T {
        val name = hostOf(url)
        if (NetworkState.isOffline) throw OfflineException(name)
        val host = hosts.getOrPut(name) { Host() }
        if (System.currentTimeMillis() < host.downUntil) throw HostDownException(name)
        if (!host.lock.tryLock(MAX_WAIT)) throw HostBusyException(name)
        try {
            // Mientras esperaba turno, el servidor pudo quedar en pausa.
            if (System.currentTimeMillis() < host.downUntil) throw HostDownException(name)
            return block().also { markUp(name, host, publish = host.strikes > 0); noteAttempt(name, ok = true) }
        } catch (e: HostDownException) {
            throw e
        } catch (e: IOException) {
            noteFailure(name, host, e)
            throw e
        } finally {
            host.lock.unlock()
        }
    }

    /**
     * Para quien pide sin turno (portadas, proxy, reproductor, o quien ya tiene su propia cola, como AniList y Animeoteca): lanza
     * [HostDownException] si el servidor de [url] está en pausa. Después se anota el resultado con [report] o [reportFailure]
     * (los adaptadores de HostGateHttp.kt lo hacen para OkHttp y Media3).
     */
    fun allow(url: String) {
        val name = hostOf(url)
        if (NetworkState.isOffline) throw OfflineException(name)
        if (System.currentTimeMillis() < (hosts[name]?.downUntil ?: 0L)) throw HostDownException(name)
    }

    /** Anota la respuesta HTTP [code] de [url]: un bloqueo o una caída cuentan como fallo; cualquier otra respuesta, como que el servidor vive. */
    fun report(url: String, code: Int, cfMitigated: String? = null) {
        val name = hostOf(url)
        val host = hosts.getOrPut(name) { Host() }
        val failure = failureFor(url, code, cfMitigated)
        if (failure != null) noteFailure(name, host, failure) else { markUp(name, host, publish = host.strikes > 0); noteAttempt(name, ok = true) }
    }

    /** Anota un fallo de red de [url] (sin respuesta). */
    fun reportFailure(url: String, e: IOException) {
        val name = hostOf(url)
        noteFailure(name, hosts.getOrPut(name) { Host() }, e)
    }

    /**
     * Clave del corte de un servidor de vídeo como familia («Uqload», «MixDrop»…), no de una web: un mismo servidor responde por
     * varios dominios y CDN (uqload.vc y uqload.is, subdominios que cambian en cada petición), así que el corte por host nunca
     * se acumula. Usa el mismo protocolo (2, 5, 10 min y 1 h) y se pregunta con [isDown] y [downUntil] con esta clave.
     */
    fun serverKey(serverName: String): String = "$SERVER_PREFIX${serverName.trim().lowercase()}"

    /** Anota que el servidor de vídeo [serverName] falló por red (DNS, conexión, tiempo). Una pausa de otro host no cuenta. */
    fun reportServerFailure(serverName: String, e: IOException) {
        if (e is HostDownException) return
        val key = serverKey(serverName)
        noteFailure(key, hosts.getOrPut(key) { Host() }, e, label = serverName)
    }

    /** El servidor de vídeo [serverName] entregó un vídeo: fuera de la pausa y sin pausas acumuladas. */
    fun reportServerUp(serverName: String) {
        val key = serverKey(serverName)
        hosts[key]?.let { markUp(key, it, publish = it.strikes > 0 || it.downUntil > 0) }
    }

    /**
     * El fallo que representa el código [code] de [url] (un 403 de bloqueo o un 5xx salvo el 503), o null si el servidor responde.
     * [cfMitigated] es el valor de la cabecera `cf-mitigated` de la respuesta: Cloudflare la pone a `challenge` en toda página de
     * verificación (https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/detect-response/).
     * Esa verificación la hace el usuario, no es un bloqueo: no cuenta.
     */
    fun failureFor(url: String, code: Int, cfMitigated: String? = null): IOException? = when {
        code == 403 && cfMitigated.equals("challenge", ignoreCase = true) -> null
        code == 403 -> ForbiddenException(url)
        code >= 500 && code != 503 -> HttpStatusException("HTTP error fetching URL", code, url)
        else -> null
    }

    /** Para las estadísticas: a qué fuente pertenece [host] (sin «www.»); null si no es de ninguna (portadas, CDN, AniList…). Las pruebas lo cambian. */
    @Volatile internal var sourceOf: (String) -> String? = { host ->
        runCatching { app.kaizen.source.AnimeSources.all.firstOrNull { host == it.host || host.endsWith(".${it.host}") }?.name }.getOrNull()
    }

    /** Anota un intento de conexión con una fuente en las estadísticas del día. Los servidores de vídeo ([serverKey]) los anota quien los extrae. */
    private fun noteAttempt(name: String, ok: Boolean, failure: IOException? = null) {
        if (name.startsWith(SERVER_PREFIX)) return
        sourceOf(name)?.let { app.kaizen.domain.ConnectionStats.record(app.kaizen.domain.ConnectionStats.Kind.SOURCE, it, ok, AppErrors.connectionCause(failure)) }
    }

    private fun noteFailure(name: String, host: Host, e: IOException, label: String = name) {
        // Primero a la detección de red: si con este fallo se da Internet por perdido, el `isNetworkUp()` de abajo ya dice que no
        // y este fallo no abre una pausa (y las que abrieron los anteriores se borran).
        if (!name.startsWith(SERVER_PREFIX)) NetworkState.noteFailure(name, e)
        if (isServerDown(e) && isNetworkUp()) {
            noteAttempt(name, ok = false, failure = e)
            // Al gestor único de errores con su número (E02, E05…): antes solo iba a las estadísticas y a un aviso, y el registro no lo mostraba.
            runCatching { AppErrors.report(e, AppErrors.Area.SOURCE, label) }
            // Los caminos sin turno (portadas, proxy, reproductor) llegan aquí a la vez: el recuento se hace bajo el cerrojo del servidor.
            val pause = synchronized(host) {
                val now = clock()
                decayStrikes(name, host, now)
                // Tras una pausa no se insiste: un solo fallo vuelve a pausar (más tiempo).
                val threshold = if (host.strikes > 0) 1 else FAILURES_TO_DOWN
                // Aunque no llegue a pausarlo, el fallo se anota para mostrarlo en «Fuentes» hasta que vuelva a responder.
                host.lastError = e.message ?: e.javaClass.simpleName
                host.lastErrorAt = now
                _failureVersion.value++
                if (now - host.lastFailure > FAILURE_WINDOW.inWholeMilliseconds) host.failures = 0
                host.lastFailure = now
                host.failures += weightOf(e)
                if (host.failures >= threshold) markDown(name, host) else null
            }
            if (pause != null) onDown("$label no responde: no se usará durante ${describe(pause)}")
        }
    }

    /**
     * Comprueba ahora [url] aunque su servidor esté en pausa (respetando el turno). Si responde, sale de la pausa;
     * si no, entra en ella. Cualquier respuesta HTTP por debajo de 500 (incluido el 403/503 de Cloudflare) cuenta
     * como que el servidor está vivo.
     */
    fun check(url: String): CheckResult {
        val name = hostOf(url)
        // Sin conexión no se pregunta a la web: no habría respuesta que valiera y la pausa no debe tocarse.
        if (NetworkState.isOffline) return CheckResult(false, null, 0, "sin conexión")
        val host = hosts.getOrPut(name) { Host() }
        val start = System.currentTimeMillis()
        if (!host.lock.tryLock(MAX_WAIT)) return CheckResult(false, null, 0, "ocupado")
        try {
            val response = app.kaizen.source.SourceHttp.execute(url, timeout = Tuning.patient(CHECK_TIMEOUT)) { ignoreHttpErrors(); maxBodySize(1) }
            val millis = System.currentTimeMillis() - start
            val alive = response.statusCode < 500 || response.statusCode == 503
            if (alive) markUp(name, host) else markDown(name, host)
            return CheckResult(alive, response.statusCode, millis, null)
        } catch (e: IOException) {
            if (isNetworkUp()) markDown(name, host)
            return CheckResult(false, null, System.currentTimeMillis() - start, e.javaClass.simpleName)
        } finally {
            host.lock.unlock()
        }
    }

    /**
     * Mide si [url] responde sin tocar pausas ni fallos: para mostrar el estado sin que mirarlo cambie el comportamiento
     * (a diferencia de [check], que es una acción explícita y sí entra o sale de la pausa).
     */
    fun probe(url: String): CheckResult {
        if (NetworkState.isOffline) return CheckResult(false, null, 0, "sin conexión")
        val start = System.currentTimeMillis()
        return try {
            val response = app.kaizen.source.SourceHttp.execute(url, timeout = Tuning.patient(CHECK_TIMEOUT)) { ignoreHttpErrors(); maxBodySize(1) }
            CheckResult(response.statusCode < 500 || response.statusCode == 503, response.statusCode, System.currentTimeMillis() - start, null)
        } catch (e: IOException) {
            CheckResult(false, null, System.currentTimeMillis() - start, e.javaClass.simpleName)
        }
    }

    /** Saca [host] de la pausa a mano. */
    fun resume(host: String) {
        val name = host.removePrefix("www.")
        hosts[name]?.let { markUp(name, it) }
    }

    /** Para tests: termina la pausa de [host] como si hubiera pasado el tiempo (sin olvidar las pausas seguidas). */
    internal fun endPause(host: String) {
        hosts[host]?.downUntil = 0L
        publish()
    }

    /** Vuelve a permitir todos los servidores. */
    fun reset() {
        hosts.forEach { (name, host) ->
            synchronized(host) {
                if (host.strikes > 0) storage?.save(name, 0L, 0)
                host.downUntil = 0L; host.failures = 0; host.lastFailure = 0L; host.strikes = 0; host.lastError = null
            }
        }
        _failureVersion.value++
        _down.value = emptySet()
    }

    /** Olvida las pausas seguidas de [host] si la última acabó hace más de [STRIKE_DECAY] (llamar con el cerrojo de [host]). */
    private fun decayStrikes(name: String, host: Host, now: Long) {
        if (host.strikes > 0 && host.downUntil > 0 && now - host.downUntil > STRIKE_DECAY.inWholeMilliseconds) {
            host.strikes = 0
            host.downUntil = 0L
            storage?.save(name, 0L, 0)
        }
    }

    /** Pone [host] en pausa (cada vez más larga) y devuelve cuánto dura. */
    private fun markDown(name: String, host: Host): Duration {
        val pause = synchronized(host) {
            val pause = PAUSES[host.strikes.coerceAtMost(PAUSES.lastIndex)]
            host.strikes++
            host.failures = 0
            host.downUntil = System.currentTimeMillis() + pause.inWholeMilliseconds
            storage?.save(name, host.downUntil, host.strikes)
            pause
        }
        publish()
        // Una vez por pausa, en el catálogo único: el fallo que la causó ya se anotó con su propia causa (E02, E05…); esta dice que la web quedó en pausa.
        runCatching { AppErrors.record(app.kaizen.domain.ConnectionStats.Cause.PAUSED, AppErrors.Area.SOURCE, "${describe(pause)} de pausa", context = name) }
        // Al acabar la pausa la fuente vuelve sola: se avisa a quien escucha [down].
        AppScope.launch {
            delay(pause + 50.milliseconds)
            publish()
        }
        return pause
    }

    /** El servidor responde: fuera de la pausa y sin pausas acumuladas. */
    private fun markUp(name: String, host: Host, publish: Boolean = true) {
        if (!name.startsWith(SERVER_PREFIX)) NetworkState.noteSuccess(name)
        synchronized(host) {
            if (host.strikes > 0 || host.downUntil > 0) storage?.save(name, 0L, 0)
            if (host.lastError != null) { host.lastError = null; _failureVersion.value++ }
            host.failures = 0
            host.strikes = 0
            host.downUntil = 0L
        }
        if (publish) publish()
    }

    /** «2 minutos», «1 hora»: lo que se dice al usuario de una pausa (también la del bypass de Cloudflare). */
    internal fun describe(duration: Duration): String = if (duration >= 1.hours) "1 hora" else "${duration.inWholeMinutes} minutos"

    private fun publish() {
        val now = System.currentTimeMillis()
        _down.value = hosts.filterValues { it.downUntil > now }.keys.toSet()
    }

    /**
     * Fallo del servidor o de la red; no cuentan 4xx (404 de una ficha que no existe, 429 por ritmo...) ni el 503 de
     * Cloudflare. Sí cuenta el 403 de bloqueo ([ForbiddenException]), no el de verificación.
     */
    private fun isServerDown(e: IOException): Boolean = when (e) {
        // Un enlace vacío o mal formado es un fallo de quien lo dio, no del servidor.
        is java.net.MalformedURLException -> false
        // Un destino que la app misma se niega a tocar (red privada, ver PrivateAddress) no es un servidor caído.
        is PrivateDestinationException -> false
        is ForbiddenException -> true
        is HttpStatusException -> e.statusCode >= 500 && e.statusCode != 503
        else -> true
    }

    /** Un servidor que ni acepta la conexión está caído (cuenta doble: basta una petición); uno lento puede ser pasajero. */
    private fun weightOf(e: IOException): Int = when {
        e is java.net.ConnectException || e is java.net.NoRouteToHostException -> FAILURES_TO_DOWN
        e is java.net.SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true -> FAILURES_TO_DOWN
        else -> 1
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: url
}
