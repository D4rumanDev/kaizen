package app.kaizen.commons

import android.content.ComponentCallbacks2
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.util.Log
import androidx.media3.common.PlaybackException
import app.kaizen.domain.ConnectionStats
import app.kaizen.domain.ConnectionStats.Cause
import app.kaizen.domain.Incident
import app.kaizen.domain.IncidentSummary
import app.kaizen.domain.summarize
import coil3.annotation.ExperimentalCoilApi
import java.io.IOException

/**
 * **El gestor único de errores de la app.** Todo fallo entra por aquí ([report] con la excepción o [record] con la causa ya conocida) y sale igual:
 * se clasifica con el catálogo único ([Cause], E01…), se apunta en la lista de la sesión, se escribe en el registro de depuración con su número,
 * su origen y qué hacer, y alimenta el resumen del visor. `logError` (el que ya usa toda la app) llama aquí, así que los sitios que capturan no
 * necesitan su propio criterio; solo dicen **de qué parte de la app** vienen ([Area]) cuando lo saben.
 *
 * La clasificación es una sola cadena de reglas ([classify]), no un `catch` por tipo: primero lo que es de la TV (memoria, disco, base de datos),
 * luego la red (con la red caída, un fallo de conexión no se atribuye a ningún servidor) y por último lo propio de cada área.
 */
object AppErrors {
    /** De qué parte de la app viene un fallo: da contexto y desempata cuando la excepción no dice nada. */
    enum class Area(val label: String) {
        APP("Error de la app"), IMAGE("Carátulas"), CACHE("Caché"), DATABASE("Base de datos"),
        STORAGE("Almacenamiento"), SOURCE("Fuentes y servidores"), PLAYER("Reproductor")
    }

    /** Un fallo anotado: [incident] (causa, detalle, hora), de qué [area] y [context] (la web o el recurso afectado). */
    data class Report(val area: Area, val context: String, val incident: Incident)

    /** ¿Hay red? Las pruebas lo cambian. Sin contexto de Android (pruebas) se da por buena: no se culpa a la red sin saberlo. */
    @Volatile internal var online: () -> Boolean = { runCatching { Network.anyInternet() }.getOrDefault(true) }

    /** Reloj en ms (las pruebas lo adelantan). */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    private val lock = Any()
    private val reports = ArrayDeque<Report>()

    /** Tope de fallos guardados de la sesión y ventana en que un mismo fallo repetido no vuelve a escribirse en el registro (sí cuenta). */
    private const val MAX_REPORTS = 300
    private const val QUIET_MS = 30_000L
    private const val DETAIL_MAX = 160

    // ------------------------------------------------------------------------------------------------ Clasificar

