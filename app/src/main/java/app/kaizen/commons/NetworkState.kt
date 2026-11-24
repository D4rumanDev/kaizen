package app.kaizen.commons

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network as AndroidNetwork
import android.net.NetworkCapabilities
import app.kaizen.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/**
 * ¿Hay Internet de verdad? Un estado único para toda la app, que se ve al instante en «Fuentes»:
 *  - [Mode.ONLINE]: con red.
 *  - [Mode.NO_INTERNET]: detectado. No hay ninguna red con salida, o hay una (el móvil como repetidor sin datos) que Android no valida y
 *    además fallan la conexión o el DNS de **varias webs distintas** sin que ninguna responda. Una red sin validar por sí sola no basta:
 *    un DNS que filtre los servidores de Google (AdGuard) la deja sin validar con Internet funcionando.
 *  - [Mode.OFFLINE_MANUAL]: el interruptor «Modo sin conexión» de Configuración.
 * Fuera de [Mode.ONLINE] no se piden cosas a las webs, los fallos no cuentan para las pausas de 2, 5 y 10 min de [HostGate] y las
 * imágenes guardadas se usan aunque hayan caducado. Al salir de [Mode.NO_INTERNET] se limpian las pausas de [HostGate]: las abrieron
 * los fallos de antes de detectarlo, que eran de la red y no de las webs.
 */
object NetworkState {
    enum class Mode { ONLINE, NO_INTERNET, OFFLINE_MANUAL }

    /** Lo que Android sabe de las redes: ninguna con Internet, alguna sin validar o alguna validada. */
    enum class Platform { NONE, UNVALIDATED, VALIDATED }

    /** Webs distintas que deben fallar por red, y en cuánto tiempo, para dar por perdido Internet con la red sin validar. */
    internal const val HOSTS_TO_DECLARE = 2
    internal val EVIDENCE_WINDOW = 2.minutes

    private val _mode = MutableStateFlow(Mode.ONLINE)
    val mode: StateFlow<Mode> get() = _mode

    /** Sin conexión por cualquiera de las dos causas. */
    val isOffline: Boolean get() = _mode.value != Mode.ONLINE

    /** Reloj (las pruebas lo adelantan). */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** El interruptor manual (las pruebas lo cambian). */
    @Volatile internal var manual: () -> Boolean = { runCatching { KaizenSettings.offlineMode }.getOrDefault(false) }

    /** Lo que dice Android; si no se puede consultar se supone que sí hay red (no se declara una caída sin pruebas). */
    @Volatile internal var platform: () -> Platform = ::readPlatform

    /** Qué hacer al salir de [Mode.NO_INTERNET] (las pruebas lo anulan). */
    @Volatile internal var onRecovered: () -> Unit = { HostGate.reset() }

    /** Fallos de red por web (host → cuándo) desde el último acierto. */
    private val failures = ConcurrentHashMap<String, Long>()

    private var registered = false

    /** Cuándo se detectó el corte de Internet que sigue en pie (-1: ninguno), para anotar cuánto duró. */
    @Volatile private var cutSince = -1L

    /** Lo que se anota al detectar un corte: qué vio Android y qué webs fallaron por red. */
    internal fun cutLine(platform: Platform, hosts: Collection<String>): String {
        val android = when (platform) {
            Platform.NONE -> "Android no ve ninguna red con salida a Internet"
            Platform.UNVALIDATED -> "Android no valida la red"
            Platform.VALIDATED -> "Android da la red por buena"
        }
        val webs = if (hosts.isEmpty()) "ninguna web falló" else "fallaron por red: " + hosts.sorted().joinToString(", ")
        return "Internet cortado: $android; $webs"
    }

    /** Lo que se anota al volver: cuánto estuvo cortado, en palabras. */
    internal fun backLine(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        val text = if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
        return "Internet recuperado tras $text"
    }

