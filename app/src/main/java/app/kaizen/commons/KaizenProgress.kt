package app.kaizen.commons

import kotlin.time.Duration.Companion.days
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import app.kaizen.pojos.av1.Record
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Kaizen: posición y duración de cada capítulo, por su serie y número ([Key]: id de serie del libro y número; el eid es un hash de 31 bits que
 * puede colisionar). Lo guardado por eid o por ficha («p:fuente:slug#número») en versiones anteriores se sigue leyendo y pasa a la clave
 * nueva ([migrateEidKeys], [migrateFichaKeys] o al tocarlo).
 * Alimenta la fila «Seguir viendo» y el aviso de reanudar. Fichero de preferencias propio.
 */
object KaizenProgress {
    /** Desde este punto un capítulo sale en «Seguir viendo» (salir al segundo de empezarlo ya cuenta como empezado). */
    private const val MIN_LISTED_MS = 1_000L

    /** Desde este punto se ofrece reanudar; antes se vuelve a empezar desde el principio (ajuste «Reanudar solo pasados»; ver [Tuning]). */
    @Volatile internal var minResumeMs = 30_000L
    private const val FINISHED_RATIO = 0.95
    private const val END_REMAINING_MS = 120_000L
    private const val TAG = "KaizenProgress"
    private const val KEY_PREFIX = "s:"
    private const val FICHA_PREFIX = "p:"

    /** Junto a cada progreso va, con la clave «t:clave», la hora (ms) de su último cambio: con ella gana el más reciente entre dos dispositivos. Sin ella (progreso de antes) se vale la posición mayor. */
    private const val TIME_PREFIX = "t:"
    private val MAX_FUTURE = 1.days

    /** El capítulo de una serie: [seriesId] (0 = el libro aún no la conoce) y [number]. Es lo que identifica el progreso (y no cambia con el hash del eid). */
    data class Key(val seriesId: Long, val number: Double) {
        val isValid: Boolean get() = seriesId > 0 && number >= 0

        /** La clave guardada en el fichero: «s:serie#número» (el número entero sin decimales). */
        val text: String get() = "$KEY_PREFIX$seriesId#${numberText(number)}"

        companion object {
            fun parse(text: String): Key? {
                if (!text.startsWith(KEY_PREFIX)) return null
                val body = text.removePrefix(KEY_PREFIX)
                val hash = body.indexOf('#')
                if (hash <= 0) return null
                val id = body.substring(0, hash).toLongOrNull() ?: return null
                val number = body.substring(hash + 1).toDoubleOrNull() ?: return null
                return Key(id, number)
            }
        }
    }

    internal fun numberText(number: Double) = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

    private val prefs get() = app.kaizen.commons.KaizenStore.prefs("kaizen_progress")

    /**
     * Copia en memoria del fichero (eid → «posición/duración», más las claves antiguas): todo lo que se lee sale de aquí y el disco se
     * escribe aparte, en un hilo de fondo con commit(). Con apply(), Android espera esas escrituras en el hilo principal al cambiar de
     * pantalla (QueuedWork.waitToFinish) y, con el disco ocupado, la pantalla principal dejaba de responder (ANR del 2026-10-27).
     */
    private val entries: ConcurrentHashMap<String, String> by lazy {
        ConcurrentHashMap<String, String>().also { map -> prefs.all.forEach { (key, value) -> if (value is String) map[key] = value } }
    }
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "kaizen-progress-writer").apply { isDaemon = true } }

    private fun persist(block: SharedPreferences.Editor.() -> Unit) {
        writer.execute { runCatching { prefs.edit().apply(block).commit() }.onFailure { android.util.Log.w(TAG, "No se pudo guardar el progreso", it) } }
    }

    private val _changes = MutableStateFlow(0L)

    /** Cambia cada vez que se guarda progreso (para refrescar «Seguir viendo»). */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    data class Progress(val positionMs: Long, val durationMs: Long) {
        private val knownDuration get() = durationMs > 0
        val fraction: Double get() = if (knownDuration) positionMs.toDouble() / durationMs else 0.0
        /** Terminado: más del 95 % visto o a menos de 2 minutos del final (créditos), así que ya no se ofrece seguir. */
        val isFinished: Boolean get() = knownDuration && (fraction >= FINISHED_RATIO || durationMs - positionMs <= END_REMAINING_MS)

        /** Empezado y sin terminar; con duración desconocida no se considera en curso. */
        val isInProgress: Boolean get() = knownDuration && positionMs >= MIN_LISTED_MS && !isFinished
        val isResumable: Boolean get() = positionMs >= minResumeMs && !isFinished
    }

    /** Qué hacer con el progreso al salir del reproductor. */
    internal enum class SaveAction { STORE, REMOVE, KEEP }

    /**
     * Terminado: se borra (para que el fichero no crezca). Salir casi al empezar no guarda nada, y salir pronto
     * ([minResumeMs]) en un capítulo que ya tenía progreso para reanudar conserva el anterior (antes lo pisaba y lo quitaba
     * de «Seguir viendo»). Si no, se guarda la posición.
     */
    internal fun planSave(previous: Progress?, current: Progress): SaveAction = when {
        current.isFinished -> SaveAction.REMOVE
        current.positionMs < MIN_LISTED_MS -> SaveAction.KEEP
        current.positionMs < minResumeMs && previous?.isResumable == true -> SaveAction.KEEP
        // Sin duración conocida (el reproductor aún prepara, tras un seekTo) una posición no es fiable y quitaría el capítulo de la fila.
        current.durationMs <= 0 -> SaveAction.KEEP
        else -> SaveAction.STORE
    }

    /**
     * Guarda el progreso según [planSave], con la clave de la ficha. Si no hay ficha ([key] nula o incompleta) se guarda con el eid
     * (clave antigua) para no perderlo. Una clave antigua del mismo capítulo se quita al guardar con la nueva.
     */
    fun save(key: Key?, eid: Int, positionMs: Long, durationMs: Long) {
        val stored = key?.takeIf { it.isValid }?.text ?: eid.takeIf { it > 0 }?.toString()
        if (stored == null) {
            android.util.Log.w(TAG, "Sin ficha ni eid: no se guarda el progreso (pos=$positionMs)")
            return
        }
        val progress = Progress(positionMs, durationMs)
        when (planSave(read(stored, eid), progress)) {
            SaveAction.KEEP -> return
            SaveAction.REMOVE -> forgetAll(stored, eid)
            SaveAction.STORE -> {
                store(stored, "$positionMs/$durationMs")
                if (stored != eid.toString() && eid > 0) forget(eid.toString())
            }
        }
        _changes.value = System.currentTimeMillis()
    }

    /** Lo guardado con [text] o, si no hay, con la clave antigua de [eid]. */
    private fun read(text: String, eid: Int): Progress? = (entries[text] ?: entries[eid.toString()])?.let(::parse)

    private fun forgetAll(text: String, eid: Int) {
        forget(text)
        if (eid > 0) forget(eid.toString())
    }

    /** La hora del último cambio de [key]; 0 si no consta (progreso de antes de que se guardara). */
    private fun timeOf(key: String): Long = entries["$TIME_PREFIX$key"]?.toLongOrNull() ?: 0L

    private fun store(key: String, value: String, at: Long = System.currentTimeMillis()) {
        entries[key] = value
        if (at > 0) entries["$TIME_PREFIX$key"] = at.toString() else entries.remove("$TIME_PREFIX$key")
        persist { putString(key, value); if (at > 0) putString("$TIME_PREFIX$key", at.toString()) else remove("$TIME_PREFIX$key") }
    }

    private fun forget(key: String) {
        entries.remove(key)
        entries.remove("$TIME_PREFIX$key")
        persist { remove(key); remove("$TIME_PREFIX$key") }
    }

    /**
     * ¿Se queda el progreso de otro dispositivo en lugar del de aquí? Si los dos traen su hora gana el más reciente (una hora de más de un día en el futuro, de un reloj roto,
     * no cuenta); si falta alguna se vale la posición mayor, como antes. Sin progreso aquí, el ajeno entra siempre.
     */
    internal fun takeIncoming(localValue: String?, localAt: Long, incomingValue: String, incomingAt: Long, now: Long = System.currentTimeMillis()): Boolean {
        val incoming = parse(incomingValue) ?: return false
        val local = localValue?.let(::parse) ?: return true
        val theirs = incomingAt.takeIf { it in 1..(now + MAX_FUTURE.inWholeMilliseconds) } ?: 0L
        val mine = localAt.takeIf { it in 1..(now + MAX_FUTURE.inWholeMilliseconds) } ?: 0L
        return if (theirs > 0 && mine > 0 && theirs != mine) theirs > mine else incoming.positionMs > local.positionMs
    }

    /** Las claves de progreso son series («s:…»), fichas («p:…», versión anterior) o eids de versiones anteriores; el resto del fichero son marcas internas. */
    private fun isProgressKey(key: String) = key.startsWith(KEY_PREFIX) || key.startsWith(FICHA_PREFIX) || key.toIntOrNull() != null

    /** Todo el progreso guardado (clave → «posición/duración»), para la copia de seguridad. */
    fun exportAll(): Map<String, String> = entries.filterKeys(::isProgressKey).toMap()

    /**
     * Como [exportAll] pero sin ids locales: el progreso por serie («s:…») sale con la clave de una de sus fichas ([fichaOf] da su fuente y
     * slug), que es lo que otro equipo entiende ([migrateFichaKeys] la devuelve a su serie).
     */
    fun exportPortable(fichaOf: (seriesId: Long) -> Pair<String, String>?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((text, value) in exportAll()) {
            val key = Key.parse(text)
            val ficha = key?.let { fichaOf(it.seriesId) }
            val portable = if (key != null && ficha != null) "$FICHA_PREFIX${ficha.first}:${ficha.second}#${numberText(key.number)}" else text
            if (portable.startsWith(KEY_PREFIX)) continue // un id local sin fichas no significa nada en otro equipo
            out[portable] = value
            // La hora del cambio viaja con la clave «t:…»: los dispositivos con la versión anterior no la reconocen y la ignoran.
            timeOf(text).takeIf { it > 0 }?.let { out["$TIME_PREFIX$portable"] = it.toString() }
        }
        return out
    }

    /** Añade el progreso de [saved] que no existe aquí; devuelve cuántos añadió. */
    fun importMissing(saved: Map<String, String>): Int {
        val missing = saved.filter { (key, value) -> isProgressKey(key) && !entries.containsKey(key) && parse(value) != null }
        if (missing.isEmpty()) return 0
        val times = missing.keys.mapNotNull { key -> saved["$TIME_PREFIX$key"]?.toLongOrNull()?.takeIf { it > 0 }?.let { "$TIME_PREFIX$key" to it.toString() } }.toMap()
        entries.putAll(missing)
        entries.putAll(times)
        persist { missing.forEach { (key, value) -> putString(key, value) }; times.forEach { (key, value) -> putString(key, value) } }
        _changes.value = System.currentTimeMillis()
        return missing.size
    }

    /** Quita el capítulo de «Seguir viendo» (también su clave antigua por eid). */
    fun remove(key: Key, eid: Int) {
        forgetAll(key.text, eid)
        _changes.value = System.currentTimeMillis()
    }

    /** Los capítulos con progreso: (id de serie, número) de las claves «s:» y (ficha «fuente:slug», número) de las «p:» de versiones anteriores, para la sincronización. */
    fun chapters(): Pair<List<Pair<Long, Double>>, List<Pair<String, Double>>> {
        val series = entries.keys.mapNotNull { Key.parse(it) }.map { it.seriesId to it.number }
        val fichas = entries.keys.filter { it.startsWith(FICHA_PREFIX) }.mapNotNull { text ->
            val body = text.removePrefix(FICHA_PREFIX)
            val number = body.substringAfterLast('#', "").toDoubleOrNull() ?: return@mapNotNull null
            body.substringBeforeLast('#') to number
        }
        return series to fichas
    }

    /** La hora del último cambio del progreso de un capítulo (por serie o por ficha); 0 si no hay o no consta. */
    fun latestChange(seriesId: Long?, ficha: String, number: Double): Long {
        val keys = listOfNotNull(seriesId?.takeIf { it > 0 }?.let { Key(it, number).text }) + "$FICHA_PREFIX$ficha#${numberText(number)}"
        return keys.maxOfOrNull { timeOf(it) } ?: 0L
    }

    /** Quita el progreso de un capítulo por lo que dice la sincronización (una lápida de otro dispositivo); false si aquí no había. */
    fun removeChapter(seriesId: Long?, fichas: Collection<String>, number: Double): Boolean {
        val texts = listOfNotNull(seriesId?.takeIf { it > 0 }?.let { Key(it, number).text }) + fichas.map { "$FICHA_PREFIX$it#${numberText(number)}" }
        val present = texts.filter { entries.containsKey(it) }
        if (present.isEmpty()) return false
        present.forEach(::forget)
        _changes.value = System.currentTimeMillis()
        return true
    }

    /** El progreso del capítulo [key]; si solo existe el guardado por [eid] (versiones anteriores), ese. */
    fun get(key: Key, eid: Int): Progress? = read(key.text, eid)

    /**
     * Pasa a la clave de la ficha el progreso guardado por eid, con los registros del historial que tienen ese eid. Idempotente; lo que no
     * tiene registro se queda con su clave antigua (se sigue leyendo al pedirlo con su eid). No pisa un progreso que ya exista con la clave nueva.
     */
    fun migrateEidKeys(records: List<Record>) {
        val legacy = entries.keys.filter { it.toIntOrNull() != null }
        if (legacy.isEmpty() || records.isEmpty()) return
        val byEid = records.groupBy { it.eid }
        var moved = false
        for (old in legacy) {
            val record = byEid[old.toInt()]?.firstOrNull { it.progressKey().isValid } ?: continue
            val value = entries[old] ?: continue
            val key = record.progressKey().text
            if (!entries.containsKey(key)) store(key, value, at = 0L)
            forget(old)
            moved = true
        }
        if (moved) _changes.value = System.currentTimeMillis()
    }

    /**
     * Pasa a la clave de la serie el progreso guardado por ficha («p:fuente:slug#número»). [seriesOf] da el id de la serie de una ficha (0 = no
     * se conoce: se deja como está). Si ya hay progreso con la clave nueva se conserva el que tenga más posición. Idempotente.
     */
    fun migrateFichaKeys(seriesOf: (source: String, slug: String) -> Long) {
        val old = entries.keys.filter { it.startsWith(FICHA_PREFIX) }
        var moved = false
        for (text in old) {
            val body = text.removePrefix(FICHA_PREFIX)
            val colon = body.indexOf(':')
            val hash = body.lastIndexOf('#')
            if (colon <= 0 || hash <= colon + 1) continue
            val number = body.substring(hash + 1).toDoubleOrNull() ?: continue
            val id = seriesOf(body.substring(0, colon), body.substring(colon + 1, hash)).takeIf { it > 0 } ?: continue
            val value = entries[text] ?: continue
            val key = Key(id, number).text
            if (takeIncoming(entries[key], timeOf(key), value, timeOf(text))) store(key, value, at = timeOf(text))
            forget(text)
            moved = true
        }
        if (moved) _changes.value = System.currentTimeMillis()
    }

    /**
     * Pasa el progreso de series unidas a otra a la clave de la serie vigente ([resolve] da el id vigente de un id). Si ya hay progreso del
     * mismo capítulo en la vigente se conserva el de más posición. Idempotente.
     */
    fun rekey(resolve: (Long) -> Long) {
        var moved = false
        for (text in entries.keys.filter { it.startsWith(KEY_PREFIX) }) {
            val key = Key.parse(text) ?: continue
            val current = resolve(key.seriesId)
            if (current == key.seriesId) continue
            val value = entries[text] ?: continue
            val target = Key(current, key.number).text
            if (takeIncoming(entries[target], timeOf(target), value, timeOf(text))) store(target, value, at = timeOf(text))
            forget(text)
            moved = true
        }
        if (moved) _changes.value = System.currentTimeMillis()
    }

    /** Cuántos capítulos hay en curso, con la clave de ficha o con la antigua por eid (también los que aún no tienen registro del historial). */
    fun inProgressCount(): Int = entries.count { (key, value) -> isProgressKey(key) && parse(value)?.isInProgress == true }

    /** El progreso del capítulo [key] solo si está en curso (empezado y sin terminar), con la clave de ficha o con la antigua por [eid]. */
    fun startedOf(key: Key, eid: Int): Progress? = get(key, eid)?.takeIf { it.isInProgress }

    /** Todos los capítulos en curso, por serie y número (lo guardado por eid o por ficha sin migrar no sale: ver [migrateEidKeys], [migrateFichaKeys]). */
    fun inProgress(): Map<Key, Progress> = entries.mapNotNull { (text, value) ->
        val key = Key.parse(text) ?: return@mapNotNull null
        val progress = parse(value)?.takeIf { it.isInProgress } ?: return@mapNotNull null
        key to progress
    }.toMap()

    private fun parse(raw: String): Progress? {
        val parts = raw.split('/')
        val position = parts.getOrNull(0)?.toLongOrNull() ?: return null
        return Progress(position, parts.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }
}