    /**
     * La causa de [e] según el catálogo único; null si no es un fallo (una cancelación de corrutina). Con [online] a falso, un fallo de conexión
     * es [Cause.NO_NETWORK], no del servidor.
     */
    @OptIn(ExperimentalCoilApi::class)
    fun classify(e: Throwable, area: Area = Area.APP, online: Boolean = this.online()): Cause? {
        if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) return null
        val chain = generateSequence(e) { it.cause }.take(8).toList()
        // Lo que es de la TV, antes que cualquier otra lectura.
        if (chain.any { it is OutOfMemoryError }) return Cause.LOW_MEMORY
        if (chain.any { it is SQLiteFullException } || chain.any { it is IOException && it.message.orEmpty().let { m -> "ENOSPC" in m || "No space left" in m } }) return Cause.STORAGE
        if (chain.any { it is SQLiteException }) return Cause.DATABASE
        // La red: el código HTTP de Coil, el de ExoPlayer/OkHttp/jsoup y el tipo de excepción, todo por el mismo catálogo.
        val coilStatus = chain.filterIsInstance<coil3.network.HttpException>().firstOrNull()?.response?.code
        val connection = if (coilStatus != null) ConnectionStats.causeOfHttp(coilStatus)
        else ConnectionStats.causeOfPlayback(PlaybackException.ERROR_CODE_UNSPECIFIED, e)
        // Una red que no da salida (el móvil de repetidor sin datos) es de la red, y se distingue de «sin ninguna red».
        if (NetworkState.mode.value == NetworkState.Mode.NO_INTERNET && (connection == Cause.DNS || connection == Cause.CONNECTION || connection == Cause.TIMEOUT)) return Cause.NO_INTERNET
        if (!online && (connection == Cause.DNS || connection == Cause.CONNECTION || connection == Cause.TIMEOUT)) return Cause.NO_NETWORK
        if (connection != Cause.OTHER) return connection
        // Sin causa de red: manda el área, y si no, un error de programación es de la app.
        val programming = chain.any {
            it is NullPointerException || it is IllegalStateException || it is IndexOutOfBoundsException || it is ClassCastException ||
                it is NumberFormatException || it is IllegalArgumentException || it is org.json.JSONException
        }
        return when {
            area == Area.SOURCE && programming -> Cause.EXTRACTOR
            area == Area.IMAGE -> Cause.IMAGE
            // Un NullPointerException en el código de la caché no es que la caché esté corrupta: es un fallo de la app.
            programming -> Cause.INTERNAL
            area == Area.CACHE -> Cause.CACHE
            area == Area.DATABASE -> Cause.DATABASE
            area == Area.STORAGE -> Cause.STORAGE
            else -> Cause.OTHER
        }
    }

    /**
     * La causa de un fallo de **conexión** para las estadísticas de fuentes y servidores: lo mismo que [classify] (con red caída, [Cause.NO_NETWORK],
     * que las estadísticas no cuentan). null si no hay excepción; una cancelación es [Cause.OTHER], como siempre.
     */
    fun connectionCause(e: Throwable?): Cause? = e?.let { classify(it, Area.SOURCE) ?: Cause.OTHER }

    // ------------------------------------------------------------------------------------------------ Anotar

    /**
     * Anota un fallo de excepción: lo clasifica y lo anota. Una cancelación no es un fallo y se ignora. Si quien captura no dice de qué [area] viene
     * ([Area.APP]), se deduce de la pila: la primera clase de la app que aparece dice si es de las fuentes, la base de datos, la caché, el reproductor…
     * y en qué método, que va como [context] si no se dio otro. Así los `catch` que ya existen quedan clasificados sin tocarlos uno a uno.
     */
    fun report(e: Throwable, area: Area = Area.APP, context: String = "", what: String = "") {
        // Una web en pausa o con la cola llena no se consulta: el fallo que la pausó ya se anotó entonces; repetirlo en cada petición saltada sería ruido.
        // (Aquí y no en [classify]: las estadísticas de conexión siguen clasificando estas excepciones como siempre.)
        if (isSkippedRequest(e)) return
        val place = if (area == Area.APP) placeOf(e) else null
        // Las fuentes se clasifican como «extractor» solo si quien captura lo dijo expresamente: el resto de su código es de la app.
        val cause = classify(e, place?.area?.takeIf { it != Area.SOURCE } ?: area) ?: return
        // [what]: qué se pedía (la ruta, sin servidor ni parámetros), para que un 404 diga cuál era la página o la imagen que faltaba.
        val detail = "${e::class.java.simpleName}${e.message?.let { ": $it" }.orEmpty()}${what.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
        record(cause, place?.area ?: area, detail, context.ifBlank { place?.frame.orEmpty() }, e)
    }

    /** Una petición que ni se hizo porque la web está en pausa o con la cola llena, también como causa de otro error. */
    internal fun isSkippedRequest(e: Throwable) =
        generateSequence(e) { it.cause }.take(8).any { it is HostGate.HostDownException || it is HostGate.HostBusyException }

    /** De qué parte de la app salió un fallo y en qué método (`Clase.método`). */
    internal data class Place(val area: Area, val frame: String)

    /** Clases de infraestructura por las que pasa casi todo error: no dicen de dónde viene. */
    private val PLUMBING = listOf("app.kaizen.commons.AppErrors", "app.kaizen.commons.ErrorLog", "app.kaizen.commons.Background", "app.kaizen.commons.AppScope")

    internal fun placeOf(e: Throwable): Place? {
        val frame = generateSequence(e) { it.cause }.take(8).flatMap { it.stackTrace.asSequence() }
            .firstOrNull { f -> f.className.startsWith("app.kaizen.") && PLUMBING.none { f.className.startsWith(it) } } ?: return null
        return Place(areaOfClass(frame.className), "${frame.className.substringAfterLast('.').substringBefore('$')}.${frame.methodName}")
    }

    /** La parte de la app a la que pertenece una clase, por su nombre y su paquete. */
    internal fun areaOfClass(className: String): Area {
        val simple = className.substringAfterLast('.')
        return when {
            simple.contains("Cache") -> Area.CACHE
            listOf("Backup", "Export", "Import", "Storage", "DebugLog").any { simple.contains(it) } -> Area.STORAGE
            className.startsWith("app.kaizen.database.") -> Area.DATABASE
            className.startsWith("app.kaizen.tv.exoplayer.") -> Area.PLAYER
            className.startsWith("app.kaizen.source.") || className.startsWith("app.kaizen.videoservers.") || className.startsWith("app.kaizen.bypass.") ||
                simple.startsWith("HostGate") -> Area.SOURCE
            else -> Area.APP
        }
    }

    /**
     * Anota un fallo cuya [cause] ya se conoce (p. ej. el reproductor, que clasifica con su código de ExoPlayer). Entra en la lista de la sesión
     * siempre; en el registro de depuración se escribe con su número, origen y solución, salvo si es el mismo fallo repetido hace menos de 30 s.
     */
    fun record(cause: Cause, area: Area, detail: String, context: String = "", throwable: Throwable? = null) {
        val now = clock()
        val cut = redact(detail).replace(Regex("\\s+"), " ").take(DETAIL_MAX)
        val repeated = synchronized(lock) {
            val again = reports.any { it.area == area && it.context == context && it.incident.cause == cause && now - it.incident.at < QUIET_MS }
            reports.addLast(Report(area, context, Incident(cause, cut, now)))
            while (reports.size > MAX_REPORTS) reports.removeFirst()
            again
        }
        // Sin la excepción: su rastro lleva el mensaje original con direcciones completas. El rastro, ya redactado, va al registro de depuración.
        runCatching { Log.w("KAIZEN", "${cause.tag} ${cause.label} [${area.label}]: $cut") }
        if (repeated) return
        // La primera línea es la anotación; la segunda (origen y solución) sale bajo ella en el visor, y detrás el rastro técnico.
        val where = context.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        DebugLog.error(area.label, "${cause.tag} ${cause.label}$where: $cut\nOrigen: ${cause.origin.label} · Qué hacer: ${cause.fix}", throwable,
            DebugLog.Fields(code = cause.tag, cause = cause.code, origin = cause.origin.label, context = context))
    }

    private val URL_IN_TEXT = Regex("""(https?)://([^/\s\]\)"'?#]+)[^\s\]\)"']*""")

    /**
     * El texto sin direcciones completas (pueden llevar tokens o búsquedas del usuario): de cada una queda solo `https://servidor`, sin ruta ni parámetros.
     * Lo aplica [DebugLog] a todo lo que escribe (mensaje y rastro técnico), no solo el detalle.
     */
    internal fun redact(text: String): String = URL_IN_TEXT.replace(text) { "${it.groupValues[1]}://${it.groupValues[2].substringAfterLast('@')}" }

    /**
     * ¿Es un aviso de falta de memoria de verdad? Solo con la app en primer plano y el sistema justo (RUNNING_LOW, RUNNING_CRITICAL) o a punto de cerrarla
     * (COMPLETE). UI_HIDDEN, BACKGROUND y MODERATE saltan cada vez que se pulsa Inicio.
     */
    fun isTrimPressure(level: Int): Boolean =
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE

    // ------------------------------------------------------------------------------------------------ Consultar

    /** Los fallos de esta sesión, el más reciente al final. */
    fun recent(): List<Report> = synchronized(lock) { reports.toList() }

    /** Los fallos de la sesión agrupados por causa (cuántos y el último detalle); los totales se derivan de la lista. */
    fun summary(): List<IncidentSummary> = recent().map { it.incident }.summarize()

    /** Una línea para el visor: «Esta sesión: 3× E05 Bloqueo (403) · 1× E18 Carátula no cargada», o que no hay fallos. */
    fun sessionLine(): String {
        val summary = summary()
        if (summary.isEmpty()) return "Esta sesión: sin errores"
        return "Esta sesión: " + summary.sortedByDescending { it.count }.take(4).joinToString(" · ") { "${it.count}× ${it.cause.tag} ${it.cause.label}" } +
            if (summary.size > 4) " · +${summary.size - 4} más" else ""
    }

    /** Vacía la lista de la sesión (las pruebas). */
    internal fun clear() = synchronized(lock) { reports.clear() }
}