    /** Se llama una vez al arrancar: sigue los cambios de red al instante, sin esperar a ninguna petición. */
    @Synchronized fun start(context: Context = App.context) {
        recompute()
        if (registered) return
        registered = true
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: AndroidNetwork) = recompute()
                override fun onLost(network: AndroidNetwork) = recompute()
                override fun onCapabilitiesChanged(network: AndroidNetwork, networkCapabilities: NetworkCapabilities) = recompute()
            })
        }.onFailure { registered = false }
        // El interruptor manual vive en las preferencias.
        runCatching {
            KaizenStore.settings.registerOnSharedPreferenceChangeListener(prefsListener)
        }
    }

    // Referencia fuerte: SharedPreferences solo guarda referencias débiles.
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "kaizen_offline_mode") recompute() }

    /** El interruptor manual cambió (también lo llama el ajuste, por si el listener no llega a tiempo). */
    fun setManual(on: Boolean) {
        KaizenSettings.offlineMode = on
        recompute()
    }

    /** «Reintentar conexión»: olvida las pruebas de «sin Internet»; si sigue sin salida, las siguientes peticiones la vuelven a detectar. */
    fun retry() {
        failures.clear()
        recompute()
    }

    /** Una petición a [host] llegó a responder: hay Internet. */
    fun noteSuccess(host: String) {
        failures.clear()
        recompute()
    }

    /** Una petición a [host] falló: si es de red (no de la web), suma a la prueba de que no hay Internet. */
    fun noteFailure(host: String, e: IOException) {
        if (!isConnectivity(e)) return
        failures[host] = clock()
        recompute()
    }

    /** Fallo de conexión, de DNS o de tiempo: lo que da una red sin salida (un 403 o un 500 los da una web viva). */
    internal fun isConnectivity(e: IOException): Boolean =
        e is UnknownHostException || e is java.net.ConnectException || e is NoRouteToHostException || e is SocketTimeoutException

    /** Calcula el modo de ahora; si salió de [Mode.NO_INTERNET] o entró en él, limpia las pausas falsas. */
    @Synchronized fun recompute() {
        val now = clock()
        failures.entries.removeAll { now - it.value > EVIDENCE_WINDOW.inWholeMilliseconds }
        val seen = runCatching { platform() }.getOrDefault(Platform.VALIDATED)
        val next = decide(manual(), seen, failures.size)
        val previous = _mode.value
        if (next == previous) return
        _mode.value = next
        // Para saber si los fallos son cortes de la red y cuánto duran: una línea al caer (con la evidencia) y otra al volver (con la duración).
        if (next == Mode.NO_INTERNET) { cutSince = now; runCatching { DebugLog.info("Red", cutLine(seen, failures.keys)) } }
        else if (previous == Mode.NO_INTERNET && cutSince >= 0) { runCatching { DebugLog.info("Red", backLine(now - cutSince)) }; cutSince = -1L }
        // Se anota una vez, al entrar (no en cada petición saltada), en el catálogo único de errores.
        val cause = when (next) {
            Mode.NO_INTERNET -> app.kaizen.domain.ConnectionStats.Cause.NO_INTERNET
            Mode.OFFLINE_MANUAL -> app.kaizen.domain.ConnectionStats.Cause.OFFLINE
            Mode.ONLINE -> null
        }
        if (cause != null) runCatching { AppErrors.record(cause, AppErrors.Area.SOURCE, if (next == Mode.NO_INTERNET) "La red no da salida a Internet" else "Interruptor encendido") }
        // Entrar en NO_INTERNET: las pausas que abrieron los primeros fallos eran de la red. Salir: lo mismo, por si quedó alguna.
        if (previous == Mode.NO_INTERNET || next == Mode.NO_INTERNET) runCatching { onRecovered() }
    }

    /**
     * La decisión, sin efectos: el interruptor manda; sin ninguna red con salida es seguro; con una red sin validar hacen falta
     * fallos de red de [HOSTS_TO_DECLARE] webs distintas (un acierto los borra todos); con la red validada se confía en Android.
     */
    internal fun decide(manual: Boolean, platform: Platform, failedHosts: Int): Mode = when {
        manual -> Mode.OFFLINE_MANUAL
        platform == Platform.NONE -> Mode.NO_INTERNET
        platform == Platform.UNVALIDATED && failedHosts >= HOSTS_TO_DECLARE -> Mode.NO_INTERNET
        else -> Mode.ONLINE
    }

    @Suppress("DEPRECATION")
    private fun readPlatform(): Platform {
        val cm = App.context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val withInternet = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }.filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        return when {
            withInternet.isEmpty() -> Platform.NONE
            withInternet.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } -> Platform.VALIDATED
            else -> Platform.UNVALIDATED
        }
    }

    /** Para pruebas: vuelve al estado de fábrica. */
    internal fun reset() {
        failures.clear()
        _mode.value = Mode.ONLINE
    }
}
