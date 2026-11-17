package app.kaizen.bypass

import app.kaizen.commons.HostGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.kaizen.tv.ui.KaizenToast

/**
 * Estado del último bypass de Cloudflare: lo muestra «Fuentes» y aplica el mismo protocolo que [HostGate]. Tras
 * [FAILURES_TO_PAUSE] fallos seguidos el arranque deja de lanzar el bypass durante una pausa cada vez más larga
 * ([HostGate.PAUSES_MS]: 2 min, 5 min, 10 min y luego 1 h); tras una pausa basta un fallo para volver a pausar.
 * Todo vuelve a empezar cuando el bypass funciona. El botón de Configuración lo lanza siempre.
 */
object BypassStatus {
    enum class State { UNKNOWN, RUNNING, OK, NOT_NEEDED, FAILED, UNREACHABLE }

    /** [at]: cuándo terminó (o empezó, si está en marcha); [millis]: lo que tardó; [pausedUntil]: hora (ms) hasta la que no se relanza solo, o 0. */
    data class Snapshot(val state: State = State.UNKNOWN, val at: Long = 0L, val millis: Long = 0L, val pausedUntil: Long = 0L)

    private const val FAILURES_TO_PAUSE = 2

    /** Pausa guardada: sobrevive a cerrar la app, para no volver a insistir al abrirla (como las de [HostGate]). */
    interface Storage {
        fun load(): Pair<Long, Int>
        fun save(pausedUntil: Long, strikes: Int)
    }

    private object PrefsStorage : Storage {
        private val prefs get() = app.kaizen.commons.KaizenStore.prefs("kaizen_bypass")

        override fun load(): Pair<Long, Int> = runCatching { prefs.getLong("until", 0L) to prefs.getInt("strikes", 0) }.getOrDefault(0L to 0)

        override fun save(pausedUntil: Long, strikes: Int) {
            runCatching { prefs.edit().putLong("until", pausedUntil).putInt("strikes", strikes).apply() }
        }
    }

    internal var storage: Storage? = PrefsStorage

    /** Aviso visible cuando el bypass entra en pausa. */
    internal var onPause: (String) -> Unit = { KaizenToast.toastLong(it) }

    /** Reloj (las pruebas lo adelantan). */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    @Volatile private var startedAt = 0L
    @Volatile private var failures = 0
    @Volatile private var strikes = 0

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> get() = _snapshot

    init {
        storage?.load()?.let { (until, saved) ->
            strikes = saved
            if (until > clock()) _snapshot.value = Snapshot(pausedUntil = until)
        }
    }

    @Synchronized fun start() {
        startedAt = clock()
        _snapshot.value = _snapshot.value.copy(state = State.RUNNING, at = startedAt)
    }

    /** Termina el bypass lanzado: [state] es su resultado y se mide lo que tardó desde [start]. */
    @Synchronized fun finish(state: State) = record(state, if (startedAt > 0) clock() - startedAt else 0L)

    private fun record(state: State, millis: Long) {
        val now = clock()
        if (state == State.FAILED || state == State.UNREACHABLE) {
            failures++
            val threshold = if (strikes > 0) 1 else FAILURES_TO_PAUSE
            if (failures >= threshold) {
                val pause = HostGate.PAUSES_MS[strikes.coerceAtMost(HostGate.PAUSES_MS.lastIndex)]
                strikes++
                failures = 0
                storage?.save(now + pause, strikes)
                _snapshot.value = Snapshot(state, now, millis, now + pause)
                onPause("Cloudflare no responde: no se reintentará solo durante ${if (pause >= 60 * 60_000L) "1 hora" else "${pause / 60_000L} minutos"}")
                return
            }
        } else {
            failures = 0
            if (strikes > 0) storage?.save(0L, 0)
            strikes = 0
        }
        _snapshot.value = Snapshot(state, now, millis, _snapshot.value.pausedUntil.takeIf { state != State.OK && state != State.NOT_NEEDED && it > now } ?: 0L)
    }

    private fun running() = _snapshot.value.state == State.RUNNING

    /** Cloudflare o la fuente no contestan al comprobar si hace falta el bypass (sin llegar a lanzarlo). */
    @Synchronized fun unreachable() {
        // En pausa no se contacta con Cloudflare: la comprobación previa no escala la pausa (como HostGate con un host en pausa).
        if (coolingDown() || running()) return
        record(State.UNREACHABLE, 0L)
    }

    /** La comprobación previa obtuvo respuesta normal (200): Cloudflare responde y no pide verificación, así que se reinicia el protocolo. */
    @Synchronized fun notNeeded() {
        if (running()) return
        record(State.NOT_NEEDED, 0L)
    }

    /** El usuario cerró el bypass a mano: no es un fallo ni un éxito, solo deja de estar en marcha (conserva la pausa vigente). */
    @Synchronized fun cancel() {
        if (!running()) return
        _snapshot.value = Snapshot(pausedUntil = _snapshot.value.pausedUntil)
    }

    /** En pausa: el arranque no relanza el bypass (el botón de Configuración sí). */
    fun coolingDown(): Boolean = _snapshot.value.pausedUntil > clock()

    @Synchronized internal fun reset() {
        startedAt = 0L
        failures = 0
        strikes = 0
        _snapshot.value = Snapshot()
    }
}
